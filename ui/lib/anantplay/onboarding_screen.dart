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

import 'package:anantplay_ui/mornye_ui/widgets/mornye_glass_press.dart';

import 'app_state.dart';
import 'bridge.dart';
import 'package:anantplay_ui/mornye_ui/widgets/mornye_glass_notification.dart';

class OnboardingScreen extends StatefulWidget {
  const OnboardingScreen({super.key});

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen>
    with WidgetsBindingObserver {
  // v3.4.6.k — THE AUTO-RETURN SLIDE-PERSISTENCE FIX (Part_01 00:45/01:15):
  // the PageController always restored the persisted stage, but `_page` (the
  // flip-detection + gating index) reset to 0 and a swipe NEVER persisted —
  // so the 90 s auto-return watcher landing on a REMOUNTED wizard (or any
  // resume that rebuilt the tree) restarted the user on Slide 1. `_page` now
  // initializes from the SAME persisted stage, EVERY page change persists it
  // immediately, and the wizard resumes on the EXACT slide with Enabled ✓.
  late final PageController _pages = PageController(
      initialPage: AppState.of(context).settings.onboardingStage.clamp(0, 5));
  late int _page =
      AppState.of(context).settings.onboardingStage.clamp(0, 5);
  static const _pageCount = 6;

  /// v3.4.6.k — THE FORWARD-SWIPE LOCK GATE: the slides whose OS permission
  /// is required before the user may advance — 2 Notifications, 3 Overlay,
  /// 4 Allow App Updates (LAW #1's own grant). Slides 0/1/5 stay free
  /// (Welcome / an optional install / the guided Sources walk).
  static bool forwardBlocked(int page, OnboardingStatus s) => switch (page) {
        2 => !s.notif,
        3 => !s.overlay,
        4 => !s.installs,
        _ => false,
      };

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
      // v3.4.6.j — THE INSTANT INSTALL DETECTION (video_6 05:00–05:45): the
      // native package-added/replaced receiver + 1 s confirm poll emit
      // {t:'installed', x:'<version>'} the EXACT instant the OS finishes —
      // Slide 2 flips to the glowing green Installed badge and auto-advances,
      // never sticking at `100% · 0 · confirm`.
      if (e.type == 'installed') {
        setState(() {
          _officialInstalledLive = true;
          if (e.extra.isNotEmpty) _officialVersionLive = e.extra;
          _installRunning = false;
          _installPercent = 100;
          _installState = '';
        });
        _confirmAndAdvance();
        return;
      }
      // v3.4.6.j — the confirm state arms the native watcher (the receiver +
      // the 1 s poll keep watching while the PackageInstaller sheet is up).
      if (e.type == 'installState' &&
          (e.value.contains('confirm') || e.extra.contains('confirm'))) {
        AnantPlay.instance.watchOfficialInstall();
      }
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
      // v3.4.6.l — THE SLIDE-1 RESET KILL (video_3 02:46/03:03/03:36): a
      // resume NEVER re-initializes _pageController (the persisted stage stays
      // the single source of truth — the controller was built from it exactly
      // once in initState) and NEVER resets _page. The stage was persisted at
      // EVERY page change AND below, right before any settings page opened, so
      // this resume is a pure re-query — the wizard stays on its slide.
      _requeryStatus();
      _requeryOfficialVersion();
    }
  }

  /// v3.4.6.l — the pre-leave persistence: called right before any callback
  /// opens a system settings page. The stage is ALREADY persisted at every
  /// page change; this is the belt-and-braces lane the auto-return relies on.
  void _persistStageBeforeLeaving() {
    AppState.of(context).scope.setOnboardingStage(_page);
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
    // v3.4.6.f — slide 5 also springs when the INSTALL-UPDATES grant lands
    // (the LAW #3 onboarding guarantee: update-install permission, day one).
    final flipped = switch (_page) {
      1 => !previous.official && s.official,
      2 => !previous.notif && s.notif,
      3 => !previous.overlay && s.overlay,
      4 => (!previous.battery && s.battery)
          || (!previous.installs && s.installs),
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

  void _onPageChanged(int page) {
    // v3.4.6.l — THE REAL FORWARD LOCK (video_3 01:30–01:58).
    //
    // ROOT CAUSE of the v3.4.6.k bypass: PageView wraps any custom `physics`
    // with PageScrollPhysics + _ForceImplicitScrollPhysics, and NEITHER of
    // those chains applyPhysicsToUserOffset down to the inner physics — the
    // v3.4.6.k _ForwardBlockedScrollPhysics class was therefore a SILENT
    // NO-OP (a forward fling sailed straight through on a real phone). The
    // gate now lives at the ONE place the framework guarantees a forward
    // arrival must pass through: onPageChanged. A forward arrival while the
    // CURRENT slide's required OS permission is ungranted snaps STRAIGHT
    // back; backward arrivals (page < _page) are always free.
    if (page > _page && forwardBlocked(_page, _status)) {
      _pages.jumpToPage(_page);
      _announceForwardLock();
      return;
    }
    setState(() => _page = page);
    // v3.4.6.k — persist the stage THE INSTANT the slide changes (swipes
    // included): the auto-return / remount path resumes on the EXACT slide.
    AppState.of(context).scope.setOnboardingStage(page);
  }

  /// The honest little hint that the slide's grant is still missing.
  void _announceForwardLock() {
    showMornyeGlassNotification(context,
        message: "Grant this slide's permission to continue");
  }

  void _finish() {
    // THE UNBREAKABLE FINISH: synchronous in-memory publish (ShellRoot flips
    // to HomeScreen this frame) + the dedicated native channel writer.
    // v3.4.6.i — THE DEAD Skip/Finish KILL (video_4 00:41–01:15): when the
    // wizard runs as a pushed ROUTE (Settings → Run setup guide / Help →
    // Run setup wizard / Home → Watch Tour), completeOnboarding flips the
    // shell underneath but the route stayed on top — the Finish looked dead.
    // The route (when one exists) is popped FIRST so the user lands on
    // HomeScreen immediately; the root-gate instance (first install) never
    // had a route to pop and keeps working exactly as before.
    final navigator = Navigator.of(context);
    final bool pushedAsRoute = navigator.canPop();
    if (pushedAsRoute) {
      navigator.pop();
    }
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
    // v3.4.6.j — THE MANDATORY FIRST-TIME WIZARD (video_4 00:30–01:10): a
    // first-time launch (onboardingCurrent == false) has NO Skip at all —
    // every slide is completed properly before the next one. A voluntary
    // re-run (Settings → Run setup guide / Help → Watch Tour) gets the clean
    // top-right Close instead. The last slide keeps its own Finish action.
    final bool firstTimeLaunch = !AppState.of(context).settings.onboardingCurrent;
    final String? closeLabel = firstTimeLaunch ? null : 'Close';
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
              // the top-right Close (a voluntary re-run only — NEVER on a
              // mandatory first-time launch; any slide but the last)
              Align(
                alignment: Alignment.centerRight,
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
                  child: _page < _pageCount - 1 && closeLabel != null
                      ? TextButton(
                          onPressed: _skip,
                          style: TextButton.styleFrom(
                            foregroundColor: const Color(0x99F2F7F7),
                            textStyle: const TextStyle(fontSize: 13),
                          ),
                          child: Text(closeLabel),
                        )
                      : const SizedBox(height: 44),
                ),
              ),
              Expanded(
                child: PageView.builder(
                  controller: _pages,
                  onPageChanged: _onPageChanged,
                  // v3.4.6.k — THE SETUP-WIZARD LOCKDOWN (Part_01): BACKWARD
                  // swiping stays free, while FORWARD swiping is completely
                  // blocked until the CURRENT slide's required OS permission
                  // is granted (the physics freezes the forward drag AND the
                  // forward fling; the Back button / dots stay available).
                  physics: _ForwardBlockedScrollPhysics(
                    blockForward: forwardBlocked(_page, _status),
                    parent: const AlwaysScrollableScrollPhysics(),
                  ),
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

  // ── the slide scaffold: 55–60% hero stage · 35–40% title/body/actions ──────

  /// v3.4.6.i — THE RED-PEN GEOMETRY (video_5 02:55–04:35): each slide is a
  /// fixed Column — the hero stage fills the UPPER ~58% (Expanded 3:2) with
  /// large crystal-clear visuals (never a cramped 210dp box with wasted
  /// space below), the title + 1–2 line subtitle + full-width action button
  /// anchor the lower ~40% right above the page dots.
  Widget _slide(int i) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 24),
      child: Column(
        children: [
          // ── THE HERO STAGE: the upper 55–60%, filling generously ─────────
          Expanded(
            flex: 3,
            child: Padding(
              padding: const EdgeInsets.only(top: 10, bottom: 6),
              child: FittedBox(
                fit: BoxFit.contain,
                child: _heroFor(i),
              ),
            ),
          ),
          // ── THE TEXT + ACTION BLOCK: the lower 35–40% ────────────────────
          Expanded(
            flex: 2,
            child: SingleChildScrollView(
              physics: const BouncingScrollPhysics(),
              child: Column(
                children: [
                  Text(
                    _titles[i],
                    key: ValueKey('t$i'),
                    style: theme.textTheme.headlineSmall?.copyWith(
                      color: const Color(0xFFF2F7F7),
                      fontWeight: FontWeight.w700,
                    ),
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 12),
                  Text(
                    _bodies[i],
                    key: ValueKey('b$i'),
                    style: theme.textTheme.bodyMedium?.copyWith(
                      color: const Color(0xCCF2F7F7),
                      height: 1.45,
                    ),
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 18),
                  _actionsFor(i),
                ],
              ),
            ),
          ),
        ],
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

  // v3.4.6.i — the CONCISE 1–2 line subtitles (the red-pen sketch: a short
  // subtitle under the title, never a dense paragraph).
  static const _bodies = [
    'Sinhala subtitles cinema — one receiver, zero forks.',
    'Install the official app right here — one tap.',
    'Cast alerts arrive as quiet notifications.',
    'A cast opens the Cinema Player by itself.',
    'Two clean permission rows — then one Continue.',
    'Copy your index feeds into AnantPlay in one tap.',
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
            onOpen: () async {
              _persistStageBeforeLeaving();   // v3.4.6.l — persist BEFORE the dialog
              await AnantPlay.instance.requestNotifications();
            },
            onContinue: () => _go(3)),
        // v3.4.6.d — the overlay slide ALSO arms the 30-second return
        // watcher: the instant the user flips the toggle in Android Settings
        // the app comes back to the front and auto-advances.
        3 => _permissionActions(
            granted: _status.overlay,
            openLabel: 'Enable Auto-Open',
            onOpen: () async {
              _persistStageBeforeLeaving();   // v3.4.6.l — persist BEFORE leaving
              await AnantPlay.instance.openOverlaySettings();
              await AnantPlay.instance.watchOverlayGrant();
            },
            onContinue: () => _go(4)),
        4 => _stabilityActions(),
        5 => _sourcesActions(),
        _ => const SizedBox.shrink(),
      };

  /// v3.4.6.f — LAW #3's ONBOARDING HALF: slide 5 ("Keep Cast ready") carries
  /// BOTH stability permissions — background activity AND the install-updates
  /// (unknown-sources) grant — so brand-new AND upgrading users can guarantee
  /// update-install permission right away (the exact permission the
  /// ForceUpdateScreen's Step 1 of 2 needs at update time).
  ///
  /// v3.4.6.i — THE RED-PEN REDESIGN (video_5 04:01–04:18): the confusing
  /// double `[✓ Enabled ✓] [Continue] [✓ Enabled ✓] [Continue]` stack is
  /// GONE. TWO cleanly labeled compact permission rows inside ONE Mornye
  /// glass card + ONE single full-width Continue button at the bottom:
  ///   Row 1: Background Activity (No restrictions) → [Enable] / [Enabled ✓]
  ///   Row 2: Allow App Updates                  → [Enable] / [Enabled ✓]
  Widget _stabilityActions() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _StabilityCard(
          batteryGranted: _status.battery,
          installsGranted: _status.installs,
          onBattery: () {
            // v3.4.6.j — the 90 s / 400 ms bounded watcher + the BAL snap-back
            // fire with the deep link (the ESET-style instant return).
            _persistStageBeforeLeaving();   // v3.4.6.l — persist BEFORE leaving
            AnantPlay.instance.openBatteryOptimization();
            AnantPlay.instance.watchBatteryGrant();
          },
          onInstalls: () {
            // v3.4.6.j — the same watcher contract for the install grant.
            _persistStageBeforeLeaving();   // v3.4.6.l — persist BEFORE leaving
            AnantPlay.instance.openUnknownSources();
            AnantPlay.instance.watchInstallPermission();
          },
        ),
        const SizedBox(height: 10),
        // v3.4.6.l — the mandatory gate (video_3 01:30–01:58): the Continue
        // action only appears once BOTH stability grants are real. Until then
        // the honest instruction line says exactly what is still missing.
        _status.battery && _status.installs
            ? _pill(label: 'Continue', onTap: () => _go(5), prominent: true)
            : _caption(
                '${_status.battery ? 'Battery: On' : 'Grant No restrictions'} · '
                '${_status.installs ? 'App updates: allowed' : 'Grant Allow app updates'}'),
      ],
    );
  }

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
    // v3.4.6.l — THE FORWARD-LOCK'S SECOND HALF (video_3 01:30–01:58):
    // 'Continue anyway' is DELETED from the mandatory first-time permission
    // slides (Notifications · Auto-open on cast). A slide whose OS grant the
    // whole receiver depends on is completed properly — the ONLY way forward
    // is the grant (or the hardware Back, which stays free). The voluntary
    // re-run keeps no escape either: the wizard is re-entered by choice.
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _pill(label: openLabel, onTap: () => onOpen(), prominent: true),
        const SizedBox(height: 8),
        _caption('Grant it to continue — Back stays free.'),
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
              showMornyeGlassNotification(context,
                  message: 'Index URL copied');
            }
          },
          icon: mi.Icons.copy_rounded,
        ),
        const SizedBox(height: 8),
        _pill(
          // v3.4.6.k — renamed per the red-pen audit: the slide-6 CTA starts
          // the coach walkthrough too (official AnantPlay + the floating
          // AnantPlayCoachService in one tap).
          label: 'Open AnantPlay with User Guide',
          onTap: () => AnantPlay.instance.openOfficialWithCoach(),
          icon: mi.Icons.open_in_new_rounded,
        ),
        const SizedBox(height: 10),
        _caption('In AnantPlay: Settings → Sources → Add Index, paste, done.'),
        // v3.4.6.i — ONE clean full-width Finish (the ONE primary action of
        // the final slide — never a duplicate Finish + Next stack).
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

  // ── the footer: Back · clickable dots (the Next button is GONE) ──────────
  // v3.4.6.i — the red-pen sketch (video_5): only Back (bottom-left when
  // page > 0) and the centered dots remain; each slide's ONE full-width
  // primary action button lives inside the slide itself, right above the
  // dots — the final slide's is `Finish` (never a duplicate Finish + Next).

  Widget _footer() {
    return Row(
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
        Expanded(
          child: Row(
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
        ),
        // the right slot stays EMPTY (no Next — each slide owns its own
        // single full-width primary action inside the slide).
        const SizedBox(width: 86, height: 44),
      ],
    );
  }
}

// ── v3.4.6.i — THE SLIDE-5 PERMISSION CARD (the red-pen redesign) ────────────
//
// TWO cleanly labeled compact permission rows inside ONE Mornye glass card:
//   Row 1: Background Activity (No restrictions) → [Enable] / [Enabled ✓]
//   Row 2: Allow App Updates                    → [Enable] / [Enabled ✓]
// ONE single full-width Continue button sits BELOW the card (added by the
// caller) — the v3.4.6.h confusing `[✓ Enabled ✓] [Continue] ×2` stack is
// dead (video_5 04:01–04:18).

class _StabilityCard extends StatelessWidget {
  const _StabilityCard({
    required this.batteryGranted,
    required this.installsGranted,
    required this.onBattery,
    required this.onInstalls,
  });

  final bool batteryGranted;
  final bool installsGranted;
  final VoidCallback onBattery;
  final VoidCallback onInstalls;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: const Color(0x14FFFFFF),
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: const Color(0x33FFFFFF)),
      ),
      child: Column(
        children: [
          _StabilityRow(
            title: 'Background Activity (No restrictions)',
            granted: batteryGranted,
            onEnable: onBattery,
          ),
          Divider(
            height: 1,
            thickness: 0.5,
            color: const Color(0x22FFFFFF),
          ),
          _StabilityRow(
            title: 'Allow App Updates',
            granted: installsGranted,
            onEnable: onInstalls,
          ),
        ],
      ),
    );
  }
}

class _StabilityRow extends StatelessWidget {
  const _StabilityRow({
    required this.title,
    required this.granted,
    required this.onEnable,
  });

  final String title;
  final bool granted;
  final VoidCallback onEnable;

  @override
  Widget build(BuildContext context) {
    final color = granted ? const Color(0xFF63D668) : const Color(0xFF7FCDD0);
    return Padding(
      padding: const EdgeInsets.fromLTRB(14, 8, 8, 8),
      child: Row(
        children: [
          Icon(
            granted ? mi.Icons.check_circle_rounded : mi.Icons.bolt_rounded,
            size: 18,
            color: color,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              title,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                color: Color(0xFFF2F7F7),
                fontSize: 13,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
          const SizedBox(width: 8),
          granted
              ? Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 10),
                  child: Text(
                    'Enabled ✓',
                    style: TextStyle(
                      color: color,
                      fontSize: 12.5,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                )
              : TextButton(
                  onPressed: onEnable,
                  style: TextButton.styleFrom(
                    minimumSize: const Size(84, 40),
                    tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                    foregroundColor: const Color(0xFF7FCDD0),
                    textStyle: const TextStyle(
                        fontSize: 12.5, fontWeight: FontWeight.w700),
                  ),
                  child: const Text('Enable'),
                ),
        ],
      ),
    );
  }
}

// ── the interactive hero micro-animations (pure Flutter springs) ─────────────
//
// v3.4.6.d — THE INTERACTIVE DEMO CONTRACT: every hero takes `replay` (a
// counter the parent bumps on tap) and restarts its step-by-step story from
// frame 0 on every bump, so tapping the visual ALWAYS does something visible.

/// Slide 1 — the app emblem breathing on a teal/cyan aura (tap = replay pulse).
/// v3.4.6.g — SLIDE 1's INTERACTIVE ANIMATED VALUE STORY. The exact app
/// pitch in three visual beats, animated: (1) Pick movie in AnantPlay,
/// (2) Auto Sinhala Subtitles — the ZWJ conjunct ශ්‍රී intact, (3) the cool
/// zero-heat Cinema Player. Tapping the hero replays from beat 1; tapping a
/// step chip replays from that beat (clickable step-by-step replay).
class _WelcomeHero extends StatefulWidget {
  const _WelcomeHero({required this.onReplay, this.replay = 0});
  final VoidCallback onReplay;
  final int replay;

  @override
  State<_WelcomeHero> createState() => _WelcomeHeroState();
}

class _WelcomeHeroState extends State<_WelcomeHero>
    with TickerProviderStateMixin {
  /// The story controller: 5.4 s for three beats (1.8 s each).
  late final AnimationController _story = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 5400),
  );

  /// The ambient aura loop around the active card.
  late final AnimationController _aura = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 2600),
  );

  static const List<String> _beatTitles = <String>[
    '1. Pick movie in AnantPlay',
    '2. Auto Sinhala Subtitles',
    // v3.4.6.j — crisp natural copy (the awkward "උණුසුම් නැති සිනමා
    // වේදිකාව" is gone — video_6 04:20).
    '3. Smooth, Cool Cinema Player',
  ];
  static const List<String> _beatSubs = <String>[
    'ඔබේ චිත්‍රපටිය AnantPlay තුළ තෝරන්න',
    'සිංහල උපසිරැසි ස්වයංක්‍රීයවම',
    'සිංහල උපසිරැසි සිනමා ධාවකය',
  ];

  int get _beat => (_story.value * 3).floor().clamp(0, 2);

  void _playFrom(double v) {
    final live = !MediaQuery.disableAnimationsOf(context);
    if (live) {
      _story.forward(from: v);
    } else {
      // Reduced motion: hold the beat's end frame (still the full story).
      _story.value = (v + 0.999).clamp(0.0, 1.0) <= 1.0 ? (v + 0.98).clamp(0.0, 1.0) : 1.0;
    }
  }

  @override
  void didUpdateWidget(_WelcomeHero old) {
    super.didUpdateWidget(old);
    // Tap-the-hero = restart the demo from beat 1 (visible every time).
    if (widget.replay != old.replay) _playFrom(0);
  }

  @override
  void dispose() {
    _story.dispose();
    _aura.dispose();
    super.dispose();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // The Mornye looping contract: ambient-motion-aware. Reduced motion
    // freezes the story at its final beat; the aura loop parks.
    final live = !MediaQuery.disableAnimationsOf(context);
    if (live) {
      if (!_story.isAnimating && _story.value >= 1.0) _story.value = 1.0;
      if (!_story.isAnimating && _story.value == 0.0) _story.forward();
      if (!_aura.isAnimating) _aura.repeat(reverse: true);
    } else {
      if (_story.isAnimating) _story.stop();
      if (_aura.isAnimating) _aura.stop();
      if (_story.value == 0.0) _story.value = 1.0;
    }
  }

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: widget.onReplay,
      child: SizedBox(
        width: 210,
        height: 238,
        child: AnimatedBuilder(
          animation: Listenable.merge([_story, _aura]),
          builder: (context, _) {
            final beat = _beat;
            // local progress inside the active beat (0..1)
            final p = (_story.value * 3 - beat).clamp(0.0, 1.0);
            final glow = 0.55 + 0.30 * _aura.value;
            return Column(
              children: [
                SizedBox(
                  width: 210,
                  height: 158,
                  child: Stack(
                    alignment: Alignment.center,
                    children: [
                      // the ambient Mornye aura (teal → green → cool blue per beat)
                      Container(
                        width: 205,
                        height: 205,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          gradient: RadialGradient(
                            colors: [
                              Color.fromRGBO(62, 142, 143, glow),
                              beat == 2
                                  ? Color.fromRGBO(100, 210, 255, glow * 0.6)
                                  : Color.fromRGBO(127, 205, 208, glow * 0.55),
                              const Color(0x000F2E30),
                            ],
                            stops: const [0.05, 0.45, 1.0],
                          ),
                        ),
                      ),
                      // THE BEAT SCENE — a 132×104 mini stage.
                      Container(
                        width: 132,
                        height: 104,
                        decoration: BoxDecoration(
                          color: const Color(0x1F1E4E4F),
                          borderRadius: BorderRadius.circular(26),
                          border: Border.all(color: const Color(0x663E8E8F)),
                          boxShadow: const [
                            BoxShadow(
                                color: Color(0x553E8E8F),
                                blurRadius: 24,
                                spreadRadius: 2),
                          ],
                        ),
                        child: _beatScene(beat, p),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 7),
                // ── the clickable step-by-step replay chips (1 · 2 · 3) ──
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    for (var i = 0; i < 3; i++)
                      Padding(
                        padding: EdgeInsets.only(left: i == 0 ? 0 : 7),
                        child: GestureDetector(
                          behavior: HitTestBehavior.opaque,
                          onTap: () => _playFrom(i / 3),
                          child: Container(
                            width: 34,
                            height: 26,
                            alignment: Alignment.center,
                            decoration: BoxDecoration(
                              color: i == beat
                                  ? const Color(0x337FCDD0)
                                  : const Color(0x141E4E4F),
                              borderRadius: BorderRadius.circular(13),
                              border: Border.all(
                                color: i == beat
                                    ? const Color(0xCC7FCDD0)
                                    : const Color(0x443E8E8F),
                              ),
                            ),
                            child: Text(
                              '${i + 1}',
                              style: TextStyle(
                                fontSize: 12,
                                fontWeight: i == beat
                                    ? FontWeight.w800
                                    : FontWeight.w600,
                                color: i == beat
                                    ? const Color(0xFFEFFCFB)
                                    : const Color(0xB3D6E9E9),
                              ),
                            ),
                          ),
                        ),
                      ),
                  ],
                ),
                const SizedBox(height: 6),
                // the ACTIVE beat's full title + its Sinhala line. Both are
                // single-line (ellipsized on hostile metrics — the fixed 238
                // hero box must never overflow on any font fallback).
                Text(
                  _beatTitles[beat],
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w700,
                      color: Color(0xFFEFFCFB),
                      height: 1.2),
                ),
                const SizedBox(height: 3),
                Text(
                  _beatSubs[beat],
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                      fontSize: 10.5, color: Color(0xCCD6E9E9), height: 1.2),
                ),
              ],
            );
          },
        ),
      ),
    );
  }

  /// One beat's mini scene (132×104 stage, Mornye glass).
  Widget _beatScene(int beat, double p) {
    switch (beat) {
      case 0:
        // BEAT 1 — Pick movie in AnantPlay: a movie list; the first row
        // highlights and the cast pill flies in from the top-right.
        final ease = Curves.easeOutCubic.transform(p);
        return Stack(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 12, 12, 10),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _mockRow(highlight: p > 0.25, leading: mi.Icons.movie_rounded),
                  const SizedBox(height: 6),
                  _mockRow(highlight: false, leading: mi.Icons.live_tv_rounded),
                ],
              ),
            ),
            Positioned(
              top: 8,
              right: 10 - 46 * (1 - ease),
              child: Opacity(
                opacity: ease.clamp(0.0, 1.0),
                child: Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                  decoration: BoxDecoration(
                    color: const Color(0xCC123B3C),
                    borderRadius: BorderRadius.circular(10),
                    border: Border.all(color: const Color(0xCC7FCDD0)),
                  ),
                  child: const Icon(mi.Icons.cast_rounded,
                      size: 14, color: Color(0xFF7FCDD0)),
                ),
              ),
            ),
          ],
        );
      case 1:
        // BEAT 2 — Auto Sinhala Subtitles: the mini player with the live
        // Sinhala cue pill (the ZWJ conjunct ශ්‍රී verbatim) sliding up.
        final slide = 14 * (1 - Curves.easeOutCubic.transform(p));
        return Stack(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(10, 10, 10, 8),
              child: Column(
                children: [
                  Container(
                    height: 40,
                    decoration: BoxDecoration(
                      color: const Color(0x33101826),
                      borderRadius: BorderRadius.circular(10),
                    ),
                    alignment: Alignment.center,
                    child: const Icon(mi.Icons.smart_display_rounded,
                        size: 22, color: Color(0xCC64D2FF)),
                  ),
                ],
              ),
            ),
            Positioned(
              left: 10,
              right: 10,
              bottom: 10 + slide,
              child: Opacity(
                opacity: p.clamp(0.0, 1.0),
                child: Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                  decoration: BoxDecoration(
                    color: const Color(0xD90E1626),
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: const Color(0x9964D2FF)),
                  ),
                  child: const Text(
                    'ශ්‍රී ලංකා · සිංහල උපසිරැසි සූදානම්',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                        fontSize: 8.5,
                        color: Color(0xFFDFF3FF),
                        fontFamily: 'Noto Sans Sinhala'),
                  ),
                ),
              ),
            ),
          ],
        );
      default:
        // BEAT 3 — Cool Cinema Player: the mini player wearing the cool
        // zero-heat badge; the thermometer settles into the blue.
        final settle = Curves.easeOutBack.transform(p);
        return Stack(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(10, 10, 10, 8),
              child: Container(
                height: 40,
                decoration: BoxDecoration(
                  color: const Color(0x33101826),
                  borderRadius: BorderRadius.circular(10),
                ),
                alignment: Alignment.center,
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(mi.Icons.play_circle_rounded,
                        size: 22,
                        color: Color.fromRGBO(
                            100, 210, 255, (0.4 + 0.6 * settle).clamp(0.0, 1.0))),
                    const SizedBox(width: 8),
                    Icon(mi.Icons.ac_unit_rounded,
                        size: 16,
                        color: Color.fromRGBO(
                            124, 227, 139, (0.3 + 0.7 * settle).clamp(0.0, 1.0))),
                  ],
                ),
              ),
            ),
            Positioned(
              left: 10,
              right: 10,
              bottom: 10,
              child: Opacity(
                opacity: p.clamp(0.0, 1.0),
                child: Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                  decoration: BoxDecoration(
                    color: const Color(0xD90E1626),
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: const Color(0x997CE38B)),
                  ),
                  child: const Text(
                    // v3.4.6.k — THE HONEST MARATHON PILL (the video's
                    // made-up '5–6 පැයක්' marathon claim never survived the
                    // audit): the truthful battery contract of the hardware
                    // cinema player.
                    'Smooth & Battery-Friendly · සිංහල උපසිරැසි',
                    textAlign: TextAlign.center,
                    style: TextStyle(fontSize: 8, color: Color(0xFFE4FFE9)),
                  ),
                ),
              ),
            ),
          ],
        );
    }
  }

  Widget _mockRow({required bool highlight, required IconData leading}) {
    return Container(
      height: 26,
      padding: const EdgeInsets.symmetric(horizontal: 8),
      decoration: BoxDecoration(
        color: highlight ? const Color(0x2E7FCDD0) : const Color(0x141E4E4F),
        borderRadius: BorderRadius.circular(8),
        border: highlight
            ? Border.all(color: const Color(0x997FCDD0))
            : Border.all(color: const Color(0x333E8E8F)),
      ),
      child: Row(
        children: [
          Icon(leading,
              size: 13, color: const Color(0xFFBFE9EA)),
          const SizedBox(width: 7),
          Container(
            width: 44,
            height: 5,
            decoration: BoxDecoration(
              color: const Color(0x66D6E9E9),
              borderRadius: BorderRadius.circular(3),
            ),
          ),
          const Spacer(),
          Container(
            width: 16,
            height: 16,
            decoration: const BoxDecoration(
              color: Color(0x26D6E9E9),
              shape: BoxShape.circle,
            ),
          ),
        ],
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
        // v3.4.6.k — the full pill text fits (the v3.4.6.j ellipsis
        // truncated it to '2. Add Index URL in Sour…').
        1 => '2. Add Index in Sources',
        _ => '3. Tap Cast — watch here',
      };
}

/// v3.4.6.k — THE SETUP-WIZARD FORWARD-SWIPE LOCK PHYSICS (Part_01):
/// allows swiping BACKWARD (drag right / velocity < 0) freely, while
/// completely blocking any FORWARD user drag or fling while the current
/// slide's required OS permission is ungranted. The forward drag offset is
/// frozen at 0 (the page never moves) and a forward fling is neutralized;
/// everything else (bounce, backward, ballistic settle) is the parent's.
class _ForwardBlockedScrollPhysics extends BouncingScrollPhysics {
  const _ForwardBlockedScrollPhysics({
    required this.blockForward,
    super.parent,
  });

  /// True while the current slide's required permission is NOT granted.
  final bool blockForward;

  @override
  _ForwardBlockedScrollPhysics applyTo(ScrollPhysics? ancestor) =>
      _ForwardBlockedScrollPhysics(
        blockForward: blockForward,
        parent: buildParent(ancestor),
      );

  @override
  double applyPhysicsToUserOffset(ScrollMetrics position, double offset) {
    // FORWARD = toward the next page. The framework hands this method the
    // pointer's own primary delta (v3.4.6.l note: a FORWARD horizontal drag
    // — finger moving LEFT — arrives here as a NEGATIVE offset), so the
    // forward freeze is the NEGATIVE branch; the positive branch (finger
    // right = BACKWARD) falls through to the parent for free back-swiping.
    if (blockForward && offset < 0) return 0;
    return super.applyPhysicsToUserOffset(position, offset);
  }

  @override
  Simulation? createBallisticSimulation(ScrollMetrics position, double velocity) {
    // A forward FLING lands here as a POSITIVE velocity (the framework
    // negates the pointer delta for the ballistic phase); it is neutralized
    // while the gate is unmet — no momentum carry-over can sneak the page
    // forward. Backward flings (negative velocities) stay live.
    final double v = (blockForward && velocity > 0) ? 0 : velocity;
    return super.createBallisticSimulation(position, v);
  }
}
