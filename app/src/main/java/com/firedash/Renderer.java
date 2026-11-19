package com.firedash;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Lays out and draws the dashboard on a gradient. Positions are grid units scaled to the screen:
 * landscape uses a 64 x 40 grid (two columns), portrait a 37.5 x 62 grid (one column).
 */
final class Renderer {
    // ---- inputs
    Config cfg = new Config();
    LiveConfig live = LiveConfig.defaults();
    Forecast data;
    United united;
    String unitedError;
    List<News.Item> news;
    List<Agenda.Event> agenda;      // null = no calendar set up
    Alarm nextAlarm;
    long nextAlarmAt;
    String lastError;
    boolean updating, updateReady;
    String assistantName = "JARVIS";
    boolean assistantConnected;
    Palette pal = Palette.of(0xFF1B2440, 0xFF06080F);
    long nowMs;

    // ---- outputs: tap targets
    static final class Hit {
        final float x, y, w, h;
        final String action;
        Hit(float x, float y, float w, float h, String action) { this.x = x; this.y = y; this.w = w; this.h = h; this.action = action; }
        boolean contains(float px, float py) { return px >= x && px <= x + w && py >= y && py <= y + h; }
    }
    final List<Hit> hits = new ArrayList<>();

    private static final int LEFT = 0, CENTER = 1, RIGHT = 2;
    private static final Painter.Font REG = Painter.Font.REGULAR, BOLD = Painter.Font.BOLD, SERIF = Painter.Font.SERIF_ITALIC;
    private static final float TRACK = 0.16f;

    private Painter p;
    private float u, ox, oy;

    void draw(Painter painter, int w, int h) {
        p = painter;
        hits.clear();
        p.fillGradient(0, 0, w, h, pal.top, pal.bottom);
        Calendar now = Wx.placeTime(nowMs, data != null ? data.utcOffsetSeconds : null);
        double age = data != null ? (nowMs - data.fetchedAt) / 60000.0 : 1e9;
        int hi = -1, di = -1;
        if (data != null) {
            hi = Forecast.findIndex(data.hTime, Wx.hourKey(now));
            di = Forecast.findIndex(data.dTime, Wx.dayKey(now));
        }
        if (h >= w * 1.15f) portrait(w, h, now, hi, di, age);
        else landscape(w, h, now, hi, di, age);
    }

    private void grid(int w, int h, float gw, float gh) {
        u = Math.min(w / gw, h / gh);
        ox = (float) Math.floor((w - gw * u) / 2);
        oy = (float) Math.floor((h - gh * u) / 2);
    }

    // ------------------------------------------------------------------ layouts

    private void landscape(int w, int h, Calendar now, int hi, int di, double age) {
        grid(w, h, 64f, 40f);
        float lx = 1.6f, lw = 31.6f, rx = 34.8f, rw = 27.6f;
        topLine(now, lx, 2.9f, lw, age);
        clock(lx, 3.4f, lw, 11.2f, 12.5f);
        alarmLine(lx, 16.1f, lw);
        weather(lx, 17.3f, lw, 10.5f, hi, di, age);
        forecast(lx, 28.6f, lw, 10.0f, hi, di);
        boolean cal = agenda != null && live.showCalendar;
        float y = 1.6f;
        if (live.showUnited) { unitedCard(rx, y, rw, 13.4f); y += 14.2f; }
        if (cal) { calendar(rx, y, rw, 7.6f); y += 8.4f; }
        if (live.showNews) newsCard(rx, y, rw, 38.6f - y);
    }

    private void portrait(int w, int h, Calendar now, int hi, int di, double age) {
        grid(w, h, 37.5f, 62f);
        float x = 1.6f, cw = 34.3f;
        topLine(now, x, 2.9f, cw, age);
        clock(x, 3.4f, cw, 11.2f, 12.5f);
        alarmLine(x, 16.1f, cw);
        weather(x, 17.3f, cw, 10.5f, hi, di, age);
        forecast(x, 28.6f, cw, 10.0f, hi, di);
        boolean cal = agenda != null && live.showCalendar;
        float y = 39.4f;
        if (live.showUnited) { unitedCard(x, y, cw, cal ? 10.8f : 12.0f); y += cal ? 11.6f : 12.8f; }
        if (cal) { calendar(x, y, cw, 6.6f); y += 7.4f; }
        if (live.showNews && 60.6f - y > 4) newsCard(x, y, cw, 60.6f - y);
    }

    // ------------------------------------------------------------------ sections

    private void topLine(Calendar now, float x, float y, float w, double age) {
        String day = Wx.DAYS[now.get(Calendar.DAY_OF_WEEK) - 1].toUpperCase(Locale.ROOT);
        String date = now.get(Calendar.DAY_OF_MONTH) + " " + Wx.MONTHS[now.get(Calendar.MONTH)].toUpperCase(Locale.ROOT);
        float dw = text(day, BOLD, 0.95f, TRACK, x, y, LEFT, pal.text, 0);
        float dw2 = text(date, REG, 0.95f, TRACK, x + dw + 0.8f, y, LEFT, pal.text, 0);
        float px = x + dw + 0.8f + dw2 + 1.0f;
        float right = assistantPill(x + w, y);
        if (updateReady) {
            String s = "Update ready · tap to install";
            float sw = measure(s, BOLD, 0.78f, 0) + 1.6f;
            if (right - sw < px) { // not enough room beside the assistant pill: shorter label
                s = "Install update";
                sw = measure(s, BOLD, 0.78f, 0) + 1.6f;
            }
            float bx = right - sw, by = y - 1.15f;
            roundRect(bx, by, sw, 1.6f, 0.8f, pal.accent);
            text(s, BOLD, 0.78f, 0, bx + 0.8f, y - 0.05f, LEFT, 0xFF1A1A1A, 0);
            hits.add(hit(bx - 0.5f, by - 0.6f, sw + 1f, 2.8f, "update"));
            right = bx - 0.8f;
        } else {
            String s = status(age);
            if (s != null) right -= text(s, REG, 0.75f, 0, right, y, RIGHT, pal.muted, w * 0.45f) + 0.8f;
        }
        if (right - px > 2) text(cfg.place.toUpperCase(Locale.ROOT), REG, 0.8f, TRACK, px, y, LEFT, pal.muted, right - px);
    }

    /** The assistant button at the right of the top line. Returns the x where the space to its left ends. */
    private float assistantPill(float right, float y) {
        String label = assistantConnected ? assistantName.toUpperCase(Locale.ROOT) : "SET UP " + assistantName.toUpperCase(Locale.ROOT);
        float size = 0.78f, track = assistantConnected ? 0.22f : 0.12f;
        float tw = measure(label, BOLD, size, track);
        float dot = 0.62f, padL = 0.7f, padR = 0.8f;
        float sw = padL + dot + 0.6f + tw + padR;
        float bx = right - sw, by = y - 1.15f;
        roundRect(bx, by, sw, 1.6f, 0.8f, pal.panel);
        int ring = assistantConnected ? 0xFF4FD1FF : pal.faint;
        float cx = bx + padL + dot / 2, cy = by + 0.8f;
        p.circle(X(cx), Y(cy), dot / 2 * u, assistantConnected ? ring : null, ring, Math.max(1f, 0.1f * u));
        if (assistantConnected) p.circle(X(cx), Y(cy), dot * 0.95f * u, null, 0x554FD1FF, Math.max(1f, 0.08f * u));
        text(label, BOLD, size, track, bx + padL + dot + 0.6f, y - 0.05f, LEFT, assistantConnected ? pal.text : pal.muted, 0);
        hits.add(hit(bx - 0.5f, by - 0.6f, sw + 1f, 2.8f, "assistant"));
        return bx - 0.8f;
    }

    private String status(double age) {
        if (updating) return "Updating" + Wx.ELLIPSIS;
        if (live.notice != null && !live.notice.isEmpty()) return live.notice;
        if (data == null) return lastError != null ? "Offline" : null;
        if (age > cfg.refreshMinutes * 3) {
            String ago = age < 120 ? Math.round(age) + " min" : Math.round(age / 60) + " h";
            return "Offline · weather from " + ago + " ago";
        }
        return null;
    }

    private void clock(float x, float y, float w, float h, float maxSize) {
        // The clock follows the tablet's own time zone.
        Calendar local = Calendar.getInstance(TimeZone.getDefault(), Locale.ROOT);
        local.setTimeInMillis(nowMs);
        String[] c = Wx.clock(local.get(Calendar.HOUR_OF_DAY), local.get(Calendar.MINUTE), cfg.clock24h);
        float size = maxSize, avail = w * 0.97f;
        float need = measure(c[0], BOLD, size, -0.02f) + (c[1] != null ? 0.4f + measure(c[1], BOLD, size * 0.2f, 0.06f) : 0);
        if (need > avail) size *= avail / need;
        float base = y + h / 2f + 0.357f * size;
        float cw = text(c[0], BOLD, size, -0.02f, x - 0.045f * size, base, LEFT, pal.text, 0);
        if (c[1] != null) text(c[1], BOLD, size * 0.2f, 0.06f, x - 0.045f * size + cw + 0.4f, base, LEFT, pal.text, 0);
        hits.add(hit(x, y, w, h + 2.2f, "clock"));
    }

    private void alarmLine(float x, float y, float w) {
        if (nextAlarm == null) {
            text("Tap the clock to set an alarm", REG, 0.85f, 0, x, y, LEFT, pal.faint, w);
            return;
        }
        Calendar c = Calendar.getInstance(TimeZone.getDefault(), Locale.ROOT);
        c.setTimeInMillis(nextAlarmAt);
        String[] t = Wx.clock(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), cfg.clock24h);
        Calendar today = Calendar.getInstance(TimeZone.getDefault(), Locale.ROOT);
        today.setTimeInMillis(nowMs);
        long days = Math.round((midday(c) - midday(today)) / 86400000.0);
        String when = days == 0 ? "today" : days == 1 ? "tomorrow" : Wx.DAYS[c.get(Calendar.DAY_OF_WEEK) - 1];
        bell(x + 0.45f, y - 0.4f, 0.95f);
        String label = nextAlarm.label.isEmpty() ? "" : "  ·  " + nextAlarm.label;
        run(new Object[] { t[0] + (t[1] != null ? " " + t[1] : ""), BOLD, "  " + when + label, REG }, 0.95f, x + 1.4f, y, w - 1.4f);
    }

    private void weather(float x, float y, float w, float h, int hi, int di, double age) {
        panel(x, y, w, h);
        hits.add(hit(x, y, w, h, "refresh"));
        float pad = 1.2f, mid = y + 4.4f;
        if (data == null) {
            if (lastError != null) {
                text("Can’t reach the weather service (" + lastError + ").", BOLD, 0.95f, 0, x + pad, mid - 0.3f, LEFT, pal.text, w - 2 * pad);
                text("Check the Wi-Fi. Trying again shortly" + Wx.ELLIPSIS, REG, 0.9f, 0, x + pad, mid + 1.2f, LEFT, pal.muted, w - 2 * pad);
            } else {
                text("Getting the weather" + Wx.ELLIPSIS, REG, 1.0f, 0, x + pad, mid + 0.4f, LEFT, pal.muted, 0);
            }
            return;
        }
        Wx.Now n = Wx.now(data, hi, age);
        if (n == null) {
            text("The saved forecast is out of date. Tap to refresh.", REG, 0.95f, 0, x + pad, mid + 0.4f, LEFT, pal.muted, w - 2 * pad);
            return;
        }
        float is = 5.6f;
        Icons.draw(p, Icons.name(n.code, n.day), X(x + pad), Y(mid - is / 2), is * u, pal);
        String temp = Wx.deg(n.temp);
        float size = 5.4f, tw = measure(temp, BOLD, size, -0.03f);
        if (tw > 8.6f) { size *= 8.6f / tw; tw = 8.6f; }
        float tx = x + pad + 6.4f;
        text(temp, BOLD, size, -0.03f, tx, mid + 0.357f * size, LEFT, pal.text, 0);

        Double hiT = Forecast.at(data.dMax, di), loT = Forecast.at(data.dMin, di);
        float dx = Math.max(x + 16.6f, tx + tw + 0.8f), maxw = x + w - pad - dx;
        String wind = n.wind != null ? Math.round(n.wind) + " " + cfg.windLabel() : Wx.NDASH;
        String hum = n.humidity != null ? Math.round(n.humidity) + "%" : Wx.NDASH;
        text(Wx.describe(n.code, n.day), BOLD, 1.3f, 0, dx, mid - 2.3f, LEFT, pal.text, maxw);
        run(new Object[] { "Feels like ", REG, Wx.deg(n.feels), BOLD }, 0.9f, dx, mid - 0.6f, maxw);
        run(new Object[] { "High ", REG, Wx.deg(hiT), BOLD, "   Low ", REG, Wx.deg(loT), BOLD }, 0.9f, dx, mid + 0.75f, maxw);
        run(new Object[] { "Wind ", REG, wind, BOLD, " " + Wx.compass(n.windDir), REG }, 0.9f, dx, mid + 2.1f, maxw);
        run(new Object[] { "Humidity ", REG, hum, BOLD }, 0.9f, dx, mid + 3.45f, maxw);
        String s = Wx.summary(data, hi, n, cfg);
        if (s != null) text(s, SERIF, 1.05f, 0, x + pad, y + h - 1.15f, LEFT, pal.text, w - 2 * pad);
    }

    private void forecast(float x, float y, float w, float h, int hi, int di) {
        panel(x, y, w, h);
        if (data == null) return;
        float pad = 1.0f;
        int step = Math.max(1, cfg.hourStep);
        float colw = (w - 2 * pad) / 8f;
        if (hi >= 0) {
            int j = hi + 1;
            while (j < data.hTime.length && Wx.isoHour(data.hTime[j]) % step != 0) j++;
            for (int n = 0; j < data.hTime.length && n < 8; j += step, n++) {
                float cx = x + pad + (n + 0.5f) * colw;
                Double isDay = Forecast.at(data.hIsDay, j);
                text(Wx.isoTime(data.hTime[j], cfg.clock24h, true), BOLD, 0.75f, 0, cx, y + 1.3f, CENTER, pal.muted, colw * 0.95f);
                Icons.draw(p, Icons.name(Forecast.at(data.hCode, j), isDay != null && isDay == 1), X(cx - 1.0f), Y(y + 1.6f), 2.0f * u, pal);
                text(Wx.deg(Forecast.at(data.hTemp, j)), BOLD, 1.0f, 0, cx, y + 4.65f, CENTER, pal.text, colw * 0.95f);
                Double pr = Forecast.at(data.hRain, j);
                if (pr != null && pr >= 10) text(Math.round(pr) + "%", BOLD, 0.66f, 0, cx, y + 5.6f, CENTER, pal.rain, 0);
            }
        }
        rect(x + pad, y + 6.1f, w - 2 * pad, 0.06f, pal.panelLine);
        if (di < 0) return;
        float dcol = (w - 2 * pad) / 5f;
        for (int n = 0, k = di + 1; k < data.dTime.length && n < 5; k++, n++) {
            float cx = x + pad + (n + 0.5f) * dcol;
            Icons.draw(p, Icons.name(Forecast.at(data.dCode, k), true), X(cx - dcol * 0.46f), Y(y + 6.6f), 2.0f * u, pal);
            float tx = cx - dcol * 0.46f + 2.3f, tw = cx + dcol * 0.46f - tx;
            text(Wx.isoWeekday(data.dTime[k]).substring(0, 3).toUpperCase(Locale.ROOT), BOLD, 0.72f, TRACK, tx, y + 7.55f, LEFT, pal.muted, tw);
            run(new Object[] { Wx.deg(Forecast.at(data.dMax, k)), BOLD, " " + Wx.deg(Forecast.at(data.dMin, k)), REG }, 0.95f, tx, y + 9.0f, tw);
        }
    }

    private void unitedCard(float x, float y, float w, float h) {
        panel(x, y, w, h);
        hits.add(hit(x, y, w, h, "united"));
        float pad = 1.2f, ix = x + pad, iw = w - 2 * pad;
        text(live.teamName.toUpperCase(Locale.ROOT), BOLD, 0.78f, TRACK, ix, y + 1.7f, LEFT, pal.muted, iw * 0.6f);
        United t = united;
        if (t == null) {
            text(unitedError != null ? "Can’t get the latest (" + unitedError + ")." : "Getting the latest" + Wx.ELLIPSIS,
                    REG, 0.95f, 0, ix, y + 4.2f, LEFT, pal.muted, iw);
            return;
        }
        String standing = shortStanding(t);
        if (!standing.isEmpty()) text(standing, REG, 0.78f, 0, ix + iw, y + 1.7f, RIGHT, pal.muted, iw * 0.4f);

        United.Match m = t.next;
        if (m != null && t.isLive()) {
            float by = y + 3.4f;
            float lw = text("LIVE", BOLD, 0.8f, TRACK, ix, by, LEFT, pal.live, 0);
            String clock = m.clock.isEmpty() ? m.detail : m.clock;
            text(clock + (m.competition.isEmpty() ? "" : "  ·  " + m.competition), REG, 0.8f, 0, ix + lw + 0.6f, by, LEFT, pal.muted, iw - lw - 0.6f);
            float sy = y + 7.3f, cx = ix + iw / 2f;
            String score = (m.homeScore == null ? "0" : m.homeScore) + " – " + (m.awayScore == null ? "0" : m.awayScore);
            float sw = text(score, BOLD, 3.0f, 0, cx, sy, CENTER, pal.text, 0);
            float side = iw / 2 - sw / 2 - 0.8f;
            text(m.home, BOLD, 1.1f, 0, cx - sw / 2 - 0.8f, sy - 0.8f, RIGHT, pal.text, side);
            text(m.away, BOLD, 1.1f, 0, cx + sw / 2 + 0.8f, sy - 0.8f, LEFT, pal.text, side);
            if (!m.venue.isEmpty()) text(m.venue, REG, 0.78f, 0, cx, sy + 1.9f, CENTER, pal.muted, iw);
        } else if (m != null) {
            text(("Next match" + (m.competition.isEmpty() ? "" : " · " + m.competition)).toUpperCase(Locale.ROOT),
                    REG, 0.7f, TRACK, ix, y + 3.5f, LEFT, pal.faint, iw);
            run(new Object[] { m.opponent(), BOLD, m.ourHome ? "  (home)" : "  (away)", REG }, 2.0f, ix, y + 6.1f, iw);
            Calendar c = Calendar.getInstance(TimeZone.getDefault(), Locale.ROOT);
            c.setTimeInMillis(m.timeMs);
            String[] tm = Wx.clock(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), cfg.clock24h);
            String when = Wx.DAYS[c.get(Calendar.DAY_OF_WEEK) - 1].substring(0, 3) + " " + c.get(Calendar.DAY_OF_MONTH) + " "
                    + Wx.MONTHS[c.get(Calendar.MONTH)].substring(0, 3) + "  ·  " + tm[0] + (tm[1] != null ? " " + tm[1] : "");
            text(when, REG, 1.05f, 0, ix, y + 7.9f, LEFT, pal.text, iw);
            float cw = text(countdown(m.timeMs), BOLD, 1.0f, 0, ix, y + 9.5f, LEFT, pal.accent, iw);
            if (!m.venue.isEmpty() && h > 12) text(m.venue, REG, 0.78f, 0, ix + iw, y + 9.5f, RIGHT, pal.muted, iw - cw - 1f);
        } else {
            text("No upcoming match listed yet.", REG, 0.95f, 0, ix, y + 5f, LEFT, pal.muted, iw);
        }
        United.Match last = t.last;
        if (last != null && last.homeScore != null && last.awayScore != null) {
            float ly = y + h - 1.25f;
            rect(ix, ly - 1.45f, iw, 0.06f, pal.panelLine);
            String r = last.result();
            float lx = ix + text("LAST", BOLD, 0.66f, TRACK, ix, ly, LEFT, pal.faint, 0) + 0.6f;
            if (r != null) {
                int col = r.equals("W") ? pal.win : r.equals("L") ? pal.loss : pal.draw;
                roundRect(lx, ly - 0.95f, 1.35f, 1.3f, 0.3f, col);
                text(r, BOLD, 0.8f, 0, lx + 0.675f, ly - 0.02f, CENTER, 0xFF101010, 0);
                lx += 1.95f;
            }
            text(last.home + " " + last.homeScore + "–" + last.awayScore + " " + last.away, REG, 0.85f, 0, lx, ly, LEFT, pal.text, ix + iw - lx);
        }
    }

    private String shortStanding(United t) {
        String s = t.standing == null ? "" : t.standing;
        int sp = s.indexOf(' ');
        String pos = sp > 0 ? s.substring(0, sp) : s;
        if (pos.isEmpty()) return "";
        return pos + (t.points >= 0 ? " · " + t.points + " pts" : "");
    }

    private String countdown(long t) {
        long min = (t - nowMs) / 60000;
        if (min < 0) return "Kick-off";
        if (min < 60) return "Kick-off in " + min + " min";
        Calendar a = Calendar.getInstance(TimeZone.getDefault(), Locale.ROOT), b = Calendar.getInstance(TimeZone.getDefault(), Locale.ROOT);
        a.setTimeInMillis(nowMs);
        b.setTimeInMillis(t);
        long days = Math.round((midday(b) - midday(a)) / 86400000.0);
        if (days == 0) return "Today · in " + (min / 60) + " h " + (min % 60) + " min";
        if (days == 1) return "Tomorrow";
        return "In " + days + " days";
    }

    private static long midday(Calendar c) {
        Calendar x = (Calendar) c.clone();
        x.set(Calendar.HOUR_OF_DAY, 12); x.set(Calendar.MINUTE, 0); x.set(Calendar.SECOND, 0); x.set(Calendar.MILLISECOND, 0);
        return x.getTimeInMillis();
    }

    private void calendar(float x, float y, float w, float h) {
        panel(x, y, w, h);
        float pad = 1.2f, ix = x + pad, iw = w - 2 * pad;
        text("TODAY", BOLD, 0.78f, TRACK, ix, y + 1.7f, LEFT, pal.muted, 0);
        Calendar local = Calendar.getInstance(TimeZone.getDefault(), Locale.ROOT);
        local.setTimeInMillis(nowMs);
        long todayNoon = midday(local);
        float ly = y + 3.5f;
        int shown = 0, maxRows = (int) ((h - 2.6f) / 1.6f);
        for (Agenda.Event e : agenda) {
            if (e.end <= nowMs) continue;
            if (shown >= maxRows) break;
            Calendar c = Calendar.getInstance(TimeZone.getDefault(), Locale.ROOT);
            c.setTimeInMillis(e.start);
            boolean isToday = e.start <= nowMs || midday(c) == todayNoon;
            String when;
            if (e.allDay) when = isToday ? "All day" : "Tmrw";
            else {
                String[] t = Wx.clock(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), cfg.clock24h);
                when = (isToday ? "" : "Tmrw ") + t[0] + (t[1] != null ? t[1].toLowerCase(Locale.ROOT) : "");
            }
            float tw = text(when, BOLD, 0.9f, 0, ix, ly, LEFT, isToday ? pal.accent : pal.muted, 6.5f);
            float tx = ix + Math.max(5.2f, tw + 0.8f);
            text(e.title, REG, 0.95f, 0, tx, ly, LEFT, pal.text, ix + iw - tx);
            ly += 1.6f;
            shown++;
        }
        if (shown == 0) text("Nothing else today", REG, 0.95f, 0, ix, ly, LEFT, pal.muted, iw);
    }

    private void newsCard(float x, float y, float w, float h) {
        panel(x, y, w, h);
        float pad = 1.2f, ix = x + pad, iw = w - 2 * pad;
        text("UNITED NEWS", BOLD, 0.78f, TRACK, ix, y + 1.7f, LEFT, pal.muted, 0);
        if (news == null || news.isEmpty()) {
            text(news == null ? "Getting the headlines" + Wx.ELLIPSIS : "No United headlines right now.", REG, 0.95f, 0, ix, y + 3.6f, LEFT, pal.muted, iw);
            return;
        }
        float ly = y + 3.6f, bottom = y + h - 0.7f;
        for (int i = 0; i < news.size(); i++) {
            News.Item item = news.get(i);
            List<String> lines = wrap(item.title, BOLD, 0.95f, iw, 2);
            float need = (lines.size() - 1) * 1.25f + 1.4f;
            if (ly + need > bottom) break;
            float top = ly - 1.1f;
            for (int k = 0; k < lines.size(); k++) text(lines.get(k), BOLD, 0.95f, 0, ix, ly + k * 1.25f, LEFT, pal.text, iw);
            float my = ly + (lines.size() - 1) * 1.25f + 1.2f;
            String meta = item.source + (item.timeMs > 0 ? "  ·  " + News.ago(item.timeMs, nowMs) : "");
            text(meta, REG, 0.7f, 0, ix, my, LEFT, pal.muted, iw);
            hits.add(hit(x, top, w, my + 0.6f - top, "news:" + i));
            ly = my + 2.1f;
        }
    }

    // ------------------------------------------------------------------ primitives (grid units)

    private float X(float gx) { return ox + gx * u; }
    private float Y(float gy) { return oy + gy * u; }

    private Hit hit(float x, float y, float w, float h, String action) { return new Hit(X(x), Y(y), w * u, h * u, action); }

    private void panel(float x, float y, float w, float h) { roundRect(x, y, w, h, 0.9f, pal.panel); }

    private void roundRect(float x, float y, float w, float h, float r, int color) {
        p.fillRoundRect(X(x), Y(y), w * u, h * u, r * u, color);
    }

    private void rect(float x, float y, float w, float h, int color) {
        p.fillRect(X(x), Y(y), w * u, Math.max(1, h * u), color);
    }

    /** A small bell for the alarm line. */
    private void bell(float cx, float base, float size) {
        float k = size * u, bx = X(cx), by = Y(base);
        p.circle(bx, by - 0.38f * k, 0.36f * k, pal.accent, null, 0);
        p.fillRect(bx - 0.36f * k, by - 0.38f * k, 0.72f * k, 0.34f * k, pal.accent);
        p.fillRoundRect(bx - 0.48f * k, by - 0.1f * k, 0.96f * k, 0.14f * k, 0.07f * k, pal.accent);
        p.circle(bx, by + 0.12f * k, 0.11f * k, pal.accent, null, 0);
    }

    private float measure(String s, Painter.Font f, float size, float tracking) {
        return p.textWidth(s, f, size * u, tracking) / u;
    }

    /** Draws text with its baseline at y; shrinks (then truncates) to fit maxW if given. Returns width in units. */
    private float text(String s, Painter.Font f, float size, float tracking, float x, float y, int align, int color, float maxW) {
        if (s == null || s.isEmpty()) return 0;
        float px = size * u;
        float wpx = p.textWidth(s, f, px, tracking);
        if (maxW > 0 && wpx > maxW * u) {
            px *= Math.max(0.8f, maxW * u / wpx);
            wpx = p.textWidth(s, f, px, tracking);
            String base = s;
            int keep = base.length();
            while (wpx > maxW * u && keep > 1) {
                keep--;
                s = base.substring(0, keep).trim() + Wx.ELLIPSIS;
                wpx = p.textWidth(s, f, px, tracking);
            }
        }
        float tx = X(x);
        if (align == RIGHT) tx -= wpx; else if (align == CENTER) tx -= wpx / 2;
        p.text(s, f, px, tracking, tx, Y(y), color);
        return wpx / u;
    }

    /** {text, font, text, font...} on one baseline, shrunk to fit maxW. Bold pieces in full white, the rest muted. */
    private void run(Object[] pieces, float size, float x, float y, float maxW) {
        float total = 0;
        for (int i = 0; i < pieces.length; i += 2) total += measure((String) pieces[i], (Painter.Font) pieces[i + 1], size, 0);
        if (maxW > 0 && total > maxW) size *= maxW / total;
        for (int i = 0; i < pieces.length; i += 2) {
            Painter.Font f = (Painter.Font) pieces[i + 1];
            x += text((String) pieces[i], f, size, 0, x, y, LEFT, f == BOLD ? pal.text : pal.muted, 0);
        }
    }

    private List<String> wrap(String s, Painter.Font f, float size, float w, int maxLines) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String attempt = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && measure(attempt, f, size, 0) > w) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(attempt);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        while (lines.size() > maxLines) {
            String lastLine = lines.remove(lines.size() - 1);
            lines.set(lines.size() - 1, lines.get(lines.size() - 1) + " " + lastLine);
        }
        return lines;
    }
}
