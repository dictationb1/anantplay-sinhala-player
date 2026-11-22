package lk.techworld.anantplay.sicast;

import java.util.Locale;

/* v3.2.0 — THE SCRIPT-RATIO LANGUAGE VALIDATOR (Smoking Gun #2: "wrong subtitle
 * language showing instead of Sinhala").
 *
 * THE FIELD FAILURE: the user picked Sinhala (si), but what landed on screen
 * was "English CC (stream)" and mislabeled OpenSubtitles/SubDL tracks — the
 * pipeline treated the FETCHED cues as Sinhala ("සිංහල උපසිරැසි සූදානම්") without
 * ever CHECKING the text. A track fetched for si that is actually English or
 * French must be detected, not displayed under a Sinhala label.
 *
 * THE DETECTOR (pure, zero Android classes — the lab drives every branch):
 *   · sinhalaRatio(text) — the share of SINHALA-script letters (U+0D80..U+0DFF,
 *     excluding U+0DCA-adjacent combining mechanics — a simple range count over
 *     the letters) among all LETTERS (Unicode letters via Character.getType);
 *   · looksSinhala(text) — ratio ≥ 0.30 over ≥ 8 letters (short mixed lines
 *     like "ආයුබෝවන්!" still qualify; an English line scores ~0.00);
 *   · latinRatio(text) — the same share for A-Z/a-z, for the auto-translate
 *     trigger ("this .srt is English → translate it into Sinhala");
 *   · dominantScript(text) — "sinhala" | "latin" | "other" for the UI pill.
 *
 * The app-side rule (PlayActivity): a cue list fetched as si with
 * !SubLangDetect.looksSinhala(sample) is NEVER shown as "සිංහල" — it goes to
 * the free keyless translator (FreeSubTranslator) or the honest fallback label. */
public final class SubLangDetect {

    private SubLangDetect() { }

    /** The Sinhala Unicode block (U+0D80..U+0DFF), letters only (space/punct-free). */
    public static boolean isSinhalaLetter(int cp) {
        return cp >= 0x0D80 && cp <= 0x0DFF;
    }

    /** True for A-Z / a-z (the Latin-script share). */
    public static boolean isLatinLetter(int cp) {
        return (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z');
    }

    /** True for any Unicode letter (Lu, Ll, Lt, Lm, Lo — not digits, marks, punct). */
    public static boolean isLetter(int cp) {
        int t = Character.getType(cp);
        return t == Character.UPPERCASE_LETTER || t == Character.LOWERCASE_LETTER
            || t == Character.TITLECASE_LETTER || t == Character.MODIFIER_LETTER
            || t == Character.OTHER_LETTER;
    }

    /** The share of Sinhala-script letters among all letters (0..1; 0 when no letters). */
    public static double sinhalaRatio(String text) {
        return scriptRatio(text, SubLangDetect::isSinhalaLetter);
    }

    /** The share of Latin letters among all letters (0..1; 0 when no letters). */
    public static double latinRatio(String text) {
        return scriptRatio(text, SubLangDetect::isLatinLetter);
    }

    private interface LetterPred { boolean is(int cp); }

    private static double scriptRatio(String text, LetterPred pred) {
        if (text == null || text.isEmpty()) return 0;
        int letters = 0, hits = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!isLetter(cp)) continue;
            letters++;
            if (pred.is(cp)) hits++;
        }
        return letters == 0 ? 0 : (double) hits / letters;
    }

    /** A real Sinhala sample: ≥30% Sinhala-script letters over ≥8 letters total. */
    public static boolean looksSinhala(String text) {
        return looksScript(text, SubLangDetect::isSinhalaLetter, 0.30, 8);
    }

    /** A real Latin sample: ≥70% Latin letters over ≥8 letters total. */
    public static boolean looksLatin(String text) {
        return looksScript(text, SubLangDetect::isLatinLetter, 0.70, 8);
    }

    private static boolean looksScript(String text, LetterPred pred, double minRatio, int minLetters) {
        if (text == null || text.isEmpty()) return false;
        int letters = 0, hits = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!isLetter(cp)) continue;
            letters++;
            if (pred.is(cp)) hits++;
        }
        return letters >= minLetters && (double) hits / letters >= minRatio;
    }

    /** The dominant script of a sample — "sinhala" | "latin" | "other". */
    public static String dominantScript(String text) {
        double si = sinhalaRatio(text), la = latinRatio(text);
        if (si >= 0.30) return "sinhala";
        if (la >= 0.70) return "latin";
        return "other";
    }

    /** A representative sample for a cue list: the concatenation of the middle
     *  cues' text (capped at 2000 chars) — the head is often credits/logos. */
    public static String sampleOf(java.util.List<Cue> cues) {
        if (cues == null || cues.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int mid = Math.max(0, cues.size() / 2 - 5);
        for (int i = mid; i < cues.size() && sb.length() < 2000; i++) {
            Cue c = cues.get(i);
            sb.append(c.si == null || c.si.isEmpty() ? c.en : c.si).append(' ');
        }
        return sb.toString().trim();
    }

    /** Human summary for the status pill (bilingual, privacy-safe). */
    public static String describe(double ratio) {
        return String.format(Locale.US, "sinhala-ratio %.0f%%", ratio * 100);
    }
}
