package lk.techworld.anantplay.sicast;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/* v3.1.2 — THE PRIVACY-FIRST ZERO-PII SCRUBBER (the strict hard rule).
 *
 * THE FIELD LEAK (2026-11-22, v3.1.1 on Android 16): the diagnostics log and the
 * crash report carried the user's exact phone hardware model ("Xiaomi
 * 24117RN76G"), the LAN IP (192.168.1.39), and the EUI-64 IPv6 link-local
 * address (fe80::f84a:81ff:feac:e2f8) — which EMBEDS the phone's Wi-Fi MAC
 * address (f8:4a:81:ac:e2:f8 flipped) and therefore identifies the physical
 * device forever. That must never happen again.
 *
 * THE HARD RULE (enforced here, on EVERY log line, crash trace, and report):
 *   NEVER collect, log, display or export: Build.MANUFACTURER / MODEL / DEVICE /
 *   PRODUCT / BRAND / HARDWARE / FINGERPRINT / BOARD / radio / serials, MAC
 *   addresses, raw LAN/WAN IPs, EUI-64 IPv6, Wi-Fi SSIDs, cookies, auth tokens
 *   (Bearer/JWT), API keys (AIza…), URL query secrets (token=/sig=/key=/auth=/
 *   pass=/expires=), user storage paths (/storage/emulated/0/...), or any other
 *   personal identifier.
 *
 * WHAT IS KEPT (the debuggable technical state, exactly what an AnantPlay
 * cast/subtitle issue needs): app version, the generic "Android SDK N" integer,
 * subsystem states (port, DLNA, mDNS, NSD, Worker status/version/providers),
 * playback state, subtitle fetch stages / HTTP codes / provider / cue counts,
 * and full redacted Java exception stack traces (class + message + frames —
 * with any host/IP/token text inside them anonymised).
 *
 * PURE JAVA + REGEX ONLY — the lab feeds it synthetic leaks (the exact strings
 * from the field phone plus 15 more shapes) and proves ZERO survive while the
 * technical lines stay byte-intact. Diag calls scrub() before storing every
 * line; CrashLog, Troubleshooter and the ADB helper scripts all pass through
 * the same engine, so nothing can bypass it. */
public final class PrivacyScrubber {

    private PrivacyScrubber() { }

    // ── 1. device / hardware identifiers ───────────────────────────────────────

    /** "Xiaomi 24117RN76G", "Redmi Note 13", "Pixel 8a" style vendor+model pairs. */
    private static final Pattern VENDOR_MODEL = Pattern.compile(
        "(?i)\\b(xiaomi|redmi|poco|realme|oppo|vivo|oneplus|honor|huawei|samsung|google|"
        + "infinix|tecno|itel|nokia|motorola|sony|asus|lenovo|meizu|zte|nothing)\\s+"
        + "[a-z0-9][a-z0-9 \\-_.]{2,40}");

    /** A bare retail hardware-model token ("24117RN76G", "25013PS27C", "M2101K6G"). */
    private static final Pattern HW_MODEL_TOKEN = Pattern.compile(
        "\\b[a-z0-9]{3}\\d[a-z]?[0-9]{3,4}[a-z]{0,4}\\d?[a-z]?\\b|"
        + "\\b[A-Z]{2}\\d{2,}[A-Z]{0,4}\\d{0,4}\\b|"
        + "\\bM2[01][0-9]{3}[A-Z0-9]{2,6}\\b");

    /** Build.* fields quoted in text ("Build.MODEL=...", "fingerprint=..."). */
    private static final Pattern BUILD_FIELD = Pattern.compile(
        "(?i)(build\\.[A-Z_]+|fingerprint|manufacturer|\\bMANUFACTURER\\b|\\bMODEL\\b"
        + "|\\bDEVICE\\b|\\bBRAND\\b|\\bPRODUCT\\b|\\bBOARD\\b|\\bHARDWARE\\b|serial)"
        + "\\s*[:=]\\s*[^,;\\s\\]\\}]+");

    // ── 2. MAC + IPv6 EUI-64 + all IP forms ───────────────────────────────────

    /** A 48-bit MAC address anywhere ("AA:BB:CC:DD:EE:FF", "aabb.ccdd.eeff"). */
    private static final Pattern MAC = Pattern.compile(
        "(?i)(?<![0-9a-f])(?:[0-9a-f]{2}[:\\-]){5}[0-9a-f]{2}(?![0-9a-f])"
        + "|(?<![0-9a-f])[0-9a-f]{4}\\.[0-9a-f]{4}\\.[0-9a-f]{4}(?![0-9a-f])");

    /** IPv6 — link-local first (EUI-64 carries the MAC), then everything. */
    private static final Pattern V6_LINKLOCAL = Pattern.compile(
        "(?i)\\bfe80::[0-9a-f:]{4,30}(?:%[a-z0-9]+)?\\b");
    private static final Pattern V6_ANY = Pattern.compile(
        "(?<!\\w)(?:[0-9a-f]{1,4}:){2,7}[0-9a-f]{1,4}(?!\\w)", Pattern.CASE_INSENSITIVE);

    /** IPv4 — private ranges vs public get different placeholders. */
    private static final Pattern V4 = Pattern.compile(
        "\\b(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\b");

    // ── 3. secrets: cookies, auth, keys, query params ──────────────────────────

    /** Bearer / Basic auth headers and JWT shapes. */
    private static final Pattern AUTH_HEADER = Pattern.compile(
        "(?i)(authorization\\s*[:=]\\s*(?:bearer|basic|token)?\\s*)([A-Za-z0-9._\\-+/=]{8,})");
    private static final Pattern JWT = Pattern.compile(
        "\\beyJ[A-Za-z0-9_\\-]{6,}\\.[A-Za-z0-9_\\-]{6,}\\.[A-Za-z0-9_\\-]{4,}\\b");
    /** Google API keys. */
    private static final Pattern GOOGLE_KEY = Pattern.compile(
        "\\bAIza[0-9A-Za-z_\\-]{30,}\\b");
    /** URL query secrets (key=value and ?key=value&… shapes). */
    private static final Pattern QUERY_SECRET = Pattern.compile(
        "(?i)[?&;](token|sig|signature|key|api_key|apikey|auth|access_token|refresh_token"
        + "|password|passwd|pass|secret|session|sessionid|expires|expire|credential|code)"
        + "=[^&\\s#'\"<>]+");
    private static final Pattern KV_SECRET = Pattern.compile(
        "(?i)\\b(cookie|set-cookie|x-api-key|api[-_]?key|jwt|bearer)(\\s*[:=]\\s*)([^\\s,;\"'&)]+)");

    // ── 4. SSID + local storage paths ─────────────────────────────────────────

    private static final Pattern SSID = Pattern.compile(
        "(?i)(ssid|wi-?fi name|network name)\\s*[:=]\\s*[^,;\\n\\r]{2,32}");
    private static final Pattern STORAGE_PATH = Pattern.compile(
        "(?i)/(?:storage|sdcard|mnt/[^/]+)/[A-Za-z0-9 _\\-.()]{3,}[^\\s]*(?:\\.\\w{1,8})?");

    // ── the placeholders (self-describing, zero information) ──────────────────

    public static final String LOCAL_V4 = "<local-v4>";
    public static final String LAN_PEER = "<lan-peer>";
    public static final String REMOTE_IP = "<remote-ip>";
    public static final String LOCAL_V6 = "<local-v6>";
    public static final String PEER_V6 = "<peer-v6>";
    public static final String REDACTED = "<redacted>";
    public static final String PHONE_MODEL = "<phone-model>";
    public static final String LOCAL_MEDIA = "<local-media>";

    // ── the public entry points ───────────────────────────────────────────────

    /** Scrub one log/report line (or a whole multi-line text): privacy-safe,
     *  never throws, never returns null. Idempotent. */
    public static String scrub(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        try {
            String out = text;
            out = scrubIps(out);
            out = scrubMacAndV6(out);
            out = scrubSecrets(out);
            out = scrubDeviceIdentifiers(out);
            out = scrubPathsAndSsid(out);
            return out;
        } catch (Throwable t) {
            // a scrubber failure must never break logging — plain Secrets-grade
            // redaction is still applied via Diag's own pass in the callers
            return Diag.redactFull(text);
        }
    }

    /** The IPv4 pass: private/loopback → &lt;local-v4&gt; / &lt;lan-peer&gt;, public → &lt;remote-ip&gt;. */
    static String scrubIps(String text) {
        Matcher m = V4.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        int last = 0;
        while (m.find()) {
            sb.append(text, last, m.start());
            sb.append(classifyV4(m.group(1), m.group(2), m.group(3), m.group(4)));
            last = m.end();
        }
        sb.append(text, last, text.length());
        return sb.toString();
    }

    /** Classify an IPv4 (o1..o4 as strings) into its placeholder. */
    static String classifyV4(String o1, String o2, String o3, String o4) {
        try {
            int a = Integer.parseInt(o1), b = Integer.parseInt(o2),
                c = Integer.parseInt(o3), d = Integer.parseInt(o4);
            if (a < 0 || a > 255 || b < 0 || b > 255 || c < 0 || c > 255 || d < 0 || d > 255)
                return LAN_PEER;                                     // malformed → safe default
            if (a == 127) return LOCAL_V4;                            // loopback (this phone)
            if (a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168))
                return LAN_PEER;                                      // LAN side incl. routers
            if (a == 192 && b == 168) return LAN_PEER;
            if (a == 0 || a >= 224) return REMOTE_IP;                  // reserved/multicast
            return REMOTE_IP;                                         // public
        } catch (Throwable t) {
            return LAN_PEER;
        }
    }

    /** The MAC + IPv6 pass (link-local first — EUI-64 embeds the MAC). */
    static String scrubMacAndV6(String text) {
        String out = V6_LINKLOCAL.matcher(text).replaceAll(LOCAL_V6);
        // any other IPv6 (a peer's): keep the shape recognizable but anonymous
        out = V6_ANY.matcher(out).replaceAll(PEER_V6);
        out = MAC.matcher(out).replaceAll(REDACTED);
        return out;
    }

    /** The secrets pass: auth headers, JWTs, API keys, query secrets, cookies. */
    static String scrubSecrets(String text) {
        String out = AUTH_HEADER.matcher(text).replaceAll("$1" + REDACTED);
        out = JWT.matcher(out).replaceAll(REDACTED);
        out = GOOGLE_KEY.matcher(out).replaceAll(REDACTED);
        out = QUERY_SECRET.matcher(out).replaceAll(mr -> mr.group(1) + "=" + REDACTED);
        out = KV_SECRET.matcher(out).replaceAll(mr ->
            mr.group(1) + mr.group(2) + Diag.mask(mr.group(3)));
        return out;
    }

    /** The device-identifier pass: vendor+model, model tokens, Build.* fields.
     *  The crash report's own benign "device: sdk N" header line survives —
     *  v3.1.2's crash header is sdk-ONLY; every OTHER device:/MODEL= value
     *  is an identifier and gets the placeholder. */
    static String scrubDeviceIdentifiers(String text) {
        // run the matcher over the WHOLE line, not the captured key=value token:
        // the exemption test needs the full "device: sdk 36" context
        StringBuilder sb = new StringBuilder(text.length());
        java.util.regex.Matcher m = BUILD_FIELD.matcher(text);
        int last = 0;
        while (m.find()) {
            sb.append(text, last, m.start());
            // find the line this match lives on for the exemption test
            int lineStart = text.lastIndexOf('\n', m.start()) + 1;
            int lineEnd = text.indexOf('\n', m.end());
            if (lineEnd < 0) lineEnd = text.length();
            String line = text.substring(lineStart, lineEnd);
            boolean exempt = line.trim().toLowerCase(java.util.Locale.ROOT)
                .matches("(?:device|model|brand|product|board|hardware|manufacturer|serial|fingerprint)\\s*[:=]\\s*sdk\\s+\\d+.*");
            if (exempt) sb.append(m.group());
            else sb.append(m.group(1)).append('=').append(PHONE_MODEL);
            last = m.end();
        }
        sb.append(text, last, text.length());
        String out = VENDOR_MODEL.matcher(sb.toString()).replaceAll(PHONE_MODEL);
        out = HW_MODEL_TOKEN.matcher(out).replaceAll(PHONE_MODEL);
        return out;
    }

    /** The paths/SSID pass. */
    static String scrubPathsAndSsid(String text) {
        String out = STORAGE_PATH.matcher(text).replaceAll(LOCAL_MEDIA);
        out = SSID.matcher(out).replaceAll("$1=" + REDACTED);
        return out;
    }

    // ── verification helpers (the lab drives these directly) ────────────────────

    /** True when a candidate leak actually SURVIVED scrubbing (case-sensitive). */
    public static boolean leaked(String scrubbed, String... secrets) {
        if (scrubbed == null) return secrets != null && secrets.length > 0;
        for (String s : secrets) {
            if (s == null || s.isEmpty()) continue;
            if (scrubbed.contains(s)) return true;
        }
        return false;
    }

    /** All the canonical leak shapes the lab feeds (the 2026-11-22 field set + more). */
    public static List<String> canaryLeaks() {
        List<String> l = new ArrayList<>();
        l.add("Xiaomi 24117RN76G");
        l.add("fe80::f84a:81ff:feac:e2f8%wlan0");
        l.add("fe80::f84a:81ff:feac:e2f8");
        l.add("192.168.1.39");
        l.add("192.168.1.148");
        l.add("AA:BB:CC:DD:EE:FF");
        l.add("f8:4a:81:ac:e2:f8");
        l.add("Cookie: session=abc123");
        l.add("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c");
        l.add("AIzaSyD-1234567890abcdefghijklmnopqrstuv");
        l.add("?token=secret123");
        l.add("/storage/emulated/0/Movies/private.mp4");
        l.add("Build.MODEL=24117RN76G");
        l.add("fingerprint=xiaomi/milkway/M2101K6G:16/SOMS:user/release-keys");
        return l;
    }
}
