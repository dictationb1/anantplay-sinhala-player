package lk.techworld.anantplay.sicast;

/* v3.1.0 — THE NON-DESTRUCTIVE NSD RE-DRIVE POLICY (Bug 3, Smoking Gun #3).
 *
 * THE FIELD FAILURE (2026-11-22 16:36 log):
 *   24.341 W/nsd: registration silent … re-driving (attempt 2)
 *   24.351 I/mdnsrsp: responder down
 * v3.0.0's watchdog called start(); start()'s healthy gate required
 * announced==true, which is NEVER true during the very MdnsAdvertiser silent-hang
 * the watchdog exists for — so the gate fell through to stopRegistrations(),
 * which executed responder.stop() on a perfectly HEALTHY MdnsResponder (the
 * mDNS pillar AnantPlay's casts were actively using), then re-created it 2.5 s
 * later. Every 12 s (then every 120 s) the cycle repeated.
 *
 * THE v3.1.0 RULES (pure Java, lab-proven, driven by NsdAnnouncer):
 *   · The watchdog — whose ONLY purpose is to hand NsdManager a fresh
 *     transaction id for registerService() — re-registers the NSD records and
 *     NOTHING ELSE: a running MdnsResponder, its multicast lock and the
 *     DlnaRenderer are never touched by a watchdog re-drive.
 *   · A watchdog re-drive may START the responder when it is down (it is the
 *     announcer of last resort while the daemon hangs), but never stop it.
 *   · Only a GENUINE network change (a different Wi-Fi / IP) may fully re-arm —
 *     and even then only when the responder is actually unhealthy or the
 *     network it was armed on is gone; same-network repeats stay no-ops
 *     (NetPolicy's debounce, unchanged).
 *
 * The enum carries the exact allowed actions so the lab can assert, per state,
 * that "stop a healthy responder" is unreachable from every watchdog path. */
final class AnnouncerPolicy {

    private AnnouncerPolicy() { }

    /** What a caller is allowed to do after a policy decision. */
    enum Scope {
        /** Nothing to do (healthy / destroyed / duplicate event). */
        NONE,
        /** Re-register NsdManager records with fresh transaction ids ONLY —
         *  NEVER stop the responder, the multicast lock or the DLNA renderer. */
        RE_REGISTER_ONLY,
        /** Re-register AND bring the responder up (it is allowed to start a
         *  stopped responder; still never to stop a running one). */
        RE_REGISTER_AND_START_RESPONDER,
        /** A genuine network change: full teardown + re-arm is allowed. */
        FULL_REARM
    }

    /** True when the silent-hang watchdog should fire at all (the v3.0.0
     *  condition, unchanged): never answered, never announced, not destroyed. */
    static boolean watchdogFires(boolean registrationAnswered, boolean announced, boolean destroyed) {
        return !destroyed && !registrationAnswered && !announced;
    }

    /** The watchdog's allowed scope: re-drive NSD registrations; the responder
     *  and the DLNA renderer are sacrosanct (may only be STARTED, never stopped). */
    static Scope watchdogScope(boolean responderRunning) {
        return responderRunning ? Scope.RE_REGISTER_ONLY : Scope.RE_REGISTER_AND_START_RESPONDER;
    }

    /** May this scope STOP a running responder/multicast lock? Only a genuine
     *  FULL_REARM may — and no watchdog path can produce one. */
    static boolean mayStopResponder(Scope s) { return s == Scope.FULL_REARM; }

    /** May this scope START a stopped responder? Everything except NONE. */
    static boolean mayStartResponder(Scope s) { return s != Scope.NONE; }

    /** A network event's scope: same network while everything is healthy → NONE
     *  (the v2.3.1/v2.4.0 debounce, expressed here so the lab owns it); a
     *  different network → FULL_REARM (the records were bound to the old
     *  interface); the same network with a dead stack → RE_REGISTER_AND_START_
     *  RESPONDER (bring pillars back, but the responder teardown is pointless —
     *  it is already down). */
    static Scope networkEventScope(boolean sameNetwork, boolean healthy, boolean registrationPending,
                                   long sinceArmMs, long graceMs) {
        if (sameNetwork) {
            // the callback's own immediate initial event, registration in flight
            if (registrationPending && sinceArmMs < graceMs) return Scope.NONE;
            if (healthy) return Scope.NONE;
            return Scope.RE_REGISTER_AND_START_RESPONDER;
        }
        return Scope.FULL_REARM;
    }
}
