package com.firedash;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

/**
 * The dashboard. Keeps the screen on, redraws every minute, fetches the forecast every
 * few minutes in the background. Tap to refresh, long-press for settings.
 */
public class DashboardActivity extends Activity {
    private DashboardView view;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Config cfg;
    private Forecast forecast;
    private String lastError;
    private boolean fetching;
    private boolean visible;
    private float appliedBrightness = Float.NaN;

    private final Runnable tick = new Runnable() {
        @Override public void run() { onTick(); }
    };
    private final Runnable fetchTask = new Runnable() {
        @Override public void run() { fetch(); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        view = new DashboardView(this);
        view.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { refreshNow(); }
        });
        view.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { SettingsDialog.show(DashboardActivity.this); return true; }
        });
        setContentView(view);
        cfg = Prefs.load(this);
        forecast = Prefs.loadForecast(this, cfg.cacheTag());
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        hideSystemBars();
        update();
        scheduleTick();
        if (forecast == null || ageMinutes() >= cfg.refreshMinutes) {
            scheduleFetch(0);
        } else {
            scheduleFetch(cfg.refreshMinutes - ageMinutes());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(fetchTask);
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

    // ------------------------------------------------------------------ timers

    private void scheduleTick() {
        handler.removeCallbacks(tick);
        long now = System.currentTimeMillis();
        handler.postDelayed(tick, 60000 - (now % 60000) + 250);
    }

    private void onTick() {
        update();
        // Catch up if the tablet slept and the fetch timer was delayed.
        if (!fetching && forecast != null && ageMinutes() > cfg.refreshMinutes * 1.5) scheduleFetch(0);
        scheduleTick();
    }

    private void scheduleFetch(double minutes) {
        handler.removeCallbacks(fetchTask);
        handler.postDelayed(fetchTask, (long) Math.max(100, minutes * 60000));
    }

    private double ageMinutes() {
        return forecast == null ? 1e9 : (System.currentTimeMillis() - forecast.fetchedAt) / 60000.0;
    }

    // ------------------------------------------------------------------ data

    private void refreshNow() {
        view.renderer.updating = true;
        view.invalidate();
        scheduleFetch(0);
    }

    private void fetch() {
        if (fetching) return;
        fetching = true;
        final Config request = cfg;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String body = WeatherClient.forecastBody(request);
                    long now = System.currentTimeMillis();
                    final Forecast f = Forecast.parse(body, now);
                    Prefs.saveForecast(DashboardActivity.this, request.cacheTag(), now, body);
                    handler.post(new Runnable() { @Override public void run() { fetched(request, f, null); } });
                } catch (final Exception e) {
                    handler.post(new Runnable() { @Override public void run() { fetched(request, null, WeatherClient.describe(e)); } });
                }
            }
        }, "forecast").start();
    }

    private void fetched(Config request, Forecast f, String error) {
        fetching = false;
        view.renderer.updating = false;
        if (!request.cacheTag().equals(cfg.cacheTag())) { // place or units changed while fetching
            scheduleFetch(0);
            return;
        }
        if (f != null) {
            forecast = f;
            lastError = null;
            if (visible) scheduleFetch(cfg.refreshMinutes);
        } else {
            lastError = error;
            if (visible) scheduleFetch(forecast != null ? 5 : 2);
        }
        update();
    }

    /** Called by SettingsDialog after a change. */
    void settingsChanged() {
        String oldTag = cfg.cacheTag();
        cfg = Prefs.load(this);
        if (!oldTag.equals(cfg.cacheTag())) {
            forecast = Prefs.loadForecast(this, cfg.cacheTag());
            lastError = null;
            scheduleFetch(0);
        }
        update();
    }

    void refreshFromSettings() { refreshNow(); }

    // ------------------------------------------------------------------ drawing

    private void update() {
        Renderer r = view.renderer;
        r.cfg = cfg;
        r.data = forecast;
        r.lastError = lastError;
        boolean night = Wx.isNight(forecast, Wx.placeTime(System.currentTimeMillis(), forecast != null ? forecast.utcOffsetSeconds : null));
        boolean dark = cfg.theme.equals("dark") || (cfg.theme.equals("auto") && night);
        r.theme = dark ? Theme.DARK : Theme.LIGHT;
        float brightness = (cfg.dimAtNight && night) ? 0.08f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        if (brightness != appliedBrightness) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = brightness;
            getWindow().setAttributes(lp);
            appliedBrightness = brightness;
        }
        view.invalidate();
    }
}
