package lk.techworld.anantplay.sicast;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

/* v3.0.0 — THE SSDP RESPONDER (UPnP/SSDP over UDP 1900), the discovery half of
 * the DLNA MediaRenderer. Pure java.net (no Android imports) so the verification
 * lab drives the exact M-SEARCH → 200-OK exchange on a bare JVM.
 *
 * WHY A SECOND RESPONDER AT ALL: the 2026-11-22 15:15 Android 16 field log
 * proved NsdManager.registerService() can hang with ZERO callbacks for 340 s
 * (the AOSP MdnsAdvertiser no-socket hang). AnantPlay's cast list, however,
 * also fills from SSDP (Dlna.search(), fired by Cast.discover on every app
 * start and Re-scan): it multicast-searches 239.255.255.250:1900 for
 * "urn:schemas-upnp-org:device:MediaRenderer:1", reads ONLY the LOCATION
 * header of each reply, GETs it, and lists the device as "<friendlyName> -
 * DLNA" — Cast.any() counts it exactly like an mDNS TV. SSDP shares no code
 * path with NsdService/MdnsAdvertiser, so the Cast chip lights up even on a
 * build where the platform mDNS stack is wedged.
 *
 * Delivery model (why this works where the v2.4.0 mDNS replies failed):
 *   · the M-SEARCH ARRIVES as multicast to 239.255.255.250:1900 — our socket
 *     is joined to that group on the Wi-Fi interface, so the kernel's group
 *     membership delivers it to us (receive path, no one-winner selection);
 *   · the reply goes UNICAST to the searcher's own ephemeral source port
 *     (AnantPlay's search socket is a plain DatagramSocket, NOT port 1900) —
 *     a unique port, so the reply can never be stolen by another 5353/1900
 *     listener. This is the delivery the v2.4.0 mDNS unicast replies could
 *     never achieve on-device.
 *
 * Lifecycle: ssdp:alive NOTIFYs ×3 on start (for passively-listening control
 * points — AnantPlay itself only M-SEARCHes), a keepalive burst every 10 min,
 * and ssdp:byebye ×3 on stop. AnantPlay re-searches on every app start and
 * Re-scan, so discovery needs no state on our side. */
public final class DlnaRenderer {

    /** Supplies "http://<lan-ip>:<port>/dlna/desc.xml" per answer (the LAN IP
     *  can change on re-arm — never cache it across network changes). */
    public interface LocationProvider { String httpLocation(); }

    private final DlnaEndpoint endpoint;
    private final LocationProvider location;
    private MulticastSocket socket;
    private Thread worker;
    private Thread keepalive;
    private volatile boolean running;
    private volatile long searchesSeen, repliesSent;
    private NetworkInterface joinedIface;

    public DlnaRenderer(DlnaEndpoint endpoint, LocationProvider location) {
        this.endpoint = endpoint;
        this.location = location;
    }

    public boolean isRunning() { return running; }
    public long searchesSeen() { return searchesSeen; }
    public long repliesSent() { return repliesSent; }

    /** Bind + join + serve. Returns false when UDP 1900 cannot be shared
     *  (degraded gracefully — the mDNS paths stay the announcers). */
    public synchronized boolean start() {
        if (running) return true;
        try {
            socket = new MulticastSocket(null);
            socket.setReuseAddress(true);            // multiple SSDP listeners are legal
            socket.bind(new InetSocketAddress(DlnaEndpoint.SSDP_PORT));
        } catch (Throwable t) {
            Diag.w("dlna", "cannot bind UDP 1900 (" + t + ") — DLNA discovery unavailable, mDNS paths stay up");
            try { if (socket != null) socket.close(); } catch (Throwable ignored) { }
            socket = null;
            return false;
        }
        try {
            InetAddress group = InetAddress.getByName(DlnaEndpoint.SSDP_GROUP);
            joinedIface = multicastIface();
            if (joinedIface != null) {
                socket.joinGroup(new InetSocketAddress(group, 0), joinedIface);
                socket.setNetworkInterface(joinedIface);   // pin the egress, same lesson as MdnsResponder
            } else {
                socket.joinGroup(group);
            }
            socket.setTimeToLive(DlnaEndpoint.SSDP_TTL);   // UPnP 1.0: SSDP multicast TTL 4
            running = true;
            worker = new Thread(this::serve, "si-dlna-ssdp");
            worker.setDaemon(true);
            worker.start();
            keepalive = new Thread(this::aliveLoop, "si-dlna-alive");
            keepalive.setDaemon(true);
            keepalive.start();
            Diag.i("dlna", "SSDP responder up on 1900 (\"" + endpoint.friendlyName()
                + "\", ttl " + DlnaEndpoint.SSDP_TTL + (joinedIface != null ? ", iface " + joinedIface.getName() : "") + ")");
            return true;
        } catch (Throwable t) {
            Diag.w("dlna", "SSDP join failed: " + t);
            stop();
            return false;
        }
    }

    /** The first up, non-loopback, multicast-capable interface (the Wi-Fi). */
    private static NetworkInterface multicastIface() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (n.isUp() && !n.isLoopback() && n.supportsMulticast()) return n;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    public synchronized void stop() {
        running = false;
        if (keepalive != null) { keepalive.interrupt(); keepalive = null; }
        if (worker != null) { try { worker.interrupt(); } catch (Throwable ignored) { } worker = null; }
        if (socket != null) {
            try {
                // ssdp:byebye ×3 — polite removal for passively-listening control points
                InetAddress group = InetAddress.getByName(DlnaEndpoint.SSDP_GROUP);
                for (int i = 0; i < 3 && joinedIface != null; i++) {
                    for (String nt : byebyeTargets()) {
                        try {
                            // v3.0.0 fix: the DatagramPacket length is the REAL payload
                            // length. A hard-coded 900 made the constructor throw
                            // IllegalArgumentException (offset+length > buf.length) and
                            // the byebye was silently swallowed — politely un-advertising
                            // on stop never actually happened.
                            byte[] bye = endpoint.byebyeNotify(nt).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            socket.send(new DatagramPacket(bye, bye.length,
                                new InetSocketAddress(group, DlnaEndpoint.SSDP_PORT)));
                        } catch (Throwable ignored) { }
                    }
                }
                if (joinedIface != null) socket.leaveGroup(new InetSocketAddress(group, 0), joinedIface);
            } catch (Throwable ignored) { }
            try { socket.close(); } catch (Throwable ignored) { }
            socket = null;
        }
        joinedIface = null;
        Diag.i("dlna", "SSDP responder down (answered " + searchesSeen + " searches, sent " + repliesSent + " replies)");
    }

    /** The serve loop: classify M-SEARCH packets, answer every matching ST. */
    private void serve() {
        byte[] buf = new byte[2048];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                String packet = new String(buf, 0, p.getLength(), java.nio.charset.StandardCharsets.UTF_8);
                if (!DlnaEndpoint.isMSearch(packet)) continue;   // NOTIFYs from other devices etc.
                searchesSeen++;
                List<String> targets = DlnaEndpoint.answerTargets(DlnaEndpoint.ssdpHeader(packet, "ST"));
                if (targets.isEmpty()) continue;                 // a search for something we are not
                long delay = endpoint.answerDelayMs(packet);      // 0..min(MX,1.2 s) — inside AnantPlay's 4 s window
                if (delay > 0) { try { Thread.sleep(delay); } catch (InterruptedException e) { return; } }
                String loc = location.httpLocation();
                if (loc == null || loc.isEmpty()) continue;
                for (String st : targets) {
                    try {
                        byte[] out = endpoint.searchResponse(st, loc).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        socket.send(new DatagramPacket(out, out.length, new InetSocketAddress(p.getAddress(), p.getPort())));
                        repliesSent++;
                    } catch (Throwable t) {
                        Diag.w("dlna", "reply send: " + t);
                    }
                }
            } catch (Throwable t) {
                if (running) Diag.w("dlna", "serve: " + t);
            }
        }
    }

    /** 3 quick alive bursts at start, then one every 10 minutes while running. */
    private void aliveLoop() {
        try {
            for (int i = 0; i < 3 && running; i++) sendAliveBurst();
            while (running) {
                Thread.sleep(10 * 60 * 1000L);
                if (running) sendAliveBurst();
            }
        } catch (InterruptedException e) {
            // stop() — the byebyes are sent there
        } catch (Throwable t) {
            Diag.w("dlna", "alive loop: " + t);
        }
    }

    /** One ssdp:alive NOTIFY per advertised NT, multicast to the group. */
    private void sendAliveBurst() {
        try {
            MulticastSocket s = socket;
            if (s == null || !running) return;
            InetAddress group = InetAddress.getByName(DlnaEndpoint.SSDP_GROUP);
            String loc = location.httpLocation();
            if (loc == null || loc.isEmpty()) return;
            for (String nt : aliveTargets()) {
                try {
                    byte[] out = endpoint.aliveNotify(nt, loc).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    s.send(new DatagramPacket(out, out.length, new InetSocketAddress(group, DlnaEndpoint.SSDP_PORT)));
                } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            Diag.w("dlna", "alive burst: " + t);
        }
    }

    /** What we advertise: the root device, the MediaRenderer device type, both services. */
    static List<String> aliveTargets() {
        return DlnaEndpoint.answerTargets("ssdp:all");
    }

    static List<String> byebyeTargets() { return aliveTargets(); }
}
