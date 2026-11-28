package lk.techworld.anantplay.sicast;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/* ═══════════════════════════════════════════════════════════════════════════
 * v3.4.6.k — LAW #1: THE SIGNED JSON OTA CONFIG SYNC (ota/v1/manifest.json)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * A silent, self-hosted, cryptographically verified config channel that keeps
 * the app's REMOTE ENDPOINTS fresh without shipping a new APK:
 *
 *   · the Multi-Mirror Subtitle Bridge endpoints (anantplay-si-subs-bridge
 *     .techworldlk.workers.dev + fallback mirrors) — a dead worker never
 *     kills Sinhala subtitles again;
 *   · the default Sri Lanka Index feeds (all 3+ presets);
 *   · the Ad-Block / extractor filter rules;
 *   · the local SubtitleStore SRT cache policy (7-day TTL sweep).
 *
 * THE SIGNATURE MODEL (fail-closed, zero trust in any single host):
 *   1. The manifest is fetched from TWO independent CDNs — the GitHub raw
 *      origin AND the jsDelivr mirror (a closer POP for Sri Lankan ISPs).
 *      Both must return IDENTICAL bytes (cross-CDN consensus) or the sync
 *      refuses the update and keeps the pinned defaults.
 *   2. The manifest carries "sha256": the SHA-256 of the CANONICAL JSON of
 *      its "payload" object (sorted keys, no whitespace). A byte that does
 *      not re-hash to the declared digest is rejected.
 *   3. Every URL must satisfy SelfUpdateCore.safeRemoteUrl (https-only, no
 *      loopback/private/metadata hosts — the SSRF guard).
 *
 * The whole sync runs under one 5-second wall-clock deadline on a single
 * background thread, single-flight, and NEVER blocks the UI or the receiver.
 * Pure-Java core + an injectable fetch seam so the lab round-trips the exact
 * verification path (the house style of SelfUpdateCore's ConnectionOpener). */
public final class OtaConfigSync {

    /** The signed manifest version this build understands. */
    static final int MANIFEST_VERSION = 1;

    /** The two CDNs that must agree (raw origin + jsDelivr edge). */
    static String rawManifestUrl() {
        return "https://raw.githubusercontent.com/" + SelfUpdateCore.REPO
            + "/main/docs/ota/manifest.json";
    }
    static String jsdelivrManifestUrl() {
        return "https://cdn.jsdelivr.net/gh/" + SelfUpdateCore.REPO
            + "@main/docs/ota/manifest.json";
    }

    /** The whole sync (both CDNs + verify + apply) must fit in this budget. */
    static final long DEADLINE_MS = 5_000L;
    /** The manifest body cap (a config document, never an APK lane). */
    static final long MAX_MANIFEST_BYTES = 512L * 1024;

    /** The pinned fail-closed defaults (also the manifest's own content). */
    static final String DEFAULT_BRIDGE_PRIMARY =
        "https://anantplay-si-subs-bridge.techworldlk.workers.dev";
    static final String DEFAULT_BRIDGE_MIRROR_2 =
        "https://anantplay-si-subs-bridge-mirror.techworldlk.workers.dev";
    static final String DEFAULT_INDEX_SL =
        "https://raw.githubusercontent.com/dictationb1/srilanka-anantplay/main/index.json";
    static final String DEFAULT_INDEX_OFFICIAL =
        "https://raw.githubusercontent.com/AnantPlay/index/main/index.json";

    // ── the injectable fetch seam (the lab only; never a release path) ──────
    interface Fetcher {
        String fetch(String url, long deadlineAt) throws Exception;
    }
    private static volatile Fetcher testFetcher;
    static void setFetcherForTests(Fetcher f) { testFetcher = f; }

    private static final java.util.concurrent.atomic.AtomicBoolean syncInFlight =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    private OtaConfigSync() { }

    /** The one-shot silent sync (single-flight; called from App.onCreate). */
    public static void sync(final Context c) {
        if (!syncInFlight.compareAndSet(false, true)) return;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                apply(app, fetchVerified());
                Diag.i("ota", "signed manifest synced + applied (v" + MANIFEST_VERSION + ")");
            } catch (Throwable t) {
                // fail-closed: the pinned defaults simply stay in force
                Diag.w("ota", "sync failed — keeping pinned defaults: " + t);
            } finally {
                syncInFlight.set(false);
            }
        }, "si-ota-sync").start();
    }

    /**
     * v3.4.6.l — THE JSDELIVR FALLBACK (video_2 04:10, the real-phone
     * "Remote config (signed): Pending"): the strict BOTH-CDN consensus is
     * kept when jsDelivr answers, but a jsDelivr that is BLOCKED or
     * unreachable (common on some Sri Lankan ISPs) used to fail the whole
     * sync forever — the About card read Pending on every launch.
     *
     * <p>The degraded lane stays honest: the raw GitHub origin is fetched
     * TWICE and the two reads must be byte-identical (a live-CDN
     * consistency check that survives a mid-flight rewrite), every payload
     * byte must still re-hash to the declared SHA-256, and the manifest
     * version + every URL must still pass the SSRF guard. Fail-closed is
     * preserved on every malformed answer.
     */
    static Map<String, Object> fetchVerified() throws Exception {
        long deadlineAt = System.currentTimeMillis() + DEADLINE_MS;
        String primary = fetch(rawManifestUrl(), deadlineAt);
        if (primary == null || primary.trim().isEmpty())
            throw new SecurityException("primary CDN returned an empty manifest");
        String mirror = null;
        try {
            mirror = fetch(jsdelivrManifestUrl(), deadlineAt);
        } catch (Throwable jsdelivrUnavailable) {
            // the fallback lane below (raw double-read consistency)
            Diag.w("ota", "jsDelivr unreachable — degraded single-origin lane: "
                + jsdelivrUnavailable);
        }
        if (mirror != null && mirror.trim().isEmpty()) mirror = null;
        if (mirror != null) {
            if (!primary.equals(mirror))
                throw new SecurityException("CDN consensus failed — raw and jsDelivr bodies differ");
            return parseVerified(primary);
        }
        // the degraded lane: re-read the origin; both reads must agree.
        long second = System.currentTimeMillis() + DEADLINE_MS;
        String primaryAgain = fetch(rawManifestUrl(), second);
        if (primaryAgain == null || !primary.equals(primaryAgain))
            throw new SecurityException("raw origin double-read mismatch — keeping pinned defaults");
        return parseVerified(primary);
    }

    /** Parse + SHA-256-verify a manifest body (the lab's seam for both paths). */
    static Map<String, Object> parseVerified(String body) throws Exception {
        Object o = MiniJson.parse(body);
        Map<String, Object> m = (Map<String, Object>) o;
        Number v = MiniJson.num(m, "v");
        if (v == null || v.intValue() != MANIFEST_VERSION)
            throw new SecurityException("unsupported manifest version: " + v);
        Object payload = MiniJson.objGet(m, "payload");
        if (!(payload instanceof Map))
            throw new SecurityException("manifest has no payload object");
        String declared = MiniJson.str(m, "sha256");
        if (declared == null || !declared.matches("(?i)[0-9a-f]{64}"))
            throw new SecurityException("manifest sha256 field missing/malformed");
        String actual = sha256Hex(canonicalJson(payload));
        if (!declared.equalsIgnoreCase(actual))
            throw new SecurityException("payload SHA-256 mismatch (declared " + declared
                + " != actual " + actual + ")");
        return (Map<String, Object>) payload;
    }

    /** Fetch one URL under the shared deadline (https-only, size-capped). */
    private static String fetch(String url, long deadlineAt) throws Exception {
        if (!SelfUpdateCore.safeRemoteUrl(url)) throw new SecurityException("unsafe manifest URL");
        Fetcher f = testFetcher;
        if (f != null) return f.fetch(url, deadlineAt);
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection(java.net.Proxy.NO_PROXY);
            long left = deadlineAt - System.currentTimeMillis();
            if (left <= 0) throw new java.net.SocketTimeoutException("ota deadline exceeded");
            c.setConnectTimeout((int) Math.min(4_000, left));
            c.setReadTimeout((int) Math.max(1_000, Math.min(5_000, left)));
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code != 200) throw new SecurityException("HTTP " + code + " for the manifest");
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) {
                    o.write(buf, 0, n);
                    if (o.size() > MAX_MANIFEST_BYTES)
                        throw new SecurityException("manifest exceeds the 512 KiB cap");
                }
                return new String(o.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Apply a VERIFIED payload to the prefs + sweep the subtitle cache TTL. */
    static void apply(Context c, Map<String, Object> payload) {
        SharedPreferences sp = c.getSharedPreferences("sicast", Context.MODE_PRIVATE);
        SharedPreferences.Editor e = sp.edit();
        List<String> mirrors = stringList(MiniJson.objGet(payload, "bridgeMirrors"));
        if (mirrors.isEmpty()) mirrors.add(DEFAULT_BRIDGE_PRIMARY);
        if (!mirrors.contains(DEFAULT_BRIDGE_PRIMARY)) mirrors.add(0, DEFAULT_BRIDGE_PRIMARY);
        e.putString(AppPrefs.K_OTA_BRIDGE_MIRRORS, join(mirrors));
        List<String> feeds = stringList(MiniJson.objGet(payload, "indexFeeds"));
        if (feeds.isEmpty()) feeds.add(DEFAULT_INDEX_SL);
        e.putString(AppPrefs.K_OTA_INDEX_FEEDS, join(feeds));
        List<String> rules = stringList(MiniJson.objGet(payload, "adRules"));
        e.putString(AppPrefs.K_OTA_AD_RULES, join(rules));
        Number days = MiniJson.num(payload, "subCacheDays");
        int d = days == null ? 7 : days.intValue();
        if (d < 1 || d > 30) d = 7;
        e.putInt(AppPrefs.K_OTA_SUB_CACHE_DAYS, d);
        e.putLong(AppPrefs.K_OTA_SYNC_AT, System.currentTimeMillis());
        e.apply();
        // the 7-day local SRT cache sweep rides every successful sync
        try {
            new SubtitleStore(c.getFilesDir())
                .sweepExpired(subCacheDaysMs(c.getSharedPreferences("sicast", Context.MODE_PRIVATE)));
        } catch (Throwable t) {
            Diag.w("ota", "subtitle TTL sweep: " + t);
        }
    }

    // ── the app-side query surface (pinned defaults when no sync ever ran) ──

    /** The subtitle bridge mirrors, DEFAULT first (never empty). */
    public static List<String> bridgeMirrors(SharedPreferences p) {
        List<String> out = stringListRaw(p.getString(AppPrefs.K_OTA_BRIDGE_MIRRORS, null));
        if (out.isEmpty()) out.add(DEFAULT_BRIDGE_PRIMARY);
        if (!out.contains(DEFAULT_BRIDGE_PRIMARY)) out.add(0, DEFAULT_BRIDGE_PRIMARY);
        return out;
    }

    /** The default Sri Lanka index feeds (never empty). */
    public static List<String> indexFeeds(SharedPreferences p) {
        List<String> out = stringListRaw(p.getString(AppPrefs.K_OTA_INDEX_FEEDS, null));
        if (out.isEmpty()) {
            out.add(DEFAULT_INDEX_SL);
            out.add(DEFAULT_INDEX_OFFICIAL);
        }
        return out;
    }

    /** The ad-block/extractor host rules (empty when nothing synced). */
    public static List<String> adRules(SharedPreferences p) {
        return stringListRaw(p.getString(AppPrefs.K_OTA_AD_RULES, null));
    }

    /** The verified manifest's bridge base for the player (mirror #1). */
    static String workerUrl(SharedPreferences p) {
        String custom = p.getString(AppPrefs.K_WORKER_URL, null);
        if (custom != null && !custom.trim().isEmpty()
                && !DEFAULT_BRIDGE_PRIMARY.equals(custom.trim()))
            return custom.trim();   // the user's own bridge always wins
        return bridgeMirrors(p).get(0);
    }

    /** The subtitle cache TTL in ms (default 7 days, 1..30 days). */
    public static long subCacheDaysMs(SharedPreferences p) {
        int d = AppPrefs.safeGetInt(p, AppPrefs.K_OTA_SUB_CACHE_DAYS, 7);
        if (d < 1 || d > 30) d = 7;
        return d * 24L * 60L * 60L * 1000L;
    }

    /** True when the last silent sync landed inside `withinMs` of now. */
    public static boolean syncedRecently(SharedPreferences p, long withinMs) {
        long at = AppPrefs.safeGetLong(p, AppPrefs.K_OTA_SYNC_AT, 0);
        return at > 0 && System.currentTimeMillis() - at < withinMs;
    }

    // ── small pure helpers (shared shapes with the house style) ─────────────

    private static List<String> stringList(Object o) {
        List<String> out = new ArrayList<>();
        for (Object x : MiniJson.arr(o)) {
            if (x == null) continue;
            String s = String.valueOf(x).trim();
            if (s.isEmpty()) continue;
            if (!SelfUpdateCore.safeRemoteUrl(s))
                throw new SecurityException("manifest lists an unsafe URL: " + s);
            out.add(s);
        }
        return out;
    }

    private static List<String> stringListRaw(String joined) {
        List<String> out = new ArrayList<>();
        if (joined == null) return out;
        for (String s : joined.split("\n")) {
            s = s.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        return sb.toString();
    }

    /** sha256Hex of UTF-8 bytes. */
    static String sha256Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(64);
        for (byte b : h) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
            .append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }

    /** The canonical JSON form: sorted keys, no whitespace, deep. */
    static String canonicalJson(Object o) {
        StringBuilder sb = new StringBuilder(128);
        writeCanonical(sb, o);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeCanonical(StringBuilder sb, Object o) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof String) { sb.append(MiniJson.quote((String) o)); return; }
        if (o instanceof Boolean || o instanceof Number) { sb.append(o); return; }
        if (o instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object x : (List<?>) o) {
                if (!first) sb.append(',');
                first = false;
                writeCanonical(sb, x);
            }
            sb.append(']');
            return;
        }
        if (o instanceof Map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet())
                sorted.put(String.valueOf(e.getKey()), e.getValue());
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(MiniJson.quote(e.getKey())).append(':');
                writeCanonical(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        sb.append(MiniJson.quote(String.valueOf(o)));
    }
}
