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

    /** Events closer than this to the last arm are ignored while a registration
     *  is still pending — registerDefaultNetworkCallback fires onAvailable
     *  IMMEDIATELY (the 2026-11-22 13:16 Android 16 log: armed at 0.003, "network
     *  up — re-announcing" at 0.050 because announced was still false, teardown at
     *  0.559 killed the pending registration mid-probe). The first registration
     *  must be allowed to finish. */
    static final long ARM_GRACE_MS = 5_000;

    /** The one decision: should onAvailable cause a re-arm?
     *  healthy = announced && responder running (the caller computes it).
     *
     *  v2.4.0: the grace window — an onAvailable on the SAME network inside
     *  ARM_GRACE_MS of arming, while the registration is still pending, is the
     *  callback's own immediate initial event, NOT a network change: ignore it.
     *  (The v2.3.1 policy treated "not yet announced" as unhealthy and tore the
     *  pending registration down 500 ms after arming — the 0.559 restart in the
     *  Android 16 log.) An UNHEALTHY stack still re-arms once the grace window
     *  has passed, so a genuinely dead registration is retried. */
    static boolean shouldReArm(String lastKey, String nowKey, boolean healthy, long armedAtMs) {
        return shouldReArm(lastKey, nowKey, healthy, armedAtMs, false);
    }

    static boolean shouldReArm(String lastKey, String nowKey, boolean healthy, long armedAtMs,
                               boolean registrationPending) {
        // never armed (or armed before this policy existed) → arm
        if (armedAtMs <= 0) return true;
        // a different network (Wi-Fi ↔ mobile ↔ another AP) → arm
        if (!sameKey(lastKey, nowKey)) return true;
        // v2.4.0: same network, inside the grace window, registration still in
        // flight → this is the callback's immediate initial event: ignore it
        if (registrationPending
            && System.currentTimeMillis() - armedAtMs < ARM_GRACE_MS) return false;
        // same network but something died (announced=false / responder down) → arm
        if (!healthy) return true;
        // same network, healthy: a repeat onAvailable carries no new information —
        // the registration and the responder are already serving. (v2.3.0 churned
        // exactly here: every repeat event tore down a HEALTHY responder.)
        return false;
    }
}
