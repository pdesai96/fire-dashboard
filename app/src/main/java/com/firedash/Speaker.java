package com.firedash;

import android.content.Context;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

/** Text to speech, for the briefing and for messages sent from Alexa. Silent if the tablet has no voice. */
final class Speaker {
    private Speaker() {}

    private static TextToSpeech tts;
    private static boolean ready;
    private static String pending;

    static synchronized void say(Context ctx, String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (tts != null && ready) {
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, "dash" + System.currentTimeMillis());
            return;
        }
        pending = pending == null ? text : pending + " " + text;
        if (tts != null) return; // still starting up
        tts = new TextToSpeech(ctx.getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override public void onInit(int status) {
                synchronized (Speaker.class) {
                    if (status != TextToSpeech.SUCCESS) { tts = null; pending = null; return; }
                    int r = tts.setLanguage(Locale.UK);
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.US);
                    ready = true;
                    if (pending != null) {
                        tts.speak(pending, TextToSpeech.QUEUE_ADD, null, "dash" + System.currentTimeMillis());
                        pending = null;
                    }
                }
            }
        });
    }

    static synchronized void stop() {
        if (tts != null && ready) tts.stop();
    }
}
