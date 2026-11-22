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
    private ProgressBar progress;
    private TextView pill, autoChip;
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
    /** v3.2.0 — the AnantPlay-style custom-fullscreen holder. */
    private View fullscreenView;
    private android.webkit.WebChromeClient.CustomViewCallback fullscreenCb;

    static final String HOME_URL = "https://duckduckgo.com/";
    private static final String UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    public WebBrowserView(Activity act) {
        super(act);
        this.act = act;
        this.prefs = act.getSharedPreferences("sicast", Context.MODE_PRIVATE);
        buildUi();
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ── UI ─────────────────────────────────────────────────────────────────────

    private void buildUi() {
        setBackgroundColor(0xFF0B1020);

        LinearLayout column = new LinearLayout(act);
        column.setOrientation(LinearLayout.VERTICAL);
        addView(column, new FrameLayout.LayoutParams(-1, -1));

        // ── the top bar ──
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF0E1526);
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));
        column.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        bar.addView(navBtn("‹", v -> goBack()), new LinearLayout.LayoutParams(dp(40), dp(40)));
        bar.addView(navBtn("›", v -> forward()), new LinearLayout.LayoutParams(dp(40), dp(40)));
        urlBox = new EditText(act);
        urlBox.setTextSize(13f);
        urlBox.setTextColor(0xFFE2E8F0);
        urlBox.setHintTextColor(0xFF94A3B8);
        urlBox.setHint("URL හෝ සෙවුම · url or search");
        urlBox.setBackground(rounded(0xFF101A30));
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
        bar.addView(navBtn("↻", v -> reload()), new LinearLayout.LayoutParams(dp(40), dp(40)));
        bar.addView(navBtn("⋮", v -> menu()), new LinearLayout.LayoutParams(dp(40), dp(40)));

        // ── the loading progress strip ──
        progress = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.getProgressDrawable().setColorFilter(0xFF3B82F6, android.graphics.PorterDuff.Mode.SRC_IN);
        column.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));

        // ── v3.2.0 — THE CUSTOM-LINKS QUICK LAUNCHER (AnantPlay's quick-sites
        // pattern): one horizontal chip row built from Settings → Web Player &
        // Custom Links — 1 tap loads a saved streaming site (users edit the list
        // any time; the row rebuilds whenever the Web tab is re-entered, so a
        // link edit is live without any app update). ──
        linksRow = new LinearLayout(act);
        linksRow.setOrientation(LinearLayout.HORIZONTAL);
        linksRow.setBackgroundColor(0xFF0E1526);
        linksRow.setPadding(dp(6), dp(5), dp(6), dp(5));
        column.addView(linksRow, new LinearLayout.LayoutParams(-1, -2));
        rebuildLinks();

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
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // THE POPUP BLOCKER: no window.open targets at all — redirect popups of
        // streaming sites die right here instead of covering the video.
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setUserAgentString(prefs.getBoolean("webDesktopUa", false) ? UA_DESKTOP : null);

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String us = u == null ? "" : String.valueOf(u);
                if (StreamSniffer.isAdOrTracker(us)) {
                    Diag.i("web", "blocked navigation to ad/tracker host: " + StreamSniffer.hostOf(us));
                    toast("🚫 " + StreamSniffer.hostOf(us));
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
                urlBox.setText(url == null ? "" : url);
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
        column.addView(web, new LinearLayout.LayoutParams(-1, 0, 1f));

        // ── the floating pill + ⚡Auto chip ──
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
        LayoutParams pl = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        pl.bottomMargin = dp(20);
        addView(pillRow, pl);

        pill = new TextView(act);
        pill.setTextColor(0xFFE2E8F0);
        pill.setTextSize(13f);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setPadding(dp(12), dp(10), dp(12), dp(10));
        pill.setOnClickListener(v -> playLatest());
        pillRow.addView(pill, new LinearLayout.LayoutParams(-2, -2));

        autoChip = new TextView(act);
        autoChip.setTextSize(11.5f);
        autoChip.setPadding(dp(10), dp(6), dp(10), dp(6));
        autoChip.setOnClickListener(v -> {
            boolean on = !prefs.getBoolean("autoOpenSniffed", false);
            prefs.edit().putBoolean("autoOpenSniffed", on).apply();
            paintAutoChip();
            toast(on ? "⚡ Auto-Open ON — video එක හමු වූ විගස player එක ඇරේ" : "⚡ Auto-Open OFF");
        });
        pillRow.addView(autoChip, new LinearLayout.LayoutParams(-2, -2));
        paintAutoChip();

        if (Build.VERSION.SDK_INT >= 19) web.setWebContentsDebuggingEnabled(false);
        load(prefs.getString("webLastUrl", HOME_URL));
    }

    private void paintAutoChip() {
        boolean on = prefs.getBoolean("autoOpenSniffed", false);
        autoChip.setText("⚡ Auto " + (on ? "ON" : "OFF"));
        autoChip.setTextColor(on ? 0xFF22C55E : 0xFF94A3B8);
    }

    private View navBtn(String glyph, OnClickListener click) {
        TextView t = new TextView(act);
        t.setText(glyph);
        t.setTextColor(0xFFE2E8F0);
        t.setTextSize(16f);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rounded(0xFF101A30));
        t.setOnClickListener(click);
        return t;
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
            : "https://duckduckgo.com/?q=" + android.net.Uri.encode(t));
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
        web.loadUrl(url);
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

    private void menu() {
        android.widget.PopupMenu m = new android.widget.PopupMenu(act, urlBox);
        final boolean desktop = prefs.getBoolean("webDesktopUa", false);
        m.getMenu().add(0, 1, 0, "🖥 Desktop mode: " + (desktop ? "ON" : "OFF"));
        m.getMenu().add(0, 2, 1, "★ Add bookmark for this page");
        m.getMenu().add(0, 3, 2, "☰ Bookmarks…");
        m.getMenu().add(0, 4, 3, "📋 Paste link from clipboard");
        m.getMenu().add(0, 5, 4, "🏠 Browser home");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    prefs.edit().putBoolean("webDesktopUa", !desktop).apply();
                    web.getSettings().setUserAgentString(!desktop ? UA_DESKTOP : null);
                    toast(!desktop ? "🖥 Desktop mode ON (reload)" : "📱 Mobile mode ON (reload)");
                    reload();
                    return true;
                case 2:
                    addBookmark(web.getUrl(), web.getTitle());
                    return true;
                case 3:
                    showBookmarks();
                    return true;
                case 4:
                    pasteFromClipboard();
                    return true;
                default:
                    load(HOME_URL);
                    return true;
            }
        });
        m.show();
    }

    private void pasteFromClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) { toast("clipboard හිස් · clipboard empty"); return; }
            CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(act);
            String s = t == null ? "" : t.toString().trim();
            if (s.startsWith("http://") || s.startsWith("https://")) load(s);
            else toast("clipboard එකේ URL එකක් නැත · no URL on the clipboard");
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
        toast("★ " + name);
    }

    private void showBookmarks() {
        String list = prefs.getString("webBookmarks", "");
        if (list.isEmpty()) { toast("bookmarks නැත · none yet (⋮ → Add bookmark)"); return; }
        final String[] entries = list.split("\n");
        String[] labels = new String[entries.length];
        for (int i = 0; i < entries.length; i++) {
            int bar2 = entries[i].indexOf('|');
            labels[i] = (bar2 < 0 ? entries[i] : entries[i].substring(bar2 + 1))
                + "\n" + (bar2 < 0 ? "" : entries[i].substring(0, bar2));
        }
        new android.app.AlertDialog.Builder(act)
            .setTitle("★ Bookmarks (tap = open, long-press = remove)")
            .setItems(labels, (d, which) -> load(entries[which].substring(0, Math.max(0, entries[which].indexOf('|')))))
            .setNeutralButton("🗑 Clear all", (d, w) -> prefs.edit().putString("webBookmarks", "").apply())
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
            pill.setText(hit.pillLabel(hits.size()));
            pillRow.setVisibility(View.VISIBLE);
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
        Intent i = new Intent(act, PlayActivity.class)
            .putExtra("url", hit.url)
            .putExtra("title", hit.pageTitle == null || hit.pageTitle.isEmpty()
                ? StreamSniffer.hostOf(hit.url) : hit.pageTitle);
        if (!headers.isEmpty()) i.putExtra("headers", MiniJson.write(new LinkedHashMap<String, Object>(headers)));
        try {
            act.startActivity(i);
            Diag.i("web", "opened in Cinema Player: [" + hit.kind + "] " + hit.url);
        } catch (Throwable t) {
            Diag.w("web", "player launch: " + t);
            toast("player එක විවෘත කළ නොහැක");
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
    }

    // ── lifecycle ──────────────────────────────────────────────────────────────

    public void onPauseWeb() { try { web.onPause(); } catch (Throwable ignored) { } }
    public void onResumeWeb() { try { web.onResume(); rebuildLinks(); } catch (Throwable ignored) { } }

    /** v3.2.0 — rebuild the quick-links chip row from the CURRENT prefs list
     *  (so an edit in Settings lands the next time the Web tab is shown). */
    private void rebuildLinks() {
        if (linksRow == null) return;
        linksRow.removeAllViews();
        List<AppPrefs.Link> links = AppPrefs.webLinks(prefs);
        if (links.isEmpty()) {
            TextView hint = new TextView(act);
            hint.setText("⚡ " + act.getString(R.string.links_hint));
            hint.setTextColor(0xFF94A3B8);
            hint.setTextSize(11.5f);
            hint.setPadding(dp(8), dp(2), dp(8), dp(2));
            linksRow.addView(hint, new LinearLayout.LayoutParams(-2, -2));
            return;
        }
        for (final AppPrefs.Link l : links) {
            TextView chip = new TextView(act);
            chip.setText("⚡ " + l.name);
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
}
