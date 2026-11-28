package lk.techworld.anantplay.sicast;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * v3.4.6.l — THE DARK-GLASS CINEMA SHEETS (the third module of the modular
 * player engine; the cramped purple v3.3 settings sheet with the clipped
 * "100%"/"ON" right edge and the overflowing horizontal language row are
 * DEAD — video_2 04:34–05:57).
 *
 * <p>THE SHEET CONTRACT (zero text clipping on ANY phone width):
 *
 * <ul>
 *   <li><b>STRICTLY 20 dp horizontal padding</b> on every sheet body (the
 *       v3.3 sheet padded 16 dp and its weighted rows still clipped — 20 dp
 *       plus weight-based columns is the guaranteed-clear contract);</li>
 *   <li><b>LinearLayout weights everywhere</b> — a label column takes
 *       {@code (0, WRAP, 1f)} and a value column is a WRAP_CONTENT sibling,
 *       so "100%", "ON" and long language names can NEVER clip off the right
 *       edge (the v3.3 bug was a WRAP_CONTENT value column at the far right
 *       of a 16 dp padded sheet — it physically did not fit);</li>
 *   <li><b>Wrapping chip flow</b> — the Subtitles language strip is a
 *       {@link Flow} of chips that wraps onto as many rows as needed (the
 *       v3.3 single horizontal row overflowed);</li>
 *   <li><b>The video keeps playing</b> — every sheet is a UiSheet overlay on
 *       the live SurfaceView (NEVER a surface detach, NEVER a pause, NEVER a
 *       recreation: the surface stays attached beneath the translucent
 *       scrim by construction).</li>
 * </ul>
 *
 * <p>Sheets: Subtitles (language flow + sync stepper + SL finder + live style
 * sliders + More tools), Audio/Video Tracks, Speed (0.25x–3.0x slider + preset
 * chips), and the FULL Player Customization sheet — synced 1:1 with the
 * Flutter Playback &amp; Subtitles settings page (same keys, same writer lane).
 */
final class CinemaSheets {

    private CinemaSheets() { }

    /** The sheet surface CinemaSheets needs from its host. */
    interface Ops {
        void switchLang(String code);
        void nudge(long ms);
        void openLkFinder();
        void pickLocalSubs();
        void freeTranslate();
        void retrySubs();
        void selectTrack(androidx.media3.common.TrackGroup g, int index);
        androidx.media3.common.Player player();
        void setSpeed(float speed);
        void applyAspect(String mode);
        void applyOrientation(String mode);
        void armSleep(long ms);
        void setVolumeBoost(int pct);
        void setDoubleTapSec(int sec);
        void setLongPressSpeedPerMille(int perMille);
        void setAutoHideMs(long ms);
        void putBool(String key, boolean v);
        boolean getBool(String key, boolean def);
        void openMoreTools();
        void sheetOpened();
    }

    // ── shared sheet scaffolding ─────────────────────────────────────────────

    /** The strict 20dp-padded sheet body UiSheet mounts. */
    private static LinearLayout body(Activity a) {
        LinearLayout b = new LinearLayout(a);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(UiCard.dp(a, 20), UiCard.dp(a, 6), UiCard.dp(a, 20), UiCard.dp(a, 8));
        return b;
    }

    /** The one label row that can never clip: weighted title + WRAP value. */
    private static void row(Activity a, LinearLayout into, String label, String value) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = new TextView(a);
        l.setText(label);
        l.setTextColor(0xFFC6D0E2);
        l.setTextSize(13f);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        if (value != null && !value.isEmpty()) {
            TextView v = new TextView(a);
            v.setText(value);
            v.setTextColor(0xFF9BE8FF);
            v.setTextSize(12.5f);
            v.setGravity(Gravity.END);
            r.addView(v, new LinearLayout.LayoutParams(-2, -2));
        }
        into.addView(r, new LinearLayout.LayoutParams(-1, -2));
    }

    // ── SUBTITLES SHEET ──────────────────────────────────────────────────────

    static UiSheet.Handle subtitles(Activity a, SharedPreferences prefs, Ops ops) {
        ThemeEngine.Palette p = pal(prefs);
        LinearLayout b = body(a);
        b.addView(UiSheet.header(a, p, "Subtitles",
            Chrome.pick("භාෂාව · සමමුහුර්තකරණය · විලාසය")), new LinearLayout.LayoutParams(-1, -2));

        // 1 — the WRAPPING language chip flow (the overflow row is dead)
        Flow flow = new Flow(a, UiCard.dp(a, 6));
        String active = activeLangOf(prefs);
        for (final Langs.Lang l : Langs.chips()) {
            flow.addView(UiChip.make(a, p, prefs, 0, l.nativeName,
                l.code.equals(active), v -> { ops.switchLang(l.code); }));
        }
        flow.addView(UiChip.make(a, p, prefs, R.drawable.ic_plus, "More",
            false, v -> ops.openMoreTools()));
        flow.addView(UiChip.make(a, p, prefs, R.drawable.ic_close, "Off",
            false, v -> ops.switchLang("off")));
        b.addView(flow, new LinearLayout.LayoutParams(-1, -2));

        // 2 — the SYNC STEPPER (−1s · −0.25s · 0.00s · +0.25s · +1s)
        LinearLayout sync = new LinearLayout(a);
        sync.setOrientation(LinearLayout.HORIZONTAL);
        sync.addView(step(a, "−1s", () -> ops.nudge(-1000)), weight());
        sync.addView(step(a, "−0.25s", () -> ops.nudge(-250)), weight());
        sync.addView(step(a, "0.00s", () -> ops.nudge(0)), weight());
        sync.addView(step(a, "+0.25s", () -> ops.nudge(250)), weight());
        sync.addView(step(a, "+1s", () -> ops.nudge(1000)), weight());
        b.addView(sync, new LinearLayout.LayoutParams(-1, -2));

        // 3 — the tools row (SL finder · local .srt/.zip · free translate)
        LinearLayout tools = new LinearLayout(a);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.addView(step(a, "Find SL Subs (10 sites)", () -> ops.openLkFinder()), weight());
        tools.addView(step(a, "Local .srt / .zip", () -> ops.pickLocalSubs()), weight());
        b.addView(tools, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout tools2 = new LinearLayout(a);
        tools2.setOrientation(LinearLayout.HORIZONTAL);
        tools2.addView(step(a, "Free Sinhala translate", () -> ops.freeTranslate()), weight());
        tools2.addView(step(a, "Retry subtitles", () -> ops.retrySubs()), weight());
        b.addView(tools2, new LinearLayout.LayoutParams(-1, -2));

        // 4 — LIVE STYLE SLIDERS (each write repaints the subtitle behind the
        //     sheet while the video keeps playing — the live preview contract)
        b.addView(UiSlider.make(a, p, prefs, "ප්‍රමාණය", "Size",
            10, 28, AppPrefs.fontSp(prefs), " sp", v -> {
                prefs.edit().putInt(AppPrefs.K_FONT_SP, v).apply();
                ops.sheetOpened();
            }), new LinearLayout.LayoutParams(-1, -2));
        b.addView(UiSlider.make(a, p, prefs, "පසුබිම් අඳුන", "Background",
            0, 95, AppPrefs.subBgOpacity(prefs), "%", v -> {
                prefs.edit().putInt(AppPrefs.K_SUB_BG_OPACITY, v).apply();
                ops.sheetOpened();
            }), new LinearLayout.LayoutParams(-1, -2));
        b.addView(UiSlider.make(a, p, prefs, "ස්ථානය", "Position",
            2, 35, AppPrefs.subBottomPct(prefs), "%", v -> {
                prefs.edit().putInt(AppPrefs.K_SUB_BOTTOM_PCT, v).apply();
                ops.sheetOpened();
            }), new LinearLayout.LayoutParams(-1, -2));

        UiSheet.Handle h = UiSheet.show(a, p, prefs, b, 66, null);
        ops.sheetOpened();
        return h;
    }

    // ── TRACKS SHEET ─────────────────────────────────────────────────────────

    static UiSheet.Handle tracks(Activity a, SharedPreferences prefs, Ops ops) {
        ThemeEngine.Palette p = pal(prefs);
        LinearLayout b = body(a);
        b.addView(UiSheet.header(a, p, "Audio / Quality",
            Chrome.pick("ධාවන පථ · විභේදනය · භාෂාව")), new LinearLayout.LayoutParams(-1, -2));
        androidx.media3.common.Player pl = ops.player();
        boolean any = false;
        if (pl != null) {
            try {
                androidx.media3.common.Tracks tracks = pl.getCurrentTracks();
                for (final androidx.media3.common.Tracks.Group g : tracks.getGroups()) {
                    if (g.getType() != androidx.media3.common.C.TRACK_TYPE_AUDIO
                            && g.getType() != androidx.media3.common.C.TRACK_TYPE_VIDEO) continue;
                    for (int i = 0; i < g.length; i++) {
                        final androidx.media3.common.TrackGroup tg = g.getMediaTrackGroup();
                        final int idx = i;
                        String label = PlayActivity.trackLabel(g.getType(), g.getTrackFormat(i));
                        boolean sel = g.isTrackSelected(i);
                        Button t = step(a, label, () -> ops.selectTrack(tg, idx));
                        if (sel) UiIcons.setCompound((TextView) t,
                            R.drawable.ic_check_circle, 0xFF86EFAC, 15, Gravity.START);
                        b.addView(t, new LinearLayout.LayoutParams(-1, -2));
                        any = true;
                    }
                }
            } catch (Throwable t) {
                Diag.w("tracks", "read: " + t);
            }
        }
        if (!any) {
            b.addView(UiGroup.empty(a, p, R.drawable.ic_audio_track,
                "One track only", "This stream carries no alternate audio or quality tracks."),
                new LinearLayout.LayoutParams(-1, -2));
        }
        UiSheet.Handle h = UiSheet.show(a, p, prefs, b, 60, null);
        return h;
    }

    // ── SPEED SHEET ──────────────────────────────────────────────────────────

    static UiSheet.Handle speed(Activity a, SharedPreferences prefs, Ops ops) {
        ThemeEngine.Palette p = pal(prefs);
        LinearLayout b = body(a);
        b.addView(UiSheet.header(a, p, "Playback speed",
            Chrome.pick("ධාවන වේගය 0.25x–3.0x")), new LinearLayout.LayoutParams(-1, -2));
        float cur = 1f;
        androidx.media3.common.Player pl = ops.player();
        try { if (pl != null) cur = pl.getPlaybackParameters().speed; } catch (Throwable ignored) { }
        b.addView(UiSlider.make(a, p, prefs, "වේගය", "Speed",
            25, 300, Math.round(cur * 100), "%", v -> {
                ops.setSpeed(v / 100f);
            }), new LinearLayout.LayoutParams(-1, -2));
        LinearLayout chips = new LinearLayout(a);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        for (final float f : new float[]{0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f}) {
            final float ff = f;
            chips.addView(step(a, String.format(java.util.Locale.US, "%.2gx", f),
                () -> ops.setSpeed(ff)), weight());
        }
        b.addView(chips, new LinearLayout.LayoutParams(-1, -2));
        UiSheet.Handle h = UiSheet.show(a, p, prefs, b, 46, null);
        return h;
    }

    // ── FULL PLAYER CUSTOMIZATION SHEET (synced 1:1 with Flutter) ────────────

    static UiSheet.Handle customization(Activity a, SharedPreferences prefs, Ops ops) {
        ThemeEngine.Palette p = pal(prefs);
        LinearLayout b = body(a);
        b.addView(UiSheet.header(a, p, "Player customization",
            Chrome.pick("සැකසුම් · 1:1 with Settings")), new LinearLayout.LayoutParams(-1, -2));

        String aspect = AppPrefs.aspectMode(prefs);
        b.addView(UiSegmented.row(a, p, prefs, "රූප පරිමාණය", "Aspect",
            new String[]{"Fit", "Fill", "16:9", "Stretch"}, aspectIndex(prefs),
            i -> ops.applyAspect(new String[]{"fit", "fill", "169", "stretch"}[i])),
            new LinearLayout.LayoutParams(-1, -2));

        String orient = prefs.getString(AppPrefs.K_ORIENTATION, "auto");
        b.addView(UiSegmented.row(a, p, prefs, "දිශානතිය", "Orientation",
            new String[]{"Auto", "Land", "Port"}, orientationIndex(orient),
            i -> ops.applyOrientation(new String[]{"auto", "landscape", "portrait"}[i])),
            new LinearLayout.LayoutParams(-1, -2));

        long sleep = AppPrefs.sleepTimerMs(prefs);
        b.addView(UiSegmented.row(a, p, prefs, "නිදාගැනීම", "Sleep timer",
            new String[]{"Off", "15m", "30m", "60m"}, sleepIndex(sleep),
            i -> ops.armSleep(new long[]{0L, 900000L, 1800000L, 3600000L}[i])),
            new LinearLayout.LayoutParams(-1, -2));

        b.addView(UiSlider.make(a, p, prefs, "හඬ වැඩි කිරීම", "Volume boost",
            100, 200, AppPrefs.volumeBoost(prefs), "%", v -> {
                prefs.edit().putInt(AppPrefs.K_VOLUME_BOOST, v).apply();
                ops.setVolumeBoost(v);
            }), new LinearLayout.LayoutParams(-1, -2));

        b.addView(UiSegmented.row(a, p, prefs, "දෙවරක් තට්ටුවේ තත්පර", "Double-tap seek",
            new String[]{"5 s", "10 s", "15 s", "30 s"}, doubleTapIndex(prefs),
            i -> ops.setDoubleTapSec(new int[]{5, 10, 15, 30}[i])),
            new LinearLayout.LayoutParams(-1, -2));

        b.addView(UiSegmented.row(a, p, prefs, "දිගු-ඔබන්න වේගය", "Long-press speed",
            new String[]{"1.5x", "2.0x", "2.5x"}, longPressIndex(prefs),
            i -> ops.setLongPressSpeedPerMille(new int[]{150, 200, 250}[i])),
            new LinearLayout.LayoutParams(-1, -2));

        b.addView(UiSegmented.row(a, p, prefs, "ස්වයං සැඟවුම", "Controls auto-hide",
            new String[]{"2 s", "3 s", "5 s"}, autoHideIndex(prefs),
            i -> ops.setAutoHideMs(new long[]{2000L, 3000L, 5000L}[i])),
            new LinearLayout.LayoutParams(-1, -2));

        row(a, b, "Swipe brightness / volume", onOff(AppPrefs.swipeBrightness(prefs), AppPrefs.swipeVolume(prefs)));
        row(a, b, "Swipe to seek", onOff(AppPrefs.swipeSeek(prefs), false));
        row(a, b, "Double-tap to seek", onOff(AppPrefs.doubleTapSeek(prefs), false));
        row(a, b, "Pinch to zoom", onOff(AppPrefs.pinchZoom(prefs), false));
        row(a, b, "Long-press speed", "1.5x / 2.0x / 2.5x");
        row(a, b, "Keep screen on", onOff(AppPrefs.keepScreenOn(prefs), false));
        row(a, b, "Frame-rate matching", onOff(AppPrefs.frameRateMatch(prefs), false));

        Button done = step(a, "Done", () -> { });
        done.setOnClickListener(v -> {
            if (b.getTag() instanceof UiSheet.Handle) {
                try { ((UiSheet.Handle) b.getTag()).dismiss(); } catch (Throwable ignored) { }
            }
        });
        b.addView(done, new LinearLayout.LayoutParams(-1, -2));

        UiSheet.Handle h = UiSheet.show(a, p, prefs, b, 74, null);
        b.setTag(h);
        return h;
    }

    // ── tiny helpers ─────────────────────────────────────────────────────────

    private static ThemeEngine.Palette pal(SharedPreferences prefs) {
        return ThemeEngine.palette(AppPrefs.themeMode(prefs), 0, AppPrefs.accent(prefs),
            AppPrefs.accentHex(prefs), AppPrefs.cardRadius(prefs));
    }

    private static String activeLangOf(SharedPreferences prefs) {
        return prefs.getString(AppPrefs.K_DEFAULT_LANG, "si");
    }

    private static String onOff(boolean on, boolean also) {
        return on ? "ON" : "OFF";
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, -2, 1f);
    }

    /** A 20dp-padded weighted sheet button (all caps off, no right-edge clip). */
    private static Button step(Activity a, String label, Runnable click) {
        Button b = new Button(a);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(12f);
        b.setTextColor(Color.WHITE);
        b.setPadding(UiCard.dp(a, 8), UiCard.dp(a, 9), UiCard.dp(a, 8), UiCard.dp(a, 9));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0x2431465F);
        g.setCornerRadius(UiCard.dp(a, 12));
        g.setStroke(UiCard.dp(a, 1), 0x2E4E6E8E);
        b.setBackground(g);
        b.setOnClickListener(v -> click.run());
        return b;
    }

    private static int aspectIndex(SharedPreferences p) {
        String v = AppPrefs.aspectMode(p);
        if (AppPrefs.ASPECT_FILL.equals(v)) return 1;
        if (AppPrefs.ASPECT_169.equals(v)) return 2;
        if (AppPrefs.ASPECT_STRETCH.equals(v)) return 3;
        return 0;
    }

    private static int orientationIndex(String v) {
        return "landscape".equals(v) ? 1 : ("portrait".equals(v) ? 2 : 0);
    }

    private static int sleepIndex(long v) {
        if (v == 900000L) return 1;
        if (v == 1800000L) return 2;
        if (v >= 3600000L) return 3;
        return 0;
    }

    private static int doubleTapIndex(SharedPreferences p) {
        int v = AppPrefs.doubleTapSec(p);
        return v == 5 ? 0 : (v == 15 ? 2 : (v == 30 ? 3 : 1));
    }

    private static int longPressIndex(SharedPreferences p) {
        int v = AppPrefs.longPressSpeedPerMille(p);
        return v == 150 ? 0 : (v == 250 ? 2 : 1);
    }

    private static int autoHideIndex(SharedPreferences p) {
        long v = AppPrefs.hudHideMs(p);
        if (v <= 2000L) return 0;
        if (v <= 3000L) return 1;
        return 2;
    }
}
