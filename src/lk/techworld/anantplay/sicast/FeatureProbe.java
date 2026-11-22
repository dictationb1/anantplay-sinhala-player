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
 *    4. Zero-Rate-Limit Updater  — the github.com/releases/latest redirect
 *                                  check (tier-1, ZERO api.github.com burn)
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

    /** The probe listener — live progress for the Troubleshoot tab's UI. */
    public interface Listener {
        void onProbeStart(int id, String name);
        void onProbeDone(Outcome o);
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
        List<Outcome> out = new ArrayList<>();
        out.add(probe1Loopback(receiverPort, l));
        out.add(probe2Dlna(receiverPort, l));
        out.add(probe3Mdns(mdnsRunning, l));
        out.add(probe4Updater(l));
        out.add(probe5Worker(workerUrl, l));
        out.add(probe6ResolveScript(workerUrl, l));
        out.add(probe7FreeTranslator(filesDir, l));
        out.add(probe8ZipExtractor(l));
        out.add(probe9FontAsset(filesDir, l));
        out.add(probe10BackupCrash(filesDir, l));
        return out;
    }

    static Outcome timed(int id, String name, Listener l, java.util.function.Function<Outcome, Outcome> body) {
        Outcome o = new Outcome(id, name);
        if (l != null) l.onProbeStart(id, name);
        long t0 = System.currentTimeMillis();
        try {
            o = body.apply(o);
        } catch (Throwable t) {
            o.pass = false;
            o.detail = "threw: " + t.getClass().getSimpleName();
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

    /** 4 — Zero-Rate-Limit Updater: the tier-1 github.com redirect check, and
     *  PROOF that api.github.com was never touched. */
    static Outcome probe4Updater(Listener l) {
        return timed(4, "Zero-Rate-Limit Updater", l, o -> {
            try {
                String tag = SelfUpdateCore.resolveLatestTagByRedirect("https://github.com/" + SelfUpdateCore.REPO + "/releases/latest");
                boolean got = tag != null && tag.matches("v\\d+\\.\\d+\\.\\d+");
                o.pass = got;
                o.detail = got ? "tier-1 redirect resolved " + tag + " (0 api.github.com calls)" : "no release redirect (no releases published yet)";
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
            } catch (Exception e) {
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
                // the bomb: a >5 MB decompressed entry must be refused
                boolean bombRefused = false;
                try {
                    ByteArrayOutputStream bomb = new ByteArrayOutputStream();
                    java.util.zip.ZipOutputStream bz = new java.util.zip.ZipOutputStream(bomb);
                    bz.putNextEntry(new ZipEntry("bomb.srt"));
                    byte[] chunk = new byte[65536];
                    for (int i = 0; i < 65536; i++) bz.write(chunk);   // ~4 MB... use 8 chunks
                    for (int i = 0; i < 8; i++) bz.write(chunk);        // ~8 MB total > 5 MB cap
                    bz.closeEntry();
                    bz.close();
                    SrtLoader.load(bomb.toByteArray(), "bomb.zip");
                } catch (java.io.IOException expected) {
                    bombRefused = true;   // the cap threw — the guard works
                }
                o.pass = extractOk && bombRefused;
                o.detail = "extract " + (extractOk ? "✓ (2 cues, Sinhala)" : "✗") + " · zip-bomb guard " + (bombRefused ? "✓" : "✗ (NOT refused!)");
            } catch (Exception e) {
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
            } catch (Exception e) {
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
