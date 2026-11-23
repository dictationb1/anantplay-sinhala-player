package lk.techworld.anantplay.sicast;

import java.util.Locale;

/**
 * v3.4.3 — THE WEBVIEW BRIDGE TRUST POLICY (pure Java 8, no Android types).
 *
 * <p>WHY THIS EXISTS (Replit QA-0008 / QA-0011):
 *
 * <p>v3.4.1 added a {@code @JavascriptInterface} method that accepts a base64 subtitle
 * Blob from the page. {@code @JavascriptInterface} methods are reachable by <b>every
 * frame on every page the WebView shows</b> — an ad iframe on a cinema site included —
 * and the v3.4.1 handler accepted whatever arrived as long as it was under the size
 * cap. That is three separate problems in one method:
 *
 * <ol>
 *   <li><b>No origin check.</b> A hostile iframe could hand us a subtitle file that the
 *       user then sees rendered over the movie — spoofed text on screen.</li>
 *   <li><b>No gesture / session window.</b> A page could post blobs in a loop with no
 *       user action at all.</li>
 *   <li><b>No single-flight limit.</b> Twenty concurrent 5 MB payloads meant twenty
 *       concurrent base64 decodes — a memory spike the user pays for, not the page.</li>
 * </ol>
 *
 * <p>This class is the policy those checks live in. It is deliberately framework-free so
 * {@code bash lab/run_lab.sh} can prove every rule instead of trusting a comment:
 * the pre-decode cap, the top-document origin match, the capture window and the
 * single-flight rate limit. {@link WebBrowserView} only calls it.
 */
final class BridgeGuard {

    /** The largest Blob (decoded bytes) we will carry across the bridge. */
    static final int BLOB_CAP = 5 * 1024 * 1024;

    /** The largest base64 STRING that can encode {@link #BLOB_CAP} bytes (pre-decode). */
    static final int B64_MAX_CHARS = BLOB_CAP * 4 / 3 + 16;

    /** Two accepted blobs must be at least this far apart (single-flight rate limit). */
    static final long MIN_BLOB_INTERVAL_MS = 1500L;

    /** A user tap opens a capture session that stays valid this long. */
    static final long CAPTURE_WINDOW_MS = 120000L;

    private BridgeGuard() { }

    /** True for an http(s) URL — the only schemes the Browser tab ever shows. */
    static boolean isHttpUrl(String u) {
        if (u == null) return false;
        String s = u.trim().toLowerCase(Locale.US);
        return s.startsWith("http://") || s.startsWith("https://");
    }

    /**
     * The DECODED size a base64 string would produce, computed WITHOUT allocating the
     * bytes (this is what makes the cap a pre-decode gate rather than a post-hoc one).
     */
    static int estimatedBytes(String base64) {
        if (base64 == null || base64.isEmpty()) return 0;
        int chars = 0, pad = 0;
        for (int i = 0; i < base64.length(); i++) {
            char c = base64.charAt(i);
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') continue;   // Base64.DEFAULT wraps
            if (c == '=') pad++;
            chars++;                    // the padding IS part of the 4-char group
        }
        long bytes = (long) chars * 3L / 4L - pad;
        if (bytes < 0) return 0;
        return bytes > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) bytes;
    }

    /**
     * THE PRE-DECODE CAP: true only when this payload is inside {@link #BLOB_CAP}.
     * Checked on the STRING length first (cheapest), then on the estimated byte count,
     * so an oversized payload is refused before a single byte is allocated.
     */
    static boolean withinCap(String base64) {
        if (base64 == null || base64.isEmpty()) return false;
        if (base64.length() > B64_MAX_CHARS) return false;
        int bytes = estimatedBytes(base64);
        return bytes > 0 && bytes <= BLOB_CAP;
    }

    /** {@code scheme://host[:port]} of a URL, lower-cased, or null when it has none. */
    static String originOf(String url) {
        if (url == null) return null;
        String u = url.trim();
        int se = u.indexOf("://");
        if (se <= 0) return null;
        String scheme = u.substring(0, se).toLowerCase(Locale.US);
        if (!scheme.equals("http") && !scheme.equals("https")) return null;
        int start = se + 3;
        int end = start;
        while (end < u.length()) {
            char c = u.charAt(end);
            if (c == '/' || c == '?' || c == '#') break;
            end++;
        }
        if (end <= start) return null;
        String authority = u.substring(start, end).toLowerCase(Locale.US);
        int at = authority.lastIndexOf('@');                 // never trust a userinfo part
        if (at >= 0) authority = authority.substring(at + 1);
        if (authority.isEmpty()) return null;
        return scheme + "://" + authority;
    }

    /**
     * THE TOP-DOCUMENT ORIGIN CHECK: the frame that posted a Blob must be the very
     * document the WebView is showing. A sub-frame (ad iframe, tracker, injected
     * widget) has a different origin and is refused.
     */
    static boolean originMatches(String pageUrl, String claimedUrl) {
        String a = originOf(pageUrl);
        String b = originOf(claimedUrl);
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    /**
     * THE USER-GESTURE / CAPTURE-SESSION WINDOW: a tap inside the WebView opens a
     * window in which the page may hand over a Blob. Outside it, nothing is accepted —
     * a background page cannot push subtitles into a movie the user is watching.
     */
    static boolean captureWindowOpen(long nowMs, long gestureAtMs) {
        if (gestureAtMs <= 0) return false;
        long delta = nowMs - gestureAtMs;
        return delta >= 0 && delta <= CAPTURE_WINDOW_MS;
    }

    /** THE SINGLE-FLIGHT RATE LIMIT: true when the previous blob is too recent. */
    static boolean rateLimited(long nowMs, long lastAcceptedMs) {
        if (lastAcceptedMs <= 0) return false;
        long delta = nowMs - lastAcceptedMs;
        return delta >= 0 && delta < MIN_BLOB_INTERVAL_MS;
    }

    /**
     * THE WHOLE GATE, in one call — every rule above, in the order that refuses
     * cheapest first. {@code WebBrowserView.onBlobDownload} is a thin wrapper.
     *
     * @param pageUrl      the WebView's current top-document URL (null when unknown)
     * @param claimedUrl   the URL the JS shim reported for itself
     * @param base64       the payload (not yet decoded)
     * @param nowMs        uptime millis at entry
     * @param gestureAtMs  uptime millis of the last user tap (0 = never)
     * @param lastBlobMs   uptime millis of the last accepted blob (0 = none)
     * @param inFlight     true while another decode is still running
     * @return "" when the payload may be decoded, else the exact refusal reason
     */
    static String refuseReason(String pageUrl, String claimedUrl, String base64,
                               long nowMs, long gestureAtMs, long lastBlobMs, boolean inFlight) {
        if (inFlight) return "single-flight: a blob decode is already running";
        if (!originMatches(pageUrl, claimedUrl))
            return "origin: frame " + originOf(claimedUrl) + " is not the top document "
                + originOf(pageUrl);
        if (!captureWindowOpen(nowMs, gestureAtMs))
            return "gesture: no user interaction inside the capture window";
        if (rateLimited(nowMs, lastBlobMs))
            return "rate: another blob landed less than " + MIN_BLOB_INTERVAL_MS + " ms ago";
        if (!withinCap(base64))
            return "cap: payload is outside the " + BLOB_CAP + "-byte pre-decode limit";
        return "";
    }
}
