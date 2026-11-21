package lk.techworld.anantplay.sicast;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile "අ Sinhala Cast": arms / disarms the receiver without opening
 *  the app. Zero accessibility, one tap — the low-friction path on HyperOS.
 *
 *  Audit fix: the OFF path now uses startForegroundService too. A TileService is not a
 *  foreground context, so plain startService() throws IllegalStateException on Android
 *  8+ (the tile would silently do nothing when the receiver had been killed). */
public final class CastTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        update();
    }

    @Override
    public void onClick() {
        if (ReceiverService.isUp()) {
            Diag.i("tile", "off by user");
            try {
                startForegroundService(new Intent(this, ReceiverService.class)
                    .setAction(ReceiverService.ACTION_STOP));
            } catch (Throwable t) {
                Diag.w("tile", "stop: " + t);
            }
        } else {
            Diag.i("tile", "arm by user");
            try {
                startForegroundService(new Intent(this, ReceiverService.class));
            } catch (Throwable t) {
                Diag.w("tile", "arm: " + t);
                // tile context sometimes can't start an FGS on Android 14+ — open the app instead
                try {
                    Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (Build.VERSION.SDK_INT >= 34) {
                        startActivityAndCollapse(PendingIntent.getActivity(this, 21, open,
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
                    } else {
                        startActivityAndCollapse(open);
                    }
                } catch (Throwable t2) {
                    Diag.w("tile", "open app: " + t2);
                }
            }
        }
        update();
    }

    private void update() {
        try {
            Tile t = getQsTile();
            if (t == null) return;
            boolean up = ReceiverService.isUp();
            t.setState(up ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            t.setSubtitle(getString(up ? R.string.tile_on : R.string.tile_off));
            t.updateTile();
        } catch (Throwable ignored) { }
    }
}
