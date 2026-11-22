package lk.techworld.anantplay.sicast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/* v2.3.0 — DNS-over-HTTPS RESOLVER + ISP-BLOCK DIAGNOSTIC (Phase 2, Hidden Killer #1).
 *
 * THE PROBLEM (proven from official AnantPlay code):
 *   PlayerActivity.java:1079  → the official player sends its video through
 *   Warp.proxyFor(host) (Warp.java:202-207), a PHONE-SIDE HTTP proxy to the bundled
 *   Cloudflare WARP helper. Cast.describe (Cast.java:342-354) forwards the RAW url +
 *   headers + cookie — NOT a WARP-proxied URL. So when the user casts a stream whose
 *   host an Sri Lankan ISP blocks (the reason they had WARP on), OUR ExoPlayer
 *   (PlayActivity DefaultHttpDataSource, no proxy) hits the ISP block directly:
 *   DNS hijack/poison answers a wrong IP, or the TCP connect times out.
 *
 * WHAT WE CAN DO HONESTLY (zero fork, zero closed binary):
 *   · We cannot ship Cloudflare WARP (AnantPlay's libwarp.so is theirs). 
 *   · We CAN resolve hostnames over DoH (Cloudflare 1.1.1.1, AdGuard fallback) —
 *     encrypted DNS defeats the ISP's DNS hijack, the most common block form in
 *     Sri Lanka. Resolution is pure java.net over HTTPS (443), works on any network.
 *   · And we can DIAGNOSE precisely: compare the system resolver's answer against
 *     DoH; a mismatch (or a system NXDOMAIN where DoH answers) = "ISP DNS block
 *     suspected" — surfaced bilingually with the DoH IPs, next to a 1-tap
 *     "Re-resolve & retry" that retries playback with the DoH answer.
 *
 * PURE JAVA: the lab drives resolve()/blocked() against 1.1.1.1 on a bare JVM with
 * the same code the phone runs (no drift). The DoH JSON wire format:
 *   GET https://1.1.1.1/dns-query?name=example.com&type=A  Accept: application/dns-json
 *   → [{"data":"93.184.216.34",...}, ...]
 */
final class DohResolver {

    private DohResolver() { }

    /** The providers, in fallback order (IP literals: no bootstrap DNS needed). */
    static final String[] PROVIDERS = {
        "https://1.1.1.1/dns-query",                     // Cloudflare
        "https://dns.adguard-dns.com/dns-query",         // AdGuard (hostname — needs working DNS)
    };

    static final int TIMEOUT_MS = 4000;

    /** One resolved A record. */
    static final class Answer {
        String host = "";
        List<String> ips = new ArrayList<>();
        String provider = "";
        long ms = -1;
        String error = "";
        boolean ok() { return error.isEmpty() && !ips.isEmpty(); }
    }

    /** Resolve A records for `host` via DoH (both providers, first success wins). */
    static Answer resolve(String host) {
        Answer a = new Answer();
        if (host == null) { a.error = "no host"; return a; }
        a.host = host;
        long t0 = System.currentTimeMillis();
        for (String p : PROVIDERS) {
            try {
                List<String> ips = queryProvider(p, host);
                if (!ips.isEmpty()) {
                    a.ips = ips;
                    a.provider = p;
                    a.ms = System.currentTimeMillis() - t0;
                    return a;
                }
            } catch (IOException e) {
                a.error = String.valueOf(e.getMessage());
            }
        }
        if (a.ips.isEmpty() && a.error.isEmpty()) a.error = "no answer";
        a.ms = System.currentTimeMillis() - t0;
        return a;
    }

    /** The ISP-block verdict comparing system DNS vs DoH. */
    static final class Verdict {
        boolean suspect;          // the two resolvers disagree → likely ISP interference
        boolean systemFailed;     // the system resolver returned nothing
        String systemIps = "";
        String dohIps = "";
        String dohProvider = "";
        String host = "";
        String summaryBilingual = "";
    }

    /** Compare system resolution with DoH for a stream host. Never throws. */
    static Verdict blocked(String host) {
        Verdict v = new Verdict();
        if (host == null) { v.summaryBilingual = "no host"; return v; }
        v.host = host;
        List<String> sys = new ArrayList<>();
        try {
            InetAddress[] all = InetAddress.getAllByName(host);
            for (InetAddress ad : all) if (ad.getAddress().length == 4) sys.add(ad.getHostAddress());
        } catch (Throwable ignored) { }
        v.systemIps = join(sys);
        Answer doh = resolve(host);
        v.dohIps = doh.ok() ? join(doh.ips) : "";
        v.dohProvider = doh.provider;
        v.systemFailed = sys.isEmpty();
        boolean agree = !sys.isEmpty() && doh.ok() && overlap(sys, doh.ips);
        v.suspect = (doh.ok() && !agree) || (v.systemFailed && doh.ok());
        if (v.suspect) {
            v.summaryBilingual = "ISP DNS බාධාවක් සැක සහිතයි — system DNS සහ DoH එකට වඩා වෙනස් පිළිතුරු දෙයි "
                + "(system: " + (sys.isEmpty() ? "නැත" : v.systemIps) + " · DoH: " + v.dohIps + ") · "
                + "ISP DNS block suspected — system DNS and DoH disagree. Play the same video in "
                + "AnantPlay with WARP on, or use the Direct-URL handoff.";
        } else if (v.systemFailed) {
            v.summaryBilingual = "host එකක් විසඳාගත නොහැක (DNS) — site එක වසා ඇත විය හැක · "
                + "host unresolvable (DNS failure) — the site may be down or blocked";
        } else {
            v.summaryBilingual = "DNS සාමාන්‍යයි · DNS looks normal (system: " + v.systemIps + ")";
        }
        return v;
    }

    // ── the DoH JSON query (Cloudflare/AdGuard both implement application/dns-json) ──

    private static List<String> queryProvider(String providerUrl, String host) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(providerUrl + "?name="
            + urlEncode(host) + "&type=A").openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("Accept", "application/dns-json");
            c.setRequestProperty("User-Agent", "AnantPlaySiCompanion/2.3");
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            byte[] body = readAll(c.getInputStream(), 64 * 1024);
            return parseIps(new String(body, StandardCharsets.UTF_8));
        } finally {
            c.disconnect();
        }
    }

    /** Pull "data":"1.2.3.4" A-record strings out of the DoH JSON answer. */
    static List<String> parseIps(String json) {
        List<String> out = new ArrayList<>();
        if (json == null) return out;
        int i = 0;
        while (true) {
            int k = json.indexOf("\"data\":\"", i);
            if (k < 0) break;
            int start = k + 8;
            int end = json.indexOf('"', start);
            if (end < 0) break;
            String v = json.substring(start, end);
            if (v.matches("\\d{1,3}(\\.\\d{1,3}){3}")) out.add(v);
            i = end + 1;
        }
        return out;
    }

    private static boolean overlap(List<String> a, List<String> b) {
        for (String x : a) for (String y : b) if (x.equals(y)) return true;
        return false;
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        for (int n; (n = in.read(buf)) != -1; ) {
            if (o.size() + n > max) throw new IOException("response too large");
            o.write(buf, 0, n);
        }
        return o.toByteArray();
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }
}
