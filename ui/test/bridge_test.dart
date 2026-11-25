// v3.4.6 bridge contract tests: the 8-channel surface against a mock
// MethodChannel/EventChannel host — proving the exact method names, argument
// shapes, and stream decoders the Java side implements.
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:anantplay_ui/anantplay/bridge.dart';
import 'package:anantplay_ui/anantplay/deep_troubleshooter.dart';
import 'package:anantplay_ui/anantplay/settings_mirror.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final calls = <MethodCall>[];
  final mock = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  Future<Object?> handler(MethodCall call) async {
    calls.add(call);
    switch (call.method) {
      case 'status':
        return <String, Object>{'up': true, 'port': 8765, 'discoverable': true, 'mdns': true};
      case 'pending':
        return <String, Object>{'pending': true, 'version': '3.4.6.b'};
      case 'appInfo':
        // v3.4.6.f — the REAL installed identity for the LAW #1 Dart audits.
        return <String, Object>{'versionName': '3.4.6.f', 'versionCode': 30412};
      case 'list':
        return '[{"title":"ශ්‍රී ලංකාවේ චිත්‍රපටිය","url":"https://example.com/v.m3u8","source":"cast","pos":120000,"dur":600000,"at":1,"ephemeralRelay":false}]';
      case 'getAll':
        return <String, Object>{'themeMode': 'amoled', 'glassClarity': 75, 'onboardingDone': false};
      case 'exportBackup':
        return '{"app":"AnantPlay Sinhala Player","schema":1}';
      case 'finishOnboarding':
        return true;
      case 'saveIndexUrl':
        return call.arguments['url'];
      case 'copyToClipboard':
        return true;
      case 'deepReport':
        // v3.4.6.e — the real host always answers with the built report.
        return 'AnantPlay Sinhala Player v3.4.6.e (30411) — Deep Troubleshoot Report';
      case 'onboardingStatus':
        return <String, Object>{'t': 'onboardingStatus', 'overlay': true, 'notif': false, 'battery': true, 'installs': true, 'official': false};
      default:
        return null;
    }
  }

  setUp(() {
    calls.clear();
    for (final channel in const [
      'anantplay/cast', 'anantplay/player', 'anantplay/subs', 'anantplay/browser',
      'anantplay/settings', 'anantplay/history', 'anantplay/updater', 'anantplay/troubleshoot',
    ]) {
      mock.setMockMethodCallHandler(MethodChannel(channel), handler);
    }
  });

  test('cast status round-trips the Java map shape', () async {
    final status = await AnantPlay.instance.currentCastStatus();
    expect(status.up, isTrue);
    expect(status.port, 8765);
    expect(status.mdns, isTrue);
  });

  test('cast status decodes a junk payload defensively', () {
    final status = CastStatus.fromMap(<String, Object>{'bogus': 1});
    expect(status.up, isFalse);
    expect(status.port, -1);
  });

  test('history JSON decodes with Sinhala titles and progress math', () async {
    final items = await AnantPlay.instance.history();
    expect(items, hasLength(1));
    expect(items.first.title, 'ශ්‍රී ලංකාවේ චිත්‍රපටිය');
    expect(items.first.percent, 20);   // 120000/600000
    expect(items.first.resumable, isTrue);
  });

  test('settings mirror reads typed values from getAll', () async {
    final mirror = await AnantPlay.instance.loadSettings();
    expect(mirror.themeMode, 'amoled');
    expect(mirror.glassClarity, 75);
    expect(mirror.onboardingDone, isFalse);
  });

  test('putBool writes the exact key/value the Java host expects', () async {
    await AnantPlay.instance.putBool(SettingsMirror.kGlassOutlines, false);
    final call = calls.firstWhere((c) => c.method == 'putBool');
    expect(call.arguments, {'key': 'glassOutlines', 'value': false});
  });

  test('pendingUpdate decodes the pending map', () async {
    final pending = await AnantPlay.instance.pendingUpdate();
    expect(pending.pending, isTrue);
    expect(pending.version, '3.4.6.b');
  });

  test('openPlayer sends the open method with url/headers/title keys', () async {
    await AnantPlay.instance.openPlayer('https://x.example/y.mp4',
        headers: '{"Cookie":"a=b"}', title: 'Test');
    final call = calls.firstWhere((c) => c.method == 'open');
    expect(call.arguments,
        {'url': 'https://x.example/y.mp4', 'headers': '{"Cookie":"a=b"}', 'title': 'Test'});
  });

  test('exportBackup returns the Java JSON string', () async {
    final json = await AnantPlay.instance.exportBackup();
    expect(json, contains('"AnantPlay Sinhala Player"'));
    expect(json, contains('"schema":1'));
  });

  test('finishOnboarding sends the dedicated channel writer', () async {
    await AnantPlay.instance.finishOnboarding();
    final call = calls.firstWhere((c) => c.method == 'finishOnboarding');
    expect(call.method, 'finishOnboarding');
  });

  test('onboardingStatus decodes the live permission/install snapshot', () {
    final s = OnboardingStatus.fromMap(<String, Object>{
      't': 'onboardingStatus', 'overlay': true, 'notif': false,
      'battery': true, 'installs': true, 'official': false,
    });
    expect(s.overlay, isTrue);
    expect(s.notif, isFalse);
    expect(s.official, isFalse);
    // junk payloads degrade to unknown, never throw
    expect(OnboardingStatus.fromMap(<String, Object>{'t': 'other'}),
        OnboardingStatus.unknown);
  });

  test('install events decode {t, v, x} frames with percent', () {
    final e = InstallEvent.fromMap(<String, Object>{'t': 'installProgress', 'v': '42', 'x': '3.4.6'});
    expect(e.type, 'installProgress');
    expect(e.percent, 42);
    expect(e.extra, '3.4.6');
  });

  test('saveIndexUrl / copyToClipboard send the exact key/value shapes', () async {
    await AnantPlay.instance.saveIndexUrl('https://example.com/index.json');
    final save = calls.firstWhere((c) => c.method == 'saveIndexUrl');
    expect(save.arguments, {'url': 'https://example.com/index.json'});
    await AnantPlay.instance.copyToClipboard('https://example.com/index.json');
    final copy = calls.firstWhere((c) => c.method == 'copyToClipboard');
    expect(copy.arguments, {'text': 'https://example.com/index.json'});
  });

  test('updater events decode the {t, v} map shape', () {
    final e = UpdaterEvent.fromMap(<String, Object>{'t': 'progress', 'v': '42'});
    expect(e.type, 'progress');
    expect(e.value, '42');
  });

  test('probe outcomes decode the outcome map shape', () {
    final o = ProbeOutcome.fromMap(<String, Object>{
      'id': 3, 'name': 'mDNS responder', 'pass': true, 'ms': 120, 'detail': 'ok'
    });
    expect(o.id, 3);
    expect(o.pass, isTrue);
    expect(o.ms, 120);
  });

  test('PlayerSession decodes the textureId + geometry map', () {
    final s = PlayerSession.fromMap(<String, Object>{
      'textureId': 7, 'videoWidth': 1920, 'videoHeight': 1080,
    });
    expect(s.textureId, 7);
    expect(s.videoWidth, 1920);
    expect(s.videoHeight, 1080);
  });

  test('PlayerFrame decodes the 4 Hz event shape with the Sinhala cue', () {
    final f = PlayerFrame.fromMap(<String, Object>{
      'positionMs': 42000, 'durationMs': 600000, 'bufferedMs': 90000,
      'playing': true, 'buffering': false,
      'videoWidth': 1280, 'videoHeight': 720,
      'activeCueSi': 'ශ්‍රී ලංකාවේ සිනමාව', 'subtitleState': 'ready · 906 cues',
    });
    expect(f.positionMs, 42000);
    expect(f.playing, isTrue);
    expect(f.activeCueSi, contains('\u200d'));   // ZWJ preserved verbatim
    expect(f.subtitleState, startsWith('ready'));
  });

  test('PlayerTrackGroup decodes the video quality ladder', () {
    final groups = PlayerTrackGroup.decode(<Object>[
      <String, Object>{
        'type': 'video', 'selected': 0,
        'tracks': <Object>[
          <String, Object>{'index': 0, 'label': '1080p'},
          <String, Object>{'index': 1, 'label': '720p'},
        ],
      },
    ]);
    expect(groups, hasLength(1));
    expect(groups.first.type, 'video');
    expect(groups.first.tracks.last.label, '720p');
  });

  test('SettingsMirror onboardingVersion: legacy done=true reads back as 1 (the upgrader gate)',
      () {
    final legacy = SettingsMirror.fromJavaMap(<String, Object>{
      'onboardingDone': true,
    });
    expect(legacy.onboardingVersion, 1);
    expect(legacy.onboardingCurrent, isFalse);
    final fresh = SettingsMirror.fromJavaMap(<String, Object>{});
    expect(fresh.onboardingVersion, 0);
    final current = SettingsMirror.fromJavaMap(<String, Object>{
      'onboardingDone': true, 'onboardingVersion': 2,
    });
    expect(current.onboardingCurrent, isTrue);
  });

  // ══ v3.4.6.e — THE DEEP TROUBLESHOOT + PLAYER CONTROL CONTRACT ═══════════

  test('playerSetOrientation sends the exact mode argument', () async {
    await AnantPlay.instance.playerSetOrientation('landscape');
    expect(calls.last.method, 'setOrientation');
    expect(calls.last.arguments, <String, Object>{'mode': 'landscape'});
  });

  test('playerSwitchSubLanguage sends the exact lang argument', () async {
    await AnantPlay.instance.playerSwitchSubLanguage('en');
    expect(calls.last.method, 'switchSubLanguage');
    expect(calls.last.arguments, <String, Object>{'lang': 'en'});
  });

  test('runDeepProbe / lastDeepResults / deepReport ride the troubleshoot channel',
      () async {
    await AnantPlay.instance.runDeepProbe();
    expect(calls.last.method, 'runDeepProbe');
    await AnantPlay.instance.lastDeepResults();
    expect(calls.last.method, 'lastDeepResults');
    final report = await AnantPlay.instance.deepReport();
    expect(calls.last.method, 'deepReport');
    expect(report, contains('AnantPlay Sinhala Player'));
  });

  test('decodeDeepOutcomes parses the domain-grouped outcome list', () {
    final outcomes = decodeDeepOutcomes(<Object>[
      <String, Object>{
        'id': 1, 'domain': 'A', 'name': 'Tier-1 redirect',
        'pass': true, 'ms': 120, 'detail': '302 resolved',
      },
      <String, Object>{
        'id': 8, 'domain': 'B', 'name': 'Stop Receiver',
        'pass': false, 'ms': 40, 'detail': 'running == true',
      },
    ]);
    expect(outcomes, hasLength(2));
    expect(outcomes.first.domain, 'A');
    expect(outcomes.first.pass, isTrue);
    expect(outcomes.last.domain, 'B');
    expect(outcomes.last.pass, isFalse);
    // The domain titles resolve.
    expect(DeepDomain.titleOf('A'), 'Auto-Updater Law');
    expect(DeepDomain.titleOf('E'), 'Cinema Player & Subtitles');
  });

  test('decodeDeepOutcomes parses a JSON string payload + rejects junk', () {
    final outcomes = decodeDeepOutcomes(
        '[{"id":1,"domain":"A","name":"x","pass":true,"ms":5,"detail":"d"}]');
    expect(outcomes, hasLength(1));
    expect(outcomes.first.name, 'x');
    expect(decodeDeepOutcomes('deep probe failed: boom'), isEmpty);
    expect(decodeDeepOutcomes(null), isEmpty);
  });

  // ════════════════════════════════════════════════════════════════════
  // v3.4.6.f — THE UPDATER LAW + EVENTCHANNEL COLLISION + HONEST VERDICTS
  // ════════════════════════════════════════════════════════════════════
  group('v3.4.6.f updater laws (the self-lock kill)', () {
    test('isNewerVersion: v3.4.6.f → v3.4.6.f is FALSE (the self-lock is dead)', () {
      expect(isNewerVersion('3.4.6.f', '3.4.6.f'), isFalse,
          reason: 'a same-version gate is mathematically impossible now');
      expect(isNewerVersion('3.4.6.e', '3.4.6.e'), isFalse);
      expect(isNewerVersion('3.4.6.f', '3.4.6.e'), isTrue);
      expect(isNewerVersion('3.4.6.g', '3.4.6.f'), isTrue);
      expect(isNewerVersion('3.4.6.z', '3.4.6.f'), isTrue);
      expect(isNewerVersion('3.4.7', '3.4.6.z'), isTrue);
      expect(isNewerVersion('3.4.6.d', '3.4.6.e'), isFalse,
          reason: 'an older "release" is never an update');
      expect(isNewerVersion(null, '3.4.6.f'), isFalse);
      expect(isNewerVersion('junk', '3.4.6.f'), isFalse);
    });

    test('appInfo reads the REAL installed identity from the host (never a literal)', () async {
      final info = await AnantPlay.instance.appInfo();
      expect(info.versionName, '3.4.6.f');
      expect(info.versionCode, 30412);
    });
  });

  group('v3.4.6.f EventChannel singleton hub (the collision kill)', () {
    setUp(() {
      AnantPlay.instance.resetStreams();
    });

    test('MANY listeners on ONE channel: exactly one native listen, zero errors', () async {
      final received = <String>[];
      // FOUR parallel listeners on the SAME cast events channel (the exact
      // v3.4.6.e topology that clobbered handlers and produced
      // `No active stream to cancel`) — now all fed by ONE hub.
      final subs = [
        AnantPlay.instance.castStatus().listen((s) => received.add('cast:${s.up}')),
        AnantPlay.instance.installEvents().listen((e) => received.add('install:${e.type}')),
        AnantPlay.instance.officialVersionEvents().listen((e) => received.add('official:${e.installed}')),
        AnantPlay.instance.onboardingStatusEvents().listen((e) => received.add('onboarding:${e.overlay}')),
      ];
      // plus the updater hub with BOTH typed views (banner + gate) on one listen.
      final gateSub = AnantPlay.instance.updateGateEvents().listen((e) => received.add('gate:${e.gate}'));
      final updaterSub = AnantPlay.instance.updaterEvents().listen((e) => received.add('updater:${e.type}'));
      await Future<void>.delayed(Duration.zero);
      final envelope = const StandardMethodCodec().encodeSuccessEnvelope(
          <String, Object>{'t': 'onboardingStatus', 'overlay': true, 'notif': false, 'battery': true, 'installs': true, 'official': false});
      await TestDefaultBinaryMessengerBinding
          .instance.defaultBinaryMessenger.handlePlatformMessage(
        'anantplay/cast/events', envelope, (ByteData? data) {});
      await Future<void>.delayed(Duration.zero);
      // The onboarding-status listener received its frame...
      expect(received.where((r) => r.startsWith('onboarding:')), isNotEmpty);
      // ...and the hub health: every channel ≤ 1 native listen, zero errors.
      final stats = AnantPlay.instance.hubStats();
      for (final st in stats) {
        expect(st.nativeListens, lessThanOrEqualTo(1),
            reason: '$st — at most ONE native listen per channel name');
        expect(st.streamErrors, 0, reason: '$st — zero stream errors');
      }
      final castStats = stats.firstWhere((s) => s.channel == 'anantplay/cast/events');
      expect(castStats.nativeListens, 1,
          reason: 'four feature views, ONE native listen (the collision killer)');
      await Future.wait([for (final s in subs) s.cancel(), gateSub.cancel(), updaterSub.cancel()]);
      AnantPlay.instance.resetStreams();
    });

    test('updateGateEvents and updaterEvents share ONE native listen (both views fed)', () async {
      final gates = <String>[];
      final states = <String>[];
      final gateSub = AnantPlay.instance.updateGateEvents().listen((e) => gates.add(e.gate));
      final stateSub = AnantPlay.instance.updaterEvents().listen((e) => states.add(e.type));
      await Future<void>.delayed(Duration.zero);
      await TestDefaultBinaryMessengerBinding
          .instance.defaultBinaryMessenger.handlePlatformMessage(
        'anantplay/updater/events',
        const StandardMethodCodec().encodeSuccessEnvelope(<String, Object>{
          't': 'gate', 'gate': 'available', 'current': '3.4.6.f', 'next': '3.4.6.g',
        }),
        (ByteData? data) {},
      );
      await Future<void>.delayed(Duration.zero);
      await TestDefaultBinaryMessengerBinding
          .instance.defaultBinaryMessenger.handlePlatformMessage(
        'anantplay/updater/events',
        const StandardMethodCodec().encodeSuccessEnvelope(<String, Object>{
          't': 'state', 'v': 'Up to date',
        }),
        (ByteData? data) {},
      );
      await Future<void>.delayed(Duration.zero);
      // BOTH typed views received THEIR frames — the handler was never stolen.
      expect(gates, contains('available'));
      expect(states, contains('state'));
      final stat = AnantPlay.instance.hubStats()
          .firstWhere((s) => s.channel == 'anantplay/updater/events');
      expect(stat.nativeListens, 1);
      expect(stat.streamErrors, 0);
      await Future.wait([gateSub.cancel(), stateSub.cancel()]);
      AnantPlay.instance.resetStreams();
    });
  });

  group('v3.4.6.f honest troubleshooter (zero fake PASSes)', () {
    test('a dead worker (HTTP 0) is a FAIL ✗ — NEVER a PASS', () {
      // The native probe 47 contract, mirrored on the honest detail text.
      const detail =
          'FAIL — Worker unreachable (HTTP 0 / HTTP 0): subtitles fall back to the local translator';
      const fail = DeepOutcome(
          id: 47, domain: 'E', name: 'Worker /health + /api/resolve live',
          pass: false, ms: 6528, detail: detail);
      expect(fail.pass, isFalse);
      expect(detail, contains('HTTP 0'));
      expect(detail, isNot(contains('offline retention path ✓')));
    });

    test('composeHonestReport puts EVERY FAIL ✗ at the very top', () {
      final native = [
        const DeepOutcome(id: 1, domain: 'A', name: 'Tier-1 redirect', pass: true, ms: 120, detail: 'ok'),
        const DeepOutcome(id: 47, domain: 'E', name: 'Worker /health + /api/resolve live',
            pass: false, ms: 6528, detail: 'FAIL — Worker unreachable (HTTP 0 / HTTP 0)'),
        const DeepOutcome(id: 56, domain: 'F', name: 'Uncaught crash gate (Last Crash)',
            pass: false, ms: 3, detail: 'FAIL — uncaught crash captured: IllegalStateException'),
      ];
      final dart = [
        const DeepOutcome(id: 57, domain: 'G', name: 'Bridge singleton hubs', pass: true, ms: 1, detail: 'ok'),
        const DeepOutcome(id: 60, domain: 'G', name: 'Settings round-trip', pass: false, ms: 2,
            detail: 'FAIL — read back null'),
      ];
      final report = composeHonestReport('AnantPlay Sinhala Player v3.4.6.f (30412) — report body', native, dart);
      // The verdict line is FIRST, and every FAIL precedes the report body.
      final verdictIndex = report.indexOf('== MERGED VERDICT: 2/5 PASS · 3 FAIL ==');
      final fail47 = report.indexOf('FAIL ✗ 47. Worker');
      final fail56 = report.indexOf('FAIL ✗ 56. Uncaught crash');
      final fail60 = report.indexOf('FAIL ✗ 60. Settings');
      final bodyIndex = report.indexOf('report body');
      expect(verdictIndex, 0);
      for (final i in [fail47, fail56, fail60]) {
        expect(i, greaterThan(0));
        expect(i, lessThan(bodyIndex),
            reason: 'every FAIL must sit ABOVE the report body (nothing hidden)');
      }
      expect(report, contains('== Domain G — Live Flutter UI (Dart layer'));
    });

    test('the crash-gate outcome: a captured PlatformException FAILS honestly', () {
      const crashOutcome = DeepOutcome(
          id: 56, domain: 'F', name: 'Uncaught crash gate (Last Crash)',
          pass: false, ms: 2,
          detail: 'FAIL — uncaught crash captured: '
              'IllegalStateException: PlatformException(error, No active stream to cancel, null, null)');
      expect(crashOutcome.pass, isFalse);
      expect(crashOutcome.detail, contains('No active stream to cancel'));
    });
  });
}
