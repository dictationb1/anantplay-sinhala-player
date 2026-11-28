package lk.techworld.anantplay.sicast;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * v3.4.6.l — A minimal wrapping flow container (the Subtitles sheet's language
 * chip flow). The v3.3 sheet packed every chip into ONE horizontal row that
 * overflowed off-screen (video_2 04:34); this container wraps children onto
 * as many rows as the width needs, left-aligned, with a uniform gap.
 *
 * <p>Measured strictly: children keep their own LayoutParams widths (WRAP
 * chips), each row breaks when the next child would cross the available
 * width. Height is the sum of row heights plus the gap. No layout resources.
 */
final class Flow extends ViewGroup {

    private final int gap;

    Flow(Context c, int gapPx) {
        super(c);
        this.gap = gapPx;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = View.MeasureSpec.getSize(widthSpec);
        int mode = View.MeasureSpec.getMode(widthSpec);
        if (mode == View.MeasureSpec.UNSPECIFIED) width = 1080;
        int heightUsed = 0, rowHeight = 0, x = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            measureChild(child, widthSpec, heightSpec);
            int w = child.getMeasuredWidth();
            int h = child.getMeasuredHeight();
            if (x > 0 && x + w > width) {
                heightUsed += rowHeight + gap;
                rowHeight = 0;
                x = 0;
            }
            x += w + gap;
            rowHeight = Math.max(rowHeight, h);
        }
        heightUsed += rowHeight;
        setMeasuredDimension(width, Math.max(heightUsed, getSuggestedMinimumHeight()));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = getWidth();
        int x = 0, y = 0, rowHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int w = child.getMeasuredWidth();
            int h = child.getMeasuredHeight();
            if (x > 0 && x + w > width) {
                y += rowHeight + gap;
                rowHeight = 0;
                x = 0;
            }
            child.layout(x, y, x + w, y + h);
            x += w + gap;
            rowHeight = Math.max(rowHeight, h);
        }
    }
}
