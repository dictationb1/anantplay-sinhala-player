package lk.techworld.anantplay.sicast;

import android.animation.ValueAnimator;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.TranslateAnimation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * v3.4.6.i — THE XRECORDER-STYLE TRANSLUCENT PERMISSION GUIDE OVERLAY
 * (video_1 00:06–00:20).
 *
 * <p>When the app sends the user to ANY Android System Settings screen, this
 * activity floats a sleek translucent dark card RIGHT ON TOP of the settings
 * page: an animated step mockup (app icon + scrolling list / a glowing toggle
 * sliding ON / an upward arrow) plus one crisp instruction line. Tapping
 * anywhere (or swiping up) dismisses the card so the user can interact with
 * the settings page directly underneath.
 *
 * <h2>The Android-16 launch trick (works EVEN BEFORE overlay permission)</h2>
 * The overlay needs NO {@code SYSTEM_ALERT_WINDOW} grant because it is a
 * NORMAL translucent activity in OUR OWN task stack —
 * {@code Theme.Translucent.NoTitleBar} + {@code windowIsFloating} — launched
 * through the {@link #stackedLaunch} two-intent helper:
 * {@code startActivities(new Intent[]{ settingsIntent, guideIntent })} puts
 * the system settings page underneath and the guide card on top, in one
 * back-stack — Back from the card lands ON the settings page (never skips it),
 * and Back from the settings returns to the app as before. This is exactly
 * how XRecorder / ScreenRecorder-style OEM guides float over settings.
 *
 * <h2>The four supported scenarios ({@code ?guide=})</h2>
 * <ul>
 *   <li>{@code overlay} — "Allow display over other apps" (a mock row with the
 *       app icon and a toggle sliding ON);</li>
 *   <li>{@code unknown_sources} — "Allow from this source" (a mock toggle
 *       sliding ON + the Xiaomi confirmation note);</li>
 *   <li>{@code battery} — "No restrictions" (a mock list highlighting the
 *       selected "No restrictions ✓" row);</li>
 *   <li>{@code notifications} — "Show notifications" (a mock toggle ON).</li>
 * </ul>
 *
 * <p>The activity is {@code excludeFromRecents}, {@code noHistory} and
 * self-dismissing — it can never pollute the recents list or the back stack.
 */
public class PermissionGuideActivity extends Activity {

    /** The guide scenario extra ({@code overlay|unknown_sources|battery|notifications}). */
    static final String EXTRA_GUIDE = "guide";

    public static final String GUIDE_OVERLAY = "overlay";
    public static final String GUIDE_UNKNOWN_SOURCES = "unknown_sources";
    public static final String GUIDE_BATTERY = "battery";
    public static final String GUIDE_NOTIFICATIONS = "notifications";

    private static final int PAGE_BG = 0xE60D1117;      // 90% opaque dark glass
    private static final int CARD_BG = 0xF20F172A;
    private static final int TEXT_MAIN = 0xFFF2F7F7;
    private static final int TEXT_DIM = 0xFF94A3B8;
    private static final int ACCENT = 0xFF7FCDD0;
    private static final int GREEN = 0xFF63D668;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            Window w = getWindow();
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.0f;               // the settings page stays visible beneath
            lp.windowAnimations = 0;
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            w.setAttributes(lp);
            String guide = getIntent() == null
                    ? GUIDE_OVERLAY : getIntent().getStringExtra(EXTRA_GUIDE);
            setContentView(buildCard(guide == null ? GUIDE_OVERLAY : guide));
        } catch (Throwable t) {
            Diag.w("guide", "overlay build refused: " + t);
            finish();
        }
    }

    /** Tap anywhere → dismiss (the card fades; the settings page below stays). */
    @Override
    public void onBackPressed() {
        try { animateOutThenFinish(); } catch (Throwable t) { super.onBackPressed(); }
    }

    // ── the static launch API (every permission launch site uses this) ────────

    /**
     * THE one-call helper: opens the system settings intent with THIS guide
     * card floating on top ({@code startActivities} = one back-stack).
     * Any failure of the settings intent itself degrades to the plain
     * (unguided) launch — the guide can never block the permission path.
     */
    public static boolean stackedLaunch(Context c, Intent settings, String guide) {
        // v3.4.6.k — THE NON-DISRUPTIVE LAW: a live troubleshoot run NEVER
        // opens a system settings page (nor stacks the guide over one) — the
        // Part_03 04:18 battery-settings hijack kill, enforced at the ONE
        // launcher every open*WithGuide lane funnels through.
        if (DeepProbe.probeRunActive()) {
            Diag.i("guide", "stacked launch refused while the troubleshoot run is active");
            Troubleshooter.record("TROUBLESHOOT", "permission-guide launch refused during the probe run");
            return false;
        }
        try {
            if (settings == null) return false;
            if (!(c instanceof Activity)) settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Intent guideIntent = forGuide(c, guide);
            try {
                c.startActivities(new Intent[]{ settings, guideIntent });
                return true;
            } catch (Throwable stackedRefused) {
                // (some OEMs refuse multi-intent starts) — the card alone, on top.
                try {
                    c.startActivity(guideIntent);
                } catch (Throwable ignored) { }
                c.startActivity(settings);
                return true;
            }
        } catch (Throwable t) {
            Diag.w("guide", "stacked launch: " + t);
            return false;
        }
    }

    /** The bare guide card intent (registered in AndroidManifest.xml). */
    public static Intent forGuide(Context c, String guide) {
        Intent i = new Intent(c, PermissionGuideActivity.class);
        i.putExtra(EXTRA_GUIDE, guide == null ? GUIDE_OVERLAY : guide);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_NO_HISTORY
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return i;
    }

    /** overlay: the MANAGE_OVERLAY_PERMISSION page + the guide card. */
    public static boolean openOverlayWithGuide(Context c) {
        Intent settings = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + c.getPackageName()));
        if (stackedLaunch(c, settings, GUIDE_OVERLAY)) return true;
        Miui.openOverlay(c);                       // the legacy unguided fallback
        return false;
    }

    /** unknown_sources: the MANAGE_UNKNOWN_APP_SOURCES page + the guide card. */
    public static boolean openUnknownSourcesWithGuide(Context c) {
        Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:" + c.getPackageName()));
        if (stackedLaunch(c, settings, GUIDE_UNKNOWN_SOURCES)) return true;
        return AnantPlayInstaller.openUnknownSources(c);   // the legacy fallback
    }

    /** battery: the IGNORE_BATTERY_OPTIMIZATION settings page + the guide card. */
    public static boolean openBatteryWithGuide(Context c) {
        Intent settings = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        if (stackedLaunch(c, settings, GUIDE_BATTERY)) return true;
        Miui.requestBattery(c);                           // the legacy fallback
        return false;
    }

    /** notifications: the APP_NOTIFICATION_SETTINGS page + the guide card. */
    public static boolean openNotificationsWithGuide(Context c) {
        Intent settings = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
        if (stackedLaunch(c, settings, GUIDE_NOTIFICATIONS)) return true;
        return false;
    }

    // ── the card UI (pure framework widgets, zero XML — keeps res/ clean) ─────

    private View buildCard(String guide) {
        final boolean isOverlay = GUIDE_OVERLAY.equals(guide);
        final boolean isUnknown = GUIDE_UNKNOWN_SOURCES.equals(guide);
        final boolean isBattery = GUIDE_BATTERY.equals(guide);
        final boolean isNotif = GUIDE_NOTIFICATIONS.equals(guide);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x40060810);        // a 25% veil over the page
        // tap ANYWHERE (or swipe up) → dismiss and use the page underneath
        root.setOnClickListener(v -> animateOutThenFinish());

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(CARD_BG);
        cardBg.setCornerRadius(dp(26));
        cardBg.setStroke(dp(1), 0x337FCDD0);
        card.setBackground(cardBg);
        int pad = dp(20);
        card.setPadding(pad, pad, pad, pad);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int margin = dp(18);
        cardLp.leftMargin = margin;
        cardLp.rightMargin = margin;
        cardLp.topMargin = margin;
        cardLp.bottomMargin = margin;
        cardLp.gravity = Gravity.CENTER;
        root.addView(card, cardLp);

        // ── the animated step mockup (the hero of the card) ──────────────────
        card.addView(mockScene(isOverlay, isUnknown, isBattery, isNotif),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(spacer(12));

        // ── the crisp instruction line ─────────────────────────────────────
        String instruction = isOverlay
                ? "Scroll down to find AnantPlay Player and turn ON 'Allow display over other apps'"
                : isUnknown
                    ? "Turn ON 'Allow from this source' so AnantPlay Player can install updates"
                    : isBattery
                        ? "Select 'No restrictions' so Cast never sleeps in the background"
                        : "Turn ON 'Show notifications' so Cast alerts always arrive";
        TextView text = new TextView(this);
        text.setText(instruction);
        setBody(text, TEXT_MAIN, 14f, true);
        text.setLineSpacing(dp(3), 1.1f);
        card.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── the dismiss hint row: the upward arrow + "tap to dismiss" ───────
        card.addView(spacer(10));
        LinearLayout hint = new LinearLayout(this);
        hint.setOrientation(LinearLayout.HORIZONTAL);
        hint.setGravity(Gravity.CENTER_VERTICAL);
        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_stat_si);
        arrow.setColorFilter(ACCENT);
        arrow.setLayoutParams(new LinearLayout.LayoutParams(dp(16), dp(16)));
        // the animated upward arrow — a looping small lift.
        TranslateAnimation lift = new TranslateAnimation(0, 0, dp(4), -dp(4));
        lift.setDuration(700);
        lift.setRepeatMode(Animation.REVERSE);
        lift.setRepeatCount(Animation.INFINITE);
        arrow.startAnimation(lift);
        hint.addView(arrow);
        hint.addView(spacerH(6));
        TextView tap = new TextView(this);
        tap.setText("tap anywhere or swipe up to dismiss");
        setBody(tap, TEXT_DIM, 11.5f, false);
        hint.addView(tap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── the card itself slides in from the bottom + fades ───────────────
        card.post(() -> {
            try {
                TranslateAnimation in = new TranslateAnimation(0, 0, dp(28), 0);
                in.setDuration(240);
                card.startAnimation(in);
            } catch (Throwable ignored) { }
        });
        return root;
    }

    /**
     * THE animated visual mockup: a mini settings-page card with the app icon
     * row / the glowing toggle that slides ON (or the list with the selected
     * row) — deliberately built from plain widgets so it needs zero layouts.
     */
    private View mockScene(boolean overlay, boolean unknown, boolean battery, boolean notif) {
        LinearLayout scene = new LinearLayout(this);
        scene.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable sceneBg = new GradientDrawable();
        sceneBg.setColor(0xFF1C2433);
        sceneBg.setCornerRadius(dp(18));
        scene.setBackground(sceneBg);
        int inner = dp(14);
        scene.setPadding(inner, inner, inner, inner);

        if (overlay || notif) {
            // The classic "app row + toggle" mockup: [icon] AnantPlay Player [switch]
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            ImageView icon = new ImageView(this);
            icon.setImageResource(R.drawable.ic_stat_si);
            icon.setColorFilter(ACCENT);
            icon.setLayoutParams(new LinearLayout.LayoutParams(dp(26), dp(26)));
            row.addView(icon);
            row.addView(spacerH(10));
            TextView label = new TextView(this);
            label.setText(overlay ? "AnantPlay Player" : "Show notifications");
            setBody(label, TEXT_MAIN, 13.5f, true);
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(spacerH(10));
            row.addView(animatedToggle(overlay
                    ? "Allow display over other apps"
                    : "Show notifications"));
            scene.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (overlay) {
                scene.addView(spacer(6));
                TextView sub = new TextView(this);
                sub.setText("Allow display over other apps");
                setBody(sub, TEXT_DIM, 11.5f, false);
                scene.addView(sub, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        } else if (unknown) {
            // "Allow from this source" toggle sliding ON + the Xiaomi note.
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(this);
            title.setText("Install unknown apps");
            setBody(title, TEXT_DIM, 11.5f, false);
            row.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(spacer(6));
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            TextView src = new TextView(this);
            src.setText("AnantPlay Player");
            setBody(src, TEXT_MAIN, 13.5f, true);
            line.addView(src, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            line.addView(animatedToggle("Allow from this source"));
            row.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            scene.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            scene.addView(spacer(8));
            TextView xiaomi = new TextView(this);
            xiaomi.setText("Xiaomi may ask once more after you flip it — that's normal");
            setBody(xiaomi, 0xFFE9B44C, 11f, false);
            scene.addView(xiaomi, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            // battery: the mock list with "No restrictions ✓" highlighted.
            String[] items = { "No restrictions", "Battery saver (recommended)", "Restrict background" };
            for (int i = 0; i < items.length; i++) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                boolean selected = i == 0;
                TextView item = new TextView(this);
                item.setText(items[i] + (selected ? "  ✓" : ""));
                setBody(item, selected ? GREEN : TEXT_DIM, 13f, selected);
                if (selected) {
                    // v3.4.6.j — crisp, high-contrast selection (the old
                    // 15%-alpha green was invisible against the dark card).
                    GradientDrawable hl = new GradientDrawable();
                    hl.setColor(0x663FBF69);
                    hl.setStroke(dp(2), GREEN);
                    hl.setCornerRadius(dp(10));
                    row.setBackground(hl);
                    item.setTextColor(0xFFD8FFD9);
                }
                row.setPadding(dp(10), dp(8), dp(10), dp(8));
                scene.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                if (i < items.length - 1) scene.addView(spacer(2));
            }
            // A second, unmistakable visual ON state: the same crisp switch
            // used by the overlay/install guides, labelled for this scenario.
            LinearLayout state = new LinearLayout(this);
            state.setOrientation(LinearLayout.HORIZONTAL);
            state.setGravity(Gravity.CENTER_VERTICAL);
            state.setPadding(dp(10), dp(6), dp(10), dp(4));
            TextView stateLabel = new TextView(this);
            stateLabel.setText("Battery optimization: OFF");
            setBody(stateLabel, 0xFFD8FFD9, 12.5f, true);
            state.addView(stateLabel, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            state.addView(animatedToggle("No restrictions is ON"));
            scene.addView(state, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return scene;
    }

    /**
     * v3.4.6.k — THE BOLD ANIMATED TOGGLE GRAPHIC (Part_01 00:26–00:40): a
     * fully custom-drawn, unmistakably visible toggle switch — the rounded
     * green track, the glowing white thumb that SLIDES on with a springy
     * overshoot, a tapping RIPPLE ring around it, and a POINTER arrow above
     * it that follows the thumb — replacing the v3.4.6.j system Switch whose
     * tiny thumb read as a text-only box on the real phone.
     */
    private View animatedToggle(String description) {
        GuideToggleView tv = new GuideToggleView(this);
        tv.setContentDescription(description);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(64), dp(52));
        tv.setLayoutParams(lp);
        return tv;
    }

    /**
     * The custom-drawn animated toggle: rounded track + glowing sliding thumb
     * + tapping ripple + following pointer arrow, all in ONE self-animating
     * view (zero layouts, zero system widgets, always visible).
     */
    private static final class GuideToggleView extends View {
        private final ValueAnimator slide;    // 0 (OFF side) … 1 (ON side), springy
        private final ValueAnimator ripple;   // 0 … 1 ripple ring around the thumb
        private float slideT, rippleT;
        private final android.graphics.Paint track = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint thumb = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint glow = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint ring = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint pointer = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        GuideToggleView(Context c) {
            super(c);
            // the slide: a smooth OFF→ON sweep with a springy settle (overshoot
            // interpolator), reversing with a hold at ON so the ON state reads.
            slide = ValueAnimator.ofFloat(0f, 1f);
            slide.setDuration(900);
            slide.setInterpolator(new android.view.animation.OvershootInterpolator(1.6f));
            slide.setRepeatCount(ValueAnimator.INFINITE);
            slide.setRepeatMode(ValueAnimator.REVERSE);
            slide.addUpdateListener(a -> {
                slideT = (float) a.getAnimatedValue();
                invalidate();
            });
            // the ripple: the tapping ring pulses out from the thumb.
            ripple = ValueAnimator.ofFloat(0f, 1f);
            ripple.setDuration(900);
            ripple.setRepeatCount(ValueAnimator.INFINITE);
            ripple.setRepeatMode(ValueAnimator.REVERSE);
            ripple.addUpdateListener(a -> {
                rippleT = (float) a.getAnimatedValue();
                invalidate();
            });
            try {
                track.setColor(0xFF2FA96C);            // the bold ON green track
                thumb.setColor(0xFFFFFFFF);            // the crisp white thumb
                glow.setShader(null);
                glow.setColor(0x662FA96C);             // the green halo
                ring.setColor(0xFFFFFFFF);             // the tapping ripple ring
                ring.setStyle(android.graphics.Paint.Style.STROKE);
                ring.setStrokeWidth(3f);
                pointer.setColor(0xFFFFFFFF);          // the following arrow
                pointer.setStyle(android.graphics.Paint.Style.FILL);
            } catch (Throwable ignored) { }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            try {
                slide.start();
                ripple.start();
            } catch (Throwable ignored) { }
        }

        @Override
        protected void onDetachedFromWindow() {
            try {
                slide.cancel();
                ripple.cancel();
            } catch (Throwable ignored) { }
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            try {
                final float w = getWidth(), h = getHeight();
                if (w <= 0 || h <= 0) return;
                final float arrowH = h * 0.26f;                  // the pointer band
                final float trackTop = arrowH + h * 0.08f;
                final float trackH = h - trackTop - h * 0.08f;
                final float trackW = w;
                final float r = trackH / 2f;
                // ── the rounded track (bold ON green, darker OFF tail) ──────
                android.graphics.RectF trackRect = new android.graphics.RectF(0, trackTop, trackW, trackTop + trackH);
                track.setColor(0xFF2FA96C);
                canvas.drawRoundRect(trackRect, r, r, track);
                track.setColor(0x332FA96C);
                android.graphics.RectF offPart = new android.graphics.RectF(
                        0, trackTop, trackW * (1f - Math.max(0f, slideT)), trackTop + trackH);
                if (offPart.right > offPart.left)
                    canvas.drawRoundRect(offPart, r, r, track);
                // ── the thumb: slides left→right with the springy sweep ────
                final float cx = r + (trackW - 2 * r) * Math.max(0f, Math.min(1f, slideT));
                final float cy = trackTop + r;
                final float thumbR = r * 0.82f;
                // the green glow halo behind the thumb
                glow.setAlpha((int) (0.45f * 255 * (0.4f + 0.6f * slideT)));
                canvas.drawCircle(cx, cy, thumbR * 1.75f + rippleT * thumbR, glow);
                // the tapping ripple ring (pulses out from the thumb)
                ring.setAlpha((int) (255 * (1f - rippleT)));
                canvas.drawCircle(cx, cy, thumbR * (1.15f + rippleT * 0.9f), ring);
                // the crisp white thumb
                thumb.setShadowLayer(6f, 0f, 2f, 0x40000000);
                canvas.drawCircle(cx, cy, thumbR, thumb);
                // ── the pointer arrow above the thumb (follows it) ─────────
                final float px = Math.max(r, Math.min(trackW - r, cx));
                final float py = trackTop - h * 0.05f;
                android.graphics.Path p = new android.graphics.Path();
                float aw = h * 0.11f;                      // half width
                float ah = h * 0.13f;                      // height
                p.moveTo(px - aw, py - ah);
                p.lineTo(px + aw, py - ah);
                p.lineTo(px, py);
                p.close();
                pointer.setAlpha((int) (255 * (0.55f + 0.45f * slideT)));
                canvas.drawPath(p, pointer);
            } catch (Throwable ignored) { }
        }
    }

    private void setBody(TextView t, int color, float sizeSp, boolean bold) {
        t.setTextColor(color);
        t.setTextSize(sizeSp);
        t.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        t.setSingleLine(false);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setMaxLines(3);
    }

    private View spacer(int hDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(hDp)));
        return v;
    }

    private View spacerH(int wDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(wDp), 1));
        return v;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void animateOutThenFinish() {
        try {
            View content = findViewById(android.R.id.content);
            if (content != null) {
                AlphaAnimation fade = new AlphaAnimation(1f, 0f);
                fade.setDuration(160);
                fade.setAnimationListener(new android.view.animation.Animation.AnimationListener() {
                    @Override public void onAnimationStart(Animation a) { }
                    @Override public void onAnimationRepeat(Animation a) { }
                    @Override public void onAnimationEnd(Animation a) { finish(); }
                });
                content.startAnimation(fade);
            } else {
                finish();
            }
        } catch (Throwable t) {
            finish();
        }
    }
}
