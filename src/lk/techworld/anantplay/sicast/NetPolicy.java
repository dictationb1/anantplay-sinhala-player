package lk.techworld.anantplay.sicast;

/* v2.3.1 — THE NETWORK RE-ARM POLICY (pure Java + one Android hook).
 *
 * THE FIELD BUG (2026-11-22 log, lines 25.304–25.321 and repeating):
 *   NsdAnnouncer.start() began with stop(); stop() UNREGISTERED the network
 *   callback; start() then registered a NEW one — and registerDefaultNetwork
 *   Callback fires onAvailable IMMEDIATELY with the current state. That
 *   onAvailable posted reArm(500 ms) → start() → stop() → register →
 *   onAvailable → … an endless down/up cycle that tore down a HEALTHY
 *   responder ("responder down (answered 12 queries)" right after it had
 *   answered 12 queries!), churned NsdManager registrations, and released/
 *   reacquired the multicast lock roughly twice a second.
 *
 * THE POLICY (two questions, both answered WITHOUT touching Android state):
 *   1. sameKey(last, now)  — is this the same network we already armed on?
 *   2. shouldReArm(last, now, healthy, armedAt) — re-arm ONLY when the
 *      network genuinely changed, or something is unhealthy, or we have
 *      never armed. A repeat onAvailable on the same network while healthy
 *      is ignored — and as belt-and-braces, any re-arm within 10 s of the
 *      last one is ignored too (OEM stacks sometimes replay events).
 *
 * Pure Java so the lab drives the exact truth table on a bare JVM; the
 * Android side (NsdAnnouncer) supplies netKey(ctx) via ConnectivityManager. */
final class NetPolicy {

    private NetPolicy() { }

    /** True when the two network keys identify the same network ("" counts as
     *  unknown → NOT the same, so the first arm always goes through). */
    static boolean sameKey(String last, String now) {
        if (last == null || now == null) return false;
        if (last.isEmpty() || now.isEmpty()) return false;
        return last.equals(now);
    }

    /** The one decision: should onAvailable cause a re-arm?
     *  healthy = announced && responder running (the caller computes it).
     *
     *  Order matters: an UNHEALTHY stack re-arms immediately even if we armed
     *  only moments ago (a dead responder must be rebuilt, never ignored) —
     *  the replay guard applies ONLY to the healthy/same-network case, where a
     *  repeat onAvailable carries no new information at all. */
    static boolean shouldReArm(String lastKey, String nowKey, boolean healthy, long armedAtMs) {
        // never armed (or armed before this policy existed) → arm
        if (armedAtMs <= 0) return true;
        // a different network (Wi-Fi ↔ mobile ↔ another AP) → arm
        if (!sameKey(lastKey, nowKey)) return true;
        // same network but something died (announced=false / responder down) → arm
        if (!healthy) return true;
        // same network, healthy: a repeat onAvailable carries no new information —
        // the registration and the responder are already serving. (v2.3.0 churned
        // exactly here: every repeat event tore down a HEALTHY responder.)
        return false;
    }
}
