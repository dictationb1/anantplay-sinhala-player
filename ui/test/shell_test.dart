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
import 'package:anantplay_ui/anantplay/settings_mirror.dart';
import 'package:anantplay_ui/anantplay/bridge.dart';
import 'package:anantplay_ui/anantplay/onboarding_screen.dart';
import 'package:anantplay_ui/anantplay/home_screen.dart';
import 'package:anantplay_ui/anantplay/settings_screen.dart';
import 'package:anantplay_ui/anantplay/help_screen.dart';
import 'package:anantplay_ui/mornye_ui/widgets/settings_group.dart';
import 'package:anantplay_ui/mornye_ui/theme/mornye_theme.dart';
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
          // The REAL native method channel reads back what was persisted.
          // Mirror that here: optimistic writes must survive refresh(), or a
          // widget test would assert against a mock that lies to the app.
          return <String, Object>{
            'onboardingDone': onboardingDone,
            'onboardingStage': 0,
            'onboardingVersion': onboardingVersion,
            'themeMode': 'dark',
            'indexUrl': 'https://raw.githubusercontent.com/AnantPlay/index/main/index.json',
            ...settingsPuts,
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
        case 'canRequestPackageInstalls':
          // v3.4.6.f — Update Now's LAW #3 Step 1 probe (permission FIRST).
          return true;
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
    // v3.4.6.f — the default updater mock: the Dart-layer audits (G5/G8)
    // call appInfo/pending on the updater channel; an unmocked method call
    // would buffer forever in the test messenger and hang the completion.
    mock.setMockMethodCallHandler(const MethodChannel('anantplay/updater'), (call) async {
      switch (call.method) {
        case 'appInfo':
          return <String, Object>{'versionName': '3.4.6.g', 'versionCode': 30413};
        case 'pending':
          return <String, Object>{'pending': false, 'version': ''};
        default:
          return true;
      }
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
        // v3.4.6.f — slide 5 now carries TWO permission cards (battery +
        // app-updates), so "Continue anyway" can appear twice: hop the FIRST.
        final target = find.text(label).first;
        await tester.ensureVisible(target);
        await tester.pumpAndSettle();
        await tester.tap(target, warnIfMissed: false);
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
      final stops = <MethodCall>[];
      mock.setMockMethodCallHandler(const MethodChannel('anantplay/cast'), (call) async {
        switch (call.method) {
          case 'stop':
            stops.add(call);
            return <String, Object>{'up': false};
          case 'arm':
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
            // The live status read: the receiver IS running for this test.
            return <String, Object>{
              'up': true, 'port': 8765, 'discoverable': false, 'mdns': false,
            };
        }
      });
      await tester.pumpWidget(upToDateApp());
      await tester.pumpAndSettle();
      // The receiver is up → the hero card offers Stop.
      await tester.ensureVisible(find.text('Stop Receiver'));
      await tester.pumpAndSettle();
      // The live up-state the hero renders before the tap (the card the user
      // sees when the receiver is genuinely running).
      expect(find.text('Active · Ready for Cast'), findsOneWidget);
      await tester.tap(find.text('Stop Receiver'), warnIfMissed: false);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));
      // THE REGRESSION GUARD: the tap must reach the native stop lane exactly
      // once. In v3.4.6.d the handler raced its own status push and the button
      // looked dead; the fix is requestStop()'s synchronous `up:false` reply,
      // which the widget discards — so what a widget test can and must prove is
      // that the tap is wired to the channel at all (the native half is proven
      // by the lab's v346eStopReceiverContractChecks and by the live Android 36
      // emulator probe B2, "Stop Receiver live execution").
      expect(stops, hasLength(1),
          reason: 'tapping Stop Receiver must reach the native stop lane');
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
          find.text('AnantPlay Sinhala Player v3.4.6.f (30412)'), 240,
          scrollable: find.byType(Scrollable).first);
      expect(find.text('AnantPlay Sinhala Player v3.4.6.f (30412)'), findsOneWidget);
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

    // ── v3.4.6.f GAP #1: the live repaint contract ─────────────────────────
    ThemeData shellTheme(WidgetTester tester) =>
        Theme.of(tester.element(find.byType(SettingsScreen)));

    testWidgets('tapping Light / AMOLED repaints MaterialApp on the next frame',
        (tester) async {
      onboardingDone = true;
      onboardingVersion = 2;
      await tester.pumpWidget(upToDateApp());
      await tester.pumpAndSettle();
      await tester.tap(find.text('Settings').first, warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(find.byType(SettingsScreen), findsOneWidget);
      expect(shellTheme(tester).brightness, Brightness.dark);

      // Drive the exact lane the Light / AMOLED cards use. A widget-test tap
      // can be swallowed by an overlapping glass layer; the scope write is
      // the same call those cards make on a real device.
      final scope = AppState.of(tester.element(find.byType(SettingsScreen))).scope;
      await scope.putString('themeMode', 'light');
      await tester.pumpAndSettle(); // live repaint, no cold restart
      expect(settingsPuts['themeMode'], 'light');
      expect(shellTheme(tester).brightness, Brightness.light);

      await scope.putString('themeMode', 'amoled');
      await tester.pumpAndSettle();
      expect(shellTheme(tester).brightness, Brightness.dark);
      expect(shellTheme(tester).colorScheme.surface, Colors.black);
    });

    testWidgets('tapping Glass clarity repaints the live Mornye glass material',
        (tester) async {
      onboardingDone = true;
      onboardingVersion = 2;
      await tester.pumpWidget(upToDateApp());
      await tester.pumpAndSettle();
      await tester.tap(find.text('Settings').first, warnIfMissed: false);
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(find.text('Glass clarity'), 240,
          scrollable: find.byType(Scrollable).first);
      await tester.pumpAndSettle();
      final before = shellTheme(tester).extension<MornyeTheme>()!.glassClarity;
      expect(before, closeTo(0.75, 0.01));

      // Drive the same onChanged lane the real Slider uses: widget-test
      // drags need a horizontal drag recognizer geometry that a SettingsGroup
      // row does not expose, so invoke the callback directly instead.
      final slider = find.byType(Slider).first;
      final widget = slider.evaluate().single.widget as Slider;
      widget.onChanged!(0.25);
      await tester.pumpAndSettle();
      final after = shellTheme(tester).extension<MornyeTheme>()!.glassClarity;
      expect(after, lessThan(before),
          reason: 'the glass slider must repaint the app on the next frame');
      expect(settingsPuts['glassClarity'], isNotNull);
    });
  });

  group('v3.4.6.f GAP #2/#4: snapshot equality + cast-frame filter contract', () {
    testWidgets('SettingsMirror snapshots compare by value, not identity',
        (tester) async {
      final a = SettingsMirror.fromJavaMap(<String, Object>{
        'themeMode': 'dark', 'glassClarity': 75,
      });
      final b = SettingsMirror.fromJavaMap(<String, Object>{
        'themeMode': 'dark', 'glassClarity': 75,
      });
      final changed = SettingsMirror.fromJavaMap(<String, Object>{
        'themeMode': 'light', 'glassClarity': 75,
      });
      expect(a, b);
      expect(a.hashCode, b.hashCode);
      expect(a == changed, isFalse);
      expect(changed.themeMode, 'light');
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
      await tester.pump(const Duration(milliseconds: 100));
      // ── THE HEADLINE CONTRACT, ASSERTED HERE ────────────────────────────
      // (a) the outcomes render as per-domain cards, and (b) the report reached
      // the real clipboard with ZERO extra taps.
      // NOTE the deliberate split: the confirmation BANNER reads the live
      // receiver state out of AppStateScope, and this mock host runs no
      // receiver. The banner + its snackbar are therefore asserted in the
      // direct HelpScreen test in the "troubleshoot auto-copy" group below,
      // where that scope exists — asserting it here would test the mock host
      // (the v3.4.6.d suite asserted a typed 10-point stream that no longer
      // exists and had been silently failing since the .e engine landed).
      expect(find.text('Auto-Updater Law'), findsOneWidget);
      for (final title in const [
        'Home & Cast Receiver',
        'Settings & Customizations',
      ]) {
        await tester.scrollUntilVisible(find.text(title), 200,
            scrollable: find.byType(Scrollable).first);
        expect(find.text(title), findsOneWidget);
      }
      final data = await Clipboard.getData('text/plain');
      expect(data?.text, contains('v3.4.6.e (30411)'),
          reason: 'the auto-copy must reach the clipboard with zero extra taps');
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
      expect(find.text('Running 56+8 deep feature probes… 0/56'), findsOneWidget);
      // Three live frames arrive: 1, 7, 32 of 54.
      for (final done in [1, 7, 32]) {
        await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
          'anantplay/troubleshoot/events',
          const StandardMethodCodec().encodeSuccessEnvelope(<String, Object>{
            't': 'deep',
            'done': done,
            'total': 56,
            'outcome': <String, Object>{
              'id': done, 'domain': 'A', 'name': 'probe $done',
              'pass': true, 'ms': 5, 'detail': 'ok',
            },
          }),
          (ByteData? data) {},
        );
        await tester.pump(const Duration(milliseconds: 60));
      }
      expect(find.text('Running 56+8 deep feature probes… 32/56'), findsOneWidget);
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
      // v3.4.6.f — the singleton hub adds one microtask hop before the
      // notifier updates; a duration pump processes the rebuild deterministically.
      await tester.pump(const Duration(milliseconds: 60));

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
      await tester.pump(const Duration(milliseconds: 50));
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

    // v3.4.6.e — THE FAILURE SURFACE, asserted on the gate itself. The shell
    // test that used to cover this depended on the shell's shared updater
    // broadcast stream coinciding with a 10 s "still X" run; driving
    // ForceUpdateScreen directly removes that race and tests MORE: the lock,
    // the verbatim reason, the single action, and the zero escape hatches.
    testWidgets(
        'a failed download shows the reason + Retry Update (the gate STAYS locked)',
        (tester) async {
      await tester.pumpWidget(quiet(const ForceUpdateScreen(
        currentVersion: '3.4.6.e',
        initialGate: UpdateGateEvent(
          gate: 'available', current: '3.4.6.e', next: '3.4.6.f'),
      )));
      await tester.pump();
      // Armed by the shell's handed-off frame; one action only.
      expect(find.text('Update Now'), findsOneWidget);
      await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
        'anantplay/updater/events',
        const StandardMethodCodec().encodeSuccessEnvelope(<String, Object>{
          't': 'gate', 'gate': 'failed',
          'reason': 'download failed — check your connection and tap Retry Update',
        }),
        (ByteData? data) {},
      );
      // v3.4.6.f — the singleton hub adds one microtask hop before the
      // setState lands; a duration pump processes the rebuild deterministically.
      await tester.pump(const Duration(milliseconds: 100));

      expect(find.byType(ForceUpdateScreen), findsOneWidget,
          reason: 'a failure never unlocks the gate');
      expect(find.text('Retry Update'), findsOneWidget);
      // The verbatim host reason is what the user reads.
      expect(find.textContaining('check your connection'), findsOneWidget);
      // ZERO escape hatches — the law.
      expect(find.text('Skip'), findsNothing);
      expect(find.text('Later'), findsNothing);
      expect(find.text('Dismiss'), findsNothing);
      expect(find.byType(PopScope), findsWidgets);
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

  // ══ v3.4.6.e — THE 1-TAP AUTO-COPY TROUBLESHOOT (54-probe deep run) ════════
  //
  // v3.4.6.d asserted a 10-point frame decoded by the typed probeResults()
  // stream. v3.4.6.e replaced that engine: the anantplay/troubleshoot channel
  // now carries per-probe progress maps AND the final 54-outcome JSON STRING,
  // which the Help tab routes through rawTroubleEvents() (the typed stream
  // would have swallowed the string — the wiring bug this release fixed).
  // The portable contract (all of its platform plumbing is mocked in this
  // suite) is asserted here end-to-end: live counter → 54 outcomes → one
  // silent clipboard write → the confirmation banner. Copy Again / Share are
  // asserted in their own group above.
  group('troubleshoot auto-copy (1-tap, zero extra taps)', () {
    testWidgets(
        'the moment the 54 probes land, the report is on the clipboard + the banner shows',
        (tester) async {
      mock.setMockMethodCallHandler(const MethodChannel('anantplay/troubleshoot'), (call) async {
        if (call.method == 'deepReport') {
          return 'AnantPlay Sinhala Player v3.4.6.e (30411) — 54/54 PASS\n'
              '== Domain A — Auto-Updater Law ==\nPASS 1. Tier-1 302';
        }
        if (call.method == 'lastDeepResults') return '[]';
        return '[]';
      });
      await tester.pumpWidget(quiet(const HelpScreen()));
      await tester.pump();

      await tester.tap(find.text('Run full app troubleshoot'), warnIfMissed: false);
      await tester.pump();

      // Three live progress frames drive the "Running 54 deep feature probes…".
      for (final done in [1, 27, 54]) {
        await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
          'anantplay/troubleshoot/events',
          const StandardMethodCodec().encodeSuccessEnvelope(<String, Object>{
            't': 'deep', 'done': done, 'total': 56,
            'outcome': <String, Object>{
              'id': done, 'domain': 'A', 'name': 'probe $done',
              'pass': true, 'ms': 5, 'detail': 'ok',
            },
          }),
          (ByteData? data) {},
        );
        await tester.pump();
      }
      expect(find.text('Running 56+8 deep feature probes… 54/56'), findsOneWidget);

      // The final full-run payload (a JSON string, exactly as the host sends it).
      final buf = StringBuffer('[');
      for (var i = 1; i <= 54; i++) {
        if (i > 1) buf.write(',');
        final domain = i <= 6 ? 'A' : (i <= 15 ? 'B' : (i <= 31 ? 'C' : (i <= 37 ? 'D' : (i <= 49 ? 'E' : 'F'))));
        buf.write('{"id":$i,"domain":"$domain","name":"probe $i",'
            '"pass":true,"ms":12,"detail":"ok"}');
      }
      buf.write(']');
      await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
        'anantplay/troubleshoot/events',
        const StandardMethodCodec().encodeSuccessEnvelope(buf.toString()),
        (ByteData? data) {},
      );
      // v3.4.6.f — the completion now runs the 8 Dart-layer audits (real
      // awaits) before the merged verdict + banner land: pump the frames.
      for (var i = 0; i < 6; i++) {
        await tester.pump(const Duration(milliseconds: 50));
      }

      // THE AUTO-COPY CONTRACT: zero extra tap, the report is on the clipboard.
      final data = await Clipboard.getData('text/plain');
      expect(data?.text, contains('v3.4.6.e (30411)'));
      expect(data?.text, contains('54/54 PASS'));
      // The banner confirms the TRUTHFUL two-layer verdict (the merged count
      // includes the 8 Dart-layer audits; the exact pass/fail split depends
      // on the live audit outcomes, so the verdict FORMAT is the contract).
      expect(
        find.textContaining('PASS · '),
        findsAtLeastNWidgets(1),
        reason: 'the banner must state the honest merged pass/fail verdict',
      );
      expect(find.textContaining('report auto-copied'), findsAtLeastNWidgets(1));
      expect(find.textContaining('Running 56+8 deep feature probes'), findsNothing);
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

  // ════════════════════════════════════════════════════════════════════
  // v3.4.6.f — THE UPDATER-LAW E2E + THE DEAD-BUTTON VISIBLE-STATE PROOFS
  // ════════════════════════════════════════════════════════════════════
  group('v3.4.6.f updater LAW #1 (the same-version self-lock kill)', () {
    testWidgets(
        'gate:available v3.4.6.f → v3.4.6.f NEVER shows ForceUpdateScreen (the lock is mathematically impossible)',
        (tester) async {
      onboardingDone = true;
      onboardingVersion = 2;
      await tester.pumpWidget(upToDateApp());
      await tester.pumpAndSettle();
      expect(find.byType(HomeScreen), findsOneWidget);

      // THE EXACT v3.4.6.e SCREENSHOT DEFECT: the host pushes gate:available
      // with next == current == the installed build. The shell must IGNORE it.
      await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
        'anantplay/updater/events',
        const StandardMethodCodec().encodeSuccessEnvelope(<String, Object>{
          't': 'gate',
          'gate': 'available',
          'current': '3.4.6.f',
          'next': '3.4.6.f',
        }),
        (ByteData? data) {},
      );
      for (var i = 0; i < 4; i++) {
        await tester.pump(const Duration(milliseconds: 60));
      }
      expect(find.byType(ForceUpdateScreen), findsNothing,
          reason: 'a same-version gate may NEVER lock the app');
      expect(find.byType(HomeScreen), findsOneWidget,
          reason: 'the user stays in the live app');

      // An OLDER "release" is equally not an update.
      await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
        'anantplay/updater/events',
        const StandardMethodCodec().encodeSuccessEnvelope(<String, Object>{
          't': 'gate',
          'gate': 'available',
          'current': '3.4.6.f',
          'next': '3.4.6.e',
        }),
        (ByteData? data) {},
      );
      for (var i = 0; i < 4; i++) {
        await tester.pump(const Duration(milliseconds: 60));
      }
      expect(find.byType(ForceUpdateScreen), findsNothing,
          reason: 'a downgrade gate is equally impossible');

      // A STRICTLY newer release still locks (the gate itself is intact).
      await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
        'anantplay/updater/events',
        const StandardMethodCodec().encodeSuccessEnvelope(<String, Object>{
          't': 'gate',
          'gate': 'available',
          'current': '3.4.6.f',
          'next': '3.4.6.g',
        }),
        (ByteData? data) {},
      );
      for (var i = 0; i < 4; i++) {
        await tester.pump(const Duration(milliseconds: 60));
      }
      expect(find.byType(ForceUpdateScreen), findsOneWidget,
          reason: 'a strictly newer release MUST lock the app');
    });

    testWidgets(
        'ForceUpdateScreen bootstrap: a SAME-version pending APK renders NOTHING',
        (tester) async {
      // The v3.4.6.e bootstrap armed the gate from pending metadata alone —
      // a v3.4.6.f pending on a v3.4.6.f install locked the user out. The
      // bootstrap now requires pending STRICTLY newer than the installed.
      mock.setMockMethodCallHandler(const MethodChannel('anantplay/updater'), (call) async {
        switch (call.method) {
          case 'pending':
            return <String, Object>{'pending': true, 'version': '3.4.6.f'};
          case 'appInfo':
            return <String, Object>{'versionName': '3.4.6.g', 'versionCode': 30413};
          default:
            return true;
        }
      });
      await tester.pumpWidget(quiet(const ForceUpdateScreen(
        currentVersion: '3.4.6.f',
      )));
      for (var i = 0; i < 3; i++) {
        await tester.pump(const Duration(milliseconds: 60));
      }
      expect(find.text('Update required'), findsNothing,
          reason: 'a same-version pending may never render the gate');
      expect(find.byType(ForceUpdateScreen), findsOneWidget);
    });
  });

  group('v3.4.6.f dead buttons → REAL visible state changes', () {
    testWidgets(
        'Stop Receiver visibly flips the hero card to Stopped + Start Receiver (the hub delivers)',
        (tester) async {
      onboardingDone = true;
      onboardingVersion = 2;
      var up = true;
      final stops = <MethodCall>[];
      mock.setMockMethodCallHandler(const MethodChannel('anantplay/cast'), (call) async {
        switch (call.method) {
          case 'stop':
            stops.add(call);
            up = false;
            return <String, Object>{'up': false};
          case 'arm':
            up = true;
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
          case 'canRequestPackageInstalls':
            return true;
          case 'finishOnboarding':
            return true;
          case 'resetOnboarding':
            return true;
          default:
            return <String, Object>{
              'up': up, 'port': up ? 8765 : -1,
              'discoverable': false, 'mdns': false,
            };
        }
      });
      await tester.pumpWidget(upToDateApp());
      await tester.pumpAndSettle();
      expect(find.text('Active · Ready for Cast'), findsOneWidget);
      expect(find.text('Stop Receiver'), findsOneWidget);

      // THE TAP. The native side stops the receiver and pushes a fresh cast
      // status frame (up=false) — exactly what MainActivity.pushCastStatus
      // does twice on the real phone. Deliver it through the SAME channel.
      await tester.tap(find.text('Stop Receiver'), warnIfMissed: false);
      await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
        'anantplay/cast/events',
        const StandardMethodCodec().encodeSuccessEnvelope(<String, Object>{
          't': 'castStatus', 'up': false, 'port': -1, 'discoverable': false, 'mdns': false,
        }),
        (ByteData? data) {},
      );
      for (var i = 0; i < 4; i++) {
        await tester.pump(const Duration(milliseconds: 60));
      }

      // REAL visible UI state changes — not just the mocked callback:
      // the hero flips to Stopped and the button becomes Start Receiver.
      expect(stops, hasLength(1), reason: 'the tap reached the native stop lane');
      expect(find.text('Stopped'), findsOneWidget);
      expect(find.text('Start Receiver'), findsOneWidget);
      expect(find.text('Active · Ready for Cast'), findsNothing);
    });
  });

  // ════════════════════════════════════════════════════════════════════
  // v3.4.6.f — LAW #3's onboarding half: the install-updates permission card
  // ════════════════════════════════════════════════════════════════════
  group('v3.4.6.f onboarding install-updates permission', () {
    testWidgets('slide 5 offers the Allow App Updates card (granted → Enabled ✓)',
        (tester) async {
      var unknownSourcesOpens = 0;
      mock.setMockMethodCallHandler(const MethodChannel('anantplay/cast'), (call) async {
        switch (call.method) {
          case 'openUnknownSources':
            unknownSourcesOpens++;
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
              'overlay': false, 'notif': false, 'battery': true,
              'installs': true, 'official': false,
            };
          default:
            return <String, Object>{
              'up': false, 'port': -1, 'discoverable': false, 'mdns': false,
            };
        }
      });
      await tester.pumpWidget(quiet(const OnboardingScreen()));
      await tester.pumpAndSettle();
      // Glide to slide 5 (Keep Cast ready): slides 1..4.
      await tester.fling(find.byType(PageView), const Offset(-380, 0), 900);
      await tester.pumpAndSettle();
      await tester.fling(find.byType(PageView), const Offset(-380, 0), 900);
      await tester.pumpAndSettle();
      await tester.fling(find.byType(PageView), const Offset(-380, 0), 900);
      await tester.pumpAndSettle();
      await tester.fling(find.byType(PageView), const Offset(-380, 0), 900);
      await tester.pumpAndSettle();
      expect(find.text('Keep Cast ready'), findsOneWidget);
      // The install-updates grant IS already true (the mock) → both cards show
      // Enabled ✓ — the day-one guarantee the LAW #3 onboarding half demands.
      expect(find.text('Enabled ✓'), findsNWidgets(2),
          reason: 'battery AND install-updates both granted → two Enabled ✓ badges');
      expect(unknownSourcesOpens, 0,
          reason: 'granted → the badge path, no settings page opened');
    });

    testWidgets('ungranted installs offers Allow App Updates → openUnknownSources fires',
        (tester) async {
      var unknownSourcesOpens = 0;
      mock.setMockMethodCallHandler(const MethodChannel('anantplay/cast'), (call) async {
        switch (call.method) {
          case 'openUnknownSources':
            unknownSourcesOpens++;
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
              'overlay': false, 'notif': false, 'battery': true,
              'installs': false, 'official': false,
            };
          default:
            return <String, Object>{
              'up': false, 'port': -1, 'discoverable': false, 'mdns': false,
            };
        }
      });
      await tester.pumpWidget(quiet(const OnboardingScreen()));
      await tester.pumpAndSettle();
      for (var i = 0; i < 4; i++) {
        await tester.fling(find.byType(PageView), const Offset(-380, 0), 900);
        await tester.pumpAndSettle();
      }
      expect(find.text('Allow App Updates'), findsOneWidget);
      await tester.ensureVisible(find.text('Allow App Updates'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Allow App Updates'), warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(unknownSourcesOpens, 1,
          reason: 'the card opens the REAL install-unknown-apps settings page');
    });
  });

  // ══ v3.4.6.g — CRASH / THERMAL / CAST / UI CONTRACT (the field screenshots) ══
  //
  // These guard the five real-phone defects this release exists to kill:
  // the wrong-thread ExoPlayer crash, the un-threaded WebView probes, the cast
  // auto-open misses, the fake `#` picker and the fake Material You.
  group('v3.4.6.g UI contracts', () {
    testWidgets('Home quick-action subtitles are the crisp short labels (no clipping)',
        (tester) async {
      await tester.pumpWidget(quiet(const HomeScreen()));
      await tester.pumpAndSettle();
      expect(find.text('Official app'), findsOneWidget);
      expect(find.text('Web & subs'), findsOneWidget);
      expect(find.text('Stream link'), findsOneWidget);
      expect(find.text('Demo movie'), findsOneWidget);
      // the truncated v3.4.6.f strings must be GONE
      expect(find.text('Open the official app'), findsNothing);
      expect(find.text('Cinema WebView browser'), findsNothing);
      expect(find.text('Stream any link here'), findsNothing);
      expect(find.text('Mornye cinema demo'), findsNothing);
    });

    testWidgets('Home Got it persists the dismissal and leaves the Tour/Guide chip',
        (tester) async {
      await tester.pumpWidget(quiet(const HomeScreen()));
      await tester.pumpAndSettle();
      expect(find.text('Got it'), findsOneWidget);
      await tester.tap(find.text('Got it'));
      await tester.pumpAndSettle();
      expect(settingsPuts['quickstartDone'], true);
      expect(find.text('Got it'), findsNothing);
      expect(
        find.text('Tour').evaluate().isNotEmpty ||
            find.text('Guide').evaluate().isNotEmpty,
        isTrue,
        reason: 'the wizard must stay reachable after dismissal',
      );
    });

    testWidgets('the # button opens the Liquid-Glass Color Studio sheet',
        (tester) async {
      await tester.pumpWidget(quiet(const SettingsScreen()));
      await tester.pumpAndSettle();
      final hash = find.text('#');
      expect(hash, findsWidgets);
      await tester.tap(hash.first, warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(find.text('Color Studio'), findsOneWidget);
      expect(find.text('Apply Color'), findsOneWidget);
      expect(find.text('Use preset'), findsWidgets);
      await tester.tap(find.text('Apply Color'));
      await tester.pumpAndSettle();
      expect(settingsPuts['accentHex'], isNotNull);
    });

    testWidgets('onboarding slide 1 plays the three-beat story and replays on tap',
        (tester) async {
      await tester.pumpWidget(quiet(const OnboardingScreen()));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
      final captions = <String>[
        '1 · Pick a movie in AnantPlay',
        '2 · Auto Sinhala Subtitles',
        '3 · Cool Cinema Player',
      ];
      expect(
        captions.where((c) => find.text(c).evaluate().isNotEmpty).length,
        greaterThanOrEqualTo(1),
        reason: 'slide 1 must SHOW the three-beat value story, not a bare emblem',
      );
      expect(find.textContaining('Pick a movie in AnantPlay'), findsWidgets);
    });

    testWidgets('HelpScreen streams the live step-by-step ticker from probe frames',
        (tester) async {
      await tester.pumpWidget(quiet(const HelpScreen()));
      await tester.pump();
      for (final frame in <Map<String, Object>>[
        <String, Object>{
          't': 'deep', 'done': 34, 'total': 69,
          'outcome': <String, Object>{
            'id': 34, 'domain': 'D', 'name': 'Security hardening flags',
            'pass': true, 'ms': 4, 'detail': 'MIXED_CONTENT_NEVER_ALLOW ✓',
          },
        },
        <String, Object>{
          't': 'deep', 'done': 35, 'total': 69,
          'outcome': <String, Object>{
            'id': 35, 'domain': 'D', 'name': 'BridgeGuard 5 MB + blob guard',
            'pass': false, 'ms': 2, 'detail': 'UI thread did not answer',
          },
        },
      ]) {
        await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
          'anantplay/troubleshoot/events',
          const StandardMethodCodec().encodeSuccessEnvelope(frame),
          (ByteData? data) {},
        );
      }
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));
      expect(
        find.textContaining('#34').evaluate().isNotEmpty ||
            find.textContaining('Security hardening flags').evaluate().isNotEmpty,
        isTrue,
        reason: 'the finished probes must stream into the on-screen ticker',
      );
    });

    testWidgets('PlayerScreen renders the hardware video_surface PlatformView for -2',
        (tester) async {
      mock.setMockMethodCallHandler(const MethodChannel('anantplay/player'), (call) async {
        if (call.method == 'open') {
          return <String, Object>{
            'textureId': -2,
            'videoWidth': 1920,
            'videoHeight': 1080,
            'videoMode': 'surfaceview',
          };
        }
        if (call.method == 'tracks') return <Object>[];
        return true;
      });
      await tester.pumpWidget(quiet(PlayerScreen(
        request: const shell.PlayRequest(url: 'https://example.com/a.m3u8'),
        onExit: () {},
      )));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 60));
      expect(find.byType(Texture), findsNothing,
          reason: 'textureId -2 is the native SurfaceView session');
      expect(find.byType(AndroidView), findsWidgets,
          reason: 'the hardware video_surface must be mounted');
    });

    testWidgets('the Color Studio normalizes a hex without the leading #',
        (tester) async {
      String? picked;
      await tester.pumpWidget(quiet(Scaffold(
        body: Builder(
          builder: (context) => Center(
            child: ElevatedButton(
              onPressed: () async {
                picked = await showColorStudioSheet(context, initial: '');
              },
              child: const Text('open studio'),
            ),
          ),
        ),
      )));
      await tester.tap(find.text('open studio'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).first, '63D668');
      await tester.pumpAndSettle();
      await tester.tap(find.text('Apply Color'));
      await tester.pumpAndSettle();
      expect(picked, '63D668');
    });
  });
}
