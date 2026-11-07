package com.fproxy.client;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts common share links, sing-box JSON, base64 subscriptions and Clash proxy lists.
 * Unknown formats are rejected rather than silently replacing valid subscriptions.
 */
public final class NodeParser {
    private NodeParser() {}
    public static final int MAX_NODES = 2000;

    public static JSONObject fromUri(String value) throws Exception {
        String original = value.trim();
        if (original.startsWith("vmess://")) return vmess(original);
        URI u = new URI(original);
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!Arrays.asList("hysteria2", "hy2", "tuic", "vless", "trojan", "ss").contains(scheme))
            throw new IllegalArgumentException("尚不支持此节点链接协议: " + scheme);
        if (scheme.equals("ss")) return shadowsocks(original);
        String server = u.getHost();
        int port = u.getPort();
        if(port==-1&&(scheme.equals("hy2")||scheme.equals("hysteria2")))port=443;
        if(server!=null&&server.startsWith("[")&&server.endsWith("]"))server=server.substring(1,server.length()-1);
        if (server == null || server.isEmpty() || port <= 0 || port > 65535)
            throw new IllegalArgumentException("节点缺少有效的服务器地址或端口");
        String fragment = decode(u.getRawFragment());
        String name = fragment == null || fragment.isBlank() ? server + ':' + port : fragment;
        Map<String,String> q = query(u.getRawQuery());
        String rawUserInfo = u.getRawUserInfo();
        String user = decode(rawUserInfo == null ? "" : rawUserInfo);
        JSONObject out = new JSONObject().put("tag", name).put("server",server).put("server_port",port);
        if (scheme.equals("hy2") || scheme.equals("hysteria2")) {
            out.put("type", "hysteria2").put("password", user);
            String obfs = q.getOrDefault("obfs", "");
            if (!obfs.isEmpty()) {
                out.put("obfs", hysteriaObfuscation(obfs, q.getOrDefault("obfs-password", "")));
            }
            putHysteriaBbrProfile(out, q.getOrDefault("bbr-profile", ""));
            if (q.containsKey("upmbps")) out.put("up_mbps",Integer.parseInt(q.get("upmbps")));
            if (q.containsKey("downmbps")) out.put("down_mbps",Integer.parseInt(q.get("downmbps")));
        } else if (scheme.equals("tuic")) {
            String[] part = user.split(":", 2);
            if (part.length != 2) throw new IllegalArgumentException("TUIC 必须提供 uuid:password");
            out.put("type", "tuic").put("uuid", part[0]).put("password", part[1]);
            out.put("congestion_control",q.getOrDefault("congestion_control","bbr"));
            if(q.containsKey("udp_relay_mode")) out.put("udp_relay_mode",q.get("udp_relay_mode"));
        } else if (scheme.equals("vless")) {
            out.put("type", "vless").put("uuid",user);
            if (q.containsKey("flow")) out.put("flow", q.get("flow"));
            String network = q.getOrDefault("type", "tcp");
            if ("ws".equals(network)) {
                JSONObject transport = new JSONObject().put("type","ws");
                if (q.containsKey("path")) transport.put("path",q.get("path"));
                if (q.containsKey("host")) transport.put("headers",new JSONObject().put("Host",q.get("host")));
                out.put("transport",transport);
            }
        } else {
            out.put("type","trojan").put("password",user);
        }
        String security = q.getOrDefault("security",scheme.equals("vless")?"none":"tls");
        if(scheme.equals("hy2")||scheme.equals("hysteria2")||scheme.equals("tuic")||scheme.equals("trojan"))security="tls";
        if (!"none".equals(security)) {
            JSONObject tls = new JSONObject().put("enabled", true);
            String sni = q.getOrDefault("sni",q.get("peer"));
            if(sni != null && !sni.isBlank()) tls.put("server_name",sni);
            if("1".equals(q.get("insecure")) || "true".equalsIgnoreCase(q.get("insecure")) || "1".equals(q.get("allowInsecure")) || "true".equalsIgnoreCase(q.get("allowInsecure"))) tls.put("insecure",true);
            String alpn = q.get("alpn");
            if(alpn != null && !alpn.isEmpty()) tls.put("alpn", new JSONArray(alpn.split(",")));
            if("reality".equals(security)) {
                tls.put("reality",new JSONObject().put("enabled",true)
                        .put("public_key",q.getOrDefault("pbk",q.getOrDefault("public_key","")))
                        .put("short_id",q.getOrDefault("sid","")));
            }
            out.put("tls",tls);
        }
        return out;
    }

    private static JSONObject vmess(String input) throws Exception {
        String s = input.substring("vmess://".length());
        JSONObject v = new JSONObject(new String(decode64(s), StandardCharsets.UTF_8));
        String server = v.getString("add"); int port = Integer.parseInt(String.valueOf(v.get("port")));
        JSONObject o = new JSONObject().put("type","vmess").put("tag",v.optString("ps",server))
                .put("server",server).put("server_port",port).put("uuid",v.getString("id"))
                .put("security",v.optString("scy","auto"))
                .put("alter_id", Integer.parseInt(v.optString("aid","0")));
        if ("tls".equals(v.optString("tls"))) o.put("tls",new JSONObject().put("enabled",true)
                .put("server_name",v.optString("sni",server)));
        if("ws".equals(v.optString("net"))) o.put("transport",new JSONObject().put("type","ws")
                .put("path",v.optString("path","/"))
                .put("headers",new JSONObject().put("Host",v.optString("host",server))));
        return o;
    }

    private static JSONObject shadowsocks(String input) throws Exception {
        String remain = input.substring("ss://".length());
        String rawFragment = "";
        int hash = remain.indexOf('#');
        if(hash>=0){rawFragment=remain.substring(hash+1);remain=remain.substring(0,hash);}
        int query=remain.indexOf('?'); if(query>=0)remain=remain.substring(0,query);
        if (!remain.contains("@")) remain = new String(decode64(remain), StandardCharsets.UTF_8);
        else {
            int at=remain.lastIndexOf('@');String cred=remain.substring(0,at);
            if(!cred.contains(":")) cred=new String(decode64(cred),StandardCharsets.UTF_8);
            remain=cred+remain.substring(at);
        }
        int at=remain.lastIndexOf('@');
        int colon=remain.indexOf(':');
        if(at<0||colon<0||colon>=at)throw new IllegalArgumentException("SS 链接不完整");
        String method=decode(remain.substring(0,colon));String pass=decode(remain.substring(colon+1,at));
        String addr=remain.substring(at+1);
        URI hostUri=new URI("ss://"+addr);
        if(hostUri.getHost()==null||hostUri.getPort()<1)throw new IllegalArgumentException("SS 地址错误");
        String name=rawFragment.isEmpty()?hostUri.getHost():decode(rawFragment);
        return new JSONObject().put("type","shadowsocks").put("tag",name).put("server",hostUri.getHost())
                .put("server_port",hostUri.getPort()).put("method",method).put("password",pass);
    }

    public static List<JSONObject> parseDocument(String content) throws Exception {
        String s=content.trim();
        if(s.length()>2_000_000)throw new IllegalArgumentException("订阅内容超过 2 MB");
        if(s.isEmpty())throw new IllegalArgumentException("订阅内容为空");
        if(s.startsWith("{")) {
            JSONObject obj=new JSONObject(s);
            JSONArray arr = obj.optJSONArray("outbounds");
            if(arr==null)arr=obj.optJSONArray("nodes");
            if(arr!=null)return parseJsonArray(arr);
        }
        if(s.startsWith("["))return parseJsonArray(new JSONArray(s));
        if(s.contains("proxies:") && (s.startsWith("proxies:")||s.startsWith("mixed-port:")||s.contains("\nproxies:"))) {
            LoaderOptions opts=new LoaderOptions();opts.setMaxAliasesForCollections(20);opts.setCodePointLimit(2_000_000);
            Object doc=new Yaml(new SafeConstructor(opts)).load(s);
            if(doc instanceof Map<?,?>) {
                Object proxies=((Map<?,?>)doc).get("proxies");
                if(proxies instanceof List<?>) {
                    List<JSONObject> result=new ArrayList<>();
                    for(Object proxy:(List<?>)proxies){
                        if(proxy instanceof Map<?,?>) {
                            JSONObject obj=fromClash((Map<?,?>)proxy);
                            if(obj!=null)result.add(obj);
                        }
                        if(result.size()>=MAX_NODES)break;
                    }
                    if(!result.isEmpty())return result;
                }
            }
            throw new IllegalArgumentException("Clash 订阅中没有可识别的节点");
        }
        if(!s.contains("://")) {
            try {
                String decoded=new String(decode64(s.replaceAll("\\s+","")),StandardCharsets.UTF_8);
                if(decoded.contains("://")||decoded.startsWith("{"))return parseDocument(decoded);
            } catch(IllegalArgumentException ignored) {}
        }
        List<JSONObject> result=new ArrayList<>();
        for(String line:s.split("\\r?\\n")) {
            line=line.trim();if(line.isEmpty()||line.startsWith("#"))continue;
            try { result.add(fromUri(line)); } catch(Exception ignored) { /* skip unsupported protocols */ }
            if(result.size()>=MAX_NODES)break;
        }
        if(result.isEmpty())throw new IllegalArgumentException("没有可导入的有效节点（支持 hy2/tuic/vless/vmess/trojan/ss）");
        return result;
    }

    private static List<JSONObject> parseJsonArray(JSONArray arr) throws Exception {
        List<JSONObject> result=new ArrayList<>();
        for(int i=0;i<arr.length()&&result.size()<MAX_NODES;i++){
            JSONObject o=arr.optJSONObject(i);if(o==null)continue;
            String t=o.optString("type");
            if(Arrays.asList("hysteria2","tuic","vless","vmess","trojan","shadowsocks","hysteria").contains(t)){
                if(o.optString("server").isEmpty()||o.optInt("server_port")<1)continue;
                result.add(new JSONObject(o.toString()));
            }
        }
        if(result.isEmpty())throw new IllegalArgumentException("JSON 订阅没有可用出站节点");
        return result;
    }

    private static JSONObject fromClash(Map<?,?> m) throws Exception {
        String type=txt(m,"type", "");
        String host=txt(m,"server","");int port=num(m,"port",0);
        if(host.isBlank()||port<1||port>65535)return null;
        JSONObject o=new JSONObject().put("server",host).put("server_port",port)
                .put("tag",txt(m,"name",host+":"+port));
        switch(type){
            case "hysteria2":
                o.put("type","hysteria2").put("password",txt(m,"password",""));
                String obfs = txt(m, "obfs", "");
                if ("salamander".equals(obfs) || "gecko".equals(obfs)) {
                    JSONObject settings = hysteriaObfuscation(obfs, txt(m, "obfs-password", ""));
                    if ("gecko".equals(obfs)) {
                        if (m.containsKey("obfs-min-packet-size")) settings.put("min_packet_size", Integer.parseInt(txt(m, "obfs-min-packet-size", "")));
                        if (m.containsKey("obfs-max-packet-size")) settings.put("max_packet_size", Integer.parseInt(txt(m, "obfs-max-packet-size", "")));
                    }
                    o.put("obfs", settings);
                } else if (!obfs.isEmpty()) {
                    // Skip unsupported nodes instead of connecting with obfuscation disabled.
                    return null;
                }
                putHysteriaBbrProfile(o, txt(m, "bbr-profile", ""));
                break;
            case "tuic":
                o.put("type","tuic").put("uuid",txt(m,"uuid",""))
                 .put("password",txt(m,"password",""))
                 .put("congestion_control",txt(m,"congestion-controller","bbr"));break;
            case "ss":
                o.put("type","shadowsocks").put("method",txt(m,"cipher",""))
                 .put("password",txt(m,"password",""));break;
            case "vless":
                o.put("type","vless").put("uuid",txt(m,"uuid",""));
                if(m.containsKey("flow"))o.put("flow",txt(m,"flow",""));break;
            case "trojan":
                o.put("type","trojan").put("password",txt(m,"password",""));break;
            case "vmess":
                o.put("type","vmess").put("uuid",txt(m,"uuid",""))
                    .put("alter_id",num(m,"alterId",0)).put("security",txt(m,"cipher","auto"));break;
            default:return null;
        }
        if(Boolean.TRUE.equals(m.get("tls"))||type.equals("tuic")||type.equals("hysteria2")||type.equals("trojan")){
            JSONObject tls=new JSONObject().put("enabled",true);
            if(m.containsKey("servername"))tls.put("server_name",txt(m,"servername",host));
            if(m.containsKey("sni"))tls.put("server_name",txt(m,"sni",host));
            if(Boolean.TRUE.equals(m.get("skip-cert-verify")))tls.put("insecure",true);
            if(m.get("alpn") instanceof List<?>)tls.put("alpn",new JSONArray((List<?>)m.get("alpn")));
            o.put("tls",tls);
        }
        String network=txt(m,"network", "");
        if("ws".equals(network) && m.get("ws-opts") instanceof Map<?,?>){
            Map<?,?> w=(Map<?,?>)m.get("ws-opts");
            JSONObject trans=new JSONObject().put("type","ws").put("path",txt(w,"path","/"));
            if(w.get("headers") instanceof Map<?,?>) {
                JSONObject h=new JSONObject();((Map<?,?>)w.get("headers")).forEach((k,v)->{try{h.put(String.valueOf(k),v);}catch(Exception ignored){}});
                trans.put("headers",h);
            }
            o.put("transport",trans);
        }
        return o;
    }

    public static String fingerprint(JSONObject node) throws Exception {
        // Includes outbound credentials in hash input, but hash alone is persisted as key.
        JSONObject copy=new JSONObject(node.toString()); copy.remove("tag");
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(copy.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder sb=new StringBuilder();for(int i=0;i<16;i++)sb.append(String.format("%02x",digest[i]));return sb.toString();
    }
    private static JSONObject hysteriaObfuscation(String type, String password) throws Exception {
        if (!"salamander".equals(type) && !"gecko".equals(type)) {
            throw new IllegalArgumentException("不支持的 HY2 混淆类型");
        }
        return new JSONObject().put("type", type).put("password", password);
    }

    private static void putHysteriaBbrProfile(JSONObject outbound, String profile) throws Exception {
        if (profile.isEmpty()) return;
        if (!Arrays.asList("conservative", "standard", "aggressive").contains(profile)) {
            throw new IllegalArgumentException("不支持的 HY2 BBR 档位");
        }
        outbound.put("bbr_profile", profile);
    }

    private static int num(Map<?,?>m,String k,int d){try{return Integer.parseInt(String.valueOf(m.get(k)));}catch(Exception e){return d;}}
    private static String txt(Map<?,?>m,String k,String d){Object v=m.get(k);return v==null?d:String.valueOf(v);}
    private static String decode(String s){
        try{return s==null?null:URLDecoder.decode(s.replace("+","%2B"),"UTF-8");}
        catch(java.io.UnsupportedEncodingException impossible){throw new AssertionError(impossible);}
    }
    private static Map<String,String> query(String raw){
        Map<String,String> m=new LinkedHashMap<>();if(raw==null)return m;
        for(String entry:raw.split("&")){String[] kv=entry.split("=",2);if(!kv[0].isEmpty())m.put(decode(kv[0]),kv.length==2?decode(kv[1]):"");}
        return m;
    }
    private static byte[] decode64(String s){
        String clean=s.replaceAll("\\s+","");
        try { return Base64.decode(clean,Base64.DEFAULT); }
        catch(IllegalArgumentException bad){ return Base64.decode(clean,Base64.URL_SAFE|Base64.NO_WRAP); }
    }
}
