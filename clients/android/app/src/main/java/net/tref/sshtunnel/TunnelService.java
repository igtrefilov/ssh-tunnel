package net.tref.sshtunnel;

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
import java.util.Properties;
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
    public static final String ACTION_START = "net.tref.sshtunnel.START";
    public static final String ACTION_STOP = "net.tref.sshtunnel.STOP";
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
    private static final int SSH_CONNECT_TIMEOUT_MS = 15000;
    private static final int SSH_KEEPALIVE_COUNT_MAX = 3;
    private static final int ONLINE_ROUTE_FAILURE_THRESHOLD = 3;
    private static final long DIAGNOSTIC_PROBE_INTERVAL_MS = 1000;
    public static final String STATUS_TUNNEL_DOWN = "Tunnel Down";
    public static final String STATUS_VPS_DOWN = "VPS Down";
    public static final String STATUS_CHEBURNET = "Cheburnet";
    public static final String STATUS_OFFLINE = "Offline";
    public static final String STATUS_ONLINE = "Online";
    public static final String STATUS_STOPPED = "Stopped";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService monitor = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
    private final DiagnosticProber prober = new DiagnosticProber();
    private final TunnelPolicy policy = new TunnelPolicy();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean foregroundStarted = new AtomicBoolean();
    private final AtomicBoolean tunnelOnline = new AtomicBoolean();
    private final Object signal = new Object();
    private final java.util.Map<Network, Integer> networks = new java.util.HashMap<>();
    private volatile long generation;
    private volatile long diagnosticsGeneration;
    private volatile SshConnection sshConnection;
    private volatile IoScope connectionScope;
    private volatile NativeEngineClient engine;
    private volatile ParcelFileDescriptor tunInterface;
    private volatile TunnelProfile activeProfile;
    private volatile Network connectionNetwork;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private android.content.BroadcastReceiver screenReceiver;
    private volatile long connectionAttempts;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopTunnel();
            return START_NOT_STICKY;
        }
        startTunnel();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        stopTunnel();
        worker.shutdownNow();
        monitor.shutdownNow();
        deadlines.shutdownNow();
        prober.close();
        super.onDestroy();
    }

    @Override public void onRevoke() {
        stopTunnel();
        super.onRevoke();
    }

    private void startTunnel() {
        if (!running.compareAndSet(false, true)) return;
        long run = ++generation;
        createNotificationChannel();
        activeProfile = TunnelSettings.profiles(this)[0];
        updateConnectionStatus(STATUS_OFFLINE, REACHABILITY_UNKNOWN);
        registerScreenReceiver();
        registerNetworkCallback();
        policy.retryNow(android.os.SystemClock.elapsedRealtime());
        TunnelLog.event(this, "VPN started; mode=" + (policy.isInteractive() ? "active" : "locked"));
        monitor.execute(() -> runReachabilityMonitor(run));
        worker.execute(() -> runTunnelLoop(run));
    }

    private boolean isRunning(long run) { return running.get() && generation == run; }

    private void stopTunnel() {
        if (running.getAndSet(false)) {
            ++generation;
            invalidateDiagnostics();
            unregisterNetworkCallback();
            if (screenReceiver != null) {
                unregisterReceiver(screenReceiver);
                screenReceiver = null;
            }
            // Socket close cancels connects/reads. Native teardown is a one-way process stop,
            // never pthread_join or Session.disconnect on the Android main thread.
            IoScope scope = connectionScope;
            if (scope != null) scope.close();
            NativeEngineClient current = engine;
            if (current != null) current.close();
            closeVpnInterface();
            tunnelOnline.set(false);
            wakeWorkers();
            TunnelLog.event(this, "VPN stopped");
        }
        updateConnectionStatus(STATUS_STOPPED, REACHABILITY_UNKNOWN);
        stopForeground(true);
        foregroundStarted.set(false);
        stopSelf();
    }

    private void runTunnelLoop(long run) {
        ParcelFileDescriptor vpn = null;
        File config = null;
        try {
            while (isRunning(run)) {
                waitUntilRetry(run);
                if (!isRunning(run)) break;
                long attemptRevision = policy.revision();
                long onlineSince = 0;
                IoScope scope = new IoScope();
                connectionScope = scope;
                SshConnection next = null;
                NativeEngineClient nextEngine = null;
                try {
                    TunnelProfile profile = activeProfile;
                    if (profile.allowedApplications.isEmpty()) throw new IOException("Select at least one application");
                    connectionNetwork = connectivityManager == null ? null
                            : UnderlyingNetworkSocketFactory.preferredUnderlyingNetwork(connectivityManager);
                    TunnelLog.event(this, "SSH attempt; mode=" + (policy.isInteractive() ? "active" : "locked"));
                    connectionAttempts++;
                    // A total deadline also covers nested SSH handshakes over a jump channel.
                    java.util.concurrent.ScheduledFuture<?> timeout = deadlines.schedule(scope::close,
                            profile.jumpEnabled ? 35 : 20, TimeUnit.SECONDS);
                    try { next = connectSsh(profile, scope); }
                    finally { timeout.cancel(false); }
                    scope.check();
                    if (!isRunning(run)) break;
                    next.gatewaySession.setPortForwardingL(LOCAL_PROXY_HOST, TunnelConfig.LOCAL_PROXY_PORT,
                            profile.proxyHost, profile.proxyPort);
                    sshConnection = next;
                    if (vpn == null) {
                        vpn = establishVpn(profile);
                        tunInterface = vpn;
                        config = writeNativeConfig();
                    }
                    nextEngine = new NativeEngineClient(this);
                    engine = nextEngine;
                    nextEngine.start(config.getAbsolutePath(), vpn);
                    scope.check();
                    if (!isRunning(run)) break;
                    next.setKeepalive(policy.keepaliveMs());
                    tunnelOnline.set(true);
                    onlineSince = android.os.SystemClock.elapsedRealtime();
                    updateConnectionStatus(STATUS_ONLINE, REACHABILITY_REACHABLE);
                    TunnelLog.event(this, "Tunnel online");
                    invalidateDiagnostics();
                    while (isRunning(run) && !scope.isClosed() && next.isConnected() && nextEngine.isAlive()) {
                        next.setKeepalive(policy.keepaliveMs());
                        synchronized (signal) { signal.wait(policy.isInteractive() ? 250 : 5000); }
                    }
                    if (isRunning(run)) throw new IOException("SSH transport or native engine stopped");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    if (isRunning(run)) {
                        Log.w(TAG, "Tunnel cycle failed", e);
                        TunnelLog.event(this, "Tunnel cycle failed: " + e.getClass().getSimpleName());
                    }
                } finally {
                    tunnelOnline.set(false);
                    scope.close();
                    if (nextEngine != null) nextEngine.close();
                    if (next != null) next.disconnect();
                    if (sshConnection == next) sshConnection = null;
                    if (engine == nextEngine) engine = null;
                    if (connectionScope == scope) connectionScope = null;
                    if (isRunning(run)) {
                        updateConnectionStatus(STATUS_TUNNEL_DOWN, REACHABILITY_UNREACHABLE);
                        invalidateDiagnostics();
                    }
                }
                if (isRunning(run)) {
                    // Preserve backoff for a repeatedly crashing engine; reset only after stable service.
                    if (onlineSince != 0 && android.os.SystemClock.elapsedRealtime() - onlineSince >= 60_000) policy.connected();
                    policy.failed(android.os.SystemClock.elapsedRealtime(), attemptRevision);
                    TunnelLog.event(this, "Retry delay ms=" + policy.retryDelay(android.os.SystemClock.elapsedRealtime()));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeQuietly(vpn);
            if (tunInterface == vpn) tunInterface = null;
            if (config != null) config.delete();
        }
    }

    private void waitUntilRetry(long run) throws InterruptedException {
        synchronized (signal) {
            while (isRunning(run)) {
                long delay = policy.retryDelay(android.os.SystemClock.elapsedRealtime());
                if (delay == 0) return;
                signal.wait(delay == Long.MAX_VALUE ? 0 : delay);
            }
        }
    }

    private SshConnection connectSsh(TunnelProfile profile, IoScope scope) throws Exception {
        Session jump = null;
        JumpHostProxy proxy = null;
        Session gateway = null;
        try {
            if (profile.jumpEnabled) {
                JSch jsch = configuredJsch(profile.jumpPrivateKeyAsset, profile.jumpHost + "-jump", profile.verifyHostKey);
                jump = jsch.getSession(profile.jumpUser, profile.jumpHost, profile.jumpPort);
                configureSession(jump, profile.verifyHostKey);
                jump.setSocketFactory(new UnderlyingNetworkSocketFactory(this, SSH_CONNECT_TIMEOUT_MS, true, scope));
                jump.connect(SSH_CONNECT_TIMEOUT_MS);
                scope.check();
                proxy = new JumpHostProxy(jump);
            }
            JSch jsch = configuredJsch(profile.privateKeyAsset, profile.sshHost + "-gateway", profile.verifyHostKey);
            gateway = jsch.getSession(profile.sshUser, profile.sshHost, profile.sshPort);
            configureSession(gateway, profile.verifyHostKey);
            if (proxy == null) gateway.setSocketFactory(new UnderlyingNetworkSocketFactory(this, SSH_CONNECT_TIMEOUT_MS, true, scope));
            else gateway.setProxy(proxy);
            gateway.connect(SSH_CONNECT_TIMEOUT_MS);
            scope.check();
            return new SshConnection(gateway, jump, proxy, profile.proxyHost, profile.proxyPort);
        } catch (Exception e) {
            scope.close();
            if (gateway != null) gateway.disconnect();
            if (proxy != null) proxy.close();
            if (jump != null) jump.disconnect();
            throw e;
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
        next.setServerAliveInterval(policy.keepaliveMs());
        next.setServerAliveCountMax(SSH_KEEPALIVE_COUNT_MAX);
    }

    private boolean interactiveAndUnlocked() {
        android.os.PowerManager power = getSystemService(android.os.PowerManager.class);
        android.app.KeyguardManager keyguard = getSystemService(android.app.KeyguardManager.class);
        return power != null && power.isInteractive() && keyguard != null && !keyguard.isKeyguardLocked();
    }

    private void registerScreenReceiver() {
        screenReceiver = new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { refreshScreenMode(); }
        };
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenReceiver, filter);
        refreshScreenMode();
    }

    private void refreshScreenMode() {
        boolean active = interactiveAndUnlocked();
        if (active == policy.isInteractive()) return;
        policy.setInteractive(active, android.os.SystemClock.elapsedRealtime());
        invalidateDiagnostics();
        TunnelLog.event(this, "Screen mode=" + (active ? "active" : "locked")
                + "; diagnostic rounds=" + prober.roundsStarted());
    }

    private void invalidateDiagnostics() {
        synchronized (signal) {
            diagnosticsGeneration++;
            prober.cancel();
            signal.notifyAll();
        }
    }

    private void wakeWorkers() { synchronized (signal) { signal.notifyAll(); } }

    private void runReachabilityMonitor(long run) {
        int failures = 0;
        long previousGeneration = -1;
        try {
            while (isRunning(run)) {
                long probeGeneration;
                synchronized (signal) {
                    while (isRunning(run) && !policy.isInteractive()) signal.wait();
                    if (!isRunning(run)) return;
                    probeGeneration = diagnosticsGeneration;
                }
                // Also catches locking without screen-off on vendor devices.
                if (!interactiveAndUnlocked()) { refreshScreenMode(); continue; }
                if (probeGeneration != previousGeneration) failures = 0;
                previousGeneration = probeGeneration;
                TunnelProfile profile = activeProfile;
                SshConnection connection = sshConnection;
                boolean online = tunnelOnline.get();
                long started = android.os.SystemClock.elapsedRealtime();
                DiagnosticProber.Result result = prober.probe(this, scope -> {
                    if (online) return connection != null && connection.isGatewayReachable(scope);
                    return DiagnosticProber.reachable(this,
                            profile.jumpEnabled ? profile.jumpHost : profile.sshHost,
                            profile.jumpEnabled ? profile.jumpPort : profile.sshPort, scope);
                }, online, () -> isRunning(run) && policy.isInteractive() && diagnosticsGeneration == probeGeneration);
                synchronized (signal) {
                    if (!isRunning(run) || !policy.isInteractive() || diagnosticsGeneration != probeGeneration) continue;
                    if (!interactiveAndUnlocked()) { refreshScreenMode(); continue; }
                    if (result != null) {
                        if (online && tunnelOnline.get() && connection == sshConnection) {
                            failures = result.route ? 0 : failures + 1;
                            if (failures >= ONLINE_ROUTE_FAILURE_THRESHOLD) {
                                TunnelLog.event(this, "Active route probe failed three times; reconnecting");
                                IoScope scope = connectionScope;
                                if (scope != null) scope.close();
                                failures = 0;
                            }
                        } else if (!tunnelOnline.get()) {
                            updateDiagnosticStatus(result);
                        }
                    }
                    // Cadence is measured from round start. Callback noise cannot shorten it.
                    long due = Math.max(started + DIAGNOSTIC_PROBE_INTERVAL_MS,
                            android.os.SystemClock.elapsedRealtime() + 50);
                    while (isRunning(run) && policy.isInteractive() && diagnosticsGeneration == probeGeneration) {
                        long remaining = due - android.os.SystemClock.elapsedRealtime();
                        if (remaining <= 0) break;
                        signal.wait(remaining);
                    }
                }
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void updateDiagnosticStatus(DiagnosticProber.Result result) {
        if (result.route) updateConnectionStatus(STATUS_TUNNEL_DOWN, REACHABILITY_UNREACHABLE);
        else if (result.ya && result.google) updateConnectionStatus(STATUS_VPS_DOWN, REACHABILITY_UNREACHABLE);
        else if (result.ya != result.google) updateConnectionStatus(STATUS_CHEBURNET, REACHABILITY_DEGRADED);
        else updateConnectionStatus(STATUS_OFFLINE, REACHABILITY_UNKNOWN);
    }

    private static int capabilityState(NetworkCapabilities caps) {
        if (caps == null) return 0;
        int value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? 1 : 0;
        if (Build.VERSION.SDK_INT >= 28 && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)) value |= 2;
        return value;
    }

    private void registerNetworkCallback() {
        connectivityManager = getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) { policy.setNetworkAvailable(true); return; }
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).build();
        for (Network network : connectivityManager.getAllNetworks()) {
            NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
            if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) networks.put(network, capabilityState(caps));
        }
        policy.setNetworkAvailable(!networks.isEmpty());
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                synchronized (signal) {
                    if (!running.get()) return;
                    if (!networks.containsKey(network)) {
                        networks.put(network, -1);
                        policy.setNetworkAvailable(true);
                        networkRecovered("network available", network);
                    }
                }
            }
            @Override public void onLost(Network network) {
                synchronized (signal) {
                    if (!running.get()) return;
                    networks.remove(network);
                    policy.setNetworkAvailable(!networks.isEmpty());
                    if (network.equals(connectionNetwork) || networks.isEmpty()) {
                        IoScope scope = connectionScope;
                        if (scope != null) scope.close();
                        if (!networks.isEmpty()) policy.retryNow(android.os.SystemClock.elapsedRealtime());
                    }
                    TunnelLog.event(TunnelService.this, "Underlying network lost; available=" + !networks.isEmpty());
                    invalidateDiagnostics();
                }
            }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                synchronized (signal) {
                    if (!running.get()) return;
                    Integer previous = networks.put(network, capabilityState(caps));
                    policy.setNetworkAvailable(true);
                    // Ignore signal strength/bandwidth churn and the initial onAvailable pair.
                    if (previous != null && previous >= 0 && (capabilityState(caps) & ~previous) != 0) {
                        networkRecovered("network resumed or validated", network);
                    }
                }
            }
        };
        try { connectivityManager.registerNetworkCallback(request, networkCallback); }
        catch (RuntimeException e) {
            networkCallback = null;
            policy.setNetworkAvailable(true); // Retain timed retries if vendor callbacks are unavailable.
            Log.w(TAG, "Unable to watch underlying networks", e);
        }
    }

    private void networkRecovered(String reason, Network network) {
        policy.retryNow(android.os.SystemClock.elapsedRealtime());
        if (!tunnelOnline.get() && !network.equals(connectionNetwork)) {
            IoScope scope = connectionScope;
            if (scope != null) scope.close();
        }
        TunnelLog.event(this, reason + "; immediate recovery allowed");
        invalidateDiagnostics();
    }

    private void unregisterNetworkCallback() {
        if (connectivityManager != null && networkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); }
            catch (RuntimeException ignored) { }
        }
        networkCallback = null;
        synchronized (signal) { networks.clear(); }
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

    private void closeVpnInterface() {
        ParcelFileDescriptor current = tunInterface;
        tunInterface = null;
        closeQuietly(current);
    }

    private static final class SshConnection {
        final Session gatewaySession;
        final Session jumpSession;
        final JumpHostProxy jumpProxy;
        final String proxyHost;
        final int proxyPort;

        SshConnection(
                Session gatewaySession,
                Session jumpSession,
                JumpHostProxy jumpProxy,
                String proxyHost,
                int proxyPort) {
            this.gatewaySession = gatewaySession;
            this.jumpSession = jumpSession;
            this.jumpProxy = jumpProxy;
            this.proxyHost = proxyHost;
            this.proxyPort = proxyPort;
        }

        boolean isConnected() {
            return gatewaySession.isConnected()
                    && (jumpSession == null || jumpSession.isConnected());
        }

        int keepalive;

        void setKeepalive(int interval) throws Exception {
            if (keepalive == interval) return;
            gatewaySession.setServerAliveInterval(interval);
            if (jumpSession != null) jumpSession.setServerAliveInterval(interval);
            keepalive = interval;
        }

        boolean isGatewayReachable(IoScope scope) {
            if (!isConnected()) {
                return false;
            }
            // A reply from the live gateway session verifies both SSH hops and
            // the SOCKS listener, including a stale TCP session on an otherwise live route.
            ChannelDirectTCPIP channel = null;
            try {
                channel = (ChannelDirectTCPIP) gatewaySession.openChannel("direct-tcpip");
                channel.setHost(proxyHost);
                channel.setPort(proxyPort);
                channel.setOrgIPAddress("127.0.0.1");
                channel.setOrgPort(0);
                scope.check();
                channel.connect(DiagnosticProber.DEADLINE_MS);
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

        TunnelLog.event(this, "Status=" + status);

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

    @Override protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter writer, String[] args) {
        writer.println("running=" + running.get());
        writer.println("interactiveUnlocked=" + policy.isInteractive());
        writer.println("tunnelOnline=" + tunnelOnline.get());
        writer.println("diagnosticRounds=" + prober.roundsStarted());
        writer.println("connectionAttempts=" + connectionAttempts);
        writer.println("keepaliveMs=" + policy.keepaliveMs());
        writer.println("retryDelayMs=" + policy.retryDelay(android.os.SystemClock.elapsedRealtime()));
    }
}
