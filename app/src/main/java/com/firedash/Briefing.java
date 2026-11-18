package com.firedash;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** The spoken morning briefing: time, weather, today's events, the next United match. */
final class Briefing {
    private Briefing() {}

    static String build(Config cfg, Forecast f, United u, List<Agenda.Event> events, List<News.Item> news, long now) {
        StringBuilder b = new StringBuilder();
        Calendar local = Calendar.getInstance(TimeZone.getDefault(), Locale.UK);
        local.setTimeInMillis(now);
        int h = local.get(Calendar.HOUR_OF_DAY);
        b.append(h < 12 ? "Good morning. " : h < 18 ? "Good afternoon. " : "Good evening. ");
        b.append("It's ").append(spokenTime(h, local.get(Calendar.MINUTE))).append(". ");

        if (f != null) {
            Calendar place = Wx.placeTime(now, f.utcOffsetSeconds);
            int hi = Forecast.findIndex(f.hTime, Wx.hourKey(place)), di = Forecast.findIndex(f.dTime, Wx.dayKey(place));
            Wx.Now n = Wx.now(f, hi, (now - f.fetchedAt) / 60000.0);
            if (n != null && n.temp != null) {
                b.append("In ").append(cfg.place).append(" it's ").append(Math.round(n.temp)).append(" degrees");
                String d = Wx.describe(n.code, n.day).toLowerCase(Locale.UK);
                if (!d.isEmpty()) b.append(" and ").append(d);
                b.append(". ");
            }
            Double max = Forecast.at(f.dMax, di), min = Forecast.at(f.dMin, di);
            if (max != null && min != null) b.append("Today's high is ").append(Math.round(max)).append(", low ").append(Math.round(min)).append(". ");
            String s = Wx.summary(f, hi, n, cfg);
            if (s != null) b.append(s.replace(" " + Wx.MIDDOT + " ", ". ").replace("%", " percent").replace(Wx.DEG, " degrees").replace(Wx.MINUS, "minus ")).append(' ');
        }

        if (events != null) {
            int count = 0;
            StringBuilder e = new StringBuilder();
            Calendar end = (Calendar) local.clone();
            end.set(Calendar.HOUR_OF_DAY, 23);
            end.set(Calendar.MINUTE, 59);
            for (Agenda.Event ev : events) {
                if (ev.start > end.getTimeInMillis() || ev.end < now) continue;
                if (count < 3) {
                    if (count > 0) e.append(", ");
                    if (ev.allDay) e.append(ev.title);
                    else {
                        Calendar c = (Calendar) local.clone();
                        c.setTimeInMillis(ev.start);
                        e.append(ev.title).append(" at ").append(spokenTime(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)));
                    }
                }
                count++;
            }
            if (count == 0) b.append("Your calendar is clear today. ");
            else b.append("You have ").append(count).append(count == 1 ? " thing" : " things").append(" today: ").append(e).append(". ");
        }

        if (u != null && u.next != null) {
            United.Match m = u.next;
            if (u.isLive()) {
                b.append("United are playing now: ").append(m.home).append(' ').append(nz(m.homeScore)).append(", ")
                        .append(m.away).append(' ').append(nz(m.awayScore)).append(". ");
            } else if (m.timeMs > now) {
                Calendar c = (Calendar) local.clone();
                c.setTimeInMillis(m.timeMs);
                b.append("United play ").append(m.opponent()).append(m.ourHome ? " at home " : " away ")
                        .append(dayWord(c, local)).append(" at ").append(spokenTime(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))).append(". ");
            }
        }
        if (news != null && !news.isEmpty()) b.append("The latest United headline: ").append(news.get(0).title).append('.');
        return b.toString().trim();
    }

    private static String nz(String s) { return s == null ? "0" : s; }

    static String spokenTime(int h, int m) {
        int hh = h % 12 == 0 ? 12 : h % 12;
        String ampm = h < 12 ? "am" : "pm";
        if (m == 0) return hh + " " + ampm;
        return hh + ":" + Wx.pad(m) + " " + ampm;
    }

    private static String dayWord(Calendar when, Calendar now) {
        Calendar a = (Calendar) now.clone(), b = (Calendar) when.clone();
        for (Calendar c : new Calendar[] { a, b }) {
            c.set(Calendar.HOUR_OF_DAY, 12); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        }
        long days = Math.round((b.getTimeInMillis() - a.getTimeInMillis()) / 86400000.0);
        if (days == 0) return "today";
        if (days == 1) return "tomorrow";
        if (days < 7) return "on " + Wx.DAYS[when.get(Calendar.DAY_OF_WEEK) - 1];
        return "on " + Wx.DAYS[when.get(Calendar.DAY_OF_WEEK) - 1] + " the " + ordinal(when.get(Calendar.DAY_OF_MONTH));
    }

    static String ordinal(int d) {
        if (d >= 11 && d <= 13) return d + "th";
        switch (d % 10) {
            case 1: return d + "st";
            case 2: return d + "nd";
            case 3: return d + "rd";
            default: return d + "th";
        }
    }
}
