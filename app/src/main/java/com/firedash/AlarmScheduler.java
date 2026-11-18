package com.firedash;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.List;
import java.util.TimeZone;

/** Keeps exactly one system alarm set: the next of the user's alarms (or a snooze). */
final class AlarmScheduler {
    private AlarmScheduler() {}

    static final String EXTRA_ID = "alarmId";

    static void reschedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long now = System.currentTimeMillis();
        List<Alarm> alarms = Prefs.alarms(c);
        Alarm a = Alarm.soonest(alarms, now, TimeZone.getDefault());
        long when = a != null ? a.next(now, TimeZone.getDefault()) : Long.MAX_VALUE;
        int id = a != null ? a.id : 0;
        long snooze = Prefs.snoozeUntil(c);
        if (snooze > now && snooze < when) { when = snooze; id = -1; }

        Intent fire = new Intent(c, AlarmReceiver.class);
        fire.putExtra(EXTRA_ID, id);
        PendingIntent op = PendingIntent.getBroadcast(c, 1, fire, flags(PendingIntent.FLAG_UPDATE_CURRENT));
        if (when == Long.MAX_VALUE) {
            am.cancel(op);
            return;
        }
        PendingIntent show = PendingIntent.getActivity(c, 2, new Intent(c, DashboardActivity.class), flags(PendingIntent.FLAG_UPDATE_CURRENT));
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(when, show), op);
    }

    static int flags(int f) {
        return Build.VERSION.SDK_INT >= 23 ? f | PendingIntent.FLAG_IMMUTABLE : f;
    }
}
