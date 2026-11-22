package lk.techworld.anantplay.sicast;

/* v2.4.0 — THE PURE-JAVA INSTALL POLICY of the built-in GitHub self-updater.
 *
 * Every Android 14–16 (targetSdk 34) trap of the PackageInstaller path is a DECISION,
 * not a UI detail, so each one lives here as a testable function and the lab drives it
 * on a bare JVM exactly the way ReceiverLab drives MdnsResponder/NetPolicy:
 *
 *   TRAP 1 — IMPLICIT MUTABLE PendingIntent (Android 14+ IllegalArgumentException):
 *            PackageInstaller.Session.commit() needs a MUTABLE PendingIntent because the
 *            system fills in the status extras. A mutable PendingIntent whose Intent is
 *            IMPLICIT is a hard crash on targetSdk 34. → isExplicitInstallIntent().
 *   TRAP 2 — BACKGROUND ACTIVITY LAUNCH (BAL) on STATUS_PENDING_USER_ACTION: a receiver
 *            woken by the 6 h CheckJob must not assume startActivity() works. Android 16
 *            silently drops it. → shouldPostNotification() (foreground check + the
 *            SecurityException fallback both funnel through it).
 *   TRAP 3 — canRequestPackageInstalls() false (sideloaded app, never granted):
 *            the commit dies silently. → needsUnknownSources() + unknownSourcesUri().
 *   TRAP 5 — DOWNLOAD SAFETY: HTTPS-only, bounded size, mandatory SHA-256 from
 *            SHA256SUMS.txt, and the EXACT line format release-apk.yml writes.
 *
 * No Android imports: this class is compiled into the gating lab. */
final class SelfUpdateInstall {

    /** Hard ceiling for a downloaded APK (the real artifact is ~3.3 MB). Anything
     *  bigger is not our release and is refused before it can fill the cache. */
    static final long MAX_APK_BYTES = 30L * 1024L * 1024L;

    private SelfUpdateInstall() { }

    // ── TRAP 5a: HTTPS only ────────────────────────────────────────────────────

    /** A download URL must be HTTPS and absolute. `http://` (or a redirect target
     *  served over http) is refused: the APK's integrity depends on the transport
     *  not being rewritable between us and GitHub. */
    static boolean isHttpsUrl(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase(java.util.Locale.ROOT);
        return u.startsWith("https://") && u.length() > "https://".length();
    }

    /** A loopback URL is the ONE non-HTTPS case that is not a transport risk (there is
     *  no network segment to rewrite); it exists so the gating lab can drive the real
     *  downloader against its own mock GitHub server. A public `http://` host never
     *  qualifies. */
    static boolean isLoopbackUrl(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase(java.util.Locale.ROOT);
        if (!u.startsWith("http://")) return false;
        String host = u.substring("http://".length());
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]");
    }

    /** The transport rule the downloader itself enforces. */
    static boolean isAllowedTransport(String url) {
        return isHttpsUrl(url) || isLoopbackUrl(url);
    }

    /** The URL we are about to stream, or a refusal reason (null == allowed). */
    static String downloadRefusal(String url, long contentLengthBytes) {
        if (!isAllowedTransport(url)) return "not-https";
        if (contentLengthBytes > MAX_APK_BYTES) return "too-big";
        return null;
    }

    /** True while a partially streamed APK is still inside the cap (checked per
     *  chunk, because a server may lie about Content-Length). */
    static boolean sizeStillAllowed(long bytesSoFar) {
        return bytesSoFar >= 0 && bytesSoFar <= MAX_APK_BYTES;
    }

    /** Legacy API 26–27 accept "package:" URIs only from API 26 on; this keeps the
     *  intent builder honest for every level we ship to. */
    static String unknownSourcesUri(String pkg) {
        return "package:" + (pkg == null ? "" : pkg);
    }

    // ── TRAP 3: the unknown-sources UX guard (API 26+) ─────────────────────────

    /** The OS only lets a non-PackageInstaller app open an install session when the
     *  user granted "Install unknown apps" for THIS package. Asking is pointless
     *  below API 26 (the setting does not exist). */
    static boolean needsUnknownSources(int sdkInt, boolean canRequestPackageInstalls) {
        return sdkInt >= 26 && !canRequestPackageInstalls;
    }

    // ── TRAP 1: the explicit mutable PendingIntent ─────────────────────────────

    /** The commit intent MUST name our own receiver explicitly (component, or a
     *  package + action pair). An implicit mutable PendingIntent throws
     *  IllegalArgumentException on Android 14+ and is a security hole at any level. */
    static boolean isExplicitInstallIntent(boolean hasComponent, boolean hasPackage) {
        return hasComponent || hasPackage;
    }

    /** The same rule expressed as a single predicate over an Intent-shaped input,
     *  so a regression in SelfUpdater.install() is caught by the lab. */
    static boolean isCommitPendingIntentSafe(boolean explicit, boolean mutable) {
        return explicit && mutable;
    }

    // ── TRAP 2: BAL — the tap-to-install notification fallback ─────────────────

    /** When the confirm dialog can be launched directly we do (best UX). Otherwise
     *  — app not in the foreground, or startActivity() threw — the ONLY correct
     *  action on Android 14–16 is a high-priority notification whose tap carries the
     *  same confirm intent (a notification tap is a user gesture, so BAL applies). */
    static boolean shouldPostNotification(boolean appForeground, boolean startThrew) {
        return !appForeground || startThrew;
    }

    // ── TRAP 5b: SHA256SUMS.txt parity with release-apk.yml ────────────────────

    /** EXACTLY the line `sha256sum "<file>"` writes in the release workflow:
     *  "<64-hex-lowercase>  <file name>" (two spaces, no path, no '*'). The lab
     *  asserts SelfUpdateCore.pickShaLine() parses THIS line — the cross-check that
     *  keeps the CI producer and the app-side parser from ever drifting apart. */
    static String sumsLine(String sha256HexLowercase, String fileName) {
        return sha256HexLowercase + "  " + fileName;
    }

    /** The producer/consumer parity assertion, in one call. */
    static boolean sumsLineParsesBack(String sha256HexLowercase, String fileName) {
        String body = sumsLine(sha256HexLowercase, fileName) + "\n";
        String parsed = SelfUpdateCore.pickShaLine(body, fileName);
        return parsed != null && parsed.equalsIgnoreCase(sha256HexLowercase);
    }

    /** A sums body is only usable when it yields a 64-hex hash for OUR asset. */
    static boolean hasVerifiableHash(String expectedSha) {
        return expectedSha != null && expectedSha.toLowerCase(java.util.Locale.ROOT)
            .matches("[0-9a-f]{64}");
    }

    /** The cached artifact name (cache dir) — one fixed slot, so a half-finished
     *  download can never masquerade as a different version. */
    static String cacheFileName() {
        return "selfupdate.apk";
    }
}
