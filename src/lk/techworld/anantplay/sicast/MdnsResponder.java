package lk.techworld.anantplay.sicast;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/* v3.0.0 — THE PURE-JAVA mDNS RESPONDER (RFC 6762), the Android-16 cast fix.
 *
 * THE 2026-11-22 15:15 REAL-DEVICE LOG (Xiaomi Android 16, sdk 36) — what v2.4.0
 * got wrong, now proven against the real AOSP android16-release sources
 * (platform/packages/modules/Connectivity @ 627eb3c6, service-t/…/mdns/):
 *
 *  FACT A — AnantPlay's discovery (NsdManager → MdnsMultinetworkSocketClient)
 *  sent its browse questions with the QU bit set, and our responder replied
 *  UNICAST-ONLY to 192.168.1.39:5353 (40+ "answered a unicast(QU) query" lines).
 *  A unicast packet to the phone's OWN address takes the kernel's `local` route,
 *  whose egress device is `lo` — while the system's per-network mDNS socket is
 *  created with SO_BINDTODEVICE(wlan0) (MdnsInterfaceSocket.java:74-80: "Bind
 *  socket to the interface for receiving from that interface only"). A packet
 *  that arrives via `lo` can NEVER be received by the wlan0-bound system socket
 *  (and SO_REUSEADDR unicast delivery is one-winner anyway). Every unicast
 *  reply was invisible to the exact client that asked. FIXED: replies are now
 *  ALWAYS sent MULTICAST to 224.0.0.251:5353 AND ff02::fb:5353 — multicast
 *  fan-out delivers a copy to EVERY socket joined to the group on that
 *  interface, ours, the system's and any other app's, with no one-winner
 *  selection at all. The QU unicast reply is ALSO sent (courtesy for real
 *  legacy queriers on other devices) but never relied on.
 *
 *  FACT B — the two "answered a multicast query" replies (5.245/5.246) were
 *  lost too, because the responder NEVER pinned its multicast egress: the
 *  kernel answers a multicast send on the DEFAULT multicast interface of the
 *  process, and the kernel's multicast LOOPBACK (the delivery to other local
 *  sockets) happens on that same EGRESS interface. On this Xiaomi the default
 *  egress was not the joined wlan0, so the system socket (joined on wlan0)
 *  never saw the looped copy. Proof by contrast: the self-test's probeLocal —
 *  which DOES call setNetworkInterface — worked in the same log ("answered=true
 *  sent=1 recv=1"). FIXED: the responder pins setNetworkInterface(joinedIface),
 *  exactly like AOSP's MdnsInterfaceSocket does (MdnsInterfaceSocket.java:70-72).
 *
 *  FACT C — TTL: Java's default multicast TTL is 1; RFC 6762 §11 mandates 255
 *  and AOSP itself sends 255 (MdnsInterfaceSocket.java:70-71). AOSP does not
 *  check the TTL on receive, but mDNS-snooping switches may. FIXED:
 *  setTimeToLive(255).
 *
 *  FACT D — the two fe80::…%wlan0 queries wanted an IPv6 answer; the v2.4.0
 *  response carried only an A record. AOSP's MdnsResponse.isComplete()
 *  (MdnsResponse.java:336-340) accepts SRV + TXT + (A OR AAAA), so an AAAA with
 *  the interface's IPv6 makes the v6 leg complete as well. FIXED: an AAAA
 *  record for the SRV target whenever the interface has an IPv6 address.
 *
 * Kept from v2.4.0 (proven correct in the field):
 *   · the instance name differs from the NsdManager registration name, and
 *     ANY (§8.1 probe) questions are NEVER answered — the daemon's
 *     MdnsAdvertiser owns its name unchallenged (the v2.3.x sabotage fix);
 *   · SRV target = <hostName>.local with the A/AAAA owner on the SAME name, so
 *     AOSP's decoder Loop 3 (MdnsResponseDecoder findResponseWithHostName)
 *     attaches the address and isComplete() becomes true;
 *   · cache-flush 0x8001 on unique records (RFC 6762 §10.2), plain IN on the
 *     shared PTR;
 *   · SO_REUSEADDR sharing of 5353 with the platform's own sockets, with
 *     graceful degradation when the bind is refused.
 *
 * Pure java.net only — the verification lab drives this exact code on a bare
 * JVM and proves the wire bytes byte-for-byte. */
public final class MdnsResponder {

    public static final String MDNS_GROUP = "224.0.0.251";      // mDNS IPv4 group
    public static final String MDNS_GROUP_V6 = "ff02::fb";      // mDNS IPv6 group
    public static final int MDNS_PORT = 5353;
    /** RFC 6762 §11 — multicast DNS packets are sent with IP TTL / hop limit 255. */
    public static final int MDNS_TTL = 255;

    private final String serviceType;     // "_anantplay._tcp"
    private final String legacyType;      // "_fmhyplay._tcp" (or "" to disable)
    private final String instanceName;    // e.g. "AnantPlay Sinhala Player" (≠ the NsdManager name!)
    private final int port;               // the receiver's TCP port
    private final InetAddress host;       // the interface IPv4 to advertise (may be loopback in the lab)
    private final InetAddress host6;      // the interface IPv6 for the AAAA record (null = none)
    /** The SRV target host (<hostName>.local.) — RFC 6763 §4.3; the A/AAAA owner
     *  name MUST equal it or AOSP's decoder never attaches the address. */
    private final String hostName;

    private MulticastSocket socket;
    private Thread worker;
    private volatile boolean running;
    private volatile boolean portShared;  // true when 5353 bound OK alongside the platform sockets
    private volatile long answered, queriesSeen;
    /** v3.0.0 — reply destination counters (the self-test shows them; a healthy
     *  reply is "m4/m6/uc" where m4=IPv4-group sends, m6=IPv6-group sends). */
    private volatile long sentM4, sentM6, sentUnicast;

    public MdnsResponder(String serviceType, String legacyType, String instanceName,
                         int port, InetAddress host) {
        this(serviceType, legacyType, instanceName, port, host, null);
    }

    /** v3.0.0 — with an IPv6 address for the AAAA record (null = A-only). */
    public MdnsResponder(String serviceType, String legacyType, String instanceName,
                         int port, InetAddress host, InetAddress host6) {
        this.serviceType = serviceType;
        this.legacyType = legacyType == null ? "" : legacyType;
        this.instanceName = instanceName;
        this.port = port;
        this.host = host;
        this.host6 = host6 instanceof Inet6Address ? host6 : null;
        this.hostName = makeHostName();
    }

    /** A stable, unique, DNS-label-safe host name derived from the advertised
     *  IP (deterministic: same IP → same host name, so the lab proves the exact
     *  bytes). ASCII-only, ≤ 63 bytes, no dots — a legal single label. */
    private String makeHostName() {
        String ip = host == null ? "0.0.0.0" : host.getHostAddress();
        int h = Math.abs(ip.hashCode());
        return String.format(Locale.ROOT, "Sinhala-Player-%04x", h & 0xFFFF);
    }

    /** The advertised SRV target ("<hostName>.local"). */
    public String hostLabel() { return hostName; }

    public boolean isRunning() { return running; }
    /** True when we actually share UDP 5353 (false = bind refused — degraded). */
    public boolean isPortShared() { return portShared; }
    public long answeredCount() { return answered; }
    public long queriesSeen() { return queriesSeen; }
    public String serviceType() { return serviceType; }
    public String legacyType() { return legacyType; }
    public long sentMulticast4() { return sentM4; }
    public long sentMulticast6() { return sentM6; }
    public long sentUnicastCount() { return sentUnicast; }

    /** Bind + pin + join + serve. Returns false when 5353 could not be shared. */
    public synchronized boolean start() {
        if (running) return true;
        try {
            socket = new MulticastSocket(null);
            socket.setReuseAddress(true);          // coexist with the platform's 5353 sockets
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
            // THE v3.0.0 FIXES (FACT B + C): TTL 255 (RFC 6762 §11) and the
            // multicast egress PINNED to the joined interface — the exact two
            // settings AOSP's own MdnsInterfaceSocket applies
            // (MdnsInterfaceSocket.java:70-72). Without the pin, the kernel
            // loops multicast back only on the DEFAULT egress interface, which
            // on the 2026-11-22 Xiaomi was NOT the joined wlan0 — the system's
            // discovery socket never saw a single reply.
            socket.setTimeToLive(MDNS_TTL);
            InetAddress group4 = InetAddress.getByName(MDNS_GROUP);
            NetworkInterface onIface = ifaceOf(host);
            if (onIface != null) {
                socket.joinGroup(new InetSocketAddress(group4, 0), onIface);
                // v3.0.0: join the IPv6 group too (only when the iface has v6) so
                // ff02::fb queries from v6-capable clients reach us at all.
                InetAddress group6 = InetAddress.getByName(MDNS_GROUP_V6);
                try {
                    socket.joinGroup(new InetSocketAddress(group6, 0), onIface);
                    joinedV6 = true;
                } catch (Throwable t) {
                    Diag.w("mdnsrsp", "no IPv6 mDNS group on " + onIface.getName() + " (" + t + ")");
                }
                // PIN THE EGRESS — this one line is the difference between the
                // v2.4.0 silent failure and a reply the system socket receives.
                try { socket.setNetworkInterface(onIface); } catch (Throwable t) {
                    Diag.w("mdnsrsp", "setNetworkInterface failed (" + t + ") — relying on the default route");
                }
            } else {
                socket.joinGroup(group4);          // lab fallback: the default join
            }
            joinedIface = onIface;
            running = true;
            worker = new Thread(this::serve, "si-mdns-rsp");
            worker.setDaemon(true);
            worker.start();
            Diag.i("mdnsrsp", "responder up on 5353 for " + serviceType + " (instance \"" + instanceName + "\" :" + port
                + (onIface != null ? ", iface " + onIface.getName() : "")
                + ", ttl " + MDNS_TTL + ", v6 " + (host6 != null ? host6.getHostAddress() : "off") + ")");
            return true;
        } catch (Throwable t) {
            Diag.w("mdnsrsp", "joinGroup failed: " + t);
            stop();
            return false;
        }
    }

    /** The interface the advertised address belongs to — but only when that
     *  interface actually carries multicast (a loopback iface on some kernels
     *  accepts joinGroup then blackholes every packet). Falls back to the
     *  first up, non-loopback, multicast-capable interface. */
    private static NetworkInterface ifaceOf(InetAddress host) {
        try {
            if (host != null && !host.isLoopbackAddress()) {
                NetworkInterface n = NetworkInterface.getByInetAddress(host);
                if (n != null && !n.isLoopback() && n.supportsMulticast() && n.isUp()) return n;
            }
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (n.isUp() && !n.isLoopback() && n.supportsMulticast()) return n;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** The interfaces we joined (for leaveGroup symmetry). */
    private NetworkInterface joinedIface;
    private boolean joinedV6;

    public synchronized void stop() {
        running = false;
        if (worker != null) { try { worker.interrupt(); } catch (Throwable ignored) { } worker = null; }
        if (socket != null) {
            try {
                InetAddress group = InetAddress.getByName(MDNS_GROUP);
                if (joinedIface != null) socket.leaveGroup(new InetSocketAddress(group, 0), joinedIface);
                else socket.leaveGroup(group);
                if (joinedV6 && joinedIface != null) {
                    socket.leaveGroup(new InetSocketAddress(InetAddress.getByName(MDNS_GROUP_V6), 0), joinedIface);
                }
            } catch (Throwable ignored) { }
            try { socket.close(); } catch (Throwable ignored) { }
            socket = null;
        }
        joinedIface = null;
        joinedV6 = false;
        Diag.i("mdnsrsp", "responder down (answered " + answered + " queries, m4=" + sentM4
            + " m6=" + sentM6 + " uc=" + sentUnicast + ")");
    }

    /** One Diag line per source per second at most; repeats collapse into
     *  "… (×N in 1 s)". Called on the serve thread only. */
    private String lastLogSource = "";
    private int suppressedSinceLog;
    private long lastLogAt;

    private void logAnswer(String source, boolean qu) {
        long now = System.currentTimeMillis();
        if (source.equals(lastLogSource) && now - lastLogAt < 1000) {
            suppressedSinceLog++;
            return;
        }
        StringBuilder line = new StringBuilder("answered ").append(qu ? "QU" : "multicast")
            .append(" query from ").append(source).append(" — reply → 224.0.0.251 + ff02::fb");
        if (qu) line.append(" + unicast");
        if (suppressedSinceLog > 0) {
            line.append(" … (×").append(suppressedSinceLog).append(" in 1 s)");
            suppressedSinceLog = 0;
        }
        Diag.i("mdnsrsp", line.toString());
        lastLogSource = source;
        lastLogAt = now;
    }

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
                    sendReply(socket, out, p.getAddress(), p.getPort(), hasQuBit(in));
                    answered++;
                    logAnswer(p.getAddress().getHostAddress() + ":" + p.getPort(), hasQuBit(in));
                }
            } catch (Throwable t) {
                if (running) Diag.w("mdnsrsp", "serve: " + t);
            }
        }
    }

    /** THE v3.0.0 REPLY POLICY (FACT A + B): the answer is ALWAYS multicast to
     *  BOTH groups (kernel fan-out → every joined socket on the interface gets
     *  a copy — the platform's per-network socket included), and ADDITIONALLY
     *  unicast to the querier when it asked with the QU bit. A failure to send
     *  on one family never blocks the other. */
    void sendReply(MulticastSocket sock, byte[] out, InetAddress from, int fromPort, boolean qu) {
        // 1. IPv4 group — multicast fan-out, the delivery the system mDNS
        //    stack (and every other app) actually receives.
        try {
            sock.send(new DatagramPacket(out, out.length,
                new InetSocketAddress(ip(MDNS_GROUP), MDNS_PORT)));
            sentM4++;
        } catch (Throwable t) {
            Diag.w("mdnsrsp", "mcast v4 send: " + t);
        }
        // 2. IPv6 group — same fan-out on the v6 leg (fe80:: queriers).
        try {
            sock.send(new DatagramPacket(out, out.length,
                new InetSocketAddress(ip(MDNS_GROUP_V6), MDNS_PORT)));
            sentM6++;
        } catch (Throwable t) {
            // v4-only iface or an AF_INET socket: normal on some builds, not an error
            Diag.w("mdnsrsp", "mcast v6 send skipped: " + t);
        }
        // 3. The QU courtesy unicast (legacy queriers off-device). NEVER the
        //    only delivery — see FACT A: on-device unicast to :5353 travels the
        //    kernel `local` route (lo) and a SO_BINDTODEVICE(wlan0) system
        //    socket cannot receive it at all.
        if (qu) {
            try {
                sock.send(new DatagramPacket(out, out.length, new InetSocketAddress(from, fromPort)));
                sentUnicast++;
            } catch (Throwable t) {
                Diag.w("mdnsrsp", "unicast send: " + t);
            }
        }
    }

    private static InetAddress ip(String s) {
        try { return InetAddress.getByName(s); } catch (Throwable t) { return null; }
    }

    /** True when the packet is a QUERY whose QUESTION section carries the QU top
     *  bit in a QCLASS field (RFC 6762 §5.4). We scan every question's QCLASS. */
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
     *  Kept from v2.4.0 — the anti-sabotage rules:
     *   · ANY questions are NEVER answered (an RFC 6762 §8.1 probe is not a query;
     *     answering it registers as a CONFLICT in MdnsRecordRepository and
     *     re-probes the daemon's advertiser forever);
     *   · the responder's instance name differs from the NsdManager name.
     *  v3.0.0 — the query may carry BOTH service types (or either); every
     *  MATCHED type gets its PTR in the answer and its SRV/TXT pair in the
     *  additional section, so a dual-type querier completes in one round trip. */
    byte[] answerIfForUs(byte[] q) {
        try {
            if (q == null || q.length < 12) return null;
            if ((q[2] & 0x80) != 0) return null;              // a response, not a query
            int qd = ((q[4] & 0xFF) << 8) | (q[5] & 0xFF);
            qd = qd & 0x7FFF;
            if (qd == 0 || qd > 4) return null;               // 0 = probe packet, >4 = junk
            int pos = 12;
            boolean anant = false, fmhy = false;
            boolean srvAsked = false, a4Asked = false, a6Asked = false;
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
                if (qtype == 255) continue;                   // §8.1 probe / ANY: NEVER answer
                String want1 = (serviceType + ".local").toLowerCase(Locale.ROOT);
                String want2 = legacyType.isEmpty() ? "" : (legacyType + ".local").toLowerCase(Locale.ROOT);
                if (qtype == 12) {                            // PTR: browse for the service type
                    if (lower.equals(want1)) anant = true;
                    if (!want2.isEmpty() && lower.equals(want2)) fmhy = true;
                }
                if (qtype == 33 || qtype == 16) {             // SRV / TXT on the instance
                    String inst = (instanceName + "." + serviceType + ".local").toLowerCase(Locale.ROOT);
                    String instLegacy = legacyType.isEmpty() ? "" : (instanceName + "." + legacyType + ".local").toLowerCase(Locale.ROOT);
                    if (lower.equals(inst) || lower.equals(instLegacy)) srvAsked = true;
                }
                if (qtype == 1 || qtype == 28) {              // A / AAAA on the SRV target host
                    String h = (hostName + ".local").toLowerCase(Locale.ROOT);
                    if (lower.equals(h)) { a4Asked = true; a6Asked = true; }
                }
            }
            boolean ours = anant || fmhy || srvAsked || a4Asked;
            if (!ours) return null;
            // A pure A/AAAA follow-up (a resolver resolving the SRV target) is
            // answered with the host records only — no service records needed.
            boolean hostOnly = !anant && !fmhy && !srvAsked && (a4Asked || a6Asked);
            return responsePacket(q, anant, fmhy, hostOnly);
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

    /** The full authoritative answer echoing the query's id:
     *  answers: one PTR per matched service type;
     *  additional: SRV + TXT per matched type, then A (+ AAAA) on
     *  <hostName>.local — the SRV target, so AOSP's decoder attaches the
     *  address and isComplete() fires. Cache-flush 0x8001 on unique records
     *  (RFC 6762 §10.2), plain 0x0001 on the shared PTR. TTLs: PTR 4500 (the
     *  DNS-SD browse standard), SRV/TXT/A/AAAA 120 (AOSP's own
     *  DEFAULT_NAME_RECORDS_TTL_MILLIS, MdnsRecordRepository.java:81). */
    byte[] responsePacket(byte[] query, boolean anant, boolean fmhy, boolean hostOnly) {
        Set<String> types = new LinkedHashSet<>();
        if (anant) types.add(serviceType);
        if (fmhy) types.add(legacyType);
        int anCount = hostOnly ? 0 : types.size();
        int arCount = hostOnly ? (host6 != null ? 2 : 1) : types.size() * 2 + (host6 != null ? 2 : 1);
        int id = query == null || query.length < 2 ? 0 : ((query[0] & 0xFF) << 8) | (query[1] & 0xFF);
        ByteArrayOutputStream b = new ByteArrayOutputStream(640);
        b.write(id >> 8); b.write(id & 0xFF);
        b.write(0x84); b.write(0x00);           // flags: AA + response
        b.write(0); b.write(0);                 // QDCOUNT 0 (an answer echoes no questions)
        b.write((anCount >> 8) & 0xFF); b.write(anCount & 0xFF);
        b.write(0); b.write(0);                 // NSCOUNT 0
        b.write((arCount >> 8) & 0xFF); b.write(arCount & 0xFF);
        for (String t : types) {
            // ── answer: PTR <instance>.<type>.local (shared record: NO cache-flush) ──
            writeName(b, instanceName, t, "local");
            b.write(0); b.write(12);            // TYPE PTR
            b.write(0); b.write(1);             // CLASS IN (no cache-flush: shared)
            writeU32(b, 4500);                  // TTL 4500 s — the standard DNS-SD browse TTL
            byte[] ptr = ptrRdata(t);
            writeU16(b, ptr.length);
            b.write(ptr, 0, ptr.length);
            // ── additional: SRV on <instance>.<type>.local, target <hostName>.local ──
            writeName(b, instanceName, t, "local");
            b.write(0); b.write(33);            // TYPE SRV
            b.write(0x80); b.write(0x01);       // CLASS IN + cache-flush (unique record)
            writeU32(b, 120);
            byte[] srv = srvRdata();
            writeU16(b, srv.length);
            b.write(srv, 0, srv.length);
            // ── additional: TXT ──
            writeName(b, instanceName, t, "local");
            b.write(0); b.write(16);            // TYPE TXT
            b.write(0x80); b.write(0x01);       // CLASS IN + cache-flush
            writeU32(b, 120);
            byte[] txt = txtRdata();
            writeU16(b, txt.length);
            b.write(txt, 0, txt.length);
        }
        if (!hostOnly || host6 != null) {
            // ── additional: A on <hostName>.local — THE SAME NAME AS THE SRV TARGET ──
            writeName(b, hostName, "local");
            b.write(0); b.write(1);             // TYPE A
            b.write(0x80); b.write(0x01);       // CLASS IN + cache-flush
            writeU32(b, 120);
            byte[] a = host == null ? new byte[4] : host.getAddress();
            if (a == null || a.length != 4) a = new byte[4];
            writeU16(b, 4);
            b.write(a, 0, 4);
        }
        if (host6 != null) {
            // ── v3.0.0 additional: AAAA on <hostName>.local (FACT D) — makes the
            //    response complete for the fe80::/v6 queriers too. ──
            writeName(b, hostName, "local");
            b.write(0); b.write(28);            // TYPE AAAA
            b.write(0x80); b.write(0x01);       // CLASS IN + cache-flush
            writeU32(b, 120);
            byte[] a6 = host6.getAddress();
            writeU16(b, a6.length);
            b.write(a6, 0, a6.length);
        }
        return b.toByteArray();
    }

    /** PTR rdata: the instance name labels under the given service type. */
    private byte[] ptrRdata(String type) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(96);
        writeLabels(b, instanceName);
        writeLabels(b, type);
        writeLabels(b, "local");
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

    /** TXT rdata: "app=sicast" + the display name AnantPlay shows for TVs. */
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

    // ── the router-free self-probe (kernel multicast loopback) ──────────────────

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
     * irrelevant. The probe socket PINS ITS EGRESS exactly like the responder now
     * does (that pin is what made the v2.4.0 probe work while the responder's
     * unpinned replies were lost — the contrast that revealed FACT B).
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
            s.setSoTimeout(400);
            iface = responder == null ? null : ifaceOf(responder.host);
            if (iface == null) iface = ifaceOf(null);
            if (iface != null) {
                s.joinGroup(new InetSocketAddress(group, 0), iface);
                try { s.setNetworkInterface(iface); } catch (Throwable ignored) { }
            } else {
                s.joinGroup(group);
            }
            String type = responder == null ? "_anantplay._tcp" : responder.serviceType;
            String legacy = responder == null ? "_fmhyplay._tcp" : responder.legacyType;
            byte[] q = MdnsWire.queryPacket(type);
            long end = System.currentTimeMillis() + Math.max(800, timeoutMs);
            int maxSends = 6;                       // hard cap — a quiet network
            while (System.currentTimeMillis() < end && !r.answered && r.packetsSent < maxSends) {
                s.send(new DatagramPacket(q, q.length, new InetSocketAddress(group, MDNS_PORT)));
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
