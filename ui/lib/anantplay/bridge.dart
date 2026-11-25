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
import 'deep_troubleshooter.dart';

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

  Stream<CastStatus>? _castStream;
  Stream<UpdaterEvent>? _updaterStream;
  Stream<UpdateGateEvent>? _gateStream;
  Stream<List<ProbeOutcome>>? _probeStream;
  // v3.4.6.e — the raw anantplay/troubleshoot frames (deep results + live
  // progress), cached beside the typed legacy streams.
  Stream<dynamic>? _rawTroubleStream;
  Stream<OnboardingStatus>? _onboardingStream;
  Stream<InstallEvent>? _installStream;

  /// Test seam: drops the cached broadcast streams so a fresh scope can listen.
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

  /// v3.4.6.d — the mandatory force-update gate stream (available → progress →
  /// verify → installer, or failed with a retry reason, or uptodate). The
  /// shell locks the whole app behind ForceUpdateScreen the moment an
  /// `available` frame arrives with a strictly newer `next`.
  Stream<UpdateGateEvent> updateGateEvents() {
    return _gateStream ??= _updaterEvents
        .receiveBroadcastStream()
        .where((e) => e is Map && e['t'] == 'gate')
        .cast<Map<dynamic, dynamic>>()
        .map(UpdateGateEvent.fromMap);
  }

  /// The probe-completion stream (10/10 outcomes, pushed when a run ends).
  Stream<List<ProbeOutcome>> probeResults() {
    return _probeStream ??= _troubleEvents
        .receiveBroadcastStream()
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
    return _rawTroubleStream ??= _troubleEvents.receiveBroadcastStream();
  }

  /// v3.4.6.e — the live deep-run progress frames only ({t:'deep', …}).
  Stream<Map<dynamic, dynamic>> deepProgressEvents() {
    return rawTroubleEvents()
        .where((e) => e is Map && e['t'] == 'deep')
        .cast<Map<dynamic, dynamic>>();
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
    return _officialVersionStream ??= _castEvents
        .receiveBroadcastStream()
        .where((e) => e is Map && e['t'] == 'officialVersion')
        .cast<Map<dynamic, dynamic>>()
        .map(OfficialVersionEvent.fromMap);
  }

  Stream<OfficialVersionEvent>? _officialVersionStream;

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
    return _shellRouteStream ??= EventChannel('anantplay/shell/events')
        .receiveBroadcastStream()
        .where((e) => e is Map)
        .map((e) => e as Map<dynamic, dynamic>);
  }

  Stream<Map<dynamic, dynamic>>? _shellRouteStream;

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
    return _frameStream ??= EventChannel('anantplay/player/events')
        .receiveBroadcastStream()
        .map(PlayerFrame.fromMap);
  }

  Stream<PlayerFrame>? _frameStream;

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
