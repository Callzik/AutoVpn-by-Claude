package com.autovpn;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** Home screen widget: power button + status. */
public class VpnWidget extends AppWidgetProvider {
    static final String ACTION_TOGGLE = "com.autovpn.WIDGET_TOGGLE";

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        VpnControl.init(c);
        updateAll(c, m);
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        super.onReceive(c, intent);
        if (!ACTION_TOGGLE.equals(intent.getAction())) return;
        VpnControl.init(c);
        if (VpnControl.isActive()) {
            VpnControl.stop(c);
        } else if (VpnControl.needsUi(c)) {
            c.startActivity(VpnControl.openAppIntent(c, true));
        } else {
            try {
                VpnControl.start(c);
            } catch (Exception e) {
                // background start refused by the system: let the app do it
                c.startActivity(VpnControl.openAppIntent(c, true));
            }
        }
        updateAll(c, AppWidgetManager.getInstance(c));
    }

    static void updateAll(Context c, AppWidgetManager m) {
        int[] ids = m.getAppWidgetIds(new ComponentName(c, VpnWidget.class));
        if (ids == null || ids.length == 0) return;
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);

        int state = AppState.vpn;
        String title, status;
        int btnBg, iconColor;
        switch (state) {
            case AppState.ON:
                title = "Подключено";
                status = VpnControl.statusLine();
                btnBg = R.drawable.widget_btn_on;
                iconColor = Ui.ACCENT;
                break;
            case AppState.CONNECTING:
                title = "Подключение…";
                status = AppState.phase.isEmpty() ? "Поиск сервера" : AppState.phase;
                btnBg = R.drawable.widget_btn_wait;
                iconColor = Ui.WARN;
                break;
            case AppState.WAITING:
                title = "Нет ответа";
                status = "Повторная попытка через несколько секунд";
                btnBg = R.drawable.widget_btn_wait;
                iconColor = Ui.WARN;
                break;
            default:
                title = "Отключено";
                status = AppState.error.isEmpty() ? "Нажмите, чтобы включить" : AppState.error;
                btnBg = R.drawable.widget_btn_off;
                iconColor = Ui.MUTED;
        }
        v.setTextViewText(R.id.widget_title, title);
        v.setTextViewText(R.id.widget_status, status);
        v.setInt(R.id.widget_btn, "setBackgroundResource", btnBg);
        v.setInt(R.id.widget_btn, "setColorFilter", iconColor);

        PendingIntent pi;
        if (state == AppState.OFF && VpnControl.needsUi(c)) {
            // no permission / no subscription yet: the button opens the app, which connects
            pi = PendingIntent.getActivity(c, 2, VpnControl.openAppIntent(c, true),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        } else {
            Intent toggle = new Intent(c, VpnWidget.class).setAction(ACTION_TOGGLE);
            pi = PendingIntent.getBroadcast(c, 0, toggle,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        }
        v.setOnClickPendingIntent(R.id.widget_btn, pi);
        PendingIntent open = PendingIntent.getActivity(c, 1, VpnControl.openAppIntent(c, false),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.widget_root, open);
        m.updateAppWidget(ids, v);
    }
}
