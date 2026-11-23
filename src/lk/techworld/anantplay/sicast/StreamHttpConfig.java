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

        // 3. Origin derived from the Referer (the token check that 403s without it).
        //    v3.4.1 — a LOCAL RELAY URL MUST NEVER SEED AN ORIGIN. Deriving it from the
        //    relay's own address is exactly the fake `Origin: http://192.168.x.x:34783`
        //    the field log recorded; AnantPlay's relay discards client headers anyway
        //    (Chromecast.java:330-341), so the only honest stage-1 request is a CLEAN
        //    one. Stage 2 (relayOriginHeaders) supplies the real decoded upstream origin.
        Relay rl = relay(url);
        if (rl.isRelay) {
            String ref = out.get("Referer");
            if (ref != null && rl.relayOrigin != null && rl.relayOrigin.equals(originOf(ref))) {
                out.remove("Referer");                     // a fake LAN Referer, never forward it
            }
        } else if (!out.containsKey("Origin")) {
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
        // v3.4.1 — for an AnantPlay relay the retry is STAGE 2: the real upstream origin
        // decoded out of the base64 segment, never the phone's LAN address. Swapping
        // headers on a relay request is otherwise pointless — the relay only reads the
        // request line and `Range` (Chromecast.java:330-341).
        Relay rl = relay(url);
        if (rl.isRelay) return relayOriginHeaders(rl, relayClean());
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

    // ───────────────────────────────────────────────────────────────────────────
    // v3.4.1 — THE ANANTPLAY LOCAL RELAY ENGINE (defect #1: the DLNA DASH 403)
    // ───────────────────────────────────────────────────────────────────────────
    //
    // WHAT THE FIELD LOG SHOWED (v3.4.0, "Influencers"):
    //   from dlna:http://<lan-peer>:34783/aHR0cHM6Ly9wcm94eS55ZW5pbWUubmV0/manifest.mpd?url=…&t=<b64>
    //   first attempt  · headers=Accept, Accept-Language, Origin, User-Agent        → HTTP 403
    //   recovery       · headers=… Origin, Referer, Sec-Fetch-*  …                 → HTTP 403
    //
    // WHY IT COULD NEVER WORK, proven against AnantPlay's own relay source:
    //   · Chromecast.Relay.serve() reads ONLY the request line and `Range`
    //     (ref-anantplay Chromecast.java:330-341). Every Origin/Referer/Sec-Fetch we
    //     sent to the relay was discarded on arrival, so our "recovery" retry was
    //     byte-identical to the first attempt — exactly what the phone recorded.
    //   · Relay.open() replays the CAPTURED header map + a cookie for the unwrapped
    //     URL (Chromecast.java:367-383). It never decodes the `t=` payload, so the
    //     signed `Edge-Cache-Cookie` the token describes is never attached.
    //   · Relay.serve() rewrites HLS only (`#EXTM3U`, Chromecast.java:348-354). A DASH
    //     `.mpd` is streamed through BYTE-FOR-BYTE, so its BaseURL/SegmentTemplate
    //     still point at the raw signed CDN — DLNA + DASH is structurally broken.
    //
    // So the fix cannot be another header. It has to be another REQUEST. Three of them.
    //
    //   Stage 1  the relay URL + clean headers (Chrome UA, Accept: */*, and NOTHING
    //            else — no Origin, no Referer, no Sec-Fetch-*) so the relay attaches
    //            its own upstream set with zero conflict;
    //   Stage 2  the relay URL + Origin/Referer derived from the DECODED base64
    //            upstream origin (https://proxy.yenime.net) — never the phone's LAN IP;
    //   Stage 3  the DIRECT upstream URL from `url=`, carrying the token's own
    //            `Edge-Cache-Cookie`, which bypasses the relay and its DASH hole.
    //
    // Everything below is pure: no Android types, no I/O, so the lab can drive it.

    /** A decoded AnantPlay relay URL. {@code isRelay} false = the URL is not a relay. */
    static final class Relay {
        boolean isRelay;
        /** The relay's own origin, e.g. {@code http://192.168.1.7:34783} (for logging only). */
        String relayOrigin;
        /** The decoded base64 origin segment, e.g. {@code https://proxy.yenime.net}. */
        String upstreamOrigin;
        /** The full upstream URL the relay would fetch: origin + path + query. */
        String upstream;
        /** The signed stream inside {@code url=}, or null when the relay URL has none. */
        String direct;
        /** The signed {@code Edge-Cache-Cookie=…} from the {@code t=} payload, or null. */
        String cookie;
        /** The {@code b} base URL from the {@code t=} payload, or null. */
        String base;
        /** The {@code r.slug} movie slug from the {@code t=} payload (e.g.
         *  {@code influencers-MAyBtWlRaw8}), or null — the REAL movie name when the
         *  page title is junk. v3.4.1. */
        String slug;

        boolean hasDirect() { return direct != null && !direct.isEmpty(); }
    }

    /** True when this URL is an AnantPlay local relay (loopback/private IP + base64 origin segment). */
    static boolean isRelay(String url) {
        return relay(url).isRelay;
    }

    /**
     * Decode an AnantPlay relay URL. Safe on anything: a non-relay URL comes back with
     * {@code isRelay == false} and every other field null.
     *
     * <p>Relay shape (ref-anantplay Chromecast.java:302-314):
     * {@code http://<private-ip>:<port>/<base64url(origin)><path>?<query>}.
     */
    static Relay relay(String url) {
        Relay r = new Relay();
        if (url == null) return r;
        String u = url.trim();
        int se = u.indexOf("://");
        if (se <= 0) return r;
        String scheme = u.substring(0, se).toLowerCase(Locale.US);
        if (!scheme.equals("http") && !scheme.equals("https")) return r;
        int hostStart = se + 3;
        int hostEnd = hostStart;
        while (hostEnd < u.length()) {
            char c = u.charAt(hostEnd);
            if (c == '/' || c == '?' || c == '#') break;
            hostEnd++;
        }
        String authority = u.substring(hostStart, hostEnd);
        int colon = authority.lastIndexOf(':');
        String host = colon >= 0 ? authority.substring(0, colon) : authority;
        int port = -1;
        if (colon >= 0) {
            try { port = Integer.parseInt(authority.substring(colon + 1)); } catch (NumberFormatException ignored) { }
        }
        if (!isPrivateHost(host) || port <= 0) return r;          // a public host is never the relay
        if (hostEnd >= u.length() || u.charAt(hostEnd) != '/') return r;
        int segStart = hostEnd + 1;
        int segEnd = u.indexOf('/', segStart);
        String seg = segEnd < 0 ? u.substring(segStart) : u.substring(segStart, segEnd);
        if (seg.isEmpty()) return r;
        String decoded = b64Url(seg);
        if (decoded == null || !(decoded.startsWith("http://") || decoded.startsWith("https://"))) return r;
        r.isRelay = true;
        r.relayOrigin = scheme + "://" + authority;
        r.upstreamOrigin = trimSlash(decoded);
        String rest = segEnd < 0 ? "/" : u.substring(segEnd);
        r.upstream = r.upstreamOrigin + rest;
        // the signed stream + its token, when the relay URL carries them
        String q = query(rest);
        if (q != null) {
            r.direct = percentDecode(param(q, "url"));
            String t = percentDecode(param(q, "t"));   // the token is percent-encoded in the relay URL
            if (t != null) {
                String json = b64Std(t);
                if (json != null && json.indexOf('{') >= 0) {
                    r.cookie = jsonString(json, "c");
                    r.base = jsonString(json, "b");
                    r.slug = jsonString(json, "slug");   // v3.4.1 — the real movie name
                }
            }
        }
        return r;
    }

    /**
     * STAGE 1 — the clean relay pass-through. A Chrome UA and {@code Accept: *}{@code /*}:
     * no Origin, no Referer, no Sec-Fetch-*, so AnantPlay's relay attaches its own
     * captured upstream set with nothing of ours to conflict with.
     */
    static Map<String, String> relayClean() {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        out.put("User-Agent", CHROME_UA);
        out.put("Accept", "*/*");
        out.put("Accept-Language", "en-US,en;q=0.9");
        return out;
    }

    /**
     * STAGE 2 — the derived-upstream recovery: the SAME relay URL, but Origin and
     * Referer come from the decoded base64 upstream origin, never from the relay's
     * own LAN address. Returns {@code clean} unchanged when the URL is not a relay.
     */
    static Map<String, String> relayOriginHeaders(Relay r, Map<String, String> clean) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (clean != null) out.putAll(clean);
        out.put("User-Agent", CHROME_UA);
        out.put("Accept", "*/*");
        if (!out.containsKey("Accept-Language")) out.put("Accept-Language", "en-US,en;q=0.9");
        if (r != null && r.isRelay && r.upstreamOrigin != null) {
            out.put("Origin", r.upstreamOrigin);
            out.put("Referer", r.upstreamOrigin + "/");
        }
        return out;
    }

    /**
     * STAGE 3 — the direct upstream unwrap: the signed stream from {@code url=} with the
     * token's own {@code Edge-Cache-Cookie}, so the relay (and its DASH pass-through
     * hole) is out of the path entirely. Origin/Referer are derived from the token's
     * {@code b} base when it is present, else from the upstream origin.
     */
    static Map<String, String> directHeaders(Relay r) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        out.put("User-Agent", CHROME_UA);
        out.put("Accept", "*/*");
        out.put("Accept-Language", "en-US,en;q=0.9");
        if (r == null) return out;
        if (r.cookie != null && !r.cookie.isEmpty()) out.put("Cookie", r.cookie);
        String org = originOf(r.base != null && !r.base.isEmpty() ? r.base : r.upstreamOrigin);
        if (org != null) {
            out.put("Origin", org);
            out.put("Referer", org + "/");
        }
        return out;
    }

    /** The URL a stage-3 request must hit: the signed stream when we have one, else the upstream. */
    static String directUrl(Relay r) {
        if (r == null || !r.isRelay) return null;
        String u = r.hasDirect() ? r.direct : r.upstream;
        // v3.4.3 (QA-0025) — a token whose `url=` is a RELATIVE path must be resolved
        // against the token's own `b` base (or the decoded upstream origin), never left
        // relative: a relative stage-3 URL is a request for nothing at all.
        if (u != null && schemeOf(u) == null) {
            String base = (r.base != null && !r.base.trim().isEmpty()) ? r.base : r.upstreamOrigin;
            u = resolveRef(base, u);
        }
        return u;
    }

    // ── v3.4.3 — DASH/segment URL resolution (Replit QA-0025) ────────────────
    //
    // Official AnantPlay's DLNA relay proxies HLS and passes DASH through UNTOUCHED
    // (Chromecast.java:348-354), so a manifest it serves still contains RELATIVE
    // <BaseURL> / SegmentTemplate media references. Media3 resolves those against the
    // URL it fetched — which, through the relay, is the relay URL WITHOUT the base64
    // origin segment, so the relay cannot tell which upstream to fetch and answers 404.
    // These three helpers make that arithmetic explicit and provable.

    /** The directory part of a URL (everything up to and including the last '/'). */
    static String dirOf(String url) {
        if (url == null) return null;
        String u = url.trim();
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        int hash = u.indexOf('#');
        if (hash >= 0) u = u.substring(0, hash);
        int se = u.indexOf("://");
        int lastSlash = u.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash <= se + 2) return u + "/";
        return u.substring(0, lastSlash + 1);
    }

    /** Resolve {@code ref} against {@code base} (RFC-3986 style, enough for DASH/HLS). */
    static String resolveRef(String base, String ref) {
        if (ref == null || ref.trim().isEmpty()) return base == null ? "" : base.trim();
        String r = ref.trim();
        String lower = r.toLowerCase(Locale.US);
        if (lower.startsWith("http://") || lower.startsWith("https://")) return r;
        if (base == null || base.trim().isEmpty()) return r;
        String b = base.trim();
        if (r.startsWith("//")) {                       // protocol-relative
            String scheme = schemeOf(b);
            return (scheme == null ? "https" : scheme) + ":" + r;
        }
        if (r.startsWith("/")) {                        // origin-absolute
            String origin = originOf(b);
            return (origin == null ? b : origin) + r;
        }
        return dirOf(b) + r;                             // document-relative
    }

    /**
     * The absolute base a DASH manifest's relative references hang off: its own
     * directory, or its {@code <BaseURL>} when that element is present.
     */
    static String dashBase(String manifestUrl, String baseUrlTag) {
        if (baseUrlTag == null || baseUrlTag.trim().isEmpty()) return dirOf(manifestUrl);
        return resolveRef(manifestUrl, baseUrlTag.trim());
    }

    /**
     * v3.4.3 — RE-BASE A RELAY-RELATIVE SEGMENT URL (Replit QA-0025).
     *
     * <p>When a segment request lands on the relay's host but WITHOUT the base64 origin
     * segment in its path, the relay has no idea what to fetch. This re-inserts the
     * segment the original relay URL carried. Any request that is not on the relay host
     * (a real CDN segment) is returned untouched.
     */
    static String rebaseOntoRelay(String relayUrl, String requestUrl) {
        Relay r = relay(relayUrl);
        if (!r.isRelay || requestUrl == null || requestUrl.trim().isEmpty()) return requestUrl;
        String req = requestUrl.trim();
        if (!schemeOf(relayUrl).equals(schemeOf(req))) return req;
        if (!hostOf(relayUrl).equals(hostOf(req))) return req;
        int se = relayUrl.indexOf("://");
        int hostEnd = relayUrl.indexOf('/', se + 3);
        if (hostEnd < 0) return req;
        int segEnd = relayUrl.indexOf('/', hostEnd + 1);
        String seg = segEnd < 0 ? relayUrl.substring(hostEnd + 1) : relayUrl.substring(hostEnd + 1, segEnd);
        if (seg.isEmpty()) return req;
        int reqSe = req.indexOf("://");
        int reqHostEnd = req.indexOf('/', reqSe + 3);
        String reqPath = reqHostEnd < 0 ? "/" : req.substring(reqHostEnd);
        if (reqPath.startsWith("/" + seg + "/") || reqPath.equals("/" + seg)) return req;
        return req.substring(0, reqHostEnd < 0 ? req.length() : reqHostEnd) + "/" + seg + reqPath;
    }

    /** Loopback, link-local, or any RFC1918 private address (a LAN peer, never a CDN). */
    static boolean isPrivateHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase(Locale.US);
        if (h.equals("localhost") || h.equals("[::1]") || h.equals("::1")) return true;
        if (h.startsWith("[")) return true;                       // any IPv6 literal
        String[] p = h.split("\\.");
        if (p.length != 4) return false;
        int[] n = new int[4];
        for (int i = 0; i < 4; i++) {
            try { n[i] = Integer.parseInt(p[i]); } catch (NumberFormatException e) { return false; }
            if (n[i] < 0 || n[i] > 255) return false;
        }
        if (n[0] == 127) return true;                              // loopback
        if (n[0] == 10) return true;                               // 10/8
        if (n[0] == 172 && n[1] >= 16 && n[1] <= 31) return true;   // 172.16/12
        if (n[0] == 192 && n[1] == 168) return true;                // 192.168/16
        if (n[0] == 169 && n[1] == 254) return true;                // link-local
        return false;
    }

    /** base64url (unpadded, Android's URL_SAFE|NO_WRAP|NO_PADDING) → text, or null. */
    static String b64Url(String s) { return b64(s, true); }

    /** standard base64 (padded or not) → text, or null. */
    static String b64Std(String s) { return b64(s, false); }

    private static String b64(String s, boolean urlSafe) {
        if (s == null || s.isEmpty()) return null;
        try {
            String t = s.replace(' ', '+');
            int pad = t.length() % 4;
            if (pad != 0) { StringBuilder sb = new StringBuilder(t); for (int i = pad; i < 4; i++) sb.append('='); t = sb.toString(); }
            byte[] raw = urlSafe ? java.util.Base64.getUrlDecoder().decode(t) : java.util.Base64.getDecoder().decode(t);
            return new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String trimSlash(String s) {
        if (s == null) return null;
        String t = s;
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    /** The query string of a path+query tail, or null. */
    private static String query(String pathAndQuery) {
        if (pathAndQuery == null) return null;
        int q = pathAndQuery.indexOf('?');
        return q < 0 ? null : pathAndQuery.substring(q + 1);
    }

    /** One raw (still percent-encoded) query parameter, or null. */
    static String param(String query, String name) {
        if (query == null || name == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            if (!k.equals(name)) continue;
            return eq < 0 ? "" : pair.substring(eq + 1);
        }
        return null;
    }

    /** Minimal %XX decoder (the token and the url= value are both percent-encoded). */
    static String percentDecode(String s) {
        if (s == null || s.indexOf('%') < 0) return s;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16), lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) { out.write((hi << 4) + lo); i += 2; continue; }
            }
            out.write((byte) c);
        }
        return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** One string field out of a small flat JSON object ("c", "b"), with backslash-u escapes and escaped slashes unescaped. */
    static String jsonString(String json, String key) {
        if (json == null || key == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            .matcher(json);
        if (!m.find()) return null;
        String v = m.group(1);
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c != '\\' || i + 1 >= v.length()) { sb.append(c); continue; }
            char e = v.charAt(++i);
            switch (e) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'r': sb.append('\r'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'u':
                    if (i + 4 < v.length()) {
                        try { sb.append((char) Integer.parseInt(v.substring(i + 1, i + 5), 16)); i += 4; }
                        catch (NumberFormatException ex) { sb.append("\\u"); }
                    }
                    break;
                default: sb.append(e);
            }
        }
        return sb.toString();
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

    // ═══════════════════════════════════════════════════════════════════════════
    // v3.4.3 — CREDENTIAL SCOPING, THE HTTPS-DOWNGRADE GUARD AND SECRET STRIPPING
    //
    // THE BUG THIS KILLS (Replit QA-0017 / QA-0018, and the `WatchHistory` hygiene
    // gap Replit missed):
    //
    //  1. v3.4.2 put the cast's `Cookie` INSIDE
    //     `DefaultHttpDataSource.Factory.setDefaultRequestProperties(...)`. A default
    //     request property is attached to EVERY request that factory makes — including
    //     the segment requests Media3 sends to a completely different CDN host. So a
    //     cookie that belongs to Origin A was silently replayed to Host B, which is a
    //     credential leak to a third party, not a playback nicety.
    //     v3.4.3 keeps the cookie OUT of the defaults and hands it to the
    //     ResolvingDataSource, which applies it ONLY when the request host is the
    //     validated origin host (or a same-site suffix of it).
    //
    //  2. `setAllowCrossProtocolRedirects(true)` let an authenticated HTTPS request be
    //     redirected to plaintext HTTP and still carry its Cookie/Authorization.
    //     `isSafeRedirect()` is the pure policy: an HTTPS → HTTP hop is refused while
    //     credentials are in play, EXCEPT for a deliberate private/LAN relay (the
    //     AnantPlay DLNA relay on 192.168.x.x), which is the one HTTP leg we ship on
    //     purpose. `allowCrossProtocolRedirects()` decides what the data source may
    //     follow at all.
    //
    //  3. `WatchHistory` persisted the raw cast `headers` blob — Cookie included — in
    //     SharedPreferences (and in a backup export). `stripSecrets*` is the ONE
    //     funnel that removes those before anything is written to disk.
    //
    // Everything here is pure (no Android types, no I/O), so the lab drives it.
    // ═══════════════════════════════════════════════════════════════════════════

    /** Header names that carry a credential and must never be persisted or forwarded
     *  outside their own origin. Lower-case, compared case-insensitively. */
    static final String[] SECRET_HEADERS = {
        "cookie", "authorization", "proxy-authorization", "set-cookie",
        "x-auth-token", "x-api-key", "x-csrf-token"
    };

    /** True when this header name carries a credential (Cookie / Authorization / …). */
    static boolean isSecretHeader(String name) {
        if (name == null) return false;
        String l = name.trim().toLowerCase(Locale.US);
        for (String s : SECRET_HEADERS) if (s.equals(l)) return true;
        return false;
    }

    /** A copy of {@code headers} with every credential header removed (never null). */
    static Map<String, String> stripSecrets(Map<String, String> headers) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (headers == null) return out;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            if (isSecretHeader(e.getKey())) continue;
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** True when the map carries ANY credential header. */
    static boolean hasCredentials(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return false;
        for (String k : headers.keySet()) if (isSecretHeader(k)) return true;
        return false;
    }

    /**
     * The persisted-safe form of a cast/WebView headers payload: the same header map
     * with every credential removed, re-serialised as a flat JSON object. A blob that
     * is not JSON is returned unchanged (it carries no parsable credentials).
     */
    static String stripSecretsJson(String headersJson) {
        if (headersJson == null || headersJson.trim().isEmpty()) return "";
        Map<String, String> parsed = parseHeaders(headersJson);
        if (parsed.isEmpty()) return headersJson;
        Map<String, String> safe = stripSecrets(parsed);
        StringBuilder sb = new StringBuilder(128).append('{');
        boolean first = true;
        for (Map.Entry<String, String> e : safe.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(MiniJson.quote(e.getKey())).append(':').append(MiniJson.quote(e.getValue()));
        }
        return sb.append('}').toString();
    }

    /** True when a headers payload still contains a credential (legacy history rows). */
    static boolean hasSecrets(String headersJson) {
        if (headersJson == null || headersJson.trim().isEmpty()) return false;
        return hasCredentials(parseHeaders(headersJson));
    }

    /** The lower-case scheme of a URL ("http"/"https"), or null when it has none. */
    static String schemeOf(String url) {
        if (url == null) return null;
        String u = url.trim();
        int se = u.indexOf("://");
        if (se <= 0) return null;
        return u.substring(0, se).toLowerCase(Locale.US);
    }

    /** The lower-case host of a URL (no port, no brackets), or null. */
    static String hostOf(String url) {
        if (url == null) return null;
        String u = url.trim();
        int se = u.indexOf("://");
        if (se <= 0) return null;
        int start = se + 3;
        int end = start;
        while (end < u.length()) {
            char c = u.charAt(end);
            if (c == '/' || c == '?' || c == '#' || c == ':') break;
            end++;
        }
        if (end <= start) return null;
        return u.substring(start, end).toLowerCase(Locale.US);
    }

    /**
     * True when two hosts belong to the same site: identical, or one is a dotted
     * suffix of the other ({@code cdn.foo.com} vs {@code foo.com}). Never true for a
     * blank host, and never true across unrelated domains (origin A ≠ CDN host B).
     */
    static boolean sameSiteHost(String a, String b) {
        if (a == null || b == null) return false;
        String x = a.trim().toLowerCase(Locale.US);
        String y = b.trim().toLowerCase(Locale.US);
        if (x.isEmpty() || y.isEmpty()) return false;
        if (x.equals(y)) return true;
        return x.endsWith("." + y) || y.endsWith("." + x);
    }

    /**
     * v3.4.3 — THE REDIRECT POLICY (Replit QA-0018).
     *
     * <p>True when following {@code fromUrl → toUrl} is safe. The one dangerous hop is
     * an <b>HTTPS → HTTP downgrade while credentials are being forwarded</b>: the
     * Cookie/Authorization would then travel in clear text to whoever answered the
     * redirect. That hop is refused for every public host.
     *
     * <p>Deliberately still allowed:
     * <ul>
     *   <li>same-scheme redirects (http→http, https→https) — nothing is downgraded;</li>
     *   <li>an HTTP → HTTPS upgrade — strictly stronger;</li>
     *   <li>HTTPS → HTTP toward a private/loopback host — the AnantPlay LAN relay is a
     *       deliberate plaintext peer on the user's own network, and refusing it would
     *       break the relay ladder the app exists for.</li>
     * </ul>
     */
    static boolean isSafeRedirect(String fromUrl, String toUrl, boolean hasCredentials) {
        if (toUrl == null || toUrl.trim().isEmpty()) return false;
        String toScheme = schemeOf(toUrl);
        if (toScheme == null) return false;                       // file:, data:, intent: …
        if (!toScheme.equals("http") && !toScheme.equals("https")) return false;
        String fromScheme = schemeOf(fromUrl);
        if (fromScheme == null) return true;                      // nothing to downgrade from
        if (fromScheme.equals(toScheme)) return true;            // same-protocol hop
        if (toScheme.equals("https")) return true;                // upgrade, always fine
        // toScheme == http and fromScheme == https → the downgrade
        if (!hasCredentials) return true;                         // nothing secret to expose
        return isPrivateHost(hostOf(toUrl));                      // only the LAN relay may
    }

    /**
     * Whether Media3's {@code DefaultHttpDataSource} may follow cross-protocol
     * redirects for this request. An authenticated HTTPS request may not (that is the
     * only way to guarantee the downgrade guard above is never bypassed by the
     * data source itself); everything else — including the plaintext LAN relay, whose
     * whole point is that it can upgrade to the real upstream — may.
     */
    static boolean allowCrossProtocolRedirects(String url, boolean hasCredentials) {
        String scheme = schemeOf(url);
        if (!"https".equals(scheme)) return true;                  // relay / LAN / plain http
        return !hasCredentials;
    }

    /**
     * The Cookie header value a request to {@code requestUrl} may carry.
     *
     * <p>Empty when the request host is not the validated origin host (or a same-site
     * suffix of it) — that is the whole point of the fix: the Origin A cookie is never
     * replayed to an unrelated CDN Host B. When the request host IS in scope, the
     * cast cookie is returned so the caller can merge it with the WebView's own
     * per-host {@code CookieManager} entry.
     */
    static String scopedCookie(String requestUrl, String originUrl, String castCookie) {
        if (castCookie == null || castCookie.trim().isEmpty()) return "";
        String origin = hostOf(originUrl);
        String req = hostOf(requestUrl);
        if (!sameSiteHost(req, origin)) return "";
        // and never across a downgrade that would expose it
        if (!isSafeRedirect(originUrl, requestUrl, true)) return "";
        return castCookie.trim();
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
