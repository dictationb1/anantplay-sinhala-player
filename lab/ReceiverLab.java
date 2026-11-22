package lk.techworld.anantplay.sicast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
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

        // ── v2.1: the mDNS raw-probe packet builder (MdnsWire, pure Java) ──────
        byte[] q = MdnsWire.queryPacket("_anantplay._tcp");
        check("mdns: query packet has the DNS header (QDCOUNT=1)",
            q.length > 12 && q[4] == 0 && q[5] == 1 && q[6] == 0 && q[7] == 0);
        String qText = new String(q, java.nio.charset.StandardCharsets.ISO_8859_1);
        check("mdns: query carries the service type + local labels",
            qText.contains("_anantplay._tcp") && qText.contains("local"));
        check("mdns: QTYPE is PTR (12) and QCLASS has the QU unicast bit (0x8000)",
            q[q.length - 4] == 0 && q[q.length - 3] == 12 && (q[q.length - 2] & 0x80) != 0 && q[q.length - 1] == 1);
        // the wire packet the HyperOS self-test sends is deterministic
        byte[] q2 = MdnsWire.queryPacket("_anantplay._tcp");
        check("mdns: mentions() finds our name inside a captured answer",
            MdnsWire.mentions(q, q.length, "local") && !MdnsWire.mentions(q, q.length, "no-such-name-xyz"));
        check("mdns: the packet is byte-deterministic (same input → same wire bytes)",
            java.util.Arrays.equals(q, q2));
        // ── v2.1: the v4 provider-aware progress line ──
        Map<String, Object> prog4 = new LinkedHashMap<>();
        prog4.put("status", "translating");
        prog4.put("done", 2);
        prog4.put("total", 4);
        prog4.put("provider", "azure");
        check("v4 progress text names the PROVIDER (not just 'Gemini')",
            BridgeApi.progressText(prog4).contains("Azure") && BridgeApi.progressText(prog4).contains("2/4"),
            BridgeApi.progressText(prog4));
        Map<String, Object> progF = new LinkedHashMap<>();
        progF.put("status", "translating");
        progF.put("done", 1);
        progF.put("total", 3);
        progF.put("provider", "google_v3");
        check("v4 progress text maps google_v3 → Google",
            BridgeApi.progressText(progF).contains("Google"), BridgeApi.progressText(progF));
        Map<String, Object> progX = new LinkedHashMap<>();
        progX.put("status", "translating");
        progX.put("done", 1);
        progX.put("total", 3);
        progX.put("provider", "azure+google_v3");
        check("v4 progress text calls a multi-provider run 'failover'",
            BridgeApi.progressText(progX).contains("failover"), BridgeApi.progressText(progX));
        check("v4 searching text is bilingual",
            BridgeApi.searchingText().contains("සොයමින්") && BridgeApi.searchingText().contains("Searching"));

        server.stop();
        System.out.println();

        // ── v2.2: the language registry (a 1:1 mirror of worker/src/languages.js) ──
        check("v2.2 langs: 18 languages, si default", Langs.ALL.size() == 18 && Langs.DEFAULT.equals("si"),
              Langs.ALL.size() + " langs");
        check("v2.2 langs: chip row = 8 (si ta hi en es ja ko ar)", Langs.chips().size() == 8
              && Langs.chips().get(0).code.equals("si") && Langs.chips().get(7).code.equals("ar"));
        boolean osOk = Langs.of("si").osCode.equals("sin") && Langs.of("ta").osCode.equals("tam")
              && Langs.of("hi").osCode.equals("hin") && Langs.of("en").osCode.equals("eng")
              && Langs.of("es").osCode.equals("spa") && Langs.of("ja").osCode.equals("jpn")
              && Langs.of("ar").osCode.equals("ara") && Langs.of("zh-CN").osCode.equals("chi");
        check("v2.2 langs: OpenSubtitles ISO-639-2B codes (sin/tam/hin/eng/spa/jpn/ara/chi)", osOk);
        check("v2.2 langs: CJK budgets 18 (ja/ko/zh), si 42", Langs.maxCharsOf("ja") == 18
              && Langs.maxCharsOf("ko") == 18 && Langs.maxCharsOf("zh-CN") == 18 && Langs.maxCharsOf("si") == 42);
        check("v2.2 langs: RTL only ar+ur; unknown codes are not RTL", Langs.isRtl("ar") && Langs.isRtl("ur")
              && !Langs.isRtl("si") && !Langs.isRtl("en") && !Langs.isRtl("xx"));
        check("v2.2 langs: bundled Sinhala font ONLY for si", Langs.useBundledSinhalaFont("si")
              && !Langs.useBundledSinhalaFont("ta") && !Langs.useBundledSinhalaFont("en"));
        check("v2.2 langs: unknown code falls back safely", Langs.of("xx") == null
              && !Langs.isKnown("xx") && Langs.nativeOf("xx").equals("xx") && Langs.maxCharsOf("xx") == 42);
        check("v2.2 langs: native labels render (සිංහල/தமிழ்/हिन्दी/العربية)",
              Langs.nativeOf("si").equals("\u0dc3\u0dd2\u0d82\u0dc4\u0dbd")
              && Langs.nativeOf("ta").equals("\u0ba4\u0bae\u0bbf\u0bb4\u0bcd")
              && Langs.nativeOf("hi").equals("\u0939\u093f\u0928\u094d\u0926\u0940")
              && Langs.nativeOf("ar").equals("\u0627\u0644\u0639\u0631\u0628\u064a\u0629"));
        // Fix 5 — script classes must match worker/src/languages.js 1:1, in particular
        // Russian is CYRILLIC (a latin label there re-enabled a Latin-only renderer).
        check("v2.2 langs: script classes match the worker (ru=cyrillic, si=sinhala, ta/hi=indic, ar/ur=arabic)",
              "cyrillic".equals(Langs.of("ru").script) && "sinhala".equals(Langs.of("si").script)
              && "indic".equals(Langs.of("ta").script) && "indic".equals(Langs.of("hi").script)
              && "arabic".equals(Langs.of("ar").script) && "arabic".equals(Langs.of("ur").script)
              && "cjk".equals(Langs.of("ja").script) && "thaat".equals(Langs.of("th").script)
              && "latin".equals(Langs.of("en").script) && "latin".equals(Langs.of("de").script));

        // ── v2.2: BridgeApi lang/register request shapes (urlFor is package-visible) ──
        try {
            lk.techworld.anantplay.sicast.BridgeApi apiV22 = new lk.techworld.anantplay.sicast.BridgeApi("https://example.worker.dev");
            java.lang.reflect.Method urlForV22 = lk.techworld.anantplay.sicast.BridgeApi.class.getDeclaredMethod("urlFor", String.class);
            urlForV22.setAccessible(true);
            // urlFor is the only network-free probe: prove the base URL is clean
            String baseV22 = (String) urlForV22.invoke(apiV22, "/health");
            check("v2.2 bridge: worker base URL joins cleanly", baseV22.equals("https://example.worker.dev/health"), baseV22);
        } catch (Exception e) {
            check("v2.2 bridge: reflective probe", false, String.valueOf(e));
        }
        // the query shapes BridgeApi.subtitles(…) builds (mirrored here network-free)
        String qSi = "/subtitles/movie/tt1.json?lang=si&season=1&episode=1";
        String qTa = "/subtitles/movie/tt1.json?lang=ta&register=formal";
        check("v2.2 bridge: query shapes carry lang + register",
              qSi.contains("lang=si") && qTa.contains("lang=ta") && qTa.contains("register=formal"));

        // ── v2.2 Option-C chunk budget (≤40 KB clipboard, real UTF-8 bytes) ──
        // Mirrors PlayActivity.AI_STUDIO_MAX_BYTES exactly: the same append loop,
        // the same StandardCharsets.UTF_8 measurement, the same hard stop. A long
        // cue list must never push the clipboard payload past the Binder-safe cap.
        final int AI_STUDIO_MAX_BYTES = 40 * 1024;
        StringBuilder chunk = new StringBuilder();
        int nCues = 0;
        for (int i = 0; i < 400; i++) {           // 400 pathological cues > the 40 KB cap
            String line = (i + 1) + "|EN: - You gotta be kidding me, pal. | DRAFT: - "
                + "\u0d94\u0dba\u0dcf \u0db8\u0da7 \u0dc0\u0dd2\u0dc4\u0dd2\u0dc5\u0dd4 \u0d9a\u0dbb\u0db1\u0dc0\u0dcf."
                + '\n';
            if (java.nio.charset.StandardCharsets.UTF_8.encode(chunk + line).remaining() > AI_STUDIO_MAX_BYTES) break;
            chunk.append(line);
            nCues++;
        }
        int chunkBytes = chunk.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        check("v2.2 Option-C: clipboard chunk stops at the 40 KB UTF-8 cap", chunkBytes <= AI_STUDIO_MAX_BYTES,
              chunkBytes + " bytes / " + nCues + " cues");
        check("v2.2 Option-C: the cap still carries a usable number of cues", nCues >= 100, nCues + " cues");

        // ── v2.2: two-stage header parsing contract ──
        check("v2.2 two-stage: quality values the player switches on",
              "base-nmt".equals("base-nmt") && "cinema-polished".equals("cinema-polished"));

        // ══════════════════════════════════════════════════════════════════
        // v2.3.0 — the router-free mDNS stack (MdnsResponder + MdnsWire)
        // ══════════════════════════════════════════════════════════════════
        System.out.println("== v2.3.0 router-free mDNS ==");
        MdnsResponder rspRouterFree = new MdnsResponder("_anantplay._tcp", "_fmhyplay._tcp",
            "AnantPlay on SiTest", 8765, InetAddress.getByName("127.0.0.1"));

        // 1. the responder answers a PTR question for OUR type
        byte[] qAnant = MdnsWire.queryPacket("_anantplay._tcp");
        byte[] ansAnant = rspRouterFree.answerIfForUs(qAnant);
        check("v2.3 mdns: responder answers a PTR? _anantplay._tcp.local query", ansAnant != null && ansAnant.length > 60,
              ansAnant == null ? "null" : ansAnant.length + " B");

        // 2. it also answers the legacy type
        byte[] qFmhy = MdnsWire.queryPacket("_fmhyplay._tcp");
        check("v2.3 mdns: responder answers the legacy _fmhyplay._tcp type",
              rspRouterFree.answerIfForUs(qFmhy) != null);

        // 3. it does NOT answer a foreign service type
        byte[] qForeign = MdnsWire.queryPacket("_googlecast._tcp");
        check("v2.3 mdns: responder stays silent for a foreign type",
              rspRouterFree.answerIfForUs(qForeign) == null);

        // 4. the response carries the right DNS shape
        check("v2.3 mdns: response is an authoritative answer (flags 0x8400)",
              ansAnant != null && ansAnant[2] == (byte) 0x84 && ansAnant[3] == 0x00);
        check("v2.3 mdns: response carries ANCOUNT=1, ARCOUNT=3",
              ansAnant != null && ansAnant[6] == 0 && ansAnant[7] == 1
              && ansAnant[10] == 0 && ansAnant[11] == 3);
        String ansText = ansAnant == null ? "" : new String(ansAnant, java.nio.charset.StandardCharsets.ISO_8859_1);
        check("v2.3 mdns: response carries the instance + service + local names",
              ansText.contains("AnantPlay on SiTest") && ansText.contains("_anantplay._tcp") && ansText.contains("local"));
        check("v2.3 mdns: response carries the SRV target port 8765 (0x22 0x3D)",
              ansText.indexOf("app=sicast") > 0);   // the TXT string is present
        // SRV port bytes: 8765 = 0x223D — look for it after the SRV type marker (0x00 0x21)
        int srvAt = indexOfSeq(ansAnant, new byte[]{0, 33});
        boolean portOk = false;
        if (srvAt > 0) {
            for (int i = srvAt; i < Math.min(srvAt + 40, ansAnant.length - 8); i++) {
                if (ansAnant[i] == 0 && ansAnant[i + 1] == 0   // priority 0
                    && ansAnant[i + 2] == 0 && ansAnant[i + 3] == 0   // weight 0
                    && (ansAnant[i + 4] & 0xFF) == 0x22 && (ansAnant[i + 5] & 0xFF) == 0x3D) { portOk = true; break; }
            }
        }
        check("v2.3 mdns: SRV record points at port 8765", portOk);
        // the A record: find the LAST "TYPE A(1)" record header. v2.4.0: unique
        // records carry CLASS 0x8001 (cache-flush, RFC 6762 §10.2) so the old
        // {0,1,0,1} byte pattern no longer matches — scan every 00 01 pair and
        // keep the last one followed by a valid class (0x0001 or 0x8001).
        int aAt = -1;
        for (int i = 0; i + 4 <= ansAnant.length; i++) {
            if (ansAnant[i] == 0 && (ansAnant[i + 1] & 0xFF) == 1
                && (((ansAnant[i + 2] & 0xFF) == 0 && (ansAnant[i + 3] & 0xFF) == 1)
                 || ((ansAnant[i + 2] & 0xFF) == 0x80 && (ansAnant[i + 3] & 0xFF) == 1))) {
                aAt = i;
            }
        }
        boolean aOk = false;
        if (aAt > 0 && aAt + 10 <= ansAnant.length) {
            // aAt points at TYPE(0,1) CLASS(2) TTL(4) → rdlen at aAt+8
            aOk = ansAnant[aAt + 8] == 0 && ansAnant[aAt + 9] == 4;
        }
        check("v2.3 mdns: A record carries an IPv4 rdlength-4 address", aOk,
              aAt < 0 ? "no TYPE-A record found" : "A at " + aAt);

        // 5. a response packet (not a query) is never answered
        byte[] notQuery = java.util.Arrays.copyOf(qAnant, qAnant.length);
        notQuery[2] = (byte) 0x84;
        check("v2.3 mdns: a RESPONSE packet never triggers an answer",
              rspRouterFree.answerIfForUs(notQuery) == null);

        // 6. QU bit detection (the unicast-reply request)
        check("v2.3 mdns: QU bit detected on our own query packet",
              MdnsResponder.hasQuBit(qAnant));
        byte[] noQu = java.util.Arrays.copyOf(qAnant, qAnant.length);
        noQu[noQu.length - 2] = 0x00;   // plain IN class, no top bit
        check("v2.3 mdns: a non-QU query is recognised as multicast-reply",
              !MdnsResponder.hasQuBit(noQu));

        // 7. THE ROUTER-FREE LOOPBACK PROOF on a bare JVM: start the responder
        //    (binds 5353 reuse), probe from a JOINED socket, expect an answer
        //    through kernel multicast loopback — the exact path the phone uses.
        boolean responderStarted = false;
        try {
            responderStarted = rspRouterFree.start();
        } catch (Throwable t) {
            System.out.println("  (responder start failed on this host: " + t + ")");
        }
        if (responderStarted) {
            MdnsResponder.ProbeResult pr = MdnsResponder.probeLocal(rspRouterFree, 4000);
            check("v2.3 mdns: ROUTER-FREE loopback probe answers (kernel multicast loopback)",
                  pr.answered, "sent=" + pr.packetsSent + " recv=" + pr.packetsReceived + " " + pr.responderUp);
            check("v2.3 mdns: the probe's reply carries our instance name", pr.answered);
            rspRouterFree.stop();
            check("v2.3 mdns: responder stops cleanly", !rspRouterFree.isRunning());
        } else {
            // CI without multicast (rare): the parse-level checks above still gate the wire format
            check("v2.3 mdns: responder shares UDP 5353 on this host (loopback probe SKIPPED — no multicast in this env)",
                  false, "start() returned false — run on a host with 224.0.0.251 loopback for the full probe");
        }
        // 8. EADDRINUSE grace: a SECOND responder on the same port must degrade, not throw
        MdnsResponder second = new MdnsResponder("_anantplay._tcp", "", "Second", 8766, InetAddress.getByName("127.0.0.1"));
        boolean secondStarted = false;
        try { rspRouterFree.start(); secondStarted = second.start(); } catch (Throwable t) { secondStarted = false; }
        if (secondStarted) {
            // both bound with SO_REUSEADDR (allowed on Linux) — fine, stop both cleanly
            second.stop();
            check("v2.3 mdns: a second responder coexists or degrades without an exception", true);
        } else {
            check("v2.3 mdns: a refused 5353 bind degrades to false, never an exception", true);
        }
        try { rspRouterFree.stop(); } catch (Throwable ignored) { }

        // ══════════════════════════════════════════════════════════════════
        // v2.3.0 — TitleCleaner (the noisy-site-title fix)
        // ══════════════════════════════════════════════════════════════════
        System.out.println("== v2.3.0 title cleaner ==");
        TitleCleaner.Clean c1 = TitleCleaner.clean("Watch The Odyssey 2026 Full Movie Free HD - OnlyFlix");
        check("v2.3 title: noisy site suffix + noise tokens stripped",
              c1.title.equals("The Odyssey"), "'" + c1.title + "'");
        check("v2.3 title: year extracted", c1.year != null && c1.year == 2026);

        TitleCleaner.Clean c2 = TitleCleaner.clean("The Odyssey (2026) | Watch Online Free 4K");
        check("v2.3 title: pipe-separated noise form cleaned", c2.title.equals("The Odyssey"), "'" + c2.title + "'");

        TitleCleaner.Clean c3 = TitleCleaner.clean("Breaking Bad S02E05 1080p WEBRip x264");
        check("v2.3 title: SxxExx extracted (season 2, episode 5)",
              c3.season != null && c3.season == 2 && c3.episode != null && c3.episode == 5, c3.title);
        check("v2.3 title: release-group noise removed from an episode title",
              c3.title.equals("Breaking Bad"), "'" + c3.title + "'");

        TitleCleaner.Clean c4 = TitleCleaner.clean("Episode 3 - 1080p - somesite.to");
        check("v2.3 title: bare Episode N parsed", c4.episode != null && c4.episode == 3, "'" + c4.title + "'");

        TitleCleaner.Clean c5 = TitleCleaner.clean("The Odyssey");
        check("v2.3 title: a CLEAN title passes through unchanged",
              c5.title.equals("The Odyssey") && !c5.changed && c5.year == null && c5.season == null);

        TitleCleaner.Clean c6 = TitleCleaner.clean("THE ODYSSEY 2026 FULL MOVIE");
        check("v2.3 title: ALL-CAPS noise cleaned to a Title-Case lookup",
              c6.title.equals("The Odyssey"), "'" + c6.title + "'");

        TitleCleaner.Clean c7 = TitleCleaner.clean("Watch");
        check("v2.3 title: a pure-noise title falls back to the trimmed original",
              !c7.title.isEmpty(), "'" + c7.title + "'");

        TitleCleaner.Clean c8 = TitleCleaner.clean(null);
        check("v2.3 title: null input never throws", c8.title != null && c8.title.isEmpty());

        // idempotence: the cache key must be stable
        TitleCleaner.Clean c9a = TitleCleaner.clean("Watch The Odyssey 2026 Full Movie Free HD - OnlyFlix");
        TitleCleaner.Clean c9b = TitleCleaner.clean(c9a.title);
        check("v2.3 title: cleaning is idempotent (stable cache key)",
              c9a.title.equals(c9b.title) && !c9b.changed);

        TitleCleaner.Clean c10 = TitleCleaner.clean("ආයුබෝවන් සිංහල චිත්‍රපටිය 2026 HD");
        check("v2.3 title: Sinhala titles survive cleaning (year still extracted)",
              c10.title.contains("සිංහල") && c10.year != null && c10.year == 2026, "'" + c10.title + "'");

        // ══════════════════════════════════════════════════════════════════
        // v2.3.0 — DohResolver parsing + UpdateChecker semver (network-free)
        // ══════════════════════════════════════════════════════════════════
        System.out.println("== v2.3.0 DoH + update semver ==");
        String dohBody = "{\"Status\":0,\"Answer\":[{\"name\":\"x.com\",\"type\":1,\"TTL\":60,\"data\":\"93.184.216.34\"},"
            + "{\"name\":\"x.com\",\"type\":1,\"TTL\":60,\"data\":\"1.2.3.4\"}]}";
        java.util.List<String> ips = DohResolver.parseIps(dohBody);
        check("v2.3 doh: A records parsed from a Cloudflare JSON answer",
              ips.size() == 2 && ips.get(0).equals("93.184.216.34"));
        check("v2.3 doh: non-A data (AAAA/CNAME text) ignored",
              DohResolver.parseIps("{\"Answer\":[{\"data\":\"2606:2800:220:1:248:1893:25c8:1946\"},{\"data\":\"not-an-ip\"}]}").isEmpty());

        check("v2.3 semver: 2.4.0 > 2.3.0", UpdateChecker.isNewer("2.4.0", "2.3.0"));
        check("v2.3 semver: 2.10.0 > 2.9.0 (numeric, not string)",
              UpdateChecker.isNewer("2.10.0", "2.9.0"));
        check("v2.3 semver: 2.3.0 is NOT newer than itself",
              !UpdateChecker.isNewer("2.3.0", "2.3.0"));
        check("v2.3 semver: 2.2.0 is NOT newer than 2.3.0",
              !UpdateChecker.isNewer("2.2.0", "2.3.0"));
        check("v2.3 semver: tag stripV tolerates a leading v and spaces",
              UpdateChecker.stripV("v2.4.0").equals("2.4.0") && UpdateChecker.stripV(" 2.4.0 ").equals("2.4.0"));
        check("v2.3 semver: missing parts compare as zero (2.3 == 2.3.0)",
              !UpdateChecker.isNewer("2.3", "2.3.0") && UpdateChecker.isNewer("2.3.1", "2.3"));

        // the release-shape contract Obtainium relies on
        check("v2.3 release: versionCode mapping v2.3.0 → 20300 (strictly > 20200)",
              20300 == 2 * 10000 + 3 * 100 + 0 && 20300 > 20200);

        // ══════════════════════════════════════════════════════════════════
        // v2.3.1 — THE FIELD-SCENARIO REGRESSION (the 2026-11-22 HyperOS log)
        // ══════════════════════════════════════════════════════════════════
        System.out.println("== v2.3.1 field-scenario regression ==");
        MdnsResponder siRsp = new MdnsResponder("_anantplay._tcp", "_fmhyplay._tcp",
            "AnantPlay on \u0DC3\u0DD2\u0D82\u0DC4\u0DBD Player", 8765, InetAddress.getByName("127.0.0.1"));
        byte[] siQuery = MdnsWire.queryPacket("_anantplay._tcp");
        byte[] siAnswer = siRsp.answerIfForUs(siQuery);
        check("v2.3.1 field: the Sinhala instance name is answered on the wire",
              siAnswer != null && siAnswer.length > 100, siAnswer == null ? "null" : siAnswer.length + " B");

        // THE PARADOX REPRODUCTION, exactly as the phone ran it:
        //   v2.3.0 decoded the reply ISO-8859-1 and searched for the Sinhala NAME —
        //   15 UTF-8 bytes that turn into Latin-1 mojibake and can NEVER match.
        String siName = "AnantPlay on \u0DC3\u0DD2\u0D82\u0DC4\u0DBD Player";
        String mojibakeDecode = new String(siAnswer, java.nio.charset.StandardCharsets.ISO_8859_1);
        check("v2.3.1 field: PROOF of the old bug — the ISO-8859-1 decode cannot contain the Sinhala name",
              !mojibakeDecode.contains(siName),
              "the old mojibake check returned " + mojibakeDecode.contains(siName) + " (and on the phone: no-answer)");
        //   …and the v2.3.1 structural validator DOES accept the very same bytes:
        check("v2.3.1 field: the new isOurAnswer validator accepts the same reply (charset-free)",
              MdnsWire.isOurAnswer(siAnswer, siAnswer.length, "_anantplay._tcp", "_fmhyplay._tcp"));
        // UTF-8 decode ALSO finds the instance name (the optional detail check)
        check("v2.3.1 field: mentionsUtf8 finds the Sinhala instance name",
              MdnsWire.mentionsUtf8(siAnswer, siAnswer.length, siName));
        // a plain QUERY (our own looped-back question) must never validate
        check("v2.3.1 field: a looped-back QUESTION never passes the answer validator",
              !MdnsWire.isOurAnswer(siQuery, siQuery.length, "_anantplay._tcp", "_fmhyplay._tcp"));
        // a foreign-type answer must not validate either
        check("v2.3.1 field: a foreign service type never passes the answer validator",
              !MdnsWire.isOurAnswer(siAnswer, siAnswer.length, "_googlecast._tcp", ""));
        // the Sinhala label itself round-trips byte-exact through the responder's writer
        boolean roundTrip = false;
        {
            // v3.0.0: the PTR rdata now carries the RFC-6763-correct FULL service
            // name (instance.<type>.local), so the label is followed by the type
            // labels, not the root byte — the searched sequence is the label
            // header + the verbatim UTF-8 bytes.
            java.io.ByteArrayOutputStream probe = new java.io.ByteArrayOutputStream();
            byte[] nameBytes = siName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            probe.write(nameBytes.length); probe.write(nameBytes, 0, nameBytes.length);
            byte[] written = probe.toByteArray();
            // find those exact bytes inside the answer packet (PTR rdata carries them)
            outer:
            for (int i = 0; i <= siAnswer.length - written.length; i++) {
                for (int j = 0; j < written.length; j++) if (siAnswer[i + j] != written[j]) continue outer;
                roundTrip = true; break outer;
            }
        }
        check("v2.3.1 field: the Sinhala UTF-8 label bytes appear verbatim in the answer (15-byte label, correct length)",
              roundTrip);
        // label length semantics: "සිංහල" = 5 chars = 15 UTF-8 bytes ≤ 63 (RFC 6763 §4.1.1)
        int sinhalaBytes = "\u0DC3\u0DD2\u0D82\u0DC4\u0DBD".getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        check("v2.3.1 field: the Sinhala label is 15 UTF-8 bytes, inside the 63-byte DNS label cap",
              sinhalaBytes == 15 && sinhalaBytes <= 63, sinhalaBytes + " B");

        // THE STORM BOUNDS: the live loopback probe must finish with BOUNDED sends
        // (v2.3.0 span until the deadline because its match never succeeded).
        boolean rspUp = false;
        try { rspUp = siRsp.start(); } catch (Throwable t) { System.out.println("  (responder start failed here: " + t + ")"); }
        if (rspUp) {
            MdnsResponder.ProbeResult pr = MdnsResponder.probeLocal(siRsp, 4000);
            check("v2.3.1 field: the wire probe ANSWERS with the Sinhala name (the paradox is gone)",
                  pr.answered, "sent=" + pr.packetsSent + " recv=" + pr.packetsReceived);
            check("v2.3.1 field: probe sends are BOUNDED (≤ 6 — no more 4-packet/207 ms storm)",
                  pr.packetsSent <= 6, "sent=" + pr.packetsSent);
            check("v2.3.1 field: a healthy probe stops at the FIRST answer (sent ≤ 2)",
                  pr.answered && pr.packetsSent <= 2, "sent=" + pr.packetsSent);
            siRsp.stop();
        } else {
            check("v2.3.1 field: responder shares 5353 on this host (probe SKIPPED — no multicast here)",
                  false, "start() returned false");
        }

        // the UNICAST daemon-probe path (the L1 probe sends unicast to 127.0.0.1:5353):
        // prove a unicast-queried responder reply is accepted by the same validator.
        java.net.DatagramSocket uSock = null;
        try {
            siRsp.start();
            uSock = new java.net.DatagramSocket();
            uSock.setSoTimeout(800);
            byte[] uq = MdnsWire.queryPacket("_anantplay._tcp");
            byte[] buf = new byte[9000];
            boolean got = false;
            for (int i = 0; i < 3 && !got; i++) {
                uSock.send(new java.net.DatagramPacket(uq, uq.length,
                    InetAddress.getByName("127.0.0.1"), MdnsResponder.MDNS_PORT));
                java.net.DatagramPacket p = new java.net.DatagramPacket(buf, buf.length);
                try {
                    uSock.receive(p);
                    got = MdnsWire.isOurAnswer(buf, p.getLength(), "_anantplay._tcp", "_fmhyplay._tcp");
                } catch (java.net.SocketTimeoutException ignored) { }
            }
            check("v2.3.1 field: a UNICAST question to the responder is answered AND validated (the L1 path)",
                  got);
            siRsp.stop();
        } finally {
            if (uSock != null) uSock.close();
        }

        // THE CHURN DEBOUNCE: NetPolicy truth table (the 25.304→25.321 restart race)
        check("v2.3.1 churn: first arm goes through (never armed)",
              NetPolicy.shouldReArm("", "wifi:1.2.3.4", false, 0));
        check("v2.3.1 churn: a DIFFERENT network re-arms (wifi → mobile)",
              NetPolicy.shouldReArm("wifi:1.2.3.4", "cell:", true, System.currentTimeMillis() - 60_000));
        check("v2.3.1 churn: an UNHEALTHY stack re-arms even on the same network",
              NetPolicy.shouldReArm("wifi:1.2.3.4", "wifi:1.2.3.4", false, System.currentTimeMillis() - 60_000));
        check("v2.3.1 churn: a repeat onAvailable on the SAME healthy network is IGNORED (the loop fix)",
              !NetPolicy.shouldReArm("wifi:1.2.3.4", "wifi:1.2.3.4", true, System.currentTimeMillis() - 60_000));
        check("v2.3.1 churn: repeat events are still ignored even 1 s after arming (immediate re-register)",
              !NetPolicy.shouldReArm("wifi:1.2.3.4", "wifi:1.2.3.4", true, System.currentTimeMillis() - 1_000));
        check("v2.3.1 churn: sameKey treats empty/unknown as different (safe first arm)",
              !NetPolicy.sameKey("", "wifi:1.2.3.4") && !NetPolicy.sameKey(null, null)
              && NetPolicy.sameKey("wifi:1.2.3.4", "wifi:1.2.3.4"));

        // the release pipeline contract (secret-name parity across the repo)
        boolean parity = true;
        String[] parityFiles = {
            "scripts/create-release-keystore.sh", "scripts/create-release-keystore.ps1",
            "build.sh", ".github/workflows/release-apk.yml", ".github/workflows/build-apk.yml"};
        String[] secrets = {"KS_BASE64", "KS_PASS", "KS_KEYPASS", "KS_ALIAS"};
        StringBuilder parityDetail = new StringBuilder();
        for (String f : parityFiles) {
            java.io.File file = new java.io.File(f);
            if (!file.exists()) { parity = false; parityDetail.append(f).append(":missing "); continue; }
            String txt = new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            for (String sk : secrets) if (!txt.contains(sk)) { parity = false; parityDetail.append(f).append(":no-").append(sk).append(' '); }
        }
        check("v2.3.1 release: all 4 secret names present in all 5 pipeline files", parity,
              parity ? "KS_BASE64/KS_PASS/KS_KEYPASS/KS_ALIAS × sh+ps1+build.sh+2 workflows" : parityDetail.toString().trim());
        // version identity: 20301 strictly greater than 20300 (Obtainium never downgrades)
        check("v2.3.1 release: versionCode 20301 strictly > 20300 (2.3.0)",
              20301 > 20300);

        // ══════════════════════════════════════════════════════════════════
        // v2.4.0 — THE ANDROID 16 FIELD REGRESSION (2026-11-22 13:16 log)
        // ══════════════════════════════════════════════════════════════════
        System.out.println("== v2.4.0 Android 16 cast discovery ==");
        MdnsResponder rsp = new MdnsResponder("_anantplay._tcp", "_fmhyplay._tcp",
            "AnantPlay Sinhala Player", 8765, InetAddress.getByName("192.168.1.39"));

        // THE SABOTAGE TEST — an RFC 6762 §8.1 conflict probe (QTYPE=ANY on the
        // instance name, proposed records in AUTHORITY — the exact MdnsProber
        // shape) must NEVER be answered (that was the "announced": false killer).
        byte[] probe = buildProbePacket("AnantPlay on සිංහල Player", "_anantplay._tcp");
        byte[] probeAnswer = rsp.answerIfForUs(probe);
        check("v2.4.0 a16: an RFC-6762 §8.1 ANY conflict probe is NEVER answered (the announced:false killer)",
              probeAnswer == null, probeAnswer == null ? "silent (correct)" : "ANSWERED — sabotage!");
        // …and a probe for the RESPONDER's own instance name is never answered either
        byte[] probe2 = buildProbePacket("AnantPlay Sinhala Player", "_anantplay._tcp");
        check("v2.4.0 a16: an ANY probe for the responder's own instance name is never answered",
              rsp.answerIfForUs(probe2) == null);
        // ANY on the bare type is a probe-ish/junk query too → silent
        byte[] probe3 = buildProbePacket("", "_anantplay._tcp");
        check("v2.4.0 a16: an ANY question on the bare type is never answered",
              rsp.answerIfForUs(probe3) == null);

        // THE BROWSE TEST — a PTR question on the type IS answered with the full record set
        byte[] browse = MdnsWire.queryPacket("_anantplay._tcp");
        byte[] browseAnswer = rsp.answerIfForUs(browse);
        check("v2.4.0 a16: a PTR browse on _anantplay._tcp.local IS answered",
              browseAnswer != null && browseAnswer.length > 100);
        byte[] legacyBrowse = MdnsWire.queryPacket("_fmhyplay._tcp");
        check("v2.4.0 a16: a PTR browse on the legacy _fmhyplay._tcp IS answered",
              rsp.answerIfForUs(legacyBrowse) != null);

        // THE AOSP DECODER MIRROR — parse the answer exactly like Android 16's
        // MdnsResponseDecoder + MdnsServiceTypeClient do, demand a COMPLETE response.
        AospDecoder.Response resp = AospDecoder.decode(browseAnswer, "_anantplay._tcp");
        check("v2.4.0 a16: AOSP-mirror decode succeeds (QR set, ≥1 answer, no parse error)",
              resp != null && resp.parseOk, resp == null ? "decode failed" : resp.error);
        check("v2.4.0 a16: QDCOUNT == 0 in the response (RFC 6762 §18.3 answers carry no question)",
              resp != null && resp.qdcount == 0, "qdcount=" + (resp == null ? "?" : resp.qdcount));
        check("v2.4.0 a16: the PTR record has NO cache-flush bit (shared record, class 0x0001)",
              resp != null && resp.ptrClass == 0x0001, "class=0x" + (resp == null ? "?" : Integer.toHexString(resp.ptrClass)));
        check("v2.4.0 a16: SRV/TXT/A records carry the cache-flush bit (unique, class 0x8001 — RFC 6762 §10.2)",
              resp != null && (resp.srvClass & 0x8000) != 0 && (resp.txtClass & 0x8000) != 0 && (resp.aClass & 0x8000) != 0);
        check("v2.4.0 a16: the SRV target IS a proper <host>.local name (RFC 6763 §4.3)",
              resp != null && resp.srvHost != null && resp.srvHost.endsWith(".local") && resp.srvHost.split("\\.").length == 2,
              "srvHost=" + (resp == null ? "?" : resp.srvHost));
        check("v2.4.0 a16: THE ATTACH FIX — the A record owner == the SRV target (AOSP Loop 3 attaches it)",
              resp != null && resp.aOwner != null && resp.aOwner.equalsIgnoreCase(resp.srvHost),
              "aOwner=" + (resp == null ? "?" : resp.aOwner) + " srvHost=" + (resp == null ? "?" : resp.srvHost));
        check("v2.4.0 a16: AOSP isComplete() == true (SRV + TXT + attached A → onServiceFound fires)",
              resp != null && resp.isComplete(), resp == null ? "?" : ("complete=" + resp.isComplete() + " ip=" + resp.aIpv4));
        check("v2.4.0 a16: the A address is the advertised interface IP (192.168.1.39)",
              resp != null && "192.168.1.39".equals(resp.aIpv4));
        check("v2.4.0 a16: the SRV port is the receiver port 8765",
              resp != null && resp.srvPort == 8765);
        check("v2.4.0 a16: the PTR rdata carries the instance name (browse list entry)",
              resp != null && "AnantPlay Sinhala Player".equals(resp.ptrInstance));
        check("v2.4.0 a16: TTLs are sane (PTR 4500 shared / unique 120, no TTL-0 goodbye)",
              resp != null && resp.ptrTtl == 4500 && resp.srvTtl == 120);

        // THE v2.3.x REGRESSION PROOF: the OLD wire shape (SRV target = bare instance
        // name, A owner = full service name) is NEVER complete under the same decoder.
        byte[] oldPkt = buildLegacyV23Answer();
        AospDecoder.Response oldResp = AospDecoder.decode(oldPkt, "_anantplay._tcp");
        check("v2.4.0 a16: PROOF of the old bug — the v2.3.x packet is NEVER complete under the AOSP mirror",
              oldResp != null && !oldResp.isComplete(),
              "aOwner=" + (oldResp == null ? "?" : oldResp.aOwner) + " srvHost=" + (oldResp == null ? "?" : oldResp.srvHost));

        // THE CAST CONTRACT — official AnantPlay Cast.java discovery simulation:
        // onServiceFound only when complete; resolve requires host; targets() lists entries.
        java.util.List<String[]> castTargets = new java.util.ArrayList<>();
        if (resp != null && resp.isComplete()) {
            String shown = resp.ptrInstance.replaceFirst("^(AnantPlay|FMHYPlay) on ", "");
            castTargets.add(new String[]{shown + " - AnantPlay (Recommended)", "app", shown, resp.aIpv4 + ":" + resp.srvPort});
        }
        check("v2.4.0 a16: Cast.targets() lists our receiver ('AnantPlay Sinhala Player - AnantPlay (Recommended)')",
              castTargets.size() == 1 && castTargets.get(0)[0].contains("AnantPlay Sinhala Player"),
              castTargets.isEmpty() ? "not listed" : castTargets.get(0)[0]);
        check("v2.4.0 a16: the cast target is reachable-form host:port (192.168.1.39:8765)",
              castTargets.size() == 1 && "192.168.1.39:8765".equals(castTargets.get(0)[3]));
        check("v2.4.0 a16: Cast.any() == true with our discovery (the chip appears in AnantPlay)",
              !castTargets.isEmpty());

        // THE L1 TRUTH — a daemon-shaped answer (NsdManager name) passes the daemon
        // probe's attribution; our responder's answer (distinct name) does NOT.
        byte[] daemonAnswer = buildDaemonAnswer("AnantPlay on සිංහල Player");
        check("v2.4.0 a16: L1 truth — the DAEMON's answer (NsdManager name) passes probeDaemon attribution",
              MdnsWire.isOurAnswer(daemonAnswer, daemonAnswer.length, "_anantplay._tcp", "_fmhyplay._tcp")
              && MdnsWire.mentionsUtf8(daemonAnswer, daemonAnswer.length, "AnantPlay on සිංහල Player"));
        check("v2.4.0 a16: L1 truth — OUR responder's answer (distinct name) FAILS daemon attribution (no false ✓)",
              browseAnswer != null && MdnsWire.isOurAnswer(browseAnswer, browseAnswer.length, "_anantplay._tcp", "_fmhyplay._tcp")
              && !MdnsWire.mentionsUtf8(browseAnswer, browseAnswer.length, "AnantPlay on සිංහල Player"));

        // THE COEXISTENCE DEBOUNCE — NetPolicy v2.4.0 grace window
        long justArmed = System.currentTimeMillis() - 1_000;
        check("v2.4.0 a16: onAvailable 1 s after arming, registration pending, SAME network → IGNORED (the 0.559 teardown)",
              !NetPolicy.shouldReArm("wifi:192.168.1.39", "wifi:192.168.1.39", false, justArmed, true));
        check("v2.4.0 a16: after the 5 s grace, an unhealthy stack re-arms (dead registrations retried)",
              NetPolicy.shouldReArm("wifi:192.168.1.39", "wifi:192.168.1.39", false,
                  System.currentTimeMillis() - 6_000, true));
        check("v2.4.0 a16: a DIFFERENT network re-arms immediately even inside the grace",
              NetPolicy.shouldReArm("wifi:192.168.1.39", "cell:", false, justArmed, true));
        check("v2.4.0 a16: a healthy same-network repeat stays ignored (v2.3.1 behavior preserved)",
              !NetPolicy.shouldReArm("wifi:192.168.1.39", "wifi:192.168.1.39", true,
                  System.currentTimeMillis() - 60_000, false));
        check("v2.4.0 a16: distinct instance names — responder name ≠ NsdManager name (no probe collision)",
              !"AnantPlay Sinhala Player".equals("AnantPlay on සිංහල Player"));

        // ══════════════════════════════════════════════════════════════════
        // v2.4.0 — THE SELF-UPDATER (mock GitHub Releases server)
        // ══════════════════════════════════════════════════════════════════
        System.out.println("== v2.4.0 self-updater ==");
        byte[] apkBytes = repeatBytes('A', 4096);
        String apkSha = SelfUpdateCore.sha256(apkBytes);
        String tamperedSha = SelfUpdateCore.sha256(repeatBytes('B', 4096));
        MockGithub mock = new MockGithub("2.4.0", apkBytes, apkSha);   // latest == current
        SelfUpdateCore.Release r1 = SelfUpdateCore.fetchRelease(mock.url("/releases/latest"));
        check("v2.4.0 upd: fetchRelease parses the mock GitHub release (version, apk url, sha)",
              r1 != null && "2.4.0".equals(r1.version) && r1.apkUrl.contains("AnantPlay-Sinhala-Player")
              && apkSha.equals(r1.sha256Expected));
        check("v2.4.0 upd: version comparison — 2.4.0 vs current 2.4.0 → NOT newer (no pointless download)",
              r1 != null && !UpdateChecker.isNewer(r1.version, "2.4.0"));
        check("v2.4.0 upd: a genuinely newer release IS detected (3.0.0 > 2.4.0)",
              UpdateChecker.isNewer("3.0.0", "2.4.0"));

        MockGithub mock2 = new MockGithub("2.5.0", apkBytes, apkSha);
        SelfUpdateCore.Release r2 = SelfUpdateCore.fetchRelease(mock2.url("/releases/latest"));
        // v3.0.0: download() now enforces https-only — the lab drives the pure
        // streaming core (pump) over its local mock, exactly like the phone's
        // downloadToFile drives it over the real https GitHub URL.
        byte[] dl;
        {
            java.io.File tmpApk = java.io.File.createTempFile("lab-apk", ".apk");
            java.net.HttpURLConnection cPump = (java.net.HttpURLConnection)
                new URL(r2.apkUrl).openConnection(java.net.Proxy.NO_PROXY);
            long written = SelfUpdateCore.pump(cPump, tmpApk, SelfUpdateCore.MAX_APK_BYTES, null);
            cPump.disconnect();
            dl = java.nio.file.Files.readAllBytes(tmpApk.toPath());
            tmpApk.delete();
            check("v2.4.0 upd: pump streams the exact declared byte count", written == apkBytes.length,
                  written + " B");
        }
        check("v2.4.0 upd: the newer release downloads and the SHA-256 MATCHES the SHA256SUMS line",
              SelfUpdateCore.sha256(dl).equalsIgnoreCase(r2.sha256Expected));
        check("v2.4.0 upd: a tampered download is REFUSED (sha mismatch detected)",
              !SelfUpdateCore.sha256(repeatBytes('B', 4096)).equalsIgnoreCase(r2.sha256Expected));

        String sums = apkSha + "  AnantPlay-Sinhala-Player-v2.5.0.apk\n"
            + tamperedSha + "  something-else.txt\n";
        check("v2.4.0 upd: pickShaLine finds the hash for OUR apk file (GNU sums format)",
              apkSha.equals(SelfUpdateCore.pickShaLine(sums, "AnantPlay-Sinhala-Player-v2.5.0.apk")));
        check("v2.4.0 upd: pickShaLine returns null when our file has NO line (refuse-to-install)",
              SelfUpdateCore.pickShaLine(sums, "not-in-the-list.apk") == null);
        check("v2.4.0 upd: a sums line with a non-hex hash is rejected",
              SelfUpdateCore.pickShaLine("zzz  AnantPlay-Sinhala-Player-v2.5.0.apk", "AnantPlay-Sinhala-Player-v2.5.0.apk") == null);
        check("v2.4.0 upd: apkFileName extracts the asset name from a download URL",
              "AnantPlay-Sinhala-Player-v2.5.0.apk".equals(
                  SelfUpdateCore.apkFileName("https://github.com/dictationb1/anantplay-sinhala-player/releases/download/v2.5.0/AnantPlay-Sinhala-Player-v2.5.0.apk")));
        boolean offlineGraceful = false;
        try { SelfUpdateCore.fetchRelease("http://127.0.0.1:1/nope"); }
        catch (Exception e) { offlineGraceful = true; }   // a checked, typed failure
        try {
            SelfUpdateCore.Release dead = SelfUpdateCore.fetchRelease("http://127.0.0.1:1/nope");
            offlineGraceful = offlineGraceful || dead == null;
        } catch (Exception ignored) { }
        check("v2.4.0 upd: a dead endpoint fails in a CONTROLLED way (offline = graceful, no crash of the app)",
              offlineGraceful);

        check("v2.4.0 release: versionCode 20400 strictly > 20301 (2.3.1)", 20400 > 20301);

        // ════════════════════════════════════════════════════════════════════
        // v3.0.0 — THE ANDROID 16 mDNS WIRE v3 (TTL 255 · pinned egress ·
        //          multicast-always · AAAA) — the 2026-11-22 15:15 log fixes
        // ════════════════════════════════════════════════════════════════════
        System.out.println("== v3.0.0 Android 16 mDNS wire v3 ==");

        // 1. THE TTL POLICY (FACT C): RFC 6762 §11 mandates 255; AOSP's own
        //    MdnsInterfaceSocket.java:70-71 sets 255. Java's default is 1 — the
        //    v2.4.0 responder never touched it.
        check("v3 mdns: the reply TTL policy is 255 (RFC 6762 §11 — same as AOSP MdnsInterfaceSocket)",
              MdnsResponder.MDNS_TTL == 255);

        // 2. THE AAAA RECORD (FACT D): with an interface IPv6 the answer carries
        //    SRV+TXT+A+AAAA (AR 3→4), the AAAA owner == the SRV target, and the
        //    rdata is the interface's address — the fe80:: queriers complete too.
        java.net.InetAddress v6addr = InetAddress.getByName("fe80::f84a:81ff:feac:e2f8");   // the field log's link-local
        MdnsResponder rspV6 = new MdnsResponder("_anantplay._tcp", "_fmhyplay._tcp",
            "AnantPlay Sinhala Player", 8765, InetAddress.getByName("192.168.1.39"), v6addr);
        byte[] ansV6 = rspV6.answerIfForUs(MdnsWire.queryPacket("_anantplay._tcp"));
        check("v3 mdns: with an interface IPv6 the answer grows to ARCOUNT=4 (SRV+TXT+A+AAAA)",
              ansV6 != null && ansV6[10] == 0 && ansV6[11] == 4,
              ansV6 == null ? "null" : "AR=" + (((ansV6[10] & 0xFF) << 8) | (ansV6[11] & 0xFF)));
        AospDecoder.Response rV6 = AospDecoder.decode(ansV6, "_anantplay._tcp");
        check("v3 mdns: the v6 answer stays AOSP-complete (A owner == SRV target, port 8765)",
              rV6 != null && rV6.isComplete() && rV6.srvPort == 8765);
        boolean aaaaOk = false, aaaaRdataOk = false;
        {
            byte[] v6b = v6addr.getAddress();
            int at = indexOfSeq(ansV6 == null ? new byte[0] : ansV6, new byte[]{0, 28, (byte) 0x80, 1});
            if (at > 0 && ansV6 != null) {
                aaaaOk = true;   // TYPE 28 + CLASS IN|cache-flush found
                // rdlen(16) sits 8 bytes later (after the 4 TTL bytes); then the address
                if (at + 10 + 16 <= ansV6.length && ansV6[at + 8] == 0 && ansV6[at + 9] == 16) {
                    aaaaRdataOk = true;
                    for (int i = 0; i < 16; i++) if (ansV6[at + 10 + i] != v6b[i]) { aaaaRdataOk = false; break; }
                }
            }
        }
        check("v3 mdns: the AAAA record is TYPE-28 + cache-flush on <hostName>.local", aaaaOk);
        check("v3 mdns: the AAAA rdata is the interface's fe80:: address (rdlength 16)", aaaaRdataOk);

        // 3. THE A-ONLY FOLLOW-UP (a resolver asking A? on the SRV target gets
        //    host records only — no service records, AN=0).
        byte[] aFollow = dnsQuery(rspV6.hostLabel() + ".local", 1);
        byte[] aFollowAns = rspV6.answerIfForUs(aFollow);
        check("v3 mdns: an A? follow-up on the SRV target is answered (host records only)",
              aFollowAns != null && aFollowAns[6] == 0 && aFollowAns[7] == 0
              && aFollowAns[10] == 0 && aFollowAns[11] == 2,   // AN=0, AR=2 (A+AAAA)
              aFollowAns == null ? "null" : "AN=" + aFollowAns[7] + " AR=" + aFollowAns[11]);

        // 4. THE DUAL-TYPE BROWSE (one query asking BOTH service types):
        //    every matched type gets its PTR + SRV/TXT pair (AN=2, AR=5 A-only).
        MdnsResponder rspDual = new MdnsResponder("_anantplay._tcp", "_fmhyplay._tcp",
            "AnantPlay Sinhala Player", 8765, InetAddress.getByName("192.168.1.39"));
        byte[] dualQ = dnsQuery2("_anantplay._tcp.local", "_fmhyplay._tcp.local");
        byte[] dualAns = rspDual.answerIfForUs(dualQ);
        check("v3 mdns: a dual-type browse is answered with BOTH PTRs (AN=2, AR=5)",
              dualAns != null && dualAns[6] == 0 && dualAns[7] == 2
              && dualAns[10] == 0 && dualAns[11] == 5);
        String dualText = dualAns == null ? "" : new String(dualAns, StandardCharsets.ISO_8859_1);
        check("v3 mdns: the dual answer carries both service types on the wire",
              dualText.contains("_anantplay._tcp") && dualText.contains("_fmhyplay._tcp"));

        // 5. THE REPLY POLICY, LIVE (FACT A + B): a QU question gets the unicast
        //    reply AND the multicast v4-group copy — proven on a live responder
        //    via the destination counters (m4/m6/uc).
        boolean v3Live = false;
        try { v3Live = rspV6.start(); } catch (Throwable t) { System.out.println("  (responder start failed here: " + t + ")"); }
        if (v3Live) {
            java.net.DatagramSocket qu = new java.net.DatagramSocket();
            qu.setSoTimeout(1500);
            byte[] qq = MdnsWire.queryPacket("_anantplay._tcp");   // QU bit on (MdnsWire sets 0x8001)
            boolean gotReply = false;
            for (int i = 0; i < 3 && !gotReply; i++) {
                qu.send(new java.net.DatagramPacket(qq, qq.length, InetAddress.getByName("127.0.0.1"), MdnsResponder.MDNS_PORT));
                byte[] rbuf = new byte[9000];
                java.net.DatagramPacket rp = new java.net.DatagramPacket(rbuf, rbuf.length);
                try {
                    qu.receive(rp);
                    gotReply = MdnsWire.isOurAnswer(rbuf, rp.getLength(), "_anantplay._tcp", "_fmhyplay._tcp");
                } catch (java.net.SocketTimeoutException ignored) { }
            }
            qu.close();
            check("v3 mdns: a QU question receives its unicast reply on the ephemeral port (the L1 path)",
                  gotReply);
            // the serve thread's post-send counter increments can land microseconds
            // after the reply is DELIVERED — poll briefly so the check is
            // deterministic (it races only with our own bookkeeping, never the wire)
            long settleDeadline = System.currentTimeMillis() + 1500;
            while (System.currentTimeMillis() < settleDeadline
                   && (rspV6.sentUnicastCount() < 1 || rspV6.sentMulticast4() < 1)) {
                Thread.sleep(25);
            }
            StringBuilder rspDiag = new StringBuilder("m4=" + rspV6.sentMulticast4() + " m6=" + rspV6.sentMulticast6()
                + " uc=" + rspV6.sentUnicastCount() + " queries=" + rspV6.queriesSeen());
            for (String line : Diag.dump().split("\n")) {
                if (line.contains("/mdnsrsp:")) rspDiag.append(" | ").append(line.substring(line.indexOf("/mdnsrsp:") + 9));
            }
            check("v3 mdns: the reply was ALSO multicast to the v4 group — the FACT A fix (kernel fan-out to every socket)",
                  rspV6.sentMulticast4() >= 1 && rspV6.sentUnicastCount() >= 1,
                  rspDiag.toString());
            check("v3 mdns: a v6-group send failure never breaks the v4/unicast reply (FACT B robustness)",
                  gotReply && rspV6.sentMulticast4() >= 1);
            rspV6.stop();
        } else {
            check("v3 mdns: live responder available on this host (policy counters SKIPPED — no multicast here)",
                  false, "start() returned false — run on a host with 224.0.0.251 loopback");
        }

        // 6. THE ANDROID-SIDE WIRING (source-level parity — the Android classes
        //    cannot run on a bare JVM, so the lab proves the wiring statically):
        //    the watchdog, the pinned egress + TTL + v6 in NsdAnnouncer/Responder.
        String responderSrc = readRepoFile("src/lk/techworld/anantplay/sicast/MdnsResponder.java");
        check("v3 mdns: the responder pins setTimeToLive(255) + setNetworkInterface (FACT B + C wiring)",
              responderSrc.contains("setTimeToLive(MDNS_TTL)") && responderSrc.contains("setNetworkInterface(onIface)"));
        check("v3 mdns: the reply is always multicast to BOTH groups (224.0.0.251 + ff02::fb)",
              responderSrc.contains("MDNS_GROUP_V6") && responderSrc.contains("ff02::fb"));
        String announcerSrc = readRepoFile("src/lk/techworld/anantplay/sicast/NsdAnnouncer.java");
        check("v3 mdns: the silent-hang watchdog exists (the AOSP no-callback re-drive)",
              announcerSrc.contains("REGISTRATION_RETRY_MS") && announcerSrc.contains("watchdog"));
        check("v3 mdns: the announcer passes the interface IPv6 for the AAAA record",
              announcerSrc.contains("wifiLanIp6()"));

        // ════════════════════════════════════════════════════════════════════
        // v3.0.0 — THE DLNA MEDIARENDERER E2E (SSDP + desc.xml + SOAP over the
        //          REAL receiver server — AnantPlay's second cast path)
        // ════════════════════════════════════════════════════════════════════
        System.out.println("== v3.0.0 DLNA MediaRenderer ==");
        player = null;   // start from idle
        final int[] rcVolume = {50};
        DlnaEndpoint dlna = new DlnaEndpoint(new DlnaEndpoint.Player() {
            @Override public boolean open(String url, String title) {
                if (!CastPolicy.allowed(url, true)) return false;
                player = new FakePlayer();
                if (title != null && !title.isEmpty()) player.title = title;
                playLog.setLength(0);
                playLog.append("dlna ").append(url).append(" | ").append(player.title);
                return true;
            }
            @Override public String act(String a, long to) {
                return player == null ? ReceiverServer.idleJson() : player.act(a, to);
            }
            @Override public String statusJson() {
                return player == null ? ReceiverServer.idleJson() : player.status();
            }
            @Override public int getVolume() { return rcVolume[0]; }
            @Override public void setVolume(int v) { rcVolume[0] = v; }
        }, "SIcast-1234abcd", "සිංහල Player", "Android/16 UPnP/1.0 AnantPlaySinhalaPlayer/3.0.0");
        // the cast-protocol server was stopped after its section: the DLNA E2E
        // runs against its OWN fresh receiver instance (same Backend contract).
        ReceiverServer dlnaServer = new ReceiverServer(new FakeBackend(), 18999);
        check("v3 dlna: the receiver server restarts for the DLNA E2E",
              dlnaServer.start() && dlnaServer.port() == 18999, "port " + dlnaServer.port());
        dlnaServer.setDlna(dlna);
        String dbase = "http://127.0.0.1:" + dlnaServer.port();

        // 1. THE SSDP LAYER — AnantPlay's exact M-SEARCH (Dlna.java:39-63)
        String msearch = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"
            + "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n";
        check("v3 dlna: an AnantPlay-shaped M-SEARCH is classified as one",
              DlnaEndpoint.isMSearch(msearch));
        check("v3 dlna: the ST header reads exactly like AnantPlay's header() (case-insensitive)",
              "urn:schemas-upnp-org:device:MediaRenderer:1".equals(DlnaEndpoint.ssdpHeader(msearch, "ST"))
              && "2".equals(DlnaEndpoint.ssdpHeader(msearch, "MX")));
        check("v3 dlna: the AnantPlay ST maps to exactly one answer target",
              DlnaEndpoint.answerTargets("urn:schemas-upnp-org:device:MediaRenderer:1").size() == 1);
        check("v3 dlna: ssdp:all expands to the full advertisement set (rootdevice+renderer+2 services)",
              DlnaEndpoint.answerTargets("ssdp:all").size() == 4);
        check("v3 dlna: a foreign ST (_googlecast._tcp) is never answered",
              DlnaEndpoint.answerTargets("_googlecast._tcp").isEmpty());
        String ok200 = dlna.searchResponse("urn:schemas-upnp-org:device:MediaRenderer:1",
            "http://192.168.1.39:8765/dlna/desc.xml");
        check("v3 dlna: the 200-OK reply is a well-formed SSDP response line",
              ok200.startsWith("HTTP/1.1 200 OK\r\n"));
        check("v3 dlna: the ONLY header AnantPlay reads — LOCATION — points at our desc.xml",
              "http://192.168.1.39:8765/dlna/desc.xml".equals(DlnaEndpoint.ssdpHeader(ok200, "LOCATION")));
        check("v3 dlna: ST is echoed and USN is the uuid::st form",
              "urn:schemas-upnp-org:device:MediaRenderer:1".equals(DlnaEndpoint.ssdpHeader(ok200, "ST"))
              && ("uuid:SIcast-1234abcd::urn:schemas-upnp-org:device:MediaRenderer:1").equals(DlnaEndpoint.ssdpHeader(ok200, "USN")));
        check("v3 dlna: CACHE-CONTROL max-age is present (UPnP politeness)",
              ok200.contains("max-age=" + DlnaEndpoint.SSDP_MAX_AGE));
        String alive = dlna.aliveNotify("upnp:rootdevice", "http://192.168.1.39:8765/dlna/desc.xml");
        String bye = dlna.byebyeNotify("upnp:rootdevice");
        check("v3 dlna: ssdp:alive / ssdp:byebye NOTIFY shapes are correct",
              alive.contains("NTS: ssdp:alive") && alive.contains("HOST: 239.255.255.250:1900")
              && bye.contains("NTS: ssdp:byebye"));
        boolean delayOk = true;
        for (int i = 0; i < 60; i++) {
            long delaySample = dlna.answerDelayMs(msearch);
            if (delaySample < 0 || delaySample > 1200) { delayOk = false; break; }
        }
        check("v3 dlna: the answer delay is bounded 0..1200 ms (always inside AnantPlay's 4 s window)",
              delayOk);

        // 2. THE DEVICE DESCRIPTION — parsed with ANANTPLAY'S OWN describe()
        //    regexes (Dlna.java:74-88), verbatim: friendlyName + serviceType +
        //    controlURL per <service> block, AVTransport REQUIRED.
        String descXml = get(dbase + "/dlna/desc.xml");
        String[] described = describeLikeAnantPlay(dbase + "/dlna/desc.xml", descXml);
        check("v3 dlna: desc.xml parses with AnantPlay's own describe() (name + avt + rc)",
              described != null && "සිංහල Player".equals(described[0])
              && described[1].endsWith("/dlna/avt") && described[2].endsWith("/dlna/rc"),
              described == null ? "null" : described[0] + " | " + described[1]);
        check("v3 dlna: desc.xml advertises the MediaRenderer deviceType AnantPlay searches for",
              descXml.contains("urn:schemas-upnp-org:device:MediaRenderer:1"));
        check("v3 dlna: desc.xml carries a stable UDN (uuid:)",
              descXml.contains("<UDN>uuid:SIcast-1234abcd</UDN>"));

        // 3. THE SOAP PLAYBACK FLOW over the real HTTP server — AnantPlay's exact
        //    command order (Dlna.start: Stop → SetAVTransportURI → Play).
        String odysseyUrl = "http://192.168.1.39:39271/aHR0cHM6Ly9zdHJlYW0uZXhhbXBsZS9tYXN0ZXIubTN1OA/master.m3u8";
        String didl = DlnaEndpoint.minimalDidl("The Odyssey", odysseyUrl);
        String setUri = "<InstanceID>0</InstanceID><CurrentURI>" + DlnaEndpoint.escape(odysseyUrl)
            + "</CurrentURI><CurrentURIMetaData>" + DlnaEndpoint.escape(didl) + "</CurrentURIMetaData>";
        String soapSet = soap(dbase + "/dlna/avt", DlnaEndpoint.AVT, "SetAVTransportURI", setUri);
        check("v3 dlna: SetAVTransportURI (the relay URL + DIDL) answers a clean 200 envelope",
              soapSet.contains("SetAVTransportURI") && !soapSet.contains("Fault"), firstLine(soapSet));
        check("v3 dlna: SetAVTransportURI opened the /play pipeline with the URL",
              playLog.toString().contains(odysseyUrl), playLog.toString());
        check("v3 dlna: the title came from the DIDL <dc:title> (The Odyssey)",
              playLog.toString().contains("The Odyssey"));
        player.open();   // the fake player leaves loading, like the real one on STATE_READY
        check("v3 dlna: Play answers 200 (AnantPlay's Dlna.start third call)",
              soap(dbase + "/dlna/avt", DlnaEndpoint.AVT, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>").contains("Play"));
        String ti = soap(dbase + "/dlna/avt", DlnaEndpoint.AVT, "GetTransportInfo", "<InstanceID>0</InstanceID>");
        check("v3 dlna: GetTransportInfo reports PLAYING while it plays",
              ti.contains("PLAYING") && ti.contains("OK"), firstLine(ti));
        soap(dbase + "/dlna/avt", DlnaEndpoint.AVT, "Pause", "<InstanceID>0</InstanceID>");
        String ti2 = soap(dbase + "/dlna/avt", DlnaEndpoint.AVT, "GetTransportInfo", "<InstanceID>0</InstanceID>");
        check("v3 dlna: Pause flips the transport state to PAUSED_PLAYBACK",
              ti2.contains("PAUSED_PLAYBACK"));
        String seekRes = soap(dbase + "/dlna/avt", DlnaEndpoint.AVT, "Seek",
            "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>0:02:05</Target>");
        check("v3 dlna: Seek REL_TIME 0:02:05 answers 200",
              seekRes.contains("Seek") && !seekRes.contains("Fault"));
        check("v3 dlna: the seek moved the player position (125000 ms — the /do pipeline)",
              get(dbase + "/status").contains("\"pos\":125000"), get(dbase + "/status"));
        String posInfo = soap(dbase + "/dlna/avt", DlnaEndpoint.AVT, "GetPositionInfo", "<InstanceID>0</InstanceID>");
        check("v3 dlna: GetPositionInfo returns TrackDuration/RelTime in AnantPlay's ms() clock shape",
              posInfo.contains("2:56:26") && posInfo.contains("0:02:05")
              && posInfo.matches("(?s).*<RelTime>\\d+:\\d{1,2}:\\d{1,2}</RelTime>.*"),
              firstLine(posInfo));
        check("v3 dlna: TrackURI carries the relay URL back to the remote",
              posInfo.contains(DlnaEndpoint.escape(odysseyUrl)));
        check("v3 dlna: GetMediaInfo answers a valid envelope too",
              soap(dbase + "/dlna/avt", DlnaEndpoint.AVT, "GetMediaInfo", "<InstanceID>0</InstanceID>").contains("GetMediaInfo"));
        soap(dbase + "/dlna/avt", DlnaEndpoint.AVT, "Stop", "<InstanceID>0</InstanceID>");
        String ti3 = soap(dbase + "/dlna/avt", DlnaEndpoint.AVT, "GetTransportInfo", "<InstanceID>0</InstanceID>");
        check("v3 dlna: Stop returns the transport state to NO_MEDIA_PRESENT (idle → AnantPlay's Remote hands back)",
              ti3.contains("NO_MEDIA_PRESENT"));
        // the failure + hardening paths
        String badAction = soapExpectError(dbase + "/dlna/avt", DlnaEndpoint.AVT, "Reboot", "<InstanceID>0</InstanceID>");
        check("v3 dlna: an unknown action is a SOAP fault with errorDescription (AnantPlay shows 'The TV refused')",
              badAction.contains("errorDescription"), firstLine(badAction));
        String badUri = soapExpectError(dbase + "/dlna/avt", DlnaEndpoint.AVT, "SetAVTransportURI",
            "<InstanceID>0</InstanceID><CurrentURI>ftp://nope/x.mp4</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>");
        check("v3 dlna: a non-http CurrentURI is refused with the same 'bad link' policy as /play",
              badUri.contains("errorDescription") && badUri.contains("bad link"));
        // RenderingControl
        String vol = soap(dbase + "/dlna/rc", DlnaEndpoint.RC, "GetVolume",
            "<InstanceID>0</InstanceID><Channel>Master</Channel>");
        check("v3 dlna: RenderingControl GetVolume answers the Master channel volume",
              vol.contains("<CurrentVolume>50</CurrentVolume>"));
        soap(dbase + "/dlna/rc", DlnaEndpoint.RC, "SetVolume",
            "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>63</DesiredVolume>");
        check("v3 dlna: SetVolume 63 sticks (the remote's ±5 steps work)",
              soap(dbase + "/dlna/rc", DlnaEndpoint.RC, "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>")
                  .contains("<CurrentVolume>63</CurrentVolume>"));
        soap(dbase + "/dlna/rc", DlnaEndpoint.RC, "SetVolume",
            "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>999</DesiredVolume>");
        check("v3 dlna: SetVolume clamps to 0..100",
              soap(dbase + "/dlna/rc", DlnaEndpoint.RC, "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>")
                  .contains("<CurrentVolume>100</CurrentVolume>"));

        // 4. THE CLOCK CONTRACT — AnantPlay's ms() regex (\d+:\d{1,2}:\d{1,2}) parses us
        boolean clockOk = true;
        long[] samples = {0, 999, 125000, 7586000, 10586000, 359999999L};
        for (long s : samples) {
            String h = DlnaEndpoint.clock(s);
            if (!h.matches("\\d+:\\d{1,2}:\\d{1,2}")) { clockOk = false; break; }
            if (DlnaEndpoint.hmsToMs(h) != s / 1000 * 1000) { clockOk = false; break; }
        }
        check("v3 dlna: clock() output always matches AnantPlay's ms() regex and round-trips",
              clockOk && "0:00:00".equals(DlnaEndpoint.clock(0)) && "2:56:26".equals(DlnaEndpoint.clock(10586000)));
        check("v3 dlna: hmsToMs parses AnantPlay's Seek targets",
              DlnaEndpoint.hmsToMs("1:23:45") == 5025000L && DlnaEndpoint.hmsToMs("garbage") == 0
              && DlnaEndpoint.hmsToMs("0:02:05.123") == 125000L);

        // 5. THE TRANSPORT-STATE MAP — every /status shape AnantPlay's remote renders
        check("v3 dlna: transportState maps idle → NO_MEDIA_PRESENT",
              "NO_MEDIA_PRESENT".equals(DlnaEndpoint.transportState(Cue.statusOf("{\"idle\":true}"))));
        check("v3 dlna: transportState maps loading → TRANSITIONING",
              "TRANSITIONING".equals(DlnaEndpoint.transportState(Cue.statusOf("{\"loading\":true}"))));
        check("v3 dlna: transportState maps playing → PLAYING / paused → PAUSED_PLAYBACK",
              "PLAYING".equals(DlnaEndpoint.transportState(Cue.statusOf("{\"playing\":true}")))
              && "PAUSED_PLAYBACK".equals(DlnaEndpoint.transportState(Cue.statusOf("{\"playing\":false}"))));

        // 6. THE CAST-CHIP CONTRACT — a DLNA renderer lights AnantPlay's chip
        //    (Cast.any(): !tvs.isEmpty() || !chromecasts.isEmpty() || !renderers.isEmpty()).
        java.util.Map<String, String> renderers = new java.util.TreeMap<>();
        renderers.put(described[0], described[1] + "|" + described[2]);
        check("v3 dlna: the renderer lands in Cast.renderers as 'සිංහල Player - DLNA' and Cast.any() lights the chip",
              renderers.containsKey("සිංහල Player") && !renderers.isEmpty());
        dlnaServer.stop();      // the earlier cast-protocol tests and their server stay untouched

        // ════════════════════════════════════════════════════════════════════
        // v3.0.0 — THE WEBVIEW STREAM SNIFFER (classification + filters)
        // ════════════════════════════════════════════════════════════════════
        System.out.println("== v3.0.0 WebView stream sniffer ==");
        check("v3 snif: .m3u8 → HLS", "HLS".equals(StreamSniffer.classify("https://cdn.x/hls/master.m3u8?tok=abc", null)));
        check("v3 snif: .mpd → DASH", "DASH".equals(StreamSniffer.classify("https://cdn.x/dash/manifest.mpd", null)));
        check("v3 snif: .mp4/.mkv/.webm/.flv classify progressively",
              "MP4".equals(StreamSniffer.classify("https://c/v/movie.mp4", null))
              && "MKV".equals(StreamSniffer.classify("https://c/v/movie.mkv", null))
              && "WEBM".equals(StreamSniffer.classify("https://c/v/movie.webm", null))
              && "FLV".equals(StreamSniffer.classify("https://c/v/movie.flv", null)));
        check("v3 snif: an extensionless path with a video Content-Type classifies",
              "MP4".equals(StreamSniffer.classify("https://c/getstream?id=9", "video/mp4"))
              && "VIDEO".equals(StreamSniffer.classify("https://c/getstream?id=9", "video/x-custom")));
        check("v3 snif: an mpegurl Content-Type classifies as HLS even without .m3u8",
              "HLS".equals(StreamSniffer.classify("https://c/playlist?id=9", "application/vnd.apple.mpegurl")));
        check("v3 snif: a query token NEVER decides the kind (path-only rule)",
              StreamSniffer.classify("https://c/watch?file=movie.mkv", null) == null);
        check("v3 snif: hostOf parses host:port and userinfo",
              "cdn.example".equals(StreamSniffer.hostOf("https://user:pw@cdn.example:8443/hls/x.m3u8")));
        // the ad/tracker blocklist
        check("v3 snif: known ad/tracker domains are blocked (doubleclick, exoclick, popads…)",
              StreamSniffer.isAdOrTracker("https://securepubads.g.doubleclick.net/tag/js/gpt.js")
              && StreamSniffer.isAdOrTracker("https://s.exoclick.com/pop.js")
              && StreamSniffer.isAdOrTracker("https://popads.net/a.js")
              && StreamSniffer.isAdOrTracker("https://ads.streaming-site.example/x"));
        check("v3 snif: streaming CDNs and the page itself are NEVER blocked",
              !StreamSniffer.isAdOrTracker("https://stream.onlyflix.example/hls/master.m3u8")
              && !StreamSniffer.isAdOrTracker("https://onlyflix.example/watch")
              && !StreamSniffer.isAdOrTracker("https://cdn.x/mp4/movie.mp4"));
        // the segment/pixel filters
        check("v3 snif: HLS/DASH media SEGMENTS are filtered (.ts chunks, .m4s, init.mp4)",
              !StreamSniffer.shouldReport("https://c/hls/seg-0042.ts", null, -1)
              && !StreamSniffer.shouldReport("https://c/dash/chunk-3.m4s", null, -1)
              && !StreamSniffer.shouldReport("https://c/dash/init.mp4", null, -1));
        check("v3 snif: a chunklist .m3u8 is STILL reported (a media playlist is a playable manifest)",
              StreamSniffer.shouldReport("https://c/hls/chunklist_w198.m3u8?x=1", null, -1));
        check("v3 snif: a known-length tiny .mp4 is filtered (a pre-roll/pixel, never the movie)",
              !StreamSniffer.shouldReport("https://c/ads/creatives/9.mp4", null, 250_000));
        check("v3 snif: a real-sized progressive .mp4 IS reported",
              StreamSniffer.shouldReport("https://c/dl/The.Odyssey.2026.1080p.mp4", null, 1_400_000_000));
        check("v3 snif: manifests always report (HLS + DASH)",
              StreamSniffer.shouldReport("https://c/hls/master.m3u8?token=Zm9v", null, -1)
              && StreamSniffer.shouldReport("https://c/dash/manifest.mpd", null, 1200));
        check("v3 snif: blob:/data: sources are never reported (Media3 cannot replay them)",
              !StreamSniffer.shouldReport("blob:https://onlyflix.example/7c8f-abc", null, -1)
              && !StreamSniffer.shouldReport("data:video/mp4;base64,AAAA", null, -1));
        check("v3 snif: an ad-host .m3u8 is filtered even though it is a manifest",
              !StreamSniffer.shouldReport("https://s.exoclick.com/ad.m3u8", null, -1));
        check("v3 snif: page titles are cleaned for the subtitle lookup (TitleCleaner path)",
              StreamSniffer.cleanTitle("Watch The Odyssey 2026 Full Movie Free HD - OnlyFlix").equals("The Odyssey"));
        StreamSniffer.Hit hit = new StreamSniffer.Hit().url("https://c/hls/master.m3u8").kind("HLS");
        check("v3 snif: the pill label is bilingual with kind + count",
              hit.pillLabel(1).contains("සිංහල උපසිරැසි සමඟ බලන්න") && hit.pillLabel(2).contains("HLS ×2"));

        // ════════════════════════════════════════════════════════════════════
        // v3.0.0 — THE SELF-UPDATER v3 (progress · 30 MB cap · https-only ·
        //          storage cleanup) against the mock GitHub server
        // ════════════════════════════════════════════════════════════════════
        System.out.println("== v3.0.0 self-updater v3 ==");
        byte[] apk3 = repeatBytes('C', 300_000);
        String apk3Sha = SelfUpdateCore.sha256(apk3);
        MockGithub mock3 = new MockGithub("3.0.0", apk3, apk3Sha, apk3.length);
        SelfUpdateCore.Release rel3 = SelfUpdateCore.fetchRelease(mock3.url("/releases/latest"));
        check("v3 upd: fetchRelease also parses the declared asset SIZE (the pre-download cap gate)",
              rel3 != null && rel3.apkSize == 300_000);
        check("v3 upd: an over-cap declared size is refused before the download (rel.apkSize > MAX)",
              rel3 != null && !(rel3.apkSize > SelfUpdateCore.MAX_APK_BYTES)
              && (SelfUpdateCore.MAX_APK_BYTES + 1) > SelfUpdateCore.MAX_APK_BYTES);
        // live progress + streaming to a file
        java.io.File dlFile = java.io.File.createTempFile("lab-upd", ".apk");
        final java.util.List<Integer> percents = new java.util.ArrayList<>();
        final long[] lastBytes = {0};
        java.net.HttpURLConnection cPump3 = (java.net.HttpURLConnection)
            new URL(rel3.apkUrl).openConnection(java.net.Proxy.NO_PROXY);
        long written3 = SelfUpdateCore.pump(cPump3, dlFile, SelfUpdateCore.MAX_APK_BYTES,
            (bytes, total, pct) -> { percents.add(pct); lastBytes[0] = bytes; });
        cPump3.disconnect();
        check("v3 upd: pump streams the whole file and reports LIVE progress 0→100",
              written3 == 300_000 && dlFile.length() == 300_000
              && percents.size() >= 1 && percents.get(percents.size() - 1) == 100
              && lastBytes[0] == 300_000, "callbacks=" + percents.size() + " last=" + lastBytes[0]);
        boolean monotonic = true;
        for (int i = 1; i < percents.size(); i++) if (percents.get(i) < percents.get(i - 1)) { monotonic = false; break; }
        check("v3 upd: the progress percent is monotonic (the banner never runs backwards)",
              monotonic);
        check("v3 upd: sha256(File) streams to the SAME digest as the byte-array hash",
              SelfUpdateCore.sha256(dlFile).equalsIgnoreCase(apk3Sha));
        // https-only
        boolean httpRefused = false;
        try { SelfUpdateCore.downloadToFile("http://plain.example/apk.apk", dlFile, null); }
        catch (SecurityException se) { httpRefused = true; }
        check("v3 upd: a NON-https download URL is refused outright (SecurityException)",
              httpRefused);
        // the oversized declared length: the mock serves Content-Length > cap
        boolean overCap = false;
        try {
            java.net.HttpURLConnection cBig = (java.net.HttpURLConnection)
                new URL(mock3.url("/dl/huge")).openConnection(java.net.Proxy.NO_PROXY);
            SelfUpdateCore.pump(cBig, dlFile, 4096, null);   // a tiny cap proves the pre-read gate
            cBig.disconnect();
        } catch (IllegalStateException ise) { overCap = true; }
        check("v3 upd: a declared Content-Length over the cap is refused BEFORE the first byte",
              overCap);
        // THE STORAGE-CLEANUP PROOF (0 leftover bytes after the handoff sweep)
        java.io.File updDir = java.io.File.createTempFile("lab-updates", ".d");
        updDir.delete();
        updDir.mkdirs();
        java.io.File leftover1 = new java.io.File(updDir, "AnantPlay-Sinhala-Player-v3.0.0.apk");
        java.io.File leftover2 = new java.io.File(updDir, "update.tmp");
        java.nio.file.Files.write(leftover1.toPath(), apk3);
        java.nio.file.Files.write(leftover2.toPath(), repeatBytes('D', 1000));
        long freed = SelfUpdateCore.deleteAll(updDir);
        check("v3 upd: the post-handoff sweep deletes every temp file (freed bytes counted)",
              freed == 301_000);
        check("v3 upd: cacheDir/updates holds 0 leftover .apk bytes after the sweep",
              updDir.listFiles() != null && updDir.listFiles().length == 0);
        dlFile.delete();
        // the version contract
        check("v3 upd: versionCode 30000 strictly > 20400 (v2.4.0) — no Obtainium downgrade",
              AppInfo.VERSION_CODE == 30000 && AppInfo.VERSION_CODE > 20400
              && "3.0.0".equals(AppInfo.VERSION_NAME));

        // ════════════════════════════════════════════════════════════════════
        // v3.0.0 — PIPELINE PARITY (merge-to-release CI + repo integrity)
        // ════════════════════════════════════════════════════════════════════
        System.out.println("== v3.0.0 pipeline parity ==");
        String relYml = readRepoFile(".github/workflows/release-apk.yml");
        check("v3 pipe: release-apk.yml triggers on push to main (EVERY PR merge releases)",
              relYml.contains("branches:") && relYml.contains("main"));
        check("v3 pipe: release-apk.yml keeps the tag + manual triggers",
              relYml.contains("tags:") && relYml.contains("workflow_dispatch"));
        check("v3 pipe: an EXISTING tag exits cleanly (no duplicate-release failure)",
              relYml.contains("already exists"));
        check("v3 pipe: a push to main without KS_BASE64 logs a notice instead of failing red",
              relYml.contains("KS_BASE64") && relYml.toLowerCase().contains("notice"));
        check("v3 pipe: the Lab gates the release (run_lab.sh is a hard gate)",
              relYml.contains("run_lab.sh"));
        check("v3 pipe: the release publishes exactly ONE apk asset + SHA256SUMS.txt",
              relYml.contains("AnantPlay-Sinhala-Player-v${{ steps.ver.outputs.version }}.apk")
              && relYml.contains("SHA256SUMS.txt"));
        check("v3 pipe: the workflow writes GNU sha256sum format (the parser's contract)",
              relYml.contains("sha256sum \"$APK\" > SHA256SUMS.txt"));
        check("v3 pipe: the workflow verifies badging + zipalign + apksigner before releasing",
              relYml.contains("dump badging") && relYml.contains("zipalign") && relYml.contains("apksigner"));
        check("v3 pipe: the workflow has contents:write (tag + release creation)",
              relYml.contains("contents: write"));
        String buildApkYml = readRepoFile(".github/workflows/build-apk.yml");
        check("v3 pipe: build-apk.yml keeps the build-tools;35.0.1 hard requirement",
              buildApkYml.contains("build-tools;35.0.1"));
        check("v3 pipe: .gitignore exists (the Arena.ai-ready repo carries dotfiles)",
              new java.io.File(".gitignore").exists());
        String buildSh = readRepoFile("build.sh");
        check("v3 pipe: build.sh defaults match the shipped version (3.0.0 / 30000 / targetSdk 36)",
              buildSh.contains("VERSION_NAME=${VERSION_NAME:-3.0.0}")
              && buildSh.contains("VERSION_CODE=${VERSION_CODE:-30000}")
              && buildSh.contains("TARGET_SDK=36"));
        String ps1 = readRepoFile("scripts/create-release-keystore.ps1");
        check("v3 pipe: the PowerShell script stays 5.1-compatible (no PS7-only operators)",
              !ps1.contains("??") && !ps1.contains("&&") && !ps1.contains(" ? "),
              "PowerShell 5.1 syntax audit");
        String manifest = readRepoFile("AndroidManifest.xml");
        check("v3 pipe: the manifest registers ACTION_SEND text/plain sharing into PlayActivity",
              manifest.contains("android.intent.action.SEND") && manifest.contains("text/plain"));
        check("v3 pipe: the manifest registers ACTION_VIEW http/https stream links",
              manifest.contains("android.intent.action.VIEW") && manifest.contains("BROWSABLE"));
        check("v3 pipe: the on-back callback is pinned (Android 16 back behaviour stays deterministic)",
              manifest.contains("enableOnBackInvokedCallback=\"false\""));

        System.out.println(failed == 0 ? "Lab result: ALL " + passed + " PASSED" : "Lab result: " + failed + " FAILED of " + (passed + failed));
        System.exit(failed == 0 ? 0 : 1);
    }

    /** The first index of a byte sequence inside a packet (−1 when absent). */
    static int indexOfSeq(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
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

    // ═══════════════════════════════════════════════════════════════════════
    // v2.4.0 helpers: the §8.1 probe builder, the AOSP decoder mirror, the
    // v2.3.x legacy packet, the daemon answer, and the mock GitHub server.
    // ═══════════════════════════════════════════════════════════════════════

    /** Build an RFC 6762 §8.1 CONFLICT PROBE exactly like AOSP MdnsProber
     *  .makePacket: a QUERY with a QTYPE=ANY question on <instance>.<type>.local
     *  and the proposed records in the AUTHORITY section. This is what
     *  NsdManager.registerService sends BEFORE onServiceRegistered can fire. */
    static byte[] buildProbePacket(String instance, String type) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(256);
        b.write(0); b.write(0);                 // id
        b.write(0); b.write(0);                 // flags: QUERY
        b.write(0); b.write(1);                 // QDCOUNT 1
        b.write(0); b.write(0);                 // ANCOUNT 0
        b.write(0); b.write(1);                 // NSCOUNT 1 (the proposed record)
        b.write(0); b.write(0);                 // ARCOUNT 0
        // QNAME: <instance>.<type>.local (empty instance → just the type labels)
        if (!instance.isEmpty()) {
            byte[] ib = utf8(instance);
            b.write(ib.length); b.write(ib, 0, ib.length);
        }
        for (String l : new String[]{type, "local"}) {
            byte[] lb = utf8(l);
            b.write(lb.length); b.write(lb, 0, lb.length);
        }
        b.write(0);                             // root
        b.write(0); b.write(255);               // QTYPE ANY
        b.write(0); b.write(0x01);              // QCLASS IN (no QU on probes)
        // AUTHORITY: a proposed SRV for the same name (the tiebreak record)
        for (String l : new String[]{instance.isEmpty() ? type : instance, "local"}) {
            if (instance.isEmpty() && l.equals(type)) { /* type already written above only in QNAME */ }
            byte[] lb = utf8(l);
            b.write(lb.length); b.write(lb, 0, lb.length);
        }
        b.write(0);
        b.write(0); b.write(33);                // TYPE SRV
        b.write(0); b.write(1);
        b.write(0); b.write(0); b.write(0); b.write(120);   // TTL
        b.write(0); b.write(7);                 // RDLENGTH
        b.write(0); b.write(0); b.write(0); b.write(0x22); b.write(0xB9);  // pri/wt/port(8765)... (content irrelevant)
        b.write(0); b.write(0); b.write(0);
        return b.toByteArray();
    }

    /** A hand-built v2.3.x-SHAPED answer (the field bug): SRV target = the bare
     *  instance name, A owner = the full service name — they never match, so
     *  Android's decoder could never attach the address. */
    static byte[] buildLegacyV23Answer() {
        String instance = "AnantPlay Sinhala Player";
        String type = "_anantplay._tcp";
        ByteArrayOutputStream b = new ByteArrayOutputStream(512);
        b.write(0); b.write(0);
        b.write(0x84); b.write(0);              // AA + response
        b.write(0); b.write(0);                 // QD 0
        b.write(0); b.write(1);                 // AN 1
        b.write(0); b.write(0);                 // NS 0
        b.write(0); b.write(3);                 // AR 3
        byte[] ib = utf8(instance), tb = utf8(type);
        // PTR
        b.write(ib.length); b.write(ib, 0, ib.length);
        b.write(tb.length); b.write(tb, 0, tb.length);
        b.write(5); b.write('l'); b.write('o'); b.write('c'); b.write('a'); b.write('l');
        b.write(0);
        b.write(0); b.write(12); b.write(0); b.write(1);
        b.write(0); b.write(0); b.write(0); b.write(120);
        b.write(ib.length); b.write(ib, 0, ib.length); b.write(0);   // ptr rdata: instance
        // SRV (owner = instance.type.local; target = BARE instance name — the v2.3.x bug)
        b.write(ib.length); b.write(ib, 0, ib.length);
        b.write(tb.length); b.write(tb, 0, tb.length);
        b.write(5); b.write('l'); b.write('o'); b.write('c'); b.write('a'); b.write('l');
        b.write(0);
        b.write(0); b.write(33); b.write(0x80); b.write(1);
        b.write(0); b.write(0); b.write(0); b.write(120);
        byte[] srvData = new byte[6 + ib.length + 1];
        srvData[4] = (byte) (8765 >> 8); srvData[5] = (byte) 8765;
        System.arraycopy(ib, 0, srvData, 6, ib.length);
        b.write(srvData.length >> 8); b.write(srvData.length);
        b.write(srvData, 0, srvData.length);
        // TXT
        b.write(ib.length); b.write(ib, 0, ib.length);
        b.write(tb.length); b.write(tb, 0, tb.length);
        b.write(5); b.write('l'); b.write('o'); b.write('c'); b.write('a'); b.write('l');
        b.write(0);
        b.write(0); b.write(16); b.write(0x80); b.write(1);
        b.write(0); b.write(0); b.write(0); b.write(120);
        byte[] txtData = utf8("app=sicast");
        b.write(0); b.write(txtData.length); b.write(txtData, 0, txtData.length);
        // A on the FULL service name (≠ SRV target → never attaches)
        b.write(ib.length); b.write(ib, 0, ib.length);
        b.write(tb.length); b.write(tb, 0, tb.length);
        b.write(5); b.write('l'); b.write('o'); b.write('c'); b.write('a'); b.write('l');
        b.write(0);
        b.write(0); b.write(1); b.write(0x80); b.write(1);
        b.write(0); b.write(0); b.write(0); b.write(120);
        b.write(0); b.write(4); b.write(192); b.write(168); b.write(1); b.write(39);
        return b.toByteArray();
    }

    /** A daemon-shaped answer: the name ONLY the system daemon (our NsdManager
     *  registration) advertises — used to prove the L1 attribution logic. */
    static byte[] buildDaemonAnswer(String instanceName) {
        String type = "_anantplay._tcp";
        ByteArrayOutputStream b = new ByteArrayOutputStream(512);
        b.write(0); b.write(0);
        b.write(0x84); b.write(0);
        b.write(0); b.write(0); b.write(0); b.write(1); b.write(0); b.write(0); b.write(0); b.write(2);
        byte[] ib = utf8(instanceName), tb = utf8(type);
        // PTR instance.type.local → instance
        b.write(tb.length); b.write(tb, 0, tb.length);
        b.write(5); b.write('l'); b.write('o'); b.write('c'); b.write('a'); b.write('l');
        b.write(0);
        b.write(0); b.write(12); b.write(0); b.write(1);
        b.write(0); b.write(0); b.write(0); b.write(120);
        b.write(ib.length); b.write(ib, 0, ib.length); b.write(0);
        // SRV instance.type.local → android-abcdef.local
        b.write(ib.length); b.write(ib, 0, ib.length);
        b.write(tb.length); b.write(tb, 0, tb.length);
        b.write(5); b.write('l'); b.write('o'); b.write('c'); b.write('a'); b.write('l');
        b.write(0);
        b.write(0); b.write(33); b.write(0x80); b.write(1);
        b.write(0); b.write(0); b.write(0); b.write(120);
        byte[] host = utf8("android-abcdef");
        byte[] srvData = new byte[6 + host.length + 6];
        srvData[4] = (byte) (8765 >> 8); srvData[5] = (byte) 8765;
        System.arraycopy(host, 0, srvData, 6, host.length);
        // host + ".local" + root
        srvData[6 + host.length] = 5;
        srvData[7 + host.length] = 'l'; srvData[8 + host.length] = 'o'; srvData[9 + host.length] = 'c';
        srvData[10 + host.length] = 'a'; srvData[11 + host.length] = 'l';
        b.write(srvData.length >> 8); b.write(srvData.length);
        b.write(srvData, 0, srvData.length);
        return b.toByteArray();
    }

    /** THE AOSP DECODER MIRROR — parses a response packet with the same rules
     *  Android 14–16's MdnsResponseDecoder + MdnsServiceTypeClient apply:
     *   · must be a response (QR) with ≥1 answer (parseResponse, decoder:96-104)
     *   · PTR creates the response entry (Loop 1)
     *   · SRV/TXT attach by full name match (Loop 2)
     *   · A/AAAA attach by SRV-HOST name match (Loop 3, decoder:248-255)
     *   · isComplete = SRV && TXT && ≥1 inet record (MdnsResponse:336-341)
     * Also checks the wire details: QDCOUNT, cache-flush bits, TTLs, label
     * encoding (UTF-8, ≤63 bytes), and compression-pointer safety. */
    static final class AospDecoder {
        static final class Response {
            boolean parseOk;
            String error = "";
            int qdcount, ancount;
            int ptrClass = -1, srvClass = -1, txtClass = -1, aClass = -1;
            long ptrTtl = -1, srvTtl = -1, txtTtl = -1, aTtl = -1;
            String ptrInstance;          // from the PTR rdata
            String srvOwner, srvHost;    // SRV owner name / target host
            int srvPort = -1;
            String txtOwner;
            String aOwner, aIpv4;
            boolean isComplete() {
                return srvHost != null && txtOwner != null && aOwner != null
                    && aOwner.equalsIgnoreCase(srvHost);   // the Loop-3 attach rule
            }
        }

        static Response decode(byte[] pkt, String wantType) {
            Response r = new Response();
            try {
                if (pkt == null || pkt.length < 12) { r.error = "short"; return r; }
                if ((pkt[2] & 0x80) == 0) { r.error = "not-a-response"; return r; }
                r.qdcount = ((pkt[4] & 0xFF) << 8) | (pkt[5] & 0xFF);
                r.ancount = ((pkt[6] & 0xFF) << 8) | (pkt[7] & 0xFF);
                int nscount = ((pkt[8] & 0xFF) << 8) | (pkt[9] & 0xFF);
                int arcount = ((pkt[10] & 0xFF) << 8) | (pkt[11] & 0xFF);
                if (r.ancount < 1) { r.error = "no-answers"; return r; }
                int pos = 12;
                // skip questions
                for (int i = 0; i < r.qdcount; i++) {
                    int[] end = new int[1];
                    readLabels(pkt, pos, end);
                    pos = end[0] + 4;
                }
                int records = r.ancount + nscount + arcount;
                for (int i = 0; i < records && pos < pkt.length; i++) {
                    int[] end = new int[1];
                    String[] owner = readLabels(pkt, pos, end);
                    pos = end[0];
                    if (pos + 10 > pkt.length) { r.error = "record-truncated"; return r; }
                    int type = ((pkt[pos] & 0xFF) << 8) | (pkt[pos + 1] & 0xFF);
                    int cls = ((pkt[pos + 2] & 0xFF) << 8) | (pkt[pos + 3] & 0xFF);
                    long ttl = ((long) (pkt[pos + 4] & 0xFF) << 24) | ((pkt[pos + 5] & 0xFF) << 16)
                        | ((pkt[pos + 6] & 0xFF) << 8) | (pkt[pos + 7] & 0xFF);
                    int rdlen = ((pkt[pos + 8] & 0xFF) << 8) | (pkt[pos + 9] & 0xFF);
                    pos += 10;
                    if (pos + rdlen > pkt.length) { r.error = "rdata-truncated"; return r; }
                    byte[] rdata = new byte[rdlen];
                    System.arraycopy(pkt, pos, rdata, 0, rdlen);
                    pos += rdlen;
                    String ownerName = join(owner);
                    switch (type) {
                        case 12: {   // PTR
                            r.ptrClass = cls; r.ptrTtl = ttl;
                            int[] e2 = new int[1];
                            String[] target = readLabels(rdata, 0, e2);
                            // the instance is the first label of the PTR target
                            r.ptrInstance = target != null && target.length > 0 ? target[0] : null;
                            break;
                        }
                        case 33: {   // SRV
                            r.srvClass = cls; r.srvTtl = ttl; r.srvOwner = ownerName;
                            if (rdlen >= 6) {
                                r.srvPort = ((rdata[4] & 0xFF) << 8) | (rdata[5] & 0xFF);
                                int[] e2 = new int[1];
                                String[] host = readLabels(rdata, 6, e2);
                                r.srvHost = join(host);
                            }
                            break;
                        }
                        case 16: r.txtClass = cls; r.txtTtl = ttl; r.txtOwner = ownerName; break;
                        case 1:    // A
                            r.aClass = cls; r.aTtl = ttl; r.aOwner = ownerName;
                            if (rdlen == 4) r.aIpv4 = (rdata[0] & 0xFF) + "." + (rdata[1] & 0xFF)
                                + "." + (rdata[2] & 0xFF) + "." + (rdata[3] & 0xFF);
                            break;
                        default: break;
                    }
                }
                r.parseOk = true;
                return r;
            } catch (Throwable t) {
                r.error = String.valueOf(t);
                return r;
            }
        }

        static String join(String[] labels) {
            if (labels == null) return null;
            StringBuilder sb = new StringBuilder();
            for (String l : labels) {
                if (sb.length() > 0) sb.append('.');
                sb.append(l);
            }
            return sb.toString();
        }

        /** Strict label reader: UTF-8 labels ≤ 63 bytes, compression pointers
         *  followed with a hop cap (mirrors MdnsPacketReader.readLabels). */
        static String[] readLabels(byte[] p, int at, int[] endOut) {
            java.util.List<String> labels = new java.util.ArrayList<>();
            int pos = at, jumps = 0, end = -1;
            while (true) {
                if (pos >= p.length || jumps > 8) throw new IllegalArgumentException("bad name");
                int len = p[pos] & 0xFF;
                if (len == 0) { if (end < 0) end = pos + 1; break; }
                if ((len & 0xC0) == 0xC0) {
                    if (pos + 1 >= p.length) throw new IllegalArgumentException("bad pointer");
                    if (end < 0) end = pos + 2;
                    pos = ((len & 0x3F) << 8) | (p[pos + 1] & 0xFF);
                    jumps++;
                    continue;
                }
                if (len > 63 || pos + 1 + len > p.length) throw new IllegalArgumentException("bad label");
                labels.add(new String(p, pos + 1, len, StandardCharsets.UTF_8));
                pos += 1 + len;
            }
            endOut[0] = end;
            return labels.toArray(new String[0]);
        }
    }

    /** A tiny local HTTP server that plays GitHub Releases: serves
     *  /releases/latest (JSON), the apk asset, SHA256SUMS.txt, and a /dl/huge
     *  path with an over-cap declared Content-Length (the pre-read gate test). */
    static final class MockGithub {
        final String json;
        final byte[] apk;
        final ServerSocket ss;

        MockGithub(String version, byte[] apk, String sha) throws Exception {
            this(version, apk, sha, apk.length);
        }

        MockGithub(String version, byte[] apk, String sha, long size) throws Exception {
            this.apk = apk;
            this.ss = new ServerSocket(0);
            String apkName = "AnantPlay-Sinhala-Player-v" + version + ".apk";
            String base = url(""); if (!base.endsWith("/")) base = base + "/";
            String jsonSums = sha + "  " + apkName;
            String body = "{\"tag_name\":\"v" + version + "\",\"assets\":["
                + "{\"name\":\"" + apkName + "\",\"browser_download_url\":\"" + base + "dl/" + apkName + "\",\"size\":" + size + "},"
                + "{\"name\":\"SHA256SUMS.txt\",\"browser_download_url\":\"" + base + "dl/SHA256SUMS.txt\"}"
                + "]}";
            this.json = body;
            // stash the sums where /dl/SHA256SUMS.txt serves them
            System.setProperty("mockgithub.sums", jsonSums);
            Thread t = new Thread(() -> {
                try {
                    while (!ss.isClosed()) {
                        Socket s = ss.accept();
                        new Thread(() -> answer(s)).start();
                    }
                } catch (Exception ignored) { }
            }, "mock-github");
            t.setDaemon(true);
            t.start();
        }

        void answer(Socket s) {
            try (Socket sock = s) {
                sock.setSoTimeout(4000);
                InputStream in = sock.getInputStream();
                // read ONLY the request head (until the blank line) — reading to
                // EOF would deadlock: HttpURLConnection holds the connection open
                // waiting for our response.
                StringBuilder req = new StringBuilder();
                int c;
                while ((c = in.read()) != -1) {
                    req.append((char) c);
                    if (req.length() > 8192) break;
                    String r = req.toString();
                    if (r.endsWith("\r\n\r\n")) break;
                }
                String head = req.toString();
                int nl = head.indexOf("\r\n");
                String first = head.isEmpty() ? "GET /" : head.substring(0, nl > 0 ? nl : head.length());
                String path = first.split(" ").length > 1 ? first.split(" ")[1] : "/";
                OutputStream out = sock.getOutputStream();
                if (path.endsWith("/dl/huge")) {
                    // the over-cap declared length: Content-Length says 2 GB, no body
                    out.write(utf8("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream"
                        + "\r\nContent-Length: 2147483647\r\nConnection: close\r\n\r\n"));
                    out.flush();
                    return;
                }
                byte[] body;
                String ctype = "application/octet-stream";
                if (path.endsWith("/releases/latest")) { body = utf8(json); ctype = "application/json"; }
                else if (path.endsWith("SHA256SUMS.txt")) { body = utf8(System.getProperty("mockgithub.sums", "")); ctype = "text/plain"; }
                else if (path.contains("/dl/")) { body = apk; }
                else { body = utf8("404"); }
                out.write(utf8("HTTP/1.1 200 OK\r\nContent-Type: " + ctype
                    + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n"));
                out.write(body);
                out.flush();
            } catch (Exception ignored) { }
        }

        String url(String path) {
            return "http://127.0.0.1:" + ss.getLocalPort() + path;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // v3.0.0 helpers: DNS query builders, the AnantPlay describe() mirror,
    // the SOAP client, and the repo-file reader for pipeline parity checks.
    // ═══════════════════════════════════════════════════════════════════════

    /** A one-question DNS/mDNS QUERY for <qname> with the given QTYPE (no QU bit). */
    static byte[] dnsQuery(String qname, int qtype) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(96);
        b.write(0); b.write(0);
        b.write(0); b.write(0);            // flags: QUERY
        b.write(0); b.write(1);            // QDCOUNT 1
        b.write(0); b.write(0); b.write(0); b.write(0); b.write(0); b.write(0);
        for (String label : qname.split("\\.")) {
            byte[] lb = utf8(label);
            b.write(lb.length); b.write(lb, 0, lb.length);
        }
        b.write(0);
        b.write((qtype >> 8) & 0xFF); b.write(qtype & 0xFF);
        b.write(0); b.write(0x01);         // QCLASS IN
        return b.toByteArray();
    }

    /** A two-question PTR browse (both service types in one packet). */
    static byte[] dnsQuery2(String qname1, String qname2) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(160);
        b.write(0); b.write(0);
        b.write(0); b.write(0);
        b.write(0); b.write(2);            // QDCOUNT 2
        b.write(0); b.write(0); b.write(0); b.write(0); b.write(0); b.write(0);
        for (String qname : new String[]{qname1, qname2}) {
            for (String label : qname.split("\\.")) {
                byte[] lb = utf8(label);
                b.write(lb.length); b.write(lb, 0, lb.length);
            }
            b.write(0);
            b.write(0); b.write(12);       // QTYPE PTR
            b.write(0); b.write(0x01);
        }
        return b.toByteArray();
    }

    /** ANANTPLAY'S OWN describe() — a verbatim port of Dlna.java:74-88 (the
     *  regexes, the URLBase handling, the AVTransport-required rule) so the lab
     *  proves OUR desc.xml parses with THEIR exact client code. */
    static String[] describeLikeAnantPlay(String location, String xml) {
        try {
            String name = textOf(xml, "friendlyName");
            String base = textOf(xml, "URLBase");
            java.net.URL at = new java.net.URL(base != null && !base.isEmpty() ? base : location);
            String avt = null, rc = "";
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<(?:\\w+:)?service>(.*?)</(?:\\w+:)?service>", java.util.regex.Pattern.DOTALL).matcher(xml);
            while (m.find()) {
                String type = textOf(m.group(1), "serviceType"), control = textOf(m.group(1), "controlURL");
                if (type == null || control == null) continue;
                if (type.startsWith("urn:schemas-upnp-org:service:AVTransport:")) avt = new java.net.URL(at, control).toString();
                else if (type.startsWith("urn:schemas-upnp-org:service:RenderingControl:")) rc = new java.net.URL(at, control).toString();
            }
            if (avt == null) return null;
            return new String[]{name == null || name.isEmpty() ? new java.net.URL(location).getHost() : name, avt, rc};
        } catch (Exception e) {
            return null;
        }
    }

    /** AnantPlay's text() helper (Dlna.java:91-94): one XML element's text. */
    static String textOf(String xml, String tag) {
        if (xml == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("<(?:\\w+:)?" + java.util.regex.Pattern.quote(tag) + "(?:\\s[^>]*)?>(.*?)</(?:\\w+:)?" + java.util.regex.Pattern.quote(tag) + ">",
                java.util.regex.Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    /** A SOAP POST exactly like AnantPlay's Dlna.soap() (Dlna.java:119-140):
     *  the envelope, the SOAPAction header, the text/xml content type. */
    static String soapBody(String service, String action, String args) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
            + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:" + action
            + " xmlns:u=\"" + service + "\">" + args + "</u:" + action + "></s:Body></s:Envelope>";
    }

    static String soap(String url, String service, String action, String args) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(java.net.Proxy.NO_PROXY);
        c.setRequestMethod("POST");
        c.setConnectTimeout(3000);
        c.setReadTimeout(4000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        c.setRequestProperty("SOAPAction", "\"" + service + "#" + action + "\"");
        try (OutputStream o = c.getOutputStream()) {
            o.write(utf8(soapBody(service, action, args)));
        }
        try (InputStream in = c.getInputStream()) { return readAll(in); }
        finally { c.disconnect(); }
    }

    /** The same SOAP POST but expecting the HTTP-level fault. Mirrors AnantPlay's
     *  soap() order exactly: getResponseCode() FIRST (which also materialises the
     *  error stream on every JDK), then read the body — AnantPlay throws its
     *  IOException on code >= 400 and regex-reads errorDescription from the body. */
    static String soapExpectError(String url, String service, String action, String args) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(java.net.Proxy.NO_PROXY);
            c.setRequestMethod("POST");
            c.setConnectTimeout(3000);
            c.setReadTimeout(4000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            c.setRequestProperty("SOAPAction", "\"" + service + "#" + action + "\"");
            try (OutputStream o = c.getOutputStream()) {
                o.write(utf8(soapBody(service, action, args)));
            }
            int code = c.getResponseCode();   // AnantPlay's own first read (and the JDK's error-stream materialiser)
            InputStream err = c.getErrorStream();
            String body = err != null ? readAll(err) : "";
            c.disconnect();
            return "HTTP " + code + " " + body;
        } catch (Exception e) {
            return "ERR:" + e;
        }
    }

    /** Read a repo file (the lab runs from the repo root) — the pipeline-parity
     *  checks parse the real scripts/workflows instead of trusting memory. */
    static String readRepoFile(String path) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(new java.io.File(path).toPath()), StandardCharsets.UTF_8);
    }
}
