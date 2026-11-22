package lk.techworld.anantplay.sicast;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/* v2.4.0 — the PURE-JAVA core of the built-in GitHub self-updater: release
 * parsing, SHA256SUMS verification, version facts. No Android imports, so the
 * verification lab drives every rule on a bare JVM against a mock GitHub
 * server (the Android side — JobScheduler, PackageInstaller, notifications —
 * lives in SelfUpdater.java). */
final class SelfUpdateCore {

    private SelfUpdateCore() { }

    /** The GitHub release facts we need. */
    static final class Release {
        String version;          // tag without 'v'
        String apkUrl;           // browser_download_url of the .apk asset
        String sha256Expected;   // from SHA256SUMS.txt (null when absent → refuse)
    }

    /** GET the /releases/latest JSON + the SHA256SUMS.txt asset, parse the apk
     *  URL, the version and the expected hash (MiniJson — no org.json). */
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
                if (name.toLowerCase(Locale.ROOT).endsWith(".apk") && r.apkUrl.isEmpty()) r.apkUrl = url;
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

    /** Find "<sha256>  <file>" in a SHA256SUMS.txt body (GNU coreutils format).
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
        StringBuilder sb = new StringBuilder(64);
        for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    /** v2.4.0 TRAP 5 — the APK download: HTTPS-ONLY (a redirect that lands on plain
     *  http is refused), hard 30 MB cap enforced per chunk as well as from
     *  Content-Length, so a hostile or broken server cannot fill the heap. */
    static byte[] download(String url) throws Exception {
        if (!SelfUpdateInstall.isAllowedTransport(url)) {
            throw new IllegalArgumentException("refusing a non-HTTPS apk url: " + url);
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(java.net.Proxy.NO_PROXY);
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            if (!SelfUpdateInstall.isAllowedTransport(c.getURL().toString())) {
                throw new IllegalStateException("redirected to a non-HTTPS url: " + c.getURL());
            }
            String refusal = SelfUpdateInstall.downloadRefusal(
                c.getURL().toString(), c.getContentLengthLong() < 0 ? 0 : c.getContentLengthLong());
            if (refusal != null) throw new IllegalStateException("apk download refused: " + refusal);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(1 << 20);
            InputStream in = c.getInputStream();
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (!SelfUpdateInstall.sizeStillAllowed(out.size())) {
                    throw new IllegalStateException("apk download exceeded the "
                        + SelfUpdateInstall.MAX_APK_BYTES + " byte cap");
                }
            }
            in.close();
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    static String httpGet(String url) throws Exception {
        if (!SelfUpdateInstall.isAllowedTransport(url)) return null;   // API + sums: HTTPS only
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
