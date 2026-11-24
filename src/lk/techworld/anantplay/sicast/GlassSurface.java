package lk.techworld.anantplay.sicast;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.view.View;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Shared Mornye-inspired liquid-glass surface tokens for native Android views.
 *
 * <p>Android RenderEffect blurs the view it is attached to; it is not a backdrop
 * sampling API. The app therefore uses it only on its decorative ambient-glow
 * layers, while the actual panel stays crisp and readable above the tint and rim.
 */
public final class GlassSurface {
    private GlassSurface() { }

    private static final Map<View, Spec> SURFACES = new WeakHashMap<>();

    private static final class Spec {
        final int color;
        final int radiusDp;
        final boolean icon;
        Spec(int color, int radiusDp, boolean icon) {
            this.color = color;
            this.radiusDp = radiusDp;
            this.icon = icon;
        }
    }

    public static void apply(View view, SharedPreferences prefs, int color, int radiusDp) {
        if (view == null) return;
        synchronized (SURFACES) { SURFACES.put(view, new Spec(color, radiusDp, false)); }
        paint(view, prefs, color, radiusDp, AppPrefs.glass(prefs));
    }

    public static void applyIcon(View view, SharedPreferences prefs, int color, int radiusDp) {
        if (view == null) return;
        synchronized (SURFACES) { SURFACES.put(view, new Spec(color, radiusDp, true)); }
        paint(view, prefs, color, radiusDp, AppPrefs.iconGlass(prefs));
    }

    public static void clear(View view) {
        if (view == null) return;
        synchronized (SURFACES) { SURFACES.remove(view); }
        view.setBackground(null);
        view.setElevation(0f);
    }

    /** Repaint registered panels after a live clarity or outline setting changes. */
    public static void refreshAll(SharedPreferences prefs) {
        synchronized (SURFACES) {
            for (Map.Entry<View, Spec> item : SURFACES.entrySet()) {
                View view = item.getKey();
                if (view == null) continue;
                Spec spec = item.getValue();
                paint(view, prefs, spec.color, spec.radiusDp,
                    spec.icon ? AppPrefs.iconGlass(prefs) : AppPrefs.glass(prefs));
            }
        }
    }

    public static Drawable drawable(Context context, SharedPreferences prefs, int color,
                                    int radiusDp, boolean enabled) {
        if (context == null) return null;
        float radius = dp(context, Math.max(0, radiusDp));
        GradientDrawable base;
        if (!enabled) {
            base = new GradientDrawable();
            base.setColor(color);
            base.setCornerRadius(radius);
            return base;
        }

        int clarity = AppPrefs.glassClarity(prefs);
        float amount = clarity / 100f;
        String mode = AppPrefs.themeMode(prefs);
        boolean light = AppPrefs.THEME_LIGHT.equals(mode)
            || (AppPrefs.THEME_SYSTEM.equals(mode)
                && (context.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_NO);
        int softened = ThemeEngine.blend(color, light ? 0xFFFFFFFF : 0xFF0B1020,
            light ? 0.10f : 0.08f);
        int high = ThemeEngine.blend(softened, 0xFFFFFFFF, 0.06f + amount * 0.12f);
        int low = ThemeEngine.blend(softened, AppPrefs.accentHexColor(AppPrefs.accentHex(prefs)) != 0
            ? AppPrefs.accentHexColor(AppPrefs.accentHex(prefs)) : ThemeEngine.ACCENTS[AppPrefs.accent(prefs)],
            0.025f + amount * 0.035f);
        base = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{withAlpha(high, (int) (0xA0 + amount * 0x45)),
                      withAlpha(low, (int) (0x90 + amount * 0x50))});
        base.setCornerRadius(radius);

        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{withAlpha(0xFFFFFFFF, (int) (0x0C + amount * 0x20)),
                      withAlpha(0xFFFFFFFF, 0x01),
                      withAlpha(0xFFFFFFFF, 0x00)});
        sheen.setCornerRadius(radius);

        GradientDrawable rim = new GradientDrawable();
        rim.setColor(0x00000000);
        rim.setCornerRadius(radius);
        if (AppPrefs.glassOutlines(prefs) && AppPrefs.borderAlpha(prefs) > 0) {
            int baseAlpha = light ? 0x66 : 0x38;
            float borderStrength = 0.35f + AppPrefs.borderAlpha(prefs) / 60f * 0.65f;
            int alpha = Math.min(0x88, Math.max(0x01,
                Math.round(baseAlpha * borderStrength * (0.35f + amount * 0.65f))));
            int colorAlpha = alpha << 24 | 0x00FFFFFF;
            rim.setStroke(dp(context, 1), colorAlpha);
        }
        return new LayerDrawable(new Drawable[]{base, sheen, rim});
    }

    /**
     * Use only on a decorative glow/backdrop view, never on text or controls.
     * RenderEffect is available from Android 12 (API 31).
     */
    public static void blurAmbient(View glow, SharedPreferences prefs, float baseRadiusDp) {
        if (glow == null || Build.VERSION.SDK_INT < 31 || !AppPrefs.iconGlass(prefs)) return;
        float clarity = AppPrefs.glassClarity(prefs) / 75f;
        float radius = Math.max(1f, dp(glow.getContext(), baseRadiusDp * clarity));
        try {
            glow.setRenderEffect(RenderEffect.createBlurEffect(
                radius, radius, Shader.TileMode.CLAMP));
        } catch (Throwable ignored) {
            // The glass tint/rim remains usable if a vendor renderer rejects blur.
        }
    }

    private static void paint(View view, SharedPreferences prefs, int color,
                              int radiusDp, boolean enabled) {
        view.setBackground(drawable(view.getContext(), prefs, color, radiusDp, enabled));
        view.setClipToOutline(true);
        float clarity = AppPrefs.glassClarity(prefs) / 95f;
        view.setElevation(enabled ? dp(view.getContext(), 1.5f + 3f * clarity) : 0f);
    }

    private static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
