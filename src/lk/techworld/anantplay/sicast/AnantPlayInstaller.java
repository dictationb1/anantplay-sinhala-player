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
     * The full honor-the-OS pipeline. Runs on the caller's executor thread:
     * resolve → (permission gate) → download with live percent → PackageInstaller
     * handoff → the 1-tap sheet. Every failure is reported as an onState line,
     * never an exception into the UI.
     */
    static void installOfficial(final Context c, final Listener l) {
        if (!running.compareAndSet(false, true)) {
            l.onState("busy");
            return;
        }
        try {
            if (!canRequestPackageInstalls(c)) {
                // Honest path: explain, deep-link the user to the OS page; the
                // slide re-runs installOfficial automatically on resume (the
                // WidgetsBindingObserver resume contract) once granted.
                l.onState("needs-permission");
                openUnknownSources(c);
                return;
            }
            final android.content.Context app = c.getApplicationContext();
            Thread worker = new Thread(() -> {
                try {
                    resolveDownloadInstall(app, l);
                } catch (Throwable t) {
                    Diag.w("weblock", "install official anantplay: " + t);
                    l.onState("failed: " + t);
                } finally {
                    running.set(false);
                }
            }, "weblock-install");
            worker.setDaemon(true);
            worker.start();
        } catch (Throwable t) {
            running.set(false);
            l.onState("failed: " + t);
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
        // Official asset naming follows AnantPlay-Sinhala-Player-…? No — the
        // official repo ships plain `AnantPlay-vX.Y.Z.apk`-family names; we
        // resolve the browser_download_url through the release page instead
        // of guessing: fetch the expanded /releases/expanded_assets JSON.
        String apkUrl = resolveAssetUrl(tag);
        if (apkUrl == null) {
            l.onState("no-asset");
            return;
        }
        // 2 — stream to our private cache (same under-cap pump the self-updater
        // uses; live percent → the slide's progress bar).
        File dir = new File(app.getCacheDir(), "weblock");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            l.onState("no-cache");
            return;
        }
        File apk = new File(dir, "anantplay-official.apk");
        l.onProgress(0, version);
        SelfUpdateCore.downloadToFile(apkUrl, apk, (bytes, total, percent) ->
            l.onProgress(Math.max(0, Math.min(100, percent < 0 ? 0 : percent)), version));
        if (!apk.isFile() || apk.length() <= 0) {
            l.onState("download-failed");
            apk.delete();
            return;
        }
        l.onProgress(100, version);
        // 3 — the honest handoff: PackageInstaller → the 1-tap system sheet.
        l.onState("confirm");
        installViaPackageInstaller(app, apk);
    }

    /**
     * The release page's expanded-assets endpoint lists asset
     * browser_download_urls as JSON. We parse the first *.apk match. This is a
     * plain web endpoint (no REST quota), exactly the class of URL the tier-1
     * resolver prefers.
     */
    private static String resolveAssetUrl(String tag) {
        try {
            String body = SelfUpdateCore.httpGet(
                "https://github.com/" + REPO + "/releases/expanded_assets/" + tag);
            if (body == null || body.isEmpty()) return null;
            String url = extractApkDownloadUrl(body);
            if (url != null && SelfUpdateCore.httpsOnly(url)) return url;
            // Fallback: the deterministic github user-content asset prefix the
            // expanded_assets HTML embeds as href values.
            return url;
        } catch (Throwable t) {
            Diag.w("weblock", "resolve official asset: " + t);
            return null;
        }
    }

    /** The expanded_assets page embeds download hrefs — find the first .apk. */
    private static String extractApkDownloadUrl(String html) {
        // href="https://github.com/AnantPlay/android/releases/download/…/x.apk"
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("href=\"(https://[^\"]+\\.apk)\"")
            .matcher(html);
        while (m.find()) {
            String u = m.group(1);
            if (u.contains("/releases/download/")) return u;
        }
        return null;
    }

    // ── the PackageInstaller handoff (SelfUpdater.install's exact shape) ──────

    private static void installViaPackageInstaller(Context c, File apk) throws Exception {
        android.content.pm.PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        android.content.pm.PackageInstaller.Session session = null;
        int sid = -1;
        try {
            sid = pi.createSession(new android.content.pm.PackageInstaller.SessionParams(
                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL));
            session = pi.openSession(sid);
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("weblock", 0, apk.length())) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                out.flush();
                session.fsync(out);
            }
            // SelfUpdater's own result receiver: on STATUS_PENDING_USER_ACTION
            // it forwards the system's 1-tap confirm sheet (BAL-safe fallback
            // notification when the background launch is refused) — exactly
            // the install UX our own updates use. Reusing it means ONE
            // install-result surface for both pipelines.
            Intent ok = new Intent(c, SelfUpdater.InstallResultReceiver.class);
            PendingIntent piOk = PendingIntent.getBroadcast(c, 32046, ok,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(piOk.getIntentSender());
            Diag.i("weblock", "official-anantplay install session " + sid + " committed");
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
