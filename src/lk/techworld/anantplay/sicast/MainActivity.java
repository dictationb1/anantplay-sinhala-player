package lk.techworld.anantplay.sicast;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
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
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/* v3.0.0 — THE 4-TAB MODERN UI (the complete consumer-grade redesign).
 *
 * The v2.x single-scroll screen carried every setting, explanation and the log
 * in one endless column — powerful for an engineer, hostile for a movie
 * watcher. This is the replacement: a sleek Material-Dark app with a bottom
 * navigation, ZERO walls of text (every explanation collapsed behind a small
 * "ⓘ" or a card that only opens when something needs attention), and a
 * diagnostics tab whose whole job is the 1-tap "📋 Copy Full Log" the field
 * debugging was crying for.
 *
 *   TAB 1 🏠 Home        — hero status card (big green/amber receiver pill +
 *                         Start/Stop + a live 3-chip strip Receiver/Cast-DLNA/
 *                         Subs-Engine), a clean 2×2 quick-action grid (Open
 *                         AnantPlay · Watch in WebView · Play Video URL · Test
 *                         Player), a compact collapsible 3-step cast guide, a
 *                         setup checklist pill that stays 100% hidden once
 *                         everything is green, and the live update banner.
 *   TAB 2 🌐 WebView     — the built-in Cinema Browser (WebBrowserView): the
 *                         stream-sniffer pill "🎬 සිංහල උපසිරැසි සමඟ බලන්න"
 *                         floats over the video, ad/popups blocked, ⚡Auto-Open.
 *   TAB 3 ⚙️ Settings    — grouped cards: Subtitles (18 languages, Spoken/
 *                         Formal, auto-fetch), Player & WebView (auto-open,
 *                         ad-block), Updates (auto-update, version badge, the
 *                         Install-Unknown-Apps state with a 1-tap jump, manual
 *                         check, the Obtainium note), Advanced collapsed
 *                         (Worker URL + reset).
 *   TAB 4 🩺 Diagnostics — the five health cards (Loopback · NSD daemon · mDNS
 *                         responder · DLNA renderer · Worker bridge), 1-tap
 *                         Run Self-Test / 📋 Copy Full Log / 📤 Share Log /
 *                         Clear Log, and the monospace log viewer.
 *
 * Android 16 (targetSdk 36) edge-to-edge safe: the content and the bottom bar
 * are padded by the REAL window insets (no deprecated system-ui flags), so
 * nothing clips under the status or gesture bars on HyperOS. All-programmatic
 * UI — the project's zero-XML-layout idiom, one file, no new dependencies. */
public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    // ── the v3 palette: explicit, never inherited from a theme ─────────────────
    static final int COL_BG = 0xFF0B1020;
    static final int COL_CARD = 0xFF111A2E;
    static final int COL_CARD2 = 0xFF0E1526;
    static final int COL_TITLE = 0xFFF1F5F9;
    static final int COL_TEXT = 0xFFE2E8F0;
    static final int COL_DESC = 0xFF94A3B8;
    static final int COL_OK = 0xFF22C55E;
    static final int COL_WARN = 0xFFF59E0B;
    static final int COL_ERR = 0xFFEF4444;
    static final int COL_ACCENT = 0xFF3B82F6;
    static final int COL_CHIP = 0xFF1A2440;

    // ── tabs ───────────────────────────────────────────────────────────────────
    private static final int TAB_HOME = 0, TAB_WEB = 1, TAB_SETTINGS = 2, TAB_DIAG = 3;
    private FrameLayout pages;
    private final TextView[] tabIcons = new TextView[4];
    private final TextView[] tabLabels = new TextView[4];
    private View homePage, settingsPage, diagPage, webPage;

    // home widgets
    private TextView heroPill, chipReceiver, chipCast, chipSubs, updateBanner;
    private android.widget.ProgressBar updateBar;
    private LinearLayout setupCard, guideCard, guideBody;
    private final List<Runnable> refreshers = new ArrayList<>();

    // diagnostics widgets
    private TextView healthLoopback, healthDaemon, healthResponder, healthDlna, healthBridge;
    private TextView selfTestLine, logView;

    // the web tab (created lazily — the first switch to Tab 2 builds it)
    private WebBrowserView browser;
    /** True once the system WebView proved unusable (updating/missing) — the tab
     *  is then disabled instead of throwing on every visit. */
    private boolean webBroken;

    /** READ_MEDIA_VIDEO on 33+, READ_EXTERNAL_STORAGE below — only used for file:// casts. */
    private static final String MEDIA_PERM = Build.VERSION.SDK_INT >= 33
        ? "android.permission.READ_MEDIA_VIDEO" : "android.permission.READ_EXTERNAL_STORAGE";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("sicast", MODE_PRIVATE);
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
        // v3.0.0 — the live update banner: SelfUpdater's UI listener drives it
        SelfUpdater.setUiListener(new SelfUpdater.UiListener() {
            @Override public void onUpdateAvailable(String version) {
                main.post(() -> showUpdateBanner("🆕 v" + version + " ඇත — බාගැනෙමින්… · downloading…", 0));
            }
            @Override public void onDownloadProgress(int percent) {
                main.post(() -> showUpdateBanner(null, percent));
            }
            @Override public void onUpdateState(String bilingual) {
                main.post(() -> showUpdateBanner(bilingual, -2));
            }
        });
    }

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

    /**
     * v3.0.0 — THE WebView LEAK FIX. MainActivity owns the only WebView in the app
     * (Tab 2), so it is the only place that may destroy it: without this, the
     * WebView, its renderer process and any still-buffered media decode outlive the
     * Activity (background audio + a leaked window). The pending Handler callbacks
     * (the 1.2 s refresh loop) and the update listener are detached here too, so
     * nothing can touch a dead view after the screen is gone.
     */
    @Override
    protected void onDestroy() {
        SelfUpdater.setUiListener(null);
        if (main != null) main.removeCallbacksAndMessages(null);
        if (browser != null) {
            try { browser.onPauseWeb(); } catch (Throwable ignored) { }
            try { browser.destroy(); } catch (Throwable ignored) { }
            browser = null;
            webPage = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Tab 2 keeps its own history: back = previous page, then tabs, then exit
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
        root.setBackgroundColor(COL_BG);

        pages = new FrameLayout(this);
        root.addView(pages, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setBackgroundColor(0xF20E1526);
        GradientDrawable topLine = new GradientDrawable();
        topLine.setColor(0xF20E1526);
        bottomBar.setBackground(topLine);
        String[][] tabs = {
            {"🏠", "Home", "මුල"},
            {"🌐", "WebView", "බ්‍රව්සර්"},
            {"⚙️", "Settings", "සැකසුම්"},
            {"🩺", "Diagnostics", "දෝෂ නිර්ණය"},
        };
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(0, dp(7), 0, dp(7));
            TextView icon = new TextView(this);
            icon.setText(tabs[i][0]);
            icon.setTextSize(19f);
            icon.setGravity(Gravity.CENTER);
            TextView label = new TextView(this);
            label.setText(tabs[i][1] + " · " + tabs[i][2]);
            label.setTextSize(9.5f);
            label.setGravity(Gravity.CENTER);
            tab.addView(icon, new LinearLayout.LayoutParams(-1, -2));
            tab.addView(label, new LinearLayout.LayoutParams(-1, -2));
            tab.setOnClickListener(v -> selectTab(idx));
            tabIcons[i] = icon;
            tabLabels[i] = label;
            bottomBar.addView(tab, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(bottomBar, blp);

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

    private void selectTab(int idx) {
        // v3.0.0 — Tab 2 is built lazily on the first visit. Android System WebView
        // can be MID-UPDATE (the provider is briefly uninstalled) or missing on a
        // debloated ROM: constructing the WebView then throws. That must never crash
        // MainActivity — we disable the tab once and stay on Home instead.
        if (idx == TAB_WEB && browser == null && !webBroken) {
            try {
                browser = new WebBrowserView(this);
                webPage = browser;
                pages.addView(webPage, new FrameLayout.LayoutParams(-1, -1));
            } catch (Throwable t) {
                webBroken = true;
                browser = null;
                webPage = null;
                Diag.w("web", "System WebView unavailable (" + t + ") — Tab 2 disabled");
                toast("Android System WebView යාවත්කාලීන වෙමින් · Play Store එකෙන් update කරන්න"
                    + "\n(WebView updating/missing — update it, then reopen the tab)");
                idx = TAB_HOME;
            }
        }
        currentTab = idx;
        homePage.setVisibility(idx == TAB_HOME ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility(idx == TAB_SETTINGS ? View.VISIBLE : View.GONE);
        diagPage.setVisibility(idx == TAB_DIAG ? View.VISIBLE : View.GONE);
        if (webPage != null) webPage.setVisibility(idx == TAB_WEB ? View.VISIBLE : View.GONE);
        for (int i = 0; i < 4; i++) {
            boolean on = i == idx;
            tabIcons[i].setTextColor(on ? COL_ACCENT : COL_DESC);
            tabLabels[i].setTextColor(on ? 0xFF9EC5FF : COL_DESC);
            tabIcons[i].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT, Typeface.NORMAL);
        }
        if (idx == TAB_DIAG) refresh();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TAB 1 — HOME
    // ═══════════════════════════════════════════════════════════════════════════

    private View buildHomeTab() {
        ScrollView scroll = new ScrollView(this);
        scroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
        scroll.setVerticalFadingEdgeEnabled(false);
        LinearLayout root = column();
        scroll.addView(root, new ViewGroup.LayoutParams(-1, -2));

        // ── the live update banner (only visible when an update exists) ──
        updateBanner = new TextView(this);
        updateBanner.setTextColor(0xFF9EC5FF);
        updateBanner.setTextSize(12.5f);
        updateBanner.setPadding(dp(12), dp(9), dp(12), dp(9));
        GradientDrawable ub = rounded(COL_CHIP, dp(12));
        ub.setStroke(dp(1), 0xFF3B82F6);
        updateBanner.setBackground(ub);
        updateBanner.setVisibility(View.GONE);
        updateBanner.setOnClickListener(v -> checkForUpdates());
        root.addView(updateBanner, widen());
        updateBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        updateBar.setMax(100);
        updateBar.setVisibility(View.GONE);
        updateBar.getProgressDrawable().setColorFilter(COL_ACCENT, android.graphics.PorterDuff.Mode.SRC_IN);
        root.addView(updateBar, widen());

        // ── HERO: the receiver status card ──
        LinearLayout hero = card(COL_CARD);
        heroPill = new TextView(this);
        heroPill.setTextSize(17f);
        heroPill.setTypeface(Typeface.DEFAULT_BOLD);
        heroPill.setPadding(dp(14), dp(8), dp(14), dp(8));
        heroPill.setBackground(rounded(0x2622C55E, dp(20)));
        hero.addView(heroPill, new LinearLayout.LayoutParams(-2, -2));
        TextView heroSub = new TextView(this);
        heroSub.setText("AnantPlay එකේ Cast බොත්තමෙන් \"සිංහල Player\" තෝරන්න · pick \"සිංහල Player\" in AnantPlay's Cast menu");
        heroSub.setTextColor(COL_DESC);
        heroSub.setTextSize(11.5f);
        heroSub.setPadding(0, dp(4), 0, 0);
        hero.addView(heroSub, widen());
        Button toggle = btnPrimary("⏻");
        toggle.setTextSize(15f);
        toggle.setOnClickListener(v -> {
            if (ReceiverService.isUp()) {
                startService(new Intent(this, ReceiverService.class).setAction(ReceiverService.ACTION_STOP));
                toast("Receiver off · වසා දමන ලදී");
            } else {
                ReceiverService.arm(this, "user");
                toast("arming… · සක්‍රිය වෙමින්…");
            }
            main.postDelayed(this::refresh, 800);
        });
        hero.addView(toggle, widen());
        // the 3-chip live status strip
        HorizontalScrollView chips = new HorizontalScrollView(this);
        chips.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipReceiver = statusChip("Receiver …");
        chipCast = statusChip("Cast/DLNA …");
        chipSubs = statusChip("Subs Engine …");
        chipRow.addView(chipReceiver);
        chipRow.addView(chipCast);
        chipRow.addView(chipSubs);
        chips.addView(chipRow, new ViewGroup.LayoutParams(-2, -2));
        hero.addView(chips, widen());
        root.addView(hero, widen());

        refreshers.add(() -> {
            boolean up = ReceiverService.isUp();
            boolean castable = ReceiverService.castDiscoverable();
            boolean announced = ReceiverService.isAnnounced();
            heroPill.setText(up
                ? (announced || castable ? "🟢 සිංහල Player සූදානම්" : "🟡 සූදානම් — announce වෙමින්…")
                : "⚪ ක්‍රියාත්මක නැත · Off");
            heroPill.setTextColor(up ? COL_OK : COL_DESC);
            GradientDrawable hp = rounded(up ? 0x2622C55E : 0x26FFFFFF, dp(20));
            heroPill.setBackground(hp);
            toggle.setText(up ? "⏹ Stop receiver · වසන්න" : "▶ Start receiver · අරඹන්න");
            paintChip(chipReceiver, up, "Receiver", up ? "port " + ReceiverService.port() : "off");
            paintChip(chipCast, castable, "Cast/DLNA",
                castable ? (announced ? "Nsd+Rsp+DLNA" : "Rsp+DLNA") : "arming…");
            paintChip(chipSubs, bridgeConfigured(), "Subs Engine", bridgeConfigured() ? "ready" : "check worker");
        });

        // ── the 2×2 quick-action grid ──
        root.addView(sectionTitle("Quick actions · ඉක්මන් ක්‍රියා"));
        LinearLayout g1 = row();
        g1.addView(actionBtn("▶", "Open AnantPlay", "AnantPlay විවෘත කරන්න", v -> {
            Miui.openAnantPlay(this);
            toast("සිංහල Player සූදානම් — AnantPlay හි Cast බොත්තම ඔබන්න");
        }), new LinearLayout.LayoutParams(0, -2, 1f));
        g1.addView(actionBtn("🌐", "Watch in WebView", "බ්‍රව්සරයෙන් බලන්න", v -> selectTab(TAB_WEB)),
            new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(g1, widen());
        LinearLayout g2 = row();
        g2.addView(actionBtn("🔗", "Play Video URL", "URL එකක් බලන්න", v -> playUrlDialog()),
            new LinearLayout.LayoutParams(0, -2, 1f));
        g2.addView(actionBtn("🎬", "Test Player", "පරීක්ෂණ වීඩියෝව", v ->
            startActivity(new Intent(this, PlayActivity.class)
                .putExtra("test", true).putExtra("title", "Sinhala Player Test"))),
            new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(g2, widen());

        // ── the collapsible 3-step cast guide (visual, NOT a wall of text) ──
        guideCard = card(COL_CARD);
        LinearLayout guideHead = row();
        guideHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView guideTitle = new TextView(this);
        guideTitle.setText("❔ How to use · භාවිත කරන ආකාරය");
        guideTitle.setTextColor(COL_TITLE);
        guideTitle.setTextSize(14.5f);
        guideTitle.setTypeface(Typeface.DEFAULT_BOLD);
        guideHead.addView(guideTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView guideToggle = new TextView(this);
        guideToggle.setText("▾");
        guideToggle.setTextColor(COL_DESC);
        guideToggle.setTextSize(16f);
        guideToggle.setPadding(dp(10), 0, dp(6), 0);
        guideHead.addView(guideToggle, new LinearLayout.LayoutParams(-2, -2));
        guideCard.addView(guideHead, widen());
        guideBody = column();
        guideBody.addView(guideStep("1", "Receiver ON කරන්න · Turn the receiver ON",
            "Home එකේ Start receiver — ස්වයංක්‍රීයවම ඇරෙනවා (boot වූ විටත්)."));
        guideBody.addView(guideStep("2", "AnantPlay එකේ Cast ඔබන්න · Tap Cast in AnantPlay",
            "Player එකේ ⌐▷ Cast chip → \"සිංහල Player\" තෝරන්න. (නැත්නම්: ඕනෑම link එකක් Share කරන්න හෝ Tab 2 බ්‍රව්සරය භාවිතා කරන්න.)"));
        guideBody.addView(guideStep("3", "උපසිරැසි ස්වයංක්‍රීයයි · Subtitles load automatically",
            "\"සිං\" බොත්තමෙන් භාෂා 18ක් හෝ Spoken/Formal මාරු කරන්න."));
        guideBody.setVisibility(View.GONE);
        guideCard.addView(guideBody, widen());
        guideHead.setOnClickListener(v -> {
            boolean open = guideBody.getVisibility() == View.VISIBLE;
            guideBody.setVisibility(open ? View.GONE : View.VISIBLE);
            guideToggle.setText(open ? "▾" : "▴");
        });
        root.addView(guideCard, widen());

        // ── the setup checklist pill (hidden once everything is green) ──
        setupCard = card(0xFF1F1508);
        root.addView(setupCard, widen());
        refreshers.add(() -> paintSetupChecklist());

        TextView foot = new TextView(this);
        foot.setText(String.format(Locale.US,
            "AnantPlay සිංහල Player v%s · zero fork · zero accessibility · official AnantPlay stays untouched",
            AppInfo.VERSION_NAME));
        foot.setTextColor(COL_DESC);
        foot.setTextSize(10.5f);
        foot.setPadding(0, dp(10), 0, dp(6));
        root.addView(foot, widen());
        return scroll;
    }

    /** The setup checklist: ONLY the four REQUIRED items, and the whole card
     *  stays GONE while they are all green (the "clean & hidden" rule). */
    private void paintSetupChecklist() {
        boolean notif = Miui.notifGranted(this);
        boolean battery = Miui.batteryUnrestricted(this);
        boolean autostart = !Miui.isXiaomi();      // Xiaomi-only requirement
        boolean installs = canInstallApks();
        boolean allOk = notif && battery && autostart && installs;
        setupCard.setVisibility(allOk ? View.GONE : View.VISIBLE);
        if (allOk || setupCard.getChildCount() > 0) return;
        LinearLayout head2 = row();
        head2.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText("⚠️ Setup · අවසර අවශ්‍යයි (1-tap each)");
        t.setTextColor(COL_WARN);
        t.setTextSize(13.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        head2.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        setupCard.addView(head2, widen());
        if (!notif) setupCard.addView(setupRow("Notifications — දැනුම්දීම් (Android 13+ runtime)",
            v -> requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7)));
        if (!battery) setupCard.addView(setupRow("Battery → No restrictions — බැටරි සීමා නැති",
            v -> Miui.requestBattery(this)));
        if (!autostart) setupCard.addView(setupRow("Autostart — MIUI ස්වයං ආරම්භය",
            v -> Miui.openAutostart(this)));
        if (!installs) setupCard.addView(setupRow("Install unknown apps — APK යාවත්කාලීන සඳහා",
            v -> openInstallPermission()));
    }

    private View setupRow(String label, View.OnClickListener fix) {
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(6), 0, 0);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(COL_TEXT);
        t.setTextSize(12f);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        Button b = smallBtn("Open · ඇරෙන්න");
        b.setOnClickListener(fix);
        r.addView(b, new LinearLayout.LayoutParams(-2, -2));
        return r;
    }

    private void playUrlDialog() {
        LinearLayout box = column();
        box.setPadding(dp(6), dp(4), dp(6), 0);
        final EditText in = new EditText(this);
        in.setHint("m3u8 / mpd / mp4 URL paste කරන්න");
        in.setTextSize(13f);
        in.setTextColor(COL_TEXT);
        in.setHintTextColor(COL_DESC);
        in.setBackground(rounded(COL_CARD2, dp(10)));
        in.setPadding(dp(10), dp(10), dp(10), dp(10));
        box.addView(in, widen());
        new android.app.AlertDialog.Builder(this)
            .setTitle("🔗 Play Video URL · සිංහල උපසිරැසි සමඟ")
            .setView(box)
            .setPositiveButton("▶ Play", (d, w) -> {
                String u = in.getText().toString().trim();
                if (u.startsWith("http://") || u.startsWith("https://"))
                    startActivity(new Intent(this, PlayActivity.class).putExtra("url", u));
                else toast("http(s) URL එකක් පමණයි · http(s) only");
            })
            .setNeutralButton("📋 Paste & Play", (d, w) -> {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                    CharSequence cs = cm == null || !cm.hasPrimaryClip() ? null : cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                    String u = cs == null ? "" : cs.toString().trim();
                    if (u.startsWith("http://") || u.startsWith("https://"))
                        startActivity(new Intent(this, PlayActivity.class).putExtra("url", u));
                    else toast("clipboard එකේ URL නැත · no URL on clipboard");
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
    // TAB 3 — SETTINGS
    // ═══════════════════════════════════════════════════════════════════════════

    private View buildSettingsTab() {
        ScrollView scroll = new ScrollView(this);
        scroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
        LinearLayout root = column();
        scroll.addView(root, new ViewGroup.LayoutParams(-1, -2));

        // ── Subtitles ──
        root.addView(sectionTitle("Subtitles · උපසිරැසි"));
        LinearLayout subs = card(COL_CARD);

        final TextView langBtn = settingRow(subs, "🌐 Default language · පෙරනිමි භාෂාව",
            Langs.nativeOf(prefs.getString("defaultLang", Langs.DEFAULT)) + " · "
                + Langs.englishOf(prefs.getString("defaultLang", Langs.DEFAULT)));
        langBtn.setOnClickListener(v -> {
            String[] labels = new String[Langs.ALL.size()];
            for (int i = 0; i < Langs.ALL.size(); i++) {
                Langs.Lang l = Langs.ALL.get(i);
                labels[i] = l.nativeName + " (" + l.english + ")";
            }
            new android.app.AlertDialog.Builder(this)
                .setTitle("Default subtitle language · පෙරනිමි භාෂාව")
                .setItems(labels, (d, which) -> {
                    prefs.edit().putString("defaultLang", Langs.ALL.get(which).code).apply();
                    langBtn.setText(Langs.ALL.get(which).nativeName + " · " + Langs.ALL.get(which).english);
                    toast(Langs.ALL.get(which).nativeName + " ✓");
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        });

        final TextView regBtn = settingRow(subs, "🗣 Register · වහර (auto by genre)",
            "Auto (genre) · ස්වයංක්‍රීය");
        regBtn.setOnClickListener(v -> {
            prefs.edit().putString("registerOverride",
                "spoken".equals(prefs.getString("registerOverride", null)) ? "formal"
                    : "formal".equals(prefs.getString("registerOverride", null)) ? null : "spoken").apply();
            String o = prefs.getString("registerOverride", null);
            regBtn.setText(o == null ? "Auto (genre) · ස්වයංක්‍රීය" : o + " · " + ("spoken".equals(o) ? "කතා වහර" : "උගත් වහර"));
            toast(o == null ? "Auto" : o);
        });

        final TextView autoBtn = settingRow(subs, "⚡ Auto-fetch subtitles · ස්වයංක්‍රීය උපසිරැසි",
            prefs.getBoolean("autoFetch", true) ? "ON" : "OFF");
        autoBtn.setOnClickListener(v -> {
            boolean on = !prefs.getBoolean("autoFetch", true);
            prefs.edit().putBoolean("autoFetch", on).apply();
            autoBtn.setText(on ? "ON" : "OFF");
            autoBtn.setTextColor(on ? COL_OK : COL_DESC);
        });
        autoBtn.setTextColor(prefs.getBoolean("autoFetch", true) ? COL_OK : COL_DESC);
        root.addView(subs, widen());

        // ── Player & WebView ──
        root.addView(sectionTitle("Player & WebView · ධාවකය සහ බ්‍රව්සරය"));
        LinearLayout pw = card(COL_CARD);
        final TextView autoOpen = settingRow(pw, "⚡ Auto-open sniffed WebView videos",
            prefs.getBoolean("autoOpenSniffed", false) ? "ON" : "OFF");
        autoOpen.setOnClickListener(v -> {
            boolean on = !prefs.getBoolean("autoOpenSniffed", false);
            prefs.edit().putBoolean("autoOpenSniffed", on).apply();
            autoOpen.setText(on ? "ON" : "OFF");
            autoOpen.setTextColor(on ? COL_OK : COL_DESC);
            toast(on ? "⚡ Auto-Open ON" : "⚡ Auto-Open OFF");
        });
        autoOpen.setTextColor(prefs.getBoolean("autoOpenSniffed", false) ? COL_OK : COL_DESC);
        settingRow(pw, "🚫 Ad/popup blocker", "Always ON (built-in)");
        root.addView(pw, widen());

        // ── Updates ──
        root.addView(sectionTitle("Updates · යාවත්කාලීන"));
        LinearLayout upd = card(COL_CARD);
        final TextView autoUpd = settingRow(upd, "⚡ Auto-update on app open · ස්වයංක්‍රීය යාවත්කාලීන",
            prefs.getBoolean("autoUpdate", true) ? "ON (check on open + every 4 h)" : "OFF");
        autoUpd.setOnClickListener(v -> {
            boolean on = !prefs.getBoolean("autoUpdate", true);
            prefs.edit().putBoolean("autoUpdate", on).apply();
            autoUpd.setText(on ? "ON (check on open + every 4 h)" : "OFF");
            autoUpd.setTextColor(on ? COL_OK : COL_DESC);
            if (on) SelfUpdater.checkOnLaunch(this);
        });
        autoUpd.setTextColor(prefs.getBoolean("autoUpdate", true) ? COL_OK : COL_DESC);
        settingRow(upd, "🏷 Version · සංස්කරණය", "v" + AppInfo.VERSION_NAME + " (code " + AppInfo.VERSION_CODE + ")");
        final TextView installPerm = settingRow(upd, "📦 Install unknown apps",
            canInstallApks() ? "✓ granted" : "✗ not granted (1-tap)");
        installPerm.setTextColor(canInstallApks() ? COL_OK : COL_WARN);
        installPerm.setOnClickListener(v -> openInstallPermission());
        TextView check = settingRow(upd, "🔎 Check now · දැන් පරීක්ෂා කරන්න", "GitHub Releases");
        check.setOnClickListener(v -> checkForUpdates());
        settingRow(upd, "🤖 Obtainium", "compatible — same permanent key, in-place updates forever");
        root.addView(upd, widen());

        // ── Advanced (collapsed by default) ──
        LinearLayout advCard = card(COL_CARD);
        LinearLayout advHead = row();
        advHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView advTitle = new TextView(this);
        advTitle.setText("⚙️ Advanced · උසස් (Worker URL)");
        advTitle.setTextColor(COL_TITLE);
        advTitle.setTextSize(14f);
        advTitle.setTypeface(Typeface.DEFAULT_BOLD);
        advHead.addView(advTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView advToggle = new TextView(this);
        advToggle.setText("▾");
        advToggle.setTextColor(COL_DESC);
        advToggle.setPadding(dp(10), 0, dp(6), 0);
        advHead.addView(advToggle, new LinearLayout.LayoutParams(-2, -2));
        advCard.addView(advHead, widen());
        final LinearLayout advBody = column();
        advBody.setVisibility(View.GONE);
        advHead.setOnClickListener(v -> {
            boolean open = advBody.getVisibility() == View.VISIBLE;
            advBody.setVisibility(open ? View.GONE : View.VISIBLE);
            advToggle.setText(open ? "▾" : "▴");
        });
        final EditText url = new EditText(this);
        url.setText(prefs.getString("workerUrl", BridgeApi.DEFAULT_WORKER_URL));
        url.setTextSize(12f);
        url.setTextColor(COL_TEXT);
        url.setHintTextColor(COL_DESC);
        url.setBackground(rounded(COL_CARD2, dp(10)));
        url.setPadding(dp(10), dp(9), dp(10), dp(9));
        advBody.addView(url, widen());
        LinearLayout srow = row();
        Button save = smallBtn("Save · සුරකින්න");
        save.setOnClickListener(v -> {
            prefs.edit().putString("workerUrl", url.getText().toString().trim()).apply();
            toast("සුරැකිණි ✓");
        });
        srow.addView(save, new LinearLayout.LayoutParams(0, -2, 1f));
        Button reset = smallBtn("Reset to default");
        reset.setOnClickListener(v -> {
            url.setText(BridgeApi.DEFAULT_WORKER_URL);
            prefs.edit().putString("workerUrl", BridgeApi.DEFAULT_WORKER_URL).apply();
            toast("default ✓");
        });
        srow.addView(reset, new LinearLayout.LayoutParams(0, -2, 1f));
        advBody.addView(srow, widen());
        advBody.addView(caption("Worker URL · Cloudflare bridge ලිපිනය — සිංහල උපසිරැසි සොයන/පරිවර්තනය කරන සේවාදායකය · the Cloudflare bridge that finds/translates subtitles"));
        advCard.addView(advBody, widen());
        root.addView(advCard, widen());
        return scroll;
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

    /** A setting row: bold label + a right-aligned state; returns the row (clickable). */
    private TextView settingRow(LinearLayout cardBox, String label, String value) {
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(9), 0, dp(9));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(COL_TEXT);
        t.setTextSize(13f);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(COL_DESC);
        v.setTextSize(12f);
        v.setGravity(Gravity.END);
        r.addView(v, new LinearLayout.LayoutParams(-2, -2));
        cardBox.addView(r, widen());
        return v;   // the VALUE view — the caller attaches the click + refreshes its text
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TAB 4 — DIAGNOSTICS
    // ═══════════════════════════════════════════════════════════════════════════

    private View buildDiagTab() {
        ScrollView scroll = new ScrollView(this);
        scroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
        LinearLayout root = column();
        scroll.addView(root, new ViewGroup.LayoutParams(-1, -2));

        root.addView(sectionTitle("Health · සෞඛ්‍ය සාරාංශය"));
        LinearLayout health = card(COL_CARD);
        healthLoopback = healthRow(health, "Loopback receiver (port 8765)");
        healthDaemon = healthRow(health, "NSD daemon (NsdManager)");
        healthResponder = healthRow(health, "mDNS responder (UDP 5353)");
        healthDlna = healthRow(health, "DLNA renderer (UDP 1900)");
        healthBridge = healthRow(health, "Worker bridge (KV + PA)");
        root.addView(health, widen());
        refreshers.add(() -> {
            paintHealth(healthLoopback, ReceiverService.isUp(), "port " + ReceiverService.port());
            paintHealth(healthDaemon, ReceiverService.isAnnounced(), ReceiverService.isAnnounced() ? "registered" : "silent — retry cycle armed");
            MdnsResponder r = ReceiverService.liveResponder();
            paintHealth(healthResponder, r != null && r.isRunning(),
                r == null ? "down" : (r.isPortShared() ? "up · ttl 255 · mcast v4+v6 · answered " + r.answeredCount() : "degraded"));
            DlnaRenderer d = ReceiverService.liveDlnaRenderer();
            paintHealth(healthDlna, d != null && d.isRunning(),
                d == null ? "down" : (d.isRunning() ? "up · searches " + d.searchesSeen() + " · replies " + d.repliesSent() : "bind-failed"));
            paintHealth(healthBridge, bridgeConfigured(), bridgeConfigured() ? "configured" : "worker URL missing");
        });

        // the 1-tap buttons
        LinearLayout b1 = row();
        Button run = btnPrimary("🧪 Run Self-Test");
        run.setOnClickListener(v -> runSelfTest());
        b1.addView(run, new LinearLayout.LayoutParams(0, -2, 1f));
        Button copy = smallBtn("📋 Copy Log");
        copy.setOnClickListener(v -> {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null) { toast("clipboard unavailable"); return; }
                cm.setPrimaryClip(android.content.ClipData.newPlainText("si-diag", Diag.dump()));
                toast("📋 copied ✓ — paste anywhere");
            } catch (Throwable t) {
                // some vendor ROMs throw from setPrimaryClip while the focused app is
                // in a restricted state — never let the diagnostics button crash us
                Diag.w("diag", "clipboard: " + t);
                toast("clipboard refused — use 📤 Share Log");
            }
        });
        b1.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(b1, widen());
        LinearLayout b2 = row();
        Button share = smallBtn("📤 Share Log");
        share.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, Diag.dump());
            i.putExtra(Intent.EXTRA_SUBJECT, "Sinhala Player diagnostics v" + AppInfo.VERSION_NAME);
            try { startActivity(Intent.createChooser(i, "Share log")); } catch (Throwable t) { toast("no share target"); }
        });
        b2.addView(share, new LinearLayout.LayoutParams(0, -2, 1f));
        Button clear = smallBtn("🗑 Clear Log");
        clear.setOnClickListener(v -> {
            Diag.reset();
            Diag.i("diag", "log cleared by user");
            toast("cleared");
            refresh();
        });
        b2.addView(clear, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(b2, widen());

        selfTestLine = new TextView(this);
        selfTestLine.setTextColor(0xFF7FE7FF);
        selfTestLine.setTextSize(12.5f);
        selfTestLine.setPadding(0, dp(8), 0, dp(4));
        selfTestLine.setText("Self-test: loopback → mDNS (probe + discovery) → DLNA → bridge.");
        root.addView(selfTestLine, widen());

        // the monospace log viewer
        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(10.5f);
        logView.setTextColor(0xFF8FA5C8);
        logView.setTextIsSelectable(true);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
        GradientDrawable lg = rounded(COL_CARD2, dp(12));
        logView.setBackground(lg);
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(-1, dp(420));
        logLp.topMargin = dp(6);
        root.addView(logView, logLp);
        refreshers.add(() -> logView.setText(tail(Diag.dump(), 160)));
        root.addView(caption("Log: every event, secrets redacted. Copy = the whole ring."));
        return scroll;
    }

    private TextView healthRow(LinearLayout box, String label) {
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(7), 0, dp(7));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(COL_TEXT);
        t.setTextSize(13f);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = new TextView(this);
        v.setText("…");
        v.setTextColor(COL_DESC);
        v.setTextSize(12f);
        v.setGravity(Gravity.END);
        r.addView(v, new LinearLayout.LayoutParams(-2, -2));
        box.addView(r, widen());
        return v;
    }

    private void paintHealth(TextView v, boolean ok, String detail) {
        v.setText((ok ? "✓ " : "✗ ") + detail);
        v.setTextColor(ok ? COL_OK : COL_WARN);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // the self-test (loopback → mDNS L1/L2/L3 → DLNA → bridge)
    // ═══════════════════════════════════════════════════════════════════════════

    /** v2.3.1 — the re-entry guard: one Self-test at a time. */
    private final java.util.concurrent.atomic.AtomicBoolean selfTestRunning = new java.util.concurrent.atomic.AtomicBoolean(false);

    private void runSelfTest() {
        if (!selfTestRunning.compareAndSet(false, true)) {
            toast("Self-test already running — දැනට ක්‍රියාත්මකයි");
            return;
        }
        selfTestLine.setText("running…");
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
                        report.append("[VPN (AdGuard app?) multicast බ්ලොක් කරයි — AdGuard → Settings → Network → මෙම ඇප් එකෙන් Route through AdGuard OFF කරන්න · "
                            + "A VPN (the AdGuard app?) is blocking multicast — AdGuard → Settings → Network → turn OFF Route-through-AdGuard for this app] ");
                    } else {
                        report.append("[mdnsd පිළිතුරු නැත — DLNA path එකත් පරීක්ෂා කරන්න · the daemon did not answer — check the DLNA path below too] ");
                    }
                }

                // 3. v3.0.0 — the DLNA pillar: the SSDP responder must be up on 1900
                DlnaRenderer dlna = ReceiverService.liveDlnaRenderer();
                boolean dlnaUp = dlna != null && dlna.isRunning();
                Diag.i("selftest", "dlna renderer (SSDP 1900): " + (dlnaUp ? "up (searches " + dlna.searchesSeen() + ")" : "down"));
                if (dlnaUp) {
                    report.append("DLNA ok [SSDP up · searches ").append(dlna.searchesSeen()).append("], ");
                } else {
                    // not a hard failure — the mDNS paths may carry cast alone
                    report.append("DLNA down [SSDP 1900 bind failed — mDNS paths remain], ");
                }
                boolean castOk = mDnsOk || dlnaUp;

                // 4. the Cloudflare bridge
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(
                        prefs.getString("workerUrl", BridgeApi.DEFAULT_WORKER_URL) + "/health").openConnection();
                    c.setConnectTimeout(2500);
                    c.setReadTimeout(2500);
                    int code = c.getResponseCode();
                    String body = code == 200 ? new String(readAll(c.getInputStream()), StandardCharsets.UTF_8) : "";
                    c.disconnect();
                    report.append("bridge ").append(code == 200 ? "ok" : "HTTP " + code).append(".");
                    Diag.i("selftest", "bridge: " + code + " " + BridgeApi.brief(body, 200));
                    if (body.contains("cache+memory")) {
                        report.append(" [⚠ Worker KV නැත (storage=cache+memory) — jobs isolate මාරුවේදී නැති වේ; "
                            + "wrangler kv namespace create SUBS_KV කර bind කරන්න · the worker has NO KV bound — jobs vanish on isolate restarts; bind SUBS_KV]");
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
                    selfTestLine.setTextColor(p ? COL_OK : COL_ERR);
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
                        .setTitle("Update check · යාවත්කාලීන පරීක්ෂාව")
                        .setMessage((r.error == null || r.error.isEmpty() ? "unknown" : r.error)
                            + "\n\nනැවත උත්සාහ කරන්න · please retry")
                        .setPositiveButton(android.R.string.ok, null);
                    try { d.show(); } catch (Throwable ignored) { }
                    return;
                }
                if (!r.newer) {
                    toast("✓ අලුත්ම — v" + AppInfo.VERSION_NAME + " is the latest release");
                    updateBanner.setVisibility(View.GONE);
                    return;
                }
                String msg = "🆕 v" + r.latest + " ඇතුළත් · a new release is available\n\n"
                    + (r.notes.isEmpty() ? "" : r.notes + "\n\n")
                    + "Download: AnantPlay-Sinhala-Player-v" + r.latest + ".apk\n"
                    + "Obtainium: app එක update කරන්නේ එකෙන්ම විවෘත වේ · Obtainium updates it in place.";
                android.app.AlertDialog.Builder d = new android.app.AlertDialog.Builder(this)
                    .setTitle("Update available · යාවත්කාලීන")
                    .setMessage(msg)
                    .setPositiveButton("Open release", (dd, w) -> {
                        try {
                            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(r.releaseUrl.isEmpty() ? UpdateChecker.REPO_URL + "/releases/latest" : r.releaseUrl)));
                        } catch (Throwable t) {
                            toast("browser එකක් නැත — " + UpdateChecker.REPO_URL);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null);
                try { d.show(); } catch (Throwable ignored) { }
                showUpdateBanner("🆕 v" + r.latest + " — Settings → Updates → Check now installs it automatically", -2);
            });
        }, "si-update").start();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // small view helpers (the shared Material-Dark vocabulary)
    // ═══════════════════════════════════════════════════════════════════════════

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(12), dp(16), dp(16));
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
        l.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable g = rounded(color, dp(16));
        g.setStroke(dp(1), 0x1FFFFFFF);
        l.setBackground(g);
        return l;
    }

    private TextView sectionTitle(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(COL_TITLE);
        v.setTextSize(15.5f);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setPadding(dp(2), dp(14), 0, dp(2));
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return v;
    }

    private TextView caption(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(COL_DESC);
        v.setTextSize(11.5f);
        v.setLineSpacing(dp(2), 1f);
        v.setPadding(dp(2), dp(4), 0, dp(4));
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return v;
    }

    private Button btnPrimary(String t) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(t);
        b.setTextSize(13.5f);
        b.setTextColor(Color.WHITE);
        b.setBackground(rounded(0xFF3B82F6, dp(12)));
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
        b.setTextColor(COL_TEXT);
        b.setBackground(rounded(COL_CHIP, dp(12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    /** One 2×2 grid action button: big glyph + bold label + bilingual sub-label. */
    private View actionBtn(String glyph, String title, String sub, View.OnClickListener click) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setPadding(dp(8), dp(14), dp(8), dp(12));
        GradientDrawable g = rounded(COL_CARD, dp(16));
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
        t.setTextColor(COL_TITLE);
        t.setTextSize(13.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        l.addView(t, new LinearLayout.LayoutParams(-1, -2));
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextColor(COL_DESC);
        s.setTextSize(10.5f);
        s.setGravity(Gravity.CENTER);
        l.addView(s, new LinearLayout.LayoutParams(-1, -2));
        l.setOnClickListener(click);
        return l;
    }

    private TextView statusChip(String initial) {
        TextView t = new TextView(this);
        t.setText(initial);
        t.setTextColor(COL_DESC);
        t.setTextSize(11.5f);
        t.setBackground(rounded(COL_CHIP, dp(14)));
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private void paintChip(TextView chip, boolean ok, String label, String detail) {
        chip.setText((ok ? "✓ " : "… ") + label + " · " + detail);
        chip.setTextColor(ok ? COL_OK : COL_WARN);
    }

    /** One numbered guide step: a circle badge + one bold line + one desc line. */
    private View guideStep(String n, String title, String desc) {
        LinearLayout l = row();
        l.setGravity(Gravity.TOP);
        l.setPadding(0, dp(8), 0, 0);
        TextView badge = new TextView(this);
        badge.setText(n);
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(13f);
        badge.setGravity(Gravity.CENTER);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = rounded(0xFF3B82F6, dp(99));
        badge.setBackground(bg);
        l.addView(badge, new LinearLayout.LayoutParams(dp(26), dp(26)));
        LinearLayout text = column2();
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(COL_TEXT);
        t.setTextSize(12.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(t, new LinearLayout.LayoutParams(-1, -2));
        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextColor(COL_DESC);
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
