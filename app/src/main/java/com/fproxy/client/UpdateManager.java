package com.fproxy.client;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.content.pm.Signature;
import androidx.core.content.FileProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One refresh button runs subscriptions, compatible SRS rules and a stable-only core check.
 * libbox is packaged as native code; This application updates its bundled core through a NEW APK signed with the
 * SAME signing certificate; it does not implement native library hot swapping.
 */
public final class UpdateManager {
    public static final String RELEASES="https://api.github.com/repos/SagerNet/sing-box/releases/latest";
    public static String updateSource(Context c){return c.getSharedPreferences("updates",Context.MODE_PRIVATE).getString("manifest",BuildConfig.APP_UPDATE_MANIFEST);}
    public static final String[] RULE_NAMES={"geoip-cn.srs","geosite-cn.srs"};
    public static final String[] RULE_URLS={
        "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-cn.srs",
        "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-cn.srs"
    };
    private UpdateManager() {}
    public interface Progress {void step(String info);}
    public static final class Result {
        public final List<String> report=new ArrayList<>();
        public String officialStableVersion=BuildConfig.CORE_VERSION;
        public JSONObject appRelease;
        public String asText(){StringBuilder sb=new StringBuilder();for(String s:report)sb.append(s).append('\n');return sb.toString().trim();}
    }
    public static Result updateAll(Context context,NodeStore store,Progress p) {
        Result r=new Result();
        try {
            JSONArray subscriptions=store.subscriptions();
            int ok=0,failed=0;
            for(int i=0;i<subscriptions.length();i++){
                JSONObject s=subscriptions.getJSONObject(i);
                String name=s.optString("name","订阅");p.step("更新订阅："+name);
                try {
                    store.replaceSubscription(s.getString("id"),NetFetch.text(s.getString("url"),2_000_000));
                    ok++;
                } catch(Exception e){failed++;r.report.add("订阅「"+name+"」更新失败，已保留旧节点："+safe(e));}
            }
            r.report.add("订阅：成功 "+ok+"，失败 "+failed);
        } catch(Exception e){r.report.add("读取订阅失败："+safe(e));}
        File ruleDir=new File(context.getFilesDir(),"rules");ruleDir.mkdirs();
        int geoOk=0;
        for(int i=0;i<RULE_NAMES.length;i++){
            String name=RULE_NAMES[i];p.step("更新 Geo 规则："+name);
            try {
                byte[] body=NetFetch.bytes(RULE_URLS[i],10_000_000);
                if(body.length<8 || body[0]!='S'||body[1]!='R'||body[2]!='S')
                    throw new java.io.IOException("规则集签名头不是 SRS");
                File tmp=new File(ruleDir,name+".tmp");File target=new File(ruleDir,name);
                try(FileOutputStream out=new FileOutputStream(tmp)) {out.write(body);out.flush();out.getFD().sync();}
                try {Files.move(tmp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
                catch(java.nio.file.AtomicMoveNotSupportedException e){Files.move(tmp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);}
                geoOk++;
            }catch(Exception e){r.report.add(name+" 更新失败，已保留原文件："+safe(e));}
        }
        r.report.add("Geo .srs：成功 "+geoOk+"/"+RULE_NAMES.length);
        p.step("更新中国大陆应用包名清单");
        try{int count=AppDirectPolicy.update(context);r.report.add("国内应用清单：已更新 "+count+" 条，重连后应用");}
        catch(Exception e){r.report.add("国内应用清单更新失败，保留内置/已有规则："+safe(e));}
        r.report.add("大陆 IP 与应用直连规则在下次连接时生效；浏览器不整应用直连。");
        p.step("检查 sing-box 官方稳定版");
        try {
            String latest=latestOfficialStable();r.officialStableVersion=latest;
            if(compareVersion(latest,BuildConfig.CORE_VERSION)>0) {
                r.report.add("官方稳定内核：v"+latest+"（当前 v"+BuildConfig.CORE_VERSION+"）");
                if(updateSource(context).isBlank()) {
                    r.report.add("请在页面底部填写同签名 F 更新清单；当前尚未配置下载来源。");
                } else {
                    JSONObject release=new JSONObject(NetFetch.text(updateSource(context),80_000));
                    String releaseCore=release.getString("core_version");
                    if(!releaseCore.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))throw new SecurityException("更新包不是正式内核版本");
                    if(compareVersion(releaseCore,BuildConfig.CORE_VERSION)>0
                            &&compareVersion(releaseCore,latest)<=0
                            &&release.getInt("version_code")>BuildConfig.VERSION_CODE
                            &&release.getString("apk_url").startsWith("https://")
                            &&release.getString("sha256").matches("(?i)[a-f0-9]{64}")) {
                        r.appRelease=release;r.report.add("F 稳定版更新包已就绪，需要用户确认安装");
                    } else r.report.add("没有可安装且匹配官方稳定版的 F 更新包");
                }
            } else r.report.add("sing-box v"+BuildConfig.CORE_VERSION+"：已是当前可用的官方正式版");
        }catch(Exception e){r.report.add("检查内核版本失败："+safe(e));}
        return r;
    }
    public static String latestOfficialStable() throws Exception {
        JSONArray releases=new JSONArray().put(new JSONObject(NetFetch.text(RELEASES,2_000_000)));
        String latest=null;
        for(int i=0;i<releases.length();i++){
            JSONObject release=releases.getJSONObject(i);
            if(release.optBoolean("draft",true)||release.optBoolean("prerelease",true))continue;
            String tag=release.optString("tag_name");
            if(!tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+"))continue; // no alpha/beta/rc/nightly
            String version=tag.substring(1);
            if(latest==null||compareVersion(version,latest)>0)latest=version;
        }
        if(latest==null)throw new java.io.IOException("官方版本列表中没有稳定版");
        return latest;
    }
    public static int compareVersion(String a,String b){
        String[] x=a.split("\\."),y=b.split("\\.");
        for(int i=0;i<3;i++){int v=Integer.parseInt(x[i]),w=Integer.parseInt(y[i]);if(v!=w)return Integer.compare(v,w);}
        return 0;
    }
    /** Verifies downloaded APK hash and SAME APK signer before invoking Android's installer. */
    public static void downloadAndInstall(Context c,JSONObject release,Progress p)throws Exception {
        String address=release.getString("apk_url");
        if(!address.startsWith("https://"))throw new SecurityException("安装包必须是 HTTPS");
        p.step("下载新版本 F 安装包");
        File dir=new File(c.getCacheDir(),"updates");dir.mkdirs();File target=new File(dir,"F-update.apk");
        NetFetch.downloadTo(address,target,160_000_000,release.getString("sha256"));
        // Verify the incoming APK has our package name and EXACTLY the currently installed signer.
        PackageManager pm=c.getPackageManager();
        int mask=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo incoming=pm.getPackageArchiveInfo(target.getAbsolutePath(),mask|PackageManager.GET_META_DATA);
        PackageInfo current=pm.getPackageInfo(c.getPackageName(),mask);
        if(incoming==null||!c.getPackageName().equals(incoming.packageName))throw new SecurityException("安装包包名不匹配");
        long incomingVersion=Build.VERSION.SDK_INT>=28?incoming.getLongVersionCode():incoming.versionCode;
        if(incomingVersion<=BuildConfig.VERSION_CODE||incomingVersion!=release.getLong("version_code"))
            throw new SecurityException("安装包版本号不匹配或不是新版");
        if(incoming.applicationInfo==null||incoming.applicationInfo.metaData==null||
                !release.getString("core_version").equals(incoming.applicationInfo.metaData.getString("com.fproxy.CORE_VERSION")))
            throw new SecurityException("安装包内核声明与更新清单不匹配");
        Signature[] a,b;
        if(Build.VERSION.SDK_INT>=28){
            if(incoming.signingInfo==null||current.signingInfo==null)throw new SecurityException("无法读取安装包签名");
            a=incoming.signingInfo.getApkContentsSigners();b=current.signingInfo.getApkContentsSigners();
        }else{a=incoming.signatures;b=current.signatures;}
        if(a==null||b==null||a.length!=1||b.length!=1
                ||!MessageDigest.isEqual(a[0].toByteArray(),b[0].toByteArray()))
            throw new SecurityException("安装包证书与当前 F 不一致");
        Uri uri=FileProvider.getUriForFile(c,c.getPackageName()+".files",target);
        Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(intent);
    }
    private static String hash(byte[] b)throws Exception{
        byte[] d=MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder s=new StringBuilder();for(byte v:d)s.append(String.format(Locale.US,"%02x",v&0xff));return s.toString();
    }
    private static String safe(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage().replace('\n',' ');}
}
