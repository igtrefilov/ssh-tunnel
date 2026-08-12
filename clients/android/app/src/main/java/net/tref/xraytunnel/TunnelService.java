package net.tref.xraytunnel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.widget.RemoteViews;

import com.jcraft.jsch.ChannelDirectTCPIP;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.Socket;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A standalone Android VPN which carries selected application traffic through
 * a SOCKS5 service reachable over an SSH local forward.
 *
 * <pre>
 * selected apps -> Android TUN -> hev-socks5-tunnel -> local SOCKS port
 *              -> SSH local forward -> 127.0.0.1:1080 on the VPS
 * </pre>
 */
public final class TunnelService extends VpnService {
    public static final String ACTION_START = "net.tref.xraytunnel.START";
    public static final String ACTION_STOP = "net.tref.xraytunnel.STOP";
    public static final String PREFS = TunnelSettings.PREFS;
    public static final String KEY_STATUS = TunnelSettings.KEY_STATUS;
    public static final String KEY_VPS_REACHABILITY = TunnelSettings.KEY_VPS_REACHABILITY;
    public static final int REACHABILITY_UNKNOWN = TunnelSettings.REACHABILITY_UNKNOWN;
    public static final int REACHABILITY_REACHABLE = TunnelSettings.REACHABILITY_REACHABLE;
    public static final int REACHABILITY_UNREACHABLE = TunnelSettings.REACHABILITY_UNREACHABLE;
    public static final int REACHABILITY_DEGRADED = TunnelSettings.REACHABILITY_DEGRADED;

    private static final String TAG = "StandaloneSshVpn";
    private static final String CHANNEL_ID = "ssh-vpn";
    private static final String LOCAL_PROXY_HOST = "127.0.0.1";
    private static final int RETRY_DELAY_MS = 1500;
    private static final int SSH_CONNECT_TIMEOUT_MS = 15000;
    private static final int SSH_KEEPALIVE_INTERVAL_MS = 10000;
    private static final int SSH_KEEPALIVE_COUNT_MAX = 3;
    private static final int ROUTE_PROBE_CONNECT_TIMEOUT_MS = 1000;
    private static final int INTERNET_PROBE_CONNECT_TIMEOUT_MS = 1000;
    private static final int ONLINE_ROUTE_FAILURE_THRESHOLD = 3;
    private static final long ONLINE_ROUTE_PROBE_INTERVAL_MS = 1000;
    private static final long DIAGNOSTIC_PROBE_INTERVAL_MS = 1000;
    private static final long DIAGNOSTIC_PROBE_WAIT_MS = 1200;
    private static final long RETRY_WAIT_MS = 500;
    public static final String STATUS_TUNNEL_DOWN = "Tunnel Down";
    public static final String STATUS_VPS_DOWN = "VPS Down";
    public static final String STATUS_CHEBURNET = "Cheburnet";
    public static final String STATUS_OFFLINE = "Offline";
    public static final String STATUS_ONLINE = "Online";
    public static final String STATUS_STOPPED = "Stopped";
    private static final String YA_HOST = "ya.ru";
    private static final String GOOGLE_HOST = "google.com";
    private static final int HTTPS_PORT = 443;

    private static native boolean TProxyStartService(String configPath, int fd);
    private static native boolean TProxyStopService();
    private static native boolean TProxyIsRunning();
    private static native long[] TProxyGetStats();

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService reachabilityExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService diagnosticProbeExecutor = Executors.newFixedThreadPool(3);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean foregroundStarted = new AtomicBoolean(false);
    private final AtomicBoolean reachabilityMonitorRunning = new AtomicBoolean(false);
    private final AtomicBoolean tunnelOnline = new AtomicBoolean(false);
    private final AtomicBoolean tunnelConnecting = new AtomicBoolean(false);
    private final AtomicBoolean routeReachable = new AtomicBoolean(false);
    private final Object reachabilitySignal = new Object();
    private final Object networkLock = new Object();
    private volatile SshConnection sshConnection;
    private volatile ParcelFileDescriptor tunInterface;
    private volatile File nativeConfig;
    private volatile TunnelProfile activeProfile;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopTunnel();
            return START_NOT_STICKY;
        }
        startTunnel();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopTunnel();
        worker.shutdownNow();
        reachabilityExecutor.shutdownNow();
        diagnosticProbeExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        stopTunnel();
        super.onRevoke();
    }

    private void startTunnel() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        createNotificationChannel();
        activeProfile = TunnelSettings.profiles(this)[0];
        tunnelOnline.set(false);
        tunnelConnecting.set(false);
        routeReachable.set(false);
        updateConnectionStatus(STATUS_OFFLINE, REACHABILITY_UNKNOWN);
        registerNetworkCallback();
        startReachabilityMonitor();
        worker.execute(this::runTunnelLoop);
    }

    private void stopTunnel() {
        boolean wasRunning = running.getAndSet(false);
        if (!wasRunning) {
            updateConnectionStatus(STATUS_STOPPED, REACHABILITY_UNKNOWN);
            stopForeground(true);
            foregroundStarted.set(false);
            stopSelf();
            return;
        }
        tunnelOnline.set(false);
        tunnelConnecting.set(false);
        routeReachable.set(false);
        signalReachabilityMonitor();
        unregisterNetworkCallback();
        stopNativeTunnel();
        closeVpnInterface();
        disconnectSsh();
        updateConnectionStatus(STATUS_STOPPED, REACHABILITY_UNKNOWN);
        stopForeground(true);
        foregroundStarted.set(false);
        stopSelf();
    }

    private void runTunnelLoop() {
        while (running.get()) {
            SshConnection nextConnection = null;
            ParcelFileDescriptor nextTun = null;
            File nextConfig = null;
            int forwardedPort = -1;
            try {
                TunnelProfile profile = activeProfile;
                if (profile == null) {
                    profile = TunnelSettings.profiles(this)[0];
                    activeProfile = profile;
                }
                if (profile.allowedApplications.isEmpty()) {
                    throw new IllegalStateException("Select at least one application");
                }

                if (!waitForReachableServer()) {
                    continue;
                }
                tunnelConnecting.set(true);
                updateConnectionStatus("Connecting SSH", REACHABILITY_UNKNOWN);
                nextConnection = connectSsh(profile);
                Session nextSession = nextConnection.gatewaySession;
                forwardedPort = nextSession.setPortForwardingL(
                        LOCAL_PROXY_HOST,
                        TunnelConfig.LOCAL_PROXY_PORT,
                        profile.proxyHost,
                        profile.proxyPort);
                sshConnection = nextConnection;

                nextTun = establishVpn(profile);
                tunInterface = nextTun;
                nextConfig = writeNativeConfig();
                nativeConfig = nextConfig;

                if (!TProxyStartService(nextConfig.getAbsolutePath(), nextTun.getFd())) {
                    throw new IOException("Native TUN engine did not start");
                }
                waitForNativeStart();
                tunnelConnecting.set(false);
                tunnelOnline.set(true);
                routeReachable.set(true);
                updateConnectionStatus(STATUS_ONLINE, REACHABILITY_REACHABLE);
                signalReachabilityMonitor();

                while (running.get()
                        && nextConnection.isConnected()
                        && TProxyIsRunning()) {
                    Thread.sleep(250);
                }
                if (running.get()) {
                    throw new IOException("SSH or TUN engine stopped");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running.get()) {
                    Log.w(TAG, "Tunnel cycle failed: " + e.getMessage(), e);
                    markTunnelUnavailable();
                }
            } finally {
                tunnelConnecting.set(false);
                stopNativeTunnel();
                closeQuietly(nextTun);
                if (nextConnection != null) {
                    Session nextSession = nextConnection.gatewaySession;
                    if (forwardedPort >= 0) {
                        try {
                            nextSession.delPortForwardingL(
                                    LOCAL_PROXY_HOST,
                                    forwardedPort);
                        } catch (Exception ignored) {
                            // The session may already be disconnected.
                        }
                    }
                    nextConnection.disconnect();
                }
                if (nextConfig != null) {
                    nextConfig.delete();
                }
                if (sshConnection == nextConnection) {
                    sshConnection = null;
                }
                if (tunInterface == nextTun) {
                    tunInterface = null;
                }
                nativeConfig = null;
                signalReachabilityMonitor();
            }
            if (running.get()) {
                waitBeforeRetry();
            }
        }
    }

    private SshConnection connectSsh(TunnelProfile profile) throws Exception {
        Session jumpSession = null;
        JumpHostProxy jumpProxy = null;
        Session gatewaySession = null;
        try {
            if (profile.jumpEnabled) {
                updateConnectionStatus("Connecting jump host", REACHABILITY_UNKNOWN);
                JSch jumpJsch = configuredJsch(
                        profile.jumpPrivateKeyAsset,
                        profile.jumpHost + "-jump",
                        profile.verifyHostKey);
                jumpSession = jumpJsch.getSession(
                        profile.jumpUser,
                        profile.jumpHost,
                        profile.jumpPort);
                configureSession(jumpSession, profile.verifyHostKey);
                jumpSession.setSocketFactory(new UnderlyingNetworkSocketFactory(this));
                jumpSession.connect(SSH_CONNECT_TIMEOUT_MS);
                jumpProxy = new JumpHostProxy(jumpSession);
            }

            updateConnectionStatus("Connecting gateway", REACHABILITY_UNKNOWN);
            JSch gatewayJsch = configuredJsch(
                    profile.privateKeyAsset,
                    profile.sshHost + "-gateway",
                    profile.verifyHostKey);
            gatewaySession = gatewayJsch.getSession(
                    profile.sshUser,
                    profile.sshHost,
                    profile.sshPort);
            configureSession(gatewaySession, profile.verifyHostKey);
            if (jumpProxy == null) {
                gatewaySession.setSocketFactory(new UnderlyingNetworkSocketFactory(this));
            } else {
                gatewaySession.setProxy(jumpProxy);
            }
            gatewaySession.connect(SSH_CONNECT_TIMEOUT_MS);
            return new SshConnection(
                    gatewaySession,
                    jumpSession,
                    jumpProxy,
                    profile.sshHost,
                    profile.sshPort);
        } catch (Exception error) {
            if (gatewaySession != null) {
                gatewaySession.disconnect();
            }
            if (jumpProxy != null) {
                jumpProxy.close();
            }
            if (jumpSession != null) {
                jumpSession.disconnect();
            }
            throw error;
        }
    }

    private JSch configuredJsch(
            String privateKeyAsset,
            String identityName,
            boolean verifyHostKey) throws Exception {
        JSch jsch = new JSch();
        jsch.addIdentity(
                identityName,
                SshKeyStore.privateKey(this, privateKeyAsset),
                null,
                null);
        if (verifyHostKey) {
            SshHostKeyStore.configure(this, jsch);
        }
        return jsch;
    }

    private void configureSession(Session next, boolean verifyHostKey) throws Exception {
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", verifyHostKey ? "yes" : "no");
        config.put("PreferredAuthentications", "publickey");
        next.setConfig(config);
        next.setServerAliveInterval(SSH_KEEPALIVE_INTERVAL_MS);
        next.setServerAliveCountMax(SSH_KEEPALIVE_COUNT_MAX);
    }

    private void startReachabilityMonitor() {
        if (!reachabilityMonitorRunning.compareAndSet(false, true)) {
            return;
        }
        reachabilityExecutor.execute(this::runReachabilityMonitor);
    }

    private void runReachabilityMonitor() {
        int consecutiveFailures = 0;
        try {
            while (running.get()) {
                TunnelProfile profile = activeProfile;
                if (profile == null) {
                    waitForNextReachabilityProbe(DIAGNOSTIC_PROBE_INTERVAL_MS);
                    continue;
                }

                if (tunnelOnline.get()) {
                    if (isOnlineRouteReachable(profile)) {
                        consecutiveFailures = 0;
                    } else {
                        consecutiveFailures++;
                        Log.w(TAG, "SSH route probe failed ("
                                + consecutiveFailures + "/"
                                + ONLINE_ROUTE_FAILURE_THRESHOLD + ")");
                        if (consecutiveFailures >= ONLINE_ROUTE_FAILURE_THRESHOLD) {
                            consecutiveFailures = 0;
                            handleOnlineRouteFailure(profile);
                        }
                    }
                    waitForNextReachabilityProbe(ONLINE_ROUTE_PROBE_INTERVAL_MS);
                    continue;
                }

                consecutiveFailures = 0;
                if (!tunnelConnecting.get()) {
                    updateDiagnosticStatus(probeConnectivity(profile));
                }
                waitForNextReachabilityProbe(DIAGNOSTIC_PROBE_INTERVAL_MS);
            }
        } finally {
            reachabilityMonitorRunning.set(false);
        }
    }

    private boolean isOnlineRouteReachable(TunnelProfile profile) {
        SshConnection current = sshConnection;
        if (current == null || !current.isConnected()) {
            return false;
        }
        if (profile.jumpEnabled) {
            return current.isGatewayReachable(ROUTE_PROBE_CONNECT_TIMEOUT_MS);
        }
        return isReachable(
                profile.sshHost,
                profile.sshPort,
                ROUTE_PROBE_CONNECT_TIMEOUT_MS);
    }

    private boolean isReachable(String host, int port, int timeoutMs) {
        try (Socket socket = new UnderlyingNetworkSocketFactory(
                this,
                timeoutMs,
                false).createSocket(host, port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private DiagnosticResult probeConnectivity(TunnelProfile profile) {
        AtomicBoolean route = new AtomicBoolean(false);
        AtomicBoolean ya = new AtomicBoolean(false);
        AtomicBoolean google = new AtomicBoolean(false);
        CountDownLatch completed = new CountDownLatch(3);

        diagnosticProbeExecutor.execute(() -> {
            route.set(isReachable(
                    profile.jumpEnabled ? profile.jumpHost : profile.sshHost,
                    profile.jumpEnabled ? profile.jumpPort : profile.sshPort,
                    ROUTE_PROBE_CONNECT_TIMEOUT_MS));
            completed.countDown();
        });
        diagnosticProbeExecutor.execute(() -> {
            ya.set(isReachable(YA_HOST, HTTPS_PORT, INTERNET_PROBE_CONNECT_TIMEOUT_MS));
            completed.countDown();
        });
        diagnosticProbeExecutor.execute(() -> {
            google.set(isReachable(GOOGLE_HOST, HTTPS_PORT, INTERNET_PROBE_CONNECT_TIMEOUT_MS));
            completed.countDown();
        });

        try {
            completed.await(DIAGNOSTIC_PROBE_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new DiagnosticResult(route.get(), ya.get(), google.get());
    }

    private synchronized void updateDiagnosticStatus(DiagnosticResult result) {
        if (!running.get() || tunnelOnline.get() || tunnelConnecting.get()) {
            return;
        }

        routeReachable.set(result.routeReachable);
        if (result.routeReachable) {
            updateConnectionStatus(STATUS_TUNNEL_DOWN, REACHABILITY_UNREACHABLE);
            signalReachabilityMonitor();
            return;
        }
        if (result.yaReachable && result.googleReachable) {
            updateConnectionStatus(STATUS_VPS_DOWN, REACHABILITY_UNREACHABLE);
            return;
        }
        if (result.yaReachable != result.googleReachable) {
            updateConnectionStatus(STATUS_CHEBURNET, REACHABILITY_DEGRADED);
            return;
        }
        updateConnectionStatus(STATUS_OFFLINE, REACHABILITY_UNKNOWN);
    }

    private void handleOnlineRouteFailure(TunnelProfile profile) {
        if (!running.get() || !tunnelOnline.get()) {
            return;
        }

        Log.w(TAG, "SSH route failure threshold reached; restarting tunnel");
        markTunnelUnavailable();
        disconnectSsh();
        DiagnosticResult result = probeConnectivity(profile);
        if (running.get() && !tunnelOnline.get()) {
            updateDiagnosticStatus(result);
        }
    }

    private boolean waitForReachableServer() throws InterruptedException {
        while (running.get() && !routeReachable.get()) {
            waitOnReachabilitySignal(RETRY_WAIT_MS);
        }
        return running.get();
    }

    private void waitForNextReachabilityProbe(long timeoutMs) {
        try {
            waitOnReachabilitySignal(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void waitOnReachabilitySignal(long timeoutMs) throws InterruptedException {
        synchronized (reachabilitySignal) {
            reachabilitySignal.wait(timeoutMs);
        }
    }

    private void signalReachabilityMonitor() {
        synchronized (reachabilitySignal) {
            reachabilitySignal.notifyAll();
        }
    }

    private void registerNetworkCallback() {
        ConnectivityManager manager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return;
        }

        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                signalReachabilityMonitor();
            }

            @Override
            public void onLost(Network network) {
                signalReachabilityMonitor();
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                signalReachabilityMonitor();
            }
        };
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build();

        synchronized (networkLock) {
            if (networkCallback != null) {
                return;
            }
            connectivityManager = manager;
            networkCallback = callback;
        }
        try {
            manager.registerNetworkCallback(request, callback);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to register underlying network callback", e);
            synchronized (networkLock) {
                connectivityManager = null;
                networkCallback = null;
            }
        }
    }

    private void unregisterNetworkCallback() {
        ConnectivityManager manager;
        ConnectivityManager.NetworkCallback callback;
        synchronized (networkLock) {
            manager = connectivityManager;
            callback = networkCallback;
            connectivityManager = null;
            networkCallback = null;
        }
        if (manager != null && callback != null) {
            try {
                manager.unregisterNetworkCallback(callback);
            } catch (RuntimeException ignored) {
                // The callback may already be gone during service teardown.
            }
        }
    }

    private void markTunnelUnavailable() {
        if (!running.get()) {
            return;
        }
        boolean wasOnline = tunnelOnline.getAndSet(false);
        tunnelConnecting.set(false);
        routeReachable.set(false);
        if (wasOnline) {
            updateConnectionStatus(STATUS_TUNNEL_DOWN, REACHABILITY_UNREACHABLE);
        }
        signalReachabilityMonitor();
    }

    private static final class DiagnosticResult {
        final boolean routeReachable;
        final boolean yaReachable;
        final boolean googleReachable;

        DiagnosticResult(boolean routeReachable, boolean yaReachable, boolean googleReachable) {
            this.routeReachable = routeReachable;
            this.yaReachable = yaReachable;
            this.googleReachable = googleReachable;
        }
    }

    private ParcelFileDescriptor establishVpn(TunnelProfile profile) throws Exception {
        Builder builder = new Builder()
                .setSession("SSH split tunnel")
                .setBlocking(false)
                .setMtu(TunnelConfig.VPN_MTU)
                .addAddress(TunnelConfig.VPN_IPV4_ADDRESS, TunnelConfig.VPN_IPV4_PREFIX)
                .addAddress(TunnelConfig.VPN_IPV6_ADDRESS, TunnelConfig.VPN_IPV6_PREFIX)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer(TunnelConfig.VPN_DNS_ADDRESS);

        int validApps = 0;
        for (String packageName : profile.allowedApplications) {
            try {
                getPackageManager().getPackageInfo(packageName, 0);
                builder.addAllowedApplication(packageName);
                validApps++;
            } catch (Exception e) {
                Log.w(TAG, "Skipping unavailable application " + packageName);
            }
        }
        if (validApps == 0) {
            throw new IllegalStateException("No selected applications are installed");
        }
        ParcelFileDescriptor established = builder.establish();
        if (established == null) {
            throw new IOException("VPN permission was not granted");
        }
        return established;
    }

    private File writeNativeConfig() throws IOException {
        File config = new File(getCacheDir(), "ssh-vpn-tun.yml");
        String yaml = "tunnel:\n"
                + "  mtu: " + TunnelConfig.VPN_MTU + "\n"
                + "  ipv4: " + TunnelConfig.VPN_IPV4_ADDRESS + "\n"
                + "  ipv6: '" + TunnelConfig.VPN_IPV6_ADDRESS + "'\n"
                + "  icmp: 'reply'\n"
                + "socks5:\n"
                + "  address: '" + LOCAL_PROXY_HOST + "'\n"
                + "  port: " + TunnelConfig.LOCAL_PROXY_PORT + "\n"
                + "  udp: 'tcp'\n"
                + "mapdns:\n"
                + "  address: " + TunnelConfig.VPN_DNS_ADDRESS + "\n"
                + "  port: 53\n"
                + "  network: 240.0.0.0\n"
                + "  netmask: 240.0.0.0\n"
                + "  cache-size: 10000\n";
        try (FileOutputStream output = new FileOutputStream(config, false)) {
            output.write(yaml.getBytes(StandardCharsets.UTF_8));
        }
        return config;
    }

    private void waitForNativeStart() throws IOException, InterruptedException {
        for (int i = 0; i < 20; i++) {
            if (TProxyIsRunning()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IOException("Native TUN engine stopped during startup");
    }

    private void stopNativeTunnel() {
        try {
            if (TProxyIsRunning()) {
                TProxyStopService();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to stop native TUN engine", e);
        }
    }

    private void closeVpnInterface() {
        ParcelFileDescriptor current = tunInterface;
        tunInterface = null;
        closeQuietly(current);
    }

    private void disconnectSsh() {
        SshConnection current = sshConnection;
        sshConnection = null;
        if (current != null) {
            current.disconnect();
        }
    }

    private static final class SshConnection {
        final Session gatewaySession;
        final Session jumpSession;
        final JumpHostProxy jumpProxy;
        final String gatewayHost;
        final int gatewayPort;

        SshConnection(
                Session gatewaySession,
                Session jumpSession,
                JumpHostProxy jumpProxy,
                String gatewayHost,
                int gatewayPort) {
            this.gatewaySession = gatewaySession;
            this.jumpSession = jumpSession;
            this.jumpProxy = jumpProxy;
            this.gatewayHost = gatewayHost;
            this.gatewayPort = gatewayPort;
        }

        boolean isConnected() {
            return gatewaySession.isConnected()
                    && (jumpSession == null || jumpSession.isConnected());
        }

        boolean isGatewayReachable(int timeoutMs) {
            if (!isConnected()) {
                return false;
            }
            if (jumpSession == null) {
                return true;
            }

            ChannelDirectTCPIP channel = null;
            try {
                channel = (ChannelDirectTCPIP) jumpSession.openChannel("direct-tcpip");
                channel.setHost(gatewayHost);
                channel.setPort(gatewayPort);
                channel.setOrgIPAddress("127.0.0.1");
                channel.setOrgPort(0);
                channel.connect(timeoutMs);
                return true;
            } catch (Exception e) {
                return false;
            } finally {
                if (channel != null) {
                    channel.disconnect();
                }
            }
        }

        void disconnect() {
            gatewaySession.disconnect();
            if (jumpProxy != null) {
                jumpProxy.close();
            }
            if (jumpSession != null) {
                jumpSession.disconnect();
            }
        }
    }

    private void waitBeforeRetry() {
        try {
            waitOnReachabilitySignal(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void updateConnectionStatus(String status, int reachability) {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String previousStatus = prefs.getString(KEY_STATUS, null);
        int previousReachability = prefs.getInt(KEY_VPS_REACHABILITY, REACHABILITY_UNKNOWN);
        if (status.equals(previousStatus) && reachability == previousReachability) {
            if (running.get() && !foregroundStarted.get()) {
                showForegroundNotification(status);
            }
            return;
        }

        prefs.edit()
                .putString(KEY_STATUS, status)
                .putInt(KEY_VPS_REACHABILITY, reachability)
                .apply();
        if (running.get()) {
            showForegroundNotification(status);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(
                Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID,
                    "SSH VPN",
                    NotificationManager.IMPORTANCE_LOW));
        }
    }

    private void showForegroundNotification(String status) {
        Notification nextNotification = notification(status);
        if (foregroundStarted.compareAndSet(false, true)) {
            startForegroundNotification(nextNotification);
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(1, nextNotification);
        }
    }

    private void startForegroundNotification(Notification notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    1,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, notification);
        }
    }

    private Notification notification(String status) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        RemoteViews content = notificationContent(status);
        builder
                .setContentTitle(getString(R.string.app_name))
                .setContentText(status)
                .setSmallIcon(R.drawable.ic_notification_tunnel)
                .setContentIntent(contentIntent)
                .setOngoing(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder
                    .setCustomContentView(content)
                    .setCustomBigContentView(content)
                    .setStyle(new Notification.DecoratedCustomViewStyle());
        } else {
            // Notification custom views are unavailable before Android 7.
            builder.setContentText(status);
        }
        return builder.build();
    }

    private RemoteViews notificationContent(String status) {
        RemoteViews views = new RemoteViews(getPackageName(), R.layout.notification_tunnel);
        views.setTextViewText(R.id.notification_title, getString(R.string.app_name));
        views.setTextViewText(R.id.notification_status, status);
        views.setImageViewResource(R.id.notification_dot, notificationDotDrawable());
        return views;
    }

    private int notificationDotDrawable() {
        int state = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt(KEY_VPS_REACHABILITY, REACHABILITY_UNKNOWN);
        if (state == REACHABILITY_REACHABLE) {
            return R.drawable.status_dot_green;
        }
        if (state == REACHABILITY_DEGRADED) {
            return R.drawable.status_dot_yellow;
        }
        if (state == REACHABILITY_UNREACHABLE) {
            return R.drawable.status_dot_red;
        }
        return R.drawable.status_dot_gray;
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        try {
            descriptor.close();
        } catch (IOException ignored) {
            // Best effort cleanup.
        }
    }
}
