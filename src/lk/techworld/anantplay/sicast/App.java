package lk.techworld.anantplay.sicast;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/* App onCreate: the diagnostics sink (every event, and any uncaught exception, lands
 * in filesDir/diag.log — nothing dies silently again), the receiver's notification
 * channel, and the receiver auto-arm (the cast server should be up from the moment
 * the phone boots or the app is first opened, like AnantPlay's own TV side). */
public class App extends Application {

    /** The persistent receiver notification (LOW: visible, silent — IMPORTANCE_MIN would
     *  let Android 13+ hide a foreground-service notification entirely). */
    static final String CHANNEL = "si-receiver";
    /** Cast arrivals that need a tap (no overlay permission, media permission, …). */
    static final String CHANNEL_CAST = "si-cast";

    private static volatile App inst;

    public static App get() { return inst; }
    public static Context ctx() { return inst; }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        // the ring works even before the sink; the sink survives crashes after a flush
        final File log = new File(getFilesDir(), "diag.log");
        Diag.install(line -> {
            try (Writer w = new OutputStreamWriter(new FileOutputStream(log, true), StandardCharsets.UTF_8)) {
                w.write(line);
                w.write('\n');
            } catch (Throwable ignored) { }
            if (log.length() > 400_000) {   // keep the tail: ~400 KB max
                File cut = new File(getFilesDir(), "diag.log.tmp");
                try {
                    java.util.List<String> tail = new java.util.ArrayList<>();
                    java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(log), StandardCharsets.UTF_8));
                    for (String l; (l = r.readLine()) != null; ) { tail.add(l); if (tail.size() > 2500) tail.remove(0); }
                    r.close();
                    try (Writer w = new OutputStreamWriter(new FileOutputStream(cut), StandardCharsets.UTF_8)) {
                        for (String l : tail) { w.write(l); w.write('\n'); }
                    }
                    cut.renameTo(log);
                } catch (Throwable ignored) { }
            }
        });
        Diag.i("app", AppInfo.NAME + " v" + AppInfo.VERSION_NAME + " up, sdk " + android.os.Build.VERSION.SDK_INT
            + " " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);

        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            Diag.crash("thread " + t.getName(), e);   // ring + file, then the system dialog
            if (prev != null) prev.uncaughtException(t, e);
        });

        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(getString(R.string.channel_desc));
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);

            NotificationChannel cast = new NotificationChannel(CHANNEL_CAST, getString(R.string.channel_cast_name),
                NotificationManager.IMPORTANCE_HIGH);
            cast.setDescription(getString(R.string.channel_cast_desc));
            cast.setShowBadge(true);
            nm.createNotificationChannel(cast);
        } catch (Throwable t) {
            Diag.w("app", "channel: " + t);
        }

        // The receiver arms itself from process start (boot, tile, launcher, or cast
        // retry) — foreground service rules differ per entry point, so this is best
        // effort with the main activity as the reliable path. (Single-process app:
        // no WebView sandbox processes to skip, unlike AnantPlay's own App.)
        try {
            ReceiverService.arm(this, "app-start");
        } catch (Throwable t) {
            Diag.w("app", "auto-arm: " + t);   // e.g. background FGS restrictions; MainActivity arms it
        }

        // v3.0.0 — the built-in GitHub self-update: an app-open check (60 s
        // debounce, fires the MOMENT the app opens) + the standing 4 h periodic
        // schedule, plus the storage sweep: no leftover .apk from a previous
        // update run ever survives an app start.
        try {
            SelfUpdater.sweepUpdates(this);
            SelfUpdater.checkOnLaunch(this);
            SelfUpdater.schedulePeriodic(this);
        } catch (Throwable t) {
            Diag.w("app", "self-update schedule: " + t);
        }
    }

    // ── v3.0.0: the self-update notifications (channel: the existing cast channel) ──

    /** The live download banner with the percentage (updated on every chunk). */
    static void notifyUpdateProgress(Context c, String version, int percent) {
        try {
            android.app.Notification n = new android.app.Notification.Builder(c, CHANNEL_CAST)
                .setSmallIcon(R.drawable.ic_stat_si)
                .setContentTitle("AnantPlay Sinhala Player " + version)
                .setContentText("අලුත් සංස්කරණය v" + version + " ඩවුන්ලෝඩ් වෙමින්… "
                    + (percent >= 0 ? percent + "%" : "…"))
                .setOngoing(true)
                .setProgress(100, Math.max(0, percent), percent < 0)
                .build();
            c.getSystemService(NotificationManager.class).notify(20401, n);
        } catch (Throwable t) {
            Diag.w("app", "update banner: " + t);
        }
    }

    /** Download verified, install prompt coming (the OS's own 1-tap dialog). */
    static void notifyUpdateReady(Context c, String version) {
        try {
            android.app.Notification n = new android.app.Notification.Builder(c, CHANNEL_CAST)
                .setSmallIcon(R.drawable.ic_stat_si)
                .setContentTitle("AnantPlay Sinhala Player " + version)
                .setContentText("යාවත්කාලීනය සූදානම් — ස්ථාපනය කිරීමට තට්ටු කරන්න · ready to install (1 tap)")
                .setAutoCancel(true)
                .build();
            c.getSystemService(NotificationManager.class).notify(20402, n);
        } catch (Throwable t) {
            Diag.w("app", "update ready banner: " + t);
        }
    }

    /** The SHA-256 mismatch / failure notice. */
    static void notifyUpdateFailed(Context c, String version) {
        try {
            android.app.Notification n = new android.app.Notification.Builder(c, CHANNEL_CAST)
                .setSmallIcon(R.drawable.ic_stat_si)
                .setContentTitle("AnantPlay Sinhala Player " + version)
                .setContentText("යාවත්කාලීන බාගැනීම අසාර්ථක විය (SHA-256 නොගැලපේ) — පසුව නැවත උත්සාහ කරනු ලැබේ · download failed verification, will retry later")
                .setAutoCancel(true)
                .build();
            c.getSystemService(NotificationManager.class).notify(20403, n);
        } catch (Throwable t) {
            Diag.w("app", "update failed banner: " + t);
        }
    }

    static void notifyUpdateDone(Context c) {
        try {
            c.getSystemService(NotificationManager.class).cancel(20401);
            c.getSystemService(NotificationManager.class).cancel(20402);
        } catch (Throwable ignored) { }
    }
}
