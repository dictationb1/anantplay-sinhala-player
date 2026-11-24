// AnantPlay Sinhala Player v3.4.6 — the app state: settings mirror + cast
// status + updater events, exposed as an InheritedWidget scope the screens
// consume (Mornye's own app uses Riverpod providers for this; the vendored
// engine reads settingsProvider — overridden here with this state).
import 'dart:async';

import 'package:flutter/widgets.dart';

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

  Future<void> putBool(String key, bool value) async {
    await AnantPlay.instance.putBool(key, value);
    await refresh();
  }

  Future<void> putInt(String key, int value) async {
    await AnantPlay.instance.putInt(key, value);
    await refresh();
  }

  Future<void> putString(String key, String value) async {
    await AnantPlay.instance.putString(key, value);
    await refresh();
  }

  /// v3.4.6 — the wizard stage is persisted as the user advances (a phone call
  /// or a kill mid-wizard resumes on the same step instead of restarting).
  Future<void> setOnboardingStage(int stage) async {
    await AnantPlay.instance.putInt(SettingsMirror.kOnboardingStage, stage);
    await refresh();
  }

  Future<void> completeOnboarding() async {
    await AnantPlay.instance.putBool(SettingsMirror.kOnboardingDone, true);
    await AnantPlay.instance.putInt(SettingsMirror.kOnboardingStage, 4);
    await refresh();
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
    _load();
    _castSub = AnantPlay.instance.castStatus().listen(_onCast);
    _updaterSub = AnantPlay.instance.updaterEvents().listen(_onUpdater);
  }

  Future<void> _load() async {
    final settings = await AnantPlay.instance.loadSettings();
    final history = await AnantPlay.instance.history();
    if (!mounted) return;
    _publish(settings, history);
  }

  void _publish(SettingsMirror settings, List<HistoryItem> history) {
    _notifier.publish(settings, _notifier.cast, _notifier.updater, history);
    // republish through setState too so this scope rebuilds the InheritedWidget
    setState(() {});
  }

  void _onCast(CastStatus s) {
    if (!mounted) return;
    _notifier.publish(_notifier.settings, s, _notifier.updater, _notifier.history);
    setState(() {});
  }

  void _onUpdater(UpdaterEvent e) {
    if (!mounted) return;
    _notifier.publish(_notifier.settings, _notifier.cast, e, _notifier.history);
    setState(() {});
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _castSub?.cancel();
    _updaterSub?.cancel();
    _notifier.dispose();
    super.dispose();
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
