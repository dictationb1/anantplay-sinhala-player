package lk.techworld.anantplay.sicast;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.IntConsumer;

/**
 * v3.4.0 - a 2..4 segment pill with an animated thumb (the Mornye segmented control).
 *
 * <p>Replaces every {@code AlertDialog.setItems(...)} that used to stand in for a
 * choice. Each segment is a TextView; the selected one paints the accent fill and the
 * thumb slides by cross-fading the two backgrounds over {@code MOTION_MEDIUM}.
 */
final class UiSegmented {

    private UiSegmented() { }

    /**
     * @param labels  2..4 short labels (already single-language)
     * @param selected the index that is on
     * @param onChange called with the NEW index, only on a real change
     */
    static LinearLayout make(Context c, ThemeEngine.Palette p, SharedPreferences prefs,
                             String[] labels, int selected, IntConsumer onChange) {
        LinearLayout track = new LinearLayout(c);
        track.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(p.card2);
        bg.setCornerRadius(UiCard.dp(c, 10));
        bg.setStroke(UiCard.dp(c, 1), ThemeEngine.hairline(p, AppPrefs.borderAlpha(prefs)));
        track.setBackground(bg);
        track.setPadding(UiCard.dp(c, 3), UiCard.dp(c, 3), UiCard.dp(c, 3), UiCard.dp(c, 3));

        final TextView[] segs = new TextView[labels.length];
        final int[] current = {Math.max(0, Math.min(labels.length - 1, selected))};

        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView t = new TextView(c);
            t.setText(labels[i]);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            UiTokens.text(t, 11f, 600, 0.2f, false);
            t.setPadding(UiCard.dp(c, 10), UiCard.dp(c, 6), UiCard.dp(c, 10), UiCard.dp(c, 6));
            t.setOnClickListener(v -> {
                if (current[0] == idx) return;
                int old = current[0];
                current[0] = idx;
                paint(c, p, segs, current[0], prefs, old);
                if (onChange != null) onChange.accept(idx);
            });
            segs[i] = t;
            track.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        paint(c, p, segs, current[0], prefs, -1);
        return track;
    }

    /** Re-paints all segments; the previously selected one fades out. */
    static void paint(Context c, ThemeEngine.Palette p, TextView[] segs, int sel,
                      SharedPreferences prefs, int previous) {
        for (int i = 0; i < segs.length; i++) {
            TextView t = segs[i];
            boolean on = i == sel;
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(UiCard.dp(c, 8));
            g.setColor(on ? p.accent : 0x00000000);
            t.setBackground(g);
            t.setTextColor(on ? 0xFF0A0A14 : p.desc);
            t.setTypeface(UiTokens.ui(on ? 600 : 400));
            if (on && previous >= 0 && previous != sel && AppPrefs.motionEnabled(prefs)) {
                t.setAlpha(0.4f);
                t.animate().alpha(1f).setDuration(UiTokens.MOTION_MEDIUM).start();
            }
        }
    }

    /** Convenience: a labelled segmented row (label left, control right, wrapped below on narrow). */
    static LinearLayout row(Context c, ThemeEngine.Palette p, SharedPreferences prefs,
                            String labelSi, String labelEn, String[] labels, int selected,
                            IntConsumer onChange) {
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, UiCard.dp(c, 8), 0, UiCard.dp(c, 8));
        TextView l = new TextView(c);
        l.setText(UiTokens.sinhalaFirst(prefs) ? labelSi : labelEn);
        l.setTextColor(p.text);
        UiTokens.body(l);
        wrap.addView(l, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout seg = make(c, p, prefs, labels, selected, onChange);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = UiCard.dp(c, 7);
        wrap.addView(seg, lp);
        return wrap;
    }

    /** A hairline strip of accent dots (10 presets + custom). */
    static LinearLayout accentDots(Context c, ThemeEngine.Palette p, SharedPreferences prefs,
                                   int selected, String customHex, IntConsumer onPick,
                                   View.OnClickListener onCustom) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, UiCard.dp(c, 6), 0, UiCard.dp(c, 10));

        final TextView[] dots = new TextView[ThemeEngine.ACCENTS.length];
        for (int i = 0; i < ThemeEngine.ACCENTS.length; i++) {
            final int idx = i;
            TextView dot = new TextView(c);
            dot.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(ThemeEngine.ACCENTS[i]);
            g.setStroke(UiCard.dp(c, i == selected ? 3 : 0), p.title);
            dot.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(UiCard.dp(c, 26), UiCard.dp(c, 26));
            lp.rightMargin = UiCard.dp(c, 7);
            dot.setOnClickListener(v -> {
                if (onPick != null) onPick.accept(idx);
            });
            dots[i] = dot;
            row.addView(dot, lp);
        }
        if (onCustom != null) {
            TextView custom = new TextView(c);
            custom.setText(UiTokens.label(prefs, "රිසි", "Custom"));
            custom.setTextColor(p.accentDim);
            UiTokens.text(custom, 10.5f, 600, 0f, false);
            GradientDrawable g = new GradientDrawable();
            g.setColor(p.accentSoft);
            g.setCornerRadius(UiCard.dp(c, 8));
            custom.setBackground(g);
            custom.setPadding(UiCard.dp(c, 9), UiCard.dp(c, 5), UiCard.dp(c, 9), UiCard.dp(c, 5));
            custom.setOnClickListener(onCustom);
            row.addView(custom, new LinearLayout.LayoutParams(-2, -2));
        }
        return row;
    }

    static View spacer(Context c, int dp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp, dp));
        return v;
    }
}
