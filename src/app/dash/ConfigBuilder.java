package app.dash;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds the sing-box configuration. */
public final class ConfigBuilder {
    public static final String GROUP_REGULAR = "auto-regular";
    public static final String GROUP_LTE = "auto-lte";
    public static final String SELECTOR = "proxy";
    public static final int CLASH_PORT = 19190;
    public static final String TEST_URL = "https://www.gstatic.com/generate_204";

    public static final class Options {
        public boolean ruDirect = true;
        public boolean blockedViaVpn = true;
        public String ruleDir = "";
        public String secret = "";
        public String initialGroup = GROUP_REGULAR;
        public String logLevel = "debug";
    }

    private ConfigBuilder() {}

    public static boolean hasGroup(List<Server> servers, int group) {
        for (Server s : servers) if (s.group == group) return true;
        return false;
    }

    public static String build(List<Server> servers, Options opt) {
        List<Object> regular = new ArrayList<>();
        List<Object> lte = new ArrayList<>();
        List<Object> serverOutbounds = new ArrayList<>();
        for (Server s : servers) {
            if (s.group == Server.EXCLUDED) continue;
            serverOutbounds.add(s.outbound);
            (s.group == Server.LTE ? lte : regular).add(s.tag);
        }
        if (regular.isEmpty() && lte.isEmpty()) throw new IllegalStateException("В подписке нет подходящих серверов");

        List<Object> groups = new ArrayList<>();
        if (!regular.isEmpty()) groups.add(GROUP_REGULAR);
        if (!lte.isEmpty()) groups.add(GROUP_LTE);
        String def = groups.contains(opt.initialGroup) ? opt.initialGroup : (String) groups.get(0);

        List<Object> outbounds = new ArrayList<>();
        List<Object> selectable = new ArrayList<>(groups);
        selectable.addAll(regular);
        selectable.addAll(lte);
        outbounds.add(Json.obj("type", "selector", "tag", SELECTOR, "outbounds", selectable, "default", def,
                "interrupt_exist_connections", true));
        if (!regular.isEmpty()) outbounds.add(urltest(GROUP_REGULAR, regular));
        if (!lte.isEmpty()) outbounds.add(urltest(GROUP_LTE, lte));
        outbounds.addAll(serverOutbounds);
        outbounds.add(Json.obj("type", "direct", "tag", "direct"));

        String dir = opt.ruleDir.endsWith("/") ? opt.ruleDir : opt.ruleDir + "/";
        List<Object> ruleSets = Json.arr(
                local("ru-inside", dir + "geosite-ru-available-only-inside.srs"),
                local("ru-blocked", dir + "geosite-ru-blocked.srs"),
                local("ru-blocked-ip", dir + "geoip-ru-blocked.srs"),
                local("geosite-ru", dir + "geosite-category-ru.srs"),
                local("geoip-ru", dir + "geoip-ru.srs"));

        List<Object> ruSuffix = Json.arr("ru", "su", "xn--p1ai");

        // DNS
        List<Object> dnsRules = new ArrayList<>();
        dnsRules.add(Json.obj("rule_set", Json.arr("ru-inside"), "server", "dns-direct"));
        if (opt.blockedViaVpn) dnsRules.add(Json.obj("rule_set", Json.arr("ru-blocked"), "server", "dns-remote"));
        if (opt.ruDirect) {
            dnsRules.add(Json.obj("domain_suffix", ruSuffix, "server", "dns-direct"));
            dnsRules.add(Json.obj("rule_set", Json.arr("geosite-ru"), "server", "dns-direct"));
        }
        Map<String, Object> dns = Json.obj(
                "servers", Json.arr(
                        Json.obj("type", "udp", "tag", "dns-direct", "server", "77.88.8.8"),
                        Json.obj("type", "https", "tag", "dns-remote", "server", "1.1.1.1", "detour", SELECTOR)),
                "rules", dnsRules,
                "final", "dns-remote",
                "strategy", "ipv4_only");

        // Routing
        List<Object> rules = new ArrayList<>();
        rules.add(Json.obj("action", "sniff"));
        rules.add(Json.obj("protocol", "dns", "action", "hijack-dns"));
        rules.add(Json.obj("ip_is_private", true, "outbound", "direct"));
        rules.add(Json.obj("rule_set", Json.arr("ru-inside"), "outbound", "direct"));
        if (opt.blockedViaVpn) rules.add(Json.obj("rule_set", Json.arr("ru-blocked", "ru-blocked-ip"), "outbound", SELECTOR));
        if (opt.ruDirect) {
            rules.add(Json.obj("domain_suffix", ruSuffix, "outbound", "direct"));
            rules.add(Json.obj("rule_set", Json.arr("geosite-ru", "geoip-ru"), "outbound", "direct"));
        }
        Map<String, Object> route = Json.obj(
                "rules", rules,
                "rule_set", ruleSets,
                "final", SELECTOR,
                "auto_detect_interface", true,
                "default_domain_resolver", "dns-direct");

        Map<String, Object> tun = Json.obj("type", "tun", "tag", "tun-in",
                "address", Json.arr("172.19.0.1/30", "fdfe:dcba:9876::1/126"),
                "mtu", 9000,
                "auto_route", true,
                "stack", "mixed");

        Map<String, Object> root = Json.obj(
                "log", Json.obj("level", opt.logLevel, "timestamp", true),
                "dns", dns,
                "inbounds", Json.arr(tun),
                "outbounds", outbounds,
                "route", route,
                "experimental", Json.obj("clash_api", Json.obj(
                        "external_controller", "127.0.0.1:" + CLASH_PORT,
                        "secret", opt.secret)));
        return Json.write(root);
    }

    private static Map<String, Object> urltest(String tag, List<Object> members) {
        return Json.obj("type", "urltest", "tag", tag, "outbounds", members,
                "url", TEST_URL, "interval", "3m", "tolerance", 100,
                "interrupt_exist_connections", false);
    }

    private static Map<String, Object> local(String tag, String path) {
        return Json.obj("type", "local", "tag", tag, "format", "binary", "path", path);
    }
}
