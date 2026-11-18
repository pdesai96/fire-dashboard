package com.firedash;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Upcoming events from a calendar's private iCal link (Outlook: Settings > Calendar >
 * Shared calendars > Publish a calendar > ICS). Handles the common repeat rules.
 */
final class Agenda {
    static final class Event {
        final String title;
        final long start, end;
        final boolean allDay;
        Event(String title, long start, long end, boolean allDay) { this.title = title; this.start = start; this.end = end; this.allDay = allDay; }
    }

    private static final Map<String, String> WINDOWS_ZONES = new HashMap<>();
    static {
        String[][] z = {
                { "GMT Standard Time", "Europe/London" }, { "Greenwich Standard Time", "Atlantic/Reykjavik" },
                { "W. Europe Standard Time", "Europe/Berlin" }, { "Romance Standard Time", "Europe/Paris" },
                { "Central Europe Standard Time", "Europe/Budapest" }, { "Central European Standard Time", "Europe/Warsaw" },
                { "E. Europe Standard Time", "Europe/Chisinau" }, { "FLE Standard Time", "Europe/Kiev" },
                { "GTB Standard Time", "Europe/Bucharest" }, { "Eastern Standard Time", "America/New_York" },
                { "Central Standard Time", "America/Chicago" }, { "Mountain Standard Time", "America/Denver" },
                { "Pacific Standard Time", "America/Los_Angeles" }, { "India Standard Time", "Asia/Kolkata" },
                { "China Standard Time", "Asia/Shanghai" }, { "Tokyo Standard Time", "Asia/Tokyo" },
                { "AUS Eastern Standard Time", "Australia/Sydney" }, { "Arabian Standard Time", "Asia/Dubai" },
                { "UTC", "UTC" }, { "Coordinated Universal Time", "UTC" },
        };
        for (String[] p : z) WINDOWS_ZONES.put(p[0], p[1]);
    }

    /** Events overlapping [from, to), sorted by start. */
    static List<Event> parse(String ics, long from, long to) {
        List<Map<String, String[]>> events = new ArrayList<>();
        Map<String, String[]> cur = null;
        Map<String, List<String>> exdates = null;
        List<Map<String, List<String>>> allExdates = new ArrayList<>();
        for (String line : unfold(ics)) {
            if (line.equals("BEGIN:VEVENT")) { cur = new HashMap<>(); exdates = new HashMap<>(); continue; }
            if (line.equals("END:VEVENT")) {
                if (cur != null) { events.add(cur); allExdates.add(exdates); }
                cur = null;
                continue;
            }
            if (cur == null) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String head = line.substring(0, colon), value = line.substring(colon + 1);
            String name = head, params = "";
            int semi = head.indexOf(';');
            if (semi >= 0) { name = head.substring(0, semi); params = head.substring(semi + 1); }
            name = name.toUpperCase(Locale.ROOT);
            if (name.equals("EXDATE")) {
                List<String> l = exdates.get(params);
                if (l == null) exdates.put(params, l = new ArrayList<>());
                Collections.addAll(l, value.split(","));
            } else {
                cur.put(name, new String[] { params, value });
            }
        }

        // Modified instances of repeating events replace the original occurrence.
        Map<String, Set<Long>> overridden = new HashMap<>();
        for (Map<String, String[]> e : events) {
            String[] rid = e.get("RECURRENCE-ID"), uid = e.get("UID");
            if (rid != null && uid != null) {
                Set<Long> s = overridden.get(uid[1]);
                if (s == null) overridden.put(uid[1], s = new HashSet<>());
                s.add(time(rid[0], rid[1]));
            }
        }

        List<Event> out = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            Map<String, String[]> e = events.get(i);
            String[] st = e.get("DTSTART");
            if (st == null) continue;
            String[] status = e.get("STATUS");
            if (status != null && status[1].equalsIgnoreCase("CANCELLED")) continue;
            String title = text(e.get("SUMMARY"));
            boolean allDay = st[0].toUpperCase(Locale.ROOT).contains("VALUE=DATE") || st[1].length() == 8;
            long start = time(st[0], st[1]);
            String[] en = e.get("DTEND");
            long dur = en != null ? time(en[0], en[1]) - start : (allDay ? 86400000L : 0);
            if (dur < 0) dur = 0;
            String[] rrule = e.get("RRULE");
            if (rrule == null || e.get("RECURRENCE-ID") != null) {
                if (start < to && start + Math.max(dur, 1) > from) out.add(new Event(title, start, start + dur, allDay));
                continue;
            }
            Set<Long> skip = new HashSet<>();
            for (Map.Entry<String, List<String>> x : allExdates.get(i).entrySet()) {
                for (String v : x.getValue()) skip.add(time(x.getKey(), v.trim()));
            }
            String[] uid = e.get("UID");
            if (uid != null && overridden.containsKey(uid[1])) skip.addAll(overridden.get(uid[1]));
            for (long occ : expand(rrule[1], start, zone(st[0]), from - dur, to)) {
                if (!skip.contains(occ)) out.add(new Event(title, occ, occ + dur, allDay));
            }
        }
        Collections.sort(out, new Comparator<Event>() {
            @Override public int compare(Event a, Event b) { return Long.compare(a.start, b.start); }
        });
        return out;
    }

    private static List<String> unfold(String ics) {
        List<String> lines = new ArrayList<>();
        for (String raw : ics.split("\r?\n")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && !lines.isEmpty()) {
                lines.set(lines.size() - 1, lines.get(lines.size() - 1) + raw.substring(1));
            } else {
                lines.add(raw);
            }
        }
        return lines;
    }

    private static String text(String[] v) {
        if (v == null) return "(no title)";
        return v[1].replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\").trim();
    }

    static TimeZone zone(String params) {
        for (String p : params.split(";")) {
            if (p.toUpperCase(Locale.ROOT).startsWith("TZID=")) {
                String id = p.substring(5).replace("\"", "");
                String mapped = WINDOWS_ZONES.get(id);
                if (mapped != null) return TimeZone.getTimeZone(mapped);
                TimeZone tz = TimeZone.getTimeZone(id);
                if (!tz.getID().equals("GMT") || id.equals("GMT")) return tz;
            }
        }
        return TimeZone.getDefault();
    }

    /** Parses 20261118T090000Z, 20261118T090000 (in the TZID zone) or 20261118 (local midnight). */
    static long time(String params, String v) {
        v = v.trim();
        try {
            boolean utc = v.endsWith("Z");
            TimeZone tz = utc ? TimeZone.getTimeZone("UTC") : zone(params);
            Calendar c = Calendar.getInstance(tz, Locale.ROOT);
            c.clear();
            int y = Integer.parseInt(v.substring(0, 4)), mo = Integer.parseInt(v.substring(4, 6)) - 1, d = Integer.parseInt(v.substring(6, 8));
            if (v.length() >= 15) {
                c.set(y, mo, d, Integer.parseInt(v.substring(9, 11)), Integer.parseInt(v.substring(11, 13)), Integer.parseInt(v.substring(13, 15)));
            } else {
                c.set(y, mo, d, 0, 0, 0);
            }
            return c.getTimeInMillis();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static final String[] DAY_CODES = { "SU", "MO", "TU", "WE", "TH", "FR", "SA" };

    /** Occurrence start times of a repeating event that fall in [from, to). */
    static List<Long> expand(String rule, long dtstart, TimeZone tz, long from, long to) {
        Map<String, String> r = new HashMap<>();
        for (String part : rule.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) r.put(part.substring(0, eq).toUpperCase(Locale.ROOT), part.substring(eq + 1));
        }
        String freq = r.containsKey("FREQ") ? r.get("FREQ") : "DAILY";
        int interval = 1, count = Integer.MAX_VALUE;
        try { if (r.containsKey("INTERVAL")) interval = Math.max(1, Integer.parseInt(r.get("INTERVAL"))); } catch (NumberFormatException ignored) { }
        try { if (r.containsKey("COUNT")) count = Integer.parseInt(r.get("COUNT")); } catch (NumberFormatException ignored) { }
        long until = r.containsKey("UNTIL") ? time("", r.get("UNTIL")) : Long.MAX_VALUE;
        if (until == 0) until = Long.MAX_VALUE;
        List<Integer> byDay = new ArrayList<>();
        if (r.containsKey("BYDAY")) {
            for (String d : r.get("BYDAY").split(",")) {
                String code = d.length() >= 2 ? d.substring(d.length() - 2) : d;
                for (int k = 0; k < 7; k++) if (DAY_CODES[k].equals(code)) byDay.add(k + 1);
            }
        }
        List<Long> out = new ArrayList<>();
        Calendar c = Calendar.getInstance(tz, Locale.ROOT);
        c.setTimeInMillis(dtstart);
        int n = 0;
        for (int guard = 0; guard < 5000; guard++) {
            List<Long> batch = new ArrayList<>();
            if (freq.equals("WEEKLY") && !byDay.isEmpty()) {
                Calendar w = (Calendar) c.clone();
                w.setFirstDayOfWeek(Calendar.MONDAY);
                for (int dow : byDay) {
                    Calendar x = (Calendar) w.clone();
                    x.set(Calendar.DAY_OF_WEEK, dow);
                    if (x.getTimeInMillis() >= dtstart) batch.add(x.getTimeInMillis());
                }
                Collections.sort(batch);
            } else {
                batch.add(c.getTimeInMillis());
            }
            for (long t : batch) {
                if (t > until || n >= count) return out;
                n++;
                if (t >= from && t < to) out.add(t);
            }
            if (c.getTimeInMillis() >= to) return out;
            switch (freq) {
                case "WEEKLY": c.add(Calendar.WEEK_OF_YEAR, interval); break;
                case "MONTHLY": c.add(Calendar.MONTH, interval); break;
                case "YEARLY": c.add(Calendar.YEAR, interval); break;
                default: c.add(Calendar.DAY_OF_MONTH, interval);
            }
        }
        return out;
    }
}
