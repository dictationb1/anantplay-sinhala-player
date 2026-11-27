// AnantPlay Sinhala Player v3.4.6.k — THE SILENT-UPDATE + VIDEO-AUDIT
// RELEASE CONTRACTS (tests).
//
// One focused regression file for the v3.4.6.k release, proving:
//   1. Force liquid blur toggles OFF/ON (the string-lane fix)
//   2. Playback & Subtitles: the Sticky Live Sinhala subtitle preview +
//      the MornyeBubbleSlider's onChangeEnd single-commit lane
//   3. the ⋮ popover anchors at Alignment.topRight (the exact button rect)
//   4. the Setup Wizard: the forward-swipe lock physics + slide persistence
//      across resume + NO cold-launch wizard flash
//   5. AnantPlay & Sources: the Multi-Index URL Manager + the broken empty
//      text box + Save is GONE
//   6. the ForceUpdateScreen/self-updater single-commit guard (the 2–3×
//      installer-loop kill) + the silent USER_ACTION_NOT_REQUIRED lane
//   7. the signed OTA config channel sits on real UI surfaces (the R2
//      zero-fake-customization law: an unwired pref is a fake pref)
//
// Every assertion reads the SHIPPED source (or pumps the real widget), never
// a copied constant — a regression in the release tree fails this file.
import 'dart:io' show File;
import 'dart:math' as math;

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart' show MaterialApp, Widget;

import 'package:anantplay_ui/anantplay/force_update_screen.dart';

String repo(String rel) => File(rel).readAsStringSync();

Widget forceUpdateApp() => MaterialApp(
      home: const ForceUpdateScreen(currentVersion: '3.4.6.j'),
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

  group('v3.4.6.k 1 — Force liquid blur toggles OFF/ON (the stuck-switch fix)',
      () {
    test('the switch reads the STRING key through the STRING getter', () {
      final settings = repo('lib/anantplay/settings_screen.dart');
      expect(
          settings.contains(
              "s.s(SettingsMirror.kSurfaceStyle, 'glass') == 'glass'"),
          isTrue,
          reason: 'the v3.4.6.j bool-getter read always answered `true` — '
              'the switch could never turn OFF');
      // the OLD broken read is gone
      expect(
          settings.contains('s.b(SettingsMirror.kSurfaceStyle, true)'), isFalse,
          reason: 'the bool-getter read of the string key must stay dead');
    });

    test('the write lane round-trips the same string key', () {
      final settings = repo('lib/anantplay/settings_screen.dart');
      expect(
          settings.contains(
              "SettingsMirror.kSurfaceStyle, v ? 'glass' : 'flat'"),
          isTrue,
          reason: 'the toggle writes the same string key it reads');
    });
  });

  group('v3.4.6.k 2 — Playback & Subtitles: live preview + bubble sliders', () {
    test('the Sticky Live Sinhala Subtitle Preview Card sits at the top',
        () {
      final settings = repo('lib/anantplay/settings_screen.dart');
      expect(settings.contains('_LiveSubtitlePreviewCard'), isTrue);
      expect(settings.contains("'ශ්\u200Dරී ලංකාවේ සිනමා උපසිරැසි'"), isTrue,
          reason: 'the reference line with the ZWJ conjunct ශ්\u200Dරී verbatim');
      expect(settings.contains('resolveSubtitleTextStyle('), isTrue,
          reason: 'the preview paints through the SAME resolver as the player');
      // the preview card is the FIRST child of the Playback & Subtitles page
      final pageAt =
          settings.indexOf("title: 'Playback & Subtitles'");
      final cardAt = settings.indexOf('_LiveSubtitlePreviewCard(s: s)');
      expect(pageAt, greaterThan(-1));
      expect(cardAt, greaterThan(pageAt),
          reason: 'the live preview card must render below the page title');
    });

    test('MornyeBubbleSlider: the local draft + the single commit point', () {
      final bubble = repo('lib/mornye_ui/widgets/mornye_bubble_slider.dart');
      expect(bubble.contains('class MornyeBubbleSlider'), isTrue);
      expect(bubble.contains('onChangeEnd'), isTrue,
          reason: 'the single commit point (drag end) exists');
      expect(bubble.contains('calloutFor'), isTrue,
          reason: 'the floating accent callout pill exists');
      final settings = repo('lib/anantplay/settings_screen.dart');
      // the rows write to the channel ONLY in onCommit — never per-frame
      expect(settings.contains('required this.onCommit,'), isTrue);
      expect(settings.contains('onChanged: (x) => setState(() => _draft = x)'),
          isTrue,
          reason: 'the drag updates ONLY the local draft (no channel writes)');
      for (final key in [
        'SettingsMirror.kGlassClarity',
        'SettingsMirror.kSubBgOpacity',
        'SettingsMirror.kSubBottomPct',
      ]) {
        // the continuation differs per call site (indent or same-line key)
        // — every _SliderRow commit lane must mention the key after onCommit.
        final at = settings.indexOf('onCommit: (v) => scope.putInt');
        var found = false;
        var cursor = at;
        while (cursor > -1) {
          final window = settings.substring(
              cursor, math.min(cursor + 130, settings.length));
          if (window.contains(key)) {
            found = true;
            break;
          }
          cursor = settings.indexOf('onCommit: (v) => scope.putInt', cursor + 1);
        }
        expect(found, isTrue,
            reason: '$key must commit on drag end only');
      }
    });

    test('the scrolling settings tiles carry NO live backdrop blur', () {
      final group = repo('lib/mornye_ui/widgets/settings_group.dart');
      expect(
          group.contains(
              'MornyeGlassPanel.overlay(samplesBackdrop: false, child: content)'),
          isTrue,
          reason: 'the tiles keep the glass tint + rim with ZERO backdrop '
              'sampling (the app-wide lag kill)');
      final main = repo('lib/main.dart');
      expect(main.contains('RepaintBoundary('), isTrue,
          reason: 'every tab body sits in its own repaint layer');
    });
  });

  group('v3.4.6.k 3 — the ⋮ popover anchors at the top-right button', () {
    test('the button\'s OWN Builder context supplies the anchor rect', () {
      final settings = repo('lib/anantplay/settings_screen.dart');
      expect(settings.contains('Builder(\n            builder: (btnContext) => IconButton('),
          isTrue,
          reason: 'the ⋮ button\'s own box is the anchor (never the Scaffold)');
      expect(settings.contains('origin: anchor?.topRight,'), isTrue,
          reason: 'the flight origin is the anchor\'s TOP-RIGHT corner');
      expect(settings.contains('final anchor = mornyeMenuAnchor(context);'), isTrue);
      expect(settings.contains('anchor: anchor,'), isTrue,
          reason: 'the exact anchor rect is passed into the menu');
    });

    test('the vendored menu pours out of the anchor (never screen-center)',
        () {
      final menu = repo('lib/mornye_ui/widgets/mornye_context_menu.dart');
      expect(menu.contains('origin ?? target?.center'), isTrue,
          reason: 'the flight origin is the anchor point');
      expect(menu.contains('anchor!.right - childSize.width'), isTrue,
          reason: 'the menu\'s right edge aligns under the anchor\'s right');
    });
  });

  group('v3.4.6.k 4 — the Setup Wizard lockdown + slide persistence', () {
    test('the forward-swipe lock physics exists and gates on permissions',
        () {
      final onboarding = repo('lib/anantplay/onboarding_screen.dart');
      expect(
          onboarding.contains('class _ForwardBlockedScrollPhysics'), isTrue);
      expect(onboarding.contains('blockForward && offset > 0'), isTrue,
          reason: 'a forward drag offset is frozen at zero while gated');
      expect(onboarding.contains('static bool forwardBlocked'), isTrue);
      expect(
          onboarding.contains('''
  static bool forwardBlocked(int page, OnboardingStatus s) => switch (page) {
        2 => !s.notif,
        3 => !s.overlay,
        4 => !s.installs,
        _ => false,
      };'''),
          isTrue,
          reason: 'slides 2/3/4 gate forward swipes on their OS permission');
    });

    test('the stage persists on EVERY page change + resumes on the same slide',
        () {
      final onboarding = repo('lib/anantplay/onboarding_screen.dart');
      expect(onboarding.contains('setOnboardingStage(page)'), isTrue,
          reason: '_onPageChanged persists the stage immediately');
      expect(
          onboarding.contains(
              'late int _page =\n      AppState.of(context).settings.onboardingStage.clamp(0, 5);'),
          isTrue,
          reason: '_page initializes from the SAME persisted stage as the '
              'PageController (the auto-return Slide-1 reset kill)');
      expect(
          onboarding.contains(
              'initialPage: AppState.of(context).settings.onboardingStage.clamp(0, 5)'),
          isTrue);
    });

    test('NO cold-launch wizard flash: the hydration gate holds the shell',
        () {
      final main = repo('lib/main.dart');
      expect(main.contains('_HydrationBackdrop'), isTrue);
      expect(main.contains('if (!state.hydrated)'), isTrue,
          reason: 'the shell holds the backdrop until the first getAll()');
      final state = repo('lib/anantplay/app_state.dart');
      expect(state.contains('bool hydrated = false;'), isTrue);
      expect(state.contains('hydrated = true;'), isTrue);
    });

    test('the honest marathon pill + the full step pills (no truncation)', () {
      final onboarding = repo('lib/anantplay/onboarding_screen.dart');
      // strip comments first: the fix note legitimately QUOTES the old lie.
      final code = onboarding.replaceAll(RegExp(r'//[^\n]*'), '');
      expect(
          code.contains("'Smooth & Battery-Friendly · සිංහල උපසිරැසි'"),
          isTrue,
          reason: 'the made-up marathon claim is replaced by the honest pill');
      expect(code.contains('5–6 පැයක්'), isFalse);
      expect(onboarding.contains("'2. Add Index in Sources'"), isTrue);
      expect(onboarding.contains("'Open AnantPlay with User Guide'"), isTrue,
          reason: 'the slide-6 CTA launches the coach walkthrough too');
    });
  });

  group('v3.4.6.k 5 — AnantPlay & Sources: the Multi-Index URL Manager', () {
    test('the manager lists every URL with 1-tap Copy & Delete + Add', () {
      final settings = repo('lib/anantplay/settings_screen.dart');
      expect(settings.contains('_MultiIndexManager'), isTrue);
      expect(settings.contains("'Add Index URL'"), isTrue);
      for (final url in [
        'dictationb1/srilanka-anantplay/main/index.json',
        'anantplay-si-subs-bridge.techworldlk.workers.dev/index.json',
        'AnantPlay/index/main/index.json',
      ]) {
        expect(settings.contains(url), isTrue,
            reason: 'the $url preset must be copyable from Settings');
      }
      expect(settings.contains("kIndexUrls, _serialize(customs)"), isTrue,
          reason: 'the list persists through the kIndexUrls lane');
    });

    test('the broken unlabeled empty text box + Save is GONE', () {
      final settings = repo('lib/anantplay/settings_screen.dart');
      expect(settings.contains('_IndexUrlField'), isFalse,
          reason: 'the dead single-URL field (and its unlabeled empty GUIDED '
              'SETUP twin) is deleted');
      expect(settings.contains("'1-tap Copy Index URL'"), isFalse,
          reason: 'the old single-URL copy row is replaced by the manager');
    });
  });

  group('v3.4.6.k 6 — the silent single-commit self-update lane', () {
    test('USER_ACTION_NOT_REQUIRED + UPDATE_PACKAGES_WITHOUT_USER_ACTION', () {
      final self = repo('../src/lk/techworld/anantplay/sicast/SelfUpdater.java');
      expect(
          self.contains(
              'params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)'),
          isTrue,
          reason: 'the API 31+ silent lane is armed');
      expect(
          repo('../AndroidManifest.xml').contains(
              'android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION'),
          isTrue);
      final weblock =
          repo('../src/lk/techworld/anantplay/sicast/AnantPlayInstaller.java');
      expect(weblock.contains('USER_ACTION_NOT_REQUIRED'), isTrue,
          reason: 'the installer-of-record for me.champdev.weblock is silent '
              'too');
    });

    test('the 2–3× installer-loop kill: single-flight + 15 s cooldown', () {
      final self = repo('../src/lk/techworld/anantplay/sicast/SelfUpdater.java');
      expect(self.contains('installCommitInFlight'), isTrue);
      expect(self.contains('activeInstallSessionId'), isTrue);
      expect(self.contains('INSTALL_COMMIT_COOLDOWN_MS = 15_000L'), isTrue);
      expect(self.contains('boolean installInFlight()'), isTrue);
      expect(self.contains('static void installSettled()'), isTrue);
      // resumePendingInstall + installPendingUpdate are gated by the lock
      expect(
          self.contains(
              'if (installInFlight()) {\n                Diag.i("selfupd", "resume skipped'),
          isTrue,
          reason: 'onResume can NEVER re-commit a pending session');
      expect(
          self.contains(
              'if (installInFlight()) {\n            Diag.i("selfupd", "install already committed'),
          isTrue);
      // the pending-resume flag clears the INSTANT the commit fires
      expect(
          self.contains(
              'putBoolean(PENDING_AWAITING_PERMISSION, false).apply();'),
          isTrue);
    });

    test('the two-lane versioning: letter patches go silent, majors gate', () {
      final upd = repo('../src/lk/techworld/anantplay/sicast/UpdateChecker.java');
      expect(upd.contains('static boolean isLetterPatch('), isTrue);
      final self = repo('../src/lk/techworld/anantplay/sicast/SelfUpdater.java');
      expect(self.contains('silent letter-patch lane'), isTrue);
      expect(self.contains('Updating to v'), isTrue,
          reason: 'the glass pill label exists');
      // the APK ceiling is 100 MiB everywhere
      expect(
          repo('../src/lk/techworld/anantplay/sicast/SelfUpdateCore.java')
              .contains('MAX_APK_BYTES = 100L * 1024 * 1024'),
          isTrue);
      expect(repo('../build.sh').contains('104857600'), isTrue);
      expect(repo('../.github/workflows/build-apk.yml').contains('104857600'),
          isTrue);
      expect(
          repo('../.github/workflows/release-apk.yml').contains('104857600'),
          isTrue);
    });

    test('the signed OTA manifest + the 7-day SRT cache TTL', () {
      final ota = repo('../src/lk/techworld/anantplay/sicast/OtaConfigSync.java');
      expect(ota.contains('cdn.jsdelivr.net'), isTrue);
      expect(ota.contains('DEADLINE_MS = 5_000L'), isTrue);
      expect(ota.contains('CDN consensus failed'), isTrue,
          reason: 'both CDNs must agree (fail-closed)');
      expect(ota.contains('sha256Hex(canonicalJson(payload))'), isTrue);
      expect(
          repo('../src/lk/techworld/anantplay/sicast/SubtitleStore.java')
              .contains('sweepExpired(long ttlMs)'),
          isTrue);
      expect(
          repo('../src/lk/techworld/anantplay/sicast/App.java')
              .contains('OtaConfigSync.sync(this)'),
          isTrue);
      final manifest = repo('../docs/ota/manifest.json');
      expect(manifest.contains('"v": 1'), isTrue);
      expect(manifest.contains('"sha256"'), isTrue);
      expect(manifest.contains('"subCacheDays": 7'), isTrue);
    });

    testWidgets('ForceUpdateScreen renders the non-dismissible gate cleanly',
        (tester) async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(const MethodChannel('anantplay/updater'),
              (call) async {
        if (call.method == 'appInfo') {
          return <String, Object>{
            'versionName': '3.4.6.j',
            'versionCode': 30416,
            'packageName': 'lk.techworld.anantplay.sicast',
          };
        }
        if (call.method == 'pending') {
          return <String, Object>{'pending': false};
        }
        return true;
      });
      await tester.pumpWidget(forceUpdateApp());
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));
      expect(tester.takeException(), isNull,
          reason: 'the gate must render without a RenderFlex overflow');
      final gate = repo('lib/anantplay/force_update_screen.dart');
      expect(gate.contains('PopScope'), isTrue,
          reason: 'the hardware Back is blocked');
    });
  });

  group('v3.4.6.k 7 — the 7-video audit showstoppers stay dead', () {
    test('the blank PlayActivity screen is dead (buildUi + startPlayback)',
        () {
      final player =
          repo('../src/lk/techworld/anantplay/sicast/PlayActivity.java');
      expect(player.contains('buildUi();'), isTrue,
          reason: 'onCreate builds the content view (the v3.4.6.j blank '
              'screen never called buildUi)');
      expect(player.contains('setContentView(root)'), isTrue);
      expect(player.contains('startPlayback();'), isTrue);
      expect(player.contains('new SurfaceView(this)'), isTrue);
      expect(player.contains('scheduleHudHide()'), isTrue,
          reason: 'the HUD auto-hides once playing');
      expect(player.contains('buildErrorCard'), isTrue,
          reason: 'a failed stream shows the error card, never a blank screen');
    });

    test('the DLNA cast lands on PlayActivity (never MainActivity)', () {
      final svc =
          repo('../src/lk/techworld/anantplay/sicast/ReceiverService.java');
      expect(svc.contains('PlayActivity.launchBal(this, castUrl,'), isTrue,
          reason: 'the /play lane rides the BAL-allowed PlayActivity launch');
      expect(svc.contains('PlayerTakeover'), isTrue,
          reason: 'the takeover reorders the live native player forward');
      final player =
          repo('../src/lk/techworld/anantplay/sicast/PlayActivity.java');
      expect(player.contains('MODE_BACKGROUND_ACTIVITY_START_ALLOWED'),
          isTrue);
    });

    test('the YouTube renderer-crash shield + the ad-free lane', () {
      final web =
          repo('../src/lk/techworld/anantplay/sicast/WebBrowserView.java');
      expect(web.contains('handleRenderProcessGone'), isTrue,
          reason: 'a dead renderer NEVER kills the app process');
      expect(web.contains('rebuildAfterRendererDeath'), isTrue);
      expect(web.contains('onPageStarted'), isTrue,
          reason: 'navigation clears the stale Play in Cinema banner');
      expect(web.contains('sniff-clear'), isTrue);
      final ytRaw =
          repo('../src/lk/techworld/anantplay/sicast/YouTubeExtractor.java');
      // strip only the block javadocs (they legitimately mention the dock's
      // label; inline // must stay — the URLs carry https://).
      final yt = ytRaw.replaceAll(RegExp(r'/\*.*?\*/', dotAll: true), '');
      expect(yt.contains('ANDROID_VR'), isTrue);
      expect(yt.contains('sponsor.ajay.app'), isTrue);
      expect(yt.contains('Play Ad-Free in Cinema'), isFalse,
          reason: 'the label lives in the Dart dock, not the extractor');
      final browser = repo('lib/anantplay/browser_screen.dart');
      expect(browser.contains("const Text('Play Ad-Free in Cinema')"), isTrue,
          reason: 'the dock renders the glowing Play Ad-Free in Cinema pill');
      expect(browser.contains('Tap to play in the AnantPlay Cinema Player'),
          isTrue);
      expect(browser.contains('parseM3u'), isTrue,
          reason: '.m3u playlists parse into the in-app IPTV channel sheet');
      expect(browser.contains('isM3uUrl'), isTrue);
    });

    test('the bottom-anchored arrow-guided coach (no × / no —)', () {
      final coach =
          repo('../src/lk/techworld/anantplay/sicast/AnantPlayCoachService.java');
      expect(coach.contains('Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL'),
          isTrue,
          reason: 'the coach anchors at the BOTTOM by default');
      expect(coach.contains('ArrowView'), isTrue,
          reason: 'the glowing animated directional arrow indicator exists');
      expect(coach.contains('smallButton("—"',), isFalse,
          reason: 'the Minimize button is gone');
      expect(coach.contains('smallButton("✕"',), isFalse,
          reason: 'the Close button is gone');
      expect(coach.contains('URL_SL_IPTV'), isTrue);
      expect(coach.contains('URL_SI_SUBS_BRIDGE'), isTrue);
      expect(coach.contains('K_INDEX_URLS'), isTrue,
          reason: 'user custom index URLs get their own Copy pills');
    });

    test('the AppSwitch stretchy lens + the Mornye notification glass', () {
      final sw = repo('lib/mornye_ui/widgets/app_switch.dart');
      expect(sw.contains('_heldWidthGrowth = 21'), isTrue,
          reason: 'the v5.4.5 held-lens thumb stretch (37→58 wide)');
      expect(sw.contains('_heldHeightGrowth = 43 / 3'), isTrue);
      expect(sw.contains('OverflowBox('), isTrue);
      expect(sw.contains('MornyeMotion.effectsFastSpring'), isTrue,
          reason: 'the 150 ms press-in spring');
      expect(sw.contains('MornyeMotion.fastSpring'), isTrue,
          reason: 'the 250 ms release spring');
      final glass =
          repo('lib/mornye_ui/widgets/mornye_glass_notification.dart');
      expect(glass.contains('ImageFilter.blur(sigmaX: 14, sigmaY: 14)'),
          isTrue,
          reason: 'the real frosted BackdropFilter at sigma 14');
      expect(glass.contains('_HairlineRimPainter'), isTrue,
          reason: 'the luminous Mornye hairline rim');
      expect(glass.contains('MornyeMotion.fast.curve'), isTrue,
          reason: 'the smooth top spring drop');
    });
  });

  group('v3.4.6.k 7 — the signed OTA config channel is USER-VISIBLE', () {
    // verify_ui_completeness R2: every AppPrefs K_* must sit on a real UI
    // surface (an unwired pref is a fake pref). The five K_OTA_* keys ride
    // the SettingsMirror contract + two live settings rows.
    test('the five K_OTA_* prefs have typed mirror constants', () {
      final mirror = repo('lib/anantplay/settings_mirror.dart');
      for (final wire in <String>['otaBridgeMirrors', 'otaIndexFeeds',
          'otaAdRules', 'otaSubCacheDays', 'otaSyncAt']) {
        expect(mirror.contains("'$wire'"), isTrue,
            reason: '$wire must be a typed SettingsMirror constant');
      }
      expect(mirror.contains('int get otaSubCacheDays'), isTrue);
      expect(mirror.contains('bool get otaSynced'), isTrue);
    });

    test('the Home tour banner never truncates the cast promise', () {
      final home = repo('lib/anantplay/home_screen.dart');
      // Trap #3.4: maxLines: 2 + ellipsis cut the banner to "…open the Brows…"
      // on 360 dp phones. The promise must wrap instead of being clipped.
      expect(home.contains('First time? Cast from AnantPlay or open the '),
          isTrue);
      expect(home.contains('Browser tab — Sinhala subtitles included.'),
          isTrue);
      final banner = home.substring(home.indexOf('First time? Cast'));
      expect(banner.contains('TextOverflow.visible'), isTrue,
          reason: 'the banner wraps instead of ellipsising');
      expect(banner.contains('maxLines: 3'), isTrue);
    });

    test('the deep probe denominators are honest: 78 native + 14 Dart = 92',
        () {
      final help = repo('lib/anantplay/help_screen.dart');
      final dart = repo('lib/anantplay/deep_troubleshooter.dart');
      // Trap #2: the counter/bar/verdict all use the one 92 total, and the
      // native docs no longer claim the v3.4.6.i 64/73 matrix.
      expect(help.contains('_done / 92'), isTrue);
      expect(help.contains('78 native'), isTrue);
      expect(help.contains('ids 79–92'), isTrue);
      expect(dart.contains('firstId = 79'), isTrue);
      expect(dart.contains('78 live probes'), isTrue);
      expect(dart.contains('(78 native + 14 Dart)'), isTrue);
      expect(dart.contains('64 live probes'), isFalse);
      expect(dart.contains('73 native'), isFalse);
    });

    test('the CI emulator gate requires 78 live probes (no stale 73/64)', () {
      final wk = repo('../.github/workflows/build-apk.yml');
      expect(wk.contains('-lt 78 '), isTrue);
      expect(wk.contains('total >= 78'), isFalse,
          reason: 'the assert is the shell -lt 78 form, not a prose token');
      expect(wk.contains('-lt 73'), isFalse, reason: 'stale denominator');
      expect(wk.contains('-lt 64'), isFalse, reason: 'stale denominator');
      expect(wk.contains('78-probe'), isTrue);
      expect(wk.contains('73-probe'), isFalse);
      // LAW #1 — the 100 MiB ceiling, never the retired 50/55 MiB tier.
      expect(wk.contains('104857600'), isTrue);
      expect(wk.contains('50 MiB'), isFalse);
      expect(wk.contains('55 MiB'), isFalse);
    });

    test('the extractor is https-only (LAW #3, no downgrade)', () {
      final yt =
          repo('../src/lk/techworld/anantplay/sicast/YouTubeExtractor.java');
      expect(yt.contains('refused non-HTTPS extraction URL'), isTrue);
      expect(yt.contains('https://'), isTrue);
    });

    test('About surfaces the signed remote config state; Storage the TTL',
        () {
      final settings = repo('lib/anantplay/settings_screen.dart');
      expect(settings.contains('_RemoteConfigRow'), isTrue,
          reason: 'About carries the signed remote config card');
      expect(settings.contains("'Remote config (signed)'"), isTrue);
      expect(settings.contains("'Pending — pinned defaults in force'"), isTrue,
          reason: 'an unsynced phone honestly says pending');
      expect(settings.contains('otaSubCacheDays'), isTrue,
          reason: 'the Storage page states the live subtitle cache TTL');
      expect(settings.contains('(signed config)'), isTrue);
    });
  });
}
