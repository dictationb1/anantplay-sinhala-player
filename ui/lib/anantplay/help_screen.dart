// AnantPlay Sinhala Player v3.4.6 — the Help & Troubleshoot hub: 1-tap full
// probe run, the report copy/share, manual update check, and the wizard
// re-run.
import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';

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

  @override
  void initState() {
    super.initState();
    _loadLast();
  }

  Future<void> _loadLast() async {
    final last = await AnantPlay.instance.lastProbeResults();
    if (mounted) setState(() => _results = last);
  }

  Future<void> _run() async {
    setState(() => _running = true);
    await AnantPlay.instance.runProbe();
    if (mounted) {
      setState(() => _running = false);
      _loadLast();
    }
  }

  @override
  Widget build(BuildContext context) {
    final passed = _results.where((o) => o.pass).length;
    final total = _results.length;
    return Scaffold(
      backgroundColor: const Color(0xFF0F2E30),
      appBar: AppBar(
        title: const Text('Troubleshoot'),
        backgroundColor: const Color(0x330F2E30),
        surfaceTintColor: Colors.transparent,
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
        children: [
          _ActionCard(
            icon: Icons.health_and_safety_rounded,
            title: _running ? 'Running the 10-point probe…' : 'Run full app troubleshoot',
            subtitle: 'Receiver · cast · subtitles · updater — all 10 probes.',
            onTap: _running ? null : _run,
          ),
          _ActionCard(
            icon: Icons.copy_rounded,
            title: 'Copy privacy-safe report',
            subtitle: 'Zero personal data — IMDb IDs and milliseconds only.',
            onTap: () => _copy(),
          ),
          _ActionCard(
            icon: Icons.update_rounded,
            title: 'Check for update',
            subtitle: 'Direct release check — no API calls.',
            onTap: () => AnantPlay.instance.checkForUpdate(),
          ),
          _ActionCard(
            icon: Icons.restart_alt_rounded,
            title: 'Run setup wizard',
            subtitle: 'Replay the first-launch checklist.',
            onTap: () => AppState.of(context).scope.putBool('onboardingDone', false),
          ),
          if (_results.isNotEmpty) ...[
            const SizedBox(height: 10),
            Text(_running ? '…' : '$passed/$total PASS',
                textAlign: TextAlign.center,
                style: TextStyle(
                    color: passed == total ? const Color(0xFF63D668) : const Color(0xFFFFB340),
                    fontSize: 15,
                    fontWeight: FontWeight.w700)),
            const SizedBox(height: 6),
            for (final o in _results)
              _ProbeRow(outcome: o),
          ],
        ],
      ),
    );
  }

  Future<void> _copy() async {
    final report = _results
        .map((o) => '${o.pass ? "PASS" : "FAIL"} #${o.id} ${o.name} (${o.ms.toInt()}ms)')
        .join('\n');
    await Clipboard.setData(ClipboardData(text: report));
    if (mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Report copied')));
    }
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
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Material(
        color: const Color(0x0FFFFFFF),
        borderRadius: BorderRadius.circular(18),
        child: InkWell(
          borderRadius: BorderRadius.circular(18),
          onTap: onTap,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(18),
              border: Border.all(color: const Color(0x1FFFFFFF)),
            ),
            child: Row(
              children: [
                Container(
                  width: 38,
                  height: 38,
                  decoration: BoxDecoration(
                    color: const Color(0x1F1E4E4F),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Icon(icon, color: const Color(0xFF7FCDD0), size: 20),
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(title,
                          style: const TextStyle(
                              color: Color(0xFFF2F7F7), fontSize: 14, fontWeight: FontWeight.w600)),
                      const SizedBox(height: 3),
                      Text(subtitle,
                          style: const TextStyle(color: Color(0x80F2F7F7), fontSize: 11.5)),
                    ],
                  ),
                ),
                const Icon(Icons.chevron_right_rounded, color: Color(0x66F2F7F7)),
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
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Row(
        children: [
          Icon(
            outcome.pass ? Icons.check_circle_rounded : Icons.cancel_rounded,
            color: outcome.pass ? const Color(0xFF63D668) : const Color(0xFFFF6482),
            size: 17,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text('#${outcome.id} ${outcome.name}',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(color: Color(0xCCF2F7F7), fontSize: 13)),
          ),
          Text('${outcome.ms.toInt()} ms',
              style: const TextStyle(color: Color(0x59F2F7F7), fontSize: 11)),
        ],
      ),
    );
  }
}
