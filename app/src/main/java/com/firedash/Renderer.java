package com.firedash;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Lays out and draws the dashboard. Positions are in grid units that are scaled to the screen:
 * portrait uses a 37.5 x 55.6 grid, landscape a 64 x 40 grid with two columns.
 */
final class Renderer {
    // Inputs
    Config cfg = new Config();
    Forecast data;
    String lastError;
    boolean updating;
    boolean touch = true;
    Theme theme = Theme.LIGHT;
    long nowMs;

    private static final int LEFT = 0, CENTER = 1, RIGHT = 2;
    private static final Painter.Font REG = Painter.Font.REGULAR, BOLD = Painter.Font.BOLD, SERIF = Painter.Font.SERIF_ITALIC;
    private static final float LABEL_TRACK = 0.16f;

    private Painter p;
    private float u, ox, oy, gw;
    private int screenW;

    void draw(Painter painter, int w, int h) {
        p = painter;
        screenW = w;
        p.fillRect(0, 0, w, h, theme.bg);
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

    private void grid(int w, int h, float gridW, float gridH) {
        gw = gridW;
        u = Math.min(w / gridW, h / gridH);
        ox = (float) Math.floor((w - gridW * u) / 2);
        oy = (float) Math.floor((h - gridH * u) / 2);
    }

    // ------------------------------------------------------------------ layouts

    private void portrait(int w, int h, Calendar now, int hi, int di, double age) {
        grid(w, h, 37.5f, 55.6f);
        float x = 1.5f, cw = 34.5f;
        masthead(now);
        clock(now, x, 3.4f, cw - 9.6f, 11.6f, 10.6f);
        sunTable(now, di, gw - 1.5f - 9.6f, 3.4f + 5.8f);
        rule(x, 15.0f, cw, 0.32f, theme.rule);
        Wx.Now n = nowBlock(x, 15.32f, cw, 1f, hi, di, age, false);
        rule(x, 24.32f, cw, 0.14f, theme.hair);
        summary(x, 24.46f, cw, 1f, hi, n);
        rule(x, 28.06f, cw, 0.14f, theme.hair);
        hours(x, 28.2f, cw, 1f, hi);
        rule(x, 41.5f, cw, 0.14f, theme.hair);
        days(x, 41.64f, cw, 1f, di);
        rule(x, 52.34f, cw, 0.32f, theme.rule);
        footer(x, 54.45f, cw, age);
    }

    private void landscape(int w, int h, Calendar now, int hi, int di, double age) {
        grid(w, h, 64f, 40f);
        float lx = 1.5f, lw = 29f, rx = 33.5f, rw = 29f;
        masthead(now);
        clock(now, lx, 3.4f, lw, 13.4f, 11.6f);
        sunLine(now, di, lx, 18.3f, lw);
        rule(lx, 19.6f, lw, 0.32f, theme.rule);
        Wx.Now n = nowBlock(lx, 19.92f, lw, 1f, hi, di, age, true);
        rule(lx, 29.2f, lw, 0.14f, theme.hair);
        summary(lx, 29.34f, lw, 1f, hi, n);
        rule(32.0f, 4.6f, 0.14f, 31.4f, theme.hair);
        hours(rx, 3.9f, rw, 1.22f, hi);
        rule(rx, 20.1f, rw, 0.14f, theme.hair);
        days(rx, 20.3f, rw, 1.22f, di);
        rule(lx, 36.9f, gw - 3f, 0.32f, theme.rule);
        footer(lx, 38.85f, gw - 3f, age);
    }

    // ------------------------------------------------------------------ sections

    private void masthead(Calendar now) {
        // The band runs edge to edge, from the very top of the screen.
        p.fillRect(0, 0, screenW, Math.round(oy + 3.4f * u), theme.band);
        float base = 1.7f + 0.36f * 1.15f;
        String day = Wx.DAYS[now.get(Calendar.DAY_OF_WEEK) - 1].toUpperCase(Locale.ROOT);
        String date = now.get(Calendar.DAY_OF_MONTH) + " " + Wx.MONTHS[now.get(Calendar.MONTH)].toUpperCase(Locale.ROOT);
        float dw = text(day, BOLD, 1.15f, 0.16f, 1.5f, base, LEFT, theme.bandText, 0);
        text(date, REG, 1.15f, 0.16f, 1.5f + dw + 1.1f, base, LEFT, theme.bandText, 0);
        text(cfg.place.toUpperCase(Locale.ROOT), REG, 0.82f, 0.24f, gw - 1.5f, 1.7f + 0.36f * 0.82f, RIGHT, theme.bandText, 11f);
    }

    private void clock(Calendar now, float x, float y, float w, float h, float maxSize) {
        String[] c = Wx.clock(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), cfg.clock24h);
        float size = maxSize;
        float avail = w * 0.94f;
        float need = measure(c[0], BOLD, size, 0) + (c[1] != null ? 0.3f + measure(c[1], BOLD, size * 0.2f, 0.06f) : 0);
        if (need > avail) size *= avail / need;
        float base = y + h / 2f + 0.357f * size;
        float cw = text(c[0], BOLD, size, -0.02f, x - 0.04f * size, base, LEFT, theme.ink, 0);
        if (c[1] != null) text(c[1], BOLD, size * 0.2f, 0.06f, x - 0.04f * size + cw + 0.3f, base, LEFT, theme.ink, 0);
    }

    /** Sunrise / sunset / daylight, or null. */
    private String[][] sunRows(Calendar now, int di) {
        if (data == null || di < 0) return null;
        String rise = Forecast.at(data.dSunrise, di), set = Forecast.at(data.dSunset, di);
        if (rise == null || set == null) return null;
        int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        String riseVal = Wx.isoTime(rise, cfg.clock24h, false);
        String next = Forecast.at(data.dSunrise, di + 1);
        if (nowMin > Wx.isoMinutes(set) && next != null) riseVal = Wx.isoTime(next, cfg.clock24h, false);
        int len = Wx.isoMinutes(set) - Wx.isoMinutes(rise);
        if (len < 0) len += 1440;
        return new String[][] {
                { "SUNRISE", riseVal },
                { "SUNSET", Wx.isoTime(set, cfg.clock24h, false) },
                { "DAYLIGHT", (len / 60) + "h " + Wx.pad(len % 60) + "m" } };
    }

    private void sunTable(Calendar now, int di, float x, float mid) {
        String[][] rows = sunRows(now, di);
        if (rows == null) return;
        for (int i = 0; i < 3; i++) {
            float y = mid + (i - 1) * 2.1f + 0.36f;
            text(rows[i][0], REG, 0.72f, LABEL_TRACK, x, y, LEFT, theme.muted, 0);
            text(rows[i][1], BOLD, 1.05f, 0, gw - 1.5f, y, RIGHT, theme.ink, 0);
        }
    }

    private void sunLine(Calendar now, int di, float x, float y, float w) {
        String[][] rows = sunRows(now, di);
        if (rows == null) return;
        float colw = w / 3f;
        for (int i = 0; i < 3; i++) {
            float cx = x + i * colw;
            float lw = text(rows[i][0], REG, 0.72f, LABEL_TRACK, cx, y, LEFT, theme.muted, 0);
            text(rows[i][1], BOLD, 1.05f, 0, cx + lw + 0.6f, y, LEFT, theme.ink, colw - lw - 0.9f);
        }
    }

    private Wx.Now nowBlock(float x, float y, float w, float s, int hi, int di, double age, boolean compact) {
        float mid = y + 4.5f * s;
        if (data == null) {
            if (lastError != null) {
                text("Can’t reach the weather service (" + lastError + ").", BOLD, 1.0f * s, 0, x, mid - 0.4f * s, LEFT, theme.ink, w);
                text("Check the Wi-Fi. Retrying in a couple of minutes" + Wx.ELLIPSIS, REG, 1.0f * s, 0, x, mid + 1.2f * s, LEFT, theme.muted, w);
            } else {
                text("Loading weather" + Wx.ELLIPSIS, REG, 1.1f * s, 0, x, mid + 0.4f * s, LEFT, theme.muted, w);
            }
            return null;
        }
        Wx.Now n = Wx.now(data, hi, age);
        if (n == null) {
            text("The saved forecast is out of date. Tap to refresh.", REG, 1.0f * s, 0, x, mid + 0.4f * s, LEFT, theme.muted, w);
            return null;
        }
        float iconSize = 6.6f * s;
        Icons.draw(p, Icons.name(n.code, n.day), ox + x * u, oy + (mid - iconSize / 2) * u, iconSize * u, theme);

        String temp = Wx.deg(n.temp);
        float size = 6.2f * s, maxTemp = (compact ? 9.4f : 10.6f) * s;
        float tw = measure(temp, BOLD, size, 0);
        if (tw > maxTemp) { size *= maxTemp / tw; tw = maxTemp; }
        float tx = x + 7.6f * s;
        text(temp, BOLD, size, -0.03f, tx, mid + 0.357f * size, LEFT, theme.ink, 0);

        Double hiT = Forecast.at(data.dMax, di), loT = Forecast.at(data.dMin, di);
        float dx = Math.max(x + (compact ? 17.4f : 19.0f) * s, tx + tw + 0.9f * s);
        float maxw = x + w - dx;
        String wind = n.wind != null ? Math.round(n.wind) + " " + cfg.windLabel() : Wx.NDASH;
        String dir = Wx.compass(n.windDir);
        String hum = n.humidity != null ? Math.round(n.humidity) + "%" : Wx.NDASH;
        float ds = 1.0f * s;
        if (!compact) {
            text(Wx.describe(n.code, n.day), BOLD, 1.45f * s, 0, dx, mid - 1.9f * s, LEFT, theme.ink, maxw);
            run(new Object[] { "Feels like ", REG, Wx.deg(n.feels), BOLD }, ds, dx, mid + 0.15f * s, maxw);
            run(new Object[] { "High ", REG, Wx.deg(hiT), BOLD, "   Low ", REG, Wx.deg(loT), BOLD }, ds, dx, mid + 1.65f * s, maxw);
            run(new Object[] { "Wind ", REG, wind, BOLD, " " + dir + "  " + Wx.MIDDOT + "  Hum. ", REG, hum, BOLD }, ds, dx, mid + 3.15f * s, maxw);
        } else {
            text(Wx.describe(n.code, n.day), BOLD, 1.45f * s, 0, dx, mid - 2.6f * s, LEFT, theme.ink, maxw);
            run(new Object[] { "Feels like ", REG, Wx.deg(n.feels), BOLD }, ds, dx, mid - 0.6f * s, maxw);
            run(new Object[] { "High ", REG, Wx.deg(hiT), BOLD, "   Low ", REG, Wx.deg(loT), BOLD }, ds, dx, mid + 0.85f * s, maxw);
            run(new Object[] { "Wind ", REG, wind, BOLD, " " + dir, REG }, ds, dx, mid + 2.3f * s, maxw);
            run(new Object[] { "Humidity ", REG, hum, BOLD }, ds, dx, mid + 3.75f * s, maxw);
        }
        return n;
    }

    private void summary(float x, float y, float w, float s, int hi, Wx.Now n) {
        if (data == null) return;
        String line = Wx.summary(data, hi, n, cfg);
        if (line == null) return;
        float size = 1.32f * s;
        List<String> lines = wrap(line, SERIF, size, w, 2);
        float mid = y + 1.8f * s + 0.36f * size;
        if (lines.size() == 1) {
            text(lines.get(0), SERIF, size, 0, x, mid, LEFT, theme.ink, w);
        } else {
            text(lines.get(0), SERIF, size, 0, x, mid - 0.8f * s, LEFT, theme.ink, w);
            text(lines.get(1), SERIF, size, 0, x, mid + 0.8f * s, LEFT, theme.ink, w);
        }
    }

    private void hours(float x, float y, float w, float s, int hi) {
        int step = Math.max(1, cfg.hourStep);
        float lab = 0.72f * s;
        text(("Next " + step * 8 + " hours").toUpperCase(Locale.ROOT), REG, lab, LABEL_TRACK, x, y + 1.75f * s, LEFT, theme.muted, 0);
        text("RAIN CHANCE", REG, lab, LABEL_TRACK, x + w, y + 1.75f * s, RIGHT, theme.muted, 0);
        if (data == null || hi < 0) return;
        float g = y + 2.2f * s, colw = w / 8f;
        int j = hi + 1;
        while (j < data.hTime.length && Wx.isoHour(data.hTime[j]) % step != 0) j++;
        for (int n = 0; j < data.hTime.length && n < 8; j += step, n++) {
            float cx = x + (n + 0.5f) * colw;
            Double pr = Forecast.at(data.hRain, j);
            double pct = pr == null ? 0 : Math.max(0, Math.min(100, pr));
            Double isDay = Forecast.at(data.hIsDay, j);
            text(Wx.isoTime(data.hTime[j], cfg.clock24h, true), BOLD, 0.92f * s, 0, cx, g + 1.05f * s, CENTER, theme.ink, colw * 0.96f);
            float is = 2.7f * s;
            Icons.draw(p, Icons.name(Forecast.at(data.hCode, j), isDay != null && isDay == 1), ox + (cx - is / 2) * u, oy + (g + 1.35f * s) * u, is * u, theme);
            text(Wx.deg(Forecast.at(data.hTemp, j)), BOLD, 1.35f * s, 0, cx, g + 5.4f * s, CENTER, theme.ink, colw * 0.96f);
            // rain-chance bar
            float bw = 1.3f * s * u, bh = 2.4f * s * u, bx = ox + cx * u - bw / 2, by = oy + (g + 6.25f * s) * u;
            float border = Math.max(1f, 0.14f * s * u);
            float fill = (float) ((bh - 2 * border) * pct / 100.0);
            if (fill > 0.5f) p.fillRect(bx + border, by + bh - border - fill, bw - 2 * border, fill, theme.rain);
            p.strokeRect(bx, by, bw, bh, border, theme.muted);
            text(Math.round(pct) + "%", REG, 0.78f * s, 0, cx, g + 9.6f * s, CENTER, theme.muted, 0);
        }
    }

    private void days(float x, float y, float w, float s, int di) {
        float lab = 0.72f * s;
        text("NEXT 5 DAYS", REG, lab, LABEL_TRACK, x, y + 1.76f * s, LEFT, theme.muted, 0);
        text("HIGH / LOW", REG, lab, LABEL_TRACK, x + w, y + 1.76f * s, RIGHT, theme.muted, 0);
        if (data == null || di < 0) return;
        float g = y + 2.2f * s, colw = w / 5f;
        for (int n = 0, j = di + 1; j < data.dTime.length && n < 5; j++, n++) {
            float cx = x + (n + 0.5f) * colw;
            text(Wx.isoWeekday(data.dTime[j]).substring(0, 3).toUpperCase(Locale.ROOT), BOLD, 0.95f * s, 0.14f, cx, g + 1.11f * s, CENTER, theme.ink, 0);
            float is = 3.2f * s;
            Icons.draw(p, Icons.name(Forecast.at(data.dCode, j), true), ox + (cx - is / 2) * u, oy + (g + 1.71f * s) * u, is * u, theme);
            runCentered(new Object[] { Wx.deg(Forecast.at(data.dMax, j)), BOLD, " " + Wx.deg(Forecast.at(data.dMin, j)), REG },
                    1.3f * s, cx, g + 6.41f * s, colw * 0.92f);
            Double pr = Forecast.at(data.dRain, j);
            text("Rain " + (pr != null ? Math.round(pr) + "%" : Wx.NDASH), REG, 0.78f * s, 0, cx, g + 7.76f * s, CENTER, theme.muted, 0);
        }
    }

    private void footer(float x, float y, float w, double age) {
        if (cfg.note != null && !cfg.note.trim().isEmpty()) {
            text(cfg.note.trim(), BOLD, 0.9f, 0, x, y, LEFT, theme.ink, w * 0.6f);
        } else {
            String hint = touch ? "Tap to refresh  " + Wx.MIDDOT + "  Hold for settings" : "Press Menu for settings";
            text(hint, REG, 0.82f, 0, x, y, LEFT, theme.muted, w * 0.6f);
        }
        String right = null;
        Painter.Font f = REG;
        int color = theme.muted;
        if (updating) {
            right = "Updating" + Wx.ELLIPSIS;
            f = BOLD; color = theme.ink;
        } else if (data != null) {
            if (age > cfg.refreshMinutes * 3) {
                String ago = age < 120 ? Math.round(age) + " min" : Math.round(age / 60) + " h";
                right = "Offline " + Wx.MIDDOT + " weather from " + ago + " ago";
                f = BOLD; color = theme.ink;
            } else {
                Calendar up = Wx.placeTime(data.fetchedAt, data.utcOffsetSeconds);
                String[] c = Wx.clock(up.get(Calendar.HOUR_OF_DAY), up.get(Calendar.MINUTE), cfg.clock24h);
                right = "Updated " + c[0] + (c[1] != null ? " " + c[1] : "") + (lastError != null ? " " + Wx.MIDDOT + " retrying" : "");
            }
        } else if (lastError != null) {
            right = "Offline";
            f = BOLD; color = theme.ink;
        }
        if (right != null) text(right, f, 0.82f, 0, x + w, y, RIGHT, color, w * 0.38f);
    }

    // ------------------------------------------------------------------ primitives (grid units)

    private void rect(float x, float y, float w, float h, int color) {
        float x1 = Math.round(ox + x * u), y1 = Math.round(oy + y * u);
        float x2 = Math.round(ox + (x + w) * u), y2 = Math.round(oy + (y + h) * u);
        p.fillRect(x1, y1, Math.max(1, x2 - x1), Math.max(1, y2 - y1), color);
    }

    private void rule(float x, float y, float w, float h, int color) { rect(x, y, w, h, color); }

    private float measure(String s, Painter.Font f, float size, float tracking) {
        return p.textWidth(s, f, size * u, tracking) / u;
    }

    /** Draws text with its baseline at y; shrinks (then truncates) to fit maxW if given. Returns width in units. */
    private float text(String s, Painter.Font f, float size, float tracking, float x, float y, int align, int color, float maxW) {
        if (s == null || s.isEmpty()) return 0;
        float px = size * u;
        float wpx = p.textWidth(s, f, px, tracking);
        if (maxW > 0 && wpx > maxW * u) {
            px *= Math.max(0.75f, maxW * u / wpx);
            wpx = p.textWidth(s, f, px, tracking);
            String base = s;
            int keep = base.length();
            while (wpx > maxW * u && keep > 1) {
                keep--;
                s = base.substring(0, keep).trim() + Wx.ELLIPSIS;
                wpx = p.textWidth(s, f, px, tracking);
            }
        }
        float X = ox + x * u;
        if (align == RIGHT) X -= wpx; else if (align == CENTER) X -= wpx / 2;
        p.text(s, f, px, tracking, X, oy + y * u, color);
        return wpx / u;
    }

    /** A run of {text, font, text, font...} pieces on one baseline, left-aligned, shrunk to fit maxW. */
    private void run(Object[] pieces, float size, float x, float y, float maxW) {
        float total = runWidth(pieces, size);
        if (maxW > 0 && total > maxW) size *= maxW / total;
        for (int i = 0; i < pieces.length; i += 2) {
            x += text((String) pieces[i], (Painter.Font) pieces[i + 1], size, 0, x, y, LEFT, pieceColor(pieces, i), 0);
        }
    }

    private void runCentered(Object[] pieces, float size, float cx, float y, float maxW) {
        float total = runWidth(pieces, size);
        if (total > maxW) { size *= maxW / total; total = maxW; }
        float x = cx - total / 2;
        for (int i = 0; i < pieces.length; i += 2) {
            x += text((String) pieces[i], (Painter.Font) pieces[i + 1], size, 0, x, y, LEFT, pieceColor(pieces, i), 0);
        }
    }

    private int pieceColor(Object[] pieces, int i) {
        return pieces[i + 1] == BOLD ? theme.ink : theme.muted;
    }

    private float runWidth(Object[] pieces, float size) {
        float total = 0;
        for (int i = 0; i < pieces.length; i += 2) total += measure((String) pieces[i], (Painter.Font) pieces[i + 1], size, 0);
        return total;
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
