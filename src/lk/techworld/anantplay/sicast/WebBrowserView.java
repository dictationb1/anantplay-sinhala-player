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

    // ── v3.4.6.i — THE THEME-AWARE BROWSER BACKGROUND (the blank/dark-panel
    //    half of the video_4 01:31 fix) ─────────────────────────────────────────

    /**
     * True when the browser should paint LIGHT (#FFFFFF) rather than the dark
     * navy (#0F172A): an explicit Light themeMode wins, an explicit Dark/AMOLED
     * loses, and `system` resolves against the live configuration's night
     * mode (never a cached guess). The v3.4.6.h build painted the container
     * and the WebView itself dark-navy in BOTH modes — a light-mode user opened
     * a pitch-dark blank panel before the first page painted.
     */
    private boolean lightTheme() {
        String mode = AppPrefs.themeMode(prefs);
        if (AppPrefs.THEME_LIGHT.equals(mode)) return true;
        if (AppPrefs.THEME_DARK.equals(mode) || AppPrefs.THEME_AMOLED.equals(mode)) return false;
        try {
            int night = act.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return night != android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    /** #FFFFFF in Light Mode, #0F172A in Dark/AMOLED — the container AND WebView. */
    private int themedBackground() { return lightTheme() ? 0xFFFFFFFF : 0xFF0F172A; }

    /**
     * v3.4.6.i — THE BOOTSTRAP CONTRACT (probe-audited): the URL a headless
     * view boots to. `last` when there is one, otherwise the engine home —
     * a first EVER Browser tab open can never sit as a blank panel.
     */
    static String bootstrapUrl(String last) {
        return last != null && !last.isEmpty() ? last : HOME_URL;
    }

    /**
     * v3.4.6.j — THE LIVE ENGINE HOME (the search-engine switch): picking
     * google / duckduckgo / startpage / brave in Settings → Browser flips the
     * Browser home URL IMMEDIATELY — the next Home tap (and every fresh
     * bootstrap) opens the chosen engine's own page, never a stale
     * google.com while the setting says otherwise.
     */
    static String homeUrlFor(android.content.SharedPreferences prefs) {
        String engine = AppPrefs.searchEngine(prefs);
        if (AppPrefs.SEARCH_DDG.equals(engine)) return "https://duckduckgo.com/";
        if (AppPrefs.SEARCH_STARTPAGE.equals(engine)) return "https://www.startpage.com/";
        if (AppPrefs.SEARCH_BRAVE.equals(engine)) return "https://search.brave.com/";
        return HOME_URL;
    }

    /**
     * v3.4.6.j — LIVE SEARCH-ENGINE SYNC: Settings → Browser home is not a
     * stale snapshot. Called on the main thread the instant `searchEngine`
     * is written; the WebView navigates to the newly chosen engine's own
     * page and that URL becomes the persisted last/home page.
     */
    public void applySearchEngineHome() {
        final String home = homeUrlFor(prefs);
        load(home);
    }

    /**
     * v3.4.6.i — re-apply the themed background + the user-agent + the page
     * color-scheme hint (called from the Browser tab on build/theme changes;
     * idempotent). The Flutter side triggers it through the
     * `anantplay/browser` `applyTheme` handler.
     */
    public void applyTheme() {
        try {
            int bg = themedBackground();
            setBackgroundColor(bg);
            column.setBackgroundColor(bg);
            if (web != null) {
                web.setBackgroundColor(bg);
                web.getSettings().setUserAgentString(effectiveUa());
                if (!lightTheme()) {
                    web.evaluateJavascript(
                        "try{document.documentElement.style.colorScheme='dark'}catch(e){}", null);
                } else {
                    web.evaluateJavascript(
                        "try{document.documentElement.style.colorScheme='light'}catch(e){}", null);
                }
            }
        } catch (Throwable t) {
            Diag.w("web", "applyTheme: " + t);
        }
    }

    // ══ v3.4.6.k — THE YOUTUBE RENDERER-CRASH SHIELD + AD-FREE LANE (LAW #3) ══

    /** The cosmetic YouTube ad-skip/mute page script (injected when Ad block
     *  is ON — even the in-WebView preview stays clean; the Ad-Free Cinema
     *  lane remains the fully ad-free playback path). */
    static final String YT_AD_SKIP_JS =
        "(function(){function tick(){try{var b=document.querySelector('.ytp-ad-skip-button,"
        + ".ytp-ad-skip-button-modern,.ytp-skip-ad-button,[class*=\"skip-button\"]');if(b){b.click();}"
        + "var a=document.querySelector('.ad-showing,.ytp-ad-player-overlay,[id*=\"ad-container\"]');"
        + "if(a){a.style.display='none';}var v=document.querySelector('video');"
        + "if(v&&v.duration&&v.duration<=120&&document.querySelector('.ad-showing')){v.muted=true;}}catch(e){}}"
        + "setInterval(tick,350);tick();})();";

    /**
     * v3.4.6.m — THE FLOATING ▶ PLAY-BUTTON OBSERVER (Part_01 00:45–01:44,
     * Part_02 04:17–05:12). A lightweight page script that finds the FIRST
     * sizeable visible <video> (any site — YouTube, Shorts, any HTML5 player),
     * reads its CSS-px rect, and reports {src, rect, viewport} through the
     * ONE registered bridge (window.SniJS.onVideoRect). Throttled by the
     * key-change dedupe + the Java-side 300 ms rail limit; posts an empty
     * rect when the last video disappears (scroll / navigation / fullscreen).
     * It never touches playback, never blocks a tap — it only OBSERVES.
     */
    static final String VIDEO_RECT_HOOK_JS =
        "(function(){if(window.__siVideoRect)return;window.__siVideoRect=1;"
        + "if(window.top!==window)return;"
        + "var last='';"
        + "function post(s,l,t,w,h){try{if(!isFinite(l)||!isFinite(t)||!isFinite(w)||!isFinite(h))return;"
        + "if(window.SniJS)SniJS.onVideoRect(String(s),l,t,w,h,"
        + "((document.documentElement&&document.documentElement.clientWidth)||window.innerWidth||0));}catch(e){}}"
        + "function pick(){try{"
        + "var vs=document.querySelectorAll('video');var best=null;var br=null;"
        + "for(var i=0;i<vs.length;i++){var v=vs[i];var r=v.getBoundingClientRect();"
        + "if(r.width>=120&&r.height>=80&&(!best||r.width*r.height>br.width*br.height)){best=v;br=r;}}"
        + "if(best){var s=best.currentSrc||best.src||'';"
        + "var k=Math.round(br.left)+','+Math.round(br.top)+','+Math.round(br.width)+','+Math.round(br.height)+','+String(s);"
        + "if(k!==last){last=k;post(s,Math.round(br.left),Math.round(br.top),Math.round(br.width),Math.round(br.height));}return;}"
        + "if(last){last='';post('',0,0,0,0);}}catch(e){}}"
        + "document.addEventListener('play',function(){pick()},true);"
        + "document.addEventListener('loadedmetadata',function(){pick()},true);"
        + "window.addEventListener('scroll',function(){last='';pick()},true);"
        + "window.addEventListener('resize',function(){last='';pick()},true);"
        + "setInterval(pick,900);pick();})();";

    /**
     * v3.4.6.k — THE YOUTUBE RENDERER-CRASH SHIELD (the Part_05 00:20 hard
     * crash kill). Android 12+ runs the WebView in a sandboxed RENDERER
     * process; a heavy YouTube page can OOM-kill it, and the DEFAULT
     * behavior of an unhandled {@code onRenderProcessGone} is to KILL THE
     * WHOLE APP PROCESS — the exact straight-to-Android-Home crash the audit
     * caught. This override RETURNS TRUE (the app survives), tears the dead
     * WebView down, rebuilds a fresh hardened one in its place and reloads
     * the last page: the user sees a short reload instead of a dead app.
     */
    static boolean handleRenderProcessGone(WebBrowserView owner) {
        Diag.w("web", "renderer gone — shielding the app, rebuilding the WebView");
        Troubleshooter.record("BROWSER", "WebView renderer died — app survived, WebView rebuilt");
        try {
            owner.rebuildAfterRendererDeath();
        } catch (Throwable t) {
            Diag.w("web", "renderer rebuild: " + t);
        }
        return true;   // the app process stays alive — never the default kill
    }

    /** Tear down + rebuild the WebView after its renderer died (main thread). */
    void rebuildAfterRendererDeath() {
        act.runOnUiThread(() -> {
            try {
                // the fullscreen custom view belongs to the DEAD renderer
                if (fullscreenView != null) {
                    try {
                        ((android.view.ViewGroup) act.getWindow().getDecorView())
                            .removeView(fullscreenView);
                    } catch (Throwable ignored) { }
                    fullscreenView = null;
                    fullscreenCb = null;
                    act.getWindow().getDecorView().setSystemUiVisibility(0);
                }
                String last = null;
                try { last = web == null ? null : web.getUrl(); } catch (Throwable ignored) { }
                if (web != null) {
                    try { ((android.view.ViewGroup) web.getParent()).removeView(web); } catch (Throwable ignored) { }
                    try { web.destroy(); } catch (Throwable ignored) { }
                    web = null;
                }
                buildUi();
                if (last != null && !last.isEmpty() && web != null) {
                    web.loadUrl(last);
                    Diag.i("web", "renderer rebuild: reloaded " + StreamSniffer.hostOf(last));
                }
            } catch (Throwable t) {
                Diag.w("web", "rebuild: " + t);
            }
        });
    }

    /**
     * v3.4.6.k — THE ZERO-AD YOUTUBE EXTRACTION LANE: on ANY watch / shorts /
     * youtu.be URL, resolve the ad-free direct stream + captions +
     * SponsorBlock segments off-thread and push a t='yt' shell route (the
     * Browser dock lights up `▶ Play Ad-Free in Cinema`). Single-flight per
     * video id; a navigation away cancels the previous extraction's push.
     */
    private void maybeExtractYouTube(final String url) {
        final String vid = YouTubeExtractor.videoIdOf(url);
        if (vid == null) return;
        final String lastStarted = String.valueOf(url);
        new Thread(() -> {
            try {
                YouTubeExtractor.Result r = YouTubeExtractor.extract(vid);
                if (!r.ok()) {
                    Diag.w("yt", "no ad-free muxed format for " + vid);
                    return;
                }
                java.util.List<YouTubeExtractor.Segment> segs = YouTubeExtractor.skipSegments(vid);
                String payload = YouTubeExtractor.shellPayload(r, segs);
                Diag.i("yt", "ad-free lane ready: \"" + r.title + "\" · "
                    + (r.hd != null ? "HD 720p + Low-Data 360p" : "Low-Data 360p")
                    + " · captions=" + (r.caption != null ? r.caption.name : "none")
                    + " · sponsor segments=" + segs.size());
                act.runOnUiThread(() -> {
                    // a newer navigation already started: this result is stale
                    String now = null;
                    try { now = web == null ? null : web.getUrl(); } catch (Throwable ignored) { }
                    if (now != null && !now.equals(lastStarted)
                            && YouTubeExtractor.videoIdOf(now) != null
                            && !vid.equals(YouTubeExtractor.videoIdOf(now))) return;
                    if (!headless) return;   // the Flutter dock owns the surface
                    try {
                        // the payload JSON rides the url slot; the Dart dock
                        // jsonDecodes it for the `▶ Play Ad-Free in Cinema` pill.
                        MainActivity.shellRoute(act, "yt", payload, r.title == null ? "" : r.title,
                            "", false, 0L);
                    } catch (Throwable t) {
                        Diag.w("web", "yt push: " + t);
                    }
                });
            } catch (Throwable t) {
                Diag.w("yt", "extract " + vid + ": " + t);
            }
        }, "si-yt").start();
    }

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
        setBackgroundColor(themedBackground());

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
        web.setBackgroundColor(themedBackground());
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
                // v3.4.6.g — the hidden Auto-extract .zip toggle (K_AUTO_ZIP)
                // is HONORED here: a .zip archive is only auto-captured when
                // the setting is on (a plain .srt/.vtt always captures — the
                // user never asked for those to be blocked).
                if (LkSubFinder.isSubtitleDownload(us)) {
                    boolean zip = LkSubFinder.isZip(us);
                    if (zip && !AppPrefs.autoZip(prefs)) {
                        Diag.i("web", "zip subtitle skipped (autoZip off): " + StreamSniffer.hostOf(us));
                        toast("auto-extract .zip is off in Settings — enable it to capture");
                        return false;   // let the site handle it; never a dead tap
                    }
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

            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                // ══ v3.4.6.k — THE STALE-BANNER RESET (Part_04 05:35, Part_05 00:00) ══
                // v3.4.6.j NEVER cleared the sniffed-stream state on navigation,
                // so the previous page's stream banner lingered on YouTube search
                // results (a stale "Play in Cinema" over unrelated pages). Every
                // navigation (and goHome) clears the hit map NOW, and the shell
                // dock is told to fold its stale row.
                synchronized (hits) { hits.clear(); }
                if (headless) {
                    try {
                        MainActivity.shellRoute(act, "sniff-clear", url == null ? "" : url,
                            "", "", false, 0L);
                        // v3.4.6.m — the floating ▶ leaves with the page too
                        // (a previous video's play button NEVER lingers).
                        MainActivity.shellRoute(act, "video-clear", url == null ? "" : url,
                            "", "", false, 0L);
                    } catch (Throwable ignored) { }
                } else {
                    try { if (pillRow != null) pillRow.setVisibility(View.GONE); } catch (Throwable ignored) { }
                }
                // ══ v3.4.6.k — THE ZERO-AD YOUTUBE LANE (LAW #3) ══════════════════
                // Any watch / shorts / youtu.be URL resolves the AD-FREE direct
                // stream + captions + SponsorBlock segments in the background;
                // the shell dock lights up `▶ Play Ad-Free in Cinema`.
                maybeExtractYouTube(url);
            }

            @Override public void onPageFinished(WebView v, String url) {
                if (AppPrefs.readerDark(prefs)) {
                    try {
                        v.evaluateJavascript(
                            "(function(){try{document.documentElement.style.colorScheme='dark';}catch(e){}})()", null);
                    } catch (Throwable ignored) { }
                }
                if (urlBox != null) urlBox.setText(url == null ? "" : url);
                // v3.4.6.i — the page color-scheme hint now follows the THEME
                // (the v3.4.6.h line forced 'dark' unconditionally — a
                // light-mode browser rendered dark pages).
                v.evaluateJavascript("try{document.documentElement.style.colorScheme='"
                    + (lightTheme() ? "light" : "dark") + "'}catch(e){}", null);
                // THE MEDIA HOOK: catch <video>/<source> picks the network layer
                // can't classify (extensionless sources, JS-built src attributes).
                v.evaluateJavascript(
                    "(function(){var f=function(el){var s=el.currentSrc||el.src;if(!s)return;" +
                    "window.SniJS&&window.SniJS.onVideo(String(s));};" +
                    "document.addEventListener('play',function(e){f(e.target)},true);" +
                    "document.addEventListener('loadedmetadata',function(e){f(e.target)},true);" +
                    "var l=document.querySelectorAll('video,source');for(var i=0;i<l.length;i++)f(l[i]);})()", null);
                // v3.4.6.m — THE FLOATING ▶ OBSERVER: the video-rect hook rides
                // every page finish (idempotent — the page flag survives
                // re-injection; scroll/resize/play all re-pick the rect).
                try {
                    v.evaluateJavascript(VIDEO_RECT_HOOK_JS, null);
                } catch (Throwable t) {
                    Diag.w("web", "video-rect hook: " + t);
                }
                injectBlobHook(v);
                // ══ v3.4.6.k — THE COSMETIC YOUTUBE AD-SKIP/MUTE JS (LAW #3, the
                // Part_05 00:17 + Part_06 00:09 in-preview overlays): when Ad
                // block is ON, a lightweight page script skips ad overlays the
                // instant they appear (clicks the Skip button, hides the ad
                // container, mutes while an ad plays) — the in-WebView preview
                // stays clean even before the Ad-Free Cinema lane opens. ══
                if (AppPrefs.adBlock(prefs) && YouTubeExtractor.isYouTubeUrl(url == null ? "" : url)) {
                    try {
                        v.evaluateJavascript(YT_AD_SKIP_JS, null);
                    } catch (Throwable t) {
                        Diag.w("web", "yt ad-skip inject: " + t);
                    }
                }
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
        // v3.4.6.i — THE BLANK-BROWSER KILL (video_4 01:31–02:05): HOME_URL is
        // a REAL bootstrap now. The v3.4.6.d headless lane loaded NOTHING when
        // there was no webLastUrl and no creationParams URL (HOME_URL was
        // declared at the top of this file and never referenced) — so the very
        // first Browser tab open sat as a blank dark-navy panel. The headless
        // WebView now boots STRAIGHT to https://www.google.com/ — never blank.
        if (headless) {
            // the Flutter side hands the initial URL through creationParams;
            // restore the last page so tab switches keep the position — and
            // when neither exists, the ENGINE home (the never-blank guarantee,
            // v3.4.6.j: the chosen search engine's own page).
            load(last != null && !last.isEmpty() ? last : homeUrlFor(prefs));
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

    // ══ v3.4.6.m — THE FLOATING ▶ RAIL (the video-detected push surface) ════

    /** The last pushed video-rect payload JSON ("" = no live video). */
    private volatile String lastVideoRectJson = "";
    /** The last rail push's uptime ms (the 300 ms rail rate limit). */
    private volatile long lastVideoPushAt;

    /**
     * v3.4.6.m — THE FLOATING ▶ RAIL HANDLER (main thread): turns the JS
     * observer's CSS-px rect into a dp-space payload for the Flutter overlay
     * and pushes it as t='video-detected' (JSON in the url slot — the same
     * shape the 'yt' lane uses); the empty rect pushes t='video-clear'.
     *
     * <p>Contract details:
     * <ul>
     *   <li>only while the Flutter shell is actually ON SCREEN (a backgrounded
     *       observer must never relaunch the app through the route pump);</li>
     *   <li>CSS px → logical dp through the WebView's own width (the platform
     *       view's coordinate space IS Flutter logical px);</li>
     *   <li>the playable url: a real http(s) source, else the newest sniffed
     *       hit (a blob: video's real stream), else the page URL on a
     *       YouTube watch/shorts page (the ad-free lane resolves it);</li>
     *   <li>dedupe (a scroll-jitter re-report of the same rect is dropped) +
     *       the 300 ms rail rate limit.</li>
     * </ul>
     */
    void onVideoRectFromJs(final String src, final int left, final int top,
                           final int width, final int height, final int viewportCss) {
        act.runOnUiThread(() -> {
            try {
                if (!headless) return;          // the Flutter capture dock owns the surface
                if (!MainActivity.shellVisible()) return;   // never a background relaunch
                // v3.4.6.m — THE GEOMETRY GUARD: a NaN/Infinity or a negative
                // rect can never become overlay geometry (the JS side already
                // drops non-finite numbers; the int bridge collapses them to 0,
                // which this bounds check treats as "no live video").
                boolean live = width >= 120 && height >= 80
                        && left >= 0 && top >= 0 && width <= 100000 && height <= 100000;
                if (!live) {
                    if (!lastVideoRectJson.isEmpty()) {
                        lastVideoRectJson = "";
                        MainActivity.shellRoute(act, "video-clear", "", "", "", false, 0L);
                    }
                    return;
                }
                int viewPx = web == null ? 0 : web.getWidth();
                float density = getResources().getDisplayMetrics().density;
                float viewDp = viewPx > 0 && density > 0f ? viewPx / density : 0f;
                float scale = viewportCss > 0 && viewDp > 0f ? viewDp / viewportCss : 1f;
                String s = src == null ? "" : src.trim();
                String url = s.startsWith("http://") || s.startsWith("https://") ? s : "";
                if (url.isEmpty()) {
                    StreamSniffer.Hit newest = null;
                    synchronized (hits) { for (StreamSniffer.Hit h : hits.values()) newest = h; }
                    if (newest != null) url = newest.url;
                }
                String page = null;
                try { page = web == null ? null : web.getUrl(); } catch (Throwable ignored) { }
                String pageUrl = page == null ? "" : page;
                boolean yt = YouTubeExtractor.videoIdOf(pageUrl) != null;
                if (yt && url.isEmpty()) url = pageUrl;   // the page IS the handoff
                // v3.4.6.m — THE HANDOFF GUARD (SSRF / credential hygiene): the
                // rail only ever hands Flutter a PUBLIC http(s) URL. Loopback,
                // private, link-local, multicast and `localhost`/`.local` hosts,
                // a non-http scheme and any embedded `user:pass@` credential are
                // dropped here, so a page can never steer the player (or the
                // user's next tap) at an internal address or leak a token. No
                // header, cookie or Authorization value is ever placed in the
                // payload — only kind/url/title/geometry.
                if (!isPublicHttpUrl(url)) url = "";
                String title = "";
                try { title = web == null ? "" : String.valueOf(web.getTitle()); } catch (Throwable ignored) { }
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("kind", yt ? "youtube" : "html5");
                m.put("url", url);
                m.put("title", title == null ? "" : title);
                m.put("left", left * scale);
                m.put("top", top * scale);
                m.put("width", width * scale);
                m.put("height", height * scale);
                m.put("adFree", yt);
                String json = MiniJson.write(m);
                if (json.equals(lastVideoRectJson)) return;   // dedupe (scroll jitter)
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastVideoPushAt < 300) return;      // the rail rate limit
                lastVideoPushAt = now;
                lastVideoRectJson = json;
                MainActivity.shellRoute(act, "video-detected", json, "", "", false, 0L);
            } catch (Throwable t) {
                Diag.w("web", "video-rect push: " + t);
            }
        });
    }

    /**
     * v3.4.6.m — THE PUBLIC-HTTP GUARD for the floating-▶ rail (SSRF / XSS
     * hygiene). True only for an {@code http://} or {@code https://} URL with a
     * real host that is NOT loopback, private (10/8, 172.16/12, 192.168/16),
     * link-local (169.254/16), multicast/reserved, {@code localhost} or
     * {@code *.local}. Any embedded userinfo credential (the {@code user:pass@}
     * form) is refused outright, so a token can never be forwarded through the
     * bridge. An empty/absent URL is false — the caller then falls back to its
     * own sniffed hit.
     */
    static boolean isPublicHttpUrl(String u) {
        if (u == null) return false;
        String s = u.trim();
        if (s.isEmpty()) return false;
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;
        try {
            java.net.URL url = new java.net.URL(s);
            String host = url.getHost();
            if (host == null) return false;
            String h = host.trim().toLowerCase(java.util.Locale.ROOT);
            if (h.isEmpty()) return false;
            if (url.getUserInfo() != null && !url.getUserInfo().isEmpty()) return false;
            if (h.startsWith("[") && h.endsWith("]") && h.length() > 2) h = h.substring(1, h.length() - 1);
            if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local")
                    || h.equals("0.0.0.0") || h.equals("::") || h.equals("::1")) return false;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matcher(h);
            if (m.matches()) {
                int a = Integer.parseInt(m.group(1));
                int b = Integer.parseInt(m.group(2));
                if (a == 0 || a == 10 || a == 127) return false;              // this-host / private / loopback
                if (a == 169 && b == 254) return false;                        // link-local
                if (a == 172 && b >= 16 && b <= 31) return false;              // private
                if (a == 192 && b == 168) return false;                        // private
                if (a >= 224) return false;                                    // multicast + reserved
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
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
         * v3.4.6.m — THE FLOATING ▶ RAIL: the VIDEO_RECT_HOOK_JS observer's
         * report — a visible <video>'s CSS-px rect (+ viewport width), or the
         * empty rect when the last video left the screen. Runs on the JS
         * bridge thread; the handler hops to the UI thread itself.
         */
        @JavascriptInterface
        public void onVideoRect(final String src, final int left, final int top,
                                final int width, final int height, final int viewportCss) {
            WebBrowserView.this.onVideoRectFromJs(src, left, top, width, height, viewportCss);
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
