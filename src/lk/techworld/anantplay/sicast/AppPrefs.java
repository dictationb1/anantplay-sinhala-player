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
    /** v3.4.1 — the first-time 3-step Quick-Start banner was dismissed ("Got it"). */
    public static final String K_QUICKSTART_DONE = "quickstartDone";
    /**
     * v3.4.3 — the 5-stage first-launch onboarding state.
     *
     * <p>DELIBERATELY **NOT** IN {@link #BACKUP_KEYS}: onboarding completion and the
     * wizard's stage describe THIS DEVICE's setup and permission state. Exporting them
     * would let a restore on another phone claim a permission it never had — the app
     * always re-checks real permissions instead. `lab/verify_ui_completeness.py`
     * carries the explicit exclusion list.
     */
    public static final String K_ONBOARDING_DONE = "onboardingDone";
    public static final String K_ONBOARDING_STAGE = "onboardingStage";
    /**
     * v3.4.6.c — THE ONBOARDING VERSION INT. v3.4.6.a persisted
     * onboardingDone=true with no stamp, so the v3.4.6.b BOOL gate silently
     * skipped every upgrader's new 6-slide wizard (real-phone complaint #4).
     * The shell shows the wizard while the stamped version is &lt; 2:
     * 0 = never run, 1 = a legacy v3.4.6.a finish, 2 = the current wizard.
     * Like DONE/STAGE it is DEVICE STATE (never backed up) — a restore onto
     * a fresh device must re-run the guide.
     */
    public static final String K_ONBOARDING_VERSION = "onboardingVersion";
    /**
     * v3.4.6.b — the Sources & Index Helper's saved URL (onboarding Slide 6).
     *
     * <p>The official AnantPlay app (me.champdev.weblock) exposes NO exported
     * deep-link/Intent for adding an index (SourcesActivity is exported=false,
     * the manifest has no VIEW filters — audited against AnantPlay/android
     * main), so the helper's contract is: save the user's favorite index URL
     * here (a normal BACKED-UP key), and offer 1-tap Copy + Open AnantPlay.
     */
    public static final String K_INDEX_URL = "indexUrl";
    /** The helper's out-of-the-box default (AnantPlay's own index feed). */
    public static final String DEFAULT_INDEX_URL =
        "https://raw.githubusercontent.com/AnantPlay/index/main/index.json";
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

    // ══ v3.4.0 - Mornye-grade shell, surface, subtitle studio, browser, player ══
    /** Surface style: flat (default) or glass (translucent + hairline). */
    public static final String K_SURFACE_STYLE = "surfaceStyle";        // flat|glass
    /** Hairline alpha on cards, 0..60 (percent). */
    public static final String K_BORDER_ALPHA = "borderAlpha";          // 0..60
    /** Bottom navigation shape: docked full-width bar or a floating pill. */
    public static final String K_BOTTOM_BAR_STYLE = "bottomBarStyle";   // docked|floating
    /** Show the text label under each bottom-bar icon. */
    public static final String K_BOTTOM_BAR_LABELS = "bottomBarLabels"; // bool
    /** Show the Troubleshoot tab. */
    public static final String K_SHOW_TROUBLE_TAB = "showTroubleTab";   // bool
    /** Master motion switch (tab pill slide, press scale). */
    public static final String K_MOTION = "motionEnabled";              // bool
    /** Accent glow on the hero card + active tab. */
    public static final String K_ACCENT_GLOW = "accentGlow";            // bool
    /** Mornye glass fill strength, percent 10..95 (75 default). */
    public static final String K_GLASS_CLARITY = "glassClarity";
    /** Show the light-catching glass rim on compatible surfaces. */
    public static final String K_GLASS_OUTLINES = "glassOutlines";
    /** Wrap action glyphs in glass medallions. */
    public static final String K_ICON_GLASS = "iconGlass";
    /** App chrome family: modern|system|rounded|monospace. */
    public static final String K_UI_FONT = "uiFont";
    /** Explicit subtitle text colour ("" = use the preset index). */
    public static final String K_SUB_COLOR_HEX = "subColorHex";         // ""|rrggbb
    /** Subtitle outline / shadow radius in dp, 0..6. */
    public static final String K_SUB_OUTLINE = "subOutline";            // 0..6
    /** Subtitle drop shadow on/off. */
    public static final String K_SUB_SHADOW = "subShadow";              // bool
    /** Browser omnibar position: bottom (default, reachable) or top. */
    public static final String K_OMNIBAR_POS = "omnibarPos";            // top|bottom
    /** Speed-dial grid columns. */
    public static final String K_SPEEDDIAL_COLS = "speedDialCols";      // 2|3|4
    /** Omnibar search engine. */
    public static final String K_SEARCH_ENGINE = "searchEngine";        // ddg|google|startpage|brave
    /** Custom user-agent (overrides both mobile and desktop presets). */
    public static final String K_WEB_CUSTOM_UA = "webCustomUa";         // ""
    /** Inject color-scheme:dark into pages. */
    public static final String K_READER_DARK = "readerDark";            // bool
    /** Show real favicons in the speed dial (else coloured monograms). */
    public static final String K_FAVICONS = "favicons";                 // bool
    /** Show the blocked-request count badge on the shield. */
    public static final String K_SHOW_BLOCK_COUNT = "showBlockCount";   // bool
    /** Player default orientation. */
    public static final String K_ORIENTATION = "orientation";           // auto|landscape|portrait
    /** Preferred external player. */
    public static final String K_EXTERNAL_PLAYER = "externalPlayer";    // vlc|mx|mpv|chooser
    /** Volume boost 0..150 percent (player.setVolume - no FFmpeg). */
    public static final String K_VOLUME_BOOST = "volumeBoost";          // 0..150
    /** Sleep timer: 0 off, 900000/1800000/3600000 ms, -1 end-of-video. */
    public static final String K_SLEEP_TIMER_MS = "sleepTimerMs";       // -1|0|900000|1800000|3600000
    /** A version the user asked to skip in the updater. */
    public static final String K_SKIP_VERSION = "skipVersion";          // ""
    /** Light-weight browser tabs: one URL per line (single renderer, no OOM). */
    public static final String K_WEB_TABS = "webTabs";                  // "url\nurl"

    // ── v3.4.2 — MIDNIGHT NIGHT DIMMER ─────────────────────────────────────────
    /** The dark-room filter: dims the window AND paints a non-touch-blocking overlay. */
    public static final String K_MIDNIGHT_DIMMER = "midnightDimmer";   // bool
    /** How much light the dimmer removes: 10..70 percent. */
    public static final String K_MIDNIGHT_DIM_PCT = "midnightDimPct";  // 10..70

    /** Every key SettingsBackup carries (ordered — the export JSON is stable). */
    /**
     * v3.4.3 — keys that must NEVER travel in a backup (device/permission state).
     * They are the documented exception to "every pref is backed up".
     */
    public static final List<String> DEVICE_STATE_KEYS = Arrays.asList(
        K_ONBOARDING_DONE, K_ONBOARDING_STAGE, K_ONBOARDING_VERSION);

    public static final List<String> BACKUP_KEYS = Arrays.asList(
        K_THEME_MODE, K_DYNAMIC_COLOR, K_ACCENT, K_ACCENT_HEX, K_UI_LANG, K_DENSITY, K_CARD_RADIUS,
        K_SHOW_WEB_TAB, K_SHOW_QUICK_ACTIONS,
        K_DEFAULT_LANG, K_REGISTER, K_AUTO_FETCH, K_CINEMA_POLISH, K_FONT_SP, K_FONT_FAMILY,
        K_SUB_COLOR, K_SUB_BG, K_SUB_BG_OPACITY, K_SUB_BOLD, K_SUB_BOTTOM_PCT, K_SUB_LINE_SPACING,
        K_SUB_ENGINE, K_AUTO_TRANSLATE_NON_SI, K_AUTO_ZIP,
        K_SYNC_STEP_MS, K_SEEK_STEP_MS, K_GESTURES, K_DOUBLE_TAP_SEEK, K_AUTO_PIP, K_KEEP_SCREEN_ON,
        K_AUTO_RESUME, K_HUD_HIDE_MS, K_PLAY_SPEED, K_ASPECT_MODE,
        K_SWIPE_BRIGHTNESS, K_SWIPE_VOLUME, K_SWIPE_SEEK, K_LOCK_ON_START, K_QUICKSTART_DONE,
        K_DOH_DIAG, K_RECEIVER_NAME, K_RECEIVER_PORT, K_DLNASTART, K_MDNS,
        K_WORKER_URL, K_AUTO_UPDATE, K_AUTO_OPEN_SNIFFED, K_WEB_DESKTOP_UA,
        K_AD_BLOCK, K_WEB_LINKS, K_FREE_TRANSLATOR,
        // v3.4.0 - every new customization surface, so Backup & Restore stays lossless.
        K_SURFACE_STYLE, K_BORDER_ALPHA, K_BOTTOM_BAR_STYLE, K_BOTTOM_BAR_LABELS,
        K_SHOW_TROUBLE_TAB, K_MOTION, K_ACCENT_GLOW,
        K_GLASS_CLARITY, K_GLASS_OUTLINES, K_ICON_GLASS, K_UI_FONT,
        K_SUB_COLOR_HEX, K_SUB_OUTLINE, K_SUB_SHADOW,
        K_OMNIBAR_POS, K_SPEEDDIAL_COLS, K_SEARCH_ENGINE, K_WEB_CUSTOM_UA,
        K_READER_DARK, K_FAVICONS, K_SHOW_BLOCK_COUNT, K_WEB_TABS,
        K_ORIENTATION, K_EXTERNAL_PLAYER, K_VOLUME_BOOST, K_SLEEP_TIMER_MS, K_SKIP_VERSION,
        // v3.4.2 - the Midnight Night Dimmer rides the same lossless backup
        K_MIDNIGHT_DIMMER, K_MIDNIGHT_DIM_PCT,
        // v3.4.6.b - the Sources & Index Helper's saved URL travels with it
        K_INDEX_URL);

    // ── the normalizers (pure — Mornye's normalizeXxx pattern) ─────────────────

    public static final String THEME_SYSTEM = "system", THEME_DARK = "dark",
        THEME_AMOLED = "amoled", THEME_LIGHT = "light";
    public static final String UI_BI = "bi", UI_SI = "si", UI_EN = "en";
    public static final String DENSITY_SPACIOUS = "spacious", DENSITY_COMFORTABLE = "comfortable", DENSITY_COMPACT = "compact";
    public static final String RADIUS_SHARP = "sharp", RADIUS_ROUNDED = "rounded", RADIUS_PILL = "apple-pill";
    public static final String BG_PILL = "pill", BG_OUTLINE = "outline", BG_BOX = "box", BG_SHADOW = "shadow";
    public static final String FONT_NOTO = "noto", FONT_SYSTEM = "system",
        FONT_SERIF = "serif", FONT_CINEMA = "cinema";
    public static final String UI_FONT_MODERN = "modern", UI_FONT_SYSTEM = "system",
        UI_FONT_ROUNDED = "rounded", UI_FONT_MONOSPACE = "monospace";
    public static final String ENGINE_HUMAN_PA_FREE = "human-pa-free", ENGINE_HUMAN_FREE = "human-free",
        ENGINE_FREE = "free", ENGINE_WORKER = "worker";
    public static final String ASPECT_FIT = "fit", ASPECT_FILL = "fill", ASPECT_ZOOM = "zoom",
        ASPECT_169 = "16:9", ASPECT_STRETCH = "stretch";

    public static String normalizeThemeMode(String v) {
        return THEME_DARK.equals(v) || THEME_AMOLED.equals(v) || THEME_LIGHT.equals(v)
            || THEME_SYSTEM.equals(v) ? v : THEME_DARK;
    }

    /**
     * v3.4.2 — THE CLEAN-ENGLISH DEFAULT.
     *
     * <p>Out of the box the chrome is 100% crisp English ("AnantPlay Player", one clean
     * label per control). The field screenshots showed the bilingual "සිංහල · English"
     * dot-walls reading as clutter; Sinhala remains a first-class OPTION in Settings
     * ({@link #UI_SI}), it is simply no longer the default. Movie subtitles stay
     * Sinhala by default — that is {@link #K_DEFAULT_LANG}, a different key.
     */
    public static String normalizeUiLang(String v) {
        if (UI_SI.equals(v) || UI_EN.equals(v) || UI_BI.equals(v)) return v;
        return UI_EN;                                               // unknown/null → English
    }

    /** The Midnight Night Dimmer on/off. */
    public static boolean midnightDimmer(SharedPreferences p) {
        return p != null && p.getBoolean(K_MIDNIGHT_DIMMER, false);
    }

    /** 10..70 percent of light removed by the dimmer. */
    public static int midnightDimPct(SharedPreferences p) {
        return normalizeDimPct(p == null ? 40 : p.getInt(K_MIDNIGHT_DIM_PCT, 40));
    }

    public static int normalizeDimPct(int v) { return v < 10 ? 10 : (v > 70 ? 70 : v); }

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
        return FONT_SYSTEM.equals(v) || FONT_SERIF.equals(v) || FONT_CINEMA.equals(v)
            ? v : FONT_NOTO;
    }

    public static String normalizeUiFont(String v) {
        return UI_FONT_SYSTEM.equals(v) || UI_FONT_ROUNDED.equals(v)
            || UI_FONT_MONOSPACE.equals(v) ? v : UI_FONT_MODERN;
    }

    public static int normalizeGlassClarity(int value) { return clampTo(value, 10, 95); }

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
        /** v3.4.0 - "streaming" (default) or "subtitle"; drives the two speed-dial groups. */
        public final String group;
        Link(String name, String url) { this(name, url, "streaming"); }
        Link(String name, String url, String group) {
            this.name = name;
            this.url = url;
            this.group = normalizeLinkGroup(group);
        }
        /** True when this link belongs in the Sri Lankan subtitle group. */
        public boolean isSubtitle() { return "subtitle".equals(group); }
    }

    public static final String GROUP_STREAMING = "streaming", GROUP_SUBTITLE = "subtitle";

    public static String normalizeLinkGroup(String v) {
        return GROUP_SUBTITLE.equals(v) ? GROUP_SUBTITLE : GROUP_STREAMING;
    }

    /** Parse the stored "name|url|group" list → clean entries (https-only URLs, trimmed
     *  names, ≤8 entries, deduped by URL). A two-field "name|url" line from a v3.3.1
     *  backup still parses and defaults to the streaming group (backwards compatible). */
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
            String rest = t.substring(bar + 1).trim();
            String group = GROUP_STREAMING;
            int bar2 = rest.indexOf('|');
            String url = rest;
            if (bar2 >= 0) {
                url = rest.substring(0, bar2).trim();
                group = normalizeLinkGroup(rest.substring(bar2 + 1).trim());
            }
            if (name.isEmpty() || name.length() > 64) continue;
            if (!(url.startsWith("https://") || url.startsWith("http://"))) continue;
            if (url.length() > 300) continue;
            boolean dup = false;
            for (Link l : out) if (l.url.equals(url)) { dup = true; break; }
            if (!dup) out.add(new Link(name, url, group));
        }
        return out;
    }

    /** Serialize links back to the "name|url\n…" storage form (round-trips parseLinks). */
    public static String serializeLinks(List<Link> links) {
        StringBuilder sb = new StringBuilder();
        if (links != null) {
            for (Link l : links) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(l.name.replace('|', ' ')).append('|').append(l.url)
                  .append('|').append(normalizeLinkGroup(l.group));
            }
        }
        return sb.toString();
    }

    /** Boundary clamping (the slider contract): a corrupt value snaps to the
     *  nearest bound — never to a default that could surprise the user. */
    // ── v3.4.0 value vocabularies ────────────────────────────────────────────
    public static final String SURFACE_FLAT = "flat", SURFACE_GLASS = "glass";
    public static final String BAR_DOCKED = "docked", BAR_FLOATING = "floating";
    public static final String OMNIBAR_TOP = "top", OMNIBAR_BOTTOM = "bottom";
    public static final String SEARCH_DDG = "ddg", SEARCH_GOOGLE = "google",
        SEARCH_STARTPAGE = "startpage", SEARCH_BRAVE = "brave";
    /** v3.4.6.d — the default engine id (google). */
    public static final String SEARCH_DEFAULT = SEARCH_GOOGLE;
    public static final String ORIENT_AUTO = "auto", ORIENT_LANDSCAPE = "landscape",
        ORIENT_PORTRAIT = "portrait";
    public static final String EXT_VLC = "vlc", EXT_MX = "mx", EXT_MPV = "mpv", EXT_CHOOSER = "chooser";

    public static String normalizeSurfaceStyle(String v) {
        return SURFACE_GLASS.equals(v) ? SURFACE_GLASS : SURFACE_FLAT;
    }
    public static String normalizeBottomBarStyle(String v) {
        return BAR_FLOATING.equals(v) ? BAR_FLOATING : BAR_DOCKED;
    }
    public static String normalizeOmnibarPos(String v) {
        return OMNIBAR_TOP.equals(v) ? OMNIBAR_TOP : OMNIBAR_BOTTOM;
    }
    public static int normalizeSpeedDialCols(int v) { return clampTo(v, 2, 4); }
    public static int normalizeBorderAlpha(int v) { return clampTo(v, 0, 60); }
    public static int normalizeSubOutline(int v) { return clampTo(v, 0, 6); }
    public static int normalizeVolumeBoost(int v) { return clampTo(v, 0, 150); }
    public static String normalizeSearchEngine(String v) {
        // v3.4.6.d — all FOUR known engines round-trip (ddg included — it was
        // missing from the old accepted list, so a ddg-persisted preference
        // silently fell to the fallback); only unknown/legacy junk resolves
        // to the Google default.
        if (SEARCH_GOOGLE.equals(v) || SEARCH_STARTPAGE.equals(v)
            || SEARCH_BRAVE.equals(v) || SEARCH_DDG.equals(v)) return v;
        return SEARCH_DEFAULT;
    }
    public static String normalizeOrientation(String v) {
        if (ORIENT_LANDSCAPE.equals(v) || ORIENT_PORTRAIT.equals(v)) return v;
        return ORIENT_AUTO;
    }
    public static String normalizeExternalPlayer(String v) {
        if (EXT_VLC.equals(v) || EXT_MX.equals(v) || EXT_MPV.equals(v)) return v;
        return EXT_CHOOSER;
    }
    /** Sleep timer: -1 = end of video, 0 = off, otherwise 15/30/60 minutes. */
    public static long normalizeSleepTimer(long v) {
        if (v < 0) return -1L;
        if (v == 0L) return 0L;
        return clampTo((int) (v / 60000L), 1, 240) * 60000L;
    }
    public static String normalizeSubColorHex(String v) {
        String h = normalizeAccentHex(v);
        return h;
    }
    public static String normalizeCustomUa(String v) {
        if (v == null) return "";
        String t = v.trim();
        return t.length() > 256 ? t.substring(0, 256) : t;
    }
    public static String normalizeSkipVersion(String v) {
        if (v == null) return "";
        String t = v.trim();
        return t.length() > 32 ? t.substring(0, 32) : t;
    }
    /** The light tab list: at most 5 http(s) URLs, one per line. */
    public static java.util.List<String> parseTabs(String stored) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (stored == null || stored.isEmpty()) return out;
        for (String line : stored.split("\n")) {
            String u = line.trim();
            if (u.isEmpty()) continue;
            if (!(u.startsWith("http://") || u.startsWith("https://"))) continue;
            if (out.size() >= 5) break;
            if (!out.contains(u)) out.add(u);
        }
        return out;
    }
    public static String serializeTabs(java.util.List<String> tabs) {
        StringBuilder sb = new StringBuilder();
        if (tabs != null) for (String u : tabs) {
            if (u == null || u.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(u.trim());
        }
        return sb.toString();
    }

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
                case K_UI_FONT: return normalizeUiFont(String.valueOf(raw));
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
                // v3.3.1 — the four v3.3.0 player switches were in BACKUP_KEYS but
                // MISSING here, so Backup & Restore silently dropped them (a restore
                // turned the swipe gestures and lock-on-start back off). Every
                // customization key must round-trip; the lab asserts all 50.
                case K_SWIPE_BRIGHTNESS: case K_SWIPE_VOLUME: case K_SWIPE_SEEK:
                case K_LOCK_ON_START:
                    return raw instanceof Boolean ? raw
                        : Boolean.parseBoolean(String.valueOf(raw)) ? Boolean.TRUE : Boolean.FALSE;
                // ── v3.4.0 ────────────────────────────────────────────────
                case K_SURFACE_STYLE: return normalizeSurfaceStyle(String.valueOf(raw));
                case K_BOTTOM_BAR_STYLE: return normalizeBottomBarStyle(String.valueOf(raw));
                case K_OMNIBAR_POS: return normalizeOmnibarPos(String.valueOf(raw));
                case K_SEARCH_ENGINE: return normalizeSearchEngine(String.valueOf(raw));
                case K_ORIENTATION: return normalizeOrientation(String.valueOf(raw));
                case K_EXTERNAL_PLAYER: return normalizeExternalPlayer(String.valueOf(raw));
                case K_BORDER_ALPHA: return normalizeBorderAlpha(Integer.parseInt(String.valueOf(raw).trim()));
                case K_GLASS_CLARITY:
                    return normalizeGlassClarity(Integer.parseInt(String.valueOf(raw).trim()));
                case K_SPEEDDIAL_COLS: return normalizeSpeedDialCols(Integer.parseInt(String.valueOf(raw).trim()));
                case K_SUB_OUTLINE: return normalizeSubOutline(Integer.parseInt(String.valueOf(raw).trim()));
                case K_VOLUME_BOOST: return normalizeVolumeBoost(Integer.parseInt(String.valueOf(raw).trim()));
                case K_SLEEP_TIMER_MS: return normalizeSleepTimer(Long.parseLong(String.valueOf(raw).trim()));
                case K_SUB_COLOR_HEX: return normalizeSubColorHex(String.valueOf(raw));
                case K_WEB_CUSTOM_UA: return normalizeCustomUa(String.valueOf(raw));
                case K_SKIP_VERSION: return normalizeSkipVersion(String.valueOf(raw));
                case K_WEB_TABS: return serializeTabs(parseTabs(String.valueOf(raw)));
                case K_BOTTOM_BAR_LABELS: case K_SHOW_TROUBLE_TAB: case K_MOTION:
                case K_ACCENT_GLOW: case K_SUB_SHADOW: case K_READER_DARK:
                case K_FAVICONS: case K_SHOW_BLOCK_COUNT: case K_MIDNIGHT_DIMMER:
                case K_GLASS_OUTLINES: case K_ICON_GLASS:
                    return raw instanceof Boolean ? raw
                        : Boolean.parseBoolean(String.valueOf(raw)) ? Boolean.TRUE : Boolean.FALSE;
                case K_MIDNIGHT_DIM_PCT:
                    return normalizeDimPct(Integer.parseInt(String.valueOf(raw).trim()));
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
    public static String uiLang(SharedPreferences p) { return normalizeUiLang(p.getString(K_UI_LANG, UI_EN)); }
    public static boolean spacious(SharedPreferences p) { return DENSITY_SPACIOUS.equals(normalizeDensity(p.getString(K_DENSITY, DENSITY_COMFORTABLE))); }

    // ── v3.4.3 — the onboarding contract (never backed up, always re-checked) ──
    /** True once the user tapped "Finish setup" (the ONLY writer is OnboardingWizard). */
    public static boolean onboardingDone(SharedPreferences p) {
        return p != null && p.getBoolean(K_ONBOARDING_DONE, false);
    }

    public static void setOnboardingDone(SharedPreferences p, boolean done) {
        if (p == null) return;
        p.edit().putBoolean(K_ONBOARDING_DONE, done).apply();
    }

    /** The stage the wizard reopens on (0..5), so rotation and process death keep it.
     *  v3.4.6.b — the wizard grew to six slides (Welcome → Install official
     *  AnantPlay → Notifications → Overlay → Background → Sources/Finish). */
    public static int onboardingStage(SharedPreferences p) {
        int s = p == null ? 0 : p.getInt(K_ONBOARDING_STAGE, 0);
        if (s < 0) return 0;
        return s > 5 ? 5 : s;
    }

    public static void setOnboardingStage(SharedPreferences p, int stage) {
        if (p == null) return;
        p.edit().putInt(K_ONBOARDING_STAGE, stage < 0 ? 0 : (stage > 5 ? 5 : stage)).apply();
    }

    /** "Run setup wizard": forget completion so the wizard opens again from stage 1.
     *  v3.4.6.c — ALSO drops the version stamp: the shell's version gate
     *  (not the done flag alone) decides whether the wizard is owed. */
    public static void resetOnboarding(SharedPreferences p) {
        if (p == null) return;
        p.edit().putBoolean(K_ONBOARDING_DONE, false)
                .putInt(K_ONBOARDING_STAGE, 0)
                .putInt(K_ONBOARDING_VERSION, 0).apply();
    }

    /** v3.4.6.c — the stamped onboarding version (0/1 legacy, 2 = current wizard seen). */
    public static int onboardingVersion(SharedPreferences p) {
        if (p == null) return 0;
        // A legacy install that already finished has NO stamp: treat done → 1,
        // so the shell shows the CURRENT wizard exactly once more (the
        // v3.4.6.b bool gate skipped them — complaint #4's root cause).
        if (!p.contains(K_ONBOARDING_VERSION)) {
            return p.getBoolean(K_ONBOARDING_DONE, false) ? 1 : 0;
        }
        return p.getInt(K_ONBOARDING_VERSION, 0);
    }

    public static void setOnboardingVersion(SharedPreferences p, int version) {
        if (p == null) return;
        p.edit().putInt(K_ONBOARDING_VERSION, version).apply();
    }
    /** v3.3.1 — the Card-corner-radius preference, normalized (sharp|rounded|apple-pill). */
    public static String cardRadius(SharedPreferences p) {
        return normalizeCardRadius(p.getString(K_CARD_RADIUS, RADIUS_ROUNDED));
    }

    public static boolean compact(SharedPreferences p) { return DENSITY_COMPACT.equals(normalizeDensity(p.getString(K_DENSITY, DENSITY_COMFORTABLE))); }
    public static boolean showWebTab(SharedPreferences p) { return p.getBoolean(K_SHOW_WEB_TAB, true); }
    public static boolean showQuickActions(SharedPreferences p) { return p.getBoolean(K_SHOW_QUICK_ACTIONS, true); }
    /** v3.3.0 — the cinema default: 18 sp portrait (was 22) / 20 sp landscape. */
    /** v3.4.2 — 16 sp is the clean compact cinema default (18 in landscape via fontSpFor). */
    public static int fontSp(SharedPreferences p) { return normalizeFontSp(p.getInt(K_FONT_SP, 16)); }

    /** The size actually rendered: +2 sp in landscape (cinema default 18 → 20), still
     *  clamped to the 12..36 range so a user's 36 never overflows. */
    public static int fontSpFor(SharedPreferences p, boolean landscape) {
        int base = fontSp(p);
        return normalizeFontSp(landscape ? base + 2 : base);
    }
    public static String fontFamily(SharedPreferences p) { return normalizeFontFamily(p.getString(K_FONT_FAMILY, FONT_NOTO)); }
    public static int subColor(SharedPreferences p) { return normalizeSubColor(p.getInt(K_SUB_COLOR, 0)); }

    /**
     * v3.4.0 - the subtitle colour actually rendered: the explicit hex wins, otherwise the
     * preset index. Pure, so the lab proves both paths.
     */
    public static int subColorArgb(SharedPreferences p) {
        int hex = accentHexColor(subColorHex(p));
        return hex != 0 ? hex : ThemeEngine.SUB_COLORS[subColor(p)];
    }
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
    /** v3.4.1 — has the first-time Quick-Start banner been dismissed? */
    public static boolean quickStartDone(SharedPreferences p) { return p.getBoolean(K_QUICKSTART_DONE, false); }
    public static boolean dohDiag(SharedPreferences p) { return p.getBoolean(K_DOH_DIAG, true); }
    public static boolean cinemaPolish(SharedPreferences p) { return p.getBoolean(K_CINEMA_POLISH, true); }
    public static String receiverName(SharedPreferences p) { return normalizeReceiverName(p.getString(K_RECEIVER_NAME, null)); }
    public static int receiverPort(SharedPreferences p) { return normalizeReceiverPort(p.getInt(K_RECEIVER_PORT, 8765)); }
    public static boolean dlnaEnabled(SharedPreferences p) { return p.getBoolean(K_DLNASTART, true); }
    public static boolean mdnsEnabled(SharedPreferences p) { return p.getBoolean(K_MDNS, true); }
    public static String workerUrl(SharedPreferences p) { return normalizeWorkerUrl(p.getString(K_WORKER_URL, BridgeApi.DEFAULT_WORKER_URL)); }
    public static boolean adBlock(SharedPreferences p) { return p.getBoolean(K_AD_BLOCK, true); }
    /** v3.4.0 - the desktop user-agent switch (was read inline before). */
    public static boolean webDesktopUa(SharedPreferences p) { return p.getBoolean(K_WEB_DESKTOP_UA, false); }
    public static boolean autoOpenSniffed(SharedPreferences p) { return p.getBoolean(K_AUTO_OPEN_SNIFFED, false); }
    public static boolean freeTranslator(SharedPreferences p) { return p.getBoolean(K_FREE_TRANSLATOR, true); }
    public static List<Link> webLinks(SharedPreferences p) { return parseLinks(p.getString(K_WEB_LINKS, "")); }

    // ── v3.4.0 readers ───────────────────────────────────────────────────────
    public static String surfaceStyle(SharedPreferences p) { return normalizeSurfaceStyle(p.getString(K_SURFACE_STYLE, SURFACE_GLASS)); }
    public static boolean glass(SharedPreferences p) { return SURFACE_GLASS.equals(surfaceStyle(p)); }
    public static int borderAlpha(SharedPreferences p) { return normalizeBorderAlpha(p.getInt(K_BORDER_ALPHA, 18)); }
    public static int glassClarity(SharedPreferences p) { return normalizeGlassClarity(p.getInt(K_GLASS_CLARITY, 75)); }
    public static boolean glassOutlines(SharedPreferences p) { return p.getBoolean(K_GLASS_OUTLINES, true); }
    public static boolean iconGlass(SharedPreferences p) { return p.getBoolean(K_ICON_GLASS, true); }
    public static String uiFont(SharedPreferences p) { return normalizeUiFont(p.getString(K_UI_FONT, UI_FONT_MODERN)); }
    public static String bottomBarStyle(SharedPreferences p) { return normalizeBottomBarStyle(p.getString(K_BOTTOM_BAR_STYLE, BAR_FLOATING)); }
    public static boolean floatingBar(SharedPreferences p) { return BAR_FLOATING.equals(bottomBarStyle(p)); }
    public static boolean bottomBarLabels(SharedPreferences p) { return p.getBoolean(K_BOTTOM_BAR_LABELS, true); }
    public static boolean showTroubleTab(SharedPreferences p) { return p.getBoolean(K_SHOW_TROUBLE_TAB, true); }
    public static boolean motionEnabled(SharedPreferences p) { return p.getBoolean(K_MOTION, true); }
    public static boolean accentGlow(SharedPreferences p) { return p.getBoolean(K_ACCENT_GLOW, true); }
    public static String subColorHex(SharedPreferences p) { return normalizeSubColorHex(p.getString(K_SUB_COLOR_HEX, "")); }
    public static int subOutline(SharedPreferences p) { return normalizeSubOutline(p.getInt(K_SUB_OUTLINE, 0)); }
    public static boolean subShadow(SharedPreferences p) { return p.getBoolean(K_SUB_SHADOW, true); }
    public static String omnibarPos(SharedPreferences p) { return normalizeOmnibarPos(p.getString(K_OMNIBAR_POS, OMNIBAR_BOTTOM)); }
    public static boolean omnibarBottom(SharedPreferences p) { return OMNIBAR_BOTTOM.equals(omnibarPos(p)); }
    public static int speedDialCols(SharedPreferences p) { return normalizeSpeedDialCols(p.getInt(K_SPEEDDIAL_COLS, 4)); }
    // v3.4.6.d — GOOGLE IS THE DEFAULT SEARCH ENGINE (the v3.4.6.c default
    // was DuckDuckGo; the user's explicit requirement flips it). DuckDuckGo,
    // Startpage and Brave remain one-tap choices in Settings → Browser.
    public static String searchEngine(SharedPreferences p) { return normalizeSearchEngine(p.getString(K_SEARCH_ENGINE, SEARCH_GOOGLE)); }
    public static String webCustomUa(SharedPreferences p) { return normalizeCustomUa(p.getString(K_WEB_CUSTOM_UA, "")); }
    public static boolean readerDark(SharedPreferences p) { return p.getBoolean(K_READER_DARK, true); }
    public static boolean favicons(SharedPreferences p) { return p.getBoolean(K_FAVICONS, true); }
    public static boolean showBlockCount(SharedPreferences p) { return p.getBoolean(K_SHOW_BLOCK_COUNT, true); }
    public static String orientation(SharedPreferences p) { return normalizeOrientation(p.getString(K_ORIENTATION, ORIENT_AUTO)); }
    public static String externalPlayer(SharedPreferences p) { return normalizeExternalPlayer(p.getString(K_EXTERNAL_PLAYER, EXT_CHOOSER)); }
    public static int volumeBoost(SharedPreferences p) { return normalizeVolumeBoost(p.getInt(K_VOLUME_BOOST, 100)); }
    public static long sleepTimerMs(SharedPreferences p) { return normalizeSleepTimer(p.getLong(K_SLEEP_TIMER_MS, 0)); }
    public static String skipVersion(SharedPreferences p) { return normalizeSkipVersion(p.getString(K_SKIP_VERSION, "")); }
    public static java.util.List<String> webTabs(SharedPreferences p) { return parseTabs(p.getString(K_WEB_TABS, "")); }

    /** The search URL for the chosen engine (pure - the lab proves all four). */
    public static String searchUrl(String engine, String query) {
        String q = query == null ? "" : query;
        String e = normalizeSearchEngine(engine);
        if (SEARCH_GOOGLE.equals(e)) return "https://www.google.com/search?q=" + q;
        if (SEARCH_STARTPAGE.equals(e)) return "https://www.startpage.com/sp/search?query=" + q;
        if (SEARCH_BRAVE.equals(e)) return "https://search.brave.com/search?q=" + q;
        return "https://duckduckgo.com/?q=" + q;
    }

    /** The UI-language picker (v3.1.0): Bilingual / සිංහල පමණයි / English Only —
     *  pure, so the lab proves each mode's rendering. */
    public static String pick(String mode, String si, String en) {
        if (UI_SI.equals(mode)) return si == null ? "" : si;
        if (UI_EN.equals(mode)) return en == null ? "" : en;
        String s = si == null ? "" : si, e = en == null ? "" : en;
        return s.isEmpty() ? e : (e.isEmpty() || s.equals(e) ? s : s + " · " + e);
    }
}
