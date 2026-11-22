package lk.techworld.anantplay.sicast;

import android.content.SharedPreferences;

import java.util.ArrayList;
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
 * v3.2.0 — THE DEEP CUSTOMIZATION EXPANSION (Mornye + Apple polish):
 *   · Look & Feel: 8 curated accents + a CUSTOM #RRGGBB hex accent, card
 *     corner radius (sharp|rounded|apple-pill), UI density adds "spacious",
 *     UI language unchanged;
 *   · Subtitle Studio: font FAMILY (noto|system|serif), line spacing
 *     (1.0–1.5), background style gains "full box", subtitle ENGINE priority
 *     (human→pa→free|human→free|free|worker-only), AUTO-TRANSLATE non-Sinhala
 *     tracks toggle, auto .zip extract toggle;
 *   · Player: default playback SPEED (0.5–2.0), aspect MODE (fit|fill|zoom),
 *     seek step adds 30 s, HUD hide adds 8 s;
 *   · Web Player: the CUSTOM LINKS registry (add/edit/reorder/delete streaming
 *     site URLs — a JSON list under "webLinks", normalized per entry: https
 *     only, ≤64 chars name, ≤6 entries);
 *   · Free translator toggle ("freeTranslator") + on-URL subtitle loader
 *     policy.
 * Every new key carries its normalizer and round-trips in SettingsBackup. */

public final class AppPrefs {

    private AppPrefs() { }

    // ── keys ────────────────────────────────────────────────────────────────────
    public static final String K_THEME_MODE = "themeMode";           // system|dark|amoled|light
    public static final String K_DYNAMIC_COLOR = "dynamicColor";     // bool
    public static final String K_ACCENT = "accent";                  // 0..7
    public static final String K_ACCENT_HEX = "accentHex";           // v3.2.0: custom #RRGGBB ("" = preset)
    public static final String K_UI_LANG = "uiLang";                 // bi|si|en
    public static final String K_DENSITY = "layoutDensity";          // spacious|comfortable|compact
    public static final String K_CARD_RADIUS = "cardRadius";         // v3.2.0: sharp|rounded|apple-pill
    public static final String K_SHOW_WEB_TAB = "showWebTab";        // bool
    public static final String K_SHOW_QUICK_ACTIONS = "showQuickActions";  // v3.2.0: bool
    public static final String K_DEFAULT_LANG = "defaultLang";       // Langs registry code
    public static final String K_REGISTER = "registerOverride";      // null|spoken|formal
    public static final String K_AUTO_FETCH = "autoFetch";           // bool
    public static final String K_CINEMA_POLISH = "cinemaPolish";     // bool
    public static final String K_FONT_SP = "fontSp";                 // 14..40
    public static final String K_FONT_FAMILY = "fontFamily";         // v3.2.0: noto|system|serif
    public static final String K_SUB_COLOR = "subColor";             // 0..5 (6 presets)
    public static final String K_SUB_BG = "subBgStyle";              // pill|outline|box|shadow
    public static final String K_SUB_BG_OPACITY = "subBgOpacity";    // 0..95
    public static final String K_SUB_BOLD = "subBold";               // bool
    public static final String K_SUB_BOTTOM_PCT = "subBottomPct";    // 2..35
    public static final String K_SUB_LINE_SPACING = "subLineSpacing"; // v3.2.0: 100..150 (per-mille-ish %)
    public static final String K_SUB_ENGINE = "subEngine";           // v3.2.0: human-pa-free|human-free|free|worker
    public static final String K_AUTO_TRANSLATE_NON_SI = "autoTranslateNonSi";  // v3.2.0: bool
    public static final String K_AUTO_ZIP = "autoZip";               // v3.2.0: bool (auto-extract picked/downloaded .zip)
    public static final String K_SYNC_STEP_MS = "subSyncStepMs";     // 100|250|500
    public static final String K_SEEK_STEP_MS = "seekStepMs";        // 5000|10000|15000|30000
    public static final String K_GESTURES = "gesturesOn";            // bool
    public static final String K_DOUBLE_TAP_SEEK = "doubleTapSeek";  // v3.2.0: bool
    public static final String K_AUTO_PIP = "autoPip";              // bool
    public static final String K_KEEP_SCREEN_ON = "keepScreenOn";    // bool
    public static final String K_AUTO_RESUME = "autoResume";         // bool
    public static final String K_HUD_HIDE_MS = "hudHideMs";          // 2000|4000|6000|8000
    public static final String K_PLAY_SPEED = "playSpeed";           // v3.2.0: per-mille (50..200 → 0.5x..2.0x)
    public static final String K_ASPECT_MODE = "aspectMode";         // v3.3.0: fit|fill|16:9|stretch
    public static final String K_SWIPE_BRIGHTNESS = "swipeBrightness";  // v3.3.0: bool (left-half vertical swipe)
    public static final String K_SWIPE_VOLUME = "swipeVolume";          // v3.3.0: bool (right-half vertical swipe)
    public static final String K_SWIPE_SEEK = "swipeSeek";              // v3.3.0: bool (horizontal swipe seek)
    public static final String K_LOCK_ON_START = "lockOnStart";         // v3.3.0: bool (start with the HUD locked)
    public static final String K_DOH_DIAG = "dohDiag";               // bool
    public static final String K_RECEIVER_NAME = "receiverName";     // free text (trimmed, ≤64)
    public static final String K_RECEIVER_PORT = "receiverPort";      // v3.2.0: 1024..65535 (8765 default)
    public static final String K_DLNASTART = "dlnaEnabled";          // bool
    public static final String K_MDNS = "mdnsEnabled";               // bool
    public static final String K_WORKER_URL = "workerUrl";           // https URL
    public static final String K_AUTO_UPDATE = "autoUpdate";         // bool
    public static final String K_AUTO_OPEN_SNIFFED = "autoOpenSniffed";  // bool (WebView)
    public static final String K_WEB_DESKTOP_UA = "webDesktopUa";    // bool (WebView)
    public static final String K_AD_BLOCK = "adBlock";               // v3.2.0: bool (WebView blocker)
    public static final String K_WEB_LINKS = "webLinks";             // v3.2.0: "name|url\n…" (≤8 entries)
    public static final String K_FREE_TRANSLATOR = "freeTranslator"; // v3.2.0: bool (Layer 3 on/off)

    /** Every key SettingsBackup carries (ordered — the export JSON is stable). */
    public static final List<String> BACKUP_KEYS = Arrays.asList(
        K_THEME_MODE, K_DYNAMIC_COLOR, K_ACCENT, K_ACCENT_HEX, K_UI_LANG, K_DENSITY, K_CARD_RADIUS,
        K_SHOW_WEB_TAB, K_SHOW_QUICK_ACTIONS,
        K_DEFAULT_LANG, K_REGISTER, K_AUTO_FETCH, K_CINEMA_POLISH, K_FONT_SP, K_FONT_FAMILY,
        K_SUB_COLOR, K_SUB_BG, K_SUB_BG_OPACITY, K_SUB_BOLD, K_SUB_BOTTOM_PCT, K_SUB_LINE_SPACING,
        K_SUB_ENGINE, K_AUTO_TRANSLATE_NON_SI, K_AUTO_ZIP,
        K_SYNC_STEP_MS, K_SEEK_STEP_MS, K_GESTURES, K_DOUBLE_TAP_SEEK, K_AUTO_PIP, K_KEEP_SCREEN_ON,
        K_AUTO_RESUME, K_HUD_HIDE_MS, K_PLAY_SPEED, K_ASPECT_MODE,
        K_SWIPE_BRIGHTNESS, K_SWIPE_VOLUME, K_SWIPE_SEEK, K_LOCK_ON_START,
        K_DOH_DIAG, K_RECEIVER_NAME, K_RECEIVER_PORT, K_DLNASTART, K_MDNS,
        K_WORKER_URL, K_AUTO_UPDATE, K_AUTO_OPEN_SNIFFED, K_WEB_DESKTOP_UA,
        K_AD_BLOCK, K_WEB_LINKS, K_FREE_TRANSLATOR);

    // ── the normalizers (pure — Mornye's normalizeXxx pattern) ─────────────────

    public static final String THEME_SYSTEM = "system", THEME_DARK = "dark",
        THEME_AMOLED = "amoled", THEME_LIGHT = "light";
    public static final String UI_BI = "bi", UI_SI = "si", UI_EN = "en";
    public static final String DENSITY_SPACIOUS = "spacious", DENSITY_COMFORTABLE = "comfortable", DENSITY_COMPACT = "compact";
    public static final String RADIUS_SHARP = "sharp", RADIUS_ROUNDED = "rounded", RADIUS_PILL = "apple-pill";
    public static final String BG_PILL = "pill", BG_OUTLINE = "outline", BG_BOX = "box", BG_SHADOW = "shadow";
    public static final String FONT_NOTO = "noto", FONT_SYSTEM = "system", FONT_SERIF = "serif";
    public static final String ENGINE_HUMAN_PA_FREE = "human-pa-free", ENGINE_HUMAN_FREE = "human-free",
        ENGINE_FREE = "free", ENGINE_WORKER = "worker";
    public static final String ASPECT_FIT = "fit", ASPECT_FILL = "fill", ASPECT_ZOOM = "zoom",
        ASPECT_169 = "16:9", ASPECT_STRETCH = "stretch";

    public static String normalizeThemeMode(String v) {
        return THEME_DARK.equals(v) || THEME_AMOLED.equals(v) || THEME_LIGHT.equals(v)
            || THEME_SYSTEM.equals(v) ? v : THEME_DARK;             // Mornye Slate default
    }

    public static String normalizeUiLang(String v) { return UI_SI.equals(v) || UI_EN.equals(v) ? v : UI_BI; }

    public static String normalizeDensity(String v) {
        return DENSITY_SPACIOUS.equals(v) || DENSITY_COMPACT.equals(v) ? v : DENSITY_COMFORTABLE;
    }

    public static String normalizeCardRadius(String v) {
        return RADIUS_SHARP.equals(v) || RADIUS_PILL.equals(v) ? v : RADIUS_ROUNDED;
    }

    public static int normalizeAccent(int v) { return v >= 0 && v < ThemeEngine.ACCENTS.length ? v : 0; }

    /** v3.2.0 — the custom accent: "#RRGGBB" (or a bare RRGGBB) or "" (use the preset). */
    public static String normalizeAccentHex(String v) {
        if (v == null) return "";
        String t = v.trim();
        if (t.isEmpty()) return "";
        String hex = t.startsWith("#") ? t.substring(1) : t;
        if (hex.length() != 6) return "";
        return hex.matches("[0-9a-fA-F]{6}") ? hex.toLowerCase(java.util.Locale.ROOT) : "";
    }

    /** The custom accent as an ARGB int (0 when absent). */
    public static int accentHexColor(String normalized) {
        if (normalized == null || normalized.length() != 6) return 0;
        try {
            return (int) ((0xFFL << 24) | Long.parseLong(normalized, 16));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String normalizeFontFamily(String v) {
        return FONT_SYSTEM.equals(v) || FONT_SERIF.equals(v) ? v : FONT_NOTO;
    }

    /** v3.3.0 — the subtitle size range the Cinema Player promises: 12..36 sp
     *  (was 14..40; the 22 sp default is what made v3.2.0's subtitles "oversized"). */
    public static int normalizeFontSp(int v) { return clampTo(v, 12, 36); }

    public static int normalizeSubColor(int v) { return clampTo(v, 0, ThemeEngine.SUB_COLORS.length - 1); }

    public static int normalizeSubBgOpacity(int v) { return clampTo(v, 0, 95); }

    public static int normalizeSubBottomPct(int v) { return clampTo(v, 2, 35); }

    /** v3.2.0 — line spacing 1.0–1.5 stored as 100..150. */
    public static int normalizeSubLineSpacing(int v) { return clampTo(v, 100, 150); }

    public static String normalizeSubBg(String v) {
        return BG_OUTLINE.equals(v) || BG_BOX.equals(v) || BG_SHADOW.equals(v) ? v : BG_PILL;
    }

    /** v3.2.0 — the subtitle engine priority whitelist. */
    public static String normalizeSubEngine(String v) {
        return ENGINE_HUMAN_FREE.equals(v) || ENGINE_FREE.equals(v) || ENGINE_WORKER.equals(v) ? v : ENGINE_HUMAN_PA_FREE;
    }

    public static long normalizeSyncStep(long v) {
        return v == 100 || v == 500 ? v : 250;
    }

    public static long normalizeSeekStep(long v) {
        return v == 5000 || v == 15000 || v == 30000 ? v : 10000;
    }

    public static long normalizeHudHide(long v) {
        return v == 2000 || v == 6000 || v == 8000 ? v : 4000;
    }

    /** v3.2.0 — default playback speed stored as per-mille (50..200). */
    public static int normalizePlaySpeed(int v) { return clampTo(v, 50, 200); }

    /** v3.3.0 — fit | fill | 16:9 | stretch. "zoom" (v3.2.0's fill alias) stays valid so an
     *  old backup restores into the new model; anything unknown is the safe FIT. */
    public static String normalizeAspectMode(String v) {
        if (ASPECT_FILL.equals(v) || ASPECT_ZOOM.equals(v)) return ASPECT_FILL;
        if (ASPECT_169.equals(v) || ASPECT_STRETCH.equals(v)) return v;
        return ASPECT_FIT;
    }

    /** v3.2.0 — the receiver port override (1024..65535, 8765 default). */
    public static int normalizeReceiverPort(int v) {
        return v >= 1024 && v <= 65535 ? v : 8765;
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
     *  backup from carrying an http:// or junk value). */
    public static String normalizeWorkerUrl(String v) {
        if (v == null) return BridgeApi.DEFAULT_WORKER_URL;
        String t = v.trim();
        if (t.isEmpty()) return BridgeApi.DEFAULT_WORKER_URL;
        return t.startsWith("https://") ? t : BridgeApi.DEFAULT_WORKER_URL;
    }

    // ── v3.2.0 — the CUSTOM LINKS registry (the editable Web-Player quick sites) ─

    /** One custom link (immutable). */
    public static final class Link {
        public final String name;
        public final String url;
        Link(String name, String url) { this.name = name; this.url = url; }
    }

    /** Parse the stored "name|url\nname|url" list → clean entries (https-only
     *  URLs, trimmed names, ≤8 entries, deduped by URL). */
    public static List<Link> parseLinks(String stored) {
        List<Link> out = new ArrayList<>();
        if (stored == null || stored.trim().isEmpty()) return out;
        for (String line : stored.split("\n")) {
            if (out.size() >= 8) break;
            String t = line.trim();
            if (t.isEmpty()) continue;
            int bar = t.indexOf('|');
            if (bar < 0) continue;
            String name = t.substring(0, bar).trim();
            String url = t.substring(bar + 1).trim();
            if (name.isEmpty() || name.length() > 64) continue;
            if (!(url.startsWith("https://") || url.startsWith("http://"))) continue;
            if (url.length() > 300) continue;
            boolean dup = false;
            for (Link l : out) if (l.url.equals(url)) { dup = true; break; }
            if (!dup) out.add(new Link(name, url));
        }
        return out;
    }

    /** Serialize links back to the "name|url\n…" storage form (round-trips parseLinks). */
    public static String serializeLinks(List<Link> links) {
        StringBuilder sb = new StringBuilder();
        if (links != null) {
            for (Link l : links) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(l.name.replace('|', ' ')).append('|').append(l.url);
            }
        }
        return sb.toString();
    }

    /** Boundary clamping (the slider contract): a corrupt value snaps to the
     *  nearest bound — never to a default that could surprise the user. */
    public static int clampTo(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    /** One validated value for ONE key from a raw map (SettingsBackup's import
     *  core): returns the normalized value or null when the key/value is not
     *  acceptable. String/Number/Boolean inputs are all tolerated. */
    public static Object normalizeValue(String key, Object raw) {
        if (key == null || raw == null) return null;
        try {
            switch (key) {
                case K_THEME_MODE: return normalizeThemeMode(String.valueOf(raw));
                case K_UI_LANG: return normalizeUiLang(String.valueOf(raw));
                case K_DENSITY: return normalizeDensity(String.valueOf(raw));
                case K_CARD_RADIUS: return normalizeCardRadius(String.valueOf(raw));
                case K_ACCENT: return normalizeAccent(Integer.parseInt(String.valueOf(raw).trim()));
                case K_ACCENT_HEX: return normalizeAccentHex(String.valueOf(raw));
                case K_FONT_SP: return normalizeFontSp(Integer.parseInt(String.valueOf(raw).trim()));
                case K_FONT_FAMILY: return normalizeFontFamily(String.valueOf(raw));
                case K_SUB_COLOR: return normalizeSubColor(Integer.parseInt(String.valueOf(raw).trim()));
                case K_SUB_BG_OPACITY: return normalizeSubBgOpacity(Integer.parseInt(String.valueOf(raw).trim()));
                case K_SUB_BOTTOM_PCT: return normalizeSubBottomPct(Integer.parseInt(String.valueOf(raw).trim()));
                case K_SUB_LINE_SPACING: return normalizeSubLineSpacing(Integer.parseInt(String.valueOf(raw).trim()));
                case K_SUB_BG: return normalizeSubBg(String.valueOf(raw));
                case K_SUB_ENGINE: return normalizeSubEngine(String.valueOf(raw));
                case K_ASPECT_MODE: return normalizeAspectMode(String.valueOf(raw));
                case K_PLAY_SPEED: return normalizePlaySpeed(Integer.parseInt(String.valueOf(raw).trim()));
                case K_RECEIVER_PORT: return normalizeReceiverPort(Integer.parseInt(String.valueOf(raw).trim()));
                case K_SYNC_STEP_MS: return normalizeSyncStep(Long.parseLong(String.valueOf(raw).trim()));
                case K_SEEK_STEP_MS: return normalizeSeekStep(Long.parseLong(String.valueOf(raw).trim()));
                case K_HUD_HIDE_MS: return normalizeHudHide(Long.parseLong(String.valueOf(raw).trim()));
                case K_REGISTER: return normalizeRegister(String.valueOf(raw));
                case K_RECEIVER_NAME: return normalizeReceiverName(String.valueOf(raw));
                case K_WORKER_URL: return normalizeWorkerUrl(String.valueOf(raw));
                case K_WEB_LINKS: {
                    String v = String.valueOf(raw);
                    return parseLinks(v).isEmpty() && v.trim().isEmpty() ? "" : serializeLinks(parseLinks(v));
                }
                case K_DEFAULT_LANG: {
                    String c = String.valueOf(raw);
                    return Langs.isKnown(c) ? c : null;
                }
                case K_DYNAMIC_COLOR: case K_SHOW_WEB_TAB: case K_SHOW_QUICK_ACTIONS: case K_AUTO_FETCH:
                case K_CINEMA_POLISH: case K_SUB_BOLD: case K_AUTO_TRANSLATE_NON_SI: case K_AUTO_ZIP:
                case K_GESTURES: case K_DOUBLE_TAP_SEEK: case K_AUTO_PIP: case K_KEEP_SCREEN_ON:
                case K_AUTO_RESUME: case K_DOH_DIAG: case K_DLNASTART: case K_MDNS:
                case K_AUTO_UPDATE: case K_AUTO_OPEN_SNIFFED: case K_WEB_DESKTOP_UA:
                case K_AD_BLOCK: case K_FREE_TRANSLATOR:
                    return raw instanceof Boolean ? raw
                        : Boolean.parseBoolean(String.valueOf(raw)) ? Boolean.TRUE : Boolean.FALSE;
                default: return null;                                 // unknown key → reject
            }
        } catch (Throwable t) {
            return null;                                              // unparsable → reject
        }
    }

    /** The whole-map import: every key normalized, unknown keys rejected, the
     *  result is a clean {key → normalized} map (never null). */
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
    public static String accentHex(SharedPreferences p) { return normalizeAccentHex(p.getString(K_ACCENT_HEX, "")); }
    public static String uiLang(SharedPreferences p) { return normalizeUiLang(p.getString(K_UI_LANG, UI_BI)); }
    public static boolean spacious(SharedPreferences p) { return DENSITY_SPACIOUS.equals(normalizeDensity(p.getString(K_DENSITY, DENSITY_COMFORTABLE))); }
    public static boolean compact(SharedPreferences p) { return DENSITY_COMPACT.equals(normalizeDensity(p.getString(K_DENSITY, DENSITY_COMFORTABLE))); }
    public static boolean showWebTab(SharedPreferences p) { return p.getBoolean(K_SHOW_WEB_TAB, true); }
    public static boolean showQuickActions(SharedPreferences p) { return p.getBoolean(K_SHOW_QUICK_ACTIONS, true); }
    /** v3.3.0 — the cinema default: 18 sp portrait (was 22) / 20 sp landscape. */
    public static int fontSp(SharedPreferences p) { return normalizeFontSp(p.getInt(K_FONT_SP, 18)); }

    /** The size actually rendered: +2 sp in landscape (cinema default 18 → 20), still
     *  clamped to the 12..36 range so a user's 36 never overflows. */
    public static int fontSpFor(SharedPreferences p, boolean landscape) {
        int base = fontSp(p);
        return normalizeFontSp(landscape ? base + 2 : base);
    }
    public static String fontFamily(SharedPreferences p) { return normalizeFontFamily(p.getString(K_FONT_FAMILY, FONT_NOTO)); }
    public static int subColor(SharedPreferences p) { return normalizeSubColor(p.getInt(K_SUB_COLOR, 0)); }
    public static String subBg(SharedPreferences p) { return normalizeSubBg(p.getString(K_SUB_BG, BG_PILL)); }
    public static int subBgOpacity(SharedPreferences p) { return normalizeSubBgOpacity(p.getInt(K_SUB_BG_OPACITY, 55)); }
    public static boolean subBold(SharedPreferences p) { return p.getBoolean(K_SUB_BOLD, true); }
    public static int subBottomPct(SharedPreferences p) { return normalizeSubBottomPct(p.getInt(K_SUB_BOTTOM_PCT, 10)); }
    public static int subLineSpacing(SharedPreferences p) { return normalizeSubLineSpacing(p.getInt(K_SUB_LINE_SPACING, 120)); }
    public static String subEngine(SharedPreferences p) { return normalizeSubEngine(p.getString(K_SUB_ENGINE, ENGINE_HUMAN_PA_FREE)); }
    public static boolean autoTranslateNonSi(SharedPreferences p) { return p.getBoolean(K_AUTO_TRANSLATE_NON_SI, true); }
    public static boolean autoZip(SharedPreferences p) { return p.getBoolean(K_AUTO_ZIP, true); }
    public static long syncStepMs(SharedPreferences p) { return normalizeSyncStep(p.getLong(K_SYNC_STEP_MS, 250)); }
    public static long seekStepMs(SharedPreferences p) { return normalizeSeekStep(p.getLong(K_SEEK_STEP_MS, 10000)); }
    public static boolean gestures(SharedPreferences p) { return p.getBoolean(K_GESTURES, true); }
    public static boolean doubleTapSeek(SharedPreferences p) { return p.getBoolean(K_DOUBLE_TAP_SEEK, true); }
    public static boolean autoPip(SharedPreferences p) { return p.getBoolean(K_AUTO_PIP, true); }
    public static boolean keepScreenOn(SharedPreferences p) { return p.getBoolean(K_KEEP_SCREEN_ON, true); }
    public static boolean autoResume(SharedPreferences p) { return p.getBoolean(K_AUTO_RESUME, true); }
    public static long hudHideMs(SharedPreferences p) { return normalizeHudHide(p.getLong(K_HUD_HIDE_MS, 4000)); }
    public static int playSpeedPm(SharedPreferences p) { return normalizePlaySpeed(p.getInt(K_PLAY_SPEED, 100)); }
    public static String aspectMode(SharedPreferences p) { return normalizeAspectMode(p.getString(K_ASPECT_MODE, ASPECT_FIT)); }
    /** v3.3.0 — the gesture toggles (all ON by default: the VLC contract). */
    public static boolean swipeBrightness(SharedPreferences p) { return p.getBoolean(K_SWIPE_BRIGHTNESS, true); }
    public static boolean swipeVolume(SharedPreferences p) { return p.getBoolean(K_SWIPE_VOLUME, true); }
    public static boolean swipeSeek(SharedPreferences p) { return p.getBoolean(K_SWIPE_SEEK, true); }
    public static boolean lockOnStart(SharedPreferences p) { return p.getBoolean(K_LOCK_ON_START, false); }
    public static boolean dohDiag(SharedPreferences p) { return p.getBoolean(K_DOH_DIAG, true); }
    public static boolean cinemaPolish(SharedPreferences p) { return p.getBoolean(K_CINEMA_POLISH, true); }
    public static String receiverName(SharedPreferences p) { return normalizeReceiverName(p.getString(K_RECEIVER_NAME, null)); }
    public static int receiverPort(SharedPreferences p) { return normalizeReceiverPort(p.getInt(K_RECEIVER_PORT, 8765)); }
    public static boolean dlnaEnabled(SharedPreferences p) { return p.getBoolean(K_DLNASTART, true); }
    public static boolean mdnsEnabled(SharedPreferences p) { return p.getBoolean(K_MDNS, true); }
    public static String workerUrl(SharedPreferences p) { return normalizeWorkerUrl(p.getString(K_WORKER_URL, BridgeApi.DEFAULT_WORKER_URL)); }
    public static boolean adBlock(SharedPreferences p) { return p.getBoolean(K_AD_BLOCK, true); }
    public static boolean freeTranslator(SharedPreferences p) { return p.getBoolean(K_FREE_TRANSLATOR, true); }
    public static List<Link> webLinks(SharedPreferences p) { return parseLinks(p.getString(K_WEB_LINKS, "")); }

    /** The UI-language picker (v3.1.0): Bilingual / සිංහල පමණයි / English Only —
     *  pure, so the lab proves each mode's rendering. */
    public static String pick(String mode, String si, String en) {
        if (UI_SI.equals(mode)) return si == null ? "" : si;
        if (UI_EN.equals(mode)) return en == null ? "" : en;
        String s = si == null ? "" : si, e = en == null ? "" : en;
        return s.isEmpty() ? e : (e.isEmpty() || s.equals(e) ? s : s + " · " + e);
    }
}
