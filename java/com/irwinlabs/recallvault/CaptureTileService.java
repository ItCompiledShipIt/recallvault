package com.irwinlabs.recallvault;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick-capture tile (v0.35.0): a button in the notification shade.
 * One tap lands on MainActivity with a quick-capture extra; the
 * voice recorder opens as soon as the app is unlocked (the app
 * lock still gates it, exactly like opening the app itself).
 */
public class CaptureTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile t = getQsTile();
        if (t != null) {
            t.setLabel("Voice note");
            t.setState(Tile.STATE_ACTIVE);
            t.updateTile();
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra(MainActivity.EXTRA_QUICK_CAPTURE, "voice");
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (Build.VERSION.SDK_INT >= 34) {
            PendingIntent pi = PendingIntent.getActivity(this, 7101, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            startActivityAndCollapse(pi);
        } else {
            startActivityAndCollapse(i);
        }
    }
}
