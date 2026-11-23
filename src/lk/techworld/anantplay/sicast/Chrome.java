package lk.techworld.anantplay.sicast;

import java.util.Locale;

/**
 * v3.4.3 — THE ENGLISH-CHROME ENFORCER (Replit QA-0005, QA-0026 … QA-0387).
 *
 * <p>THE PROBLEM. v3.4.2's UI language preference worked only for strings that were
 * written as an explicit pair, {@code t("සිංහල", "English")}. Everywhere else the two
 * languages had been baked into ONE literal — {@code "සිංහල උපසිරැසි සූදානම් · Sinhala
 * subtitles ready"} — so with {@code uiLang = "en"} (the DEFAULT) the Sinhala half still
 * appeared in a status pill, a toast, a sheet header or an accessibility label. The
 * audit counted hundreds of those occurrences.
 *
 * <p>THE FIX. One pure helper that understands the app's own convention — a bilingual
 * literal is the Sinhala part and the English part joined by {@code " · "} (in either
 * order) — and returns ONLY the side the user's language asked for:
 *
 * <ul>
 *   <li>{@code en} → the Latin-script side, with no Sinhala character left anywhere;</li>
 *   <li>{@code si} → the Sinhala side;</li>
 *   <li>{@code bi} (the explicit bilingual opt-in) → the whole literal, unchanged.</li>
 * </ul>
 *
 * <p>Sinhala is deliberately still allowed in exactly four places, none of which go
 * through this class: rendered movie subtitles ({@link Cue}), the Subtitle Style Studio's
 * live preview, the native language names in the subtitle-language picker, and the
 * explicit {@code uiLang = "si"/"bi"} modes.
 *
 * <p>Pure Java 8, no Android types — the lab proves every branch.
 */
final class Chrome {

    /** The process-wide UI language, kept in sync by every Activity's create path. */
    private static volatile String uiLang = "en";

    private Chrome() { }

    /** Called from {@code MainActivity.applyTheme()} and {@code PlayActivity.onCreate()}. */
    static void setUiLang(String lang) {
        if (lang == null) return;
        String l = lang.trim().toLowerCase(Locale.US);
        if (l.equals("si") || l.equals("bi") || l.equals("en")) uiLang = l;
    }

    /** The language chrome is currently rendered in. */
    static String uiLang() { return uiLang; }

    /** True when the string contains at least one Sinhala character (U+0D80–U+0DFF). */
    static boolean hasSinhala(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0D80 && c <= 0x0DFF) return true;
        }
        return false;
    }

    /** True when the string contains at least one ASCII letter (a Latin-script side). */
    static boolean hasLatin(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) return true;
        }
        return false;
    }

    /** The separator the app uses between the Sinhala and the English half. */
    static final String SEP = " · ";

    /**
     * The ENGLISH half of a bilingual literal: every {@code " · "}-separated segment that
     * carries no Sinhala character, rejoined. A literal with no Sinhala is returned
     * unchanged; a literal that is ONLY Sinhala yields "" (the caller then shows nothing
     * rather than Sinhala chrome).
     */
    static String en(String bilingual) {
        if (bilingual == null) return "";
        if (!hasSinhala(bilingual)) return bilingual;
        if (!bilingual.contains(SEP)) {
            // no separator: keep any Latin words that are not Sinhala, else drop it
            return hasLatin(bilingual) ? latinOnly(bilingual) : "";
        }
        StringBuilder sb = new StringBuilder();
        for (String part : bilingual.split("·")) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            if (hasSinhala(t)) {
                // A part that MIXES scripts ("AnantPlay සිංහල Player v%s") keeps its Latin
                // content — dropping the whole part would delete a format placeholder.
                t = latinOnly(t);
                if (t.isEmpty()) continue;
            }
            if (sb.length() > 0) sb.append(SEP);
            sb.append(t);
        }
        return sb.toString().trim();
    }

    /** The SINHALA half of a bilingual literal (the {@code si} opt-in). */
    static String si(String bilingual) {
        if (bilingual == null) return "";
        if (!hasSinhala(bilingual)) return "";
        if (!bilingual.contains(SEP)) return bilingual.trim();
        StringBuilder sb = new StringBuilder();
        for (String part : bilingual.split("·")) {
            if (!hasSinhala(part)) continue;
            String t = part.trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(SEP);
            sb.append(t);
        }
        return sb.toString().trim();
    }

    /** Drop every Sinhala code point, keeping the Latin text and punctuation. */
    static String latinOnly(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0D80 && c <= 0x0DFF) continue;
            sb.append(c);
        }
        // collapse the separator debris a removal can leave behind ("a · · b" → "a · b")
        String out = sb.toString().replace("· ·", "·").replaceAll("\\s+", " ").trim();
        if (out.startsWith("·")) out = out.substring(1).trim();
        if (out.endsWith("·")) out = out.substring(0, out.length() - 1).trim();
        return out;
    }

    /** The one call the UI makes: the literal rendered in the CURRENT UI language. */
    static String pick(String bilingual) { return pick(uiLang, bilingual); }

    /** The literal rendered in an explicit language ({@code en}/{@code si}/{@code bi}). */
    static String pick(String lang, String bilingual) {
        String l = lang == null ? "en" : lang.trim().toLowerCase(Locale.US);
        if (l.equals("si")) return si(bilingual);
        if (l.equals("bi")) return bilingual == null ? "" : bilingual;
        return en(bilingual);
    }

    /**
     * True when the UI language forbids Sinhala chrome — the invariant the English
     * verifier asserts: in {@code en} mode NOTHING this class returns may contain a
     * Sinhala character.
     */
    static boolean englishOnly() { return "en".equals(uiLang); }
}
