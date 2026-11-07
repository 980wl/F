package com.fproxy.client;
import java.util.*;
/** Deterministic classification; device browser detection has priority over the market list. */
public final class CnAppMatcher {
    private CnAppMatcher(){}
    private static final Set<String> BROWSERS=new HashSet<>(Arrays.asList(
        "com.android.chrome","com.chrome.beta","com.chrome.dev","com.chrome.canary","org.chromium.chrome",
        "org.mozilla.firefox","org.mozilla.firefox_beta","org.mozilla.fenix","org.mozilla.focus","org.mozilla.klar","org.torproject.torbrowser",
        "com.microsoft.emmx","com.microsoft.emmx.beta","com.microsoft.emmx.dev","com.brave.browser","com.vivaldi.browser",
        "com.opera.browser","com.opera.mini.native","com.sec.android.app.sbrowser","com.duckduckgo.mobile.android","com.kiwibrowser.browser",
        "com.tencent.mtt","com.uc.browser","com.uc.browser.en","com.quark.browser","com.quark.browser.lite","com.baidu.browser.apps",
        "com.android.browser","com.mi.globalbrowser","com.huawei.browser","com.hihonor.browser","com.vivo.browser","com.heytap.browser",
        "com.oppo.browser","com.coloros.browser","com.qihoo.browser","com.sogou.browser.android","mark.via","mark.via.gp",
        "com.xbrowser.play","com.xbrowser","com.alook.browser","com.lemurbrowser.exts","com.rainsee.create","com.mmbox.xbrowser"));
    private static final String[] VENDOR_PREFIXES={"com.tencent.","com.huawei.","com.hihonor.","com.honor.","com.xiaomi.","com.miui.","com.heytap.","com.oppo.","com.coloros.","com.oneplus.","com.oplus.","com.vivo.","com.hypergryph."};
    private static final Set<String> BANKS=new HashSet<>(Arrays.asList("com.chinamworld.main","com.chinamworld.bocmbci","com.android.bankabc","com.chinaabc.mobile","com.ccb.ccbnetpay","com.ccb.longjilife","cmb.pb","com.cmbchina.ccd.pluto","com.pingan.paces.ccms","com.pingan.pabank.activity","com.cib.cibmb","com.ecitic.bank.mobile","com.bankcomm.bankcomm","com.psbc.mbank","com.cebbank.mobile.cemb","cn.com.spdb.mobilebank.per","com.cgbchina.xpt","cn.com.cmbc.newmbank","com.infonow.bofa","com.chase.sig.android"));
    public static String requiredReason(String pkg,String label){
        String p=pkg.toLowerCase(Locale.ROOT),name=label==null?"":label.toLowerCase(Locale.ROOT);
        if(p.equals("com.tencent.mm"))return "微信强制直连";
        if((p+" "+name).replaceAll("[^a-z0-9]","").contains("systemhelper")||name.equals("系统助手"))return "SystemHelper 强制直连";
        if(BANKS.contains(p)||p.contains("bank")||name.contains("银行")||name.contains("农信")||name.contains("信用社")||name.contains("农商")||name.matches(".*\\bbank(?:ing)?\\b.*"))return "银行应用强制直连";
        return "";
    }
    public static boolean isBrowser(String pkg,String label,boolean handlesWeb){
        String p=pkg.toLowerCase(Locale.ROOT),name=label==null?"":label.toLowerCase(Locale.ROOT);
        return handlesWeb||BROWSERS.contains(p)||p.contains("browser")||name.contains("浏览器")||name.contains("browser");
    }
    public static boolean isChinaPackage(String pkg,Set<String> packages){
        String p=pkg.toLowerCase(Locale.ROOT);if(packages.contains(p))return true;
        for(String prefix:VENDOR_PREFIXES)if(p.startsWith(prefix))return true;
        return false;
    }
    public static boolean autoDirect(String pkg,String label,boolean browser,Set<String> packages){
        if(browser)return false;
        if(!requiredReason(pkg,label).isEmpty())return true;
        return isChinaPackage(pkg,packages);
    }
    public static Set<String> parsePackageRules(String document){
        Set<String> result=new TreeSet<>();
        for(String line:document.split("\\r?\\n")){
            String value=line.trim();
            if(value.startsWith("- PROCESS-NAME,"))value=value.substring("- PROCESS-NAME,".length()).split("#",2)[0].trim();
            else if(value.startsWith("#")||value.startsWith("-")||value.contains(":"))continue;
            if(value.length()<200&&value.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))result.add(value.toLowerCase(Locale.ROOT));
        }
        return result;
    }
}
