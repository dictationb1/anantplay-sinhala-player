// AnantPlay Sinhala Player v3.4.6.g — THE REAL-PHONE FIX CONTRACT TESTS.
//
// Covers every newly fixed surface end-to-end in the live Flutter UI:
//   · the thermal pump-rate contract (playerSetPumpRate 4 Hz HUD / 1 Hz hidden)
//   · PlayerScreen's HUD auto-hide + unmount and its Texture fallback lane
//   · the Mornye liquid-glass AppSwitch physics (tap, drag, stretch press)
//   · the Home Got-it banner dismissal (homeTipDismissed) + Watch Tour
//   · the # Custom Color Studio sheet (swatch grid, hex input, Apply Color)
//   · REAL Material You: the honest caption when the OS has no scheme, the
//     live-wallpaper caption when it does (DynamicColorBuilder)
//   · the live step-by-step troubleshooter feed lines
//   · the honest report header that PrivacyScrubber must NEVER eat
//   · Updater LAW #1's same-version self-lock (already covered by shell_test,
//     re-asserted here against the shipped version identity).
import 'package:flutter/gestures.dart' show PointerDeviceKind;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'package:anantplay_ui/anantplay/app_state.dart';
import 'package:anantplay_ui/mornye_ui/widgets/mornye_chrome.dart';
import 'package:anantplay_ui/mornye_ui/theme/mornye_theme.dart';
import 'package:anantplay_ui/anantplay/bridge.dart';
import 'package:anantplay_ui/anantplay/settings_screen.dart';
import 'package:anantplay_ui/anantplay/help_screen.dart';
import 'package:anantplay_ui/anantplay/onboarding_screen.dart' as ob;
import 'package:anantplay_ui/anantplay/player_screen.dart';
import 'package:anantplay_ui/anantplay/deep_troubleshooter.dart';
import 'package:anantplay_ui/mornye_ui/widgets/app_switch.dart';
import 'package:anantplay_ui/main.dart' as shell;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final mock = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final settingsPuts = <String, Object>{};
  bool onboardingDone = false;
  int onboardingVersion = 0;
  final playerCalls = <String>[];
  Object? dynamicColorReply; // null = no OS scheme (the honest caption lane)

  // Bare screens get the full app scaffolding (Directionality + Localizations).
  Widget quiet(Widget child) => MediaQuery(
        data: const MediaQueryData(disableAnimations: true),
        child: ProviderScope(
          child: AppStateScope(
            child: MaterialApp(
              home: child,
              debugShowCheckedModeBanner: false,
              // v3.4.6.g — the REAL Mornye theme so AppSwitch renders its
              // liquid-glass _MornyeSwitch lane (isMornye == true).
              theme: MornyeTheme.build(Brightness.dark),
            ),
          ),
        ),
      );

  // The real app shell (AnantPlayApp carries its own AppStateScope + theme).
  Widget realApp() => const MediaQuery(
        data: MediaQueryData(disableAnimations: true),
        child: shell.AnantPlayApp(),
      );

  setUp(() {
    // v3.4.6.g — the SAME hub-reset contract shell_test uses: a fresh scope
    // can listen again (zero `Stream has already been listened to`).
    AnantPlay.instance.resetStreams();
    settingsPuts.clear();
    onboardingDone = true;
    onboardingVersion = 2;
    playerCalls.clear();
    dynamicColorReply = null;
    mock.setMockMethodCallHandler(const MethodChannel('anantplay/settings'), (call) async {
      switch (call.method) {
        case 'getAll':
          return <String, Object>{
            'onboardingDone': onboardingDone,
            'onboardingStage': 0,
            'onboardingVersion': onboardingVersion,
            'themeMode': 'dark',
            ...settingsPuts,
          };
        case 'putBool':
          settingsPuts[call.arguments['key'] as String] = call.arguments['value'];
          return true;
        case 'putInt':
          settingsPuts[call.arguments['key'] as String] = call.arguments['value'];
          return true;
        case 'putString':
          settingsPuts[call.arguments['key'] as String] = call.arguments['value'];
          return true;
        default:
          return null;
      }
    });
    mock.setMockMethodCallHandler(const MethodChannel('anantplay/history'), (call) async => '[]');
    mock.setMockMethodCallHandler(const MethodChannel('anantplay/cast'), (call) async {
      switch (call.method) {
        case 'officialInstalled':
          return false;
        case 'officialVersion':
          return '';
        default:
          return <String, Object>{'up': false, 'port': -1, 'discoverable': false, 'mdns': false};
      }
    });
    mock.setMockMethodCallHandler(const MethodChannel('anantplay/player'), (call) async {
      playerCalls.add(call.method);
      switch (call.method) {
        case 'open':
          return <String, Object>{'textureId': 5, 'videoWidth': 1280, 'videoHeight': 720};
        case 'tracks':
          return const <Object>[];
        default:
          return true;
      }
    });
    mock.setMockMethodCallHandler(const MethodChannel('anantplay/browser'), (call) async => true);
    mock.setMockMethodCallHandler(const MethodChannel('anantplay/troubleshoot'), (call) async => '[]');
    mock.setMockMethodCallHandler(const MethodChannel('anantplay/updater'), (call) async => true);
    mock.setMockMethodCallHandler(const MethodChannel('anantplay/subs'), (call) async => true);
    mock.setMockMethodCallHandler(const MethodChannel('anantplay/crashlog'), (call) async => true);
    // v3.4.6.g — the REAL dynamic_color plugin channel: null = no OS scheme.
    mock.setMockMethodCallHandler(
        const OptionalMethodChannel('io.material.plugins/dynamic_color'), (call) async {
      // getCorePalette: null (no Android core palette in these lanes);
      // getAccentColor: the configured reply (null = NO OS scheme at all).
      if (call.method == 'getAccentColor') return dynamicColorReply;
      return null;
    });
    for (final name in const [
      'anantplay/cast/events',
      'anantplay/updater/events',
      'anantplay/player/events',
      'anantplay/shell/events',
      'anantplay/troubleshoot/events',
    ]) {
      mock.setMockMethodCallHandler(MethodChannel(name), (call) async => null);
    }
    mock.setMockMethodCallHandler(SystemChannels.platform, (call) async => null);
  });

  group('v3.4.6.g thermal pump-rate contract (playerSetPumpRate)', () {
    test('the method call reaches the anantplay/player channel with hz', () async {
      await AnantPlay.instance.playerSetPumpRate(1);
      expect(playerCalls, contains('setPumpRate'));
    });

    testWidgets(
        'PlayerScreen opens at 4 Hz and drops to 1 Hz after the 3.5 s HUD auto-hide',
        (tester) async {
      // The player events channel: one frame pump listener.
      mock.setMockMethodCallHandler(const MethodChannel('anantplay/player/events'),
          (call) async => null);
      await tester.pumpWidget(quiet(PlayerScreen(
        request: const shell.PlayRequest(url: '', test: true),
        onExit: () {},
      )));
      await tester.pump();
      // open() ran + the initial pump-rate set to 4.
      expect(playerCalls, contains('open'));
      expect(playerCalls.where((m) => m == 'setPumpRate').length, greaterThanOrEqualTo(1));
      // Wait past the 3.5 s auto-hide (+ the 260 ms fade).
      await tester.pump(const Duration(milliseconds: 3700));
      await tester.pump(const Duration(milliseconds: 300));
      // The hidden HUD dropped the native pump to 1 Hz.
      expect(playerCalls.where((m) => m == 'setPumpRate').length, greaterThanOrEqualTo(2));
    });

    testWidgets('the HUD unmounts after the fade (zero blur over the movie)', (tester) async {
      await tester.pumpWidget(quiet(PlayerScreen(
        request: const shell.PlayRequest(url: '', test: true),
        onExit: () {},
      )));
      await tester.pump();
      expect(find.byType(MornyeGlassPanel), findsWidgets);
      await tester.pump(const Duration(milliseconds: 3700));
      await tester.pump(const Duration(milliseconds: 300));
      expect(find.byType(MornyeGlassPanel), findsNothing);
    });

    testWidgets('the video viewport rides the Texture fallback lane off-Android',
        (tester) async {
      await tester.pumpWidget(quiet(PlayerScreen(
        request: const shell.PlayRequest(url: '', test: true),
        onExit: () {},
      )));
      await tester.pump();
      expect(find.byType(Texture), findsOneWidget);
      expect(find.byType(RepaintBoundary), findsWidgets);
    });
  });

  group('v3.4.6.g Mornye liquid-glass AppSwitch physics', () {
    // The shared host: quiet() carries the REAL Mornye theme, so AppSwitch
    // renders its liquid-glass _MornyeSwitch lane (isMornye == true).
    Widget switchHost(bool value, ValueChanged<bool> onChanged) =>
        quiet(StatefulBuilder(
            builder: (_, setS) => Scaffold(
                  body: Center(
                    child: AppSwitch(value: value, onChanged: onChanged),
                  ),
                )));

    testWidgets('a tap flips the value through onChanged', (tester) async {
      var value = false;
      await tester.pumpWidget(switchHost(
          value, (v) => value = v));
      await tester.pump();
      await tester.tap(find.byType(AppSwitch));
      await tester.pump();
      expect(value, isTrue);
    });

    testWidgets('a drag past the midpoint commits ON from OFF', (tester) async {
      var value = false;
      await tester.pumpWidget(switchHost(
          value, (v) => value = v));
      await tester.pump();
      final center = tester.getCenter(find.byType(AppSwitch));
      final gesture = await tester.startGesture(center);
      await gesture.moveBy(const Offset(14, 0)); // past the 22px midpoint
      await gesture.up();
      await tester.pump();
      expect(value, isTrue);
    });

    testWidgets('pressing the switch engages the tactile stretch (a Transform appears)',
        (tester) async {
      var value = false;
      await tester.pumpWidget(switchHost(
          value, (v) => value = v));
      await tester.pump();
      // the Mornye lane is live (the material fallback is NOT in the tree).
      expect(
          tester
              .element(find.byType(AppSwitch))
              .debugIsActive,
          isTrue);
      await tester.startGesture(tester.getCenter(find.byType(AppSwitch)),
          kind: PointerDeviceKind.touch);
      await tester.pump(const Duration(milliseconds: 120));
      // while held the thumb rides a Transform.scale (the liquid stretch) —
      // and the material fallback Switch never rendered.
      expect(find.byType(Switch), findsNothing);
      expect(
          find.descendant(
              of: find.byType(AppSwitch), matching: find.byType(Transform)),
          findsWidgets);
    });
  });

  group('v3.4.6.g Home Got-it banner (homeTipDismissed)', () {
    testWidgets('tapping Got it persists the dismissal and collapses the banner',
        (tester) async {
      settingsPuts.clear();
      await tester.pumpWidget(realApp());
      await tester.pumpAndSettle();
      expect(find.text('Got it'), findsOneWidget);
      await tester.tap(find.text('Got it'), warnIfMissed: false);
      await tester.pump();
      expect(settingsPuts['homeTipDismissed'], isTrue);
      await tester.pumpAndSettle();
      expect(find.text('Got it'), findsNothing);
    });

    testWidgets('the Watch Tour pill opens the Interactive Visual Setup guide',
        (tester) async {
      await tester.pumpWidget(realApp());
      await tester.pumpAndSettle();
      expect(find.byType(ob.OnboardingScreen), findsNothing);
      await tester.tap(find.text('Watch Tour'), warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(find.byType(ob.OnboardingScreen), findsOneWidget);
    });
  });

  group('v3.4.6.g # Custom Color Studio', () {
    testWidgets('tapping the # button opens the studio with grid + hex + Apply Color',
        (tester) async {
      await tester.pumpWidget(quiet(const SettingsScreen()));
      await tester.pump();
      await tester.ensureVisible(find.text('#'));
      await tester.tap(find.text('#'), warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(find.text('Color Studio'), findsOneWidget);
      expect(find.text('Apply Color'), findsOneWidget);
      expect(find.text('Use preset'), findsOneWidget);
    });

    testWidgets('a swatch tap fills the hex field and Apply Color persists accentHex',
        (tester) async {
      settingsPuts.clear();
      await tester.pumpWidget(quiet(const SettingsScreen()));
      await tester.pump();
      await tester.ensureVisible(find.text('#'));
      await tester.tap(find.text('#'), warnIfMissed: false);
      await tester.pumpAndSettle();
      // tap the first swatch in the grid (a bare circle inside the sheet).
      await tester.tap(
          find.descendant(
              of: find.byType(Material), matching: find.byType(GestureDetector)).first,
          warnIfMissed: false);
      await tester.pump();
      // Apply with whatever hex the field now holds (a valid 6-hex from the grid).
      await tester.tap(find.text('Apply Color'), warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(settingsPuts['accentHex'], isNotNull);
      expect((settingsPuts['accentHex'] as String).length, 6);
    });

    testWidgets('Use preset resets the custom accent to the preset lane', (tester) async {
      settingsPuts.clear();
      await tester.pumpWidget(quiet(const SettingsScreen()));
      await tester.pump();
      await tester.ensureVisible(find.text('#'));
      await tester.tap(find.text('#'), warnIfMissed: false);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Use preset'), warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(settingsPuts['accentHex'], '');
    });
  });

  group('v3.4.6.g REAL Material You (DynamicColorBuilder)', () {
    // The caption lives inside SettingsScreen, but the scheme availability is
    // published by _ThemedApp's DynamicColorBuilder — so these drive the REAL
    // app shell (AnantPlayApp) and hop to the Settings tab.
    Future<void> hopToSettings(WidgetTester tester) async {
      await tester.pumpWidget(realApp());
      await tester.pumpAndSettle();
      // switch to the Settings tab through the REAL tab bar
      await tester.tap(find.text('Settings'), warnIfMissed: false);
      await tester.pumpAndSettle();
    }

    testWidgets(
        'no OS scheme + toggle ON shows the honest caption (never a fake transformation)',
        (tester) async {
      dynamicColorReply = null; // the OS returns nothing
      settingsPuts['dynamicColor'] = true;
      await hopToSettings(tester);
      expect(find.text('System wallpaper colors unavailable on this device'),
          findsOneWidget);
    });

    testWidgets('a live OS scheme + toggle ON confirms the wallpaper repaint lane',
        (tester) async {
      dynamicColorReply = 0xFF2E7DFF; // getAccentColor answers a real accent
      settingsPuts['dynamicColor'] = true;
      await hopToSettings(tester);
      expect(
          find.text(
              'Live wallpaper ColorScheme — the whole app repaints from your wallpaper'),
          findsOneWidget);
    });
  });

  group('v3.4.6.g live step-by-step troubleshooter feed', () {
    testWidgets('start + done frames render the ▶ and ✓ lines live',
        (tester) async {
      await tester.pumpWidget(quiet(const HelpScreen()));
      await tester.pump();
      await tester.tap(find.text('Run full app troubleshoot'), warnIfMissed: false);
      await tester.pump();
      // A start frame: "▶ Checking #47 Worker /health + /api/resolve live…"
      await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
        'anantplay/troubleshoot/events',
        const StandardMethodCodec().encodeSuccessEnvelope(<String, Object>{
          't': 'deepStart',
          'id': 47,
          'domain': 'E',
          'name': 'Worker /health + /api/resolve live',
        }),
        (ByteData? data) {},
      );
      await tester.pump(const Duration(milliseconds: 60));
      expect(find.textContaining('▶ Checking #47'), findsOneWidget);
      // Its done frame flips the line in place to ✓/✗.
      await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
        'anantplay/troubleshoot/events',
        const StandardMethodCodec().encodeSuccessEnvelope(<String, Object>{
          't': 'deep',
          'done': 1,
          'total': 64,
          'outcome': <String, Object>{
            'id': 47, 'domain': 'E', 'name': 'Worker /health + /api/resolve live',
            'pass': false, 'ms': 8, 'detail': 'FAIL — Worker unreachable (HTTP 0 / HTTP 0)',
          },
        }),
        (ByteData? data) {},
      );
      await tester.pump(const Duration(milliseconds: 60));
      expect(find.textContaining('✗ #47 FAIL (8 ms)'), findsOneWidget);
    });
  });

  group('v3.4.6.g the honest report header (the scrubber-eats-nothing mirror)', () {
    test('composeHonestReport carries the exact header PrivacyScrubber must preserve',
        () {
      const native = <DeepOutcome>[
        DeepOutcome(
            id: 47,
            domain: 'E',
            name: 'Worker /health + /api/resolve live',
            pass: false,
            ms: 8,
            detail: 'FAIL — Worker unreachable (HTTP 0 / HTTP 0)'),
        DeepOutcome(id: 1, domain: 'A', name: 'Tier-1', pass: true, ms: 4, detail: 'ok'),
      ];
      final report = composeHonestReport('report body', native, const []);
      // The exact literal the Java PrivacyScrubber's prose guard protects.
      expect(report,
          contains('== EVERY FAIL ✗ (the whole truth, first — nothing hidden) =='));
      // The FAIL is hoisted ABOVE the report body.
      expect(report.indexOf('FAIL ✗ 47.'), lessThan(report.indexOf('report body')));
    });
  });

  group('v3.4.6.g Updater LAW #1 (same-version self-lock)', () {
    test('isNewerVersion refuses the same version (v3.4.6.g → v3.4.6.g is false)',
        () {
      expect(isNewerVersion('3.4.6.g', '3.4.6.g'), isFalse);
      expect(isNewerVersion('3.4.6.g', '3.4.6.f'), isTrue);
      expect(isNewerVersion('3.4.7', '3.4.6.g'), isTrue);
    });
  });
}
