package lk.techworld.anantplay.sicast;

/**
 * v3.3.0 — THE ASPECT-RATIO ENGINE (pure math, zero Android imports, lab-provable).
 *
 * <p>THE BUG THIS KILLS (v3.2.0 screenshot 2/3): the SurfaceView was added to the
 * root FrameLayout with MATCH_PARENT/MATCH_PARENT and nothing ever listened to
 * {@code Player.Listener.onVideoSizeChanged}. A 16:9 movie therefore filled a
 * 1080×2400 portrait screen — 1920×1080 pixels forced into 1080×2400 = a 2.37×
 * vertical stretch. Every open-source player (VLC, NextPlayer, AnantPlay) instead
 * sizes the video SURFACE from the decoded video size and CENTERS it on black.
 *
 * <p>This class is that sizing decision, in one testable place:
 * {@link #compute(int, int, float, int, int, int)} returns the exact
 * {@code {width, height}} the surface must have inside a container of
 * {@code containerW × containerH}, for four modes:
 *
 * <ul>
 *   <li>{@link #FIT} (default) — letterbox / pillarbox, exact original aspect,
 *       NEVER distorted. 1920×1080 in 1080×2400 → 1080×608.</li>
 *   <li>{@link #FILL} — proportional crop to cover the screen (VLC/YouTube
 *       pinch-to-zoom): 1920×1080 in 1080×2400 → 4267×2400 (aspect kept).</li>
 *   <li>{@link #STRETCH} — the only distorting mode, and only if the user asks.</li>
 *   <li>{@link #R16_9} — force a 16:9 box whatever the stream claims.</li>
 * </ul>
 *
 * <p>{@code par} is Media3's {@code VideoSize.pixelWidthHeightRatio} (anamorphic
 * content: 1440×1080 with par 1.333 is really 1920×1080). A non-finite or
 * non-positive par is treated as 1.
 */
final class VideoViewport {

    static final int FIT = 0;
    static final int FILL = 1;
    static final int STRETCH = 2;
    static final int R16_9 = 3;

    /** The 16:9 target used by {@link #R16_9}. */
    static final double SIXTEEN_NINE = 16.0 / 9.0;

    private VideoViewport() { }

    /** The user-facing preference string → a mode. Unknown/absent = FIT (the safe default). */
    static int modeFrom(String s) {
        if (s == null) return FIT;
        String m = s.trim().toLowerCase(java.util.Locale.US);
        if (m.equals("fill") || m.equals("crop") || m.equals("zoom")) return FILL;
        if (m.equals("stretch")) return STRETCH;
        if (m.equals("16:9") || m.equals("169") || m.equals("16_9")) return R16_9;
        return FIT;
    }

    static String modeName(int mode) {
        switch (mode) {
            case FILL: return "fill";
            case STRETCH: return "stretch";
            case R16_9: return "16:9";
            default: return "fit";
        }
    }

    /** The clean chip label ("Fit", "Fill", "16:9", "Stretch"). */
    static String modeLabel(int mode) {
        switch (mode) {
            case FILL: return "Fill";
            case STRETCH: return "Stretch";
            case R16_9: return "16:9";
            default: return "Fit";
        }
    }

    /** Fit → Fill → 16:9 → Stretch → Fit (the 📐 chip cycle). */
    static int next(int mode) {
        switch (mode) {
            case FIT: return FILL;
            case FILL: return R16_9;
            case R16_9: return STRETCH;
            default: return FIT;
        }
    }

    /**
     * The exact surface size for a decoded video inside a container.
     *
     * @param videoW     decoded width in pixels (VideoSize.width)
     * @param videoH     decoded height in pixels (VideoSize.height)
     * @param par        pixelWidthHeightRatio (1 for square pixels)
     * @param containerW the available width (the video host view's width)
     * @param containerH the available height (the video host view's height)
     * @param mode       FIT / FILL / STRETCH / R16_9
     * @return {width, height} — always ≥ 1×1, never larger than needed
     */
    static int[] compute(int videoW, int videoH, float par, int containerW, int containerH, int mode) {
        int cw = Math.max(1, containerW);
        int ch = Math.max(1, containerH);
        if (videoW <= 0 || videoH <= 0) return new int[]{cw, ch};        // unknown size yet: fill, never crash
        float p = (Float.isNaN(par) || Float.isInfinite(par) || par <= 0f) ? 1f : par;
        double displayW = videoW * (double) p;                            // anamorphic correction
        double displayH = videoH;

        if (mode == STRETCH) return new int[]{cw, ch};

        if (mode == R16_9) {
            // the biggest 16:9 box that fits the container
            double byWidth = cw / SIXTEEN_NINE;
            if (byWidth <= ch) return new int[]{cw, Math.max(1, (int) Math.round(byWidth))};
            return new int[]{Math.max(1, (int) Math.round(ch * SIXTEEN_NINE)), ch};
        }

        double scaleX = cw / displayW;
        double scaleY = ch / displayH;
        double scale = (mode == FILL) ? Math.max(scaleX, scaleY) : Math.min(scaleX, scaleY);
        int w = (int) Math.round(displayW * scale);
        int h = (int) Math.round(displayH * scale);
        return new int[]{Math.max(1, w), Math.max(1, h)};
    }

    /** True when the surface covers the whole container (no black bars) — the FILL case. */
    static boolean coversScreen(int[] size, int containerW, int containerH) {
        return size != null && size[0] >= Math.max(1, containerW) - 1 && size[1] >= Math.max(1, containerH) - 1;
    }
}
