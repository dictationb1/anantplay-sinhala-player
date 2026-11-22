package lk.techworld.anantplay.sicast;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/* v2.3.0 — THE NOISY-TITLE CLEANER (Phase 2, Hidden Killer #2).
 *
 * What AnantPlay's Cast.describe (Cast.java:342-354) sends when the user plays from an
 * arbitrary streaming site: imdb="" and title = the site's page title, e.g.
 *   "Watch The Odyssey 2026 Full Movie Free HD - OnlyFlix"
 *   "Episode 3 - 1080p - SomeSite.to"
 *   "THE ODYSSEY (2026) | Watch Online Free 4K"
 * Feeding that raw string to the worker's /api/resolve (Cinemeta fuzzy match) fails or
 * mismatches; subtitle lookup then dies with "couldn't match".
 *
 * This class turns any noisy site title into {cleanTitle, year, season, episode} —
 * PURE JAVA, zero Android classes, so the verification lab drives the exact same code
 * the app ships (the project's no-drift rule).
 *
 * Design rules:
 *   · NEVER throw — a weird title degrades to a trimmed original.
 *   · Idempotent: clean(clean(x)) == clean(x) (the cache key is built on the output).
 *   · Keeps the display title UNTOUCHED elsewhere: the cleaner is only for /api/resolve
 *     and the SubtitleStore key; the HUD shows what the user saw.
 *   · The ORIGINAL title is the fallback: if cleaning would produce <2 chars, the
 *     trimmed original is used (resolve then behaves exactly like v2.2.0). */
final class TitleCleaner {

    private TitleCleaner() { }

    /** The cleaned lookup result. */
    static final class Clean {
        String title = "";     // the cleaned title for /api/resolve (never null, ≥2 chars unless input was)
        Integer year;          // 1930–2029 or null
        Integer season;        // ≥1 or null
        Integer episode;       // ≥1 or null
        boolean changed;       // true when the cleaner altered the input at all
    }

    /** Site-name-ish tail after a separator: " - OnlyFlix", " | some.site.to", " – Watch". */
    private static final Pattern SITE_TAIL = Pattern.compile(
        "\\s*[-–—|·:]\\s*[A-Za-z0-9 .]*(?:Watch|Movie|Stream|Cinema|Flix|Hub|Verse|Play|HD|Online|Free)"
            + "[A-Za-z0-9 .]*(?:\\.(?:com|net|org|to|tv|cc|io|me|co|xyz|lol|ru|in|pk|lk))?\\s*$",
        Pattern.CASE_INSENSITIVE);

    /** A bare domain tail: " - OnlyFlix.to", " | 123movies.net". */
    private static final Pattern DOMAIN_TAIL = Pattern.compile(
        "\\s*[-–—|·:]\\s*[A-Za-z0-9][A-Za-z0-9 ]{0,24}\\.(?:com|net|org|to|tv|cc|io|me|co|xyz|lol|ru|in|pk|lk)\\s*$",
        Pattern.CASE_INSENSITIVE);

    /** Year in parentheses, brackets or bare: "(2026)", "[2024]", " 2026 ". */
    private static final Pattern YEAR = Pattern.compile("\\(?\\b(19[3-9]\\d|20[0-4]\\d)\\b\\)?");

    /** S01E02 / s1e2 / 1x02 forms. */
    private static final Pattern SXEX = Pattern.compile("\\b[Ss](\\d{1,2})[xX ._-]?[Ee](\\d{1,3})\\b");

    /** "Season 2 Episode 3" / "season 2, ep 3". */
    private static final Pattern SEASON_EP = Pattern.compile(
        "\\bseason\\s+(\\d{1,2})[\\s,]*(?:episode|ep\\.?|part)?\\s*(\\d{1,3})?\\b", Pattern.CASE_INSENSITIVE);

    /** "Episode 3" alone. */
    private static final Pattern EP_ONLY = Pattern.compile("\\bepisode\\s+(\\d{1,3})\\b", Pattern.CASE_INSENSITIVE);

    /** Noise tokens to delete (word-bounded, case-insensitive), in order. */
    private static final String[] NOISE = {
        "watch online", "watch free", "watch full", "watch now", "watch",
        "full movie", "full episode", "full", "free online", "online free", "online",
        "movie free", "free streaming", "free download", "free",
        "streaming", "stream", "download", "subtitled", "subtitles", "subtitle", "subs",
        "hd quality", "1080p", "720p", "480p", "360p", "4k", "uhd", "hd", "fhd",
        "web-dl", "webrip", "web", "brrip", "bdrip", "bluray", "blu-ray", "dvdrip", "dvdscr", "dvd",
        "x264", "x265", "h264", "h265", "hevc", "avc", "aac", "aac2", "dts", "hdrip", "camrip", "hdcam",
        "official", "trailer", "teaser", "extended", "uncut", "remastered", "complete",
    };

    /** Prefixes the sites love: "Watch The Odyssey" → "The Odyssey". */
    private static final Pattern LEADING_NOISE = Pattern.compile(
        "^(?:watch|download|stream|play)\\s+", Pattern.CASE_INSENSITIVE);

    /** "Movie" / "Series" as a trailing generic word. */
    private static final Pattern TRAILING_GENERIC = Pattern.compile(
        "\\s+(?:movie|film|series|show|episode)\\s*$", Pattern.CASE_INSENSITIVE);

    /** The result of cleaning: never throws, never returns an empty title from non-empty input. */
    static Clean clean(String raw) {
        Clean c = new Clean();
        if (raw == null) return c;
        String t = raw.replace('\u00A0', ' ').trim();
        if (t.isEmpty()) return c;
        String original = t;

        // 1. season/episode FIRST (before noise deletion eats the numbers)
        Matcher m = SXEX.matcher(t);
        if (m.find()) {
            c.season = parseInt(m.group(1), 99);
            c.episode = parseInt(m.group(2), 999);
            t = t.substring(0, m.start()) + " " + t.substring(m.end());
        } else {
            Matcher se = SEASON_EP.matcher(t);
            if (se.find()) {
                c.season = parseInt(se.group(1), 99);
                if (se.group(2) != null) c.episode = parseInt(se.group(2), 999);
                t = t.substring(0, se.start()) + " " + t.substring(se.end());
            } else {
                Matcher eo = EP_ONLY.matcher(t);
                if (eo.find()) {
                    c.episode = parseInt(eo.group(1), 999);
                    t = t.substring(0, eo.start()) + " " + t.substring(eo.end());
                }
            }
        }

        // 2. the year
        m = YEAR.matcher(t);
        if (m.find()) {
            c.year = parseInt(m.group(1), 2999);
            t = t.substring(0, m.start()) + " " + t.substring(m.end());
        }

        // 3. site tails (two shapes; loop until stable)
        for (int i = 0; i < 3; i++) {
            String next = strip(t, SITE_TAIL);
            next = strip(next, DOMAIN_TAIL);
            if (next.equals(t)) break;
            t = next;
        }

        // 4. noise tokens (longest first so "watch online" goes before "watch")
        for (String n : NOISE) {
            t = t.replaceAll("(?i)\\b" + Pattern.quote(n) + "\\b", " ");
        }

        // 5. leading verbs + trailing generic words + separator crumbs
        t = LEADING_NOISE.matcher(t).replaceFirst("");
        t = TRAILING_GENERIC.matcher(t).replaceFirst("");
        t = t.replaceAll("\\s*[-–—|·]\\s*$", "");          // a dangling separator
        t = t.replaceAll("\\s*[-–—|·]\\s*[-–—|·]\\s*", " - ");   // doubled separators
        t = t.replaceAll("[\\[\\](){}<>_]+", " ");
        t = t.replaceAll("\\s{2,}", " ").trim();
        if (t.matches("[-–—|·:]+")) t = "";

        // 6. ALL-CAPS → Title Case word by word (Cinemeta matches Title Case better)
        if (t.length() >= 4 && t.equals(t.toUpperCase(Locale.ROOT)) && t.matches(".*[A-Z]{2}.*")) {
            StringBuilder tc = new StringBuilder(t.length());
            for (String w : t.split("\\s+")) {
                if (tc.length() > 0) tc.append(' ');
                if (w.length() > 0) tc.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase(Locale.ROOT));
            }
            t = tc.toString();
        }

        // 7. never produce a too-short artifact — fall back to the trimmed original
        if (t.replaceFirst("^(?:the|a|an)\\s+", "").trim().length() < 2) t = original;

        c.title = t;
        c.changed = !t.equals(raw.replace('\u00A0', ' ').trim());
        return c;
    }

    /** Remove the first match of the pattern (a site tail) plus its separator crumbs. */
    private static String strip(String s, Pattern p) {
        Matcher m = p.matcher(s);
        if (!m.find()) return s;
        return (s.substring(0, m.start()) + " " + s.substring(m.end())).replaceAll("\\s{2,}", " ").trim();
    }

    private static int parseInt(String s, int max) {
        try {
            int v = Integer.parseInt(s.trim());
            return v >= 0 && v <= max ? v : 1;
        } catch (Exception e) {
            return 1;
        }
    }
}
