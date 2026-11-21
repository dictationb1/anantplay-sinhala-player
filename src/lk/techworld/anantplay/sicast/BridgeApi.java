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

    /** The progress line for a 202 body ({status:"translating",done,total}). */
    public static String progressText(Map<String, Object> p) {
        Object done = p == null ? null : p.get("done");
        Object total = p == null ? null : p.get("total");
        if (done != null && total != null)
            return "Gemini · batch " + done + "/" + total + " · සිංහලට හරවමින්…";
        return "Gemini · සිංහලට හරවමින්… (translating)";
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
    public Map<String, Object> subtitles(String type, String imdb, int season, int episode, String title) throws IOException {
        String q = "/subtitles/" + type + "/" + imdb + ".json";
        boolean first = true;
        if (season > 0) { q += (first ? "?" : "&") + "season=" + season; first = false; }
        if (episode > 0) { q += (first ? "?" : "&") + "episode=" + episode; first = false; }
        if (title != null && !title.isEmpty()) { q += (first ? "?" : "&") + "title=" + enc(title); first = false; }
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

    // ── transport ───────────────────────────────────────────────────────────────

    String getJson(String path) throws IOException {
        return new String(getBytes(path), StandardCharsets.UTF_8);
    }

    byte[] getBytes(String path) throws IOException {
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
            return body;
        } catch (java.net.SocketTimeoutException e) {
            throw new java.net.SocketTimeoutException("worker timeout after " + (timeoutMs / 1000) + " s");
        } finally {
            c.disconnect();
        }
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
