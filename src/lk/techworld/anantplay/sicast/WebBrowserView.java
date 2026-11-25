package lk.techworld.anantplay.sicast;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/* v3.0.0 — THE BUILT-IN CINEMA BROWSER (Tab 2): a real WebView streaming browser
 * whose whole purpose is the ZERO-CAST subtitle path the user asked for —
 * "playing any video immediately loads Sinhala subtitles, no Cast hunting".
 * Works on Wi-Fi AND Mobile Data, on ANY router: no mDNS, no SSDP, no AnantPlay
 * discovery — just browse, and the sniffer does the rest.
 *
 *   TOP BAR   — URL/search box (Enter = go; non-URL input = DuckDuckGo search),
 *               back / forward / reload, a ⋮ menu (Desktop mode toggle, Add
 *               bookmark, Bookmarks…, Paste link from clipboard).
 *   SNIFFER   — shouldInterceptRequest classifies every request with the pure
 *               StreamSniffer core (HLS/DASH/MP4/MKV/WEBM, ad/tracker/segment
 *               filtered) and captures the EXACT Referer/Origin/Cookie/UA the
 *               site served the stream with; an injected JS hook catches
 *               <video>/<source> elements the network layer can't see, and the
 *               page title goes through TitleCleaner for the subtitle lookup.
 *   PILL      — the moment a real stream is detected: "🎬 සිංහල උපසිරැසි සමඟ
 *               බලන්න (HLS)" floats over the video — one tap opens OUR Media3
 *               player with the SAME url+headers and auto-fetches Sinhala subs
 *               (the Worker v4.1 two-stage pipeline). An ⚡Auto chip toggles
 *               auto-open (default OFF, user-controlled per the spec).
 *   BLOCKER   — known ad/tracker domains are blocked in shouldOverrideUrlLoading
 *               (no popup storms) and popups/windows are refused entirely
 *               (setSupportMultipleWindows + a no-op onCreateWindow).
 *
 * Zero new dependencies: android.webkit only — the APK stays as light as v2.4.0. */
public class WebBrowserView extends FrameLayout {

    private final Activity act;
    private final SharedPreferences prefs;
    private WebView web;
    private EditText urlBox;
    // v3.4.2 — the omnibar host (IME inset target) + the live suggestion dropdown
    private LinearLayout omnibar;
    private LinearLayout suggestBox;
    private ProgressBar progress;
    private TextView pill;
    private ImageView autoChip;
    private LinearLayout pillRow;
    /** The latest hits, newest first, deduped by URL (the pill shows the newest). */
    private final LinkedHashMap<String, StreamSniffer.Hit> hits = new LinkedHashMap<String, StreamSniffer.Hit>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, StreamSniffer.Hit> e) { return size() > 12; }
    };
    private String lastAutoOpened = "";
    private long lastAutoOpenAt;
    /** v3.2.0 — the custom-links quick launcher row (Settings → Web Player &
     *  Custom Links owns the list; this row renders it as 1-tap site chips). */
    private LinearLayout linksRow;
    /** v3.4.0 - the stacked web area, the speed-dial start page and the light tab strip. */
    private FrameLayout webHost;
    private FrameLayout startPage;
    private LinearLayout tabStrip;
    private final java.util.List<String> tabUrls = new java.util.ArrayList<>();
    private int activeTab;
    private int blockedCount;
    private FrameLayout shieldBtn;
    private TextView shieldBadge;
    private final java.util.List<String> blockedHosts = new java.util.ArrayList<>();
    /** v3.2.0 — the AnantPlay-style custom-fullscreen holder. */
    private View fullscreenView;
    private android.webkit.WebChromeClient.CustomViewCallback fullscreenCb;

    // v3.4.6.d — Google is the default home + search engine (DuckDuckGo was
    // the v3.4.6.c default; DDG/Startpage/Brave stay available in Settings).
    static final String HOME_URL = "https://www.google.com/";
    private static final String UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    public WebBrowserView(Activity act) {
        super(act);
        this.act = act;
        this.prefs = act.getSharedPreferences("sicast", Context.MODE_PRIVATE);
        buildUi();
    }

    /**
     * v3.4.6.d — THE HEADLESS (CHROME-HIDDEN) CONSTRUCTOR: used whenever the
     * view is embedded in the Flutter shell as the anantplay/webview
     * PlatformView. EVERY piece of the old Java browser chrome is omitted —
     * no Java omnibar, no Java nav buttons, no Java tab strip, no Java speed
     * dial, no Java snackbar pills — so ONLY the pure native Chromium WebView
     * viewport renders. The Flutter BrowserScreen owns 100% of the visible
     * UI (the Mornye omnibar, speed dial and capture dock), while 100% of
     * the hardened Chromium settings (MIXED_CONTENT_NEVER_ALLOW, BridgeGuard
     * Blob subtitle capture, StreamSniffer, ad/tracker blocker, reader dark
     * mode, the JS automation hooks) keep working unchanged.
     */
    public WebBrowserView(Activity act, boolean headlessMode) {
        super(act);
        this.act = act;
        this.prefs = act.getSharedPreferences("sicast", Context.MODE_PRIVATE);
        this.headless = headlessMode;
        buildUi();
    }

    /** True when embedded in the Flutter shell (all Java chrome omitted). */
    private boolean headless;

    /** v3.4.6.f — the deep probe reads the ACTUAL construction policy. */
    boolean isHeadless() { return headless; }

    // v3.4.6.f — GAP #4: runtime hardening facts, captured from the REAL
    // WebSettings after configuration and read by DeepProbe's browser hooks.
    private static volatile boolean lastMixedNever =
        android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW ==
        android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW;
    private static volatile boolean lastFileAccess = false;
    private static volatile boolean lastContentAccess = false;
    private static volatile boolean lastHeadless = true;

    /** The real WebSettings state (never a hardcoded probe result). */
    static boolean[] liveHardeningState() {
        WebBrowserView v = androidAppView;
        if (v != null && v.web != null) {
            android.webkit.WebSettings s = v.web.getSettings();
            lastMixedNever = s.getMixedContentMode() ==
                android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW;
            lastFileAccess = s.getAllowFileAccess();
            lastContentAccess = s.getAllowContentAccess();
        }
        return new boolean[]{
            lastMixedNever, !lastFileAccess, !lastContentAccess,
            BridgeGuard.blobCapConfigured(), BridgeGuard.blobGuardConfigured(),
        };
    }

    /** The actual headless policy of the Flutter PlatformView lane. */
    static boolean platformViewHeadless() { return lastHeadless; }

    private static volatile WebBrowserView androidAppView;

    private void noteRuntimeState(android.webkit.WebSettings s) {
        lastMixedNever = s.getMixedContentMode() ==
            android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW;
        lastFileAccess = s.getAllowFileAccess();
        lastContentAccess = s.getAllowContentAccess();
        lastHeadless = headless;
        androidAppView = this;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /**
     * v3.4.6.d — the SHARED web host builder: the FrameLayout that stacks the
     * progress bar, the (non-headless) speed-dial start page and the hardened
     * Chromium WebView. Called by BOTH constructors — the headless (Flutter
     * PlatformView) lane passes exactly this and NOTHING else, so only the
     * pure WebView viewport renders while every hardened setting survives.
     */
    private void buildWebHost() {
        webHost = new FrameLayout(act);
        column.addView(webHost, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    // ── UI ─────────────────────────────────────────────────────────────────────

    /** v3.4.6.d — the vertical host (field so the headless lane can share it). */
    private LinearLayout column;

    private void buildUi() {
        setBackgroundColor(0xFF0B1020);

        column = new LinearLayout(act);
        column.setOrientation(LinearLayout.VERTICAL);
        addView(column, new FrameLayout.LayoutParams(-1, -1));
        // v3.4.6.d — HEADLESS (the Flutter PlatformView lane): the column
        // hosts ONLY the WebView viewport — the Java omnibar, tab strip,
        // suggestions, shield and speed dial are all OMITTED (the Flutter
        // BrowserScreen owns 100% of the visible UI). Every hardened
        // Chromium setting below still applies to the WebView.

        // ── v3.4.0 — THE OM NIBAR: one widget, two positions (K_OMNIBAR_POS).
        //    Bottom is the default because it is the only reachable place on a 6.7" phone.
        // v3.4.6.d — OMITTED entirely in headless mode (Flutter renders the omnibar).
        final boolean omnibarBottom = !headless && AppPrefs.omnibarBottom(prefs);
        LinearLayout bar = null;
        if (!headless) {
        bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        GlassSurface.apply(bar, prefs, 0xFF0E1526, 22);
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));
        if (!omnibarBottom) column.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        omnibar = bar;   // v3.4.2 — the host the IME inset is applied to

        bar.addView(navBtn(R.drawable.ic_back, v -> goBack()), new LinearLayout.LayoutParams(dp(44), dp(44)));
        bar.addView(navBtn(R.drawable.ic_forward, v -> forward()), new LinearLayout.LayoutParams(dp(44), dp(44)));
        urlBox = new EditText(act);
        urlBox.setTextSize(13f);
        urlBox.setTextColor(0xFFE2E8F0);
        urlBox.setHintTextColor(0xFF94A3B8);
        urlBox.setHint(Chrome.pick("URL or search · URL හෝ සෙවුම"));
        urlBox.setBackground(GlassSurface.drawable(act, prefs, 0xFF101A30, 12,
            AppPrefs.glass(prefs)));
        urlBox.setPadding(dp(10), dp(8), dp(10), dp(8));
        urlBox.setSingleLine(true);
        urlBox.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBox.setOnEditorActionListener((v, action, ev) -> {
            if (action == EditorInfo.IME_ACTION_GO
                || (ev != null && ev.getAction() == KeyEvent.ACTION_DOWN && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                go(urlBox.getText().toString());
                return true;
            }
            return false;
        });
        bar.addView(urlBox, new LinearLayout.LayoutParams(0, -2, 1f));
        // ── v3.4.2 — LIVE SEARCH SUGGESTIONS (debounced 250 ms, HTTPS, off-main) ──
        // The dropdown is a plain LinearLayout inside the same column, so it can never
        // float over the keyboard: it is part of the layout that the IME inset pushes.
        suggestBox = new LinearLayout(act);
        suggestBox.setOrientation(LinearLayout.VERTICAL);
        GlassSurface.apply(suggestBox, prefs, 0xFF101A30, 12);
        suggestBox.setVisibility(View.GONE);
        urlBox.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                scheduleSuggestions(e == null ? "" : e.toString());
            }
        });
        urlBox.setOnFocusChangeListener((v, has) -> {
            if (!has) suggestBox.setVisibility(View.GONE);
        });
        // v3.4.0 - the ad-block shield + a live blocked count badge
        shieldBtn = new FrameLayout(act);
        shieldBtn.setContentDescription("Ad blocking status");
        shieldBtn.setFocusable(true);
        shieldBtn.setClickable(true);
        GlassSurface.applyIcon(shieldBtn, prefs, 0xFF14532D, 18);
        ImageView shieldIcon = new ImageView(act);
        shieldIcon.setImageDrawable(UiIcons.tinted(act, R.drawable.ic_shield_adblock, 0xFF22C55E, 19));
        shieldIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        shieldBtn.addView(shieldIcon, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        shieldBadge = new TextView(act);
        shieldBadge.setTextSize(10.5f);
        shieldBadge.setTypeface(Typeface.DEFAULT_BOLD);
        shieldBadge.setTextColor(0xFF04170B);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(0xFF22C55E);
        sbg.setCornerRadius(dp(6));
        shieldBadge.setBackground(sbg);
        shieldBadge.setPadding(dp(3), 0, dp(3), 0);
        shieldBadge.setVisibility(View.GONE);
        FrameLayout.LayoutParams sbLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        sbLp.topMargin = dp(2);
        sbLp.rightMargin = dp(1);
        shieldBtn.addView(shieldBadge, sbLp);
        shieldBtn.setOnClickListener(v -> showBlockedSheet());
        bar.addView(shieldBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));
        bar.addView(navBtn(R.drawable.ic_refresh, v -> reload()), new LinearLayout.LayoutParams(dp(44), dp(44)));
        bar.addView(navBtn(R.drawable.ic_more_dots, v -> menu()), new LinearLayout.LayoutParams(dp(44), dp(44)));
        }   // v3.4.6.d — end of the !headless Java omnibar block

        // ── the tab strip (v3.4.0: light tabs - one renderer, zero OOM risk) ──
        // v3.4.6.d — OMITTED in headless mode (no Java tab strip in the shell).
        if (!headless) {
            tabStrip = new LinearLayout(act);
            tabStrip.setOrientation(LinearLayout.HORIZONTAL);
            GlassSurface.apply(tabStrip, prefs, 0xFF0B1020, 10);
            tabStrip.setPadding(dp(8), dp(4), dp(8), dp(6));
            column.addView(tabStrip, new LinearLayout.LayoutParams(-1, -2));
        }

        // ── the web host: WebView + progress + start page + capture dock ALL stack here,
        //    so every child uses FrameLayout.LayoutParams (the v3.3.1 static gate) ──
        buildWebHost();

        progress = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.getProgressDrawable().setColorFilter(0xFF3B82F6, android.graphics.PorterDuff.Mode.SRC_IN);
        progress.setAlpha(0f);
        FrameLayout.LayoutParams progLp = new FrameLayout.LayoutParams(-1, dp(2), Gravity.TOP);
        webHost.addView(progress, progLp);

        // ── v3.4.0 — THE VISUAL SPEED DIAL (replaces the clipped horizontal chip row):
        //    a GridLayout with 2/3/4 columns in two groups, so link 5-8 can never fall
        //    off the right edge again. ──
        // v3.4.6.d — OMITTED in headless mode (the Mornye Flutter speed dial renders).
        if (!headless) {
            startPage = new FrameLayout(act);
            startPage.setBackgroundColor(0xFF0B1020);
            webHost.addView(startPage, new FrameLayout.LayoutParams(-1, -1));
            rebuildStartPage();
        }

        // ── the WebView ──
        web = new WebView(act);
        web.setBackgroundColor(0xFF0B1020);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        // v3.1.0 (security audit) — EXPLICIT hardening, never relying on platform
        // defaults that shift between Android versions: no file:// or content://
        // access from the page context, no file-scheme cross-origin escape, no
        // geolocation beacon. The browser tab is for http(s) cinema sites only.
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        if (Build.VERSION.SDK_INT >= 16) {
            s.setAllowFileAccessFromFileURLs(false);
            s.setAllowUniversalAccessFromFileURLs(false);
        }
        s.setGeolocationEnabled(false);
        s.setSaveFormData(false);
        // ══ v3.4.3 — P0 FIX #3 (Replit QA-0008): MIXED CONTENT IS BLOCKED BY DEFAULT ══
        // v3.4.2 defaulted to MIXED_CONTENT_COMPATIBILITY_MODE, which silently upgrades
        // or allows plaintext sub-resources (scripts included) on an HTTPS page — the
        // exact downgrade an on-path attacker wants. The default is now NEVER_ALLOW.
        // A legacy site that genuinely needs it can be opted in PER SESSION from the
        // ⋮ sheet (setMixedContentCompatibility), and that choice never persists.
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(mixedContentCompat
            ? WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            : WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        noteRuntimeState(s);   // v3.4.6.f — the deep probe reads these live facts
        // THE POPUP BLOCKER: no window.open targets at all — redirect popups of
        // streaming sites die right here instead of covering the video.
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setUserAgentString(effectiveUa());

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String us = u == null ? "" : String.valueOf(u);
                // ══ v3.3.0 — THE SRI LANKAN SUBTITLE CAPTURE ══════════════════
                // The user taps a .zip/.srt on Baiscope / Zoom.lk / PirateLK / SubDL
                // and the app does the rest: download → unzip (5 MB zip-bomb cap,
                // ".." traversal refused by SrtLoader) → Sinhala check → translate if
                // not → attach to the movie that is playing RIGHT NOW.
                if (LkSubFinder.isSubtitleDownload(us)) {
                    Diag.i("web", "subtitle link captured: " + StreamSniffer.hostOf(us));
                    captureSubtitle(us);
                    return true;      // never navigate away from the page
                }
                if (StreamSniffer.isAdOrTracker(us)) {
                    Diag.i("web", "blocked navigation to ad/tracker host: " + StreamSniffer.hostOf(us));
                    blockedCount++;
                    if (blockedHosts.size() < 60) blockedHosts.add(StreamSniffer.hostOf(us));
                    paintShield();
                    toast("blocked " + StreamSniffer.hostOf(us));
                    return true;   // refuse — never load it
                }
                String scheme = u == null ? "" : (u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT));
                if ("http".equals(scheme) || "https".equals(scheme)) return false;   // we load it
                // v3.1.0 (security audit) — file://, content:// and intent:// are
                // REFUSED outright (an intent:// escape can carry extras that hand
                // the user to arbitrary components; a content:// URL can probe
                // providers). Only the two SAFE external schemes (mailto:, tel:)
                // go to the system's own chooser — and only when they carry no
                // selector/flags games.
                if ("mailto".equals(scheme) || "tel".equals(scheme)) {
                    try {
                        act.startActivity(new Intent(Intent.ACTION_VIEW, u));
                    } catch (Throwable t) {
                        Diag.w("web", "external scheme refused: " + us);
                    }
                    return true;
                }
                Diag.w("web", "blocked non-http(s) navigation: " + us);   // file:// content:// intent:// about:blob …
                return true;
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                try {
                    Uri u = req.getUrl();
                    String us = u == null ? "" : String.valueOf(u);
                    // v3.2.0 — the Ad/Popup Blocker is a USER toggle (Settings →
                    // Web Player): ON by default, and when ON both the ad
                    // REQUESTS and the navigations die (shouldOverrideUrlLoading
                    // already refuses the same list).
                    if (AppPrefs.adBlock(prefs) && StreamSniffer.isAdOrTracker(us)) {
                        return new WebResourceResponse("text/plain", "utf-8",
                            new java.io.ByteArrayInputStream(new byte[0]));   // a 0-byte refuse
                    }
                    if (u != null && (StreamSniffer.shouldReport(us, null, -1))) {
                        Map<String, String> h = req.getRequestHeaders();
                        StreamSniffer.Hit hit = new StreamSniffer.Hit()
                            .url(us)
                            .kind(StreamSniffer.classify(us, null));
                        if (h != null) {
                            hit.referer = h.get("Referer");
                            hit.origin = h.get("Origin");
                            hit.cookie = h.get("Cookie");
                            hit.ua = h.get("User-Agent");
                        }
                        report(hit);
                    }
                } catch (Throwable t) {
                    Diag.w("web", "sniff: " + t);
                }
                return null;   // the WebView loads it normally — we only listen
            }

            @Override public void onPageFinished(WebView v, String url) {
                if (AppPrefs.readerDark(prefs)) {
                    try {
                        v.evaluateJavascript(
                            "(function(){try{document.documentElement.style.colorScheme='dark';}catch(e){}})()", null);
                    } catch (Throwable ignored) { }
                }
                if (urlBox != null) urlBox.setText(url == null ? "" : url);
                // clean dark-mode styling: color-scheme dark so sites that honour
                // it render dark, and our own background shows through the rest
                v.evaluateJavascript("try{document.documentElement.style.colorScheme='dark'}catch(e){}", null);
                // THE MEDIA HOOK: catch <video>/<source> picks the network layer
                // can't classify (extensionless sources, JS-built src attributes).
                v.evaluateJavascript(
                    "(function(){var f=function(el){var s=el.currentSrc||el.src;if(!s)return;" +
                    "window.SniJS&&window.SniJS.onVideo(String(s));};" +
                    "document.addEventListener('play',function(e){f(e.target)},true);" +
                    "document.addEventListener('loadedmetadata',function(e){f(e.target)},true);" +
                    "var l=document.querySelectorAll('video,source');for(var i=0;i<l.length;i++)f(l[i]);})()", null);
                injectBlobHook(v);
            }
        });

        // a real download (Content-Disposition) is captured the same way
        web.setDownloadListener((url, ua, cd, mime, len) -> {
            if (LkSubFinder.isSubtitleDownload(url)) {
                Diag.i("web", "subtitle download captured: " + StreamSniffer.hostOf(url));
                captureSubtitle(url);
            } else {
                toast("saved " + StreamSniffer.hostOf(url));
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
            @Override public boolean onCreateWindow(WebView v, boolean d, boolean u, android.os.Message r) {
                // the popup blocker: a window.open target is simply never created
                Diag.i("web", "popup refused (window.open blocked)");
                return false;
            }
            // ── v3.2.0 — ANANTPLAY-STYLE INLINE FULLSCREEN (WebActivity.java:276):
            // a site's fullscreen video request is mounted on the decor with the
            // immersive sticky flags, exactly the way official AnantPlay does. ──
            @Override public void onShowCustomView(View v, android.webkit.WebChromeClient.CustomViewCallback cb) {
                if (fullscreenView != null) { try { cb.onCustomViewHidden(); } catch (Throwable ignored) { } return; }
                fullscreenView = v;
                fullscreenCb = cb;
                try {
                    v.setBackgroundColor(Color.BLACK);
                    ((android.view.ViewGroup) act.getWindow().getDecorView()).addView(v,
                        new FrameLayout.LayoutParams(-1, -1));
                    act.getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                    Diag.i("web", "inline fullscreen mounted (AnantPlay-style)");
                } catch (Throwable t) {
                    Diag.w("web", "fullscreen mount: " + t);
                    fullscreenView = null;
                    try { cb.onCustomViewHidden(); } catch (Throwable ignored) { }
                }
            }
            @Override public void onHideCustomView() {
                if (fullscreenView == null) return;
                try {
                    ((android.view.ViewGroup) act.getWindow().getDecorView()).removeView(fullscreenView);
                    act.getWindow().getDecorView().setSystemUiVisibility(0);
                } catch (Throwable ignored) { }
                fullscreenView = null;
                if (fullscreenCb != null) { try { fullscreenCb.onCustomViewHidden(); } catch (Throwable ignored) { } fullscreenCb = null; }
            }
        });
        web.addJavascriptInterface(new JsBridge(), "SniJS");
        // v3.4.3 — the capture-session window: a real tap inside the page opens it.
        // Without one, the Blob bridge accepts nothing (see BridgeGuard).
        web.setOnTouchListener((v, ev) -> {
            try {
                if (ev != null && ev.getActionMasked() == android.view.MotionEvent.ACTION_UP)
                    captureGestureAt = android.os.SystemClock.uptimeMillis();
            } catch (Throwable ignored) { }
            return false;                       // never consume: the page keeps its taps
        });
        webHost.addView(web, new FrameLayout.LayoutParams(-1, -1));

        // ── the floating pill + ⚡Auto chip ──
        // v3.4.6.d — OMITTED in headless mode (the Flutter capture dock owns
        // the sniffed-stream surface; report() also pushes shell events).
        if (!headless) {
            pillRow = new LinearLayout(act);
            pillRow.setOrientation(LinearLayout.HORIZONTAL);
            pillRow.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setColor(0xF20E1424);
            rowBg.setCornerRadius(dp(22));
            rowBg.setStroke(dp(1), 0x33FFFFFF);
            pillRow.setBackground(rowBg);
            pillRow.setPadding(dp(6), dp(4), dp(6), dp(4));
            pillRow.setVisibility(View.GONE);
            FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            pl.bottomMargin = dp(16);
            webHost.addView(pillRow, pl);

            pill = new TextView(act);
            pill.setTextColor(0xFFE2E8F0);
            pill.setTextSize(13f);
            pill.setTypeface(Typeface.DEFAULT_BOLD);
            pill.setPadding(dp(12), dp(10), dp(12), dp(10));
            pill.setOnClickListener(v -> playLatest());
            pillRow.addView(pill, new LinearLayout.LayoutParams(-2, -2));

            autoChip = new ImageView(act);
            autoChip.setScaleType(ImageView.ScaleType.CENTER);
            autoChip.setPadding(dp(12), dp(12), dp(12), dp(12));
            autoChip.setBackground(rounded(0xFF101A30));
            autoChip.setMinimumWidth(dp(44));
            autoChip.setMinimumHeight(dp(44));
            autoChip.setFocusable(true);
            autoChip.setOnClickListener(v -> {
                boolean on = !prefs.getBoolean("autoOpenSniffed", false);
                prefs.edit().putBoolean("autoOpenSniffed", on).apply();
                paintAutoChip();
                toast(on ? "Automatic stream open enabled" : "Automatic stream open disabled");
            });
            pillRow.addView(autoChip, new LinearLayout.LayoutParams(dp(44), dp(44)));
            paintAutoChip();
        }   // v3.4.6.d — end of the !headless floating-pill block

        if (omnibarBottom && bar != null && suggestBox != null) {
            // the dropdown sits ABOVE the omnibar, in the same linear column
            column.addView(suggestBox, new LinearLayout.LayoutParams(-1, -2));
            column.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        } else if (!headless && suggestBox != null) {
            column.addView(suggestBox, new LinearLayout.LayoutParams(-1, -2));
        }
        if (!headless) {
            rebuildTabs();
            applyImeInsets();      // v3.4.2 — the omnibar rides above the keyboard
        }

        if (Build.VERSION.SDK_INT >= 19) web.setWebContentsDebuggingEnabled(false);
        String last = prefs.getString("webLastUrl", "");
        // v3.4.6.d — headless boots STRAIGHT to the page (no Java start page;
        // the Flutter speed dial is the home surface when no URL is passed).
        if (headless) {
            // the Flutter side hands the initial URL through creationParams;
            // restore the last page so tab switches keep the position.
            if (last != null && !last.isEmpty()) load(last);
        } else if (last != null && !last.isEmpty()) {
            load(last);
        } else {
            showStartPage();
        }
    }

    /** v3.4.0 - the shield badge: the live blocked count (hidden at zero). */
    private void paintShield() {
        if (shieldBadge == null) return;
        boolean show = blockedCount > 0 && AppPrefs.showBlockCount(prefs) && AppPrefs.adBlock(prefs);
        shieldBadge.setText(blockedCount > 99 ? "99+" : String.valueOf(blockedCount));
        shieldBadge.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** The blocked-host list (tap the shield). */
    private void showBlockedSheet() {
        ThemeEngine.Palette p = ThemeEngine.palette(AppPrefs.themeMode(prefs), 0,
            AppPrefs.accent(prefs), AppPrefs.accentHex(prefs), AppPrefs.cardRadius(prefs));
        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(UiSheet.header(act, p, "Blocked this session",
            blockedCount + " requests stopped · tracker + popup shield"),
            new LinearLayout.LayoutParams(-1, -2));
        if (blockedHosts.isEmpty()) {
            body.addView(UiGroup.empty(act, p, R.drawable.ic_shield_adblock,
                "Nothing blocked yet", "Ad and tracker hosts are stopped before they load."),
                new LinearLayout.LayoutParams(-1, -2));
        } else {
            for (String h : blockedHosts) {
                TextView t = new TextView(act);
                t.setText(h);
                t.setTextColor(p.text);
                UiTokens.bodySecondary(t);
                t.setPadding(0, dp(5), 0, dp(5));
                body.addView(t, new LinearLayout.LayoutParams(-1, -2));
            }
        }
        UiSheet.show(act, p, prefs, body, 55, null);
    }

    /** v3.4.0 - custom UA > desktop UA > the WebView default. */
    private String effectiveUa() {
        String custom = AppPrefs.webCustomUa(prefs);
        if (!custom.isEmpty()) return custom;
        return AppPrefs.webDesktopUa(prefs) ? UA_DESKTOP : null;
    }

    private void paintAutoChip() {
        boolean on = prefs.getBoolean("autoOpenSniffed", false);
        int tint = on ? 0xFF22C55E : 0xFF94A3B8;
        autoChip.setImageDrawable(UiIcons.tinted(act, R.drawable.ic_refresh, tint, 20));
        autoChip.setSelected(on);
        autoChip.setContentDescription("Automatic stream open, " + (on ? "on" : "off"));
        autoChip.setTooltipText("Automatic stream open: " + (on ? "on" : "off"));
    }

    /** v3.4.0 - a browser bar button: tinted vector, no glyph. */
    private View navBtn(int resId, OnClickListener click) {
        String description = resId == R.drawable.ic_back ? "Go back"
            : (resId == R.drawable.ic_forward ? "Go forward"
            : (resId == R.drawable.ic_refresh ? "Reload page" : "Browser menu"));
        return UiGlassIcon.create(act, prefs, resId, 0xFFE2E8F0, 19, 44,
            description, click);
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(10));
        return g;
    }

    // ── navigation ─────────────────────────────────────────────────────────────

    /** Enter in the box: a URL goes straight there, anything else is a search. */
    private void go(String input) {
        if (input == null || input.trim().isEmpty()) return;
        String t = input.trim();
        boolean urlish = t.startsWith("http://") || t.startsWith("https://")
            || (t.contains(".") && !t.contains(" "));
        load(urlish ? (t.startsWith("http") ? t : "https://" + t)
            : AppPrefs.searchUrl(AppPrefs.searchEngine(prefs), android.net.Uri.encode(t)));
    }

    void load(String url) {
        if (url == null || url.isEmpty()) return;
        // v3.1.0 (security audit): the address box loads http(s) only — a
        // pasted file:// or content:// URL is refused at the gate.
        String t = url.trim().toLowerCase(Locale.ROOT);
        if (!(t.startsWith("http://") || t.startsWith("https://"))) {
            Diag.w("web", "refusing non-http(s) load: " + url);
            return;
        }
        prefs.edit().putString("webLastUrl", url).apply();
        if (startPage != null) startPage.setVisibility(View.GONE);
        addTab(url);
        web.loadUrl(url);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // v3.4.0 - THE LIGHT TAB MODEL
    //
    // Multiple live WebViews cost 60-120 MB EACH on Android 16 (a full Chromium
    // renderer process per view) and would OOM a 3 GB phone. So a "tab" here is a URL
    // in a list plus one shared renderer: switching a tab calls loadUrl(). The user gets
    // a visible strip and a switcher; the process keeps one renderer. Documented honestly
    // in the sheet as "Tabs (light)".
    // ══════════════════════════════════════════════════════════════════════════

    private void addTab(String url) {
        if (url == null) return;
        if (tabUrls.size() >= 5) {
            if (tabUrls.contains(url)) { activeTab = tabUrls.indexOf(url); }
            else { tabUrls.set(tabUrls.size() - 1, url); activeTab = tabUrls.size() - 1; }
        } else if (!tabUrls.contains(url)) {
            tabUrls.add(url);
            activeTab = tabUrls.size() - 1;
        } else {
            activeTab = tabUrls.indexOf(url);
        }
        rebuildTabs();
    }

    private void rebuildTabs() {
        if (tabStrip == null) return;
        tabStrip.removeAllViews();
        for (int i = 0; i < tabUrls.size(); i++) {
            final int idx = i;
            TextView t = new TextView(act);
            t.setText(hostLabel(tabUrls.get(i)));
            t.setTextSize(10.5f);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(dp(9), dp(5), dp(9), dp(5));
            GradientDrawable g = new GradientDrawable();
            boolean on = idx == activeTab;
            g.setColor(on ? 0xFF1A2440 : 0xFF0E1526);
            g.setCornerRadius(dp(9));
            t.setBackground(g);
            t.setTextColor(on ? 0xFFE2E8F0 : 0xFF94A3B8);
            t.setOnClickListener(v -> {
                activeTab = idx;
                if (startPage != null) startPage.setVisibility(View.GONE);
                web.loadUrl(tabUrls.get(idx));
                rebuildTabs();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.rightMargin = dp(6);
            tabStrip.addView(t, lp);
        }
        TextView plus = new TextView(act);
        plus.setText("+");
        plus.setTextSize(13f);
        plus.setTextColor(0xFF94A3B8);
        plus.setGravity(Gravity.CENTER);
        plus.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable pg = new GradientDrawable();
        pg.setColor(0xFF0E1526);
        pg.setCornerRadius(dp(9));
        plus.setBackground(pg);
        plus.setOnClickListener(v -> showStartPage());
        tabStrip.addView(plus, new LinearLayout.LayoutParams(-2, -2));
        UiFonts.applyTree(tabStrip, prefs);
    }

    private static String hostLabel(String url) {
        try {
            String h = android.net.Uri.parse(url).getHost();
            if (h == null) return url;
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (Throwable t) {
            return url;
        }
    }

    /** v3.4.0 - back to the speed dial. */
    void showStartPage() {
        if (startPage == null) return;
        rebuildStartPage();
        startPage.setVisibility(View.VISIBLE);
    }

    /** v3.4.0 - the visual speed dial (two groups, 2/3/4 columns, add + long-press edit). */
    // ── v3.4.2 — the keyboard fix + live suggestions ──────────────────────────

    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable suggestPending;

    /**
     * THE IME FIX (field bug: tapping the search bar brought up a keyboard that covered
     * the bar itself). Two things were missing and both are here now:
     * <ol>
     *   <li>{@code adjustResize} on MainActivity (AndroidManifest.xml),</li>
     *   <li>the REAL {@code WindowInsets.Type.ime()} bottom inset applied to the omnibar
     *       host, so the bar rides on top of the keyboard instead of under it.</li>
     * </ol>
     */
    private void applyImeInsets() {
        setOnApplyWindowInsetsListener((v, insets) -> {
            try {
                int ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
                int nav = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom;
                int lift = Math.max(0, ime - nav);
                if (omnibar != null) omnibar.setPadding(dp(6), dp(6), dp(6), dp(6) + lift);
                if (lift > 0 && urlBox != null && urlBox.hasFocus()) {
                    urlBox.requestRectangleOnScreen(new android.graphics.Rect(
                        0, 0, urlBox.getWidth(), urlBox.getHeight() + lift), false);
                }
            } catch (Throwable t) {
                Diag.w("web", "ime insets: " + t);
            }
            return insets;
        });
        requestApplyInsets();
    }

    /** Debounce 250 ms, then fetch the engine's own autocomplete JSON off the UI thread. */
    private void scheduleSuggestions(final String q) {
        if (suggestBox == null) return;
        if (suggestPending != null) ui.removeCallbacks(suggestPending);
        final String query = q == null ? "" : q.trim();
        if (query.length() < 2 || query.contains("://") || query.contains(".")) {
            suggestBox.setVisibility(View.GONE);
            suggestBox.removeAllViews();
            return;
        }
        suggestPending = () -> new Thread(() -> {
            final java.util.List<String> out = fetchSuggestions(query);
            ui.post(() -> paintSuggestions(query, out));
        }, "suggest").start();
        ui.postDelayed(suggestPending, 250);
    }

    /**
     * The autocomplete endpoint — HTTPS only, 4 s timeout, never throws. DuckDuckGo is the
     * default (no key, no account, no tracking cookie); Google's endpoint is used when the
     * user picked Google as the search engine in Settings.
     */
    private java.util.List<String> fetchSuggestions(String q) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            String enc = java.net.URLEncoder.encode(q, "UTF-8");
            String url = AppPrefs.SEARCH_GOOGLE.equals(AppPrefs.searchEngine(prefs))
                ? "https://suggestqueries.google.com/complete/search?client=firefox&q=" + enc
                : "https://duckduckgo.com/ac/?type=list&q=" + enc;
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.setRequestProperty("User-Agent", "AnantPlay-Player/3.4.2");
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            c.disconnect();
            Object parsed = MiniJson.parse(new String(bos.toByteArray(), "UTF-8"));
            // DDG: ["q",["a","b"]]  ·  Google: ["q",["a","b"]]
            java.util.List<Object> arr = MiniJson.arr(parsed);
            if (arr != null && arr.size() > 1) {
                java.util.List<Object> items = MiniJson.arr(arr.get(1));
                if (items != null) for (Object o : items) {
                    if (o instanceof String && out.size() < 6) out.add((String) o);
                    else if (o instanceof java.util.List && !((java.util.List<?>) o).isEmpty()
                        && ((java.util.List<?>) o).get(0) instanceof String && out.size() < 6)
                        out.add((String) ((java.util.List<?>) o).get(0));
                }
            }
        } catch (Throwable t) {
            Diag.w("web", "suggest: " + t);
        }
        return out;
    }

    /** Render the dropdown (only if the query is still the one the user is typing). */
    private void paintSuggestions(String query, java.util.List<String> items) {
        if (suggestBox == null || urlBox == null) return;
        String now = urlBox.getText() == null ? "" : urlBox.getText().toString().trim();
        if (!query.equals(now)) return;                       // a stale response is dropped
        suggestBox.removeAllViews();
        if (items == null || items.isEmpty()) { suggestBox.setVisibility(View.GONE); return; }
        for (final String s : items) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(9), dp(12), dp(9));
            row.setClickable(true);
            ImageView ic = new ImageView(act);
            ic.setImageDrawable(UiIcons.tinted(act, R.drawable.ic_search, 0xFF94A3B8, 15));
            ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
            row.addView(ic, new LinearLayout.LayoutParams(dp(22), dp(22)));
            TextView tv = new TextView(act);
            tv.setText(s);
            tv.setTextColor(0xFFE2E8F0);
            tv.setTextSize(13f);
            tv.setPadding(dp(10), 0, 0, 0);
            tv.setMaxLines(1);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));
            row.setOnClickListener(v -> {
                urlBox.setText(s);
                urlBox.setSelection(s.length());
                suggestBox.setVisibility(View.GONE);
                hideIme();
                go(s);
            });
            suggestBox.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        UiFonts.applyTree(suggestBox, prefs);
        suggestBox.setVisibility(View.VISIBLE);
    }

    /** Put the keyboard away without losing the page. */
    private void hideIme() {
        try {
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && urlBox != null) imm.hideSoftInputFromWindow(urlBox.getWindowToken(), 0);
            if (urlBox != null) urlBox.clearFocus();
        } catch (Throwable ignored) { }
    }

    private void rebuildStartPage() {
        if (startPage == null) return;   // v3.4.6.d — headless: Flutter renders the dial
        if (startPage == null) return;
        startPage.removeAllViews();
        ThemeEngine.Palette p = ThemeEngine.palette(AppPrefs.themeMode(prefs), 0,
            AppPrefs.accent(prefs), AppPrefs.accentHex(prefs), AppPrefs.cardRadius(prefs));
        View dial = SpeedDial.build(act, p, prefs, AppPrefs.webLinks(prefs),
            new SpeedDial.OnOpen() {
                @Override public void open(String url) { load(url); }
                @Override public void add() { editLinks(); }
                @Override public void edit(AppPrefs.Link link, int index) { linkSheet(link, index); }
            });
        startPage.addView(dial, new FrameLayout.LayoutParams(-1, -1));
    }

    /** The edit sheet for one speed-dial tile (move up / move down / delete). */
    private void linkSheet(final AppPrefs.Link link, final int index) {
        ThemeEngine.Palette p = ThemeEngine.palette(AppPrefs.themeMode(prefs), 0,
            AppPrefs.accent(prefs), AppPrefs.accentHex(prefs), AppPrefs.cardRadius(prefs));
        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(UiSheet.header(act, p, link.name, link.url),
            new LinearLayout.LayoutParams(-1, -2));
        body.addView(UiChip.make(act, p, prefs, R.drawable.ic_plus,
            act.getString(R.string.web_hint).isEmpty() ? "Open" : "Open", true,
            v -> { load(link.url); }));
        LinearLayout row = UiChip.stripRow(act);
        row.addView(UiChip.make(act, p, prefs, R.drawable.ic_back, "Move up", false,
            v -> moveLink(index, -1)));
        row.addView(UiChip.make(act, p, prefs, R.drawable.ic_forward, "Move down", false,
            v -> moveLink(index, 1)));
        row.addView(UiChip.make(act, p, prefs, R.drawable.ic_close, "Delete", false,
            v -> deleteLink(index)));
        body.addView(row, new LinearLayout.LayoutParams(-1, -2));
        UiSheet.show(act, p, prefs, body, 42, null);
    }

    private void moveLink(int index, int dir) {
        List<AppPrefs.Link> links = AppPrefs.webLinks(prefs);
        int to = index + dir;
        if (index < 0 || index >= links.size() || to < 0 || to >= links.size()) return;
        AppPrefs.Link a = links.get(index), b = links.get(to);
        links.set(index, b);
        links.set(to, a);
        prefs.edit().putString(AppPrefs.K_WEB_LINKS, AppPrefs.serializeLinks(links)).apply();
        rebuildStartPage();
    }

    private void deleteLink(int index) {
        List<AppPrefs.Link> links = AppPrefs.webLinks(prefs);
        if (index < 0 || index >= links.size()) return;
        links.remove(index);
        prefs.edit().putString(AppPrefs.K_WEB_LINKS, AppPrefs.serializeLinks(links)).apply();
        rebuildStartPage();
        toast("removed");
    }

    /** Opens the Customize → Browser page where the link list is edited. */
    private void editLinks() {
        toast("Add sites in Customize → Browser → Custom links");
    }

    void reload() { try { web.reload(); } catch (Throwable ignored) { } }

    /** Back handling: TRUE when the browser consumed it (MainActivity asks). */
    public boolean goBack() {
        try {
            if (web.canGoBack()) { web.goBack(); return true; }
        } catch (Throwable ignored) { }
        return false;
    }

    public void forward() { try { if (web.canGoForward()) web.goForward(); } catch (Throwable ignored) { } }

    // ── the ⋮ menu ─────────────────────────────────────────────────────────────

    /**
     * v3.4.0 - THE OVERFLOW SHEET (was a PopupMenu anchored to the address box, which
     * covered the omnibar itself on a small screen). Every item is a vector row.
     */
    private void menu() {
        ThemeEngine.Palette p = ThemeEngine.palette(AppPrefs.themeMode(prefs), 0,
            AppPrefs.accent(prefs), AppPrefs.accentHex(prefs), AppPrefs.cardRadius(prefs));
        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(UiSheet.header(act, p, "Browser",
            hostLabel(web.getUrl() == null ? "" : web.getUrl())), new LinearLayout.LayoutParams(-1, -2));

        LinearLayout g = UiCard.make(act, p, prefs);
        UiGroup.row(act, g, p, prefs, R.drawable.ic_desktop, "Desktop mode",
            AppPrefs.webDesktopUa(prefs) ? "On" : "Off", null, false, v -> {
                prefs.edit().putBoolean(AppPrefs.K_WEB_DESKTOP_UA, !AppPrefs.webDesktopUa(prefs)).apply();
                web.getSettings().setUserAgentString(effectiveUa());
                toast(AppPrefs.webDesktopUa(prefs) ? "Desktop mode ON" : "Mobile mode ON");
                reload();
            });
        UiGroup.divider(act, g, p, prefs);
        UiGroup.row(act, g, p, prefs, R.drawable.ic_bookmark, "Bookmark this page", null, null, false,
            v -> addBookmark(web.getUrl(), web.getTitle()));
        UiGroup.divider(act, g, p, prefs);
        UiGroup.row(act, g, p, prefs, R.drawable.ic_history, "Bookmarks", null, null, true,
            v -> showBookmarks());
        UiGroup.divider(act, g, p, prefs);
        UiGroup.row(act, g, p, prefs, R.drawable.ic_copy, "Paste a link", null, null, false,
            v -> pasteFromClipboard());
        UiGroup.divider(act, g, p, prefs);
        UiGroup.row(act, g, p, prefs, R.drawable.ic_search, "Find on page", null, null, false,
            v -> findOnPage());
        UiGroup.divider(act, g, p, prefs);
        UiGroup.row(act, g, p, prefs, R.drawable.ic_nav_home, "Browser home", null, null, false,
            v -> showStartPage());
        UiGroup.divider(act, g, p, prefs);
        UiGroup.row(act, g, p, prefs, R.drawable.ic_close, "Clear cache & cookies",
            null, null, false, v -> clearWebData());
        // v3.4.3 — the ONLY way mixed content ever comes back: an explicit, per-session
        // opt-in from the user for a legacy site. It is never persisted.
        UiGroup.divider(act, g, p, prefs);
        UiGroup.row(act, g, p, prefs, R.drawable.ic_alert,
            "Allow mixed content (this session only)",
            mixedContentCompat ? "On" : "Off — blocked", null, false, v -> {
                setMixedContentCompatibility(!mixedContentCompat);
                toast(mixedContentCompat
                    ? "Mixed content allowed for this session"
                    : "Mixed content blocked again");
                reload();
            });
        body.addView(g, new LinearLayout.LayoutParams(-1, -2));
        UiSheet.show(act, p, prefs, body, 62, null);
    }

    /** A simple find-on-page prompt that injects the browser's own find highlight. */
    private void findOnPage() {
        final EditText in = new EditText(act);
        in.setHint("Find text on this page");
        in.setTextSize(14f);
        in.setSingleLine(true);
        new android.app.AlertDialog.Builder(act)
            .setTitle("Find on page")
            .setView(in)
            .setPositiveButton("Find", (d, w) -> {
                String q = in.getText().toString();
                if (q.isEmpty()) return;
                try {
                    web.findAllAsync(q);
                } catch (Throwable t) {
                    Diag.w("web", "find: " + t);
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Clear the WebView's own storage - never the app's settings. */
    private void clearWebData() {
        try {
            android.webkit.CookieManager.getInstance().removeAllCookies(null);
            android.webkit.WebStorage.getInstance().deleteAllData();
            web.clearCache(true);
            web.clearHistory();
            toast("cache & cookies cleared");
        } catch (Throwable t) {
            Diag.w("web", "clear: " + t);
        }
    }

    private void pasteFromClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) {
                toast(Chrome.pick("Clipboard empty · clipboard හිස්")); return;
            }
            CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(act);
            String s = t == null ? "" : t.toString().trim();
            if (s.startsWith("http://") || s.startsWith("https://")) load(s);
            else toast(Chrome.pick("No URL on the clipboard · clipboard එකේ URL එකක් නැත"));
        } catch (Throwable t) {
            Diag.w("web", "clipboard: " + t);
        }
    }

    private void addBookmark(String url, String title) {
        if (url == null || url.isEmpty()) return;
        String name = title == null || title.trim().isEmpty() ? StreamSniffer.hostOf(url) : title.trim();
        String list = prefs.getString("webBookmarks", "");
        if (list.contains("\n" + url + "|") || list.startsWith(url + "|")) { toast("already bookmarked"); return; }
        list = (list.isEmpty() ? "" : list + "\n") + url + "|" + name.replace('\n', ' ');
        prefs.edit().putString("webBookmarks", list).apply();
        toast("Saved: " + name);
    }

    private void showBookmarks() {
        String list = prefs.getString("webBookmarks", "");
        if (list.isEmpty()) {
            toast(Chrome.pick("No bookmarks yet · bookmarks නැත (⋮ → Add bookmark)")); return;
        }
        final String[] entries = list.split("\n");
        String[] labels = new String[entries.length];
        for (int i = 0; i < entries.length; i++) {
            int bar2 = entries[i].indexOf('|');
            labels[i] = (bar2 < 0 ? entries[i] : entries[i].substring(bar2 + 1))
                + "\n" + (bar2 < 0 ? "" : entries[i].substring(0, bar2));
        }
        new android.app.AlertDialog.Builder(act)
            .setTitle("Bookmarks — tap to open, long-press to remove")
            .setItems(labels, (d, which) -> load(entries[which].substring(0, Math.max(0, entries[which].indexOf('|')))))
            .setNeutralButton("Clear all", (d, w) -> prefs.edit().putString("webBookmarks", "").apply())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ── the sniffer bridge (network + JS hits land here) ───────────────────────

    private void report(StreamSniffer.Hit hit) {
        if (hit == null || hit.url == null) return;
        synchronized (hits) {
            if (hits.containsKey(hit.url)) return;   // one report per URL
            hits.put(hit.url, hit);
        }
        String pageTitle = "";
        try { pageTitle = web.getTitle() == null ? "" : web.getTitle(); } catch (Throwable ignored) { }
        hit.pageTitle = StreamSniffer.cleanTitle(pageTitle);
        Diag.i("web", "stream sniffed: [" + hit.kind + "] " + StreamSniffer.hostOf(hit.url)
            + (hit.pageTitle.isEmpty() ? "" : " — \"" + hit.pageTitle + "\""));
        act.runOnUiThread(() -> {
            // v3.4.6.d — HEADLESS: the hit flows to the Flutter capture dock
            // through the shell route pump (t='sniff') instead of the Java pill.
            if (headless) {
                try {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("t", "sniff");
                    m.put("url", hit.url);
                    m.put("kind", hit.kind == null ? "STREAM" : hit.kind);
                    m.put("title", hit.pageTitle == null ? "" : hit.pageTitle);
                    Map<String, String> h = new LinkedHashMap<String, String>();
                    if (hit.referer != null && !hit.referer.isEmpty()) h.put("Referer", hit.referer);
                    if (hit.origin != null && !hit.origin.isEmpty()) h.put("Origin", hit.origin);
                    if (hit.cookie != null && !hit.cookie.isEmpty()) h.put("Cookie", hit.cookie);
                    if (hit.ua != null && !hit.ua.isEmpty()) h.put("User-Agent", hit.ua);
                    m.put("headers", MiniJson.write(new LinkedHashMap<String, Object>(h)));
                    MainActivity.shellRoute(act, "sniff", hit.url,
                        hit.pageTitle == null ? "" : hit.pageTitle,
                        MiniJson.write(new LinkedHashMap<String, Object>(h)), false, 0L);
                } catch (Throwable e) {
                    Diag.w("web", "sniff push: " + e);
                }
                maybeAutoOpen(hit);
                return;
            }
            // v3.3.1 — read the size through the synchronized accessor: `hits` is a
            // plain LinkedHashMap written by the sniffer thread, so touching it
            // directly from the UI thread was an unsynchronised read of live state.
            if (pill != null && pillRow != null) {
                pill.setText(hit.pillLabel(hitCount()));
                pillRow.setVisibility(View.VISIBLE);
            }
            maybeAutoOpen(hit);
        });
    }

    /** The user-controlled ⚡Auto-Open: a NEW stream opens the player by itself
     *  (1.5 s debounce so a burst of segments/pickers reports only one play). */
    private void maybeAutoOpen(StreamSniffer.Hit hit) {
        if (!prefs.getBoolean("autoOpenSniffed", false)) return;
        long now = System.currentTimeMillis();
        if (hit.url.equals(lastAutoOpened) && now - lastAutoOpenAt < 30000) return;
        if (now - lastAutoOpenAt < 1500) return;
        lastAutoOpened = hit.url;
        lastAutoOpenAt = now;
        play(hit);
    }

    private void playLatest() {
        StreamSniffer.Hit newest = null;
        synchronized (hits) { for (StreamSniffer.Hit h : hits.values()) newest = h; }
        if (newest != null) play(newest);
    }

    /** Open OUR Media3 player with the SAME url + headers the site served. */
    private void play(StreamSniffer.Hit hit) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (hit.referer != null && !hit.referer.isEmpty()) headers.put("Referer", hit.referer);
        if (hit.origin != null && !hit.origin.isEmpty()) headers.put("Origin", hit.origin);
        if (hit.cookie != null && !hit.cookie.isEmpty()) headers.put("Cookie", hit.cookie);
        if (hit.ua != null && !hit.ua.isEmpty()) headers.put("User-Agent", hit.ua);
        // v3.4.6.d — ONE UNIFIED PLAYER: every sniffed stream opens the
        // in-shell MORNYE CINEMA PLAYER through the shell route pump (was:
        // the raw Java PlayActivity — the v3.4.6.c "two video players"
        // complaint).
        String title = hit.pageTitle == null || hit.pageTitle.isEmpty()
            ? StreamSniffer.hostOf(hit.url) : hit.pageTitle;
        String headersJson = headers.isEmpty() ? "" : MiniJson.write(new LinkedHashMap<String, Object>(headers));
        try {
            PlayActivity.trampolineToShell(act, hit.url, title, headersJson, false);
            Diag.i("web", "opened in the Mornye Cinema Player: [" + hit.kind + "] " + hit.url);
        } catch (Throwable t) {
            Diag.w("web", "player launch: " + t);
            toast(Chrome.pick("Could not open the player · player එක විවෘත කළ නොහැක"));
        }
    }

    /** The JS-side media hook target (window.SniJS.onVideo(src)). */
    private class JsBridge {
        @JavascriptInterface
        public void onVideo(final String src) {
            if (src == null || src.isEmpty()) return;
            // blob:/data: sources cannot be replayed by Media3 — the network-layer
            // sniffer is the one that catches those sites' real http(s) streams.
            String s = src.trim();
            if (!(s.startsWith("http://") || s.startsWith("https://"))) return;
            if (!StreamSniffer.shouldReport(s, null, -1)) return;
            String kind = StreamSniffer.classify(s, null);
            report(new StreamSniffer.Hit().url(s).kind(kind == null ? "VIDEO" : kind));
        }

        /**
         * v3.4.1 — Layer 5: a client-side Blob subtitle (tools.newzone.top, any site).
         *
         * <p>v3.4.3 — AUDITED (Replit QA-0011): every {@code @JavascriptInterface} method
         * is reachable from EVERY frame of EVERY page, so neither this one nor
         * {@link #onVideo} trusts its caller. The reported page URL is verified against
         * the WebView's real top-document URL inside {@link #onBlobDownload}, and the
         * payload only reaches a decoder when the user interacted recently.
         */
        @JavascriptInterface
        public void onBlobDownload(String base64, String name, String pageUrl) {
            WebBrowserView.this.onBlobDownload(base64, name, pageUrl);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // v3.4.1 — LAYER 5: THE CHROMIUM BLOB SUBTITLE BRIDGE (defect #4.4)
    //
    // Android 16's WebView IS Chromium, and the most useful Sinhala workflow on the
    // web ends in a CLIENT-SIDE Blob: tools.newzone.top/en/subtitle-translator
    // translates an .srt in the page and hands you the result through
    // URL.createObjectURL(new Blob([...])). A blob: URL cannot be downloaded by the
    // DownloadListener (it has no network address) and Media3 cannot play it, so in
    // v3.4.0 that file simply vanished. This shim closes the loop:
    //   · URL.createObjectURL is wrapped, so any Blob that looks like a subtitle is
    //     read with FileReader and posted to the Java bridge as base64;
    //   · an <a download href="blob:…"> click is caught in the capture phase and the
    //     blob is fetched in-page (only the page can read its own blob:) and posted;
    //   · Java caps the payload at 5 MB, base64-decodes it and attaches it to the
    //     movie that is playing RIGHT NOW — one tap, no file manager.
    // ══════════════════════════════════════════════════════════════════════════

    /** The largest Blob we will carry across the bridge (matches SrtLoader's cap). */
    private static final int BLOB_CAP = BridgeGuard.BLOB_CAP;

    /** v3.4.3 — the per-session mixed-content opt-in (default: never allow). */
    private volatile boolean mixedContentCompat;
    /** v3.4.3 — the last user tap inside the page (opens the capture window). */
    private volatile long captureGestureAt;
    /** v3.4.3 — the last accepted blob (single-flight rate limit). */
    private volatile long lastBlobAtMs;
    /** v3.4.3 — true while a blob decode is running (one at a time, ever). */
    private volatile boolean blobInFlight;

    /** Install the Blob hook (idempotent: the page flag survives re-injection). */
    private void injectBlobHook(WebView v) {
        try {
            v.evaluateJavascript(BLOB_HOOK_JS, null);
        } catch (Throwable t) {
            Diag.w("web", "blob hook: " + t);
        }
    }

    /** The shim: wrap createObjectURL, catch <a download>, fetch the blob in-page. */
    private static final String BLOB_HOOK_JS =
        "(function(){if(window.__siBlobHook)return;window.__siBlobHook=1;" +
        "if(window.top!==window)return;" +            // v3.4.3 — TOP DOCUMENT ONLY (no iframes)
        "if(!/^https?:/i.test(String(location.href)))return;" +
        "var grab=function(u,n){try{fetch(u).then(function(r){return r.blob()})" +
        ".then(function(b){if(!b||b.size>" + BLOB_CAP + ")return;" +
        "var fr=new FileReader();fr.onload=function(){try{var s=String(fr.result||'');" +
        "var i=s.indexOf(',');if(i>0&&window.SniJS)SniJS.onBlobDownload(s.substring(i+1),n||'subtitle.srt',String(location.href));}catch(e){}};" +
        "fr.readAsDataURL(b);}).catch(function(){});}catch(e){}};" +
        "var orig=URL.createObjectURL?URL.createObjectURL.bind(URL):null;" +
        "if(orig){URL.createObjectURL=function(b){try{" +
        "var n=(b&&b.name)||'';var t=(b&&b.type)||'';" +
        "if(b&&b.size&&b.size<=" + BLOB_CAP + "&&/(srt|vtt|zip|sub|ass|ssa)/i.test(t+' '+n)){grab(orig(b),n||'subtitle.srt');}" +
        "}catch(e){}return orig(b);};}" +
        "document.addEventListener('click',function(ev){try{" +
        "var a=ev.target&&ev.target.closest?ev.target.closest('a[download]'):null;if(!a)return;" +
        "var h=a.getAttribute('href')||'';if(h.indexOf('blob:')!==0)return;" +
        "grab(h,a.getAttribute('download')||'subtitle.srt');}catch(e){}},true);})()";

    /** Called from JS on a worker thread: a base64 subtitle Blob, straight to the player.
     *  Reached through {@link JsBridge#onBlobDownload} — SniJS is the ONE interface
     *  registered with the WebView, so the shim's `SniJS.onBlobDownload(...)` resolves. */
    void onBlobDownload(final String base64, final String name, final String pageUrl) {
        final long now = android.os.SystemClock.uptimeMillis();
        String current = null;
        try { current = web == null ? null : web.getUrl(); } catch (Throwable ignored) { }
        // ══ v3.4.3 — THE HARDENED BRIDGE GATE (Replit QA-0011) ══════════════════
        // Every rule lives in the pure, lab-proven BridgeGuard: single flight, the
        // TOP-DOCUMENT origin match, the user-gesture capture window, and the 5 MB
        // PRE-decode cap. Nothing is allocated until all four pass.
        final String refuse = BridgeGuard.refuseReason(current, pageUrl, base64,
            now, captureGestureAt, lastBlobAtMs, blobInFlight);
        if (!refuse.isEmpty()) {
            Diag.w("web", "blob refused: " + refuse);
            Troubleshooter.record("WEB", "blob bridge refused — " + refuse);
            return;
        }
        blobInFlight = true;
        lastBlobAtMs = now;
        new Thread(() -> {
            byte[] bytes = null;
            try {
                bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            } catch (Throwable t) {
                Diag.w("web", "blob decode: " + t);
            } finally {
                blobInFlight = false;               // single-flight ends with the decode
            }
            if (bytes == null || bytes.length == 0) return;
            if (bytes.length > BLOB_CAP) {          // belt and braces after the estimate
                Diag.w("web", "blob refused: decoded " + bytes.length + " B > cap");
                return;
            }
            final byte[] b = bytes;
            final String nm = name == null || name.isEmpty() ? "blob-subtitle.srt" : name;
            Diag.i("web", "blob subtitle captured (" + b.length + " B, " + nm + ")");
            act.runOnUiThread(() -> attachBytes(b, nm));
        }, "blob-sub").start();
    }

    /**
     * v3.4.3 — THE PER-SESSION MIXED-CONTENT OPT-IN (Replit QA-0008).
     *
     * <p>Off by default and never persisted: a legacy site can be given
     * {@code MIXED_CONTENT_COMPATIBILITY_MODE} for THIS browser session only, from the
     * ⋮ sheet. Leaving the tab (or the app) drops it back to NEVER_ALLOW.
     */
    void setMixedContentCompatibility(boolean on) {
        mixedContentCompat = on;
        try {
            if (Build.VERSION.SDK_INT >= 21 && web != null)
                web.getSettings().setMixedContentMode(on
                    ? WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    : WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        } catch (Throwable t) { Diag.w("web", "mixed content: " + t); }
        Diag.i("web", "mixed content compatibility = " + on + " (this session only)");
        Troubleshooter.record("WEB", "mixed content compatibility " + (on ? "ON" : "OFF")
            + " for this session");
    }

    /** True while this session allows mixed content (the ⋮ row shows it). */
    boolean mixedContentCompatibility() { return mixedContentCompat; }

    /** Attach already-in-memory subtitle bytes to the live player (shared with the
     *  Blob bridge; the download path reuses the same tail). */
    private void attachBytes(byte[] bytes, String name) {
        PlayActivity p = PlayActivity.current();
        if (p != null) {
            p.attachCapturedSubs(bytes, name);
            toast("subtitle attached · " + bytes.length + " B");
            return;
        }
        try {
            java.io.File f = new java.io.File(act.getCacheDir(), "captured-sub.bin");
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(bytes); }
            toast("saved — open a movie, then Subtitles → Local .srt");
        } catch (Throwable t) {
            toast("could not save the subtitle: " + t.getMessage());
        }
    }

    // ── lifecycle ──────────────────────────────────────────────────────────────

    public void onPauseWeb() { try { web.onPause(); } catch (Throwable ignored) { } }
    public void onResumeWeb() { try { web.onResume(); rebuildStartPage(); } catch (Throwable ignored) { } }
    // (rebuildStartPage is a null-guarded no-op in headless mode)

    /** v3.2.0 — rebuild the quick-links chip row from the CURRENT prefs list
     *  (so an edit in Settings lands the next time the Web tab is shown). */
    private void rebuildLinks() {
        if (linksRow == null) return;
        linksRow.removeAllViews();
        List<AppPrefs.Link> links = AppPrefs.webLinks(prefs);
        if (links.isEmpty()) {
            TextView hint = new TextView(act);
            hint.setText(act.getString(R.string.links_hint));
            hint.setTextColor(0xFF94A3B8);
            hint.setTextSize(11.5f);
            hint.setPadding(dp(8), dp(2), dp(8), dp(2));
            linksRow.addView(hint, new LinearLayout.LayoutParams(-2, -2));
            return;
        }
        for (final AppPrefs.Link l : links) {
            TextView chip = new TextView(act);
            chip.setText(l.name);
            UiIcons.setCompound(chip, R.drawable.ic_nav_web, 0xFFE2E8F0, 12, Gravity.START);
            chip.setTextColor(0xFFE2E8F0);
            chip.setTextSize(12f);
            chip.setGravity(Gravity.CENTER);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xFF101A30);
            g.setCornerRadius(dp(14));
            chip.setBackground(g);
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(6);
            chip.setOnClickListener(v -> load(l.url));
            linksRow.addView(chip, lp);
        }
    }

    public void destroy() {
        try {
            web.stopLoading();
            web.loadUrl("about:blank");
            web.removeAllViews();
            web.destroy();
        } catch (Throwable ignored) { }
    }

    public WebView webView() { return web; }
    public int hitCount() { synchronized (hits) { return hits.size(); } }

    private void toast(String s) { Toast.makeText(act, s, Toast.LENGTH_SHORT).show(); }

    // ═══════════════════════════════════════════════════════════════════════════
    // v3.3.0 — THE SUBTITLE CAPTURE PIPELINE (WebView → player, zero file hunting)
    // ═══════════════════════════════════════════════════════════════════════════

    /** True while a capture is in flight (one at a time — never a download storm). */
    private volatile boolean capturing;

    /**
     * Download a captured .srt/.zip and attach it to the live player.
     * Everything happens off the UI thread; SrtLoader enforces the 5 MB cap, the
     * 64-entry cap and the ".." traversal refusal, so a hostile archive cannot
     * escape the cache dir.
     */
    private void captureSubtitle(final String url) {
        if (capturing) { toast("a subtitle is already downloading…"); return; }
        capturing = true;
        toast(LkSubFinder.captureLabel(url));
        new Thread(() -> {
            byte[] bytes = null;
            String why = null;
            try {
                bytes = httpBytes(url);
            } catch (Throwable t) {
                why = t.getMessage();
            }
            final byte[] b = bytes;
            final String err = why;
            act.runOnUiThread(() -> {
                capturing = false;
                if (b == null) {
                    toast("subtitle download failed: " + err);
                    return;
                }
                PlayActivity p = PlayActivity.current();
                if (p != null) {
                    p.attachCapturedSubs(b, url);
                    Diag.i("web", "subtitle attached live (" + b.length + " B)");
                } else {
                    try {
                        java.io.File f = new java.io.File(act.getCacheDir(), "captured-sub.bin");
                        try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(b); }
                        toast("saved — open a movie, then Subtitles → Local .srt");
                    } catch (Throwable t) {
                        toast("could not save the subtitle: " + t.getMessage());
                    }
                }
            });
        }, "sub-capture").start();
    }

    /** A capped HTTP GET that carries the WebView's own cookie for that host. */
    private byte[] httpBytes(String url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection(java.net.Proxy.NO_PROXY);
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        String ck = android.webkit.CookieManager.getInstance().getCookie(url);
        if (ck != null && !ck.isEmpty()) c.setRequestProperty("Cookie", ck);
        c.setRequestProperty("User-Agent", LkSubFinder.isZip(url)
            ? "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            : "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36");
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new Exception("HTTP " + code);
            try (java.io.InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int total = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    total += n;
                    if (total > 6 * 1024 * 1024) throw new Exception("file too large (6 MB max)");
                    o.write(buf, 0, n);
                }
                return o.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
