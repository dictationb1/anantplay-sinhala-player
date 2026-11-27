// AnantPlay Sinhala Player v3.4.6.g — the Help & Troubleshoot hub.
//
// THE HONEST TWO-LAYER FULL-APP DEEP TROUBLESHOOTER: one tap runs the
// 7-domain native audit (A Auto-Updater Law · B Home & Cast · C Settings &
// Customizations · D Browser · E Cinema Player & Subtitles · F Permissions &
// Privacy · G the v3.4.6.g real-phone fix contracts · I the v3.4.6.i
// video-audit contracts · K the v3.4.6.k silent-update contracts —
// 78 live probes) with
// live progress, PLUS the Dart layer (H — 8 live Flutter UI audits executed
// inside Dart against the live AppState & AnantPlayBridge hubs, ids 79–92 —
// v3.4.6.h grew the Dart layer from 8 to 14 audits: live Light/AMOLED theme
// integrity, the Material You interlock, the 4 subtitle fonts, the semantic
// icon registry and the zero-uncaught-error session check).
// The merged run is 92 unique sequential ids (1..92) across 10 domains (78
// native A–G + I, plus 14 Dart H). v3.4.6.i closed the LAST id collision: the
// native Domain I (the nine video-audit fix contracts) owns ids 65–73, so the
// Dart layer moved from 65–78 to 79–92 — zero duplicate ids, zero gaps. The merged verdict highlights EVERY
// FAIL ✗ at the very top of the report — nothing is ever hidden — and the
// full privacy-safe report is copied to the clipboard AUTOMATICALLY
// (native ClipboardManager + Flutter Clipboard) the instant the run ends,
// with Copy Again + Share buttons.
//
// The run is NON-DESTRUCTIVE: the native engine snapshots the user's
// settings + receiver state first and restores them in a finally block,
// and the Dart layer restores every value it touches (theme, search engine).
import 'dart:async';

import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';

import 'package:anantplay_ui/mornye_ui/widgets/animation_utils.dart';
import 'package:anantplay_ui/mornye_ui/widgets/mornye_glass_notification.dart';
import 'package:anantplay_ui/mornye_ui/theme/mornye_icons.dart';

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
  /// The live progress counter (n of 92 probes — 78 native (69 through
  /// v3.4.6.h, the 9 v3.4.6.i Domain I video-audit probes and the 5
  /// v3.4.6.k Domain K silent-update probes) plus the
  /// 14 Dart-layer audits, ids 1..92 with zero gaps).
  int _done = 0;
  final Map<String, bool> _expanded = {};
  /// v3.4.6.e — the watchdog Timer (owned; cancelled in dispose).
  Timer? _watchdog;

  @override
  void initState() {
    super.initState();
    _loadLast();
    // v3.4.6.e — THE DEEP EVENTS, RAW: the anantplay/troubleshoot channel
    // carries the final full 64-outcome payload (a JSON string) AND the
    // per-probe live progress frames. The typed probeResults() stream
    // pre-decodes into the 10-point shape, so the deep payloads must be
    // routed from HERE.
    _probeSub = AnantPlay.instance.rawTroubleEvents().listen((event) {
      // The final payload is the JSON-array STRING of all native outcomes, but
      // some Flutter embedding/test-messenger builds deliver it pre-decoded as
      // a List. decodeDeepOutcomes handles BOTH; the per-probe progress frames
      // are Maps ({t:'deep'/'deepStart'}) and decode to empty (routed below).
      final outcomes = decodeDeepOutcomes(event);
      if (!mounted || outcomes.isEmpty) return;
      // v3.4.6.f — LAYER 1 (the LIVE Dart audits) runs the moment the native
      // layer finishes: the receiver is back at its original state (the
      // native finally block restored it), so the Dart round-trips are safe.
      _runDartLayerAndFinish(outcomes);
    });
    // v3.4.6.g — the LIVE COUNTER: "Running 72 deep feature probes… 32/72".
    _progressSub = AnantPlay.instance.deepProgressEvents().listen((frame) {
      if (!mounted || _running != true) return;
      final done = (frame['done'] as num?)?.toInt() ?? 0;
      if (done > 0) setState(() => _done = done);
      // v3.4.6.g — THE LIVE STEP-BY-STEP FEED: the DONE frame lands as
      // "✓ #47 PASS (120 ms)" / "✗ #47 FAIL (8 ms)" the instant the probe
      // finishes, replacing its own "▶ Checking…" line in place.
      final outcome = frame['outcome'];
      if (outcome is Map) {
        final id = (outcome['id'] as num?)?.toInt() ?? 0;
        final pass = outcome['pass'] == true;
        final ms = (outcome['ms'] as num?)?.toInt() ?? 0;
        _upsertFeed(id,
            '${pass ? '✓' : '✗'} #$id ${pass ? 'PASS' : 'FAIL'} ($ms ms)',
            done: true,
            name: ((outcome['name'] ?? '') as String));
      }
    });
    // v3.4.6.g — the START frames: "▶ Checking #34 Security hardening flags…"
    _startSub = AnantPlay.instance.deepStartEvents().listen((frame) {
      if (!mounted || _running != true) return;
      final id = (frame['id'] as num?)?.toInt() ?? 0;
      final name = (frame['name'] ?? '') as String;
      _upsertFeed(id, '▶ Checking #$id $name…', done: false, name: name);
    });
  }

  // ── v3.4.6.g — THE LIVE STEP-BY-STEP TICKER STATE ────────────────────────
  // One line per probe: "▶ Checking #34 Security hardening flags…" the instant
  // it starts, flipped in place to "✓ #34 PASS (4 ms)" / "✗ #47 FAIL (8 ms)"
  // the instant it lands. Auto-scrolls so the user watches every single
  // feature being verified live under the progress bar.
  final List<int> _feedIds = <int>[];
  final List<String> _feedLines = <String>[];
  /// v3.4.6.j — the per-probe NAME ledger (index-aligned with [_feedIds]):
  /// the Mornye status row shows the probe's own title on the done row too,
  /// even though its Checking line was replaced in place.
  final List<String> _feedNames = <String>[];
  final ScrollController _feedScroll = ScrollController();
  StreamSubscription<Map<dynamic, dynamic>>? _startSub;

  void _upsertFeed(int id, String line, {required bool done, String name = ''}) {
    if (!mounted) return;
    setState(() {
      final i = _feedIds.indexOf(id);
      if (i >= 0) {
        _feedLines[i] = line;
        // v3.4.6.j — the done row keeps its probe NAME: the Checking line it
        // was looked up from is REPLACED in place by the done line, so the
        // name rides its own ledger (never a post-hoc regex over the lines).
        if (name.isNotEmpty) _feedNames[i] = name;
      } else {
        _feedIds.add(id);
        _feedLines.add(line);
        _feedNames.add(name);
      }
      // cap the ledger (the run is bounded; keep the newest 128 lines).
      if (_feedLines.length > 128) {
        _feedIds.removeRange(0, _feedIds.length - 128);
        _feedLines.removeRange(0, _feedLines.length - 128);
        _feedNames.removeRange(0, _feedNames.length - 128);
      }
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_feedScroll.hasClients) return;
      _feedScroll.animateTo(
        _feedScroll.position.maxScrollExtent,
        duration: const Duration(milliseconds: 240),
        curve: Curves.easeOutCubic,
      );
    });
  }

  /// v3.4.6.f — THE TWO-LAYER COMPLETION: run the 8 live Dart audits (the
  /// honest Flutter-UI layer), merge them with the native outcomes, compose
  /// the honest report (EVERY FAIL ✗ at the very top), and auto-copy it.
  Future<void> _runDartLayerAndFinish(List<DeepOutcome> nativeOutcomes) async {
    // The native layer is COMPLETE: render its domain cards + auto-copy the
    // report IMMEDIATELY (the native verdict already stands and must never be
    // gated behind the Dart round-trips), THEN run the 8 live Dart audits
    // (Domain H, ids 79–92) and merge them in as they land.
    // v3.4.6.i — ONE TOAST AT THE VERY END (Law #2): the intermediate
    // auto-copy (the native-layer landing) is SILENT — no mid-run
    // `8/10`/`21/24`-style snackbar; the ONE completion banner fires only
    // after the FULL two-layer run lands.
    if (!mounted) return;
    setState(() {
      _results = nativeOutcomes;
      _ranOnce = true;
    });
    _autoCopyReport(nativeOutcomes, const [], true);
    List<DeepOutcome> dartLayer = const [];
    try {
      final scope = AppState.of(context).scope;
      // v3.4.6.g — the Dart layer streams into the SAME live step-by-step feed
      // as the native probes, so the ticker covers all 92 unique sequential ids
      // (▶ Checking #79 … → ✓ #79 PASS) and the counter climbs 78 → 92. Zero
      // duplicate ids, zero overwritten rows.
      dartLayer = await DartLayerAuditor.run(
        scope,
        onStart: (id, name) {
          if (!mounted) return;
          _upsertFeed(id, '▶ Checking #$id $name…', done: false);
        },
        onDone: (o) {
          if (!mounted) return;
          setState(() => _done = o.id);
          _upsertFeed(o.id,
              '${o.pass ? '✓' : '✗'} #${o.id} ${o.pass ? 'PASS' : 'FAIL'} (${o.ms.round()} ms)',
              done: true);
        },
      );
    } catch (_) {
      // The Dart layer itself failed to run — an honest empty ledger (the
      // native verdict still stands; the report says the layer threw).
    }
    if (!mounted) return;
    setState(() {
      _results = [...nativeOutcomes, ...dartLayer];
      _running = false;
    });
    // Refresh the clipboard with the FULL two-layer report (every FAIL ✗ at the
    // top) once the Dart layer lands.
    _autoCopyReport(nativeOutcomes, dartLayer);
  }

  /// The AUTOMATIC clipboard copy the moment the final probe lands: the full
  /// TWO-LAYER structured report (the Java side's scrubbed report + the Dart
  /// layer ledger + the merged honest verdict with every FAIL at the top)
  /// goes to the phone's clipboard through BOTH the native ClipboardManager
  /// (anantplay/cast.copyToClipboard) AND Flutter's Clipboard.setData, then
  /// the confirmation banner shows the truthful pass/fail counts.
  Future<void> _autoCopyReport(List<DeepOutcome> nativeOutcomes,
      [List<DeepOutcome> dartOutcomes = const [], bool silent = false]) async {
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
    // v3.4.6.i — the ONE completion banner: shown ONLY at the very end of
    // the FULL run (the intermediate native-layer landing passes silent).
    // v3.4.6.j — the completion banner rides the TOP floating glass pill
    // (the SnackBar is dead — Law #2). The honest PASS/FAIL counts stay.
    if (mounted && !silent) {
      showMornyeGlassNotification(
        context,
        message:
            '$passed/${all.length} PASS · $failed FAIL — honest report '
            'auto-copied ✓${failed > 0 ? ' (see every FAIL at the top)' : ''}',
        error: failed > 0,
      );
    }
  }

  @override
  void dispose() {
    _probeSub?.cancel();
    _progressSub?.cancel();
    _startSub?.cancel();
    _feedScroll.dispose();
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
      _feedIds.clear();
      _feedLines.clear();   // v3.4.6.g — the ticker restarts with the run
      _feedNames.clear();   // v3.4.6.j — the name ledger restarts too
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
      showMornyeGlassNotification(context, message: 'Report copied');
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
      showMornyeGlassNotification(context, message: 'Sharing the report…');
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
          StaggeredListItem(
            index: _copied && !_running && total > 0 ? 1 : 0,
            child: _ActionCard(
              icon: mornyeIconFor(Icons.health_and_safety_rounded),
              title: _running
                  ? 'Running 92 deep feature probes… $_done/92'
                  : _ranOnce || total > 0
                      ? 'Run full app troubleshoot again'
                      : 'Run full app troubleshoot',
              subtitle: _running
                  ? 'Live audit of every feature — settings are restored after.'
                  : 'Every feature · 78 native + 14 Dart-layer probes · 10 domains · honest results.',
              onTap: _running ? null : _run,
            ),
          ),
          // v3.4.6.g — THE LIVE PROGRESS BAR + STEP-BY-STEP TICKER: while the
          // run is in flight the user sees the bar AND a live auto-scrolling
          // feed of every probe as it executes (▶ Checking #34 … → ✓ #34 PASS).
          if (_running || _feedLines.isNotEmpty)
            StaggeredListItem(
              index: _copied && !_running && total > 0 ? 2 : 1,
              child: Padding(
                padding: const EdgeInsets.only(top: 10, bottom: 6),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (_running)
                      ClipRRect(
                        borderRadius: BorderRadius.circular(8),
                        child: LinearProgressIndicator(
                          minHeight: 6,
                          // v3.4.6.i — the bar now divides by the SAME live
                          // total as the counter (the v3.4.6.h bar divided by
                          // 72 while the counter read /78 — the bar visibly
                          // lagged behind 100% at the end).
                          value: (_done / 92).clamp(0.0, 1.0),
                          backgroundColor: scheme.surfaceContainerHigh,
                        ),
                      ),
                    if (_running) const SizedBox(height: 4),
                    Text(
                      _running ? 'Live — every probe as it executes:' : 'Last run:',
                      style: TextStyle(
                          color: scheme.onSurfaceVariant, fontSize: 11),
                    ),
                    const SizedBox(height: 6),
                    Container(
                      constraints: const BoxConstraints(maxHeight: 210),
                      decoration: BoxDecoration(
                        color: scheme.surfaceContainerHigh.withValues(alpha: 0.55),
                        borderRadius: BorderRadius.circular(14),
                      ),
                      child: ListView.builder(
                        controller: _feedScroll,
                        shrinkWrap: true,
                        padding: const EdgeInsets.symmetric(
                            horizontal: 8, vertical: 8),
                        itemCount: _feedLines.length,
                        itemBuilder: (context, i) {
                          final line = _feedLines[i];
                          // v3.4.6.j — the row carries the probe's OWN name
                          // from the ledger (the Checking line was replaced
                          // in place when it flipped, so a regex lookup over
                          // the remaining lines would find nothing).
                          final name =
                              i < _feedNames.length ? _feedNames[i] : '';
                          return _ProbeStatusRow(line: line, name: name);
                        },
                      ),
                    ),
                  ],
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
                  child: Icon(mornyeIconFor(icon), color: scheme.primary, size: 20),
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

/// v3.4.6.j — ONE LIVE FEED ROW (video_2 02:20–02:45): the raw
/// `✓ #84 PASS (0 ms)` ASCII clutter is replaced on screen by a sleek Mornye
/// status row — a crisp circular green/red VECTOR badge, the probe number +
/// its running title, and a compact pill with the duration. The structured
/// `✓/✗` text itself still feeds the auto-copied clipboard report (Law #5).
class _ProbeStatusRow extends StatelessWidget {
  const _ProbeStatusRow({required this.line, this.name = ''});

  final String line;

  /// v3.4.6.j — the probe's own title (from its Checking line).
  final String name;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final pass = line.startsWith('✓');
    final fail = line.startsWith('✗');
    final checking = line.startsWith('▶');
    final id = RegExp(r'#(\d+)').firstMatch(line)?.group(1) ?? '';
    final ms = RegExp(r'\((\d+) ms\)').firstMatch(line)?.group(1) ?? '';
    final title = name.isNotEmpty ? name : (checking ? line : '');
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        children: [
          // the crisp circular vector badge (never a raw ASCII glyph)
          Container(
            width: 18,
            height: 18,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: pass
                  ? const Color(0xFF63D668).withValues(alpha: 0.18)
                  : (fail
                      ? scheme.error.withValues(alpha: 0.18)
                      : scheme.primary.withValues(alpha: 0.12)),
            ),            child: Icon(
              pass
                  ? Icons.check_rounded
                  : (fail ? Icons.close_rounded : Icons.autorenew_rounded),
              size: 12,
              color: pass
                  ? const Color(0xFF63D668)
                  : (fail ? scheme.error : scheme.primary),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              title.isEmpty
                  ? 'Probe #$id'
                  : (checking ? 'Checking #$id — $title' : ' #$id · $title'),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: scheme.onSurface,
                fontSize: 11.5,
                fontWeight: fail ? FontWeight.w700 : FontWeight.w500,
              ),
            ),
          ),
          if (ms.isNotEmpty)
            Container(
              padding:
                  const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
              decoration: BoxDecoration(
                color: scheme.onSurface.withValues(alpha: 0.06),
                borderRadius: BorderRadius.circular(999),
              ),
              child: Text('$ms ms',
                  style: TextStyle(
                      color: scheme.onSurfaceVariant,
                      fontSize: 9.5,
                      fontWeight: FontWeight.w600,
                      fontFeatures: const [FontFeature.tabularFigures()])),
            ),
        ],
      ),
    );
  }
}

/// One domain's collapsible card: header (code · title · n/n PASS) + the
/// probe rows (the Mornye vector badges + ms + the detail line).
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
