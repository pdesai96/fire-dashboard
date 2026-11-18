package com.firedash;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** The ringing screen: big time, Snooze and Stop. After Stop, reads the morning briefing. */
public class AlarmActivity extends Activity {
    private static final long SNOOZE_MS = 9 * 60000L, GIVE_UP_MS = 10 * 60000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private ToneGenerator tone;
    private String label;
    private boolean done;

    private final Runnable beep = new Runnable() {
        @Override public void run() {
            if (tone != null) tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900);
            handler.postDelayed(this, 1500);
        }
    };
    private final Runnable giveUp = new Runnable() {
        @Override public void run() { finishRinging(false); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        label = getIntent().getStringExtra("label");
        if (label == null) label = "";
        setContentView(new RingView(this));
        startSound();
        handler.postDelayed(giveUp, GIVE_UP_MS);
    }

    private void startSound() {
        try {
            Uri uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_ALARM);
            player.setDataSource(this, uri);
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception e) {
            if (player != null) { player.release(); player = null; }
            try {
                tone = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
                handler.post(beep);
            } catch (RuntimeException ignored) { }
        }
    }

    private void stopSound() {
        handler.removeCallbacks(beep);
        handler.removeCallbacks(giveUp);
        if (player != null) {
            try { player.stop(); } catch (IllegalStateException ignored) { }
            player.release();
            player = null;
        }
        if (tone != null) { tone.release(); tone = null; }
    }

    private void snooze() {
        if (done) return;
        done = true;
        stopSound();
        Prefs.setSnooze(this, System.currentTimeMillis() + SNOOZE_MS);
        AlarmScheduler.reschedule(this);
        finish();
    }

    private void finishRinging(boolean briefing) {
        if (done) return;
        done = true;
        stopSound();
        if (briefing && Prefs.load(this).briefing) Speaker.say(this, briefingText(this));
        finish();
    }

    /** The briefing, built from what the dashboard saved most recently. */
    static String briefingText(Context c) {
        Config cfg = Prefs.load(c);
        long now = System.currentTimeMillis();
        Forecast f = Prefs.loadForecast(c, cfg.cacheTag());
        United u = null;
        try {
            String team = Prefs.get(c, "unitedTeam");
            if (team != null) u = United.parse(team, Prefs.get(c, "unitedSchedule"), LiveConfig.defaults().teamId, now);
        } catch (RuntimeException ignored) { }
        List<Agenda.Event> events = null;
        String ics = Prefs.get(c, "icsBody");
        if (ics != null && !cfg.icsUrl.isEmpty()) {
            try {
                events = Agenda.parse(ics, now - 86400000L, now + 2 * 86400000L);
            } catch (RuntimeException ignored) { }
        }
        return Briefing.build(cfg, f, u, events, News.deserialize(Prefs.get(c, "newsCache")), now);
    }

    @Override
    protected void onDestroy() {
        stopSound();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        finishRinging(false);
    }

    /** Draws the ringing screen and handles the two buttons. */
    private final class RingView extends View {
        private float sx, sy, sw, sh, tx, ty, tw, th;

        RingView(Context c) {
            super(c);
            setKeepScreenOn(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            AndroidPainter p = new AndroidPainter(canvas);
            int w = getWidth(), h = getHeight();
            p.fillGradient(0, 0, w, h, 0xFF2B2F63, 0xFFF0915F);
            float u = Math.min(w, h) / 30f;
            Calendar now = Calendar.getInstance(TimeZone.getDefault(), Locale.ROOT);
            boolean h24 = Prefs.load(getContext()).clock24h;
            String[] t = Wx.clock(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), h24);
            String time = t[0] + (t[1] != null ? " " + t[1] : "");
            float size = 7.5f * u;
            float tw0 = p.textWidth(time, Painter.Font.BOLD, size, -0.02f);
            p.text(time, Painter.Font.BOLD, size, -0.02f, (w - tw0) / 2, h * 0.36f, 0xFFFFFFFF);
            String sub = label.isEmpty() ? "Alarm" : label;
            float lw = p.textWidth(sub, Painter.Font.REGULAR, 1.6f * u, 0.04f);
            p.text(sub, Painter.Font.REGULAR, 1.6f * u, 0.04f, (w - lw) / 2, h * 0.36f + 2.6f * u, 0xDDFFFFFF);
            // buttons
            sw = Math.min(w * 0.38f, 14 * u); sh = 4.2f * u;
            tw = sw; th = sh;
            sy = ty = h * 0.62f;
            sx = w / 2f - sw - u;
            tx = w / 2f + u;
            p.fillRoundRect(sx, sy, sw, sh, sh / 2, 0x55FFFFFF);
            p.fillRoundRect(tx, ty, tw, th, th / 2, 0xFFFFFFFF);
            centred(p, "Snooze 9 min", sx, sy, sw, sh, 0xFFFFFFFF, u);
            centred(p, "Stop", tx, ty, tw, th, 0xFF1A1A2E, u);
        }

        private void centred(AndroidPainter p, String s, float x, float y, float w, float h, int color, float u) {
            float size = 1.5f * u, width = p.textWidth(s, Painter.Font.BOLD, size, 0);
            p.text(s, Painter.Font.BOLD, size, 0, x + (w - width) / 2, y + h / 2 + 0.36f * size, color);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_UP) {
                float x = e.getX(), y = e.getY();
                if (x >= sx && x <= sx + sw && y >= sy && y <= sy + sh) snooze();
                else if (x >= tx && x <= tx + tw && y >= ty && y <= ty + th) finishRinging(true);
            }
            return true;
        }
    }
}
