// AnantPlay Sinhala Player v3.4.6.c — THE MORNYE LIQUID-GLASS BROWSER TAB.
//
// Hosts the hardened native WebBrowserView (a real Android PlatformView:
// MIXED_CONTENT_NEVER_ALLOW, BridgeGuard 5 MB pre-decode cap + Blob subtitle
// capture, StreamSniffer HLS/DASH/MP4 detection, ad-block counter, reader
// dark) inside the Flutter shell — with a floating Mornye Liquid-Glass
// capture dock that hands sniffed streams straight to the Mornye Cinema
// Player (never the old Java PlayActivity — real-phone complaint #1/#3).
import 'dart:async';
import 'dart:io' show Platform;

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart' hide Icons;
import 'package:material_ui/material_ui.dart' as mi show Icons;

import 'bridge.dart';
import 'package:anantplay_ui/main.dart';

/// The PlatformView type id MainActivity registers the factory under.
const String _viewType = 'anantplay/webview';

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

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      backgroundColor: scheme.surface,
      body: Stack(
        children: [
          // ── THE NATIVE WEBVIEW (the hardened WebBrowserView) ───────────────
          // dart:io Platform (the HOST, not the framework target): the real
          // Android app embeds the platform view; the widget-test host renders
          // the dock + a placeholder instead (an unmocked platform_views
          // channel would buffer forever and hang the suite).
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
          // ── THE FLOATING LIQUID-GLASS CAPTURE DOCK ────────────────────────
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
                ShellRootState.of(context)
                    .pushPlayer(PlayRequest(url: url, title: title, headers: headers));
              },
            ),
          ),
        ],
      ),
    );
  }
}

/// The floating dock: shows the newest sniffed stream with a 1-tap Play, and
/// the shield badge (blocked ad/tracker count) — Mornye glass over the page.
class _CaptureDock extends StatelessWidget {
  const _CaptureDock({
    required this.hits,
    required this.open,
    required this.onToggle,
    required this.onPlay,
  });

  final List<Map<dynamic, dynamic>> hits;
  final bool open;
  final VoidCallback onToggle;
  final void Function(String url, String title, String headers) onPlay;

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
                FilledButton.tonalIcon(
                  icon: const Icon(mi.Icons.play_arrow_rounded, size: 18),
                  label: const Text('Play'),
                  onPressed: () => onPlay(
                    (newest['url'] ?? '') as String,
                    (newest['title'] ?? '') as String,
                    (newest['headers'] ?? '') as String,
                  ),
                ),
              const SizedBox(width: 6),
              // v3.4.6.g — THE BUILT-IN NEWZONE / AUTO-TRANSLATE HELPER: one tap
              // opens the NewZone subtitle translator on the page title, so an
              // English .srt becomes Sinhala without leaving the browser flow.
              IconButton(
                tooltip: 'NewZone · Auto-Translate',
                visualDensity: VisualDensity.compact,
                icon: Icon(mi.Icons.translate_rounded,
                    size: 18, color: scheme.primary),
                onPressed: () {
                  final q = (newest?['title'] ?? '').toString();
                  AnantPlay.instance.openNativeWeb(
                      'https://tools.newzone.top/search?q=${Uri.encodeComponent(q)}');
                },
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
