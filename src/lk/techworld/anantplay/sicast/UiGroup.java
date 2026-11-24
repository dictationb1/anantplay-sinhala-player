package lk.techworld.anantplay.sicast;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * v3.4.0 - a GROUPED settings list, the Mornye {@code settings_group.dart} grammar:
 * an uppercase overline header, then rows of
 * <pre>
 *   [20dp vector]  Primary label          value pill  ›
 *                  Secondary (68%) label
 * </pre>
 * with hairline dividers indented to the TEXT (not to the icon) and a 48dp minimum
 * touch height.
 */
final class UiGroup {

    private UiGroup() { }

    /**
     * Starts a group: a card with an overline header. Add rows with {@link #row} /
     * {@link #switchRow} / {@link #valueRow}.
     */
    static LinearLayout make(Context c, ThemeEngine.Palette p, SharedPreferences prefs,
                             String overlineSi, String overlineEn) {
        LinearLayout card = UiCard.make(c, p, prefs);
        TextView head = new TextView(c);
        head.setText(UiTokens.sinhalaFirst(prefs) ? overlineSi : overlineEn);
        head.setTextColor(p.desc);
        head.setAllCaps(true);
        UiTokens.text(head, UiTokens.OVERLINE, 600, 0.6f, false);
        head.setPadding(0, 0, 0, UiCard.dp(c, 6));
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    /**
     * One row.
     *
     * @param iconRes  a {@code R.drawable.ic_*} or 0 for none
     * @param value    the right-hand value text, or null
     * @param chevron  true for a drill-down row
     * @return the PRIMARY label view (callers update its text after a change)
     */
    static TextView row(Context c, LinearLayout card, ThemeEngine.Palette p, SharedPreferences prefs,
                        int iconRes, String si, String en, String value, boolean chevron,
                        View.OnClickListener click) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(UiCard.dp(c, 52));
        r.setPadding(0, UiCard.dp(c, 10), 0, UiCard.dp(c, 10));

        if (iconRes != 0) {
            View ic = UiGlassIcon.create(c, prefs, iconRes, p.accent, 18, 36,
                en, null);
            r.addView(ic, new LinearLayout.LayoutParams(UiCard.dp(c, 36), UiCard.dp(c, 36)));
            View gap = new View(c);
            r.addView(gap, new LinearLayout.LayoutParams(UiCard.dp(c, 12), 1));
        }

        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView primary = new TextView(c);
        primary.setTextColor(p.title);
        UiTokens.cardTitle(primary);
        primary.setMaxLines(1);
        primary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(primary, new LinearLayout.LayoutParams(-1, -2));

        TextView secondary = new TextView(c);
        secondary.setTextColor(p.desc);
        UiTokens.bodySecondary(secondary);
        secondary.setMaxLines(2);
        text.addView(secondary, new LinearLayout.LayoutParams(-1, -2));
        UiTokens.setBilingual(prefs, primary, secondary, si, en);

        r.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));

        if (value != null) {
            TextView v = new TextView(c);
            v.setText(value);
            v.setTextColor(p.accentDim);
            UiTokens.text(v, 11.5f, 600, 0f, false);
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(p.accentSoft);
            pill.setCornerRadius(UiCard.dp(c, 7));
            v.setBackground(pill);
            v.setPadding(UiCard.dp(c, 8), UiCard.dp(c, 3), UiCard.dp(c, 8), UiCard.dp(c, 3));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = UiCard.dp(c, 8);
            r.addView(v, lp);
        }
        if (chevron) {
            ImageView ch = new ImageView(c);
            ch.setImageDrawable(UiIcons.tinted(c, R.drawable.ic_forward, p.desc, 16));
            ch.setContentDescription("Open " + en);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                UiCard.dp(c, 24), UiCard.dp(c, 40));
            lp.leftMargin = UiCard.dp(c, 8);
            r.addView(ch, lp);
        }
        if (click != null) {
            r.setOnClickListener(click);
            r.setClickable(true);
            r.setFocusable(true);
            UiCard.press(r, prefs);
        }
        card.addView(r, new LinearLayout.LayoutParams(-1, -2));
        return primary;
    }

    /** A row with an ON/OFF switch that flips a boolean preference. */
    static TextView switchRow(Context c, LinearLayout card, ThemeEngine.Palette p, SharedPreferences prefs,
                              int iconRes, String si, String en, String key, boolean def) {
        return switchRow(c, card, p, prefs, iconRes, si, en, key, def, null);
    }

    /** Same control with a lightweight callback for live visual preferences. */
    static TextView switchRow(Context c, LinearLayout card, ThemeEngine.Palette p, SharedPreferences prefs,
                              int iconRes, String si, String en, String key, boolean def,
                              Runnable afterToggle) {
        final TextView v = new TextView(c);
        v.setTextColor(prefs.getBoolean(key, def) ? p.ok : p.desc);
        UiTokens.text(v, 11.5f, 600, 0f, false);
        final String[] label = new String[]{si, en};
        final View[] rowHolder = new View[1];

        TextView primary = row(c, card, p, prefs, iconRes, si, en, null, false, null);
        // find the row we just added (last child) and give it the switch behaviour
        View rowView = card.getChildAt(card.getChildCount() - 1);
        rowHolder[0] = rowView;
        if (rowView instanceof LinearLayout) {
            LinearLayout r = (LinearLayout) rowView;
            boolean on = prefs.getBoolean(key, def);
            v.setText(on ? "ON" : "OFF");
            v.setTextColor(on ? p.ok : p.desc);
            r.addView(v, new LinearLayout.LayoutParams(-2, -2));
            final Runnable paint = () -> {
                boolean now = prefs.getBoolean(key, def);
                v.setText(now ? "ON" : "OFF");
                v.setTextColor(now ? p.ok : p.desc);
            };
            r.setOnClickListener(x -> {
                prefs.edit().putBoolean(key, !prefs.getBoolean(key, def)).apply();
                paint.run();
                if (afterToggle != null) afterToggle.run();
            });
            r.setClickable(true);
            r.setFocusable(true);
        }
        return primary;
    }

    /** A quiet hairline divider inset to the text column. */
    static void divider(Context c, LinearLayout card, ThemeEngine.Palette p, SharedPreferences prefs) {
        View line = new View(c);
        line.setBackgroundColor(ThemeEngine.hairline(p, Math.max(6, AppPrefs.borderAlpha(prefs) / 2)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, UiCard.dp(c, 0.5f)));
        lp.leftMargin = UiCard.dp(c, 34);
        card.addView(line, lp);
    }

    /** A slider row: label on the left, live value on the right, the slider below. */
    static android.widget.SeekBar sliderRow(Context c, LinearLayout card, ThemeEngine.Palette p,
                                            SharedPreferences prefs, String si, String en,
                                            int min, int max, int value,
                                            String suffix, java.util.function.IntConsumer onChange) {
        LinearLayout wrap = UiSlider.make(c, p, prefs, si, en, min, max, value, suffix, onChange);
        card.addView(wrap, new LinearLayout.LayoutParams(-1, -2));
        return null;
    }

    static android.widget.SeekBar unusedLegacySlider(Context c, LinearLayout card, ThemeEngine.Palette p,
                                            String si, String en, int min, int max, int value,
                                            String suffix, java.util.function.IntConsumer onChange) {
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, UiCard.dp(c, 8), 0, UiCard.dp(c, 6));

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(c);
        label.setText(si);
        label.setTextColor(p.text);
        UiTokens.body(label);
        head.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView valueView = new TextView(c);
        valueView.setText(value + suffix);
        valueView.setTextColor(p.accentDim);
        UiTokens.text(valueView, 12f, 600, 0f, false);
        head.addView(valueView, new LinearLayout.LayoutParams(-2, -2));
        wrap.addView(head, new LinearLayout.LayoutParams(-1, -2));

        android.widget.SeekBar bar = new android.widget.SeekBar(c);
        bar.setMax(Math.max(1, max - min));
        bar.setProgress(Math.max(0, Math.min(max - min, value - min)));
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int progress, boolean fromUser) {
                int v = min + progress;
                valueView.setText(v + suffix);
                if (fromUser && onChange != null) onChange.accept(v);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) { }
        });
        wrap.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        card.addView(wrap, new LinearLayout.LayoutParams(-1, -2));
        return bar;
    }

    /** A caption/footnote under a group. */
    static TextView caption(Context c, ThemeEngine.Palette p, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextColor(p.desc);
        tv.setAlpha(0.75f);
        UiTokens.caption(tv);
        tv.setLineSpacing(UiCard.dp(c, 2), 1.2f);
        tv.setPadding(UiCard.dp(c, 4), UiCard.dp(c, 8), UiCard.dp(c, 4), UiCard.dp(c, 4));
        return tv;
    }

    /** A designed empty state: big muted vector, one bold line, one quiet line. */
    static LinearLayout empty(Context c, ThemeEngine.Palette p, int iconRes,
                              String title, String body) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(0, UiCard.dp(c, 26), 0, UiCard.dp(c, 22));
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(UiIcons.tinted(c, iconRes, p.desc, 34));
        box.addView(iv, new LinearLayout.LayoutParams(-2, -2));
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(p.text);
        UiTokens.cardTitle(t);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, UiCard.dp(c, 10), 0, 0);
        box.addView(t, new LinearLayout.LayoutParams(-1, -2));
        TextView b = new TextView(c);
        b.setText(body);
        b.setTextColor(p.desc);
        UiTokens.bodySecondary(b);
        b.setGravity(Gravity.CENTER);
        b.setPadding(UiCard.dp(c, 18), UiCard.dp(c, 4), UiCard.dp(c, 18), 0);
        box.addView(b, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    static Typeface semi(Context c) { return UiTokens.si(c, 600); }
}
