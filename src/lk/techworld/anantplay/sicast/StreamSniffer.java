package lk.techworld.anantplay.sicast;

import java.util.Locale;

/* v3.0.0 — THE WEBVIEW STREAM SNIFFER CORE (pure Java — no Android imports, so
 * the verification lab proves every classification/filter rule on a bare JVM).
 *
 * WHAT IT DECIDES, for every request the built-in browser's WebView makes
 * (shouldInterceptRequest) and every <video> the injected JS hook reports:
 *   1. CLASSIFY — is this URL a real video stream, and which kind?
 *      HLS (.m3u8/.m3u playlists), DASH (.mpd), progressive MP4/MKV/WEBM/FLV/TS.
 *      Only the PATH decides (StreamHint's rule: a token in the query must never
 *      classify a URL); the Content-Type helps when the path has no extension.
 *   2. FILTER OUT NOISE — ad/tracker domains (the blocklist), tracking pixels,
 *      HLS/DASH media SEGMENTS (.ts/.m4s/init.mp4/seg-* chunks: the manifest is
 *      what we report, not its 4-second fragments), tiny .mp4s (< 3 MB when the
 *      length is known = a pre-roll or a pixel, never the movie).
 *   3. CARRY THE CONTEXT — the exact Referer / Origin / Cookie / User-Agent the
 *      site served the stream with, plus the cleaned page title (TitleCleaner):
 *      PlayActivity needs all of them to open the SAME stream the site's own
 *      player sees (and the worker needs the cleaned title to find subtitles).
 *
 * A hit is reported AT MOST ONCE per URL per page — the floating pill aggregates
 * the count ("HLS ×2"), so the user picks exactly what to watch with subtitles. */
public final class StreamSniffer {

    /** One detected stream, with everything PlayActivity needs to replay it. */
    public static final class Hit {
        public String url;
        public String kind;        // HLS | DASH | MP4 | MKV | WEBM | FLV | TS | VIDEO
        public String referer;     // the page URL the request came from
        public String origin;
        public String cookie;
        public String ua;
        public String pageTitle;   // cleaned page title (TitleCleaner)
        public long contentLength = -1;
        public long atMs = System.currentTimeMillis();

        Hit url(String u) { this.url = u; return this; }
        Hit kind(String k) { this.kind = k; return this; }
        /** The bilingual pill label: "🎬 සිංහල උපසිරැසි සමඟ බලන්න (HLS ×2)". */
        public String pillLabel(int count) {
            return "🎬 සිංහල උපසිරැසි සමඟ බලන්න · Play with Subs (" + kind + (count > 1 ? " ×" + count : "") + ")";
        }
    }

    /** The minimum plausible progressive video size when a length is known —
     *  below this a .mp4 is a pre-roll/pixel, not the movie. */
    static final long MIN_PROGRESSIVE_BYTES = 3_000_000;

    /** The ad/tracker domain blocklist (suffix-matched against the host). These
     *  are the popup/redirect networks streaming sites commonly embed; a hit is
     *  silently dropped, and shouldOverrideUrlLoading blocks navigations to them
     *  so the popups never open at all. Curated — streaming CDNs are NOT here. */
    static final String[] AD_DOMAINS = {
        "doubleclick.net", "googlesyndication.com", "googletagservices.com",
        "google-analytics.com", "analytics.google.com", "adservice.google.com",
        "adsrvr.org", "criteo.com", "criteo.net", "taboola.com", "outbrain.com",
        "popads.net", "popcash.net", "propellerads.com", "propellerclick.com",
        "adsterra.com", "adsterranetwork.com", "exoclick.com", "exosrv.com",
        "exdynsrv.com", "juicyads.com", "trafficjunky.com", "trafficfactory.biz",
        "mgid.com", "revcontent.com", "adskeeper.com", "adskeeper.co.uk",
        "onclckds.com", "onclckpp.com", "onclckpro.com", "hilltopads.net",
        "clickadu.com", "adcash.com", "zeropark.com", "adskeeper.us",
        "a-ads.com", "adnxs.com", "advertising.com", "adform.net",
    };

    private StreamSniffer() { }

    /** The URL's path, lower-cased, without query/fragment (StreamHint.path's
     *  rule: only the path classifies, never a token in the query). */
    static String pathOf(String url) { return StreamHint.path(url); }

    /** The host part ("" when unparsable). */
    public static String hostOf(String url) {
        if (url == null) return "";
        String u = url.trim();
        int s = u.indexOf("://");
        if (s < 0) return "";
        int e = u.indexOf('/', s + 3);
        String host = e < 0 ? u.substring(s + 3) : u.substring(s + 3, e);
        int at = host.indexOf('@');
        if (at >= 0) host = host.substring(at + 1);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        return host.toLowerCase(Locale.ROOT);
    }

    /** True when the host belongs to a known ad/tracker domain (suffix match),
     *  or is an obvious ads./ad./tracker./analytics. subdomain. */
    public static boolean isAdOrTracker(String url) {
        String host = hostOf(url);
        if (host.isEmpty()) return false;
        for (String d : AD_DOMAINS) if (host.equals(d) || host.endsWith("." + d)) return true;
        return host.startsWith("ads.") || host.startsWith("ad.")
            || host.startsWith("tracker.") || host.startsWith("tracking.")
            || host.startsWith("analytics.") || host.startsWith("telemetry.");
    }

    /** Classify a URL (+ optional Content-Type) → the stream kind, or null when
     *  this is not a video stream. Mirrors StreamHint.mime's family rules. */
    public static String classify(String url, String mime) {
        String p = pathOf(url);
        String m = mime == null ? "" : mime.trim().toLowerCase(Locale.ROOT);
        if (p.endsWith(".m3u8") || p.endsWith(".m3u")
            || p.contains("manifest.m3u8") || p.contains("playlist.m3u8")
            || m.contains("mpegurl") || m.contains("vnd.apple.mpegurl")) return "HLS";
        if (p.endsWith(".mpd") || p.contains("manifest.mpd") || m.contains("dash+xml")) return "DASH";
        if (p.endsWith(".mp4") || p.endsWith(".m4v") || m.equals("video/mp4")) return "MP4";
        if (p.endsWith(".mkv") || m.contains("matroska")) return "MKV";
        if (p.endsWith(".webm") || m.equals("video/webm")) return "WEBM";
        if (p.endsWith(".flv") || m.equals("video/x-flv")) return "FLV";
        if (p.endsWith(".ts")) return "TS";
        if (m.startsWith("video/")) return "VIDEO";   // a video Content-Type on an extensionless path
        return null;
    }

    /** True when a classified URL is a media SEGMENT, not a reportable stream:
     *  HLS .ts chunks, DASH .m4s/init fragments, explicit /seg/ chunk paths. */
    static boolean isSegment(String url) {
        String p = pathOf(url);
        String u = url == null ? "" : url.toLowerCase(Locale.ROOT);
        return p.endsWith(".m4s") || p.endsWith(".ts") || p.endsWith(".aac")
            || p.contains("/seg") || p.contains("/chunklist")
            || p.contains("init.mp4") || p.contains("init.m4s")
            || u.contains("fragment") || u.contains("/thumbnails/") || u.contains("preview");
    }

    /** THE REPORT GATE. True when this request should surface on the pill:
     *  http(s) only (blob:/data: URLs cannot be replayed by Media3), not an
     *  ad/tracker, a real stream kind, not a segment, and — when the length is
     *  known — big enough to be the movie. Manifests (.m3u8/.mpd) always pass:
     *  they are what the site's own player feeds on. */
    public static boolean shouldReport(String url, String mime, long contentLength) {
        if (url == null) return false;
        String u = url.trim().toLowerCase(Locale.ROOT);
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false;
        if (isAdOrTracker(url)) return false;
        String kind = classify(url, mime);
        if (kind == null) return false;
        if ("HLS".equals(kind) || "DASH".equals(kind)) {
            // an audio-only variant playlist is still a manifest — report; but a
            // master playlist is preferred, so the pill keeps the FIRST hit per URL.
            return !isAdOrTracker(url);
        }
        if (isSegment(url)) return false;
        if (contentLength > 0 && contentLength < MIN_PROGRESSIVE_BYTES) return false;
        return true;
    }

    /** The cleaned page title for the subtitle lookup (TitleCleaner strips the
     *  "Watch X Full Movie Free HD - OnlyFlix" noise sites add).
     *  v3.2.0 — a JUNK page title ("ArrowTV Player", "VidSrc", "Embed
     *  Player"…) returns "" so the player's junk-title path (the 1-tap
     *  movie-title search pill) takes over instead of a blind Cinemeta call
     *  that flashes raw JSON over the video. */
    public static String cleanTitle(String rawPageTitle) {
        if (rawPageTitle == null) return "";
        String t = rawPageTitle.trim();
        if (t.isEmpty()) return "";
        if (TitleCleaner.isJunkTitle(t)) return "";   // never a player-branding "title"
        TitleCleaner.Clean c = TitleCleaner.clean(t);
        return c.changed ? c.title : t;
    }
}
