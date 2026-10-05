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

    /* ---------- subscriptions (several links) ---------- */

    /** All subscription links / keys, in the order they were added. */
    public synchronized java.util.List<String> subUrls() {
        java.util.List<String> out = new java.util.ArrayList<>();
        String j = p.getString("subs", null);
        if (j == null) {
            // migrate from the single-subscription versions
            String old = p.getString("sub_url", "").trim();
            SharedPreferences.Editor e = p.edit();
            if (!old.isEmpty()) {
                out.add(old);
                String cache = p.getString("sub_cache", "");
                if (!cache.isEmpty()) e.putString("cache:" + old, cache);
            }
            e.putString("subs", new org.json.JSONArray(out).toString()).remove("sub_url").remove("sub_cache").apply();
            return out;
        }
        try {
            org.json.JSONArray a = new org.json.JSONArray(j);
            for (int i = 0; i < a.length(); i++) {
                String u = a.optString(i, "").trim();
                if (!u.isEmpty() && !out.contains(u)) out.add(u);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private synchronized void subUrls(java.util.List<String> v) {
        p.edit().putString("subs", new org.json.JSONArray(v).toString()).apply();
    }

    /** First subscription or "" — used to know whether anything is set up. */
    public String subUrl() {
        java.util.List<String> l = subUrls();
        return l.isEmpty() ? "" : l.get(0);
    }

    /** Adds a subscription; false when it is already there. */
    public synchronized boolean addSub(String url) {
        url = url.trim();
        java.util.List<String> l = subUrls();
        if (url.isEmpty() || l.contains(url)) return false;
        l.add(url);
        subUrls(l);
        return true;
    }

    public synchronized void removeSub(String url) {
        java.util.List<String> l = subUrls();
        l.remove(url);
        subUrls(l);
        p.edit().remove("cache:" + url).remove("name:" + url).apply();
    }

    public String subCache(String url) { return p.getString("cache:" + url, ""); }
    public void subCache(String url, String body) { p.edit().putString("cache:" + url, body).apply(); }

    /** Name the panel gave the subscription (profile-title), "" when unknown. */
    public String subName(String url) { return p.getString("name:" + url, ""); }
    public void subName(String url, String name) { p.edit().putString("name:" + url, name).apply(); }

    public long subUpdated() { return p.getLong("sub_updated", 0); }
    public void markSubUpdated() { p.edit().putLong("sub_updated", System.currentTimeMillis()).apply(); }
    public void resetSubUpdated() { p.edit().remove("sub_updated").apply(); }

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
