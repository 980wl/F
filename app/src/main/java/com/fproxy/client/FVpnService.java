package com.fproxy.client;
import android.content.Intent;
import android.net.VpnService;
import android.os.*;
import io.nekohasekai.libbox.*;
import java.util.concurrent.*;
/** Native code lives in :core so a JNI/Go crash cannot kill the node list. */
public final class FVpnService extends VpnService implements CommandServerHandler {
    public static final String ACTION_STOP="STOP";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile boolean stopping,started;private boolean cleanupQueued;private volatile boolean destroyed,resourcesClosed;
    private AppDirectPolicy.Snapshot directPolicy;
    private ParcelFileDescriptor tun;private CommandServer server;private PlatformBridge platform;
    private String nodeId="",lastError="";
    @Override public void onCreate(){super.onCreate();CrashDiary.install(this,"VPN");}
    private void state(String phase){RunState.vpn(this,phase,lastError,nodeId);}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent!=null&&ACTION_STOP.equals(intent.getAction())){requestStop();return START_NOT_STICKY;}
        if(started)return START_NOT_STICKY;
        started=true;stopping=false;state("starting");
        try{FNotification.show(this,33,"正在连接");}
        catch(Throwable t){fail("创建前台通知",t);requestStop();return START_NOT_STICKY;}
        worker.execute(()->{
            String stage="初始化内核";
            try{
                CoreRuntime.setup(this);
                NodeStore store=new NodeStore(this);nodeId=store.selected();
                stage="识别应用直连规则";
                directPolicy=AppDirectPolicy.scan(this);
                if(!directPolicy.conflict.isEmpty())throw new IllegalStateException(directPolicy.conflict);
                if(Build.VERSION.SDK_INT>=29&&isLockdownEnabled())throw new IllegalStateException("请关闭系统 VPN 设置中的‘阻止未使用 VPN 的连接’，否则直连应用会断网。");
                stage="载入中国大陆 IP 规则";
                String config=SingConfig.mainlandSplit(store.selectedConfig(),RuleResources.mainland(this));
                stage="验证节点配置";Libbox.checkConfig(config);
                if(stopping)return;
                platform=new PlatformBridge(this,fd->{if(!protect(fd))throw new java.io.IOException("内核 socket 保护失败");},this::openTun,this::setUnderlyingNetworks);
                stage="创建内核服务";server=new CommandServer(this,platform);
                // No command listener is needed: all controls use explicit service intents.
                stage="启动 VPN";server.startOrReloadService(config,new OverrideOptions());
                if(tun==null)throw new IllegalStateException("内核没有建立 VPN 接口");
                if(!stopping){state("connected");main.post(()->{if(!stopping)try{FNotification.show(this,33,"大陆直连 / 境外代理");}catch(Throwable t){fail("更新通知",t);requestStop();}});}
            }catch(Throwable t){fail(stage,t);main.post(this::requestStop);}
        });
        return START_NOT_STICKY;
    }
    private void fail(String stage,Throwable t){lastError=stage+"："+t;CrashDiary.record(this,stage,t);state("failed");}
    private int openTun(TunOptions options)throws Exception{
        if(stopping)throw new IllegalStateException("VPN 正在关闭");
        if(prepare(this)!=null)throw new SecurityException("VPN 尚未获得授权");
        if(tun!=null){tun.close();tun=null;}
        Builder b=new Builder().setSession("F · 大陆 IP 与国内应用直连").setMtu(options.getMTU());
        if(Build.VERSION.SDK_INT>=29)b.setMetered(false);
        RoutePrefixIterator v4=options.getInet4Address(),v6=options.getInet6Address();
        while(v4.hasNext()){RoutePrefix p=v4.next();b.addAddress(p.address(),p.prefix());}
        while(v6.hasNext()){RoutePrefix p=v6.next();b.addAddress(p.address(),p.prefix());}
        StringIterator dns=options.getDNSServerAddress();while(dns.hasNext())b.addDnsServer(dns.next());
        b.addRoute("0.0.0.0",0).addRoute("::",0);
        if(directPolicy==null)throw new IllegalStateException("应用直连策略未初始化");
        org.json.JSONArray applied=new org.json.JSONArray();
        for(String pkg:directPolicy.excluded){
            try{b.addDisallowedApplication(pkg);applied.put(pkg);}
            catch(android.content.pm.PackageManager.NameNotFoundException removed){android.util.Log.i("F-Core","Application removed during startup: "+pkg);}
        }
        RunState.write(this,"app-direct-active",new org.json.JSONObject().put("packages",applied).put("browser_count",directPolicy.browsers));
        tun=b.establish();if(tun==null)throw new IllegalStateException("Android VPN 建立失败");return tun.getFd();
    }
    @Override public void serviceStop(){main.post(this::requestStop);}
    @Override public void serviceReload()throws Exception{throw new UnsupportedOperationException("请断开后重新连接");}
    @Override public SystemProxyStatus getSystemProxyStatus(){return new SystemProxyStatus();}
    @Override public void setSystemProxyEnabled(boolean enabled){}
    @Override public int connectSSHAgent(){return -1;}
    @Override public void triggerNativeCrash()throws Exception{throw new UnsupportedOperationException("Disabled");}
    @Override public void writeDebugMessage(String message){android.util.Log.d("F-Core",message);}
    @Override public void onRevoke(){lastError="系统撤销了 VPN 授权";requestStop();}
    private void requestStop(){
        if(cleanupQueued)return;cleanupQueued=true;stopping=true;if(lastError.isEmpty())state("stopping");
        worker.execute(()->{
            if(server!=null){try{server.closeService();}catch(Throwable t){CrashDiary.record(this,"关闭内核",t);}
                try{server.close();}catch(Throwable t){CrashDiary.record(this,"关闭控制服务",t);}server=null;}
            if(platform!=null)platform.closeDefaultInterfaceMonitor(null);
            if(tun!=null){try{tun.close();}catch(Exception ignored){}tun=null;}
            resourcesClosed=true;
            main.post(()->{stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
                if(destroyed)state(lastError.isEmpty()?"idle":"failed");});
        });
        worker.shutdown();
    }
    @Override public void onDestroy(){destroyed=true;requestStop();if(resourcesClosed)state(lastError.isEmpty()?"idle":"failed");super.onDestroy();}
}
