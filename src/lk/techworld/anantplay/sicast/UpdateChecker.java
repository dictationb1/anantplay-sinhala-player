package lk.techworld.anantplay.sicast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/* v2.3.0 — THE IN-APP GITHUB UPDATE CHECKER (Phase 3, the Obtainium loop).
 *
 * Queries the repo's LATEST release:
 *   GET https://api.github.com/repos/dictationb1/anantplay-sinhala-player/releases/latest
 *   → {tag_name: "v2.3.0", assets:[{name, browser_download_url}], …}
 * and compares its version against AppInfo.VERSION_NAME with a clean semver
 * comparison (never a string compare: 2.10.0 > 2.9.0).
 *
 * PURE JAVA (no Android classes): MainActivity calls check() on a background
 * thread and renders the result; the lab drives the same comparison + JSON
 * parsing on a bare JVM against a fixture body. The network call itself is only
 * exercised live (never in CI — no gate depends on GitHub being reachable). */
public final class UpdateChecker {

    /** The repo this app updates from (Obtainium uses the same URL). */
    public static final String REPO_URL = "https://github.com/dictationb1/anantplay-sinhala-player";
    public static final String LATEST_API = "https://api.github.com/repos/dictationb1/anantplay-sinhala-player/releases/latest";
    public static final String REPO_NAME = "dictationb1/anantplay-sinhala-player";

    private UpdateChecker() { }

    /** The outcome, rendered bilingually by the caller. */
    static final class Result {
        boolean newer;            // the release is newer than the running build
        String latest = "";       // e.g. "2.3.0"
        String tagName = "";      // e.g. "v2.3.0"
        String apkUrl = "";       // the .asset download URL ("" when absent)
        String releaseUrl = "";   // the release's html_url
        String notes = "";        // first 300 chars of the release body
        boolean offline;          // the query failed (no network / rate limit)
        String error = "";
    }

    /** Blocking check — call from a background thread. */
    static Result check() {
        Result r = new Result();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(LATEST_API).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "AnantPlaySiCompanion/2.3");
            try {
                int code = c.getResponseCode();
                if (code == 404) {                    // no release published yet
                    r.offline = true;
                    r.error = "no releases yet";
                    return r;
                }
                if (code == 403) {                    // GitHub API rate limit (60/h anonymous)
                    r.offline = true;
                    r.error = "GitHub API rate-limited — try later";
                    return r;
                }
                if (code != 200) {
                    r.offline = true;
                    r.error = "HTTP " + code;
                    return r;
                }
                byte[] body = readAll(c.getInputStream());
                Map<String, Object> j = Cue.statusOf(new String(body, StandardCharsets.UTF_8));
                r.tagName = String.valueOf(j.get("tag_name"));
                r.latest = stripV(r.tagName);
                Object assetsObj = MiniJson.objGet(j, "assets");
                if (assetsObj instanceof java.util.List) {
                    for (Object o : (java.util.List<?>) assetsObj) {
                        if (!(o instanceof Map)) continue;
                        Map<?, ?> a = (Map<?, ?>) o;
                        String name = String.valueOf(a.get("name"));
                        if (name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                            r.apkUrl = String.valueOf(a.get("browser_download_url"));
                        }
                    }
                }
                Object rel = j.get("html_url");
                if (rel != null) r.releaseUrl = String.valueOf(rel);
                Object bodyText = j.get("body");
                if (bodyText != null) r.notes = BridgeApi.brief(String.valueOf(bodyText), 300);
                r.newer = isNewer(r.latest, AppInfo.VERSION_NAME);
                return r;
            } finally {
                c.disconnect();
            }
        } catch (IOException | RuntimeException e) {
            r.offline = true;
            r.error = String.valueOf(e.getMessage());
            return r;
        }
    }

    /** "v2.3.0" → "2.3.0" (tolerant of a missing v, spaces, junk). */
    static String stripV(String tag) {
        if (tag == null) return "";
        String t = tag.trim();
        if (t.toLowerCase(Locale.ROOT).startsWith("v")) t = t.substring(1);
        return t.trim();
    }

    /** Clean semver compare: true when candidate > current. Tolerant of
     *  missing parts (2.3 == 2.3.0) and non-numeric junk (falls back to unequal). */
    static boolean isNewer(String candidate, String current) {
        int[] a = parse(candidate);
        int[] b = parse(current);
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return a[i] > b[i];
        }
        return false;
    }

    private static int[] parse(String v) {
        int[] out = {0, 0, 0};
        if (v == null) return out;
        String[] parts = v.split("[.\\-+]");
        for (int i = 0; i < 3 && i < parts.length; i++) {
            String p = parts[i].replaceAll("\\D", "");
            if (p.isEmpty()) continue;
            try { out[i] = Integer.parseInt(p); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0; ) {
            o.write(buf, 0, n);
            if (o.size() > 1024 * 1024) throw new IOException("response too large");
        }
        return o.toByteArray();
    }
}
