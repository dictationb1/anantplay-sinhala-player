package lk.techworld.anantplay.sicast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/* ═══════════════════════════════════════════════════════════════════════════
 * v3.4.6.k — LAW #3: THE PURE-JAVA ZERO-AD YOUTUBE EXTRACTOR
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * The Part_05 00:20 hard crash + the 00:17/01:32 in-WebView ad overlays end
 * here. Whenever the user opens ANY youtube.com/watch?v=…, m.youtube.com/
 * watch?v=…, youtube.com/shorts/… or youtu.be/… URL in the Browser tab, this
 * extractor resolves the 100% AD-FREE direct stream (no YouTube player, no
 * ad server ever loads) plus the native/auto-generated CAPTION track — and
 * the Browser dock lights up `▶ Play Ad-Free in Cinema`, which opens the
 * native hardware PlayActivity with:
 *   · the ad-free muxed stream (Low-Data 360p itag 18 / HD 720p itag 22 when
 *     YouTube serves it), auto-translated into Sinhala subtitles;
 *   · auto-skipped SponsorBlock segments (sponsor.ajay.app).
 *
 * THE PIPELINE (pure Java, ZERO Python, ZERO api.github.com):
 *   1. InnerTube `POST /youtubei/v1/player` with the ANDROID_VR client
 *      (needs NO JavaScript signature deciphering for muxed formats), then
 *      IOS, then WEB as the fallback ladder;
 *   2. `streamingData.formats` → the muxed progressive itags (18 = 360p
 *      AVC+AAC, 22 = 720p AVC+AAC) — a single URL ExoPlayer plays directly,
 *      no DASH manifest stitching, no ad-bearing playlist;
 *   3. `captions.playerCaptionsTracklistRenderer.captionTracks` → every
 *      caption track with its timedtext baseUrl (English preferred, the
 *      auto-generated track as the fallback);
 *   4. `sponsor.ajay.app/api/skipSegments` → the SponsorBlock segments to
 *      auto-skip during playback.
 *
 * Every request runs under one 6-second connect/read budget. The whole class
 * is pure static + an injectable fetch seam, so the lab round-trips the
 * exact parse/verify path without a network. */
public final class YouTubeExtractor {

    private YouTubeExtractor() { }

    /** The whole extraction budget (per request: 6 s connect + 6 s read). */
    static final int TIMEOUT_MS = 6_000;

    /** The InnerTube client ladder (ANDROID_VR first: no sig deciphering). */
    static final String[][] CLIENTS = {
        {"ANDROID_VR", "1.62.27", "28", "Android", "com.google.android.apps.youtube.vr.oculus"},
        {"IOS", "20.10.4", "16", "iPhone", "com.google.ios.youtube"},
        {"WEB", "2.20250101.00.00", "1", "", ""},
    };

    static final String PLAYER_URL =
        "https://www.youtube.com/youtubei/v1/player?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
    static final String SPONSOR_URL = "https://sponsor.ajay.app/api/skipSegments";

    // ── the result shape (pure data; MiniJson-writable for the shell route) ──

    /** One muxed ad-free stream (itag → quality lane). */
    public static final class Format {
        public final String url;
        public final int itag;
        public final int height;
        public final String quality;   // "Low-Data 360p" / "HD 720p" / …
        Format(String url, int itag, int height, String quality) {
            this.url = url; this.itag = itag; this.height = height; this.quality = quality;
        }
    }

    /** One caption track (native or auto-generated). */
    public static final class CaptionTrack {
        public final String baseUrl;   // the timedtext endpoint (add &fmt=json3)
        public final String lang;
        public final String name;
        public final boolean auto;
        CaptionTrack(String baseUrl, String lang, String name, boolean auto) {
            this.baseUrl = baseUrl; this.lang = lang; this.name = name; this.auto = auto;
        }
    }

    public static final class Result {
        public String videoId = "";
        public String title = "";
        public Format lowData;         // itag 18 (360p) — the Low-Data lane
        public Format hd;              // itag 22 (720p) — the HD lane (may be null)
        public CaptionTrack caption;   // the best caption track (may be null)
        public List<Format> allFormats = new ArrayList<>();
        public List<CaptionTrack> allCaptions = new ArrayList<>();
        public boolean ok() { return lowData != null || hd != null; }
    }

    /** One SponsorBlock segment [startSec, endSec] with its category. */
    public static final class Segment {
        public final double start, end;
        public final String category;
        Segment(double start, double end, String category) {
            this.start = start; this.end = end; this.category = category;
        }
    }

    // ── the URL classifier (shared by WebBrowserView + the lab) ───────────────

    /** The 11-char video id of ANY YouTube watch/shorts/youtu.be URL, else null. */
    public static String videoIdOf(String url) {
        if (url == null) return null;
        String u = url.trim();
        if (u.isEmpty()) return null;
        int q = u.indexOf('?');
        String bare = q > 0 ? u.substring(0, q) : u;
        int hash = bare.indexOf('#');
        if (hash > 0) bare = bare.substring(0, hash);
        // youtu.be/<id> · youtube.com/shorts/<id> · /embed/<id> · /live/<id>
        for (String prefix : new String[]{
                "https://youtu.be/", "http://youtu.be/",
                "https://www.youtube.com/shorts/", "https://youtube.com/shorts/",
                "https://m.youtube.com/shorts/", "https://www.youtube.com/embed/",
                "https://www.youtube.com/live/"}) {
            if (bare.startsWith(prefix)) return idOf(bare.substring(prefix.length()));
        }
        // watch?v=<id> (+ m. / www. / bare hosts, any param order)
        if (u.contains("youtube.com/watch") || u.contains("/watch?v=")) {
            for (String pair : u.substring(u.indexOf('?') + 1).split("&")) {
                if (pair.startsWith("v=")) return idOf(pair.substring(2));
            }
        }
        return null;
    }

    private static String idOf(String tail) {
        String t = tail.split("[?&#/]")[0];
        return t.length() == 11 ? t : null;
    }

    /** True for any URL the extractor handles (shared by onPageStarted). */
    public static boolean isYouTubeUrl(String url) { return videoIdOf(url) != null; }

    // ── the extractable fetch seam (the lab only) ─────────────────────────────

    interface Http {
        String get(String url, Map<String, String> headers) throws Exception;
        String post(String url, Map<String, String> headers, String body) throws Exception;
    }
    private static volatile Http testHttp;
    static void setHttpForTests(Http h) { testHttp = h; }

    // ── the extraction ladder ────────────────────────────────────────────────

    /** Extract the ad-free stream + captions for a video id (the ladder). */
    public static Result extract(String videoId) throws Exception {
        if (videoId == null || videoId.length() != 11)
            throw new IllegalArgumentException("bad video id");
        Result last = new Result();
        for (String[] client : CLIENTS) {
            try {
                Result r = extractWith(videoId, client);
                if (r.ok()) return r;
                last = r;
            } catch (Exception e) {
                Diag.w("yt", "client " + client[0] + " failed: " + e);
            }
        }
        if (last.title != null && !last.title.isEmpty()) return last;
        throw new IllegalStateException("every InnerTube client refused the video");
    }

    /** One InnerTube client's player response, parsed into the Result shape. */
    static Result extractWith(String videoId, String[] client) throws Exception {
        Map<String, Object> ctx = new LinkedHashMap<>();
        Map<String, Object> clientO = new LinkedHashMap<>();
        clientO.put("clientName", client[0]);
        clientO.put("clientVersion", client[1]);
        if (!client[3].isEmpty()) clientO.put("deviceMake", client[3]);
        clientO.put("androidSdkVersion", client[2]);
        if (!client[4].isEmpty()) clientO.put("androidPackage", client[4]);
        ctx.put("client", clientO);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("context", ctx);
        body.put("videoId", videoId);
        body.put("contentCheckOk", true);
        body.put("racyCheckOk", true);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent",
            "ANDROID_VR".equals(client[0])
                ? "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12)"
                : "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        headers.put("Accept-Language", "en-US,en;q=0.9,si;q=0.8");
        String raw = http(PLAYER_URL, headers, MiniJson.write(body));
        return parsePlayerResponse(raw, videoId);
    }

    /** Parse an InnerTube player response (the lab's seam: no network). */
    static Result parsePlayerResponse(String raw, String videoId) {
        Result r = new Result();
        r.videoId = videoId;
        Object o = MiniJson.parse(raw);
        Object details = MiniJson.objGet(o, "videoDetails");
        if (details instanceof Map) {
            String t = MiniJson.str((Map<String, Object>) details, "title");
            if (t != null) r.title = t;
        }
        Object sd = MiniJson.objGet(o, "streamingData");
        Object formatsObj = sd instanceof Map ? MiniJson.objGet(sd, "formats") : null;
        for (Object f : MiniJson.arr(formatsObj)) {
            if (!(f instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) f;
            String url = MiniJson.str(m, "url");
            Number itagN = MiniJson.num(m, "itag");
            int itag = itagN == null ? 0 : itagN.intValue();
            if (url == null || url.isEmpty() || itag == 0) continue;
            String quality = qualityOf(itag);
            if (quality == null) continue;   // only the muxed progressive lanes
            Format fmt = new Format(url, itag, heightOf(itag), quality);
            r.allFormats.add(fmt);
            if (itag == 18) r.lowData = fmt;              // the Low-Data 360p lane
            if (itag == 22) r.hd = fmt;                  // the HD 720p lane
        }
        // the caption tracks (native first, auto-generated second)
        Object captions = MiniJson.objGet(o, "captions");
        Object renderer = captions instanceof Map
            ? MiniJson.objGet(captions, "playerCaptionsTracklistRenderer") : null;
        for (Object t : MiniJson.arr(MiniJson.objGet(renderer, "captionTracks"))) {
            if (!(t instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) t;
            String baseUrl = MiniJson.str(m, "baseUrl");
            String lang = MiniJson.str(m, "languageCode");
            String kind = MiniJson.str(m, "kind");
            if (baseUrl == null || baseUrl.isEmpty()) continue;
            String name = "";
            Object nameObj = MiniJson.objGet(m, "name");
            Object runs = MiniJson.objGet(nameObj, "runs");
            for (Object run : MiniJson.arr(runs)) {
                if (run instanceof Map) {
                    String tx = MiniJson.str((Map<String, Object>) run, "text");
                    if (tx != null && !tx.isEmpty()) { name = tx; break; }
                }
            }
            if (name.isEmpty()) name = lang == null ? "captions" : lang;
            CaptionTrack ct = new CaptionTrack(baseUrl,
                lang == null ? "" : lang, name, "asr".equals(kind));
            r.allCaptions.add(ct);
        }
        r.caption = pickCaption(r.allCaptions);
        return r;
    }

    /** English native → any native → English auto → any auto → null. */
    static CaptionTrack pickCaption(List<CaptionTrack> tracks) {
        CaptionTrack nativeEn = null, nativeAny = null, autoEn = null, autoAny = null;
        for (CaptionTrack t : tracks) {
            if ("en".equalsIgnoreCase(t.lang)) {
                if (!t.auto) { if (nativeEn == null) nativeEn = t; }
                else if (autoEn == null) autoEn = t;
            } else {
                if (!t.auto) { if (nativeAny == null) nativeAny = t; }
                else if (autoAny == null) autoAny = t;
            }
        }
        if (nativeEn != null) return nativeEn;
        if (nativeAny != null) return nativeAny;
        if (autoEn != null) return autoEn;
        return autoAny;
    }

    /** The muxed progressive itags only (a single URL ExoPlayer plays raw). */
    static String qualityOf(int itag) {
        switch (itag) {
            case 18: return "Low-Data 360p";
            case 22: return "HD 720p";
            case 59: return "480p";
            default: return null;   // adaptive-only itags need stitching — skipped
        }
    }

    static int heightOf(int itag) {
        switch (itag) {
            case 18: return 360;
            case 22: return 720;
            case 59: return 480;
            default: return 0;
        }
    }

    // ── SponsorBlock (sponsor.ajay.app — the auto-skip segments) ─────────────

    /** The auto-skip segments for a video (empty list when the API has none). */
    public static List<Segment> skipSegments(String videoId) {
        List<Segment> out = new ArrayList<>();
        try {
            String url = SPONSOR_URL + "?videoID=" + videoId
                + "&categories=%5B%22sponsor%22%2C%22selfpromo%22%2C%22intro%22%2C%22outro%22%5D";
            String raw = httpGet(url);
            for (Object seg : MiniJson.arr(MiniJson.parse(raw))) {
                if (!(seg instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) seg;
                List<Object> pair = MiniJson.arr(MiniJson.objGet(m, "segment"));
                if (pair.size() < 2) continue;
                double start = ((Number) pair.get(0)).doubleValue();
                double end = ((Number) pair.get(1)).doubleValue();
                String cat = MiniJson.str(m, "category");
                out.add(new Segment(start, end, cat == null ? "sponsor" : cat));
            }
        } catch (Throwable t) {
            Diag.w("yt", "sponsorblock: " + t);   // never fatal: playback just doesn't skip
        }
        return out;
    }

    // ── the tiny HTTP lane (6 s budget, gzip-aware, https-only) ──────────────

    private static String http(String url, Map<String, String> headers, String body)
            throws Exception {
        Http seam = testHttp;
        if (seam != null)
            return body == null ? seam.get(url, headers) : seam.post(url, headers, body);
        HttpURLConnection c = null;
        try {
            // v3.4.6.k — HTTPS-ONLY (LAW #3): the extractor talks to
            // YouTube/SponsorBlock over TLS exclusively. A plain-http URL is
            // refused before a socket opens (no downgrade, no mixed content).
            if (url == null || !url.regionMatches(true, 0, "https://", 0, 8))
                throw new SecurityException("refused non-HTTPS extraction URL");
            c = (HttpURLConnection) new URL(url).openConnection(java.net.Proxy.NO_PROXY);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet())
                c.setRequestProperty(e.getKey(), e.getValue());
            if (body == null) {
                return readAll(c);
            }
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            try (java.io.OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            return readAll(c);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static String httpGet(String url) throws Exception { return http(url, null, null); }

    private static String readAll(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        if (in != null) {
            try (InputStream is = in) {
                byte[] buf = new byte[8192];
                for (int n; (n = is.read(buf)) > 0; ) {
                    o.write(buf, 0, n);
                    if (o.size() > 4 * 1024 * 1024) throw new Exception("response too large");
                }
            }
        }
        String body = new String(o.toByteArray(), StandardCharsets.UTF_8);
        if (code >= 400) throw new IllegalStateException("HTTP " + code + ": "
            + BridgeApi.brief(body, 120));
        return body;
    }

    /** The shell-route payload for the Browser dock (one JSON map). */
    public static String shellPayload(Result r, List<Segment> segs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "yt");
        m.put("videoId", r.videoId);
        m.put("title", r.title == null ? "" : r.title);
        Format f = r.hd != null ? r.hd : r.lowData;
        m.put("url", f == null ? "" : f.url);
        m.put("quality", f == null ? "" : f.quality);
        m.put("hd", r.hd != null);
        m.put("captionUrl", r.caption == null ? "" : r.caption.baseUrl);
        m.put("captionName", r.caption == null ? "" : r.caption.name);
        List<Object> segList = new ArrayList<>();
        for (Segment s : segs) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("start", s.start);
            sm.put("end", s.end);
            sm.put("category", s.category);
            segList.add(sm);
        }
        m.put("segments", segList);
        return MiniJson.write(m);
    }
}
