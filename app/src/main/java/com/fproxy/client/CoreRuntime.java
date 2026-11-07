package com.fproxy.client;

import android.content.Context;
import go.Seq;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.SetupOptions;
import java.io.File;
import java.io.IOException;
import java.util.UUID;

public class CoreRuntime {
    private static boolean configured;

    public static synchronized void setup(Context context) throws Exception {
        setup(context, "core");
    }

    public static synchronized void setup(Context context, String workDirName) throws Exception {
        if (configured) {
            return;
        }
        Seq.setContext(context.getApplicationContext());
        File workDir = new File(context.getFilesDir(), workDirName);
        if (!workDir.isDirectory() && !workDir.mkdirs()) {
            throw new IOException("无法创建内核工作目录");
        }
        SetupOptions options = new SetupOptions();
        options.setBasePath(workDir.getAbsolutePath());
        options.setWorkingPath(workDir.getAbsolutePath());
        options.setTempPath(context.getCacheDir().getAbsolutePath());
        options.setLogMaxLines(300L);
        options.setFixAndroidStack(true);
        options.setCrashReportSource(workDirName);
        options.setAppVersion(Integer.toString(BuildConfig.VERSION_CODE));
        options.setAppMarketingVersion(BuildConfig.VERSION_NAME);
        options.setCommandServerSecret(UUID.randomUUID().toString());
        Libbox.setup(options);
        if (!BuildConfig.CORE_VERSION.equals(Libbox.version())) {
            throw new IllegalStateException("内核版本不匹配：" + Libbox.version());
        }
        configured = true;
    }
}
