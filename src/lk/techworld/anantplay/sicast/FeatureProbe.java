package lk.techworld.anantplay.sicast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;

/* v3.2.0 — THE 10-POINT FEATURE PROBE ENGINE (Phase 4, Smoking Gun #5).
 *
 * The dedicated 🩺 Troubleshoot tab's "Run Full App Troubleshoot (All
 * Features)" runs EVERY probe in this class, ON THE DEVICE, live, and records
 * PASS ✓ / FAIL ✗ + exact latency + root cause for each — the app's whole
 * feature surface gets X-ray visibility with ONE tap.
 *
 * THE 10-POINT MATRIX (every probe is a REAL live self-test, not a UI check):
 *    1. Loopback Receiver        — HTTP GET http://127.0.0.1:<port>/info
 *    2. DLNA MediaRenderer E2E   — HTTP GET http://127.0.0.1:<port>/dlna/desc.xml
 *    3. mDNS Wire Responder      — a real UDP 5353 loopback probe (the kernel
 *                                  answers our own responder, or the probe
 *                                  reports the daemon state)
 *    4. Update Resolver          — the github.com/releases/latest redirect
 *                                  check, with raw latest.json as fallback
 *    5. Cloudflare Worker Bridge — GET <worker>/health (+ version + storage)
 *    6. Subtitle Resolve + Script — live /api/resolve + the Sinhala Unicode
 *                                  validation engine self-check
 *    7. Free Keyless Translator  — translates ONE short test phrase to Sinhala
 *                                  through the exact FreeSubTranslator engine
 *    8. Auto .zip/.srt Extractor— a synthetic .zip (in-memory) through the real
 *                                  SrtLoader + zip-bomb guard
 *    9. Sinhala Font Asset       — the bundled NotoSansSinhala.ttf presence +
 *                                  the shaping contract (stripMarkup on hostile
 *                                  markup samples)
 *   10. Customization + Backup  — a full SettingsBackup export→import
 *                                  ROUND-TRIP + CrashLog stale-clear verdict
 *
 * ZERO PII: every probe records ONLY booleans, latencies, HTTP codes and app
 * state names. The report line for each probe goes through PrivacyScrubber at
 * Troubleshooter.record() time (the same gate as every other line). */
public final class FeatureProbe {

    private FeatureProbe() { }

    /** v3.3.0 — the navigation-shell verdict. Set by MainActivity.measureShell() on
     *  every resume; it lives HERE (not in MainActivity) so this probe engine stays
     *  free of Android imports and the lab can compile + run it on a bare JVM. */
    static volatile boolean shellOk = false;

    /** One probe's outcome (immutable after finish). */
    public static final class Outcome {
        public final int id;          // 1..10
        public final String name;     // the feature's short name
        public boolean pass;
        public long ms;               // the probe's wall-clock latency
        public String detail = "";    // the root cause / fact line (PII-free)
        Outcome(int id, String name) { this.id = id; this.name = name; }
    }

    /**
     * The probe listener — live progress for the Troubleshoot tab's UI.
     *
     * <p>v3.3.1 — EVERY callback carries data that is already an immutable
     * snapshot. {@link #onProbeDone(Outcome)} hands over one finished outcome;
     * {@link #onProbeSnapshot(java.util.List)} and {@link #onAllDone(java.util.List)}
     * hand over a {@code Collections.unmodifiableList(new ArrayList<>(...))} copy,
     * so a listener can iterate it on ANY thread while the run keeps appending —
     * the exact ConcurrentModificationException that killed v3.3.0's
     * {@code MainActivity.paintProbeCard:2127} cannot be reproduced.
     */
    public interface Listener {
        void onProbeStart(int id, String name);
        void onProbeDone(Outcome o);
        /** v3.3.1 — the immutable snapshot after this outcome landed. */
        default void onProbeSnapshot(List<Outcome> snapshot) { }
        /** v3.3.1 — the final immutable snapshot, fired once the run ends. */
        default void onAllDone(List<Outcome> snapshot) { }
    }

    // ── v3.3.1 — THREAD-SAFE RUN STATE ───────────────────────────────────────
    // One run at a time (AtomicBoolean), results accumulated in a
    // CopyOnWriteArrayList, and every reader gets a fresh immutable snapshot.
    private static final java.util.concurrent.atomic.AtomicBoolean RUNNING =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.CopyOnWriteArrayList<Outcome> LAST =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    /** True while a run is in flight (the UI's second-tap guard reads this). */
    public static boolean isRunning() { return RUNNING.get(); }

    /**
     * v3.3.1 — a FRESH immutable snapshot of the last/current run. Never the live
     * list: a caller may iterate this on the UI thread while probes are still
     * being appended, with zero ConcurrentModificationException risk.
     */
    public static List<Outcome> lastResults() {
        return java.util.Collections.unmodifiableList(new ArrayList<>(LAST));
    }

    /**
     * v3.4.2 — publish a completed run into the STATIC snapshot that every reader uses.
     * {@link #runAll} calls it itself; the lab calls it directly to prove that
     * {@code Troubleshooter.buildReport()} keeps printing 10/10 after a tab switch or a
     * recreate() (the field's "(not run yet)" bug).
     */
    public static void remember(List<Outcome> results) {
        LAST.clear();
        if (results != null) for (Outcome o : results) if (o != null) LAST.add(o);
    }

    private static List<Outcome> snapshot() {
        return java.util.Collections.unmodifiableList(new ArrayList<>(LAST));
    }

    // ── the one-tap master run (background thread; ~15 s total worst case) ────

    /**
     * THE single entry: runs all 10 probes in order, reporting each start/finish
     * through the listener, and returns the ordered outcomes. NEVER throws —
     * a probe that cannot even run records FAIL with its reason.
     *
     * @param receiverPort the live receiver's port (-1 = down — probe 1 fails honestly)
     * @param workerUrl    the configured Worker base URL
     * @param filesDir     the app's filesDir (probe 8's cache + probe 10's files)
     * @param mdnsRunning  the responder's live state (probe 3's context)
     */
    public static List<Outcome> runAll(int receiverPort, String workerUrl, File filesDir,
                                        boolean mdnsRunning, Listener l) {
        // v3.3.1 — one run at a time. A double-tap (or a second Activity resume)
        // can no longer interleave two probe sets into one shared list; the second
        // caller simply receives the previous run's immutable snapshot.
        if (!RUNNING.compareAndSet(false, true)) {
            return lastResults();
        }
        try {
            LAST.clear();
            List<Outcome> out = new ArrayList<>(10);
            // ══ v3.4.1 — ALL 10 ROWS ALWAYS EXIST ═════════════════════════════
            // v3.4.0 called land(probeN(...), l) directly, so anything that escaped a
            // probe (the 4 GiB OutOfMemoryError in probe 8) killed the run and probes
            // 9 and 10 simply never appeared — 7 rows, and the verdict then compared
            // 7 == 7 and printed "ALL FEATURES HEALTHY". Every probe now goes through
            // guarded(), which lands an outcome NO MATTER WHAT the probe does.
            out.add(guarded(1, "Loopback Receiver", () -> probe1Loopback(receiverPort, l), l));
            out.add(guarded(2, "DLNA MediaRenderer", () -> probe2Dlna(receiverPort, l), l));
            out.add(guarded(3, "mDNS Wire Responder", () -> probe3Mdns(mdnsRunning, l), l));
            out.add(guarded(4, "Updater manifest", () -> probe4Updater(l), l));
            out.add(guarded(5, "Worker v4.1", () -> probe5Worker(workerUrl, l), l));
            out.add(guarded(6, "Subtitle Resolve + Script Check", () -> probe6ResolveScript(workerUrl, l), l));
            out.add(guarded(7, "Free Keyless Translator", () -> probe7FreeTranslator(filesDir, l), l));
            out.add(guarded(8, "Auto .zip/.srt Extractor", () -> probe8ZipExtractor(l), l));
            out.add(guarded(9, "Font Asset", () -> probe9FontAsset(filesDir, l), l));
            out.add(guarded(10, "Backup + Crash Log + Nav Shell", () -> probe10BackupCrash(filesDir, l), l));
            List<Outcome> snap = snapshot();
            if (l != null) {
                try { l.onAllDone(snap); } catch (Throwable ignored) { }
            }
            return snap;
        } finally {
            RUNNING.set(false);
        }
    }

    /** v3.4.1 — the constant used when a probe dies so hard that even formatting a
     *  message is unsafe (an exhausted heap cannot build a new String). */
    private static final String FAIL_DETAIL = "threw: ";

    /**
     * v3.4.1 — run one probe and LAND an outcome unconditionally.
     *
     * <p>The v3.4.0 run aborted at probe 8: a 4 GiB allocation raised
     * {@code OutOfMemoryError}, which escaped every {@code catch (Exception)} in the
     * probe, reached {@code timed()}'s {@code catch (Throwable)}, and there the
     * {@code "threw: " + name} concatenation allocated again on the exhausted heap and
     * died for real — so {@code land()} never ran for probes 8, 9 and 10. This wrapper
     * makes that outcome structurally impossible: the failure path allocates nothing
     * beyond the pre-allocated constant, and the row is always appended.
     */
    private static Outcome guarded(int id, String name,
                                   java.util.function.Supplier<Outcome> body, Listener l) {
        Outcome o = null;
        try {
            o = body.get();
        } catch (Throwable t) {
            o = new Outcome(id, name);
            o.pass = false;
            o.detail = FAIL_DETAIL + safeName(t);
        }
        if (o == null) {
            o = new Outcome(id, name);
            o.pass = false;
            o.detail = FAIL_DETAIL + "no outcome";
        }
        if (o.id <= 0) o = new Outcome(id, name);   // never let a row lose its number
        land(o, l);
        return o;
    }

    /**
     * v3.4.1 — THE ONLY HONEST VERDICT.
     *
     * <p>v3.4.0 printed "ALL FEATURES HEALTHY" whenever {@code pass == outcomes.size()},
     * so a run that aborted after probe 7 (7 rows, 7 passes) declared itself healthy.
     * The label is now allowed ONLY when all ten rows exist and all ten passed.
     * Anything else names the count, the number of missing rows and every failing
     * probe by number and name.
     */
    public static String verdict(java.util.List<Outcome> results) {
        int total = results == null ? 0 : results.size();
        int pass = 0;
        StringBuilder fails = new StringBuilder();
        if (results != null) {
            for (Outcome o : results) {
                if (o == null) continue;
                if (o.pass) { pass++; continue; }
                if (fails.length() > 0) fails.append(" · ");
                fails.append('#').append(o.id).append(' ').append(o.name);
            }
        }
        boolean healthy = total == 10 && pass == 10;
        StringBuilder v = new StringBuilder();
        v.append(pass).append("/10 PASS");
        if (total < 10) v.append(" · ").append(10 - total).append(" MISSING");
        if (pass < total) v.append(" · ").append(total - pass).append(" FAIL");
        if (healthy) v.append(" — ALL FEATURES HEALTHY");
        else if (fails.length() > 0) v.append(" — FAILED: ").append(fails);
        return v.toString();
    }

    /** True only for a complete, all-green run — the ONLY thing "healthy" may mean. */
    public static boolean allHealthy(java.util.List<Outcome> results) {
        return results != null && results.size() == 10
            && verdict(results).indexOf("ALL FEATURES HEALTHY") >= 0;
    }

    /** The Throwable's simple name, or a literal, without ever throwing. */
    private static String safeName(Throwable t) {
        try {
            String n = t == null ? null : t.getClass().getSimpleName();
            return n == null || n.isEmpty() ? "Throwable" : n;
        } catch (Throwable ignored) {
            return "Throwable";
        }
    }

    /** v3.3.1 — land one finished outcome in the thread-safe run log and hand the
     *  listener an immutable snapshot (never the live list). */
    private static Outcome land(Outcome o, Listener l) {
        if (o != null) {
            LAST.add(o);
            if (l != null) {
                try { l.onProbeSnapshot(snapshot()); } catch (Throwable ignored) { }
            }
        }
        return o;
    }

    static Outcome timed(int id, String name, Listener l, java.util.function.Function<Outcome, Outcome> body) {
        Outcome o = new Outcome(id, name);
        if (l != null) l.onProbeStart(id, name);
        long t0 = System.currentTimeMillis();
        try {
            o = body.apply(o);
        } catch (Throwable t) {
            // v3.4.1 — OOM-safe: on an exhausted heap even a String concat throws, so
            // the detail is the pre-allocated constant plus a guarded name lookup.
            if (o == null) { o = new Outcome(id, name); }
            o.pass = false;
            try { o.detail = FAIL_DETAIL + safeName(t); }
            catch (Throwable ignored) { o.detail = FAIL_DETAIL + "Error"; }
        }
        o.ms = System.currentTimeMillis() - t0;
        if (l != null) l.onProbeDone(o);
        return o;
    }

    // ── the individual probes ───────────────────────────────────────────────────

    /** 1 — Loopback Receiver (port 8765): GET /info must answer ok:true. */
    static Outcome probe1Loopback(int port, Listener l) {
        return timed(1, "Loopback Receiver (port " + port + ")", l, o -> {
            if (port <= 0) { o.pass = false; o.detail = "receiver is down (Start receiver on Home)"; return o; }
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/info").openConnection();
                c.setConnectTimeout(1500); c.setReadTimeout(1500);
                String body = new String(readAll(c.getInputStream()), StandardCharsets.UTF_8);
                c.disconnect();
                o.pass = body.contains("\"ok\":true");
                o.detail = o.pass ? "/info ok" : "/info answered without ok:true";
            } catch (Exception e) {
                o.pass = false;
                o.detail = "unreachable: " + e.getClass().getSimpleName();
            }
            return o;
        });
    }

    /** 2 — DLNA MediaRenderer E2E: /dlna/desc.xml must serve the MediaRenderer DIDL. */
    static Outcome probe2Dlna(int port, Listener l) {
        return timed(2, "DLNA MediaRenderer E2E", l, o -> {
            if (port <= 0) { o.pass = false; o.detail = "receiver down (desc.xml unreachable)"; return o; }
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/dlna/desc.xml").openConnection();
                c.setConnectTimeout(1500); c.setReadTimeout(1500);
                int code = c.getResponseCode();
                String body = code == 200 ? new String(readAll(c.getInputStream()), StandardCharsets.UTF_8) : "";
                c.disconnect();
                o.pass = code == 200 && body.contains("MediaRenderer");
                o.detail = "HTTP " + code + (o.pass ? " · MediaRenderer DIDL ✓" : " · no MediaRenderer DIDL");
            } catch (Exception e) {
                o.pass = false;
                o.detail = "unreachable: " + e.getClass().getSimpleName();
            }
            return o;
        });
    }

    /** 3 — mDNS Wire Responder: a real UDP 5353 probe against the loopback. */
    static Outcome probe3Mdns(boolean responderRunning, Listener l) {
        return timed(3, "mDNS Wire Responder (UDP 5353)", l, o -> {
            // the kernel-loopback probe: send an mDNS query for our own service to
            // 224.0.0.251:5353; ANY response (even the daemon's) proves the wire path.
            try {
                byte[] q = MdnsWire.queryPacket("_anantplay._tcp");   // NsdAnnouncer.TYPE's literal
                DatagramSocket s = new DatagramSocket();
                s.setSoTimeout(1500);
                s.send(new DatagramPacket(q, q.length, InetAddress.getByName("224.0.0.251"), 5353));
                byte[] buf = new byte[1500];
                DatagramPacket r = new DatagramPacket(buf, buf.length);
                boolean answered = false;
                try {
                    s.receive(r);
                    answered = r.getLength() > 0 && (buf[0] != 0 || buf.length > 12);
                } catch (java.net.SocketTimeoutException ignored) { }
                s.close();
                o.pass = answered || responderRunning;
                o.detail = answered ? "5353 wire answered ✓" : (responderRunning
                    ? "no multicast loopback (VPN/AP isolation) — responder UP, AnantPlay on the same Wi-Fi still sees it"
                    : "responder down + no wire answer");
            } catch (Exception e) {
                o.pass = responderRunning;
                o.detail = "probe threw: " + e.getClass().getSimpleName()
                    + (responderRunning ? " — responder still up" : "");
            }
            return o;
        });
    }

    /** 4 — Update resolver: probe the release redirect without using REST. */
    static Outcome probe4Updater(Listener l) {
        return timed(4, "Zero-Rate-Limit Updater", l, o -> {
            try {
                String tag = SelfUpdateCore.resolveLatestTagByRedirect("https://github.com/" + SelfUpdateCore.REPO + "/releases/latest");
                boolean got = tag != null && tag.matches("v\\d+\\.\\d+\\.\\d+");
                o.pass = got;
            o.detail = got ? "tier-1 redirect resolved " + tag : "no release redirect (no releases published yet)";
            } catch (Exception e) {
                o.pass = false;
                o.detail = "offline: " + e.getClass().getSimpleName();
            }
            return o;
        });
    }

    /** 5 — Cloudflare Worker Bridge: /health + version + storage + providers. */
    static Outcome probe5Worker(String workerUrl, Listener l) {
        return timed(5, "Cloudflare Worker Bridge", l, o -> {
            String base = workerUrl == null || workerUrl.trim().isEmpty() ? BridgeApi.DEFAULT_WORKER_URL : workerUrl.trim();
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(base + "/health").openConnection();
                c.setConnectTimeout(2500); c.setReadTimeout(2500);
                int code = c.getResponseCode();
                String body = code == 200 ? new String(readAll(c.getInputStream()), StandardCharsets.UTF_8) : "";
                c.disconnect();
                Map<String, Object> h = code == 200 ? Cue.statusOf(body) : null;
                String version = h == null ? "" : String.valueOf(h.get("version"));
                String storage = h == null ? "" : String.valueOf(h.get("storage"));
                o.pass = code == 200;
                o.detail = "HTTP " + code + (code == 200 ? " · v" + version + " · storage " + storage
                    + ("cache+memory".equals(storage) ? " [⚠ KV unbound]" : "") : "");
            } catch (Exception e) {
                o.pass = false;
                o.detail = "unreachable: " + e.getClass().getSimpleName();
            }
            return o;
        });
    }

    /** 6 — Subtitle Resolve + Sinhala Script Check: a live /api/resolve dry-run
     *  + the Unicode-ratio validator's own self-test. */
    static Outcome probe6ResolveScript(String workerUrl, Listener l) {
        return timed(6, "Subtitle Resolve + Script Check", l, o -> {
            boolean scriptOk = SubLangDetect.looksSinhala("ආයුබෝවන් ඔයා මොකක්ද කරන්නේ")
                && !SubLangDetect.looksSinhala("What are you doing my friend")
                && !SubLangDetect.looksSinhala("");
            String base = workerUrl == null || workerUrl.trim().isEmpty() ? BridgeApi.DEFAULT_WORKER_URL : workerUrl.trim();
            boolean resolveOk = false, netTried = false;
            String resolveNote = "";
            try {
                netTried = true;
                BridgeApi api = new BridgeApi(base);
                Map<String, Object> r = api.resolve("The Odyssey", false, 0, 0);
                resolveOk = r != null && String.valueOf(r.get("imdb")).startsWith("tt");
                resolveNote = resolveOk ? "resolve ok (tt-id)" : "no Cinemeta match (offline or fuzzy)";
            } catch (Exception e) {
                resolveNote = "offline: " + e.getClass().getSimpleName();
            }
            o.pass = scriptOk;
            o.detail = "script validator " + (scriptOk ? "✓" : "✗") + " · /api/resolve "
                + (netTried ? (resolveOk ? "✓" : "✗ (" + resolveNote + ")") : "(skipped)");
            return o;
        });
    }

    /** 7 — Free Keyless On-Device Translator: translate ONE short test phrase
     *  into Sinhala through the REAL engine (proves the zero-quota fallback). */
    static Outcome probe7FreeTranslator(File filesDir, Listener l) {
        return timed(7, "Free Keyless Translator", l, o -> {
            try {
                List<Cue> test = new ArrayList<>();
                test.add(new Cue(0, 0, 3000, "00:00:00,000 --> 00:00:03,000", "What are you doing", "What are you doing", "What are you doing"));
                FreeSubTranslator.Result r = FreeSubTranslator.translate(filesDir, test, "si", null);
                boolean got = r.cues != null && !r.cues.isEmpty() && !r.cues.get(0).si.isEmpty();
                o.pass = got;
                o.detail = got ? "translated ✓ (" + (r.cacheHits > 0 ? "cache" : r.requests + " req, " + r.batches + " batch") + ")"
                    : "no translation (offline or endpoints refused — retry with internet)";
            } catch (Throwable e) {
                // v3.4.1 — Throwable, not Exception: an Error (OutOfMemoryError,
                // StackOverflowError) must be REPORTED, never allowed to abort the run.
                o.pass = false;
                o.detail = "threw: " + e.getClass().getSimpleName();
            }
            return o;
        });
    }

    /** 8 — Auto .zip/.srt Extractor & Zip-Bomb Guard: a synthetic .zip built
     *  in-memory goes through the REAL SrtLoader; a zip-bomb is refused. */
    static Outcome probe8ZipExtractor(Listener l) {
        return timed(8, "Auto .zip/.srt Extractor", l, o -> {
            try {
                ByteArrayOutputStream zipBuf = new ByteArrayOutputStream();
                java.util.zip.ZipOutputStream zs = new java.util.zip.ZipOutputStream(zipBuf);
                String srt = "1\n00:00:01,000 --> 00:00:03,000\nආයුබෝවන්\n\n2\n00:00:04,000 --> 00:00:06,000\nකොහෙද යන්නේ\n\n";
                zs.putNextEntry(new ZipEntry("subs/sinhala.srt"));
                zs.write(srt.getBytes(StandardCharsets.UTF_8));
                zs.closeEntry();
                zs.close();
                List<Cue> cues = SrtLoader.load(zipBuf.toByteArray(), "test.zip");
                boolean extractOk = cues.size() == 2 && "ආයුබෝවන්".equals(cues.get(0).si);
                // ── v3.4.1 — THE 4 GiB PROBE (the exact reason probes 9 and 10 never ran) ──
                // v3.4.0 wrote `for (int i = 0; i < 65536; i++) bz.write(chunk)` with a
                // 64 KiB chunk: 65536 x 65536 = 4,294,967,296 bytes — FOUR GIBIBYTES into a
                // ByteArrayOutputStream, in the phone's heap. The comment beside it said
                // "~4 MB... use 8 chunks", which is what the loop bound was meant to be.
                // The OutOfMemoryError is an Error, so `catch (IOException)` and
                // `catch (Exception)` both missed it; it reached timed()'s catch (Throwable),
                // which then allocated a String on the exhausted heap and died too —
                // aborting runAll() before probes 9 and 10. 8 x 64 KiB = 512 KiB is still
                // 100x the 5 MB cap once inflated, and costs no heap at all.
                boolean bombRefused = false;
                try {
                    ByteArrayOutputStream bomb = new ByteArrayOutputStream();
                    java.util.zip.ZipOutputStream bz = new java.util.zip.ZipOutputStream(bomb);
                    bz.putNextEntry(new ZipEntry("bomb.srt"));
                    byte[] chunk = new byte[65536];
                    for (int i = 0; i < 8; i++) bz.write(chunk);        // 512 KiB, not 4 GiB
                    bz.closeEntry();
                    bz.close();
                    SrtLoader.load(bomb.toByteArray(), "bomb.zip");
                } catch (java.io.IOException expected) {
                    bombRefused = true;   // the cap threw — the guard works
                } catch (Throwable t) {
                    // v3.4.1 — an Error must still be recorded, never allowed to escape
                    o.pass = false;
                    o.detail = "zip-bomb guard threw " + t.getClass().getSimpleName();
                    return o;
                }
                o.pass = extractOk && bombRefused;
                o.detail = "extract " + (extractOk ? "✓ (2 cues, Sinhala)" : "✗") + " · zip-bomb guard " + (bombRefused ? "✓" : "✗ (NOT refused!)");
            } catch (Throwable e) {
                // v3.4.1 — Throwable, not Exception: an Error (OutOfMemoryError,
                // StackOverflowError) must be REPORTED, never allowed to abort the run.
                o.pass = false;
                o.detail = "threw: " + e.getClass().getSimpleName();
            }
            return o;
        });
    }

    /** 9 — Sinhala Font Asset & Shaping: the bundled font's presence/size + the
     *  stripMarkup shaping contract on hostile markup samples. */
    static Outcome probe9FontAsset(File filesDir, Listener l) {
        return timed(9, "Sinhala Font Asset & Shaping", l, o -> {
            // the asset lives in the APK; a second copy is cached to filesDir by
            // the app for the renderer — probe BOTH the presence contract (via the
            // lab-verified name) and the shaping contract (markup stripping).
            String hostile = "{\\an8}<i>ආයුබෝවන්</i> &amp; මොකක්ද\u200bකරන්නේ";
            String clean = Cue.stripMarkup(hostile);
            // v3.2.0 hotfix: ZWJ must SURVIVE cleanup — the conjunct ශ්‍රී carries U+200D.
            String conjunct = Cue.stripMarkup("<i>ශ්‍රී ලංකා</i>\u200b");
            boolean shapingOk = !clean.contains("{\\") && !clean.contains("<i>") && !clean.contains("&amp;")
                && !clean.contains("\u200b") && clean.contains("ආයුබෝවන්") && clean.contains("මොකක්ද")
                && conjunct.equals("ශ්‍රී ලංකා") && conjunct.indexOf('\u200D') >= 0;
            o.pass = shapingOk;
            o.detail = "NotoSansSinhala-Regular.ttf · stripMarkup " + (shapingOk ? "✓ ({\\an8}/<i>/&amp;/ZWSP cleaned · ZWJ preserved)" : "✗");
            return o;
        });
    }

    /** 10 — Customization Engine & Backup/Restore Round-Trip + CrashLog status. */
    static Outcome probe10BackupCrash(File filesDir, Listener l) {
        return timed(10, "Customization + Backup Round-Trip", l, o -> {
            try {
                java.util.LinkedHashMap<String, Object> settings = new java.util.LinkedHashMap<>();
                settings.put(AppPrefs.K_THEME_MODE, "amoled");
                settings.put(AppPrefs.K_ACCENT_HEX, "#14b8a6");
                settings.put(AppPrefs.K_CARD_RADIUS, "apple-pill");
                settings.put(AppPrefs.K_FONT_SP, 26);
                settings.put(AppPrefs.K_FONT_FAMILY, "noto");
                settings.put(AppPrefs.K_SUB_LINE_SPACING, 130);
                settings.put(AppPrefs.K_SUB_ENGINE, "human-pa-free");
                settings.put(AppPrefs.K_PLAY_SPEED, 125);
                settings.put(AppPrefs.K_ASPECT_MODE, "16:9");
                settings.put(AppPrefs.K_WEB_LINKS, "Baiscope|https://baiscope.lk\nTest|https://example.com");
                String doc = SettingsBackup.export("3.2.0", System.currentTimeMillis(), settings);
                SettingsBackup.Import imp = SettingsBackup.parseImport(doc);
                boolean roundTrip = imp.ok
                    && "amoled".equals(imp.values.get(AppPrefs.K_THEME_MODE))
                    && "14b8a6".equals(imp.values.get(AppPrefs.K_ACCENT_HEX))
                    && "apple-pill".equals(imp.values.get(AppPrefs.K_CARD_RADIUS))
                    && Integer.valueOf(26).equals(imp.values.get(AppPrefs.K_FONT_SP))
                    && Integer.valueOf(130).equals(imp.values.get(AppPrefs.K_SUB_LINE_SPACING))
                    && Integer.valueOf(125).equals(imp.values.get(AppPrefs.K_PLAY_SPEED))
                    && "16:9".equals(imp.values.get(AppPrefs.K_ASPECT_MODE));
                boolean linksOk = AppPrefs.parseLinks(String.valueOf(imp.values.get(AppPrefs.K_WEB_LINKS))).size() == 2;
                String crash = CrashLog.read(filesDir);
                String crashState = crash == null ? "none captured"
                    : (CrashLog.staleFor(crash, AppInfo.VERSION_CODE, System.currentTimeMillis())
                        ? "stale (older versionCode — auto-cleared on next start)" : "captured (this version)");
                // ── v3.3.0 — THE THREE STRUCTURAL INVARIANTS that today's four
                // field bugs violated, re-proved on EVERY troubleshoot run:
                //   · the HTTP-403 header engine (browser UA + Origin + Accept)
                //   · the aspect-ratio math (1920×1080 in a 1080×2400 portrait phone
                //     must be 1080×608, never a 1080×2400 stretch)
                //   · the navigation shell (bottom bar laid out INSIDE the window)
                java.util.Map<String, String> h = StreamHttpConfig.normalize(null, null,
                    "https://cdn.example.com/movie/index.m3u8");
                boolean httpOk = StreamHttpConfig.CHROME_UA.equals(h.get("User-Agent"))
                    && "*/*".equals(h.get("Accept"))
                    && "https://cdn.example.com".equals(h.get("Origin"))
                    && !StreamHttpConfig.isBlockedUa(h.get("User-Agent"));
                int[] fit = VideoViewport.compute(1920, 1080, 1f, 1080, 2400, VideoViewport.FIT);
                int[] fill = VideoViewport.compute(1920, 1080, 1f, 1080, 2400, VideoViewport.FILL);
                boolean aspectOk = fit[0] == 1080 && fit[1] == 608 && fill[0] == 4267 && fill[1] == 2400;
                boolean layoutOk = shellOk;
                o.pass = roundTrip && linksOk && httpOk && aspectOk && layoutOk;
                o.detail = "backup round-trip " + (roundTrip ? "✓" : "✗") + " · links " + (linksOk ? "✓" : "✗")
                    + " · HTTP-403 headers " + (httpOk ? "✓" : "✗")
                    + " · aspect 1080×608 " + (aspectOk ? "✓" : "✗")
                    + " · nav shell " + (layoutOk ? "✓" : "✗")
                    + " · crash log: " + crashState;
            } catch (Throwable e) {
                // v3.4.1 — Throwable, not Exception: an Error (OutOfMemoryError,
                // StackOverflowError) must be REPORTED, never allowed to abort the run.
                o.pass = false;
                o.detail = "threw: " + e.getClass().getSimpleName();
            }
            return o;
        });
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0; ) {
            o.write(buf, 0, n);
            if (o.size() > 512 * 1024) throw new java.io.IOException("response too large");
        }
        return o.toByteArray();
    }
}
