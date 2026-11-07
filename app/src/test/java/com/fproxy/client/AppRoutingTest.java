package com.fproxy.client;
import org.junit.Test;
import java.util.*;
import org.json.*;
import static org.junit.Assert.*;
public class AppRoutingTest {
    private final Set<String> cn=new HashSet<>(Arrays.asList("com.taobao.taobao","com.tencent.mtt","com.quark.browser","cn.example.game"));
    @Test public void listedMainlandAppsAndGamesBypass(){assertTrue(CnAppMatcher.autoDirect("com.taobao.taobao","淘宝",false,cn));assertTrue(CnAppMatcher.autoDirect("cn.example.game","游戏",false,cn));}
    @Test public void mainlandBrowsersAreNeverBulkBypassed(){
        for(String pkg:Arrays.asList("com.tencent.mtt","com.quark.browser","com.huawei.browser","com.android.browser")){
            boolean browser=CnAppMatcher.isBrowser(pkg,"浏览器",false);assertTrue(browser);assertFalse(CnAppMatcher.autoDirect(pkg,"浏览器",browser,cn));}
    }
    @Test public void unknownBrowsersAreDetectedFromAndroidWebHandling(){assertTrue(CnAppMatcher.isBrowser("org.custom.navigator","Navigator",true));assertFalse(CnAppMatcher.autoDirect("com.taobao.taobao","测试浏览器",true,cn));}
    @Test public void helperAndWechatAreProtectedRegardlessOfCountryIp(){
        assertTrue(CnAppMatcher.autoDirect("com.tencent.mm","微信",false,Collections.emptySet()));
        assertTrue(CnAppMatcher.autoDirect("org.custom.helper","SystemHelper",false,Collections.emptySet()));
        assertTrue(CnAppMatcher.autoDirect("com.vendor.system_helper","Helper",false,Collections.emptySet()));
    }
    @Test public void banksAreProtectedEvenWhenNotInMarketList(){
        assertTrue(CnAppMatcher.autoDirect("com.chinamworld.bocmbci","中国银行",false,Collections.emptySet()));
        assertTrue(CnAppMatcher.autoDirect("org.small.creditunion","某某农信",false,Collections.emptySet()));
        assertTrue(CnAppMatcher.autoDirect("org.custom.finance","示例银行",false,Collections.emptySet()));
    }
    @Test public void translatedForeignAppNamesDoNotImplyMainland(){assertFalse(CnAppMatcher.autoDirect("com.google.android.apps.maps","谷歌地图",false,cn));}
    @Test public void marketParserDoesNotTurnRegexOrHtmlIntoPackages(){
        Set<String> parsed=CnAppMatcher.parsePackageRules("payload:\n - PROCESS-NAME,com.tencent.mm # 微信\n - PROCESS-NAME-REGEX,^com.*\n<html>error</html>\n");
        assertEquals(Collections.singleton("com.tencent.mm"),parsed);
    }
    @Test public void splitRulesKeepChinaAndPrivateDirectBeforeProxyFallback()throws Exception{
        JSONObject root=new JSONObject(SingConfig.mainlandSplit(NodeParser.fromUri("hy2://pass@127.0.0.1:443"),"/private/geoip-cn.srs"));
        JSONObject route=root.getJSONObject("route");JSONArray rules=route.getJSONArray("rules");
        assertEquals("hijack-dns",rules.getJSONObject(0).getString("action"));
        assertTrue(rules.getJSONObject(1).getBoolean("ip_is_private"));assertEquals("F-DIRECT",rules.getJSONObject(1).getString("outbound"));
        assertEquals("geoip-cn",rules.getJSONObject(2).getJSONArray("rule_set").getString(0));assertEquals("F-DIRECT",rules.getJSONObject(2).getString("outbound"));
        assertEquals("F-PROXY",route.getString("final"));assertEquals("local",route.getJSONArray("rule_set").getJSONObject(0).getString("type"));
        assertEquals("https",root.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).getString("type"));
    }
    @Test public void realProbeStillForcesProxyInsteadOfMainlandDirect()throws Exception{
        JSONObject root=new JSONObject(SingConfig.probe(NodeParser.fromUri("hy2://pass@127.0.0.1:443"),12345));
        assertEquals(0,root.getJSONObject("route").getJSONArray("rules").length());assertFalse(root.getJSONObject("route").has("rule_set"));
    }
}
