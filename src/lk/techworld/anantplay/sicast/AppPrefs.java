package lk.techworld.anantplay.sicast;

import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/* v3.1.0 — THE SETTINGS MODEL (the Mornye-inspired customization engine's data
 * half — github.com/mornyeapp/Mornye lib/models/theme_settings.dart translated
 * to pure Android Java: one place defines every key, its default and its
 * NORMALIZER (a clamp/whitelist that repairs any corrupt value on read, exactly
 * Mornye's normalizeXxx() functions), so a restored backup or a vendor-default
 * weird value can never break the UI.
 *
 * Pure Java for every rule (the lab round-trips the whole model); the thin
 * Android half reads them out of the existing "sicast" SharedPreferences. */

public final class AppPrefs {

    private AppPrefs() { }

    // ── keys ────────────────────────────────────────────────────────────────────
    public static final String K_THEME_MODE = "themeMode";           // system|dark|amoled|light
    public static final String K_DYNAMIC_COLOR = "dynamicColor";     // bool
    public static final String K_ACCENT = "accent";                  // 0..5
    public static final String K_UI_LANG = "uiLang";                 // bi|si|en
    public static final String K_DENSITY = "layoutDensity";          // comfortable|compact
    public static final String K_SHOW_WEB_TAB = "showWebTab";        // bool
    public static final String K_DEFAULT_LANG = "defaultLang";       // Langs registry code
    public static final String K_REGISTER = "registerOverride";      // null|spoken|formal
    public static final String K_AUTO_FETCH = "autoFetch";           // bool
    public static final String K_CINEMA_POLISH = "cinemaPolish";     // bool
    public static final String K_FONT_SP = "fontSp";                 // 14..36
    public static final String K_SUB_COLOR = "subColor";             // 0..3
    public static final String K_SUB_BG = "subBgStyle";              // pill|outline
    public static final String K_SUB_BG_OPACITY = "subBgOpacity";    // 0..90
    public static final String K_SUB_BOLD = "subBold";               // bool
    public static final String K_SUB_BOTTOM_PCT = "subBottomPct";    // 4..28
    public static final String K_SYNC_STEP_MS = "subSyncStepMs";     // 100|250|500
    public static final String K_SEEK_STEP_MS = "seekStepMs";        // 5000|10000|15000|30000
    public static final String K_GESTURES = "gesturesOn";            // bool
    public static final String K_AUTO_PIP = "autoPip";              // bool
    public static final String K_KEEP_SCREEN_ON = "keepScreenOn";    // bool
    public static final String K_AUTO_RESUME = "autoResume";         // bool
    public static final String K_HUD_HIDE_MS = "hudHideMs";          // 2000|4000|6000
    public static final String K_DOH_DIAG = "dohDiag";               // bool
    public static final String K_RECEIVER_NAME = "receiverName";     // free text (trimmed, ≤64)
    public static final String K_DLNASTART = "dlnaEnabled";          // bool
    public static final String K_MDNS = "mdnsEnabled";               // bool
    public static final String K_WORKER_URL = "workerUrl";           // https URL
    public static final String K_AUTO_UPDATE = "autoUpdate";         // bool
    public static final String K_AUTO_OPEN_SNIFFED = "autoOpenSniffed";  // bool (WebView)
    public static final String K_WEB_DESKTOP_UA = "webDesktopUa";    // bool (WebView)

    /** Every key SettingsBackup carries (ordered — the export JSON is stable). */
    public static final List<String> BACKUP_KEYS = Arrays.asList(
        K_THEME_MODE, K_DYNAMIC_COLOR, K_ACCENT, K_UI_LANG, K_DENSITY, K_SHOW_WEB_TAB,
        K_DEFAULT_LANG, K_REGISTER, K_AUTO_FETCH, K_CINEMA_POLISH, K_FONT_SP, K_SUB_COLOR,
        K_SUB_BG, K_SUB_BG_OPACITY, K_SUB_BOLD, K_SUB_BOTTOM_PCT, K_SYNC_STEP_MS,
        K_SEEK_STEP_MS, K_GESTURES, K_AUTO_PIP, K_KEEP_SCREEN_ON, K_AUTO_RESUME,
        K_HUD_HIDE_MS, K_DOH_DIAG, K_RECEIVER_NAME, K_DLNASTART, K_MDNS,
        K_WORKER_URL, K_AUTO_UPDATE, K_AUTO_OPEN_SNIFFED, K_WEB_DESKTOP_UA);

    // ── the normalizers (pure — Mornye's normalizeXxx pattern) ─────────────────

    public static final String THEME_SYSTEM = "system", THEME_DARK = "dark",
        THEME_AMOLED = "amoled", THEME_LIGHT = "light";
    public static final String UI_BI = "bi", UI_SI = "si", UI_EN = "en";
    public static final String DENSITY_COMFORTABLE = "comfortable", DENSITY_COMPACT = "compact";
    public static final String BG_PILL = "pill", BG_OUTLINE = "outline";

    public static String normalizeThemeMode(String v) {
        return THEME_DARK.equals(v) || THEME_AMOLED.equals(v) || THEME_LIGHT.equals(v)
            || THEME_SYSTEM.equals(v) ? v : THEME_DARK;             // Mornye Slate default
    }

    public static String normalizeUiLang(String v) { return UI_SI.equals(v) || UI_EN.equals(v) ? v : UI_BI; }

    public static String normalizeDensity(String v) {
        return DENSITY_COMPACT.equals(v) ? v : DENSITY_COMFORTABLE;
    }

    public static int normalizeAccent(int v) { return v >= 0 && v < ThemeEngine.ACCENTS.length ? v : 0; }

    public static int normalizeFontSp(int v) { return clampTo(v, 14, 36); }

    public static int normalizeSubColor(int v) { return clampTo(v, 0, 3); }

    public static int normalizeSubBgOpacity(int v) { return clampTo(v, 0, 90); }

    public static int normalizeSubBottomPct(int v) { return clampTo(v, 4, 28); }

    public static String normalizeSubBg(String v) { return BG_OUTLINE.equals(v) ? v : BG_PILL; }

    public static long normalizeSyncStep(long v) {
        return v == 100 || v == 500 ? v : 250;
    }

    public static long normalizeSeekStep(long v) {
        return v == 5000 || v == 15000 || v == 30000 ? v : 10000;
    }

    public static long normalizeHudHide(long v) {
        return v == 2000 || v == 6000 ? v : 4000;
    }

    public static String normalizeRegister(String v) {
        return "spoken".equals(v) || "formal".equals(v) ? v : null;
    }

    /** Receiver names travel on the wire (mDNS/SSDP): trim, strip control chars, cap. */
    public static String normalizeReceiverName(String v) {
        if (v == null) return "සිංහල Player";
        String t = v.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.isEmpty()) return "සිංහල Player";
        return t.length() > 64 ? t.substring(0, 64) : t;
    }

    /** Worker URL: https only (BridgeApi itself re-validates; here we keep the
     * backup from carrying an http:// or junk value). */
    public static String normalizeWorkerUrl(String v) {
        if (v == null) return BridgeApi.DEFAULT_WORKER_URL;
        String t = v.trim();
        if (t.isEmpty()) return BridgeApi.DEFAULT_WORKER_URL;
        return t.startsWith("https://") ? t : BridgeApi.DEFAULT_WORKER_URL;
    }

    /** Boundary clamping (the slider contract): a corrupt value snaps to the
     *  nearest bound — never to a default that could surprise the user. */
    public static int clampTo(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    /** One validated value for ONE key from a raw map (SettingsBackup's import
     * core): returns the normalized value or null when the key/value is not
     * acceptable. String/Number/Boolean inputs are all tolerated. */
    public static Object normalizeValue(String key, Object raw) {
        if (key == null || raw == null) return null;
        try {
            switch (key) {
                case K_THEME_MODE: return normalizeThemeMode(String.valueOf(raw));
                case K_UI_LANG: return normalizeUiLang(String.valueOf(raw));
                case K_DENSITY: return normalizeDensity(String.valueOf(raw));
                case K_ACCENT: return normalizeAccent(Integer.parseInt(String.valueOf(raw).trim()));
                case K_FONT_SP: return normalizeFontSp(Integer.parseInt(String.valueOf(raw).trim()));
                case K_SUB_COLOR: return normalizeSubColor(Integer.parseInt(String.valueOf(raw).trim()));
                case K_SUB_BG_OPACITY: return normalizeSubBgOpacity(Integer.parseInt(String.valueOf(raw).trim()));
                case K_SUB_BOTTOM_PCT: return normalizeSubBottomPct(Integer.parseInt(String.valueOf(raw).trim()));
                case K_SUB_BG: return normalizeSubBg(String.valueOf(raw));
                case K_SYNC_STEP_MS: return normalizeSyncStep(Long.parseLong(String.valueOf(raw).trim()));
                case K_SEEK_STEP_MS: return normalizeSeekStep(Long.parseLong(String.valueOf(raw).trim()));
                case K_HUD_HIDE_MS: return normalizeHudHide(Long.parseLong(String.valueOf(raw).trim()));
                case K_REGISTER: return normalizeRegister(String.valueOf(raw));
                case K_RECEIVER_NAME: return normalizeReceiverName(String.valueOf(raw));
                case K_WORKER_URL: return normalizeWorkerUrl(String.valueOf(raw));
                case K_DEFAULT_LANG: {
                    String c = String.valueOf(raw);
                    return Langs.isKnown(c) ? c : null;
                }
                case K_DYNAMIC_COLOR: case K_SHOW_WEB_TAB: case K_AUTO_FETCH: case K_CINEMA_POLISH:
                case K_SUB_BOLD: case K_GESTURES: case K_AUTO_PIP: case K_KEEP_SCREEN_ON:
                case K_AUTO_RESUME: case K_DOH_DIAG: case K_DLNASTART: case K_MDNS:
                case K_AUTO_UPDATE: case K_AUTO_OPEN_SNIFFED: case K_WEB_DESKTOP_UA:
                    return raw instanceof Boolean ? raw
                        : Boolean.parseBoolean(String.valueOf(raw)) ? Boolean.TRUE : Boolean.FALSE;
                default: return null;                                 // unknown key → reject
            }
        } catch (Throwable t) {
            return null;                                              // unparsable → reject
        }
    }

    /** The whole-map import: every key normalized, unknown keys rejected, the
     * result is a clean {key → normalized} map (never null). */
    public static Map<String, Object> normalizeMap(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) return out;
        for (String key : BACKUP_KEYS) {
            if (!raw.containsKey(key)) continue;
            Object v = normalizeValue(key, raw.get(key));
            if (v != null) out.put(key, v);
        }
        return out;
    }

    // ── the Android glue (thin readers with normalization on every access) ─────

    public static String themeMode(SharedPreferences p) { return normalizeThemeMode(p.getString(K_THEME_MODE, THEME_DARK)); }
    public static boolean dynamicColor(SharedPreferences p) { return p.getBoolean(K_DYNAMIC_COLOR, false); }
    public static int accent(SharedPreferences p) { return normalizeAccent(p.getInt(K_ACCENT, 0)); }
    public static String uiLang(SharedPreferences p) { return normalizeUiLang(p.getString(K_UI_LANG, UI_BI)); }
    public static boolean compact(SharedPreferences p) { return DENSITY_COMPACT.equals(normalizeDensity(p.getString(K_DENSITY, DENSITY_COMFORTABLE))); }
    public static boolean showWebTab(SharedPreferences p) { return p.getBoolean(K_SHOW_WEB_TAB, true); }
    public static int fontSp(SharedPreferences p) { return normalizeFontSp(p.getInt(K_FONT_SP, 22)); }
    public static int subColor(SharedPreferences p) { return normalizeSubColor(p.getInt(K_SUB_COLOR, 0)); }
    public static String subBg(SharedPreferences p) { return normalizeSubBg(p.getString(K_SUB_BG, BG_PILL)); }
    public static int subBgOpacity(SharedPreferences p) { return normalizeSubBgOpacity(p.getInt(K_SUB_BG_OPACITY, 55)); }
    public static boolean subBold(SharedPreferences p) { return p.getBoolean(K_SUB_BOLD, true); }
    public static int subBottomPct(SharedPreferences p) { return normalizeSubBottomPct(p.getInt(K_SUB_BOTTOM_PCT, 10)); }
    public static long syncStepMs(SharedPreferences p) { return normalizeSyncStep(p.getLong(K_SYNC_STEP_MS, 250)); }
    public static long seekStepMs(SharedPreferences p) { return normalizeSeekStep(p.getLong(K_SEEK_STEP_MS, 10000)); }
    public static boolean gestures(SharedPreferences p) { return p.getBoolean(K_GESTURES, true); }
    public static boolean autoPip(SharedPreferences p) { return p.getBoolean(K_AUTO_PIP, true); }
    public static boolean keepScreenOn(SharedPreferences p) { return p.getBoolean(K_KEEP_SCREEN_ON, true); }
    public static boolean autoResume(SharedPreferences p) { return p.getBoolean(K_AUTO_RESUME, true); }
    public static long hudHideMs(SharedPreferences p) { return normalizeHudHide(p.getLong(K_HUD_HIDE_MS, 4000)); }
    public static boolean dohDiag(SharedPreferences p) { return p.getBoolean(K_DOH_DIAG, true); }
    public static boolean cinemaPolish(SharedPreferences p) { return p.getBoolean(K_CINEMA_POLISH, true); }
    public static String receiverName(SharedPreferences p) { return normalizeReceiverName(p.getString(K_RECEIVER_NAME, null)); }
    public static boolean dlnaEnabled(SharedPreferences p) { return p.getBoolean(K_DLNASTART, true); }
    public static boolean mdnsEnabled(SharedPreferences p) { return p.getBoolean(K_MDNS, true); }
    public static String workerUrl(SharedPreferences p) { return normalizeWorkerUrl(p.getString(K_WORKER_URL, BridgeApi.DEFAULT_WORKER_URL)); }

    /** The UI-language picker (v3.1.0): Bilingual / සිංහල පමණයි / English Only —
     *  pure, so the lab proves each mode's rendering. */
    public static String pick(String mode, String si, String en) {
        if (UI_SI.equals(mode)) return si == null ? "" : si;
        if (UI_EN.equals(mode)) return en == null ? "" : en;
        String s = si == null ? "" : si, e = en == null ? "" : en;
        return s.isEmpty() ? e : (e.isEmpty() || s.equals(e) ? s : s + " · " + e);
    }
}
