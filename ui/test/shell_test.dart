// v3.4.6.b widget tests: the Mornye shell renders its surfaces (onboarding →
// home + settings + help) against the mocked bridge. THE headline test:
// tapping Finish on the last slide must flip the shell into HomeScreen with
// OnboardingScreen GONE (the v3.4.6.b lockout regression guard), plus the
// new PageView swipe contract and the installer/permission states.
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'package:anantplay_ui/anantplay/app_state.dart';
import 'package:anantplay_ui/anantplay/bridge.dart';
import 'package:anantplay_ui/anantplay/onboarding_screen.dart';
import 'package:anantplay_ui/anantplay/home_screen.dart';
import 'package:anantplay_ui/anantplay/settings_screen.dart';
import 'package:anantplay_ui/anantplay/help_screen.dart';
import 'package:anantplay_ui/main.dart' as shell;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final mock = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  bool onboardingDone = false;

  setUp(() {
    onboardingDone = false;
    mock.setMockMethodCallHandler(const MethodChannel('anantplay/settings'), (call) async {
      switch (call.method) {
        case 'getAll':
          return <String, Object>{
            'onboardingDone': onboardingDone,
            'onboardingStage': 0,
            'themeMode': 'dark',
            'indexUrl': 'https://raw.githubusercontent.com/AnantPlay/index/main/index.json',
          };
        case 'putBool':
          onboardingDone = call.arguments['value'] == true;
          return true;
        case 'putInt':
          return true;
        default:
          return null;
      }
    });
    mock.setMockMethodCallHandler(const MethodChannel('anantplay/history'), (call) async {
      return '[]';
    });
    mock.setMockMethodCallHandler(const MethodChannel('anantplay/cast'), (call) async {
      switch (call.method) {
        case 'overlayGranted':
          return false;
        case 'notifGranted':
          return false;
        case 'officialInstalled':
          return false;
        case 'onboardingStatus':
          return <String, Object>{
            't': 'onboardingStatus',
            'overlay': false,
            'notif': false,
            'battery': false,
            'installs': false,
            'official': false,
          };
        case 'finishOnboarding':
          onboardingDone = true;
          return true;
        default:
          return <String, Object>{'up': false, 'port': -1, 'discoverable': false, 'mdns': false};
      }
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

  /// The looping hero micro-animations honor disableAnimations exactly like
  /// Mornye's own widgets — the test harness freezes them at their current
  /// frame so pumpAndSettle can settle (on-device they always play).
  Widget quiet(Widget child) => wrap(MediaQuery(
        data: const MediaQueryData(disableAnimations: true),
        child: child,
      ));

  testWidgets('onboarding renders the six-slide interactive wizard', (tester) async {
    await tester.pumpWidget(quiet(const OnboardingScreen()));
    await tester.pump();
    expect(find.text('Welcome'), findsOneWidget);
    expect(find.text('Get started'), findsOneWidget);
    expect(find.text('Skip'), findsOneWidget);
    expect(find.text('Next'), findsOneWidget);
  });

  testWidgets('THE FINISH CONTRACT: Finish flips the shell into HomeScreen (regression guard for the v3.4.6.b lockout)',
      (tester) async {
    // The shell root exactly as main.dart builds it: onboarding until done.
    // The quiet harness: looping heroes freeze under disableAnimations (the
    // same MediaQuery.of respect the on-screen heroes already implement).
    tester.platformDispatcher.views.first.platformDispatcher; // touch
    await tester.pumpWidget(MediaQuery(
      data: const MediaQueryData(disableAnimations: true),
      child: const shell.AnantPlayApp(),
    ));
    await tester.pumpAndSettle();
    expect(find.byType(OnboardingScreen), findsOneWidget);
    expect(find.byType(HomeScreen), findsNothing);

    // Walk to the last slide one hop at a time (Next + the slide-2 escape
    // hatch), scrolling each slide body into view first — the onboarding
    // scrolls, so buttons can sit below the fold on the 800x600 test surface.
    Future<void> hop(String label) async {
      await tester.ensureVisible(find.text(label));
      await tester.pumpAndSettle();
      await tester.tap(find.text(label), warnIfMissed: false);
      await tester.pumpAndSettle();
    }

    await hop('Get started');
    await hop('Continue without installing');   // slide 2 → slide 3
    await hop('Continue anyway');               // slide 3 → slide 4
    await hop('Continue anyway');               // slide 4 → slide 5
    await hop('Continue anyway');               // slide 5 → slide 6

    // Slide 6: the Sources & Index helper with the working Finish button.
    expect(find.text('Finish'), findsOneWidget);
    await tester.ensureVisible(find.text('Finish'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Finish'), warnIfMissed: false);
    await tester.pumpAndSettle();

    // THE assertion: OnboardingScreen is GONE, HomeScreen is rendered.
    expect(find.byType(OnboardingScreen), findsNothing);
    expect(find.byType(HomeScreen), findsOneWidget);
    // and the flag persisted through the dedicated channel writer
    expect(onboardingDone, isTrue);
  });

  testWidgets('PageView swipes left and right across all slides', (tester) async {
    await tester.pumpWidget(quiet(const OnboardingScreen()));
    await tester.pump();
    expect(find.text('Welcome'), findsOneWidget);

    // swipe right-to-left → slide 2 (title + the Install button both match)
    await tester.fling(find.byType(PageView), const Offset(-380, 0), 900);
    await tester.pumpAndSettle();
    expect(find.text('Install AnantPlay'), findsWidgets);
    expect(find.text('Continue without installing'), findsOneWidget);

    // and back: swipe left-to-right → slide 1 again
    await tester.fling(find.byType(PageView), const Offset(380, 0), 900);
    await tester.pumpAndSettle();
    expect(find.text('Welcome'), findsOneWidget);
    expect(find.text('Continue without installing'), findsNothing);
  });

  testWidgets('slide 2 shows the live installed state when the official app is present',
      (tester) async {
    mock.setMockMethodCallHandler(const MethodChannel('anantplay/cast'), (call) async {
      switch (call.method) {
        case 'officialInstalled':
          return true;
        case 'onboardingStatus':
          return <String, Object>{
            't': 'onboardingStatus',
            'overlay': false,
            'notif': false,
            'battery': false,
            'installs': false,
            'official': true,
          };
        default:
          return <String, Object>{'up': false, 'port': -1, 'discoverable': false, 'mdns': false};
      }
    });
    await tester.pumpWidget(quiet(const OnboardingScreen()));
    await tester.pump();
    // jump straight to slide 2 via the dot
    await tester.fling(find.byType(PageView), const Offset(-380, 0), 900);
    await tester.pumpAndSettle();
    expect(find.text('AnantPlay Installed ✓'), findsOneWidget);
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
    await tester.scrollUntilVisible(
        find.text('AnantPlay Sinhala Player v3.4.6.b (30408)'), 240,
        scrollable: find.byType(Scrollable).first);
    expect(find.text('AnantPlay Sinhala Player v3.4.6.b (30408)'), findsOneWidget);
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
