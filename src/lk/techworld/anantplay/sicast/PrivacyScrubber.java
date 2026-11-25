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

    /** "Xiaomi 24117RN76G", "Redmi Note 13", "Pixel 9" style vendor+model pairs.
     *  v3.4.2 — the model tail accepts a SINGLE character ("Pixel 9", "Galaxy A52"):
     *  the v3.4.1 pattern needed 3+ and silently leaked short retail names. */
    private static final Pattern VENDOR_MODEL = Pattern.compile(
        "(?i)\\b(xiaomi|redmi|poco|realme|oppo|vivo|oneplus|honor|huawei|samsung|google|"
        + "pixel|galaxy|infinix|tecno|itel|nokia|motorola|sony|asus|lenovo|meizu|zte|nothing)\\s+"
        + "[a-z0-9][a-z0-9 \\-_.]{0,40}");

    /**
     * v3.4.2 — THE OVER-SCRUBBING FIX (the field log's {@code imdb=<phone-model>} and
     * {@code cue t0=<phone-model>}).
     *
     * <p>The v3.4.1 hardware-token regex was a blind digit-run matcher, so it ate
     * IMDb ids ({@code tt33764258}), millisecond playhead numbers ({@code t0=141776},
     * {@code playhead=832031}, {@code cues 90956-6842000}) and every other 7-digit
     * technical number — destroying exactly the debuggable state the log exists for.
     *
     * <p>Now the regex only PROPOSES a candidate token; {@link #isHwModel(String)}
     * decides. A real phone hardware model always carries <b>two or more upper-case
     * letters and at least one digit</b> ({@code 24117RN76G}, {@code M2101K6G},
     * {@code SM-S928B}, {@code CPH2451}), while an IMDb id, a millisecond count, a
     * codec ({@code h264}), a resolution ({@code 1080p}) or a version never does.
     */
    private static final Pattern HW_MODEL_TOKEN = Pattern.compile(
        "(?<![A-Za-z0-9_\\-])[A-Za-z0-9][A-Za-z0-9_\\-]{3,17}(?![A-Za-z0-9_\\-])");

    /** Ordinary English words that can follow a brand word in a sentence
     *  ("nothing hidden", "nothing else", "one plus one"). */
    private static final Pattern ENGLISH_STOP = Pattern.compile(
        "(?i)^(hidden|else|new|here|there|to|and|or|but|is|was|has|had|will|"
        + "more|less|wrong|missing|found|changed|special|important|unusual|"
        + "outstanding|suspicious|plus|minus a|detected|reported|available)$");

    /** True when the token carries at least one ASCII digit. */
    private static boolean hasDigit(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) >= '0' && s.charAt(i) <= '9') return true;
        return false;
    }

    /** An IMDb id — never an identifier of the phone, always kept verbatim. */
    private static final Pattern IMDB_ID = Pattern.compile("(?i)\\btt\\d{6,10}\\b");

    /** Leading letter runs that are APP TECHNOLOGY, never a phone model. */
    private static final java.util.Set<String> BENIGN_TOKENS = new java.util.HashSet<>(java.util.Arrays.asList(
        "SDK", "API", "HTTP", "HTTPS", "HLS", "DASH", "DASHBOARD", "DLNA", "NSD", "MDNS",
        "SSDP", "EXO", "EXOPLAYER", "MEDIA3", "WEBM", "WEBVTT", "SRT", "VTT", "ASS", "SSA",
        "MP4", "MKV", "AVI", "MOV", "M4A", "WAV", "AAC", "OPUS", "FLAC", "MP3",
        "H264", "H265", "AVC", "HEVC", "VP9", "AV1", "AAC2", "EAC3", "AC3", "DTS",
        "URL", "URI", "JSON", "UTF", "UTF8", "UTF16", "BASE64", "JWT", "TLS", "SSL",
        "RTMP", "HDR", "FPS", "GPU", "CPU", "RAM", "ROM", "APK", "PID", "UID", "GID",
        "IPV4", "IPV6", "DNS", "DOH", "MIME", "CODEC", "GOP", "PTS", "DTS2", "SEI"));

    /**
     * Is this candidate token a real phone hardware model?
     *
     * <p>Kept deliberately narrow — a false NEGATIVE here can leak a model, a false
     * POSITIVE destroys a debuggable number. The v3.4.2 rule: at least two upper-case
     * letters, at least one digit, no dot (versions), not an IMDb id, not a pure
     * digit run, and not a known app-technology token.
     */
    static boolean isHwModel(String tok) {
        if (tok == null) return false;
        String t = tok.trim();
        if (t.length() < 5 || t.length() > 18) return false;
        if (IMDB_ID.matcher(t).matches()) return false;          // tt33764258 — NEVER
        int upper = 0, digit = 0, letter = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= 'A' && c <= 'Z') upper++;
            if (c >= 'a' && c <= 'z') letter++;
            if (c >= '0' && c <= '9') digit++;
        }
        if (digit == 0 || upper < 2) return false;               // ms counts, 1080p, h264
        if (letter == 0 && upper == 0) return false;
        // the leading letter run: "SDK-36" / "EXO-1" / "HLS-2" are technology, not hardware
        int i = 0;
        StringBuilder lead = new StringBuilder();
        while (i < t.length() && Character.isLetter(t.charAt(i))) lead.append(t.charAt(i++));
        String leadUp = lead.toString().toUpperCase(java.util.Locale.ROOT);
        if (BENIGN_TOKENS.contains(leadUp)) return false;
        if (BENIGN_TOKENS.contains(t.toUpperCase(java.util.Locale.ROOT))) return false;
        return true;
    }

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
        // v3.4.6.g — THE HEADER FALSE POSITIVE. The vendor list carries the
        // real brand "Nothing", so the report header "== EVERY FAIL ✗ (the whole
        // truth, first — nothing hidden) ==" was scrubbed to
        // "== EVERY FAIL ✗ (<phone-model>) ==" — the scrubber ate the sentence
        // that says nothing is hidden. A vendor+model pair is now only replaced
        // when the model TAIL carries a digit (every real model does:
        // 24117RN76G, Note 13, Pixel 9, Galaxy A52, CPH2451) and the whole match
        // is not an ordinary English phrase.
        java.util.regex.Matcher vm = VENDOR_MODEL.matcher(sb.toString());
        StringBuilder vmOut = new StringBuilder(sb.length());
        int vmLast = 0;
        while (vm.find()) {
            String whole = vm.group();
            String tail = whole.substring(vm.group(1).length()).trim();
            boolean looksModel = hasDigit(tail) && tail.length() <= 40
                && !ENGLISH_STOP.matcher(tail).find();
            vmOut.append(sb, vmLast, vm.start());
            vmOut.append(looksModel ? PHONE_MODEL : whole);
            vmLast = vm.end();
        }
        vmOut.append(sb, vmLast, sb.length());
        String out = vmOut.toString();
        // v3.4.2 — validate every candidate: only a REAL hardware model is replaced.
        out = HW_MODEL_TOKEN.matcher(out).replaceAll(mr ->
            isHwModel(mr.group()) ? PHONE_MODEL : mr.group());
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
