// AnantPlay Sinhala Player v3.4.6.f — GAP #2: visible feedback for the
// actions that used to fire-and-forget. Every action shows an immediate
// "working" snack and then a crisp, HONEST result (no fake PASS on HTTP 0).
import 'dart:async';

import 'package:material_ui/material_ui.dart' hide Icons;

import 'app_state.dart';
import 'bridge.dart';

/// The installed identity used in the "Up to date" reply. Kept as a literal
/// ONLY for this user-facing label; the updater's gate itself reads appInfo.
const String kVisibleVersion = 'v3.4.6.f';

void _say(BuildContext context, String message) {
  if (!context.mounted) return;
  final messenger = ScaffoldMessenger.maybeOf(context);
  messenger
    ?..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(content: Text(message), duration: const Duration(seconds: 3)));
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
      finish('Up to date ($kVisibleVersion)');
    } else if (e.gate == 'available') {
      finish('Update available · ${e.next.isEmpty ? kVisibleVersion : e.next}');
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
    _say(context, message);
  } catch (error) {
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
    _say(context, message);
  } catch (error) {
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
      _say(context, 'Imported $applied settings ✓');
    } else {
      _say(context, 'Import failed · ${reply['error'] ?? 'invalid backup'}');
    }
  } catch (error) {
    _say(context, 'Import failed · $error');
  }
}
