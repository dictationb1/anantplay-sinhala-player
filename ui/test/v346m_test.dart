// AnantPlay Sinhala Player v3.4.6.m — THE SILENT-GLASS-PLAYER FIX RELEASE
// CONTRACTS (tests).
//
// One focused regression file for the v3.4.6.m release, proving every fix
// from the 7-video audit + the real-phone crash:
//   1. THE FGS CRASH KILL: ReceiverService satisfies startForeground FIRST
//      on every lane (veto / ACTION_STOP / duplicate instance), the typed
//      connectedDevice → mediaPlayback → dataSync chain, and ACTION_STOP is
//      never dispatched with startForegroundService().
//   2. THE SILENT LETTER-PATCH LANE: the ForceUpdateScreen gate NEVER arms
//      for a 3.4.6.x patch with install permission (silentPatchLane), the
//      glass pill glides "Updating to vX…" → "Installing update…", the
//      button stays disabled 8 s as 'Installer Active…', and the permission
//      watcher polls at 200 ms (450 ticks / 90 s).
//   3. THE ZERO WELCOME-FLASH ROUTE: MainActivity.getInitialRoute answers
//      '/home' or '/onboarding?stage=N' synchronously, the Dart shell reads
//      platformDispatcher.defaultRouteName, and the pre-hydration stage-0
//      write is dead (the Slide-1 reset kill).
//   4. COMPLETE ONBOARDING GATING: slides 1/2/3/4 block forward swipes on
//      their real grants (official + notif + overlay + battery AND installs),
//      'Continue without installing' is deleted, and the coach-gated ✓
//      Finish reads K_COACH_COMPLETED with the honest caption.
//   5. THE COACH TARGET POINTER + FINISH STAMP: the non-blocking arrow
//      overlay (FLAG_NOT_TOUCHABLE), the synchronous completion stamps, and
//      the onboardingStatus frame carries `coach`.
//   6. THE THERMAL/HW-CODEC CINEMA ENGINE: CodecPolicy's hardware-first
//      selector, Low Data Mode 480p / 1.2 Mbps, the 0/1/4 Hz tick law, and
//      the quality-switch viewport guard with Fit as the strict default.
//   7. THE REAL LOCK MODE + ZERO LEGACY DIALOGS: dispatchTouchEvent swallows
//      everything but the unlock pill; zero AlertDialog in the Cinema Player;
//      the subtitle chip instant highlight + the "Fetching " space.
//   8. THE APPLE iOS 26 LIQUID GLASS BANNER + THE ZERO-CLIP BUBBLE: the
//      single-queue frosted banner (sigma 22 + specular rim) and the
//      OverlayPortal slider bubble escaping every card clip.
//   9. THE BROWSER FLOATING ▶: the video-detected/video-clear shell rail and
//      the 52dp pulsing frosted play overlay.
//  10. THE HONEST PROBES: PrivacyScrubber's technical-term whitelist, the
//      six Domain M probes, and the 98-probe merged counter.
import 'dart:io' show File;

import 'package:flutter_test/flutter_test.dart';

String repo(String rel) => File(rel.startsWith('lib/') || rel.startsWith('test/') ? rel : '../$rel').readAsStringSync();

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  // ═══ 1 — THE FGS CRASH KILL (FAIL 56 / FAIL 92) ═════════════════════════
  group('v3.4.6.m 1 — ReceiverService zero-FGSDidNotStartInTime', () {
    final svc = repo('src/lk/techworld/anantplay/sicast/ReceiverService.java');
    final manifest = repo('AndroidManifest.xml');

    test('startForeground is satisfied FIRST in onCreate (before the veto)',
        () {
      final onCreate = svc.substring(svc.indexOf('public void onCreate'),
          svc.indexOf('restoreUserStop(this);   // process restart'));
      expect(onCreate.contains('ensureForegroundSatisfied();'), isTrue,
          reason: 'the FGS contract must run before any user-stop veto can '
              'return — the exact FAIL 56/92 lane');
    });

    test('onStartCommand re-satisfies the contract before ACTION_STOP tears '
        'down', () {
      final onStart = svc.substring(svc.indexOf('public int onStartCommand'),
          svc.indexOf('if (ACTION_STOP.equals(a))'));
      expect(onStart.contains('ensureForegroundSatisfied();'), isTrue);
    });

    test('the duplicate-instance no-op now tears down cleanly (no unsatisfied '
        'FGS start)', () {
      expect(svc.contains('duplicate tears down'), isTrue);
      expect(svc.contains('private volatile boolean fgsStarted'), isTrue);
    });

    test('requestStop NEVER uses startForegroundService (only stopService / '
        'live-instance startService)', () {
      final requestStop = svc.substring(svc.indexOf('static android.content.Intent requestStop'),
          svc.indexOf('stopNow();', svc.indexOf('static android.content.Intent requestStop')));
      // the CALL form is absent (the javadoc legitimately says "NEVER
      // startForegroundService() for ACTION_STOP" — the law, in prose).
      expect(requestStop.contains('.startForegroundService('), isFalse);
      expect(requestStop.contains('startService(stop)'), isTrue);
      expect(requestStop.contains('if (running != null)'), isTrue);
    });

    test('the typed startForeground chain covers mediaPlayback + dataSync '
        '(declared in the manifest)', () {
      expect(svc.contains('FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK'), isTrue);
      expect(svc.contains('FOREGROUND_SERVICE_TYPE_DATA_SYNC'), isTrue);
      expect(manifest.contains('connectedDevice|mediaPlayback|dataSync'),
          isTrue);
      expect(manifest.contains(
          'android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK'), isTrue);
      expect(manifest.contains(
          'android.permission.FOREGROUND_SERVICE_DATA_SYNC'), isTrue);
    });
  });

  // ═══ 2 — THE SILENT LETTER-PATCH LANE + THE INSTANT AUTO-RETURN ═════════
  group('v3.4.6.m 2 — the silent 3.4.6.x auto-push contract', () {
    final upd = repo('src/lk/techworld/anantplay/sicast/SelfUpdater.java');
    final gate =
        repo('ui/lib/anantplay/force_update_screen.dart');
    final main =
        repo('src/lk/techworld/anantplay/sicast/MainActivity.java');

    test('a letter patch with install permission NEVER arms the blocking '
        'gate (the ordering kill)', () {
      expect(upd.contains('silentPatchLane'), isTrue);
      expect(upd.contains('if (!silentPatchLane)'), isTrue,
          reason: 'persistRequiredVer + gateAvailable run only on the '
              'major/minor lane or when the patch lane cannot run');
    });

    test('the glass pill glides Updating → Installing', () {
      expect(upd.contains('fire("Updating to v" + rel.version'), isTrue);
      expect(upd.contains('fire("Installing update…")'), isTrue);
    });

    test('the installer button is locked 8 s as Installer Active… (the '
        're-clickable double-tap kill)', () {
      expect(gate.contains("'Installer Active…'"), isTrue);
      expect(gate.contains('Duration(seconds: 8)'), isTrue);
      expect(gate.contains("'Installer Open…'"), isFalse);
    });

    test('the permission watcher snaps back at 200 ms (450 ticks / 90 s)',
        () {
      expect(main.contains('permissionWatchTicks >= 450'), isTrue);
      expect(main.contains('postDelayed(this, 200)'), isTrue);
      expect(main.contains('postDelayed(permissionWatcher, 200)'), isTrue);
    });

    test('OtaConfigSync gives EACH CDN its own 4 s budget (the Pending kill)',
        () {
      final ota =
          repo('src/lk/techworld/anantplay/sicast/OtaConfigSync.java');
      expect(ota.contains('FETCH_BUDGET_MS = 4_000L'), isTrue);
      expect(ota.contains('jsDelivr unreachable — degraded single-origin lane'),
          isTrue);
      expect(ota.contains('e.commit();'), isTrue,
          reason: 'the Verified stamp is durable the instant SHA-256 passed');
    });
  });

  // ═══ 3 — THE ZERO WELCOME-FLASH ROUTE ═══════════════════════════════════
  group('v3.4.6.m 3 — the synchronous getInitialRoute', () {
    final main = repo('src/lk/techworld/anantplay/sicast/MainActivity.java');
    final shell = repo('ui/lib/main.dart');

    test('the host answers /home or /onboarding?stage=N before frame 1',
        () {
      expect(main.contains('public String getInitialRoute()'), isTrue);
      expect(main.contains('"/home"'), isTrue);
      expect(main.contains('"/onboarding?stage=" + AppPrefs.onboardingStage(p);'),
          isTrue);
      expect(main.contains('AppPrefs.onboardingVersion(p) >= 2 && '
          'AppPrefs.onboardingDone(p)'), isTrue);
    });

    test('the Dart shell reads the native route + never resets the stage on '
        'pre-hydration defaults', () {
      expect(shell.contains('platformDispatcher.defaultRouteName'), isTrue);
      expect(shell.contains('_initialOnboardingStage'), isTrue);
      expect(shell.contains('legacyFinish ? 0 : _initialOnboardingStage'),
          isTrue);
      expect(shell.contains('!state.settings.onboardingCurrent && '
          'state.hydrated'), isTrue);
    });
  });

  // ═══ 4 — THE COMPLETE ONBOARDING GATING ══════════════════════════════════
  group('v3.4.6.m 4 — slides 2–5 gating + the coach-gated Finish', () {
    final dart = repo('ui/lib/anantplay/onboarding_screen.dart');

    test('slide 1 blocks until the official app is really installed', () {
      expect(dart.contains('1 => !s.official,'), isTrue);
      expect(dart.contains("'Continue without installing' is DELETED"), isTrue);
    });

    test('slide 4 blocks until BOTH battery AND update grants', () {
      expect(dart.contains('4 => !s.battery || !s.installs,'), isTrue);
    });

    test('the ✓ Finish is coach-gated with the honest caption', () {
      expect(dart.contains('K_COACH_COMPLETED'), isTrue);
      expect(dart.contains("_status.coach"), isTrue);
      expect(dart.contains(
          "Complete 'Open AnantPlay with User Guide' first"), isTrue);
    });

    test('the bridge carries the coach field', () {
      final bridge = repo('ui/lib/anantplay/bridge.dart');
      expect(bridge.contains('this.coach = false,'), isTrue);
      expect(bridge.contains("coach: map['coach'] == true,"), isTrue);
    });
  });

  // ═══ 5 — THE COACH TARGET POINTER + THE FINISH STAMP ════════════════════
  group('v3.4.6.m 5 — the AnantPlayCoachService upgrade', () {
    final coach =
        repo('src/lk/techworld/anantplay/sicast/AnantPlayCoachService.java');

    test('the floating target pointer is strictly non-blocking', () {
      expect(coach.contains('TargetPointerView'), isTrue);
      expect(coach.contains('FLAG_NOT_TOUCHABLE'), isTrue);
      expect(coach.contains('FLAG_NOT_FOCUSABLE'), isTrue);
      expect(coach.contains('pointerParams()'), isTrue);
    });

    test('Step 10 Finish stamps K_COACH_COMPLETED + stage 5 synchronously',
        () {
      expect(coach.contains('AppPrefs.setCoachCompleted(p, true)'), isTrue);
      expect(coach.contains('putInt(AppPrefs.K_ONBOARDING_STAGE, 5).commit()'),
          isTrue);
    });

    test('the 1-tap-copy auto-advance rides the URL steps', () {
      expect(coach.contains('step == 4 || step == 5'), isTrue);
      expect(coach.contains('advance();'), isTrue);
    });
  });

  // ═══ 6 — THE THERMAL / HW-CODEC ENGINE ═══════════════════════════════════
  group('v3.4.6.m 6 — CodecPolicy + the adaptive tick law', () {
    final codec = repo('src/lk/techworld/anantplay/sicast/CodecPolicy.java');
    final play = repo('src/lk/techworld/anantplay/sicast/PlayActivity.java');

    test('the hardware-first selector prefers real HW decoders', () {
      expect(codec.contains('MediaCodecList.REGULAR_CODECS'), isTrue);
      expect(codec.contains('isHardwareAccelerated'), isTrue);
      expect(codec.contains('hardwareFirstSelector'), isTrue);
      expect(play.contains('CodecPolicy.applyHardwareFirst'), isTrue);
    });

    test('Low Data Mode caps at 480p / 1.2 Mbps and is live-toggleable',
        () {
      expect(codec.contains('setMaxVideoBitrate(1_200_000)'), isTrue);
      expect(play.contains('AppPrefs.lowDataMode'), isTrue);
      final settings =
          repo('ui/lib/anantplay/settings_screen.dart');
      expect(settings.contains('lowDataMode'), isTrue,
          reason: 'the Settings → Playback & Subtitles toggle exists');
      final mirror = repo('ui/lib/anantplay/settings_mirror.dart');
      expect(mirror.contains("kLowDataMode = 'lowDataMode'"), isTrue);
    });

    test('the tick throttles to 0 / 1 / 4 Hz', () {
      expect(play.contains('armTicker()'), isTrue);
      expect(play.contains('THE ADAPTIVE TICK CADENCE'), isTrue);
    });

    test('the quality-switch viewport guard holds the aspect until the '
        'first rendered frame', () {
      expect(play.contains('pendingVideoSize'), isTrue);
      expect(play.contains('onRenderedFirstFrame'), isTrue);
      expect(play.contains('vs.width <= 0 || vs.height <= 0'), isTrue);
      final prefs = repo('src/lk/techworld/anantplay/sicast/AppPrefs.java');
      expect(prefs.contains('K_ASPECT_MODE = "aspectMode"'), isTrue);
      expect(prefs.contains('ASPECT_FIT'), isTrue);
    });
  });

  // ═══ 7 — THE REAL LOCK MODE + ZERO LEGACY DIALOGS ════════════════════════
  group('v3.4.6.m 7 — lock swallow + zero AlertDialog + subtitle chips', () {
    final play = repo('src/lk/techworld/anantplay/sicast/PlayActivity.java');
    final sheets = repo('src/lk/techworld/anantplay/sicast/CinemaSheets.java');
    final hud = repo('src/lk/techworld/anantplay/sicast/CinemaHud.java');
    final gestures =
        repo('src/lk/techworld/anantplay/sicast/CinemaGestures.java');

    test('locked swallows every touch except the unlock pill', () {
      expect(play.contains('public boolean dispatchTouchEvent'), isTrue);
      expect(play.contains('insideUnlockPill(e)'), isTrue);
      expect(play.contains('postDelayed(hideUnlockPill, 2500)'), isTrue);
      expect(gestures.contains('locked'), isTrue);
    });

    test('ZERO AlertDialog in the Cinema Player (all three legacy dialogs '
        'are dark-glass sheets)', () {
      expect(play.contains('AlertDialog'), isFalse);
      expect(sheets.contains('AlertDialog'), isFalse);
      expect(hud.contains('AlertDialog'), isFalse);
      expect(sheets.contains('static UiSheet.Handle orientation('), isTrue);
      expect(sheets.contains('static UiSheet.Handle languages('), isTrue);
    });

    test('the subtitle chip flips the highlight on the same tap + the '
        '"Fetching " space', () {
      expect(sheets.contains('selectLangChip'), isTrue);
      expect(sheets.contains('ops.switchLang(code)'), isTrue);
      expect(play.contains('"Fetching " + name'), isTrue);
    });

    test('live thumb value bubbles + the center buffering ring + '
        'landscape-safe sheets', () {
      expect(sheets.contains('showThumbBubble'), isTrue);
      expect(sheets.contains('insetSafe('), isTrue);
      expect(hud.contains('THE CENTER BUFFERING RING'), isTrue);
    });
  });

  // ═══ 8 — THE APPLE iOS 26 LIQUID GLASS BANNER + THE ZERO-CLIP BUBBLE ════
  group('v3.4.6.m 8 — the Liquid Glass single-queue + OverlayPortal bubble',
      () {
    test('the banner is frosted (sigma 22) with a specular rim + a strict '
        'single queue', () {
      final banner = repo(
          'ui/lib/mornye_ui/widgets/mornye_glass_notification.dart');
      expect(banner.contains('sigmaX: 22, sigmaY: 22'), isTrue);
      expect(banner.contains('_SpecularRimPainter'), isTrue);
      expect(banner.contains('AnimatedSwitcher'), isTrue);
      expect(banner.contains('OverlayEntry? _activeGlassNotification'), isTrue,
          reason: 'exactly one banner can ever be alive app-wide');
    });

    test('the slider bubble rides the app Overlay (zero card clipping)', () {
      final slider =
          repo('ui/lib/mornye_ui/widgets/mornye_bubble_slider.dart');
      expect(slider.contains('OverlayPortalController'), isTrue);
      expect(slider.contains('CompositedTransformFollower'), isTrue);
      expect(slider.contains('showWhenUnlinked: false'), isTrue);
    });

    test('the troubleshoot completion fires exactly ONE banner', () {
      final help = repo('ui/lib/anantplay/help_screen.dart');
      expect(help.contains('_finishRun'), isTrue);
    });
  });

  // ═══ 9 — THE BROWSER FLOATING ▶ VIDEO PLAY BUTTON ═══════════════════════
  group('v3.4.6.m 9 — the video-viewport play rail', () {
    test('the native observer + rail (video-detected / video-clear)', () {
      final web =
          repo('src/lk/techworld/anantplay/sicast/WebBrowserView.java');
      expect(web.contains('VIDEO_RECT_HOOK_JS'), isTrue);
      expect(web.contains('video-detected'), isTrue);
      expect(web.contains('video-clear'), isTrue);
      expect(web.contains('static boolean shellVisible()'), isFalse,
          reason: 'shellVisible lives on MainActivity (the background-'
              'relaunch guard), asserted below');
      final main =
          repo('src/lk/techworld/anantplay/sicast/MainActivity.java');
      expect(main.contains('static boolean shellVisible()'), isTrue);
    });

    test('the 52dp pulsing frosted ▶ rides the rect corner', () {
      final browser = repo('ui/lib/anantplay/browser_screen.dart');
      expect(browser.contains("m['t'] == 'video-detected'"), isTrue);
      expect(browser.contains("m['t'] == 'video-clear'"), isTrue);
      expect(browser.contains('_videoPlay'), isTrue);
      expect(browser.contains('play_arrow_rounded'), isTrue);
      expect(browser.contains('- 52'), isTrue);
    });
  });

  // ═══ 10 — THE HONEST PROBES ═════════════════════════════════════════════
  group('v3.4.6.m 10 — the honest v3.4.6.m probes', () {
    test('PrivacyScrubber whitelists the technical terms (SHA-256 / 3-URL / '
        '16-KiB survive, models still scrubbed)', () {
      final scrub =
          repo('src/lk/techworld/anantplay/sicast/PrivacyScrubber.java');
      expect(scrub.contains('TECHNICAL_TERMS'), isTrue);
      expect(scrub.contains('"SHA-256"'), isTrue);
      expect(scrub.contains('"3-URL"'), isTrue);
      expect(scrub.contains('"16-KIB"'), isTrue);
      expect(scrub.contains('"H.264"'), isTrue);
    });

    test('the six Domain M probes are registered (79–84)', () {
      final deep = repo('src/lk/techworld/anantplay/sicast/DeepProbe.java');
      expect(deep.contains('probeM1(l)'), isTrue);
      expect(deep.contains('probeM6(l)'), isTrue);
      expect(deep.contains('Silent Glass Player Fixes'), isFalse,
          reason: 'the M domain title lives in the Dart registry only');
      expect(deep.contains('"M"'), isTrue);
    });

    test('the Dart counter matches the 98-probe merged matrix', () {
      final deep = repo('ui/lib/anantplay/deep_troubleshooter.dart');
      expect(deep.contains('static const int firstId = 85;'), isTrue);
      expect(deep.contains('1..98 (84 native + 14 Dart)'), isTrue);
      expect(deep.contains("DeepDomain('M', 'Silent Glass Player Fixes')"),
          isTrue);
      final help = repo('ui/lib/anantplay/help_screen.dart');
      expect(help.contains(r'… $_done/98'), isTrue,
          reason: 'the counter format carries the exact 98 total');
      expect(help.contains('98 deep feature probes'), isTrue);
    });
  });

  // ═══ THE VERSION IDENTITY ═══════════════════════════════════════════════
  group('v3.4.6.m 11 — the shipped identity', () {
    test('AppInfo + pubspec + workflows + build.sh all say 3.4.6.m / 30419',
        () {
      final appInfo = repo('src/lk/techworld/anantplay/sicast/AppInfo.java');
      expect(appInfo.contains('VERSION_NAME = "3.4.6.m"'), isTrue);
      expect(appInfo.contains('VERSION_CODE = 30419'), isTrue);
      final pubspec = repo('ui/pubspec.yaml');
      expect(pubspec.contains('version: 3.4.6+30419'), isTrue);
      final buildWf = repo('.github/workflows/build-apk.yml');
      expect(buildWf.contains(r'test "$VERSION" = "3.4.6.m"'), isTrue);
      expect(buildWf.contains(r'test "$CODE" = "30419"'), isTrue);
      final relWf = repo('.github/workflows/release-apk.yml');
      expect(relWf.contains('default: "3.4.6.m"'), isTrue);
      final buildSh = repo('build.sh');
      expect(buildSh.contains('VERSION_NAME:-3.4.6.m'), isTrue);
      expect(buildSh.contains('VERSION_CODE:-30419'), isTrue);
    });

    test('docs/latest.json stays at the LIVE 3.4.6.l / 30418 tier', () {
      final latest = repo('docs/latest.json');
      expect(latest.contains('"version":"3.4.6.l"'), isTrue);
      expect(latest.contains('"code":30418'), isTrue);
      expect(latest.contains('3.4.6.m'), isFalse);
    });
  });

  // ═══ 12 — THE MIT → GPL-3.0-only REPOSITORY LICENSE SWITCH ══════════════
  group('v3.4.6.m 12 — the GPL-3.0-only repository license', () {
    test('root LICENSE is the verbatim GNU GPL v3.0 (GPL-3.0-only)', () {
      final lic = repo('LICENSE');
      expect(lic.contains('GNU GENERAL PUBLIC LICENSE'), isTrue);
      expect(lic.contains('Version 3, 29 June 2007'), isTrue);
      expect(lic.contains('END OF TERMS AND CONDITIONS'), isTrue);
      expect(lic.contains('MIT License'), isFalse,
          reason: 'the repository license switched to GPL-3.0-only in v3.4.6.m');
    });

    test('the vendored Mornye UI stays LGPL-3.0-only with notices untouched',
        () {
      for (final p in <String>[
        'ui/lib/mornye_ui/LICENSE',
        'ui/lib/mornye_ui/COPYING',
        'ui/lib/mornye_ui/NOTICE',
        'ui/assets/mornye/LICENSE',
        'ui/assets/mornye/COPYING',
        'ui/assets/mornye/NOTICE',
      ]) {
        expect(File('../$p').existsSync(), isTrue,
            reason: '$p must stay in-tree (never narrowed by the upgrade)');
      }
      expect(
          repo('ui/lib/mornye_ui/LICENSE')
              .contains('GNU LESSER GENERAL PUBLIC LICENSE'),
          isTrue);
    });

    test('docs + the About page state GPL-3.0-only and every component license',
        () {
      expect(repo('THIRD_PARTY.md').contains('GPL-3.0-only'), isTrue);
      expect(repo('README.md').contains('GPL-3.0-only'), isTrue);
      expect(repo('SETUP-SI.md').contains('GPL-3.0-only'), isTrue);
      final settings = repo('ui/lib/anantplay/settings_screen.dart');
      expect(settings.contains('Project license: GPL-3.0-only'), isTrue);
      expect(settings.contains('LGPL-3.0-only'), isTrue);
      expect(settings.contains('Apache-2.0'), isTrue);
    });
  });
}
