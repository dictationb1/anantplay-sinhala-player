package lk.techworld.anantplay.sicast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;

/* v3.0.0 — the PURE-JAVA core of the built-in GitHub self-updater: release
 * parsing, SHA256SUMS verification, STREAMED download with live progress and a
 * hard size cap. No Android imports, so the verification lab drives every rule
 * on a bare JVM against a mock GitHub server (progress callbacks, tampered-hash
 * refusal, oversize refusal, cleanup proof).
 *
 * v3 additions over v2.4.0:
 *   · downloadToFile() — streams to cacheDir/updates/ with a Progress callback
  *     (bytes/total/percent) and the under-50-MiB cap enforced DURING the stream, so a
 *     hostile "release" asset can never fill the phone's storage;
 *   · sha256(File) — streaming digest (the APK is never fully held in memory);
 *   · Release.apkSize — the asset's declared size from the release JSON, used
 *     to refuse an oversized download BEFORE the first byte arrives;
 *   · httpsOnly() — the install path accepts https:// download URLs only.
 *
 * v3.2.0 — THE ZERO-RATE-LIMIT 2-TIER RESOLVER:
 *   · Tier 1 (PRIMARY) — https://github.com/<repo>/releases/latest, a plain
 *     WEB page redirect: HttpURLConnection with setInstanceFollowRedirects(false)
 *     sees the 302 and reads the Location header
 *     (…/releases/tag/v3.2.0) — the tag is the version, and BOTH asset URLs
 *     are DERIVED deterministically:
 *         https://github.com/<repo>/releases/download/v<ver>/SHA256SUMS.txt
 *         https://github.com/<repo>/releases/download/v<ver>/AnantPlay-Sinhala-Player-v<ver>.apk
  *     this route is a simple web redirect and does not call the REST API;
 *   · Tier 2 (FALLBACK) — the tiny docs/latest.json the release workflow
 *     publishes to raw.githubusercontent.com ({"version":"3.2.0","code":30200}),
 *     auto-updated by .github/workflows/release-apk.yml on every release.
 * There is no GitHub REST API endpoint in the update path. A failed check is
 * throttled locally instead of falling back to a rate-limited service.
 * Every tier fills the SAME Release shape, so the download/verify/install path
  * above is untouched: https-only, under-50-MiB cap, strict SHA-256 vs SHA256SUMS.txt. */
final class SelfUpdateCore {

    /** Strictly below 50 MiB; a hostile/renamed asset is rejected before it fills storage. */
    static final long MAX_APK_BYTES = 50L * 1024 * 1024 - 1;

    /** Live download progress (called on the download thread). */
    interface Progress {
        void onProgress(long bytes, long total, int percent);
    }

    /** v3.2.0 — the repo the resolver derives every tier-1 URL from. */
    static final String REPO = "dictationb1/anantplay-sinhala-player";

    /** The two resolver routes, in firing order (the lab asserts the winner). */
    static final String TIER_WEB_REDIRECT = "web-redirect", TIER_RAW_JSON = "raw-json";

    /** Injectable only by the package-local verification lab. */
    interface ConnectionOpener {
        HttpURLConnection open(String url) throws Exception;
    }
    private static volatile ConnectionOpener testConnectionOpener;

    static void setConnectionOpenerForTests(ConnectionOpener opener) {
        testConnectionOpener = opener;
    }

    private static HttpURLConnection openConnection(String url) throws Exception {
        ConnectionOpener opener = testConnectionOpener;
        if (opener != null) return opener.open(url);
        return (HttpURLConnection) new URL(url).openConnection(java.net.Proxy.NO_PROXY);
    }

    /** The deterministic release-name contract (the CI's exact asset name). */
    static String apkAssetName(String version) {
        return "AnantPlay-Sinhala-Player-v" + version + ".apk";
    }

    /** v3.2.0 — the asset URL derived from a resolved tag (tier 1). */
    static String derivedApkUrl(String tag) {
        return "https://github.com/" + REPO + "/releases/download/" + tag + "/" + apkAssetName(stripV(tag));
    }

    /** v3.2.0 — the SHA256SUMS.txt URL derived from a resolved tag (tier 1). */
    static String derivedSumsUrl(String tag) {
        return "https://github.com/" + REPO + "/releases/download/" + tag + "/SHA256SUMS.txt";
    }

    /** v3.2.0 — the raw docs/latest.json URL (tier 2). */
    static String rawLatestJsonUrl() {
        return "https://raw.githubusercontent.com/" + REPO + "/main/docs/latest.json";
    }

    private SelfUpdateCore() { }

    /** True only for https:// URLs (the updater refuses everything else). */
    static boolean httpsOnly(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).startsWith("https://");
    }

    // ── v3.2.0 — THE BACKOFF GATE (pure — the lab proves the whole contract) ──

    /** The app-open success cooldown: automatic checks run at most every 5 min. */
    static final long MIN_CHECK_INTERVAL_MS = 5 * 60 * 1000L;
    /** THE FAILURE BACKOFF: after a failed/offline check, automatic re-checks
     *  are locked out for 15 minutes (the anti-hammer contract that killed the
     *  250 ms field loop). A manual "Check now" always bypasses it. */
    static final long FAILURE_BACKOFF_MS = 15 * 60 * 1000L;

    /**
     * May an AUTOMATIC check start right now? Two locks, either one vetoes:
     * the 5-minute app-open cooldown (lastCheck — persisted BEFORE the
     * network attempt, so it holds even when the attempt crashed), the
     * 15-minute FAILURE backoff (lastFailedCheck). `manual=true` bypasses both
     * gates because the user explicitly asked to check now.
     * Pure — the lab drives every branch and the 200-calls-in-148-s field storm. */
    static boolean checkAllowed(long nowMs, long lastCheck, long lastFailedCheck, boolean manual) {
        if (manual) return true;
        if (lastCheck > 0 && nowMs - lastCheck < MIN_CHECK_INTERVAL_MS) return false;
        if (lastFailedCheck > 0 && nowMs - lastFailedCheck < FAILURE_BACKOFF_MS) return false;
        return true;
    }

    /** The GitHub release facts required before any APK can be downloaded. */
    static final class Release {
        String version;          // tag without 'v'
        String apkUrl;           // browser_download_url of the .apk asset
        String sha256Expected;   // from SHA256SUMS.txt (null when absent → refuse)
        long apkSize = -1;       // the asset's declared size (bytes; -1 unknown)
        String tier = "";        // web-redirect | raw-json (the lab asserts)
    }

    // ── the 2-tier resolver (no GitHub REST API calls) ────────────────────────

    /**
     * THE release resolver: routes fire in order web-redirect → raw-json;
     * the first one that produces a complete, verified release shape
     * wins. A tier's own HTTP body errors are soft (the next tier gets its
     * chance); only both-routes-failed returns null.
     */
    static Release fetchReleaseAny() throws Exception {
        // Route 1 — the github.com/releases/latest 302 redirect (web CDN, no quota)
        try {
            Release r = fetchViaWebRedirect();
            if (usableRelease(r)) return r;
        } catch (Throwable t) {
            Diag.w("selfupd", "tier1 web-redirect: " + t);
        }
        // Route 2 — the CI-maintained docs/latest.json on raw.githubusercontent.com
        try {
            Release r = fetchViaRawJson();
            if (usableRelease(r)) return r;
        } catch (Throwable t) {
            Diag.w("selfupd", "tier2 raw-json: " + t);
        }
        return null;
    }

    private static boolean usableRelease(Release r) {
        return r != null
            && r.version != null && r.version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")
            && httpsOnly(r.apkUrl)
            && apkAssetName(r.version).equals(apkFileName(r.apkUrl))
            && r.sha256Expected != null && r.sha256Expected.matches("(?i)[0-9a-f]{64}");
    }

    /**
     * TIER 1 — https://github.com/<repo>/releases/latest answers 302 with
     * Location: …/releases/tag/vX.Y.Z when a release exists (200 when none).
     * setInstanceFollowRedirects(false) makes the redirect OBSERVABLE instead
     * of followed: one request, zero quota, and both asset URLs are derived
     * deterministically from the tag. `connect` is seam-injectable for the lab
     * (a mock github.com that serves the exact 302). */
    static Release fetchViaWebRedirect() throws Exception {
        String tag = resolveLatestTagByRedirect("https://github.com/" + REPO + "/releases/latest");
        if (tag == null || tag.isEmpty()) return null;
        Release r = new Release();
        r.version = stripV(tag);
        if (!r.version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) return null;
        r.tier = TIER_WEB_REDIRECT;
        r.apkUrl = derivedApkUrl(tag);
        r.sha256Expected = fetchExpectedSha(derivedSumsUrl(tag), apkAssetName(r.version));
        return r;
    }

    /** The observable-redirect core: HEAD → 302 → Location's /tag/vX.Y.Z tail.
     *  Public-contract seam: the lab passes its own connection factory. */
    static String resolveLatestTagByRedirect(String releasesLatestUrl) throws Exception {
        HttpURLConnection c = openConnection(releasesLatestUrl);
        try {
            c.setRequestMethod("HEAD");
            c.setInstanceFollowRedirects(false);     // the 302 is the ANSWER, not a chase
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36");
            int code = c.getResponseCode();
            if (code != 302 && code != 303 && code != 307) return null;   // 200 = no releases
            String loc = c.getHeaderField("Location");
            return tagFromLocation(loc);
        } finally {
            c.disconnect();
        }
    }

    /** "https://github.com/<repo>/releases/tag/v3.2.0" → "v3.2.0". */
    static String tagFromLocation(String location) {
        if (location == null) return null;
        String t = location.trim();
        int i = t.lastIndexOf("/tag/");
        String tag = i >= 0 ? t.substring(i + 5) : t.substring(t.lastIndexOf('/') + 1);
        int q = tag.indexOf('?');
        if (q >= 0) tag = tag.substring(0, q);
        return tag.isEmpty() ? null : tag;
    }

    /** Route 2 — docs/latest.json written by the release workflow. */
    static Release fetchViaRawJson() throws Exception {
        Release r = fetchViaRawJsonBody(httpGet(rawLatestJsonUrl()));
        if (r == null) return null;
        String sumsSha = fetchExpectedSha(derivedSumsUrl("v" + r.version), apkAssetName(r.version));
        if (sumsSha == null) return null;
        if (r.sha256Expected != null && !r.sha256Expected.equalsIgnoreCase(sumsSha)) return null;
        r.sha256Expected = sumsSha;
        return r;
    }

    /** The tier-2 JSON parser (pure — the lab drives fixture bodies). Never throws:
     *  a junk body is null; network resolution separately requires a matching
     *  SHA256SUMS.txt entry before the release can be used. */
    static Release fetchViaRawJsonBody(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        Object root;
        try {
            root = MiniJson.parse(json);
        } catch (Throwable t) {
            return null;                       // a non-JSON body (an error page) → tier falls through
        }
        if (!(root instanceof Map)) return null;
        String version = MiniJson.str(root, "version");
        if (version == null || !stripV(version).matches("[0-9]+\\.[0-9]+\\.[0-9]+")) return null;
        String tag = version.startsWith("v") ? version : "v" + version;
        Release r = new Release();
        r.version = stripV(tag);
        r.tier = TIER_RAW_JSON;
        // an explicitly-published apk URL wins; otherwise the derived contract
        String apk = MiniJson.str(root, "apkUrl");
        r.apkUrl = apk != null && !apk.isEmpty() && httpsOnly(apk) ? apk : derivedApkUrl(tag);
        String sha = MiniJson.str(root, "sha256");
        if (sha != null && sha.matches("[0-9a-fA-F]{64}")) {
            r.sha256Expected = sha.toLowerCase(Locale.ROOT);
        }
        return r;
    }

    /** "v3.2.0" → "3.2.0" (tolerant of a missing v, spaces, junk). */
    static String stripV(String tag) {
        if (tag == null) return "";
        String t = tag.trim();
        if (t.toLowerCase(Locale.ROOT).startsWith("v")) t = t.substring(1);
        return t.trim();
    }

    /** GET the SHA256SUMS.txt asset and pick the apk's line (soft: null when the
     *  fetch or the line is missing — the installer refuses on null). */
    static String fetchExpectedSha(String sumsUrl, String apkFile) {
        try {
            String sums = httpGet(sumsUrl);
            return pickShaLine(sums, apkFile);
        } catch (Throwable t) {
            Diag.w("selfupd", "sha fetch: " + t);
            return null;
        }
    }

    /** The file name part of a download URL. */
    static String apkFileName(String url) {
        int s = url.lastIndexOf('/');
        return s < 0 ? url : url.substring(s + 1);
    }

    /** Find "<sha256>  <file>" in a SHA256SUMS.txt body (GNU coreutils format —
     *  the EXACT format .github/workflows/release-apk.yml writes: one line,
     *  "sha256sum <apk>" output, hash + two spaces + file name).
     *  Returns null when the file has no line (→ refuse to install). */
    static String pickShaLine(String sums, String apkFile) {
        if (sums == null || apkFile == null) return null;
        for (String line : sums.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+", 2);
            if (parts.length != 2) continue;
            String hash = parts[0].trim().toLowerCase(Locale.ROOT);
            String file = parts[1].trim().replaceFirst("^[*]", "");
            if (apkFile.equals(file) && hash.matches("[0-9a-f]{64}")) return hash;
        }
        return null;
    }

    /** SHA-256 of a byte array, lowercase hex. */
    static String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(data);
        return hex(d);
    }

    /** SHA-256 of a FILE, streamed in 64 KB chunks (never holds the APK in memory). */
    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    /** Shared fail-closed file verification used before PackageInstaller handoff. */
    static boolean matchesSha256(File file, String expected) {
        if (file == null || !file.isFile() || expected == null
                || !expected.matches("(?i)[0-9a-f]{64}")) return false;
        try { return expected.equalsIgnoreCase(sha256(file)); }
        catch (Throwable ignored) { return false; }
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(64);
        for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    /**
     * v3.0.0 — STREAM the APK into a file with live progress, enforcing:
     *   · https-only URL (anything else → SecurityException before connecting);
     *   · the under-50-MiB cap, checked against the declared Content-Length BEFORE the
     *     first byte AND against the accumulated bytes DURING the stream.
     * Returns the bytes written (== file length).
     */
    static long downloadToFile(String url, File out, Progress progress) throws Exception {
        if (!httpsOnly(url)) throw new SecurityException("refusing non-https download: " + url);
        HttpURLConnection c = openConnection(url);
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        try {
            return pump(c, out, MAX_APK_BYTES, progress);
        } finally {
            c.disconnect();
        }
    }

    /** The streaming core (package-private — the verification lab drives it on a
     *  bare JVM over a local http server): enforces the cap BEFORE the first
     *  byte (declared Content-Length) and DURING the stream, reports progress
     *  (percent = -1 when the length is unknown), and never writes a byte past
     *  the cap. v3.2.0: FOLLOWS the github.com/download 302 once (the asset
     *  URLs are release/download links that redirect to objects.githubusercontent
     *  .com — following redirects here is required for the download, unlike the
     *  tier-1 CHECK where the redirect IS the answer). */
    static long pump(HttpURLConnection c, File out, long cap, Progress progress) throws Exception {
        c.setInstanceFollowRedirects(true);
        if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
        long total = c.getContentLengthLong();   // -1 when unknown
        if (total > cap) throw new IllegalStateException("apk too large before download: " + total + " > " + cap);
        long done = 0;
        try (InputStream in = c.getInputStream(); OutputStream fo = new FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) {
                done += n;
                if (done > cap) throw new IllegalStateException("apk exceeded the " + (cap / 1024 / 1024) + " MB cap mid-download");
                fo.write(buf, 0, n);
                if (progress != null) progress.onProgress(done, total,
                    total > 0 ? (int) (done * 100 / total) : -1);
            }
        }
        return done;
    }

    /** v3.0.0 — the storage-cleanup primitive the Android side sweeps with
     *  (SelfUpdater.sweepUpdates + the post-session-handoff delete): removes
     *  EVERY file under dir, returning the bytes freed. Lab-proven: after a
     *  handoff + sweep the updates dir holds 0 leftover bytes. */
    static long deleteAll(File dir) {
        long freed = 0;
        if (dir == null || !dir.isDirectory()) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            long len = f.length();
            if (f.delete()) freed += len;
        }
        return freed;
    }

    /** The v2.4.0 in-memory download (kept for the lab + tiny fixtures). */
    static byte[] download(String url) throws Exception {
        if (!httpsOnly(url)) throw new SecurityException("refusing non-https download: " + url);
        HttpURLConnection c = openConnection(url);
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(1 << 20);
            InputStream in = c.getInputStream();
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            in.close();
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    static String httpGet(String url) throws Exception {
        HttpURLConnection c = openConnection(url);
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/json, text/plain;q=0.9, */*;q=0.8");
        try {
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            in.close();
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }
}
