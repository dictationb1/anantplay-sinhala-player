package lk.techworld.anantplay.sicast;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** App-wide typeface selection, keeping cinema subtitle styling independent. */
public final class UiFonts {
    private UiFonts() { }

    private static final Object SUBTITLE_TAG = new Object();
    private static volatile Typeface inter;
    private static volatile boolean interLoadAttempted;

    public static void markSubtitle(TextView view) {
        if (view != null) view.setTag(SUBTITLE_TAG);
    }

    public static Typeface typeface(Context context, SharedPreferences prefs, int weight) {
        int requested = Math.max(100, Math.min(900, weight));
        String family = AppPrefs.uiFont(prefs);
        if (AppPrefs.UI_FONT_ROUNDED.equals(family)) {
            return styled(Typeface.create("sans-serif-rounded", Typeface.NORMAL), requested);
        }
        if (AppPrefs.UI_FONT_MONOSPACE.equals(family)) {
            return styled(Typeface.MONOSPACE, requested);
        }
        if (AppPrefs.UI_FONT_SYSTEM.equals(family)) {
            return styled(Typeface.SANS_SERIF, requested);
        }
        Typeface modern = modernTypeface(context);
        return styled(modern == null ? Typeface.SANS_SERIF : modern, requested);
    }

    /** The bundled Inter variable face; system sans is the explicit fallback. */
    public static Typeface modernTypeface(Context context) {
        if (interLoadAttempted) return inter;
        synchronized (UiFonts.class) {
            if (interLoadAttempted) return inter;
            try {
                inter = Typeface.createFromAsset(context.getAssets(), "fonts/Inter[opsz,wght].ttf");
            } catch (Throwable ignored) {
                inter = null;
            }
            interLoadAttempted = true;
        }
        return inter;
    }

    /** Dedicated Cinema Bold subtitle face, paired with the Sinhala Noto fallback. */
    public static Typeface cinemaTypeface(Context context, boolean bold) {
        Typeface base = modernTypeface(context);
        if (base == null) base = Typeface.SANS_SERIF;
        return styled(base, bold ? 700 : 600);
    }

    public static void applyTree(View root, SharedPreferences prefs) {
        if (root == null) return;
        if (root instanceof TextView) {
            TextView text = (TextView) root;
            if (text.getTag() != SUBTITLE_TAG) {
                int weight = text.getTypeface() != null && text.getTypeface().isBold() ? 700 : 400;
                Typeface selected = typeface(text.getContext(), prefs, weight);
                CharSequence value = text.getText();
                if (AppPrefs.UI_FONT_MODERN.equals(AppPrefs.uiFont(prefs))
                        && containsSinhala(value)) {
                    // Inter supplies the Latin UI; the bundled Noto face supplies
                    // Sinhala glyphs instead of relying on a vendor system fallback.
                    selected = UiTokens.si(text.getContext(), weight >= 600);
                }
                text.setTypeface(selected);
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyTree(group.getChildAt(i), prefs);
            }
        }
    }

    private static Typeface styled(Typeface base, int weight) {
        if (base == null) base = Typeface.SANS_SERIF;
        if (Build.VERSION.SDK_INT >= 28) {
            try { return Typeface.create(base, weight, false); }
            catch (Throwable ignored) { }
        }
        return Typeface.create(base, weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
    }

    private static boolean containsSinhala(CharSequence value) {
        if (value == null) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '\u0D80' && c <= '\u0DFF') return true;
        }
        return false;
    }
}
