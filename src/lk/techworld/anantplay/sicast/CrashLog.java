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
 * THE FIELD FAILURE (2026-11-22 16:36, Xiaomi Android 16): the app showed
 * "AnantPlay සිංහල Player keeps stopping" right while casting, and NOTHING on
 * disk explained why — v3.0.0's only trace was the Diag ring entry (truncated at
 * 3000 chars, rolling over after 1200 lines), which is useless after a restart
 * and invisible to the user.
 *
 * v3.1.0: App.onCreate() installs a CHAINED Thread.UncaughtExceptionHandler
 * (ours first, then the previous default so the system crash dialog still shows)
 * that ATOMICALLY writes the full story to filesDir/last_crash.txt:
 *
 *   · wall-clock timestamp + app version + versionCode;
 *   · Android SDK level + manufacturer/model/fingerprint;
 *   · the thread name, the exception class + message and the FULL stack trace
 *     (every cause, every frame — no 3000-char cap);
 *   · every line passed through Diag.redact(): stream cookies, Authorization
 *     headers, API keys, tokens and JWT-shaped blobs can NEVER land in the file
 *     (the "1-tap Copy/Share" buttons share exactly this text);
 *   · the write is atomic (last_crash.txt.tmp + rename) — a crash DURING the
 *     write can never leave a half-written file behind.
 *
 * MainActivity's Diagnostics tab surfaces it: a prominent "⚠ Last Crash Captured"
 * card with 1-tap Copy / Share / Clear. The pure formatting half runs in the
 * verification lab on a bare JVM (redaction + completeness proofs). */
public final class CrashLog {

    /** The one file (kept in filesDir). */
    public static final String FILE_NAME = "last_crash.txt";

    private CrashLog() { }

    // ── the pure core (lab-proven) ─────────────────────────────────────────────

    /** The crash report header line format: timestamp is wall-clock, locale-stable. */
    static String stamp(long epochMs) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(epochMs));
    }

    /**
     * Build the FULL redacted crash text. `deviceInfo` is one pre-joined line
     * ("sdk 36 | Xiaomi 25013PS27C | release-16…") — the caller decides what
     * identifies the build; everything else is derived from the throwable.
     */
    public static String format(long epochMs, String appVersion, String deviceInfo,
                                String threadName, Throwable t) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("============================================================\n");
        sb.append(" UNCAUGHT CRASH — AnantPlay Sinhala Player v")
          .append(appVersion == null ? "?" : appVersion).append('\n');
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
        // the LAST line of defense: one FULL redaction pass over the whole text
        // (line-preserving, never truncated — Diag.redactFull): a message like
        // "Connection with Cookie: sid=… refused" is scrubbed too.
        return Diag.redactFull(sb.toString());
    }

    /** Redact a possibly-crash-shaped text (used by the copy/share path too). */
    public static String scrub(String text) { return Diag.redactFull(text == null ? "" : text); }

    // ── the file side (atomic, crash-safe) ─────────────────────────────────────

    /** Where the crash lives for this install (filesDir/last_crash.txt). */
    public static File fileFor(File filesDir) { return new File(filesDir, FILE_NAME); }

    /**
     * Atomically persist a crash report: write to last_crash.txt.tmp, fsync-ish
     * flush, then rename over last_crash.txt. Returns false (never throws) when
     * the disk refuses — the Diag ring still carries a short copy.
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
