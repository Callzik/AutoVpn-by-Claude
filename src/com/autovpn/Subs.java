package com.autovpn;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Several subscriptions merged into one server list. */
public final class Subs {
    public static final class Entry {
        public String url, name, body;
    }

    private Subs() {}

    public static boolean isHttp(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }

    /** Readable name: the panel's profile-title, else the host, else the key type. */
    public static String label(Prefs prefs, String url) {
        String n = prefs.subName(url);
        if (!n.isEmpty()) return n;
        if (isHttp(url)) {
            try {
                String h = new URI(url).getHost();
                if (h != null && !h.isEmpty()) return h;
            } catch (Exception ignored) {
            }
            return "Подписка";
        }
        int i = url.indexOf("://");
        return i > 0 ? "Ключ " + url.substring(0, i) : "Ключ";
    }

    /** What is saved on the phone, without network. */
    public static List<Entry> cached(Prefs prefs) {
        List<Entry> out = new ArrayList<>();
        for (String url : prefs.subUrls()) {
            Entry e = new Entry();
            e.url = url;
            e.name = label(prefs, url);
            e.body = isHttp(url) ? prefs.subCache(url) : url;
            if (!e.body.isEmpty()) out.add(e);
        }
        return out;
    }

    /**
     * Parses every subscription and joins the servers. Server names stay unique across subscriptions
     * (the manual choice is stored by name) and tags are renumbered so they never clash.
     * Subscriptions that are only a panel stub go to {@code stubs} as "name: message".
     */
    public static List<Server> merge(List<Entry> entries, List<String> warnings, List<String> stubs) {
        List<Server> out = new ArrayList<>();
        for (Entry e : entries) {
            List<String> w = new ArrayList<>();
            List<Server> list = SubParser.parse(e.body, w);
            for (String x : w) warnings.add(e.name + ": " + x);
            String stub = BoxVpnService.stubReason(list);
            if (stub != null) {
                stubs.add(e.name + ": «" + stub + "»");
                continue;
            }
            for (Server s : list) {
                s.sub = e.name;
                out.add(s);
            }
        }
        Set<String> names = new HashSet<>();
        int n = 0;
        for (Server s : out) {
            String base = s.name, name = base;
            for (int k = 2; names.contains(name); k++) name = base + " (" + k + ")";
            s.name = name;
            names.add(name);
            n++;
            s.tag = "srv" + n;
            s.outbound.put("tag", s.tag);
        }
        return out;
    }
}
