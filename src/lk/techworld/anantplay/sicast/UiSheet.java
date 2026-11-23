package lk.techworld.anantplay.sicast;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/**
 * v3.4.0 - a modal bottom sheet with a drag handle, a scrim and drag-to-dismiss
 * (the Mornye {@code app_bottom_sheet} contract), built with no dependency at all.
 *
 * <p>It is an OVERLAY on the activity's content view, not a Dialog, so the cinema
 * player keeps rendering behind it - which is exactly what the live subtitle restyle
 * needs: the video and the subtitle line stay visible above the sheet while a slider
 * moves.
 *
 * <p>Insets: the panel pads its bottom by {@code navigationBars()} so the last row is
 * never under the gesture bar on Android 16.
 */
final class UiSheet {

    private UiSheet() { }

    /** Holds the overlay so a caller can dismiss it programmatically. */
    static final class Handle {
        final FrameLayout overlay;
        final View panel;
        final View scrim;
        final Activity activity;
        Handle(Activity a, FrameLayout o, View p, View s) {
            activity = a; overlay = o; panel = p; scrim = s;
        }
        public void dismiss() { close(activity, overlay, panel, scrim, null); }
    }

    /**
     * Shows a sheet.
     *
     * @param content   the sheet body (already padded by the caller if it wants)
     * @param heightPct 0..100 of the screen height (60 keeps the video visible)
     * @param onDismiss run after the close animation, or null
     */
    static Handle show(Activity a, ThemeEngine.Palette p, SharedPreferences prefs,
                       View content, int heightPct, Runnable onDismiss) {
        if (a == null || a.isFinishing()) return null;
        ViewGroup root = a.findViewById(android.R.id.content);
        if (root == null) return null;

        final FrameLayout overlay = new FrameLayout(a);

        final View scrim = new View(a);
        scrim.setBackgroundColor(0xB3000000);
        scrim.setAlpha(0f);
        overlay.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        final LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(UiCard.sheetDrawable(a, p, prefs));
        panel.setElevation(UiCard.dp(a, 16));

        // the drag handle
        FrameLayout handleWrap = new FrameLayout(a);
        View handle = new View(a);
        GradientDrawable hg = new GradientDrawable();
        hg.setColor(p.desc);
        hg.setCornerRadius(UiCard.dp(a, 999));
        handle.setBackground(hg);
        handle.setAlpha(0.55f);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(UiCard.dp(a, 36), UiCard.dp(a, 4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = UiCard.dp(a, 10);
        handleWrap.addView(handle, hlp);
        panel.addView(handleWrap, new LinearLayout.LayoutParams(-1, UiCard.dp(a, 20)));

        ScrollView sc = new ScrollView(a);
        sc.setClipToPadding(false);
        sc.addView(content, new FrameLayout.LayoutParams(-1, -2));
        panel.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1f));

        int screenH = a.getResources().getDisplayMetrics().heightPixels;
        int target = Math.round(screenH * Math.max(20, Math.min(95, heightPct)) / 100f);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-1, target, Gravity.BOTTOM);
        overlay.addView(panel, plp);

        // bottom inset for the gesture bar
        panel.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets nav = insets.getInsets(android.view.WindowInsets.Type.navigationBars());
            v.setPadding(0, 0, 0, nav.bottom + UiCard.dp(a, 10));
            return insets;
        });

        // drag to dismiss
        final float[] startY = new float[1];
        final boolean[] dragging = new boolean[1];
        panel.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startY[0] = e.getRawY();
                    dragging[0] = false;
                    return false;
                case MotionEvent.ACTION_MOVE: {
                    float dy = e.getRawY() - startY[0];
                    if (dy > UiCard.dp(a, 8)) dragging[0] = true;
                    if (dragging[0]) {
                        v.setTranslationY(Math.max(0, dy));
                    }
                    return dragging[0];
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float dy = v.getTranslationY();
                    if (dy > target * 0.25f) {
                        close(a, overlay, panel, scrim, onDismiss);
                    } else {
                        v.animate().translationY(0).setDuration(UiTokens.MOTION_MEDIUM)
                            .setInterpolator(new DecelerateInterpolator()).start();
                    }
                    dragging[0] = false;
                    return true;
                }
                default:
                    return false;
            }
        });

        scrim.setOnClickListener(v -> close(a, overlay, panel, scrim, onDismiss));

        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        panel.setTranslationY(target);
        panel.animate().translationY(0).setDuration(UiTokens.MOTION_MEDIUM)
            .setInterpolator(new DecelerateInterpolator()).start();
        scrim.animate().alpha(1f).setDuration(UiTokens.MOTION_MEDIUM).start();
        return new Handle(a, overlay, panel, scrim);
    }

    private static void close(Activity a, FrameLayout overlay, View panel, View scrim, Runnable after) {
        if (overlay == null || overlay.getParent() == null) {
            if (after != null) after.run();
            return;
        }
        panel.animate().translationY(panel.getHeight()).setDuration(UiTokens.MOTION_MEDIUM)
            .setInterpolator(new DecelerateInterpolator()).start();
        scrim.animate().alpha(0f).setDuration(UiTokens.MOTION_MEDIUM).withEndAction(() -> {
            try {
                ((ViewGroup) overlay.getParent()).removeView(overlay);
            } catch (Throwable t) {
                Diag.w("sheet", "remove: " + t);
            }
            if (after != null) after.run();
        }).start();
    }

    /** A titled sheet header: bold title + optional subtitle. */
    static LinearLayout header(Activity a, ThemeEngine.Palette p, String title, String subtitle) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiCard.dp(a, 18), UiCard.dp(a, 8), UiCard.dp(a, 18), UiCard.dp(a, 8));
        android.widget.TextView t = new android.widget.TextView(a);
        t.setText(title);
        t.setTextColor(p.title);
        UiTokens.screenTitle(t);
        box.addView(t, new LinearLayout.LayoutParams(-1, -2));
        if (subtitle != null && !subtitle.isEmpty()) {
            android.widget.TextView s = new android.widget.TextView(a);
            s.setText(subtitle);
            s.setTextColor(p.desc);
            UiTokens.bodySecondary(s);
            s.setPadding(0, UiCard.dp(a, 3), 0, 0);
            box.addView(s, new LinearLayout.LayoutParams(-1, -2));
        }
        return box;
    }
}
