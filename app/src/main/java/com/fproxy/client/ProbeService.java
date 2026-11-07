package com.fproxy.client;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import io.nekohasekai.libbox.CommandServer;
import io.nekohasekai.libbox.CommandServerHandler;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.OverrideOptions;
import io.nekohasekai.libbox.SystemProxyStatus;
import io.nekohasekai.libbox.TunOptions;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

public class ProbeService extends Service implements CommandServerHandler {
    public static final String URL = "https://www.gstatic.com/generate_204";
    private static final int PROBE_CONCURRENCY = 4;
    private String current = "";
    private volatile boolean destroyed;
    private String finishMessage = "";
    private final Handler main = new Handler(Looper.getMainLooper());
    private PlatformBridge platform;
    private String probeUrl = URL;
    private volatile boolean resourcesClosed;
    private JSONObject results = new JSONObject();
    private boolean running;
    private JSONObject snapshot = new JSONObject();
    private volatile boolean stopped;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService probePool = Executors.newFixedThreadPool(PROBE_CONCURRENCY);
    private final AtomicInteger completed = new AtomicInteger(0);
    private final Set<HttpsURLConnection> liveRequests =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private void close() {
        if (platform != null) {
            platform.closeDefaultInterfaceMonitor(null);
        }
    }

    private static int allocateLocalPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private synchronized void result(String nodeId, String status, long delay, String detail) {
        if (nodeId == null || nodeId.isEmpty()) {
            return;
        }
        try {
            JSONObject item = new JSONObject()
                    .put("status", status)
                    .put("delay", delay)
                    .put("detail", CrashDiary.redact(detail))
                    .put("time", System.currentTimeMillis());
            results.put(nodeId, item);
        } catch (Exception ignored) {
        }
    }

    private void probeOne(JSONObject entry, CountDownLatch latch) {
        String id = "";
        try {
            id = entry.getString("id");
        } catch (Exception ignored) {
        }
        if (id.isEmpty()) {
            latch.countDown();
            return;
        }
        final String nodeId = id;
        try {
            if (stopped) {
                result(nodeId, "cancelled", 0L, "已取消");
                return;
            }
            current = nodeId;
            result(nodeId, "testing", 0L, "");
            String tag = entry.getJSONObject("config").optString("tag", "节点");
            save(true, "正在测试 " + tag);

            JSONObject nodeConfig = entry.getJSONObject("config");
            // 生成一次性本地代理鉴权凭证，防止测速期间本机其他应用未授权连接 127.0.0.1 端口
            String probeUser = "fprobe";
            String probePass = UUID.randomUUID().toString();
            String basicAuth = "Basic " + Base64.encodeToString(
                    (probeUser + ":" + probePass).getBytes(StandardCharsets.UTF_8),
                    Base64.NO_WRAP);

            // 每个并发任务使用独立的 CommandServer：上游 libbox 每个实例自带独立 service，
            // 端口为 0 时走进程内 Unix socket（command.sock，仅供外部 gRPC 客户端，测速不用），
            // 多实例并存不冲突；共享一个 PlatformBridge 只做 socket 保护与接口查询
            CommandServer taskServer = new CommandServer(this, platform);
            try {
                int port = 0;
                boolean startedProbe = false;
                // 增加端口绑定重试，消除 ServerSocket 关闭与 sing-box 监听之间的 TOCTOU 竞争失败
                for (int attempt = 0; attempt < 3 && !stopped; attempt++) {
                    port = allocateLocalPort();
                    String config = SingConfig.probe(nodeConfig, port, probeUser, probePass);
                    Libbox.checkConfig(config);
                    try {
                        taskServer.startOrReloadService(config, new OverrideOptions());
                        startedProbe = true;
                        break;
                    } catch (Exception bindErr) {
                        if (attempt == 2) {
                            throw bindErr;
                        }
                    }
                }

                if (!startedProbe || stopped) {
                    if (stopped) {
                        result(nodeId, "cancelled", 0L, "已取消");
                    }
                    return;
                }

                long startMs = SystemClock.elapsedRealtime();
                URL url = new URL(probeUrl);
                Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", port));
                HttpsURLConnection conn = (HttpsURLConnection) url.openConnection(proxy);
                liveRequests.add(conn);
                try {
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setUseCaches(false);
                    conn.setInstanceFollowRedirects(false);
                    conn.setRequestProperty("Proxy-Authorization", basicAuth);
                    conn.setRequestProperty("Connection", "close");

                    int code = conn.getResponseCode();
                    if (code == 204) {
                        long elapsed = Math.max(1L, SystemClock.elapsedRealtime() - startMs);
                        result(nodeId, "ok", elapsed, "HTTPS 204 · " + probeUrl);
                    } else {
                        throw new IOException("测试地址返回 HTTP " + code + "，未通过 204 校验");
                    }
                } finally {
                    liveRequests.remove(conn);
                    conn.disconnect();
                }
            } finally {
                try {
                    taskServer.closeService();
                } catch (Exception ignored) {
                }
                try {
                    taskServer.close();
                } catch (Exception ignored) {
                }
            }
        } catch (Throwable t) {
            String status = stopped ? "cancelled" : "failed";
            String detail = stopped
                    ? "已取消"
                    : t.getClass().getSimpleName() + "：" + String.valueOf(t.getMessage());
            result(nodeId, status, 0L, detail);
        } finally {
            completed.incrementAndGet();
            save(true, "已完成 " + completed.get() + " 个节点");
            latch.countDown();
        }
    }

    private void runTests(String targetNodeId) {
        try {
            CoreRuntime.setup(this, "probe");
            platform = new PlatformBridge(
                    this,
                    fd -> platform.bindPhysical(fd),
                    (TunOptions options) -> {
                        throw new UnsupportedOperationException("Probe must not create a VPN");
                    },
                    null
            );
            NodeStore store = new NodeStore(this);
            JSONArray nodes = store.nodes();
            List<JSONObject> targets = new ArrayList<>();
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject entry = nodes.getJSONObject(i);
                String id = entry.getString("id");
                if (targetNodeId != null && !targetNodeId.isEmpty() && !targetNodeId.equals(id)) {
                    continue;
                }
                targets.add(entry);
            }

            CountDownLatch latch = new CountDownLatch(targets.size());
            for (JSONObject entry : targets) {
                if (stopped) {
                    latch.countDown();
                    continue;
                }
                probePool.execute(() -> probeOne(entry, latch));
            }
            latch.await();

            finishMessage = stopped ? "测速已停止" : "测速完成，共 " + completed.get() + " 个节点";
        } catch (Throwable t) {
            CrashDiary.record(this, "测速内核", t);
            result(current, "failed", 0L, t.toString());
            finishMessage = "测速启动失败：" + CrashDiary.redact(t.toString());
        } finally {
            close();
            resourcesClosed = true;
            main.post(() -> {
                stopSelf();
                if (destroyed) {
                    save(false, finishMessage);
                }
            });
        }
    }

    private synchronized void save(boolean isRunning, String message) {
        try {
            snapshot.put("running", isRunning)
                    .put("pid", Process.myPid())
                    .put("message", message)
                    .put("current", current)
                    .put("results", results);
            RunState.write(this, "probe-state", snapshot);
        } catch (Exception ignored) {
        }
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
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        CrashDiary.install(this, "测速");
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        stopped = true;
        for (HttpsURLConnection req : liveRequests) {
            try {
                req.disconnect();
            } catch (Exception ignored) {
            }
        }
        probePool.shutdownNow();
        worker.shutdown();
        if (resourcesClosed) {
            save(false, finishMessage);
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) {
            return START_NOT_STICKY;
        }
        running = true;
        stopped = false;
        snapshot = RunState.read(this, "probe-state");
        results = snapshot.optJSONObject("results");
        if (results == null) {
            results = new JSONObject();
        }
        save(true, "正在初始化真实连接测试");
        try {
            FNotification.show(this, 34, "正在测试节点真实连接");
            // 仅 Debug 构建接受本地测试地址（instrumentation 测试用 10.0.2.2:18443）
            if (BuildConfig.DEBUG && intent != null
                    && "https://10.0.2.2:18443/".equals(intent.getStringExtra("test_url"))) {
                probeUrl = intent.getStringExtra("test_url");
            }
            String targetNode = intent == null ? "" : intent.getStringExtra("node");
            worker.execute(() -> runTests(targetNode));
        } catch (Throwable t) {
            CrashDiary.record(this, "测速前台通知", t);
            save(false, "启动失败：" + CrashDiary.redact(t.toString()));
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void serviceReload() throws Exception {
        throw new UnsupportedOperationException("Disabled");
    }

    @Override
    public void serviceStop() {
        stopped = true;
        main.post(this::stopSelf);
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
        if (BuildConfig.DEBUG && message != null) {
            Log.d("F-Probe", CrashDiary.redact(message));
        }
    }
}
