package com.autovpn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts JSON subscriptions into sing-box outbounds:
 *  - Xray full configs (array of {remarks, outbounds:[{protocol, settings, streamSettings}]}), as sent by Remnawave etc.
 *  - sing-box configs / outbound lists ({outbounds:[{type, server, ...}]}).
 */
final class XrayJson {
    private XrayJson() {}

    private static final Set<String> SB_TYPES = new HashSet<>(java.util.Arrays.asList(
            "vless", "vmess", "trojan", "shadowsocks", "hysteria2", "tuic"));

    static List<Server> parse(Object root, List<String> warnings) {
        List<Object> configs = new ArrayList<>();
        if (root instanceof List) configs.addAll((List<?>) root);
        else configs.add(root);

        List<Server> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object c : configs) {
            Map<String, Object> cfg = map(c);
            if (cfg == null) continue;
            String remarks = str(cfg.get("remarks"));
            List<Object> obs = list(cfg.get("outbounds"));
            if (obs == null && cfg.containsKey("type")) { obs = new ArrayList<>(); obs.add(cfg); }
            if (obs == null) continue;

            List<Server> fromCfg = new ArrayList<>();
            for (Object ob : obs) {
                Map<String, Object> o = map(ob);
                if (o == null) continue;
                Server s;
                try {
                    s = o.containsKey("protocol") ? fromXray(o, obs, remarks, warnings) : fromSingBox(o, remarks);
                } catch (Exception e) {
                    warnings.add("«" + remarks + "»: не удалось разобрать сервер (" + e.getMessage() + ")");
                    continue;
                }
                if (s != null) fromCfg.add(s);
            }
            if (fromCfg.size() > 1) {
                for (int i = 0; i < fromCfg.size(); i++) {
                    Server s = fromCfg.get(i);
                    s.rawName = s.rawName + " #" + (i + 1);
                    s.name = SubParser.cleanName(s.rawName);
                }
            }
            for (Server s : fromCfg) {
                String key = Json.write(s.outbound) + (s.xray != null ? Json.write(s.xray) : "") + "|" + s.rawName.replaceAll(" #\\d+$", "");
                if (seen.add(key)) out.add(s);
            }
        }
        return out;
    }

    /* ---------- sing-box outbound ---------- */

    private static Server fromSingBox(Map<String, Object> o, String remarks) {
        String type = str(o.get("type"));
        if (!SB_TYPES.contains(type) || o.get("server") == null) return null;
        String tag = str(o.get("tag"));
        String name = !remarks.isEmpty() ? remarks : tag;
        Server s = SubParser.newServer(name, str(o.get("server")));
        Map<String, Object> copy = new java.util.LinkedHashMap<>(o);
        copy.remove("detour");
        copy.remove("domain_resolver");
        s.outbound = copy;
        return s;
    }

    /* ---------- Xray outbound ---------- */

    private static Server fromXray(Map<String, Object> o, List<Object> all, String remarks, List<String> warnings) {
        String proto = str(o.get("protocol"));
        Map<String, Object> settings = map(o.get("settings"));
        if (settings == null) return null;
        String name = !remarks.isEmpty() ? remarks : str(o.get("tag"));

        Map<String, Object> sb;
        String host;
        switch (proto) {
            case "vless":
            case "vmess": {
                Map<String, Object> vn = first(settings.get("vnext"));
                if (vn == null) return null;
                host = str(vn.get("address"));
                Map<String, Object> user = first(vn.get("users"));
                if (user == null) return null;
                sb = Json.obj("type", proto, "tag", "", "server", host, "server_port", num(vn.get("port")),
                        "uuid", str(user.get("id")));
                if (proto.equals("vless")) {
                    String flow = str(user.get("flow"));
                    if (!flow.isEmpty()) sb.put("flow", flow);
                } else {
                    sb.put("alter_id", num(user.get("alterId")));
                    String sec = str(user.get("security"));
                    sb.put("security", sec.isEmpty() ? "auto" : sec);
                }
                sb.put("packet_encoding", "xudp");
                break;
            }
            case "trojan":
            case "shadowsocks": {
                Map<String, Object> sv = first(settings.get("servers"));
                if (sv == null) return null;
                host = str(sv.get("address"));
                sb = Json.obj("type", proto, "tag", "", "server", host, "server_port", num(sv.get("port")),
                        "password", str(sv.get("password")));
                if (proto.equals("shadowsocks")) sb.put("method", str(sv.get("method")));
                break;
            }
            default:
                return null; // freedom, blackhole, dns, loopback …
        }
        Server s = SubParser.newServer(name, host);

        Map<String, Object> st = map(o.get("streamSettings"));
        if (st == null) st = new HashMap<>();
        Map<String, String> q = new HashMap<>();
        String net = str(st.get("network"));
        if (SubParser.isXhttp(net)) {
            // sing-box has no xhttp: hand this outbound to the bundled Xray as it is
            s.xray = copy(o);
            s.xray.remove("tag");
            s.xrayDeps = deps(st, all);
            s.outbound = SubParser.xrayPlaceholder();
            return s;
        }
        q.put("type", net.isEmpty() ? "tcp" : net);
        String security = str(st.get("security"));
        if (security.equals("none")) security = "";

        Map<String, Object> tls = map(st.get("tlsSettings"));
        Map<String, Object> reality = map(st.get("realitySettings"));
        if ("reality".equals(security) && reality != null) {
            q.put("sni", str(reality.get("serverName")));
            q.put("fp", str(reality.get("fingerprint")));
            q.put("pbk", str(reality.get("publicKey")));
            q.put("sid", str(reality.get("shortId")));
        } else if (tls != null) {
            q.put("sni", str(tls.get("serverName")));
            q.put("fp", str(tls.get("fingerprint")));
            if (Boolean.TRUE.equals(tls.get("allowInsecure"))) q.put("allowInsecure", "1");
            List<Object> alpn = list(tls.get("alpn"));
            if (alpn != null && !alpn.isEmpty()) {
                StringBuilder a = new StringBuilder();
                for (Object x : alpn) { if (a.length() > 0) a.append(','); a.append(str(x)); }
                q.put("alpn", a.toString());
            }
        }

        switch (q.get("type")) {
            case "ws": {
                Map<String, Object> ws = map(st.get("wsSettings"));
                if (ws != null) {
                    q.put("path", str(ws.get("path")));
                    String h = str(ws.get("host"));
                    Map<String, Object> hdr = map(ws.get("headers"));
                    if (h.isEmpty() && hdr != null) h = str(hdr.get("Host"));
                    q.put("host", h);
                }
                break;
            }
            case "grpc": {
                Map<String, Object> g = map(st.get("grpcSettings"));
                if (g != null) q.put("serviceName", str(g.get("serviceName")));
                break;
            }
            case "httpupgrade": {
                Map<String, Object> h = map(st.get("httpupgradeSettings"));
                if (h != null) { q.put("path", str(h.get("path"))); q.put("host", str(h.get("host"))); }
                break;
            }
            case "h2":
            case "http": {
                Map<String, Object> h = map(st.get("httpSettings"));
                if (h != null) { q.put("path", str(h.get("path"))); q.put("host", joinList(h.get("host"))); }
                break;
            }
            case "tcp":
            case "raw": {
                Map<String, Object> t = map(st.get("tcpSettings"));
                if (t == null) t = map(st.get("rawSettings"));
                Map<String, Object> header = t == null ? null : map(t.get("header"));
                if (header != null && "http".equals(str(header.get("type")))) {
                    q.put("headerType", "http");
                    Map<String, Object> req = map(header.get("request"));
                    if (req != null) {
                        q.put("path", firstStr(req.get("path")));
                        Map<String, Object> hh = map(req.get("headers"));
                        if (hh != null) q.put("host", joinList(hh.get("Host")));
                    }
                }
                break;
            }
            default:
                break;
        }
        if (!SubParser.applyStream(sb, q, security, host, warnings, s.rawName)) return null;
        s.outbound = sb;
        return s;
    }

    /** Outbounds this one dials through (sockopt.dialerProxy, also inside downloadSettings), followed recursively. */
    private static List<Map<String, Object>> deps(Map<String, Object> st, List<Object> all) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<String> want = new ArrayList<>();
        collectDialer(st, want);
        for (int i = 0; i < want.size() && i < 8; i++) {
            String tag = want.get(i);
            for (Object ob : all) {
                Map<String, Object> d = map(ob);
                if (d != null && tag.equals(str(d.get("tag")))) {
                    boolean dup = false;
                    for (Map<String, Object> e : out) dup |= tag.equals(str(e.get("tag")));
                    if (!dup) {
                        out.add(copy(d));
                        collectDialer(map(d.get("streamSettings")), want);
                    }
                    break;
                }
            }
        }
        return out;
    }

    private static void collectDialer(Map<String, Object> st, List<String> into) {
        if (st == null) return;
        Map<String, Object> so = map(st.get("sockopt"));
        if (so != null && !str(so.get("dialerProxy")).isEmpty()) into.add(str(so.get("dialerProxy")));
        Map<String, Object> xh = map(st.get("xhttpSettings"));
        Map<String, Object> extra = xh == null ? null : map(xh.get("extra"));
        Map<String, Object> dl = extra == null ? null : map(extra.get("downloadSettings"));
        if (dl != null) collectDialer(dl, into);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copy(Map<String, Object> o) {
        try {
            return (Map<String, Object>) Json.parse(Json.write(o));
        } catch (Exception e) {
            return new java.util.LinkedHashMap<>(o);
        }
    }

    /**
     * Config for the bundled Xray: one password-protected local socks inbound per server, routed to that
     * server's outbound. sing-box reaches each server through its inbound.
     */
    static String buildCoreConfig(List<Server> servers, String user, String pass) {
        List<Object> inbounds = new ArrayList<>();
        List<Object> outbounds = new ArrayList<>();
        List<Object> rules = new ArrayList<>();
        outbounds.add(Json.obj("protocol", "blackhole", "tag", "block"));
        for (Server s : servers) {
            if (s.xray == null) continue;
            String in = "in-" + s.tag, out = "out-" + s.tag;
            inbounds.add(Json.obj("tag", in, "listen", "127.0.0.1", "port", s.outbound.get("server_port"), "protocol", "socks",
                    "settings", Json.obj("auth", "password", "accounts", Json.arr(Json.obj("user", user, "pass", pass)),
                            "udp", true, "ip", "127.0.0.1")));
            Map<String, Object> ob = copy(s.xray);
            ob.put("tag", out);
            // dialer chains get tags unique to this server
            Map<String, String> rename = new HashMap<>();
            int k = 0;
            if (s.xrayDeps != null) for (Map<String, Object> d : s.xrayDeps) rename.put(str(d.get("tag")), out + "-via" + (++k));
            retag(ob, rename);
            outbounds.add(ob);
            if (s.xrayDeps != null) {
                for (Map<String, Object> d0 : s.xrayDeps) {
                    Map<String, Object> d = copy(d0);
                    d.put("tag", rename.get(str(d0.get("tag"))));
                    retag(d, rename);
                    outbounds.add(d);
                }
            }
            rules.add(Json.obj("type", "field", "inboundTag", Json.arr(in), "outboundTag", out));
        }
        return Json.write(Json.obj(
                "log", Json.obj("loglevel", "warning"),
                "inbounds", inbounds,
                "outbounds", outbounds,
                "routing", Json.obj("domainStrategy", "AsIs", "rules", rules)));
    }

    /** Rewrites sockopt.dialerProxy references (also in xhttp downloadSettings). */
    private static void retag(Map<String, Object> ob, Map<String, String> rename) {
        Map<String, Object> st = map(ob.get("streamSettings"));
        retagStream(st, rename);
    }

    private static void retagStream(Map<String, Object> st, Map<String, String> rename) {
        if (st == null) return;
        Map<String, Object> so = map(st.get("sockopt"));
        if (so != null && so.containsKey("dialerProxy")) {
            String t = str(so.get("dialerProxy"));
            if (rename.containsKey(t)) so.put("dialerProxy", rename.get(t));
            else so.remove("dialerProxy"); // unknown chain would stop Xray from starting at all
        }
        Map<String, Object> xh = map(st.get("xhttpSettings"));
        Map<String, Object> extra = xh == null ? null : map(xh.get("extra"));
        if (extra != null) retagStream(map(extra.get("downloadSettings")), rename);
    }

    /* ---------- helpers ---------- */

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    private static Map<String, Object> first(Object o) {
        List<Object> l = list(o);
        return l == null || l.isEmpty() ? null : map(l.get(0));
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private static String firstStr(Object o) {
        List<Object> l = list(o);
        if (l != null) return l.isEmpty() ? "" : str(l.get(0));
        return str(o);
    }

    private static String joinList(Object o) {
        List<Object> l = list(o);
        if (l == null) return str(o);
        StringBuilder sb = new StringBuilder();
        for (Object x : l) { if (sb.length() > 0) sb.append(','); sb.append(str(x)); }
        return sb.toString();
    }

    private static int num(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(str(o)); } catch (Exception e) { return 0; }
    }
}
