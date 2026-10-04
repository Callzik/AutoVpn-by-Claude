package com.autovpn;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.service.quicksettings.TileService;

/** One place to switch the VPN from the screen, the tile and the widget, and to refresh the tile and widget. */
final class VpnControl {
    static final String EXTRA_CONNECT = "connect";

    private static volatile boolean hooked;
    private static Context app;

    private VpnControl() {}

    /** Called from every entry point; makes tile and widget follow state changes. */
    static void init(Context c) {
        if (hooked) return;
        synchronized (VpnControl.class) {
            if (hooked) return;
            app = c.getApplicationContext();
            hooked = true;
            AppState.addListener(new Runnable() {
                private String last = "";
                @Override public void run() {
                    String key = AppState.vpn + "|" + AppState.serverName + "|" + AppState.ping + "|" + AppState.phase;
                    if (key.equals(last)) return;
                    last = key;
                    refreshOutside(app);
                }
            });
        }
    }

    static void refreshOutside(Context c) {
        try {
            TileService.requestListeningState(c, new ComponentName(c, VpnTileService.class));
        } catch (Throwable ignored) {
        }
        try {
            VpnWidget.updateAll(c, AppWidgetManager.getInstance(c));
        } catch (Throwable ignored) {
        }
    }

    static boolean isActive() {
        return AppState.vpn != AppState.OFF;
    }

    /** Needs the screen first: no subscription yet or no VPN permission. */
    static boolean needsUi(Context c) {
        return new Prefs(c).subUrl().trim().isEmpty() || VpnService.prepare(c) != null;
    }

    static Intent openAppIntent(Context c, boolean connect) {
        Intent i = new Intent(c, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (connect) i.putExtra(EXTRA_CONNECT, true);
        return i;
    }

    static void start(Context c) {
        AppState.error = "";
        c.startForegroundService(new Intent(c, BoxVpnService.class).setAction(BoxVpnService.ACTION_START));
    }

    static void stop(Context c) {
        c.startService(new Intent(c, BoxVpnService.class).setAction(BoxVpnService.ACTION_STOP));
    }

    /** Text for the tile and the widget. */
    static String statusLine() {
        switch (AppState.vpn) {
            case AppState.ON:
                String s = AppState.serverName.isEmpty() ? "Подключено" : AppState.serverName;
                return AppState.ping > 0 ? s + " · " + AppState.ping + " мс" : s;
            case AppState.CONNECTING: return "Подключение…";
            case AppState.WAITING: return "Нет ответа, жду";
            default: return AppState.error.isEmpty() ? "Выключен" : "Ошибка";
        }
    }
}
