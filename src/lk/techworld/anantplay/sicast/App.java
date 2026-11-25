package lk.techworld.anantplay.sicast;

import android.app.Application;
import android.app.Notification;
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
    /** v3.1.0 (Bug 5) — the SILENT update channel: IMPORTANCE_LOW, no sound, no
     *  vibration, no badge. v3.0.0 posted update banners on CHANNEL_CAST
     *  (IMPORTANCE_HIGH + heads-up + badge) — the notification spam that visibly
     *  lagged the phone. Every update notification now goes here, is
     *  setOnlyAlertOnce(true) + PRIORITY_LOW (android-36 has no Builder.setSilent —
     *  the LOW channel importance is what makes them silent), and progress posts
     *  are throttled by UpdateNotify to at most 1 per second. */
    static final String CHANNEL_UPDATES_SILENT = "si-updates-silent";

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
        // v3.1.2 PRIVACY FIX (the 2026-11-22 field leak): v3.1.1 logged
        // Build.MANUFACTURER + Build.MODEL ("Xiaomi 24117RN76G") here AND in every
        // crash header. Hardware identifiers are NEVER collected, logged, displayed
        // or exported again — only the generic SDK integer travels (PrivacyScrubber
        // enforces this everywhere and the lab proves it on synthetic leaks).
        Diag.i("app", AppInfo.NAME + " v" + AppInfo.VERSION_NAME + " up, sdk " + android.os.Build.VERSION.SDK_INT);

        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            // v3.1.0 (Bug 2) — THE PERSISTENT CRASH RECORDER: the FULL redacted
            // report (timestamp, version, thread, every cause + frame) lands
            // atomically in filesDir/last_crash.txt BEFORE the system dialog, so
            // "keeps stopping" is always explainable afterwards from
            // Diagnostics → Last Crash Captured (1-tap Copy/Share).
            // v3.1.2: deviceInfo is the GENERIC "sdk N" line only — zero phone
            // model / manufacturer / fingerprint ever enters a crash report.
        // v3.1.0 (Bug 2) — THE PERSISTENT CRASH RECORDER ... (v3.1.2: also the
        // Troubleshooter CRASH timeline entry, privacy-scrubbed top-first)
            try {
                String report = CrashLog.format(System.currentTimeMillis(), AppInfo.VERSION_NAME,
                    AppInfo.VERSION_CODE,
                    "sdk " + android.os.Build.VERSION.SDK_INT,
                    t.getName(), e);
                CrashLog.writeAtomically(getFilesDir(), report);
                Diag.crash("thread " + t.getName(), e);   // ring + file, then the system dialog
                Troubleshooter.record("CRASH", "uncaught on thread " + t.getName()
                    + ": " + e.getClass().getName() + (e.getMessage() == null ? "" : " — " + e.getMessage()));
            } catch (Throwable ignored) { }
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

            // v3.1.0 (Bug 5) — the dedicated SILENT update channel: zero sound,
            // zero vibration, zero badge, LOW importance. A ~3–4 MB app update
            // must never make a sound, never heads-up, never spam.
            NotificationChannel silent = new NotificationChannel(CHANNEL_UPDATES_SILENT,
                "Silent updates · නිහඬ යාවත්කාලීන", NotificationManager.IMPORTANCE_LOW);
            silent.setDescription("Update progress + ready notices — always silent, throttled, never spam");
            silent.setSound(null, null);
            silent.enableVibration(false);
            silent.setShowBadge(false);
            nm.createNotificationChannel(silent);
        } catch (Throwable t) {
            Diag.w("app", "channel: " + t);
        }

        // v3.1.2 — the Troubleshooter timeline starts here: process start + arming
        Troubleshooter.record("APP", "process up v" + AppInfo.VERSION_NAME + " (sdk " + android.os.Build.VERSION.SDK_INT + ")");
        // v3.2.0 — THE STALE-CRASH SWEEP (Smoking Gun #3): last_crash.txt still
        // carried v3.1.1's ALREADY-FIXED #0x88000000 crash on the field phone,
        // so v3.1.2's "Auto-Detected Issues" kept lying ("❌ a crash was
        // captured"). Every report is now stamped versionCode; a report from
        // an OLDER build is deleted HERE — before the Troubleshooter, the
        // Diagnostics tab or the report builder ever reads it.
        try {
            CrashLog.clearStaleFor(getFilesDir(), AppInfo.VERSION_CODE, System.currentTimeMillis());
        } catch (Throwable t) {
            Diag.w("app", "stale crash sweep: " + t);
        }
        // The receiver arms itself from process start (boot, tile, launcher, or cast
        // retry) — foreground service rules differ per entry point, so this is best
        // effort with the main activity as the reliable path. (Single-process app:
        // no WebView sandbox processes to skip, unlike AnantPlay's own App.)
        // v3.4.6.e — a STANDING USER STOP survives the process restart: opening
        // the app again must not silently re-arm what the user stopped on Home
        // (the Home Start button — which clears the veto — is the one re-arm).
        try {
            // v3.4.6.f — GAP #3: hydrate the veto from receiver_state BEFORE
            // any auto-arm decision (the old in-memory flag reset on restart).
            ReceiverService.restoreUserStop(this);
            if (ReceiverService.userStopped()) {
                Diag.i("app", "standing user stop — receiver stays off until Start is tapped");
                Troubleshooter.record("APP", "receiver NOT auto-armed (standing user stop)");
            } else {
                ReceiverService.arm(this, "app-start");
                Troubleshooter.record("APP", "receiver arm requested (app-start)");
            }
        } catch (Throwable t) {
            Diag.w("app", "auto-arm: " + t);   // e.g. background FGS restrictions; MainActivity arms it
        }

        // The update cache is swept without deleting a verified APK waiting for
        // the user to return from install-permission settings. MainActivity starts
        // the immediate daemon-thread check after its in-app update UI is attached.
        // The 4-hour JobScheduler check remains the background/reboot fallback.
        // v3.4.6.f — LAW #1: sweepStalePending FIRST (pending metadata that is
        // not STRICTLY newer than the installed build — the completed-install
        // leftover that produced the v3.4.6.e → v3.4.6.e self-lock — is killed
        // from prefs AND disk before anything can re-arm a gate from it).
        try {
            SelfUpdater.sweepStalePending(this);
            SelfUpdater.sweepUpdates(this);
            SelfUpdater.schedulePeriodic(this);
        } catch (Throwable t) {
            Diag.w("app", "self-update schedule: " + t);
        }
    }

    // ── v3.1.0: the SILENT self-update notifications (channel: si-updates-silent) ──
    // Bug 5 rules: every builder is setOnlyAlertOnce(true) + PRIORITY_LOW,
    // the channel is IMPORTANCE_LOW with sound/vibration/badge disabled (that
    // combination IS the silent mode on API 26+), and progress posts are
    // throttled by UpdateNotify (at most 1 per second) BEFORE they reach this
    // method — a download can no longer flood SystemUI.

    /** The quiet download progress banner (called at most once per second). */
    static void notifyUpdateProgress(Context c, String version, int percent) {
        try {
            android.app.Notification n = new android.app.Notification.Builder(c, CHANNEL_UPDATES_SILENT)
                .setSmallIcon(R.drawable.ic_stat_si)
                .setContentTitle("AnantPlay Sinhala Player " + version)
                .setContentText("v" + version + " බාගැනෙමින්… " + (percent >= 0 ? percent + "%" : "…")
                    + " · downloading quietly")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(Notification.PRIORITY_LOW)
                .setProgress(100, Math.max(0, percent), percent < 0)
                .build();
            c.getSystemService(NotificationManager.class).notify(20401, n);
        } catch (Throwable t) {
            Diag.w("app", "update banner: " + t);
        }
    }

    /** Download verified, install prompt coming (the OS's own 1-tap dialog).
     *  Carries the quiet Skip-this-version action (v3.1.0, Bug 5). */
    static void notifyUpdateReady(Context c, String version) {
        try {
            android.app.Notification n = new android.app.Notification.Builder(c, CHANNEL_UPDATES_SILENT)
                .setSmallIcon(R.drawable.ic_stat_si)
                .setContentTitle("Update ready (v" + version + ") — Tap to install")
                .setContentText("v" + version + " සූදානම් — ස්ථාපනයට තට්ටු කරන්න · verified (SHA-256 ✓), tap to install")
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(Notification.PRIORITY_LOW)
                .setContentIntent(SelfUpdater.confirmPendingIntent(c))
                .addAction(new android.app.Notification.Action.Builder(null,
                    "Skip v" + version + " · මඟහරින්න", SelfUpdater.skipPendingIntent(c, version)).build())
                .build();
            c.getSystemService(NotificationManager.class).notify(20402, n);
        } catch (Throwable t) {
            Diag.w("app", "update ready banner: " + t);
        }
    }

    /** The SHA-256 mismatch / failure notice (still silent — a failure is never an alarm). */
    static void notifyUpdateFailed(Context c, String version) {
        try {
            android.app.Notification n = new android.app.Notification.Builder(c, CHANNEL_UPDATES_SILENT)
                .setSmallIcon(R.drawable.ic_stat_si)
                .setContentTitle("AnantPlay Sinhala Player " + version)
                .setContentText("බාගැනීම අසාර්ථක විය (SHA-256 නොගැලපේ) — පසුව නැවත උත්සාහ වේ · download failed verification, will retry later")
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(Notification.PRIORITY_LOW)
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
