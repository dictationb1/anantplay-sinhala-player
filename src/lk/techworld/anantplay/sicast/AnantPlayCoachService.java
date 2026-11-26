package lk.techworld.anantplay.sicast;

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
 * v3.4.6.j — THE FLOATING ANANTPLAY SETUP COACH (video_5 01:40–05:55, Law #3).
 *
 * Official AnantPlay (me.champdev.weblock) is installed and updated directly
 * from inside our app; the LAST mile of the guided setup — adding our three
 * index URLs inside ANANTPLAY's OWN Settings — now rides a floating, draggable,
 * collapsible glass coach card over AnantPlay itself:
 *
 *   · the overlay uses the `Display over other apps` permission we already
 *     ask for in onboarding (SYSTEM_ALERT_WINDOW, TYPE_APPLICATION_OVERLAY) —
 *     ZERO AccessibilityService, exactly like every other surface of this app;
 *   · the card walks the SIX exact steps from video_5 with a bold title, a
 *     short instruction line, a 1-tap Copy button for every URL that must be
 *     pasted into AnantPlay, and a Next/Finish button — never a second app,
 *     never a video the user has to remember;
 *   · dragging: touch-drag moves the card anywhere (a small drag slop keeps
 *     taps working); collapse folds it into a compact pill; Finish Setup ✓
 *     removes the overlay and returns cleanly to AnantPlay;
 *   · lightweight by Law #4 discipline: ONE view tree, ZERO repeating
 *     animations, ZERO blur shaders (a plain translucent GradientDrawable
 *     glass tint), 0 CPU while the user follows a step.
 *
 * Started from: Onboarding slide 2 (after the official app lands) and
 * Settings → AnantPlay & Sources → "Guided AnantPlay Setup" — the
 * openOfficialWithCoach channel (MainActivity) starts the coach and launches
 * official AnantPlay in one tap.
 */
public class AnantPlayCoachService extends Service {

    // ── the six guided steps (video_5 01:40–05:55, verbatim labels) ──────────
    private static final Step[] STEPS = new Step[]{
            new Step("Settings ⚙ → Sources",
                    "In official AnantPlay, tap Settings (top-right ⚙) → Sources. "
                            + "Then tap “Add this index” on the AnantPlay index "
                            + "(Streaming sites · Free TV channels · Title links).", null),
            new Step("Add the Sri Lanka IPTV index",
                    "Tap “Add an index by link” in AnantPlay → Sources, then copy "
                            + "this URL, paste it there, and Save:",
                    "https://raw.githubusercontent.com/dictationb1/srilanka-anantplay/main/index.json"),
            new Step("Add the Sinhala Subs Bridge",
                    "Tap “Add an index by link” again, copy this URL, paste and Save:",
                    "https://anantplay-si-subs-bridge.techworldlk.workers.dev/index.json"),
            new Step("Connection → WARP · Ad block · Address bar",
                    "Open Connection in AnantPlay Settings and enable WARP: On, "
                            + "Ad block: On, Address bar: On.", null),
            new Step("IPTV → Hide offline channels",
                    "Open IPTV in AnantPlay and enable “Hide offline channels: On” "
                            + "(optional: Adult channels / Add playlist), then tap "
                            + "“Check channels now”.", null),
            new Step("Player → boosts · Finish",
                    "Open Player in AnantPlay Settings, enable your preferred "
                            + "boosts / auto-subs, then tap Finish Setup ✓.", null),
    };

    /** One guided step: a bold title, a short instruction, an optional 1-tap copy URL. */
    private static final class Step {
        final String title, body, copyUrl;
        Step(String title, String body, String copyUrl) {
            this.title = title; this.body = body; this.copyUrl = copyUrl;
        }
    }

    private WindowManager wm;
    private LinearLayout card;          // the full coach card
    private LinearLayout collapsedPill; // the compact "Setup Coach · k/6" pill
    private TextView stepTitle, stepBody, stepCounter;
    private Button copyButton, nextButton;
    private int step = 0;
    private boolean collapsed = false;

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
            Diag.i("coach", "setup coach overlay started (6 steps)");
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
        // v3.4.6.j — Android 16 safety: never touch addView() without the
        // live SYSTEM_ALERT_WINDOW grant (start() checks too; this is the
        // last gate for an explicit startService from anywhere else).
        if (!canCoach(this)) {
            Diag.w("coach", "overlay permission absent — coach not attached");
            stopSelf();
            return;
        }
        buildCard();
        addOverlay();
        showStep();
        Diag.i("coach", "coach card attached over official AnantPlay");
        Troubleshooter.record("COACH", "AnantPlay setup coach overlay attached · 6 steps · "
                + "overlay=" + canCoach(this) + " · zero AccessibilityService");
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        // A re-tap of "Guided AnantPlay Setup" re-shows the coach at the same
        // step (it never restarts the walk from scratch).
        if (card != null) {
            collapsed = false;
            applyCollapsed();
        }
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
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF3141A24);
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), 0x4DFFFFFF);
        card.setBackground(bg);

        // header: coach label + step counter + collapse + close
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setText("AnantPlay Setup Coach");
        label.setTextColor(0xFF7DD3FC);
        label.setTextSize(13f);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        head.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        Button collapse = smallButton("—", v -> { collapsed = true; applyCollapsed(); });
        head.addView(collapse, wrap());
        Button close = smallButton("✕", v -> stopSelf());
        head.addView(close, wrap());
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));

        stepCounter = new TextView(this);
        stepCounter.setTextColor(0xFF94A3B8);
        stepCounter.setTextSize(11f);
        card.addView(stepCounter, new LinearLayout.LayoutParams(-1, -2));

        stepTitle = new TextView(this);
        stepTitle.setTextColor(Color.WHITE);
        stepTitle.setTextSize(15f);
        stepTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        stepTitle.setPadding(0, dp(6), 0, 0);
        card.addView(stepTitle, new LinearLayout.LayoutParams(-1, -2));

        stepBody = new TextView(this);
        stepBody.setTextColor(0xFFCBD5E1);
        stepBody.setTextSize(12f);
        stepBody.setLineSpacing(dp(1), 1.25f);
        card.addView(stepBody, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        copyButton = pillButton("Copy", null);
        copyButton.setTextColor(0xFF7DD3FC);
        row.addView(copyButton, new LinearLayout.LayoutParams(0, -2, 1f));
        nextButton = pillButton("Next step", v -> advance());
        row.addView(nextButton, wrap());
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));

        // the collapsed pill: "Setup Coach · k/6" (tap to re-open)
        collapsedPill = new LinearLayout(this);
        collapsedPill.setOrientation(LinearLayout.HORIZONTAL);
        collapsedPill.setGravity(Gravity.CENTER_VERTICAL);
        collapsedPill.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(0xF3141A24);
        pillBg.setCornerRadius(dp(999));
        pillBg.setStroke(dp(1), 0x4DFFFFFF);
        collapsedPill.setBackground(pillBg);
        TextView pillText = new TextView(this);
        pillText.setTextColor(0xFF7DD3FC);
        pillText.setTextSize(12f);
        pillText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        collapsedPill.addView(pillText, wrap());
        collapsedPill.setOnClickListener(v -> { collapsed = false; applyCollapsed(); });
        stepPillText = pillText;
    }

    private TextView stepPillText;

    private void applyCollapsed() {
        if (card == null || collapsedPill == null) return;
        try {
            if (collapsed) {
                wm.removeView(card);
                wm.addView(collapsedPill, pillParams());
                stepPillText.setText("Setup Coach · " + (step + 1) + "/6");
            } else {
                wm.removeView(collapsedPill);
                wm.addView(card, cardParams());
            }
        } catch (Throwable t) {
            Diag.w("coach", "collapse toggle: " + t);
        }
    }

    private void showStep() {
        Step s = STEPS[step];
        stepCounter.setText("Step " + (step + 1) + " of 6");
        stepTitle.setText(s.title);
        stepBody.setText(s.body);
        if (s.copyUrl != null) {
            copyButton.setVisibility(View.VISIBLE);
            copyButton.setText("Copy: " + shorten(s.copyUrl, 34));
            copyButton.setOnClickListener(v -> copy(s.copyUrl));
        } else {
            copyButton.setVisibility(View.GONE);
        }
        nextButton.setText(step == STEPS.length - 1 ? "Finish Setup ✓" : "Next step");
    }

    private void advance() {
        if (step < STEPS.length - 1) {
            step++;
            showStep();
            return;
        }
        // Finish Setup ✓ — the coach removes itself; the user is left in
        // official AnantPlay exactly where they were (video_5 05:55).
        Diag.i("coach", "guided setup finished (6/6)");
        Troubleshooter.record("COACH", "guided AnantPlay setup finished · all 6 steps");
        stopSelf();
    }

    private void copy(String url) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("AnantPlay index", url));
            if (nextButton != null) nextButton.setText("Copied ✓ — " + nextButton.getText());
            Diag.i("coach", "copied: " + url);
        } catch (Throwable t) {
            Diag.w("coach", "copy: " + t);
        }
    }

    // ── window params + drag ─────────────────────────────────────────────────

    private WindowManager.LayoutParams cardParams() {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                windowType(), Gravity.TOP | Gravity.CENTER_HORIZONTAL, PixelFormat.TRANSLUCENT);
        p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        return p;
    }

    private WindowManager.LayoutParams pillParams() {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                windowType(), Gravity.TOP | Gravity.START, PixelFormat.TRANSLUCENT);
        p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
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
        try { wm.removeView(collapsedPill); } catch (Throwable ignored) { }
    }

    /** Touch-drag anywhere on the header area moves the whole card. */
    private void attachDrag(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY, startX, startY;
            @Override public boolean onTouch(View view, MotionEvent e) {
                WindowManager.LayoutParams p = (WindowManager.LayoutParams) view.getLayoutParams();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        startX = p.x; startY = p.y;
                        return false;                       // taps still work
                    case MotionEvent.ACTION_MOVE:
                        p.x = (int) (startX + (e.getRawX() - downX));
                        p.y = (int) (startY + (e.getRawY() - downY));
                        p.gravity = Gravity.TOP | Gravity.START;
                        try { wm.updateViewLayout(view, p); } catch (Throwable ignored) { }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    // ── tiny view helpers (no res dependencies — the coach stays self-earned) ──

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(-2, -2);
    }

    private Button smallButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(0xFF94A3B8);
        b.setTextSize(12f);
        b.setAllCaps(false);
        b.setPadding(dp(6), dp(2), dp(6), dp(2));
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setOnClickListener(l);
        return b;
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
