package com.firedash;

import java.util.Arrays;

/** Line-drawn weather icons, designed on a 64x64 grid. */
final class Icons {
    private Icons() {}

    static final int MOVE = 0, LINE = 1, CUBIC = 2, CLOSE = 3;

    /** A path as a flat list of commands: MOVE x y | LINE x y | CUBIC x1 y1 x2 y2 x y | CLOSE. */
    static final class PathData {
        final float[] c;
        PathData(float[] c) { this.c = c; }

        PathData transform(float tx, float ty, float s) {
            float[] o = Arrays.copyOf(c, c.length);
            int i = 0;
            while (i < o.length) {
                int op = (int) o[i++];
                int n = op == MOVE || op == LINE ? 2 : op == CUBIC ? 6 : 0;
                for (int j = 0; j < n; j += 2) {
                    o[i + j] = o[i + j] * s + tx;
                    o[i + j + 1] = o[i + j + 1] * s + ty;
                }
                i += n;
            }
            return new PathData(o);
        }
    }

    static final class Builder {
        private float[] a = new float[64];
        private int n = 0;
        private boolean open = false;

        private void add(float... v) {
            if (n + v.length > a.length) a = Arrays.copyOf(a, Math.max(a.length * 2, n + v.length));
            System.arraycopy(v, 0, a, n, v.length);
            n += v.length;
        }
        Builder moveTo(float x, float y) { add(MOVE, x, y); open = true; return this; }
        Builder lineTo(float x, float y) { add(LINE, x, y); return this; }
        Builder close() { add(CLOSE); open = false; return this; }

        /** Circular arc around (cx, cy) from angle a0 to a1 (degrees, y pointing down), as cubic curves. */
        Builder arc(float cx, float cy, float r, float a0, float a1) {
            double s = Math.toRadians(a0), e = Math.toRadians(a1);
            float x0 = (float) (cx + r * Math.cos(s)), y0 = (float) (cy + r * Math.sin(s));
            if (open) lineTo(x0, y0); else moveTo(x0, y0);
            int segs = (int) Math.ceil(Math.abs(e - s) / (Math.PI / 2));
            double step = (e - s) / segs, k = 4.0 / 3.0 * Math.tan(step / 4);
            for (int i = 0; i < segs; i++) {
                double p = s + i * step, q = p + step;
                double c1x = Math.cos(p) - k * Math.sin(p), c1y = Math.sin(p) + k * Math.cos(p);
                double c2x = Math.cos(q) + k * Math.sin(q), c2y = Math.sin(q) - k * Math.cos(q);
                add(CUBIC, (float) (cx + r * c1x), (float) (cy + r * c1y), (float) (cx + r * c2x), (float) (cy + r * c2y),
                        (float) (cx + r * Math.cos(q)), (float) (cy + r * Math.sin(q)));
            }
            return this;
        }
        PathData build() { return new PathData(Arrays.copyOf(a, n)); }
    }

    static final PathData CLOUD = new Builder()
            .moveTo(17, 46).lineTo(47, 46)
            .arc(47, 36, 10, 90, -90)
            .arc(33.8f, 30.66f, 14, -19.4f, -156.1f)
            .arc(19, 35.5f, 10.69f, -79.2f, -259.2f)
            .close().build();
    static final PathData CLOUD_HIGH = CLOUD.transform(0, -7, 1);
    static final PathData CLOUD_PARTLY = CLOUD.transform(4, 8, 1);
    static final PathData MOON = new Builder()
            .arc(37f, 32f, 20, -87.1f, -323.1f)
            .arc(45.5f, 28f, 17.67f, 64.9f, 244.9f)
            .close().build();
    static final PathData MOON_SMALL = MOON.transform(-6, -4, 0.7f);
    static final PathData BOLT = new Builder()
            .moveTo(35, 40).lineTo(25, 52).lineTo(31, 52).lineTo(27, 62).lineTo(40, 48).lineTo(34, 48).lineTo(38, 40)
            .close().build();

    static String name(Double code, boolean day) {
        String k = Wx.kind(code);
        if (k.equals("clear") || k.equals("partly")) return k + (day ? "-day" : "-night");
        return k;
    }

    /** Draws icon `name` with its top-left corner at (x, y), size pixels square. */
    static void draw(Painter p, String name, float x, float y, float size, Theme t) {
        float k = size / 64f, sw = 4 * k;
        switch (name) {
            case "clear-day":
                sun(p, x, y, k, 32, 32, 11, 18, 25, t.sun, sw);
                break;
            case "clear-night":
                p.path(MOON, x, y, k, null, t.ink, sw);
                break;
            case "partly-day":
                sun(p, x, y, k, 23, 23, 8, 13, 18, t.sun, sw);
                p.path(CLOUD_PARTLY, x, y, k, t.bg, t.ink, sw);
                break;
            case "partly-night":
                p.path(MOON_SMALL, x, y, k, null, t.ink, sw * 0.8f);
                p.path(CLOUD_PARTLY, x, y, k, t.bg, t.ink, sw);
                break;
            case "fog":
                line(p, x, y, k, 12, 22, 52, 22, sw, t.ink);
                line(p, x, y, k, 8, 32, 48, 32, sw, t.ink);
                line(p, x, y, k, 16, 42, 56, 42, sw, t.ink);
                line(p, x, y, k, 12, 52, 44, 52, sw, t.ink);
                break;
            case "rain":
                p.path(CLOUD_HIGH, x, y, k, t.bg, t.ink, sw);
                line(p, x, y, k, 25, 46, 21, 56, sw, t.rain);
                line(p, x, y, k, 35, 46, 31, 56, sw, t.rain);
                line(p, x, y, k, 45, 46, 41, 56, sw, t.rain);
                break;
            case "drizzle":
                p.path(CLOUD_HIGH, x, y, k, t.bg, t.ink, sw);
                line(p, x, y, k, 24, 47, 23, 50, sw, t.rain);
                line(p, x, y, k, 34, 47, 33, 50, sw, t.rain);
                line(p, x, y, k, 44, 47, 43, 50, sw, t.rain);
                line(p, x, y, k, 29, 55, 28, 58, sw, t.rain);
                line(p, x, y, k, 39, 55, 38, 58, sw, t.rain);
                break;
            case "snow":
                p.path(CLOUD_HIGH, x, y, k, t.bg, t.ink, sw);
                float[][] dots = { { 23, 49 }, { 33, 52 }, { 43, 49 }, { 28, 58 }, { 38, 58 } };
                for (float[] d : dots) p.circle(x + d[0] * k, y + d[1] * k, 2.8f * k, t.snow, null, 0);
                break;
            case "storm":
                p.path(CLOUD_HIGH, x, y, k, t.bg, t.ink, sw);
                p.path(BOLT, x, y, k, t.bolt, t.bolt, 2 * k);
                break;
            default: // cloud
                p.path(CLOUD, x, y, k, t.bg, t.ink, sw);
        }
    }

    private static void line(Painter p, float x, float y, float k, float x1, float y1, float x2, float y2, float sw, int color) {
        p.line(x + x1 * k, y + y1 * k, x + x2 * k, y + y2 * k, sw, color);
    }

    private static void sun(Painter p, float x, float y, float k, float cx, float cy, float r, float r1, float r2, int color, float sw) {
        p.circle(x + cx * k, y + cy * k, r * k, null, color, sw);
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            float c = (float) Math.cos(a), s = (float) Math.sin(a);
            line(p, x, y, k, cx + c * r1, cy + s * r1, cx + c * r2, cy + s * r2, sw, color);
        }
    }
}
