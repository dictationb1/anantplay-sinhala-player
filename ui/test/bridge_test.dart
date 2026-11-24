// v3.4.6 bridge contract tests: the 8-channel surface against a mock
// MethodChannel/EventChannel host — proving the exact method names, argument
// shapes, and stream decoders the Java side implements.
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:anantplay_ui/anantplay/bridge.dart';
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
        return <String, Object>{'pending': true, 'version': '3.4.6.a'};
      case 'list':
        return '[{"title":"ශ්‍රී ලංකාවේ චිත්‍රපටිය","url":"https://example.com/v.m3u8","source":"cast","pos":120000,"dur":600000,"at":1,"ephemeralRelay":false}]';
      case 'getAll':
        return <String, Object>{'themeMode': 'amoled', 'glassClarity': 75, 'onboardingDone': false};
      case 'exportBackup':
        return '{"app":"AnantPlay Sinhala Player","schema":1}';
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
    expect(pending.version, '3.4.6.a');
  });

  test('playUrl sends the open method with url/headers/title keys', () async {
    await AnantPlay.instance.playUrl('https://x.example/y.mp4',
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
}
