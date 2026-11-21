package lk.techworld.anantplay.sicast;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/* The in-memory diagnostics ring every component logs to — the fix for the silent
 * death of the old companion: even when something goes wrong at 18:12 on a Xiaomi
 * phone, the what/when/why is in here, and MainActivity's Diagnostics screen shows
 * it with a Copy button (plus a file copy, so a crash still leaves a trace).
 *
 * SECRETS NEVER LEAVE THIS CLASS: every line passes through redact() before it is
 * stored, so a stream cookie, an Authorization header, a worker job token or an API
 * key can never be recovered from the "Copy log" button or from diag.log.
 *
 * Pure Java: the ring works on the bare JVM in the lab too; the Android app supplies
 * the file sink + the uncaught-exception hook via install(). */
public final class Diag {

    public interface Sink { void line(String s); }

    public static final int RING = 1200;

    /** One log line never exceeds this — a 256 KB /play body must not flood the ring. */
    static final int MAX_LINE = 2000;

    /** key = value / key: value secrets (cookie, token, api key, signature …). */
    private static final Pattern KV_SECRET = Pattern.compile(
        "(?i)\\b(cookie|set-cookie|authorization|proxy-authorization|x-api-key|api[-_]?key|"
        + "token|access[-_]?token|session|sessionid|password|passwd|secret|signature|sig|"
        + "key|jwt|bearer)(\\s*[:=]\\s*)([^\\s,;\"'&)]+)");

    /** "Authorization: Bearer eyJ…" — the scheme word itself is not a secret. */
    private static final Pattern BEARER = Pattern.compile(
        "(?i)\\b(bearer\\s+)([A-Za-z0-9._\\-+/=]{6,})");

    /** Standalone long opaque blobs that look like a token/URL signature. */
    private static final Pattern LONG_BLOB = Pattern.compile(
        "(?i)\\b([A-Za-z0-9_\\-]{24,}\\.[A-Za-z0-9_\\-]{16,}(?:\\.[A-Za-z0-9_\\-]{8,})?)");

    private static final Object lock = new Object();
    private static final Deque<String> ring = new ArrayDeque<>();
    private static volatile Sink sink;
    private static long t0 = System.currentTimeMillis();

    private Diag() { }

    public static void install(Sink fileSink) { sink = fileSink; }

    public static void i(String tag, String msg) { add("I", tag, msg); }
    public static void w(String tag, String msg) { add("W", tag, msg); }
    public static void e(String tag, String msg) { add("E", tag, msg); }

    public static void crash(String where, Throwable t) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("UNCAUGHT in ").append(where).append(": ").append(t);
        for (StackTraceElement el : t.getStackTrace()) {
            sb.append("\n    at ").append(el);
            if (sb.length() > 3000) { sb.append("\n    …"); break; }
        }
        add("E", "crash", sb.toString());
    }

    /** Mask a secret value, keeping just enough shape to recognise it. */
    static String mask(String v) {
        if (v == null) return "••••";
        if (v.length() <= 4) return "••••";
        return v.substring(0, 2) + "••••" + v.substring(v.length() - 1);
    }

    /**
     * Scrub secrets out of one log line. Idempotent and cheap enough to run on every
     * line (the ring is small and logging is not in a hot loop).
     */
    public static String redact(String msg) {
        if (msg == null) return "";
        String out = BEARER.matcher(msg).replaceAll("$1" + Matcher.quoteReplacement("••••"));
        Matcher kv = KV_SECRET.matcher(out);
        StringBuffer sb = new StringBuffer(out.length());
        while (kv.find()) {
            kv.appendReplacement(sb, Matcher.quoteReplacement(kv.group(1) + kv.group(2) + mask(kv.group(3))));
        }
        kv.appendTail(sb);
        out = LONG_BLOB.matcher(sb.toString()).replaceAll(Matcher.quoteReplacement("••••"));
        return out.length() > MAX_LINE ? out.substring(0, MAX_LINE) + "…" : out;
    }

    private static void add(String lvl, String tag, String msg) {
        long up = System.currentTimeMillis() - t0;
        String clean = redact(msg).replace('\n', ' ');
        String s = String.format(java.util.Locale.US, "%8d.%03d %s/%s: %s",
            up / 1000, up % 1000, lvl, tag, clean);
        synchronized (lock) {
            if (ring.size() >= RING) ring.removeFirst();
            ring.addLast(s);
        }
        Sink sk = sink;
        if (sk != null) {
            try { sk.line(s); } catch (Throwable ignored) { }
        }
    }

    /** The ring as one text block (newest last) — the Copy Log button's payload. */
    public static String dump() {
        synchronized (lock) {
            StringBuilder sb = new StringBuilder(ring.size() * 96);
            for (String s : ring) sb.append(s).append('\n');
            return sb.toString();
        }
    }

    public static int size() { synchronized (lock) { return ring.size(); } }

    /** Test hook: clear the ring (the lab measures redaction on a clean slate). */
    static void reset() { synchronized (lock) { ring.clear(); } }
}
