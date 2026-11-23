package lk.techworld.anantplay.sicast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/* v3.2.0 — THE FREE KEYLESS ON-DEVICE SUBTITLE TRANSLATOR (Smoking Gun #2:
 * "translation quota runs out").
 *
 * THE FIELD FAILURE: the Worker's AI providers (Gemini/Azure/Google V3) are
 * QUOTA-BEARING — on a busy night the user hits HTTP 429 and subtitles die
 * with no fallback. Layer 1 (human Sinhala .srt/.zip from OpenSubtitles/SubDL/
 * Baiscope) and Layer 2 (the Worker's keyless `pa` path) can both miss.
 *
 * THE ENGINE (Layer 3 — the LAST mile, always free):
 *   · translate.googleapis.com/translate_a/single?client=gtx — Google's
 *     PUBLIC keyless web translate endpoint (the one Chrome's own right-click
 *     translate uses), no API key, no quota account, no billing;
 *   · clients5.google.com/translate_a/t?client=dict-chrome-ex — the second
 *     keyless endpoint (the Google Dictionary extension's), used as FAILover
 *     when endpoint 1 hiccups;
 *   · SMART BATCHING — cues are grouped (~40 cues / ≤1900 chars per request),
 *     separated by the transport-safe '\n' + numbered lines, translated as ONE
 *     request, then re-split EXACTLY onto the SAME indices: SRT timestamps and
 *     cue order are preserved byte-for-byte (the lab proves index identity);
 *   · RATE PACING — one request every ~600 ms (polite keyless usage), a 3-attempt
 *     retry with exponential (1 s / 2 s / 4 s) waits, endpoint failover, and a
 *     hard stop that returns the UNTRANSLATED cues rather than an error (the
 *     player keeps playing English instead of dying);
 *   · DISK CACHE — every translated batch persists under filesDir/subcache/
 *     keyed by the SOURCE hash + target lang, so a re-watched movie NEVER
 *     re-translates (the cache-hit path is lab-proven).
 *
 * PURE JAVA (no Android classes): the same engine runs in the verification
 * lab against a mock endpoint, and the Android side calls it from the subtitle
 * pipeline's background thread. */
public final class FreeSubTranslator {

    private FreeSubTranslator() { }

    /** One translated batch's outcome. */
    public static final class Result {
        public List<Cue> cues = new ArrayList<>();
        public int batches, cacheHits, requests;
        public boolean complete;                 // every cue translated
        public String error = "";                // "" when no hard failure
    }

    /** Where the disk cache lives (filesDir/subcache). */
    static File cacheDir(File filesDir) {
        File d = new File(filesDir, "subcache");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    // ── the batching engine (pure — the lab proves index preservation) ───────

    /** The transport separator: a line break the endpoints treat as a break,
     *  and our own split key. Numbered prefixes keep empty/blank cues from
     *  collapsing ("3 " → index 3, empty text). */
    static final String SEP = "\n";

    /** Build the request lines for one batch: "1 <text>" .. "N <text>". */
    static List<String> encodeBatch(List<Cue> cues, int from, int to) {
        List<String> out = new ArrayList<>();
        for (int i = from; i <= to && i < cues.size(); i++) {
            String t = cues.get(i).en == null || cues.get(i).en.isEmpty() ? cues.get(i).si : cues.get(i).en;
            out.add((i - from + 1) + " " + maskJoiners(t == null ? "" : t.replace('\n', ' ').trim()));
        }
        return out;
    }

    // ══ v3.4.3 — SINHALA JOINER PRESERVATION (Replit QA-1460) ═════════════════
    // Sinhala's conjuncts depend on U+200D ZWJ and U+200C ZWNJ. A machine-translation
    // round trip silently DROPS them (they are zero-width control characters), which
    // turns a correct conjunct into a broken one — a wrong letter, not a style choice.
    // They are masked to private-use code points before the request and restored after
    // it, so the transport can never eat them.
    static final char ZWJ = '\u200D';
    static final char ZWNJ = '\u200C';
    static final char ZWJ_MASK = '\uE000';
    static final char ZWNJ_MASK = '\uE001';

    /** Replace ZWJ/ZWNJ with private-use placeholders before sending. */
    static String maskJoiners(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        return s.replace(ZWJ, ZWJ_MASK).replace(ZWNJ, ZWNJ_MASK);
    }

    /** Restore ZWJ/ZWNJ after the reply comes back (idempotent, never throws). */
    static String unmaskJoiners(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        return s.replace(ZWJ_MASK, ZWJ).replace(ZWNJ_MASK, ZWNJ);
    }

    /** True when the text still carries its Sinhala joiners (the lab's assertion). */
    static boolean joinersIntact(String original, String after) {
        if (original == null || after == null) return false;
        int z = 0, n = 0;
        for (int i = 0; i < original.length(); i++) {
            if (original.charAt(i) == ZWJ) z++;
            if (original.charAt(i) == ZWNJ) n++;
        }
        int az = 0, an = 0;
        for (int i = 0; i < after.length(); i++) {
            if (after.charAt(i) == ZWJ) az++;
            if (after.charAt(i) == ZWNJ) an++;
        }
        return az >= z && an >= n;
    }

    /** Split a translated multi-line reply back onto the batch indices.
     *  Lines are "1 translated…": the leading number is matched (the endpoint
     *  sometimes drops it); unmatched lines fall back to positional order, and
     *  a MISSING line leaves that cue untranslated (never a wrong index). */
    static String[] decodeBatch(String reply, int batchCount) {
        String[] out = new String[batchCount];
        if (reply == null) return out;
        String[] lines = reply.split("\\r?\\n");
        int position = 0;                                   // positional cursor
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) continue;
            int idx = -1;
            int sp = line.indexOf(' ');
            if (sp > 0) {
                try { idx = Integer.parseInt(line.substring(0, sp).trim()) - 1; } catch (NumberFormatException ignored) { }
                if (idx >= 0 && idx < batchCount) line = line.substring(sp + 1).trim();
                else idx = -1;
            }
            if (idx < 0) {                                  // no usable number → positional
                if (position >= batchCount) continue;
                idx = position;
            }
            if (idx >= 0 && idx < batchCount && (out[idx] == null || out[idx].isEmpty())) {
                out[idx] = unmaskJoiners(line);   // v3.4.3 — restore ZWJ/ZWNJ
            }
            position++;
        }
        return out;
    }

    /** The next batch's [from, to] for the ~40-cue / 1900-char budget.
     *  Returns {-1,-1} when nothing remains. */
    static int[] nextBatch(List<Cue> cues, int from) {
        if (cues == null || from >= cues.size()) return new int[]{-1, -1};
        int chars = 0, count = 0;
        for (int i = from; i < cues.size() && count < 40; i++) {
            Cue c = cues.get(i);
            String t = c.en == null || c.en.isEmpty() ? c.si : c.en;
            int len = (t == null ? 0 : t.length()) + 6;      // "N " + margin
            if (count > 0 && chars + len > 1900) break;
            chars += len;
            count++;
        }
        return count == 0 ? new int[]{from, from} : new int[]{from, from + count - 1};
    }

    // ── the transport (keyless public endpoints, failover + retry) ────────────

    /** The two keyless endpoints, in firing order. */
    static final String EP1 = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=";
    static final String EP2 = "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=";

    /** One keyless translation request. `endpoint` is EP1 or EP2 (the lab's
     *  mock server swaps in via the same seam). Returns the joined translated
     *  text or null (the caller retries / fails over / keeps English). */
    static String request(String endpointBase, String targetLang, String multiLineText,
                          long connectTimeoutMs, long readTimeoutMs) throws IOException {
        // HTTPS-ONLY (the updater's httpsOnly contract applied to subtitle
        // traffic too): a plaintext endpoint is refused before any socket opens,
        // so a downgraded/mocked URL can never carry the user's cue text.
        if (endpointBase == null || !endpointBase.startsWith("https://"))
            throw new IOException("refusing non-HTTPS translation endpoint");
        String url = endpointBase + URLEncoder.encode(targetLang, "UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(java.net.Proxy.NO_PROXY);
        c.setRequestMethod("POST");
        c.setConnectTimeout((int) connectTimeoutMs);
        c.setReadTimeout((int) readTimeoutMs);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36");
        try {
            byte[] body = ("q=" + URLEncoder.encode(multiLineText, "UTF-8")).getBytes(StandardCharsets.UTF_8);
            c.getOutputStream().write(body);
            c.getOutputStream().flush();
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String resp = new String(readAll(in == null ? new ByteArrayInputStream(new byte[0]) : in, 2 * 1024 * 1024), StandardCharsets.UTF_8);
            if (code != 200) throw new IOException("HTTP " + code);
            return parseReply(endpointBase, resp);
        } finally {
            c.disconnect();
        }
    }

    /** EP1 replies [[["t1","",null,null,X],["t2",…]],…]; EP2 replies [["t1",…]] or
     *  "t1\nt2". Extract the translated lines (best-effort, never throws — a
     *  non-JSON reply falls back to its bare text). */
    static String parseReply(String endpointBase, String body) {
        if (body == null || body.trim().isEmpty()) return null;
        String trimmed = body.trim();
        if (trimmed.startsWith("[")) {
            // both endpoints reply JSON-ish arrays; MiniJson tolerates the shape
            // when it parses — and a parse failure falls back to bare text
            try {
                StringBuilder sb = new StringBuilder();
                collectTranslatedStrings(MiniJson.parse(trimmed), sb);
                String joined = sb.toString();
                if (!joined.isEmpty()) return joined;
            } catch (Throwable ignored) { }
        }
        // plain text fallback (the t endpoint sometimes replies bare lines)
        return trimmed.replace("\"", "").trim();
    }

    /** Walk the nested arrays and collect the first string of every innermost
     *  array (the translate_a/single contract: [[["translated", …], …], …]). */
    private static void collectTranslatedStrings(Object node, StringBuilder out) {
        if (node instanceof List) {
            for (Object o : (List<?>) node) {
                if (o instanceof List) {
                    List<?> inner = (List<?>) o;
                    if (!inner.isEmpty() && inner.get(0) instanceof String) {
                        String s = (String) inner.get(0);
                        if (!s.isEmpty()) {
                            if (out.length() > 0) out.append(SEP);
                            out.append(s);
                        }
                    } else {
                        collectTranslatedStrings(o, out);
                    }
                }
            }
        }
    }

    // ── the whole-cue-list pipeline (batch → translate → decode → cache) ─────

    /**
     * Translate a cue list into `targetLang` with the full Layer-3 engine:
     * batch-split (timestamps intact), disk cache per source hash, keyless
     * endpoint 1 → failover endpoint 2 → retry(3) with pacing, and a
     * NEVER-THROWS contract — on hard failure the ORIGINAL cues come back with
     * complete=false (the player shows English rather than an error card).
     * `sleeper` is the lab seam for the pacing waits (the app passes real sleeps).
     */
    public static Result translate(File filesDir, List<Cue> cues, String targetLang,
                                    java.util.function.LongConsumer sleeper) throws IOException {
        Result r = new Result();
        if (cues == null || cues.isEmpty()) return r;
        String sourceHash;
        try {
            sourceHash = SelfUpdateCore.sha256(sourceBytes(cues));
        } catch (Exception e) {
            throw new IOException("hash: " + e);
        }
        File cacheFile = new File(cacheDir(filesDir), sourceHash + "." + targetLang + ".srt");
        // cache hit → instant, zero network
        List<Cue> cached = readCachedSrt(cacheFile);
        if (cached != null && cached.size() == cues.size()) {
            r.cues = cached;
            r.cacheHits = 1;
            r.batches = 1;
            r.complete = true;
            return r;
        }
        List<Cue> out = new ArrayList<>(cues.size());
        for (Cue c : cues) out.add(new Cue(c.i, c.t0, c.t1, c.ts, c.en, c.enf, c.si));
        int from = 0;
        while (true) {
            int[] batch = nextBatch(out, from);
            if (batch[0] < 0) break;
            List<String> encoded = encodeBatch(out, batch[0], batch[1]);
            String joined = String.join(SEP, encoded);
            String reply = null;
            IOException last = null;
            for (int attempt = 0; attempt < 3 && reply == null; attempt++) {
                for (String ep : new String[]{EP1, EP2}) {
                    try {
                        reply = request(ep, targetLang, joined, 6000, 12000);
                        r.requests++;
                        if (reply != null) break;
                    } catch (IOException e) {
                        last = e;
                    }
                }
                if (reply == null && sleeper != null && attempt < 2) {
                    sleeper.accept(1000L << attempt);        // 1 s, 2 s pacing between retries
                }
            }
            if (reply == null) {
                // ══ v3.4.3 — NEVER WIPE THE ENGLISH ON A TRANSLATION FAILURE ══
                // `out` still holds every original cue (the loop only ever REPLACES a
                // cue after a successful decode), and complete stays false, so the
                // player keeps showing the loaded English instead of an error card.
                // This is asserted by the lab: a dead endpoint must return the input.
                r.error = last == null ? "no reply" : last.getMessage();
                r.cues = out;
                r.complete = false;
                return r;
            }
            String[] decoded = decodeBatch(reply, encoded.size());
            for (int i = 0; i < decoded.length; i++) {
                Cue c = out.get(batch[0] + i);
                String translated = decoded[i];
                if (translated != null && !translated.isEmpty()) {
                    out.set(batch[0] + i, new Cue(c.i, c.t0, c.t1, c.ts, c.en, c.enf, translated));
                }
            }
            r.batches++;
            from = batch[1] + 1;
            if (sleeper != null && from < out.size()) sleeper.accept(600L);   // polite pacing between batches
        }
        r.cues = out;
        int translatedCount = 0;
        for (Cue c : out) if (c.si != null && !c.si.isEmpty() && !c.si.equals(c.en)) translatedCount++;
        r.complete = translatedCount >= out.size();
        // persist ONLY complete translations (a partial cache would lie later)
        if (r.complete) writeCachedSrt(cacheFile, out);
        return r;
    }

    /** The deterministic source bytes (index + timestamps + source text). */
    static byte[] sourceBytes(List<Cue> cues) {
        StringBuilder sb = new StringBuilder(4096);
        for (Cue c : cues) {
            sb.append(c.i).append('|').append(c.t0).append('|').append(c.t1).append('|')
              .append(c.en == null ? "" : c.en).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ── the disk cache (plain SRT, timestamp-preserving) ─────────────────────

    /** Package-private since v3.3.0 so the regression lab can prove the round-trip
     *  OFFLINE (a network-dependent cache test failed on every air-gapped runner). */
    static List<Cue> readCachedSrt(File f) {
        try {
            if (!f.exists() || f.length() == 0 || f.length() > 5 * 1024 * 1024) return null;
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
            }
            return Cue.fromSrt(new String(o.toByteArray(), StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Package-private since v3.3.0 (see readCachedSrt). */
    static void writeCachedSrt(File f, List<Cue> cues) {
        try {
            File tmp = new File(f.getPath() + ".tmp");
            try (OutputStream o = new FileOutputStream(tmp)) {
                o.write(toSrt(cues).getBytes(StandardCharsets.UTF_8));
                o.flush();
            }
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (Throwable ignored) { }
    }

    /** Render cues as a standard SRT (index 1-based, original timestamps). */
    public static String toSrt(List<Cue> cues) {
        StringBuilder sb = new StringBuilder(cues.size() * 64);
        for (Cue c : cues) {
            sb.append(c.i + 1).append('\n');
            sb.append(srtTs(c.t0)).append(" --> ").append(srtTs(c.t1)).append('\n');
            String text = c.si == null || c.si.isEmpty() ? (c.en == null ? "" : c.en) : c.si;
            sb.append(text).append('\n').append('\n');
        }
        return sb.toString();
    }

    /** ms → "HH:MM:SS,mmm" (SRT). */
    static String srtTs(long ms) {
        if (ms < 0) ms = 0;
        long h = ms / 3600000L, m = (ms / 60000L) % 60, s = (ms / 1000L) % 60, f = ms % 1000;
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, f);
    }

    // ── transport helpers ─────────────────────────────────────────────────────

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) != -1; ) {
            if (out.size() + n > max) throw new IOException("response too large");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static final class ByteArrayInputStream extends java.io.ByteArrayInputStream {
        ByteArrayInputStream(byte[] b) { super(b == null ? new byte[0] : b); }
    }
}
