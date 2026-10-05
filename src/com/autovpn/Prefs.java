package com.autovpn;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    public static final String MODE_WL = "wl";
    public static final String MODE_NET = "net";

    private final SharedPreferences p;

    public Prefs(Context c) {
        p = c.getSharedPreferences("main", Context.MODE_PRIVATE);
    }

    public String subUrl() { return p.getString("sub_url", ""); }
    public void subUrl(String v) { p.edit().putString("sub_url", v).apply(); }

    public String subCache() { return p.getString("sub_cache", ""); }
    public long subUpdated() { return p.getLong("sub_updated", 0); }
    public void subCache(String body) {
        p.edit().putString("sub_cache", body).putLong("sub_updated", System.currentTimeMillis()).apply();
    }

    public void clearSubCache() { p.edit().remove("sub_cache").remove("sub_updated").apply(); }

    public String mode() { return p.getString("mode", MODE_WL); }
    public void mode(String v) { p.edit().putString("mode", v).apply(); }

    public boolean ruDirect() { return p.getBoolean("ru_direct", true); }
    public void ruDirect(boolean v) { p.edit().putBoolean("ru_direct", v).apply(); }

    public boolean blockedVpn() { return p.getBoolean("blocked_vpn", true); }
    public void blockedVpn(boolean v) { p.edit().putBoolean("blocked_vpn", v).apply(); }

    /** Apps that go around the VPN (package names). */
    public java.util.Set<String> excludedApps() {
        return new java.util.HashSet<>(p.getStringSet("excluded_apps", new java.util.HashSet<String>()));
    }
    public void excludedApps(java.util.Set<String> v) {
        p.edit().putStringSet("excluded_apps", new java.util.HashSet<>(v)).apply();
    }

    /** Name of the server chosen by hand, empty = automatic. */
    public String pinned() { return p.getString("pinned", ""); }
    public void pinned(String v) { p.edit().putString("pinned", v).apply(); }

    public String hwid() { return p.getString("hwid", ""); }
    public void hwid(String v) { p.edit().putString("hwid", v).apply(); }

    public boolean askedNotifications() { return p.getBoolean("asked_notif", false); }
    public void askedNotifications(boolean v) { p.edit().putBoolean("asked_notif", v).apply(); }
}
