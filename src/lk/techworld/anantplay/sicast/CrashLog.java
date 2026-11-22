package lk.techworld.anantplay.sicast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/* v3.1.0 — THE PERSISTENT UNCAUGHT CRASH RECORDER (Bug 2, Smoking Gun #2).
 *
 * v3.2.0 — THE STALE-CRASH AUTO-CLEAR (Smoking Gun #3): the field phone kept
 * showing "❌ a crash was captured" in v3.1.2 for a crash that v3.1.1 had
 * ALREADY fixed (#0x88000000) — last_crash.txt carried no version identity, so
 * a NEW build could not tell "a crash from THIS build" (worth showing) apart
 * from "a crash from an OLDER build" (already fixed, worthless noise).
 * THE FIX:
 *   · the report header now carries `versionCode:` (the stable numeric build
 *     identity, not the name) — writeHeader stamps it on every new capture;
 *   · CrashLog.staleFor(report, currentCode) — pure predicate: true when the
 *     report's stamped code is missing (legacy) is FALSE-POSITIVE-SAFE…
 *     no: a report with no versionCode is TREATED AS STALE-UNKNOWN and kept
 *     only when it is recent (<48 h); a report stamped with an OLDER code is
 *     ALWAYS stale for a newer running build;
 *   · App.onCreate calls CrashLog.clearStaleFor(filesDir, AppInfo.VERSION_CODE)
 *     at every startup — a fixed-in-a-newer-build crash is deleted BEFORE the
 *     Troubleshooter ever reads it, so "Auto-Detected Issues" never lies.
 *
 * Everything else is unchanged from v3.1.0: the atomic write, the FULL chained
 * trace, the Diag.redactFull pass, the 1-tap Copy/Share from Diagnostics. */
public final class CrashLog {

    /** The one file (kept in filesDir). */
    public static final String FILE_NAME = "last_crash.txt";

    /** How long a versionCode-less (legacy) report stays relevant. */
    static final long LEGACY_MAX_AGE_MS = 48L * 60 * 60 * 1000;

    private CrashLog() { }

    // ── the pure core (lab-proven) ─────────────────────────────────────────────

    /** The crash report header line format: timestamp is wall-clock, locale-stable. */
    static String stamp(long epochMs) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(epochMs));
    }

    /**
     * Build the FULL redacted crash text. `deviceInfo` is one pre-joined line
     * ("sdk 36") — the caller decides what identifies the build; everything
     * else is derived from the throwable. v3.2.0: the header carries the
     * versionCode line (the stale-crash contract).
     */
    public static String format(long epochMs, String appVersion, int versionCode, String deviceInfo,
                                String threadName, Throwable t) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("============================================================\n");
        sb.append(" UNCAUGHT CRASH — AnantPlay Sinhala Player v")
          .append(appVersion == null ? "?" : appVersion).append('\n');
        sb.append(" versionCode: ").append(versionCode).append('\n');
        sb.append(" at ").append(stamp(epochMs)).append('\n');
        if (deviceInfo != null && !deviceInfo.isEmpty())
            sb.append(" device: ").append(Diag.redact(deviceInfo)).append('\n');
        sb.append(" thread: ").append(threadName == null ? "?" : threadName).append('\n');
        sb.append("------------------------------------------------------------\n");
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 12) {                     // every cause, chained
            sb.append(depth == 0 ? "\nEXCEPTION " : "\nCAUSED BY ")
              .append(cur.getClass().getName())
              .append(cur.getMessage() == null ? "" : ": " + cur.getMessage())
              .append('\n');
            for (StackTraceElement el : cur.getStackTrace()) {  // EVERY frame, no cap
                sb.append("    at ").append(el).append('\n');
            }
            cur = cur.getCause();
            depth++;
        }
        sb.append("============================================================\n");
        return Diag.redactFull(sb.toString());
    }

    // ── the stale-crash predicate (pure — the lab drives every branch) ─────────

    /**
     * v3.2.0 — is this stored report STALE for the running build?
     *   · a report stamped with an OLDER versionCode → ALWAYS stale (the code
     *     that crashed was replaced; every such crash is already fixed or
     *     superseded by construction — the exact field false-positive);
     *   · a report stamped with the SAME versionCode → never stale;
     *   · a legacy report with NO versionCode (v3.1.x) → stale only once it is
     *     older than 48 h (recent unknowns stay visible: a fresh crash from
     *     an upgraded-in-place build deserves attention).
     */
    public static boolean staleFor(String report, int runningVersionCode, long nowMs) {
        if (report == null || report.isEmpty()) return false;
        int code = versionCodeOf(report);
        if (code > 0) return code != runningVersionCode;   // stamped: different code = stale
        // legacy report: age it out (the " at <timestamp>" line)
        long at = capturedAtMs(report, nowMs);
        return nowMs - at > LEGACY_MAX_AGE_MS;
    }

    /** The report's stamped versionCode, 0 when absent (legacy v3.1.x report). */
    public static int versionCodeOf(String report) {
        if (report == null) return 0;
        for (String line : report.split("\n", 20)) {
            String t = line.trim();
            if (t.startsWith("versionCode:")) {
                try {
                    return Integer.parseInt(t.substring("versionCode:".length()).trim());
                } catch (NumberFormatException ignored) { return 0; }
            }
        }
        return 0;
    }

    /** The report's captured-at epoch ms (parsed from the " at " line; the
     *  caller's nowMs when unparseable — a fresh-unknown report). */
    static long capturedAtMs(String report, long fallbackNowMs) {
        if (report == null) return fallbackNowMs;
        for (String line : report.split("\n", 20)) {
            String t = line.trim();
            if (t.startsWith("at ") && t.length() >= 19) {
                try {
                    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
                    return f.parse(t.substring(3).trim()).getTime();
                } catch (Exception ignored) { return fallbackNowMs; }
            }
        }
        return fallbackNowMs;
    }

    /**
     * v3.2.0 — THE STARTUP SWEEP: App.onCreate deletes a stale last_crash.txt
     * (older versionCode, or a legacy report past 48 h) BEFORE the
     * Troubleshooter or the Diagnostics tab ever reads it. Returns true when a
     * stale report was cleared (logged by the caller). Never throws.
     */
    public static boolean clearStaleFor(File filesDir, int runningVersionCode, long nowMs) {
        try {
            String report = read(filesDir);
            if (report == null) return false;
            if (staleFor(report, runningVersionCode, nowMs)) {
                if (clear(filesDir)) {
                    Diag.i("crash", "stale crash report (older versionCode) auto-cleared on startup");
                }
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Redact a possibly-crash-shaped text (used by the copy/share path too). */
    public static String scrub(String text) { return Diag.redactFull(text == null ? "" : text); }

    // ── the file side (atomic, crash-safe) ─────────────────────────────────────

    /** Where the crash lives for this install (filesDir/last_crash.txt). */
    public static File fileFor(File filesDir) { return new File(filesDir, FILE_NAME); }

    /**
     * Atomically persist a crash report: write to last_crash.txt.tmp, flush,
     * then rename over last_crash.txt. Returns false (never throws) when the
     * disk refuses — the Diag ring still carries a short copy.
     */
    public static boolean writeAtomically(File filesDir, String report) {
        try {
            File out = fileFor(filesDir);
            File tmp = new File(filesDir, FILE_NAME + ".tmp");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                w.write(report == null ? "" : report);
                w.flush();
            }
            if (!tmp.renameTo(out)) {                            // cross-fs? fall back
                java.io.FileInputStream in = new java.io.FileInputStream(tmp);
                try (FileOutputStream o = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
                } finally { in.close(); }
                tmp.delete();
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** The stored report (null when none / unreadable). */
    public static String read(File filesDir) {
        try {
            File f = fileFor(filesDir);
            if (!f.exists() || f.length() == 0 || f.length() > 256 * 1024) return null;
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
            }
            return new String(o.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 1-tap Clear from Diagnostics. */
    public static boolean clear(File filesDir) {
        try {
            File f = fileFor(filesDir);
            new File(filesDir, FILE_NAME + ".tmp").delete();
            return !f.exists() || f.delete();
        } catch (Throwable t) {
            return false;
        }
    }
}
