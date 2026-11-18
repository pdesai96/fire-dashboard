package com.firedash;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Formatting and interpretation of forecast data. No Android dependencies. */
final class Wx {
    private Wx() {}

    static final String DEG = "°", MINUS = "–", NDASH = "–", MIDDOT = "·", ELLIPSIS = "…";
    static final String[] DAYS = { "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday" };
    static final String[] MONTHS = { "January", "February", "March", "April", "May", "June", "July",
            "August", "September", "October", "November", "December" };

    // ---------------------------------------------------------------- time

    /** Wall-clock time at the place: a UTC calendar shifted by the place's offset. */
    static Calendar placeTime(long nowMs, Integer offsetSeconds) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        long shift = offsetSeconds != null ? offsetSeconds * 1000L : TimeZone.getDefault().getOffset(nowMs);
        c.setTimeInMillis(nowMs + shift);
        return c;
    }

    static String dayKey(Calendar c) {
        return String.format(Locale.ROOT, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    static String hourKey(Calendar c) {
        return dayKey(c) + String.format(Locale.ROOT, "T%02d:00", c.get(Calendar.HOUR_OF_DAY));
    }

    static int isoHour(String s) { return Integer.parseInt(s.substring(11, 13)); }

    static int isoMinutes(String s) { return isoHour(s) * 60 + Integer.parseInt(s.substring(14, 16)); }

    static String isoWeekday(String s) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        c.clear();
        c.set(Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(5, 7)) - 1, Integer.parseInt(s.substring(8, 10)), 12, 0);
        return DAYS[c.get(Calendar.DAY_OF_WEEK) - 1];
    }

    /** {main, suffix-or-null}, e.g. {"18:42", null} or {"6:42", "PM"}. */
    static String[] clock(int h, int m, boolean h24) {
        if (h24) return new String[] { pad(h) + ":" + pad(m), null };
        int hh = h % 12 == 0 ? 12 : h % 12;
        return new String[] { hh + ":" + pad(m), h < 12 ? "AM" : "PM" };
    }

    /** "2026-11-18T21:00" -> "21:00", or "9pm" (compact) / "9:30pm". */
    static String isoTime(String s, boolean h24, boolean compact) {
        int h = isoHour(s);
        String m = s.substring(14, 16);
        if (h24) return pad(h) + ":" + m;
        int hh = h % 12 == 0 ? 12 : h % 12;
        return hh + ((compact && m.equals("00")) ? "" : ":" + m) + (h < 12 ? "am" : "pm");
    }

    static String pad(int n) { return n < 10 ? "0" + n : String.valueOf(n); }

    // ---------------------------------------------------------------- values

    static String deg(Double t) {
        if (t == null) return NDASH;
        long r = Math.round(t);
        return (r < 0 ? MINUS + (-r) : String.valueOf(r)) + DEG;
    }

    static String compass(Double dir) {
        if (dir == null) return "";
        String[] pts = { "N", "NE", "E", "SE", "S", "SW", "W", "NW" };
        double d = ((dir % 360) + 360) % 360;
        return pts[(int) (Math.round(d / 45) % 8)];
    }

    // ---------------------------------------------------------------- weather codes (WMO)

    static String kind(Double code) {
        if (code == null) return "cloud";
        int c = (int) Math.round(code);
        if (c == 0 || c == 1) return "clear";
        if (c == 2) return "partly";
        if (c == 3) return "cloud";
        if (c == 45 || c == 48) return "fog";
        if (c >= 51 && c <= 57) return "drizzle";
        if ((c >= 61 && c <= 67) || (c >= 80 && c <= 82)) return "rain";
        if ((c >= 71 && c <= 77) || c == 85 || c == 86) return "snow";
        if (c >= 95) return "storm";
        return "cloud";
    }

    static boolean isWet(String k) {
        return k.equals("rain") || k.equals("drizzle") || k.equals("snow") || k.equals("storm");
    }

    static String describe(Double code, boolean day) {
        if (code == null) return "";
        switch ((int) Math.round(code)) {
            case 0: return day ? "Sunny" : "Clear";
            case 1: return day ? "Mostly sunny" : "Mostly clear";
            case 2: return "Partly cloudy";
            case 3: return "Overcast";
            case 45: return "Fog";
            case 48: return "Freezing fog";
            case 51: return "Light drizzle";
            case 53: return "Drizzle";
            case 55: return "Heavy drizzle";
            case 56: case 57: return "Freezing drizzle";
            case 61: return "Light rain";
            case 63: return "Rain";
            case 65: return "Heavy rain";
            case 66: case 67: return "Freezing rain";
            case 71: return "Light snow";
            case 73: return "Snow";
            case 75: return "Heavy snow";
            case 77: return "Snow grains";
            case 80: return "Light showers";
            case 81: return "Showers";
            case 82: return "Heavy showers";
            case 85: return "Snow showers";
            case 86: return "Heavy snow showers";
            case 95: return "Thunderstorms";
            case 96: case 99: return "Thunder and hail";
            default: return "";
        }
    }

    // ---------------------------------------------------------------- interpretation

    /** What "now" looks like. */
    static final class Now {
        Double temp, feels, humidity, code, wind, windDir;
        boolean day;
    }

    /** The latest observation if it's recent, otherwise the forecast for this hour. */
    static Now now(Forecast f, int hi, double ageMinutes) {
        Now n = new Now();
        if (ageMinutes < 90 && f.curTemp != null) {
            n.temp = f.curTemp; n.feels = f.curFeels; n.humidity = f.curHumidity; n.code = f.curCode;
            n.wind = f.curWind; n.windDir = f.curWindDir; n.day = f.curIsDay != null && f.curIsDay == 1;
            return n;
        }
        if (hi < 0) return null;
        n.temp = Forecast.at(f.hTemp, hi); n.feels = Forecast.at(f.hFeels, hi); n.humidity = Forecast.at(f.hHumidity, hi);
        n.code = Forecast.at(f.hCode, hi); n.wind = Forecast.at(f.hWind, hi); n.windDir = Forecast.at(f.hWindDir, hi);
        Double d = Forecast.at(f.hIsDay, hi);
        n.day = d != null && d == 1;
        return n;
    }

    /** One line about the next 12 hours, e.g. "Rain likely from 21:00 (70%)." */
    static String summary(Forecast f, int hi, Now now, Config cfg) {
        if (hi < 0) return null;
        int last = Math.min(hi + 12, f.hTime.length - 1);
        List<String> parts = new ArrayList<>();
        String nowKind = now != null ? kind(now.code) : "cloud";
        if (isWet(nowKind)) {
            int stop = -1;
            for (int i = hi + 1; i <= last; i++) {
                Double p = Forecast.at(f.hRain, i);
                if ((p == null ? 0 : p) < 30 && !isWet(kind(Forecast.at(f.hCode, i)))) { stop = i; break; }
            }
            parts.add(stop > 0 ? "Should dry up around " + isoTime(f.hTime[stop], cfg.clock24h, true)
                    : (nowKind.equals("snow") ? "Snow" : "Wet") + " for the next 12 hours");
        } else {
            double maxP = 0;
            int maxI = -1, likely = -1;
            for (int i = hi + 1; i <= last; i++) {
                Double p = Forecast.at(f.hRain, i);
                if (p == null) continue;
                if (p >= 50 && likely < 0) likely = i;
                if (p > maxP) { maxP = p; maxI = i; }
            }
            if (likely >= 0) {
                parts.add(word(f, likely) + " likely from " + isoTime(f.hTime[likely], cfg.clock24h, true)
                        + " (" + Math.round(f.hRain[likely]) + "%)");
            } else if (maxI >= 0 && maxP >= 25) {
                parts.add("Chance of " + (word(f, maxI).equals("Snow") ? "snow" : "a shower") + " around "
                        + isoTime(f.hTime[maxI], cfg.clock24h, true) + " (" + Math.round(maxP) + "%)");
            } else {
                parts.add("Dry for the next 12 hours");
            }
        }
        Double min = null, max = null;
        for (int i = hi; i <= last; i++) {
            Double t = Forecast.at(f.hTemp, i);
            if (t == null) continue;
            if (min == null || t < min) min = t;
            if (max == null || t > max) max = t;
        }
        double frost = cfg.fahrenheit ? 33 : 1, hot = cfg.fahrenheit ? 86 : 30;
        if (min != null && min <= frost) parts.add("frost risk, down to " + deg(min));
        else if (max != null && max >= hot) parts.add("hot, up to " + deg(max));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) b.append(' ').append(MIDDOT).append(' ');
            b.append(parts.get(i));
        }
        return b.append('.').toString();
    }

    private static String word(Forecast f, int i) {
        return kind(Forecast.at(f.hCode, i)).equals("snow") ? "Snow" : "Rain";
    }

    /** Is it dark at the place right now? Uses today's sunrise/sunset, else a simple guess. */
    static boolean isNight(Forecast f, Calendar now) {
        int mins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        if (f != null) {
            int di = Forecast.findIndex(f.dTime, dayKey(now));
            String rise = Forecast.at(f.dSunrise, di), set = Forecast.at(f.dSunset, di);
            if (di >= 0 && rise != null && set != null && dayKey(now).equals(f.dTime[di])) {
                return mins < isoMinutes(rise) || mins >= isoMinutes(set);
            }
        }
        return mins < 7 * 60 || mins >= 19 * 60;
    }
}
