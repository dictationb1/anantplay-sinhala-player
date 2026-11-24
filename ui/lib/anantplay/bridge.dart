// AnantPlay Sinhala Player v3.4.6 — the Dart side of the 8-channel bridge.
//
// Every call maps 1:1 onto the Java host (MainActivity.configureFlutterEngine):
//   anantplay/cast, /player, /subs, /browser, /settings, /history, /updater,
//   /troubleshoot (+ the two EventChannels: cast status, updater progress).
// The streaming/subtitle workhorse stays 100% native (PlayActivity); this
// surface is the Mornye shell's window onto it.
import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'settings_mirror.dart';

const MethodChannel _cast = MethodChannel('anantplay/cast');
const MethodChannel _player = MethodChannel('anantplay/player');
const MethodChannel _subs = MethodChannel('anantplay/subs');
const MethodChannel _browser = MethodChannel('anantplay/browser');
const MethodChannel _settings = MethodChannel('anantplay/settings');
const MethodChannel _history = MethodChannel('anantplay/history');
const MethodChannel _updater = MethodChannel('anantplay/updater');
const MethodChannel _trouble = MethodChannel('anantplay/troubleshoot');

const EventChannel _castEvents = EventChannel('anantplay/cast/events');
const EventChannel _updaterEvents = EventChannel('anantplay/updater/events');
const EventChannel _troubleEvents = EventChannel('anantplay/troubleshoot/events');

/// The live receiver status (anantplay/cast).
class CastStatus {
  const CastStatus({
    required this.up,
    required this.port,
    required this.discoverable,
    required this.mdns,
  });

  final bool up;
  final int port;
  final bool discoverable;
  final bool mdns;

  static CastStatus fromMap(Object? map) {
    if (map is! Map) {
      return const CastStatus(up: false, port: -1, discoverable: false, mdns: false);
    }
    return CastStatus(
      up: map['up'] == true,
      port: (map['port'] as num?)?.toInt() ?? -1,
      discoverable: map['discoverable'] == true,
      mdns: map['mdns'] == true,
    );
  }
}

/// v3.4.6.b — one frame of the onboarding permission/install snapshot the
/// host pushes on every resume (and on demand): overlay · notif · battery ·
/// unknown-sources · official-app presence, so the wizard's auto-advance and
/// every Enabled ✓ pill resolve from live native state.
class OnboardingStatus {
  const OnboardingStatus({
    required this.overlay,
    required this.notif,
    required this.battery,
    required this.installs,
    required this.official,
  });

  final bool overlay;
  final bool notif;
  final bool battery;
  final bool installs;
  final bool official;

  static OnboardingStatus get unknown => const OnboardingStatus(
        overlay: false, notif: false, battery: false, installs: false, official: false);

  static OnboardingStatus fromMap(Object? map) {
    if (map is! Map || map['t'] != 'onboardingStatus') return OnboardingStatus.unknown;
    return OnboardingStatus(
      overlay: map['overlay'] == true,
      notif: map['notif'] == true,
      battery: map['battery'] == true,
      installs: map['installs'] == true,
      official: map['official'] == true,
    );
  }
}

/// v3.4.6.b — the in-app official-AnantPlay installer stream (Slide 2):
/// live download percent (`installProgress`, v = 0..100) and pipeline state
/// (`installState`, v = resolving/needs-permission/confirm/…).
class InstallEvent {
  const InstallEvent({required this.type, required this.value, required this.extra});

  factory InstallEvent.fromMap(Map<dynamic, dynamic> map) => InstallEvent(
        type: (map['t'] as String?) ?? '',
        value: (map['v'] as String?) ?? '',
        extra: (map['x'] as String?) ?? '',
      );

  final String type;
  final String value;
  final String extra;

  int get percent => int.tryParse(value) ?? 0;
}

/// One continue-watching row (WatchHistory.Item, JSON-decoded).
class HistoryItem {
  const HistoryItem({
    required this.title,
    required this.url,
    required this.source,
    required this.pos,
    required this.dur,
    required this.at,
    required this.percent,
    required this.key,
    required this.ephemeralRelay,
  });

  factory HistoryItem.fromMap(Map<dynamic, dynamic> map) {
    final pos = (map['pos'] as num?)?.toDouble() ?? 0;
    final dur = (map['dur'] as num?)?.toDouble() ?? 0;
    return HistoryItem(
      title: (map['title'] as String?) ?? '',
      url: (map['url'] as String?) ?? '',
      source: (map['source'] as String?) ?? '',
      pos: pos,
      dur: dur,
      at: (map['at'] as num?)?.toDouble() ?? 0,
      percent: dur <= 0 ? 0 : ((pos / dur) * 100).round().clamp(0, 100),
      key: (map['key'] as String?) ?? '',
      ephemeralRelay: map['ephemeralRelay'] == true,
    );
  }

  final String title;
  final String url;
  final String source;
  final double pos;
  final double dur;
  final double at;
  final int percent;
  final String key;
  final bool ephemeralRelay;

  bool get resumable => pos > 0 && (dur <= 0 || pos < dur - 5000);
}

/// A single probe outcome (FeatureProbe.Outcome).
class ProbeOutcome {
  const ProbeOutcome({
    required this.id,
    required this.name,
    required this.pass,
    required this.ms,
    required this.detail,
  });

  factory ProbeOutcome.fromMap(Map<dynamic, dynamic> map) => ProbeOutcome(
        id: (map['id'] as num?)?.toInt() ?? 0,
        name: (map['name'] as String?) ?? '',
        pass: map['pass'] == true,
        ms: (map['ms'] as num?)?.toDouble() ?? 0,
        detail: (map['detail'] as String?) ?? '',
      );

  final int id;
  final String name;
  final bool pass;
  final double ms;
  final String detail;
}

/// A live updater event (anantplay/updater/events).
class UpdaterEvent {
  const UpdaterEvent({required this.type, required this.value});

  factory UpdaterEvent.fromMap(Map<dynamic, dynamic> map) => UpdaterEvent(
        type: (map['t'] as String?) ?? 'state',
        value: (map['v'] as String?) ?? '',
      );

  final String type;   // available | progress | state | pending
  final String value;
}

// ── anantplay/cast ─────────────────────────────────────────────────────────

/// The AnantPlay native bridge surface (one singleton per app).
class AnantPlay {
  AnantPlay._();
  static final AnantPlay instance = AnantPlay._();

  Stream<CastStatus>? _castStream;
  Stream<UpdaterEvent>? _updaterStream;
  Stream<List<ProbeOutcome>>? _probeStream;
  Stream<OnboardingStatus>? _onboardingStream;
  Stream<InstallEvent>? _installStream;

  /// Test seam: drops the cached broadcast streams so a fresh scope can listen.
  @visibleForTesting
  void resetStreams() {
    _castStream = null;
    _updaterStream = null;
    _probeStream = null;
    _onboardingStream = null;
    _installStream = null;
  }

  /// The live receiver status stream (pushed on every change + resume).
  Stream<CastStatus> castStatus() {
    return _castStream ??= _castEvents
        .receiveBroadcastStream()
        .map(CastStatus.fromMap)
        .startWith(currentCastStatus());
  }

  /// The live updater event stream (glass banner's data source).
  Stream<UpdaterEvent> updaterEvents() {
    return _updaterStream ??= _updaterEvents
        .receiveBroadcastStream()
        .map((e) => UpdaterEvent.fromMap(e as Map));
  }

  /// The probe-completion stream (10/10 outcomes, pushed when a run ends).
  Stream<List<ProbeOutcome>> probeResults() {
    return _probeStream ??= _troubleEvents
        .receiveBroadcastStream()
        .map((e) => _decodeOutcomes(e));
  }

  static List<ProbeOutcome> _decodeOutcomes(Object? event) {
    if (event is String) {
      return const [];
    }
    if (event is! List) return const [];
    return event
        .whereType<Map>()
        .map((m) => ProbeOutcome.fromMap(m))
        .toList(growable: false);
  }

  Future<CastStatus> currentCastStatus() async =>
      CastStatus.fromMap(await _cast.invokeMethod('status'));

  Future<void> armReceiver() => _cast.invokeMethod('arm');
  Future<void> stopReceiver() => _cast.invokeMethod('stop');
  Future<bool> overlayGranted() async => await _cast.invokeMethod('overlayGranted');
  Future<void> openOverlaySettings() => _cast.invokeMethod('openOverlay');
  Future<bool> notificationsGranted() async => await _cast.invokeMethod('notifGranted');
  Future<void> requestBatteryExemption() => _cast.invokeMethod('requestBattery');
  Future<bool> batteryUnrestricted() async => await _cast.invokeMethod('batteryUnrestricted');
  Future<String> autostartState() async => await _cast.invokeMethod('autostartState');
  Future<void> confirmAutostart() => _cast.invokeMethod('confirmAutostart');
  Future<void> openAutostartSettings() => _cast.invokeMethod('openAutostart');
  Future<void> openAppDetails() => _cast.invokeMethod('openAppDetails');
  Future<bool> officialInstalled() async => await _cast.invokeMethod('officialInstalled');
  Future<void> openOfficialApp() => _cast.invokeMethod('openOfficial');
  Future<void> runTestPlayer() => _cast.invokeMethod('testPlayer');

  // ── v3.4.6.b — the unbreakable onboarding finish + in-app installer ─────────

  /// THE dedicated persistence path: K_ONBOARDING_DONE = true through the
  /// native `finishOnboarding` handler (AppPrefs.setOnboardingDone — a writer
  /// no allowlist ever filters). Never silently dropped, never normalized away.
  Future<void> finishOnboarding() => _cast.invokeMethod('finishOnboarding');

  /// The live permission/install snapshot (overlay · notif · battery ·
  /// installs · official app) — one native round-trip for the whole wizard.
  Future<OnboardingStatus> onboardingStatus() async =>
      OnboardingStatus.fromMap(await _cast.invokeMethod('onboardingStatus'));

  /// The onboardingStatus stream: the host pushes a fresh frame on every
  /// resume, so auto-advance and every Enabled ✓ pill reflect live state.
  Stream<OnboardingStatus> onboardingStatusEvents() {
    return _onboardingStream ??= _castEvents
        .receiveBroadcastStream()
        .map(OnboardingStatus.fromMap)
        .where((s) => s != OnboardingStatus.unknown);
  }

  /// Slide 2's installer stream (installProgress/installState frames).
  Stream<InstallEvent> installEvents() {
    return _installStream ??= _castEvents
        .receiveBroadcastStream()
        .map((e) => e is Map ? InstallEvent.fromMap(e) : null)
        .where((e) => e != null)
        .cast<InstallEvent>()
        .where((e) => e.type == 'installProgress' || e.type == 'installState');
  }

  Future<bool> canRequestPackageInstalls() async =>
      await _cast.invokeMethod('canRequestPackageInstalls');

  Future<void> openUnknownSources() => _cast.invokeMethod('openUnknownSources');

  /// Slide 2's Install button: resolve the official latest release → live
  /// percent → Android's 1-tap PackageInstaller sheet.
  Future<void> installOfficialAnantPlay() =>
      _cast.invokeMethod('installOfficialAnantPlay');

  Future<String> officialVersion() async =>
      await _cast.invokeMethod('officialVersion');

  /// Slide 5's universal background stability entry (OEM-aware page).
  Future<void> openBatteryOptimization() => _cast.invokeMethod('openBatteryOptimization');

  /// Slide 6's 1-tap copy (Sources & Index Helper).
  Future<void> copyToClipboard(String text) =>
      _cast.invokeMethod('copyToClipboard', {'text': text});

  /// The editable Saved Index URL box (backed-up pref, returns the stored value).
  Future<String> saveIndexUrl(String url) async =>
      await _cast.invokeMethod('saveIndexUrl', {'url': url});

  // ── anantplay/player ─────────────────────────────────────────────────────

  /// Hand a URL (with optional headers/title) to the native Media3 engine.
  Future<void> playUrl(String url, {String? headers, String? title, bool test = false}) =>
      _player.invokeMethod('open', {
        if (url.isNotEmpty) 'url': url,
        if (headers != null && headers.isNotEmpty) 'headers': headers,
        if (title != null && title.isNotEmpty) 'title': title,
        if (test) 'test': true,
      });

  /// The live player status snapshot (the cast server's /status JSON).
  Future<String> playerState() async => await _player.invokeMethod('state');

  // ── anantplay/subs + browser ─────────────────────────────────────────────

  Future<void> openPlayerWithSubs(String url, {String? title}) =>
      _subs.invokeMethod('openPlayerSearch', {
        if (url.isNotEmpty) 'url': url,
        if (title != null && title.isNotEmpty) 'title': title,
      });

  Future<void> playCapturedStream(String url, {String? headers, String? title}) =>
      _browser.invokeMethod('openInPlayer', {
        'url': url,
        if (headers != null && headers.isNotEmpty) 'headers': headers,
        if (title != null && title.isNotEmpty) 'title': title,
      });

  Future<void> openWebTab(String url) =>
      _browser.invokeMethod('openWebTab', {'url': url});

  // ── anantplay/settings ───────────────────────────────────────────────────

  Future<SettingsMirror> loadSettings() async {
    final map = await _settings.invokeMethod('getAll');
    return SettingsMirror.fromJavaMap(map);
  }

  Future<void> putBool(String key, bool value) =>
      _settings.invokeMethod('putBool', {'key': key, 'value': value});

  Future<void> putInt(String key, int value) =>
      _settings.invokeMethod('putInt', {'key': key, 'value': value});

  Future<void> putString(String key, String value) =>
      _settings.invokeMethod('putString', {'key': key, 'value': value});

  Future<bool> getBool(String key, {bool def = false}) async =>
      await _settings.invokeMethod('getBool', {'key': key, 'def': def});

  Future<String> exportBackup() async => await _settings.invokeMethod('exportBackup');

  Future<ImportPreview> importPreview(String json) async =>
      ImportPreview.fromMap(await _settings.invokeMethod('importPreview', {'json': json}));

  Future<void> resetAll() => _settings.invokeMethod('resetAll');

  // ── anantplay/history ─────────────────────────────────────────────────────

  Future<List<HistoryItem>> history({int limit = 20}) async {
    final json = await _history.invokeMethod('list', {'limit': limit}) as String;
    final list = jsonDecode(json) as List<dynamic>;
    return list
        .whereType<Map>()
        .map(HistoryItem.fromMap)
        .toList(growable: false);
  }

  Future<void> removeHistory(String key) =>
      _history.invokeMethod('remove', {'key': key});

  // ── anantplay/updater ─────────────────────────────────────────────────────

  Future<void> checkForUpdate() => _updater.invokeMethod('checkNow');
  Future<void> installPendingUpdate() => _updater.invokeMethod('updateNow');

  Future<({bool pending, String version})> pendingUpdate() async {
    final m = await _updater.invokeMethod('pending') as Map;
    return (
      pending: m['pending'] == true,
      version: (m['version'] as String?) ?? '',
    );
  }

  // ── anantplay/troubleshoot ────────────────────────────────────────────────

  Future<void> runProbe() => _trouble.invokeMethod('runProbe');

  Future<List<ProbeOutcome>> lastProbeResults() async {
    final raw = await _trouble.invokeMethod('lastResults');
    return _decodeOutcomes(raw);
  }
}

extension<T> on Stream<T> {
  Stream<T> startWith(Future<T> first) async* {
    yield await first;
    yield* this;
  }
}
