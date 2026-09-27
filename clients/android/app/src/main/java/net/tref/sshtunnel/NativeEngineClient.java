package net.tref.sshtunnel;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Asynchronous IPC; only the tunnel worker waits for startup, never the UI thread. */
final class NativeEngineClient implements Closeable {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CountDownLatch ready = new CountDownLatch(1);
    private final CountDownLatch died = new CountDownLatch(1);
    private final Messenger replies = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private volatile Messenger remote;
    private volatile boolean closed;
    private volatile int pid;
    private volatile long heartbeat;
    private boolean bound;
    private ServiceConnection connection;

    NativeEngineClient(Context context) { this.context = context; }

    void start(String config, ParcelFileDescriptor tun) throws IOException, InterruptedException {
        main.post(() -> {
            if (closed) { ready.countDown(); return; }
            connection = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                    if (closed || died.getCount() == 0) { unbind(); return; }
                    remote = new Messenger(binder);
                    try {
                        binder.linkToDeath(NativeEngineClient.this::engineDied, 0);
                        if (closed) return;
                        Message message = Message.obtain(null, NativeEngineService.START);
                        message.replyTo = replies;
                        message.getData().putString("config", config);
                        message.getData().putParcelable("tun", tun);
                        remote.send(message);
                    } catch (RemoteException | RuntimeException e) { ready.countDown(); }
                }
                @Override public void onServiceDisconnected(ComponentName name) {
                    engineDied();
                }
                @Override public void onNullBinding(ComponentName name) { ready.countDown(); }
                @Override public void onBindingDied(ComponentName name) { engineDied(); }
            };
            try {
                bound = context.bindService(new Intent(context, NativeEngineService.class),
                        connection, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
                if (!bound) ready.countDown();
            } catch (RuntimeException e) { ready.countDown(); }
        });
        if (!ready.await(10, TimeUnit.SECONDS) || !isAlive()) {
            close();
            throw new IOException("Native engine did not become ready");
        }
    }

    private boolean receive(Message message) {
        if (message.what == NativeEngineService.EXIT) {
            TunnelLog.event(context, "Engine exit: " + message.getData().getString("reason"));
        }
        if (message.what == NativeEngineService.HEALTH) {
            pid = message.arg1;
            heartbeat = message.getData().getLong("heartbeat");
            if (closed) kill();
            else if (heartbeat > 0) ready.countDown();
        }
        return true;
    }

    boolean isAlive() {
        Messenger current = remote;
        return !closed && heartbeat > 0 && died.getCount() != 0
                && current != null && current.getBinder().isBinderAlive()
                && SystemClock.uptimeMillis() - heartbeat <= NativeEngineService.STALL_TIMEOUT_MS + 10_000;
    }

    void simulateStallForTest() { if (BuildConfig.DEBUG) send(NativeEngineService.TEST_STALL); }

    private void engineDied() {
        died.countDown();
        ready.countDown();
        // Recovery belongs to TunnelPolicy. Do not let BIND_AUTO_CREATE bypass its backoff.
        main.post(this::unbind);
    }

    private void unbind() {
        if (bound) {
            context.unbindService(connection);
            bound = false;
        }
    }

    private void send(int command) {
        Messenger current = remote;
        if (current != null) {
            try { current.send(Message.obtain(null, command)); } catch (RemoteException ignored) { }
        }
    }

    private void kill() {
        Messenger current = remote;
        if (pid > 0 && pid != Process.myPid() && current != null && current.getBinder().isBinderAlive()) {
            Process.killProcess(pid);
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        ready.countDown();
        send(NativeEngineService.STOP);
        kill();
        main.post(this::unbind);
    }
}
