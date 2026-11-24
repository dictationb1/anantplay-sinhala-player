package lk.techworld.anantplay.sicast;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * v3.4.3 — THE 5-STAGE FIRST-LAUNCH ONBOARDING WIZARD (Replit QA-0001 … QA-0004).
 *
 * <p>THE PROBLEM IT SOLVES. v3.4.2 dropped a brand-new user straight into a dense
 * Home screen and fired the Android 13 notification dialog from
 * {@code MainActivity.onCreate()} — <b>before</b> the app had explained what it is or
 * why it wanted anything. A first-time user had no idea that (a) official AnantPlay must
 * be installed, (b) an Index AND Sources must be added there, (c) "Display over other
 * apps" is what makes a cast open the player instantly. The audit's verdict: setup was
 * guesswork, and the permission prompt was a cold pop-up.
 *
 * <p>THE CONTRACT THIS CLASS IMPLEMENTS (every line is asserted by the lab):
 *
 * <ol>
 *   <li><b>Nothing is asked before it is explained.</b> The wizard opens on first
 *       launch ({@code !AppPrefs.onboardingDone}); no system dialog fires from
 *       {@code onCreate()}.</li>
 *   <li><b>Five crisp English stages</b>, zero jargon: Welcome · Notifications ·
 *       Auto-Open on Cast · Background Stability · Connect AnantPlay + Quick Check.</li>
 *   <li><b>Live truth, re-queried in {@code onResume()}.</b> Returning from a system
 *       screen NEVER means "granted": every stage re-reads the real permission.</li>
 *   <li><b>Skip never blocks.</b> Denying or skipping any stage still reaches stage 5;
 *       only {@code Finish setup} writes {@link AppPrefs#K_ONBOARDING_DONE}.</li>
 *   <li><b>The stage survives rotation and process death</b> — it is persisted in
 *       {@link AppPrefs#K_ONBOARDING_STAGE}, not in a field.</li>
 *   <li><b>Never exported.</b> {@code onboardingDone} / {@code onboardingStage} are
 *       deliberately NOT in {@link AppPrefs#BACKUP_KEYS}: a device's permission state
 *       must never travel to another phone, and after a restore the real permissions are
 *       re-checked anyway.</li>
 *   <li><b>Re-openable</b> from Settings → Help → "Run setup wizard", and after a
 *       revoked permission the Home screen shows a calm repair banner instead of
 *       trapping the user in the wizard again.</li>
 * </ol>
 *
 * <p>Everything the lab needs to prove is a pure static method here; the view code below
 * only reads their answers.
 */
public final class OnboardingWizard {

    /** The 5 stages, in order. */
    public static final int STAGE_WELCOME = 0;
    public static final int STAGE_NOTIFICATIONS = 1;
    public static final int STAGE_OVERLAY = 2;
    public static final int STAGE_BACKGROUND = 3;
    public static final int STAGE_CONNECT = 4;
    /** How many stages exist (the dots, the clamp, the progress line). */
    public static final int STAGES = 5;

    /** The official AnantPlay package (the ONLY source of movies — never a fork). */
    public static final String ANANTPLAY_PKG = "me.champdev.weblock";

    /** The live session (null when the wizard is closed). */
    private static Session session;

    private OnboardingWizard() { }

    // ══════════════════════════════════════════════════════════════════════════
    // THE PURE CONTRACT (the lab drives exactly these)
    // ══════════════════════════════════════════════════════════════════════════

    /** Clamp a stored stage into [0, STAGES-1] — a corrupt pref can never crash. */
    public static int clampStage(int stage) { return OnboardingSpec.clampStage(stage); }

    /** The next stage (clamped: stage 5 is the end). */
    public static int nextStage(int stage) { return OnboardingSpec.nextStage(stage); }

    /** The previous stage (clamped at Welcome — Back never closes the wizard). */
    public static int prevStage(int stage) { return OnboardingSpec.prevStage(stage); }

    /** True when the wizard should open by itself (first launch, never completed). */
    public static boolean shouldAutoOpen(boolean onboardingDone) { return OnboardingSpec.shouldAutoOpen(onboardingDone); }

    /**
     * True when the Home screen should show the calm REPAIR banner: onboarding was
     * completed, but a permission it set up has since been revoked. This is a banner,
     * never a forced wizard loop (the audit's explicit rule).
     */
    public static boolean needsRepair(boolean onboardingDone, boolean overlayGranted,
                                      boolean notifGranted, boolean batteryUnrestricted) { return OnboardingSpec.needsRepair(onboardingDone, overlayGranted, notifGranted, batteryUnrestricted); }

    /** The one-line repair banner text (never jargon, never a wall of permissions). */
    public static String repairBannerText(boolean overlayGranted, boolean notifGranted,
                                          boolean batteryUnrestricted) { return OnboardingSpec.repairBannerText(overlayGranted, notifGranted, batteryUnrestricted); }

    /** Stage 2 — the honest notification status line. */
    public static String notifStatus(int sdkInt, boolean granted) { return OnboardingSpec.notifStatus(sdkInt, granted); }

    /** Stage 3 — the honest overlay status line (re-queried, never assumed). */
    public static String overlayStatus(boolean granted) { return OnboardingSpec.overlayStatus(granted); }

    /**
     * Stage 4 — the TRUTHFUL background-stability line. The OS read is authoritative
     * when it answers; when HyperOS blocks it the app says "Unknown" and offers the
     * user's own confirmation — it never claims the OS granted something it cannot see.
     */
    public static String backgroundStatus(boolean xiaomi, Miui.AutostartState autostart,
                                          boolean userConfirmed, boolean batteryUnrestricted) {
        return OnboardingSpec.backgroundStatus(xiaomi, autostartCode(autostart),
            userConfirmed, batteryUnrestricted);
    }

    /** Map the OEM enum onto the pure spec's int codes. */
    static int autostartCode(Miui.AutostartState st) {
        if (st == Miui.AutostartState.ENABLED) return OnboardingSpec.AUTOSTART_ENABLED;
        if (st == Miui.AutostartState.DISABLED) return OnboardingSpec.AUTOSTART_DISABLED;
        return OnboardingSpec.AUTOSTART_UNKNOWN;
    }

    /** The stage titles (used by the header and by the lab's contract assertions). */
    public static String stageTitle(int stage) { return OnboardingSpec.stageTitle(stage); }

    /** The primary button label for a stage. */
    public static String stageAction(int stage) { return OnboardingSpec.stageAction(stage); }

    // ══════════════════════════════════════════════════════════════════════════
    // lifecycle — open / close / re-query
    // ══════════════════════════════════════════════════════════════════════════

    /** Open the wizard on first launch only (never twice, never over another dialog). */
    public static void showIfNeeded(Activity a, SharedPreferences prefs) {
        if (a == null || prefs == null) return;
        if (!shouldAutoOpen(AppPrefs.onboardingDone(prefs))) return;
        show(a, prefs);
    }

    /** Open (or re-open) the wizard at its persisted stage. Idempotent. */
    public static void show(final Activity a, final SharedPreferences prefs) {
        if (a == null || prefs == null) return;
        if (a.isFinishing()) return;
        if (session != null) { session.render(); return; }     // already open
        try {
            session = new Session(a, prefs);
            session.open();
            Diag.i("onboarding", "wizard opened at stage " + session.stage);
            Troubleshooter.record("SETUP", "wizard opened at stage " + session.stage);
        } catch (Throwable t) {
            // a wizard that cannot draw must never block the app
            Diag.e("onboarding", "open failed: " + t);
            session = null;
            AppPrefs.setOnboardingDone(prefs, true);
        }
    }

    /**
     * Called from the host Activity's {@code onResume()}: re-query EVERY live state.
     * Returning from the overlay or battery screen is not a grant — it is only a
     * reason to look again (the audit's QA-0003 stale-rows bug).
     */
    public static void onHostResume() {
        if (session == null) return;
        try { session.render(); } catch (Throwable t) { Diag.w("onboarding", "resume: " + t); }
    }

    /** True while the wizard is on screen (the host defers its own dialogs to it). */
    public static boolean isOpen() { return session != null; }

    /** Close the wizard without completing it (Back out of stage 1). */
    public static void dismiss() {
        if (session == null) return;
        try { session.close(); } catch (Throwable ignored) { }
        session = null;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // the session (view code)
    // ══════════════════════════════════════════════════════════════════════════

    private static final class Session {
        private final Activity act;
        private final SharedPreferences prefs;
        private final ThemeEngine.Palette p;
        private Dialog dialog;
        private int stage;
        private LinearLayout body;
        private TextView title;
        private TextView dots;
        private TextView progress;
        private LinearLayout footer;

        /** Live state, re-read on every render(). */
        private boolean overlayGranted;
        private boolean notifGranted;
        private boolean batteryOk;
        private boolean anantplayInstalled;
        private boolean probeRunning;
        private List<FeatureProbe.Outcome> probeResults;

        Session(Activity a, SharedPreferences prefs) {
            this.act = a;
            this.prefs = prefs;
            this.p = ThemeEngine.palette(AppPrefs.themeMode(prefs), 0, AppPrefs.accent(prefs),
                AppPrefs.accentHex(prefs), AppPrefs.cardRadius(prefs));
            this.stage = clampStage(AppPrefs.onboardingStage(prefs));
            this.probeResults = FeatureProbe.lastResults();
        }

        void open() {
            dialog = new Dialog(act);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setCanceledOnTouchOutside(false);
            LinearLayout root = new LinearLayout(act);
            root.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(p.bg);
            bg.setCornerRadius(UiCard.dp(act, p.radiusSheet));
            root.setBackground(bg);
            root.setPadding(dp(20), dp(20), dp(20), dp(14));

            // ── header: title + stage dots ──
            title = new TextView(act);
            title.setTextColor(p.title);
            title.setTextSize(20f);
            title.setTypeface(UiTokens.si(act, 600));
            root.addView(title, new LinearLayout.LayoutParams(-1, -2));

            dots = new TextView(act);
            dots.setTextColor(p.accent);
            dots.setTextSize(13f);
            dots.setPadding(0, dp(6), 0, dp(2));
            root.addView(dots, new LinearLayout.LayoutParams(-1, -2));

            progress = new TextView(act);
            progress.setTextColor(p.desc);
            progress.setTextSize(10.5f);
            root.addView(progress, new LinearLayout.LayoutParams(-1, -2));

            // ── the scrolling body ──
            ScrollView sc = new ScrollView(act);
            body = new LinearLayout(act);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(0, dp(10), 0, dp(6));
            sc.addView(body, new ViewGroup.LayoutParams(-1, -2));
            root.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1f));

            // ── footer: Back · Skip · primary ──
            footer = new LinearLayout(act);
            footer.setOrientation(LinearLayout.HORIZONTAL);
            footer.setGravity(Gravity.CENTER_VERTICAL);
            footer.setPadding(0, dp(8), 0, 0);
            root.addView(footer, new LinearLayout.LayoutParams(-1, -2));

            dialog.setContentView(root);
            Window w = dialog.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                w.setLayout((int) (act.getResources().getDisplayMetrics().widthPixels * 0.94f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            dialog.show();
            render();
        }

        void close() {
            try { if (dialog != null) dialog.dismiss(); } catch (Throwable ignored) { }
            dialog = null;
        }

        /** Re-query every permission for real, then redraw the current stage. */
        void render() {
            if (dialog == null) return;
            overlayGranted = Settings.canDrawOverlays(act);
            notifGranted = Miui.notifGranted(act);
            batteryOk = Miui.batteryUnrestricted(act);
            anantplayInstalled = isInstalled(ANANTPLAY_PKG);
            title.setText(stageTitle(stage));
            StringBuilder d = new StringBuilder();
            for (int i = 0; i < STAGES; i++) d.append(i == stage ? "● " : "○ ");
            dots.setText(d.toString().trim());
            progress.setText("Step " + (stage + 1) + " of " + STAGES);
            body.removeAllViews();
            switch (stage) {
                case STAGE_WELCOME: paintWelcome(); break;
                case STAGE_NOTIFICATIONS: paintNotifications(); break;
                case STAGE_OVERLAY: paintOverlay(); break;
                case STAGE_BACKGROUND: paintBackground(); break;
                default: paintConnect(); break;
            }
            paintFooter();
            GlassSurface.apply(root, prefs, p.card, 26);
            UiFonts.applyTree(root, prefs);
        }

        // ── stage 1 — Welcome ────────────────────────────────────────────────
        private void paintWelcome() {
            LinearLayout hero = new LinearLayout(act);
            hero.setOrientation(LinearLayout.VERTICAL);
            hero.setGravity(Gravity.CENTER_HORIZONTAL);
            hero.setPadding(0, dp(10), 0, dp(12));
            android.widget.ImageView icon = new android.widget.ImageView(act);
            icon.setImageDrawable(act.getDrawable(R.mipmap.ic_launcher));
            icon.setContentDescription("AnantPlay Player");
            hero.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));
            TextView name = new TextView(act);
            name.setText("AnantPlay Player");
            name.setTextColor(p.title);
            name.setTextSize(17f);
            name.setTypeface(UiTokens.si(act, 600));
            name.setPadding(0, dp(8), 0, 0);
            hero.addView(name, new LinearLayout.LayoutParams(-2, -2));
            body.addView(hero, new LinearLayout.LayoutParams(-1, -2));
            body.addView(paragraph("Watch AnantPlay movies with automatic Sinhala subtitles."));
            body.addView(note("Setup takes about 30 seconds. Every step can be skipped and "
                + "changed later in Settings."));
        }

        // ── stage 2 — Notifications ──────────────────────────────────────────
        private void paintNotifications() {
            int sdk = Build.VERSION.SDK_INT;
            body.addView(statusLine(notifStatus(sdk, notifGranted),
                sdk < 33 ? p.ok : (notifGranted ? p.ok : p.warn)));
            if (sdk < 33) {
                body.addView(paragraph("Your Android version does not ask for this — "
                    + "the receiver's status notification works already."));
            } else {
                body.addView(paragraph("The receiver runs without this. The permission only "
                    + "lets it show its status, and the tap-to-open fallback when a cast "
                    + "cannot open the player by itself."));
                body.addView(note("Skipping this never blocks setup. You can grant it later "
                    + "in Android Settings → Apps → AnantPlay Player → Notifications."));
            }
            if (sdk >= 33 && !notifGranted) {
                body.addView(actionRow("Enable notifications", () -> requestNotifications()));
            }
        }

        // ── stage 3 — Auto-open on cast (overlay) ────────────────────────────
        private void paintOverlay() {
            body.addView(statusLine(overlayStatus(overlayGranted),
                overlayGranted ? p.ok : p.warn));
            body.addView(paragraph("With \"Display over other apps\" allowed, a cast jumps "
                + "straight into the player. Without it, Android shows a notification you "
                + "tap instead — the cast still works either way."));
            body.addView(note("The status above is read live from Android every time you "
                + "come back to this screen."));
            if (!overlayGranted) {
                body.addView(actionRow("Enable auto-open", () -> Miui.openOverlay(act)));
            }
        }

        // ── stage 4 — Background stability ───────────────────────────────────
        private void paintBackground() {
            boolean xiaomi = Miui.isXiaomi();
            Miui.AutostartState st = Miui.autostartState(act);
            boolean confirmed = Miui.autostartUserConfirmed(act);
            body.addView(statusLine(backgroundStatus(xiaomi, st, confirmed, batteryOk),
                batteryOk ? p.ok : p.warn));
            body.addView(paragraph("Android may put the receiver to sleep to save battery. "
                + "Setting it to \"No restrictions\" keeps the receiver alive between casts"
                + (xiaomi ? ", and HyperOS additionally has its own Autostart switch." : ".")));
            if (!batteryOk) {
                body.addView(actionRow("Battery → No restrictions", () -> Miui.requestBattery(act)));
            }
            if (xiaomi) {
                body.addView(actionRow("Open Autostart settings", this::openOemAutostart));
                if (st != Miui.AutostartState.ENABLED) {
                    body.addView(actionRow("I turned Autostart on", () -> {
                        Miui.confirmAutostart(act, true);
                        render();
                    }));
                }
            }
            body.addView(note("This is a vendor setting the OS does not always report — the "
                + "line above never claims a grant it cannot verify."));
        }

        /** Resolve the OEM intent BEFORE launching it, with a safe fallback. */
        private void openOemAutostart() {
            try {
                Intent i = new Intent()
                    .setComponent(new android.content.ComponentName("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"));
                if (act.getPackageManager().resolveActivity(i, 0) != null) {
                    act.startActivity(i);
                    Diag.i("onboarding", "OEM autostart screen resolved");
                    return;
                }
            } catch (Throwable ignored) { }
            try { Miui.openAutostart(act); } catch (Throwable t) { Miui.openAppDetails(act); }
        }

        // ── stage 5 — Connect AnantPlay + Quick Check ────────────────────────
        private void paintConnect() {
            body.addView(statusLine(anantplayInstalled
                    ? "Official AnantPlay is installed ✓"
                    : "Official AnantPlay is not installed",
                anantplayInstalled ? p.ok : p.warn));
            if (!anantplayInstalled) {
                body.addView(paragraph("AnantPlay Player is a receiver — it plays the movies "
                    + "official AnantPlay casts. Install AnantPlay from its official Play Store "
                    + "or GitHub page (this app never downloads an APK for you)."));
            }
            LinearLayout card = UiCard.make(act, p, prefs);
            TextView head = new TextView(act);
            head.setText("3 steps to watch");
            head.setTextColor(p.title);
            head.setTextSize(13.5f);
            head.setTypeface(UiTokens.si(act, 600));
            card.addView(head, new LinearLayout.LayoutParams(-1, -2));
            card.addView(step("1", "Open AnantPlay → Sources → add an Index AND your Sources."));
            card.addView(step("2", "Start any movie in AnantPlay."));
            card.addView(step("3", "Tap Cast → choose \"AnantPlay Player\"."));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
            clp.topMargin = dp(6);
            body.addView(card, clp);

            body.addView(actionRow(probeRunning ? "Running the 10-point check…"
                : "Run quick check", this::runQuickCheck));
            if (probeResults != null && !probeResults.isEmpty()) {
                body.addView(paragraph(FeatureProbe.verdict(probeResults)));
                int shown = 0;
                for (FeatureProbe.Outcome o : probeResults) {
                    if (o == null) continue;
                    if (shown++ >= 10) break;
                    body.addView(probeRow(o));
                }
                body.addView(note("Probes 1–3 exercise this phone's own receiver (localhost "
                    + "and the mDNS wire). Wi-Fi Cast discovery from AnantPlay is a separate "
                    + "network step — a green list here does not claim it."));
            }
            if (!anantplayInstalled) {
                body.addView(actionRow("Open AnantPlay's page", () -> {
                    try { Miui.openAnantPlay(act); } catch (Throwable ignored) { }
                }));
            }
        }

        /** The real 10-point FeatureProbe, on a background thread, never two at once. */
        private void runQuickCheck() {
            if (probeRunning) return;
            probeRunning = true;
            render();
            new Thread(() -> {
                List<FeatureProbe.Outcome> out;
                try {
                    MdnsResponder rsp = ReceiverService.liveResponder();
                    out = FeatureProbe.runAll(ReceiverService.port(), AppPrefs.workerUrl(prefs),
                        act.getFilesDir(), rsp != null && rsp.isRunning(), null);
                } catch (Throwable t) {
                    Diag.w("onboarding", "probe: " + t);
                    out = FeatureProbe.lastResults();
                }
                final List<FeatureProbe.Outcome> snap = out;
                probeRunning = false;
                probeResults = snap;
                act.runOnUiThread(this::render);
            }, "onboarding-probe").start();
        }

        // ── footer ───────────────────────────────────────────────────────────
        private void paintFooter() {
            footer.removeAllViews();
            if (stage > STAGE_WELCOME) {
                TextView back = footerButton("Back", false);
                back.setOnClickListener(v -> goTo(prevStage(stage)));
                footer.addView(back, new LinearLayout.LayoutParams(-2, dp(44)));
            }
            if (stage != STAGE_WELCOME && stage != STAGE_CONNECT) {
                TextView skip = footerButton("Skip", false);
                skip.setOnClickListener(v -> goTo(nextStage(stage)));
                footer.addView(skip, new LinearLayout.LayoutParams(-2, dp(44)));
            }
            TextView primary = footerButton(stageAction(stage), true);
            primary.setOnClickListener(v -> {
                if (stage == STAGE_CONNECT) finishSetup();
                else goTo(nextStage(stage));
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            lp.leftMargin = dp(6);
            footer.addView(primary, lp);
        }

        private TextView footerButton(String text, boolean primary) {
            TextView b = new TextView(act);
            b.setText(text);
            b.setGravity(Gravity.CENTER);
            b.setTextSize(13f);
            b.setTypeface(UiTokens.si(act, 600));
            b.setPadding(dp(14), 0, dp(14), 0);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(22));
            if (primary) {
                bg.setColor(p.accent);
                b.setTextColor(p.light ? Color.WHITE : 0xFF0A0A14);
            } else {
                bg.setColor(p.chip);
                b.setTextColor(p.text);
            }
            b.setBackground(bg);
            UiCard.press(b, prefs);
            return b;
        }

        private void goTo(int target) {
            stage = clampStage(target);
            AppPrefs.setOnboardingStage(prefs, stage);     // survives rotation + process death
            render();
        }

        /** The ONLY place onboarding is marked complete. */
        private void finishSetup() {
            AppPrefs.setOnboardingDone(prefs, true);
            AppPrefs.setOnboardingStage(prefs, STAGE_WELCOME);
            Diag.i("onboarding", "setup finished");
            Troubleshooter.record("SETUP", "wizard finished — onboardingDone=true");
            close();
            session = null;
            try { act.recreate(); } catch (Throwable ignored) { }   // the repair banner state
        }

        // ── tiny view builders ───────────────────────────────────────────────
        private TextView paragraph(String s) {
            TextView tv = new TextView(act);
            tv.setText(s);
            tv.setTextColor(p.text);
            tv.setTextSize(12.5f);
            tv.setLineSpacing(dp(2), 1f);
            tv.setPadding(0, dp(4), 0, dp(6));
            return tv;
        }

        private TextView note(String s) {
            TextView tv = new TextView(act);
            tv.setText(s);
            tv.setTextColor(p.desc);
            tv.setTextSize(11f);
            tv.setLineSpacing(dp(1), 1f);
            tv.setPadding(0, dp(2), 0, dp(6));
            return tv;
        }

        private TextView statusLine(String s, int color) {
            TextView tv = new TextView(act);
            tv.setText(s);
            tv.setTextColor(color);
            tv.setTextSize(12.5f);
            tv.setTypeface(UiTokens.si(act, 600));
            tv.setPadding(0, dp(2), 0, dp(6));
            return tv;
        }

        private View step(String n, String text) {
            LinearLayout r = new LinearLayout(act);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.TOP);
            r.setPadding(0, dp(6), 0, 0);
            TextView num = new TextView(act);
            num.setText(n);
            num.setTextColor(p.accent);
            num.setTextSize(12f);
            num.setTypeface(UiTokens.si(act, 600));
            num.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(p.accentSoft);
            bg.setCornerRadius(dp(11));
            num.setBackground(bg);
            r.addView(num, new LinearLayout.LayoutParams(dp(22), dp(22)));
            TextView t = new TextView(act);
            t.setText(text);
            t.setTextColor(p.text);
            t.setTextSize(12f);
            t.setPadding(dp(10), 0, 0, 0);
            t.setLineSpacing(dp(1), 1f);
            r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            return r;
        }

        private View probeRow(FeatureProbe.Outcome o) {
            LinearLayout r = new LinearLayout(act);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, dp(4), 0, 0);
            TextView mark = new TextView(act);
            mark.setText(o.pass ? "✓" : "✗");
            mark.setTextColor(o.pass ? p.ok : p.err);
            mark.setTextSize(12f);
            r.addView(mark, new LinearLayout.LayoutParams(dp(20), -2));
            TextView name = new TextView(act);
            name.setText("#" + o.id + " " + o.name);
            name.setTextColor(p.text);
            name.setTextSize(11.5f);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            r.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView ms = new TextView(act);
            ms.setText(o.ms + " ms");
            ms.setTextColor(p.desc);
            ms.setTextSize(10.5f);
            r.addView(ms, new LinearLayout.LayoutParams(-2, -2));
            return r;
        }

        private View actionRow(String label, final Runnable action) {
            TextView b = new TextView(act);
            b.setText(label);
            b.setTextColor(p.accent);
            b.setTextSize(12.5f);
            b.setTypeface(UiTokens.si(act, 600));
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(14), dp(10), dp(14), dp(10));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(p.accentSoft);
            bg.setCornerRadius(dp(p.radiusChip));
            b.setBackground(bg);
            UiCard.press(b, prefs);
            b.setOnClickListener(v -> {
                try { action.run(); } catch (Throwable t) { Diag.w("onboarding", "action: " + t); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(6);
            b.setLayoutParams(lp);
            LinearLayout wrap = new LinearLayout(act);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.addView(b, new LinearLayout.LayoutParams(-1, -2));
            return wrap;
        }

        private void requestNotifications() {
            try {
                act.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
            } catch (Throwable t) {
                Diag.w("onboarding", "notif request: " + t);
            }
        }

        private boolean isInstalled(String pkg) {
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    return act.getPackageManager().getPackageInfo(pkg,
                        PackageManager.PackageInfoFlags.of(0)) != null;
                }
                return act.getPackageManager().getPackageInfo(pkg, 0) != null;
            } catch (Throwable t) {
                return false;
            }
        }

        private int dp(float v) {
            return Math.round(v * act.getResources().getDisplayMetrics().density);
        }
    }
}
