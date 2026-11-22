package lk.techworld.anantplay.sicast;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/* v3.0.0 — THE SEAMLESS APP-OPEN AUTO-UPDATER (zero external dependencies).
 *
 * THE HONEST ANDROID 16 SECURITY MODEL (non-root, unchanged from v2.4.0):
 *   Silent zero-tap APK installation is BLOCKED by the OS — PackageInstaller
 *   always shows the 1-tap system "Update" confirmation. That is by design and
 *   no app can (or should) bypass it. Everything UP TO that single tap is fully
 *   automatic, mirroring official AnantPlay's own update flow:
 *
 *     1. CHECK — automatically the MOMENT the app opens (a short 60-second
 *        debounce so quick re-opens never spam GitHub) and every 4 HOURS via a
 *        persisted JobScheduler job (BIND_JOB_SERVICE + RECEIVE_BOOT_COMPLETED:
 *        the schedule survives reboots). NOT hidden behind a manual button.
 *     2. DOWNLOAD — starts BY ITSELF when a newer release exists and
 *        Auto-Update is ON (the default): a live visible in-app progress bar
 *        (MainActivity's Home banner) AND a notification with the percentage
 *        ("අලුත් සංස්කරණය vX.Y.Z ඩවුන්ලෝඩ් වෙමින්… 64%").
 *     3. VERIFY — https-only URL, a hard 30 MB size cap (checked before AND
 *        during the stream), and a STRICT SHA-256 match against the release's
 *        SHA256SUMS.txt line (SelfUpdateCore.pickShaLine — the exact parser of
 *        the CI's `sha256sum` output). A tampered/corrupt APK is REFUSED and
 *        its file deleted.
 *     4. INSTALL — canRequestPackageInstalls() is checked first: without the
 *        "Install unknown apps" grant the user gets a 1-tap dialog straight to
 *        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES. With it, the verified file
 *        is streamed into a PackageInstaller.Session committed with an EXPLICIT
 *        MUTABLE PendingIntent (Android 14–16 compliant: explicit component,
 *        FLAG_MUTABLE so the system can fill in the status extras). When the
 *        confirm intent cannot be launched from the background, it is re-posted
 *        as a high-priority BAL-safe notification instead (tap → the same
 *        1-tap system dialog).
 *     5. CLEANUP — the temporary .apk under cacheDir/updates/ is deleted the
 *        MOMENT it has been written into the session (handoff complete), and
 *        any leftovers are swept on EVERY app startup — storage stays clean.
 *
 * Obtainium compatibility is unchanged: same permanent signing key, so both
 * paths install over each other in place forever. */
public final class SelfUpdater {

    static final String REPO = "dictationb1/anantplay-sinhala-player";
    static final String API_LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    /** v3.0.0 — the app-open debounce: a check runs at most once per 60 s. */
    static final long MIN_CHECK_INTERVAL_MS = 60 * 1000L;
    /** v3.0.0 — the standing periodic check: every 4 hours (was 6 h). */
    static final long PERIODIC_MS = 4 * 60 * 60 * 1000L;
    static final int JOB_ID = 20400;

    /** The live in-app progress listener (MainActivity's Home banner sets it). */
    public interface UiListener {
        void onUpdateAvailable(String version);
        void onDownloadProgress(int percent);
        void onUpdateState(String bilingual);
    }
    private static volatile UiListener ui;
    public static void setUiListener(UiListener l) { ui = l; }
    static void fire(String state) {
        UiListener u = ui;
        if (u != null) { try { u.onUpdateState(state); } catch (Throwable ignored) { } }
    }

    private SelfUpdater() { }

    // ── storage: the one temp dir + the startup sweep ─────────────────────────

    /** cacheDir/updates — the ONLY place a download ever lands. */
    static File updatesDir(Context c) {
        File d = new File(c.getCacheDir(), "updates");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** v3.0.0 — delete EVERY leftover .apk in cacheDir/updates (App.onCreate +
     *  after every session handoff): storage space is always 100% cleared. The
     *  primitive (SelfUpdateCore.deleteAll) is lab-proven. */
    static long sweepUpdates(Context c) {
        long freed;
        try {
            freed = SelfUpdateCore.deleteAll(updatesDir(c));
        } catch (Throwable t) {
            Diag.w("selfupd", "sweep: " + t);
            return 0;
        }
        if (freed > 0) Diag.i("selfupd", "startup sweep freed " + (freed / 1024) + " KiB of leftover update files");
        return freed;
    }

    // ── the checks ────────────────────────────────────────────────────────────

    /** Called from App.onCreate: fire the background check at most once per
     *  60 s (the app-open debounce). Non-blocking, never throws. */
    public static void checkOnLaunch(final Context c) {
        try {
            long last = c.getSharedPreferences("selfupdate", Context.MODE_PRIVATE).getLong("lastCheck", 0);
            if (System.currentTimeMillis() - last < MIN_CHECK_INTERVAL_MS) return;
            schedule(c, 0L);
        } catch (Throwable t) {
            Diag.w("selfupd", "launch check: " + t);
        }
    }

    /** One-shot ASAP job (network permitting). */
    public static void schedule(Context c, long delayMs) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js == null) return;
            JobInfo.Builder b = new JobInfo.Builder(JOB_ID,
                new ComponentName(c, CheckJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(delayMs)
                .setOverrideDeadline(delayMs + 30 * 60 * 1000L);
            js.schedule(b.build());
            c.getSharedPreferences("selfupdate", Context.MODE_PRIVATE).edit()
                .putLong("lastCheck", System.currentTimeMillis()).apply();
        } catch (Throwable t) {
            Diag.w("selfupd", "schedule: " + t);
        }
    }

    /** The standing 4-hour periodic schedule (persisted across reboots). */
    public static void schedulePeriodic(Context c) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js == null) return;
            JobInfo info = new JobInfo.Builder(JOB_ID + 1, new ComponentName(c, CheckJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIODIC_MS)
                .setPersisted(true)
                .build();
            js.schedule(info);
        } catch (Throwable t) {
            Diag.w("selfupd", "periodic schedule: " + t);
        }
    }

    // ── the flow: check + download (progress) + verify + install + cleanup ────

    /** One update attempt. Returns a human-readable result (also logged). */
    public static String checkAndMaybeInstall(final Context c) {
        try {
            SelfUpdateCore.Release rel = SelfUpdateCore.fetchRelease(API_LATEST);
            if (rel == null) return "no release";
            if (!UpdateChecker.isNewer(rel.version, AppInfo.VERSION_NAME)) {
                Diag.i("selfupd", "up to date (" + AppInfo.VERSION_NAME + "; latest " + rel.version + ")");
                return "up-to-date";
            }
            // Auto-Update is ON by default (Settings → Updates → the toggle)
            if (!c.getSharedPreferences("sicast", Context.MODE_PRIVATE).getBoolean("autoUpdate", true)) {
                Diag.i("selfupd", "new release " + rel.version + " but Auto-Update is OFF — notifying only");
                App.notifyUpdateReady(c, rel.version);
                fire("අලුත් සංස්කරණයක් ඇත · v" + rel.version + " available — Settings → Updates → Check now");
                return "available " + rel.version;
            }
            // the pre-download gates: https-only, declared size within the cap
            if (!SelfUpdateCore.httpsOnly(rel.apkUrl)) {
                Diag.e("selfupd", "release apk URL is not https — refusing");
                return "not-https";
            }
            if (rel.apkSize > SelfUpdateCore.MAX_APK_BYTES) {
                Diag.e("selfupd", "release apk is " + rel.apkSize + " B — over the 30 MB cap, refusing");
                return "too-large";
            }
            if (rel.sha256Expected == null) {
                // a release without a SHA256SUMS line for the apk: refuse (security)
                Diag.w("selfupd", "no SHA-256 line for " + SelfUpdateCore.apkFileName(rel.apkUrl) + " — refusing");
                App.notifyUpdateFailed(c, rel.version);
                return "no-sha";
            }
            Diag.i("selfupd", "new release " + rel.version + " > " + AppInfo.VERSION_NAME + " — downloading");
            UiListener u = ui;
            if (u != null) { try { u.onUpdateAvailable(rel.version); } catch (Throwable ignored) { } }

            // ── download → cacheDir/updates/<name>.apk with live progress ──
            sweepUpdates(c);   // never two temp files at once
            final File apk = new File(updatesDir(c), SelfUpdateCore.apkFileName(rel.apkUrl));
            App.notifyUpdateProgress(c, rel.version, 0);
            SelfUpdateCore.downloadToFile(rel.apkUrl, apk, (bytes, total, percent) -> {
                App.notifyUpdateProgress(c, rel.version, percent);
                UiListener ul = ui;
                if (ul != null) { try { ul.onDownloadProgress(percent); } catch (Throwable ignored) { } }
            });

            // ── verify the strict SHA-256 against SHA256SUMS.txt ──
            String sha = SelfUpdateCore.sha256(apk);
            if (!rel.sha256Expected.equalsIgnoreCase(sha)) {
                Diag.e("selfupd", "SHA-256 MISMATCH — refusing to install (downloaded " + sha
                    + ", expected " + rel.sha256Expected + ")");
                apk.delete();
                App.notifyUpdateFailed(c, rel.version);
                fire("බාගැනීම අසාර්ථක (SHA-256 නොගැලපේ) · download failed verification");
                return "sha-mismatch";
            }
            Diag.i("selfupd", "sha256 verified (" + sha + ") — handing off to PackageInstaller");

            // ── the Install-Unknown-Apps gate: a 1-tap jump when missing ──
            if (!c.getPackageManager().canRequestPackageInstalls()) {
                Diag.w("selfupd", "canRequestPackageInstalls=false — posting the 1-tap permission prompt");
                notifyInstallPermission(c, rel.version);
                fire("ස්ථාපන අවසරය අවශ්‍යයි — දැනුම්දීම ඔබන්න · install permission needed — tap the notification");
                // the file stays until the next sweep; the user can grant + re-check
                return "needs-install-permission";
            }

            // ── install: stream the file into the session, then DELETE it ──
            App.notifyUpdateReady(c, rel.version);
            install(c, apk);
            apk.delete();              // the handoff is complete — storage cleared NOW
            sweepUpdates(c);           // and nothing else lingers either
            fire("v" + rel.version + " සූදානම් — 1-tap ස්ථාපනය · ready to install (1 tap)");
            return "installing " + rel.version;
        } catch (Throwable t) {
            Diag.w("selfupd", "check: " + t);
            fire("යාවත්කාලීන පරීක්ෂාව අසාර්ථක — පසුව නැවත · update check failed — will retry");
            return "offline";
        }
    }

    // ── the install (Android side) ─────────────────────────────────────────────

    /** Stream the verified APK FILE into a PackageInstaller.Session → the
     *  system's 1-tap "Update" prompt. Android 14–16 compliant: an EXPLICIT
     *  mutable PendingIntent (explicit component + FLAG_MUTABLE — the system
     *  fills in the status extras). Same-signature updates install in place:
     *  no uninstall, all data preserved. */
    static void install(Context c, File apk) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.Session session = null;
        int sid = -1;
        try {
            sid = pi.createSession(new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL));
            session = pi.openSession(sid);
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("update", 0, apk.length())) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                out.flush();
                session.fsync(out);
            }
            // EXPLICIT intent (component named) + FLAG_MUTABLE: the Android 12+
            // PendingIntent contract, and the only mutable shape PackageInstaller
            // accepts for its status broadcast.
            Intent ok = new Intent(c, InstallResultReceiver.class);
            PendingIntent piOk = PendingIntent.getBroadcast(c, sid, ok,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(piOk.getIntentSender());
            Diag.i("selfupd", "install session " + sid + " committed (" + apk.length() + " B) — waiting for the 1-tap prompt");
        } catch (Throwable t) {
            if (session != null) try { session.abandon(); } catch (Throwable ignored) { }
            throw t;
        }
    }

    /** Receives the install result broadcast (STATUS_PENDING_USER_ACTION → the
     *  system dialog was shown; we forward the user's confirm intent, with a
     *  BAL-SAFE high-priority notification as the fallback when the background
     *  activity launch is refused). */
    public static final class InstallResultReceiver extends android.content.BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            try {
                int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            c.startActivity(confirm);   // the 1-tap "Update" dialog
                            Diag.i("selfupd", "user-action prompt shown");
                        } catch (Throwable bal) {
                            // Android 14+ BAL refusal → the SAME dialog, one tap away
                            Diag.w("selfupd", "BAL refused the prompt (" + bal + ") — posting the tap-to-install notification");
                            postConfirmNotification(c, confirm);
                        }
                    }
                } else if (status == PackageInstaller.STATUS_SUCCESS) {
                    Diag.i("selfupd", "installed ✓");
                    App.notifyUpdateDone(c);
                } else {
                    Diag.w("selfupd", "install status " + status + ": "
                        + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
                }
            } catch (Throwable t) {
                Diag.w("selfupd", "install result: " + t);
            }
        }
    }

    /** The BAL-safe fallback: the confirm intent as a high-priority notification. */
    static void postConfirmNotification(Context c, Intent confirm) {
        try {
            PendingIntent open = PendingIntent.getActivity(c, 20405, confirm,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(c, App.CHANNEL_CAST)
                .setSmallIcon(R.drawable.ic_stat_si)
                .setContentTitle("AnantPlay Sinhala Player — update")
                .setContentText("ස්ථාපනය කිරීමට තට්ටු කරන්න · tap to install (1 tap)")
                .setPriority(Notification.PRIORITY_HIGH)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
            c.getSystemService(android.app.NotificationManager.class).notify(20405, n);
        } catch (Throwable t) {
            Diag.w("selfupd", "confirm notification: " + t);
        }
    }

    /** The 1-tap "Install unknown apps" prompt: a notification carrying the
     *  exact settings deep-link (Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES). */
    static void notifyInstallPermission(Context c, String version) {
        try {
            Intent open = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + c.getPackageName()));
            PendingIntent pi = PendingIntent.getActivity(c, 20406, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(c, App.CHANNEL_CAST)
                .setSmallIcon(R.drawable.ic_stat_si)
                .setContentTitle("v" + version + " — install permission needed")
                .setContentText("1-tap: \"Unknown sources\" මෙම ඇප් එකට ON කරන්න · allow installs from this app")
                .setPriority(Notification.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
            c.getSystemService(android.app.NotificationManager.class).notify(20406, n);
        } catch (Throwable t) {
            Diag.w("selfupd", "install-permission notification: " + t);
        }
    }

    /** The JobScheduler entry: check + (when needed) download/verify/install. */
    public static final class CheckJob extends JobService {
        @Override public boolean onStartJob(final JobParameters params) {
            new Thread(() -> {
                try { checkAndMaybeInstall(getApplicationContext()); } catch (Throwable ignored) { }
                jobFinished(params, false);
                schedulePeriodic(getApplicationContext());   // keep the 4 h schedule alive
            }, "si-selfupd").start();
            return true;
        }
        @Override public boolean onStopJob(JobParameters params) { return true; }   // retry later
    }
}
