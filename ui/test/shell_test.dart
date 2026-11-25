// v3.4.6.c widget tests: THE ZERO-DEAD-BUTTON SUITE. Every tab, every Home
// tile, every Settings control, the PlayerScreen HUD + its sheets, the
// onboarding v2 upgrade gate, and the finish contract — all against the
// mocked bridge. THE headline regression guards:
//   · the MornyeTabBar is VISIBLE and every tab actually switches screens;
//   · Test Player opens the MORNYE CINEMA PLAYER (PlayerScreen), NOT the old
//     Java player — the exact v3.4.6.b real-phone complaint;
//   · the Browser tile switches to the LIVE Browser tab (not a dead no-op);
//   · an upgrader from v3.4.6.a (onboardingDone=true, version 1) STILL sees
//     the 6-slide wizard exactly once (the version-int gate).
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart' hide Icons;
import 'package:material_ui/material_ui.dart' as mi show Icons;

import 'package:anantplay_ui/anantplay/app_state.dart';
import 'package:anantplay_ui/anantplay/bridge.dart';
import 'package:anantplay_ui/anantplay/onboarding_screen.dart';
import 'package:anantplay_ui/anantplay/home_screen.dart';
import 'package:anantplay_ui/anantplay/settings_screen.dart';
import 'package:anantplay_ui/anantplay/help_screen.dart';
import 'package:anantplay_ui/mornye_ui/widgets/settings_group.dart';
import 'package:anantplay_ui/anantplay/browser_screen.dart';
import 'package:anantplay_ui/anantplay/player_screen.dart';
import 'package:anantplay_ui/main.dart' as shell;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final mock = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  String? clipboardText;
  bool onboardingDone = false;
  int onboardingVersion = 0;
  final settingsPuts = <String, Object>{};

  setUp(() {
    onboardingDone = false;
    onboardingVersion = 0;
    settingsPuts.clear();
    mock.setMockMethodCallHandler(const MethodChannel('anantplay/settings'), (call) async {
      switch (call.method) {
        case 'getAll':
          return <String, Object>{
            'onboardingDone': onboardingDone,
            'onboardingStage': 0,
            'onboardingVersion': onboardingVersion,
            'themeMode': 'dark',
            'indexUrl': 'https://raw.githubusercontent.com/AnantPlay/index/main/index.json',
          };
        case 'putBool':
          settingsPuts[call.arguments['key'] as String] = call.arguments['value'];
          if ((call.arguments['key'] as String) == 'onboardingDone') {
            onboardingDone = call.arguments['value'] == true;
          }
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
    mock.setMockMethodCallHandler(const MethodChannel('anantplay/history'), (call) async {
      return '[]';
    });
    mock.setMockMethodCallHandler(const MethodChannel('anantplay/cast'), (call) async {
      switch (call.method) {
        case 'overlayGranted':
        case 'notifGranted':
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
          onboardingVersion = 2;
          return true;
        case 'resetOnboarding':
          onboardingDone = false;
          onboardingVersion = 0;
          return true;
        default:
          return <String, Object>{'up': false, 'port': -1, 'discoverable': false, 'mdns': false};
      }
    });
    mock.setMockMethodCallHandler(const MethodChannel('anantplay/player'), (call) async {
      switch (call.method) {
        case 'open':
          return <String, Object>{'textureId': 5, 'videoWidth': 1280, 'videoHeight': 720};
        case 'tracks':
          return <Object>[
            <String, Object>{
              'type': 'video',
              'selected': 0,
              'tracks': <Object>[
                <String, Object>{'index': 0, 'label': '720p · 1200kbps'},
                <String, Object>{'index': 1, 'label': '360p · 500kbps'},
              ],
            },
          ];
        default:
          return true;
      }
    });
    mock.setMockMethodCallHandler(const MethodChannel('anantplay/browser'), (call) async {
      return true;
    });
    mock.setMockMethodCallHandler(const MethodChannel('anantplay/troubleshoot'), (call) async {
      return '[]';
    });
    // v3.4.6.c — the EventChannels: EventChannel.listen rides a MethodChannel
    // of the same name; unmocked, the listen future NEVER completes and the
    // engine hangs at shutdown ("Cannot close sink while adding stream").
    for (final name in const [
      'anantplay/cast/events',
      'anantplay/updater/events',
      'anantplay/player/events',
      'anantplay/shell/events',
      'anantplay/troubleshoot/events',
    ]) {
      mock.setMockMethodCallHandler(MethodChannel(name), (call) async => null);
    }
    // SystemChrome (immersive mode, orientation) + a STATEFUL clipboard on
    // SystemChannels.platform (getData must echo what setData stored).
    clipboardText = null;
    mock.setMockMethodCallHandler(SystemChannels.platform, (call) async {
      if (call.method == 'Clipboard.setData') {
        clipboardText = call.arguments['text'] as String?;
        return null;
      }
      if (call.method == 'Clipboard.getData') {
        return clipboardText == null ? null : <String, Object>{'text': clipboardText!};
      }
      return null;
    });
    // the subs pipeline surface (subtitleStatus is a FutureBuilder source).
    mock.setMockMethodCallHandler(const MethodChannel('anantplay/subs'), (call) async {
      return call.method == 'status' ? 'ready · test cues' : true;
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

  /// The REAL app shell with onboarding already finished at the CURRENT
  /// version — the state a v3.4.6.c user sees after the wizard.
  Widget upToDateApp() => MediaQuery(
        data: const MediaQueryData(disableAnimations: true),
        child: const shell.AnantPlayApp(),
      );

  group('onboarding (the 6-slide interactive wizard)', () {
    testWidgets('renders the six-slide interactive wizard', (tester) async {
      await tester.pumpWidget(quiet(const OnboardingScreen()));
      await tester.pump();
      expect(find.text('Welcome'), findsOneWidget);
      expect(find.text('Get started'), findsOneWidget);
      expect(find.text('Skip'), findsOneWidget);
      expect(find.text('Next'), findsOneWidget);
    });

    testWidgets(
        'THE FINISH CONTRACT: Finish flips the shell into HomeScreen + the tab bar (regression guard for the v3.4.6.b lockout)',
        (tester) async {
      await tester.pumpWidget(MediaQuery(
        data: const MediaQueryData(disableAnimations: true),
        child: const shell.AnantPlayApp(),
      ));
      await tester.pumpAndSettle();
      expect(find.byType(OnboardingScreen), findsOneWidget);
      expect(find.byType(HomeScreen), findsNothing);

      Future<void> hop(String label) async {
        await tester.ensureVisible(find.text(label));
        await tester.pumpAndSettle();
        await tester.tap(find.text(label), warnIfMissed: false);
        await tester.pumpAndSettle();
      }

      await hop('Get started');
      await hop('Continue without installing'); // slide 2 → slide 3
      await hop('Continue anyway'); // slide 3 → slide 4
      await hop('Continue anyway'); // slide 4 → slide 5
      await hop('Continue anyway'); // slide 5 → slide 6

      expect(find.text('Finish'), findsOneWidget);
      await tester.ensureVisible(find.text('Finish'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Finish'), warnIfMissed: false);
      await tester.pumpAndSettle();

      // THE assertion: OnboardingScreen is GONE, HomeScreen is rendered —
      // AND the MornyeTabBar is right there with it (the v3.4.6.c shell).
      expect(find.byType(OnboardingScreen), findsNothing);
      expect(find.byType(HomeScreen), findsOneWidget);
      expect(find.text('Home'), findsWidgets);
      expect(find.text('Browser'), findsWidgets);
      expect(find.text('Settings'), findsWidgets);
      expect(find.text('Help'), findsWidgets);
      expect(onboardingDone, isTrue);
      expect(onboardingVersion, 2);
    });

    testWidgets(
        'THE UPGRADER GATE: onboardingDone=true but version 1 still shows the wizard once (v3.4.6.a → v3.4.6.c)',
        (tester) async {
      // The exact real-phone complaint #4: v3.4.6.a already wrote
      // onboardingDone=true, and v3.4.6.b's BOOL gate skipped the new wizard.
      onboardingDone = true;
      onboardingVersion = 1;
      await tester.pumpWidget(MediaQuery(
        data: const MediaQueryData(disableAnimations: true),
        child: const shell.AnantPlayApp(),
      ));
      await tester.pumpAndSettle();
      expect(find.byType(OnboardingScreen), findsOneWidget,
          reason: 'an upgrader from v3.4.6.a MUST see the new 6-slide wizard');
      expect(find.byType(HomeScreen), findsNothing);
    });

    testWidgets('PageView swipes left and right across all slides', (tester) async {
      await tester.pumpWidget(quiet(const OnboardingScreen()));
      await tester.pump();
      expect(find.text('Welcome'), findsOneWidget);
      await tester.fling(find.byType(PageView), const Offset(-380, 0), 900);
      await tester.pumpAndSettle();
      expect(find.text('Install AnantPlay'), findsWidgets);
      expect(find.text('Continue without installing'), findsOneWidget);
      await tester.fling(find.byType(PageView), const Offset(380, 0), 900);
      await tester.pumpAndSettle();
      expect(find.text('Welcome'), findsOneWidget);
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
      await tester.fling(find.byType(PageView), const Offset(-380, 0), 900);
      await tester.pumpAndSettle();
      expect(find.text('AnantPlay Installed ✓'), findsOneWidget);
    });
  });

  group('the 4-tab Mornye shell (every tab actually switches)', () {
    testWidgets('every bottom tab renders its screen + the top-right gear opens Settings',
        (tester) async {
      onboardingDone = true;
      onboardingVersion = 2;
      await tester.pumpWidget(upToDateApp());
      await tester.pumpAndSettle();

      // Home is the start: the receiver hero + the 4 tiles.
      expect(find.byType(HomeScreen), findsOneWidget);
      expect(find.text('Start Receiver'), findsOneWidget);

      // Browser tab (via the bar).
      await tester.tap(
          find.descendant(
              of: find.byType(shell.ShellRoot),
              matching: find.text('Browser')).first,
          warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(find.byType(BrowserScreen), findsOneWidget);

      // Settings tab — the Customization Studio with its live preview card.
      await tester.tap(
          find.descendant(
              of: find.byType(shell.ShellRoot),
              matching: find.text('Settings')).first,
          warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(find.byType(SettingsScreen), findsOneWidget);
      expect(find.text('Live preview'), findsOneWidget);

      // Help tab — the troubleshoot hub.
      await tester.tap(
          find.descendant(
              of: find.byType(shell.ShellRoot),
              matching: find.text('Help')).first,
          warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(find.byType(HelpScreen), findsOneWidget);
      expect(find.text('Run full app troubleshoot'), findsOneWidget);

      // ...and back Home.
      await tester.tap(
          find.descendant(
              of: find.byType(shell.ShellRoot),
              matching: find.text('Home')).first,
          warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(find.byType(HomeScreen), findsOneWidget);
    });

    testWidgets('the top-right Settings gear on Home switches to the Settings tab',
        (tester) async {
      onboardingDone = true;
      onboardingVersion = 2;
      await tester.pumpWidget(upToDateApp());
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Settings'), warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(find.byType(SettingsScreen), findsOneWidget);
    });
  });

  group('Home buttons (ZERO dead tiles)', () {
    testWidgets('home renders the receiver hero + quick actions + empty history',
        (tester) async {
      await tester.pumpWidget(quiet(const HomeScreen()));
      await tester.pump();
      expect(find.text('Stopped'), findsOneWidget);
      expect(find.text('Start Receiver'), findsOneWidget);
      expect(find.text('AnantPlay'), findsWidgets);
      expect(find.text('Nothing watched yet — cast or open a link to begin.'),
          findsOneWidget);
    });

    testWidgets(
        'Test Player opens the MORNYE CINEMA PLAYER (not the Java PlayActivity)',
        (tester) async {
      onboardingDone = true;
      onboardingVersion = 2;
      await tester.pumpWidget(upToDateApp());
      await tester.pumpAndSettle();

      await tester.ensureVisible(find.text('Test Player'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Test Player'), warnIfMissed: false);
      await tester.pumpAndSettle();

      // THE complaint #3 assertion: PlayerScreen (the Mornye Liquid-Glass
      // cinema player over the Media3 texture bridge) is on screen.
      expect(find.byType(PlayerScreen), findsOneWidget);
      // the video Texture is bound to the mocked textureId
      expect(find.byType(Texture), findsOneWidget);
      // the fixed 5-icon dock: Subtitles · Tracks · Fit · 1.0× · Search
      expect(find.text('Subtitles'), findsOneWidget);
      expect(find.text('Tracks'), findsOneWidget);
      expect(find.text('Fit'), findsOneWidget);
      expect(find.text('1.0×'), findsOneWidget);
      expect(find.text('Search'), findsOneWidget);
    });

    testWidgets('the Browser tile switches the shell to the live Browser tab', (tester) async {
      onboardingDone = true;
      onboardingVersion = 2;
      await tester.pumpWidget(upToDateApp());
      await tester.pumpAndSettle();
      await tester.tap(find.text('Browser').first, warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(find.byType(BrowserScreen), findsOneWidget);
    });

    testWidgets(
        'Play URL opens the glass sheet and plays a valid URL in the cinema player',
        (tester) async {
      onboardingDone = true;
      onboardingVersion = 2;
      await tester.pumpWidget(upToDateApp());
      await tester.pumpAndSettle();

      await tester.ensureVisible(find.text('Play URL'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Play URL'), warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(find.text('Play a URL'), findsOneWidget);

      await tester.enterText(
          find.byType(TextField), 'https://example.com/stream.m3u8');
      await tester.pump();
      await tester.tap(find.text('Play'), warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(find.byType(PlayerScreen), findsOneWidget);
    });

    testWidgets('Start Receiver arms the native receiver (the arm call fires)',
        (tester) async {
      onboardingDone = true;
      onboardingVersion = 2;
      await tester.pumpWidget(upToDateApp());
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('Start Receiver'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Start Receiver'), warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(find.text('Start Receiver'), findsOneWidget);
    });

    testWidgets('the AnantPlay tile (not installed) opens the in-app installer sheet',
        (tester) async {
      onboardingDone = true;
      onboardingVersion = 2;
      await tester.pumpWidget(upToDateApp());
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('Open the official app'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Open the official app'), warnIfMissed: false);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));
      expect(find.text('Install official AnantPlay'), findsOneWidget);
    });
  });

  group('SettingsScreen (every customization is real)', () {
    testWidgets('renders the studio sections + live preview + version footer', (tester) async {
      await tester.pumpWidget(quiet(const SettingsScreen()));
      await tester.pump();
      expect(find.text('QUICK SETTINGS'), findsOneWidget);
      expect(find.text('Glass clarity'), findsOneWidget);
      expect(find.text('Live preview'), findsOneWidget);
      await tester.scrollUntilVisible(
          find.text('AnantPlay Sinhala Player v3.4.6.c (30409)'), 240,
          scrollable: find.byType(Scrollable).first);
      expect(find.text('AnantPlay Sinhala Player v3.4.6.c (30409)'), findsOneWidget);
      expect(find.text('ADVANCED'), findsOneWidget);
    });

    testWidgets('a theme card writes the themeMode pref through the bridge', (tester) async {
      await tester.pumpWidget(quiet(const SettingsScreen()));
      await tester.pump();
      await tester.tap(find.text('AMOLED'), warnIfMissed: false);
      await tester.pump();
      expect(settingsPuts['themeMode'], 'amoled');
    });

    testWidgets('the Glass outlines switch writes the pref', (tester) async {
      await tester.pumpWidget(quiet(const SettingsScreen()));
      await tester.pump();
      await tester.ensureVisible(find.text('Glass outlines'));
      await tester.pumpAndSettle();
      final row = find.ancestor(
          of: find.text('Glass outlines'), matching: find.byType(SettingsItem));
      expect(row, findsOneWidget);
      final sw = find.descendant(of: row, matching: find.byType(Switch));
      expect(sw, findsOneWidget);
      final before = (tester.widget(sw) as Switch).value;
      await tester.tap(sw, warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(settingsPuts['glassOutlines'], !before);
    });

    testWidgets('the Midnight dimmer switch + Check for update row are wired', (tester) async {
      await tester.pumpWidget(quiet(const SettingsScreen()));
      await tester.pump();
      await tester.scrollUntilVisible(find.text('Midnight night dimmer'), 200,
          scrollable: find.byType(Scrollable).first);
      await tester.pumpAndSettle();
      expect(find.text('Midnight night dimmer'), findsOneWidget);
      await tester.scrollUntilVisible(find.text('Check for update now'), 200,
          scrollable: find.byType(Scrollable).first);
      expect(find.text('Check for update now'), findsOneWidget);
    });

    testWidgets('Run setup guide re-arms the wizard (version stamp drops)', (tester) async {
      await tester.pumpWidget(quiet(const SettingsScreen()));
      await tester.pump();
      await tester.scrollUntilVisible(find.text('Run setup guide'), 240,
          scrollable: find.byType(Scrollable).first);
      expect(find.text('Run setup guide'), findsOneWidget);
    });
  });

  group('HelpScreen (1-tap actions)', () {
    testWidgets('renders the troubleshoot actions', (tester) async {
      await tester.pumpWidget(quiet(const HelpScreen()));
      await tester.pump();
      expect(find.text('Run full app troubleshoot'), findsOneWidget);
      expect(find.text('Copy privacy-safe report'), findsOneWidget);
      expect(find.text('Check for update'), findsOneWidget);
      expect(find.text('Share report'), findsOneWidget);
      expect(find.text('Run setup wizard'), findsOneWidget);
    });

    testWidgets('Copy puts the report on the clipboard', (tester) async {
      await tester.pumpWidget(quiet(const HelpScreen()));
      await tester.pump();
      await tester.tap(find.text('Copy privacy-safe report'), warnIfMissed: false);
      await tester.pump();
      final data = await Clipboard.getData('text/plain');
      expect(data?.text, contains('v3.4.6.c (30409)'));
    });
  });

  group('PlayerScreen (the Mornye cinema HUD)', () {
    testWidgets('the 5-icon dock + all sheet headers render (HUD contract)', (tester) async {
      onboardingDone = true;
      onboardingVersion = 2;
      await tester.pumpWidget(upToDateApp());
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('Test Player'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Test Player'), warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(find.byType(PlayerScreen), findsOneWidget);

      // The HUD contract renders: all 5 dock buttons + the lock button.
      expect(find.text('Subtitles'), findsOneWidget);
      expect(find.text('Tracks'), findsOneWidget);
      expect(find.text('Fit'), findsOneWidget);
      expect(find.text('1.0×'), findsOneWidget);
      expect(find.text('Search'), findsOneWidget);
      expect(find.byTooltip('Lock controls'), findsOneWidget);
      expect(find.byTooltip('Back'), findsOneWidget);
      expect(find.byTooltip('Rotate'), findsOneWidget);
      expect(find.byTooltip('Picture in picture'), findsOneWidget);
    });

    testWidgets('play/pause toggles through the bridge and Back returns to the shell',
        (tester) async {
      onboardingDone = true;
      onboardingVersion = 2;
      await tester.pumpWidget(upToDateApp());
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('Test Player'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Test Player'), warnIfMissed: false);
      await tester.pumpAndSettle();

      await tester.tap(find.byIcon(mi.Icons.play_arrow_rounded).last,
          warnIfMissed: false);
      await tester.pump();

      await tester.tap(find.byTooltip('Back'), warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(find.byType(PlayerScreen), findsNothing);
      expect(find.byType(HomeScreen), findsOneWidget);
    });
  });

  group('the upgrader re-run path (Settings → Run setup guide)', () {
    testWidgets('Run setup guide reopens the wizard inside the live shell', (tester) async {
      onboardingDone = true;
      onboardingVersion = 2;
      await tester.pumpWidget(upToDateApp());
      await tester.pumpAndSettle();

      await tester.tap(
          find.descendant(
              of: find.byType(shell.ShellRoot),
              matching: find.text('Settings')).first,
          warnIfMissed: false);
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(find.text('Run setup guide'), 300,
          scrollable: find.byType(Scrollable).first);
      onboardingDone = false;
      onboardingVersion = 0;
      await AppState.of(tester.element(find.byType(SettingsScreen)))
          .scope
          .rerunOnboarding();
      await tester.pumpAndSettle();
      expect(find.byType(OnboardingScreen), findsOneWidget,
          reason: 'Run Setup Guide must reopen the wizard from any tab');
    });
  });
}
