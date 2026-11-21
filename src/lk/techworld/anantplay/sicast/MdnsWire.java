package lk.techworld.anantplay.sicast;

import java.util.Locale;

/* Pure-Java mDNS wire helpers (RFC 6762) — extracted from NsdAnnouncer so the
 * verification lab can prove the exact bytes the phone puts on the wire, on a
 * bare JVM, with no Android classes involved.
 *
 * The one-shot unicast question:
 *   header:  id 0, flags 0, QDCOUNT 1
 *   QNAME:   <type>.local   (label-encoded, terminated by the root label)
 *   QTYPE:   12 (PTR)       — "which services of this type exist?"
 *   QCLASS:  1 (IN) | 0x8000 (QU — unicast the reply; best through routers that
 *            filter multicast reflection, and it is what makes the self-probe
 *            work even on APs with multicast snooping quirks)
 *
 * Deterministic by construction: same input → byte-identical packet (proven by
 * the lab), so a capture from the phone can be diffed against the lab's bytes. */
final class MdnsWire {

    private MdnsWire() { }

    /** Build the PTR question packet for "<type>.local". */
    static byte[] queryPacket(String type) {
        String t = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream(64);
        b.write(0); b.write(0);            // ID 0
        b.write(0); b.write(0);            // flags: a plain query
        b.write(0); b.write(1);            // QDCOUNT 1
        b.write(0); b.write(0);            // ANCOUNT 0
        b.write(0); b.write(0);            // NSCOUNT 0
        b.write(0); b.write(0);            // ARCOUNT 0
        for (String label : new String[]{t, "local"}) {
            byte[] lb = label.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            b.write(lb.length);
            b.write(lb, 0, lb.length);
        }
        b.write(0);                        // the root label
        b.write(0); b.write(12);           // QTYPE 12 = PTR
        b.write(0); b.write(0x01 | 0x80);  // QCLASS: IN + the QU unicast bit
        return b.toByteArray();
    }

    /** True when a captured mDNS answer payload mentions the given name (our
     *  service name check on the probe's receive path). */
    static boolean mentions(byte[] payload, int len, String name) {
        if (payload == null || name == null) return false;
        int n = Math.min(len, payload.length);
        String text = new String(payload, 0, n, java.nio.charset.StandardCharsets.ISO_8859_1);
        return text.contains(name);
    }
}
