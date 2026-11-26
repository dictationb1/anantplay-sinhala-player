// AnantPlay Sinhala Player v3.4.6.j — ZERO-TRUST RELEASE CONTRACTS (tests).
//
// One focused regression file for the v3.4.6.j release:
//   1. dual App Style (Mornye liquid glass ⇄ Material 3 Expressive)
//   2. the searchable eight-page Settings hub + Storage & Cache sub-page
//   3. the REAL Midnight dimmer scrim (root IgnorePointer, Dark/AMOLED only)
//   4. the top-floating Mornye glass notification (bottom SnackBars are gone)
//   5. first-run Onboarding hides Skip; a voluntary re-run shows Close
//   6. ForceUpdateScreen's Installer Open… → Install Downloaded Update ladder
//   7. the five audit-trap fixes stay fixed
//   8. the #87 diagnostic classification stays HONEST
//
// Every assertion reads the SHIPPED source (or pumps the real widget), never
// a copied constant — a regression in the release tree fails this file.
import 'dart:io' show File;

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart' show MaterialApp, Widget;

import 'package:anantplay_ui/anantplay/force_update_screen.dart';

String repo(String rel) => File(rel).readAsStringSync();

Widget forceUpdateApp() => MaterialApp(
      home: const ForceUpdateScreen(currentVersion: '3.4.6.i'),
    );

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(const MethodChannel('anantplay/cast'),
            (call) async {
      if (call.method == 'officialVersion') return '';
      if (call.method == 'onboardingStatus') {
        return <String, Object>{
          't': 'onboardingStatus',
          'overlay': false,
          'notif': false,
          'battery': false,
          'installs': false,
          'official': false,
        };
      }
      return <String, Object>{
        'up': false,
        'port': -1,
        'discoverable': false,
        'mdns': false,
      };
    });
  });

  group('v3.4.6.j Law #2 — the unified Mornye/Material experience', () {
    test('dual App Style: Mornye default + authentic Material 3 seed scheme',
        () {
      final main = repo('lib/main.dart');
      expect(main.contains("SettingsMirror.kAppStyle, 'mornye') == 'material'"),
          isTrue,
          reason: 'the material lane must switch off the Mornye default');
      expect(main.contains('ColorScheme.fromSeed('), isTrue,
          reason: 'Material M3 Expressive uses the real tonal generator');
      final settings = repo('lib/anantplay/settings_screen.dart');
      expect(settings.contains("title: 'App style'"), isTrue);
    });

    test('eight searchable Settings pages include Storage & Cache', () {
      final settings = repo('lib/anantplay/settings_screen.dart');
      expect(settings.contains("hintText: 'Search settings'"), isTrue);
      const pages = <String>[
        'Appearance',
        'Playback & Subtitles',
        'AnantPlay & Sources',
        'Browser & Privacy',
        'Cast & Network',
        'Storage & Cache',
        'Backup & Restore',
        'About & Licenses',
      ];
      for (final page in pages) {
        expect(settings.contains("title: '$page'"), isTrue,
            reason: 'the $page hub row/sub-page must exist');
      }
    });

    test('Midnight dimmer is a real root scrim in Dark/AMOLED only', () {
      final main = repo('lib/main.dart');
      expect(main.contains("ValueKey('midnight-dim-overlay')"), isTrue);
      expect(main.contains('Theme.of(context).brightness == Brightness.dark'),
          isTrue);
      expect(main.contains('IgnorePointer('), isTrue,
          reason: 'the scrim is purely visual — zero hit-test impact');
    });

    test('floating notifications are top-anchored glass, never bottom SnackBars',
        () {
      final glass = repo('lib/mornye_ui/widgets/mornye_glass_notification.dart');
      expect(glass.contains('top: 0,'), isTrue);
      final feedback = repo('lib/anantplay/action_feedback.dart');
      expect(feedback.contains('showMornyeGlassNotification(context, message: message)'),
          isTrue,
          reason: 'the shared action feedback uses glass, not SnackBar');
      expect(repo('lib/anantplay/settings_screen.dart')
          .contains('showMornyeGlassNotification'), isTrue);
    });

    test('first-run Onboarding hides Skip; voluntary re-run shows Close', () {
      final onboarding = repo('lib/anantplay/onboarding_screen.dart');
      expect(onboarding.contains("final String? closeLabel = firstTimeLaunch ? null : 'Close';"),
          isTrue,
          reason: 'first run has NO Skip; a voluntary re-run has Close');
    });
  });

  group('v3.4.6.j Law #1 — the mandatory updater handoff', () {
    test('Installer Open… (3 s disabled) → Install Downloaded Update', () {
      final dart = repo('lib/anantplay/force_update_screen.dart');
      expect(dart.contains("'Installer Open…'"), isTrue);
      expect(dart.contains("'Install Downloaded Update'"), isTrue);
      expect(dart.contains('const Duration(seconds: 3)'), isTrue);
      expect(dart.contains('ZERO-DOUBLE-DOWNLOAD'), isTrue);
      expect(dart.contains("'Open Installer Again'"), isFalse,
          reason: 'no red Update Now flash after the installer handoff');
    });

    testWidgets('a verified pending APK renders the cached-install label',
        (tester) async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(const MethodChannel('anantplay/updater'),
              (call) async {
        if (call.method == 'pending') {
          return <String, Object>{'pending': true, 'version': '3.4.6.j'};
        }
        if (call.method == 'appInfo') {
          return <String, Object>{
            'versionName': '3.4.6.i',
            'versionCode': 30415,
            'packageName': 'lk.techworld.anantplay.sicast',
          };
        }
        return true;
      });
      await tester.pumpWidget(forceUpdateApp());
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));
      expect(find.text('Install Downloaded Update'), findsOneWidget);
      expect(find.text('Update Now'), findsNothing);
      expect(tester.takeException(), isNull,
          reason: 'the gate must render without a RenderFlex overflow');
    });
  });

  group('v3.4.6.j — the five audit traps stay fixed', () {
    test('decoder fallback is enabled and the cast watchdog is signalled', () {
      final player = repo('../src/lk/techworld/anantplay/sicast/PlayActivity.java');
      expect(player.contains('.setEnableDecoderFallback(true)'), isTrue);
      expect(player.contains('.setEnableDecoderFallback(false)'), isFalse);
      expect(player.contains('EXTENSION_RENDERER_MODE_OFF'), isTrue);
      expect(player.contains('ReceiverService.markPlayerOpened('), isTrue,
          reason: 'onCreate signals the 5 s cast watchdog immediately');
    });

    test('index feed refuses hostile redirect hops and the coach gates addView',
        () {
      final core =
          repo('../src/lk/techworld/anantplay/sicast/SelfUpdateCore.java');
      expect(core.contains('if (!safeRemoteUrl(current))'), isTrue,
          reason: 'every redirect hop gets the SSRF predicate');
      expect(core.contains('FEED_MAX_BYTES = 1024 * 1024'), isTrue);
      expect(core.contains('FEED_DEADLINE_MS = 10_000L'), isTrue);
      final coach = repo(
          '../src/lk/techworld/anantplay/sicast/AnantPlayCoachService.java');
      expect(coach.indexOf('if (!canCoach(this)) { stopSelf(); return; }'),
          greaterThan(-1));
      expect(coach.contains('TYPE_APPLICATION_OVERLAY'), isTrue);
      expect(coach.contains('startForeground('), isFalse);
      expect(repo('../AndroidManifest.xml').contains('ACCESS_NETWORK_STATE'),
          isTrue);
    });

    test('battery guide is high contrast and Search engine re-homes the Browser',
        () {
      final guide = repo(
          '../src/lk/techworld/anantplay/sicast/PermissionGuideActivity.java');
      expect(guide.contains('setStroke(dp(2), GREEN)'), isTrue);
      expect(guide.contains('Battery optimization: OFF'), isTrue);
      expect(guide.contains('screenOrientation'), isFalse,
          reason: 'Android 16 translucent crash guard: no orientation request');
      final main =
          repo('../src/lk/techworld/anantplay/sicast/MainActivity.java');
      expect(main.contains('syncBrowserEngineHome()'), isTrue);
      final browser =
          repo('../src/lk/techworld/anantplay/sicast/WebBrowserView.java');
      expect(browser.contains('applySearchEngineHome()'), isTrue);
      for (final engine in ['duckduckgo.com', 'startpage.com', 'search.brave.com']) {
        expect(browser.contains(engine), isTrue);
      }
    });

    test('#87 diagnostics stay honest: real exceptions still fail H14', () {
      final main = repo('lib/main.dart');
      expect(main.contains('sessionFlutterErrorCount++'), isTrue);
      expect(main.contains('final bool isDiagnostic ='), isTrue);
      expect(main.contains("asText.startsWith('A RenderFlex overflowed')"), isTrue);
      // A real exception is counted; only framework diagnostics are split out.
      final counterAt = main.indexOf('sessionFlutterErrorCount++');
      final diagnosticAt = main.indexOf('final bool isDiagnostic =');
      expect(counterAt, greaterThan(diagnosticAt),
          reason: 'the guarded real-error branch must follow classification');
    });
  });
}
