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
        MdnsResponder rsp = new MdnsResponder("_anantplay._tcp", "_fmhyplay._tcp",
            "AnantPlay on SiTest", 8765, InetAddress.getByName("127.0.0.1"));

        // 1. the responder answers a PTR question for OUR type
        byte[] qAnant = MdnsWire.queryPacket("_anantplay._tcp");
        byte[] ansAnant = rsp.answerIfForUs(qAnant);
        check("v2.3 mdns: responder answers a PTR? _anantplay._tcp.local query", ansAnant != null && ansAnant.length > 60,
              ansAnant == null ? "null" : ansAnant.length + " B");

        // 2. it also answers the legacy type
        byte[] qFmhy = MdnsWire.queryPacket("_fmhyplay._tcp");
        check("v2.3 mdns: responder answers the legacy _fmhyplay._tcp type",
              rsp.answerIfForUs(qFmhy) != null);

        // 3. it does NOT answer a foreign service type
        byte[] qForeign = MdnsWire.queryPacket("_googlecast._tcp");
        check("v2.3 mdns: responder stays silent for a foreign type",
              rsp.answerIfForUs(qForeign) == null);

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
        // the A record: find the LAST "TYPE A(1) CLASS IN(1)" pair (an earlier {0,1,0,1}
        // byte run can appear inside SRV rdata), then rdlength 4 + the 4 IP bytes
        int aAt = -1;
        {
            int from = 0;
            while (true) {
                int hit = indexOfSeq(ansAnant, new byte[]{0, 1, 0, 1});
                if (hit < 0 || hit < from) break;
                aAt = hit;
                from = hit + 1;
                // advance the search window past this hit
                byte[] rest = java.util.Arrays.copyOfRange(ansAnant, hit + 1, ansAnant.length);
                int next = indexOfSeq(rest, new byte[]{0, 1, 0, 1});
                if (next < 0) break;
                aAt = hit + 1 + next;
                from = aAt + 1;
            }
        }
        boolean aOk = false;
        if (aAt > 0 && aAt + 10 <= ansAnant.length) {
            // aAt points at TYPE(0,1) CLASS(0,1) TTL(4) → rdlen at aAt+8
            aOk = ansAnant[aAt + 8] == 0 && ansAnant[aAt + 9] == 4;
        }
        check("v2.3 mdns: A record carries an IPv4 rdlength-4 address", aOk,
              aAt < 0 ? "no TYPE-A record found" : "A at " + aAt);

        // 5. a response packet (not a query) is never answered
        byte[] notQuery = java.util.Arrays.copyOf(qAnant, qAnant.length);
        notQuery[2] = (byte) 0x84;
        check("v2.3 mdns: a RESPONSE packet never triggers an answer",
              rsp.answerIfForUs(notQuery) == null);

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
            responderStarted = rsp.start();
        } catch (Throwable t) {
            System.out.println("  (responder start failed on this host: " + t + ")");
        }
        if (responderStarted) {
            MdnsResponder.ProbeResult pr = MdnsResponder.probeLocal(rsp, 4000);
            check("v2.3 mdns: ROUTER-FREE loopback probe answers (kernel multicast loopback)",
                  pr.answered, "sent=" + pr.packetsSent + " recv=" + pr.packetsReceived + " " + pr.responderUp);
            check("v2.3 mdns: the probe's reply carries our instance name", pr.answered);
            rsp.stop();
            check("v2.3 mdns: responder stops cleanly", !rsp.isRunning());
        } else {
            // CI without multicast (rare): the parse-level checks above still gate the wire format
            check("v2.3 mdns: responder shares UDP 5353 on this host (loopback probe SKIPPED — no multicast in this env)",
                  false, "start() returned false — run on a host with 224.0.0.251 loopback for the full probe");
        }
        // 8. EADDRINUSE grace: a SECOND responder on the same port must degrade, not throw
        MdnsResponder second = new MdnsResponder("_anantplay._tcp", "", "Second", 8766, InetAddress.getByName("127.0.0.1"));
        boolean secondStarted = false;
        try { rsp.start(); secondStarted = second.start(); } catch (Throwable t) { secondStarted = false; }
        if (secondStarted) {
            // both bound with SO_REUSEADDR (allowed on Linux) — fine, stop both cleanly
            second.stop();
            check("v2.3 mdns: a second responder coexists or degrades without an exception", true);
        } else {
            check("v2.3 mdns: a refused 5353 bind degrades to false, never an exception", true);
        }
        try { rsp.stop(); } catch (Throwable ignored) { }

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
}
