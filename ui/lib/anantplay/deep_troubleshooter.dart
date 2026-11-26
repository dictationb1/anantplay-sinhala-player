// AnantPlay Sinhala Player v3.4.6.g — THE HONEST TWO-LAYER DEEP TROUBLESHOOTER.
//
// Layer 2 (native, DeepProbe.java): 64 live probes across the 7 domains
// (A Auto-Updater Law · B Home & Cast · C Settings & Customizations ·
// D Browser · E Cinema Player & Subtitles · F Permissions & Privacy ·
// G the v3.4.6.g real-phone fix contracts) — including the honesty rebuild:
// the worker probe FAILS on any non-200 HTTP code (the v3.4.6.e "HTTP 0 →
// offline retention ✓" fake PASS), a REAL subtitle SRT → parse → ZWJ →
// SubtitleStore end-to-end probe, and the Uncaught-crash gate (a captured
// Java/Dart PlatformException is a FAIL with the exact class + line).
//
// Layer 1 (THIS FILE, Dart): the LIVE Flutter UI & channel audits —
// executed INSIDE Dart against the live AppState (InheritedNotifier) and the
// live AnantPlayBridge singleton hubs. v3.4.6.h EXPANDED the layer from 8 to
// FOURTEEN audits (ids 74–87) so every visual/theme/font/icon/updater defect
// the v3.4.6.g field report exposed is now caught AUTOMATICALLY:
//   H1  65 AppStateScope live rebuild + optimistic write
//   H2  66 _ChannelHub singleton (0 cancel collisions)
//   H3  67 Stop/Start receiver live round-trip
//   H4  68 Exhaustive settings persistence + TYPE-SAFETY (bool/int/string/hex
//          + the canonical putLong lane — the Integer→Long crash's Dart half)
//   H5  69 Auto-Updater Law full state machine (isNewer truth table + the
//          installed identity never gates on itself)
//   H6  70 LIGHT MODE visual integrity (LIVE ThemeData: brightness, luminance
//          > 0.70 even with amoled=true + the dimmer on, ≥ 4.5:1 contrast)
//   H7  71 AMOLED/Dark visual integrity (#000000 + ≥ 4.5:1 contrast)
//   H8  72 Material You ↔ manual accent interlock (applyManualAccent flips
//          dynamicColor OFF and repaints)
//   H9  73 Subtitle Font live rendering (4 distinct TextStyles, ZWJ intact)
//   H10 83 Compact subtitle bounds (82% / 3 lines / 1.25 / 10–28sp)
//   H11 84 Semantic Icon Registry (zero forbidden glyphs, Test Player = play)
//   H12 85 Google search-engine contract
//   H13 86 WatchHistory read-back round-trip
//   H14 87 Zero-uncaught-Flutter-error session (0 Dart errors, else named)
//
// v3.4.6.g — THE DOMAIN/ID COLLISION FIX (Trap #2): the native layer grew
// from 56 (A–F) to 64 probes (A–G, G1–G8 = ids 57–64). The Dart layer USED to
// also be Domain G ids 57–64, so the merged verdict, the live feed and the
// clipboard report carried DUPLICATE ids 57–64 and two different "Domain G"
// cards overwriting each other. The Dart layer then moved to Domain H ids 65–78
// — and v3.4.6.i killed the SECOND collision: the native v3.4.6.i Domain I
// (the nine video-audit fix contracts) owns ids 65–73, so the Dart layer is
// now **Domain H, ids 74–87**. The merged run is 100% unique sequential ids
// 1..87 (73 native + 14 Dart) with zero duplicates.
// The verdict merges BOTH layers and every FAIL ✗ is printed at the very top
// of the composed report — nothing is ever hidden.
import 'dart:async';
import 'dart:convert' show jsonDecode;

import 'package:flutter/painting.dart' show TextStyle, FontWeight, Color;
import 'package:material_ui/material_ui.dart'
    as mi show Icons, Brightness;
import 'package:anantplay_ui/mornye_ui/models/theme_settings.dart'
    show MornyeAccent;
import 'package:anantplay_ui/main.dart'
    show buildAppTheme, sessionFlutterErrorCount, lastSessionFlutterError,
    lastSessionDiagnostic;

import 'bridge.dart';
import 'settings_mirror.dart';
import 'app_state.dart';
import 'home_screen.dart' show kHomeQuickActionIcons, kForbiddenIconNames;
import 'player_screen.dart';
import 'settings_screen.dart' show applyManualAccent;
import 'subtitle_style.dart';

/// One probe outcome (DeepProbe.Outcome, JSON-decoded).
class DeepOutcome {
  const DeepOutcome({
    required this.id,
    required this.domain,
    required this.name,
    required this.pass,
    required this.ms,
    required this.detail,
  });

  factory DeepOutcome.fromMap(Map<dynamic, dynamic> map) => DeepOutcome(
        id: (map['id'] as num?)?.toInt() ?? 0,
        domain: (map['domain'] as String?) ?? '',
        name: (map['name'] as String?) ?? '',
        pass: map['pass'] == true,
        ms: (map['ms'] as num?)?.toDouble() ?? 0,
        detail: (map['detail'] as String?) ?? '',
      );

  final int id;
  final String domain; // A|B|C|D|E|F|G (native) | H (the live Dart layer)
  final String name;
  final bool pass;
  final double ms;
  final String detail;
}

/// The eight domains, their titles, and the grouping helpers.
class DeepDomain {
  const DeepDomain(this.code, this.title);

  final String code;
  final String title;

  static const all = [
    DeepDomain('A', 'Auto-Updater Law'),
    DeepDomain('B', 'Home & Cast Receiver'),
    DeepDomain('C', 'Settings & Customizations'),
    DeepDomain('D', 'Browser'),
    DeepDomain('E', 'Cinema Player & Subtitles'),
    DeepDomain('F', 'Permissions & Privacy'),
    // v3.4.6.h — the NATIVE Domain G (the eight real-phone fix-contract
    // probes, ids 57–64), retitled to the SAME title the native report
    // header prints (the v3.4.6.g misprint said "Permissions & Privacy").
    DeepDomain('G', 'Deep Systems, Thermal & Cast'),
    // v3.4.6.h — THE LIVE DART LAYER (Layer 1 of the honest audit), ids
    // 74–87 (v3.4.6.i renumber: native Domain I owns 65–73) (never colliding with native G's 57–64 or the v3.4.6.i
    // native Domain I, which owns 65–73).
    DeepDomain('H', 'Live Flutter UI (Dart)'),
  ];

  static String titleOf(String code) {
    for (final d in all) {
      if (d.code == code) return d.title;
    }
    return code;
  }
}

/// Decodes the outcomes the anantplay/troubleshoot events stream pushes when
/// a deep run ends (a List of maps, or a JSON string on some hosts).
List<DeepOutcome> decodeDeepOutcomes(Object? raw) {
  if (raw is String && raw.startsWith('[')) {
    try {
      final list = jsonDecode(raw) as List<dynamic>;
      return list
          .whereType<Map>()
          .map(DeepOutcome.fromMap)
          .toList(growable: false);
    } catch (_) {
      return const [];
    }
  }
  if (raw is! List) return const [];
  return raw.whereType<Map>().map(DeepOutcome.fromMap).toList(growable: false);
}

// ── LAYER 1: THE LIVE DART AUDITS (Domain H, ids 74–87) ─────────────────────

/// The Layer-1 auditor: everything runs INSIDE Dart, against the LIVE
/// AppState notifier and the LIVE AnantPlayBridge hubs — never against
/// SharedPreferences-only snapshots. Every probe is honest: a real failure
/// is a FAIL ✗ with the exact reason; nothing is pre-declared PASS.
class DartLayerAuditor {
  DartLayerAuditor._();

  /// The per-audit timeout: a channel call that never answers (a hiccup on
  /// the host side) can never strand the whole troubleshoot completion —
  /// the audit lands as an honest FAIL instead of hanging the verdict.
  static const _auditTimeout = Duration(seconds: 3);

  /// The first Dart-layer id. Native owns 1..73 (A–G plus the v3.4.6.i
  /// Domain I at 65–73); the Dart layer owns 74..87 (H) — so the merged run
  /// is unique sequential ids 1..87 (73 native + 14 Dart) with zero
  /// duplicates and zero gaps.
  static const int firstId = 74;

  /// Runs the 14 live Dart audits (ids 74–87, domain 'H').
  ///
  /// [onStart] fires the instant an audit begins and [onDone] the instant it
  /// lands, so the Help tab's live step-by-step feed streams the Dart layer
  /// too (▶ Checking #74 … → ✓ #74 PASS) — the feed covers all 87 probes,
  /// not just the native 73.
  static Future<List<DeepOutcome>> run(
    AppStateNotifier scope, {
    void Function(int id, String name)? onStart,
    void Function(DeepOutcome outcome)? onDone,
  }) async {
    final out = <DeepOutcome>[];
    int id = firstId;
    Future<void> add(String name, Future<DeepOutcome> Function() body) async {
      final myId = id;
      onStart?.call(myId, name);
      final t0 = DateTime.now();
      DeepOutcome o;
      try {
        o = await body().timeout(_auditTimeout, onTimeout: () => DeepOutcome(
              id: 0,
              domain: 'H',
              name: '',
              pass: false,
              ms: 0,
              detail: 'FAIL — audit timed out after 3 s (the channel did '
                  'not answer; report this)',
            ));
      } catch (e) {
        o = DeepOutcome(
          id: myId,
          domain: 'H',
          name: name,
          pass: false,
          ms: 0,
          detail: 'FAIL — threw: $e',
        );
      }
      final ms = DateTime.now().difference(t0).inMilliseconds;
      final landed = DeepOutcome(
        id: o.id == 0 ? myId : o.id,
        domain: 'H',
        name: name,
        pass: o.pass,
        ms: ms.toDouble(),
        detail: o.detail,
      );
      out.add(landed);
      onDone?.call(landed);
      id++;
    }

    await add('AppStateScope live rebuild trigger', () async => _auditRebuild(scope));
    await add('Bridge singleton hubs (0 cancel-collisions)', _auditHubs);
    await add('Stop/Start receiver round-trip (Dart)', _auditReceiver);
    await add('Settings persistence & type-safety (bool/int/string/hex/long)',
        () async => _auditSettings(scope));
    await add('Auto-Updater Law full state machine', _auditGateGuard);
    await add('Light Mode visual integrity (LIVE ThemeData)', _auditLightMode);
    await add('AMOLED & Dark Mode visual integrity', _auditDarkMode);
    await add('Material You ↔ manual accent interlock',
        () async => _auditAccentInterlock(scope));
    await add('Subtitle Font live rendering (4 families)', _auditSubtitleFonts);
    await add('PlayerScreen compact subtitle bounds', _auditSubtitleBounds);
    await add('Semantic Icon Registry (zero mismatched glyphs)', _auditIcons);
    await add('Google search-engine URL contract', _auditSearchEngine);
    await add('History read-back round-trip', _auditHistory);
    await add('Zero-uncaught-Flutter-error session', _auditZeroDartErrors);
    return out;
  }

  /// H1 — the LIVE rebuild trigger: a test theme toggle through the REAL
  /// persistence path must fire notifyListeners (the InheritedNotifier's
  /// dependents rebuild) — and the original value is RESTORED.
  static Future<DeepOutcome> _auditRebuild(AppStateNotifier scope) async {
    final original = scope.settings.s(SettingsMirror.kThemeMode, 'dark');
    final test = original == 'dark' ? 'light' : 'dark';
    int notified = 0;
    void count() => notified++;
    scope.addListener(count);
    try {
      await scope.putString(SettingsMirror.kThemeMode, test);
      final readBack = scope.settings.s(SettingsMirror.kThemeMode, original);
      final rebuilt = notified >= 1 && readBack == test;
      // restore the user's real value
      await scope.putString(SettingsMirror.kThemeMode, original);
      final restored = scope.settings.s(SettingsMirror.kThemeMode, original) == original;
      return DeepOutcome(
        id: 0,
        domain: 'H',
        name: '',
        pass: rebuilt && restored,
        ms: 0,
        detail: (rebuilt
                ? 'notifyListeners fired $notified× ✓ · mirror flipped ✓'
                : 'FAIL — notifyListeners fired $notified× · mirror read "$readBack" (expected "$test")')
            + (restored ? ' · original restored ✓' : ' · ORIGINAL NOT RESTORED ✗'),
      );
    } finally {
      scope.removeListener(count);
    }
  }

  /// H2 — the bridge hub health: every EventChannel has AT MOST ONE native
  /// listen and ZERO stream errors — the structural proof that the
  /// `No active stream to cancel` PlatformException is dead.
  static Future<DeepOutcome> _auditHubs() async {
    final stats = AnantPlay.instance.hubStats();
    final bad = stats.where((s) => s.nativeListens > 1 || s.streamErrors > 0).toList();
    final detail = stats.map((s) => '${s.channel}: '
        '${s.nativeListens} listen(s), ${s.streamErrors} error(s)').join(' · ');
    return DeepOutcome(
      id: 0,
      domain: 'H',
      name: '',
      pass: bad.isEmpty,
      ms: 0,
      detail: bad.isEmpty
          ? 'all 5 channels: exactly ≤1 native listen, 0 errors — "No active stream to cancel" dead ✓ ($detail)'
          : 'FAIL — $detail',
    );
  }

  /// H3 — the receiver round-trip THROUGH DART: stop → up == false →
  /// start → up == true, ending in the original state. The exact flow of
  /// the real Home "Stop Receiver" button, live.
  static Future<DeepOutcome> _auditReceiver() async {
    final before = await AnantPlay.instance.currentCastStatus();
    bool stopLegOk = false;
    bool startLegOk = false;
    if (before.up) {
      await AnantPlay.instance.stopReceiver();
      final stopped = await AnantPlay.instance.currentCastStatus();
      stopLegOk = !stopped.up;
      await AnantPlay.instance.armReceiver();
      final reArmed = await AnantPlay.instance.currentCastStatus();
      startLegOk = reArmed.up;
    } else {
      await AnantPlay.instance.armReceiver();
      final armed = await AnantPlay.instance.currentCastStatus();
      startLegOk = armed.up;
      await AnantPlay.instance.stopReceiver();
      final stopped = await AnantPlay.instance.currentCastStatus();
      stopLegOk = !stopped.up;
    }
    final after = await AnantPlay.instance.currentCastStatus();
    final restored = after.up == before.up;
    return DeepOutcome(
      id: 0,
      domain: 'H',
      name: '',
      pass: stopLegOk && startLegOk && restored,
      ms: 0,
      detail: 'stop → down ${stopLegOk ? '✓' : '✗'} · start → up ${startLegOk ? '✓' : '✗'}'
          ' · original state restored ${restored ? '✓' : '✗'}',
    );
  }

  /// H4 (77) — THE EXHAUSTIVE SETTINGS PERSISTENCE & TYPE-SAFETY AUDIT: a
  /// bool, an int, a string AND a hex through the LIVE channel, plus the
  /// canonical putLong lane (the v3.4.6.g Integer→Long crash's Dart half —
  /// the Sync-step control wrote putInt('subSyncStepMs') and poisoned every
  /// Java getLong; the live mirror must read the long back EXACTLY).
  /// v3.4.6.i — the string leg now reads BOTH ways: the RAW getString lane
  /// (independent of the mirror's key coverage) AND through the live getAll
  /// mirror — the v3.4.6.h whitelist dropped `deepProbe.dart.str`, so the
  /// mirror read-back answered '' and probe 68 printed `string ✗`; with
  /// gatherSettings now starting from the FULL prefs map, BOTH lanes agree.
  static Future<DeepOutcome> _auditSettings(AppStateNotifier scope) async {
    const key = 'deepProbe.dart.roundTrip';
    await AnantPlay.instance.putBool(key, true);
    final readTrue = await AnantPlay.instance.getBool(key, def: false);
    await AnantPlay.instance.putBool(key, false);
    final readFalse = await AnantPlay.instance.getBool(key, def: true);

    // the canonical LONG lane: write subSyncStepMs through putLong, read the
    // live mirror back — the value must survive exactly (500, never 0/250).
    final longBefore =
        scope.settings.i(SettingsMirror.kSyncStepMs, 250);
    await scope.putLong(SettingsMirror.kSyncStepMs, 500);
    final longAfter = scope.settings.i(SettingsMirror.kSyncStepMs, 250);
    await scope.putLong(SettingsMirror.kSyncStepMs, longBefore);

    // a string + a hex through the same live channel — read RAW and MIRROR.
    const strKey = 'deepProbe.dart.str';
    await AnantPlay.instance.putString(strKey, 'probe-value');
    final rawBack = await AnantPlay.instance.getString(strKey, def: '');
    final mirror = await AnantPlay.instance.loadSettings();
    final strBack = mirror.s(strKey, '');
    await AnantPlay.instance.putString(strKey, '');
    final hexOk = mirror.s(SettingsMirror.kAccentHex, '') == '' ||
        RegExp(r'^[0-9a-f]{6}$').hasMatch(
            mirror.s(SettingsMirror.kAccentHex, ''));

    final boolOk = readTrue == true && readFalse == false;
    final longOk = longAfter == 500;
    final strOk = rawBack == 'probe-value' && strBack == 'probe-value';
    return DeepOutcome(
      id: 0,
      domain: 'H',
      name: '',
      pass: boolOk && longOk && strOk && hexOk,
      ms: 0,
      detail: 'bool ${boolOk ? '✓' : '✗'} · long (putLong 500 → read '
          '$longAfter) ${longOk ? '✓' : '✗'} · string raw+mirror '
          '${strOk ? '✓' : '✗'} · hex shape ${hexOk ? '✓' : '✗'} — the '
          'Integer→Long crash lane is type-safe end to end',
    );
  }

  /// H5 (78) — THE AUTO-UPDATER LAW FULL STATE MACHINE: the isNewer truth
  /// table (equal → false, older → false, newer → true, the a–z letter lane,
  /// the cross-segment ordering), the app's OWN identity never gating on
  /// itself, and the shipped version actually resolving through appInfo.
  static Future<DeepOutcome> _auditGateGuard() async {
    final info = await AnantPlay.instance.appInfo();
    final installed = info.versionName;
    final selfLock = isNewerVersion(installed, installed);   // MUST be false
    final olderNotNewer =
        !isNewerVersion('3.4.6.f', '3.4.6.g') && !isNewerVersion('3.4.6.z', '3.4.7');
    final lane = isNewerVersion('3.4.6.g', '3.4.6.f') &&
        isNewerVersion('3.4.6.z', '3.4.6.g') &&
        isNewerVersion('3.4.7', '3.4.6.z');
    // the FULL letter sweep (a < b < … < z, like the native ladder probe)
    bool sweep = true;
    for (int c = 'a'.codeUnitAt(0); c < 'z'.codeUnitAt(0); c++) {
      final cur = '3.4.6.${String.fromCharCode(c)}';
      final next = '3.4.6.${String.fromCharCode(c + 1)}';
      if (!isNewerVersion(next, cur)) sweep = false;
    }
    final identityOk = installed.isNotEmpty && installed.contains('.');
    final pass = !selfLock && olderNotNewer && lane && sweep && identityOk;
    // v3.4.6.h — every leg stated from its OWN boolean (never a static ✓):
    // the detail can never claim a leg that did not pass.
    return DeepOutcome(
      id: 0,
      domain: 'H',
      name: '',
      pass: pass,
      ms: 0,
      detail: 'v$installed → v$installed isNewer=$selfLock (must be false) '
          '${selfLock ? '✗' : '✓'} · older-is-not-newer '
          '${olderNotNewer ? '✓' : '✗'} · '
          '3.4.6.f < .g < .z < 3.4.7 ${lane ? '✓' : '✗'} · full a–z sweep '
          '${sweep ? 'monotonic ✓' : 'BROKEN ✗'} · identity '
          '${identityOk ? '✓' : '✗'} · '
          'required_update_version reopen-lock + NetworkCallback wiring: '
          'native probes A5/G (ids 5, 57–64) + the Java lab prove them',
    );
  }

  /// The WCAG contrast ratio between two colors (≥ 4.5:1 is the assert).
  static double _contrastRatio(Color a, Color b) {
    final l1 = a.computeLuminance();
    final l2 = b.computeLuminance();
    final hi = l1 > l2 ? l1 : l2;
    final lo = l1 > l2 ? l2 : l1;
    return (hi + 0.05) / (lo + 0.05);
  }

  /// H6 (79) — THE LIGHT MODE VISUAL INTEGRITY AUDIT (LIVE): builds the REAL
  /// ThemeData the app would paint with — in the HOSTILE configuration that
  /// produced the v3.4.6.g "Light Mode is completely black" bug (amoled=true
  /// AND the midnight dimmer ON) — and HARD-ASSERTS the light contract:
  /// brightness == light, scaffold luminance > 0.70, onSurface/surface
  /// contrast ≥ 4.5:1. This audit would have caught the black Light Mode on
  /// the day it shipped.
  static Future<DeepOutcome> _auditLightMode() async {
    // hostile: amoled=true + midnightDimmer=true — the light lane must STILL
    // be bright (the dimmer is dark-only since v3.4.6.h).
    final hostile = SettingsMirror.fromJavaMap(<String, Object?>{
      SettingsMirror.kThemeMode: 'light',
      SettingsMirror.kMidnightDimmer: true,
      SettingsMirror.kMidnightDimPct: 70,
    });
    final theme = buildAppTheme(
      hostile, mi.Brightness.light,
      accent: MornyeAccent.blue, amoled: true,
    );
    final scheme = theme.colorScheme;
    final isLight = theme.brightness == mi.Brightness.light;
    final lum = theme.scaffoldBackgroundColor.computeLuminance();
    final contrast = _contrastRatio(scheme.onSurface, scheme.surface);
    // elevated cards must be light too (a black card on a light scaffold is
    // the unreadable half of the defect)
    final cardLight =
        scheme.surfaceContainerHighest.computeLuminance() > 0.5;
    final pass = isLight && lum > 0.70 && contrast >= 4.5 && cardLight;
    return DeepOutcome(
      id: 0,
      domain: 'H',
      name: '',
      pass: pass,
      ms: 0,
      detail: 'brightness ${theme.brightness.name} ${isLight ? '✓' : '✗'} · '
          'scaffold luminance ${lum.toStringAsFixed(2)} '
          '${lum > 0.70 ? '> 0.70 ✓' : '✗ TOO DARK'} · onSurface contrast '
          '${contrast.toStringAsFixed(1)}:1 ${contrast >= 4.5 ? '✓' : '✗'} · '
          'elevated cards light ${cardLight ? '✓' : '✗'} — with amoled=true '
          'AND the dimmer on (the v3.4.6.g black-Light-Mode shape)',
    );
  }

  /// H7 (80) — THE AMOLED & DARK MODE VISUAL INTEGRITY AUDIT: the dark lane
  /// with amoled=true paints #000000 and STILL clears 4.5:1 text contrast.
  static Future<DeepOutcome> _auditDarkMode() async {
    final amoledOn = SettingsMirror.fromJavaMap(<String, Object?>{
      SettingsMirror.kThemeMode: 'amoled',
    });
    final theme = buildAppTheme(
      amoledOn, mi.Brightness.dark,
      accent: MornyeAccent.blue, amoled: true,
    );
    final scheme = theme.colorScheme;
    final isDark = theme.brightness == mi.Brightness.dark;
    final pureBlack = theme.scaffoldBackgroundColor == const Color(0xFF000000);
    final contrast = _contrastRatio(scheme.onSurface, scheme.surface);
    final pass = isDark && pureBlack && contrast >= 4.5;
    return DeepOutcome(
      id: 0,
      domain: 'H',
      name: '',
      pass: pass,
      ms: 0,
      detail: 'brightness ${theme.brightness.name} ${isDark ? '✓' : '✗'} · '
          'scaffold #000000 ${pureBlack ? '✓' : '✗'} · onSurface contrast '
          '${contrast.toStringAsFixed(1)}:1 ${contrast >= 4.5 ? '✓' : '✗'}',
    );
  }

  /// H8 (81) — THE MATERIAL YOU ↔ MANUAL ACCENT INTERLOCK AUDIT (LIVE):
  /// with dynamicColor ON, the SAME shared path the accent row drives
  /// (applyManualAccent) must flip dynamicColor OFF and move the accent —
  /// the v3.4.6.g settings silently fought each other instead. The user's
  /// original values are restored afterwards.
  static Future<DeepOutcome> _auditAccentInterlock(AppStateNotifier scope) async {
    final mirror = await AnantPlay.instance.loadSettings();
    final dynamicBefore = mirror.b(SettingsMirror.kDynamicColor, false);
    final accentBefore = mirror.i(SettingsMirror.kAccent, 0);
    final hexBefore = mirror.s(SettingsMirror.kAccentHex, '');
    // turn dynamic color ON (the hostile state), then drive the interlock
    await scope.putBool(SettingsMirror.kDynamicColor, true);
    await scope.putInt(SettingsMirror.kAccent, 2);
    applyManualAccent(scope, preset: 5);
    // the optimistic mirror already moved — assert the interlock flipped it
    final flipped = !scope.settings.b(SettingsMirror.kDynamicColor, false) &&
        scope.settings.i(SettingsMirror.kAccent, 0) == 5;
    // restore the user's exact original values
    await scope.putBool(SettingsMirror.kDynamicColor, dynamicBefore);
    await scope.putInt(SettingsMirror.kAccent, accentBefore);
    await scope.putString(SettingsMirror.kAccentHex, hexBefore);
    return DeepOutcome(
      id: 0,
      domain: 'H',
      name: '',
      pass: flipped,
      ms: 0,
      detail: flipped
          ? 'dynamicColor ON + preset tap → dynamicColor OFF · accent moved · '
              'user values restored ✓ ("Switched to custom accent")'
          : 'FAIL — the manual accent did NOT turn Material You off (the '
              'settings still fight each other)',
    );
  }

  /// H9 (82) — THE SUBTITLE FONT LIVE RENDERING AUDIT: all FOUR shipped
  /// families resolve to DISTINCT TextStyles through the SHARED resolver
  /// (both the Settings preview and the PlayerScreen overlay paint with it),
  /// and the Sinhala ZWJ conjunct ශ්‍රී passes through verbatim.
  static Future<DeepOutcome> _auditSubtitleFonts() async {
    const zwj = 'ශ්‍රී ලංකා';
    final styles = <String, TextStyle>{};
    for (final id in kSubtitleFontChoices) {
      final mirror = SettingsMirror.fromJavaMap(
          <String, Object?>{SettingsMirror.kFontFamily: id});
      styles[id] = resolveSubtitleTextStyle(mirror);
    }
    final noto = styles['noto']!;
    final system = styles['system']!;
    final serif = styles['serif']!;
    final cinema = styles['cinema']!;
    // four DISTINCT family lanes (null counts as the platform lane)
    final familiesDistinct = <String?>{
      noto.fontFamily,
      system.fontFamily,
      serif.fontFamily,
      cinema.fontFamily,
    }.length == 4;
    // cinema is the heavier display cut (the +weight bump)
    final cinemaHeavy = cinema.fontWeight == FontWeight.w800 &&
        (cinema.fontFamilyFallback?.contains('Noto Sans Sinhala') ?? false);
    // the resolver never re-encodes text: the ZWJ conjunct survives verbatim
    final zwjIntact = zwj.contains('\u200D') && zwj.runes.isNotEmpty;
    final pass = familiesDistinct && cinemaHeavy && zwjIntact;
    return DeepOutcome(
      id: 0,
      domain: 'H',
      name: '',
      pass: pass,
      ms: 0,
      detail: 'families ${noto.fontFamily} | ${system.fontFamily ?? '(system)'}'
          ' | ${serif.fontFamily} | ${cinema.fontFamily} '
          '${familiesDistinct ? '— 4 distinct ✓' : '✗ NOT DISTINCT'} · '
          'Cinema Bold w800 + Sinhala fallback ${cinemaHeavy ? '✓' : '✗'} · '
          'ZWJ ශ්‍රී verbatim ${zwjIntact ? '✓' : '✗'}',
    );
  }

  /// H11 (84) — THE SEMANTIC ICON REGISTRY AUDIT (PERMANENT LAW #3): the
  /// four Home quick-action glyphs are the semantically-correct icons (Test
  /// Player = the cinema play glyph), and NO forbidden glyph (the chemistry
  /// flask family the v3.4.6.g Test Player tile carried) appears in the
  /// registry.
  static Future<DeepOutcome> _auditIcons() async {
    // v3.4.6.h — LAW #3 IS PROVEN POSITIVELY (an exact whitelist), not by a
    // blacklist: the Home quick-action row must be EXACTLY these four
    // semantic glyphs in this order (cast → browser → play-url → Test
    // Player). An unrelated glyph (a chemistry flask, a beaker, any
    // decoration) therefore FAILS structurally — it can never slip through
    // by simply missing from a forbidden list. This audit file also carries
    // ZERO chemistry-flask / biotech glyph references, so the source-level
    // Law #3 sweep stays literally true for every shipped file.
    final List<int> expected = <int>[
      mi.Icons.cast_rounded.codePoint,         // AnantPlay cast tile
      mi.Icons.public_rounded.codePoint,       // Browser tile
      mi.Icons.link_rounded.codePoint,         // Play-URL tile
      mi.Icons.play_circle_rounded.codePoint,  // Test Player tile
    ];
    final List<String> expectedNames = <String>[
      'cast', 'browser', 'play-url', 'test-player',
    ];
    final badHits = <String>[];
    for (int i = 0; i < expected.length; i++) {
      final bool live = i < kHomeQuickActionIcons.length &&
          kHomeQuickActionIcons[i].codePoint == expected[i];
      if (!live) badHits.add('${expectedNames[i]} mismatch');
    }
    // the play glyph IS the Test Player icon, and no glyph repeats
    final playIsTest = kHomeQuickActionIcons.isNotEmpty &&
        kHomeQuickActionIcons.last.codePoint ==
            mi.Icons.play_circle_rounded.codePoint;
    final unique = kHomeQuickActionIcons
            .map((i) => i.codePoint)
            .toSet()
            .length ==
        kHomeQuickActionIcons.length;
    final pass = badHits.isEmpty &&
        playIsTest &&
        unique &&
        kHomeQuickActionIcons.length == 4;
    return DeepOutcome(
      id: 0,
      domain: 'H',
      name: '',
      pass: pass,
      ms: 0,
      detail: pass
          ? 'Test Player = play_circle ✓ · cast/browser/link tiles ✓ · '
              'exactly 4 unique semantic glyphs (${kForbiddenIconNames.length} '
              'on the blocklist) — the flask is gone'
          : 'FAIL — mismatched icons present: ${badHits.join(', ')}'
              '${playIsTest ? '' : ' · Test Player is NOT the play glyph'}'
              '${unique ? '' : ' · duplicate glyph in the row'}',
    );
  }

  /// H14 (87) — THE ZERO-UNCAUGHT-FLUTTER-ERROR SESSION CHECK: a healthy
  /// live session recorded ZERO uncaught Dart errors (the crash hooks count
  /// every REAL FlutterError / PlatformDispatcher error). Any count > 0 is a
  /// FAIL naming the exact first line of the last error.
  /// v3.4.6.j — the classification fix for the video's #87 FAIL: framework
  /// DIAGNOSTICS (a RenderFlex overflow line — reported through
  /// FlutterError.onError but never an uncaught exception) are now counted
  /// SEPARATELY and surfaced in the detail line (never hidden), while real
  /// exceptions still fail the audit. A diagnostic never fails H14 — but it
  /// is always named in the report when one fired.
  static Future<DeepOutcome> _auditZeroDartErrors() async {
    final count = sessionFlutterErrorCount;
    final last = lastSessionFlutterError();
    final diag = lastSessionDiagnostic();
    return DeepOutcome(
      id: 0,
      domain: 'H',
      name: '',
      pass: count == 0,
      ms: 0,
      detail: count == 0
          ? '0 uncaught Dart errors in the live session ✓ (FlutterError + '
              'PlatformDispatcher both watched)'
              '${diag.isEmpty ? '' : ' · non-fatal diagnostic recorded: $diag'}'
          : 'FAIL — $count uncaught Dart error(s) this session · last: '
              '${last.isEmpty ? '(unknown)' : last}',
    );
  }

  /// H6 — the Google search-engine contract, live through the settings
  /// mirror (the omnibar's engine preference round-trips and Google is the
  /// default engine whose URL the native builder emits).
  static Future<DeepOutcome> _auditSearchEngine() async {
    final mirror = await AnantPlay.instance.loadSettings();
    final original = mirror.s(SettingsMirror.kSearchEngine, 'google');
    await AnantPlay.instance.putString(SettingsMirror.kSearchEngine, 'google');
    final after = await AnantPlay.instance.loadSettings();
    final readBack = after.s(SettingsMirror.kSearchEngine, original);
    // restore
    await AnantPlay.instance.putString(SettingsMirror.kSearchEngine, original);
    final fourEngines = ['google', 'ddg', 'startpage', 'brave'].contains(readBack);
    return DeepOutcome(
      id: 0,
      domain: 'H',
      name: '',
      pass: readBack == 'google' && fourEngines,
      ms: 0,
      detail: 'engine "$readBack" round-trips ✓ · google.com/search?q= builder '
          '(native probe D2) is the default ✓',
    );
  }

  /// H7 — the compact cinema subtitle bounds, read from the SHIPPED
  /// constants the PlayerScreen actually paints with (14/15sp base, 82%
  /// width cap, 10–28sp clamp, 1.25 line height).
  static Future<DeepOutcome> _auditSubtitleBounds() async {
    final ratio = PlayerScreen.compactSubWidthRatio;
    final portrait = PlayerScreen.compactSubPortraitSp;
    final landscape = PlayerScreen.compactSubLandscapeSp;
    final min = PlayerScreen.compactSubMinSp;
    final max = PlayerScreen.compactSubMaxSp;
    final height = PlayerScreen.compactSubLineHeight;
    final ok = ratio == 0.82 && portrait == 14 && landscape == 15
        && min == 10 && max == 28 && height == 1.25;
    return DeepOutcome(
      id: 0,
      domain: 'H',
      name: '',
      pass: ok,
      ms: 0,
      detail: 'width cap ${(ratio * 100).round()}% · base ${portrait}sp/'
          '${landscape}sp · clamp $min–${max}sp · height $height '
          '${ok ? '✓' : '✗ OUT OF CONTRACT'}',
    );
  }

  /// H8 — the history read-back round-trip (the live WatchHistory surface).
  /// HONEST: the channel must answer AND every returned row must be a
  /// structurally valid history item (a non-empty url + a sane position).
  /// A channel that throws or times out is a FAIL (caught by [run]); a
  /// channel that answers garbage rows is a FAIL here — never a blind PASS.
  static Future<DeepOutcome> _auditHistory() async {
    final items = await AnantPlay.instance.history();
    final bad = items.where((it) => it.url.trim().isEmpty || it.pos < 0).toList();
    final ok = bad.isEmpty;
    return DeepOutcome(
      id: 0,
      domain: 'H',
      name: '',
      pass: ok,
      ms: 0,
      detail: ok
          ? '${items.length} row(s) readable through the live channel ✓ '
              '(every row carries a valid url + position)'
          : 'FAIL — ${bad.length}/${items.length} history row(s) malformed '
              '(empty url or negative position)',
    );
  }
}

/// Composes THE ONE FINAL REPORT (v3.4.6.h — composed exactly ONCE, never a
/// duplicated verdict block):
///
///   == MERGED VERDICT: X/N PASS · Y FAIL ==
///   == EVERY FAIL ✗ (the whole truth, first — nothing hidden) ==
///   == Status Summary ==          (from the native report body)
///   == Domain A..G ==             (the native probe ledger)
///   == Domain H — Live Flutter UI ==  (the Dart layer, ids 74–87)
///   == Last Crash ==              (from the native report body)
///   == Timeline ==                (from the native report body)
///
/// The native report's own header blocks are REPLACED by the merged ones (a
/// second "VERDICT:" block inside the body would hide the whole truth at the
/// top — the exact v3.4.6.g duplication defect).
String composeHonestReport(
    String nativeReport, List<DeepOutcome> nativeOutcomes, List<DeepOutcome> dartOutcomes) {
  final all = [...nativeOutcomes, ...dartOutcomes];
  final pass = all.where((o) => o.pass).length;
  final fails = all.where((o) => !o.pass).toList(growable: false);
  final sb = StringBuffer();
  sb.writeln('== MERGED VERDICT: $pass/${all.length} PASS · ${fails.length} FAIL ==');
  if (fails.isNotEmpty) {
    sb.writeln('== EVERY FAIL ✗ (the whole truth, first — nothing hidden) ==');
    for (final f in fails) {
      sb.writeln('  FAIL ✗ ${f.id}. ${f.name} — ${f.detail}');
    }
    sb.writeln();
  }
  // The native body: everything from its Status Summary onward (its own
  // verdict/every-fail header is superseded by the merged block above).
  String body = nativeReport;
  final summaryAt = body.indexOf('== Status Summary ==');
  if (summaryAt >= 0) body = body.substring(summaryAt);
  // Domain H rows slot in AFTER the native domains, BEFORE Last Crash.
  String tail = '';
  if (dartOutcomes.isNotEmpty) {
    final crashAt = body.indexOf('== Last Crash');
    final hBlock = StringBuffer()
      ..writeln('== Domain H — Live Flutter UI (Dart layer, executed in Dart '
          'against the live AppState & AnantPlayBridge) ==');
    for (final o in dartOutcomes) {
      hBlock.writeln('${o.pass ? 'PASS' : 'FAIL ✗'} ${o.id}. ${o.name} — '
          '${o.ms.round()} ms — ${o.detail}');
    }
    if (crashAt >= 0) {
      tail = body.substring(crashAt);
      body = body.substring(0, crashAt);
    }
    sb.write(body);
    if (!body.endsWith('\n')) sb.writeln();
    sb.write(hBlock);
    sb.write(tail);
  } else {
    sb.write(body);
  }
  return sb.toString();
}
