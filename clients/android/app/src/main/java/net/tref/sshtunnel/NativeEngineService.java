package net.tref.sshtunnel;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

/** A disposable private process. Never joins a possibly stuck native thread. */
public final class NativeEngineService extends Service {
    static final int START = 1;
    static final int STOP = 2;
    static final int HEALTH = 3;
    static final int TEST_STALL = 4;
    static final int EXIT = 5;
    static final long STALL_TIMEOUT_MS = 15_000;
    private final Handler handler = new Handler(Looper.getMainLooper(), this::receive);
    private final Messenger messenger = new Messenger(handler);
    private Messenger owner;
    private IBinder.DeathRecipient ownerDeath;
    private ParcelFileDescriptor tun;
    private long started;
    private boolean launched;

    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }

    private boolean receive(Message message) {
        if (message.what == STOP) {
            terminate("requested");
        } else if (message.what == TEST_STALL && BuildConfig.DEBUG && launched) {
            NativeEngine.simulateStall();
        } else if (message.what == START && !launched) {
            launched = true;
            owner = message.replyTo;
            message.getData().setClassLoader(ParcelFileDescriptor.class.getClassLoader());
            tun = message.getData().getParcelable("tun");
            String config = message.getData().getString("config");
            if (owner == null || tun == null || config == null) {
                terminate("invalid start");
                return true;
            }
            ownerDeath = () -> terminate("VPN process died");
            try { owner.getBinder().linkToDeath(ownerDeath, 0); }
            catch (RemoteException e) { terminate("VPN process absent"); return true; }
            started = SystemClock.uptimeMillis();
            // Send the PID before loading native code, so startup is also bounded by the owner.
            report(0);
            new Thread(() -> {
                try { NativeEngine.run(config, tun.getFd()); }
                finally { terminate("native engine exited"); }
            }, "tun-engine").start();
            handler.postDelayed(this::checkHealth, 250);
        }
        return true;
    }

    private void checkHealth() {
        long heartbeat = NativeEngine.heartbeat();
        // CLOCK_MONOTONIC and uptimeMillis both exclude suspend: sleep is not a hang.
        if (SystemClock.uptimeMillis() - Math.max(started, heartbeat) > STALL_TIMEOUT_MS) {
            Log.e("StandaloneSshVpn", "Native event loop stalled; terminating engine process");
            terminate("native event loop stalled");
            return;
        }
        report(heartbeat);
        handler.postDelayed(this::checkHealth, heartbeat == 0 ? 250 : 5000);
    }

    private void report(long heartbeat) {
        Message message = Message.obtain(null, HEALTH, Process.myPid(), 0);
        message.getData().putLong("heartbeat", heartbeat);
        try { owner.send(message); }
        catch (RemoteException e) { terminate("VPN process unreachable"); }
    }

    private void terminate(String reason) {
        Log.i("StandaloneSshVpn", "Engine exit: " + reason);
        if (owner != null) {
            Message message = Message.obtain(null, EXIT);
            message.getData().putString("reason", reason);
            try { owner.send(message); } catch (RemoteException ignored) { }
        }
        Process.killProcess(Process.myPid());
    }

    @Override public boolean onUnbind(Intent intent) {
        terminate("VPN unbound");
        return false;
    }

    @Override public void onDestroy() {
        terminate("service destroyed");
        super.onDestroy();
    }
}
