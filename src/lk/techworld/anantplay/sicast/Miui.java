package lk.techworld.anantplay.sicast;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/* The Xiaomi HyperOS / MIUI permission helpers. Vendor activities are undocumented and
 * move between builds — every jump is a best-effort list with the app-details page as
 * the guaranteed fallback, and everything is wrapped: nothing here can crash, worst
 * case the user lands on the app's own settings page and navigates the last step. */
final class Miui {

    private Miui() { }

    static boolean isXiaomi() { return ReceiverService.isXiaomi(); }

    /** Battery saver "No restrictions": readable on stock Android (and MIUI maps it). */
    static boolean batteryUnrestricted(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** The overlay grant: one system toggle, needed for the one-tap cast takeover
     *  (Android only lets a background app open its player with this). */
    static boolean overlayGranted(Context c) { return Settings.canDrawOverlays(c); }

    static void openOverlay(Context c) {
        try {
            c.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + c.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            openAppDetails(c);
        }
    }

    static void openBattery(Context c) {
        try {
            c.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            openAppDetails(c);
        }
    }

    /** The direct doalog: whitelist for battery optimization ("No restrictions"). */
    static void requestBattery(Context c) {
        try {
            c.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + c.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            openBattery(c);
        }
    }

    static boolean notifGranted(Context c) {
        if (Build.VERSION.SDK_INT < 33) return true;
        return c.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
            == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** MIUI Autostart screen: known component names across MIUI 12 → HyperOS, with fallbacks. */
    static void openAutostart(Context c) {
        String[][] comps = {
            {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
            {"com.miui.securitycenter", "com.miui.permcenter.bootapsible.BootDisposableControlActivity"},
            {"com.miui.securitycenter", "com.miui.securityscan.IndexActivity"},
        };
        for (String[] a : comps) {
            try {
                Intent i = new Intent().setComponent(new ComponentName(a[0], a[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                Diag.i("miui", "autostart via " + a[1]);
                return;
            } catch (Throwable ignored) { }
        }
        openAppDetails(c);   // HyperOS moved it? the app page has the Autostart switch too
    }

    /** MIUI "Other permissions" (the Display pop-up windows while running in the background screen). */
    static void openOtherPermissions(Context c) {
        String[][] comps = {
            {"com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"},
            {"com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity"},
        };
        for (String[] a : comps) {
            try {
                Intent i = new Intent().setComponent(new ComponentName(a[0], a[1]));
                i.putExtra("extra_pkgname", c.getPackageName());
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                Diag.i("miui", "other permissions via " + a[1]);
                return;
            } catch (Throwable ignored) { }
        }
        openAppDetails(c);
    }

    /** The Android 13+ sideload gate that greys toggles until the user allows it. */
    static void openAppDetails(Context c) {
        try {
            c.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + c.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            Diag.w("miui", "app details: " + t);
        }
    }

    /** Official AnantPlay's launcher (its Home activity is exported). */
    static void openAnantPlay(Context c) {
        try {
            Intent i = c.getPackageManager().getLaunchIntentForPackage("me.champdev.weblock");
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                Diag.i("miui", "AnantPlay launched");
                return;
            }
        } catch (Throwable t) {
            Diag.w("miui", "launch AnantPlay: " + t);
        }
        try {
            c.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:me.champdev.weblock")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) { }
    }
}
