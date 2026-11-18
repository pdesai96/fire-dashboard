package com.firedash;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Talks to Open-Meteo (https://open-meteo.com): free for non-commercial use, no API key.
 * Plain HTTP is tried first: Fire OS 5 is too old to trust some modern HTTPS certificates.
 */
final class WeatherClient {
    private WeatherClient() {}

    static final class Place {
        final String name, label;
        final double lat, lon;
        Place(String name, String label, double lat, double lon) { this.name = name; this.label = label; this.lat = lat; this.lon = lon; }
    }

    static String forecastUrl(Config c, String scheme) {
        return scheme + "://api.open-meteo.com/v1/forecast"
                + String.format(Locale.ROOT, "?latitude=%.4f&longitude=%.4f", c.lat, c.lon)
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day,wind_speed_10m,wind_direction_10m"
                + "&hourly=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation_probability,weather_code,is_day,wind_speed_10m,wind_direction_10m"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset"
                + "&timezone=auto&forecast_days=7"
                + "&temperature_unit=" + (c.fahrenheit ? "fahrenheit" : "celsius")
                + "&wind_speed_unit=" + c.windUnit;
    }

    static String geocodeUrl(String name, String scheme) {
        try {
            return scheme + "://geocoding-api.open-meteo.com/v1/search?count=6&language=en&format=json&name="
                    + URLEncoder.encode(name, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Raw forecast JSON. */
    static String forecastBody(Config c) throws IOException {
        return Net.getFirst(forecastUrl(c, "http"), forecastUrl(c, "https"));
    }

    @SuppressWarnings("unchecked")
    static List<Place> geocode(String name) throws IOException {
        String body = Net.getFirst(geocodeUrl(name, "http"), geocodeUrl(name, "https"));
        List<Place> out = new ArrayList<>();
        Object root = Json.parse(body);
        if (!(root instanceof Map)) return out;
        Object results = ((Map<String, Object>) root).get("results");
        if (!(results instanceof List)) return out;
        for (Object o : (List<Object>) results) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> r = (Map<String, Object>) o;
            Object n = r.get("name"), la = r.get("latitude"), lo = r.get("longitude");
            if (!(n instanceof String) || !(la instanceof Double) || !(lo instanceof Double)) continue;
            StringBuilder label = new StringBuilder((String) n);
            Object admin = r.get("admin1"), country = r.get("country");
            if (admin instanceof String && !admin.equals(n)) label.append(", ").append(admin);
            if (country instanceof String) label.append(", ").append(country);
            out.add(new Place((String) n, label.toString(), (Double) la, (Double) lo));
        }
        return out;
    }

    static String describe(Exception e) { return Net.describe(e); }
}
