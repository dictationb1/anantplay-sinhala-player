package lk.techworld.anantplay.sicast;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;

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
 * Registration is retried (HyperOS sometimes drops it), and re-done whenever the
 * network comes back (Wi-Fi re-associate), because mDNS records live on the interface.
 *
 * Audit fixes (Gate 1):
 *   · per-type registration state — one type failing no longer marks us "not announced"
 *     while the other type is live (the setup checklist used to flip-flop);
 *   · a Wi-Fi multicast lock is held while announcing (some MIUI builds drop mDNS
 *     packets without it), released in stop();
 *   · network callbacks are registered once and always unregistered — no listener leak
 *     across Wi-Fi toggles;
 *   · every NsdManager call is individually wrapped: NSD can throw
 *     IllegalArgumentException("listener already in use") on re-registration. */
public final class NsdAnnouncer {

    static final String TYPE = "_anantplay._tcp";
    static final String OLD_TYPE = "_fmhyplay._tcp";
    static final String PREFIX = "AnantPlay on ";
    static final String NAME = "සිංහල Player";

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

    /** True when Wi-Fi (or hotspot) is up — what same-device mDNS needs. */
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
}
