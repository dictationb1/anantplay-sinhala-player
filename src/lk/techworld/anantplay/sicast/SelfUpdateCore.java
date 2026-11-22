package lk.techworld.anantplay.sicast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;

/* v3.0.0 — the PURE-JAVA core of the built-in GitHub self-updater: release
 * parsing, SHA256SUMS verification, STREAMED download with live progress and a
 * hard size cap. No Android imports, so the verification lab drives every rule
 * on a bare JVM against a mock GitHub server (progress callbacks, tampered-hash
 * refusal, oversize refusal, cleanup proof).
 *
 * v3 additions over v2.4.0:
 *   · downloadToFile() — streams to cacheDir/updates/ with a Progress callback
 *     (bytes/total/percent) and the 30 MB cap enforced DURING the stream, so a
 *     hostile "release" asset can never fill the phone's storage;
 *   · sha256(File) — streaming digest (the APK is never fully held in memory);
 *   · Release.apkSize — the asset's declared size from the release JSON, used
 *     to refuse an oversized download BEFORE the first byte arrives;
 *   · httpsOnly() — the install path accepts https:// download URLs only. */
final class SelfUpdateCore {

    /** Nothing larger than this is ever downloaded (a hostile/renamed asset). */
    static final long MAX_APK_BYTES = 30L * 1024 * 1024;

    private SelfUpdateCore() { }

    /** Live download progress (called on the download thread). */
    interface Progress {
        void onProgress(long bytes, long total, int percent);
    }

    /** The GitHub release facts we need. */
    static final class Release {
        String version;          // tag without 'v'
        String apkUrl;           // browser_download_url of the .apk asset
        String sha256Expected;   // from SHA256SUMS.txt (null when absent → refuse)
        long apkSize = -1;       // the asset's declared size (bytes; -1 unknown)
    }

    /** True only for https:// URLs (the updater refuses everything else). */
    static boolean httpsOnly(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).startsWith("https://");
    }

    /** GET the /releases/latest JSON + the SHA256SUMS.txt asset, parse the apk
     *  URL, the version, the declared size and the expected hash (MiniJson). */
    static Release fetchRelease(String apiUrl) throws Exception {
        String body = httpGet(apiUrl);
        if (body == null || body.isEmpty()) return null;
        Object root = MiniJson.parse(body);
        if (!(root instanceof java.util.Map)) return null;
        String tag = MiniJson.str(root, "tag_name");
        if (tag == null || tag.isEmpty()) return null;
        Release r = new Release();
        r.version = tag.startsWith("v") ? tag.substring(1) : tag;
        r.apkUrl = "";
        java.util.List<Object> assets = MiniJson.arr(MiniJson.objGet(root, "assets"));
        if (assets != null) {
            String sumsUrl = null;
            for (Object a : assets) {
                String name = MiniJson.str(a, "name");
                String url = MiniJson.str(a, "browser_download_url");
                if (name == null || url == null) continue;
                if (name.toLowerCase(Locale.ROOT).endsWith(".apk") && r.apkUrl.isEmpty()) {
                    r.apkUrl = url;
                    Number size = MiniJson.num(a, "size");
                    r.apkSize = size == null ? -1 : size.longValue();
                }
                if (name.equalsIgnoreCase("SHA256SUMS.txt")) sumsUrl = url;
            }
            if (sumsUrl != null && !r.apkUrl.isEmpty()) {
                String sums = httpGet(sumsUrl);
                r.sha256Expected = pickShaLine(sums, apkFileName(r.apkUrl));
            }
        }
        return r.apkUrl.isEmpty() ? null : r;
    }

    /** The file name part of a download URL. */
    static String apkFileName(String url) {
        int s = url.lastIndexOf('/');
        return s < 0 ? url : url.substring(s + 1);
    }

    /** Find "<sha256>  <file>" in a SHA256SUMS.txt body (GNU coreutils format —
     *  the EXACT format .github/workflows/release-apk.yml writes: one line,
     *  "sha256sum <apk>" output, hash + two spaces + file name).
     *  Returns null when the file has no line (→ refuse to install). */
    static String pickShaLine(String sums, String apkFile) {
        if (sums == null || apkFile == null) return null;
        for (String line : sums.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+", 2);
            if (parts.length != 2) continue;
            String hash = parts[0].trim().toLowerCase(Locale.ROOT);
            String file = parts[1].trim().replaceFirst("^[*]", "");
            if (apkFile.equals(file) && hash.matches("[0-9a-f]{64}")) return hash;
        }
        return null;
    }

    /** SHA-256 of a byte array, lowercase hex. */
    static String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(data);
        return hex(d);
    }

    /** SHA-256 of a FILE, streamed in 64 KB chunks (never holds the APK in memory). */
    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(64);
        for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    /**
     * v3.0.0 — STREAM the APK into a file with live progress, enforcing:
     *   · https-only URL (anything else → SecurityException before connecting);
     *   · the 30 MB cap, checked against the declared Content-Length BEFORE the
     *     first byte AND against the accumulated bytes DURING the stream.
     * Returns the bytes written (== file length).
     */
    static long downloadToFile(String url, File out, Progress progress) throws Exception {
        if (!httpsOnly(url)) throw new SecurityException("refusing non-https download: " + url);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(java.net.Proxy.NO_PROXY);
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        try {
            return pump(c, out, MAX_APK_BYTES, progress);
        } finally {
            c.disconnect();
        }
    }

    /** The streaming core (package-private — the verification lab drives it on a
     *  bare JVM over a local http server): enforces the cap BEFORE the first
     *  byte (declared Content-Length) and DURING the stream, reports progress
     *  (percent = -1 when the length is unknown), and never writes a byte past
     *  the cap. */
    static long pump(HttpURLConnection c, File out, long cap, Progress progress) throws Exception {
        if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
        long total = c.getContentLengthLong();   // -1 when unknown
        if (total > cap) throw new IllegalStateException("apk too large before download: " + total + " > " + cap);
        long done = 0;
        try (InputStream in = c.getInputStream(); OutputStream fo = new FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) {
                done += n;
                if (done > cap) throw new IllegalStateException("apk exceeded the " + (cap / 1024 / 1024) + " MB cap mid-download");
                fo.write(buf, 0, n);
                if (progress != null) progress.onProgress(done, total,
                    total > 0 ? (int) (done * 100 / total) : -1);
            }
        }
        return done;
    }

    /** v3.0.0 — the storage-cleanup primitive the Android side sweeps with
     *  (SelfUpdater.sweepUpdates + the post-session-handoff delete): removes
     *  EVERY file under dir, returning the bytes freed. Lab-proven: after a
     *  handoff + sweep the updates dir holds 0 leftover bytes. */
    static long deleteAll(File dir) {
        long freed = 0;
        if (dir == null || !dir.isDirectory()) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            long len = f.length();
            if (f.delete()) freed += len;
        }
        return freed;
    }

    /** The v2.4.0 in-memory download (kept for the lab + tiny fixtures). */
    static byte[] download(String url) throws Exception {
        if (!httpsOnly(url)) throw new SecurityException("refusing non-https download: " + url);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(java.net.Proxy.NO_PROXY);
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(1 << 20);
            InputStream in = c.getInputStream();
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            in.close();
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(java.net.Proxy.NO_PROXY);
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            in.close();
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }
}
