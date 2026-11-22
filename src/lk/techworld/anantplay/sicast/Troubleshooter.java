package lk.techworld.anantplay.sicast;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/* v3.1.2 — THE BUILT-IN DEEP TROUBLESHOOTER (1-tap copyable, privacy-first).
 *
 * Runs INSIDE the app, catches every runtime fault from launch onward, tracks
 * the complete lifecycle timeline in structured categories, and produces a
 * prioritized bilingual issue list with exact 1-tap fixes. Everything it
 * records is anonymized app-internal + AnantPlay cast/subtitle technical
 * state — PrivacyScrubber guards every line, so ZERO phone details / PII can
 * ever leave the device through the Copy/Share buttons.
 *
 * THE TIMELINE (one structured event list, newest last, capped at 400):
 *   [APP]     startup, service arming, permissions state (boolean only)
 *   [CAST]    mDNS responder, NSD daemon, DLNA SSDP searches/replies,
 *             incoming /play + /dlna/* HTTP outcomes (accept/refuse + why)
 *   [PLAYER]  PlayActivity launch source (cast-mdns|cast-dlna|test-player|
 *             manual-url|webview|share), Media3 state + exact ExoPlayer
 *             error-code name + the DoH verdict
 *   [SUBS]    TitleCleaner output, Worker /api/resolve + /subtitles HTTP
 *             statuses, provider used, polling progress, X-Sub-Quality /
 *             X-Polish-Status, loaded cue count, render status
 *   [UPDATER] the single check result (zero spam by construction)
 *   [CRASH]   the TOP-first privacy-scrubbed last-crash trace
 *
 * THE AUTO DIAGNOSIS (Troubleshooter.diagnose(...)): a pure rule engine that
 * maps captured telemetry + live subsystem facts into ordered, bilingual,
 * immediately-actionable cards — the same rules run in the lab against
 * synthetic fault states, so the shipped verdicts are proven, not guessed.
 *
 * PURE JAVA (File-less): the Android side feeds it Diag's ring, CrashLog's
 * report and the live subsystem booleans; the lab drives the same engine on
 * synthetic states. */
public final class Troubleshooter {

    private Troubleshooter() { }

    public static final int MAX_EVENTS = 400;

    /** One timeline event (immutable; text is already privacy-scrubbed). */
    public static final class Event {
        public final long atMs;        // process-relative ms (Diag's clock)
        public final String category;  // APP|CAST|PLAYER|SUBS|UPDATER|CRASH
        public final String text;

        Event(long at, String cat, String t) {
            this.atMs = at;
            this.category = cat;
            this.text = t == null ? "" : t;
        }
    }

    /** The live subsystem facts the diagnose() rule engine reads (all boolean /
     *  counters — nothing identifying; the Android side fills it from the
     *  running ReceiverService / PlayActivity / prefs, the lab from synthetic
     *  fault states). */
    public static final class Facts {
        public boolean receiverUp;
        public int receiverPort = -1;
        public boolean nsdAnnounced;
        public boolean mdnsResponderUp;
        public boolean dlnaUp;
        public boolean dlnaEnabled = true;
        public boolean mdnsEnabled = true;
        public boolean workerConfigured = true;
        public boolean workerHealthy = true;      // last /health probe result
        public String workerVersion = "";          // e.g. "4.1.0"
        public String workerStorage = "";          // e.g. "kv" / "cache+memory"
        public boolean notifGranted = true;
        public boolean overlayGranted;
        public boolean playerOpen;
        public String playerState = "idle";        // idle|buffering|ready|error
        public String playerError = "";            // ExoPlayer error-code name
        public boolean subsLoaded;
        public int cueCount;
        public String subsState = "idle";          // idle|fetching|ready|local|not-found|needs-key|error
        public String subsDetail = "";
        public boolean updaterRan;
        public String updaterResult = "";
        public boolean hasCrashLog;
        public String crashTop = "";               // the TOP-first summary line

        /** The default all-healthy baseline the lab mutates per scenario. */
        public static Facts healthy() {
            Facts f = new Facts();
            f.receiverUp = true;
            f.receiverPort = 8765;
            f.nsdAnnounced = true;
            f.mdnsResponderUp = true;
            f.dlnaUp = true;
            f.workerHealthy = true;
            f.playerOpen = true;
            f.playerState = "ready";
            f.subsLoaded = true;
            f.cueCount = 812;
            f.subsState = "ready";
            f.subsDetail = "812 cues · human Sinhala";
            f.updaterRan = true;
            f.updaterResult = "up-to-date";
            return f;
        }
    }

    /** One diagnosed issue card: severity-ordered, bilingual, with the exact fix. */
    public static final class Issue {
        public final char sev;        // 'X' error · 'W' warning · 'O' ok
        public final String si;       // Sinhala line
        public final String en;       // English line
        public final String fix;       // the exact 1-tap action ("" when none)

        Issue(char s, String si, String en, String fix) {
            this.sev = s; this.si = si; this.en = en; this.fix = fix;
        }
    }

    // ── the timeline (static ring, Diag-clock timestamps) ─────────────────────

    private static final Object lock = new Object();
    private static final Deque<Event> events = new ArrayDeque<>();
    private static long t0 = System.currentTimeMillis();

    /** Record one structured event (privacy-scrubbed BEFORE storing). */
    public static void record(String category, String text) {
        String cat = normCategory(category);
        String clean = PrivacyScrubber.scrub(text == null ? "" : text);
        synchronized (lock) {
            if (events.size() >= MAX_EVENTS) events.removeFirst();
            events.addLast(new Event(System.currentTimeMillis() - t0, cat, clean));
        }
    }

    static void reset() { synchronized (lock) { events.clear(); } }
    public static int size() { synchronized (lock) { return events.size(); } }

    /** The timeline as text lines ("  [CAST] 12.310 text"). */
    public static List<String> timelineLines() {
        List<String> out = new ArrayList<>();
        synchronized (lock) {
            for (Event e : events) {
                out.add(String.format(java.util.Locale.US, "  [%s] %d.%03d %s",
                    e.category, e.atMs / 1000, e.atMs % 1000, e.text));
            }
        }
        return out;
    }

    static String normCategory(String cat) {
        if (cat == null) return "APP";
        String c = cat.trim().toUpperCase(java.util.Locale.ROOT);
        if ("CAST".equals(c) || "PLAYER".equals(c) || "SUBS".equals(c)
            || "UPDATER".equals(c) || "CRASH".equals(c) || "APP".equals(c)) return c;
        return "APP";
    }

    // ── the TOP-FIRST crash formatter (Bug: bottom-only frames were useless) ──

    /**
     * The TOP of a stored CrashLog report, TOP-FIRST by contract:
     *   line 1 = "EXCEPTION <class>: <message>" (or "CAUSED BY …" for the chain),
     *   followed by the FIRST app stack frames ("    at …") in file order —
     *   never the bottom Handler.dispatchMessage tail the v3.1.1 card showed.
     * Returns at most maxLines lines (14 default); every line privacy-scrubbed.
     * Already-scrubbed input (CrashLog's own pass) stays intact.
     */
    public static List<String> crashTop(String report, int maxLines) {
        List<String> trace = new ArrayList<>();
        List<String> head = new ArrayList<>();
        if (report == null || report.isEmpty()) return trace;
        int cap = maxLines <= 0 ? 14 : maxLines;
        boolean traceStarted = false;
        for (String raw : report.split("\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            boolean isExc = line.startsWith("EXCEPTION ") || line.startsWith("CAUSED BY ");
            // a real Java frame always carries "(File.java:NN)" — the header's
            // " at 2026-11-22 19:16:11.100" timestamp line must NOT count as one
            boolean isFrame = line.startsWith("at ") && line.indexOf('(') > 0
                && line.indexOf(')', line.indexOf('(')) > 0;
            if (isExc || isFrame) {
                traceStarted = true;
                String sc = PrivacyScrubber.scrub(line);
                if (trace.size() < cap && !trace.contains(sc)) trace.add(sc);
                if (trace.size() >= cap) break;
                continue;
            }
            // header facts (thread/sdk) collected only until the trace starts
            if (!traceStarted && (line.startsWith("thread:") || line.startsWith("device:")
                || line.startsWith("sdk "))) {
                String sc = PrivacyScrubber.scrub(line);
                if (!head.contains(sc)) head.add(sc);
            }
            // the framing rules / title / " at <timestamp>" lines are skipped:
            // the timestamp would be mis-typed as IPv6 by the scrubber anyway
        }
        List<String> result = new ArrayList<>(trace);
        for (String h : head) result.add(h);   // compact header AFTER the trace
        return result.size() > cap
            ? new ArrayList<>(result.subList(0, cap))
            : result;
    }

    // ── the automated rule-based fault detector ───────────────────────────────

    /**
     * THE diagnosis: every rule is severity-ordered so the card list reads
     * "worst first". Pure function of (Facts, timeline) — lab-proven.
     */
    public static List<Issue> diagnose(Facts f, List<Event> timeline) {
        List<Issue> out = new ArrayList<>();

        // 0. a captured crash always leads
        if (f.hasCrashLog && f.crashTop != null && !f.crashTop.isEmpty()) {
            out.add(new Issue('X',
                "PlayActivity crash එකක් සළකුණු වී ඇත — " + f.crashTop,
                "a crash was captured — " + f.crashTop,
                "Diagnostics → 📋 Copy Full Troubleshoot Report → 1-tap Share"));
        }
        // 1. player error
        if ("error".equals(f.playerState) || !f.playerError.isEmpty()) {
            out.add(new Issue('X',
                "ධාවනය අසාර්ථක (" + (f.playerError.isEmpty() ? "?" : f.playerError) + ")",
                "playback failed (" + (f.playerError.isEmpty() ? "?" : f.playerError) + ")",
                "error box එක ඔබන්න — අවසාන ස්ථානයෙන් නැවත · tap the error box to retry"));
        }
        // 2. receiver down
        if (!f.receiverUp) {
            out.add(new Issue('X',
                "රිසීවරය ක්‍රියාත්මක නැත (port " + (f.receiverPort > 0 ? f.receiverPort : "off") + ")",
                "receiver is down (port " + (f.receiverPort > 0 ? f.receiverPort : "off") + ")",
                "Home → ▶ Start receiver"));
        }
        // 3. cast pillars
        if (f.receiverUp && !f.mdnsResponderUp && !f.dlnaUp && !f.nsdAnnounced) {
            out.add(new Issue('X',
                "Cast සොයාගැනීම අක්‍රීයයි — mDNS + DLNA + NSD තුනම නිහඬයි",
                "cast discovery is fully down (mDNS + DLNA + NSD all off)",
                "Wi-Fi ON ද? Settings → Cast & Network → දෙපැත්තම ON · turn both pillars on"));
        } else if (f.receiverUp && !f.mdnsResponderUp && !f.dlnaUp) {
            out.add(new Issue('W',
                "cast පැති දෙකම නිහඬයි — AnantPlay අපව නොදකියි",
                "both cast pillars are silent — AnantPlay cannot see this receiver",
                "Self-Test ධාවනය කරන්න · run the Self-Test for the exact layer that died"));
        }
        // 4. NSD daemon pending (the Android 16 no-callback hang)
        if (f.receiverUp && f.mdnsEnabled && !f.nsdAnnounced && f.mdnsResponderUp) {
            out.add(new Issue('O',
                "NSD daemon නිහඬයි — නමුත් අපේ 5353 responder + DLNA දෙකම සජීවීයි (cast ක්‍රියාත්මකයි)",
                "NSD daemon silent — but our own 5353 responder + DLNA are alive (casting works)",
                ""));
        }
        // 5. worker
        if (!f.workerConfigured) {
            out.add(new Issue('X',
                "Worker URL වින්‍යාස කර නැත",
                "the subtitle Worker URL is not configured",
                "Settings → Cast & Network → Worker URL"));
        } else if (!f.workerHealthy) {
            out.add(new Issue('X',
                "Worker සාමාන්‍ය පිළිතුරු දෙන්නේ නැත",
                "the subtitle Worker is not answering",
                "Cast & Network → Worker ping; internet/WARP පරීක්ෂා කරන්න · check connectivity"));
        } else if ("cache+memory".equals(f.workerStorage)) {
            out.add(new Issue('W',
                "Worker KV බඳින ලද්දක් නැත — cache එක පමණයි",
                "Worker KV is not bound — cache-only storage",
                "Cloudflare dashboard → the Worker → SUBS_KV bind කරන්න · bind SUBS_KV"));
        }
        // 6. subtitles
        if ("needs-key".equals(f.subsState)) {
            out.add(new Issue('X',
                "සිංහල උපසිරැසි නැත — Worker එකේ provider key එකක්වත් set කර නැත",
                "no subtitles — no translation provider key is configured on the Worker",
                "Worker secrets: AZURE_TRANSLATOR_KEY / GOOGLE_V3_KEY / GEMINI_API_KEY"));
        } else if ("not-found".equals(f.subsState)) {
            out.add(new Issue('W',
                "මෙම තේමාවට උපසිරැසි හමු නොවීය",
                "no subtitles were found for this title",
                "සිං බොත්තම → title override · ⏱ or the local .srt loader"));
        } else if ("error".equals(f.subsState)) {
            out.add(new Issue('X',
                "උපසිරැසි ලබාගැනීම බිඳ වැටුණි" + (f.subsDetail.isEmpty() ? "" : " — " + f.subsDetail),
                "subtitle fetch failed" + (f.subsDetail.isEmpty() ? "" : " — " + f.subsDetail),
                "↻ Retry · ⇄ Provider · 📄 Local .srt"));
        } else if (f.subsLoaded && f.cueCount > 0) {
            out.add(new Issue('O',
                "සිංහල උපසිරැසි " + f.cueCount + " cues සමඟ සූදානම්",
                "Sinhala subtitles ready (" + f.cueCount + " cues)",
                ""));
        }
        // 7. the all-healthy verdict (only when nothing worse exists)
        if (out.isEmpty()) {
            out.add(new Issue('O',
                "✓ Cast + උපසිරැසි දෙකම සෞඛ්‍යසම්පූර්ණයි",
                "✓ cast + subtitles are healthy",
                ""));
        }
        return out;
    }

    // ── the 1-tap report builder (the Copy/Share payload) ──────────────────────

    /**
     * The complete privacy-safe Markdown bundle: Status Summary + Auto-Detected
     * Issues + Top-First Last Crash + the subsystem timeline. Every line is
     * scrubbed again at the end (the final gate).
     */
    public static String buildReport(Facts f, String diagLogDump, String crashReport) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("# AnantPlay Sinhala Player — Troubleshoot Report\n");
        sb.append("app v").append(AppInfo.VERSION_NAME).append(" · Android SDK ")
          .append(androidSdkOnly()).append(" · privacy-safe (zero phone details / PII)\n\n");

        // 1. status summary
        sb.append("## Status Summary\n");
        sb.append("- receiver: ").append(f.receiverUp ? "up (port " + f.receiverPort + ")" : "down").append('\n');
        sb.append("- cast pillars: NSD ").append(f.nsdAnnounced ? "announced" : "pending")
          .append(" · mDNS responder ").append(f.mdnsResponderUp ? "up" : "down")
          .append(" · DLNA ").append(f.dlnaUp ? "up" : "down").append('\n');
        sb.append("- worker: ").append(f.workerConfigured
            ? (f.workerHealthy ? "healthy" : "unreachable") : "not configured");
        if (!f.workerVersion.isEmpty()) sb.append(" (v").append(f.workerVersion).append(", storage ").append(f.workerStorage).append(')');
        sb.append('\n');
        sb.append("- player: ").append(f.playerState)
          .append(f.playerError.isEmpty() ? "" : " — " + f.playerError).append('\n');
        sb.append("- subtitles: ").append(f.subsState)
          .append(f.subsDetail.isEmpty() ? "" : " — " + f.subsDetail).append('\n');
        sb.append("- updater: ").append(f.updaterRan ? f.updaterResult : "not run this session").append('\n');
        sb.append("- permissions: notif=").append(f.notifGranted ? "on" : "off")
          .append(" · overlay=").append(f.overlayGranted ? "on" : "off").append('\n');
        sb.append('\n');

        // 2. auto-detected issues
        sb.append("## Auto-Detected Issues\n");
        List<Issue> issues = diagnose(f, null);
        for (Issue i : issues) {
            String mark = i.sev == 'X' ? "❌" : i.sev == 'W' ? "⚠️" : "✓";
            sb.append(mark).append(' ').append(i.en).append('\n');
            if (!i.si.isEmpty()) sb.append("   ").append(i.si).append('\n');
            if (!i.fix.isEmpty()) sb.append("   → ").append(i.fix).append('\n');
        }
        sb.append('\n');

        // 3. top-first last crash
        sb.append("## Last Crash (top-first)\n");
        List<String> top = crashTop(crashReport, 14);
        if (top.isEmpty()) {
            sb.append("(none captured)\n");
        } else {
            for (String l : top) sb.append(l).append('\n');
        }
        sb.append('\n');

        // 4. the subsystem timeline
        sb.append("## Subsystem Timeline  [APP]/[CAST]/[PLAYER]/[SUBS]/[UPDATER]\n");
        List<String> lines = timelineLines();
        if (lines.isEmpty() && diagLogDump != null && !diagLogDump.isEmpty()) {
            // fall back to the Diag ring tail (already scrubbed line-by-line)
            String[] arr = diagLogDump.split("\n", -1);
            int from = Math.max(0, arr.length - 60);
            for (int i = from; i < arr.length; i++) if (!arr[i].isEmpty()) sb.append(arr[i]).append('\n');
        } else {
            for (String l : lines) sb.append(l).append('\n');
        }
        return PrivacyScrubber.scrub(sb.toString());
    }

    /** The generic SDK integer only — never a model/manufacturer string.
     *  Reflection keeps this file compilable on a bare JVM (the lab) while
     *  still reading the REAL Build.VERSION.SDK_INT inside the app. */
    private static String androidSdkOnly() {
        try {
            Class<?> build = Class.forName("android.os.Build");
            Class<?> ver = Class.forName("android.os.Build$VERSION");
            Object sdk = ver.getField("SDK_INT").get(null);
            return String.valueOf(sdk);
        } catch (Throwable t) {
            return "unknown";   // the pure-JVM lab path — the lab variant stamps its own label
        }
    }

    /** Lab/pure-JVM variant: stamps a caller-supplied SDK label into the report header. */
    static String reportForLab(Facts f, String diagDump, String crashReport, String sdkLabel) {
        String real = buildReport(f, diagDump, crashReport);
        return real.replace("Android SDK unknown", "Android SDK " + sdkLabel);
    }
}
