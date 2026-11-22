package lk.techworld.anantplay.sicast;

import android.app.ActivityManager;
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
import java.io.OutputStream;
import java.util.List;

/* v2.4.0 — THE BUILT-IN GITHUB SELF-UPDATER (zero external dependencies).
 *
 * THE HONEST ANDROID 16 SECURITY MODEL (non-root):
 *   Silent zero-tap APK installation is BLOCKED by the OS — PackageInstaller
 *   always shows the 1-tap system "Update" confirmation. That is by design and
 *   no app can (or should) bypass it. What we CAN do — mirroring official
 *   AnantPlay's own update flow — is everything up to that single tap:
 *     1. CHECK:   GET https://api.github.com/repos/dictationb1/anantplay-sinhala-player/releases/latest
 *                 automatically on every app launch (rate-limited to 15 min) and
 *                 every 6 h via JobScheduler (persisted, survives reboots).
 *     2. COMPARE: release versionName vs AppInfo.VERSION_NAME (UpdateChecker.isNewer).
 *     3. DOWNLOAD the single .apk asset from the SAME release, streaming over
 *                 HTTPS ONLY with a hard 30 MB cap (SelfUpdateInstall).
 *     4. VERIFY:  SHA-256 of the download against SHA256SUMS.txt from the same
 *                 release — a corrupted or tampered APK is REFUSED and deleted.
 *     5. INSTALL: android.content.PackageInstaller.Session → the system's 1-tap
 *                 "Update" prompt → in-place update, SAME permanent keystore as
 *                 the GitHub Release build → zero uninstall, data preserved.
 *   Obtainium compatibility is unaffected: Obtainium reads the same GitHub
 *   Releases independently; both paths install over each other (same key).
 *
 * v2.4.0 ANDROID 14–16 (targetSdk 34) HARDENING — the five PackageInstaller traps,
 * every one of them decided by the pure-Java, lab-tested SelfUpdateInstall:
 *   T1 explicit MUTABLE PendingIntent   — `new Intent(c, InstallResultReceiver.class)
 *      .setPackage(getPackageName())`; an implicit mutable PendingIntent is an
 *      IllegalArgumentException on targetSdk 34 (and a spoofing hole at any level).
 *   T2 BAL (background activity launch)  — STATUS_PENDING_USER_ACTION outside the
 *      foreground (the 6 h CheckJob path) is routed to a HIGH-priority
 *      tap-to-install notification; startActivity() is still attempted when we ARE
 *      foreground, and a SecurityException/thrown BAL falls back to the same
 *      notification. The update can never be lost silently.
 *   T3 canRequestPackageInstalls()      — checked BEFORE the session is committed;
 *      when false the user gets a bilingual prompt that opens
 *      Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES for `package:<pkg>`.
 *   T4 manifest/JobService              — REQUEST_INSTALL_PACKAGES + RECEIVE_BOOT_COMPLETED
 *      (needed by setPersisted(true)), CheckJob with BIND_JOB_SERVICE + exported=false,
 *      install receiver exported=false.
 *   T5 download safety                  — HTTPS-only, ≤ 30 MB, mandatory SHA-256 from
 *      SHA256SUMS.txt (missing/tampered hash → refuse + delete), 100 % off the main
 *      thread (checkOnLaunch only schedules; CheckJob and the manual button each run
 *      on their own worker thread).
 *
 * The pure-Java core (release parsing, hash verification) is SelfUpdateCore — the
 * lab drives it on a bare JVM against a local mock GitHub server. */
public final class SelfUpdater {

    static final String REPO = "dictationb1/anantplay-sinhala-player";
    static final String API_LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    /** Launch check rate limit (ms). */
    static final long MIN_CHECK_INTERVAL_MS = 15 * 60 * 1000L;
    static final int JOB_ID = 20400;
    static final String PREFS = "selfupdate";
    static final String KEY_LAST = "lastCheck";
    /** v2.4.0 T3: set once the user has been sent to the unknown-sources screen, so
     *  the bilingual prompt is shown once and not on every 6 h retry. */
    static final String KEY_ASKED_UNKNOWN = "askedUnknownSources";

    private SelfUpdater() { }

    // ── the launch check (rate-limited) ────────────────────────────────────────

    /** Called from App.onCreate: fire the background check at most once per
     *  15 min. Non-blocking, never throws, never touches the network on the caller's
     *  thread (T5) — it only schedules the JobService. */
    public static void checkOnLaunch(final Context c) {
        try {
            long last = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST, 0);
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
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST, System.currentTimeMillis()).apply();
        } catch (Throwable t) {
            Diag.w("selfupd", "schedule: " + t);
        }
    }

    /** The standing 6-hour periodic schedule (persisted → needs RECEIVE_BOOT_COMPLETED). */
    public static void schedulePeriodic(Context c) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js == null) return;
            JobInfo info = new JobInfo.Builder(JOB_ID + 1, new ComponentName(c, CheckJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(6 * 60 * 60 * 1000L)
                .setPersisted(true)
                .build();
            js.schedule(info);
        } catch (Throwable t) {
            Diag.w("selfupd", "periodic schedule: " + t);
        }
    }

    // ── the flow: check + download + verify + install ──────────────────────────

    /** One update attempt. Returns a human-readable result (also logged).
     *  ALWAYS called from a worker thread (CheckJob's own thread, or the manual
     *  "check for updates" button) — never from the main thread (T5). */
    public static String checkAndMaybeInstall(Context c) {
        try {
            SelfUpdateCore.Release rel = SelfUpdateCore.fetchRelease(API_LATEST);
            if (rel == null) return "no release";
            if (!UpdateChecker.isNewer(rel.version, AppInfo.VERSION_NAME)) {
                Diag.i("selfupd", "up to date (" + AppInfo.VERSION_NAME + "; latest " + rel.version + ")");
                return "up-to-date";
            }
            // T5: the asset URL itself must be HTTPS before a single byte is read.
            if (!SelfUpdateInstall.isHttpsUrl(rel.apkUrl)) {
                Diag.e("selfupd", "refusing a non-HTTPS apk url: " + rel.apkUrl);
                App.notifyUpdateFailed(c, rel.version);
                return "not-https";
            }
            Diag.i("selfupd", "new release " + rel.version + " > " + AppInfo.VERSION_NAME + " — downloading");
            App.notifyUpdate(c, rel.version);          // the banner: "ඩවුන්ලෝඩ් වෙමින්…"
            byte[] apk = SelfUpdateCore.download(rel.apkUrl);
            if (!SelfUpdateInstall.sizeStillAllowed(apk.length)) {
                Diag.e("selfupd", "downloaded APK is over the " + SelfUpdateInstall.MAX_APK_BYTES
                    + " byte cap (" + apk.length + ") — refusing");
                App.notifyUpdateFailed(c, rel.version);
                return "too-big";
            }
            String sha = SelfUpdateCore.sha256(apk);
            if (!SelfUpdateInstall.hasVerifiableHash(rel.sha256Expected)) {
                // a release without a usable SHA256SUMS line: refuse (security, T5)
                Diag.w("selfupd", "no verifiable SHA-256 line for " + SelfUpdateCore.apkFileName(rel.apkUrl)
                    + " — refusing to install");
                App.notifyUpdateFailed(c, rel.version);
                return "no-sha";
            }
            if (!rel.sha256Expected.equalsIgnoreCase(sha)) {
                Diag.e("selfupd", "SHA-256 MISMATCH — refusing to install (downloaded " + sha
                    + ", expected " + rel.sha256Expected + ")");
                App.notifyUpdateFailed(c, rel.version);
                return "sha-mismatch";
            }
            App.notifyUpdateReady(c, rel.version);     // verified — install prompt next
            return install(c, apk, rel.version);
        } catch (Throwable t) {
            Diag.w("selfupd", "check: " + t);
            return "offline";
        }
    }

    // ── the install (Android side) ─────────────────────────────────────────────

    /** Stream the verified APK into a PackageInstaller.Session → the system's
     *  1-tap "Update" prompt appears immediately. Same-signature updates install
     *  in place: no uninstall, all data (subs cache, prefs) preserved.
     *
     *  TRAP 3 runs FIRST: committing a session without the "install unknown apps"
     *  grant fails silently on API 26+, so we send the user to the exact settings
     *  page (for our package) instead of failing in the dark. */
    static String install(Context c, byte[] apk, String version) throws Exception {
        int sdk = Build.VERSION.SDK_INT;
        boolean canInstall;
        try {
            canInstall = c.getPackageManager().canRequestPackageInstalls();
        } catch (Throwable t) {
            canInstall = true;   // below API 26 the method does not exist
        }
        if (SelfUpdateInstall.needsUnknownSources(sdk, canInstall)) {
            Diag.w("selfupd", "canRequestPackageInstalls() == false — cannot open an install session");
            askForUnknownSources(c);
            App.notifyUnknownSourcesNeeded(c, version);
            return "needs-unknown-sources";
        }

        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.Session session = null;
        int sid = -1;
        try {
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            // keep the whole payload inside the installer's own size budget
            try { sp.setSize(apk.length); } catch (Throwable ignored) { }
            sid = pi.createSession(sp);
            session = pi.openSession(sid);
            OutputStream out = session.openWrite("update", 0, apk.length);
            out.write(apk);
            out.flush();
            session.fsync(out);
            out.close();

            // TRAP 1 — EXPLICIT mutable PendingIntent. `new Intent(c, class)` already
            // names the component; setPackage() makes the explicitness explicit for
            // any future refactor, and assert() documents the invariant in the log.
            Intent ok = new Intent(c, InstallResultReceiver.class);
            ok.setPackage(c.getPackageName());
            assert SelfUpdateInstall.isCommitPendingIntentSafe(
                SelfUpdateInstall.isExplicitInstallIntent(
                    ok.getComponent() != null, ok.getPackage() != null), true);
            PendingIntent piOk = PendingIntent.getBroadcast(c, sid, ok,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(piOk.getIntentSender());
            Diag.i("selfupd", "install session " + sid + " committed (" + apk.length + " B) — waiting for the 1-tap prompt");
            return "installing " + version;
        } catch (Throwable t) {
            if (session != null) try { session.abandon(); } catch (Throwable ignored) { }
            Diag.w("selfupd", "session: " + t);
            App.notifyUpdateFailed(c, version);
            return "install-error";
        }
    }

    /** TRAP 3 — the explicit "install unknown apps" screen FOR OUR PACKAGE.
     *  ACTION_MANAGE_UNKNOWN_APP_SOURCES with `package:<pkg>` opens the per-app
     *  toggle directly (API 26+), which is the only place the grant exists. */
    static void askForUnknownSources(Context c) {
        try {
            if (Build.VERSION.SDK_INT < 26) return;
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse(SelfUpdateInstall.unknownSourcesUri(c.getPackageName())));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ASKED_UNKNOWN, true).apply();
            Diag.i("selfupd", "opened install-unknown-apps for package:" + c.getPackageName());
        } catch (Throwable t) {
            Diag.w("selfupd", "unknown-sources screen: " + t);
        }
    }

    /** True when any of our activities is the resumed/visible one (the only state in
     *  which a background-activity-launch is guaranteed on Android 14–16). */
    static boolean isAppForeground(Context c) {
        try {
            ActivityManager am = c.getSystemService(ActivityManager.class);
            if (am == null) return false;
            List<ActivityManager.RunningAppProcessInfo> ps = am.getRunningAppProcesses();
            if (ps == null) return false;
            for (ActivityManager.RunningAppProcessInfo p : ps) {
                if (!c.getPackageName().equals(p.processName)) continue;
                return p.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                    || p.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
            }
            return false;
        } catch (Throwable t) {
            Diag.w("selfupd", "foreground check: " + t);
            return false;   // unknown → take the notification path, never lose the update
        }
    }

    /** The PackageInstaller result broadcast.
     *  STATUS_PENDING_USER_ACTION → launch the confirm dialog when we may, otherwise
     *  (TRAP 2) post a high-priority tap-to-install notification carrying the SAME
     *  confirm intent — a notification tap is a user gesture, so BAL cannot block it. */
    public static final class InstallResultReceiver extends android.content.BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            try {
                int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm == null) {
                        Diag.w("selfupd", "pending-user-action without a confirm intent");
                        return;
                    }
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    boolean fg = isAppForeground(c);
                    boolean threw = false;
                    if (!SelfUpdateInstall.shouldPostNotification(fg, false)) {
                        try {
                            c.startActivity(confirm);            // we are foreground: guaranteed
                        } catch (Throwable t) {                   // SecurityException / BAL denial
                            threw = true;
                            Diag.w("selfupd", "startActivity(confirm) blocked (" + t + ") — notification fallback");
                        }
                    }
                    if (SelfUpdateInstall.shouldPostNotification(fg, threw)) {
                        // TRAP 2 — the update is NEVER lost in the background: the user
                        // gets a high-priority notification whose tap IS the install.
                        App.notifyTapToInstall(c, confirm);
                        Diag.i("selfupd", "background (fg=" + fg + ") — posted the tap-to-install notification");
                    } else {
                        Diag.i("selfupd", "user-action prompt shown");
                    }
                } else if (status == PackageInstaller.STATUS_SUCCESS) {
                    Diag.i("selfupd", "installed ✓");
                    App.notifyUpdateDone(c);
                } else {
                    Diag.w("selfupd", "install status " + status + ": "
                        + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
                    App.notifyUpdateFailed(c, AppInfo.VERSION_NAME);
                }
            } catch (Throwable t) {
                Diag.w("selfupd", "install result: " + t);
            }
        }
    }

    /** The JobScheduler entry: check + (when needed) download/verify/install. */
    public static final class CheckJob extends JobService {
        @Override public boolean onStartJob(final JobParameters params) {
            new Thread(() -> {
                try { checkAndMaybeInstall(getApplicationContext()); } catch (Throwable ignored) { }
                jobFinished(params, false);
                schedulePeriodic(getApplicationContext());   // keep the 6 h schedule alive
            }, "si-selfupd").start();
            return true;
        }
        @Override public boolean onStopJob(JobParameters params) { return true; }   // retry later
    }
}
