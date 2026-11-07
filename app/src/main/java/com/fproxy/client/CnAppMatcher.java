package com.fproxy.client;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

public final class CnAppMatcher {
    private static final Set<String> BROWSERS = new HashSet<>(Arrays.asList(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "org.chromium.chrome",
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "org.mozilla.fenix",
            "org.mozilla.focus",
            "org.mozilla.klar",
            "org.torproject.torbrowser",
            "com.microsoft.emmx",
            "com.microsoft.emmx.beta",
            "com.microsoft.emmx.dev",
            "com.brave.browser",
            "com.vivaldi.browser",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.sec.android.app.sbrowser",
            "com.duckduckgo.mobile.android",
            "com.kiwibrowser.browser",
            "com.tencent.mtt",
            "com.uc.browser",
            "com.uc.browser.en",
            "com.quark.browser",
            "com.quark.browser.lite",
            "com.baidu.browser.apps",
            "com.android.browser",
            "com.mi.globalbrowser",
            "com.huawei.browser",
            "com.hihonor.browser",
            "com.vivo.browser",
            "com.heytap.browser",
            "com.oppo.browser",
            "com.coloros.browser",
            "com.qihoo.browser",
            "com.sogou.browser.android",
            "mark.via",
            "mark.via.gp",
            "com.xbrowser.play",
            "com.xbrowser",
            "com.alook.browser",
            "com.lemurbrowser.exts",
            "com.rainsee.create",
            "com.mmbox.xbrowser"
    ));

    private static final Set<String> BANKS = new HashSet<>(Arrays.asList(
            "com.chinamworld.main",
            "com.chinamworld.bocmbci",
            "com.android.bankabc",
            "com.chinaabc.mobile",
            "com.ccb.ccbnetpay",
            "com.ccb.longjilife",
            "cmb.pb",
            "com.cmbchina.ccd.pluto",
            "com.pingan.paces.ccms",
            "com.pingan.pabank.activity",
            "com.cib.cibmb",
            "com.ecitic.bank.mobile",
            "com.bankcomm.bankcomm",
            "com.psbc.mbank",
            "com.cebbank.mobile.cemb",
            "cn.com.spdb.mobilebank.per",
            "com.cgbchina.xpt",
            "cn.com.cmbc.newmbank",
            "com.infonow.bofa",
            "com.chase.sig.android"
    ));

    // 预编译正则表达式，避免在解析数千行规则或扫描本机应用时重复编译 Pattern
    private static final Pattern LINE_SPLIT_PATTERN = Pattern.compile("\\r?\\n");
    private static final Pattern VALID_PACKAGE_PATTERN = Pattern.compile("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    private static final Pattern NON_ALPHANUM_PATTERN = Pattern.compile("[^a-z0-9]");
    private static final Pattern BANK_WORD_PATTERN = Pattern.compile(".*\\bbank(?:ing)?\\b.*");

    private CnAppMatcher() {
    }

    public static boolean autoDirect(String pkg, String label, boolean browser, Set<String> cnRules) {
        if (browser) {
            return false;
        }
        if (!requiredReason(pkg, label).isEmpty()) {
            return true;
        }
        return isChinaPackage(pkg, cnRules);
    }

    public static boolean isBrowser(String pkg, String label, boolean inBrowserQuery) {
        String lowerPkg = pkg.toLowerCase(Locale.ROOT);
        String lowerLabel = label == null ? "" : label.toLowerCase(Locale.ROOT);
        return inBrowserQuery
                || BROWSERS.contains(lowerPkg)
                || lowerPkg.contains("browser")
                || lowerLabel.contains("浏览器")
                || lowerLabel.contains("browser");
    }

    /**
     * 仅依据精确包名规则集（cn-apps.txt / cn-games.txt）判断大陆应用，
     * 移除原有的宽泛厂商前缀（com.tencent.* / com.xiaomi.* 等）一刀切匹配，
     * 防止国际服游戏或海外版应用被默认绕过 VPN 导致真实 IP 泄露。
     */
    public static boolean isChinaPackage(String pkg, Set<String> cnRules) {
        if (pkg == null || cnRules == null) {
            return false;
        }
        String lowerPkg = pkg.toLowerCase(Locale.ROOT);
        return cnRules.contains(lowerPkg);
    }

    public static Set<String> parsePackageRules(String rawText) {
        Set<String> result = new TreeSet<>();
        if (rawText == null || rawText.isEmpty()) {
            return result;
        }
        String[] lines = LINE_SPLIT_PATTERN.split(rawText);
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("- PROCESS-NAME,")) {
                line = line.substring(15);
                int commentIdx = line.indexOf('#');
                if (commentIdx >= 0) {
                    line = line.substring(0, commentIdx);
                }
                line = line.trim();
            } else if (line.startsWith("#") || line.startsWith("-") || line.contains(":")) {
                continue;
            }
            if (line.length() < 200 && VALID_PACKAGE_PATTERN.matcher(line).matches()) {
                result.add(line.toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    public static String requiredReason(String pkg, String label) {
        String lowerPkg = pkg.toLowerCase(Locale.ROOT);
        String lowerLabel = label == null ? "" : label.toLowerCase(Locale.ROOT);
        if ("com.tencent.mm".equals(lowerPkg)) {
            return "微信强制直连";
        }
        String normalized = NON_ALPHANUM_PATTERN.matcher(lowerPkg + " " + lowerLabel).replaceAll("");
        if (normalized.contains("systemhelper") || "系统助手".equals(lowerLabel)) {
            return "SystemHelper 强制直连";
        }
        if (BANKS.contains(lowerPkg)
                || lowerPkg.contains("bank")
                || lowerLabel.contains("银行")
                || lowerLabel.contains("农信")
                || lowerLabel.contains("信用社")
                || lowerLabel.contains("农商")
                || BANK_WORD_PATTERN.matcher(lowerLabel).matches()) {
            return "银行应用强制直连";
        }
        return "";
    }
}
