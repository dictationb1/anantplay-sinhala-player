import 'dart:ui';
// AnantPlay Sinhala Player v3.4.6.c — the Mornye Liquid-Glass shell entry.
//
// Hosts the vendored Mornye v5.4.0 engine (ui/lib/mornye_ui, LGPL-3.0-only)
// and routes between the screens: onboarding (first launch OR a v3.4.6.a →
// v3.4.6.c upgrade — see the onboarding-VERSION gate below), and the 4-tab
// shell (Home · Browser · Settings · Help) with the ALWAYS-VISIBLE floating
// Mornye Liquid-Glass bottom navigation bar (MornyeTabBar + the GLSL held
// selection lens), edge-to-edge safe-area padded, zero hit-test gaps.
//
// v3.4.6.c — THE "FAKE UI" ROOT-CAUSE FIXES (all four, verified line-by-line
// against the v3.4.6.b tree the real phone ran):
//   1. v3.4.6.b's ShellRoot returned a BARE HomeScreen — no tab bar at all,
//      so SettingsScreen/HelpScreen were orphaned widgets NOTHING rendered
//      ("Customization and Settings are COMPLETELY MISSING"). This shell
//      hosts all four tabs in one Stack with the MornyeTabBar on top.
//   2. The onboarding gate read the plain onboardingDone BOOL, so anyone
//      upgrading from v3.4.6.a (onboardingDone=true already persisted) NEVER
//      saw the new 6-slide wizard. The gate is now the K_ONBOARDING_VERSION
//      INT: v3.4.6.a persisted version 1, the 6-slide interactive wizard is
//      version 2, and version < 2 shows the wizard ONCE (Skip + Finish both
//      work, and Settings/Help keep a "Run setup guide" re-entry).
//   3. The player/browser live HERE now: PlayerScreen renders Media3 video
//      inside a real Flutter Texture (Media3TextureBridge), and BrowserScreen
//      hosts the hardened WebBrowserView PlatformView — no PlayActivity hops.
//   4. A cast / a DLNA SetAVTransportURI / a Test tap / a shared URL all
//      pump through the SAME shell route (ShellRoot.pushPlayer) so the
//      Mornye Cinema Player opens IN the shell.
import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

import 'package:anantplay_ui/mornye_ui/theme/mornye_theme.dart';
import 'package:anantplay_ui/mornye_ui/theme/app_tokens.dart';
import 'package:anantplay_ui/mornye_ui/models/theme_settings.dart';
import 'package:anantplay_ui/mornye_ui/widgets/mornye_glass_preparation.dart';
import 'package:anantplay_ui/mornye_ui/theme/mornye_icons.dart';
import 'package:anantplay_ui/mornye_ui/widgets/mornye_chrome.dart';
import 'package:dynamic_color/dynamic_color.dart';

import 'anantplay/app_state.dart';
import 'anantplay/bridge.dart';
import 'anantplay/settings_mirror.dart';
import 'anantplay/force_update_screen.dart';
import 'anantplay/help_screen.dart';
import 'anantplay/home_screen.dart';
import 'anantplay/onboarding_screen.dart';
import 'anantplay/settings_screen.dart';
import 'anantplay/browser_screen.dart';
import 'anantplay/player_screen.dart';

// ── v3.4.6.b — THE DART-SIDE CRASH REPORTER ─────────────────────────────────
// The native layer exposes `anantplay/crashlog` (MainActivity.onCrashlogCall),
// which persists the report into the SAME CrashLog file native crashes use.
// Both hooks below are registered BEFORE runApp() and guard themselves: a
// reporter that throws inside the error hook would loop, so every send is
// try/caught and async-fire-and-forget — the framework still gets to render
// the error screen / keep running (PlatformDispatcher.onError returning true
// means "handled": a startup error must never take the whole app down with it,
// which is the exact "won't open" failure class this build exists to kill).
const MethodChannel _crashlog = MethodChannel('anantplay/crashlog');

void _forwardToNativeCrashLog(String kind, Object error, StackTrace? stack,
    {String zone = 'main'}) {
  try {
    _crashlog.invokeMethod('report', <String, Object>{
      'kind': kind,
      'zone': zone,
      'error': error.toString(),
      'stack': stack?.toString() ?? '',
    }).catchError((Object e) {/* the native side already logged; drop */});
  } catch (_) {
    // The channel itself is down (engine-level failure) — nothing more to do.
  }
}

void _installCrashHooks() {
  FlutterError.onError = (FlutterErrorDetails details) {
    _forwardToNativeCrashLog('FlutterError', details.exception, details.stack,
        zone: details.library ?? 'flutter');
    FlutterError.presentError(details); // keep the default red/error surface too
  };
  PlatformDispatcher.instance.onError = (Object error, StackTrace stack) {
    _forwardToNativeCrashLog('PlatformError', error, stack);
    return true; // handled — the app stays alive
  };
}

void main() {
  _installCrashHooks();
  runApp(const AnantPlayApp());
}

class AnantPlayApp extends StatelessWidget {
  const AnantPlayApp({super.key});

  @override
  Widget build(BuildContext context) {
    // v3.4.6.b — FULL MORNYE PARITY: ProviderScope (the riverpod root the
    // vendored Mornye providers — glass level, runtime profile — resolve
    // against) + MornyeGlassPreparation (upstream app.dart's exact placement:
    // inside the app widget tree, wrapping the shell; it fires the one-time
    // GPU warm-up when liquid glass is on so the FIRST glass frame never
    // janks — the same contract upstream keeps).
    return ProviderScope(
      child: AppStateScope(
        child: MornyeGlassPreparation(
          // v3.4.6.f — GAP #1: the ONE place that builds the app's ThemeData.
          // A hardcoded MaterialApp here meant every Settings slider wrote a
          // pref but painted nothing until a cold restart. _ThemedApp reads
          // AppState on EVERY rebuild, so the very next frame after any
          // customization repaints the whole tree.
          child: const _ThemedApp(),
        ),
      ),
    );
  }
}

/// The live Mornye theme bridge. Every appearance key the Studio writes flows
/// through the app state notifier into this widget; because it reads
/// AppState.of during build, Flutter rebuilds + repaints the entire app the
/// instant a preference changes (no restart, no setState in 40 screens).
/// v3.4.6.g: wrapped in dynamic_color's DynamicColorBuilder for REAL
/// Material You wallpaper palettes.
class _ThemedApp extends StatelessWidget {
  const _ThemedApp();

  static const _accentColors = <Color>[
    Color(0xFF7FCDD0), Color(0xFFFFB340), Color(0xFF63D668),
    Color(0xFF64D2FF), Color(0xFF7E7FF0), Color(0xFFF07FD0), Color(0xFFFF6482),
  ];

  @override
  Widget build(BuildContext context) {
    final s = AppState.of(context).settings;
    final String modeId = s.themeMode;
    final bool amoled = modeId == 'amoled';
    final ThemeMode mode = modeId == 'system'
        ? ThemeMode.system
        : (modeId == 'light' ? ThemeMode.light : ThemeMode.dark);
    // The platform dispatcher keeps system-brightness in sync with the
    // rendering binding; MaterialApp resolves ThemeMode.system itself.
    PlatformDispatcher.instance.platformBrightness;
    // Presets 0–6 map 1:1 onto MornyeAccent. A valid custom #RRGGBB or the
    // Material You seed overrides the seed roles on the derived ColorScheme.
    final int accentIndex = s.accent.clamp(0, _accentColors.length - 1);
    final MornyeAccent accent = MornyeAccent.values[accentIndex];
    final String hex = s.accentHex.trim();
    final int? custom = hex.length == 6 ? int.tryParse('FF$hex', radix: 16) : null;
    final Color? override = custom != null
        ? Color(custom)
        : (s.dynamicColor ? const Color(0xFF6750A4) : null);

    // 0.10–0.95 is the Studio's published glass range; "flat" force-blurs off.
    final double clarity =
        (s.i(SettingsMirror.kGlassClarity, 75) / 100.0).clamp(0.10, 0.95);
    final bool flat =
        s.s(SettingsMirror.kSurfaceStyle, 'glass') == 'flat';
    final bool outlines = flat ? false : s.glassOutlines;

    // ── v3.4.6.g — REAL MATERIAL YOU ──────────────────────────────────────
    // The v3.4.6.f build FAKED dynamic color: `dynamicColor` on only nudged the
    // seed to a hardcoded #6750A4, so toggling it changed almost nothing. The
    // whole app is now wrapped in dynamic_color's DynamicColorBuilder: on
    // Android 12–16 it hands back the REAL wallpaper-extracted ColorSchemes
    // (light + dark), which are painted across primary/primaryContainer/
    // secondary/tertiary/surface/surfaceContainerLow…Highest/nav pill/cards/
    // sliders/switches/outlines — under Dark / AMOLED / Light alike. When the
    // platform answers null (Android ≤ 11, desktop, tests) the preset seed is
    // used and Settings shows the honest "not available on this device" note.
    return DynamicColorBuilder(
      builder: (ColorScheme? lightDynamic, ColorScheme? darkDynamic) {
        // publish for Settings (the honest availability note + preview swatch)
        AppDynamicColor.current.value = darkDynamic ?? lightDynamic;
        final ThemeData light = _themeFor(
          s, Brightness.light, accent, override, clarity, outlines,
          amoled: false,
          dynamic: lightDynamic,
        );
        final ThemeData dark = _themeFor(
          s, Brightness.dark, accent, override, clarity, outlines,
          amoled: amoled,
          dynamic: darkDynamic,
        );
        return MaterialApp(
          title: 'AnantPlay Player',
          debugShowCheckedModeBanner: false,
          themeMode: mode,
          theme: light,
          darkTheme: dark,
          home: const ShellRoot(),
        );
      },
    );
  }

  static ThemeData _themeFor(
    SettingsMirror s,
    Brightness brightness,
    MornyeAccent accent,
    Color? override,
    double clarity,
    bool outlines, {
    required bool amoled,
    ColorScheme? dynamic,
  }) {
    final String? font = switch (s.s(SettingsMirror.kUiFont, '')) {
      '' => null,
      final f => f,
    };
    final ThemeData base = MornyeTheme.build(
      brightness,
      accent: accent,
      fontFamily: font,
      glassClarity: clarity,
      glassOutlines: outlines,
    );
    ColorScheme scheme = base.colorScheme;
    // v3.4.6.g — THE REAL WALLPAPER SCHEME WINS when Material You is ON: every
    // role the shell paints with comes from the platform's dynamic palette. A
    // custom #RRGGBB accent still overrides the accent roles (the user asked
    // for that colour explicitly).
    final bool you = s.b(SettingsMirror.kDynamicColor, false);
    if (you && dynamic != null) {
      scheme = dynamic.copyWith(
        brightness: brightness,
        onPrimary: dynamic.onPrimary,
        primaryContainer: dynamic.primaryContainer,
        secondary: dynamic.secondary,
        secondaryContainer: dynamic.secondaryContainer,
        tertiary: dynamic.tertiary,
        tertiaryContainer: dynamic.tertiaryContainer,
        surface: dynamic.surface,
        surfaceContainerLowest: dynamic.surfaceContainerLowest,
        surfaceContainerLow: dynamic.surfaceContainerLow,
        surfaceContainer: dynamic.surfaceContainer,
        surfaceContainerHigh: dynamic.surfaceContainerHigh,
        surfaceContainerHighest: dynamic.surfaceContainerHighest,
        outline: dynamic.outline,
        outlineVariant: dynamic.outlineVariant,
        inverseSurface: dynamic.inverseSurface,
      );
    }
    if (override != null) {
      scheme = scheme.copyWith(
        primary: override,
        onPrimary: override.computeLuminance() < 0.179
            ? Colors.white
            : Colors.black,
        secondary: override,
        onSecondary: Colors.white,
      );
    }
    if (amoled && brightness == Brightness.dark) {
      scheme = scheme.copyWith(
        surface: Colors.black,
        surfaceContainerLowest: Colors.black,
        surfaceContainerLow: const Color(0xFF080808),
        surfaceContainer: const Color(0xFF101010),
        surfaceContainerHigh: const Color(0xFF161616),
        surfaceContainerHighest: const Color(0xFF1C1C1C),
      );
    }
    if (s.b(SettingsMirror.kMidnightDimmer, false)) {
      final double dim = (s.i(SettingsMirror.kMidnightDimPct, 30) / 100.0)
          .clamp(0.10, 0.70);
      scheme = scheme.copyWith(
        surface: Color.lerp(scheme.surface, Colors.black, dim) ?? scheme.surface,
        surfaceContainerLowest:
            Color.lerp(scheme.surfaceContainerLowest, Colors.black, dim) ??
                scheme.surfaceContainerLowest,
      );
    }
    final AppTokens tokens = base.extension<AppTokens>() ?? AppTokens.standard;
    final String density = s.s(SettingsMirror.kDensity, 'comfortable');
    final String radius = s.s('cardRadius', 'rounded');
    final bool motion = s.motionEnabled;
    final AppTokens liveTokens = tokens.copyWith(
      radiusCard: radius == 'sharp'
          ? 4
          : (radius == 'apple-pill' ? 30 : tokens.radiusCard),
      rowPaddingV: density == 'compact'
          ? 10
          : (density == 'spacious' ? 20 : tokens.rowPaddingV),
      rowPaddingVCompact: density == 'compact' ? 6 : tokens.rowPaddingVCompact,
      motionFast: motion ? tokens.motionFast : Duration.zero,
      motionMedium: motion ? tokens.motionMedium : Duration.zero,
      motionSlow: motion ? tokens.motionSlow : Duration.zero,
    );
    return base.copyWith(
      colorScheme: scheme,
      scaffoldBackgroundColor: scheme.surface,
      extensions: [
        for (final e in base.extensions.values)
          if (e is AppTokens) liveTokens else e,
      ],
    );
  }
}

/// One queued playback request (a cast, a sniffed stream, a shared URL —
/// anything the native layer or another entry point asks the shell to play).
class PlayRequest {
  const PlayRequest({
    required this.url,
    this.title,
    this.headers,
    this.test = false,
    this.resumeMs = 0,
  });

  final String url;
  final String? title;
  final String? headers;
  final bool test;
  final int resumeMs;
}

/// The shell root: first-launch (or upgrade) onboarding vs. the 4-tab shell.
///
/// v3.4.6.c — the tab bar is ALWAYS visible at the bottom (floating Mornye
/// glass capsule, safe-area padded `bottom + 12`), and every screen renders
/// inside the SAME Stack so switching tabs never rebuilds the world. The
/// onboarding gate is the VERSION int (an upgrader from v3.4.6.a sees the
/// 6-slide wizard exactly once), never the bare done-flag.
class ShellRoot extends StatefulWidget {
  const ShellRoot({super.key});

  @override
  State<ShellRoot> createState() => ShellRootState();
}

/// Public state handle: the native-side route pumps (initial intent, casts)
/// reach the shell through [ShellRootState.of].
class ShellRootState extends State<ShellRoot> {
  int _tab = 0;

  /// The in-flight player session (pushed as a full-screen route ABOVE the
  /// tab shell so the immersive HUD owns the whole display).
  PlayRequest? _activePlay;

  /// The URL the Browser tab should open first (an openWebTab handoff).
  String? _pendingWebUrl;

  /// v3.4.6.d — THE MANDATORY FORCE-UPDATE LOCK: true the moment a strictly
  /// newer release is known (gate:available). While true the ENTIRE viewport
  /// is ForceUpdateScreen — no tabs, no onboarding, no player, no Back.
  bool _forceUpdateLock = false;

  /// The last gate:available frame (current → next), handed to the
  /// ForceUpdateScreen so it renders armed even when IT subscribed after
  /// the event landed (the shell's listener fired first — exactly the cold
  /// handoff when a pending APK re-arms the gate at boot).
  UpdateGateEvent? _lastGate;

  /// v3.4.6.f — the REAL installed version (from the native appInfo method:
  /// never a hardcoded literal — v3.4.6.e baked '3.4.6.e' into this file and
  /// shipped it inside the .f build). Empty until the first appInfo reply.
  String _installedVersion = '';

  StreamSubscription<UpdateGateEvent>? _gateSub;

  static ShellRootState of(BuildContext context) =>
      context.findAncestorStateOfType<ShellRootState>()!;

  /// The four tabs, in bar order. `mornyeIconFor` remaps each glyph into the
  /// Mornye set at paint time (the vendored engine's own icon contract).
  static const _tabs = <(String, IconData)>[
    ('Home', Icons.home_rounded),
    ('Browser', Icons.public_rounded),
    ('Settings', Icons.settings_rounded),
    ('Help', Icons.shield_rounded),
  ];

  @override
  void initState() {
    super.initState();
    // v3.4.6.f — the REAL installed identity (the gate's `current` string and
    // the fallback below are read from native AppInfo — never a literal).
    AnantPlay.instance.appInfo().then((info) {
      if (!mounted) return;
      setState(() => _installedVersion = info.versionName);
    });
    // v3.4.6.d — THE MANDATORY GATE SUBSCRIPTION: the instant the host pushes
    // gate:available (a strictly newer release resolved through Tier-1/Tier-2,
    // zero api.github.com calls), the lock flips on and the whole app becomes
    // ForceUpdateScreen. `uptodate` (no update / the release vanished) is the
    // ONLY thing that unlocks it.
    // v3.4.6.f — LAW #1 IN DART: an `available` frame arms the lock ONLY when
    // `next` is STRICTLY newer than the installed build (isNewerVersion —
    // the Dart mirror of Java's UpdateChecker.isNewer). A v3.4.6.f → v3.4.6.f
    // frame (the v3.4.6.e self-lock) can NEVER flip the lock. NEVER `>=`.
    _gateSub = AnantPlay.instance.updateGateEvents().listen((e) {
      if (!mounted) return;
      final String installed =
          e.current.isNotEmpty ? e.current : _installedVersion;
      if (e.gate == 'available' && e.next.isNotEmpty) {
        if (!isNewerVersion(e.next, installed)) {
          // Same-version or older: the lock is mathematically impossible.
          if (_forceUpdateLock) setState(() => _forceUpdateLock = false);
          return;
        }
        setState(() {
          _lastGate = e;
          _forceUpdateLock = true;
        });
      } else if (e.gate == 'uptodate') {
        if (_forceUpdateLock) {
          setState(() => _forceUpdateLock = false);
        }
      }
    });
    // The launch/upgrade onboarding gate: v3.4.6.a wrote onboardingDone=true
    // with NO version stamp, so upgraders were silently skipped — the exact
    // complaint #4. K_ONBOARDING_VERSION (default 1 for a legacy install,
    // 0 for a fresh one) < 2 shows the 6-slide interactive wizard ONCE.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      final state = AppState.of(context);
      if (!state.settings.onboardingCurrent) {
        state.scope.setOnboardingStage(0);
      }
    });
  }

  @override
  void dispose() {
    _gateSub?.cancel();
    super.dispose();
  }

  void selectTab(int index) {
    // A tap on the CURRENT tab from anywhere (the Home Browser tile, the
    // top-right Settings gear) lands here too.
    setState(() => _tab = index.clamp(0, 3));
  }

  /// Switch to the Browser tab on a URL (the Home tile + openWebTab pumps).
  void openBrowserTab([String url = '']) {
    setState(() {
      _pendingWebUrl = url.isEmpty ? null : url;
      _tab = 1;
    });
  }

  /// THE one player entry point: Test Player, Play URL, Continue Watching,
  /// browser-captured streams, and native casts all route here — the Mornye
  /// Cinema Player (PlayerScreen over Media3TextureBridge) opens IN the shell.
  void pushPlayer(PlayRequest request) {
    setState(() => _activePlay = request);
  }

  /// v3.4.6.g — THE GUIDE/TOUR CHIP: re-open the interactive wizard on demand.
  /// Rewinds the persisted stage to slide 1 and drops the seen-marker, so the
  /// shell renders OnboardingScreen again (the same gate as a first launch).
  Future<void> replayOnboarding() async {
    final scope = AppState.of(context).scope;
    await scope.scopeStageForReplay();
    await scope.setOnboardingStage(0);
    if (mounted) setState(() => _tab = 0);
  }

  /// The player route popped (back / PiP-less exit): clear the session.
  void popPlayer() {
    if (_activePlay == null) return;
    setState(() => _activePlay = null);
    // History moved under the player session — refresh the Home list.
    AppState.of(context).scope.refresh();
  }

  @override
  Widget build(BuildContext context) {
    final state = AppState.of(context);
    // ── v3.4.6.d — THE MANDATORY FORCE-UPDATE LOCK ──────────────────────────
    // While a strictly newer release is known, the ForceUpdateScreen IS the
    // app: no onboarding, no tabs, no player. PopScope(canPop: false) inside
    // blocks the hardware Back; there is NO skip/dismiss/later anywhere.
    if (_forceUpdateLock) {
      return ForceUpdateScreen(
        key: const ValueKey('force-update-gate'),
        // v3.4.6.f — the installed version the gate's badge shows: read from
        // the gate frame the host resolved (AppInfo.VERSION_NAME on the native
        // side), falling back to the native appInfo reply — NEVER a literal
        // (v3.4.6.e hardcoded '3.4.6.d', then .e left '3.4.6.e' behind).
        currentVersion: _lastGate?.current.isNotEmpty == true
            ? _lastGate!.current
            : (_installedVersion.isEmpty ? '…' : _installedVersion),
        initialGate: _lastGate,
      );
    }
    if (!state.settings.onboardingCurrent) {
      return const OnboardingScreen();
    }
    final scheme = Theme.of(context).colorScheme;
    final bottomInset = MediaQuery.paddingOf(context).bottom + 12;

    // The player session routes ABOVE the whole tab shell (immersive).
    if (_activePlay != null) {
      return PlayerScreen(
        key: ValueKey<String>('player-${_activePlay!.url}'),
        request: _activePlay!,
        onExit: popPlayer,
      );
    }

    // The four pages stay OFFSTAGE-alive (never disposed on a tab switch, so
    // the browser keeps its page and the scroll positions survive).
    // v3.4.6.d — TickerMode gates every OFFSTAGE tab: the ambient/looping
    // AnimationControllers inside a hidden tab stop ticking (the battery
    // drain fix — an offstage Home pulse no longer repaints at 60 fps while
    // the user watches a movie in the Browser tab).
    final pages = [
      TickerMode(enabled: _tab == 0, child: const HomeScreen()),
      TickerMode(enabled: _tab == 1, child: BrowserScreen(initialUrl: _pendingWebUrl)),
      TickerMode(enabled: _tab == 2, child: const SettingsScreen()),
      TickerMode(enabled: _tab == 3, child: const HelpScreen()),
    ];
    return Scaffold(
      backgroundColor: scheme.surface,
      body: Stack(
        children: [
          // The active page, padded above the floating bar.
          Positioned.fill(
            child: Padding(
              padding: EdgeInsets.only(bottom: bottomInset + 58),
              child: IndexedStack(index: _tab, children: pages),
            ),
          ),
          // ── THE ALWAYS-VISIBLE FLOATING MORNYE TAB BAR ────────────────────
          // v3.4.6.c hit-test contract: Positioned INSIDE the Stack (never
          // clipped, never overlapped), safe-area padded bottom + 12, and the
          // MornyeSelectionPill inside MornyeTabBar forwards taps through
          // its Listener (MornyeGlassPress stays OUT of the gesture arena —
          // verified in mornye_glass_press.dart: it uses raw Listener
          // callbacks, so a child InkWell/GestureDetector always wins).
          Positioned(
            left: 16,
            right: 16,
            bottom: bottomInset,
            child: MornyeTabBar(
              destinations: [
                for (final (label, icon) in _tabs)
                  NavigationDestination(
                    icon: Icon(mornyeIconFor(icon)),
                    label: label,
                  ),
              ],
              selectedIndex: _tab,
              onSelected: selectTab,
              blurEnabled: true,
            ),
          ),
        ],
      ),
    );
  }
}
