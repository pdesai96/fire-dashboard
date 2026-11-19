package com.firedash;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

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
        cfg.autoLocation = s.getBoolean("autoLocation", d.autoLocation);
        cfg.locatedAt = s.getLong("locatedAt", d.locatedAt);
        cfg.fahrenheit = s.getBoolean("fahrenheit", d.fahrenheit);
        cfg.windUnit = s.getString("windUnit", d.windUnit);
        cfg.clock24h = s.getBoolean("clock24h", d.clock24h);
        cfg.hourStep = s.getInt("hourStep", d.hourStep);
        cfg.note = s.getString("note", d.note);
        cfg.refreshMinutes = s.getInt("refreshMinutes", d.refreshMinutes);
        cfg.background = s.getString("background", d.background);
        cfg.briefing = s.getBoolean("briefing", d.briefing);
        cfg.icsUrl = s.getString("icsUrl", d.icsUrl);
        cfg.dimAtNight = s.getBoolean("dimAtNight", d.dimAtNight);
        cfg.startOnBoot = s.getBoolean("startOnBoot", d.startOnBoot);
        return cfg;
    }

    static void save(Context c, Config cfg) {
        sp(c).edit()
                .putString("place", cfg.place)
                .putString("lat", Double.toString(cfg.lat))
                .putString("lon", Double.toString(cfg.lon))
                .putBoolean("autoLocation", cfg.autoLocation)
                .putLong("locatedAt", cfg.locatedAt)
                .putBoolean("fahrenheit", cfg.fahrenheit)
                .putString("windUnit", cfg.windUnit)
                .putBoolean("clock24h", cfg.clock24h)
                .putInt("hourStep", cfg.hourStep)
                .putString("note", cfg.note)
                .putInt("refreshMinutes", cfg.refreshMinutes)
                .putString("background", cfg.background)
                .putBoolean("briefing", cfg.briefing)
                .putString("icsUrl", cfg.icsUrl)
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

    // ---- alarms
    static List<Alarm> alarms(Context c) { return Alarm.parseAll(sp(c).getString("alarms", "")); }

    static void saveAlarms(Context c, List<Alarm> alarms) { sp(c).edit().putString("alarms", Alarm.serialize(alarms)).apply(); }

    static long snoozeUntil(Context c) { return sp(c).getLong("snoozeUntil", 0); }

    static void setSnooze(Context c, long t) { sp(c).edit().putLong("snoozeUntil", t).apply(); }

    // ---- small caches and state
    static String get(Context c, String key) { return sp(c).getString(key, null); }

    static void put(Context c, String key, String value) { sp(c).edit().putString(key, value).apply(); }

    static long getLong(Context c, String key) { return sp(c).getLong(key, 0); }

    static void putLong(Context c, String key, long value) { sp(c).edit().putLong(key, value).apply(); }

    // ---- JARVIS (the assistant server on the Raspberry Pi)
    /** Server address, e.g. http://192.168.1.50:8787, or null if never set. */
    static String jarvisUrl(Context c) { return sp(c).getString("jarvisUrl", null); }

    /** This tablet's device token, or null when not paired. Only ever sent to jarvisUrl. */
    static String jarvisToken(Context c) { return sp(c).getString("jarvisToken", null); }

    static String jarvisName(Context c) { return sp(c).getString("jarvisName", "JARVIS"); }

    static boolean jarvisSpeak(Context c) { return sp(c).getBoolean("jarvisSpeak", true); }

    static void setJarvisSpeak(Context c, boolean on) { sp(c).edit().putBoolean("jarvisSpeak", on).apply(); }

    static void setJarvis(Context c, String url, String token, String name) {
        sp(c).edit().putString("jarvisUrl", url).putString("jarvisToken", token)
                .putString("jarvisName", name == null || name.isEmpty() ? "JARVIS" : name).apply();
    }

    static void forgetJarvisToken(Context c) { sp(c).edit().remove("jarvisToken").apply(); }

    /** A random id that pairs this tablet with the Alexa relay. */
    static String channel(Context c) {
        String id = sp(c).getString("channel", null);
        if (id == null) {
            java.security.SecureRandom r = new java.security.SecureRandom();
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 16; i++) b.append(String.format(java.util.Locale.ROOT, "%02x", r.nextInt(256)));
            id = b.toString();
            sp(c).edit().putString("channel", id).apply();
        }
        return id;
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
