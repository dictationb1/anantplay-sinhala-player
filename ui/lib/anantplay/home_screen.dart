// AnantPlay Sinhala Player v3.4.6.c — the Home shell: Mornye collapsing large
// title with the top-right Settings + Check-Update glass buttons, the instant
// in-app auto-update glass card (live 0→100% + 1-tap Update Now), the receiver
// hero glass card (Start/Stop flips status + the native ReceiverService
// immediately), 4 REAL quick-action tiles (every one does something visible),
// and Continue Watching (progress, exact resume timestamp, 1-tap resume,
// delete row, clear all).
//
// v3.4.6.c — THE "FAKE UI" FIX, per tile:
//   · AnantPlay → launches the official app OR the in-app installer sheet
//   · Browser   → switches the SHELL to the live Browser tab (no PlayActivity)
//   · Play URL  → the Mornye glass URL sheet (Paste + Play) → PlayerScreen
//   · Test      → the MORNYE CINEMA PLAYER (player_screen.dart over the real
//                 Media3TextureBridge) with the Mux HLS test + Sinhala demo
import 'dart:async' show StreamSubscription;

import 'package:flutter/services.dart' show Clipboard;
import 'package:material_ui/material_ui.dart';

import 'package:anantplay_ui/mornye_ui/widgets/animation_utils.dart';

import 'action_feedback.dart';
import 'app_state.dart';
import 'bridge.dart';
import 'package:anantplay_ui/mornye_ui/widgets/mornye_glass_notification.dart';
import 'package:anantplay_ui/main.dart';
import 'settings_mirror.dart';
import 'onboarding_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  @override
  Widget build(BuildContext context) {
    final state = AppState.of(context);
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      backgroundColor: scheme.surface,
      body: SafeArea(
        bottom: false,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // v3.4.6.i — THE FIXED MORNYE LARGE HEADER (the clipped-title kill
            // caught on video_3/video_4): a plain pinned header row above the
            // scroll view — 28sp / w700 / −0.3 tracking, NEVER vertically
            // clipped under the status bar (SafeArea wraps it directly; the
            // old SliverAppBar.large could half-cut itself on edge-to-edge
            // insets). The actions stay 1-tap reachable beside the title.
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 6, 12, 2),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      'AnantPlay Player',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: scheme.onSurface,
                        fontSize: 28,
                        fontWeight: FontWeight.w700,
                        letterSpacing: -0.3,
                      ),
                    ),
                  ),
                  // v3.4.6.c — complaint #1/#2 fix: Settings is ALWAYS 1-tap
                  // reachable from Home's own top bar, in ADDITION to the tab.
                  IconButton(
                    tooltip: 'Check for update',
                    icon: const Icon(Icons.system_update_rounded),
                    onPressed: () => checkForUpdateWithFeedback(context),
                  ),
                  IconButton(
                    tooltip: 'Settings',
                    icon: const Icon(Icons.settings_rounded),
                    onPressed: () =>
                        ShellRootState.of(context).selectTab(2),
                  ),
                ],
              ),
            ),
            Expanded(
              child: CustomScrollView(
                slivers: [
                  SliverToBoxAdapter(
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          // v3.4.6.b — the Mornye entry reveal (upstream Home's
                          // StaggeredListItem): 0.2 s spatial spring per section,
                          // 40 ms stagger. animate:false is never needed here — the
                          // widget itself skips on scrolled/reduced-motion.
                          StaggeredListItem(
                            index: 0,
                            duration: const Duration(milliseconds: 250),
                            staggerDelay: const Duration(milliseconds: 40),
                            child: _UpdateCard(event: state.updaterEvent),
                          ),
                          // v3.4.6.i — the OFFICIAL ANANTPLAY UPDATE PILL: when
                          // the installed me.champdev.weblock is behind the latest
                          // github.com/AnantPlay/android release, a sleek 1-tap
                          // update pill sits above the receiver hero.
                          StaggeredListItem(
                            index: 1,
                            child: _OfficialAnantPlayUpdatePill(),
                          ),
                          const SizedBox(height: 16),
                          StaggeredListItem(
                            index: 2,
                            child: _ReceiverHero(status: state.castStatus),
                          ),
                          const SizedBox(height: 16),
                          StaggeredListItem(
                            index: 3,
                            child: _QuickStartCard(settings: state.settings),
                          ),
                          StaggeredListItem(index: 4, child: const _QuickActions()),
                          const SizedBox(height: 20),
                          StaggeredListItem(
                            index: 5,
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
    final scheme = Theme.of(context).colorScheme;
    final up = status.up;
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: scheme.surfaceContainerHigh.withValues(alpha: 0.5),
        borderRadius: BorderRadius.circular(24),
        border: Border.all(
            color: up
                ? scheme.primary.withValues(alpha: 0.55)
                : scheme.outlineVariant.withValues(alpha: 0.4),
            width: up ? 1.4 : 1),
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
                  color: up ? scheme.primary : scheme.outline,
                  boxShadow: up
                      ? [BoxShadow(
                          color: scheme.primary.withValues(alpha: 0.5),
                          blurRadius: 8)]
                      : null,
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  // v3.4.6.c — the user-visible status flip the hero owes:
                  // the label states exactly what the receiver is doing.
                  up ? 'Active · Ready for Cast' : 'Stopped',
                  style: TextStyle(
                    color: scheme.onSurface,
                    fontSize: 16,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
              Text(
                up && status.port > 0 ? ':${status.port}' : '',
                style: TextStyle(
                    color: scheme.onSurfaceVariant, fontSize: 13),
              ),
            ],
          ),
          const SizedBox(height: 6),
          Text(
            up
                ? (status.mdns
                    ? 'Announced on the LAN — cast from AnantPlay.'
                    : 'Cast server up (announcing…).')
                : 'Start it to accept casts from the official app.',
            style: TextStyle(
                color: scheme.onSurfaceVariant, fontSize: 12.5, height: 1.4),
          ),
          const SizedBox(height: 14),
          Row(
            children: [
              Expanded(
                child: FilledButton.icon(
                  // v3.4.6.c — REAL toggle: arm/stop the native ReceiverService
                  // and flip the card (the status stream pushes the change).
                  onPressed: () => up
                      ? AnantPlay.instance.stopReceiver()
                      : AnantPlay.instance.armReceiver(),
                  style: FilledButton.styleFrom(
                    backgroundColor: up
                        ? scheme.surfaceContainerHighest
                        : scheme.primary,
                    foregroundColor:
                        up ? scheme.onSurface : scheme.onPrimary,
                    padding: const EdgeInsets.symmetric(vertical: 12),
                    shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(13)),
                  ),
                  icon: Icon(
                      up ? Icons.stop_rounded : Icons.play_arrow_rounded,
                      size: 18),
                  label: Text(
                      up
                          ? 'Stop Receiver'
                          : 'Start Receiver',
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

// ── the auto-update glass card ───────────────────────────────────────────────

class _UpdateCard extends StatelessWidget {
  const _UpdateCard({required this.event});

  final UpdaterEvent? event;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final e = event;
    if (e == null) return const SizedBox.shrink();
    final isProgress = e.type == 'progress';
    final isPending = e.type == 'pending' || e.type == 'available';
    if (!isProgress && !isPending) return const SizedBox.shrink();
    final pct = int.tryParse(e.value) ?? 0;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: scheme.primaryContainer.withValues(alpha: 0.35),
        borderRadius: BorderRadius.circular(16),
        border:
            Border.all(color: scheme.primary.withValues(alpha: 0.4)),
      ),
      child: Row(
        children: [
          Icon(Icons.system_update_rounded,
              color: scheme.primary, size: 20),
          const SizedBox(width: 12),
          Expanded(
            child: isProgress
                ? Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('Downloading update — $pct%…',
                          style: TextStyle(
                              color: scheme.onSurface, fontSize: 13.5)),
                      const SizedBox(height: 6),
                      ClipRRect(
                        borderRadius: BorderRadius.circular(3),
                        child: LinearProgressIndicator(
                          // v3.4.6.c — the live 0→100% bar (the old banner
                          // printed the version string into the percent).
                          value: pct.clamp(0, 100) / 100.0,
                          minHeight: 4,
                          backgroundColor:
                              scheme.onSurface.withValues(alpha: 0.1),
                          color: scheme.primary,
                        ),
                      ),
                    ],
                  )
                : Text(
                    e.type == 'pending'
                        ? 'Update ready: v${e.value} — tap to install'
                        : 'Update available: v${e.value}',
                    style: TextStyle(
                        color: scheme.onSurface, fontSize: 13.5),
                  ),
          ),
          if (!isProgress)
            TextButton(
              onPressed: () => AnantPlay.instance.installPendingUpdate(),
              child: const Text('Update Now'),
            ),
        ],
      ),
    );
  }
}

// ── the one-time quick-start card (K_QUICKSTART_DONE) ───────────────────────

/// Mornye Glass, one glance: what to do first, and how to get back to the
/// wizard later. v3.4.6.g — THE REAL-PHONE FIX: on v3.4.6.f the `Got it` tap
/// never visibly dismissed the banner (no spring collapse, no persistence the
/// user could trust). The card now: (a) dismisses through the dedicated
/// `homeTipDismissed` pref (honoring legacy `quickstartDone` holders so nobody
/// who already dismissed it sees it again), (b) collapses with a real spring
/// animation (AnimatedSize), and (c) carries a `Watch Tour` pill that opens
/// the revamped Interactive Visual Setup & Value Guide.
///
/// v3.4.6.i — THE DEAD `Got it` KILL (video_3 04:01, video_4 01:15): the tap
/// now flips an OPTIMISTIC in-memory `_tipDismissed` AND persists
/// `homeTipDismissed=true` through the raw-mirror putBool lane, so the very
/// first tap collapses the card — and with gatherSettings() now returning the
/// FULL prefs map, the flag SURVIVES the optimistic refresh (the v3.4.6.h
/// whitelist dropped `homeTipDismissed`, so the banner snapped back after
/// every mirror refresh). The button itself is an OPAQUE 44dp tap target.
class _QuickStartCard extends StatefulWidget {
  const _QuickStartCard({required this.settings});

  final SettingsMirror settings;

  @override
  State<_QuickStartCard> createState() => _QuickStartCardState();
}

class _QuickStartCardState extends State<_QuickStartCard> {
  /// v3.4.6.i — the optimistic one-tap collapse (set the frame the tap lands).
  bool _tipDismissed = false;

  bool get _dismissed =>
      _tipDismissed ||
      widget.settings.b('homeTipDismissed', false) ||
      widget.settings.quickstartDone;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    // v3.4.6.g — the SPRING COLLAPSE: the AnimatedSize stays mounted while its
    // child swaps card → shrink, so the banner visibly folds away (the
    // v3.4.6.f hard-cut never registered as a dismissal).
    return AnimatedSize(
      duration: const Duration(milliseconds: 340),
      curve: Curves.easeOutCubic,
      alignment: Alignment.topCenter,
      child: _dismissed
          ? const SizedBox(width: double.infinity)
          : Padding(
              padding: const EdgeInsets.only(bottom: 16),
              child: DecoratedBox(
                decoration: BoxDecoration(
                  color: theme.colorScheme.surfaceContainerHigh
                      .withValues(alpha: 0.4),
                  borderRadius: BorderRadius.circular(20),
                  border: Border.all(
                      color: theme.colorScheme.outlineVariant
                          .withValues(alpha: 0.4)),
                ),
                child: Padding(
                  // v3.4.6.g — COMPACT: the two pills ride on ONE line with
                  // the message (crisp 12sp, shrinkWrap tap targets) so the
                  // banner stays exactly as tall as the v3.4.6.f card and the
                  // quick-action tiles below remain in the tappable viewport.
                  padding: const EdgeInsets.fromLTRB(16, 10, 8, 10),
                  child: Row(
                    children: [
                      Icon(Icons.tips_and_updates_rounded,
                          size: 20, color: theme.colorScheme.primary),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Text(
                          'First time? Cast from AnantPlay or open the Browser '
                          'tab — Sinhala subtitles included.',
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                              fontSize: 12,
                              height: 1.3,
                              color: theme.colorScheme.onSurface),
                        ),
                      ),
                      // v3.4.6.g — the Watch Tour pill: opens the Interactive
                      // Visual Setup & Value Guide beside the dismissal.
                      TextButton(
                        style: TextButton.styleFrom(
                          padding: const EdgeInsets.symmetric(horizontal: 8),
                          minimumSize: const Size(64, 44),
                          tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                          textStyle: const TextStyle(fontSize: 12),
                        ),
                        onPressed: () => _openTour(context),
                        child: const Text('Watch Tour'),
                      ),
                      TextButton(
                        style: TextButton.styleFrom(
                          // v3.4.6.i — an OPAQUE 44dp tap target: the very
                          // first tap ALWAYS registers (the v3.4.6.h 32dp-high
                          // label could miss the finger on the video).
                          padding: const EdgeInsets.symmetric(horizontal: 10),
                          minimumSize: const Size(64, 44),
                          tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                          textStyle: const TextStyle(
                              fontSize: 12, fontWeight: FontWeight.w700),
                        ),
                        // v3.4.6.i — optimistic in-memory flip + the persistent
                        // write through the raw-mirror lane (probe G4 audits
                        // the round-trip; the whitelist used to drop it).
                        onPressed: () {
                          setState(() => _tipDismissed = true);
                          AppState.of(context)
                              .scope
                              .putBool('homeTipDismissed', true);
                        },
                        child: const Text('Got it'),
                      ),
                    ],
                  ),
                ),
              ),
            ),
    );
  }

  /// The tour entry: the revamped onboarding guide on top of the shell.
  static void _openTour(BuildContext context) {
    Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (_) => const OnboardingScreen(),
      fullscreenDialog: true,
    ));
  }
}

// ── v3.4.6.i — the OFFICIAL ANANTPLAY UPDATE PILL ──────────────────────────

/// Sleek Home pill: `AnantPlay Update Available (vX → vY) — Update Now`.
/// The facts come from the native officialUpdateFacts check (the installed
/// me.champdev.weblock versionName vs the latest AnantPlay/android release
/// tag — zero api.github.com calls, 10-minute async cache), and Update Now
/// rides the FIXED AnantPlayInstaller (real asset discovery + auto-resume).
class _OfficialAnantPlayUpdatePill extends StatefulWidget {
  const _OfficialAnantPlayUpdatePill();

  @override
  State<_OfficialAnantPlayUpdatePill> createState() =>
      _OfficialAnantPlayUpdatePillState();
}

class _OfficialAnantPlayUpdatePillState extends State<_OfficialAnantPlayUpdatePill> {
  OfficialAnantPlayUpdate? _facts;

  @override
  void initState() {
    super.initState();
    AnantPlay.instance.officialUpdateFacts().then((facts) {
      if (mounted) setState(() => _facts = facts);
    }).catchError((Object _) {});
  }

  @override
  Widget build(BuildContext context) {
    final facts = _facts;
    if (facts == null || !facts.updateAvailable) {
      return const SizedBox(width: double.infinity);
    }
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Material(
        color: theme.colorScheme.primaryContainer.withValues(alpha: 0.55),
        borderRadius: BorderRadius.circular(18),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: () => AnantPlay.instance.installOfficialAnantPlay(),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(14, 12, 10, 12),
            child: Row(
              children: [
                Icon(Icons.upgrade_rounded,
                    color: theme.colorScheme.primary, size: 22),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    'AnantPlay Update Available (${facts.installed} → ${facts.latest}) — Update Now',
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 12.5,
                      fontWeight: FontWeight.w600,
                      color: theme.colorScheme.onSurface,
                    ),
                  ),
                ),
                TextButton(
                  style: TextButton.styleFrom(
                    minimumSize: const Size(72, 44),
                    tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  ),
                  onPressed: () => AnantPlay.instance.installOfficialAnantPlay(),
                  child: const Text('Update Now'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

// ── quick actions ───────────────────────────────────────────────────────────

// ── v3.4.6.h — PERMANENT LAW #3: THE SEMANTIC ICON REGISTRY ──────────────────
// Every Home quick-action glyph, NAMED by its feature so the live H11 audit
// (and the widget tests) can assert each icon semantically matches its tile.
// The forbidden set (science, biotech, science_outlined…) can NEVER appear.
const IconData kTestPlayerIcon = Icons.play_circle_rounded;
const IconData kAnantPlayTileIcon = Icons.cast_rounded;
const IconData kBrowserTileIcon = Icons.public_rounded;
const IconData kPlayUrlTileIcon = Icons.link_rounded;

/// The icons the four Home quick-action tiles actually paint with (audit H11).
const List<IconData> kHomeQuickActionIcons = <IconData>[
  kAnantPlayTileIcon,
  kBrowserTileIcon,
  kPlayUrlTileIcon,
  kTestPlayerIcon,
];

/// The glyphs PERMANENT LAW #3 forbids anywhere in the app chrome (a flask is
/// not a movie; a beaker is not a player).
const List<String> kForbiddenIconNames = <String>[
  'science',
  'science_outlined',
  'biotech',
  'emoji_nature',
  'pest_control',
];

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
      children: [
        _ActionTile(
          icon: Icons.cast_rounded,
          title: 'AnantPlay',
          subtitle: 'Official app',  // v3.4.6.g — short crisp (no '…' truncation)
          // v3.4.6.c — the official app, OR (not installed) the in-app
          // AnantPlayInstaller sheet — never a silent no-op.
          onTap: () async {
            final installed = await AnantPlay.instance.officialInstalled();
            if (!context.mounted) return;
            if (installed) {
              await AnantPlay.instance.openOfficialApp();
            } else {
              showInstallerSheet(context);
            }
          },
        ),
        _ActionTile(
          icon: Icons.public_rounded,
          title: 'Browser',
          subtitle: 'Web & subs',
          // v3.4.6.c — complaint #1 fix: this tile switches the SHELL to the
          // live Browser tab (was: an openWebTab no-op that opened nothing).
          onTap: () => ShellRootState.of(context).openBrowserTab(),
        ),
        _ActionTile(
          icon: Icons.link_rounded,
          title: 'Play URL',
          subtitle: 'Stream link',
          onTap: () => _openUrlSheet(context),
        ),
        _ActionTile(
          // v3.4.6.h — PERMANENT LAW #3 (THE SEMANTIC ICON LAW): the cinema
          // play glyph replaces the mismatched chemistry flask the v3.4.6.g
          // Home tile carried — Test Player plays a demo MOVIE.
          icon: kTestPlayerIcon,
          title: 'Test Player',
          subtitle: 'Demo movie',
          // v3.4.6.c — complaint #3 fix: the MORNYE CINEMA PLAYER in the
          // shell (was: the raw Java PlayActivity).
          onTap: () => ShellRootState.of(context).pushPlayer(
                const PlayRequest(
                  url: '',
                  test: true,
                ),
              ),
        ),
      ],
    );
  }

  /// The Mornye Liquid-Glass URL sheet: Paste (clipboard) + Play. Empty input
  /// is refused with a hint — the button can never be a dead tap.
  static void _openUrlSheet(BuildContext context) {
    final controller = TextEditingController();
    final scheme = Theme.of(context).colorScheme;
    showModalBottomSheet<void>(
      context: context,
      backgroundColor: scheme.surfaceContainerHigh,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(32)),
      ),
      isScrollControlled: true,
      builder: (sheetContext) {
        return Padding(
          padding: EdgeInsets.fromLTRB(
              20, 20, 20, 20 + MediaQuery.viewInsetsOf(sheetContext).bottom),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text('Play a URL',
                  style: TextStyle(
                      color: scheme.onSurface,
                      fontSize: 18,
                      fontWeight: FontWeight.w700)),
              const SizedBox(height: 4),
              Text('Any HLS / DASH / MP4 link — played in the Mornye Cinema Player.',
                  style: TextStyle(
                      color: scheme.onSurfaceVariant, fontSize: 12.5)),
              const SizedBox(height: 16),
              TextField(
                controller: controller,
                autofocus: true,
                decoration: InputDecoration(
                  hintText: 'https://example.com/stream.m3u8',
                  prefixIcon: const Icon(Icons.link_rounded),
                  border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(14)),
                ),
              ),
              const SizedBox(height: 14),
              Row(
                children: [
                  OutlinedButton.icon(
                    icon: const Icon(Icons.content_paste_rounded, size: 18),
                    label: const Text('Paste'),
                    onPressed: () async {
                      final data = await Clipboard.getData('text/plain');
                      final text = data?.text?.trim() ?? '';
                      if (text.isNotEmpty) controller.text = text;
                    },
                  ),
                  const Spacer(),
                  FilledButton.icon(
                    icon: const Icon(Icons.play_arrow_rounded, size: 20),
                    label: const Text('Play'),
                    onPressed: () {
                      final url = controller.text.trim();
                      if (!url.startsWith('http')) {
                        showMornyeGlassNotification(sheetContext,
                            message: 'Paste a http(s) link first',
                            error: true);
                        return;
                      }
                      Navigator.pop(sheetContext);
                      ShellRootState.of(context).pushPlayer(PlayRequest(
                          url: url, title: url));
                    },
                  ),
                ],
              ),
            ],
          ),
        );
      },
    );
  }
}

/// The in-app AnantPlay installer sheet (official app not installed): the
/// same live resolve → percent → PackageInstaller pipeline onboarding uses.
void showInstallerSheet(BuildContext context) {
  final scheme = Theme.of(context).colorScheme;
  showModalBottomSheet<void>(
    context: context,
    backgroundColor: scheme.surfaceContainerHigh,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(32)),
    ),
    builder: (sheetContext) {
      return _InstallerSheet(scheme: scheme);
    },
  );
}

class _InstallerSheet extends StatefulWidget {
  const _InstallerSheet({required this.scheme});

  final ColorScheme scheme;

  @override
  State<_InstallerSheet> createState() => _InstallerSheetState();
}

class _InstallerSheetState extends State<_InstallerSheet> {
  int _percent = 0;
  String _state = '';
  bool _running = false;
  StreamSubscription<InstallEvent>? _installSub;

  @override
  void initState() {
    super.initState();
    // v3.4.6.f — the ONE bridge hub subscription is cancelled on dispose:
    // every previously opened sheet leaked a listener forever (each new
    // sheet stacked ANOTHER one). The hub makes them harmless to the native
    // side; this makes them harmless to memory as well.
    _installSub = AnantPlay.instance.installEvents().listen((e) {
      if (!mounted) return;
      setState(() {
        if (e.type == 'installProgress') {
          _running = true;
          _percent = e.percent;
        } else {
          _state = e.extra.isEmpty ? e.value : '${e.value} · ${e.extra}';
        }
      });
    });
  }

  @override
  void dispose() {
    _installSub?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final scheme = widget.scheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 20, 20, 24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('Install official AnantPlay',
              style: TextStyle(
                  color: scheme.onSurface,
                  fontSize: 18,
                  fontWeight: FontWeight.w700)),
          const SizedBox(height: 6),
          Text(
              'AnantPlay is the casting remote — install it to cast movies here.',
              style:
                  TextStyle(color: scheme.onSurfaceVariant, fontSize: 12.5)),
          const SizedBox(height: 16),
          if (_running || _percent > 0) ...[
            ClipRRect(
              borderRadius: BorderRadius.circular(3),
              child: LinearProgressIndicator(
                value: _percent.clamp(0, 100) / 100.0,
                minHeight: 5,
                backgroundColor: scheme.onSurface.withValues(alpha: 0.1),
                color: scheme.primary,
              ),
            ),
            const SizedBox(height: 6),
            Text(_state.isEmpty ? '$_percent%' : _state,
                style: TextStyle(
                    color: scheme.onSurfaceVariant, fontSize: 12)),
            const SizedBox(height: 12),
          ] else
            FilledButton.icon(
              icon: const Icon(Icons.download_rounded, size: 20),
              label: const Text('Download & Install'),
              onPressed: () =>
                  AnantPlay.instance.installOfficialAnantPlay(),
            ),
        ],
      ),
    );
  }
}

class _ActionTile extends StatelessWidget {
  const _ActionTile({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.onTap,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Material(
      color: scheme.surfaceContainerHigh.withValues(alpha: 0.35),
      borderRadius: BorderRadius.circular(20),
      child: InkWell(
        borderRadius: BorderRadius.circular(20),
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(20),
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
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Text(title,
                        style: TextStyle(
                            color: scheme.onSurface,
                            fontSize: 14,
                            fontWeight: FontWeight.w600)),
                    const SizedBox(height: 2),
                    Text(subtitle,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                            color: scheme.onSurfaceVariant,
                            fontSize: 11.5)),
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
    final scheme = Theme.of(context).colorScheme;
    if (history.isEmpty) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 18),
        child: Center(
          child: Text(
              'Nothing watched yet — cast or open a link to begin.',
              style: TextStyle(
                  color: scheme.onSurfaceVariant, fontSize: 13)),
        ),
      );
    }
    // v3.4.6.j — THE HISTORY VIEW (video_2 05:20): 'list' renders the
    // full-width rows exactly as before; 'grid' reflows the same rows into
    // a 2-column poster-grid (the exact Mornye History View pattern).
    final bool grid =
        AppState.of(context).settings.s(SettingsMirror.kHistoryView, 'list') ==
            'grid';
    final shown = history.take(6).toList();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Text('Continue watching',
                  style: TextStyle(
                      color: scheme.onSurface,
                      fontSize: 17,
                      fontWeight: FontWeight.w700)),
            ),
            TextButton(
              // v3.4.6.c — Clear all (each row also has its own delete).
              onPressed: () {
                // fire-and-forget per row, then one refresh — the scope
                // notifies the shell itself; no context use after await.
                for (final it in List<HistoryItem>.from(history)) {
                  AnantPlay.instance.removeHistory(it.key);
                }
                AppState.of(context).scope.refresh();
              },
              child: const Text('Clear all'),
            ),
          ],
        ),
        const SizedBox(height: 10),
        if (grid)
          // the grid: 2 columns of compact poster cards
          GridView.count(
            crossAxisCount: 2,
            mainAxisSpacing: 10,
            crossAxisSpacing: 10,
            childAspectRatio: 1.55,
            physics: const NeverScrollableScrollPhysics(),
            shrinkWrap: true,
            children: [for (final it in shown) _HistoryRow(item: it, grid: true)],
          )
        else
          for (final it in shown) _HistoryRow(item: it),
      ],
    );
  }
}

class _HistoryRow extends StatelessWidget {
  const _HistoryRow({required this.item, this.grid = false});

  final HistoryItem item;

  /// v3.4.6.j — the History-view mode this row renders in (grid compacts
  /// the same content into a poster-card shape).
  final bool grid;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final resumable = item.resumable;
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Material(
        color: scheme.surfaceContainerHigh.withValues(alpha: 0.25),
        borderRadius: BorderRadius.circular(14),
        child: InkWell(
          borderRadius: BorderRadius.circular(14),
          // v3.4.6.c — 1-tap resume IN the Mornye Cinema Player, at the
          // saved position (the bridge seeks on open), with the timestamp
          // shown right on the row.
          onTap: () => ShellRootState.of(context).pushPlayer(PlayRequest(
                url: item.url,
                title: item.title,
                resumeMs: item.pos.round(),
              )),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                          item.title.isEmpty ? item.url : item.title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                              color: scheme.onSurface,
                              fontSize: 13.5,
                              fontWeight: FontWeight.w500)),
                      const SizedBox(height: 6),
                      ClipRRect(
                        borderRadius: BorderRadius.circular(3),
                        child: LinearProgressIndicator(
                          value: item.percent / 100.0,
                          minHeight: 3,
                          backgroundColor:
                              scheme.onSurface.withValues(alpha: 0.1),
                          color: scheme.primary,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        item.dur > 0
                            ? '${_clock(item.pos)} / ${_clock(item.dur)}'
                            : _clock(item.pos),
                        style: TextStyle(
                            color: scheme.onSurfaceVariant, fontSize: 11),
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 10),
                if (resumable)
                  Icon(Icons.play_arrow_rounded,
                      color: scheme.primary, size: 20)
                else
                  Icon(Icons.replay_rounded,
                      color: scheme.onSurfaceVariant, size: 16),
                // v3.4.6.c — per-row delete (Dismissible-style tap target;
                // hidden in the compact grid card — the list view owns the
                // per-row delete surface).
                if (!grid)
                  IconButton(
                    tooltip: 'Remove',
                    visualDensity: VisualDensity.compact,
                    icon: Icon(Icons.close_rounded,
                        size: 16, color: scheme.onSurfaceVariant),
                    onPressed: () {
                      AnantPlay.instance.removeHistory(item.key);
                      AppState.of(context).scope.refresh();
                    },
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  static String _clock(double ms) {
    final s = (ms / 1000).round();
    final h = s ~/ 3600;
    final m = (s % 3600) ~/ 60;
    final sec = s % 60;
    return h > 0
        ? '$h:${m.toString().padLeft(2, '0')}:${sec.toString().padLeft(2, '0')}'
        : '${m.toString().padLeft(2, '0')}:${sec.toString().padLeft(2, '0')}';
  }
}
