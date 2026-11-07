package com.fproxy.client;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;

public class AppDirectPolicy {
    public static final String APPS_URL =
            "https://raw.githubusercontent.com/mnixry/direct-android-ruleset/refs/heads/rules/@Merged/APP.mutated.yaml";
    public static final String GAMES_URL =
            "https://raw.githubusercontent.com/mnixry/direct-android-ruleset/refs/heads/rules/@Merged/GAME.mutated.yaml";

    public static class Entry {
        public boolean automatic;
        public boolean browser;
        public boolean direct;
        public String label;
        public String pkg;
        public String reason;
        public boolean required;
        public int uid;
    }

    public static class Snapshot {
        public final List<Entry> apps = new ArrayList<>();
        public int browsers;
        public String conflict = "";
        public final Set<String> excluded = new TreeSet<>();
        public int listCount;
    }

    public static Set<String> manual(Context context) {
        Set<String> set = new TreeSet<>();
        JSONObject obj = RunState.read(context, "app-direct-settings");
        JSONArray arr = obj.optJSONArray("manual");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                set.add(arr.optString(i));
            }
        }
        return set;
    }

    public static Set<String> packages(Context context) throws Exception {
        Set<String> all = new TreeSet<>();
        for (String kind : new String[]{"apps", "games"}) {
            Set<String> parsed = CnAppMatcher.parsePackageRules(
                    text(context.getAssets().open("cn-" + kind + ".txt")));
            File updated = new File(context.getFilesDir(), "rules/cn-" + kind + ".txt");
            if (updated.exists()) {
                Set<String> updatedSet = CnAppMatcher.parsePackageRules(text(new FileInputStream(updated)));
                if (updatedSet.size() >= 100) {
                    parsed = updatedSet;
                }
            }
            all.addAll(parsed);
        }
        return all;
    }

    public static Snapshot scan(Context context) throws Exception {
        PackageManager pm = context.getPackageManager();
        Set<String> cnPackages = packages(context);
        Set<String> manualSet = manual(context);
        Set<String> browserPkgs = new HashSet<>();

        for (String url : new String[]{"http://www.example.com/", "https://www.example.com/"}) {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addCategory(Intent.CATEGORY_BROWSABLE);
            for (ResolveInfo ri : pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)) {
                if (ri.activityInfo != null) {
                    browserPkgs.add(ri.activityInfo.packageName);
                }
            }
        }

        Snapshot snapshot = new Snapshot();
        snapshot.listCount = cnPackages.size();

        for (ApplicationInfo app : pm.getInstalledApplications(0)) {
            if (app.packageName.equals(context.getPackageName()) || !app.enabled) {
                continue;
            }
            Entry entry = new Entry();
            entry.pkg = app.packageName;
            entry.uid = app.uid;
            entry.label = String.valueOf(pm.getApplicationLabel(app));

            String reqReason = CnAppMatcher.requiredReason(entry.pkg, entry.label);
            boolean isKnownBrowser = CnAppMatcher.isBrowser(entry.pkg, entry.label, false);
            entry.required = !reqReason.isEmpty() && !isKnownBrowser;
            entry.browser = !entry.required
                    && CnAppMatcher.isBrowser(entry.pkg, entry.label, browserPkgs.contains(entry.pkg));
            if (entry.browser) {
                snapshot.browsers++;
            }
            entry.automatic = CnAppMatcher.autoDirect(entry.pkg, entry.label, entry.browser, cnPackages);
            entry.direct = entry.automatic || (!entry.browser && manualSet.contains(entry.pkg));

            if (entry.required) {
                entry.reason = reqReason;
            } else if (entry.browser) {
                entry.reason = "浏览器：按大陆 IP 分流";
            } else if (entry.automatic) {
                entry.reason = "国内应用清单：整应用直连";
            } else if (entry.direct) {
                entry.reason = "手动补充：整应用直连";
            } else {
                entry.reason = "按大陆 IP 分流";
            }
            snapshot.apps.add(entry);
        }

        Set<Integer> browserUids = new HashSet<>();
        for (Entry entry : snapshot.apps) {
            if (entry.browser) {
                browserUids.add(entry.uid);
            }
        }
        for (Entry entry : snapshot.apps) {
            if (entry.direct && browserUids.contains(entry.uid)) {
                if (entry.required) {
                    snapshot.conflict = entry.label + " 与浏览器共享 Android UID，无法同时保证应用直连和浏览器分流。";
                } else {
                    entry.direct = false;
                    entry.automatic = false;
                    entry.reason = "与浏览器共享 UID：保留 IP 分流";
                }
            }
            if (entry.direct) {
                snapshot.excluded.add(entry.pkg);
            }
        }

        snapshot.apps.sort(Comparator.comparingInt((Entry e) -> {
            if (e.required) return 0;
            if (e.direct) return 1;
            if (e.browser) return 3;
            return 2;
        }).thenComparing(e -> e.label));

        return snapshot;
    }

    public static void setManual(Context context, String pkg, boolean enabled) throws Exception {
        Set<String> set = manual(context);
        if (enabled) {
            set.add(pkg);
        } else {
            set.remove(pkg);
        }
        JSONObject obj = new JSONObject().put("manual", new JSONArray(set));
        RunState.write(context, "app-direct-settings", obj);
    }

    private static String text(InputStream in) throws Exception {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = input.read(buf)) != -1) {
                if (out.size() + read > 4000000) {
                    throw new IOException("应用清单过大");
                }
                out.write(buf, 0, read);
            }
            return out.toString("UTF-8");
        }
    }

    public static int update(Context context) throws Exception {
        File rulesDir = new File(context.getFilesDir(), "rules");
        rulesDir.mkdirs();
        Set<String> apps = CnAppMatcher.parsePackageRules(NetFetch.text(APPS_URL, 3000000));
        Set<String> games = CnAppMatcher.parsePackageRules(NetFetch.text(GAMES_URL, 3000000));
        if (apps.size() < 100 || games.size() < 100) {
            throw new IOException("国内应用清单异常，保留旧规则");
        }
        int total = 0;
        List<Set<String>> lists = Arrays.asList(apps, games);
        for (int i = 0; i < lists.size(); i++) {
            Set<String> set = lists.get(i);
            String kind = (total == 0) ? "apps" : "games";
            File target = new File(rulesDir, "cn-" + kind + ".txt");
            File tmp = new File(rulesDir, "cn-" + kind + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] bytes = (String.join("\n", set) + "\n").getBytes(StandardCharsets.UTF_8);
                out.write(bytes);
                out.getFD().sync();
            }
            Files.move(tmp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            total += set.size();
        }
        return total;
    }
}
