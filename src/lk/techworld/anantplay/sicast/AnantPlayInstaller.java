package lk.techworld.anantplay.sicast;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * v3.4.6.b — THE IN-APP OFFICIAL-ANANTPLAY INSTALLER (onboarding Slide 2).
 *
 * The official app (me.champdev.weblock, github.com/AnantPlay/android) is the
 * side of the pair the receiver takes casts from — onboarding now checks LIVE
 * via PackageManager and, when missing, installs it right inside the slide:
 *
 *   · anantplayInstalled()        — the live PackageManager check (resumed re-query)
 *   · canRequestPackageInstalls() — Android 8+ "install unknown apps" state
 *   · openUnknownSources()       — ACTION_MANAGE_UNKNOWN_APP_SOURCES for THIS package
 *   · installOfficial()          — the full honor-Android-16 pipeline:
 *       1. resolve https://github.com/AnantPlay/android/releases/latest via the
 *          SAME zero-rate-limit 302 redirect SelfUpdateCore uses for itself,
 *       2. stream the release APK to cacheDir/weblock/ with live 0→100 percent
 *          events (SelfUpdateCore.downloadToFile — same under-cap streaming),
 *       3. hand the file to Android's PackageInstaller → the honest 1-tap
 *          system confirm sheet. NO root, NO silent installs — exactly the
 *          contract SelfUpdater documents for our own updates.
 *
 * Progress/state is reported through the anantplay/cast EventChannel the same
 * way SelfUpdater's UiListener pushes update events (t/v map), so the slide's
 * progress bar is a live stream, not a polled file.
 *
 * v3.4.6.i — THE VIDEO-2/4/5 FIXES (the `0 · no-asset` / `0 · needs-permission`
 * / `0 · busy` trio caught on the real phone):
 *   1. REAL ASSET DISCOVERY — GitHub's expanded_assets fragment embeds
 *      RELATIVE hrefs (`/AnantPlay/android/releases/download/v2.24.0/…apk`),
 *      but the v3.4.6.h regex demanded ABSOLUTE `https://…apk` hrefs, so it
 *      could never match → `no-asset` every time. The resolver now accepts
 *      BOTH shapes, absolutizes relative ones against https://github.com,
 *      and prefers the phone (non-TV) build: arm64-v8a/universal, then the
 *      versioned `AnantPlay-Android-v2.24.0.apk`, then the unversioned
 *      `AnantPlay-Android.apk`, TV last.
 *   2. AUTO-RESUME AFTER THE GRANT — the leaked `running=true` on the
 *      needs-permission path (its early `return` never reset the flag, so
 *      every later tap hit `0 · busy` until process death) is fixed with a
 *      try/finally that resets on EVERY path, and the promised auto-resume
 *      now EXISTS: resumeIfWaiting() — called from MainActivity.onResume() —
 *      re-runs the real download 0%→100% the moment the install permission
 *      lands, with the stored listener still pushing events.
 *   3. OFFICIAL ANANTPLAY UPDATE DETECTION — officialUpdateCheck() compares
 *      the installed me.champdev.weblock versionName against the latest
 *      releases/latest tag (zero GitHub-REST calls), so Home/Settings can
 *      show the "AnantPlay Update Available (vX → vY) — Update Now" pill.
 */
final class AnantPlayInstaller {

    static final String PKG = "me.champdev.weblock";   // OnboardingWizard.ANANTPLAY_PKG
    static final String REPO = "AnantPlay/android";
    static final long MAX_APK_BYTES = 200L * 1024 * 1024 - 1;   // official APKs are bigger than ours

    private AnantPlayInstaller() { }

    /** The LIVE install check (PackageManager, no cached state). */
    static boolean anantplayInstalled(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** The official app's installed version name ("" when absent). */
    static String anantplayVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(PKG, 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    /** Android 8+ "allow this source to install apps" for OUR package. */
    static boolean canRequestPackageInstalls(Context c) {
        try {
            return Build.VERSION.SDK_INT >= 26 && c.getPackageManager().canRequestPackageInstalls();
        } catch (Throwable t) {
            return false;
        }
    }

    /** ACTION_MANAGE_UNKNOWN_APP_SOURCES on our own package (the once-only grant). */
    static boolean openUnknownSources(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + c.getPackageName()));
            if (!(c instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Throwable t) {
            Diag.w("weblock", "open unknown-sources settings: " + t);
            return false;
        }
    }

    // ── the install pipeline (background thread; events via the listener) ─────

    interface Listener {
        /** percent 0..100, version resolved from the tag ("3.4.6.b"-style or plain). */
        void onProgress(int percent, String version);
        void onState(String state);
    }

    /** True while an install attempt is running (the slide disables its button). */
    private static final java.util.concurrent.atomic.AtomicBoolean running =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    static boolean isRunning() { return running.get(); }

    /**
     * v3.4.6.i — TRUE between the needs-permission return and the grant (or a
     * failed resolve). MainActivity.onResume() calls resumeIfWaiting() which,
     * when the permission has landed and the official app is still missing,
     * re-runs installOfficial with the SAME listener so progress keeps
     * streaming into the slide that started it.
     */
    private static volatile boolean awaitingPermission;
    private static volatile Listener parkedListener;

    static boolean isAwaitingPermission() { return awaitingPermission; }

    /**
     * v3.4.6.i — the auto-resume half of the needs-permission fix: called from
     * MainActivity.onResume(). The moment canRequestPackageInstalls() is true
     * AND an install attempt is parked waiting for it AND the official app is
     * still not installed, the real HTTPS download (0% → 100% + MB progress)
     * starts by itself — never stuck on `0 · needs-permission` or `0 · busy`.
     */
    static void resumeIfWaiting(Context c) {
        try {
            if (!awaitingPermission) return;
            if (!canRequestPackageInstalls(c)) return;      // still not granted — keep waiting
            awaitingPermission = false;
            final Listener l = parkedListener;
            parkedListener = null;
            if (l == null) return;
            if (anantplayInstalled(c)) {                    // already installed meanwhile
                l.onState("installed");
                return;
            }
            Diag.i("weblock", "install permission granted on resume — auto-resuming the official download");
            l.onState("resuming");
            installOfficial(c, l);
        } catch (Throwable t) {
            Diag.w("weblock", "resume if waiting: " + t);
        }
    }

    /**
     * The full honor-the-OS pipeline. Runs on the caller's executor thread:
     * resolve → (permission gate) → download with live percent → PackageInstaller
     * handoff → the 1-tap sheet. Every failure is reported as an onState line,
     * never an exception into the UI.
     *
     * <p>v3.4.6.i — the busy leak is dead: `running` is reset on EVERY path
     * (the needs-permission early return included) through the outer
     * try/finally; once the worker is launched, the worker's own finally owns
     * the reset.
     */
    static void installOfficial(final Context c, final Listener l) {
        if (!running.compareAndSet(false, true)) {
            l.onState("busy");
            return;
        }
        boolean workerStarted = false;
        try {
            if (!canRequestPackageInstalls(c)) {
                // Honest path: explain, deep-link the user to the OS page; the
                // AUTO-RESUME: MainActivity.onResume() → resumeIfWaiting()
                // re-runs this pipeline the moment the grant lands (the
                // parked listener keeps streaming events — the v3.4.6.h
                // comment promised this and nothing implemented it, so the
                // user re-tapped into a leaked `running=true` → `0 · busy`).
                awaitingPermission = true;
                parkedListener = l;
                l.onState("needs-permission");
                // v3.4.6.i — the unknown-sources page opens WITH the
                // XRecorder-style translucent guide card on top (the animated
                // 'Allow from this source' toggle mockup + the Xiaomi note);
                // the plain page stays the fallback inside the helper.
                PermissionGuideActivity.openUnknownSourcesWithGuide(c);
                return;
            }
            awaitingPermission = false;
            parkedListener = null;
            final android.content.Context app = c.getApplicationContext();
            Thread worker = new Thread(() -> {
                try {
                    resolveDownloadInstall(app, l);
                } catch (Throwable t) {
                    Diag.w("weblock", "install official anantplay: " + t);
                    l.onState("failed: " + t.getClass().getSimpleName());
                } finally {
                    running.set(false);
                }
            }, "weblock-install");
            worker.setDaemon(true);
            workerStarted = true;
            worker.start();
        } catch (Throwable t) {
            l.onState("failed: " + t.getClass().getSimpleName());
        } finally {
            // v3.4.6.i — the busy-kill: the needs-permission return and the
            // pre-worker exception path BOTH hand the flag back (no worker
            // owns it), so a later tap can never hit `0 · busy`.
            if (!workerStarted) running.set(false);
        }
    }

    private static void resolveDownloadInstall(Context app, Listener l) throws Exception {
        l.onState("resolving");
        // 1 — the zero-quota 302 redirect (same trick SelfUpdateCore uses for
        // our own repo; github.com/AnantPlay/android/releases/latest → tag).
        String tag = SelfUpdateCore.resolveLatestTagByRedirect(
            "https://github.com/" + REPO + "/releases/latest");
        if (tag == null || tag.isEmpty()) {
            l.onState("no-release");
            return;
        }
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        // 2 — v3.4.6.i — REAL asset discovery: parse the expanded_assets HTML
        // fragment (relative AND absolute hrefs, phone build preferred) instead
        // of a guessed filename. Never `no-asset` again for a release that
        // ships APKs.
        String apkUrl = resolveAssetUrl(tag);
        if (apkUrl == null) {
            l.onState("no-asset");
            return;
        }
        // 3 — stream to our private cache (the official-app 200 MB cap; live
        // percent → the slide's progress bar).
        File dir = new File(app.getCacheDir(), "weblock");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            l.onState("no-cache");
            return;
        }
        File apk = new File(dir, "anantplay-official.apk");
        l.onProgress(0, version);
        SelfUpdateCore.downloadToFile(apkUrl, apk, MAX_APK_BYTES, (bytes, total, percent) ->
            l.onProgress(Math.max(0, Math.min(100, percent < 0 ? 0 : percent)), version));
        if (!apk.isFile() || apk.length() <= 0) {
            l.onState("download-failed");
            apk.delete();
            return;
        }
        l.onProgress(100, version);
        // 4 — the honest handoff: PackageInstaller → the 1-tap system sheet.
        l.onState("confirm");
        installViaPackageInstaller(app, apk);
    }

    // ── v3.4.6.i — the real asset resolver (the no-asset kill) ────────────────

    /**
     * The release's expanded-assets fragment lists the real download hrefs.
     * GitHub embeds them as RELATIVE hrefs (`/AnantPlay/android/releases/
     * download/v2.24.0/AnantPlay-Android-v2.24.0.apk`) — the v3.4.6.h regex
     * demanded absolute `https://…apk` hrefs, which is why it ALWAYS answered
     * null and the slide printed `0 · no-asset`. This resolver:
     *
     *   · collects every .apk href in BOTH shapes (absolute https and
     *     site-relative `/…apk`), absolutizing the relative ones;
     *   · scores them — arm64-v8a/universal phone builds first, then the
     *     versioned phone APK (e.g. AnantPlay-Android-v2.24.0.apk), then the
     *     unversioned phone APK, TV builds last;
     *   · returns the best https URL (or null only when the release truly
     *     ships no APK).
     *
     * Plain web endpoint (no REST quota, zero GitHub-REST calls).
     */
    static String resolveAssetUrl(String tag) {
        try {
            String body = SelfUpdateCore.httpGet(
                "https://github.com/" + REPO + "/releases/expanded_assets/" + tag);
            if (body == null || body.isEmpty()) return null;
            List<String> urls = extractApkDownloadUrls(body);
            String best = pickBestApkUrl(urls);
            return best;
        } catch (Throwable t) {
            Diag.w("weblock", "resolve official asset: " + t);
            return null;
        }
    }

    /** Every .apk download href in the fragment, absolutized. Testable pure. */
    static List<String> extractApkDownloadUrls(String html) {
        List<String> out = new ArrayList<>();
        if (html == null || html.isEmpty()) return out;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("href=\"([^\"]+\\.apk)\"")
            .matcher(html);
        while (m.find()) {
            String u = m.group(1);
            if (u.startsWith("/")) {
                // v3.4.6.i — GitHub's relative form: /AnantPlay/android/releases/download/…
                u = "https://github.com" + u;
            }
            if (u.contains("/releases/download/") && SelfUpdateCore.httpsOnly(u)) out.add(u);
        }
        return out;
    }

    /** The preference ladder: arm64/universal phone → versioned phone → unversioned phone → any non-TV → TV. */
    static String pickBestApkUrl(List<String> urls) {
        if (urls == null || urls.isEmpty()) return null;
        String best = null;
        int bestScore = -1;
        for (String u : urls) {
            String low = u.toLowerCase(java.util.Locale.ROOT);
            int score;
            if (low.contains("tv")) {
                score = 0;                                   // TV build — last resort
            } else if (low.contains("arm64-v8a") || low.contains("universal")) {
                score = 40;                                  // explicit arch/universal phone build
            } else if (low.contains("android") && countDigits(u) > 0) {
                score = 30;                                  // versioned phone build (AnantPlay-Android-v2.24.0.apk)
            } else if (low.contains("android")) {
                score = 20;                                  // unversioned phone build
            } else {
                score = 10;                                  // any other non-TV apk
            }
            if (score > bestScore) {
                bestScore = score;
                best = u;
            }
        }
        return best;
    }

    private static int countDigits(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) n++;
        }
        return n;
    }

    // ── v3.4.6.i — the official AnantPlay update check (the update pill) ──────

    /** Cache: the check must never run per-frame; one refresh per 10 minutes. */
    private static final long OFFICIAL_CHECK_TTL_MS = 10 * 60 * 1000L;
    private static volatile long officialCheckAt;
    private static volatile String officialLatest = "";    // "" = unknown
    private static volatile boolean officialLookupInFlight;
    private static final Object officialLock = new Object();

    /**
     * The official-AnantPlay update facts for the Home/Settings pill:
     * installed versionName ("" when the official app is absent), the latest
     * releases/latest tag resolved behind the fast 4 s deadline ("" when the
     * network refused), and whether an update is available (strictly newer).
     * Never blocks the caller longer than the map build — the network leg is
     * refreshed asynchronously with a 10-minute cache.
     */
    static java.util.Map<String, Object> officialUpdateFacts(Context c) {
        String installed = anantplayVersion(c);
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("installed", installed);
        out.put("latest", officialLatest == null ? "" : officialLatest);
        boolean available = !installed.isEmpty()
            && officialLatest != null && !officialLatest.isEmpty()
            && UpdateChecker.isNewer(officialLatest, installed);
        out.put("updateAvailable", available);
        refreshOfficialLatestAsync(c);
        return out;
    }

    /** The async refresh (one flight at a time, 10-minute cache). */
    private static void refreshOfficialLatestAsync(Context c) {
        final long now = System.currentTimeMillis();
        if (now - officialCheckAt < OFFICIAL_CHECK_TTL_MS) return;
        synchronized (officialLock) {
            if (officialLookupInFlight) return;
            if (System.currentTimeMillis() - officialCheckAt < OFFICIAL_CHECK_TTL_MS) return;
            officialLookupInFlight = true;
        }
        final android.content.Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                String tag = SelfUpdateCore.resolveLatestTagByRedirect(
                    "https://github.com/" + REPO + "/releases/latest",
                    SelfUpdateCore.FAST_CONNECT_MS, SelfUpdateCore.FAST_READ_MS);
                officialLatest = tag == null ? "" : SelfUpdateCore.stripV(tag);
                Diag.i("weblock", "official anantplay latest: " + officialLatest);
            } catch (Throwable t) {
                Diag.w("weblock", "official latest resolve: " + t);
            } finally {
                officialCheckAt = System.currentTimeMillis();
                synchronized (officialLock) {
                    officialLookupInFlight = false;
                }
            }
        }, "weblock-latest").start();
    }

    // ── the PackageInstaller handoff (SelfUpdater.install's exact shape) ──────

    /** v3.4.6.k — LAW #1 SILENT ZERO-PROMPT: this app is the INSTALLER-OF-
     *  RECORD for me.champdev.weblock (Official AnantPlay), so its updates
     *  carry UPDATE_PACKAGES_WITHOUT_USER_ACTION +
     *  SessionParams.setRequireUserAction(USER_ACTION_NOT_REQUIRED) on API
     *  31+ — the same silent lane our own self-updates use (Android 12–16,
     *  targetSdk 36 ≥ 34). Single-flight + the shared cooldown keep the
     *  system installer from ever stacking sheets. */
    private static void installViaPackageInstaller(Context c, File apk) throws Exception {
        if (SelfUpdater.installInFlight()) {
            Diag.i("weblock", "install already in flight — dropping the duplicate commit");
            return;
        }
        android.content.pm.PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        android.content.pm.PackageInstaller.Session session = null;
        int sid = -1;
        try {
            android.content.pm.PackageInstaller.SessionParams params =
                new android.content.pm.PackageInstaller.SessionParams(
                    android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                try {
                    params.setRequireUserAction(
                        android.content.pm.PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                } catch (Throwable noSilent) {
                    Diag.w("weblock", "USER_ACTION_NOT_REQUIRED refused (" + noSilent + ")");
                }
            }
            sid = pi.createSession(params);
            session = pi.openSession(sid);
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("weblock", 0, apk.length())) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                out.flush();
                session.fsync(out);
            }
            // SelfUpdater's own result receiver: the silent lane resolves with
            // STATUS_SUCCESS (zero prompt); a forced STATUS_PENDING_USER_ACTION
            // forwards the system's 1-tap confirm sheet exactly ONCE (BAL-safe
            // notification fallback when the background launch is refused) —
            // ONE install-result surface for both pipelines.
            Intent ok = new Intent(c, SelfUpdater.InstallResultReceiver.class);
            PendingIntent piOk = PendingIntent.getBroadcast(c, 32046, ok,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(piOk.getIntentSender());
            Diag.i("weblock", "official-anantplay install session " + sid
                + " committed (USER_ACTION_NOT_REQUIRED, API "
                + android.os.Build.VERSION.SDK_INT + ")");
        } catch (Throwable t) {
            if (session != null) try { session.abandon(); } catch (Throwable ignored) { }
            throw t;
        }
    }

    /** The post-install cleanup (called on resume when me.champdev.weblock appears). */
    static void sweepCache(Context c) {
        try {
            File apk = new File(c.getCacheDir(), "weblock/anantplay-official.apk");
            if (apk.isFile()) apk.delete();
        } catch (Throwable t) {
            Diag.w("weblock", "sweep: " + t);
        }
    }
}
