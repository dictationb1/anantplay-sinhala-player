package lk.techworld.anantplay.sicast;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;

/**
 * v3.4.0 - MANDATORY FIX #4: THE WINDOW ITSELF FOLLOWS THE THEME.
 *
 * <p>Before this class the palette painted VIEWS only: {@code res/values/styles.xml}
 * hard-codes {@code #0B1020} into {@code windowBackground} / {@code statusBarColor} /
 * {@code navigationBarColor}, so a user on AMOLED (pure {@code #000000}) or Clean Light
 * saw a Midnight Slate window behind the content - a visible flash on cold start and a
 * 1px seam during every activity transition.
 *
 * <p>Doing it here (and NOT in styles.xml) also keeps the resource file free of any
 * {@code tools:targetApi} / {@code xmlns:tools} trickery that aapt2 could reject.
 *
 * <p>This class lives apart from {@link ThemeEngine} on purpose: the lab compiles
 * ThemeEngine as a PURE Java class (no android.jar) to prove the palette maths, and
 * that contract must not be broken by a window call.
 */
final class UiWindow {

    private UiWindow() { }

    /** Paints the decor background + both system bars and sets the bar icon appearance. */
    static void apply(Activity a, ThemeEngine.Palette p) {
        if (a == null || p == null) return;
        Window w = a.getWindow();
        if (w == null) return;
        try {
            View decor = w.getDecorView();
            decor.setBackgroundColor(p.bg);
            // Deprecated on API 35+ (edge-to-edge is enforced by the OS) but still the
            // correct call on 26-34 and harmless afterwards.
            w.setStatusBarColor(p.bg);
            w.setNavigationBarColor(p.bg);
            if (Build.VERSION.SDK_INT >= 29) {
                w.setNavigationBarContrastEnforced(false);
                w.setStatusBarContrastEnforced(false);
            }
            boolean lightBars = p.light;              // dark icons on a light canvas
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController c = w.getInsetsController();
                if (c != null) {
                    int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                             | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    c.setSystemBarsAppearance(lightBars ? mask : 0, mask);
                }
            } else {
                int flags = decor.getSystemUiVisibility();
                if (lightBars) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                } else {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
                decor.setSystemUiVisibility(flags);
            }
        } catch (Throwable t) {
            Diag.w("theme", "applyWindow: " + t);
        }
    }
}
