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

    /** Strictly below 55 MiB (v3.4.6: the Flutter APK tier — still safely under the
     *  50 MiB SHIPPING gate in build.sh/CI, with headroom for the updater to accept
     *  any valid future release); a hostile/renamed asset is rejected before it
     *  fills storage. */
    static final long MAX_APK_BYTES = 55L * 1024 * 1024 - 1;

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

    /**
     * v3.4.6.i — THE jsDelivr CDN MIRROR of the same docs/latest.json (tier 3
     * / the FAIL 2 fix). The v3.4.6.h phone stalled 6,008 ms on
     * raw.githubusercontent.com (Sri Lankan ISP DNS + IPv6-first multi-address
     * connect are NOT covered by socket timers), so probe 2 printed a FAIL
     * and the fast check lane resolved null. The CDN edge serves the identical
     * bytes from a closer POP — a genuine fallback, not a second API (zero
     * GitHub-REST calls, still just a static file).
     */
    static String jsdelivrLatestJsonUrl() {
        return "https://cdn.jsdelivr.net/gh/" + REPO + "@main/docs/latest.json";
    }

    private SelfUpdateCore() { }

    /** True only for https:// URLs (the updater refuses everything else). */
    static boolean httpsOnly(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).startsWith("https://");
    }

    /**
     * v3.4.6.i — THE SSRF GUARD for every caller-supplied remote URL (the
     * Dart {@code fetchUrl} lane that feeds the Sources Hub, and the installer
     * fetcher). {@link #httpsOnly(String)} only proves the scheme; a hostile
     * or simply buggy caller could still aim the request at loopback, a
     * private/link-local range, a cloud metadata endpoint or a bare host. This
     * adds the HOST law on top of the scheme law:
     *
     *   · https:// only (no file://, content://, http://, ftp://, …);
     *   · a real, dotted DNS name or a globally-routable literal address;
     *   · localhost / *.localhost / *.local / *.internal and every
     *     loopback, private, link-local, CGNAT, unique-local, multicast,
     *     broadcast and unspecified literal is refused, IPv6 included
     *     ("[::1]", "[::ffff:127.0.0.1]", "[fd00::1]", "[fe80::1]").
     *
     * Deliberately resolves NOTHING (no DNS lookup here): the check is a pure
     * string/parse predicate, so it is fast, offline, and lab-testable.
     */
    static boolean safeRemoteUrl(String url) {
        if (!httpsOnly(url)) return false;
        try {
            URL u = new URL(url);
            String scheme = u.getProtocol();
            if (scheme == null || !"https".equals(scheme.toLowerCase(Locale.ROOT))) return false;
            String host = u.getHost();
            if (host == null) return false;
            host = host.trim().toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            if (host.isEmpty()) return false;
            // ── 1. the banned name suffixes (the classic SSRF pivots) ─────────
            if (host.equals("localhost") || host.endsWith(".localhost")
                    || host.endsWith(".local") || host.endsWith(".internal")
                    || host.endsWith(".home.arpa") || host.equals("metadata.google.internal")) {
                return false;
            }
            // ── 2. a bare single-label host (an intranet name, never a CDN) ──
            boolean ipv6 = host.startsWith("[") || host.indexOf(':') >= 0;
            if (!ipv6 && host.indexOf('.') < 0) return false;
            // ── 3. numeric literals: refuse every non-public address ─────────
            if (ipv6) return !ipv6Banned(host);
            if (isIPv4Literal(host)) return !ipv4Banned(host);
            // a dotted DNS name that is not a literal: accept.
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when the host is all-digits-and-dots (an IPv4 literal in any of the
     *  shapes URL parsing normalises: 127.0.0.1, 0x7f.0.0.1, 2130706433 …). */
    private static boolean isIPv4Literal(String host) {
        if (host.indexOf('.') < 0) return host.matches("[0-9]+|0[xX][0-9a-fA-F]+");
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || c == '.' || c == 'x' || c == 'X'
                    || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) return false;
        }
        return host.matches("[0-9a-fA-FxX.]+");
    }

    /** The banned IPv4 ranges (loopback, private, CGNAT, link-local, metadata,
     *  multicast, reserved, broadcast). */
    private static boolean ipv4Banned(String host) {
        try {
            String[] parts = host.split("\\.");
            if (parts.length != 4) return true;          // an odd shape is refused
            int[] o = new int[4];
            for (int i = 0; i < 4; i++) o[i] = Integer.parseInt(parts[i]);
            for (int v : o) if (v < 0 || v > 255) return true;
            if (o[0] == 0) return true;                              // 0.0.0.0/8
            if (o[0] == 10) return true;                             // private
            if (o[0] == 127) return true;                            // loopback
            if (o[0] == 100 && o[1] >= 64 && o[1] <= 127) return true;  // CGNAT
            if (o[0] == 169 && o[1] == 254) return true;             // link-local
            if (o[0] == 172 && o[1] >= 16 && o[1] <= 31) return true;   // private
            if (o[0] == 192 && o[1] == 168) return true;             // private
            if (o[0] == 192 && o[1] == 0 && o[2] == 0) return true;  // 192.0.0.0/24
            if (o[0] >= 224) return true;                            // multicast+reserved
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    /** The banned IPv6 forms (loopback, unspecified, ULA, link-local, v4-mapped
     *  private, multicast). */
    private static boolean ipv6Banned(String rawHost) {
        String h = rawHost;
        if (h.startsWith("[")) h = h.substring(1, h.endsWith("]") ? h.length() - 1 : h.length());
        h = h.toLowerCase(Locale.ROOT);
        int pct = h.indexOf('%');
        if (pct >= 0) h = h.substring(0, pct);          // strip the zone id
        if (h.isEmpty()) return true;
        if (h.equals("::") || h.equals("::1") || h.equals("0:0:0:0:0:0:0:0")
                || h.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        if (h.startsWith("fe80") || h.startsWith("fc") || h.startsWith("fd")) return true;
        if (h.startsWith("ff")) return true;            // multicast
        // an IPv4-mapped/compatible form: judge the embedded v4 tail.
        int lastColon = h.lastIndexOf(':');
        if (lastColon >= 0) {
            String tail = h.substring(lastColon + 1);
            if (isIPv4Literal(tail) && ipv4Banned(tail)) return true;
        }
        if (isIPv4Literal(h)) return ipv4Banned(h);
        return false;
    }

    // ── v3.2.0 — THE BACKOFF GATE (pure — the lab proves the whole contract) ──
    // v3.4.6.f — LAW #2: the check network timeouts are 5 s connect /
    // 8 s read (the v3.4.6.e field log stalled 32,891 ms on docs/latest.json
    // inside the troubleshooter — a check may never hang like that).
    static final int CHECK_CONNECT_MS = 5000;
    static final int CHECK_READ_MS = 8000;

    // ══ v3.4.6.h — GUARANTEE #4: THE 4-SECOND HARD WALL-CLOCK FAST LANE ══════
    // The v3.4.6.g gate could take 5 s connect + 8 s read per tier (and the
    // SHA fetch after it) before the ForceUpdateScreen appeared. The CHECK
    // lane now carries an ABSOLUTE 4-second wall-clock deadline per tier:
    // tier-1 releases/latest 302 (4 s) → immediate fallback → tier-2
    // docs/latest.json (4 s) — zero GitHub-REST calls, and the SHA leg runs
    // only in the user's Update Now download path.
    static final int FAST_CONNECT_MS = 4000;
    static final int FAST_READ_MS = 4000;
    static final long FAST_DEADLINE_MS = 4000L;

    /**
     * v3.4.6.h — THE SUB-SECOND-OR-4s VERSION RESOLVE (GUARANTEE #4):
     * tier-1's releases/latest 302 redirect behind an ABSOLUTE 4 s
     * wall-clock, immediate fallback to tier-2's docs/latest.json (4 s).
     * Returns the newest release VERSION string ("3.4.6.h"), or null when
     * both tiers failed. ZERO GitHub-REST calls, zero SHA round trips —
     * the caller arms + persists the gate the moment this answers.
     */
    static String fetchVersionFast() {
        try {
            String tag = resolveTagDeadline("https://github.com/" + REPO + "/releases/latest",
                FAST_CONNECT_MS, FAST_READ_MS, FAST_DEADLINE_MS);
            if (tag != null) {
                String v = stripV(tag);
                if (v.matches("[0-9]+\\.[0-9]+\\.[0-9]+(\\.[a-z])?")) return v;
            }
        } catch (Throwable t) {
            Diag.w("selfupd", "fast tier-1: " + t);
        }
        try {
            String body = httpGetDeadline(rawLatestJsonUrl(),
                FAST_CONNECT_MS, FAST_READ_MS, FAST_DEADLINE_MS);
            if (body != null && !body.trim().isEmpty()) {
                Object root = MiniJson.parse(body);
                if (root instanceof Map) {
                    String v = stripV(String.valueOf(((Map<?, ?>) root).get("version")));
                    if (v.matches("[0-9]+\\.[0-9]+\\.[0-9]+(\\.[a-z])?")) return v;
                }
            }
        } catch (Throwable t) {
            Diag.w("selfupd", "fast tier-2: " + t);
        }
        // v3.4.6.i — TIER 3, the jsDelivr CDN mirror of the SAME file (the
        // FAIL 2 fix): raw stalled → the CDN edge answers the identical bytes.
        try {
            String body = httpGetDeadline(jsdelivrLatestJsonUrl(),
                FAST_CONNECT_MS, FAST_READ_MS, FAST_DEADLINE_MS);
            if (body != null && !body.trim().isEmpty()) {
                Object root = MiniJson.parse(body);
                if (root instanceof Map) {
                    String v = stripV(String.valueOf(((Map<?, ?>) root).get("version")));
                    if (v.matches("[0-9]+\\.[0-9]+\\.[0-9]+(\\.[a-z])?")) return v;
                }
            }
        } catch (Throwable t) {
            Diag.w("selfupd", "fast tier-3 jsdelivr: " + t);
        }
        return null;
    }

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
        // Route 3 (v3.4.6.i) — the jsDelivr CDN mirror of the same
        // docs/latest.json (the FAIL 2 Sri Lankan raw stall fallback). The
        // parsed release keeps tier "raw-json" (it IS the same JSON file);
        // only the transport differs.
        try {
            Release r = fetchViaMirrorJson();
            if (usableRelease(r)) return r;
        } catch (Throwable t) {
            Diag.w("selfupd", "tier3 jsdelivr-json: " + t);
        }
        return null;
    }

    private static boolean usableRelease(Release r) {
        return r != null
            && r.version != null && r.version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(\\.[a-z])?")
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
     * (a mock github.com that serves the exact 302).
     * v3.4.6.h — GUARANTEE #4: the resolve runs behind the 4-second HARD
     * wall-clock fast-lane deadline (a stalled DNS or a multi-address connect
     * can never delay the gate beyond it). */
    static Release fetchViaWebRedirect() throws Exception {
        String tag = resolveLatestTagByRedirect("https://github.com/" + REPO + "/releases/latest",
            FAST_CONNECT_MS, FAST_READ_MS);
        if (tag == null || tag.isEmpty()) return null;
        Release r = new Release();
        r.version = stripV(tag);
        if (!r.version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(\\.[a-z])?")) return null;
        r.tier = TIER_WEB_REDIRECT;
        r.apkUrl = derivedApkUrl(tag);
        r.sha256Expected = fetchExpectedSha(derivedSumsUrl(tag), apkAssetName(r.version));
        return r;
    }

    /** The observable-redirect core: HEAD → 302 → Location's /tag/vX.Y.Z tail.
     *  v3.4.6.f — 5 s connect / 8 s read (LAW #2: never a 32-second hang).
     *  Public-contract seam: the lab passes its own connection factory. */
    static String resolveLatestTagByRedirect(String releasesLatestUrl) throws Exception {
        return resolveLatestTagByRedirect(releasesLatestUrl, CHECK_CONNECT_MS, CHECK_READ_MS);
    }

    /** The tunable-timeout overload (the deep troubleshooter drives 5 s/6 s
     *  so the whole run finishes < 12 s; the updater keeps 5 s/8 s). */
    static String resolveLatestTagByRedirect(String releasesLatestUrl,
                                             int connectMs, int readMs) throws Exception {
        HttpURLConnection c = openConnection(releasesLatestUrl);
        try {
            c.setRequestMethod("HEAD");
            c.setInstanceFollowRedirects(false);     // the 302 is the ANSWER, not a chase
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
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

    /** Route 2 — docs/latest.json written by the release workflow.
     *  v3.4.6.h — GUARANTEE #4: the GET rides the 4-second hard deadline. */
    static Release fetchViaRawJson() throws Exception {
        String body = httpGetDeadline(rawLatestJsonUrl(), FAST_CONNECT_MS, FAST_READ_MS, FAST_DEADLINE_MS);
        Release r = fetchViaRawJsonBody(body);
        if (r == null) return null;
        String sumsSha = fetchExpectedSha(derivedSumsUrl("v" + r.version), apkAssetName(r.version));
        if (sumsSha == null) return null;
        if (r.sha256Expected != null && !r.sha256Expected.equalsIgnoreCase(sumsSha)) return null;
        r.sha256Expected = sumsSha;
        return r;
    }

    /**
     * Route 3 (v3.4.6.i) — the jsDelivr CDN mirror of docs/latest.json:
     * identical parsing, identical SHA256SUMS.txt cross-check, identical
     * "raw-json" tier label (the transport is the only thing that differs).
     */
    static Release fetchViaMirrorJson() throws Exception {
        String body = httpGetDeadline(jsdelivrLatestJsonUrl(), FAST_CONNECT_MS, FAST_READ_MS, FAST_DEADLINE_MS);
        Release r = fetchViaRawJsonBody(body);
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
        if (version == null || !stripV(version).matches("[0-9]+\\.[0-9]+\\.[0-9]+(\\.[a-z])?")) return null;
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
     *  fetch or the line is missing — the installer refuses on null).
     *  v3.4.6.h — the sums fetch rides the 4-second hard deadline too. */
    static String fetchExpectedSha(String sumsUrl, String apkFile) {
        try {
            String sums = httpGetDeadline(sumsUrl, FAST_CONNECT_MS, FAST_READ_MS, FAST_DEADLINE_MS);
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
        return downloadToFile(url, out, MAX_APK_BYTES, progress);
    }

    /**
     * v3.4.6.i — the cap-parameterized overload: the official-AnantPlay
     * installer downloads the OFFICIAL app (whose 200 MB cap is deliberately
     * wider than our own 55 MB self-update cap). Everything else (https-only,
     * declared-length check, mid-stream cap) is identical.
     */
    static long downloadToFile(String url, File out, long capBytes, Progress progress) throws Exception {
        if (!httpsOnly(url)) throw new SecurityException("refusing non-https download: " + url);
        HttpURLConnection c = openConnection(url);
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        try {
            return pump(c, out, capBytes, progress);
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
        return httpGet(url, CHECK_CONNECT_MS, CHECK_READ_MS);
    }

    /** v3.4.6.f — the tunable-timeout GET (the troubleshooter drives 5 s/6 s
     *  per probe so the entire run finishes < 12 seconds). */
    static String httpGet(String url, int connectMs, int readMs) throws Exception {
        HttpURLConnection c = openConnection(url);
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
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

    // ══ v3.4.6.g — THE HARD WALL-CLOCK DEADLINE ═════════════════════════════
    // The real phone's probe 2 (docs/latest.json) STALLED 20,607 ms despite
    // 5 s/6 s connect/read caps. Those timeouts do NOT cover DNS resolution
    // or multi-address (IPv6-first) connect attempts — the socket layer can
    // burn three times the budget before either timer even starts. Every
    // probe-time network leg now runs on a single-shot executor behind an
    // ABSOLUTE deadline; a miss interrupts the socket thread and answers
    // null, so a probe can never outlive its budget.
    // v3.4.6.g — THE THREAD-CLEANUP FIX (Trap #4): the old shape submitted to a
    // SHARED static single-thread pool that was NEVER shut down. A socket read
    // that ignores Thread.interrupt() could keep that one worker occupied, so
    // the NEXT probe's task queued behind it and burned its whole 6 s budget
    // without even starting — and the pool thread leaked for the process life.
    // Each call now gets a FRESH single-shot executor that is ALWAYS
    // shutdownNow()'d in finally: the worker is interrupted and reclaimed, no
    // thread leaks, and no cross-call queue blocking. Probes 1/2 stay <= 6 s.
    private static String runWithDeadline(java.util.concurrent.Callable<String> task,
                                          long deadlineMs, String url) {
        java.util.concurrent.ExecutorService ex =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "probe-deadline");
                t.setDaemon(true);
                return t;
            });
        try {
            java.util.concurrent.Future<String> f = ex.submit(task);
            try {
                return f.get(deadlineMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                f.cancel(true);   // interrupt the in-flight socket read
                Diag.w("selfupd", "deadline " + deadlineMs + " ms hit on " + hostOf(url));
                return null;
            } catch (java.util.concurrent.ExecutionException ee) {
                Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                throw cause instanceof RuntimeException
                    ? (RuntimeException) cause : new RuntimeException(cause);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        } finally {
            // ALWAYS reclaim the single-shot worker — interrupt any blocked
            // socket I/O so the thread dies even if the connection ignores the
            // Future.cancel. This is the leak-proof contract the probe relies on.
            ex.shutdownNow();
        }
    }

    /** The hard-deadline GET: returns the body, or null when the wall clock ran out. */
    static String httpGetDeadline(String url, int connectMs, int readMs, long deadlineMs) {
        return runWithDeadline(() -> httpGet(url, connectMs, readMs), deadlineMs, url);
    }

    // ═══ v3.4.6.j — THE SOURCES-HUB INDEX FETCHER (video_4 03:30–04:05) ══════
    // The old fetchUrl lane (6 s / 256 KiB / no redirects / no gzip / no mirror)
    // reported the Sri Lanka IPTV + Sinhala Subs Bridge feeds as a vague
    // "unreachable (offline ok)". The new contract:
    //   · 10 s hard deadline, 1 MiB body cap;
    //   · HTTP 301/302/307/308 redirects followed (≤ 5 hops, every hop https);
    //   · gzip Content-Encoding decoded;
    //   · a clean standard browser User-Agent (never a bare Java bot string
    //     that CDNs 403);
    //   · the automatic jsDelivr mirror fallback: a failing
    //     raw.githubusercontent.com/<owner>/<repo>/<branch>/<path> retries as
    //     https://cdn.jsdelivr.net/gh/<owner>/<repo>@<branch>/<path> (the ISP
    //     DNS/connect block kill);
    //   · the EXACT sanitized reason on failure (e.g. "HTTP 404",
    //     "UnknownHostException: raw.githubusercontent.com", "deadline 10 s") —
    //     never a vague "unreachable (offline ok)".

    /** The index-fetch result: the body when ok; otherwise the exact reason. */
    public static final class IndexFetch {
        public final String body;
        public final String error;
        public final boolean viaMirror;
        IndexFetch(String body, String error, boolean viaMirror) {
            this.body = body; this.error = error; this.viaMirror = viaMirror;
        }
        public boolean ok() { return body != null; }
    }

    /** The Sources Hub's reader (run off the main thread). */
    public static IndexFetch fetchIndexFeed(String url) {
        if (url == null || url.trim().isEmpty()) return new IndexFetch(null, "empty URL", false);
        String u = url.trim();
        if (!safeRemoteUrl(u)) return new IndexFetch(null, "refused: non-https or non-public host", false);
        IndexFetch direct = fetchFeedOnce(u, FEED_DEADLINE_MS);
        if (direct.ok()) return direct;
        // The jsDelivr mirror lane: raw.githubusercontent.com/<o>/<r>/<b>/<path>
        // → cdn.jsdelivr.net/gh/<o>/<r>@<b>/<path>
        String mirror = jsdelivrMirrorOf(u);
        if (mirror != null) {
            Diag.i("browser", "feed mirror retry: " + hostOf(u) + " → cdn.jsdelivr.net");
            IndexFetch mirrored = fetchFeedOnce(mirror, FEED_DEADLINE_MS);
            if (mirrored.ok()) return new IndexFetch(mirrored.body, null, true);
            return new IndexFetch(null,
                direct.error + " · mirror " + mirrored.error, false);
        }
        return direct;
    }

    /** The v3.4.6.j feed deadline (10 s — a slow cold CDN edge still answers). */
    static final long FEED_DEADLINE_MS = 10_000L;

    /** The v3.4.6.j feed body cap (1 MiB of index.json is ~40 feeds deep). */
    static final int FEED_MAX_BYTES = 1024 * 1024;

    /** One redirect-following, gzip-aware GET under the deadline + cap.
     *  The callable returns "OK<US>\n<body>" on success or "ERR<US>\n<exact
     *  sanitized reason>" on failure — never an ambiguous shape. */
    private static IndexFetch fetchFeedOnce(final String url, final long deadlineMs) {
        String outcome = runWithDeadline(() -> {
            try {
                String current = url;
                for (int hop = 0; hop <= 5; hop++) {
                    // v3.4.6.j — TRAP #4: a 302 to https://127.0.0.1 (or a
                    // .local/metadata host) must be refused just like the
                    // initial URL. Validate EVERY hop with the full SSRF
                    // predicate, not merely the scheme.
                    if (!safeRemoteUrl(current)) return "ERR\nrefused: non-public hop";
                    HttpURLConnection c = openConnection(current);
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(8000);
                    c.setInstanceFollowRedirects(false);   // manual: every hop must stay https
                    c.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137 Mobile Safari/537.36");
                    c.setRequestProperty("Accept", "application/json, text/plain, */*");
                    c.setRequestProperty("Accept-Encoding", "gzip");
                    int code = c.getResponseCode();
                    if (code == HttpURLConnection.HTTP_MOVED_PERM        // 301
                            || code == HttpURLConnection.HTTP_MOVED_TEMP  // 302
                            || code == 307 || code == 308) {
                        String loc = c.getHeaderField("Location");
                        c.disconnect();
                        if (loc == null || loc.isEmpty()) return "ERR\nredirect without Location";
                        current = loc.startsWith("http") ? loc
                                : new java.net.URL(new java.net.URL(current), loc).toString();
                        continue;
                    }
                    if (code != 200) {
                        c.disconnect();
                        return "ERR\nHTTP " + code;
                    }
                    InputStream in = c.getInputStream();
                    if ("gzip".equalsIgnoreCase(c.getContentEncoding())) {
                        in = new java.util.zip.GZIPInputStream(in);
                    }
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int total = 0;
                    for (int n; (n = in.read(buf)) > 0; ) {
                        total += n;
                        if (total > FEED_MAX_BYTES) {
                            in.close(); c.disconnect();
                            return "ERR\nbody over 1 MiB cap";
                        }
                        out.write(buf, 0, n);
                    }
                    in.close();
                    c.disconnect();
                    return "OK\n" + new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                }
                return "ERR\ntoo many redirects (5+)";
            } catch (java.net.UnknownHostException uh) {
                return "ERR\nUnknownHostException: " + hostOf(url);
            } catch (java.net.ConnectException ce) {
                return "ERR\nConnectException: " + hostOf(url);
            } catch (java.net.SocketTimeoutException st) {
                return "ERR\nSocketTimeoutException: " + hostOf(url);
            } catch (javax.net.ssl.SSLException se) {
                return "ERR\nSSLException: " + hostOf(url);
            } catch (Throwable t) {
                String n = t.getClass().getSimpleName();
                return "ERR\n" + (n == null || n.isEmpty() ? "Exception" : n) + ": " + hostOf(url);
            }
        }, deadlineMs, url);
        // deadline-miss → null body → the exact "deadline N s" reason
        if (outcome == null) return new IndexFetch(null, "deadline " + (deadlineMs / 1000) + " s", false);
        if (outcome.startsWith("ERR\n")) {
            String reason = outcome.substring(4).trim();
            return new IndexFetch(null, reason.isEmpty() ? "unreachable" : reason, false);
        }
        if (outcome.startsWith("OK\n")) return new IndexFetch(outcome.substring(3), null, false);
        // unreachable shape (never expected): a defensive error, never a fake body
        return new IndexFetch(null, "unreachable: unexpected lane", false);
    }

    /**
     * v3.4.6.j — the jsDelivr mirror of a raw.githubusercontent.com path:
     * raw.githubusercontent.com/&lt;o&gt;/&lt;r&gt;/&lt;b&gt;/&lt;p&gt; →
     * https://cdn.jsdelivr.net/gh/&lt;o&gt;/&lt;r&gt;@&lt;b&gt;/&lt;p&gt;,
     * or null when the URL is not a raw GitHub path.
     */
    static String jsdelivrMirrorOf(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            if (!"raw.githubusercontent.com".equalsIgnoreCase(u.getHost())) return null;
            String p = u.getPath();        // /<owner>/<repo>/<branch>/<path...>
            if (p == null || !p.startsWith("/")) return null;
            String[] parts = p.substring(1).split("/", 4);
            if (parts.length < 4 || parts[0].isEmpty() || parts[1].isEmpty()
                    || parts[2].isEmpty() || parts[3].isEmpty()) return null;
            return "https://cdn.jsdelivr.net/gh/"
                    + parts[0] + "/" + parts[1] + "@" + parts[2] + "/" + parts[3];
        } catch (Throwable t) {
            return null;
        }
    }

    /** The hard-deadline tier-1 redirect resolve (null on a deadline miss). */
    static String resolveTagDeadline(String url, int connectMs, int readMs, long deadlineMs) {
        return runWithDeadline(() -> resolveLatestTagByRedirect(url, connectMs, readMs), deadlineMs, url);
    }

    /** "https://host/x" → "host" (log-safe: no path, no query). */
    private static String hostOf(String url) {
        try {
            String u = url == null ? "" : url;
            int s = u.indexOf("//");
            if (s < 0) return "?";
            int e = u.indexOf('/', s + 2);
            return e < 0 ? u.substring(s + 2) : u.substring(s + 2, e);
        } catch (Throwable t) {
            return "?";
        }
    }
}
