package com.firedash;

import android.content.Context;

import java.io.IOException;
import java.util.Locale;

/**
 * Talks to jarvis-core on the Raspberry Pi. The tablet never holds an OpenAI key: it holds only
 * its own device token, which the Pi can revoke at any time.
 */
final class AssistantClient {
    private AssistantClient() {}

    static final int DEFAULT_PORT = 8787;
    private static final int CHAT_TIMEOUT_MS = 95000; // the server allows up to 90 s per turn

    /** The outcome of a chat or confirmation request. */
    static final class Result {
        boolean ok;
        String reply;           // what to show and say
        String conversationId;
        String pendingId;       // set when an action is waiting for yes/no
        String pendingSummary;
        String tools;           // comma-separated tool names used, for the transcript
        String status;          // for confirm/cancel: confirmed, cancelled or failed
        boolean unpaired;       // the token was rejected: pair again
    }

    /** "192.168.1.50" becomes "http://192.168.1.50:8787". Returns null if it doesn't look like an address. */
    static String normaliseUrl(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty() || s.contains(" ")) return null;
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://" + s;
        String rest = s.substring(s.indexOf("://") + 3);
        if (rest.isEmpty() || rest.contains("/")) return null;
        int colon = rest.lastIndexOf(':');
        if (colon < 0 || rest.endsWith("]")) s = s + ":" + DEFAULT_PORT;
        return s.toLowerCase(Locale.ROOT);
    }

    /** Exchanges a 6-digit pairing code for a device token. Returns {token, assistantName}. */
    static String[] pair(String url, String code, String deviceName) throws IOException {
        Net.Reply r = Net.send("POST", url + "/api/pair", Json.object("code", code, "name", deviceName), null, 15000);
        Object body = parse(r.body);
        if (r.code == 200) {
            String token = Json.str(body, "token");
            if (token == null) throw new IOException("the server's reply was missing a token");
            return new String[] { token, Json.str(body, "assistantName") };
        }
        if (r.code == 401) throw new IOException("that code is wrong or has expired");
        if (r.code == 429) throw new IOException("too many attempts; wait 15 minutes");
        if (r.code == 400) throw new IOException("pairing codes are 6 digits");
        throw new IOException(r.code == 404 ? "that address isn't a JARVIS server" : "server error " + r.code);
    }

    /** Sends a message. channel "voice" asks for a reply suited to being spoken. Never throws. */
    static Result chat(Context ctx, String message, String conversationId, boolean spoken) {
        String json = Json.object("message", message, "conversationId", conversationId, "channel", spoken ? "voice" : "text");
        return call(ctx, "POST", "/api/chat", json);
    }

    /** Confirms or cancels a pending action from the on-screen buttons. Never throws. */
    static Result decide(Context ctx, String pendingId, boolean confirm) {
        Result r = call(ctx, "POST", "/api/actions/" + pendingId + (confirm ? "/confirm" : "/cancel"), "{}");
        if (r.ok && r.reply == null) {
            r.reply = "confirmed".equals(r.status) ? "Done." : "cancelled".equals(r.status) ? "Cancelled." : "That didn't work.";
        }
        return r;
    }

    /** Checks the server and the token. Returns a short human-readable status. */
    static String check(Context ctx) {
        String url = Prefs.jarvisUrl(ctx);
        if (url == null) return "Not set up";
        try {
            Net.Reply health = Net.send("GET", url + "/api/health", null, null, 8000);
            if (health.code != 200) return "Server error " + health.code;
            String model = Json.str(parse(health.body), "modelStatus");
            Net.Reply me = Net.send("GET", url + "/api/device", null, Prefs.jarvisToken(ctx), 8000);
            if (me.code == 401) return "Reachable, but this tablet is no longer paired";
            if (me.code != 200) return "Server error " + me.code;
            if ("ok".equals(model)) return "Connected";
            if ("no_key".equals(model)) return "Connected, but the server has no OpenAI key yet";
            if ("unavailable".equals(model)) return "Connected, but the server's AI model isn't available";
            return "Connected (model not yet verified)";
        } catch (IOException e) {
            return "Can't reach " + url + " (" + Net.describe(e) + ")";
        }
    }

    private static Result call(Context ctx, String method, String path, String json) {
        Result res = new Result();
        String url = Prefs.jarvisUrl(ctx), token = Prefs.jarvisToken(ctx);
        if (url == null || token == null) {
            res.unpaired = true;
            res.reply = "This tablet isn't connected to " + Prefs.jarvisName(ctx) + " yet.";
            return res;
        }
        Net.Reply r;
        try {
            r = Net.send(method, url + path, json, token, CHAT_TIMEOUT_MS);
        } catch (IOException e) {
            res.reply = "I can't reach the server at the moment (" + Net.describe(e) + ").";
            return res;
        }
        Object body = parse(r.body);
        if (r.code == 200) {
            res.ok = true;
            res.reply = Json.str(body, "reply");
            res.conversationId = Json.str(body, "conversationId");
            Object pending = Json.get(body, "pendingAction");
            if (pending != null) {
                res.pendingId = Json.str(pending, "id");
                res.pendingSummary = Json.str(pending, "summary");
            }
            Object tools = Json.get(body, "toolsUsed");
            if (tools instanceof java.util.List) {
                StringBuilder b = new StringBuilder();
                for (Object t : (java.util.List<?>) tools) {
                    String n = Json.str(t, "name");
                    if (n == null) continue;
                    if (b.length() > 0) b.append(", ");
                    b.append(n.replace("get_", "").replace('_', ' '));
                }
                res.tools = b.length() > 0 ? b.toString() : null;
            }
            res.status = Json.str(body, "status");
            return res;
        }
        if (r.code == 401) {
            res.unpaired = true;
            res.reply = "The server no longer recognises this tablet. Connect again in Settings.";
            return res;
        }
        String reply = Json.str(body, "reply");
        if (reply == null) reply = Json.str(body, "message");
        res.reply = reply != null ? reply : "The server returned an error (" + r.code + ").";
        return res;
    }

    private static Object parse(String body) {
        try {
            return body == null || body.isEmpty() ? null : Json.parse(body);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
