package com.firedash;

import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.DialogInterface;
import android.widget.EditText;
import android.widget.TimePicker;

import java.util.Calendar;
import java.util.List;

/** Tap the clock: list, add, switch off and delete alarms. */
final class AlarmsDialog {
    private AlarmsDialog() {}

    private static final int THEME = android.R.style.Theme_Material_Light_Dialog_Alert;

    static void show(final DashboardActivity a) {
        final List<Alarm> alarms = Prefs.alarms(a);
        final String[] items = new String[alarms.size() + 1];
        for (int i = 0; i < alarms.size(); i++) {
            Alarm x = alarms.get(i);
            String[] t = Wx.clock(x.hour, x.minute, a.cfg.clock24h);
            items[i] = t[0] + (t[1] != null ? " " + t[1] : "") + "  ·  " + x.daysLabel()
                    + (x.label.isEmpty() ? "" : "  ·  " + x.label) + (x.enabled ? "" : "   (off)");
        }
        items[alarms.size()] = "+  Add an alarm";
        new AlertDialog.Builder(a, THEME)
                .setTitle("Alarms")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == alarms.size()) pickTime(a);
                        else editAlarm(a, alarms, which);
                    }
                })
                .setNegativeButton("Done", null)
                .show();
    }

    private static void editAlarm(final DashboardActivity a, final List<Alarm> alarms, final int index) {
        final Alarm x = alarms.get(index);
        String[] opts = { x.enabled ? "Turn off" : "Turn on", "Delete" };
        new AlertDialog.Builder(a, THEME).setItems(opts, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                if (which == 0) x.enabled = !x.enabled;
                else alarms.remove(index);
                save(a, alarms);
                show(a);
            }
        }).show();
    }

    private static void pickTime(final DashboardActivity a) {
        Calendar c = Calendar.getInstance();
        new TimePickerDialog(a, THEME, new TimePickerDialog.OnTimeSetListener() {
            @Override public void onTimeSet(TimePicker v, int hour, int minute) { pickDays(a, hour, minute); }
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), a.cfg.clock24h).show();
    }

    private static void pickDays(final DashboardActivity a, final int hour, final int minute) {
        final String[] opts = { "Once", "Weekdays", "Weekends", "Every day", "Choose days…" };
        final int[] masks = { 0, Alarm.WEEKDAYS, Alarm.WEEKENDS, Alarm.EVERY_DAY, -1 };
        new AlertDialog.Builder(a, THEME).setTitle("Repeat").setItems(opts, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                if (masks[which] >= 0) askLabel(a, hour, minute, masks[which]);
                else chooseDays(a, hour, minute);
            }
        }).show();
    }

    private static void chooseDays(final DashboardActivity a, final int hour, final int minute) {
        final String[] names = { "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday" };
        final boolean[] on = new boolean[7];
        new AlertDialog.Builder(a, THEME).setTitle("Which days?")
                .setMultiChoiceItems(names, on, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override public void onClick(DialogInterface d, int which, boolean checked) { on[which] = checked; }
                })
                .setPositiveButton("Next", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        int mask = 0;
                        for (int i = 0; i < 7; i++) if (on[i]) mask |= 1 << ((i + 1) % 7); // Monday = bit 1 ... Sunday = bit 0
                        askLabel(a, hour, minute, mask);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void askLabel(final DashboardActivity a, final int hour, final int minute, final int days) {
        final EditText input = new EditText(a);
        input.setSingleLine(true);
        input.setHint("Name (optional), e.g. Work");
        new AlertDialog.Builder(a, THEME).setTitle("Name this alarm").setView(input)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        Alarm x = new Alarm();
                        x.hour = hour;
                        x.minute = minute;
                        x.days = days;
                        x.label = input.getText().toString().trim();
                        add(a, x);
                        show(a);
                    }
                })
                .setNegativeButton("Skip", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        Alarm x = new Alarm();
                        x.hour = hour;
                        x.minute = minute;
                        x.days = days;
                        add(a, x);
                        show(a);
                    }
                })
                .show();
    }

    /** Adds an alarm (or switches on an identical one) and reschedules. */
    static void add(DashboardActivity a, Alarm x) {
        List<Alarm> alarms = Prefs.alarms(a);
        int maxId = 0;
        for (Alarm e : alarms) {
            maxId = Math.max(maxId, e.id);
            if (e.hour == x.hour && e.minute == x.minute && e.days == x.days) {
                e.enabled = true;
                if (!x.label.isEmpty()) e.label = x.label;
                save(a, alarms);
                return;
            }
        }
        x.id = maxId + 1;
        x.enabled = true;
        alarms.add(x);
        save(a, alarms);
    }

    private static void save(DashboardActivity a, List<Alarm> alarms) {
        Prefs.saveAlarms(a, alarms);
        AlarmScheduler.reschedule(a);
        a.update();
    }
}
