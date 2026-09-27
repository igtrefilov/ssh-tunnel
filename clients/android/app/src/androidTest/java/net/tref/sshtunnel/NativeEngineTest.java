package net.tref.sshtunnel;

import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Runs only on an explicitly selected emulator/test device, never as part of assemble. */
@RunWith(AndroidJUnit4.class)
public class NativeEngineTest {
    @Test public void testStalledEventLoopIsKilledAndFreshEngineStarts() throws Exception {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File config = new File(context.getCacheDir(), "engine-test.yml");
        try (FileOutputStream output = new FileOutputStream(config)) {
            output.write(("tunnel:\n  mtu: 1500\n  ipv4: 198.18.0.1\n  ipv6: 'fc00::1'\n"
                    + "socks5:\n  address: '127.0.0.1'\n  port: 18080\n  udp: 'tcp'\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createSocketPair();
        try {
            NativeEngineClient first = new NativeEngineClient(context);
            try {
                first.start(config.getAbsolutePath(), pipe[0]);
                assertTrue(first.isAlive());
                first.simulateStallForTest();
                long deadline = SystemClock.uptimeMillis() + 30_000;
                while (first.isAlive() && SystemClock.uptimeMillis() < deadline) Thread.sleep(100);
                assertFalse("Watchdog must terminate a spinning native scheduler", first.isAlive());
            } finally { first.close(); }
            // The VPN's original descriptor survives child process death.
            assertTrue(pipe[0].getFileDescriptor().valid());
            NativeEngineClient second = new NativeEngineClient(context);
            try {
                second.start(config.getAbsolutePath(), pipe[0]);
                assertTrue(second.isAlive());
                Thread.sleep(18_000);
                assertTrue("An idle engine must keep proving scheduler progress", second.isAlive());
                long started = SystemClock.uptimeMillis();
                second.close();
                assertTrue("Shutdown must not join the native thread", SystemClock.uptimeMillis() - started < 1000);
            } finally { second.close(); }
        } finally {
            pipe[0].close();
            pipe[1].close();
            config.delete();
        }
    }
}
