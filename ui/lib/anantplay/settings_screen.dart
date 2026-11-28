// AnantPlay Sinhala Player v3.4.6.j — THE MORNYE-STYLE SETTINGS HUB.
//
// v3.4.6.j (video_2 03:40 / video_1 00:30–01:25): the ONE GIANT SCROLL WALL
// is GONE. The Settings tab is now a clean connected hub:
//   · a live `Search settings` field at the top (filters every setting
//     across every page as you type — the exact Mornye pattern);
//   · eight crisp navigation rows pushing dedicated sub-pages
//     (Cupertino/M3 spring transitions): Appearance · Playback & Subtitles ·
//     AnantPlay & Sources · Browser & Privacy · Cast & Network ·
//     Storage & Cache (NEW) · Backup & Restore (upgraded) · About &
//     Licenses;
//   · the page-level quick overflow (Backup/Reset/Engine pickers) rides the
//     Apple-style liquid-glass popover (showMornyeContextMenu).
//
// v3.4.6.c legacy structure retained underneath: the LIVE interactive
// preview card, theme cards, accent studio, glass sliders, the subtitle
// studio, the sources/installer rows, browser/privacy rows, cast rows and
// backup rows — every control still writes through the real
// `anantplay/settings` bridge (nothing decorative).
import 'package:flutter/services.dart'
    show FilteringTextInputFormatter, Clipboard, ClipboardData;
import 'package:material_ui/material_ui.dart' hide Icons;
import 'package:material_ui/material_ui.dart' as mi show Icons;

import 'package:anantplay_ui/mornye_ui/theme/app_tokens.dart';
import 'package:anantplay_ui/mornye_ui/theme/mornye_icons.dart';
import 'package:anantplay_ui/mornye_ui/widgets/settings_group.dart';
import 'package:anantplay_ui/mornye_ui/widgets/app_switch.dart';
import 'package:anantplay_ui/mornye_ui/widgets/mornye_chrome.dart';
import 'package:anantplay_ui/mornye_ui/widgets/mornye_context_menu.dart';
import 'package:anantplay_ui/mornye_ui/widgets/mornye_glass_notification.dart';
import 'package:anantplay_ui/mornye_ui/widgets/mornye_bubble_slider.dart';

import 'action_feedback.dart';
import 'app_state.dart';
import 'home_screen.dart' show showInstallerSheet;
import 'bridge.dart';
import 'settings_mirror.dart';
import 'subtitle_style.dart';
import 'package:anantplay_ui/main.dart'
    show wallpaperSchemeAvailable, kWallpaperUnavailableCaption;

/// v3.4.6.k — the Interface size label (85–115% around the 14sp studio base).
String _interfaceSizeLabel(double v) {
  final sp = (12 + v * 8).round().clamp(12, 20);
  return sp == 14 ? '100%' : '${(sp / 14 * 100).round()}%';
}

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      backgroundColor: scheme.surface,
      appBar: AppBar(
        title: const Text('Settings'),
        backgroundColor: scheme.scrim.withValues(alpha: 0.2),
        surfaceTintColor: Colors.transparent,
        actions: [
          // v3.4.6.j — the Apple-style liquid-glass overflow popover (the
          // Mornye context menu): Backup / Reset / Update-check quick picks.
          // v3.4.6.k — THE ANCHOR FIX (Part_04 02:18–04:18): the call used the
          // SCAFFOLD's context, so mornyeMenuAnchor() measured the whole page
          // (the >65% guard nulled it) and the menu expanded from the CENTER
          // of the screen. The ⋮ button's OWN Builder context now supplies the
          // exact anchor rect, and the flight origin is its top-right corner —
          // the glass pours directly out of the button (Alignment.topRight).
          Builder(
            builder: (btnContext) => IconButton(
              icon: const Icon(mi.Icons.more_vert_rounded),
              tooltip: 'Quick actions',
              onPressed: () => _settingsOverflow(btnContext),
            ),
          ),
        ],
      ),
      body: const _SettingsHub(),
    );
  }

  Future<void> _settingsOverflow(BuildContext context) {
    // v3.4.6.j — the Apple-style liquid-glass popover (the vendored Mornye
    // context menu): Backup / Reset / Update-check quick picks.
    // v3.4.6.k — the exact ⋮ anchor rect (the button's own box, root-overlay
    // coordinates) + the top-right origin: the menu scales smoothly out of
    // the ⋮ button itself — never from the center of the screen.
    final anchor = mornyeMenuAnchor(context);
    return showMornyeContextMenu<void>(
      context: context,
      anchor: anchor,
      origin: anchor?.topRight,
      builder: (menuContext) => MornyeContextMenu(
        dense: true,
        groups: [
          [
            MornyeMenuAction(
              icon: mi.Icons.backup_rounded,
              label: 'Export backup',
              onPressed: () {
                Navigator.pop(menuContext);
                _exportBackup(context);
              },
            ),
            MornyeMenuAction(
              icon: mi.Icons.restore_rounded,
              label: 'Import backup',
              onPressed: () {
                Navigator.pop(menuContext);
                _importBackup(context);
              },
            ),
            MornyeMenuAction(
              icon: mi.Icons.update_rounded,
              label: 'Check for update now',
              onPressed: () {
                Navigator.pop(menuContext);
                checkForUpdateWithFeedback(context);
              },
            ),
          ],
        ],
      ),
    );
  }

  Future<void> _exportBackup(BuildContext context) async {
    try {
      final text = await AnantPlay.instance.exportBackup();
      await Clipboard.setData(ClipboardData(text: text));
      if (!context.mounted) return;
      showMornyeGlassNotification(context,
          message: 'Backup copied to the clipboard');
    } catch (e) {
      if (!context.mounted) return;
      showMornyeGlassNotification(context,
          message: 'Export failed · $e', error: true);
    }
  }

  Future<void> _importBackup(BuildContext context) async {
    final data = await Clipboard.getData('text/plain');
    final text = data?.text ?? '';
    if (!context.mounted) return;
    if (text.trim().isEmpty) {
      showMornyeGlassNotification(context,
          message: 'Clipboard is empty — copy a backup first', error: true);
      return;
    }
    await importBackupWithFeedback(context, text);
  }
}

/// v3.4.6.j — THE HUB: the searchable, connected settings surface (video_2
/// 03:40 / video_1 00:30–01:25). An empty query shows the eight navigation
/// rows; typing filters EVERY setting across every page inline.
class _SettingsHub extends StatefulWidget {
  const _SettingsHub();

  @override
  State<_SettingsHub> createState() => _SettingsHubState();
}

class _SettingsHubState extends State<_SettingsHub> {
  String _query = '';

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final state = AppState.of(context);
    final s = state.settings;
    final matched =
        _query.trim().isEmpty ? const <SettingEntry>[] : _search(_query);
    return ListView(
      padding: const EdgeInsets.fromLTRB(0, 8, 0, 32),
      children: [
        // ── THE SEARCH FIELD (live-filtering every setting) ──────────────
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
          child: TextField(
            key: const ValueKey('settings-search'),
            onChanged: (v) => setState(() => _query = v),
            decoration: InputDecoration(
              hintText: 'Search settings',
              prefixIcon: const Icon(mi.Icons.search_rounded),
              isDense: true,
              filled: true,
              fillColor: scheme.surfaceContainerHigh.withValues(alpha: 0.6),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(14),
                borderSide: BorderSide.none,
              ),
            ),
          ),
        ),

        if (matched.isNotEmpty) ...[
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 4, 20, 6),
            child: Text('${matched.length} settings match "${_query.trim()}"',
                style: TextStyle(
                    fontSize: 12.5, color: scheme.onSurfaceVariant)),
          ),
          SettingsGroup(children: [
            for (final entry in matched)
              _ActionRow(
                title: entry.title,
                subtitle: entry.subtitle,
                onTap: () => _pushPage(context, entry.page),
              ),
          ]),
        ] else ...[
          // ── THE EIGHT CONNECTED NAVIGATION ROWS ─────────────────────────
          SettingsGroup(children: [
            _NavRow(
              title: 'Appearance',
              subtitle: _appStyleLabel(s),
              icon: mi.Icons.palette_rounded,
              page: const _AppearanceSettingsPage(),
            ),
            _NavRow(
              title: 'Playback & Subtitles',
              subtitle: 'Subtitle studio · gestures · cinema player',
              icon: mi.Icons.movie_rounded,
              page: const _PlaybackSubtitlesPage(),
            ),
            _NavRow(
              title: 'AnantPlay & Sources',
              subtitle: 'Indexes · official app · guided setup',
              icon: mi.Icons.cast_rounded,
              page: const _AnantPlaySourcesPage(),
            ),
            _NavRow(
              title: 'Browser & Privacy',
              subtitle: 'Search engine · ad block · reader',
              icon: mi.Icons.public_rounded,
              page: const _BrowserPrivacyPage(),
            ),
            _NavRow(
              title: 'Cast & Network',
              subtitle: 'DLNA · mDNS · worker ping',
              icon: mi.Icons.wifi_rounded,
              page: const _CastNetworkPage(),
            ),
            _NavRow(
              title: 'Storage & Cache',
              subtitle: 'Clear cache · real MB usage',
              icon: mi.Icons.storage_rounded,
              page: const _StorageCachePage(),
            ),
            _NavRow(
              title: 'Backup & Restore',
              subtitle: 'Backup categories · reset',
              icon: mi.Icons.backup_rounded,
              page: const _BackupRestorePage(),
            ),
            _NavRow(
              title: 'About & Licenses',
              subtitle: 'Version · the live installed build',
              icon: mi.Icons.info_rounded,
              page: const _AboutPage(),
            ),
          ]),
          const _VersionFooter(),
        ],
      ],
    );
  }

  String _appStyleLabel(SettingsMirror s) {
    final style = s.s(SettingsMirror.kAppStyle, 'mornye');
    return style == 'material'
        ? 'App style: Material · theme · accent · glass'
        : 'App style: Mornye · theme · accent · glass';
  }

  void _pushPage(BuildContext context, Widget page) {
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => page,
        // v3.4.6.j — the Mornye springy slide-up + fade the reference app's
        // sub-pages use (Material's own transitions follow the app style).
        fullscreenDialog: false,
      ),
    );
  }

  List<SettingEntry> _search(String query) {
    final q = query.toLowerCase().trim();
    if (q.isEmpty) return const [];
    return kSettingCatalog
        .where((e) =>
            e.title.toLowerCase().contains(q) ||
            e.keywords.toLowerCase().contains(q))
        .toList();
  }
}

/// One searchable settings entry (the catalog the hub filters).
class SettingEntry {
  const SettingEntry(this.title, this.subtitle, this.keywords, this.page);
  final String title;
  final String subtitle;
  final String keywords;
  final Widget page;
}

/// The full searchable settings catalog (one entry per high-value setting,
/// each pushing the page it lives on).
const List<SettingEntry> kSettingCatalog = [
  SettingEntry('App style (Material / Mornye)',
      'Switch the whole shell between the two design languages',
      'appstyle material mornye theme design', _AppearanceSettingsPage()),
  SettingEntry('Theme', 'System · Light · Dark · AMOLED',
      'theme dark light amoled mode', _AppearanceSettingsPage()),
  SettingEntry('Accent color', 'Presets, # hex Color Studio, Material You',
      'accent color hex dynamic material you', _AppearanceSettingsPage()),
  SettingEntry('Glass clarity', 'Live slider with the percentage bubble',
      'glass clarity blur transparency', _AppearanceSettingsPage()),
  SettingEntry('Midnight night dimmer', 'Dim the whole screen at night',
      'midnight dimmer night dim brightness', _AppearanceSettingsPage()),
  SettingEntry('Interface size', '85% – 115% with the live bubble',
      'interface size scale font', _AppearanceSettingsPage()),
  SettingEntry('History view', 'Continue Watching as a list or a grid',
      'history grid list continue watching', _AppearanceSettingsPage()),
  SettingEntry('Subtitle size / font / color',
      'The live Sinhala subtitle studio',
      'subtitle size font color bold background opacity position',
      _PlaybackSubtitlesPage()),
  SettingEntry('Auto Sinhala subtitles', 'Fetch & translate automatically',
      'auto sinhala translate subtitles fetch', _PlaybackSubtitlesPage()),
  SettingEntry('Sync step', 'Subtitle timing nudge ±0.25 s / ±1 s',
      'sync step subtitle timing offset', _PlaybackSubtitlesPage()),
  SettingEntry('Search engine', 'Google · DuckDuckGo · Startpage · Brave',
      'search engine browser google duckduckgo startpage brave home',
      _BrowserPrivacyPage()),
  SettingEntry('Ad block', 'The browser ad blocker',
      'ad block ads browser privacy', _BrowserPrivacyPage()),
  SettingEntry('Ping worker', 'The subtitle worker latency check',
      'ping worker network cloudflare cast', _CastNetworkPage()),
  SettingEntry('Clear all cache', 'App · WebView · subtitles · updates',
      'storage cache clear free space mb', _StorageCachePage()),
  SettingEntry('Create backup file', 'Export the settings document',
      'backup restore export import reset', _BackupRestorePage()),
  SettingEntry('Check for update now', 'The honest release check',
      'update version check', _AboutPage()),
  SettingEntry('Run setup guide', 'Replay the 6-slide wizard',
      'setup guide onboarding wizard', _AboutPage()),
];

/// One connected navigation row (the Mornye inset-grouped row: icon plate +
/// title + subtitle + the chevron).
class _NavRow extends StatelessWidget {
  const _NavRow({required this.title, required this.subtitle, required this.icon, required this.page});

  final String title;
  final String subtitle;
  final IconData icon;
  final Widget page;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return ListTile(
      onTap: () {
        Navigator.of(context).push(
          MaterialPageRoute<void>(builder: (_) => page),
        );
      },
      leading: Container(
        width: 38,
        height: 38,
        decoration: BoxDecoration(
          color: scheme.primary.withValues(alpha: 0.16),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Icon(icon, size: 20, color: scheme.primary),
      ),
      title: Text(title,
          style: const TextStyle(fontSize: 14.5, fontWeight: FontWeight.w600)),
      subtitle: Text(subtitle,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
              fontSize: 11.5, color: scheme.onSurfaceVariant)),
      trailing: const Icon(mi.Icons.chevron_right_rounded, size: 20),
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 2),
    );
  }
}

// ═══════════════════════════════════════════════════════════════════════════
// v3.4.6.j — THE EIGHT SETTINGS SUB-PAGES
// ═══════════════════════════════════════════════════════════════════════════

/// The shared sub-page scaffold (the Mornye inset-grouped layout).
class _SettingsSubPage extends StatelessWidget {
  const _SettingsSubPage({required this.title, required this.children});

  final String title;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      backgroundColor: scheme.surface,
      appBar: AppBar(
        title: Text(title),
        backgroundColor: scheme.scrim.withValues(alpha: 0.2),
        surfaceTintColor: Colors.transparent,
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(0, 8, 0, 32),
        children: children,
      ),
    );
  }
}

/// PAGE 1 — Appearance: the dual App Style, theme, accent, glass, fonts,
/// interface size, animation speed, history view, midnight dimmer.
class _AppearanceSettingsPage extends StatelessWidget {
  const _AppearanceSettingsPage();

  @override
  Widget build(BuildContext context) {
    final state = AppState.of(context);
    final scope = state.scope;
    final s = state.settings;
    final scheme = Theme.of(context).colorScheme;
    return _SettingsSubPage(
      title: 'Appearance',
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
          child: const _LivePreviewCard(),
        ),
        SettingsGroup(children: [
          // v3.4.6.j — THE DUAL APP STYLE (video_2 05:20 / video_1 00:00):
          // 'mornye' = the Apple Liquid-Glass engine (default), 'material' =
          // Material 3 Expressive / Material You. The whole shell's theme
          // flips on this one key (main.dart's buildAppTheme reads it live).
          _SegmentedRow(
            title: 'App style',
            labels: const ['Mornye', 'Material'],
            selected: s.s(SettingsMirror.kAppStyle, 'mornye') == 'mornye' ? 0 : 1,
            onChanged: (i) => scope.putString(
                SettingsMirror.kAppStyle, i == 0 ? 'mornye' : 'material'),
          ),
          _ThemeCards(current: s.themeMode),
          const SizedBox(height: 14),
          _AccentRow(current: s.accent, hex: s.accentHex),
          const SizedBox(height: 4),
        ]),
        SettingsGroup(children: [
          _SliderRow(
            title: 'Glass clarity',
            value: s.glassClarity / 100.0,
            labelFor: (v) =>
                '${(v * 100).round().clamp(10, 95)}%',
            onCommit: (v) => scope.putInt(
                SettingsMirror.kGlassClarity, (v * 100).round().clamp(10, 95)),
          ),
          _SwitchRow(
            title: 'Glass outlines',
            value: s.glassOutlines,
            onChanged: (v) =>
                scope.putBool(SettingsMirror.kGlassOutlines, v),
          ),
          _SwitchRow(
            // v3.4.6.k — THE STUCK FORCE-LIQUID-BLUR FIX (Part_02 00:50–01:10):
            // v3.4.6.j read the STRING surfaceStyle key ('glass'/'flat')
            // through the BOOL getter `b()` — a String is never a bool, so it
            // ALWAYS fell back to the default `true` and the switch rendered
            // permanently ON no matter what was stored (writing 'flat'
            // succeeded, the next read just ignored it). The String lane
            // (s() == 'glass') both reads AND writes the same key now.
            title: 'Force liquid blur',
            value: s.s(SettingsMirror.kSurfaceStyle, 'glass') == 'glass',
            onChanged: (v) => scope.putString(
                SettingsMirror.kSurfaceStyle, v ? 'glass' : 'flat'),
          ),
          _SliderRow(
            title: 'Animation speed',
            value: (s.b(SettingsMirror.kMotion, true) ? 1.0 : 0.0),
            labelFor: (v) => v >= 0.5 ? 'Full' : 'Reduced',
            onCommit: (v) => scope.putBool(
                SettingsMirror.kMotion, v >= 0.5),
          ),
          // v3.4.6.j — Interface size (85–115%) with the live bubble, riding
          // the fontSp lane (the same studio value the player reads).
          _SliderRow(
            title: 'Interface size',
            value: (s.i(SettingsMirror.kFontSpKey, 14) - 12) / 8.0,
            labelFor: (v) => _interfaceSizeLabel(v),
            onCommit: (v) => scope.putInt(
                SettingsMirror.kFontSpKey, (12 + v * 8).round().clamp(12, 20)),
          ),
          _SegmentedRow(
            title: 'History view',
            labels: const ['List', 'Grid'],
            selected: s.s(SettingsMirror.kHistoryView, 'list') == 'list' ? 0 : 1,
            onChanged: (i) => scope.putString(
                SettingsMirror.kHistoryView, i == 0 ? 'list' : 'grid'),
          ),
        ]),
        _SectionHeader('Navigation'),
        SettingsGroup(children: const [_AppearanceNavRows()]),
        _SectionHeader('Midnight dimmer'),
        SettingsGroup(children: [
          _SwitchRow(
            title: 'Midnight night dimmer',
            value: s.b(SettingsMirror.kMidnightDimmer, false),
            onChanged: (v) =>
                scope.putBool(SettingsMirror.kMidnightDimmer, v),
            enabled: Theme.of(context).brightness == Brightness.dark,
            subtitle: Theme.of(context).brightness == Brightness.dark
                ? null
                : 'Active in Dark & AMOLED modes',
          ),
          _SliderRow(
            title: 'Dim amount',
            value: s.i(SettingsMirror.kMidnightDimPct, 30) / 70.0,
            labelFor: (v) =>
                '${(v * 70).round().clamp(10, 70)}%',
            onCommit: (v) => scope.putInt(
                SettingsMirror.kMidnightDimPct,
                (v * 70).round().clamp(10, 70)),
            enabled: Theme.of(context).brightness == Brightness.dark,
          ),
        ]),
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 6, 16, 0),
          child: Text(
            // v3.4.6.j — the live style preview caption.
            'App style: ${s.s(SettingsMirror.kAppStyle, 'mornye') == 'material' ? 'Material 3 Expressive · Material You dynamic color' : 'Mornye Liquid Glass · refracting surfaces'}',
            style: TextStyle(fontSize: 11.5, color: scheme.onSurfaceVariant),
          ),
        ),
      ],
    );
  }
}

/// PAGE 2 — Playback & Subtitles: the subtitle studio + playback rows.
///
/// v3.4.6.l — THE LIVE-DRAGGING PREVIEW (video_2 02:55): the page is now
/// STATEFUL and owns three live draft notifiers (size / opacity / position).
/// While a MornyeBubbleSlider is DRAGGED, onChanged feeds the local draft
/// through onPreview — the preview card + the top-right sp readout repaint
/// at 60/120 fps BEFORE anything commits; the single putInt commit still
/// fires once on onChangeEnd (the AppState + MethodChannel stay write-clean).
class _PlaybackSubtitlesPage extends StatefulWidget {
  const _PlaybackSubtitlesPage();

  @override
  State<_PlaybackSubtitlesPage> createState() => _PlaybackSubtitlesPageState();
}

class _PlaybackSubtitlesPageState extends State<_PlaybackSubtitlesPage> {
  /// The in-flight drag drafts (null = resting on the committed mirror value).
  /// Public so the studio rows below can feed the SAME drafts (one source of
  /// truth for the live preview).
  final ValueNotifier<double?> _sizeDraft = ValueNotifier<double?>(null);
  final ValueNotifier<double?> _bgOpacityDraft = ValueNotifier<double?>(null);
  final ValueNotifier<double?> _positionDraft = ValueNotifier<double?>(null);

  @override
  void dispose() {
    _sizeDraft.dispose();
    _bgOpacityDraft.dispose();
    _positionDraft.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final state = AppState.of(context);
    final scope = state.scope;
    final s = state.settings;
    return _SettingsSubPage(
      title: 'Playback & Subtitles',
      children: [
        // v3.4.6.k — THE STICKY LIVE SINHALA SUBTITLE PREVIEW CARD (Part_02
        // 03:00–03:16, Part_04 00:25–00:54): the reference line
        // "ශ්‍රී ලංකාවේ සිනමා උපසිරැසි" updates INSTANTANEOUSLY as the user
        // adjusts Subtitle size, Subtitle font, Background style, Background
        // opacity and Vertical position below (it rides the SAME
        // resolveSubtitleTextStyle resolver the player paints with, with the
        // ZWJ conjuncts ශ්‍රී passing through verbatim).
        // v3.4.6.l — the card ALSO listens to the three live drag drafts.
        ValueListenableBuilder<double?>(
          valueListenable: _sizeDraft,
          builder: (context, _, d0) => ValueListenableBuilder<double?>(
            valueListenable: _bgOpacityDraft,
            builder: (context, _, d1) => ValueListenableBuilder<double?>(
              valueListenable: _positionDraft,
              builder: (context, _, d2) => _LiveSubtitlePreviewCard(
                s: s,
                liveSize: _sizeDraft.value,
                liveBgOpacity: _bgOpacityDraft.value,
                livePosition: _positionDraft.value,
              ),
            ),
          ),
        ),
        SettingsGroup(children: [
          _SliderRow(
            title: 'Subtitle size',
            value: ((s.i(SettingsMirror.kFontSpKey, 14)) - 12) / 24.0,
            labelFor: (v) => '${(12 + v * 24).round().clamp(12, 36)}sp',
            // v3.4.6.l — the LIVE drag lane: the preview + sp readout update
            // on every drag frame, before the single commit lands.
            onPreview: (v) => _sizeDraft.value = v,
            onCommit: (v) => scope.putInt(
                SettingsMirror.kFontSpKey, (12 + v * 24).round().clamp(12, 36)),
          ),
          _SwitchRow(
            title: 'Auto Sinhala subtitles',
            value: s.b(SettingsMirror.kAutoFetch, true),
            onChanged: (v) =>
                scope.putBool(SettingsMirror.kAutoFetch, v),
          ),
          _SwitchRow(
            title: 'On-device translator',
            value: s.b(SettingsMirror.kFreeTranslator, true),
            onChanged: (v) =>
                scope.putBool(SettingsMirror.kFreeTranslator, v),
          ),
          _SwitchRow(
            title: 'Continue watching',
            value: s.b(SettingsMirror.kContinueWatching, true),
            onChanged: (v) => scope.putBool(
                SettingsMirror.kContinueWatching, v),
          ),
        ]),
        _SectionHeader('Player controls'),
        SettingsGroup(children: [
          // v3.4.6.l — THE NEXT-LEVEL CINEMA PLAYER CUSTOMIZATION (1:1 with
          // the native Player Customization sheet: same keys, same values,
          // same writer lanes — the two surfaces are one control set).
          _SegmentedRow(
            title: 'Double-tap seek seconds',
            labels: const ['5 s', '10 s', '15 s', '30 s'],
            selected: const [5, 10, 15, 30]
                .indexOf(s.i(SettingsMirror.kDoubleTapSeekSec, 10))
                .clamp(0, 3),
            onChanged: (i) => scope.putInt(
                SettingsMirror.kDoubleTapSeekSec, const [5, 10, 15, 30][i]),
          ),
          _SegmentedRow(
            title: 'Long-press speed',
            labels: const ['1.5x', '2.0x', '2.5x'],
            selected: const [150, 200, 250]
                .indexOf(s.i(SettingsMirror.kLongPressSpeedX, 200))
                .clamp(0, 2),
            onChanged: (i) => scope.putInt(
                SettingsMirror.kLongPressSpeedX, const [150, 200, 250][i]),
          ),
          _SegmentedRow(
            title: 'Seek step',
            labels: const ['5 s', '10 s', '15 s', '30 s'],
            selected: const [5000, 10000, 15000, 30000]
                .indexOf(s.i(SettingsMirror.kSeekStepMs, 10000))
                .clamp(0, 3),
            onChanged: (i) => scope.putInt(
                SettingsMirror.kSeekStepMs, const [5000, 10000, 15000, 30000][i]),
          ),
          _SegmentedRow(
            title: 'Controls auto-hide',
            labels: const ['2 s', '3 s', '5 s'],
            selected: s.i(SettingsMirror.kHudHideMs, 3500) <= 2000
                ? 0
                : (s.i(SettingsMirror.kHudHideMs, 3500) <= 3000 ? 1 : 2),
            onChanged: (i) => scope.putLong(
                SettingsMirror.kHudHideMs, const [2000, 3000, 5000][i]),
          ),
          _SwitchRow(
            title: 'Swipe brightness and volume',
            subtitle: 'Vertical edge swipes with the live capsules',
            value: s.b(SettingsMirror.kSwipeBrightness, true) ||
                s.b(SettingsMirror.kSwipeVolume, true),
            onChanged: (v) {
              scope.putBool(SettingsMirror.kSwipeBrightness, v);
              scope.putBool(SettingsMirror.kSwipeVolume, v);
            },
          ),
          _SwitchRow(
            title: 'Swipe to seek',
            subtitle: 'Horizontal scrub with the live time pill',
            value: s.b(SettingsMirror.kSwipeSeek, true),
            onChanged: (v) => scope.putBool(SettingsMirror.kSwipeSeek, v),
          ),
          _SwitchRow(
            title: 'Double-tap to seek',
            value: s.b(SettingsMirror.kDoubleTapSeek, true),
            onChanged: (v) => scope.putBool(SettingsMirror.kDoubleTapSeek, v),
          ),
          _SwitchRow(
            title: 'Pinch to zoom',
            subtitle: 'Two fingers cycle Fit and Fill',
            value: s.b(SettingsMirror.kPinchZoom, true),
            onChanged: (v) => scope.putBool(SettingsMirror.kPinchZoom, v),
          ),
          _SwitchRow(
            title: 'Frame-rate matching',
            subtitle: 'Seamless 24/30/60 Hz display sync',
            value: s.b(SettingsMirror.kFrameRateMatch, true),
            onChanged: (v) => scope.putBool(SettingsMirror.kFrameRateMatch, v),
          ),
        ]),
        _SectionHeader('Subtitle studio'),
        SettingsGroup(children: const [_SubtitleStudioRows()]),
      ],
    );
  }
}

/// PAGE 3 — AnantPlay & Sources: the multi-index manager + official app.
class _AnantPlaySourcesPage extends StatelessWidget {
  const _AnantPlaySourcesPage();

  @override
  Widget build(BuildContext context) {
    return _SettingsSubPage(
      title: 'AnantPlay & Sources',
      children: [
        _SectionHeader('Official AnantPlay'),
        SettingsGroup(children: const [
          _AnantPlayRows(),
          _OfficialAnantPlayUpdateRow(),
        ]),
        _SectionHeader('Guided setup'),
        SettingsGroup(children: [
          _ActionRow(
            title: 'Guided AnantPlay Setup',
            subtitle:
                'The floating coach walks all 10 steps over AnantPlay',
            onTap: () => AnantPlay.instance.openOfficialWithCoach(),
          ),
          // v3.4.6.k — THE BROKEN EMPTY BOX DELETION (Part_02 03:38, Part_03
          // 02:55): the v3.4.6.j GUIDED SETUP card carried an unlabeled EMPTY
          // text field + Save that saved nothing into the guided flow. It is
          // GONE; the multi-index manager below is the one true URL surface.
        ]),
        _SectionHeader('Index URLs'),
        SettingsGroup(children: const [
          _MultiIndexManager(),
        ]),
      ],
    );
  }
}

/// PAGE 4 — Browser & Privacy.
class _BrowserPrivacyPage extends StatelessWidget {
  const _BrowserPrivacyPage();

  @override
  Widget build(BuildContext context) {
    return _SettingsSubPage(
      title: 'Browser & Privacy',
      children: [
        SettingsGroup(children: const [_BrowserRows()]),
      ],
    );
  }
}

/// PAGE 5 — Cast & Network.
class _CastNetworkPage extends StatelessWidget {
  const _CastNetworkPage();

  @override
  Widget build(BuildContext context) {
    return _SettingsSubPage(
      title: 'Cast & Network',
      children: [
        SettingsGroup(children: const [_CastRows()]),
      ],
    );
  }
}

/// PAGE 6 — Storage & Cache (video_1 00:50): the LIVE real-MB breakdown +
/// Clear all cache + Refresh stats.
class _StorageCachePage extends StatefulWidget {
  const _StorageCachePage();

  @override
  State<_StorageCachePage> createState() => _StorageCachePageState();
}

class _StorageCachePageState extends State<_StorageCachePage> {
  CacheStats? _stats;
  bool _clearing = false;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    try {
      final stats = await AnantPlay.instance.cacheStats();
      if (mounted) setState(() => _stats = stats);
    } catch (_) {
      if (mounted) {
        setState(() => _stats = const CacheStats(
            appCache: 0, subs: 0, updates: 0, history: 0));
      }
    }
  }

  Future<void> _clearAll() async {
    if (_clearing) return;
    setState(() => _clearing = true);
    try {
      final freed = await AnantPlay.instance.clearCache();
      await _refresh();
      if (!mounted) return;
      showMornyeGlassNotification(context,
          message: 'Freed ${_mb(freed)} of cache');
    } catch (e) {
      if (!mounted) return;
      showMornyeGlassNotification(context,
          message: 'Clear failed · $e', error: true);
    } finally {
      if (mounted) setState(() => _clearing = false);
    }
  }

  static String _mb(int bytes) {
    if (bytes <= 0) return '0 MB';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(0)} KB';
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final stats = _stats;
    // v3.4.6.k — the OTA-managed subtitle cache TTL (AppPrefs.K_OTA_SUB_CACHE_
    // DAYS via the signed manifest; the row states the live policy, never a
    // stale literal — an unwired pref is a fake pref).
    final subTtlDays =
        AppState.of(context).settings.otaSubCacheDays;
    return _SettingsSubPage(
      title: 'Storage & Cache',
      children: [
        SettingsGroup(children: [
          _StorageRow(
              title: 'App cache & WebView',
              bytes: stats?.appCache ?? 0),
          _StorageRow(
              key: const ValueKey('sub-cache-ttl'),
              title: 'Subtitle cache',
              subtitle: 'Auto-expires after $subTtlDays days (signed config)',
              bytes: stats?.subs ?? 0),
          _StorageRow(title: 'Update cache', bytes: stats?.updates ?? 0),
          _StorageRow(title: 'Watch history', bytes: stats?.history ?? 0),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 6, 16, 10),
            child: Text(
              'Total ${_mb(stats?.total ?? 0)}',
              key: const ValueKey('storage-total'),
              style: TextStyle(
                  fontSize: 12.5,
                  fontWeight: FontWeight.w700,
                  color: scheme.onSurface),
            ),
          ),
        ]),
        SettingsGroup(children: [
          _ActionRow(
            title: 'Clear all cache',
            key: const ValueKey('clear-all-cache'),
            subtitle: _clearing ? 'Clearing…' : 'App · WebView · subtitles · updates',
            onTap: _clearAll,
          ),
          _ActionRow(
            title: 'Refresh stats',
            subtitle: 'Re-read the live byte counts',
            onTap: _refresh,
          ),
          _ActionRow(
            title: 'Clear watch history',
            subtitle: 'Removes every Continue Watching row',
            onTap: () async {
              await AnantPlay.instance.clearHistory();
              if (!context.mounted) return;
              await AppState.of(context).scope.refresh();
              if (!context.mounted) return;
              showMornyeGlassNotification(context,
                  message: 'Watch history cleared');
            },
          ),
        ]),
      ],
    );
  }
}

/// One real-MB storage row (the honest byte counts, never placeholders).
class _StorageRow extends StatelessWidget {
  const _StorageRow(
      {super.key, required this.title, required this.bytes, this.subtitle});

  final String title;
  final int bytes;

  /// v3.4.6.k — the optional live policy line (e.g. the OTA subtitle cache
  /// TTL); a row that states its real policy beats a silent hidden pref.
  final String? subtitle;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 11),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title,
                    style: const TextStyle(
                        fontSize: 13.5, fontWeight: FontWeight.w600)),
                if (subtitle != null && subtitle!.isNotEmpty)
                  Text(subtitle!,
                      key: ValueKey('storage-sub-$title'),
                      style: TextStyle(
                          fontSize: 11.5,
                          fontWeight: FontWeight.w500,
                          color: scheme.onSurfaceVariant)),
              ],
            ),
          ),
          Text(
            _StorageCachePageState._mb(bytes),
            key: ValueKey('storage-$title'),
            style: TextStyle(
                fontSize: 12.5,
                fontFeatures: const [FontFeature.tabularFigures()],
                color: scheme.onSurfaceVariant),
          ),
        ],
      ),
    );
  }
}

/// PAGE 7 — Backup & Restore (video_1 01:20): per-category toggles +
/// create/restore/reset.
class _BackupRestorePage extends StatelessWidget {
  const _BackupRestorePage();

  @override
  Widget build(BuildContext context) {
    final state = AppState.of(context);
    final scope = state.scope;
    final s = state.settings;
    return _SettingsSubPage(
      title: 'Backup & Restore',
      children: [
        SettingsGroup(children: [
          // v3.4.6.j — the per-category backup toggles (video_1 01:20):
          // App settings · Watch history · Custom index sources.
          _SwitchRow(
            title: 'App settings',
            value: s.b(SettingsMirror.kBackupAppSettings, true),
            onChanged: (v) =>
                scope.putBool(SettingsMirror.kBackupAppSettings, v),
          ),
          _SwitchRow(
            title: 'Watch history',
            value: s.b(SettingsMirror.kBackupHistory, true),
            onChanged: (v) =>
                scope.putBool(SettingsMirror.kBackupHistory, v),
          ),
          _SwitchRow(
            title: 'Custom index sources',
            value: s.b(SettingsMirror.kBackupIndexes, true),
            onChanged: (v) =>
                scope.putBool(SettingsMirror.kBackupIndexes, v),
          ),
        ]),
        SettingsGroup(children: const [_BackupRows()]),
      ],
    );
  }
}

/// PAGE 8 — About & Licenses.
class _AboutPage extends StatelessWidget {
  const _AboutPage();

  @override
  Widget build(BuildContext context) {
    return _SettingsSubPage(
      title: 'About & Licenses',
      children: [
        SettingsGroup(children: [
          // v3.4.6.i — PERMANENT LAW #1: the Auto-update ON/OFF switch is
          // GONE (auto-updating is mandatory); only the honest manual
          // "Check for update now" row remains.
          _ActionRow(
            title: 'Check for update now',
            subtitle: 'Checking… then Up to date on the live build',
            onTap: () => checkForUpdateWithFeedback(context),
          ),
          _ActionRow(
            title: 'Run setup guide',
            subtitle: 'Replay the 6-slide first-launch wizard',
            // v3.4.6.j — THE SUB-PAGE POP-FIRST RULE: this row lives on a
            // PUSHED sub-page route; re-arming the wizard without popping
            // first would render the wizard BEHIND this route (the shell
            // rebuilds underneath the pushed page — the user sees nothing).
            // Pop the sub-page, then rerun (the scope is captured before the
            // pop because the row's element is gone after it).
            onTap: () {
              final scope = AppState.of(context).scope;
              Navigator.of(context).pop();
              scope.rerunOnboarding();
            },
          ),
          // v3.4.6.k — THE SIGNED REMOTE CONFIG CARD: the OTA channel's state
          // is USER-VISIBLE (when it last synced, how many bridge mirrors /
          // index feeds / ad rules it pinned). A config channel that reports
          // nothing is indistinguishable from a dead one.
          const _RemoteConfigRow(),
        ]),
        _SectionHeader('Licenses'),
        SettingsGroup(children: [
          _ActionRow(
            title: 'Open-source licenses (LGPL-3.0-only)',
            onTap: () => _aboutPageLicenses(context),
          ),
          const _VersionFooter(),
        ]),
      ],
    );
  }

  Future<void> _aboutPageLicenses(BuildContext context) async {
    // v3.4.6.l — the LIVE installed version (the stale-literal kill: the
    // fallback matches the current release only until the channel answers).
    String version = kVisibleVersionFallback;
    try {
      final info = await AnantPlay.instance.appInfo();
      if (info.versionName.isNotEmpty) version = info.versionName;
    } catch (_) {/* the honest fallback literal */ }
    if (!context.mounted) return;
    showLicensePage(
      context: context,
      applicationName: 'AnantPlay Sinhala Player',
      applicationVersion: version,
      applicationIcon: const SizedBox(width: 48, height: 48),
    );
  }
}

// ── v3.4.6.k — THE SIGNED REMOTE CONFIG CARD (OTA channel transparency) ──────

/// One honest, live line of the signed OTA config state — About's answer to
/// "is the remote config channel actually alive?" It reads the SAME mirror
/// values OtaConfigSync writes (both-CDN consensus + SHA-256 verified), so
/// an unsynced phone honestly says "pending" instead of faking numbers.
/// v3.4.6.l — THE PENDING FLIP (video_2 04:10): the row re-reads the LIVE
/// mirror the moment the About page opens (the native sync may have landed
/// after the app's startup hydration — the row used to keep showing the
/// start-of-process snapshot forever). One refresh on init + on resume.
class _RemoteConfigRow extends StatefulWidget {
  const _RemoteConfigRow();

  @override
  State<_RemoteConfigRow> createState() => _RemoteConfigRowState();
}

class _RemoteConfigRowState extends State<_RemoteConfigRow>
    with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    try {
      await AppState.of(context).scope.refresh();
    } catch (_) {/* the pinned-defaults line stays honest */}
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final settings = AppState.of(context).settings;
    final synced = settings.otaSynced;
    final mirrors = SettingsMirror.lineCount(settings.otaBridgeMirrors);
    final feeds = SettingsMirror.lineCount(settings.otaIndexFeeds);
    final rules = SettingsMirror.lineCount(settings.otaAdRules);
    final String status;
    if (synced) {
      final when = DateTime.fromMillisecondsSinceEpoch(
          settings.otaSyncAtMs,
          isUtc: false);
      final clock =
          '${when.hour.toString().padLeft(2, '0')}:${when.minute.toString().padLeft(2, '0')}';
      final day = '${when.year}-${_two(when.month)}-${_two(when.day)}';
      status = 'Verified $day $clock · $mirrors mirrors · '
          '$feeds feeds · $rules ad rules';
    } else {
      status = 'Pending — pinned defaults in force';
    }
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 11),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            Expanded(
              child: Text('Remote config (signed)',
                  key: const ValueKey('remote-config-title'),
                  style: const TextStyle(
                      fontSize: 13.5, fontWeight: FontWeight.w600)),
            ),
            Icon(
                synced
                    ? mi.Icons.verified_rounded
                    : mi.Icons.hourglass_empty_rounded,
                size: 18,
                color: scheme.primary),
          ]),
          const SizedBox(height: 2),
          Text(status,
              key: const ValueKey('remote-config-status'),
              style: TextStyle(
                  fontSize: 11.5,
                  fontWeight: FontWeight.w500,
                  color: scheme.onSurfaceVariant)),
        ],
      ),
    );
  }

  static String _two(int v) => v.toString().padLeft(2, '0');
}

// ── v3.4.6.h — THE MATERIAL YOU ↔ MANUAL ACCENT INTERLOCK (one shared path) ─

/// THE SMART INTERLOCK, shared by the UI and the H8 live audit: picking ANY
/// manual accent (a preset swatch or a #RRGGBB hex from the Color Studio)
/// automatically turns OFF Material You dynamic color and repaints with the
/// chosen accent — the v3.4.6.g settings silently fought each other (both
/// ON, the toggle ignoring the user's tap). Returns the feedback line so
/// both callers show the SAME crisp 1-line message.
String applyManualAccent(
  AppStateNotifier scope, {
  int? preset,
  String? hex,
}) {
  if (preset != null) {
    scope
      ..putBool(SettingsMirror.kDynamicColor, false)
      ..putString(SettingsMirror.kAccentHex, '')
      ..putInt(SettingsMirror.kAccent, preset);
    return 'Switched to custom accent';
  }
  if (hex != null) {
    scope
      ..putBool(SettingsMirror.kDynamicColor, false)
      ..putString(SettingsMirror.kAccentHex, hex);
    return 'Switched to custom accent';
  }
  return '';
}

/// One crisp feedback line under the accent row (the interlock's visible
/// confirmation — a silent repaint is indistinguishable from a dead button).
/// v3.4.6.j — rides the TOP floating glass pill (the SnackBar is dead).
void _sayAccentFeedback(BuildContext context, String message) {
  if (!context.mounted) return;
  showMornyeGlassNotification(context, message: message);
}

// ── THE LIVE PREVIEW CARD ────────────────────────────────────────────────────

/// One glance at every visual setting at once: a glass card whose clarity,
/// outline, accent and tint react LIVE to the Quick Settings above it, with
/// a Sinhala subtitle line styled live (size/color/background) and the
/// midnight dimmer vignetting the whole card when on.
class _LivePreviewCard extends StatelessWidget {
  const _LivePreviewCard();

  @override
  Widget build(BuildContext context) {
    final state = AppState.of(context);
    final s = state.settings;
    final scheme = Theme.of(context).colorScheme;
    final accent = s.accentHex.isEmpty
        ? scheme.primary
        : _parseHex(s.accentHex, scheme.primary);
    final glassAlpha = 0.15 + (s.glassClarity / 100.0) * 0.55;
    final fontSp = s.i(SettingsMirror.kFontSpKey, 16).toDouble();
    final dim = s.b(SettingsMirror.kMidnightDimmer, false)
        ? s.i(SettingsMirror.kMidnightDimPct, 30) / 100.0
        : 0.0;
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        // gradient "page content" the glass sits over — the clarity slider
        // visibly changes how much of it shows through.
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            scheme.primaryContainer.withValues(alpha: 0.75),
            scheme.tertiaryContainer.withValues(alpha: 0.6),
            scheme.surfaceContainerHighest,
          ],
        ),
        borderRadius: BorderRadius.circular(28),
      ),
      foregroundDecoration: BoxDecoration(
        borderRadius: BorderRadius.circular(28),
        color: Colors.black.withValues(alpha: dim * 0.6),
      ),
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: scheme.surface.withValues(alpha: glassAlpha),
          borderRadius: BorderRadius.circular(20),
          border: s.glassOutlines
              ? Border.all(
                  color: scheme.onSurface.withValues(alpha: 0.25))
              : null,
          boxShadow: [
            BoxShadow(
              color: accent.withValues(alpha: 0.25),
              blurRadius: 18,
              blurStyle: BlurStyle.outer,
            ),
          ],
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Icon(mi.Icons.movie_rounded, color: accent, size: 20),
                const SizedBox(width: 8),
                Text('Live preview',
                    style: TextStyle(
                        color: scheme.onSurface,
                        fontSize: 13.5,
                        fontWeight: FontWeight.w700)),
                const Spacer(),
                Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                  decoration: BoxDecoration(
                    color: accent.withValues(alpha: 0.2),
                    borderRadius: BorderRadius.circular(999),
                    border: Border.all(color: accent.withValues(alpha: 0.6)),
                  ),
                  child: Text('glass ${s.glassClarity}%',
                      style: TextStyle(
                          color: scheme.onSurface, fontSize: 10.5)),
                ),
              ],
            ),
            const SizedBox(height: 18),
            // The live Sinhala subtitle line — v3.4.6.h: the FONT also
            // reacts instantly (the SHARED resolver the PlayerScreen overlay
            // paints with — noto | system | serif | cinema are all visibly
            // distinct; size, color, background react as before).
            Center(
              child: Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                decoration: BoxDecoration(
                  color: _subBg(s, scheme),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(
                  'ශ්‍රී ලංකාවේ සිනමා උපසිරැසි',
                  textAlign: TextAlign.center,
                  style: resolveSubtitleTextStyle(
                    s,
                    base: TextStyle(
                      fontSize: fontSp.clamp(12.0, 36.0),
                      height: 1.35,
                      color: _subColor(s, scheme),
                      fontWeight:
                          s.b(SettingsMirror.kSubBold, false)
                              ? FontWeight.w700
                              : FontWeight.w500,
                    ),
                  ),
                ),
              ),
            ),
            const SizedBox(height: 18),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [
                _pill(accent, scheme, 'Accent'),
                _pill(scheme.onSurfaceVariant, scheme,
                    s.b(SettingsMirror.kMotion, true) ? 'Motion on' : 'Motion off'),
                _pill(scheme.onSurfaceVariant, scheme,
                    s.b(SettingsMirror.kMidnightDimmer, false) ? 'Dimmed' : 'Bright'),
                _pill(scheme.onSurfaceVariant, scheme,
                    subtitleFontLabel(s.s(SettingsMirror.kFontFamily, 'noto'))),
              ],
            ),
          ],
        ),
      ),
    );
  }

  static Widget _pill(Color color, ColorScheme scheme, String label) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
            width: 8,
            height: 8,
            decoration: BoxDecoration(shape: BoxShape.circle, color: color)),
        const SizedBox(width: 5),
        Text(label,
            style: TextStyle(
                color: scheme.onSurfaceVariant, fontSize: 10.5)),
      ],
    );
  }

  static Color _parseHex(String hex, Color fallback) {
    final h = hex.replaceFirst('#', '');
    if (h.length != 6) return fallback;
    final v = int.tryParse('FF$h', radix: 16);
    return v == null ? fallback : Color(v);
  }

  static Color _subColor(SettingsMirror s, ColorScheme scheme) {
    const presets = [
      Color(0xFFFFFFFF), Color(0xFFFFD866), Color(0xFF7FE0C8),
      Color(0xFF9FC7FF), Color(0xFFFFA8C5), Color(0xFFE2C7FF),
    ];
    if (s.accentHex.isNotEmpty) return _parseHex(s.accentHex, Colors.white);
    return presets[s.i(SettingsMirror.kSubColor, 0)
        .clamp(0, presets.length - 1)];
  }

  static Color? _subBg(SettingsMirror s, ColorScheme scheme) {
    final style = s.s(SettingsMirror.kSubBg, 'pill');
    final opacity =
        s.i(SettingsMirror.kSubBgOpacity, 40) / 100.0;
    if (style == 'outline' || style == 'shadow') return null;
    return Colors.black.withValues(alpha: opacity * 0.9);
  }
}

// ── the advanced group bodies ────────────────────────────────────────────────

class _AppearanceNavRows extends StatelessWidget {
  const _AppearanceNavRows();

  @override
  Widget build(BuildContext context) {
    final state = AppState.of(context);
    final scope = state.scope;
    final s = state.settings;
    return Column(children: [
      _SegmentedRow(
        title: 'Interface scale',
        labels: const ['Compact', 'Comfortable', 'Spacious'],
        selected: ['compact', 'comfortable', 'spacious']
            .indexOf(s.s(SettingsMirror.kDensity, 'comfortable'))
            .clamp(0, 2),
        onChanged: (i) => scope.putString(
            SettingsMirror.kDensity,
            ['compact', 'comfortable', 'spacious'][i]),
      ),
      _SegmentedRow(
        title: 'Bottom bar',
        labels: const ['Floating', 'Docked'],
        selected: s.s(SettingsMirror.kBottomBarStyle, 'floating') == 'floating'
            ? 0
            : 1,
        onChanged: (i) => scope.putString(SettingsMirror.kBottomBarStyle,
            i == 0 ? 'floating' : 'docked'),
      ),
      _SwitchRow(
        title: 'Tab labels',
        value: s.b(SettingsMirror.kBottomBarLabels, true),
        onChanged: (v) => scope.putBool(
            SettingsMirror.kBottomBarLabels, v),
      ),
      _SwitchRow(
        title: 'Hero animations',
        value: s.b(SettingsMirror.kAccentGlow, true),
        onChanged: (v) =>
            scope.putBool(SettingsMirror.kAccentGlow, v),
      ),
    ]);
  }
}

class _SubtitleStudioRows extends StatelessWidget {
  const _SubtitleStudioRows();

  /// v3.4.6.l — the studio slider previews are plumbed through the nearest
  /// _PlaybackSubtitlesPageState when present (the page owns the drafts).
  static _PlaybackSubtitlesPageState? _pageOf(BuildContext c) =>
      c.findAncestorStateOfType<_PlaybackSubtitlesPageState>();

  @override
  Widget build(BuildContext context) {
    final state = AppState.of(context);
    final scope = state.scope;
    final s = state.settings;
    return Column(children: [
      _SegmentedRow(
        title: 'Subtitle font',
        // v3.4.6.h — ALL FOUR shipped faces (Cinema Bold was missing from
        // the v3.4.6.g selector) — every choice is a distinct TextStyle via
        // the SHARED resolveSubtitleTextStyle the player overlay uses.
        // v3.4.6.i — SHORTENED labels (Noto / System / Serif / Bold) so the
        // four-segment control can never ellipsize or overlap on a narrow
        // phone; the full names stay in the row subtitle below the control.
        labels: const ['Noto', 'System', 'Serif', 'Bold'],
        selected: kSubtitleFontChoices
            .indexOf(s.s(SettingsMirror.kFontFamily, 'noto'))
            .clamp(0, 3),
        onChanged: (i) => scope.putString(
            SettingsMirror.kFontFamily, kSubtitleFontChoices[i]),
      ),
      _SegmentedRow(
        title: 'Background style',
        labels: const ['Pill', 'Box', 'Outline', 'Shadow'],
        selected: ['pill', 'box', 'outline', 'shadow']
            .indexOf(s.s(SettingsMirror.kSubBg, 'pill'))
            .clamp(0, 3),
        onChanged: (i) => scope.putString(SettingsMirror.kSubBg,
            ['pill', 'box', 'outline', 'shadow'][i]),
      ),
      _SliderRow(
        title: 'Background opacity',
        value: s.i(SettingsMirror.kSubBgOpacity, 40) / 95.0,
        labelFor: (v) => '${(v * 95).round().clamp(0, 95)}%',
        // v3.4.6.l — the live draft (null on drag end) feeds the page preview
        onPreview: (v) => _pageOf(context)?._bgOpacityDraft.value = v,
        onCommit: (v) => scope.putInt(
            SettingsMirror.kSubBgOpacity, (v * 95).round().clamp(0, 95)),
      ),
      _SliderRow(
        title: 'Vertical position',
        value: (s.i(SettingsMirror.kSubBottomPct, 8) - 2) / 33.0,
        labelFor: (v) => '${(2 + v * 33).round().clamp(2, 35)}%',
        onPreview: (v) => _pageOf(context)?._positionDraft.value = v,
        onCommit: (v) => scope.putInt(SettingsMirror.kSubBottomPct,
            (2 + v * 33).round().clamp(2, 35)),
      ),
      _SegmentedRow(
        title: 'Sync step',
        labels: const ['±0.1s', '±0.25s', '±0.5s'],
        selected: [100, 250, 500]
            .indexOf(s.i(SettingsMirror.kSyncStepMs, 250))
            .clamp(0, 2),
        // v3.4.6.h — THE INTEGER→LONG CRASH KILLER'S DART HALF: this control
        // is the EXACT v3.4.6.g poison writer (putInt('subSyncStepMs')) that
        // crashed gatherSettings with ClassCastException and broke Settings,
        // Backup, the rebuild and the search-engine probes 30/56/65/70. It
        // now rides the canonical putLong lane (and Java canonicalizes every
        // Number anyway — defense in depth on both shores).
        onChanged: (i) => scope.putLong(
            SettingsMirror.kSyncStepMs, [100, 250, 500][i]),
      ),
      _SwitchRow(
        title: 'Auto-extract .zip subs',
        value: s.b(SettingsMirror.kAutoZip, true),
        onChanged: (v) => scope.putBool(SettingsMirror.kAutoZip, v),
      ),
      _SwitchRow(
        title: 'Auto-translate non-Sinhala',
        value: s.b(SettingsMirror.kAutoTranslateNonSi, true),
        onChanged: (v) => scope.putBool(
            SettingsMirror.kAutoTranslateNonSi, v),
      ),
      _SegmentedRow(
        title: 'Default aspect',
        labels: const ['Fit', 'Fill', '16:9', 'Stretch'],
        selected: ['fit', 'fill', '16:9', 'stretch']
            .indexOf(s.s(SettingsMirror.kAspectMode, 'fit'))
            .clamp(0, 3),
        onChanged: (i) => scope.putString(SettingsMirror.kAspectMode,
            ['fit', 'fill', '16:9', 'stretch'][i]),
      ),
    ]);
  }
}

class _AnantPlayRows extends StatelessWidget {
  const _AnantPlayRows();

  @override
  Widget build(BuildContext context) {
    return Column(children: [
      // v3.4.6.i — the OFFICIAL ANANTPLAY UPDATE PILL (Settings side): the
      // installed me.champdev.weblock vs the latest AnantPlay/android release,
      // 1-tap Update Now through the fixed in-app installer.
      const _OfficialAnantPlayUpdateRow(),
      _ActionRow(
        title: 'Check / install official AnantPlay',
        subtitle: 'In-app downloader + 1-tap PackageInstaller',
        onTap: () async {
          final installed = await AnantPlay.instance.officialInstalled();
          if (!context.mounted) return;
          if (installed) {
            await AnantPlay.instance.openOfficialApp();
          } else {
            showInstallerSheet(
                context); // same-frame after the mounted guard — safe
          }
        },
      ),
      _ActionRow(
        title: 'Re-run first-time setup wizard',
        subtitle: 'The 6-slide interactive guide',
        onTap: () => AppState.of(context).scope.rerunOnboarding(),
      ),
    ]);
  }
}

/// v3.4.6.k — THE MULTI-INDEX URL MANAGER (Part_02 03:38, Part_03 02:55): the
/// list of ALL built-in + unlimited user-added index URLs — each with 1-Tap
/// Copy & Delete — plus `+ Add Index URL`. Replaces the v3.4.6.j single
/// `Saved Index URL` box (and the broken unlabeled empty box + Save inside
/// the GUIDED SETUP card, which is deleted entirely).
class _MultiIndexManager extends StatefulWidget {
  const _MultiIndexManager();

  @override
  State<_MultiIndexManager> createState() => _MultiIndexManagerState();
}

class _MultiIndexManagerState extends State<_MultiIndexManager> {
  static const _builtIns = [
    ('Sri Lanka IPTV',
        'https://raw.githubusercontent.com/dictationb1/srilanka-anantplay/main/index.json'),
    ('Sinhala Subtitle Bridge',
        'https://anantplay-si-subs-bridge.techworldlk.workers.dev/index.json'),
    ('Official AnantPlay Index',
        'https://raw.githubusercontent.com/AnantPlay/index/main/index.json'),
  ];

  List<(String, String)> _customs(String raw) => [
        for (final line in raw.split('\n'))
          if (line.trim().isNotEmpty && line.contains('|'))
            (
              line.substring(0, line.indexOf('|')).trim(),
              line.substring(line.indexOf('|') + 1).trim()
            )
      ];

  String _serialize(List<(String, String)> customs) => [
        for (final (name, url) in customs)
          if (url.trim().isNotEmpty) '$name|$url',
      ].join('\n');

  void _save(List<(String, String)> customs) =>
      AppState.of(context).scope.putString(
          SettingsMirror.kIndexUrls, _serialize(customs));

  void _copy(String url) {
    AnantPlay.instance.copyToClipboard(url);
    showMornyeGlassNotification(context, message: 'Index URL copied');
  }

  void _add() async {
    final nameCtl = TextEditingController();
    final urlCtl = TextEditingController();
    await showDialog<void>(
      context: context,
      builder: (dctx) => AlertDialog(
        title: const Text('Add Index URL'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: nameCtl,
              decoration: const InputDecoration(
                  labelText: 'Name', hintText: 'e.g. My IPTV index'),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: urlCtl,
              decoration: const InputDecoration(
                  labelText: 'URL', hintText: 'https://…/index.json'),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dctx).pop(),
            child: const Text('Cancel'),
          ),
          FilledButton.tonal(
            onPressed: () {
              final raw =
                  AppState.of(context).settings.s(SettingsMirror.kIndexUrls, '');
              final customs = _customs(raw)
                ..add((
                  nameCtl.text.trim().isEmpty
                      ? 'Custom index'
                      : nameCtl.text.trim(),
                  urlCtl.text.trim()
                ));
              _save(customs);
              Navigator.of(dctx).pop();
            },
            child: const Text('Add'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final s = AppState.of(context).settings;
    final customs = _customs(s.s(SettingsMirror.kIndexUrls, ''));
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (final (name, url) in _builtIns)
          _IndexUrlRow(
            name: name,
            url: url,
            builtin: true,
            onCopy: () => _copy(url),
          ),
        for (final entry in customs)
          _IndexUrlRow(
            name: entry.$1,
            url: entry.$2,
            builtin: false,
            onCopy: () => _copy(entry.$2),
            onDelete: () => _save(customs
                .where((c) => c != entry)
                .toList()),
          ),
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 10, 16, 12),
          child: OutlinedButton.icon(
            icon: Icon(mi.Icons.add_rounded,
                size: 18, color: scheme.primary),
            label: const Text('Add Index URL'),
            onPressed: _add,
          ),
        ),
      ],
    );
  }
}

class _IndexUrlRow extends StatelessWidget {
  const _IndexUrlRow({
    required this.name,
    required this.url,
    required this.builtin,
    required this.onCopy,
    this.onDelete,
  });

  final String name;
  final String url;
  final bool builtin;
  final VoidCallback onCopy;
  final VoidCallback? onDelete;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
      child: Row(
        children: [
          Icon(builtin ? mi.Icons.star_rounded : mi.Icons.link_rounded,
              size: 18, color: scheme.primary),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                        fontSize: 13.5,
                        fontWeight: FontWeight.w700,
                        color: scheme.onSurface)),
                const SizedBox(height: 2),
                Text(url,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                        fontSize: 11, color: scheme.onSurfaceVariant)),
              ],
            ),
          ),
          const SizedBox(width: 6),
          FilledButton.tonal(
            onPressed: onCopy,
            style: FilledButton.styleFrom(
                padding:
                    const EdgeInsets.symmetric(horizontal: 12),
                minimumSize: const Size(0, 34)),
            child: const Text('Copy', style: TextStyle(fontSize: 12)),
          ),
          if (onDelete != null) ...[
            const SizedBox(width: 6),
            IconButton(
              tooltip: 'Delete',
              icon: Icon(mi.Icons.delete_outline_rounded,
                  size: 20, color: scheme.error),
              onPressed: onDelete,
            ),
          ],
        ],
      ),
    );
  }
}

/// v3.4.6.k — THE STICKY LIVE SINHALA SUBTITLE PREVIEW CARD: one reference
/// line, styled by the LIVE subtitle studio settings, at the very top of
/// Playback & Subtitles. Same resolver as the player (resolveSubtitleTextStyle),
/// same bg style/opacity pill, same vertical position anchor (as a visual
/// hint band, scaled into the card) — every knob below moves it instantly.
class _LiveSubtitlePreviewCard extends StatelessWidget {
  const _LiveSubtitlePreviewCard({
    required this.s,
    this.liveSize,
    this.liveBgOpacity,
    this.livePosition,
  });

  final SettingsMirror s;

  /// v3.4.6.l — the LIVE DRAG DRAFTS (video_2 02:55): while a slider thumb
  /// is STILL MOVING these carry the in-flight normalized value; the card
  /// paints the draft (and the sp readout) at 60/120 fps before any commit.
  final double? liveSize;
  final double? liveBgOpacity;
  final double? livePosition;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    // v3.4.6.l — the draft overrides the committed mirror value WHILE the
    // thumb moves (null drafts = the resting committed state).
    final int sizeSp = (liveSize == null
            ? s.i(SettingsMirror.kFontSpKey, 14)
            : (12 + liveSize! * 24).round())
        .clamp(12, 36);
    final String bg = s.s(SettingsMirror.kSubBg, 'pill');
    final int bgOpacity = (liveBgOpacity == null
            ? s.i(SettingsMirror.kSubBgOpacity, 40)
            : (liveBgOpacity! * 95).round())
        .clamp(0, 95);
    // v3.4.6.k — the outline lane rides the existing subBold preference.
    final bool outline = s.b(SettingsMirror.kSubBold, false);
    final Color subColor = _subColorOf(s);
    final TextStyle resolved = resolveSubtitleTextStyle(
      s,
      base: TextStyle(
        fontSize: sizeSp.toDouble(),
        height: 1.28,
        color: subColor,
      ),
    );
    final BoxDecoration pillBg = bg == 'pill'
        ? BoxDecoration(
            color: Color(0xFF000000).withValues(alpha: bgOpacity / 100),
            borderRadius: BorderRadius.circular(10),
          )
        : bg == 'box'
            ? BoxDecoration(
                color: Color(0xFF000000).withValues(alpha: bgOpacity / 100),
                borderRadius: BorderRadius.circular(4),
              )
            : bg == 'outline'
                ? BoxDecoration(
                    borderRadius: BorderRadius.circular(6),
                    border: Border.all(
                        color: subColor.withValues(alpha: bgOpacity / 100),
                        width: 1.2),
                  )
                : BoxDecoration(); // 'shadow' paints via the text shadows
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Icon(mi.Icons.subtitles_rounded,
                  size: 15, color: scheme.primary),
              const SizedBox(width: 6),
              Text('Live Sinhala subtitle preview',
                  style: TextStyle(
                      fontSize: 11.5,
                      fontWeight: FontWeight.w700,
                      color: scheme.onSurfaceVariant)),
              const Spacer(),
              Text('$sizeSp sp',
                  style: TextStyle(
                      fontSize: 11.5,
                      fontWeight: FontWeight.w600,
                      color: scheme.onSurfaceVariant)),
            ],
          ),
          const SizedBox(height: 8),
          Container(
            height: 96,
            // v3.4.6.l — the LIVE vertical-position draft: the preview line
            // glides within the card while the position slider is dragged.
            alignment: livePosition == null
                ? Alignment.center
                : Alignment(0, (livePosition! * 2 - 1).clamp(-1.0, 1.0)),
            decoration: BoxDecoration(
              color: scheme.surfaceContainerHighest,
              borderRadius: BorderRadius.circular(14),
            ),
            child: Container(
              margin: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
              padding:
                  const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              decoration: pillBg,
              child: Text(
                'ශ්‍රී ලංකාවේ සිනමා උපසිරැසි',
                textAlign: TextAlign.center,
                style: resolved.copyWith(
                  shadows: bg == 'shadow'
                      ? [
                          Shadow(
                              color: Color(0xFF000000)
                                  .withValues(alpha: bgOpacity / 100),
                              blurRadius: 4),
                        ]
                      : (outline
                          ? [
                              Shadow(color: Color(0x99000000), blurRadius: 2),
                              Shadow(color: Color(0x99000000), blurRadius: 2),
                            ]
                          : null),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  static Color _subColorOf(SettingsMirror s) {
    final hex = s.s(SettingsMirror.kSubColor, '#FFF2F7F7');
    final v = int.tryParse(hex.replaceFirst('#', ''), radix: 16);
    return v == null ? const Color(0xFFF2F7F7) : Color(v | 0xFF000000);
  }
}

/// v3.4.6.i — the Settings-side official-AnantPlay update pill: loads the
/// native officialUpdateFacts once, and when a strictly newer official
/// release exists renders the sleek `AnantPlay Update Available (vX → vY) —
/// Update Now` row (1 tap rides the fixed installer).
class _OfficialAnantPlayUpdateRow extends StatefulWidget {
  const _OfficialAnantPlayUpdateRow();

  @override
  State<_OfficialAnantPlayUpdateRow> createState() =>
      _OfficialAnantPlayUpdateRowState();
}

class _OfficialAnantPlayUpdateRowState
    extends State<_OfficialAnantPlayUpdateRow> {
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
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 10),
      child: Material(
        color: scheme.primaryContainer.withValues(alpha: 0.55),
        borderRadius: BorderRadius.circular(16),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: () => AnantPlay.instance.installOfficialAnantPlay(),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(14, 12, 12, 12),
            child: Row(
              children: [
                Icon(mi.Icons.upgrade_rounded,
                    color: scheme.primary, size: 22),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    'AnantPlay Update Available (${facts.installed} → ${facts.latest})',
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 12.5,
                      fontWeight: FontWeight.w600,
                      color: scheme.onSurface,
                    ),
                  ),
                ),
                TextButton(
                  style: TextButton.styleFrom(
                    minimumSize: const Size(72, 44),
                    tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  ),
                  onPressed: () =>
                      AnantPlay.instance.installOfficialAnantPlay(),
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


class _BrowserRows extends StatelessWidget {
  const _BrowserRows();

  @override
  Widget build(BuildContext context) {
    final state = AppState.of(context);
    final scope = state.scope;
    final s = state.settings;
    return Column(children: [
      _SegmentedRow(
        title: 'Omnibar position',
        labels: const ['Bottom', 'Top'],
        selected:
            s.s(SettingsMirror.kOmnibarPos, 'bottom') == 'bottom' ? 0 : 1,
        onChanged: (i) => scope.putString(
            SettingsMirror.kOmnibarPos, i == 0 ? 'bottom' : 'top'),
      ),
      _SegmentedRow(
        title: 'Speed dial columns',
        labels: const ['2', '3', '4'],
        selected:
            (s.i(SettingsMirror.kSpeedDialCols, 3) - 2).clamp(0, 2),
        onChanged: (i) =>
            scope.putInt(SettingsMirror.kSpeedDialCols, i + 2),
      ),
      _SegmentedRow(
        title: 'Search engine',
        // v3.4.6.d — GOOGLE IS THE DEFAULT (the v3.4.6.c default 'ddg'
        // resolves to Google too through AppPrefs.normalizeSearchEngine).
        // v3.4.6.i — SHORTENED engine labels (Google / DDG / Start / Brave)
        // so four choices never ellipsize on a narrow phone; the full names
        // ride in the row subtitle.
        labels: const ['Google', 'DDG', 'Start', 'Brave'],
        selected: ['google', 'ddg', 'startpage', 'brave']
            .indexOf(s.s(SettingsMirror.kSearchEngine, 'google'))
            .clamp(0, 3),
        onChanged: (i) => scope.putString(SettingsMirror.kSearchEngine,
            ['google', 'ddg', 'startpage', 'brave'][i]),
      ),
      _SwitchRow(
        title: 'Ad-block',
        value: s.b(SettingsMirror.kAdBlock, true),
        onChanged: (v) => scope.putBool(SettingsMirror.kAdBlock, v),
      ),
      _SwitchRow(
        title: 'Reader dark',
        value: s.b(SettingsMirror.kReaderDark, true),
        onChanged: (v) => scope.putBool(SettingsMirror.kReaderDark, v),
      ),
      _SwitchRow(
        title: 'Desktop site',
        value: s.b(SettingsMirror.kWebDesktopUa, false),
        onChanged: (v) =>
            scope.putBool(SettingsMirror.kWebDesktopUa, v),
      ),
    ]);
  }
}

class _CastRows extends StatelessWidget {
  const _CastRows();

  @override
  Widget build(BuildContext context) {
    final state = AppState.of(context);
    final scope = state.scope;
    final s = state.settings;
    return Column(children: [
      _SwitchRow(
        title: 'DLNA renderer',
        value: s.b(SettingsMirror.kDlna, true),
        onChanged: (v) => scope.putBool(SettingsMirror.kDlna, v),
      ),
      _SwitchRow(
        title: 'mDNS announce',
        value: s.b(SettingsMirror.kMdns, true),
        onChanged: (v) => scope.putBool(SettingsMirror.kMdns, v),
      ),
      _ActionRow(
        title: 'Receiver name / port',
        subtitle:
            '${s.s(SettingsMirror.kReceiverName, 'AnantPlay Player')} · :${s.i(SettingsMirror.kReceiverPort, 8765)}',
        onTap: () => _editReceiver(context),
      ),
      _ActionRow(
        title: 'Ping worker',
        subtitle: s.s(SettingsMirror.kWorkerUrl,
            'https://anantplay-si-subs-bridge.techworldlk.workers.dev'),
        onTap: () => pingWorkerWithFeedback(context),
      ),
    ]);
  }

  static void _editReceiver(BuildContext context) {
    final scope = AppState.of(context).scope;
    final s = AppState.of(context).settings;
    final name = TextEditingController(
        text: s.s(SettingsMirror.kReceiverName, 'AnantPlay Player'));
    final port = TextEditingController(
        text: s.i(SettingsMirror.kReceiverPort, 8765).toString());
    final scheme = Theme.of(context).colorScheme;
    showDialog<void>(
      context: context,
      builder: (d) => AlertDialog(
        backgroundColor: scheme.surfaceContainerHigh,
        title: Text('Receiver',
            style: TextStyle(color: scheme.onSurface)),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: name,
              decoration: const InputDecoration(labelText: 'Name'),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: port,
              keyboardType: TextInputType.number,
              inputFormatters: [FilteringTextInputFormatter.digitsOnly],
              decoration: const InputDecoration(labelText: 'Port'),
            ),
          ],
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(d), child: const Text('Cancel')),
          FilledButton(
            onPressed: () {
              scope.putString(
                  SettingsMirror.kReceiverName, name.text.trim());
              final p = int.tryParse(port.text.trim()) ?? 8765;
              scope.putInt(
                  SettingsMirror.kReceiverPort, p.clamp(1024, 65535));
              Navigator.pop(d);
            },
            child: const Text('Save'),
          ),
        ],
      ),
    );
  }
}

class _BackupRows extends StatelessWidget {
  const _BackupRows();

  @override
  Widget build(BuildContext context) {
    return Column(children: [
      _ActionRow(
          title: 'Export backup',
          subtitle: 'JSON to the app folder',
          onTap: () => _export(context)),
      _ActionRow(
          title: 'Import backup',
          subtitle: 'Previewed, then applied',
          onTap: () => _import(context)),
      _ActionRow(
          title: 'Reset all settings',
          subtitle: 'History stays',
          destructive: true,
          onTap: () => _reset(context)),
    ]);
  }

  Future<void> _export(BuildContext context) async {
    showMornyeGlassNotification(context, message: 'Exporting backup…');
    try {
      final json = await AnantPlay.instance.exportBackup();
      if (!context.mounted) return;
      showMornyeGlassNotification(context,
          message: 'Backup exported · ${json.length} bytes ✓');
    } catch (error) {
      if (context.mounted) {
        showMornyeGlassNotification(context,
            message: 'Export failed · $error', error: true);
      }
    }
  }

  Future<void> _import(BuildContext context) async {
    // v3.4.6.f — the REAL apply path: paste the exported JSON, preview it
    // through SettingsBackup.parseImport, then apply via native importBackup.
    final controller = TextEditingController();
    final scheme = Theme.of(context).colorScheme;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (d) => AlertDialog(
        backgroundColor: scheme.surfaceContainerHigh,
        title: Text('Import backup',
            style: TextStyle(color: scheme.onSurface)),
        content: TextField(
          controller: controller,
          maxLines: 8,
          decoration: const InputDecoration(hintText: 'Paste backup JSON here'),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(d, false),
              child: const Text('Cancel')),
          FilledButton(
              onPressed: () => Navigator.pop(d, true),
              child: const Text('Import')),
        ],
      ),
    );
    if (confirmed != true) return;
    final json = controller.text.trim();
    if (json.isEmpty) {
      if (context.mounted) {
        showMornyeGlassNotification(context,
            message: 'Import failed · empty backup', error: true);
      }
      return;
    }
    try {
      final preview = await AnantPlay.instance.importPreview(json);
      if (!context.mounted) return;
      if (!preview.ok) {
        showMornyeGlassNotification(context,
            message: 'Import failed · ${preview.error}', error: true);
        return;
      }
      await importBackupWithFeedback(context, json);
    } catch (error) {
      if (context.mounted) {
        showMornyeGlassNotification(context,
            message: 'Import failed · $error', error: true);
      }
    }
  }

  Future<void> _reset(BuildContext context) async {
    final scheme = Theme.of(context).colorScheme;
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: scheme.surfaceContainerHigh,
        title: Text('Reset all settings?',
            style: TextStyle(color: scheme.onSurface)),
        content: Text('Every preference returns to its default. History stays.',
            style: TextStyle(color: scheme.onSurfaceVariant)),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Cancel')),
          TextButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Reset')),
        ],
      ),
    );
    if (ok == true) {
      await AnantPlay.instance.resetAll();
      if (context.mounted) await AppState.of(context).scope.refresh();
    }
  }
}

// ── shared tiles ────────────────────────────────────────────────────────────

class _SectionHeader extends StatelessWidget {
  const _SectionHeader(this.title);

  final String title;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    // v3.4.6.b — connected-list contract: the group owns the horizontal
    // inset, so the header pads itself to the same 16 dp the group uses
    // upstream (settingsHorizontalInset at phone width).
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 6, 16, 8),
      child: Text(title.toUpperCase(),
          style: TextStyle(
              color: scheme.onSurfaceVariant,
              fontSize: 11.5,
              fontWeight: FontWeight.w700,
              letterSpacing: 0.8)),
    );
  }
}

/// v3.4.6.c — the expandable connected group: a header row that expands /
/// collapses its body inline (the Mornye connected-list look, one surface,
/// dividers between rows, radiusCard = 24).
class _ExpandableGroup extends StatefulWidget {
  const _ExpandableGroup({
    required this.title,
    required this.icon,
    required this.children,
  });

  final String title;
  final IconData icon;
  final WidgetBuilder children;

  @override
  State<_ExpandableGroup> createState() => _ExpandableGroupState();
}

class _ExpandableGroupState extends State<_ExpandableGroup> {
  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 10),
      child: Material(
        color: scheme.surfaceContainerHigh.withValues(alpha: 0.35),
        borderRadius: BorderRadius.circular(24),
        clipBehavior: Clip.antiAlias,
        child: Theme(
          data: Theme.of(context).copyWith(dividerColor: Colors.transparent),
          child: ExpansionTile(
            dense: true,
            tilePadding: const EdgeInsets.symmetric(horizontal: 16),
            childrenPadding: const EdgeInsets.only(bottom: 8),
            backgroundColor: Colors.transparent,
            collapsedBackgroundColor: Colors.transparent,
            iconColor: scheme.onSurfaceVariant,
            title: Row(
              children: [
                // v3.4.6.i — PERMANENT LAW #3: the group glyphs ride the crisp
                // Mornye/Cupertino remap (mornyeIconFor) — zero mismatched
                // boxy Material icons on Settings.
                Icon(mornyeIconFor(widget.icon),
                    size: 20, color: scheme.primary),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(widget.title,
                      style: TextStyle(
                          color: scheme.onSurface,
                          fontSize: 14.5,
                          fontWeight: FontWeight.w600)),
                ),
              ],
            ),
            children: [widget.children(context)],
          ),
        ),
      ),
    );
  }
}

class _ThemeCards extends StatelessWidget {
  const _ThemeCards({required this.current});

  final String current;

  static const _themes = [
    ('system', 'System', mi.Icons.brightness_auto_rounded),
    ('dark', 'Midnight', mi.Icons.dark_mode_rounded),
    ('amoled', 'AMOLED', mi.Icons.contrast_rounded),
    ('light', 'Light', mi.Icons.light_mode_rounded),
  ];

  @override
  Widget build(BuildContext context) {
    // v3.4.6.b — inside the connected group, pad the row to the group's
    // own 16 dp inset (the group surface itself is edge-to-edge).
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      child: Row(
        children: [
          for (final (id, label, icon) in _themes)
            Expanded(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 4),
                child: _ThemeCard(
                    id: id, label: label, icon: icon, selected: current == id),
              ),
            ),
        ],
      ),
    );
  }
}

class _ThemeCard extends StatelessWidget {
  const _ThemeCard({
    required this.id,
    required this.label,
    required this.icon,
    required this.selected,
  });

  final String id;
  final String label;
  final IconData icon;
  final bool selected;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return GestureDetector(
      onTap: () =>
          AppState.of(context).scope.putString(SettingsMirror.kThemeMode, id),
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 12),
        decoration: BoxDecoration(
          // AMOLED states its #000000 contract right on the card.
          color: selected
              ? scheme.primary.withValues(alpha: 0.16)
              : id == 'amoled'
                  ? Colors.black
                  : scheme.surfaceContainerHighest.withValues(alpha: 0.4),
          borderRadius: BorderRadius.circular(14),
          border: Border.all(
              color:
                  selected ? scheme.primary : scheme.outlineVariant,
              width: selected ? 1.6 : 1),
        ),
        child: Column(
          children: [
            Icon(icon, color: scheme.primary, size: 20),
            const SizedBox(height: 6),
            Text(label,
                style: TextStyle(
                    color: id == 'amoled' && !selected
                        ? Colors.white70
                        : scheme.onSurface,
                    fontSize: 11.5,
                    fontWeight: FontWeight.w600)),
          ],
        ),
      ),
    );
  }
}

class _AccentRow extends StatelessWidget {
  const _AccentRow({required this.current, required this.hex});

  final int current;
  final String hex;

  static const _accents = [
    Color(0xFF7FCDD0), Color(0xFFFFB340), Color(0xFF63D668),
    Color(0xFF64D2FF), Color(0xFF7E7FF0), Color(0xFFF07FD0), Color(0xFFFF6482),
  ];

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    // v3.4.6.b — inside the connected group, pad to the group's 16 dp inset.
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            height: 44,
            child: Row(
              children: [
                for (var i = 0; i < _accents.length; i++)
                  Padding(
                    padding: const EdgeInsets.only(right: 10),
                    child: GestureDetector(
                      behavior: HitTestBehavior.opaque,
                      onTap: () {
                        // v3.4.6.h — THE SMART INTERLOCK: a preset tap turns
                        // OFF Material You dynamic color and repaints with the
                        // preset IMMEDIATELY (the v3.4.6.g row silently
                        // fought the toggle — both ON, the tap ignored). The
                        // same shared path the H8 audit drives.
                        _sayAccentFeedback(
                            context,
                            applyManualAccent(
                                AppState.of(context).scope,
                                preset: i));
                      },
                      child: Container(
                        width: 30,
                        height: 30,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          color: _accents[i],
                          border: current == i && hex.isEmpty
                              ? Border.all(
                                  color: scheme.onSurface, width: 2.5)
                              : null,
                        ),
                      ),
                    ),
                  ),
                const Spacer(),
                // v3.4.6.g — the custom #RRGGBB Color Studio entry
                // (K_ACCENT_HEX). THE REAL-PHONE FIX: the v3.4.6.f button was a
                // null-color Container — its hit-test only covered the 1.4 px
                // border ring and the # glyph, so a real finger tap landed
                // NOWHERE (the "dead #" bug). opaque behavior + a solid lane
                // background make the whole 40×30 target tappable, and the tap
                // opens the full Mornye Liquid-Glass Color Studio sheet.
                GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: () => _openColorStudio(context),
                  child: Container(
                    width: 44,
                    height: 32,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(10),
                      border: Border.all(
                          color: scheme.primary, width: 1.4),
                      // a SOLID lane: the target hit-tests everywhere now.
                      color: hex.isEmpty
                          ? scheme.primary.withValues(alpha: 0.14)
                          : _parse(hex),
                    ),
                    child: hex.isEmpty
                        ? Text('#',
                            style: TextStyle(
                                color: scheme.primary,
                                fontWeight: FontWeight.w700))
                        : Icon(mi.Icons.check_rounded,
                            size: 18, color: scheme.onPrimary),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 8),
          // v3.4.6.h — THE INTERLOCK INDICATOR ON THE ACCENT ROW: while
          // Material You is ON with a REAL OS scheme, the manual lane says so
          // ("Wallpaper colors active") — the two lanes can never silently
          // fight again; picking any swatch flips this off with feedback.
          // (When the OS has no scheme the honest caption below the toggle is
          // the single source of truth — no duplicate caption here.)
          ValueListenableBuilder<bool>(
            valueListenable: wallpaperSchemeAvailable,
            builder: (context, available, _) {
              final bool dynamicOn = AppState.of(context)
                  .settings
                  .b(SettingsMirror.kDynamicColor, false);
              if (!dynamicOn || !available) return const SizedBox.shrink();
              return Padding(
                padding: const EdgeInsets.only(bottom: 6),
                child: Row(
                  children: [
                    Icon(
                      mi.Icons.palette_rounded,
                      size: 13,
                      color: scheme.primary,
                    ),
                    const SizedBox(width: 5),
                    Text(
                      'Wallpaper colors active — manual accents off',
                      style: TextStyle(
                          color: scheme.onSurfaceVariant,
                          fontSize: 10.5,
                          fontWeight: FontWeight.w600),
                    ),
                  ],
                ),
              );
            },
          ),
          // v3.4.6.c — Material You dynamic color toggle (K_DYNAMIC_COLOR).
          _SwitchRow(
            title: 'Material You dynamic color',
            value: AppState.of(context)
                .settings
                .b(SettingsMirror.kDynamicColor, false),
            onChanged: (v) => AppState.of(context)
                .scope
                .putBool(SettingsMirror.kDynamicColor, v),
          ),
          // v3.4.6.g — THE HONEST CAPTION: with the toggle ON but no OS scheme,
          // say so — never fake the transformation. With a REAL scheme the
          // caption confirms the live wallpaper theming lane.
          Padding(
            padding: const EdgeInsets.fromLTRB(6, 2, 6, 0),
            child: ValueListenableBuilder<bool>(
              valueListenable: wallpaperSchemeAvailable,
              builder: (context, available, _) {
                final on = AppState.of(context)
                    .settings
                    .b(SettingsMirror.kDynamicColor, false);
                if (!on) return const SizedBox.shrink();
                return Row(
                  children: [
                    Icon(
                      available
                          ? mi.Icons.palette_rounded
                          : mi.Icons.info_outline_rounded,
                      size: 14,
                      color: available
                          ? scheme.primary
                          : scheme.onSurfaceVariant,
                    ),
                    const SizedBox(width: 6),
                    Expanded(
                      child: Text(
                        available
                            ? 'Live wallpaper ColorScheme — the whole app repaints from your wallpaper'
                            : kWallpaperUnavailableCaption,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                            color: scheme.onSurfaceVariant,
                            fontSize: 11),
                      ),
                    ),
                  ],
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  static Color _parse(String hex) {
    final h = hex.replaceFirst('#', '');
    final v =
        h.length == 6 ? int.tryParse('FF$h', radix: 16) : null;
    return v == null ? const Color(0xFF7FCDD0) : Color(v);
  }

  /// v3.4.6.g — THE MORNYE LIQUID-GLASS COLOR STUDIO SHEET: an interactive
  /// hue/saturation swatch grid, a live `#RRGGBB` hex input, a live preview
  /// pill, and `Apply Color` — persisting K_ACCENT_HEX and repainting the
  /// ENTIRE app the same frame (through _ThemedApp's AppState read).
  void _openColorStudio(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final scope = AppState.of(context).scope;
    final controller = TextEditingController(
        text: hex.isEmpty ? '#7FCDD0' : '#${hex.toUpperCase()}');
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      backgroundColor: scheme.surfaceContainerHigh,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
      ),
      builder: (sheet) {
        // the 24-swatch hue/saturation grid: every hue family at two
        // saturations — deterministic, tappable, live.
        const grid = <Color>[
          Color(0xFFFF6482), Color(0xFFFF8FA3), Color(0xFFFFB340), Color(0xFFFFCF70),
          Color(0xFF63D668), Color(0xFF93E096), Color(0xFF7FCDD0), Color(0xFFB0E4E6),
          Color(0xFF64D2FF), Color(0xFFA7E3FF), Color(0xFF7E7FF0), Color(0xFFB4B3F5),
          Color(0xFFF07FD0), Color(0xFFF7B2E4), Color(0xFF26A69A), Color(0xFF80CBC4),
          Color(0xFFEF5350), Color(0xFFEF9A9A), Color(0xFF66BB6A), Color(0xFFA5D6A7),
          Color(0xFF42A5F5), Color(0xFF90CAF9), Color(0xFFEC407A), Color(0xFFF48FB1),
        ];
        return StatefulBuilder(
          builder: (context, setSheet) {
            final live = _normalizeHex(controller.text);
            final preview = live == null
                ? _parse(hex.isEmpty ? '7FCDD0' : hex)
                : Color(0xFF000000 | int.parse(live, radix: 16));
            return SafeArea(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(20, 18, 20, 18),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(mi.Icons.colorize_rounded,
                            color: scheme.primary, size: 20),
                        const SizedBox(width: 8),
                        Text('Color Studio',
                            style: Theme.of(context)
                                .textTheme
                                .titleMedium
                                ?.copyWith(fontWeight: FontWeight.w700)),
                        const Spacer(),
                        // ── the live preview pill ─────────────────────
                        Container(
                          padding: const EdgeInsets.symmetric(
                              horizontal: 14, vertical: 7),
                          decoration: BoxDecoration(
                            color: preview,
                            borderRadius: BorderRadius.circular(20),
                            boxShadow: [
                              BoxShadow(
                                color: preview.withValues(alpha: 0.45),
                                blurRadius: 16,
                              ),
                            ],
                          ),
                          child: Text(
                            live == null ? 'Preview' : '#$live',
                            style: TextStyle(
                              color: preview.computeLuminance() < 0.179
                                  ? Colors.white
                                  : Colors.black,
                              fontWeight: FontWeight.w700,
                              fontSize: 12.5,
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 4),
                    Text(
                        'Tap a swatch or type a hex — the whole app repaints live on Apply Color.',
                        style: TextStyle(
                            color: scheme.onSurfaceVariant, fontSize: 11.5)),
                    const SizedBox(height: 14),
                    // ── the interactive hue/saturation grid ──────────
                    GridView.count(
                      crossAxisCount: 8,
                      shrinkWrap: true,
                      physics: const NeverScrollableScrollPhysics(),
                      childAspectRatio: 1.05,
                      mainAxisSpacing: 10,
                      crossAxisSpacing: 10,
                      children: [
                        for (final c in grid)
                          Builder(builder: (swatchContext) {
                            final cHex = _toHex(c);
                            return GestureDetector(
                              behavior: HitTestBehavior.opaque,
                              onTap: () {
                                controller.text = '#$cHex';
                                setSheet(() {});
                              },
                              child: Container(
                                decoration: BoxDecoration(
                                  shape: BoxShape.circle,
                                  color: c,
                                  border: live != null && live == cHex
                                      ? Border.all(
                                          color: scheme.onSurface,
                                          width: 2.5)
                                      : null,
                                ),
                              ),
                            );
                          }),
                      ],
                    ),
                    const SizedBox(height: 16),
                    // ── the live #RRGGBB hex input ──────────────────
                    TextField(
                      controller: controller,
                      onChanged: (_) => setSheet(() {}),
                      inputFormatters: [
                        FilteringTextInputFormatter.allow(
                            RegExp(r'[0-9a-fA-F#]')),
                      ],
                      decoration: InputDecoration(
                        hintText: '#RRGGBB',
                        prefixIcon:
                            const Icon(mi.Icons.format_color_fill_rounded),
                        suffixIcon: live == null
                            ? Icon(mi.Icons.error_outline_rounded,
                                color: scheme.error, size: 20)
                            : Icon(mi.Icons.check_circle_rounded,
                                color: scheme.primary, size: 20),
                      ),
                    ),
                    const SizedBox(height: 16),
                    Row(
                      children: [
                        TextButton(
                          onPressed: () {
                            scope.putString(SettingsMirror.kAccentHex, '');
                            Navigator.pop(sheet);
                          },
                          child: const Text('Use preset'),
                        ),
                        const Spacer(),
                        FilledButton.icon(
                          icon: const Icon(mi.Icons.check_rounded, size: 18),
                          // K_ACCENT_HEX — the persisted custom accent.
                          // v3.4.6.h — Apply Color rides the SAME interlock
                          // path: Material You turns OFF, the hex repaints
                          // the whole app, and the user sees the 1-line
                          // confirmation (never a silent no-op).
                          label: const Text('Apply Color'),
                          onPressed: live == null
                              ? null
                              : () {
                                  _sayAccentFeedback(
                                      sheet,
                                      applyManualAccent(scope, hex: live));
                                  Navigator.pop(sheet);
                                },
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            );
          },
        );
      },
    );
  }

  /// '#7fcdd0' | '7fcdd0' → '7FCDD0' (null when not a valid 6-hex).
  static String? _normalizeHex(String raw) {
    var v = raw.trim();
    if (v.startsWith('#')) v = v.substring(1);
    return RegExp(r'^([0-9a-fA-F]{6})$').hasMatch(v)
        ? v.toUpperCase()
        : null;
  }

  static String _toHex(Color c) {
    var h = (c.toARGB32() & 0xFFFFFF).toRadixString(16).toUpperCase();
    while (h.length < 6) {
      h = '0$h';
    }
    return h;
  }
}

// ── v3.4.6.b — the connected rows (upstream SettingsItem shapes, Mornye
// paddings from context.tokens, divider between rows by the group) ──────────

class _SliderRow extends StatefulWidget {
  const _SliderRow({
    required this.title,
    required this.value,
    required this.labelFor,
    required this.onCommit,
    this.enabled = true,
    this.onPreview,
  });

  final String title;

  /// The normalized slider value, 0.0 … 1.0 (the caller's own mapping).
  final double value;

  /// The value label for a given slider fraction (`14sp`, `55%`, `100%` …) —
  /// used for BOTH the resting row label and the drag bubble callout.
  final String Function(double v) labelFor;

  /// v3.4.6.k — THE SINGLE COMMIT POINT: fires ONCE when the drag ends.
  /// While dragging, ONLY the local draft updates (60/120 fps smooth, zero
  /// MethodChannel putInt + getAll() round-trips per frame — the Part_02/
  /// Part_03/Part_04 settings lag kill).
  final ValueChanged<double> onCommit;

  /// v3.4.6.i — inert-but-visible slider (the Midnight dim amount in Light
  /// mode): the control stays on screen in its clean disabled color.
  final bool enabled;

  /// v3.4.6.l — THE LIVE PREVIEW LANE (video_2 02:55, "30sp stuck at 12sp"):
  /// fires on EVERY drag frame while the thumb moves; a null value fires once
  /// on drag END so the page can clear its draft (the commit still fires
  /// exactly once on drag end).
  final ValueChanged<double?>? onPreview;

  @override
  State<_SliderRow> createState() => _SliderRowState();
}

class _SliderRowState extends State<_SliderRow> {
  /// The local drag draft: while non-null the row shows the DRAFT value and
  /// writes NOTHING to the channel; the commit fires once on drag end.
  double? _draft;

  @override
  Widget build(BuildContext context) {
    final tokens = context.tokens;
    final scheme = Theme.of(context).colorScheme;
    final v = _draft ?? widget.value;
    return Padding(
      padding: EdgeInsets.symmetric(
        horizontal: tokens.rowPaddingH,
        vertical: 12,
      ),
      child: Row(
        children: [
          Expanded(
              child: Text(widget.title,
                  style: TextStyle(
                      color: scheme.onSurface, fontSize: 14))),
          Expanded(
            flex: 2,
            // v3.4.6.k — the MORNYE BUBBLE SLIDER: the accent callout pill
            // pops up above the thumb while dragging; onChanged feeds ONLY
            // the local draft; onCommit is the single channel write.
            child: MornyeBubbleSlider(
              value: v.clamp(0.0, 1.0),
              enabled: widget.enabled,
              calloutFor: widget.labelFor,
              // v3.4.6.l — the LIVE lane feeds the preview DURING the drag;
              // the single commit still fires once on drag end.
              onChanged: (x) {
                setState(() => _draft = x);
                widget.onPreview?.call(x);
              },
              onChangeEnd: (x) {
                setState(() => _draft = null);
                widget.onPreview?.call(null);   // v3.4.6.l — clear the live draft
                widget.onCommit(x);
              },
            ),
          ),
          SizedBox(
            width: 44,
            child: Text(widget.labelFor(v),
                textAlign: TextAlign.right,
                style: TextStyle(
                    color: scheme.onSurfaceVariant, fontSize: 12)),
          ),
        ],
      ),
    );
  }
}

class _SwitchRow extends StatelessWidget {
  const _SwitchRow({
    required this.title,
    required this.value,
    required this.onChanged,
    this.subtitle,
    this.enabled = true,
  });

  final String title;
  final bool value;
  final ValueChanged<bool> onChanged;

  /// v3.4.6.i — an optional caption under the title (the disabled-state
  /// explanation row) and an [enabled] flag so a setting that is a no-op in
  /// the current mode can show a CLEAN disabled state instead of a dead
  /// no-op switch that looks broken when tapped.
  final String? subtitle;
  final bool enabled;

  @override
  Widget build(BuildContext context) {
    // v3.4.6.b — the upstream connected-list switch row: SettingsItem's own
    // row chrome (padding from tokens, divider kept by the group) with the
    // Mornye AppSwitch as the trailing control, exactly as upstream's
    // settings pages place their switches.
    return SettingsItem(
      title: title,
      subtitle: subtitle,
      trailing: AppSwitch(
        value: value,
        onChanged: enabled ? onChanged : null,
      ),
    );
  }
}

class _SegmentedRow extends StatelessWidget {
  const _SegmentedRow({
    required this.title,
    required this.labels,
    required this.selected,
    required this.onChanged,
  });

  final String title;
  final List<String> labels;
  final int selected;
  final ValueChanged<int> onChanged;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(bottom: 2),
            child: Text(title,
                style: TextStyle(
                    color: Theme.of(context).colorScheme.onSurface,
                    fontSize: 13.5)),
          ),
          // v3.4.6.c — the REAL Mornye segmented control (the glowing glass
          // pill on the active choice), replacing dead static text rows.
          MornyeSegmentedControl(
            labels: labels,
            selectedIndex: selected,
            onChanged: onChanged,
          ),
        ],
      ),
    );
  }
}

class _ActionRow extends StatelessWidget {
  const _ActionRow({
    super.key,
    required this.title,
    required this.onTap,
    this.subtitle,
    this.destructive = false,
  });

  final String title;
  final VoidCallback onTap;
  final String? subtitle;
  final bool destructive;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return SettingsItem(
      title: title,
      subtitle: subtitle,
      onTap: onTap,
      // v3.4.6.b: `isLast` was declared but never passed by any caller —
      // dart analyze fails CI on `unused_element_parameter` (WARNING).
      // The shipped rendering always showed the divider (default false →
      // showDivider: !false), so that behavior is preserved explicitly.
      showDivider: true,
      trailing: Icon(mi.Icons.chevron_right_rounded,
          color: scheme.onSurfaceVariant),
    );
  }
}

class _VersionFooter extends StatelessWidget {
  const _VersionFooter();

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    // v3.4.6.h — the LIVE installed identity (never a stale literal: the
    // v3.4.6.g footer said 3.4.6.g inside every later release).
    return FutureBuilder<AppBuildInfo>(
      future: AnantPlay.instance.appInfo(),
      initialData: const AppBuildInfo(versionName: '3.4.6.i', versionCode: 30415),
      builder: (context, snap) {
        final info = snap.data ??
            const AppBuildInfo(versionName: '3.4.6.i', versionCode: 30415);
        return Padding(
          padding: const EdgeInsets.only(top: 18, bottom: 6),
          child: Center(
            child: Text(
                'AnantPlay Sinhala Player v${info.versionName} (${info.versionCode})',
                style: TextStyle(
                    color: scheme.onSurfaceVariant, fontSize: 11.5)),
          ),
        );
      },
    );
  }
}
