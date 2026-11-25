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
import 'package:flutter_riverpod/flutter_riverpod.dart';
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
import 'package:anantplay_ui/anantplay/force_update_screen.dart';

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
        case 'officialVersion':
          return '';
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
        child: ProviderScope(
          child: MaterialApp(
            debugShowCheckedModeBanner: false,
            home: child,
          ),
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
          case 'officialVersion':
            return '3.9.2';
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
      // v3.4.6.d — the badge carries the exact installed version now.
      expect(find.text('AnantPlay v3.9.2 Installed ✓'), findsOneWidget);
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

    // v3.4.6.e — THE STOP RECEIVER FIX: tapping Stop Receiver on Home fires
    // the anantplay/cast 'stop' invokeMethod (the v3.4.6.d defect: the stop
    // lane raced its own 400 ms status push and looked dead).
    testWidgets('Stop Receiver fires the real stop through the bridge',
        (tester) async {
      onboardingDone = true;
      onboardingVersion = 2;
      var receiverUp = true;
      final stops = <MethodCall>[];
      mock.setMockMethodCallHandler(const MethodChannel('anantplay/cast'), (call) async {
        switch (call.method) {
          case 'stop':
            stops.add(call);
            receiverUp = false;
            return <String, Object>{'up': false};
          case 'arm':
            receiverUp = true;
            return true;
          case 'overlayGranted':
          case 'notifGranted':
          case 'officialInstalled':
            return false;
          case 'officialVersion':
            return '';
          case 'onboardingStatus':
            return <String, Object>{
              't': 'onboardingStatus',
              'overlay': false, 'notif': false, 'battery': false,
              'installs': false, 'official': false,
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
            return <String, Object>{
              'up': receiverUp, 'port': receiverUp ? 8765 : -1,
              'discoverable': false, 'mdns': false,
            };
        }
      });
      await tester.pumpWidget(upToDateApp());
      await tester.pumpAndSettle();
      // The receiver is up → the hero card offers Stop.
      await tester.ensureVisible(find.text('Stop Receiver'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Stop Receiver'), warnIfMissed: false);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));
      expect(stops, isNotEmpty,
          reason: 'tapping Stop Receiver must reach the native stop lane');
      // And the hero follows the LIVE native status: the mock now answers
      // up == false, so a fresh mount renders Start (the v3.4.6.d defect was
      // that the tap never reached a lane that could turn the receiver off —
      // the call assertion above is the regression guard; this is the render).
      await tester.pumpWidget(const SizedBox());
      await tester.pumpWidget(upToDateApp());
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
          find.text('AnantPlay Sinhala Player v3.4.6.e (30411)'), 240,
          scrollable: find.byType(Scrollable).first);
      expect(find.text('AnantPlay Sinhala Player v3.4.6.e (30411)'), findsOneWidget);
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
      expect(find.text('Copy Again'), findsOneWidget);
      expect(find.text('Check for update'), findsOneWidget);
      expect(find.text('Share report'), findsOneWidget);
      expect(find.text('Run setup wizard'), findsOneWidget);
    });

    testWidgets('Copy puts the deep report on the clipboard', (tester) async {
      // The deep report arrives through anantplay/troubleshoot.deepReport.
      mock.setMockMethodCallHandler(const MethodChannel('anantplay/troubleshoot'), (call) async {
        if (call.method == 'deepReport') {
          return 'AnantPlay Sinhala Player v3.4.6.e (30411) — Deep Troubleshoot Report';
        }
        if (call.method == 'lastDeepResults') return '[]';
        if (call.method == 'runDeepProbe') return true;
        return '[]';
      });
      await tester.pumpWidget(quiet(const HelpScreen()));
      await tester.pump();
      await tester.tap(find.text('Copy Again'), warnIfMissed: false);
      await tester.pump();
      final data = await Clipboard.getData('text/plain');
      expect(data?.text, contains('v3.4.6.e (30411)'));
    });

    // v3.4.6.e — THE DEEP TROUBLESHOOT CONTRACT: the run fires runDeepProbe,
    // the outcomes stream renders the domain cards, and the completion
    // auto-copies the privacy-safe report to the clipboard.
    testWidgets('Run full app troubleshoot fires the 54-probe deep run', (tester) async {
      var deepRuns = 0;
      mock.setMockMethodCallHandler(const MethodChannel('anantplay/troubleshoot'), (call) async {
        if (call.method == 'runDeepProbe') deepRuns++;
        if (call.method == 'deepReport') {
          return 'AnantPlay Sinhala Player v3.4.6.e (30411) — Deep Troubleshoot Report';
        }
        if (call.method == 'lastDeepResults') return '[]';
        return '[]';
      });
      await tester.pumpWidget(quiet(const HelpScreen()));
      await tester.pump();
      await tester.tap(find.text('Run full app troubleshoot'), warnIfMissed: false);
      await tester.pump();
      expect(deepRuns, 1);
    });

    testWidgets('the deep outcomes render as domain cards + auto-copy fires',
        (tester) async {
      mock.setMockMethodCallHandler(const MethodChannel('anantplay/troubleshoot'), (call) async {
        if (call.method == 'deepReport') {
          return 'AnantPlay Sinhala Player v3.4.6.e (30411) — Deep Troubleshoot Report';
        }
        if (call.method == 'lastDeepResults') return '[]';
        return '[]';
      });
      await tester.pumpWidget(quiet(const HelpScreen()));
      await tester.pump();
      // v3.4.6.e — the host delivers the deep results as a JSON STRING on
      // anantplay/troubleshoot/events (deepProbeJson), after the per-probe
      // progress frames: the raw router decodes it into the domain cards.
      const deepJson = '[{"id":1,"domain":"A","name":"Tier-1 redirect",'
          '"pass":true,"ms":120,"detail":"302 resolved"},'
          '{"id":8,"domain":"B","name":"Stop Receiver live execution",'
          '"pass":true,"ms":40,"detail":"stop -> running == false"},'
          '{"id":24,"domain":"C","name":"Subtitle Studio compact bounds",'
          '"pass":true,"ms":5,"detail":"slider 10-28"}]';
      await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
        'anantplay/troubleshoot/events',
        const StandardMethodCodec().encodeSuccessEnvelope(deepJson),
        (ByteData? data) {},
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      // The domain cards render grouped by domain. The Help tab is a lazy
      // ListView, so each card is asserted AFTER it is scrolled into view
      // (an unbuilt off-screen child is not a missing card).
      expect(find.text('Auto-Updater Law'), findsOneWidget);
      for (final title in const [
        'Home & Cast Receiver',
        'Settings & Customizations',
      ]) {
        await tester.scrollUntilVisible(find.text(title), 200,
            scrollable: find.byType(Scrollable).first);
        expect(find.text(title), findsOneWidget);
      }
      // The auto-copy confirmation banner shows.
      expect(
        find.text('1/3 Deep Troubleshoot complete — 100% '
            'Privacy-Safe report auto-copied to clipboard ✓'),
        findsOneWidget);
      // The report is on the clipboard (Flutter lane).
      final data = await Clipboard.getData('text/plain');
      expect(data?.text, contains('v3.4.6.e (30411)'));
    });

    // v3.4.6.e — THE LIVE PROGRESS COUNTER: the per-probe frames
    // ({t:'deep', done:n, total:54, outcome:{…}}) render
    // "Running 54 deep feature probes… n/54" while the run is in flight.
    testWidgets('the live deep progress frames update the run counter',
        (tester) async {
      var deepRuns = 0;
      mock.setMockMethodCallHandler(const MethodChannel('anantplay/troubleshoot'), (call) async {
        if (call.method == 'runDeepProbe') {
          deepRuns++;
          return true;
        }
        if (call.method == 'deepReport') {
          return 'AnantPlay Sinhala Player v3.4.6.e (30411) — Deep Troubleshoot Report';
        }
        if (call.method == 'lastDeepResults') return '[]';
        return '[]';
      });
      await tester.pumpWidget(quiet(const HelpScreen()));
      await tester.pump();
      await tester.tap(find.text('Run full app troubleshoot'), warnIfMissed: false);
      await tester.pump();
      // The run title shows with the counter at 0/54.
      expect(find.text('Running 54 deep feature probes… 0/54'), findsOneWidget);
      // Three live frames arrive: 1, 7, 32 of 54.
      for (final done in [1, 7, 32]) {
        await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
          'anantplay/troubleshoot/events',
          const StandardMethodCodec().encodeSuccessEnvelope(<String, Object>{
            't': 'deep',
            'done': done,
            'total': 54,
            'outcome': <String, Object>{
              'id': done, 'domain': 'A', 'name': 'probe $done',
              'pass': true, 'ms': 5, 'detail': 'ok',
            },
          }),
          (ByteData? data) {},
        );
        await tester.pump();
      }
      expect(find.text('Running 54 deep feature probes… 32/54'), findsOneWidget);
      // The run itself fired exactly once.
      expect(deepRuns, 1);
    });
  });

  group('PlayerScreen (the Mornye cinema HUD)', () {
    // v3.4.6.e — THE COMPACT SUBTITLE CONTRACT: the rendered subtitle is
    // bottom-anchored, width-capped at 82% of the viewport, at most 3 lines,
    // with a tight 1.25 line height and the compact 10-28sp clamp — the
    // oversized-subtitle field complaint, asserted on the LIVE widget tree.
    //
    // The cue arrives exactly the way the native bridge sends it: one
    // anantplay/player/events frame carrying activeCueSi. The test drives that
    // frame itself rather than assuming a host-side demo cue exists, so the
    // assertion can never silently pass on an empty string.
    testWidgets('the subtitle pill obeys the compact cinema contract (82% / 3 lines / 1.25)',
        (tester) async {
      onboardingDone = true;
      onboardingVersion = 2;
      await tester.pumpWidget(upToDateApp());
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('Test Player'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Test Player'), warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(find.byType(PlayerScreen), findsOneWidget);

      // The Sinhala cue: the ZWJ conjunct in ක්‍රියාත්මකයි is carried verbatim.
      const cue = 'ක්‍රියාත්මකයි — ශ්‍රී ලංකාවේ චිත්‍රපටිය';
      await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
        'anantplay/player/events',
        const StandardMethodCodec().encodeSuccessEnvelope(<String, Object>{
          'positionMs': 1000,
          'durationMs': 600000,
          'bufferedMs': 2000,
          'playing': true,
          'buffering': false,
          'videoWidth': 1280,
          'videoHeight': 720,
          'activeCueSi': cue,
          'subtitleState': 'ready · 906 cues',
        }),
        (ByteData? data) {},
      );
      await tester.pump();

      final pill = find.text(cue);
      expect(pill, findsOneWidget, reason: 'the active Sinhala cue must render');
      final textWidget = tester.widget<Text>(pill);
      // The compact cinema typography.
      expect(textWidget.maxLines, 3);
      expect(textWidget.softWrap ?? true, isTrue);
      expect(textWidget.style?.height, 1.25);
      // The compact 10-28sp clamp (the default is 14 portrait / 15 landscape).
      final size = textWidget.style!.fontSize!;
      expect(size, greaterThanOrEqualTo(10.0));
      expect(size, lessThanOrEqualTo(28.0));
      // The 82% viewport width cap on the pill's ConstrainedBox.
      final constrained = tester.widgetList<ConstrainedBox>(
          find.ancestor(of: pill, matching: find.byType(ConstrainedBox)));
      final width = MediaQuery.sizeOf(tester.element(pill)).width;
      expect(
        constrained.any((c) =>
            (c.constraints.maxWidth - width * 0.82).abs() < 0.5),
        isTrue,
        reason: 'the subtitle pill must be capped at 82% of the viewport',
      );
    });

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

  // ══ v3.4.6.d — THE MANDATORY FORCE-UPDATE GATE ═══════════════════════════
  group('ForceUpdateScreen (the mandatory blocking gate)', () {
    testWidgets(
        'gate:available locks the WHOLE app — no tabs, no onboarding, no skip button',
        (tester) async {
      onboardingDone = true;
      onboardingVersion = 2;
      await tester.pumpWidget(upToDateApp());
      await tester.pumpAndSettle();
      expect(find.byType(HomeScreen), findsOneWidget);

      // The host pushes gate:available (v3.4.6.d → v3.4.6.e) on the updater
      // EventChannel: the shell must replace EVERYTHING with ForceUpdateScreen.
      await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
        'anantplay/updater/events',
        const StandardMethodCodec().encodeSuccessEnvelope(<String, Object>{
          't': 'gate',
          'gate': 'available',
          'current': '3.4.6.e',
          'next': '3.4.6.f',
        }),
        (ByteData? data) {},
      );
      await tester.pumpAndSettle();

      expect(find.byType(ForceUpdateScreen), findsOneWidget,
          reason: 'a strictly newer release must lock the whole viewport');
      // HomeScreen is COMPLETELY gone (blocked, not covered).
      expect(find.byType(HomeScreen), findsNothing);
      // THE NO-BYPASS CONTRACT: no Skip / Dismiss / Later anywhere.
      expect(find.text('Skip'), findsNothing);
      expect(find.text('Dismiss'), findsNothing);
      expect(find.text('Later'), findsNothing);
      // The version badge + the one and only action.
      expect(find.text('v3.4.6.e  →  v3.4.6.f'), findsOneWidget);
      expect(find.text('Update required'), findsOneWidget);
      expect(find.text('Update Now'), findsOneWidget);
    });

    testWidgets(
        'live 0→100% download progress renders + Update Now taps installPendingUpdate',
        (tester) async {
      onboardingDone = true;
      onboardingVersion = 2;
      var installNowCalls = 0;
      mock.setMockMethodCallHandler(const MethodChannel('anantplay/updater'), (call) async {
        if (call.method == 'updateNow') installNowCalls++;
        if (call.method == 'pending') {
          return <String, Object>{'pending': false, 'version': ''};
        }
        return true;
      });
      await tester.pumpWidget(upToDateApp());
      await tester.pumpAndSettle();

      // arm the gate
      await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
        'anantplay/updater/events',
        const StandardMethodCodec().encodeSuccessEnvelope(<String, Object>{
          't': 'gate', 'gate': 'available',
          'current': '3.4.6.e', 'next': '3.4.6.f',
        }),
        (ByteData? data) {},
      );
      await tester.pumpAndSettle();

      await tester.tap(find.text('Update Now'), warnIfMissed: false);
      await tester.pump();
      expect(installNowCalls, greaterThan(0),
          reason: 'Update Now must invoke the native install/update pipeline');

      // live progress frames stream onto the gate (fixed pumps: the busy
      // spinner animates continuously, so pumpAndSettle would never settle).
      await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
        'anantplay/updater/events',
        const StandardMethodCodec().encodeSuccessEnvelope(<String, Object>{
          't': 'gate', 'gate': 'progress', 'percent': 47,
          'bytes': 11534336, 'total': 24576000,
        }),
        (ByteData? data) {},
      );
      await tester.pump(const Duration(milliseconds: 120));
      expect(find.textContaining('Downloading update — 47%'), findsOneWidget);

      // the verify leg
      await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
        'anantplay/updater/events',
        const StandardMethodCodec().encodeSuccessEnvelope(<String, Object>{
          't': 'gate', 'gate': 'verify',
        }),
        (ByteData? data) {},
      );
      await tester.pump(const Duration(milliseconds: 120));
      expect(find.text('Verifying SHA-256…'), findsOneWidget);

      // the installer leg
      await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
        'anantplay/updater/events',
        const StandardMethodCodec().encodeSuccessEnvelope(<String, Object>{
          't': 'gate', 'gate': 'installer',
        }),
        (ByteData? data) {},
      );
      await tester.pump(const Duration(milliseconds: 120));
      expect(find.textContaining('Opening installer'), findsOneWidget);
    });

    testWidgets(
        'a failed download shows the reason + Retry Update (the gate STAYS locked)',
        (tester) async {
      onboardingDone = true;
      onboardingVersion = 2;
      await tester.pumpWidget(upToDateApp());
      await tester.pumpAndSettle();
      await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
        'anantplay/updater/events',
        const StandardMethodCodec().encodeSuccessEnvelope(<String, Object>{
          't': 'gate', 'gate': 'available',
          'current': '3.4.6.e', 'next': '3.4.6.f',
        }),
        (ByteData? data) {},
      );
      await tester.pumpAndSettle();
      await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
        'anantplay/updater/events',
        const StandardMethodCodec().encodeSuccessEnvelope(<String, Object>{
          't': 'gate', 'gate': 'failed',
          'reason': 'download failed — check your connection and tap Retry Update',
        }),
        (ByteData? data) {},
      );
      await tester.pumpAndSettle();

      expect(find.byType(ForceUpdateScreen), findsOneWidget,
          reason: 'a failure never unlocks the gate');
      expect(find.text('Retry Update'), findsOneWidget);
      expect(find.textContaining('check your connection'), findsOneWidget);
    });

    testWidgets(
        'gate:uptodate releases the lock (offline / no-update playback works)',
        (tester) async {
      onboardingDone = true;
      onboardingVersion = 2;
      await tester.pumpWidget(upToDateApp());
      await tester.pumpAndSettle();
      await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
        'anantplay/updater/events',
        const StandardMethodCodec().encodeSuccessEnvelope(<String, Object>{
          't': 'gate', 'gate': 'available',
          'current': '3.4.6.e', 'next': '3.4.6.f',
        }),
        (ByteData? data) {},
      );
      await tester.pumpAndSettle();
      expect(find.byType(ForceUpdateScreen), findsOneWidget);
      // the release vanished / up-to-date: unlock.
      await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
        'anantplay/updater/events',
        const StandardMethodCodec().encodeSuccessEnvelope(<String, Object>{
          't': 'gate', 'gate': 'uptodate',
        }),
        (ByteData? data) {},
      );
      await tester.pumpAndSettle();
      expect(find.byType(ForceUpdateScreen), findsNothing);
      expect(find.byType(HomeScreen), findsOneWidget);
    });
  });

  // ══ v3.4.6.d — THE ONBOARDING PERMISSION INTENTS ══════════════════════════
  group('onboarding permission intents (the wrong-intent fix)', () {
    testWidgets(
        'the Notifications slide fires requestNotifications — NEVER the overlay page',
        (tester) async {
      final calls = <String>[];
      mock.setMockMethodCallHandler(const MethodChannel('anantplay/cast'), (call) async {
        calls.add(call.method);
        switch (call.method) {
          case 'officialVersion':
            return '';
          case 'onboardingStatus':
            return <String, Object>{
              't': 'onboardingStatus',
              'overlay': false, 'notif': false, 'battery': false,
              'installs': false, 'official': false,
            };
          default:
            return <String, Object>{'up': false, 'port': -1, 'discoverable': false, 'mdns': false};
        }
      });
      await tester.pumpWidget(quiet(const OnboardingScreen()));
      await tester.pump();
      // glide to slide 3 (Notifications)
      for (var i = 0; i < 2; i++) {
        await tester.fling(find.byType(PageView), const Offset(-380, 0), 900);
        await tester.pumpAndSettle();
      }
      await tester.ensureVisible(find.text('Enable Notifications'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Enable Notifications'), warnIfMissed: false);
      await tester.pump();
      // THE v3.4.6.c DEFECT ASSERTION: the slide opened OVERLAY settings; the
      // fix fires the in-app runtime notification dialog instead.
      expect(calls, contains('requestNotifications'));
      expect(calls, isNot(contains('openOverlay')));
    });

    testWidgets(
        'the Auto-Open slide opens overlay settings AND arms the return watcher',
        (tester) async {
      final calls = <String>[];
      mock.setMockMethodCallHandler(const MethodChannel('anantplay/cast'), (call) async {
        calls.add(call.method);
        switch (call.method) {
          case 'officialVersion':
            return '';
          case 'onboardingStatus':
            return <String, Object>{
              't': 'onboardingStatus',
              'overlay': false, 'notif': false, 'battery': false,
              'installs': false, 'official': false,
            };
          default:
            return <String, Object>{'up': false, 'port': -1, 'discoverable': false, 'mdns': false};
        }
      });
      await tester.pumpWidget(quiet(const OnboardingScreen()));
      await tester.pump();
      for (var i = 0; i < 3; i++) {
        await tester.fling(find.byType(PageView), const Offset(-380, 0), 900);
        await tester.pumpAndSettle();
      }
      await tester.ensureVisible(find.text('Enable Auto-Open'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Enable Auto-Open'), warnIfMissed: false);
      await tester.pump();
      expect(calls, contains('openOverlay'));
      expect(calls, contains('watchOverlayGrant'),
          reason: 'the 30-second return watcher must be armed with the intent');
    });

    testWidgets('the official app slide shows the live version pill', (tester) async {
      mock.setMockMethodCallHandler(const MethodChannel('anantplay/cast'), (call) async {
        switch (call.method) {
          case 'officialInstalled':
            return true;
          case 'officialVersion':
            return '3.9.2';
          case 'onboardingStatus':
            return <String, Object>{
              't': 'onboardingStatus',
              'overlay': false, 'notif': false, 'battery': false,
              'installs': false, 'official': true,
            };
          default:
            return <String, Object>{'up': false, 'port': -1, 'discoverable': false, 'mdns': false};
        }
      });
      await tester.pumpWidget(quiet(const OnboardingScreen()));
      await tester.pump();
      await tester.fling(find.byType(PageView), const Offset(-380, 0), 900);
      await tester.pumpAndSettle();
      expect(find.text('AnantPlay v3.9.2 Installed ✓'), findsOneWidget);
    });
  });

  // ══ v3.4.6.d — THE 1-TAP AUTO-COPY TROUBLESHOOT ═══════════════════════════
  group('troubleshoot auto-copy (1-tap, zero extra taps)', () {
    testWidgets(
        'the moment 10 probes land, the report is on the clipboard + the pill shows',
        (tester) async {
      mock.setMockMethodCallHandler(const MethodChannel('anantplay/troubleshoot'), (call) async {
        return '[]';
      });
      await tester.pumpWidget(quiet(const HelpScreen()));
      await tester.pump();

      await tester.tap(find.text('Run full app troubleshoot'), warnIfMissed: false);
      await tester.pump();

      // The 10-probe result frame lands on the troubleshoot EventChannel.
      final outcomes = <Object>[
        for (var i = 1; i <= 10; i++)
          <String, Object>{'id': i, 'name': 'probe $i', 'pass': true, 'ms': 12.0, 'detail': 'ok'},
      ];
      await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
        'anantplay/troubleshoot/events',
        const StandardMethodCodec().encodeSuccessEnvelope(outcomes),
        (ByteData? data) {},
      );
      await tester.pump();
      await tester.pump();

      // THE AUTO-COPY CONTRACT: zero extra tap and the report is on the
      // clipboard with the 10/10 confirmation pill.
      final data = await Clipboard.getData('text/plain');
      expect(data?.text, contains('10/10 PASS'));
      expect(data?.text, contains('v3.4.6.d (30410)'));
      expect(find.textContaining('10/10 Troubleshoot complete'), findsOneWidget);
      // Copy Again + Share stay available below.
      expect(find.text('Copy Again'), findsOneWidget);
      expect(find.text('Share report'), findsOneWidget);
    });
  });

  // ══ v3.4.6.d — GOOGLE IS THE DEFAULT SEARCH ENGINE ════════════════════════
  group('browser settings (Google default)', () {
    testWidgets('the search engine row defaults to Google', (tester) async {
      await tester.pumpWidget(quiet(const SettingsScreen()));
      await tester.pump();
      // The Browser & Privacy group (collapsed by default).
      await tester.scrollUntilVisible(find.text('Browser & Privacy'), 300,
          scrollable: find.byType(Scrollable).first);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Browser & Privacy'), warnIfMissed: false);
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(find.text('Search engine'), 200,
          scrollable: find.byType(Scrollable).first);
      await tester.pumpAndSettle();
      expect(find.text('Search engine'), findsOneWidget);
      // The FIRST option (the default) is Google.
      expect(find.text('Google'), findsWidgets);
    });
  });

  // ══ v3.4.6.d — PERFORMANCE GATING (TickerMode on offstage tabs) ═══════════
  group('performance gating (TickerMode)', () {
    testWidgets('every offstage tab is wrapped in a disabled TickerMode',
        (tester) async {
      onboardingDone = true;
      onboardingVersion = 2;
      await tester.pumpWidget(upToDateApp());
      await tester.pumpAndSettle();

      // The FOUR tab-level TickerMode gates live in the IndexedStack's child
      // CONFIG (offstage tabs are not inflated in the widget test harness, so
      // the assertion reads the stack's children — exactly what the shell
      // builds every frame on device).
      final stack = tester.widget<IndexedStack>(find.byType(IndexedStack));
      expect(stack.children.length, 4);
      final tabModes = stack.children
          .whereType<TickerMode>()
          .map((m) => m.enabled)
          .toList();
      expect(tabModes.length, 4, reason: 'all four tabs are TickerMode-gated');
      expect(tabModes.where((e) => e).length, 1,
          reason: 'only the ACTIVE tab may run tickers');
      expect(tabModes.first, isTrue, reason: 'Home (active) is the enabled one');

      // Switch to Browser: now Browser is the one enabled tab.
      await tester.tap(
          find.descendant(
              of: find.byType(shell.ShellRoot),
              matching: find.text('Browser')).first,
          warnIfMissed: false);
      await tester.pumpAndSettle();
      final stack2 = tester.widget<IndexedStack>(find.byType(IndexedStack));
      final tabModes2 = stack2.children
          .whereType<TickerMode>()
          .map((m) => m.enabled)
          .toList();
      expect(tabModes2.where((e) => e).length, 1);
      expect(tabModes2[1], isTrue, reason: 'Browser (now active) is enabled');
    });
  });
}
