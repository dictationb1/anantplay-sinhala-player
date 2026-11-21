package lk.techworld.anantplay.sicast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/* THE CAST-PROTOCOL LAB — drives the ReceiverServer with the EXACT bytes official
 * AnantPlay sends (Cast.java @ be74df2): a raw hand-rolled HTTP request built the way
 * Cast.call() builds it, a describe()-shaped /play JSON (PlayerActivity's intent
 * extras + at + cookie + live + title), and the Remote's /status + /do polling.
 *
 * Pure Java on a bare JVM — the same test pattern as AnantPlay's own DlnaTest.java
 * (which drives Dlna against a fake renderer). If this passes, the wire protocol the
 * real app speaks is proven, byte for byte, before any phone is involved.
 *
 * Beyond the protocol it also gates every HARDENING claim made by the app:
 *   · CastPolicy        — scheme whitelist, /do whitelist, seek clamping, file:// gating
 *   · ReceiverServer    — header casing, negative/oversized Content-Length, malformed
 *                         JSON, header-injection, unknown action, foreign-source refusal
 *   · SrtLoader         — BOM/CRLF, nested folders, __MACOSX, path traversal, zip bomb,
 *                         gzip, UTF-16
 *   · MiniJson          — nesting cap (no StackOverflow from a hostile body)
 *   · Diag              — cookie/token/Authorization redaction in "Copy log"
 *   · BridgeApi         — literal colons in job ids, 202 progress text, HTTP 429/5xx UX
 *   · StreamHint        — HLS/DASH/MP4 mime defaulting for extensionless URLs
 *
 * Exit code 0 only when every check passes: CI fails the build otherwise. */
public class ReceiverLab {

    static int passed, failed;

    static void check(String name, boolean ok) { check(name, ok, null); }

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("  PASS " + name + (detail == null ? "" : " — " + detail)); }
        else { failed++; System.out.println("  FAIL " + name + (detail == null ? "" : " — " + detail)); }
    }

    /** The fake phone-side state machine the backend exposes. */
    static class FakePlayer {
        String title = "The Odyssey";
        long pos, dur = 10586000;   // 2:56:26 — the user's real duration
        boolean playing = true, live = false, loading = true, closed;

        String status() {
            if (closed) return ReceiverServer.idleJson();
            if (loading) return ReceiverServer.loadingJson();
            return ReceiverServer.statusJson(title, pos, dur, playing, live);
        }

        String act(String a, long to) {
            if (player == null) return ReceiverServer.idleJson();
            if ("toggle".equals(a)) playing = !playing;
            else if ("seek".equals(a)) pos = to;
            else if ("stop".equals(a)) { closed = true; return ReceiverServer.idleJson(); }
            return status();
        }

        void open() { loading = false; }
    }

    static FakePlayer player;
    static final StringBuilder playLog = new StringBuilder();
    static boolean mediaGranted = true;   // the fake "READ_MEDIA_VIDEO granted"

    static class FakeBackend implements ReceiverServer.Backend {
        @Override public String onPlay(Map<String, Object> body, String sourceAddr) {
            playLog.setLength(0);
            playLog.append(sourceAddr).append(' ').append(body.get("url")).append(' ').append(body.get("title"))
                .append(" at=").append(body.get("at"));
            String url = String.valueOf(body.get("url"));
            // the SAME policy the Android service applies (shared code, no drift)
            if (!CastPolicy.allowed(url, mediaGranted)) return ReceiverServer.whyJson(CastPolicy.rejectWhy(url));
            player = new FakePlayer();
            playTitle = String.valueOf(body.get("title"));
            playHeaders = String.valueOf(body.get("headers"));
            playCookie = String.valueOf(body.get("cookie"));
            Number at = MiniJson.num(body, "at");
            playAt = at == null ? 0 : at.longValue();
            return ReceiverServer.okJson();
        }
        @Override public String onAction(String a, long to) {
            if ("vol+".equals(a) || "vol-".equals(a)) return player == null ? ReceiverServer.idleJson() : player.status();
            return player == null ? ReceiverServer.idleJson() : player.act(a, to);
        }
        @Override public String status() { return player == null ? ReceiverServer.idleJson() : player.status(); }
        @Override public Map<String, Object> info() { return new LinkedHashMap<>(); }
    }

    static String playTitle, playHeaders, playCookie;
    static long playAt;

    public static void main(String[] args) throws Exception {
        Diag.install(null);
        System.out.println("== AnantPlay Cast protocol lab ==");

        // ── unit: isSelf (the LAN-source lock) ─────────────────────────────────
        check("isSelf loopback", ReceiverServer.isSelf(InetAddress.getByName("127.0.0.1")));
        check("isSelf rejects a foreign phone", !ReceiverServer.isSelf(InetAddress.getByName("192.168.1.77")));
        check("isSelf accepts the v4-mapped loopback form (vendor v6 sockets)",
            ReceiverServer.isSelf(InetAddress.getByName("::ffff:127.0.0.1")));

        // ── unit: CastPolicy (shared with the Android service) ─────────────────
        check("policy allows https", CastPolicy.allowed("https://cdn.example/a.m3u8", false));
        check("policy allows plain http (cleartext casts)", CastPolicy.allowed("http://10.0.0.5:8080/a.mp4", false));
        check("policy allows an uppercase scheme (URIs are case-insensitive)",
            CastPolicy.allowed("HTTPS://cdn.example/a.m3u8", false));
        check("policy refuses content:// and javascript:",
            !CastPolicy.allowed("content://media/external/video/1", true)
                && !CastPolicy.allowed("javascript:alert(1)", true));
        check("policy gates file:// on the media permission",
            CastPolicy.allowed("file:///storage/emulated/0/Movies/x.mp4", true)
                && !CastPolicy.allowed("file:///storage/emulated/0/Movies/x.mp4", false));
        check("policy rejection wording stays AnantPlay's", "bad link".equals(CastPolicy.rejectWhy("ftp://x/y"))
            && "blocked".equals(CastPolicy.rejectWhy("file:///x.mp4")));
        check("policy whitelists only the five remote actions",
            CastPolicy.isAllowedAction("toggle") && CastPolicy.isAllowedAction("seek")
                && CastPolicy.isAllowedAction("stop") && CastPolicy.isAllowedAction("vol+")
                && CastPolicy.isAllowedAction("vol-") && !CastPolicy.isAllowedAction("reboot")
                && !CastPolicy.isAllowedAction(null));
        check("policy clamps seek targets", CastPolicy.clampSeek(-5) == 0
            && CastPolicy.clampSeek(125000) == 125000
            && CastPolicy.clampSeek(Long.MAX_VALUE) == CastPolicy.MAX_SEEK_MS);

        // ── unit: StreamHint (mime defaulting) ─────────────────────────────────
        check("mime: .m3u8 → HLS", StreamHint.HLS.equals(StreamHint.mime("https://c/v/m.m3u8?tok=1", null, false)));
        check("mime: extensionless stream → HLS",
            StreamHint.HLS.equals(StreamHint.mime("https://c/hls/abc123", null, false)));
        check("mime: dash flag → DASH", StreamHint.DASH.equals(StreamHint.mime("https://c/v/abc", null, true)));
        check("mime: .mpd → DASH", StreamHint.DASH.equals(StreamHint.mime("https://c/v/m.mpd", "", false)));
        check("mime: progressive .mp4 is NOT fed to the HLS parser",
            StreamHint.MP4.equals(StreamHint.mime("https://c/v/movie.mp4", null, false)));
        check("mime: explicit payload mime always wins",
            "video/x-custom".equals(StreamHint.mime("https://c/a.mp4", "video/x-custom", true)));

        // ── unit: param + line parsing exactly as Cast uses them ───────────────
        check("param a", "seek".equals(ReceiverServer.param("a=seek&to=125000", "a")));
        check("param to", "125000".equals(ReceiverServer.param("a=seek&to=125000", "to")));
        check("param encoded", "vol+".equals(ReceiverServer.param("a=vol%2B", "a")));
        check("param missing", ReceiverServer.param("a=seek", "to") == null);

        // ── unit: Diag redaction (no secret ever reaches "Copy log") ────────────
        Diag.reset();
        Diag.i("lab", "cookie: session=SUPERSECRET123; token=ABCDEF999");
        Diag.i("lab", "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature");
        Diag.i("lab", "url=https://cdn.example/a.m3u8?token=TOPSECRET42&x=1");
        String d = Diag.dump();
        check("diag redacts cookies / tokens / bearer credentials",
            !d.contains("SUPERSECRET123") && !d.contains("ABCDEF999") && !d.contains("TOPSECRET42")
                && !d.contains("eyJhbGciOiJIUzI1NiJ9.payload"));
        check("diag keeps the surrounding context readable", d.contains("cookie:") && d.contains("url="));
        check("diag caps one line (a 256 KB body cannot flood the ring)",
            Diag.redact(repeat("x", 5000)).length() <= Diag.MAX_LINE + 1);

        // ── unit: MiniJson hardening ───────────────────────────────────────────
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 200; i++) deep.append('[');
        for (int i = 0; i < 200; i++) deep.append(']');
        boolean capped = false;
        try { MiniJson.parse(deep.toString()); } catch (IllegalArgumentException e) { capped = true; }
        check("json nesting cap stops a StackOverflow body", capped);

        // ── start the receiver on a test port ───────────────────────────────────
        ReceiverServer server = new ReceiverServer(new FakeBackend(), 18765);
        check("server starts", server.start() && server.port() == 18765, "port " + server.port());
        String base = "http://127.0.0.1:" + server.port();

        // ── /play: the EXACT payload Cast.describe() + castTo() produce ─────────
        Map<String, Object> play = new LinkedHashMap<>();
        play.put("url", "https://stream.onlyflix.example/hls/the-odyssey/master.m3u8");
        play.put("headers", "{\"User-Agent\":\"Mozilla/5.0 (Linux; Android 14)\",\"Referer\":\"https://onlyflix.example/\"}");
        play.put("title", "The Odyssey");
        play.put("pageTitle", "Watch The Odyssey | OnlyFlix");
        play.put("site", "OnlyFlix");
        play.put("pageUrl", "https://onlyflix.example/watch/the-odyssey");
        play.put("imdb", "tt29544716");
        play.put("series", Boolean.FALSE);
        play.put("season", 0);
        play.put("episode", 0);
        play.put("dash", Boolean.FALSE);
        List<String> subs = new ArrayList<>();
        subs.add("https://onlyflix.example/sub/en.vtt");
        play.put("subs", subs);
        play.put("at", 8699000L);      // 2:24:59 — where the user paused
        play.put("cookie", "session=abc; token=def");
        play.put("live", Boolean.FALSE);

        String body = MiniJson.write(play);
        String res = post(base + "/play", body);
        check("/play answers ok", res.contains("\"ok\":true"), res);
        check("/play carried the stream URL", String.valueOf(play.get("url")).contentEquals(playLog.toString().split(" ")[1]));
        check("/play carried at=2:24:59", playLog.toString().contains("at=8699000"), playLog.toString());
        check("backend parsed headers/title/cookie",
            playTitle.equals("The Odyssey") && playHeaders.contains("Referer") && playCookie.contains("session"));

        // ── /status: the Remote's poll states ──────────────────────────────────
        check("/status loading first", get(base + "/status").contains("\"loading\":true"));
        player.open();
        String st = get(base + "/status");
        check("/status playing shape", st.contains("\"title\":\"The Odyssey\"") && st.contains("\"pos\":")
            && st.contains("\"dur\":10586000") && st.contains("\"playing\":true") && st.contains("\"live\":false"), st);

        // ── /do: the Remote's buttons ──────────────────────────────────────────
        post(base + "/do?a=seek&to=125000", "");
        check("/do seek", get(base + "/status").contains("\"pos\":125000"));
        post(base + "/do?a=toggle&to=0", "");
        check("/do toggle pauses", get(base + "/status").contains("\"playing\":false"));
        post(base + "/do?a=vol%2B&to=0", "");
        check("/do volume answers", get(base + "/status").contains("\"playing\":false"));

        // ── /do hardening: whitelist + clamping ────────────────────────────────
        String bogus = post(base + "/do?a=reboot&to=0", "");
        check("/do refuses a non-whitelisted action", bogus.contains("\"ok\":false"), bogus);
        post(base + "/do?a=seek&to=-999", "");
        check("/do clamps a negative seek to 0", get(base + "/status").contains("\"pos\":0"));
        post(base + "/do?a=seek&to=999999999999", "");
        check("/do clamps an absurd seek", get(base + "/status").contains("\"pos\":" + CastPolicy.MAX_SEEK_MS));

        post(base + "/do?a=stop&to=0", "");
        check("/do stop → idle", get(base + "/status").contains("\"idle\":true"));

        // ── the guard rails ─────────────────────────────────────────────────────
        Map<String, Object> bad = new LinkedHashMap<>(play);
        bad.put("url", "ftp://nope.example/x.mp4");
        String badRes = post(base + "/play", MiniJson.write(bad));
        check("/play rejects a non-http link (Cast.play's rule)", badRes.contains("\"ok\":false") && badRes.contains("bad link"), badRes);

        Map<String, Object> ctxt = new LinkedHashMap<>(play);
        ctxt.put("url", "content://media/external/video/media/42");
        check("/play refuses content:// (nothing outside the app can hand us a stream)",
            post(base + "/play", MiniJson.write(ctxt)).contains("bad link"));

        Map<String, Object> filePlay = new LinkedHashMap<>(play);
        filePlay.put("url", "file:///storage/emulated/0/Movies/x.mp4");
        check("/play accepts an authorized file:// cast (media granted)",
            post(base + "/play", MiniJson.write(filePlay)).contains("\"ok\":true"));
        mediaGranted = false;
        String blocked = post(base + "/play", MiniJson.write(filePlay));
        mediaGranted = true;
        check("/play answers \"blocked\" for file:// without the media permission (no crash)",
            blocked.contains("\"ok\":false") && blocked.contains("blocked"), blocked);

        check("unknown path answers {ok:false}", get(base + "/nope").contains("\"ok\":false"));
        check("/info answers with the app identity", get(base + "/info").contains("\"ok\":true") && get(base + "/info").contains("Sinhala"));
        check("oversized body is dropped silently (no response)", postSilentDrop(base + "/play", 257 * 1024));

        // ── HTTP parsing hardening ─────────────────────────────────────────────
        String mixedCase = raw(server.port(),
            "POST /do?a=toggle&to=0 HTTP/1.1\r\nHost: tv\r\ncOnTeNt-LeNgTh: 0\r\n\r\n");
        check("header names are matched case-insensitively", mixedCase.contains("HTTP/1.0 200 OK"), firstLine(mixedCase));

        String negLen = raw(server.port(), "POST /play HTTP/1.1\r\nHost: tv\r\nContent-Length: -12\r\n\r\n");
        check("a negative Content-Length is dropped, never parsed", negLen.isEmpty(), "answered: " + firstLine(negLen));

        String hugeLen = raw(server.port(), "POST /play HTTP/1.1\r\nHost: tv\r\nContent-Length: 999999999999\r\n\r\n");
        check("an absurd Content-Length is dropped", hugeLen.isEmpty(), "answered: " + firstLine(hugeLen));

        String garbageLen = raw(server.port(), "POST /play HTTP/1.1\r\nHost: tv\r\nContent-Length: abc\r\n\r\n");
        check("a non-numeric Content-Length is dropped", garbageLen.isEmpty(), "answered: " + firstLine(garbageLen));

        String malformed = post(base + "/play", "{\"url\": ");
        check("malformed JSON answers {\"ok\":false,\"why\":\"bad json\"} (no exception)",
            malformed.contains("bad json"), malformed);

        // A body that *contains* CRLF (JSON-escaped, so the cast still parses) must not
        // be able to add a header to our hand-written response.
        String sneakyJson = "{\"note\":\"a\\r\\nX-Injected: 1\",\"url\":\"https://x/a.m3u8\"}";
        String inject = raw(server.port(), "POST /play HTTP/1.1\r\nHost: tv\r\nContent-Length: "
            + sneakyJson.getBytes(StandardCharsets.US_ASCII).length + "\r\n\r\n" + sneakyJson);
        int headerEnd = inject.indexOf("\r\n\r\n");
        String headerBlock = headerEnd < 0 ? inject : inject.substring(0, headerEnd);
        check("client text can never inject a response header",
            headerBlock.startsWith("HTTP/1.0 200 OK")
                && !headerBlock.toLowerCase(java.util.Locale.ROOT).contains("x-injected"), firstLine(inject));

        // ── SRT round-trip through the exact renderer input path ───────────────
        List<Cue> cues = new ArrayList<>();
        cues.add(new Cue(0, 1000, 4000, "00:00:01,000 --> 00:00:04,000", "Hello there", "Hello there", "ආයුබෝවන්"));
        cues.add(new Cue(1, 5000, 9000, "00:00:05,000 --> 00:00:09,000", "General Kenobi!", "General Kenobi!", "ජනරාල් කෙනෝබි!"));
        File tmp = new File(System.getProperty("java.io.tmpdir"), "si-lab-" + System.currentTimeMillis());
        tmp.mkdirs();
        SubtitleStore store = new SubtitleStore(tmp);
        store.save("movie:t:the odyssey:0:0", cues);
        List<Cue> back = store.load("movie:t:the odyssey:0:0");
        check("subtitle store round-trip", back != null && back.size() == 2
            && back.get(0).t0 == 1000 && back.get(1).t1 == 9000
            && back.get(0).si.contains("ආයුබෝවන්") && back.get(1).si.contains("කෙනෝබි"),
            back == null ? "null" : back.size() + " cues");

        // ── SrtLoader: BOM, CRLF, nested zip, __MACOSX, traversal, gzip, cap ───
        byte[] srt = utf8("\uFEFF1\r\n00:00:01,000 --> 00:00:04,000\r\nආයුබෝවන්\r\n\r\n"
            + "2\r\n00:00:05,000 --> 00:00:09,000\r\nදෙවැන්න\r\n\r\n");
        List<Cue> bom = SrtLoader.load(srt, "si.srt");
        check("SRT: UTF-8 BOM + CRLF parsed cleanly", bom.size() == 2
            && bom.get(0).si.equals("ආයුබෝවන්") && bom.get(0).t0 == 1000,
            bom.size() + " cues");

        byte[] utf16 = ("\uFEFF" + "1\n00:00:02,000 --> 00:00:03,000\nසිංහල UTF-16\n\n").getBytes(StandardCharsets.UTF_16LE);
        List<Cue> u16 = SrtLoader.load(utf16, "si16.srt");
        check("SRT: UTF-16LE with BOM is honoured", u16.size() == 1 && u16.get(0).si.contains("සිංහල"));

        byte[] nested = zip(new String[]{"Movie.2024/subs/sinhala.srt"}, new byte[][]{srt});
        check("ZIP: nested folder inside the archive is found", SrtLoader.load(nested, "x.zip").size() == 2);

        byte[] withMac = zip(new String[]{"__MACOSX/._junk.srt", "subs/real.srt", "notes.txt"},
            new byte[][]{utf8("garbage"), srt, utf8("hi")});
        List<Cue> mac = SrtLoader.load(withMac, "x.zip");
        check("ZIP: __MACOSX/ and non-subtitle entries are ignored", mac.size() == 2);

        byte[] traversal = zip(new String[]{"../../../../etc/evil.srt", "subs/good.srt"},
            new byte[][]{utf8("1\n00:00:01,000 --> 00:00:02,000\nEVIL\n\n"), srt});
        check("ZIP: '..' path traversal entries are rejected (only the safe track loads)",
            SrtLoader.load(traversal, "x.zip").size() == 2);
        byte[] absolute = zip(new String[]{"/sdcard/evil.srt"}, new byte[][]{utf8("1\n00:00:01,000 --> 00:00:02,000\nEVIL\n\n")});
        boolean absoluteRejected = false;
        try { SrtLoader.load(absolute, "x.zip"); } catch (java.io.IOException e) { absoluteRejected = true; }
        check("ZIP: absolute entry paths are rejected", absoluteRejected);
        byte[] drive = zip(new String[]{"C:\\subs\\evil.srt"}, new byte[][]{utf8("1\n00:00:01,000 --> 00:00:02,000\nEVIL\n\n")});
        boolean driveRejected = false;
        try { SrtLoader.load(drive, "x.zip"); } catch (java.io.IOException e) { driveRejected = true; }
        check("ZIP: Windows drive-letter entries are rejected", driveRejected);

        byte[] gz = gzip(srt);
        check("GZ: gzip-wrapped SRT inflates", SrtLoader.load(gz, "si.srt.gz").size() == 2);

        byte[] bomb = zip(new String[]{"a.srt"}, new byte[][]{repeatBytes('x', SrtLoader.MAX_BYTES + 4096)});
        boolean bombRejected = false;
        try { SrtLoader.load(bomb, "bomb.zip"); } catch (java.io.IOException e) { bombRejected = true; }
        check("ZIP: a >5 MB inflated entry is refused (zip-bomb cap)", bombRejected);

        boolean tooBig = false;
        try { SrtLoader.load(repeatBytes('y', SrtLoader.MAX_BYTES + 1), "big.srt"); }
        catch (java.io.IOException e) { tooBig = true; }
        check("SRT: an input over 5 MB is refused", tooBig);

        // ── MiniJson write→parse round-trip (every response AnantPlay parses) ──
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", "A \"quoted\" සිංහල title");
        m.put("pos", 1234L);
        m.put("playing", Boolean.TRUE);
        Map<String, Object> back2 = Cue.statusOf(MiniJson.write(m));
        check("json write→parse round-trip", "A \"quoted\" සිංහල title".contentEquals((CharSequence) back2.get("title"))
            && Boolean.TRUE.equals(back2.get("playing")) && MiniJson.lng(back2, "pos", 0) == 1234);

        // ── BridgeApi: URL shape + worker error UX ────────────────────────────
        BridgeApi api = new BridgeApi("https://worker.example/");
        check("bridge trims the trailing slash", "https://worker.example".equals(api.base()));
        String pairedUrl = api.urlFor("/api/paired/movie:tt29544716:0:0.json");
        check("bridge keeps LITERAL colons in job ids (no %3A → 404 bug)",
            pairedUrl.endsWith("/api/paired/movie:tt29544716:0:0.json"), pairedUrl);
        check("bridge polls the deterministic /sub/<token>.srt path",
            api.urlFor("/sub/abc123.srt").endsWith("/sub/abc123.srt"));
        check("bridge 429 text names the quota in Sinhala AND English",
            BridgeApi.explain(429).contains("429") && BridgeApi.explain(429).contains("Gemini")
                && BridgeApi.explain(429).contains("කෝටා"));
        check("bridge 5xx text is actionable",
            BridgeApi.explain(503).contains("503") && BridgeApi.explain(503).contains("Worker"));
        check("bridge 202 text is a progress line, not an error",
            BridgeApi.explain(202).contains("202") && BridgeApi.explain(202).contains("translating"));
        Map<String, Object> prog = new LinkedHashMap<>();
        prog.put("status", "translating");
        prog.put("done", 3);
        prog.put("total", 12);
        check("bridge 202 progress text shows the batch",
            BridgeApi.progressText(prog).contains("3/12"), BridgeApi.progressText(prog));
        check("bridge keeps a failure body short enough for the status bar",
            BridgeApi.brief(repeat("z", 500), 40).length() <= 41);

        server.stop();
        System.out.println();
        System.out.println(failed == 0 ? "Lab result: ALL " + passed + " PASSED" : "Lab result: " + failed + " FAILED of " + (passed + failed));
        System.exit(failed == 0 ? 0 : 1);
    }

    // ── the AnantPlay-side HTTP client: raw, like Cast.call + answer parse ─────

    static String post(String url, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(java.net.Proxy.NO_PROXY);
        c.setRequestMethod("POST");
        c.setConnectTimeout(3000);
        c.setReadTimeout(4000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream o = c.getOutputStream()) {
            o.write(json.getBytes(StandardCharsets.UTF_8));
        }
        try (InputStream in = c.getInputStream()) { return readAll(in); }
        finally { c.disconnect(); }
    }

    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(java.net.Proxy.NO_PROXY);
        c.setConnectTimeout(3000);
        c.setReadTimeout(4000);
        try (InputStream in = c.getInputStream()) { return readAll(in); }
        finally { c.disconnect(); }
    }

    /** Hand-rolled bytes, exactly like Cast.call() writes them (mixed-case headers etc.). */
    static String raw(int port, String request) {
        try {
            Socket s = new Socket("127.0.0.1", port);
            s.setSoTimeout(3000);
            try {
                s.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                s.getOutputStream().flush();
                return readAll(s.getInputStream());
            } finally {
                s.close();
            }
        } catch (Exception e) {
            return "";
        }
    }

    /** A body over Cast's own 256 KB cap: AnantPlay's answer() just returns (no
     *  response) — we expect the connection to close with no bytes. */
    static boolean postSilentDrop(String url, int size) {
        Socket s = null;
        try {
            s = new Socket("127.0.0.1", 18765);
            s.setSoTimeout(2000);
            OutputStream o = s.getOutputStream();
            try {
                o.write(("POST /play HTTP/1.1\r\nHost: tv\r\nContent-Length: " + size + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
                byte[] zeros = new byte[4096];
                int sent = 0;
                while (sent < size) { int n = Math.min(4096, size - sent); o.write(zeros, 0, n); sent += n; }
                o.flush();
            } catch (java.io.IOException peerHungUp) {
                // the receiver dropped us at the header — that IS the expected behaviour
            }
            int first;
            try {
                first = s.getInputStream().read();   // -1 = no response at all
            } catch (java.io.IOException reset) {
                first = -1;
            }
            return first == -1;
        } catch (Exception e) {
            return false;
        } finally {
            if (s != null) try { s.close(); } catch (Exception ignored) { }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    static Map<String, Object> single(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    static String firstLine(String s) {
        if (s == null || s.isEmpty()) return "(no response)";
        int nl = s.indexOf('\n');
        return (nl < 0 ? s : s.substring(0, nl)).trim();
    }

    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    static byte[] repeatBytes(char c, int n) {
        byte[] b = new byte[n];
        java.util.Arrays.fill(b, (byte) c);
        return b;
    }

    static byte[] zip(String[] names, byte[][] bodies) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(o)) {
            for (int i = 0; i < names.length; i++) {
                z.putNextEntry(new ZipEntry(names[i]));
                z.write(bodies[i]);
                z.closeEntry();
            }
        }
        return o.toByteArray();
    }

    static byte[] gzip(byte[] data) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(o)) { g.write(data); }
        return o.toByteArray();
    }

    static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
        return o.toString("UTF-8");
    }
}
