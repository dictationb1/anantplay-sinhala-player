package lk.techworld.anantplay.sicast;

import java.util.Locale;

/* Media3 mime-type hinting, in pure Java (no Android/media3 imports) so the lab can
 * prove the defaulting rules the player uses.
 *
 * Why this exists: MediaItem.fromUri() with a null mime lets DefaultMediaSourceFactory
 * infer from the *file extension*. Real casts are frequently extensionless
 * ("https://cdn/hls/abc123?token=…"), and a wrong guess means "the video never starts".
 * Official AnantPlay defaults every non-FLV cast to HLS (PlayerActivity.java:1107-1110);
 * we keep that behaviour as the last resort but resolve the obvious cases first
 * (mp4/mkv/webm/flv/ts/mpd) so a progressive MP4 cast is not fed to the HLS parser. */
final class StreamHint {

    static final String HLS = "application/x-mpegURL";        // MimeTypes.APPLICATION_M3U8
    static final String DASH = "application/dash+xml";        // MimeTypes.APPLICATION_MPD
    static final String MP4 = "video/mp4";                    // MimeTypes.VIDEO_MP4
    static final String WEBM = "video/webm";                  // MimeTypes.VIDEO_WEBM
    static final String MKV = "video/x-matroska";             // MimeTypes.VIDEO_MATROSKA
    static final String FLV = "video/x-flv";                  // MimeTypes.VIDEO_FLV
    static final String TS = "video/mp2t";                    // MimeTypes.VIDEO_MP2T

    private StreamHint() { }

    /** The URL's path, lower-cased, without query/fragment ("" when unparsable). */
    static String path(String url) {
        String u = url == null ? "" : url.trim();
        int cut = u.length();
        int q = u.indexOf('?');
        if (q >= 0 && q < cut) cut = q;
        int h = u.indexOf('#');
        if (h >= 0 && h < cut) cut = h;
        String p = u.substring(0, cut);
        int scheme = p.indexOf("://");
        if (scheme >= 0) {
            int slash = p.indexOf('/', scheme + 3);
            p = slash < 0 ? "" : p.substring(slash);
        }
        return p.toLowerCase(Locale.ROOT);
    }

    /**
     * Resolve the Media3 mime to set on the MediaItem.
     *
     * @param url      the cast stream URL
     * @param explicit the mime AnantPlay put in the cast payload (may be null/blank)
     * @param dash     AnantPlay's "dash" flag from the same payload
     * @return the mime string, or null only when the caller should let Media3 infer
     */
    static String mime(String url, String explicit, boolean dash) {
        if (explicit != null && !explicit.trim().isEmpty()) return explicit.trim();
        if (dash) return DASH;
        String p = path(url);
        if (p.endsWith(".mpd")) return DASH;
        if (p.endsWith(".m3u8") || p.endsWith(".m3u")) return HLS;
        if (p.endsWith(".mp4") || p.endsWith(".m4v")) return MP4;
        if (p.endsWith(".webm")) return WEBM;
        if (p.endsWith(".mkv")) return MKV;
        if (p.endsWith(".flv")) return FLV;
        if (p.endsWith(".ts")) return TS;
        // Extensionless streams: look at the PATH only (a random token in the query must
        // never decide the container), then fall back to AnantPlay's own HLS default.
        if (p.contains("dash") || p.contains("mpd")) return DASH;
        return HLS;   // AnantPlay's own last-resort default (PlayerActivity.java:1109)
    }
}
