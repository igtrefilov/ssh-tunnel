package net.tref.sshtunnel;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Optional local-fixture test. Host ports are passed as instrumentation arguments. */
@RunWith(AndroidJUnit4.class)
public class TunnelLifecycleTest {
    @Test public void directAndJumpRecoverAcrossScreenModes() throws Exception {
        android.os.Bundle arguments = InstrumentationRegistry.getArguments();
        assumeTrue("Requires the loopback SSH fixture", arguments.containsKey("gatewayPort"));
        assertTrue("This test must only run on an emulator", Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("generic"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        int gateway = Integer.parseInt(arguments.getString("gatewayPort"));
        int jump = Integer.parseInt(arguments.getString("jumpPort"));
        int socks = Integer.parseInt(arguments.getString("socksPort"));
        int echo = Integer.parseInt(arguments.getString("echoPort"));
        String user = arguments.getString("sshUser");
        try {
            for (boolean nested : new boolean[]{false, true}) {
                shell("input keyevent KEYCODE_WAKEUP");
                shell("wm dismiss-keyguard");
                TunnelSettings.Values values = new TunnelSettings.Values(
                        Collections.singletonList(nested ? "127.0.0.1" : "10.0.2.2"), 0, user, gateway,
                        "127.0.0.1", socks, false, nested, "10.0.2.2", user, jump,
                        Collections.singleton(context.getPackageName()));
                TunnelSettings.saveValues(context, values);
                TunnelSettings.saveAllowedApplications(context, values.allowedApplications);
                assertNull("The fixture must grant ACTIVATE_VPN before preparing",
                        android.net.VpnService.prepare(context));
                context.startForegroundService(new Intent(context, TunnelService.class).setAction(TunnelService.ACTION_START));
                waitFor(context, TunnelService.STATUS_ONLINE, 30_000);
                verifyTraffic(echo);
                long activeCount = counter("diagnosticRounds");
                Thread.sleep(2200);
                assertTrue("Active screen must probe every second", counter("diagnosticRounds") >= activeCount + 2);
                shell("input keyevent KEYCODE_SLEEP");
                Thread.sleep(1500);
                long lockedCount = counter("diagnosticRounds");
                Thread.sleep(3500);
                assertEquals("No diagnostics while locked", lockedCount, counter("diagnosticRounds"));
                assertEquals(60_000, counter("keepaliveMs"));
                verifyTraffic(echo);
                // Kill only the disposable engine; VPN and SSH owner must recover while locked.
                String pid = shell("pidof " + context.getPackageName() + ":engine").trim();
                assertFalse(pid.isEmpty());
                shell("run-as " + context.getPackageName() + " kill -9 " + pid);
                waitFor(context, TunnelService.STATUS_TUNNEL_DOWN, 8000);
                waitFor(context, TunnelService.STATUS_ONLINE, 25_000);
                assertEquals(lockedCount, counter("diagnosticRounds"));
                verifyTraffic(echo);
                shell("cmd connectivity airplane-mode enable");
                waitFor(context, TunnelService.STATUS_TUNNEL_DOWN, 8000);
                long offlineAttempts = counter("connectionAttempts");
                Thread.sleep(2500);
                assertEquals("No connection attempts without a network", offlineAttempts, counter("connectionAttempts"));
                shell("cmd connectivity airplane-mode disable");
                waitFor(context, TunnelService.STATUS_ONLINE, 25_000);
                assertEquals("Network return must not enable locked diagnostics", lockedCount, counter("diagnosticRounds"));
                verifyTraffic(echo);
                shell("input keyevent KEYCODE_WAKEUP");
                shell("wm dismiss-keyguard");
                Thread.sleep(1200);
                assertTrue("Unlock must immediately resume diagnostics", counter("diagnosticRounds") > lockedCount);
                assertEquals(10_000, counter("keepaliveMs"));
                context.startService(new Intent(context, TunnelService.class).setAction(TunnelService.ACTION_STOP));
                waitFor(context, TunnelService.STATUS_STOPPED, 3000);
                Thread.sleep(1000);
            }
        } finally {
            context.startService(new Intent(context, TunnelService.class).setAction(TunnelService.ACTION_STOP));
            shell("cmd connectivity airplane-mode disable");
            shell("input keyevent KEYCODE_WAKEUP");
            shell("wm dismiss-keyguard");
        }
    }

    private static void verifyTraffic(int port) throws Exception {
        // Only the fixture SOCKS server accepts this destination and echoes it.
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("198.18.10.10", port), 5000);
            socket.setSoTimeout(5000);
            byte[] sent = "ssh-tunnel-vpn-check".getBytes(StandardCharsets.UTF_8);
            socket.getOutputStream().write(sent);
            byte[] received = new byte[sent.length];
            int offset = 0;
            while (offset < received.length) {
                int count = socket.getInputStream().read(received, offset, received.length - offset);
                if (count < 0) break;
                offset += count;
            }
            assertArrayEquals(sent, received);
        }
    }

    private static long counter(String name) throws Exception {
        String output = shell("dumpsys activity service net.tref.xraytunnel/net.tref.sshtunnel.TunnelService");
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.startsWith(name + "=")) return Long.parseLong(line.substring(name.length() + 1));
        }
        throw new AssertionError("Missing " + name + ": " + output);
    }

    private static void waitFor(Context context, String status, long timeout) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (status.equals(context.getSharedPreferences(TunnelSettings.PREFS, 0).getString(TunnelSettings.KEY_STATUS, ""))) return;
            Thread.sleep(100);
        }
        fail("Expected status " + status + "; actual=" + context.getSharedPreferences(TunnelSettings.PREFS, 0).getString(TunnelSettings.KEY_STATUS, ""));
    }

    private static String shell(String command) throws Exception {
        ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
        try (FileInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096];
            int count;
            while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
            return output.toString("UTF-8");
        }
    }
}
