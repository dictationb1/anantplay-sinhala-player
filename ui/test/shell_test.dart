// v3.4.6 widget smoke tests: the Mornye shell renders its three surfaces
// (onboarding → home + settings + help) against the mocked bridge, with the
// Liquid-Glass Cinema onboarding art loading from the pure-SVG assets.
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'package:anantplay_ui/anantplay/app_state.dart';
import 'package:anantplay_ui/anantplay/bridge.dart';
import 'package:anantplay_ui/anantplay/onboarding_screen.dart';
import 'package:anantplay_ui/anantplay/home_screen.dart';
import 'package:anantplay_ui/anantplay/settings_screen.dart';
import 'package:anantplay_ui/anantplay/help_screen.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final mock = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  setUp(() {
    // a fresh-but-onboarded settings surface for the shell screens
    mock.setMockMethodCallHandler(const MethodChannel('anantplay/settings'), (call) async {
      switch (call.method) {
        case 'getAll':
          return <String, Object>{'onboardingDone': false, 'themeMode': 'dark'};
        default:
          return null;
      }
    });
    mock.setMockMethodCallHandler(const MethodChannel('anantplay/history'), (call) async {
      return '[]';
    });
    mock.setMockMethodCallHandler(const MethodChannel('anantplay/cast'), (call) async {
      if (call.method == 'overlayGranted') return false;
      return <String, Object>{'up': false, 'port': -1, 'discoverable': false, 'mdns': false};
    });
    mock.setMockMethodCallHandler(const MethodChannel('anantplay/troubleshoot'), (call) async {
      return '[]';
    });
    AnantPlay.instance.resetStreams();
  });

  Widget wrap(Widget child) => AppStateScope(
        child: MaterialApp(
          debugShowCheckedModeBanner: false,
          home: child,
        ),
      );

  testWidgets('onboarding renders the 5-stage wizard with Sinhala-safe art slots',
      (tester) async {
    await tester.pumpWidget(wrap(const OnboardingScreen()));
    await tester.pump();
    expect(find.text('Welcome'), findsOneWidget);
    expect(find.text('Get started'), findsOneWidget);
    // the real label walk: Get started → Continue → Open settings → Continue → Finish
    await tester.tap(find.text('Get started'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Continue'));
    await tester.pumpAndSettle();
    expect(find.text('Open settings'), findsOneWidget);
    await tester.tap(find.text('Open settings'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Continue'));
    await tester.pumpAndSettle();
    expect(find.text('Finish'), findsOneWidget);
  });

  testWidgets('home renders the receiver hero + quick actions + empty history',
      (tester) async {
    await tester.pumpWidget(wrap(const HomeScreen()));
    await tester.pump();
    expect(find.text('Receiver stopped'), findsOneWidget);
    expect(find.text('Start'), findsOneWidget);
    expect(find.text('AnantPlay'), findsWidgets);
    expect(find.text('Nothing watched yet — cast or open a link to begin.'), findsOneWidget);
  });

  testWidgets('settings renders the studio sections + version footer', (tester) async {
    await tester.pumpWidget(wrap(const SettingsScreen()));
    await tester.pump();
    expect(find.text('APPEARANCE'), findsOneWidget);
    expect(find.text('Glass clarity'), findsOneWidget);
    // the lazy ListView builds children on demand — scroll the studio open
    await tester.scrollUntilVisible(
        find.text('AnantPlay Sinhala Player v3.4.6 (30406)'), 240,
        scrollable: find.byType(Scrollable).first);
    expect(find.text('AnantPlay Sinhala Player v3.4.6 (30406)'), findsOneWidget);
    expect(find.text('BACKUP'), findsOneWidget);   // revealed by the scroll
  });

  testWidgets('help renders the troubleshoot actions', (tester) async {
    await tester.pumpWidget(wrap(const HelpScreen()));
    await tester.pump();
    expect(find.text('Run full app troubleshoot'), findsOneWidget);
    expect(find.text('Copy privacy-safe report'), findsOneWidget);
    expect(find.text('Check for update'), findsOneWidget);
  });
}

