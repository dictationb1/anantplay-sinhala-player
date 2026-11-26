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
import 'package:anantplay_ui/mornye_ui/theme/mornye_icons.dart';
import 'package:anantplay_ui/mornye_ui/widgets/settings_group.dart';
import 'package:anantplay_ui/mornye_ui/widgets/app_switch.dart';
import 'package:anantplay_ui/mornye_ui/widgets/mornye_chrome.dart';

import 'action_feedback.dart';
import 'app_state.dart';
import 'home_screen.dart' show showInstallerSheet;
import 'bridge.dart';
import 'settings_mirror.dart';
import 'subtitle_style.dart';
import 'package:anantplay_ui/main.dart'
    show wallpaperSchemeAvailable, kWallpaperUnavailableCaption;

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
          // v3.4.6.i — PERMANENT LAW #1: the Auto-update ON/OFF switch is
          // GONE (auto-updating is mandatory, users can never turn it off);
          // only the honest manual "Check for update now" row remains.
          SettingsGroup(children: [
            // v3.4.6.i — the Midnight dimmer is a Dark/AMOLED feature (the
            // buildAppTheme dim overlay only arms when brightness == dark).
            // In Light mode it used to sit as a dead no-op switch that looked
            // broken when tapped; now it shows a CLEAN disabled state with an
            // explicit caption, and the Dim amount slider goes inert too.
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
              label: '${s.i(SettingsMirror.kMidnightDimPct, 30)}%',
              onChanged: (v) => scope.putInt(
                  SettingsMirror.kMidnightDimPct,
                  (v * 70).round().clamp(10, 70)),
              enabled: Theme.of(context).brightness == Brightness.dark,
            ),
            _ActionRow(
              title: 'Check for update now',
              subtitle: 'Checking… → Up to date ($kVisibleVersion)',
              onTap: () => checkForUpdateWithFeedback(context),
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
    // v3.4.6.h — the LIVE installed version (the v3.4.6.g page carried a
    // stale '3.4.6.d' literal through three releases).
    String version = '3.4.6.i';
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
void _sayAccentFeedback(BuildContext context, String message) {
  final messenger = ScaffoldMessenger.maybeOf(context);
  messenger?.hideCurrentSnackBar();
  messenger?.showSnackBar(
      SnackBar(content: Text(message), duration: const Duration(seconds: 2)));
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
    final state = AppState.of(context);
    final s = state.settings;
    return Column(children: [
      // v3.4.6.i — the OFFICIAL ANANTPLAY UPDATE PILL (Settings side): the
      // installed me.champdev.weblock vs the latest AnantPlay/android release,
      // 1-tap Update Now through the fixed in-app installer.
      const _OfficialAnantPlayUpdateRow(),
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
    ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Exporting backup…')));
    try {
      final json = await AnantPlay.instance.exportBackup();
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('Backup exported · ${json.length} bytes ✓')));
    } catch (error) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('Export failed · $error')));
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
        ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Import failed · empty backup')));
      }
      return;
    }
    try {
      final preview = await AnantPlay.instance.importPreview(json);
      if (!context.mounted) return;
      if (!preview.ok) {
        ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('Import failed · ${preview.error}')));
        return;
      }
      await importBackupWithFeedback(context, json);
    } catch (error) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('Import failed · $error')));
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

class _SliderRow extends StatelessWidget {
  const _SliderRow({
    required this.title,
    required this.value,
    required this.label,
    required this.onChanged,
    this.enabled = true,
  });

  final String title;
  final double value;
  final String label;
  final ValueChanged<double> onChanged;

  /// v3.4.6.i — inert-but-visible slider (the Midnight dim amount in Light
  /// mode): the control stays on screen in its clean disabled color.
  final bool enabled;

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
              onChanged: enabled ? onChanged : null,
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
