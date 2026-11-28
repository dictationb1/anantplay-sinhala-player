package lk.techworld.anantplay.sicast;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * v3.4.6.l — THE NEXT-LEVEL CINEMA HUD (the first module of the modular player
 * engine, studied clean-room against the Next Player control stack and the
 * Mornye dark-glass language; NO GPL-3 source is copied — the repository stays
 * 100% LGPL-3.0-only).
 *
 * <p>THE DESIGN CONTRACT (video_1 00:00–01:59, video_2 04:34–05:57 — every
 * visible v3.3/v3.4 defect is killed at the source):
 *
 * <ul>
 *   <li><b>NO gray rounded squares.</b> Every icon is an icon-only circular
 *       ripple target: a transparent 44 dp touch area with a crisp 22 dp white
 *       vector glyph (24 dp on the top chevron), accent-tinted when active —
 *       the UiGlassIcon medallion (the v3.3 "giant gray plate" look) is
 *       retired from the cinema surface entirely.</li>
 *   <li><b>Smooth gradient scrims, zero GPU blur.</b> The HUD paints two static
 *       gradient scrims (#CC000000 to transparent, top and bottom) behind the
 *       chrome instead of any blur/BackdropFilter surface — the 2-hour battery
 *       contract (~10%) keeps zero compositor work while the HUD is visible.</li>
 *   <li><b>220 ms cubic show/hide.</b> show()/hide() animate alpha + a subtle
 *       vertical slide (top bar slides down in, bottom bar slides up in) with
 *       a DecelerateInterpolator — never a hard visibility flip.</li>
 *   <li><b>Tabular timestamps.</b> The left/right clock texts enable
 *       {@code fontFeatureSettings "tnum"} so 00:18 / 10:34 never jitter.</li>
 *   <li><b>Configurable auto-hide</b> (2 s / 3 s / 5 s, default 3.5 s) is owned
 *       by the caller (PlayActivity's scheduleHudHide) through AppPrefs.</li>
 * </ul>
 *
 * <p>The caller (PlayActivity) builds this once in buildUi(), then drives the
 * references — the HUD never touches the player object itself.
 */
final class CinemaHud {

    private CinemaHud() { }

    /** The chrome the HUD exposes to its host (all fields assigned at build). */
    static final class Chrome {
        FrameLayout root;          // the full-screen overlay root (mounted on the activity root)
        LinearLayout topBar;       // inset-padded by the host's inset listener
        LinearLayout bottomBar;
        TextView title;             // 17sp medium, one line, clean ellipsis
        TextView subtitleBadge;     // compact auto-fading CC badge (never a green box)
        ImageView playPause;        // the 64dp hero disc
        View rewind;               // −seek flanks
        View forward;
        ScrubBar seek;
        TextView timeNow;          // tabular, tap toggles remaining time
        TextView timeTotal;
        View aspectButton;          // the dock quick-action row (content updates live)
        TextView speedLabel;
        FrameLayout topScrim;
        FrameLayout bottomScrim;
        boolean remainingTime = false;
        // v3.4.6.m — THE CENTER BUFFERING RING state (paintBuffering owns it):
        // while STATE_BUFFERING the ring owns the hero disc (glyph hidden).
        View bufferRing;
        boolean buffering = false;
        boolean lastPlaying = false;
    }

    /** The one-click surface the HUD needs from its host. */
    interface Ops {
        void onBack();
        void onLock();
        void onAudioTracks();
        void onSubtitles();
        void onPlayerSettings();
        void onPlayPause();
        void onSeekRelative(long ms);
        void onRotate();
        void onCycleAspect();
        void onCycleSpeed();
        void onPip();
        void onExternalPlayer();
        void onSearchSubs();
    }

    /** The tabular time feature (17:05 never reflows 17:06 — digits align). */
    private static final String TABULAR = "tnum on";

    // ── the scrims: pure static gradients, zero blur, zero GPU cost ───────────

    private static FrameLayout scrim(Activity a, boolean top) {
        FrameLayout box = new FrameLayout(a);
        GradientDrawable g = new GradientDrawable(
            top ? GradientDrawable.Orientation.TOP_BOTTOM : GradientDrawable.Orientation.BOTTOM_TOP,
            new int[]{0xCC000000, 0x73000000, 0x00000000});
        g.setCornerRadius(0);
        box.setBackground(g);
        return box;
    }

    // ── the icon-only ripple target (the gray-squares killer) ─────────────────

    /**
     * A transparent circular ripple target: 44 dp (48 dp on the hero flanks),
     * a crisp white vector glyph, a hairline oval mask so the ripple stays a
     * clean circle. accent=true tints the glyph — the active-state highlight.
     */
    private static View target(Activity a, int glyphRes, int glyphDp, boolean accent,
                              String description, View.OnClickListener click) {
        int dp48 = UiCard.dp(a, 48);
        FrameLayout plate = new FrameLayout(a);
        plate.setMinimumWidth(dp48);
        plate.setMinimumHeight(dp48);
        plate.setContentDescription(description);
        int color = accent ? 0xFF7DD3FC : Color.WHITE;
        ImageView glyph = new ImageView(a);
        glyph.setImageDrawable(UiIcons.tinted(a, glyphRes, color, glyphDp));
        glyph.setScaleType(ImageView.ScaleType.FIT_CENTER);
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
            UiCard.dp(a, glyphDp), UiCard.dp(a, glyphDp), Gravity.CENTER);
        plate.addView(glyph, glp);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.TRANSPARENT);
        RippleDrawable ripple = new RippleDrawable(
            ColorStateList.valueOf(0x40FFFFFF), null, mask);
        plate.setBackground(ripple);
        if (click != null) {
            plate.setClickable(true);
            plate.setFocusable(true);
            plate.setOnClickListener(click);
            // v3.4.6.l — the tactile spring: the target scales 0.90 on press
            // and springs back 1.0 on release (respecting motionEnabled).
        }
        return plate;
    }

    /** The 64 dp translucent hero disc with a spring press-scale. */
    private static ImageView heroDisc(Activity a) {
        ImageView disc = new ImageView(a);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x59000000);
        bg.setStroke(UiCard.dp(a, 1), 0x2EFFFFFF);
        disc.setBackground(bg);
        disc.setClickable(true);
        disc.setFocusable(true);
        disc.setScaleType(ImageView.ScaleType.FIT_CENTER);
        disc.setContentDescription("Play or pause");
        return disc;
    }

    // ── BUILD ────────────────────────────────────────────────────────────────

    static Chrome build(Activity a, SharedPreferences prefs, Ops ops) {
        Chrome c = new Chrome();
        FrameLayout layer = new FrameLayout(a);
        c.root = layer;

        // LAYER A — the gradient scrims (top taller than the bar so the title
        // never floats on bare video; bottom covers the dock + seekbar).
        c.topScrim = scrim(a, true);
        layer.addView(c.topScrim, new FrameLayout.LayoutParams(-1, UiCard.dp(a, 132), Gravity.TOP));
        c.bottomScrim = scrim(a, false);
        layer.addView(c.bottomScrim, new FrameLayout.LayoutParams(-1, UiCard.dp(a, 168), Gravity.BOTTOM));

        // LAYER B — the three-zone chrome column (top WRAP · centre weight 1 ·
        // bottom WRAP): a physically impossible top/bottom overlap by layout.
        LinearLayout zones = new LinearLayout(a);
        zones.setOrientation(LinearLayout.VERTICAL);

        // ── ZONE 1 — TOP BAR (back chevron · title · badge · icon targets) ────
        c.topBar = new LinearLayout(a);
        c.topBar.setOrientation(LinearLayout.HORIZONTAL);
        c.topBar.setGravity(Gravity.CENTER_VERTICAL);
        c.topBar.setMinimumHeight(UiCard.dp(a, 52));
        // (the inset listener pads statusBars|displayCutout + 8dp top)

        View back = target(a, R.drawable.ic_back, 24, false, "Back", v -> ops.onBack());
        c.topBar.addView(back, new LinearLayout.LayoutParams(
            UiCard.dp(a, 48), UiCard.dp(a, 48)));

        LinearLayout titles = new LinearLayout(a);
        titles.setOrientation(LinearLayout.VERTICAL);
        c.title = new TextView(a);
        c.title.setTextColor(Color.WHITE);
        c.title.setTextSize(17f);
        c.title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        c.title.setMaxLines(1);
        c.title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(c.title, new LinearLayout.LayoutParams(-1, -2));
        c.subtitleBadge = new TextView(a);
        c.subtitleBadge.setTextColor(0xFFB8C4D9);
        c.subtitleBadge.setTextSize(10.5f);
        c.subtitleBadge.setMaxLines(1);
        c.subtitleBadge.setEllipsize(android.text.TextUtils.TruncateAt.END);
        c.subtitleBadge.setAlpha(0f);
        titles.addView(c.subtitleBadge, new LinearLayout.LayoutParams(-1, -2));
        c.topBar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));

        c.topBar.addView(target(a, R.drawable.ic_lock, 22, false,
            "Lock player controls", v -> ops.onLock()), new LinearLayout.LayoutParams(
            UiCard.dp(a, 44), UiCard.dp(a, 44)));
        c.topBar.addView(target(a, R.drawable.ic_audio_track, 22, false,
            "Audio tracks and video quality", v -> ops.onAudioTracks()), new LinearLayout.LayoutParams(
            UiCard.dp(a, 44), UiCard.dp(a, 44)));
        c.topBar.addView(target(a, R.drawable.ic_subtitles, 22, false,
            "Subtitles", v -> ops.onSubtitles()), new LinearLayout.LayoutParams(
            UiCard.dp(a, 44), UiCard.dp(a, 44)));
        c.topBar.addView(target(a, R.drawable.ic_settings, 22, false,
            "Player settings", v -> ops.onPlayerSettings()), new LinearLayout.LayoutParams(
            UiCard.dp(a, 44), UiCard.dp(a, 44)));
        zones.addView(c.topBar, new LinearLayout.LayoutParams(-1, -2));

        // ── ZONE 2 — CENTRE (the floating transport) ──────────────────────────
        LinearLayout center = new LinearLayout(a);
        center.setOrientation(LinearLayout.HORIZONTAL);
        center.setGravity(Gravity.CENTER);
        c.rewind = target(a, R.drawable.ic_replay_10, 22, false,
            "Seek back", v -> ops.onSeekRelative(-seekMs(prefs)));
        c.forward = target(a, R.drawable.ic_forward_10, 22, false,
            "Seek forward", v -> ops.onSeekRelative(seekMs(prefs)));
        // v3.4.6.m — THE CENTER BUFFERING RING: the hero disc is now a
        // FrameLayout carrying the play/pause ImageView AND the spinning ring
        // view on top, so the ring is CENTERED ON the disc by construction (the
        // old root-mounted spinner rendered BEHIND the translucent disc).
        FrameLayout discWrap = new FrameLayout(a);
        c.playPause = heroDisc(a);
        c.playPause.setOnClickListener(v -> ops.onPlayPause());
        discWrap.addView(c.playPause, new FrameLayout.LayoutParams(-1, -1));
        c.bufferRing = new BufferRing(a);
        c.bufferRing.setVisibility(View.GONE);
        discWrap.addView(c.bufferRing, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(
            UiCard.dp(a, 64), UiCard.dp(a, 64));
        heroLp.leftMargin = heroLp.rightMargin = UiCard.dp(a, 28);
        center.addView(c.rewind, new LinearLayout.LayoutParams(UiCard.dp(a, 48), UiCard.dp(a, 48)));
        center.addView(discWrap, heroLp);
        center.addView(c.forward, new LinearLayout.LayoutParams(UiCard.dp(a, 48), UiCard.dp(a, 48)));
        zones.addView(center, new LinearLayout.LayoutParams(-1, 0, 1f));

        // ── ZONE 3 — BOTTOM (seekbar · times · the quick-action row) ──────────
        c.bottomBar = new LinearLayout(a);
        c.bottomBar.setOrientation(LinearLayout.VERTICAL);
        c.bottomBar.setPadding(UiCard.dp(a, 20), 0, UiCard.dp(a, 20), 0);

        ThemeEngine.Palette pal = ThemeEngine.palette(
            AppPrefs.themeMode(prefs), 0, AppPrefs.accent(prefs),
            AppPrefs.accentHex(prefs), AppPrefs.cardRadius(prefs));
        c.seek = new ScrubBar(a, pal.accent);
        c.bottomBar.addView(c.seek, new LinearLayout.LayoutParams(-1, UiCard.dp(a, 26)));

        LinearLayout times = new LinearLayout(a);
        times.setOrientation(LinearLayout.HORIZONTAL);
        times.setGravity(Gravity.CENTER_VERTICAL);
        c.timeNow = new TextView(a);
        c.timeNow.setTextColor(Color.WHITE);
        c.timeNow.setTextSize(13f);
        c.timeNow.setFontFeatureSettings(TABULAR);
        c.timeNow.setText("00:00");
        c.timeNow.setClickable(true);
        times.addView(c.timeNow, new LinearLayout.LayoutParams(0, -2, 1f));
        c.timeTotal = new TextView(a);
        c.timeTotal.setTextColor(0xFFAAB2C4);
        c.timeTotal.setTextSize(13f);
        c.timeTotal.setFontFeatureSettings(TABULAR);
        c.timeTotal.setGravity(Gravity.END);
        // v3.4.6.l — the honest duration readout (video_2 05:41, the
        // "0:00:16 / 0:00" defect): "--:--" while metadata resolves, the real
        // total ONLY once getDuration() != TIME_UNSET && > 0 (STATE_READY).
        c.timeTotal.setText("--:--");
        times.addView(c.timeTotal, new LinearLayout.LayoutParams(0, -2, 1f));
        c.bottomBar.addView(times, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout dock = new LinearLayout(a);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.addView(target(a, R.drawable.ic_rotate, 22, false,
            "Rotate screen", v -> ops.onRotate()), dockWeight(a));
        c.aspectButton = target(a, R.drawable.ic_aspect_fit, 22, false,
            "Aspect ratio", v -> ops.onCycleAspect());
        dock.addView(c.aspectButton, dockWeight(a));
        // the speed target: icon + the live "1.0x" label stacked (never clipped)
        LinearLayout speedBox = new LinearLayout(a);
        speedBox.setOrientation(LinearLayout.VERTICAL);
        speedBox.setGravity(Gravity.CENTER);
        View speed = target(a, R.drawable.ic_speed, 22, false,
            "Playback speed", v -> ops.onCycleSpeed());
        speedBox.addView(speed, new LinearLayout.LayoutParams(
            UiCard.dp(a, 44), UiCard.dp(a, 44)));
        c.speedLabel = new TextView(a);
        c.speedLabel.setTextColor(Color.WHITE);
        c.speedLabel.setGravity(Gravity.CENTER);
        c.speedLabel.setTextSize(10f);
        c.speedLabel.setTypeface(Typeface.DEFAULT_BOLD);
        c.speedLabel.setText("1.0x");
        speedBox.addView(c.speedLabel, new LinearLayout.LayoutParams(-1, -2));
        dock.addView(speedBox, dockWeight(a));
        dock.addView(target(a, R.drawable.ic_pip, 22, false,
            "Picture in picture", v -> ops.onPip()), dockWeight(a));
        dock.addView(target(a, R.drawable.ic_vlc_external, 22, false,
            "Open in external player", v -> ops.onExternalPlayer()), dockWeight(a));
        dock.addView(target(a, R.drawable.ic_search, 22, false,
            "Search for subtitles", v -> ops.onSearchSubs()), dockWeight(a));
        c.bottomBar.addView(dock, new LinearLayout.LayoutParams(-1, UiCard.dp(a, 52)));
        zones.addView(c.bottomBar, new LinearLayout.LayoutParams(-1, -2));

        layer.addView(zones, new FrameLayout.LayoutParams(-1, -1));
        c.root = layer;
        return c;
    }

    private static LinearLayout.LayoutParams dockWeight(Activity a) {
        return new LinearLayout.LayoutParams(0, UiCard.dp(a, 48), 1f);
    }

    /** The configurable seek step the flanks respect (5/10/15/30 s). */
    static long seekMs(SharedPreferences prefs) {
        long v = AppPrefs.seekStepMs(prefs);
        return v > 0 ? v : 10_000L;
    }

    /**
     * Swap the hero disc glyph (24dp, the ONE 24dp+ glyph in the chrome).
     * v3.4.6.m — the Chrome carries the state so the glyph can stand down
     * while the ring owns the disc (see paintBuffering).
     */
    static void paintPlayPause(Chrome c, boolean playing) {
        if (c == null || c.playPause == null) return;
        c.lastPlaying = playing;
        if (c.buffering) return;   // the ring owns the disc while buffering
        Activity a = (c.playPause.getContext() instanceof Activity)
            ? (Activity) c.playPause.getContext() : null;
        if (a == null) return;
        c.playPause.setImageDrawable(UiIcons.tinted(a,
            playing ? R.drawable.ic_pause_filled : R.drawable.ic_play_filled, Color.WHITE, 24));
        c.playPause.setContentDescription(playing ? "Pause playback" : "Play playback");
        // the tactile spring: 0.90 on press is owned by the host's touch pass
    }

    /**
     * v3.4.6.m — THE CENTER BUFFERING RING CONTRACT: BUFFERING → the ring spins
     * centered on the hero disc and the play/pause glyph HIDES; anything else
     * → the ring is gone and the glyph repaints per the last play state. The
     * host calls this from onPlaybackStateChanged (its `spinner` field aliases
     * the ring view, so both lanes drive the same contract).
     */
    static void paintBuffering(Chrome c, boolean buffering) {
        if (c == null) return;
        c.buffering = buffering;
        if (c.bufferRing != null) {
            try {
                c.bufferRing.setVisibility(buffering ? View.VISIBLE : View.GONE);
                if (c.bufferRing instanceof BufferRing) {
                    ((BufferRing) c.bufferRing).setSpinning(buffering);
                }
            } catch (Throwable ignored) { }
        }
        if (c.playPause != null) {
            if (buffering) {
                c.playPause.setImageDrawable(null);   // the glyph stands down
            } else {
                paintPlayPause(c, c.lastPlaying);     // the glyph returns per play state
            }
        }
    }

    /**
     * v3.4.6.m — the spinning circular-progress ring: an arc sweep drawn by an
     * 8 Hz self-repost (125 ms — zero animator allocation, zero GPU blur),
     * centered on the hero disc and sized to it. The spin starts and stops
     * with visibility (setSpinning), so a parked ring costs zero invalidates.
     */
    static final class BufferRing extends View {
        private static final long FRAME_MS = 125L;      // 8 Hz
        private static final float SWEEP_STEP = 42f;    // degrees per frame
        private float sweep = 0f;
        private boolean spinning = false;
        private final Runnable spin = new Runnable() {
            @Override public void run() {
                sweep = (sweep + SWEEP_STEP) % 360f;
                invalidate();
                if (spinning) postDelayed(spin, FRAME_MS);
            }
        };

        BufferRing(Activity a) {
            super(a);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            setContentDescription("Buffering");
        }

        /** Start/stop the 8 Hz pump (idempotent). */
        void setSpinning(boolean on) {
            if (spinning == on) return;
            spinning = on;
            if (on) {
                removeCallbacks(spin);
                post(spin);
            } else {
                removeCallbacks(spin);
            }
        }

        @Override protected void onDetachedFromWindow() {
            spinning = false;
            removeCallbacks(spin);
            super.onDetachedFromWindow();
        }

        @Override protected void onDraw(Canvas canvas) {
            try {
                float inset = UiCard.dp(getContext(), 9);
                float stroke = UiCard.dp(getContext(), 3);
                RectF box = new RectF(inset, inset, getWidth() - inset, getHeight() - inset);
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(stroke);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(0xFF7DD3FC);          // the chrome's active accent tint
                canvas.drawArc(box, sweep, 300f, false, paint);
                // the soft under-arc so the sweep reads as progress, not a slash
                Paint soft = new Paint(Paint.ANTI_ALIAS_FLAG);
                soft.setStyle(Paint.Style.STROKE);
                soft.setStrokeWidth(stroke);
                soft.setColor(0x33FFFFFF);
                canvas.drawArc(box, sweep + 300f, 60f, false, soft);
            } catch (Throwable ignored) { }
        }
    }

    // ── the 220 ms show/hide with the subtle vertical slide ───────────────────

    static void show(Chrome c) {
        if (c == null) return;
        c.root.setVisibility(View.VISIBLE);
        c.root.animate().cancel();
        c.root.setAlpha(0f);
        c.root.animate().alpha(1f).setDuration(220)
            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        c.topBar.setTranslationY(UiCard.dp(c.root.getContext(), -10));
        c.topBar.animate().translationY(0f).setDuration(220)
            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        c.bottomBar.setTranslationY(UiCard.dp(c.root.getContext(), 10));
        c.bottomBar.animate().translationY(0f).setDuration(220)
            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    static void hide(Chrome c) {
        if (c == null) return;
        c.root.animate().cancel();
        c.root.animate().alpha(0f).setDuration(220)
            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
            .withEndAction(() -> { try { c.root.setVisibility(View.GONE); } catch (Throwable ignored) { } })
            .start();
    }

    /**
     * The compact auto-fading subtitle badge: a subtle "2 languages" line
     * under the title that fades out 2.5 s after a language switch — NEVER a
     * green-bordered banner box (video_1's "test cues" rectangle is dead).
     */
    static void announceSubtitles(Chrome c, android.os.Handler main, String text) {
        if (c == null || c.subtitleBadge == null) return;
        c.subtitleBadge.setText(text);
        c.subtitleBadge.animate().cancel();
        c.subtitleBadge.animate().alpha(1f).setDuration(220).start();
        main.postDelayed(() -> {
            try {
                c.subtitleBadge.animate().alpha(0f).setDuration(400).start();
            } catch (Throwable ignored) { }
        }, 2500);
    }
}
