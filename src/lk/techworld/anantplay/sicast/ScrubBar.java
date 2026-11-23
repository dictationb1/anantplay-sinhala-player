package lk.techworld.anantplay.sicast;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * v3.4.0 - THE CINEMA SCRUB BAR (replaces the framework SeekBar).
 *
 * <p>What the stock {@code SeekBar} could not do and this view does:
 * <ul>
 *   <li>a 3dp hairline that grows to 6dp while the finger is down (tactile, VLC-grade),</li>
 *   <li>a separate BUFFERED layer at 30% white behind the played accent,</li>
 *   <li>a 13dp knob that only appears while touching,</li>
 *   <li>a 24dp vertical touch slop so a slightly-off tap still scrubs,</li>
 *   <li>a live drag-preview callback, so the HUD can show {@code +02:14} before release.</li>
 * </ul>
 *
 * <p>It is a plain {@link View}: no dependency, no accessibility service, and it reports
 * its own height through {@link #onMeasure} so a LinearLayout parent never needs a cast.
 */
final class ScrubBar extends View {

    interface Listener {
        void onScrubStart();
        /** @param positionMs the previewed position while dragging */
        void onScrub(long positionMs);
        void onScrubEnd(long positionMs);
    }

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint buffer = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint played = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private long durationMs = 1, positionMs = 0, bufferedMs = 0;
    private boolean dragging;
    private float downX, downY;
    private Listener listener;

    private final int accent, bufferedColor, trackColor;
    private final float density;

    ScrubBar(Context c, int accentColor) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        accent = accentColor;
        bufferedColor = 0x4DFFFFFF;
        trackColor = 0x33FFFFFF;
        track.setColor(trackColor);
        buffer.setColor(bufferedColor);
        played.setColor(accent);
        knob.setColor(0xFFFFFFFF);
        setFocusable(true);
    }

    void setListener(Listener l) { listener = l; }

    void setDuration(long ms) {
        durationMs = Math.max(1, ms);
        invalidate();
    }

    void setPosition(long ms) {
        if (dragging) return;
        positionMs = Math.max(0, Math.min(durationMs, ms));
        invalidate();
    }

    void setBuffered(long ms) {
        bufferedMs = Math.max(0, Math.min(durationMs, ms));
        invalidate();
    }

    long position() { return positionMs; }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int h = Math.round(26 * density);          // 26dp: the 24dp touch slop + the bar
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, resolveSize(h, hSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        float h = dragging ? 6 * density : 3 * density;
        float cy = getHeight() / 2f;
        float pad = 2 * density;
        float left = pad, right = getWidth() - pad;
        float radius = h / 2f;

        rect.set(left, cy - h / 2f, right, cy + h / 2f);
        canvas.drawRoundRect(rect, radius, radius, track);

        float bFrac = durationMs <= 0 ? 0 : (float) bufferedMs / durationMs;
        if (bFrac > 0.001f) {
            rect.set(left, cy - h / 2f, left + (right - left) * bFrac, cy + h / 2f);
            canvas.drawRoundRect(rect, radius, radius, buffer);
        }

        float pFrac = durationMs <= 0 ? 0 : (float) positionMs / durationMs;
        pFrac = Math.max(0f, Math.min(1f, pFrac));
        if (pFrac > 0f) {
            rect.set(left, cy - h / 2f, left + (right - left) * pFrac, cy + h / 2f);
            canvas.drawRoundRect(rect, radius, radius, played);
        }

        if (dragging) {
            canvas.drawCircle(left + (right - left) * pFrac, cy, 6.5f * density, knob);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                dragging = true;
                seekToX(e.getX());
                if (listener != null) listener.onScrubStart();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                // 24dp vertical slop: a finger that drifts still scrubs instead of cancelling
                if (Math.abs(e.getY() - downY) < 24 * density) seekToX(e.getX());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (listener != null) listener.onScrubEnd(positionMs);
                invalidate();
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private void seekToX(float x) {
        float pad = 2 * density;
        float left = pad, right = getWidth() - pad;
        float frac = right <= left ? 0 : (x - left) / (right - left);
        frac = Math.max(0f, Math.min(1f, frac));
        positionMs = Math.round(frac * durationMs);
        if (listener != null) listener.onScrub(positionMs);
        invalidate();
    }
}
