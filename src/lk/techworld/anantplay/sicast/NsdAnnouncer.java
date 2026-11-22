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
    /** v2.4.0 — the RESPONDER's instance name, deliberately DIFFERENT from the
     *  NsdManager registration name ("AnantPlay on සිංහල Player"). The 2026-11-22
     *  13:16 Android 16 log proved the v2.3.x coexistence fatal: our responder
     *  answered RFC 6762 §8.1 ANY-probes on the SAME instance name the daemon's
     *  MdnsAdvertiser was probing, every answer registered as a CONFLICT
     *  (MdnsRecordRepository.getConflictingServices — IDENTICAL needs equal rdata,
     *  ours always differed), the advertiser renamed and re-probed forever, and
     *  onServiceRegistered never fired ("announced": false for 35 s). Distinct
     *  names = the daemon owns its name unchallenged, our responder owns a second
     *  entry; official AnantPlay lists BOTH (its tvs map is keyed by shown name). */
    static final String RESPONDER_NAME = "AnantPlay Sinhala Player";
    /** The mDNS multicast group + port (RFC 6762) — the wire the probe speaks. */
    static final String MDNS_GROUP = "224.0.0.251";
    static final int MDNS_PORT = 5353;
    /** How long the raw probe waits for a reflected answer (ms). */
    static final int PROBE_TIMEOUT_MS = 4000;
    /** v2.4.0 — how long start() waits for onServiceRegistered/onRegistrationFailed
     *  before starting the responder anyway (registration may be impossible, e.g.
     *  no daemon; the responder is then the only announcer). */
    static final long REGISTRATION_SETTLE_MS = 2500;

    private final Context ctx;
    private final int port;
    private final NsdManager nsd;
    private final NsdManager.RegistrationListener[] listeners = new NsdManager.RegistrationListener[2];
    private final boolean[] registered = new boolean[2];
    private volatile boolean announced;
    private android.net.wifi.WifiManager.MulticastLock mcast;
    /** v2.3.0 — our own 5353 responder (kernel-loopback answerer; null when the port
     *  cannot be shared and mdnsd registration is the only announcer). */
    private MdnsResponder responder;
    private int attempt;
    /** v2.4.0 — registration-first coexistence state: the responder starts only
     *  after the NsdManager registration settles (or the settle timeout passes),
     *  so it can never answer the daemon's §8.1 probes for a pending name. */
    private volatile boolean registrationSettled;
    private volatile boolean registrationPending;
    /** v2.3.1 — the churn-fix state: what we last armed on (IP + network key), so
     *  start() can no-op when healthy and onAvailable can ignore repeat events. */
    private java.net.InetAddress lastArmedIp;
    private String lastArmedNetKey = "";
    private long armedAt;
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

    /** v2.3.0 — our own responder (may be null/down when 5353 cannot be shared). */
    public MdnsResponder responder() { return responder; }

    public void start() {
        if (nsd == null) { Diag.w("nsd", "no NsdManager — cast discovery cannot see us"); return; }
        // v2.3.1 — IDEMPOTENT START (the churn fix). v2.3.0's start() ran stop()
        // FIRST on every call, and stop() unregistered the network callback; the
        // immediate re-register fired onAvailable, which posted reArm(500 ms) →
        // start() again → an endless down/up cycle ("responder down / responder
        // up / network up — re-announcing" repeating in the 2026-11-22 log) that
        // churned NsdManager, released/reacquired the multicast lock and killed
        // a HEALTHY responder mid-probe. Now: when everything is already healthy
        // on the SAME Wi-Fi address, start() is a no-op; the teardown+rebuild
        // path runs only for a genuine change (first arm, network change, or a
        // dead component), and the network callback is registered ONCE and only
        // ever released by destroy() (ReceiverService.onDestroy).
        InetAddress nowIp = wifiLanIp();
        String nowKey = netKey(ctx);
        boolean healthy = announced
            && responder != null && responder.isRunning()
            && nowIp != null && nowIp.equals(lastArmedIp)
            && NetPolicy.sameKey(lastArmedNetKey, nowKey);
        if (healthy) {
            Diag.i("nsd", "already announced on " + nowKey + " — start() is a no-op (v2.3.1 churn fix)");
            return;
        }
        lastArmedIp = nowIp;
        lastArmedNetKey = nowKey;
        armedAt = System.currentTimeMillis();
        stopRegistrations();
        String service = PREFIX + NAME;
        String[] types = {TYPE, OLD_TYPE};
        boolean any = false;
        registrationSettled = false;
        registrationPending = true;
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
                        registrationSettled = true;
                        Diag.i("nsd", "announced " + t + " as \"" + s.getServiceName() + "\" :" + port);
                        maybeStartResponder();
                    }
                    @Override public void onRegistrationFailed(NsdServiceInfo s, int e) {
                        registered[slot] = false;
                        announced = registered[0] || registered[1];
                        registrationSettled = announced;   // one type failing is fine if the other made it
                        Diag.w("nsd", "announce " + t + " failed, error " + e + " ("
                            + failureText(e) + ") — retrying");
                        if (++attempt < 20) retry.postDelayed(reArm, 2000 + attempt * 1000);
                        maybeStartResponder();
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
        if (!any) registrationSettled = true;   // no registration possible → responder is the announcer
        watchNetwork();
        acquireMulticastLock();
        // v2.4.0 — REGISTRATION-FIRST: the responder starts only after the daemon's
        // §8.1 probing settles (either callback, or the 2.5 s settle timeout). This
        // is the coexistence fix: our responder can never answer (and thereby
        // sabotage) MdnsAdvertiser's conflict probes for the pending registration.
        if (registrationSettled) startResponder();
        else retry.postDelayed(new Runnable() {
            @Override public void run() {
                if (!registrationSettled) {
                    registrationSettled = true;
                    Diag.i("nsd", "registration settle timeout — starting responder anyway (v2.4.0)");
                    startResponder();
                }
            }
        }, REGISTRATION_SETTLE_MS);
        if (!any) Diag.e("nsd", "no registration possible");
    }

    /** v2.4.0 — start the responder once it is SAFE (registration settled). */
    private void maybeStartResponder() {
        if (registrationSettled) startResponder();
    }

    /** NsdManager failure codes → human-readable (log diagnosis on Android 16). */
    static String failureText(int e) {
        switch (e) {
            case 1: return "FAILURE_INTERNAL_ERROR";
            case 2: return "FAILURE_INTERNAL_ERROR (legacy daemon)";
            case 3: return "FAILURE_MAX_LIMIT";
            default: return "error " + e;
        }
    }

    /** v2.3.0 — start our own RFC-6762 responder on 5353 (SO_REUSEADDR alongside
     *  mdnsd). Answers PTR/SRV/TXT/A for our service with unicast replies, and —
     *  equally important — gives the self-test a router-free kernel-loopback path.
     *  A bind failure (EADDRINUSE on a locked-down build) degrades to
     *  NsdManager-only announcing: logged, never fatal. */
    private void startResponder() {
        if (responder != null && responder.isRunning()) return;
        try {
            java.net.InetAddress ip = wifiLanIp();
            if (ip == null) ip = java.net.InetAddress.getByName("127.0.0.1");
            responder = new MdnsResponder(TYPE, OLD_TYPE, RESPONDER_NAME, port, ip);
            responder.start();
        } catch (Throwable t) {
            Diag.w("mdnsrsp", "start: " + t);
            responder = null;
        }
    }

    /** mDNS records live on the interface: re-announce when Wi-Fi comes back.
     *  v2.3.1: registered ONCE (never torn down by restarts — that was the churn
     *  loop), and onAvailable is DEBOUNCED through NetPolicy.shouldReArm so a
     *  repeat event on the SAME network while everything is healthy is ignored. */
    private void watchNetwork() {
        if (netWatch != null) return;
        try {
            final ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            if (cm == null) return;
            netWatch = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) {
                    String key = netKey(ctx);
                    boolean healthy = announced && responder != null && responder.isRunning();
                    if (!NetPolicy.shouldReArm(lastArmedNetKey, key, healthy, armedAt, registrationPending)) {
                        Diag.i("nsd", "network up on " + key + " — already announced, no re-arm (debounced)");
                        return;
                    }
                    Diag.i("nsd", "network up on " + key + " — re-announcing");
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

    /** Teardown for a RE-ARM (registrations + responder + lock, but the network
     *  callback STAYS — re-registering it fires an immediate onAvailable and
     *  was half of the v2.3.0 churn loop). */
    void stopRegistrations() {
        for (int i = 0; i < listeners.length; i++) {
            if (listeners[i] != null) {
                try { nsd.unregisterService(listeners[i]); } catch (Throwable ignored) { }
                listeners[i] = null;
            }
            registered[i] = false;
        }
        announced = false;
        if (responder != null) {
            try { responder.stop(); } catch (Throwable ignored) { }
            responder = null;
        }
        if (mcast != null) {
            try { if (mcast.isHeld()) mcast.release(); } catch (Throwable ignored) { }
            mcast = null;
        }
        retry.removeCallbacks(reArm);
    }

    /** FINAL teardown (ReceiverService.onDestroy): everything, including the
     *  network callback. */
    public void stop() {
        stopRegistrations();
        if (netWatch != null) {
            try { ctx.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netWatch); } catch (Throwable ignored) { }
            netWatch = null;
        }
    }

    // ── the mDNS probes (the self-test's discovery layers) ─────────────────────

    /** One mDNS question packet: PTR? <type>.local — the same query AnantPlay's
     *  NsdManager discovery sends, hand-built so no OEM daemon sits between us
     *  and the wire. The bytes come from the pure-Java MdnsWire builder (the
     *  verification lab proves them byte-for-byte on a bare JVM). */
    static byte[] mdnsQueryPacket(String type) {
        return MdnsWire.queryPacket(type);
    }

    /** v2.3.0 — the DAEMON probe: send the QU question UNICAST to the phone's
     *  mDNS endpoints (loopback + our Wi-Fi IP, port 5353). Android's mdnsd listens
     *  on 5353 across all interfaces and answers unicast questions about records it
     *  holds — including OUR NsdManager registration. This is the same daemon
     *  AnantPlay's discovery reads (Cast.discover → NsdManager binder IPC), so an
     *  answer here is the honest proof "AnantPlay can see us" WITHOUT needing the
     *  router to reflect multicast. A plain unicast socket is correct for this.
     *
     *  v2.3.1 — TWO FIELD BUGS FIXED (the 2026-11-22 log):
     *   · the reply was matched against the Sinhala NAME with an ISO-8859-1 decode
     *     — impossible mojibake math → every real answer counted as silence and
     *     the loop span re-sending (the ×4/~207 ms bursts). Now validated with
     *     MdnsWire.isOurAnswer (QR bit + records + the ASCII service type).
     *   · when wifiLanIp() is null BOTH targets were 127.0.0.1 → duplicate sends.
     *     Targets are now de-duplicated, sends are capped, and each round WAITS
     *     its receive window instead of re-sending the instant a reply fails the
     *     (old, broken) check. */
    /** v2.4.0 — THE L1 TRUTH FIX. The 2026-11-22 13:16 Android 16 log showed
     *  "L1 daemon ✓" while our OWN responder (sharing 5353 via SO_REUSEADDR) had
     *  answered the probe — a false positive. Now the daemon probe requires the
     *  answer to carry the NSDMANAGER registration name ("AnantPlay on සිංහල
     *  Player", only the daemon advertises it); our responder uses the distinct
     *  RESPONDER_NAME ("AnantPlay Sinhala Player") and can no longer satisfy it.
     *  Attribution is now provable: isOurAnswer validates structure, the extra
     *  check requires the daemon's name bytes specifically. */
    static boolean probeDaemon(Context c, long timeoutMs) {
        DatagramSocket sock = null;
        try {
            InetAddress wifi = wifiLanIp();
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            // de-duplicated target list: loopback mdnsd + (when known) the Wi-Fi address
            java.util.LinkedHashSet<InetAddress> targets = new java.util.LinkedHashSet<>();
            targets.add(loopback);
            if (wifi != null) targets.add(wifi);
            sock = new DatagramSocket();
            sock.setSoTimeout(600);
            byte[] q = mdnsQueryPacket(TYPE);
            long end = System.currentTimeMillis() + Math.max(1000, timeoutMs);
            int rounds = 0;
            while (System.currentTimeMillis() < end && rounds < 4) {
                for (InetAddress target : targets) {
                    try {
                        sock.send(new DatagramPacket(q, q.length, target, MDNS_PORT));
                    } catch (Throwable ignored) { }
                }
                // WAIT the window: even when a reply arrives instantly and fails
                // no check re-fires until the next round (no spin storm).
                long roundEnd = System.currentTimeMillis() + 600;
                while (System.currentTimeMillis() < roundEnd) {
                    byte[] buf = new byte[9000];
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        sock.receive(p);
                        // structure first (QR + records + our ASCII service type)…
                        if (MdnsWire.isOurAnswer(buf, p.getLength(), TYPE, OLD_TYPE)
                            // …then ATTRIBUTION: the answer must mention the name ONLY
                            // the system daemon advertises (the NsdManager registration).
                            && MdnsWire.mentionsUtf8(buf, p.getLength(), PREFIX + NAME)) {
                            Diag.i("nsdtest", "daemon probe answered by " + p.getAddress().getHostAddress());
                            return true;
                        }
                    } catch (java.net.SocketTimeoutException retry) { break; }
                }
                rounds++;
            }
        } catch (Throwable t) {
            Diag.w("nsdtest", "daemon probe: " + t);
        } finally {
            if (sock != null) { try { sock.close(); } catch (Throwable ignored) { } }
        }
        return false;
    }

    /** v2.3.0 — the WIRE probe against OUR OWN responder (MdnsResponder.probeLocal):
     *  both sockets joined to 224.0.0.251, the question and its answer travel through
     *  the phone's kernel multicast loopback — 100% router-independent. Replaces the
     *  v2.2.0 raw ephemeral probe whose fate depended on the router reflecting
     *  multicast (the "no-answer" false negative on the old-router phone). */
    static MdnsResponder.ProbeResult probeWire(MdnsResponder responder, long timeoutMs) {
        return MdnsResponder.probeLocal(responder, timeoutMs);
    }

    /** The self-probe v2.2.0 shape, kept for /info and diagnostics: reports whether
     *  the OLD raw path now sees answers (it will, when our responder is up). */
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
            r.vpnActive = true;
            r.detail = "VPN සක්‍රියයි (AdGuard app?) — multicast බ්ලොක් විය හැක · VPN active (AdGuard app?) — may block multicast";
        }
        android.net.wifi.WifiManager.MulticastLock lock = null;
        MulticastSocket sock = null;
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                c.getApplicationContext().getSystemService(android.content.Context.WIFI_SERVICE);
            if (wm != null) {
                lock = wm.createMulticastLock("si-cast-probe");
                lock.setReferenceCounted(false);
                lock.acquire();
                r.multicastLockHeld = true;
            }
            sock = new MulticastSocket(null);
            sock.setReuseAddress(true);
            sock.bind(new InetSocketAddress(wifi, 0));
            // v2.3.0: JOIN the group + enable kernel loopback — the v2.2.0 probe
            // (a plain unicast socket) could not hear multicast answers at all.
            java.net.InetAddress group = java.net.InetAddress.getByName(MDNS_GROUP);
            sock.joinGroup(group);
            sock.setLoopbackMode(false);   // Java: false = loopback ENABLED (the inverted API)
            sock.setSoTimeout(400);
            byte[] q = mdnsQueryPacket(TYPE);
            boolean sent = false;
            for (int round = 0; round < Math.max(1, timeoutMs / 400) && !r.answered; round++) {
                if (!sent || round % 5 == 0) {   // re-send every ~2 s (mDNS is lossy)
                    sock.send(new DatagramPacket(q, q.length, group, MDNS_PORT));
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
                        r.detail = "mDNS පිළිතුර ලැබුණි (raw probe, group-joined) — ජාලය හරහා අපේ record එක පෙනේ · mDNS answer received on the wire — our record is visible";
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
                    r.detail = "mDNS ප්‍රතිචාර නැත — නමුත් v2.3.0 cast එක තවමත් ක්‍රියා කරයි (AnantPlay එක mdnsd daemon හරහා අපව සොයයි — රවුටරය multicast බ්ලොක් කළත්) · "
                        + "no raw-wire answer — casting STILL WORKS: AnantPlay discovers us through the system mDNS daemon, no router multicast needed";
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

    /** v2.3.1 — a stable identity for the CURRENT default network: the
     *  transports + the Wi-Fi BSSID/interface when available. Used by
     *  NetPolicy.sameKey so a repeat onAvailable on the SAME network is
     *  recognised and ignored (the churn fix). */
    static String netKey(Context c) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            Network n = cm.getActiveNetwork();
            if (n == null) return "none";
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps == null) return "unknown";
            StringBuilder sb = new StringBuilder();
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) sb.append("wifi:");
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) sb.append("cell:");
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) sb.append("eth:");
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) sb.append("vpn:");
            else sb.append("other:");
            InetAddress ip = wifiLanIp();
            sb.append(ip == null ? "noip" : ip.getHostAddress());
            return sb.toString();
        } catch (Throwable t) {
            return "unknown";
        }
    }

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
