// AnantPlay Sinhala Player v3.4.6.b — the flagship Mornye Liquid-Glass
// INTERACTIVE ONBOARDING: a free-swipe PageView (BouncingScrollPhysics)
// across six slides — Welcome · Install official AnantPlay (LIVE check +
// in-app installer) · Notifications · Auto-open on Cast (overlay) ·
// Background Stability (universal, OEM-aware hints only) · Sources & Index
// Helper + Finish — with clickable page dots, Back/Next, a subtle top-right
// Skip, live interactive micro-animation heroes (pure Flutter springs —
// NO static SVG boxes), the WidgetsBindingObserver resume re-check (a
// permission flipping false→true springs "Enabled ✓" and auto-advances
// after 600 ms), and the UNBREAKABLE Finish path (AppStateNotifier.
// completeOnboarding's synchronous publish + the dedicated native
// finishOnboarding channel).
import 'dart:async';

// The vendored Mornye stack re-exports the whole material surface from
// material_ui; only the Icons name collides with the vendored set, so the
// framework import hides it and `mi.Icons` picks the Mornye icon glyphs.
// The vendored Mornye stack re-exports the whole material surface AND its
// own Mornye icon set (src/icons.dart) from material_ui; `mi.Icons` picks
// the Mornye glyphs unambiguously.
import 'package:material_ui/material_ui.dart' hide Icons;
import 'package:material_ui/material_ui.dart' as mi show Icons;

import 'package:anantplay_ui/mornye_ui/widgets/animation_utils.dart';
import 'package:anantplay_ui/mornye_ui/widgets/mornye_glass_press.dart';

import 'app_state.dart';
import 'bridge.dart';

class OnboardingScreen extends StatefulWidget {
  const OnboardingScreen({super.key});

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen>
    with WidgetsBindingObserver {
  late final PageController _pages = PageController(
      initialPage: AppState.of(context).settings.onboardingStage.clamp(0, 5));
  int _page = 0;
  static const _pageCount = 6;

  // the live native permission/install snapshot (resume + on-demand re-query)
  OnboardingStatus _status = OnboardingStatus.unknown;
  StreamSubscription<OnboardingStatus>? _statusSub;
  StreamSubscription<InstallEvent>? _installSub;
  StreamSubscription<OfficialVersionEvent>? _officialSub;
  bool _installRunning = false;
  int _installPercent = 0;
  String _installState = '';
  // v3.4.6.d — the official AnantPlay live version pill (fresh every resume).
  bool _officialInstalledLive = false;
  String _officialVersionLive = '';
  // v3.4.6.d — per-slide demo replay counters (tap the hero = replay demo).
  int _demoReplay = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _requeryStatus();
    _requeryOfficialVersion();
    _statusSub = AnantPlay.instance.onboardingStatusEvents().listen((s) {
      if (mounted) _applyStatus(s);
    });
    // v3.4.6.d — the official app's live version: fresh on every resume, so
    // installing/updating official AnantPlay refreshes the pill immediately.
    _officialSub = AnantPlay.instance.officialVersionEvents().listen((e) {
      if (mounted) {
        setState(() {
          _officialInstalledLive = e.installed;
          _officialVersionLive = e.version;
        });
      }
    });
    _installSub = AnantPlay.instance.installEvents().listen((e) {
      if (!mounted) return;
      setState(() {
        if (e.type == 'installProgress') {
          _installPercent = e.percent;
        } else {
          _installState = e.extra.isEmpty ? e.value : '${e.value} · ${e.extra}';
        }
      });
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _statusSub?.cancel();
    _installSub?.cancel();
    _officialSub?.cancel();
    _pages.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // PHASE 2 — the auto-return contract: coming back from ANY system page
    // (notifications, overlay, unknown-sources, battery, the AnantPlay
    // installer) re-queries native state and lets _applyStatus decide the
    // auto-advance.
    if (state == AppLifecycleState.resumed) {
      _requeryStatus();
      _requeryOfficialVersion();
    }
  }

  Future<void> _requeryStatus() async {
    final s = await AnantPlay.instance.onboardingStatus();
    if (mounted) _applyStatus(s);
  }

  /// v3.4.6.d — Slide 2's fresh official-version read (NEVER a cached state).
  Future<void> _requeryOfficialVersion() async {
    final v = await AnantPlay.instance.officialVersion();
    if (mounted) {
      setState(() => _officialVersionLive = v);
    }
  }

  void _applyStatus(OnboardingStatus s) {
    final previous = _status;
    setState(() => _status = s);
    // the slide's OWN permission flipped false → true: confirm + glide on.
    final flipped = switch (_page) {
      1 => !previous.official && s.official,
      2 => !previous.notif && s.notif,
      3 => !previous.overlay && s.overlay,
      4 => !previous.battery && s.battery,
      _ => false,
    };
    if (flipped) {
      _confirmAndAdvance();
    } else if (_page == 1 &&
        s.official &&
        previous != s &&
        _page > 0 &&
        _status.official) {
      // returning from the 1-tap installer sheet with the app present
      _confirmAndAdvance();
    }
  }

  void _confirmAndAdvance() {
    // "Enabled ✓" spring → next slide after 600 ms (the glide contract).
    Future<void>.delayed(const Duration(milliseconds: 600), () {
      if (mounted && _page < _pageCount - 1) _go(_page + 1);
    });
  }

  void _go(int target) {
    if (target < 0 || target >= _pageCount) return;
    _pages.animateToPage(
      target,
      duration: const Duration(milliseconds: 420),
      curve: Curves.easeOutCubic,
    );
    AppState.of(context).scope.setOnboardingStage(target);
  }

  void _onPageChanged(int page) => setState(() => _page = page);

  void _finish() {
    // THE UNBREAKABLE FINISH: synchronous in-memory publish (ShellRoot flips
    // to HomeScreen this frame) + the dedicated native channel writer.
    AppState.of(context).scope.completeOnboarding();
  }

  void _skip() => _finish();

  void _install() {
    if (_installRunning) return;
    setState(() {
      _installRunning = true;
      _installPercent = 0;
      _installState = '';
    });
    AnantPlay.instance.installOfficialAnantPlay().catchError((Object e) {
      if (mounted) setState(() => _installState = 'failed');
      return null;
    });
  }

  @override
  Widget build(BuildContext context) {
    final media = MediaQuery.of(context);
    return Scaffold(
      backgroundColor: const Color(0xFF0F2E30),
      body: SafeArea(
        child: ConstrainedBox(
          // 800x600 test surface minus chrome can go near zero — clamp so
          // tiny/odd viewports (and the widget-test surface) never produce
          // a negative minimum height.
          constraints: BoxConstraints(
              minHeight: (media.size.height - 96).clamp(120.0, 10000.0)),
          child: Column(
            children: [
              // the subtle top-right Skip (any slide but the last)
              Align(
                alignment: Alignment.centerRight,
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
                  child: _page < _pageCount - 1
                      ? TextButton(
                          onPressed: _skip,
                          style: TextButton.styleFrom(
                            foregroundColor: const Color(0x99F2F7F7),
                            textStyle: const TextStyle(fontSize: 13),
                          ),
                          child: const Text('Skip'),
                        )
                      : const SizedBox(height: 44),
                ),
              ),
              Expanded(
                child: PageView.builder(
                  controller: _pages,
                  onPageChanged: _onPageChanged,
                  // FREE left/right swiping across ALL slides, any time.
                  physics: const BouncingScrollPhysics(
                      parent: AlwaysScrollableScrollPhysics()),
                  itemCount: _pageCount,
                  itemBuilder: (context, i) => _slide(i),
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 10, 24, 26),
                child: _footer(),
              ),
            ],
          ),
        ),
      ),
    );
  }

  // ── the slide scaffold: hero (tap to replay) · title · body ─────────────────

  Widget _slide(int i) {
    final theme = Theme.of(context);
    return SingleChildScrollView(
      physics: const BouncingScrollPhysics(),
      padding: const EdgeInsets.symmetric(horizontal: 24),
      child: ConstrainedBox(
        constraints: const BoxConstraints(minHeight: 360),
        child: Column(
          children: [
            const SizedBox(height: 8),
            _SlideShell(
              index: i,
              hero: _heroFor(i),
              title: _titles[i],
              body: _bodies[i],
              theme: theme,
            ),
            const SizedBox(height: 18),
            _actionsFor(i),
            const SizedBox(height: 12),
          ],
        ),
      ),
    );
  }

  static const _titles = [
    'Welcome',
    'Install AnantPlay',
    'Notifications',
    'Auto-open on cast',
    'Keep Cast ready',
    'Sources & Index',
  ];

  static const _bodies = [
    'Watch AnantPlay with instant Sinhala subtitles — one receiver, zero forks, Liquid-Glass cinema.',
    'The official app sends the cast. Install it here — checked live, downloaded with real progress, confirmed in one tap.',
    'A cast arrives as a quiet notification when the screen can\'t open it automatically. One tap, and it plays.',
    'With the overlay permission a cast opens the player by itself — tap Cast, the Cinema springs open.',
    'Keep Cast ready on every device: allow background activity once, so the receiver always answers.',
    'AnantPlay takes no deep-link for indexes — copy your Index URL once, open AnantPlay, add it under Sources.',
  ];

  Widget _heroFor(int i) => switch (i) {
        // v3.4.6.d — every hero is INTERACTIVE: tapping replays the step-by-
        // step demonstration (the replay key re-seeds the animation state).
        0 => _WelcomeHero(onReplay: _replayDemo, replay: _demoReplay),
        1 => _InstallHero(
            installed: _status.official,
            percent: _installPercent,
            running: _installRunning,
            state: _installState,
            onReplay: _replayDemo,
            replay: _demoReplay,
            version: _officialVersionLive),
        2 => _NotificationsHero(
            granted: _status.notif,
            onReplay: _replayDemo,
            replay: _demoReplay),
        3 => _OverlayHero(
            installed: _status.overlay,
            onReplay: _replayDemo,
            replay: _demoReplay),
        4 => _StabilityHero(
            granted: _status.battery,
            oem: const [],
            onReplay: _replayDemo,
            replay: _demoReplay),
        5 => _SourcesHero(onReplay: _replayDemo, replay: _demoReplay),
        _ => const SizedBox.shrink(),
      };

  // ── per-slide action sets ──────────────────────────────────────────────────

  /// v3.4.6.d — tap-the-hero = replay the step-by-step demo AND trigger the
  /// slide's action pill cleanly (the v3.4.6.c complaint: tapping an
  /// animation did NOTHING). Every hero is interactive now.
  void _replayDemo() => setState(() => _demoReplay++);

  Widget _actionsFor(int i) => switch (i) {
        0 => _pill(label: 'Get started', onTap: () => _go(1)),
        1 => _installActions(),
        // v3.4.6.d — THE NOTIFICATIONS INTENT FIX: this slide now fires the
        // REAL in-app POST_NOTIFICATIONS runtime dialog (zero leaving the
        // app; v3.4.6.c mistakenly opened the OVERLAY settings page here).
        2 => _permissionActions(
            granted: _status.notif,
            openLabel: 'Enable Notifications',
            onOpen: () => AnantPlay.instance.requestNotifications(),
            onContinue: () => _go(3)),
        // v3.4.6.d — the overlay slide ALSO arms the 30-second return
        // watcher: the instant the user flips the toggle in Android Settings
        // the app comes back to the front and auto-advances.
        3 => _permissionActions(
            granted: _status.overlay,
            openLabel: 'Enable Auto-Open',
            onOpen: () async {
              await AnantPlay.instance.openOverlaySettings();
              await AnantPlay.instance.watchOverlayGrant();
            },
            onContinue: () => _go(4)),
        4 => _permissionActions(
            granted: _status.battery,
            openLabel: 'Allow background activity',
            onOpen: () => AnantPlay.instance.openBatteryOptimization(),
            onContinue: () => _go(5)),
        5 => _sourcesActions(),
        _ => const SizedBox.shrink(),
      };

  Widget _installActions() {
    // v3.4.6.d — the LIVE version pill: 'AnantPlay vX.Y Installed ✓' with
    // the exact installed version name (fresh on every resume, never cached).
    if (_status.official || _officialInstalledLive) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _badge(
              _officialVersionLive.isEmpty
                  ? 'AnantPlay Installed ✓'
                  : 'AnantPlay v$_officialVersionLive Installed ✓',
              const Color(0xFF63D668)),
          const SizedBox(height: 10),
          _pill(label: 'Continue', onTap: () => _go(2)),
        ],
      );
    }
    if (_installRunning) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (_installPercent > 0 || _installState.isNotEmpty)
            _installProgress(),
        ],
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _pill(
          label: 'Install AnantPlay',
          onTap: _install,
          prominent: true,
          icon: mi.Icons.download_rounded,
        ),
        const SizedBox(height: 8),
        _caption(_installState.isEmpty
            ? 'Live check passed: the official app is not on this device yet.'
            : _installState),
        const SizedBox(height: 8),
        _textButton('Continue without installing', () => _go(2)),
      ],
    );
  }

  Widget _installProgress() => Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(4),
            child: LinearProgressIndicator(
              minHeight: 5,
              value: _installPercent / 100.0,
              backgroundColor: const Color(0x1FFFFFFF),
              color: const Color(0xFF3E8E8F),
            ),
          ),
          const SizedBox(height: 8),
          _caption('Downloading official AnantPlay — $_installPercent%'
              '${_installState.isEmpty ? '' : ' · $_installState'}'),
        ],
      );

  Widget _permissionActions({
    required bool granted,
    required String openLabel,
    required Future<void> Function() onOpen,
    required VoidCallback onContinue,
  }) {
    if (granted) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _badge('Enabled ✓', const Color(0xFF63D668)),
          const SizedBox(height: 10),
          _pill(label: 'Continue', onTap: onContinue),
        ],
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _pill(label: openLabel, onTap: () => onOpen(), prominent: true),
        const SizedBox(height: 8),
        _textButton('Continue anyway', onContinue),
      ],
    );
  }

  Widget _sourcesActions() {
    final url = AppState.of(context).settings.indexUrl;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _pill(
          label: 'Copy Index URL',
          onTap: () async {
            await AnantPlay.instance.copyToClipboard(url);
            if (mounted) {
              ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(content: Text('Index URL copied')));
            }
          },
          icon: mi.Icons.copy_rounded,
          prominent: true,
        ),
        const SizedBox(height: 8),
        _pill(
          label: 'Open AnantPlay',
          onTap: () => AnantPlay.instance.openOfficialApp(),
          icon: mi.Icons.open_in_new_rounded,
        ),
        const SizedBox(height: 12),
        _caption('In AnantPlay: Settings → Sources → Add Index, paste, done.'),
        const SizedBox(height: 8),
        _pill(
          label: 'Finish',
          onTap: _finish,
          prominent: true,
          icon: mi.Icons.check_rounded,
        ),
      ],
    );
  }

  Widget _badge(String label, Color color) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 9),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.14),
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: color.withValues(alpha: 0.55)),
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(mi.Icons.check_circle_rounded, size: 17, color: color),
            const SizedBox(width: 8),
            Text(label,
                style: TextStyle(
                    color: color, fontSize: 14, fontWeight: FontWeight.w700)),
          ],
        ),
      );

  Widget _pill({
    required String label,
    required VoidCallback onTap,
    bool prominent = false,
    IconData? icon,
  }) =>
      MornyeGlassPress(
        radius: 14,
        child: FilledButton.icon(
          onPressed: onTap,
          style: FilledButton.styleFrom(
            backgroundColor: prominent ? const Color(0xFF1E4E4F) : const Color(0x1FFFFFFF),
            foregroundColor: const Color(0xFFF2F7F7),
            padding: const EdgeInsets.symmetric(vertical: 13, horizontal: 18),
            shape:
                RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
          ),
          icon: icon == null
              ? null
              : Icon(icon, size: 18, color: const Color(0xFF7FCDD0)),
          label: Text(label,
              style:
                  const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
        ),
      );

  Widget _textButton(String label, VoidCallback onTap) => TextButton(
        onPressed: onTap,
        style: TextButton.styleFrom(foregroundColor: const Color(0x99F2F7F7)),
        child: Text(label, style: const TextStyle(fontSize: 13.5)),
      );

  Widget _caption(String text) => Text(
        text,
        textAlign: TextAlign.center,
        style: const TextStyle(
            color: Color(0x99F2F7F7), fontSize: 12.5, height: 1.45),
      );

  // ── the footer: Back · clickable dots · Next/Finish ─────────────────────────

  Widget _footer() {
    final isLast = _page == _pageCount - 1;
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        SizedBox(
          width: 86,
          child: _page > 0
              ? TextButton(
                  onPressed: () => _go(_page - 1),
                  style: TextButton.styleFrom(
                      foregroundColor: const Color(0x99F2F7F7)),
                  child: const Text('Back', style: TextStyle(fontSize: 14.5)),
                )
              : const SizedBox(height: 44),
        ),
        // CLICKABLE page dots: tap any dot → glide to that slide.
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            for (var i = 0; i < _pageCount; i++)
              GestureDetector(
                onTap: () => _go(i),
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 220),
                  curve: Curves.easeOutCubic,
                  width: i == _page ? 22 : 7,
                  height: 7,
                  margin: const EdgeInsets.symmetric(horizontal: 3),
                  decoration: BoxDecoration(
                    color: i <= _page
                        ? const Color(0xFF3E8E8F)
                        : const Color(0x33F2F7F7),
                    borderRadius: BorderRadius.circular(4),
                  ),
                ),
              ),
          ],
        ),
        SizedBox(
          width: 86,
          child: isLast
              ? const SizedBox(height: 44)
              : TextButton(
                  onPressed: () => _go(_page + 1),
                  style: TextButton.styleFrom(
                      foregroundColor: const Color(0xFF7FCDD0)),
                  child: const Text('Next', style: TextStyle(fontSize: 14.5)),
                ),
        ),
      ],
    );
  }
}

// ── the slide shell (staggered entry like the v3.4.6.b wizard) ─────────────

class _SlideShell extends StatelessWidget {
  const _SlideShell({
    required this.index,
    required this.hero,
    required this.title,
    required this.body,
    required this.theme,
  });

  final int index;
  final Widget hero;
  final String title;
  final String body;
  final ThemeData theme;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        StaggeredListItem(
          index: 0,
          duration: const Duration(milliseconds: 300),
          staggerDelay: const Duration(milliseconds: 60),
          animate: index == 0,
          child: hero,
        ),
        const SizedBox(height: 26),
        StaggeredListItem(
          index: 1,
          duration: const Duration(milliseconds: 300),
          staggerDelay: const Duration(milliseconds: 60),
          animate: index == 0,
          child: Text(
            title,
            key: ValueKey('t$index'),
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
          animate: index == 0,
          child: Text(
            body,
            key: ValueKey('b$index'),
            style: theme.textTheme.bodyMedium?.copyWith(
              color: const Color(0xCCF2F7F7),
              height: 1.45,
            ),
            textAlign: TextAlign.center,
          ),
        ),
      ],
    );
  }
}

// ── the interactive hero micro-animations (pure Flutter springs) ─────────────
//
// v3.4.6.d — THE INTERACTIVE DEMO CONTRACT: every hero takes `replay` (a
// counter the parent bumps on tap) and restarts its step-by-step story from
// frame 0 on every bump, so tapping the visual ALWAYS does something visible.

/// Slide 1 — the app emblem breathing on a teal/cyan aura (tap = replay pulse).
class _WelcomeHero extends StatefulWidget {
  const _WelcomeHero({required this.onReplay, this.replay = 0});
  final VoidCallback onReplay;
  final int replay;

  @override
  State<_WelcomeHero> createState() => _WelcomeHeroState();
}

class _WelcomeHeroState extends State<_WelcomeHero>
    with SingleTickerProviderStateMixin {
  late final AnimationController _breath = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 2600),
  );

  @override
  void didUpdateWidget(_WelcomeHero old) {
    super.didUpdateWidget(old);
    // Tap-the-hero = restart the demo from frame 0 (visible every time).
    if (widget.replay != old.replay) _breath.forward(from: 0);
  }

  @override
  void dispose() {
    _breath.dispose();
    super.dispose();
  }
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // The Mornye looping contract: ambient-motion-aware heroes. Reduced
    // motion (or a test harness with disableAnimations) freezes the loop
    // cleanly at its current frame; the spring timer never even starts.
    final live = !MediaQuery.disableAnimationsOf(context);
    if (live && !_breath.isAnimating) {
      _breath.repeat(reverse: true);
    } else if (!live && _breath.isAnimating) {
      _breath.stop();
    }
  }


  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: widget.onReplay,
      child: SizedBox(
        width: 210,
        height: 210,
        child: AnimatedBuilder(
          animation: _breath,
          builder: (context, _) {
            final t = _breath.value;
            final scale = 1.0 + 0.045 * Curves.easeInOut.transform(t);
            final aura = 0.55 + 0.30 * t;
            return Stack(
              alignment: Alignment.center,
              children: [
                // the ambient teal/cyan Mornye aura
                Container(
                  width: 205,
                  height: 205,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    gradient: RadialGradient(
                      colors: [
                        Color.fromRGBO(62, 142, 143, aura),
                        Color.fromRGBO(127, 205, 208, aura * 0.55),
                        const Color(0x000F2E30),
                      ],
                      stops: const [0.05, 0.45, 1.0],
                    ),
                  ),
                ),
                Transform.scale(
                  scale: scale,
                  child: Container(
                    width: 128,
                    height: 128,
                    decoration: BoxDecoration(
                      color: const Color(0x1F1E4E4F),
                      borderRadius: BorderRadius.circular(38),
                      border: Border.all(color: const Color(0x663E8E8F)),
                      boxShadow: const [
                        BoxShadow(
                            color: Color(0x553E8E8F),
                            blurRadius: 24,
                            spreadRadius: 2),
                      ],
                    ),
                    child: const Icon(mi.Icons.cast_for_education_rounded,
                        size: 62, color: Color(0xFF7FCDD0)),
                  ),
                ),
              ],
            );
          },
        ),
      ),
    );
  }
}

/// Slide 2 — the live install state: a glowing green badge when installed, an
/// animated download/demo (arrow bobbing into a tray with the live percent)
/// while downloading. v3.4.6.d: the hero shows the exact installed version
/// (`AnantPlay vX.Y`) and taps replay the demo.
class _InstallHero extends StatelessWidget {
  const _InstallHero({
    required this.installed,
    required this.percent,
    required this.running,
    required this.state,
    required this.onReplay,
    this.replay = 0,
    this.version = '',
  });

  final bool installed;
  final int percent;
  final bool running;
  final String state;
  final VoidCallback onReplay;
  final int replay;
  final String version;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onReplay,
      child: SizedBox(
        width: 210,
        height: 210,
        child: Stack(
          alignment: Alignment.center,
          children: [
            Container(
              width: 190,
              height: 190,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                gradient: RadialGradient(
                  colors: [
                    installed
                        ? const Color(0x8C63D668)
                        : const Color(0x8C3E8E8F),
                    const Color(0x000F2E30),
                  ],
                ),
              ),
            ),
            Container(
              width: 140,
              height: 140,
              decoration: BoxDecoration(
                color: const Color(0x1F1E4E4F),
                borderRadius: BorderRadius.circular(34),
                border: Border.all(
                    color: installed
                        ? const Color(0x9963D668)
                        : const Color(0x663E8E8F)),
              ),
              child: installed
                  ? FittedBox(
                      fit: BoxFit.scaleDown,
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          const Icon(mi.Icons.verified_rounded,
                              size: 56, color: Color(0xFF63D668)),
                          const SizedBox(height: 6),
                          Text(
                            version.isEmpty
                                ? 'me.champdev.weblock'
                                : 'AnantPlay v$version',
                            style: const TextStyle(
                                color: Color(0xCCF2F7F7), fontSize: 11),
                          ),
                        ],
                      ),
                    )
                  // replay seeds a fresh bob each tap (the demo is visible
                  // even while idle — the 3-step story is always clear).
                  : KeyedSubtree(
                      key: ValueKey('install-demo-$replay'),
                      child: _downloadDemo(),
                    ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _downloadDemo() {
    // FittedBox: the demo always fits the 140×140 card on every density —
    // the phone glyph, the live progress bar and the bobbing arrow scale
    // as one composition instead of overflowing.
    return FittedBox(
      fit: BoxFit.scaleDown,
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const Icon(mi.Icons.phone_android_rounded,
              size: 34, color: Color(0xFF7FCDD0)),
          const SizedBox(height: 8),
          SizedBox(
            height: 12,
            width: 84,
            child: Stack(
              children: [
                Container(
                    decoration: BoxDecoration(
                        color: const Color(0x1FFFFFFF),
                        borderRadius: BorderRadius.circular(6))),
                FractionallySizedBox(
                  alignment: Alignment.centerLeft,
                  widthFactor: (running ? percent : 0) / 100.0,
                  child: Container(
                      decoration: BoxDecoration(
                          color: const Color(0xFF3E8E8F),
                          borderRadius: BorderRadius.circular(6))),
                ),
              ],
            ),
          ),
          const SizedBox(height: 8),
          _BobbingArrow(),
        ],
      ),
    );
  }
}

class _BobbingArrow extends StatefulWidget {
  @override
  State<_BobbingArrow> createState() => _BobbingArrowState();
}

class _BobbingArrowState extends State<_BobbingArrow>
    with SingleTickerProviderStateMixin {
  late final AnimationController _bob = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 900),
  );

  @override
  void dispose() {
    _bob.dispose();
    super.dispose();
  }
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // The Mornye looping contract: ambient-motion-aware heroes. Reduced
    // motion (or a test harness with disableAnimations) freezes the loop
    // cleanly at its current frame; the spring timer never even starts.
    final live = !MediaQuery.disableAnimationsOf(context);
    if (live && !_bob.isAnimating) {
      _bob.repeat(reverse: true);
    } else if (!live && _bob.isAnimating) {
      _bob.stop();
    }
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _bob,
      builder: (context, _) => Transform.translate(
        offset: Offset(0, -7 * Curves.easeInOut.transform(_bob.value)),
        child: const Icon(mi.Icons.arrow_downward_rounded,
            size: 22, color: Color(0xFF7FCDD0)),
      ),
    );
  }
}

/// Slide 3 — v3.4.6.d — THE CRYSTAL-CLEAR NOTIFICATIONS STORY: a mini phone
/// mockup with a glowing Cast pill sliding in from the top — "Casting: The
/// Odyssey → Tap to play" — so the 5-word WHY (see casts when closed) is
/// obvious at a glance. Tap = replay the slide-in from the top.
class _NotificationsHero extends StatefulWidget {
  const _NotificationsHero(
      {required this.granted, required this.onReplay, this.replay = 0});
  final bool granted;
  final VoidCallback onReplay;
  final int replay;

  @override
  State<_NotificationsHero> createState() => _NotificationsHeroState();
}

class _NotificationsHeroState extends State<_NotificationsHero>
    with SingleTickerProviderStateMixin {
  late final AnimationController _slide = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1500),
  );

  @override
  void didUpdateWidget(_NotificationsHero old) {
    super.didUpdateWidget(old);
    // Tap-the-hero = the cast pill slides in again from the top.
    if (widget.replay != old.replay) _slide.forward(from: 0);
  }

  @override
  void dispose() {
    _slide.dispose();
    super.dispose();
  }
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // The Mornye looping contract: ambient-motion-aware heroes. Reduced
    // motion (or a test harness with disableAnimations) freezes the loop
    // cleanly at its current frame; the spring timer never even starts.
    final live = !MediaQuery.disableAnimationsOf(context);
    if (live && !_slide.isAnimating) {
      _slide.repeat(reverse: true);
    } else if (!live && _slide.isAnimating) {
      _slide.stop();
    }
  }

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: widget.onReplay,
      child: SizedBox(
        width: 210,
        height: 210,
        child: AnimatedBuilder(
          animation: _slide,
          builder: (context, _) {
            final t = Curves.easeOutCubic.transform(_slide.value);
            return Stack(
              alignment: Alignment.center,
              children: [
                // the mini phone shade (the notification's home)
                Container(
                  width: 184,
                  height: 120,
                  decoration: BoxDecoration(
                    color: const Color(0xE612393B),
                    borderRadius: BorderRadius.circular(24),
                    border: Border.all(color: const Color(0x33FFFFFF)),
                  ),
                ),
                // the glowing Cast pill sliding in from the TOP
                Transform.translate(
                  offset: Offset(0, -46 + 46 * t),
                  child: Opacity(
                    opacity: 0.35 + 0.65 * t,
                    child: Container(
                      width: 172,
                      padding: const EdgeInsets.symmetric(
                          horizontal: 12, vertical: 9),
                      decoration: BoxDecoration(
                        color: const Color(0xFF12393B),
                        borderRadius: BorderRadius.circular(14),
                        border: Border.all(color: const Color(0x663E8E8F)),
                        boxShadow: [
                          BoxShadow(
                              color: const Color(0x663E8E8F),
                              blurRadius: 18 * t),
                        ],
                      ),
                      child: Row(
                        children: [
                          const Icon(mi.Icons.cast_connected_rounded,
                              size: 20, color: Color(0xFF7FCDD0)),
                          const SizedBox(width: 8),
                          const Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text('Casting: The Odyssey',
                                    style: TextStyle(
                                        color: Color(0xFFF2F7F7),
                                        fontSize: 11.5,
                                        fontWeight: FontWeight.w600)),
                                Text('Tap to play',
                                    style: TextStyle(
                                        color: Color(0x99F2F7F7),
                                        fontSize: 9.5)),
                              ],
                            ),
                          ),
                          if (widget.granted)
                            const Icon(mi.Icons.check_circle_rounded,
                                size: 16, color: Color(0xFF63D668))
                          else
                            const Icon(mi.Icons.notifications_active_rounded,
                                size: 16, color: Color(0xFF7FCDD0)),
                        ],
                      ),
                    ),
                  ),
                ),
                // the WHY, in 5 words, right under the phone
                Positioned(
                  bottom: 8,
                  child: Text(
                    'See casts when app is closed',
                    style: TextStyle(
                        color: const Color(0x99F2F7F7).withValues(
                            alpha: 0.6 + 0.4 * t),
                        fontSize: 10.5,
                        fontWeight: FontWeight.w600),
                  ),
                ),
              ],
            );
          },
        ),
      ),
    );
  }
}

/// Slide 4 — v3.4.6.d — THE CRYSTAL-CLEAR 2-FRAME OVERLAY STORY:
/// Frame 1 = "Tap Cast in AnantPlay" (the mini phone with the Cast button),
/// Frame 2 = "Player pops open automatically (no extra tap!)" — the WHY of
/// Display over other apps is obvious at a glance. Tap = replay the 2 frames.
class _OverlayHero extends StatefulWidget {
  const _OverlayHero(
      {required this.installed, required this.onReplay, this.replay = 0});
  final bool installed;
  final VoidCallback onReplay;
  final int replay;

  @override
  State<_OverlayHero> createState() => _OverlayHeroState();
}

class _OverlayHeroState extends State<_OverlayHero>
    with SingleTickerProviderStateMixin {
  late final AnimationController _spring = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1300),
  );

  @override
  void didUpdateWidget(_OverlayHero old) {
    super.didUpdateWidget(old);
    // Tap-the-hero = replay the 2-frame story from Frame 1.
    if (widget.replay != old.replay) _spring.forward(from: 0);
  }

  @override
  void dispose() {
    _spring.dispose();
    super.dispose();
  }
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // The Mornye looping contract: ambient-motion-aware heroes. Reduced
    // motion (or a test harness with disableAnimations) freezes the loop
    // cleanly at its current frame; the spring timer never even starts.
    final live = !MediaQuery.disableAnimationsOf(context);
    if (live && !_spring.isAnimating) {
      _spring.repeat(reverse: true);
    } else if (!live && _spring.isAnimating) {
      _spring.stop();
    }
  }

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: widget.onReplay,
      child: SizedBox(
        width: 210,
        height: 210,
        child: AnimatedBuilder(
          animation: _spring,
          builder: (context, _) {
            final t = _spring.value;
            final pop = 1.0 + 0.06 * Curves.easeOutBack.transform(t);
            return Stack(
              alignment: Alignment.center,
              children: [
                // FRAME 1 — the mini phone + its Cast button
                Container(
                  width: 96,
                  height: 168,
                  decoration: BoxDecoration(
                    color: const Color(0x14FFFFFF),
                    borderRadius: BorderRadius.circular(20),
                    border: Border.all(color: const Color(0x2EFFFFFF)),
                  ),
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Icon(mi.Icons.cast_rounded,
                          size: 30, color: const Color(0xFF7FCDD0)),
                      const SizedBox(height: 4),
                      const Text('Tap Cast',
                          style: TextStyle(
                              color: Color(0x99F2F7F7), fontSize: 9.5)),
                    ],
                  ),
                ),
                // FRAME 2 — the cinema player springing OVER it, by itself
                Transform.scale(
                  scale: 0.55 + 0.45 * t * pop,
                  child: Opacity(
                    opacity: 0.25 + 0.75 * t,
                    child: Container(
                      width: 156,
                      height: 112,
                      decoration: BoxDecoration(
                        color: const Color(0xFF12393B),
                        borderRadius: BorderRadius.circular(18),
                        border: Border.all(color: const Color(0x663E8E8F)),
                        boxShadow: const [
                          BoxShadow(
                              color: Color(0x663E8E8F),
                              blurRadius: 22,
                              spreadRadius: 1),
                        ],
                      ),
                      child: const Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Icon(mi.Icons.play_circle_fill_rounded,
                              size: 44, color: Color(0xFF7FCDD0)),
                          Text('Player opens by itself',
                              style: TextStyle(
                                  color: Color(0xCCF2F7F7),
                                  fontSize: 10.5,
                                  fontWeight: FontWeight.w600)),
                          Text('— no extra tap —',
                              style: TextStyle(
                                  color: Color(0x99F2F7F7), fontSize: 9)),
                        ],
                      ),
                    ),
                  ),
                ),
              ],
            );
          },
        ),
      ),
    );
  }
}

/// Slide 5 — the pulsing shield (universal stability; OEM hints are rendered
/// as action captions, never baked into the headline).
class _StabilityHero extends StatefulWidget {
  const _StabilityHero({
    required this.granted,
    required this.oem,
    required this.onReplay,
    this.replay = 0,
  });

  final bool granted;
  final List<String> oem;
  final VoidCallback onReplay;
  final int replay;

  @override
  State<_StabilityHero> createState() => _StabilityHeroState();
}

class _StabilityHeroState extends State<_StabilityHero>
    with SingleTickerProviderStateMixin {
  late final AnimationController _pulse = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1700),
  );

  @override
  void didUpdateWidget(_StabilityHero old) {
    super.didUpdateWidget(old);
    // v3.4.6.d — tap-the-hero = replay the keep-alive wave from frame 0.
    if (widget.replay != old.replay) _pulse.forward(from: 0);
  }

  @override
  void dispose() {
    _pulse.dispose();
    super.dispose();
  }
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // The Mornye looping contract: ambient-motion-aware heroes. Reduced
    // motion (or a test harness with disableAnimations) freezes the loop
    // cleanly at its current frame; the spring timer never even starts.
    final live = !MediaQuery.disableAnimationsOf(context);
    if (live && !_pulse.isAnimating) {
      _pulse.repeat(reverse: true);
    } else if (!live && _pulse.isAnimating) {
      _pulse.stop();
    }
  }

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: widget.onReplay,
      child: SizedBox(
        width: 210,
        height: 210,
        child: AnimatedBuilder(
          animation: _pulse,
          builder: (context, _) {
            final t = _pulse.value;
            final ring = 0.35 + 0.3 * t;
            return Stack(
              alignment: Alignment.center,
              children: [
                Container(
                  width: 196,
                  height: 196,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    gradient: RadialGradient(colors: [
                      Color.fromRGBO(62, 142, 143, ring * 0.9),
                      const Color(0x000F2E30),
                    ]),
                  ),
                ),
                Container(
                  width: 136,
                  height: 136,
                  decoration: BoxDecoration(
                    color: const Color(0x1F1E4E4F),
                    shape: BoxShape.circle,
                    border: Border.all(
                        color: Color.fromRGBO(62, 142, 143, 0.35 + 0.4 * t)),
                  ),
                  child: Icon(
                    widget.granted
                        ? mi.Icons.verified_user_rounded
                        : mi.Icons.shield_rounded,
                    size: 60,
                    color: widget.granted
                        ? const Color(0xFF63D668)
                        : const Color(0xFF7FCDD0),
                  ),
                ),
                // v3.4.6.d — the cast signal wave staying green & alive when
                // the screen switches apps (the WHY of background stability).
                Positioned(
                  bottom: 6,
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(mi.Icons.podcasts_rounded,
                          size: 13, color: Color(0xFF63D668)),
                      const SizedBox(width: 5),
                      Text(
                        'Cast signal stays alive in background',
                        style: TextStyle(
                            color: const Color(0x99F2F7F7)
                                .withValues(alpha: 0.6 + 0.4 * t),
                            fontSize: 10.5,
                            fontWeight: FontWeight.w600),
                      ),
                    ],
                  ),
                ),
              ],
            );
          },
        ),
      ),
    );
  }
}

/// Slide 6 — the 3-step Sources walk (Open AnantPlay → Settings → Sources →
/// Add Index) as a spring-stepped visual rail + the index URL chip.
class _SourcesHero extends StatefulWidget {
  const _SourcesHero({required this.onReplay, this.replay = 0});
  final VoidCallback onReplay;
  final int replay;

  @override
  State<_SourcesHero> createState() => _SourcesHeroState();
}

class _SourcesHeroState extends State<_SourcesHero>
    with SingleTickerProviderStateMixin {
  late final AnimationController _steps = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 3600),
  );

  @override
  void didUpdateWidget(_SourcesHero old) {
    super.didUpdateWidget(old);
    // v3.4.6.d — tap-the-hero = replay the 3-step walk from step 1.
    if (widget.replay != old.replay) _steps.forward(from: 0);
  }

  @override
  void dispose() {
    _steps.dispose();
    super.dispose();
  }
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // The Mornye looping contract: ambient-motion-aware heroes. Reduced
    // motion (or a test harness with disableAnimations) freezes the loop
    // cleanly at its current frame; the spring timer never even starts.
    final live = !MediaQuery.disableAnimationsOf(context);
    if (live && !_steps.isAnimating) {
      _steps.repeat();
    } else if (!live && _steps.isAnimating) {
      _steps.stop();
    }
  }

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: widget.onReplay,
      child: SizedBox(
        width: 210,
        height: 210,
        child: AnimatedBuilder(
          animation: _steps,
          builder: (context, _) {
            final t = _steps.value; // 0..1 across the whole walk
            final active = ((t * 3).floor()).clamp(0, 2); // step 0..2
            return FittedBox(
              fit: BoxFit.scaleDown,
              child: SizedBox(
                width: 210,
                height: 206,
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    for (var i = 0; i < 3; i++)
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 3),
                    child: AnimatedContainer(
                      duration: const Duration(milliseconds: 380),
                      curve: Curves.easeOutCubic,
                      width: 186,
                      padding: const EdgeInsets.symmetric(
                          horizontal: 12, vertical: 7),
                      decoration: BoxDecoration(
                        color: i <= active
                            ? const Color(0x291E4E4F)
                            : const Color(0x0DFFFFFF),
                        borderRadius: BorderRadius.circular(14),
                        border: Border.all(
                            color: i == active
                                ? const Color(0xFF3E8E8F)
                                : const Color(0x1FFFFFFF)),
                      ),
                      child: Row(
                        children: [
                          Icon(_stepIcon(i),
                              size: 16,
                              color: i <= active
                                  ? const Color(0xFF7FCDD0)
                                  : const Color(0x66F2F7F7)),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Text(_stepText(i),
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: TextStyle(
                                    color: i <= active
                                        ? const Color(0xFFF2F7F7)
                                        : const Color(0x66F2F7F7),
                                    fontSize: 11.5,
                                    fontWeight: FontWeight.w600)),
                          ),
                          if (i < active)
                            const Icon(mi.Icons.check_rounded,
                                size: 14, color: Color(0xFF63D668)),
                        ],
                      ),
                    ),
                  ),
              ],
                  ),
                ),
              );
          },
        ),
      ),
    );
  }

  static IconData _stepIcon(int i) => switch (i) {
        0 => mi.Icons.open_in_new_rounded,
        1 => mi.Icons.settings_rounded,
        _ => mi.Icons.add_circle_rounded,
      };

  static String _stepText(int i) => switch (i) {
        0 => '1. Install AnantPlay',
        1 => '2. Add Index URL in Sources',
        _ => '3. Tap Cast — watch here',
      };
}
