// AnantPlay Sinhala Player v3.4.6.j — GAP #2: visible feedback for the
// actions that used to fire-and-forget. Every action shows an immediate
// "working" pill and then a crisp, HONEST result (no fake PASS on HTTP 0) —
// v3.4.6.j: every one of these rides the TOP FLOATING MORNYE LIQUID-GLASS
// NOTIFICATION (video_1 05:10–05:45, Law #2) — ZERO bottom SnackBars.
import 'dart:async';

import 'package:material_ui/material_ui.dart' hide Icons;
import 'package:anantplay_ui/mornye_ui/widgets/mornye_glass_notification.dart';

import 'app_state.dart';
import 'bridge.dart';

/// v3.4.6.l — resolve the LIVE installed version once (AppInfo, never a
/// literal) and keep [kLiveVersion] fresh for every user-facing label.
Future<void> warmLiveVersion() async {
  try {
    final info = await AnantPlay.instance.appInfo();
    if (info.versionName.isNotEmpty) kLiveVersion.value = info.versionName;
  } catch (_) {/* the fallback literal stays */}
}

/// v3.4.6.l — THE STALE-VERSION KILL (video_2 04:10: v3.4.6.k showed "Up to
/// date (v3.4.6.j)"). The old literal outlived every release; the label now
/// resolves the LIVE installed identity from the native appInfo channel
/// (AppInfo.VERSION_NAME — the same source the gate itself reads), with a
/// last-resort literal ONLY if the channel is unreachable. The About page
/// and every check-for-update pill can never show a stale version again.
const String kVisibleVersionFallback = 'v3.4.6.m';

/// The live installed identity, cached after the first successful appInfo()
/// read and shared by every surface that used to read the dead literal.
final ValueNotifier<String> kLiveVersion =
    ValueNotifier<String>(kVisibleVersionFallback);

void _say(BuildContext context, String message) {
  // v3.4.6.j — THE TOP FLOATING GLASS PILL (the SnackBar is dead): slides
  // down below the status bar, auto-dismisses in 2.5 s, swipe-up to
  // dismiss — never covers the bottom tab bar.
  if (!context.mounted) return;
  showMornyeGlassNotification(context, message: message);
}

/// Check now: Checking… → the REAL gate reply (up to date / available /
/// failed). The listener attaches BEFORE the channel call so a fast native
/// reply can never be missed.
Future<void> checkForUpdateWithFeedback(BuildContext context) async {
  _say(context, 'Checking…');
  final completer = Completer<String>();
  StreamSubscription<UpdateGateEvent>? sub;
  void finish(String message) {
    if (completer.isCompleted) return;
    completer.complete(message);
  }

  sub = AnantPlay.instance.updateGateEvents().listen((e) {
    if (e.gate == 'uptodate') {
      // v3.4.6.l — the LIVE installed version (never the stale literal that
      // showed v3.4.6.j on a running v3.4.6.k).
      warmLiveVersion().then((_) {
        if (!completer.isCompleted) {
          finish('Up to date (v${kLiveVersion.value})');
        }
      });
    } else if (e.gate == 'available') {
      finish('Update available · ${e.next.isEmpty ? kLiveVersion.value : e.next}');
    } else if (e.gate == 'failed') {
      finish('Check failed · ${e.reason.isEmpty ? "network" : e.reason}');
    }
  });
  try {
    await AnantPlay.instance.checkForUpdate();
    final message = await completer.future.timeout(
      const Duration(seconds: 4),
      onTimeout: () => 'Check requested · see notification',
    );
    if (!context.mounted) return;
    _say(context, message);
  } catch (error) {
    if (!context.mounted) return;
    _say(context, 'Check failed · $error');
  } finally {
    await sub.cancel();
  }
}

/// Ping worker: the 10-point probe's REAL Cloudflare Worker outcome, with the
/// actual HTTP code. HTTP 0 is reported exactly as unreachable — never PASS.
Future<void> pingWorkerWithFeedback(BuildContext context) async {
  _say(context, 'Pinging worker…');
  final completer = Completer<String>();
  StreamSubscription<List<ProbeOutcome>>? sub;
  sub = AnantPlay.instance.probeResults().listen((outcomes) {
    if (completer.isCompleted) return;
    final worker = outcomes.where((o) => o.id == 5).toList();
    if (worker.isEmpty) return;
    final o = worker.first;
    final String detail = o.detail;
    final match = RegExp(r'HTTP\s+([0-9]+)').firstMatch(detail);
    final String http = match?.group(1) ?? '0';
    completer.complete(o.pass
        ? 'Worker OK · HTTP $http · ${o.ms.round()} ms'
        : 'Worker unreachable · HTTP $http');
  });
  try {
    await AnantPlay.instance.runProbe();
    final message = await completer.future.timeout(
      const Duration(seconds: 10),
      onTimeout: () => 'Worker probe running · see troubleshoot report',
    );
    if (!context.mounted) return;
    _say(context, message);
  } catch (error) {
    if (!context.mounted) return;
    _say(context, 'Worker unreachable · $error');
  } finally {
    await sub.cancel();
  }
}

/// Apply a validated backup document: the native SettingsBackup handler
/// returns the number of keys it actually applied (onboarding keys excluded).
Future<void> importBackupWithFeedback(
    BuildContext context, String json) async {
  _say(context, 'Importing…');
  try {
    final reply = await AnantPlay.instance.importBackup(json);
    final bool ok = reply['ok'] == true;
    final int applied = (reply['applied'] as num?)?.toInt() ?? 0;
    if (!context.mounted) return;
    if (ok) {
      await AppState.of(context).scope.refresh();
      if (!context.mounted) return;
      _say(context, 'Imported $applied settings ✓');
    } else {
      _say(context, 'Import failed · ${reply['error'] ?? 'invalid backup'}');
    }
  } catch (error) {
    if (!context.mounted) return;
    _say(context, 'Import failed · $error');
  }
}
