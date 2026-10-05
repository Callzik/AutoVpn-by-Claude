package com.autovpn;

import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/** State shared between the VPN service and the screens (same process). */
public final class AppState {
    public static final int OFF = 0, CONNECTING = 1, ON = 2, WAITING = 3;
    public static final int WL_UNKNOWN = -1, WL_OFF = 0, WL_ON = 1, WL_NONET = 2;

    public static volatile int vpn = OFF;
    public static volatile String phase = "";
    public static volatile long since;
    public static volatile String net = "none";      // wifi | cell | other | none
    public static volatile int wl = WL_UNKNOWN;
    public static volatile boolean wlChecking;
    public static volatile String wlDetail = "";
    /** White lists look off, but regular VPN servers are blocked and only white-list servers work. */
    public static volatile boolean regularBlocked;
    public static volatile String group = "";
    public static volatile String serverName = "";
    public static volatile int ping = -1;
    public static volatile int alive;
    public static volatile int groupSize;
    public static volatile boolean switching;
    public static volatile String error = "";
    public static volatile String banner = "";
    public static volatile int bannerTone;           // 1 ok, 2 warn
    public static volatile long bannerAt;
    /** Name of the server chosen by hand, empty = automatic. */
    public static volatile String pinned = "";
    public static volatile List<Server> servers = new ArrayList<>();
    /** tag → last measured delay in ms, -1 = did not answer. */
    public static final java.util.concurrent.ConcurrentHashMap<String, Integer> pings = new java.util.concurrent.ConcurrentHashMap<>();
    public static volatile boolean pinging;
    public static volatile boolean pingingCurrent;
    public static volatile String lastServers = "";  // summary for settings

    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static final LinkedList<String> logLines = new LinkedList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Runnable notifier = new Runnable() {
        @Override public void run() {
            for (Runnable r : listeners) r.run();
        }
    };

    private AppState() {}

    public static void addListener(Runnable r) { listeners.add(r); }
    public static void removeListener(Runnable r) { listeners.remove(r); }

    public static void changed() {
        main.removeCallbacks(notifier);
        main.post(notifier);
    }

    public static void banner(String text, int tone) {
        banner = text;
        bannerTone = tone;
        bannerAt = System.currentTimeMillis();
        log(text);
        changed();
    }

    public static void log(String line) {
        try {
            android.util.Log.i("AutoVPN", line);
        } catch (Throwable ignored) {
        }
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        synchronized (logLines) {
            logLines.add(ts + "  " + line);
            while (logLines.size() > 400) logLines.removeFirst();
        }
    }

    public static List<String> logSnapshot() {
        synchronized (logLines) {
            return new ArrayList<>(logLines);
        }
    }
}
