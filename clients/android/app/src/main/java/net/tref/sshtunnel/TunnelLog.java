package net.tref.sshtunnel;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Two bounded private files; only transitions, never credentials or packet contents. */
final class TunnelLog {
    private static final String TAG = "StandaloneSshVpn";

    static synchronized void event(Context context, String event) {
        Log.i(TAG, event);
        File file = new File(context.getFilesDir(), "tunnel-events.log");
        try {
            if (file.length() > 256 * 1024) {
                File previous = new File(context.getFilesDir(), "tunnel-events.previous.log");
                if (previous.exists() && !previous.delete()) return;
                if (!file.renameTo(previous)) return;
            }
            // System time is for diagnosis; timers use monotonic clocks elsewhere.
            String line = System.currentTimeMillis() + " " + event + "\n";
            try (FileOutputStream output = new FileOutputStream(file, true)) {
                output.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "Cannot write tunnel event log", e);
        }
    }

    private TunnelLog() {}
}
