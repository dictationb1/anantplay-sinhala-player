package lk.techworld.anantplay.sicast;

import java.util.Locale;

/* Pure version comparison helpers retained for skip-version policy.
 * Any release lookup delegates to SelfUpdateCore's two-route resolver so
 * update checks never use the GitHub REST API. */
public final class UpdateChecker {

    public static final String REPO_URL = "https://github.com/dictationb1/anantplay-sinhala-player";
    public static final String REPO_NAME = "dictationb1/anantplay-sinhala-player";

    private UpdateChecker() { }

    /** The outcome, rendered bilingually by the caller. */
    static final class Result {
        boolean newer;            // the release is newer than the running build
        String latest = "";       // e.g. "2.3.0"
        String tagName = "";      // e.g. "v2.3.0"
        String apkUrl = "";       // the .asset download URL ("" when absent)
        String releaseUrl = "";   // the release's html_url
        String notes = "";        // first 300 chars of the release body
        boolean offline;          // the query failed (no network / rate limit)
        String error = "";
    }

    /** Blocking check — call from a background thread. Uses only the 2-tier resolver. */
    static Result check() {
        Result r = new Result();
        try {
            SelfUpdateCore.Release release = SelfUpdateCore.fetchReleaseAny();
            if (release == null) {
                r.offline = true;
                r.error = "no release metadata from either HTTPS source";
                return r;
            }
            r.latest = release.version;
            r.tagName = "v" + release.version;
            r.apkUrl = release.apkUrl;
            r.releaseUrl = REPO_URL + "/releases/tag/" + r.tagName;
            r.newer = isNewer(r.latest, AppInfo.VERSION_NAME);
            return r;
        } catch (Exception e) {
            r.offline = true;
            r.error = String.valueOf(e.getMessage());
            return r;
        }
    }

    /** "v2.3.0" → "2.3.0" (tolerant of a missing v, spaces, junk). */
    static String stripV(String tag) {
        if (tag == null) return "";
        String t = tag.trim();
        if (t.toLowerCase(Locale.ROOT).startsWith("v")) t = t.substring(1);
        return t.trim();
    }

    /** Clean semver compare: true when candidate > current. Tolerant of
     *  missing parts (2.3 == 2.3.0) and non-numeric junk (falls back to
     *  unequal). v3.4.6.a — compares ALL FOUR dimensions, so the alphabetical
     *  emergency lane orders correctly: 3.4.6 < 3.4.6.a < 3.4.6.b < … < 3.4.6.z
     *  < 3.4.7 (the patch digit outranks the letter). v3.4.6.d — the lane is
     *  the WHOLE ALPHABET (a→1 … z→26), never an a–e ceiling. */
    static boolean isNewer(String candidate, String current) {
        int[] a = parse(candidate);
        int[] b = parse(current);
        for (int i = 0; i < 4; i++) {
            if (a[i] != b[i]) return a[i] > b[i];
        }
        return false;
    }

    /** v3.4.6.a — THE ALPHABETICAL 4TH SEGMENT, parsed natively.
     * "3.4.6.a" → {3,4,6,1}; "3.4.6.b" → {3,4,6,2}; "3.4.6.z" → {3,4,6,26};
     * "3.4.7" → {3,4,7,0}.
     * The letter is its own compare dimension — NEVER stripped, NEVER fed to
     * Integer.parseInt (that is the NumberFormatException trap), and it orders
     * the emergency lane AFTER the bare base: 3.4.6 < 3.4.6.a < … < 3.4.6.z,
     * and below the next numeric: 3.4.6.z < 3.4.7 because the letter vs the
     * patch digit resolves at the PATCH digit first (position 2), which parse()
     * encodes by construction: 3.4.6.z → {3,4,6,26} vs 3.4.7 → {3,4,7,0}.
     *
     * v3.4.6.d — THE PERMANENT LAW: the lane is a→z (1…26), NOT a→e. A ceiling
     * at 'e' would silently order 3.4.6.f as "no letter" (== 3.4.6) and the
     * update would never fire again. The alphabet is the only bound. */
    /** v3.4.6.k — LAW #1 TWO-LANE VERSIONING: true when candidate is a
     *  letter-suffix PATCH of the SAME major.minor.patch base as current
     *  (3.4.6.k → 3.4.6.l → 3.4.6.m): the SILENT zero-prompt lane (download
     *  once + SHA-256 verify + USER_ACTION_NOT_REQUIRED commit, a glass
     *  pill "Updating to vX…" only). Any numeric bump (3.4.7 / 3.5.0) is the
     *  major/minor lane → the full-screen non-dismissible ForceUpdateScreen. */
    static boolean isLetterPatch(String candidate, String current) {
        if (candidate == null || current == null) return false;
        int[] a = parse(candidate);
        int[] b = parse(current);
        boolean sameBase = a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
        boolean hasLetter = a[3] > 0 || b[3] > 0;
        return sameBase && hasLetter;
    }

    static int[] parse(String v) {
        int[] out = {0, 0, 0, 0};
        if (v == null) return out;
        String[] parts = v.split("[.\\-+]");
        for (int i = 0; i < 4 && i < parts.length; i++) {
            String p = parts[i];
            if (i == 3) {                      // the letter segment: a..z → 1..26
                if (p.length() == 1 && p.charAt(0) >= 'a' && p.charAt(0) <= 'z') {
                    out[3] = p.charAt(0) - 'a' + 1;
                } else if (p.length() == 1 && p.charAt(0) >= 'A' && p.charAt(0) <= 'Z') {
                    out[3] = p.charAt(0) - 'A' + 1;   // tolerant of an upper-case tag
                }
                continue;                     // any junk tail is inert, never an exception
            }
            p = p.replaceAll("\\D", "");
            if (p.isEmpty()) continue;
            try { out[i] = Integer.parseInt(p); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

}
