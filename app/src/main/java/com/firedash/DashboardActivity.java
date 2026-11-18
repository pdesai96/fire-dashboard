package com.firedash;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The dashboard. Keeps the screen on and, every few seconds, checks what needs refreshing:
 * weather, United, news, calendar, the live config from GitHub, app updates, the tablet's
 * location and commands from Alexa. Tap to refresh, long-press for settings.
 */
public class DashboardActivity extends Activity {
    private static final long HEARTBEAT_MS = 5000;

    private DashboardView view;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final ExecutorService relayWork = Executors.newSingleThreadExecutor();

    Config cfg;
    LiveConfig live = LiveConfig.defaults();
    private Forecast forecast;
    private United united;
    private List<News.Item> news;
    private String icsBody;
    private String weatherError, unitedError;

    // when each thing was last fetched (ms), and whether a fetch is running
    private long lastWeather, lastUnited, lastNews, lastCalendar, lastConfig, lastUpdateCheck, lastRelay, lastLocate;
    private boolean busyWeather, busyUnited, busyNews, busyCalendar, busyConfig, busyUpdate, busyRelay, busyLocate;
    private int latestVersion;
    private float appliedBrightness = Float.NaN;

    private final Runnable tick = new Runnable() {
        @Override public void run() { update(); scheduleTick(); }
    };
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() { refreshDue(); handler.postDelayed(this, HEARTBEAT_MS); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Net.installExtraRoots(getResources().openRawResource(R.raw.roots));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        view = new DashboardView(this);
        view.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onTap(view.actionAtLastTouch()); }
        });
        view.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { SettingsDialog.show(DashboardActivity.this); return true; }
        });
        setContentView(view);

        cfg = Prefs.load(this);
        String liveJson = Prefs.get(this, "liveConfig");
        if (liveJson != null) {
            try { live = LiveConfig.parse(liveJson); } catch (RuntimeException ignored) { }
        }
        forecast = Prefs.loadForecast(this, cfg.cacheTag());
        if (forecast != null) lastWeather = forecast.fetchedAt;
        try {
            String team = Prefs.get(this, "unitedTeam");
            if (team != null) united = United.parse(team, Prefs.get(this, "unitedSchedule"), live.teamId, System.currentTimeMillis());
        } catch (RuntimeException ignored) { }
        String newsCache = Prefs.get(this, "newsCache");
        if (newsCache != null) news = News.deserialize(newsCache);
        icsBody = Prefs.get(this, "icsBody");
        lastLocate = cfg.locatedAt;
        AlarmScheduler.reschedule(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        update();
        scheduleTick();
        handler.removeCallbacks(heartbeat);
        handler.post(heartbeat);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        handler.removeCallbacks(heartbeat);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private void hideSystemBars() {
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private void scheduleTick() {
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 60000 - (System.currentTimeMillis() % 60000) + 250);
    }

    // ------------------------------------------------------------------ taps

    private void onTap(String action) {
        if (action == null || action.equals("refresh") || action.equals("united")) {
            refreshAll();
        } else if (action.equals("clock")) {
            AlarmsDialog.show(this);
        } else if (action.equals("update")) {
            installUpdate();
        } else if (action.startsWith("news:")) {
            int i = Integer.parseInt(action.substring(5));
            if (news != null && i < news.size() && !news.get(i).link.isEmpty()) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(news.get(i).link)));
                } catch (RuntimeException e) {
                    toast("Couldn't open that story");
                }
            }
        }
    }

    void refreshAll() {
        lastWeather = lastUnited = lastNews = lastCalendar = 0;
        view.renderer.updating = true;
        update();
        refreshDue();
    }

    // ------------------------------------------------------------------ background work

    private void refreshDue() {
        long now = System.currentTimeMillis();
        if (!busyWeather && now - lastWeather > cfg.refreshMinutes * 60000L) fetchWeather();
        if (live.showUnited && !busyUnited && now - lastUnited > unitedInterval(now)) fetchUnited();
        if (live.showNews && !busyNews && now - lastNews > live.newsMinutes * 60000L) fetchNews();
        if (!cfg.icsUrl.isEmpty() && !busyCalendar && now - lastCalendar > 30 * 60000L) fetchCalendar();
        if (!busyConfig && now - lastConfig > live.configMinutes * 60000L) fetchConfig();
        if (!busyUpdate && now - lastUpdateCheck > live.updateCheckHours * 3600000L) checkForUpdate();
        if (cfg.autoLocation && !busyLocate && now - lastLocate > 6 * 3600000L) locate(false);
        if (!live.relayUrl.isEmpty() && !busyRelay && now - lastRelay > live.relaySeconds * 1000L) pollRelay();
        if (view.renderer.updating && !busyWeather && !busyUnited && !busyNews) {
            view.renderer.updating = false;
            update();
        }
    }

    /** Every minute during a match, more often near kick-off, otherwise every half hour. */
    private long unitedInterval(long now) {
        if (united != null && united.isLive()) return live.liveSeconds * 1000L;
        if (united != null && united.next != null) {
            long until = united.next.timeMs - now;
            if (until < 15 * 60000L && until > -3 * 3600000L) return live.liveSeconds * 1000L;
            if (until < 3 * 3600000L && until > 0) return 5 * 60000L;
        }
        return live.unitedMinutes * 60000L;
    }

    private void fetchWeather() {
        busyWeather = true;
        final Config request = cfg;
        work.execute(new Runnable() {
            @Override public void run() {
                try {
                    String body = WeatherClient.forecastBody(request);
                    long now = System.currentTimeMillis();
                    final Forecast f = Forecast.parse(body, now);
                    Prefs.saveForecast(DashboardActivity.this, request.cacheTag(), now, body);
                    post(new Runnable() { @Override public void run() {
                        busyWeather = false;
                        lastWeather = System.currentTimeMillis();
                        if (request.cacheTag().equals(cfg.cacheTag())) { forecast = f; weatherError = null; }
                        update();
                    } });
                } catch (final Exception e) {
                    post(new Runnable() { @Override public void run() {
                        busyWeather = false;
                        lastWeather = System.currentTimeMillis() - cfg.refreshMinutes * 60000L + 120000L; // retry in 2 min
                        weatherError = Net.describe(e);
                        update();
                    } });
                }
            }
        });
    }

    private void fetchUnited() {
        busyUnited = true;
        final LiveConfig lc = live;
        work.execute(new Runnable() {
            @Override public void run() {
                try {
                    String team = Net.getFirst(United.teamUrl(lc, "http"), United.teamUrl(lc, "https"));
                    String schedule = null;
                    try {
                        schedule = Net.getFirst(United.scheduleUrl(lc, "http"), United.scheduleUrl(lc, "https"));
                    } catch (Exception ignored) { }
                    final United u = United.parse(team, schedule, lc.teamId, System.currentTimeMillis());
                    Prefs.put(DashboardActivity.this, "unitedTeam", team);
                    if (schedule != null) Prefs.put(DashboardActivity.this, "unitedSchedule", schedule);
                    post(new Runnable() { @Override public void run() {
                        busyUnited = false;
                        lastUnited = System.currentTimeMillis();
                        united = u;
                        unitedError = null;
                        update();
                    } });
                } catch (final Exception e) {
                    post(new Runnable() { @Override public void run() {
                        busyUnited = false;
                        lastUnited = System.currentTimeMillis() - unitedInterval(System.currentTimeMillis()) + 120000L;
                        unitedError = Net.describe(e);
                        update();
                    } });
                }
            }
        });
    }

    private void fetchNews() {
        busyNews = true;
        final LiveConfig lc = live;
        work.execute(new Runnable() {
            @Override public void run() {
                final List<News.Item> items = News.fetch(lc);
                if (!items.isEmpty()) Prefs.put(DashboardActivity.this, "newsCache", News.serialize(items));
                post(new Runnable() { @Override public void run() {
                    busyNews = false;
                    lastNews = System.currentTimeMillis();
                    if (!items.isEmpty() || news == null) news = items;
                    update();
                } });
            }
        });
    }

    private void fetchCalendar() {
        busyCalendar = true;
        final String url = cfg.icsUrl.startsWith("webcal://") ? "https://" + cfg.icsUrl.substring(9) : cfg.icsUrl;
        work.execute(new Runnable() {
            @Override public void run() {
                try {
                    final String body = Net.get(url);
                    if (!body.contains("BEGIN:VCALENDAR")) throw new IllegalArgumentException("not a calendar");
                    Prefs.put(DashboardActivity.this, "icsBody", body);
                    post(new Runnable() { @Override public void run() {
                        busyCalendar = false;
                        lastCalendar = System.currentTimeMillis();
                        icsBody = body;
                        update();
                    } });
                } catch (Exception e) {
                    post(new Runnable() { @Override public void run() {
                        busyCalendar = false;
                        lastCalendar = System.currentTimeMillis() - 25 * 60000L;
                    } });
                }
            }
        });
    }

    private void fetchConfig() {
        busyConfig = true;
        work.execute(new Runnable() {
            @Override public void run() {
                try {
                    final String json = Net.getFirst(LiveConfig.URLS);
                    final LiveConfig lc = LiveConfig.parse(json);
                    Prefs.put(DashboardActivity.this, "liveConfig", json);
                    post(new Runnable() { @Override public void run() {
                        busyConfig = false;
                        lastConfig = System.currentTimeMillis();
                        boolean newTeam = !lc.teamId.equals(live.teamId);
                        boolean newsChanged = lc.feeds.size() != live.feeds.size();
                        live = lc;
                        if (newTeam) lastUnited = 0;
                        if (newsChanged) lastNews = 0;
                        update();
                    } });
                } catch (Exception e) {
                    post(new Runnable() { @Override public void run() {
                        busyConfig = false;
                        lastConfig = System.currentTimeMillis() - live.configMinutes * 60000L + 10 * 60000L;
                    } });
                }
            }
        });
    }

    private void checkForUpdate() {
        busyUpdate = true;
        work.execute(new Runnable() {
            @Override public void run() {
                int v = 0;
                try { v = Updater.latestVersion(); } catch (Exception ignored) { }
                final int latest = v;
                post(new Runnable() { @Override public void run() {
                    busyUpdate = false;
                    lastUpdateCheck = System.currentTimeMillis();
                    if (latest > 0) latestVersion = latest;
                    update();
                } });
            }
        });
    }

    boolean updateReady() { return latestVersion > Updater.installedVersion(this); }

    void checkForUpdateNow() {
        lastUpdateCheck = 0;
        refreshDue();
    }

    void installUpdate() {
        toast("Downloading the update" + Wx.ELLIPSIS);
        work.execute(new Runnable() {
            @Override public void run() {
                try {
                    final File apk = Updater.download(DashboardActivity.this);
                    post(new Runnable() { @Override public void run() { Updater.install(DashboardActivity.this, apk); } });
                } catch (final Exception e) {
                    post(new Runnable() { @Override public void run() { toast("Couldn't download the update (" + Net.describe(e) + ")"); } });
                }
            }
        });
    }

    /** Finds the tablet's location. If announce, says what it found. */
    void locate(final boolean announce) {
        busyLocate = true;
        Locator.find(this, new Locator.Callback() {
            @Override public void found(double lat, double lon, String name, boolean precise) {
                busyLocate = false;
                lastLocate = System.currentTimeMillis();
                Config c = Prefs.load(DashboardActivity.this);
                c.lat = lat;
                c.lon = lon;
                c.place = name;
                c.autoLocation = true;
                c.locatedAt = lastLocate;
                Prefs.save(DashboardActivity.this, c);
                settingsChanged();
                if (announce) {
                    toast(precise ? "Found you near " + name
                            : "Roughly " + name + ". For an exact spot, turn on Location-Based Services in the tablet's Settings.");
                }
            }
            @Override public void failed(String why) {
                busyLocate = false;
                lastLocate = System.currentTimeMillis() - 5 * 3600000L; // try again in an hour
                if (announce) toast("Couldn't find your location (" + why + ")");
            }
        });
    }

    // ------------------------------------------------------------------ Alexa commands

    private void pollRelay() {
        busyRelay = true;
        final String url = live.relayUrl, channel = Prefs.channel(this);
        final long after = Prefs.getLong(this, "relayLast");
        relayWork.execute(new Runnable() {
            @Override public void run() {
                List<Relay.Command> got = null;
                try { got = Relay.poll(url, channel, after); } catch (Exception ignored) { }
                final List<Relay.Command> commands = got;
                post(new Runnable() { @Override public void run() {
                    busyRelay = false;
                    lastRelay = System.currentTimeMillis();
                    if (commands == null) { lastRelay += 25000; return; } // back off when unreachable
                    long max = after;
                    for (Relay.Command c : commands) {
                        if (c.id <= after) continue;
                        max = Math.max(max, c.id);
                        try { handleCommand(c); } catch (RuntimeException ignored) { }
                    }
                    if (max > after) Prefs.putLong(DashboardActivity.this, "relayLast", max);
                } });
            }
        });
    }

    private void handleCommand(Relay.Command c) {
        switch (c.type) {
            case "alarm": {
                String[] hm = c.str("time").split(":");
                if (hm.length < 2) return;
                Alarm a = new Alarm();
                a.hour = Integer.parseInt(hm[0].trim());
                a.minute = Integer.parseInt(hm[1].trim().substring(0, 2));
                a.days = Alarm.parseDays(c.str("days"));
                a.label = c.str("label");
                AlarmsDialog.add(this, a);
                toast("Alarm set for " + Wx.pad(a.hour) + ":" + Wx.pad(a.minute) + " (" + a.daysLabel().toLowerCase(Locale.UK) + ")");
                break;
            }
            case "alarms_off": {
                List<Alarm> alarms = Prefs.alarms(this);
                for (Alarm a : alarms) a.enabled = false;
                Prefs.saveAlarms(this, alarms);
                Prefs.setSnooze(this, 0);
                AlarmScheduler.reschedule(this);
                toast("All alarms are off");
                break;
            }
            case "background": {
                String want = c.str("name").toLowerCase(Locale.ROOT).replace(" ", "");
                for (LiveConfig.Gradient g : live.gradients.values()) {
                    if (g.id.equals(want) || g.label.toLowerCase(Locale.ROOT).replace(" ", "").equals(want)) {
                        cfg.background = g.id;
                        Prefs.save(this, cfg);
                    }
                }
                break;
            }
            case "say":
                Speaker.say(this, c.str("text"));
                break;
            case "briefing":
                Speaker.say(this, AlarmActivity.briefingText(this));
                break;
            case "news": {
                if (news == null || news.isEmpty()) {
                    Speaker.say(this, "I don't have any United news yet.");
                    break;
                }
                StringBuilder b = new StringBuilder("Here are the latest United headlines. ");
                for (int i = 0; i < Math.min(3, news.size()); i++) b.append(news.get(i).title).append(". ");
                Speaker.say(this, b.toString());
                break;
            }
            case "refresh":
                refreshAll();
                break;
            case "locate":
                locate(true);
                break;
            default:
                break;
        }
        update();
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private void post(Runnable r) { handler.post(r); }

    // ------------------------------------------------------------------ settings

    /** Called after a settings change. */
    void settingsChanged() {
        String oldTag = cfg.cacheTag();
        String oldIcs = cfg.icsUrl;
        cfg = Prefs.load(this);
        if (!oldTag.equals(cfg.cacheTag())) {
            forecast = Prefs.loadForecast(this, cfg.cacheTag());
            weatherError = null;
            lastWeather = 0;
        }
        if (!oldIcs.equals(cfg.icsUrl)) {
            lastCalendar = 0;
            if (cfg.icsUrl.isEmpty()) icsBody = null;
        }
        AlarmScheduler.reschedule(this);
        update();
        refreshDue();
    }

    // ------------------------------------------------------------------ drawing

    void update() {
        long now = System.currentTimeMillis();
        Renderer r = view.renderer;
        r.cfg = cfg;
        r.live = live;
        r.data = forecast;
        r.lastError = weatherError;
        r.united = united;
        r.unitedError = unitedError;
        r.news = news;
        r.updateReady = updateReady();
        r.agenda = null;
        if (!cfg.icsUrl.isEmpty()) {
            r.agenda = new ArrayList<>();
            if (icsBody != null) {
                try {
                    Calendar end = Calendar.getInstance(TimeZone.getDefault(), Locale.ROOT);
                    end.setTimeInMillis(now);
                    end.add(Calendar.DAY_OF_MONTH, 2);
                    end.set(Calendar.HOUR_OF_DAY, 0);
                    end.set(Calendar.MINUTE, 0);
                    r.agenda = Agenda.parse(icsBody, now - 86400000L, end.getTimeInMillis());
                } catch (RuntimeException ignored) { }
            }
        }
        List<Alarm> alarms = Prefs.alarms(this);
        Alarm next = Alarm.soonest(alarms, now, TimeZone.getDefault());
        r.nextAlarm = next;
        r.nextAlarmAt = next != null ? next.next(now, TimeZone.getDefault()) : 0;
        long snooze = Prefs.snoozeUntil(this);
        if (snooze > now && (next == null || snooze < r.nextAlarmAt)) {
            Alarm s = new Alarm();
            s.label = "Snoozed";
            r.nextAlarm = s;
            r.nextAlarmAt = snooze;
        }

        Calendar place = Wx.placeTime(now, forecast != null ? forecast.utcOffsetSeconds : null);
        String bg = cfg.background.isEmpty() ? live.defaultBackground : cfg.background;
        LiveConfig.Gradient g = live.gradient(bg);
        if (g == null || g.id.equals("sky")) {
            int rise = 0, set = 0;
            if (forecast != null) {
                int di = Forecast.findIndex(forecast.dTime, Wx.dayKey(place));
                String sr = Forecast.at(forecast.dSunrise, di), ss = Forecast.at(forecast.dSunset, di);
                if (sr != null && ss != null) { rise = Wx.isoMinutes(sr); set = Wx.isoMinutes(ss); }
            }
            r.pal = Palette.sky(place, rise, set);
        } else {
            r.pal = Palette.of(g.top, g.bottom);
        }
        boolean night = Wx.isNight(forecast, place);
        float brightness = (cfg.dimAtNight && night) ? 0.08f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        if (brightness != appliedBrightness) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = brightness;
            getWindow().setAttributes(lp);
            appliedBrightness = brightness;
        }
        view.invalidate();
    }

    @Override
    protected void onDestroy() {
        work.shutdownNow();
        relayWork.shutdownNow();
        super.onDestroy();
    }
}
