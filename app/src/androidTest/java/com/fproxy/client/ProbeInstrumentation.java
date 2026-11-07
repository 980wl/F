package com.fproxy.client;
import android.app.*;
import android.content.*;
import android.os.Bundle;
import org.json.*;
public final class ProbeInstrumentation {
    public static void run(Instrumentation runner){Bundle report=new Bundle();Context c=runner.getTargetContext();
        try{
            NodeStore store=new NodeStore(c);store.deleteAllNodes();RunState.write(c,"probe-state",new JSONObject());
            String[] links={"hy2://test-pass@127.0.0.1:19443?insecure=1#HY2-OK","tuic://00000000-0000-0000-0000-000000000001:test-pass@127.0.0.1:19444?insecure=1&alpn=h3#TUIC-OK",
                "hy2://wrong@127.0.0.1:19443?insecure=1#HY2-BAD","tuic://00000000-0000-0000-0000-000000000001:wrong@127.0.0.1:19444?insecure=1&alpn=h3#TUIC-BAD"};
            for(String link:links)store.importManual(link);
            String selected=store.selected();c.startForegroundService(new Intent(c,ProbeService.class).putExtra("test_url","https://10.0.2.2:18443/"));
            JSONObject state=new JSONObject();
            for(int i=0;i<1200;i++){
                state=RunState.read(c,"probe-state");JSONObject results=state.optJSONObject("results");
                if(!state.optBoolean("running")&&results!=null&&results.length()==4)break;
                Thread.sleep(100);
            }
            JSONObject results=state.getJSONObject("results");
            for(int i=0;i<4;i++){
                JSONObject result=results.getJSONObject("manual:"+NodeParser.fingerprint(NodeParser.fromUri(links[i])));
                String expected=i<2?"ok":"failed";if(!expected.equals(result.optString("status")))throw new AssertionError("Node "+i+" expected "+expected+" got "+result);
            }
            if(!selected.equals(new NodeStore(c).selected()))throw new AssertionError("Testing changed selected node");
            Activity ui=runner.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));runner.waitForIdleSync();
            android.graphics.Bitmap screenshot=runner.getUiAutomation().takeScreenshot();if(screenshot!=null){
                try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(c.getFilesDir(),"ui-qa.png"))){screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}}
            runner.runOnMainSync(ui::finish);
            report.putString("result","PASS: real HY2+TUIC authenticated HTTPS 204 requests; wrong credentials rejected for both; selected node preserved. Results="+results);runner.finish(0,report);
        }catch(Throwable t){report.putString("result","FAIL: "+android.util.Log.getStackTraceString(t)+"\n"+CrashDiary.read(c));runner.finish(1,report);}
        finally{c.stopService(new Intent(c,ProbeService.class));}
    }
}
