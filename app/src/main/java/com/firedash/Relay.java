package com.firedash;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Picks up commands sent from the Alexa skill. The skill drops them in a small online
 * mailbox; the tablet asks for anything newer than the last one it handled.
 */
final class Relay {
    private Relay() {}

    static final class Command {
        final long id;
        final String type;
        final Map<String, Object> args;
        Command(long id, String type, Map<String, Object> args) { this.id = id; this.type = type; this.args = args; }

        String str(String key) { Object o = args.get(key); return o instanceof String ? (String) o : ""; }
    }

    static List<Command> poll(String url, String channel, long after) throws IOException {
        String full = url + (url.contains("?") ? "&" : "?") + "channel=" + channel + "&after=" + after;
        return parse(Net.get(full));
    }

    static List<Command> parse(String json) {
        List<Command> out = new ArrayList<>();
        for (Object o : United.list(United.map(Json.parse(json)).get("commands"))) {
            Map<String, Object> m = United.map(o);
            Object id = m.get("id"), type = m.get("type");
            if (!(id instanceof Double) || !(type instanceof String)) continue;
            out.add(new Command(((Double) id).longValue(), (String) type, m));
        }
        return out;
    }
}
