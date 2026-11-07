package com.fproxy.client;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class UpdateManager {
    public static final String RELEASES = "https://api.github.com/repos/SagerNet/sing-box/releases/latest";
    public static final String[] RULE_NAMES = new String[]{"geoip-cn.srs", "geosite-cn.srs"};
    public static final String[] RULE_URLS = new String[]{
            "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-cn.srs",
            "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-cn.srs"
    };

    public interface Progress {
        void step(String message);
    }

    public static class Result {
        public JSONObject appRelease;
        public String officialStableVersion = BuildConfig.CORE_VERSION;
        public final List<String> report = new ArrayList<>();

        public String asText() {
            StringBuilder sb = new StringBuilder();
            for (String line : report) {
                sb.append(line).append('\n');
            }
            return sb.toString().trim();
        }
    }

    private UpdateManager() {
    }

    static boolean canInstallRelease(
            JSONObject release, String officialStable, String currentCore, int currentVersionCode) throws Exception {
        String coreVer = release.getString("core_version");
        if (!coreVer.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) {
            throw new SecurityException("更新包不是正式内核版本");
        }
        return compareVersion(coreVer, currentCore) >= 0
                && compareVersion(coreVer, officialStable) <= 0
                && release.getInt("version_code") > currentVersionCode
                && release.getString("apk_url").startsWith("https://")
                && release.getString("sha256").matches("(?i)[a-f0-9]{64}");
    }

    public static int compareVersion(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int va = Integer.parseInt(pa[i]);
            int vb = Integer.parseInt(pb[i]);
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        return 0;
    }

    @SuppressWarnings("deprecation")
    public static void downloadAndInstall(Context context, JSONObject release, Progress progress) throws Exception {
        String apkUrl = release.getString("apk_url");
        if (!apkUrl.startsWith("https://")) {
            throw new SecurityException("安装包必须是 HTTPS");
        }
        progress.step("下载新版本 F 安装包");
        File dir = new File(context.getCacheDir(), "updates");
        dir.mkdirs();
        File apkFile = new File(dir, "F-update.apk");
        NetFetch.downloadTo(apkUrl, apkFile, 160000000L, release.getString("sha256"));

        PackageManager pm = context.getPackageManager();
        int sigFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
        PackageInfo archiveInfo = pm.getPackageArchiveInfo(
                apkFile.getAbsolutePath(), sigFlags | PackageManager.GET_META_DATA);
        PackageInfo installedInfo = pm.getPackageInfo(context.getPackageName(), sigFlags);

        if (archiveInfo == null || !context.getPackageName().equals(archiveInfo.packageName)) {
            throw new SecurityException("安装包包名不匹配");
        }
        long archiveVer = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? archiveInfo.getLongVersionCode()
                : archiveInfo.versionCode;
        if (archiveVer <= BuildConfig.VERSION_CODE || archiveVer != release.getLong("version_code")) {
            throw new SecurityException("安装包版本号不匹配或不是新版");
        }
        if (archiveInfo.applicationInfo == null
                || archiveInfo.applicationInfo.metaData == null
                || !release.getString("core_version").equals(
                        archiveInfo.applicationInfo.metaData.getString("com.fproxy.CORE_VERSION"))) {
            throw new SecurityException("安装包内核声明与更新清单不匹配");
        }

        Signature[] archiveSigs = null;
        Signature[] installedSigs = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && archiveInfo.signingInfo != null
                && installedInfo.signingInfo != null) {
            archiveSigs = archiveInfo.signingInfo.getApkContentsSigners();
            installedSigs = installedInfo.signingInfo.getApkContentsSigners();
        } else {
            PackageInfo aLegacy = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), PackageManager.GET_SIGNATURES);
            PackageInfo iLegacy = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
            if (aLegacy == null || iLegacy == null) {
                throw new SecurityException("无法读取安装包签名");
            }
            archiveSigs = aLegacy.signatures;
            installedSigs = iLegacy.signatures;
        }

        if (archiveSigs == null
                || installedSigs == null
                || archiveSigs.length != 1
                || installedSigs.length != 1
                || !MessageDigest.isEqual(archiveSigs[0].toByteArray(), installedSigs[0].toByteArray())) {
            throw new SecurityException("安装包证书与当前 F 不一致");
        }

        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".files", apkFile);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(intent);
    }

    public static String latestOfficialStable() throws Exception {
        JSONArray arr = new JSONArray().put(new JSONObject(NetFetch.text(RELEASES, 2000000)));
        String best = null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject rel = arr.getJSONObject(i);
            if (rel.optBoolean("draft", true) || rel.optBoolean("prerelease", true)) {
                continue;
            }
            String tag = rel.optString("tag_name");
            if (!tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+")) {
                continue;
            }
            String ver = tag.substring(1);
            if (best == null || compareVersion(ver, best) > 0) {
                best = ver;
            }
        }
        if (best != null) {
            return best;
        }
        throw new IOException("官方版本列表中没有稳定版");
    }

    private static String safe(Exception e) {
        return e.getMessage() == null
                ? e.getClass().getSimpleName()
                : CrashDiary.redact(e.getMessage().replace('\n', ' '));
    }

    public static Result updateAll(Context context, NodeStore store, Progress progress) {
        Result result = new Result();
        try {
            JSONArray subs = store.subscriptions();
            int ok = 0;
            int fail = 0;
            for (int i = 0; i < subs.length(); i++) {
                JSONObject sub = subs.getJSONObject(i);
                String name = sub.optString("name", "订阅");
                progress.step("更新订阅：" + name);
                try {
                    String id = sub.getString("id");
                    String body = NetFetch.text(sub.getString("url"), 2000000);
                    store.replaceSubscription(id, body);
                    ok++;
                } catch (Exception e) {
                    fail++;
                    result.report.add("订阅「" + name + "」更新失败，已保留旧节点：" + safe(e));
                }
            }
            result.report.add("订阅：成功 " + ok + "，失败 " + fail);
        } catch (Exception e) {
            result.report.add("读取订阅失败：" + safe(e));
        }

        File rulesDir = new File(context.getFilesDir(), "rules");
        rulesDir.mkdirs();
        int geoOk = 0;
        for (int i = 0; i < RULE_NAMES.length; i++) {
            String ruleName = RULE_NAMES[i];
            progress.step("更新 Geo 规则：" + ruleName);
            try {
                byte[] bytes = NetFetch.bytes(RULE_URLS[i], 10000000);
                if (bytes.length < 1024 || bytes[0] != 'S' || bytes[1] != 'R' || bytes[2] != 'S') {
                    throw new IOException("规则集过小或签名头不是 SRS");
                }
                File tmp = new File(rulesDir, ruleName + ".tmp");
                File dst = new File(rulesDir, ruleName);
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(bytes);
                    out.flush();
                    out.getFD().sync();
                }
                try {
                    Files.move(tmp.toPath(), dst.toPath(),
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception moveErr) {
                    Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                geoOk++;
            } catch (Exception e) {
                result.report.add(ruleName + " 更新失败，已保留原文件：" + safe(e));
            }
        }
        result.report.add("Geo .srs：成功 " + geoOk + "/" + RULE_NAMES.length);

        progress.step("更新中国大陆应用包名清单");
        try {
            int count = AppDirectPolicy.update(context);
            result.report.add("国内应用清单：已更新 " + count + " 条，重连后应用");
        } catch (Exception e) {
            result.report.add("国内应用清单更新失败，保留内置/已有规则：" + safe(e));
        }
        result.report.add("大陆 IP 与应用直连规则在下次连接时生效；浏览器不整应用直连。");

        progress.step("检查 sing-box 官方稳定版");
        try {
            String latest = latestOfficialStable();
            result.officialStableVersion = latest;
            result.report.add("官方稳定内核：v" + latest + "（当前 v" + BuildConfig.CORE_VERSION + "）");
            String source = updateSource(context);
            if (source.trim().isEmpty()) {
                result.report.add("请在页面底部填写同签名 F 更新清单；当前尚未配置下载来源。");
            } else {
                progress.step("检查 F 应用更新");
                try {
                    JSONObject manifest = new JSONObject(NetFetch.text(source, 80000));
                    if (canInstallRelease(manifest, result.officialStableVersion,
                            BuildConfig.CORE_VERSION, BuildConfig.VERSION_CODE)) {
                        result.appRelease = manifest;
                        result.report.add("F 稳定版更新包已就绪，需要用户确认安装");
                    } else {
                        result.report.add("没有可安装且匹配官方稳定版的 F 更新包");
                    }
                } catch (Exception e) {
                    result.report.add("检查 F 应用更新失败：" + safe(e));
                }
            }
        } catch (Exception e) {
            result.report.add("检查内核版本失败：" + safe(e));
        }
        return result;
    }

    public static String updateSource(Context context) {
        return context.getSharedPreferences("updates", Context.MODE_PRIVATE)
                .getString("manifest", BuildConfig.APP_UPDATE_MANIFEST);
    }
}
