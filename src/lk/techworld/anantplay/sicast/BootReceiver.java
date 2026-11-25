package lk.techworld.anantplay.sicast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Arms the receiver after boot (MIUI delivers BOOT_COMPLETED only with the app's
 *  Autostart permission on — the setup checklist's step 2), and after the app is
 *  updated/sideloaded over (MY_PACKAGE_REPLACED).
 *
 *  Android 14–16: BOOT_COMPLETED is on the foreground-service start exemption list for
 *  the connectedDevice type, so the start is legal — but vendor builds (HyperOS) still
 *  refuse it sometimes. ReceiverService.arm() catches
 *  ForegroundServiceStartNotAllowedException explicitly and logs why, so a refused boot
 *  start can never crash the app; the user's next app open or tile tap arms it. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (i == null) return;
        String a = i.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(a)
            || "android.intent.action.QUICKBOOT_POWERON".equals(a)
            || "android.intent.action.HTC.QUICKBOOT_POWERON".equals(a);
        boolean updated = Intent.ACTION_MY_PACKAGE_REPLACED.equals(a);
        if (!boot && !updated) return;
        // v3.4.6.e — a user's explicit Stop must SURVIVE a reboot's auto-arm
        // (the phone re-boots, the receiver stays stopped until THEY start it).
        // MY_PACKAGE_REPLACED always re-arms — an update is a fresh contract.
        if (boot && ReceiverService.userStopped()) {
            Diag.i("boot", "boot with a standing user stop — receiver stays off");
            return;
        }
        Diag.i("boot", (boot ? "boot completed" : "package replaced") + " — arming");
        ReceiverService.arm(c, boot ? "boot" : "update");
    }
}
