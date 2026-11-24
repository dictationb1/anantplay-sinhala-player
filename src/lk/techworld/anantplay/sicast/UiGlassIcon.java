package lk.techworld.anantplay.sicast;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

/** Accessible glass medallions with a centered, theme-tinted vector glyph. */
public final class UiGlassIcon {
    private UiGlassIcon() { }

    public static FrameLayout create(Context context, SharedPreferences prefs, int drawableId,
                                    int tint, int glyphDp, int targetDp, String description,
                                    View.OnClickListener listener) {
        int target = Math.max(targetDp, listener == null ? 32 : 44);
        FrameLayout medallion = new FrameLayout(context);
        medallion.setMinimumWidth(UiTokens.dp(context, target));
        medallion.setMinimumHeight(UiTokens.dp(context, target));
        medallion.setFocusable(listener != null);
        medallion.setClickable(listener != null);
        medallion.setContentDescription(description);

        int accent = tint == 0 ? 0xFF8B5CF6 : tint;
        int base = ThemeEngine.blend(accent, 0xFF0B1020, 0.62f);
        GlassSurface.applyIcon(medallion, prefs, base, target / 2);

        if (AppPrefs.iconGlass(prefs)) {
            View glow = new View(context);
            GradientDrawable glowFill = new GradientDrawable();
            glowFill.setShape(GradientDrawable.OVAL);
            glowFill.setColor((0x24 << 24) | (accent & 0x00FFFFFF));
            glow.setBackground(glowFill);
            FrameLayout.LayoutParams glowLp = new FrameLayout.LayoutParams(
                UiTokens.dp(context, target * 0.72f), UiTokens.dp(context, target * 0.72f),
                Gravity.CENTER);
            medallion.addView(glow, glowLp);
            GlassSurface.blurAmbient(glow, prefs, 5f);
        }

        ImageView glyph = new ImageView(context);
        glyph.setImageDrawable(UiIcons.tinted(context, drawableId, tint, glyphDp));
        glyph.setScaleType(ImageView.ScaleType.FIT_CENTER);
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams glyphLp = new FrameLayout.LayoutParams(
            UiTokens.dp(context, glyphDp), UiTokens.dp(context, glyphDp), Gravity.CENTER);
        medallion.addView(glyph, glyphLp);
        if (listener != null) {
            medallion.setOnClickListener(listener);
            medallion.setOnTouchListener((view, event) -> {
                if (!AppPrefs.motionEnabled(prefs)) return false;
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    view.animate().scaleX(0.93f).scaleY(0.93f).setDuration(110)
                        .setInterpolator(new DecelerateInterpolator()).start();
                } else if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(280)
                        .setInterpolator(new OvershootInterpolator(0.7f)).start();
                }
                return false;
            });
        }
        return medallion;
    }
}
