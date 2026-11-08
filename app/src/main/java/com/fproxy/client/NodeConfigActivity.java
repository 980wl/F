package com.fproxy.client;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 节点详细参数配置页：编辑已保存节点的配置 JSON，保存后写回 NodeStore，
 * 返回主页面后列表自动刷新。未点保存直接返回则丢弃全部修改。
 * hysteria2 节点额外提供混淆、BBR 档位、上/下行带宽与端口跳跃设置。
 */
public class NodeConfigActivity extends Activity {
    private static final int DARK = 0xff202632;
    private static final int MUTED = 0xff7a8291;
    private static final int RED = 0xffd3222a;

    private static final String[] BBR_LABELS = {"标准", "激进", "保守"};
    private static final String[] BBR_VALUES = {"standard", "aggressive", "conservative"};
    private static final String[] OBFS_LABELS = {"无", "salamander", "gecko"};
    private static final String[] OBFS_VALUES = {"", "salamander", "gecko"};
    private static final String[] TUIC_CC_LABELS = {"bbr", "cubic", "new_reno"};
    private static final String[] TUIC_CC_VALUES = {"bbr", "cubic", "new_reno"};

    private NodeStore store;
    private String nodeId;
    private JSONObject cfg;
    private String type;

    private EditText tagInput;
    private EditText serverInput;
    private EditText portInput;
    private EditText passwordInput;
    private EditText uuidInput;
    private EditText methodInput;
    private EditText sniInput;

    private Spinner obfsSpinner;
    private LinearLayout obfsPwBox;
    private EditText obfsPwInput;
    private LinearLayout geckoBox;
    private EditText geckoMinInput;
    private EditText geckoMaxInput;
    private Spinner bbrSpinner;
    private EditText upInput;
    private EditText downInput;
    private CheckBox hopCheck;
    private LinearLayout hopBox;
    private EditText hopPortsInput;
    private EditText hopIntervalInput;
    private EditText hopIntervalMaxInput;
    private Spinner tuicCcSpinner;

    private static final class Field {
        LinearLayout box;
        EditText input;
    }

    private static final class SpinField {
        LinearLayout box;
        Spinner spinner;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashDiary.install(this, "节点配置");
        nodeId = getIntent().getStringExtra("node_id");
        try {
            store = new NodeStore(this);
            JSONObject node = nodeId == null ? null : store.node(nodeId);
            if (node == null || node.optJSONObject("config") == null) {
                Toast.makeText(this, "节点不存在", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            cfg = new JSONObject(node.optJSONObject("config").toString());
            type = cfg.optString("type", "");
            buildUI(node);
        } catch (Exception e) {
            CrashDiary.record(this, "打开节点配置", e);
            Toast.makeText(this, "打开失败，请重试", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void buildUI(JSONObject node) {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xfff5f7fb);
        LinearLayout root = vertical();
        root.setPadding(dp(16f), dp(18f), dp(16f), dp(24f));
        scroll.addView(root);

        TextView title = text("节点配置", 23, DARK);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);
        root.addView(text(type + "  ·  " + cfg.optString("server", "") + ":" + cfg.optInt("server_port"), 12, MUTED));

        String source = node.optString("source", "");
        if (source.startsWith("sub:")) {
            root.addView(note("订阅节点：下次刷新订阅时会被覆盖，如需长期保留请改用手动导入。"));
        }
        boolean connected = false;
        try {
            connected = nodeId.equals(RunState.vpn(this).optString("node"));
        } catch (Exception ignored) {
        }
        if (connected) {
            root.addView(note("正在使用中，修改下次连接生效。"));
        }

        section(root, "通用");
        tagInput = addField(root, "备注", cfg.optString("tag", ""), "节点名称", 0).input;
        serverInput = addField(root, "服务器", cfg.optString("server", ""), "域名或 IP", 0).input;
        String portStr = cfg.optInt("server_port") > 0 ? String.valueOf(cfg.optInt("server_port")) : "";
        portInput = addField(root, "端口", portStr, "1-65535", InputType.TYPE_CLASS_NUMBER).input;

        section(root, "认证与连接");
        boolean hasPassword = cfg.has("password")
                || "hysteria2".equals(type) || "trojan".equals(type)
                || "shadowsocks".equals(type) || "tuic".equals(type);
        boolean hasUuid = cfg.has("uuid")
                || "vless".equals(type) || "vmess".equals(type) || "tuic".equals(type);
        if ("shadowsocks".equals(type)) {
            methodInput = addField(root, "加密方式", cfg.optString("method", ""), "如 aes-128-gcm", 0).input;
        }
        if (hasUuid) {
            uuidInput = addField(root, "UUID", cfg.optString("uuid", ""), "UUID", 0).input;
        }
        if (hasPassword) {
            passwordInput = addField(root, "密码", cfg.optString("password", ""), "密码", 0).input;
        }
        if (!hasPassword && !hasUuid && !"shadowsocks".equals(type)) {
            root.addView(text("该类型节点无认证字段。", 12, MUTED));
        }
        boolean hasTls = cfg.has("tls")
                || "vless".equals(type) || "vmess".equals(type) || "trojan".equals(type)
                || "tuic".equals(type) || "hysteria2".equals(type);
        if (hasTls) {
            JSONObject tls = cfg.optJSONObject("tls");
            sniInput = addField(root, "TLS SNI", tls == null ? "" : tls.optString("server_name", ""),
                    "留空使用服务器地址", 0).input;
        }
        if ("tuic".equals(type)) {
            tuicCcSpinner = addSpinner(root, "拥塞控制", TUIC_CC_LABELS,
                    indexOf(TUIC_CC_VALUES, cfg.optString("congestion_control", "bbr"))).spinner;
        }

        if ("hysteria2".equals(type)) {
            buildHysteria2(root);
        }

        gap(root, 20);
        LinearLayout btnRow = row();
        Button saveBtn = button("保存", true);
        Button backBtn = button("返回", false);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, dp(43f), 1f);
        saveLp.setMargins(dp(3f), dp(4f), dp(3f), dp(4f));
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(0, dp(43f), 1f);
        backLp.setMargins(dp(3f), dp(4f), dp(3f), dp(4f));
        btnRow.addView(saveBtn, saveLp);
        btnRow.addView(backBtn, backLp);
        root.addView(btnRow);

        saveBtn.setOnClickListener(v -> save());
        backBtn.setOnClickListener(v -> finish());

        setContentView(scroll);
    }

    private void buildHysteria2(LinearLayout root) {
        section(root, "Hysteria2");
        JSONObject obfs = cfg.optJSONObject("obfs");
        String obfsType = obfs == null ? "" : obfs.optString("type", "");
        obfsSpinner = addSpinner(root, "混淆", OBFS_LABELS, indexOf(OBFS_VALUES, obfsType)).spinner;

        obfsPwBox = vertical();
        obfsPwInput = addField(obfsPwBox, "混淆密码",
                obfs == null ? "" : obfs.optString("password", ""), "与服务器一致", 0).input;
        root.addView(obfsPwBox);

        geckoBox = vertical();
        int minPkt = obfs == null ? 0 : obfs.optInt("min_packet_size", 0);
        int maxPkt = obfs == null ? 0 : obfs.optInt("max_packet_size", 0);
        geckoMinInput = addField(geckoBox, "最小包大小（字节）",
                minPkt > 0 ? String.valueOf(minPkt) : "", "留空默认 512", InputType.TYPE_CLASS_NUMBER).input;
        geckoMaxInput = addField(geckoBox, "最大包大小（字节）",
                maxPkt > 0 ? String.valueOf(maxPkt) : "", "留空默认 1200", InputType.TYPE_CLASS_NUMBER).input;
        root.addView(geckoBox);

        obfsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateObfsVisibility();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        updateObfsVisibility();

        bbrSpinner = addSpinner(root, "BBR 档位", BBR_LABELS,
                indexOf(BBR_VALUES, cfg.optString("bbr_profile", "standard"))).spinner;
        root.addView(text("保守档在丢包多的移动网络上更稳；标准为 sing-box 默认。", 11, MUTED));

        int up = cfg.optInt("up_mbps", 0);
        int down = cfg.optInt("down_mbps", 0);
        upInput = addField(root, "上行带宽 up_mbps（Mbps）",
                up > 0 ? String.valueOf(up) : "", "留空使用 BBR", InputType.TYPE_CLASS_NUMBER).input;
        downInput = addField(root, "下行带宽 down_mbps（Mbps）",
                down > 0 ? String.valueOf(down) : "", "留空使用 BBR", InputType.TYPE_CLASS_NUMBER).input;
        root.addView(text("填实测带宽；填得比实际高会超发导致降速，填得低会浪费带宽。", 11, MUTED));

        hopCheck = new CheckBox(this);
        hopCheck.setText("启用端口跳跃");
        hopCheck.setTextSize(14f);
        hopCheck.setTextColor(DARK);
        JSONArray ports = cfg.optJSONArray("server_ports");
        hopCheck.setChecked(ports != null && ports.length() > 0);
        LinearLayout.LayoutParams hopLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hopLp.topMargin = dp(14f);
        root.addView(hopCheck, hopLp);

        hopBox = vertical();
        StringBuilder portsText = new StringBuilder();
        if (ports != null) {
            for (int i = 0; i < ports.length(); i++) {
                if (i > 0) {
                    portsText.append(",");
                }
                portsText.append(ports.optString(i));
            }
        }
        hopPortsInput = addField(hopBox, "跳跃端口", portsText.toString(),
                "如 10000:20000 或 12345,20000:20100", 0).input;
        hopIntervalInput = addField(hopBox, "跳跃间隔", cfg.optString("hop_interval", ""),
                "如 30s，留空默认 30s", 0).input;
        hopIntervalMaxInput = addField(hopBox, "最大跳跃间隔（可选）", cfg.optString("hop_interval_max", ""),
                "留空不随机化，如 60s", 0).input;
        root.addView(hopBox);
        root.addView(text("开启后单端口失效，按跳跃范围轮换；范围与间隔须与服务器一致。", 11, MUTED));

        hopCheck.setOnCheckedChangeListener((btn, checked) -> hopBox.setVisibility(checked ? View.VISIBLE : View.GONE));
        hopBox.setVisibility(hopCheck.isChecked() ? View.VISIBLE : View.GONE);
    }

    private void updateObfsVisibility() {
        String selected = OBFS_VALUES[obfsSpinner.getSelectedItemPosition()];
        obfsPwBox.setVisibility(selected.isEmpty() ? View.GONE : View.VISIBLE);
        geckoBox.setVisibility("gecko".equals(selected) ? View.VISIBLE : View.GONE);
    }

    private void save() {
        try {
            String server = serverInput.getText().toString().trim();
            if (server.isEmpty()) {
                toast("服务器不能为空");
                return;
            }
            int port = parsePort(portInput.getText().toString().trim());
            if (port < 0) {
                toast("端口必须是 1-65535 的数字");
                return;
            }
            String tag = tagInput.getText().toString().trim();
            cfg.put("tag", tag.isEmpty() ? server + ":" + port : tag);
            cfg.put("server", server);
            cfg.put("server_port", port);

            if (methodInput != null) {
                String method = methodInput.getText().toString().trim();
                if (method.isEmpty()) {
                    toast("加密方式不能为空");
                    return;
                }
                cfg.put("method", method);
            }
            if (uuidInput != null) {
                String uuid = uuidInput.getText().toString().trim();
                if (uuid.isEmpty()) {
                    toast("UUID 不能为空");
                    return;
                }
                cfg.put("uuid", uuid);
            }
            if (passwordInput != null) {
                String password = passwordInput.getText().toString().trim();
                if (password.isEmpty()) {
                    toast("密码不能为空");
                    return;
                }
                cfg.put("password", password);
            }
            if (sniInput != null) {
                JSONObject tls = cfg.optJSONObject("tls");
                if (tls == null) {
                    tls = new JSONObject();
                }
                String sni = sniInput.getText().toString().trim();
                if (sni.isEmpty()) {
                    tls.remove("server_name");
                } else {
                    tls.put("server_name", sni);
                }
                cfg.put("tls", tls);
            }
            if (tuicCcSpinner != null) {
                cfg.put("congestion_control", TUIC_CC_VALUES[tuicCcSpinner.getSelectedItemPosition()]);
            }
            if ("hysteria2".equals(type) && !saveHysteria2()) {
                return;
            }

            store.updateNodeConfig(nodeId, cfg);
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception e) {
            CrashDiary.record(this, "保存节点配置", e);
            toast("保存失败，请重试");
        }
    }

    /** 校验并写入 hysteria2 专属字段；失败返回 false（已弹提示）。 */
    private boolean saveHysteria2() throws Exception {
        String obfsType = OBFS_VALUES[obfsSpinner.getSelectedItemPosition()];
        if (obfsType.isEmpty()) {
            cfg.remove("obfs");
        } else {
            String obfsPw = obfsPwInput.getText().toString().trim();
            if (obfsPw.isEmpty()) {
                toast("混淆密码不能为空");
                return false;
            }
            JSONObject obfs = new JSONObject().put("type", obfsType).put("password", obfsPw);
            if ("gecko".equals(obfsType)) {
                String minStr = geckoMinInput.getText().toString().trim();
                String maxStr = geckoMaxInput.getText().toString().trim();
                int min = minStr.isEmpty() ? 0 : parsePositiveInt(minStr);
                int max = maxStr.isEmpty() ? 0 : parsePositiveInt(maxStr);
                if (min < 0 || max < 0) {
                    toast("包大小必须是正整数");
                    return false;
                }
                if (min > 0 && max > 0 && min > max) {
                    toast("最小包大小不能大于最大包大小");
                    return false;
                }
                if (min > 0) {
                    obfs.put("min_packet_size", min);
                }
                if (max > 0) {
                    obfs.put("max_packet_size", max);
                }
            }
            cfg.put("obfs", obfs);
        }

        cfg.put("bbr_profile", BBR_VALUES[bbrSpinner.getSelectedItemPosition()]);

        int up = parsePositiveIntAllowEmpty(upInput.getText().toString().trim());
        int down = parsePositiveIntAllowEmpty(downInput.getText().toString().trim());
        if (up < 0 || down < 0) {
            toast("带宽必须是正整数，留空使用 BBR");
            return false;
        }
        if (up > 0) {
            cfg.put("up_mbps", up);
        } else {
            cfg.remove("up_mbps");
        }
        if (down > 0) {
            cfg.put("down_mbps", down);
        } else {
            cfg.remove("down_mbps");
        }

        if (hopCheck.isChecked()) {
            String[] normalized = parsePortRanges(hopPortsInput.getText().toString().trim());
            if (normalized == null) {
                toast("跳跃端口格式不对，如 10000:20000 或 12345,20000:20100");
                return false;
            }
            String hop = hopIntervalInput.getText().toString().trim();
            String hopMax = hopIntervalMaxInput.getText().toString().trim();
            if (!hop.isEmpty() && !validDuration(hop)) {
                toast("跳跃间隔格式不对，如 30s");
                return false;
            }
            if (!hopMax.isEmpty() && !validDuration(hopMax)) {
                toast("最大跳跃间隔格式不对，如 60s");
                return false;
            }
            if (!hop.isEmpty() && !hopMax.isEmpty() && durationSeconds(hopMax) < durationSeconds(hop)) {
                toast("最大跳跃间隔不能小于跳跃间隔");
                return false;
            }
            JSONArray arr = new JSONArray();
            for (String p : normalized) {
                arr.put(p);
            }
            cfg.put("server_ports", arr);
            if (hop.isEmpty()) {
                cfg.remove("hop_interval");
            } else {
                cfg.put("hop_interval", hop);
            }
            if (hopMax.isEmpty()) {
                cfg.remove("hop_interval_max");
            } else {
                cfg.put("hop_interval_max", hopMax);
            }
        } else {
            cfg.remove("server_ports");
            cfg.remove("hop_interval");
            cfg.remove("hop_interval_max");
        }
        return true;
    }

    /** 端口：合法返回 1-65535，否则 -1。 */
    private static int parsePort(String s) {
        int v = parsePositiveIntAllowEmpty(s);
        return v >= 1 && v <= 65535 ? v : -1;
    }

    /** 正整数：合法返回数值；空串返回 0；非法返回 -1。 */
    private static int parsePositiveIntAllowEmpty(String s) {
        if (s.isEmpty()) {
            return 0;
        }
        return parsePositiveInt(s);
    }

    private static int parsePositiveInt(String s) {
        try {
            int v = Integer.parseInt(s);
            return v >= 1 ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 跳跃端口：逗号分隔的单端口或 start:end 区间；非法返回 null，否则返回规范化后的数组。 */
    private static String[] parsePortRanges(String s) {
        if (s.isEmpty()) {
            return null;
        }
        String[] items = s.split(",");
        String[] out = new String[items.length];
        for (int i = 0; i < items.length; i++) {
            String item = items[i].trim();
            if (item.isEmpty()) {
                return null;
            }
            if (item.contains(":")) {
                String[] range = item.split(":", -1);
                if (range.length != 2) {
                    return null;
                }
                int start = parsePort(range[0].trim());
                int end = parsePort(range[1].trim());
                if (start < 0 || end < 0 || start > end) {
                    return null;
                }
                out[i] = start + ":" + end;
            } else {
                int p = parsePort(item);
                if (p < 0) {
                    return null;
                }
                out[i] = String.valueOf(p);
            }
        }
        return out;
    }

    private static boolean validDuration(String s) {
        if (s.length() < 2) {
            return false;
        }
        char unit = s.charAt(s.length() - 1);
        if (unit != 's' && unit != 'm' && unit != 'h') {
            return false;
        }
        return parsePositiveInt(s.substring(0, s.length() - 1)) > 0;
    }

    private static long durationSeconds(String s) {
        long v = parsePositiveInt(s.substring(0, s.length() - 1));
        switch (s.charAt(s.length() - 1)) {
            case 'm':
                return v * 60;
            case 'h':
                return v * 3600;
            default:
                return v;
        }
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return i;
            }
        }
        return 0;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private TextView note(String msg) {
        TextView tv = text(msg, 11, MUTED);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6f);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void section(LinearLayout parent, String title) {
        TextView tv = text(title, 15, DARK);
        tv.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18f);
        parent.addView(tv, lp);
    }

    private Field addField(LinearLayout parent, String label, String value, String hint, int inputType) {
        Field f = new Field();
        f.box = vertical();
        TextView tv = text(label, 12, MUTED);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = dp(12f);
        f.box.addView(tv, labelLp);
        EditText et = new EditText(this);
        et.setText(value == null ? "" : value);
        et.setHint(hint);
        et.setTextSize(14f);
        et.setTextColor(DARK);
        if (inputType != 0) {
            et.setInputType(inputType);
        }
        et.setSingleLine(true);
        et.setBackground(inputBg());
        et.setPadding(dp(10f), dp(10f), dp(10f), dp(10f));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.topMargin = dp(4f);
        f.box.addView(et, inputLp);
        f.input = et;
        parent.addView(f.box);
        return f;
    }

    private SpinField addSpinner(LinearLayout parent, String label, String[] labels, int selected) {
        SpinField f = new SpinField();
        f.box = vertical();
        TextView tv = text(label, 12, MUTED);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = dp(12f);
        f.box.addView(tv, labelLp);
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(adapter);
        sp.setSelection(selected);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        spLp.topMargin = dp(4f);
        f.box.addView(sp, spLp);
        f.spinner = sp;
        parent.addView(f.box);
        return f;
    }

    private GradientDrawable inputBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xffffffff);
        d.setCornerRadius((float) dp(10f));
        d.setStroke(dp(1f), 0xffdfe3ea);
        return d;
    }

    private GradientDrawable bg(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius((float) dp((float) radiusDp));
        return drawable;
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

    private TextView text(String content, int sizeSp, int color) {
        TextView tv = new TextView(this);
        tv.setText(content);
        tv.setTextSize((float) sizeSp);
        tv.setTextColor(color);
        return tv;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private void gap(LinearLayout parent, int heightDp) {
        View v = new View(this);
        parent.addView(v, new LinearLayout.LayoutParams(1, dp((float) heightDp)));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
