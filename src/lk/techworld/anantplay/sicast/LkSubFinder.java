package lk.techworld.anantplay.sicast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v3.3.0 — THE SRI LANKAN SUBTITLE QUICK-FINDER (pure Java: URL building + link
 * classification only, so the lab proves every rule without a browser).
 *
 * <p>What the user asked for, in their words: reliable AUTOMATIC Sinhala subtitle
 * finding from the Sri Lankan sites they actually use — Baiscope, Zoom.lk, PirateLK,
 * SubDL — with .zip auto-extract, wired into both the player and the WebView.
 *
 * <p>This class is the deterministic half of that:
 * <ul>
 *   <li>{@link #sites()} — the ordered site list with a search-URL template
 *       ({@code {q}} = the URL-encoded, title-cleaned query).</li>
 *   <li>{@link #searchUrl(String, String)} — the exact deep link for a movie title.</li>
 *   <li>{@link #isSubtitleDownload(String)} / {@link #isZip(String)} — what the
 *       WebView must intercept (".srt", ".zip", ".ass", ".vtt", ".ssa", ".sub",
 *       including {@code ?file=x.zip} query forms and Baiscope's
 *       {@code /download/1234} redirectors when they end in a subtitle type).</li>
 *   <li>{@link #preferredEntry(List)} — which .srt inside a .zip to attach when the
 *       archive holds several (Sinhala first, then the biggest).</li>
 * </ul>
 *
 * <p>The network half (worker v4.1 + the keyless translator) already exists in
 * {@link BridgeApi} / {@link FreeSubTranslator}; the extraction half is
 * {@link SrtLoader} (5 MB zip-bomb cap, {@code ..} traversal refusal). This class
 * only decides WHERE to look and WHAT a captured link is.
 */
final class LkSubFinder {

    /** One Sri Lankan (or global) subtitle site. */
    static final class Site {
        final String name;
        final String home;
        /** Search URL template; {@code {q}} is replaced with the encoded query. */
        final String search;
        final boolean sriLankan;

        Site(String name, String home, String search, boolean sriLankan) {
            this.name = name;
            this.home = home;
            this.search = search;
            this.sriLankan = sriLankan;
        }

        /** The deep link for a query ("" when the site has no search endpoint). */
        String urlFor(String q) {
            if (search == null || search.isEmpty()) return home;
            return search.replace("{q}", enc(q));
        }

        @Override public String toString() { return name; }
    }

    private LkSubFinder() { }

    /**
     * The site list, Sri Lankan first — exactly the order the in-player sheet shows.
     * Templates are the real public search endpoints of each site.
     */
    static List<Site> sites() {
        List<Site> l = new ArrayList<>();
        l.add(new Site("Baiscope", "https://baiscope.lk/", "https://baiscope.lk/?s={q}", true));
        l.add(new Site("Zoom.lk", "https://zoom.lk/", "https://zoom.lk/?s={q}", true));
        l.add(new Site("PirateLK", "https://piratelk.com/", "https://piratelk.com/?s={q}", true));
        l.add(new Site("SubDL", "https://subdl.com/", "https://subdl.com/search/{q}", false));
        l.add(new Site("OpenSubtitles", "https://www.opensubtitles.org/",
            "https://www.opensubtitles.org/en/search2/sublanguageid-sin/moviename-{q}", false));
        return l;
    }

    /** The Sri Lankan sites only (the 🇱🇰 button). */
    static List<Site> sriLankanSites() {
        List<Site> l = new ArrayList<>();
        for (Site s : sites()) if (s.sriLankan) l.add(s);
        return l;
    }

    /** The deep link for {@code title} on the site named {@code siteName} (home when unknown). */
    static String searchUrl(String siteName, String title) {
        String q = query(title);
        for (Site s : sites()) if (s.name.equalsIgnoreCase(siteName)) return s.urlFor(q);
        return "https://duckduckgo.com/?q=" + enc(q + " sinhala subtitles srt");
    }

    /** Every deep link for a title, in site order — the mini web-picker's tab set. */
    static Map<String, String> allSearchUrls(String title) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        for (Site s : sites()) m.put(s.name, s.urlFor(query(title)));
        return m;
    }

    /**
     * The title as a search query: junk/extension noise removed, spaces collapsed,
     * capped at 80 chars. Reuses {@link TitleCleaner} so the WebView search matches
     * the worker's own title cleaning.
     */
    static String query(String title) {
        TitleCleaner.Clean c = TitleCleaner.clean(title);
        String t = c == null || c.title == null ? "" : c.title;
        t = t.replaceAll("\\s+", " ").trim();
        if (t.length() > 80) t = t.substring(0, 80).trim();
        return t;
    }

    // ───────────────────────────────────────────────────────────────────────────
    // link classification (what the WebView intercepts)
    // ───────────────────────────────────────────────────────────────────────────

    private static final String[] SUB_EXT = {".srt", ".zip", ".ass", ".ssa", ".vtt", ".sub"};

    /** True when this URL is a subtitle file/download the app should capture. */
    static boolean isSubtitleDownload(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase(Locale.US);
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false;
        String path = u;
        int q = path.indexOf('?');
        String queryPart = q >= 0 ? path.substring(q + 1) : "";
        if (q >= 0) path = path.substring(0, q);
        for (String ext : SUB_EXT) {
            if (path.endsWith(ext)) return true;
            // ?file=Movie.srt · &name=x.zip · download.php?f=sub.zip
            if (queryPart.contains(ext)) return true;
        }
        return false;
    }

    /** True when the captured link is an archive that must be unzipped first. */
    static boolean isZip(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase(Locale.US);
        return u.contains(".zip");
    }

    /** True when the link is a plain subtitle text file. */
    static boolean isPlainSub(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase(Locale.US);
        return u.endsWith(".srt") || u.endsWith(".ass") || u.endsWith(".ssa") || u.endsWith(".vtt");
    }

    /**
     * Which .srt inside a multi-file archive to attach: a Sinhala-named entry first
     * ("sinhala", "si", "සිං"), then the biggest entry (the feature, not a sample).
     */
    static String preferredEntry(List<String> names) {
        if (names == null || names.isEmpty()) return null;
        String best = null;
        long bestScore = -1;
        for (String n : names) {
            if (n == null) continue;
            String l = n.toLowerCase(Locale.US);
            if (!(l.endsWith(".srt") || l.endsWith(".vtt"))) continue;
            long score = 0;
            if (l.contains("sinhala") || l.contains("sinhalese") || l.contains("si.") || l.contains("_si")) score += 1000;
            if (l.contains("සිං")) score += 1000;
            score += Math.min(500, l.length());            // deterministic tie-break
            if (score > bestScore) { bestScore = score; best = n; }
        }
        if (best != null) return best;
        for (String n : names) if (n != null && n.toLowerCase(Locale.US).endsWith(".srt")) return n;
        return names.get(0);
    }

    /** The 🎬 pill label for a captured link. */
    static String captureLabel(String url) {
        if (isZip(url)) return "💬 Subtitle .zip captured — attach to player";
        if (isPlainSub(url)) return "💬 Subtitle file captured — attach to player";
        return "💬 Subtitle link captured";
    }

    // ───────────────────────────────────────────────────────────────────────────
    // the external-player escape hatch (VLC / MX / MPV)
    // ───────────────────────────────────────────────────────────────────────────

    /** The players we hand a stream to when Media3 cannot decode it — VLC first. */
    static final String[] EXTERNAL_PLAYERS = {
        "org.videolan.vlc",              // VLC for Android
        "com.mxtech.videoplayer.ad",     // MX Player (free)
        "com.mxtech.videoplayer.pro",    // MX Player Pro
        "is.air.tv.mpv",                 // mpv-android
    };

    /**
     * The Intent extras that make VLC/MX/mpv open the CURRENT stream at the CURRENT
     * position WITH the downloaded Sinhala .srt attached. Pure (a map, not an Intent)
     * so the lab can prove the contract: every player reads a different key, so all
     * of them are set — VLC's {@code subtitles_location}, MX's {@code subtitle} /
     * {@code subs}, and the generic {@code subs} array AnantPlay itself forwards.
     */
    static Map<String, String> externalExtras(String url, String subPath, String title, long posMs) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("url", url == null ? "" : url);
        m.put("title", title == null ? "" : title);
        m.put("position", String.valueOf(Math.max(0, posMs)));
        m.put("from_start", "0");
        String loc = safeSubLocation(subPath);
        if (loc != null) {
            m.put("subtitles_location", loc);   // VLC
            m.put("subtitle", loc);             // MX Player
            m.put("subs", loc);                 // generic / AnantPlay's own extra
        }
        return m;
    }

    /**
     * v3.3.0 AUDIT FIX — WHAT AN EXTERNAL PLAYER MAY BE HANDED (Android 7–16 safety).
     *
     * <p>A VLC/MX/mpv launch runs in ANOTHER app's process, under ANOTHER Linux UID.
     * Two failure modes made the old {@code 📺 VLC} button useless (or fatal):
     * <ul>
     *   <li>{@code Uri.fromFile(new File(getCacheDir(), "sinhala-sub.srt"))} placed in an
     *       outgoing Intent throws {@link android.os.FileUriExposedException} on Android 7+
     *       (StrictMode {@code detectFileUriExposure}) — a hard crash on Android 16.</li>
     *   <li>Even a raw path STRING ({@code /data/user/0/<pkg>/cache/sinhala-sub.srt})
     *       never crashes us, but VLC cannot {@code open()} it: {@code EACCES}, our
     *       private cache is mode 0700. The user sees "no subtitles" and blames the app.</li>
     * </ul>
     *
     * <p>So only URIs another app can actually read survive: {@code http(s)://} — the
     * app's own loopback {@code ReceiverServer} {@code /subs/current.srt} route — and
     * {@code content://} (a future FileProvider). Raw paths and {@code file://} are
     * DROPPED, never forwarded. The lab asserts this contract, so the regression cannot
     * come back.
     */
    static String safeSubLocation(String sub) {
        if (sub == null) return null;
        String s = sub.trim();
        if (s.isEmpty()) return null;
        String l = s.toLowerCase(Locale.US);
        for (String scheme : EXTERNAL_READABLE_SCHEMES) {
            if (l.startsWith(scheme)) return s;
        }
        return null;
    }

    /** The ONLY schemes an external player can read from our Intent (never file://). */
    private static final String[] EXTERNAL_READABLE_SCHEMES = {"http://", "https://", "content://"};

    /** The package that should receive {@link #externalExtras} first (VLC). */
    static String preferredPlayer() { return EXTERNAL_PLAYERS[0]; }

    // ───────────────────────────────────────────────────────────────────────────

    /** RFC-3986-ish query encoding (space → %20, UTF-8 bytes). */
    static String enc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (byte bb : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = bb & 0xFF;
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.' || c == '~';
            if (safe) sb.append((char) c);
            else sb.append('%').append(String.format(Locale.US, "%02X", c));
        }
        return sb.toString();
    }
}
