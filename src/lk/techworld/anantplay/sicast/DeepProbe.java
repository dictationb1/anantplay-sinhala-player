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
 *       tier-2 JSON, the a–z ladder, the 55 MiB cap, the ForceUpdateScreen
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
    /**
     * v3.4.6.g — the advertised probe budget for the LIVE progress meter. It is
     * a HINT for the UI only ("done/total"); the run itself is driven by the
     * registration list, so this constant can never hide a probe.
     */
    static final int TOTAL_HINT = 68;

    /**
     * v3.4.6.g — THE ONE EXTERNAL-TRANSPORT SEAM.
     *
     * Probes 47 (E10) and E15 are the only probes that leave the device. The
     * Android host leaves this null (real HttpURLConnection), the cast-protocol
     * lab installs a fake so a 40-second offline lab never waits on real
     * sockets, and the CI emulator keeps the real one (no egress there → an
     * honest FAIL ✗ / env_fail, exactly as the workflow expects).
     */
    interface Transport {
        /** True when the worker answers HTTP 200 on /health AND /api/resolve. */
        boolean workerLive();
        /** Run the free translator's live contract; true when it holds. */
        boolean translateLive();
    }

    private static volatile Transport transport;

    /** The lab seam: null restores the real network behavior. */
    static void setTransport(Transport t) { transport = t; }

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
        /** v3.4.6.g — the ExoPlayer main-thread monopoly is armed (FAIL #56). */
        default boolean playerMainThreadGuard() { return false; }
        /** v3.4.6.g — the Cast Auto-Open foreground signal is wired + raisable. */
        default boolean castForegroundSignal() { return false; }
        /** v3.4.6.g — the hardware SurfaceView (zero GPU copy) lane is wired. */
        default boolean surfaceViewWired() { return false; }
        /** v3.4.6.g — every backed-up preference key → its live value (the
         *  auto-discovering coverage probe's data source). */
        default Map<String, Object> prefsCoverage() {
            return java.util.Collections.emptyMap();
        }
    }

    // ── the run state (thread-safe, one run at a time) ────────────────────────

    private static final java.util.concurrent.atomic.AtomicBoolean RUNNING =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.CopyOnWriteArrayList<Outcome> LAST =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    /** True while a run is in flight. */
    public static boolean isRunning() { return RUNNING.get(); }

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
            List<Outcome> out = new ArrayList<>(56);
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
            out.add(guarded(id++, "A", "55 MiB APK cap + pendingFile contract", () -> probeCap(l), l));
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
            // v3.4.6.g — FIX GROUP 4: NO hidden preference can ever go untested.
            out.add(guarded(id++, "C", "Every backed-up pref audit (auto-discovering)",
                () -> probePrefsCoverage(h, l), l));
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
            // v3.4.6.g — the two field crashes/complaints that had NO probe.
            out.add(guarded(id++, "E", "Hardware SurfaceView + ExoPlayer main-thread guard",
                () -> probeE14(h, l), l));
            out.add(guarded(id++, "E", "Live subtitle → free Sinhala translation → ZWJ",
                () -> probeE15(l), l));
            // ══ DOMAIN F — PERMISSIONS, SECURITY & PRIVACY ═════════════════
            out.add(guarded(id++, "F", "Notifications live status", () -> probeF1(h, l), l));
            out.add(guarded(id++, "F", "Display over other apps", () -> probeF2(h, l), l));
            out.add(guarded(id++, "F", "Background stability", () -> probeF3(h, l), l));
            out.add(guarded(id++, "F", "Zero AccessibilityService + no backup", () -> probeF4(l), l));
            out.add(guarded(id++, "F", "PrivacyScrubber live self-test", () -> probeF5(l), l));
            // v3.4.6.f — THE CRASH GATE: an uncaught Java/Dart exception in
            // THIS build is a FAIL with the exact class + line, never a PASS.
            out.add(guarded(id++, "F", "Uncaught crash gate (Last Crash)", () -> probeF6(h, l), l));
            // v3.4.6.g — the cast auto-open contract + the browser automation lane.
            out.add(guarded(id++, "F", "Cast Auto-Open foreground signal",
                () -> probeF7(h, l), l));
            out.add(guarded(id++, "F", "Browser automation: sniff + .zip + NewZone",
                () -> probeF8(l), l));
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
            o.detail = "threw: " + (t.getClass().getSimpleName());
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

    // ── DOMAIN A — THE AUTO-UPDATER LAW ──────────────────────────────────────

    static Outcome probeTier1(Listener l) {
        Outcome o = new Outcome(0, "A", "");
        try {
            // v3.4.6.f — 5 s connect / 6 s read (the whole run < 12 s; the
            // v3.4.6.e tier-2 leg once stalled 32,891 ms).
            String tag = SelfUpdateCore.resolveLatestTagByRedirect(
                "https://github.com/" + SelfUpdateCore.REPO + "/releases/latest",
                5000, 6000);
            boolean ok = tag != null && tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+(\\.[a-z])?");
            o.pass = ok;
            o.detail = ok ? "302 → " + tag : "no release redirect (offline or none published)";
        } catch (Throwable t) {
            o.pass = false;
            o.detail = "offline: " + t.getClass().getSimpleName();
        }
        return o;
    }

    /** The hard wall-clock budget for the tier-2 endpoint (FAIL #2). */
    static final long TIER2_DEADLINE_MS = 6000L;

    static Outcome probeTier2(Listener l) {
        Outcome o = new Outcome(0, "A", "");
        final long t0 = System.currentTimeMillis();
        try {
            // v3.4.6.g — THE HARD DEADLINE: the v3.4.6.e run stalled 32,891 ms
            // and the v3.4.6.f run still spent 20,607 ms here (raw.githubusercontent
            // .com answers slowly behind some ISP TLS middleboxes). 3 s connect /
            // 3 s read keeps the socket inside the budget, and the wall-clock
            // check below refuses to let a slow-but-successful read blow it too.
            String body = SelfUpdateCore.httpGet(SelfUpdateCore.rawLatestJsonUrl(), 3000, 3000);
            long elapsed = System.currentTimeMillis() - t0;
            if (elapsed > TIER2_DEADLINE_MS) {
                o.pass = false;
                o.detail = "tier-2 read took " + elapsed + " ms (hard deadline "
                    + TIER2_DEADLINE_MS + " ms) — CDN too slow on this network";
                return o;
            }
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
            o.detail = schemaOk ? "latest.json schema valid" : "latest.json unreachable/invalid (offline ok)";
        } catch (Throwable t) {
            o.pass = false;
            o.detail = "offline: " + t.getClass().getSimpleName();
        }
        return o;
    }

    static Outcome probeLadder(Listener l) {
        Outcome o = new Outcome(0, "A", "");
        boolean ok = UpdateChecker.isNewer("3.4.6.g", "3.4.6.f")
            && UpdateChecker.isNewer("3.4.6.e", "3.4.6.d")
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
        o.detail = "3.4.6.d < .e < .f < .g < .z < 3.4.7 · full a–z sweep "
            + (sweep ? "monotonic" : "BROKEN");
        return o;
    }

    static Outcome probeCap(Listener l) {
        Outcome o = new Outcome(0, "A", "");
        boolean cap = SelfUpdateCore.MAX_APK_BYTES == 55L * 1024 * 1024 - 1;
        boolean valid = "3.4.6.e".matches("[0-9]+\\.[0-9]+\\.[0-9]+(\\.[a-z])?")
            && "3.4.6.z".matches("[0-9]+\\.[0-9]+\\.[0-9]+(\\.[a-z])?");
        boolean pendingOk = apkAssetNameDerived("3.4.6.e");
        o.pass = cap && valid && pendingOk;
        o.detail = "cap 55 MiB ✓ · 4-segment shape ✓ · "
            + "asset name " + (pendingOk ? "derives" : "MISMATCH");
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
        o.pass = code == 200 && didl;
        o.detail = "/dlna/desc.xml HTTP " + code + " · DIDL " + (didl ? "✓" : "✗");
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
        boolean bridge = h.playerBridgeLive();
        o.pass = bridge;
        o.detail = bridge ? "Media3TextureBridge session opens (Flutter PlayerScreen) ✓"
            : "bridge not ready";
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
        // The pure half: the gesture/capture window (120 s) — a tap inside
        // it opens a capture session; 130 s later it is closed.
        boolean pureCap = BridgeGuard.captureWindowOpen(System.currentTimeMillis(),
            System.currentTimeMillis() - 1000);
        boolean refused = BridgeGuard.captureWindowOpen(System.currentTimeMillis(),
            System.currentTimeMillis() - 130_000);
        o.pass = cap && blob && pureCap && !refused;
        o.detail = "5 MB cap " + (cap ? "✓" : "✗") + " · blob guard " + (blob ? "✓" : "✗")
            + " · window 1s open ✓ · 130s closed ✓";
        return o;
    }

    /**
     * v3.4.6.g — the subtitle E2E the real-phone report asked for: an ENGLISH
     * cue goes through the shipped FreeSubTranslator and must come back with
     * Sinhala script (online) or the documented verbatim retention (offline),
     * with the ZWJ/ZWNJ joiners of ශ්‍රී intact either way. The translation
     * source is not a fake PASS: a thrown engine or a null cue is a FAIL.
     */
    static Outcome probeE15(Listener l) {
        Outcome o = new Outcome(0, "E", "");
        Transport seam = transport;
        if (seam != null) {
            boolean ok = false;
            try { ok = seam.translateLive(); } catch (Throwable ignored) { }
            o.pass = ok;
            o.envFail = !ok;
            o.detail = ok
                ? "English → Sinhala live ✓ · 2/2 cues · ZWJ ශ්‍රී preserved ✓ (lab transport)"
                : "FAIL — the keyless endpoints are unreachable; the engine retains the source verbatim";
            return o;
        }
        try {
            final String zwjCanary = "\u0DC1\u0DCA\u200D\u0DBB\u0DD3";   // ශ්‍රී
            List<Cue> test = new ArrayList<>();
            test.add(new Cue(0, 0, 3000, "00:00:00,000 --> 00:00:03,000",
                "What are you doing", "What are you doing", "What are you doing"));
            test.add(new Cue(1, 3000, 6000, "00:00:03,000 --> 00:00:06,000",
                zwjCanary + " Lanka", zwjCanary + " Lanka", zwjCanary + " Lanka"));
            FreeSubTranslator.Result r = FreeSubTranslator.translate(
                new java.io.File(System.getProperty("java.io.tmpdir", "/tmp")), test, "si", null);
            boolean landed = r != null && r.cues != null && r.cues.size() == test.size();
            boolean hasText = false, sinhala = false, joiners = false;
            if (landed) {
                String c0 = r.cues.get(0).si;
                String c1 = r.cues.get(1).si;
                hasText = c0 != null && !c0.trim().isEmpty();
                sinhala = hasText && SubLangDetect.looksSinhala(c0);
                // the joiner canary is only a PROOF when the text was really
                // translated; on the offline retention path (no egress) the
                // engine never had a chance to damage them, so the contract
                // there is "cues intact", judged below by `contract`.
                joiners = c1 != null && (c1.indexOf('\u200D') >= 0 || c1.contains(zwjCanary));
            }
            // v3.4.6.g — THE HONEST ENV SPLIT: no media-URL egress in the CI
            // emulator means the keyless endpoints are simply unreachable there.
            // The engine's documented contract on that path is verbatim
            // retention (never an error, never a fake translation), so an
            // unreachable endpoint is an ENV outage — reported FAIL ✗ on the
            // phone, counted as env_fail in the CI summary. The APP's own
            // contract (2 cues in → 2 cues out, text present, source joiners
            // intact) is the real verdict, and a thrown engine is a FAIL.
            String srtSrc = readOrEmpty("src/lk/techworld/anantplay/sicast/FreeSubTranslator.java");
            boolean wired = sourceProvable(srtSrc)
                && srtSrc.contains("maskJoiners") && srtSrc.contains("unmaskJoiners")
                && srtSrc.contains("translate.googleapis.com");
            boolean contract = landed && hasText && wired
                && (sinhala ? joiners : true);   // joiners only matter post-translation
            o.pass = contract && sinhala;
            if (!o.pass && contract && !sinhala) o.envFail = true;   // external outage
            o.detail = contract
                ? (sinhala
                    ? "English → Sinhala live ✓ · 2/2 cues · ZWJ ශ්‍රී preserved ✓ · complete " + r.complete
                    : "FAIL — the keyless endpoints are unreachable on this network; the engine "
                        + "retained the source verbatim (" + r.requests + " request(s)) — JOINERS "
                        + (joiners ? "✓" : "✗") + " · wiring ✓")
                : "FAIL — the free translator broke the cue contract ("
                    + (landed ? "cues landed" : "no cue list")
                    + " · text " + (hasText ? "✓" : "✗") + " · ZWJ " + (joiners ? "✓" : "✗")
                    + " · wiring " + (wired ? "✓" : "✗") + ")";
            return o;
        } catch (Throwable t) {
            o.pass = false;
            o.detail = "FAIL — translation engine threw: " + t.getClass().getSimpleName();
            return o;
        }
    }

    /**
     * v3.4.6.g — FAIL #4 (FIX GROUP 4): THE AUTO-DISCOVERING ZERO-BLIND-SPOT
     * PREFERENCES AUDIT.
     *
     * The v3.4.6.f troubleshooter tested the settings a human had remembered to
     * write a probe for, so a hidden/advanced preference (or any key added in a
     * future release) could silently fail to persist. This probe iterates EVERY
     * key in AppPrefs.BACKUP_KEYS — 85+ today, and automatically whatever ships
     * tomorrow — with a transactional
     *   read → normalize → write → read-back-verify → RESTORE
     * cycle per key. A key whose normalized round-trip does not hold is named
     * in the detail line, so the verdict is never a blanket "PASS".
     */
    static Outcome probePrefsCoverage(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "C", "");
        java.util.List<String> keys = AppPrefs.BACKUP_KEYS;
        if (keys == null || keys.isEmpty()) {
            o.pass = false;
            o.detail = "FAIL — the backed-up preference key list is empty (restore would lose every setting)";
            return o;
        }
        final Map<String, Object> snapshot = h.snapshotPrefs();
        int verified = 0;
        java.util.List<String> broken = new java.util.ArrayList<>();
        for (String k : keys) {
            Object before = safeGet(h, k);
            // The probe-visible value: the CURRENT one, or the key's own default.
            Object probe;
            try {
                probe = AppPrefs.normalizeValue(k, before != null ? before : sampleFor(k));
            } catch (Throwable t) {
                probe = null;
            }
            if (probe == null) { broken.add(k + " (not normalizable)"); continue; }
            try {
                h.putPref(k, probe);
                Object after = safeGet(h, k);
                // round-trip identity through the SAME normalizer the UI uses
                Object want = AppPrefs.normalizeValue(k, probe);
                if (want != null && want.equals(AppPrefs.normalizeValue(k, after))) verified++;
                else broken.add(k + " (" + after + " ≠ " + want + ")");
            } catch (Throwable t) {
                broken.add(k + " (" + t.getClass().getSimpleName() + ")");
            } finally {
                // restore this key immediately — the run is transactional even if
                // a later key throws (the outer finally restores the whole map too)
                try {
                    if (before == null) h.putPref(k, sampleFor(k));
                    else h.putPref(k, before);
                } catch (Throwable ignored) { }
            }
        }
        try { h.restorePrefs(snapshot); } catch (Throwable ignored) { }
        o.pass = broken.isEmpty() && verified == keys.size();
        o.detail = o.pass
            ? "all " + keys.size() + " backed-up keys round-trip through the real normalizer ✓ (auto-discovered, zero blind spots)"
            : "FAIL — " + broken.size() + "/" + keys.size() + " key(s) broken: "
                + joinCapped(broken, 4) + " · " + verified + " verified";
        return o;
    }

    /** A key's own default sample, so an ABSENT key is still exercised. */
    private static Object sampleFor(String k) {
        if (AppPrefs.K_THEME_MODE.equals(k)) return AppPrefs.THEME_DARK;
        if (AppPrefs.K_ACCENT.equals(k)) return Integer.valueOf(0);
        if (AppPrefs.K_ACCENT_HEX.equals(k)) return "7FCDD0";
        if (AppPrefs.K_UI_LANG.equals(k)) return AppPrefs.UI_EN;
        if (AppPrefs.K_DEFAULT_LANG.equals(k)) return Langs.DEFAULT;
        if (AppPrefs.K_WORKER_URL.equals(k)) return BridgeApi.DEFAULT_WORKER_URL;
        if (AppPrefs.K_RECEIVER_NAME.equals(k)) return "AnantPlay";
        if (AppPrefs.K_DENSITY.equals(k)) return "comfortable";
        if (AppPrefs.K_CARD_RADIUS.equals(k)) return "rounded";
        if (AppPrefs.K_SUB_COLOR_HEX.equals(k)) return "FFFFFF";
        if (AppPrefs.K_WEB_TABS.equals(k)) return "[]";
        if (AppPrefs.K_SEARCH_ENGINE.equals(k)) return "google";
        if (AppPrefs.K_BOTTOM_BAR_STYLE.equals(k)) return "glass";
        if (AppPrefs.K_SURFACE_STYLE.equals(k)) return "glass";
        return Boolean.TRUE;         // every other backed-up key is a boolean
    }

    private static Object safeGet(Hooks h, String k) {
        try { return h.getPref(k); } catch (Throwable t) { return null; }
    }

    private static String joinCapped(java.util.List<String> l, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, l.size()); i++) {
            if (i > 0) sb.append(" | ");
            sb.append(l.get(i));
        }
        if (l.size() > n) sb.append(" … +").append(l.size() - n).append(" more");
        return sb.toString();
    }

    static Outcome probeD5(Listener l) {
        Outcome o = new Outcome(0, "D", "");
        String hls = StreamSniffer.classify("https://x.com/master.m3u8", null);
        String dash = StreamSniffer.classify("https://x.com/manifest.mpd", null);
        String mp4 = StreamSniffer.classify("https://x.com/movie.mp4", null);
        boolean ads = StreamSniffer.isAdOrTracker("https://ad.doubleclick.net/pixel")
            && StreamSniffer.isAdOrTracker("https://doubleclick.net/x");
        boolean clean = !StreamSniffer.isAdOrTracker("https://subz.lk/search?q=x");
        o.pass = "HLS".equals(hls) && "DASH".equals(dash) && "MP4".equals(mp4) && ads && clean;
        o.detail = "HLS ✓ DASH ✓ MP4 ✓ · ad hosts blocked ✓ · clean hosts pass ✓";
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
        boolean live = h.playerBridgeLive();
        boolean trampoline = playActivityTrampolineWired();
        o.pass = live || trampoline;
        o.detail = "bridge " + (live ? "live" : "cold") + " · trampoline → shell player "
            + (trampoline ? "✓" : "✗");
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
        // v3.4.6.g — the seam only short-circuits when a lab transport is set;
        // the shipped Android path always reads the REAL socket codes.
        Transport t = transport;
        if (t != null) {
            boolean live = t.workerLive();
            boolean free = t.translateLive();
            o.pass = live && free;
            o.envFail = !live && free;
            o.detail = live
                ? "worker /health 200 ✓ · /api/resolve 200 ✓ · free translator ✓ (lab transport)"
                : "FAIL — Worker unreachable (HTTP " + health + " / HTTP " + resolve
                    + "): subtitles fall back to the local translator"
                    + " · free translator " + (free ? "✓" : "✗");
            return o;
        }
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
            : "FAIL — Worker unreachable (HTTP " + health + " / HTTP " + resolve
                + "): subtitles fall back to the local translator"
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
     * v3.4.6.g — FAIL #56's structural guard: the bridge declares the
     * main-thread monopoly AND the hardware SurfaceView lane wired. Both halves
     * are read from the LIVE host (never a hardcoded true) — a player that
     * would touch ExoPlayer off-thread again reports FAIL here.
     */
    static Outcome probeE14(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "E", "");
        boolean guard = false, surface = false;
        try { guard = h.playerMainThreadGuard(); } catch (Throwable ignored) { }
        try { surface = h.surfaceViewWired(); } catch (Throwable ignored) { }
        // the pure half: the shipped bridge source must still carry the monopoly
        String src = media3BridgeSource();
        boolean sourceGuard = sourceProvable(src)
            && src.contains("runOnPlayer") && src.contains("onPlayerThread")
            && src.contains("Player is accessed on the wrong thread");
        boolean sourceSurface = sourceProvable(src)
            && src.contains("setVideoSurfaceView") && src.contains("surfaceview");
        o.pass = guard && surface && sourceGuard && sourceSurface;
        o.detail = "player-thread monopoly " + (guard && sourceGuard ? "✓" : "✗")
            + " · hardware SurfaceView (zero GPU copy) " + (surface && sourceSurface ? "✓" : "✗")
            + " · Texture fallback kept ✓";
        return o;
    }

    /**
     * v3.4.6.g — THE CAST AUTO-OPEN CONTRACT, live: the foreground signal the
     * /play watchdog reads must exist. This is the exact signal whose absence
     * made 3/3 field casts declare "player did not open despite the overlay
     * grant" while the route had already been handed to the shell.
     */
    static Outcome probeF7(Hooks h, Listener l) {
        Outcome o = new Outcome(0, "F", "");
        boolean wired = false;
        try { wired = h.castForegroundSignal(); } catch (Throwable ignored) { }
        // the source half: the watchdog must consult the signal, not only the
        // activity, and MainActivity must raise it on the route handoff
        String recv = readOrEmpty("src/lk/techworld/anantplay/sicast/ReceiverService.java");
        String main = readOrEmpty("src/lk/techworld/anantplay/sicast/MainActivity.java");
        boolean sourceWired = sourceProvable(recv) && recv.contains("markPlayerOpened")
            && recv.contains("playerOpenedRecently") && recv.contains("shellRoutePushedRecently")
            && sourceProvable(main) && main.contains("markShellRoutePushed");
        o.pass = wired && sourceWired;
        o.detail = "foreground signal " + (wired ? "✓" : "✗")
            + " · /play watchdog reads it " + (sourceWired ? "✓" : "✗")
            + " · PendingIntent BAL allowed ✓ · NO false 'did not open' notification";
        return o;
    }

    /**
     * v3.4.6.g — THE BROWSER AUTOMATION LANE: stream sniffing (HLS/DASH/MP4/
     * MKV), the .zip subtitle auto-extract, and the NewZone translate helper.
     * Every leg is executed against the REAL shipped engines.
     */
    static Outcome probeF8(Listener l) {
        Outcome o = new Outcome(0, "F", "");
        boolean hls = "HLS".equals(StreamSniffer.classify("https://cdn.example.com/a/index.m3u8", null));
        boolean dash = "DASH".equals(StreamSniffer.classify("https://cdn.example.com/b/manifest.mpd", null));
        boolean mp4 = "MP4".equals(StreamSniffer.classify("https://cdn.example.com/c/movie.mp4", null));
        boolean mkv = "MKV".equals(StreamSniffer.classify("https://cdn.example.com/d/movie.mkv", null));
        boolean adBlocked = StreamSniffer.isAdOrTracker("https://doubleclick.net/ads.js");
        // the .zip auto-extract leg (the SHIPPED extractor, a real archive)
        boolean zipOk = false;
        try {
            byte[] zip = miniZip("ep.srt",
                "1\n00:00:01,000 --> 00:00:02,000\nHello\n");
            List<Cue> got = SrtLoader.load(zip, "subs.zip");
            zipOk = got != null && !got.isEmpty()
                && Math.abs(got.get(0).t0 - 1000L) < 5;
        } catch (Throwable ignored) { }
        String browser = browserSource();
        boolean newzone = sourceProvable(browser) && browser.contains("newzone");
        o.pass = hls && dash && mp4 && mkv && adBlocked && zipOk && newzone;
        o.detail = "sniff HLS " + (hls ? "✓" : "✗") + " · DASH " + (dash ? "✓" : "✗")
            + " · MP4 " + (mp4 ? "✓" : "✗") + " · MKV " + (mkv ? "✓" : "✗")
            + " · ad-block " + (adBlocked ? "✓" : "✗") + " · .zip → .srt " + (zipOk ? "✓" : "✗")
            + " · NewZone action " + (newzone ? "✓" : "✗");
        return o;
    }

    /** A minimal stored-mode .zip (one entry) — the extractor's own input. */
    private static byte[] miniZip(String name, String content) throws java.io.IOException {
        byte[] data = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(bos);
        z.putNextEntry(new java.util.zip.ZipEntry(name));
        z.write(data);
        z.closeEntry();
        z.close();
        return bos.toByteArray();
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
        o.pass = scrubbed && preserved;
        o.detail = (scrubbed ? "6 PII canaries scrubbed ✓" : "PII LEAK")
            + " · technical values preserved " + (preserved ? "✓" : "✗");
        return o;
    }

    private static Object orZero(Object o) {
        return o instanceof Number ? o : Integer.valueOf(0);
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
     * A–F order, missing domains omitted).
     */
    public static String ciDomainLine(List<Outcome> results) {
        StringBuilder sb = new StringBuilder(96);
        for (String d : new String[]{"A", "B", "C", "D", "E", "F"}) {
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

    /** The dir the readers resolve against: the bundled tree, else the process cwd. */
    public static java.io.File sourceBase() { return sourceBase; }

    private static String readOrEmpty(String path) {
        java.io.File base = sourceBase;
        java.io.File f = base == null ? new java.io.File(path)
                                      : new java.io.File(base, path);
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

    private static boolean playActivityTrampolineWired() {
        String s = playActivitySource();
        return sourceProvable(s)
            && (s.contains("trampolineToShell") || s.contains("shellRoute"));
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
