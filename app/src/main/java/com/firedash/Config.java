package com.firedash;

/** User settings. Stored by Prefs on the device. */
final class Config {
    String place = "London";
    double lat = 51.5072;
    double lon = -0.1276;
    boolean fahrenheit = false;
    String windUnit = "mph";        // mph, kmh, ms, kn
    boolean clock24h = true;
    int hourStep = 3;               // hours between columns in the hourly strip
    String note = "";               // one line of your own text in the footer
    int refreshMinutes = 15;
    String theme = "auto";          // auto (dark at night), light, dark
    boolean dimAtNight = true;
    boolean startOnBoot = true;

    String windLabel() {
        switch (windUnit) {
            case "kmh": return "km/h";
            case "ms": return "m/s";
            case "kn": return "kn";
            default: return "mph";
        }
    }

    /** Identifies the forecast request, so a cached forecast is only reused for the same place and units. */
    String cacheTag() {
        return String.format(java.util.Locale.ROOT, "%.3f,%.3f,%s,%s", lat, lon, fahrenheit ? "F" : "C", windUnit);
    }
}
