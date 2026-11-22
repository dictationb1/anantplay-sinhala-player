package lk.techworld.anantplay.sicast;

/* v3.1.0 — THE NATIVE THEME ENGINE (the Mornye-inspired customization engine's
 * visual half — github.com/mornyeapp/Mornye lib/theme/* translated to pure
 * Android Java, ZERO new dependencies).
 *
 * Mornye's model (lib/models/theme_settings.dart + lib/theme/app_theme.dart):
 *   ThemeMode (system|dark|light) × useDynamicColor × seed/accent × useAmoled,
 *   where AMOLED paints the canvas #000000 but KEEPS a distinct container tier
 *   for navigation (app_theme.dart:285) and accents derive from the seed.
 * Ours:
 *   · MODE: System · Dark (Mornye Slate) · AMOLED Pure Black (#000000) · Light;
 *   · DYNAMIC COLOR (Material You, Android 12+): the wallpaper's
 *     system_accent1 seed replaces the preset accent (surfaces stay per-mode);
 *   · 6 CURATED ACCENT PRESETS (Mornye Violet, Ocean Blue, Emerald Green,
 *     Sunset Coral, Warm Amber, Rose Pink) shown as selectable color dots;
 *   · a derived accentDim (accent ⇢ background blend) for chips/borders so a
 *     single accent seeds a coherent, Material-3-Expressive-feeling surface.
 *
 * PURE MATH: every value is a deterministic function of (mode, dynamicSeed,
 * accentIdx) — the lab proves the palettes byte-for-byte, the AMOLED blackness,
 * and that dynamic color never repaints the surfaces. */

public final class ThemeEngine {

    private ThemeEngine() { }

    /** The 6 curated accents (Mornye's named-accent pattern as color dots). */
    public static final int[] ACCENTS = {
        0xFF8B5CF6,   // 0 Mornye Violet
        0xFF3B82F6,   // 1 Ocean Blue
        0xFF10B981,   // 2 Emerald Green
        0xFFFB7185,   // 3 Sunset Coral
        0xFFF59E0B,   // 4 Warm Amber
        0xFFEC4899,   // 5 Rose Pink
    };
    public static final String[] ACCENT_NAMES = {
        "Mornye Violet", "Ocean Blue", "Emerald Green", "Sunset Coral", "Warm Amber", "Rose Pink",
    };

    /** One resolved theme — every color the app's UI needs. */
    public static final class Palette {
        public final String mode;         // dark|amoled|light (system already resolved)
        public final boolean amoled;
        public final boolean light;
        public final int bg, card, card2, chip;
        public final int title, text, desc;
        public final int accent, accentDim, accentSoft;
        public final int ok, warn, err;

        Palette(String mode, int bg, int card, int card2, int chip, int title, int text, int desc,
                int accent, int accentDim, int accentSoft, int ok, int warn, int err) {
            this.mode = mode;
            this.amoled = AppPrefs.THEME_AMOLED.equals(mode);
            this.light = AppPrefs.THEME_LIGHT.equals(mode);
            this.bg = bg; this.card = card; this.card2 = card2; this.chip = chip;
            this.title = title; this.text = text; this.desc = desc;
            this.accent = accent; this.accentDim = accentDim; this.accentSoft = accentSoft;
            this.ok = ok; this.warn = warn; this.err = err;
        }
    }

    /** "System" mode → the concrete mode for this moment: night → Dark (the
     *  AMOLED choice is always explicit, never system-guessed), day → Light.
     *  Pure — the Android caller supplies the night bit from its configuration. */
    public static String resolveSystemMode(boolean night) {
        return night ? AppPrefs.THEME_DARK : AppPrefs.THEME_LIGHT;
    }

    /**
     * THE palette decision.
     *
     * @param mode        dark|amoled|light (system already resolved by the caller)
     * @param dynamicSeed the wallpaper accent seed (ARGB) when dynamic color is
     *                    on AND supported; any value <= 0 falls back to the preset
     * @param accentIdx   the chosen preset index (clamped by AppPrefs)
     */
    public static Palette palette(String mode, int dynamicSeed, int accentIdx) {
        int preset = ACCENTS[AppPrefs.normalizeAccent(accentIdx)];
        // ARGB ints are NEGATIVE when the alpha byte is 0xFF — the seed is "on"
        // whenever it is a real color (nonzero), not when it is positive.
        int accent = dynamicSeed != 0 ? (dynamicSeed | 0xFF000000) : preset;
        int accentDim, accentSoft, bg, card, card2, chip, title, text, desc;
        String m = AppPrefs.normalizeThemeMode(mode);
        if (AppPrefs.THEME_LIGHT.equals(m)) {
            bg = 0xFFF3F5FB; card = 0xFFFFFFFF; card2 = 0xFFEBEFF7; chip = 0xFFE4E9F5;
            title = 0xFF111827; text = 0xFF1F2937; desc = 0xFF64748B;
            accentDim = blend(accent, 0xFFFFFFFF, 0.72f);      // tinted, readable on white
            accentSoft = blend(accent, 0xFFFFFFFF, 0.86f);
        } else if (AppPrefs.THEME_AMOLED.equals(m)) {
            // AMOLED: PURE #000000 canvas, but a distinct near-black container
            // tier so cards/navigation stay visible (the Mornye lesson).
            bg = 0xFF000000; card = 0xFF0A0F1C; card2 = 0xFF070B14; chip = 0xFF141B2E;
            title = 0xFFF1F5F9; text = 0xFFE2E8F0; desc = 0xFF94A3B8;
            accentDim = blend(accent, 0xFF000000, 0.62f);
            accentSoft = blend(accent, 0xFF000000, 0.80f);
        } else {                                                // dark — Mornye Slate
            bg = 0xFF0B1020; card = 0xFF111A2E; card2 = 0xFF0E1526; chip = 0xFF1A2440;
            title = 0xFFF1F5F9; text = 0xFFE2E8F0; desc = 0xFF94A3B8;
            accentDim = blend(accent, 0xFF0B1020, 0.62f);
            accentSoft = blend(accent, 0xFF0B1020, 0.80f);
        }
        int ok = 0xFF22C55E, warn = 0xFFF59E0B, err = 0xFFEF4444;
        if (AppPrefs.THEME_LIGHT.equals(m)) { ok = 0xFF15803D; warn = 0xFFB45309; err = 0xFFB91C1C; }
        return new Palette(m, bg, card, card2, chip, title, text, desc, accent, accentDim, accentSoft, ok, warn, err);
    }

    /** Linear ARGB blend: mix(color, into, mixAmount) — mixAmount 0 = color, 1 = into. */
    public static int blend(int color, int into, float amount) {
        float a = Math.max(0f, Math.min(1f, amount));
        int r = Math.round(((color >> 16) & 0xFF) * (1 - a) + ((into >> 16) & 0xFF) * a);
        int g = Math.round(((color >> 8) & 0xFF) * (1 - a) + ((into >> 8) & 0xFF) * a);
        int b = Math.round((color & 0xFF) * (1 - a) + (into & 0xFF) * a);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** The four subtitle text colors of the Styling Studio (pure). */
    public static final int[] SUB_COLORS = {
        0xFFFFFFFF,   // Pure White
        0xFFFFE899,   // Warm Yellow
    0xFF9BE8FF,   // Soft Cyan
        0xFFA7F3C5,   // Mint Green
    };
    public static final String[] SUB_COLOR_NAMES = {
        "Pure White · සුදු", "Warm Yellow · කහ", "Soft Cyan · නිල්", "Mint Green · කොළ",
    };

    /** The translucency color of the subtitle pill (alpha = opacity 0..90%). */
    public static int pillColor(int opacityPct) {
        int a = Math.max(0, Math.min(90, opacityPct)) * 255 / 100;
        return (a << 24) | 0x000000;                            // pure black pill
    }
}
