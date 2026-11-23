package lk.techworld.anantplay.sicast;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.animation.ValueAnimator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/* v3.1.0 — THE MORNYE-INSPIRED ULTRA-CLEAN CUSTOMIZABLE UI (Phase 2).
 *
 * The v3.0.0 four-tab shell kept every setting in one long vertical scroll and
 * packed the Hero card with a horizontally-scrolled chip strip whose third chip
 * clipped off the right edge on a Xiaomi screen (the 2026-11-22 field report).
 * This is the Mornye translation (github.com/mornyeapp/Mornye — read-only
 * research reference, NEVER a fork; its theme/settings/backup architecture is
 * re-implemented natively in pure Android Java, ZERO new dependencies):
 *
 *   · ZERO-CLIP HERO — the status strip is a responsive weighted grid (2 per
 *     row, full-width last row): nothing can ever clip on any screen width.
 *   · CATEGORIZED SETTINGS DRILL-DOWN (Mornye's settings_tab pattern): icon +
 *     title + 1-line subtitle category rows (🎨 Appearance · 💬 Subtitles &
 *     Style Studio · 🎬 Playback · 📡 Cast & Network · ⬆️ Silent Updates · 💾
 *     Backup & Restore · ℹ️ About & Security) — each opens ONE focused
 *     sub-page with a crisp ‹ back bar. No more 10-page vertical scroll.
 *   · INSTANT THEME (Mornye's ThemeMode × AMOLED × accent × dynamic-color
 *     model, natively): System / Dark (Mornye Slate) / AMOLED Pure Black /
 *     Light, Material You dynamic color on Android 12+, six curated accent
 *     dot presets, UI language mode (Bilingual / සිංහල පමණයි / English Only),
 *     layout density and WebView tab visibility.
 *   · SUBTITLE STYLING STUDIO with a REAL-TIME live cinema preview: drag a
 *     slider and the sample Sinhala line re-renders inside Settings instantly.
 *   · BACKUP & RESTORE ("Yours to Keep"): strict-schema JSON export/import.
 *   · 3-STATE AUTOSTART (Bug 4): the HyperOS UNKNOWN state carries a 1-tap
 *     "✓ Done / මම සක්‍රිය කළා" beside [Open]; the setup row clears forever.
 *   · LAST CRASH CAPTURED card in Diagnostics (Bug 2): the CrashLog report
 *     with 1-tap Copy / Share / Clear.
 *
 * Android 16 (targetSdk 36) edge-to-edge safe: content + bottom bar padded by
 * the REAL window insets. All-programmatic UI — the project's zero-XML-layout
 * idiom, one file, no new dependencies, APK stays ~3.3–3.8 MB. */
public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    // ── the live theme (ThemeEngine palette — every color the UI paints) ───────
    private ThemeEngine.Palette P;

    // ── tabs ───────────────────────────────────────────────────────────────────
    /** v3.2.0 — FIVE clean bottom tabs: 🏠 Home · 🌐 Web · 🎨 Customize · 🩺
     *  Troubleshoot · (ℹ️ About inside Settings). The old Diagnostics tab
     *  became the DEDICATED Troubleshoot hub (Smoking Gun #5); its settings
     *  sub-pages merged into Customize. The Web tab is hideable (Appearance). */
    private static final int TAB_HOME = 0, TAB_WEB = 1, TAB_SETTINGS = 2, TAB_DIAG = 3, TAB_TROUBLE = 4;
    private FrameLayout pages;
    /** v3.3.0 — the fixed top app bar (Settings + Troubleshoot always 1 tap away). */
    private LinearLayout topBar;
    /** v3.4.0 — vector tab icons (outline inactive / filled active). No emoji, ever. */
    private final List<ImageView> tabIcons = new ArrayList<>();
    private final List<TextView> tabLabels = new ArrayList<>();
    /** Per tab: {outlineRes, filledRes}. */
    private final List<int[]> tabIconRes = new ArrayList<>();
    private View tabIndicator;
    private View homePage, settingsPage, diagPage, webPage, troublePage;
    private LinearLayout bottomBar;
    /** v3.1.0 — the settings drill-down overlay (one sub-page at a time). */
    private View subPage;

    // home widgets
    private TextView heroPill, updateBanner;
    private android.widget.ProgressBar updateBar;
    private LinearLayout setupCard, guideCard, guideBody;
    /** v3.4.3 — the one-line Home repair banner (only after onboarding is complete). */
    private TextView repairBanner;
    /** v3.4.3 — the Quick-Settings live subtitle preview (updated by its slider). */
    private TextView quickPreview;
    private final List<Runnable> refreshers = new ArrayList<>();

    // diagnostics widgets
    private TextView healthLoopback, healthDaemon, healthResponder, healthDlna, healthBridge;
    private TextView selfTestLine, logView;
    private LinearLayout crashCard;
    /** v3.1.2 — the live worker health cache (the Deep Troubleshoot probe fills it). */
    private volatile boolean workerHealthyCache = true;
    private volatile String workerVersionCache = "", workerStorageCache = "";
    private volatile boolean updaterRanFlag = false;
    private volatile String updaterResultCache = "not run this session";
    /** v3.2.0 — the 10-point full-feature probe's live outcome list + the
     *  Troubleshoot tab's result card (Smoking Gun #5). */
    /** v3.3.1 — ALWAYS an immutable snapshot (Collections.unmodifiableList). The
     *  v3.3.0 field held a live ArrayList that the probe thread appended to while
     *  the UI thread iterated it in paintProbeCard() — the exact
     *  ConcurrentModificationException (MainActivity.java:2127) from the field log. */
    private volatile java.util.List<FeatureProbe.Outcome> probeOutcomes =
        java.util.Collections.emptyList();

    /**
     * v3.4.2 — re-read the last probe snapshot into this instance. FeatureProbe keeps
     * it statically (the immutable 10/10 result), so a tab switch or a recreate()
     * repopulates the Troubleshoot card and the copied report instead of showing
     * "(not run yet)" after a perfectly good run.
     */
    private void seedProbeOutcomes() {
        try {
            java.util.List<FeatureProbe.Outcome> last = FeatureProbe.lastResults();
            if (last != null && !last.isEmpty()) probeOutcomes = last;
        } catch (Throwable ignored) { }
    }

    // the web tab (created lazily — the first switch to it builds it)
    private WebBrowserView browser;

    /** READ_MEDIA_VIDEO on 33+, READ_EXTERNAL_STORAGE below — only used for file:// casts. */
    private static final String MEDIA_PERM = Build.VERSION.SDK_INT >= 33
        ? "android.permission.READ_MEDIA_VIDEO" : "android.permission.READ_EXTERNAL_STORAGE";

    // ───────────────────────────────────────────────────────────────────────────
    // lifecycle + theme
    // ───────────────────────────────────────────────────────────────────────────

    // ── v3.4.2 — STAY-IN-PLACE SETTINGS ────────────────────────────────────────
    //
    // THE FIELD BUG: changing ANY appearance setting called themeChanged(), which did
    // closeSubPage() + recreate(). recreate() rebuilt the shell on TAB_HOME, so the
    // user was thrown out of Settings (and out of the sub-page they were editing) on
    // every single tap. These three volatile slots carry the exact place back across
    // recreate(); they are consumed once and cleared, so a cold start still opens Home.
    private static volatile int pendingTab = -1;
    private static volatile int pendingScrollY = -1;
    private static volatile Runnable pendingReopen = null;

    /** The last opened sub-page, as a re-runnable builder (null = none open). */
    private volatile Runnable lastSubOpener = null;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("sicast", MODE_PRIVATE);
        seedProbeOutcomes();
        applyTheme();
        setContentView(buildUi());
        restorePlace();
        applyMidnightDimmer();
        ReceiverService.arm(this, "main");
        // ══ v3.4.3 — Replit QA-0001/QA-0002: NO COLD PERMISSION POP-UP ═════════════
        // v3.4.2 fired the Android 13 notification dialog right here, before the app
        // had explained itself. That request now lives in the onboarding wizard's
        // stage 2, where the user has just read WHY it is being asked for.
        main.post(() -> {
            try { OnboardingWizard.showIfNeeded(this, prefs); } catch (Throwable t) {
                Diag.w("onboarding", "auto-open: " + t);
            }
        });
        if (getIntent().getBooleanExtra("needMedia", false)) requestMediaPermission();
        if (getIntent().getBooleanExtra("runTest", false)) { selectTab(TAB_TROUBLE); runFullTroubleshoot(); }
        // v3.3.0 — the Cinema Player's 🇱🇰 mini web-picker hands a search URL over here:
        // the Web tab opens on it and its .zip/.srt downloads are captured live.
        openWebFromIntent(getIntent());
        // the live update banner: SelfUpdater's UI listener drives it (silent +
        // throttled since v3.1.0 — the banner is the ONLY visible update noise)
        SelfUpdater.setUiListener(new SelfUpdater.UiListener() {
            @Override public void onUpdateAvailable(String version) {
                main.post(() -> showUpdateBanner("v" + version + Chrome.pick(" — නිහඬව බාගැනෙමින්… · downloading quietly…"), 0));
            }
            @Override public void onDownloadProgress(int percent) {
                main.post(() -> showUpdateBanner(null, percent));
            }
            @Override public void onUpdateState(String bilingual) {
                main.post(() -> showUpdateBanner(bilingual, -2));
            }
        });
    }

    /** Resolve the ThemeEngine palette for the current prefs (+ wallpaper seed
     *  when dynamic color is on AND Android 12+) and paint the window.
     *  v3.2.0 — the custom hex accent rides the same palette call. */
    private void applyTheme() {
        // v3.4.3 — the English-chrome enforcer learns the UI language before any
        // string is built (Replit QA-0005). Every Activity sets it on create.
        Chrome.setUiLang(AppPrefs.uiLang(prefs));
        String mode = AppPrefs.themeMode(prefs);
        if (AppPrefs.THEME_SYSTEM.equals(mode)) {
            boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
            mode = ThemeEngine.resolveSystemMode(night);
        }
        int dynamicSeed = 0;   // 0 = unavailable/off (a real ARGB seed is never 0)
        if (AppPrefs.dynamicColor(prefs) && Build.VERSION.SDK_INT >= 31) {
            try {
                int id = getResources().getIdentifier("system_accent1_500", "color", "android");
                if (id != 0) dynamicSeed = getResources().getColor(id, getTheme());
            } catch (Throwable t) {
                Diag.w("theme", "dynamic color unavailable: " + t);
            }
        }
        P = ThemeEngine.palette(mode, dynamicSeed, AppPrefs.accent(prefs), AppPrefs.accentHex(prefs),
            prefs.getString(AppPrefs.K_CARD_RADIUS, AppPrefs.RADIUS_ROUNDED));
        // v3.4.0 - the WINDOW follows the palette too (no Midnight flash on AMOLED/Light).
        UiWindow.apply(this, P);
    }

    /** The bilingual helper honoring the UI Language mode (Bilingual / si / en). */
    private String t(String si, String en) { return AppPrefs.pick(AppPrefs.uiLang(prefs), si, en); }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        int granted = res != null && res.length > 0 ? res[0] : -1;
        Diag.i("perm", "request " + req + " (" + (perms != null && perms.length > 0 ? perms[0] : "?")
            + ") → " + (granted == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "denied"));
        if (req == 8 && granted == PackageManager.PERMISSION_GRANTED) {
            toast("වීඩියෝ අවසරය ලැබුණි — AnantPlay එකෙන් නැවත Cast කරන්න");
        }
        refresh();
    }

    private boolean mediaGranted() {
        try { return checkSelfPermission(MEDIA_PERM) == PackageManager.PERMISSION_GRANTED; }
        catch (Throwable t) { return false; }
    }

    private void requestMediaPermission() {
        if (mediaGranted()) { toast("video access granted"); return; }
        try { requestPermissions(new String[]{MEDIA_PERM}, 8); }
        catch (Throwable t) { Diag.w("perm", "media request: " + t); Miui.openAppDetails(this); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // v3.4.3 — the wizard re-queries every permission LIVE on return from a system
        // screen (Replit QA-0003: v3.4.2 trusted "the user came back", so the rows lied).
        OnboardingWizard.onHostResume();
        refresh();
        main.postDelayed(this::refresh, 1200);   // let the receiver/nsd state settle
        main.postDelayed(this::measureShell, 260);   // v3.3.0: prove the nav shell every resume
        if (browser != null) browser.onResumeWeb();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (browser != null) browser.onPauseWeb();
    }

    @Override
    public void onBackPressed() {
        // settings drill-down first, then the WebView's own history, then tabs
        if (subPage != null) { closeSubPage(); return; }
        if (currentTab == TAB_WEB && browser != null && browser.goBack()) return;
        if (currentTab != TAB_HOME) { selectTab(TAB_HOME); return; }
        super.onBackPressed();
    }

    private void refresh() { for (Runnable r : refreshers) r.run(); }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ═══════════════════════════════════════════════════════════════════════════
    // the shell: pages + bottom navigation (edge-to-edge safe)
    // ═══════════════════════════════════════════════════════════════════════════

    private int currentTab = TAB_HOME;

    /**
     * v3.3.0 — THE UNBREAKABLE NAVIGATION SHELL.
     *
     * <p>THE BUG THIS KILLS (v3.2.0 screenshot 1 — the entire bottom navigation bar
     * GONE, Settings/Customize/Troubleshoot unreachable):
     *
     * <p>v3.2.0 built a {@code FrameLayout root}, added {@code pages} to it with
     * MATCH_PARENT/MATCH_PARENT, built {@code bottomBar} — and then <b>never called
     * {@code root.addView(bottomBar)}</b>. The bar was constructed, populated with four
     * tabs, wired to {@code selectTab()}… and left parentless, so it was never
     * measured or drawn. The only trace of it was {@code pages.setPadding(...,
     * bottom + dp(58))}: 58 dp of reserved empty space at the bottom of the content,
     * which is exactly the "pure empty dark background" under
     * "AnantPlay සිංහල Player v3.2.0…" in the screenshot.
     *
     * <p>The fix is structural, not cosmetic: a {@code LinearLayout(VERTICAL)} shell of
     * three children —
     * <pre>
     *   topBar    MATCH_PARENT × WRAP_CONTENT   (title + version pill · 🩺 · ⚙️)
     *   pages     MATCH_PARENT × 0, weight 1.0f (the content host)
     *   bottomBar MATCH_PARENT × WRAP_CONTENT   (🏠 · 🌐 · 🎨 · 🩺)
     * </pre>
     * With the content at height 0 + weight 1 the bottom bar is guaranteed to be laid
     * out INSIDE the window on every screen and every inset: pushing it off-screen is
     * arithmetically impossible, because the content can only ever take what is left.
     * The regression lab asserts this exact shape ({@code v330 layout:}).
     */
    private View buildUi() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(P.bg);

        // ══ A. THE FIXED TOP APP BAR ═══════════════════════════════════════════
        // Settings and Troubleshoot live HERE as icon buttons, so even a device
        // whose bottom bar a manufacturer's skin eats can never lock the user out.
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setMinimumHeight(dp(56));
        topBar.setBackgroundColor(P.card2);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView appTitle = new TextView(this);
        appTitle.setText(t("ඇනන්ට්ප්ලේ සිංහල", "AnantPlay Sinhala"));
        appTitle.setTextColor(P.text);
        appTitle.setTextSize(16f);
        appTitle.setTypeface(Typeface.DEFAULT_BOLD);
        appTitle.setMaxLines(1);
        appTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        brand.addView(appTitle, new LinearLayout.LayoutParams(-2, -2));

        TextView versionPill = new TextView(this);
        versionPill.setText("v" + AppInfo.VERSION_NAME);
        versionPill.setTextColor(P.accent);
        versionPill.setTextSize(9.5f);
        versionPill.setTypeface(Typeface.DEFAULT_BOLD);
        versionPill.setPadding(dp(7), dp(2), dp(7), dp(2));
        GradientDrawable vp = new GradientDrawable();
        vp.setColor(P.chip);
        vp.setCornerRadius(dp(8));
        versionPill.setBackground(vp);
        LinearLayout.LayoutParams vplp = new LinearLayout.LayoutParams(-2, -2);
        vplp.topMargin = dp(2);
        brand.addView(versionPill, vplp);

        LinearLayout.LayoutParams brandLp = new LinearLayout.LayoutParams(0, -2, 1f);
        brandLp.leftMargin = dp(14);
        topBar.addView(brand, brandLp);

        View troubleBtn = topBarIcon(R.drawable.ic_nav_troubleshoot, v -> selectTab(TAB_TROUBLE));
        topBar.addView(troubleBtn, new LinearLayout.LayoutParams(dp(42), dp(42)));
        View settingsBtn = topBarIcon(R.drawable.ic_settings, v -> selectTab(TAB_SETTINGS));
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(dp(42), dp(42));
        sblp.rightMargin = dp(8);
        sblp.leftMargin = dp(2);
        topBar.addView(settingsBtn, sblp);
        shell.addView(topBar, new LinearLayout.LayoutParams(-1, -2));

        // ══ B. THE CONTENT HOST — height 0, weight 1: it can NEVER push the bar ══
        pages = new FrameLayout(this);
        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        shell.addView(pages, contentLp);

        // ══ C. THE FIXED BOTTOM NAVIGATION BAR ═════════════════════════════════
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setMinimumHeight(dp(64));
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setBackgroundColor(P.card2);
        final boolean web = AppPrefs.showWebTab(prefs);
        final boolean troubleTab = AppPrefs.showTroubleTab(prefs);
        // v3.4.0 — icon + label pairs. The filled twin is the ACTIVE state.
        int[][] iconSet;
        String[][] labels;
        if (web && troubleTab) {
            iconSet = new int[][]{{R.drawable.ic_nav_home, R.drawable.ic_nav_home_filled},
                {R.drawable.ic_nav_web, R.drawable.ic_nav_web_filled},
                {R.drawable.ic_nav_customize, R.drawable.ic_nav_customize_filled},
                {R.drawable.ic_nav_troubleshoot, R.drawable.ic_nav_troubleshoot_filled}};
            labels = new String[][]{{"මුල", "Home"}, {"බ්‍රව්සර්", "Web"},
                {"අභිරුචි", "Customize"}, {"දෝෂ", "Troubleshoot"}};
        } else if (troubleTab) {
            iconSet = new int[][]{{R.drawable.ic_nav_home, R.drawable.ic_nav_home_filled},
                {R.drawable.ic_nav_customize, R.drawable.ic_nav_customize_filled},
                {R.drawable.ic_nav_troubleshoot, R.drawable.ic_nav_troubleshoot_filled}};
            labels = new String[][]{{"මුල", "Home"}, {"අභිරුචි", "Customize"},
                {"දෝෂ", "Troubleshoot"}};
        } else {
            iconSet = new int[][]{{R.drawable.ic_nav_home, R.drawable.ic_nav_home_filled},
                {R.drawable.ic_nav_customize, R.drawable.ic_nav_customize_filled}};
            labels = new String[][]{{"මුල", "Home"}, {"අභිරුචි", "Customize"}};
        }
        int[] tabIds = tabIdsInOrder();
        tabIcons.clear();
        tabLabels.clear();
        tabIconRes.clear();
        final boolean showLabels = AppPrefs.bottomBarLabels(prefs);
        final boolean floating = AppPrefs.floatingBar(prefs);
        // v3.4.1 — the bottom-nav glyph is EXACTLY 22dp in a 48dp target, with or
        // without labels. v3.4.0 grew it to 24dp the moment labels were hidden, which
        // is precisely the mode that made the symbols read as "way too huge".
        final int iconDp = 22;
        for (int i = 0; i < tabIds.length && i < iconSet.length; i++) {
            final int idx = tabIds[i];
            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(2), dp(showLabels ? 7 : 10), dp(2), dp(showLabels ? 7 : 10));
            tab.setClickable(true);
            tab.setFocusable(true);

            ImageView icon = UiIcons.view(this, iconSet[i][0], P.desc, iconDp);
            TextView label = new TextView(this);
            label.setText(UiTokens.label(prefs, labels[i][0], labels[i][1]));
            UiTokens.navLabel(label);
            label.setTextColor(P.desc);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(1);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label.setPadding(dp(1), dp(2), dp(1), 0);

            tab.addView(icon, new LinearLayout.LayoutParams(-1, dp(iconDp)));   // v3.4.1 — no inflated box
            if (showLabels) tab.addView(label, new LinearLayout.LayoutParams(-1, -2));
            tab.setOnClickListener(v -> selectTab(idx));
            tabIcons.add(icon);
            tabLabels.add(label);
            tabIconRes.add(iconSet[i]);
            bottomBar.addView(tab, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        // docked = full-width bar with a hairline; floating = a 24dp-radius pill
        if (floating) {
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(P.card2);
            pill.setCornerRadius(dp(24));
            pill.setStroke(dp(1), ThemeEngine.hairline(P, AppPrefs.borderAlpha(prefs)));
            bottomBar.setBackground(pill);
        } else {
            bottomBar.setBackgroundColor(P.card2);
        }
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, -2);
        if (floating) {
            barLp.setMargins(dp(12), 0, dp(12), dp(8));
        }
        shell.addView(bottomBar, barLp);

        // ══ the pages (built once, toggled by selectTab) ═══════════════════════
        homePage = buildHomeTab();
        settingsPage = buildSettingsTab();
        troublePage = buildTroubleTab();
        pages.addView(homePage, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(settingsPage, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(troublePage, new FrameLayout.LayoutParams(-1, -1));

        // ══ the insets: measured, applied to the BARS (never to the content) ═══
        shell.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets top = insets.getInsets(
                android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.displayCutout());
            android.graphics.Insets bottom = insets.getInsets(
                android.view.WindowInsets.Type.navigationBars() | android.view.WindowInsets.Type.displayCutout());
            // v3.4.2 — THE KEYBOARD INSET. Without this the bottom nav bar sat ON TOP
            // of the IME and the WebView omnibar could never rise above it.
            int ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
            int left = Math.max(top.left, bottom.left), right = Math.max(top.right, bottom.right);
            topBar.setPadding(left, top.top + dp(4), right, dp(4));
            bottomBar.setPadding(left, dp(6), right, bottom.bottom + dp(6));
            pages.setPadding(left, 0, right, Math.max(0, ime - bottom.bottom));
            // the nav bar steps aside while the keyboard is up (it would float on it)
            bottomBar.setVisibility(ime > 0 ? View.GONE : View.VISIBLE);
            return insets;
        });

        selectTab(TAB_HOME);
        return shell;
    }

    /** v3.4.1 — the dismissible first-time Quick-Start banner (null once dismissed). */
    private LinearLayout quickStartCard;

    /**
     * v3.4.1 — one numbered Quick-Start row: a small accent badge, a bold step title
     * and one quiet line. Drawn with the design grammar, never an emoji.
     */
    private LinearLayout quickStartStep(String n, String title, String body) {
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, 0);
        TextView badge = new TextView(this);
        badge.setText(n);
        badge.setTextColor(0xFF0A0A14);
        badge.setTextSize(12f);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(rounded(P.accent, dp(9)));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(18), dp(18));
        bp.setMarginEnd(dp(9));
        row.addView(badge, bp);
        LinearLayout text = column();
        TextView t1 = new TextView(this);
        t1.setText(title);
        t1.setTextColor(P.title);
        t1.setTextSize(13.5f);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(t1, widen(0, 0));
        TextView t2v = new TextView(this);
        t2v.setText(body);
        t2v.setTextColor(P.desc);
        t2v.setTextSize(12f);
        text.addView(t2v, widen(0, 0));
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    /** v3.3.0 — the LAST measured navigation-shell verdict (read by FeatureProbe #10). */
    private static volatile boolean shellOk = false;

    static boolean shellInvariantOk() { return shellOk; }

    /**
     * The runtime proof of the fix: the bottom bar is a real child of the shell, it is
     * shown, it sits at or below the content host, and its bottom edge is inside the
     * window. v3.2.0 would fail every one of these (the bar had no parent at all).
     */
    private void measureShell() {
        try {
            View shell = bottomBar == null ? null : (View) bottomBar.getParent();
            boolean ok = shell != null && pages != null && bottomBar.isShown()
                && pages.getHeight() > 0 && bottomBar.getHeight() > 0
                && bottomBar.getTop() >= pages.getBottom() - 1
                && bottomBar.getBottom() <= shell.getHeight();
            shellOk = ok;
            FeatureProbe.shellOk = ok;   // probe #10 reads this
            Troubleshooter.record("LAYOUT", "nav shell " + (ok ? "OK" : "VIOLATED")
                + " · content " + (pages == null ? -1 : pages.getHeight()) + "px · bar top "
                + (bottomBar == null ? -1 : bottomBar.getTop()) + " · bar h "
                + (bottomBar == null ? -1 : bottomBar.getHeight()));
        } catch (Throwable t) {
            shellOk = false;
            FeatureProbe.shellOk = false;
        }
    }

    /** v3.4.0 — a top-bar icon button (troubleshoot / settings): vector, 48dp target. */
    private View topBarIcon(int resId, View.OnClickListener click) {
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(UiIcons.tinted(this, resId, P.text, 20));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(P.chip);
        g.setCornerRadius(dp(12));
        iv.setBackground(g);
        iv.setContentDescription(getString(resId == R.drawable.ic_settings
            ? R.string.tab_settings : R.string.tab_diag));
        iv.setOnClickListener(click);
        return iv;
    }

    /** v3.3.0 — open the Web tab on a URL handed over by the Cinema Player. */
    private void openWebFromIntent(Intent i) {
        if (i == null) return;
        String u = i.getStringExtra("webUrl");
        if (u == null && !i.getBooleanExtra("openWeb", false)) return;
        if (!AppPrefs.showWebTab(prefs)) return;      // the tab is hidden: stay on Home
        selectTab(TAB_WEB);
        if (u != null && browser != null) browser.load(u);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        openWebFromIntent(i);
    }

    private int[] tabIdsInOrder() {
        return AppPrefs.showWebTab(prefs)
            ? new int[]{TAB_HOME, TAB_WEB, TAB_SETTINGS, TAB_TROUBLE}
            : new int[]{TAB_HOME, TAB_SETTINGS, TAB_TROUBLE};
    }

    private void selectTab(int idx) {
        currentTab = idx;
        homePage.setVisibility(idx == TAB_HOME ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility(idx == TAB_SETTINGS ? View.VISIBLE : View.GONE);
        troublePage.setVisibility(idx == TAB_TROUBLE ? View.VISIBLE : View.GONE);
        if (idx == TAB_WEB && browser == null) {
            browser = new WebBrowserView(this);
            webPage = browser;
            pages.addView(webPage, new FrameLayout.LayoutParams(-1, -1));
        }
        if (webPage != null) webPage.setVisibility(idx == TAB_WEB ? View.VISIBLE : View.GONE);
        int[] ids = tabIdsInOrder();
        for (int i = 0; i < tabIcons.size(); i++) {
            boolean on = ids[i] == idx;
            // v3.4.0 — outline ⇄ filled, muted ⇄ accent. The state is legible, not just tinted.
            int[] res = tabIconRes.get(i);
            tabIcons.get(i).setImageDrawable(
                UiIcons.tinted(this, on ? res[1] : res[0], on ? P.accent : P.desc, 22));
            tabLabels.get(i).setTextColor(on ? P.accentDim : P.desc);
            tabLabels.get(i).setTypeface(UiTokens.ui(on ? 600 : 400));
            if (on && AppPrefs.motionEnabled(prefs)) {
                tabIcons.get(i).setAlpha(0.55f);
                tabIcons.get(i).animate().alpha(1f).setDuration(UiTokens.MOTION_FAST).start();
            }
        }
        if (idx == TAB_TROUBLE) refresh();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TAB 1 — HOME (zero-clip hero · quick actions · guide · setup checklist)
    // ═══════════════════════════════════════════════════════════════════════════

    private TextView chipReceiver, chipCast, chipSubs;

    // ═══════════════════════════════════════════════════════════════════════════
    // v3.4.2 — CONTINUE WATCHING + AUTO-OPEN ON CAST
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * The Home "Continue watching" card: the last few resumable titles with a real
     * progress bar, the exact {@code 0:42:15 / 1:58:00} timestamp and 1-tap resume.
     * Returns null (nothing rendered) when there is no history yet — a first run
     * must never show an empty shell.
     */
    private View continueWatchingCard() {
        List<WatchHistory.Item> items = WatchHistory.continueWatching(WatchHistory.load(prefs), 5);
        if (items.isEmpty()) return null;
        LinearLayout box = card(P.card);

        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(t("නැවත බලන්න", "Continue watching"));
        title.setTextColor(P.title);
        title.setTextSize(14.5f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        UiIcons.setCompound(title, R.drawable.ic_history, P.accent, 16, Gravity.START);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView clear = new TextView(this);
        clear.setText(t("හිස් කරන්න", "Clear"));
        clear.setTextColor(P.desc);
        clear.setTextSize(11.5f);
        clear.setPadding(dp(10), dp(6), dp(2), dp(6));
        clear.setOnClickListener(v -> {
            WatchHistory.save(prefs, new ArrayList<>());
            toast(t("ඉතිහාසය මකා දැමුවා", "watch history cleared"));
            rememberPlace();
            recreate();
        });
        head.addView(clear, new LinearLayout.LayoutParams(-2, -2));
        box.addView(head, widen(0, 4));

        for (final WatchHistory.Item it : items) box.addView(historyRow(it), widen(0, 0));
        return box;
    }

    /** One history row: poster-less, weight-safe, one tap resumes at the exact ms. */
    private View historyRow(final WatchHistory.Item it) {
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(8), 0, dp(8));

        ImageView play = new ImageView(this);
        play.setImageDrawable(UiIcons.tinted(this, R.drawable.ic_play_filled, P.accent, 18));
        play.setScaleType(ImageView.ScaleType.FIT_CENTER);
        play.setPadding(dp(9), dp(9), dp(9), dp(9));
        play.setBackground(rounded(P.accentSoft, dp(18)));
        r.addView(play, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), 0, 0, 0);
        TextView name = new TextView(this);
        name.setText(it.title == null || it.title.trim().isEmpty() ? "Video" : it.title.trim());
        name.setTextColor(P.title);
        name.setTextSize(13f);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(name, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ProgressBar pb = new android.widget.ProgressBar(this, null,
            android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setProgress(it.percent());
        try { pb.getProgressDrawable().setColorFilter(P.accent, android.graphics.PorterDuff.Mode.SRC_IN); }
        catch (Throwable ignored) { }
        bar.addView(pb, new LinearLayout.LayoutParams(0, dp(4), 1f));
        TextView ts = new TextView(this);
        // v3.4.3 — an EPHEMERAL relay row says so up front instead of failing silently
        // when it is tapped a day later (the timestamp stays exactly as saved).
        ts.setText(it.needsReopen()
            ? it.progressLabel() + " · " + t("කල් ඉකුත් විය", "expired")
            : it.progressLabel());
        ts.setTextColor(it.needsReopen() ? P.warn : P.desc);
        ts.setTextSize(10.5f);
        ts.setPadding(dp(8), 0, 0, 0);
        bar.addView(ts, new LinearLayout.LayoutParams(-2, -2));
        col.addView(bar, widen(4, 0));
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView go = new TextView(this);
        go.setText(it.needsReopen() ? t("නැවත විවෘත", "Re-open") : t("නැවත", "Resume"));
        go.setTextColor(P.accent);
        go.setTextSize(11.5f);
        go.setTypeface(Typeface.DEFAULT_BOLD);
        go.setPadding(dp(10), dp(6), dp(2), dp(6));
        r.addView(go, new LinearLayout.LayoutParams(-2, -2));

        View.OnClickListener resume = v -> resumeFrom(it);
        r.setOnClickListener(resume);
        go.setOnClickListener(resume);
        return r;
    }

    /** Re-open the player at the saved millisecond (subtitles fetch exactly as before).
     *
     * <p>v3.4.3 — an EPHEMERAL AnantPlay relay entry is resumed from its CANONICAL
     * upstream URL when one was decoded out of the relay, with the dead session's
     * headers dropped (its token died with the cast; replaying it would only earn a
     * 403). The saved timestamp is preserved either way. When there is no canonical
     * URL the user gets the honest 1-tap "Session expired — Re-open in AnantPlay"
     * sheet instead of a silent failure.
     */
    private void resumeFrom(final WatchHistory.Item it) {
        try {
            String target = it.url;
            String headers = it.headers;
            if (it.ephemeralRelay) {
                if (it.canonical != null && !it.canonical.trim().isEmpty()) {
                    target = it.canonical;      // the address that outlives the LAN relay
                    headers = "";              // never replay a dead session's credentials
                } else {
                    showExpiredRelay(it);
                    return;
                }
            }
            Intent i = new Intent(this, PlayActivity.class)
                .putExtra("url", target)
                .putExtra("title", it.title)
                .putExtra("headers", headers)
                .putExtra("startMs", it.pos)
                .putExtra("source", it.source == null || it.source.isEmpty() ? "history" : it.source);
            startActivity(i);
        } catch (Throwable t) {
            Diag.e("ui", "resume: " + t);
            toast(t("නැවත විවෘත කළ නොහැක", "could not resume — please retry"));
        }
    }

    /**
     * v3.4.3 — THE "SESSION EXPIRED" SHEET (Replit-adjacent: the ephemeral relay gap).
     *
     * <p>Calm, one screen, no dead end: the timestamp is kept, and the user is handed the
     * one action that actually works — re-open the movie in official AnantPlay and cast
     * again. "Try anyway" still attempts the stored URL for a relay that happens to be
     * alive on this Wi-Fi right now.
     */
    private void showExpiredRelay(final WatchHistory.Item it) {
        try {
            String msg = t(
                "මෙම සැසිය කල් ඉකුත් වී ඇත — ඔබ නතර කළ තැන (" + WatchHistory.clock(it.pos)
                    + ") සුරකිනු ලැබේ. නැවත AnantPlay තුළින් විවෘත කර Cast කරන්න.",
                "This cast session has expired — your position (" + WatchHistory.clock(it.pos)
                    + ") is kept. Re-open the movie in AnantPlay and cast again.");
            new android.app.AlertDialog.Builder(this)
                .setTitle(WatchHistory.EXPIRED_LABEL)
                .setMessage(msg)
                .setPositiveButton(t("AnantPlay විවෘත කරන්න", "Open AnantPlay"),
                    (d, w) -> openOfficialAnantPlay())
                .setNeutralButton(t("එසේ වුවත් උත්සාහ", "Try anyway"), (d, w) -> {
                    try {
                        startActivity(new Intent(this, PlayActivity.class)
                            .putExtra("url", it.url).putExtra("title", it.title)
                            .putExtra("startMs", it.pos).putExtra("source", "history"));
                    } catch (Throwable t2) { toast("could not open"); }
                })
                .setNegativeButton(t("වසන්න", "Close"), null)
                .show();
        } catch (Throwable t) {
            Diag.w("ui", "expired sheet: " + t);
            toast(WatchHistory.EXPIRED_LABEL);
        }
    }

    /** Launch official AnantPlay when it is installed, else explain where it lives.
     *  v3.4.3 — NEVER an invented download URL (the audit's explicit rule). */
    private void openOfficialAnantPlay() {
        final String pkg = "me.champdev.weblock";
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) { startActivity(i); return; }
        } catch (Throwable ignored) { }
        toast(t("AnantPlay ස්ථාපිත නැත — Play Store / GitHub වෙතින් ලබාගන්න",
            "AnantPlay is not installed — get it from its official Play Store / GitHub page"));
    }

    /**
     * The ONE-TAP overlay setup card. Without SYSTEM_ALERT_WINDOW every cast lands as a
     * tap-to-open notification (the field log: {@code permissions: overlay=off →
     * no overlay permission — tap-to-open notification posted}). Asking once, on Home,
     * in plain words removes that friction forever.
     */
    private View autoOpenBanner() {
        try {
            if (android.provider.Settings.canDrawOverlays(this)) return null;   // already instant
        } catch (Throwable t) { return null; }
        LinearLayout box = card(P.accentSoft);
        TextView head = new TextView(this);
        head.setText(t("Cast කළ විට ඉබේ විවෘත කරන්න",
            "Auto-Open Player on Cast"));
        head.setTextColor(P.title);
        head.setTextSize(14f);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        UiIcons.setCompound(head, R.drawable.ic_cast, P.accent, 16, Gravity.START);
        box.addView(head, widen(0, 2));
        TextView body = new TextView(this);
        body.setText(t("\"Display over other apps\" එක වරක් දෙන්න — ඊට පසු Cast කළ සැණින් player විවෘත වේ.",
            "Allow \"Display over other apps\" once, and every cast opens the player"
            + " by itself — no notification to tap."));
        body.setTextColor(P.desc);
        body.setTextSize(11.5f);
        body.setLineSpacing(dp(2), 1f);
        box.addView(body, widen(0, 8));
        Button enable = btnPrimary(t("දැන් සක්‍රිය කරන්න", "Enable now"));
        UiIcons.setCompound(enable, R.drawable.ic_check_circle, 0xFF0A0A14, 16, Gravity.START);
        enable.setOnClickListener(v -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            } catch (Throwable t) {
                Miui.openAppDetails(this);
            }
        });
        box.addView(enable, widen(0, 0));
        return box;
    }

    private View buildHomeTab() {
        ScrollView scroll = new ScrollView(this);
        scroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
        scroll.setVerticalFadingEdgeEnabled(false);
        LinearLayout root = column();
        scroll.addView(root, new ViewGroup.LayoutParams(-1, -2));

        // ── the quiet update banner (only visible while an update flows) ──
        updateBanner = new TextView(this);
        updateBanner.setTextColor(P.accentDim);
        updateBanner.setTextSize(12.5f);
        updateBanner.setPadding(dp(12), dp(9), dp(12), dp(9));
        GradientDrawable ub = rounded(P.chip, dp(12));
        ub.setStroke(dp(1), P.accent);
        updateBanner.setBackground(ub);
        updateBanner.setVisibility(View.GONE);
        UiIcons.setCompound(updateBanner, R.drawable.ic_update, P.accentDim, 14, Gravity.START);
        updateBanner.setOnClickListener(v -> checkForUpdates());
        root.addView(updateBanner, widen());
        updateBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        updateBar.setMax(100);
        updateBar.setVisibility(View.GONE);
        updateBar.getProgressDrawable().setColorFilter(P.accent, android.graphics.PorterDuff.Mode.SRC_IN);
        root.addView(updateBar, widen());

        // ── HERO: the receiver status card ──
        // ══ v3.4.1 — THE FIRST-TIME 3-STEP QUICK START (defect #5.2) ══════════
        // A first-time user must understand the whole app in ten seconds. Three
        // numbered steps, one button, dismissible, and remembered forever in AppPrefs
        // (and in the settings backup). The technical detail lives in Troubleshoot.
        if (!AppPrefs.quickStartDone(prefs)) {
            quickStartCard = card(P.accentSoft);
            LinearLayout qsHead = row();
            qsHead.setGravity(Gravity.CENTER_VERTICAL);
            TextView qsTitle = new TextView(this);
            qsTitle.setText(t("මිනිත්තු 10න් පටන් ගන්න", "Get started in 3 steps"));
            UiIcons.setCompound(qsTitle, R.drawable.ic_check_circle, P.accent, 16, Gravity.START);
            qsTitle.setTextColor(P.title);
            qsTitle.setTextSize(15f);
            qsTitle.setTypeface(Typeface.DEFAULT_BOLD);
            qsHead.addView(qsTitle, new LinearLayout.LayoutParams(0, -2, 1f));
            quickStartCard.addView(qsHead, widen());

            quickStartCard.addView(quickStartStep("1", t("රිසීවරය අරඹන්න",
                "Start the receiver"), t("පහළ ඇති Start receiver ඔබන්න",
                "Tap Start receiver below")), widen());
            quickStartCard.addView(quickStartStep("2", t("AnantPlay වලින් Cast කරන්න",
                "Cast from AnantPlay"), t("නැත්නම් බ්‍රව්සරයෙන් විවෘත කරන්න",
                "or open a video in the browser")), widen());
            quickStartCard.addView(quickStartStep("3", t("සිංහල උපසිරැසි තනිවම එනවා",
                "Sinhala subtitles arrive by themselves"),
                t("කිසිවක් සැකසීමට අවශ්‍ය නැත", "nothing to configure")), widen());

            Button got = btnPrimary(t("Got it", "Got it"));
            UiIcons.setCompound(got, R.drawable.ic_check_circle, 0xFF0A0A14, 16, Gravity.START);
            got.setOnClickListener(v -> {
                prefs.edit().putBoolean(AppPrefs.K_QUICKSTART_DONE, true).apply();
                if (quickStartCard != null) quickStartCard.setVisibility(View.GONE);
                toast(t("හරි! සුබ නැරඹුමක්", "Got it — enjoy the movie"));
            });
            LinearLayout qsBtnRow = row();
            qsBtnRow.addView(got, new LinearLayout.LayoutParams(0, -2, 1f));
            quickStartCard.addView(qsBtnRow, widen(0, 10));
            root.addView(quickStartCard, widen());
        }

        LinearLayout hero = card(P.card);
        heroPill = new TextView(this);
        heroPill.setTextSize(17f);
        heroPill.setTypeface(Typeface.DEFAULT_BOLD);
        heroPill.setPadding(dp(14), dp(8), dp(14), dp(8));
        heroPill.setBackground(rounded(P.accentSoft, dp(20)));
        hero.addView(heroPill, new LinearLayout.LayoutParams(-2, -2));
        TextView heroSub = new TextView(this);
        heroSub.setText(t("AnantPlay එකේ Cast බොත්තමෙන් \"සිංහල Player\" තෝරන්න",
            "pick \"සිංහල Player\" in AnantPlay's Cast menu"));
        heroSub.setTextColor(P.desc);
        heroSub.setTextSize(11.5f);
        heroSub.setPadding(0, dp(4), 0, 0);
        hero.addView(heroSub, widen());
        Button toggle = btnPrimary("");
        toggle.setTextSize(15f);
        toggle.setOnClickListener(v -> {
            if (ReceiverService.isUp()) {
                startService(new Intent(this, ReceiverService.class).setAction(ReceiverService.ACTION_STOP));
                toast(t("වසා දමන ලදී", "Receiver off"));
            } else {
                ReceiverService.arm(this, "user");
                toast(t("සක්‍රිය වෙමින්…", "arming…"));
            }
            main.postDelayed(this::refresh, 800);
        });
        hero.addView(toggle, widen());

        // v3.1.0 — THE ZERO-CLIP STATUS STRIP: a responsive weighted grid (2 per
        // row, the last chip full width). The v3.0.0 horizontal strip clipped
        // its 3rd chip off the right edge on the Xiaomi field device — a
        // weighted grid physically cannot clip on ANY screen width.
        LinearLayout chipGrid = new LinearLayout(this);
        chipGrid.setOrientation(LinearLayout.VERTICAL);
        chipGrid.setPadding(0, dp(8), 0, 0);
        LinearLayout r1 = row();
        chipReceiver = statusChip("Receiver …");
        chipCast = statusChip("Cast/DLNA …");
        chipSubs = statusChip("Subs Engine …");
        r1.addView(chipReceiver, new LinearLayout.LayoutParams(0, -2, 1f));
        r1.addView(chipCast, new LinearLayout.LayoutParams(0, -2, 1f));
        chipGrid.addView(r1, widen(0, 0));
        chipGrid.addView(chipSubs, widen(0, 0));
        hero.addView(chipGrid, widen());
        root.addView(hero, widen());

        refreshers.add(() -> {
            boolean up = ReceiverService.isUp();
            boolean castable = ReceiverService.castDiscoverable();
            boolean announced = ReceiverService.isAnnounced();
            heroPill.setText(up
                ? (announced || castable ? t("සිංහල Player සූදානම්", "Sinhala Player ready")
                                         : t("සූදානම් — announce වෙමින්…", "arming announcement…"))
                : t("ක්‍රියාත්මක නැත", "Off"));
            statusDot(heroPill, up ? (announced || castable ? P.ok : P.warn) : P.desc);
            heroPill.setTextColor(up ? P.ok : P.desc);
            GradientDrawable hp = rounded(up ? P.accentSoft : P.chip, dp(20));
            heroPill.setBackground(hp);
            toggle.setText(up ? t("වසන්න", "Stop receiver") : t("අරඹන්න", "Start receiver"));
            UiIcons.setCompound(toggle, up ? R.drawable.ic_pause : R.drawable.ic_play,
                0xFF0A0A14, 16, Gravity.START);
            // v3.4.1 — plain words on Home. "port 8765" / "Rsp+DLNA" / "NSD pending" are
            // real facts for the Troubleshoot tab, and noise for a first-time user.
            paintChip(chipReceiver, up, t("රිසීවරය", "Receiver"), up ? t("ක්‍රියාත්මකයි", "running") : t("නිවී ඇත", "off"));
            paintChip(chipCast, castable, t("Cast", "Cast"),
                castable ? t("හමුවෙනවා", "discoverable") : t("සක්‍රිය වෙමින්", "starting…"));
            paintChip(chipSubs, bridgeConfigured(), t("උපසිරැසි එන්ජිනය", "Subs Engine"),
                bridgeConfigured() ? t("ලෑස්තියි", "ready") : t("worker බලන්න", "check worker"));
        });

        // ── v3.4.2 — CONTINUE WATCHING (the history the user asked for) ──
        View cw = continueWatchingCard();
        if (cw != null) root.addView(cw, widen());

        // ── v3.4.2 — the 1-tap "Auto-Open on Cast" setup banner ──
        View overlayBanner = autoOpenBanner();
        if (overlayBanner != null) root.addView(overlayBanner, widen());

        // ── the 2×2 quick-action grid ──
        root.addView(sectionTitle(t("ඉක්මන් ක්‍රියා", "Quick actions")));
        LinearLayout g1 = row();
        g1.addView(actionBtn(R.drawable.ic_play, t("AnantPlay විවෘත කරන්න", "Open AnantPlay"), v -> {
            Miui.openAnantPlay(this);
            // v3.1.0 — the ONE smart non-intrusive cast confirmation (zero nagging)
            toast("සිංහල Player Cast සූදානම් — AnantPlay හි Cast බොත්තමෙන් තෝරන්න");
        }), new LinearLayout.LayoutParams(0, -2, 1f));
        if (AppPrefs.showWebTab(prefs)) {
            g1.addView(actionBtn(R.drawable.ic_nav_web, t("බ්‍රව්සරයෙන් බලන්න", "Watch in WebView"), v -> selectTab(TAB_WEB)),
                new LinearLayout.LayoutParams(0, -2, 1f));
        } else {
            g1.addView(actionBtn(R.drawable.ic_link, t("URL එකක් බලන්න", "Play Video URL"), v -> playUrlDialog()),
                new LinearLayout.LayoutParams(0, -2, 1f));
        }
        root.addView(g1, widen());
        LinearLayout g2 = row();
        if (AppPrefs.showWebTab(prefs)) {
            g2.addView(actionBtn(R.drawable.ic_link, t("URL එකක් බලන්න", "Play Video URL"), v -> playUrlDialog()),
                new LinearLayout.LayoutParams(0, -2, 1f));
        } else {
            g2.addView(actionBtn(R.drawable.ic_settings, t("සැකසුම්", "Settings"), v -> selectTab(TAB_SETTINGS)),
                new LinearLayout.LayoutParams(0, -2, 1f));
        }
        g2.addView(actionBtn(R.drawable.ic_movie, t("පරීක්ෂණ වීඩියෝව", "Test Player"), v -> {
            // v3.1.2 — belt-and-braces: the launch itself can never kill the app
            // (the v3.1.1 crash was inside PlayActivity.onCreate/buildUi — fixed
            // at the source; this guard catches any residual vendor quirk).
            try {
                startActivity(new Intent(this, PlayActivity.class)
                    .putExtra("test", true).putExtra("title", "Sinhala Player Test"));
            } catch (Throwable t2) {
                Diag.e("ui", "test player launch: " + t2);
                toast(t("විවෘත කිරීමට නොහැක — නැවත උත්සාහ කරන්න", "could not open — please retry"));
            }
        }),
            new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(g2, widen());

        // ── the collapsible 3-step cast guide (visual, NOT a wall of text) ──
        guideCard = card(P.card);
        LinearLayout guideHead = row();
        guideHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView guideTitle = new TextView(this);
        guideTitle.setText(t("භාවිත කරන ආකාරය", "How to use"));
        UiIcons.setCompound(guideTitle, R.drawable.ic_info, P.accentDim, 14, Gravity.START);
        guideTitle.setTextColor(P.title);
        guideTitle.setTextSize(14.5f);
        guideTitle.setTypeface(Typeface.DEFAULT_BOLD);
        guideHead.addView(guideTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView guideToggle = new TextView(this);
        guideToggle.setText("▾");
        guideToggle.setTextColor(P.desc);
        guideToggle.setTextSize(16f);
        guideToggle.setPadding(dp(10), 0, dp(6), 0);
        guideHead.addView(guideToggle, new LinearLayout.LayoutParams(-2, -2));
        guideCard.addView(guideHead, widen());
        guideBody = column();
        guideBody.addView(guideStep("1", t("Receiver ON කරන්න", "Turn the receiver ON"),
            t("Home එකේ Start receiver — boot වූ විටත් ස්වයංක්‍රීයවම ඇරෙනවා.",
              "Start receiver on Home — it arms itself on boot too.")));
        guideBody.addView(guideStep("2", t("AnantPlay එකේ Cast ඔබන්න", "Tap Cast in AnantPlay"),
            t("Player එකේ Cast chip → \"සිංහල Player\" තෝරන්න. (නැත්නම්: ඕනෑම link එකක් Share කරන්න හෝ බ්‍රව්සරය භාවිතා කරන්න.)",
              "Pick \"සිංහල Player\" in the Cast chip. (Or: share any link, or use the browser tab.)")));
        guideBody.addView(guideStep("3", t("උපසිරැසි ස්වයංක්‍රීයයි", "Subtitles load automatically"),
            t("\"සිං\" බොත්තමෙන් භාෂා 18ක් හෝ Spoken/Formal මාරු කරන්න.",
              "The \"සිං\" button switches 18 languages or Spoken/Formal register.")));
        guideBody.setVisibility(View.GONE);
        guideCard.addView(guideBody, widen());
        guideHead.setOnClickListener(v -> {
            boolean open = guideBody.getVisibility() == View.VISIBLE;
            guideBody.setVisibility(open ? View.GONE : View.VISIBLE);
            guideToggle.setText(open ? "▾" : "▴");
        });
        root.addView(guideCard, widen());

        // ── v3.4.3 — THE CALM REPAIR BANNER (one line, only when a grant was revoked) ──
        repairBanner = new TextView(this);
        repairBanner.setTextColor(P.warn);
        repairBanner.setTextSize(11.5f);
        repairBanner.setPadding(dp(2), dp(8), dp(2), dp(8));
        repairBanner.setVisibility(View.GONE);
        repairBanner.setOnClickListener(v -> {
            AppPrefs.resetOnboarding(prefs);
            OnboardingWizard.show(this, prefs);
        });
        root.addView(repairBanner, widen());
        refreshers.add(() -> paintRepairBanner());

        // ── the setup checklist (hidden once everything is green) ──
        setupCard = card(0xFF1F1508);
        root.addView(setupCard, widen());
        refreshers.add(() -> paintSetupChecklist());

        TextView foot = new TextView(this);
        foot.setText(String.format(Locale.US,
            Chrome.pick("AnantPlay සිංහල Player v%s · zero fork · zero accessibility · official AnantPlay stays untouched"),
            AppInfo.VERSION_NAME));
        foot.setTextColor(P.desc);
        foot.setTextSize(10.5f);
        foot.setPadding(0, dp(10), 0, dp(6));
        root.addView(foot, widen());
        return scroll;
    }

    /**
     * v3.4.3 — THE REPAIR BANNER (Replit QA-0004).
     *
     * <p>After onboarding is complete, a REVOKED permission must not drag the user back
     * through the wizard (the endless-loop failure mode the audit called out). One
     * honest line, one tap to fix, nothing else.
     */
    private void paintRepairBanner() {
        if (repairBanner == null) return;
        boolean done = AppPrefs.onboardingDone(prefs);
        boolean overlay = android.provider.Settings.canDrawOverlays(this);
        boolean notif = Miui.notifGranted(this);
        boolean battery = Miui.batteryUnrestricted(this);
        boolean repair = OnboardingWizard.needsRepair(done, overlay, notif, battery);
        repairBanner.setVisibility(repair ? View.VISIBLE : View.GONE);
        if (repair) repairBanner.setText(OnboardingWizard.repairBannerText(overlay, notif, battery));
    }

    /** The setup checklist: ONLY the REQUIRED items, the whole card stays GONE
     *  while everything is green — and v3.1.0 (Bug 4): the HyperOS autostart
     *  row carries the 3-state answer + the 1-tap "✓ Done / මම සක්‍රිය කළා"
     *  confirmation so it can actually clear on a Xiaomi phone. */
    private void paintSetupChecklist() {
        boolean notif = Miui.notifGranted(this);
        boolean battery = Miui.batteryUnrestricted(this);
        boolean autostart = Miui.autostartOk(this);
        // v3.4.3 — Replit QA-0002: "Install unknown apps" is NOT part of playback or
        // cast readiness. It is only ever needed when the user taps "Install update",
        // so asking for it on Home was a permission the app does not need yet.
        // v3.4.3 — Replit QA-0003: the OVERLAY grant IS part of readiness (it is what
        // makes a cast open the player instead of posting a notification), and it is
        // re-read on every resume so the row can never go stale.
        boolean overlay = android.provider.Settings.canDrawOverlays(this);
        boolean allOk = notif && battery && autostart && overlay;
        setupCard.setVisibility(allOk ? View.GONE : View.VISIBLE);
        if (allOk) { setupCard.removeAllViews(); return; }
        // every resume re-diffs: a revoked grant must REAPPEAR, a granted one must clear
        setupCard.removeAllViews();
        LinearLayout head2 = row();
        head2.setGravity(Gravity.CENTER_VERTICAL);
        TextView h = new TextView(this);
        h.setText(t("සැකසුම · අවසර අවශ්‍යයි (1-tap each)", "Setup · permissions needed (1-tap each)"));
        UiIcons.setCompound(h, R.drawable.ic_alert, P.warn, 15, Gravity.START);
        h.setTextColor(P.warn);
        h.setTextSize(13.5f);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        head2.addView(h, new LinearLayout.LayoutParams(0, -2, 1f));
        setupCard.addView(head2, widen());
        if (!notif) setupCard.addView(setupRow(
            "Notifications — " + t("දැනුම්දීම් (Android 13+ runtime)", "notifications (Android 13+ runtime)"),
            null, null,
            v -> { try { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7); } catch (Throwable ignored) { } }));
        if (!battery) setupCard.addView(setupRow(
            "Battery → No restrictions — " + t("බැටරි සීමා නැති", "battery unrestricted"),
            null, null, v -> Miui.requestBattery(this)));
        if (!autostart) {
            // Bug 4 — the honest 3-state row: UNKNOWN (HyperOS 15/16 blocks the
            // read) shows the confirm button; DISABLED shows it too (the user's
            // word is trusted once they say they flipped the vendor switch).
            String stateNote;
            switch (Miui.autostartState(this)) {
                case DISABLED: stateNote = t("වසා ඇත (read)", "off (readable)"); break;
                case UNKNOWN: stateNote = t("HyperOS කියවිය නොහැක", "unreadable on HyperOS"); break;
                default: stateNote = "?"; break;
            }
            setupCard.addView(setupRow("Autostart — MIUI " + t("ස්වයං ආරම්භය", "autostart") + " · " + stateNote,
                t("මම සක්‍රිය කළා", "Done"),
                v -> {
                    Miui.confirmAutostart(this, true);
                    toast(t("සලකුණු කළා — මෙම අනතුරු ඇඟවීම ඉවත් වේ",
                        "confirmed — this warning stays cleared"));
                    setupCard.removeAllViews();
                    refresh();
                },
                v -> Miui.openAutostart(this)));
        }
        if (!overlay) setupCard.addView(setupRow(
            "Auto-open on cast — " + t("වෙනත් යෙදුම් මත දර්ශනය", "Display over other apps"),
            null, null, v -> Miui.openOverlay(this)));
    }

    /** One setup row: label + optional [✓ Done] + [Open]. The Done button is
     *  the Bug 4 fix for the permanently-stuck HyperOS autostart warning. */
    private View setupRow(String label, String confirmLabel,
                          View.OnClickListener confirm, View.OnClickListener fix) {
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(6), 0, 0);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(P.text);
        t.setTextSize(12f);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        if (confirmLabel != null && confirm != null) {
            Button ok = smallBtn(confirmLabel);
            ok.setOnClickListener(confirm);
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
            olp.rightMargin = dp(6);
            r.addView(ok, olp);
        }
        Button b = smallBtn("Open · " + t("ඇරෙන්න", "open"));
        b.setOnClickListener(fix);
        r.addView(b, new LinearLayout.LayoutParams(-2, -2));
        return r;
    }

    private void playUrlDialog() {
        LinearLayout box = column();
        box.setPadding(dp(6), dp(4), dp(6), 0);
        final EditText in = new EditText(this);
        in.setHint(t("m3u8 / mpd / mp4 URL paste කරන්න", "paste an m3u8 / mpd / mp4 URL"));
        in.setTextSize(13f);
        in.setTextColor(P.text);
        in.setHintTextColor(P.desc);
        in.setBackground(rounded(P.card2, dp(10)));
        in.setPadding(dp(10), dp(10), dp(10), dp(10));
        box.addView(in, widen());
        new android.app.AlertDialog.Builder(this)
            .setTitle(t("Play Video URL · සිංහල උපසිරැසි සමඟ", "Play Video URL · with Sinhala subtitles"))
            .setView(box)
            .setPositiveButton(t("වාදනය", "Play"), (d, w) -> {
                String u = in.getText().toString().trim();
                if (u.startsWith("http://") || u.startsWith("https://"))
                    startActivity(new Intent(this, PlayActivity.class).putExtra("url", u));
                else toast(t("http(s) URL එකක් පමණයි", "http(s) URLs only"));
            })
            .setNeutralButton(t("Paste & Play", "Paste & Play"), (d, w) -> {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                    CharSequence cs = cm == null || !cm.hasPrimaryClip() ? null : cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                    String u = cs == null ? "" : cs.toString().trim();
                    if (u.startsWith("http://") || u.startsWith("https://"))
                        startActivity(new Intent(this, PlayActivity.class).putExtra("url", u));
                    else toast(t("clipboard එකේ URL නැත", "no URL on clipboard"));
                } catch (Throwable t) {
                    Diag.w("ui", "paste: " + t);
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showUpdateBanner(String text, int percent) {
        updateBanner.setVisibility(View.VISIBLE);
        if (text != null) updateBanner.setText(text);
        if (percent >= 0) {
            updateBar.setVisibility(View.VISIBLE);
            updateBar.setProgress(percent);
            updateBanner.setText(updateBanner.getText().toString().replaceAll("\\d+%", "") + " " + percent + "%");
        } else if (percent == -2) {
            updateBar.setVisibility(View.GONE);
        }
    }

    private boolean bridgeConfigured() {
        String u = prefs.getString("workerUrl", BridgeApi.DEFAULT_WORKER_URL);
        return u != null && u.startsWith("https://");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TAB 3 — SETTINGS: the Mornye categorized drill-down (icon + title + 1-line
    // subtitle rows → ONE focused sub-page each, with a crisp ‹ back bar)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * v3.4.0 - THE CUSTOMIZE HUB.
     *
     * <p>Five Mornye-grouped cards: Appearance (inline, with real theme thumbnails,
     * accent dots, radius/density/bottom-bar segmenteds), Subtitles & style (a live
     * 74dp cinema frame), Browser, Player, and Cast/Network/Data. Every control writes
     * a preference that is in AppPrefs.BACKUP_KEYS, so a backup round-trips all of it.
     */
    private View buildSettingsTab() {
        ScrollView scroll = new ScrollView(this);
        scroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
        LinearLayout root = column();
        final LinearLayout outer = root;                 // v3.4.3 — the page itself
        scroll.addView(root, new ViewGroup.LayoutParams(-1, -2));

        // ══════════════════════════════════════════════════════════════════════
        // v3.4.3 — QUICK SETTINGS (Replit QA-0006 / QA-0007): the five controls a
        // beginner actually changes, always visible, zero jargon. Everything else
        // lives below in ONE collapsible "Advanced settings" container, so the tab
        // opens calm instead of as a 76-preference wall.
        // ══════════════════════════════════════════════════════════════════════
        outer.addView(quickSettingsCard(), widen());

        final LinearLayout advHeader = new LinearLayout(this);
        advHeader.setOrientation(LinearLayout.HORIZONTAL);
        advHeader.setGravity(Gravity.CENTER_VERTICAL);
        advHeader.setPadding(dp(4), dp(14), dp(4), dp(8));
        final TextView advTitle = new TextView(this);
        advTitle.setText(t("උසස් සැකසුම්", "Advanced settings"));
        advTitle.setTextColor(P.title);
        advTitle.setTextSize(14f);
        advTitle.setTypeface(Typeface.DEFAULT_BOLD);
        advHeader.addView(advTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView advChevron = new TextView(this);
        advChevron.setTextColor(P.desc);
        advChevron.setTextSize(14f);
        advHeader.addView(advChevron, new LinearLayout.LayoutParams(-2, -2));
        outer.addView(advHeader, widen());

        final LinearLayout advBody = column();
        outer.addView(advBody, widen());
        final boolean[] expanded = {false};
        Runnable paintAdv = () -> {
            advBody.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
            advChevron.setText(expanded[0] ? "▴" : "▾");
            advTitle.setText(t("උසස් සැකසුම්", "Advanced settings")
                + (expanded[0] ? "" : t(" · සියලු 76 සැකසුම්", " · all 76 preferences")));
        };
        paintAdv.run();
        advHeader.setOnClickListener(v -> { expanded[0] = !expanded[0]; paintAdv.run(); });

        // every existing section below now builds into advBody (one reference change)
        root = advBody;

        // ══ 1. APPEARANCE ══════════════════════════════════════════════════════
        LinearLayout g1 = UiGroup.make(this, P, prefs, "පෙනුම", "Appearance");
        g1.addView(themeThumbs(), widen());
        g1.addView(accentPicker(), widen());
        g1.addView(UiSegmented.row(this, P, prefs, "කොන් රේඛාව", "Card radius",
            new String[]{t("තියුණු", "Sharp"), t("රවුම්", "Rounded"), t("පිල්", "Pill")},
            radiusIndex(), i -> {
                prefs.edit().putString(AppPrefs.K_CARD_RADIUS,
                    new String[]{AppPrefs.RADIUS_SHARP, AppPrefs.RADIUS_ROUNDED, AppPrefs.RADIUS_PILL}[i]).apply();
                themeChanged();
            }), widen());
        g1.addView(UiSegmented.row(this, P, prefs, "මතුපිට", "Surface",
            new String[]{t("සමතලා", "Flat"), t("වීදුරු", "Glass")},
            AppPrefs.glass(prefs) ? 1 : 0, i -> {
                prefs.edit().putString(AppPrefs.K_SURFACE_STYLE,
                    i == 1 ? AppPrefs.SURFACE_GLASS : AppPrefs.SURFACE_FLAT).apply();
                themeChanged();
            }), widen());
        g1.addView(UiSlider.make(this, P, prefs, "මායිම් අඳුන", "Border alpha",
            0, 60, AppPrefs.borderAlpha(prefs), "%", v -> {
                prefs.edit().putInt(AppPrefs.K_BORDER_ALPHA, v).apply();
            }), widen());
        g1.addView(UiSegmented.row(this, P, prefs, "පිරිසැලසුම", "Density",
            new String[]{t("පළල්", "Spacious"), t("සුවපහසු", "Comfort"), t("තද", "Compact")},
            AppPrefs.spacious(prefs) ? 0 : (AppPrefs.compact(prefs) ? 2 : 1), i -> {
                prefs.edit().putString(AppPrefs.K_DENSITY,
                    new String[]{AppPrefs.DENSITY_SPACIOUS, AppPrefs.DENSITY_COMFORTABLE,
                        AppPrefs.DENSITY_COMPACT}[i]).apply();
                themeChanged();
            }), widen());
        g1.addView(UiSegmented.row(this, P, prefs, "පහළ තීරුව", "Bottom bar",
            new String[]{t("සවිකර", "Docked"), t("පාවෙන", "Floating")},
            AppPrefs.floatingBar(prefs) ? 1 : 0, i -> {
                prefs.edit().putString(AppPrefs.K_BOTTOM_BAR_STYLE,
                    i == 1 ? AppPrefs.BAR_FLOATING : AppPrefs.BAR_DOCKED).apply();
                themeChanged();
            }), widen());
        g1.addView(UiSegmented.row(this, P, prefs, "UI භාෂාව", "UI language",
            new String[]{t("ද්විභාෂා", "Bilingual"), "සිංහල", "English"},
            AppPrefs.UI_SI.equals(AppPrefs.uiLang(prefs)) ? 1
                : (AppPrefs.UI_EN.equals(AppPrefs.uiLang(prefs)) ? 2 : 0), i -> {
                prefs.edit().putString(AppPrefs.K_UI_LANG,
                    new String[]{AppPrefs.UI_BI, AppPrefs.UI_SI, AppPrefs.UI_EN}[i]).apply();
                themeChanged();
            }), widen());
        UiGroup.divider(this, g1, P, prefs);
        UiGroup.switchRow(this, g1, P, prefs, R.drawable.ic_sliders,
            "පහළ තීරු ලේබල", "Bottom bar labels", AppPrefs.K_BOTTOM_BAR_LABELS, true);
        UiGroup.divider(this, g1, P, prefs);
        UiGroup.switchRow(this, g1, P, prefs, R.drawable.ic_refresh,
            "චලනය", "Motion & animation", AppPrefs.K_MOTION, true);
        UiGroup.divider(this, g1, P, prefs);
        UiGroup.switchRow(this, g1, P, prefs, R.drawable.ic_palette,
            "වර්ණ දීප්තිය", "Accent glow", AppPrefs.K_ACCENT_GLOW, true);
        UiGroup.divider(this, g1, P, prefs);
        UiGroup.switchRow(this, g1, P, prefs, R.drawable.ic_nav_web,
            "Web ටැබ් එක", "Show the Browser tab", AppPrefs.K_SHOW_WEB_TAB, true);
        UiGroup.divider(this, g1, P, prefs);
        UiGroup.switchRow(this, g1, P, prefs, R.drawable.ic_nav_troubleshoot,
            "දෝෂ ටැබ් එක", "Show the Troubleshoot tab", AppPrefs.K_SHOW_TROUBLE_TAB, true);
        root.addView(g1, widen());

        // ══ 2. SUBTITLES & STYLE ═══════════════════════════════════════════════
        LinearLayout g2 = UiGroup.make(this, P, prefs, "උපසිරැසි", "Subtitles & style");
        g2.addView(subtitlePreview(), widen());
        UiGroup.row(this, g2, P, prefs, R.drawable.ic_font,
            "අකුරු · වර්ණය · පසුබිම", "Font, colour, background, position", null, true,
            v -> openSubtitleStudio());
        UiGroup.divider(this, g2, P, prefs);
        UiGroup.row(this, g2, P, prefs, R.drawable.ic_translate,
            "පරිවර්තනය සහ එන්ජිම", "Translation & engine priority", null, true,
            v -> openSubtitleStudio());
        root.addView(g2, widen());

        // ══ 3. BROWSER ═════════════════════════════════════════════════════════
        LinearLayout g3 = UiGroup.make(this, P, prefs, "බ්‍රව්සර්", "Browser");
        g3.addView(UiSegmented.row(this, P, prefs, "ලිපින තීරුව", "Omnibar",
            new String[]{t("ඉහළ", "Top"), t("පහළ", "Bottom")},
            AppPrefs.omnibarBottom(prefs) ? 1 : 0, i -> {
                prefs.edit().putString(AppPrefs.K_OMNIBAR_POS,
                    i == 0 ? AppPrefs.OMNIBAR_TOP : AppPrefs.OMNIBAR_BOTTOM).apply();
                if (browser != null) { pages.removeView(browser); browser = null; webPage = null;
                    if (currentTab == TAB_WEB) selectTab(TAB_WEB); }
            }), widen());
        g3.addView(UiSegmented.row(this, P, prefs, "Speed dial තීරු", "Speed dial columns",
            new String[]{"2", "3", "4"}, AppPrefs.speedDialCols(prefs) - 2, i -> {
                prefs.edit().putInt(AppPrefs.K_SPEEDDIAL_COLS, i + 2).apply();
                if (browser != null) browser.onResumeWeb();
            }), widen());
        g3.addView(UiSegmented.row(this, P, prefs, "සෙවුම් යන්ත්‍රය", "Search engine",
            new String[]{"DDG", "Google", "Startpage", "Brave"}, searchIndex(), i -> {
                prefs.edit().putString(AppPrefs.K_SEARCH_ENGINE,
                    new String[]{AppPrefs.SEARCH_DDG, AppPrefs.SEARCH_GOOGLE,
                        AppPrefs.SEARCH_STARTPAGE, AppPrefs.SEARCH_BRAVE}[i]).apply();
            }), widen());
        UiGroup.divider(this, g3, P, prefs);
        UiGroup.switchRow(this, g3, P, prefs, R.drawable.ic_shield_adblock,
            "දැන්වීම් පෙරහන", "Ad & tracker shield", AppPrefs.K_AD_BLOCK, true);
        UiGroup.divider(this, g3, P, prefs);
        UiGroup.switchRow(this, g3, P, prefs, R.drawable.ic_info,
            "අවහිර ගණන", "Show the blocked count", AppPrefs.K_SHOW_BLOCK_COUNT, true);
        UiGroup.divider(this, g3, P, prefs);
        UiGroup.switchRow(this, g3, P, prefs, R.drawable.ic_refresh,
            "ස්වයංක්‍රීය විවෘත", "Auto-open a sniffed stream", AppPrefs.K_AUTO_OPEN_SNIFFED, false);
        UiGroup.divider(this, g3, P, prefs);
        UiGroup.switchRow(this, g3, P, prefs, R.drawable.ic_desktop,
            "Desktop UA", "Desktop user-agent", AppPrefs.K_WEB_DESKTOP_UA, false);
        UiGroup.divider(this, g3, P, prefs);
        UiGroup.switchRow(this, g3, P, prefs, R.drawable.ic_moon,
            "අඳුරු පිටු", "Reader dark", AppPrefs.K_READER_DARK, true);
        UiGroup.divider(this, g3, P, prefs);
        UiGroup.switchRow(this, g3, P, prefs, R.drawable.ic_globe,
            "Favicons", "Real favicons in the speed dial", AppPrefs.K_FAVICONS, true);
        UiGroup.divider(this, g3, P, prefs);
        UiGroup.row(this, g3, P, prefs, R.drawable.ic_link,
            "අඩවි ලින්ක්", "Custom links & user-agent", null, true, v -> openWebPlayer());
        root.addView(g3, widen());

        // ══ 4. PLAYER ══════════════════════════════════════════════════════════
        LinearLayout g4 = UiGroup.make(this, P, prefs, "ධාවකය", "Player");
        g4.addView(UiSegmented.row(this, P, prefs, "රූප පරිමාණය", "Default aspect",
            new String[]{t("ගැළපුම", "Fit"), t("පිරවුම", "Fill"), "16:9", t("දිගු", "Stretch")},
            aspectIndex(), i -> {
                prefs.edit().putString(AppPrefs.K_ASPECT_MODE,
                    new String[]{AppPrefs.ASPECT_FIT, AppPrefs.ASPECT_FILL,
                        AppPrefs.ASPECT_169, AppPrefs.ASPECT_STRETCH}[i]).apply();
            }), widen());
        g4.addView(UiSlider.make(this, P, prefs, "පෙරනිමි වේගය", "Default speed",
            25, 250, AppPrefs.playSpeedPm(prefs), "%", v -> {
                prefs.edit().putInt(AppPrefs.K_PLAY_SPEED, v).apply();
            }), widen());
        g4.addView(UiSlider.make(this, P, prefs, "හඬ වැඩි කිරීම", "Volume boost",
            0, 150, AppPrefs.volumeBoost(prefs), "%", v -> {
                prefs.edit().putInt(AppPrefs.K_VOLUME_BOOST, v).apply();
            }), widen());
        g4.addView(UiSegmented.row(this, P, prefs, "තිර දිශාව", "Orientation",
            new String[]{t("ස්වයංක්‍රීය", "Auto"), t("තිරස්", "Landscape"), t("සිරස්", "Portrait")},
            orientationIndex(), i -> {
                prefs.edit().putString(AppPrefs.K_ORIENTATION,
                    new String[]{AppPrefs.ORIENT_AUTO, AppPrefs.ORIENT_LANDSCAPE,
                        AppPrefs.ORIENT_PORTRAIT}[i]).apply();
            }), widen());
        g4.addView(UiSegmented.row(this, P, prefs, "බාහිර ධාවකය", "External player",
            new String[]{"VLC", "MX", "mpv", t("තෝරන්න", "Ask")}, externalIndex(), i -> {
                prefs.edit().putString(AppPrefs.K_EXTERNAL_PLAYER,
                    new String[]{AppPrefs.EXT_VLC, AppPrefs.EXT_MX, AppPrefs.EXT_MPV,
                        AppPrefs.EXT_CHOOSER}[i]).apply();
            }), widen());
        g4.addView(UiSegmented.row(this, P, prefs, "නිදාගැනීමේ කාලය", "Sleep timer",
            new String[]{t("නැත", "Off"), "15m", "30m", "60m"},
            sleepIndex(), i -> {
                prefs.edit().putLong(AppPrefs.K_SLEEP_TIMER_MS,
                    new long[]{0L, 900000L, 1800000L, 3600000L}[i]).apply();
            }), widen());
        UiGroup.divider(this, g4, P, prefs);
        UiGroup.switchRow(this, g4, P, prefs, R.drawable.ic_speed,
            "දෙවරක් තට්ටු කිරීම", "Double-tap to seek", AppPrefs.K_DOUBLE_TAP_SEEK, true);
        UiGroup.divider(this, g4, P, prefs);
        UiGroup.switchRow(this, g4, P, prefs, R.drawable.ic_pip,
            "Auto-PiP", "Auto picture-in-picture", AppPrefs.K_AUTO_PIP, true);
        UiGroup.divider(this, g4, P, prefs);
        UiGroup.switchRow(this, g4, P, prefs, R.drawable.ic_play,
            "නැවත අරඹන්න", "Auto-resume position", AppPrefs.K_AUTO_RESUME, true);
        UiGroup.divider(this, g4, P, prefs);
        UiGroup.switchRow(this, g4, P, prefs, R.drawable.ic_brightness,
            "තිරය දැල්වී තබන්න", "Keep the screen on", AppPrefs.K_KEEP_SCREEN_ON, true);
        UiGroup.divider(this, g4, P, prefs);
        UiGroup.row(this, g4, P, prefs, R.drawable.ic_sliders,
            "gestures සහ seek පියවර", "Gestures, seek step & HUD timeout", null, true,
            v -> openPlayback());
        root.addView(g4, widen());

        // ══ 5. CAST, NETWORK & DATA ════════════════════════════════════════════
        LinearLayout g5 = UiGroup.make(this, P, prefs, "ජාලය සහ දත්ත", "Cast, network & data");
        UiGroup.row(this, g5, P, prefs, R.drawable.ic_cast,
            "රිසීවර නම සහ port", "Receiver name & port", String.valueOf(AppPrefs.receiverPort(prefs)),
            true, v -> openCastNetwork());
        UiGroup.divider(this, g5, P, prefs);
        UiGroup.switchRow(this, g5, P, prefs, R.drawable.ic_cast,
            "DLNA", "DLNA renderer", AppPrefs.K_DLNASTART, true);
        UiGroup.divider(this, g5, P, prefs);
        UiGroup.switchRow(this, g5, P, prefs, R.drawable.ic_globe,
            "mDNS", "mDNS responder", AppPrefs.K_MDNS, true);
        UiGroup.divider(this, g5, P, prefs);
        UiGroup.row(this, g5, P, prefs, R.drawable.ic_update,
            "යාවත්කාලීන", "Silent updates", null, true, v -> openUpdates());
        UiGroup.divider(this, g5, P, prefs);
        UiGroup.row(this, g5, P, prefs, R.drawable.ic_backup,
            "උපස්ථ කිරීම", "Backup & restore", null, true, v -> openBackup());
        UiGroup.divider(this, g5, P, prefs);
        UiGroup.row(this, g5, P, prefs, R.drawable.ic_info,
            "පිළිබඳ සහ ආරක්ෂණය", "About & security", "v" + AppInfo.VERSION_NAME, true,
            v -> openAbout());
        root.addView(g5, widen());

        root.addView(UiGroup.caption(this, P, t(
            "සැකසුම් 100% දේශීයයි — කිසිදු දත්ත එළියට නොයයි. සියල්ල Backup එකේ ඇතුළත්.",
            "All settings stay on this phone — nothing leaves it, and every one is in the backup.")));
        return scroll;
    }

    /**
     * v3.4.3 — THE QUICK SETTINGS CARD (Replit QA-0006).
     *
     * <p>Five controls, no acronyms: theme, accent, subtitle size (with a LIVE preview),
     * automatic Sinhala subtitles, and the two "comfort" switches (resume + night
     * dimmer). Every one of them is a real preference — nothing here is decorative.
     */
    private View quickSettingsCard() {
        LinearLayout card = UiGroup.make(this, P, prefs, "ඉක්මන් සැකසුම්", "Quick settings");
        card.addView(themeThumbs(), widen());
        card.addView(accentPicker(), widen());
        UiGroup.divider(this, card, P, prefs);
        // subtitle size + the live preview (the Studio preview itself stays Sinhala on purpose)
        card.addView(UiSlider.make(this, P, prefs, "උපසිරැසි අකුරු", "Subtitle size",
            14, 40, AppPrefs.fontSp(prefs), "sp", v -> {
                prefs.edit().putInt(AppPrefs.K_FONT_SP, v).apply();
                quickPreview.setTextSize(v);
                quickPreview.setText(t("ශ්‍රී ලංකාවේ චිත්‍රපටිය — අකුරු හරිම පැහැදිලියි!",
                    "Subtitle preview — this is how every line will look."));
            }), widen());
        quickPreview = new TextView(this);
        quickPreview.setText(t("ශ්‍රී ලංකාවේ චිත්‍රපටිය — අකුරු හරිම පැහැදිලියි!",
            "Subtitle preview — this is how every line will look."));
        quickPreview.setTextColor(P.title);
        quickPreview.setTextSize(AppPrefs.fontSp(prefs));
        quickPreview.setTypeface(UiTokens.si(this, false));
        quickPreview.setGravity(Gravity.CENTER);
        quickPreview.setPadding(dp(6), dp(8), dp(6), dp(8));
        quickPreview.setBackground(rounded(P.card2, dp(12)));
        card.addView(quickPreview, widen());
        UiGroup.divider(this, card, P, prefs);
        UiGroup.switchRow(this, card, P, prefs, R.drawable.ic_subtitles,
            "සිංහල උපසිරැසි ස්වයංක්‍රීයව", "Automatic Sinhala subtitles", AppPrefs.K_AUTO_FETCH, true);
        UiGroup.divider(this, card, P, prefs);
        UiGroup.switchRow(this, card, P, prefs, R.drawable.ic_play_filled,
            "නැවත බලන්න සිහිපත් කරන්න", "Continue watching resume", AppPrefs.K_AUTO_RESUME, true);
        UiGroup.divider(this, card, P, prefs);
        UiGroup.switchRow(this, card, P, prefs, R.drawable.ic_settings,
            "මධ්‍යම රාත්‍රී අඳුරු", "Midnight night dimmer", AppPrefs.K_MIDNIGHT_DIMMER, true);
        return card;
    }

    // ── Customize helpers (v3.4.0) ────────────────────────────────────────────

    private int radiusIndex() {
        String v = AppPrefs.cardRadius(prefs);
        return AppPrefs.RADIUS_SHARP.equals(v) ? 0 : (AppPrefs.RADIUS_PILL.equals(v) ? 2 : 1);
    }

    private int searchIndex() {
        String v = AppPrefs.searchEngine(prefs);
        if (AppPrefs.SEARCH_GOOGLE.equals(v)) return 1;
        if (AppPrefs.SEARCH_STARTPAGE.equals(v)) return 2;
        if (AppPrefs.SEARCH_BRAVE.equals(v)) return 3;
        return 0;
    }

    private int aspectIndex() {
        String v = AppPrefs.aspectMode(prefs);
        if (AppPrefs.ASPECT_FILL.equals(v)) return 1;
        if (AppPrefs.ASPECT_169.equals(v)) return 2;
        if (AppPrefs.ASPECT_STRETCH.equals(v)) return 3;
        return 0;
    }

    private int orientationIndex() {
        String v = AppPrefs.orientation(prefs);
        if (AppPrefs.ORIENT_LANDSCAPE.equals(v)) return 1;
        if (AppPrefs.ORIENT_PORTRAIT.equals(v)) return 2;
        return 0;
    }

    private int externalIndex() {
        String v = AppPrefs.externalPlayer(prefs);
        if (AppPrefs.EXT_VLC.equals(v)) return 0;
        if (AppPrefs.EXT_MX.equals(v)) return 1;
        if (AppPrefs.EXT_MPV.equals(v)) return 2;
        return 3;
    }

    private int sleepIndex() {
        long v = AppPrefs.sleepTimerMs(prefs);
        if (v == 900000L) return 1;
        if (v == 1800000L) return 2;
        if (v >= 3600000L) return 3;
        return 0;
    }

    /** The 4 theme thumbnail cards: a real miniature of each canvas. */
    private View themeThumbs() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(10));
        String[] keys = {AppPrefs.THEME_SYSTEM, AppPrefs.THEME_DARK,
            AppPrefs.THEME_AMOLED, AppPrefs.THEME_LIGHT};
        String[] names = {t("පද්ධති", "System"), "Midnight", "AMOLED", "Light"};
        int[] bgs = {0xFF12172A, 0xFF0B1020, 0xFF000000, 0xFFF3F5FB};
        int[] cards = {0xFF1E2540, 0xFF111A2E, 0xFF0A0F1C, 0xFFFFFFFF};
        String cur = AppPrefs.themeMode(prefs);
        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            boolean on = key.equals(cur);
            GradientDrawable g = new GradientDrawable();
            g.setColor(bgs[i]);
            g.setCornerRadius(dp(12));
            g.setStroke(dp(on ? 2 : 1), on ? P.accent : 0x33FFFFFF);
            tile.setBackground(g);
            tile.setPadding(dp(6), dp(8), dp(6), dp(7));

            View bar = new View(this);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(cards[i]);
            bg.setCornerRadius(dp(4));
            bar.setBackground(bg);
            tile.addView(bar, new LinearLayout.LayoutParams(-1, dp(22)));

            TextView label = new TextView(this);
            label.setText(names[i]);
            label.setTextColor(i == 3 ? 0xFF111827 : 0xFFCBD5E1);
            UiTokens.text(label, 9.5f, 600, 0f, false);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(4), 0, 0);
            tile.addView(label, new LinearLayout.LayoutParams(-1, -2));

            tile.setOnClickListener(v -> {
                prefs.edit().putString(AppPrefs.K_THEME_MODE, key).apply();
                themeChanged();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.rightMargin = dp(7);
            row.addView(tile, lp);
        }
        return row;
    }

    /** 10 accent dots + the custom hex entry. */
    private View accentPicker() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(UiSegmented.accentDots(this, P, prefs, AppPrefs.accent(prefs),
            AppPrefs.accentHex(prefs), idx -> {
                prefs.edit().putInt(AppPrefs.K_ACCENT, idx).putString(AppPrefs.K_ACCENT_HEX, "").apply();
                themeChanged();
            }, v -> openAppearance()), widen());
        return wrap;
    }

    /** The 74dp live cinema frame that proves the subtitle style before a film is opened. */
    private View subtitlePreview() {
        FrameLayout stage = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0xFF3B2E6E, 0xFF12101F, 0xFF07060C});
        bg.setCornerRadius(dp(11));
        stage.setBackground(bg);
        TextView line = new TextView(this);
        line.setText("ශ්‍රී ලංකාවේ චිත්‍රපටිය — සිංහල උපසිරැසි");
        line.setTextColor(AppPrefs.subColorArgb(prefs));
        line.setTextSize(AppPrefs.fontSp(prefs));
        line.setGravity(Gravity.CENTER);
        line.setPadding(dp(8), dp(3), dp(8), dp(3));
        line.setTypeface(UiTokens.si(this, AppPrefs.subBold(prefs) ? 600 : 400));
        line.setLineSpacing(dp(1), AppPrefs.subLineSpacing(prefs) / 100f);
        String style = AppPrefs.subBg(prefs);
        if (AppPrefs.BG_BOX.equals(style)) {
            line.setBackgroundColor(ThemeEngine.pillColor(Math.max(90, AppPrefs.subBgOpacity(prefs))));
        } else if (AppPrefs.BG_OUTLINE.equals(style) || AppPrefs.BG_SHADOW.equals(style)) {
            line.setBackgroundColor(0);
        } else {
            line.setBackgroundColor(ThemeEngine.pillColor(AppPrefs.subBgOpacity(prefs)));
        }
        line.setShadowLayer(dp(Math.max(1, AppPrefs.subOutline(prefs))), 0, 0, 0xFF000000);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2,
            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(9);
        stage.addView(line, lp);
        stage.setOnClickListener(v -> openSubtitleStudio());
        // No setLayoutParams here: the caller adds it with widen() (LinearLayout.LayoutParams),
        // which is the only class the parent accepts (the v3.3.1 static gate proves it).
        return stage;
    }

    /** One Mornye-style category row: big icon chip + bold title + 1-line subtitle + ›. */
    /** v3.4.0 - a category row whose leading icon is a tinted vector, not an emoji. */
    private View categoryRow(int iconRes, int iconColor, String title, String subtitle, View.OnClickListener click) {
        return categoryRow(iconRes, iconColor, title, subtitle, click, true);
    }

    private View categoryRow(int iconRes, int iconColor, String title, String subtitle,
                             View.OnClickListener click, boolean unusedLegacy) {
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(11), 0, dp(11));
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(UiIcons.tinted(this, iconRes, iconColor, 20));
        ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
        GradientDrawable g = rounded(iconColor, dp(14));
        g.setAlpha(46);
        ic.setBackground(g);
        r.addView(ic, new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout text = column2();
        TextView t1 = new TextView(this);
        t1.setText(title);
        t1.setTextColor(P.title);
        t1.setTextSize(14f);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(t1, new LinearLayout.LayoutParams(-1, -2));
        TextView t2 = new TextView(this);
        t2.setText(subtitle);
        t2.setTextColor(P.desc);
        t2.setTextSize(11f);
        t2.setPadding(0, dp(1), 0, 0);
        text.addView(t2, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.leftMargin = dp(12);
        r.addView(text, tlp);
        TextView more = new TextView(this);
        more.setText("›");
        more.setTextColor(P.desc);
        more.setTextSize(22f);
        more.setPadding(dp(8), 0, dp(4), 0);
        r.addView(more, new LinearLayout.LayoutParams(-2, -2));
        r.setOnClickListener(click);
        return r;
    }

    // ── the drill-down host (one sub-page overlays the shell) ─────────────────

    /** Open a focused sub-page with a crisp ‹ back bar (Mornye's page pattern). */
    private void openSub(String title, java.util.function.Consumer<LinearLayout> builder) {
        closeSubPage();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(P.bg);
        // the back bar
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(P.card2);
        bar.setPadding(dp(8), dp(10), dp(12), dp(10));
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextColor(P.accent);
        back.setTextSize(26f);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(12), 0, dp(8), 0);
        bar.addView(back, new LinearLayout.LayoutParams(-2, -2));
        TextView barTitle = new TextView(this);
        barTitle.setText(title);
        barTitle.setTextColor(P.title);
        barTitle.setTextSize(16f);
        barTitle.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(barTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        View.OnClickListener pop = v -> closeSubPage();
        back.setOnClickListener(pop);
        barTitle.setOnClickListener(pop);
        page.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        // the scrollable content
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = column();
        scroll.addView(content, new ViewGroup.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        try {
            builder.accept(content);
        } catch (Throwable t) {
            Diag.e("ui", "sub-page build: " + t);
        }
        subPage = page;
        // v3.4.2 — remember how to rebuild THIS page, so a recreate() (theme/accent/
        // language change) re-opens it instead of dropping the user back on Home.
        lastSubOpener = () -> openSub(title, builder);
        pages.addView(page, pages.getChildCount(), new FrameLayout.LayoutParams(-1, -1));   // above every tab page
        page.setAlpha(0f);
        page.animate().alpha(1f).setDuration(160).start();
    }

    private void closeSubPage() {
        if (subPage == null) return;
        View old = subPage;
        subPage = null;
        lastSubOpener = null;
        old.animate().alpha(0f).setDuration(140).withEndAction(() -> pages.removeView(old)).start();
    }

    // ── 🎨 Appearance ───────────────────────────────────────────────────────────

    private void openAppearance() {
        openSub(t("පෙනුම සහ තේමාව", "Appearance & Theme"), content -> {
            LinearLayout c1 = card(P.card);
            final TextView mode = settingRow(c1, t("තේමා ආකාරය", "Theme mode"), themeModeLabel());
            mode.setOnClickListener(v -> {
                final String[] vals = {AppPrefs.THEME_SYSTEM, AppPrefs.THEME_DARK, AppPrefs.THEME_AMOLED, AppPrefs.THEME_LIGHT};
                String[] labels = {t("පද්ධතිය · System", "System"), "Dark · Mornye Slate",
                    "AMOLED · " + t("සම්පූර්ණ කළු #000000", "Pure Black #000000"), "Light"};
                new android.app.AlertDialog.Builder(this)
                    .setTitle(t("තේමා ආකාරය", "Theme mode"))
                    .setItems(labels, (d, which) -> {
                        prefs.edit().putString(AppPrefs.K_THEME_MODE, vals[which]).apply();
                        themeChanged();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            });
            final TextView dyn = settingRow(c1, t("Dynamic Color (Material You)", "Dynamic Color (Material You)"),
                Build.VERSION.SDK_INT >= 31
                    ? (AppPrefs.dynamicColor(prefs) ? "ON · wallpaper" : "OFF")
                    : t("Android 12+ පමණයි", "Android 12+ only"));
            if (Build.VERSION.SDK_INT >= 31) {
                dyn.setTextColor(AppPrefs.dynamicColor(prefs) ? P.ok : P.desc);
                dyn.setOnClickListener(v -> {
                    boolean on = !AppPrefs.dynamicColor(prefs);
                    prefs.edit().putBoolean(AppPrefs.K_DYNAMIC_COLOR, on).apply();
                    themeChanged();
                });
            }
            content.addView(c1, widen());

            // ── v3.4.2 — MIDNIGHT NIGHT DIMMER (dark-room watching) ──────────────
            LinearLayout dimCard = card(P.card);
            TextView dimHead = new TextView(this);
            dimHead.setText(t("මධ්‍යම රාත්‍රී අඳුරු මාදිලිය", "Midnight Night Dimmer"));
            dimHead.setTextColor(P.title);
            dimHead.setTextSize(13.5f);
            dimHead.setTypeface(Typeface.DEFAULT_BOLD);
            UiIcons.setCompound(dimHead, R.drawable.ic_moon, P.accent, 16, Gravity.START);
            dimCard.addView(dimHead, widen(0, 2));
            dimCard.addView(caption(t(
                "තිරයේ දීප්තිය අඩු කර අඳුරු ආවරණයක් යොදයි — අඳුරු කාමරයේ නැරඹීමට.",
                "Lowers the screen backlight and lays a soft dark veil over the app and the"
                + " player — comfortable in a pitch-dark room.")), widen(0, 4));
            toggleRow(dimCard, t("සක්‍රියයි", "Enabled"), AppPrefs.K_MIDNIGHT_DIMMER, false,
                this::applyMidnightDimmer);
            final TextView pctLabel = settingRow(dimCard,
                t("අඳුරු මට්ටම", "Dim strength"),
                AppPrefs.midnightDimPct(prefs) + "%");
            SeekBar dimBar = new SeekBar(this);
            dimBar.setMax(60);                                   // 10..70 → 0..60
            dimBar.setProgress(AppPrefs.midnightDimPct(prefs) - 10);
            dimBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    int pct = AppPrefs.normalizeDimPct(p + 10);
                    pctLabel.setText(pct + "%");
                    if (fromUser) prefs.edit().putInt(AppPrefs.K_MIDNIGHT_DIM_PCT, pct).apply();
                }
                @Override public void onStartTrackingTouch(SeekBar sb) { }
                @Override public void onStopTrackingTouch(SeekBar sb) { applyMidnightDimmer(); }
            });
            dimCard.addView(dimBar, widen(0, 4));
            content.addView(dimCard, widen());

            // the 6 accent color dots
            LinearLayout c2 = card(P.card);
            c2.addView(settingLabel(t("වර්ණ තේමාව (Dynamic Color OFF විට)", "Accent color (when Dynamic Color is OFF)")));
            LinearLayout dots = new LinearLayout(this);
            dots.setOrientation(LinearLayout.HORIZONTAL);
            dots.setGravity(Gravity.CENTER);
            dots.setPadding(0, dp(8), 0, dp(4));
            for (int i = 0; i < ThemeEngine.ACCENTS.length; i++) {
                final int idx = i;
                TextView dot = new TextView(this);
                dot.setGravity(Gravity.CENTER);
                dot.setTextSize(13f);
                dot.setText("");
                if (idx == AppPrefs.accent(prefs)) UiIcons.setIconOnly(dot, R.drawable.ic_check_circle, 0xFF0A0A14, 14);
                dot.setTextColor(0xFFFFFFFF);
                GradientDrawable dg = new GradientDrawable();
                dg.setShape(GradientDrawable.OVAL);
                dg.setColor(ThemeEngine.ACCENTS[idx]);
                if (idx == AppPrefs.accent(prefs)) dg.setStroke(dp(3), 0xFFFFFFFF);
                dot.setBackground(dg);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
                lp.leftMargin = lp.rightMargin = dp(5);
                dot.setLayoutParams(lp);
                dot.setOnClickListener(v -> {
                    prefs.edit().putInt(AppPrefs.K_ACCENT, idx).apply();
                    themeChanged();
                });
                dots.addView(dot, lp);
            }
            c2.addView(dots, widen());
            c2.addView(caption(ThemeEngine.ACCENT_NAMES[AppPrefs.accent(prefs)]));
            // v3.2.0 — the CUSTOM #RRGGBB accent: one row, type a hex, it wins
            // over both the preset dots and Dynamic Color (the explicit user
            // choice, Mornye's own precedence).
            final TextView hexRow = settingRow(c2, t("රිසි #RRGGBB වර්ණය", "Custom #RRGGBB accent"),
                AppPrefs.accentHex(prefs).isEmpty() ? t("නැත (preset එක භාවිතා වේ)", "none (preset in use)")
                    : "#" + AppPrefs.accentHex(prefs));
            hexRow.setTextColor(AppPrefs.accentHex(prefs).isEmpty() ? P.desc : P.accent);
            hexRow.setOnClickListener(v -> {
                LinearLayout box = column();
                box.setPadding(dp(6), dp(4), dp(6), 0);
                final EditText in = new EditText(this);
                in.setHint("#14b8a6");
                in.setText(AppPrefs.accentHex(prefs).isEmpty() ? "" : "#" + AppPrefs.accentHex(prefs));
                in.setTextSize(13f);
                in.setTextColor(P.text);
                box.addView(in, widen());
                new android.app.AlertDialog.Builder(this)
                    .setTitle(t("රිසි වර්ණය · Custom accent", "Custom accent"))
                    .setView(box)
                    .setPositiveButton(t("හරි", "OK"), (d, w) -> {
                        String norm = AppPrefs.normalizeAccentHex(in.getText().toString());
                        prefs.edit().putString(AppPrefs.K_ACCENT_HEX, norm).apply();
                        if (!norm.isEmpty() && AppPrefs.dynamicColor(prefs)) {
                            prefs.edit().putBoolean(AppPrefs.K_DYNAMIC_COLOR, false).apply();   // explicit > wallpaper
                        }
                        themeChanged();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            });
            content.addView(c2, widen());

            // v3.2.0 — CARD CORNER RADIUS (sharp | rounded | Apple pill — the
            // Mornye app_tokens.dart radius family as ONE user-facing shape).
            LinearLayout c2b = card(P.card);
            final TextView rad = settingRow(c2b, t("Card කොන් රේඛාව", "Card corner radius"), radiusLabel());
            rad.setOnClickListener(v -> {
                String cur = AppPrefs.normalizeCardRadius(prefs.getString(AppPrefs.K_CARD_RADIUS, AppPrefs.RADIUS_ROUNDED));
                String next = AppPrefs.RADIUS_SHARP.equals(cur) ? AppPrefs.RADIUS_ROUNDED
                    : (AppPrefs.RADIUS_ROUNDED.equals(cur) ? AppPrefs.RADIUS_PILL : AppPrefs.RADIUS_SHARP);
                prefs.edit().putString(AppPrefs.K_CARD_RADIUS, next).apply();
                rad.setText(radiusLabel());
                themeChanged();
            });
            c2b.addView(caption(t("Sharp 6dp · Rounded 16dp · Apple Pill 24dp — Mornye tokens", "Sharp 6dp · Rounded 16dp · Apple Pill 24dp — Mornye tokens")));
            content.addView(c2b, widen());

            // UI language mode
            LinearLayout c3 = card(P.card);
            final TextView lang = settingRow(c3, t("UI භාෂාව", "UI language"), uiLangLabel());
            lang.setOnClickListener(v -> {
                final String[] vals = {AppPrefs.UI_BI, AppPrefs.UI_SI, AppPrefs.UI_EN};
                String[] labels = {t("ද්විභාෂා (සිංහල + EN)", "Bilingual (සිංහල + EN)"),
                    "සිංහල පමණයි", "English Only"};
                new android.app.AlertDialog.Builder(this)
                    .setTitle(t("UI භාෂාව", "UI language"))
                    .setItems(labels, (d, which) -> {
                        prefs.edit().putString(AppPrefs.K_UI_LANG, vals[which]).apply();
                        themeChanged();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            });
            // layout density (v3.2.0: spacious | comfortable | compact)
            final TextView density = settingRow(c3, t("පිරිසැලසුම ඝනත්වය", "Layout density"), densityLabel());
            density.setOnClickListener(v -> {
                String cur = AppPrefs.normalizeDensity(prefs.getString(AppPrefs.K_DENSITY, AppPrefs.DENSITY_COMFORTABLE));
                String next = AppPrefs.DENSITY_SPACIOUS.equals(cur) ? AppPrefs.DENSITY_COMFORTABLE
                    : (AppPrefs.DENSITY_COMFORTABLE.equals(cur) ? AppPrefs.DENSITY_COMPACT : AppPrefs.DENSITY_SPACIOUS);
                prefs.edit().putString(AppPrefs.K_DENSITY, next).apply();
                density.setText(densityLabel());
                themeChanged();
            });
            // quick-actions visibility (v3.2.0)
            final TextView qa = settingRow(c3, t("ඉක්මන් ක්‍රියා බොත්තම්", "Quick action buttons"),
                AppPrefs.showQuickActions(prefs) ? t("පෙන්වයි", "shown") : t("සැඟවියි", "hidden"));
            qa.setTextColor(AppPrefs.showQuickActions(prefs) ? P.ok : P.desc);
            qa.setOnClickListener(v -> {
                boolean on = !AppPrefs.showQuickActions(prefs);
                prefs.edit().putBoolean(AppPrefs.K_SHOW_QUICK_ACTIONS, on).apply();
                themeChanged();
            });
            // WebView tab visibility
            final TextView web = settingRow(c3, t("WebView tab", "WebView tab"),
                AppPrefs.showWebTab(prefs) ? t("පෙන්වයි (4 tabs)", "shown (4 tabs)") : t("සැඟවිය (3 tabs)", "hidden (3 tabs)"));
            web.setTextColor(AppPrefs.showWebTab(prefs) ? P.ok : P.desc);
            web.setOnClickListener(v -> {
                boolean on = !AppPrefs.showWebTab(prefs);
                prefs.edit().putBoolean(AppPrefs.K_SHOW_WEB_TAB, on).apply();
                themeChanged();   // the tab bar rebuilds
            });
            content.addView(c3, widen());
            content.addView(caption(t("වෙනස්කම් ක්ෂණිකව යෙදේ · changes apply instantly",
                "changes apply instantly")));
        });
    }

    private String themeModeLabel() {
        switch (AppPrefs.themeMode(prefs)) {
            case AppPrefs.THEME_SYSTEM: return t("පද්ධතිය", "System");
            case AppPrefs.THEME_AMOLED: return "AMOLED " + t("කළු", "Black");
            case AppPrefs.THEME_LIGHT: return "Light";
            default: return "Dark";
        }
    }

    private String uiLangLabel() {
        switch (AppPrefs.uiLang(prefs)) {
            case AppPrefs.UI_SI: return "සිංහල පමණයි";
            case AppPrefs.UI_EN: return "English Only";
            default: return t("ද්විභාෂික", "Bilingual");
        }
    }

    /** v3.2.0 — the density label (3 modes). */
    private String densityLabel() {
        String d = AppPrefs.normalizeDensity(prefs.getString(AppPrefs.K_DENSITY, AppPrefs.DENSITY_COMFORTABLE));
        if (AppPrefs.DENSITY_SPACIOUS.equals(d)) return t("විශාල · Spacious", "Spacious");
        if (AppPrefs.DENSITY_COMPACT.equals(d)) return t("සංයුක්ත · Compact", "Compact");
        return t("පහසු · Comfortable", "Comfortable");
    }

    /** v3.2.0 — the card-radius label. */
    private String radiusLabel() {
        String r = AppPrefs.normalizeCardRadius(prefs.getString(AppPrefs.K_CARD_RADIUS, AppPrefs.RADIUS_ROUNDED));
        if (AppPrefs.RADIUS_SHARP.equals(r)) return t("Sharp (6dp)", "Sharp (6dp)");
        if (AppPrefs.RADIUS_PILL.equals(r)) return t("Apple Pill (24dp)", "Apple Pill (24dp)");
        return t("Rounded (16dp)", "Rounded (16dp)");
    }

    private static int indexOfVal(String[] vals, String v) {
        for (int i = 0; i < vals.length; i++) if (vals[i].equals(v)) return i;
        return 0;
    }

    /**
     * Appearance changes rebuild the themed shell instantly (no restart dialog) AND
     * keep the user exactly where they were — same tab, same sub-page, same scroll
     * position (v3.4.2). Before this, every toggle jumped back to Home.
     */
    private void themeChanged() {
        rememberPlace();
        toast(t("යෙදුණි", "applied"));
        recreate();
    }

    /** Capture tab + scroll + the open sub-page so recreate() can put them back. */
    private void rememberPlace() {
        pendingTab = currentTab;
        pendingScrollY = currentScrollY();
        pendingReopen = lastSubOpener;
    }

    /** The scroll offset of whichever scroll container is on screen. */
    private int currentScrollY() {
        try {
            View target = subPage != null ? subPage : pageFor(currentTab);
            ScrollView sv = findScroll(target);
            return sv == null ? -1 : sv.getScrollY();
        } catch (Throwable t) { return -1; }
    }

    private View pageFor(int tab) {
        if (tab == TAB_HOME) return homePage;
        if (tab == TAB_SETTINGS) return settingsPage;
        if (tab == TAB_TROUBLE) return troublePage;
        if (tab == TAB_WEB) return webPage;
        return diagPage;
    }

    /** The first ScrollView inside a view tree (null when there is none). */
    private ScrollView findScroll(View v) {
        if (v == null) return null;
        if (v instanceof ScrollView) return (ScrollView) v;
        if (!(v instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            ScrollView found = findScroll(g.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    /** Put the user back where they were (once) — then clear the slots. */
    private void restorePlace() {
        final int tab = pendingTab;
        final int y = pendingScrollY;
        final Runnable reopen = pendingReopen;
        pendingTab = -1;
        pendingScrollY = -1;
        pendingReopen = null;
        if (tab < 0) return;                       // cold start → Home, as always
        try {
            if (tab != currentTab) selectTab(tab);
            if (reopen != null) reopen.run();       // re-open the same Settings sub-page
            final int sy = y;
            main.post(() -> {
                try {
                    if (sy <= 0) return;
                    View target = subPage != null ? subPage : pageFor(tab);
                    ScrollView sv = findScroll(target);
                    if (sv != null) sv.scrollTo(0, sy);
                } catch (Throwable ignored) { }
            });
        } catch (Throwable t) {
            Diag.w("ui", "restorePlace: " + t);
        }
    }

    // ── v3.4.2 — MIDNIGHT NIGHT DIMMER ────────────────────────────────────────

    private View dimView;

    /**
     * Dim the room without touching the content: the window's own backlight is lowered
     * AND a non-touch-blocking black veil is laid over the shell. A plain View with no
     * click listener returns false from onTouchEvent, so every tap still reaches the UI.
     */
    private void applyMidnightDimmer() {
        try {
            ViewGroup content = findViewById(android.R.id.content);
            if (content == null) return;
            if (dimView != null) { content.removeView(dimView); dimView = null; }
            boolean on = AppPrefs.midnightDimmer(prefs);
            int pct = AppPrefs.midnightDimPct(prefs);
            android.view.WindowManager.LayoutParams wl = getWindow().getAttributes();
            wl.screenBrightness = on ? Math.max(0.05f, 1f - pct / 100f)
                                     : android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(wl);
            if (!on) return;
            View v = new View(this);
            v.setBackgroundColor(android.graphics.Color.argb(Math.round(pct * 2.55f), 0, 0, 0));
            v.setClickable(false);
            v.setFocusable(false);
            content.addView(v, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            dimView = v;
        } catch (Throwable t) {
            Diag.w("ui", "midnight dimmer: " + t);
        }
    }

    // ── 💬 Subtitles & Style Studio (REAL-TIME live preview) ────────────────────

    private void openSubtitleStudio() {
        openSub(t("උපසිරැසි සහ විලුම් චිත්‍රාගාරය", "Subtitles & Style Studio"), content -> {
            // ── THE LIVE CINEMA PREVIEW: re-renders as the sliders move ──
            LinearLayout previewCard = card(0xFF000000);
            GradientDrawable pv = rounded(0xFF05070D, dp(14));
            pv.setStroke(dp(1), 0x22FFFFFF);
            previewCard.setBackground(pv);
            TextView previewLabel = new TextView(this);
            previewLabel.setText(t("සජීවී preview · Live preview", "Live preview"));
            previewLabel.setTextColor(0xFF64748B);
            previewLabel.setTextSize(10.5f);
            previewCard.addView(previewLabel, widen());
            final TextView preview = new TextView(this);
            // v3.3.1 — the preview line carries the Sinhala ZWJ conjunct
            // (ශ්‍රී = ශ + ් + ZWJ + ර + ී), so a broken shaping stack is visible
            // in the studio BEFORE the user ever opens a movie.
            preview.setText("ශ්‍රී ලංකාවේ චිත්‍රපටිය — සිංහල උපසිරැසි");
            preview.setGravity(Gravity.CENTER);
            preview.setPadding(dp(10), dp(4), dp(10), dp(4));
            FrameLayout previewStage = new FrameLayout(this);
            previewStage.setMinimumHeight(dp(96));
            previewStage.addView(preview, new FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
            ((FrameLayout.LayoutParams) preview.getLayoutParams()).bottomMargin = dp(18);
            previewCard.addView(previewStage, new LinearLayout.LayoutParams(-1, dp(104)));
            content.addView(previewCard, widen());
            final Runnable restyle = () -> {
                preview.setTextSize(AppPrefs.fontSp(prefs));
                preview.setTextColor(ThemeEngine.SUB_COLORS[AppPrefs.subColor(prefs)]);
                // v3.2.0 — the studio preview uses the FAMILY too (noto/system/
                // serif) and the line spacing (1.0–1.5), exactly what the player
                // renders; Typeface.create() merges family + bold the ONE way.
                Typeface family = AppPrefs.FONT_NOTO.equals(AppPrefs.fontFamily(prefs)) ? previewSiFont()
                    : (AppPrefs.FONT_SERIF.equals(AppPrefs.fontFamily(prefs)) ? Typeface.SERIF : Typeface.DEFAULT);
                preview.setTypeface(Typeface.create(family, AppPrefs.subBold(prefs) ? Typeface.BOLD : Typeface.NORMAL));
                preview.setLineSpacing(dp(1), AppPrefs.subLineSpacing(prefs) / 100f);
                String bg = AppPrefs.subBg(prefs);
                if (AppPrefs.BG_OUTLINE.equals(bg)) {
                    preview.setBackgroundColor(0);
                    preview.setShadowLayer(dp(4), 0, 0, 0xFF000000);
                } else if (AppPrefs.BG_BOX.equals(bg)) {
                    preview.setBackgroundColor(ThemeEngine.pillColor(Math.max(90, AppPrefs.subBgOpacity(prefs))));
                    preview.setShadowLayer(dp(2), 0, 0, 0x99000000);
                } else if (AppPrefs.BG_SHADOW.equals(bg)) {
                    preview.setBackgroundColor(0);
                    preview.setShadowLayer(dp(6), 0, 0, 0xCC000000);
                } else {
                    preview.setBackgroundColor(ThemeEngine.pillColor(AppPrefs.subBgOpacity(prefs)));
                    preview.setShadowLayer(dp(2), 0, 0, 0x99000000);
                }
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) preview.getLayoutParams();
                lp.bottomMargin = Math.round(dp(104) * AppPrefs.subBottomPct(prefs) / 100f);
                preview.setLayoutParams(lp);
            };
            restyle.run();

            // ── the controls ──
            LinearLayout c1 = card(P.card);
            final TextView langBtn = settingRow(c1, t("පෙරනිමි උපසිරැසි භාෂාව", "Default subtitle language"),
                Langs.nativeOf(prefs.getString("defaultLang", Langs.DEFAULT)) + " · "
                    + Langs.englishOf(prefs.getString("defaultLang", Langs.DEFAULT)));
            langBtn.setOnClickListener(v -> {
                String[] labels = new String[Langs.ALL.size()];
                for (int i = 0; i < Langs.ALL.size(); i++) {
                    Langs.Lang l = Langs.ALL.get(i);
                    labels[i] = l.nativeName + " (" + l.english + ")";
                }
                new android.app.AlertDialog.Builder(this)
                    .setTitle(t("පෙරනිමි උපසිරැසි භාෂාව · 18 languages", "Default subtitle language · 18 languages"))
                    .setItems(labels, (d, which) -> {
                        prefs.edit().putString(AppPrefs.K_DEFAULT_LANG, Langs.ALL.get(which).code).apply();
                        langBtn.setText(Langs.ALL.get(which).nativeName + " · " + Langs.ALL.get(which).english);
                        toast(Langs.ALL.get(which).nativeName);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            });
            final TextView regBtn = settingRow(c1, t("සිංහල වහර · Sinhala register", "Sinhala register"),
                registerLabel());
            regBtn.setOnClickListener(v -> {
                prefs.edit().putString(AppPrefs.K_REGISTER,
                    "spoken".equals(prefs.getString(AppPrefs.K_REGISTER, null)) ? "formal"
                        : "formal".equals(prefs.getString(AppPrefs.K_REGISTER, null)) ? null : "spoken").apply();
                regBtn.setText(registerLabel());
            });
            final TextView autoBtn = toggleRow(c1, t("ස්වයංක්‍රීය උපසිරැසි ලබාගැනීම", "Auto-fetch subtitles"),
                AppPrefs.K_AUTO_FETCH, true);
            final TextView polishBtn = toggleRow(c1, R.drawable.ic_sun, "Cinema Polish", AppPrefs.K_CINEMA_POLISH, true);
            content.addView(c1, widen());

            // font size slider (v3.2.0: 14–40 sp)
            LinearLayout c2 = card(P.card);
            c2.addView(sliderRow(t("අකුරේ ප්‍රමාණය · Font size", "Font size"), 14, 40,
                AppPrefs.fontSp(prefs), " sp", v -> {
                    prefs.edit().putInt(AppPrefs.K_FONT_SP, v).apply();
                    restyle.run();
                }));
            // v3.2.0 — font family (Noto Sans Sinhala | System Sans | Serif)
            final TextView famBtn = settingRow(c2, t("අකුරු පවුල · Font family", "Font family"),
                AppPrefs.FONT_NOTO.equals(AppPrefs.fontFamily(prefs)) ? "Noto Sans Sinhala"
                    : (AppPrefs.FONT_SERIF.equals(AppPrefs.fontFamily(prefs)) ? t("Serif · සෙරිෆ්", "Serif")
                        : t("System Sans · පද්ධති", "System Sans")));
            famBtn.setOnClickListener(v -> {
                String cur = AppPrefs.fontFamily(prefs);
                String next = AppPrefs.FONT_NOTO.equals(cur) ? AppPrefs.FONT_SYSTEM
                    : (AppPrefs.FONT_SYSTEM.equals(cur) ? AppPrefs.FONT_SERIF : AppPrefs.FONT_NOTO);
                prefs.edit().putString(AppPrefs.K_FONT_FAMILY, next).apply();
                reopenStudio();
            });
            // v3.2.0 — line spacing 1.0–1.5 (Sinhala conjunct breathing room)
            c2.addView(sliderRow(t("පේළි පරතරය · Line spacing", "Line spacing") + " (1.0–1.5)",
                100, 150, AppPrefs.subLineSpacing(prefs), "%", v -> {
                    prefs.edit().putInt(AppPrefs.K_SUB_LINE_SPACING, v).apply();
                    restyle.run();
                }));
            // text color dots (v3.2.0: 6 presets)
            c2.addView(settingLabel(t("පෙළ වර්ණය · Text color", "Text color")));
            LinearLayout colors = new LinearLayout(this);
            colors.setOrientation(LinearLayout.HORIZONTAL);
            colors.setGravity(Gravity.CENTER);
            for (int i = 0; i < ThemeEngine.SUB_COLORS.length; i++) {
                final int idx = i;
                TextView dot = new TextView(this);
                dot.setGravity(Gravity.CENTER);
                dot.setTextSize(12f);
                dot.setText("");
                if (idx == AppPrefs.subColor(prefs)) UiIcons.setIconOnly(dot, R.drawable.ic_check_circle, 0xFF0A0A14, 14);
                dot.setTextColor(0xFF000000);
                GradientDrawable dg = new GradientDrawable();
                dg.setShape(GradientDrawable.OVAL);
                dg.setColor(ThemeEngine.SUB_COLORS[idx]);
                if (idx == AppPrefs.subColor(prefs)) dg.setStroke(dp(3), P.accent);
                dot.setBackground(dg);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
                lp.leftMargin = lp.rightMargin = dp(6);
                dot.setOnClickListener(v -> {
                    prefs.edit().putInt(AppPrefs.K_SUB_COLOR, idx).apply();
                    restyle.run();
                    reopenStudio();
                });
                colors.addView(dot, lp);
            }
            c2.addView(colors, widen());
            c2.addView(caption(ThemeEngine.SUB_COLOR_NAMES[AppPrefs.subColor(prefs)]));
            // background style (v3.2.0: pill | outline | full box | shadow-only)
            final TextView bgBtn = settingRow(c2, t("පසුබිම් රටාව", "Background style"), bgStyleLabel());
            bgBtn.setOnClickListener(v -> {
                String cur = AppPrefs.subBg(prefs);
                String next = AppPrefs.BG_PILL.equals(cur) ? AppPrefs.BG_OUTLINE
                    : (AppPrefs.BG_OUTLINE.equals(cur) ? AppPrefs.BG_BOX
                        : (AppPrefs.BG_BOX.equals(cur) ? AppPrefs.BG_SHADOW : AppPrefs.BG_PILL));
                prefs.edit().putString(AppPrefs.K_SUB_BG, next).apply();
                reopenStudio();
            });
            if (AppPrefs.BG_PILL.equals(AppPrefs.subBg(prefs))) {
                c2.addView(sliderRow(t("Pill පාරදෘශ්‍යතාව · Pill opacity", "Pill opacity") + " (0–95%)",
                    0, 95, AppPrefs.subBgOpacity(prefs), "%", v -> {
                        prefs.edit().putInt(AppPrefs.K_SUB_BG_OPACITY, v).apply();
                        restyle.run();
                    }));
            }
            if (AppPrefs.BG_BOX.equals(AppPrefs.subBg(prefs))) {
                c2.addView(sliderRow(t("Box පාරදෘශ්‍යතාව · Box opacity", "Box opacity") + " (0–95%)",
                    0, 95, AppPrefs.subBgOpacity(prefs), "%", v -> {
                        prefs.edit().putInt(AppPrefs.K_SUB_BG_OPACITY, v).apply();
                        restyle.run();
                    }));
            }
            toggleRow(c2, t("තද අකුරු · Bold text (කුඩාකුරු පවුල රඳවා ගන්නී)", "Bold text (keeps the Sinhala family)"),
                AppPrefs.K_SUB_BOLD, true, restyle);
            c2.addView(sliderRow(t("පහළ ස්ථානය · Bottom position", "Bottom position") + " (2–35%)",
                2, 35, AppPrefs.subBottomPct(prefs), "%", v -> {
                    prefs.edit().putInt(AppPrefs.K_SUB_BOTTOM_PCT, v).apply();
                    restyle.run();
                }));
            content.addView(c2, widen());

            // ── v3.2.0 — THE SUBTITLE ENGINE LAYER (the zero-quota studio) ──
            LinearLayout c3 = card(P.card);
            final TextView engBtn = settingRow(c3, t("උපසිරැසි එන්ජිමේ ප්‍රමුඛතාව", "Subtitle engine priority"),
                engineLabel());
            engBtn.setOnClickListener(v -> {
                String cur = AppPrefs.subEngine(prefs);
                String next = AppPrefs.ENGINE_HUMAN_PA_FREE.equals(cur) ? AppPrefs.ENGINE_HUMAN_FREE
                    : (AppPrefs.ENGINE_HUMAN_FREE.equals(cur) ? AppPrefs.ENGINE_FREE
                        : (AppPrefs.ENGINE_FREE.equals(cur) ? AppPrefs.ENGINE_WORKER : AppPrefs.ENGINE_HUMAN_PA_FREE));
                prefs.edit().putString(AppPrefs.K_SUB_ENGINE, next).apply();
                engBtn.setText(engineLabel());
                toast(engineLabel());
            });
            c3.addView(caption(t("ලේයර්: 1) human Sinhala .srt/.zip  2) Worker keyless PA  3) නොමිලේ keyless පරිවර්තකය — කෝටා කවර වුණත් උපසිරැසි මැරෙන්නේ නැත.",
                "Layers: 1) human Sinhala .srt/.zip  2) Worker keyless PA  3) free keyless on-device translator — subtitles never die of quota.")));
            toggleRow(c3, t("සිංහල නොවන track ස්වයංක්‍රීයව පරිවර්තනය", "Auto-translate non-Sinhala tracks"),
                AppPrefs.K_AUTO_TRANSLATE_NON_SI, true, null);
            toggleRow(c3, t(".zip ස්වයංක්‍රීයව extract + attach", "Auto-extract .zip subtitles"),
                AppPrefs.K_AUTO_ZIP, true, null);
            toggleRow(c3, R.drawable.ic_translate, t("නොමිලේ keyless පරිවර්තකය (Layer 3)", "Free keyless translator (Layer 3)"),
                AppPrefs.K_FREE_TRANSLATOR, true, null);
            content.addView(c3, widen());
        });
    }

    /** The engine priority label (the 4 orders). */
    private String engineLabel() {
        String e = AppPrefs.subEngine(prefs);
        if (AppPrefs.ENGINE_HUMAN_FREE.equals(e)) return t("human → නොමිලේ පරිවර්තකය", "human → free translator");
        if (AppPrefs.ENGINE_FREE.equals(e)) return t("නොමිලේ පරිවර්තකය පමණයි", "free translator only");
        if (AppPrefs.ENGINE_WORKER.equals(e)) return t("Worker පමණයි (AI providers)", "Worker only (AI providers)");
        return t("human → Worker PA → නොමිලේ (පෙරනිමි)", "human → Worker PA → free (default)");
    }

    /** The background-style label (4 styles). */
    private String bgStyleLabel() {
        String b = AppPrefs.subBg(prefs);
        if (AppPrefs.BG_OUTLINE.equals(b)) return t("Crisp black edge/outline", "Crisp black edge/outline");
        if (AppPrefs.BG_BOX.equals(b)) return t("සම්පූර්ණ box", "Full box");
        if (AppPrefs.BG_SHADOW.equals(b)) return t("සෙවුම් පමණයි", "Drop shadow only");
        return t("අර්ධ පාරදෘශ්‍ය pill", "Translucent pill");
    }

    /** The bundled Sinhala font for the studio preview (loaded once per activity). */
    private Typeface previewSiFont() {
        if (siFontPreview == null) {
            try {
                // v3.4.0 - the real 400-weight instance (never faux bold, never a crash)
                siFontPreview = UiTokens.si(this, false);
            } catch (Throwable t) {
                siFontPreview = Typeface.DEFAULT;
            }
        }
        return siFontPreview;
    }
    private Typeface siFontPreview;

    /** Re-open the studio (a dot/bg change repaints its own checkmarks). */
    private void reopenStudio() { openSubtitleStudio(); }

    private String registerLabel() {
        String o = prefs.getString(AppPrefs.K_REGISTER, null);
        return o == null ? t("ස්වයංක්‍රීය (genre අනුව)", "Auto (by genre)")
            : "spoken".equals(o) ? t("කතා වහර · Spoken", "Spoken") : t("උගත් වහර · Formal", "Formal");
    }

    // ── 🎬 Playback & Gestures (v3.2.0: speed · aspect · double-tap) ────────────

    private void openPlayback() {
        openSub(t("ධාවනය සහ gestures", "Playback & Gestures"), content -> {
            LinearLayout c0 = card(P.card);
            // v3.2.0 — DEFAULT PLAYBACK SPEED (0.5x–2.0x): a slider (per-mille),
            // applied by PlayActivity at every open.
            c0.addView(sliderRow(t("පෙරනිමි ධාවන වේගය · Default speed", "Default speed") + " (0.5x–2.0x)",
                50, 200, AppPrefs.playSpeedPm(prefs), "%", v -> {
                    prefs.edit().putInt(AppPrefs.K_PLAY_SPEED, v).apply();
                }));
            c0.addView(caption(String.format(java.util.Locale.US, "%.2fx — %s",
                AppPrefs.playSpeedPm(prefs) / 100f,
                t("සෑම වීඩියෝවක්ම මෙයින් ඇරඹේ", "every video opens at this speed"))));
            // v3.2.0 — DEFAULT ASPECT MODE (fit | fill | zoom)
            final TextView asp = settingRow(c0, t("පෙරනිමි රූප පරිමාණය · Default aspect", "Default aspect"),
                AppPrefs.aspectMode(prefs));
            asp.setOnClickListener(v -> {
                String cur = AppPrefs.aspectMode(prefs);
                String next = AppPrefs.ASPECT_FIT.equals(cur) ? AppPrefs.ASPECT_FILL
                    : (AppPrefs.ASPECT_FILL.equals(cur) ? AppPrefs.ASPECT_ZOOM : AppPrefs.ASPECT_FIT);
                prefs.edit().putString(AppPrefs.K_ASPECT_MODE, next).apply();
                asp.setText(next);
            });
            content.addView(c0, widen());

            LinearLayout c1 = card(P.card);
            final TextView seek = settingRow(c1, t("Seek පියවර", "Seek step"),
                (AppPrefs.seekStepMs(prefs) / 1000) + " s");
            seek.setOnClickListener(v -> {
                final long[] vals = {5000, 10000, 15000, 30000};
                String[] labels = {"5 s", "10 s", "15 s", "30 s"};
                new android.app.AlertDialog.Builder(this)
                    .setTitle(t("Seek පියවර", "Seek step"))
                    .setItems(labels, (d, which) -> {
                        prefs.edit().putLong(AppPrefs.K_SEEK_STEP_MS, vals[which]).apply();
                        seek.setText((vals[which] / 1000) + " s");
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            });
            toggleRow(c1, t("Swipe gestures (දීප්තිය · ශබ්දය)", "Swipe gestures (brightness · volume)"),
                AppPrefs.K_GESTURES, true);
            toggleRow(c1, t("ද්විත්ව-තට්ටු seek", "Double-tap side seek"), AppPrefs.K_DOUBLE_TAP_SEEK, true);
            toggleRow(c1, "Auto-PiP", AppPrefs.K_AUTO_PIP, true);
            toggleRow(c1, t("තිරය අවදියෙන් තබාගන්න", "Keep screen on"), AppPrefs.K_KEEP_SCREEN_ON, true);
            toggleRow(c1, t("අවසාන ස්ථානයෙන් ඉබේම", "Auto-resume last position"), AppPrefs.K_AUTO_RESUME, true);
            content.addView(c1, widen());

            LinearLayout c2 = card(P.card);
            final TextView hud = settingRow(c2, t("පාලක සැඟවීමේ ප්‍රමාදය", "Auto-hide controls delay"),
                (AppPrefs.hudHideMs(prefs) / 1000) + " s");
            hud.setOnClickListener(v -> {
                final long[] vals = {2000, 4000, 6000, 8000};
                String[] labels = {"2 s", "4 s", "6 s", "8 s"};
                new android.app.AlertDialog.Builder(this)
                    .setTitle(t("පාලක සැඟවීමේ ප්‍රමාදය", "Auto-hide controls delay"))
                    .setItems(labels, (d, which) -> {
                        prefs.edit().putLong(AppPrefs.K_HUD_HIDE_MS, vals[which]).apply();
                        hud.setText((vals[which] / 1000) + " s");
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            });
            final TextView sync = settingRow(c2, t("උපසිරැසි කාල ගැලපුම් පියවර", "Subtitle sync step"),
                (AppPrefs.syncStepMs(prefs) / 1000f) + " s");
            sync.setOnClickListener(v -> {
                final long[] vals = {100, 250, 500};
                String[] labels = {"0.1 s", "0.25 s", "0.5 s"};
                new android.app.AlertDialog.Builder(this)
                    .setTitle(t("උපසිරැසි කාල ගැලපුම් පියවර", "Subtitle sync step"))
                    .setItems(labels, (d, which) -> {
                        prefs.edit().putLong(AppPrefs.K_SYNC_STEP_MS, vals[which]).apply();
                        sync.setText((vals[which] / 1000f) + " s");
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            });
            content.addView(c2, widen());
        });
    }

    // ── 🌐 Web Player & Custom Links (v3.2.0 — the Mornye drill-down pattern) ───

    /**
     * v3.2.0 — THE CUSTOM LINKS MANAGER: users can Add / Edit / Rename /
     * Reorder / Delete their streaming-site quick-launch URLs (the "so users
     * can update/replace links anytime without needing an app update"
     * contract), plus the WebView policy toggles (Desktop UA, Ad/Popup
     * Blocker, Auto-Sniff & Auto-Open). Pure preferences — WebBrowserView
     * reads the same list on every build, so a link edit is live on the next
     * Web tab open, zero app update needed. */
    private void openWebPlayer() {
        openSub(t("Web Player සහ Custom Links", "Web Player & Custom Links"), content -> {
            content.addView(caption(t("ඔබේම අඩවි ලැයිස්තුව — අලුත් link එකක් හදාගන්න, නම් කරන්න, අනුපිළැවෙන්න, මකන්න — app යාවත්කාලීනයක් අවශ්‍ය නැත.",
                "Your own site list — add, rename, reorder or delete streaming links any time; no app update ever needed.")));

            // ── the links list (live CRUD) ──
            final LinearLayout linkCard = card(P.card);
            content.addView(linkCard, widen());
            paintLinks(linkCard);

            // ── the add row ──
            LinearLayout addCard = card(P.card);
            final EditText nameIn = new EditText(this);
            nameIn.setHint(t("අඩවියේ නම (උදා: Baiscope)", "Site name (e.g. Baiscope)"));
            nameIn.setTextSize(13f);
            nameIn.setTextColor(P.text);
            nameIn.setHintTextColor(P.desc);
            addCard.addView(nameIn, widen());
            final EditText urlIn = new EditText(this);
            urlIn.setHint("https://…");
            urlIn.setTextSize(13f);
            urlIn.setTextColor(P.text);
            urlIn.setHintTextColor(P.desc);
            addCard.addView(urlIn, widen());
            Button add = iconPrimaryBtn(R.drawable.ic_plus, t("link එක එකතු කරන්න", "Add the link"));
            add.setOnClickListener(v -> {
                String nm = nameIn.getText().toString().trim();
                String ul = urlIn.getText().toString().trim();
                if (nm.isEmpty() || ul.length() < 8) { toast(t("නමයි URL යයි දෙකම අවශ්‍යයි", "both a name and a URL are needed")); return; }
                if (!(ul.startsWith("https://") || ul.startsWith("http://"))) { toast(t("http(s) URL පමණයි", "http(s) URLs only")); return; }
                List<AppPrefs.Link> links = new ArrayList<>(AppPrefs.webLinks(prefs));
                for (AppPrefs.Link l : links) if (l.url.equals(ul)) { toast(t("URL එක දැනටමත් තියෙනවා", "already in the list")); return; }
                if (links.size() >= 8) { toast(t("link 8ක් පමණයි (පිළිවෙලින්)", "at most 8 links (in order)")); return; }
                links.add(new AppPrefs.Link(nm, ul));
                prefs.edit().putString(AppPrefs.K_WEB_LINKS, AppPrefs.serializeLinks(links)).apply();
                nameIn.setText(""); urlIn.setText("");
                paintLinks(linkCard);
                toast(nm + " " + t("එකතු විය", "added"));
            });
            addCard.addView(add, widen());
            content.addView(addCard, widen());

            // ── the WebView policy toggles ──
            LinearLayout c2 = card(P.card);
            toggleRow(c2, R.drawable.ic_desktop, t("Desktop User-Agent", "Desktop User-Agent"),
                AppPrefs.K_WEB_DESKTOP_UA, false, null);
            toggleRow(c2, R.drawable.ic_shield_adblock, t("Ad/Popup Blocker", "Ad/Popup Blocker"),
                AppPrefs.K_AD_BLOCK, true, null);
            toggleRow(c2, R.drawable.ic_refresh, t("Auto-Sniff & Auto-Open Cinema Player", "Auto-Sniff & Auto-Open Cinema Player"),
                AppPrefs.K_AUTO_OPEN_SNIFFED, false, null);
            content.addView(c2, widen());
        });
    }

    /** Paint the links list card (rename/reorder/delete per link, 1 tap each). */
    private void paintLinks(final LinearLayout linkCard) {
        linkCard.removeAllViews();
        final List<AppPrefs.Link> links = AppPrefs.webLinks(prefs);
        if (links.isEmpty()) {
            linkCard.addView(caption(t("තව link නැත — පහළින් එකක් එකතු කරන්න. Web ටැබ් එකේ launcher එක මෙයින් පිරේ.",
                "No links yet — add one below. The Web tab's quick launcher fills from here.")));
            return;
        }
        for (int i = 0; i < links.size(); i++) {
            final int idx = i;
            final AppPrefs.Link l = links.get(i);
            LinearLayout r = row();
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, dp(6), 0, dp(6));
            TextView nm = new TextView(this);
            nm.setText("• " + l.name);
            nm.setTextColor(P.text);
            nm.setTextSize(13f);
            r.addView(nm, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView ul = new TextView(this);
            ul.setText(StreamSniffer.hostOf(l.url));
            ul.setTextColor(P.desc);
            ul.setTextSize(11f);
            r.addView(ul, new LinearLayout.LayoutParams(-2, -2));
            Button up = smallBtn("↑");
            LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(dp(26), dp(30));
            ulp.leftMargin = dp(4);
            up.setLayoutParams(ulp);
            up.setOnClickListener(v -> {
                if (idx == 0) return;
                java.util.Collections.swap(links, idx, idx - 1);
                prefs.edit().putString(AppPrefs.K_WEB_LINKS, AppPrefs.serializeLinks(links)).apply();
                paintLinks(linkCard);
            });
            r.addView(up, ulp);
            Button dn = smallBtn("↓");
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(26), dp(30));
            dn.setLayoutParams(dlp);
            dn.setOnClickListener(v -> {
                if (idx >= links.size() - 1) return;
                java.util.Collections.swap(links, idx, idx + 1);
                prefs.edit().putString(AppPrefs.K_WEB_LINKS, AppPrefs.serializeLinks(links)).apply();
                paintLinks(linkCard);
            });
            r.addView(dn, dlp);
            Button del = iconOnlyBtn(R.drawable.ic_close, P.err);
            LinearLayout.LayoutParams delp = new LinearLayout.LayoutParams(dp(30), dp(30));
            delp.leftMargin = dp(4);
            del.setLayoutParams(delp);
            del.setOnClickListener(v -> {
                links.remove(idx);
                prefs.edit().putString(AppPrefs.K_WEB_LINKS, AppPrefs.serializeLinks(links)).apply();
                paintLinks(linkCard);
                toast(l.name + " " + t("ඉවත් කළා", "removed"));
            });
            r.addView(del, delp);
            // 1-tap rename
            nm.setOnClickListener(v -> {
                LinearLayout box = column();
                box.setPadding(dp(6), dp(4), dp(6), 0);
                final EditText in = new EditText(this);
                in.setText(l.name);
                in.setTextSize(13f);
                in.setTextColor(P.text);
                box.addView(in, widen());
                new android.app.AlertDialog.Builder(this)
                    .setTitle(t("නම වෙනස් කරන්න · rename", "Rename the link"))
                    .setView(box)
                    .setPositiveButton(t("හරි", "OK"), (d, w) -> {
                        String nn = in.getText().toString().trim();
                        if (nn.isEmpty()) return;
                        List<AppPrefs.Link> cur = new ArrayList<>(AppPrefs.webLinks(prefs));
                        if (idx < cur.size()) cur.set(idx, new AppPrefs.Link(nn, l.url));
                        prefs.edit().putString(AppPrefs.K_WEB_LINKS, AppPrefs.serializeLinks(cur)).apply();
                        paintLinks(linkCard);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            });
            // 1-tap open
            r.setOnClickListener(v -> {
                selectTab(TAB_WEB);
                if (browser != null) browser.load(l.url);
            });
            linkCard.addView(r, widen());
        }
    }

    // ── 📡 Cast & Network ──────────────────────────────────────────────────────

    private void openCastNetwork() {
        openSub(t("Cast සහ ජාලය", "Cast & Network"), content -> {
            LinearLayout c1 = card(P.card);
            final TextView name = settingRow(c1, t("රිසීවරයේ නම (DLNA)", "Receiver display name (DLNA)"),
                AppPrefs.receiverName(prefs));
            name.setOnClickListener(v -> {
                LinearLayout box = column();
                box.setPadding(dp(6), dp(4), dp(6), 0);
                final EditText in = new EditText(this);
                in.setText(AppPrefs.receiverName(prefs));
                in.setTextSize(13f);
                in.setTextColor(P.text);
                in.setBackground(rounded(P.card2, dp(10)));
                in.setPadding(dp(10), dp(10), dp(10), dp(10));
                box.addView(in, widen());
                new android.app.AlertDialog.Builder(this)
                    .setTitle(t("රිසීවරයේ නම", "Receiver display name"))
                    .setView(box)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        prefs.edit().putString(AppPrefs.K_RECEIVER_NAME, in.getText().toString()).apply();
                        applyCastPrefs();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            });
            content.addView(c1, widen());

            LinearLayout c2 = card(P.card);
            final TextView dlna = toggleRow(c2, t("DLNA renderer (SSDP 1900)", "DLNA renderer (SSDP 1900)"),
                AppPrefs.K_DLNASTART, true, this::applyCastPrefs);
            final TextView mdns = toggleRow(c2, t("mDNS announcer (NsdManager + 5353)", "mDNS announcer (NsdManager + 5353)"),
                AppPrefs.K_MDNS, true, this::applyCastPrefs);
            content.addView(c2, widen());
            content.addView(caption(t("අකුරු මාරු කළ විට receiver එක නැවත අරඹනවා — cast පැති දෙකම 100% ක්‍රියාකාරී තිබිය යුතුයි.",
                "Toggles re-arm the receiver — keep at least one discovery pillar on.")));

            LinearLayout c3 = card(P.card);
            final EditText url = new EditText(this);
            url.setText(AppPrefs.workerUrl(prefs));
            url.setTextSize(12f);
            url.setTextColor(P.text);
            url.setHintTextColor(P.desc);
            url.setBackground(rounded(P.card2, dp(10)));
            url.setPadding(dp(10), dp(9), dp(10), dp(9));
            c3.addView(settingLabel("Cloudflare Worker URL · " + t("උපසිරැසි පාලම", "subtitle bridge")));
            c3.addView(url, widen());
            LinearLayout srow = row();
            Button save = smallBtn(t("සුරකින්න · Save", "Save"));
            save.setOnClickListener(v -> {
                prefs.edit().putString(AppPrefs.K_WORKER_URL, url.getText().toString().trim()).apply();
                toast(t("සුරැකිණි", "saved"));
            });
            srow.addView(save, new LinearLayout.LayoutParams(0, -2, 1f));
            Button reset = smallBtn("Reset");
            reset.setOnClickListener(v -> {
                url.setText(BridgeApi.DEFAULT_WORKER_URL);
                prefs.edit().putString(AppPrefs.K_WORKER_URL, BridgeApi.DEFAULT_WORKER_URL).apply();
                toast(t("පෙරනිමිය", "default"));
            });
            srow.addView(reset, new LinearLayout.LayoutParams(0, -2, 1f));
            c3.addView(srow, widen());
            final TextView pingLine = settingRow(c3, t("Worker ping", "Worker ping"),
                t("දැන් පරීක්ෂා කරන්න →", "tap to test now →"));
            pingLine.setTextColor(P.accentDim);
            pingLine.setOnClickListener(v -> pingWorker(pingLine));
            toggleRow(c3, t("DoH ISP-block දෝෂ නිර්ණය", "DoH ISP-block diagnostics"), AppPrefs.K_DOH_DIAG, true);
            content.addView(c3, widen());
        });
    }

    /** Poke the receiver service to re-arm with fresh cast prefs. */
    private void applyCastPrefs() {
        try {
            startService(new Intent(this, ReceiverService.class).setAction(ReceiverService.ACTION_APPLY_PREFS));
            toast(t("receiver නැවත අරඹන ලදී", "receiver re-armed"));
        } catch (Throwable t) {
            Diag.w("ui", "apply cast prefs: " + t);
        }
    }

    private void pingWorker(final TextView line) {
        line.setText("…");
        final String base = AppPrefs.workerUrl(prefs);
        new Thread(() -> {
            String out;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(base + "/health").openConnection();
                c.setConnectTimeout(2500);
                c.setReadTimeout(2500);
                int code = c.getResponseCode();
                String body = code == 200 ? new String(readAll(c.getInputStream()), StandardCharsets.UTF_8) : "";
                c.disconnect();
                out = code == 200 ? "HTTP 200 · " + BridgeApi.brief(body, 60) : "HTTP " + code;
                Diag.i("ping", "worker " + base + " → " + code);
            } catch (Exception e) {
                out = String.valueOf(e.getMessage());
                Diag.w("ping", "worker: " + e);
            }
            final String o = out;
            main.post(() -> line.setText(o));
        }, "si-ping").start();
    }

    // ── ⬆️ Silent Updates ───────────────────────────────────────────────────────

    private void openUpdates() {
        openSub(t("නිහඬ යාවත්කාලීන", "Silent Updates"), content -> {
            content.addView(caption(t("v3.1.0: යාවත්කාලීන 100% නිහඬයි — ශබ්දයක් නැත, vibration නැත, spam නැත. Progress දැනුම්දීම් තත්පරයට වරකට වඩා නොයයි.",
                "v3.1.0: updates are 100% silent — no sound, no vibration, no spam. Progress posts at most once per second.")));
            LinearLayout c1 = card(P.card);
            toggleRow(c1, t("ස්වයංක්‍රීය යාවත්කාලීනය", "Auto-update on app open"),
                AppPrefs.K_AUTO_UPDATE, true, () -> SelfUpdater.checkOnLaunch(this));
            settingRow(c1, t("සංස්කරණය", "Current version"), "v" + AppInfo.VERSION_NAME + " (code " + AppInfo.VERSION_CODE + ")");
            final TextView installPerm = settingRow(c1, t("Install unknown apps", "Install unknown apps"),
                canInstallApks() ? t("ලැබී ඇත", "granted") : t("නැත (1-tap)", "not granted (1-tap)"));
            installPerm.setTextColor(canInstallApks() ? P.ok : P.warn);
            installPerm.setOnClickListener(v -> openInstallPermission());
            final TextView check = settingRow(c1, R.drawable.ic_search, t("දැන් පරීක්ෂා කරන්න", "Check now"), "GitHub Releases");
            check.setTextColor(P.accentDim);
            check.setOnClickListener(v -> checkForUpdates());
            settingRow(c1, R.drawable.ic_info, "Obtainium", t("ගැලපේ — එකම signing key", "compatible — same permanent key"));
            content.addView(c1, widen());

            // the Skip-this-version control (mirror of the notification action)
            final String skipped = getSharedPreferences("selfupdate", MODE_PRIVATE).getString("skippedVersion", null);
            LinearLayout c2 = card(P.card);
            if (skipped != null && !skipped.isEmpty()) {
                final TextView sk = settingRow(c2, t("Skip කළ සංස්කරණය", "Skipped version"), "v" + skipped);
                Button un = smallBtn(t("නැවත සලකුණු කරන්න", "un-skip"));
                un.setOnClickListener(v -> {
                    getSharedPreferences("selfupdate", MODE_PRIVATE).edit().remove("skippedVersion").apply();
                    toast(t("skip ඉවත් කළා", "un-skipped"));
                    closeSubPage();
                });
                c2.addView(un, widen());
            } else {
                c2.addView(caption(t("නව සංස්කරණයක් පැමිණි විට දැනුම්දීමේ \"Skip v…\" බොත්තමෙන් එය ස්ථිරව මඟහැරිය හැක.",
                    "When a new release arrives, its quiet notification carries a \"Skip v…\" action that silences it forever.")));
            }
            content.addView(c2, widen());
        });
    }

    // ── 💾 Backup & Restore ─────────────────────────────────────────────────────

    private void openBackup() {
        openSub(t("Backup & Restore", "Backup & Restore"), content -> {
            content.addView(caption(t("ඔබේ සියලු සැකසුම් එක පිරිසිදු JSON ගොනුවකින් — ගෙන යා හැක, නැවත දා හැක. \"Yours to Keep\" · your entire setup in one clean JSON file.",
                "Every setting exports as one clean JSON file — carry it to a new phone and import it back. Yours to keep.")));
            LinearLayout c1 = card(P.card);
            Button exportBtn = iconPrimaryBtn(R.drawable.ic_share, t("Export — ගොනුවකට සුරකින්න", "Export to a file"));
            exportBtn.setOnClickListener(v -> {
                try {
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("application/json");
                    i.putExtra(Intent.EXTRA_TITLE, "sinhala-player-settings-v" + AppInfo.VERSION_NAME + ".json");
                    startActivityForResult(i, 50);
                } catch (Throwable t) {
                    copyBackupToClipboard();
                }
            });
            c1.addView(exportBtn, widen());
            Button copyBtn = iconBtn(R.drawable.ic_copy, t("JSON එක clipboard එකට", "Copy JSON to clipboard"));
            copyBtn.setOnClickListener(v -> copyBackupToClipboard());
            c1.addView(copyBtn, widen());
            Button importBtn = iconBtn(R.drawable.ic_download, t("Import — ගොනුවකින්", "Import from a file"));
            importBtn.setOnClickListener(v -> {
                try {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(i, 51);
                } catch (Throwable t) {
                    Diag.w("backup", "import picker: " + t);
                    pasteBackupFromClipboard();
                }
            });
            c1.addView(importBtn, widen());
            Button pasteBtn = iconBtn(R.drawable.ic_copy, t("Clipboard JSON එක import කරන්න", "Paste JSON to import"));
            pasteBtn.setOnClickListener(v -> pasteBackupFromClipboard());
            c1.addView(pasteBtn, widen());
            content.addView(c1, widen());
            content.addView(caption(t("Import එක strict-schema පරීක්ෂාවකින් යයි — වැරදි ගොනුවක් කිසිවක් වෙනස් නොකරයි.",
                "Imports pass strict schema validation — a corrupt file never changes anything.")));
        });
    }

    /** Gather the current settings (normalized) for export. */
    private Map<String, Object> gatherSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(AppPrefs.K_THEME_MODE, AppPrefs.themeMode(prefs));
        m.put(AppPrefs.K_DYNAMIC_COLOR, AppPrefs.dynamicColor(prefs));
        m.put(AppPrefs.K_ACCENT, AppPrefs.accent(prefs));
        m.put(AppPrefs.K_UI_LANG, AppPrefs.uiLang(prefs));
        m.put(AppPrefs.K_DENSITY, AppPrefs.compact(prefs) ? AppPrefs.DENSITY_COMPACT : AppPrefs.DENSITY_COMFORTABLE);
        m.put(AppPrefs.K_SHOW_WEB_TAB, AppPrefs.showWebTab(prefs));
        m.put(AppPrefs.K_DEFAULT_LANG, prefs.getString(AppPrefs.K_DEFAULT_LANG, Langs.DEFAULT));
        String reg = prefs.getString(AppPrefs.K_REGISTER, null);
        if (reg != null) m.put(AppPrefs.K_REGISTER, reg);
        m.put(AppPrefs.K_AUTO_FETCH, prefs.getBoolean(AppPrefs.K_AUTO_FETCH, true));
        m.put(AppPrefs.K_CINEMA_POLISH, AppPrefs.cinemaPolish(prefs));
        m.put(AppPrefs.K_FONT_SP, AppPrefs.fontSp(prefs));
        m.put(AppPrefs.K_SUB_COLOR, AppPrefs.subColor(prefs));
        m.put(AppPrefs.K_SUB_BG, AppPrefs.subBg(prefs));
        m.put(AppPrefs.K_SUB_BG_OPACITY, AppPrefs.subBgOpacity(prefs));
        m.put(AppPrefs.K_SUB_BOLD, AppPrefs.subBold(prefs));
        m.put(AppPrefs.K_SUB_BOTTOM_PCT, AppPrefs.subBottomPct(prefs));
        m.put(AppPrefs.K_SYNC_STEP_MS, AppPrefs.syncStepMs(prefs));
        m.put(AppPrefs.K_SEEK_STEP_MS, AppPrefs.seekStepMs(prefs));
        m.put(AppPrefs.K_GESTURES, AppPrefs.gestures(prefs));
        m.put(AppPrefs.K_AUTO_PIP, AppPrefs.autoPip(prefs));
        m.put(AppPrefs.K_KEEP_SCREEN_ON, AppPrefs.keepScreenOn(prefs));
        m.put(AppPrefs.K_AUTO_RESUME, AppPrefs.autoResume(prefs));
        m.put(AppPrefs.K_HUD_HIDE_MS, AppPrefs.hudHideMs(prefs));
        m.put(AppPrefs.K_DOH_DIAG, AppPrefs.dohDiag(prefs));
        m.put(AppPrefs.K_RECEIVER_NAME, AppPrefs.receiverName(prefs));
        m.put(AppPrefs.K_DLNASTART, AppPrefs.dlnaEnabled(prefs));
        m.put(AppPrefs.K_MDNS, AppPrefs.mdnsEnabled(prefs));
        m.put(AppPrefs.K_WORKER_URL, AppPrefs.workerUrl(prefs));
        m.put(AppPrefs.K_AUTO_UPDATE, prefs.getBoolean(AppPrefs.K_AUTO_UPDATE, true));
        m.put(AppPrefs.K_AUTO_OPEN_SNIFFED, prefs.getBoolean(AppPrefs.K_AUTO_OPEN_SNIFFED, false));
        m.put(AppPrefs.K_WEB_DESKTOP_UA, prefs.getBoolean(AppPrefs.K_WEB_DESKTOP_UA, false));
        return m;
    }

    private void copyBackupToClipboard() {
        try {
            String json = SettingsBackup.export(AppInfo.VERSION_NAME, System.currentTimeMillis(), gatherSettings());
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("sinhala-player-settings", json));
            toast(t("JSON " + json.length() + " අකුරු copied", "JSON copied (" + json.length() + " chars)"));
            Diag.i("backup", "exported " + json.length() + " chars to clipboard");
        } catch (Throwable t) {
            Diag.w("backup", "clipboard export: " + t);
        }
    }

    private void pasteBackupFromClipboard() {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            CharSequence cs = cm == null || !cm.hasPrimaryClip() ? null : cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            applyImportedJson(cs == null ? "" : cs.toString());
        } catch (Throwable t) {
            Diag.w("backup", "clipboard import: " + t);
        }
    }

    /** Validate + apply an import document (never partial: everything or nothing). */
    private void applyImportedJson(String json) {
        SettingsBackup.Import r = SettingsBackup.parseImport(json);
        if (!r.ok) {
            toast(r.errorSi + " · " + r.error);
            Diag.w("backup", "import rejected: " + r.error);
            return;
        }
        SharedPreferences.Editor e = prefs.edit();
        for (Map.Entry<String, Object> en : r.values.entrySet()) {
            String k = en.getKey();
            Object v = en.getValue();
            if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
            else if (v instanceof Integer) e.putInt(k, (Integer) v);
            else if (v instanceof Long) e.putLong(k, (Long) v);
            else e.putString(k, String.valueOf(v));
        }
        e.apply();
        Diag.i("backup", "imported " + r.values.size() + " settings (" + r.rejected + " rejected)");
        toast(r.values.size() + " " + t("settings import කළා", "settings imported"));
        closeSubPage();
        applyCastPrefs();
        recreate();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri u = data.getData();
        if (req == 50) {   // export
            new Thread(() -> {
                try {
                    String json = SettingsBackup.export(AppInfo.VERSION_NAME, System.currentTimeMillis(), gatherSettings());
                    try (java.io.OutputStream o = getContentResolver().openOutputStream(u)) {
                        o.write(json.getBytes(StandardCharsets.UTF_8));
                    }
                    Diag.i("backup", "exported to " + u);
                    main.post(() -> toast(t("ගොනුව සුරැකිණි", "file saved")));
                } catch (Exception e) {
                    Diag.w("backup", "export: " + e);
                    main.post(() -> toast(String.valueOf(e.getMessage())));
                }
            }, "si-export").start();
        } else if (req == 51) {   // import
            new Thread(() -> {
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
                    final String json = new String(o.toByteArray(), StandardCharsets.UTF_8);
                    main.post(() -> applyImportedJson(json));
                } catch (Exception e) {
                    Diag.w("backup", "import: " + e);
                    main.post(() -> toast(String.valueOf(e.getMessage())));
                }
            }, "si-import").start();
        }
    }

    // ── ℹ️ About & Security ─────────────────────────────────────────────────────

    private void openAbout() {
        openSub(t("පිළිබඳ සහ ආරක්ෂණය", "About & Security"), content -> {
            LinearLayout c1 = card(P.card);
            settingRow(c1, t("සංස්කරණය", "Version"), "v" + AppInfo.VERSION_NAME + " (code " + AppInfo.VERSION_CODE + ") · targetSdk 36");
            settingRow(c1, t("ගොනු ගබඩාව", "Repository"), "dictationb1/anantplay-sinhala-player");
            settingRow(c1, "AnantPlay", t("100% නිල කළමේ වෙනස් කළ — zero fork", "100% official + unmodified — zero fork"));
            content.addView(c1, widen());

            LinearLayout c2 = card(P.card);
            c2.addView(settingLabel(t("ආරක්ෂණ විග්‍රහය · Security audit", "Security audit")));
            for (String line : new String[]{
                t("සීමිත අවසර පමණයි (ජාලය · දැනුම්දීම් · install · overlay)", "minimal permissions (network · notifications · install · overlay)"),
                t("Analytics/ads/telemetry නැත", "zero analytics / ads / telemetry SDKs"),
                t("AccessibilityService නැත", "zero AccessibilityService"),
                t("Obfuscated blobs · DexClassLoader නැත", "zero obfuscated blobs / dynamic code loading"),
                t("Cast policy: මෙම දුරකථනයෙන් පමණයි (local-only)", "cast policy: this device only (local-device lock)"),
                t("HTTPS-only යාවත්කාලීන + SHA-256 තහවුරුව", "HTTPS-only updates + SHA-256 verification"),
                t("log වලින් secrets redact වේ", "secrets redacted from every log"),
                "allowBackup=false · " + t("වෙනත් app වලට export නැත", "no cross-app data export"),
            }) {
                TextView l = new TextView(this);
                l.setText(line);
                l.setTextColor(P.text);
                l.setTextSize(12f);
                UiIcons.setCompound(l, R.drawable.ic_check_circle, P.ok, 13, Gravity.START);
                l.setPadding(dp(2), dp(4), dp(2), dp(4));
                c2.addView(l, widen());
            }
            content.addView(c2, widen());
            content.addView(caption("THIRD_PARTY.md · media3 1.11.1 (Apache-2.0) · Noto Sans Sinhala (OFL)"));
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TAB — 🩺 TROUBLESHOOT (v3.2.0: the DEDICATED 1-tap full-feature hub)
    // The v3.1.2 Diagnostics tab, upgraded per Smoking Gun #5:
    //   · ONE master hero button runs the 10-POINT FULL-FEATURE self-test
    //     (FeatureProbe.runAll — every app feature, PASS ✓ / FAIL ✗ + latency +
    //     root cause, live as each probe lands);
    //   · 📋 Copy Full Troubleshoot Report / 📤 Share Report carry the probe
    //     section (Troubleshooter.probeSection) — 100% Privacy-Safe;
    //   · the health summary, the top-first Last-Crash card and the Diag log
    //     viewer stay (they were right already).
    // ═══════════════════════════════════════════════════════════════════════════

    /** The live probe result card (rebuilt by each onProbeDone). */
    private LinearLayout probeCard;

    private View buildTroubleTab() {
        ScrollView scroll = new ScrollView(this);
        scroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
        LinearLayout root = column();
        scroll.addView(root, new ViewGroup.LayoutParams(-1, -2));

        // ── THE MASTER HERO: ONE tap runs EVERYTHING ──
        Button hero = iconPrimaryBtn(R.drawable.ic_nav_troubleshoot, t("සම්පූර්ණ ඇප් පරීක්ෂණය ධාවනය කරන්න (සියලු විශේෂාංග)",
            "Run Full App Troubleshoot (All Features)"));
        hero.setOnClickListener(v -> runFullTroubleshoot());
        root.addView(hero, widen());
        LinearLayout hero2 = row();
        Button shareRep = iconBtn(R.drawable.ic_share, t("Report බෙදාගන්න", "Share Report"));
        shareRep.setOnClickListener(v -> shareTroubleshootReport());
        hero2.addView(shareRep, new LinearLayout.LayoutParams(0, -2, 1f));
        Button copyRep = iconBtn(R.drawable.ic_copy, t("Report copy", "Copy Report"));
        copyRep.setOnClickListener(v -> copyTroubleshootReport());
        hero2.addView(copyRep, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(hero2, widen());
        // ══ v3.4.3 — HELP → "RUN SETUP WIZARD" (Replit QA-0001) ══════════════════
        // The wizard is re-openable at any time from here; it never re-runs by itself
        // once completed (a revoked permission shows the calm Home banner instead).
        Button rerun = iconBtn(R.drawable.ic_check_circle,
            t("සැකසුම් විශාරදය නැවත ධාවනය කරන්න", "Run setup wizard"));
        rerun.setOnClickListener(v -> {
            AppPrefs.resetOnboarding(prefs);
            OnboardingWizard.show(this, prefs);
        });
        root.addView(rerun, widen());
        root.addView(caption(t("10-ජංගම ස්වයං-පරීක්ෂණය: receiver · DLNA · mDNS · යාවත්කාලීන · worker · උපසිරැසි · පරිවර්තක · zip · ෆොන්ට් · නිර්ණාමකය — 100% රහස්‍යතා-ආරක්ෂිතයි.",
            "10-point live self-test: receiver · DLNA · mDNS · updater · worker · subtitles · translator · zip · font · customizer — 100% privacy-safe.")));

        // ── THE LIVE 10-POINT PROBE CARD (one line per feature) ──
        probeCard = card(P.card);
        root.addView(probeCard, widen());
        paintProbeCard();

        // ── the classic health summary (still the at-a-glance state) ──
        root.addView(sectionTitle(t("සෞඛ්‍ය සාරාංශය", "Health summary")));
        LinearLayout health = card(P.card);
        healthLoopback = healthRow(health, t("ලූප්බැක් රිසීවරය (port 8765)", "Loopback receiver (port 8765)"));
        healthDaemon = healthRow(health, t("NSD daemon (NsdManager)", "NSD daemon (NsdManager)"));
        healthResponder = healthRow(health, t("mDNS responder (UDP 5353)", "mDNS responder (UDP 5353)"));
        healthDlna = healthRow(health, t("DLNA renderer (UDP 1900)", "DLNA renderer (UDP 1900)"));
        healthBridge = healthRow(health, t("Worker bridge (KV + PA)", "Worker bridge (KV + PA)"));
        root.addView(health, widen());
        refreshers.add(() -> {
            paintHealth(healthLoopback, ReceiverService.isUp(), "port " + ReceiverService.port());
            paintHealth(healthDaemon, ReceiverService.isAnnounced(),
                ReceiverService.isAnnounced() ? "registered" : t("නිහඬයි — watchdog චක්‍රය සක්‍රියයි", "silent — non-destructive retry cycle armed"));
            MdnsResponder r = ReceiverService.liveResponder();
            paintHealth(healthResponder, r != null && r.isRunning(),
                r == null ? (AppPrefs.mdnsEnabled(prefs) ? "down" : t("Settings වලින් වසා ඇත", "disabled in Settings"))
                    : (r.isRunning() ? "up · ttl 255 · answered " + r.answeredCount() : "down"));
            DlnaRenderer d = ReceiverService.liveDlnaRenderer();
            paintHealth(healthDlna, d != null && d.isRunning(),
                d == null ? (AppPrefs.dlnaEnabled(prefs) ? "down" : t("Settings වලින් වසා ඇත", "disabled in Settings"))
                    : (d.isRunning() ? "up · searches " + d.searchesSeen() + " · replies " + d.repliesSent() : "bind-failed"));
            paintHealth(healthBridge, bridgeConfigured(), bridgeConfigured() ? t("වින්‍යාස කළා", "configured") : t("worker URL නැත", "worker URL missing"));
        });

        // ── the Last-Crash card (top-first) ──
        crashCard = card(0xFF1F0A08);
        root.addView(crashCard, widen());
        refreshers.add(() -> paintCrashCard());

        // ── the 1-tap log buttons ──
        LinearLayout b1 = row();
        Button copy = iconBtn(R.drawable.ic_copy, t("Copy Log", "Copy Log"));
        copy.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("si-diag", Diag.dump()));
            toast(t("copied — කොහෙහරි paste කරන්න", "copied — paste anywhere"));
        });
        b1.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
        Button share = iconBtn(R.drawable.ic_share, t("Share Log", "Share Log"));
        share.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, Diag.dump());
            i.putExtra(Intent.EXTRA_SUBJECT, "Sinhala Player diagnostics v" + AppInfo.VERSION_NAME);
            try { startActivity(Intent.createChooser(i, t("Log බෙදාගන්න", "Share log"))); } catch (Throwable t) { toast("no share target"); }
        });
        b1.addView(share, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(b1, widen());
        LinearLayout b2 = row();
        Button clear = iconDangerBtn(R.drawable.ic_close, t("Clear Log", "Clear Log"));
        clear.setOnClickListener(v -> {
            Diag.reset();
            Diag.i("diag", "log cleared by user");
            toast(t("හිස් කළා", "cleared"));
            refresh();
        });
        b2.addView(clear, new LinearLayout.LayoutParams(0, -2, 1f));
        Button stale = iconDangerBtn(R.drawable.ic_close, t("Crash log හිස් කරන්න", "Clear crash log"));
        stale.setOnClickListener(v -> {
            CrashLog.clear(getFilesDir());
            toast(t("crash log හිස් කළා", "crash log cleared"));
            refresh();
        });
        b2.addView(stale, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(b2, widen());

        selfTestLine = new TextView(this);
        selfTestLine.setTextColor(P.accentDim);
        selfTestLine.setTextSize(12.5f);
        selfTestLine.setPadding(0, dp(8), 0, dp(4));
        selfTestLine.setText(t("10-ජංගම පරීක්ෂණය ඉහත hero බොත්තමෙන් — ප්‍රතිඵල මෙහි පෙනේ.",
            "The 10-point test runs from the hero button above — results land here."));
        root.addView(selfTestLine, widen());

        // v3.1.2 — the log viewer OVERLAP FIX: a real ScrollView wrapping a
        // wrap-content monospace TextView cannot overlap by construction.
        LinearLayout logCard = card(P.card2);
        ScrollView logScroll = new ScrollView(this);
        logScroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(10.5f);
        logView.setTextColor(P.text);
        logView.setTextIsSelectable(true);
        logScroll.addView(logView, new ViewGroup.LayoutParams(-1, -2));
        logCard.addView(logScroll, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(-1, dp(360));
        logLp.topMargin = dp(6);
        root.addView(logCard, logLp);
        refreshers.add(() -> logView.setText(tail(Diag.dump(), 160)));
        root.addView(caption(t("Log: සෑම event එකම — secrets redacted. Copy = සම්පූර්ණ ring එක.",
            "Log: every event, secrets redacted. Copy = the whole ring.")));
        return scroll;
    }

    /** The probe result card: one row per FeatureProbe outcome (✓/✗ + ms + detail). */
    private void paintProbeCard() {
        // v3.3.1 — THE CRASH #2 HARDENING. Three independent guarantees:
        //   1. a local IMMUTABLE snapshot is iterated (never the shared field, and
        //      never a list another thread can append to),
        //   2. a dead Activity's views are never touched,
        //   3. any unexpected state is recorded in the Troubleshooter timeline
        //      instead of taking the process down.
        if (probeCard == null) return;
        if (isFinishing() || isDestroyed()) return;
        try {
            paintProbeCardInner();
        } catch (Throwable t) {
            Diag.w("trouble", "probe card paint: " + t);
            Troubleshooter.record("TROUBLE", "probe card paint failed: " + t.getClass().getSimpleName());
        }
    }

    private void paintProbeCardInner() {
        if (probeCard == null) return;
        probeCard.removeAllViews();
        // the immutable snapshot: safe to iterate on the UI thread at any moment
        final java.util.List<FeatureProbe.Outcome> outcomes = probeOutcomes == null
            ? java.util.Collections.<FeatureProbe.Outcome>emptyList() : probeOutcomes;
        if (outcomes.isEmpty()) {
            TextView idle = new TextView(this);
            idle.setText(t("තව පරීක්ෂණයක් ධාවනය වී නැත — ඉහත hero බොත්තම ඔබන්න.",
                "No full-feature run yet — tap the hero button above."));
            idle.setTextColor(P.desc);
            idle.setTextSize(12f);
            probeCard.addView(idle, widen());
            return;
        }
        int pass = 0;
        for (FeatureProbe.Outcome o : outcomes) {
            if (o.pass) pass++;
            LinearLayout line = row();
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(0, dp(4), 0, dp(4));
            TextView mark = new TextView(this);
            UiIcons.setIconOnly(mark, o.pass ? R.drawable.ic_check_circle : R.drawable.ic_close, o.pass ? P.ok : P.err, 15);
            mark.setTextColor(o.pass ? P.ok : P.err);
            mark.setTextSize(14f);
            mark.setTypeface(Typeface.DEFAULT_BOLD);
            line.addView(mark, new LinearLayout.LayoutParams(dp(24), -2));
            TextView name = new TextView(this);
            name.setText(o.id + ". " + o.name);
            name.setTextColor(P.text);
            name.setTextSize(12.5f);
            line.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView ms = new TextView(this);
            ms.setText(o.ms + " ms");
            ms.setTextColor(P.desc);
            ms.setTextSize(11f);
            line.addView(ms, new LinearLayout.LayoutParams(-2, -2));
            probeCard.addView(line, widen(0, 0));
            TextView detail = new TextView(this);
            detail.setText("    " + (o.detail == null ? "" : o.detail));
            detail.setTextColor(P.desc);
            detail.setTextSize(11f);
            probeCard.addView(detail, widen(0, 0));
        }
        TextView verdict = new TextView(this);
        // v3.4.1 — "ALL FEATURES HEALTHY" requires results.size() == 10 AND pass == 10.
        // A truncated run (probes 9/10 missing) now says exactly how many are missing.
        final boolean healthy = FeatureProbe.allHealthy(outcomes);
        verdict.setText(t("තීරණය: " + FeatureProbe.verdict(outcomes).replace("ALL FEATURES HEALTHY", "සියලු විශේෂාංග සෞඛ්‍යසම්පූර්ණයි"),
            "Verdict: " + FeatureProbe.verdict(outcomes)));
        verdict.setTextColor(healthy ? P.ok : P.warn);
        verdict.setTextSize(12.5f);
        verdict.setTypeface(Typeface.DEFAULT_BOLD);
        verdict.setPadding(0, dp(6), 0, 0);
        probeCard.addView(verdict, widen());
    }

    /**
     * v3.2.0 — THE ONE-TAP FULL-FEATURE TROUBLESHOOT (Smoking Gun #5):
     * FeatureProbe.runAll on a background thread, every probe start/done lands
     * live in the probe card, every outcome is recorded in the Troubleshooter
     * timeline (privacy-scrubbed) and the final report carries the whole
     * section. One guard: never two runs at once. */
    private final java.util.concurrent.atomic.AtomicBoolean probeRunning =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    private void runFullTroubleshoot() {
        if (!probeRunning.compareAndSet(false, true)) {
            toast(t("පරීක්ෂණය දැනටමත් ක්‍රියාත්මකයි", "a troubleshoot run is already active"));
            return;
        }
        probeOutcomes = java.util.Collections.emptyList();   // v3.3.1 — immutable, never a live list
        selfTestLine.setText(t("සම්පූර්ණ පරීක්ෂණය ධාවනය වෙමින්… · running all 10 feature probes…",
            "running all 10 feature probes…"));
        Diag.i("trouble", "full-feature troubleshoot start (10 probes)");
        new Thread(() -> {
            try {
                MdnsResponder rsp = ReceiverService.liveResponder();
                java.util.List<FeatureProbe.Outcome> outcomes = FeatureProbe.runAll(
                    ReceiverService.port(),
                    AppPrefs.workerUrl(prefs),
                    getFilesDir(),
                    rsp != null && rsp.isRunning(),
                    new FeatureProbe.Listener() {
                        @Override public void onProbeStart(int id, String name) {
                            main.post(() -> selfTestLine.setText("… " + id + "/10 " + name));
                        }
                        @Override public void onProbeDone(FeatureProbe.Outcome o) {
                            Troubleshooter.record("APP", "probe " + o.id + " " + o.name
                                + " → " + (o.pass ? "PASS" : "FAIL") + " (" + o.ms + " ms)");
                        }
                        // v3.3.1 — the engine hands over an IMMUTABLE snapshot; the
                        // UI only ever swaps a reference, it never mutates a list.
                        @Override public void onProbeSnapshot(java.util.List<FeatureProbe.Outcome> snap) {
                            probeOutcomes = snap;
                            main.post(MainActivity.this::paintProbeCard);
                        }
                    });
                probeOutcomes = outcomes;      // already an immutable snapshot
                int pass = 0, total = 0;
                for (FeatureProbe.Outcome o : outcomes) { total++; if (o.pass) pass++; }
                final int totalCount = total;
                final boolean all = total > 0 && pass == total;
                final int passCount = pass;
                Diag.i("trouble", "full-feature troubleshoot: " + pass + "/10 PASS");
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;      // v3.3.1 — never touch a dead UI
                    selfTestLine.setText(passCount + "/" + totalCount + " "
                        + t("විශේෂාංග PASS — report එක copy කරන්න", "features PASS — copy the report"));
                    selfTestLine.setTextColor(all ? P.ok : P.warn);
                    UiIcons.setCompound(selfTestLine, all ? R.drawable.ic_check_circle : R.drawable.ic_alert,
                        all ? P.ok : P.warn, 14, Gravity.START);
                    paintProbeCard();
                    refresh();
                });
            } finally {
                probeRunning.set(false);
            }
        }, "si-probe").start();
    }

    // ── v3.1.2 — the 1-tap privacy-safe troubleshoot report ────────────────────

    /** Collect the live Facts (booleans + counters only, zero PII). */
    private Troubleshooter.Facts collectFacts() {
        Troubleshooter.Facts f = new Troubleshooter.Facts();
        f.receiverUp = ReceiverService.isUp();
        f.receiverPort = ReceiverService.port();
        f.nsdAnnounced = ReceiverService.isAnnounced();
        MdnsResponder r = ReceiverService.liveResponder();
        f.mdnsResponderUp = r != null && r.isRunning();
        DlnaRenderer d = ReceiverService.liveDlnaRenderer();
        f.dlnaUp = d != null && d.isRunning();
        f.dlnaEnabled = AppPrefs.dlnaEnabled(prefs);
        f.mdnsEnabled = AppPrefs.mdnsEnabled(prefs);
        f.workerConfigured = bridgeConfigured();
        f.workerHealthy = workerHealthyCache;
        f.workerVersion = workerVersionCache;
        f.workerStorage = workerStorageCache;
        f.notifGranted = Miui.notifGranted(this);
        f.overlayGranted = android.provider.Settings.canDrawOverlays(this);
        PlayActivity p = PlayActivity.current();
        f.playerOpen = p != null;
        if (p != null) { f.playerState = p.playerStateForDiag(); f.playerError = p.playerErrorForDiag(); }
        f.subsLoaded = p != null && p.subsStateForDiag() != null && !"idle".equals(p.subsStateForDiag());
        f.subsState = p == null ? "idle" : p.subsStateForDiag();
        f.subsDetail = p == null ? "" : p.subsDetailForDiag();
        f.cueCount = p == null ? 0 : p.cueCountForDiag();
        f.updaterRan = updaterRanFlag;
        f.updaterResult = updaterResultCache;
        String crash = CrashLog.read(getFilesDir());
        f.hasCrashLog = crash != null;
        if (crash != null) {
            java.util.List<String> top = Troubleshooter.crashTop(crash, 1);
            f.crashTop = top.isEmpty() ? "(captured)" : top.get(0);
        }
        return f;
    }

    /** The complete 1-tap report (Status Summary + Issues + Probes + Crash + Timeline). */
    private String troubleshootReport() {
        Troubleshooter.Facts f = collectFacts();
        // v3.4.2 — the probe section reads FeatureProbe's static snapshot whenever this
        // instance has none (tab switch / recreate), so "(not run yet)" can no longer
        // appear after a completed 10/10 run.
        java.util.List<FeatureProbe.Outcome> probes = probeOutcomes.isEmpty()
            ? FeatureProbe.lastResults() : probeOutcomes;
        return Troubleshooter.buildReport(f, Diag.dump(), CrashLog.read(getFilesDir()))
            + "\n" + Troubleshooter.probeSection(probes);
    }

    private void copyTroubleshootReport() {
        try {
            String rep = troubleshootReport();
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("si-troubleshoot", rep));
            toast(t("report copied — ", "report copied (privacy-safe, ") + rep.length() + t(" අකුරු", " chars)"));
            Diag.i("diag", "troubleshoot report copied (" + rep.length() + " chars, privacy-scrubbed)");
        } catch (Throwable t) {
            Diag.w("diag", "report copy: " + t);
            toast(String.valueOf(t.getMessage()));
        }
    }

    private void shareTroubleshootReport() {
        try {
            String rep = troubleshootReport();
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, rep);
            i.putExtra(Intent.EXTRA_SUBJECT, "Sinhala Player troubleshoot report v" + AppInfo.VERSION_NAME);
            startActivity(Intent.createChooser(i, t("Report බෙදාගන්න (privacy-safe)", "Share report (privacy-safe)")));
        } catch (Throwable t) {
            toast("no share target");
        }
    }

    /** The ⚠ Last Crash Captured card: v3.1.2 — TOP-FIRST. The exception class +
     *  message + the first app frames lead (immediately readable, no scrolling),
     *  with the full trace on 1-tap Copy/Share. Hidden while no crash exists. */
    private void paintCrashCard() {
        String report = CrashLog.read(getFilesDir());
        crashCard.setVisibility(report == null ? View.GONE : View.VISIBLE);
        if (report == null) return;
        crashCard.removeAllViews();
        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(t("අවසාන Crash එක සටහන් වී ඇත", "Last Crash Captured"));
        UiIcons.setCompound(t, R.drawable.ic_alert, P.warn, 15, Gravity.START);
        t.setTextColor(P.err);
        t.setTextSize(13.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        crashCard.addView(head, widen());
        TextView body = new TextView(this);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextSize(10f);
        body.setTextColor(0xFFD8B4B4);
        // v3.1.2 — THE TOP of the trace, not the bottom: exception + message +
        // the first app frames (Troubleshooter.crashTop reorders so the class
        // line leads, never Handler.dispatchMessage).
        java.util.List<String> top = Troubleshooter.crashTop(report, 12);
        StringBuilder sb = new StringBuilder();
        for (String l : top) sb.append(l).append('\n');
        if (top.size() < 2) sb.setLength(0);          // degenerate file → fall back
        body.setText(sb.length() > 0 ? sb.toString() : tail(report, 12));
        crashCard.addView(body, widen());
        LinearLayout acts = row();
        Button cp = iconBtn(R.drawable.ic_copy, t("Copy (full)", "Copy (full)"));
        cp.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("si-crash", CrashLog.scrub(report)));
            toast(t("full crash trace copied", "full crash trace copied"));
        });
        acts.addView(cp, new LinearLayout.LayoutParams(0, -2, 1f));
        Button sh = iconBtn(R.drawable.ic_share, t("Share", "Share"));
        sh.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, CrashLog.scrub(report));
            i.putExtra(Intent.EXTRA_SUBJECT, "Sinhala Player crash v" + AppInfo.VERSION_NAME);
            try { startActivity(Intent.createChooser(i, t("Share crash trace", "Share crash trace"))); } catch (Throwable x) { toast("no share target"); }
        });
        acts.addView(sh, new LinearLayout.LayoutParams(0, -2, 1f));
        Button cl = iconDangerBtn(R.drawable.ic_close, t("Clear", "Clear"));
        cl.setOnClickListener(v -> {
            CrashLog.clear(getFilesDir());
            toast(t("හිස් කළා", "cleared"));
            refresh();
        });
        acts.addView(cl, new LinearLayout.LayoutParams(0, -2, 1f));
        crashCard.addView(acts, widen());
    }

    private TextView healthRow(LinearLayout box, String label) {
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(7), 0, dp(7));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(P.text);
        t.setTextSize(13f);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = new TextView(this);
        v.setText("…");
        v.setTextColor(P.desc);
        v.setTextSize(12f);
        v.setGravity(Gravity.END);
        r.addView(v, new LinearLayout.LayoutParams(-2, -2));
        box.addView(r, widen());
        return v;
    }

    private void paintHealth(TextView v, boolean ok, String detail) {
        v.setText(detail);
        UiIcons.setCompound(v, ok ? R.drawable.ic_check_circle : R.drawable.ic_close,
            ok ? P.ok : P.err, 13, Gravity.START);
        v.setTextColor(ok ? P.ok : P.warn);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // v3.1.2 — THE DEEP TROUBLESHOOT (6 real active stages, privacy-safe)
    // ═══════════════════════════════════════════════════════════════════════════





    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
        return o.toByteArray();
    }

    // ── the in-app GitHub update check (renders UpdateChecker.Result) ──────────

    private void checkForUpdates() {
        toast("…");
        new Thread(() -> {
            UpdateChecker.Result r = UpdateChecker.check();
            main.post(() -> {
                if (r.offline) {
                    android.app.AlertDialog.Builder d = new android.app.AlertDialog.Builder(this)
                        .setTitle(t("යාවත්කාලීන පරීක්ෂාව", "Update check"))
                        .setMessage((r.error == null || r.error.isEmpty() ? "unknown" : r.error)
                            + "\n\n" + t("නැවත උත්සාහ කරන්න", "please retry"))
                        .setPositiveButton(android.R.string.ok, null);
                    try { d.show(); } catch (Throwable ignored) { }
                    return;
                }
                if (!r.newer) {
                    toast(t("අලුත්ම — v" + AppInfo.VERSION_NAME, "v" + AppInfo.VERSION_NAME + " is the latest release"));
                    updateBanner.setVisibility(View.GONE);
        UiIcons.setCompound(updateBanner, R.drawable.ic_update, P.accentDim, 14, Gravity.START);
                    return;
                }
                String msg = "v" + r.latest + " " + t("ඇතුළත් · a new release is available", "— a new release is available")
                    + "\n\n" + (r.notes.isEmpty() ? "" : r.notes + "\n\n")
                    + "Download: AnantPlay-Sinhala-Player-v" + r.latest + ".apk\n"
                    + t("නිහඬ ස්වයංක්‍රීය යාවත්කාලීනය දැනටමත් ON නම් එය බාගෙන නිහඬව සූදානම් වේ.",
                        "With silent auto-update ON it downloads quietly and posts one quiet ready note.");
                android.app.AlertDialog.Builder d = new android.app.AlertDialog.Builder(this)
                    .setTitle(t("යාවත්කාලීනයක් ඇත", "Update available"))
                    .setMessage(msg)
                    .setPositiveButton(t("දැන් install කරන්න", "Install now"), (dd, w) -> {
                        // a manual check deliberately bypasses the cooldown
                        new Thread(() -> SelfUpdater.checkAndMaybeInstall(MainActivity.this, true), "si-upd-manual").start();
                    })
                    .setNeutralButton(t("Release එක විවෘත කරන්න", "Open release"), (dd, w) -> {
                        try {
                            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(r.releaseUrl.isEmpty() ? UpdateChecker.REPO_URL + "/releases/latest" : r.releaseUrl)));
                        } catch (Throwable t) {
                            toast("browser එකක් නැත — " + UpdateChecker.REPO_URL);
                        }
                    })
                    .setNegativeButton(t("මෙම සංස්කරණය මඟහරින්න", "Skip this version"), (dd, w) -> {
                        getSharedPreferences("selfupdate", MODE_PRIVATE).edit()
                            .putString("skippedVersion", r.latest).apply();
                        SelfUpdater.cancelBanners(this);
                        toast(t("v" + r.latest + " මඟහැරියා", "skipped v" + r.latest));
                        updateBanner.setVisibility(View.GONE);
        UiIcons.setCompound(updateBanner, R.drawable.ic_update, P.accentDim, 14, Gravity.START);
                    })
                    .setOnCancelListener(dd -> { });
                try { d.show(); } catch (Throwable ignored) { }
                showUpdateBanner("v" + r.latest + " — " + t("නිහඬව බාගැනෙයි", "downloading quietly"), -2);
            });
        }, "si-update").start();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // small view helpers (the shared themed vocabulary)
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean compact() { return AppPrefs.compact(prefs); }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int vPad = compact() ? dp(9) : dp(12);
        l.setPadding(dp(16), vPad, dp(16), dp(16));
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout.LayoutParams widen() { return widen(dp(8), 0); }

    private LinearLayout.LayoutParams widen(int topMargin, int bottomMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = topMargin;
        lp.bottomMargin = bottomMargin;
        return lp;
    }

    private LinearLayout card(int color) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int vPad = compact() ? dp(9) : dp(12);
        l.setPadding(dp(14), vPad, dp(14), vPad);
        // v3.3.1 — the user's Card-corner-radius choice (6 / 16 / 28 dp), never a
        // hard-coded 16: the Customize row used to change nothing on screen.
        GradientDrawable g = rounded(color, dp(P.radiusCard));
        g.setStroke(dp(1), 0x1FFFFFFF);
        l.setBackground(g);
        return l;
    }

    private TextView sectionTitle(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(P.title);
        v.setTextSize(15.5f);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setPadding(dp(2), dp(14), 0, dp(2));
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return v;
    }

    private TextView caption(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(P.desc);
        v.setTextSize(11.5f);
        v.setLineSpacing(dp(2), 1f);
        v.setPadding(dp(2), dp(4), 0, dp(4));
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return v;
    }

    private TextView settingLabel(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(P.desc);
        v.setTextSize(11.5f);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(dp(2), dp(8), 0, dp(2));
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return v;
    }

    /** A setting row: bold label + a right-aligned state; returns the VALUE view. */
    private TextView settingRow(LinearLayout cardBox, String label, String value) {
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(9), 0, dp(9));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(P.text);
        t.setTextSize(13f);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(P.desc);
        v.setTextSize(12f);
        v.setGravity(Gravity.END);
        r.addView(v, new LinearLayout.LayoutParams(-2, -2));
        cardBox.addView(r, widen());
        return v;   // the VALUE view — the caller attaches the click + refreshes its text
    }

    /** v3.1.0 — a boolean setting row with ON/OFF value that flips itself. */
    private TextView toggleRow(LinearLayout cardBox, String label, String key, boolean def) {
        return toggleRow(cardBox, label, key, def, null);
    }

    /** v3.4.0 - a settings row whose label carries a leading vector (no emoji prefix). */
    private TextView settingRow(LinearLayout cardBox, int iconRes, String label, String value) {
        TextView v = settingRow(cardBox, label, value);
        labelIcon(v, iconRes);
        return v;
    }

    /** v3.4.0 - a toggle row whose label carries a leading vector. */
    private TextView toggleRow(LinearLayout cardBox, int iconRes, String label, String key, boolean def) {
        TextView v = toggleRow(cardBox, label, key, def);
        labelIcon(v, iconRes);
        return v;
    }

    private TextView toggleRow(LinearLayout cardBox, int iconRes, String label, String key, boolean def, Runnable after) {
        TextView v = toggleRow(cardBox, label, key, def, after);
        labelIcon(v, iconRes);
        return v;
    }

    /** Puts the tinted vector on the row's LABEL view (child 0), keeping the label text. */
    private void labelIcon(TextView valueView, int iconRes) {
        if (valueView == null) return;
        android.view.ViewParent parent = valueView.getParent();
        if (!(parent instanceof LinearLayout)) return;
        LinearLayout r = (LinearLayout) parent;
        if (r.getChildCount() == 0) return;
        View first = r.getChildAt(0);
        if (first instanceof TextView) {
            UiIcons.setCompound((TextView) first, iconRes, P.accent, 15, Gravity.START);
        }
    }

    /** v3.4.0 - a secondary button with a leading vector (drop-in for iconBtn(R.drawable.ic_copy, x)). */
    private Button iconBtn(int iconRes, String text) {
        Button b = smallBtn(text);
        UiIcons.setCompound(b, iconRes, P.text, 15, Gravity.START);
        return b;
    }

    /** v3.4.0 - a primary button with a leading vector. */
    private Button iconPrimaryBtn(int iconRes, String text) {
        Button b = btnPrimary(text);
        UiIcons.setCompound(b, iconRes, 0xFF0A0A14, 16, Gravity.START);
        return b;
    }

    /** v3.4.0 - a destructive small button (clear / delete) with a leading vector. */
    private Button iconDangerBtn(int iconRes, String text) {
        Button b = smallBtn(text);
        UiIcons.setCompound(b, iconRes, P.err, 15, Gravity.START);
        return b;
    }

    /** v3.4.0 - a button with a leading vector instead of an emoji prefix. */
    private Button iconBtn(int iconRes, String text, boolean primary, int tint) {
        Button b = primary ? btnPrimary(text) : smallBtn(text);
        UiIcons.setCompound(b, iconRes, tint, primary ? 16 : 15, Gravity.START);
        return b;
    }

    /** v3.4.0 - a button that IS an icon (destructive actions, clear buttons). */
    private Button iconOnlyBtn(int iconRes, int tint) {
        Button b = smallBtn("");
        UiIcons.setIconOnly(b, iconRes, tint, 16);
        return b;
    }

    /** v3.4.0 - the live status dot as a compound drawable (replaces 🟢/🟡/⚪). */
    private void statusDot(TextView tv, int color) {
        UiIcons.setCompound(tv, R.drawable.ic_dot, color, 9, Gravity.START);
    }

    private TextView toggleRow(LinearLayout cardBox, String label, String key, boolean def, Runnable after) {
        boolean on = prefs.getBoolean(key, def);
        final TextView v = settingRow(cardBox, label, on ? "ON" : "OFF");
        v.setTextColor(on ? P.ok : P.desc);
        // the whole row flips the toggle (settingRow returned the VALUE view;
        // its parent is the row just added to cardBox)
        View row = (View) v.getParent();
        row.setOnClickListener(x -> {
            boolean now = !prefs.getBoolean(key, def);
            prefs.edit().putBoolean(key, now).apply();
            v.setText(now ? "ON" : "OFF");
            v.setTextColor(now ? P.ok : P.desc);
            if (after != null) after.run();
        });
        return v;
    }

    /** v3.1.0 — a labeled slider row (the Styling Studio's live controls). */
    private View sliderRow(String label, int min, int max, int value, String unit,
                           java.util.function.IntConsumer onChange) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(6), 0, dp(2));
        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(P.text);
        l.setTextSize(12.5f);
        head.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView val = new TextView(this);
        val.setText(value + unit);
        val.setTextColor(P.accent);
        val.setTextSize(12.5f);
        val.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(val, new LinearLayout.LayoutParams(-2, -2));
        box.addView(head, new LinearLayout.LayoutParams(-1, -2));
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(value - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                int v = p + min;
                val.setText(v + unit);
                if (user) onChange.accept(v);   // the LIVE preview re-renders as it drags
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        box.addView(sb, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    private Button btnPrimary(String t) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(t);
        b.setTextSize(13.5f);
        b.setTextColor(0xFFFFFFFF);
        b.setBackground(rounded(P.accent, dp(12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private Button smallBtn(String t) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(t);
        b.setTextSize(12.5f);
        b.setTextColor(P.text);
        b.setBackground(rounded(P.chip, dp(12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    /** One 2×2 grid action button: big glyph + bold label. */
    private View actionBtn(int iconRes, String title, View.OnClickListener click) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setPadding(dp(8), compact() ? dp(11) : dp(14), dp(8), compact() ? dp(10) : dp(12));
        GradientDrawable g = rounded(P.card, dp(16));
        g.setStroke(dp(1), 0x1FFFFFFF);
        l.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        l.setLayoutParams(lp);
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(UiIcons.tinted(this, iconRes, P.accent, 22));
        ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
        l.addView(ic, new LinearLayout.LayoutParams(-1, dp(26)));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(P.title);
        t.setTextSize(13f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        l.addView(t, new LinearLayout.LayoutParams(-1, -2));
        l.setOnClickListener(click);
        return l;
    }

    private TextView statusChip(String initial) {
        TextView t = new TextView(this);
        t.setText(initial);
        t.setTextColor(P.desc);
        t.setTextSize(11.5f);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rounded(P.chip, dp(14)));
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private void paintChip(TextView chip, boolean ok, String label, String detail) {
        chip.setText(label + " · " + detail);
        chip.setTextColor(ok ? P.ok : P.warn);
        UiIcons.setCompound(chip, ok ? R.drawable.ic_check_circle : R.drawable.ic_alert,
            ok ? P.ok : P.warn, 12, Gravity.START);
        chip.requestLayout();
    }

    /** One numbered guide step: a circle badge + one bold line + one desc line. */
    private View guideStep(String n, String title, String desc) {
        LinearLayout l = row();
        l.setGravity(Gravity.TOP);
        l.setPadding(0, dp(8), 0, 0);
        TextView badge = new TextView(this);
        badge.setText(n);
        badge.setTextColor(0xFFFFFFFF);
        badge.setTextSize(13f);
        badge.setGravity(Gravity.CENTER);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = rounded(P.accent, dp(99));
        badge.setBackground(bg);
        l.addView(badge, new LinearLayout.LayoutParams(dp(26), dp(26)));
        LinearLayout text = column2();
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(P.text);
        t.setTextSize(12.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(t, new LinearLayout.LayoutParams(-1, -2));
        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextColor(P.desc);
        d.setTextSize(11f);
        d.setPadding(0, dp(1), 0, 0);
        text.addView(d, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.leftMargin = dp(10);
        l.addView(text, tlp);
        return l;
    }

    private LinearLayout column2() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private boolean canInstallApks() {
        try { return getPackageManager().canRequestPackageInstalls(); }
        catch (Throwable t) { return false; }
    }

    private void openInstallPermission() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            Miui.openAppDetails(this);
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private static String tail(String s, int lines) {
        String[] l = s.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, l.length - lines); i < l.length; i++) sb.append(l[i]).append('\n');
        return sb.toString();
    }

    /** Runs NSD discovery from THIS phone — the LEGACY self-test layer (v2). On
     *  HyperOS this can legitimately NOT see our own registration (the OEM daemon
     *  filters same-uid services), so it is a bonus signal only; the raw probe is
     *  the authoritative proof. */
    static final class NsdAnnouncerTest {
        static volatile Boolean result;                 // last self-test outcome (probe OR legacy)
        private static volatile Boolean lastKnown;      // announced + discovered at least once

        static boolean lastKnown() {
            Boolean r = result;
            return r != null && r;
        }

        static boolean discoverSelf(Context c, long timeoutMs) {
            try {
                final Object done = new Object();
                final boolean[] found = {false};
                android.net.nsd.NsdManager nsd = c.getSystemService(android.net.nsd.NsdManager.class);
                if (nsd == null) return false;
                android.net.nsd.NsdManager.DiscoveryListener l = new android.net.nsd.NsdManager.DiscoveryListener() {
                    @Override public void onServiceFound(android.net.nsd.NsdServiceInfo i) {
                        String n = i.getServiceName() == null ? "" : i.getServiceName();
                        Diag.i("nsdtest", "found: " + n);
                        if (n.contains(NsdAnnouncer.NAME) || n.contains(NsdAnnouncer.RESPONDER_NAME)) found[0] = true;
                    }
                    @Override public void onServiceLost(android.net.nsd.NsdServiceInfo i) { }
                    @Override public void onDiscoveryStarted(String t) { }
                    @Override public void onDiscoveryStopped(String t) { }
                    @Override public void onStartDiscoveryFailed(String t, int e) { Diag.w("nsdtest", "start fail " + e); }
                    @Override public void onStopDiscoveryFailed(String t, int e) { }
                };
                nsd.discoverServices(NsdAnnouncer.TYPE, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, l);
                long end = System.currentTimeMillis() + timeoutMs;
                while (System.currentTimeMillis() < end && !found[0]) Thread.sleep(150);
                try { nsd.stopServiceDiscovery(l); } catch (Throwable ignored) { }
                if (found[0]) lastKnown = true;
                return found[0];
            } catch (Throwable t) {
                Diag.w("nsdtest", "discover: " + t);
                return false;
            }
        }
    }
}


