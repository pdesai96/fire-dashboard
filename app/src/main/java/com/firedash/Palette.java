package com.firedash;

import java.util.Calendar;

/** Colours for one frame: a vertical background gradient with white text on soft panels. */
final class Palette {
    int top, bottom;
    int text = 0xFFFFFFFF, muted = 0xB3FFFFFF, faint = 0x66FFFFFF;
    int panel = 0x38000000, panelLine = 0x22FFFFFF;
    int iconStroke = 0xFFF5F7FA, iconFill = 0xFFF5F7FA, sun = 0xFFFFC94A, rain = 0xFF8FD0FF, snow = 0xFFE3F2FF, bolt = 0xFFFFC94A;
    int accent = 0xFFFFC94A, live = 0xFFFF5A5F, win = 0xFF4CD08A, draw = 0xFFB8BEC8, loss = 0xFFFF6B6B;

    static Palette of(int top, int bottom) {
        Palette p = new Palette();
        p.top = top;
        p.bottom = bottom;
        return p;
    }

    /**
     * "Follow the sky": colours that move with the sun. Uses today's sunrise and sunset
     * (minutes after midnight), or 07:30 / 16:30 if unknown.
     */
    static Palette sky(Calendar now, int sunrise, int sunset) {
        int m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        if (sunrise <= 0 || sunset <= sunrise) { sunrise = 450; sunset = 990; }
        int mid = (sunrise + sunset) / 2;
        // keyframes: minute, top colour, bottom colour
        int[][] k = {
                { 0, 0xFF070B1A, 0xFF141E3A },
                { sunrise - 75, 0xFF0B1230, 0xFF1D2650 },
                { sunrise - 25, 0xFF2B2F63, 0xFF8A4F7D },
                { sunrise + 10, 0xFF4A5A9A, 0xFFF0915F },
                { sunrise + 90, 0xFF3D78C2, 0xFF9CC8EC },
                { mid, 0xFF2C6CC0, 0xFF6FB1E6 },
                { sunset - 80, 0xFF3F66AE, 0xFFF0B46C },
                { sunset - 5, 0xFF3A3470, 0xFFE8735A },
                { sunset + 40, 0xFF1C1F4A, 0xFF4E3570 },
                { sunset + 110, 0xFF0A0F26, 0xFF18213F },
                { 1440, 0xFF070B1A, 0xFF141E3A },
        };
        for (int i = 0; i < k.length - 1; i++) {
            if (m >= k[i][0] && m < k[i + 1][0]) {
                float t = (m - k[i][0]) / (float) Math.max(1, k[i + 1][0] - k[i][0]);
                return of(mix(k[i][1], k[i + 1][1], t), mix(k[i][2], k[i + 1][2], t));
            }
        }
        return of(k[0][1], k[0][2]);
    }

    static int mix(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | (Math.round(ar + (br - ar) * t) << 16) | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }
}
