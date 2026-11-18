package com.firedash;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** One alarm. days is a bitmask, Sunday = bit 0 ... Saturday = bit 6; 0 means "once". */
final class Alarm {
    int id;
    int hour, minute;
    int days;
    String label = "";
    boolean enabled = true;

    static final int WEEKDAYS = 0b0111110, WEEKENDS = 0b1000001, EVERY_DAY = 0b1111111;
    private static final String[] SHORT = { "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat" };

    /** Next time this alarm goes off, strictly after now. */
    long next(long now, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz, Locale.ROOT);
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < 8; i++) {
            if (c.getTimeInMillis() > now) {
                int dow = c.get(Calendar.DAY_OF_WEEK) - 1;
                if (days == 0 || (days & (1 << dow)) != 0) return c.getTimeInMillis();
            }
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return Long.MAX_VALUE;
    }

    String daysLabel() {
        if (days == 0) return "Once";
        if (days == EVERY_DAY) return "Every day";
        if (days == WEEKDAYS) return "Weekdays";
        if (days == WEEKENDS) return "Weekends";
        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= 7; i++) {
            int d = i % 7; // Monday first
            if ((days & (1 << d)) != 0) b.append(b.length() > 0 ? ", " : "").append(SHORT[d]);
        }
        return b.toString();
    }

    /** "weekdays", "weekends", "daily", "once" or "mon,wed,fri". */
    static int parseDays(String s) {
        if (s == null) return 0;
        s = s.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.equals("once") || s.equals("tomorrow")) return 0;
        if (s.startsWith("weekday")) return WEEKDAYS;
        if (s.startsWith("weekend")) return WEEKENDS;
        if (s.equals("daily") || s.equals("every day") || s.equals("everyday")) return EVERY_DAY;
        int mask = 0;
        for (String part : s.split("[,\\s]+")) {
            for (int i = 0; i < 7; i++) if (part.startsWith(SHORT[i].toLowerCase(Locale.ROOT))) mask |= 1 << i;
        }
        return mask;
    }

    // Stored as lines: id|hour|minute|days|enabled|label
    static String serialize(List<Alarm> alarms) {
        StringBuilder b = new StringBuilder();
        for (Alarm a : alarms) {
            b.append(a.id).append('|').append(a.hour).append('|').append(a.minute).append('|').append(a.days).append('|')
                    .append(a.enabled ? 1 : 0).append('|').append(a.label.replace('|', ' ').replace('\n', ' ')).append('\n');
        }
        return b.toString();
    }

    static List<Alarm> parseAll(String s) {
        List<Alarm> out = new ArrayList<>();
        if (s == null) return out;
        for (String line : s.split("\n")) {
            String[] f = line.split("\\|", 6);
            if (f.length < 5) continue;
            try {
                Alarm a = new Alarm();
                a.id = Integer.parseInt(f[0]);
                a.hour = Integer.parseInt(f[1]);
                a.minute = Integer.parseInt(f[2]);
                a.days = Integer.parseInt(f[3]);
                a.enabled = f[4].equals("1");
                a.label = f.length > 5 ? f[5] : "";
                out.add(a);
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    /** The enabled alarm that goes off soonest, or null. */
    static Alarm soonest(List<Alarm> alarms, long now, TimeZone tz) {
        Alarm best = null;
        long bestT = Long.MAX_VALUE;
        for (Alarm a : alarms) {
            if (!a.enabled) continue;
            long t = a.next(now, tz);
            if (t < bestT) { bestT = t; best = a; }
        }
        return best;
    }
}
