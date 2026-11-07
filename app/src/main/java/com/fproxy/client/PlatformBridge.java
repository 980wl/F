package com.fproxy.client;
import android.content.Context;
import android.net.*;
import android.os.*;
import android.system.OsConstants;
import android.util.Log;
import io.nekohasekai.libbox.*;
import java.net.InterfaceAddress;
import java.net.InetSocketAddress;
import java.util.*;
/** Shared Android integration for isolated VPN and real-connection probe processes. */
public final class PlatformBridge implements PlatformInterface {
    public interface Protector {void protect(int fd)throws Exception;}
    public interface TunOpener {int open(TunOptions options)throws Exception;}
    public interface Underlying {void update(Network[] networks);}
    private final Context context;private final Protector protector;private final TunOpener opener;private final Underlying underlying;
    private volatile InterfaceUpdateListener interfaceListener;
    private ConnectivityManager.NetworkCallback networkCallback;
    private static final String TAG="F-Platform";
    public PlatformBridge(Context c,Protector p,TunOpener t,Underlying u){context=c;protector=p;opener=t;underlying=u;}
    private ConnectivityManager connectivity(){return (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);}
    @Override public boolean usePlatformAutoDetectInterfaceControl(){return true;}
    @Override public void autoDetectInterfaceControl(int fd)throws Exception{protector.protect(fd);}
    @Override public int openTun(TunOptions options)throws Exception{return opener.open(options);}
    public void bindPhysical(int fd)throws Exception{
        Network n=physicalNetwork();if(n==null)throw new java.io.IOException("没有可用的底层网络");
        try(ParcelFileDescriptor copy=ParcelFileDescriptor.fromFd(fd)){n.bindSocket(copy.getFileDescriptor());}
    }
    public Network physicalNetwork() {
        ConnectivityManager cm = connectivity();
        Network active = cm.getActiveNetwork();
        Network best = null;
        int bestScore = -1;
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;

            int score = 0;
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 4;
            if (network.equals(active)) score += 2;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) score++;
            if (score > bestScore) {
                best = network;
                bestScore = score;
            }
        }
        return best;
    }
    private void updateNetwork(){
        InterfaceUpdateListener listener=interfaceListener;if(listener==null)return;
        try{
            Network network=physicalNetwork();
            LinkProperties lp=network==null?null:connectivity().getLinkProperties(network);
            NetworkCapabilities caps=network==null?null:connectivity().getNetworkCapabilities(network);
            java.net.NetworkInterface ni=lp==null||lp.getInterfaceName()==null?null:java.net.NetworkInterface.getByName(lp.getInterfaceName());
            if(underlying!=null)underlying.update(network==null?new Network[0]:new Network[]{network});
            listener.updateDefaultInterface(ni==null?"":ni.getName(),ni==null?-1:ni.getIndex(),
                    caps!=null&&!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),false);
        }catch(Exception e){Log.w(TAG,"Network update failed",e);}
    }
    @Override public void startDefaultInterfaceMonitor(InterfaceUpdateListener listener){
        interfaceListener=listener;
        if(networkCallback==null){
            networkCallback=new ConnectivityManager.NetworkCallback(){
                @Override public void onAvailable(Network n){updateNetwork();}
                @Override public void onLost(Network n){updateNetwork();}
                @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c){updateNetwork();}
                @Override public void onLinkPropertiesChanged(Network n,LinkProperties lp){updateNetwork();}
            };
            connectivity().registerNetworkCallback(new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),networkCallback);
        }
        updateNetwork();
    }
    @Override public void closeDefaultInterfaceMonitor(InterfaceUpdateListener listener){
        interfaceListener=null;
        if(networkCallback!=null){try{connectivity().unregisterNetworkCallback(networkCallback);}catch(Exception ignored){}networkCallback=null;}
    }
    @Override public NetworkInterfaceIterator getInterfaces()throws Exception{
        List<io.nekohasekai.libbox.NetworkInterface> all=new ArrayList<>();
        for(Network network:connectivity().getAllNetworks()){
            LinkProperties link=connectivity().getLinkProperties(network);
            NetworkCapabilities caps=connectivity().getNetworkCapabilities(network);
            if(link==null||caps==null||link.getInterfaceName()==null||!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))continue;
            java.net.NetworkInterface ni=java.net.NetworkInterface.getByName(link.getInterfaceName());if(ni==null)continue;
            io.nekohasekai.libbox.NetworkInterface box=new io.nekohasekai.libbox.NetworkInterface();
            box.setName(ni.getName());box.setIndex(ni.getIndex());box.setMTU(ni.getMTU());
            box.setType(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?Libbox.InterfaceTypeWIFI:
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?Libbox.InterfaceTypeCellular:
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)?Libbox.InterfaceTypeEthernet:Libbox.InterfaceTypeOther);
            int flags=ni.isUp()?OsConstants.IFF_UP|OsConstants.IFF_RUNNING:0;
            if(ni.isLoopback())flags|=OsConstants.IFF_LOOPBACK;
            if(ni.isPointToPoint())flags|=OsConstants.IFF_POINTOPOINT;
            if(ni.supportsMulticast())flags|=OsConstants.IFF_MULTICAST;
            box.setFlags(flags);box.setMetered(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
            List<String> addresses=new ArrayList<>(),dns=new ArrayList<>(),gateways=new ArrayList<>();
            for(InterfaceAddress a:ni.getInterfaceAddresses())if(a.getAddress()!=null)
                addresses.add(AddressText.prefix(a.getAddress().getHostAddress(),a.getNetworkPrefixLength()));
            for(java.net.InetAddress a:link.getDnsServers())dns.add(a.getHostAddress());
            for(RouteInfo route:link.getRoutes())if(route.isDefaultRoute()&&route.getGateway()!=null&&!route.getGateway().isAnyLocalAddress())gateways.add(route.getGateway().getHostAddress());
            box.setAddresses(strings(addresses));box.setDNSServer(strings(dns));box.setGateway(strings(gateways));all.add(box);
        }
        Iterator<io.nekohasekai.libbox.NetworkInterface> it=all.iterator();
        return new NetworkInterfaceIterator(){public boolean hasNext(){return it.hasNext();}public io.nekohasekai.libbox.NetworkInterface next(){return it.next();}};
    }
    static StringIterator strings(List<String> list){Iterator<String> it=list.iterator();
        return new StringIterator(){public boolean hasNext(){return it.hasNext();}public String next(){return it.next();}public int len(){return list.size();}};}
    @Override public ConnectionOwner findConnectionOwner(int protocol,String src,int sp,String dst,int dp)throws Exception{
        if(Build.VERSION.SDK_INT<29)return Libbox.findConnectionOwner(protocol,src,sp,dst,dp);
        int uid=connectivity().getConnectionOwnerUid(protocol,new InetSocketAddress(src,sp),new InetSocketAddress(dst,dp));
        if(uid<0)throw new java.io.IOException("Connection owner unavailable");
        ConnectionOwner owner=new ConnectionOwner();owner.setUserId(uid);
        String[] packages=context.getPackageManager().getPackagesForUid(uid);
        owner.setAndroidPackageNames(strings(packages==null?Collections.emptyList():Arrays.asList(packages)));return owner;
    }
    @Override public boolean underNetworkExtension(){return false;}
    @Override public boolean includeAllNetworks(){return false;}
    @Override public WIFIState readWIFIState(){return null;}
    @Override public void clearDNSCache(){}
    @Override public LocalDNSTransport localDNSTransport(){return null;}
    @Override public void registerMyInterface(String name){}
    @Override public void startNeighborMonitor(NeighborUpdateListener listener){}
    @Override public void closeNeighborMonitor(NeighborUpdateListener listener){}
    @Override public String tailscaleHostname(){return Build.MODEL;}
    @Override public boolean usePlatformShell(){return false;}
    @Override public boolean usePlatformBridge(){return false;}
    @Override public void checkPlatformShell()throws Exception{throw new UnsupportedOperationException("Shell disabled");}
    @Override public ShellSession openShellSession(PlatformUser u,String c,StringIterator e,String t,int r,int col)throws Exception{throw new UnsupportedOperationException("Shell disabled");}
    @Override public PlatformUser lookupUser(String user)throws Exception{throw new UnsupportedOperationException("Shell disabled");}
    @Override public String lookupSFTPServer()throws Exception{throw new UnsupportedOperationException("SFTP disabled");}
    @Override public String readSystemSSHHostKey()throws Exception{throw new UnsupportedOperationException("SSH disabled");}
    @Override public BridgeSession createBridge(BridgeOptions options)throws Exception{throw new UnsupportedOperationException("Bridge disabled");}
    @Override public void sendNotification(io.nekohasekai.libbox.Notification n){Log.i(TAG,n.getTitle()+": "+n.getBody());}
    @Override public void cancelNotification(String id,int type){}

}
