package com.firedash;

/** Colours (ARGB). Paper by day, ink by night. */
final class Theme {
    final int bg, ink, muted, band, bandText, rule, hair, rain, sun, snow, bolt;
    final boolean dark;

    private Theme(boolean dark, int bg, int ink, int muted, int band, int bandText, int rule, int hair,
                  int rain, int sun, int snow, int bolt) {
        this.dark = dark; this.bg = bg; this.ink = ink; this.muted = muted; this.band = band; this.bandText = bandText;
        this.rule = rule; this.hair = hair; this.rain = rain; this.sun = sun; this.snow = snow; this.bolt = bolt;
    }

    static final Theme LIGHT = new Theme(false,
            0xFFF4F0E8, 0xFF1B1A18, 0xFF6E6A62, 0xFF1B1A18, 0xFFF4F0E8, 0xFF1B1A18, 0xFFB9B3A7,
            0xFF2E6DA4, 0xFFD9930D, 0xFF6F9FC8, 0xFFD9930D);

    static final Theme DARK = new Theme(true,
            0xFF0B0B0A, 0xFFE8E2D4, 0xFF8D877B, 0xFF1F1D1A, 0xFFE8E2D4, 0xFFE8E2D4, 0xFF3A3731,
            0xFF5C9AD1, 0xFFE3AE45, 0xFF9CC0DE, 0xFFE3AE45);
}
