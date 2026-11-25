// AnantPlay Sinhala Player v3.4.6 — the app state: settings mirror + cast
// status + updater events, exposed as an InheritedWidget scope the screens
// consume (Mornye's own app uses Riverpod providers for this; the vendored
// engine reads settingsProvider — overridden here with this state).
import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:material_ui/material_ui.dart' show ColorScheme;

import 'bridge.dart';
import 'settings_mirror.dart';

/// Notifies dependents when any slice changes.
class AppState extends InheritedNotifier<AppStateNotifier> {
  const AppState({
    super.key,
    required this.settings,
    required this.castStatus,
    required this.updaterEvent,
    required this.history,
    required super.notifier,
    required super.child,
  });

  final SettingsMirror settings;
  final CastStatus castStatus;
  final UpdaterEvent? updaterEvent;
  final List<HistoryItem> history;

  static AppState of(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<AppState>()!;

  /// The owning scope's data (put/refresh entry points for dependents).
  AppStateNotifier get scope => notifier!;

  @override
  bool updateShouldNotify(AppState oldWidget) =>
      settings != oldWidget.settings ||
      castStatus != oldWidget.castStatus ||
      updaterEvent != oldWidget.updaterEvent ||
      !identical(history, oldWidget.history);
}

/// The ChangeNotifier the InheritedNotifier listens to (State itself is not
/// a Listenable in this Flutter world — this is the Mornye-safe bridge).
/// Owns the mutation surface: every put* writes through the bridge and
/// republishes the refreshed snapshot.
class AppStateNotifier extends ChangeNotifier {
  SettingsMirror settings = AppStateDefaults.settings;
  CastStatus cast = const CastStatus(up: false, port: -1, discoverable: false, mdns: false);
  UpdaterEvent? updater;
  List<HistoryItem> history = const [];

  void publish(SettingsMirror newSettings, CastStatus newCast, UpdaterEvent? newUpdater,
      List<HistoryItem> newHistory) {
    settings = newSettings;
    cast = newCast;
    updater = newUpdater;
    history = newHistory;
    notifyListeners();
  }

  Future<void> refresh() async {
    final s = await AnantPlay.instance.loadSettings();
    final h = await AnantPlay.instance.history();
    publish(s, cast, updater, h);
  }

  /// v3.4.6.f — GAP #2: OPTIMISTIC writes. The mirror moves and listeners fire
  /// BEFORE the platform round-trip, so a switch snaps on the very next frame
  /// even on a slow/emulator MethodChannel. The channel still persists and
  /// refresh() reconciles with Java; a failed channel restores the old value
  /// instead of leaving the UI lying about what was saved.
  Future<void> putBool(String key, bool value) async =>
      _optimistic(key, value, () => AnantPlay.instance.putBool(key, value));

  Future<void> putInt(String key, int value) async =>
      _optimistic(key, value, () => AnantPlay.instance.putInt(key, value));

  Future<void> putString(String key, String value) async =>
      _optimistic(key, value, () => AnantPlay.instance.putString(key, value));

  Future<void> _optimistic(
      String key, Object? value, Future<void> Function() writer) async {
    final SettingsMirror previous = settings;
    settings = SettingsMirror.fromJavaMap(<String, Object?>{
      ...previous.raw,
      key: value,
    });
    notifyListeners();
    try {
      await writer();
    } catch (error) {
      settings = previous;
      notifyListeners();
      debugPrint('settings write failed ($key): $error');
      return;
    }
    await refresh();
  }

  /// v3.4.6 — the wizard stage is persisted as the user advances (a phone call
  /// or a kill mid-wizard resumes on the same step instead of restarting).
  /// v3.4.6.g — the Guide/Tour chip's entry: clear the "wizard seen" marker so
  /// [SettingsMirror.onboardingCurrent] is false again and the shell renders
  /// the interactive wizard. The persisted stage is reset by the caller.
  Future<void> scopeStageForReplay() async {
    await AnantPlay.instance
        .putInt(SettingsMirror.kOnboardingVersion, 0);
    await refresh();
  }

  Future<void> setOnboardingStage(int stage) async {
    await AnantPlay.instance.putInt(SettingsMirror.kOnboardingStage, stage);
    await refresh();
  }

  /// v3.4.6.c — THE UPGRADER'S WIZARD GATE: the version int decides whether
  /// the CURRENT 6-slide interactive wizard still owes the user a run. A
  /// v3.4.6.a install persisted onboardingDone=true with NO version stamp
  /// (version reads back as 1), so the v3.4.6.b bool gate silently skipped
  /// them — real-phone complaint #4. `onboardingCurrent` is false while
  /// version < 2, and completeOnboarding() stamps version 2 through the
  /// dedicated native writer so the wizard shows EXACTLY once per upgrade.
  bool get onboardingOwed => settings.onboardingVersion < 2;

  /// v3.4.6.c — "Run Setup Guide" (Settings & Help): re-arm the wizard from
  /// slide 1. The shell's gate flips because the version int drops below 2.
  Future<void> rerunOnboarding() async {
    await AnantPlay.instance.resetOnboarding();
    await refresh();
  }

  /// v3.4.6.b — THE UNBREAKABLE FINISH. Three guarantees the old path lacked:
  ///   1. The in-memory snapshot flips to onboardingDone = true SYNCHRONOUSLY
  ///      (a local overlay on the current settings map) and notifyListeners()
  ///      fires before any await — the shell animates into HomeScreen even if
  ///      a platform call is slow or the channel is briefly down;
  ///   2. Persistence rides the DEDICATED native `finishOnboarding` channel
  ///      (MainActivity → AppPrefs.setOnboardingDone) — a writer no allowlist
  ///      (BACKUP_KEYS filtering, normalizeValue) can ever drop;
  ///   3. The v3.4.6.b root cause is fixed at the source too: gatherSettings()
  ///      now returns onboardingDone/onboardingStage live, so even the
  ///      refreshed map the shell rebuilds from carries the flag.
  /// v3.4.6.c — the finish ALSO stamps K_ONBOARDING_VERSION = 2 (the same
  /// dedicated writer), so the upgrader's wizard never loops after Finish.
  void completeOnboarding() {
    // 1 — the synchronous local publish (the UI never waits on the channel).
    settings = SettingsMirror.fromJavaMap({
      ...settings.raw,
      SettingsMirror.kOnboardingDone: true,
      SettingsMirror.kOnboardingStage: 5,
      SettingsMirror.kOnboardingVersion: 2,
    });
    notifyListeners();
    // 2 — the dedicated persistence path (fire-and-forget is fine: step 1
    //     already moved the UI; this only makes it survive a restart).
    AnantPlay.instance.finishOnboarding().catchError((Object e) {
      // The channel hiccupped — retry once through the generic setter so the
      // flag still lands in SharedPreferences before next relaunch.
      return AnantPlay.instance
          .putBool(SettingsMirror.kOnboardingDone, true)
          .catchError((Object _) {});
    });
  }

  /// v3.4.6 — the Home quick-start card is a one-time surface: this is where it
  /// retires itself (K_QUICKSTART_DONE, a backed-up preference).
  Future<void> dismissQuickstart() async {
    await AnantPlay.instance.putBool(SettingsMirror.kQuickstartDone, true);
    await refresh();
  }

  Future<void> removeHistory(String key) async {
    await AnantPlay.instance.removeHistory(key);
    await refresh();
  }
}

/// The pre-load defaults (a neutral dark glass surface).
class AppStateDefaults {
  static final SettingsMirror settings =
      SettingsMirror.fromJavaMap(const <String, Object>{});
}

/// The state notifier: loads settings, subscribes to the event channels,
/// and republishes the immutable snapshot on every change.
class AppStateScope extends StatefulWidget {
  const AppStateScope({super.key, required this.child});

  final Widget child;

  @override
  State<AppStateScope> createState() => AppStateScopeData();
}

class AppStateScopeData extends State<AppStateScope> with WidgetsBindingObserver {
  final AppStateNotifier _notifier = AppStateNotifier();
  StreamSubscription<CastStatus>? _castSub;
  StreamSubscription<UpdaterEvent>? _updaterSub;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    // v3.4.6.b — THE OTHER HALF OF THE FINISH BUG: completeOnboarding's
    // synchronous notifyListeners() only reaches dependents if this scope
    // ALSO rebuilds its AppState wrapper (the InheritedNotifier's child
    // widget still carries the OLD settings map otherwise — exactly why a
    // Finish tap in v3.4.6.b never swapped OnboardingScreen for HomeScreen
    // even though the pref had persisted). The scope now listens to its own
    // notifier and republishes through setState on every mutation.
    _notifier.addListener(_republish);
    _load();
    _castSub = AnantPlay.instance.castStatus().listen(_onCast);
    _updaterSub = AnantPlay.instance.updaterEvents().listen(_onUpdater);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _notifier.removeListener(_republish);
    _castSub?.cancel();
    _updaterSub?.cancel();
    _notifier.dispose();
    super.dispose();
  }

  /// Any notifier mutation (completeOnboarding's synchronous flip included)
  /// rebuilds the AppState wrapper this frame — ShellRoot re-reads
  /// onboardingDone and swaps OnboardingScreen → HomeScreen immediately.
  void _republish() {
    if (mounted) setState(() {});
  }

  Future<void> _load() async {
    final settings = await AnantPlay.instance.loadSettings();
    final history = await AnantPlay.instance.history();
    if (!mounted) return;
    _notifier.publish(settings, _notifier.cast, _notifier.updater, history);
    setState(() {});
  }

  void _onCast(CastStatus s) {
    if (!mounted) return;
    // publish → notifyListeners → _republish → setState (the one path).
    _notifier.publish(_notifier.settings, s, _notifier.updater, _notifier.history);
  }

  void _onUpdater(UpdaterEvent e) {
    if (!mounted) return;
    _notifier.publish(_notifier.settings, _notifier.cast, e, _notifier.history);
    // _republish already fires through the notifier listener — no direct
    // setState here (a double setState in one frame throws).
  }

  @override
  Widget build(BuildContext context) {
    return AppState(
      settings: _notifier.settings,
      castStatus: _notifier.cast,
      updaterEvent: _notifier.updater,
      history: _notifier.history,
      notifier: _notifier,
      child: widget.child,
    );
  }
}

/// v3.4.6.g — THE LIVE MATERIAL YOU PALETTE.
///
/// `DynamicColorBuilder` (main.dart) resolves the wallpaper-extracted colour
/// scheme on Android 12–16 and publishes it here. Two consumers:
///   · the app's themed root paints the whole UI from it when `dynamicColor`
///     is ON;
///   · Settings reads it for the honest "available / not available" subtitle
///     and the live preview swatch — never a faked Material You.
class AppDynamicColor {
  AppDynamicColor._();

  /// The live wallpaper scheme (null = platform does not support it).
  static final ValueNotifier<ColorScheme?> current =
      ValueNotifier<ColorScheme?>(null);
}
