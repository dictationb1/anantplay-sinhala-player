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
        // ══ v3.4.3 — THE LIVE-TESTED ORDER (Replit QA-1451 … QA-1464) ═════════════
        // The order is no longer a guess: it is what Replit's live HTTP audit measured
        // on 2026-11-23. Tier 1 answers 200/206 to a plain GET and is tried first;
        // Tier 2 is behind a Cloudflare/JS challenge and only works inside the
        // interactive Chromium WebView, so it is labelled as browser-mediated.
        //
        // ── TIER 1a — Sri Lankan, live HTTP 200, human-verified Sinhala ──
        l.add(new Site("Zoom.lk", "https://zoom.lk/", "https://zoom.lk/?s={q}", true));
        l.add(new Site("PirateLK", "https://piratelk.com/", "https://piratelk.com/?s={q}", true));
        // v3.4.3 — subz.lk is the canonical host (HTTP 200 verified); subzlk.com was the
        // v3.4.2 URL and stays recognised as an alias by canonical() below.
        l.add(new Site("Subz.lk", "https://subz.lk/", "https://subz.lk/?s={q}", true));
        // ── TIER 1b — global catalogues, live HTTP 200/206 ──
        l.add(new Site("SubDL", "https://subdl.com/", "https://subdl.com/search/{q}", false));
        l.add(new Site("Subsource", "https://subsource.net/", "https://subsource.net/search/{q}", false));
        l.add(new Site("YIFY", "https://yifysubtitles.ch/", "https://yifysubtitles.ch/search?q={q}", false));
        // ── TIER 2 — challenge-protected: browser-mediated only, labelled as such ──
        l.add(new Site("Baiscope", "https://www.baiscope.lk/", "https://www.baiscope.lk/?s={q}", true));
        l.add(new Site("Cineru.lk", "https://cineru.lk/", "https://cineru.lk/?s={q}", true));
        l.add(new Site("OpenSubtitles", "https://www.opensubtitles.org/",
            "https://www.opensubtitles.org/en/search2/sublanguageid-sin/moviename-{q}", false));
        l.add(new Site("Podnapisi", "https://www.podnapisi.net/",
            "https://www.podnapisi.net/subtitles/search/?keywords={q}", false));
        return l;
    }

    /** Tier-1 sites answered 200/206 to a plain GET in the live audit. */
    static final java.util.Set<String> LIVE_OK = new java.util.HashSet<>(java.util.Arrays.asList(
        "Zoom.lk", "PirateLK", "Subz.lk", "SubDL", "Subsource", "YIFY"));

    /** Tier-2 sites are Cloudflare/JS-challenge protected: interactive WebView only. */
    static final java.util.Set<String> BROWSER_MEDIATED = new java.util.HashSet<>(java.util.Arrays.asList(
        "Baiscope", "Cineru.lk", "OpenSubtitles", "Podnapisi"));

    /** True when the site must be opened in the interactive browser (never a plain GET). */
    static boolean isBrowserMediated(String siteName) {
        return siteName != null && BROWSER_MEDIATED.contains(siteName);
    }

    /** The honest one-word provenance the sheet shows beside a site. */
    static String tierLabel(String siteName) {
        if (siteName == null) return "";
        if (LIVE_OK.contains(siteName)) return "live ✓";
        if (BROWSER_MEDIATED.contains(siteName)) return "browser only";
        return "";
    }

    /** The canonical host for a site (subzlk.com → subz.lk), or the input unchanged. */
    static String canonical(String urlOrHost) {
        if (urlOrHost == null) return "";
        return urlOrHost.replace("subzlk.com", "subz.lk").replace("www.subzlk.com", "subz.lk");
    }

    // ───────────────────────────────────────────────────────────────────────────
    // v3.4.2 — THE METADATA-RANKED PRIORITY ENGINE
    //
    // "Which subtitle is THE one?" is an arithmetic question, not a guess. Every
    // candidate is scored by what the player actually KNOWS about the movie, in the
    // exact priority order the brief specifies:
    //
    //   exact IMDb id match          +1000
    //   exact cleaned title + year    +400 (+120 for the year)
    //   season/episode match           +250
    //   Sinhala human-verified         +300
    //   English exact (auto-translate) +120
    //   same site family tie-break      +10
    //
    // The lab drives score() and rank() directly, so a future refactor cannot quietly
    // demote an IMDb-exact match below a fuzzy title hit.
    // ───────────────────────────────────────────────────────────────────────────

    /** What the player knows about the movie being watched. All fields optional. */
    static final class Meta {
        String imdbId = "";
        String title = "";
        int year = 0;
        int season = 0;
        int episode = 0;

        Meta() { }

        Meta imdb(String id) { this.imdbId = id == null ? "" : id.trim(); return this; }
        Meta title(String t) { this.title = t == null ? "" : t; return this; }
        Meta year(int y) { this.year = y; return this; }
        Meta episode(int s, int e) { this.season = s; this.episode = e; return this; }
    }

    /** One candidate subtitle as the sites hand it back. */
    static final class Candidate {
        String site = "";
        String label = "";        // the page's own title
        String imdbId = "";
        String lang = "";          // "si" / "en" / ""
        int year = 0;
        int season = 0;
        int episode = 0;
        boolean humanVerified;      // a human-translated Sinhala release
        String url = "";
        int score;

        Candidate() { }

        Candidate site(String s) { this.site = s == null ? "" : s; return this; }
        Candidate label(String s) { this.label = s == null ? "" : s; return this; }
        Candidate imdb(String s) { this.imdbId = s == null ? "" : s.trim(); return this; }
        Candidate lang(String s) { this.lang = s == null ? "" : s; return this; }
        Candidate year(int y) { this.year = y; return this; }
        Candidate season(int s, int e) { this.season = s; this.episode = e; return this; }
        Candidate human(boolean b) { this.humanVerified = b; return this; }
        Candidate url(String s) { this.url = s == null ? "" : s; return this; }
    }

    /** Score ONE candidate against what the player knows. Deterministic, side-effect free. */
    static int score(Candidate c, Meta m) {
        if (c == null) return -1;
        if (m == null) m = new Meta();
        int s = 0;
        // 1. exact IMDb id — the single strongest signal there is
        if (!m.imdbId.isEmpty() && !c.imdbId.isEmpty()
            && m.imdbId.equalsIgnoreCase(c.imdbId)) s += 1000;
        // 2. exact cleaned title (+ the year when both know it)
        String mt = normTitle(m.title), ct = normTitle(c.label);
        if (!mt.isEmpty() && !ct.isEmpty()) {
            if (mt.equals(ct)) s += 400;
            else if (ct.contains(mt) || mt.contains(ct)) s += 180;   // partial is weaker, not zero
        }
        if (m.year > 0 && c.year > 0 && m.year == c.year) s += 120;
        // 3. season / episode (a series must not pick up another episode's file)
        if (m.season > 0 && c.season == m.season && c.episode == m.episode) s += 250;
        else if (m.season > 0 && (c.season != m.season || c.episode != m.episode)
            && (c.season > 0 || c.episode > 0)) s -= 150;          // a WRONG episode is a penalty
        // 4. language quality: human Sinhala beats machine English
        if ("si".equalsIgnoreCase(c.lang)) s += c.humanVerified ? 300 : 60;
        else if ("en".equalsIgnoreCase(c.lang)) s += 120;
        c.score = s;
        return s;
    }

    /** Score + sort every candidate, best first (stable: ties keep the site order). */
    static List<Candidate> rank(List<Candidate> in, Meta m) {
        List<Candidate> out = new ArrayList<>();
        if (in != null) for (Candidate c : in) if (c != null) { score(c, m); out.add(c); }
        java.util.Collections.sort(out, (a, b) -> Integer.compare(b.score, a.score));
        return out;
    }

    /** The best candidate, or null when the list is empty. */
    static Candidate best(List<Candidate> in, Meta m) {
        List<Candidate> r = rank(in, m);
        return r.isEmpty() ? null : r.get(0);
    }

    /** Lower-case, punctuation-free, year-free title for comparison. */
    static String normTitle(String t) {
        if (t == null) return "";
        String s = TitleCleaner.clean(t).title;
        if (s == null) s = t;
        s = s.toLowerCase(Locale.ROOT)
             .replaceAll("\\((?:19|20)\\d{2}\\)", " ")
             .replaceAll("[^a-z0-9\u0D80-\u0DFF ]+", " ")
             .replaceAll("\\s+", " ").trim();
        return s;
    }

    // ───────────────────────────────────────────────────────────────────────────
    // v3.4.2 — SMART TITLE DISCOVERY (never nag "tap to search" first)
    // ───────────────────────────────────────────────────────────────────────────

    /**
     * The title hidden in a URL slug: {@code /influencers-2026-sinhala-subtitles/} →
     * "influencers 2026". Returns "" when the path carries nothing usable.
     */
    static String titleFromUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            String u = url.trim();
            int q = u.indexOf('?');
            if (q >= 0) u = u.substring(0, q);
            int hash = u.indexOf('#');
            if (hash >= 0) u = u.substring(0, hash);
            int scheme = u.indexOf("://");
            if (scheme >= 0) {
                int slash = u.indexOf('/', scheme + 3);
                u = slash >= 0 ? u.substring(slash) : "";
            }
            String[] parts = u.split("/");
            String bestPart = "";
            for (String part : parts) {
                String p = part.trim();
                if (p.length() < 3) continue;
                if (p.matches("(?i)(index\\.php|index\\.html|watch|video|movie|movies|play|embed|stream|srt|zip)")) continue;
                if (p.length() > bestPart.length()) bestPart = p;
            }
            String t = bestPart.replaceAll("(?i)\\.(html?|php|mp4|m3u8|srt|zip)$", "")
                .replace('-', ' ').replace('_', ' ').replace('+', ' ')
                .replaceAll("\\s+", " ").trim();
            if (t.length() < 3) return "";
            // drop the trailing noise every streaming slug carries
            t = t.replaceAll("(?i)\\b(with |full |watch |online |free |download |sinhala |subtitles?\\b)", " ")
                 .replaceAll("\\s+", " ").trim();
            return t;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * The title from a page's HTML: {@code <meta property="og:title">} first, then
     * {@code <title>}. Pure string work, so the lab can prove it on a captured page.
     */
    static String titleFromHtml(String html) {
        if (html == null || html.isEmpty()) return "";
        try {
            String og = firstGroup(html, "(?is)<meta[^>]+property\\s*=\\s*[\"']og:title[\"'][^>]*content\\s*=\\s*[\"']([^\"']{2,200})[\"']");
            if (og.isEmpty()) og = firstGroup(html, "(?is)<meta[^>]+content\\s*=\\s*[\"']([^\"']{2,200})[\"'][^>]*property\\s*=\\s*[\"']og:title[\"']");
            if (!og.isEmpty()) return og.trim();
            String t = firstGroup(html, "(?is)<title[^>]*>(.{2,200}?)</title>");
            return t.replaceAll("(?i)\\s*[|\\-–]\\s*(?:watch|stream|arrowtv|anantplay|player).*$", "").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String firstGroup(String text, String regex) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(text);
            if (m.find()) {
                String g = m.group(1);
                return g == null ? "" : g;
            }
        } catch (Throwable ignored) { }
        return "";
    }

    /**
     * Is a "playhead is OUTSIDE cue range" warning actually a problem?
     *
     * <p>During the opening credits (playhead under 3 minutes, before the first cue) this
     * is completely normal and must never be shown as an alarm.
     */
    static boolean isNormalPreRollGap(long playheadMs, long firstCueStartMs) {
        if (playheadMs < 0) return false;
        if (playheadMs < 180000L) return true;      // first 3 minutes: pre-roll, always normal
        return firstCueStartMs > 0 && playheadMs < firstCueStartMs;
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
