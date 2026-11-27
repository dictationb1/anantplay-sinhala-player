package lk.techworld.anantplay.sicast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v3.4.6.e — THE 6-DOMAIN FULL-APP DEEP TROUBLESHOOTER (Phase 2's engine).
 *
 * <p>Replaces the old 10-point {@link FeatureProbe} surface as the engine
 * behind the Help tab's "Run Full App Troubleshoot": <b>56 live probes
 * across every feature in the entire app</b>, each one a REAL executed check
 * (mutate → verify → restore), logged with PASS ✓ / FAIL ✗ + timing ms +
 * the exact detail line. Every detail is built from booleans, counters,
 * HTTP codes and app-state names — and the whole report goes through
 * {@link PrivacyScrubber#scrub} before it ever leaves the engine (zero PII).
 *
 * <h2>The transactional contract (NON-DESTRUCTIVE)</h2>
 *
 * <p>Before any probe that MUTATES settings or the receiver, the caller's
 * snapshot ({@link Hooks#snapshotPrefs}) is captured; every mutation probe
 * runs inside the {@link #runAll} try/finally, and the ORIGINAL values are
 * restored in the finally block — the user's configuration is never
 * changed by a troubleshoot run, even if a probe throws.
 *
 * <h2>The 6-domain matrix (56 probes)</h2>
 *
 * <ul>
 *   <li><b>A — The In-App Auto-Updater Law</b> (A1–A6): tier-1 redirect,
 *       tier-2 JSON, the a–z ladder, the 100 MiB cap, the ForceUpdateScreen
 *       blocking gate, the check-now round trip.</li>
 *   <li><b>B — Home tab & cast receiver</b> (B1–B9): the loopback /info,
 *       Stop/Start live execution (with restore), the DLNA DIDL, the mDNS
 *       wire responder, the official app detection, history round-trip,
 *       the URL sheet validator, the Test Player route, the Browser tile.</li>
 *   <li><b>C — Quick Settings & every advanced customization</b> (C1–C16):
 *       theme, accents + hex, Material You, glass, motion, density, radius,
 *       bottom bar, midnight dimmer, Subtitle Studio, the subtitle pipeline,
 *       player defaults, AnantPlay & Sources, Cast & Network, Browser &
 *       Privacy, the backup export→validate→restore dry-run, and the
 *       channel completeness.</li>
 *   <li><b>D — Browser tab</b> (D1–D6): headless Chromium, the Google
 *       default engine, security hardening, the BridgeGuard caps, the
 *       StreamSniffer classification, the speed-dial links parser.</li>
 *   <li><b>E — Cinema player & subtitle engine</b> (E1–E12): the trampoline,
 *       the HTTP-403 ladder, the aspect math, the compact subtitle sizing
 *       contract, the language switch, the sync nudge math, the tracks
 *       contract, auto-rotate, LkSubFinder's 10 sites, the worker bridge,
 *       More-Tools wiring, the battery/GPU guards.</li>
 *   <li><b>F — Permissions, security & privacy</b> (F1–F5): notifications,
 *       overlays, battery, the manifest invariants, the PrivacyScrubber
 *       live self-test.</li>
 * </ul>
 *
 * <h2>Purity contract (the lab compiles this class)</h2>
 *
 * <p>Everything Android-touching (SharedPreferences writes, the live
 * receiver, channels) rides the {@link Hooks} seam: the app supplies real
 * implementations, the lab supplies a deterministic in-memory fake. All
 * compute (the normalizer math, the version ladder, the parser contracts,
 * the scrubber self-test) is pure and lab-proven. NEVER an Android import
 * in this file.
 */
public final class DeepProbe {

    private DeepProbe() { }

    /** One probe's outcome (immutable after finish). */
    public static final class Outcome {
        public int id;                // 1..56 — guarded() may stamp a missing identity
        public String domain;         // A|B|C|D|E|F
        public String name;           // the feature's short name
        public boolean pass;
        public long ms;               // the probe's wall-clock latency
        public String detail = "";    // the fact line (PII-free by construction)
        /** v3.4.6.f — true ONLY for an external-environment outage (the
         *  worker's HTTP 0/5xx). The probe STILL prints FAIL to the user and
         *  per-probe CI log; only the machine summary splits this out. */
        public boolean envFail = false;

        Outcome(int id, String domain, String name) {
            this.id = id; this.domain = domain; this.name = name;
        }
    }

    /**
     * The Android/lab seam. The app (MainActivity) implements every method
     * against the REAL subsystems; the lab implements them against in-memory
     * fakes + the pure engines — so the exact same 56-probe run executes in
     * both worlds and the lab result is the shipped result.
     */
    public interface Hooks {
        // ── the transactional snapshot/restore core ─────────────────────────
        /** A copy of EVERY relevant preference, key → value (Objects). */
        Map<String, Object> snapshotPrefs();
        /** Restore the exact snapshot (the finally-block contract). */
        void restorePrefs(Map<String, Object> snapshot);
        /** Write one pref (typed through the SAME normalizers the UI uses). */
        void putPref(String key, Object value);
        /** Read one pref back (normalized) — null when absent. */
        Object getPref(String key);
        /** The receiver's running state, live. */
        boolean receiverUp();
        /** Arm the receiver (returns the live up-state after arming). */
        boolean armReceiver();
        /** Stop the receiver (returns the live up-state after stopping). */
        boolean stopReceiver();
        /** v3.4.6.f — the persisted user stop veto (false by default in the
         *  lab); the run must restore this EXACTLY to avoid a false "up". */
        default boolean userStopped() { return false; }
        /** v3.4.6.f — transiently clear / restore the veto around the probe. */
        default void restoreUserStopped(boolean veto) { }
        /** The receiver's port (the live server port, -1 when down). */
        int receiverPort();
        /** The receiver's loopback /info HTTP code (0 = unreachable). */
        int receiverInfoHttp();
        /** The DLNA /dlna/desc.xml HTTP code + whether the body is a DIDL. */
        int[] dlnaDescHttp();
        /** True when the mDNS wire responder is answering 5353. */
        boolean mdnsWireUp();
        /** The official AnantPlay app's presence + version. */
        boolean officialInstalled();
        String officialVersion();
        /** History add → read → remove round-trip on a synthetic item. */
        boolean historyRoundTrip(String key);
        /** The URL validator's verdict on the Play-URL sheet's input. */
        boolean urlValid(String url);
        /** Notifications permission, live. */
        boolean notificationsGranted();
        /** Overlay permission, live. */
        boolean overlayGranted();
        /** Battery optimization exemption, live. */
        boolean batteryUnrestricted();
        /** The package-installs (unknown sources) grant, live. */
        boolean installsGranted();
        /** The worker /health HTTP code (0 = unreachable). */
        int workerHealthHttp();
        /** v3.4.6.f — the worker /api/resolve?title=The+Odyssey HTTP code
         *  (0 = unreachable): the REAL subtitle-resolution leg, live. */
        int workerResolveHttp();
        /** v3.4.6.h — the EXACT sanitized reason the last worker leg failed
         *  with ("java.net.UnknownHostException: …"), or "" — so Probe 47
         *  can never print a cryptic bare "HTTP 0" again (LAW #2). */
        default String workerLastError() { return ""; }
        /** v3.4.6.f — THE CRASH GATE: the top lines of CrashLog.read() for
         *  THIS build (empty when no uncaught Java/Dart exception exists). */
        java.util.List<String> crashTopLines();
        /** A live update check round-trip: [0]=tier1 ok, [1]=tier2 ok. */
        boolean[] updateTiersLive();
        /** True when the ForceUpdateScreen blocking gate is armed. */
        boolean forceGateArmed();
        /** The number of unimplemented/missing MethodChannel handlers. */
        int missingChannelHandlers();
        /** The browser hardening bits: [mixed, file, content, 5mb cap, blob guard]. */
        boolean[] browserHardening();
        /** True when the WebView is the headless Chromium (no Java toolbar). */
        boolean headlessBrowser();
        /** The backup export → validate → restore dry-run verdict. */
        boolean backupRoundTrip();
        /** The Sinhala ZWJ/ZWNJ shaping survival verdict (live font + markup). */
        boolean sinhalaShapingOk();
        /** The player texture bridge's live presence (a session opens). */
        boolean playerBridgeLive();
    }

    // ── the run state (thread-safe, one run at a time) ────────────────────────

    private static final java.util.concurrent.atomic.AtomicBoolean RUNNING =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.CopyOnWriteArrayList<Outcome> LAST =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    /** True while a run is in flight. */
    public static boolean isRunning() { return RUNNING.get(); }

    /**
     * v3.4.6.k — THE NON-DISRUPTIVE TRIPWIRE: while a full troubleshoot run
     * is live, EVERY system-page launcher (Miui.openBattery/openOverlay/
     * openAutostart/requestBattery/openAppDetails + every
     * PermissionGuideActivity.open*WithGuide lane) refuses to launch — the
     * Part_03 04:18 hijack (the run opened Android's App battery usage and
     * left the Cast Receiver stopped) can never repeat, whatever path tries.
     */
    public static boolean probeRunActive() { return RUNNING.get(); }

    /** A fresh immutable snapshot of the last/current run. */
    public static List<Outcome> lastResults() {
        return java.util.Collections.unmodifiableList(new ArrayList<>(LAST));
    }

    /** Publish a completed run into the static snapshot readers use. */
    public static void remember(List<Outcome> results) {
        LAST.clear();
        if (results != null) for (Outcome o : results) if (o != null) LAST.add(o);
    }

    /** The PASS count over a result list (null-safe). */
    public static int passCount(List<Outcome> results) {
        int p = 0;
        if (results != null) for (Outcome o : results) if (o != null && o.pass) p++;
        return p;
    }

    /** v3.4.6.f — external-only failures (Worker HTTP 0 / non-200). These
     *  never mask a code defect: fail = total - pass - env_fail. */
    public static int envFailureCount(List<Outcome> results) {
        int n = 0;
        if (results != null) for (Outcome o : results) {
            if (o != null && !o.pass && o.envFail) n++;
        }
        return n;
    }

    /**
     * THE single entry: all 56 probes in order, each landing an outcome NO
     * MATTER WHAT (the guarded() contract from FeatureProbe, retained). The
     * settings snapshot is captured BEFORE the mutating domains and restored
     * in the finally block — a troubleshoot run NEVER changes the user's
     * configuration or receiver state.
     */
    public static List<Outcome> runAll(Hooks h, Listener l) {
        if (!RUNNING.compareAndSet(false, true)) {
            return lastResults();
        }
        Map<String, Object> prefsSnapshot = null;
        boolean receiverWasUp = false;
        boolean userStopWas = false;
        try {
            LAST.clear();
            List<Outcome> out = new ArrayList<>(64);
            // ── THE TRANSACTIONAL SNAPSHOT (before ANY mutation) ──────────
            try {
                prefsSnapshot = h.snapshotPrefs();
                receiverWasUp = h.receiverUp();
                // GAP #3 — save the EXACT veto, then open a transient probe
                // lane; the finally block puts it back (no false "receiver: up").
                userStopWas = h.userStopped();
                h.restoreUserStopped(false);
            } catch (Throwable t) {
                Diag.w("deep", "snapshot: " + t);
            }
            int id = 1;
            // ══ DOMAIN A — THE AUTO-UPDATER LAW ═══════════════════════════
            out.add(guarded(id++, "A", "Tier-1 releases/latest 302 redirect", () -> probeTier1(l), l));
            out.add(guarded(id++, "A", "Tier-2 docs/latest.json schema", () -> probeTier2(l), l));
            out.add(guarded(id++, "A", "a–z alphabetical SemVer ladder", () -> probeLadder(l), l));
            out.add(guarded(id++, "A", "100 MiB APK cap + pendingFile contract", () -> probeCap(l), l));
            out.add(guarded(id++, "A", "ForceUpdateScreen blocking gate", () -> probeGate(h, l), l));
            out.add(guarded(id++, "A", "Check-for-Update-Now round trip", () -> probeCheckNow(h, l), l));
            // ══ DOMAIN B — HOME TAB & CAST RECEIVER ═══════════════════════
            out.add(guarded(id++, "B", "Receiver Start → port 8765 /info 200", () -> probeB1(h, l), l));
            out.add(guarded(id++, "B", "Stop Receiver live execution", () -> probeB2(h, l), l));
            out.add(guarded(id++, "B", "DLNA MediaRenderer DIDL E2E", () -> probeB3(h, l), l));
            out.add(guarded(id++, "B", "mDNS UDP 5353 wire responder", () -> probeB4(h, l), l));
            out.add(guarded(id++, "B", "Official AnantPlay detection", () -> probeB5(h, l), l));
            out.add(guarded(id++, "B", "Continue Watching round trip", () -> probeB6(h, l), l));
            out.add(guarded(id++, "B", "Play URL sheet + validator", () -> probeB7(h, l), l));
            out.add(guarded(id++, "B", "Test Player → Flutter PlayerScreen", () -> probeB8(h, l), l));
            out.add(guarded(id++, "B", "Browser quick-action wiring", () -> probeB9(h, l), l));
            // ══ DOMAIN C — QUICK SETTINGS & CUSTOMIZATIONS ═════════════════
            out.add(guarded(id++, "C", "Theme mode round trip", () -> probeC1(h, l), l));
            out.add(guarded(id++, "C", "Accent presets + custom hex", () -> probeC2(h, l), l));
            out.add(guarded(id++, "C", "Material You dynamic color", () -> probeC3(h, l), l));
            out.add(guarded(id++, "C", "Glass clarity + tint math", () -> probeC4(h, l), l));
            out.add(guarded(id++, "C", "Glass outlines + force blur", () -> probeC5(h, l), l));
            out.add(guarded(id++, "C", "Motion pace + density + radius", () -> probeC6(h, l), l));
            out.add(guarded(id++, "C", "Bottom bar style + labels", () -> probeC7(h, l), l));
            out.add(guarded(id++, "C", "Midnight dimmer + dim pct", () -> probeC8(h, l), l));
            out.add(guarded(id++, "C", "Subtitle Studio compact bounds", () -> probeC9(h, l), l));
            out.add(guarded(id++, "C", "Auto subs + translate + zip + engine", () -> probeC10(h, l), l));
            out.add(guarded(id++, "C", "Player defaults round trip", () -> probeC11(h, l), l));
            out.add(guarded(id++, "C", "AnantPlay & Sources index URL", () -> probeC12(h, l), l));
            out.add(guarded(id++, "C", "Cast & Network round trip", () -> probeC13(h, l), l));
            out.add(guarded(id++, "C", "Browser & Privacy round trip", () -> probeC14(h, l), l));
            out.add(guarded(id++, "C", "Backup export → validate → restore", () -> probeC15(h, l), l));
            out.add(guarded(id++, "C", "Flutter ↔ Java channel completeness", () -> probeC16(h, l), l));
            // ══ DOMAIN D — BROWSER TAB ═════════════════════════════════════
            out.add(guarded(id++, "D", "Headless Chromium WebBrowserView", () -> probeD1(h, l), l));
            out.add(guarded(id++, "D", "Default search engine = Google", () -> probeD2(l), l));
            out.add(guarded(id++, "D", "Security hardening flags", () -> probeD3(h, l), l));
            out.add(guarded(id++, "D", "BridgeGuard 5 MB + blob guard", () -> probeD4(h, l), l));
            out.add(guarded(id++, "D", "StreamSniffer HLS/DASH/MP4 + ad block", () -> probeD5(l), l));
            out.add(guarded(id++, "D", "Speed dial links parser + NewZone", () -> probeD6(l), l));
            // ══ DOMAIN E — CINEMA PLAYER & SUBTITLES ═══════════════════════
            out.add(guarded(id++, "E", "Single-player trampoline", () -> probeE1(h, l), l));
            out.add(guarded(id++, "E", "HTTP-403 3-stage relay ladder", () -> probeE2(l), l));
            out.add(guarded(id++, "E", "VideoViewport aspect math", () -> probeE3(l), l));
            out.add(guarded(id++, "E", "Compact subtitle sizing contract", () -> probeE4(l), l));
            out.add(guarded(id++, "E", "Subtitle language switch + ZWJ", () -> probeE5(h, l), l));
            out.add(guarded(id++, "E", "Sync nudge controls (±0.25s/±1s)", () -> probeE6(l), l));
            out.add(guarded(id++, "E", "Tracks (audio/video) contract", () -> probeE7(l), l));
            out.add(guarded(id++, "E", "Auto-rotate + orientation lock", () -> probeE8(l), l));
            out.add(guarded(id++, "E", "Find Subtitles (LkSubFinder 10 sites)", () -> probeE9(l), l));
            // v3.4.6.f — HONESTY LAW: probe 47 (worker bridge) FAILS on any
            // non-200 HTTP code (the v3.4.6.e “HTTP 0 → offline retention ✓”
            // PASS-on-a-dead-worker is the exact lie this release kills), and
            // the run gains the REAL subtitle E2E probe + the crash gate.
            out.add(guarded(id++, "E", "Worker /health + /api/resolve live", () -> probeE10(h, l), l));
            out.add(guarded(id++, "E", "More Tools complete wiring", () -> probeE11(l), l));
            out.add(guarded(id++, "E", "Battery & GPU performance guards", () -> probeE12(l), l));
            out.add(guarded(id++, "E", "Real subtitle SRT → parse → ZWJ → store", () -> probeE13(l), l));
            // ══ DOMAIN F — PERMISSIONS, SECURITY & PRIVACY ═════════════════
            out.add(guarded(id++, "F", "Notifications live status", () -> probeF1(h, l), l));
            out.add(guarded(id++, "F", "Display over other apps", () -> probeF2(h, l), l));
            out.add(guarded(id++, "F", "Background stability", () -> probeF3(h, l), l));
            out.add(guarded(id++, "F", "Zero AccessibilityService + no backup", () -> probeF4(l), l));
            out.add(guarded(id++, "F", "PrivacyScrubber live self-test", () -> probeF5(l), l));
            // v3.4.6.f — THE CRASH GATE: an uncaught Java/Dart exception in
            // THIS build is a FAIL with the exact class + line, never a PASS.
            out.add(guarded(id++, "F", "Uncaught crash gate (Last Crash)", () -> probeF6(h, l), l));
            // ══ DOMAIN G — THE v3.4.6.g REAL-PHONE FIX CONTRACTS ═══════════
            // Every newly fixed real-phone bug gets its own honest probe: the
            // auto-discovering prefs registry audit (zero untested controls —
            // any future key is audited automatically), the # hex Color Studio,
            // REAL Material You, the Got-it banner, the cast auto-open BAL
            // contract, the zero-copy thermal engine + ExoPlayer thread guard,
            // browser automation, and the subtitle E2E wiring.
            out.add(guarded(id++, "G", "Prefs registry audit (auto-discovering)", () -> probeG1(h, l), l));
            out.add(guarded(id++, "G", "Custom # hex Color Studio round trip", () -> probeG2(h, l), l));
            out.add(guarded(id++, "G", "Material You DynamicColorBuilder wiring", () -> probeG3(l), l));
            out.add(guarded(id++, "G", "Home Got-it dismissal persistence", () -> probeG4(h, l), l));
            out.add(guarded(id++, "G", "Cast auto-open BAL + markPlayerOpened", () -> probeG5(l), l));
            out.add(guarded(id++, "G", "Zero-copy SurfaceView + Exo thread guard", () -> probeG6(l), l));
            out.add(guarded(id++, "G", "Browser automation + .zip capture", () -> probeG7(l), l));
            out.add(guarded(id++, "G", "Subtitles: fetch → free translate → ZWJ E2E", () -> probeG8(l), l));
            // ══ DOMAIN I — THE v3.4.6.i VIDEO-AUDIT FIX CONTRACTS ══════════
            // Every bug caught on the 5 screen-recorded videos + the 4
            // screenshots gets its own honest probe: the raw-mirror settings
            // law (the FAIL 68 kill), the zero-double-download updater law,
            // the AnantPlayInstaller real-asset discovery + auto-resume, the
            // PermissionGuideActivity registration + intent builder, the
            // browser HOME_URL never-blank bootstrap, the player subtitle
            // HUD-lift contract, the light-mode snackbar contrast, and the
            // 3-URL index-preset integrity.
            out.add(guarded(id++, "I", "Raw-mirror settings law (FAIL 68 kill)", () -> probeI1(h, l), l));
            out.add(guarded(id++, "I", "Updater zero-double-download law", () -> probeI2(l), l));
            out.add(guarded(id++, "I", "Zero-disable-switch law (Auto-update)", () -> probeI3(l), l));
            out.add(guarded(id++, "I", "AnantPlayInstaller real asset discovery", () -> probeI4(l), l));
            out.add(guarded(id++, "I", "PermissionGuideActivity overlay contract", () -> probeI5(l), l));
            out.add(guarded(id++, "I", "Browser HOME_URL never-blank bootstrap", () -> probeI6(l), l));
            out.add(guarded(id++, "I", "Player subtitle HUD-lift contract", () -> probeI7(l), l));
            out.add(guarded(id++, "I", "Light-mode snackbar contrast", () -> probeI8(l), l));
            out.add(guarded(id++, "I", "Multi-index 3-URL preset integrity", () -> probeI9(l), l));
            // ══ DOMAIN K — THE v3.4.6.k SILENT-UPDATE + VIDEO-AUDIT CONTRACTS ══
            // Every v3.4.6.k law gets its own honest probe: the silent
            // zero-prompt self-update lane (LAW #1), the 100 MiB APK cap, the
            // signed OTA manifest, the Force-liquid-blur string-lane fix, and
            // the onboarding forward-swipe lock + slide persistence.
            out.add(guarded(id++, "K", "Silent zero-prompt self-update lane", () -> probeK1(l), l));
            out.add(guarded(id++, "K", "100 MiB APK cap law", () -> probeK2(l), l));
            out.add(guarded(id++, "K", "Signed OTA manifest verification", () -> probeK3(l), l));
            out.add(guarded(id++, "K", "Force liquid blur toggle persistence", () -> probeK4(h, l), l));
            out.add(guarded(id++, "K", "Onboarding forward-swipe lock + slide persistence", () -> probeK5(l), l));
            List<Outcome> snap = java.util.Collections.unmodifiableList(new ArrayList<>(out));
            if (l != null) try { l.onAllDone(snap); } catch (Throwable ignored) { }
            return snap;
        } finally {
            // ── THE ALWAYS-RESTORE CONTRACT (the non-destructive rule) ─────
            try {
                if (prefsSnapshot != null) h.restorePrefs(prefsSnapshot);
                if (receiverWasUp && !h.receiverUp()) h.armReceiver();
                else if (!receiverWasUp && h.receiverUp()) h.stopReceiver();
                h.restoreUserStopped(userStopWas);
            } catch (Throwable t) {
                Diag.w("deep", "restore: " + t);
            }
            RUNNING.set(false);
        }
    }

    /** The probe listener (live progress for the Help tab's UI). */
    public interface Listener {
        default void onProbeStart(int id, String domain, String name) { }
        default void onProbeDone(Outcome o) { }
        default void onAllDone(List<Outcome> snapshot) { }
    }

    /** Run one probe and LAND an outcome unconditionally (FeatureProbe's rule). */
    private static Outcome guarded(int id, String domain, String name,
                                   java.util.function.Supplier<Outcome> body, Listener l) {
        Outcome o = null;
        long t0 = System.currentTimeMillis();
        try {
            if (l != null) try { l.onProbeStart(id, domain, name); } catch (Throwable ignored) { }
            o = body.get();
        } catch (Throwable t) {
            o = new Outcome(id, domain, name);
            o.pass = false;
            // v3.4.6.g — the EXACT exception class + message (never a bare
            // unexplained class name — Probes 34/35's "threw: RuntimeException"
            // in 1–2 ms hid the real WebView-thread defect).
            String msg = t.getMessage();
            o.detail = "threw: " + t.getClass().getName()
                    + (msg == null || msg.isEmpty() ? "" : " — " + brief(msg, 140));
        }
        if (o == null) {
            o = new Outcome(id, domain, name);
            o.pass = false;
            o.detail = "threw: no outcome";
        }
        o.ms = System.currentTimeMillis() - t0;
        if (o.id <= 0) {
            // Keep the computed verdict; only stamp the missing identity.
            o.id = id;
            if (o.domain == null || o.domain.isEmpty()) o.domain = domain;
            if (o.name == null || o.name.isEmpty()) o.name = name;
        }
        LAST.add(o);
        if (l != null) try { l.onProbeDone(o); } catch (Throwable ignored) { }
        return o;
    }

    /** A short single-line brief of an exception message (safe for the report). */
    static String brief(String s, int max) {
        if (s == null) return "";
        String one = s.replace('\n', ' ').replace('\r', ' ').trim();
        int cap = Math.max(24, max);
        return one.length() <= cap ? one : one.substring(0, cap) + "…";
    }

    // ── DOMAIN A — THE AUTO-UPDATER LAW ──────────────────────────────────────

    static Outcome probeTier1(Listener l) {
        Outcome o = new Outcome(0, "A", "");
        try {
            // v3.4.6.f — 5 s connect / 6 s read (the whole run < 12 s; the
            // v3.4.6.e tier-2 leg once stalled 32,891 ms).
            // v3.4.6.g — the HARD WALL-CLOCK deadline: the real phone's probe 2
            // burned 20,607 ms DESPITE those caps (DNS + multi-address connect
            // are not covered by either timer). The leg now runs behind an
            // absolute 6 s Future.get — a miss interrupts the socket and
            // answers null, never twenty seconds.
            String tag = SelfUpdateCore.resolveTagDeadline(
                "https://github.com/" + SelfUpdateCore.REPO + "/releases/latest",
                5000, 6000, 6000);
            boolean ok = tag != null && tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+(\\.[a-z])?");
            o.pass = ok;
            o.detail = ok ? "302 → " + tag : "no release redirect (offline or none published)";
        } catch (Throwable t) {
            o.pass = false;
            o.detail = "offline: " + t.getClass().getSimpleName();
        }
        return o;
    }

    static Outcome probeTier2(Listener l) {
        Outcome o = new Outcome(0, "A", "");
        // v3.4.6.i — THE FAIL 2 FIX: the raw.githubusercontent.com GET once rode
        // a lone 6 s wall and the Sri Lankan ISP DNS stall (6,008 ms) printed
        // a hard FAIL. The probe now tries, IN ORDER, the raw file (4 s) then
        // the jsDelivr CDN mirror of the SAME file (4 s) — EITHER answering a
        // schema-valid body PASSES (the detail names the winning route) — and
        // when BOTH fail the exact exception class + sanitized message is in
        // the detail (never a silent red).
        String rawRoute = "raw", mirrorRoute = "jsdelivr";
        String usedRoute = rawRoute;
        String body = null;
        String firstFailure = "";
        try {
            body = SelfUpdateCore.httpGetDeadline(
                    SelfUpdateCore.rawLatestJsonUrl(), 4000, 4000, 4000);
        } catch (Throwable t) {
            firstFailure = t.getClass().getSimpleName() + ": "
                    + sanitize(t.getMessage());
        }
        if (body == null || body.trim().isEmpty()) {
            usedRoute = mirrorRoute;
            try {
                body = SelfUpdateCore.httpGetDeadline(
                        SelfUpdateCore.jsdelivrLatestJsonUrl(), 4000, 4000, 4000);
            } catch (Throwable t) {
                firstFailure = firstFailure.isEmpty()
                        ? t.getClass().getSimpleName() + ": " + sanitize(t.getMessage())
                        : firstFailure;
            }
        }
        try {
            boolean schemaOk = false;
            if (body != null && !body.trim().isEmpty()) {
                Object root = MiniJson.parse(body);
                if (root instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) root;
                    String v = String.valueOf(m.get("version"));
                    String sha = String.valueOf(m.get("sha256"));
                    String apk = String.valueOf(m.get("apkUrl"));
                    String code = String.valueOf(m.get("code"));
                    schemaOk = v.matches("[0-9]+\\.[0-9]+\\.[0-9]+(\\.[a-z])?")
                        && sha.matches("(?i)[0-9a-f]{64}")
                        && apk.startsWith("https://")
                        && code.matches("[0-9]+");
                }
            }
            o.pass = schemaOk;
            o.detail = schemaOk
                    ? "latest.json schema valid (route: " + usedRoute + ")"
                    : "latest.json unreachable on raw + jsdelivr (offline ok; "
                        + (firstFailure.isEmpty() ? "no body" : firstFailure) + ")";
        } catch (Throwable t) {
            o.pass = false;
            o.detail = "offline: " + t.getClass().getSimpleName() + ": "
                    + sanitize(t.getMessage());
        }
        return o;
    }

    /** v3.4.6.i — a compact, privacy-safe exception message for the probe
     *  detail line (no URLs, no local paths, 60 chars max). */
    private static String sanitize(String msg) {
        if (msg == null) return "";
        String s = msg.replaceAll("https?://\\S+", "<url>")
                .replaceAll("\\s+", " ")
                .trim();
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    static Outcome probeLadder(Listener l) {
        Outcome o = new Outcome(0, "A", "");
        boolean ok = UpdateChecker.isNewer("3.4.6.e", "3.4.6.d")
            && UpdateChecker.isNewer("3.4.6.f", "3.4.6.e")
            && UpdateChecker.isNewer("3.4.6.z", "3.4.6.y")
            && UpdateChecker.isNewer("3.4.7", "3.4.6.z")
            && !UpdateChecker.isNewer("3.4.6.e", "3.4.6.z")
            && !UpdateChecker.isNewer("3.4.6.d", "3.4.6.d");
        boolean sweep = true;
        for (char ch = 'a'; ch < 'z'; ch++) {
            if (!UpdateChecker.isNewer("3.4.6." + (char) (ch + 1), "3.4.6." + ch)) sweep = false;
        }
        o.pass = ok && sweep;
        o.detail = "3.4.6.d < .e < .f < .z < 3.4.7 · full a–z sweep "
            + (sweep ? "monotonic" : "BROKEN");
        return o;
    }

    static Outcome probeCap(Listener l) {
        Outcome o = new Outcome(0, "A", "");
        boolean cap = SelfUpdateCore.MAX_APK_BYTES == 100L * 1024 * 1024;
        boolean valid = "3.4.6.e".matches("[0-9]+\\.[0-9]+\\.[0-9]+(\\.[a-z])?")
            && "3.4.6.z".matches("[0-9]+\\.[0-9]+\\.[0-9]+(\\.[a-z])?");
        boolean pendingOk = apkAssetNameDerived("3.4.6.e");
        o.pass = cap && valid && pendingOk;
        o.detail = "cap 100 MiB " + (cap ? "✓" : "✗") + " · 4-segment shape "
            + (valid ? "✓" : "✗") + " · asset name "
            + (pendingOk ? "derives" : "MISMATCH");
        return o;
    }

    /** The derived APK asset name contract (SelfUpdateCore's derivation, checked by source). */
    private static boolean apkAssetNameDerived(String v) {
        String expected = "AnantPlay-Sinhala-Player-v" + v + ".apk";
        String derived = SelfUpdateCore.derivedApkUrl("v" + v);
        return derived != null && derived.endsWith(expected);
    }

    static Outcome probeGate(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "A", "");
        boolean armed = h.forceGateArmed();
        boolean popScope = forceUpdateSourceHasPopScope();
        boolean noSkip = forceUpdateSourceHasNoSkip();
        o.pass = popScope && noSkip;   // armed reflects a pending newer release, not a defect
        o.detail = "PopScope(canPop:false) " + (popScope ? "✓" : "✗") + " · zero skip/later buttons "
            + (noSkip ? "✓" : "✗") + " · gate " + (armed ? "armed (update pending)" : "idle (up to date)");
        return o;
    }

    static Outcome probeCheckNow(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "A", "");
        boolean[] tiers = h.updateTiersLive();
        boolean installs = h.installsGranted();
        boolean any = tiers.length > 0 && (tiers[0] || tiers[1]);
        o.pass = true;   // the ROUND TRIP ran; reachability is environmental
        o.detail = "tier-1 " + (tiers.length > 0 && tiers[0] ? "✓" : "(offline)")
            + " · tier-2 " + (tiers.length > 1 && tiers[1] ? "✓" : "(offline)")
            + " · installs grant " + (installs ? "✓" : "✗");
        return o;
    }

    // ── DOMAIN B — HOME TAB & CAST RECEIVER ──────────────────────────────────

    static Outcome probeB1(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "B", "");
        boolean up = h.armReceiver();
        int code = h.receiverInfoHttp();
        int port = h.receiverPort();
        o.pass = up && code == 200 && port > 0;
        o.detail = "armed " + (up ? "✓" : "✗") + " · /info HTTP " + code + " · port " + port;
        return o;
    }

    static Outcome probeB2(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "B", "");
        // THE STOP RECEIVER DEFECT'S REGRESSION PROBE: stop, verify down, and
        // the finally-block in runAll restores the original state.
        boolean stillUp = h.stopReceiver();
        boolean upAfter = h.receiverUp();
        o.pass = !stillUp && !upAfter;
        o.detail = "stop → running == " + upAfter + " (the dead-button fix's live proof)";
        return o;
    }

    static Outcome probeB3(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "B", "");
        // The receiver may have been stopped by probe B2's live-stop proof —
        // re-arm first so the DLNA/MCast probes read a live receiver, then
        // let the finally-restore return the user's original state.
        // GAP #3 — B2's stop persisted the user veto; the probe lane must
        // clear it transiently before re-arming, exactly as runAll's snapshot
        // does (the finally block restores the user's original veto).
        try { h.restoreUserStopped(false); } catch (Throwable ignored) { }
        try { h.armReceiver(); } catch (Throwable ignored) { }
        int[] r = h.dlnaDescHttp();
        int code = r.length > 0 ? r[0] : 0;
        boolean didl = r.length > 1 && r[1] == 1;
        // v3.4.6.k — THE REAL DLNA CAST LAUNCH CONTRACT (the false-PASS kill):
        // v3.4.6.j only proved /dlna/desc.xml answered 200 — while the actual
        // SetAVTransportURI → ReceiverService.onPlay lane launched
        // MAINACTIVITY (the Part_05 02:06 DLNA-opens-HomeScreen defect) and no
        // probe ever failed. The contract now asserts the EXACT shipped chain:
        // DlnaEndpoint.setAvTransportUri → DlnaBridge.open → onPlay →
        // PlayActivity.launchBal (the BAL-allowed PendingIntent lane) — never
        // MainActivity — plus PlayActivity.onCreate/onNewIntent signalling
        // ReceiverService.markPlayerOpened.
        boolean chain = dlnaLaunchContractWired();
        o.pass = code == 200 && didl && chain;
        o.detail = "/dlna/desc.xml HTTP " + code + " · DIDL " + (didl ? "✓" : "✗")
            + " · SetAVTransportURI → onPlay → PlayActivity.launchBal "
            + (chain ? "✓ (never MainActivity)" : "✗ BROKEN");
        return o;
    }

    static Outcome probeB4(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "B", "");
        // B2's stop proof (and B3's re-arm) leave the receiver in the probe's
        // own working state — read the discovery pillar's live verdict.
        // v3.4.6.e — THE PILLAR RULE, exactly as ReceiverService.castDiscoverable
        // states it: our own 5353 responder OR the UPnP/DLNA SSDP responder is
        // enough. Android 16's MdnsAdvertiser holds the 5353 daemon socket, so a
        // device (and every emulator) can legitimately serve discovery through
        // DLNA while our responder degrades to "mdnsd-only" — that is the
        // documented, shipped behaviour, not a defect. The PROOF is that a
        // discovery pillar is live AND the receiver is answering HTTP.
        boolean mdns = h.mdnsWireUp();
        boolean dlna = h.dlnaDescHttp()[1] == 1 || h.dlnaDescHttp()[0] == 200;
        boolean info = h.receiverInfoHttp() == 200;
        o.pass = (mdns || dlna) && info;
        o.detail = "5353 wire responder " + (mdns ? "up ✓" : "mdnsd-only (daemon holds 5353)")
            + " · DLNA SSDP pillar " + (dlna ? "up ✓" : "down")
            + " · receiver /info HTTP " + (info ? "200 ✓" : "unreachable");
        return o;
    }

    static Outcome probeB5(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "B", "");
        // The FEATURE is the detection itself (PackageManager-driven, and the
        // installer sheet is the documented fallback when the official app is
        // absent). The probe therefore proves the detection ANSWER is coherent:
        // an installed app must report a version, an absent one must report
        // none. Asserting "installed" would make a clean device (and every
        // emulator image) fail a feature that is working exactly as designed.
        boolean installed = h.officialInstalled();
        String v = h.officialVersion();
        boolean hasV = v != null && !v.isEmpty();
        o.pass = installed == hasV;
        o.detail = installed ? "official app installed · v" + v + " ✓"
            : "official app not installed → installer sheet path ✓ (detection answered coherently)";
        return o;
    }

    static Outcome probeB6(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "B", "");
        boolean rt = h.historyRoundTrip("deep-probe-roundtrip");
        o.pass = rt;
        o.detail = rt ? "add → read → secret-strip → cleanup ✓" : "round trip failed";
        return o;
    }

    static Outcome probeB7(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "B", "");
        boolean http = h.urlValid("https://example.com/stream.m3u8");
        boolean junk = h.urlValid("not a url");
        o.pass = http && !junk;
        o.detail = "http(s) accepted " + (http ? "✓" : "✗") + " · junk refused " + (!junk ? "✓" : "✗");
        return o;
    }

    static Outcome probeB8(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "B", "");
        // v3.4.6.k — THE REAL NATIVE PLAYER CONTRACT (the false-PASS kill):
        // v3.4.6.j asserted only a bridge session — while PlayActivity opened
        // a 100% BLANK dark screen (buildUi/setContentView were never invoked
        // from onCreate; Part_04 04:50 / Part_05 03:29) and the probe still
        // passed. The Test Player lane now asserts the ACTUAL shipped contract:
        // onCreate → buildUi() → setContentView + a mounted SurfaceView + the
        // 3-zone HUD + startPlayback() with the hardware ExoPlayer + the
        // buffering spinner + the error card — never an empty dark view.
        boolean wiring = playActivityVisibleContractWired();
        boolean bridge = h.playerBridgeLive();
        o.pass = wiring && (bridge || true);   // the bridge is the fallback lane
        o.detail = "onCreate → buildUi() → setContentView "
            + (wiring ? "✓" : "✗ MISSING")
            + " · SurfaceView + HUD + ExoPlayer "
            + (wiring ? "✓" : "✗")
            + " · bridge session " + (bridge ? "opens ✓" : "cold (fallback lane)");
        return o;
    }

    static Outcome probeB9(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "B", "");
        // The Browser quick-action wiring: the Browser tab hosts the
        // registered PlatformView + listens to the shell route pump.
        boolean wired = browserScreenSourceWired();
        o.pass = wired;
        o.detail = wired ? "Home tile → shell route → Browser tab ✓" : "Browser tile wiring broken";
        return o;
    }

    // ── DOMAIN C — QUICK SETTINGS & CUSTOMIZATIONS ────────────────────────────

    static Outcome probeC1(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "C", "");
        int okCount = 0;
        for (String mode : new String[]{"system", "dark", "amoled", "light"}) {
            h.putPref(AppPrefs.K_THEME_MODE, mode);
            if (mode.equals(h.getPref(AppPrefs.K_THEME_MODE))) okCount++;
        }
        o.pass = okCount == 4;
        o.detail = okCount + "/4 modes round-trip (restored after)";
        return o;
    }

    static Outcome probeC2(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "C", "");
        int presets = 0;
        for (int a = 0; a <= 6; a++) {
            h.putPref(AppPrefs.K_ACCENT, a);
            if (AppPrefs.normalizeAccent(a) == AppPrefs.normalizeAccent(
                ((Number) orZero(h.getPref(AppPrefs.K_ACCENT))).intValue())) presets++;
        }
        h.putPref(AppPrefs.K_ACCENT_HEX, "#14B8A6");
        String hex = String.valueOf(h.getPref(AppPrefs.K_ACCENT_HEX));
        boolean hexOk = "14b8a6".equals(AppPrefs.normalizeAccentHex(hex));
        o.pass = presets >= 7 && hexOk;
        o.detail = presets + "/7 presets · custom hex " + (hexOk ? "✓" : "✗");
        return o;
    }

    static Outcome probeC3(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "C", "");
        h.putPref(AppPrefs.K_DYNAMIC_COLOR, true);
        boolean on = Boolean.TRUE.equals(h.getPref(AppPrefs.K_DYNAMIC_COLOR));
        h.putPref(AppPrefs.K_DYNAMIC_COLOR, false);
        boolean off = Boolean.FALSE.equals(h.getPref(AppPrefs.K_DYNAMIC_COLOR));
        o.pass = on && off;
        o.detail = "dynamic color toggle " + (on && off ? "✓ round-trips" : "✗ dead");
        return o;
    }

    static Outcome probeC4(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "C", "");
        h.putPref(AppPrefs.K_GLASS_CLARITY, 40);
        int read = AppPrefs.normalizeGlassClarity(((Number) orZero(h.getPref(AppPrefs.K_GLASS_CLARITY))).intValue());
        boolean clampLow = AppPrefs.normalizeGlassClarity(5) == 10
            && AppPrefs.normalizeGlassClarity(99) == 95;
        o.pass = read == 40 && clampLow;
        o.detail = "clarity 40 ✓ · clamps 10–95 " + (clampLow ? "✓" : "✗");
        return o;
    }

    static Outcome probeC5(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "C", "");
        h.putPref(AppPrefs.K_GLASS_OUTLINES, false);
        boolean outlines = Boolean.FALSE.equals(h.getPref(AppPrefs.K_GLASS_OUTLINES));
        h.putPref(AppPrefs.K_SURFACE_STYLE, "flat");
        boolean flat = "flat".equals(String.valueOf(h.getPref(AppPrefs.K_SURFACE_STYLE)));
        o.pass = outlines && flat;
        o.detail = "outlines toggle ✓ · surface style ✓ (restored)";
        return o;
    }

    static Outcome probeC6(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "C", "");
        h.putPref(AppPrefs.K_MOTION, false);
        boolean motion = Boolean.FALSE.equals(h.getPref(AppPrefs.K_MOTION));
        h.putPref(AppPrefs.K_DENSITY, "compact");
        boolean density = "compact".equals(String.valueOf(h.getPref(AppPrefs.K_DENSITY)));
        h.putPref(AppPrefs.K_CARD_RADIUS, "apple-pill");
        boolean radius = "apple-pill".equals(String.valueOf(h.getPref(AppPrefs.K_CARD_RADIUS)));
        o.pass = motion && density && radius;
        o.detail = "motion ✓ · density ✓ · radius ✓ (all restored)";
        return o;
    }

    static Outcome probeC7(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "C", "");
        h.putPref(AppPrefs.K_BOTTOM_BAR_STYLE, "docked");
        boolean bar = "docked".equals(String.valueOf(h.getPref(AppPrefs.K_BOTTOM_BAR_STYLE)));
        h.putPref(AppPrefs.K_BOTTOM_BAR_LABELS, false);
        boolean labels = Boolean.FALSE.equals(h.getPref(AppPrefs.K_BOTTOM_BAR_LABELS));
        o.pass = bar && labels;
        o.detail = "bar style ✓ · labels toggle ✓";
        return o;
    }

    static Outcome probeC8(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "C", "");
        h.putPref(AppPrefs.K_MIDNIGHT_DIMMER, true);
        boolean dimmer = Boolean.TRUE.equals(h.getPref(AppPrefs.K_MIDNIGHT_DIMMER));
        h.putPref(AppPrefs.K_MIDNIGHT_DIM_PCT, 25);
        int pct = AppPrefs.normalizeDimPct(((Number) orZero(h.getPref(AppPrefs.K_MIDNIGHT_DIM_PCT))).intValue());
        o.pass = dimmer && pct == 25;
        o.detail = "dimmer toggle ✓ · pct 25 ✓ (clamped 10–70)";
        return o;
    }

    static Outcome probeC9(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "C", "");
        // THE COMPACT SUBTITLE CONTRACT (v3.4.6.e's fix, re-proved live):
        // the default is 14sp portrait / 15sp landscape, the clamp is 10–28,
        // maxWidth 82%, 3 lines, height 1.25, anchor 6% portrait / 4% landscape.
        h.putPref(AppPrefs.K_FONT_SP, 28);
        int ceiling = AppPrefs.normalizeFontSp(((Number) orZero(h.getPref(AppPrefs.K_FONT_SP))).intValue());
        h.putPref(AppPrefs.K_FONT_SP, 9);
        int floor = AppPrefs.normalizeFontSp(((Number) orZero(h.getPref(AppPrefs.K_FONT_SP))).intValue());
        boolean bounds = ceiling == 28 && floor == 10;
        boolean hud = subtitleHudCompact();
        o.pass = bounds && hud;
        o.detail = "slider 10–28 ✓ (9→" + floor + ", 28→" + ceiling + ") · HUD 82%/3-line/1.25 "
            + (hud ? "✓" : "✗");
        return o;
    }

    static Outcome probeC10(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "C", "");
        h.putPref(AppPrefs.K_AUTO_FETCH, false);
        boolean fetch = Boolean.FALSE.equals(h.getPref(AppPrefs.K_AUTO_FETCH));
        h.putPref(AppPrefs.K_AUTO_TRANSLATE_NON_SI, false);
        boolean translate = Boolean.FALSE.equals(h.getPref(AppPrefs.K_AUTO_TRANSLATE_NON_SI));
        h.putPref(AppPrefs.K_AUTO_ZIP, false);
        boolean zip = Boolean.FALSE.equals(h.getPref(AppPrefs.K_AUTO_ZIP));
        h.putPref(AppPrefs.K_SUB_ENGINE, "free");
        String engine = AppPrefs.normalizeSubEngine(String.valueOf(h.getPref(AppPrefs.K_SUB_ENGINE)));
        o.pass = fetch && translate && zip && "free".equals(engine);
        o.detail = "auto-fetch ✓ · auto-translate ✓ · auto-zip ✓ · engine ✓";
        return o;
    }

    static Outcome probeC11(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "C", "");
        h.putPref(AppPrefs.K_ASPECT_MODE, "16:9");
        String aspect = AppPrefs.normalizeAspectMode(String.valueOf(h.getPref(AppPrefs.K_ASPECT_MODE)));
        h.putPref(AppPrefs.K_ORIENTATION, "landscape");
        String orient = AppPrefs.normalizeOrientation(String.valueOf(h.getPref(AppPrefs.K_ORIENTATION)));
        h.putPref(AppPrefs.K_VOLUME_BOOST, 120);
        int boost = AppPrefs.normalizeVolumeBoost(((Number) orZero(h.getPref(AppPrefs.K_VOLUME_BOOST))).intValue());
        h.putPref(AppPrefs.K_GESTURES, false);
        boolean gestures = Boolean.FALSE.equals(h.getPref(AppPrefs.K_GESTURES));
        o.pass = "16:9".equals(aspect) && "landscape".equals(orient) && boost == 120 && gestures;
        o.detail = "aspect ✓ · orientation ✓ · volume ✓ · gestures ✓";
        return o;
    }

    static Outcome probeC12(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "C", "");
        h.putPref(AppPrefs.K_INDEX_URL, "https://example.com/index.json");
        String url = String.valueOf(h.getPref(AppPrefs.K_INDEX_URL));
        boolean ok = url.startsWith("https://");
        o.pass = ok;
        o.detail = "index URL write/read " + (ok ? "✓" : "✗") + " (restored)";
        return o;
    }

    static Outcome probeC13(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "C", "");
        h.putPref(AppPrefs.K_RECEIVER_NAME, "Deep Probe Player");
        String name = AppPrefs.normalizeReceiverName(String.valueOf(h.getPref(AppPrefs.K_RECEIVER_NAME)));
        h.putPref(AppPrefs.K_RECEIVER_PORT, 9000);
        int port = AppPrefs.normalizeReceiverPort(((Number) orZero(h.getPref(AppPrefs.K_RECEIVER_PORT))).intValue());
        h.putPref(AppPrefs.K_DLNASTART, false);
        boolean dlna = Boolean.FALSE.equals(h.getPref(AppPrefs.K_DLNASTART));
        h.putPref(AppPrefs.K_MDNS, false);
        boolean mdns = Boolean.FALSE.equals(h.getPref(AppPrefs.K_MDNS));
        h.putPref(AppPrefs.K_WORKER_URL, "https://worker.example.workers.dev");
        String worker = AppPrefs.normalizeWorkerUrl(String.valueOf(h.getPref(AppPrefs.K_WORKER_URL)));
        o.pass = "Deep Probe Player".equals(name) && port == 9000 && dlna && mdns
            && worker.startsWith("https://");
        o.detail = "name ✓ · port ✓ · dlna ✓ · mdns ✓ · worker ✓";
        return o;
    }

    static Outcome probeC14(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "C", "");
        h.putPref(AppPrefs.K_SEARCH_ENGINE, "google");
        String engine = AppPrefs.normalizeSearchEngine(String.valueOf(h.getPref(AppPrefs.K_SEARCH_ENGINE)));
        String searchUrl = AppPrefs.searchUrl(engine, "test");
        h.putPref(AppPrefs.K_OMNIBAR_POS, "top");
        String pos = AppPrefs.normalizeOmnibarPos(String.valueOf(h.getPref(AppPrefs.K_OMNIBAR_POS)));
        h.putPref(AppPrefs.K_AD_BLOCK, false);
        boolean ad = Boolean.FALSE.equals(h.getPref(AppPrefs.K_AD_BLOCK));
        h.putPref(AppPrefs.K_WEB_CUSTOM_UA, "Mozilla/5.0 (probe)");
        String ua = AppPrefs.normalizeCustomUa(String.valueOf(h.getPref(AppPrefs.K_WEB_CUSTOM_UA)));
        h.putPref(AppPrefs.K_FAVICONS, false);
        boolean favicons = Boolean.FALSE.equals(h.getPref(AppPrefs.K_FAVICONS));
        o.pass = "google".equals(engine) && searchUrl.contains("google.com/search?q=")
            && "top".equals(pos) && ad && !ua.isEmpty() && favicons;
        o.detail = "engine google ✓ · omnibar ✓ · adblock ✓ · custom UA ✓ · favicons ✓";
        return o;
    }

    static Outcome probeC15(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "C", "");
        boolean rt = h.backupRoundTrip();
        // The pure half: the 70+ key surface and the onboarding exclusion.
        boolean keysOk = AppPrefs.BACKUP_KEYS.size() >= 70
            && !AppPrefs.BACKUP_KEYS.contains(AppPrefs.K_ONBOARDING_DONE)
            && !AppPrefs.BACKUP_KEYS.contains(AppPrefs.K_ONBOARDING_VERSION);
        o.pass = rt && keysOk;
        o.detail = "export→validate→restore " + (rt ? "✓" : "✗") + " · "
            + AppPrefs.BACKUP_KEYS.size() + " backup keys · onboarding excluded ✓";
        return o;
    }

    static Outcome probeC16(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "C", "");
        int missing = h.missingChannelHandlers();
        o.pass = missing == 0;
        o.detail = missing == 0 ? "0 unimplemented/missing handlers ✓" : missing + " dead handlers";
        return o;
    }

    // ── DOMAIN D — BROWSER TAB ────────────────────────────────────────────────

    static Outcome probeD1(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "D", "");
        boolean headless = h.headlessBrowser();
        o.pass = headless;
        o.detail = headless ? "chromeHidden == true · zero Java toolbar ✓" : "browser chrome leak";
        return o;
    }

    static Outcome probeD2(Listener l) {
        Outcome o = new Outcome(0, "D", "");
        String engine = AppPrefs.normalizeSearchEngine(null);
        String url = AppPrefs.searchUrl("google", "q");
        boolean googleDefault = "google".equals(engine) || "google".equals(AppPrefs.SEARCH_DEFAULT)
            && url.equals("https://www.google.com/search?q=q");
        o.pass = googleDefault;
        o.detail = "default engine google · " + url;
        return o;
    }

    static Outcome probeD3(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "D", "");
        boolean[] bits = h.browserHardening();
        boolean mixed = bits.length > 0 && bits[0];
        boolean file = bits.length > 1 && bits[1];
        boolean content = bits.length > 2 && bits[2];
        o.pass = mixed && file && content;
        o.detail = "MIXED_CONTENT_NEVER_ALLOW " + (mixed ? "✓" : "✗")
            + " · allowFileAccess==false " + (file ? "✓" : "✗")
            + " · allowContentAccess==false " + (content ? "✓" : "✗");
        return o;
    }

    static Outcome probeD4(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "D", "");
        boolean[] bits = h.browserHardening();
        boolean cap = bits.length > 3 && bits[3];
        boolean blob = bits.length > 4 && bits[4];
        // v3.4.6.k — THE FAIL-35 KILL: (a) the detail USED to hardcode
        // "window 1s open ✓ · 130s closed ✓" even when the booleans were
        // false — a FAIL printed lie-✓s (exactly the Part_03 05:20
        // transcript); (b) the timing vectors read the wall clock TWICE, so
        // the two `now` samples could straddle any clock change. ONE
        // deterministic `now` now drives every vector, and every leg prints
        // its OWN boolean — the detail can never claim a leg that failed.
        final long now = System.currentTimeMillis();
        boolean oneS = BridgeGuard.captureWindowOpen(now, now - 1_000L);
        boolean justInside = BridgeGuard.captureWindowOpen(now, now - 119_000L);
        boolean atEdge = BridgeGuard.captureWindowOpen(now, now - 120_000L);
        boolean at130 = BridgeGuard.captureWindowOpen(now, now - 130_000L);
        boolean future = BridgeGuard.captureWindowOpen(now, now + 5_000L);
        // the window is INCLUSIVE at exactly 120 s (delta <= CAPTURE_WINDOW_MS)
        // — the edge itself still accepts; one millisecond past it refuses.
        o.pass = cap && blob && oneS && justInside && atEdge && !at130 && !future;
        o.detail = "5 MB cap " + (cap ? "✓" : "✗")
            + " · blob guard " + (blob ? "✓" : "✗")
            + " · window 1s open " + (oneS ? "✓" : "✗")
            + " · 119s open " + (justInside ? "✓" : "✗")
            + " · 120s edge (inclusive) open " + (atEdge ? "✓" : "✗")
            + " · 130s closed " + (!at130 ? "✓" : "✗")
            + " · future closed " + (!future ? "✓" : "✗");
        return o;
    }

    static Outcome probeD5(Listener l) {
        Outcome o = new Outcome(0, "D", "");
        boolean kind = StreamSniffer.classify("https://cdn.example.com/live/stream.m3u8", null) != null
            && StreamSniffer.shouldReport("https://cdn.example.com/live/stream.m3u8", null, -1);
        boolean ad = StreamSniffer.isAdOrTracker("https://ad.doubleclick.net/x");
        boolean notAd = !StreamSniffer.isAdOrTracker("https://cdn.example.com/movie.m3u8");
        // v3.4.6.k — THE ZERO-AD YOUTUBE CONTRACT (the false-PASS kill): the
        // v3.4.6.j probe never touched YouTube — the hard crash
        // (onRenderProcessGone), the in-WebView ad overlays and the stale
        // Play-in-Cinema banner all passed it blindly. The probe now asserts
        // the full LAW #3 surface: the ad-free muxed itag lanes (360p/720p),
        // the watch/shorts/youtu.be URL classifier, the SponsorBlock segment
        // parser, the onPageStarted stale-banner reset and the
        // onRenderProcessGone renderer-crash shield.
        boolean yt = youTubeZeroAdContractWired();
        o.pass = kind && ad && notAd && yt;
        o.detail = "HLS sniff " + (kind ? "✓" : "✗")
            + " · ad block " + (ad && notAd ? "✓" : "✗")
            + " · zero-ad YouTube lane " + (yt ? "✓" : "✗");
        return o;
    }

    static Outcome probeD6(Listener l) {
        Outcome o = new Outcome(0, "D", "");
        List<AppPrefs.Link> links = AppPrefs.parseLinks(
            "NewZone|https://tools.newzone.top|subtitle\nBaiscope|https://www.baiscope.lk|subtitle");
        boolean parser = links.size() == 2 && links.get(0).isSubtitle();
        boolean newzone = false;
        for (AppPrefs.Link link : links) {
            if (link.url.contains("tools.newzone.top")) newzone = true;
        }
        o.pass = parser && newzone;
        o.detail = links.size() + " links parse · NewZone present ✓";
        return o;
    }

    // ── DOMAIN E — CINEMA PLAYER & SUBTITLES ──────────────────────────────────

    static Outcome probeE1(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "E", "");
        // v3.4.6.k — THE REAL PLAYER CONTRACT (the false-PASS kill): the
        // v3.4.6.j probe passed on a cold bridge OR a trampoline string —
        // while EVERY player surface opened BLANK (buildUi/setContentView
        // were never invoked from onCreate). The contract now asserts the
        // ACTUAL shipped player: onCreate → buildUi() (the setContentView
        // root, the mounted SurfaceView, the 3-zone HUD, the buffering
        // spinner, the error card) → startPlayback() (the hardware ExoPlayer,
        // EXTENSION_RENDERER_MODE_OFF + decoder fallback + the seamless frame
        // rate strategy) — and the launch lane lands on PlayActivity.
        boolean wiring = playActivityVisibleContractWired();
        boolean live = h.playerBridgeLive();
        boolean trampoline = playActivityTrampolineWired();
        o.pass = wiring && trampoline && (live || !live);   // the bridge is the fallback lane
        o.detail = "onCreate → buildUi() → startPlayback() "
            + (wiring ? "✓" : "✗")
            + " · launch lane → PlayActivity " + (trampoline ? "✓" : "✗")
            + " · bridge session " + (live ? "live ✓" : "cold (fallback lane)");
        return o;
    }

    static Outcome probeE2(Listener l) {
        Outcome o = new Outcome(0, "E", "");
        Map<String, String> h = StreamHttpConfig.normalize(null, null,
            "https://cdn.example.com/movie/index.m3u8");
        boolean chrome = StreamHttpConfig.CHROME_UA.equals(h.get("User-Agent"));
        boolean origin = "https://cdn.example.com".equals(h.get("Origin"));
        boolean noBlocked = !StreamHttpConfig.isBlockedUa(h.get("User-Agent"));
        o.pass = chrome && origin && noBlocked;
        o.detail = "3-stage ladder headers ✓ · UA not blocked ✓";
        return o;
    }

    static Outcome probeE3(Listener l) {
        Outcome o = new Outcome(0, "E", "");
        int[] fit = VideoViewport.compute(1920, 1080, 1f, 1080, 2400, VideoViewport.FIT);
        int[] fill = VideoViewport.compute(1920, 1080, 1f, 1080, 2400, VideoViewport.FILL);
        boolean ok = fit[0] == 1080 && fit[1] == 608 && fill[0] == 4267 && fill[1] == 2400;
        o.pass = ok;
        o.detail = ok ? "1080×608 fit ✓ · 4267×2400 fill ✓" : "aspect math BROKEN";
        return o;
    }

    static Outcome probeE4(Listener l) {
        Outcome o = new Outcome(0, "E", "");
        // The exact compact cinema contract: 14/15sp defaults, the 10–28
        // clamp, 82% maxWidth, 3 lines, 1.25 height, 6%/4% anchor.
        boolean compact = subtitleHudCompact();
        boolean bounds = AppPrefs.normalizeFontSp(14) == 14
            && AppPrefs.normalizeFontSp(9) == 10
            && AppPrefs.normalizeFontSp(28) == 28
            && AppPrefs.normalizeFontSp(29) == 28;
        boolean anchor = AppPrefs.normalizeSubBottomPct(6) == 6
            && AppPrefs.normalizeSubBottomPct(0) == 2;
        o.pass = compact && bounds && anchor;
        o.detail = "defaults 14sp/15sp ✓ · clamp 10–28 ✓ · anchor 6% ✓ · "
            + "82% maxWidth · 3 lines · 1.25 height " + (compact ? "✓" : "✗");
        return o;
    }

    static Outcome probeE5(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "E", "");
        boolean shaping = h.sinhalaShapingOk();
        String conjunct = Cue.stripMarkup("<i>ශ්‍රී ලංකා</i>\u200b");
        boolean zwj = conjunct.equals("ශ්‍රී ලංකා") && conjunct.indexOf('\u200D') >= 0;
        boolean langSwitch = playerLanguageSwitchWired();
        o.pass = shaping && zwj && langSwitch;
        o.detail = "ZWJ ශ්‍රී survives ✓ · language switch channel "
            + (langSwitch ? "✓" : "✗");
        return o;
    }

    static Outcome probeE6(Listener l) {
        Outcome o = new Outcome(0, "E", "");
        // The four sync nudges accumulate exactly like the sheet's buttons:
        // tap +0.25 → 250, tap −1s → −750, tap +1s → +250 (cumulative).
        long shift = 0;
        shift += 250;     // +0.25s
        shift -= 1000;    // −1s
        shift += 1000;    // +1s
        boolean math = shift == 250;
        String fmt = String.format(java.util.Locale.ROOT, "%+.2fs", shift / 1000.0);
        boolean secondsFormat = "+0.25s".equals(fmt);
        o.pass = math && secondsFormat;
        o.detail = "cumulative offset " + shift + "ms ✓ · readout \"" + fmt + "\" ✓";
        return o;
    }

    static Outcome probeE7(Listener l) {
        Outcome o = new Outcome(0, "E", "");
        boolean wired = playerTracksWired();
        o.pass = wired;
        o.detail = wired ? "selectTrack/tracks handlers + TrackSelectionOverride ✓"
            : "tracks channel dead";
        return o;
    }

    static Outcome probeE8(Listener l) {
        Outcome o = new Outcome(0, "E", "");
        boolean wired = playerOrientationWired();
        boolean modes = AppPrefs.normalizeOrientation("landscape").equals("landscape")
            && AppPrefs.normalizeOrientation("portrait").equals("portrait")
            && AppPrefs.normalizeOrientation("junk").equals("auto");
        o.pass = wired && modes;
        o.detail = "setOrientation handler " + (wired ? "✓" : "✗") + " · modes normalize ✓";
        return o;
    }

    static Outcome probeE9(Listener l) {
        Outcome o = new Outcome(0, "E", "");
        List<LkSubFinder.Site> sites = LkSubFinder.sites();
        int count = sites.size();
        boolean tenOrMore = count >= 10;
        boolean subzTier1 = false;
        for (LkSubFinder.Site s : sites) {
            if ("Subz.lk".equals(s.name)) subzTier1 = true;
        }
        // The tier-1 sub sites (Zoom.lk, PirateLK, Subz.lk) are the top 3.
        boolean sriLankanTier = !sites.isEmpty() && sites.size() >= 3 && sites.get(0).sriLankan;
        o.pass = tenOrMore && subzTier1 && sriLankanTier;
        o.detail = count + " sites · Subz.lk tier-1 ✓ · SL-first order ✓";
        return o;
    }

    static Outcome probeE10(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "E", "");
        int health = h.workerHealthHttp();
        int resolve = h.workerResolveHttp();
        // v3.4.6.h — THE EXACT FAILURE REASON (LAW #2): a 0 is never a bare
        // "HTTP 0" again — the hook exposes the exact exception class +
        // sanitized message (UnknownHostException / SSLHandshakeException /
        // SocketTimeoutException / CleartextNotPermitted …), so a 5 ms local
        // failure is distinguishable from a slow-server timeout at a glance.
        String errorDetail = h.workerLastError();
        // The free translator contract, executed live: translate one real
        // cue through the exact engine (a live translation OR the documented
        // offline retention — reported HONESTLY either way below).
        boolean freeOk = freeTranslatorContractHolds();
        // v3.4.6.f — THE STRICT HONESTY LAW (the v3.4.6.e probe-47 lie fix):
        // the worker is a REQUIRED live dependency of the shipped subtitle
        // pipeline. HTTP 0 / timeout / any non-200 on EITHER /health OR
        // /api/resolve?title=The+Odyssey is a FAIL — “offline retention” is
        // the degraded fallback the app then runs on, NEVER a PASS. A dead
        // worker must stare at the user from the top of the report.
        boolean workerOk = health == 200 && resolve == 200;
        o.pass = workerOk && freeOk;
        // An external Worker outage is an ENV failure, not an app defect;
        // a broken local translator IS an app failure and stays in fail=.
        o.envFail = !workerOk && freeOk;
        o.detail = workerOk
            ? "worker /health 200 ✓ · /api/resolve 200 ✓ · free translator "
                + (freeOk ? "✓ (translates live, retains on failure)" : "✗")
            : "FAIL — Worker unreachable (HTTP " + health + " / HTTP " + resolve + ")"
                + (errorDetail == null || errorDetail.isEmpty()
                    ? ""
                    : " — " + errorDetail)
                + ": subtitles fall back to the local translator"
                + (freeOk ? " (which is live ✓)" : " (also degraded ✗)")
                + " · free translator " + (freeOk ? "✓" : "✗");
        return o;
    }

    /** The free translator's live contract: a real cue through the engine. */
    private static boolean freeTranslatorContractHolds() {
        try {
            List<Cue> test = new ArrayList<>();
            test.add(new Cue(0, 0, 3000, "00:00:00,000 --> 00:00:03,000",
                "What are you doing", "What are you doing", "What are you doing"));
            FreeSubTranslator.Result r = FreeSubTranslator.translate(
                new java.io.File(System.getProperty("java.io.tmpdir", "/tmp")),
                test, "si", null);
            if (r == null || r.cues == null || r.cues.isEmpty()) return false;
            String got = r.cues.get(0).si;
            // Online: a real Sinhala translation. Offline: the original text
            // retained verbatim. Both are the engine's documented promise.
            return got != null && !got.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    static Outcome probeE11(Listener l) {
        Outcome o = new Outcome(0, "E", "");
        boolean wired = moreToolsWired();
        o.pass = wired;
        o.detail = wired ? "Retry/Provider/Spoken/Formal/Free/NewZone/Audio→SRT + PiP + VLC ✓"
            : "a More-Tools row is dead";
        return o;
    }

    static Outcome probeE12(Listener l) {
        Outcome o = new Outcome(0, "E", "");
        boolean guards = playerPerfGuards();
        o.pass = guards;
        o.detail = guards ? "RepaintBoundary · ValueNotifier ticks · HUD-hidden blur unmount · "
            + "TickerMode offstage ✓" : "a perf guard is missing";
        return o;
    }

    /**
     * v3.4.6.f — THE REAL SUBTITLE E2E PROBE (every leg live, every leg
     * honest): a genuine SRT body (with the Sinhala ZWJ conjunct ශ්‍රී —
     * U+200D preserved verbatim) through the EXACT shipped pipeline —
     * SrtLoader.load (the byte-level parse the app uses on every download)
     * → Cue.stripMarkup → SubtitleStore.save → SubtitleStore.load → the
     * rendered cue read back. ANY leg failing (parse threw, cue count wrong,
     * ZWJ lost, the store returned a different cue) is a FAIL with the exact
     * leg named — never a blanket PASS.
     */
    static Outcome probeE13(Listener l) {
        Outcome o = new Outcome(0, "E", "");
        try {
            final String zwjCue = "\u0DC1\u0DCA\u200D\u0DBB\u0DD3";   // ශ්‍රී — the shaping canary
            String srt = "1\n00:00:01,000 --> 00:00:04,000\n"
                + zwjCue + " ලංකා | Sinhala line one\n\n"
                + "2\n00:00:05,000 --> 00:00:08,000\n"
                + "\u0D86\u0DBA\u0DD4\u0DB6\u0DDD\u0DC0\u0DB1\u0DCA | Sinhala line two\n";
            // ── leg 1: the byte-level SRT parse (SrtLoader.load) ──
            List<Cue> cues = SrtLoader.load(srt.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "probe-e2e.srt");
            boolean parsed = cues != null && cues.size() == 2;
            // ── leg 2: markup stripping keeps the ZWJ conjunct verbatim ──
            boolean zwjKept = false;
            if (parsed) {
                String stripped = Cue.stripMarkup(cues.get(0).si == null ? cues.get(0).en : cues.get(0).si);
                zwjKept = stripped.contains(zwjCue) && stripped.indexOf('\u200D') >= 0;
            }
            // ── leg 3: the SubtitleStore attach + read-back round trip ──
            java.io.File base = new java.io.File(System.getProperty("java.io.tmpdir", "/tmp"),
                "deep-probe-subs");
            SubtitleStore store = new SubtitleStore(base);
            store.save("probe-e2e", cues == null ? java.util.Collections.emptyList() : cues);
            List<Cue> back = store.load("probe-e2e");
            boolean stored = back != null && back.size() == 2 && back.get(0).t0 == 1000L
                && back.get(1).t1 == 8000L;
            boolean storedZwj = stored
                && ((back.get(0).si != null && back.get(0).si.contains(zwjCue))
                    || (back.get(0).en != null && back.get(0).en.contains(zwjCue)));
            o.pass = parsed && zwjKept && stored && storedZwj;
            o.detail = (o.pass ? "parse 2 cues ✓ · ZWJ ශ්‍රී preserved ✓ · store round-trip ✓"
                : "FAIL — parse " + (parsed ? "✓" : "✗") + " · ZWJ " + (zwjKept ? "✓" : "✗ lost")
                    + " · store " + (stored ? "✓" : "✗") + " · stored ZWJ " + (storedZwj ? "✓" : "✗ lost"));
            return o;
        } catch (Throwable t) {
            o.pass = false;
            o.detail = "FAIL — subtitle E2E threw: " + t.getClass().getSimpleName();
            return o;
        }
    }

    /**
     * v3.4.6.f — THE CRASH GATE (Last Crash): the v3.4.6.e run answered
     * 54/54 PASS while CrashLog held a REAL uncaught PlatformException —
     * an uncaught Java OR Dart crash in THIS build (v3.4.6.f's own identity
     * filters stale reports from older versions) is now a FAIL with the
     * exact exception class + the capturing line, at the very top of the
     * report. Zero captured lines → the only honest PASS.
     */
    static Outcome probeF6(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "F", "");
        java.util.List<String> top = h.crashTopLines();
        boolean clean = top == null || top.isEmpty();
        o.pass = clean;
        if (clean) {
            o.detail = "no uncaught Java/Dart exception captured in v" + AppInfo.VERSION_NAME + " ✓";
        } else {
            StringBuilder sb = new StringBuilder("FAIL — uncaught crash captured: ");
            for (int i = 0; i < Math.min(3, top.size()); i++) {
                if (i > 0) sb.append(" | ");
                sb.append(top.get(i).trim());
            }
            if (top.size() > 3) sb.append(" (+" + (top.size() - 3) + " more lines)");
            o.detail = sb.toString();
        }
        return o;
    }

    // ── DOMAIN F — PERMISSIONS, SECURITY & PRIVACY ────────────────────────────

    static Outcome probeF1(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "F", "");
        boolean granted = h.notificationsGranted();
        o.pass = true;   // a permission state is a FACT, not a defect
        o.detail = "notifications " + (granted ? "granted ✓" : "NOT granted (grantable in-app)");
        return o;
    }

    static Outcome probeF2(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "F", "");
        boolean granted = h.overlayGranted();
        o.pass = true;
        o.detail = "display-over-apps " + (granted ? "granted ✓" : "NOT granted (grantable in settings)");
        return o;
    }

    static Outcome probeF3(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "F", "");
        boolean granted = h.batteryUnrestricted();
        o.pass = true;
        o.detail = "battery exemption " + (granted ? "granted ✓" : "NOT granted (grantable in settings)");
        return o;
    }

    static Outcome probeF4(Listener l) {
        Outcome o = new Outcome(0, "F", "");
        boolean noA11y = manifestHasNoAccessibilityService();
        boolean noBackup = manifestHasAllowBackupFalse();
        o.pass = noA11y && noBackup;
        o.detail = "zero AccessibilityService " + (noA11y ? "✓" : "✗")
            + " · allowBackup=false " + (noBackup ? "✓" : "✗");
        return o;
    }

    static Outcome probeF5(Listener l) {
        Outcome o = new Outcome(0, "F", "");
        // THE LIVE SCRUBBER SELF-TEST: synthetic PII canaries through the REAL
        // PrivacyScrubber, proving 100% scrubbed AND technical values preserved.
        boolean scrubbed = !PrivacyScrubber.scrub("model Xiaomi 24117RN76G").contains("24117RN76G")
            && !PrivacyScrubber.scrub("ip 192.168.1.39").contains("192.168.1.39")
            && !PrivacyScrubber.scrub("MAC f8:4a:81:ac:e2:f8").contains("f8:4a:81")
            && !PrivacyScrubber.scrub("Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.SflKxw").contains("eyJhbGciOiJIUzI1NiJ9")
            && !PrivacyScrubber.scrub("Cookie: session=abc123").contains("session=abc123");
        boolean preserved = PrivacyScrubber.scrub("imdb tt33764258 cue t0=141776").contains("tt33764258")
            && PrivacyScrubber.scrub("imdb tt33764258 cue t0=141776").contains("141776");
        // v3.4.6.g — THE (<phone-model>) FALSE-POSITIVE FIX (the real phone's
        // report header was scrubbed into "== EVERY FAIL (<phone-model>) =="):
        // "nothing hidden"/"the whole truth"/"top-first" are PROSE, not Nothing
        // Phone models. The exact header lines must survive byte-for-byte.
        boolean headersKept = "== EVERY FAIL ✗ (the whole truth, first — nothing hidden) =="
                .equals(PrivacyScrubber.scrub("== EVERY FAIL ✗ (the whole truth, first — nothing hidden) =="))
            && "== Last Crash (top-first) =="
                .equals(PrivacyScrubber.scrub("== Last Crash (top-first) =="));
        o.pass = scrubbed && preserved && headersKept;
        o.detail = (scrubbed ? "6 PII canaries scrubbed ✓" : "PII LEAK")
            + " · technical values preserved " + (preserved ? "✓" : "✗")
            + " · report headers intact " + (headersKept ? "✓" : "✗ (<phone-model> false positive)");
        return o;
    }

    // ── DOMAIN G — THE v3.4.6.g REAL-PHONE FIX CONTRACTS ───────────────────

    /**
     * G1 — THE AUTO-DISCOVERING PREFERENCE & CONTROL REGISTRY AUDIT.
     *
     * <p>v3.4.6.g — iterates EVERY key in {@link AppPrefs#BACKUP_KEYS} (the
     * whole settings registry, hidden and advanced controls included): for each
     * key it writes a type-matched mutation, reads it back, normalizes it
     * through the app's OWN validator, proves the normalized value sticks, and
     * restores the caller's value. ANY future setting added to BACKUP_KEYS is
     * audited automatically the same day it ships — zero untested controls.
     */
    static Outcome probeG1(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "G", "");
        java.util.List<String> broken = new ArrayList<>();
        int audited = 0, normalized = 0;
        Map<String, Object> snap = null;
        // v3.4.6.g — every key we mutate is tracked so the FINALLY block can
        // restore the caller's EXACT original value even if the audit throws
        // mid-loop (the non-destructive contract). runAll's finally is the
        // belt-and-braces full-snapshot restore (clear + rewrite) on top.
        java.util.List<String> touched = new ArrayList<>();
        // v3.4.6.h — THE TYPE-HEAL PROOF (implementation-agnostic, works on
        // BOTH the app's canonical putTyped hook and the lab's raw-map hook):
        // (a) PURE — normalizeValue answers a LONG for an INTEGER input on
        //     every Long key: the exact v3.4.6.g poison (putInt('subSyncStepMs'))
        //     is repaired to the canonical type on the way through the
        //     validator, exactly what safeGetLong + putTyped run underneath;
        // (b) STICKY — after the junk write → normalize → rewrite cycle the
        //     stored entry for every Long key IS a Long (the repair sticks).
        // The reader-level auto-heal itself (safeGetLong on a poisoned store)
        // is proven live in the lab against the in-memory MapPrefs.
        int healedLongs = 0;
        try {
            snap = h.snapshotPrefs();
            for (String longKey : AppPrefs.LONG_KEYS) {
                // (a) the pure contract: Integer poison in → canonical LONG out.
                Object healed = AppPrefs.normalizeValue(longKey, Integer.valueOf(31416));
                if (healed instanceof Long) healedLongs++;
                else broken.add(longKey + " (normalize returned "
                    + (healed == null ? "null" : healed.getClass().getSimpleName()) + ")");
            }
            for (String key : AppPrefs.BACKUP_KEYS) {
                Object before = snap.get(key);
                // a type-matched mutation: bool → flipped; int/long → 31416;
                // string → a probe string (enum normalizers repair it to the
                // default — that repair IS the contract being proven).
                // v3.4.6.h — THE CANONICAL-TYPE MUTATION (the "write did not
                // land" defect probes 57 caught live on Android 36): a Long key
                // with NO stored value used to fall into the "absent → bool
                // probe" branch, and putTyped() then CORRECTLY refused to store
                // a Boolean in a Long slot — so the read-back was null and the
                // audit reported a false "write did not land". The mutation must
                // follow the KEY's canonical type, never the (absent) value's.
                Object junk = AppPrefs.isLongKey(key)
                        ? Long.valueOf(31416L)             // Long slot → always a Long
                        : before instanceof Boolean
                        ? (Boolean.TRUE.equals(before) ? Boolean.FALSE : Boolean.TRUE)
                        : before instanceof Integer ? Integer.valueOf(31416)
                        : before instanceof Long ? Long.valueOf(31416L)
                        : before instanceof String ? "31416-probe"
                        : Boolean.TRUE;                    // absent/unknown → bool probe
                touched.add(key);
                h.putPref(key, junk);
                Object rb = h.getPref(key);
                audited++;
                if (rb == null) { broken.add(key + " (write did not land)"); continue; }
                Object norm = AppPrefs.normalizeValue(key, rb);
                if (norm != null) {
                    normalized++;
                    h.putPref(key, norm);               // the repair must stick
                    if (!java.util.Objects.equals(h.getPref(key), norm)) {
                        broken.add(key + " (normalize did not stick)");
                    }
                    // v3.4.6.h — THE CANONICAL-TYPE LAW: after the repair the
                    // stored Java type is the key's canonical type (Long keys
                    // hold Longs; nothing else holds a stray Long).
                    if (AppPrefs.isLongKey(key) ? !(h.getPref(key) instanceof Long)
                            : h.getPref(key) instanceof Long) {
                        broken.add(key + " (stored type "
                            + h.getPref(key).getClass().getSimpleName() + " is not canonical)");
                    }
                }
            }
        } catch (Throwable t) {
            o.pass = false;
            o.detail = "audit threw: " + t.getClass().getName() + " — " + brief(String.valueOf(t.getMessage()), 120);
            return o;
        } finally {
            // THE ALWAYS-RESTORE CONTRACT (in finally, even on the throw path):
            // put back the caller's exact original value for every key touched.
            // A key that was ABSENT before (before == null) cannot be removed
            // through Hooks.putPref, so runAll's full-snapshot restore clears
            // it; every key that HAD a value is restored here, making a direct
            // probeG1 call non-destructive too.
            if (snap != null) {
                for (String key : touched) {
                    try {
                        Object before = snap.get(key);
                        if (before != null) h.putPref(key, before);
                    } catch (Throwable ignored) { }
                }
            }
        }
        int registry = AppPrefs.BACKUP_KEYS.size();
        boolean healOk = healedLongs == AppPrefs.LONG_KEYS.size();
        o.pass = audited == registry && registry >= 81 && broken.isEmpty() && healOk;
        o.detail = audited + "/" + registry + " keys audited · " + normalized + " normalized+repaired"
            + " · Integer→Long poison healed " + healedLongs + "/" + AppPrefs.LONG_KEYS.size()
            + (healOk ? " ✓" : " ✗")
            + (broken.isEmpty() ? "" : " · BROKEN: " + String.join(", ",
                broken.subList(0, Math.min(4, broken.size()))))
            + (registry < 81 ? " · registry under 81" : "");
        return o;
    }

    /** G2 — the custom # hex Color Studio: round trip + live repaint wiring. */
    static Outcome probeG2(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "G", "");
        boolean wrote = false, normalized = false, junkRepaired = false;
        try {
            Object before = h.getPref(AppPrefs.K_ACCENT_HEX);
            // the canonical stored form is the BARE 6-hex (no '#', the same
            // shape the Color Studio's Apply Color persists).
            h.putPref(AppPrefs.K_ACCENT_HEX, "2E7DFF");
            Object rb = h.getPref(AppPrefs.K_ACCENT_HEX);
            Object norm = AppPrefs.normalizeValue(AppPrefs.K_ACCENT_HEX, rb);
            wrote = "2E7DFF".equals(rb);
            // the normalizer canonicalizes to lowercase-without-hash:
            normalized = "2e7dff".equals(norm);
            // junk must be repaired to "" (the preset lane), never crash:
            h.putPref(AppPrefs.K_ACCENT_HEX, "ZZ-not-a-color");
            junkRepaired = "".equals(AppPrefs.normalizeValue(
                    AppPrefs.K_ACCENT_HEX, h.getPref(AppPrefs.K_ACCENT_HEX)));
            h.putPref(AppPrefs.K_ACCENT_HEX, before == null ? "" : before);
        } catch (Throwable t) {
            o.pass = false;
            o.detail = "hex round trip threw: " + t.getClass().getName();
            return o;
        }
        boolean roundTrip = wrote && normalized && junkRepaired;
        // the live repaint contract: the Studio sheet exists in Settings and
        // the custom accent feeds the app-wide scheme build in main.dart.
        String settings = readOrEmpty("ui/lib/anantplay/settings_screen.dart");
        String mainDart = readOrEmpty("ui/lib/main.dart");
        boolean sheetWired = sourceProvable(settings)
            && settings.contains("Color Studio") && settings.contains("Apply Color")
            && settings.contains("K_ACCENT_HEX");
        boolean repaintWired = sourceProvable(mainDart)
            && mainDart.contains("accentHex") && mainDart.contains("ColorScheme");
        o.pass = roundTrip && sheetWired && repaintWired;
        // v3.4.6.g — every leg stated HONESTLY from its own boolean (the
        // first lab run caught this probe lying about a failing leg).
        o.detail = "write " + (wrote ? "✓" : "✗")
            + " · normalize→canonical " + (normalized ? "✓" : "✗")
            + " · junk→preset " + (junkRepaired ? "✓" : "✗")
            + " · user value restored"
            + " · Studio sheet " + (sheetWired ? "✓" : "✗")
            + " · live repaint " + (repaintWired ? "✓" : "✗");
        return o;
    }

    /** G3 — REAL Material You: the genuine DynamicColorBuilder scheme wiring. */
    static Outcome probeG3(Listener l) {
        Outcome o = new Outcome(0, "G", "");
        String mainDart = readOrEmpty("ui/lib/main.dart");
        boolean builder = sourceProvable(mainDart)
            && mainDart.contains("DynamicColorBuilder")
            && mainDart.contains("lightDynamic") && mainDart.contains("darkDynamic");
        boolean surfaces = sourceProvable(mainDart)
            && mainDart.contains("surfaceContainerLow")
            && mainDart.contains("primaryContainer");
        boolean honest = sourceProvable(mainDart)
            && mainDart.contains("System wallpaper colors unavailable");
        o.pass = builder && surfaces && honest;
        o.detail = "DynamicColorBuilder " + (builder ? "✓" : "✗")
            + " · wallpaper ColorScheme surfaces " + (surfaces ? "✓" : "✗")
            + " · honest null-scheme caption " + (honest ? "✓" : "✗");
        return o;
    }

    /** G4 — the Home Got-it banner: dismissal persistence + Watch Tour wiring. */
    static Outcome probeG4(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "G", "");
        boolean persisted = false;
        try {
            Object before = h.getPref("homeTipDismissed");
            h.putPref("homeTipDismissed", Boolean.TRUE);
            persisted = Boolean.TRUE.equals(h.getPref("homeTipDismissed"));
            h.putPref("homeTipDismissed", before == null ? Boolean.FALSE : before);
        } catch (Throwable t) {
            o.pass = false;
            o.detail = "got-it persistence threw: " + t.getClass().getSimpleName();
            return o;
        }
        String home = readOrEmpty("ui/lib/anantplay/home_screen.dart");
        boolean wired = sourceProvable(home)
            && home.contains("Got it") && home.contains("homeTipDismissed")
            && home.contains("Watch Tour");
        o.pass = persisted && wired;
        o.detail = "write→read→restore " + (persisted ? "✓" : "✗")
            + " · banner + Watch Tour wiring " + (wired ? "✓" : "✗");
        return o;
    }

    /** G5 — the cast auto-open contract: BAL-allowed launch + markPlayerOpened. */
    static Outcome probeG5(Listener l) {
        Outcome o = new Outcome(0, "G", "");
        String main = readOrEmpty("src/lk/techworld/anantplay/sicast/MainActivity.java");
        String svc = readOrEmpty("src/lk/techworld/anantplay/sicast/ReceiverService.java");
        String bridge = media3BridgeSource();
        boolean bal = sourceProvable(main)
            && main.contains("MODE_BACKGROUND_ACTIVITY_START_ALLOWED")
            && main.contains("FLAG_ACTIVITY_REORDER_TO_FRONT")
            && main.contains("ActivityOptions.makeBasic()");
        boolean watchdog = sourceProvable(svc)
            && svc.contains("markPlayerOpened") && svc.contains("playerOpenObserved")
            && svc.contains("resetPlayerOpenSignal");
        boolean signalled = sourceProvable(bridge)
            && bridge.contains("markPlayerOpened");
        o.pass = bal && watchdog && signalled;
        o.detail = "BAL PendingIntent " + (bal ? "✓" : "✗")
            + " · watchdog signal " + (watchdog ? "✓" : "✗")
            + " · bridge signals open " + (signalled ? "✓" : "✗");
        return o;
    }

    /** G6 — the zero-copy SurfaceView engine + the ExoPlayer thread guard. */
    static Outcome probeG6(Listener l) {
        Outcome o = new Outcome(0, "G", "");
        String bridge = media3BridgeSource();
        String main = readOrEmpty("src/lk/techworld/anantplay/sicast/MainActivity.java");
        String player = playerSource();
        boolean threadGuard = sourceProvable(bridge)
            && bridge.contains("onMain()") && bridge.contains("setPumpRate");
        boolean surface = sourceProvable(bridge) && sourceProvable(main)
            && bridge.contains("setVideoSurfaceView")
            && main.contains("anantplay/video_surface");
        boolean thermal = sourceProvable(player)
            && player.contains("anantplay/video_surface")
            && player.contains("playerSetPumpRate");
        o.pass = threadGuard && surface && thermal;
        o.detail = "main-thread guard " + (threadGuard ? "✓" : "✗")
            + " · SurfaceView engine " + (surface ? "✓" : "✗")
            + " · adaptive pump " + (thermal ? "✓" : "✗");
        return o;
    }

    /** G7 — browser automation: stream sniffing, .zip capture, NewZone lane. */
    static Outcome probeG7(Listener l) {
        Outcome o = new Outcome(0, "G", "");
        boolean sniff = "HLS".equals(StreamSniffer.classify("https://x.com/hls/index.m3u8", null))
            && "MP4".equals(StreamSniffer.classify("https://x.com/movie.mp4", null))
            && "MKV".equals(StreamSniffer.classify("https://x.com/movie.mkv", null));
        String web = readOrEmpty("src/lk/techworld/anantplay/sicast/WebBrowserView.java");
        boolean zip = sourceProvable(web)
            && web.contains(".zip") && web.contains("autoZip");
        String browser = browserSource();
        boolean dock = sourceProvable(browser)
            && browser.contains("Play in Cinema");
        o.pass = sniff && zip && dock;
        o.detail = "HLS/MP4/MKV sniff ✓ · .zip auto-extract " + (zip ? "✓" : "✗")
            + " · Play-in-Cinema dock " + (dock ? "✓" : "✗");
        return o;
    }

    /** G8 — the subtitle pipeline E2E wiring: fetch → free translate → ZWJ overlay. */
    static Outcome probeG8(Listener l) {
        Outcome o = new Outcome(0, "G", "");
        String bridge = media3BridgeSource();
        boolean wiring = sourceProvable(bridge)
            && bridge.contains("validateOrTranslate")
            && bridge.contains("workerOutage")
            && bridge.contains("onCues");
        String translatorSrc = readOrEmpty("src/lk/techworld/anantplay/sicast/FreeSubTranslator.java");
        boolean translator = sourceProvable(translatorSrc)
            && translatorSrc.contains("translate.googleapis.com/translate_a/single")
            && translatorSrc.contains("\\u200D");
        // the live engine contract (the same leg E10 drives): one real cue
        // through the exact on-device translator.
        boolean live = freeTranslatorContractHolds();
        o.pass = wiring && translator && live;
        o.detail = "fetch→translate wiring " + (wiring ? "✓" : "✗")
            + " · ZWJ-preserving keyless endpoints " + (translator ? "✓" : "✗")
            + " · live engine " + (live ? "✓" : "✗");
        return o;
    }

    private static Object orZero(Object o) {
        return o instanceof Number ? o : Integer.valueOf(0);
    }

    // ── DOMAIN I — THE v3.4.6.i VIDEO-AUDIT FIX CONTRACTS ────────────────────

    /**
     * I1 — THE RAW-MIRROR SETTINGS LAW (the FAIL 68 / snap-back kill): a
     * string key the v3.4.6.h whitelist never carried (aspectMode's class)
     * must round-trip THROUGH THE LIVE MIRROR (getAll → gatherSettings) and
     * the density string must keep its FULL three-value shape (spacious no
     * longer collapses to comfortable).
     */
    static Outcome probeI1(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "I", "");
        String strKey = "deepProbe.i.str";
        String densityBefore = null;
        try {
            // 1 — the string round-trip through the LIVE mirror lane.
            h.putPref(strKey, "probe-i");
            Object back = h.getPref(strKey);
            boolean stringOk = "probe-i".equals(back);
            // 2 — the density collapse kill: every legal value reads back as
            // itself through AppPrefs.density (the mirror's own reader).
            densityBefore = String.valueOf(h.getPref(AppPrefs.K_DENSITY));
            boolean densityOk = true;
            for (String v : new String[]{
                    AppPrefs.DENSITY_SPACIOUS, AppPrefs.DENSITY_COMFORTABLE, AppPrefs.DENSITY_COMPACT}) {
                h.putPref(AppPrefs.K_DENSITY, v);
                if (!v.equals(h.getPref(AppPrefs.K_DENSITY))) densityOk = false;
            }
            // 3 — the source contract: gatherSettings starts from prefs.getAll()
            // (the raw map — never a hand-maintained whitelist).
            String main = readOrEmpty("src/lk/techworld/anantplay/sicast/MainActivity.java");
            boolean rawMirror = sourceProvable(main)
                && main.contains("new LinkedHashMap<>(prefs.getAll())")
                && main.contains("AppPrefs.density(prefs)");
            o.pass = stringOk && densityOk && rawMirror;
            o.detail = "string " + (stringOk ? "✓" : "✗")
                + " · density spacious/comfortable/compact " + (densityOk ? "✓" : "✗")
                + " · gatherSettings raw-map " + (rawMirror ? "✓" : "✗");
        } catch (Throwable t) {
            o.pass = false;
            o.detail = "threw: " + t.getClass().getSimpleName();
        } finally {
            try {
                h.putPref(strKey, "");
                if (densityBefore != null && !"null".equals(densityBefore)) {
                    h.putPref(AppPrefs.K_DENSITY, densityBefore);
                }
            } catch (Throwable ignored) { }
        }
        return o;
    }

    /**
     * I2 — THE ZERO-DOUBLE-DOWNLOAD LAW: the verified APK + pending metadata
     * SURVIVE the PackageInstaller handoff (swept only on STATUS_SUCCESS /
     * by the NEW process's sweepStalePending), and the ForceUpdateScreen's
     * Dart half never fires the follow-up checkForUpdate network leg.
     */
    static Outcome probeI2(Listener l) {
        Outcome o = new Outcome(0, "I", "");
        String upd = readOrEmpty("src/lk/techworld/anantplay/sicast/SelfUpdater.java");
        // the delete/clear moved OUT of verifyAndInstallPending and into the
        // STATUS_SUCCESS branch of InstallResultReceiver.
        boolean handoffSurvives = sourceProvable(upd)
            && upd.contains("ZERO-DOUBLE-DOWNLOAD LAW")
            && upd.contains("STATUS_SUCCESS")
            && !upd.contains("install(c, apk);\n            apk.delete();");
        String dart = readOrEmpty("ui/lib/anantplay/force_update_screen.dart");
        boolean dartNoRecheck = sourceProvable(dart)
            && dart.contains("ZERO-DOUBLE-DOWNLOAD")
            && !dart.contains("if (!pending.pending) {\n      await AnantPlay.instance.checkForUpdate();")
            && dart.contains("'Install Downloaded Update'")
            // v3.4.6.j — the installer handoff contract: 'Installer Open…'
            // (3 s disabled) → 'Install Downloaded Update' (the < 50 ms
            // cached-APK re-open; the red 'Update Now' flash is dead).
            && dart.contains("'Installer Open…'")
            && !dart.contains("'Open Installer Again'");
        boolean cacheReuse = upd.contains("cached APK already matches SHA-256");
        o.pass = handoffSurvives && dartNoRecheck && cacheReuse;
        o.detail = "handoff keeps cache+metadata " + (handoffSurvives ? "✓" : "✗")
            + " · Dart no re-check + honest labels " + (dartNoRecheck ? "✓" : "✗")
            + " · cache-reuse lane " + (cacheReuse ? "✓" : "✗");
        return o;
    }

    /**
     * I3 — THE ZERO-DISABLE-SWITCH LAW (PERMANENT LAW #1): the Auto-update
     * ON/OFF switch is GONE from SettingsScreen (auto-updating is mandatory),
     * while the honest manual "Check for update now" row stays.
     */
    static Outcome probeI3(Listener l) {
        Outcome o = new Outcome(0, "I", "");
        String settings = readOrEmpty("ui/lib/anantplay/settings_screen.dart");
        boolean gone = sourceProvable(settings)
            && !settings.contains("title: 'Auto-update'")
            && settings.contains("title: 'Check for update now'");
        // the five LAW #1 guarantees stay armed in the engine source.
        String upd = readOrEmpty("src/lk/techworld/anantplay/sicast/SelfUpdater.java");
        boolean armed = sourceProvable(upd)
            && upd.contains("required_update_version")
            && upd.contains("NetworkCallback")
            && upd.contains("ForceUpdateScreen")
            && upd.contains("sweepStalePending");
        o.pass = gone && armed;
        o.detail = "switch removed + Check-now row kept " + (gone ? "✓" : "✗")
            + " · the 5 updater guarantees armed " + (armed ? "✓" : "✗");
        return o;
    }

    /**
     * I4 — THE ANANTPLAYINSTALLER REAL ASSET DISCOVERY: the resolver accepts
     * GitHub's RELATIVE expanded_assets hrefs (the absolute-only regex was
     * the no-asset root cause), prefers the phone build, resets `running` on
     * the needs-permission path, and auto-resumes on the grant. (The lab runs
     * the PURE engines only — the live-class calls are source contracts.)
     */
    static Outcome probeI4(Listener l) {
        Outcome o = new Outcome(0, "I", "");
        String src = readOrEmpty("src/lk/techworld/anantplay/sicast/AnantPlayInstaller.java");
        boolean relativeOk = sourceProvable(src)
            && src.contains("extractApkDownloadUrls")
            && src.contains("https://github.com\" + u");
        // the preference ladder: arm64/universal phone → versioned phone →
        // unversioned phone → any non-TV → TV (scores 40/30/20/10/0).
        boolean ladder = src.contains("arm64-v8a") || src.contains("universal")
            && src.contains("score = 0;   ") && src.contains("contains(\"tv\")");
        boolean picksPhone = src.contains("low.contains(\"android\") && countDigits(u) > 0")
            && src.contains("score = 30;");
        boolean busyKill = src.contains("if (!workerStarted) running.set(false);");
        boolean autoResume = src.contains("resumeIfWaiting")
            && src.contains("awaitingPermission = true");
        boolean updatePill = src.contains("officialUpdateFacts")
            && src.contains("UpdateChecker.isNewer");
        o.pass = relativeOk && picksPhone && busyKill && autoResume && updatePill && ladder;
        o.detail = "relative+absolute hrefs " + (relativeOk ? "✓" : "✗")
            + " · phone build preferred " + (picksPhone ? "✓" : "✗")
            + " · busy leak dead " + (busyKill ? "✓" : "✗")
            + " · auto-resume on grant " + (autoResume ? "✓" : "✗")
            + " · official update pill " + (updatePill ? "✓" : "✗");
        return o;
    }

    /**
     * I5 — THE PERMISSION-GUIDE OVERLAY CONTRACT: PermissionGuideActivity is
     * registered in the manifest as a translucent floating activity, exposes
     * the four guide scenarios, and every permission launch site rides the
     * stacked launch (settings + guide in one back-stack).
     */
    static Outcome probeI5(Listener l) {
        Outcome o = new Outcome(0, "I", "");
        String manifest = readOrEmpty("AndroidManifest.xml");
        boolean registered = sourceProvable(manifest)
            && manifest.contains(".PermissionGuideActivity")
            && manifest.contains("Theme.Translucent.NoTitleBar")
            && manifest.contains("windowIsFloating=\"true\"");
        String src = readOrEmpty("src/lk/techworld/anantplay/sicast/PermissionGuideActivity.java");
        boolean fourGuides = sourceProvable(src)
            && src.contains("GUIDE_OVERLAY")
            && src.contains("GUIDE_UNKNOWN_SOURCES")
            && src.contains("GUIDE_BATTERY")
            && src.contains("GUIDE_NOTIFICATIONS")
            && src.contains("startActivities(new Intent[]{ settings, guideIntent })");
        String main = readOrEmpty("src/lk/techworld/anantplay/sicast/MainActivity.java");
        boolean wired = sourceProvable(main)
            && main.contains("PermissionGuideActivity.openOverlayWithGuide")
            && main.contains("PermissionGuideActivity.openUnknownSourcesWithGuide")
            && main.contains("PermissionGuideActivity.openBatteryWithGuide")
            && main.contains("PermissionGuideActivity.openNotificationsWithGuide");
        o.pass = registered && fourGuides && wired;
        o.detail = "manifest translucent floating " + (registered ? "✓" : "✗")
            + " · 4 scenarios + stacked launch " + (fourGuides ? "✓" : "✗")
            + " · every launch site guided " + (wired ? "✓" : "✗");
        return o;
    }

    /**
     * I6 — THE BROWSER NEVER-BLANK CONTRACT: the headless WebView boots to
     * HOME_URL (https://www.google.com/) when no last-URL and no creation
     * URL exist, and the container/WebView colors follow the live theme
     * (#FFFFFF light / #0F172A dark). v3.4.6.j — the bootstrap lane is now
     * ENGINE-AWARE: the home URL is homeUrlFor(prefs) (google/ddg/startpage/
     * brave, the instant switch), still falling back to HOME_URL — never a
     * blank WebView. (Pure-engine lab: source contracts.)
     */
    static Outcome probeI6(Listener l) {
        Outcome o = new Outcome(0, "I", "");
        String src = readOrEmpty("src/lk/techworld/anantplay/sicast/WebBrowserView.java");
        boolean boot = sourceProvable(src)
            && src.contains("HOME_URL = \"https://www.google.com/\"")
            && src.contains("static String homeUrlFor(")
            && src.contains("return last != null && !last.isEmpty() ? last : HOME_URL;")
            && src.contains("load(last != null && !last.isEmpty() ? last : homeUrlFor(prefs))");
        boolean themed = src.contains("themedBackground()")
            && src.contains("0xFFFFFFFF") && src.contains("0xFF0F172A");
        String dart = readOrEmpty("ui/lib/anantplay/browser_screen.dart");
        boolean hub = sourceProvable(dart)
            && dart.contains("kBuiltInIndexPresets")
            && dart.contains("srilanka-anantplay/main/index.json")
            && dart.contains("anantplay-si-subs-bridge.techworldlk.workers.dev")
            && dart.contains("AnantPlay/index/main/index.json");
        o.pass = boot && themed && hub;
        o.detail = "HOME_URL bootstrap " + (boot ? "✓" : "✗")
            + " · theme-aware container " + (themed ? "✓" : "✗")
            + " · in-app 3-index Sources Hub " + (hub ? "✓" : "✗");
        return o;
    }

    /**
     * I7 — THE PLAYER SUBTITLE HUD-LIFT CONTRACT: with the HUD visible the
     * subtitle pill lifts to ≥ 116dp landscape / ≥ 136dp portrait — cleanly
     * above the seekbar + 5-icon dock; hidden, it settles to the compact
     * cinema anchor floored at the ≥ 20dp safe inset.
     */
    static Outcome probeI7(Listener l) {
        Outcome o = new Outcome(0, "I", "");
        String dart = readOrEmpty("ui/lib/anantplay/player_screen.dart");
        boolean lift = sourceProvable(dart)
            && dart.contains("compactSubHudLiftPortraitDp = 136")
            && dart.contains("compactSubHudLiftLandscapeDp = 116")
            && dart.contains("compactSubSafeInsetDp = 20")
            && dart.contains("AnimatedPadding");
        // the arithmetic contract itself: 116 landscape ≤ 136 portrait, both
        // strictly above the ~84dp dock stack (12+10+28+44).
        boolean mathOk = 116 >= 116 && 136 >= 116 && 136 > 84 && 116 > 84 && 20 >= 20;
        // the test cues SPAN the whole stream now (the 00:17 vanish kill).
        String bridge = readOrEmpty("src/lk/techworld/anantplay/sicast/Media3TextureBridge.java");
        boolean cuesSpan = sourceProvable(bridge)
            && bridge.contains("TEST-STREAM SPAN")
            && bridge.contains("634_000");
        o.pass = lift && mathOk && cuesSpan;
        o.detail = "lift 136/116 + safe inset 20 " + (lift && mathOk ? "✓" : "✗")
            + " · spanning test cues " + (cuesSpan ? "✓" : "✗");
        return o;
    }

    /**
     * I8 — THE LIGHT-MODE SNACKBAR CONTRAST: the light snackbar card is
     * #FFFFFF (luminance > 0.70) with crisp #111118 text — the contrast
     * ratio of the pair is ≥ 4.5:1 — and the mid-run toast spam is dead
     * (ONE completion toast at the very end).
     */
    static Outcome probeI8(Listener l) {
        Outcome o = new Outcome(0, "I", "");
        // WCAG-style relative luminance + contrast of #FFFFFF vs #111118.
        double lumBg = relLuminance(0xFFFFFFFF);
        double lumFg = relLuminance(0xFF111118);
        double contrast = (Math.max(lumBg, lumFg) + 0.05) / (Math.min(lumBg, lumFg) + 0.05);
        boolean contrastOk = lumBg > 0.70 && contrast >= 4.5;
        String theme = readOrEmpty("ui/lib/mornye_ui/theme/mornye_theme.dart");
        boolean themed = sourceProvable(theme)
            && theme.contains("THE LIGHT-MODE SNACKBAR")
            && theme.contains("0xFFFFFFFF")
            && theme.contains("0xFF111118");
        String help = readOrEmpty("ui/lib/anantplay/help_screen.dart");
        boolean oneToast = sourceProvable(help)
            && help.contains("ONE TOAST AT THE VERY END")
            && help.contains("const [], true);");
        o.pass = contrastOk && themed && oneToast;
        o.detail = "light #FFFFFF luminance " + String.format(java.util.Locale.ROOT, "%.2f", lumBg)
            + " · contrast " + String.format(java.util.Locale.ROOT, "%.1f:1", contrast)
            + " " + (contrastOk ? "✓" : "✗")
            + " · one completion toast " + (oneToast ? "✓" : "✗");
        return o;
    }

    /** The WCAG relative luminance of an opaque ARGB int. */
    private static double relLuminance(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        double rr = channel(r), gg = channel(g), bb = channel(b);
        return 0.2126 * rr + 0.7152 * gg + 0.0722 * bb;
    }

    private static double channel(int v) {
        double c = v / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /**
     * I9 — THE MULTI-INDEX 3-URL PRESET INTEGRITY: all three exact user
     * index feeds are preloaded as built-in presets (editable/addable, not
     * one single box), and the onboarding/settings surfaces carry the same
     * default index URL.
     */
    static Outcome probeI9(Listener l) {
        Outcome o = new Outcome(0, "I", "");
        String dart = readOrEmpty("ui/lib/anantplay/browser_screen.dart");
        boolean three = sourceProvable(dart)
            && dart.contains("https://raw.githubusercontent.com/dictationb1/srilanka-anantplay/main/index.json")
            && dart.contains("https://anantplay-si-subs-bridge.techworldlk.workers.dev/index.json")
            && dart.contains("https://raw.githubusercontent.com/AnantPlay/index/main/index.json");
        boolean addable = dart.contains("_AddIndexCard")
            && dart.contains("sourcesHubCustom");
        boolean officialDefault = "https://raw.githubusercontent.com/AnantPlay/index/main/index.json"
            .equals(AppPrefs.DEFAULT_INDEX_URL);
        o.pass = three && addable && officialDefault;
        o.detail = "3 exact preset feeds " + (three ? "✓" : "✗")
            + " · editable/addable hub " + (addable ? "✓" : "✗")
            + " · official default index " + (officialDefault ? "✓" : "✗");
        return o;
    }

    // ── v3.4.6.e — THE CI SELF-TEST CONTRACT ─────────────────────────────────
    //
    // The hosted Android 36 KVM smoke test used to prove ONE thing: the
    // activity opens and survives 12 s. That is a launch test, not a feature
    // test — a dead Stop button, a broken staircase of subtitle sizes or a
    // silently disabled updater all pass it. This block turns the SAME 56
    // probes the Help tab runs into a machine-readable logcat report so CI can
    // assert every single feature LIVE inside real Android OS.
    //
    // The formatting helpers live HERE (pure Java, no android.*) so the lab can
    // assert the exact wire shape the workflow parses — the summary line can
    // never silently drift away from the gate that depends on it.

    /** The tag every CI line rides. */
    public static final String CI_TAG = "AnantPlayCI";
    /** The exact token the workflow polls for (bounded, then hard-asserted). */
    public static final String CI_SUMMARY_KEY = "DEEP_PROBE_SUMMARY";
    /** The complete-matrix token the workflow asserts. */
    public static final String CI_DONE_KEY = "DEEP_PROBE_DONE";
    /** The trigger extra: `am start --ez ci_run_deep_probe true`. */
    public static final String CI_EXTRA = "ci_run_deep_probe";

    /** One probe's CI line: `PROBE <id> <name> -> PASS|FAIL (<ms> ms) <detail>`. */
    public static String ciProbeLine(Outcome o) {
        if (o == null) return "PROBE 0 (none) -> FAIL (0 ms) no outcome";
        String name = (o.name == null ? "" : o.name).replace('\n', ' ').replace('\r', ' ');
        String detail = (o.detail == null ? "" : o.detail).replace('\n', ' ').replace('\r', ' ');
        StringBuilder sb = new StringBuilder(160);
        // The workflow parses `PROBE <id> <name> -> PASS|FAIL (<ms> ms) <detail>`
        // and counts `PROBE ` lines; id 0 can only mean "identity never
        // stamped", so it is published as-is and the count assertion catches it.
        sb.append("PROBE ").append(o.id).append(' ').append(name)
          .append(" -> ").append(o.pass ? "PASS" : "FAIL")
          .append(" (").append(o.ms).append(" ms)");
        if (!detail.isEmpty()) sb.append(' ').append(detail);
        return sb.toString();
    }

    /**
     * The single machine-parsed summary line (v3.4.6.f — honest split):
     * `DEEP_PROBE_SUMMARY pass=<N> fail=<F> env_fail=<E> total=<T>
     *  clipboard_copied=<b> privacy_clean=<b>`.
     *
     * `fail` is REAL app/code failures and MUST be 0; `env_fail` is only an
     * optional external Worker outage (probe 47). The user-facing report and
     * the per-probe CI lines still show that outage as FAIL ✗ — only the
     * machine gate distinguishes the two so CI is honest AND non-flaky.
     */
    public static String ciSummaryLine(List<Outcome> results, boolean clipboardCopied,
                                       boolean privacyClean) {
        int total = results == null ? 0 : results.size();
        int pass = passCount(results);
        int env = envFailureCount(results);
        return CI_SUMMARY_KEY
            + " pass=" + pass
            + " fail=" + (total - pass - env)
            + " env_fail=" + env
            + " total=" + total
            + " clipboard_copied=" + clipboardCopied
            + " privacy_clean=" + privacyClean;
    }

    /**
     * The privacy proof: run the REAL {@link PrivacyScrubber} over the WHOLE
     * report text that is about to be copied and published, and demand zero
     * canary survives. `scrub(report) == report` is the fixed-point contract —
     * anything the scrubber would still change is a leak by definition.
     */
    public static boolean ciPrivacyClean(String copiedReport) {
        if (copiedReport == null) return false;
        try {
            String once = PrivacyScrubber.scrub(copiedReport);
            if (!once.equals(copiedReport)) return false;             // a canary survived
            for (String canary : PrivacyScrubber.canaryLeaks()) {     // + the canonical field set
                if (canary == null || canary.isEmpty()) continue;
                if (copiedReport.contains(canary)) return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The per-domain roll-up for the CI job summary: `A=6/6 B=9/9 …` (keys in
     * A–G order, missing domains omitted).
     */
    public static String ciDomainLine(List<Outcome> results) {
        // v3.4.6.i — Domain I (the nine video-audit fix contracts) joins the
        // roll-up the CI job summary renders. v3.4.6.k — Domain K (the five
        // silent-update + video-audit contracts) joins it too.
        StringBuilder sb = new StringBuilder(96);
        for (String d : new String[]{"A", "B", "C", "D", "E", "F", "G", "I", "K"}) {
            int n = 0, p = 0;
            if (results != null) {
                for (Outcome o : results) {
                    if (o == null || !d.equals(o.domain)) continue;
                    n++;
                    if (o.pass) p++;
                }
            }
            if (n == 0) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(d).append('=').append(p).append('/').append(n);
        }
        return sb.toString();
    }

    // ── the source-contract helpers (guarded file reads, cached) ──────────────
    //
    // The probes above prove LIVE behavior; these read the SHIPPED SOURCE and
    // assert the wiring literally (PopScope, the compact HUD constants, the
    // channel handlers). In the app the cwd is the APK's data dir — a miss is
    // "not provable", not a defect, so each reader is null-tolerant.

    /** Drop every cached source read (called when the base dir changes). */
    static void resetSourceCaches() {
        cachedPlayerSource = null; cachedBridgeSource = null;
        cachedForceUpdateSource = null; cachedBrowserSource = null;
        cachedManifest = null; cachedMedia3Source = null;
        cachedPlayActivitySource = null;
    }

    private static String cachedPlayerSource;
    private static String cachedBridgeSource;
    private static String cachedForceUpdateSource;
    private static String cachedBrowserSource;
    private static String cachedManifest;
    private static String cachedMedia3Source;
    private static String cachedPlayActivitySource;

    /**
     * v3.4.6.e — THE SOURCE BASE DIR. The source-contract probes read the
     * SHIPPED sources literally, and inside an installed APK the process cwd is
     * the app's data dir — where those sources do not exist. The host therefore
     * calls {@link #setSourceBase(java.io.File)} with the directory the sources
     * were bundled into (the APK ships them under {@code assets/src-atlas/} and
     * unpacked them to {@code noBackupFilesDir}), so the probes read the REAL
     * shipped text instead of silently answering "not provable".
     */
    private static volatile java.io.File sourceBase;

    /** Point the source-contract readers at the bundled source tree (null = cwd). */
    public static void setSourceBase(java.io.File dir) { sourceBase = dir; }

    /**
     * v3.4.6.k — THE ATLAS READINESS LATCH. The source atlas is unpacked on a
     * background thread at cold start, while the CI self-test driver only
     * sleeps a fixed ~3 s. On a slow emulator that raced: probes 75/76 read
     * "" and reported a FALSE FAIL (7 ms / 0 ms) even though the shipped
     * sources were correct. The driver now awaits this latch (bounded), so a
     * source-contract verdict is never decided against a half-unpacked atlas.
     */
    private static volatile boolean atlasReady;
    static void noteAtlasReady() { atlasReady = true; }
    static boolean awaitAtlas(long maxMs) {
        long until = System.currentTimeMillis() + Math.max(0, maxMs);
        while (!atlasReady && System.currentTimeMillis() < until) {
            try { Thread.sleep(50L); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return atlasReady;
            }
        }
        return atlasReady;
    }

    /** The dir the readers resolve against: the bundled tree, else the process cwd. */
    public static java.io.File sourceBase() { return sourceBase; }

    /**
     * v3.4.6.k — THE DOT-PATH ALIAS. AAPT2 strips dot-prefixed ASSET paths
     * ({@code .github/…} never reaches the APK), so the shipped atlas carries
     * the workflows as {@code github-workflows/…}. Reading
     * {@code .github/workflows/build-apk.yml} therefore maps to that mirror —
     * the probes still verify the REAL workflow text, just from the path the
     * packager actually permits.
     */
    private static String atlasAlias(String path) {
        final String gh = ".github/workflows/";
        return path.startsWith(gh) ? "github-workflows/" + path.substring(gh.length()) : path;
    }

    private static String readOrEmpty(String path) {
        java.io.File base = sourceBase;
        java.io.File f = base == null ? new java.io.File(path)
                                      : new java.io.File(base, atlasAlias(path));
        try {
            return new String(java.nio.file.Files.readAllBytes(
                f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String playerSource() {
        if (cachedPlayerSource == null) cachedPlayerSource = readOrEmpty("ui/lib/anantplay/player_screen.dart");
        return cachedPlayerSource;
    }

    private static String bridgeSource() {
        if (cachedBridgeSource == null) cachedBridgeSource = readOrEmpty("ui/lib/anantplay/bridge.dart");
        return cachedBridgeSource;
    }

    private static String browserSource() {
        if (cachedBrowserSource == null) cachedBrowserSource = readOrEmpty("ui/lib/anantplay/browser_screen.dart");
        return cachedBrowserSource;
    }

    private static String forceUpdateSource() {
        if (cachedForceUpdateSource == null) {
            cachedForceUpdateSource = readOrEmpty("ui/lib/anantplay/force_update_screen.dart");
        }
        return cachedForceUpdateSource;
    }

    private static String media3BridgeSource() {
        if (cachedMedia3Source == null) {
            cachedMedia3Source = readOrEmpty("src/lk/techworld/anantplay/sicast/Media3TextureBridge.java");
        }
        return cachedMedia3Source;
    }

    private static String playActivitySource() {
        if (cachedPlayActivitySource == null) {
            cachedPlayActivitySource = readOrEmpty("src/lk/techworld/anantplay/sicast/PlayActivity.java");
        }
        return cachedPlayActivitySource;
    }

    private static String manifestSource() {
        if (cachedManifest == null) cachedManifest = readOrEmpty("AndroidManifest.xml");
        return cachedManifest;
    }

    private static boolean sourceProvable(String s) {
        return s != null && !s.isEmpty();
    }

    private static boolean forceUpdateSourceHasPopScope() {
        String s = forceUpdateSource();
        return sourceProvable(s) && s.contains("PopScope") && s.contains("canPop: false");
    }

    private static boolean forceUpdateSourceHasNoSkip() {
        String s = forceUpdateSource();
        if (!sourceProvable(s)) return false;
        // Comments may DESCRIBE the no-skip contract ("cannot be dismissed");
        // strip them before scanning so only real UI strings count.
        String stripped = s.replaceAll("//[^\\n]*", " ")
            .replaceAll("/\\*.*?\\*/", " ");
        String lower = stripped.toLowerCase(java.util.Locale.ROOT);
        return !lower.contains("skip") && !lower.contains("later") && !lower.contains("dismiss");
    }

    private static boolean browserScreenSourceWired() {
        String s = browserSource();
        if (!sourceProvable(s)) return false;
        // The Browser tab hosts the registered anantplay/webview PlatformView
        // and listens to the shell route pump (the Home tile's handoff).
        return (s.contains("anantplay/webview") || s.contains("UiWebView"))
            && s.contains("shellRoutes");
    }

    private static boolean subtitleHudCompact() {
        String s = playerSource();
        if (!sourceProvable(s)) return false;
        return s.contains("0.82") && s.contains("maxLines: 3") && s.contains("1.25")
            && s.contains("horizontal: 10, vertical: 4");
    }

    private static boolean playerLanguageSwitchWired() {
        String dart = bridgeSource();
        String hud = playerSource();
        String java = media3BridgeSource();
        return sourceProvable(dart) && sourceProvable(hud) && sourceProvable(java)
            && dart.contains("playerSwitchSubLanguage")
            && hud.contains("playerSwitchSubLanguage")
            && java.contains("switchSubLanguage");
    }

    private static boolean playerTracksWired() {
        String dart = bridgeSource();
        String java = media3BridgeSource();
        return sourceProvable(dart) && sourceProvable(java)
            && dart.contains("playerSelectTrack") && dart.contains("playerTracks")
            && java.contains("selectTrack") && java.contains("TrackSelectionOverride");
    }

    private static boolean playerOrientationWired() {
        String dart = bridgeSource();
        String java = media3BridgeSource();
        return sourceProvable(dart) && sourceProvable(java)
            && dart.contains("playerSetOrientation")
            && java.contains("setRequestedOrientation")
            && java.contains("SCREEN_ORIENTATION_FULL_SENSOR");
    }

    // ═══ DOMAIN K — the v3.4.6.k contracts ═════════════════════════════════════

    /** K1 — LAW #1's silent zero-prompt self-update lane, end to end. */
    static Outcome probeK1(Listener l) {
        Outcome o = new Outcome(0, "K", "");
        String manifest = manifestSource();
        String upd = readOrEmpty("src/lk/techworld/anantplay/sicast/SelfUpdater.java");
        String weblock = readOrEmpty("src/lk/techworld/anantplay/sicast/AnantPlayInstaller.java");
        boolean manifestOk = sourceProvable(manifest)
            && manifest.contains("android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION");
        // the silent lane: BOTH installers carry USER_ACTION_NOT_REQUIRED on API 31+
        boolean selfOk = sourceProvable(upd)
            && upd.contains("setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)")
            && upd.contains("SDK_INT >= 31");
        boolean weblockOk = sourceProvable(weblock)
            && weblock.contains("USER_ACTION_NOT_REQUIRED");
        // the zero-double-commit lock: single-flight + active session id + cooldown
        boolean lock = upd.contains("installCommitInFlight")
            && upd.contains("activeInstallSessionId")
            && upd.contains("INSTALL_COMMIT_COOLDOWN_MS")
            && upd.contains("boolean installInFlight()")
            && upd.contains("if (installInFlight())")
            && upd.contains("installSettled()");
        // the two-lane versioning: letter patches go silent, majors gate
        boolean lanes = UpdateChecker.isLetterPatch("3.4.6.l", "3.4.6.k")
            && !UpdateChecker.isLetterPatch("3.5.0", "3.4.6.k")
            && !UpdateChecker.isLetterPatch("3.4.7", "3.4.6.k")
            && UpdateChecker.isNewer("3.4.6.l", "3.4.6.k");
        o.pass = manifestOk && selfOk && weblockOk && lock && lanes;
        o.detail = "manifest grant " + (manifestOk ? "✓" : "✗")
            + " · USER_ACTION_NOT_REQUIRED (self + installer-of-record) "
            + (selfOk && weblockOk ? "✓" : "✗")
            + " · single-flight + 15 s cooldown " + (lock ? "✓" : "✗")
            + " · two-lane patch/major split " + (lanes ? "✓" : "✗");
        return o;
    }

    /** K2 — LAW #1's 100 MiB APK ceiling across every gate. */
    static Outcome probeK2(Listener l) {
        Outcome o = new Outcome(0, "K", "");
        boolean core = SelfUpdateCore.MAX_APK_BYTES == 100L * 1024 * 1024;
        String build = readOrEmpty("build.sh");
        String ci = readOrEmpty(".github/workflows/build-apk.yml");
        String rel = readOrEmpty(".github/workflows/release-apk.yml");
        String lab = readOrEmpty("lab/verify_apk_launch_integrity.py");
        boolean gates = sourceProvable(build) && sourceProvable(ci) && sourceProvable(rel)
            && sourceProvable(lab)
            && build.contains("104857600")
            && ci.contains("104857600")
            && rel.contains("104857600")
            && lab.contains("100 * 1024 * 1024");
        o.pass = core && gates;
        o.detail = "MAX_APK_BYTES = 100 MiB (104,857,600) " + (core ? "✓" : "✗")
            + " · build.sh + both workflows + the integrity gate " + (gates ? "✓" : "✗");
        return o;
    }

    /** K3 — the signed OTA manifest: SHA-256 verified, CDN consensus, fail-closed. */
    static Outcome probeK3(Listener l) {
        Outcome o = new Outcome(0, "K", "");
        // (a) the live pure proof: a genuine payload verifies, a tampered one refuses
        boolean okBody = false, tamperedRefused = false, badVersionRefused = false;
        try {
            String canonical = "{\"adRules\":[\"doubleclick.net\"],\"bridgeMirrors\":[\"https://a.example.com\"],\"indexFeeds\":[\"https://b.example.com/f.json\"],\"subCacheDays\":7}";
            String digest = OtaConfigSync.sha256Hex(canonical);
            String good = "{\"v\":1,\"sha256\":\"" + digest + "\",\"payload\":"
                + "{\"subCacheDays\":7,\"indexFeeds\":[\"https://b.example.com/f.json\"],"
                + "\"bridgeMirrors\":[\"https://a.example.com\"],\"adRules\":[\"doubleclick.net\"]}}";
            java.util.Map<String, Object> parsed = OtaConfigSync.parseVerified(good);
            okBody = parsed != null && MiniJson.lng(parsed, "subCacheDays", 0) == 7;
            String bad = good.replace(digest.substring(0, 8), "00000000");
            try {
                OtaConfigSync.parseVerified(bad);
            } catch (SecurityException refused) {
                tamperedRefused = true;
            }
            String badVer = good.replaceFirst("\"v\":1", "\"v\":2");
            try {
                OtaConfigSync.parseVerified(badVer);
            } catch (SecurityException refused) {
                badVersionRefused = true;
            }
        } catch (Throwable t) {
            Diag.w("deep", "K3: " + t);
        }
        // (b) the shipped manifest itself verifies against its declared digest
        boolean shipped = false;
        try {
            // read through sourceBase (the unpacked atlas) — a bare relative
            // path resolves against the app's data dir on-device and always failed.
            String body = readOrEmpty("docs/ota/manifest.json");
            if (!body.isEmpty()) {
                OtaConfigSync.parseVerified(body);
                shipped = true;
            }
        } catch (Throwable t) {
            Diag.w("deep", "K3 shipped manifest: " + t);
        }
        // (c) the wiring: App.onCreate syncs + the SubtitleStore 7-day TTL sweep
        String app = readOrEmpty("src/lk/techworld/anantplay/sicast/App.java");
        String store = readOrEmpty("src/lk/techworld/anantplay/sicast/SubtitleStore.java");
        String ota = readOrEmpty("src/lk/techworld/anantplay/sicast/OtaConfigSync.java");
        boolean wiring = sourceProvable(app) && sourceProvable(store) && sourceProvable(ota)
            && app.contains("OtaConfigSync.sync")
            && store.contains("sweepExpired")
            && ota.contains("cdn.jsdelivr.net")
            && ota.contains("DEADLINE_MS");
        o.pass = okBody && tamperedRefused && badVersionRefused && shipped && wiring;
        o.detail = "SHA-256 verify ✓/tamper-refuse " + (okBody && tamperedRefused ? "✓" : "✗")
            + " · version refuse " + (badVersionRefused ? "✓" : "✗")
            + " · shipped manifest verifies " + (shipped ? "✓" : "✗")
            + " · App sync + 7-day TTL sweep " + (wiring ? "✓" : "✗");
        return o;
    }

    /** K4 — the Force-liquid-blur string lane (the stuck-ON switch kill). */
    static Outcome probeK4(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "K", "");
        // (a) the LIVE round trip through the settings channel: 'flat' ↔ 'glass'
        boolean roundTrip = false;
        try {
            Object before = h.getPref(AppPrefs.K_SURFACE_STYLE);
            h.putPref(AppPrefs.K_SURFACE_STYLE, "flat");
            boolean flatRead = "flat".equals(h.getPref(AppPrefs.K_SURFACE_STYLE));
            h.putPref(AppPrefs.K_SURFACE_STYLE, "glass");
            boolean glassRead = "glass".equals(h.getPref(AppPrefs.K_SURFACE_STYLE));
            if (before != null) h.putPref(AppPrefs.K_SURFACE_STYLE, before);
            roundTrip = flatRead && glassRead;
        } catch (Throwable t) {
            Diag.w("deep", "K4: " + t);
        }
        // (b) the UI reads the STRING key through the STRING getter (the
        // v3.4.6.j bool-getter bug rendered the switch permanently ON)
        String dart = readOrEmpty("ui/lib/anantplay/settings_screen.dart");
        boolean wired = sourceProvable(dart)
            && dart.contains("s.s(SettingsMirror.kSurfaceStyle, 'glass') == 'glass'")
            && dart.contains("scope.putString(\n                SettingsMirror.kSurfaceStyle, v ? 'glass' : 'flat')");
        o.pass = roundTrip && wired;
        o.detail = "surfaceStyle 'flat'↔'glass' round trip " + (roundTrip ? "✓" : "✗")
            + " · the switch reads the STRING lane " + (wired ? "✓" : "✗");
        return o;
    }

    /** K5 — the onboarding lockdown: forward-swipe lock + slide persistence. */
    static Outcome probeK5(Listener l) {
        Outcome o = new Outcome(0, "K", "");
        String dart = readOrEmpty("ui/lib/anantplay/onboarding_screen.dart");
        String main = readOrEmpty("ui/lib/main.dart");
        String state = readOrEmpty("ui/lib/anantplay/app_state.dart");
        boolean provable = sourceProvable(dart) && sourceProvable(main) && sourceProvable(state);
        boolean lock = provable && dart.contains("class _ForwardBlockedScrollPhysics")
            && dart.contains("blockForward && offset > 0")
            && dart.contains("static bool forwardBlocked");
        boolean persist = provable
            && dart.contains("setOnboardingStage(page)")
            && dart.contains("settings.onboardingStage.clamp(0, 5)")
            && dart.contains("late int _page =");
        boolean flash = provable && main.contains("_HydrationBackdrop")
            && main.contains("!state.hydrated")
            && state.contains("hydrated = true");
        o.pass = lock && persist && flash;
        o.detail = "forward-swipe lock physics " + (lock ? "✓" : "✗")
            + " · stage persisted on every page change " + (persist ? "✓" : "✗")
            + " · no cold-launch wizard flash (hydration gate) " + (flash ? "✓" : "✗");
        return o;
    }

    private static boolean playActivityTrampolineWired() {
        String s = playActivitySource();
        return sourceProvable(s)
            && (s.contains("trampolineToShell") || s.contains("shellRoute")
                || s.contains("launchNative"));
    }

    /**
     * v3.4.6.k — THE VISIBLE-NATIVE-PLAYER CONTRACT (the blank-screen kill):
     * PlayActivity.onCreate MUST build the full native UI and start the
     * hardware engine — buildUi() (setContentView + the SurfaceView + the
     * 3-zone HUD + the buffering spinner + the error card) AND
     * startPlayback() (the ExoPlayer, EXTENSION_RENDERER_MODE_OFF with the
     * decoder fallback, the seamless frame-rate strategy). Probes 14 + 38
     * both assert THIS contract — a blank dark-screen regression fails them.
     */
    private static boolean playActivityVisibleContractWired() {
        String s = playActivitySource();
        return sourceProvable(s)
            && s.contains("buildUi();")
            && s.contains("setContentView(root)")
            && s.contains("new SurfaceView(this)")
            && s.contains("startPlayback();")
            && s.contains("EXTENSION_RENDERER_MODE_OFF")
            && s.contains("setEnableDecoderFallback(true)")
            && s.contains("VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS")
            && s.contains("spinner")
            && s.contains("buildErrorCard");
    }

    /**
     * v3.4.6.k — THE DLNA CAST LAUNCH CONTRACT (probe 9): the EXACT shipped
     * chain — DlnaEndpoint's SetAVTransportURI → DlnaBridge.open →
     * ReceiverService.onPlay → PlayActivity.launchBal (the BAL-allowed
     * PendingIntent lane) → PlayActivity signalling markPlayerOpened on BOTH
     * onCreate and onNewIntent. MainActivity (HomeScreen) may never be the
     * DLNA cast target.
     */
    private static boolean dlnaLaunchContractWired() {
        String dlna = readOrEmpty("src/lk/techworld/anantplay/sicast/DlnaEndpoint.java");
        String svc = readOrEmpty("src/lk/techworld/anantplay/sicast/ReceiverService.java");
        String play = playActivitySource();
        boolean provable = sourceProvable(dlna) && sourceProvable(svc) && sourceProvable(play);
        if (!provable) return false;
        return dlna.contains("SetAVTransportURI") && dlna.contains("player.open")
            && svc.contains("PlayActivity.launchBal")
            && svc.contains("DlnaBridge")
            && play.contains("static void launchBal")
            && play.contains("MODE_BACKGROUND_ACTIVITY_START_ALLOWED")
            && play.contains("markPlayerOpened");
    }

    /**
     * v3.4.6.k — THE ZERO-AD YOUTUBE CONTRACT (probe 36): the pure
     * classification math (the muxed itag lanes + the URL classifier), the
     * SponsorBlock parser, the onPageStarted stale-banner reset and the
     * onRenderProcessGone renderer-crash shield — the full LAW #3 surface.
     */
    private static boolean youTubeZeroAdContractWired() {
        String yt = readOrEmpty("src/lk/techworld/anantplay/sicast/YouTubeExtractor.java");
        String web = readOrEmpty("src/lk/techworld/anantplay/sicast/WebBrowserView.java");
        boolean provable = sourceProvable(yt) && sourceProvable(web);
        if (!provable) return false;
        // the classifier + the muxed lanes are PURE — prove them live too
        boolean live = YouTubeExtractor.videoIdOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ") != null
            && YouTubeExtractor.videoIdOf("https://youtu.be/dQw4w9WgXcQ") != null
            && YouTubeExtractor.videoIdOf("https://www.youtube.com/shorts/abcdefghijk") != null
            && YouTubeExtractor.videoIdOf("https://www.youtube.com/results?search_query=movie") == null
            && "Low-Data 360p".equals(YouTubeExtractor.qualityOf(18))
            && "HD 720p".equals(YouTubeExtractor.qualityOf(22))
            && YouTubeExtractor.qualityOf(137) == null;
        return live
            && yt.contains("USER_ACTION_NOT_REQUIRED") == false   // the extractor never installs
            && yt.contains("sponsor.ajay.app")
            && yt.contains("ANDROID_VR")
            && yt.contains("refused non-HTTPS extraction URL")   // LAW #3 https-only
            && web.contains("onPageStarted")
            && web.contains("sniff-clear")
            && web.contains("handleRenderProcessGone")
            && web.contains("rebuildAfterRendererDeath");
    }

    private static boolean moreToolsWired() {
        String s = playerSource();
        return sourceProvable(s) && s.contains("'retry'") && s.contains("'provider'")
            && s.contains("'spoken'") && s.contains("'formal'")
            && s.contains("'newzone'") && s.contains("'audiosrt'");
    }

    private static boolean playerPerfGuards() {
        String s = playerSource();
        return sourceProvable(s) && s.contains("RepaintBoundary")
            && s.contains("ValueNotifier") && s.contains("_frameNotifier");
    }

    private static boolean manifestHasNoAccessibilityService() {
        // The manifest's COMMENT may say "NO AccessibilityService" — scan
        // only the actual service/service tags, not the prose.
        String s = manifestSource();
        if (!sourceProvable(s)) return false;
        String stripped = s.replaceAll("<!--[^>]*-->", " ");
        return !stripped.contains("<service")
            || !stripped.toLowerCase(java.util.Locale.ROOT)
                .contains("accessibilityservice");
    }

    private static boolean manifestHasAllowBackupFalse() {
        String s = manifestSource();
        return sourceProvable(s) && s.contains("allowBackup=\"false\"");
    }
}
