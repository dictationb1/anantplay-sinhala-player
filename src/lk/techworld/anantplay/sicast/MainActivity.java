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
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
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
    private static final int TAB_HOME = 0, TAB_WEB = 1, TAB_SETTINGS = 2, TAB_DIAG = 3;
    private FrameLayout pages;
    private final List<TextView> tabIcons = new ArrayList<>();
    private final List<TextView> tabLabels = new ArrayList<>();
    private View homePage, settingsPage, diagPage, webPage;
    private LinearLayout bottomBar;
    /** v3.1.0 — the settings drill-down overlay (one sub-page at a time). */
    private View subPage;

    // home widgets
    private TextView heroPill, updateBanner;
    private android.widget.ProgressBar updateBar;
    private LinearLayout setupCard, guideCard, guideBody;
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

    // the web tab (created lazily — the first switch to it builds it)
    private WebBrowserView browser;

    /** READ_MEDIA_VIDEO on 33+, READ_EXTERNAL_STORAGE below — only used for file:// casts. */
    private static final String MEDIA_PERM = Build.VERSION.SDK_INT >= 33
        ? "android.permission.READ_MEDIA_VIDEO" : "android.permission.READ_EXTERNAL_STORAGE";

    // ───────────────────────────────────────────────────────────────────────────
    // lifecycle + theme
    // ───────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("sicast", MODE_PRIVATE);
        applyTheme();
        setContentView(buildUi());
        ReceiverService.arm(this, "main");
        // Android 13+ runtime notification permission (sideloaded apps need the
        // "Allow restricted settings" unlock first — the setup checklist walks it).
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
            != PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7); } catch (Throwable ignored) { }
        }
        if (getIntent().getBooleanExtra("needMedia", false)) requestMediaPermission();
        if (getIntent().getBooleanExtra("runTest", false)) { selectTab(TAB_DIAG); runSelfTest(); }
        // the live update banner: SelfUpdater's UI listener drives it (silent +
        // throttled since v3.1.0 — the banner is the ONLY visible update noise)
        SelfUpdater.setUiListener(new SelfUpdater.UiListener() {
            @Override public void onUpdateAvailable(String version) {
                main.post(() -> showUpdateBanner("🆕 v" + version + " — නිහඬව බාගැනෙමින්… · downloading quietly…", 0));
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
     *  when dynamic color is on AND Android 12+) and paint the window. */
    private void applyTheme() {
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
        P = ThemeEngine.palette(mode, dynamicSeed, AppPrefs.accent(prefs));
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
            toast("වීඩියෝ අවසරය ලැබුණි ✓ — AnantPlay එකෙන් නැවත Cast කරන්න");
        }
        refresh();
    }

    private boolean mediaGranted() {
        try { return checkSelfPermission(MEDIA_PERM) == PackageManager.PERMISSION_GRANTED; }
        catch (Throwable t) { return false; }
    }

    private void requestMediaPermission() {
        if (mediaGranted()) { toast("video access ✓"); return; }
        try { requestPermissions(new String[]{MEDIA_PERM}, 8); }
        catch (Throwable t) { Diag.w("perm", "media request: " + t); Miui.openAppDetails(this); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        main.postDelayed(this::refresh, 1200);   // let the receiver/nsd state settle
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

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(P.bg);

        pages = new FrameLayout(this);
        root.addView(pages, new FrameLayout.LayoutParams(-1, -1));

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setBackgroundColor(P.card2);
        // v3.1.0 — Custom Home/Tab Visibility: users who only cast get a clean
        // 3-tab app (the WebView tab is toggled in Appearance settings).
        boolean web = AppPrefs.showWebTab(prefs);
        String[][] tabs = web ? new String[][]{
            {"🏠", t("මුල", "Home")},
            {"🌐", t("බ්‍රව්සර්", "WebView")},
            {"⚙️", t("සැකසුම්", "Settings")},
            {"🩺", t("දෝෂ නිර්ණය", "Diagnostics")},
        } : new String[][]{
            {"🏠", t("මුල", "Home")},
            {"⚙️", t("සැකසුම්", "Settings")},
            {"🩺", t("දෝෂ නිර්ණය", "Diagnostics")},
        };
        int[] tabIds = tabIdsInOrder();
        tabIcons.clear();
        tabLabels.clear();
        for (int i = 0; i < tabIds.length; i++) {
            final int idx = tabIds[i];
            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(0, dp(7), 0, dp(7));
            TextView icon = new TextView(this);
            icon.setText(tabs[i][0]);
            icon.setTextSize(19f);
            icon.setGravity(Gravity.CENTER);
            TextView label = new TextView(this);
            label.setText(tabs[i][1]);
            label.setTextSize(9.5f);
            label.setGravity(Gravity.CENTER);
            tab.addView(icon, new LinearLayout.LayoutParams(-1, -2));
            tab.addView(label, new LinearLayout.LayoutParams(-1, -2));
            tab.setOnClickListener(v -> selectTab(idx));
            tabIcons.add(icon);
            tabLabels.add(label);
            bottomBar.addView(tab, new LinearLayout.LayoutParams(0, -2, 1f));
        }

        homePage = buildHomeTab();
        settingsPage = buildSettingsTab();
        diagPage = buildDiagTab();
        pages.addView(homePage, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(settingsPage, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(diagPage, new FrameLayout.LayoutParams(-1, -1));

        // Android 16 edge-to-edge (targetSdk 35+ enforces it): pad the content
        // by the REAL insets — status bar above, gesture bar below, never a
        // clipped title or a half-swallowed button on HyperOS.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            int left = insets.getSystemWindowInsetLeft();
            int right = insets.getSystemWindowInsetRight();
            pages.setPadding(left, top, right, bottom + dp(58));
            bottomBar.setPadding(left, 0, right, bottom);
            return insets;
        });

        selectTab(TAB_HOME);
        return root;
    }

    private int[] tabIdsInOrder() {
        return AppPrefs.showWebTab(prefs)
            ? new int[]{TAB_HOME, TAB_WEB, TAB_SETTINGS, TAB_DIAG}
            : new int[]{TAB_HOME, TAB_SETTINGS, TAB_DIAG};
    }

    private void selectTab(int idx) {
        currentTab = idx;
        homePage.setVisibility(idx == TAB_HOME ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility(idx == TAB_SETTINGS ? View.VISIBLE : View.GONE);
        diagPage.setVisibility(idx == TAB_DIAG ? View.VISIBLE : View.GONE);
        if (idx == TAB_WEB && browser == null) {
            browser = new WebBrowserView(this);
            webPage = browser;
            pages.addView(webPage, new FrameLayout.LayoutParams(-1, -1));
        }
        if (webPage != null) webPage.setVisibility(idx == TAB_WEB ? View.VISIBLE : View.GONE);
        int[] ids = tabIdsInOrder();
        for (int i = 0; i < tabIcons.size(); i++) {
            boolean on = ids[i] == idx;
            tabIcons.get(i).setTextColor(on ? P.accent : P.desc);
            tabLabels.get(i).setTextColor(on ? P.accentDim : P.desc);
            tabIcons.get(i).setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT, Typeface.NORMAL);
        }
        if (idx == TAB_DIAG) refresh();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TAB 1 — HOME (zero-clip hero · quick actions · guide · setup checklist)
    // ═══════════════════════════════════════════════════════════════════════════

    private TextView chipReceiver, chipCast, chipSubs;

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
        updateBanner.setOnClickListener(v -> checkForUpdates());
        root.addView(updateBanner, widen());
        updateBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        updateBar.setMax(100);
        updateBar.setVisibility(View.GONE);
        updateBar.getProgressDrawable().setColorFilter(P.accent, android.graphics.PorterDuff.Mode.SRC_IN);
        root.addView(updateBar, widen());

        // ── HERO: the receiver status card ──
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
        Button toggle = btnPrimary("⏻");
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
                ? (announced || castable ? "🟢 " + t("සිංහල Player සූදානම්", "සිංහල Player ready")
                                         : "🟡 " + t("සූදානම් — announce වෙමින්…", "arming announcement…"))
                : "⚪ " + t("ක්‍රියාත්මක නැත · Off", "Off"));
            heroPill.setTextColor(up ? P.ok : P.desc);
            GradientDrawable hp = rounded(up ? P.accentSoft : P.chip, dp(20));
            heroPill.setBackground(hp);
            toggle.setText(up ? "⏹ " + t("වසන්න · Stop", "Stop receiver")
                              : "▶ " + t("අරඹන්න · Start", "Start receiver"));
            paintChip(chipReceiver, up, t("රිසීවරය", "Receiver"), up ? "port " + ReceiverService.port() : "off");
            paintChip(chipCast, castable, t("Cast/DLNA", "Cast/DLNA"),
                castable ? (announced ? "Nsd+Rsp+DLNA" : "Rsp+DLNA") : t("සක්‍රිය වෙමින්", "arming"));
            paintChip(chipSubs, bridgeConfigured(), t("උපසිරැසි එන්ජිනය", "Subs Engine"),
                bridgeConfigured() ? t("ලෑස්තියි", "ready") : t("worker බලන්න", "check worker"));
        });

        // ── the 2×2 quick-action grid ──
        root.addView(sectionTitle(t("ඉක්මන් ක්‍රියා", "Quick actions")));
        LinearLayout g1 = row();
        g1.addView(actionBtn("▶", t("AnantPlay විවෘත කරන්න", "Open AnantPlay"), v -> {
            Miui.openAnantPlay(this);
            // v3.1.0 — the ONE smart non-intrusive cast confirmation (zero nagging)
            toast("🟢 සිංහල Player Cast සූදානම් — AnantPlay හි Cast බොත්තමෙන් තෝරන්න");
        }), new LinearLayout.LayoutParams(0, -2, 1f));
        if (AppPrefs.showWebTab(prefs)) {
            g1.addView(actionBtn("🌐", t("බ්‍රව්සරයෙන් බලන්න", "Watch in WebView"), v -> selectTab(TAB_WEB)),
                new LinearLayout.LayoutParams(0, -2, 1f));
        } else {
            g1.addView(actionBtn("🔗", t("URL එකක් බලන්න", "Play Video URL"), v -> playUrlDialog()),
                new LinearLayout.LayoutParams(0, -2, 1f));
        }
        root.addView(g1, widen());
        LinearLayout g2 = row();
        if (AppPrefs.showWebTab(prefs)) {
            g2.addView(actionBtn("🔗", t("URL එකක් බලන්න", "Play Video URL"), v -> playUrlDialog()),
                new LinearLayout.LayoutParams(0, -2, 1f));
        } else {
            g2.addView(actionBtn("⚙️", t("සැකසුම්", "Settings"), v -> selectTab(TAB_SETTINGS)),
                new LinearLayout.LayoutParams(0, -2, 1f));
        }
        g2.addView(actionBtn("🎬", t("පරීක්ෂණ වීඩියෝව", "Test Player"), v -> {
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
        guideTitle.setText("❔ " + t("භාවිත කරන ආකාරය", "How to use"));
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

        // ── the setup checklist (hidden once everything is green) ──
        setupCard = card(0xFF1F1508);
        root.addView(setupCard, widen());
        refreshers.add(() -> paintSetupChecklist());

        TextView foot = new TextView(this);
        foot.setText(String.format(Locale.US,
            "AnantPlay සිංහල Player v%s · zero fork · zero accessibility · official AnantPlay stays untouched",
            AppInfo.VERSION_NAME));
        foot.setTextColor(P.desc);
        foot.setTextSize(10.5f);
        foot.setPadding(0, dp(10), 0, dp(6));
        root.addView(foot, widen());
        return scroll;
    }

    /** The setup checklist: ONLY the REQUIRED items, the whole card stays GONE
     *  while everything is green — and v3.1.0 (Bug 4): the HyperOS autostart
     *  row carries the 3-state answer + the 1-tap "✓ Done / මම සක්‍රිය කළා"
     *  confirmation so it can actually clear on a Xiaomi phone. */
    private void paintSetupChecklist() {
        boolean notif = Miui.notifGranted(this);
        boolean battery = Miui.batteryUnrestricted(this);
        boolean installs = canInstallApks();
        boolean autostart = Miui.autostartOk(this);
        boolean allOk = notif && battery && autostart && installs;
        setupCard.setVisibility(allOk ? View.GONE : View.VISIBLE);
        if (allOk || setupCard.getChildCount() > 0) return;
        LinearLayout head2 = row();
        head2.setGravity(Gravity.CENTER_VERTICAL);
        TextView h = new TextView(this);
        h.setText("⚠️ " + t("සැකසුම · අවසර අවශ්‍යයි (1-tap each)", "Setup · permissions needed (1-tap each)"));
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
                "✓ Done · මම සක්‍රිය කළා",
                v -> {
                    Miui.confirmAutostart(this, true);
                    toast(t("සලකුණු කළා ✓ — මෙම අනතුරු ඇඟවීම ඉවත් වේ",
                        "confirmed ✓ — this warning stays cleared"));
                    setupCard.removeAllViews();
                    refresh();
                },
                v -> Miui.openAutostart(this)));
        }
        if (!installs) setupCard.addView(setupRow(
            "Install unknown apps — " + t("APK යාවත්කාලීන සඳහා", "for APK updates"),
            null, null, v -> openInstallPermission()));
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
            .setTitle("🔗 " + t("Play Video URL · සිංහල උපසිරැසි සමඟ", "Play Video URL · with Sinhala subtitles"))
            .setView(box)
            .setPositiveButton("▶ Play", (d, w) -> {
                String u = in.getText().toString().trim();
                if (u.startsWith("http://") || u.startsWith("https://"))
                    startActivity(new Intent(this, PlayActivity.class).putExtra("url", u));
                else toast(t("http(s) URL එකක් පමණයි", "http(s) URLs only"));
            })
            .setNeutralButton("📋 " + t("Paste & Play", "Paste & Play"), (d, w) -> {
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

    private View buildSettingsTab() {
        ScrollView scroll = new ScrollView(this);
        scroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
        LinearLayout root = column();
        scroll.addView(root, new ViewGroup.LayoutParams(-1, -2));
        root.addView(sectionTitle(t("සැකසුම්", "Settings")));
        LinearLayout list = card(P.card);
        list.addView(categoryRow("🎨", P.accent,
            t("පෙනුම සහ තේමාව", "Appearance & Theme"),
            t("තේමාව · AMOLED · Dynamic Color · වර්ණ · භාෂාව · density",
              "Theme · AMOLED · Dynamic Color · accent · language · density"),
            v -> openAppearance()));
        list.addView(categoryRow("💬", 0xFF8B5CF6,
            t("උපසිරැසි සහ විලුම් චිත්‍රාගාරය", "Subtitles & Style Studio"),
            t("සජීවී preview සමඟ අකුරු ප්‍රමාණය · වර්ණ · පසුබිම · ස්ථානය",
              "Font size · color · background · position, with a live preview"),
            v -> openSubtitleStudio()));
        list.addView(categoryRow("🎬", 0xFF3B82F6,
            t("ධාවනය", "Playback"),
            t("Seek පියවර · gestures · Auto-PiP · resume · HUD",
              "Seek step · gestures · Auto-PiP · resume · HUD"),
            v -> openPlayback()));
        list.addView(categoryRow("📡", 0xFF10B981,
            t("Cast සහ ජාලය", "Cast & Network"),
            t("නම · DLNA · mDNS · Worker URL · DoH", "Name · DLNA · mDNS · Worker URL · DoH"),
            v -> openCastNetwork()));
        list.addView(categoryRow("⬆️", 0xFFF59E0B,
            t("නිහඬ යාවත්කාලීන", "Silent Updates"),
            t("ස්වයංක්‍රීය · නිහඬ · throttled · Skip version",
              "Automatic · silent · throttled · Skip version"),
            v -> openUpdates()));
        list.addView(categoryRow("💾", 0xFFEC4899,
            t("Backup & Restore", "Backup & Restore"),
            t("සියලු සැකසුම් JSON එකක් ලෙස — ඔබේමයි", "All settings as clean JSON — yours to keep"),
            v -> openBackup()));
        list.addView(categoryRow("ℹ️", 0xFF94A3B8,
            t("පිළිබඳ සහ ආරක්ෂණය", "About & Security"),
            t("සංස්කරණය · ආරක්ෂණ විග්‍රහය · බලපත්‍ර", "Version · security audit · licenses"),
            v -> openAbout()));
        root.addView(list, widen());
        root.addView(caption(t("සැකසුම් 100% දේශීයයි — කිසිදු දත්ත එළතට නොයයි.",
            "All settings stay on this phone — nothing leaves the device.")));
        return scroll;
    }

    /** One Mornye-style category row: big icon chip + bold title + 1-line subtitle + ›. */
    private View categoryRow(String icon, int iconColor, String title, String subtitle, View.OnClickListener click) {
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(11), 0, dp(11));
        TextView ic = new TextView(this);
        ic.setText(icon);
        ic.setTextSize(20f);
        ic.setGravity(Gravity.CENTER);
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
        pages.addView(page, pages.getChildCount(), new FrameLayout.LayoutParams(-1, -1));   // above every tab page
        page.setAlpha(0f);
        page.animate().alpha(1f).setDuration(160).start();
    }

    private void closeSubPage() {
        if (subPage == null) return;
        View old = subPage;
        subPage = null;
        old.animate().alpha(0f).setDuration(140).withEndAction(() -> pages.removeView(old)).start();
    }

    // ── 🎨 Appearance ───────────────────────────────────────────────────────────

    private void openAppearance() {
        openSub("🎨 " + t("පෙනුම සහ තේමාව", "Appearance & Theme"), content -> {
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
                dot.setText(idx == AppPrefs.accent(prefs) ? "✓" : "");
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
            content.addView(c2, widen());

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
            // layout density
            final TextView density = settingRow(c3, t("පිරිසැලසුම ඝනත්වය", "Layout density"),
                AppPrefs.compact(prefs) ? t("සංයුක්ත", "Compact") : t("පහසු", "Comfortable"));
            density.setOnClickListener(v -> {
                boolean compact = !AppPrefs.compact(prefs);
                prefs.edit().putString(AppPrefs.K_DENSITY, compact ? AppPrefs.DENSITY_COMPACT : AppPrefs.DENSITY_COMFORTABLE).apply();
                density.setText(compact ? t("සංයුක්ත", "Compact") : t("පහසු", "Comfortable"));
                toast(t("නව cards වලට බලපායි", "applies to the cards"));
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

    private static int indexOfVal(String[] vals, String v) {
        for (int i = 0; i < vals.length; i++) if (vals[i].equals(v)) return i;
        return 0;
    }

    /** Appearance changes rebuild the themed shell instantly (no restart dialog). */
    private void themeChanged() {
        closeSubPage();
        toast(t("✓ යෙදුණි", "✓ applied"));
        recreate();
    }

    // ── 💬 Subtitles & Style Studio (REAL-TIME live preview) ────────────────────

    private void openSubtitleStudio() {
        openSub("💬 " + t("උපසිරැසි සහ විලුම් චිත්‍රාගාරය", "Subtitles & Style Studio"), content -> {
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
            preview.setText("ඔයා මොකක්ද කරන්නේ? — මේක හරිම පැහැදිලියි!");
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
                preview.setTypeface(Typeface.DEFAULT,
                    AppPrefs.subBold(prefs) ? Typeface.BOLD : Typeface.NORMAL);
                if (AppPrefs.BG_OUTLINE.equals(AppPrefs.subBg(prefs))) {
                    preview.setBackgroundColor(0);
                    preview.setShadowLayer(dp(4), 0, 0, 0xFF000000);
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
                        toast(Langs.ALL.get(which).nativeName + " ✓");
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
            final TextView polishBtn = toggleRow(c1, "✨ Cinema Polish", AppPrefs.K_CINEMA_POLISH, true);
            content.addView(c1, widen());

            // font size slider
            LinearLayout c2 = card(P.card);
            c2.addView(sliderRow(t("අකුරේ ප්‍රමාණය · Font size", "Font size"), 14, 36,
                AppPrefs.fontSp(prefs), " sp", v -> {
                    prefs.edit().putInt(AppPrefs.K_FONT_SP, v).apply();
                    restyle.run();
                }));
            // text color dots
            c2.addView(settingLabel(t("පෙළ වර්ණය · Text color", "Text color")));
            LinearLayout colors = new LinearLayout(this);
            colors.setOrientation(LinearLayout.HORIZONTAL);
            colors.setGravity(Gravity.CENTER);
            for (int i = 0; i < ThemeEngine.SUB_COLORS.length; i++) {
                final int idx = i;
                TextView dot = new TextView(this);
                dot.setGravity(Gravity.CENTER);
                dot.setTextSize(12f);
                dot.setText(idx == AppPrefs.subColor(prefs) ? "✓" : "");
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
            // background style
            final TextView bgBtn = settingRow(c2, t("පසුබිම් රටාව", "Background style"),
                AppPrefs.BG_OUTLINE.equals(AppPrefs.subBg(prefs))
                    ? t("Crisp black edge/outline පමණයි", "Crisp black edge/outline only")
                    : t("අර්ධ පාරදෘශ්‍ය pill", "Translucent pill"));
            bgBtn.setOnClickListener(v -> {
                boolean pill = AppPrefs.BG_OUTLINE.equals(AppPrefs.subBg(prefs));
                prefs.edit().putString(AppPrefs.K_SUB_BG, pill ? AppPrefs.BG_PILL : AppPrefs.BG_OUTLINE).apply();
                reopenStudio();
            });
            if (AppPrefs.BG_PILL.equals(AppPrefs.subBg(prefs))) {
                c2.addView(sliderRow(t("Pill පාරදෘශ්‍යතාව · Pill opacity", "Pill opacity") + " (0–90%)",
                    0, 90, AppPrefs.subBgOpacity(prefs), "%", v -> {
                        prefs.edit().putInt(AppPrefs.K_SUB_BG_OPACITY, v).apply();
                        restyle.run();
                    }));
            }
            final TextView boldBtn = toggleRow(c2, t("තද අකුරු · Bold text", "Bold text"), AppPrefs.K_SUB_BOLD, true,
                restyle);
            c2.addView(boldBtn);
            c2.addView(sliderRow(t("පහළ ස්ථානය · Bottom position", "Bottom position") + " (4–28%)",
                4, 28, AppPrefs.subBottomPct(prefs), "%", v -> {
                    prefs.edit().putInt(AppPrefs.K_SUB_BOTTOM_PCT, v).apply();
                    restyle.run();
                }));
            content.addView(c2, widen());
        });
    }

    /** Re-open the studio (a dot/bg change repaints its own checkmarks). */
    private void reopenStudio() { openSubtitleStudio(); }

    private String registerLabel() {
        String o = prefs.getString(AppPrefs.K_REGISTER, null);
        return o == null ? t("ස්වයංක්‍රීය (genre අනුව)", "Auto (by genre)")
            : "spoken".equals(o) ? t("කතා වහර · Spoken", "Spoken") : t("උගත් වහර · Formal", "Formal");
    }

    // ── 🎬 Playback ─────────────────────────────────────────────────────────────

    private void openPlayback() {
        openSub("🎬 " + t("ධාවනය", "Playback"), content -> {
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
            toggleRow(c1, "Auto-PiP", AppPrefs.K_AUTO_PIP, true);
            toggleRow(c1, t("තිරය අවදියෙන් තබාගන්න", "Keep screen on"), AppPrefs.K_KEEP_SCREEN_ON, true);
            toggleRow(c1, t("අවසාන ස්ථානයෙන් ඉබේම", "Auto-resume last position"), AppPrefs.K_AUTO_RESUME, true);
            content.addView(c1, widen());

            LinearLayout c2 = card(P.card);
            final TextView hud = settingRow(c2, t("පාලක සැඟවීමේ ප්‍රමාදය", "Auto-hide controls delay"),
                (AppPrefs.hudHideMs(prefs) / 1000) + " s");
            hud.setOnClickListener(v -> {
                final long[] vals = {2000, 4000, 6000};
                String[] labels = {"2 s", "4 s", "6 s"};
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

    // ── 📡 Cast & Network ──────────────────────────────────────────────────────

    private void openCastNetwork() {
        openSub("📡 " + t("Cast සහ ජාලය", "Cast & Network"), content -> {
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
                toast(t("සුරැකිණි ✓", "saved ✓"));
            });
            srow.addView(save, new LinearLayout.LayoutParams(0, -2, 1f));
            Button reset = smallBtn("Reset");
            reset.setOnClickListener(v -> {
                url.setText(BridgeApi.DEFAULT_WORKER_URL);
                prefs.edit().putString(AppPrefs.K_WORKER_URL, BridgeApi.DEFAULT_WORKER_URL).apply();
                toast("default ✓");
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
            toast(t("✓ receiver නැවත අරඹන ලදී", "✓ receiver re-armed"));
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
                out = code == 200 ? "✓ HTTP 200 · " + BridgeApi.brief(body, 60) : "✗ HTTP " + code;
                Diag.i("ping", "worker " + base + " → " + code);
            } catch (Exception e) {
                out = "✗ " + e.getMessage();
                Diag.w("ping", "worker: " + e);
            }
            final String o = out;
            main.post(() -> line.setText(o));
        }, "si-ping").start();
    }

    // ── ⬆️ Silent Updates ───────────────────────────────────────────────────────

    private void openUpdates() {
        openSub("⬆️ " + t("නිහඬ යාවත්කාලීන", "Silent Updates"), content -> {
            content.addView(caption(t("v3.1.0: යාවත්කාලීන 100% නිහඬයි — ශබ්දයක් නැත, vibration නැත, spam නැත. Progress දැනුම්දීම් තත්පරයට වරකට වඩා නොයයි.",
                "v3.1.0: updates are 100% silent — no sound, no vibration, no spam. Progress posts at most once per second.")));
            LinearLayout c1 = card(P.card);
            toggleRow(c1, t("ස්වයංක්‍රීය යාවත්කාලීනය", "Auto-update on app open"),
                AppPrefs.K_AUTO_UPDATE, true, () -> SelfUpdater.checkOnLaunch(this));
            settingRow(c1, t("සංස්කරණය", "Current version"), "v" + AppInfo.VERSION_NAME + " (code " + AppInfo.VERSION_CODE + ")");
            final TextView installPerm = settingRow(c1, t("Install unknown apps", "Install unknown apps"),
                canInstallApks() ? "✓ granted" : "✗ " + t("නැත (1-tap)", "not granted (1-tap)"));
            installPerm.setTextColor(canInstallApks() ? P.ok : P.warn);
            installPerm.setOnClickListener(v -> openInstallPermission());
            final TextView check = settingRow(c1, "🔎 " + t("දැන් පරීක්ෂා කරන්න", "Check now"), "GitHub Releases");
            check.setTextColor(P.accentDim);
            check.setOnClickListener(v -> checkForUpdates());
            settingRow(c1, "🤖 Obtainium", t("ගැලපේ — එකම signing key", "compatible — same permanent key"));
            content.addView(c1, widen());

            // the Skip-this-version control (mirror of the notification action)
            final String skipped = getSharedPreferences("selfupdate", MODE_PRIVATE).getString("skippedVersion", null);
            LinearLayout c2 = card(P.card);
            if (skipped != null && !skipped.isEmpty()) {
                final TextView sk = settingRow(c2, t("Skip කළ සංස්කරණය", "Skipped version"), "v" + skipped);
                Button un = smallBtn(t("නැවත සලකුණු කරන්න", "un-skip"));
                un.setOnClickListener(v -> {
                    getSharedPreferences("selfupdate", MODE_PRIVATE).edit().remove("skippedVersion").apply();
                    toast(t("skip ඉවත් කළා", "un-skipped ✓"));
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
        openSub("💾 " + t("Backup & Restore", "Backup & Restore"), content -> {
            content.addView(caption(t("ඔබේ සියලු සැකසුම් එක පිරිසිදු JSON ගොනුවකින් — ගෙන යා හැක, නැවත දා හැක. \"Yours to Keep\" · your entire setup in one clean JSON file.",
                "Every setting exports as one clean JSON file — carry it to a new phone and import it back. Yours to keep.")));
            LinearLayout c1 = card(P.card);
            Button exportBtn = btnPrimary("📤 " + t("Export — ගොනුවකට සුරකින්න", "Export to a file"));
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
            Button copyBtn = smallBtn("📋 " + t("JSON එක clipboard එකට", "Copy JSON to clipboard"));
            copyBtn.setOnClickListener(v -> copyBackupToClipboard());
            c1.addView(copyBtn, widen());
            Button importBtn = smallBtn("📥 " + t("Import — ගොනුවකින්", "Import from a file"));
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
            Button pasteBtn = smallBtn("📋 " + t("Clipboard JSON එක import කරන්න", "Paste JSON to import"));
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
            toast(t("📋 JSON " + json.length() + " අකුරු copied ✓", "📋 JSON copied ✓ (" + json.length() + " chars)"));
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
            toast("✗ " + r.errorSi + " · " + r.error);
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
        toast("✓ " + r.values.size() + " " + t("settings import කළා", "settings imported"));
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
                    main.post(() -> toast("✓ " + t("ගොනුව සුරැකිණි", "file saved")));
                } catch (Exception e) {
                    Diag.w("backup", "export: " + e);
                    main.post(() -> toast("✗ " + e.getMessage()));
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
                    main.post(() -> toast("✗ " + e.getMessage()));
                }
            }, "si-import").start();
        }
    }

    // ── ℹ️ About & Security ─────────────────────────────────────────────────────

    private void openAbout() {
        openSub("ℹ️ " + t("පිළිබඳ සහ ආරක්ෂණය", "About & Security"), content -> {
            LinearLayout c1 = card(P.card);
            settingRow(c1, t("සංස්කරණය", "Version"), "v" + AppInfo.VERSION_NAME + " (code " + AppInfo.VERSION_CODE + ") · targetSdk 36");
            settingRow(c1, t("ගොනු ගබඩාව", "Repository"), "dictationb1/anantplay-sinhala-player");
            settingRow(c1, "AnantPlay", t("100% නිල කළමේ වෙනස් කළ — zero fork", "100% official + unmodified — zero fork"));
            content.addView(c1, widen());

            LinearLayout c2 = card(P.card);
            c2.addView(settingLabel(t("ආරක්ෂණ විග්‍රහය · Security audit", "Security audit")));
            for (String line : new String[]{
                "✓ " + t("සීමිත අවසර පමණයි (ජාලය · දැනුම්දීම් · install · overlay)", "minimal permissions (network · notifications · install · overlay)"),
                "✓ " + t("Analytics/ads/telemetry නැත", "zero analytics / ads / telemetry SDKs"),
                "✓ " + t("AccessibilityService නැත", "zero AccessibilityService"),
                "✓ " + t("Obfuscated blobs · DexClassLoader නැත", "zero obfuscated blobs / dynamic code loading"),
                "✓ " + t("Cast policy: මෙම දුරකථනයෙන් පමණයි (local-only)", "cast policy: this device only (local-device lock)"),
                "✓ " + t("HTTPS-only යාවත්කාලීන + SHA-256 තහවුරුව", "HTTPS-only updates + SHA-256 verification"),
                "✓ " + t("log වලින් secrets redact වේ", "secrets redacted from every log"),
                "✓ allowBackup=false · " + t("වෙනත් app වලට export නැත", "no cross-app data export"),
            }) {
                TextView l = new TextView(this);
                l.setText(line);
                l.setTextColor(P.text);
                l.setTextSize(12f);
                l.setPadding(dp(2), dp(4), dp(2), dp(4));
                c2.addView(l, widen());
            }
            content.addView(c2, widen());
            content.addView(caption("THIRD_PARTY.md · media3 1.11.1 (Apache-2.0) · Noto Sans Sinhala (OFL)"));
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TAB 4 — DIAGNOSTICS (health cards · LAST CRASH · self-test · log)
    // ═══════════════════════════════════════════════════════════════════════════

    private View buildDiagTab() {
        ScrollView scroll = new ScrollView(this);
        scroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
        LinearLayout root = column();
        scroll.addView(root, new ViewGroup.LayoutParams(-1, -2));

        // ── v3.1.2 — THE HERO ROW: 1-tap Copy Full Troubleshoot Report + Share ──
        // (privacy-safe: PrivacyScrubber guards every line, zero phone details)
        Button hero = btnPrimary("📋 " + t("Copy Full Troubleshoot Report (Privacy-Safe)",
            "Copy Full Troubleshoot Report (Privacy-Safe)"));
        hero.setOnClickListener(v -> copyTroubleshootReport());
        root.addView(hero, widen());
        LinearLayout hero2 = row();
        Button shareRep = smallBtn("📤 " + t("Share Report", "Share Report"));
        shareRep.setOnClickListener(v -> shareTroubleshootReport());
        hero2.addView(shareRep, new LinearLayout.LayoutParams(0, -2, 1f));
        Button deep = smallBtn("🩺 " + t("Run Deep Troubleshoot", "Run Deep Troubleshoot"));
        deep.setOnClickListener(v -> runDeepTroubleshoot());
        hero2.addView(deep, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(hero2, widen());

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

        // ── v3.1.0 (Bug 2) — THE LAST CRASH CARD: the CrashLog report ──
        // v3.1.2 — TOP-FIRST: the exception class + message + the first app
        // frames lead (the v3.1.1 card showed only the useless bottom
        // Handler.dispatchMessage tail). Full trace on 1-tap Copy/Share.
        crashCard = card(0xFF1F0A08);
        root.addView(crashCard, widen());
        refreshers.add(() -> paintCrashCard());

        // the 1-tap buttons
        LinearLayout b1 = row();
        Button run = btnPrimary("🧪 " + t("Run Self-Test", "Run Self-Test"));
        run.setOnClickListener(v -> runSelfTest());
        b1.addView(run, new LinearLayout.LayoutParams(0, -2, 1f));
        Button copy = smallBtn("📋 " + t("Copy Log", "Copy Log"));
        copy.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("si-diag", Diag.dump()));
            toast("📋 " + t("copied ✓ — කොහෙහරි paste කරන්න", "copied ✓ — paste anywhere"));
        });
        b1.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(b1, widen());
        LinearLayout b2 = row();
        Button share = smallBtn("📤 " + t("Share Log", "Share Log"));
        share.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, Diag.dump());
            i.putExtra(Intent.EXTRA_SUBJECT, "Sinhala Player diagnostics v" + AppInfo.VERSION_NAME);
            try { startActivity(Intent.createChooser(i, t("Log බෙදාගන්න", "Share log"))); } catch (Throwable t) { toast("no share target"); }
        });
        b2.addView(share, new LinearLayout.LayoutParams(0, -2, 1f));
        Button clear = smallBtn("🗑 " + t("Clear Log", "Clear Log"));
        clear.setOnClickListener(v -> {
            Diag.reset();
            Diag.i("diag", "log cleared by user");
            toast(t("හිස් කළා", "cleared"));
            refresh();
        });
        b2.addView(clear, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(b2, widen());

        selfTestLine = new TextView(this);
        selfTestLine.setTextColor(P.accentDim);
        selfTestLine.setTextSize(12.5f);
        selfTestLine.setPadding(0, dp(8), 0, dp(4));
        selfTestLine.setText(t("Self-test: loopback → mDNS (probe + discovery) → DLNA → bridge.",
            "Self-test: loopback → mDNS (probe + discovery) → DLNA → bridge."));
        root.addView(selfTestLine, widen());

        // v3.1.2 — the log viewer OVERLAP FIX: the v3.1.1 fixed-height (420dp)
        // TextView with an internal ScrollingMovementMethod rendered overlapping
        // lines on the field phone. A real ScrollView wrapping a wrap-content
        // monospace TextView cannot overlap by construction, and the card no
        // longer reserves a giant fixed block.
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
        // bounded height, but the CONTENT flows inside its own scroller —
        // zero overlap, zero giant empty block (the v3.1.2 fix)
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(-1, dp(360));
        logLp.topMargin = dp(6);
        root.addView(logCard, logLp);
        refreshers.add(() -> logView.setText(tail(Diag.dump(), 160)));
        root.addView(caption(t("Log: සෑම event එකම — secrets redacted. Copy = සම්පූර්ණ ring එක.",
            "Log: every event, secrets redacted. Copy = the whole ring.")));
        return scroll;
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

    /** The complete 1-tap report (Status Summary + Issues + Crash + Timeline). */
    private String troubleshootReport() {
        Troubleshooter.Facts f = collectFacts();
        return Troubleshooter.buildReport(f, Diag.dump(), CrashLog.read(getFilesDir()));
    }

    private void copyTroubleshootReport() {
        try {
            String rep = troubleshootReport();
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("si-troubleshoot", rep));
            toast("📋 " + t("report copied ✓ — ", "report copied ✓ (privacy-safe, ") + rep.length() + t(" අකුරු · privacy-safe", " chars)"));
            Diag.i("diag", "troubleshoot report copied (" + rep.length() + " chars, privacy-scrubbed)");
        } catch (Throwable t) {
            Diag.w("diag", "report copy: " + t);
            toast("✗ " + t.getMessage());
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
        t.setText("⚠ " + t("අවසාන Crash එක සටහන් වී ඇත", "Last Crash Captured"));
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
        Button cp = smallBtn("📋 " + t("Copy (full)", "Copy (full)"));
        cp.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("si-crash", CrashLog.scrub(report)));
            toast("📋 " + t("full crash trace copied ✓", "full crash trace copied ✓"));
        });
        acts.addView(cp, new LinearLayout.LayoutParams(0, -2, 1f));
        Button sh = smallBtn("📤 " + t("Share", "Share"));
        sh.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, CrashLog.scrub(report));
            i.putExtra(Intent.EXTRA_SUBJECT, "Sinhala Player crash v" + AppInfo.VERSION_NAME);
            try { startActivity(Intent.createChooser(i, t("Share crash trace", "Share crash trace"))); } catch (Throwable x) { toast("no share target"); }
        });
        acts.addView(sh, new LinearLayout.LayoutParams(0, -2, 1f));
        Button cl = smallBtn("🗑 " + t("Clear", "Clear"));
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
        v.setText((ok ? "✓ " : "✗ ") + detail);
        v.setTextColor(ok ? P.ok : P.warn);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // v3.1.2 — THE DEEP TROUBLESHOOT (6 real active stages, privacy-safe)
    // ═══════════════════════════════════════════════════════════════════════════

    /** v2.3.1 — the re-entry guard: one Self-test at a time. */
    private final java.util.concurrent.atomic.AtomicBoolean selfTestRunning = new java.util.concurrent.atomic.AtomicBoolean(false);
    /** v3.1.2 — the deep troubleshoot's own guard. */
    private final java.util.concurrent.atomic.AtomicBoolean deepRunning = new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 🩺 Run Deep Troubleshoot — the real 6-stage ACTIVE self-test:
     *   1. Loopback HTTP against our own receiver (/info on port 8765);
     *   2. DLNA E2E loopback (SSDP responder up + desc.xml fetch);
     *   3. mDNS wire (the responder's kernel-loopback probe);
     *   4. Worker /health (version + storage + providers, cached for the report);
     *   5. Worker live subtitle-resolve dry-run (/api/resolve for a known title
     *      — proves the full cast→subs path without starting playback);
     *   6. Player readiness (our ExoPlayer surface state).
     * Every stage lands in the Troubleshooter timeline (privacy-scrubbed) and the
     * verdict shows in selfTestLine. The structured report is ONE tap away.
     */
    private void runDeepTroubleshoot() {
        if (!deepRunning.compareAndSet(false, true)) {
            toast(t("Deep troubleshoot දැනටමත් ක්‍රියාත්මකයි", "Deep troubleshoot already running"));
            return;
        }
        selfTestLine.setText(t("ගැඹුරු පරීක්ෂණය ක්‍රියාත්මකයි… · deep troubleshoot running…", "deep troubleshoot running…"));
        Diag.i("deepdiag", "start (6 stages)");
        final StringBuilder report = new StringBuilder();
        final boolean[] pass = {true};
        new Thread(() -> {
            try {
                // 1. loopback receiver
                int port = ReceiverService.port();
                if (port < 0) { pass[0] = false; report.append("receiver down. "); Troubleshooter.record("CAST", "stage1 loopback: receiver down"); }
                else {
                    try {
                        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/info").openConnection();
                        c.setConnectTimeout(1500); c.setReadTimeout(1500);
                        String body = new String(readAll(c.getInputStream()), StandardCharsets.UTF_8);
                        c.disconnect();
                        boolean ok = body.contains("\"ok\":true");
                        if (!ok) { pass[0] = false; report.append("loopback BAD, "); } else report.append("loopback ✓, ");
                        Troubleshooter.record("CAST", "stage1 loopback /info: " + (ok ? "ok" : "BAD"));
                    } catch (Exception e) {
                        pass[0] = false; report.append("loopback failed, ");
                        Troubleshooter.record("CAST", "stage1 loopback: " + e);
                    }
                }
                // 2. DLNA E2E loopback
                DlnaRenderer d = ReceiverService.liveDlnaRenderer();
                boolean dlnaUp = d != null && d.isRunning();
                if (dlnaUp && port > 0) {
                    try {
                        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/dlna/desc.xml").openConnection();
                        c.setConnectTimeout(1500); c.setReadTimeout(1500);
                        int code = c.getResponseCode();
                        String body = code == 200 ? new String(readAll(c.getInputStream()), StandardCharsets.UTF_8) : "";
                        c.disconnect();
                        boolean ok = code == 200 && body.contains("MediaRenderer");
                        report.append(ok ? "DLNA E2E ✓ (" + d.searchesSeen() + " searches), " : "DLNA desc ✗, ");
                        if (!ok) pass[0] = false;
                        Troubleshooter.record("CAST", "stage2 DLNA E2E loopback desc.xml: HTTP " + code + (ok ? " ok" : " BAD"));
                    } catch (Exception e) {
                        report.append("DLNA desc unreachable, ");
                        Troubleshooter.record("CAST", "stage2 DLNA E2E: " + e);
                    }
                } else if (!AppPrefs.dlnaEnabled(prefs)) {
                    report.append("DLNA off (Settings), ");
                    Troubleshooter.record("CAST", "stage2 DLNA: disabled in Settings");
                } else {
                    report.append("DLNA down, ");
                    Troubleshooter.record("CAST", "stage2 DLNA: responder down");
                }
                // 3. mDNS wire (kernel loopback probe against our own responder)
                MdnsResponder rsp = ReceiverService.liveResponder();
                boolean wireOk = false;
                if (rsp != null && rsp.isRunning()) {
                    MdnsResponder.ProbeResult pr = MdnsResponder.probeLocal(rsp, 2500);
                    wireOk = pr.answered;
                    report.append(wireOk ? "mDNS wire ✓, " : "mDNS wire ✗, ");
                    Troubleshooter.record("CAST", "stage3 mDNS wire probe: " + (wireOk ? "answered" : "no answer (responder up)"));
                } else {
                    report.append("mDNS responder down, ");
                    Troubleshooter.record("CAST", "stage3 mDNS: responder down" + (AppPrefs.mdnsEnabled(prefs) ? "" : " (disabled in Settings)"));
                }
                if (!wireOk && !dlnaUp) pass[0] = false;
                // 4. Worker /health (cache the facts for the report)
                String base = AppPrefs.workerUrl(prefs);
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(base + "/health").openConnection();
                    c.setConnectTimeout(2500); c.setReadTimeout(2500);
                    int code = c.getResponseCode();
                    String body = code == 200 ? new String(readAll(c.getInputStream()), StandardCharsets.UTF_8) : "";
                    c.disconnect();
                    workerHealthyCache = code == 200;
                    Map<String, Object> h = code == 200 ? Cue.statusOf(body) : null;
                    workerVersionCache = h == null ? "" : String.valueOf(h.get("version"));
                    workerStorageCache = h == null ? "" : String.valueOf(h.get("storage"));
                    report.append(code == 200 ? "worker " + workerVersionCache + " (" + workerStorageCache + ") ✓" : "worker HTTP " + code + ", ");
                    if (code != 200) pass[0] = false;
                    if ("cache+memory".equals(workerStorageCache)) report.append("[⚠ KV unbound], ");
                    Troubleshooter.record("SUBS", "stage4 worker /health: HTTP " + code
                        + " · v" + workerVersionCache + " · storage " + workerStorageCache
                        + (h != null && Boolean.TRUE.equals(h.get("ok")) ? " · ok" : ""));
                } catch (Exception e) {
                    workerHealthyCache = false;
                    report.append("worker unreachable, ");
                    Troubleshooter.record("SUBS", "stage4 worker /health: " + e);
                }
                // 5. the live subtitle-resolve dry-run (no playback started)
                try {
                    BridgeApi api = new BridgeApi(base);
                    Map<String, Object> r = api.resolve("The Odyssey", false, 0, 0);
                    boolean ok = r != null && String.valueOf(r.get("imdb")).startsWith("tt");
                    report.append(ok ? "subs-resolve ✓, " : "subs-resolve ✗, ");
                    if (!ok) pass[0] = false;
                    Troubleshooter.record("SUBS", "stage5 live resolve dry-run: " + (ok ? "matched tt-id" : "no match"));
                } catch (Exception e) {
                    report.append("subs-resolve failed, ");
                    Troubleshooter.record("SUBS", "stage5 live resolve dry-run: " + e);
                }
                // 6. player readiness
                PlayActivity p = PlayActivity.current();
                boolean playerOk = p == null ? true : !"error".equals(p.playerStateForDiag());
                report.append(p == null ? "player idle ✓" : "player " + p.playerStateForDiag() + (playerOk ? " ✓" : " ✗"));
                if (!playerOk) pass[0] = false;
                Troubleshooter.record("PLAYER", "stage6 player readiness: " + (p == null ? "idle (no cast open)" : p.playerStateForDiag()));
                boolean verdict = pass[0];
                Diag.i("deepdiag", (verdict ? "PASS" : "FAIL") + " — " + report);
                final boolean pv = verdict;
                main.post(() -> {
                    selfTestLine.setText((pv ? "🩺 ✓ " : "🩺 ✗ ") + report);
                    selfTestLine.setTextColor(pv ? P.ok : P.err);
                    refresh();
                });
            } finally {
                deepRunning.set(false);
            }
        }, "si-deepdiag").start();
    }

    private void runSelfTest() {
        if (!selfTestRunning.compareAndSet(false, true)) {
            toast(t("Self-test දැනටමත් ක්‍රියාත්මකයි", "Self-test already running"));
            return;
        }
        selfTestLine.setText(t("ක්‍රියාත්මකයි…", "running…"));
        Diag.i("selftest", "start");
        final StringBuilder report = new StringBuilder();
        final boolean[] pass = {true};

        new Thread(() -> {
            try {
                // 1. loopback HTTP against our own receiver (the /play path AnantPlay uses)
                int port = ReceiverService.port();
                if (port < 0) { pass[0] = false; report.append("receiver not running. "); }
                else {
                    try {
                        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/info").openConnection();
                        c.setConnectTimeout(1500);
                        c.setReadTimeout(1500);
                        String body = new String(readAll(c.getInputStream()), StandardCharsets.UTF_8);
                        c.disconnect();
                        boolean ok = body.contains("\"ok\":true") && body.contains("Sinhala");
                        report.append(ok ? "loopback ok, " : "loopback BAD, ");
                        if (!ok) pass[0] = false;
                        Diag.i("selftest", "loopback: " + body);
                    } catch (Exception e) {
                        pass[0] = false;
                        report.append("loopback failed (").append(e.getMessage()).append("), ");
                        Diag.w("selftest", "loopback: " + e);
                    }
                }

                // 2. mDNS — the honest layers (L1 daemon · L2 responder · L3 NSD)
                boolean daemonOk = NsdAnnouncer.probeDaemon(MainActivity.this, 2500);
                Diag.i("selftest", "mdns daemon probe (mdnsd unicast): " + daemonOk);

                MdnsResponder responder = ReceiverService.liveResponder();
                boolean tempResponder = false;
                if (responder == null || !responder.isRunning()) {
                    try {
                        java.net.InetAddress ip = NsdAnnouncer.wifiLanIp();
                        if (ip == null) ip = java.net.InetAddress.getByName("127.0.0.1");
                        int rport = ReceiverService.port();
                        responder = new MdnsResponder(NsdAnnouncer.TYPE, NsdAnnouncer.OLD_TYPE,
                            NsdAnnouncer.RESPONDER_NAME, rport > 0 ? rport : ReceiverServer.PREFERRED_PORT, ip);
                        if (responder.start()) tempResponder = true;
                        else responder = null;
                    } catch (Throwable t) {
                        Diag.w("selftest", "temp responder: " + t);
                        responder = null;
                    }
                }
                MdnsResponder.ProbeResult wire = MdnsResponder.probeLocal(responder, 2500);
                boolean wireOk = wire.answered;
                Diag.i("selftest", "mdns wire probe (own responder, kernel loopback): answered=" + wire.answered
                    + " responder=" + wire.responderUp + " sent=" + wire.packetsSent + " recv=" + wire.packetsReceived
                    + (wire.detail.isEmpty() ? "" : " — " + wire.detail));
                if (tempResponder && responder != null) { try { responder.stop(); } catch (Throwable ignored) { } }

                boolean legacy = NsdAnnouncerTest.discoverSelf(MainActivity.this, 2000);
                Diag.i("selftest", "mdns legacy discovery: " + legacy);

                boolean mDnsOk = daemonOk || wireOk || legacy;
                NsdAnnouncerTest.result = mDnsOk;
                boolean regOk = ReceiverService.isUp() && ReceiverService.isAnnounced();
                if (mDnsOk) {
                    report.append("mDNS ok [")
                        .append(daemonOk ? "L1 daemon ✓" : "L1 daemon ✗").append(" · ")
                        .append(wireOk ? "L2 responder ✓" : "L2 responder ✗").append(" · ")
                        .append(legacy ? "L3 NSD ✓" : "L3 NSD ✗")
                        .append(regOk ? "" : " ⚠ daemon-announce pending").append("], ");
                } else {
                    pass[0] = false;
                    report.append("mDNS FAILED (no-answer), ");
                    if (!NsdAnnouncer.onWifiLikeNetwork(MainActivity.this)) {
                        report.append("[Wi-Fi ක්‍රියාත්මක නැත — Wi-Fi on කර නැවත උත්සාහ කරන්න · Turn Wi-Fi on and retry] ");
                    } else if (NsdAnnouncer.vpnActive(MainActivity.this)) {
                        report.append("[VPN (AdGuard app?) multicast බ්ලොක් කරයි · A VPN (the AdGuard app?) is blocking multicast] ");
                    } else {
                        report.append("[mdnsd පිළිතුරු නැත — DLNA path එකත් පරීක්ෂා කරන්න · the daemon did not answer — check the DLNA path below too] ");
                    }
                }

                // 3. the DLNA pillar: the SSDP responder must be up on 1900
                DlnaRenderer dlna = ReceiverService.liveDlnaRenderer();
                boolean dlnaUp = dlna != null && dlna.isRunning();
                Diag.i("selftest", "dlna renderer (SSDP 1900): " + (dlnaUp ? "up (searches " + dlna.searchesSeen() + ")" : "down/disabled"));
                if (dlnaUp) {
                    report.append("DLNA ok [SSDP up · searches ").append(dlna.searchesSeen()).append("], ");
                } else if (!AppPrefs.dlnaEnabled(prefs)) {
                    report.append("DLNA off [disabled in Settings], ");
                } else {
                    report.append("DLNA down [SSDP 1900 bind failed — mDNS paths remain], ");
                }
                boolean castOk = mDnsOk || dlnaUp;

                // 4. the Cloudflare bridge
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(
                        AppPrefs.workerUrl(prefs) + "/health").openConnection();
                    c.setConnectTimeout(2500);
                    c.setReadTimeout(2500);
                    int code = c.getResponseCode();
                    String body = code == 200 ? new String(readAll(c.getInputStream()), StandardCharsets.UTF_8) : "";
                    c.disconnect();
                    report.append("bridge ").append(code == 200 ? "ok" : "HTTP " + code).append(".");
                    Diag.i("selftest", "bridge: " + code + " " + BridgeApi.brief(body, 200));
                    if (body.contains("cache+memory")) {
                        report.append(" [⚠ Worker KV නැත — bind SUBS_KV · the worker has NO KV bound; bind SUBS_KV]");
                    }
                } catch (Exception e) {
                    report.append("bridge unreachable (").append(e.getMessage()).append(").");
                    Diag.w("selftest", "bridge: " + e);
                }

                // the verdict: loopback + (mDNS or DLNA) + bridge all count
                boolean verdict = pass[0] && castOk;
                Diag.i("selftest", (verdict ? "PASS" : "FAIL") + " — " + report);
                final boolean p = verdict;
                main.post(() -> {
                    selfTestLine.setText((p ? "✓ " : "✗ ") + report);
                    selfTestLine.setTextColor(p ? P.ok : P.err);
                    refresh();
                });
            } finally {
                selfTestRunning.set(false);
            }
        }, "si-selftest").start();
    }

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
                    toast("✓ " + t("අලුත්ම — v" + AppInfo.VERSION_NAME, "v" + AppInfo.VERSION_NAME + " is the latest release"));
                    updateBanner.setVisibility(View.GONE);
                    return;
                }
                String msg = "🆕 v" + r.latest + " " + t("ඇතුළත් · a new release is available", "— a new release is available")
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
                        toast(t("v" + r.latest + " මඟහැරියා ✓", "skipped v" + r.latest + " ✓"));
                        updateBanner.setVisibility(View.GONE);
                    })
                    .setOnCancelListener(dd -> { });
                try { d.show(); } catch (Throwable ignored) { }
                showUpdateBanner("🆕 v" + r.latest + " — " + t("නිහඬව බාගැනෙයි", "downloading quietly"), -2);
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
        GradientDrawable g = rounded(color, dp(16));
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
    private View actionBtn(String glyph, String title, View.OnClickListener click) {
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
        TextView ic = new TextView(this);
        ic.setText(glyph);
        ic.setTextSize(22f);
        ic.setGravity(Gravity.CENTER);
        l.addView(ic, new LinearLayout.LayoutParams(-1, -2));
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
        chip.setText((ok ? "✓ " : "… ") + label + " · " + detail);
        chip.setTextColor(ok ? P.ok : P.warn);
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


