package lk.techworld.anantplay.sicast;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;

/**
 * v3.4.0 - ONE card surface for the whole app (the Mornye {@code AppContentCard} idea
 * translated to pure framework Java).
 *
 * <p>Every group, panel and sheet paints through here, so the Card-corner-radius,
 * Surface-style (flat / glass) and Border-alpha preferences reach EVERY surface at
 * once - the v3.2.0 bug where the radius setting changed nothing happened precisely
 * because each screen painted its own {@code dp(16)}.
 */
final class UiCard {

    private UiCard() { }

    /** A vertical card: the user's radius, surface style and hairline alpha. */
    static LinearLayout make(Context c, ThemeEngine.Palette p, SharedPreferences prefs) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(drawable(c, p, prefs));
        box.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        return box;
    }

    /** The card background itself (also used by panels and sheets). */
    static GradientDrawable drawable(Context c, ThemeEngine.Palette p, SharedPreferences prefs) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(ThemeEngine.cardFill(p, AppPrefs.glass(prefs)));
        int[] radii = ThemeEngine.radii(AppPrefs.cardRadius(prefs));
        g.setCornerRadius(dp(c, radii[0]));
        g.setStroke(dp(c, 1), ThemeEngine.hairline(p, AppPrefs.borderAlpha(prefs)));
        return g;
    }

    /** A sheet surface: always the sheet radius, always translucent. */
    static GradientDrawable sheetDrawable(Context c, ThemeEngine.Palette p, SharedPreferences prefs) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(ThemeEngine.cardFill(p, true));
        g.setCornerRadii(new float[]{dp(c, 28), dp(c, 28), dp(c, 28), dp(c, 28), 0, 0, 0, 0});
        g.setStroke(dp(c, 1), ThemeEngine.hairline(p, AppPrefs.borderAlpha(prefs)));
        return g;
    }

    /**
     * The expressive press: a 0.985 scale that returns on release. Honours the Motion
     * toggle, so a battery- or accessibility-minded user gets an instant, still state.
     */
    static void press(View v, SharedPreferences prefs) {
        if (v == null || !AppPrefs.motionEnabled(prefs)) return;
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.985f).scaleY(0.985f)
                        .setDuration(UiTokens.MOTION_FAST).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f)
                        .setDuration(UiTokens.MOTION_FAST).start();
                    break;
                default:
                    break;
            }
            return false;      // never consume: the click must still fire
        });
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
