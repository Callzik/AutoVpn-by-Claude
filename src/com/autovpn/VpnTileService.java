package com.autovpn;

import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: tap to switch the VPN on and off. */
public class VpnTileService extends TileService {
    private final Runnable listener = new Runnable() {
        @Override public void run() { render(); }
    };

    @Override public void onStartListening() {
        VpnControl.init(this);
        AppState.addListener(listener);
        render();
    }

    @Override public void onStopListening() {
        AppState.removeListener(listener);
    }

    @Override public void onClick() {
        VpnControl.init(this);
        if (VpnControl.isActive()) {
            VpnControl.stop(this);
        } else if (VpnControl.needsUi(this)) {
            startActivityAndCollapse(VpnControl.openAppIntent(this, true));
        } else {
            try {
                VpnControl.start(this);
            } catch (Exception e) {
                startActivityAndCollapse(VpnControl.openAppIntent(this, true));
            }
        }
        render();
    }

    private void render() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean active = VpnControl.isActive();
        t.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setIcon(Icon.createWithResource(this, R.drawable.ic_stat));
        t.setLabel(getString(R.string.app_name));
        if (Build.VERSION.SDK_INT >= 29) t.setSubtitle(VpnControl.statusLine());
        t.setContentDescription(getString(R.string.app_name) + ": " + VpnControl.statusLine());
        t.updateTile();
    }
}
