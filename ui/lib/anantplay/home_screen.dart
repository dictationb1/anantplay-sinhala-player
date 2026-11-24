// AnantPlay Sinhala Player v3.4.6.a — the Home shell: Mornye collapsing large
// title, receiver hero glass card (pulsing dot + Start/Stop), the instant
// in-app auto-update glass banner (live % + 1-tap Update Now), quick-action
// tiles, and Continue Watching (WatchHistory with progress + resume).
// v3.4.6.a — FULL MORNYE PARITY: the entry reveal is now Mornye's
// StaggeredListItem (the upstream Home exact effect: each section springs up
// after its own delay on the spatial spring, opacity on the effects spring,
// reduced-motion and scrolled-page aware), matching the upstream reveal.
import 'package:material_ui/material_ui.dart';

import 'package:anantplay_ui/mornye_ui/widgets/animation_utils.dart';

import 'app_state.dart';
import 'bridge.dart';
import 'settings_mirror.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  @override
  Widget build(BuildContext context) {
    final state = AppState.of(context);
    return Scaffold(
      backgroundColor: const Color(0xFF0F2E30),
      body: SafeArea(
        bottom: false,
        child: CustomScrollView(
          slivers: [
            SliverAppBar.large(
              title: const Text('AnantPlay Player'),
              backgroundColor: const Color(0x330F2E30),
              surfaceTintColor: Colors.transparent,
              expandedHeight: 128,
              pinned: true,
            ),
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    // v3.4.6.a — the Mornye entry reveal (upstream Home's
                    // StaggeredListItem): 0.2 s spatial spring per section,
                    // 40 ms stagger. animate:false is never needed here — the
                    // widget itself skips on scrolled/reduced-motion.
                    StaggeredListItem(
                      index: 0,
                      duration: const Duration(milliseconds: 250),
                      staggerDelay: const Duration(milliseconds: 40),
                      child: _UpdateBanner(event: state.updaterEvent),
                    ),
                    const SizedBox(height: 16),
                    StaggeredListItem(
                      index: 1,
                      child: _ReceiverHero(status: state.castStatus),
                    ),
                    const SizedBox(height: 16),
                    StaggeredListItem(
                      index: 2,
                      child: _QuickStartCard(settings: state.settings),
                    ),
                    StaggeredListItem(index: 3, child: const _QuickActions()),
                    const SizedBox(height: 20),
                    StaggeredListItem(
                      index: 4,
                      child: _ContinueWatching(history: state.history),
                    ),
                    const SizedBox(height: 28),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ── the receiver hero glass card ─────────────────────────────────────────────

class _ReceiverHero extends StatelessWidget {
  const _ReceiverHero({required this.status});

  final CastStatus status;

  @override
  Widget build(BuildContext context) {
    final up = status.up;
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: const Color(0x14FFFFFF),
        borderRadius: BorderRadius.circular(24),
        border: Border.all(color: const Color(0x2EFFFFFF)),
      ),
      child: Column(
        children: [
          Row(
            children: [
              Container(
                width: 9,
                height: 9,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: up ? const Color(0xFF3E8E8F) : const Color(0x66F2F7F7),
                  boxShadow: up
                      ? [const BoxShadow(color: Color(0x553E8E8F), blurRadius: 8)]
                      : null,
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  up ? 'Receiver ready' : 'Receiver stopped',
                  style: const TextStyle(
                    color: Color(0xFFF2F7F7),
                    fontSize: 16,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
              Text(
                up && status.port > 0 ? ':${status.port}' : '',
                style: const TextStyle(color: Color(0x99F2F7F7), fontSize: 13),
              ),
            ],
          ),
          const SizedBox(height: 6),
          Text(
            up
                ? (status.mdns ? 'Announced on the LAN — cast from AnantPlay.' : 'Cast server up (announcing…).')
                : 'Start it to accept casts from the official app.',
            style: const TextStyle(color: Color(0x99F2F7F7), fontSize: 12.5, height: 1.4),
          ),
          const SizedBox(height: 14),
          Row(
            children: [
              Expanded(
                child: FilledButton.icon(
                  onPressed: () => up
                      ? AnantPlay.instance.stopReceiver()
                      : AnantPlay.instance.armReceiver(),
                  style: FilledButton.styleFrom(
                    backgroundColor: up ? const Color(0x1FFFFFFF) : const Color(0xFF1E4E4F),
                    foregroundColor: const Color(0xFFF2F7F7),
                    padding: const EdgeInsets.symmetric(vertical: 12),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(13)),
                  ),
                  icon: Icon(up ? Icons.stop_rounded : Icons.play_arrow_rounded, size: 18),
                  label: Text(up ? 'Stop' : 'Start',
                      style: const TextStyle(fontWeight: FontWeight.w600)),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

// ── the auto-update glass banner ────────────────────────────────────────────

class _UpdateBanner extends StatelessWidget {
  const _UpdateBanner({required this.event});

  final UpdaterEvent? event;

  @override
  Widget build(BuildContext context) {
    final e = event;
    if (e == null) return const SizedBox.shrink();
    final isProgress = e.type == 'progress';
    final isPending = e.type == 'pending' || e.type == 'available';
    if (!isProgress && !isPending) return const SizedBox.shrink();
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: const Color(0x1A1E4E4F),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: const Color(0x2E3E8E8F)),
      ),
      child: Row(
        children: [
          const Icon(Icons.system_update_rounded, color: Color(0xFF3E8E8F), size: 20),
          const SizedBox(width: 12),
          Expanded(
            child: isProgress
                ? Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('Downloading v${e.value} — ${e.value}%…',
                          style: const TextStyle(color: Color(0xFFF2F7F7), fontSize: 13.5)),
                      const SizedBox(height: 6),
                      ClipRRect(
                        borderRadius: BorderRadius.circular(3),
                        child: LinearProgressIndicator(
                          value: (int.tryParse(e.value) ?? 0) / 100.0,
                          minHeight: 4,
                          backgroundColor: const Color(0x1FFFFFFF),
                          color: const Color(0xFF3E8E8F),
                        ),
                      ),
                    ],
                  )
                : Text(
                    e.type == 'pending'
                        ? 'Update ready: v${e.value} — tap to install'
                        : 'Update available: v${e.value}',
                    style: const TextStyle(color: Color(0xFFF2F7F7), fontSize: 13.5),
                  ),
          ),
          if (!isProgress)
            TextButton(
              onPressed: () => AnantPlay.instance.installPendingUpdate(),
              child: const Text('Update now', style: TextStyle(color: Color(0xFF7FCDD0))),
            ),
        ],
      ),
    );
  }
}

// ── the one-time quick-start card (K_QUICKSTART_DONE) ───────────────────────

/// Mornye Glass, one glance: what to do first, and how to get back to the
/// wizard later. It retires itself the moment the user says it has been read —
/// the pref is a normal backed-up key, so it survives export/restore.
class _QuickStartCard extends StatelessWidget {
  const _QuickStartCard({required this.settings});

  final SettingsMirror settings;

  @override
  Widget build(BuildContext context) {
    if (settings.quickstartDone) return const SizedBox(height: 16);
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: const Color(0x1AFFFFFF),
          borderRadius: BorderRadius.circular(20),
          border: Border.all(color: const Color(0x26FFFFFF)),
        ),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 8, 12),
          child: Row(
            children: [
              Icon(Icons.tips_and_updates_rounded,
                  size: 22, color: theme.colorScheme.primary),
              const SizedBox(width: 12),
              const Expanded(
                child: Text(
                  'First time? Cast from AnantPlay, or open the Browser tab to '
                  'watch with Sinhala subtitles.',
                  style: TextStyle(fontSize: 13, height: 1.35),
                ),
              ),
              TextButton(
                onPressed: () => AppState.of(context).scope.dismissQuickstart(),
                child: const Text('Got it'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ── quick actions ───────────────────────────────────────────────────────────

class _QuickActions extends StatelessWidget {
  const _QuickActions();

  @override
  Widget build(BuildContext context) {
    return GridView.count(
      crossAxisCount: 2,
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      mainAxisSpacing: 12,
      crossAxisSpacing: 12,
      childAspectRatio: 1.62,
      children: const [
        _ActionTile(
          icon: Icons.cast_rounded,
          title: 'AnantPlay',
          subtitle: 'Open the official app',
          action: _openOfficial,
        ),
        _ActionTile(
          icon: Icons.public_rounded,
          title: 'Browser',
          subtitle: 'Cinema WebView browser',
          action: _openBrowser,
        ),
        _ActionTile(
          icon: Icons.link_rounded,
          title: 'Play URL',
          subtitle: 'Stream any link here',
          action: _playUrl,
        ),
        _ActionTile(
          icon: Icons.science_rounded,
          title: 'Test',
          subtitle: 'Try the demo stream',
          action: _testPlayer,
        ),
      ],
    );
  }

  static Future<void> _openOfficial() => AnantPlay.instance.openOfficialApp();
  static Future<void> _openBrowser() => AnantPlay.instance.openWebTab('');
  static Future<void> _playUrl() async {
    await AnantPlay.instance.playUrl('');
  }

  static Future<void> _testPlayer() => AnantPlay.instance.runTestPlayer();
}

class _ActionTile extends StatelessWidget {
  const _ActionTile({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.action,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final Future<void> Function() action;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: const Color(0x0FFFFFFF),
      borderRadius: BorderRadius.circular(20),
      child: InkWell(
        borderRadius: BorderRadius.circular(20),
        onTap: () => action(),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(20),
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
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Text(title,
                        style: const TextStyle(
                            color: Color(0xFFF2F7F7), fontSize: 14, fontWeight: FontWeight.w600)),
                    const SizedBox(height: 2),
                    Text(subtitle,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(color: Color(0x80F2F7F7), fontSize: 11.5)),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ── continue watching ────────────────────────────────────────────────────────

class _ContinueWatching extends StatelessWidget {
  const _ContinueWatching({required this.history});

  final List<HistoryItem> history;

  @override
  Widget build(BuildContext context) {
    if (history.isEmpty) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 18),
        child: Center(
          child: Text('Nothing watched yet — cast or open a link to begin.',
              style: TextStyle(color: Color(0x80F2F7F7), fontSize: 13)),
        ),
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text('Continue watching',
            style: TextStyle(
                color: Color(0xFFF2F7F7), fontSize: 17, fontWeight: FontWeight.w700)),
        const SizedBox(height: 10),
        for (final it in history.take(6))
          _HistoryRow(item: it),
      ],
    );
  }
}

class _HistoryRow extends StatelessWidget {
  const _HistoryRow({required this.item});

  final HistoryItem item;

  @override
  Widget build(BuildContext context) {
    final resumable = item.resumable;
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Material(
        color: const Color(0x0AFFFFFF),
        borderRadius: BorderRadius.circular(14),
        child: InkWell(
          borderRadius: BorderRadius.circular(14),
          onTap: () => AnantPlay.instance.playUrl(item.url, title: item.title),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(item.title.isEmpty ? item.url : item.title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                              color: Color(0xFFF2F7F7), fontSize: 13.5, fontWeight: FontWeight.w500)),
                      const SizedBox(height: 6),
                      ClipRRect(
                        borderRadius: BorderRadius.circular(3),
                        child: LinearProgressIndicator(
                          value: item.percent / 100.0,
                          minHeight: 3,
                          backgroundColor: const Color(0x1FFFFFFF),
                          color: const Color(0xFF3E8E8F),
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 10),
                if (resumable)
                  const Icon(Icons.play_arrow_rounded, color: Color(0xFF7FCDD0), size: 20)
                else
                  const Icon(Icons.replay_rounded, color: Color(0x66F2F7F7), size: 16),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
