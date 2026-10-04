package com.autovpn;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses a subscription (base64 or plain list of share links) into sing-box outbounds. */
public final class SubParser {
    private SubParser() {}

    public static List<Server> parse(String text, List<String> warnings) {
        List<Server> out = new ArrayList<>();
        if (text == null) return out;
        String body = text.trim();
        if (!body.startsWith("[") && !body.startsWith("{") && !body.contains("://")) {
            String decoded = b64(body);
            if (decoded != null && (decoded.contains("://") || decoded.trim().startsWith("[") || decoded.trim().startsWith("{"))) {
                body = decoded.trim();
            }
        }
        if (body.startsWith("[") || body.startsWith("{")) {
            try {
                List<Server> js = XrayJson.parse(Json.parse(body), warnings);
                int n = 0;
                for (Server s : js) {
                    n++;
                    s.tag = "srv" + n;
                    s.outbound.put("tag", s.tag);
                    classify(s);
                    out.add(s);
                }
            } catch (Exception e) {
                warnings.add("Не разобрал JSON-подписку: " + e.getMessage());
            }
            return out;
        }
        int n = 0;
        for (String line : body.split("[\\r\\n]+")) {
            line = line.trim();
            if (line.isEmpty() || !line.contains("://")) continue;
            try {
                Server s = parseLink(line, warnings);
                if (s == null) continue;
                n++;
                s.tag = "srv" + n;
                s.outbound.put("tag", s.tag);
                classify(s);
                out.add(s);
            } catch (Exception e) {
                warnings.add("Не разобрал ссылку: " + shortLink(line) + " (" + e.getMessage() + ")");
            }
        }
        return out;
    }

    /* ---------- grouping ---------- */

    static void classify(Server s) {
        String n = s.rawName.toLowerCase(Locale.ROOT);
        if (n.contains("авто") || n.contains("auto")) {
            s.group = Server.EXCLUDED;
            s.excludeReason = "Автовыбор провайдера, дублирует наш";
        } else if (s.rawName.contains("🇷🇺") || n.contains("россия") || n.contains("russia")) {
            s.group = Server.EXCLUDED;
            s.excludeReason = "Российские сайты и так идут напрямую";
        } else if (n.contains("lte") || n.contains("белые списки") || n.contains("белый список") || n.contains("whitelist")) {
            s.group = Server.LTE;
        } else {
            s.group = Server.REGULAR;
        }
    }

    /** Keeps letters, digits, punctuation and flag emoji; drops other pictographs. */
    static String cleanName(String raw) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < raw.length()) {
            int cp = raw.codePointAt(i);
            int len = Character.charCount(cp);
            boolean flag = cp >= 0x1F1E6 && cp <= 0x1F1FF;
            if (flag) {
                sb.appendCodePoint(cp);
                int next = i + len;
                if (next < raw.length()) {
                    int cp2 = raw.codePointAt(next);
                    if (cp2 >= 0x1F1E6 && cp2 <= 0x1F1FF) {
                        sb.appendCodePoint(cp2);
                        len += Character.charCount(cp2);
                    }
                }
                sb.append(' ');
            } else if (Character.isLetterOrDigit(cp) || Character.isSpaceChar(cp) || "|#-_.,()+/:".indexOf(cp) >= 0) {
                sb.appendCodePoint(cp);
            } else {
                sb.append(' ');
            }
            i += len;
        }
        String s = sb.toString().replaceAll("\\s+", " ").trim();
        s = s.replaceAll("^[|\\s]+", "").replaceAll("[|\\s]+$", "");
        s = s.replaceAll("\\s*\\|\\s*", " | ");
        return s.isEmpty() ? raw.trim() : s;
    }

    /* ---------- links ---------- */

    static Server parseLink(String link, List<String> warnings) throws Exception {
        String scheme = link.substring(0, link.indexOf("://")).toLowerCase(Locale.ROOT);
        switch (scheme) {
            case "vless": return parseVlessTrojan(link, "vless", warnings);
            case "trojan": return parseVlessTrojan(link, "trojan", warnings);
            case "vmess": return parseVmess(link, warnings);
            case "ss": return parseSs(link);
            case "hy2":
            case "hysteria2": return parseHy2(link);
            default:
                warnings.add("Протокол " + scheme + " не поддерживается: " + shortLink(link));
                return null;
        }
    }

    private static Server parseVlessTrojan(String link, String type, List<String> warnings) throws Exception {
        Parts p = Parts.of(link);
        Server s = newServer(p.fragment, p.host);
        Map<String, Object> o = Json.obj("type", type, "tag", "", "server", p.host, "server_port", p.port);
        if (type.equals("vless")) {
            o.put("uuid", p.user);
            String flow = p.q("flow");
            if (!flow.isEmpty()) o.put("flow", flow);
            o.put("packet_encoding", "xudp");
        } else {
            o.put("password", p.user);
        }
        String security = p.q("security");
        if (type.equals("trojan") && security.isEmpty()) security = "tls";
        if (!applyStream(o, p.query, security, p.host, warnings, s.rawName)) return null;
        s.outbound = o;
        return s;
    }

    private static Server parseVmess(String link, List<String> warnings) {
        String json = b64(link.substring("vmess://".length()));
        if (json == null) throw new IllegalArgumentException("vmess: не base64");
        Map<String, String> m = flatJson(json);
        String host = m.getOrDefault("add", "");
        Server s = newServer(m.getOrDefault("ps", host), host);
        Map<String, Object> o = Json.obj("type", "vmess", "tag", "", "server", host,
                "server_port", Integer.parseInt(m.getOrDefault("port", "443")),
                "uuid", m.getOrDefault("id", ""),
                "alter_id", Integer.parseInt(m.getOrDefault("aid", "0").isEmpty() ? "0" : m.get("aid")),
                "security", m.getOrDefault("scy", "auto").isEmpty() ? "auto" : m.getOrDefault("scy", "auto"),
                "packet_encoding", "xudp");
        Map<String, String> q = new HashMap<>();
        q.put("type", m.getOrDefault("net", "tcp"));
        q.put("headerType", m.getOrDefault("type", ""));
        q.put("host", m.getOrDefault("host", ""));
        q.put("path", m.getOrDefault("path", ""));
        q.put("serviceName", m.getOrDefault("path", ""));
        q.put("sni", m.getOrDefault("sni", ""));
        q.put("alpn", m.getOrDefault("alpn", ""));
        q.put("fp", m.getOrDefault("fp", ""));
        String sec = "tls".equals(m.get("tls")) ? "tls" : "";
        if (!applyStream(o, q, sec, host, warnings, s.rawName)) return null;
        s.outbound = o;
        return s;
    }

    private static Server parseSs(String link) throws Exception {
        String rest = link.substring("ss://".length());
        String fragment = "";
        int hash = rest.indexOf('#');
        if (hash >= 0) { fragment = rest.substring(hash + 1); rest = rest.substring(0, hash); }
        int qm = rest.indexOf('?');
        if (qm >= 0) rest = rest.substring(0, qm);
        String userInfo, hostPort;
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            userInfo = rest.substring(0, at);
            hostPort = rest.substring(at + 1);
            String dec = b64(dec(userInfo));
            if (dec != null && dec.contains(":")) userInfo = dec; else userInfo = dec(userInfo);
        } else {
            String dec = b64(rest);
            if (dec == null) throw new IllegalArgumentException("ss: формат");
            at = dec.lastIndexOf('@');
            userInfo = dec.substring(0, at);
            hostPort = dec.substring(at + 1);
        }
        int colon = userInfo.indexOf(':');
        String method = userInfo.substring(0, colon);
        String password = userInfo.substring(colon + 1);
        int pc = hostPort.lastIndexOf(':');
        String host = hostPort.substring(0, pc).replace("[", "").replace("]", "");
        int port = Integer.parseInt(hostPort.substring(pc + 1).replaceAll("[^0-9]", ""));
        Server s = newServer(dec(fragment), host);
        s.outbound = Json.obj("type", "shadowsocks", "tag", "", "server", host, "server_port", port,
                "method", method, "password", password);
        return s;
    }

    private static Server parseHy2(String link) throws Exception {
        Parts p = Parts.of(link);
        Server s = newServer(p.fragment, p.host);
        Map<String, Object> tls = Json.obj("enabled", true,
                "server_name", p.q("sni").isEmpty() ? p.host : p.q("sni"),
                "alpn", Json.arr("h3"));
        if ("1".equals(p.q("insecure"))) tls.put("insecure", true);
        Map<String, Object> o = Json.obj("type", "hysteria2", "tag", "", "server", p.host, "server_port", p.port,
                "password", p.user, "tls", tls);
        if (!p.q("obfs").isEmpty()) {
            o.put("obfs", Json.obj("type", p.q("obfs"), "password", p.q("obfs-password")));
        }
        s.outbound = o;
        return s;
    }

    /** Adds tls and transport sections. Returns false when the transport is unsupported. */
    static boolean applyStream(Map<String, Object> o, Map<String, String> q, String security, String host,
                               List<String> warnings, String name) {
        String sni = val(q, "sni");
        if (sni.isEmpty()) sni = val(q, "peer");
        String hostHeader = val(q, "host");
        String fp = val(q, "fp");
        if ("reality".equals(security)) {
            Map<String, Object> tls = Json.obj("enabled", true,
                    "server_name", sni.isEmpty() ? host : sni,
                    "utls", Json.obj("enabled", true, "fingerprint", fp.isEmpty() ? "chrome" : fp),
                    "reality", Json.obj("enabled", true, "public_key", val(q, "pbk"), "short_id", val(q, "sid")));
            o.put("tls", tls);
        } else if ("tls".equals(security) || "xtls".equals(security)) {
            Map<String, Object> tls = Json.obj("enabled", true,
                    "server_name", !sni.isEmpty() ? sni : !hostHeader.isEmpty() ? hostHeader : host);
            if ("1".equals(val(q, "allowInsecure")) || "true".equals(val(q, "allowInsecure"))) tls.put("insecure", true);
            String alpn = val(q, "alpn");
            if (!alpn.isEmpty()) tls.put("alpn", new java.util.ArrayList<Object>(java.util.Arrays.asList(alpn.split(","))));
            if (!fp.isEmpty()) tls.put("utls", Json.obj("enabled", true, "fingerprint", fp));
            o.put("tls", tls);
        }
        String type = val(q, "type");
        if (type.isEmpty()) type = "tcp";
        String path = val(q, "path");
        switch (type) {
            case "tcp":
            case "raw":
                if ("http".equals(val(q, "headerType"))) {
                    o.put("transport", Json.obj("type", "http",
                            "host", hostHeader.isEmpty() ? null : Json.arr((Object[]) hostHeader.split(",")),
                            "path", path.isEmpty() ? "/" : path));
                }
                return true;
            case "ws": {
                Map<String, Object> t = Json.obj("type", "ws");
                Matcher m = Pattern.compile("[?&]ed=(\\d+)").matcher(path);
                if (m.find()) {
                    t.put("max_early_data", Integer.parseInt(m.group(1)));
                    t.put("early_data_header_name", "Sec-WebSocket-Protocol");
                    path = path.replaceAll("[?&]ed=\\d+", "");
                }
                t.put("path", path.isEmpty() ? "/" : path);
                if (!hostHeader.isEmpty()) t.put("headers", Json.obj("Host", hostHeader));
                o.put("transport", t);
                return true;
            }
            case "grpc":
                o.put("transport", Json.obj("type", "grpc", "service_name", val(q, "serviceName")));
                return true;
            case "http":
            case "h2":
                o.put("transport", Json.obj("type", "http",
                        "host", hostHeader.isEmpty() ? null : Json.arr((Object[]) hostHeader.split(",")),
                        "path", path.isEmpty() ? "/" : path));
                return true;
            case "httpupgrade":
                o.put("transport", Json.obj("type", "httpupgrade",
                        "host", hostHeader.isEmpty() ? null : hostHeader,
                        "path", path.isEmpty() ? "/" : path));
                return true;
            default:
                warnings.add("«" + name + "»: транспорт " + type + " не поддерживается ядром sing-box, сервер пропущен");
                return false;
        }
    }

    /* ---------- helpers ---------- */

    static Server newServer(String rawName, String host) {
        Server s = new Server();
        s.rawName = (rawName == null || rawName.trim().isEmpty()) ? host : rawName.trim();
        s.name = cleanName(s.rawName);
        return s;
    }

    private static String val(Map<String, String> q, String k) {
        String v = q.get(k);
        return v == null ? "" : v.trim();
    }

    static String dec(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    static String b64(String s) {
        String t = s.trim().replaceAll("\\s", "");
        if (t.isEmpty()) return null;
        try {
            String norm = t.replace('-', '+').replace('_', '/');
            while (norm.length() % 4 != 0) norm += "=";
            return new String(Base64.getDecoder().decode(norm), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String shortLink(String l) {
        return l.length() > 48 ? l.substring(0, 48) + "…" : l;
    }

    static Map<String, String> flatJson(String json) {
        Map<String, String> m = new HashMap<>();
        Matcher mt = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|-?[0-9.]+|true|false|null)").matcher(json);
        while (mt.find()) {
            String v = mt.group(3) != null ? mt.group(3).replace("\\/", "/").replace("\\\"", "\"") : mt.group(2);
            if ("null".equals(v)) v = "";
            m.put(mt.group(1), v);
        }
        return m;
    }

    /** scheme://user@host:port?query#fragment */
    static final class Parts {
        String user, host, fragment = "";
        int port;
        Map<String, String> query = new HashMap<>();

        String q(String k) {
            String v = query.get(k);
            return v == null ? "" : v;
        }

        static Parts of(String link) {
            Parts p = new Parts();
            String rest = link.substring(link.indexOf("://") + 3);
            int hash = rest.indexOf('#');
            if (hash >= 0) { p.fragment = dec(rest.substring(hash + 1)); rest = rest.substring(0, hash); }
            int qm = rest.indexOf('?');
            String query = "";
            if (qm >= 0) { query = rest.substring(qm + 1); rest = rest.substring(0, qm); }
            if (rest.endsWith("/")) rest = rest.substring(0, rest.length() - 1);
            int at = rest.lastIndexOf('@');
            p.user = dec(rest.substring(0, at));
            String hp = rest.substring(at + 1);
            if (hp.startsWith("[")) {
                int close = hp.indexOf(']');
                p.host = hp.substring(1, close);
                p.port = Integer.parseInt(hp.substring(close + 2));
            } else {
                int c = hp.lastIndexOf(':');
                p.host = hp.substring(0, c);
                p.port = Integer.parseInt(hp.substring(c + 1));
            }
            for (String kv : query.split("&")) {
                if (kv.isEmpty()) continue;
                int eq = kv.indexOf('=');
                if (eq < 0) p.query.put(dec(kv), "");
                else p.query.put(dec(kv.substring(0, eq)), dec(kv.substring(eq + 1)));
            }
            return p;
        }
    }
}
