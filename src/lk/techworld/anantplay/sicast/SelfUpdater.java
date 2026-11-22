package lk.techworld.anantplay.sicast;

import android.app.Notification;
import android.app.NotificationManager;
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
 * v3.2.0 — THE ZERO-RATE-LIMIT + ZERO-SPIN-LOOP UPDATER (Smoking Gun #1, the
 * 200-calls-in-148-s field log). THREE structural changes, all lab-proven:
 *
 *   1. THE RESOLVER NEVER TOUCHES api.github.com on the happy path —
 *      SelfUpdateCore.fetchReleaseAny() fires github.com/releases/latest's
 *      observable 302 (web CDN, NO quota) first, docs/latest.json on
 *      raw.githubusercontent.com second, and the rate-limited REST API ONLY as
 *      the last resort. The "60/ hr lockout → null release → re-check"
 *      death spiral is dead at the source.
 *
 *   2. THE 15-MINUTE FAILURE BACKOFF — a failed/offline check persists
 *      `lastFailedCheck` and EVERY automatic re-check inside 15 minutes is a
 *      hard no-op (manual checks bypass). Even if JobScheduler re-delivers a
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
 *     OS's 1-tap prompt, BAL-safe notification fallback) → CLEANUP (the temp
 *     .apk is deleted the moment the session takes it over; the startup sweep
 *     removes anything older). Obtainium compatibility is unchanged. */
public final class SelfUpdater {

    static final String REPO = SelfUpdateCore.REPO;
    /** v3.2.0 — the REST endpoint is now TIER 3 (last resort only). */
    static final String API_LATEST = SelfUpdateCore.restLatestUrl();
    /** v3.0.0 — the app-open debounce: a check runs at most once per 60 s. */
    static final long MIN_CHECK_INTERVAL_MS = 60 * 1000L;
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
    /** v3.1.0 (Bug 5) — the progress THROTTLE: ≤ 1 notify() per second, deduped
     *  per percent (UpdateNotify, lab-proven). */
    private static final UpdateNotify throttle = new UpdateNotify();
    /** v3.1.2 — the single-flight skip log's own rate limit (1/s). */
    private static volatile long lastSkipLogAt;

    private SelfUpdater() { }

    // ── v3.1.0: the notification action receiver (tap-to-install / skip) ───────

    static final String ACTION_INSTALL_NOW = "lk.techworld.anantplay.sicast.UPDATE_INSTALL_NOW";
    static final String ACTION_SKIP_VERSION = "lk.techworld.anantplay.sicast.UPDATE_SKIP_VERSION";

    /** The "Update ready — Tap to install" notification's content intent. */
    static PendingIntent confirmPendingIntent(Context c) {
        Intent i = new Intent(c, UpdateActionReceiver.class).setAction(ACTION_INSTALL_NOW);
        return PendingIntent.getBroadcast(c, 20410, i,
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
                    schedule(c, 0L);   // an immediate best-effort job; the user asked
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

    /** v3.0.0 — delete EVERY leftover .apk in cacheDir/updates (App.onCreate +
     *  after every session handoff): storage space is always 100% cleared. */
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

    /** v3.1.2 — the in-process zero-delay schedule debounce (kept: still useful
     *  against a caller storm from the UI layer). */
    static final long SCHEDULE_DEBOUNCE_MS = 10 * 1000L;
    private static volatile long lastScheduleAt;

    /**
     * v3.2.0 — THE BACKOFF GATE (delegates to the pure SelfUpdateCore
     * contract — the lab proves every branch + the exact field storm):
     *   · the 60 s app-open debounce (lastCheck — persisted BEFORE the
     *     network attempt, so it holds even when the attempt crashed);
     *   · the 15-minute FAILURE backoff (lastFailedCheck);
     *   · the 4-hour periodic cadence is JobScheduler's own — but a periodic
     *     callback rides through the same gate.
     * `manual=true` (the user pressed Check now) bypasses everything.
     */
    static boolean checkAllowed(long nowMs, long lastCheck, long lastFailedCheck, boolean manual) {
        return SelfUpdateCore.checkAllowed(nowMs, lastCheck, lastFailedCheck, manual);
    }

    /** Called from App.onCreate: fire the background check at most once per
     *  60 s (the app-open debounce) AND never inside the 15-minute failure
     *  backoff. `lastCheck` is written BEFORE the job is handed over — the
     *  "an attempt is running" fact survives a crash mid-check (the v3.1.x
     *  bug: it was persisted only after a SUCCESSFUL schedule, so a failed
     *  round kept the gate open forever). Non-blocking, never throws. */
    public static void checkOnLaunch(final Context c) {
        try {
            android.content.SharedPreferences sp = c.getSharedPreferences("selfupdate", Context.MODE_PRIVATE);
            long now = System.currentTimeMillis();
            long last = sp.getLong("lastCheck", 0);
            long lastFail = sp.getLong("lastFailedCheck", 0);
            if (!checkAllowed(now, last, lastFail, false)) return;
            sp.edit().putLong("lastCheck", now).apply();       // PERSISTED BEFORE the network call
            schedule(c, 0L);
        } catch (Throwable t) {
            Diag.w("selfupd", "launch check: " + t);
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

    /**
     * One update attempt. v3.2.0 changes:
     *   · THE RESOLVER — SelfUpdateCore.fetchReleaseAny() (tier 1 web redirect
     *     → tier 2 raw.json → tier 3 REST last resort): ZERO api.github.com
     *     calls on the happy path, so the 60/hr lockout can never again feed
     *     the "no release info" loop;
     *   · THE BACKOFF — a null/offline result persists `lastFailedCheck` NOW,
     *     so every automatic re-check inside 15 minutes is a no-op even if
     *     something re-delivers a job (manual checks bypass);
     *   · single-flight + skip-version + 24 h per-version alert cooldown +
     *     throttled progress (all inherited from v3.1.0/v3.1.2, lab-proven).
     * Returns a human-readable result (also logged). */
    public static String checkAndMaybeInstall(final Context c, final boolean manualCheck) {
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
        // v3.2.0 — the automatic backoff gate, enforced INSIDE the run too:
        // a re-delivered job that slipped past schedule() still dies here.
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
            SelfUpdateCore.Release rel = SelfUpdateCore.fetchReleaseAny();
            if (rel == null) {
                // v3.2.0 — the failure is PERSISTED: 15 minutes of guaranteed
                // network silence follow, whatever re-delivers a job (the exact
                // anti-250-ms-loop contract, lab-proven).
                sp.edit().putLong("lastFailedCheck", System.currentTimeMillis()).apply();
                Diag.w("selfupd", "check failed on every tier (web-redirect/raw-json/rest) — 15-min backoff engaged");
                Troubleshooter.record("UPDATER", "check: no release info (all 3 tiers failed) — 15-min backoff engaged");
                return "no release";
            }
            Diag.i("selfupd", "release resolved via tier: " + rel.tier + " → v" + rel.version);
            Troubleshooter.record("UPDATER", "release v" + rel.version + " resolved via " + rel.tier
                + " (api.github.com " + (SelfUpdateCore.TIER_REST_API.equals(rel.tier) ? "tier-3 last resort" : "NOT touched") + ")");
            if (!UpdateChecker.isNewer(rel.version, AppInfo.VERSION_NAME)) {
                Diag.i("selfupd", "up to date (" + AppInfo.VERSION_NAME + "; latest " + rel.version + ")");
                Troubleshooter.record("UPDATER", "check: up-to-date (" + AppInfo.VERSION_NAME + "; latest " + rel.version + ")");
                return "up-to-date";
            }
            // v3.1.0 — the skip choice: never alert about a skipped version again
            String skippedVersion = sp.getString("skippedVersion", null);
            if (UpdateNotify.stillSkipped(skippedVersion, rel.version)) {
                Diag.i("selfupd", "v" + rel.version + " skipped by the user — staying quiet");
                return "skipped";
            }
            // v3.1.0 — the 24 h per-version alert cooldown (manual checks bypass)
            String alertKey = "lastAlert:" + rel.version;
            long lastAlert = sp.getLong(alertKey, 0);
            if (!UpdateNotify.alertAllowed(rel.version, System.currentTimeMillis(), lastAlert,
                    skippedVersion, manualCheck)) {
                Diag.i("selfupd", "v" + rel.version + " alerted "
                    + ((System.currentTimeMillis() - lastAlert) / 3600000L) + " h ago — cooldown, staying quiet");
                return "cooldown";
            }
            sp.edit().putLong(alertKey, System.currentTimeMillis()).apply();

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
            Diag.i("selfupd", "new release " + rel.version + " > " + AppInfo.VERSION_NAME + " — downloading (silent, throttled)");
            UiListener u = ui;
            if (u != null) { try { u.onUpdateAvailable(rel.version); } catch (Throwable ignored) { } }

            // ── download → cacheDir/updates/<name>.apk with THROTTLED progress ──
            sweepUpdates(c);   // never two temp files at once
            final File apk = new File(updatesDir(c), SelfUpdateCore.apkFileName(rel.apkUrl));
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
                fire("බාගැනීම අසාර්ථක (SHA-256 නොගැලපේ) · download failed verification");
                return "sha-mismatch";
            }
            Diag.i("selfupd", "sha256 verified (" + sha + ") — handing off to PackageInstaller");

            // ── the Install-Unknown-Apps gate: a 1-tap jump when missing ──
            if (!c.getPackageManager().canRequestPackageInstalls()) {
                Diag.w("selfupd", "canRequestPackageInstalls=false — posting the 1-tap permission prompt");
                notifyInstallPermission(c, rel.version);
                fire("ස්ථාපන අවසරය අවශ්‍යයි — දැනුම්දීම ඔබන්න · install permission needed — tap the notification");
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
            // v3.2.0 — a thrown failure arms the SAME 15-minute backoff
            try {
                sp.edit().putLong("lastFailedCheck", System.currentTimeMillis()).apply();
            } catch (Throwable ignored) { }
            Diag.w("selfupd", "check: " + t);
            fire("යාවත්කාලීන පරීක්ෂාව අසාර්ථක — මිනිත්තුවකින් නැවත · update check failed — retrying within 15 min");
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

    /** The 1-tap "Install unknown apps" prompt: a notification carrying the
     *  exact settings deep-link (v3.1.0: silent channel). */
    static void notifyInstallPermission(Context c, String version) {
        try {
            Intent open = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + c.getPackageName()));
            PendingIntent pi = PendingIntent.getActivity(c, 20406, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(c, App.CHANNEL_UPDATES_SILENT)
                .setSmallIcon(R.drawable.ic_stat_si)
                .setContentTitle("v" + version + " — install permission needed")
                .setContentText("1-tap: \"Unknown sources\" මෙම ඇප් එකට ON කරන්න · allow installs from this app")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
            c.getSystemService(android.app.NotificationManager.class).notify(20406, n);
        } catch (Throwable t) {
            Diag.w("selfupd", "install-permission notification: " + t);
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
