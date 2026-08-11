package net.tref.xraytunnel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        // A VPN permission can only be granted interactively.  Do not start a
        // reconnect loop during boot when the user has not completed setup.
        if (TunnelSettings.allowedApplications(context).isEmpty()
                || VpnService.prepare(context) != null) {
            return;
        }

        Intent service = new Intent(context, TunnelService.class)
                .setAction(TunnelService.ACTION_START);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(service);
        } else {
            context.startService(service);
        }
    }
}
