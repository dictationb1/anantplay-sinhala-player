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
 *   · v3.2.0 — 8 CURATED ACCENT PRESETS (Mornye Violet, Ocean Blue, Emerald
 *     Green, Sunset Coral, Warm Amber, Rose Pink, Sky Cyan, Slate Teal) shown
 *     as selectable color dots + a CUSTOM #RRGGBB hex accent (AppPrefs.
 *     normalizeAccentHex validates; the custom wins when non-empty);
 *   · a derived accentDim (accent ⇢ background blend) for chips/borders so a
 *     single accent seeds a coherent, Material-3-Expressive-feeling surface;
 *   · v3.2.0 — the CARD CORNER RADIUS token (Mornye app_tokens.dart's
 *     radiusCard/radiusSheet translated: sharp 6dp | rounded 16dp |
 *     apple-pill 24dp) + 6 subtitle text colors.
 *
 * PURE MATH: every value is a deterministic function of (mode, dynamicSeed,
 * accentIdx, customHex) — the lab proves the palettes byte-for-byte, the AMOLED
 * blackness, and that dynamic color never repaints the surfaces. */

public final class ThemeEngine {

    private ThemeEngine() { }

    /** The 8 curated accents (Mornye's named-accent pattern as color dots). */
    public static final int[] ACCENTS = {
        0xFF8B5CF6,   // 0 Mornye Violet
        0xFF3B82F6,   // 1 Ocean Blue
        0xFF10B981,   // 2 Emerald Green
        0xFFFB7185,   // 3 Sunset Coral
        0xFFF59E0B,   // 4 Warm Amber
        0xFFEC4899,   // 5 Rose Pink
        0xFF38BDF8,   // 6 Sky Cyan (v3.2.0)
        0xFF14B8A6,   // 7 Slate Teal (v3.2.0)
    };
    public static final String[] ACCENT_NAMES = {
        "Mornye Violet", "Ocean Blue", "Emerald Green", "Sunset Coral", "Warm Amber", "Rose Pink",
        "Sky Cyan", "Slate Teal",
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
        public final int radiusCard, radiusSheet, radiusChip;   // v3.2.0 (dp)

        Palette(String mode, int bg, int card, int card2, int chip, int title, int text, int desc,
                int accent, int accentDim, int accentSoft, int ok, int warn, int err,
                int radiusCard, int radiusSheet, int radiusChip) {
            this.mode = mode;
            this.amoled = AppPrefs.THEME_AMOLED.equals(mode);
            this.light = AppPrefs.THEME_LIGHT.equals(mode);
            this.bg = bg; this.card = card; this.card2 = card2; this.chip = chip;
            this.title = title; this.text = text; this.desc = desc;
            this.accent = accent; this.accentDim = accentDim; this.accentSoft = accentSoft;
            this.ok = ok; this.warn = warn; this.err = err;
            this.radiusCard = radiusCard; this.radiusSheet = radiusSheet; this.radiusChip = radiusChip;
        }
    }

    /** "System" mode → the concrete mode for this moment: night → Dark (the
     *  AMOLED choice is always explicit, never system-guessed), day → Light.
     *  Pure — the Android caller supplies the night bit from its configuration. */
    public static String resolveSystemMode(boolean night) {
        return night ? AppPrefs.THEME_DARK : AppPrefs.THEME_LIGHT;
    }

    /**
     * v3.2.0 — the card-corner radius token (Mornye app_tokens.dart's
     * radiusBadge/Thumb/Cover/Control/Card/Sheet 6/8/12/16/20/28 family,
     * compressed to our three user-facing shapes).
     */
    public static int[] radii(String cardRadiusPref) {
        String v = AppPrefs.normalizeCardRadius(cardRadiusPref);
        if (AppPrefs.RADIUS_SHARP.equals(v)) return new int[]{6, 10, 4};      // sharp: badge/card/chip
        if (AppPrefs.RADIUS_PILL.equals(v)) return new int[]{24, 28, 16};     // the Apple pill
        return new int[]{16, 22, 10};                                          // rounded (default)
    }

    /**
     * THE palette decision.
     *
     * @param mode        dark|amoled|light (system already resolved by the caller)
     * @param dynamicSeed the wallpaper accent seed (ARGB) when dynamic color is
     *                    on AND supported; any value <= 0 falls back to the preset
     * @param accentIdx   the chosen preset index (clamped by AppPrefs)
     * @param customHex   v3.2.0 — the validated custom accent hex ("" = preset);
     *                    APP that AFTER dynamic color: a user's explicit custom
     *                    choice outranks the wallpaper seed (Mornye's own order).
     */
    public static Palette palette(String mode, int dynamicSeed, int accentIdx, String customHex) {
        return palette(mode, dynamicSeed, accentIdx, customHex, null);
    }

    /**
     * v3.3.1 — THE CARD-RADIUS FIX.
     *
     * <p>v3.2.0 exposed a "Card corner radius" setting (Sharp 6dp / Rounded 16dp /
     * Apple Pill 24dp) and then painted every card with a hard-coded {@code dp(16)}
     * — the palette called {@code radii(null)}, so the token was ALWAYS the default
     * and the setting had no visible effect anywhere in the app. The radius now
     * flows from the user's preference into the palette, and every card paints
     * {@code P.radiusCard}, so tapping the row changes the UI immediately (the
     * Customize page recreates the activity on every theme/radius change).
     */
    public static Palette palette(String mode, int dynamicSeed, int accentIdx, String customHex,
                                  String cardRadiusPref) {
        int preset = ACCENTS[AppPrefs.normalizeAccent(accentIdx)];
        int custom = AppPrefs.accentHexColor(AppPrefs.normalizeAccentHex(customHex));
        // ARGB ints are NEGATIVE when the alpha byte is 0xFF — the seed is "on"
        // whenever it is a real color (nonzero), not when it is positive.
        int accent = dynamicSeed != 0 ? (dynamicSeed | 0xFF000000) : preset;
        if (custom != 0) accent = custom;                        // the explicit user choice wins
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
        int[] r = radii(cardRadiusPref);   // v3.3.1 — the user's radius choice, honoured
        return new Palette(m, bg, card, card2, chip, title, text, desc, accent, accentDim, accentSoft, ok, warn, err,
            r[0], r[1], r[2]);
    }

    /** The v3.1-compatible two-arg shape (kept for the lab's existing proofs). */
    public static Palette palette(String mode, int dynamicSeed, int accentIdx) {
        return palette(mode, dynamicSeed, accentIdx, "");
    }

    /** Linear ARGB blend: mix(color, into, mixAmount) — mixAmount 0 = color, 1 = into. */
    public static int blend(int color, int into, float amount) {
        float a = Math.max(0f, Math.min(1f, amount));
        int r = Math.round(((color >> 16) & 0xFF) * (1 - a) + ((into >> 16) & 0xFF) * a);
        int g = Math.round(((color >> 8) & 0xFF) * (1 - a) + ((into >> 8) & 0xFF) * a);
        int b = Math.round((color & 0xFF) * (1 - a) + (into & 0xFF) * a);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** The six subtitle text colors of the Styling Studio (v3.2.0: 4→6 presets). */
    public static final int[] SUB_COLORS = {
        0xFFFFFFFF,   // Pure White
        0xFFFFE899,   // Warm Yellow
        0xFF9BE8FF,   // Soft Cyan
        0xFFA7F3C5,   // Mint Green
        0xFFF9A8D4,   // Soft Pink (v3.2.0)
        0xFFFDE68A,   // Subtle Gold (v3.2.0)
    };
    public static final String[] SUB_COLOR_NAMES = {
        "Pure White · සුදු", "Warm Yellow · කහ", "Soft Cyan · නිල්", "Mint Green · කොළ",
        "Soft Pink · රෝස", "Subtle Gold · රන්",
    };

    /** The translucency color of the subtitle pill (alpha = opacity 0..95%). */
    public static int pillColor(int opacityPct) {
        int a = Math.max(0, Math.min(95, opacityPct)) * 255 / 100;
        return (a << 24) | 0x000000;                            // pure black pill
    }
}
