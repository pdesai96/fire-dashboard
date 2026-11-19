package com.firedash;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Build;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.net.ConnectException;

/**
 * Stage 1 assistant UI: type to JARVIS, see and hear the reply. (Voice input arrives in Stage 2.)
 * Also hosts the "Connect to JARVIS" pairing flow.
 */
final class AssistantDialog {
    private AssistantDialog() {}

    private static final int DARK = android.R.style.Theme_Material_Dialog_Alert;
    private static final int LIGHT = android.R.style.Theme_Material_Light_Dialog_Alert;
    private static final long CONTINUE_MS = 10 * 60 * 1000L; // follow-ups within 10 minutes share context

    private static final int ACCENT = 0xFF4FD1FF, TEXT = 0xFFE6F1FF, MUTED = 0xFF8AA0BD;

    private static String conversationId;
    private static long lastUsed;

    static boolean connected(DashboardActivity a) { return Prefs.jarvisToken(a) != null && Prefs.jarvisUrl(a) != null; }

    // ------------------------------------------------------------------ conversation

    static void show(final DashboardActivity a) {
        if (!connected(a)) { connect(a); return; }
        final String name = Prefs.jarvisName(a);
        if (System.currentTimeMillis() - lastUsed > CONTINUE_MS) conversationId = null;
        final float dp = a.getResources().getDisplayMetrics().density;
        int screenH = a.getResources().getDisplayMetrics().heightPixels;

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(dp, 22), px(dp, 6), px(dp, 22), 0);

        final ScrollView scroll = new ScrollView(a);
        final LinearLayout log = new LinearLayout(a);
        log.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(log);
        int logH = (int) Math.min(screenH * 0.42f, 440 * dp);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, logH));

        final LinearLayout confirmRow = new LinearLayout(a);
        confirmRow.setOrientation(LinearLayout.HORIZONTAL);
        confirmRow.setGravity(Gravity.END);
        final Button yes = new Button(a);
        yes.setText("Confirm");
        final Button no = new Button(a);
        no.setText("Cancel");
        confirmRow.addView(no);
        confirmRow.addView(yes);
        confirmRow.setVisibility(View.GONE);
        root.addView(confirmRow);

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final EditText input = new EditText(a);
        input.setSingleLine(true);
        input.setHint("Ask " + name + Wx.ELLIPSIS);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        final Button send = new Button(a);
        send.setText("Send");
        row.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(send);
        root.addView(row);

        if (conversationId == null) {
            add(a, log, scroll, null, "Type a message below." + (Prefs.jarvisSpeak(a) ? " Replies are read aloud." : ""), false, dp);
        } else {
            add(a, log, scroll, null, "Carrying on from earlier. Tap New conversation to start afresh.", false, dp);
        }

        final AlertDialog d = new AlertDialog.Builder(a, DARK)
                .setTitle(name)
                .setView(root)
                .setNegativeButton("Close", null)
                .setNeutralButton("New conversation", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface di, int w) {
                        conversationId = null;
                        show(a);
                    }
                })
                .create();
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface di) { Speaker.stop(); }
        });

        final String[] pending = new String[1];

        final Runnable sendNow = new Runnable() {
            @Override public void run() {
                final String text = input.getText().toString().trim();
                if (text.isEmpty() || !send.isEnabled()) return;
                input.setText("");
                confirmRow.setVisibility(View.GONE);
                pending[0] = null;
                add(a, log, scroll, "You", text, true, dp);
                final TextView answer = add(a, log, scroll, name, "Thinking" + Wx.ELLIPSIS, false, dp);
                send.setEnabled(false);
                Speaker.stop();
                final boolean speak = Prefs.jarvisSpeak(a);
                final String convo = conversationId;
                new Thread(new Runnable() {
                    @Override public void run() {
                        final AssistantClient.Result r = AssistantClient.chat(a, text, convo, speak);
                        a.runOnUiThread(new Runnable() {
                            @Override public void run() {
                                send.setEnabled(true);
                                lastUsed = System.currentTimeMillis();
                                if (r.conversationId != null) conversationId = r.conversationId;
                                String reply = r.reply == null || r.reply.isEmpty() ? "(no reply)" : r.reply;
                                answer.setText(reply);
                                if (r.tools != null) add(a, log, scroll, null, "used: " + r.tools, false, dp);
                                if (r.pendingId != null) {
                                    pending[0] = r.pendingId;
                                    confirmRow.setVisibility(View.VISIBLE);
                                }
                                scrollDown(scroll);
                                if (speak && r.ok && d.isShowing()) Speaker.say(a, reply);
                                if (r.unpaired) a.update();
                            }
                        });
                    }
                }, "jarvis-chat").start();
            }
        };

        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendNow.run(); }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                boolean enter = e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER && e.getAction() == KeyEvent.ACTION_DOWN;
                if (actionId == EditorInfo.IME_ACTION_SEND || enter) {
                    sendNow.run();
                    return true;
                }
                return false;
            }
        });

        View.OnClickListener decide = new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String id = pending[0];
                if (id == null) return;
                final boolean confirm = v == yes;
                pending[0] = null;
                confirmRow.setVisibility(View.GONE);
                final TextView answer = add(a, log, scroll, name, confirm ? "Confirming" + Wx.ELLIPSIS : "Cancelling" + Wx.ELLIPSIS, false, dp);
                new Thread(new Runnable() {
                    @Override public void run() {
                        final AssistantClient.Result r = AssistantClient.decide(a, id, confirm);
                        a.runOnUiThread(new Runnable() {
                            @Override public void run() {
                                answer.setText(r.reply);
                                scrollDown(scroll);
                            }
                        });
                    }
                }, "jarvis-decide").start();
            }
        };
        yes.setOnClickListener(decide);
        no.setOnClickListener(decide);

        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        d.show();
        input.requestFocus();
    }

    /** Adds a line to the transcript. who == null makes a small grey note. Returns the body text view. */
    private static TextView add(DashboardActivity a, LinearLayout log, ScrollView scroll, String who, String text, boolean user, float dp) {
        if (who != null) {
            TextView label = new TextView(a);
            label.setText(who.toUpperCase(java.util.Locale.ROOT));
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            label.setTextColor(user ? MUTED : ACCENT);
            label.setLetterSpacing(0.15f);
            label.setPadding(0, px(dp, 10), 0, 0);
            log.addView(label);
        }
        TextView body = new TextView(a);
        body.setText(text);
        if (who == null) {
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            body.setTextColor(MUTED);
            body.setPadding(0, px(dp, 4), 0, px(dp, 2));
        } else {
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
            body.setTextColor(TEXT);
            body.setPadding(0, px(dp, 2), 0, px(dp, 4));
        }
        log.addView(body);
        scrollDown(scroll);
        return body;
    }

    private static void scrollDown(final ScrollView scroll) {
        scroll.post(new Runnable() {
            @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
        });
    }

    private static int px(float dp, int v) { return Math.round(v * dp); }

    // ------------------------------------------------------------------ settings and pairing

    /** The "JARVIS" entry in Settings. */
    static String settingsLabel(DashboardActivity a) {
        String name = Prefs.jarvisName(a);
        if (!connected(a)) return "Connect to " + name + " (assistant)";
        return name + ": connected to " + Prefs.jarvisUrl(a).replace("http://", "");
    }

    static void settings(final DashboardActivity a) {
        if (!connected(a)) { connect(a); return; }
        final String name = Prefs.jarvisName(a);
        String[] items = {
                "Talk to " + name,
                "Read replies aloud: " + (Prefs.jarvisSpeak(a) ? "on" : "off"),
                "Check the connection",
                "Pair again with a new code",
                "Disconnect this tablet",
        };
        new AlertDialog.Builder(a, LIGHT).setTitle(name).setItems(items, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                switch (which) {
                    case 0: show(a); return;
                    case 1:
                        Prefs.setJarvisSpeak(a, !Prefs.jarvisSpeak(a));
                        settings(a);
                        return;
                    case 2:
                        Toast.makeText(a, "Checking" + Wx.ELLIPSIS, Toast.LENGTH_SHORT).show();
                        new Thread(new Runnable() {
                            @Override public void run() {
                                final String status = AssistantClient.check(a);
                                a.runOnUiThread(new Runnable() {
                                    @Override public void run() { Toast.makeText(a, name + ": " + status, Toast.LENGTH_LONG).show(); }
                                });
                            }
                        }, "jarvis-check").start();
                        return;
                    case 3: connect(a); return;
                    default:
                        Prefs.forgetJarvisToken(a);
                        conversationId = null;
                        a.update();
                        Toast.makeText(a, "Disconnected. To revoke it fully, also remove this tablet under Devices in the " + name + " console.", Toast.LENGTH_LONG).show();
                }
            }
        }).setNegativeButton("Done", null).show();
    }

    static void connect(final DashboardActivity a) {
        final String name = Prefs.jarvisName(a);
        float dp = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(dp, 22), px(dp, 4), px(dp, 22), 0);
        final EditText address = new EditText(a);
        address.setSingleLine(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setHint("Pi address, e.g. 192.168.1.50");
        String saved = Prefs.jarvisUrl(a);
        if (saved != null) address.setText(saved.replace("http://", "").replace(":" + AssistantClient.DEFAULT_PORT, ""));
        final EditText code = new EditText(a);
        code.setSingleLine(true);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setHint("6-digit pairing code");
        box.addView(address);
        box.addView(code);
        new AlertDialog.Builder(a, LIGHT)
                .setTitle("Connect to " + name)
                .setMessage("On a computer, open the " + name + " console on your Raspberry Pi (http://<Pi address>:8787), "
                        + "go to Devices and create a pairing code. Then enter the Pi's address and the code here.")
                .setView(box)
                .setPositiveButton("Connect", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        pair(a, address.getText().toString(), code.getText().toString());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void pair(final DashboardActivity a, String addressText, String codeText) {
        final String url = AssistantClient.normaliseUrl(addressText);
        final String code = codeText.replaceAll("\\s", "");
        if (url == null) { a.toast("That address doesn't look right. Try something like 192.168.1.50"); return; }
        if (!code.matches("\\d{6}")) { a.toast("The pairing code is 6 digits"); return; }
        a.toast("Connecting" + Wx.ELLIPSIS);
        new Thread(new Runnable() {
            @Override public void run() {
                String error = null;
                String[] result = null;
                try {
                    result = AssistantClient.pair(url, code, "Fire tablet (" + Build.MODEL + ")");
                } catch (ConnectException e) {
                    error = "nothing answered at " + url + ". Is the Pi on and the server running?";
                } catch (IOException e) {
                    error = e.getClass() == IOException.class && e.getMessage() != null ? e.getMessage() : Net.describe(e);
                }
                final String err = error;
                final String[] ok = result;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (ok == null) {
                            a.toast("Couldn't connect: " + err);
                            return;
                        }
                        Prefs.setJarvis(a, url, ok[0], ok[1]);
                        conversationId = null;
                        a.update();
                        a.toast("Connected to " + Prefs.jarvisName(a));
                        show(a);
                    }
                });
            }
        }, "jarvis-pair").start();
    }
}
