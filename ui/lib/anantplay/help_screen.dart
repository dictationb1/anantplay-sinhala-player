// AnantPlay Sinhala Player v3.4.6.e — the Help & Troubleshoot hub.
//
// THE 54-PROBE FULL-APP DEEP TROUBLESHOOTER: one tap runs the 6-domain
// live audit (A Auto-Updater Law · B Home & Cast · C Settings &
// Customizations · D Browser · E Cinema Player & Subtitles · F Permissions
// & Privacy) with live progress ("Running 54 deep feature probes… 32/54"),
// grouped collapsible PASS/FAIL cards per domain, and — the INSTANT the
// final probe completes — the full privacy-safe report is copied to the
// clipboard AUTOMATICALLY (native ClipboardManager + Flutter Clipboard)
// with the green confirmation banner, plus Copy Again + Share buttons.
//
// The run is NON-DESTRUCTIVE: the native engine snapshots the user's
// settings + receiver state first and restores them in a finally block.
import 'dart:async';

import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';

import 'package:anantplay_ui/mornye_ui/widgets/animation_utils.dart';

import 'app_state.dart';
import 'bridge.dart';
import 'deep_troubleshooter.dart';

class HelpScreen extends StatefulWidget {
  const HelpScreen({super.key});

  @override
  State<HelpScreen> createState() => _HelpScreenState();
}

class _HelpScreenState extends State<HelpScreen> {
  List<DeepOutcome> _results = const [];
  bool _running = false;
  bool _ranOnce = false;
  bool _copied = false;
  String _report = '';
  StreamSubscription<dynamic>? _probeSub;
  StreamSubscription<Map<dynamic, dynamic>>? _progressSub;
  /// v3.4.6.e — the LIVE progress counter (n of 54, while a run is in flight).
  int _done = 0;
  final Map<String, bool> _expanded = {};
  /// v3.4.6.e — the watchdog Timer (owned; cancelled in dispose).
  Timer? _watchdog;

  @override
  void initState() {
    super.initState();
    _loadLast();
    // v3.4.6.e — THE DEEP EVENTS, RAW: the anantplay/troubleshoot channel
    // carries the final full 54-outcome payload (a JSON string) AND the
    // per-probe live progress frames. The typed probeResults() stream
    // pre-decodes into the 10-point shape, so the deep payloads must be
    // routed from HERE.
    _probeSub = AnantPlay.instance.rawTroubleEvents().listen((event) {
      if (event is! String) return;   // progress frames are Maps (below)
      final outcomes = decodeDeepOutcomes(event);
      if (!mounted || outcomes.isEmpty) return;
      setState(() {
        _results = outcomes;
        _running = false;
        _ranOnce = true;
      });
      _autoCopyReport(outcomes);
    });
    // v3.4.6.e — the LIVE COUNTER: "Running 54 deep feature probes… 32/54".
    _progressSub = AnantPlay.instance.deepProgressEvents().listen((frame) {
      if (!mounted || _running != true) return;
      final done = (frame['done'] as num?)?.toInt() ?? 0;
      if (done > 0) setState(() => _done = done);
    });
  }

  /// v3.4.6.e — the AUTOMATIC clipboard copy the moment the final probe
  /// lands: the full structured report (built on the Java side, scrubbed)
  /// goes to the phone's clipboard through BOTH the native ClipboardManager
  /// (anantplay/cast.copyToClipboard) AND Flutter's Clipboard.setData, then
  /// the green confirmation banner shows.
  Future<void> _autoCopyReport(List<DeepOutcome> outcomes) async {
    final report = await AnantPlay.instance.deepReport();
    if (!mounted) return;
    setState(() {
      _report = report;
      _copied = true;
    });
    // 1 — Flutter's clipboard.
    await Clipboard.setData(ClipboardData(text: report));
    // 2 — the native ClipboardManager (the same lane Slide 6's Copy uses).
    try {
      await AnantPlay.instance.copyToClipboard(report);
    } catch (_) {}
    final passed = outcomes.where((o) => o.pass).length;
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text(
            '$passed/${outcomes.length} Deep Troubleshoot complete — 100% '
            'Privacy-Safe report auto-copied to clipboard ✓'),
        backgroundColor: const Color(0xFF1E4634),
        duration: const Duration(seconds: 5),
      ));
    }
  }

  @override
  void dispose() {
    _probeSub?.cancel();
    _progressSub?.cancel();
    _watchdog?.cancel();
    super.dispose();
  }

  Future<void> _loadLast() async {
    try {
      final last = await AnantPlay.instance.lastDeepResults();
      if (mounted && last.isNotEmpty) {
        setState(() => _results = last);
      }
    } catch (_) {}
  }

  Future<void> _run() async {
    setState(() {
      _running = true;
      _done = 0;   // the live counter restarts with the run
    });
    await AnantPlay.instance.runDeepProbe();
    // The stream listener flips _running off; the watchdog covers a channel
    // hiccup so the button can never stay stuck (owned + cancelled).
    _watchdog?.cancel();
    _watchdog = Timer(const Duration(seconds: 90), () async {
      if (mounted && _running) {
        await _loadLast();
        setState(() => _running = false);
      }
    });
  }

  Future<void> _copy() async {
    final report =
        _report.isNotEmpty ? _report : await AnantPlay.instance.deepReport();
    await Clipboard.setData(ClipboardData(text: report));
    try {
      await AnantPlay.instance.copyToClipboard(report);
    } catch (_) {}
    if (mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Report copied')));
    }
  }

  Future<void> _share() async {
    final report =
        _report.isNotEmpty ? _report : await AnantPlay.instance.deepReport();
    await AnantPlay.instance.shareText(report);
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
                  ? 'Running 54 deep feature probes… $_done/54'
                  : _ranOnce || total > 0
                      ? 'Run full app troubleshoot again'
                      : 'Run full app troubleshoot',
              subtitle: _running
                  ? 'Live audit of every feature — settings are restored after.'
                  : 'Every feature in the app · 54 probes · 6 domains · live results.',
              onTap: _running ? null : _run,
            ),
          ),
          // v3.4.6.e — the AUTO-COPIED confirmation banner.
          if (_copied && !_running && total > 0)
            StaggeredListItem(
              index: 1,
              child: Padding(
                padding: const EdgeInsets.only(bottom: 10),
                child: Container(
                  padding: const EdgeInsets.symmetric(
                      horizontal: 16, vertical: 12),
                  decoration: BoxDecoration(
                    color: const Color(0xFF1E4634).withValues(alpha: 0.9),
                    borderRadius: BorderRadius.circular(16),
                    border: Border.all(
                        color: const Color(0xFF63D668).withValues(alpha: 0.6)),
                  ),
                  child: Row(
                    children: [
                      const Icon(Icons.check_circle_rounded,
                          color: Color(0xFF63D668), size: 22),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Text(
                          '$passed/$total Deep Troubleshoot complete — 100% '
                          'Privacy-Safe report auto-copied to clipboard ✓',
                          style: const TextStyle(
                              color: Colors.white,
                              fontSize: 12.5,
                              fontWeight: FontWeight.w600),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          StaggeredListItem(
            index: 2,
            child: _ActionCard(
              icon: Icons.copy_rounded,
              title: 'Copy Again',
              subtitle: 'Zero personal data — IMDb IDs and milliseconds only.',
              onTap: _copy,
            ),
          ),
          StaggeredListItem(
            index: 3,
            child: _ActionCard(
              icon: Icons.ios_share_rounded,
              title: 'Share report',
              subtitle: 'Open the system share sheet with the full report.',
              onTap: _share,
            ),
          ),
          StaggeredListItem(
            index: 4,
            child: _ActionCard(
              icon: Icons.update_rounded,
              title: 'Check for update',
              subtitle: 'Direct release check — no API calls.',
              onTap: () => AnantPlay.instance.checkForUpdate(),
            ),
          ),
          StaggeredListItem(
            index: 5,
            child: _ActionCard(
              icon: Icons.restart_alt_rounded,
              title: 'Run setup wizard',
              subtitle: 'Replay the 6-slide first-launch guide.',
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
            // v3.4.6.e — the grouped collapsible domain cards.
            for (final d in DeepDomain.all)
              if (_results.any((o) => o.domain == d.code))
                _DomainCard(
                  domain: d,
                  outcomes:
                      _results.where((o) => o.domain == d.code).toList(),
                  expanded: _expanded[d.code] ?? true,
                  onToggle: () => setState(() =>
                      _expanded[d.code] = !(_expanded[d.code] ?? true)),
                ),
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

/// One domain's collapsible card: header (code · title · n/n PASS) + the
/// probe rows (PASS ✓ / FAIL ✗ + ms + the detail line).
class _DomainCard extends StatelessWidget {
  const _DomainCard({
    required this.domain,
    required this.outcomes,
    required this.expanded,
    required this.onToggle,
  });

  final DeepDomain domain;
  final List<DeepOutcome> outcomes;
  final bool expanded;
  final VoidCallback onToggle;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final passed = outcomes.where((o) => o.pass).length;
    final allPass = passed == outcomes.length;
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Material(
        color: scheme.surfaceContainerHigh.withValues(alpha: 0.3),
        borderRadius: BorderRadius.circular(16),
        child: InkWell(
          borderRadius: BorderRadius.circular(16),
          onTap: onToggle,
          child: Container(
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(16),
              border: Border.all(
                  color: allPass
                      ? const Color(0xFF63D668).withValues(alpha: 0.4)
                      : scheme.outlineVariant.withValues(alpha: 0.4)),
            ),
            padding: const EdgeInsets.fromLTRB(14, 10, 14, 10),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Text(domain.code,
                        style: TextStyle(
                            color: scheme.primary,
                            fontSize: 13,
                            fontWeight: FontWeight.w800)),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(domain.title,
                          style: TextStyle(
                              color: scheme.onSurface,
                              fontSize: 13.5,
                              fontWeight: FontWeight.w600)),
                    ),
                    Text('$passed/${outcomes.length}',
                        style: TextStyle(
                            color: allPass
                                ? const Color(0xFF63D668)
                                : scheme.tertiary,
                            fontSize: 12.5,
                            fontWeight: FontWeight.w700)),
                    const SizedBox(width: 6),
                    Icon(
                        expanded
                            ? Icons.expand_less_rounded
                            : Icons.expand_more_rounded,
                        color: scheme.onSurfaceVariant,
                        size: 20),
                  ],
                ),
                if (expanded) ...[
                  const SizedBox(height: 4),
                  for (final o in outcomes)
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 4),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Icon(
                            o.pass
                                ? Icons.check_circle_rounded
                                : Icons.cancel_rounded,
                            color: o.pass
                                ? const Color(0xFF63D668)
                                : scheme.error,
                            size: 16,
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text('${o.id}. ${o.name}',
                                    style: TextStyle(
                                        color: scheme.onSurface,
                                        fontSize: 12.5,
                                        fontWeight: FontWeight.w500)),
                                if (o.detail.isNotEmpty)
                                  Text(o.detail,
                                      style: TextStyle(
                                          color: scheme.onSurfaceVariant,
                                          fontSize: 10.5)),
                              ],
                            ),
                          ),
                          Text('${o.ms.toInt()} ms',
                              style: TextStyle(
                                  color: scheme.onSurfaceVariant,
                                  fontSize: 10)),
                        ],
                      ),
                    ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}
