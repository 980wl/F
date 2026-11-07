package com.fproxy.client;
import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
/** Local test fixture apps observe their own active network under the real VPN UID exclusions. */
public final class RoutingInstrumentation {
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static void await(Context c,String phase)throws Exception{
        for(int i=0;i<600;i++){JSONObject s=RunState.vpn(c);if(phase.equals(s.optString("phase")))return;
            if("failed".equals(s.optString("phase")))throw new AssertionError(s.toString()+"\n"+CrashDiary.read(c));Thread.sleep(100);}
        throw new AssertionError("Timed out: "+RunState.vpn(c)+"\n"+CrashDiary.read(c));
    }
    public static void run(Instrumentation runner){Context c=runner.getTargetContext();Bundle report=new Bundle();BroadcastReceiver receiver=null;
        String[] pkgs={"com.tencent.mm","com.taobao.taobao","org.fixture.helper","org.fixture.finance","com.tencent.mtt","org.fixture.foreign"};
        try{
            AppDirectPolicy.Snapshot policy=AppDirectPolicy.scan(c);
            require(policy.conflict.isEmpty(),policy.conflict);
            for(int i=0;i<4;i++)require(policy.excluded.contains(pkgs[i]),"Not bypassed: "+pkgs[i]);
            for(int i=4;i<6;i++)require(!policy.excluded.contains(pkgs[i]),"Should stay in VPN: "+pkgs[i]);
            AppDirectPolicy.setManual(c,"com.tencent.mtt",true);
            require(!AppDirectPolicy.scan(c).excluded.contains("com.tencent.mtt"),"Browser exception lost to manual bypass");
            AppDirectPolicy.setManual(c,"com.tencent.mtt",false);
            NodeStore store=new NodeStore(c);store.deleteAllNodes();String uri="hy2://test-pass@127.0.0.1:19999?insecure=1#Route-Test";
            store.importManual(uri);store.select("manual:"+NodeParser.fingerprint(NodeParser.fromUri(uri)));
            require(android.net.VpnService.prepare(c)==null,"VPN authorization missing");
            c.startForegroundService(new Intent(c,FVpnService.class));await(c,"connected");
            JSONObject applied=RunState.read(c,"app-direct-active");Set<String> real=new HashSet<>();JSONArray list=applied.getJSONArray("packages");for(int i=0;i<list.length();i++)real.add(list.getString(i));
            for(int i=0;i<4;i++)require(real.contains(pkgs[i]),"Not applied to VPN builder: "+pkgs[i]);
            require(!real.contains(pkgs[4]),"Mainland browser was excluded");
            Map<String,JSONObject> networks=new ConcurrentHashMap<>();CountDownLatch latch=new CountDownLatch(pkgs.length);String token=UUID.randomUUID().toString();
            receiver=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){
                if(!token.equals(intent.getStringExtra("token")))return;
                try{JSONObject value=new JSONObject(intent.getStringExtra("result"));if(networks.putIfAbsent(value.getString("package"),value)==null)latch.countDown();}catch(Exception ignored){}
            }};
            IntentFilter filter=new IntentFilter("com.fproxy.client.ROUTE_TEST_RESULT");
            if(Build.VERSION.SDK_INT>=33)c.registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED);else c.registerReceiver(receiver,filter);
            for(String pkg:pkgs)c.sendBroadcast(new Intent("fixture.NETWORK").setComponent(new ComponentName(pkg,"fixture.NetworkReceiver")).putExtra("token",token));
            require(latch.await(45,TimeUnit.SECONDS),"Fixture network responses missing: "+networks);
            for(int i=0;i<pkgs.length;i++){
                JSONObject net=networks.get(pkgs[i]);require(net!=null,"No result: "+pkgs[i]);
                require(i<4?net.getBoolean("not_vpn")&&!net.getBoolean("vpn"):net.getBoolean("vpn")&&!net.getBoolean("not_vpn"),"Wrong app routing: "+net);
            }
            c.startService(new Intent(c,FVpnService.class).setAction(FVpnService.ACTION_STOP));await(c,"idle");
            AppDirectPolicy.setManual(c,"org.fixture.foreign",true);require(AppDirectPolicy.scan(c).excluded.contains("org.fixture.foreign"),"Manual supplement failed");AppDirectPolicy.setManual(c,"org.fixture.foreign",false);
            report.putString("result","PASS: real VPN startup with bundled CN IPv4/IPv6 rule set; WeChat/mainland app/SystemHelper/bank fixture UIDs use physical network; mainland browser and foreign app UIDs stay in VPN; browser exception beats manual bypass; manual supplement works. Networks="+networks);runner.finish(0,report);
        }catch(Throwable t){report.putString("result","FAIL: "+android.util.Log.getStackTraceString(t)+"\n"+CrashDiary.read(c));runner.finish(1,report);}
        finally{if(receiver!=null)c.unregisterReceiver(receiver);c.startService(new Intent(c,FVpnService.class).setAction(FVpnService.ACTION_STOP));}
    }
}
