package com.firedash;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings that can change without a new install. The app fetches live/config.json from the
 * GitHub repo every hour; anything it doesn't specify falls back to the defaults below.
 */
final class LiveConfig {
    static final String REPO = "pdesai96/fire-dashboard";
    static final String[] URLS = {
            "https://raw.githubusercontent.com/" + REPO + "/main/live/config.json",
            "https://cdn.jsdelivr.net/gh/" + REPO + "@main/live/config.json",
    };

    static final class Feed {
        final String name, url;
        final boolean filter;
        Feed(String name, String url, boolean filter) { this.name = name; this.url = url; this.filter = filter; }
    }

    static final class Gradient {
        final String id, label;
        final int top, bottom;
        Gradient(String id, String label, int top, int bottom) { this.id = id; this.label = label; this.top = top; this.bottom = bottom; }
    }

    String defaultBackground = "sky";
    final Map<String, Gradient> gradients = new LinkedHashMap<>();
    String teamName = "Manchester United", teamShort = "Man Utd", teamId = "360", teamLeague = "eng.1";
    final List<Feed> feeds = new ArrayList<>();
    final List<String> keywords = new ArrayList<>();
    int maxNews = 6;
    boolean showUnited = true, showNews = true, showCalendar = true, showForecast = true;
    String relayUrl = "";
    int newsMinutes = 30, unitedMinutes = 30, liveSeconds = 60, configMinutes = 60, updateCheckHours = 6, relaySeconds = 5;
    boolean briefing = true;
    String notice = "";

    static LiveConfig defaults() {
        LiveConfig c = new LiveConfig();
        c.gradients.put("sky", new Gradient("sky", "Follow the sky", 0, 0));
        c.gradients.put("reddevils", new Gradient("reddevils", "Red Devils", 0xFFC8102E, 0xFF1A0507));
        c.gradients.put("midnight", new Gradient("midnight", "Midnight", 0xFF1B2440, 0xFF06080F));
        c.gradients.put("ocean", new Gradient("ocean", "Ocean", 0xFF0F5E8C, 0xFF07203A));
        c.gradients.put("aurora", new Gradient("aurora", "Aurora", 0xFF0E6B5C, 0xFF1C1440));
        c.gradients.put("sunset", new Gradient("sunset", "Sunset", 0xFFE0703A, 0xFF4A1F4F));
        c.gradients.put("graphite", new Gradient("graphite", "Graphite", 0xFF3A3D42, 0xFF121315));
        c.feeds.add(new Feed("ManUtd.com", "https://www.manutd.com/Feeds/NewsSecondRSSFeed", false));
        c.feeds.add(new Feed("BBC Sport", "http://feeds.bbci.co.uk/sport/football/rss.xml", true));
        c.keywords.add("Man Utd");
        c.keywords.add("Manchester United");
        c.keywords.add("Man United");
        c.keywords.add("Old Trafford");
        return c;
    }

    /** Defaults, overlaid with whatever the JSON specifies. */
    @SuppressWarnings("unchecked")
    static LiveConfig parse(String json) {
        LiveConfig c = defaults();
        Object root = Json.parse(json);
        if (!(root instanceof Map)) return c;
        Map<String, Object> m = (Map<String, Object>) root;
        c.defaultBackground = str(m.get("background"), c.defaultBackground);
        c.notice = str(m.get("notice"), c.notice);
        c.briefing = bool(m.get("briefing"), c.briefing);
        Object g = m.get("gradients");
        if (g instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) g).entrySet()) {
                if (!(e.getValue() instanceof Map)) continue;
                Map<String, Object> v = (Map<String, Object>) e.getValue();
                Integer top = color(v.get("top")), bottom = color(v.get("bottom"));
                if (top != null && bottom != null) {
                    c.gradients.put(e.getKey(), new Gradient(e.getKey(), str(v.get("label"), e.getKey()), top, bottom));
                }
            }
        }
        Object t = m.get("team");
        if (t instanceof Map) {
            Map<String, Object> v = (Map<String, Object>) t;
            c.teamName = str(v.get("name"), c.teamName);
            c.teamShort = str(v.get("short"), c.teamShort);
            c.teamId = str(v.get("espnId"), c.teamId);
            c.teamLeague = str(v.get("league"), c.teamLeague);
        }
        Object n = m.get("news");
        if (n instanceof Map) {
            Map<String, Object> v = (Map<String, Object>) n;
            if (v.get("feeds") instanceof List) {
                c.feeds.clear();
                for (Object o : (List<Object>) v.get("feeds")) {
                    if (!(o instanceof Map)) continue;
                    Map<String, Object> f = (Map<String, Object>) o;
                    String url = str(f.get("url"), "");
                    if (!url.isEmpty()) c.feeds.add(new Feed(str(f.get("name"), "News"), url, bool(f.get("filter"), false)));
                }
            }
            if (v.get("keywords") instanceof List) {
                c.keywords.clear();
                for (Object o : (List<Object>) v.get("keywords")) if (o instanceof String) c.keywords.add((String) o);
            }
            c.maxNews = num(v.get("max"), c.maxNews);
        }
        Object cards = m.get("cards");
        if (cards instanceof Map) {
            Map<String, Object> v = (Map<String, Object>) cards;
            c.showUnited = bool(v.get("united"), c.showUnited);
            c.showNews = bool(v.get("news"), c.showNews);
            c.showCalendar = bool(v.get("calendar"), c.showCalendar);
            c.showForecast = bool(v.get("forecast"), c.showForecast);
        }
        Object r = m.get("relay");
        if (r instanceof Map) {
            Map<String, Object> v = (Map<String, Object>) r;
            c.relayUrl = str(v.get("url"), c.relayUrl);
            c.relaySeconds = Math.max(2, num(v.get("pollSeconds"), c.relaySeconds));
        }
        Object rf = m.get("refresh");
        if (rf instanceof Map) {
            Map<String, Object> v = (Map<String, Object>) rf;
            c.newsMinutes = Math.max(5, num(v.get("newsMinutes"), c.newsMinutes));
            c.unitedMinutes = Math.max(5, num(v.get("unitedMinutes"), c.unitedMinutes));
            c.liveSeconds = Math.max(20, num(v.get("liveSeconds"), c.liveSeconds));
            c.configMinutes = Math.max(10, num(v.get("configMinutes"), c.configMinutes));
            c.updateCheckHours = Math.max(1, num(v.get("updateCheckHours"), c.updateCheckHours));
        }
        return c;
    }

    Gradient gradient(String id) {
        Gradient g = gradients.get(id);
        return g != null ? g : gradients.get("sky");
    }

    private static String str(Object o, String d) { return o instanceof String ? (String) o : d; }
    private static boolean bool(Object o, boolean d) { return o instanceof Boolean ? (Boolean) o : d; }
    private static int num(Object o, int d) { return o instanceof Double ? ((Double) o).intValue() : d; }

    static Integer color(Object o) {
        if (!(o instanceof String)) return null;
        String s = ((String) o).trim();
        if (s.startsWith("#")) s = s.substring(1);
        try {
            if (s.length() == 6) return (int) (0xFF000000L | Long.parseLong(s, 16));
            if (s.length() == 8) return (int) Long.parseLong(s, 16);
        } catch (NumberFormatException ignored) { }
        return null;
    }
}
