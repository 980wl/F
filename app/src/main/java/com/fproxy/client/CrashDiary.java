package com.fproxy.client;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
public final class CrashDiary {
    public static void install(Context c,String process){
        Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
            record(c,process,error);
            if(previous!=null)previous.uncaughtException(thread,error);
            else android.os.Process.killProcess(android.os.Process.myPid());
        });
    }
    public static void record(Context c,String stage,Throwable error){
        try(PrintWriter out=new PrintWriter(new FileOutputStream(new File(c.getFilesDir(),"last-error.log")))){
            out.println(new java.util.Date()+"  "+stage);error.printStackTrace(out);
        }catch(Exception ignored){}
    }
    public static String read(Context c){
        StringBuilder text=new StringBuilder();
        for(String name:new String[]{"last-error.log","CrashReport-.log","core/CrashReport-core.log","probe/CrashReport-probe.log"}){
            File f=new File(c.getFilesDir(),name);if(!f.exists()||f.length()==0)continue;
            try(RandomAccessFile in=new RandomAccessFile(f,"r")){
                in.seek(Math.max(0,in.length()-12000));byte[] bytes=new byte[(int)(in.length()-in.getFilePointer())];in.readFully(bytes);
                text.append(name).append('\n').append(new String(bytes,StandardCharsets.UTF_8)).append('\n');
            }catch(Exception ignored){}
        }
        return text.length()==0?"暂无错误记录。":text.toString();
    }
}
