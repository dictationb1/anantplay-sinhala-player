// AnantPlay Sinhala Player v3.4.6.i — THE MORNYE LIQUID-GLASS BROWSER TAB.
//
// Hosts the hardened native WebBrowserView (a real Android PlatformView:
// MIXED_CONTENT_NEVER_ALLOW, BridgeGuard 5 MB pre-decode cap + Blob subtitle
// capture, StreamSniffer HLS/DASH/MP4 detection, ad-block counter, reader
// dark) inside the Flutter shell — with a floating Mornye Liquid-Glass
// capture dock that hands sniffed streams straight to the Mornye Cinema
// Player (never the old Java PlayActivity — real-phone complaint #1/#3).
//
// v3.4.6.i — THE VIDEO-4 FIXES:
//   · THE BROWSER BAR (top): Home + the Built-In Sources & Speed Dial Hub in
//     ONE tap — the tab never sits as a blank dark panel again (the native
//     side also boots the WebView straight to HOME_URL now).
//   · THE INTEGRATED SOURCES HUB: ALL THREE exact user index feeds preloaded
//     (Sri Lanka IPTV & Live Sites · Sinhala Subtitle Bridge · Official
//     AnantPlay Index) + an editable/addable custom list, parsed IN-APP from
//     the real index.json (fetchUrl → dart:convert), with 1-tap Copy and the
//     guided paste-into-AnantPlay setup — mirroring the user's screenshots,
//     with our Ad block / Address bar / DoH DNS bypass chips beside them.
import 'dart:async';
import 'dart:convert' show jsonDecode;
import 'dart:io' show Platform;

import 'package:flutter/services.dart'
    show Clipboard, ClipboardData, StandardMessageCodec;
import 'package:material_ui/material_ui.dart' hide Icons;
import 'package:material_ui/material_ui.dart' as mi show Icons;

import 'bridge.dart';
import 'app_state.dart';
import 'settings_mirror.dart';
import 'package:anantplay_ui/main.dart';

/// The PlatformView type id MainActivity registers the factory under.
const String _viewType = 'anantplay/webview';

/// v3.4.6.i — THE THREE BUILT-IN INDEX PRESETS (the user's exact screenshots:
/// every feed pre-wired, editable/extendable — never just 1 single box).
class IndexPreset {
  const IndexPreset(this.name, this.url, this.blurb);
  final String name;
  final String url;
  final String blurb;
}

const List<IndexPreset> kBuiltInIndexPresets = [
  IndexPreset(
    'Sri Lanka IPTV & Live Sites',
    'https://raw.githubusercontent.com/dictationb1/srilanka-anantplay/main/index.json',
    'Curated direct streams, broadcaster sites & live coverage',
  ),
  IndexPreset(
    'Sinhala Subtitle Bridge',
    'https://anantplay-si-subs-bridge.techworldlk.workers.dev/index.json',
    'Search / machine-translate / upload Sinhala subs for any stream',
  ),
  IndexPreset(
    'Official AnantPlay Index',
    'https://raw.githubusercontent.com/AnantPlay/index/main/index.json',
    'The official index: streaming sites, free TV channels, title links',
  ),
];

/// The prefs key holding the hub's custom additions ("name|url\n…" lines).
const String _kHubCustomKey = 'sourcesHubCustom';

class BrowserScreen extends StatefulWidget {
  const BrowserScreen({super.key, this.initialUrl});

  final String? initialUrl;

  @override
  State<BrowserScreen> createState() => _BrowserScreenState();
}

class _BrowserScreenState extends State<BrowserScreen> {
  /// The latest sniffed stream hits (newest last), pushed by the native side.
  List<Map<dynamic, dynamic>> _hits = const [];
  StreamSubscription<Map<dynamic, dynamic>>? _hitSub;
  bool _dockOpen = true;
  Brightness? _appliedTheme;

  @override
  void initState() {
    super.initState();
    // StreamSniffer hits arrive on the shell route channel with t='sniff'.
    _hitSub = AnantPlay.instance.shellRoutes().listen((m) {
      if (m['t'] == 'sniff' && mounted) {
        setState(() => _hits = [..._hits, m]);
      }
    });
    if (widget.initialUrl != null && widget.initialUrl!.isNotEmpty) {
      // Hand the URL to the native view once it is attached.
      WidgetsBinding.instance.addPostFrameCallback((_) {
        AnantPlay.instance.openNativeWeb(widget.initialUrl!);
      });
    }
  }

  @override
  void didUpdateWidget(BrowserScreen old) {
    super.didUpdateWidget(old);
    if (widget.initialUrl != old.initialUrl &&
        widget.initialUrl != null &&
        widget.initialUrl!.isNotEmpty) {
      AnantPlay.instance.openNativeWeb(widget.initialUrl!);
    }
  }

  @override
  void dispose() {
    _hitSub?.cancel();
    super.dispose();
  }

  void _openHub() {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Theme.of(context).colorScheme.surfaceContainerHigh,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
      ),
      builder: (_) => const _SourcesHubSheet(),
    );
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    // v3.4.6.i — keep the long-lived native WebView's background, user-agent
    // and page color-scheme in sync with the live theme whenever it flips.
    final brightness = Theme.of(context).brightness;
    if (_appliedTheme != brightness) {
      _appliedTheme = brightness;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        AnantPlay.instance.applyBrowserTheme();
      });
    }
    return Scaffold(
      backgroundColor: scheme.surface,
      body: SafeArea(
        bottom: false,
        child: Column(
          children: [
            // ── v3.4.6.i — THE BROWSER BAR (1-tap Sources Hub + Home) ──────
            _BrowserBar(onHome: _home, onHub: _openHub),
            // ── THE NATIVE WEBVIEW (the hardened WebBrowserView) ───────────
            // dart:io Platform (the HOST, not the framework target): the real
            // Android app embeds the platform view; the widget-test host renders
            // the dock + a placeholder instead (an unmocked platform_views
            // channel would buffer forever and hang the suite).
            Expanded(
              child: Stack(
                children: [
                  Positioned.fill(
                    child: Padding(
                      padding: const EdgeInsets.only(bottom: 78),
                      child: Platform.isAndroid
                          ? AndroidView(
                              viewType: _viewType,
                              creationParams: <String, Object?>{
                                if (widget.initialUrl != null)
                                  'url': widget.initialUrl,
                              },
                              creationParamsCodec: const StandardMessageCodec(),
                            )
                          : const ColoredBox(color: Color(0xFF0B1020)),
                    ),
                  ),
                  // ── THE FLOATING LIQUID-GLASS CAPTURE DOCK ──────────────
                  Positioned(
                    left: 16,
                    right: 16,
                    bottom: MediaQuery.paddingOf(context).bottom + 10,
                    child: _CaptureDock(
                      hits: _hits,
                      open: _dockOpen,
                      onToggle: () => setState(() => _dockOpen = !_dockOpen),
                      onPlay: (url, title, headers) {
                        // v3.4.6.c — a sniffed stream opens the MORNYE CINEMA
                        // PLAYER in the shell (was: the raw Java PlayActivity).
                        ShellRootState.of(context).pushPlayer(
                            PlayRequest(url: url, title: title, headers: headers));
                      },
                      onTranslate: () {
                        // v3.4.6.g — the NewZone Translator Automation lane: the
                        // dock opens tools.newzone.top in the same native view —
                        // grab or translate subtitles in 1–2 taps.
                        AnantPlay.instance.openNativeWeb('https://tools.newzone.top');
                      },
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

  void _home() {
    // v3.4.6.i — the bar's Home button: HOME_URL through the native lane.
    AnantPlay.instance.openBrowserHome();
  }
}

/// v3.4.6.i — the compact glass browser bar: Home on the left, the Sources &
/// Speed Dial Hub pill in ONE tap, and the ad-block chip on the right.
class _BrowserBar extends StatelessWidget {
  const _BrowserBar({required this.onHome, required this.onHub});

  final VoidCallback onHome;
  final VoidCallback onHub;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final s = AppState.of(context).settings;
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 6, 12, 6),
      child: Material(
        color: scheme.surfaceContainerHigh.withValues(alpha: 0.85),
        borderRadius: BorderRadius.circular(22),
        clipBehavior: Clip.antiAlias,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
          child: Row(
            children: [
              IconButton(
                tooltip: 'Home (Google)',
                icon: Icon(mi.Icons.home_rounded,
                    size: 22, color: scheme.onSurface),
                onPressed: onHome,
              ),
              // ── ONE-TAP SOURCES HUB PILL ─────────────────────────────────
              Expanded(
                child: InkWell(
                  borderRadius: BorderRadius.circular(18),
                  onTap: onHub,
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                        horizontal: 10, vertical: 8),
                    child: Row(
                      children: [
                        Icon(mi.Icons.apps_rounded,
                            size: 19, color: scheme.primary),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            'Sources & Speed Dial',
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: 12.5,
                              fontWeight: FontWeight.w600,
                              color: scheme.onSurface,
                            ),
                          ),
                        ),
                        Icon(mi.Icons.chevron_right_rounded,
                            size: 18, color: scheme.onSurfaceVariant),
                      ],
                    ),
                  ),
                ),
              ),
              // v3.4.6.i — the live shield chip: the user's exact screenshots
              // carry `Ad block: On` beside the index feeds; ours reflects the
              // REAL mirror value (toggling in Settings flips it live).
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                decoration: BoxDecoration(
                  color: scheme.primaryContainer.withValues(alpha: 0.5),
                  borderRadius: BorderRadius.circular(14),
                ),
                child: Text(
                  'Ad block: ${s.b(SettingsMirror.kAdBlock, true) ? 'On' : 'Off'}',
                  style: TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w700,
                    color: scheme.onSurface,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// v3.4.6.i — THE BUILT-IN SOURCES & SPEED DIAL HUB. All three user indexes
/// preloaded + custom additions, parsed in-app from the real index.json, with
/// 1-tap Copy and the guided paste-into-AnantPlay setup.
class _SourcesHubSheet extends StatefulWidget {
  const _SourcesHubSheet();

  @override
  State<_SourcesHubSheet> createState() => _SourcesHubSheetState();
}

class _SourcesHubSheetState extends State<_SourcesHubSheet> {
  /// URL → parsed feed (name + sources list). One fetch per URL, cached.
  final Map<String, Map<String, dynamic>?> _feeds = {};
  final Map<String, String> _errors = {};
  bool _hideOffline = true;

  List<IndexPreset> get _presets => kBuiltInIndexPresets;

  List<IndexPreset> _customEntries(String raw) {
    final out = <IndexPreset>[];
    for (final line in raw.split('\n')) {
      final t = line.trim();
      if (t.isEmpty) continue;
      final i = t.indexOf('|');
      if (i <= 0) continue;
      out.add(IndexPreset(
          t.substring(0, i).trim(), t.substring(i + 1).trim(), 'Custom index'));
    }
    return out;
  }

  Future<void> _loadFeed(String url) async {
    if (_feeds.containsKey(url)) return;
    setState(() => _feeds[url] = null); // loading sentinel
    final body = await AnantPlay.instance.fetchUrl(url);
    if (!mounted) return;
    if (body.isEmpty) {
      setState(() => _errors[url] = 'unreachable (offline ok)');
      return;
    }
    try {
      final parsed = jsonDecode(body);
      if (parsed is Map<String, dynamic> &&
          parsed['sources'] is List &&
          (parsed['name'] is String || parsed['sources'].isNotEmpty)) {
        setState(() => _feeds[url] = parsed);
      } else {
        setState(() => _errors[url] = 'not an index feed');
      }
    } on FormatException catch (e) {
      setState(() => _errors[url] = 'invalid JSON (${e.message})');
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final state = AppState.of(context);
    final s = state.settings;
    final customRaw = s.s(_kHubCustomKey, '');
    final customs = _customEntries(customRaw);
    final height = MediaQuery.sizeOf(context).height;
    return SizedBox(
      height: height * 0.82,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // ── the hub header + status chips ─────────────────────────────────
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 16, 20, 6),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Sources & Speed Dial',
                    style: TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.w800,
                        color: scheme.onSurface)),
                const SizedBox(height: 4),
                Text(
                  'Every built-in index feed loads & parses right here — '
                  'then paste any of them into official AnantPlay in 1 tap.',
                  style: TextStyle(
                      fontSize: 12, color: scheme.onSurfaceVariant),
                ),
                const SizedBox(height: 10),
                // v3.4.6.i — the user's exact status chips: Address bar / Ad
                // block / DoH DNS bypass / Hide offline channels (our own
                // live mirror values; the hide-offline toggle filters the
                // hub's own source rows below).
                Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: [
                    _chip('Address bar: On', scheme),
                    _chip(
                        'Ad block: ${s.b(SettingsMirror.kAdBlock, true) ? 'On' : 'Off'}',
                        scheme),
                    _chip(
                        'DoH DNS bypass: ${s.b(SettingsMirror.kDohDiag, true) ? 'On' : 'Off'}',
                        scheme),
                    _choiceChip(
                      'Hide offline channels: ${_hideOffline ? 'On' : 'Off'}',
                      scheme,
                      _hideOffline,
                      () => setState(() => _hideOffline = !_hideOffline),
                    ),
                  ],
                ),
              ],
            ),
          ),
          // ── the feeds ─────────────────────────────────────────────────────
          Expanded(
            child: ListView(
              padding: const EdgeInsets.fromLTRB(16, 4, 16, 20),
              children: [
                for (final p in _presets)
                  _PresetCard(
                    preset: p,
                    builtIn: true,
                    feed: _feeds[p.url],
                    error: _errors[p.url],
                    hideOffline: _hideOffline,
                    onLoad: () => _loadFeed(p.url),
                    onCopy: () => _copy(context, p.url),
                  ),
                if (customs.isNotEmpty) ...[
                  Padding(
                    padding: const EdgeInsets.fromLTRB(4, 12, 4, 6),
                    child: Text('Custom indexes',
                        style: TextStyle(
                            fontSize: 13,
                            fontWeight: FontWeight.w700,
                            color: scheme.onSurface)),
                  ),
                  for (final c in customs)
                    _PresetCard(
                      preset: c,
                      builtIn: false,
                      feed: _feeds[c.url],
                      error: _errors[c.url],
                      hideOffline: _hideOffline,
                      onLoad: () => _loadFeed(c.url),
                      onCopy: () => _copy(context, c.url),
                      onRemove: () => state.scope.putString(
                          _kHubCustomKey, _removeCustom(customRaw, c.url)),
                    ),
                ],
                _AddIndexCard(
                  onAdd: (name, url) => state.scope.putString(_kHubCustomKey,
                      _addCustom(customRaw, name, url)),
                ),
                const SizedBox(height: 14),
                // ── the guided setup for pasting into official AnantPlay ──
                Container(
                  padding: const EdgeInsets.all(14),
                  decoration: BoxDecoration(
                    color: scheme.primaryContainer.withValues(alpha: 0.4),
                    borderRadius: BorderRadius.circular(16),
                    border: Border.all(
                        color: scheme.primary.withValues(alpha: 0.5)),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('Guided setup — official AnantPlay',
                          style: TextStyle(
                              fontSize: 13,
                              fontWeight: FontWeight.w700,
                              color: scheme.onSurface)),
                      const SizedBox(height: 6),
                      Text(
                        '1. Tap Copy on any feed above\n'
                        '2. Open AnantPlay → Settings → Sources\n'
                        '3. Paste the index URL and confirm',
                        style: TextStyle(
                            fontSize: 11.5,
                            height: 1.5,
                            color: scheme.onSurfaceVariant),
                      ),
                      const SizedBox(height: 8),
                      FilledButton.tonalIcon(
                        icon: const Icon(mi.Icons.open_in_new_rounded, size: 16),
                        label: const Text('Open official AnantPlay'),
                        onPressed: () => AnantPlay.instance.openOfficialApp(),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  String _addCustom(String raw, String name, String url) {
    final line = '${name.trim()}|${url.trim()}';
    final lines = raw
        .split('\n')
        .map((l) => l.trim())
        .where((l) => l.isNotEmpty)
        .toList()
      ..add(line);
    return lines.join('\n');
  }

  String _removeCustom(String raw, String url) {
    return raw
        .split('\n')
        .map((l) => l.trim())
        .where((l) => l.isNotEmpty)
        .where((l) => !l.endsWith('|${url.trim()}'))
        .join('\n');
  }

  void _copy(BuildContext context, String url) {
    Clipboard.setData(ClipboardData(text: url));
    AnantPlay.instance.copyToClipboard(url);
  }

  Widget _chip(String label, ColorScheme scheme) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        decoration: BoxDecoration(
          color: scheme.surfaceContainerHighest.withValues(alpha: 0.6),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Text(label,
            style: TextStyle(
                fontSize: 11, fontWeight: FontWeight.w600, color: scheme.onSurface)),
      );

  Widget _choiceChip(String label, ColorScheme scheme, bool selected,
          VoidCallback onTap) =>
      InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
          decoration: BoxDecoration(
            color: (selected
                    ? scheme.primaryContainer
                    : scheme.surfaceContainerHighest)
                .withValues(alpha: selected ? 0.8 : 0.6),
            borderRadius: BorderRadius.circular(12),
          ),
          child: Text(label,
              style: TextStyle(
                  fontSize: 11,
                  fontWeight: FontWeight.w700,
                  color: scheme.onSurface)),
        ),
      );
}

/// One preset feed card: name + blurb + url + [Load/Loaded N] + [Copy]
/// (+ [Remove] for custom entries), expanding into its parsed source rows.
class _PresetCard extends StatelessWidget {
  const _PresetCard({
    required this.preset,
    required this.builtIn,
    required this.feed,
    required this.error,
    required this.hideOffline,
    required this.onLoad,
    required this.onCopy,
    this.onRemove,
  });

  final IndexPreset preset;
  final bool builtIn;
  final Map<String, dynamic>? feed;
  final String? error;

  /// v3.4.6.i — `Hide offline channels: On` filters out sources the feed
  /// itself marks dead (AnantPlay-style indexes carry state/online hints;
  /// a source with `"state":"offline"` or a dead URL is hidden).
  final bool hideOffline;
  final VoidCallback onLoad;
  final VoidCallback onCopy;
  final VoidCallback? onRemove;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final sources = feed?['sources'];
    final sourceList = sources is List ? sources : const <dynamic>[];
    // v3.4.6.i — the Hide-offline filter (the user's exact chip): feeds carry
    // state hints ("state":"offline" / "dead":true / an empty URL is dead);
    // with the chip On those rows are filtered out of the parsed list.
    final feedName = (feed?['name'] as String?) ?? preset.name;
    final visibleSources = hideOffline
        ? sourceList
            .where((src) => src is Map<String, dynamic> && _isAlive(src))
            .toList()
        : sourceList;
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: scheme.surfaceContainer.withValues(alpha: 0.5),
          borderRadius: BorderRadius.circular(18),
          border:
              Border.all(color: scheme.outlineVariant.withValues(alpha: 0.4)),
        ),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(14, 12, 8, 12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(builtIn
                      ? mi.Icons.public_rounded
                      : mi.Icons.bookmark_rounded,
                      size: 20, color: scheme.primary),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(feedName,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                            fontSize: 13.5,
                            fontWeight: FontWeight.w700,
                            color: scheme.onSurface)),
                  ),
                  // ── 1-TAP COPY (always available, even unloaded) ──────
                  TextButton.icon(
                    onPressed: onCopy,
                    icon: const Icon(mi.Icons.copy_rounded, size: 15),
                    label: const Text('Copy'),
                    style: TextButton.styleFrom(
                      minimumSize: const Size(64, 36),
                      tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                      textStyle:
                          const TextStyle(fontSize: 12, fontWeight: FontWeight.w700),
                    ),
                  ),
                  if (!builtIn && onRemove != null)
                    IconButton(
                      tooltip: 'Remove',
                      icon: Icon(mi.Icons.close_rounded,
                          size: 18, color: scheme.onSurfaceVariant),
                      onPressed: onRemove,
                    ),
                ],
              ),
              const SizedBox(height: 2),
              Text(preset.blurb,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                      fontSize: 11.5, color: scheme.onSurfaceVariant)),
              const SizedBox(height: 6),
              Text(
                preset.url,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                    fontSize: 10.5,
                    color: scheme.onSurfaceVariant.withValues(alpha: 0.8)),
              ),
              const SizedBox(height: 8),
              // ── LOAD: fetch + parse the real index.json IN-APP ───────────
              if (feed == null && error == null)
                FilledButton.tonalIcon(
                  onPressed: onLoad,
                  icon: const Icon(mi.Icons.download_rounded, size: 16),
                  label: const Text('Load & parse'),
                )
              else if (error != null)
                Text('Feed: $error',
                    style: TextStyle(
                        fontSize: 11.5, color: scheme.error))
              else ...[
                Text(
                    '${visibleSources.length} sources · parsed live in-app'
                    '${hideOffline && visibleSources.length != sourceList.length ? ' (${sourceList.length - visibleSources.length} offline hidden)' : ''}',
                    style: TextStyle(
                        fontSize: 11.5,
                        fontWeight: FontWeight.w600,
                        color: scheme.primary)),
                const SizedBox(height: 6),
                for (final src in visibleSources.take(6))
                  if (src is Map<String, dynamic>) _SourceRow(source: src),
                if (visibleSources.length > 6)
                  Padding(
                    padding: const EdgeInsets.only(left: 4, top: 4),
                    child: Text('+ ${visibleSources.length - 6} more in the feed',
                        style: TextStyle(
                            fontSize: 11, color: scheme.onSurfaceVariant)),
                  ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  /// v3.4.6.i — a feed source is "alive" unless the feed itself marks it
  /// dead: `state: offline/down/dead`, `dead: true`, or a missing url.
  static bool _isAlive(Map<String, dynamic> src) {
    final url = (src['url'] as String?) ?? '';
    if (url.isEmpty) return false;
    if (src['dead'] == true) return false;
    final state = ((src['state'] as String?) ?? '').toLowerCase();
    return !(state == 'offline' || state == 'down' || state == 'dead');
  }
}

/// One parsed source row from a real index.json feed.
class _SourceRow extends StatelessWidget {
  const _SourceRow({required this.source});

  final Map<String, dynamic> source;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final name = (source['name'] as String?) ?? 'Source';
    final type = (source['type'] as String?) ?? '';
    final url = (source['url'] as String?) ?? '';
    final description = (source['description'] as String?) ?? '';
    return InkWell(
      borderRadius: BorderRadius.circular(10),
      // Tapping opens the source's own URL in the browser tab (the native
      // lane keeps every hardened setting for the page).
      onTap: url.isNotEmpty ? () => AnantPlay.instance.openNativeWeb(url) : null,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 7),
        child: Row(
          children: [
            if (type.isNotEmpty)
              Container(
                margin: const EdgeInsets.only(right: 8),
                padding:
                    const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
                decoration: BoxDecoration(
                  color: scheme.primaryContainer.withValues(alpha: 0.5),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(type,
                    style: TextStyle(
                        fontSize: 10,
                        fontWeight: FontWeight.w700,
                        color: scheme.onSurface)),
              ),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(name,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                          fontSize: 12.5,
                          fontWeight: FontWeight.w600,
                          color: scheme.onSurface)),
                  if (description.isNotEmpty)
                    Text(description,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                            fontSize: 10.5,
                            color: scheme.onSurfaceVariant)),
                ],
              ),
            ),
            Icon(mi.Icons.open_in_new_rounded,
                size: 14, color: scheme.onSurfaceVariant),
          ],
        ),
      ),
    );
  }
}

/// The add-a-custom-index card (the editable/addable half of the hub).
class _AddIndexCard extends StatefulWidget {
  const _AddIndexCard({required this.onAdd});

  final void Function(String name, String url) onAdd;

  @override
  State<_AddIndexCard> createState() => _AddIndexCardState();
}

class _AddIndexCardState extends State<_AddIndexCard> {
  final TextEditingController _name = TextEditingController();
  final TextEditingController _url = TextEditingController();

  @override
  void dispose() {
    _name.dispose();
    _url.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final valid = _url.text.trim().startsWith('https://') &&
        _name.text.trim().isNotEmpty;
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: DecoratedBox(
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(18),
          border: Border.all(
              color: scheme.outlineVariant.withValues(alpha: 0.5)),
        ),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Add a custom index',
                  style: TextStyle(
                      fontSize: 12.5,
                      fontWeight: FontWeight.w700,
                      color: scheme.onSurface)),
              const SizedBox(height: 8),
              TextField(
                controller: _name,
                decoration: const InputDecoration(
                    isDense: true,
                    labelText: 'Name',
                    border: OutlineInputBorder()),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _url,
                keyboardType: TextInputType.url,
                decoration: const InputDecoration(
                    isDense: true,
                    labelText: 'Index URL (https://…index.json)',
                    border: OutlineInputBorder()),
              ),
              const SizedBox(height: 8),
              FilledButton.tonalIcon(
                onPressed: valid
                    ? () {
                        widget.onAdd(_name.text, _url.text);
                        _name.clear();
                        _url.clear();
                      }
                    : null,
                icon: const Icon(mi.Icons.add_rounded, size: 16),
                label: const Text('Add index'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// The floating dock: shows the newest sniffed stream with a 1-tap Play, the
/// NewZone Auto-Translate helper pill, and the shield badge (blocked
/// ad/tracker count) — Mornye glass over the page.
class _CaptureDock extends StatelessWidget {
  const _CaptureDock({
    required this.hits,
    required this.open,
    required this.onToggle,
    required this.onPlay,
    required this.onTranslate,
  });

  final List<Map<dynamic, dynamic>> hits;
  final bool open;
  final VoidCallback onToggle;
  final void Function(String url, String title, String headers) onPlay;
  final VoidCallback onTranslate;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final newest = hits.isEmpty ? null : hits.last;
    return Material(
      color: scheme.surfaceContainerHigh.withValues(alpha: 0.9),
      borderRadius: BorderRadius.circular(22),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onToggle,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
          child: Row(
            children: [
              Icon(mi.Icons.bolt_rounded,
                  size: 20, color: scheme.primary),
              const SizedBox(width: 10),
              Expanded(
                child: newest == null
                    ? Text(
                        'Open a cinema site — sniffed streams appear here',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                            color: scheme.onSurfaceVariant,
                            fontSize: 12.5),
                      )
                    : GestureDetector(
                        onTap: () => onPlay(
                          (newest['url'] ?? '') as String,
                          (newest['title'] ?? '') as String,
                          (newest['headers'] ?? '') as String,
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              '${newest['kind'] ?? 'stream'} · ${(newest['url'] ?? '')}',
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                  color: scheme.onSurface,
                                  fontSize: 12.5,
                                  fontWeight: FontWeight.w600),
                            ),
                            const SizedBox(height: 2),
                            Text(
                              'Tap to play in the Mornye Cinema Player',
                              style: TextStyle(
                                  color: scheme.onSurfaceVariant,
                                  fontSize: 10.5),
                            ),
                          ],
                        ),
                      ),
              ),
              if (newest != null)
                // v3.4.6.g — THE GLOWING 1-TAP PILL: `Play in Cinema` with a
                // live accent glow (the exact stream, Referer + Cookie + UA
                // captured by StreamSniffer, opens the Mornye Cinema Player).
                Container(
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(22),
                    boxShadow: [
                      BoxShadow(
                        color: scheme.primary.withValues(alpha: 0.45),
                        blurRadius: 18,
                        spreadRadius: 1,
                      ),
                    ],
                  ),
                  child: FilledButton.tonalIcon(
                    icon: const Icon(mi.Icons.play_arrow_rounded, size: 18),
                    label: const Text('Play in Cinema'),
                    onPressed: () => onPlay(
                      (newest['url'] ?? '') as String,
                      (newest['title'] ?? '') as String,
                      (newest['headers'] ?? '') as String,
                    ),
                  ),
                ),
              // v3.4.6.g — the NewZone Auto-Translate helper: grab or translate
              // Sinhala subtitles in 1–2 taps without leaving the page.
              IconButton(
                tooltip: 'Auto-Translate / grab Sinhala subs (NewZone)',
                icon: Icon(mi.Icons.translate_rounded,
                    size: 20, color: scheme.primary),
                onPressed: onTranslate,
              ),
              const SizedBox(width: 6),
              Icon(
                open
                    ? mi.Icons.keyboard_arrow_down_rounded
                    : mi.Icons.keyboard_arrow_up_rounded,
                color: scheme.onSurfaceVariant,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
