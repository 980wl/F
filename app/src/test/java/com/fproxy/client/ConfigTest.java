package com.fproxy.client;
import org.junit.Test;
import org.json.*;
import static org.junit.Assert.*;
public class ConfigTest {
 @Test public void hy2DefaultPortAndTls()throws Exception{
  JSONObject n=NodeParser.fromUri("hy2://p%2Bss@example.com?sni=edge.example&insecure=true&security=none#Test");
  assertEquals(443,n.getInt("server_port"));assertEquals("p+ss",n.getString("password"));
  assertTrue(n.getJSONObject("tls").getBoolean("enabled"));assertTrue(n.getJSONObject("tls").getBoolean("insecure"));
 }
 @Test public void tuicCredentialsAndIpv6()throws Exception{
  JSONObject n=NodeParser.fromUri("tuic://00000000-0000-0000-0000-000000000001:p%3Aa@[2001:db8::1]:443?congestion_control=cubic&alpn=h3");
  assertEquals("2001:db8::1",n.getString("server"));assertEquals("p:a",n.getString("password"));
  assertEquals("cubic",n.getString("congestion_control"));assertEquals("h3",n.getJSONObject("tls").getJSONArray("alpn").getString(0));
 }
 @Test public void globalRoutesAndDns()throws Exception{
  JSONObject root=new JSONObject(SingConfig.global(NodeParser.fromUri("hy2://pass@127.0.0.1:443")));
  assertEquals(2,root.getJSONArray("inbounds").getJSONObject(0).getJSONArray("address").length());
  assertEquals("F-PROXY",root.getJSONObject("route").getString("final"));
  assertEquals("F-PROXY",root.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).getString("detour"));
  assertEquals("bootstrap",root.getJSONObject("route").getString("default_domain_resolver"));
 }
 @Test public void stableVersionComparison(){assertTrue(UpdateManager.compareVersion("1.14.10","1.14.4")>0);}
 @Test(expected=IllegalArgumentException.class) public void emptyNodeRejected()throws Exception{SingConfig.global(null);}
 @Test public void probeUsesLoopbackProxyWithoutTun()throws Exception{
  JSONObject root=new JSONObject(SingConfig.probe(NodeParser.fromUri("hy2://pass@127.0.0.1:443"),12345));
  JSONObject inbound=root.getJSONArray("inbounds").getJSONObject(0);
  assertEquals("mixed",inbound.getString("type"));assertEquals("127.0.0.1",inbound.getString("listen"));
  assertEquals(12345,inbound.getInt("listen_port"));
  assertEquals("F-PROXY",root.getJSONObject("route").getString("final"));
  assertEquals(0,root.getJSONObject("route").getJSONArray("rules").length());
 }

 @Test public void interfacePrefixesNeverCarryIpv6Zones(){
  assertEquals("fe80::abcd/64",AddressText.prefix("fe80::abcd%wlan0",64));
  assertEquals("fe80::1/64",AddressText.prefix("fe80::1%4",64));
  assertEquals("192.0.2.1/24",AddressText.prefix("192.0.2.1",24));
 }

 @Test public void remoteDnsIsCloudflareDohThroughProxy()throws Exception{
  JSONObject root=new JSONObject(SingConfig.global(NodeParser.fromUri("hy2://pass@127.0.0.1:443")));
  JSONObject dns=root.getJSONObject("dns").getJSONArray("servers").getJSONObject(0);
  assertEquals("https",dns.getString("type"));assertEquals("1.1.1.1",dns.getString("server"));
  assertEquals(443,dns.getInt("server_port"));assertEquals("/dns-query",dns.getString("path"));
  assertEquals("cloudflare-dns.com",dns.getJSONObject("tls").getString("server_name"));
  assertEquals("F-PROXY",dns.getString("detour"));
 }


 @Test public void importsHysteriaGeckoAndBbrFromShareLink() throws Exception {
  JSONObject node = NodeParser.fromUri("hy2://auth@example.com:443?obfs=gecko&obfs-password=secret&bbr-profile=conservative");
  assertEquals("gecko", node.getJSONObject("obfs").getString("type"));
  assertEquals("secret", node.getJSONObject("obfs").getString("password"));
  assertEquals("conservative", node.getString("bbr_profile"));
 }

 @Test public void preservesExplicitGeckoPacketSizesFromClash() throws Exception {
  JSONObject node = NodeParser.parseDocument("""
      proxies:
        - {name: Mobile, type: hysteria2, server: example.com, port: 443, password: auth, obfs: gecko, obfs-password: secret, obfs-min-packet-size: 512, obfs-max-packet-size: 1100, bbr-profile: conservative}
      """).get(0);
  JSONObject obfs = node.getJSONObject("obfs");
  assertEquals("gecko", obfs.getString("type"));
  assertEquals(512, obfs.getInt("min_packet_size"));
  assertEquals(1100, obfs.getInt("max_packet_size"));
  assertEquals("conservative", node.getString("bbr_profile"));
 }
}
