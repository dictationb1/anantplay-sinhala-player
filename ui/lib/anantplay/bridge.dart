// AnantPlay Sinhala Player v3.4.6.f — the Dart side of the 8-channel bridge.
//
// Every call maps 1:1 onto the Java host (MainActivity.configureFlutterEngine):
//   anantplay/cast, /player, /subs, /browser, /settings, /history, /updater,
//   /troubleshoot (+ the five EventChannels: cast status, updater progress,
//   player frames, troubleshoot events, shell routes).
// The streaming/subtitle workhorse stays 100% native (PlayActivity); this
// surface is the Mornye shell's window onto it.
//
// ── v3.4.6.f — THE SINGLETON BROADCAST HUB (the `No active stream to cancel`
// killer) ────────────────────────────────────────────────────────────────────
// v3.4.6.e called EventChannel(...).receiveBroadcastStream() SEPARATELY for
// every feature stream — FOUR parallel products on 'anantplay/cast/events'
// alone (cast status, officialVersion, onboardingStatus, install events) and
// TWO on 'anantplay/updater/events' (updater banner + the force-update gate).
// Each extra product REPLACED the binary messenger's message handler for that
// channel name when its first listener attached, so whichever stream listened
// LAST silently stole every event (the Home receiver card went dead), and a
// stale product's cancel landed on a native channel with no active stream —
// the exact `PlatformException(error, No active stream to cancel, null, null)`
// the v3.4.6.e CrashLog captured, forwarded by the Dart crash reporter.
//
// THE LAW: one channel name = ONE `_ChannelHub` = ONE lazy native
// subscription for the whole process lifetime, feeding ONE broadcast
// StreamController that unlimited Dart consumers subscribe to. The native
// side sees at most one onListen and ZERO cancels; every widget gets every
// event; `hubStats()` counts the listens/cancels/errors for the honest
// troubleshooter (Layer 1, bridge probe).
import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'settings_mirror.dart';
import 'deep_troubleshooter.dart';

const MethodChannel _cast = MethodChannel('anantplay/cast');
const MethodChannel _player = MethodChannel('anantplay/player');
const MethodChannel _subs = MethodChannel('anantplay/subs');
const MethodChannel _browser = MethodChannel('anantplay/browser');
const MethodChannel _settings = MethodChannel('anantplay/settings');
const MethodChannel _history = MethodChannel('anantplay/history');
const MethodChannel _updater = MethodChannel('anantplay/updater');
const MethodChannel _trouble = MethodChannel('anantplay/troubleshoot');

// ── v3.4.6.f — THE SINGLETON BROADCAST HUB ─────────────────────────────────

/// One EventChannel name → ONE native subscription → ONE broadcast stream,
/// for the entire app lifetime (never canceled, never duplicated).
class _ChannelHub {
  _ChannelHub._(this.name);

  static final Map<String, _ChannelHub> _hubs = <String, _ChannelHub>{};

  /// The one hub for a channel name (created on first use).
  static _ChannelHub of(String name) =>
      _hubs.putIfAbsent(name, () => _ChannelHub._(name));

  /// Test seam: dispose every hub so a fresh scope can start clean.
  @visibleForTesting
  static void resetAll() {
    for (final h in _hubs.values.toList(growable: false)) {
      h._dispose();
    }
    _hubs.clear();
  }

  final String name;
  StreamController<dynamic>? _controller;
  StreamSubscription<dynamic>? _nativeSub;
  bool _started = false;

  // v3.4.6.f — the honest audit counters (Layer 1 bridge probe reads these).
  int nativeListens = 0;
  int nativeCancels = 0;
  int streamErrors = 0;

  /// The broadcast stream every consumer subscribes to. The FIRST subscriber
  /// starts the ONE native listen; later subscribers attach to the same
  /// controller; the last unsubscribe leaves the native stream alive (at most
  /// one onListen per channel for the app lifetime — zero cancel storms).
  Stream<dynamic> get stream {
    final c = _controller ??=
        StreamController<dynamic>.broadcast(onListen: _startNative);
    return c.stream;
  }

  void _startNative() {
    if (_started) return;
    _started = true;
    nativeListens++;
    _nativeSub = EventChannel(name).receiveBroadcastStream().listen(
          (dynamic event) => _controller?.add(event),
          onError: (Object error) {
            // Counted, NEVER an uncaught zone error (the v3.4.6.e crash was
            // exactly an unhandled PlatformException here).
            streamErrors++;
            debugPrint('bridge hub $name stream error ($streamErrors): $error');
          },
          cancelOnError: false,
        );
  }

  void _dispose() {
    _started = false;
    final sub = _nativeSub;
    _nativeSub = null;
    // A dispose (test reset only) is the single legitimate cancel path.
    unawaited(sub?.cancel().then((_) {}, onError: (Object _) {}));
    nativeCancels++;
    unawaited(_controller?.close());
    _controller = null;
  }
}

/// v3.4.6.f — the honest hub health snapshot (Layer 1 of the deep
/// troubleshooter): the native listen count per channel and any error that
/// ever crossed a hub (a healthy bridge answers `listens <= 1, errors == 0`).
class BridgeHubStats {
  const BridgeHubStats({
    required this.channel,
    required this.nativeListens,
    required this.nativeCancels,
    required this.streamErrors,
  });

  final String channel;
  final int nativeListens;
  final int nativeCancels;
  final int streamErrors;

  @override
  String toString() =>
      '$channel listens=$nativeListens cancels=$nativeCancels errors=$streamErrors';
}

/// v3.4.6.f — the installed build identity (the REAL one, from the native
/// side; never a hardcoded literal again).
class AppBuildInfo {
  const AppBuildInfo({required this.versionName, required this.versionCode});

  final String versionName;
  final int versionCode;

  static AppBuildInfo fromMap(Object? map) {
    final m = map is Map ? map : const <dynamic, dynamic>{};
    return AppBuildInfo(
      versionName: (m['versionName'] as String?) ?? '',
      versionCode: (m['versionCode'] as num?)?.toInt() ?? 0,
    );
  }
}

/// v3.4.6.f — the DART MIRROR of UpdateChecker.isNewer (Java): strictly-newer
/// four-segment comparison with the whole a–z alphabetical lane, so the Dart
/// gate (ShellRoot + ForceUpdateScreen) enforces LAW #1 identically:
/// 3.4.6 < 3.4.6.a < … < 3.4.6.e < 3.4.6.f < … < 3.4.6.z < 3.4.7.
/// `v3.4.6.f → v3.4.6.f` answers FALSE — the self-lock is impossible here.
bool isNewerVersion(String? candidate, String? current) {
  final List<int> a = _parseVersion(candidate);
  final List<int> b = _parseVersion(current);
  for (int i = 0; i < 4; i++) {
    if (a[i] != b[i]) return a[i] > b[i];
  }
  return false;
}

/// The 4-segment parse with the a→z lane (1..26), junk-tolerant.
List<int> _parseVersion(String? v) {
  final out = [0, 0, 0, 0];
  if (v == null) return out;
  final parts = v.split(RegExp(r'[.\-+]'));
  for (var i = 0; i < 4 && i < parts.length; i++) {
    final p = parts[i];
    if (i == 3) {
      if (p.length == 1) {
        final c = p.toLowerCase();
        if (c.codeUnitAt(0) >= 'a'.codeUnitAt(0) &&
            c.codeUnitAt(0) <= 'z'.codeUnitAt(0)) {
          out[3] = c.codeUnitAt(0) - 'a'.codeUnitAt(0) + 1;
        }
      }
      continue;
    }
    final digits = p.replaceAll(RegExp(r'[^0-9]'), '');
    if (digits.isEmpty) continue;
    out[i] = int.tryParse(digits) ?? 0;
  }
  return out;
}

// ── the typed event payloads (unchanged shapes) ─────────────────────────────

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

  @override
  bool operator ==(Object other) =>
      other is CastStatus &&
      other.up == up &&
      other.port == port &&
      other.discoverable == discoverable &&
      other.mdns == mdns;

  @override
  int get hashCode => Object.hash(up, port, discoverable, mdns);
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

  final String type;   // available | progress | state | pending | gate
  final String value;
}

/// v3.4.6.d — one frame of the mandatory force-update gate state the host
/// pushes on the anantplay/updater EventChannel: the available blob carries
/// the current → new version pair, and the live legs (progress / verify /
/// installer / failed / uptodate) drive the ForceUpdateScreen.
class UpdateGateEvent {
  const UpdateGateEvent({
    required this.gate,
    this.current = '',
    this.next = '',
    this.percent = 0,
    this.bytes = 0,
    this.total = 0,
    this.reason = '',
  });

  factory UpdateGateEvent.fromMap(Map<dynamic, dynamic> map) => UpdateGateEvent(
        gate: (map['gate'] as String?) ?? '',
        current: (map['current'] as String?) ?? '',
        next: (map['next'] as String?) ?? '',
        percent: (map['percent'] as num?)?.toInt() ?? 0,
        bytes: (map['bytes'] as num?)?.toInt() ?? 0,
        total: (map['total'] as num?)?.toInt() ?? 0,
        reason: (map['reason'] as String?) ?? '',
      );

  final String gate; // available | progress | verify | installer | failed | uptodate
  final String current;
  final String next;
  final int percent;
  final int bytes;
  final int total;
  final String reason;
}

/// v3.4.6.d — the official AnantPlay live version pill (Slide 2): installed
/// state + the exact installed version name, pushed fresh on every resume.
class OfficialVersionEvent {
  const OfficialVersionEvent({required this.installed, required this.version});

  factory OfficialVersionEvent.fromMap(Map<dynamic, dynamic> map) =>
      OfficialVersionEvent(
        installed: map['installed'] == true,
        version: (map['version'] as String?) ?? '',
      );

  final bool installed;
  final String version;
}

/// v3.4.6.c — the native texture session Media3TextureBridge.open returns:
/// the live textureId (a Flutter Texture renders it) + the first video
/// geometry (updated live on the frames stream).
class PlayerSession {
  const PlayerSession({
    required this.textureId,
    required this.videoWidth,
    required this.videoHeight,
  });

  factory PlayerSession.fromMap(Object? map) {
    final m = map is Map ? map : const <dynamic, dynamic>{};
    return PlayerSession(
      textureId: (m['textureId'] as num?)?.toInt() ?? -1,
      videoWidth: (m['videoWidth'] as num?)?.toInt() ?? 0,
      videoHeight: (m['videoHeight'] as num?)?.toInt() ?? 0,
    );
  }

  final int textureId;
  final int videoWidth;
  final int videoHeight;
}

/// v3.4.6.c — one frame of the player position stream (the EventChannel the
/// bridge pushes at ~4 Hz: position, duration, buffered, play state, the
/// active Sinhala cue text, and the subtitle pipeline status).
class PlayerFrame {
  const PlayerFrame({
    required this.positionMs,
    required this.durationMs,
    required this.bufferedMs,
    required this.playing,
    required this.buffering,
    required this.videoWidth,
    required this.videoHeight,
    required this.activeCueSi,
    required this.subtitleState,
  });

  static const empty = PlayerFrame(
    positionMs: 0,
    durationMs: 0,
    bufferedMs: 0,
    playing: false,
    buffering: false,
    videoWidth: 0,
    videoHeight: 0,
    activeCueSi: '',
    subtitleState: '',
  );

  factory PlayerFrame.fromMap(Object? event) {
    final m = event is Map ? event : const <dynamic, dynamic>{};
    return PlayerFrame(
      positionMs: (m['positionMs'] as num?)?.toInt() ?? 0,
      durationMs: (m['durationMs'] as num?)?.toInt() ?? 0,
      bufferedMs: (m['bufferedMs'] as num?)?.toInt() ?? 0,
      playing: m['playing'] == true,
      buffering: m['buffering'] == true,
      videoWidth: (m['videoWidth'] as num?)?.toInt() ?? 0,
      videoHeight: (m['videoHeight'] as num?)?.toInt() ?? 0,
      activeCueSi: (m['activeCueSi'] as String?) ?? '',
      subtitleState: (m['subtitleState'] as String?) ?? '',
    );
  }

  final int positionMs;
  final int durationMs;
  final int bufferedMs;
  final bool playing;
  final bool buffering;
  final int videoWidth;
  final int videoHeight;

  /// The Sinhala cue that should be on screen RIGHT NOW (ZWJ/ZWNJ preserved
  /// verbatim — the bridge never re-encodes cue text).
  final String activeCueSi;

  /// The live subtitle pipeline status ("ready · 906 cues · subz.lk").
  final String subtitleState;
}

/// v3.4.6.c — one selectable track group from Media3 (video quality or audio
/// language): the group's tracks with labels and the currently-active index.
class PlayerTrackGroup {
  const PlayerTrackGroup({
    required this.type,
    required this.tracks,
    required this.selected,
  });

  final String type; // 'video' | 'audio'
  final List<PlayerTrack> tracks;
  final int selected;

  static List<PlayerTrackGroup> decode(Object? raw) {
    if (raw is! List) return const [];
    final out = <PlayerTrackGroup>[];
    for (final g in raw) {
      if (g is! Map) continue;
      final list = (g['tracks'] as List?) ?? const [];
      out.add(PlayerTrackGroup(
        type: (g['type'] as String?) ?? '',
        selected: (g['selected'] as num?)?.toInt() ?? -1,
        tracks: [
          for (final t in list)
            if (t is Map)
              PlayerTrack(
                index: (t['index'] as num?)?.toInt() ?? -1,
                label: (t['label'] as String?) ?? '',
              ),
        ],
      ));
    }
    return out;
  }
}

/// One track inside a [PlayerTrackGroup].
class PlayerTrack {
  const PlayerTrack({required this.index, required this.label});

  final int index;
  final String label;
}

// ── anantplay/cast ─────────────────────────────────────────────────────────

/// The AnantPlay native bridge surface (one singleton per app).
class AnantPlay {
  AnantPlay._();
  static final AnantPlay instance = AnantPlay._();

  // v3.4.6.f — the FIVE channel names that carry events (one hub each).
  static const _castEventsName = 'anantplay/cast/events';
  static const _updaterEventsName = 'anantplay/updater/events';
  static const _troubleEventsName = 'anantplay/troubleshoot/events';
  static const _shellEventsName = 'anantplay/shell/events';
  static const _playerEventsName = 'anantplay/player/events';

  // The lazily-cached feature views over the five singleton hubs.
  Stream<CastStatus>? _castStream;
  Stream<UpdaterEvent>? _updaterStream;
  Stream<UpdateGateEvent>? _gateStream;
  Stream<List<ProbeOutcome>>? _probeStream;
  Stream<dynamic>? _rawTroubleStream;
  Stream<OnboardingStatus>? _onboardingStream;
  Stream<InstallEvent>? _installStream;
  Stream<OfficialVersionEvent>? _officialVersionStream;
  Stream<Map<dynamic, dynamic>>? _shellRouteStream;
  Stream<PlayerFrame>? _frameStream;

  /// Test seam: dispose the hubs so a fresh scope can listen (the cached
  /// feature streams re-attach lazily on their next request).
  @visibleForTesting
  void resetStreams() {
    _castStream = null;
    _updaterStream = null;
    _gateStream = null;
    _probeStream = null;
    _rawTroubleStream = null;
    _onboardingStream = null;
    _installStream = null;
    _officialVersionStream = null;
    _shellRouteStream = null;
    _frameStream = null;
    _ChannelHub.resetAll();
  }

  /// v3.4.6.f — the honest bridge health snapshot (one stats row per event
  /// channel; a healthy bridge shows nativeListens ≤ 1 and streamErrors == 0
  /// — the Layer 1 audit's bridge probe).
  List<BridgeHubStats> hubStats() => [
        _ChannelHub.of(_castEventsName),
        _ChannelHub.of(_updaterEventsName),
        _ChannelHub.of(_troubleEventsName),
        _ChannelHub.of(_shellEventsName),
        _ChannelHub.of(_playerEventsName),
      ]
          .map((h) => BridgeHubStats(
                channel: h.name,
                nativeListens: h.nativeListens,
                nativeCancels: h.nativeCancels,
                streamErrors: h.streamErrors,
              ))
          .toList(growable: false);

  /// v3.4.6.f — the REAL installed identity (native AppInfo; the Dart side
  /// must never hardcode a version literal again).
  Future<AppBuildInfo> appInfo() async =>
      AppBuildInfo.fromMap(await _updater.invokeMethod('appInfo'));

  /// The live receiver status stream (pushed on every change + resume).
  Stream<CastStatus> castStatus() {
    return _castStream ??= _ChannelHub.of(_castEventsName)
        .stream
        // v3.4.6.f — GAP #4: the cast channel is SHARED with onboardingStatus,
        // officialVersion and installEvents. Without this discriminator every
        // one of those frames decodes as `up:false` and kills the Home card.
        .where((e) => e is Map &&
            (e['t'] == 'castStatus' ||
                (!e.containsKey('t') && e.containsKey('up'))))
        .map(CastStatus.fromMap)
        .startWith(currentCastStatus());
  }

  /// The live updater event stream (glass banner's data source).
  Stream<UpdaterEvent> updaterEvents() {
    return _updaterStream ??= _ChannelHub.of(_updaterEventsName)
        .stream
        .map((e) => UpdaterEvent.fromMap(e as Map));
  }

  /// v3.4.6.d — the mandatory force-update gate stream (available → progress →
  /// verify → installer, or failed with a retry reason, or uptodate). The
  /// shell locks the whole app behind ForceUpdateScreen the moment an
  /// `available` frame arrives with a strictly newer `next`.
  Stream<UpdateGateEvent> updateGateEvents() {
    return _gateStream ??= _ChannelHub.of(_updaterEventsName)
        .stream
        .where((e) => e is Map && e['t'] == 'gate')
        .cast<Map<dynamic, dynamic>>()
        .map(UpdateGateEvent.fromMap);
  }

  /// The probe-completion stream (10/10 outcomes, pushed when a run ends).
  Stream<List<ProbeOutcome>> probeResults() {
    return _probeStream ??= _ChannelHub.of(_troubleEventsName)
        .stream
        .map((e) => _decodeOutcomes(e));
  }

  /// v3.4.6.e — THE DEEP-RESULT FRAMES, RAW: the anantplay/troubleshoot
  /// events channel carries BOTH the 10-point legacy frames AND the deep
  /// run's payloads — the final full outcome list (a JSON string) and the
  /// per-probe progress frames ({t:'deep', done:n, total:54, outcome:{…}}).
  /// The typed [probeResults] stream pre-decodes every event into the
  /// 10-point ProbeOutcome shape, so the Help screen listens HERE and does
  /// its own routing (deep JSON list → the domain cards; deep progress →
  /// the live counter).
  Stream<dynamic> rawTroubleEvents() {
    return _rawTroubleStream ??= _ChannelHub.of(_troubleEventsName).stream;
  }

  /// v3.4.6.e — the live deep-run progress frames only ({t:'deep', …}).
  Stream<Map<dynamic, dynamic>> deepProgressEvents() {
    return rawTroubleEvents()
        .map(_deepFrameMap)
        .where((e) => e != null && e['t'] == 'deep')
        .cast<Map<dynamic, dynamic>>();
  }

  /// v3.4.6.g — THE LIVE STEP-BY-STEP FEED: one frame per probe START
  /// ({t:'deepStart', id, domain, name}) — the Help tab renders
  /// "▶ Checking #34 Security hardening flags…" the instant it begins.
  Stream<Map<dynamic, dynamic>> deepStartEvents() {
    return rawTroubleEvents()
        .map(_deepFrameMap)
        .where((e) => e != null && e['t'] == 'deepStart')
        .cast<Map<dynamic, dynamic>>();
  }

  /// v3.4.6.g — THE REAL-DEVICE FEED FIX (Trap #2): a deep progress/start
  /// frame arrives as EITHER a Map (the native EventChannel now sends the
  /// LinkedHashMap directly) OR a JSON object string (MiniJson.write on older
  /// hosts). Decode BOTH so the live step-by-step feed works on every host —
  /// the v3.4.6.g feed was dead on the real phone because native sent strings
  /// while Dart filtered `e is Map` (the tests pumped Maps and fake-passed).
  static Map<dynamic, dynamic>? _deepFrameMap(Object? e) {
    if (e is Map) return e;
    if (e is String && e.startsWith('{')) {
      try {
        final d = jsonDecode(e);
        if (d is Map) return d;
      } catch (_) {}
    }
    return null;
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

  // ── v3.4.6.d — the REAL notifications grant + the overlay return-watch ─────

  /// Slide 3's real in-app grant: API 33+ fires the native POST_NOTIFICATIONS
  /// runtime dialog IN-APP (zero leaving the app); a permanently-denied state
  /// opens ACTION_APP_NOTIFICATION_SETTINGS. The result arrives through
  /// [onboardingStatusEvents] (onRequestPermissionsResult pushes it).
  Future<void> requestNotifications() => _cast.invokeMethod('requestNotifications');

  /// The fallback lane: the app's own notification settings page.
  Future<void> openNotificationSettings() =>
      _cast.invokeMethod('openNotificationSettings');

  /// The 30-second bounded overlay watcher: polls canDrawOverlays every
  /// 500 ms while the app is paused and the instant the user flips the
  /// Display-over-other-apps toggle ON in Android Settings, brings the app
  /// back to the front automatically.
  Future<void> watchOverlayGrant() => _cast.invokeMethod('watchOverlayGrant');

  /// The live official-AnantPlay version pill (installed + version name),
  /// pushed fresh on every resume through [officialVersionEvents].
  Future<String> officialVersion() async =>
      await _cast.invokeMethod('officialVersion');

  /// The officialVersion event stream ({t:'officialVersion', installed, version}).
  Stream<OfficialVersionEvent> officialVersionEvents() {
    return _officialVersionStream ??= _ChannelHub.of(_castEventsName)
        .stream
        .where((e) => e is Map && e['t'] == 'officialVersion')
        .cast<Map<dynamic, dynamic>>()
        .map(OfficialVersionEvent.fromMap);
  }


  // ── v3.4.6.b — the unbreakable onboarding finish + in-app installer ─────────

  /// THE dedicated persistence path: K_ONBOARDING_DONE = true AND the
  /// K_ONBOARDING_VERSION stamp through the native `finishOnboarding` handler
  /// (AppPrefs.setOnboardingDone — a writer no allowlist ever filters). Never
  /// silently dropped, never normalized away.
  Future<void> finishOnboarding() => _cast.invokeMethod('finishOnboarding');

  /// v3.4.6.c — "Run Setup Guide": drop the done flag AND the version stamp
  /// so the shell's gate re-opens the 6-slide wizard exactly once more.
  Future<void> resetOnboarding() => _cast.invokeMethod('resetOnboarding');

  /// The live permission/install snapshot (overlay · notif · battery ·
  /// installs · official app) — one native round-trip for the whole wizard.
  Future<OnboardingStatus> onboardingStatus() async =>
      OnboardingStatus.fromMap(await _cast.invokeMethod('onboardingStatus'));

  /// The onboardingStatus stream: the host pushes a fresh frame on every
  /// resume, so auto-advance and every Enabled ✓ pill reflect live state.
  Stream<OnboardingStatus> onboardingStatusEvents() {
    return _onboardingStream ??= _ChannelHub.of(_castEventsName)
        .stream
        .map(OnboardingStatus.fromMap)
        .where((s) => s != OnboardingStatus.unknown);
  }

  /// Slide 2's installer stream (installProgress/installState frames).
  Stream<InstallEvent> installEvents() {
    return _installStream ??= _ChannelHub.of(_castEventsName)
        .stream
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

  /// Slide 5's universal background stability entry (OEM-aware page).
  Future<void> openBatteryOptimization() => _cast.invokeMethod('openBatteryOptimization');

  /// Slide 6's 1-tap copy (Sources & Index Helper).
  Future<void> copyToClipboard(String text) =>
      _cast.invokeMethod('copyToClipboard', {'text': text});

  /// v3.4.6.c — Help's 1-tap Share (the system ACTION_SEND sheet).
  Future<void> shareText(String text) =>
      _cast.invokeMethod('shareText', {'text': text});

  /// The editable Saved Index URL box (backed-up pref, returns the stored value).
  Future<String> saveIndexUrl(String url) async =>
      await _cast.invokeMethod('saveIndexUrl', {'url': url});

  // ── anantplay/player ─────────────────────────────────────────────────────

  /// v3.4.6.c — THE REAL TEXTURE PLAYER SURFACE. `open` builds a hardware
  /// ExoPlayer inside the Flutter TextureRegistry (SurfaceProducer) and
  /// returns the live textureId + video geometry; the HUD then drives the
  /// control methods and listens to the position EventChannel below.
  Future<PlayerSession> openPlayer(
    String url, {
    String? headers,
    String? title,
    bool test = false,
    int startMs = 0,
  }) async {
    final map = await _player.invokeMethod('open', {
      if (url.isNotEmpty) 'url': url,
      if (headers != null && headers.isNotEmpty) 'headers': headers,
      if (title != null && title.isNotEmpty) 'title': title,
      if (test) 'test': true,
      if (startMs > 0) 'startMs': startMs,
    });
    return PlayerSession.fromMap(map);
  }

  /// v3.4.6.c — a captured/sniffed browser stream → the in-shell Mornye
  /// Cinema Player (NOT PlayActivity — the exact complaint #3 fix).
  Future<void> playCaptured(String url, {String? headers, String? title}) =>
      _browser.invokeMethod('openInPlayer', {
        'url': url,
        if (headers != null && headers.isNotEmpty) 'headers': headers,
        if (title != null && title.isNotEmpty) 'title': title,
      });

  /// v3.4.6.c — the cast / DLNA / share / openWebTab route pump: the native
  /// layer pushes a route blob here; the shell (ShellRootState) picks it up
  /// and renders the Mornye Player or switches to the Browser tab. The blob
  /// shape: {t: 'play'|'web', url, headers, title, test, startMs}.
  Stream<Map<dynamic, dynamic>> shellRoutes() {
    return _shellRouteStream ??= _ChannelHub.of(_shellEventsName)
        .stream
        .where((e) => e is Map)
        .map((e) => e as Map<dynamic, dynamic>);
  }


  /// The live player status snapshot (the cast server's /status JSON).
  Future<String> playerState() async => await _player.invokeMethod('state');

  // ── player controls (Media3TextureBridge, all main-thread) ────────────────

  Future<void> playerPlay() => _player.invokeMethod('play');
  Future<void> playerPause() => _player.invokeMethod('pause');
  Future<void> playerSeek(int positionMs) =>
      _player.invokeMethod('seek', {'positionMs': positionMs});
  Future<void> playerSetSpeed(double speed) =>
      _player.invokeMethod('setSpeed', {'speed': speed});
  Future<void> playerSetVolume(double volume) =>
      _player.invokeMethod('setVolume', {'volume': volume});
  Future<void> playerSelectTrack(int type, int index) =>
      _player.invokeMethod('selectTrack', {'type': type, 'index': index});
  Future<List<PlayerTrackGroup>> playerTracks() async =>
      PlayerTrackGroup.decode(await _player.invokeMethod('tracks'));

  /// v3.4.6.e — REAL orientation control on the host activity: 'auto' =
  /// FULL_SENSOR free rotation, 'landscape' / 'portrait' = real locks via
  /// Activity.setRequestedOrientation; 'dispose' restores UNSPECIFIED.
  Future<void> playerSetOrientation(String mode) =>
      _player.invokeMethod('setOrientation', {'mode': mode});

  /// v3.4.6.g — THE THERMAL PUMP-RATE CONTRACT: 4 Hz while the HUD is visible,
  /// 1 Hz while it is hidden (the lightweight subtitle-cue check). PlayerScreen
  /// calls 4 on show, 1 on hide — the native ticker adapts instantly, so a
  /// 5–6 hour marathon session never burns 14,400 CPU wake-ups/hour for
  /// nothing.
  Future<void> playerSetPumpRate(int hz) =>
      _player.invokeMethod('setPumpRate', {'hz': hz});

  /// v3.4.6.e — the subtitle LANGUAGE switch: re-fetches/re-applies the cue
  /// pipeline for the selected language code ('si' | 'en' | …) on the live
  /// session (the native side re-runs findSubs with the language). The
  /// language ALSO persists as K_DEFAULT_LANG so future sessions match.
  Future<void> playerSwitchSubLanguage(String lang) =>
      _player.invokeMethod('switchSubLanguage', {'lang': lang});

  Future<void> playerDispose() => _player.invokeMethod('dispose');

  /// The player position EventChannel: {positionMs, durationMs, bufferedMs,
  /// playing, buffering, videoWidth, videoHeight, activeCueSi, subtitleState}.
  Stream<PlayerFrame> playerFrames() {
    return _frameStream ??= _ChannelHub.of(_playerEventsName)
        .stream
        .map(PlayerFrame.fromMap);
  }


  // ── v3.4.6.c — the in-shell subtitle pipeline (the proven native engine) ──

  /// Search → fetch → translate Sinhala subs for a title (the 10-site ladder
  /// with subz.lk in tier 1, cast subs first, the free translator fallback).
  /// Emits progress strings ("fetching…" / "90 cues · subz.lk") and finishes
  /// with the applied cue list on the player's active session.
  Future<void> findSubtitles(String title, {String? imdb, int season = 0, int episode = 0}) =>
      _subs.invokeMethod('find', {
        'title': title,
        if (imdb != null && imdb.isNotEmpty) 'imdb': imdb,
        'season': season,
        'episode': episode,
      });

  /// Load a local .srt/.zip subtitle (SAF content URI picked in the sheet).
  Future<bool> loadSubtitleFile(String uri) async =>
      await _subs.invokeMethod('loadFile', {'uri': uri});

  /// Load a subtitle from a pasted URL (auto-download → unzip → detect).
  Future<bool> loadSubtitleUrl(String url) async =>
      await _subs.invokeMethod('loadUrl', {'url': url});

  /// Shift the active subtitles by ±ms (the Sync stepper in the sheet).
  Future<void> shiftSubtitles(int deltaMs) =>
      _subs.invokeMethod('shift', {'deltaMs': deltaMs});

  /// The current subtitle status line ("ready · 906 cues · cached").
  Future<String> subtitleStatus() async =>
      await _subs.invokeMethod('status');

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

  /// v3.4.6.c — load a URL inside the ATTACHED native browser PlatformView
  /// (the WebBrowserView already on screen in the Browser tab).
  Future<void> openNativeWeb(String url) =>
      _browser.invokeMethod('loadNative', {'url': url});

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

  /// v3.4.6.f — the REAL backup apply (SettingsBackup validates + applies and
  /// returns the applied count + the fresh live snapshot).
  Future<Map<dynamic, dynamic>> importBackup(String json) async =>
      (await _settings.invokeMethod('importBackup', {'json': json})) as Map;

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

  /// v3.4.6.e — THE 54-PROBE DEEP TROUBLESHOOTER: launches the 6-domain
  /// full-app run (A Auto-Updater Law · B Home & Cast · C Settings &
  /// Customizations · D Browser · E Cinema Player & Subtitles · F
  /// Permissions & Privacy). The outcomes arrive on the troubleshoot events
  /// stream; decodeDeepOutcomes (deep_troubleshooter.dart) parses them.
  Future<void> runDeepProbe() => _trouble.invokeMethod('runDeepProbe');

  /// The last completed deep run's outcomes (a fresh start answers []).
  Future<List<DeepOutcome>> lastDeepResults() async =>
      decodeDeepOutcomes(await _trouble.invokeMethod('lastDeepResults'));

  /// The full structured privacy-safe report (Status Summary + Domains A–F +
  /// Last Crash + Timeline) — the clipboard auto-copy payload.
  ///
  /// v3.4.6.e — NEVER an unchecked cast: a host that answers null (or with a
  /// non-string) must degrade to the honest "no report yet" string, because
  /// this is awaited inside the Help tab's auto-copy path and a TypeError
  /// there would leave the banner stuck. The report is always built by the
  /// native side, so an empty answer really does mean "nothing to copy yet".
  Future<String> deepReport() async {
    final Object? raw = await _trouble.invokeMethod('deepReport');
    return raw is String ? raw : '';
  }
}

extension<T> on Stream<T> {
  Stream<T> startWith(Future<T> first) async* {
    yield await first;
    yield* this;
  }
}
