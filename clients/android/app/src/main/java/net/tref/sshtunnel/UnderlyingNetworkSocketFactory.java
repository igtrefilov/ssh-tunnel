package net.tref.sshtunnel;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.util.Log;

import com.jcraft.jsch.SocketFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

final class UnderlyingNetworkSocketFactory implements SocketFactory {
    private static final String TAG = "StandaloneSshVpn";
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 15000;

    private final Context context;
    private final VpnService vpnService;
    private final int connectTimeoutMs;
    private final boolean logFailures;
    private final IoScope scope;

    UnderlyingNetworkSocketFactory(Context context) {
        this(context, DEFAULT_CONNECT_TIMEOUT_MS, true);
    }

    UnderlyingNetworkSocketFactory(Context context, int connectTimeoutMs, boolean logFailures) {
        this(context, connectTimeoutMs, logFailures, new IoScope());
    }

    UnderlyingNetworkSocketFactory(Context context, int connectTimeoutMs, boolean logFailures, IoScope scope) {
        this.scope = scope;
        this.context = context.getApplicationContext();
        this.vpnService = context instanceof VpnService ? (VpnService) context : null;
        this.connectTimeoutMs = connectTimeoutMs;
        this.logFailures = logFailures;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        long deadline = android.os.SystemClock.elapsedRealtime() + connectTimeoutMs;
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        List<Network> networks = manager == null ? new ArrayList<>() : orderedUnderlyingNetworks(manager);
        Network preferred = networks.isEmpty() ? null : networks.get(0);
        IOException failure = null;
        // Default routing first retains compatibility with vendor ROMs which reject bindSocket.
        for (int i = -1; i < networks.size(); i++) {
            scope.check();
            Network network = i < 0 ? preferred : networks.get(i);
            Socket socket = null;
            try {
                java.net.InetAddress[] addresses = BoundedDns.resolve(network, host, deadline, scope);
                for (int addressIndex = 0; addressIndex < addresses.length; addressIndex++) {
                    scope.check();
                    int remaining = remaining(deadline);
                    try {
                        socket = scope.add(new Socket());
                        // Materialize the file descriptor before VpnService.protect on
                        // Android versions whose Socket constructor creates it lazily.
                        socket.bind(new InetSocketAddress(0));
                        protect(socket);
                        if (i >= 0) network.bindSocket(socket);
                        int slice = Math.max(1, Math.min(5000, remaining / (addresses.length - addressIndex)));
                        socket.connect(new InetSocketAddress(addresses[addressIndex], port), slice);
                        return socket;
                    } catch (IOException e) {
                        closeQuietly(socket);
                        failure = e;
                    }
                }

            } catch (IOException e) {
                closeQuietly(socket);
                failure = e;
                if (scope.isClosed() || android.os.SystemClock.elapsedRealtime() >= deadline) break;
            }
        }
        if (logFailures && failure != null) Log.w(TAG, "SSH socket connection failed: " + failure.getClass().getSimpleName());
        throw failure != null ? failure : new IOException("No usable non-VPN network");
    }

    static int remaining(long deadline) throws java.net.SocketTimeoutException {
        long remaining = deadline - android.os.SystemClock.elapsedRealtime();
        if (remaining <= 0) throw new java.net.SocketTimeoutException("Connection deadline expired");
        return (int) Math.min(Integer.MAX_VALUE, remaining);
    }

    private void protect(Socket socket) throws IOException {
        if (vpnService != null && !vpnService.protect(socket)) {
            throw new IOException("Android VPN refused to protect SSH socket");
        }
    }

    @Override
    public InputStream getInputStream(Socket socket) throws IOException {
        return socket.getInputStream();
    }

    @Override
    public OutputStream getOutputStream(Socket socket) throws IOException {
        return socket.getOutputStream();
    }

    static Network preferredUnderlyingNetwork(ConnectivityManager manager) {
        List<Network> networks = orderedUnderlyingNetworks(manager);
        return networks.isEmpty() ? null : networks.get(0);
    }

    static String describeNetwork(ConnectivityManager manager, Network network) {
        return describeNetwork(manager.getNetworkCapabilities(network), network);
    }

    private static List<Network> orderedUnderlyingNetworks(ConnectivityManager manager) {
        ArrayList<Network> networks = new ArrayList<>();
        addNetworks(manager, networks, true, NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_ETHERNET);
        addNetworks(manager, networks, true, NetworkCapabilities.TRANSPORT_CELLULAR);
        addNetworks(manager, networks, false, NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_ETHERNET);
        addNetworks(manager, networks, false, NetworkCapabilities.TRANSPORT_CELLULAR);
        return networks;
    }

    private static void addNetworks(
            ConnectivityManager manager,
            List<Network> output,
            boolean requireValidated,
            int... transports) {
        for (Network network : manager.getAllNetworks()) {
            if (output.contains(network)) {
                continue;
            }
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (!isUsableNetwork(caps, requireValidated)) {
                continue;
            }
            for (int transport : transports) {
                if (caps.hasTransport(transport)) {
                    output.add(network);
                    break;
                }
            }
        }
    }

    private static boolean isUsableNetwork(NetworkCapabilities caps, boolean requireValidated) {
        if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return false;
        }
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return false;
        }
        return !requireValidated || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private static boolean hasUnderlyingTransport(NetworkCapabilities caps) {
        return caps != null
                && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
    }

    private static String describeNetwork(NetworkCapabilities caps, Network network) {
        if (caps == null) {
            return "network " + network;
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return "Wi-Fi network " + network;
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return "Ethernet network " + network;
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return "cellular network " + network;
        }
        return "network " + network;
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Best effort cleanup.
        }
    }
}
