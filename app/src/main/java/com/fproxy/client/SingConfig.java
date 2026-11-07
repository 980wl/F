package com.fproxy.client;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class SingConfig {
    private SingConfig() {
    }

    public static String global(JSONObject node) throws JSONException {
        if (node == null) {
            throw new IllegalArgumentException("请先导入并选择节点");
        }
        JSONObject proxyOutbound = new JSONObject(node.toString());
        proxyOutbound.put("tag", "F-PROXY");

        JSONObject tls = proxyOutbound.optJSONObject("tls");
        JSONObject reality = tls == null ? null : tls.optJSONObject("reality");
        if (reality != null && tls.optBoolean("enabled") && reality.optBoolean("enabled")) {
            NodeParser.enableRealityUtls(tls);
        }

        JSONObject tunInbound = new JSONObject()
                .put("type", "tun")
                .put("tag", "F-TUN")
                .put("address", new JSONArray()
                        .put("172.19.0.1/30")
                        .put("fdfe:dcba:9876::1/126"))
                .put("mtu", 1400)
                .put("auto_route", true)
                .put("stack", "system");

        JSONObject root = new JSONObject()
                .put("log", new JSONObject().put("level", "warn"))
                .put("inbounds", new JSONArray().put(tunInbound))
                .put("outbounds", new JSONArray()
                        .put(proxyOutbound)
                        .put(new JSONObject()
                                .put("type", "direct")
                                .put("tag", "F-DIRECT")));

        // 远程加密 DoH（走代理解析境外域名，防止 DNS 污染与运营商窥探）
        JSONObject remoteDns = new JSONObject()
                .put("type", "https")
                .put("tag", "dns")
                .put("server", "1.1.1.1")
                .put("server_port", 443)
                .put("path", "/dns-query")
                .put("tls", new JSONObject()
                        .put("enabled", true)
                        .put("server_name", "cloudflare-dns.com"))
                .put("detour", "F-PROXY");

        // 修复明文 UDP 223.5.5.5 泄露：将 bootstrap 升级为加密 DoH（用于解析节点服务器域名与国内域名）
        JSONObject bootstrapDns = new JSONObject()
                .put("type", "https")
                .put("tag", "bootstrap")
                .put("server", "223.5.5.5")
                .put("server_port", 443)
                .put("path", "/dns-query")
                .put("tls", new JSONObject()
                        .put("enabled", true)
                        .put("server_name", "dns.alidns.com"));

        JSONObject dns = new JSONObject()
                .put("servers", new JSONArray()
                        .put(remoteDns)
                        .put(bootstrapDns))
                .put("final", "dns")
                .put("independent_cache", true);

        root.put("dns", dns);

        JSONObject route = new JSONObject()
                .put("auto_detect_interface", true)
                .put("default_domain_resolver", "bootstrap")
                .put("rules", new JSONArray()
                        .put(new JSONObject()
                                .put("port", 53)
                                .put("action", "hijack-dns")))
                .put("final", "F-PROXY");

        root.put("route", route);
        return root.toString();
    }

    public static String mainlandSplit(JSONObject node, String geoipPath) throws JSONException {
        return mainlandSplit(node, geoipPath, null);
    }

    /**
     * 同时加载 geoip-cn.srs 与 geosite-cn.srs，实现域名级与 IP 级双重分流，
     * 避免访问境外域名时因 IP 规则匹配而触发向国内 DNS 发起明文解析。
     */
    public static String mainlandSplit(JSONObject node, String geoipPath, String geositePath) throws JSONException {
        if (geoipPath == null || geoipPath.isEmpty()) {
            throw new IllegalArgumentException("缺少中国大陆 IP 规则");
        }
        JSONObject root = new JSONObject(global(node));
        JSONObject route = root.getJSONObject("route");
        JSONObject dns = root.getJSONObject("dns");

        JSONArray ruleSets = new JSONArray();
        ruleSets.put(new JSONObject()
                .put("type", "local")
                .put("tag", "geoip-cn")
                .put("format", "binary")
                .put("path", geoipPath));

        boolean hasGeosite = geositePath != null && !geositePath.isEmpty();
        if (hasGeosite) {
            ruleSets.put(new JSONObject()
                    .put("type", "local")
                    .put("tag", "geosite-cn")
                    .put("format", "binary")
                    .put("path", geositePath));

            // 国内域名使用加密 bootstrap DoH 解析，其余境外域名默认走代理 DoH (final = "dns")
            JSONArray dnsRules = new JSONArray();
            dnsRules.put(new JSONObject()
                    .put("rule_set", new JSONArray().put("geosite-cn"))
                    .put("server", "bootstrap"));
            dns.put("rules", dnsRules);
        }

        route.put("rule_set", ruleSets);

        JSONArray routeRules = new JSONArray();
        routeRules.put(new JSONObject()
                .put("port", 53)
                .put("action", "hijack-dns"));
        routeRules.put(new JSONObject()
                .put("ip_is_private", true)
                .put("action", "route")
                .put("outbound", "F-DIRECT"));

        // 优先匹配 geosite-cn 域名规则直连，避免在域名阶段触发额外 DNS 解析
        if (hasGeosite) {
            routeRules.put(new JSONObject()
                    .put("rule_set", new JSONArray().put("geosite-cn"))
                    .put("action", "route")
                    .put("outbound", "F-DIRECT"));
        }

        routeRules.put(new JSONObject()
                .put("rule_set", new JSONArray().put("geoip-cn"))
                .put("action", "route")
                .put("outbound", "F-DIRECT"));

        route.put("rules", routeRules);
        return root.toString();
    }

    public static String probe(JSONObject node, int port) throws JSONException {
        return probe(node, port, null, null);
    }

    /**
     * 生成节点延迟测试配置，支持为本地 127.0.0.1 监听端口设置随机一次性鉴权凭证，
     * 防止测速期间本机其他应用未授权连接该端口。
     */
    public static String probe(JSONObject node, int port, String username, String password) throws JSONException {
        JSONObject root = new JSONObject(global(node));
        JSONObject inbound = new JSONObject()
                .put("type", "mixed")
                .put("tag", "F-PROBE")
                .put("listen", "127.0.0.1")
                .put("listen_port", port);

        if (username != null && !username.isEmpty() && password != null && !password.isEmpty()) {
            inbound.put("users", new JSONArray()
                    .put(new JSONObject()
                            .put("username", username)
                            .put("password", password)));
        }

        root.put("inbounds", new JSONArray().put(inbound));
        root.getJSONObject("route").put("rules", new JSONArray());
        root.getJSONArray("outbounds").getJSONObject(0).put("connect_timeout", "5s");
        return root.toString();
    }
}
