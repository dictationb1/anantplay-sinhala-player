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
 * v3.4.6.f — THE SIX UPDATER LAWS (the v3.4.6.e real-phone field report rebuild):
 *
 *   LAW #1 — MATHEMATICALLY IMPOSSIBLE SAME-VERSION LOCK. The v3.4.6.e
 *     screenshot locked a user ALREADY on v3.4.6.e behind a gate reading
 *     "v3.4.6.e → v3.4.6.e": the pending-APK metadata survived the completed
 *     install (Obtainium lane / a killed cleanup), and EVERY re-arm path
 *     (pendingFile() != null) treated that stale metadata as gospel without
 *     ONE isNewer() check. Now: sweepStalePending() runs on App.onCreate AND
 *     every check entry; a pending version that is not STRICTLY newer than
 *     AppInfo.VERSION_NAME (isNewer == false: equal, older, junk) is deleted
 *     from prefs AND from disk, and every gate-arming path (Java AND Dart)
 *     requires UpdateChecker.isNewer() == true.
 *
 *   LAW #2 — ZERO BACKGROUND AUTO-DOWNLOAD. The automatic legs (launch /
 *     resume / periodic / Check-for-update) run EXACTLY ONE lightweight
 *     single-flight version check (AtomicBoolean checkInFlight + a 60-second
 *     in-process dedupe — the v3.4.6.e timeline recorded 7 checks inside
 *     12 seconds), resolve Tier-1 302 → Tier-2 docs/latest.json with 5s
 *     connect / 8s read timeouts (zero REST-API calls, NEVER a 32-second
 *     hang), and when a STRICTLY newer release exists they arm the
 *     ForceUpdateScreen gate and WAIT for the user's Update Now tap. No byte
 *     of APK is ever fetched by a background leg. Auto-update OFF only mutes
 *     the 4-hour periodic job — the CHECK (and the gate) always runs.
 *
 *   LAW #3 — PERMISSION FIRST, DOWNLOAD EXACTLY ONCE. Update Now checks
 *     canRequestPackageInstalls() FIRST (Step 1 of 2: the inline guide +
 *     Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES + a bounded 30-second
 *     watcher that auto-resumes the instant the grant lands); Step 2 is a
 *     single-flight download (AtomicBoolean downloadInFlight) that reuses an
 *     already-cached, SHA-256-verified APK instead of re-downloading; Step 3
 *     verifies the strict SHA-256 against SHA256SUMS.txt (a tampered APK is
 *     refused + deleted) and hands the verified file to Android's
 *     PackageInstaller 1-tap confirmation sheet.
 *
 * The HONEST ANDROID 16 SECURITY MODEL (non-root, v3.4.6.k):
 *   Silent zero-tap APK installation is ARMED by the OS on Android 12–16:
 *   UPDATE_PACKAGES_WITHOUT_USER_ACTION + SessionParams
 *   .setRequireUserAction(USER_ACTION_NOT_REQUIRED) (API 31+, targetSdk
 *   36 ≥ 34) — same-signature letter-patch updates (3.4.6.k → .l → .m)
 *   install with ZERO prompt (a top glass pill "Updating to vX…" only).
 *   A full 1-tap system "Update" sheet appears ONLY when the OEM refuses
 *   the silent lane (STATUS_PENDING_USER_ACTION) or the install permission
 *   is missing — and then exactly ONCE (single-flight + 15 s cooldown).
 *   Major/minor updates (3.5.0, 3.6.0…) present the full-screen
 *   non-dismissible ForceUpdateScreen instead. DOWNLOAD is single-flight
 *   with live progress; VERIFY is https-only, 100 MB cap, strict SHA-256
 *   vs SHA256SUMS.txt (a tampered APK is refused and deleted); INSTALL is
 *   the OS's silent lane (BAL-safe notification fallback); CLEANUP removes
 *   stale files while a verified APK remains until it installs.
 *   Obtainium compatibility is unchanged. */
public final class SelfUpdater {

    static final String REPO = SelfUpdateCore.REPO;
    /** The app-open success cooldown: automatic checks run at most every 5 min. */
    static final long MIN_CHECK_INTERVAL_MS = 5 * 60 * 1000L;
    /** v3.2.0 — THE FAILURE BACKOFF: after a failed/offline check, automatic
     *  re-checks are locked out for 15 minutes (the anti-hammer contract).
     *  A manual "Check now" always bypasses it — the user asked. */
    static final long FAILURE_BACKOFF_MS = 15 * 60 * 1000L;
    /** v3.4.6.f — LAW #2: the 60-second in-process check dedupe. The
     *  v3.4.6.e timeline captured SEVEN updater checks inside 12 seconds
     *  (launch + resume + Dart-side checkNow storms all firing inside one
     *  minute). v3.4.6.h — THE TRAP IS DEAD: this gate now applies ONLY to
     *  repeated ALREADY-UP-TO-DATE resume checks inside the same process
     *  while the network never changed (see resumeAllows). Manual checks,
     *  cold-start launch checks and offline→online transitions NEVER pass
     *  through it — the v3.4.6.g "Update Now vanished after reopen" defect
     *  was exactly this gate eating the one check that mattered. */
    static final long PROCESS_DEDUPE_MS = 60 * 1000L;
    /** v3.4.6.h — the manual-check anti-double-tap debounce (tiny, 3 s: a
     *  rage-tap on Check for update fires ONE network check, but a user who
     *  taps again 4 s later genuinely gets a fresh answer). */
    static final long MANUAL_TAP_DEBOUNCE_MS = 3 * 1000L;
    /** v3.0.0 — the standing periodic check: every 4 hours (was 6 h). */
    static final long PERIODIC_MS = 4 * 60 * 60 * 1000L;
    static final int JOB_ID = 20400;

    /** v3.4.6.f — the last in-process network-check attempt (LAW #2 dedupe). */
    private static volatile long lastCheckAttemptAt;
    /** v3.4.6.h — the conclusion of the last completed check in THIS process:
     *  "uptodate" | "available" | "failed". Only an up-to-date conclusion
     *  may be deduped by a resume; a failure or an available gate always
     *  re-checks the moment the user or the network asks. */
    private static volatile String lastCheckConclusion = "";
    /** v3.4.6.h — the network generation: bumped on EVERY offline→online
     *  transition (ConnectivityManager callback), so a resume check inside
     *  the 60 s window still fires when the network CHANGED since the last
     *  check. */
    private static volatile int networkGeneration;
    /** v3.4.6.h — the generation the last check attempt ran under. */
    private static volatile int lastCheckGeneration;
    /** v3.4.6.h — the last manual checkNow tap (the 3 s anti-double-tap). */
    private static volatile long lastManualTapAt;
    /** v3.4.6.f — strict single-flight for the NETWORK CHECK leg. */
    private static final java.util.concurrent.atomic.AtomicBoolean checkInFlight =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    /** v3.4.6.f — LAW #3 Step 2: strict single-flight for the DOWNLOAD leg
     *  (the multi-download killer — a download only ever starts from the
     *  user's Update Now tap, and never twice). */
    private static final java.util.concurrent.atomic.AtomicBoolean downloadInFlight =
        new java.util.concurrent.atomic.AtomicBoolean(false);

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
        pushGateEvent("state", state);
    }

    // ── v3.4.6.d — THE MANDATORY GATE EVENT SURFACE ──────────────────────────
    // The ForceUpdateScreen's data source. MainActivity forwards these onto the
    // anantplay/updater EventChannel; the shell renders the blocking gate the
    // moment a strictly-newer release is known (updateAvailable == true).
    private static volatile GateListener gate;
    interface GateListener {
        void onAvailable(String currentVersion, String newVersion, String apkUrl);
        void onProgress(int percent, long bytes, long total);
        void onVerify();
        void onInstaller();
        void onFailed(String reason);
        void onUpToDate();
    }
    static void setGateListener(GateListener l) { gate = l; }
    static void pushGateEvent(String type, String value) {
        GateListener g = gate;
        if (g == null) return;
        try {
            if ("available".equals(type)) {
                // value carries "current|new" — split and forward.
                String[] parts = value.split("\\|");
                g.onAvailable(parts[0], parts.length > 1 ? parts[1] : parts[0], "");
            } else if ("verify".equals(type)) {
                g.onVerify();
            } else if ("installer".equals(type)) {
                g.onInstaller();
            } else if ("failed".equals(type)) {
                g.onFailed(value);
            } else if ("uptodate".equals(type)) {
                g.onUpToDate();
            }
        } catch (Throwable ignored) { }
    }
    /** v3.4.6.f — LAW #1: the ONLY availability push that may ever exist. The
     *  gate arms IFF `next` is STRICTLY newer than the installed build. */
    private static void gateAvailable(String current, String next) {
        if (!UpdateChecker.isNewer(next, current)) {
            // Same-version or older: the lock is mathematically impossible.
            Diag.i("selfupd", "gate suppressed: " + next + " is not newer than " + current);
            pushGateEvent("uptodate", next);
            return;
        }
        GateListener g = gate;
        if (g != null) { try { g.onAvailable(current, next, ""); } catch (Throwable ignored) { } }
    }
    private static void gateProgress(int percent, long bytes, long total) {
        GateListener g = gate;
        if (g != null) { try { g.onProgress(percent, bytes, total); } catch (Throwable ignored) { } }
    }

    /** v3.4.6.f — the launch/resume queue guard (a second cold-entry while the
     *  first check is still queued is a no-op). */
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
     *  skip-this-version (records the choice, cancels the banners, never alerts
     *  for that version again until a NEWER release appears). */
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

    // ── storage: the one temp dir, the startup sweep, and the STALE-PENDING KILL ─

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

    /** v3.4.6.d — THE ALPHABETICAL VERSION PATTERN (the Obtainium root cause):
     * every pending-metadata gate in v3.4.6.c matched the strict 3-segment
     * "[0-9]+\\.[0-9]+\\.[0-9]+" — so a downloaded+verified 3.4.6.d release
     * made savePendingUpdate() THROW IllegalArgumentException, the metadata
     * was never written, hasPendingUpdate() stayed false and the install
     * never ran (the user had to fall back to Obtainium).
     *
     * v3.4.6.d PERMANENT LAW — the 4th segment is the WHOLE ALPHABET a–z
     * (never a–e): the earlier (\\.[a-e]) ceiling would have broken again on
     * 3.4.6.f, and this gate must never have a regex ceiling. 3.4.6 < 3.4.6.a
     * < … < 3.4.6.d < … < 3.4.6.z < 3.4.7 holds forever. */
    static final String VERSION_PATTERN = "[0-9]+\\.[0-9]+\\.[0-9]+(\\.[a-z])?";

    /** True when v is a valid release version (3 or 4 segments, a–z lane). */
    static boolean validVersion(String v) {
        return v != null && v.matches(VERSION_PATTERN);
    }

    /** Returns only a name-checked file beneath cacheDir/updates.
     * v3.4.6.f — LAW #1: a pending file whose version is NOT strictly newer
     * than the installed build is stale metadata (the v3.4.6.e → v3.4.6.e
     * self-lock) — the metadata AND the file are deleted here, and null is
     * returned: no code path can ever re-arm a same-version gate. */
    private static File pendingFile(Context c) {
        android.content.SharedPreferences sp = c.getSharedPreferences("selfupdate", Context.MODE_PRIVATE);
        String version = sp.getString(PENDING_VERSION, "");
        String hash = sp.getString(PENDING_SHA256, "");
        String name = sp.getString(PENDING_FILE, "");
        if (version == null || !validVersion(version)
                || hash == null || !hash.matches("(?i)[0-9a-f]{64}")
                || !SelfUpdateCore.apkAssetName(version).equals(name)) {
            clearPendingMetadata(c);
            return null;
        }
        if (!UpdateChecker.isNewer(version, AppInfo.VERSION_NAME)) {
            // THE SELF-LOCK KILL: pending <= installed (equal or older) is
            // definitionally not an update. Kill the metadata AND the file.
            Diag.i("selfupd", "stale pending v" + version + " <= installed v"
                + AppInfo.VERSION_NAME + " — deleting pending metadata + cached APK");
            Troubleshooter.record("UPDATER", "stale pending v" + version
                + " <= installed v" + AppInfo.VERSION_NAME + " — swept (self-lock impossible)");
            clearPendingMetadata(c);
            sweepUpdates(c);
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
        if (apk == null || version == null || !validVersion(version)
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

    // ══ v3.4.6.h — PERMANENT LAW #1 GUARANTEE #1: THE REOPEN-PROOF GATE ══════
    //
    // THE REAL-PHONE DEFECT (v3.4.6.g): the user saw Update Now, closed the
    // app, reopened it — and the gate was GONE (and even manual Check for
    // update answered nothing). Root cause: the gate existed only as a
    // volatile in-process event; a reopen starts a NEW process whose
    // cold-start check could be eaten by the persisted 15-minute FAILURE
    // backoff left behind by the offline moments (checkOnLaunch returned
    // BEFORE any network attempt), and the 60 s dedupe ate the manual check
    // that followed. THE FIX: the FIRST moment ANY check sees a strictly
    // newer release, that version is PERSISTED in the selfupdate prefs —
    // and EVERY cold start re-arms the gate from that persisted fact on
    // Frame 0, before any network reply, for as long as it stays strictly
    // newer than the installed build.
    static final String K_REQUIRED_UPDATE_VER = "required_update_version";

    /** Persist `ver` as the mandatory-update floor IFF it is strictly newer
     *  than the installed build (the instant any check discovers it). */
    static void persistRequiredVer(Context c, String ver) {
        if (c == null || ver == null || !validVersion(ver)) return;
        if (!UpdateChecker.isNewer(ver, AppInfo.VERSION_NAME)) return;
        android.content.SharedPreferences sp =
            c.getSharedPreferences("selfupdate", Context.MODE_PRIVATE);
        if (ver.equals(sp.getString(K_REQUIRED_UPDATE_VER, null))) return;
        sp.edit().putString(K_REQUIRED_UPDATE_VER, ver).apply();
        Diag.i("selfupd", "required update persisted: v" + AppInfo.VERSION_NAME
            + " → v" + ver + " (closing/reopening can never bypass it)");
        Troubleshooter.record("UPDATER", "required update persisted v" + ver
            + " — reopen-proof gate armed");
    }

    /**
     *  The persisted required version, verified STRICTLY newer than the
     *  installed build. A stale/equal/older entry (the user already
     *  installed it) is cleared here and "" is answered — the reopen-lock
     *  can never become a v3.4.6.e-style self-lock.
     */
    static String requiredUpdateVer(Context c) {
        if (c == null) return "";
        android.content.SharedPreferences sp =
            c.getSharedPreferences("selfupdate", Context.MODE_PRIVATE);
        String ver = sp.getString(K_REQUIRED_UPDATE_VER, "");
        if (ver == null || ver.isEmpty()) return "";
        if (!validVersion(ver) || !UpdateChecker.isNewer(ver, AppInfo.VERSION_NAME)) {
            sp.edit().remove(K_REQUIRED_UPDATE_VER).apply();
            return "";
        }
        return ver;
    }

    /**
     *  GUARANTEE #1 — re-arm the mandatory gate from the PERSISTED required
     *  version, no network needed: called on every cold start BEFORE the
     *  network check. True when the gate was armed (Frame-0 lock: the user
     *  who closes and reopens the app while an update is owed lands straight
     *  on the ForceUpdateScreen, even offline).
     */
    static boolean armGateFromPersisted(Context c) {
        String ver = requiredUpdateVer(c);
        if (ver.isEmpty()) return false;
        Diag.i("selfupd", "reopening lock: persisted required update v" + ver
            + " > installed v" + AppInfo.VERSION_NAME + " — arming gate on frame 0");
        gateAvailable(AppInfo.VERSION_NAME, ver);
        return true;
    }

    /**
     *  GUARANTEE #5 — THE HONEST GATE STATE (Probe 5's fix): the gate is
     *  armed IFF a strictly-newer release is actually known — a persisted
     *  required version OR a verified strictly-newer pending APK. The
     *  v3.4.6.g probe reported "gate armed (update pending)" from a
     *  non-null volatile blob (an uptodate/failed blob is not an armed
     *  gate); this is the strictly-verified truth.
     */
    static boolean isGateArmed(Context c) {
        if (requiredUpdateVer(c).length() > 0) return true;
        String pending = pendingVersion(c);
        return !pending.isEmpty() && UpdateChecker.isNewer(pending, AppInfo.VERSION_NAME);
    }

    /**
     *  GUARANTEE #2 — THE OFFLINE→ONLINE INSTANT TRIGGER. MainActivity's
     *  ConnectivityManager.NetworkCallback calls this the moment connectivity
     *  returns: the stale failure stamp is cleared, the network generation
     *  bumps (so every dedupe window is invalidated), and ONE single-flight
     *  check fires immediately — the gate pops within ~1 s of Wi-Fi/Mobile
     *  Data coming back, never on a 15-minute delay.
     */
    public static void noteNetworkTransition(Context c) {
        try {
            networkGeneration++;
            Context app = c.getApplicationContext();
            app.getSharedPreferences("selfupdate", Context.MODE_PRIVATE)
                .edit().remove("lastFailedCheck").apply();
            Diag.i("selfupd", "network transition — failure backoff cleared, single-flight check firing");
            Troubleshooter.record("UPDATER", "offline→online transition — instant check (backoff cleared)");
            if (!launchQueued.compareAndSet(false, true)) return;
            checkExecutor.execute(() -> {
                try { checkAndMaybeInstall(app, false, false); }
                finally { launchQueued.set(false); }
            });
        } catch (Throwable t) {
            Diag.w("selfupd", "network transition: " + t);
            launchQueued.set(false);
        }
    }

    /** v3.4.6.f — LAW #1, THE STALE-PENDING SWEEP. Called from App.onCreate()
     *  AND the top of every check entry: pending metadata for a version that
     *  is NOT strictly newer than the installed build (equal, older, or junk
     *  from a completed/cancelled install) is deleted from prefs, and every
     *  cached APK that is not the live pending file is swept from disk. This
     *  is the structural fix for the v3.4.6.e → v3.4.6.e lockout: it can
     *  never again matter whether a previous session's cleanup finished. */
    static long sweepStalePending(Context c) {
        try {
            // The isNewer() check inside pendingFile() performs the kill:
            // equal/older pending → metadata cleared + files swept.
            // v3.4.6.h — the persisted K_REQUIRED_UPDATE_VER carries the same
            // guarantee: requiredUpdateVer() clears it here on every cold
            // start the moment the installed build caught up (or the entry is
            // junk), so a completed install can never leave a ghost gate.
            requiredUpdateVer(c);
            File pending = pendingFile(c);
            long freed = 0;
            if (pending == null) {
                freed = SelfUpdateCore.deleteAll(updatesDir(c));
            } else {
                freed = sweepUpdates(c);
            }
            if (freed > 0) {
                Diag.i("selfupd", "stale-pending sweep freed " + (freed / 1024) + " KiB");
            }
            return freed;
        } catch (Throwable t) {
            Diag.w("selfupd", "stale-pending sweep: " + t);
            return 0;
        }
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

    /** v3.4.6.f — LAW #2: the 60-second in-process dedupe shared by EVERY
     *  entry point (launch, resume, periodic, and the Dart checkNow storm).
     *  Only the explicit download leg (Update Now) bypasses it. Returns true
     *  when a check may run right now. */
    static boolean processDedupeAllows(boolean bypassForDownload) {
        if (bypassForDownload) return true;
        long now = System.currentTimeMillis();
        long last = lastCheckAttemptAt;
        if (last > 0 && now - last < PROCESS_DEDUPE_MS) return false;
        lastCheckAttemptAt = now;
        return true;
    }

    /**
     * v3.4.6.h — GUARANTEE #3: THE ONLY LAUNCH CHECK. EXACTLY ONE
     * lightweight, single-flight version check per cold start, NEVER blocked
     * by a stale 15-minute failure backoff or the 60-second dedupe (both
     * v3.4.6.g traps: a previous offline moment left lastFailedCheck behind
     * and the reopened app's one check was eaten before it started — the
     * "Update Now vanished after reopen" defect). The persisted required
     * version re-arms the gate FIRST (Frame 0, zero network), then the
     * network check refreshes it. ZERO background download, always.
     */
    public static void checkOnLaunch(final Context c) {
        try {
            android.content.Context app = c.getApplicationContext();
            sweepStalePending(app);                     // LAW #1 (self-lock kill)
            // GUARANTEE #1 — the reopen-proof Frame-0 arm, BEFORE any network.
            armGateFromPersisted(app);
            if (pendingFile(app) != null) {
                // A STRICTLY-NEWER verified APK is already cached: re-arm the
                // gate (installed → pending) and wait for the Update Now tap.
                gateAvailable(AppInfo.VERSION_NAME, pendingVersion(app));
                persistRequiredVer(app, pendingVersion(app));
                fire("Update ready — tap Update Now");
                return;
            }
            if (resumeDedupeBlocks()) return;           // only an up-to-date + same-network repeat
            launchCheckedThisProcess = true;
            if (!launchQueued.compareAndSet(false, true)) return;
            noteAttempt();
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

    /** True once a launch check has run in THIS process (warm re-entries cool down). */
    private static volatile boolean launchCheckedThisProcess;

    /** v3.4.6.h — record a network-check attempt (timestamp + generation). */
    private static void noteAttempt() {
        lastCheckAttemptAt = System.currentTimeMillis();
        lastCheckGeneration = networkGeneration;
    }

    /**
     * v3.4.6.h — THE ONLY SURVIVING DEDUPE (GUARANTEE #3): a repeated resume
     * check is skipped ONLY when the previous check in THIS process concluded
     * up-to-date, ran under the SAME network generation, and less than 60 s
     * ago. A failed check, an available gate, a cold start, a manual tap or
     * any offline→online transition ALWAYS re-checks.
     */
    static boolean resumeDedupeBlocks() {
        long last = lastCheckAttemptAt;
        return last > 0
            && "uptodate".equals(lastCheckConclusion)
            && lastCheckGeneration == networkGeneration
            && System.currentTimeMillis() - last < PROCESS_DEDUPE_MS;
    }

    /** v3.4.6.d — the historical warm-resume cadence (kept for the lab's
     *  contract table; v3.4.6.h no longer gates resume checks on it — the
     *  narrow up-to-date + same-network + 60 s dedupe above is the only
     *  throttle, so a phone that comes back online re-checks immediately). */
    static final long RESUME_RECHECK_MS = 5 * 60 * 1000L;

    /**
     * WARM RESUME: called from MainActivity.onResume() on EVERY foreground
     * return (including the return from the Unknown-Sources settings screen
     * and from the installer sheet). A strictly-newer pending APK or the
     * persisted required version re-arms the gate WITHOUT any download;
     * otherwise exactly one deduped network check. Fully guarded: a resume
     * path must never be able to crash the activity.
     */
    public static void checkOnResume(Context c) {
        try {
            android.content.Context app = c.getApplicationContext();
            sweepStalePending(app);                     // LAW #1 (self-lock kill)
            // GUARANTEE #1 — the persisted arm first (a resume after the
            // system killed the process mid-session re-locks instantly).
            armGateFromPersisted(app);
            if (pendingFile(app) != null) {
                launchCheckedThisProcess = true;
                gateAvailable(AppInfo.VERSION_NAME, pendingVersion(app));
                persistRequiredVer(app, pendingVersion(app));
                return;
            }
            if (resumeDedupeBlocks()) return;           // the ONE narrow dedupe
            if (!launchQueued.compareAndSet(false, true)) return;
            noteAttempt();
            checkExecutor.execute(() -> {
                try { checkAndMaybeInstall(app, false, false); }
                finally { launchQueued.set(false); }
            });
        } catch (Throwable t) {
            Diag.w("selfupd", "resume check: " + t);
        }
    }

    /**
     * Manual checks bypass every persisted cooldown (the user asked) and use
     * ONLY a tiny 3-second anti-double-tap debounce — GUARANTEE #3: the
     * v3.4.6.g 60-second dedupe ate this check right after a launch attempt
     * and the user stared at "Checked just now" with a real update waiting.
     * A manual check NEVER downloads (LAW #2).
     */
    public static void checkNow(Context c) {
        Context app = c.getApplicationContext();
        long now = System.currentTimeMillis();
        long last = lastManualTapAt;
        if (last > 0 && now - last < MANUAL_TAP_DEBOUNCE_MS) {
            fire("Checking…");
            return;
        }
        lastManualTapAt = now;
        // GUARANTEE #1 — even a manual tap re-arms from the persisted floor.
        armGateFromPersisted(app);
        fire("Checking…");
        if (!launchQueued.compareAndSet(false, true)) return;
        noteAttempt();
        checkExecutor.execute(() -> {
            try { checkAndMaybeInstall(app, true, false); }
            finally { launchQueued.set(false); }
        });
    }

    /** A deliberate Update Now action (the user tapped the gate's ONLY button):
     *  this is the ONE path allowed to download — permission first (LAW #3),
     *  single-flight download, SHA-256 verify, installer handoff. */
    public static void updateNow(Context c) {
        Context app = c.getApplicationContext();
        fire("Checking…");
        checkExecutor.execute(() -> checkAndMaybeInstall(app, true, true));
    }

    /** User-tap install entry: permission settings open only after this action. */
    static String installPendingUpdate(Context c, boolean promptIfNeeded) {
        // v3.4.6.k — a committed/pending session swallows EVERY re-entry (the
        // Update-gate's Update Now, onResume, the 90 s watcher): ONE system
        // installer sheet, ever.
        if (installInFlight()) {
            Diag.i("selfupd", "install already committed — waiting for its result (no re-commit)");
            return "install already in progress";
        }
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
                // v3.4.6.i — the FORCE-UPDATE GATE'S STEP-1 ALSO CARRIES THE
                // XRECORDER-STYLE GUIDE CARD: the unknown-sources settings page
                // opens with the translucent animated 'Allow from this source'
                // toggle mockup floating on top (video_1). The stacked launch
                // degrades to the plain page on any OEM refusal — the watcher
                // (below) is armed either way.
                PermissionGuideActivity.openUnknownSourcesWithGuide(c);
                // v3.4.6.f — LAW #3 Step 1: the bounded 30 s watcher that resumes
                // the install THE INSTANT the grant lands (see watchInstallPermission).
                watchInstallPermission(c);
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
            fire("Could not start installer — tap Update Now to retry");
            return "could not queue installer";
        }
        return "installing " + version;
    }

    /** v3.4.6.j — LAW #3 Step 1: the bounded 90-second install-permission
     *  watcher. Started the moment the Unknown-Sources settings page opens
     *  from the Update Now flow; polls canRequestPackageInstalls() every
     *  400 ms off the main thread for at most 90 s (225 ticks — the whole
     *  HyperOS Danger-warning countdown fits with margin), and the INSTANT
     *  the grant lands it flips PENDING_AWAITING_PERMISSION off, snaps the
     *  app back to the front through the BAL-allowed lane (the ESET-style
     *  instant return), and resumes the install — no manual re-tap, no dead
     *  waiting. Idempotent: a second start while one watcher is live is a
     *  no-op. */
    private static volatile boolean installWatcherActive;

    static void watchInstallPermission(Context c) {
        if (installWatcherActive) return;
        installWatcherActive = true;
        final Context app = c.getApplicationContext();
        final long deadline = System.currentTimeMillis() + 90_000L;
        new Thread(() -> {
            try {
                while (System.currentTimeMillis() < deadline) {
                    // v3.4.6.k — THE NON-DISRUPTIVE LAW: never snap the app
                    // forward (nor resume the installer) while a full
                    // troubleshoot run is live.
                    if (DeepProbe.probeRunActive()) break;
                    if (app.getSharedPreferences("selfupdate", Context.MODE_PRIVATE)
                            .getBoolean(PENDING_AWAITING_PERMISSION, false)
                            && canRequestInstalls(app)) {
                        Diag.i("selfupd", "install permission granted inside the 90 s watch — resuming install");
                        // v3.4.6.j — the ESET-style instant return: the app
                        // snaps back to the front through the BAL-allowed
                        // PendingIntent lane the moment the grant lands.
                        MainActivity.shellLaunchBal(app, "install-permission-granted");
                        resumePendingInstall(app);
                        break;
                    }
                    Thread.sleep(400);
                }
            } catch (Throwable t) {
                Diag.w("selfupd", "install watcher: " + t);
            } finally {
                installWatcherActive = false;
            }
        }, "si-install-watch").start();
    }

    /** Called on Activity resume after the user returns from install permission settings.
     *  v3.4.6.k — THE 2–3× INSTALLER LOOP KILL: NEVER re-invoke while an
     *  install session is already committed / pending user action (the OS
     *  sheet is up) or inside the 15 s post-commit cooldown — onResume()
     *  stacking a second and third system installer sheet over Android Home
     *  was exactly the XRecorder_20261127_01 01:38–02:02 defect. */
    public static void resumePendingInstall(Context c) {
        try {
            if (installInFlight()) {
                Diag.i("selfupd", "resume skipped — install session already committed/pending (single-flight lock)");
                return;
            }
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
            // v3.4.6.i — THE ZERO-DOUBLE-DOWNLOAD LAW: the verified APK and its
            // pending metadata SURVIVE the PackageInstaller handoff. The
            // v3.4.6.h build deleted the file + metadata the moment the session
            // was committed — so when the user dismissed the Android confirm
            // sheet and tapped Update Now again, hasPendingUpdate() was false,
            // the cache-reuse branch in downloadVerifyInstall could never hit,
            // and the ENTIRE APK re-downloaded from 0% (the 42.8 MB × 2 caught
            // on video_5). Now: dismiss the sheet → the next Update Now re-opens
            // the installer from the SAME cached verified APK in < 50 ms.
            // The cache is swept only AFTER the install actually SUCCEEDS —
            // the NEW version's App.onCreate runs sweepStalePending(), sees
            // the pending version is no longer newer than the installed one,
            // clears the metadata and deletes the file ( LAW #1's self-lock
            // kill, unchanged).
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
            fire("Install failed — tap Update Now to retry");
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
     *  reschedule → re-fire at the ~250 ms cadence of the field log).
     *  v3.4.6.k — LAW #1 ZERO-DISABLE-SWITCH: the periodic job ALWAYS runs
     *  (there is NO Auto-update OFF switch anywhere in Settings, and the old
     *  K_AUTO_UPDATE mute is deleted — a silent-letter-patch self-updater
     *  that a preference could switch off would break Law #1's contract). */
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

    // ── the flow: check (LAW #2) + the user-tap download leg (LAW #3) ─────────

    /** One update attempt (automatic cycle). */
    public static String checkAndMaybeInstall(final Context c) {
        return checkAndMaybeInstall(c, false);
    }

    /** One update attempt. `manualCheck` bypasses the persisted cooldowns;
     *  `forceDownload` is the USER'S Update Now tap — the ONLY path that may
     *  ever download an APK (LAW #2: zero background auto-download). */
    public static String checkAndMaybeInstall(final Context c, final boolean manualCheck) {
        return checkAndMaybeInstall(c, manualCheck, false);
    }

    static String checkAndMaybeInstall(final Context c, final boolean manualCheck, final boolean forceDownload) {
        if (!checkInFlight.compareAndSet(false, true)) {
            // v3.1.2 — the rate-limited skip: at most ONE skip line per second.
            long now = System.currentTimeMillis();
            long last = lastSkipLogAt;
            if (last == 0 || now - last >= 1000L) {
                lastSkipLogAt = now;
                Diag.i("selfupd", "single-flight: another update run is active — skipping");
            }
            return "in-flight";
        }
        final android.content.SharedPreferences sp =
            c.getSharedPreferences("selfupdate", Context.MODE_PRIVATE);
        try {
            sweepStalePending(c);                        // LAW #1 (self-lock kill)
            // A verified STRICTLY-NEWER pending package is never re-downloaded
            // or overwritten; it re-arms the gate and waits for the tap.
            if (pendingFile(c) != null) {
                String pVer = pendingVersion(c);
                // v3.4.6.k — LAW #1 TWO-LANE: a cached letter-suffix patch APK
                // commits SILENTLY on the launch/reconnect check (download once
                // already happened; zero prompt, glass pill only). Everything
                // else re-arms the ForceUpdateScreen gate as before.
                if (UpdateChecker.isLetterPatch(pVer, AppInfo.VERSION_NAME)
                        && canRequestInstalls(c) && !installInFlight()) {
                    Diag.i("selfupd", "silent patch lane: cached v" + pVer + " → zero-prompt commit");
                    Troubleshooter.record("UPDATER", "silent letter-patch lane: cached v" + pVer
                        + " → USER_ACTION_NOT_REQUIRED commit (no gate, no tap)");
                    fire("Updating to v" + pVer + "…");
                    installPendingUpdate(c, true);
                    lastCheckConclusion = "available";
                    return "installing " + pVer;
                }
                gateAvailable(AppInfo.VERSION_NAME, pVer);
                persistRequiredVer(c, pVer);
                lastCheckConclusion = "available";
                if (forceDownload) {
                    // The user tapped Update Now with a verified APK in cache:
                    // go straight to the permission + installer leg (no download).
                    installPendingUpdate(c, true);
                    return "installing " + pVer;
                }
                fire("Update ready — tap Update Now");
                return "ready";
            }
            // (the outer `sp` is the single prefs handle for this run)
            if (!manualCheck) {
                long now = System.currentTimeMillis();
                // v3.4.6.h — TRAP #2 TYPE-SAFETY: the throttle stamps are read
                // through safeGetLong() so a stray Integer in either slot can
                // never throw ClassCastException out of the check entry point.
                if (!checkAllowed(now, AppPrefs.safeGetLong(sp, "lastCheck", 0),
                        AppPrefs.safeGetLong(sp, "lastFailedCheck", 0), false)) {
                    return "backoff";
                }
                sp.edit().putLong("lastCheck", now).apply();       // persisted BEFORE the network call
            }
            fire("Checking…");
            // v3.4.6.h — GUARANTEE #4 (SUB-SECOND DETECTION): the CHECK leg
            // resolves the VERSION first (tier-1 302, 4 s hard wall-clock →
            // tier-2 latest.json, 4 s) and arms + PERSISTS the gate the
            // moment a strictly-newer version is known — the SHA256SUMS leg
            // only runs in the download path (the user's Update Now tap),
            // so the ForceUpdateScreen appears as fast as the redirect
            // itself, never behind a second network round trip.
            String fastVersion = SelfUpdateCore.fetchVersionFast();
            if (fastVersion != null && UpdateChecker.isNewer(fastVersion, AppInfo.VERSION_NAME)
                    && !forceDownload) {
                // v3.4.6.k — a letter-suffix patch does NOT arm the gate: it
                // falls through to the release resolve + the SILENT lane below
                // (zero ForceUpdateScreen for 3.4.6.k → .l → .m patches).
                if (!UpdateChecker.isLetterPatch(fastVersion, AppInfo.VERSION_NAME)) {
                    persistRequiredVer(c, fastVersion);
                    gateAvailable(AppInfo.VERSION_NAME, fastVersion);
                    fire("Update available: v" + AppInfo.VERSION_NAME + " → v" + fastVersion
                        + " — tap Update Now");
                    Diag.i("selfupd", "fast lane: v" + fastVersion + " is newer — gate armed + persisted (" +
                        "SHA resolves on the Update Now tap)");
                    Troubleshooter.record("UPDATER", "gate armed v" + AppInfo.VERSION_NAME
                        + " → v" + fastVersion + " (fast lane; background download suppressed)");
                    lastCheckConclusion = "available";
                    return "available " + fastVersion;
                }
                Diag.i("selfupd", "fast lane: v" + fastVersion
                    + " is a letter patch — resolving the release for the silent lane");
            }
            SelfUpdateCore.Release rel = SelfUpdateCore.fetchReleaseAny();
            if (rel == null) {
                // Persist the failure so the PERIODIC job alone can throttle
                // (v3.4.6.h: launch/resume/manual/online-transition checks no
                // longer read this stamp — an offline→online transition clears
                // it and re-checks within ~1 s).
                sp.edit().putLong("lastFailedCheck", System.currentTimeMillis()).apply();
                Diag.w("selfupd", "check failed on both HTTPS routes — periodic job backs off");
                Troubleshooter.record("UPDATER", "check: no release info (both routes failed)");
                fire("Update check failed — will retry when you're back online");
                lastCheckConclusion = "failed";
                return "no release";
            }
            Diag.i("selfupd", "release resolved via tier: " + rel.tier + " → v" + rel.version);
            Troubleshooter.record("UPDATER", "release v" + rel.version + " resolved via " + rel.tier
                + " (GitHub-REST calls: 0)");
            if (!UpdateChecker.isNewer(rel.version, AppInfo.VERSION_NAME)) {
                Diag.i("selfupd", "up to date (" + AppInfo.VERSION_NAME + "; latest " + rel.version + ")");
                Troubleshooter.record("UPDATER", "check: up-to-date (" + AppInfo.VERSION_NAME + "; latest " + rel.version + ")");
                fire("Up to date (v" + AppInfo.VERSION_NAME + ")");
                pushGateEvent("uptodate", rel.version);            // the ONLY unlock
                // v3.4.6.h — the persisted required floor clears with the same
                // verdict: an installed update (or a yanked release) leaves NO
                // ghost reopen-lock behind.
                sp.edit().remove(K_REQUIRED_UPDATE_VER).apply();
                lastCheckConclusion = "uptodate";
                return "up-to-date";
            }
            // v3.4.6.f — LAW #1 + LAW #2: a STRICTLY newer release is known.
            // v3.4.6.k — TWO-LANE VERSIONING (LAW #1):
            //   · letter-suffix patch (same major.minor.patch base) → the
            //     SILENT zero-prompt lane: download ONCE + SHA-256 verify +
            //     USER_ACTION_NOT_REQUIRED commit, a top Mornye glass pill
            //     "Updating to vX…" only (ForceUpdateScreen appears ONLY if
            //     the OS returns STATUS_PENDING_USER_ACTION or the install
            //     permission is missing);
            //   · major/minor (3.5.0, 3.6.0…) → the full-screen
            //     non-dismissible ForceUpdateScreen (PopScope(canPop: false)),
            //     committing with USER_ACTION_NOT_REQUIRED on the tap.
            persistRequiredVer(c, rel.version);
            gateAvailable(AppInfo.VERSION_NAME, rel.version);

            if (!forceDownload) {
                if (UpdateChecker.isLetterPatch(rel.version, AppInfo.VERSION_NAME)
                        && canRequestInstalls(c)) {
                    fire("Updating to v" + rel.version + "…");
                    Diag.i("selfupd", "letter patch " + rel.version
                        + " — SILENT lane: download once + verify + zero-prompt commit");
                    Troubleshooter.record("UPDATER", "silent letter-patch lane v" + rel.version
                        + " — download once + SHA-256 + USER_ACTION_NOT_REQUIRED commit");
                    String r = downloadVerifyInstall(c, rel);
                    lastCheckConclusion = "available";
                    return r;
                }
                Diag.i("selfupd", "new release " + rel.version + " — gate armed, WAITING for the user's Update Now tap (major/minor lane)");
                Troubleshooter.record("UPDATER", "gate armed v" + AppInfo.VERSION_NAME
                    + " → v" + rel.version + " — background download suppressed (waits for Update Now)");
                fire("Update available: v" + AppInfo.VERSION_NAME + " → v" + rel.version
                    + " — tap Update Now");
                lastCheckConclusion = "available";
                return "available " + rel.version;
            }

            // ── LAW #3 — THE USER TAPPED UPDATE NOW (the only download leg) ──
            // Step 1 — the install-permission guarantee comes FIRST.
            if (!canRequestInstalls(c)) {
                // openPermissionAndAwait() arms the 30 s watcher; Step 2
                // auto-resumes the instant the grant lands (onResume /
                // watchInstallPermission), and re-drives the download leg.
                String lane = openPermissionAndAwait(c, rel);
                return lane;
            }

            return downloadVerifyInstall(c, rel);
        } catch (Throwable t) {
            // v3.2.0 — a thrown failure stamps the PERIODIC job's backoff
            try {
                sp.edit().putLong("lastFailedCheck", System.currentTimeMillis()).apply();
            } catch (Throwable ignored) { }
            Diag.w("selfupd", "check: " + t);
            pushGateEvent("failed", Chrome.pick(
                "බාගැනීම අසාර්ථක — අන්තර්ජාල සම්බන්ධතාව පරීක්ෂා කර Retry Update ඔබන්න · download failed — check your connection and tap Retry Update"));
            fire(Chrome.pick("යාවත්කාලීන පරීක්ෂාව අසාර්ථක — මිනිත්තුවකින් නැවත · update check failed — retrying within 15 min"));
            Troubleshooter.record("UPDATER", "check threw: " + t.getClass().getSimpleName() + " — 15-min backoff engaged");
            return "offline";
        } finally {
            checkInFlight.set(false);
        }
    }

    /** v3.4.6.f — LAW #3 Step 1: the permission guarantee. Marks the pending
     * flag, opens Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, starts the
     * bounded 30 s watcher; the grant auto-resumes the WHOLE download →
     * verify → install pipeline (the release is re-resolved; the cached
     * file is reused below when its SHA still matches). */
    private static String openPermissionAndAwait(Context c, SelfUpdateCore.Release rel) {
        try {
            c.getSharedPreferences("selfupdate", Context.MODE_PRIVATE).edit()
                .putBoolean(PENDING_AWAITING_PERMISSION, true).apply();
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + c.getPackageName()));
            if (!(c instanceof Activity)) settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(settings);
            watchInstallPermission(c);                    // the bounded 30 s auto-resume
            pushGateEvent("failed",
                "Step 1 of 2: allow AnantPlay Player to install updates (just opened) — then the download starts automatically.");
            Diag.i("selfupd", "install permission requested first — download resumes on grant");
            return "awaiting install permission";
        } catch (Throwable t) {
            Diag.w("selfupd", "open install permission: " + t);
            pushGateEvent("failed", "Could not open the install-permission settings — tap Retry Update.");
            return "permission settings unavailable";
        }
    }

    /** v3.4.6.f — LAW #3 Steps 2 + 3 (the ONLY download path): single-flight
     *  download guarded by `downloadInFlight`, reuse of a cached APK whose
     *  SHA-256 already matches, strict verification, and the installer
     *  handoff. Called exclusively from the user's Update Now tap. */
    private static String downloadVerifyInstall(Context c, SelfUpdateCore.Release rel) {
        if (!downloadInFlight.compareAndSet(false, true)) {
            Diag.i("selfupd", "download single-flight: a download is already running — skipping duplicate");
            return "download in-flight";
        }
        try {
            // the pre-download gates: https-only, declared size within the cap
            if (!SelfUpdateCore.httpsOnly(rel.apkUrl)) {
                Diag.e("selfupd", "release apk URL is not https — refusing");
                pushGateEvent("failed", "The release URL is not https — refused for safety. Tap Retry Update.");
                return "not-https";
            }
            if (rel.apkSize > SelfUpdateCore.MAX_APK_BYTES) {
                Diag.e("selfupd", "release apk is " + rel.apkSize + " B — over the 55 MB cap, refusing");
                pushGateEvent("failed", "The release APK is over the 55 MB cap — refused. Tap Retry Update.");
                return "too-large";
            }
            if (rel.sha256Expected == null || !rel.sha256Expected.matches("(?i)[0-9a-f]{64}")) {
                // a release without a SHA256SUMS line for the apk: refuse (security)
                Diag.w("selfupd", "no SHA-256 line for " + SelfUpdateCore.apkFileName(rel.apkUrl) + " — refusing");
                App.notifyUpdateFailed(c, rel.version);
                pushGateEvent("failed", "The release has no valid SHA-256 signature — refused for safety. Tap Retry Update.");
                fire("Update refused: no valid SHA-256");
                return "no-sha";
            }
            final File apk = new File(updatesDir(c), SelfUpdateCore.apkFileName(rel.apkUrl));

            // ── Step 2, the CACHE reuse: never download twice ──
            if (apk.isFile() && apk.length() > 0
                    && SelfUpdateCore.matchesSha256(apk, rel.sha256Expected)) {
                Diag.i("selfupd", "cached APK already matches SHA-256 — skipping the download entirely");
                savePendingUpdate(c, apk, rel.version, rel.sha256Expected.toLowerCase(java.util.Locale.ROOT));
                pushGateEvent("installer", rel.version);
                App.notifyUpdateReady(c, rel.version);
                fire("Update ready: v" + rel.version + " — 1-tap install");
                installPendingUpdate(c, false);
                return "installing " + rel.version;
            }

            Diag.i("selfupd", "Update Now — downloading v" + rel.version + " (single-flight, live progress)");
            fire("Downloading v" + rel.version + " — 0%");
            sweepUpdates(c);   // never two temp files at once
            UiListener initialProgress = ui;
            if (initialProgress != null) {
                try { initialProgress.onDownloadProgress(0); } catch (Throwable ignored) { }
            }
            App.notifyUpdateProgress(c, rel.version, 0);
            gateProgress(0, 0, -1);
            SelfUpdateCore.downloadToFile(rel.apkUrl, apk, (bytes, total, percent) -> {
                if (throttle.onProgress(percent)) {
                    App.notifyUpdateProgress(c, rel.version, percent);
                }
                gateProgress(percent, bytes, total);
                UiListener ul = ui;
                if (ul != null) { try { ul.onDownloadProgress(percent); } catch (Throwable ignored) { } }
            });

            // ── Step 3 — verify the strict SHA-256 against SHA256SUMS.txt ──
            pushGateEvent("verify", rel.version);
            String sha = SelfUpdateCore.sha256(apk);
            if (!rel.sha256Expected.equalsIgnoreCase(sha)) {
                Diag.e("selfupd", "SHA-256 MISMATCH — refusing to install (downloaded " + sha
                    + ", expected " + rel.sha256Expected + ")");
                apk.delete();
                App.notifyUpdateFailed(c, rel.version);
                pushGateEvent("failed", "SHA-256 verification failed — the download was corrupted. Tap Retry Update.");
                fire(Chrome.pick("බාගැනීම අසාර්ථක (SHA-256 නොගැලපේ) · download failed verification"));
                return "sha-mismatch";
            }
            Diag.i("selfupd", "sha256 verified (" + sha + ") — saving verified update for installer handoff");
            savePendingUpdate(c, apk, rel.version, sha);
            App.notifyUpdateReady(c, rel.version);
            pushGateEvent("installer", rel.version);
            fire("Update ready: v" + rel.version + " — 1-tap install");
            if (canRequestInstalls(c)) {
                installPendingUpdate(c, false);
                return "installing " + rel.version;
            }
            // Permission is requested only after the user taps Update Now.
            return "ready " + rel.version;
        } catch (Throwable t) {
            Diag.w("selfupd", "download: " + t);
            pushGateEvent("failed", Chrome.pick(
                "බාගැනීම අසාර්ථක — අන්තර්ජාල සම්බන්ධතාව පරීක්ෂා කර Retry Update ඔබන්න · download failed — check your connection and tap Retry Update"));
            return "download-failed";
        } finally {
            downloadInFlight.set(false);                  // exactly one download at a time, forever
        }
    }

    // ── the install (Android side) ─────────────────────────────────────────────

    // ══ v3.4.6.k — THE SINGLE-FLIGHT INSTALL STATE (LAW #1, the 2–3× installer
    // loop kill, XRecorder_20261127_01 01:38–02:02) ═════════════════════════
    // The v3.4.6.j lane let THREE commits stack: the 90 s install watcher
    // fired resumePendingInstall() → commit #1, MainActivity.onResume() fired
    // resumePendingInstall() again → commit #2, and the persisted gate's
    // Update Now re-drove installPendingUpdate() → commit #3 — each commit
    // popped ANOTHER system installer sheet floating over Android Home. A
    // strict single-flight state now guards commit(): one AtomicBoolean in
    // flight, the live session id, and a 15 s cooldown after every commit.
    private static final java.util.concurrent.atomic.AtomicBoolean installCommitInFlight =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile int activeInstallSessionId = -1;
    private static volatile long lastCommitAtMs;
    /** The installer-sheet cooldown: a second commit inside this window is dropped. */
    static final long INSTALL_COMMIT_COOLDOWN_MS = 15_000L;

    /** True while a committed session is still pending its final status. */
    static boolean installInFlight() {
        return installCommitInFlight.get()
            || (activeInstallSessionId >= 0 && System.currentTimeMillis() - lastCommitAtMs < INSTALL_COMMIT_COOLDOWN_MS);
    }

    /** v3.4.6.k — release the single-flight lock (success / terminal failure). */
    static void installSettled() {
        activeInstallSessionId = -1;
        installCommitInFlight.set(false);
    }

    /** Stream the verified APK FILE into a PackageInstaller.Session.
     *  v3.4.6.k — LAW #1 SILENT ZERO-PROMPT: on API 31+ the session carries
     *  SessionParams.USER_ACTION_NOT_REQUIRED, so a same-signature update
     *  installs with ZERO system installer sheet (Android 12–16, targetSdk 36
     *  ≥ 34). Single-flight + 15 s cooldown: the system installer can NEVER be
     *  popped 2–3 times or float over the Home screen. Same-signature updates
     *  install in place: no uninstall, all data preserved. */
    static void install(Context c, File apk) throws Exception {
        // ── the single-flight + cooldown gate (every caller funnels here) ──
        long now = System.currentTimeMillis();
        if (installCommitInFlight.get()) {
            Diag.w("selfupd", "install: another session is already committed — dropping this duplicate commit");
            return;
        }
        if (activeInstallSessionId >= 0 && now - lastCommitAtMs < INSTALL_COMMIT_COOLDOWN_MS) {
            Diag.w("selfupd", "install: session " + activeInstallSessionId
                + " committed " + (now - lastCommitAtMs) + " ms ago — inside the "
                + INSTALL_COMMIT_COOLDOWN_MS + " ms cooldown, dropping the duplicate");
            return;
        }
        installCommitInFlight.set(true);
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.Session session = null;
        int sid = -1;
        try {
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            // ══ v3.4.6.k — THE SILENT ZERO-PROMPT CONTRACT (Android 12–16) ══
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                try {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                    Diag.i("selfupd", "silent install lane armed: USER_ACTION_NOT_REQUIRED (API "
                        + android.os.Build.VERSION.SDK_INT + ")");
                } catch (Throwable noSilent) {
                    Diag.w("selfupd", "USER_ACTION_NOT_REQUIRED refused (" + noSilent + ") — prompt lane");
                }
            }
            sid = pi.createSession(params);
            activeInstallSessionId = sid;
            lastCommitAtMs = System.currentTimeMillis();
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
            // v3.4.6.k — the pending-resume flags clear THE INSTANT the commit
            // fires: neither onResume() nor the 90 s watcher may re-commit this
            // session (the 2–3× installer loop's second and third legs).
            try {
                c.getSharedPreferences("selfupdate", Context.MODE_PRIVATE).edit()
                    .putBoolean(PENDING_AWAITING_PERMISSION, false).apply();
            } catch (Throwable ignored) { }
            Diag.i("selfupd", "install session " + sid + " committed (" + apk.length()
                + " B, USER_ACTION_NOT_REQUIRED=" + (android.os.Build.VERSION.SDK_INT >= 31) + ")");
        } catch (Throwable t) {
            if (sid >= 0 && activeInstallSessionId == sid) activeInstallSessionId = -1;
            installCommitInFlight.set(false);
            if (session != null) try { session.abandon(); } catch (Throwable ignored) { }
            throw t;
        }
    }

    /** Receives the install result broadcast. v3.4.6.k — the silent lane
     *  normally resolves here with STATUS_SUCCESS (no sheet at all on
     *  Android 12–16). STATUS_PENDING_USER_ACTION is now the FALLBACK: the
     *  OEM refused USER_ACTION_NOT_REQUIRED (or install permission is
     *  missing), so exactly ONE confirm sheet may be forwarded — never two
     *  stacked (the single-flight lock stays held until a terminal status). */
    public static final class InstallResultReceiver extends android.content.BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            try {
                int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    // v3.4.6.k — the OS refused the silent lane: exactly ONE
                    // confirm sheet. The lock stays HELD (a second PENDING
                    // broadcast for this session must not stack another sheet).
                    Diag.i("selfupd", "OS forced the confirm sheet (silent lane refused) — forwarding it once");
                    Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            c.startActivity(confirm);   // the single 1-tap "Update" dialog
                            Diag.i("selfupd", "user-action prompt shown (fallback lane)");
                        } catch (Throwable bal) {
                            Diag.w("selfupd", "BAL refused the prompt (" + bal + ") — posting the tap-to-install notification");
                            postConfirmNotification(c, confirm);
                        }
                    }
                } else if (status == PackageInstaller.STATUS_SUCCESS) {
                    // v3.4.6.k — release the single-flight lock FIRST: no
                    // onResume()/watcher re-commit can ever float a second
                    // installer sheet over Home after a successful install.
                    installSettled();
                    Diag.i("selfupd", "installed ✓ — sweeping the verified pending cache");
                    // v3.4.6.i — the install SUCCEEDED: the verified APK cache
                    // and its metadata are swept NOW (the belt-and-braces path;
                    // the NEW process's App.onCreate → sweepStalePending() does
                    // the identical sweep when the old process was already gone).
                    try {
                        clearPendingMetadata(c);
                        SelfUpdateCore.deleteAll(updatesDir(c));
                    } catch (Throwable sweep) {
                        Diag.w("selfupd", "post-success sweep: " + sweep);
                    }
                    App.notifyUpdateDone(c);
                } else {
                    // every other terminal status releases the lock too
                    installSettled();
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

    /** The JobScheduler entry: check ONLY (zero download — LAW #2).
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

    /** v3.4.6.f — test seam: reset the in-process dedupe clock (the lab only;
     *  never shipped into a release build path). */
    static void resetProcessDedupeForTests() {
        lastCheckAttemptAt = 0;
    }
}
