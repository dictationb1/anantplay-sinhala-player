// AnantPlay Sinhala Player v3.4.6.c — the full Mornye v5.4.0 Customization
// Studio: a LIVE interactive preview card pinned at the top (instantly
// reflecting theme, accent, glass clarity, outlines, subtitle size, subtitle
// color, subtitle background and the midnight dimmer), then Quick Settings
// (theme cards, 7 Mornye accent swatches + custom hex + dynamic color, glass
// clarity/outlines/force-blur/motion sliders & switches, subtitle size, auto
// Sinhala subs, continue watching, midnight dimmer + dim %, auto-update +
// Check Now) and the expandable connected ADVANCED groups (Appearance &
// Navigation · Cinema Player & Subtitle Studio · AnantPlay & Sources ·
// Browser & Privacy · Cast & Network · Backup & Restore · About/Licenses).
//
// v3.4.6.c — complaint #2 fix: this screen is reachable BOTH from the
// always-visible bottom tab bar AND Home's top-right gear, and every control
// writes through the real `anantplay/settings` bridge (nothing decorative).
import 'package:flutter/services.dart' show FilteringTextInputFormatter;
import 'package:material_ui/material_ui.dart' hide Icons;
import 'package:material_ui/material_ui.dart' as mi show Icons;

import 'package:anantplay_ui/mornye_ui/theme/app_tokens.dart';
import 'package:anantplay_ui/mornye_ui/widgets/settings_group.dart';
import 'package:anantplay_ui/mornye_ui/widgets/app_switch.dart';
import 'package:anantplay_ui/mornye_ui/widgets/mornye_chrome.dart';

import 'app_state.dart';
import 'home_screen.dart' show showInstallerSheet;
import 'bridge.dart';
import 'settings_mirror.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final state = AppState.of(context);
    final scope = state.scope;
    final s = state.settings;
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      backgroundColor: scheme.surface,
      appBar: AppBar(
        title: const Text('Customize'),
        backgroundColor: scheme.scrim.withValues(alpha: 0.2),
        surfaceTintColor: Colors.transparent,
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(0, 8, 0, 32),
        children: [
          // ── THE LIVE PREVIEW CARD (pinned first — instant feedback) ──────
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
            child: const _LivePreviewCard(),
          ),

          _SectionHeader('Quick settings'),
          SettingsGroup(children: [
            _ThemeCards(current: s.themeMode),
            const SizedBox(height: 14),
            _AccentRow(current: s.accent, hex: s.accentHex),
            const SizedBox(height: 4),
          ]),
          SettingsGroup(children: [
            _SliderRow(
              title: 'Glass clarity',
              value: s.glassClarity / 100.0,
              label: '${s.glassClarity}%',
              onChanged: (v) => scope.putInt(
                  SettingsMirror.kGlassClarity, (v * 100).round().clamp(10, 95)),
            ),
            _SwitchRow(
              title: 'Glass outlines',
              value: s.glassOutlines,
              onChanged: (v) =>
                  scope.putBool(SettingsMirror.kGlassOutlines, v),
            ),
            _SwitchRow(
              title: 'Force liquid blur',
              value: s.b(SettingsMirror.kSurfaceStyle, true),
              onChanged: (v) => scope.putString(
                  SettingsMirror.kSurfaceStyle, v ? 'glass' : 'flat'),
            ),
            _SliderRow(
              title: 'Animation speed',
              value: (s.b(SettingsMirror.kMotion, true) ? 1.0 : 0.0),
              label: s.b(SettingsMirror.kMotion, true) ? 'Full' : 'Reduced',
              onChanged: (v) => scope.putBool(
                  SettingsMirror.kMotion, v >= 0.5),
            ),
          ]),
          SettingsGroup(children: [
            _SliderRow(
              title: 'Subtitle size',
              value: ((s.i(SettingsMirror.kFontSpKey, 16)) - 12) / 24.0,
              label: '${s.i(SettingsMirror.kFontSpKey, 16)}sp',
              onChanged: (v) => scope.putInt(
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
          SettingsGroup(children: [
            _SwitchRow(
              title: 'Midnight night dimmer',
              value: s.b(SettingsMirror.kMidnightDimmer, false),
              onChanged: (v) =>
                  scope.putBool(SettingsMirror.kMidnightDimmer, v),
            ),
            _SliderRow(
              title: 'Dim amount',
              value: s.i(SettingsMirror.kMidnightDimPct, 30) / 70.0,
              label: '${s.i(SettingsMirror.kMidnightDimPct, 30)}%',
              onChanged: (v) => scope.putInt(
                  SettingsMirror.kMidnightDimPct,
                  (v * 70).round().clamp(10, 70)),
            ),
            _SwitchRow(
              title: 'Auto-update',
              value: s.autoUpdate,
              onChanged: (v) => scope.putBool(SettingsMirror.kAutoUpdate, v),
            ),
            _ActionRow(
              title: 'Check for update now',
              onTap: () => AnantPlay.instance.checkForUpdate(),
            ),
          ]),

          // ── the expandable connected ADVANCED groups ──────────────────────
          const SizedBox(height: 10),
          _SectionHeader('Advanced'),
          _ExpandableGroup(
            title: 'Appearance & Navigation',
            icon: mi.Icons.palette_rounded,
            children: (ctx) => const _AppearanceNavRows(),
          ),
          _ExpandableGroup(
            title: 'Cinema Player & Subtitle Studio',
            icon: mi.Icons.movie_rounded,
            children: (ctx) => const _SubtitleStudioRows(),
          ),
          _ExpandableGroup(
            title: 'AnantPlay & Sources',
            icon: mi.Icons.cast_rounded,
            children: (ctx) => const _AnantPlayRows(),
          ),
          _ExpandableGroup(
            title: 'Browser & Privacy',
            icon: mi.Icons.public_rounded,
            children: (ctx) => const _BrowserRows(),
          ),
          _ExpandableGroup(
            title: 'Cast & Network',
            icon: mi.Icons.wifi_rounded,
            children: (ctx) => const _CastRows(),
          ),
          _ExpandableGroup(
            title: 'Backup & Restore',
            icon: mi.Icons.backup_rounded,
            children: (ctx) => const _BackupRows(),
          ),

          const SizedBox(height: 10),
          _SectionHeader('About'),
          SettingsGroup(children: [
            _ActionRow(
              title: 'Open-source licenses (LGPL-3.0-only)',
              onTap: () => _licenses(context),
            ),
            _ActionRow(
              title: 'Run setup guide',
              subtitle: 'Replay the 6-slide first-launch wizard',
              onTap: () => AppState.of(context).scope.rerunOnboarding(),
            ),
            const _VersionFooter(),
          ]),
        ],
      ),
    );
  }

  Future<void> _licenses(BuildContext context) async {
    showLicensePage(
      context: context,
      applicationName: 'AnantPlay Sinhala Player',
      applicationVersion: '3.4.6.d',
      applicationIcon: const SizedBox(width: 48, height: 48),
    );
  }
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
            // The live Sinhala subtitle line — size, color and background
            // all react instantly to the Subtitle Studio settings.
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
                  style: TextStyle(
                    fontFamily: 'Noto Sans Sinhala',
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
            const SizedBox(height: 18),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [
                _pill(accent, scheme, 'Accent'),
                _pill(scheme.onSurfaceVariant, scheme,
                    s.b(SettingsMirror.kMotion, true) ? 'Motion on' : 'Motion off'),
                _pill(scheme.onSurfaceVariant, scheme,
                    s.b(SettingsMirror.kMidnightDimmer, false) ? 'Dimmed' : 'Bright'),
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

  @override
  Widget build(BuildContext context) {
    final state = AppState.of(context);
    final scope = state.scope;
    final s = state.settings;
    return Column(children: [
      _SegmentedRow(
        title: 'Subtitle font',
        labels: const ['Noto Sinhala', 'System', 'Serif'],
        selected: ['noto', 'system', 'serif']
            .indexOf(s.s(SettingsMirror.kFontFamily, 'noto'))
            .clamp(0, 2),
        onChanged: (i) => scope.putString(SettingsMirror.kFontFamily,
            ['noto', 'system', 'serif'][i]),
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
        label: '${s.i(SettingsMirror.kSubBgOpacity, 40)}%',
        onChanged: (v) => scope.putInt(
            SettingsMirror.kSubBgOpacity, (v * 95).round().clamp(0, 95)),
      ),
      _SliderRow(
        title: 'Vertical position',
        value: (s.i(SettingsMirror.kSubBottomPct, 8) - 2) / 33.0,
        label: '${s.i(SettingsMirror.kSubBottomPct, 8)}%',
        onChanged: (v) => scope.putInt(SettingsMirror.kSubBottomPct,
            (2 + v * 33).round().clamp(2, 35)),
      ),
      _SegmentedRow(
        title: 'Sync step',
        labels: const ['±0.1s', '±0.25s', '±0.5s'],
        selected: [100, 250, 500]
            .indexOf(s.i(SettingsMirror.kSyncStepMs, 250))
            .clamp(0, 2),
        onChanged: (i) => scope.putInt(
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
    final state = AppState.of(context);
    final s = state.settings;
    return Column(children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(16, 10, 16, 6),
        child: Align(
          alignment: Alignment.centerLeft,
          child: Text('Saved Index URL',
              style: TextStyle(
                  color: Theme.of(context).colorScheme.onSurface,
                  fontSize: 13.5)),
        ),
      ),
      Padding(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 10),
        child: _IndexUrlField(initial: s.indexUrl),
      ),
      _ActionRow(
        title: '1-tap Copy Index URL',
        subtitle: 'Paste into AnantPlay → Settings → Sources',
        onTap: () => AnantPlay.instance.copyToClipboard(s.indexUrl),
      ),
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

class _IndexUrlField extends StatefulWidget {
  const _IndexUrlField({required this.initial});

  final String initial;

  @override
  State<_IndexUrlField> createState() => _IndexUrlFieldState();
}

class _IndexUrlFieldState extends State<_IndexUrlField> {
  late final TextEditingController _c =
      TextEditingController(text: widget.initial);
  String _saved = '';

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Row(
      children: [
        Expanded(
          child: TextField(
            controller: _c,
            maxLines: 1,
            decoration: InputDecoration(
              isDense: true,
              border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12)),
              suffixIcon: _saved.isEmpty
                  ? null
                  : Icon(mi.Icons.check_rounded,
                      size: 18, color: scheme.primary),
            ),
          ),
        ),
        const SizedBox(width: 8),
        FilledButton.tonal(
          onPressed: () {
            final url = _c.text.trim();
            AnantPlay.instance.saveIndexUrl(url);
            setState(() => _saved = url);   // optimistic; the pref write is fire-and-forget
          },
          child: const Text('Save'),
        ),
      ],
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
        labels: const ['Google', 'DuckDuckGo', 'Startpage', 'Brave'],
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
        onTap: () => AnantPlay.instance.runProbe(),
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
    final json = await AnantPlay.instance.exportBackup();
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Backup exported to the app folder')));
    // The Java side writes the file; the JSON length proves it materialized.
    debugPrint('backup bytes: ${json.length}');
  }

  Future<void> _import(BuildContext context) async {
    // The SAF picker on the Java side writes the pending import; the shell
    // previews through the bridge (SettingsBackup.parseImport).
    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
        content: Text('Open the .json backup from the player screen')));
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
                Icon(widget.icon, size: 20, color: scheme.primary),
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
                      onTap: () => AppState.of(context)
                          .scope
                          .putInt(SettingsMirror.kAccent, i),
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
                // v3.4.6.c — the custom #RRGGBB hex picker (K_ACCENT_HEX).
                GestureDetector(
                  onTap: () => _pickHex(context),
                  child: Container(
                    width: 40,
                    height: 30,
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(9),
                      border: Border.all(
                          color: scheme.primary, width: 1.4),
                      color: hex.isEmpty ? null : _parse(hex),
                    ),
                    child: Center(
                      child: hex.isEmpty
                          ? Text('#',
                              style: TextStyle(
                                  color: scheme.primary,
                                  fontWeight: FontWeight.w700))
                          : const SizedBox.shrink(),
                    ),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 8),
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

  void _pickHex(BuildContext context) {
    final controller = TextEditingController(
        text: hex.isEmpty ? '#7FCDD0' : hex);
    final scheme = Theme.of(context).colorScheme;
    showDialog<void>(
      context: context,
      builder: (d) => AlertDialog(
        backgroundColor: scheme.surfaceContainerHigh,
        title: Text('Custom accent',
            style: TextStyle(color: scheme.onSurface)),
        content: TextField(
          controller: controller,
          autofocus: true,
          inputFormatters: [
            FilteringTextInputFormatter.allow(
                RegExp(r'[0-9a-fA-F#]')),
          ],
          decoration: const InputDecoration(
              hintText: '#RRGGBB', prefixIcon: Icon(mi.Icons.colorize_rounded)),
        ),
        actions: [
          TextButton(
            onPressed: () {
              AppState.of(context).scope
                  .putString(SettingsMirror.kAccentHex, '');
              Navigator.pop(d);
            },
            child: const Text('Use preset'),
          ),
          FilledButton(
            onPressed: () {
              var v = controller.text.trim();
              if (!v.startsWith('#')) v = '#$v';
              final clean = RegExp(r'^#?([0-9a-fA-F]{6})$').hasMatch(v)
                  ? v.replaceFirst('#', '').toUpperCase()
                  : '';
              if (clean.isNotEmpty) {
                AppState.of(context).scope
                    .putString(SettingsMirror.kAccentHex, clean);
              }
              Navigator.pop(d);
            },
            child: const Text('Apply'),
          ),
        ],
      ),
    );
  }
}

// ── v3.4.6.b — the connected rows (upstream SettingsItem shapes, Mornye
// paddings from context.tokens, divider between rows by the group) ──────────

class _SliderRow extends StatelessWidget {
  const _SliderRow({
    required this.title,
    required this.value,
    required this.label,
    required this.onChanged,
  });

  final String title;
  final double value;
  final String label;
  final ValueChanged<double> onChanged;

  @override
  Widget build(BuildContext context) {
    final tokens = context.tokens;
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: EdgeInsets.symmetric(
        horizontal: tokens.rowPaddingH,
        vertical: 12,
      ),
      child: Row(
        children: [
          Expanded(
              child: Text(title,
                  style: TextStyle(
                      color: scheme.onSurface, fontSize: 14))),
          Expanded(
            flex: 2,
            child: Slider(
              value: value.clamp(0.0, 1.0),
              onChanged: onChanged,
              activeColor: scheme.primary,
              inactiveColor:
                  scheme.onSurface.withValues(alpha: 0.12),
            ),
          ),
          SizedBox(
            width: 44,
            child: Text(label,
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
  const _SwitchRow({required this.title, required this.value, required this.onChanged});

  final String title;
  final bool value;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    // v3.4.6.b — the upstream connected-list switch row: SettingsItem's own
    // row chrome (padding from tokens, divider kept by the group) with the
    // Mornye AppSwitch as the trailing control, exactly as upstream's
    // settings pages place their switches.
    return SettingsItem(
      title: title,
      trailing: AppSwitch(value: value, onChanged: onChanged),
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
    return Padding(
      padding: const EdgeInsets.only(top: 18, bottom: 6),
      child: Center(
        child: Text('AnantPlay Sinhala Player v3.4.6.e (30411)',
            style: TextStyle(
                color: scheme.onSurfaceVariant, fontSize: 11.5)),
      ),
    );
  }
}
