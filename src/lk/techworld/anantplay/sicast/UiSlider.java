package lk.techworld.anantplay.sicast;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.function.IntConsumer;

/**
 * v3.4.0 - the themed slider: label + live value readout + the framework SeekBar with
 * the accent tint. The readout updates WHILE dragging, so a subtitle restyle is visible
 * before the finger lifts.
 */
final class UiSlider {

    private UiSlider() { }

    static LinearLayout make(Context c, ThemeEngine.Palette p, SharedPreferences prefs,
                             String si, String en, int min, int max, int value,
                             String suffix, IntConsumer onChange) {
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, UiCard.dp(c, 8), 0, UiCard.dp(c, 4));

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(c);
        label.setText(UiTokens.sinhalaFirst(prefs) ? si : en);
        label.setTextColor(p.text);
        UiTokens.body(label);
        head.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView valueView = new TextView(c);
        valueView.setText(value + suffix);
        valueView.setTextColor(p.accentDim);
        UiTokens.text(valueView, 12f, 600, 0f, false);
        head.addView(valueView, new LinearLayout.LayoutParams(-2, -2));
        wrap.addView(head, new LinearLayout.LayoutParams(-1, -2));

        SeekBar bar = new SeekBar(c);
        bar.setMax(Math.max(1, max - min));
        bar.setProgress(Math.max(0, Math.min(max - min, value - min)));
        try {
            bar.getProgressDrawable().setTint(p.accent);
            bar.getThumb().setTint(p.accent);
        } catch (Throwable ignored) { }
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int v = min + progress;
                valueView.setText(v + suffix);
                if (fromUser && onChange != null) onChange.accept(v);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        wrap.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        return wrap;
    }
}
