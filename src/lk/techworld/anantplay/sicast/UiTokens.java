package lk.techworld.anantplay.sicast;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.view.View;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * v3.4.0 - THE TYPE SCALE, THE SPACING SCALE AND THE BILINGUAL HIERARCHY.
 *
 * <p>One source of truth for every dp, sp, radius and duration the UI uses, plus the two
 * Sinhala weights. Pure Java, zero dependencies, compiled by {@code javac} and dexed by {@code d8}
 * exactly like the rest of the app.
 *
 * <h3>The font architecture</h3>
 * <p>{@code assets/fonts/} now ships TWO real static instances cut from the variable original
 * (which carried a {@code wght} 100-900 axis): {@code NotoSansSinhala-Regular.ttf} at weight 400
 * and {@code NotoSansSinhala-SemiBold.ttf} at 600. Both keep the full GSUB/GPOS/GDEF shaping
 * tables and the ZWJ (U+200D) / ZWNJ (U+200C) code points, so
 * {@code \u0DC1\u0DCA\u200D\u0DBB\u0DD3} (&#x0DC1;&#x0DCA;&#x200D;&#x0DBB;&#x0DD3;) still shapes as one conjunct.
 * That is why bold Sinhala is now REAL bold instead of the framework's smeared faux bold.
 *
 * <p>Latin stays on the system sans (Roboto Flex on Android 16): bundling a Latin face would
 * cost hundreds of KB for zero visual gain on a device that already has a better one.
 */
public final class UiTokens {

    private UiTokens() { }

    // ── spacing (4dp base grid) ──────────────────────────────────────────────
    public static final int GAP_XS = 4;
    public static final int GAP_SM = 8;
    public static final int GAP_MD = 12;
    public static final int GAP_LG = 16;
    public static final int GAP_XL = 24;

    // ── radii ────────────────────────────────────────────────────────────────
    public static final int RADIUS_BADGE = 6;
    public static final int RADIUS_THUMB = 8;
    public static final int RADIUS_CONTROL = 12;
    public static final int RADIUS_FIELD = 14;
    public static final int RADIUS_CARD = 16;
    public static final int RADIUS_SHEET = 28;

    // ── motion ───────────────────────────────────────────────────────────────
    public static final int MOTION_FAST = 150;
    public static final int MOTION_MEDIUM = 250;
    public static final int MOTION_SLOW = 400;
    public static final int MOTION_PULSE = 2400;

    // ── the type scale (sp) ──────────────────────────────────────────────────
    public static final float HERO_TITLE = 28f;
    public static final float SCREEN_TITLE = 22f;
    public static final float SECTION_HEADER = 15f;
    public static final float CARD_TITLE = 15.5f;
    public static final float BODY = 14f;
    public static final float BODY_SECONDARY = 12.5f;
    public static final float CAPTION = 11.5f;
    public static final float OVERLINE = 10.5f;
    public static final float CHIP_LABEL = 12f;
    public static final float NUMERIC = 17f;
    public static final float MONO_LOG = 10.5f;
    public static final float NAV_LABEL = 10f;      // the floor: never below 10sp
    public static final float SUBTITLE_DEFAULT = 18f;

    // ── weights (the numeric weight the bundled font actually has) ───────────
    public static final int W_REGULAR = 400;
    public static final int W_SEMIBOLD = 600;

    // ── helpers ──────────────────────────────────────────────────────────────

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static final Map<String, Typeface> FONTS = new HashMap<String, Typeface>();

    /**
     * The Sinhala face at a real weight. Never returns null; never throws.
     *
     * <p>Falls back to the system font if the asset is missing (a corrupted install must not
     * crash the player) and caches the loaded Typeface per weight.
     */
    public static Typeface si(Context c, boolean semibold) {
        String key = semibold ? "si600" : "si400";
        Typeface cached = FONTS.get(key);
        if (cached != null) return cached;
        Typeface tf = null;
        String path = semibold ? "fonts/NotoSansSinhala-SemiBold.ttf" : "fonts/NotoSansSinhala-Regular.ttf";
        try {
            tf = Typeface.createFromAsset(c.getAssets(), path);
        } catch (Throwable t) {
            Diag.w("font", path + ": " + t);
        }
        if (tf == null) {
            try {                                   // one more chance: the other weight
                tf = Typeface.createFromAsset(c.getAssets(), "fonts/NotoSansSinhala-Regular.ttf");
            } catch (Throwable t) {
                Diag.w("font", "sinhala fallback: " + t);
            }
        }
        if (tf == null) tf = Typeface.SANS_SERIF;
        FONTS.put(key, tf);
        return tf;
    }

    /** The Sinhala family merged with a weight - the ONLY correct way (never faux bold). */
    public static Typeface si(Context c, int weight) {
        Typeface base = si(c, weight >= W_SEMIBOLD);
        // Typeface.create(family, style) merges family + weight the one way Android supports.
        return Typeface.create(base, weight >= W_SEMIBOLD ? Typeface.BOLD : Typeface.NORMAL);
    }

    /** Latin/UI face for a weight: system sans, no bundled file. */
    public static Typeface ui(int weight) {
        if (weight >= 700) return Typeface.create("sans-serif-black", Typeface.NORMAL);
        if (weight >= W_SEMIBOLD) return Typeface.create("sans-serif-medium", Typeface.NORMAL);
        return Typeface.SANS_SERIF;
    }

    /** Tabular numerals - every timestamp, every ms counter. */
    public static void tnum(TextView tv) {
        if (tv == null) return;
        try {
            tv.setFontFeatureSettings("tnum");
        } catch (Throwable ignored) { }
    }

    /** Applies one step of the scale to a TextView, keeping the caller's colour. */
    public static void text(TextView tv, float sp, int weight, float letterSpacing, boolean sinhala) {
        if (tv == null) return;
        tv.setTextSize(sp);
        tv.setTypeface(sinhala ? si(tv.getContext(), weight) : ui(weight));
        try {
            tv.setLetterSpacing(letterSpacing / sp);   // letterSpacing is em-based
        } catch (Throwable ignored) { }
    }

    public static void heroTitle(TextView tv)   { text(tv, HERO_TITLE, 700, -0.4f, false); }
    public static void screenTitle(TextView tv) { text(tv, SCREEN_TITLE, 700, -0.2f, false); }
    public static void sectionHeader(TextView tv) { text(tv, SECTION_HEADER, W_SEMIBOLD, 0.1f, false); }
    public static void cardTitle(TextView tv)   { text(tv, CARD_TITLE, W_SEMIBOLD, -0.1f, false); }
    public static void body(TextView tv)        { text(tv, BODY, W_REGULAR, 0f, false); }
    public static void bodySecondary(TextView tv) { text(tv, BODY_SECONDARY, W_REGULAR, 0f, false); }
    public static void caption(TextView tv)     { text(tv, CAPTION, W_REGULAR, 0.1f, false); }
    public static void overline(TextView tv)    { text(tv, OVERLINE, W_SEMIBOLD, 0.6f, false); }
    public static void chipLabel(TextView tv)   { text(tv, CHIP_LABEL, W_SEMIBOLD, 0.2f, false); }
    public static void numeric(TextView tv)     { text(tv, NUMERIC, 700, -0.3f, false); tnum(tv); }
    public static void monoLog(TextView tv)     { text(tv, MONO_LOG, W_REGULAR, 0f, false); tv.setTypeface(Typeface.MONOSPACE); }
    public static void navLabel(TextView tv)    { text(tv, NAV_LABEL, W_SEMIBOLD, 0.2f, false); }

    // ── the bilingual hierarchy ──────────────────────────────────────────────

    /** True when the UI should show two lines (Sinhala primary + English secondary). */
    public static boolean bilingual(SharedPreferences prefs) {
        return AppPrefs.UI_BI.equals(AppPrefs.uiLang(prefs));
    }

    /** True when the primary line should be Sinhala (BI and SI), false in English mode. */
    public static boolean sinhalaFirst(SharedPreferences prefs) {
        return !AppPrefs.UI_EN.equals(AppPrefs.uiLang(prefs));
    }

    /**
     * THE fix for the dot-wall. In BILINGUAL mode the row shows a Sinhala primary line and an
     * English secondary line; in SI or EN mode the row collapses to one line and the second
     * View is hidden (never left as an empty gap).
     *
     * <pre>
     * before:  "තේමාව · AMOLED · Dynamic Color · වර්ණ · භාෂාව · density"   // unreadable
     * after:   primary   "තේමාව"          secondary "Theme"
     * </pre>
     */
    public static void setBilingual(SharedPreferences prefs, TextView primary, TextView secondary,
                                    String si, String en) {
        boolean siFirst = sinhalaFirst(prefs);
        if (primary != null) primary.setText(siFirst ? si : en);
        if (secondary == null) return;
        if (bilingual(prefs)) {
            secondary.setText(siFirst ? en : si);
            secondary.setVisibility(View.VISIBLE);
        } else {
            secondary.setText("");
            secondary.setVisibility(View.GONE);
        }
    }

    /**
     * A single-language label for a control (chip, tab, segmented item). Controls never carry
     * two languages: the user picked one in Customize, and a control must fit on one line.
     */
    public static String label(SharedPreferences prefs, String si, String en) {
        return sinhalaFirst(prefs) ? si : en;
    }

    /** A one-line "both languages" hint for a tooltip/empty state (uses a real separator, not a dot). */
    public static String both(String si, String en) {
        if (si == null || si.isEmpty()) return en;
        if (en == null || en.isEmpty()) return si;
        return si + "  (" + en + ")";
    }

    /** Animation duration honouring the Motion toggle. */
    public static int motion(SharedPreferences prefs, int ms) {
        return AppPrefs.motionEnabled(prefs) ? ms : 0;
    }
}
