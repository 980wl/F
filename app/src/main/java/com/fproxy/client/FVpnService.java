package com.fproxy.client;

import android.content.Intent;
import android.net.Network;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import io.nekohasekai.libbox.CommandServer;
import io.nekohasekai.libbox.CommandServerHandler;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.OverrideOptions;
import io.nekohasekai.libbox.RoutePrefix;
import io.nekohasekai.libbox.RoutePrefixIterator;
import io.nekohasekai.libbox.StringIterator;
import io.nekohasekai.libbox.SystemProxyStatus;
import io.nekohasekai.libbox.TunOptions;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

public class FVpnService extends VpnService implements CommandServerHandler {
    public static final String ACTION_STOP = "STOP";
    private boolean cleanupQueued;
    private volatile boolean destroyed;
    private AppDirectPolicy.Snapshot directPolicy;
    private String lastError = "";
    private final Handler main = new Handler(Looper.getMainLooper());
    private String nodeId = "";
    private PlatformBridge platform;
    private volatile boolean resourcesClosed;
    private CommandServer server;
    private volatile boolean started;
    private volatile boolean stopping;
    private ParcelFileDescriptor tun;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private void fail(String stage, Throwable throwable) {
        String safeError = CrashDiary.redact(stage + "：" + throwable);
        this.lastError = safeError;
        CrashDiary.record(this, stage, throwable);
        state("failed");
    }

    private int openTun(TunOptions options) throws Exception {
        if (stopping) {
            throw new IllegalStateException("VPN 正在关闭");
        }
        if (prepare(this) != null) {
            throw new SecurityException("VPN 尚未获得授权");
        }
        if (tun != null) {
            tun.close();
            tun = null;
        }
        Builder builder = new Builder()
                .setSession("F · 大陆 IP 与国内应用直连")
                .setMtu(options.getMTU());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false);
        }

        RoutePrefixIterator inet4 = options.getInet4Address();
        RoutePrefixIterator inet6 = options.getInet6Address();
        while (inet4.hasNext()) {
            RoutePrefix prefix = inet4.next();
            builder.addAddress(prefix.address(), prefix.prefix());
        }
        while (inet6.hasNext()) {
            RoutePrefix prefix = inet6.next();
            builder.addAddress(prefix.address(), prefix.prefix());
        }

        StringIterator dnsServers = options.getDNSServerAddress();
        while (dnsServers.hasNext()) {
            builder.addDnsServer(dnsServers.next());
        }

        builder.addRoute("0.0.0.0", 0)
                .addRoute("::", 0);

        if (directPolicy == null) {
            throw new IllegalStateException("应用直连策略未初始化");
        }

        JSONArray excludedPackages = new JSONArray();
        for (String pkg : directPolicy.excluded) {
            try {
                builder.addDisallowedApplication(pkg);
                excludedPackages.put(pkg);
            } catch (Exception ignored) {
                if (BuildConfig.DEBUG) {
                    Log.i("F-Core", "Application removed during startup: " + pkg);
                }
            }
        }

        JSONObject activeDirect = new JSONObject()
                .put("packages", excludedPackages)
                .put("browser_count", directPolicy.browsers);
        RunState.write(this, "app-direct-active", activeDirect);

        tun = builder.establish();
        if (tun == null) {
            throw new IllegalStateException("Android VPN 建立失败");
        }
        return tun.getFd();
    }

    private void requestStop() {
        if (cleanupQueued) {
            return;
        }
        cleanupQueued = true;
        stopping = true;
        if (lastError.isEmpty()) {
            state("stopping");
        }
        worker.execute(() -> {
            if (server != null) {
                try {
                    server.closeService();
                } catch (Throwable t) {
                    CrashDiary.record(this, "关闭内核", t);
                }
                try {
                    server.close();
                } catch (Throwable t) {
                    CrashDiary.record(this, "关闭控制服务", t);
                }
                server = null;
            }
            if (platform != null) {
                platform.closeDefaultInterfaceMonitor(null);
            }
            if (tun != null) {
                try {
                    tun.close();
                } catch (Exception ignored) {
                }
                tun = null;
            }
            resourcesClosed = true;
            main.post(() -> {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                if (destroyed) {
                    state(lastError.isEmpty() ? "idle" : "failed");
                }
            });
        });
        worker.shutdown();
    }

    private void state(String phase) {
        RunState.vpn(this, phase, lastError, nodeId);
    }

    @Override
    public int connectSSHAgent() {
        return -1;
    }

    @Override
    public SystemProxyStatus getSystemProxyStatus() {
        return new SystemProxyStatus();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        CrashDiary.install(this, "VPN");
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        requestStop();
        if (resourcesClosed) {
            state(lastError.isEmpty() ? "idle" : "failed");
        }
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        lastError = "系统撤销了 VPN 授权";
        requestStop();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            requestStop();
            return START_NOT_STICKY;
        }
        if (started) {
            return START_NOT_STICKY;
        }
        started = true;
        stopping = false;
        state("starting");
        try {
            FNotification.show(this, 33, "正在连接");
        } catch (Throwable t) {
            fail("创建前台通知", t);
            requestStop();
            return START_NOT_STICKY;
        }

        worker.execute(() -> {
            String stage = "初始化内核";
            try {
                CoreRuntime.setup(this);
                NodeStore store = new NodeStore(this);
                nodeId = store.selected();

                stage = "识别应用直连规则";
                directPolicy = AppDirectPolicy.scan(this);
                if (!directPolicy.conflict.isEmpty()) {
                    throw new IllegalStateException(directPolicy.conflict);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isLockdownEnabled()) {
                    throw new IllegalStateException("请关闭系统 VPN 设置中的‘阻止未使用 VPN 的连接’，否则直连应用会断网。");
                }

                stage = "载入中国大陆分流规则";
                JSONObject selectedNode = store.selectedConfig();
                String geoipPath = RuleResources.mainland(this);
                String geositePath = RuleResources.geositeCn(this);
                String config = SingConfig.mainlandSplit(selectedNode, geoipPath, geositePath);

                stage = "验证节点配置";
                Libbox.checkConfig(config);
                if (stopping) {
                    return;
                }

                platform = new PlatformBridge(
                        this,
                        fd -> {
                            if (!protect(fd)) {
                                throw new IOException("内核 socket 保护失败");
                            }
                        },
                        this::openTun,
                        (Network[] networks) -> setUnderlyingNetworks(networks)
                );

                stage = "创建内核服务";
                server = new CommandServer(this, platform);

                stage = "启动 VPN";
                server.startOrReloadService(config, new OverrideOptions());
                if (tun == null) {
                    throw new IllegalStateException("内核没有建立 VPN 接口");
                }
                if (!stopping) {
                    state("connected");
                    main.post(() -> {
                        if (!stopping) {
                            try {
                                FNotification.show(this, 33, "大陆直连 / 境外代理");
                            } catch (Throwable t) {
                                fail("更新通知", t);
                                requestStop();
                            }
                        }
                    });
                }
            } catch (Throwable t) {
                fail(stage, t);
                main.post(this::requestStop);
            }
        });
        return START_NOT_STICKY;
    }

    @Override
    public void serviceReload() throws Exception {
        throw new UnsupportedOperationException("请断开后重新连接");
    }

    @Override
    public void serviceStop() {
        main.post(this::requestStop);
    }

    @Override
    public void setSystemProxyEnabled(boolean isEnabled) {
    }

    @Override
    public void triggerNativeCrash() throws Exception {
        throw new UnsupportedOperationException("Disabled");
    }

    @Override
    public void writeDebugMessage(String message) {
        // 仅在 Debug 构建中输出脱敏后的内核日志，防止正式版向系统 logcat 泄露连接详情
        if (BuildConfig.DEBUG && message != null) {
            Log.d("F-Core", CrashDiary.redact(message));
        }
    }
}
