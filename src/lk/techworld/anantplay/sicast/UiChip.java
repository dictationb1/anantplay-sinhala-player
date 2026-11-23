package lk.techworld.anantplay.sicast;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * v3.4.0 - a pill with a leading vector and ONE language (the Mornye choice chip).
 *
 * <p>Used by the player's action strip and the browser's toolbars. A chip never carries
 * two languages: it takes the label the user asked for, so it always fits on one line.
 */
final class UiChip {

    private UiChip() { }

    static TextView make(Context c, ThemeEngine.Palette p, SharedPreferences prefs,
                         int iconRes, String label, boolean selected, View.OnClickListener click) {
        TextView t = new TextView(c);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        UiTokens.chipLabel(t);
        apply(c, t, p, prefs, selected);
        if (iconRes != 0) {
            UiIcons.setCompound(t, iconRes, selected ? 0xFF0A0A14 : p.text, 14, Gravity.START);
        }
        t.setPadding(UiCard.dp(c, 12), UiCard.dp(c, 7), UiCard.dp(c, 12), UiCard.dp(c, 7));
        if (click != null) t.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = UiCard.dp(c, 6);
        t.setLayoutParams(lp);
        return t;
    }

    /** Re-paints a chip in place (the selected state of a filter strip). */
    static void setSelected(Context c, TextView chip, ThemeEngine.Palette p,
                            SharedPreferences prefs, boolean selected) {
        apply(c, chip, p, prefs, selected);
    }

    private static void apply(Context c, TextView t, ThemeEngine.Palette p,
                              SharedPreferences prefs, boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(UiCard.dp(c, 999));
        g.setColor(selected ? p.accent : (AppPrefs.glass(prefs) ? 0x22FFFFFF : p.chip));
        if (!selected) g.setStroke(UiCard.dp(c, 1), ThemeEngine.hairline(p, AppPrefs.borderAlpha(prefs)));
        t.setBackground(g);
        t.setTextColor(selected ? 0xFF0A0A14 : p.text);
    }

    /** A horizontally scrollable strip of chips (never clips off-screen). */
    static android.widget.HorizontalScrollView strip(Context c) {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        return hs;
    }

    static LinearLayout stripRow(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, UiCard.dp(c, 4), 0, UiCard.dp(c, 4));
        return row;
    }
}
