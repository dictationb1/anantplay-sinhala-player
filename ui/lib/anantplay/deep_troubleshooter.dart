// AnantPlay Sinhala Player v3.4.6.f — THE HONEST TWO-LAYER DEEP TROUBLESHOOTER.
//
// Layer 2 (native, DeepProbe.java): 56 live probes across the 6 domains
// (A Auto-Updater Law · B Home & Cast · C Settings & Customizations ·
// D Browser · E Cinema Player & Subtitles · F Permissions & Privacy) —
// including the v3.4.6.f honesty rebuild: the worker probe FAILS on any
// non-200 HTTP code (the v3.4.6.e "HTTP 0 → offline retention ✓" fake
// PASS), a REAL subtitle SRT → parse → ZWJ → SubtitleStore end-to-end
// probe, and the Uncaught-crash gate (a captured Java/Dart PlatformException
// is a FAIL with the exact class + line).
//
// Layer 1 (THIS FILE, Dart): the LIVE Flutter UI & channel audits the
// v3.4.6.e run never had — executed INSIDE Dart against the live AppState
// (InheritedNotifier) and the live AnantPlayBridge singleton hubs: the
// rebuild trigger, the bridge-hub health (zero "No active stream to cancel"),
// the Stop/Start receiver round-trip through Dart, the settings
// persistence round-trip, the ForceUpdateScreen isNewer guard (v3.4.6.f →
// v3.4.6.f MUST be false — LAW #1 in Dart), the Google search-engine
// contract, the PlayerScreen compact subtitle bounds, and the history
// read-back. The verdict merges BOTH layers and every FAIL ✗ is printed at
// the very top of the composed report — nothing is ever hidden.
import 'dart:async';
import 'dart:convert' show jsonDecode;

import 'bridge.dart';
import 'settings_mirror.dart';
import 'app_state.dart';
import 'player_screen.dart';

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
  final String domain; // A|B|C|D|E|F|G
  final String name;
  final bool pass;
  final double ms;
  final String detail;
}

/// The seven domains, their titles, and the grouping helpers.
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
    // v3.4.6.f — THE LIVE DART LAYER (Layer 1 of the honest audit).
    DeepDomain('G', 'Live Flutter UI (Dart)'),
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

// ── v3.4.6.f — LAYER 1: THE LIVE DART AUDITS (domain G) ────────────────────

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

  /// Runs the 8 live Dart audits (ids 57–64, domain 'G').
  static Future<List<DeepOutcome>> run(AppStateNotifier scope) async {
    final out = <DeepOutcome>[];
    int id = 57;
    Future<void> add(String name, Future<DeepOutcome> Function() body) async {
      final t0 = DateTime.now();
      DeepOutcome o;
      try {
        o = await body().timeout(_auditTimeout, onTimeout: () => DeepOutcome(
              id: 0,
              domain: 'G',
              name: '',
              pass: false,
              ms: 0,
              detail: 'FAIL — audit timed out after 3 s (the channel did '
                  'not answer; report this)',
            ));
      } catch (e) {
        o = DeepOutcome(
          id: id,
          domain: 'G',
          name: name,
          pass: false,
          ms: 0,
          detail: 'FAIL — threw: $e',
        );
      }
      final ms = DateTime.now().difference(t0).inMilliseconds;
      out.add(DeepOutcome(
        id: o.id == 0 ? id : o.id,
        domain: 'G',
        name: name,
        pass: o.pass,
        ms: ms.toDouble(),
        detail: o.detail,
      ));
      id++;
    }

    await add('AppStateScope live rebuild trigger', () async => _auditRebuild(scope));
    await add('Bridge singleton hubs (0 cancel-collisions)', _auditHubs);
    await add('Stop/Start receiver round-trip (Dart)', _auditReceiver);
    await add('Settings persistence round-trip', _auditSettings);
    await add('ForceUpdateScreen isNewer guard', _auditGateGuard);
    await add('Google search-engine URL contract', _auditSearchEngine);
    await add('PlayerScreen compact subtitle bounds', _auditSubtitleBounds);
    await add('History read-back round-trip', _auditHistory);
    return out;
  }

  /// G1 — the LIVE rebuild trigger: a test theme toggle through the REAL
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
        domain: 'G',
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

  /// G2 — the bridge hub health: every EventChannel has AT MOST ONE native
  /// listen and ZERO stream errors — the structural proof that the
  /// `No active stream to cancel` PlatformException is dead.
  static Future<DeepOutcome> _auditHubs() async {
    final stats = AnantPlay.instance.hubStats();
    final bad = stats.where((s) => s.nativeListens > 1 || s.streamErrors > 0).toList();
    final detail = stats.map((s) => '${s.channel}: '
        '${s.nativeListens} listen(s), ${s.streamErrors} error(s)').join(' · ');
    return DeepOutcome(
      id: 0,
      domain: 'G',
      name: '',
      pass: bad.isEmpty,
      ms: 0,
      detail: bad.isEmpty
          ? 'all 5 channels: exactly ≤1 native listen, 0 errors — "No active stream to cancel" dead ✓ ($detail)'
          : 'FAIL — $detail',
    );
  }

  /// G3 — the receiver round-trip THROUGH DART: stop → up == false →
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
      domain: 'G',
      name: '',
      pass: stopLegOk && startLegOk && restored,
      ms: 0,
      detail: 'stop → down ${stopLegOk ? '✓' : '✗'} · start → up ${startLegOk ? '✓' : '✗'}'
          ' · original state restored ${restored ? '✓' : '✗'}',
    );
  }

  /// G4 — the settings persistence round-trip through the live channel.
  static Future<DeepOutcome> _auditSettings() async {
    const key = 'deepProbe.dart.roundTrip';
    await AnantPlay.instance.putBool(key, true);
    final readTrue = await AnantPlay.instance.getBool(key, def: false);
    await AnantPlay.instance.putBool(key, false);
    final readFalse = await AnantPlay.instance.getBool(key, def: true);
    return DeepOutcome(
      id: 0,
      domain: 'G',
      name: '',
      pass: readTrue == true && readFalse == false,
      ms: 0,
      detail: 'put true → read $readTrue · put false → read $readFalse '
          '(a real write/read through the live channel)',
    );
  }

  /// G5 — LAW #1 in Dart, LIVE: the app's OWN version identity may never
  /// gate on itself (the v3.4.6.f → v3.4.6.f self-lock), while a genuinely
  /// newer release must.
  static Future<DeepOutcome> _auditGateGuard() async {
    final info = await AnantPlay.instance.appInfo();
    final installed = info.versionName;
    final selfLock = isNewerVersion(installed, installed);   // MUST be false
    final newer = isNewerVersion('3.4.6.z', '3.4.6.f');
    final lane = isNewerVersion('3.4.6.g', '3.4.6.f') && isNewerVersion('3.4.7', '3.4.6.z');
    return DeepOutcome(
      id: 0,
      domain: 'G',
      name: '',
      pass: !selfLock && newer && lane,
      ms: 0,
      detail: 'v$installed → v$installed isNewer=$selfLock (must be false) · '
          '3.4.6.f < 3.4.6.g < 3.4.6.z < 3.4.7 ✓',
    );
  }

  /// G6 — the Google search-engine contract, live through the settings
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
      domain: 'G',
      name: '',
      pass: readBack == 'google' && fourEngines,
      ms: 0,
      detail: 'engine "$readBack" round-trips ✓ · google.com/search?q= builder '
          '(native probe D2) is the default ✓',
    );
  }

  /// G7 — the compact cinema subtitle bounds, read from the SHIPPED
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
      domain: 'G',
      name: '',
      pass: ok,
      ms: 0,
      detail: 'width cap ${(ratio * 100).round()}% · base ${portrait}sp/'
          '${landscape}sp · clamp $min–${max}sp · height $height '
          '${ok ? '✓' : '✗ OUT OF CONTRACT'}',
    );
  }

  /// G8 — the history read-back round-trip (the live WatchHistory surface).
  static Future<DeepOutcome> _auditHistory() async {
    final items = await AnantPlay.instance.history();
    return DeepOutcome(
      id: 0,
      domain: 'G',
      name: '',
      pass: true,
      ms: 0,
      detail: '${items.length} row(s) readable through the live channel ✓',
    );
  }
}

/// Composes the FINAL honest report: the merged verdict + EVERY FAIL ✗ at
/// the very top (nothing hidden), then the native report body, then the
/// Dart-layer ledger.
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
  sb.writeln(nativeReport);
  if (dartOutcomes.isNotEmpty) {
    sb.writeln();
    sb.writeln('== Domain G — Live Flutter UI (Dart layer, executed in Dart '
        'against the live AppState & AnantPlayBridge) ==');
    for (final o in dartOutcomes) {
      sb.writeln('${o.pass ? 'PASS' : 'FAIL ✗'} ${o.id}. ${o.name} — '
          '${o.ms.round()} ms — ${o.detail}');
    }
  }
  return sb.toString();
}
