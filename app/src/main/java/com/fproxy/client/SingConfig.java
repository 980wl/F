package com.fproxy.client;

import org.json.JSONArray;
import org.json.JSONObject;

/** Minimal sing-box 1.14+ TUN configuration. No bypass rules: GLOBAL proxy by default. */
public final class SingConfig {
    private SingConfig(){}
    public static String global(JSONObject selected) throws Exception {
        if(selected==null)throw new IllegalArgumentException("请先导入并选择节点");
        JSONObject outbound=new JSONObject(selected.toString()).put("tag","F-PROXY");
        JSONObject tls = outbound.optJSONObject("tls");
        JSONObject reality = tls == null ? null : tls.optJSONObject("reality");
        if (reality != null && tls.optBoolean("enabled") && reality.optBoolean("enabled")) {
            // Repair nodes saved before the importer emitted the required uTLS settings.
            NodeParser.enableRealityUtls(tls);
        }
        JSONObject tun=new JSONObject().put("type","tun").put("tag","F-TUN")
                .put("address",new JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
                .put("mtu",1400).put("auto_route",true).put("stack","system");
        JSONObject root=new JSONObject().put("log",new JSONObject().put("level","warn"))
                .put("inbounds",new JSONArray().put(tun))
                .put("outbounds",new JSONArray().put(outbound)
                        .put(new JSONObject().put("type","direct").put("tag","F-DIRECT")))
                .put("dns",new JSONObject().put("servers",new JSONArray().put(
                        new JSONObject().put("type","https").put("tag","dns").put("server","1.1.1.1").put("server_port",443).put("path","/dns-query")
                            .put("tls",new JSONObject().put("enabled",true).put("server_name","cloudflare-dns.com")).put("detour","F-PROXY"))
                        .put(new JSONObject().put("type","udp").put("tag","bootstrap").put("server","223.5.5.5")))
                        .put("final","dns"))
                .put("route",new JSONObject().put("auto_detect_interface",true).put("default_domain_resolver","bootstrap")
                        .put("rules",new JSONArray().put(new JSONObject().put("port",53).put("action","hijack-dns")))
                        .put("final","F-PROXY"));
        return root.toString();
    }
    public static String probe(JSONObject selected,int port)throws Exception{
        JSONObject root=new JSONObject(global(selected));
        root.put("inbounds",new JSONArray().put(new JSONObject().put("type","mixed").put("tag","F-PROBE")
            .put("listen","127.0.0.1").put("listen_port",port)));
        root.getJSONObject("route").put("rules",new JSONArray());
        root.getJSONArray("outbounds").getJSONObject(0).put("connect_timeout","5s");
        return root.toString();
    }

    /** Production policy: local/mainland destinations direct, everything else through the selected node. */
    public static String mainlandSplit(JSONObject selected,String mainlandRules)throws Exception{
        if(mainlandRules==null||mainlandRules.isEmpty())throw new IllegalArgumentException("缺少中国大陆 IP 规则");
        JSONObject root=new JSONObject(global(selected));
        JSONObject route=root.getJSONObject("route");
        route.put("rule_set",new JSONArray().put(new JSONObject().put("type","local").put("tag","geoip-cn")
            .put("format","binary").put("path",mainlandRules)));
        route.put("rules",new JSONArray()
            .put(new JSONObject().put("port",53).put("action","hijack-dns"))
            .put(new JSONObject().put("ip_is_private",true).put("action","route").put("outbound","F-DIRECT"))
            .put(new JSONObject().put("rule_set",new JSONArray().put("geoip-cn")).put("action","route").put("outbound","F-DIRECT")));
        return root.toString();
    }

}
