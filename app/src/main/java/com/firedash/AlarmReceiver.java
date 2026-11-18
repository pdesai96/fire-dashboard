package com.firedash;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.List;

/** The system alarm went off: show the ringing screen and set up the next one. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        int id = intent.getIntExtra(AlarmScheduler.EXTRA_ID, 0);
        String label = "";
        if (id == -1) {
            Prefs.setSnooze(c, 0);
            label = "Snoozed alarm";
        } else {
            List<Alarm> alarms = Prefs.alarms(c);
            for (Alarm a : alarms) {
                if (a.id != id) continue;
                label = a.label;
                if (a.days == 0) a.enabled = false; // one-off alarms switch themselves off
            }
            Prefs.saveAlarms(c, alarms);
        }
        AlarmScheduler.reschedule(c);
        Intent ring = new Intent(c, AlarmActivity.class);
        ring.putExtra("label", label);
        ring.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        c.startActivity(ring);
    }
}
