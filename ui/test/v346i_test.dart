// AnantPlay Sinhala Player v3.4.6.i — THE VIDEO-AUDIT FIX CONTRACTS (tests).
//
// Every defect caught on the 5 screen-recorded videos + the 4 screenshots +
// the v3.4.6.h Troubleshoot log gets a regression test here:
//   · FAIL 68 / settings snap-back → the raw-mirror law (probe I1's shape)
//   · ForceUpdateScreen double-download → the zero-double-download law
//   · Auto-update OFF switch removed → the zero-disable-switch law
//   · AnantPlayInstaller no-asset/busy/needs-permission → the resolver
//   · PermissionGuideActivity → the manifest + intent contract
//   · Blank browser → the HOME_URL bootstrap + theme-aware container
//   · Player subtitle HUD-lift → the 136/116/20 constants
//   · Light-mode snackbar → the WCAG contrast law
//   · Onboarding red-pen layout → no Next, slide-5 permission rows, Finish
//   · 3-index Sources Hub → the exact preset feeds
import 'dart:io' show File;
import 'dart:math' as math show pow;

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart' show MaterialApp, Widget;

import 'package:anantplay_ui/anantplay/force_update_screen.dart';

/// Reads a repo file from the ui/ module root (test current directory).
String repo(String rel) => File(rel).readAsStringSync();

/// A minimal MaterialApp shell for the gate's widget test (material_ui's
/// own MaterialApp re-export, matching the app's import surface).
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
          'overlay': false, 'notif': false, 'battery': false,
          'installs': false, 'official': false,
        };
      }
      return <String, Object>{
        'up': false, 'port': -1, 'discoverable': false, 'mdns': false,
      };
    });
  });

  group('v3.4.6.i I1/I2 — the raw-mirror + zero-double-download laws', () {
    test('gatherSettings starts from the FULL prefs map (the FAIL 68 kill)',
        () {
      final main =
          repo('../src/lk/techworld/anantplay/sicast/MainActivity.java');
      expect(main.contains('new LinkedHashMap<>(prefs.getAll())'), isTrue,
          reason: 'the live mirror must start from prefs.getAll(), never a '
              'hand-maintained whitelist');
      expect(main.contains('AppPrefs.density(prefs)'), isTrue,
          reason: 'the density reader returns the FULL three-value string');
    });

    test('the verified APK + metadata SURVIVE the installer handoff', () {
      final upd = repo('../src/lk/techworld/anantplay/sicast/SelfUpdater.java');
      expect(upd.contains('ZERO-DOUBLE-DOWNLOAD LAW'), isTrue);
      // the handoff body: verify → install → (comment block) → fire(ready).
      // The v3.4.6.h sequence 'install(c, apk); apk.delete(); clearPending…'
      // at the handoff is GONE — the sweep lives only in the receiver's
      // STATUS_SUCCESS branch.
      expect(upd.contains('install(c, apk);\n            apk.delete();'), isFalse,
          reason: 'the commit-time delete is dead (the 42.8 MB × 2 catch)');
      final successIdx = upd.indexOf('STATUS_SUCCESS');
      final sweepAfterSuccess = upd.indexOf('SelfUpdateCore.deleteAll(updatesDir(c));', successIdx);
      expect(successIdx, greaterThan(0));
      expect(sweepAfterSuccess, greaterThan(successIdx),
          reason: 'the verified-cache sweep must live in the SUCCESS branch');
    });

    test('ForceUpdateScreen: honest labels + no follow-up network check', () {
      final dart = repo('lib/anantplay/force_update_screen.dart');
      expect(dart.contains("'Install Downloaded Update'"), isTrue);
      // v3.4.6.m — the installer handoff label contract: 'Installer Active…'
      // (disabled 8 s, Part_0.01 04:46–05:15) → 'Install Downloaded Update'
      // (< 50 ms cached-APK re-open; the red 'Update Now' flash from video_6
      // 03:20 is dead).
      expect(dart.contains("'Installer Active…'"), isTrue);
      expect(dart.contains("'Installer Open…'"), isFalse,
          reason: 'v3.4.6.m: the label is Installer Active… (8 s disabled)');
      expect(dart.contains("'Open Installer Again'"), isFalse);
      expect(dart.contains("'Opening installer…'"), isFalse);
      expect(dart.contains('ZERO-DOUBLE-DOWNLOAD'), isTrue);
      expect(dart.contains('if (!pending.pending) {\n      await AnantPlay.instance.checkForUpdate();'), isFalse,
          reason: 'the v3.4.6.h follow-up checkForUpdate network leg is dead');
    });

    testWidgets('the gate renders the honest cached-APK label on bootstrap',
        (tester) async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(const MethodChannel('anantplay/updater'),
              (call) async {
        if (call.method == 'pending') {
          return <String, Object>{'pending': true, 'version': '3.4.6.j'};
        }
        if (call.method == 'appInfo') {
          return <String, Object>{
            'versionName': '3.4.6.i', 'versionCode': 30415,
            'packageName': 'lk.techworld.anantplay.sicast',
          };
        }
        return true;
      });
      await tester.pumpWidget(
          forceUpdateApp());
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));
      // a strictly-newer pending APK → the button is 'Install Downloaded
      // Update' (NEVER a re-download).
      expect(find.text('Install Downloaded Update'), findsOneWidget);
    });
  });

  group('v3.4.6.i I3 — the zero-disable-switch law (PERMANENT LAW #1)', () {
    test('the Auto-update ON/OFF switch is gone from SettingsScreen', () {
      final settings = repo('lib/anantplay/settings_screen.dart');
      expect(settings.contains("title: 'Auto-update'"), isFalse,
          reason: 'auto-updating is mandatory — the switch can never return');
      expect(settings.contains("title: 'Check for update now'"), isTrue);
    });

    test('the Midnight dimmer shows the clean disabled state in Light Mode',
        () {
      final settings = repo('lib/anantplay/settings_screen.dart');
      expect(settings.contains("'Active in Dark & AMOLED modes'"), isTrue);
      expect(settings.contains('enabled: Theme.of(context).brightness == Brightness.dark'),
          isTrue);
    });
  });

  group('v3.4.6.i I4 — the AnantPlayInstaller fixes', () {
    test('the asset resolver accepts GitHub RELATIVE hrefs + prefers phone',
        () {
      final installer =
          repo('../src/lk/techworld/anantplay/sicast/AnantPlayInstaller.java');
      expect(installer.contains('extractApkDownloadUrls'), isTrue);
      expect(installer.contains('https://github.com" + u'), isTrue,
          reason: 'relative /AnantPlay/android/… hrefs must absolutize');
      expect(installer.contains('if (!workerStarted) running.set(false);'),
          isTrue, reason: 'the busy leak is dead on every path');
      expect(installer.contains('resumeIfWaiting'), isTrue,
          reason: 'the auto-resume-on-grant contract exists');
      expect(installer.contains('officialUpdateFacts'), isTrue,
          reason: 'the official AnantPlay update pill facts lane exists');
    });

    test('the stacked-launch guide helper exists for every permission site',
        () {
      final guide = repo(
          '../src/lk/techworld/anantplay/sicast/PermissionGuideActivity.java');
      expect(guide.contains('startActivities(new Intent[]{ settings, guideIntent })'),
          isTrue);
      for (final g in ['GUIDE_OVERLAY', 'GUIDE_UNKNOWN_SOURCES', 'GUIDE_BATTERY', 'GUIDE_NOTIFICATIONS']) {
        expect(guide.contains(g), isTrue, reason: 'the $g scenario is declared');
      }
    });

    test('the manifest registers the translucent guide activity', () {
      final manifest = repo('../AndroidManifest.xml');
      expect(manifest.contains('.PermissionGuideActivity'), isTrue);
      expect(manifest.contains('Theme.Translucent.NoTitleBar'), isTrue);
      expect(manifest.contains('windowIsFloating="true"'), isTrue);
    });

    test('MainActivity wires EVERY permission site through the guide', () {
      final main = repo('../src/lk/techworld/anantplay/sicast/MainActivity.java');
      for (final site in [
        'openOverlayWithGuide',
        'openUnknownSourcesWithGuide',
        'openBatteryWithGuide',
        'openNotificationsWithGuide',
      ]) {
        expect(main.contains('PermissionGuideActivity.$site'), isTrue,
            reason: 'the $site lane must be guided');
      }
      expect(main.contains('AnantPlayInstaller.resumeIfWaiting(this)'), isTrue,
          reason: 'onResume auto-resumes the official install after the grant');
    });
  });

  group('v3.4.6.i I6/I9 — the browser + the 3-index Sources Hub', () {
    test('the headless WebView boots to HOME_URL (never blank)', () {
      final view =
          repo('../src/lk/techworld/anantplay/sicast/WebBrowserView.java');
      // v3.4.6.j — the boot lane now resolves through the LIVE engine home
      // (homeUrlFor): google / duckduckgo / startpage / brave per the
      // Settings switch, HOME_URL the never-blank fallback.
      expect(view.contains('homeUrlFor(prefs))'), isTrue);
      expect(view.contains('static String homeUrlFor('), isTrue);
      expect(view.contains('HOME_URL = "https://www.google.com/"'), isTrue);
      expect(view.contains('https://duckduckgo.com/'), isTrue);
      expect(view.contains('https://www.startpage.com/'), isTrue);
      expect(view.contains('https://search.brave.com/'), isTrue);
    });

    test('the theme-aware container paints #FFFFFF light / #0F172A dark', () {
      final view =
          repo('../src/lk/techworld/anantplay/sicast/WebBrowserView.java');
      expect(view.contains('themedBackground()'), isTrue);
      expect(view.contains('0xFFFFFFFF'), isTrue);
      expect(view.contains('0xFF0F172A'), isTrue);
    });

    test('all THREE exact index feeds are built-in presets', () {
      final browser = repo('lib/anantplay/browser_screen.dart');
      expect(browser.contains(
          'https://raw.githubusercontent.com/dictationb1/srilanka-anantplay/main/index.json'),
      isTrue);
      expect(browser.contains(
          'https://anantplay-si-subs-bridge.techworldlk.workers.dev/index.json'),
      isTrue);
      expect(browser.contains(
          'https://raw.githubusercontent.com/AnantPlay/index/main/index.json'),
      isTrue);
      expect(browser.contains('_AddIndexCard'), isTrue,
          reason: 'the hub is editable/addable, never one single box');
    });

    test('the browser bar exposes the 1-tap Sources Hub + Home', () {
      final browser = repo('lib/anantplay/browser_screen.dart');
      expect(browser.contains("'Sources & Speed Dial'"), isTrue);
      expect(browser.contains('openBrowserHome'), isTrue);
      expect(browser.contains(r'Ad block: ${s.b(SettingsMirror.kAdBlock, true)'), isTrue);
    });

    test('the hub sheet + bar are plain widgets (no platform dependency)', () {
      final browser = repo('lib/anantplay/browser_screen.dart');
      expect(browser.contains('_SourcesHubSheet'), isTrue);
      expect(browser.contains('_BrowserBar'), isTrue);
      expect(browser.contains("tooltip: 'Home (Google)'"), isTrue);
    });
  });

  group('v3.4.6.i I7/I8 — the player HUD-lift + light-mode snackbar', () {
    test('the subtitle HUD-lift constants carry the exact contract', () {
      final player = repo('lib/anantplay/player_screen.dart');
      expect(player.contains('compactSubHudLiftPortraitDp = 136'), isTrue);
      expect(player.contains('compactSubHudLiftLandscapeDp = 116'), isTrue);
      expect(player.contains('compactSubSafeInsetDp = 20'), isTrue);
      expect(player.contains('AnimatedPadding'), isTrue);
    });

    test('the test cues SPAN the whole stream (the 00:17 vanish kill)', () {
      final bridge =
          repo('../src/lk/techworld/anantplay/sicast/Media3TextureBridge.java');
      expect(bridge.contains('TEST-STREAM SPAN'), isTrue);
      expect(bridge.contains('634_000'), isTrue);
    });

    test('the light snackbar is a WCAG-clean white card', () {
      final theme = repo('lib/mornye_ui/theme/mornye_theme.dart');
      expect(theme.contains('THE LIGHT-MODE SNACKBAR'), isTrue);
      expect(theme.contains('0xFFFFFFFF'), isTrue);
      expect(theme.contains('0xFF111118'), isTrue);
      // the arithmetic: #FFFFFF vs #111118 ≥ 4.5:1 (computed inline).
      const bg = 0xFFFFFFFF, fg = 0xFF111118;
      double lum(int argb) {
        double ch(int v) {
          double c = v / 255.0;
          return c <= 0.03928 ? c / 12.92 : math.pow((c + 0.055) / 1.055, 2.4).toDouble();
        }
        final r = ch((argb >> 16) & 0xFF), g = ch((argb >> 8) & 0xFF), b = ch(argb & 0xFF);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
      }
      final l1 = lum(bg), l2 = lum(fg);
      final contrast = (l1 > l2 ? l1 : l2 + 0.05) / (l1 > l2 ? l2 : l1 - 0.001 + 0.05);
      expect(contrast, greaterThan(4.5),
          reason: 'the light snackbar text must clear 4.5:1');
    });

    test('the ONE-toast law: the intermediate copy is silent', () {
      final help = repo('lib/anantplay/help_screen.dart');
      expect(help.contains('ONE TOAST AT THE VERY END'), isTrue);
      expect(help.contains('const [], true);'), isTrue,
          reason: 'the intermediate auto-copy passes the silent flag');
    });
  });

  group('v3.4.6.i — the onboarding red-pen layout', () {
    test('the bottom-right Next button is gone; dots + Back remain', () {
      final ob = repo('lib/anantplay/onboarding_screen.dart');
      expect(ob.contains("child: const Text('Next'"), isFalse);
      expect(ob.contains("const Text('Back'"), isTrue);
      expect(ob.contains('the Next button is GONE'), isTrue);
    });

    test('slide 5 carries the TWO permission rows + ONE Continue', () {
      final ob = repo('lib/anantplay/onboarding_screen.dart');
      expect(ob.contains("'Background Activity (No restrictions)'"), isTrue);
      expect(ob.contains("'Allow App Updates'"), isTrue);
      expect(ob.contains("'Continue'"), isTrue);
      expect(ob.contains('_StabilityCard'), isTrue);
      expect(ob.contains("'Enabled ✓'"), isTrue);
      expect(ob.contains("'Enable'"), isTrue);
    });

    test('the 55/40 geometry: the hero rides an Expanded flex stage', () {
      final ob = repo('lib/anantplay/onboarding_screen.dart');
      expect(ob.contains('flex: 3,'), isTrue);
      expect(ob.contains('flex: 2,'), isTrue);
      expect(ob.contains('FittedBox'), isTrue);
    });

    test('Skip/Finish pop a pushed route + persist the wizard state', () {
      final ob = repo('lib/anantplay/onboarding_screen.dart');
      expect(ob.contains('navigator.pop();'), isTrue);
      expect(ob.contains('completeOnboarding();'), isTrue);
    });

    test('slide 1 subtitle is the concise one-line form', () {
      final ob = repo('lib/anantplay/onboarding_screen.dart');
      expect(ob.contains("'Sinhala subtitles cinema — one receiver, zero forks.'"), isTrue);
    });
  });

  group('v3.4.6.i — the accent AppSwitch + Mornye icons', () {
    test('the ON track paints the live theme primary (never hardcoded green)',
        () {
      final sw = repo('lib/mornye_ui/widgets/app_switch.dart');
      expect(sw.contains('final trackOn = scheme.primary;'), isTrue);
      expect(sw.contains('Color.lerp('), isFalse,
          reason: 'the green lerp is gone');
    });

    test('the icon registry carries the Help/Settings glyph remaps', () {
      final icons = repo('lib/mornye_ui/theme/mornye_icons.dart');
      for (final needle in [
        'Icons.health_and_safety_rounded',
        'Icons.restart_alt_rounded',
        'Icons.movie_rounded',
        'Icons.cast_rounded',
        'Icons.backup_rounded',
        'Icons.palette_rounded',
        'Icons.upgrade_rounded',
      ]) {
        expect(icons.contains(needle), isTrue,
            reason: '$needle must have a Mornye counterpart');
      }
    });

    test('Help + Settings render icons through mornyeIconFor', () {
      expect(
          repo('lib/anantplay/help_screen.dart').contains('mornyeIconFor(icon)'),
          isTrue);
      expect(repo('lib/anantplay/settings_screen.dart')
          .contains('mornyeIconFor(widget.icon)'), isTrue);
    });
  });

  group('v3.4.6.i — version identity', () {
    test('pubspec is 3.4.6+30419 (pure integer build)', () {
      final pubspec = repo('pubspec.yaml');
      expect(
          RegExp(r'^version: 3\.4\.6\+30419$', multiLine: true)
              .hasMatch(pubspec),
          isTrue);
    });

    test('AppInfo declares 3.4.6.m / 30419', () {
      final appInfo =
          repo('../src/lk/techworld/anantplay/sicast/AppInfo.java');
      expect(appInfo.contains('"3.4.6.m"'), isTrue);
      expect(appInfo.contains('30419'), isTrue);
    });

    test('docs/latest.json stays at the LIVE 3.4.6.l / 30418 tier', () {
      final latest = repo('../docs/latest.json');
      // v3.4.6.m — the LIVE tier is 3.4.6.l / 30418 (this branch NEVER
      // touches latest.json; the release workflow rewrites it to .m / 30419
      // only when v3.4.6.m ships).
      expect(latest.contains('"version":"3.4.6.l"'), isTrue);
      expect(latest.contains('"code":30418'), isTrue);
    });

    test('the bridge exposes the raw getString/getInt lanes', () {
      final bridge = repo('lib/anantplay/bridge.dart');
      expect(bridge.contains('Future<String> getString('), isTrue);
      expect(bridge.contains('Future<int> getInt('), isTrue);
    });

    test('the officialAnantPlay update facts model + lane exist', () {
      final bridge = repo('lib/anantplay/bridge.dart');
      expect(bridge.contains('class OfficialAnantPlayUpdate'), isTrue);
      expect(bridge.contains('officialUpdateFacts'), isTrue);
    });

    test('the Home + Settings update pills render through the lane', () {
      expect(repo('lib/anantplay/home_screen.dart')
          .contains('_OfficialAnantPlayUpdatePill'), isTrue);
      expect(repo('lib/anantplay/settings_screen.dart')
          .contains('_OfficialAnantPlayUpdateRow'), isTrue);
    });

    test('the segments never ellipsize: Noto/System/Serif/Bold + engine shorts',
        () {
      final settings = repo('lib/anantplay/settings_screen.dart');
      expect(settings.contains("'Noto', 'System', 'Serif', 'Bold'"), isTrue);
      expect(settings.contains("'Google', 'DDG', 'Start', 'Brave'"), isTrue);
    });

    test('the bottom bar docked/floating + labels apply to the live shell',
        () {
      final main = repo('lib/main.dart');
      expect(main.contains('barDocked'), isTrue);
      expect(main.contains('showLabels: barLabels'), isTrue);
      expect(main.contains('docked: true'), isTrue);
    });

    test('the tab bar carries the docked + showLabels contract', () {
      final chrome = repo('lib/mornye_ui/widgets/mornye_chrome.dart');
      expect(chrome.contains('this.docked = false,'), isTrue);
      expect(chrome.contains('this.showLabels = true,'), isTrue);
      expect(chrome.contains('if (showLabels)'), isTrue);
    });

    test('the Interface scale rides the density text scaler', () {
      final main = repo('lib/main.dart');
      expect(main.contains('_DensityTextScaler'), isTrue);
      expect(main.contains('0.94'), isTrue);
      expect(main.contains('1.06'), isTrue);
    });

    test('the jsDelivr mirror fallback exists on both shores', () {
      final core =
          repo('../src/lk/techworld/anantplay/sicast/SelfUpdateCore.java');
      expect(core.contains('jsdelivrLatestJsonUrl'), isTrue);
      expect(core.contains('cdn.jsdelivr.net/gh/'), isTrue);
      expect(core.contains('fetchViaMirrorJson'), isTrue);
    });

    test('probe 2 reports the winning route + sanitized failures', () {
      final probe = repo('../src/lk/techworld/anantplay/sicast/DeepProbe.java');
      expect(probe.contains('route: ' r'"' ' + usedRoute'), isTrue);
      expect(probe.contains('private static String sanitize'), isTrue);
    });

    test('the DeepProbe engine carries the 9 new Domain I probes', () {
      final probe = repo('../src/lk/techworld/anantplay/sicast/DeepProbe.java');
      for (final p in [
        'probeI1', 'probeI2', 'probeI3', 'probeI4', 'probeI5',
        'probeI6', 'probeI7', 'probeI8', 'probeI9',
      ]) {
        expect(probe.contains('static Outcome $p('), isTrue,
            reason: 'the $p probe must exist');
      }
      expect(probe.contains('THE v3.4.6.i VIDEO-AUDIT FIX CONTRACTS'), isTrue);
    });

    test('the Home header is the fixed 28sp Mornye large header', () {
      final home = repo('lib/anantplay/home_screen.dart');
      expect(home.contains("'AnantPlay Player'"), isTrue);
      expect(home.contains('fontSize: 28'), isTrue);
      expect(home.contains('FontWeight.w700'), isTrue);
      expect(home.contains('letterSpacing: -0.3'), isTrue);
    });

    test('the Got it button is an optimistic 44dp tap target', () {
      final home = repo('lib/anantplay/home_screen.dart');
      expect(home.contains('_tipDismissed = true'), isTrue);
      expect(home.contains("putBool('homeTipDismissed', true)"), isTrue);
      expect(home.contains('const Size(64, 44)'), isTrue);
    });
  });
}
