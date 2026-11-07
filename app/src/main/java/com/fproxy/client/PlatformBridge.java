package com.fproxy.client;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;
import android.util.Log;
import io.nekohasekai.libbox.BridgeOptions;
import io.nekohasekai.libbox.BridgeSession;
import io.nekohasekai.libbox.ConnectionOwner;
import io.nekohasekai.libbox.InterfaceUpdateListener;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.LocalDNSTransport;
import io.nekohasekai.libbox.NeighborUpdateListener;
import io.nekohasekai.libbox.NetworkInterface;
import io.nekohasekai.libbox.NetworkInterfaceIterator;
import io.nekohasekai.libbox.Notification;
import io.nekohasekai.libbox.PlatformInterface;
import io.nekohasekai.libbox.PlatformUser;
import io.nekohasekai.libbox.ShellSession;
import io.nekohasekai.libbox.StringIterator;
import io.nekohasekai.libbox.TunOptions;
import io.nekohasekai.libbox.WIFIState;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class PlatformBridge implements PlatformInterface {
    private static final String TAG = "F-Platform";
    private final Context context;
    private volatile InterfaceUpdateListener interfaceListener;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final TunOpener opener;
    private final Protector protector;
    private final Underlying underlying;

    public interface Protector {
        void protect(int fd) throws Exception;
    }

    public interface TunOpener {
        int open(TunOptions options) throws Exception;
    }

    public interface Underlying {
        void update(Network[] networks);
    }

    public PlatformBridge(Context context, Protector protector, TunOpener opener, Underlying underlying) {
        this.context = context;
        this.protector = protector;
        this.opener = opener;
        this.underlying = underlying;
    }

    private ConnectivityManager connectivity() {
        return (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    static StringIterator strings(final List<String> list) {
        final Iterator<String> it = list.iterator();
        return new StringIterator() {
            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public int len() {
                return list.size();
            }

            @Override
            public String next() {
                return it.next();
            }
        };
    }

    private void updateNetwork() {
        InterfaceUpdateListener listener = this.interfaceListener;
        if (listener == null) {
            return;
        }
        try {
            Network net = physicalNetwork();
            LinkProperties lp = net == null ? null : connectivity().getLinkProperties(net);
            NetworkCapabilities caps = net == null ? null : connectivity().getNetworkCapabilities(net);
            java.net.NetworkInterface nif = null;
            if (lp != null && lp.getInterfaceName() != null) {
                nif = java.net.NetworkInterface.getByName(lp.getInterfaceName());
            }
            if (underlying != null) {
                underlying.update(net == null ? new Network[0] : new Network[]{net});
            }
            String name = nif == null ? "" : nif.getName();
            int index = nif == null ? -1 : nif.getIndex();
            boolean isExpensive = caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
            listener.updateDefaultInterface(name, index, isExpensive, false);
        } catch (Exception e) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Network update failed", e);
            }
        }
    }

    @Override
    public void autoDetectInterfaceControl(int fd) throws Exception {
        protector.protect(fd);
    }

    public void bindPhysical(int fd) throws Exception {
        Network net = physicalNetwork();
        if (net == null) {
            throw new IOException("没有可用的底层网络");
        }
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.fromFd(fd)) {
            net.bindSocket(pfd.getFileDescriptor());
        }
    }

    @Override
    public void cancelNotification(String tag, int id) {
    }

    @Override
    public void checkPlatformShell() throws Exception {
        throw new UnsupportedOperationException("Shell disabled");
    }

    @Override
    public void clearDNSCache() {
    }

    @Override
    public void closeDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
        this.interfaceListener = null;
        if (networkCallback != null) {
            try {
                connectivity().unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
            networkCallback = null;
        }
    }

    @Override
    public void closeNeighborMonitor(NeighborUpdateListener listener) {
    }

    @Override
    public BridgeSession createBridge(BridgeOptions options) throws Exception {
        throw new UnsupportedOperationException("Bridge disabled");
    }

    @Override
    public ConnectionOwner findConnectionOwner(
            int ipProtocol, String sourceAddress, int sourcePort, String destinationAddress, int destinationPort)
            throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Libbox.findConnectionOwner(ipProtocol, sourceAddress, sourcePort, destinationAddress, destinationPort);
        }
        int uid = connectivity().getConnectionOwnerUid(
                ipProtocol,
                new InetSocketAddress(sourceAddress, sourcePort),
                new InetSocketAddress(destinationAddress, destinationPort));
        if (uid < 0) {
            throw new IOException("Connection owner unavailable");
        }
        ConnectionOwner owner = new ConnectionOwner();
        owner.setUserId(uid);
        String[] pkgs = context.getPackageManager().getPackagesForUid(uid);
        List<String> list = pkgs == null ? Collections.emptyList() : Arrays.asList(pkgs);
        owner.setAndroidPackageNames(strings(list));
        return owner;
    }

    @Override
    public NetworkInterfaceIterator getInterfaces() throws Exception {
        List<NetworkInterface> list = new ArrayList<>();
        ConnectivityManager cm = connectivity();
        for (Network net : cm.getAllNetworks()) {
            LinkProperties lp = cm.getLinkProperties(net);
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (lp == null || caps == null || lp.getInterfaceName() == null) {
                continue;
            }
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                continue;
            }
            java.net.NetworkInterface nif = java.net.NetworkInterface.getByName(lp.getInterfaceName());
            if (nif == null) {
                continue;
            }
            NetworkInterface item = new NetworkInterface();
            item.setName(nif.getName());
            item.setIndex(nif.getIndex());
            item.setMTU(nif.getMTU());
            int type = 3;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                type = 0;
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                type = 1;
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                type = 2;
            }
            item.setType(type);

            int flags = 0;
            if (nif.isUp()) {
                flags = OsConstants.IFF_UP | OsConstants.IFF_RUNNING;
            }
            if (nif.isLoopback()) {
                flags |= OsConstants.IFF_LOOPBACK;
            }
            if (nif.isPointToPoint()) {
                flags |= OsConstants.IFF_POINTOPOINT;
            }
            if (nif.supportsMulticast()) {
                flags |= OsConstants.IFF_MULTICAST;
            }
            item.setFlags(flags);
            item.setMetered(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));

            List<String> addresses = new ArrayList<>();
            List<String> dnsServers = new ArrayList<>();
            List<String> gateways = new ArrayList<>();

            for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                if (ia.getAddress() != null) {
                    addresses.add(AddressText.prefix(ia.getAddress().getHostAddress(), ia.getNetworkPrefixLength()));
                }
            }
            for (InetAddress dns : lp.getDnsServers()) {
                dnsServers.add(dns.getHostAddress());
            }
            for (RouteInfo route : lp.getRoutes()) {
                if (route.isDefaultRoute() && route.getGateway() != null && !route.getGateway().isAnyLocalAddress()) {
                    gateways.add(route.getGateway().getHostAddress());
                }
            }
            item.setAddresses(strings(addresses));
            item.setDNSServer(strings(dnsServers));
            item.setGateway(strings(gateways));
            list.add(item);
        }
        final Iterator<NetworkInterface> it = list.iterator();
        return new NetworkInterfaceIterator() {
            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public NetworkInterface next() {
                return it.next();
            }
        };
    }

    @Override
    public boolean includeAllNetworks() {
        return false;
    }

    @Override
    public LocalDNSTransport localDNSTransport() {
        return null;
    }

    @Override
    public String lookupSFTPServer() throws Exception {
        throw new UnsupportedOperationException("SFTP disabled");
    }

    @Override
    public PlatformUser lookupUser(String user) throws Exception {
        throw new UnsupportedOperationException("Shell disabled");
    }

    @Override
    public ShellSession openShellSession(
            PlatformUser user, String command, StringIterator args, String workingDir, int rows, int cols)
            throws Exception {
        throw new UnsupportedOperationException("Shell disabled");
    }

    @Override
    public int openTun(TunOptions options) throws Exception {
        return opener.open(options);
    }

    public Network physicalNetwork() {
        ConnectivityManager cm = connectivity();
        Network active = cm.getActiveNetwork();
        Network best = null;
        int bestScore = -1;
        for (Network net : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps == null
                    || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                continue;
            }
            int score = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? 4 : 0;
            if (net.equals(active)) {
                score += 2;
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                score += 1;
            }
            if (score > bestScore) {
                best = net;
                bestScore = score;
            }
        }
        return best;
    }

    @Override
    public String readSystemSSHHostKey() throws Exception {
        throw new UnsupportedOperationException("SSH disabled");
    }

    @Override
    public WIFIState readWIFIState() {
        return null;
    }

    @Override
    public void registerMyInterface(String name) {
    }

    @Override
    public void sendNotification(Notification notification) {
        if (BuildConfig.DEBUG && notification != null) {
            Log.i(TAG, notification.getTitle() + ": " + notification.getBody());
        }
    }

    @Override
    public void startDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
        this.interfaceListener = listener;
        if (networkCallback == null) {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    updateNetwork();
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
                    updateNetwork();
                }

                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                    updateNetwork();
                }

                @Override
                public void onLost(Network network) {
                    updateNetwork();
                }
            };
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build();
            connectivity().registerNetworkCallback(request, networkCallback);
        }
        updateNetwork();
    }

    @Override
    public void startNeighborMonitor(NeighborUpdateListener listener) {
    }

    @Override
    public String tailscaleHostname() {
        // 避免暴露真实设备硬件型号 Build.MODEL
        return "android-client";
    }

    @Override
    public boolean underNetworkExtension() {
        return false;
    }

    @Override
    public boolean usePlatformAutoDetectInterfaceControl() {
        return true;
    }

    @Override
    public boolean usePlatformBridge() {
        return false;
    }

    @Override
    public boolean usePlatformShell() {
        return false;
    }
}
