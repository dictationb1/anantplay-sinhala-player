package lk.techworld.anantplay.sicast;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * v3.4.0 - THE VISUAL SPEED DIAL (the browser's start page).
 *
 * <p>Replaces the v3.2.0 {@code linksRow}: a horizontal {@code LinearLayout} of chips with
 * no scroll container, which clipped links 5-8 off the right edge on any 360dp phone.
 * A {@link GridLayout} physically cannot do that: with 2, 3 or 4 columns the tiles wrap.
 *
 * <p>Two groups, as the brief requires:
 * <ul>
 *   <li><b>Streaming</b> - the user's cinema sites,</li>
 *   <li><b>Sri Lankan subtitles</b> - Baiscope, Zoom.lk, PirateLK, SubDL, OpenSubtitles.</li>
 * </ul>
 *
 * <p>Each tile is a coloured monogram (the first letter, tinted by a stable hash of the
 * host) so it looks designed with zero network calls. Long-press opens an edit sheet.
 */
final class SpeedDial {

    /** The five Sri Lankan subtitle sites the app knows about, in the order users expect. */
    static final String[][] LK_SITES = {
        {"Baiscope", "https://baiscope.lk"},
        {"Zoom.lk", "https://zoom.lk"},
        {"PirateLK", "https://piratelk.com"},
        {"SubDL", "https://subdl.com"},
        {"OpenSubtitles", "https://www.opensubtitles.org"},
        // v3.4.1 — LAYER 5: translate any .srt to Sinhala inside the WebView itself.
        // Its result is a client-side Blob, which the Blob bridge captures and
        // attaches to the playing movie with one tap (see WebBrowserView).
        {"NewZone Translate", "https://tools.newzone.top/en/subtitle-translator"},
    };

    interface OnOpen {
        void open(String url);
        void add();
        void edit(AppPrefs.Link link, int index);
    }

    private SpeedDial() { }

    /**
     * Builds the whole start page.
     *
     * @param links the user's links (name|url|group), may be empty
     */
    static View build(Context c, ThemeEngine.Palette p, SharedPreferences prefs,
                      List<AppPrefs.Link> links, OnOpen cb) {
        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiCard.dp(c, 14), UiCard.dp(c, 10), UiCard.dp(c, 14), UiCard.dp(c, 20));
        scroll.addView(root, new FrameLayout.LayoutParams(-1, -2));

        // ── group 1: streaming ────────────────────────────────────────────────
        List<AppPrefs.Link> streaming = new ArrayList<>();
        List<AppPrefs.Link> subs = new ArrayList<>();
        for (AppPrefs.Link l : links) {
            if (l == null || l.url == null) continue;
            if ("subtitle".equals(l.group)) subs.add(l); else streaming.add(l);
        }
        if (streaming.isEmpty()) {
            streaming.add(new AppPrefs.Link("AnantPlay", "https://github.com/dictationb1/anantplay-sinhala-player", "streaming"));
            streaming.add(new AppPrefs.Link("YouTube", "https://m.youtube.com", "streaming"));
            streaming.add(new AppPrefs.Link("Baiscope", "https://baiscope.lk", "streaming"));
        }
        if (subs.isEmpty()) {
            for (String[] s : LK_SITES) {
                subs.add(new AppPrefs.Link(s[0], s[1], "subtitle"));
            }
        }

        root.addView(section(c, p, prefs, "Streaming", "ස්ට්‍රීමිං"), new LinearLayout.LayoutParams(-1, -2));
        root.addView(grid(c, p, prefs, streaming, AppPrefs.speedDialCols(prefs), cb),
            new LinearLayout.LayoutParams(-1, -2));

        root.addView(section(c, p, prefs, "Sri Lankan subtitles", "සිංහල උපසිරැසි"),
            new LinearLayout.LayoutParams(-1, -2));
        root.addView(grid(c, p, prefs, subs, AppPrefs.speedDialCols(prefs), cb),
            new LinearLayout.LayoutParams(-1, -2));

        TextView hint = new TextView(c);
        hint.setText(UiTokens.label(prefs, "දිගුව එබීමෙන් සංස්කරණය කරන්න",
            "Long-press a tile to edit, move or delete it"));
        hint.setTextColor(p.desc);
        hint.setAlpha(0.8f);
        UiTokens.caption(hint);
        hint.setPadding(0, UiCard.dp(c, 16), 0, 0);
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        return scroll;
    }

    private static TextView section(Context c, ThemeEngine.Palette p, SharedPreferences prefs,
                                    String en, String si) {
        TextView t = new TextView(c);
        t.setText(UiTokens.sinhalaFirst(prefs) ? si : en);
        t.setTextColor(p.title);
        UiTokens.sectionHeader(t);
        t.setPadding(UiCard.dp(c, 2), UiCard.dp(c, 14), UiCard.dp(c, 2), UiCard.dp(c, 8));
        return t;
    }

    /**
     * The tile grid: weighted rows of {@code cols} tiles. A GridLayout would be the
     * obvious choice, but weighted LinearLayout rows (a) cannot clip, (b) keep every
     * child's LayoutParams provable by lab/verify_layouts.py, and (c) let the last row
     * pad with invisible spacers so a 3-tile group still aligns.
     */
    private static LinearLayout grid(Context c, ThemeEngine.Palette p, SharedPreferences prefs,
                                     List<AppPrefs.Link> links, int cols, OnOpen cb) {
        int n = Math.max(2, Math.min(4, cols));
        LinearLayout g = new LinearLayout(c);
        g.setOrientation(LinearLayout.VERTICAL);

        java.util.List<View> tiles = new ArrayList<>();
        for (int i = 0; i < links.size(); i++) {
            final AppPrefs.Link l = links.get(i);
            final int index = i;
            tiles.add(tile(c, p, prefs, l, () -> cb.open(l.url), () -> cb.edit(l, index)));
        }
        tiles.add(addTile(c, p, prefs, cb));

        LinearLayout row = null;
        for (int i = 0; i < tiles.size(); i++) {
            if (i % n == 0) {
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                g.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(UiCard.dp(c, 3), UiCard.dp(c, 3), UiCard.dp(c, 3), UiCard.dp(c, 3));
            row.addView(tiles.get(i), lp);
        }
        // pad the last row so its tiles keep the same width as a full row
        if (row != null) {
            int missing = (n - (tiles.size() % n)) % n;
            for (int i = 0; i < missing; i++) {
                row.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
            }
        }
        return g;
    }

    private static LinearLayout tile(Context c, ThemeEngine.Palette p, SharedPreferences prefs,
                                     final AppPrefs.Link link, Runnable onClick, Runnable onLong) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(0, UiCard.dp(c, 8), 0, UiCard.dp(c, 6));

        TextView mono = new TextView(c);
        String letter = (link.name == null || link.name.isEmpty()) ? "?" : link.name.substring(0, 1).toUpperCase();
        mono.setText(letter);
        mono.setTextColor(0xFFFFFFFF);
        mono.setGravity(Gravity.CENTER);
        UiTokens.text(mono, 18f, 800, 0f, false);
        int[] grad = gradientFor(link.url);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, grad);
        bg.setCornerRadius(UiCard.dp(c, 15));
        mono.setBackground(bg);
        int side = UiCard.dp(c, 54);
        box.addView(mono, new LinearLayout.LayoutParams(side, side));

        TextView label = new TextView(c);
        label.setText(link.name);
        label.setTextColor(p.text);
        UiTokens.text(label, 10f, 600, 0f, false);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setPadding(UiCard.dp(c, 2), UiCard.dp(c, 6), UiCard.dp(c, 2), 0);
        box.addView(label, new LinearLayout.LayoutParams(-1, -2));

        box.setOnClickListener(v -> onClick.run());
        box.setOnLongClickListener(v -> { onLong.run(); return true; });
        box.setClickable(true);
        UiCard.press(box, prefs);
        return box;
    }

    private static LinearLayout addTile(Context c, ThemeEngine.Palette p, SharedPreferences prefs, OnOpen cb) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(0, UiCard.dp(c, 8), 0, UiCard.dp(c, 6));

        android.widget.ImageView plus = new android.widget.ImageView(c);
        plus.setImageDrawable(UiIcons.tinted(c, R.drawable.ic_plus, p.accent, 22));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(p.accentSoft);
        bg.setCornerRadius(UiCard.dp(c, 15));
        bg.setStroke(UiCard.dp(c, 1), p.accent);
        plus.setBackground(bg);
        int side = UiCard.dp(c, 54);
        box.addView(plus, new LinearLayout.LayoutParams(side, side));

        TextView label = new TextView(c);
        label.setText(UiTokens.label(prefs, "එකතු", "Add"));
        label.setTextColor(p.desc);
        UiTokens.text(label, 10f, 600, 0f, false);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, UiCard.dp(c, 6), 0, 0);
        box.addView(label, new LinearLayout.LayoutParams(-1, -2));
        box.setOnClickListener(v -> cb.add());
        return box;
    }

    /** A stable two-stop gradient from the host name - no favicon request, no privacy cost. */
    static int[] gradientFor(String url) {
        String host = url == null ? "" : url;
        try {
            host = android.net.Uri.parse(url).getHost();
            if (host == null) host = url;
        } catch (Throwable ignored) { }
        int h = host == null ? 0 : Math.abs(host.hashCode());
        int hue = h % 360;
        float[] a = {hue, 0.62f, 0.58f};
        float[] b = {(hue + 34) % 360, 0.72f, 0.42f};
        int c1 = android.graphics.Color.HSVToColor(a);
        int c2 = android.graphics.Color.HSVToColor(b);
        return new int[]{c1, c2};
    }
}
