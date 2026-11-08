package com.fproxy.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int APP_PAGE_SIZE = 40;
    private static final int DARK = 0xff202632;
    private static final int MUTED = 0xff7a8291;
    private static final int RED = 0xffd3222a;
    private static final int REQUEST_VPN = 501;
    private static final int REQUEST_EXPORT = 502;
    private static final int REQUEST_IMPORT = 503;
    private static final int REQUEST_INSTALL = 504;

    private NodeAdapter adapter;
    private int appLimit;
    private AppDirectPolicy.Snapshot appPolicy;
    private LinearLayout appPolicyPanel;
    private LinearLayout appPolicyRows;
    private EditText appSearch;

    // 使用 AtomicBoolean + 共享线程池替代裸 new Thread() 与非线程安全布尔标志；
    // 状态轮询单独走一个单线程池，避免被后台长任务（更新全部）饿死
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicBoolean scanningApps = new AtomicBoolean(false);
    private final AtomicBoolean pollingState = new AtomicBoolean(false);
    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService pollExecutor = Executors.newSingleThreadExecutor();

    private TextView detail;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private JSONArray items = new JSONArray();
    private TextView logs;
    private JSONObject measurements = new JSONObject();
    private Button moreApps;
    private TextView nodeCount;
    private String oldPhase = "";
    private JSONObject pendingUpdate;
    private JSONObject probe = new JSONObject();
    private TextView probeInfo;
    private long probeVersion = -1L;
    private long lastVpnFileMod = -1L;
    private long lastProbeFileMod = -1L;
    private Button refresh;
    private TextView status;
    private NodeStore store;
    private LinearLayout subscriptions;
    private Button testAll;
    private boolean testing;
    private Button toggle;
    private EditText updateUrl;
    private JSONObject vpn = new JSONObject();

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!isFinishing()) {
                readStatesAsync();
                handler.postDelayed(this, 1000L);
            }
        }
    };

    private final Runnable debouncedRenderApps = this::renderAppPolicy;

    interface Work {
        String run() throws Exception;
    }

    /**
     * 使用 ViewHolder 模式复用 NodeAdapter 列表项视图，消除每次轮询/刷新时重复 new View 与 GC 卡顿。
     */
    private static final class NodeViewHolder {
        LinearLayout root;
        LinearLayout card;
        TextView titleView;
        TextView delayView;
        TextView endpointView;
        TextView selectHintView;
        Button configButton;
        Button testButton;
        Button deleteButton;
        TextView errorDetailView;
    }

    private class NodeAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.length();
        }

        @Override
        public Object getItem(int position) {
            return items.optJSONObject(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            NodeViewHolder holder;
            if (convertView == null || !(convertView.getTag() instanceof NodeViewHolder)) {
                holder = new NodeViewHolder();
                holder.root = vertical();
                holder.root.setPadding(0, dp(4f), 0, dp(4f));

                holder.card = vertical();
                holder.card.setPadding(dp(12f), dp(12f), dp(12f), dp(10f));
                holder.root.addView(holder.card);

                LinearLayout topRow = row();
                holder.titleView = text("", 15, DARK);
                holder.titleView.setTypeface(null, Typeface.BOLD);
                topRow.addView(holder.titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                holder.delayView = text("", 13, MUTED);
                holder.delayView.setGravity(Gravity.END);
                topRow.addView(holder.delayView, new LinearLayout.LayoutParams(dp(84f), ViewGroup.LayoutParams.WRAP_CONTENT));
                holder.card.addView(topRow);

                holder.endpointView = text("", 11, MUTED);
                holder.card.addView(holder.endpointView);
                gap(holder.card, 7);

                LinearLayout actionRow = row();
                holder.selectHintView = text("", 11, MUTED);
                actionRow.addView(holder.selectHintView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                holder.testButton = button("测速", false);
                holder.configButton = button("配置", false);
                holder.deleteButton = button("删除", false);
                actionRow.addView(holder.testButton, new LinearLayout.LayoutParams(dp(65f), dp(35f)));
                LinearLayout.LayoutParams cfgParams = new LinearLayout.LayoutParams(dp(65f), dp(35f));
                cfgParams.leftMargin = dp(7f);
                actionRow.addView(holder.configButton, cfgParams);
                LinearLayout.LayoutParams delParams = new LinearLayout.LayoutParams(dp(65f), dp(35f));
                delParams.leftMargin = dp(7f);
                actionRow.addView(holder.deleteButton, delParams);
                holder.card.addView(actionRow);

                holder.errorDetailView = text("", 10, RED);
                holder.errorDetailView.setTextIsSelectable(true);
                holder.errorDetailView.setVisibility(View.GONE);
                holder.card.addView(holder.errorDetailView);

                convertView = holder.root;
                convertView.setTag(holder);
            } else {
                holder = (NodeViewHolder) convertView.getTag();
            }

            JSONObject item = items.optJSONObject(position);
            if (item == null) {
                return convertView;
            }
            final JSONObject config = item.optJSONObject("config");
            final String id = item.optString("id");
            boolean isSelected = id.equals(store.selected());

            GradientDrawable cardBg = bg(isSelected ? 0xfffff3f3 : 0xffffffff, 12);
            if (isSelected) {
                cardBg.setStroke(dp(1f), RED);
            }
            holder.card.setBackground(cardBg);

            String serverHost = config == null ? "节点" : config.optString("server", "节点");
            String tag = config == null ? serverHost : config.optString("tag", serverHost);
            holder.titleView.setText((isSelected ? "●  " : "○  ") + tag);
            holder.titleView.setTextColor(isSelected ? RED : DARK);

            JSONObject m = measurements.optJSONObject(id);
            String statusStr = m == null ? "" : m.optString("status");
            String delayText;
            int delayColor = MUTED;
            if ("ok".equals(statusStr)) {
                delayText = m.optLong("delay") + " ms";
                delayColor = 0xff138052;
            } else if ("testing".equals(statusStr)) {
                delayText = testing ? "测速中…" : "测速中断";
            } else if ("failed".equals(statusStr)) {
                delayText = "未连通";
                delayColor = RED;
            } else if ("cancelled".equals(statusStr)) {
                delayText = "已取消";
            } else {
                delayText = "未测速";
            }
            holder.delayView.setText(delayText);
            holder.delayView.setTextColor(delayColor);

            String type = config == null ? "" : config.optString("type");
            int port = config == null ? 0 : config.optInt("server_port");
            holder.endpointView.setText(type + "  ·  " + serverHost + ":" + port);

            holder.selectHintView.setText(isSelected ? "当前选择" : "点击此卡片选择");
            holder.testButton.setEnabled(!testing);
            holder.testButton.setOnClickListener(v -> probeNode(id));
            holder.configButton.setOnClickListener(v -> openNodeConfig(id));
            holder.deleteButton.setOnClickListener(v -> deleteNode(id, tag));

            if (m != null && "failed".equals(statusStr)) {
                holder.errorDetailView.setText(m.optString("detail"));
                holder.errorDetailView.setVisibility(View.VISIBLE);
            } else {
                holder.errorDetailView.setVisibility(View.GONE);
            }

            holder.card.setOnClickListener(v -> {
                if (RunState.active(vpn)) {
                    detail.setText("请先断开连接，再切换节点。");
                    return;
                }
                try {
                    store.select(id);
                    notifyDataSetChanged();
                } catch (Exception e) {
                    error(e);
                }
            });

            return convertView;
        }
    }

    private void addSubscriptionDialog() {
        LinearLayout layout = vertical();
        EditText nameInput = new EditText(this);
        EditText urlInput = new EditText(this);
        nameInput.setHint("订阅名称");
        urlInput.setHint("HTTPS 订阅地址");
        urlInput.setSingleLine(true);
        layout.addView(nameInput);
        layout.addView(urlInput);
        new AlertDialog.Builder(this)
                .setTitle("添加订阅")
                .setView(layout)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存并更新", (d, w) -> background("正在添加订阅", () -> {
                    String url = urlInput.getText().toString().trim();
                    String subId = store.addSubscription(nameInput.getText().toString(), url);
                    store.replaceSubscription(subId, NetFetch.text(url, 2000000));
                    return "订阅添加完成";
                }))
                .show();
    }

    private void background(String label, Work work) {
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        refresh.setEnabled(false);
        detail.setText(label);
        ioExecutor.execute(() -> {
            String resultMsg;
            try {
                resultMsg = work.run();
            } catch (Throwable t) {
                CrashDiary.record(this, label, t);
                resultMsg = label + "失败：" + CrashDiary.redact(t.toString());
            }
            final String finalMsg = resultMsg;
            runOnUiThread(() -> {
                busy.set(false);
                if (isFinishing()) {
                    return;
                }
                refresh.setEnabled(true);
                refreshNodeList();
                detail.setText(finalMsg);
            });
        });
    }

    private GradientDrawable bg(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius((float) dp((float) radiusDp));
        return drawable;
    }

    private void buildUI() {
        ListView listView = new ListView(this);
        listView.setBackgroundColor(0xfff5f7fb);
        listView.setDivider(null);
        listView.setClipToPadding(false);
        listView.setPadding(dp(14f), 0, dp(14f), 0);

        LinearLayout header = vertical();
        header.setPadding(dp(3f), dp(18f), dp(3f), dp(8f));

        LinearLayout titleRow = row();
        TextView appTitle = text("F", 25, DARK);
        appTitle.setTypeface(null, Typeface.BOLD);
        titleRow.addView(appTitle);

        TextView subTitle = text("大陆直连 / 境外代理 · 内核 " + BuildConfig.CORE_VERSION + " · v" + BuildConfig.VERSION_NAME, 12, MUTED);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        subParams.leftMargin = dp(9f);
        titleRow.addView(subTitle, subParams);
        header.addView(titleRow);

        gap(header, 14);
        status = text("未连接", 23, DARK);
        status.setTypeface(null, Typeface.BOLD);
        header.addView(status);

        detail = text("点击节点选择，再连接；测速会真实访问 HTTPS。", 12, MUTED);
        detail.setTextIsSelectable(true);
        header.addView(detail);

        gap(header, 8);
        toggle = button("连接所选节点", true);
        refresh = button("更新全部", false);
        pair(header, toggle, refresh);
        toggle.setOnClickListener(v -> toggleVpn());
        refresh.setOnClickListener(v -> updateAll());

        Button importBtn = button("导入节点", false);
        Button addSubBtn = button("添加订阅", false);
        Button exportBtn = button("导出备份", false);
        Button restoreBtn = button("恢复备份", false);
        LinearLayout toolRow = row();
        for (Button b : new Button[]{importBtn, addSubBtn, exportBtn, restoreBtn}) {
            b.setTextSize(12f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42f), 1f);
            lp.setMargins(dp(2f), dp(4f), dp(2f), dp(4f));
            toolRow.addView(b, lp);
        }
        header.addView(toolRow);
        importBtn.setOnClickListener(v -> showImportDialog());
        addSubBtn.setOnClickListener(v -> addSubscriptionDialog());
        exportBtn.setOnClickListener(v -> requestExport());
        restoreBtn.setOnClickListener(v -> requestImport());

        gap(header, 10);
        LinearLayout nodeHeaderRow = row();
        nodeCount = text("节点  0", 16, DARK);
        nodeCount.setTypeface(null, Typeface.BOLD);
        nodeHeaderRow.addView(nodeCount, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        testAll = button("测速全部", false);
        Button clearAllBtn = button("全部删除", false);
        nodeHeaderRow.addView(testAll, new LinearLayout.LayoutParams(dp(90f), dp(40f)));
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(dp(84f), dp(40f));
        clearLp.leftMargin = dp(6f);
        nodeHeaderRow.addView(clearAllBtn, clearLp);
        header.addView(nodeHeaderRow);

        testAll.setOnClickListener(v -> {
            if (testing) {
                stopService(new Intent(this, ProbeService.class));
                probeInfo.setText("正在停止测速…");
            } else {
                probeNode(null);
            }
        });
        clearAllBtn.setOnClickListener(v -> clearNodes());

        probeInfo = text("测速显示 HTTPS 真连接耗时（ms），不是 TCP ping。", 11, MUTED);
        header.addView(probeInfo);
        listView.addHeaderView(header, null, false);

        LinearLayout footer = vertical();
        footer.setPadding(dp(3f), dp(14f), dp(3f), dp(24f));
        footer.addView(text("订阅", 17, DARK));
        subscriptions = vertical();
        footer.addView(subscriptions);

        gap(footer, 16);
        footer.addView(text("应用直连", 17, DARK));
        appPolicyPanel = vertical();
        appPolicyPanel.setVisibility(View.GONE);
        footer.addView(appPolicyPanel);

        Button rescanBtn = button("刷新本机应用列表", false);
        Button collapseBtn = button("收起应用列表", false);
        pair(appPolicyPanel, rescanBtn, collapseBtn);
        rescanBtn.setOnClickListener(v -> scanApps());
        collapseBtn.setOnClickListener(v -> {
            appLimit = 0;
            appPolicyPanel.setVisibility(View.GONE);
            appPolicyRows.removeAllViews();
            moreApps.setVisibility(View.VISIBLE);
        });

        appSearch = new EditText(this);
        appSearch.setTextSize(13f);
        appSearch.setSingleLine(true);
        appSearch.setHint("查找应用名称或包名");
        appPolicyPanel.addView(appSearch);

        appPolicyRows = vertical();
        appPolicyPanel.addView(appPolicyRows);

        // 搜索输入增加 200ms 防抖（Debounce），避免每输入一个字符都在主线程重建数十个 CheckBox
        appSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (appLimit == 0) {
                    return;
                }
                appLimit = APP_PAGE_SIZE;
                handler.removeCallbacks(debouncedRenderApps);
                handler.postDelayed(debouncedRenderApps, 200L);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        moreApps = button("再显示40个应用", false);
        footer.addView(moreApps);
        moreApps.setOnClickListener(v -> {
            appLimit += APP_PAGE_SIZE;
            appPolicyPanel.setVisibility(View.VISIBLE);
            if (appPolicy == null) {
                scanApps();
            } else {
                renderAppPolicy();
            }
        });

        gap(footer, 16);
        footer.addView(text("内核更新地址", 15, DARK));
        footer.addView(text("填入同签名 F 安装包的 HTTPS 更新清单地址。", 11, MUTED));
        updateUrl = new EditText(this);
        updateUrl.setTextSize(12f);
        updateUrl.setSingleLine(true);
        updateUrl.setHint("https://…/f-updates.json");
        updateUrl.setText(UpdateManager.updateSource(this));
        footer.addView(updateUrl);

        Button saveUpdateUrlBtn = button("保存更新地址", false);
        footer.addView(saveUpdateUrlBtn);
        saveUpdateUrlBtn.setOnClickListener(v -> {
            String url = updateUrl.getText().toString().trim();
            if (!url.isEmpty() && !url.startsWith("https://")) {
                message("请使用 HTTPS 地址");
                return;
            }
            SharedPreferences prefs = getSharedPreferences("updates", MODE_PRIVATE);
            prefs.edit().putString("manifest", url).apply();
            detail.setText("更新地址已保存");
        });

        gap(footer, 12);
        Button readLogBtn = button("查看 / 刷新错误日志", false);
        footer.addView(readLogBtn);
        logs = text("", 11, MUTED);
        logs.setTextIsSelectable(true);
        footer.addView(logs);
        readLogBtn.setOnClickListener(v -> logs.setText(CrashDiary.read(this)));

        listView.addFooterView(footer, null, false);
        adapter = new NodeAdapter();
        listView.setAdapter(adapter);
        setContentView(listView);

        ViewCompat.setOnApplyWindowInsetsListener(listView, (v, insets) -> {
            Insets sys = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(dp(14f) + sys.left, sys.top, dp(14f) + sys.right, sys.bottom);
            return insets;
        });
    }

    private Button button(String textStr, boolean primary) {
        Button b = new Button(this);
        b.setText(textStr);
        b.setTextSize(13f);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(7f), 0, dp(7f), 0);
        b.setTextColor(primary ? 0xffffffff : DARK);
        b.setBackground(bg(primary ? RED : 0xffe9edf4, 10));
        return b;
    }

    private void clearNodes() {
        new AlertDialog.Builder(this)
                .setTitle("删除全部节点")
                .setMessage("删除全部 " + items.length() + " 个节点并断开连接？保留订阅地址，之后更新订阅会重新导入节点。")
                .setNegativeButton("取消", null)
                .setPositiveButton("全部删除", (d, w) -> {
                    try {
                        startService(new Intent(this, FVpnService.class).setAction(FVpnService.ACTION_STOP));
                        stopService(new Intent(this, ProbeService.class));
                        store.deleteAllNodes();
                        refreshNodeList();
                    } catch (Exception e) {
                        error(e);
                    }
                })
                .show();
    }

    private void deleteNode(String id, String tag) {
        new AlertDialog.Builder(this)
                .setTitle("删除节点")
                .setMessage("删除「" + tag + "」？如果正在使用该节点，会同时断开连接。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    try {
                        if (id.equals(RunState.vpn(this).optString("node"))) {
                            startService(new Intent(this, FVpnService.class).setAction(FVpnService.ACTION_STOP));
                        }
                        store.deleteNode(id);
                        refreshNodeList();
                    } catch (Exception e) {
                        error(e);
                    }
                })
                .show();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void error(Exception e) {
        operationError("操作", e);
    }

    private void fatal(String msg) {
        new AlertDialog.Builder(this)
                .setTitle("启动失败")
                .setMessage(msg)
                .setPositiveButton("关闭", (d, w) -> finish())
                .show();
    }

    private void gap(LinearLayout parent, int heightDp) {
        View v = new View(this);
        parent.addView(v, new LinearLayout.LayoutParams(1, dp((float) heightDp)));
    }

    private void installUpdate(JSONObject release) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            pendingUpdate = release;
            Intent intent = new Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_INSTALL);
            return;
        }
        background("下载并验证更新包", () -> {
            UpdateManager.downloadAndInstall(this, release, step -> runOnUiThread(() -> detail.setText(step)));
            return "已打开系统安装器";
        });
    }

    private void message(String msg) {
        new AlertDialog.Builder(this)
                .setMessage(msg)
                .setPositiveButton("确定", null)
                .show();
    }

    private void operationError(String stage, Throwable t) {
        CrashDiary.record(this, stage, t);
        detail.setText(stage + "失败：" + CrashDiary.redact(t.toString()));
    }

    private void pair(LinearLayout parent, Button first, Button second) {
        LinearLayout r = row();
        for (Button b : new Button[]{first, second}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(43f), 1f);
            lp.setMargins(dp(3f), dp(4f), dp(3f), dp(4f));
            r.addView(b, lp);
        }
        parent.addView(r);
    }

    private void openNodeConfig(String id) {
        Intent intent = new Intent(this, NodeConfigActivity.class);
        intent.putExtra("node_id", id);
        startActivity(intent);
    }

    private void probeNode(String nodeId) {
        if (testing || busy.get()) {
            return;
        }
        if (items.length() == 0) {
            message("请先导入节点");
            return;
        }
        try {
            Intent intent = new Intent(this, ProbeService.class);
            if (nodeId != null) {
                intent.putExtra("node", nodeId);
            }
            startForegroundService(intent);
            testing = true;
            testAll.setText("停止测速");
            probeInfo.setText("正在启动真实连接测速…");
            adapter.notifyDataSetChanged();
        } catch (Throwable t) {
            testing = false;
            operationError("启动测速", t);
        }
    }

    /**
     * 将跨进程状态文件读取与 ActivityManager 进程存活检查移出 UI 主线程：
     * 1. 先检查文件最后修改时间戳及是否处于过渡态，若无变化则跳过磁盘读取。
     * 2. 在后台线程读取 JSON 与检查进程存活后，再切回主线程更新 UI。
     */
    private void readStatesAsync() {
        if (!pollingState.compareAndSet(false, true)) {
            return;
        }
        pollExecutor.execute(() -> {
            try {
                long vpnMod = RunState.lastModified(this, "vpn-state");
                long probeMod = RunState.lastModified(this, "probe-state");
                boolean activeCheckNeeded = RunState.active(vpn) || testing;
                if (!activeCheckNeeded && vpnMod == lastVpnFileMod && probeMod == lastProbeFileMod) {
                    return;
                }
                lastVpnFileMod = vpnMod;
                lastProbeFileMod = probeMod;

                final JSONObject nextVpn = RunState.vpn(this);
                final JSONObject nextProbe = RunState.read(this, "probe-state");
                boolean isProbeRunning = nextProbe.optBoolean("running");
                if (isProbeRunning
                        && System.currentTimeMillis() - nextProbe.optLong("updated") > 3000L
                        && !RunState.alive(this, nextProbe.optInt("pid"))) {
                    nextProbe.put("message", "测速内核意外退出，请查看错误日志");
                    isProbeRunning = false;
                }
                final boolean finalProbeRunning = isProbeRunning;
                runOnUiThread(() -> applyStatesToUi(nextVpn, nextProbe, finalProbeRunning));
            } catch (Exception ignored) {
            } finally {
                pollingState.set(false);
            }
        });
    }

    private void applyStatesToUi(JSONObject nextVpn, JSONObject nextProbe, boolean isProbeRunning) {
        if (isFinishing()) {
            return;
        }
        this.vpn = nextVpn;
        status.setText(RunState.label(vpn));
        String phase = vpn.optString("phase");
        toggle.setEnabled(!"stopping".equals(phase));
        toggle.setText(RunState.active(vpn) ? "断开连接" : "连接所选节点");

        long nextProbeUpdated = nextProbe.optLong("updated");
        boolean probeChanged = (testing != isProbeRunning) || (probeVersion != nextProbeUpdated);
        testing = isProbeRunning;
        probe = nextProbe;
        probeVersion = nextProbeUpdated;
        measurements = probe.optJSONObject("results");
        if (measurements == null) {
            measurements = new JSONObject();
        }
        testAll.setText(testing ? "停止测速" : "测速全部");
        String probeMsg = probe.optString("message");
        if (!probeMsg.isEmpty()) {
            probeInfo.setText(probeMsg);
        }
        if (!busy.get()) {
            String vpnErr = vpn.optString("error");
            if (!vpnErr.isEmpty()) {
                detail.setText(vpnErr);
            }
        }
        if (probeChanged || !oldPhase.equals(phase)) {
            adapter.notifyDataSetChanged();
            oldPhase = phase;
        }
    }

    private void refreshNodeList() {
        try {
            store.reload();
            items = store.nodes();
            nodeCount.setText("节点  " + items.length());
            adapter.notifyDataSetChanged();
            renderSubscriptions();
        } catch (Exception e) {
            error(e);
        }
    }

    private void renderAppPolicy() {
        if (appLimit == 0 || appPolicy == null) {
            return;
        }
        appPolicyRows.removeAllViews();
        String query = appSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        boolean hasMore = false;
        for (AppDirectPolicy.Entry entry : appPolicy.apps) {
            String text = entry.label + "\n" + entry.pkg;
            if (!query.isEmpty() && !text.toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            if (appPolicyRows.getChildCount() == appLimit) {
                hasMore = true;
                break;
            }
            CheckBox cb = new CheckBox(this);
            cb.setTextSize(12f);
            cb.setText(text);
            cb.setChecked(entry.direct);
            boolean editable = !entry.automatic && !entry.required && !entry.browser;
            cb.setEnabled(editable);
            appPolicyRows.addView(cb);
            cb.setOnCheckedChangeListener((btn, isChecked) -> {
                try {
                    AppDirectPolicy.setManual(this, entry.pkg, isChecked);
                    entry.direct = isChecked;
                    if (isChecked) {
                        appPolicy.excluded.add(entry.pkg);
                    } else {
                        appPolicy.excluded.remove(entry.pkg);
                    }
                    if (RunState.active(RunState.vpn(this))) {
                        startService(new Intent(this, FVpnService.class).setAction(FVpnService.ACTION_STOP));
                        detail.setText("直连设置已保存。请重新连接使设置生效。");
                    } else {
                        detail.setText("直连设置已保存，下次连接生效。");
                    }
                } catch (Exception e) {
                    error(e);
                }
            });
        }
        moreApps.setVisibility(hasMore ? View.VISIBLE : View.GONE);
    }

    private void renderSubscriptions() {
        try {
            subscriptions.removeAllViews();
            JSONArray subs = store.subscriptions();
            if (subs.length() == 0) {
                subscriptions.addView(text("暂无订阅，可直接点击上方添加订阅。", 12, MUTED));
                return;
            }
            for (int i = 0; i < subs.length(); i++) {
                JSONObject sub = subs.getJSONObject(i);
                String id = sub.getString("id");
                LinearLayout itemLayout = vertical();
                itemLayout.setPadding(0, dp(8f), 0, dp(8f));
                itemLayout.addView(text(sub.optString("name", "订阅"), 14, DARK));
                Button updateSubBtn = button("更新订阅", false);
                Button deleteSubBtn = button("删除订阅", false);
                pair(itemLayout, updateSubBtn, deleteSubBtn);
                updateSubBtn.setOnClickListener(v -> background("更新订阅", () -> {
                    store.replaceSubscription(id, NetFetch.text(sub.getString("url"), 2000000));
                    return "订阅更新完成";
                }));
                deleteSubBtn.setOnClickListener(v -> new AlertDialog.Builder(this)
                        .setMessage("删除此订阅及其节点？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除", (d, w) -> {
                            try {
                                store.deleteSubscription(id);
                                refreshNodeList();
                            } catch (Exception e) {
                                error(e);
                            }
                        })
                        .show());
                subscriptions.addView(itemLayout);
            }
        } catch (Exception e) {
            error(e);
        }
    }

    private void requestExport() {
        new AlertDialog.Builder(this)
                .setMessage("备份含节点密码和订阅地址，导出为未加密 JSON，请妥善保管。")
                .setNegativeButton("取消", null)
                .setPositiveButton("导出", (d, w) -> {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .setType("application/json")
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .putExtra(Intent.EXTRA_TITLE, "F-nodes-backup.json");
                    startActivityForResult(intent, REQUEST_EXPORT);
                })
                .show();
    }

    private void requestImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("application/json")
                .addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private void scanApps() {
        if (appLimit == 0 || !scanningApps.compareAndSet(false, true)) {
            return;
        }
        moreApps.setEnabled(false);
        ioExecutor.execute(() -> {
            try {
                AppDirectPolicy.Snapshot snapshot = AppDirectPolicy.scan(this);
                runOnUiThread(() -> {
                    scanningApps.set(false);
                    if (isFinishing()) {
                        return;
                    }
                    moreApps.setEnabled(true);
                    appPolicy = snapshot;
                    if (appLimit != 0 && !snapshot.conflict.isEmpty()) {
                        detail.setText(snapshot.conflict);
                    }
                    renderAppPolicy();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    scanningApps.set(false);
                    moreApps.setEnabled(true);
                    if (!isFinishing() && appLimit != 0) {
                        error(e);
                    }
                });
            }
        });
    }

    private void showImportDialog() {
        EditText input = new EditText(this);
        input.setMinLines(4);
        input.setMaxLines(10);
        input.setHint("粘贴 hy2://、hysteria2://、tuic:// 等链接\n支持批量链接及 sing-box JSON / Clash 订阅");
        new AlertDialog.Builder(this)
                .setTitle("导入节点")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("导入", (d, w) -> background("正在导入", () ->
                        "已新增 " + store.importManual(input.getText().toString()) + " 个节点"))
                .show();
    }

    private void startVpn() {
        try {
            detail.setText("正在启动 VPN，出错信息会保留在此页。");
            startForegroundService(new Intent(this, FVpnService.class));
        } catch (Throwable t) {
            operationError("启动 VPN", t);
        }
    }

    private TextView text(String content, int sizeSp, int color) {
        TextView tv = new TextView(this);
        tv.setText(content);
        tv.setTextSize((float) sizeSp);
        tv.setTextColor(color);
        return tv;
    }

    private void toggleVpn() {
        if (RunState.active(RunState.vpn(this))) {
            startService(new Intent(this, FVpnService.class).setAction(FVpnService.ACTION_STOP));
            detail.setText("正在断开连接…");
            return;
        }
        try {
            if (store.selectedConfig() == null) {
                message("请先导入并选择节点");
                return;
            }
            Intent prepareIntent = VpnService.prepare(this);
            if (prepareIntent != null) {
                startActivityForResult(prepareIntent, REQUEST_VPN);
            } else {
                startVpn();
            }
        } catch (Throwable t) {
            operationError("请求 VPN 授权", t);
        }
    }

    private void updateAll() {
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        refresh.setEnabled(false);
        detail.setText("正在更新订阅、Geo 及检查正式版内核…");
        ioExecutor.execute(() -> {
            try {
                UpdateManager.Result res = UpdateManager.updateAll(
                        this,
                        store,
                        step -> runOnUiThread(() -> detail.setText(step))
                );
                runOnUiThread(() -> {
                    busy.set(false);
                    if (isFinishing()) {
                        return;
                    }
                    refresh.setEnabled(true);
                    refreshNodeList();
                    appPolicy = null;
                    scanApps();
                    AlertDialog.Builder dlg = new AlertDialog.Builder(this)
                            .setTitle("更新结果")
                            .setMessage(res.asText())
                            .setNegativeButton("关闭", null);
                    if (res.appRelease != null) {
                        dlg.setPositiveButton("安装正式版", (d, w) -> installUpdate(res.appRelease));
                    }
                    dlg.show();
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    busy.set(false);
                    refresh.setEnabled(true);
                    operationError("更新", t);
                });
            }
        });
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_INSTALL) {
            JSONObject release = pendingUpdate;
            pendingUpdate = null;
            if (release != null && getPackageManager().canRequestPackageInstalls()) {
                installUpdate(release);
            }
            return;
        }
        if (resultCode != RESULT_OK) {
            return;
        }
        if (requestCode == REQUEST_VPN) {
            startVpn();
            return;
        }
        if (data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQUEST_EXPORT) {
            background("导出备份", () -> {
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) {
                        throw new IOException("无法创建文件");
                    }
                    out.write(store.exportJson().getBytes(StandardCharsets.UTF_8));
                }
                return "备份已导出";
            });
        } else if (requestCode == REQUEST_IMPORT) {
            background("合并恢复", () -> {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) {
                        throw new IOException("无法打开文件");
                    }
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int read;
                    while ((read = in.read(buf)) != -1) {
                        if (out.size() + read > 8000000) {
                            throw new IOException("备份超过 8 MB");
                        }
                        out.write(buf, 0, read);
                    }
                    int count = store.restoreMerge(out.toString("UTF-8"));
                    return "已合并恢复 " + count + " 个节点";
                }
            });
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashDiary.install(this, "界面");
        try {
            store = new NodeStore(this);
            buildUI();
            refreshNodeList();
            readStatesAsync();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 601);
            }
        } catch (Exception e) {
            fatal(e.toString());
        }
    }

    @Override
    public void onPause() {
        handler.removeCallbacks(poll);
        handler.removeCallbacks(debouncedRenderApps);
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshNodeList();
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(poll);
        handler.removeCallbacks(debouncedRenderApps);
        ioExecutor.shutdownNow();
        pollExecutor.shutdownNow();
        super.onDestroy();
    }
}