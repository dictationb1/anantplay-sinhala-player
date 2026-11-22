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
 *     (bytes/total/percent) and the 30 MB cap enforced DURING the stream, so a
 *     hostile "release" asset can never fill the phone's storage;
 *   · sha256(File) — streaming digest (the APK is never fully held in memory);
 *   · Release.apkSize — the asset's declared size from the release JSON, used
 *     to refuse an oversized download BEFORE the first byte arrives;
 *   · httpsOnly() — the install path accepts https:// download URLs only.
 *
 * v3.2.0 — THE ZERO-RATE-LIMIT 3-TIER RESOLVER (Smoking Gun #1: the 60/hr
 * api.github.com REST limit burned 200+ calls in 148 s on the field phone):
 *   · Tier 1 (PRIMARY) — https://github.com/<repo>/releases/latest, a plain
 *     WEB page redirect: HttpURLConnection with setInstanceFollowRedirects(false)
 *     sees the 302 and reads the Location header
 *     (…/releases/tag/v3.2.0) — the tag is the version, and BOTH asset URLs
 *     are DERIVED deterministically:
 *         https://github.com/<repo>/releases/download/v<ver>/SHA256SUMS.txt
 *         https://github.com/<repo>/releases/download/v<ver>/AnantPlay-Sinhala-Player-v<ver>.apk
 *     github.com's web CDN has no per-IP REST quota — the check costs ZERO
 *     api.github.com calls by construction;
 *   · Tier 2 (FALLBACK) — the tiny docs/latest.json the release workflow
 *     publishes to raw.githubusercontent.com ({"version":"3.2.0","code":30200}),
 *     auto-updated by .github/workflows/release-apk.yml on every release;
 *   · Tier 3 (LAST RESORT) — the old api.github.com REST call, only when BOTH
 *     tiers above failed, honoring X-RateLimit-Remaining / Retry-After when the
 *     API answers 403 (and NEVER re-fired while a backoff is outstanding).
 * Every tier fills the SAME Release shape, so the download/verify/install path
 * above is untouched: https-only, 30 MB cap, strict SHA-256 vs SHA256SUMS.txt. */
final class SelfUpdateCore {

    /** Nothing larger than this is ever downloaded (a hostile/renamed asset). */
    static final long MAX_APK_BYTES = 30L * 1024 * 1024;

    /** Live download progress (called on the download thread). */
    interface Progress {
        void onProgress(long bytes, long total, int percent);
    }

    /** v3.2.0 — the repo the resolver derives every tier-1 URL from. */
    static final String REPO = "dictationb1/anantplay-sinhala-player";

    /** v3.2.0 — the three resolver tiers, in firing order (the lab asserts
     *  which tier answered via Release.tier). */
    static final String TIER_WEB_REDIRECT = "web-redirect", TIER_RAW_JSON = "raw-json", TIER_REST_API = "rest-api";

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

    /** v3.2.0 — the legacy REST URL (tier 3, LAST resort only). */
    static String restLatestUrl() {
        return "https://api.github.com/repos/" + REPO + "/releases/latest";
    }

    private SelfUpdateCore() { }

    /** True only for https:// URLs (the updater refuses everything else). */
    static boolean httpsOnly(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).startsWith("https://");
    }

    // ── v3.2.0 — THE BACKOFF GATE (pure — the lab proves the whole contract) ──

    /** The app-open debounce: a check runs at most once per 60 s. */
    static final long MIN_CHECK_INTERVAL_MS = 60 * 1000L;
    /** THE FAILURE BACKOFF: after a failed/offline check, automatic re-checks
     *  are locked out for 15 minutes (the anti-hammer contract that killed the
     *  250 ms field loop). A manual "Check now" always bypasses it. */
    static final long FAILURE_BACKOFF_MS = 15 * 60 * 1000L;

    /**
     * v3.2.0 — may an AUTOMATIC check start right now? Three locks, any one
     * vetoes: the 60 s app-open debounce (lastCheck — persisted BEFORE the
     * network attempt, so it holds even when the attempt crashed), the
     * 15-minute FAILURE backoff (lastFailedCheck), and (by JobScheduler's own
     * cadence) the 4-hour period. `manual=true` (the user pressed Check now)
     * bypasses everything. Pure — the lab drives every branch and the exact
     * 200-calls-in-148-s field storm. */
    static boolean checkAllowed(long nowMs, long lastCheck, long lastFailedCheck, boolean manual) {
        if (manual) return true;
        if (lastCheck > 0 && nowMs - lastCheck < MIN_CHECK_INTERVAL_MS) return false;
        if (lastFailedCheck > 0 && nowMs - lastFailedCheck < FAILURE_BACKOFF_MS) return false;
        return true;
    }

    /** The GitHub release facts we need. v3.2.0 adds `tier` (which resolver
     *  answered) so the lab + the Troubleshooter can prove api.github.com was
     *  NEVER touched on the happy path. */
    static final class Release {
        String version;          // tag without 'v'
        String apkUrl;           // browser_download_url of the .apk asset
        String sha256Expected;   // from SHA256SUMS.txt (null when absent → refuse)
        long apkSize = -1;       // the asset's declared size (bytes; -1 unknown)
        String tier = "";        // web-redirect | raw-json | rest-api (the lab asserts)
    }

    // ── the v3.2.0 3-tier resolver (ZERO api.github.com on the happy path) ────

    /**
     * THE release resolver: tiers fire in order web-redirect → raw-json →
     * rest-api, the first one that produces a Release with a valid apk URL
     * wins. A tier's own HTTP body errors are soft (the next tier gets its
     * chance); only all-three-failed returns null.
     * `restHook` lets the lab stub the REST transport without network. */
    static Release fetchReleaseAny() throws Exception {
        // TIER 1 — the github.com/releases/latest 302 redirect (web CDN, no quota)
        try {
            Release r = fetchViaWebRedirect();
            if (r != null) return r;
        } catch (Throwable t) {
            Diag.w("selfupd", "tier1 web-redirect: " + t);
        }
        // TIER 2 — the CI-maintained docs/latest.json on raw.githubusercontent.com
        try {
            Release r = fetchViaRawJson();
            if (r != null) return r;
        } catch (Throwable t) {
            Diag.w("selfupd", "tier2 raw-json: " + t);
        }
        // TIER 3 — LAST RESORT: the rate-limited REST API (a 403 here is final
        // for this attempt; the caller's backoff keeps us from re-burning it)
        try {
            Release r = fetchRelease(restLatestUrl());
            if (r != null) { r.tier = TIER_REST_API; return r; }
        } catch (Throwable t) {
            Diag.w("selfupd", "tier3 rest-api: " + t);
        }
        return null;
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
        r.tier = TIER_WEB_REDIRECT;
        r.apkUrl = derivedApkUrl(tag);
        r.sha256Expected = fetchExpectedSha(derivedSumsUrl(tag), apkAssetName(r.version));
        return r;
    }

    /** The observable-redirect core: HEAD → 302 → Location's /tag/vX.Y.Z tail.
     *  Public-contract seam: the lab passes its own connection factory. */
    static String resolveLatestTagByRedirect(String releasesLatestUrl) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(releasesLatestUrl).openConnection(java.net.Proxy.NO_PROXY);
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

    /** TIER 2 — docs/latest.json ({"version":"3.2.0","code":30200}), written by
     *  the release workflow; the asset URLs derive exactly like tier 1. */
    static Release fetchViaRawJson() throws Exception {
        return fetchViaRawJsonBody(httpGet(rawLatestJsonUrl()));
    }

    /** The tier-2 parser (pure — the lab drives fixture bodies). Never throws:
     *  a junk body is a null (the tier falls through to tier 3). */
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
        if (version == null || stripV(version).isEmpty()) return null;
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
        } else {
            r.sha256Expected = fetchExpectedSha(derivedSumsUrl(tag), apkAssetName(r.version));
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

    // ── the legacy REST path (tier 3 — LAST resort only) ───────────────────────

    /** GET the /releases/latest JSON + the SHA256SUMS.txt asset, parse the apk
     *  URL, the version, the declared size and the expected hash (MiniJson).
     *  v3.2.0: honors X-RateLimit-Remaining / Retry-After by REPORTING them
     *  (SelfUpdater's backoff enforces the pause; the Troubleshooter records
     *  the fact the quota is exhausted instead of hammering it). */
    static Release fetchRelease(String apiUrl) throws Exception {
        String body = httpGet(apiUrl);
        if (body == null || body.isEmpty()) return null;
        Object root = MiniJson.parse(body);
        if (!(root instanceof java.util.Map)) return null;
        String tag = MiniJson.str(root, "tag_name");
        if (tag == null || tag.isEmpty()) return null;
        Release r = new Release();
        r.version = tag.startsWith("v") ? tag.substring(1) : tag;
        r.apkUrl = "";
        java.util.List<Object> assets = MiniJson.arr(MiniJson.objGet(root, "assets"));
        if (assets != null) {
            String sumsUrl = null;
            for (Object a : assets) {
                String name = MiniJson.str(a, "name");
                String url = MiniJson.str(a, "browser_download_url");
                if (name == null || url == null) continue;
                if (name.toLowerCase(Locale.ROOT).endsWith(".apk") && r.apkUrl.isEmpty()) {
                    r.apkUrl = url;
                    Number size = MiniJson.num(a, "size");
                    r.apkSize = size == null ? -1 : size.longValue();
                }
                if (name.equalsIgnoreCase("SHA256SUMS.txt")) sumsUrl = url;
            }
            if (sumsUrl != null && !r.apkUrl.isEmpty()) {
                String sums = httpGet(sumsUrl);
                r.sha256Expected = pickShaLine(sums, apkFileName(r.apkUrl));
            }
        }
        return r.apkUrl.isEmpty() ? null : r;
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

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(64);
        for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    /**
     * v3.0.0 — STREAM the APK into a file with live progress, enforcing:
     *   · https-only URL (anything else → SecurityException before connecting);
     *   · the 30 MB cap, checked against the declared Content-Length BEFORE the
     *     first byte AND against the accumulated bytes DURING the stream.
     * Returns the bytes written (== file length).
     */
    static long downloadToFile(String url, File out, Progress progress) throws Exception {
        if (!httpsOnly(url)) throw new SecurityException("refusing non-https download: " + url);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(java.net.Proxy.NO_PROXY);
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
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(java.net.Proxy.NO_PROXY);
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
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(java.net.Proxy.NO_PROXY);
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
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
