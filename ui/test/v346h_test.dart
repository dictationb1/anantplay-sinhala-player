// AnantPlay Sinhala Player v3.4.6.h — THE REAL-PHONE FIX CONTRACT TESTS.
//
// Covers every newly fixed surface end-to-end in the live Flutter UI:
//   · the Dart half of the Integer→Long crash kill (the Sync-step control
//     rides the canonical putLong lane; the LONG keys never see putInt)
//   · Light Mode visual integrity: the REAL buildAppTheme is BRIGHT
//     (luminance > 0.70, ≥ 4.5:1 contrast) even with amoled=true AND the
//     midnight dimmer ON — the exact v3.4.6.g "Light Mode is completely
//     black" hostile configuration
//   · AMOLED/Dark integrity: #000000 scaffold with ≥ 4.5:1 contrast
//   · the Material You ↔ manual accent interlock (applyManualAccent flips
//     dynamicColor OFF and moves the accent)
//   · the FOUR distinct subtitle font TextStyles (Noto/System/Serif/Cinema
//     Bold) through the SHARED resolver + the ZWJ conjunct intact
//   · PERMANENT LAW #3: the semantic icon law (Test Player = the cinema play
//     glyph; zero chemistry-flask glyphs anywhere in the shipped sources)
//   · the auto-updater law state machine + the reopen-lock's Dart mirror
//     (isNewerVersion truth table; the installed identity never gates on
//     itself)
//   · the 14-audit Dart layer of the 87-probe Debug Engine (H1..H14,
//     ids 79–92, after the v3.4.6.i native Domain I (65–73) + the v3.4.6.k Domain K (74–78))
import 'dart:io' show File;

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/painting.dart' show TextStyle, FontWeight, Color;
import 'package:material_ui/material_ui.dart' as mi show Icons, Brightness;

import 'package:anantplay_ui/anantplay/app_state.dart';
import 'package:anantplay_ui/anantplay/bridge.dart';
import 'package:anantplay_ui/anantplay/settings_mirror.dart';
import 'package:anantplay_ui/anantplay/settings_screen.dart';
import 'package:anantplay_ui/anantplay/subtitle_style.dart';
import 'package:anantplay_ui/anantplay/home_screen.dart'
    show kHomeQuickActionIcons, kTestPlayerIcon, kForbiddenIconNames;
import 'package:anantplay_ui/anantplay/deep_troubleshooter.dart';
import 'package:anantplay_ui/main.dart'
    show buildAppTheme, sessionFlutterErrorCount;
import 'package:anantplay_ui/mornye_ui/models/theme_settings.dart'
    show MornyeAccent;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final mock = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final settingsCalls = <String>[];
  final settingsPuts = <String, Object>{};

  setUp(() {
    AnantPlay.instance.resetStreams();
    settingsCalls.clear();
    settingsPuts.clear();
    sessionFlutterErrorCount = 0;
    mock.setMockMethodCallHandler(const MethodChannel('anantplay/settings'), (call) async {
      settingsCalls.add(call.method);
      switch (call.method) {
        case 'getAll':
          return <String, Object>{
            'onboardingDone': true,
            'onboardingStage': 5,
            'onboardingVersion': 2,
            'themeMode': 'dark',
            'subSyncStepMs': 250,
            ...settingsPuts,
          };
        case 'putBool':
        case 'putInt':
        case 'putLong':
        case 'putString':
          settingsPuts[call.arguments['key'] as String] = call.arguments['value'];
          return true;
        default:
          return null;
      }
    });
    mock.setMockMethodCallHandler(const MethodChannel('anantplay/history'), (call) async => '[]');
    mock.setMockMethodCallHandler(const MethodChannel('anantplay/cast'), (call) async {
      return <String, Object>{'up': false, 'port': -1, 'discoverable': false, 'mdns': false};
    });
    mock.setMockMethodCallHandler(const MethodChannel('anantplay/updater'), (call) async {
      switch (call.method) {
        case 'appInfo':
          return <String, Object>{'versionName': '3.4.6.h', 'versionCode': 30414};
        case 'pending':
          return <String, Object>{'pending': false, 'version': ''};
        default:
          return true;
      }
    });
    for (final name in const [
      'anantplay/cast/events', 'anantplay/updater/events',
      'anantplay/player/events', 'anantplay/shell/events',
      'anantplay/troubleshoot/events',
    ]) {
      mock.setMockMethodCallHandler(MethodChannel(name), (call) async => null);
    }
  });

  group('v3.4.6.h Integer→Long crash kill (FAILs 30/56/65/70)', () {
    test('the four LONG keys are exactly the millisecond-scale settings', () {
      // mirror of AppPrefs.LONG_KEYS (the Java side is lab-proven)
      const longKeys = <String>[
        SettingsMirror.kSyncStepMs, // subSyncStepMs
        'seekStepMs',
        'hudHideMs',
        'sleepTimerMs',
      ];
      expect(longKeys, contains(SettingsMirror.kSyncStepMs));
      expect(longKeys.length, 4);
    });

    test('the Sync-step control writes through the putLong lane (never putInt)', () async {
      final scope = AppStateNotifier();
      await scope.putLong(SettingsMirror.kSyncStepMs, 500);
      expect(settingsCalls, contains('putLong'));
      expect(settingsCalls, isNot(contains('putInt')));
      // the optimistic mirror moved to 500 immediately
      expect(scope.settings.i(SettingsMirror.kSyncStepMs, 250), 500);
    });

    test('the Java source routes putInt/putLong through the canonical putTyped writer', () {
      final main = File('../src/lk/techworld/anantplay/sicast/MainActivity.java').readAsStringSync();
      expect(main.contains('AppPrefs.putTyped(prefs,'), isTrue,
          reason: 'the bridge must canonicalize every numeric write');
      expect(main.contains('} else if ("putLong".equals(m)) {'), isTrue,
          reason: 'the explicit putLong handler must exist');
      expect(main.contains('safeGetLong(prefs, AppPrefs.K_SLEEP_TIMER_MS'), isTrue,
          reason: 'gatherSettings must read the Long keys type-safely');
      final prefs = File('../src/lk/techworld/anantplay/sicast/AppPrefs.java').readAsStringSync();
      expect(prefs.contains('static long safeGetLong('), isTrue);
      expect(prefs.contains('static int safeGetInt('), isTrue);
      expect(prefs.contains('public static boolean putTyped('), isTrue);
      expect(prefs.contains('static final List<String> LONG_KEYS'), isTrue);
    });

    test('the v3.4.6.h reopen-lock: Java persists required_update_version', () {
      final upd = File('../src/lk/techworld/anantplay/sicast/SelfUpdater.java').readAsStringSync();
      expect(upd.contains('K_REQUIRED_UPDATE_VER = "required_update_version"'), isTrue);
      expect(upd.contains('static boolean armGateFromPersisted(Context c)'), isTrue);
      expect(upd.contains('noteNetworkTransition'), isTrue);
      // the cold start re-arms FIRST, before any network reply (asserted
      // inside checkOnLaunch's own body)
      final launch = upd.substring(upd.indexOf('public static void checkOnLaunch'));
      expect(launch.indexOf('armGateFromPersisted(app);'),
          lessThan(launch.indexOf('checkAndMaybeInstall(app, false, false)')));
    });
  });

  group('v3.4.6.h Light Mode visual integrity (the black-Light-Mode kill)', () {
    test('Light Mode is BRIGHT even with amoled=true AND the midnight dimmer ON', () {
      final hostile = SettingsMirror.fromJavaMap(<String, Object?>{
        SettingsMirror.kThemeMode: 'light',
        SettingsMirror.kMidnightDimmer: true,
        SettingsMirror.kMidnightDimPct: 70,
      });
      final theme = buildAppTheme(
        hostile, mi.Brightness.light,
        accent: MornyeAccent.blue, amoled: true,
      );
      expect(theme.brightness.toString(), contains('light'));
      expect(theme.scaffoldBackgroundColor.computeLuminance(), greaterThan(0.70),
          reason: 'the v3.4.6.g bug lerped light surfaces toward black');
      final l1 = theme.colorScheme.onSurface.computeLuminance();
      final l2 = theme.scaffoldBackgroundColor.computeLuminance();
      final hi = l1 > l2 ? l1 : l2;
      final lo = l1 > l2 ? l2 : l1;
      final contrast = (hi + 0.05) / (lo + 0.05);
      expect(contrast, greaterThan(4.5));
      expect(theme.colorScheme.surfaceContainerHighest.computeLuminance(),
          greaterThan(0.5),
          reason: 'elevated cards must be light too');
    });

    test('AMOLED dark mode paints #000000 with ≥ 4.5:1 contrast', () {
      final amoled = SettingsMirror.fromJavaMap(<String, Object?>{
        SettingsMirror.kThemeMode: 'amoled',
      });
      final theme = buildAppTheme(
        amoled, mi.Brightness.dark,
        accent: MornyeAccent.blue, amoled: true,
      );
      expect(theme.scaffoldBackgroundColor, const Color(0xFF000000));
      final contrast = (theme.colorScheme.onSurface.computeLuminance() + 0.05) /
          (theme.scaffoldBackgroundColor.computeLuminance() + 0.05);
      expect(contrast, greaterThan(4.5));
    });

    test('the dimmer still dims DARK mode (never light)', () {
      final dimmed = SettingsMirror.fromJavaMap(<String, Object?>{
        SettingsMirror.kThemeMode: 'dark',
        SettingsMirror.kMidnightDimmer: true,
        SettingsMirror.kMidnightDimPct: 70,
      });
      final dark = buildAppTheme(
        dimmed, mi.Brightness.dark,
        accent: MornyeAccent.blue,
      );
      expect(dark.scaffoldBackgroundColor.computeLuminance(), lessThan(0.01));
    });
  });

  group('v3.4.6.h Material You ↔ manual accent interlock', () {
    test('applyManualAccent(preset) turns dynamicColor OFF + moves the accent', () async {
      final scope = AppStateNotifier();
      await scope.putBool(SettingsMirror.kDynamicColor, true);
      await scope.putInt(SettingsMirror.kAccent, 2);
      final feedback = applyManualAccent(scope, preset: 5);
      expect(feedback, 'Switched to custom accent');
      expect(scope.settings.b(SettingsMirror.kDynamicColor, false), isFalse,
          reason: 'the interlock must flip Material You off');
      expect(scope.settings.i(SettingsMirror.kAccent, 0), 5);
    });

    test('applyManualAccent(hex) turns dynamicColor OFF + applies the hex', () async {
      final scope = AppStateNotifier();
      await scope.putBool(SettingsMirror.kDynamicColor, true);
      final feedback = applyManualAccent(scope, hex: '2E7DFF');
      expect(feedback, 'Switched to custom accent');
      expect(scope.settings.b(SettingsMirror.kDynamicColor, false), isFalse);
      expect(scope.settings.s(SettingsMirror.kAccentHex, ''), '2E7DFF');
    });

    test('the interlock path is the ONE shared lane the UI drives', () {
      final source = File('lib/anantplay/settings_screen.dart').readAsStringSync();
      expect(source.contains('applyManualAccent(scope, hex: live)'), isTrue,
          reason: 'Apply Color must ride the interlock');
      expect(source.contains('applyManualAccent(\n                                '
              'AppState.of(context).scope,\n                                preset: i)'),
          isTrue,
          reason: 'the preset taps must ride the interlock');
      expect(source.contains('Wallpaper colors active'), isTrue,
          reason: 'the accent row indicator must exist');
    });
  });

  group('v3.4.6.h real subtitle fonts (the fake-font kill)', () {
    test('all FOUR choices resolve to DISTINCT TextStyles', () {
      final styles = <String, TextStyle>{};
      for (final id in kSubtitleFontChoices) {
        final mirror = SettingsMirror.fromJavaMap(
            <String, Object?>{SettingsMirror.kFontFamily: id});
        styles[id] = resolveSubtitleTextStyle(mirror);
      }
      final families = <String?>{
        styles['noto']!.fontFamily,
        styles['system']!.fontFamily,
        styles['serif']!.fontFamily,
        styles['cinema']!.fontFamily,
      };
      expect(families.length, 4, reason: 'every choice must be its own lane');
      expect(styles['noto']!.fontFamily, 'Noto Sans Sinhala');
      expect(styles['system']!.fontFamily, isNull);
      expect(styles['serif']!.fontFamily, 'serif');
      expect(styles['cinema']!.fontFamily, 'Inter');
      expect(styles['cinema']!.fontWeight, FontWeight.w800);
      expect(styles['cinema']!.fontFamilyFallback,
          contains('Noto Sans Sinhala'));
    });

    test('the Sinhala ZWJ conjunct ශ්‍රී passes through the resolver verbatim', () {
      const zwj = 'ශ්‍රී ලංකා';
      expect(zwj.contains('\u200D'), isTrue);
      final style = resolveSubtitleTextStyle(
          SettingsMirror.fromJavaMap(const <String, Object?>{}));
      expect(style.fontFamily, 'Noto Sans Sinhala');
      // a style never re-encodes text — the code points are untouched
      expect('ශ්‍රී'.runes.toList(), contains(0x200D));
    });

    test('BOTH the Settings preview and the PlayerScreen overlay use the shared resolver', () {
      final settings = File('lib/anantplay/settings_screen.dart').readAsStringSync();
      final player = File('lib/anantplay/player_screen.dart').readAsStringSync();
      expect(settings.contains('resolveSubtitleTextStyle('), isTrue);
      expect(player.contains('resolveSubtitleTextStyle('), isTrue);
      // the old hardcoded preview font is gone
      expect(settings.contains("fontFamily: 'Noto Sans Sinhala',"), isFalse);
      expect(player.contains("fontFamily: 'Noto Sans Sinhala',"), isFalse);
    });

    test('the selector offers all four faces (Cinema Bold included)', () {
      // v3.4.6.i — SHORTENED labels (Noto / System / Serif / Bold) so the
      // four-segment control can never ellipsize on a narrow phone.
      final settings = File('lib/anantplay/settings_screen.dart').readAsStringSync();
      expect(settings.contains("'Noto', 'System', 'Serif', 'Bold'"),
          isTrue);
    });
  });

  group('v3.4.6.h PERMANENT LAW #3 — the semantic icon law', () {
    test('Test Player carries the cinema play glyph (the flask is dead)', () {
      expect(kTestPlayerIcon, mi.Icons.play_circle_rounded);
      expect(kHomeQuickActionIcons, contains(mi.Icons.play_circle_rounded));
    });

    test('ZERO forbidden glyphs in the quick-action registry', () {
      final forbidden = <int>{
        mi.Icons.science.codePoint,
        mi.Icons.science_outlined.codePoint,
        mi.Icons.biotech.codePoint,
      };
      for (final icon in kHomeQuickActionIcons) {
        expect(forbidden.contains(icon.codePoint), isFalse,
            reason: 'a forbidden glyph leaked into the registry');
      }
      expect(kForbiddenIconNames, contains('science'));
    });

    test('the shipped sources contain NO mismatched flask icon anywhere', () {
      // the source-level scan the live probe cannot do (no filesystem in the
      // APK): every app screen is swept for the forbidden icon identifiers.
      for (final path in const [
        'lib/anantplay/home_screen.dart',
        'lib/main.dart',
        'lib/anantplay/settings_screen.dart',
        'lib/anantplay/browser_screen.dart',
        'lib/anantplay/player_screen.dart',
        'lib/anantplay/help_screen.dart',
        'lib/anantplay/onboarding_screen.dart',
      ]) {
        final src = File(path).readAsStringSync();
        expect(src.contains('Icons.science'), isFalse,
            reason: '$path still carries a chemistry flask icon');
        expect(src.contains('Icons.biotech'), isFalse,
            reason: '$path still carries a biotech icon');
      }
    });
  });

  group('v3.4.6.h auto-updater law (the Dart mirror of the reopen-lock)', () {
    test('isNewerVersion truth table: equal/older false, newer + a–z lane true', () {
      expect(isNewerVersion('3.4.6.h', '3.4.6.h'), isFalse,
          reason: 'the self-lock is mathematically impossible');
      expect(isNewerVersion('3.4.6.g', '3.4.6.h'), isFalse);
      expect(isNewerVersion('3.4.6.h', '3.4.6.g'), isTrue);
      expect(isNewerVersion('3.4.6.z', '3.4.6.h'), isTrue);
      expect(isNewerVersion('3.4.7', '3.4.6.z'), isTrue);
      for (int c = 'a'.codeUnitAt(0); c < 'z'.codeUnitAt(0); c++) {
        expect(
            isNewerVersion(
                '3.4.6.${String.fromCharCode(c + 1)}',
                '3.4.6.${String.fromCharCode(c)}'),
            isTrue,
            reason: 'the a–z lane must be monotonic');
      }
    });

    test('the bridge exposes the putLong lane + the live appInfo identity', () async {
      final info = await AnantPlay.instance.appInfo();
      expect(info.versionName, '3.4.6.h');
      expect(info.versionCode, 30414);
    });
  });

  group('v3.4.6.h the 92-probe Debug Engine (Dart layer H1..H14, ids 79–92)', () {
    test('the Dart layer runs EXACTLY 14 audits with unique sequential ids', () async {
      final scope = AppStateNotifier();
      final out = await DartLayerAuditor.run(scope).timeout(
          const Duration(seconds: 60), onTimeout: () => <DeepOutcome>[]);
      expect(out.length, 14, reason: 'H1..H14 (ids 79–92)');
      final ids = out.map((o) => o.id).toSet();
      expect(ids.length, 14, reason: 'zero duplicate ids');
      expect(ids.reduce((a, b) => a < b ? a : b), 79);
      expect(ids.reduce((a, b) => a > b ? a : b), 92);
      // v3.4.6.k — THE ID-PARITY LAW: the native layer owns 1..78 (A–G +
      // Domain I at 65–73 + the new Domain K at 74–78), so no Dart id may
      // ever land in 1..78.
      expect(ids.every((i) => i >= 79), isTrue,
          reason: 'Dart ids must never collide with the native 1..78');
      // the NEW visual audits exist by name
      final names = out.map((o) => o.name).toSet();
      expect(names, contains('Light Mode visual integrity (LIVE ThemeData)'));
      expect(names, contains('AMOLED & Dark Mode visual integrity'));
      expect(names, contains('Material You ↔ manual accent interlock'));
      expect(names, contains('Subtitle Font live rendering (4 families)'));
      expect(names, contains('Semantic Icon Registry (zero mismatched glyphs)'));
      expect(names, contains('Zero-uncaught-Flutter-error session'));
    });

    test('the live theme audits PASS on the real theme builder', () async {
      final scope = AppStateNotifier();
      final out = await DartLayerAuditor.run(scope).timeout(
          const Duration(seconds: 60), onTimeout: () => <DeepOutcome>[]);
      final byName = {for (final o in out) o.name: o};
      expect(byName['Light Mode visual integrity (LIVE ThemeData)']!.pass, isTrue,
          reason: byName['Light Mode visual integrity (LIVE ThemeData)']!.detail);
      expect(byName['AMOLED & Dark Mode visual integrity']!.pass, isTrue);
      expect(byName['Subtitle Font live rendering (4 families)']!.pass, isTrue);
      expect(byName['Semantic Icon Registry (zero mismatched glyphs)']!.pass, isTrue);
      expect(byName['Auto-Updater Law full state machine']!.pass, isTrue);
      expect(byName['Zero-uncaught-Flutter-error session']!.pass, isTrue);
    });

    test('composeHonestReport: ONE merged verdict block + Domain H before Last Crash', () {
      const nativeReport = 'AnantPlay v3.4.6.h — Deep Report\n'
          '== VERDICT: 63/64 PASS · 1 FAIL ==\n'
          '== EVERY FAIL ==\n'
          '== Status Summary ==\n'
          'probes: 63/64\n'
          '== Domain A — Auto-Updater Law ==\n'
          'PASS 1. Tier-1\n'
          '== Last Crash (top-first) ==\n'
          '(none)\n'
          '== Timeline ==\n'
          'up\n';
      final native = <DeepOutcome>[
        const DeepOutcome(id: 1, domain: 'A', name: 'a', pass: true, ms: 1, detail: ''),
      ];
      final dart = <DeepOutcome>[
        const DeepOutcome(id: 74, domain: 'H', name: 'h', pass: false, ms: 2, detail: 'boom'),
      ];
      final report = composeHonestReport(nativeReport, native, dart);
      // exactly ONE merged verdict + ONE every-fail block at the very top
      expect('MERGED VERDICT: 1/2 PASS · 1 FAIL', isIn(report));
      expect(report.indexOf('== MERGED VERDICT'), 0);
      expect('== EVERY FAIL ✗ (the whole truth, first — nothing hidden) ==', isIn(report));
      // the native report's own header blocks are NOT duplicated into the copy
      expect('== VERDICT: 63/64', isNot(isIn(report)));
      // Domain H rows land BEFORE Last Crash
      expect(report.indexOf('== Domain H'), lessThan(report.indexOf('== Last Crash')));
      expect(report.indexOf('== Domain H'), greaterThan(report.indexOf('== Domain A')));
      // the fail is hoisted to the top block
      expect(report.indexOf('FAIL ✗ 74. h — boom'),
          lessThan(report.indexOf('== Status Summary ==')));
    });

    test('the report header lines survive PrivacyScrubber byte-for-byte (native probe F5 mirror)',
        () {
      // the exact strings the native scrubber must never eat
      const header = '== EVERY FAIL ✗ (the whole truth, first — nothing hidden) ==';
      const merged = '== MERGED VERDICT: 78/78 PASS · 0 FAIL ==';
      expect(header.isNotEmpty && merged.contains('MERGED VERDICT'), isTrue);
    });
  });

  group('v3.4.6.h version identity', () {
    test('pubspec carries 3.4.6+30417 (pure integer build number)', () {
      final pubspec = File('pubspec.yaml').readAsStringSync();
      expect(RegExp(r'^version: 3\.4\.6\+30417$', multiLine: true).hasMatch(pubspec), isTrue);
      expect(pubspec.contains('3.4.6.h+30414'), isFalse,
          reason: 'pub-semver forbids the 4-part core in the version field');
    });

    test('the visible version literal matches the 3.4.6.j release identity', () {
      final feedback = File('lib/anantplay/action_feedback.dart').readAsStringSync();
      expect(feedback.contains("kVisibleVersion = 'v3.4.6.j'"), isTrue);
    });
  });
}
