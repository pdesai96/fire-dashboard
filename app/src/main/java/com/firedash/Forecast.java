package com.firedash;

import java.util.List;
import java.util.Map;

/** One Open-Meteo forecast response. Missing values are null. Times are local to the place. */
final class Forecast {
    int utcOffsetSeconds;
    long fetchedAt; // ms since epoch

    // current conditions
    Double curTemp, curFeels, curHumidity, curCode, curIsDay, curWind, curWindDir;

    // hourly
    String[] hTime = new String[0];
    Double[] hTemp, hFeels, hHumidity, hRain, hCode, hIsDay, hWind, hWindDir;

    // daily
    String[] dTime = new String[0];
    String[] dSunrise, dSunset;
    Double[] dCode, dMax, dMin, dRain;

    @SuppressWarnings("unchecked")
    static Forecast parse(String body, long fetchedAt) {
        Object root = Json.parse(body);
        if (!(root instanceof Map)) throw new IllegalArgumentException("Not an object");
        Map<String, Object> m = (Map<String, Object>) root;
        Object hourly = m.get("hourly"), daily = m.get("daily");
        if (!(hourly instanceof Map) || !(daily instanceof Map)) {
            Object reason = m.get("reason");
            throw new IllegalArgumentException(reason instanceof String ? (String) reason : "Unexpected reply");
        }
        Forecast f = new Forecast();
        f.fetchedAt = fetchedAt;
        Object off = m.get("utc_offset_seconds");
        f.utcOffsetSeconds = off instanceof Double ? ((Double) off).intValue() : 0;

        Object cur = m.get("current");
        if (cur instanceof Map) {
            Map<String, Object> c = (Map<String, Object>) cur;
            f.curTemp = num(c.get("temperature_2m"));
            f.curFeels = num(c.get("apparent_temperature"));
            f.curHumidity = num(c.get("relative_humidity_2m"));
            f.curCode = num(c.get("weather_code"));
            f.curIsDay = num(c.get("is_day"));
            f.curWind = num(c.get("wind_speed_10m"));
            f.curWindDir = num(c.get("wind_direction_10m"));
        }
        Map<String, Object> h = (Map<String, Object>) hourly;
        f.hTime = strings(h.get("time"));
        int n = f.hTime.length;
        f.hTemp = nums(h.get("temperature_2m"), n);
        f.hFeels = nums(h.get("apparent_temperature"), n);
        f.hHumidity = nums(h.get("relative_humidity_2m"), n);
        f.hRain = nums(h.get("precipitation_probability"), n);
        f.hCode = nums(h.get("weather_code"), n);
        f.hIsDay = nums(h.get("is_day"), n);
        f.hWind = nums(h.get("wind_speed_10m"), n);
        f.hWindDir = nums(h.get("wind_direction_10m"), n);

        Map<String, Object> d = (Map<String, Object>) daily;
        f.dTime = strings(d.get("time"));
        int k = f.dTime.length;
        f.dCode = nums(d.get("weather_code"), k);
        f.dMax = nums(d.get("temperature_2m_max"), k);
        f.dMin = nums(d.get("temperature_2m_min"), k);
        f.dRain = nums(d.get("precipitation_probability_max"), k);
        f.dSunrise = stringsN(d.get("sunrise"), k);
        f.dSunset = stringsN(d.get("sunset"), k);
        return f;
    }

    private static Double num(Object o) { return o instanceof Double ? (Double) o : null; }

    private static String[] strings(Object o) {
        if (!(o instanceof List)) return new String[0];
        List<?> l = (List<?>) o;
        String[] out = new String[l.size()];
        for (int i = 0; i < out.length; i++) out[i] = l.get(i) instanceof String ? (String) l.get(i) : "";
        return out;
    }

    private static String[] stringsN(Object o, int n) {
        String[] out = new String[n];
        if (o instanceof List) {
            List<?> l = (List<?>) o;
            for (int i = 0; i < n && i < l.size(); i++) out[i] = l.get(i) instanceof String ? (String) l.get(i) : null;
        }
        return out;
    }

    private static Double[] nums(Object o, int n) {
        Double[] out = new Double[n];
        if (o instanceof List) {
            List<?> l = (List<?>) o;
            for (int i = 0; i < n && i < l.size(); i++) out[i] = num(l.get(i));
        }
        return out;
    }

    static <T> T at(T[] a, int i) { return (a != null && i >= 0 && i < a.length) ? a[i] : null; }

    /** Index of key in a sorted list of ISO times, or of the entry just before it; -1 if past the end. */
    static int findIndex(String[] list, String key) {
        for (int i = 0; i < list.length; i++) {
            int c = list[i].compareTo(key);
            if (c >= 0) return c == 0 ? i : Math.max(0, i - 1);
        }
        return -1;
    }
}
