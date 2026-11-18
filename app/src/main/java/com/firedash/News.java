package com.firedash;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Headlines from RSS/Atom feeds. Only the headline, source and time are shown. */
final class News {
    static final class Item {
        final String title, link, source;
        final long timeMs;
        Item(String title, String link, String source, long timeMs) { this.title = title; this.link = link; this.source = source; this.timeMs = timeMs; }
    }

    private static final Pattern ITEM = Pattern.compile("<(item|entry)[\\s>](.*?)</\\1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    static List<Item> fetch(LiveConfig c) {
        List<Item> all = new ArrayList<>();
        for (LiveConfig.Feed f : c.feeds) {
            try {
                String alt = f.url.startsWith("https:") ? "http:" + f.url.substring(6) : null;
                all.addAll(parse(Net.getFirst(f.url, alt), f.name, f.filter ? c.keywords : null));
            } catch (Exception ignored) {
                // one feed failing shouldn't hide the others
            }
        }
        return merge(all, c.maxNews);
    }

    static List<Item> parse(String xml, String source, List<String> keywords) {
        List<Item> out = new ArrayList<>();
        Matcher m = ITEM.matcher(xml);
        while (m.find()) {
            String body = m.group(2);
            String title = clean(tag(body, "title"));
            if (title.isEmpty()) continue;
            String link = clean(tag(body, "link"));
            if (link.isEmpty()) {
                Matcher h = Pattern.compile("<link[^>]*href=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(body);
                if (h.find()) link = unescape(h.group(1));
            }
            String date = tag(body, "pubDate");
            if (date.isEmpty()) date = tag(body, "updated");
            if (date.isEmpty()) date = tag(body, "published");
            if (keywords != null) {
                String hay = (title + " " + clean(tag(body, "description"))).toLowerCase(Locale.ROOT);
                boolean hit = false;
                for (String k : keywords) if (hay.contains(k.toLowerCase(Locale.ROOT))) { hit = true; break; }
                if (!hit) continue;
            }
            out.add(new Item(title, link, source, parseDate(date.trim())));
        }
        return out;
    }

    static List<Item> merge(List<Item> all, int max) {
        Collections.sort(all, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) { return Long.compare(b.timeMs, a.timeMs); }
        });
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Item i : all) {
            String key = i.title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (seen.add(key)) out.add(i);
            if (out.size() >= max) break;
        }
        return out;
    }

    private static String tag(String body, String name) {
        Matcher m = Pattern.compile("<" + name + "(?:\\s[^>]*)?>(.*?)</" + name + ">", Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(body);
        return m.find() ? m.group(1) : "";
    }

    private static String clean(String s) {
        s = s.trim();
        if (s.startsWith("<![CDATA[")) s = s.substring(9, s.endsWith("]]>") ? s.length() - 3 : s.length());
        s = s.replaceAll("<[^>]+>", " ");
        return unescape(s).replaceAll("\\s+", " ").trim();
    }

    static String unescape(String s) {
        Matcher m = Pattern.compile("&(#x?[0-9a-fA-F]+|amp|lt|gt|quot|apos|nbsp);").matcher(s);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            String e = m.group(1), r;
            switch (e) {
                case "amp": r = "&"; break;
                case "lt": r = "<"; break;
                case "gt": r = ">"; break;
                case "quot": r = "\""; break;
                case "apos": r = "'"; break;
                case "nbsp": r = " "; break;
                default:
                    try {
                        int cp = e.startsWith("#x") || e.startsWith("#X") ? Integer.parseInt(e.substring(2), 16) : Integer.parseInt(e.substring(1));
                        r = new String(Character.toChars(cp));
                    } catch (RuntimeException ex) {
                        r = m.group(0);
                    }
            }
            m.appendReplacement(b, Matcher.quoteReplacement(r));
        }
        m.appendTail(b);
        return b.toString();
    }

    static long parseDate(String s) {
        if (s.isEmpty()) return 0;
        String[] patterns = { "EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "dd MMM yyyy HH:mm:ss Z",
                "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'" };
        for (String p : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, Locale.ENGLISH);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                return f.parse(s).getTime();
            } catch (ParseException | IllegalArgumentException ignored) { }
        }
        return 0;
    }

    // Cached as lines: title \u001f link \u001f source \u001f time
    static String serialize(List<Item> items) {
        StringBuilder b = new StringBuilder();
        for (Item i : items) {
            b.append(i.title.replace('\n', ' ')).append('\u001f').append(i.link).append('\u001f').append(i.source)
                    .append('\u001f').append(i.timeMs).append('\n');
        }
        return b.toString();
    }

    static List<Item> deserialize(String s) {
        List<Item> out = new ArrayList<>();
        if (s == null) return out;
        for (String line : s.split("\n")) {
            String[] f = line.split("\u001f");
            if (f.length < 4) continue;
            try {
                out.add(new Item(f[0], f[1], f[2], Long.parseLong(f[3])));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    /** "12 min ago", "3 h ago", "2 days ago". */
    static String ago(long timeMs, long now) {
        if (timeMs <= 0) return "";
        long min = Math.max(0, (now - timeMs) / 60000);
        if (min < 1) return "just now";
        if (min < 60) return min + " min ago";
        long h = min / 60;
        if (h < 24) return h + " h ago";
        long d = h / 24;
        return d == 1 ? "yesterday" : d + " days ago";
    }
}
