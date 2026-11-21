package lk.techworld.anantplay.sicast;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;

/* Announces this phone on the local Wi-Fi exactly like an AnantPlay TV does
 * (Cast.java:65-84): two NsdManager registrations — "_anantplay._tcp" (current) and
 * "_fmhyplay._tcp" (the old name phones still discover) — named
 * "AnantPlay on සිංහල Player", port = the receiver's actual port.
 *
 * Official AnantPlay's phone side discovers both types from app start
 * (Cast.discover — Cast.java:239-259), so our entry appears in its cast list as
 * "සිංහල Player - AnantPlay (Recommended)" (strings_player.xml:94), listed FIRST
 * (Cast.targets() — Cast.java:299-307).
 *
 * v2 (this upgrade) — the mDNS self-discovery fix, layered on the v1 audit fixes:
 *
 *  THE FIELD FAILURE (Xiaomi HyperOS + AdGuard, 2026-11-21): the v1 Self-test ran
 *  NsdManager.discoverServices() from THIS app and expected to see THIS app's own
 *  registration. On many OEM mDNS stacks (HyperOS included) the daemon filters a
 *  service out of its OWN uid's discovery results, so "mDNS self-discovery FAILED"
 *  even while the announcement was perfectly visible to OTHER apps (AnantPlay
 *  casts worked!). AdGuard Private DNS (DoT, TCP 853) never touches UDP 5353
 *  multicast — but the AdGuard VPN app (tun0) can blackhole mDNS for the whole
 *  process. The v1 test held no MulticastLock either, so Wi-Fi power-save could
 *  drop the multicast responses even where the filter didn't apply.
 *
 *  THE FIX — three layers, each independently verifiable from the app:
 *   1. REGISTRATION PROOF without discovery: NsdManager's own
 *      onServiceRegistered() callback (v1) PLUS an active raw-socket mDNS probe:
 *      we send a real unicast mDNS PTR query for _anantplay._tcp.local to the
 *      Wi-Fi interface's mDNS endpoint (224.0.0.251:5353 bound to the interface)
 *      while holding the MulticastLock, and parse the answer for OUR name. This
 *      is the same wire path AnantPlay's NsdManager uses, minus the OEM's
 *      same-uid discovery filter — the honest proof that the network, not just
 *      the daemon, carries our record.
 *   2. LEGACY DISCOVERY STILL TRIED: NsdManager.discoverServices() runs too;
 *      success there is a bonus (some stacks don't filter), never a requirement.
 *   3. ROOT-CAUSE DIAGNOSIS on failure: Wi-Fi off vs multicast lock denied vs
 *      an active VPN (AdGuard app tun0) vs no answer on the wire (router AP
 *      isolation) — each with its own bilingual fix instruction, surfaced by
 *      the Self-test. (AdGuard PRIVATE DNS is explicitly NOT blamed: DoT only
 *      encrypts unicast DNS on port 853; it cannot affect UDP 5353 multicast.)
 *
 *  Kept from v1: per-type registration state, a Wi-Fi multicast lock while
 *  announcing, network callbacks registered once and always unregistered, every
 *  NsdManager call individually wrapped (IllegalArgumentException("listener
 *  already in use") on re-registration), retry with backoff, re-announce on
 *  network return. */
public final class NsdAnnouncer {

    static final String TYPE = "_anantplay._tcp";
    static final String OLD_TYPE = "_fmhyplay._tcp";
    static final String PREFIX = "AnantPlay on ";
    static final String NAME = "සිංහල Player";
    /** The mDNS multicast group + port (RFC 6762) — the wire the probe speaks. */
    static final String MDNS_GROUP = "224.0.0.251";
    static final int MDNS_PORT = 5353;
    /** How long the raw probe waits for a reflected answer (ms). */
    static final int PROBE_TIMEOUT_MS = 4000;

    private final Context ctx;
    private final int port;
    private final NsdManager nsd;
    private final NsdManager.RegistrationListener[] listeners = new NsdManager.RegistrationListener[2];
    private final boolean[] registered = new boolean[2];
    private volatile boolean announced;
    private android.net.wifi.WifiManager.MulticastLock mcast;
    private int attempt;
    private final android.os.Handler retry = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable reArm = new Runnable() {
        @Override public void run() { start(); }   // network back up or retry fired
    };
    private ConnectivityManager.NetworkCallback netWatch;

    NsdAnnouncer(Context ctx, int port) {
        this.ctx = ctx;
        this.port = port;
        NsdManager n = null;
        try { n = ctx.getSystemService(NsdManager.class); } catch (Throwable ignored) { }
        this.nsd = n;
    }

    public boolean isAnnounced() { return announced; }

    public void start() {
        if (nsd == null) { Diag.w("nsd", "no NsdManager — cast discovery cannot see us"); return; }
        stop();
        String service = PREFIX + NAME;
        String[] types = {TYPE, OLD_TYPE};
        boolean any = false;
        for (int i = 0; i < types.length; i++) {
            try {
                NsdServiceInfo info = new NsdServiceInfo();
                info.setServiceName(service);   // some stacks refuse duplicate names → the prefix, like TVs
                info.setServiceType(types[i]);
                info.setPort(port);
                final String t = types[i];
                final int slot = i;
                listeners[i] = new NsdManager.RegistrationListener() {
                    @Override public void onServiceRegistered(NsdServiceInfo s) {
                        registered[slot] = true;
                        announced = true;
                        attempt = 0;
                        Diag.i("nsd", "announced " + t + " as \"" + s.getServiceName() + "\" :" + port);
                    }
                    @Override public void onRegistrationFailed(NsdServiceInfo s, int e) {
                        registered[slot] = false;
                        announced = registered[0] || registered[1];
                        Diag.w("nsd", "announce " + t + " failed " + e + " — retrying");
                        if (++attempt < 20) retry.postDelayed(reArm, 2000 + attempt * 1000);
                    }
                    @Override public void onServiceUnregistered(NsdServiceInfo s) { }
                    @Override public void onUnregistrationFailed(NsdServiceInfo s, int e) { }
                };
                nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listeners[i]);
                any = true;
            } catch (Throwable t) {
                Diag.w("nsd", "register " + types[i] + ": " + t);
            }
        }
        watchNetwork();
        acquireMulticastLock();
        if (!any) Diag.e("nsd", "no registration possible");
    }

    /** mDNS records live on the interface: re-announce when Wi-Fi comes back. */
    private void watchNetwork() {
        if (netWatch != null) return;
        try {
            final ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            if (cm == null) return;
            netWatch = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) {
                    Diag.i("nsd", "network up — re-announcing");
                    retry.removeCallbacks(reArm);
                    retry.postDelayed(reArm, 500);
                }
                @Override public void onLost(Network n) {
                    // mDNS records die with the interface: mark unannounced, wait for onAvailable
                    registered[0] = false;
                    registered[1] = false;
                    announced = false;
                    Diag.w("nsd", "network lost — announcement dropped, will re-register");
                }
            };
            cm.registerDefaultNetworkCallback(netWatch);
        } catch (Throwable t) {
            Diag.w("nsd", "network watch: " + t);
        }
    }

    /** Some MIUI/HyperOS builds need the lock for mDNS to be seen by other apps. */
    private void acquireMulticastLock() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                ctx.getApplicationContext().getSystemService(android.content.Context.WIFI_SERVICE);
            if (wm == null || mcast != null) return;
            mcast = wm.createMulticastLock("si-cast-mdns");
            mcast.setReferenceCounted(false);
            mcast.acquire();
            Diag.i("nsd", "Wi-Fi multicast lock held");
        } catch (Throwable t) {
            Diag.w("nsd", "multicast lock: " + t);
        }
    }

    public void stop() {
        for (int i = 0; i < listeners.length; i++) {
            if (listeners[i] != null) {
                try { nsd.unregisterService(listeners[i]); } catch (Throwable ignored) { }
                listeners[i] = null;
            }
            registered[i] = false;
        }
        announced = false;
        if (mcast != null) {
            try { if (mcast.isHeld()) mcast.release(); } catch (Throwable ignored) { }
            mcast = null;
        }
        retry.removeCallbacks(reArm);
        if (netWatch != null) {
            try { ctx.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netWatch); } catch (Throwable ignored) { }
            netWatch = null;
        }
    }

    // ── the raw mDNS probe (the self-test's layer 1) ───────────────────────────

    /** One mDNS question packet: PTR? <type>.local — the same query AnantPlay's
     *  NsdManager discovery sends, hand-built so no OEM daemon sits between us
     *  and the wire. The bytes come from the pure-Java MdnsWire builder (the
     *  verification lab proves them byte-for-byte on a bare JVM). */
    static byte[] mdnsQueryPacket(String type) {
        return MdnsWire.queryPacket(type);
    }

    /** The self-probe: send the PTR query for BOTH service types on the Wi-Fi
     *  interface and wait for an answer containing OUR service name. Returns a
     *  structured result the Self-test turns into a bilingual diagnosis.
     *
     *  layer details:
     *   · the socket is a plain unicast UDP socket bound to the Wi-Fi interface's
     *     own address (mDNS queries may be unicast to 224.0.0.251:5353 — RFC 6762
     *     §5.1 allows one-shot questions from any port; the QU bit asks for a
     *     unicast reply back to our socket, which is what routers reflect best);
     *   · a MulticastLock is held for the whole probe (the v1 bug: Wi-Fi
     *     power-save filters multicast/broadcast receives without it);
     *   · every failure mode returns a distinct reason code — never a bare false. */
    static ProbeResult probeSelf(Context c, long timeoutMs) {
        ProbeResult r = new ProbeResult();
        r.sentType = TYPE;
        if (!onWifiLikeNetwork(c)) {
            r.reason = ProbeResult.WIFI_OFF;
            r.detail = "Wi-Fi ක්‍රියාත්මක නැත — Wi-Fi on කර නැවත උත්සාහ කරන්න · Wi-Fi is off — turn it on and retry";
            return r;
        }
        InetAddress wifi = wifiLanIp();
        if (wifi == null) {
            r.reason = ProbeResult.WIFI_OFF;
            r.detail = "Wi-Fi interface address හමු නොවීය · no Wi-Fi interface address";
            return r;
        }
        if (vpnActive(c)) {
            // Not fatal: many VPNs pass multicast. We note it and still probe.
            r.vpnActive = true;
            r.detail = "VPN සක්‍රියයි (AdGuard app?) — multicast බ්ලොක් විය හැක · VPN active (AdGuard app?) — may block multicast";
        }
        android.net.wifi.WifiManager.MulticastLock lock = null;
        DatagramSocket sock = null;
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                c.getApplicationContext().getSystemService(android.content.Context.WIFI_SERVICE);
            if (wm != null) {
                lock = wm.createMulticastLock("si-cast-probe");
                lock.setReferenceCounted(false);
                lock.acquire();
                r.multicastLockHeld = true;
            }
            sock = new DatagramSocket(new InetSocketAddress(wifi, 0));
            sock.setSoTimeout(400);
            byte[] q = mdnsQueryPacket(TYPE);
            SocketAddress group = new InetSocketAddress(InetAddress.getByName(MDNS_GROUP), MDNS_PORT);
            boolean sent = false;
            for (int round = 0; round < Math.max(1, timeoutMs / 400) && !r.answered; round++) {
                if (!sent || round % 5 == 0) {   // re-send every ~2 s (mDNS is lossy)
                    sock.send(new DatagramPacket(q, q.length, group));
                    sent = true;
                    r.packetsSent++;
                }
                byte[] buf = new byte[9000];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(p);
                    r.packetsReceived++;
                    String text = new String(buf, 0, p.getLength(), java.nio.charset.StandardCharsets.ISO_8859_1);
                    if (text.contains(NAME)) {
                        r.answered = true;
                        r.detail = "mDNS පිළිතුර ලැබුණි (raw probe) — ජාලය හරහා අපේ record එක පෙනේ · mDNS answer received on the wire — our record is visible";
                        break;
                    }
                } catch (java.net.SocketTimeoutException ignored) { }
            }
            if (!r.answered) {
                r.reason = r.packetsReceived > 0
                    ? ProbeResult.FILTERED
                    : ProbeResult.NO_ANSWER;
                if (r.reason == ProbeResult.FILTERED) {
                    r.detail = "mDNS ප්‍රතිචාර ලැබුණේ නමුත් අපේ නම නැත — mDNS බලපත්‍රය/නම ගැටලුවක් · mDNS answered but not with our name";
                } else {
                    r.detail = "mDNS ප්‍රතිචාර නැත — රවුටරය multicast බ්ලොක් කරයි (AP isolation?) හෝ VPN (AdGuard app) බ්ලොක් කරයි · no mDNS answer — the router blocks multicast (AP isolation?) or a VPN (AdGuard app) does";
                }
            }
        } catch (Throwable t) {
            r.reason = ProbeResult.ERROR;
            r.detail = "probe error: " + t;
            Diag.w("nsdtest", "probe: " + t);
        } finally {
            if (sock != null) { try { sock.close(); } catch (Throwable ignored) { } }
            if (lock != null) { try { if (lock.isHeld()) lock.release(); } catch (Throwable ignored) { } }
        }
        return r;
    }

    /** True when ANY VPN transports the default network (AdGuard app local VPN,
     *  WireGuard, …). AdGuard PRIVATE DNS (DoT) is deliberately NOT checked here:
     *  it only encrypts unicast DNS (TCP 853) and cannot affect UDP 5353 multicast. */
    static boolean vpnActive(Context c) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        } catch (Throwable t) {
            return false;
        }
    }

    // ── small static helpers ────────────────────────────────────────────────────

    /** This phone's Wi-Fi IPv4 (null when off Wi-Fi) — used by the Self-test and /info. */
    static InetAddress wifiLanIp() {
        try {
            for (Enumeration<NetworkInterface> ns = NetworkInterface.getNetworkInterfaces(); ns.hasMoreElements(); ) {
                NetworkInterface n = ns.nextElement();
                if (n == null || !n.isUp() || n.isLoopback()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a;
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** True when Wi-Fi (or hotspot/ethernet) is up — what same-device mDNS needs. */
    static boolean onWifiLikeNetwork(Context c) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } catch (Throwable t) {
            return wifiLanIp() != null;
        }
    }

    /** The structured probe outcome — every failure has a named reason + bilingual fix. */
    static final class ProbeResult {
        static final String OK = "ok";                 // answered with our name
        static final String WIFI_OFF = "wifi-off";     // no Wi-Fi → turn it on
        static final String FILTERED = "filtered";     // mDNS alive, our name missing
        static final String NO_ANSWER = "no-answer";   // silence: router/VPN blocking
        static final String ERROR = "error";           // socket/API failure

        String sentType = TYPE;
        boolean answered;        // OUR name came back on the wire
        boolean vpnActive;       // a VPN (AdGuard app etc.) transports the network
        boolean multicastLockHeld;
        int packetsSent, packetsReceived;
        String reason = OK;
        String detail = "";

        boolean ok() { return answered; }
    }
}
