// AnantPlay Sinhala Player v3.4.6.f — the Help & Troubleshoot hub.
//
// THE HONEST TWO-LAYER FULL-APP DEEP TROUBLESHOOTER: one tap runs the
// 6-domain native audit (A Auto-Updater Law · B Home & Cast · C Settings &
// Customizations · D Browser · E Cinema Player & Subtitles · F Permissions &
// Privacy — 56 live probes) with live progress, PLUS the v3.4.6.f Dart layer
// (G — 8 live Flutter UI audits executed inside Dart against the live
// AppState & AnantPlayBridge hubs). The merged verdict highlights EVERY
// FAIL ✗ at the very top of the report — nothing is ever hidden — and the
// full privacy-safe report is copied to the clipboard AUTOMATICALLY
// (native ClipboardManager + Flutter Clipboard) the instant the run ends,
// with Copy Again + Share buttons.
//
// The run is NON-DESTRUCTIVE: the native engine snapshots the user's
// settings + receiver state first and restores them in a finally block,
// and the Dart layer restores every value it touches (theme, search engine).
import 'dart:async';
import 'dart:ui' show FontFeature;

import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';

import 'package:anantplay_ui/mornye_ui/widgets/animation_utils.dart';

import 'action_feedback.dart';
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
  /// The live progress counter (n of the native probes, while a run is in flight).
  int _done = 0;

  /// v3.4.6.g — THE LIVE TICKER LINES: every probe's own start/verdict line,
  /// newest last. Rendered under the progress bar in real time
  /// ("▶ #34 Security hardening flags…" → "✓ PASS (4 ms)" / "✗ FAIL (...)").
  final List<_TickerLine> _ticker = <_TickerLine>[];
  int _tickerTotal = 0;
  final Map<String, bool> _expanded = {};
  /// v3.4.6.e — the watchdog Timer (owned; cancelled in dispose).
  Timer? _watchdog;

  @override
  void initState() {
    super.initState();
    _loadLast();
    // v3.4.6.e — THE DEEP EVENTS, RAW: the anantplay/troubleshoot channel
    // carries the final full 56-outcome payload (a JSON string) AND the
    // per-probe live progress frames. The typed probeResults() stream
    // pre-decodes into the 10-point shape, so the deep payloads must be
    // routed from HERE.
    _probeSub = AnantPlay.instance.rawTroubleEvents().listen((event) {
      if (event is! String) return;   // progress frames are Maps (below)
      final outcomes = decodeDeepOutcomes(event);
      if (!mounted || outcomes.isEmpty) return;
      // v3.4.6.f — LAYER 1 (the LIVE Dart audits) runs the moment the native
      // layer finishes: the receiver is back at its original state (the
      // native finally block restored it), so the Dart round-trips are safe.
      _runDartLayerAndFinish(outcomes);
    });
    // v3.4.6.f — the LIVE COUNTER: "Running 56 deep feature probes… 32/56".
    // v3.4.6.g — THE LIVE STEP-BY-STEP TICKER. Every progress frame carries the
    // probe that just finished (`outcome`: id/domain/name/pass/ms/detail), so the
    // screen streams the run as it happens instead of showing a bare counter.
    _progressSub = AnantPlay.instance.deepProgressEvents().listen((frame) {
      if (!mounted || _running != true) return;
      final done = (frame['done'] as num?)?.toInt() ?? 0;
      final total = (frame['total'] as num?)?.toInt() ?? 0;
      _TickerLine? line;
      final raw = frame['outcome'];
      if (raw is Map) {
        line = _TickerLine(
          id: (raw['id'] as num?)?.toInt() ?? done,
          domain: (raw['domain'] ?? '?').toString(),
          name: (raw['name'] ?? '').toString(),
          pass: raw['pass'] == true,
          ms: (raw['ms'] as num?)?.toInt() ?? 0,
          detail: (raw['detail'] ?? '').toString(),
        );
      }
      setState(() {
        if (done > 0) _done = done;
        if (total > 0) _tickerTotal = total;
        if (line != null) {
          _ticker.add(line);
          // bounded memory: the ticker shows the whole run, capped at 200 lines
          if (_ticker.length > 200) _ticker.removeAt(0);
        }
      });
    });
  }

  /// v3.4.6.f — THE TWO-LAYER COMPLETION: run the 8 live Dart audits (the
  /// honest Flutter-UI layer), merge them with the native outcomes, compose
  /// the honest report (EVERY FAIL ✗ at the very top), and auto-copy it.
  Future<void> _runDartLayerAndFinish(List<DeepOutcome> nativeOutcomes) async {
    List<DeepOutcome> dartLayer = const [];
    try {
      final scope = AppState.of(context).scope;
      dartLayer = await DartLayerAuditor.run(scope);
    } catch (_) {
      // The Dart layer itself failed to run — an honest empty ledger (the
      // native verdict still stands; the report says the layer threw).
    }
    if (!mounted) return;
    final merged = [...nativeOutcomes, ...dartLayer];
    setState(() {
      _results = merged;
      _running = false;
      _ranOnce = true;
    });
    _autoCopyReport(nativeOutcomes, dartLayer);
  }

  /// The AUTOMATIC clipboard copy the moment the final probe lands: the full
  /// TWO-LAYER structured report (the Java side's scrubbed report + the Dart
  /// layer ledger + the merged honest verdict with every FAIL at the top)
  /// goes to the phone's clipboard through BOTH the native ClipboardManager
  /// (anantplay/cast.copyToClipboard) AND Flutter's Clipboard.setData, then
  /// the confirmation banner shows the truthful pass/fail counts.
  Future<void> _autoCopyReport(List<DeepOutcome> nativeOutcomes,
      [List<DeepOutcome> dartOutcomes = const []]) async {
    // v3.4.6.e — a channel hiccup must never strand the run: if the report
    // cannot be fetched the completion still lands (cards + counter reset),
    // and the Copy Again row re-reads it on demand.
    String nativeReport = '';
    try {
      nativeReport = await AnantPlay.instance.deepReport();
    } catch (_) {}
    final report = composeHonestReport(nativeReport, nativeOutcomes, dartOutcomes);
    if (!mounted) return;
    setState(() {
      _report = report;
      _copied = report.isNotEmpty;
    });
    if (report.isEmpty) return;
    // 1 — Flutter's clipboard.
    try {
      await Clipboard.setData(ClipboardData(text: report));
    } catch (_) {}
    // 2 — the native ClipboardManager (the same lane Slide 6's Copy uses).
    try {
      await AnantPlay.instance.copyToClipboard(report);
    } catch (_) {}
    final all = [...nativeOutcomes, ...dartOutcomes];
    final passed = all.where((o) => o.pass).length;
    final failed = all.length - passed;
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text(
            '$passed/${all.length} PASS · $failed FAIL — honest report '
            'auto-copied ✓${failed > 0 ? ' (see every FAIL at the top)' : ''}'),
        backgroundColor: failed > 0 ? const Color(0xFF46201E) : const Color(0xFF1E4634),
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
      _ticker.clear();   // and so does the step-by-step ticker
      _tickerTotal = 0;
    });
    try {
      await AnantPlay.instance.runDeepProbe();
    } catch (_) {
      // v3.4.6.e — the launch itself failed (host not ready / channel error):
      // surface it immediately instead of spinning for the 90 s watchdog.
      if (mounted) setState(() => _running = false);
      return;
    }
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
    String report = _report;
    if (report.isEmpty) {
      try {
        report = await AnantPlay.instance.deepReport();
      } catch (_) {}
    }
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
    String report = _report;
    if (report.isEmpty) {
      try {
        report = await AnantPlay.instance.deepReport();
      } catch (_) {}
    }
    if (report.isEmpty) return;
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
          // v3.4.6.e — the AUTO-COPIED confirmation banner.
          if (_copied && !_running && total > 0)
            StaggeredListItem(
              index: 0,
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
                          '$passed/$total PASS · ${total - passed} FAIL — honest '
                          'two-layer report auto-copied to clipboard ✓',
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
          // v3.4.6.g — THE LIVE STEP-BY-STEP TICKER, directly below the run
          // control: every probe streams in as it executes, with its exact
          // latency and verdict — nothing is ever hidden behind a spinner.
          if (_running || _ticker.isNotEmpty)
            StaggeredListItem(
              index: 0,
              child: Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: _LiveTicker(lines: _ticker, running: _running,
                    done: _done, total: _tickerTotal),
              ),
            ),
          StaggeredListItem(
            index: _copied && !_running && total > 0 ? 1 : 0,
            child: _ActionCard(
              icon: Icons.health_and_safety_rounded,
              title: _running
                  ? 'Running 56+8 deep feature probes… $_done/56'
                  : _ranOnce || total > 0
                      ? 'Run full app troubleshoot again'
                      : 'Run full app troubleshoot',
              subtitle: _running
                  ? 'Live audit of every feature — settings are restored after.'
                  : 'Every feature · 56 native + 8 Dart-layer probes · 7 domains · honest results.',
              onTap: _running ? null : _run,
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
              onTap: () => checkForUpdateWithFeedback(context),
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

/// v3.4.6.g — ONE live ticker line (a probe that finished during this run).
class _TickerLine {
  const _TickerLine({
    required this.id,
    required this.domain,
    required this.name,
    required this.pass,
    required this.ms,
    required this.detail,
  });

  final int id;
  final String domain;
  final String name;
  final bool pass;
  final int ms;
  final String detail;

  String get text => pass
      ? '✓ PASS ($ms ms) · #$id $domain $name'
      : '✗ FAIL ($ms ms) · #$id $domain $name — $detail';
}

/// The auto-scrolling live feed of probe verdicts (Mornye glass, monospace
/// rhythm, newest last, honest colours).
class _LiveTicker extends StatefulWidget {
  const _LiveTicker({
    required this.lines,
    required this.running,
    required this.done,
    required this.total,
  });

  final List<_TickerLine> lines;
  final bool running;
  final int done;
  final int total;

  @override
  State<_LiveTicker> createState() => _LiveTickerState();
}

class _LiveTickerState extends State<_LiveTicker> {
  final ScrollController _scroll = ScrollController();

  @override
  void didUpdateWidget(_LiveTicker old) {
    super.didUpdateWidget(old);
    // follow the newest line (never fights a user who scrolled up)
    if (widget.lines.length != old.lines.length && _scroll.hasClients) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted || !_scroll.hasClients) return;
        _scroll.animateTo(
          _scroll.position.maxScrollExtent,
          duration: const Duration(milliseconds: 180),
          curve: Curves.easeOut,
        );
      });
    }
  }

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final int total = widget.total > 0 ? widget.total : (widget.done > 0 ? widget.done : 1);
    final double progress = (widget.done / total).clamp(0.0, 1.0);
    return Container(
      decoration: BoxDecoration(
        color: scheme.surfaceContainerHigh.withValues(alpha: 0.55),
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: scheme.outlineVariant.withValues(alpha: 0.4)),
      ),
      padding: const EdgeInsets.fromLTRB(14, 12, 14, 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(widget.running
                      ? Icons.bolt_rounded
                      : Icons.checklist_rounded,
                  size: 18, color: scheme.primary),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  widget.running
                      ? 'Running live · ${widget.done}/$total probes'
                      : 'Last run · ${widget.lines.length} probes streamed',
                  style: TextStyle(
                      color: scheme.onSurface,
                      fontSize: 12.5,
                      fontWeight: FontWeight.w600),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          ClipRRect(
            borderRadius: BorderRadius.circular(4),
            child: LinearProgressIndicator(
              value: widget.running ? progress : 1.0,
              minHeight: 3,
              backgroundColor: scheme.outlineVariant.withValues(alpha: 0.35),
            ),
          ),
          const SizedBox(height: 8),
          SizedBox(
            height: 132,
            child: widget.lines.isEmpty
                ? Center(
                    child: Text('Waiting for the first probe…',
                        style: TextStyle(
                            color: scheme.onSurfaceVariant, fontSize: 11.5)),
                  )
                : ListView.builder(
                    controller: _scroll,
                    padding: EdgeInsets.zero,
                    itemCount: widget.lines.length,
                    itemBuilder: (context, i) {
                      final l = widget.lines[i];
                      return Padding(
                        padding: const EdgeInsets.symmetric(vertical: 1.5),
                        child: Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Icon(
                              l.pass
                                  ? Icons.check_circle_rounded
                                  : Icons.cancel_rounded,
                              size: 13,
                              color: l.pass
                                  ? const Color(0xFF63D668)
                                  : scheme.error,
                            ),
                            const SizedBox(width: 6),
                            Expanded(
                              child: Text(
                                l.text,
                                maxLines: 2,
                                overflow: TextOverflow.ellipsis,
                                style: TextStyle(
                                  color: scheme.onSurfaceVariant,
                                  fontSize: 10.5,
                                  height: 1.3,
                                  fontFeatures: const [
                                    FontFeature.tabularFigures()
                                  ],
                                ),
                              ),
                            ),
                          ],
                        ),
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }
}
