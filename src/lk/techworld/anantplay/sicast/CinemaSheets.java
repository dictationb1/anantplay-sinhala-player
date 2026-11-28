package lk.techworld.anantplay.sicast;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;
import java.util.function.Consumer;

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

        // 1 — the WRAPPING language chip flow (the overflow row is dead).
        //    v3.4.6.m — THE CHIP-TAP CONTRACT (Part_0.04 04:29–05:17): the
        //    selection highlight flips on the FIRST tap, in place, BEFORE any
        //    network — the sheet tracks every chip and repaints the accent
        //    pill instantly (the old sheet kept the stale highlight until a
        //    full rebuild).
        Flow flow = new Flow(a, UiCard.dp(a, 6));
        String active = activeLangOf(prefs);
        final java.util.Map<String, TextView> langChips = new java.util.LinkedHashMap<>();
        for (final Langs.Lang l : Langs.chips()) {
            final String code = l.code;
            TextView chip = UiChip.make(a, p, prefs, 0, l.nativeName,
                code.equals(active), v -> {
                    selectLangChip(a, p, prefs, langChips, code);
                    ops.switchLang(code);
                });
            langChips.put(code, chip);
            flow.addView(chip);
        }
        TextView offChip = UiChip.make(a, p, prefs, R.drawable.ic_close, "Off",
            false, v -> {
                selectLangChip(a, p, prefs, langChips, "off");
                ops.switchLang("off");     // v3.4.6.m — the dead Off button now works
            });
        langChips.put("off", offChip);
        flow.addView(offChip);
        flow.addView(UiChip.make(a, p, prefs, R.drawable.ic_plus, "More",
            false, v -> ops.openMoreTools()));
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
        //     sheet while the video keeps playing — the live preview contract).
        //     v3.4.6.m — each drag also shows the floating VALUE PILL above the
        //     thumb (showThumbBubble) so the exact value is readable mid-drag.
        b.addView(bubbled(a, p, UiSlider.make(a, p, prefs, "ප්‍රමාණය", "Size",
            10, 28, AppPrefs.fontSp(prefs), " sp", v -> {
                prefs.edit().putInt(AppPrefs.K_FONT_SP, v).apply();
                ops.sheetOpened();
            }), 10, " sp"), new LinearLayout.LayoutParams(-1, -2));
        b.addView(bubbled(a, p, UiSlider.make(a, p, prefs, "පසුබිම් අඳුන", "Background",
            0, 95, AppPrefs.subBgOpacity(prefs), "%", v -> {
                prefs.edit().putInt(AppPrefs.K_SUB_BG_OPACITY, v).apply();
                ops.sheetOpened();
            }), 0, "%"), new LinearLayout.LayoutParams(-1, -2));
        b.addView(bubbled(a, p, UiSlider.make(a, p, prefs, "ස්ථානය", "Position",
            2, 35, AppPrefs.subBottomPct(prefs), "%", v -> {
                prefs.edit().putInt(AppPrefs.K_SUB_BOTTOM_PCT, v).apply();
                ops.sheetOpened();
            }), 2, "%"), new LinearLayout.LayoutParams(-1, -2));

        UiSheet.Handle h = UiSheet.show(a, p, prefs, b, 66, null);
        insetSafe(h);
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
        insetSafe(h);   // v3.4.6.m — landscape clock never overlaps the rows
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
        insetSafe(h);   // v3.4.6.m — landscape clock never overlaps the rows
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

        b.addView(bubbled(a, p, UiSlider.make(a, p, prefs, "හඬ වැඩි කිරීම", "Volume boost",
            100, 200, AppPrefs.volumeBoost(prefs), "%", v -> {
                prefs.edit().putInt(AppPrefs.K_VOLUME_BOOST, v).apply();
                ops.setVolumeBoost(v);
            }), 100, "%"), new LinearLayout.LayoutParams(-1, -2));

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
        insetSafe(h);   // v3.4.6.m — "Volume boost"/"Sleep timer" never under the landscape clock
        return h;
    }

    // ── ORIENTATION SHEET (v3.4.6.m — the rectangular dialog is dead) ────────

    /**
     * v3.4.6.m — THE ORIENTATION SHEET: Auto-rotate (sensor) · Lock landscape
     * · Lock portrait as full-width dark-glass rows. The selection APPLIES on
     * the FIRST tap (persist + setRequestedOrientation through the host's
     * ops.applyOrientation lane) and highlights immediately — the sheet stays
     * open with the new row accented (no second confirmation, no dismiss).
     */
    static UiSheet.Handle orientation(Activity a, SharedPreferences prefs, Ops ops) {
        ThemeEngine.Palette p = pal(prefs);
        LinearLayout b = body(a);
        b.addView(UiSheet.header(a, p, "Orientation",
            Chrome.pick("දිශානතිය · auto / landscape / portrait")),
            new LinearLayout.LayoutParams(-1, -2));
        final String[] modes = {AppPrefs.ORIENT_AUTO, AppPrefs.ORIENT_LANDSCAPE, AppPrefs.ORIENT_PORTRAIT};
        final String[] labels = {"Auto-rotate (sensor)", "Lock landscape", "Lock portrait"};
        final String[] cur = {AppPrefs.orientation(prefs)};
        final Button[] rows = new Button[3];
        for (int i = 0; i < rows.length; i++) {
            final int idx = i;
            rows[i] = step(a, labels[i], () -> {
                cur[0] = modes[idx];
                paintChoiceRows(a, p, rows, modes, cur[0]);
                // v3.4.6.m — first tap: apply + highlight, the sheet stays open.
                ops.applyOrientation(modes[idx]);
            });
            b.addView(rows[i], new LinearLayout.LayoutParams(-1, -2));
        }
        paintChoiceRows(a, p, rows, modes, cur[0]);
        UiSheet.Handle h = UiSheet.show(a, p, prefs, b, 40, null);
        insetSafe(h);
        return h;
    }

    /** Paints the choice rows: the selected one is the accent pill + check. */
    private static void paintChoiceRows(Activity a, ThemeEngine.Palette p, Button[] rows,
                                        String[] modes, String cur) {
        for (int i = 0; i < rows.length; i++) {
            paintChoice(a, p, rows[i], modes[i].equals(cur));
        }
    }

    /** One accent/neutral row paint (shared by the choice sheets). */
    private static void paintChoice(Activity a, ThemeEngine.Palette p, Button row, boolean on) {
        try {
            GradientDrawable g = new GradientDrawable();
            g.setColor(on ? p.accent : 0x2431465F);
            g.setCornerRadius(UiCard.dp(a, 12));
            g.setStroke(UiCard.dp(a, 1), on ? p.accent : 0x2E4E6E8E);
            row.setBackground(g);
            row.setTextColor(on ? 0xFF0A0A14 : Color.WHITE);
            if (on) UiIcons.setCompound(row, R.drawable.ic_check_circle, 0xFF0A0A14, 15, Gravity.END);
            else row.setCompoundDrawables(null, null, null, null);
        } catch (Throwable t) {
            Diag.w("sheet", "choice paint: " + t);
        }
    }

    // ── ALL-LANGUAGES SHEET (v3.4.6.m — searchable, the old list dialog is dead) ──

    /**
     * v3.4.6.m — THE SEARCHABLE LANGUAGE SHEET: a filter field on top + the
     * full {@link Langs} registry as clean rows, filtered live as the query
     * types. Tapping a row behaves EXACTLY like a language chip: instant
     * highlight + switchLang (which persists K_DEFAULT_LANG, shows the live
     * "Fetching <Language> subtitles…" line and refetches/translates).
     */
    static UiSheet.Handle languages(Activity a, SharedPreferences prefs, Ops ops) {
        ThemeEngine.Palette p = pal(prefs);
        LinearLayout b = body(a);
        b.addView(UiSheet.header(a, p, "All subtitle languages",
            Chrome.pick("සියලු උපසිරැසි භාෂා · search")), new LinearLayout.LayoutParams(-1, -2));
        // the search field (dark-glass, live filtering)
        final EditText query = new EditText(a);
        query.setHint(Chrome.pick("භාෂාව සොයන්න · search a language"));
        query.setTextSize(13.5f);
        query.setTextColor(0xFFE2E8F0);
        query.setHintTextColor(0xFF8C97AB);
        query.setSingleLine(true);
        query.setAllCaps(false);
        GradientDrawable qg = new GradientDrawable();
        qg.setColor(0x2431465F);
        qg.setCornerRadius(UiCard.dp(a, 12));
        qg.setStroke(UiCard.dp(a, 1), 0x2E4E6E8E);
        query.setBackground(qg);
        query.setPadding(UiCard.dp(a, 12), UiCard.dp(a, 10), UiCard.dp(a, 12), UiCard.dp(a, 10));
        b.addView(query, new LinearLayout.LayoutParams(-1, -2));
        // the live row list
        final LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        b.addView(list, new LinearLayout.LayoutParams(-1, -2));
        final String[] active = {activeLangOf(prefs)};
        final Runnable[] fill = {null};
        fill[0] = () -> fillLangRows(a, p, list, query.getText().toString(), active[0], code -> {
            active[0] = code;
            fill[0].run();          // instant re-highlight, same tap
            ops.switchLang(code);   // the chip-tap contract (persist + refetch)
        });
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence cs, int i0, int i1, int i2) { }
            @Override public void onTextChanged(CharSequence cs, int i0, int i1, int i2) { }
            @Override public void afterTextChanged(Editable e) { fill[0].run(); }
        });
        fill[0].run();
        UiSheet.Handle h = UiSheet.show(a, p, prefs, b, 74, null);
        insetSafe(h);
        return h;
    }

    /** (Re)builds the filtered language rows; the active row is accented. */
    private static void fillLangRows(Activity a, ThemeEngine.Palette p, LinearLayout list,
                                     CharSequence q, String active, Consumer<String> pick) {
        try {
            list.removeAllViews();
            String ql = q == null ? "" : q.toString().trim().toLowerCase(Locale.US);
            boolean any = false;
            for (final Langs.Lang l : Langs.ALL) {
                if (!ql.isEmpty()
                        && !l.nativeName.toLowerCase(Locale.US).contains(ql)
                        && !l.english.toLowerCase(Locale.US).contains(ql)
                        && !l.code.toLowerCase(Locale.US).contains(ql)) continue;
                final String code = l.code;
                Button row = step(a, l.nativeName + " (" + l.english + ")",
                    () -> pick.accept(code));
                paintChoice(a, p, row, code.equals(active));
                list.addView(row, new LinearLayout.LayoutParams(-1, -2));
                any = true;
            }
            if (!any) {
                TextView none = new TextView(a);
                none.setText(Chrome.pick("ගැළපෙන භාෂාවක් නැත · no matching language"));
                none.setTextColor(p.desc);
                none.setTextSize(12.5f);
                none.setPadding(0, UiCard.dp(a, 10), 0, UiCard.dp(a, 10));
                list.addView(none, new LinearLayout.LayoutParams(-1, -2));
            }
        } catch (Throwable t) {
            Diag.w("sheet", "lang rows: " + t);
        }
    }

    // ── v3.4.6.m — the chip selection painter (the instant highlight) ─────────

    private static void selectLangChip(Activity a, ThemeEngine.Palette p, SharedPreferences prefs,
                                       java.util.Map<String, TextView> chips, String code) {
        for (java.util.Map.Entry<String, TextView> e : chips.entrySet()) {
            UiChip.setSelected(a, e.getValue(), p, prefs, code.equals(e.getKey()));
        }
    }

    // ── v3.4.6.m — THE LIVE THUMB VALUE BUBBLES (all sheet sliders) ───────────

    /**
     * Wires the floating VALUE PILL into a UiSlider row: while the thumb
     * drags, an accent pill rides directly above it (x computed from the
     * progress fraction × the real track width, clamped inside the sheet);
     * 600 ms after the drag stops the pill fades out. The pill lives on the
     * sheet's overlay FrameLayout (above the ScrollView), is built purely in
     * code (house law: no res XML) and never touches UiSlider's own change
     * listener (the touch pass-through returns false).
     */
    private static LinearLayout bubbled(Activity a, ThemeEngine.Palette p, LinearLayout sliderRow,
                                        int min, String suffix) {
        try {
            SeekBar bar = null;
            for (int i = 0; i < sliderRow.getChildCount(); i++) {
                if (sliderRow.getChildAt(i) instanceof SeekBar) {
                    bar = (SeekBar) sliderRow.getChildAt(i);
                    break;
                }
            }
            if (bar == null) return sliderRow;
            final SeekBar b = bar;
            b.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_MOVE: {
                        int val = min + b.getProgress();
                        showThumbBubble(a, p, b, val + suffix);
                        return false;   // UiSlider's own listener keeps working
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        hideThumbBubbleSoon(b);
                        return false;
                    default:
                        return false;
                }
            });
        } catch (Throwable t) {
            Diag.w("sheet", "thumb bubble wire: " + t);
        }
        return sliderRow;
    }

    /** The per-slider bubble holder (stored as the SeekBar's tag). */
    private static final class Thumb {
        final TextView bubble;
        final Runnable hide = new Runnable() {
            @Override public void run() { bubble.setVisibility(View.GONE); }
        };
        Thumb(Activity a) {
            bubble = new TextView(a);
            bubble.setTextColor(Color.WHITE);
            bubble.setTextSize(12.5f);
            bubble.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            bubble.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xFF1E4E4F);                       // the dark-teal accent pill
            g.setCornerRadius(UiCard.dp(a, 999));
            g.setStroke(UiCard.dp(a, 1), 0x40FFFFFF);
            bubble.setBackground(g);
            bubble.setPadding(UiCard.dp(a, 12), UiCard.dp(a, 5), UiCard.dp(a, 12), UiCard.dp(a, 5));
            bubble.setVisibility(View.GONE);
            bubble.setElevation(UiCard.dp(a, 6));
            bubble.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
    }

    private static Thumb thumbOf(Activity a, SeekBar b) {
        Object t = b.getTag();
        if (t instanceof Thumb) return (Thumb) t;
        Thumb th = new Thumb(a);
        b.setTag(th);
        return th;
    }

    /** Show (or move) the value pill above the dragging thumb. */
    private static void showThumbBubble(Activity a, ThemeEngine.Palette p, SeekBar b, String value) {
        try {
            // v3.4.6.m — declared as the CONCRETE FrameLayout the loop proves
            // it is (verify_layouts must be able to prove the addView params
            // type matches the host — a bare ViewGroup is unprovable).
            android.widget.FrameLayout overlay = null;
            for (ViewParent vp = b.getParent(); vp != null; vp = vp.getParent()) {
                if (vp instanceof android.widget.FrameLayout) {
                    overlay = (android.widget.FrameLayout) vp;
                    break;
                }
            }
            if (overlay == null) return;
            Thumb th = thumbOf(a, b);
            if (th.bubble.getParent() != overlay) {
                if (th.bubble.getParent() instanceof ViewGroup) {
                    ((ViewGroup) th.bubble.getParent()).removeView(th.bubble);
                }
                overlay.addView(th.bubble, new FrameLayout.LayoutParams(
                    -2, -2, Gravity.TOP | Gravity.START));
            }
            b.removeCallbacks(th.hide);   // dragging: never auto-hide mid-drag
            th.bubble.setText(value);
            th.bubble.setVisibility(View.VISIBLE);
            th.bubble.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            int w = th.bubble.getMeasuredWidth();
            int h = th.bubble.getMeasuredHeight();
            // the thumb's x inside the track (progress fraction × track width)
            int track = Math.max(1, b.getWidth() - b.getPaddingLeft() - b.getPaddingRight());
            float f = b.getMax() <= 0 ? 0f : b.getProgress() / (float) b.getMax();
            int cxBar = b.getPaddingLeft() + Math.round(f * track);
            int[] barLoc = new int[2], overlayLoc = new int[2];
            b.getLocationOnScreen(barLoc);
            overlay.getLocationOnScreen(overlayLoc);
            int cx = (barLoc[0] - overlayLoc[0]) + cxBar;
            int topY = (barLoc[1] - overlayLoc[1]) - h - UiCard.dp(a, 12);
            android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) th.bubble.getLayoutParams();
            lp.leftMargin = Math.max(UiCard.dp(a, 8),
                Math.min(cx - w / 2, Math.max(0, overlay.getWidth() - w - UiCard.dp(a, 8))));
            lp.topMargin = Math.max(UiCard.dp(a, 4), topY);
            th.bubble.setLayoutParams(lp);
        } catch (Throwable t) {
            Diag.w("sheet", "bubble show: " + t);
        }
    }

    /** 600 ms after the drag stops, the pill hides. */
    private static void hideThumbBubbleSoon(SeekBar b) {
        try {
            Object t = b.getTag();
            if (!(t instanceof Thumb)) return;
            final Thumb th = (Thumb) t;
            b.removeCallbacks(th.hide);
            b.postDelayed(th.hide, 600);
        } catch (Throwable ignored) { }
    }

    // ── v3.4.6.m — LANDSCAPE STATUS-BAR CLIPPING KILL (sheet insets) ─────────

    /**
     * Every CinemaSheets sheet's ScrollView content pads by
     * {@code statusBars() | displayCutout()} (top) and
     * {@code navigationBars() | displayCutout()} (bottom), so the "Volume
     * boost" / "Sleep timer" rows NEVER overlap the landscape clock. The
     * padding rides the ScrollView (not the panel background), so the sheet's
     * own corner rounding stays untouched.
     */
    private static void insetSafe(UiSheet.Handle h) {
        if (h == null || h.panel == null) return;
        try {
            View target = null;
            if (h.panel instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) h.panel;
                for (int i = 0; i < g.getChildCount(); i++) {
                    if (g.getChildAt(i) instanceof ScrollView) {
                        target = g.getChildAt(i);
                        ((ScrollView) target).setClipToPadding(false);
                        break;
                    }
                }
            }
            if (target == null) target = h.panel;
            final View sc = target;
            sc.setOnApplyWindowInsetsListener((v, insets) -> {
                try {
                    android.graphics.Insets top = insets.getInsets(
                        WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout());
                    android.graphics.Insets bottom = insets.getInsets(
                        WindowInsets.Type.navigationBars() | WindowInsets.Type.displayCutout());
                    v.setPadding(0, top.top, 0, bottom.bottom);
                } catch (Throwable ignored) { }
                return insets;
            });
        } catch (Throwable t) {
            Diag.w("sheet", "insets: " + t);
        }
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
