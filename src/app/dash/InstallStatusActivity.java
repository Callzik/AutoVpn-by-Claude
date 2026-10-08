package app.dash;

import android.app.Activity;
import android.os.Bundle;

/**
 * Receives the package installer's answer for a Dash update. Not exported: only the system,
 * through the PendingIntent Dash itself created, can start it, so the confirm intent inside
 * (EXTRA_INTENT) cannot be planted by another app.
 */
public class InstallStatusActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Updater.ACTION_STATUS.equals(getIntent().getAction())) Updater.onStatus(this, getIntent());
        finish();
    }
}
