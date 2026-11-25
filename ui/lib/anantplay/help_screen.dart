// AnantPlay Sinhala Player v3.4.6.c — the Help & Troubleshoot hub: 1-tap full
// 10-probe run with LIVE results, 1-tap Copy privacy-safe report, 1-tap Share
// report, 1-tap Check for update, and Re-run Setup Wizard.
// v3.4.6.b — FULL MORNYE PARITY: the action cards reveal on the upstream
// StaggeredListItem ladder (the exact upstream list-entry effect).
// v3.4.6.c — reachable from the ALWAYS-VISIBLE bottom Help tab (was an
// orphaned widget nothing rendered — real-phone complaint #1/#2).
import 'dart:async';

import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';

import 'package:anantplay_ui/mornye_ui/widgets/animation_utils.dart';

import 'app_state.dart';
import 'bridge.dart';

class HelpScreen extends StatefulWidget {
  const HelpScreen({super.key});

  @override
  State<HelpScreen> createState() => _HelpScreenState();
}

class _HelpScreenState extends State<HelpScreen> {
  List<ProbeOutcome> _results = const [];
  bool _running = false;
  bool _ranOnce = false;
  StreamSubscription<List<ProbeOutcome>>? _probeSub;
  /// v3.4.6.d — the 20 s watchdog Timer, owned so dispose can cancel it (a
  /// pending timer at teardown fails the widget-test invariants).
  Timer? _watchdog;

  @override
  void initState() {
    super.initState();
    _loadLast();
    // v3.4.6.c — LIVE results: the probe stream pushes the 10 outcomes the
    // moment the run ends (was: a blind "running…" label with no feedback).
    _probeSub = AnantPlay.instance.probeResults().listen((outcomes) {
      if (mounted && outcomes.isNotEmpty) {
        setState(() {
          _results = outcomes;
          _running = false;
          _ranOnce = true;
        });
        // v3.4.6.d — THE 1-TAP AUTO-COPY CONTRACT: the moment Probe 10
        // finishes, the complete privacy-safe report is copied to the
        // Android clipboard AUTOMATICALLY (zero extra tap) and the green
        // confirmation pill shows. Copy Again + Share stay right below.
        _autoCopyReport(outcomes);
      }
    });
  }

  /// v3.4.6.d — the automatic clipboard copy the moment all 10 probes land.
  Future<void> _autoCopyReport(List<ProbeOutcome> outcomes) async {
    final passed = outcomes.where((o) => o.pass).length;
    final lines = outcomes
        .map((o) =>
            '${o.pass ? "PASS" : "FAIL"} #${o.id} ${o.name} (${o.ms.toInt()}ms)')
        .join('\n');
    final report =
        'AnantPlay Sinhala Player v3.4.6.d (30410) — $passed/${outcomes.length} PASS\n$lines';
    await Clipboard.setData(ClipboardData(text: report));
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text(
            '$passed/${outcomes.length} Troubleshoot complete — Privacy-safe report auto-copied to clipboard ✓'),
        backgroundColor: const Color(0xFF1E4634),
        duration: const Duration(seconds: 4),
      ));
    }
  }

  @override
  void dispose() {
    _probeSub?.cancel();
    _watchdog?.cancel();
    super.dispose();
  }

  Future<void> _loadLast() async {
    final last = await AnantPlay.instance.lastProbeResults();
    if (mounted && last.isNotEmpty) setState(() => _results = last);
  }

  Future<void> _run() async {
    setState(() => _running = true);
    await AnantPlay.instance.runProbe();
    // The stream listener flips _running off; a 20 s watchdog covers a
    // channel hiccup so the button can never stay stuck. The Timer is
    // owned + cancelled in dispose (v3.4.6.d), never leaked at teardown.
    _watchdog?.cancel();
    _watchdog = Timer(const Duration(seconds: 20), () async {
      if (mounted && _running) {
        await _loadLast();
        setState(() => _running = false);
      }
    });
  }

  String get _report {
    if (_results.isEmpty) {
      return 'AnantPlay Sinhala Player v3.4.6.d (30410) — no probe run yet.';
    }
    final passed = _results.where((o) => o.pass).length;
    final lines = _results
        .map((o) =>
            '${o.pass ? "PASS" : "FAIL"} #${o.id} ${o.name} (${o.ms.toInt()}ms)')
        .join('\n');
    return 'AnantPlay Sinhala Player v3.4.6.d (30410) — $passed/${_results.length} PASS\n$lines';
  }

  Future<void> _copy() async {
    await Clipboard.setData(ClipboardData(text: _report));
    if (mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Report copied')));
    }
  }

  Future<void> _share() async {
    // v3.4.6.c — 1-tap share through the system sheet (ACTION_SEND).
    await AnantPlay.instance.shareText(_report);
    if (mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Sharing the report…')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final passed = _results.where((o) => o.pass).length;
    final total = _results.length;
    return Scaffold(
      backgroundColor: scheme.surface,
      appBar: AppBar(
        title: const Text('Troubleshoot'),
        backgroundColor: scheme.scrim.withValues(alpha: 0.2),
        surfaceTintColor: Colors.transparent,
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
        children: [
          StaggeredListItem(
            index: 0,
            child: _ActionCard(
              icon: Icons.health_and_safety_rounded,
              title: _running
                  ? 'Running the 10-point probe…'
                  : _ranOnce || total > 0
                      ? 'Run full app troubleshoot again'
                      : 'Run full app troubleshoot',
              subtitle:
                  'Receiver · cast · subtitles · updater — all 10 probes, live results.',
              onTap: _running ? null : _run,
            ),
          ),
          StaggeredListItem(
            index: 1,
            child: _ActionCard(
              icon: Icons.copy_rounded,
              title: 'Copy Again',
              subtitle: 'Zero personal data — IMDb IDs and milliseconds only.',
              onTap: () => _copy(),
            ),
          ),
          StaggeredListItem(
            index: 2,
            child: _ActionCard(
              icon: Icons.ios_share_rounded,
              title: 'Share report',
              subtitle: 'Open the system share sheet with the full report.',
              onTap: () => _share(),
            ),
          ),
          StaggeredListItem(
            index: 3,
            child: _ActionCard(
              icon: Icons.update_rounded,
              title: 'Check for update',
              subtitle: 'Direct release check — no API calls.',
              onTap: () => AnantPlay.instance.checkForUpdate(),
            ),
          ),
          StaggeredListItem(
            index: 4,
            child: _ActionCard(
              icon: Icons.restart_alt_rounded,
              title: 'Run setup wizard',
              subtitle: 'Replay the 6-slide first-launch guide.',
              // v3.4.6.c — the version-stamped re-arm (was: a raw putBool the
              // upgraded shell's gate ignored — the wizard never reopened).
              onTap: () => AppState.of(context).scope.rerunOnboarding(),
            ),
          ),
          if (_results.isNotEmpty || _running) ...[
            const SizedBox(height: 10),
            Text(
              _running ? '…' : '$passed/$total PASS',
              textAlign: TextAlign.center,
              style: TextStyle(
                  color: passed == total
                      ? const Color(0xFF63D668)
                      : scheme.tertiary,
                  fontSize: 15,
                  fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 6),
            for (final o in _results)
              _ProbeRow(outcome: o),
          ],
        ],
      ),
    );
  }
}

class _ActionCard extends StatelessWidget {
  const _ActionCard({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.onTap,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Material(
        color: scheme.surfaceContainerHigh.withValues(alpha: 0.35),
        borderRadius: BorderRadius.circular(18),
        child: InkWell(
          borderRadius: BorderRadius.circular(18),
          onTap: onTap,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(18),
              border: Border.all(
                  color: scheme.outlineVariant.withValues(alpha: 0.4)),
            ),
            child: Row(
              children: [
                Container(
                  width: 38,
                  height: 38,
                  decoration: BoxDecoration(
                    color: scheme.primaryContainer.withValues(alpha: 0.5),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Icon(icon, color: scheme.primary, size: 20),
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(title,
                          style: TextStyle(
                              color: scheme.onSurface,
                              fontSize: 14,
                              fontWeight: FontWeight.w600)),
                      const SizedBox(height: 3),
                      Text(subtitle,
                          style: TextStyle(
                              color: scheme.onSurfaceVariant,
                              fontSize: 11.5)),
                    ],
                  ),
                ),
                Icon(Icons.chevron_right_rounded,
                    color: scheme.onSurfaceVariant),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _ProbeRow extends StatelessWidget {
  const _ProbeRow({required this.outcome});

  final ProbeOutcome outcome;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Row(
        children: [
          Icon(
            outcome.pass
                ? Icons.check_circle_rounded
                : Icons.cancel_rounded,
            color: outcome.pass
                ? const Color(0xFF63D668)
                : scheme.error,
            size: 17,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text('#${outcome.id} ${outcome.name}',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                    color: scheme.onSurface, fontSize: 13)),
          ),
          Text('${outcome.ms.toInt()} ms',
              style: TextStyle(
                  color: scheme.onSurfaceVariant, fontSize: 11)),
        ],
      ),
    );
  }
}
