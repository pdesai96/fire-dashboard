package com.firedash;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Small JSON reader: objects become Maps, arrays Lists, numbers Doubles, null stays null. */
final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    static Object parse(String text) {
        Json j = new Json(text);
        Object v = j.value();
        j.ws();
        if (j.i != j.s.length()) throw new IllegalArgumentException("Trailing data at " + j.i);
        return v;
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++; else break;
        }
    }

    private Object value() {
        ws();
        if (i >= s.length()) throw new IllegalArgumentException("Unexpected end");
        char c = s.charAt(i);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        return number();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new HashMap<>();
        i++;
        ws();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            String k = string();
            ws();
            expect(':');
            m.put(k, value());
            ws();
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw new IllegalArgumentException("Expected , or } at " + (i - 1));
        }
    }

    private List<Object> array() {
        List<Object> a = new ArrayList<>();
        i++;
        ws();
        if (s.charAt(i) == ']') { i++; return a; }
        while (true) {
            a.add(value());
            ws();
            char c = s.charAt(i++);
            if (c == ']') return a;
            if (c != ',') throw new IllegalArgumentException("Expected , or ] at " + (i - 1));
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: b.append(e);
                }
            } else {
                b.append(c);
            }
        }
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw new IllegalArgumentException("Bad value at " + i);
        return Double.valueOf(s.substring(start, i));
    }

    private void expect(char c) {
        if (i >= s.length() || s.charAt(i) != c) throw new IllegalArgumentException("Expected " + c + " at " + i);
        i++;
    }

    // ---- writing

    /** A JSON string literal for s (null becomes null). */
    static String quote(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': b.append('\\').append('"'); break;
                case '\\': b.append('\\').append('\\'); break;
                case '\n': b.append('\\').append('n'); break;
                case '\r': b.append('\\').append('r'); break;
                case '\t': b.append('\\').append('t'); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        b.append('\\').append('u').append(String.format(java.util.Locale.ROOT, "%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.append('"').toString();
    }

    /** Builds a flat JSON object from key/value pairs; values may be String, Number, Boolean or null. */
    static String object(Object... kv) {
        StringBuilder b = new StringBuilder("{");
        for (int k = 0; k + 1 < kv.length; k += 2) {
            if (k > 0) b.append(',');
            b.append(quote(String.valueOf(kv[k]))).append(':');
            Object v = kv[k + 1];
            if (v == null) b.append("null");
            else if (v instanceof Number || v instanceof Boolean) b.append(v);
            else b.append(quote(v.toString()));
        }
        return b.append('}').toString();
    }

    // ---- reading helpers for parsed maps

    static String str(Object map, String key) {
        if (!(map instanceof Map)) return null;
        Object v = ((Map<?, ?>) map).get(key);
        return v instanceof String ? (String) v : null;
    }

    static Object get(Object map, String key) {
        return map instanceof Map ? ((Map<?, ?>) map).get(key) : null;
    }
}
