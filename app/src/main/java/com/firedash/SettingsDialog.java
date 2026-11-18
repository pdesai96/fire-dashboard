package com.firedash;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/** Settings, shown on a long press. Each change is saved straight away. */
final class SettingsDialog {
    private SettingsDialog() {}

    private static final int THEME = android.R.style.Theme_Material_Light_Dialog_Alert;

    static void show(final DashboardActivity a) {
        final Config c = Prefs.load(a);
        String[] items = {
                "Location: " + c.place,
                "Note: " + (c.note.trim().isEmpty() ? "none" : c.note),
                "Clock: " + (c.clock24h ? "24-hour" : "12-hour"),
                "Units: " + (c.fahrenheit ? "°F" : "°C") + ", " + c.windLabel(),
                "Look: " + themeLabel(c.theme),
                "Dim the screen at night: " + onOff(c.dimAtNight),
                "Hourly forecast: every " + c.hourStep + (c.hourStep == 1 ? " hour" : " hours"),
                "Update the weather every " + c.refreshMinutes + " minutes",
                "Open when the tablet starts: " + onOff(c.startOnBoot),
                "Refresh the weather now",
                "Close the dashboard",
        };
        new AlertDialog.Builder(a, THEME)
                .setTitle("Dashboard settings")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0: askLocation(a); return;
                            case 1: askNote(a, c); return;
                            case 2: c.clock24h = !c.clock24h; break;
                            case 3: chooseUnits(a, c); return;
                            case 4: chooseTheme(a, c); return;
                            case 5: c.dimAtNight = !c.dimAtNight; break;
                            case 6: chooseInt(a, c, "Hourly forecast", new int[] { 1, 2, 3 },
                                    new String[] { "Every hour (next 8 hours)", "Every 2 hours (next 16 hours)", "Every 3 hours (next 24 hours)" }, true); return;
                            case 7: chooseInt(a, c, "Update the weather every", new int[] { 10, 15, 30, 60 },
                                    new String[] { "10 minutes", "15 minutes", "30 minutes", "1 hour" }, false); return;
                            case 8: c.startOnBoot = !c.startOnBoot; break;
                            case 9: a.refreshFromSettings(); return;
                            default: a.finish(); return;
                        }
                        saveAndReopen(a, c);
                    }
                })
                .setNegativeButton("Done", null)
                .show();
    }

    private static void saveAndReopen(DashboardActivity a, Config c) {
        Prefs.save(a, c);
        a.settingsChanged();
        show(a);
    }

    private static String onOff(boolean b) { return b ? "on" : "off"; }

    private static String themeLabel(String t) {
        if (t.equals("light")) return "always light";
        if (t.equals("dark")) return "always dark";
        return "light by day, dark at night";
    }

    private static void chooseUnits(final DashboardActivity a, final Config c) {
        final String[] labels = { "°C and mph", "°C and km/h", "°C and m/s", "°F and mph" };
        final boolean[] fahrenheit = { false, false, false, true };
        final String[] wind = { "mph", "kmh", "ms", "mph" };
        new AlertDialog.Builder(a, THEME).setTitle("Units").setItems(labels, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                c.fahrenheit = fahrenheit[which];
                c.windUnit = wind[which];
                saveAndReopen(a, c);
            }
        }).show();
    }

    private static void chooseTheme(final DashboardActivity a, final Config c) {
        final String[] values = { "auto", "light", "dark" };
        final String[] labels = { "Light by day, dark at night", "Always light", "Always dark" };
        new AlertDialog.Builder(a, THEME).setTitle("Look").setItems(labels, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                c.theme = values[which];
                saveAndReopen(a, c);
            }
        }).show();
    }

    private static void chooseInt(final DashboardActivity a, final Config c, String title, final int[] values,
                                  String[] labels, final boolean hourStep) {
        new AlertDialog.Builder(a, THEME).setTitle(title).setItems(labels, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                if (hourStep) c.hourStep = values[which]; else c.refreshMinutes = values[which];
                saveAndReopen(a, c);
            }
        }).show();
    }

    private static void askNote(final DashboardActivity a, final Config c) {
        final EditText input = new EditText(a);
        input.setSingleLine(true);
        input.setHint("e.g. Bins out Thursday");
        input.setText(c.note);
        new AlertDialog.Builder(a, THEME)
                .setTitle("Note for the footer")
                .setView(input)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        c.note = input.getText().toString().replace('\n', ' ').trim();
                        saveAndReopen(a, c);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void askLocation(final DashboardActivity a) {
        final EditText input = new EditText(a);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setHint("Town or city, e.g. Leeds");
        new AlertDialog.Builder(a, THEME)
                .setTitle("Where are you?")
                .setMessage("Type a town or city. You can also type coordinates, like 51.51, -0.13")
                .setView(input)
                .setPositiveButton("Search", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        search(a, input.getText().toString().trim());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void search(final DashboardActivity a, final String query) {
        if (query.isEmpty()) return;
        String[] parts = query.split(",");
        if (parts.length == 2) {
            try {
                double lat = Double.parseDouble(parts[0].trim()), lon = Double.parseDouble(parts[1].trim());
                if (Math.abs(lat) <= 90 && Math.abs(lon) <= 180) {
                    setPlace(a, String.format(Locale.ROOT, "%.2f, %.2f", lat, lon), lat, lon);
                    return;
                }
            } catch (NumberFormatException ignored) {
                // not coordinates: search by name
            }
        }
        Toast.makeText(a, "Searching…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                List<WeatherClient.Place> found = null;
                String error = null;
                try {
                    found = WeatherClient.geocode(query);
                } catch (Exception e) {
                    error = WeatherClient.describe(e);
                }
                final List<WeatherClient.Place> places = found;
                final String err = error;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() { showPlaces(a, query, places, err); }
                });
            }
        }, "geocode").start();
    }

    private static void showPlaces(final DashboardActivity a, String query, final List<WeatherClient.Place> places, String error) {
        if (a.isFinishing()) return;
        if (places == null) {
            Toast.makeText(a, "Couldn't search (" + error + "). Check the Wi-Fi.", Toast.LENGTH_LONG).show();
            return;
        }
        if (places.isEmpty()) {
            Toast.makeText(a, "No places found for “" + query + "”", Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[places.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = places.get(i).label;
        new AlertDialog.Builder(a, THEME).setTitle("Which one?").setItems(labels, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                WeatherClient.Place p = places.get(which);
                setPlace(a, p.name, p.lat, p.lon);
            }
        }).setNegativeButton("Cancel", null).show();
    }

    private static void setPlace(DashboardActivity a, String name, double lat, double lon) {
        Config c = Prefs.load(a);
        c.place = name;
        c.lat = lat;
        c.lon = lon;
        Prefs.save(a, c);
        a.settingsChanged();
        Toast.makeText(a, "Location set to " + name, Toast.LENGTH_SHORT).show();
    }
}
