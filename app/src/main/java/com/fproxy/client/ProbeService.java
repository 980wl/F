package com.fproxy.client;
import android.app.Service;
import android.content.Intent;
import android.os.*;
import io.nekohasekai.libbox.*;
import org.json.*;
import java.net.*;
import javax.net.ssl.HttpsURLConnection;
import java.util.concurrent.*;
/** Tests through each node's private HTTP CONNECT listener, never a direct fallback. */
public final class ProbeService extends Service implements CommandServerHandler {
    public static final String URL="https://www.gstatic.com/generate_204";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile boolean stopped;private boolean running;
    private volatile HttpsURLConnection request;
    private CommandServer server;private PlatformBridge platform;
    private JSONObject snapshot=new JSONObject(),results=new JSONObject();
    private String current="",probeUrl=URL,finishMessage="";private volatile boolean resourcesClosed,destroyed;
    @Override public void onCreate(){super.onCreate();CrashDiary.install(this,"测速");}
    @Override public IBinder onBind(Intent intent){return null;}
    private synchronized void save(boolean active,String message){
        try{snapshot.put("running",active).put("pid",android.os.Process.myPid()).put("message",message)
            .put("current",current).put("results",results);RunState.write(this,"probe-state",snapshot);}catch(Exception ignored){}
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent!=null&&"STOP".equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        if(running)return START_NOT_STICKY;running=true;stopped=false;
        snapshot=RunState.read(this,"probe-state");results=snapshot.optJSONObject("results");if(results==null)results=new JSONObject();
        save(true,"正在初始化真实连接测试");
        try{FNotification.show(this,34,"正在测试节点真实连接");}
        catch(Throwable t){CrashDiary.record(this,"测速前台通知",t);save(false,"启动失败："+t);stopSelf();return START_NOT_STICKY;}
        // Test endpoint is compiled out of signed release builds; only the emulator loopback gateway is accepted.
        if(BuildConfig.DEBUG&&intent!=null&&"https://10.0.2.2:18443/".equals(intent.getStringExtra("test_url")))probeUrl=intent.getStringExtra("test_url");
        final String only=intent==null?"":intent.getStringExtra("node");
        worker.execute(()->runTests(only));return START_NOT_STICKY;
    }
    private void result(String status,long delay,String detail){
        if(current.isEmpty())return;
        try{results.put(current,new JSONObject().put("status",status).put("delay",delay).put("detail",detail)
            .put("time",System.currentTimeMillis()));}catch(Exception ignored){}
    }
    private void runTests(String only){
        try{
            CoreRuntime.setup(this,"probe");
            platform=new PlatformBridge(this,fd->{platform.bindPhysical(fd);},options->{throw new UnsupportedOperationException("Probe must not create a VPN");},null);
            server=new CommandServer(this,platform);
            JSONArray nodes=new NodeStore(this).nodes();int tested=0;
            for(int i=0;i<nodes.length()&&!stopped;i++){
                JSONObject node=nodes.getJSONObject(i);String nodeId=node.getString("id");
                if(only!=null&&!only.isEmpty()&&!only.equals(nodeId))continue;
                current=nodeId;result("testing",0,"");save(true,"正在测试 "+node.getJSONObject("config").optString("tag","节点"));
                try{
                    int port;try(ServerSocket reservation=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=reservation.getLocalPort();}
                    String config=SingConfig.probe(node.getJSONObject("config"),port);Libbox.checkConfig(config);
                    server.startOrReloadService(config,new OverrideOptions());
                    if(stopped)break;
                    long begin=SystemClock.elapsedRealtime();
                    HttpsURLConnection c=(HttpsURLConnection)new java.net.URL(probeUrl).openConnection(
                        new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",port)));
                    request=c;c.setConnectTimeout(5000);c.setReadTimeout(5000);c.setUseCaches(false);
                    c.setInstanceFollowRedirects(false);c.setRequestProperty("Connection","close");
                    // Default TLS trust and hostname checks remain enabled.
                    try{
                        int status=c.getResponseCode();
                        if(status!=204)throw new java.io.IOException("测试地址返回 HTTP "+status+"，未通过 204 校验");
                        result("ok",Math.max(1,SystemClock.elapsedRealtime()-begin),"HTTPS 204 · "+probeUrl);
                    }finally{c.disconnect();request=null;}
                }catch(Throwable t){
                    result(stopped?"cancelled":"failed",0,stopped?"已取消":t.getClass().getSimpleName()+"："+String.valueOf(t.getMessage()));
                }finally{try{server.closeService();}catch(Throwable ignored){}}
                tested++;save(true,"已完成 "+tested+" 个节点");
            }
            if(stopped)result("cancelled",0,"已取消");
            finishMessage=stopped?"测速已停止":"测速完成，共 "+tested+" 个节点";
        }catch(Throwable t){CrashDiary.record(this,"测速内核",t);result("failed",0,t.toString());finishMessage="测速启动失败："+t;}
        finally{close();resourcesClosed=true;main.post(()->{stopSelf();if(destroyed)save(false,finishMessage);});}
    }
    private void close(){
        if(server!=null){try{server.closeService();}catch(Throwable ignored){}try{server.close();}catch(Throwable ignored){}server=null;}
        if(platform!=null)platform.closeDefaultInterfaceMonitor(null);
    }
    @Override public void onDestroy(){
        destroyed=true;stopped=true;HttpsURLConnection c=request;if(c!=null)c.disconnect();
        worker.shutdown();if(resourcesClosed)save(false,finishMessage);stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
    @Override public void serviceStop(){stopped=true;main.post(this::stopSelf);}
    @Override public void serviceReload()throws Exception{throw new UnsupportedOperationException("Disabled");}
    @Override public SystemProxyStatus getSystemProxyStatus(){return new SystemProxyStatus();}
    @Override public void setSystemProxyEnabled(boolean value){}
    @Override public int connectSSHAgent(){return -1;}
    @Override public void triggerNativeCrash()throws Exception{throw new UnsupportedOperationException("Disabled");}
    @Override public void writeDebugMessage(String message){android.util.Log.d("F-Probe",message);}
}
