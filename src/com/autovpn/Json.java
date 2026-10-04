package com.autovpn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON writer, works on Android and on a desktop JVM. */
public final class Json {
    private Json() {}

    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    public static List<Object> arr(Object... v) {
        return new ArrayList<>(Arrays.asList(v));
    }

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        w(sb, o, 0, true);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void w(StringBuilder sb, Object o, int ind, boolean pretty) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String) {
            str(sb, (String) o);
        } else if (o instanceof Number || o instanceof Boolean) {
            sb.append(o);
        } else if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                nl(sb, ind + 1, pretty);
                str(sb, e.getKey());
                sb.append(pretty ? ": " : ":");
                w(sb, e.getValue(), ind + 1, pretty);
            }
            nl(sb, ind, pretty);
            sb.append('}');
        } else if (o instanceof List) {
            List<Object> l = (List<Object>) o;
            if (l.isEmpty()) { sb.append("[]"); return; }
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(',');
                nl(sb, ind + 1, pretty);
                w(sb, l.get(i), ind + 1, pretty);
            }
            nl(sb, ind, pretty);
            sb.append(']');
        } else {
            str(sb, o.toString());
        }
    }

    /* ---------- parser: objects → LinkedHashMap, arrays → ArrayList, numbers → Long/Double ---------- */

    public static Object parse(String s) {
        int[] pos = {0};
        Object v = value(s, pos);
        skip(s, pos);
        if (pos[0] != s.length()) throw new IllegalArgumentException("JSON: лишние символы на позиции " + pos[0]);
        return v;
    }

    private static void skip(String s, int[] p) {
        while (p[0] < s.length() && Character.isWhitespace(s.charAt(p[0]))) p[0]++;
    }

    private static Object value(String s, int[] p) {
        skip(s, p);
        if (p[0] >= s.length()) throw new IllegalArgumentException("JSON: неожиданный конец");
        char c = s.charAt(p[0]);
        if (c == '{') {
            p[0]++;
            Map<String, Object> m = new LinkedHashMap<>();
            skip(s, p);
            if (s.charAt(p[0]) == '}') { p[0]++; return m; }
            while (true) {
                skip(s, p);
                String k = string(s, p);
                skip(s, p);
                if (s.charAt(p[0]++) != ':') throw new IllegalArgumentException("JSON: ожидалось ':'");
                m.put(k, value(s, p));
                skip(s, p);
                char d = s.charAt(p[0]++);
                if (d == '}') return m;
                if (d != ',') throw new IllegalArgumentException("JSON: ожидалось ',' или '}'");
            }
        }
        if (c == '[') {
            p[0]++;
            List<Object> l = new ArrayList<>();
            skip(s, p);
            if (s.charAt(p[0]) == ']') { p[0]++; return l; }
            while (true) {
                l.add(value(s, p));
                skip(s, p);
                char d = s.charAt(p[0]++);
                if (d == ']') return l;
                if (d != ',') throw new IllegalArgumentException("JSON: ожидалось ',' или ']'");
            }
        }
        if (c == '"') return string(s, p);
        if (s.startsWith("true", p[0])) { p[0] += 4; return Boolean.TRUE; }
        if (s.startsWith("false", p[0])) { p[0] += 5; return Boolean.FALSE; }
        if (s.startsWith("null", p[0])) { p[0] += 4; return null; }
        int st = p[0];
        while (p[0] < s.length() && "+-0123456789.eE".indexOf(s.charAt(p[0])) >= 0) p[0]++;
        String num = s.substring(st, p[0]);
        if (num.isEmpty()) throw new IllegalArgumentException("JSON: неожиданный символ '" + c + "'");
        if (num.contains(".") || num.contains("e") || num.contains("E")) return Double.parseDouble(num);
        return Long.parseLong(num);
    }

    private static String string(String s, int[] p) {
        if (s.charAt(p[0]) != '"') throw new IllegalArgumentException("JSON: ожидалась строка");
        p[0]++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = s.charAt(p[0]++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            char e = s.charAt(p[0]++);
            switch (e) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'r': sb.append('\r'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'u': sb.append((char) Integer.parseInt(s.substring(p[0], p[0] + 4), 16)); p[0] += 4; break;
                default: sb.append(e);
            }
        }
    }

    private static void nl(StringBuilder sb, int ind, boolean pretty) {
        if (!pretty) return;
        sb.append('\n');
        for (int i = 0; i < ind; i++) sb.append("  ");
    }

    private static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }
}
