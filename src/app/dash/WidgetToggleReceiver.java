package app.dash;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Widget power button. Not exported: only Dash's own PendingIntent can reach it. */
public class WidgetToggleReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (VpnWidget.ACTION_TOGGLE.equals(intent.getAction())) VpnWidget.toggle(c);
    }
}
