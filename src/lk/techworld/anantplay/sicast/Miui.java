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
 * case the user lands on the app's own settings page and navigates the last step.
 *
 * v3.1.0 (Bug 4, Smoking Gun #4) — THE 3-STATE AUTOSTART MODEL:
 * the 2026-11-22 16:36 field log showed the Home setup card permanently displaying
 * "⚠️ Setup · Autostart — MIUI ස්වයං ආරම්භය [Open]" even though the user had
 * ALREADY enabled Autostart in HyperOS settings — v3.0.0's checklist treated every
 * Xiaomi as "not granted" (`autostart = !isXiaomi()`, MainActivity.java:444), and
 * HyperOS (Android 15/16) additionally blocks third-party reads of OP_AUTO_START
 * (the hidden AppOps op 10008), so even an attempted read reports nothing.
 * The fix:
 *   · AutostartState { ENABLED, DISABLED, UNKNOWN } — a best-effort AppOps read
 *     (works on MIUI 12–14, returns UNKNOWN where HyperOS blocks it);
 *   · a clean user-confirmation pref (`autostart_user_confirmed`, in "sicast")
 *     with a 1-tap "✓ Done / මම සක්‍රිය කළා" button right next to [Open];
 *   · autostartOk() = state==ENABLED OR the user confirmed — the warning clears
 *     permanently once confirmed, and a DISABLED state re-shows the row so the
 *     user can re-enable after a HyperOS reset. */
final class Miui {

    /** v3.1.0 — the honest three-state autostart answer. */
    enum AutostartState { ENABLED, DISABLED, UNKNOWN }

    /** The hidden Xiaomi AppOps op for autostart (OP_AUTO_START) — no SDK constant. */
    static final int OP_AUTO_START = 10008;

    private Miui() { }

    static boolean isXiaomi() { return ReceiverService.isXiaomi(); }

    /** v3.1.0 — the best-effort AppOps read. MIUI 12–14 answer through it;
     *  HyperOS 15/16 block third-party access (the int-op API left the public
     *  SDK in android-36, so the read is reflection-based and every failure is
     *  UNKNOWN — never a crash, never a false DISABLED). */
    static AutostartState autostartState(Context c) {
        if (!isXiaomi()) return AutostartState.ENABLED;   // not a Xiaomi: no gate exists
        try {
            android.app.AppOpsManager ops = (android.app.AppOpsManager)
                c.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return AutostartState.UNKNOWN;
            android.content.pm.ApplicationInfo ai = c.getPackageManager().getApplicationInfo(c.getPackageName(), 0);
            // OP_AUTO_START (10008) has no SDK constant and no String form —
            // the int-typed checkOpNoThrow(int,int,String) is @hide-but-real.
            Object modeObj = android.app.AppOpsManager.class
                .getMethod("checkOpNoThrow", int.class, int.class, String.class)
                .invoke(ops, OP_AUTO_START, ai.uid, c.getPackageName());
            int mode = ((Number) modeObj).intValue();
            if (mode == android.app.AppOpsManager.MODE_ALLOWED) return AutostartState.ENABLED;
            if (mode == android.app.AppOpsManager.MODE_ERRORED
                || mode == android.app.AppOpsManager.MODE_IGNORED) return AutostartState.DISABLED;
            return AutostartState.UNKNOWN;                 // MODE_DEFAULT / vendor haze
        } catch (Throwable t) {
            return AutostartState.UNKNOWN;                 // the HyperOS 15/16 case
        }
    }

    /** The user tapped "✓ Done / මම සක්‍රිය කළා" — the warning clears permanently. */
    static void confirmAutostart(Context c, boolean confirmed) {
        try {
            c.getSharedPreferences("sicast", Context.MODE_PRIVATE).edit()
                .putBoolean("autostart_user_confirmed", confirmed).apply();
            Diag.i("miui", "autostart user-confirmed=" + confirmed);
        } catch (Throwable ignored) { }
    }

    static boolean autostartUserConfirmed(Context c) {
        try {
            return c.getSharedPreferences("sicast", Context.MODE_PRIVATE)
                .getBoolean("autostart_user_confirmed", false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** The one question the setup checklist asks: is autostart satisfied?
     *  ENABLED by read, or enabled + user-confirmed while the read is unreliable. */
    static boolean autostartOk(Context c) {
        if (!isXiaomi()) return true;
        AutostartState s = autostartState(c);
        if (s == AutostartState.ENABLED) return true;
        if (s == AutostartState.DISABLED) return autostartUserConfirmed(c);  // read says off; trust the user only if they confirmed after
        return autostartUserConfirmed(c);                                    // UNKNOWN (HyperOS): the user's word
    }

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
