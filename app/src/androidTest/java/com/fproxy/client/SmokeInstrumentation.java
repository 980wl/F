package com.fproxy.client;
import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.json.*;
/** Exercises the same isolated service path as tapping Connect; requires an emulator VPN app-op. */
public class SmokeInstrumentation extends Instrumentation {
    private boolean probeOnly,routingOnly;
    @Override public void onCreate(Bundle b){super.onCreate(b);probeOnly=b!=null&&"true".equals(b.getString("probe"));routingOnly=b!=null&&"true".equals(b.getString("routing"));start();}
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private JSONObject await(String phase)throws Exception{
        Context c=getTargetContext();
        for(int i=0;i<300;i++){
            JSONObject state=RunState.vpn(c);String p=state.optString("phase");
            if(p.equals(phase))return state;
            if(p.equals("failed"))throw new AssertionError(state.optString("error")+"\n"+CrashDiary.read(c));
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for "+phase+": "+RunState.vpn(c)+"\n"+CrashDiary.read(c));
    }
    private boolean contains(View view,Class<?> type){
        if(type.isInstance(view))return true;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(contains(group.getChildAt(i),type))return true;}
        return false;
    }
    @Override public void onStart(){if(routingOnly){RoutingInstrumentation.run(this);return;}if(probeOnly){ProbeInstrumentation.run(this);return;}Bundle report=new Bundle();Context c=getTargetContext();int protocols=0;
        try{
            NodeStore store=new NodeStore(c);store.deleteAllNodes();
            for(String uri:new String[]{"hy2://test-pass@127.0.0.1:19443?insecure=1#HY2-Test","tuic://00000000-0000-0000-0000-000000000001:test-pass@127.0.0.1:19444?insecure=1&alpn=h3#TUIC-Test"}){
                store.importManual(uri);store.select("manual:"+NodeParser.fingerprint(NodeParser.fromUri(uri)));
                check(android.net.VpnService.prepare(c)==null,"VPN permission missing");
                RunState.vpn(c,"idle","","");
                c.startForegroundService(new Intent(c,FVpnService.class));JSONObject connected=await("connected");
                check(connected.optInt("pid")!=android.os.Process.myPid(),"VPN must run outside the UI process");
                c.startService(new Intent(c,FVpnService.class).setAction(FVpnService.ACTION_STOP));await("idle");protocols++;
            }
            Activity ui=startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(()->{check(!contains(ui.getWindow().getDecorView(),Spinner.class),"Dropdown still present");check(contains(ui.getWindow().getDecorView(),ListView.class),"Flat node list missing");});
            runOnMainSync(ui::finish);
            int subCount=store.subscriptions().length();store.deleteAllNodes();check(store.nodes().length()==0,"Delete all failed");check(store.subscriptions().length()==subCount,"Clear nodes changed subscriptions");
            report.putString("result","PASS: HY2/TUIC native startup + TUN + stop ("+protocols+" protocols), isolated core process, flat list without Spinner, delete all. Remote authentication/traffic not tested.");finish(0,report);
        }catch(Throwable t){report.putString("result","FAIL after "+protocols+" protocols: "+android.util.Log.getStackTraceString(t));finish(1,report);}
        finally{c.startService(new Intent(c,FVpnService.class).setAction(FVpnService.ACTION_STOP));}
    }
}
