package lk.techworld.anantplay.sicast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/* The AnantPlay-Cast receiver: an HTTP server that speaks the EXACT protocol of
 * official AnantPlay's own TV receiver (AnantPlay/android Cast.java @ be74df2):
 *
 *   POST /play            body = JSON of the phone player's intent (url, headers, title,
 *                         at, cookie, live, subs[], season, episode, imdb, mime, …)
 *                         → {"ok":true} once our player opened, {"ok":false,"why":…}
 *   GET  /status          → {"title","pos","dur","playing","live"} | {"loading":true} | {"idle":true}
 *   POST /do?a=…&to=ms    → toggle | seek | stop | vol+ | vol-
 *   GET  /info            → ours: diagnostics for the app's Self-test (AnantPlay ignores it)
 *
 * Byte-compatible with the sender: AnantPlay's Cast.answer() hand-parses the request line
 * and Content-Length header the same way we hand-write the response
 * ("HTTP/1.0 200 OK", Content-Type: application/json, Content-Length).
 *
 * Pure java.net only (no Android classes) — the verification lab drives this exact code
 * on a bare JVM, with a fake Backend, exactly like AnantPlay's own DlnaTest drives Dlna.
 *
 * SECURITY (stronger than AnantPlay's own TV receiver, which accepts any LAN source):
 * /play, /do and /status are only answered when the TCP source address is THIS device
 * (loopback or one of our own interface addresses). AnantPlay connects from the same
 * phone, so its source is always local; any other phone on the Wi-Fi is refused —
 * a stranger on public Wi-Fi cannot hijack playback, and the refusal carries no data
 * about the current stream.
 *
 * Hardening applied in this file (audit Gate 2):
 *   · 256 KB body cap (AnantPlay's own, Cast.java:110) + a hard per-connection
 *     concurrency cap so a LAN peer cannot exhaust the phone with sockets;
 *   · Content-Length parsed defensively (negative/huge/garbage → silent drop, never
 *     an exception storm) and every header compared locale-independently;
 *   · responses are hand-written with fixed headers only — client text can never
 *     inject a header (bodies are JSON-escaped via MiniJson);
 *   · /do actions are whitelisted and seek targets clamped (CastPolicy). */
public final class ReceiverServer {

    public static final int PREFERRED_PORT = 8765;   // AnantPlay's convention (Cast.java:58)
    public static final int MAX_BODY = 256 * 1024;   // AnantPlay's own cap (Cast.java:110)

    /** What the server needs from its host. The Android service implements it with the
     *  real player; the lab implements it with a fake — the protocol code never changes. */
    public interface Backend {
        /** Handle a parsed /play body (already JSON-parsed). May block up to ~5 s
         *  (mirrors Cast.play's 50 × 100 ms wait). Returns the response JSON body. */
        String onPlay(Map<String, Object> body, String sourceAddr);
        /** toggle | seek | stop | vol+ | vol- ; returns the response JSON body. */
        String onAction(String action, long to);
        /** The current /status body (never blocks: read from a volatile snapshot). */
        String status();
        /** Extra fields for /info (server adds uptime, port, counters itself). */
        Map<String, Object> info();
    }

    private final Backend backend;
    private ServerSocket server;
    private Thread acceptThread;
    private volatile boolean running;
    private volatile int port = -1;
    private final int preferredPort;
    private long startedAt;
    private final AtomicLong served = new AtomicLong();
    private final AtomicLong plays = new AtomicLong();
    /** v3.0.0 — the DLNA MediaRenderer endpoints (/dlna/*). Null = not mounted
     *  (the lab's plain cast tests run exactly as before). */
    private volatile DlnaEndpoint dlna;
    /** At most 12 in-flight requests: AnantPlay sends one at a time; a flood is dropped. */
    private static final int MAX_INFLIGHT = 12;
    private final AtomicInteger inFlight = new AtomicInteger();

    /** v3.0.0 — mount the DLNA MediaRenderer (ReceiverService calls this at arm). */
    public void setDlna(DlnaEndpoint dlna) { this.dlna = dlna; }
    /** The mounted DLNA endpoint (null when none) — for /info diagnostics. */
    public DlnaEndpoint dlna() { return dlna; }

    public ReceiverServer(Backend backend) { this(backend, PREFERRED_PORT); }

    /** Lab variant: start on a chosen port instead of the AnantPlay default. */
    public ReceiverServer(Backend backend, int preferredPort) {
        this.backend = backend;
        this.preferredPort = preferredPort;
    }

    /** The port we actually listen on (PREFERRED_PORT, else the next free one). */
    public int port() { return port; }
    public boolean isRunning() { return running; }

    /** Bind and serve until stop(). Returns false when no port could be bound. */
    public synchronized boolean start() {
        if (running) return true;
        for (int p = preferredPort; p < preferredPort + 25; p++) {
            try {
                server = new ServerSocket(p, 8, InetAddress.getByName("0.0.0.0"));
                port = p;
                break;
            } catch (IOException e) {
                Diag.w("server", "port " + p + " busy: " + e.getMessage());
            }
        }
        if (port < 0) { Diag.e("server", "no free port"); return false; }
        running = true;
        startedAt = System.currentTimeMillis();
        acceptThread = new Thread(this::serve, "si-receiver");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Diag.i("server", "listening on 0.0.0.0:" + port);
        return true;
    }

    public synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        server = null;
        port = -1;
        Diag.i("server", "stopped");
    }

    private void serve() {
        while (running) {
            try {
                Socket s = server.accept();
                if (inFlight.incrementAndGet() > MAX_INFLIGHT) {
                    inFlight.decrementAndGet();
                    Diag.w("server", "too many in-flight requests — dropping " + s.getInetAddress());
                    try { s.close(); } catch (IOException ignored) { }
                    continue;
                }
                Thread t = new Thread(() -> {
                    try { answer(s); } finally { inFlight.decrementAndGet(); }
                }, "si-req");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) Diag.w("server", "accept: " + e.getMessage());
            }
        }
    }

    /** One request — mirrors Cast.answer() (Cast.java:103-126) line by line, plus
     *  the v3.0.0 /dlna/* MediaRenderer routes (same local-device-only policy:
     *  AnantPlay's DLNA control always comes from this phone, via its relay). */
    void answer(Socket s) {
        long t0 = System.currentTimeMillis();
        String route = "?", verdict = "ok";
        try (Socket sock = s) {
            sock.setSoTimeout(10000);
            InputStream in = sock.getInputStream();
            String[] req = line(in).split(" ");
            int len = 0;
            String soapAction = null;   // v3.0.0: the DLNA control POSTs carry it
            for (String h; !(h = line(in)).isEmpty(); ) {
                String hl = h.toLowerCase(Locale.US);
                if (!hl.startsWith("content-length:")) {
                    // capture the SOAPAction header for /dlna/* (case-insensitive value kept raw)
                    if (hl.startsWith("soapaction:") && h.length() > 11) soapAction = h.substring(11).trim();
                    continue;
                }
                try {
                    long v = Long.parseLong(hl.substring(15).trim());
                    if (v < 0 || v > MAX_BODY) len = -1;       // mark as too large / invalid
                    else len = (int) v;
                } catch (NumberFormatException e) {
                    len = -1;
                }
            }
            if (req.length < 2 || len < 0) return;   // same silent drop as Cast.answer
            byte[] body = new byte[len];
            for (int n = 0; n < len; ) {
                int r = in.read(body, n, len - n);
                if (r < 0) break;
                n += r;
            }
            // "http://tv" + req[1] — AnantPlay's trick to get Uri.parse to split path/query
            URI u;
            String raw = req[1];
            try { u = URI.create("http://tv" + raw); }
            catch (IllegalArgumentException e) { raw = "/bad"; u = URI.create("http://tv/bad"); }
            String path = u.getPath() == null ? "" : u.getPath();
            route = req.length > 0 ? req[0] + " " + path : "?";

            boolean local = isSelf(sock.getInetAddress());
            String out;
            String contentType = "application/json";
            int status = 200;
            DlnaEndpoint d = dlna;
            if (!local) {
                verdict = "refused-nonlocal";
                out = "{\"ok\":false,\"why\":\"local-device-only\"}";
            } else if (d != null && path.startsWith("/dlna/")) {
                // v3.0.0 — the DLNA MediaRenderer surface (desc.xml + AVTransport/RC
                // SOAP). The LOCATION the client used is rebuilt from the socket's
                // own local address so the control URLs match how it reached us.
                String baseUrl = "http://" + sock.getLocalAddress().getHostAddress() + ":" + port;
                DlnaEndpoint.Response r = d.handle(path, req[0], soapAction,
                    body.length == 0 ? "" : new String(body, StandardCharsets.UTF_8), baseUrl);
                if (r == null) {
                    verdict = "dlna-404";
                    status = 404;
                    contentType = "text/xml; charset=utf-8";
                    out = DlnaEndpoint.soapFault("no such DLNA resource: " + path);
                } else {
                    verdict = "dlna-" + req[0] + " " + path + " → " + r.code;
                    status = r.code;
                    contentType = r.contentType;
                    out = r.body;
                }
            } else if (path.equals("/play") && req[0].equals("POST")) {
                plays.incrementAndGet();
                Map<String, Object> j = null;
                try { j = Cue.statusOf(new String(body, StandardCharsets.UTF_8)); }
                catch (Exception e) { Diag.w("play", "bad json: " + e); }
                if (j == null) { verdict = "bad-json"; out = "{\"ok\":false,\"why\":\"bad json\"}"; }
                else {
                    String why = backend.onPlay(j, sock.getInetAddress().getHostAddress());
                    if (why != null && why.contains("\"ok\":false")) verdict = "play-refused";
                    out = why == null ? okJson() : why;
                }
            } else if (path.equals("/status")) {
                out = backend.status();
            } else if (path.equals("/do") && req[0].equals("POST")) {
                String a = param(u.getRawQuery(), "a"), toS = param(u.getRawQuery(), "to");
                long to = 0;
                try { to = Long.parseLong(toS == null ? "0" : toS); } catch (NumberFormatException ignored) { }
                if (!CastPolicy.isAllowedAction(a)) {           // whitelist, never a passthrough
                    verdict = "bad-action";
                    out = "{\"ok\":false}";
                } else {
                    out = backend.onAction(a, CastPolicy.clampSeek(to));
                }
            } else if (path.equals("/info")) {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("ok", Boolean.TRUE);
                m.put("app", AppInfo.NAME);
                m.put("version", AppInfo.VERSION_NAME);
                m.put("port", port);
                m.put("uptimeMs", System.currentTimeMillis() - startedAt);
                m.put("requests", served.get());
                m.put("plays", plays.get());
                Map<String, Object> extra = backend.info();
                if (extra != null) m.putAll(extra);
                out = MiniJson.write(m);
            } else {
                verdict = "unknown";
                out = "{\"ok\":false}";
            }
            served.incrementAndGet();
            OutputStream o = sock.getOutputStream();
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            o.write(("HTTP/1.0 " + status + " " + reason(status) + "\r\nContent-Type: " + contentType + "\r\nContent-Length: " + b.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            o.write(b);
            o.flush();
            Diag.i("http", route + " → " + verdict + " (" + (System.currentTimeMillis() - t0) + " ms, " + b.length + " B)");
        } catch (Exception e) {
            Diag.w("http", route + " failed: " + e);
        }
    }

    /** Cast.line (Cast.java:128-132): byte-wise line, CRLF stripped, 16 KB cap. */
    static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c; (c = in.read()) != -1 && c != '\n'; )
            if (c != '\r' && b.size() < 16384) b.write(c);
        return b.toString("UTF-8");
    }

    /** The reason phrase for the hand-written status line (AnantPlay's parser only
     *  reads the code; HttpURLConnection.getInputStream() throws on >= 400 — the
     *  exact behaviour its Dlna.soap() treats as a failed action). */
    static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 500: return "Internal Server Error";
            case 501: return "Not Implemented";
            default: return "Status";
        }
    }

    static String param(String query, String key) {
        if (query == null) return null;
        for (String p : query.split("&")) {
            int eq = p.indexOf('=');
            String k = eq < 0 ? p : p.substring(0, eq);
            if (k.equals(key)) {
                String v = eq < 0 ? "" : p.substring(eq + 1);
                try { return java.net.URLDecoder.decode(v, "UTF-8"); }
                catch (Exception e) { return v; }
            }
        }
        return null;
    }

    /** True when the address belongs to this device (loopback or any interface address). */
    static boolean isSelf(InetAddress a) {
        if (a == null) return false;
        if (a.isLoopbackAddress()) return true;
        if (a.isAnyLocalAddress()) return true;
        // An IPv6 socket reports an IPv4 client as ::ffff:a.b.c.d — normalise it, otherwise
        // a same-device cast over a v6 socket would be refused on some vendor stacks.
        byte[] raw = a.getAddress();
        if (raw != null && raw.length == 16) {
            boolean mapped = true;
            for (int i = 0; i < 10; i++) if (raw[i] != 0) { mapped = false; break; }
            if (mapped && (raw[10] & 0xFF) == 0xFF && (raw[11] & 0xFF) == 0xFF) {
                try {
                    InetAddress v4 = InetAddress.getByAddress(new byte[]{raw[12], raw[13], raw[14], raw[15]});
                    if (v4.isLoopbackAddress()) return true;
                    a = v4;
                } catch (Exception ignored) { }
            }
        }
        try {
            for (Enumeration<NetworkInterface> ns = NetworkInterface.getNetworkInterfaces(); ns.hasMoreElements(); ) {
                NetworkInterface n = ns.nextElement();
                if (n == null) continue;
                for (InetAddress i : Collections.list(n.getInetAddresses()))
                    if (i.equals(a)) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    /** The volatile /status snapshot the player keeps fresh (never blocks the server). */
    public static String statusJson(String title, long pos, long dur, boolean playing, boolean live) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"title\":").append(MiniJson.quote(title == null ? "" : title));
        sb.append(",\"pos\":").append(pos).append(",\"dur\":").append(live ? 0 : dur);
        sb.append(",\"playing\":").append(playing).append(",\"live\":").append(live).append("}");
        return sb.toString();
    }

    public static String loadingJson() { return "{\"loading\":true}"; }
    public static String idleJson() { return "{\"idle\":true}"; }
    public static String okJson() { return "{\"ok\":true}"; }
    public static String whyJson(String why) { return "{\"ok\":false,\"why\":" + MiniJson.quote(why) + "}"; }

    /** The string AnantPlay's Remote shows for the seekbar total ("h:mm:ss" like clock()). */
    public static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return s / 3600 + ":" + String.format(java.util.Locale.US, "%02d:%02d", s / 60 % 60, s % 60);
    }
}
