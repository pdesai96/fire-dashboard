package com.firedash;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** The team card: next match (or live score), last result, league position. Data from ESPN's public JSON. */
final class United {
    static final class Match {
        String id, state = "pre", detail = "", clock = "", competition = "", venue = "";
        String home = "", away = "", homeAbbr = "", awayAbbr = "", homeScore, awayScore;
        boolean ourHome;
        long timeMs;

        String opponent() { return ourHome ? away : home; }

        /** W, D or L from our point of view, or null if not finished/known. */
        String result() {
            if (!"post".equals(state) || homeScore == null || awayScore == null) return null;
            try {
                double h = Double.parseDouble(homeScore), a = Double.parseDouble(awayScore);
                double us = ourHome ? h : a, them = ourHome ? a : h;
                return us > them ? "W" : us < them ? "L" : "D";
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    String standing = "";     // e.g. "3rd in English Premier League"
    int points = -1;
    Match next, last;
    long fetchedAt;

    boolean isLive() { return next != null && "in".equals(next.state); }

    static String teamUrl(LiveConfig c, String scheme) {
        return scheme + "://site.api.espn.com/apis/site/v2/sports/soccer/" + c.teamLeague + "/teams/" + c.teamId;
    }

    static String scheduleUrl(LiveConfig c, String scheme) {
        return teamUrl(c, scheme) + "/schedule";
    }

    static United fetch(LiveConfig c, long now) throws IOException {
        String team = Net.getFirst(teamUrl(c, "http"), teamUrl(c, "https"));
        String schedule = null;
        try {
            schedule = Net.getFirst(scheduleUrl(c, "http"), scheduleUrl(c, "https"));
        } catch (IOException ignored) {
            // the last result is a nice-to-have
        }
        return parse(team, schedule, c.teamId, now);
    }

    @SuppressWarnings("unchecked")
    static United parse(String teamJson, String scheduleJson, String teamId, long now) {
        United u = new United();
        u.fetchedAt = now;
        Map<String, Object> root = map(Json.parse(teamJson));
        Map<String, Object> team = map(root.get("team"));
        u.standing = str(team.get("standingSummary"));
        Map<String, Object> record = map(team.get("record"));
        List<Object> items = list(record.get("items"));
        if (!items.isEmpty()) {
            for (Object s : list(map(items.get(0)).get("stats"))) {
                Map<String, Object> stat = map(s);
                if ("points".equals(stat.get("name")) && stat.get("value") instanceof Double) {
                    u.points = ((Double) stat.get("value")).intValue();
                }
            }
        }
        List<Object> nextEvents = list(team.get("nextEvent"));
        if (!nextEvents.isEmpty()) u.next = match(map(nextEvents.get(0)), teamId);

        if (scheduleJson != null) {
            try {
                Map<String, Object> sched = map(Json.parse(scheduleJson));
                for (Object e : list(sched.get("events"))) {
                    Match m = match(map(e), teamId);
                    if (m != null && "post".equals(m.state) && m.timeMs <= now && (u.last == null || m.timeMs > u.last.timeMs)) {
                        u.last = m;
                    }
                }
            } catch (RuntimeException ignored) {
                // keep what we have
            }
        }
        // Just after full time, ESPN may still list the finished match as "next".
        if (u.next != null && "post".equals(u.next.state)) {
            if (u.last == null || u.next.timeMs >= u.last.timeMs) u.last = u.next;
            u.next = null;
        }
        return u;
    }

    private static Match match(Map<String, Object> ev, String teamId) {
        List<Object> comps = list(ev.get("competitions"));
        if (comps.isEmpty()) return null;
        Map<String, Object> comp = map(comps.get(0));
        Match m = new Match();
        m.id = str(ev.get("id"));
        String date = str(comp.get("date"));
        if (date.isEmpty()) date = str(ev.get("date"));
        m.timeMs = parseTime(date);
        Map<String, Object> status = map(comp.get("status"));
        Map<String, Object> type = map(status.get("type"));
        m.state = str(type.get("state"));
        if (m.state.isEmpty()) m.state = "pre";
        m.detail = str(type.get("shortDetail"));
        m.clock = str(status.get("displayClock"));
        Map<String, Object> league = map(ev.get("league"));
        m.competition = str(league.get("abbreviation"));
        if (m.competition.isEmpty()) m.competition = str(league.get("name"));
        if (m.competition.isEmpty()) m.competition = str(map(ev.get("seasonType")).get("name"));
        m.venue = str(map(comp.get("venue")).get("fullName"));
        for (Object o : list(comp.get("competitors"))) {
            Map<String, Object> c = map(o);
            Map<String, Object> t = map(c.get("team"));
            String name = str(t.get("shortDisplayName"));
            if (name.isEmpty()) name = str(t.get("displayName"));
            boolean home = "home".equals(c.get("homeAway"));
            String score = score(c.get("score"));
            boolean ours = teamId.equals(str(c.get("id"))) || teamId.equals(str(t.get("id")));
            if (home) {
                m.home = name; m.homeAbbr = str(t.get("abbreviation")); m.homeScore = score;
                if (ours) m.ourHome = true;
            } else {
                m.away = name; m.awayAbbr = str(t.get("abbreviation")); m.awayScore = score;
            }
        }
        return m;
    }

    private static String score(Object s) {
        if (s instanceof String) return ((String) s).isEmpty() ? null : (String) s;
        if (s instanceof Double) return String.valueOf(((Double) s).intValue());
        if (s instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) s;
            Object d = m.get("displayValue");
            if (d instanceof String && !((String) d).isEmpty()) return (String) d;
            Object v = m.get("value");
            if (v instanceof Double) return String.valueOf(((Double) v).intValue());
        }
        return null;
    }

    static long parseTime(String s) {
        String[] patterns = { "yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" };
        for (String p : patterns) {
            SimpleDateFormat f = new SimpleDateFormat(p, Locale.ROOT);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            f.setLenient(false);
            try {
                return f.parse(s).getTime();
            } catch (ParseException ignored) { }
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : java.util.Collections.<String, Object>emptyMap();
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : java.util.Collections.emptyList();
    }

    static String str(Object o) { return o instanceof String ? (String) o : ""; }
}
