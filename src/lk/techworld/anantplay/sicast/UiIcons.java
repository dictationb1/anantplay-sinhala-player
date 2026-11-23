package lk.techworld.anantplay.sicast;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * v3.4.0 - THE ONLY WAY an icon reaches the screen.
 *
 * <p>Every drawable in {@code res/drawable/ic_*.xml} is white-on-transparent and is tinted
 * here, so ONE VectorDrawable serves every theme, every accent and both the outline and the
 * active states. No emoji, no PNG, no BitmapDrawable, no third-party icon font, no new
 * dependency - all of it is plain Android framework code compiled by {@code javac} + {@code d8}.
 *
 * <h3>Three rules that are enforced here, not by the caller</h3>
 * <ol>
 *   <li><b>{@link #setCompound} NEVER touches the text.</b> A chip that reads "Subtitles" keeps
 *       reading "Subtitles" and simply gains a leading vector. Only {@link #setIconOnly} clears
 *       the text, and it is called deliberately, by name.</li>
 *   <li><b>{@link #view} never sets a container-specific LayoutParams.</b> Returning an
 *       ImageView pre-configured with {@code LinearLayout.LayoutParams} is exactly how the
 *       v3.3.0 {@code ClassCastException: LinearLayout$LayoutParams cannot be cast to
 *       FrameLayout$LayoutParams} happened. The caller supplies the matching params at
 *       {@code addView()}; {@link #paramsFor} builds the right class for the parent.</li>
 *   <li><b>Every drawable is {@code mutate()}d before {@code setTint}.</b> Without it the tint
 *       leaks into the shared ConstantState and every other icon using that resource changes
 *       colour.</li>
 * </ol>
 *
 * <p>Thread rule: UI thread only (it touches View state).
 */
public final class UiIcons {

    private UiIcons() { }

    /** The icon grid size in dp - the grammar's viewport. */
    public static final int SIZE = 24;
    /** Material's minimum interactive size. A 24dp glyph inside a 48dp target. */
    public static final int TOUCH = 48;

    // ── drawables ─────────────────────────────────────────────────────────────

    /** A tinted, correctly-sized drawable. Returns null only if the resource is missing. */
    public static Drawable tinted(Context ctx, int resId, int color, int sizeDp) {
        if (ctx == null) return null;
        Drawable d;
        try {
            d = ctx.getDrawable(resId);
        } catch (Throwable t) {
            Diag.w("ui", "icon " + Integer.toHexString(resId) + ": " + t);
            return null;
        }
        if (d == null) return null;
        d = d.mutate();                      // rule 3: never share the tint
        d.setTint(color);
        int px = Math.round(sizeDp * ctx.getResources().getDisplayMetrics().density);
        d.setBounds(0, 0, px, px);
        return d;
    }

    /**
     * An ImageView carrying the icon. <b>No LayoutParams are set here on purpose</b> (rule 2) -
     * use {@link #paramsFor} or the parent's own params class at {@code addView()}.
     */
    public static ImageView view(Context ctx, int resId, int color, int sizeDp) {
        ImageView iv = new ImageView(ctx);
        iv.setImageDrawable(tinted(ctx, resId, color, sizeDp));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setAdjustViewBounds(false);
        int px = Math.round(sizeDp * ctx.getResources().getDisplayMetrics().density);
        iv.setMinimumWidth(px);
        iv.setMinimumHeight(px);
        return iv;
    }

    /**
     * A tappable icon with a real 48dp touch target around a {@code sizeDp} glyph.
     * The returned ImageView is square at {@code max(sizeDp, 48)}dp so it is finger-safe
     * inside a 48dp-minimum top bar. Still no LayoutParams (rule 2).
     */
    public static ImageView button(Context ctx, int resId, int color, int sizeDp) {
        int box = Math.max(sizeDp, TOUCH);
        ImageView iv = new ImageView(ctx);
        iv.setImageDrawable(tinted(ctx, resId, color, sizeDp));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int px = Math.round(box * ctx.getResources().getDisplayMetrics().density);
        iv.setMinimumWidth(px);
        iv.setMinimumHeight(px);
        iv.setClickable(true);
        iv.setFocusable(true);
        iv.setBackground(pressable(ctx));
        return iv;
    }

    /** A rounded ripple that reads as a button without a Material Components dependency. */
    public static Drawable pressable(Context ctx) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        shape.setColor(0x00000000);
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), shape, null);
    }

    /** A soft rounded square behind a leading icon in a settings row. */
    public static Drawable iconChip(Context ctx, int fillColor, int radiusDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setColor(fillColor);
        g.setCornerRadius(radiusDp * ctx.getResources().getDisplayMetrics().density);
        return g;
    }

    // ── TextView integration ─────────────────────────────────────────────────

    /**
     * Attaches a tinted vector to an EXISTING TextView <b>without touching its text</b>
     * (rule 1). This is the drop-in replacement for the old
     * {@code tv.setText("\uD83D\uDCAC Subtitles")} pattern: the label survives, the emoji does not.
     *
     * @param side one of {@link Gravity#START}, {@link Gravity#TOP}, {@link Gravity#END},
     *             {@link Gravity#BOTTOM}
     */
    public static void setCompound(TextView tv, int resId, int color, int sizeDp, int side) {
        if (tv == null) return;
        Drawable d = tinted(tv.getContext(), resId, color, sizeDp);
        if (d == null) return;
        Drawable l = null, t = null, r = null, b = null;
        switch (side) {
            case Gravity.TOP:    t = d; break;
            case Gravity.END:    r = d; break;
            case Gravity.BOTTOM: b = d; break;
            default:             l = d; break;
        }
        tv.setCompoundDrawables(l, t, r, b);
        int pad = Math.round(6 * tv.getResources().getDisplayMetrics().density);
        tv.setCompoundDrawablePadding(pad);
        // NOTE: tv.getText() is intentionally left untouched - see rule 1.
    }

    /**
     * Replaces a TextView's content with a single centred vector. Only for a view whose whole
     * purpose is the glyph (a tab icon, a HUD button). Text is cleared HERE and only here.
     */
    public static void setIconOnly(TextView tv, int resId, int color, int sizeDp) {
        if (tv == null) return;
        Drawable d = tinted(tv.getContext(), resId, color, sizeDp);
        if (d == null) return;
        tv.setText("");
        tv.setCompoundDrawables(null, null, null, null);
        tv.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
        tv.setGravity(Gravity.CENTER);
        tv.setCompoundDrawables(d, null, null, null);
    }

    // ── LayoutParams: the crash-proof factory ────────────────────────────────

    /**
     * Builds the LayoutParams class the PARENT actually requires, so a
     * {@code LinearLayout.LayoutParams} can never reach a {@code FrameLayout}
     * (the v3.3.1 crash) and {@code weight} can never reach a {@code FrameLayout}
     * (where it does not exist).
     *
     * @param weight 0 for none; honoured only by a LinearLayout parent
     */
    public static android.view.ViewGroup.LayoutParams paramsFor(
            android.view.ViewGroup parent, int w, int h, int weight, int gravity) {
        if (parent instanceof LinearLayout) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h, weight);
            lp.gravity = gravity;
            return lp;
        }
        if (parent instanceof android.widget.FrameLayout) {
            return new android.widget.FrameLayout.LayoutParams(w, h, gravity);
        }
        if (parent instanceof android.widget.GridLayout) {
            // GridLayout.LayoutParams has no (width,height) constructor - set them after.
            android.widget.GridLayout.LayoutParams glp = new android.widget.GridLayout.LayoutParams();
            glp.width = w;
            glp.height = h;
            glp.setGravity(gravity);
            return glp;
        }
        return new android.view.ViewGroup.LayoutParams(w, h);
    }

    /** Adds a child with the correct params for that parent. The only sanctioned add. */
    public static void add(android.view.ViewGroup parent, View child, int w, int h, int weight, int gravity) {
        if (parent == null || child == null) return;
        parent.addView(child, paramsFor(parent, w, h, weight, gravity));
    }
}
