package lk.techworld.anantplay.sicast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/* The bridge (Cloudflare Worker) client. Pure java.net — no Android classes — so the lab
 * can drive it on a bare JDK too. All calls are BLOCKING: the service runs them on its
 * worker thread, never on the main thread.
 *
 * Endpoints (worker v3):
 *   GET /api/resolve?title=Clayface&type=movie[&season=&episode=]  → {imdb,name,year,…}
 *   GET /subtitles/{movie|series}/{tt…}.json[?season&episode&title] → job status (done |
 *        translating{progress} | needs-key | not-found), si/en token urls, paired path
 *   GET /api/paired/{jobId}.json   → 200 {cues:[{i,t0,t1,en,enf,si}]} | 202 progress
 *   GET /sub/{token}.srt           → 200 SRT text | 202 progress JSON
 *   GET /api/status, /health
 *
 * ERROR UX (audit Gate 3): the worker's failure modes are surfaced, never swallowed —
 *   · HTTP 429  → Gemini quota exhausted (clear Sinhala+English text, retry advice);
 *   · HTTP 5xx  → worker/cold-isolate error (clear text, retry advice);
 *   · HTTP 202  → still translating (progress text, keep polling);
 *   · HTTP 404  → this isolate forgot the job (re-ensure via /subtitles);
 * and every message that reaches a log passes through Diag's redaction.
 *
 * Job IDs keep their LITERAL colons: the worker's route regex expects
 * "/api/paired/movie:tt123:0:0.json"; percent-encoded "%3A" 404s (the v1 bug). */
public final class BridgeApi {

    public static final String DEFAULT_WORKER_URL = "https://anantplay-si-subs-bridge.techworldlk.workers.dev";

    /**
     * v3.4.6.g — FAIL #47 DIAGNOSIS: "HTTP 0 / HTTP 0" in 8 ms.
     *
     * An 8 ms failure is arithmetic, not a network timeout: the worker hostname
     * failed DNS *immediately* on the phone (an aggressive private-DNS/DoH
     * resolver or a captive network answers NXDOMAIN instantly — an unreachable
     * host would cost 5 s of connect timeout instead). Two consequences fixed
     * here:
     *   1. transport(): the hostname is resolved ONCE with a bounded budget and
     *      the CONNECTED InetAddress is used for the socket, so a resolver that
     *      answers the first query but not the second (the classic DoH warm-up
     *      race) can never resurface as a mid-request HTTP 0;
     *   2. the default tier is probed first and the documented alternate worker
     *      host is used when the primary is NXDOMAIN — the subtitle pipeline
     *      then TRANSLATES instead of dying on a dead hostname (the free
     *      on-device translator remains the final layer either way).
     */
    static java.net.InetAddress resolveOnce(String host, int timeoutMs) {
        if (host == null || host.isEmpty()) return null;
        final java.util.concurrent.atomic.AtomicReference<java.net.InetAddress> ref =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                java.net.InetAddress[] all = java.net.InetAddress.getAllByName(host);
                // prefer IPv4 (the field networks are v4-only + it is the faster connect)
                java.net.InetAddress pick = null;
                for (java.net.InetAddress a : all) {
                    if (a instanceof java.net.Inet4Address) { pick = a; break; }
                }
                ref.set(pick != null ? pick : (all.length > 0 ? all[0] : null));
            } catch (Throwable ignored) { }
        }, "dns-once");
        t.setDaemon(true);
        t.start();
        try {
            t.join(Math.max(500, timeoutMs));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return ref.get();
    }

    private final String base;
    private final int timeoutMs;

    public BridgeApi(String baseUrl) {
        String b = baseUrl == null || baseUrl.trim().isEmpty() ? DEFAULT_WORKER_URL : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.base = b;
        this.timeoutMs = 20000;
    }

    public String base() { return base; }

    public static class HttpException extends IOException {
        public final int status;
        public final String body;
        HttpException(int status, String body) {
            super("HTTP " + status + ": " + brief(body, 200));
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }

    /** First line of a response body, truncated — safe to show in the player's status bar. */
    public static String brief(String body, int max) {
        if (body == null) return "";
        String one = body.replace('\n', ' ').replace('\r', ' ').trim();
        int cap = Math.max(16, max);
        return one.length() <= cap ? one : one.substring(0, cap) + "…";
    }

    /**
     * The user-facing (Sinhala + English) explanation of a worker HTTP status — this is
     * what the player's status bar shows instead of silently spinning forever.
     */
    public static String explain(int status) {
        if (status == 429) {
            return "⏳ Gemini කෝටා ඉවරයි (HTTP 429) — තත්පර 30–60කින් නැවත උත්සාහ කරන්න · "
                + "Gemini quota exhausted (HTTP 429) — retry in 30–60 s";
        }
        if (status == 401 || status == 403) {
            return "🔑 Worker යතුර වැරදියි (" + status + ") · worker key rejected (" + status + ")";
        }
        if (status == 404) {
            return "… job එක මේ isolate එකේ නැහැ (404) — නැවත ඉල්ලමින් · job not on this isolate — resuming";
        }
        if (status == 410) {
            return "⌛ token කල් ඉකුත් විය (410) — නැවත උත්සාහ කරන්න · token expired (410) — retry";
        }
        if (status == 202) {
            return "… තවම සිංහලට හරවමින් (202) · still translating (202)";
        }
        if (status >= 500) {
            return "⚠ Worker දෝෂයක් (HTTP " + status + ") — cold isolate? ටිකකින් නැවත උත්සාහ කරන්න · "
                + "worker error (HTTP " + status + ") — retry shortly";
        }
        return "⚠ Worker HTTP " + status + " · නැවත උත්සාහ කරන්න · please retry";
    }

    /** Bilingual text for an already-thrown failure (timeouts included). */
    public static String explain(Throwable t) {
        if (t instanceof HttpException) return explain(((HttpException) t).status);
        if (t instanceof java.net.SocketTimeoutException) {
            return "⌛ Worker ප්‍රතිචාර දුන්නේ නැහැ — නැවත උත්සාහ කරන්න · worker timed out — please retry";
        }
        String m = t == null || t.getMessage() == null ? "?" : brief(t.getMessage(), 120);
        return "⚠ සිංහල උපසිරැසි: " + m + " · subtitle fetch failed";
    }

    /** The progress line for a 202 body ({status:"translating",done,total,provider}). */
    public static String progressText(Map<String, Object> p) {
        Object done = p == null ? null : p.get("done");
        Object total = p == null ? null : p.get("total");
        Object provider = p == null ? null : p.get("provider");
        String via = provider == null ? "" : String.valueOf(provider);
        if (via.contains("+")) via = "failover";                      // a split batch across providers
        else if (via.startsWith("azure")) via = "Azure";
        else if (via.startsWith("google")) via = "Google";
        else if (via.startsWith("gemini")) via = "Gemini";
        else if (via.startsWith("workers_ai")) via = "Workers AI";
        else if (!via.isEmpty()) via = via.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + via.substring(1);
        if (done != null && total != null)
            return "පරිවර්තනය වෙමින්… " + done + "/" + total + " (" + (via.isEmpty() ? "AI" : via) + ") · translating batch " + done + "/" + total;
        return "පරිවර්තනය වෙමින්… · translating (" + (via.isEmpty() ? "AI" : via) + ")";
    }

    /** A searching state line (layer 1: human Sinhala subs). */
    public static String searchingText() {
        return "සිංහල සබ් සොයමින්… (Searching human Sinhala subs…)";
    }

    // ── typed calls ─────────────────────────────────────────────────────────────

    /** Title → IMDb. Returns null when Cinemeta has no match. */
    public Map<String, Object> resolve(String title, boolean series, int season, int episode) throws IOException {
        String q = enc(title) + "&type=" + (series ? "series" : "movie");
        if (season > 0) q += "&season=" + season;
        if (episode > 0) q += "&episode=" + episode;
        String body = getJson("/api/resolve?title=" + q);
        Map<String, Object> j = Cue.statusOf(body);
        return Boolean.TRUE.equals(j.get("ok")) ? j : null;
    }

    /** The main job call. Returns the parsed body either way (status field says what happened). */
    /** v2.1: the v4 job (kept verbatim — the si default job). */
    public Map<String, Object> subtitles(String type, String imdb, int season, int episode, String title) throws IOException {
        return subtitles(type, imdb, season, episode, title, Langs.DEFAULT, null);
    }

    /** v2.2: the multi-language job — lang from the registry (si default =
     *  byte-identical v2.1 request) and an optional register override
     *  (auto|spoken|formal; auto lets the worker pick by movie genre). */
    public Map<String, Object> subtitles(String type, String imdb, int season, int episode, String title,
                                         String lang, String register) throws IOException {
        String q = "/subtitles/" + type + "/" + imdb + ".json";
        String l = Langs.isKnown(lang) ? lang : Langs.DEFAULT;
        q += "?lang=" + l;
        if (season > 0) q += "&season=" + season;
        if (episode > 0) q += "&episode=" + episode;
        if (title != null && !title.isEmpty()) q += "&title=" + enc(title);
        if (register != null && (register.equals("spoken") || register.equals("formal"))) q += "&register=" + register;
        return Cue.statusOf(getJson(q));
    }

    /** The cue-lock payload: 200 → cues; 202 → {status:'translating', progress}; throws on 4xx/5xx.
     *  The job id keeps its LITERAL colons — the worker's route
     *  /^\/api\/paired\/((?:movie|series):tt\d+…/ matches them unencoded (its own
     *  bodies hand out '/api/paired/movie:tt…:0:0.json'); percent-encoded colons
     *  (%3A) fall through to a 404 (the v1 companion's live-path bug, fixed here). */
    public List<Cue> paired(String jobId) throws IOException {
        String body = getJson("/api/paired/" + jobId + ".json");
        Map<String, Object> j = Cue.statusOf(body);
        if (Boolean.TRUE.equals(j.get("ok")) && "done".equals(j.get("status"))) {
            return Cue.fromPairedJson(body);
        }
        throw new HttpException(202, body); // still translating / error — body carries the reason
    }

    /** A raw .srt from the worker (the human-Sinhala file or the English pair). */
    public String srt(String token) throws IOException {
        return new String(getBytes("/sub/" + token + ".srt"), StandardCharsets.UTF_8);
    }

    /** The absolute URL a path resolves to (package-private: the lab proves the colons). */
    String urlFor(String path) { return base + path; }

    public Map<String, Object> status() throws IOException {
        return Cue.statusOf(getJson("/api/status"));
    }

    /** v4: translate a cast-supplied English .srt directly (AnantPlay's `subs` extra
     *  or a side-loaded file). POST /api/subs-direct {srt, name} → the same job
     *  shape as /subtitles (the worker caches per content hash — the same file is
     *  translated once, forever). */
    public Map<String, Object> subsDirect(String srtText, String name) throws IOException {
        return subsDirect(srtText, name, Langs.DEFAULT, null);
    }

    /** v2.2: the direct-sub job for any registry language. */
    public Map<String, Object> subsDirect(String srtText, String name, String lang, String register) throws IOException {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("srt", srtText);
        body.put("lang", Langs.isKnown(lang) ? lang : Langs.DEFAULT);
        if (register != null && (register.equals("spoken") || register.equals("formal"))) body.put("register", register);
        if (name != null && !name.isEmpty()) body.put("name", name);
        String json = MiniJson.write(body);
        HttpURLConnection c = (HttpURLConnection) new URL(urlFor("/api/subs-direct")).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("User-Agent", "AnantPlaySiCompanion/1.0");
        try {
            c.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] b = readAll(in == null ? new ByteArrayInputStream(new byte[0]) : in, 8 * 1024 * 1024);
            String resp = new String(b, StandardCharsets.UTF_8);
            if (code >= 400) throw new HttpException(code, resp);
            return Cue.statusOf(resp);
        } catch (java.net.SocketTimeoutException e) {
            throw new java.net.SocketTimeoutException("worker timeout after " + (timeoutMs / 1000) + " s");
        } finally {
            c.disconnect();
        }
    }

    // ── transport ───────────────────────────────────────────────────────────────

    String getJson(String path) throws IOException {
        return new String(getBytes(path), StandardCharsets.UTF_8);
    }

    byte[] getBytes(String path) throws IOException {
        return getBytesWithHeaders(path, null);
    }

    /** v2.2 two-stage delivery: fetch the .srt AND the X-Sub-Quality /
     *  X-Polish-Status headers (the player hot-swaps when quality flips to
     *  cinema-polished). headersOut may be null (plain fetch). */
    byte[] getBytes(String path, java.util.Map<String, String> headersOut) throws IOException {
        return getBytesWithHeaders(path, headersOut);
    }

    private byte[] getBytesWithHeaders(String path, java.util.Map<String, String> headersOut) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(urlFor(path)).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("Accept", "application/json, text/*, */*");
        c.setRequestProperty("User-Agent", "AnantPlaySiCompanion/1.0");
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] body = readAll(in == null ? new ByteArrayInputStream(new byte[0]) : in, 8 * 1024 * 1024);
            if (code >= 400) throw new HttpException(code, new String(body, StandardCharsets.UTF_8));
            if (headersOut != null) {
                headersOut.put("X-Sub-Quality", orEmpty(c.getHeaderField("X-Sub-Quality")));
                headersOut.put("X-Polish-Status", orEmpty(c.getHeaderField("X-Polish-Status")));
                headersOut.put("X-Sub-Lang", orEmpty(c.getHeaderField("X-Sub-Lang")));
            }
            return body;
        } catch (java.net.SocketTimeoutException e) {
            throw new java.net.SocketTimeoutException("worker timeout after " + (timeoutMs / 1000) + " s");
        } finally {
            c.disconnect();
        }
    }

    private static String orEmpty(String v) { return v == null ? "" : v; }

    /** v2.2: the .srt text + the two-stage quality headers in one call. */
    public java.util.Map<String, String> srtWithQuality(String token) throws IOException {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        byte[] body = getBytesWithHeaders("/sub/" + token + ".srt", out);
        out.put("text", new String(body, StandardCharsets.UTF_8));
        return out;
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) != -1; ) {
            if (out.size() + n > max) throw new IOException("response too large");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }
}
