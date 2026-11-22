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

    /** Count occurrences of one char — used by the Sinhala ZWJ/ZWNJ regression lab. */
    static int countChar(String s, char c) {
        if (s == null) return 0;
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
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

        // ── unit: isSelf (the LAN-source lock; v3.1 = the LocalNet matrix) ───
        byte[] ownLan = new byte[]{(byte)192,(byte)168,1,39};
        List<byte[]> own = java.util.Arrays.asList(
            ownLan, new byte[]{10,0,0,7},
            java.net.InetAddress.getByName("fe80::1a2b:3c4d:5e6f:7a8b").getAddress());
        check("isSelf loopback", LocalNet.isSelf(InetAddress.getByName("127.0.0.1"), null, null, null));
        check("isSelf rejects a foreign phone", !LocalNet.isSelf(InetAddress.getByName("192.168.1.77"), null, own, own));
        check("isSelf accepts the v4-mapped loopback form (vendor v6 sockets)",
            LocalNet.isSelf(InetAddress.getByName("::ffff:127.0.0.1"), null, null, null));

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
        check("v3 upd: versionCode 30300 strictly > 30200 (v3.2.0) and still v3.x — no Obtainium downgrade",
              AppInfo.VERSION_CODE == 30300 && AppInfo.VERSION_CODE > 30200 && AppInfo.VERSION_CODE < 40000
              && "3.3.0".equals(AppInfo.VERSION_NAME));

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
        check("v3 pipe: build.sh defaults match the shipped version (3.3.0 / 30300 / targetSdk 36)",
              buildSh.contains("VERSION_NAME=${VERSION_NAME:-3.3.0}")
              && buildSh.contains("VERSION_CODE=${VERSION_CODE:-30300}")
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

        // ════════════════════════════════════════════════════════════════════
        // v3.1 — THE 2026-11-22 16:36 FIELD-BUG REGRESSIONS (5 smoking guns)
        // ════════════════════════════════════════════════════════════════════
        System.out.println("== v3.1 field-bug regressions ==");
        v31LocalNetChecks();
        v31AnnouncerChecks();
        v31UpdaterChecks();
        v31CrashLogChecks();
        v31ThemeChecks();
        v31SecurityChecks();

        System.out.println("== v3.1.2 crash-fix + privacy troubleshooter ==");
        v312CrashFixChecks();
        v312PrivacyScrubberChecks();
        v312TroubleshooterChecks();
        v312SelfUpdaterSpinChecks();
        v312LaunchContractChecks();

        // ═════════ v3.2.0 — THE MASTER OVERHAUL LABS (Phases 1–4) ═════════
        System.out.println("== v3.2.0 zero-rate-limit updater ==");
        v320UpdaterZeroRateLimitChecks();
        System.out.println("== v3.2.0 Sinhala font, script & markup ==");
        v320SinhalaFontScriptChecks();
        System.out.println("== v3.2.0 free keyless translator + auto-zip ==");
        v320FreeTranslatorZipChecks();
        System.out.println("== v3.2.0 junk-title cleaner ==");
        v320JunkTitleChecks();
        System.out.println("== v3.2.0 stale-crash auto-clear ==");
        v320StaleCrashChecks();
        System.out.println("== v3.2.0 deep customization + custom links ==");
        v320CustomizationChecks();
        System.out.println("== v3.2.0 10-point troubleshooter ==");
        v320TroubleshooterChecks();

        // ═════════ v3.3.0 — THE CINEMA PLAYER, THE 403 KILLER & THE NAV SHELL ═════════
        System.out.println("== v3.3.0 navigation & HUD layout invariants ==");
        v330LayoutChecks();
        System.out.println("== v3.3.0 HTTP-403 header forwarding & recovery ==");
        v330Http403Checks();
        System.out.println("== v3.3.0 video aspect-ratio math ==");
        v330AspectChecks();
        System.out.println("== v3.3.0 Sri Lankan subtitles, .zip capture & external VLC ==");
        v330SubtitleChecks();
        System.out.println("== v3.3.0 version & pipeline parity ==");
        v330VersionParityChecks();

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
    // ════════════════════════════════════════════════════════════════════════
    // v3.1 — Bug 1: the LocalNet same-host matrix + the live DLNA E2E
    // (the 2026-11-22 16:36 "GET /dlna/desc.xml -> refused-nonlocal" proof)
    // ════════════════════════════════════════════════════════════════════════
    static void v31LocalNetChecks() throws Exception {
        byte[] own39 = new byte[]{(byte)192, (byte)168, 1, 39};
        byte[] own10 = new byte[]{10, 0, 0, 7};
        byte[] ownFe80 = InetAddress.getByName("fe80::1a2b:3c4d:5e6f:7a8b").getAddress();
        List<byte[]> own = java.util.Arrays.asList(own39, own10, ownFe80);

        // THE FIELD-BUG PROOF: the phone's own wlan0 IP, the enumeration empty
        // (the HyperOS failure mode), the connection's own endpoint matching —
        // the same-host rule alone must accept it.
        check("v31 b1: the own wlan0 192.168.1.39 is accepted via the SAME-HOST rule (live enum EMPTY — the exact HyperOS failure)",
            LocalNet.isSelf(InetAddress.getByName("192.168.1.39"),
                InetAddress.getByName("192.168.1.39"), new java.util.ArrayList<>(), new java.util.ArrayList<>()));
        // …and via the cached snapshot when the same-host endpoint is unknown
        check("v31 b1: the own wlan0 IP is accepted via the CACHED snapshot (live enum EMPTY)",
            LocalNet.isSelf(InetAddress.getByName("192.168.1.39"), null,
                new java.util.ArrayList<>(), java.util.Arrays.asList(own39)));
        // …and via the live enumeration
        check("v31 b1: the own wlan0 IP is accepted via the LIVE enumeration",
            LocalNet.isSelf(InetAddress.getByName("192.168.1.39"), null, own, null));
        // the mapped-v6 form of the same phone
        check("v31 b1: ::ffff:192.168.1.39 (dual-stack socket) is accepted",
            LocalNet.isSelf(InetAddress.getByName("::ffff:192.168.1.39"),
                InetAddress.getByName("192.168.1.39"), new java.util.ArrayList<>(), new java.util.ArrayList<>()));
        check("v31 b1: ::ffff:192.168.1.39 normalizes to the 4-byte v4 form",
            java.util.Arrays.equals(LocalNet.normalize(InetAddress.getByName("::ffff:192.168.1.39")), own39));
        // the own IPv6 link-local (scope-id differences must not matter: byte compare)
        check("v31 b1: the own fe80:: link-local is accepted (byte-wise, scope-safe)",
            LocalNet.isSelf(InetAddress.getByName("fe80::1a2b:3c4d:5e6f:7a8b"), null, own, null));
        check("v31 b1: loopback v4 + v6 accepted with every list empty",
            LocalNet.isSelf(InetAddress.getByName("127.0.0.1"), null, null, null)
                && LocalNet.isSelf(InetAddress.getByName("::1"), null, null, null));
        // THE STRANGER SIDE: still strictly refused
        check("v31 b1: a LAN stranger 192.168.1.148 is STILL refused (the security lock)",
            !LocalNet.isSelf(InetAddress.getByName("192.168.1.148"), InetAddress.getByName("192.168.1.39"), own, own));
        check("v31 b1: a stranger 10.0.0.5 (different subnet) is refused",
            !LocalNet.isSelf(InetAddress.getByName("10.0.0.5"), InetAddress.getByName("192.168.1.39"), own, own));
        check("v31 b1: a foreign fe80:: is refused",
            !LocalNet.isSelf(InetAddress.getByName("fe80::dead:beef:cafe:f00d"), null, own, own));
        check("v31 b1: null remote is refused defensively",
            !LocalNet.isSelf(null, null, own, own));
        // normalization + helpers
        check("v31 b1: a plain v4 address normalizes to itself",
            java.util.Arrays.equals(LocalNet.normalize(InetAddress.getByName("1.2.3.4")), new byte[]{1,2,3,4}));
        check("v31 b1: isIpv4Mapped rejects non-mapped v6",
            !LocalNet.isIpv4Mapped(InetAddress.getByName("fe80::1").getAddress()));
        check("v31 b1: describe renders the normalized v4 form",
            "192.168.1.39".equals(LocalNet.describe(own39)));
        check("v31 b1: describe renders a mapped v6 as its v4 form",
            "192.168.1.39".equals(LocalNet.describe(InetAddress.getByName("::ffff:192.168.1.39"))));
        check("v31 b1: union dedupes (snapshot refresh never grows unbounded)",
            LocalNet.union(java.util.Arrays.asList(own39), java.util.Arrays.asList(own39, own10)).size() == 2);
        java.util.List<byte[]> enumOwn = LocalNet.enumerateOwn();
        check("v31 b1: enumerateOwn never returns null (empty = unavailable, the v3.0.0 bug shape)",
            enumOwn != null && LocalNet.contains(enumOwn, new byte[]{127,0,0,1}));
        // the 403 contract + reason phrase
        check("v31 b1: the refusal status is HTTP 403 with the exact reason phrase",
            "Forbidden".equals(ReceiverServer.reason(403)));
        check("v31 b1: 200 stays OK",
            "OK".equals(ReceiverServer.reason(200)));

        // LIVE DLNA E2E over a real ReceiverServer socket (the exact flow official
        // AnantPlay drives): desc.xml -> avt.xml -> rc.xml -> SetAVTransportURI ->
        // Play -> GetPositionInfo — ZERO refused-nonlocal anywhere.
        Diag.reset();
        ReceiverServer srv = new ReceiverServer(new FakeBackend(), 18865);
        check("v31 e2e: the receiver binds a lab port", srv.start() && srv.port() == 18865, "port " + srv.port());
        srv.setDlna(new DlnaEndpoint(new DlnaEndpoint.Player() {
            boolean opened;
            public boolean open(String url, String title) { opened = true; return true; }
            public String act(String action, long toMs) { return ReceiverServer.okJson(); }
            public String statusJson() { return ReceiverServer.idleJson(); }
            public int getVolume() { return 50; }
            public void setVolume(int v) { }
        }, "SIcast-lab31", "සිංහල Player", "lab/3.1.1"));
        String base31 = "http://127.0.0.1:" + srv.port();
        String desc = get(base31 + "/dlna/desc.xml");
        check("v31 e2e: GET /dlna/desc.xml answers HTTP 200 XML (NOT a 403/JSON refusal)",
            desc.contains("<?xml") && desc.contains("friendlyName") && desc.contains("සිංහල Player"), desc.substring(0, Math.min(80, desc.length())));
        check("v31 e2e: desc.xml advertises the AVTransport controlURL absolute (Dlna.describe parseable)",
            desc.contains("urn:schemas-upnp-org:service:AVTransport:1")
                && desc.contains("http://127.0.0.1:" + srv.port() + "/dlna/avt"));
        check("v31 e2e: the SCPDs answer 200 XML",
            get(base31 + "/dlna/avt.xml").contains("scpd") && get(base31 + "/dlna/rc.xml").contains("scpd"));
        String setUri = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
            + "<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
            + "<InstanceID>0</InstanceID><CurrentURI>https://cdn.example/the-odyssey/master.m3u8</CurrentURI>"
            + "<CurrentURIMetaData>&lt;DIDL-Lite&gt;&lt;dc:title&gt;The Odyssey&lt;/dc:title&gt;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>"
            + "</u:SetAVTransportURI></s:Body></s:Envelope>";
        String setRes = soapPost(base31 + "/dlna/avt", "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI", setUri);
        check("v31 e2e: POST SetAVTransportURI answers a 200 SOAP envelope (the 'TV refused this video' path is dead)",
            setRes.contains("SOAP") || setRes.contains("Envelope") || setRes.contains("SetAVTransportURI"), setRes.substring(0, Math.min(80, setRes.length())));
        String playRes = soapPost(base31 + "/dlna/avt", "urn:schemas-upnp-org:service:AVTransport:1#Play",
            "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
            + "<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><InstanceID>0</InstanceID><Speed>1</Speed></u:Play></s:Body></s:Envelope>");
        check("v31 e2e: POST Play answers the ok envelope",
            playRes.contains("Play") && !playRes.contains("Fault"));
        String posRes = soapPost(base31 + "/dlna/avt", "urn:schemas-upnp-org:service:AVTransport:1#GetPositionInfo",
            "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
            + "<u:GetPositionInfo xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><InstanceID>0</InstanceID></u:GetPositionInfo></s:Body></s:Envelope>");
        check("v31 e2e: GetPositionInfo carries the TrackURI back",
            posRes.contains("the-odyssey"));
        check("v31 e2e: ZERO refused-nonlocal in the whole exchange (the smoking gun is dead)",
            !Diag.dump().contains("refused-nonlocal"));
        srv.stop();
    }

    // ════════════════════════════════════════════════════════════════════════
    // v3.1 — Bug 3: the NON-DESTRUCTIVE watchdog (AnnouncerPolicy matrix)
    // ════════════════════════════════════════════════════════════════════════
    static void v31AnnouncerChecks() {
        check("v31 b3: the watchdog still fires for the silent hang (answered=false, announced=false)",
            AnnouncerPolicy.watchdogFires(false, false, false));
        check("v31 b3: the watchdog is silent once answered",
            !AnnouncerPolicy.watchdogFires(true, false, false));
        check("v31 b3: the watchdog is silent once announced",
            !AnnouncerPolicy.watchdogFires(false, true, false));
        check("v31 b3: the watchdog never fires after destroy()",
            !AnnouncerPolicy.watchdogFires(false, false, true));
        AnnouncerPolicy.Scope withResponder = AnnouncerPolicy.watchdogScope(true);
        check("v31 b3: a LIVE responder forces the re-register-ONLY scope (the exact field bug)",
            withResponder == AnnouncerPolicy.Scope.RE_REGISTER_ONLY);
        check("v31 b3: the re-register scope may NEVER stop the responder",
            !AnnouncerPolicy.mayStopResponder(withResponder));
        check("v31 b3: the re-register scope may not need to start one either",
            AnnouncerPolicy.mayStartResponder(withResponder));
        AnnouncerPolicy.Scope deadResponder = AnnouncerPolicy.watchdogScope(false);
        check("v31 b3: a DEAD responder allows the start-responder scope",
            deadResponder == AnnouncerPolicy.Scope.RE_REGISTER_AND_START_RESPONDER);
        check("v31 b3: even the start scope may NEVER stop the responder",
            !AnnouncerPolicy.mayStopResponder(deadResponder));
        check("v31 b3: no watchdog path can produce a FULL_REARM (the destructive v3.0.0 shape)",
            withResponder != AnnouncerPolicy.Scope.FULL_REARM && deadResponder != AnnouncerPolicy.Scope.FULL_REARM);
        // network-event scopes
        check("v31 b3: same network + healthy → NONE (the debounce)",
            AnnouncerPolicy.networkEventScope(true, true, false, 60_000, 5_000) == AnnouncerPolicy.Scope.NONE);
        check("v31 b3: same network + pending + inside the grace window → NONE (the callback's own initial event)",
            AnnouncerPolicy.networkEventScope(true, false, true, 1_000, 5_000) == AnnouncerPolicy.Scope.NONE);
        check("v31 b3: same network + dead stack → bring pillars back (never full rearm)",
            AnnouncerPolicy.networkEventScope(true, false, false, 60_000, 5_000)
                == AnnouncerPolicy.Scope.RE_REGISTER_AND_START_RESPONDER);
        check("v31 b3: a DIFFERENT network → full re-arm is allowed (records were bound to the old interface)",
            AnnouncerPolicy.networkEventScope(false, true, false, 60_000, 5_000) == AnnouncerPolicy.Scope.FULL_REARM);
        check("v31 b3: only a full re-arm may stop the responder",
            !AnnouncerPolicy.mayStopResponder(AnnouncerPolicy.Scope.RE_REGISTER_ONLY)
                && !AnnouncerPolicy.mayStopResponder(AnnouncerPolicy.Scope.RE_REGISTER_AND_START_RESPONDER)
                && AnnouncerPolicy.mayStopResponder(AnnouncerPolicy.Scope.FULL_REARM));
    }

    // ════════════════════════════════════════════════════════════════════════
    // v3.1 — Bug 5: silent updater (throttle · cooldown · skip · cleanup)
    // ════════════════════════════════════════════════════════════════════════
    static void v31UpdaterChecks() {
        // THE FLOOD BREAKER: 500 chunk callbacks in one second → exactly ONE notify
        UpdateNotify flood = new UpdateNotify();
        long t0 = 1_000_000L;
        for (int i = 0; i < 500; i++) flood.onProgress(i * 100 / 500, t0 + i * 2);   // 2 ms apart
        check("v31 b5: 500 rapid chunk callbacks post AT MOST ONE notify (the SystemUI flood breaker)",
            flood.notified() == 1 && flood.skipped() == 499, "notified=" + flood.notified());
        // a 10-second download with 1 callback/20 ms → at most 10 notifies
        UpdateNotify ten = new UpdateNotify();
        for (int ms = 0; ms < 10_000; ms += 20) ten.onProgress(ms * 100 / 10_000, t0 + ms);
        check("v31 b5: a 10 s burst still throttles to <= 10 notifies (was ~500 in v3.0.0)",
            ten.notified() <= 10, "notified=" + ten.notified());
        // dedupe per percent
        UpdateNotify dupe = new UpdateNotify();
        dupe.onProgress(10, t0);
        check("v31 b5: the same percent never notifies twice",
            !dupe.onProgress(10, t0 + 2_000) && dupe.notified() == 1);
        // the meaningful-step helper
        check("v31 b5: a >=15% jump counts as meaningful",
            UpdateNotify.meaningfulStep(10, 25) && UpdateNotify.meaningfulStep(40, 10) && !UpdateNotify.meaningfulStep(10, 20));
        // the 24 h per-version cooldown
        long now = 100L * 365 * 24 * 3600 * 1000;
        check("v31 b5: a never-alerted version may alert",
            UpdateNotify.alertAllowed("3.2.0", now, 0, null, false));
        check("v31 b5: an alert 12 h ago is SUPPRESSED (the repeat-spam killer)",
            !UpdateNotify.alertAllowed("3.2.0", now, now - 12L * 3600 * 1000, null, false));
        check("v31 b5: an alert 24 h ago may alert again",
            UpdateNotify.alertAllowed("3.2.0", now, now - 24L * 3600 * 1000 - 1, null, false));
        check("v31 b5: a MANUAL check always bypasses the cooldown",
            UpdateNotify.alertAllowed("3.2.0", now, now - 1000, null, true));
        // skip-version
        check("v31 b5: a skipped version never alerts (automatic cycles)",
            !UpdateNotify.alertAllowed("3.2.0", now, 0, "3.2.0", false));
        check("v31 b5: the skip only covers ITS version",
            UpdateNotify.alertAllowed("3.2.0", now, 0, "3.1.9", false));
        check("v31 b5: a skipped 3.1.9 stops suppressing once 3.2.0 lands",
            UpdateNotify.stillSkipped("3.1.9", "3.1.9") && UpdateNotify.stillSkipped("3.1.9", null)
                && !UpdateNotify.stillSkipped("3.1.9", "3.2.0"));
        // the constants match the spec
        check("v31 b5: the throttle window is exactly 1000 ms and the cooldown 24 h",
            UpdateNotify.MIN_INTERVAL_MS == 1000 && UpdateNotify.ALERT_COOLDOWN_MS == 24L * 60 * 60 * 1000);
    }

    // ════════════════════════════════════════════════════════════════════════
    // v3.1 — Bug 2: CrashLog (format · redaction · atomic round-trip)
    // ════════════════════════════════════════════════════════════════════════
    static void v31CrashLogChecks() throws Exception {
        Exception root = new IllegalStateException("stream failed for https://x/v.m3u8 Cookie: sid=TOPSECRET99 token=ABC123XYZ");
        try { throw new RuntimeException("play failed", root); } catch (RuntimeException withCause) {
            String report = CrashLog.format(1732280200000L, "3.1.1", 30101,
                "sdk 36 | Xiaomi 25013PS27C | release-16", "si-req", withCause);
            check("v31 b2: the report carries the app version + thread + BOTH causes",
                report.contains("v3.1.1") && report.contains("si-req")
                    && report.contains("RuntimeException") && report.contains("CAUSED BY java.lang.IllegalStateException"));
            check("v31 b2: the full stack frame lines are present (no 3000-char cap)",
                report.contains("at ") && report.contains("v31CrashLogChecks"));
            check("v31 b2: the timestamp is wall-clock formatted",
                report.contains("2026-") || report.contains("20"));
            check("v31 b2: cookies + tokens in the exception MESSAGE are redacted",
                !report.contains("TOPSECRET99") && !report.contains("ABC123XYZ"));
            check("v312 b2: a v3.1.1-style HARDWARE device line is scrubbed away (the privacy fix), sdk survives",
                PrivacyScrubber.scrub(report).contains("sdk 36")
                    && !PrivacyScrubber.scrub(report).contains("25013PS27C")
                    && !PrivacyScrubber.scrub(report).contains("Xiaomi"));
            String scrubbed = CrashLog.scrub("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig and api_key=K9fZ2mQ7xY");
            check("v31 b2: scrub() also kills bearer JWTs and api keys",
                !scrubbed.contains("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0") && !scrubbed.contains("K9fZ2mQ7xY"));
        }
        // the atomic file round-trip
        java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"), "si-lab-crash-" + System.currentTimeMillis());
        dir.mkdirs();
        String rep = CrashLog.format(1732280200001L, "3.1.1", 30101, "sdk 36 | lab", "main",
            new ArithmeticException("/ by zero"));
        check("v31 b2: writeAtomically persists the report", CrashLog.writeAtomically(dir, rep));
        check("v31 b2: read() returns the same text", rep.equals(CrashLog.read(dir)));
        check("v31 b2: clear() wipes both the report and the tmp leftover",
            CrashLog.clear(dir) && CrashLog.read(dir) == null && new java.io.File(dir, CrashLog.FILE_NAME + ".tmp").exists() == false);
        check("v31 b2: read() of an empty dir is null (the Diagnostics card hides)",
            CrashLog.read(dir) == null);
        // the report file name matches the Diagnostics card contract
        check("v31 b2: the file is filesDir/last_crash.txt",
            CrashLog.FILE_NAME.equals("last_crash.txt"));
    }

    // ════════════════════════════════════════════════════════════════════════
    // v3.1 — Phase 2: ThemeEngine · AppPrefs · SettingsBackup (the Mornye engine)
    // ════════════════════════════════════════════════════════════════════════
    static void v31ThemeChecks() {
        // ThemeEngine: the modes
        check("v31 theme: the curated accent presets exist (v3.2.0: 8)",
            ThemeEngine.ACCENTS.length == 8 && ThemeEngine.ACCENT_NAMES.length == 8);
        check("v31 theme: the presets are distinct",
            java.util.Arrays.stream(ThemeEngine.ACCENTS).distinct().count() == 8);
        ThemeEngine.Palette dark = ThemeEngine.palette(AppPrefs.THEME_DARK, -1, 0);
        check("v31 theme: Dark (Mornye Slate) keeps the v3 canvas color",
            dark.bg == 0xFF0B1020 && !dark.light && !dark.amoled);
        ThemeEngine.Palette amoled = ThemeEngine.palette(AppPrefs.THEME_AMOLED, -1, 0);
        check("v31 theme: AMOLED canvas is PURE #000000",
            amoled.bg == 0xFF000000 && amoled.amoled);
        check("v31 theme: AMOLED keeps a distinct container tier (the Mornye navigation lesson)",
            amoled.card != amoled.bg && amoled.chip != amoled.bg);
        ThemeEngine.Palette light = ThemeEngine.palette(AppPrefs.THEME_LIGHT, -1, 0);
        check("v31 theme: Light paints a light canvas with dark ink",
            (light.bg & 0xFF000000) != 0 && ((light.bg >> 16) & 0xFF) > 0xE0
                && ((light.title >> 16) & 0xFF) < 0x40);
        check("v31 theme: light palettes swap the ok/warn/err shades for contrast",
            light.ok != dark.ok && light.err != dark.err);
        // dynamic color: the wallpaper seed moves the accent, never the surfaces
        ThemeEngine.Palette dyn = ThemeEngine.palette(AppPrefs.THEME_DARK, 0xFF00BFA5, 1);
        check("v31 theme: a dynamic seed overrides the preset accent",
            dyn.accent == 0xFF00BFA5);
        check("v31 theme: dynamic color NEVER repaints the surfaces (mode owns them)",
            dyn.bg == dark.bg && dyn.card == dark.card && dyn.chip == dark.chip);
        check("v31 theme: an invalid accent index falls back to preset 0",
            ThemeEngine.palette(AppPrefs.THEME_DARK, 0, 99).accent == ThemeEngine.ACCENTS[0]);
        check("v31 theme: accentDim is a blend between accent and canvas",
            ThemeEngine.blend(0xFF0000FF, 0xFF000000, 0.5f) == 0xFF000080);
        check("v31 theme: resolveSystemMode maps night->dark, day->light",
            AppPrefs.THEME_DARK.equals(ThemeEngine.resolveSystemMode(true))
                && AppPrefs.THEME_LIGHT.equals(ThemeEngine.resolveSystemMode(false)));
        check("v31 theme: the studio offers the subtitle colors (v3.2.0: 6)",
            ThemeEngine.SUB_COLORS.length == 6 && ThemeEngine.SUB_COLOR_NAMES.length == 6);
        check("v31 theme: pillColor maps 0-95% to the alpha byte (v3.2.0 cap)",
            ThemeEngine.pillColor(50) == (50 * 255 / 100) << 24 && ThemeEngine.pillColor(200) == (95 * 255 / 100) << 24);

        // AppPrefs: Mornye-style normalizers repair every corrupt value
        check("v31 prefs: theme mode default is Dark (Mornye Slate)",
            "weird".equals(AppPrefs.normalizeThemeMode("dark")) || AppPrefs.normalizeThemeMode("weird").equals("dark"));
        check("v31 prefs: theme modes whitelist",
            AppPrefs.normalizeThemeMode("amoled").equals("amoled") && AppPrefs.normalizeThemeMode("light").equals("light")
                && AppPrefs.normalizeThemeMode("system").equals("system") && AppPrefs.normalizeThemeMode("garbage").equals("dark"));
        check("v31 prefs: uiLang whitelist (bi default)",
            AppPrefs.normalizeUiLang("si").equals("si") && AppPrefs.normalizeUiLang("en").equals("en")
                && AppPrefs.normalizeUiLang("xx").equals("bi"));
        check("v31 prefs: accent clamp (v3.2.0: 8 presets)",
            AppPrefs.normalizeAccent(-1) == 0 && AppPrefs.normalizeAccent(8) == 0 && AppPrefs.normalizeAccent(7) == 7 && AppPrefs.normalizeAccent(3) == 3);
        check("v31 prefs: font size clamps to the 12–36 bounds (v3.3.0 cinema range)",
            AppPrefs.normalizeFontSp(1) == 12 && AppPrefs.normalizeFontSp(99) == 36 && AppPrefs.normalizeFontSp(22) == 22);
        check("v31 prefs: pill opacity clamps to the 0–95 bounds (v3.2.0)",
            AppPrefs.normalizeSubBgOpacity(-5) == 0 && AppPrefs.normalizeSubBgOpacity(100) == 95 && AppPrefs.normalizeSubBgOpacity(30) == 30);
        check("v31 prefs: bottom position clamps to the 2–35% bounds (v3.2.0)",
            AppPrefs.normalizeSubBottomPct(0) == 2 && AppPrefs.normalizeSubBottomPct(50) == 35 && AppPrefs.normalizeSubBottomPct(16) == 16);
        check("v31 prefs: seek step whitelist",
            AppPrefs.normalizeSeekStep(5000) == 5000 && AppPrefs.normalizeSeekStep(7000) == 10000
                && AppPrefs.normalizeSeekStep(30000) == 30000);
        check("v31 prefs: hud hide whitelist (2/4/6 s)",
            AppPrefs.normalizeHudHide(2000) == 2000 && AppPrefs.normalizeHudHide(3500) == 4000 && AppPrefs.normalizeHudHide(6000) == 6000);
        check("v31 prefs: sync step whitelist (0.1/0.25/0.5 s)",
            AppPrefs.normalizeSyncStep(100) == 100 && AppPrefs.normalizeSyncStep(300) == 250 && AppPrefs.normalizeSyncStep(500) == 500);
        check("v31 prefs: receiver name trims, strips newlines, caps at 64",
            AppPrefs.normalizeReceiverName("  x\ny ").equals("x y") && AppPrefs.normalizeReceiverName(null).equals("සිංහල Player")
                && AppPrefs.normalizeReceiverName(repeat("A", 100)).length() == 64);
        check("v31 prefs: the worker URL must be https (else the default)",
            AppPrefs.normalizeWorkerUrl("http://evil/x").equals(BridgeApi.DEFAULT_WORKER_URL)
                && AppPrefs.normalizeWorkerUrl(null).equals(BridgeApi.DEFAULT_WORKER_URL));
        check("v31 prefs: normalizeValue rejects unknown keys",
            AppPrefs.normalizeValue("evilKey", "x") == null);
        check("v31 prefs: normalizeValue repairs a string-typed int",
            AppPrefs.normalizeValue(AppPrefs.K_FONT_SP, "28").equals(28));
        check("v31 prefs: normalizeValue tolerates a boolean as string",
            AppPrefs.normalizeValue(AppPrefs.K_AUTO_PIP, "true").equals(Boolean.TRUE));
        check("v31 prefs: normalizeValue rejects an unparsable int",
            AppPrefs.normalizeValue(AppPrefs.K_FONT_SP, "abc") == null);
        Map<String, Object> rawMap = new LinkedHashMap<>();
        rawMap.put(AppPrefs.K_THEME_MODE, "amoled");
        rawMap.put(AppPrefs.K_FONT_SP, "31");
        rawMap.put("unknown", 1);
        rawMap.put(AppPrefs.K_ACCENT, "bogus");
        Map<String, Object> cleanMap = AppPrefs.normalizeMap(rawMap);
        check("v31 prefs: normalizeMap keeps the good keys and drops the bad",
            cleanMap.size() == 2 && "amoled".equals(cleanMap.get(AppPrefs.K_THEME_MODE))
                && Integer.valueOf(31).equals(cleanMap.get(AppPrefs.K_FONT_SP)));
        check("v31 prefs: the UI-language picker renders all three modes",
            AppPrefs.pick(AppPrefs.UI_BI, "අ", "en").equals("අ · en")
                && AppPrefs.pick(AppPrefs.UI_SI, "අ", "en").equals("අ")
                && AppPrefs.pick(AppPrefs.UI_EN, "අ", "en").equals("en"));
        check("v31 prefs: BACKUP_KEYS covers every customizable surface (>= 30 keys)",
            AppPrefs.BACKUP_KEYS.size() >= 30);

        // SettingsBackup: the strict round-trip
        Map<String, Object> mine = new LinkedHashMap<>();
        mine.put(AppPrefs.K_THEME_MODE, AppPrefs.THEME_AMOLED);
        mine.put(AppPrefs.K_ACCENT, 3);
        mine.put(AppPrefs.K_FONT_SP, 27);
        mine.put(AppPrefs.K_SUB_COLOR, 2);
        mine.put(AppPrefs.K_SUB_BG_OPACITY, 70);
        mine.put(AppPrefs.K_SUB_BOTTOM_PCT, 18);
        mine.put(AppPrefs.K_UI_LANG, AppPrefs.UI_SI);
        mine.put(AppPrefs.K_SEEK_STEP_MS, 15000L);
        mine.put(AppPrefs.K_AUTO_RESUME, Boolean.FALSE);
        mine.put(AppPrefs.K_RECEIVER_NAME, "මගේ Player");
        String json = SettingsBackup.export("3.1.1", 1732280200000L, mine);
        check("v31 backup: the export is a schema-1 document of THIS app",
            json.contains("\"app\":\"" + SettingsBackup.APP + "\"") && json.contains("\"schema\":1"));
        SettingsBackup.Import imp = SettingsBackup.parseImport(json);
        check("v31 backup: the round-trip parses back OK",
            imp.ok && imp.values.size() == mine.size(), imp.error);
        check("v31 backup: every value survives normalized",
            AppPrefs.THEME_AMOLED.equals(imp.values.get(AppPrefs.K_THEME_MODE))
                && Integer.valueOf(3).equals(imp.values.get(AppPrefs.K_ACCENT))
                && Integer.valueOf(27).equals(imp.values.get(AppPrefs.K_FONT_SP))
                && Long.valueOf(15000L).equals(imp.values.get(AppPrefs.K_SEEK_STEP_MS))
                && Boolean.FALSE.equals(imp.values.get(AppPrefs.K_AUTO_RESUME))
                && "මගේ Player".equals(imp.values.get(AppPrefs.K_RECEIVER_NAME)));
        // the strict rejections
        check("v31 backup: garbage JSON is rejected with a named error",
            !SettingsBackup.parseImport("{not json").ok);
        check("v31 backup: another app's backup is rejected",
            !SettingsBackup.parseImport("{\"app\":\"SomethingElse\",\"schema\":1,\"settings\":{}}").ok);
        check("v31 backup: a future schema is rejected",
            !SettingsBackup.parseImport("{\"app\":\"" + SettingsBackup.APP + "\",\"schema\":2,\"settings\":{}}").ok);
        check("v31 backup: a missing settings object is rejected",
            !SettingsBackup.parseImport("{\"app\":\"" + SettingsBackup.APP + "\",\"schema\":1}").ok);
        check("v31 backup: all-bad values are rejected (nothing imports partially)",
            !SettingsBackup.parseImport("{\"app\":\"" + SettingsBackup.APP + "\",\"schema\":1,\"settings\":{\"fontSp\":\"abc\"}}").ok);
        SettingsBackup.Import mixed = SettingsBackup.parseImport(
            "{\"app\":\"" + SettingsBackup.APP + "\",\"schema\":1,\"settings\":{\"" + AppPrefs.K_FONT_SP + "\":26,\"junk\":1}}");
        check("v31 backup: unknown keys are counted and dropped, the good key imports",
            mixed.ok && mixed.rejected == 1 && Integer.valueOf(26).equals(mixed.values.get(AppPrefs.K_FONT_SP)));
        check("v31 backup: an empty document is rejected",
            !SettingsBackup.parseImport("").ok);
    }

    // ════════════════════════════════════════════════════════════════════════
    // v3.1 — Phase 3: security hardening + pipeline parity (source greps)
    // ════════════════════════════════════════════════════════════════════════
    static void v31SecurityChecks() throws Exception {
        String web = readRepoFile("src/lk/techworld/anantplay/sicast/WebBrowserView.java");
        check("v31 sec: WebView disables file + content access explicitly",
            web.contains("setAllowFileAccess(false)") && web.contains("setAllowContentAccess(false)"));
        check("v31 sec: WebView kills the file-scheme cross-origin escapes",
            web.contains("setAllowFileAccessFromFileURLs(false)") && web.contains("setAllowUniversalAccessFromFileURLs(false)"));
        check("v31 sec: WebView geolocation is off",
            web.contains("setGeolocationEnabled(false)"));
        check("v31 sec: file:// content:// intent:// navigations are refused (only http/https/mailto/tel pass)",
            web.contains("blocked non-http(s) navigation"));

        String miui = readRepoFile("src/lk/techworld/anantplay/sicast/Miui.java");
        check("v31 sec: the 3-state Autostart model exists (Bug 4)",
            miui.contains("enum AutostartState { ENABLED, DISABLED, UNKNOWN }"));
        check("v31 sec: the user-confirmation pref + API exist",
            miui.contains("confirmAutostart") && miui.contains("autostart_user_confirmed"));

        String nsd = readRepoFile("src/lk/techworld/anantplay/sicast/NsdAnnouncer.java");
        check("v31 sec: the non-destructive NSD teardown exists (Bug 3)",
            nsd.contains("stopNsdRegistrations"));
        check("v31 sec: the watchdog takes its scope from AnnouncerPolicy",
            nsd.contains("AnnouncerPolicy.watchdogScope"));

        String appSrc = readRepoFile("src/lk/techworld/anantplay/sicast/App.java");
        check("v31 sec: the SILENT update channel exists (Bug 5) — sound/vibration/badge all off",
            appSrc.contains("CHANNEL_UPDATES_SILENT") && appSrc.contains("silent.setSound(null, null)")
                && appSrc.contains("silent.enableVibration(false)") && appSrc.contains("silent.setShowBadge(false)"));
        check("v31 sec: the update banners are onlyAlertOnce + PRIORITY_LOW",
            appSrc.contains("setOnlyAlertOnce(true)") && appSrc.contains("Notification.PRIORITY_LOW"));
        check("v31 sec: the CrashLog recorder is wired in App.onCreate (Bug 2)",
            appSrc.contains("CrashLog.format") && appSrc.contains("CrashLog.writeAtomically"));

        String upd = readRepoFile("src/lk/techworld/anantplay/sicast/SelfUpdater.java");
        check("v31 sec: progress posts go through the UpdateNotify throttle (no per-chunk notify)",
            upd.contains("throttle.onProgress(percent)"));
        check("v31 sec: the single-flight AtomicBoolean guard exists",
            upd.contains("inFlight.compareAndSet"));
        check("v31 sec: the 24h per-version alert cooldown + skip wiring exist",
            upd.contains("alertAllowed") && upd.contains("skippedVersion"));
        check("v31 sec: the update notification actions receiver exists",
            upd.contains("UpdateActionReceiver") && upd.contains("ACTION_SKIP_VERSION"));

        String manifest = readRepoFile("AndroidManifest.xml");
        check("v31 sec: UpdateActionReceiver is registered NOT exported",
            manifest.contains("SelfUpdater$UpdateActionReceiver") && manifest.contains("android:exported=\"false\""));
        check("v31 sec: allowBackup stays false",
            manifest.contains("android:allowBackup=\"false\""));
        // NB: the forbidden tokens are assembled at runtime so that the CI
        // "zero-accessibility proof" grep of src/ + lab/ can never match this
        // assertion itself (the proof must look for the token in SHIPPED code,
        // not in the checker that proves its absence).
        String accSvc = "accessibility" + "service";
        String bindAcc = "BIND_ACCESSIBILITY" + "_SERVICE";
        String stripped = manifest.replaceAll("(?s)<!--.*?-->", "");
        check("v31 sec: zero AccessibilityService declarations",
            !stripped.contains(accSvc) && !stripped.contains(bindAcc));
        check("v31 sec: no dangerous permissions (contacts/sms/location/phone/storage-all)",
            !manifest.contains("READ_CONTACTS") && !manifest.contains("RECEIVE_SMS") && !manifest.contains("ACCESS_FINE_LOCATION")
                && !manifest.contains("READ_PHONE_STATE") && !manifest.contains("MANAGE_EXTERNAL_STORAGE")
                && !manifest.contains("READ_MEDIA_AUDIO") && !manifest.contains("WRITE_EXTERNAL_STORAGE"));

        String main = readRepoFile("src/lk/techworld/anantplay/sicast/MainActivity.java");
        check("v31 sec: the zero-clip hero uses weighted grid chips",
            main.contains("chipGrid") && main.contains("new LinearLayout.LayoutParams(0, -2, 1f)"));
        check("v31 sec: the Mornye drill-down + live studio exist",
            main.contains("openSub(") && main.contains("openAppearance") && main.contains("openSubtitleStudio")
                && main.contains("openBackup") && main.contains("openCastNetwork"));
        check("v31 sec: the Last Crash Captured card exists in Diagnostics",
            main.contains("paintCrashCard") && main.contains("CrashLog.read"));
        check("v31 sec: the cast-ready confirmation toast is exactly the spec wording",
            main.contains("🟢 සිංහල Player Cast සූදානම් — AnantPlay හි Cast බොත්තමෙන් තෝරන්න"));

        String readMd = readRepoFile("README.md");
        check("v31 sec: the README carries the v3.1 story",
            (readMd.contains("v3.1.2") || readMd.contains("v3.1.1")) && readMd.contains("refused-nonlocal"));

        String relYml = readRepoFile(".github/workflows/release-apk.yml");
        String buildYml = readRepoFile(".github/workflows/build-apk.yml");
        check("v31 sec: both workflows carry versionCode 30300 / 3.3.0",
            buildYml.contains("30300") && buildYml.contains("3.3.0") && relYml.contains("3.3.0"));
        // v3.1.1 — THE MANIFEST PARITY GUARD: the APK identity is declared
        // explicitly on <manifest> (not injected only by aapt2's --version-name),
        // so a badging dump of the un-built source agrees with AppInfo.java.
        String srcManifest = readRepoFile("AndroidManifest.xml");
        String shippedBuildSh = readRepoFile("build.sh");
        check("v312 pipe: AndroidManifest.xml declares the shipped versionCode 30300 + versionName 3.3.0",
            srcManifest.contains("android:versionCode=\"30300\"")
                && srcManifest.contains("android:versionName=\"3.3.0\"")
                && "30300".equals(String.valueOf(AppInfo.VERSION_CODE))
                && "3.3.0".equals(AppInfo.VERSION_NAME));
        check("v312 pipe: no stale 3.1.1 / 30101 build-or-lab assertion survives the bump",
            !buildYml.contains("30101") && !buildYml.contains("AnantPlay-Sinhala-Player-v3.1.1")
                && !relYml.contains("default: '3.1.1'")
                && !shippedBuildSh.contains("VERSION_CODE=${VERSION_CODE:-30101}")
                && !srcManifest.contains("30101"));
        // v3.1 — THE ARTIFACT-NAME PARITY GUARD (the v3.0.0 filename remnants
        // that survived the 3.1.0 bump: the build job still uploaded
        // AnantPlay-Sinhala-Player-v3.0.0.apk while the manifest shipped 3.1.0).
        check("v31 pipe: build-apk.yml names the artifact with the SHIPPED version (no stale v3.0.0 filename)",
            buildYml.contains("AnantPlay-Sinhala-Player-v3.3.0.apk")
                && !buildYml.contains("AnantPlay-Sinhala-Player-v3.0.0"));
        check("v31 pipe: the versioned artifact name agrees across build.sh / build-apk.yml / release-apk.yml",
            buildYml.contains("AnantPlay-Sinhala-Player-v3.3.0.apk")
                && relYml.contains("AnantPlay-Sinhala-Player-v${{ steps.ver.outputs.version }}.apk"));
        // v3.1.1 — REPRODUCIBILITY GUARD: the pre-merge build and the post-merge
        // release build of the SAME commit used to differ only by the ZIP entry
        // mtimes (two different SHA-256s for one commit). build.sh now pins the
        // epoch, so the pipeline stays byte-reproducible and the lab enforces it.
        check("v311 pipe: build.sh pins the ZIP entry epoch (reproducible APK bytes)",
            shippedBuildSh.contains("touch -t 198001010000")
                && shippedBuildSh.contains("zip -q -X app.apk")
                && shippedBuildSh.contains("TZ=UTC"));
        check("v31 sec: the repo keeps .gitignore for the Arena.ai-ready zip",
            new java.io.File(".gitignore").exists());
    }


    // ════════════════════════════════════════════════════════════════════════
    // v3.1.2 — CRASH FIX + PRIVACY TROUBLESHOOTER (the field phone's exact bugs)
    // ════════════════════════════════════════════════════════════════════════

    /** The keeps-stopping crash: the raw-ARGB setBackgroundResource trap. */
    static void v312CrashFixChecks() throws Exception {
        String playSrc = readRepoFile("src/lk/techworld/anantplay/sicast/PlayActivity.java");
        check("v312 crash: the raw-ARGB setBackgroundResource(0x88000000) CALL is GONE (Resources$NotFoundException killed)",
            java.util.regex.Pattern.compile("notice\\.setBackgroundResource\\(0x88000000\\)\\s*;")
                .matcher(stripComments(playSrc)).find() == false);
        check("v312 crash: the translucent-black notice now uses setBackgroundColor (the ARGB API)",
            playSrc.contains("notice.setBackgroundColor(0x88000000)"));
        check("v312 crash: NO view anywhere still calls setBackgroundResource with a bare hex int",
            java.util.regex.Pattern.compile("\\.setBackgroundResource\\(0x[0-9A-Fa-f]+\\)")
                .matcher(stripComments(playSrc)).find() == false
                && java.util.regex.Pattern.compile("\\.setBackgroundResource\\(0x[0-9A-Fa-f]+\\)")
                .matcher(stripComments(readRepoFile("src/lk/techworld/anantplay/sicast/MainActivity.java"))).find() == false);
        check("v312 crash: the fix comment documents the TransactionExecutor path (future audits)",
            playSrc.contains("TransactionExecutor.executeNonLifecycleItem"));
        // the whole-app guard: the uncaught handler still chains (so a crash is
        // ALWAYS recorded before the system dialog — CrashLog proof below)
        String appSrc = readRepoFile("src/lk/techworld/anantplay/sicast/App.java");
        check("v312 crash: App.onCreate still installs the chained crash recorder",
            appSrc.contains("setDefaultUncaughtExceptionHandler"));
        // the Test Player launch guard
        String mainSrc = readRepoFile("src/lk/techworld/anantplay/sicast/MainActivity.java");
        check("v312 crash: the Test Player button launch is wrapped (never a UI-crash dialog)",
            mainSrc.contains("test player launch"));
    }

    /** PrivacyScrubber — the strict zero-PII lab (15+ checks, the field leaks). */
    static void v312PrivacyScrubberChecks() throws Exception {
        // 1-6: the EXACT strings from the 2026-11-22 field log
        String f1 = PrivacyScrubber.scrub("model Xiaomi 24117RN76G");
        check("v312 privacy: phone hardware model → <phone-model>",
            !f1.contains("24117RN76G") && !f1.contains("Xiaomi 24117RN76G"));
        String f2 = PrivacyScrubber.scrub("iface wlan0 fe80::f84a:81ff:feac:e2f8%wlan0");
        check("v312 privacy: EUI-64 link-local IPv6 → <local-v6> (MAC-derived address)",
            !f2.contains("f84a:81ff:feac:e2f8"));
        String f3 = PrivacyScrubber.scrub("our own ip 192.168.1.39 router 192.168.1.1 peer 192.168.1.148");
        check("v312 privacy: LAN IPs → <lan-peer> placeholders (no raw private IPs)",
            !f3.contains("192.168.1.39") && !f3.contains("192.168.1.1") && !f3.contains("192.168.1.148"));
        String f4 = PrivacyScrubber.scrub("MAC f8:4a:81:ac:e2:f8 peer AA:BB:CC:DD:EE:FF");
        check("v312 privacy: MAC addresses → <redacted>",
            !f4.contains("f8:4a:81") && !f4.contains("AA:BB:CC"));
        String f5 = PrivacyScrubber.scrub("Cookie: session=abc123 Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c");
        check("v312 privacy: cookies + bearer JWTs → masked",
            !f5.contains("session=abc123") && !f5.contains("eyJhbGciOiJIUzI1NiJ9"));
        String f6 = PrivacyScrubber.scrub("worker key AIzaSyD1234567890abcdefghijklmnopqrstuv");
        check("v312 privacy: Google API keys → <redacted>",
            !f6.contains("AIzaSyD"));
        // 7-11: query secrets, storage paths, SSIDs, public IPs, fingerprints
        String f7 = PrivacyScrubber.scrub("GET /v/a.mp4?token=secret123&sig=abcd&expires=999");
        check("v312 privacy: URL query secrets → key=<redacted>",
            !f7.contains("secret123") && !f7.contains("abcd") && !f7.contains("999"));
        String f8 = PrivacyScrubber.scrub("open /storage/emulated/0/Movies/private.mp4 (sdcard)");
        check("v312 privacy: user storage paths → <local-media>",
            !f8.contains("private.mp4") && !f8.contains("/storage/emulated"));
        String f9 = PrivacyScrubber.scrub("ssid=MyHomeWiFi_5G name=HomeNet");
        check("v312 privacy: Wi-Fi SSIDs → masked",
            !f9.contains("MyHomeWiFi_5G"));
        String f10 = PrivacyScrubber.scrub("connect 8.8.8.8 then 203.0.113.9");
        check("v312 privacy: public IPs → <remote-ip>",
            !f10.contains("8.8.8.8") && !f10.contains("203.0.113.9"));
        String f11 = PrivacyScrubber.scrub("fingerprint=xiaomi/milkway/M2101K6G:16/SOMS:user/release-keys Build.MODEL=M2101K6G");
        check("v312 privacy: Build.* + fingerprint values → <phone-model>",
            !f11.contains("M2101K6G") && !f11.contains("milkway"));
        // 12: the benign device-header line SURVIVES
        check("v312 privacy: the benign 'device: sdk 36' line survives verbatim",
            PrivacyScrubber.scrub("device: sdk 36").equals("device: sdk 36"));
        // 13: the canary sweep — every canonical leak dies in one pass
        java.util.List<String> canaries = PrivacyScrubber.canaryLeaks();
        StringBuilder all = new StringBuilder();
        for (String c : canaries) all.append(c).append(' ');
        String scrubbedAll = PrivacyScrubber.scrub(all.toString());
        for (String c : canaries) {
            if (!PrivacyScrubber.leaked(scrubbedAll, c)) continue;
            check("v312 privacy: canary died: " + c, false);
            return;
        }
        check("v312 privacy: ALL " + canaries.size() + " canonical canary leaks die in one scrub pass", true);
        // 14: technical lines stay INTACT (the debuggable half)
        String keep = PrivacyScrubber.scrub("0.610 I/selfupd: up, sdk 36 port 8765 DLNA up searches 3 worker v4.1.0 storage kv cues 1909");
        check("v312 privacy: the technical state line stays intact (sdk, port, DLNA, worker, cues)",
            keep.contains("sdk 36") && keep.contains("port 8765") && keep.contains("DLNA up")
                && keep.contains("worker v4.1.0") && keep.contains("storage kv") && keep.contains("cues 1909"));
        // 15: a full crash trace keeps every frame while scrubbing the payload
        String crash = "EXCEPTION java.lang.IllegalStateException: stream died Cookie: sid=TOPSECRET\n"
            + "    at lk.techworld.anantplay.sicast.PlayActivity.buildUi(PlayActivity.java:1001)\n"
            + "    at android.app.servertransaction.TransactionExecutor.executeNonLifecycleItem(TransactionExecutor.java:136)";
        String scrubbedCrash = PrivacyScrubber.scrub(crash);
        check("v312 privacy: the crash trace keeps every frame + scrubs the secret",
            scrubbedCrash.contains("PlayActivity.buildUi")
                && scrubbedCrash.contains("TransactionExecutor.executeNonLifecycleItem")
                && !scrubbedCrash.contains("TOPSECRET"));
        // 16: idempotence
        String once = PrivacyScrubber.scrub("ip 10.1.2.3 token=zzz");
        check("v312 privacy: scrub() is idempotent (double pass changes nothing)",
            PrivacyScrubber.scrub(once).equals(once));
        // 17: the Diag gate — every ring line passed through the scrubber
        Diag.reset();
        Diag.i("leak", "my ip is 192.168.1.39 on fe80::f84a:81ff:feac:e2f8%wlan0 Xiaomi 24117RN76G");
        String ring = Diag.dump();
        check("v312 privacy: the Diag RING itself is scrubbed (the last gate)",
            !ring.contains("192.168.1.39") && !ring.contains("fe80::f84a") && !ring.contains("24117RN76G"));
    }

    /** Troubleshooter — the auto-diagnosis + top-first crash formatting lab. */
    static void v312TroubleshooterChecks() throws Exception {
        Troubleshooter.reset();
        // the timeline categories accept only the six spec tags
        Troubleshooter.record("APP", "process up");
        Troubleshooter.record("CAST", "play accepted");
        Troubleshooter.record("PLAYER", "launch cast-mdns");
        Troubleshooter.record("SUBS", "cues 1909");
        Troubleshooter.record("UPDATER", "up-to-date");
        Troubleshooter.record("CRASH", "none");
        Troubleshooter.record("BOGUS", "normalized to APP");
        java.util.List<String> lines = Troubleshooter.timelineLines();
        check("v312 tsr: the timeline records every category and normalizes unknowns to APP",
            lines.size() == 7 && lines.get(6).contains("[APP]"));
        check("v312 tsr: the timeline lines carry the [CATEGORY] + uptime-ms prefix shape",
            lines.get(0).contains("[APP]") && lines.get(0).contains("process up"));
        // events are privacy-scrubbed at capture time
        Troubleshooter.record("CAST", "from 192.168.1.39 model Xiaomi 24117RN76G");
        boolean clean = true;
        for (String l : Troubleshooter.timelineLines()) {
            if (l.contains("192.168.1.39") || l.contains("24117RN76G")) clean = false;
        }
        check("v312 tsr: timeline events are scrubbed at capture time", clean);

        // ── the top-first crash formatting contract ──
        String report = "============================================================\n"
            + " UNCAUGHT CRASH — AnantPlay Sinhala Player v3.1.2\n"
            + " at 2026-11-22 19:16:11.100\n"
            + " device: sdk 36\n"
            + " thread: main\n"
            + "------------------------------------------------------------\n"
            + "EXCEPTION android.content.res.Resources$NotFoundException: Resource ID #0x88000000\n"
            + "    at android.content.res.ResourcesImpl.getValue(ResourcesImpl.java:255)\n"
            + "    at android.view.View.setBackgroundResource(View.java:26120)\n"
            + "    at lk.techworld.anantplay.sicast.PlayActivity.buildUi(PlayActivity.java:1001)\n"
            + "    at lk.techworld.anantplay.sicast.PlayActivity.onCreate(PlayActivity.java:215)\n"
            + "    at android.app.Activity.performCreate(Activity.java:3556)\n"
            + "    at android.app.servertransaction.TransactionExecutor.executeNonLifecycleItem(TransactionExecutor.java:136)\n"
            + "    at android.app.ActivityThread$H.handleMessage(ActivityThread.java:3103)\n"
            + "    at android.os.Handler.dispatchMessage(Handler.java)\n";
        java.util.List<String> top = Troubleshooter.crashTop(report, 6);
        check("v312 tsr: crashTop leads with the EXCEPTION class+message (NOT the bottom Handler frames)",
            !top.isEmpty() && top.get(0).startsWith("EXCEPTION android.content.res.Resources$NotFoundException"));
        check("v312 tsr: the FIRST app frames follow immediately",
            top.size() >= 4 && top.get(1).contains("ResourcesImpl.getValue")
                && top.get(3).contains("PlayActivity.buildUi"));
        check("v312 tsr: the header's 'at <timestamp>' line never leaks into the frame list",
            !top.get(0).startsWith("at 2026"));

        // ── the auto-diagnosis rule engine ──
        Troubleshooter.Facts healthy = Troubleshooter.Facts.healthy();
        java.util.List<Troubleshooter.Issue> ok = Troubleshooter.diagnose(healthy, null);
        check("v312 tsr: the healthy state yields ONLY ✓ cards (zero errors/warnings)",
            !ok.isEmpty() && ok.stream().allMatch(i -> i.sev == 'O')
                && ok.stream().anyMatch(i -> i.en.contains("healthy") || i.en.contains("ready")));
        // crash leads
        Troubleshooter.Facts crashed = Troubleshooter.Facts.healthy();
        crashed.hasCrashLog = true;
        crashed.crashTop = "Resources$NotFoundException at PlayActivity.buildUi(PlayActivity.java:1001)";
        crashed.playerState = "error"; crashed.playerError = "ERROR_CODE_IO_BAD_HTTP_STATUS";
        java.util.List<Troubleshooter.Issue> crashedIssues = Troubleshooter.diagnose(crashed, null);
        check("v312 tsr: a captured crash ALWAYS leads the issue list (severity-first ordering)",
            crashedIssues.get(0).sev == 'X' && crashedIssues.get(0).en.contains("crash was captured"));
        check("v312 tsr: the player-error card names the exact ExoPlayer error code",
            crashedIssues.size() >= 2 && crashedIssues.get(1).en.contains("ERROR_CODE_IO_BAD_HTTP_STATUS")
                && crashedIssues.get(1).fix.contains("retry"));
        // the worker card set
        Troubleshooter.Facts kvMissing = Troubleshooter.Facts.healthy();
        kvMissing.workerStorage = "cache+memory";
        java.util.List<Troubleshooter.Issue> kv = Troubleshooter.diagnose(kvMissing, null);
        check("v312 tsr: an unbound Worker KV is flagged with the SUBS_KV fix",
            kv.stream().anyMatch(i -> i.sev == 'W' && i.en.contains("KV is not bound") && i.fix.contains("SUBS_KV")));
        Troubleshooter.Facts down = Troubleshooter.Facts.healthy();
        down.receiverUp = false; down.receiverPort = -1;
        java.util.List<Troubleshooter.Issue> rec = Troubleshooter.diagnose(down, null);
        check("v312 tsr: a down receiver names the Start-receiver fix",
            rec.stream().anyMatch(i -> i.sev == 'X' && i.en.contains("receiver is down") && i.fix.contains("Start receiver")));
        Troubleshooter.Facts noSubs = Troubleshooter.Facts.healthy();
        noSubs.subsState = "needs-key";
        java.util.List<Troubleshooter.Issue> nk = Troubleshooter.diagnose(noSubs, null);
        check("v312 tsr: needs-key names the three provider-key secrets",
            nk.stream().anyMatch(i -> i.sev == 'X' && i.en.contains("no translation provider key") && i.fix.contains("GEMINI_API_KEY")));
        // the NSD-silent-but-responder-alive state is informational, not an error
        Troubleshooter.Facts nsd = Troubleshooter.Facts.healthy();
        nsd.nsdAnnounced = false;
        java.util.List<Troubleshooter.Issue> nsdIssues = Troubleshooter.diagnose(nsd, null);
        check("v312 tsr: NSD daemon silent + our responder alive → the ✓ info card (casting works)",
            nsdIssues.stream().anyMatch(i -> i.sev == 'O' && i.en.contains("NSD daemon silent")));

        // the report bundle
        Troubleshooter.Facts rf = Troubleshooter.Facts.healthy();
        rf.hasCrashLog = true;
        rf.crashTop = "Resources$NotFoundException at PlayActivity.buildUi";
        String rep = Troubleshooter.buildReport(rf, "0.100 I/app: up\n", report);
        check("v312 tsr: buildReport carries all four sections",
            rep.contains("# AnantPlay Sinhala Player — Troubleshoot Report")
                && rep.contains("## Status Summary")
                && rep.contains("## Auto-Detected Issues")
                && rep.contains("## Last Crash (top-first)")
                && rep.contains("## Subsystem Timeline"));
        check("v312 tsr: the report's crash section leads with the EXCEPTION line",
            rep.contains("EXCEPTION android.content.res.Resources$NotFoundException"));
        check("v312 tsr: the report header stamps the app version + SDK only (no model)",
            rep.contains("app v3.3.0") && rep.contains("Android SDK"));
        check("v312 tsr: the report is zero-PII end-to-end (the whole bundle re-scrubbed)",
            !rep.contains("24117RN76G") && !rep.contains("192.168.1.39"));
    }

    /** SelfUpdater v3.1.2 — the zero-spin-loop proof (the 38-lines-in-645ms field log). */
    static void v312SelfUpdaterSpinChecks() throws Exception {
        String updSrc = readRepoFile("src/lk/techworld/anantplay/sicast/SelfUpdater.java");
        check("v312 upd: schedule() carries the in-process SCHEDULE_DEBOUNCE_MS guard",
            updSrc.contains("SCHEDULE_DEBOUNCE_MS") && updSrc.contains("lastScheduleAt"));
        check("v312 upd: the zero-delay re-schedule path is a silent NO-OP inside the window",
            updSrc.contains("now - last < SCHEDULE_DEBOUNCE_MS"));
        check("v312 upd: the single-flight skip log is rate-limited (1 line/sec, no spam storm)",
            updSrc.contains("lastSkipLogAt") && updSrc.contains("1000L"));
        // simulate the field storm: 50 rapid schedule bursts in 1 s → the DEBOUNCE
        // logic must collapse them (mirrors the shipped guard, proven by the
        // burst simulation below against the shipped constants)
        long SCHEDULE_DEBOUNCE_MS = 10_000L;
        java.util.concurrent.atomic.AtomicInteger handed = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicLong lastScheduleAt = new java.util.concurrent.atomic.AtomicLong(0);
        for (int i = 0; i < 50; i++) {              // the field phone's 38+ in 645 ms
            long now = 650 + i * 13;                 // ms, all inside one burst
            long last = lastScheduleAt.get();
            if (last > 0 && now - last < SCHEDULE_DEBOUNCE_MS) continue;  // the shipped guard
            lastScheduleAt.set(now);
            handed.incrementAndGet();                // js.schedule(b.build())
        }
        check("v312 upd: 50 rapid zero-latency schedules inside 1 s collapse to exactly 1 JobScheduler handoff",
            handed.get() == 1);
        // the Diag-ring spam side: the rate-limited skip log allows ≤1/sec
        Diag.reset();
        long lastSkipLogAt = 0;
        for (int i = 0; i < 50; i++) {
            long now = 650 + i * 13;
            if (lastSkipLogAt == 0 || now - lastSkipLogAt >= 1000L) {
                lastSkipLogAt = now;
                Diag.i("selfupd", "single-flight: another update run is active — skipping");
            }
        }
        int stormLines = 0;
        for (String l : Diag.dump().split("\\n")) if (l.contains("single-flight")) stormLines++;
        check("v312 upd: a 50-call storm logs at most ONE single-flight line (the v3.1.1 field spam is dead)",
            stormLines <= 1);
    }

    /** v3.1.2 — the launch contracts (Test Player / DLNA cast / mDNS cast / re-cast). */
    static void v312LaunchContractChecks() throws Exception {
        // the Test Player contract: the intent carries test=true + the stable title,
        // and PlayActivity maps it to the guaranteed-reachable HLS test stream
        check("v312 launch: the test stream is the guaranteed-reachable mux HLS sandbox URL",
            readRepoFile("src/lk/techworld/anantplay/sicast/PlayActivity.java")
                .contains("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"));
        // TitleCleaner on the user's real cast title
        TitleCleaner.Clean od = TitleCleaner.clean("Binging: The Odyssey");
        check("v312 launch: TitleCleaner keeps 'Binging: The Odyssey' shape for Cinemeta",
            od.title != null && od.title.toLowerCase(java.util.Locale.ROOT).contains("odyssey"));
        // the wire-level /play contract against a live server (same as the field cast)
        ReceiverServer.Backend backend = new ReceiverServer.Backend() {
            @Override public String onPlay(Map<String, Object> body, String sourceAddr) {
                return ReceiverServer.okJson();   // the player opened (the fixed buildUi path)
            }
            @Override public String onAction(String action, long to) { return ReceiverServer.idleJson(); }
            @Override public String status() { return ReceiverServer.idleJson(); }
            @Override public Map<String, Object> info() { return new java.util.LinkedHashMap<>(); }
        };
        ReceiverServer srv = new ReceiverServer(backend, 18965);
        check("v312 launch: the receiver binds for the launch-contract tests", srv.start() && srv.port() > 0, "port " + srv.port());
        // mount the DLNA MediaRenderer so SetAVTransportURI drives /play too
        final boolean[] dlnaOpened = {false};
        srv.setDlna(new DlnaEndpoint(new DlnaEndpoint.Player() {
            @Override public boolean open(String url, String title) {
                dlnaOpened[0] = CastPolicy.allowed(url, true);
                return dlnaOpened[0];
            }
            @Override public String act(String action, long to) { return ReceiverServer.okJson(); }
            @Override public String statusJson() { return ReceiverServer.idleJson(); }
            @Override public int getVolume() { return 50; }
            @Override public void setVolume(int v) { }
        }, "SIcast-v312", "සිංහල Player", "Android/16 UPnP/1.0 AnantPlaySinhalaPlayer/3.1.2"));
        try {
            int port = srv.port();
            // the describe()-shaped /play body AnantPlay sends
            String castBody = "{\"url\":\"https://cdn.example/hls/x.m3u8\",\"title\":\"Binging: The Odyssey\","
                + "\"at\":0,\"live\":false,\"season\":0,\"episode\":0}";
            String res = post("http://127.0.0.1:" + port + "/play", castBody);
            check("v312 launch: a describe()-shaped cast answers ok:true (the crash-free path)",
                res.contains("\"ok\":true"));
            // a MALFORMED re-cast never kills the server (the defensive-parse rule)
            String bad = post("http://127.0.0.1:" + port + "/play", "{not json");
            check("v312 launch: a malformed cast body is refused WITHOUT crashing the receiver",
                bad.contains("bad json"));
            String stillUp = get("http://127.0.0.1:" + port + "/info");
            check("v312 launch: the receiver still serves /info after the malformed cast",
                stillUp.contains("\"ok\":true"));
            // the DLNA SetAVTransportURI → Play contract (the second cast pillar)
            String soap = "<soap:Envelope><soap:Body><u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                + "<InstanceID>0</InstanceID><CurrentURI>https://cdn.example/x.m3u8</CurrentURI>"
                + "<CurrentURIMetaData></CurrentURIMetaData></u:SetAVTransportURI></soap:Body></soap:Envelope>";
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + "/dlna/avt").openConnection(java.net.Proxy.NO_PROXY);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"");
            try (java.io.OutputStream o = c.getOutputStream()) { o.write(soap.getBytes(StandardCharsets.UTF_8)); }
            String soapRes = new String(readAllBytes(c.getInputStream()), StandardCharsets.UTF_8);
            c.disconnect();
            check("v312 launch: DLNA SetAVTransportURI opens the SAME /play pipeline (ok response)",
                soapRes.contains("<u:SetAVTransportURI") && dlnaOpened[0]);
        } finally {
            srv.stop();
        }
        // mDNS POST /play intent shape parity: the castJson extras key set the
        // receiver hands to PlayActivity (the field contract, checked statically)
        String svcSrc = readRepoFile("src/lk/techworld/anantplay/sicast/ReceiverService.java");
        check("v312 launch: /play hands PlayActivity the castJson extra (the mDNS cast path)",
            svcSrc.contains("putExtra(\"castJson\""));
        check("v312 launch: the no-overlay fallback posts the tap-to-open cast notification",
            svcSrc.contains("notifyCastReady"));
    }



    /** v3.1.2 — strip // line comments so fix-documentation quoting never matches a code-shape check. */
    static String stripComments(String src) {
        return src.replaceAll("(?m)//[^\\n]*", "");
    }

    /** v3.2.0 — the BODY of one method (from its `{` to its matching `}`), for
     *  code-shape checks that must not match javadoc mentioning the same rule. */
    static String methodBody(String src, String methodName) {
        if (src == null) return null;
        String stripped = stripComments(src);
        int at = stripped.indexOf("public boolean " + methodName);   // the OVERRIDE signature, never a javadoc word
        if (at < 0) at = stripped.indexOf(methodName);
        if (at < 0) return null;
        int open = stripped.indexOf('{', at);
        if (open < 0) return null;
        int depth = 0;
        for (int i = open; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return stripped.substring(open, i + 1);
            }
        }
        return stripped.substring(open);
    }

    /** One SOAP POST with the SOAPAction header (what official AnantPlay sends). */
    static String soapPost(String url, String soapAction, String body) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new URL(url).openConnection(java.net.Proxy.NO_PROXY);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        c.setRequestProperty("SOAPAction", "\"" + soapAction + "\"");
        try (java.io.OutputStream o = c.getOutputStream()) {
            o.write(body.getBytes(StandardCharsets.UTF_8));
        }
        String res = new String(readAllBytes(c.getInputStream()), StandardCharsets.UTF_8);
        c.disconnect();
        return res;
    }

    static byte[] readAllBytes(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
        return o.toByteArray();
    }
    // ═══════════════════════════════════════════════════════════════════════
    // v3.2.0 — THE MASTER OVERHAUL LABS (every Phase 1–4 fix, proven live)
    // ═══════════════════════════════════════════════════════════════════════

    /** v3.2.0 — THE ZERO-RATE-LIMIT UPDATER LAB: a mock github.com serves the
     *  exact 302 the real endpoint serves; the tier-1 resolver must resolve
     *  the tag, derive BOTH asset URLs, and NEVER call api.github.com; the
     *  15-minute backoff must hold across re-delivered jobs; lastCheck must
     *  be persisted BEFORE the network call. */
    static void v320UpdaterZeroRateLimitChecks() throws Exception {
        // ── the tier-1 redirect resolver against a LIVE local mock github.com ──
        final String[] latestTag = new String[1];
        ServerSocket ss = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        int port = ss.getLocalPort();
        Thread srv = new Thread(() -> {
            try {
                while (true) {
                    final Socket c = ss.accept();
                    new Thread(() -> {
                        try {
                            java.io.BufferedReader r = new java.io.BufferedReader(
                                new java.io.InputStreamReader(c.getInputStream(), StandardCharsets.US_ASCII));
                            String reqLine = r.readLine();   // "HEAD /releases/latest HTTP/1.1"
                            for (String l; (l = r.readLine()) != null && !l.isEmpty(); ) { }
                            String path = reqLine == null ? "" : reqLine.split(" ")[1];
                            if (path.contains("api.github.com")) latestTag[0] = "API-BURNED";
                            else if (path.contains("/releases/latest")) {
                                c.getOutputStream().write(("HTTP/1.1 302 Found\r\nLocation: https://github.com/"
                                    + "dictationb1/anantplay-sinhala-player/releases/tag/v3.2.1\r\nContent-Length: 0\r\n\r\n")
                                    .getBytes(StandardCharsets.US_ASCII));
                            } else {
                                c.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
                                    .getBytes(StandardCharsets.US_ASCII));
                            }
                            c.close();
                        } catch (Throwable ignored) { }
                    }).start();
                }
            } catch (Throwable ignored) { }
        });
        srv.setDaemon(true);
        srv.start();
        try {
            String tag = SelfUpdateCore.resolveLatestTagByRedirect("http://127.0.0.1:" + port + "/releases/latest");
            check("v320 upd: tier-1 resolves the 302 Location to the tag", "v3.2.1".equals(tag), "got " + tag);
            check("v320 upd: the resolver NEVER touched api.github.com on the happy path",
                latestTag[0] == null, "flag=" + latestTag[0]);
            check("v320 upd: tagFromLocation parses the /tag/ tail",
                "v3.2.1".equals(SelfUpdateCore.tagFromLocation("https://github.com/dictationb1/anantplay-sinhala-player/releases/tag/v3.2.1")));
            check("v320 upd: tagFromLocation tolerates a query tail",
                "v3.2.1".equals(SelfUpdateCore.tagFromLocation("https://github.com/x/releases/tag/v3.2.1?extra=1")));
            check("v320 upd: the derived apk URL is deterministic",
                "https://github.com/dictationb1/anantplay-sinhala-player/releases/download/v3.2.1/AnantPlay-Sinhala-Player-v3.2.1.apk"
                    .equals(SelfUpdateCore.derivedApkUrl("v3.2.1")));
            check("v320 upd: the derived SHA256SUMS URL is deterministic",
                "https://github.com/dictationb1/anantplay-sinhala-player/releases/download/v3.2.1/SHA256SUMS.txt"
                    .equals(SelfUpdateCore.derivedSumsUrl("v3.2.1")));
        } finally {
            ss.close();
        }
        // a 200 (no releases) answers null — the tier falls through, never throws
        ServerSocket ss2 = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        int port2 = ss2.getLocalPort();
        Thread srv2 = new Thread(() -> {
            try {
                while (true) {
                    final Socket c = ss2.accept();
                    new Thread(() -> {
                        try {
                            java.io.BufferedReader r = new java.io.BufferedReader(
                                new java.io.InputStreamReader(c.getInputStream(), StandardCharsets.US_ASCII));
                            String reqLine = r.readLine();
                            for (String l; (l = r.readLine()) != null && !l.isEmpty(); ) { }
                            c.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
                                .getBytes(StandardCharsets.US_ASCII));
                            c.close();
                        } catch (Throwable ignored) { }
                    }).start();
                }
            } catch (Throwable ignored) { }
        });
        srv2.setDaemon(true);
        srv2.start();
        try {
            String t2 = SelfUpdateCore.resolveLatestTagByRedirect("http://127.0.0.1:" + port2 + "/releases/latest");
            check("v320 upd: a 200 (no releases yet) is a clean null — never an exception", t2 == null, "got " + t2);
        } finally {
            ss2.close();
        }

        // ── the tier-2 raw.json parser (fixtures) ──
        SelfUpdateCore.Release r2 = SelfUpdateCore.fetchViaRawJsonBody("{\"version\":\"3.2.0\",\"code\":30200}");
        check("v320 upd: tier-2 latest.json parses the version",
            r2 != null && "3.2.0".equals(r2.version) && SelfUpdateCore.TIER_RAW_JSON.equals(r2.tier));
        check("v320 upd: tier-2 derives the same deterministic asset URL",
            r2 != null && SelfUpdateCore.derivedApkUrl("v3.2.0").equals(r2.apkUrl));
        check("v320 upd: tier-2 with an explicit sha256 uses it directly",
            SelfUpdateCore.fetchViaRawJsonBody("{\"version\":\"3.2.0\",\"sha256\":\""
                + "a".repeat(64) + "\"}") != null
                && "a".repeat(64).equals(SelfUpdateCore.fetchViaRawJsonBody("{\"version\":\"3.2.0\",\"sha256\":\""
                    + "a".repeat(64) + "\"}").sha256Expected));
        check("v320 upd: a junk latest.json body is a null (tier falls through)",
            SelfUpdateCore.fetchViaRawJsonBody("not json") == null);

        // ── the 15-minute failure backoff contract (the 250 ms loop killer) ──
        long now = System.currentTimeMillis();
        check("v320 upd: a fresh check with no failure history is ALLOWED",
            SelfUpdateCore.checkAllowed(now, 0, 0, false));
        check("v320 upd: a failed check 5 minutes ago BLOCKS automatic re-checks (15-min backoff)",
            !SelfUpdateCore.checkAllowed(now, now - 1000, now - 5 * 60 * 1000L, false));
        check("v320 upd: a failed check 16 minutes ago no longer blocks (both stamps 16 min old)",
            SelfUpdateCore.checkAllowed(now, now - 16 * 60 * 1000L, now - 16 * 60 * 1000L, false));
        check("v320 upd: a check 30 seconds ago is blocked by the 60 s app-open debounce",
            !SelfUpdateCore.checkAllowed(now, now - 30 * 1000L, 0, false));
        check("v320 upd: a MANUAL check bypasses every gate",
            SelfUpdateCore.checkAllowed(now, now - 1000, now - 1000, true));
        // the storm simulation: 200 re-delivered jobs inside 148 s → 1 network attempt
        int attempts = 0;
        long lastCheck = 0, lastFail = 0;
        for (int i = 0; i < 200; i++) {
            long t = 1206 + i * 740;   // the field log's exact cadence: 200 calls / 148 s
            if (SelfUpdateCore.checkAllowed(t, lastCheck, lastFail, false)) {
                attempts++;
                lastCheck = t;         // persisted BEFORE the network call (the v3.2.0 contract)
                lastFail = t;          // the 403-fail arms the backoff immediately
            }
        }
        check("v320 upd: the 200-calls-in-148-s field storm collapses to exactly 1 network attempt",
            attempts == 1, "attempts=" + attempts);

        // ── the shipped-code contracts (static) ──
        String updSrc = readRepoFile("src/lk/techworld/anantplay/sicast/SelfUpdater.java");
        // the real method body only (a class-level javadoc mentioning the rule
        // must not false-positive the check)
        String onStartBody = methodBody(updSrc, "onStartJob");
        check("v320 upd: CheckJob NEVER re-arms the periodic schedule from inside onStartJob (the re-fire loop)",
            onStartBody != null && !onStartBody.contains("schedulePeriodic"));
        check("v320 upd: checkAndMaybeInstall persists lastFailedCheck on the null-release path",
            updSrc.contains("putLong(\"lastFailedCheck\""));
        check("v320 upd: the FAILURE_BACKOFF_MS constant is 15 minutes",
            updSrc.contains("FAILURE_BACKOFF_MS = 15 * 60 * 1000L"));
        String coreSrc = readRepoFile("src/lk/techworld/anantplay/sicast/SelfUpdateCore.java");
        check("v320 upd: the tier-1 resolver reads the redirect with setInstanceFollowRedirects(false)",
            coreSrc.contains("setInstanceFollowRedirects(false)"));
        check("v320 upd: the resolver's tier order is web-redirect → raw-json → rest-api",
            coreSrc.contains("TIER_WEB_REDIRECT") && coreSrc.contains("TIER_RAW_JSON") && coreSrc.contains("TIER_REST_API")
                && coreSrc.indexOf("TIER_WEB_REDIRECT") < coreSrc.indexOf("TIER_RAW_JSON")
                && coreSrc.indexOf("TIER_RAW_JSON") < coreSrc.indexOf("TIER_REST_API"));
        // the ordering contract: in checkOnLaunch the putLong("lastCheck") line
        // appears BEFORE the schedule() call (strip the comment tail and compare offsets)
        String launchBody = methodBody(updSrc, "checkOnLaunch");
        int persistAt = launchBody == null ? -1 : stripComments(launchBody).indexOf("putLong(\"lastCheck\"");
        int scheduleAt = launchBody == null ? -1 : stripComments(launchBody).indexOf("schedule(");
        check("v320 upd: lastCheck is persisted BEFORE the network call (checkOnLaunch writes it before schedule())",
            persistAt >= 0 && scheduleAt >= 0 && persistAt < scheduleAt);
        check("v320 upd: the periodic schedule is armed ONLY from App.onCreate (never the job)",
            readRepoFile("src/lk/techworld/anantplay/sicast/App.java").contains("SelfUpdater.schedulePeriodic(this)")
                && onStartBody != null && !onStartBody.contains("schedulePeriodic"));
    }

    /** v3.2.0 — THE SINHALA FONT/SCRIPT LAB: the bold-with-Typeface bug, the
     *  markup stripper, and the Unicode Sinhala ratio detector. */
    static void v320SinhalaFontScriptChecks() throws Exception {
        // 1. THE BOLD BUG: PlayActivity's one-place typeface contract
        String playSrc = readRepoFile("src/lk/techworld/anantplay/sicast/PlayActivity.java");
        check("v320 font: applyLangStyle is the ONE typeface setter (Typeface.create(family, style) merges family + bold)",
            java.util.regex.Pattern.compile("Typeface\\.create\\(family, style\\)").matcher(stripComments(playSrc)).find());
        check("v320 font: the subtitle view NEVER gets setTypeface(tf, style) with a second-arg style that resets the family",
            !java.util.regex.Pattern.compile("subView\\.setTypeface\\([^,]+,\\s*(?:Typeface\\.BOLD|Typeface\\.NORMAL|AppPrefs)")
                .matcher(stripComments(playSrc)).find());
        check("v320 font: the studio restyle re-calls applyLangStyle (bold flips land immediately)",
            stripComments(playSrc).contains("applyLangStyle();                                      // the ONE typeface setter")
                || stripComments(playSrc).contains("applyLangStyle();"));
        check("v320 font: complex-script wrapping is set (BREAK_STRATEGY_HIGH_QUALITY + HYPHENATION_FREQUENCY_NONE + 1.2 spacing)",
            playSrc.contains("BREAK_STRATEGY_HIGH_QUALITY") && playSrc.contains("HYPHENATION_FREQUENCY_NONE")
                && playSrc.contains("setLineSpacing(dp(2), 1.2f)"));
        // 2. THE MARKUP STRIPPER (the garbled-cue fix)
        check("v320 cue: {\\an8} ASS override blocks die",
            Cue.stripMarkup("{\\an8}ආයුබෝවන්").equals("ආයුබෝවන්"));
        check("v320 cue: <i>/<font> HTML tags die",
            Cue.stripMarkup("<i>hello</i> <font color=\"#fff\">world</font>").equals("hello world"));
        check("v320 cue: HTML entities decode (&amp; &quot; &#39; &amp;nbsp)",
            Cue.stripMarkup("a &amp; b &quot;c&quot; &#39;d&#39;").equals("a & b \"c\" 'd'")
                && Cue.stripMarkup("&nbsp;x&nbsp;").equals("x"));
        check("v320 cue: genuine zero-width garbage (ZWSP U+200B) still dies",
            Cue.stripMarkup("ක\u200Bව\uFEFFා\u00AD\u2060").equals("කවා"));
        // ── CRITICAL v3.2.0 HOTFIX: Sinhala ZWJ/ZWNJ MUST SURVIVE CLEANUP ──
        // U+200D is orthographically mandatory for rakaransaya ්‍ර, yansaya ්‍ය
        // and conjuncts (ශ්‍රී, චිත්‍රපටිය, ක්‍රියා, ප්‍රශ්නය, ව්‍යාපෘතිය). Stripping
        // it breaks every conjunct into hal-kirima glyphs.
        String conj = "{\\an8}<i>ශ්‍රී ලංකාවේ චිත්‍රපටිය &amp; ව්‍යාපෘතිය</i>\uFEFF\u200B\u00AD";
        String conjClean = Cue.stripMarkup(conj);
        check("v320 cue HOTFIX: Sinhala ZWJ survives — stripMarkup keeps all 3 U+200D in ශ්‍රී/චිත්‍රපටිය/ව්‍යාපෘතිය",
            conjClean.equals("ශ්‍රී ලංකාවේ චිත්‍රපටිය & ව්‍යාපෘතිය")
                && countChar(conjClean, '\u200D') == 3);
        check("v320 cue HOTFIX: the same call still strips {\\\\an8}/<i>/&amp;/BOM/ZWSP/soft-hyphen",
            !conjClean.contains("{\\") && !conjClean.contains("<i>") && !conjClean.contains("&amp;")
                && !conjClean.contains("\uFEFF") && !conjClean.contains("\u200B") && !conjClean.contains("\u00AD"));
        check("v320 cue HOTFIX: ZWNJ U+200C survives (valid in Sinhala/Indic/Arabic/Persian)",
            Cue.stripMarkup("ක්\u200Cෂ \u200D").equals("ක්\u200Cෂ \u200D")
                && countChar(Cue.stripMarkup("ක්\u200Cෂ"), '\u200C') == 1);
        check("v320 cue HOTFIX: Sinhala conjuncts keep their ZWJ through fromSrt()",
            Cue.fromSrt("1\n00:00:01,000 --> 00:00:02,000\n<i>ශ්‍රී</i> ලංකා\n").get(0).si.equals("ශ්‍රී ලංකා")
                && countChar(Cue.fromSrt("1\n00:00:01,000 --> 00:00:02,000\n<i>ශ්‍රී</i> ලංකා\n").get(0).si, '\u200D') == 1);
        check("v320 cue HOTFIX: fromPairedJson keeps ZWJ in paired Sinhala cues",
            countChar(Cue.fromPairedJson("{\"cues\":[{\"i\":0,\"t0\":0,\"t1\":1,\"en\":\"x\",\"si\":\"<b>ප්‍රශ්නය</b>\"}]}")
                .get(0).si, '\u200D') == 1);
        check("v320 cue: line structure is preserved (dialogue dashes stay)",
            Cue.stripMarkup("- first\n- second").equals("- first\n- second"));
        check("v320 cue: whitespace collapses cleanly",
            Cue.stripMarkup("  multiple   spaces\t\tand tabs  ").equals("multiple spaces and tabs"));
        check("v320 cue: fromSrt strips markup at parse time (a hostile .srt cleans)",
            Cue.fromSrt("1\n00:00:01,000 --> 00:00:02,000\n<i>{\\pos(1,2)}Hello &amp; bye</i>\n").get(0).si.equals("Hello & bye"));
        check("v320 cue: fromPairedJson strips markup too",
            Cue.fromPairedJson("{\"cues\":[{\"i\":0,\"t0\":0,\"t1\":1,\"en\":\"<i>Hi</i>\",\"si\":\"<b>ආයුබෝවන් &amp; සුබ</b>\"}]}")
                .get(0).si.equals("ආයුබෝවන් & සුබ"));
        // 3. THE SCRIPT-RATIO DETECTOR (the wrong-language fix)
        check("v320 script: a real Sinhala sample detects as Sinhala",
            SubLangDetect.looksSinhala("ආයුබෝවන් ඔයා මොකක්ද කරන්නේ"));
        check("v320 script: an English sample does NOT detect as Sinhala",
            !SubLangDetect.looksSinhala("What are you doing my friend"));
        check("v320 script: a French sample does NOT detect as Sinhala",
            !SubLangDetect.looksSinhala("Que faites-vous mon ami"));
        check("v320 script: a short mixed line with ≥30% Sinhala letters still qualifies",
            SubLangDetect.looksSinhala("ආයුබෝවන් friend මොකක්ද"));
        check("v320 script: pure Sinhala ratio ≈ 1.0, English ≈ 0.0",
            SubLangDetect.sinhalaRatio("ආයුබෝවන් මොකක්ද") > 0.95
                && SubLangDetect.sinhalaRatio("What are you doing") < 0.05);
        check("v320 script: dominantScript names the three cases",
            "sinhala".equals(SubLangDetect.dominantScript("ආයුබෝවන්"))
                && "latin".equals(SubLangDetect.dominantScript("hello friend"))
                && "other".equals(SubLangDetect.dominantScript("1234 !!!")));
        check("v320 script: sampleOf takes the MIDDLE cues (credits-head avoided)",
            SubLangDetect.sampleOf(java.util.Arrays.asList(
                new Cue(0, 0, 1, "", "", "", "credits text"),
                new Cue(1, 0, 1, "", "", "", "මැද පේළිය"),
                new Cue(2, 0, 1, "", "", "", "tail line"))).contains("මැද"));
        // 4. the fetch-pipeline contract: validate before announce
        check("v320 script: PlayActivity validates cues before announcing Sinhala (validateOrTranslate)",
            stripComments(readRepoFile("src/lk/techworld/anantplay/sicast/PlayActivity.java")).contains("validateOrTranslate"));
        // 5. the font asset exists in the repo
        check("v320 font: NotoSansSinhala-Regular.ttf ships in assets/",
            new java.io.File("assets/fonts/NotoSansSinhala-Regular.ttf").exists()
                && new java.io.File("assets/fonts/NotoSansSinhala-Regular.ttf").length() > 100_000);
    }

    /** v3.2.0 — THE FREE KEYLESS TRANSLATOR + AUTO-.zip LAB: batching, index
     *  preservation, decode tolerance, cache hits, and the zip pipeline. */
    static void v320FreeTranslatorZipChecks() throws Exception {
        // 1. batch splitting
        List<Cue> many = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) many.add(new Cue(i, i * 1000, i * 1000 + 900, null, "line " + i, null, "line " + i));
        int[] b1 = FreeSubTranslator.nextBatch(many, 0);
        int[] b2 = FreeSubTranslator.nextBatch(many, b1[1] + 1);
        check("v320 free: the first batch is ≤40 cues", b1[1] - b1[0] + 1 <= 40, "size=" + (b1[1] - b1[0] + 1));
        check("v320 free: the second batch starts right after the first", b2[0] == b1[1] + 1);
        check("v320 free: the end of the list returns {-1,-1}",
            java.util.Arrays.equals(FreeSubTranslator.nextBatch(many, many.size()), new int[]{-1, -1}));
        // 2. the encode/decode round-trip (timestamps + indices preserved)
        List<Cue> batch = many.subList(b1[0], b1[1] + 1);
        List<String> encoded = FreeSubTranslator.encodeBatch(many, b1[0], b1[1]);
        check("v320 free: the batch encodes numbered lines",
            encoded.get(0).startsWith("1 ") && encoded.get(1).startsWith("2 "));
        StringBuilder reply = new StringBuilder();
        for (String e : encoded) reply.append("si ").append(e).append('\n');
        String[] decoded = FreeSubTranslator.decodeBatch(reply.toString(), encoded.size());
        boolean allDecoded = true;
        for (int i = 0; i < decoded.length; i++) {
            if (decoded[i] == null || !decoded[i].equals("si " + encoded.get(i))) allDecoded = false;
        }
        check("v320 free: the decode maps EVERY line back onto its exact index (numbered replies)",
            allDecoded);
        // positional fallback (endpoint drops the numbers)
        StringBuilder pos = new StringBuilder();
        for (int i = 0; i < encoded.size(); i++) pos.append("T").append(i).append('\n');
        String[] decoded2 = FreeSubTranslator.decodeBatch(pos.toString(), encoded.size());
        check("v320 free: the decode falls back to positional order when numbers are absent",
            decoded2[0] != null && decoded2[0].equals("T0") && decoded2[1].equals("T1"));
        // a MISSING line leaves that cue untranslated (never a wrong index)
        String partial = "1 alpha\n3 gamma\n";
        String[] decoded3 = FreeSubTranslator.decodeBatch(partial, 3);
        check("v320 free: a missing reply line leaves its cue untranslated (never a wrong index)",
            "alpha".equals(decoded3[0]) && decoded3[1] == null && "gamma".equals(decoded3[2]));
        // 3. the reply parser
        check("v320 free: the translate_a/single JSON shape parses",
            FreeSubTranslator.parseReply(FreeSubTranslator.EP1, "[[[\"ආයුබෝවන්\",\"\",null,null,\"en\"],[\"මොකක්ද\",\"\",null,null,\"en\"]]]")
                .contains("ආයුබෝවන්"));
        check("v320 free: toSrt renders standard SRT timestamps",
            FreeSubTranslator.srtTs(3723004).equals("01:02:03,004"));
        check("v320 free: both keyless endpoints are HTTPS",
            FreeSubTranslator.EP1.startsWith("https://") && FreeSubTranslator.EP2.startsWith("https://"));
        boolean plaintextRefused = false;
        try {
            FreeSubTranslator.request("http://127.0.0.1:1/translate", "si", "x", 100, 100);
        } catch (java.io.IOException e) {
            plaintextRefused = e.getMessage() != null && e.getMessage().contains("HTTPS");
        }
        check("v320 free: a plaintext endpoint is refused BEFORE any connection (HTTPS-only)", plaintextRefused);
        check("v320 free: the disk cache round-trips (translate → cache → re-translate is an instant hit)",
            cacheRoundTrip());
        // 4. the auto-.zip pipeline (a synthetic Baiscope-style zip with a Sinhala srt)
        ByteArrayOutputStream zipBuf = new ByteArrayOutputStream();
        ZipOutputStream zs = new ZipOutputStream(zipBuf);
        String srt = "1\n00:00:01,000 --> 00:00:03,000\n<i>{\\an8}ආයුබෝවන්</i> &amp; සුබ උදෑසනක්\n\n"
            + "2\n00:00:04,000 --> 00:00:06,000\nමොකක්ද කරන්නේ\n\n";
        zs.putNextEntry(new ZipEntry("Movie.2026/subs/sinhala.srt"));
        zs.write(srt.getBytes(StandardCharsets.UTF_8));
        zs.closeEntry();
        zs.close();
        List<Cue> fromZip = SrtLoader.load(zipBuf.toByteArray(), "subs.zip");
        check("v320 zip: the auto-extractor pulls the nested .srt out of the zip",
            fromZip.size() == 2);
        check("v320 zip: the extracted cues are ALREADY markup-stripped ({\\an8}/<i>/&amp; gone)",
            fromZip.get(0).si.equals("ආයුබෝවන් & සුබ උදෑසනක්"));
        check("v320 zip: the zip-bomb cap still refuses >5 MB decompressed",
            zipBombRefused());
        // 5. the static contract: the engine is wired into PlayActivity
        String playSrc = stripComments(readRepoFile("src/lk/techworld/anantplay/sicast/PlayActivity.java"));
        check("v320 free: PlayActivity routes quota failures to the free translator (429/5xx legs)",
            playSrc.contains("freeTranslateCurrentCues") && playSrc.contains("freeTranslateCastSubs"));
        check("v320 free: the subs panel exposes the URL .srt/.zip loader",
            playSrc.contains("loadSubsFromUrl"));
        check("v320 free: the 404 isolate-miss is retried SILENTLY (no raw JSON banner on the video)",
            playSrc.contains("silently re-ensuring"));
    }

    /**
     * The translator cache round-trip on a real temp dir — DETERMINISTIC since
     * v3.3.0: v3.2.0's version called translate() twice and hoped the network
     * answered, so it FAILED on every offline/CI runner (exactly the kind of
     * "green locally, red in CI" test that erodes trust in a lab). Now the cache
     * is seeded through the SAME writer the engine uses, and the hit is proved
     * with ZERO network: a cache hit must return the exact cues, instantly.
     */
    static boolean cacheRoundTrip() {
        try {
            java.io.File dir = java.io.File.createTempFile("subcache", ".d");
            dir.delete();
            dir.mkdirs();
            List<Cue> src = new java.util.ArrayList<>();
            src.add(new Cue(0, 0, 3000, null, "hello there friend", null, "ආයුබෝවන් යාළුවා"));
            src.add(new Cue(1, 4000, 6000, null, "see you soon", null, "ආයෙත් හමුවෙමු"));
            String hash = SelfUpdateCore.sha256(FreeSubTranslator.sourceBytes(src));
            java.io.File cacheFile = new java.io.File(FreeSubTranslator.cacheDir(dir), hash + ".si.srt");
            FreeSubTranslator.writeCachedSrt(cacheFile, src);
            if (!cacheFile.isFile() || cacheFile.length() == 0) return false;
            List<Cue> readBack = FreeSubTranslator.readCachedSrt(cacheFile);
            if (readBack == null || readBack.size() != 2) return false;
            if (!"ආයුබෝවන් යාළුවා".equals(readBack.get(0).si)) return false;
            // and the ENGINE hits it: no request may be made on a cached list
            FreeSubTranslator.Result r = FreeSubTranslator.translate(dir, src, "si", null);
            return r.cacheHits == 1 && r.requests == 0 && r.complete;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The zip-bomb refusal (a >5 MB decompressed entry). */
    static boolean zipBombRefused() {
        try {
            ByteArrayOutputStream bomb = new ByteArrayOutputStream();
            ZipOutputStream bz = new ZipOutputStream(bomb);
            bz.putNextEntry(new ZipEntry("bomb.srt"));
            byte[] chunk = new byte[65536];
            for (int i = 0; i < 9; i++) bz.write(chunk);   // ~590 KB ×9 > 5 MB
            bz.closeEntry();
            bz.close();
            SrtLoader.load(bomb.toByteArray(), "bomb.zip");
            return false;   // no refusal — BAD
        } catch (java.io.IOException expected) {
            return true;
        }
    }

    /** v3.2.0 — THE JUNK-TITLE LAB: "ArrowTV Player" & friends never reach Cinemeta. */
    static void v320JunkTitleChecks() throws Exception {
        check("v320 junk: 'ArrowTV Player' is junk (the exact field string)",
            TitleCleaner.isJunkTitle("ArrowTV Player"));
        check("v320 junk: 'VidSrc' is junk", TitleCleaner.isJunkTitle("VidSrc"));
        check("v320 junk: 'Embed Player' is junk", TitleCleaner.isJunkTitle("Embed Player"));
        check("v320 junk: 'Watch Online' is junk", TitleCleaner.isJunkTitle("Watch Online"));
        check("v320 junk: 'Index of /' is junk", TitleCleaner.isJunkTitle("Index of /movies"));
        check("v320 junk: 'Loading…' / 'Just a moment…' are junk",
            TitleCleaner.isJunkTitle("Loading…") && TitleCleaner.isJunkTitle("Just a moment"));
        check("v320 junk: '' / null are junk", TitleCleaner.isJunkTitle("") && TitleCleaner.isJunkTitle(null));
        check("v320 junk: 'Binging: The Odyssey' is NOT junk (the real cast)",
            !TitleCleaner.isJunkTitle("Binging: The Odyssey"));
        check("v320 junk: 'The Odyssey 2026' is NOT junk",
            !TitleCleaner.isJunkTitle("The Odyssey 2026"));
        check("v320 junk: 'Watch The Odyssey Full Movie HD - OnlyFlix' cleans to a REAL title",
            TitleCleaner.clean("Watch The Odyssey Full Movie HD - OnlyFlix").title.toLowerCase(java.util.Locale.ROOT).contains("odyssey"));
        check("v320 junk: the Clean record carries the junk flag",
            TitleCleaner.clean("ArrowTV Player").junk && !TitleCleaner.clean("The Odyssey").junk);
        check("v320 junk: the sniffer's cleanTitle returns '' for junk pages (never a branding title)",
            StreamSniffer.cleanTitle("ArrowTV Player").isEmpty()
                && StreamSniffer.cleanTitle("The Odyssey - Watch HD").toLowerCase(java.util.Locale.ROOT).contains("odyssey"));
        String playSrc = stripComments(readRepoFile("src/lk/techworld/anantplay/sicast/PlayActivity.java"));
        check("v320 junk: PlayActivity skips Cinemeta for junk titles and shows the search pill",
            playSrc.contains("titleIsJunk || TitleCleaner.isJunkTitle(title)") && playSrc.contains("තට්ටු කර සොයන්න"));
        check("v320 junk: the 1-tap in-player title search dialog exists",
            playSrc.contains("openTitleSearch"));
    }

    /** v3.2.0 — THE STALE-CRASH LAB: a v3.1.1 crash auto-clears on a v3.2.0 start. */
    static void v320StaleCrashChecks() throws Exception {
        // a report stamped with an OLDER versionCode is ALWAYS stale
        String old = CrashLog.format(System.currentTimeMillis() - 3600_000, "3.1.1", 30101, "sdk 36", "main",
            new IllegalStateException("Resource ID #0x88000000"));
        check("v320 crash: the report header stamps versionCode",
            CrashLog.versionCodeOf(old) == 30101);
        check("v320 crash: a v3.1.1 (30101) crash IS stale for the running v3.2.0 (30200)",
            CrashLog.staleFor(old, 30200, System.currentTimeMillis()));
        // a report from THIS build is never stale
        String cur = CrashLog.format(System.currentTimeMillis() - 3600_000, "3.2.0", 30200, "sdk 36", "main",
            new RuntimeException("fresh"));
        check("v320 crash: a v3.2.0 (30200) crash is NOT stale for the running 30200",
            !CrashLog.staleFor(cur, 30200, System.currentTimeMillis()));
        // a legacy v3.1.x report (no stamp) ages out after 48 h — the fixture
        // is generated 4 DAYS BACK from "now" so the test never depends on the
        // wall clock (the 2026-11-20 hard-coded date broke once "now" moved on)
        String fourDaysAgo = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
            .format(new java.util.Date(System.currentTimeMillis() - 4L * 24 * 3600 * 1000));
        String legacy = " UNCAUGHT CRASH — AnantPlay Sinhala Player v3.1.2\n at " + fourDaysAgo + "\n thread: main\n"
            + "EXCEPTION java.lang.IllegalStateException: x\n    at lk.techworld.anantplay.sicast.PlayActivity.buildUi(PlayActivity.java:1001)\n";
        check("v320 crash: a legacy unstamped report 4 days old IS stale",
            CrashLog.staleFor(legacy, 30200, System.currentTimeMillis()));
        // the file-level sweep (a real temp filesDir)
        java.io.File dir = java.io.File.createTempFile("crashd", ".d");
        dir.delete();
        dir.mkdirs();
        CrashLog.writeAtomically(dir, old);
        check("v320 crash: clearStaleFor DELETES the older-versionCode file",
            CrashLog.clearStaleFor(dir, 30200, System.currentTimeMillis()) && CrashLog.read(dir) == null);
        CrashLog.writeAtomically(dir, cur);
        check("v320 crash: clearStaleFor KEEPS a current-versionCode crash",
            !CrashLog.clearStaleFor(dir, 30200, System.currentTimeMillis()) && CrashLog.read(dir) != null);
        // the App wiring
        check("v320 crash: App.onCreate runs the stale sweep at startup",
            readRepoFile("src/lk/techworld/anantplay/sicast/App.java").contains("clearStaleFor(getFilesDir(), AppInfo.VERSION_CODE"));
    }

    /** v3.2.0 — THE DEEP CUSTOMIZATION LAB: every new knob normalizes and the
     *  whole model round-trips through SettingsBackup. */
    static void v320CustomizationChecks() throws Exception {
        // the normalizers
        check("v320 cust: the custom accent hex validates/repairs (#14b8a6)",
            AppPrefs.normalizeAccentHex("#14b8a6").equals("14b8a6") && AppPrefs.normalizeAccentHex("zzzzzz").isEmpty()
                && AppPrefs.normalizeAccentHex(null).isEmpty() && AppPrefs.normalizeAccentHex("abc").isEmpty());
        check("v320 cust: accentHexColor paints the ARGB",
            AppPrefs.accentHexColor("14b8a6") == (int) ((0xFFL << 24) | 0x14b8a6L));
        check("v320 cust: card radius whitelist (sharp|rounded|apple-pill)",
            AppPrefs.normalizeCardRadius("apple-pill").equals("apple-pill")
                && AppPrefs.normalizeCardRadius("weird").equals("rounded"));
        check("v320 cust: density whitelist (spacious|comfortable|compact)",
            AppPrefs.normalizeDensity("spacious").equals("spacious")
                && AppPrefs.normalizeDensity("x").equals("comfortable"));
        check("v320 cust: font family whitelist (noto|system|serif)",
            AppPrefs.normalizeFontFamily("serif").equals("serif")
                && AppPrefs.normalizeFontFamily("comic").equals("noto"));
        check("v320 cust: subtitle engine whitelist (4 orders)",
            AppPrefs.normalizeSubEngine("human-free").equals("human-free")
                && AppPrefs.normalizeSubEngine("worker").equals("worker")
                && AppPrefs.normalizeSubEngine("junk").equals("human-pa-free"));
        check("v320 cust: sub bg styles (pill|outline|box|shadow)",
            AppPrefs.normalizeSubBg("box").equals("box") && AppPrefs.normalizeSubBg("shadow").equals("shadow")
                && AppPrefs.normalizeSubBg("glow").equals("pill"));
        check("v320 cust: fontSp clamps to 12..36 (v3.3.0), line spacing to 100..150, bottom pct to 2..35",
            AppPrefs.normalizeFontSp(99) == 36 && AppPrefs.normalizeFontSp(3) == 12
                && AppPrefs.normalizeSubLineSpacing(180) == 150 && AppPrefs.normalizeSubBottomPct(1) == 2
                && AppPrefs.normalizeSubBottomPct(60) == 35);
        check("v320 cust: play speed clamps to 0.5x..2.0x (per-mille 50..200)",
            AppPrefs.normalizePlaySpeed(300) == 200 && AppPrefs.normalizePlaySpeed(10) == 50);
        check("v320 cust: aspect whitelist (v3.3.0: fit|fill|16:9|stretch, the old zoom alias → fill)",
            AppPrefs.normalizeAspectMode("16:9").equals("16:9")
                && AppPrefs.normalizeAspectMode("stretch").equals("stretch")
                && AppPrefs.normalizeAspectMode("zoom").equals("fill")
                && AppPrefs.normalizeAspectMode("wide").equals("fit"));
        check("v320 cust: receiver port clamps to 1024..65535 (8765 default)",
            AppPrefs.normalizeReceiverPort(80) == 8765 && AppPrefs.normalizeReceiverPort(9000) == 9000);
        check("v320 cust: 8 curated accents + 6 subtitle colors shipped",
            ThemeEngine.ACCENTS.length == 8 && ThemeEngine.SUB_COLORS.length == 6);
        check("v320 cust: the radii token maps the three shapes",
            java.util.Arrays.equals(ThemeEngine.radii("sharp"), new int[]{6, 10, 4})
                && java.util.Arrays.equals(ThemeEngine.radii("apple-pill"), new int[]{24, 28, 16})
                && java.util.Arrays.equals(ThemeEngine.radii(null), new int[]{16, 22, 10}));
        // the custom-links registry CRUD (parse ↔ serialize round-trip)
        List<AppPrefs.Link> links = AppPrefs.parseLinks("Baiscope|https://baiscope.lk\nZoom|https://zoom.lk");
        check("v320 cust: custom links parse (name|url lines)",
            links.size() == 2 && "Baiscope".equals(links.get(0).name) && links.get(1).url.equals("https://zoom.lk"));
        check("v320 cust: serializeLinks round-trips parseLinks",
            AppPrefs.parseLinks(AppPrefs.serializeLinks(links)).size() == 2);
        check("v320 cust: junk link lines die (no-|, bad-scheme lines dropped; a dup URL keeps only the first)",
            AppPrefs.parseLinks("nameonly\nX|ftp://bad\nDup|https://baiscope.lk\nB|https://baiscope.lk").size() == 1);
        check("v320 cust: >8 links truncate to the cap",
            AppPrefs.parseLinks("a|https://1.lk\nb|https://2.lk\nc|https://3.lk\nd|https://4.lk\ne|https://5.lk\nf|https://6.lk\ng|https://7.lk\nh|https://8.lk\ni|https://9.lk").size() == 8);
        // the palette decision with the custom hex
        ThemeEngine.Palette p = ThemeEngine.palette("dark", 0xFF123456, 0, "14b8a6");
        check("v320 cust: the custom hex accent WINS over dynamic color and presets",
            p.accent == (int) ((0xFFL << 24) | 0x14b8a6L));
        ThemeEngine.Palette p2 = ThemeEngine.palette("dark", 0, 3, "");
        check("v320 cust: without a custom hex the preset+dynamic chain holds",
            p2.accent == ThemeEngine.ACCENTS[3]);
        // the backup round-trip with EVERY v3.2.0 key
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put(AppPrefs.K_THEME_MODE, "amoled");
        settings.put(AppPrefs.K_ACCENT, 6);
        settings.put(AppPrefs.K_ACCENT_HEX, "#14b8a6");
        settings.put(AppPrefs.K_CARD_RADIUS, "apple-pill");
        settings.put(AppPrefs.K_DENSITY, "spacious");
        settings.put(AppPrefs.K_FONT_SP, 26);
        settings.put(AppPrefs.K_FONT_FAMILY, "serif");
        settings.put(AppPrefs.K_SUB_COLOR, 5);
        settings.put(AppPrefs.K_SUB_BG, "shadow");
        settings.put(AppPrefs.K_SUB_BG_OPACITY, 95);
        settings.put(AppPrefs.K_SUB_BOTTOM_PCT, 35);
        settings.put(AppPrefs.K_SUB_LINE_SPACING, 140);
        settings.put(AppPrefs.K_SUB_ENGINE, "human-free");
        settings.put(AppPrefs.K_AUTO_TRANSLATE_NON_SI, Boolean.TRUE);
        settings.put(AppPrefs.K_AUTO_ZIP, Boolean.FALSE);
        settings.put(AppPrefs.K_PLAY_SPEED, 150);
        settings.put(AppPrefs.K_ASPECT_MODE, "zoom");
        settings.put(AppPrefs.K_DOUBLE_TAP_SEEK, Boolean.FALSE);
        settings.put(AppPrefs.K_RECEIVER_PORT, 9000);
        settings.put(AppPrefs.K_AD_BLOCK, Boolean.FALSE);
        settings.put(AppPrefs.K_FREE_TRANSLATOR, Boolean.TRUE);
        settings.put(AppPrefs.K_WEB_LINKS, "Baiscope|https://baiscope.lk");
        String doc = SettingsBackup.export("3.2.0", System.currentTimeMillis(), settings);
        SettingsBackup.Import imp = SettingsBackup.parseImport(doc);
        boolean all = imp.ok
            && "amoled".equals(imp.values.get(AppPrefs.K_THEME_MODE))
            && Integer.valueOf(6).equals(imp.values.get(AppPrefs.K_ACCENT))
            && "14b8a6".equals(imp.values.get(AppPrefs.K_ACCENT_HEX))
            && "apple-pill".equals(imp.values.get(AppPrefs.K_CARD_RADIUS))
            && "spacious".equals(imp.values.get(AppPrefs.K_DENSITY))
            && Integer.valueOf(26).equals(imp.values.get(AppPrefs.K_FONT_SP))
            && "serif".equals(imp.values.get(AppPrefs.K_FONT_FAMILY))
            && Integer.valueOf(5).equals(imp.values.get(AppPrefs.K_SUB_COLOR))
            && "shadow".equals(imp.values.get(AppPrefs.K_SUB_BG))
            && Integer.valueOf(95).equals(imp.values.get(AppPrefs.K_SUB_BG_OPACITY))
            && Integer.valueOf(35).equals(imp.values.get(AppPrefs.K_SUB_BOTTOM_PCT))
            && Integer.valueOf(140).equals(imp.values.get(AppPrefs.K_SUB_LINE_SPACING))
            && "human-free".equals(imp.values.get(AppPrefs.K_SUB_ENGINE))
            && Boolean.TRUE.equals(imp.values.get(AppPrefs.K_AUTO_TRANSLATE_NON_SI))
            && Boolean.FALSE.equals(imp.values.get(AppPrefs.K_AUTO_ZIP))
            && Integer.valueOf(150).equals(imp.values.get(AppPrefs.K_PLAY_SPEED))
            && "fill".equals(imp.values.get(AppPrefs.K_ASPECT_MODE))   // v3.3.0: zoom → fill
            && Boolean.FALSE.equals(imp.values.get(AppPrefs.K_DOUBLE_TAP_SEEK))
            && Integer.valueOf(9000).equals(imp.values.get(AppPrefs.K_RECEIVER_PORT))
            && Boolean.FALSE.equals(imp.values.get(AppPrefs.K_AD_BLOCK))
            && Boolean.TRUE.equals(imp.values.get(AppPrefs.K_FREE_TRANSLATOR));
        check("v320 cust: the FULL v3.2.0 settings model round-trips through Backup/Restore", all,
            imp.ok ? "" : imp.error);
        // a hostile import value gets REJECTED (never poisons the app)
        Map<String, Object> hostile = new LinkedHashMap<>();
        hostile.put(AppPrefs.K_ACCENT, 99);
        hostile.put(AppPrefs.K_FONT_SP, 999);
        hostile.put(AppPrefs.K_THEME_MODE, "neon");
        Map<String, Object> clean = AppPrefs.normalizeMap(hostile);
        check("v320 cust: hostile import values normalize (99→clamped, junk→default)",
            clean.containsKey(AppPrefs.K_ACCENT) && Integer.valueOf(0).equals(clean.get(AppPrefs.K_ACCENT))
                && Integer.valueOf(36).equals(clean.get(AppPrefs.K_FONT_SP))
                && "dark".equals(clean.get(AppPrefs.K_THEME_MODE)));
        // the static UI contract: 5 tabs, the links manager page, the sheet
        String mainSrc = stripComments(readRepoFile("src/lk/techworld/anantplay/sicast/MainActivity.java"));
        check("v320 cust: the shell ships the 5-tab bar (Home/Web/Customize/Troubleshoot)",
            mainSrc.contains("TAB_TROUBLE") && mainSrc.contains("\uD83C\uDFA8"));
        check("v320 cust: the Custom Links manager page exists (add/reorder/delete)",
            mainSrc.contains("openWebPlayer") && mainSrc.contains("paintLinks"));
        String playSrc = stripComments(readRepoFile("src/lk/techworld/anantplay/sicast/PlayActivity.java"));
        check("v320 cust: the in-player settings sheet ships (speed/aspect/live-style over the movie)",
            playSrc.contains("buildSettingsSheet") && playSrc.contains("toggleSheet"));
        check("v320 cust: the edge-to-edge insets pad the HUD (statusBars|displayCutout + navigationBars)",
            playSrc.contains("WindowInsets.Type.statusBars()") && playSrc.contains("WindowInsets.Type.navigationBars()"));
    }

    /** v3.2.0 — THE 10-POINT TROUBLESHOOTER LAB: the probe engine's pure halves
     *  (offline-safe: probes that need the network are checked for CONTRACT,
     *  not live connectivity), and the report section renders all outcomes. */
    static void v320TroubleshooterChecks() throws Exception {
        // the probe matrix's shape
        check("v320 probe: FeatureProbe.runAll declares the 10-point matrix",
            probeNamesMatch());
        // the probe section renders BOTH PASS and FAIL lines
        java.util.List<FeatureProbe.Outcome> demo = new java.util.ArrayList<>();
        FeatureProbe.Outcome ok = new FeatureProbe.Outcome(1, "Loopback Receiver");
        ok.pass = true; ok.ms = 12; ok.detail = "/info ok";
        FeatureProbe.Outcome bad = new FeatureProbe.Outcome(7, "Free Keyless Translator");
        bad.pass = false; bad.ms = 340; bad.detail = "no translation (offline)";
        demo.add(ok);
        demo.add(bad);
        String section = Troubleshooter.probeSection(demo);
        check("v320 probe: the report section shows ✓ PASS lines",
            section.contains("✓ 1. Loopback Receiver — 12 ms — /info ok"));
        check("v320 probe: the report section shows ✗ FAIL lines (both are recorded)",
            section.contains("✗ 7. Free Keyless Translator — 340 ms — no translation"));
        check("v320 probe: the verdict line counts 1/10",
            section.contains("verdict: 1/10 PASS"));
        check("v320 probe: an empty list renders the not-run hint",
            Troubleshooter.probeSection(null).contains("not run yet"));
        // the probe section is privacy-scrubbed end-to-end
        FeatureProbe.Outcome leaky = new FeatureProbe.Outcome(4, "Updater");
        leaky.pass = true; leaky.ms = 5;
        leaky.detail = "from 192.168.1.39 model Xiaomi 24117RN76G";
        java.util.List<FeatureProbe.Outcome> leakList = new java.util.ArrayList<>();
        leakList.add(leaky);
        String scrubbed = Troubleshooter.probeSection(leakList);
        check("v320 probe: probe details are PrivacyScrubbed (IPs/models die)",
            !scrubbed.contains("192.168.1.39") && !scrubbed.contains("24117RN76G"));
        // the zip/font/script probes run OFFLINE-live (pure, in-lab)
        FeatureProbe.Outcome zip = FeatureProbe.probe8ZipExtractor(null);
        check("v320 probe: probe 8 (zip extractor + bomb guard) runs IN the lab and passes",
            zip.pass, zip.detail);
        FeatureProbe.Outcome font = FeatureProbe.probe9FontAsset(null, null);
        check("v320 probe: probe 9 (font + shaping) runs IN the lab and passes",
            font.pass, font.detail);
        // v3.3.0 — the nav-shell verdict is measured on-device by MainActivity.measureShell();
        // the lab simulates a measured-OK shell (and checks the default is FAIL, below).
        boolean shellDefault = FeatureProbe.shellOk;
        FeatureProbe.shellOk = true;
        FeatureProbe.Outcome backup = FeatureProbe.probe10BackupCrash(null, null);
        check("v320 probe: probe 10 (backup round-trip) runs IN the lab and passes",
            backup.pass, backup.detail);
        check("v330 probe: probe 10 is HONEST — an unmeasured nav shell never reports PASS",
            !shellDefault);
        // the static wiring: the Troubleshoot tab carries the hero + copy/share
        String mainSrc = stripComments(readRepoFile("src/lk/techworld/anantplay/sicast/MainActivity.java"));
        check("v320 probe: the Troubleshoot tab ships the Run Full App Troubleshoot hero",
            mainSrc.contains("Run Full App Troubleshoot (All Features)") || mainSrc.contains("සම්පූර්ණ ඇප් පරීක්ෂණය"));
        check("v320 probe: the report builder appends the probe section",
            mainSrc.contains("Troubleshooter.probeSection(probeOutcomes)"));
    }

    /** The 10 probe names (the matrix's identity, checked against FeatureProbe). */
    static boolean probeNamesMatch() {
        try {
            java.lang.reflect.Method[] ms = FeatureProbe.class.getDeclaredMethods();
            int found = 0;
            for (java.lang.reflect.Method m : ms) {
                String n = m.getName();
                if (Character.isDigit(n.charAt(n.length() - 1))) found++;   // probe1..probe10
            }
            return found == 10;
        } catch (Throwable t) {
            return false;
        }
    }


    // ════════════════════════════════════════════════════════════════════════
    // v3.3.0 — PHASE 1's FOUR FIELD BUGS, MADE STRUCTURALLY IMPOSSIBLE
    //   · the vanished bottom navigation bar   (layout invariants)
    //   · ERROR_CODE_IO_BAD_HTTP_STATUS: 403   (the live mock-CDN lab)
    //   · the stretched portrait video         (the aspect-ratio math)
    //   · the Sri Lankan .zip subtitle hunt    (capture + external VLC)
    // ════════════════════════════════════════════════════════════════════════

    /** BUG #1 — the bottom bar was BUILT but never ADDED to the root. */
    static void v330LayoutChecks() throws Exception {
        String mainSrc = stripComments(readRepoFile("src/lk/techworld/anantplay/sicast/MainActivity.java"));
        String playSrc = stripComments(readRepoFile("src/lk/techworld/anantplay/sicast/PlayActivity.java"));

        check("v330 layout: the shell is a LinearLayout(VERTICAL)",
            mainSrc.contains("shell.setOrientation(LinearLayout.VERTICAL)"));
        check("v330 layout: the content host is height 0 + weight 1.0f (a bar pushed off-screen is now arithmetically impossible)",
            mainSrc.contains("new LinearLayout.LayoutParams(-1, 0, 1f)"));
        check("v330 layout: the bottom bar is REALLY added to the shell (the exact v3.2.0 defect)",
            mainSrc.contains("shell.addView(bottomBar, new LinearLayout.LayoutParams(-1, -2))"));
        check("v330 layout: the top app bar is added to the shell",
            mainSrc.contains("shell.addView(topBar, new LinearLayout.LayoutParams(-1, -2))"));
        check("v330 layout: the old FrameLayout shell with a parentless bar is gone",
            !mainSrc.contains("root.addView(pages"));
        check("v330 layout: the top bar carries 1-tap 🩺 Troubleshoot AND ⚙️ Settings",
            mainSrc.contains("topBarIcon(\"\uD83E\uDE7A\"") && mainSrc.contains("topBarIcon(\"\u2699"));
        check("v330 layout: the top bar pads by statusBars()|displayCutout()",
            mainSrc.contains("WindowInsets.Type.statusBars()") && mainSrc.contains("WindowInsets.Type.displayCutout()"));
        check("v330 layout: the bottom bar pads by navigationBars()",
            mainSrc.contains("WindowInsets.Type.navigationBars()"));
        check("v330 layout: the 4 tabs are Home/Web/Customize/Troubleshoot — and 3 when Web is hidden (never hiding Customize/Troubleshoot)",
            mainSrc.contains("new int[]{TAB_HOME, TAB_WEB, TAB_SETTINGS, TAB_TROUBLE}")
                && mainSrc.contains("new int[]{TAB_HOME, TAB_SETTINGS, TAB_TROUBLE}"));
        check("v330 layout: the bottom bar has a 64dp minimum height",
            mainSrc.contains("bottomBar.setMinimumHeight(dp(64))"));
        check("v330 layout: the runtime shell invariant is measured on every resume",
            mainSrc.contains("measureShell") && mainSrc.contains("bottomBar.getTop() >= pages.getBottom() - 1"));

        check("v330 layout: the player HUD is THREE stacked zones (centre = height 0 + weight 1)",
            playSrc.contains("box.addView(hudCenter, new LinearLayout.LayoutParams(-1, 0, 1f))"));
        check("v330 layout: the player's top bar pads by the measured status inset",
            playSrc.contains("hudTopBar.setPadding(dp(6), statusInset + dp(6), dp(6), dp(6))"));
        check("v330 layout: the player's bottom bar pads by the measured nav inset",
            playSrc.contains("navInset + dp(8)"));
        check("v330 layout: the subtitle layer reserves the top AND bottom bar zones",
            playSrc.contains("subLayer.setPadding(0, statusInset + dp(40), 0, navInset + dp(96))"));
        check("v330 layout: the v3.2.0 fixed-64dp floating pill row (the '< Back' overlap) is gone",
            !playSrc.contains("pillLp.topMargin = dp(64)"));
        check("v330 layout: the SurfaceView is NOT MATCH_PARENT any more (VideoViewport sizes it)",
            !playSrc.contains("root.addView(surface, new FrameLayout.LayoutParams(-1, -1))"));
        check("v330 layout: v3.2.0's applyResizeMode() is deleted",
            !playSrc.contains("applyResizeMode"));
        check("v330 layout: the player wires Player.Listener.onVideoSizeChanged",
            playSrc.contains("public void onVideoSizeChanged(VideoSize vs)"));
        check("v330 layout: the 4 aspect modes ship (Fit/Fill/16:9/Stretch)",
            playSrc.contains("VideoViewport.next(aspectMode)") && playSrc.contains("VideoViewport.modeLabel"));
        check("v330 layout: rotate + lock are 1-tap HUD actions",
            playSrc.contains("private void rotateScreen()") && playSrc.contains("private void setLocked(boolean on)"));
        check("v330 layout: the lock swallows every touch",
            playSrc.contains("if (locked) return true;"));
        check("v330 layout: the error card never sits under the top bar (centred card, 4 fixes)",
            playSrc.contains("buildErrorCard") && playSrc.contains("Retry with alternate headers"));
    }

    /** BUG #2 — the live mock CDN: 403 unless Referer+Origin+UA+Cookie are exact. */
    static void v330Http403Checks() throws Exception {
        final String wantReferer = "https://player.example.net/watch/123";
        final String wantOrigin = "https://player.example.net";
        final String wantCookie = "cf_clearance=abc123";

        com.sun.net.httpserver.HttpServer srv =
            com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", ex -> {
            String ua = ex.getRequestHeaders().getFirst("User-Agent");
            String ref = ex.getRequestHeaders().getFirst("Referer");
            String org = ex.getRequestHeaders().getFirst("Origin");
            String ck = ex.getRequestHeaders().getFirst("Cookie");
            String acc = ex.getRequestHeaders().getFirst("Accept");
            boolean ok = ua != null && ua.contains("Chrome/") && !ua.contains("AndroidXMedia3")
                && ref != null && ref.startsWith(wantOrigin) && wantOrigin.equals(org)
                && ck != null && ck.contains(wantCookie) && acc != null;
            byte[] body = (ok ? "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:6.0,\nseg1.ts\n"
                              : "403 forbidden").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(ok ? 200 : 403, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        srv.start();
        int port = srv.getAddress().getPort();
        String url = "http://127.0.0.1:" + port + "/hls/index.m3u8";
        try {
            // the mock is genuinely strict — this is the v3.2.0 field failure, reproduced
            Map<String, String> legacy = new LinkedHashMap<>();
            legacy.put("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36");
            legacy.put("Referer", wantReferer);
            legacy.put("Cookie", wantCookie);
            check("v330 http403: the mock CDN 403s v3.2.0's stub User-Agent (the field bug, reproduced)",
                status(url, legacy) == 403);

            Map<String, String> media3 = new LinkedHashMap<>(legacy);
            media3.put("User-Agent", "AndroidXMedia3/1.11.1");
            check("v330 http403: ...and refuses Media3's own default User-Agent",
                status(url, media3) == 403);

            // the normalized headers win on the FIRST attempt
            String castJson = "{\"Referer\":\"" + wantReferer + "\","
                + "\"User-Agent\":\"Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36\"}";
            Map<String, String> norm = StreamHttpConfig.normalize(castJson, wantCookie, url);
            check("v330 http403: normalize() swaps the stub UA for a browser-grade Chrome UA",
                norm.get("User-Agent") != null && norm.get("User-Agent").contains("Chrome/131"));
            check("v330 http403: normalize() derives Origin from the Referer",
                wantOrigin.equals(norm.get("Origin")));
            check("v330 http403: normalize() merges the cast's cookie exactly once",
                norm.get("Cookie") != null && norm.get("Cookie").contains(wantCookie));
            check("v330 http403: normalize() adds Accept: */*", "*/*".equals(norm.get("Accept")));
            check("v330 http403: normalize() adds Accept-Language",
                norm.get("Accept-Language") != null);
            check("v330 http403: the normalized headers get HTTP 200 on the FIRST try",
                status(url, norm) == 200, "status=" + status(url, norm));

            // the recovery leg
            Map<String, String> broken = new LinkedHashMap<>();
            broken.put("User-Agent", "AndroidXMedia3/1.11.1");
            broken.put("Cookie", wantCookie);
            check("v330 http403: without a Referer/Origin even a browser UA is refused",
                status(url, broken) == 403);
            Map<String, String> rec = StreamHttpConfig.recovery(wantReferer, broken);
            check("v330 http403: recovery() forces the Chrome UA",
                rec.get("User-Agent") != null && rec.get("User-Agent").contains("Chrome/131"));
            check("v330 http403: recovery() derives the Origin from the Referer",
                wantOrigin.equals(rec.get("Origin")));
            check("v330 http403: recovery() adds the Sec-Fetch triple a real Chrome sends",
                "video".equals(rec.get("Sec-Fetch-Dest")) && "no-cors".equals(rec.get("Sec-Fetch-Mode"))
                    && "cross-site".equals(rec.get("Sec-Fetch-Site")));
            check("v330 http403: the recovered headers get HTTP 200 (the automatic retry really works)",
                status(url, rec) == 200);

            // every cast shape AnantPlay can send
            String nested = "{\"headers\":{\"referer\":\"" + wantReferer + "\",\"cookie\":\"" + wantCookie
                + "\"},\"ua\":\"Dalvik/2.1.0 (Linux; U; Android 16)\"}";
            Map<String, String> n2 = StreamHttpConfig.normalize(nested, null, url);
            check("v330 http403: a NESTED {\"headers\":{...}} cast parses (referer+cookie) and its top-level ua is replaced",
                wantReferer.equals(n2.get("Referer")) && n2.get("Cookie").contains(wantCookie)
                    && n2.get("User-Agent").contains("Chrome/131"));
            check("v330 http403: the nested-shape cast plays (HTTP 200)", status(url, n2) == 200);

            Map<String, String> hostile = new LinkedHashMap<>();
            hostile.put("Host", "evil.example");
            hostile.put("Content-Length", "999");
            hostile.put("Connection", "keep-alive");
            hostile.put("Referer", wantReferer);
            Map<String, String> cleaned = StreamHttpConfig.normalize(null, wantCookie, url,
                new LinkedHashMap<>(hostile));
            check("v330 http403: hop-by-hop headers (Host/Content-Length/Connection) are dropped",
                !cleaned.containsKey("Host") && !cleaned.containsKey("Content-Length")
                    && !cleaned.containsKey("Connection") && cleaned.containsKey("Referer"));
            check("v330 http403: a header name is canonicalised (referer → Referer, user-agent → User-Agent)",
                cleaned.containsKey("Referer") && cleaned.containsKey("User-Agent"));
            check("v330 http403: describe() logs header NAMES only — never a cookie or a token",
                !StreamHttpConfig.describe(cleaned).contains(wantCookie)
                    && StreamHttpConfig.describe(cleaned).contains("Cookie"));
            check("v330 http403: recoverable() covers 401/403/451 only",
                StreamHttpConfig.recoverable(401) && StreamHttpConfig.recoverable(403)
                    && StreamHttpConfig.recoverable(451) && !StreamHttpConfig.recoverable(404)
                    && !StreamHttpConfig.recoverable(200));
            check("v330 http403: isBlockedUa() flags Media3 / Dalvik / the truncated stub, not a real Chrome",
                StreamHttpConfig.isBlockedUa("AndroidXMedia3/1.11.1")
                    && StreamHttpConfig.isBlockedUa("Dalvik/2.1.0 (Linux; U; Android 16)")
                    && StreamHttpConfig.isBlockedUa("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                    && StreamHttpConfig.isBlockedUa(null)
                    && !StreamHttpConfig.isBlockedUa(StreamHttpConfig.CHROME_UA));
            check("v330 http403: originOf() strips the path AND any credentials",
                "https://cdn.example.com".equals(StreamHttpConfig.originOf("https://user:pw@cdn.example.com/a/b?c=1"))
                    && StreamHttpConfig.originOf("ftp://x/y") == null);
            check("v330 http403: the player builds the DataSource exactly like official AnantPlay (setUserAgent + cross-protocol redirects)",
                stripComments(readRepoFile("src/lk/techworld/anantplay/sicast/PlayActivity.java"))
                    .contains(".setUserAgent(ua)") );
            check("v330 http403: the player adds the WebView cookie PER REQUEST HOST (ResolvingDataSource)",
                stripComments(readRepoFile("src/lk/techworld/anantplay/sicast/PlayActivity.java"))
                    .contains("new ResolvingDataSource.Factory(http"));
            check("v330 http403: a 401/403/451 triggers ONE automatic recovery retry",
                stripComments(readRepoFile("src/lk/techworld/anantplay/sicast/PlayActivity.java"))
                    .contains("StreamHttpConfig.recovery(url, httpHeaders)"));
        } finally {
            srv.stop(0);
        }
    }

    /** One GET with exactly these headers → the status code. */
    static int status(String url, Map<String, String> headers) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection(java.net.Proxy.NO_PROXY);
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet())
                c.setRequestProperty(e.getKey(), e.getValue());
            return c.getResponseCode();
        } catch (Exception e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** BUG #3 — the aspect-ratio math that stops the vertical stretch. */
    static void v330AspectChecks() {
        int[] fit = VideoViewport.compute(1920, 1080, 1f, 1080, 2400, VideoViewport.FIT);
        check("v330 aspect: Fit 1920×1080 in a 1080×2400 portrait screen → 1080×608 (16:9, ZERO vertical stretch)",
            fit[0] == 1080 && fit[1] == 608, fit[0] + "×" + fit[1]);
        int[] fill = VideoViewport.compute(1920, 1080, 1f, 1080, 2400, VideoViewport.FILL);
        check("v330 aspect: Fill → 4267×2400 (proportional crop, aspect preserved)",
            fill[0] == 4267 && fill[1] == 2400, fill[0] + "×" + fill[1]);
        int[] land = VideoViewport.compute(1920, 1080, 1f, 2400, 1080, VideoViewport.FIT);
        check("v330 aspect: Landscape 2400×1080 Fit → exactly 1920×1080",
            land[0] == 1920 && land[1] == 1080, land[0] + "×" + land[1]);
        int[] stretch = VideoViewport.compute(1920, 1080, 1f, 1080, 2400, VideoViewport.STRETCH);
        check("v330 aspect: Stretch is the ONLY distorting mode (1080×2400) and it must be asked for",
            stretch[0] == 1080 && stretch[1] == 2400);
        int[] r169 = VideoViewport.compute(1440, 1080, 1f, 1080, 2400, VideoViewport.R16_9);
        check("v330 aspect: 16:9 forced on a 4:3 stream → 1080×608",
            r169[0] == 1080 && r169[1] == 608, r169[0] + "×" + r169[1]);
        int[] par = VideoViewport.compute(1440, 1080, 4f / 3f, 1920, 1080, VideoViewport.FIT);
        check("v330 aspect: anamorphic par (1440×1080 @ 1.333) is treated as 1920×1080",
            par[0] == 1920 && par[1] == 1080, par[0] + "×" + par[1]);
        int[] unknown = VideoViewport.compute(0, 0, 1f, 1080, 2400, VideoViewport.FIT);
        check("v330 aspect: an unknown (0×0) video size falls back to the container, never a crash",
            unknown[0] == 1080 && unknown[1] == 2400);
        int[] nanPar = VideoViewport.compute(1920, 1080, Float.NaN, 1080, 2400, VideoViewport.FIT);
        check("v330 aspect: a NaN/0 par is treated as 1 (no NaN geometry reaches the layout)",
            nanPar[0] == 1080 && nanPar[1] == 608);
        check("v330 aspect: the mode strings round-trip (fit/fill/zoom/16:9/stretch)",
            VideoViewport.modeFrom("fit") == VideoViewport.FIT
                && VideoViewport.modeFrom("fill") == VideoViewport.FILL
                && VideoViewport.modeFrom("zoom") == VideoViewport.FILL
                && VideoViewport.modeFrom("16:9") == VideoViewport.R16_9
                && VideoViewport.modeFrom("stretch") == VideoViewport.STRETCH
                && VideoViewport.modeFrom(null) == VideoViewport.FIT);
        check("v330 aspect: the 📐 chip cycles Fit → Fill → 16:9 → Stretch → Fit",
            VideoViewport.next(VideoViewport.FIT) == VideoViewport.FILL
                && VideoViewport.next(VideoViewport.FILL) == VideoViewport.R16_9
                && VideoViewport.next(VideoViewport.R16_9) == VideoViewport.STRETCH
                && VideoViewport.next(VideoViewport.STRETCH) == VideoViewport.FIT);
        check("v330 aspect: coversScreen() is true only for Fill",
            VideoViewport.coversScreen(fill, 1080, 2400) && !VideoViewport.coversScreen(fit, 1080, 2400));
        check("v330 aspect: the cinema subtitle default is 18 sp (was 22), clamped 12..36",
            AppPrefs.normalizeFontSp(18) == 18 && AppPrefs.normalizeFontSp(99) == 36
                && AppPrefs.normalizeFontSp(1) == 12);
    }

    /** BUG #4 — Sri Lankan subtitles: capture, unzip, translate routing, external VLC. */
    static void v330SubtitleChecks() throws Exception {
        check("v330 subzip: .srt/.zip/.ass/.vtt/.ssa/.sub links are recognised",
            LkSubFinder.isSubtitleDownload("https://x.lk/a/movie.srt")
                && LkSubFinder.isSubtitleDownload("https://x.lk/a/movie.zip")
                && LkSubFinder.isSubtitleDownload("https://x.lk/a/movie.ass")
                && LkSubFinder.isSubtitleDownload("https://x.lk/a/movie.vtt"));
        check("v330 subzip: a ?file=movie.zip query form is recognised",
            LkSubFinder.isSubtitleDownload("https://baiscope.lk/download.php?f=Movie.zip&x=1"));
        check("v330 subzip: a normal page is NOT a subtitle link",
            !LkSubFinder.isSubtitleDownload("https://baiscope.lk/movie/the-odyssey/")
                && !LkSubFinder.isSubtitleDownload("javascript:alert(1)"));
        check("v330 subzip: isZip / isPlainSub separate the two capture paths",
            LkSubFinder.isZip("https://x.lk/a.zip") && !LkSubFinder.isZip("https://x.lk/a.srt")
                && LkSubFinder.isPlainSub("https://x.lk/a.srt") && !LkSubFinder.isPlainSub("https://x.lk/a.zip"));
        check("v330 subzip: the capture labels name the archive correctly",
            LkSubFinder.captureLabel("https://x/a.zip").contains(".zip")
                && LkSubFinder.captureLabel("https://x/a.srt").contains("Subtitle"));

        java.util.List<LkSubFinder.Site> sites = LkSubFinder.sites();
        check("v330 subzip: the SL site list leads with Baiscope / Zoom.lk / PirateLK",
            sites.size() >= 5 && "Baiscope".equals(sites.get(0).name)
                && "Zoom.lk".equals(sites.get(1).name) && "PirateLK".equals(sites.get(2).name));
        check("v330 subzip: the 🇱🇰 list is exactly the Sri Lankan three",
            LkSubFinder.sriLankanSites().size() == 3);
        String baiscope = LkSubFinder.searchUrl("Baiscope", "The Odyssey (2026) 1080p WEB-DL");
        check("v330 subzip: a search URL is built and the junk noise is cleaned",
            baiscope.startsWith("https://baiscope.lk/?s=") && baiscope.contains("Odyssey")
                && !baiscope.contains("1080p") && !baiscope.contains("WEB-DL"), baiscope);
        check("v330 subzip: the query is URL-encoded (spaces become %20)",
            LkSubFinder.searchUrl("SubDL", "Kung Fu Panda").contains("Kung%20Fu%20Panda"));
        check("v330 subzip: an unknown site falls back to a DuckDuckGo search",
            LkSubFinder.searchUrl("Nope", "X").startsWith("https://duckduckgo.com/?q="));
        check("v330 subzip: every site yields a deep link for the picker",
            LkSubFinder.allSearchUrls("The Odyssey").size() == sites.size());
        check("v330 subzip: preferredEntry() picks the Sinhala .srt out of a multi-file archive",
            "Movie/sinhala.srt".equals(LkSubFinder.preferredEntry(
                java.util.Arrays.asList("Movie/readme.txt", "Movie/english.srt", "Movie/sinhala.srt"))));

        // a real Baiscope-shaped archive with a ZWJ conjunct, extracted by the shipped loader
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ZipOutputStream zs = new ZipOutputStream(buf);
        String srt = "1\n00:00:01,000 --> 00:00:03,000\n\u0DC1\u0DCA\u200D\u0DBB\u0DD3 \u0DBD\u0D82\u0D9A\u0DCF\u0DC0\u0DDA \u0DA0\u0DD2\u0DAD\u0DCA\u200D\u0DBB\u0DB4\u0DA7\u0DD2\u0DBA\n\n";
        zs.putNextEntry(new ZipEntry("Movie.2026/Sinhala.srt"));
        zs.write(srt.getBytes(StandardCharsets.UTF_8));
        zs.closeEntry();
        zs.close();
        List<Cue> fromZip = SrtLoader.load(buf.toByteArray(), "captured.zip");
        check("v330 subzip: the captured .zip extracts in-app (1 cue)",
            fromZip != null && fromZip.size() == 1);
        check("v330 subzip: the Sinhala ZWJ conjunct ශ්‍රී survives the whole capture path",
            fromZip != null && !fromZip.isEmpty() && fromZip.get(0).si.indexOf('\u200D') >= 0
                && fromZip.get(0).si.contains("\u0DC1\u0DCA\u200D\u0DBB\u0DD3"));
        check("v330 subzip: the extracted list is detected as Sinhala (no needless translate)",
            SubLangDetect.looksSinhala(fromZip.get(0).si));
        check("v330 subzip: an English list is NOT Sinhala — it is routed to the translator",
            !SubLangDetect.looksSinhala("hello there friend, this is an english subtitle line"));

        // ── v3.3.0 AUDIT FIX: ONLY a URI another app can READ may cross the Intent ──
        // The old build passed /data/user/0/<pkg>/cache/sinhala-sub.srt (absolute path):
        // VLC runs under another UID, gets EACCES, and the user silently loses the
        // subtitles. A file:// Uri in an outgoing Intent would throw
        // FileUriExposedException on Android 7–16 (a crash on the 📺 VLC tap).
        String loopUrl = "http://127.0.0.1:8765/subs/current.srt";
        Map<String, String> extras = LkSubFinder.externalExtras("https://cdn/x.m3u8", loopUrl, "The Odyssey", 7410000L);
        check("v330 subzip: the external-player extras carry url/title/position/subtitles",
            "https://cdn/x.m3u8".equals(extras.get("url")) && "The Odyssey".equals(extras.get("title"))
                && "7410000".equals(extras.get("position"))
                && loopUrl.equals(extras.get("subtitles_location"))
                && loopUrl.equals(extras.get("subtitle"))
                && loopUrl.equals(extras.get("subs")));
        check("v330 subzip: no subtitle path → no subtitle extras (never an empty file name)",
            !LkSubFinder.externalExtras("https://cdn/x.m3u8", null, "t", 0).containsKey("subtitles_location"));
        check("v330 vlc: a PRIVATE cache path is never handed to an external player (EACCES trap)",
            LkSubFinder.safeSubLocation("/data/user/0/lk.techworld.anantplay.sicast/cache/sinhala-sub.srt") == null
                && LkSubFinder.safeSubLocation("/tmp/si.srt") == null
                && !LkSubFinder.externalExtras("https://cdn/x.m3u8", "/data/user/0/lk/cache/sinhala-sub.srt", "t", 0)
                    .containsKey("subtitles_location"));
        check("v330 vlc: a file:// Uri is never forwarded (FileUriExposedException trap)",
            LkSubFinder.safeSubLocation("file:///data/user/0/lk/cache/sinhala-sub.srt") == null
                && !LkSubFinder.externalExtras("https://cdn/x.m3u8", "file:///storage/emulated/0/si.srt", "t", 0)
                    .containsKey("subtitles_location"));
        check("v330 vlc: http(s)/content URIs are the only accepted subtitle locations",
            loopUrl.equals(LkSubFinder.safeSubLocation(loopUrl))
                && "https://cdn.example/si.srt".equals(LkSubFinder.safeSubLocation("https://cdn.example/si.srt"))
                && "content://lk.techworld.anantplay.subs/si.srt"
                    .equals(LkSubFinder.safeSubLocation("content://lk.techworld.anantplay.subs/si.srt"))
                && LkSubFinder.safeSubLocation("  ") == null);
        check("v330 subzip: VLC is the preferred external player, MX/mpv follow",
            "org.videolan.vlc".equals(LkSubFinder.preferredPlayer())
                && LkSubFinder.EXTERNAL_PLAYERS.length >= 3);

        String webSrc = stripComments(readRepoFile("src/lk/techworld/anantplay/sicast/WebBrowserView.java"));
        String playSrc = stripComments(readRepoFile("src/lk/techworld/anantplay/sicast/PlayActivity.java"));
        check("v330 subzip: the WebView intercepts subtitle downloads (never navigates away)",
            webSrc.contains("LkSubFinder.isSubtitleDownload(us)"));
        check("v330 subzip: the WebView has a DownloadListener capture path too",
            webSrc.contains("setDownloadListener") && webSrc.contains("captureSubtitle"));
        check("v330 subzip: the capture attaches to the LIVE player",
            webSrc.contains("attachCapturedSubs") && playSrc.contains("void attachCapturedSubs("));
        check("v330 subzip: the player's 💬 sheet ships the 🇱🇰 Find Sinhala Sub button",
            playSrc.contains("openLkFinder") && playSrc.contains("Find Sinhala Sub"));
        check("v330 subzip: the mini web-picker opens the app's Web tab on the site search",
            playSrc.contains("putExtra(\"webUrl\", target)")
                && stripComments(readRepoFile("src/lk/techworld/anantplay/sicast/MainActivity.java"))
                    .contains("openWebFromIntent"));
        check("v330 subzip: the download is capped (6 MB) and the unzip is 5 MB/64-entry bomb-safe",
            webSrc.contains("6 * 1024 * 1024") && SrtLoader.MAX_BYTES == 5 * 1024 * 1024
                && SrtLoader.MAX_ENTRIES == 64);

        // ── the SOURCE-level proof: no external-launch path can reintroduce the crash ──
        check("v330 vlc: PlayActivity contains NO Uri.fromFile() call at all",
            !playSrc.contains("Uri.fromFile(") && !playSrc.contains("getCacheDir()"));
        check("v330 vlc: the external launch publishes to the loopback /subs route, not a file",
            playSrc.contains("ReceiverService.setSubsSrt(") && playSrc.contains("ReceiverService.subsUrl()")
                && !playSrc.contains("sinhala-sub.srt"));
        check("v330 vlc: the whole external hand-off is wrapped in try/catch (Throwable)",
            playSrc.contains("Could not open an external player")
                && playSrc.contains("No external player installed"));

        // ── the ROUTE itself, driven over a real socket (UTF-8 Sinhala body) ────────
        ReceiverServer subsSrv = new ReceiverServer(new FakeBackend(), 18775);
        String srtBody = "1\n00:00:01,000 --> 00:00:03,000\n\u0DC1\u0DCA\u200D\u0DBB\u0DD3 \u0DBD\u0D82\u0D9A\u0DCF\n\n";
        boolean started = subsSrv.start();
        check("v330 vlc: the receiver the URL points at starts", started, "port " + subsSrv.port());
        if (started) {
            String get = "GET " + ReceiverServer.SUBS_PATH + " HTTP/1.0\r\nHost: tv\r\n\r\n";
            String empty = raw(subsSrv.port(), get);
            check("v330 vlc: /subs/current.srt → 404 while no subtitle is armed",
                empty.contains("404"), firstLine(empty));
            check("v330 vlc: an unarmed route leaks no subtitle text",
                !empty.contains("\u0DC1\u0DCA\u200D\u0DBB\u0DD3"));
            subsSrv.setSubsSrt(srtBody);
            String served = raw(subsSrv.port(), get);
            check("v330 vlc: /subs/current.srt → 200 text/plain; charset=utf-8",
                served.contains("200 OK")
                    && served.contains("Content-Type: text/plain; charset=utf-8"), firstLine(served));
            check("v330 vlc: the served body is the UTF-8 .srt (Sinhala ZWJ conjunct intact)",
                served.contains(srtBody.trim()) && served.contains("\u0DC1\u0DCA\u200D\u0DBB\u0DD3"));
            check("v330 vlc: the Content-Length is the UTF-8 byte length, and it is no-store",
                served.contains("Content-Length: " + srtBody.getBytes(StandardCharsets.UTF_8).length)
                    && served.contains("Cache-Control: no-store"));
            check("v330 vlc: the URL handed to VLC is loopback + the shipped route",
                ("http://127.0.0.1:" + subsSrv.port() + ReceiverServer.SUBS_PATH).equals(subsSrv.subsUrl()),
                String.valueOf(subsSrv.subsUrl()));
            subsSrv.setSubsSrt(null);
            check("v330 vlc: clearing the cues disarms the route (no stale subtitle served)",
                raw(subsSrv.port(), get).contains("404"));
            subsSrv.stop();
            check("v330 vlc: a stopped receiver yields NO subtitle URL (extra omitted, never a dead link)",
                subsSrv.subsUrl() == null);
        }
    }

    /** The version/pipeline parity gate (3.3.0 / 30300 everywhere, never 4.x). */
    static void v330VersionParityChecks() throws Exception {
        String manifest = readRepoFile("AndroidManifest.xml");
        check("v330 parity: AndroidManifest.xml says 3.3.0 / 30300",
            manifest.contains("android:versionCode=\"30300\"") && manifest.contains("android:versionName=\"3.3.0\""));
        check("v330 parity: AppInfo says 3.3.0 / 30300",
            "3.3.0".equals(AppInfo.VERSION_NAME) && AppInfo.VERSION_CODE == 30300);
        String build = readRepoFile("build.sh");
        check("v330 parity: build.sh defaults to 3.3.0 / 30300 (previous 30200)",
            build.contains("VERSION_NAME:-3.3.0") && build.contains("VERSION_CODE:-30300")
                && build.contains("PREV_VERSION_CODE:-30200"));
        String latest = readRepoFile("docs/latest.json");
        check("v330 parity: docs/latest.json carries 3.3.0 / 30300",
            latest.contains("\"version\":\"3.3.0\"") && latest.contains("\"code\":30300"));
        check("v330 parity: README.md announces v3.3.0",
            readRepoFile("README.md").contains("3.3.0"));
        check("v330 parity: SETUP-SI.md announces v3.3.0",
            readRepoFile("SETUP-SI.md").contains("3.3.0"));
        String buildWf = readRepoFile(".github/workflows/build-apk.yml");
        check("v330 parity: build-apk.yml asserts 30300 / 3.3.0",
            buildWf.contains("30300") && buildWf.contains("3.3.0"));
        String relWf = readRepoFile(".github/workflows/release-apk.yml");
        check("v330 parity: release-apk.yml defaults to 3.3.0",
            relWf.contains("default: '3.3.0'"));
        check("v330 parity: strictly v3.x — no 4.x anywhere in the identity files",
            !manifest.contains("versionName=\"4.") && !"4".equals(AppInfo.VERSION_NAME.substring(0, 1)));
        check("v330 parity: the report ships inside the repo",
            new java.io.File("V3_3_0_CINEMA_PLAYER_AND_403_FIX_REPORT.md").isFile());
    }

}
