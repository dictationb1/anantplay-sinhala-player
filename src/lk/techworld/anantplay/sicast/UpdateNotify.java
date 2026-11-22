package lk.techworld.anantplay.sicast;

/* v3.1.0 — THE SILENT-UPDATE NOTIFICATION POLICY (Bug 5, Smoking Gun #5).
 *
 * THE FIELD FAILURE (2026-11-22 16:36, Xiaomi Android 16): update notifications
 * spammed repeatedly and visibly LAGGED the whole phone. Two causes in v3.0.0:
 *   1. SelfUpdater called App.notifyUpdateProgress() on EVERY 64 KB download
 *      chunk (SelfUpdateCore.pump → progress callback) — dozens of
 *      NotificationManager.notify() calls per ~3.5 MB APK;
 *   2. the banner lived on CHANNEL_CAST = IMPORTANCE_HIGH (heads-up + sound +
 *      badge) with no setSilent()/setOnlyAlertOnce(), so every chunk re-alerted
 *      SystemUI — the exact "phone slowed down" report.
 *
 * THE v3.1.0 RULES (this class, pure Java, lab-proven):
 *   · PROGRESS THROTTLE — at most ONE notify() per 1,000 ms, and never twice
 *     for the same percent; a 500-callback download burst produces at most
 *     ceil(duration/1 s) notifications, in practice ~4–6 for a 3–4 MB APK;
 *   · PER-VERSION 24 h ALERT COOLDOWN — a release that was already alerted
 *     (downloaded or notified) does not re-alert the user for 24 hours, so the
 *     "අප්ඩේට් කරන්න, අප්ඩේට් කරන්න…" repeat spam from the 4-hour periodic job is
 *     impossible (a manual "Check now" deliberately bypasses the cooldown);
 *   · SKIP VERSION — a user-skipped version never alerts again until a NEWER
 *     release appears.
 * The channel side (silent IMPORTANCE_LOW channel, setSilent + setOnlyAlertOnce)
 * lives in App/SelfUpdater; the decision side is ALL here. */
public final class UpdateNotify {

    /** A progress notification at most once per this many milliseconds. */
    public static final long MIN_INTERVAL_MS = 1_000;

    /** The per-version alert cooldown (24 h). */
    public static final long ALERT_COOLDOWN_MS = 24L * 60 * 60 * 1000;

    private long lastNotifyAt;
    private int lastPercent = -1;
    private int notified, skipped;

    /** True when a progress notify() should ACTUALLY be posted for this chunk
     * callback. Deterministic variant for the lab (explicit now). */
    public boolean onProgress(int percent, long nowMs) {
        if (lastNotifyAt > 0 && nowMs - lastNotifyAt < MIN_INTERVAL_MS) {
            skipped++;
            return false;                       // hard 1 s cap — the SystemUI flood breaker
        }
        if (percent == lastPercent) {
            skipped++;
            return false;                       // same percent twice — nothing new to say
        }
        lastNotifyAt = nowMs;
        lastPercent = percent;
        notified++;
        return true;
    }

    /** The wall-clock variant the app uses. */
    public boolean onProgress(int percent) { return onProgress(percent, System.currentTimeMillis()); }

    /** Statistics for the lab + /info: how many notify() calls were allowed. */
    public int notified() { return notified; }
    public int skipped() { return skipped; }
    public int lastPercent() { return lastPercent; }

    // ── the per-version cooldown + skip rules (static, pure) ───────────────────

    /** May we alert the user about `version` now? Manual checks bypass (the user
     *  asked), automatic cycles respect the 24 h cooldown and the skip choice. */
    public static boolean alertAllowed(String version, long nowMs, long lastAlertAtMs,
                                       String skippedVersion, boolean manualCheck) {
        if (manualCheck) return true;
        if (version != null && version.equals(skippedVersion)) return false;   // skipped forever
        if (lastAlertAtMs <= 0) return true;                                   // never alerted
        return nowMs - lastAlertAtMs >= ALERT_COOLDOWN_MS;
    }

    /** A skipped version stops suppressing as soon as a NEWER release appears. */
    public static boolean stillSkipped(String skippedVersion, String latestVersion) {
        if (skippedVersion == null || skippedVersion.isEmpty()) return false;
        if (latestVersion == null || latestVersion.equals(skippedVersion)) return true;
        return !UpdateChecker.isNewer(latestVersion, skippedVersion);
    }

    /** True when the percent jumped by at least the informative step since the
     *  last allowed notify (used to prefer "meaningful" updates when several
     *  percent values were suppressed inside one window). */
    public static boolean meaningfulStep(int lastAllowedPercent, int percent) {
        return Math.abs(percent - lastAllowedPercent) >= 15;
    }
}
