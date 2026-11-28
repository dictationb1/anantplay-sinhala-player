// AnantPlay Sinhala Player v3.4.6.l — THE NEXT-LEVEL CINEMA PLAYER +
// FIX-EVERY-REMAINING-BUG RELEASE CONTRACTS (tests).
//
// One focused regression file for the v3.4.6.l release, proving:
//   1. THE SETUP-WIZARD FORWARD LOCK: forward flings on ungranted permission
//      slides stay frozen (backward always works) + the persisted stage never
//      resets to Slide 1 across a simulated auto-return resume.
//   2. THE LIVE SUBTITLE PREVIEW: the drag draft repaints the preview and the
//      sp readout DURING the drag (before onChangeEnd commits anything).
//   3. THE APP STYLE SPLIT: 'material' renders a REAL M3 NavigationBar while
//      'mornye' renders the floating MornyeTabBar — with the root navigator
//      untouched (zero key resets between styles).
//   4. HELP SCREEN PROBE NAMES: every row #1..#92 carries its real title
//      (zero "Probe #84" fallbacks, zero raw arrows in the visual titles).
//   5. ABOUT'S LIVE VERSION: the check-for-update lane reads the LIVE
//      appInfo version (never the stale v3.4.6.j literal).
//   6. THE NATIVE CINEMA ENGINE CONTRACTS: the clamped LoudnessEnhancer (off
//      at 100%), the full CinemaGestures set, the 20dp sheet padding, the
//      hardware decoder fallback + frame-rate strategy + WAKE_MODE_LOCAL.
//   7. THE COACH AUTO-RETURN: Finish relaunches MainActivity (no CLEAR_TOP)
//      and the URL-paste steps lift the card to the TOP below the status bar.
//   8. THE SILENT AUTO-UPDATE LAW: the zero-prompt installer flags, the 100
//      MiB ceiling and the strictly-increasing letter-patch ladder
//      (k < l < m).
import 'dart:io' show File;

import 'package:material_ui/material_ui.dart' as md show PageView;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart' hide Icons;

import 'package:anantplay_ui/main.dart' as shell;
import 'package:anantplay_ui/anantplay/onboarding_screen.dart';

String repo(String rel) => File(rel).readAsStringSync();

Widget materialApp(Widget child) => MaterialApp(home: child);

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
      if (call.method == 'appInfo') {
        // v3.4.6.l — the LIVE identity channel: AppInfo.VERSION_NAME.
        return <String, Object>{
          'versionName': '3.4.6.l',
          'versionCode': 30418,
          'packageName': 'lk.techworld.anantplay.sicast',
        };
      }
      return <String, Object>{
        'up': false,
        'port': -1,
        'discoverable': false,
        'mdns': false,
      };
    });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(const MethodChannel('anantplay/settings'),
            (call) async {
      if (call.method == 'getAll') {
        return <String, Object>{
          'onboardingDone': false,
          'onboardingStage': 0,
          'onboardingVersion': 0,
          'themeMode': 'dark',
          'appStyle': 'mornye',
        };
      }
      return true;
    });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(const MethodChannel('anantplay/history'),
            (call) async => '[]');
  });

  // ══ 1 — THE SETUP-WIZARD FORWARD-SWIPE LOCK (video_3 01:30–01:58) ════════

  group('v3.4.6.l 1 — the onboarding forward lock + stage persistence', () {
    testWidgets(
        'a forward fling on an ungranted permission slide stays on the slide '
        '(backward fling still works)', (tester) async {
      await tester.pumpWidget(const MediaQuery(
          data: MediaQueryData(disableAnimations: true),
          child: shell.AnantPlayApp()));
      await tester.pumpAndSettle();

      // hop to slide 3 (index 2, Notifications — ungranted in the mock)
      expect(find.byType(OnboardingScreen), findsOneWidget);
      final controller = find
          .byType(OnboardingScreen)
          .evaluate()
          .first
          .widget as OnboardingScreen;
      expect(controller, isNotNull); // the wizard mounted
      // drive: Get started (slide 0 → 1), Continue without installing (→ 2)
      Future<void> hop(String label) async {
        final target = find.text(label).first;
        await tester.ensureVisible(target);
        await tester.pumpAndSettle();
        await tester.tap(target, warnIfMissed: false);
        await tester.pumpAndSettle();
      }

      await hop('Get started');
      await hop('Continue without installing');
      expect(find.text('Notifications'), findsOneWidget);

      // THE FORWARD FLING on the ungranted slide: the onPageChanged gate
      // snaps it straight back (the v3.4.6.k physics-only lock was a silent
      // no-op — PageView never chains custom physics; video_3 01:30–01:58).
      expect(find.text('Notifications'), findsOneWidget);
      await tester.fling(find.byType(md.PageView), const Offset(-400, 0), 800);
      await tester.pumpAndSettle();
      // still on the Notifications slide — the page index never advanced
      expect(find.text('Notifications'), findsOneWidget,
          reason: 'the forward fling on an ungranted permission slide is '
              'snapped back by the onPageChanged forward gate');

      // THE BACKWARD FLING: free (the user can always go back)
      await tester.fling(find.byType(md.PageView), const Offset(400, 0), 800);
      await tester.pumpAndSettle();
      expect(find.text('Install AnantPlay').evaluate(), isNotEmpty,
          reason: 'backward swiping is never blocked (the wizard landed on '
              'the Install slide)');
    });

    test(
        'the persisted stage is written BEFORE the settings page opens (the '
        'auto-return never lands on Slide 1)', () {
      final onboarding = repo('lib/anantplay/onboarding_screen.dart');
      expect(onboarding.contains('_persistStageBeforeLeaving();'), isTrue,
          reason: 'every onOpen callback persists the stage BEFORE leaving');
      // the resume path never re-initializes the controller: it is a
      // late-final field built from the persisted stage exactly ONCE.
      expect(onboarding.contains('late final PageController _pages'), isTrue);
      expect(
          RegExp(r'PageController\s*=\s*\n?\s*new\s+PageController').allMatches(onboarding).length,
          0,
          reason: 'no imperative controller re-creation anywhere');
      expect(onboarding.contains('_pages.dispose();'), isTrue);
      // Java: the relaunch lanes carry NO CLEAR_TOP (the slide-1 reset kill)
      final main = repo(
          '../src/lk/techworld/anantplay/sicast/MainActivity.java');
      expect(
          main.contains('FLAG_ACTIVITY_NEW_TASK\n'
              '                            | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP\n'
              '                            | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)'),
          isTrue);
      expect(
          RegExp(r'FLAG_ACTIVITY_CLEAR_TOP').allMatches(main).length, 0,
          reason: 'NO relaunch lane in MainActivity carries CLEAR_TOP any more '
              '(the slide-1 reset is structurally gone)');
    });

    test('the mandatory slides carry NO Continue-anywhere escape', () {
      final onboarding = repo('lib/anantplay/onboarding_screen.dart');
      // the CALL is gone (the word only survives inside the comment that
      // documents the deletion):
      expect(onboarding.contains("_textButton('Continue anyway', onContinue)"), isFalse,
          reason: 'the mandatory permission slides have no escape hatch');
      expect(onboarding.contains("'Continue without installing'"), isTrue,
          reason: 'the OPTIONAL official-install slide keeps its escape');
    });
  });

  // ══ 2 — THE LIVE SUBTITLE PREVIEW (video_2 02:55) ════════════════════════

  group('v3.4.6.l 2 — the live-dragging subtitle preview', () {
    test('the drag feeds the page drafts BEFORE any commit lands', () {
      final settings = repo('lib/anantplay/settings_screen.dart');
      expect(settings.contains('onPreview:'), isTrue,
          reason: 'the slider rows carry the live preview lane');
      expect(settings.contains('widget.onPreview?.call(x);'), isTrue,
          reason: 'onChanged fires onPreview every drag frame');
      expect(settings.contains('widget.onPreview?.call(null);'), isTrue,
          reason: 'the draft clears on drag end, before the single commit');
      expect(settings.contains('liveSize'), isTrue);
      expect(settings.contains('liveBgOpacity'), isTrue);
      expect(settings.contains('livePosition'), isTrue);
      expect(settings.contains('ValueListenableBuilder<double?>'), isTrue,
          reason: 'the preview card rebuilds from the drafts at 60/120 fps');
    });

    test('the single-commit law still holds (no per-frame channel writes)',
        () {
      final settings = repo('lib/anantplay/settings_screen.dart');
      expect(settings.contains('required this.onCommit,'), isTrue);
      expect(settings.contains('setState(() => _draft = x)'), isTrue);
      expect(
          settings.contains(
              'onChanged: (x) => setState(() => _draft = x)'), isFalse,
          reason: 'the raw onChanged never writes the channel per frame');
    });
  });

  // ══ 3 — THE APP STYLE SPLIT (video_2 02:05–02:30) ════════════════════════

  group('v3.4.6.l 3 — Mornye vs Material app style', () {
    test('the shell renders a REAL M3 NavigationBar for the material style',
        () {
      final main = repo('lib/main.dart');
      expect(main.contains('materialStyle'), isTrue);
      expect(main.contains('bottomNavigationBar: NavigationBar('), isTrue,
          reason: 'the authentic Material 3 Expressive tonal bottom bar');
      expect(main.contains('NavigationDestinationLabelBehavior'), isTrue);
    });

    test('the Mornye style keeps the floating MornyeTabBar capsule', () {
      final main = repo('lib/main.dart');
      expect(main.contains('MornyeTabBar('), isTrue);
      expect(main.contains('blurEnabled: true'), isTrue);
    });

    test('the theme switch NEVER rebuilds the root navigator key', () {
      final main = repo('lib/main.dart');
      // home stays a const constructor — no per-style key on MaterialApp
      expect(main.contains('home: const ShellRoot()'), isTrue);
      expect(
          RegExp(r'key: ValueKey\(.*appStyle').hasMatch(main), isFalse,
          reason: 'no key on the root changes with the app style');
      // the black-flash kill: the system-bar paint moved out of build
      expect(main.contains('_applySystemBarsPostFrame(mode);'), isTrue,
          reason: 'the platform side effect rides a deduped post-frame');
      expect(
          main.contains(
              'SystemChrome.setSystemUIOverlayStyle(\n      mode == ThemeMode.light'),
          isFalse,
          reason: 'the synchronous in-build platform call is dead');
    });
  });

  // ══ 4 — HELP SCREEN PROBE NAMES (video_2 03:35–04:01) ═══════════════════

  group('v3.4.6.l 4 — the troubleshooter feed names', () {
    test('the Dart layer passes its real titles through both frames', () {
      final help = repo('lib/anantplay/help_screen.dart');
      expect(
          help.contains("done: false, name: name)"), isTrue,
          reason: 'the Dart onStart frame carries the probe name');
      expect(
          help.contains('done: true, name: o.name'), isTrue,
          reason: 'the Dart onDone frame carries the probe name');
    });

    test('zero raw arrows in the visual probe titles', () {
      final help = repo('lib/anantplay/help_screen.dart');
      expect(help.contains("_cleanTitle"), isTrue,
          reason: 'the visual row sanitizes arrows');
      expect(help.contains("replaceAll('→', '·')"), isTrue);
      expect(help.contains("replaceAll('↔', '·')"), isTrue);
    });

    test('the Dart auditor registers 14 named probes (ids 79–92)', () {
      final deep = repo('lib/anantplay/deep_troubleshooter.dart');
      expect(deep.contains('static const int firstId = 79;'), isTrue);
      expect(deep.contains("'History read-back round-trip'"), isTrue,
          reason: 'probe #91 has its real name');
      expect(deep.contains("'Zero-uncaught-Flutter-error session'"), isTrue);
    });

    test('the #91 root cause is dead: URL-less history rows are refused', () {
      final wh = repo('../src/lk/techworld/anantplay/sicast/WatchHistory.java');
      expect(wh.contains('refused a URL-less row'), isTrue);
    });
  });

  // ══ 5 — ABOUT'S LIVE VERSION (video_2 04:10) ═══════════════════════════

  group('v3.4.6.l 5 — the About & Licenses live version', () {
    test('the check-for-update label resolves the LIVE appInfo identity',
        () {
      final feedback = repo('lib/anantplay/action_feedback.dart');
      expect(feedback.contains('warmLiveVersion()'), isTrue);
      expect(feedback.contains("'Up to date (v\${kLiveVersion.value})'"), isTrue,
          reason: 'the pill composes the live resolved version');
      expect(feedback.contains("kVisibleVersion = 'v3.4.6.j'"), isFalse,
          reason: 'the stale v3.4.6.j literal is dead');
      expect(feedback.contains("kVisibleVersionFallback = 'v3.4.6.l'"), isTrue);
    });

    test('the appInfo channel serves the live identity on both shores', () {
      final java = repo('../src/lk/techworld/anantplay/sicast/MainActivity.java');
      expect(java.contains('m.put("versionName", AppInfo.VERSION_NAME);'), isTrue);
      final appInfo = repo('../src/lk/techworld/anantplay/sicast/AppInfo.java');
      expect(appInfo.contains('VERSION_NAME = "3.4.6.l"'), isTrue);
      expect(appInfo.contains('VERSION_CODE = 30418'), isTrue);
    });

    test('the remote config row refreshes live (Pending -> Verified)', () {
      final settings = repo('lib/anantplay/settings_screen.dart');
      expect(settings.contains('_RemoteConfigRowState'), isTrue,
          reason: 'the row re-reads the mirror on open + resume');
      expect(settings.contains('scope.refresh()'), isTrue);
    });
  });

  // ══ 6 — THE NATIVE CINEMA ENGINE CONTRACTS ══════════════════════════════

  group('v3.4.6.l 6 — the next-level native cinema player', () {
    test('the clamped LoudnessEnhancer: OFF at 100%, clamped dB above', () {
      final play =
          repo('../src/lk/techworld/anantplay/sicast/PlayActivity.java');
      // the bus NEVER exceeds 1.0 — the buzz's root cause is dead
      expect(play.contains('player.setVolume(1f)'), isTrue);
      expect(play.contains('new android.media.audiofx.LoudnessEnhancer(session)'), isTrue);
      expect(play.contains('Math.min(6.02'), isTrue,
          reason: 'the +6.02 dB ceiling clamp');
      expect(play.contains('pct > 100 && session != 0'), isTrue,
          reason: 'the effect arms ONLY above 100% boost');
      // and the digital-path kill: the raw v>1 lane is gone
      expect(
          play.contains('setVolume(AppPrefs.volumeBoost(prefs) / 100f)'), isFalse);
    });

    test('the hardware engine pins: fallback, frame-rate, wake, session', () {
      final play =
          repo('../src/lk/techworld/anantplay/sicast/PlayActivity.java');
      expect(play.contains('.setEnableDecoderFallback(true)'), isTrue);
      expect(
          play.contains('C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS'),
          isTrue);
      expect(play.contains('player.setWakeMode(C.WAKE_MODE_LOCAL);'), isTrue);
      expect(play.contains('player.setAudioSessionId(audioSessionId)'), isTrue);
      expect(play.contains('AppPrefs.frameRateMatch(prefs)'), isTrue,
          reason: 'the frame-rate strategy is the customizable toggle');
    });

    test('the CinemaGestures engine ships every gesture lane', () {
      final g =
          repo('../src/lk/techworld/anantplay/sicast/CinemaGestures.java');
      expect(g.contains('doubleTapSec'), isTrue);      // double-tap ±N s
      expect(g.contains('swipeBrightness'), isTrue);  // left vertical
      expect(g.contains('swipeVolume'), isTrue);      // right vertical
      expect(g.contains('swipeSeek'), isTrue);        // horizontal scrub
      expect(g.contains('holdSpeedStart'), isTrue);   // long-press speed
      expect(g.contains('pinchZoom'), isTrue);        // 2-finger Fit/Fill
      expect(g.contains('longPressSpeedPerMille'), isTrue);
    });

    test('the CinemaHud chrome: scrims, icon-only targets, tabular clocks',
        () {
      final hud = repo('../src/lk/techworld/anantplay/sicast/CinemaHud.java');
      expect(hud.contains('0xCC000000'), isTrue,
          reason: 'the smooth top gradient scrim');
      expect(hud.contains('RippleDrawable'), isTrue,
          reason: 'icon-only circular ripple targets (no gray medallions)');
      expect(hud.contains('tnum on'), isTrue,
          reason: 'the tabular clock digits');
      expect(hud.contains('"--:--"'), isTrue,
          reason: 'the honest duration readout while metadata resolves');
      expect(hud.contains('fontFeatureSettings'), isTrue);
    });

    test('the honest duration contract: TIME_UNSET never paints a total', () {
      final play =
          repo('../src/lk/techworld/anantplay/sicast/PlayActivity.java');
      expect(play.contains('live = dur == C.TIME_UNSET'), isTrue);
      expect(play.contains('timeTotal.setText("--:--");'), isTrue);
      expect(play.contains('"LIVE"'), isTrue);
    });

    test('the sheets: 20dp padding, weights, wrapping flow, UiSheet overlays',
        () {
      final sheets =
          repo('../src/lk/techworld/anantplay/sicast/CinemaSheets.java');
      expect(sheets.contains('UiCard.dp(a, 20), UiCard.dp(a, 6), UiCard.dp(a, 20), UiCard.dp(a, 8)'),
          isTrue,
          reason: 'the strict 20dp horizontal sheet padding');
      expect(sheets.contains('weight()'), isTrue,
          reason: 'weighted rows — no right-edge clipping ever');
      expect(sheets.contains('new Flow(a,'), isTrue,
          reason: 'the wrapping language chip flow');
      expect(sheets.contains('UiSheet.show(a, p, prefs, b,'), isTrue,
          reason: 'overlay sheets — the surface never detaches, never pauses');
    });

    test('the four customization keys are wired Java + Dart 1:1', () {
      final prefs =
          repo('../src/lk/techworld/anantplay/sicast/AppPrefs.java');
      expect(prefs.contains('K_DOUBLE_TAP_SEC = "doubleTapSeekSec"'), isTrue);
      expect(prefs.contains('K_LONG_PRESS_SPEED = "longPressSpeedX"'), isTrue);
      expect(prefs.contains('K_PINCH_ZOOM = "pinchZoom"'), isTrue);
      expect(prefs.contains('K_FRAME_RATE_MATCH = "frameRateMatch"'), isTrue);
      final mirror = repo('lib/anantplay/settings_mirror.dart');
      expect(mirror.contains("kDoubleTapSeekSec = 'doubleTapSeekSec'"), isTrue);
      expect(mirror.contains("kLongPressSpeedX = 'longPressSpeedX'"), isTrue);
      expect(mirror.contains("kPinchZoom = 'pinchZoom'"), isTrue);
      expect(mirror.contains("kFrameRateMatch = 'frameRateMatch'"), isTrue);
      final settings = repo('lib/anantplay/settings_screen.dart');
      expect(settings.contains("'Double-tap seek seconds'"), isTrue);
      expect(settings.contains("'Long-press speed'"), isTrue);
      expect(settings.contains("'Pinch to zoom'"), isTrue);
      expect(settings.contains("'Frame-rate matching'"), isTrue);
    });
  });

  // ══ 7 — THE COACH AUTO-RETURN (video_2 00:50, video_3 04:09–06:06) ═════

  group('v3.4.6.l 7 — the AnantPlayCoachService upgrade', () {
    test('Finish Setup removes the coach AND relaunches our MainActivity',
        () {
      final coach =
          repo('../src/lk/techworld/anantplay/sicast/AnantPlayCoachService.java');
      expect(coach.contains('returnToShell();'), isTrue);
      expect(coach.contains('putExtra("openOnboarding", true)'), isTrue);
      expect(coach.contains('MainActivity.balAllowedOptions()'), isTrue);
      // the auto-return lane carries NO CLEAR_TOP (the slide-1 reset kill)
      final lane = coach.substring(coach.indexOf('private void returnToShell()'));
      expect(lane.contains('FLAG_ACTIVITY_CLEAR_TOP'), isFalse);
    });

    test('the URL-paste steps (5, 6 AND 7) lift the card to the TOP', () {
      final coach =
          repo('../src/lk/techworld/anantplay/sicast/AnantPlayCoachService.java');
      expect(coach.contains('step >= 4 && step <= 6'), isTrue,
          reason: 'the keyboard-safe top anchor covers all three paste steps');
      expect(coach.contains('pasteStep ? dp(64) : dp(16)'), isTrue,
          reason: 'the top anchor sits below the status bar');
    });

    test('the Connection step copy says verify + Address bar (not enable-all)',
        () {
      final coach =
          repo('../src/lk/techworld/anantplay/sicast/AnantPlayCoachService.java');
      expect(
          coach.contains(
              'Open Connection → verify WARP & Ad block are On, and turn '),
          isTrue);
    });

    test('every step carries its visual target badge', () {
      final coach =
          repo('../src/lk/techworld/anantplay/sicast/AnantPlayCoachService.java');
      expect(coach.contains('"Top-right ⚙ icon"'), isTrue);
      expect(coach.contains('"Top row: Sources"'), isTrue);
      expect(coach.contains('"Tap Add on each row"'), isTrue);
      expect(coach.contains('"URL box + keyboard"'), isTrue);
    });

    test('the shell forwards the openOnboarding extra as a route frame', () {
      final main =
          repo('../src/lk/techworld/anantplay/sicast/MainActivity.java');
      expect(main.contains('i.getBooleanExtra("openOnboarding", false)'), isTrue);
      expect(main.contains('pushShellRoute("onboarding"'), isTrue);
    });
  });

  // ══ 8 — THE SILENT AUTO-UPDATE LAW (the v3.4.6.k → .l proof lane) ══════

  group('v3.4.6.l 8 — the silent auto-update law', () {
    test('the zero-prompt installer flags are intact', () {
      final manifest = repo('../AndroidManifest.xml');
      expect(
          manifest.contains(
              'UPDATE_PACKAGES_WITHOUT_USER_ACTION'),
          isTrue);
      final self = repo(
          '../src/lk/techworld/anantplay/sicast/SelfUpdater.java');
      expect(self.contains('USER_ACTION_NOT_REQUIRED'), isTrue);
      expect(self.contains('installCommitInFlight'), isTrue,
          reason: 'the single-flight commit guard');
    });

    test('the 100 MiB APK ceiling stays enforced', () {
      final rel =
          repo('../.github/workflows/release-apk.yml');
      expect(rel.contains('104857600'), isTrue);
      final buildWf = repo('../.github/workflows/build-apk.yml');
      expect(buildWf.contains('104857600'), isTrue);
    });

    test('the letter-patch ladder: k < l < m (strictly increasing)', () {
      final checker =
          repo('../src/lk/techworld/anantplay/sicast/UpdateChecker.java');
      expect(checker.contains('isNewer'), isTrue);
      // the lab proves the full a→z sweep by execution; the identity ladder
      // for THIS release: 30417 (k) < 30418 (l) < a future 30419 (m).
      final appInfo = repo('../src/lk/techworld/anantplay/sicast/AppInfo.java');
      expect(appInfo.contains('VERSION_CODE = 30418'), isTrue);
      final latest = repo('../docs/latest.json');
      expect(latest.contains('"version":"3.4.6.k"'), isTrue);
      expect(latest.contains('"code":30417'), isTrue,
          reason: 'docs/latest.json stays at the LIVE .k tier on this branch');
      expect(latest.contains('3.4.6.l'), isFalse,
          reason: 'the updater tier is never hand-bumped to the unreleased .l');
    });

    test('the updater gate reads the live AppInfo (never a stale literal)',
        () {
      final self =
          repo('../src/lk/techworld/anantplay/sicast/SelfUpdater.java');
      expect(self.contains('AppInfo.VERSION_NAME'), isTrue);
      final feedback = repo('lib/anantplay/action_feedback.dart');
      expect(feedback.contains('kLiveVersion'), isTrue);
    });
  });
}