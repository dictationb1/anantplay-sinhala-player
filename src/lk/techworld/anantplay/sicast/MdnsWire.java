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
        // QCLASS: IN (1) + the QU top bit (0x8000) — RFC 6762 §5.4 "unicast response
        // preferred". v2.3.0 FIX: v2.2.0 wrote 0x00,0x81 (the bit in the LOW byte =
        // class 129 — meaningless), so the QU flag was never actually on the wire and
        // one-shot queriers got multicast replies they could not receive on their
        // ephemeral ports. The real unicast-reply request is the TOP bit of the
        // 16-bit class field: bytes 0x80, 0x01.
        b.write(0x80); b.write(0x01);
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

    // ───────────────────────────────────────────────────────────────────────────
    // v2.3.1 — THE CHARSET-SAFE ANSWER VALIDATOR (the paradox fix)
    //
    // THE FIELD BUG (Xiaomi HyperOS, 2026-11-22 11:15): the responder logged
    // "answered a unicast(QU) query from 127.0.0.1:…" 16+ times while the
    // Self-test reported "mDNS FAILED (no-answer)". The replies ARRIVED — the
    // probes decoded them as ISO-8859-1 and searched for the Sinhala instance
    // name "සිංහල Player" (15 UTF-8 bytes for "සිංහල"). Those bytes decoded
    // as Latin-1 are "à·ƒà·â€¦" mojibake that .contains() can never match, so
    // every genuinely-received reply was counted as silence and the probe span
    // at full speed re-sending (the 4-packet/~207 ms storm in the log).
    //
    // THE FIX: validate STRUCTURALLY and only against ASCII, never against the
    // instance name's charset:
    //   · the packet must be a RESPONSE (the QR bit set — our own query has it
    //     clear, so a looped-back question can never false-positive);
    //   · it must carry at least one record (AN+NS+AR > 0);
    //   · the pure-ASCII service type ("_anantplay._tcp") must appear in the
    //     packet — for OUR OWN responder's reply that is always true, and an
    //     ISO-8859-1 decode is safe because ASCII is invariant in Latin-1.
    // mentionsUtf8() remains for the OPTIONAL instance-name detail check and
    // decodes UTF-8 correctly.
    // ───────────────────────────────────────────────────────────────────────────

    /** True when `pkt` is an mDNS RESPONSE carrying records for our service
     *  type — charset-independent by construction (ASCII-only matching).
     *  `type` is the primary service type ("_anantplay._tcp"); `legacyType`
     *  may be "" (not checked). */
    static boolean isOurAnswer(byte[] pkt, int len, String type, String legacyType) {
        if (pkt == null || len < 12 || pkt.length < 12) return false;
        if ((pkt[2] & 0x80) == 0) return false;                 // not a response
        int records = (((pkt[6] & 0xFF) << 8) | (pkt[7] & 0xFF))               // AN
                    | (((pkt[8] & 0xFF) << 8) | (pkt[9] & 0xFF))               // NS
                    | (((pkt[10] & 0xFF) << 8) | (pkt[11] & 0xFF));            // AR
        if (records == 0) return false;                          // nothing to read
        String want = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if (want.isEmpty()) return false;
        int n = Math.min(len, pkt.length);
        // ASCII is invariant in ISO-8859-1 → this decode can never mojibake
        String text = new String(pkt, 0, n, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (!text.contains(want)) {
            String legacy = legacyType == null ? "" : legacyType.trim().toLowerCase(Locale.ROOT);
            if (legacy.isEmpty() || !text.contains(legacy)) return false;
        }
        return true;
    }

    /** UTF-8-correct name search inside a captured packet (the instance-name
     *  check the old mojibake code tried to do). */
    static boolean mentionsUtf8(byte[] payload, int len, String name) {
        if (payload == null || name == null) return false;
        int n = Math.min(len, payload.length);
        String text = new String(payload, 0, n, java.nio.charset.StandardCharsets.UTF_8);
        return text.contains(name);
    }
}
