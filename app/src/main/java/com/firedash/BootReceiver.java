package com.firedash;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** When the tablet starts: sets the alarms again and opens the dashboard (if that setting is on). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        AlarmScheduler.reschedule(context); // system alarms don't survive a restart
        if (!Prefs.load(context).startOnBoot) return;
        Intent open = new Intent(context, DashboardActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(open);
    }
}
