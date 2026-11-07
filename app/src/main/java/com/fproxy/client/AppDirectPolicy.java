package com.fproxy.client;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Uses Android's per-application exclusion, so every destination of an excluded UID stays outside the VPN. */
public final class AppDirectPolicy {
    public static final String APPS_URL="https://raw.githubusercontent.com/mnixry/direct-android-ruleset/refs/heads/rules/@Merged/APP.mutated.yaml";
    public static final String GAMES_URL="https://raw.githubusercontent.com/mnixry/direct-android-ruleset/refs/heads/rules/@Merged/GAME.mutated.yaml";
    public static final class Entry {
        public String pkg,label,reason;public int uid;public boolean browser,required,automatic,direct;
    }
    public static final class Snapshot {
        public final List<Entry> apps=new ArrayList<>();public final Set<String> excluded=new TreeSet<>();
        public String conflict="";public int listCount,browsers;
    }
    private static String text(InputStream in)throws IOException{
        try(InputStream input=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1){if(out.size()+n>4_000_000)throw new IOException("应用清单过大");out.write(b,0,n);}return out.toString("UTF-8");}
    }
    public static Set<String> packages(Context c)throws IOException{
        Set<String> all=new TreeSet<>();
        for(String part:new String[]{"apps","games"}){
            Set<String> defaults=CnAppMatcher.parsePackageRules(text(c.getAssets().open("cn-"+part+".txt")));Set<String> chosen=defaults;
            File cache=new File(c.getFilesDir(),"rules/cn-"+part+".txt");
            if(cache.exists())try{Set<String> newer=CnAppMatcher.parsePackageRules(text(new FileInputStream(cache)));if(newer.size()>=100)chosen=newer;}catch(IOException ignored){}
            all.addAll(chosen);
        }
        return all;
    }
    public static Set<String> manual(Context c){Set<String> result=new TreeSet<>();JSONArray array=RunState.read(c,"app-direct-settings").optJSONArray("manual");
        if(array!=null)for(int i=0;i<array.length();i++)result.add(array.optString(i));return result;}
    public static void setManual(Context c,String pkg,boolean direct)throws Exception{
        Set<String> set=manual(c);if(direct)set.add(pkg);else set.remove(pkg);
        RunState.write(c,"app-direct-settings",new JSONObject().put("manual",new JSONArray(set)));
    }
    @android.annotation.SuppressLint("QueryPermissionsNeeded")
    public static Snapshot scan(Context c)throws Exception{
        PackageManager pm=c.getPackageManager();Set<String> list=packages(c),manual=manual(c),browserPackages=new HashSet<>();
        for(String url:new String[]{"http://www.example.com/","https://www.example.com/"}){
            Intent view=new Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE);
            for(ResolveInfo result:pm.queryIntentActivities(view,PackageManager.MATCH_ALL))if(result.activityInfo!=null)browserPackages.add(result.activityInfo.packageName);
        }
        Snapshot out=new Snapshot();out.listCount=list.size();
        for(ApplicationInfo app:pm.getInstalledApplications(0)){
            if(app.packageName.equals(c.getPackageName())||!app.enabled)continue;
            Entry e=new Entry();e.pkg=app.packageName;e.uid=app.uid;e.label=String.valueOf(pm.getApplicationLabel(app));
            String required=CnAppMatcher.requiredReason(e.pkg,e.label);boolean explicitBrowser=CnAppMatcher.isBrowser(e.pkg,e.label,false);
            e.required=!required.isEmpty()&&!explicitBrowser;
            e.browser=!e.required&&CnAppMatcher.isBrowser(e.pkg,e.label,browserPackages.contains(e.pkg));if(e.browser)out.browsers++;
            e.automatic=CnAppMatcher.autoDirect(e.pkg,e.label,e.browser,list);e.direct=e.automatic||(!e.browser&&manual.contains(e.pkg));
            e.reason=e.required?required:e.browser?"浏览器：按大陆 IP 分流":e.automatic?"国内应用清单：整应用直连":e.direct?"手动补充：整应用直连":"按大陆 IP 分流";
            out.apps.add(e);
        }
        // Android exclusions operate on UIDs; do not silently exclude a browser that shares a UID.
        Set<Integer> browserUids=new HashSet<>();for(Entry e:out.apps)if(e.browser)browserUids.add(e.uid);
        for(Entry e:out.apps){
            if(e.direct&&browserUids.contains(e.uid)){
                if(e.required)out.conflict=e.label+" 与浏览器共享 Android UID，无法同时保证应用直连和浏览器分流。";
                else{e.direct=false;e.automatic=false;e.reason="与浏览器共享 UID：保留 IP 分流";}
            }
            if(e.direct)out.excluded.add(e.pkg);
        }
        out.apps.sort(Comparator.comparingInt((Entry e)->e.required?0:e.direct?1:e.browser?3:2).thenComparing(e->e.label));
        return out;
    }
    public static int update(Context c)throws Exception{
        int count=0;File dir=new File(c.getFilesDir(),"rules");dir.mkdirs();
        // Validate both responses before replacing either file; a failed fetch retains the old snapshot.
        Set<String> apps=CnAppMatcher.parsePackageRules(NetFetch.text(APPS_URL,3_000_000));
        Set<String> games=CnAppMatcher.parsePackageRules(NetFetch.text(GAMES_URL,3_000_000));
        if(apps.size()<100||games.size()<100)throw new IOException("国内应用清单异常，保留旧规则");
        for(Set<String> set:Arrays.asList(apps,games)){
            String part=count==0?"apps":"games";File target=new File(dir,"cn-"+part+".txt"),tmp=new File(dir,"cn-"+part+".tmp");
            try(FileOutputStream out=new FileOutputStream(tmp)){out.write((String.join("\n",set)+"\n").getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
            java.nio.file.Files.move(tmp.toPath(),target.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);count+=set.size();
        }
        return count;
    }
}
