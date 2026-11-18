package com.firedash;

import android.content.Context;
import android.content.SharedPreferences;

/** Saves settings and the last forecast on the device. */
final class Prefs {
    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("dashboard", Context.MODE_PRIVATE);
    }

    static Config load(Context c) {
        SharedPreferences s = sp(c);
        Config d = new Config();
        Config cfg = new Config();
        cfg.place = s.getString("place", d.place);
        cfg.lat = parse(s.getString("lat", null), d.lat);
        cfg.lon = parse(s.getString("lon", null), d.lon);
        cfg.fahrenheit = s.getBoolean("fahrenheit", d.fahrenheit);
        cfg.windUnit = s.getString("windUnit", d.windUnit);
        cfg.clock24h = s.getBoolean("clock24h", d.clock24h);
        cfg.hourStep = s.getInt("hourStep", d.hourStep);
        cfg.note = s.getString("note", d.note);
        cfg.refreshMinutes = s.getInt("refreshMinutes", d.refreshMinutes);
        cfg.theme = s.getString("theme", d.theme);
        cfg.dimAtNight = s.getBoolean("dimAtNight", d.dimAtNight);
        cfg.startOnBoot = s.getBoolean("startOnBoot", d.startOnBoot);
        return cfg;
    }

    static void save(Context c, Config cfg) {
        sp(c).edit()
                .putString("place", cfg.place)
                .putString("lat", Double.toString(cfg.lat))
                .putString("lon", Double.toString(cfg.lon))
                .putBoolean("fahrenheit", cfg.fahrenheit)
                .putString("windUnit", cfg.windUnit)
                .putBoolean("clock24h", cfg.clock24h)
                .putInt("hourStep", cfg.hourStep)
                .putString("note", cfg.note)
                .putInt("refreshMinutes", cfg.refreshMinutes)
                .putString("theme", cfg.theme)
                .putBoolean("dimAtNight", cfg.dimAtNight)
                .putBoolean("startOnBoot", cfg.startOnBoot)
                .apply();
    }

    static void saveForecast(Context c, String tag, long fetchedAt, String body) {
        sp(c).edit().putString("cacheTag", tag).putLong("cacheTime", fetchedAt).putString("cacheBody", body).apply();
    }

    /** The last forecast for these settings, or null. */
    static Forecast loadForecast(Context c, String tag) {
        SharedPreferences s = sp(c);
        if (!tag.equals(s.getString("cacheTag", ""))) return null;
        String body = s.getString("cacheBody", null);
        if (body == null) return null;
        try {
            return Forecast.parse(body, s.getLong("cacheTime", 0));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static double parse(String v, double fallback) {
        if (v == null) return fallback;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
