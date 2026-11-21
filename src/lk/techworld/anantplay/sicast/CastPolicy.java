package lk.techworld.anantplay.sicast;

import java.util.Locale;

/* The cast-arrival POLICY, kept in pure Java so the verification lab enforces the exact
 * same rules the Android service applies (no drift between "tested" and "shipped").
 *
 * Rules (byte-compatible with official AnantPlay's Cast.play, Cast.java:144-145):
 *   · http:// and https:// are always allowed — that is what AnantPlay casts.
 *   · file:// is allowed ONLY for its downloaded-video casts, and only when we actually
 *     hold the media read permission (otherwise the service answers "blocked" and posts
 *     a one-tap permission notification instead of crashing).
 *   · everything else — content://, javascript:, data:, intents, ftp:// — is refused
 *     with the same "bad link" wording AnantPlay shows.
 *
 * /do actions are whitelisted here too: an unknown action never reaches the player. */
final class CastPolicy {

    /** AnantPlay's own rejection wording (Cast.play → {\"ok\":false,\"why\":…}). */
    static final String BAD_LINK = "bad link";
    /** Our wording for "the link is fine but we may not read it yet". */
    static final String BLOCKED = "blocked";

    /** A seek target beyond 24 h is never a real position — clamp it (NaN-free long). */
    static final long MAX_SEEK_MS = 24L * 60 * 60 * 1000;

    private CastPolicy() { }

    /** True for the schemes a cast may use (AnantPlay's rule + our file:// case). */
    static boolean allowedScheme(String url) {
        String u = trim(url);
        if (u.isEmpty()) return false;
        // URI schemes are case-insensitive: "HTTPS://…" is a legal cast.
        String lower = u.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("file://");
    }

    /** True when the cast is a local file (downloaded video) rather than a network stream. */
    static boolean isFile(String url) {
        return trim(url).toLowerCase(Locale.ROOT).startsWith("file://");
    }

    /** The full gate: scheme allowed AND (not a file OR the media permission is held). */
    static boolean allowed(String url, boolean canReadSharedMedia) {
        if (!allowedScheme(url)) return false;
        return !isFile(url) || canReadSharedMedia;
    }

    /** The exact JSON "why" the receiver answers with when allowed() is false. */
    static String rejectWhy(String url) {
        return allowedScheme(url) ? BLOCKED : BAD_LINK;
    }

    /** /do whitelist — exactly the actions official AnantPlay's Remote sends. */
    static boolean isAllowedAction(String action) {
        if (action == null) return false;
        return "toggle".equals(action) || "seek".equals(action) || "stop".equals(action)
            || "vol+".equals(action) || "vol-".equals(action);
    }

    /** Clamp a seek target into [0, MAX_SEEK_MS] (a negative or absurd ms breaks players). */
    static long clampSeek(long to) {
        if (to < 0) return 0;
        return to > MAX_SEEK_MS ? MAX_SEEK_MS : to;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
