package net.tref.xraytunnel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private static native boolean TProxyStartService(String configPath, int fd);
    private static native boolean TProxyStopService();
    private static native boolean TProxyIsRunning();
    private static native long[] TProxyGetStats();

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile SshConnection sshConnection;
    private volatile ParcelFileDescriptor tunInterface;
    private volatile File nativeConfig;

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
        updateStatus("Starting", REACHABILITY_UNKNOWN);
        startForegroundNotification("Starting");
        worker.execute(this::runTunnelLoop);
    }

    private void stopTunnel() {
        if (!running.getAndSet(false)) {
            return;
        }
        stopNativeTunnel();
        closeVpnInterface();
        disconnectSsh();
        updateStatus("Stopped", REACHABILITY_UNKNOWN);
        stopForeground(true);
        stopSelf();
    }

    private void runTunnelLoop() {
        while (running.get()) {
            SshConnection nextConnection = null;
            ParcelFileDescriptor nextTun = null;
            File nextConfig = null;
            int forwardedPort = -1;
            try {
                TunnelProfile profile = TunnelSettings.profiles(this)[0];
                if (profile.allowedApplications.isEmpty()) {
                    throw new IllegalStateException("Select at least one application");
                }

                updateStatus("Connecting SSH", REACHABILITY_UNKNOWN);
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
                updateStatus("Online", REACHABILITY_REACHABLE);
                startForegroundNotification("Online");

                while (running.get()
                        && nextConnection.isConnected()
                        && TProxyIsRunning()) {
                    Thread.sleep(500);
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
                    updateStatus("Reconnecting", REACHABILITY_UNREACHABLE);
                }
            } finally {
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
            }
            if (running.get()) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private SshConnection connectSsh(TunnelProfile profile) throws Exception {
        Session jumpSession = null;
        JumpHostProxy jumpProxy = null;
        Session gatewaySession = null;
        try {
            if (profile.jumpEnabled) {
                updateStatus("Connecting jump host", REACHABILITY_UNKNOWN);
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

            updateStatus("Connecting gateway", REACHABILITY_UNKNOWN);
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
            return new SshConnection(gatewaySession, jumpSession, jumpProxy);
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

        SshConnection(
                Session gatewaySession,
                Session jumpSession,
                JumpHostProxy jumpProxy) {
            this.gatewaySession = gatewaySession;
            this.jumpSession = jumpSession;
            this.jumpProxy = jumpProxy;
        }

        boolean isConnected() {
            return gatewaySession.isConnected()
                    && (jumpSession == null || jumpSession.isConnected());
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

    private void updateStatus(String status, int reachability) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_STATUS, status)
                .putInt(KEY_VPS_REACHABILITY, reachability)
                .apply();
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

    private void startForegroundNotification(String status) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(status)
                .setSmallIcon(R.drawable.ic_notification_tunnel)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    1,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, notification);
        }
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
