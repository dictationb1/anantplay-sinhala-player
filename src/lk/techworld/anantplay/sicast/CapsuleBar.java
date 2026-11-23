package lk.techworld.anantplay.sicast;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * v3.4.0 - the Apple-style vertical capsule for live Brightness / Volume feedback.
 *
 * <p>26 x 112 dp, radius 999, a 10% white shell, an 85% white fill that rises with the
 * value, and the vector glyph at the bottom. It is drawn, not composed, so it costs one
 * View and no layout pass.
 */
final class CapsuleBar extends View {

    private final Paint shell = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Drawable icon;
    private final float density;
    private float value = 0.5f;
    private final int wDp, hDp;

    CapsuleBar(Context c, int iconRes, int tint, int widthDp, int heightDp) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        wDp = widthDp;
        hDp = heightDp;
        shell.setColor(0x1AFFFFFF);
        fill.setColor(0xD9FFFFFF);
        Drawable d = null;
        try {
            d = c.getDrawable(iconRes);
            if (d != null) {
                d = d.mutate();
                d.setTint(tint);
            }
        } catch (Throwable t) {
            Diag.w("capsule", "icon: " + t);
        }
        icon = d;
    }

    void setValue(float v) {
        value = Math.max(0f, Math.min(1f, v));
        invalidate();
    }

    float value() { return value; }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(Math.round(wDp * density), Math.round(hDp * density));
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float r = w / 2f;
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, r, r, shell);

        float fillH = Math.max(r * 2f, h * value);
        rect.set(0, h - fillH, w, h);
        canvas.drawRoundRect(rect, r, r, fill);

        if (icon != null) {
            int s = Math.round(13 * density);
            int cx = Math.round(w / 2f);
            int bottom = Math.round(h - 7 * density);
            icon.setBounds(cx - s / 2, bottom - s, cx + s / 2, bottom);
            icon.draw(canvas);
        }
    }
}
