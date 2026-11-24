// AnantPlay Sinhala Player v3.4.6.a — the 5-stage Mornye spring-animated
// first-launch wizard (Welcome → Notifications → Auto-Open Overlay →
// Background/MIUI stability → Connect official AnantPlay), mirroring
// OnboardingWizard's stage model; K_ONBOARDING_DONE persists only on Finish.
// v3.4.6.a — FULL MORNYE PARITY: the first stage's elements reveal on the
// upstream StaggeredListItem spring ladder (art → title → body), the exact
// upstream entry effect.
import 'package:flutter_svg/flutter_svg.dart';
import 'package:material_ui/material_ui.dart';

import 'package:anantplay_ui/mornye_ui/widgets/animation_utils.dart';

import 'app_state.dart';
import 'bridge.dart';

class OnboardingScreen extends StatefulWidget {
  const OnboardingScreen({super.key});

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen> {
  int _stage = 0;
  static const _stages = 5;

  static const _titles = [
    'Welcome',
    'Notifications',
    'Auto-open on cast',
    'Background stability',
    'Connect AnantPlay',
  ];

  static const _bodies = [
    'The Sinhala cast receiver — zero accessibility services, zero forks of the official app. Liquid-Glass cinema, all the way through.',
    'A cast arrives as a quiet notification when the screen can\'t open it automatically. Nothing spammy — one tap, and it plays.',
    'With the overlay permission a cast opens the player by itself. Without it, you get the one-tap notification instead — either way, the stream plays.',
    'On Xiaomi/HyperOS, allow background activity so the receiver survives. One switch, set once.',
    'Cast from the official AnantPlay app — pick "සිංහල Player" in its Cast sheet.',
  ];

  void _next() {
    final scope = AppState.of(context).scope;
    if (_stage < _stages - 1) {
      setState(() => _stage++);
      // K_ONBOARDING_STAGE — resume on this step if the wizard is interrupted.
      scope.setOnboardingStage(_stage);
    } else {
      scope.completeOnboarding();
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final media = MediaQuery.of(context);
    return Scaffold(
      backgroundColor: const Color(0xFF0F2E30),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 24),
          child: ConstrainedBox(
            constraints: BoxConstraints(minHeight: media.size.height - 48),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const Spacer(),
                StaggeredListItem(
                  index: 0,
                  duration: const Duration(milliseconds: 300),
                  staggerDelay: const Duration(milliseconds: 60),
                  // Only the entry reveal staggers; every stage change keeps
                  // the AnimatedSwitcher swap (upstream behavior).
                  animate: _stage == 0,
                  child: AnimatedSwitcher(
                    duration: const Duration(milliseconds: 260),
                    child: _stageArt(_stage),
                  ),
                ),
                const SizedBox(height: 32),
                StaggeredListItem(
                  index: 1,
                  duration: const Duration(milliseconds: 300),
                  staggerDelay: const Duration(milliseconds: 60),
                  animate: _stage == 0,
                  child: Text(
                    _titles[_stage],
                    key: ValueKey('t$_stage'),
                    style: theme.textTheme.headlineSmall?.copyWith(
                      color: const Color(0xFFF2F7F7),
                      fontWeight: FontWeight.w700,
                    ),
                    textAlign: TextAlign.center,
                  ),
                ),
                const SizedBox(height: 12),
                StaggeredListItem(
                  index: 2,
                  duration: const Duration(milliseconds: 300),
                  staggerDelay: const Duration(milliseconds: 60),
                  animate: _stage == 0,
                  child: Text(
                    _bodies[_stage],
                    key: ValueKey('b$_stage'),
                    style: theme.textTheme.bodyMedium?.copyWith(
                      color: const Color(0xCCF2F7F7),
                      height: 1.45,
                    ),
                    textAlign: TextAlign.center,
                  ),
                ),
                const Spacer(),
                _stageActions(_stage, theme),
                const SizedBox(height: 28),
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    for (var i = 0; i < _stages; i++)
                      Container(
                        width: i == _stage ? 22 : 7,
                        height: 7,
                        margin: const EdgeInsets.symmetric(horizontal: 3),
                        decoration: BoxDecoration(
                          color: i <= _stage
                              ? const Color(0xFF3E8E8F)
                              : const Color(0x33F2F7F7),
                          borderRadius: BorderRadius.circular(4),
                        ),
                      ),
                  ],
                ),
                const SizedBox(height: 18),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _stageArt(int stage) {
    // the hero art per stage (pure-SVG Liquid-Glass Cinema assets)
    const art = [
      'assets/onboarding/welcome.svg',
      'assets/onboarding/notifications.svg',
      'assets/onboarding/overlay.svg',
      'assets/onboarding/stability.svg',
      'assets/onboarding/connect.svg',
    ];
    return SizedBox(
      key: ValueKey('a$stage'),
      width: 210,
      height: 210,
      child: SvgPicture.asset(art[stage], fit: BoxFit.contain),
    );
  }

  Widget _stageActions(int stage, ThemeData theme) {
    switch (stage) {
      case 1:
        return _action(
          label: 'Continue',
          onTap: _next,
          caption: 'Notifications are already requested at install — nothing to flip here.',
        );
      case 2:
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            FutureBuilder<bool>(
              future: AnantPlay.instance.overlayGranted(),
              builder: (context, snap) => _action(
                label: snap.data == true ? 'Continue' : 'Open settings',
                onTap: () {
                  if (snap.data == true) {
                    _next();
                  } else {
                    // open the system page AND advance — the re-check on
                    // return (onResume live status) reflects the grant; the
                    // wizard never traps a user on this stage.
                    AnantPlay.instance.openOverlaySettings();
                    _next();
                  }
                },
                caption: snap.data == true
                    ? 'Overlay permission granted — casts auto-open.'
                    : 'Optional: allow "Display over other apps" for auto-open.',
              ),
            ),
          ],
        );
      case 3:
        return _action(
          label: 'Continue',
          onTap: _next,
          caption: 'On Xiaomi/HyperOS, also allow background activity once.',
        );
      case 4:
        return _action(
          label: 'Finish',
          onTap: _next,
          caption: 'You can launch the official app anytime from Home.',
        );
      default:
        return _action(label: 'Get started', onTap: _next, caption: null);
    }
  }

  Widget _action({
    required String label,
    required VoidCallback onTap,
    String? caption,
  }) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        FilledButton(
          onPressed: onTap,
          style: FilledButton.styleFrom(
            backgroundColor: const Color(0xFF1E4E4F),
            foregroundColor: const Color(0xFFF2F7F7),
            padding: const EdgeInsets.symmetric(vertical: 14),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
          ),
          child: Text(label, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
        ),
        if (caption != null)
          Padding(
            padding: const EdgeInsets.only(top: 10),
            child: Text(
              caption,
              textAlign: TextAlign.center,
              style: const TextStyle(color: Color(0x99F2F7F7), fontSize: 12.5, height: 1.4),
            ),
          ),
      ],
    );
  }
}
