package lk.techworld.anantplay.sicast;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * v3.4.6.l — THE NEXT-LEVEL GESTURE ENGINE (the second module of the modular
 * cinema player; studied clean-room against Next Player's stacked gesture
 * states — NO GPL-3 source copied, the repository stays LGPL-3.0-only).
 *
 * <p>THE FULL SET, every piece individually toggleable through AppPrefs:
 *
 * <ul>
 *   <li><b>single tap</b> — toggle the HUD chrome (220 ms fade);</li>
 *   <li><b>double-tap left / right</b> — cumulative ±seek with a translucent
 *       curved ripple overlay + a stacking "−10 s / −20 s" indicator
 *       (K_DOUBLE_TAP_SEC: 5/10/15/30 s, default 10);</li>
 *   <li><b>left vertical swipe</b> — brightness capsule (icon + progress bar +
 *       percentage, fading out cleanly);</li>
 *   <li><b>right vertical swipe</b> — volume capsule (same design);</li>
 *   <li><b>horizontal swipe anywhere</b> — live scrub pill
 *       ("01:24 / 10:34 (+00:15)" updating the seekbar in real time, seeking
 *       on finger release — the full width spans exactly ±2 minutes);</li>
 *   <li><b>long-press hold</b> — a top-center "2.0x speed" pill while held
 *       (K_LONG_PRESS_SPEED: 1.5x / 2.0x / 2.5x), snapping back on release;</li>
 *   <li><b>two-finger pinch</b> — cycles Fit ↔ Fill with a floating aspect
 *       pill (K_PINCH_ZOOM).</li>
 * </ul>
 *
 * <p>Every gesture while LOCKED is swallowed except the unlock affordance
 * (the host owns the lock overlay; canTap() gates it).
 */
final class CinemaGestures {

    private CinemaGestures() { }

    /** The one surface the gesture engine drives. */
    interface Ops {
        void toggleControls();
        long playerPositionMs();                   // the live playhead
        long playerDurationMs();                   // 0 while metadata resolves
        void seekBy(long ms);                       // the double-tap cumulative lane
        void ripple(boolean right, String label);   // the curved double-tap overlay
        void brightnessBy(float dy);                // left vertical swipe
        void volumeBy(float dy);                    // right vertical swipe
        void brightnessPct(int pct);                // capsule repaint
        void volumePct(int pct);                    // capsule repaint
        void scrubPreview(long targetMs, long deltaMs);  // the live pill text
        void scrubCommit(long targetMs);            // release → seek
        void hideGesturePills();                    // capsules/pills fade
        void holdSpeedStart(float speed);
        void holdSpeedEnd();
        void pinchZoom(boolean out);                // true = spread = Fill
        void pinchLabel(String text);               // the floating aspect pill
    }

    // ── BUILD & WIRE ────────────────────────────────────────────────────────

    static void wire(final Activity a, final FrameLayout root,
                     final SharedPreferences prefs, final Ops ops) {
        final ScaleGestureDetector pinch = new ScaleGestureDetector(a,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                private boolean fired;
                @Override public boolean onScale(ScaleGestureDetector d) {
                    if (!AppPrefs.pinchZoom(prefs)) return false;
                    if (d.getScaleFactor() > 1.15f && !fired) {
                        fired = true;
                        ops.pinchZoom(true);
                        return true;
                    }
                    if (d.getScaleFactor() < 0.87f && !fired) {
                        fired = false;
                        ops.pinchZoom(false);
                        return true;
                    }
                    return false;
                }
                @Override public void onScaleEnd(ScaleGestureDetector d) { fired = false; }
            });

        // the double-tap cumulative lane (−10s, −20s, −30s … within 900 ms)
        final long[] lastTapAt = {0L};
        final int[] tapStreak = {0};
        final long[] tapDelta = {0L};

        final GestureDetector gd = new GestureDetector(a, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                ops.toggleControls();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (!AppPrefs.doubleTapSeek(prefs)) { ops.toggleControls(); return true; }
                long stepMs = AppPrefs.doubleTapSec(prefs) * 1000L;
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastTapAt[0] > 900L) { tapStreak[0] = 0; tapDelta[0] = 0L; }
                lastTapAt[0] = now;
                boolean right = e.getX() > root.getWidth() / 2f;
                tapStreak[0]++;
                tapDelta[0] += right ? stepMs : -stepMs;
                ops.seekBy(tapDelta[0]);
                ops.ripple(right, (right ? "+" : "−") + (Math.abs(tapDelta[0]) / 1000) + " s");
                return true;
            }
            @Override public boolean onDown(MotionEvent e) { return true; }
        });

        // the swipe state machine
        final float[] startY = {0f}, startX = {0f};
        final long[] startPos = {0L}, previewTo = {-1L};
        final int[] mode = {0};   // 0 none · 1 brightness · 2 volume · 3 scrub

        // the long-press hold lane (550 ms, cancelled by any real movement)
        final Runnable holdStart = () -> {
            float speed = AppPrefs.longPressSpeedPerMille(prefs) / 1000f;
            ops.holdSpeedStart(speed);
        };

        root.setOnTouchListener((v, e) -> {
            pinch.onTouchEvent(e);
            gd.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX[0] = e.getX();
                    startY[0] = e.getY();
                    startPos[0] = ops.playerPositionMs();
                    previewTo[0] = -1;
                    mode[0] = 0;
                    v.postDelayed(holdStart, 550);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - startX[0], dy = e.getY() - startY[0];
                    if (mode[0] == 0) {
                        if (Math.abs(dy) > UiCard.dp(v.getContext(), 18) && Math.abs(dy) > Math.abs(dx) * 1.4f) {
                            boolean right = startX[0] > root.getWidth() / 2f;
                            if (right && AppPrefs.swipeVolume(prefs)) mode[0] = 2;
                            else if (!right && AppPrefs.swipeBrightness(prefs)) mode[0] = 1;
                            else if (AppPrefs.gestures(prefs)) mode[0] = right ? 2 : 1;
                        } else if (Math.abs(dx) > UiCard.dp(v.getContext(), 24) && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                            if (AppPrefs.swipeSeek(prefs)) mode[0] = 3;
                        }
                    }
                    // any real movement means the user is swiping, not holding
                    if (Math.abs(dx) > UiCard.dp(v.getContext(), 16) || Math.abs(dy) > UiCard.dp(v.getContext(), 16)) {
                        v.removeCallbacks(holdStart);
                        ops.holdSpeedEnd();
                    }
                    if (mode[0] == 1) {
                        ops.brightnessBy(dy - UiCard.dp(v.getContext(), 40));
                    } else if (mode[0] == 2) {
                        ops.volumeBy(dy - UiCard.dp(v.getContext(), 40));
                    } else if (mode[0] == 3) {
                        long dur = ops.playerDurationMs();
                        long delta = (long) ((dx / Math.max(1f, root.getWidth())) * 120000L);
                        long to = Math.max(0, Math.min(dur > 0 ? dur : Long.MAX_VALUE, startPos[0] + delta));
                        previewTo[0] = to;
                        ops.scrubPreview(to, delta);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (mode[0] == 3 && previewTo[0] >= 0) ops.scrubCommit(previewTo[0]);
                    if (mode[0] != 0) ops.hideGesturePills();
                    mode[0] = 0;
                    v.removeCallbacks(holdStart);
                    ops.holdSpeedEnd();
                    return true;
                default:
                    return false;
            }
        });
    }

    // ── the gesture feedback views (built once by the host) ───────────────────

    /**
     * The live scrub pill: "01:24 / 10:34 (+00:15)" — the centre-top floating
     * text nextplayer renders, with the delta signed and tabular.
     */
    static TextView scrubPill(Activity a) {
        TextView t = pill(a, 13f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL), Typeface.BOLD);
        return t;
    }

    /** The long-press speed pill: "2.0x speed" (a pill, no emoji glyph). */
    static TextView speedPill(Activity a) {
        TextView t = pill(a, 13.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** The pinch aspect pill: "Fit" / "Fill (Zoom)". */
    static TextView aspectPill(Activity a) {
        return pill(a, 13f);
    }

    private static TextView pill(Activity a, float sp) {
        TextView t = new TextView(a);
        t.setTextColor(Color.WHITE);
        t.setTextSize(sp);
        t.setGravity(Gravity.CENTER);
        t.setPadding(UiCard.dp(a, 16), UiCard.dp(a, 9), UiCard.dp(a, 16), UiCard.dp(a, 9));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xD91B2432);
        g.setCornerRadius(UiCard.dp(a, 16));
        g.setStroke(UiCard.dp(a, 1), 0x33FFFFFF);
        t.setBackground(g);
        t.setVisibility(View.GONE);
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return t;
    }

    /** The pill's gravity params (host mounts them on the root FrameLayout). */
    static FrameLayout.LayoutParams pillLp(Activity a, int gravity) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, gravity);
        return lp;
    }
}
