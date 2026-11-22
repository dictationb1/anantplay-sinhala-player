package lk.techworld.anantplay.sicast;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/* v3.1.0 — THE LOCAL-DEVICE ADDRESS POLICY (Bug 1, Smoking Gun #1).
 *
 * THE FIELD FAILURE (2026-11-22 16:36, Xiaomi Android 16, v3.0.0):
 *   official AnantPlay on the SAME phone fetched GET /dlna/desc.xml from
 *   192.168.1.39 (our own wlan0 IP) and ReceiverServer.isSelf() refused it with
 *   `refused-nonlocal` — "The TV refused this video" / "Can't reach සිංහල Player".
 *   The v3.0.0 isSelf() relied SOLELY on NetworkInterface.getNetworkInterfaces()
 *   inside a swallowed try/catch: when the HyperOS stack returns null or throws
 *   (transient netd contention early in process life), a same-device cast is
 *   silently classified as a stranger.
 *
 * THE v3.1.0 RULE SET (each layer independently sufficient, all byte-wise):
 *   1. null remote → refuse (defensive only; accept() never passes null).
 *   2. loopback (127.0.0.0/8, ::1) → self.
 *   3. any-local (0.0.0.0/::) → self.
 *   4. IPv4-mapped IPv6 `::ffff:a.b.c.d` → normalised to the 4-byte IPv4 form
 *      FIRST, so every later comparison sees the same shape (the dual-stack
 *      socket case the v3.0.0 code only half-handled).
 *   5. THE SAME-HOST TCP RULE — the bulletproof one: when the remote address
 *      EQUALS the connection's own local (destination) address, the connection
 *      can only have originated on this phone: AnantPlay connects to the exact
 *      LOCATION we advertise (http://<our-ip>:8765/…), so source == destination.
 *      A LAN stranger (192.168.1.148) connecting to us has source != destination
 *      by construction — the rule can NEVER accept a stranger, and NEVER needs
 *      the interface enumeration that failed on HyperOS.
 *   6. byte-wise equality against EVERY live interface address (NetworkInterface
 *      enumeration, compared by getAddress() bytes — never InetAddress.equals(),
 *      which mismatches IPv6 scope-ids; the v3.0.0 field failure mode).
 *   7. byte-wise equality against the CACHED snapshot of our own addresses
 *      (captured whenever an enumeration succeeds and refreshed by the service's
 *      network callback) — the fallback for the exact HyperOS case where a
 *      fresh enumeration transiently fails.
 *
 * Link-local IPv6 `fe80::…%wlan0`: byte comparison ignores the scope id entirely
 * (the address bytes are identical for the same interface), which is exactly the
 * semantics a same-device check needs.
 *
 * Pure java.net only — the verification lab drives every rule on a bare JVM. */
public final class LocalNet {

    private LocalNet() { }

    /** The live own-address snapshot (refreshed by the host on network changes). */
    public interface Snapshot { List<byte[]> ownAddresses(); }

    /** The 4-byte IPv4 loopback prefix 127.0.0.0/8 — belt and braces for byte arrays. */
    static boolean isLoopbackBytes(byte[] b) {
        return b != null && b.length == 4 && (b[0] & 0xFF) == 127;
    }

    /** True for byte arrays of `::1` (16 zero bytes except the last = 1). */
    static boolean isV6LoopbackBytes(byte[] b) {
        if (b == null || b.length != 16) return false;
        for (int i = 0; i < 15; i++) if (b[i] != 0) return false;
        return b[15] == 1;
    }

    /** Normalise an InetAddress to its comparison form:
     *  IPv4-mapped IPv6 (::ffff:a.b.c.d) → the 4-byte IPv4 array;
     *  everything else → getAddress() verbatim (scope ids never participate). */
    public static byte[] normalize(InetAddress a) {
        if (a == null) return null;
        byte[] raw = a.getAddress();
        if (raw == null) return null;
        if (raw.length == 16 && isIpv4Mapped(raw)) {
            return new byte[]{raw[12], raw[13], raw[14], raw[15]};
        }
        return raw;
    }

    /** True when the 16 bytes are `::ffff:` + a 4-byte IPv4 tail. */
    static boolean isIpv4Mapped(byte[] b) {
        for (int i = 0; i < 10; i++) if (b[i] != 0) return false;
        return (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF;
    }

    /** Byte-wise address equality (never InetAddress.equals — scope-id trap). */
    public static boolean sameHost(byte[] a, byte[] b) {
        return a != null && b != null && Arrays.equals(a, b);
    }

    /** Human form of a normalised address for the refusal log (redaction-safe). */
    public static String describe(byte[] b) {
        if (b == null) return "null";
        try {
            return InetAddress.getByAddress(b).getHostAddress();
        } catch (Throwable t) {
            return Arrays.toString(b);
        }
    }

    /** Human form of an InetAddress with mapping normalisation applied. */
    public static String describe(InetAddress a) { return describe(normalize(a)); }

    /**
     * THE decision (v3.1.0): may the connection whose remote address is `remote`
     * (and whose own destination address was `connLocal`) be served?
     *
     * @param remote    the accepted socket's remote address (never null in practice)
     * @param connLocal the accepted socket's LOCAL address (the IP the client
     *                  connected to — the advertised LOCATION), may be null
     * @param live      the live enumeration of this device's addresses (null ok)
     * @param cached    a cached snapshot of this device's addresses (null ok)
     */
    public static boolean isSelf(InetAddress remote, InetAddress connLocal,
                                 List<byte[]> live, List<byte[]> cached) {
        if (remote == null) return false;
        if (remote.isLoopbackAddress() || remote.isAnyLocalAddress()) return true;
        byte[] rn = normalize(remote);
        if (rn == null) return false;
        if (isLoopbackBytes(rn) || isV6LoopbackBytes(rn)) return true;
        // Rule 5 — the same-host TCP proof: source == destination ⇒ same phone.
        if (connLocal != null && sameHost(rn, normalize(connLocal))) return true;
        // Rules 6+7 — byte-wise membership, live first, then the cached snapshot.
        if (contains(live, rn)) return true;
        return contains(cached, rn);
    }

    /** True when `own` contains an address byte-equal to `b`. */
    static boolean contains(List<byte[]> own, byte[] b) {
        if (own == null || b == null) return false;
        for (byte[] o : own) if (o != null && Arrays.equals(o, b)) return true;
        return false;
    }

    /** Enumerate every address of every interface (comparison form, mapped v6
     *  normalised). Returns an EMPTY list when the stack refuses — never throws,
     *  never returns null; the caller treats empty as "enumeration unavailable",
     *  not as "no local addresses" (the v3.0.0 bug). */
    public static List<byte[]> enumerateOwn() {
        List<byte[]> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ns = NetworkInterface.getNetworkInterfaces();
            if (ns == null) return out;                       // seen on OEM stacks
            for (NetworkInterface n : Collections.list(ns)) {
                if (n == null) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    byte[] b = normalize(a);
                    if (b != null) out.add(b);
                }
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /** Every distinct address in both lists (cached snapshot refresh helper). */
    public static List<byte[]> union(List<byte[]> a, List<byte[]> b) {
        List<byte[]> out = new ArrayList<>();
        if (a != null) for (byte[] x : a) if (x != null && !contains(out, x)) out.add(x);
        if (b != null) for (byte[] x : b) if (x != null && !contains(out, x)) out.add(x);
        return out;
    }
}
