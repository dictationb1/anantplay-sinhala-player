package lk.techworld.anantplay.sicast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v2.2 — the universal language registry (a 1:1 mirror of worker/src/languages.js;
 * the Player lab asserts the two lists stay in lock-step).
 *
 * Every language the bridge can translate into: OpenSubtitles ISO-639-2B code
 * (human-subs search), native label (the language chips), script class
 * (sinhala | latin | indic | cjk | arabic | thaat | cyrillic — the exact seven
 * classes worker/src/languages.js declares), RTL flag (Arabic/Urdu rendering)
 * and the max-chars-per-line budget the
 * subtitle view enforces (38–42 for Sinhala/Latin/Indic, 16–20 for CJK).
 */
public final class Langs {

    private Langs() {}

    /** One registry entry — immutable by design. */
    public static final class Lang {
        public final String code;      // ISO-639-1 (worker ?lang=, job ids)
        public final String osCode;    // OpenSubtitles ISO-639-2B
        public final String subdl;     // SubDL code
        public final String nativeName; // chip label
        public final String english;   // status text
        public final String script;    // sinhala|latin|indic|cjk|arabic|thaat|cyrillic
        public final boolean rtl;
        public final int maxChars;     // per-line budget

        Lang(String code, String osCode, String subdl, String nativeName, String english,
             String script, boolean rtl, int maxChars) {
            this.code = code;
            this.osCode = osCode;
            this.subdl = subdl;
            this.nativeName = nativeName;
            this.english = english;
            this.script = script;
            this.rtl = rtl;
            this.maxChars = maxChars;
        }
    }

    /** The registry, in chip order (si first — the app's default). */
    public static final List<Lang> ALL = build();

    private static List<Lang> build() {
        List<Lang> l = new ArrayList<>();
        l.add(new Lang("si",   "sin", "SI", "සිංහල",          "Sinhala",            "sinhala", false, 42));
        l.add(new Lang("ta",   "tam", "TA", "தமிழ்",            "Tamil",              "indic",   false, 38));
        l.add(new Lang("hi",   "hin", "HI", "हिन्दी",             "Hindi",              "indic",   false, 38));
        l.add(new Lang("en",   "eng", "EN", "English",          "English",            "latin",   false, 42));
        l.add(new Lang("es",   "spa", "ES", "Español",          "Spanish",            "latin",   false, 42));
        l.add(new Lang("fr",   "fre", "FR", "Français",         "French",             "latin",   false, 42));
        l.add(new Lang("ja",   "jpn", "JA", "日本語",            "Japanese",           "cjk",     false, 18));
        l.add(new Lang("ko",   "kor", "KO", "한국어",            "Korean",             "cjk",     false, 18));
        l.add(new Lang("zh-CN","chi", "ZH", "中文",              "Chinese (Simplified)","cjk",    false, 18));
        l.add(new Lang("ar",   "ara", "AR", "العربية",          "Arabic",             "arabic",  true,  38));
        l.add(new Lang("ur",   "urd", "UR", "اردو",             "Urdu",               "arabic",  true,  38));
        l.add(new Lang("de",   "ger", "DE", "Deutsch",          "German",             "latin",   false, 42));
        l.add(new Lang("ru",   "rus", "RU", "Русский",          "Russian",            "cyrillic",   false, 40));
        l.add(new Lang("pt",   "por", "PT", "Português",        "Portuguese",         "latin",   false, 42));
        l.add(new Lang("th",   "tha", "TH", "ไทย",              "Thai",               "thaat",   false, 24));
        l.add(new Lang("id",   "ind", "ID", "Bahasa Indonesia", "Indonesian",         "latin",   false, 42));
        l.add(new Lang("tr",   "tur", "TR", "Türkçe",           "Turkish",            "latin",   false, 42));
        l.add(new Lang("vi",   "vie", "VI", "Tiếng Việt",       "Vietnamese",         "latin",   false, 42));
        return java.util.Collections.unmodifiableList(l);
    }

    public static final String DEFAULT = "si";

    /** The 8 first-class chips (the "+ More" row opens the full list). */
    public static List<Lang> chips() {
        List<Lang> c = new ArrayList<>();
        for (Lang l : ALL) {
            if ("si".equals(l.code) || "ta".equals(l.code) || "hi".equals(l.code) || "en".equals(l.code)
                    || "es".equals(l.code) || "ja".equals(l.code) || "ko".equals(l.code) || "ar".equals(l.code)) {
                c.add(l);
            }
        }
        return c;
    }

    /** Registry lookup — null for unknown codes (callers fall back to si). */
    public static Lang of(String code) {
        if (code == null) return null;
        String c = code.trim();
        for (Lang l : ALL) if (l.code.equals(c)) return l;
        return null;
    }

    public static boolean isKnown(String code) { return of(code) != null; }

    public static boolean isRtl(String code) {
        Lang l = of(code);
        return l != null && l.rtl;
    }

    /** The bundled NotoSansSinhala applies ONLY to si — every other language
     *  uses the system font stack (its script is covered by the OS fonts). */
    public static boolean useBundledSinhalaFont(String code) {
        return "si".equals(code);
    }

    /** Chip label for a code ("si" → "සිංහල"; unknown → the code itself). */
    public static String nativeOf(String code) {
        Lang l = of(code);
        return l != null ? l.nativeName : code;
    }

    /** English name for status lines ("si" → "Sinhala"). */
    public static String englishOf(String code) {
        Lang l = of(code);
        return l != null ? l.english : code;
    }

    /** Max chars/line for the subtitle view's wrap budget. */
    public static int maxCharsOf(String code) {
        Lang l = of(code);
        return l != null ? l.maxChars : 42;
    }
}
