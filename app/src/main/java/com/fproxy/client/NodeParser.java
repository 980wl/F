package com.fproxy.client;

import android.util.Base64;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class NodeParser {
    public static final int MAX_NODES = 2000;

    private NodeParser() {
    }

    private static String decode(String s) {
        if (s == null) {
            return null;
        }
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] decode64(String s) {
        String cleaned = s.replaceAll("\\s+", "");
        try {
            return Base64.decode(cleaned, Base64.DEFAULT);
        } catch (Exception e) {
            return Base64.decode(cleaned, Base64.URL_SAFE | Base64.NO_WRAP);
        }
    }

    static void enableRealityUtls(JSONObject tls) throws Exception {
        JSONObject utls = tls.optJSONObject("utls");
        if (utls == null) {
            utls = new JSONObject();
        }
        utls.put("enabled", true);
        if (utls.isNull("fingerprint") || utls.optString("fingerprint").trim().isEmpty()) {
            utls.put("fingerprint", "chrome");
        }
        tls.put("utls", utls);
    }

    public static String fingerprint(JSONObject config) throws Exception {
        JSONObject copy = new JSONObject(config.toString());
        copy.remove("tag");
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(copy.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            sb.append(String.format("%02x", digest[i]));
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static JSONObject fromClash(Map<String, Object> map) throws Exception {
        String type = txt(map, "type", "");
        String server = txt(map, "server", "");
        int port = num(map, "port", 0);
        if (server.trim().isEmpty() || port < 1 || port > 65535) {
            return null;
        }
        JSONObject out = new JSONObject()
                .put("server", server)
                .put("server_port", port)
                .put("tag", txt(map, "name", server + ":" + port));

        switch (type) {
            case "vmess":
                out.put("type", "vmess")
                        .put("uuid", txt(map, "uuid", ""))
                        .put("alter_id", num(map, "alterId", 0))
                        .put("security", txt(map, "cipher", "auto"));
                break;
            case "vless":
                out.put("type", "vless")
                        .put("uuid", txt(map, "uuid", ""));
                if (map.containsKey("flow")) {
                    out.put("flow", txt(map, "flow", ""));
                }
                break;
            case "tuic":
                out.put("type", "tuic")
                        .put("uuid", txt(map, "uuid", ""))
                        .put("password", txt(map, "password", ""))
                        .put("congestion_control", txt(map, "congestion-controller", "bbr"));
                break;
            case "ss":
                out.put("type", "shadowsocks")
                        .put("method", txt(map, "cipher", ""))
                        .put("password", txt(map, "password", ""));
                break;
            case "hysteria2":
                out.put("type", "hysteria2")
                        .put("password", txt(map, "password", ""));
                String obfs = txt(map, "obfs", "");
                if ("salamander".equals(obfs) || "gecko".equals(obfs)) {
                    JSONObject obfsObj = hysteriaObfuscation(obfs, txt(map, "obfs-password", ""));
                    if ("gecko".equals(obfs)) {
                        if (map.containsKey("obfs-min-packet-size")) {
                            obfsObj.put("min_packet_size", Integer.parseInt(txt(map, "obfs-min-packet-size", "")));
                        }
                        if (map.containsKey("obfs-max-packet-size")) {
                            obfsObj.put("max_packet_size", Integer.parseInt(txt(map, "obfs-max-packet-size", "")));
                        }
                    }
                    out.put("obfs", obfsObj);
                } else if (!obfs.isEmpty()) {
                    return null;
                }
                putHysteriaBbrProfile(out, txt(map, "bbr-profile", ""));
                break;
            case "trojan":
                out.put("type", "trojan")
                        .put("password", txt(map, "password", ""));
                break;
            default:
                return null;
        }

        if (Boolean.TRUE.equals(map.get("tls"))
                || "tuic".equals(type)
                || "hysteria2".equals(type)
                || "trojan".equals(type)) {
            JSONObject tls = new JSONObject().put("enabled", true);
            if (map.containsKey("servername")) {
                tls.put("server_name", txt(map, "servername", server));
            }
            if (map.containsKey("sni")) {
                tls.put("server_name", txt(map, "sni", server));
            }
            if (Boolean.TRUE.equals(map.get("skip-cert-verify"))) {
                tls.put("insecure", true);
            }
            if (map.get("alpn") instanceof List) {
                tls.put("alpn", new JSONArray((List<?>) map.get("alpn")));
            }
            out.put("tls", tls);
        }

        if ("ws".equals(txt(map, "network", "")) && map.get("ws-opts") instanceof Map) {
            Map<String, Object> wsOpts = (Map<String, Object>) map.get("ws-opts");
            JSONObject transport = new JSONObject()
                    .put("type", "ws")
                    .put("path", txt(wsOpts, "path", "/"));
            if (wsOpts.get("headers") instanceof Map) {
                JSONObject headers = new JSONObject();
                ((Map<?, ?>) wsOpts.get("headers")).forEach((k, v) -> {
                    try {
                        headers.put(String.valueOf(k), v);
                    } catch (Exception ignored) {
                    }
                });
                transport.put("headers", headers);
            }
            out.put("transport", transport);
        }
        return out;
    }

    public static JSONObject fromUri(String uriStr) throws Exception {
        uriStr = uriStr.trim();
        if (uriStr.startsWith("vmess://")) {
            return vmess(uriStr);
        }
        URI uri = new URI(uriStr);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!Arrays.asList("hysteria2", "hy2", "tuic", "vless", "trojan", "ss").contains(scheme)) {
            throw new IllegalArgumentException("尚不支持此节点链接协议: " + scheme);
        }
        if ("ss".equals(scheme)) {
            return shadowsocks(uriStr);
        }
        String host = uri.getHost();
        int port = uri.getPort();
        if (port == -1 && ("hy2".equals(scheme) || "hysteria2".equals(scheme))) {
            port = 443;
        }
        if (host != null && host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host == null || host.isEmpty() || port <= 0 || port > 65535) {
            throw new IllegalArgumentException("节点缺少有效的服务器地址或端口");
        }
        String tag = decode(uri.getRawFragment());
        if (tag == null || tag.trim().isEmpty()) {
            tag = host + ":" + port;
        }
        Map<String, String> q = query(uri.getRawQuery());
        String userInfo = decode(uri.getRawUserInfo() == null ? "" : uri.getRawUserInfo());

        JSONObject out = new JSONObject()
                .put("tag", tag)
                .put("server", host)
                .put("server_port", port);

        if ("hy2".equals(scheme) || "hysteria2".equals(scheme)) {
            out.put("type", "hysteria2").put("password", userInfo);
            String obfs = q.getOrDefault("obfs", "");
            if (!obfs.isEmpty()) {
                out.put("obfs", hysteriaObfuscation(obfs, q.getOrDefault("obfs-password", "")));
            }
            putHysteriaBbrProfile(out, q.getOrDefault("bbr-profile", ""));
            if (q.containsKey("upmbps")) {
                out.put("up_mbps", Integer.parseInt(q.get("upmbps")));
            }
            if (q.containsKey("downmbps")) {
                out.put("down_mbps", Integer.parseInt(q.get("downmbps")));
            }
        } else if ("tuic".equals(scheme)) {
            String[] parts = userInfo.split(":", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("TUIC 必须提供 uuid:password");
            }
            out.put("type", "tuic")
                    .put("uuid", parts[0])
                    .put("password", parts[1])
                    .put("congestion_control", q.getOrDefault("congestion_control", "bbr"));
            if (q.containsKey("udp_relay_mode")) {
                out.put("udp_relay_mode", q.get("udp_relay_mode"));
            }
        } else if ("vless".equals(scheme)) {
            out.put("type", "vless").put("uuid", userInfo);
            if (q.containsKey("flow")) {
                out.put("flow", q.get("flow"));
            }
            if ("ws".equals(q.getOrDefault("type", "tcp"))) {
                JSONObject transport = new JSONObject().put("type", "ws");
                if (q.containsKey("path")) {
                    transport.put("path", q.get("path"));
                }
                if (q.containsKey("host")) {
                    transport.put("headers", new JSONObject().put("Host", q.get("host")));
                }
                out.put("transport", transport);
            }
        } else {
            out.put("type", "trojan").put("password", userInfo);
        }

        String secDefault = "vless".equals(scheme) ? "none" : "tls";
        String security = q.getOrDefault("security", secDefault);
        if ("hy2".equals(scheme) || "hysteria2".equals(scheme) || "tuic".equals(scheme) || "trojan".equals(scheme)) {
            security = "tls";
        }
        if (!"none".equals(security)) {
            JSONObject tls = new JSONObject().put("enabled", true);
            String sni = q.getOrDefault("sni", q.get("peer"));
            if (sni != null && !sni.trim().isEmpty()) {
                tls.put("server_name", sni);
            }
            if ("1".equals(q.get("insecure"))
                    || "true".equalsIgnoreCase(q.get("insecure"))
                    || "1".equals(q.get("allowInsecure"))
                    || "true".equalsIgnoreCase(q.get("allowInsecure"))) {
                tls.put("insecure", true);
            }
            String alpn = q.get("alpn");
            if (alpn != null && !alpn.isEmpty()) {
                tls.put("alpn", new JSONArray(alpn.split(",")));
            }
            if ("reality".equals(security)) {
                JSONObject reality = new JSONObject()
                        .put("enabled", true)
                        .put("public_key", q.getOrDefault("pbk", q.getOrDefault("public_key", "")))
                        .put("short_id", q.getOrDefault("sid", ""));
                tls.put("reality", reality);
                String fp = q.getOrDefault("fp", "").trim();
                if (!fp.isEmpty()) {
                    tls.put("utls", new JSONObject().put("fingerprint", fp));
                }
                enableRealityUtls(tls);
            }
            out.put("tls", tls);
        }
        return out;
    }

    private static JSONObject hysteriaObfuscation(String type, String password) throws Exception {
        if (!"salamander".equals(type) && !"gecko".equals(type)) {
            throw new IllegalArgumentException("不支持的 HY2 混淆类型");
        }
        return new JSONObject().put("type", type).put("password", password);
    }

    private static int num(Map<String, Object> map, String key, int def) {
        try {
            return Integer.parseInt(String.valueOf(map.get(key)));
        } catch (Exception e) {
            return def;
        }
    }

    @SuppressWarnings("unchecked")
    public static List<JSONObject> parseDocument(String raw) throws Exception {
        String text = raw.trim();
        if (text.length() > 2000000) {
            throw new IllegalArgumentException("订阅内容超过 2 MB");
        }
        if (text.isEmpty()) {
            throw new IllegalArgumentException("订阅内容为空");
        }
        if (text.startsWith("{")) {
            try {
                JSONObject obj = new JSONObject(text);
                JSONArray arr = obj.optJSONArray("outbounds");
                if (arr == null) {
                    arr = obj.optJSONArray("nodes");
                }
                if (arr != null) {
                    return parseJsonArray(arr);
                }
            } catch (Exception ignored) {
            }
        }
        if (text.startsWith("[")) {
            return parseJsonArray(new JSONArray(text));
        }
        if (text.contains("proxies:")
                && (text.startsWith("proxies:") || text.startsWith("mixed-port:") || text.contains("\nproxies:"))) {
            LoaderOptions opts = new LoaderOptions();
            opts.setMaxAliasesForCollections(20);
            opts.setCodePointLimit(2000000);
            Yaml yaml = new Yaml(new SafeConstructor(opts));
            Object loaded = yaml.load(text);
            if (loaded instanceof Map) {
                Object proxies = ((Map<?, ?>) loaded).get("proxies");
                if (proxies instanceof List) {
                    List<JSONObject> list = new ArrayList<>();
                    for (Object item : (List<?>) proxies) {
                        if (item instanceof Map) {
                            JSONObject node = fromClash((Map<String, Object>) item);
                            if (node != null) {
                                list.add(node);
                            }
                        }
                        if (list.size() >= MAX_NODES) {
                            break;
                        }
                    }
                    if (!list.isEmpty()) {
                        return list;
                    }
                }
            }
            throw new IllegalArgumentException("Clash 订阅中没有可识别的节点");
        }
        if (!text.contains("://")) {
            try {
                String decoded = new String(decode64(text.replaceAll("\\s+", "")), StandardCharsets.UTF_8);
                if (decoded.contains("://") || decoded.startsWith("{")) {
                    return parseDocument(decoded);
                }
            } catch (Exception ignored) {
            }
        }
        List<JSONObject> list = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            list.add(fromUri(line));
            if (list.size() >= MAX_NODES) {
                break;
            }
        }
        if (!list.isEmpty()) {
            return list;
        }
        throw new IllegalArgumentException("没有可导入的有效节点（支持 hy2/tuic/vless/vmess/trojan/ss）");
    }

    private static List<JSONObject> parseJsonArray(JSONArray arr) throws Exception {
        List<JSONObject> list = new ArrayList<>();
        List<String> supported = Arrays.asList(
                "hysteria2", "tuic", "vless", "vmess", "trojan", "shadowsocks", "hysteria");
        for (int i = 0; i < arr.length() && list.size() < MAX_NODES; i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) {
                continue;
            }
            if (supported.contains(item.optString("type"))
                    && !item.optString("server").isEmpty()
                    && item.optInt("server_port") >= 1) {
                list.add(new JSONObject(item.toString()));
            }
        }
        if (!list.isEmpty()) {
            return list;
        }
        throw new IllegalArgumentException("JSON 订阅没有可用出站节点");
    }

    private static void putHysteriaBbrProfile(JSONObject out, String profile) throws Exception {
        if (profile.isEmpty()) {
            return;
        }
        if (Arrays.asList("standard", "aggressive", "conservative").contains(profile)) {
            out.put("bbr_profile", profile);
            return;
        }
        throw new IllegalArgumentException("不支持的 HY2 BBR 档位");
    }

    private static Map<String, String> query(String rawQuery) {
        Map<String, String> map = new LinkedHashMap<>();
        if (rawQuery == null) {
            return map;
        }
        for (String pair : rawQuery.split("&")) {
            String[] kv = pair.split("=", 2);
            if (!kv[0].isEmpty()) {
                map.put(decode(kv[0]), kv.length == 2 ? decode(kv[1]) : "");
            }
        }
        return map;
    }

    private static JSONObject shadowsocks(String uriStr) throws Exception {
        String body = uriStr.substring(5);
        int hashIdx = body.indexOf('#');
        String tag = "";
        if (hashIdx >= 0) {
            tag = body.substring(hashIdx + 1);
            body = body.substring(0, hashIdx);
        }
        int qIdx = body.indexOf('?');
        if (qIdx >= 0) {
            body = body.substring(0, qIdx);
        }
        String decoded;
        if (!body.contains("@")) {
            decoded = new String(decode64(body), StandardCharsets.UTF_8);
        } else {
            int at = body.lastIndexOf('@');
            String userPart = body.substring(0, at);
            if (!userPart.contains(":")) {
                userPart = new String(decode64(userPart), StandardCharsets.UTF_8);
            }
            decoded = userPart + body.substring(at);
        }
        int atIdx = decoded.lastIndexOf('@');
        int colonIdx = decoded.indexOf(':');
        if (atIdx < 0 || colonIdx < 0 || colonIdx >= atIdx) {
            throw new IllegalArgumentException("SS 链接不完整");
        }
        String method = decode(decoded.substring(0, colonIdx));
        String password = decode(decoded.substring(colonIdx + 1, atIdx));
        URI hostUri = new URI("ss://" + decoded.substring(atIdx + 1));
        if (hostUri.getHost() == null || hostUri.getPort() < 1) {
            throw new IllegalArgumentException("SS 地址错误");
        }
        String finalTag = tag.isEmpty() ? hostUri.getHost() : decode(tag);
        return new JSONObject()
                .put("type", "shadowsocks")
                .put("tag", finalTag)
                .put("server", hostUri.getHost())
                .put("server_port", hostUri.getPort())
                .put("method", method)
                .put("password", password);
    }

    private static String txt(Map<String, Object> map, String key, String def) {
        Object val = map.get(key);
        return val == null ? def : String.valueOf(val);
    }

    private static JSONObject vmess(String uriStr) throws Exception {
        JSONObject v = new JSONObject(new String(decode64(uriStr.substring(8)), StandardCharsets.UTF_8));
        String server = v.getString("add");
        int port = Integer.parseInt(String.valueOf(v.get("port")));
        JSONObject out = new JSONObject()
                .put("type", "vmess")
                .put("tag", v.optString("ps", server))
                .put("server", server)
                .put("server_port", port)
                .put("uuid", v.getString("id"))
                .put("security", v.optString("scy", "auto"))
                .put("alter_id", Integer.parseInt(v.optString("aid", "0")));
        if ("tls".equals(v.optString("tls"))) {
            out.put("tls", new JSONObject()
                    .put("enabled", true)
                    .put("server_name", v.optString("sni", server)));
        }
        if ("ws".equals(v.optString("net"))) {
            out.put("transport", new JSONObject()
                    .put("type", "ws")
                    .put("path", v.optString("path", "/"))
                    .put("headers", new JSONObject().put("Host", v.optString("host", server))));
        }
        return out;
    }
}
