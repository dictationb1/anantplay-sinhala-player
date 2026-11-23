package lk.techworld.anantplay.sicast;

/**
 * v3.4.3 — THE ONBOARDING CONTRACT, pure Java (no Android types).
 *
 * <p>This is the arithmetic of the 5-stage first-launch wizard: which stage comes next,
 * what each stage is called, what its status line may honestly say, and when the Home
 * screen shows a repair banner instead of re-running the wizard. {@link OnboardingWizard}
 * owns the views and delegates every decision here, which is what lets
 * {@code bash lab/run_lab.sh} prove the contract without an emulator.
 *
 * <p>WHY THE SPLIT EXISTS AT ALL (Replit QA-0001 … QA-0004): v3.4.2 had no onboarding, so
 * a first-time user met a cold permission dialog in {@code onCreate()} and a Home screen
 * full of protocol acronyms. Every rule the audit asked for is a rule in this file.
 */
final class OnboardingSpec {

    /** The 5 stages, in the audited order. */
    static final int STAGE_WELCOME = 0;
    static final int STAGE_NOTIFICATIONS = 1;
    static final int STAGE_OVERLAY = 2;
    static final int STAGE_BACKGROUND = 3;
    static final int STAGE_CONNECT = 4;
    static final int STAGES = 5;

    /** The official AnantPlay package — the only source of movies (never a fork). */
    static final String ANANTPLAY_PKG = "me.champdev.weblock";

    /** OEM autostart state, mirroring {@code Miui.AutostartState} without the enum. */
    static final int AUTOSTART_ENABLED = 0;
    static final int AUTOSTART_DISABLED = 1;
    static final int AUTOSTART_UNKNOWN = 2;

    private OnboardingSpec() { }

    /** Clamp a stored stage into [0, STAGES-1] — a corrupt pref can never crash. */
    static int clampStage(int stage) {
        if (stage < 0) return STAGE_WELCOME;
        return stage > STAGES - 1 ? STAGES - 1 : stage;
    }

    static int nextStage(int stage) { return clampStage(clampStage(stage) + 1); }

    static int prevStage(int stage) { return clampStage(clampStage(stage) - 1); }

    /** True when the wizard should open by itself (first launch, never completed). */
    static boolean shouldAutoOpen(boolean onboardingDone) { return !onboardingDone; }

    /**
     * True when Home shows the calm REPAIR banner: onboarding is complete but a grant it
     * set up has since been revoked. Never a forced wizard loop (QA-0004).
     */
    static boolean needsRepair(boolean onboardingDone, boolean overlayGranted,
                               boolean notifGranted, boolean batteryUnrestricted) {
        if (!onboardingDone) return false;
        return !overlayGranted || !notifGranted || !batteryUnrestricted;
    }

    /** The one-line repair banner (plain words, no protocol jargon). */
    static String repairBannerText(boolean overlayGranted, boolean notifGranted,
                                   boolean batteryUnrestricted) {
        StringBuilder sb = new StringBuilder("Setup needs attention — ");
        boolean first = true;
        if (!overlayGranted) { sb.append("auto-open on cast is off"); first = false; }
        if (!notifGranted) {
            if (!first) sb.append(", ");
            sb.append("notifications are off");
            first = false;
        }
        if (!batteryUnrestricted) {
            if (!first) sb.append(", ");
            sb.append("battery optimization is on");
        }
        sb.append(" · tap to fix");
        return sb.toString();
    }

    /** Stage 2 — the honest notification status line. */
    static String notifStatus(int sdkInt, boolean granted) {
        if (sdkInt < 33) return "Not required ✓";
        return granted ? "Granted ✓" : "Not enabled";
    }

    /** Stage 3 — the honest overlay status line (re-queried, never assumed). */
    static String overlayStatus(boolean granted) { return granted ? "Granted ✓" : "Not enabled"; }

    /**
     * Stage 4 — the TRUTHFUL background-stability line: Verified / User confirmed /
     * Unknown. It never claims an OS grant the app cannot read (HyperOS).
     */
    static String backgroundStatus(boolean xiaomi, int autostart, boolean userConfirmed,
                                   boolean batteryUnrestricted) {
        if (!batteryUnrestricted) return "Battery optimization is ON — receiver may sleep";
        if (!xiaomi) return "Verified — no OEM autostart gate on this device";
        if (autostart == AUTOSTART_ENABLED) return "Verified — autostart allowed by the OS";
        if (userConfirmed) return "User confirmed — the OS read is unavailable on HyperOS";
        return "Unknown — the OS does not expose autostart on HyperOS";
    }

    static String stageTitle(int stage) {
        switch (clampStage(stage)) {
            case STAGE_WELCOME: return "Welcome";
            case STAGE_NOTIFICATIONS: return "Notifications";
            case STAGE_OVERLAY: return "Auto-Open on Cast";
            case STAGE_BACKGROUND: return "Background Stability";
            default: return "Connect AnantPlay";
        }
    }

    static String stageAction(int stage) {
        switch (clampStage(stage)) {
            case STAGE_WELCOME: return "Start setup";
            case STAGE_CONNECT: return "Finish setup";
            default: return "Continue";
        }
    }
}
