package lk.techworld.anantplay.sicast;

import android.animation.ValueAnimator;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * v3.4.6.k — THE FLOATING ANANTPLAY SETUP COACH, REBUILT TO THE RED-PEN SPECS
 * (Part_01 01:40–05:16, Part_02 03:38–06:01, Part_03 00:00–02:47).
 *
 * Official AnantPlay (me.champdev.weblock) is installed and updated directly
 * from inside our app; the LAST mile of the guided setup — adding our index
 * URLs inside ANANTPLAY's OWN Settings — rides this floating, draggable glass
 * coach card over AnantPlay itself, built to the user's exact audit:
 *
 *   · DEFAULT POSITION: anchored at the BOTTOM of the screen; it lifts to
 *     the TOP automatically on the URL-paste steps (a dialog/keyboard is
 *     opening there), and stays 100% smoothly draggable everywhere in
 *     between (Part_01 01:40);
 *   · NO `✕` (Close) and NO `—` (Minimize) buttons exist anywhere — only
 *     clean step navigation (Back / Next step / the final Finish Setup ✓)
 *     with sleek glass styling (Part_01 01:52);
 *   · EVERY step renders a GLOWING ANIMATED DIRECTIONAL ARROW indicator with
 *     the exact audit labels — `↗ Top-right ⚙`, `↓ Tap Sources`, `↓ Tap Add
 *     this index`, `↓ Tap Add on all 3 rows`, `↓ Tap Add an index by link`,
 *     `↓ Paste into URL box`, `↓ Tap Add on BOTH Sinhala Subtitle Bridge
 *     rows`, `↓ Enable WARP · Ad block · Address bar`, `↓ Enable Hide
 *     offline + Add MyTV .m3u + Check channels`, `↓ Player settings → Finish
 *     Setup ✓` (Part_02 03:38, Part_03 02:55);
 *   · ONE-TAP COPY pills for BOTH index JSONs (Sri Lanka IPTV + the Sinhala
 *     Subtitle Bridge) AND every user-added custom index URL from Settings →
 *     AnantPlay & Sources (Part_03 02:09);
 *   · the overlay uses the `Display over other apps` grant we already ask
 *     for in onboarding (SYSTEM_ALERT_WINDOW, TYPE_APPLICATION_OVERLAY) —
 *     ZERO AccessibilityService, exactly like every other surface;
 *   · lightweight by Law #4 discipline: ONE view tree, ONE 4 Hz alpha pulse
 *     on the arrow (a ValueAnimator invalidating a 40 dp arrow view), ZERO
 *     blur shaders, ~0 CPU while the user follows a step.
 *
 * Started from: Onboarding slide 6 ("Open AnantPlay with User Guide") and
 * Settings → AnantPlay & Sources → "Guided AnantPlay Setup" — the
 * openOfficialWithCoach channel (MainActivity) starts the coach and launches
 * official AnantPlay in one tap.
 */
public class AnantPlayCoachService extends Service {

    /** The two built-in index JSONs every guided setup needs (both copyable). */
    static final String URL_SL_IPTV =
            "https://raw.githubusercontent.com/dictationb1/srilanka-anantplay/main/index.json";
    static final String URL_SI_SUBS_BRIDGE =
            "https://anantplay-si-subs-bridge.techworldlk.workers.dev/index.json";

    // ── the guided steps, verbatim from the audit's red-pen walkthrough ──────
    // Each step: the bold title, the instruction body, the ARROW direction
    // (the glowing indicator), and the copyable URLs (both built-ins + the
    // user's own custom index URLs are appended at build time).
    private static final Step[] STEPS = new Step[]{
            // v3.4.6.l — every step now carries its VISUAL TARGET BADGE (the
            // exact UI element the glowing arrow points at; video_3 04:43).
            new Step("Top-right ⚙ → Settings",
                    "In official AnantPlay, tap the ⚙ at the TOP-RIGHT, then tap Settings.",
                    Arrow.NE, new String[0], "Top-right ⚙ icon"),
            new Step("Tap Sources",
                    "In AnantPlay Settings, tap the Sources row.",
                    Arrow.DOWN, new String[0], "Top row: Sources"),
            new Step("Tap Add this index",
                    "In Sources, tap “Add this index” on the AnantPlay index group.",
                    Arrow.DOWN, new String[0], "Tap Add on each row"),
            new Step("Tap Add on all 3 rows",
                    "Tap Add on ALL 3 rows of the AnantPlay index (Streaming sites · "
                            + "Free TV channels · Title links).",
                    Arrow.DOWN, new String[0], "Tap Add on each row"),
            new Step("Tap Add an index by link",
                    "Now tap “Add an index by link” — a URL box opens. Copy the Sri Lanka "
                            + "IPTV URL below and paste it in:",
                    Arrow.DOWN, new String[]{URL_SL_IPTV}, "Tap Add on each row"),
            new Step("Paste into URL box",
                    "Paste into the URL box and tap Save/OK. If the box is empty again, "
                            + "re-copy the URL below:",
                    Arrow.DOWN, new String[]{URL_SL_IPTV, URL_SI_SUBS_BRIDGE},
                    "URL box + keyboard"),
            new Step("Tap Add on BOTH Sinhala Subtitle Bridge rows",
                    "Repeat “Add an index by link” and tap Add on BOTH Sinhala Subtitle "
                            + "Bridge rows — copy the bridge URL below:",
                    Arrow.DOWN, new String[]{URL_SI_SUBS_BRIDGE, URL_SL_IPTV},
                    "URL box + keyboard"),
            // v3.4.6.l — THE ACCURATE CONNECTION COPY (video_2 00:25): WARP
            // and Ad block are usually ALREADY On — the user is told to VERIFY
            // those two and only TURN ON the one that starts off.
            new Step("Connection → verify + Address bar",
                    "Open Connection → verify WARP & Ad block are On, and turn "
                            + "Address bar: On.",
                    Arrow.DOWN, new String[0], "Connection row"),
            new Step("Enable Hide offline + Add MyTV .m3u + Check channels",
                    "Open IPTV in AnantPlay: enable “Hide offline channels: On”, add the "
                            + "MyTV .m3u playlist if you want it, then tap “Check channels now”.",
                    Arrow.DOWN, new String[0], "IPTV row"),
            new Step("Player settings → Finish Setup ✓",
                    "Open Player in AnantPlay Settings, pick your boosts / auto-subs, "
                            + "then tap Finish Setup ✓ below — done!",
                    Arrow.DOWN, new String[0], "Player row"),
    };

    /** The animated arrow directions the coach renders. */
    static final class Arrow {
        static final int NE = 0;    // ↗ (the top-right ⚙)
        static final int DOWN = 1;  // ↓ (everything else)
    }

    /**
     * One guided step: title, body, arrow direction, copyable URLs, and the
     * v3.4.6.l VISUAL TARGET BADGE — the unmistakable label of the exact UI
     * element the arrow points at (video_3 04:43).
     */
    static final class Step {
        final String title, body, target;
        final int arrow;
        final String[] copyUrls;
        Step(String title, String body, int arrow, String[] copyUrls, String target) {
            this.title = title; this.body = body;
            this.arrow = arrow; this.copyUrls = copyUrls;
            this.target = target == null || target.isEmpty() ? "" : target;
        }
    }

    private WindowManager wm;
    private LinearLayout card;      // the coach card (bottom-anchored by default)
    private TextView stepTitle, stepBody, stepCounter;
    private LinearLayout copyRow;  // the 1-tap Copy pill rows (rebuilt per step)
    private TextView targetBadge;  // v3.4.6.l — the visual target indicator label
    private Button backButton, nextButton;
    private ArrowView arrowView;
    private int step = 0;

    /** True when the overlay permission is granted (the only precondition). */
    public static boolean canCoach(Context c) {
        try { return Settings.canDrawOverlays(c); } catch (Throwable t) { return false; }
    }

    /** Start the coach overlay (idempotent, only with the overlay grant). */
    public static void start(Context c) {
        if (!canCoach(c)) {
            Diag.w("coach", "overlay permission not granted — the coach card cannot show");
            return;
        }
        try {
            c.startService(new Intent(c, AnantPlayCoachService.class));
            Diag.i("coach", "setup coach overlay started (10 steps, bottom-anchored)");
        } catch (Throwable t) {
            Diag.w("coach", "start: " + t);
        }
    }

    /** Remove the coach (leaving the guided setup early). */
    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, AnantPlayCoachService.class));
        } catch (Throwable t) {
            Diag.w("coach", "stop: " + t);
        }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) { stopSelf(); return; }
        // Android 16 safety: never touch addView() without the live
        // SYSTEM_ALERT_WINDOW grant.
        if (!canCoach(this)) {
            Diag.w("coach", "overlay permission absent — coach not attached");
            stopSelf();
            return;
        }
        buildCard();
        addOverlay();
        showStep();
        Diag.i("coach", "coach card attached over official AnantPlay (bottom anchor)");
        Troubleshooter.record("COACH", "AnantPlay setup coach overlay attached · 10 steps · "
                + "bottom anchor · overlay=" + canCoach(this) + " · zero AccessibilityService");
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        // A re-tap of "Guided AnantPlay Setup" re-shows the coach at the same
        // step (it never restarts the walk from scratch).
        if (card != null) repositionForStep();
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        removeOverlay();
        super.onDestroy();
    }

    // ── the card ──────────────────────────────────────────────────────────────

    private void buildCard() {
        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(13), dp(16), dp(13));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF3141A24);
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), 0x4DFFFFFF);
        card.setBackground(bg);

        // ── the header: coach label + step counter (NO ✕, NO — anywhere) ──────
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setText("AnantPlay Setup Coach");
        label.setTextColor(0xFF7DD3FC);
        label.setTextSize(13f);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        head.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        stepCounter = new TextView(this);
        stepCounter.setTextColor(0xFF94A3B8);
        stepCounter.setTextSize(11f);
        head.addView(stepCounter, wrap());
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // ── the GLOWING ANIMATED ARROW + THE VISUAL TARGET BADGE ────────────
        // v3.4.6.l (video_3 04:43): the arrow now rides WITH an unmistakable
        // target pill ("Top-right ⚙ icon", "Top row: Sources", "URL box +
        // keyboard") — a pulsing glowing badge pointing at the exact target.
        LinearLayout indicator = new LinearLayout(this);
        indicator.setOrientation(LinearLayout.HORIZONTAL);
        indicator.setGravity(Gravity.CENTER_VERTICAL);
        arrowView = new ArrowView(this);
        indicator.addView(arrowView, new LinearLayout.LayoutParams(0, dp(34), 0.9f));
        targetBadge = new TextView(this);
        targetBadge.setTextColor(0xFF7DD3FC);
        targetBadge.setTextSize(11.5f);
        targetBadge.setTypeface(Typeface.DEFAULT_BOLD);
        targetBadge.setPadding(dp(8), dp(3), dp(8), dp(3));
        GradientDrawable tb = new GradientDrawable();
        tb.setColor(0x3D0C4A6E);
        tb.setCornerRadius(dp(999));
        tb.setStroke(dp(1), 0x667DD3FC);
        targetBadge.setBackground(tb);
        indicator.addView(targetBadge, new LinearLayout.LayoutParams(0, -2, 1.1f));
        card.addView(indicator, new LinearLayout.LayoutParams(-1, -2));

        stepTitle = new TextView(this);
        stepTitle.setTextColor(Color.WHITE);
        stepTitle.setTextSize(15f);
        stepTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        stepTitle.setPadding(0, dp(4), 0, 0);
        card.addView(stepTitle, new LinearLayout.LayoutParams(-1, -2));

        stepBody = new TextView(this);
        stepBody.setTextColor(0xFFCBD5E1);
        stepBody.setTextSize(12f);
        stepBody.setLineSpacing(dp(1), 1.25f);
        card.addView(stepBody, new LinearLayout.LayoutParams(-1, -2));

        // ── the 1-tap Copy pills (rebuilt per step) ───────────────────────────
        copyRow = new LinearLayout(this);
        copyRow.setOrientation(LinearLayout.VERTICAL);
        card.addView(copyRow, new LinearLayout.LayoutParams(-1, -2));

        // ── the navigation row: Back · Next step / Finish Setup ✓ ────────────
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        backButton = pillButton("Back", v -> back());
        backButton.setTextColor(0xFF94A3B8);
        row.addView(backButton, new LinearLayout.LayoutParams(0, -2, 1f));
        nextButton = pillButton("Next step", v -> advance());
        row.addView(nextButton, new LinearLayout.LayoutParams(0, -2, 1.6f));
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    /** The user's own custom index URLs (Settings → AnantPlay & Sources). */
    private java.util.List<String[]> customIndexes() {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        try {
            String raw = getSharedPreferences("sicast", MODE_PRIVATE)
                    .getString(AppPrefs.K_INDEX_URLS, "");
            if (raw != null && !raw.isEmpty()) {
                for (String line : raw.split("\n")) {
                    int bar = line.indexOf('|');
                    if (bar > 0 && bar < line.length() - 1)
                        out.add(new String[]{line.substring(0, bar), line.substring(bar + 1)});
                }
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private void showStep() {
        Step s = STEPS[step];
        stepCounter.setText("Step " + (step + 1) + " of " + STEPS.length);
        arrowView.setDirection(s.arrow);
        targetBadge.setText(s.target.isEmpty() ? " " : s.target);
        targetBadge.setVisibility(s.target.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        stepTitle.setText(s.title);
        stepBody.setText(s.body);

        // ── the copy pills: the step's URLs + EVERY user custom index ────────
        copyRow.removeAllViews();
        java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<>();
        for (String u : s.copyUrls) urls.add(u);
        // every custom index URL from Settings joins the copyable set too
        for (String[] cust : customIndexes()) urls.add(cust[1]);
        if (!urls.isEmpty()) {
            for (String u : urls) {
                Button pill = pillButton("⧉  " + shorten(u, 40), v -> copy(u));
                pill.setTextColor(0xFF7DD3FC);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = dp(6);
                copyRow.addView(pill, lp);
            }
        }

        backButton.setVisibility(step > 0 ? View.VISIBLE : View.INVISIBLE);
        nextButton.setText(step == STEPS.length - 1 ? "Finish Setup ✓" : "Next step");
        repositionForStep();
    }

    /**
     * v3.4.6.k — THE BOTTOM DEFAULT + THE AUTO-LIFT: the card anchors at the
     * BOTTOM (out of the way of the content the user is reading), and lifts
     * to the TOP automatically on the URL-paste steps (4/5) where a dialog
     * + keyboard opens over the bottom of the screen. Only while un-dragged.
     */
    private void repositionForStep() {
        if (card == null) return;
        try {
            WindowManager.LayoutParams p = (WindowManager.LayoutParams) card.getLayoutParams();
            if (p == null) return;
            // v3.4.6.l (video_3 05:25–05:33): the URL-paste steps are 5, 6 AND 7
            // (indices 4, 5, 6 — step 7 repeats “Add an index by link” for the
            // Bridge rows and opens the SAME dialog + keyboard). All three lift
            // the card to the TOP below the status bar so it can NEVER cover
            // the “Add an index by link” dialog or the keyboard.
            boolean pasteStep = step >= 4 && step <= 6;
            p.gravity = pasteStep
                    ? Gravity.TOP | Gravity.CENTER_HORIZONTAL
                    : Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            p.x = 0;
            p.y = pasteStep ? dp(64) : dp(16);
            wm.updateViewLayout(card, p);
        } catch (Throwable t) {
            Diag.w("coach", "reposition: " + t);
        }
    }

    private void back() {
        if (step > 0) {
            step--;
            showStep();
        }
    }

    private void advance() {
        if (step < STEPS.length - 1) {
            step++;
            showStep();
            return;
        }
        // v3.4.6.l — THE FINISH AUTO-RETURN (video_2 00:50): tapping Finish
        // Setup ✓ removes the overlay AND immediately relaunches OUR
        // MainActivity to the foreground — the BAL-allowed, NO-CLEAR_TOP lane
        // (REORDER_TO_FRONT | SINGLE_TOP through a PendingIntent) so the LIVE
        // shell instance lands via onNewIntent with zero recreation. A
        // first-time wizard (onboarding still current) therefore resumes on
        // the EXACT persisted slide — Slide 6 (Sources & Index) with its own
        // Finish ready.
        Diag.i("coach", "guided setup finished (" + STEPS.length + "/" + STEPS.length
                + ") — auto-returning to the shell");
        Troubleshooter.record("COACH", "guided AnantPlay setup finished · all "
                + STEPS.length + " steps · auto-return fired");
        stopSelf();
        returnToShell();
    }

    /**
     * The BAL-allowed shell relaunch: REORDER_TO_FRONT | SINGLE_TOP (NO
     * CLEAR_TOP — the slide-1 reset kill), with the openOnboarding extra so a
     * live wizard instance re-presents itself at the persisted slide.
     */
    private void returnToShell() {
        try {
            android.content.Intent i = new android.content.Intent(this, MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                            | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra("openOnboarding", true);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    this, 0x6C69, i,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE,
                    MainActivity.balAllowedOptions());
            pi.send();
            Diag.i("coach", "auto-return to the shell sent (BAL lane)");
        } catch (Throwable t) {
            Diag.w("coach", "auto-return: " + t);
            try {
                startActivity(new android.content.Intent(this, MainActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable t2) {
                Diag.w("coach", "auto-return fallback: " + t2);
            }
        }
    }

    private void copy(String url) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("AnantPlay index", url));
            Diag.i("coach", "copied: " + url);
            if (nextButton != null && step < STEPS.length - 1)
                nextButton.setText("Copied ✓ — Next step");
        } catch (Throwable t) {
            Diag.w("coach", "copy: " + t);
        }
    }

    // ── window params + drag ─────────────────────────────────────────────────

    private WindowManager.LayoutParams cardParams() {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                windowType(), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, PixelFormat.TRANSLUCENT);
        p.y = dp(16);
        p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        return p;
    }

    private int windowType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void addOverlay() {
        if (!canCoach(this)) { stopSelf(); return; }
        try {
            wm.addView(card, cardParams());
            attachDrag(card);
        } catch (android.view.WindowManager.BadTokenException | SecurityException denied) {
            // Android 14–16 can revoke the grant between the check and addView.
            Diag.w("coach", "overlay attach DENIED: " + denied);
            stopSelf();
        } catch (Throwable t) {
            Diag.w("coach", "overlay attach: " + t);
            stopSelf();
        }
    }

    private void removeOverlay() {
        try { wm.removeView(card); } catch (Throwable ignored) { }
    }

    /**
     * Smooth touch-drag: the whole card (except its buttons) drags anywhere.
     * The drag switches the window gravity to TOP|START on the first move
     * and tracks the finger 1:1 — 100% smooth, taps still work.
     */
    private void attachDrag(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY, startX, startY;
            boolean dragging;
            @Override public boolean onTouch(View view, MotionEvent e) {
                WindowManager.LayoutParams p = (WindowManager.LayoutParams) view.getLayoutParams();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        int[] loc = new int[2];
                        view.getLocationOnScreen(loc);
                        startX = loc[0]; startY = loc[1];
                        dragging = false;
                        return false;                       // taps still work
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!dragging && dx * dx + dy * dy < dp(6) * dp(6)) return false;
                        dragging = true;
                        p.gravity = Gravity.TOP | Gravity.START;
                        p.x = (int) (startX + dx);
                        p.y = Math.max(0, (int) (startY + dy));
                        try { wm.updateViewLayout(view, p); } catch (Throwable ignored) { }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        boolean was = dragging;
                        dragging = false;
                        return was;
                    default:
                        return false;
                }
            }
        });
    }

    // ── the glowing animated arrow indicator ──────────────────────────────────

    /**
     * The GLOWING ANIMATED DIRECTIONAL ARROW: a custom-drawn arrow (↗ for the
     * top-right ⚙, ↓ for everything else) with a soft alpha glow that pulses
     * at 4 Hz — unmistakable on ANY wallpaper, zero layout resources.
     */
    private static final class ArrowView extends View {
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint glow =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final ValueAnimator pulse;
        private int direction = Arrow.DOWN;

        ArrowView(Context c) {
            super(c);
            paint.setColor(0xFF7DD3FC);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            glow.setColor(0x667DD3FC);
            glow.setStyle(android.graphics.Paint.Style.STROKE);
            glow.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            pulse = ValueAnimator.ofFloat(0f, 1f);
            pulse.setDuration(500);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.addUpdateListener(a -> invalidate());
        }

        void setDirection(int dir) {
            direction = dir;
            try {
                if (!pulse.isStarted()) pulse.start();
            } catch (Throwable ignored) { }
            invalidate();
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            try { pulse.start(); } catch (Throwable ignored) { }
        }

        @Override protected void onDetachedFromWindow() {
            try { pulse.cancel(); } catch (Throwable ignored) { }
            super.onDetachedFromWindow();
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            try {
                final float w = getWidth(), h = getHeight();
                if (w <= 0 || h <= 0) return;
                float t = pulse.getAnimatedFraction();
                float alpha = 0.55f + 0.45f * t;   // the 4 Hz glow pulse
                final float cx = w * (direction == Arrow.NE ? 0.82f : 0.5f);
                final float cy = h * (direction == Arrow.NE ? 0.18f : 0.78f);
                // ↗ points at the TOP-RIGHT ⚙; ↓ points straight down.
                float len = Math.min(w, h) * 0.34f;
                float head = len * 0.55f;
                float nx = direction == Arrow.NE ? 0.7071f : 0f;   // unit dir
                float ny = direction == Arrow.NE ? -0.7071f : 1f;
                float x0 = cx - nx * len / 2, y0 = cy - ny * len / 2;
                float x1 = cx + nx * len / 2, y1 = cy + ny * len / 2;
                glow.setStrokeWidth(9f);
                glow.setAlpha((int) (255 * 0.45f * alpha));
                paint.setStrokeWidth(4.5f);
                paint.setAlpha((int) (255 * alpha));
                canvas.drawLine(x0, y0, x1, y1, glow);
                canvas.drawLine(x0, y0, x1, y1, paint);
                // the arrow head: two strokes at ±150° from the tip direction
                for (int side = 0; side < 2; side++) {
                    float a = (float) Math.atan2(ny, nx)
                            + (side == 0 ? (float) Math.toRadians(150) : (float) Math.toRadians(-150));
                    float hx = x1 + (float) Math.cos(a) * head;
                    float hy = y1 + (float) Math.sin(a) * head;
                    canvas.drawLine(x1, y1, hx, hy, glow);
                    canvas.drawLine(x1, y1, hx, hy, paint);
                }
                // the glow dot at the anchor (where the finger should tap)
                glow.setStyle(android.graphics.Paint.Style.FILL);
                glow.setAlpha((int) (255 * 0.5f * alpha));
                canvas.drawCircle(x1, y1, 7f * t + 3f, glow);
                glow.setStyle(android.graphics.Paint.Style.STROKE);
            } catch (Throwable ignored) { }
        }
    }

    // ── tiny view helpers (no res dependencies — the coach stays self-earned) ──

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(-2, -2);
    }

    private Button pillButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(12.5f);
        b.setAllCaps(false);
        b.setPadding(dp(12), dp(7), dp(12), dp(7));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x33FFFFFF);
        g.setCornerRadius(dp(999));
        g.setStroke(dp(1), 0x66FFFFFF);
        b.setBackground(g);
        b.setOnClickListener(l);
        return b;
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
