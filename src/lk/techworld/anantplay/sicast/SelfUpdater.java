package lk.techworld.anantplay.sicast;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Activity;
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
 * v3.2.0 — THE ZERO-RATE-LIMIT + ZERO-SPIN-LOOP UPDATER. The release lookup
 * uses only the web redirect and raw latest.json routes; automatic checks are
 * throttled, single-flight, and run off the launch thread:
 *
 *   1. THE RESOLVER NEVER CALLS the GitHub REST API —
 *      SelfUpdateCore.fetchReleaseAny() checks github.com/releases/latest's
 *      observable 302 first, then docs/latest.json on raw.githubusercontent.com.
 *
 *   2. THE 15-MINUTE FAILURE BACKOFF — a failed/offline check persists
 *      `lastFailedCheck` and EVERY automatic re-check inside 15 minutes is a
 *      hard no-op (manual checks bypass). Successful checks have a 5-minute
 *      cooldown. Even if JobScheduler re-delivers a
 *      job storm, the network sees ONE attempt per 15 minutes, never 200 per
 *      2.5 minutes. `lastCheck` is persisted BEFORE the network call (not only
 *      on success): the "attempt happened" fact survives crashes and
 *      overlapping entry points.
 *
 *   3. THE RE-ARM LOOP BREAKER — CheckJob.onStartJob NEVER re-arms the
 *      periodic schedule from inside itself (v3.1.x did: a rescheduled
 *      past-due periodic job re-fired immediately → fail → reschedule →
 *      re-fire, the ~250 ms cadence the field log captured). The periodic
 *      job's re-arm happens ONLY in App.onCreate; the job itself finishes
 *      cleanly and re-arms nothing.
 *
 * THE HONEST ANDROID 16 SECURITY MODEL (non-root, unchanged):
 *   Silent zero-tap APK installation is BLOCKED by the OS — PackageInstaller
 *   always shows the 1-tap system "Update" confirmation. Everything up TO that
 *   single tap is automatic:
 *     CHECK (app-open + 4 h persisted job) → DOWNLOAD (silent, throttled,
 *     live progress) → VERIFY (https-only, 30 MB cap, strict SHA-256 vs
 *     SHA256SUMS.txt — a tampered APK is refused and deleted) → INSTALL (the
  *     OS's 1-tap prompt, BAL-safe notification fallback) → CLEANUP (stale
  *     files are removed, while a verified APK remains until install permission
  *     is granted and the user returns). Obtainium compatibility is unchanged. */
public final class SelfUpdater {

    static final String REPO = SelfUpdateCore.REPO;
    /** The app-open success cooldown: automatic checks run at most every 5 min. */
    static final long MIN_CHECK_INTERVAL_MS = 5 * 60 * 1000L;
    /** v3.2.0 — THE FAILURE BACKOFF: after a failed/offline check, automatic
     *  re-checks are locked out for 15 minutes (the anti-hammer contract).
     *  A manual "Check now" always bypasses it — the user asked. */
    static final long FAILURE_BACKOFF_MS = 15 * 60 * 1000L;
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

    /** v3.1.0 (Bug 5) — the SINGLE-FLIGHT guard: the launch-check job and the
     *  4-hour periodic job can fire concurrently; without this, two downloads
     *  race on the same temp file and double-notify. */
    private static final java.util.concurrent.atomic.AtomicBoolean inFlight =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicBoolean launchQueued =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicBoolean installQueued =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.ExecutorService checkExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "si-updater");
            t.setDaemon(true);
            return t;
        });
    /** v3.1.0 (Bug 5) — the progress THROTTLE: ≤ 1 notify() per second, deduped
     *  per percent (UpdateNotify, lab-proven). */
    private static final UpdateNotify throttle = new UpdateNotify();
    /** v3.1.2 — the single-flight skip log's own rate limit (1/s). */
    private static volatile long lastSkipLogAt;
    private static final String PENDING_VERSION = "pendingInstallVersion";
    private static final String PENDING_SHA256 = "pendingInstallSha256";
    private static final String PENDING_FILE = "pendingInstallFile";
    private static final String PENDING_AWAITING_PERMISSION = "pendingInstallAwaitingPermission";

    private SelfUpdater() { }

    // ── v3.1.0: the notification action receiver (tap-to-install / skip) ───────

    static final String ACTION_INSTALL_NOW = "lk.techworld.anantplay.sicast.UPDATE_INSTALL_NOW";
    static final String ACTION_SKIP_VERSION = "lk.techworld.anantplay.sicast.UPDATE_SKIP_VERSION";

    /** The "Update ready — Tap to install" notification's content intent. */
    static PendingIntent confirmPendingIntent(Context c) {
        Intent i = new Intent(c, MainActivity.class).setAction(ACTION_INSTALL_NOW)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 20410, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The quiet Skip-this-version action. */
    static PendingIntent skipPendingIntent(Context c, String version) {
        Intent i = new Intent(c, UpdateActionReceiver.class).setAction(ACTION_SKIP_VERSION)
            .putExtra("version", version == null ? "" : version);
        return PendingIntent.getBroadcast(c, 20411, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** v3.1.0 — 1-tap notification actions: install-now (a manual re-drive) and
     * skip-this-version (records the choice, cancels the banners, never alerts
     * for that version again until a NEWER release appears). */
    public static final class UpdateActionReceiver extends android.content.BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            try {
                String a = i == null ? null : i.getAction();
                if (ACTION_SKIP_VERSION.equals(a)) {
                    String v = i.getStringExtra("version");
                    if (v != null && !v.isEmpty()) {
                        c.getSharedPreferences("selfupdate", Context.MODE_PRIVATE).edit()
                            .putString("skippedVersion", v).apply();
                        Diag.i("selfupd", "user skipped v" + v + " — no more alerts until a newer release");
                    }
                    cancelBanners(c);
                } else if (ACTION_INSTALL_NOW.equals(a)) {
                    cancelBanners(c);
                    Intent open = new Intent(c, MainActivity.class).setAction(ACTION_INSTALL_NOW)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    c.startActivity(open);
                }
            } catch (Throwable t) {
                Diag.w("selfupd", "action receiver: " + t);
            }
        }
    }

    /** Cancel every update banner id (the quiet way to disappear). */
    static void cancelBanners(Context c) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) for (int id : new int[]{20401, 20402, 20403, 20405, 20406}) nm.cancel(id);
        } catch (Throwable ignored) { }
    }

    // ── storage: the one temp dir + the startup sweep ─────────────────────────

    /** cacheDir/updates — the ONLY place a download ever lands. */
    static File updatesDir(Context c) {
        File d = new File(c.getCacheDir(), "updates");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static void clearPendingMetadata(Context c) {
        c.getSharedPreferences("selfupdate", Context.MODE_PRIVATE).edit()
            .remove(PENDING_VERSION).remove(PENDING_SHA256).remove(PENDING_FILE)
            .remove(PENDING_AWAITING_PERMISSION).apply();
    }

    /** Returns only a name-checked file beneath cacheDir/updates. */
    private static File pendingFile(Context c) {
        android.content.SharedPreferences sp = c.getSharedPreferences("selfupdate", Context.MODE_PRIVATE);
        String version = sp.getString(PENDING_VERSION, "");
        String hash = sp.getString(PENDING_SHA256, "");
        String name = sp.getString(PENDING_FILE, "");
        if (version == null || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")
                || hash == null || !hash.matches("(?i)[0-9a-f]{64}")
                || !SelfUpdateCore.apkAssetName(version).equals(name)) {
            clearPendingMetadata(c);
            return null;
        }
        File dir = updatesDir(c);
        File f = new File(dir, name);
        if (!f.isFile() || f.length() <= 0 || !f.getParentFile().equals(dir)) {
            clearPendingMetadata(c);
            return null;
        }
        return f;
    }

    private static void savePendingUpdate(Context c, File apk, String version, String sha256) {
        if (apk == null || version == null || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")
                || sha256 == null || !sha256.matches("(?i)[0-9a-f]{64}")
                || !SelfUpdateCore.apkAssetName(version).equals(apk.getName())
                || !apk.getParentFile().equals(updatesDir(c))) {
            throw new IllegalArgumentException("invalid pending APK metadata");
        }
        c.getSharedPreferences("selfupdate", Context.MODE_PRIVATE).edit()
            .putString(PENDING_VERSION, version)
            .putString(PENDING_SHA256, sha256.toLowerCase(java.util.Locale.ROOT))
            .putString(PENDING_FILE, apk.getName())
            .putBoolean(PENDING_AWAITING_PERMISSION, false)
            .apply();
    }

    static boolean hasPendingUpdate(Context c) {
        return pendingFile(c) != null;
    }

    static String pendingVersion(Context c) {
        return hasPendingUpdate(c)
            ? c.getSharedPreferences("selfupdate", Context.MODE_PRIVATE).getString(PENDING_VERSION, "")
            : "";
    }

    /** Remove stale downloads while preserving a verified APK waiting for the
     *  user to enable install permission and return to the app. */
    static long sweepUpdates(Context c) {
        try {
            File dir = updatesDir(c);
            File pending = pendingFile(c);
            if (pending == null) return SelfUpdateCore.deleteAll(dir);
            long freed = 0;
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) {
                if (f.equals(pending)) continue;
                long size = f.length();
                if (f.delete()) freed += size;
            }
            if (freed > 0) Diag.i("selfupd", "startup sweep freed " + (freed / 1024) + " KiB of stale files");
            return freed;
        } catch (Throwable t) {
            Diag.w("selfupd", "sweep: " + t);
            return 0;
        }
    }

    // ── the checks ────────────────────────────────────────────────────────────

    /** v3.1.2 — the in-process zero-delay schedule debounce (kept: still useful
     *  against a caller storm from the UI layer). */
    static final long SCHEDULE_DEBOUNCE_MS = 10 * 1000L;
    private static volatile long lastScheduleAt;

    /**
     * v3.2.0 — THE BACKOFF GATE (delegates to the pure SelfUpdateCore
     * contract — the lab proves every branch + the exact field storm):
     *   · the 5-minute app-open success cooldown (lastCheck — persisted BEFORE the
     *     network attempt, so it holds even when the attempt crashed);
     *   · the 15-minute FAILURE backoff (lastFailedCheck);
     *   · the 4-hour periodic cadence is JobScheduler's own — but a periodic
     *     callback rides through the same gate.
     * `manual=true` (the user pressed Check now) bypasses everything.
     */
    static boolean checkAllowed(long nowMs, long lastCheck, long lastFailedCheck, boolean manual) {
        return SelfUpdateCore.checkAllowed(nowMs, lastCheck, lastFailedCheck, manual);
    }

    /** Called by MainActivity after the live UI listener is ready. The check
     *  runs immediately on a daemon worker; JobScheduler is only the periodic
     *  fallback. The attempt timestamp is written inside the worker directly
     *  before networking, avoiding a deferred-job race with the cooldown gate. */
    public static void checkOnLaunch(final Context c) {
        try {
            android.content.Context app = c.getApplicationContext();
            if (!app.getSharedPreferences("sicast", Context.MODE_PRIVATE)
                    .getBoolean(AppPrefs.K_AUTO_UPDATE, true)) return;
            if (pendingFile(app) != null) {
                fire("Update ready — tap Install Update");
                return;
            }
            android.content.SharedPreferences sp = app.getSharedPreferences("selfupdate", Context.MODE_PRIVATE);
            long now = System.currentTimeMillis();
            long last = sp.getLong("lastCheck", 0);
            long lastFail = sp.getLong("lastFailedCheck", 0);
            if (!checkAllowed(now, last, lastFail, false)) return;
            if (!launchQueued.compareAndSet(false, true)) return;
            fire("Checking…");
            checkExecutor.execute(() -> {
                try { checkAndMaybeInstall(app, false, false); }
                finally { launchQueued.set(false); }
            });
        } catch (Throwable t) {
            Diag.w("selfupd", "launch check: " + t);
            launchQueued.set(false);
        }
    }

    /** Manual checks bypass cooldowns and use the same REST-free release resolver. */
    public static void checkNow(Context c) {
        Context app = c.getApplicationContext();
        fire("Checking…");
        checkExecutor.execute(() -> checkAndMaybeInstall(app, true, false));
    }

    /** A deliberate Update Now action also bypasses the Auto-update preference. */
    public static void updateNow(Context c) {
        Context app = c.getApplicationContext();
        fire("Checking…");
        checkExecutor.execute(() -> checkAndMaybeInstall(app, true, true));
    }

    /** User-tap install entry: permission settings open only after this action. */
    static String installPendingUpdate(Context c, boolean promptIfNeeded) {
        File apk = pendingFile(c);
        if (apk == null) {
            fire("No verified update is ready");
            return "no pending update";
        }
        String version = c.getSharedPreferences("selfupdate", Context.MODE_PRIVATE)
            .getString(PENDING_VERSION, "");
        if (!canRequestInstalls(c)) {
            fire("Install permission needed — tap to allow");
            if (!promptIfNeeded) return "needs install permission";
            c.getSharedPreferences("selfupdate", Context.MODE_PRIVATE).edit()
                .putBoolean(PENDING_AWAITING_PERMISSION, true).apply();
            try {
                Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + c.getPackageName()));
                if (!(c instanceof Activity)) settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(settings);
            } catch (Throwable t) {
                Diag.w("selfupd", "open install permission: " + t);
                fire("Could not open install permission settings");
                return "permission settings unavailable";
            }
            return "opened install permission settings";
        }
        c.getSharedPreferences("selfupdate", Context.MODE_PRIVATE).edit()
            .putBoolean(PENDING_AWAITING_PERMISSION, false).apply();
        if (!installQueued.compareAndSet(false, true)) return "install already in progress";
        final Context app = c.getApplicationContext();
        final File verifiedFile = apk;
        fire("Verifying update v" + version + " — opening Android installer");
        try {
            checkExecutor.execute(() -> {
                try { verifyAndInstallPending(app, verifiedFile, version); }
                finally { installQueued.set(false); }
            });
        } catch (Throwable t) {
            installQueued.set(false);
            Diag.w("selfupd", "queue installer handoff: " + t);
            fire("Could not start installer — tap Install Update to retry");
            return "could not queue installer";
        }
        return "installing " + version;
    }

    /** Called on Activity resume after the user returns from install permission settings. */
    public static void resumePendingInstall(Context c) {
        try {
            android.content.SharedPreferences sp = c.getSharedPreferences("selfupdate", Context.MODE_PRIVATE);
            if (sp.getBoolean(PENDING_AWAITING_PERMISSION, false) && canRequestInstalls(c)) {
                sp.edit().putBoolean(PENDING_AWAITING_PERMISSION, false).apply();
                installPendingUpdate(c, false);
            }
        } catch (Throwable t) {
            Diag.w("selfupd", "resume pending install: " + t);
        }
    }

    private static boolean canRequestInstalls(Context c) {
        try { return c.getPackageManager().canRequestPackageInstalls(); }
        catch (Throwable t) { return false; }
    }

    private static void verifyAndInstallPending(Context c, File apk, String version) {
        android.content.SharedPreferences sp = c.getSharedPreferences("selfupdate", Context.MODE_PRIVATE);
        String expected = sp.getString(PENDING_SHA256, "");
        try {
            if (!apk.isFile() || expected == null || !expected.matches("(?i)[0-9a-f]{64}")) {
                throw new SecurityException("pending update metadata is incomplete");
            }
            if (!SelfUpdateCore.matchesSha256(apk, expected)) {
                throw new SecurityException("pending APK SHA-256 no longer matches");
            }
            install(c, apk);
            apk.delete();
            clearPendingMetadata(c);
            sweepUpdates(c);
            fire("v" + version + Chrome.pick(" සූදානම් — 1-tap ස්ථාපනය · ready to install (1 tap)"));
        } catch (Throwable t) {
            Diag.w("selfupd", "pending install verification/handoff: " + t);
            if (apk.exists() && expected != null && !expected.isEmpty()) {
                // Keep only a retryable file when its recorded hash still matches.
                try {
                    if (!SelfUpdateCore.matchesSha256(apk, expected)) apk.delete();
                } catch (Throwable ignored) { apk.delete(); }
            }
            if (!apk.exists()) clearPendingMetadata(c);
            fire("Install failed — tap Install Update to retry");
        }
    }

    /** One-shot ASAP job (network permitting).
     *  v3.1.2: the in-process debounce makes an immediate re-schedule a NO-OP. */
    public static void schedule(Context c, long delayMs) {
        try {
            // the spin-loop breaker: a zero-delay re-schedule inside the debounce
            // window is silently ignored (the first job is already pending/firing)
            if (delayMs <= 0) {
                long last = lastScheduleAt;
                long now = System.currentTimeMillis();
                if (last > 0 && now - last < SCHEDULE_DEBOUNCE_MS) return;
                lastScheduleAt = now;
            }
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js == null) return;
            JobInfo.Builder b = new JobInfo.Builder(JOB_ID,
                new ComponentName(c, CheckJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(delayMs)
                .setOverrideDeadline(delayMs + 30 * 60 * 1000L);
            js.schedule(b.build());
        } catch (Throwable t) {
            Diag.w("selfupd", "schedule: " + t);
        }
    }

    /** The standing 4-hour periodic schedule (persisted across reboots).
     *  v3.2.0 — re-armed ONLY from App.onCreate. NEVER from inside
     *  CheckJob.onStartJob: rescheduling a past-due periodic job from inside
     *  its own run made JobScheduler re-deliver it IMMEDIATELY (fail →
     *  reschedule → re-fire at the ~250 ms cadence of the field log). */
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

    /** One update attempt (automatic cycle). */
    public static String checkAndMaybeInstall(final Context c) {
        return checkAndMaybeInstall(c, false);
    }

    /** One update attempt. `manualCheck` bypasses both cooldowns; `forceDownload`
     *  lets the explicit Update Now button work with Auto-update disabled. */
    public static String checkAndMaybeInstall(final Context c, final boolean manualCheck) {
        return checkAndMaybeInstall(c, manualCheck, false);
    }

    static String checkAndMaybeInstall(final Context c, final boolean manualCheck, final boolean forceDownload) {
        if (!inFlight.compareAndSet(false, true)) {
            // v3.1.2 — the rate-limited skip: at most ONE skip line per second.
            long now = System.currentTimeMillis();
            long last = lastSkipLogAt;
            if (last == 0 || now - last >= 1000L) {
                lastSkipLogAt = now;
                Diag.i("selfupd", "single-flight: another update run is active — skipping");
            }
            return "in-flight";
        }
        // A verified pending package is never re-downloaded or overwritten.
        if (pendingFile(c) != null) {
            inFlight.set(false);
            fire("Update ready — tap Install Update");
            return "ready";
        }
        android.content.SharedPreferences sp = c.getSharedPreferences("selfupdate", Context.MODE_PRIVATE);
        if (!manualCheck) {
            long now = System.currentTimeMillis();
            if (!checkAllowed(now, sp.getLong("lastCheck", 0), sp.getLong("lastFailedCheck", 0), false)) {
                inFlight.set(false);
                return "backoff";
            }
            sp.edit().putLong("lastCheck", now).apply();       // persisted BEFORE the network call
        }
        try {
            fire("Checking…");
            SelfUpdateCore.Release rel = SelfUpdateCore.fetchReleaseAny();
            if (rel == null) {
                // Persist the failure so neither launch nor periodic jobs can
                // turn an offline phone into a rapid retry loop.
                sp.edit().putLong("lastFailedCheck", System.currentTimeMillis()).apply();
                Diag.w("selfupd", "check failed on both HTTPS routes — 15-min backoff engaged");
                Troubleshooter.record("UPDATER", "check: no release info (both routes failed) — 15-min backoff engaged");
                fire("Update check failed — retrying after the 15-minute cooldown");
                return "no release";
            }
            Diag.i("selfupd", "release resolved via tier: " + rel.tier + " → v" + rel.version);
            Troubleshooter.record("UPDATER", "release v" + rel.version + " resolved via " + rel.tier
                + " (GitHub REST API calls: 0)");
            if (!UpdateChecker.isNewer(rel.version, AppInfo.VERSION_NAME)) {
                Diag.i("selfupd", "up to date (" + AppInfo.VERSION_NAME + "; latest " + rel.version + ")");
                Troubleshooter.record("UPDATER", "check: up-to-date (" + AppInfo.VERSION_NAME + "; latest " + rel.version + ")");
                fire("Up to date (v" + AppInfo.VERSION_NAME + ")");
                return "up-to-date";
            }
            // v3.1.0 — the skip choice: never alert about a skipped version again
            String skippedVersion = sp.getString("skippedVersion", null);
            if (UpdateNotify.stillSkipped(skippedVersion, rel.version)) {
                Diag.i("selfupd", "v" + rel.version + " skipped by the user — staying quiet");
                fire("Skipped v" + rel.version);
                return "skipped";
            }
            // v3.1.0 — the 24 h per-version alert cooldown (manual checks bypass)
            String alertKey = "lastAlert:" + rel.version;
            long lastAlert = sp.getLong(alertKey, 0);
            if (!UpdateNotify.alertAllowed(rel.version, System.currentTimeMillis(), lastAlert,
                    skippedVersion, manualCheck)) {
                Diag.i("selfupd", "v" + rel.version + " alerted "
                    + ((System.currentTimeMillis() - lastAlert) / 3600000L) + " h ago — cooldown, staying quiet");
                fire("Update alert is cooling down");
                return "cooldown";
            }
            sp.edit().putLong(alertKey, System.currentTimeMillis()).apply();

            UiListener u = ui;
            if (u != null) { try { u.onUpdateAvailable(rel.version); } catch (Throwable ignored) { } }
            fire("Update available: v" + AppInfo.VERSION_NAME + " → v" + rel.version);

            boolean autoUpdate = c.getSharedPreferences("sicast", Context.MODE_PRIVATE)
                .getBoolean(AppPrefs.K_AUTO_UPDATE, true);
            if (!autoUpdate && !forceDownload) {
                Diag.i("selfupd", "new release " + rel.version + " but Auto-update is OFF — waiting for Update Now");
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
            if (rel.sha256Expected == null || !rel.sha256Expected.matches("(?i)[0-9a-f]{64}")) {
                // a release without a SHA256SUMS line for the apk: refuse (security)
                Diag.w("selfupd", "no SHA-256 line for " + SelfUpdateCore.apkFileName(rel.apkUrl) + " — refusing");
                App.notifyUpdateFailed(c, rel.version);
                fire("Update refused: no valid SHA-256");
                return "no-sha";
            }
            Diag.i("selfupd", "new release " + rel.version + " > " + AppInfo.VERSION_NAME + " — downloading (silent, throttled)");
            fire("Downloading v" + rel.version + " — 0%");

            // ── download → cacheDir/updates/<name>.apk with THROTTLED progress ──
            sweepUpdates(c);   // never two temp files at once
            final File apk = new File(updatesDir(c), SelfUpdateCore.apkFileName(rel.apkUrl));
            UiListener initialProgress = ui;
            if (initialProgress != null) {
                try { initialProgress.onDownloadProgress(0); } catch (Throwable ignored) { }
            }
            App.notifyUpdateProgress(c, rel.version, 0);
            SelfUpdateCore.downloadToFile(rel.apkUrl, apk, (bytes, total, percent) -> {
                if (throttle.onProgress(percent)) {
                    App.notifyUpdateProgress(c, rel.version, percent);
                }
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
                fire(Chrome.pick("බාගැනීම අසාර්ථක (SHA-256 නොගැලපේ) · download failed verification"));
                return "sha-mismatch";
            }
            Diag.i("selfupd", "sha256 verified (" + sha + ") — saving verified update for installer handoff");
            savePendingUpdate(c, apk, rel.version, sha);
            App.notifyUpdateReady(c, rel.version);
            fire("Update ready: v" + rel.version + " — tap Install Update");
            if (canRequestInstalls(c)) {
                installPendingUpdate(c, false);
                return "installing " + rel.version;
            }
            // Permission is requested only after the user taps Install Update.
            return "ready " + rel.version;
        } catch (Throwable t) {
            // v3.2.0 — a thrown failure arms the SAME 15-minute backoff
            try {
                sp.edit().putLong("lastFailedCheck", System.currentTimeMillis()).apply();
            } catch (Throwable ignored) { }
            Diag.w("selfupd", "check: " + t);
            fire(Chrome.pick("යාවත්කාලීන පරීක්ෂාව අසාර්ථක — මිනිත්තුවකින් නැවත · update check failed — retrying within 15 min"));
            Troubleshooter.record("UPDATER", "check threw: " + t.getClass().getSimpleName() + " — 15-min backoff engaged");
            return "offline";
        } finally {
            inFlight.set(false);
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

    /** The BAL-safe fallback: the confirm intent as a quiet tap-to-install note. */
    static void postConfirmNotification(Context c, Intent confirm) {
        try {
            PendingIntent open = PendingIntent.getActivity(c, 20405, confirm,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(c, App.CHANNEL_UPDATES_SILENT)
                .setSmallIcon(R.drawable.ic_stat_si)
                .setContentTitle("AnantPlay Sinhala Player — update")
                .setContentText("ස්ථාපනය කිරීමට තට්ටු කරන්න · tap to install (1 tap)")
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
            c.getSystemService(android.app.NotificationManager.class).notify(20405, n);
        } catch (Throwable t) {
            Diag.w("selfupd", "confirm notification: " + t);
        }
    }

    /** The JobScheduler entry: check + (when needed) download/verify/install.
     *  v3.2.0 — NO schedulePeriodic() HERE: the periodic re-arm lives in
     *  App.onCreate ONLY (the in-job re-arm was the reschedule→re-fire loop). */
    public static final class CheckJob extends JobService {
        @Override public boolean onStartJob(final JobParameters params) {
            new Thread(() -> {
                try { checkAndMaybeInstall(getApplicationContext()); } catch (Throwable ignored) { }
                jobFinished(params, false);
            }, "si-selfupd").start();
            return true;
        }
        @Override public boolean onStopJob(JobParameters params) { return false; }   // the 4 h job re-fires by itself
    }
}
