package com.fproxy.client;
import android.content.Context;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.SetupOptions;
import java.io.File;
/** One native instance per private process; separate paths prevent command-socket collisions. */
public final class CoreRuntime {
    private static boolean configured;
    public static synchronized void setup(Context context)throws Exception{setup(context,"core");}
    public static synchronized void setup(Context context,String process)throws Exception{
        if(configured)return;
        go.Seq.setContext(context.getApplicationContext());
        File dir=new File(context.getFilesDir(),process);if(!dir.isDirectory()&&!dir.mkdirs())throw new java.io.IOException("无法创建内核工作目录");
        SetupOptions o=new SetupOptions();o.setBasePath(dir.getAbsolutePath());o.setWorkingPath(dir.getAbsolutePath());
        o.setTempPath(context.getCacheDir().getAbsolutePath());o.setLogMaxLines(300);o.setFixAndroidStack(true);
        o.setCrashReportSource(process);o.setAppVersion(Integer.toString(BuildConfig.VERSION_CODE));o.setAppMarketingVersion(BuildConfig.VERSION_NAME);
        o.setCommandServerSecret(java.util.UUID.randomUUID().toString());Libbox.setup(o);
        if(!BuildConfig.CORE_VERSION.equals(Libbox.version()))throw new IllegalStateException("内核版本不匹配："+Libbox.version());
        configured=true;
    }
}
