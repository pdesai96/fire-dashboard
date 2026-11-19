package com.firedash;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Settings, shown on a long press. Each change is saved straight away. */
final class SettingsDialog {
    private SettingsDialog() {}

    private static final int THEME = android.R.style.Theme_Material_Light_Dialog_Alert;

    static void show(final DashboardActivity a) {
        final Config c = Prefs.load(a);
        String bgId = c.background.isEmpty() ? a.live.defaultBackground : c.background;
        LiveConfig.Gradient g = a.live.gradient(bgId);
        int alarmsOn = 0;
        for (Alarm x : Prefs.alarms(a)) if (x.enabled) alarmsOn++;
        String[] items = {
                AssistantDialog.settingsLabel(a),
                "Location: " + c.place + (c.autoLocation ? " (automatic)" : ""),
                "Background: " + (g != null ? g.label : bgId),
                "Alarms: " + (alarmsOn == 0 ? "none" : alarmsOn + " on"),
                "Read a briefing after the alarm: " + onOff(c.briefing),
                "Read the briefing now",
                "Outlook calendar: " + (c.icsUrl.isEmpty() ? "not connected" : "connected"),
                "Clock: " + (c.clock24h ? "24-hour" : "12-hour"),
                "Units: " + (c.fahrenheit ? "°F" : "°C") + ", " + c.windLabel(),
                "Dim the screen at night: " + onOff(c.dimAtNight),
                "Hourly forecast: every " + c.hourStep + (c.hourStep == 1 ? " hour" : " hours"),
                "Open when the tablet starts: " + onOff(c.startOnBoot),
                "Check for updates (this is version " + Updater.installedName(a) + ")",
                "Close the dashboard",
        };
        new AlertDialog.Builder(a, THEME)
                .setTitle("Dashboard settings")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0: AssistantDialog.settings(a); return;
                            case 1: location(a); return;
                            case 2: background(a, c); return;
                            case 3: AlarmsDialog.show(a); return;
                            case 4: c.briefing = !c.briefing; break;
                            case 5: Speaker.say(a, AlarmActivity.briefingText(a)); return;
                            case 6: calendar(a, c); return;
                            case 7: c.clock24h = !c.clock24h; break;
                            case 8: units(a, c); return;
                            case 9: c.dimAtNight = !c.dimAtNight; break;
                            case 10: hourly(a, c); return;
                            case 11: c.startOnBoot = !c.startOnBoot; break;
                            case 12:
                                a.checkForUpdateNow();
                                Toast.makeText(a, "Checking… if there's an update, a yellow button appears at the top.", Toast.LENGTH_LONG).show();
                                return;
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

    private static void location(final DashboardActivity a) {
        String[] opts = { "Use my location automatically", "Search for a town or city" };
        new AlertDialog.Builder(a, THEME).setTitle("Location").setItems(opts, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                if (which == 0) {
                    Toast.makeText(a, "Finding you…", Toast.LENGTH_SHORT).show();
                    a.locate(true);
                } else {
                    askPlace(a);
                }
            }
        }).show();
    }

    private static void background(final DashboardActivity a, final Config c) {
        final List<LiveConfig.Gradient> list = new ArrayList<>(a.live.gradients.values());
        String[] labels = new String[list.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = list.get(i).label;
        new AlertDialog.Builder(a, THEME).setTitle("Background").setItems(labels, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                c.background = list.get(which).id;
                saveAndReopen(a, c);
            }
        }).show();
    }

    private static void calendar(final DashboardActivity a, final Config c) {
        final EditText input = new EditText(a);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://outlook.office365.com/owa/calendar/…/calendar.ics");
        input.setText(c.icsUrl);
        new AlertDialog.Builder(a, THEME)
                .setTitle("Outlook calendar")
                .setMessage("In Outlook on the web: Settings → Calendar → Shared calendars → Publish a calendar. "
                        + "Pick your calendar, choose \"Can view all details\", tap Publish, then copy the ICS link and paste it here. "
                        + "The link stays on this tablet.")
                .setView(input)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        c.icsUrl = input.getText().toString().trim();
                        saveAndReopen(a, c);
                    }
                })
                .setNeutralButton("Disconnect", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        c.icsUrl = "";
                        saveAndReopen(a, c);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void units(final DashboardActivity a, final Config c) {
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

    private static void hourly(final DashboardActivity a, final Config c) {
        final int[] values = { 1, 2, 3 };
        String[] labels = { "Every hour (next 8 hours)", "Every 2 hours (next 16 hours)", "Every 3 hours (next 24 hours)" };
        new AlertDialog.Builder(a, THEME).setTitle("Hourly forecast").setItems(labels, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                c.hourStep = values[which];
                saveAndReopen(a, c);
            }
        }).show();
    }

    private static void askPlace(final DashboardActivity a) {
        final EditText input = new EditText(a);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setHint("Town or city, e.g. Salford");
        new AlertDialog.Builder(a, THEME)
                .setTitle("Where are you?")
                .setMessage("Type a town or city. You can also type coordinates, like 53.48, -2.24")
                .setView(input)
                .setPositiveButton("Search", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) { search(a, input.getText().toString().trim()); }
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
                    error = Net.describe(e);
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
        c.autoLocation = false; // the user chose a place: stop following the tablet
        Prefs.save(a, c);
        a.settingsChanged();
        Toast.makeText(a, "Location set to " + name, Toast.LENGTH_SHORT).show();
    }
}
