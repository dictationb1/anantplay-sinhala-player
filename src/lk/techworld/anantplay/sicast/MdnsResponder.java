package lk.techworld.anantplay.sicast;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.Locale;

/* v2.3.0 — THE PURE-JAVA mDNS RESPONDER (RFC 6762), the zero-router fix.
 *
 * WHY THIS EXISTS (the field failure, Xiaomi HyperOS + old router, 2026-11-22):
 *   The v2.2.0 Self-test sent a QU PTR question to 224.0.0.251:5353 from a PLAIN
 *   ephemeral DatagramSocket (NsdAnnouncer.java:257) and waited for a unicast
 *   reply. On a network where the old router does not reflect multicast back to
 *   the sender (multicast snooping without an IGMP querier, or AP isolation), the
 *   question never reached ANY responder — not even the phone's own mdnsd, which
 *   HOLDS our NsdManager registration — so nothing answered: "mDNS FAILED
 *   (no-answer)". The test probed the wrong path: real AnantPlay discovery is
 *   NsdManager binder IPC into the same mdnsd (daemon cache, no router involved).
 *
 * WHAT THIS CLASS DOES — two jobs, both pure Java (the lab drives them on a bare
 * JVM with multicast loopback, no Android, no router):
 *
 *   JOB 1 — THE RESPONDER: a MulticastSocket bound to UDP 5353 with
 *     SO_REUSEADDR (setReuseAddress(true) — legal for a non-root app on Android
 *     14–16; system mdnsd binds with reuse too, and multiple 5353 listeners
 *     coexist; EADDRINUSE or a bind failure is caught and DEGRADES GRACEFULLY:
 *     NsdManager registration stays the announcement, only the extra responder
 *     is skipped). It joins 224.0.0.251 on the active interface, holds the
 *     caller's MulticastLock, and answers:
 *       · PTR?  _anantplay._tcp.local  → PTR <name>.local + SRV + TXT + A/additional
 *       · PTR?  _fmhyplay._tcp.local   → the same records under the legacy type
 *       · SRV/ANY? <instance>.local    → our SRV + TXT + A
 *     with a UNICAST reply to the querier's own address:port when the QU bit is
 *     set (RFC 6762 §5.4), else to the group. Unicast replies traverse routers
 *     that only filter multicast — and between two of OUR sockets on the same
 *     phone they never leave the kernel.
 *
 *   JOB 2 — THE ROUTER-FREE SELF-PROBE: probeLocal() joins the group on its own
 *     MulticastSocket and asks the question. The kernel's multicast loopback
 *     (IP_MULTICAST_LOOP, ON by default; we ALSO call setLoopbackMode(false) —
 *     in Java's inverted semantics FALSE means loopback ENABLED, the exact call
 *     the audit asked for) delivers the question to our responder socket on the
 *     SAME interface, 100% inside the phone's Linux kernel. The responder's
 *     unicast reply comes back to the probe socket. NO ROUTER REFLECTION NEEDED.
 *     This is the honest layer-1 proof: "our service answers on the wire".
 *
 *   The response builder (responsePacket) is deliberately deterministic — the lab
 *   proves the exact bytes (header flags 0x8400 authoritative-answer, our PTR/
 *   SRV/TXT/A records, correct name compression via the shared name-writer) so a
 *   phone capture can be diffed against the lab's bytes. */
public final class MdnsResponder {

    public static final String MDNS_GROUP = "224.0.0.251";
    public static final int MDNS_PORT = 5353;

    private final String serviceType;     // "_anantplay._tcp"
    private final String legacyType;      // "_fmhyplay._tcp" (or "" to disable)
    private final String instanceName;    // e.g. "AnantPlay Sinhala Player" (v2.4.0: DIFFERENT from the NsdManager name!)
    private final int port;               // the receiver's TCP port
    private final InetAddress host;       // the interface address to advertise
    /** v2.4.0 — the SRV target host (<hostName>.local.). RFC 6763 §4.3 requires a
     *  proper host name, and Android's MdnsResponseDecoder (Loop 3,
     *  MdnsResponseDecoder.java:248-255) attaches an A record to a service ONLY
     *  when the A owner name equals the SRV target. v2.3.x put the bare instance
     *  name as SRV target and the full service name as A owner — the two never
     *  matched, the address never attached, isComplete() stayed false and official
     *  AnantPlay ignored every answer (the 2026-11-22 13:16 Android 16 log). */
    private final String hostName;        // e.g. "Sinhala-Player-a1b2"

    private MulticastSocket socket;
    private Thread worker;
    private volatile boolean running;
    private volatile boolean portShared;  // true when 5353 bound OK alongside mdnsd
    private volatile long answered, queriesSeen;

    public MdnsResponder(String serviceType, String legacyType, String instanceName,
                         int port, InetAddress host) {
        this.serviceType = serviceType;
        this.legacyType = legacyType == null ? "" : legacyType;
        this.instanceName = instanceName;
        this.port = port;
        this.host = host;
        this.hostName = makeHostName();
    }

    /** v2.4.0 — a stable, unique, DNS-label-safe host name derived from the
     *  advertised IP (deterministic: same IP → same host name, so the lab proves
     *  the exact bytes). ASCII-only, ≤ 63 bytes, no dots — a legal single label. */
    private String makeHostName() {
        String ip = host == null ? "0.0.0.0" : host.getHostAddress();
        int h = Math.abs(ip.hashCode());
        return String.format(Locale.ROOT, "Sinhala-Player-%04x", h & 0xFFFF);
    }

    /** The advertised SRV target ("<hostName>.local"). */
    public String hostLabel() { return hostName; }

    public boolean isRunning() { return running; }
    /** True when we actually share UDP 5353 (false = mdnsd refused / EADDRINUSE — degraded). */
    public boolean isPortShared() { return portShared; }
    public long answeredCount() { return answered; }
    public long queriesSeen() { return queriesSeen; }
    /** The service types this responder answers for (probe validation). */
    public String serviceType() { return serviceType; }
    public String legacyType() { return legacyType; }

    /** Bind + join + serve. Returns false when 5353 could not be shared (caller
     *  keeps the NsdManager path; only the extra responder is missing). */
    public synchronized boolean start() {
        if (running) return true;
        try {
            socket = new MulticastSocket(null);
            socket.setReuseAddress(true);          // coexist with mdnsd's 5353
            socket.bind(new InetSocketAddress(MDNS_PORT));
            portShared = true;
        } catch (Throwable t) {
            // EADDRINUSE or a vendor block: degrade gracefully, never crash
            Diag.w("mdnsrsp", "cannot share UDP 5353 (" + t + ") — NsdManager registration stays the only announcer");
            try { if (socket != null) socket.close(); } catch (Throwable ignored) { }
            socket = null;
            portShared = false;
            running = false;
            return false;
        }
        try {
            InetAddress group = InetAddress.getByName(MDNS_GROUP);
            NetworkInterface onIface = ifaceOf(host);
            if (onIface != null) socket.joinGroup(new InetSocketAddress(group, 0), onIface);
            else socket.joinGroup(group);          // fallback: the deprecated default join
            joinedIface = onIface;
            running = true;
            worker = new Thread(this::serve, "si-mdns-rsp");
            worker.setDaemon(true);
            worker.start();
            Diag.i("mdnsrsp", "responder up on 5353 for " + serviceType + " (instance \"" + instanceName + "\" :" + port
                + (onIface != null ? ", iface " + onIface.getName() : "") + ")");
            return true;
        } catch (Throwable t) {
            Diag.w("mdnsrsp", "joinGroup failed: " + t);
            stop();
            return false;
        }
    }

    /** The interface the advertised address belongs to — but only when that
     *  interface actually carries multicast (a loopback iface on some kernels
     *  accepts joinGroup then blackholes every packet, which is exactly the
     *  silent failure this whole feature exists to avoid). Falls back to the
     *  first up, non-loopback, multicast-capable interface. */
    private static NetworkInterface ifaceOf(InetAddress host) {
        try {
            if (host != null) {
                NetworkInterface n = NetworkInterface.getByInetAddress(host);
                if (n != null && !n.isLoopback() && n.supportsMulticast() && n.isUp()) return n;
            }
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (n.isUp() && !n.isLoopback() && n.supportsMulticast()) return n;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** The interface we joined (for leaveGroup symmetry; null when the default join was used). */
    private NetworkInterface joinedIface;

    public synchronized void stop() {
        running = false;
        if (worker != null) { try { worker.interrupt(); } catch (Throwable ignored) { } worker = null; }
        if (socket != null) {
            try {
                InetAddress group = InetAddress.getByName(MDNS_GROUP);
                if (joinedIface != null) socket.leaveGroup(new InetSocketAddress(group, 0), joinedIface);
                else socket.leaveGroup(group);
            } catch (Throwable ignored) { }
            try { socket.close(); } catch (Throwable ignored) { }
            socket = null;
        }
        joinedIface = null;
        Diag.i("mdnsrsp", "responder down (answered " + answered + " queries)");
    }

    /** v2.3.1 — log aggregation state: the field log showed 16 identical
     *  "answered a unicast(QU) query" lines inside 0.4 s (the spinning v2.3.0
     *  probes re-sent at full speed because their mojibake match never
     *  succeeded). Even with the probes fixed, a busy network can carry many
     *  queries per second — the diagnostics ring must never be flooded. */
    private String lastLogSource = "";
    private int suppressedSinceLog;
    private long lastLogAt;

    private void serve() {
        byte[] buf = new byte[9000];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                byte[] in = new byte[p.getLength()];
                System.arraycopy(buf, 0, in, 0, p.getLength());
                queriesSeen++;
                byte[] out = answerIfForUs(in);
                if (out != null && out.length > 0) {
                    SocketAddress to = hasQuBit(in)
                        ? new InetSocketAddress(p.getAddress(), p.getPort())   // unicast back (RFC 6762 §5.4)
                        : new InetSocketAddress(InetAddress.getByName(MDNS_GROUP), MDNS_PORT);
                    socket.send(new DatagramPacket(out, out.length, to));
                    answered++;
                    logAnswer(p.getAddress().getHostAddress() + ":" + p.getPort(), hasQuBit(in));
                }
            } catch (Throwable t) {
                if (running) Diag.w("mdnsrsp", "serve: " + t);
            }
        }
    }

    /** One Diag line per source per second at most; repeats collapse into
     *  "… (×N in 1 s)". Called on the serve thread only. */
    private void logAnswer(String source, boolean qu) {
        long now = System.currentTimeMillis();
        if (source.equals(lastLogSource) && now - lastLogAt < 1000) {
            suppressedSinceLog++;
            return;
        }
        StringBuilder line = new StringBuilder("answered a ").append(qu ? "unicast(QU)" : "multicast")
            .append(" query from ").append(source);
        if (suppressedSinceLog > 0) {
            line.append(" … (×").append(suppressedSinceLog).append(" in 1 s)");
            suppressedSinceLog = 0;
        }
        Diag.i("mdnsrsp", line.toString());
        lastLogSource = source;
        lastLogAt = now;
    }

    // ── the query parser (enough RFC-6762/1035 to know a question is ours) ─────

    /** True when the packet is a QUERY (not a response) whose QUESTION section
     *  carries the QU top bit in a QCLASS field (RFC 6762 §5.4: "unicast the
     *  reply"). We scan every question's QCLASS byte-pair for the top bit. */
    static boolean hasQuBit(byte[] q) {
        if (q == null || q.length < 12) return false;
        if ((q[2] & 0x80) != 0) return false;               // a response, not a query
        int qd = (((q[4] & 0xFF) << 8) | (q[5] & 0xFF)) & 0x7FFF;
        int pos = 12;
        for (int i = 0; i < qd && pos < q.length; i++) {
            int[] end = new int[1];
            String[] name = readName(q, pos, end);          // skip the (possibly compressed) name
            pos = end[0];
            if (pos + 4 > q.length) return false;
            int qclassHi = q[pos + 2] & 0xFF;               // QCLASS high byte: the QU bit
            if ((qclassHi & 0x80) != 0) return true;
            pos += 4;
        }
        return false;
    }

    /** The answer payload when this query asks about our service (else null).
     *
     *  v2.4.0 — THE SABOTAGE FIX (the 2026-11-22 13:16 Android 16 log, "announced":
     *  false for 35 s): v2.3.x also answered QTYPE=255 (ANY) questions on
     *  <instance>.<type>.local — but that is EXACTLY the shape of an RFC 6762 §8.1
     *  PROBE (MdnsProber.java:78-95 builds MdnsAnyRecord questions; the proposed
     *  records ride in AUTHORITY). Our answers carrying a same-named SRV record
     *  always counted as CONFLICT in MdnsRecordRepository.getConflictingServices
     *  (MdnsRecordRepository.java:1196, 1263-1290 — IDENTICAL needs equal rdata;
     *  ours differs), so Android 14–16's MdnsAdvertiser renamed the NsdManager
     *  registration over and over and NEVER fired onServiceRegistered. Now:
     *   · ANY questions are NEVER answered (correct: a probe is not a query);
     *   · the responder's instance name differs from the NsdManager name, so even
     *     a future bug in the filter cannot collide with the daemon's probes.
     *  Matches: PTR on <type>.local (browse); SRV/TXT on <instance>.<type>.local;
     *  A/AAAA on <hostName>.local (the SRV target — RFC 6763 §4.3, so a resolver's
     *  address follow-up is answered too). */
    byte[] answerIfForUs(byte[] q) {
        try {
            if (q == null || q.length < 12) return null;
            if ((q[2] & 0x80) != 0) return null;              // a response, not a query
            int qd = ((q[4] & 0xFF) << 8) | (q[5] & 0xFF);
            // the QU bit occupies the top bit of QDCOUNT — mask it off for the count
            qd = qd & 0x7FFF;
            if (qd == 0 || qd > 4) return null;               // 0 = probe packet, >4 = junk
            int pos = 12;
            boolean ours = false;
            for (int i = 0; i < qd && pos < q.length; i++) {
                int[] nameEnd = new int[1];
                String[] name = readName(q, pos, nameEnd);
                pos = nameEnd[0];
                if (pos + 4 > q.length) return null;
                int qtype = ((q[pos] & 0xFF) << 8) | (q[pos + 1] & 0xFF);
                pos += 4;                                     // qtype + qclass
                if (name == null) continue;
                String full = joinName(name);
                String lower = full.toLowerCase(Locale.ROOT);
                if (qtype == 255) continue;                   // §8.1 probe / ANY: NEVER answer (v2.4.0)
                if (qtype == 12) {                            // PTR: browse for the service type
                    String want1 = (serviceType + ".local").toLowerCase(Locale.ROOT);
                    String want2 = legacyType.isEmpty() ? "" : (legacyType + ".local").toLowerCase(Locale.ROOT);
                    if (lower.equals(want1) || lower.equals(want2)) ours = true;
                }
                if (qtype == 33 || qtype == 16) {              // SRV / TXT on the instance
                    String inst = (instanceName + "." + serviceType + ".local").toLowerCase(Locale.ROOT);
                    String instLegacy = legacyType.isEmpty() ? "" : (instanceName + "." + legacyType + ".local").toLowerCase(Locale.ROOT);
                    if (lower.equals(inst) || lower.equals(instLegacy)) ours = true;
                }
                if (qtype == 1 || qtype == 28) {               // A / AAAA on the SRV target host
                    String h = (hostName + ".local").toLowerCase(Locale.ROOT);
                    if (lower.equals(h)) ours = true;
                }
            }
            return ours ? responsePacket(q) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Read one (possibly compressed) DNS name → labels; follows compression pointers
     *  once (RFC 1035 §4.1.4). nameEnd[0] = position AFTER the name in the original. */
    private static String[] readName(byte[] p, int at, int[] nameEnd) {
        java.util.List<String> labels = new java.util.ArrayList<>();
        int pos = at, jumps = 0, end = -1;
        while (true) {
            if (pos >= p.length || jumps > 4) return null;
            int len = p[pos] & 0xFF;
            if (len == 0) {
                if (end < 0) end = pos + 1;
                break;
            }
            if ((len & 0xC0) == 0xC0) {                       // a compression pointer
                if (pos + 1 >= p.length || jumps >= 4) return null;
                if (end < 0) end = pos + 2;
                pos = ((len & 0x3F) << 8) | (p[pos + 1] & 0xFF);
                jumps++;
                continue;
            }
            if (len > 63 || pos + 1 + len > p.length) return null;
            labels.add(new String(p, pos + 1, len, java.nio.charset.StandardCharsets.UTF_8));
            pos += 1 + len;
        }
        nameEnd[0] = end < 0 ? pos : end;
        return labels.toArray(new String[0]);
    }

    private static String joinName(String[] labels) {
        StringBuilder sb = new StringBuilder();
        for (String l : labels) {
            if (sb.length() > 0) sb.append('.');
            sb.append(l);
        }
        return sb.toString();
    }

    // ── the response builder (deterministic — the lab proves these exact bytes) ──

    /** The full authoritative answer for OUR service, echoing the query's id:
     *  answers: PTR instance.service.local ; additional: SRV, TXT, A.
     *
     *  v2.4.0 — THE ATTACH FIX: the SRV target is now <hostName>.local (RFC 6763
     *  §4.3) and the A record's owner name is THE SAME <hostName>.local, so
     *  Android's MdnsResponseDecoder Loop 3 (findResponseWithHostName matches
     *  inetRecord.getName() == serviceRecord.getServiceHost(),
     *  MdnsResponseDecoder.java:248-255) attaches the address, isComplete()
     *  (MdnsResponse.java:336-341: SRV && TXT && ≥1 inet) becomes true and
     *  onServiceFound fires (MdnsServiceTypeClient.java:646-651). Cache-flush
     *  0x8001 on the unique SRV/TXT/A records (RFC 6762 §10.2), plain 0x0001 on
     *  the shared PTR. */
    byte[] responsePacket(byte[] query) {
        int id = query == null || query.length < 2 ? 0 : ((query[0] & 0xFF) << 8) | (query[1] & 0xFF);
        ByteArrayOutputStream b = new ByteArrayOutputStream(512);
        b.write(id >> 8); b.write(id & 0xFF);
        b.write(0x84); b.write(0x00);           // flags: AA + response
        b.write(0); b.write(0);                 // QDCOUNT 0 (an answer echoes no questions)
        b.write(0); b.write(1);                 // ANCOUNT 1
        b.write(0); b.write(0);                 // NSCOUNT 0
        b.write(0); b.write(3);                 // ARCOUNT 3 (SRV + TXT + A)
        // ── answer: PTR <instance>.<service>.local (shared record: NO cache-flush) ──
        writeName(b, instanceName, serviceType, "local");
        b.write(0); b.write(12);                // TYPE PTR
        b.write(0); b.write(1);                 // CLASS IN (no cache-flush: shared)
        writeU32(b, 4500);                      // TTL 4500 s — the standard DNS-SD browse TTL
        byte[] ptr = ptrRdata();
        writeU16(b, ptr.length);
        b.write(ptr, 0, ptr.length);
        // ── additional 1: SRV on <instance>.<service>.local, target <hostName>.local ──
        writeName(b, instanceName, serviceType, "local");
        b.write(0); b.write(33);                // TYPE SRV
        b.write(0x80); b.write(0x01);           // CLASS IN + cache-flush (unique record)
        writeU32(b, 120);
        byte[] srv = srvRdata();
        writeU16(b, srv.length);
        b.write(srv, 0, srv.length);
        // ── additional 2: TXT ──
        writeName(b, instanceName, serviceType, "local");
        b.write(0); b.write(16);                // TYPE TXT
        b.write(0x80); b.write(0x01);           // CLASS IN + cache-flush
        writeU32(b, 120);
        byte[] txt = txtRdata();
        writeU16(b, txt.length);
        b.write(txt, 0, txt.length);
        // ── additional 3: A on <hostName>.local — THE SAME NAME AS THE SRV TARGET ──
        writeName(b, hostName, "local");
        b.write(0); b.write(1);                 // TYPE A
        b.write(0x80); b.write(0x01);           // CLASS IN + cache-flush
        writeU32(b, 120);
        byte[] a = host == null ? new byte[4] : host.getAddress();
        if (a == null || a.length != 4) a = new byte[4];
        writeU16(b, 4);
        b.write(a, 0, 4);
        return b.toByteArray();
    }

    /** PTR rdata: just the instance name (labels). */
    private byte[] ptrRdata() {
        ByteArrayOutputStream b = new ByteArrayOutputStream(96);
        writeLabels(b, instanceName);
        b.write(0);
        return b.toByteArray();
    }

    /** SRV rdata: 0 0 port <hostName>.local (a proper RFC 6763 §4.3 target). */
    private byte[] srvRdata() {
        ByteArrayOutputStream b = new ByteArrayOutputStream(96);
        writeU16(b, 0);                          // priority
        writeU16(b, 0);                          // weight
        writeU16(b, port);                       // the receiver's TCP port
        writeLabels(b, hostName);                // target = <hostName>
        writeLabels(b, "local");                 // .local
        b.write(0);                              // root
        return b.toByteArray();
    }

    /** TXT rdata: "app=sicast" + the display name AnantPlay shows for TVs (the
     *  `fn` attribute Chromecast uses for the friendly name). */
    private byte[] txtRdata() {
        ByteArrayOutputStream b = new ByteArrayOutputStream(96);
        for (String s : new String[]{"app=sicast", "fn=" + instanceName}) {
            byte[] str = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (str.length > 255) continue;
            b.write(str.length);
            b.write(str, 0, str.length);
        }
        return b.toByteArray();
    }

    private static void writeName(ByteArrayOutputStream b, String... labels) {
        for (String l : labels) writeLabels(b, l);
        b.write(0);
    }

    private static void writeLabels(ByteArrayOutputStream b, String label) {
        byte[] raw = label.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        b.write(raw.length & 0xFF);
        b.write(raw, 0, raw.length);
    }

    private static void writeU16(ByteArrayOutputStream b, int v) {
        b.write((v >> 8) & 0xFF);
        b.write(v & 0xFF);
    }

    private static void writeU32(ByteArrayOutputStream b, long v) {
        b.write((int) ((v >> 24) & 0xFF));
        b.write((int) ((v >> 16) & 0xFF));
        b.write((int) ((v >> 8) & 0xFF));
        b.write((int) (v & 0xFF));
    }

    // ── JOB 2: the router-free self-probe (kernel multicast loopback) ──────────

    /** Result of the local probe. */
    static final class ProbeResult {
        boolean answered;          // our responder answered on the wire
        String responderUp;        // "yes(5353)" | "degraded(mdnsd-only)"
        int packetsSent, packetsReceived;
        String detail = "";
    }

    /**
     * The router-free proof: ask _anantplay._tcp.local on the multicast group from a
     * JOINED socket and wait for a reply carrying our instance name. Between our own
     * two sockets this is 100% kernel loopback — the router's multicast behaviour is
     * irrelevant. The caller supplies the responder (already started) and optionally
     * re-arms the MulticastLock for the probe duration.
     */
    static ProbeResult probeLocal(MdnsResponder responder, long timeoutMs) {
        ProbeResult r = new ProbeResult();
        r.responderUp = responder != null && responder.isPortShared() ? "yes(5353)" : "degraded(mdnsd-only)";
        MulticastSocket s = null;
        NetworkInterface iface = null;
        try {
            InetAddress group = InetAddress.getByName(MDNS_GROUP);
            s = new MulticastSocket(null);
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(0));        // any port for the probe side
            // Java's inverted semantics: setLoopbackMode(false) ENABLES kernel loopback
            s.setLoopbackMode(false);
            // interface-explicit join: the ONLY form that reliably loops back to
            // another socket joined on the same interface (the plain joinGroup()
            // can pick a non-multicast iface such as lo and silently blackhole).
            iface = responder == null ? null : ifaceOf(responder.host);
            if (iface == null) iface = ifaceOf(null);
            if (iface != null) {
                s.joinGroup(new InetSocketAddress(group, 0), iface);
                // v2.3.1: pin the SEND egress to the same interface — without this
                // the kernel's default multicast route can egress loopback on some
                // builds (the 127.0.0.1 source in the field log), which works but
                // makes the probe's own queries loop back and doubles the traffic.
                try { s.setNetworkInterface(iface); } catch (Throwable ignored) { }
            } else {
                s.joinGroup(group);
            }
            s.setSoTimeout(400);
            String type = responder == null ? "_anantplay._tcp" : responder.serviceType;
            String legacy = responder == null ? "_fmhyplay._tcp" : responder.legacyType;
            byte[] q = MdnsWire.queryPacket(type);
            // v2.3.1 — THE PARADOX FIX: the reply is validated structurally with
            // MdnsWire.isOurAnswer (QR bit + records + the ASCII service type).
            // The v2.3.0 check decoded the packet as ISO-8859-1 and searched for
            // the Sinhala instance name — 15 UTF-8 bytes that turn into Latin-1
            // mojibake and NEVER match, so every reply that DID arrive was
            // counted as silence and the loop span at full speed re-sending
            // (the 4-packet/~207 ms storm in the 2026-11-22 field log).
            long end = System.currentTimeMillis() + Math.max(800, timeoutMs);
            int maxSends = 6;                       // hard cap — a quiet network
            while (System.currentTimeMillis() < end && !r.answered && r.packetsSent < maxSends) {
                s.send(new DatagramPacket(q, q.length, group, MDNS_PORT));
                r.packetsSent++;
                byte[] buf = new byte[9000];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                    r.packetsReceived++;
                    if (MdnsWire.isOurAnswer(buf, p.getLength(), type, legacy)) {
                        r.answered = true;
                        r.detail = "mDNS පිළිතුර ලැබුණි (our own responder, kernel loopback — router-free) · "
                            + "mDNS answer received via our own responder — no router reflection involved";
                        break;
                    }
                } catch (java.net.SocketTimeoutException retry) { }
            }
            if (!r.answered) {
                r.detail = "responder up: " + r.responderUp + " sent=" + r.packetsSent + " recv=" + r.packetsReceived;
            }
        } catch (Throwable t) {
            r.detail = "probe error: " + t;
        } finally {
            if (s != null) {
                try {
                    InetAddress group = InetAddress.getByName(MDNS_GROUP);
                    if (iface != null) s.leaveGroup(new InetSocketAddress(group, 0), iface);
                    else s.leaveGroup(group);
                } catch (Throwable ignored) { }
                try { s.close(); } catch (Throwable ignored) { }
            }
        }
        return r;
    }
}
