package lk.techworld.anantplay.sicast;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v3.3.0 — THE HTTP-403 HEADER ENGINE (pure Java, lab-provable).
 *
 * <p>THE BUG THIS KILLS (v3.2.0 screenshots 2 &amp; 3):
 * {@code ERROR_CODE_IO_BAD_HTTP_STATUS · Response code: 403} on a stream that plays
 * perfectly inside official AnantPlay. Comparing our v3.2.0
 * {@code PlayActivity.startPlayback()} with official
 * {@code AnantPlay/android PlayerActivity.java:1087-1103} line by line shows four
 * concrete differences, and every one of them produces a 403 on a token-checking CDN:
 *
 * <ol>
 *   <li><b>UA</b>. Official builds the data source as
 *       {@code new DefaultHttpDataSource.Factory().setUserAgent(ua)} with
 *       {@code ua = "Mozilla/5.0 (Linux; Android 14) ... Chrome/130.0 Mobile Safari/537.36"}
 *       and REMOVES User-Agent from the request-properties map
 *       ({@code if (k.equalsIgnoreCase("User-Agent")) ua = ...; else headers.put(...)}).
 *       We put a truncated stub — {@code "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"},
 *       no Chrome token, no Mobile, no Safari — straight into the request properties,
 *       and never called {@code setUserAgent()}. Cloudflare / VidSrc / Prism style
 *       CDNs answer a non-browser UA (and Media3's own default) with 403.</li>
 *   <li><b>Origin</b>. Official forwards the cast headers verbatim, so Origin arrives
 *       from the phone player. A cast that carries only a Referer loses the
 *       Origin the CDN's CORS/token check wants.</li>
 *   <li><b>Cookie per request host</b>. Official wraps the factory in a
 *       {@code ResolvingDataSource} that asks {@code CookieManager} for the cookie
 *       of EACH request's own host ({@code PlayerActivity.java:1099-1103}); we sent
 *       one cast cookie to every CDN segment host and had no WebView-cookie fallback.</li>
 *   <li><b>No recovery leg</b>. Official logs the failing request's status + header
 *       keys. We showed a red box and stopped, so a recoverable 403 was terminal.</li>
 * </ol>
 *
 * <p>{@link #normalize} is the single place that turns any cast/WebView header soup
 * into a canonical, browser-grade header map; {@link #recovery} is the one-shot
 * 403 fallback (Origin derived from Referer + Chrome UA + Accept). Both are pure
 * functions of their arguments, which is what makes the {@code v330 http403:}
 * regression lab able to prove them against a mock server that answers 403 unless
 * the exact headers arrive.
 */
final class StreamHttpConfig {

    /**
     * The browser-grade UA used whenever the stream supplied none (or supplied one a
     * CDN blocks). This is the exact shape official AnantPlay's PlayerActivity ships,
     * pinned to a modern Chrome Mobile build.
     */
    static final String CHROME_UA = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

    /** Header names that must never be forwarded verbatim (connection-scoped / Media3-owned). */
    private static final String[] DROP = {
        "host", "connection", "content-length", "transfer-encoding", "keep-alive",
        "proxy-connection", "upgrade", "te", "trailer",
    };

    /** lowercase → the canonical casing a CDN expects to see. */
    private static final String[][] CANON = {
        {"user-agent", "User-Agent"},
        {"referer", "Referer"},
        {"referrer", "Referer"},
        {"origin", "Origin"},
        {"cookie", "Cookie"},
        {"accept", "Accept"},
        {"accept-language", "Accept-Language"},
        {"accept-encoding", "Accept-Encoding"},
        {"authorization", "Authorization"},
        {"range", "Range"},
        {"if-range", "If-Range"},
        {"content-type", "Content-Type"},
        {"x-requested-with", "X-Requested-With"},
        {"sec-fetch-dest", "Sec-Fetch-Dest"},
        {"sec-fetch-mode", "Sec-Fetch-Mode"},
        {"sec-fetch-site", "Sec-Fetch-Site"},
        {"sec-fetch-user", "Sec-Fetch-User"},
        {"sec-ch-ua", "Sec-CH-UA"},
        {"sec-ch-ua-mobile", "Sec-CH-UA-Mobile"},
        {"sec-ch-ua-platform", "Sec-CH-UA-Platform"},
    };

    private StreamHttpConfig() { }

    // ───────────────────────────────────────────────────────────────────────────
    // parsing
    // ───────────────────────────────────────────────────────────────────────────

    /**
     * Parse whatever shape the cast/WebView produced into a flat header map.
     *
     * <p>Handles all three shapes AnantPlay can send (Cast.describe copies the phone
     * player's intent extras verbatim, so "headers" is a JSON STRING of an object):
     * a JSON object of headers, a nested {@code {"headers":{...}}} object, a nested
     * {@code "headers"} string, plus the top-level aliases {@code ua / referer /
     * referrer / origin / cookie / authorization}.
     */
    static Map<String, String> parseHeaders(String headersJson) {
        Map<String, String> out = new LinkedHashMap<>();
        if (headersJson == null) return out;
        String s = headersJson.trim();
        if (s.isEmpty() || s.equals("null") || s.equals("{}")) return out;
        Object parsed = null;
        try { parsed = MiniJson.parse(s); } catch (Throwable ignored) { return out; }
        if (!(parsed instanceof Map)) return out;
        absorb(out, (Map<?, ?>) parsed, true);
        return out;
    }

    /** Copy one JSON object's entries into {@code out}, following the nested shapes. */
    private static void absorb(Map<String, String> out, Map<?, ?> m, boolean allowAliases) {
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String k = String.valueOf(e.getKey());
            Object v = e.getValue();
            if (v == null) continue;
            if ("headers".equalsIgnoreCase(k)) {
                if (v instanceof Map) {
                    absorb(out, (Map<?, ?>) v, false);
                } else if (v instanceof String) {
                    Map<String, String> nested = parseHeaders(String.valueOf(v));
                    for (Map.Entry<String, String> ne : nested.entrySet()) put(out, ne.getKey(), ne.getValue());
                }
                continue;
            }
            if (v instanceof Map || v instanceof List) continue;          // never a header value
            if (allowAliases) {
                String alias = aliasOf(k);
                if (alias != null) { put(out, alias, String.valueOf(v)); continue; }
            }
            if (looksLikeHeader(k)) put(out, k, String.valueOf(v));
        }
    }

    /** The cast JSON's top-level aliases → the header they mean. */
    private static String aliasOf(String k) {
        String l = k.toLowerCase(Locale.US);
        switch (l) {
            case "ua": case "useragent": case "user_agent": return "User-Agent";
            case "referer": case "referrer": return "Referer";
            case "origin": return "Origin";
            case "cookie": return "Cookie";
            case "authorization": case "auth": return "Authorization";
            default: return null;
        }
    }

    /** Anything with a dash, or one of the well-known bare header names, is a header. */
    private static boolean looksLikeHeader(String k) {
        if (k == null || k.isEmpty() || k.length() > 64) return false;
        String l = k.toLowerCase(Locale.US);
        if (l.indexOf('-') >= 0) return true;
        switch (l) {
            case "accept": case "cookie": case "origin": case "referer": case "authorization":
            case "range": case "via": case "dnt": case "pragma": case "cache-control":
                return true;
            default: return false;
        }
    }

    // ───────────────────────────────────────────────────────────────────────────
    // normalize / recovery
    // ───────────────────────────────────────────────────────────────────────────

    /** Canonical, browser-grade headers for the first playback attempt. */
    static Map<String, String> normalize(String headersJson, String cookie, String url) {
        return normalize(headersJson, cookie, url, null);
    }

    /**
     * Canonical, browser-grade headers for the first playback attempt.
     *
     * @param headersJson the cast/WebView "headers" payload (any shape)
     * @param cookie      the cast's top-level cookie (merged, never duplicated)
     * @param url         the stream URL (for the Origin/Referer defaults)
     * @param extra       extra top-level aliases from the cast (ua/referer/origin), may be null
     */
    static Map<String, String> normalize(String headersJson, String cookie, String url,
                                         Map<String, String> extra) {
        Map<String, String> parsed = parseHeaders(headersJson);
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : parsed.entrySet()) {
            String v = e.getValue();
            if (v == null) continue;
            String name = canonical(e.getKey());
            if (dropped(name)) continue;
            if (v.trim().isEmpty() && !"Cookie".equals(name)) continue;
            out.put(name, v.trim());
        }
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                String name = canonical(e.getKey());
                if (dropped(name) || e.getValue().trim().isEmpty()) continue;
                if (!out.containsKey(name)) out.put(name, e.getValue().trim());
            }
        }

        // 1. the UA — a CDN blocks anything that is not a real browser
        String ua = out.get("User-Agent");
        if (ua == null || isBlockedUa(ua)) out.put("User-Agent", CHROME_UA);

        // 2. the cookie — the cast's top-level cookie wins over nothing, never duplicates
        if (cookie != null && !cookie.trim().isEmpty()) {
            String existing = out.get("Cookie");
            if (existing == null || existing.trim().isEmpty()) out.put("Cookie", cookie.trim());
            else if (!existing.contains(cookie.trim())) out.put("Cookie", existing.trim() + "; " + cookie.trim());
        }

        // 3. Origin derived from the Referer (the token check that 403s without it)
        if (!out.containsKey("Origin")) {
            String o = originOf(out.get("Referer"));
            if (o == null) o = originOf(url);
            if (o != null) out.put("Origin", o);
        }

        // 4. the browser defaults every CDN expects
        if (!out.containsKey("Accept")) out.put("Accept", "*/*");
        if (!out.containsKey("Accept-Language")) out.put("Accept-Language", "en-US,en;q=0.9");
        return out;
    }

    /**
     * The ONE automatic retry after an HTTP 403/401: force the browser UA, derive the
     * Origin from the Referer, add {@code Accept: *&#47;*} and the Sec-Fetch triple a
     * real Chrome sends. Deterministic, so the lab can assert it exactly.
     */
    static Map<String, String> recovery(String url, Map<String, String> first) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (first != null) out.putAll(first);
        out.put("User-Agent", CHROME_UA);
        String referer = out.get("Referer");
        if (referer == null || referer.trim().isEmpty()) {
            String o = originOf(url);
            if (o != null) { referer = o + "/"; out.put("Referer", referer); }
        }
        String origin = originOf(referer);
        if (origin != null) out.put("Origin", origin);
        out.put("Accept", "*/*");
        if (!out.containsKey("Accept-Language")) out.put("Accept-Language", "en-US,en;q=0.9");
        out.put("Sec-Fetch-Dest", "video");
        out.put("Sec-Fetch-Mode", "no-cors");
        out.put("Sec-Fetch-Site", "cross-site");
        return out;
    }

    /** True when the status is worth the one automatic header-recovery retry. */
    static boolean recoverable(int responseCode) {
        return responseCode == 401 || responseCode == 403 || responseCode == 451;
    }

    /** A UA a streaming CDN refuses: Media3's own default, Dalvik, or a truncated stub. */
    static boolean isBlockedUa(String ua) {
        if (ua == null || ua.trim().isEmpty()) return true;
        String l = ua.toLowerCase(Locale.US);
        if (l.contains("androidxmedia3") || l.contains("exoplayer")) return true;
        if (l.startsWith("dalvik") || l.contains("okhttp")) return true;
        if (l.startsWith("stagefright")) return true;
        // our own v3.2.0 stub: "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36" — no Chrome token
        return l.contains("mozilla/") && !l.contains("chrome/") && !l.contains("firefox/");
    }

    /** {@code scheme://host[:port]} of a URL, or null. */
    static String originOf(String url) {
        if (url == null) return null;
        String u = url.trim();
        int schemeEnd = u.indexOf("://");
        if (schemeEnd <= 0) return null;
        String scheme = u.substring(0, schemeEnd).toLowerCase(Locale.US);
        if (!scheme.equals("http") && !scheme.equals("https")) return null;
        int start = schemeEnd + 3;
        int end = u.length();
        for (int i = start; i < u.length(); i++) {
            char c = u.charAt(i);
            if (c == '/' || c == '?' || c == '#') { end = i; break; }
        }
        String authority = u.substring(start, end);
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);          // never log credentials
        if (authority.isEmpty()) return null;
        return scheme + "://" + authority;
    }

    /**
     * The header KEY NAMES present (sorted, never values) — what the Troubleshooter
     * records so a future 403 is diagnosable without leaking a cookie or a token.
     */
    static String describe(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return "(none)";
        java.util.TreeSet<String> names = new java.util.TreeSet<>();
        for (String k : headers.keySet()) names.add(k);
        return String.join(", ", names);
    }

    // ───────────────────────────────────────────────────────────────────────────

    private static void put(Map<String, String> out, String k, String v) {
        if (k == null || v == null) return;
        String name = canonical(k);
        if (dropped(name)) return;
        out.put(name, v.trim());
    }

    private static String canonical(String k) {
        String l = k.trim().toLowerCase(Locale.US);
        for (String[] pair : CANON) if (pair[0].equals(l)) return pair[1];
        // Title-Case every dash segment (x-my-header → X-My-Header)
        StringBuilder sb = new StringBuilder(l.length());
        boolean up = true;
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if (c == '-') { sb.append(c); up = true; }
            else { sb.append(up ? Character.toUpperCase(c) : c); up = false; }
        }
        return sb.toString();
    }

    private static boolean dropped(String name) {
        String l = name.toLowerCase(Locale.US);
        for (String d : DROP) if (d.equals(l)) return true;
        return false;
    }
}
