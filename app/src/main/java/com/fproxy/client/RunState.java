package com.fproxy.client;
import android.content.Context;
import android.app.ActivityManager;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
/** Atomic snapshots work across the UI, VPN and probe processes without stale preferences. */
public final class RunState {
    public static JSONObject read(Context c,String name){
        try{return new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(c.getFilesDir(),name+".json").toPath()),StandardCharsets.UTF_8));}
        catch(Exception ignored){return new JSONObject();}
    }
    public static synchronized void write(Context c,String name,JSONObject value){
        File target=new File(c.getFilesDir(),name+".json");
        File temp=new File(c.getFilesDir(),name+"-"+android.os.Process.myPid()+".tmp");
        try{
            value.put("updated",System.currentTimeMillis());
            try(FileOutputStream out=new FileOutputStream(temp)){out.write(value.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
            java.nio.file.Files.move(temp.toPath(),target.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception e){temp.delete();android.util.Log.e("F-State","Write failed",e);}
    }
    public static void vpn(Context c,String phase,String error,String node){
        try{write(c,"vpn-state",new JSONObject().put("phase",phase).put("error",error==null?"":error)
            .put("node",node==null?"":node).put("pid",android.os.Process.myPid()));}catch(Exception ignored){}
    }
    public static boolean alive(Context c,int pid){
        if(pid<=0)return false;
        ActivityManager am=(ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);
        java.util.List<ActivityManager.RunningAppProcessInfo> list=am.getRunningAppProcesses();
        if(list==null)return true; // Don't report a crash just because a vendor hides process metadata.
        for(ActivityManager.RunningAppProcessInfo p:list)if(p.pid==pid&&p.uid==android.os.Process.myUid())return true;
        return false;
    }
    public static JSONObject vpn(Context c){
        JSONObject s=read(c,"vpn-state");String p=s.optString("phase","idle");
        if((p.equals("starting")||p.equals("connected")||p.equals("stopping"))
            &&System.currentTimeMillis()-s.optLong("updated")>3000&&!alive(c,s.optInt("pid"))){
            try{s.put("phase","failed").put("error","内核进程意外退出，请查看页面下方的错误日志。");}catch(Exception ignored){}
        }
        return s;
    }
    public static boolean active(JSONObject s){String p=s.optString("phase");return p.equals("starting")||p.equals("connected")||p.equals("stopping");}
    public static String label(JSONObject s){switch(s.optString("phase")){
        case "starting":return "正在连接";case "connected":return "已连接 · 大陆直连";
        case "stopping":return "正在断开";case "failed":return "连接失败";default:return "未连接";}}
}
