// AnantPlay Sinhala Player v3.4.6.c — THE MORNYE LIQUID-GLASS CINEMA PLAYER.
//
// Real hardware video: a fullscreen Flutter Texture bound to the native
// Media3TextureBridge (ExoPlayer 1.11.1 on a TextureRegistry SurfaceProducer)
// with the full StreamHttpConfig 3-stage 403/relay ladder underneath. The HUD
// is pure Mornye Liquid-Glass: top glass bar (Back · title · status pill ·
// Lock/Rotate/PiP/External/Settings circular glass controls), center
// play/pause ±10s with double-tap seek ripples, the bottom glass bar with a
// tactile seek bar (buffered + tabular timestamps) and the fixed 5-icon dock
// (Subtitles · Tracks · Aspect · Speed · Search), and the bottom-anchored
// crisp Sinhala subtitle line — ZWJ/ZWNJ preserved, styled live from the
// Subtitle Studio settings.
//
// v3.4.6.c — this screen replaces the OLD RAW JAVA PLAYER (PlayActivity) as
// the destination of Test Player / Play URL / Continue Watching / casts /
// browser captures (real-phone complaint #3).
import 'dart:async';

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart' hide Icons;
import 'package:material_ui/material_ui.dart' as mi show Icons;

import 'package:anantplay_ui/mornye_ui/widgets/mornye_chrome.dart';
import 'package:anantplay_ui/mornye_ui/widgets/app_bottom_sheet.dart';

import 'app_state.dart';
import 'bridge.dart';
import 'package:anantplay_ui/main.dart';
import 'settings_mirror.dart';

/// The playback request the shell hands the player.
class PlayerScreen extends StatefulWidget {
  const PlayerScreen({super.key, required this.request, required this.onExit});

  final PlayRequest request;
  final VoidCallback onExit;

  @override
  State<PlayerScreen> createState() => _PlayerScreenState();
}

class _PlayerScreenState extends State<PlayerScreen> {
  PlayerSession _session = const PlayerSession(
      textureId: -1, videoWidth: 0, videoHeight: 0);
  PlayerFrame _frame = PlayerFrame.empty;
  StreamSubscription<PlayerFrame>? _frameSub;
  bool _hudVisible = true;
  bool _locked = false;
  Timer? _hudTimer;
  double _speed = 1.0;
  String _aspect = 'fit';
  bool _draggingSeek = false;
  double _seekValue = 0;

  @override
  void initState() {
    super.initState();
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
    _open();
    _armHudTimer();
  }

  Future<void> _open() async {
    final r = widget.request;
    _frameSub = AnantPlay.instance.playerFrames().listen((f) {
      if (mounted) setState(() => _frame = f);
    });
    try {
      final s = await AnantPlay.instance.openPlayer(
        r.url,
        headers: r.headers,
        title: r.title,
        test: r.test,
        startMs: r.resumeMs,
      );
      if (!mounted) return;
      if (s.textureId < 0) {
        // Bridge texture creation failed — the honest error surface (never a
        // dead black screen): message + back.
        setState(() => _session = s);
        return;
      }
      setState(() => _session = s);
    } catch (e) {
      if (mounted) {
        setState(() => _session = const PlayerSession(
            textureId: -1, videoWidth: 0, videoHeight: 0));
      }
    }
  }

  @override
  void dispose() {
    _frameSub?.cancel();
    _hudTimer?.cancel();
    AnantPlay.instance.playerDispose();
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    SystemChrome.setPreferredOrientations(DeviceOrientation.values);
    super.dispose();
  }

  void _armHudTimer() {
    _hudTimer?.cancel();
    _hudTimer = Timer(const Duration(seconds: 4), () {
      if (mounted && !_locked) setState(() => _hudVisible = false);
    });
  }

  void _toggleHud() {
    setState(() => _hudVisible = !_hudVisible);
    if (_hudVisible) _armHudTimer();
  }

  Future<void> _togglePlay() async {
    if (_frame.playing) {
      await AnantPlay.instance.playerPause();
    } else {
      await AnantPlay.instance.playerPlay();
    }
    _armHudTimer();
  }

  Future<void> _seekBy(int deltaMs) async {
    final target =
        (_frame.positionMs + deltaMs).clamp(0, _frame.durationMs > 0 ? _frame.durationMs : 1 << 40);
    await AnantPlay.instance.playerSeek(target);
    _armHudTimer();
  }

  String _clock(int ms) {
    final s = ms ~/ 1000;
    final h = s ~/ 3600;
    final m = (s % 3600) ~/ 60;
    final sec = s % 60;
    String two(int v) => v.toString().padLeft(2, '0');
    return h > 0 ? '$h:${two(m)}:${two(sec)}' : '${two(m)}:${two(sec)}';
  }

  // ── layout math: VideoViewport's Fit/Fill/16:9/Stretch in Dart ────────────

  BoxConstraints _videoConstraints(BoxConstraints parent) {
    final vw = _frame.videoWidth <= 0 ? _session.videoWidth : _frame.videoWidth;
    final vh = _frame.videoHeight <= 0 ? _session.videoHeight : _frame.videoHeight;
    if (vw <= 0 || vh <= 0) return parent; // no geometry yet — fill.
    final videoAspect = (vw * (vh > 0 ? 1 : 1)) / (vh == 0 ? 1 : vh);
    final screenAspect = parent.maxWidth / parent.maxHeight;
    double w, h;
    switch (_aspect) {
      case 'fill':
        w = parent.maxWidth;
        h = parent.maxHeight;
        break;
      case '16:9':
        w = parent.maxWidth;
        h = w / (16 / 9);
        if (h > parent.maxHeight) {
          h = parent.maxHeight;
          w = h * (16 / 9);
        }
        break;
      case 'stretch':
        w = parent.maxWidth;
        h = parent.maxHeight;
        break;
      default: // fit
        if (videoAspect > screenAspect) {
          w = parent.maxWidth;
          h = w / videoAspect;
        } else {
          h = parent.maxHeight;
          w = h * videoAspect;
        }
    }
    return BoxConstraints.tight(Size(w, h));
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final s = AppState.of(context).settings;
    final hasVideo = _session.textureId >= 0;
    return Scaffold(
      backgroundColor: Colors.black,
      body: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: _locked ? _showLockHint : _toggleHud,
        onDoubleTapDown: (d) {
          if (_locked) return;
          final half = MediaQuery.sizeOf(context).width / 2;
          _seekBy(d.globalPosition.dx < half ? -10000 : 10000);
        },
        child: Stack(
          fit: StackFit.expand,
          children: [
            // ── THE VIDEO (Flutter Texture on the Media3 surface) ────────────
            if (hasVideo)
              Center(
                child: LayoutBuilder(builder: (context, c) {
                  return Container(
                    constraints: _videoConstraints(c),
                    child: Texture(textureId: _session.textureId),
                  );
                }),
              )
            else
              Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    if (_frame.subtitleState.startsWith('error') ||
                        _session.textureId == -2)
                      Icon(mi.Icons.error_outline_rounded,
                          size: 42, color: scheme.error)
                    else
                      const SizedBox(
                        width: 42,
                        height: 42,
                        child: CircularProgressIndicator(strokeWidth: 3),
                      ),
                    const SizedBox(height: 14),
                    Text(
                      _frame.subtitleState.isEmpty
                          ? 'Opening the stream…'
                          : _frame.subtitleState,
                      style: const TextStyle(
                          color: Colors.white70, fontSize: 13),
                    ),
                    const SizedBox(height: 18),
                    TextButton.icon(
                      icon: const Icon(mi.Icons.arrow_back_rounded, size: 18),
                      label: const Text('Back'),
                      onPressed: widget.onExit,
                    ),
                  ],
                ),
              ),

            // ── the readability scrim under the HUD ─────────────────────────
            if (_hudVisible && hasVideo)
              Positioned.fill(
                child: IgnorePointer(
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                      gradient: LinearGradient(
                        begin: Alignment.topCenter,
                        end: Alignment.bottomCenter,
                        stops: const [0, 0.25, 0.75, 1],
                        colors: [
                          Colors.black.withValues(alpha: 0.55),
                          Colors.transparent,
                          Colors.transparent,
                          Colors.black.withValues(alpha: 0.6),
                        ],
                      ),
                    ),
                  ),
                ),
              ),

            // ── the bottom-anchored Sinhala subtitle ─────────────────────────
            if (!_hudVisible || hasVideo)
              Positioned(
                left: 16,
                right: 16,
                bottom: _subBottom(s),
                child: IgnorePointer(
                  child: Center(
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 14, vertical: 6),
                      decoration: _subDecoration(s),
                      child: Text(
                        _frame.activeCueSi,
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          fontFamily: 'Noto Sans Sinhala',
                          fontSize: _subFontSp(s).clamp(12.0, 36.0),
                          height: _subLineHeight(s),
                          color: _subColor(s),
                          fontWeight: s.b(SettingsMirror.kSubBold, false)
                              ? FontWeight.w700
                              : FontWeight.w500,
                        ),
                      ),
                    ),
                  ),
                ),
              ),

            // ── THE TOP GLASS BAR ────────────────────────────────────────────
            if (_hudVisible && !_locked)
              Positioned(
                top: 0,
                left: 0,
                right: 0,
                child: SafeArea(
                  bottom: false,
                  child: Padding(
                    padding:
                        const EdgeInsets.fromLTRB(12, 8, 12, 8),
                    child: MornyeGlassPanel(
                      radius: 26,
                      child: Padding(
                        padding: const EdgeInsets.symmetric(
                            horizontal: 6, vertical: 4),
                        child: Row(
                          children: [
                            _GlassIconButton(
                              icon: mi.Icons.arrow_back_rounded,
                              tooltip: 'Back',
                              onTap: widget.onExit,
                            ),
                            const SizedBox(width: 6),
                            Expanded(
                              child: Column(
                                crossAxisAlignment:
                                    CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    (widget.request.title ?? '').isEmpty
                                        ? (widget.request.test
                                            ? 'Sinhala Player Test'
                                            : widget.request.url)
                                        : (widget.request.title ?? ''),
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: const TextStyle(
                                        color: Colors.white,
                                        fontSize: 14,
                                        fontWeight: FontWeight.w600),
                                  ),
                                  if (_frame.subtitleState.isNotEmpty)
                                    Text(
                                      _frame.subtitleState,
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                      style: TextStyle(
                                          color: Colors.white
                                              .withValues(alpha: 0.6),
                                          fontSize: 10.5),
                                    ),
                                ],
                              ),
                            ),
                            _GlassIconButton(
                              icon: mi.Icons.lock_outline_rounded,
                              tooltip: 'Lock controls',
                              onTap: () =>
                                  setState(() => _locked = true),
                            ),
                            _GlassIconButton(
                              icon: mi.Icons.screen_rotation_rounded,
                              tooltip: 'Rotate',
                              onTap: _rotate,
                            ),
                            _GlassIconButton(
                              icon: mi.Icons.picture_in_picture_alt_rounded,
                              tooltip: 'Picture in picture',
                              onTap: _enterPip,
                            ),
                            _GlassIconButton(
                              icon: mi.Icons.open_in_new_rounded,
                              tooltip: 'External player (VLC)',
                              onTap: _external,
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              ),

            // ── the lock veil (only a unlock button survives) ────────────────
            if (_locked)
              Positioned(
                top: 0,
                right: 0,
                child: SafeArea(
                  child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: _GlassIconButton(
                      icon: mi.Icons.lock_open_rounded,
                      tooltip: 'Unlock',
                      onTap: () => setState(() => _locked = false),
                    ),
                  ),
                ),
              ),

            // ── THE CENTER CONTROLS ──────────────────────────────────────────
            if (_hudVisible && !_locked)
              Center(
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    _GlassIconButton(
                      icon: mi.Icons.replay_10_rounded,
                      tooltip: 'Back 10 seconds',
                      size: 30,
                      onTap: () => _seekBy(-10000),
                    ),
                    const SizedBox(width: 26),
                    GestureDetector(
                      onTap: _togglePlay,
                      child: Container(
                        width: 68,
                        height: 68,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          color: scheme.primary.withValues(alpha: 0.9),
                          boxShadow: [
                            BoxShadow(
                              color: scheme.primary.withValues(alpha: 0.4),
                              blurRadius: 24,
                            ),
                          ],
                        ),
                        child: Icon(
                          _frame.playing
                              ? mi.Icons.pause_rounded
                              : mi.Icons.play_arrow_rounded,
                          color: scheme.onPrimary,
                          size: 38,
                        ),
                      ),
                    ),
                    const SizedBox(width: 26),
                    _GlassIconButton(
                      icon: mi.Icons.forward_10_rounded,
                      tooltip: 'Forward 10 seconds',
                      size: 30,
                      onTap: () => _seekBy(10000),
                    ),
                  ],
                ),
              ),

            // ── the buffering spinner over the center ────────────────────────
            if (_frame.buffering && hasVideo)
              const Center(
                child: SizedBox(
                  width: 44,
                  height: 44,
                  child: CircularProgressIndicator(strokeWidth: 3),
                ),
              ),

            // ── THE BOTTOM GLASS BAR (seek + the 5-icon dock) ────────────────
            if (_hudVisible && !_locked)
              Positioned(
                left: 0,
                right: 0,
                bottom: 0,
                child: SafeArea(
                  top: false,
                  child: Padding(
                    padding:
                        const EdgeInsets.fromLTRB(12, 0, 12, 10),
                    child: MornyeGlassPanel(
                      radius: 28,
                      child: Padding(
                        padding: const EdgeInsets.fromLTRB(14, 10, 14, 8),
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            // the tactile seek bar with buffered progress
                            Row(
                              children: [
                                SizedBox(
                                  width: 44,
                                  child: Text(_clock(_frame.positionMs),
                                      style: const TextStyle(
                                          color: Colors.white70,
                                          fontSize: 11,
                                          fontFeatures: [
                                            FontFeature.tabularFigures()
                                          ])),
                                ),
                                Expanded(
                                  child: GestureDetector(
                                    behavior: HitTestBehavior.opaque,
                                    onHorizontalDragStart: (_) {
                                      setState(() {
                                        _draggingSeek = true;
                                        _seekValue = _frame.durationMs > 0
                                            ? _frame.positionMs /
                                                _frame.durationMs
                                            : 0;
                                      });
                                    },
                                    onHorizontalDragUpdate: (d) {
                                      setState(() => _seekValue =
                                          (_seekValue +
                                                  d.primaryDelta! / 600)
                                              .clamp(0.0, 1.0));
                                    },
                                    onHorizontalDragEnd: (_) async {
                                      final target = (_seekValue *
                                              _frame.durationMs)
                                          .round();
                                      await AnantPlay.instance
                                          .playerSeek(target);
                                      setState(
                                          () => _draggingSeek = false);
                                    },
                                    child: SizedBox(
                                      height: 26,
                                      child: Stack(
                                        alignment:
                                            Alignment.centerLeft,
                                        children: [
                                          // the track
                                          Container(
                                            height: 4,
                                            decoration: BoxDecoration(
                                              borderRadius:
                                                  BorderRadius.circular(
                                                      2),
                                              color: Colors.white
                                                  .withValues(
                                                      alpha: 0.2),
                                            ),
                                          ),
                                          // the buffered fill
                                          FractionallySizedBox(
                                            widthFactor: _frame
                                                        .durationMs >
                                                    0
                                                ? (_frame.bufferedMs /
                                                        _frame
                                                            .durationMs)
                                    .clamp(0.0, 1.0)
                                                : 0.0,
                                            child: Container(
                                              height: 4,
                                              color: Colors.white
                                                  .withValues(
                                                      alpha: 0.35),
                                            ),
                                          ),
                                          // the played fill
                                          FractionallySizedBox(
                                            widthFactor: _draggingSeek
                                                ? _seekValue
                                                : (_frame.durationMs >
                                                        0
                                                    ? (_frame.positionMs /
                                                            _frame
                                                                .durationMs)
                                                        .clamp(
                                                            0.0, 1.0)
                                                    : 0.0),
                                            child: Container(
                                              height: 4,
                                              decoration: BoxDecoration(
                                                borderRadius:
                                                    BorderRadius
                                                        .circular(2),
                                                color:
                                                    scheme.primary,
                                              ),
                                            ),
                                          ),
                                        ],
                                      ),
                                    ),
                                  ),
                                ),
                                SizedBox(
                                  width: 52,
                                  child: Text(
                                      _clock(_frame.durationMs),
                                      textAlign: TextAlign.right,
                                      style: const TextStyle(
                                          color: Colors.white70,
                                          fontSize: 11,
                                          fontFeatures: [
                                            FontFeature.tabularFigures()
                                          ])),
                                ),
                              ],
                            ),
                            const SizedBox(height: 2),
                            // ── THE FIXED 5-ICON MORNYE DOCK ──────────────
                            Row(
                              mainAxisAlignment:
                                  MainAxisAlignment.spaceEvenly,
                              children: [
                                _DockButton(
                                  icon: mi.Icons
                                      .subtitles_rounded,
                                  label: 'Subtitles',
                                  accent: _frame.activeCueSi
                                          .isNotEmpty ||
                                      _frame.subtitleState
                                          .startsWith('ready'),
                                  onTap: _openSubtitlesSheet,
                                ),
                                _DockButton(
                                  icon: mi.Icons
                                      .graphic_eq_rounded,
                                  label: 'Tracks',
                                  onTap: _openTracksSheet,
                                ),
                                _DockButton(
                                  icon: mi.Icons
                                      .aspect_ratio_rounded,
                                  label: _aspectLabel(),
                                  onTap: _cycleAspect,
                                ),
                                _DockButton(
                                  icon: mi.Icons.speed_rounded,
                                  label:
                                      '${_speed.toStringAsFixed(1)}×',
                                  onTap: _openSpeedSheet,
                                ),
                                _DockButton(
                                  icon: mi.Icons.search_rounded,
                                  label: 'Search',
                                  onTap: _openSearchSheet,
                                ),
                              ],
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }

  // ── subtitle styling (live from the Subtitle Studio settings) ──────────────

  double _subBottom(SettingsMirror s) {
    final pct =
        s.i(SettingsMirror.kSubBottomPct, 8).clamp(2, 35) / 100.0;
    final portrait =
        MediaQuery.sizeOf(context).height > MediaQuery.sizeOf(context).width;
    // 8% portrait / 5% landscape default, from the studio setting.
    final base = portrait ? 0.08 : 0.05;
    return MediaQuery.sizeOf(context).height * (pct > 0 ? pct : base);
  }

  double _subFontSp(SettingsMirror s) {
    return s.i(SettingsMirror.kFontSpKey, 16).toDouble();
  }

  double _subLineHeight(SettingsMirror s) {
    return (s.i(SettingsMirror.kSubLineSpacing, 100)).clamp(100, 150) / 100.0 + 0.35;
  }

  Color _subColor(SettingsMirror s) {
    const presets = [
      Color(0xFFFFFFFF), Color(0xFFFFD866), Color(0xFF7FE0C8),
      Color(0xFF9FC7FF), Color(0xFFFFA8C5), Color(0xFFE2C7FF),
    ];
    final i = s.i(SettingsMirror.kSubColor, 0).clamp(0, presets.length - 1);
    return presets[i];
  }

  BoxDecoration? _subDecoration(SettingsMirror s) {
    final style = s.s(SettingsMirror.kSubBg, 'pill');
    final opacity = s.i(SettingsMirror.kSubBgOpacity, 40) / 100.0;
    if (style == 'outline') {
      return BoxDecoration(
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: Colors.black54, width: 1.4),
      );
    }
    if (style == 'shadow') {
      return const BoxDecoration(
        boxShadow: [
          BoxShadow(color: Colors.black87, blurRadius: 8, spreadRadius: 1)
        ],
      );
    }
    return BoxDecoration(
      color: Colors.black.withValues(alpha: opacity * 0.9),
      borderRadius: style == 'pill'
          ? BorderRadius.circular(999)
          : BorderRadius.circular(8),
    );
  }

  // ── controls ───────────────────────────────────────────────────────────────

  void _showLockHint() {
    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
        content: Text('Locked — tap the unlock button'),
        duration: Duration(milliseconds: 1200)));
  }

  Future<void> _rotate() async {
    final portrait = MediaQuery.sizeOf(context).height >
        MediaQuery.sizeOf(context).width;
    await SystemChrome.setPreferredOrientations(portrait
        ? [DeviceOrientation.landscapeLeft]
        : [DeviceOrientation.portraitUp]);
  }

  Future<void> _enterPip() async {
    // PiP rides the native host (the Flutter engine keeps rendering in pip).
    try {
      await const MethodChannel('anantplay/cast')
          .invokeMethod('enterPip');
    } catch (_) {}
  }

  Future<void> _external() async {
    // hand the URL to the external player (VLC default) through the bridge.
    await const MethodChannel('anantplay/browser').invokeMethod(
        'openExternal', {'url': widget.request.url.isEmpty
            ? Media3TestUrl.value : widget.request.url});
  }

  String _aspectLabel() {
    switch (_aspect) {
      case 'fill':
        return 'Fill';
      case '16:9':
        return '16:9';
      case 'stretch':
        return 'Stretch';
      default:
        return 'Fit';
    }
  }

  Future<void> _cycleAspect() async {
    const order = ['fit', 'fill', '16:9', 'stretch'];
    final next = order[(order.indexOf(_aspect) + 1) % order.length];
    setState(() => _aspect = next);
    _armHudTimer();
  }

  // ── the three Mornye radius-32 spring sheets ───────────────────────────────

  void _openSubtitlesSheet() {
    final s = AppState.of(context).settings;
    final searchController = TextEditingController();
    final urlController = TextEditingController();
    showAppBottomSheet<void>(
      context: context,
      title: 'Subtitles',
      subtitle: _frame.subtitleState.isEmpty
          ? 'Sinhala · the 10-site ladder with subz.lk tier 1'
          : _frame.subtitleState,
      builder: (sheet) => StatefulBuilder(
        builder: (sheet, setSheet) => Padding(
          padding: const EdgeInsets.fromLTRB(8, 4, 8, 20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // language chips
              SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                child: Row(
                  children: [
                    for (final lang in const ['සිංහල', 'English'])
                      Padding(
                        padding: const EdgeInsets.only(right: 8),
                        child: MornyeFilterChip(
                          label: lang,
                          selected: lang == 'සිංහල',
                          onTap: () {},
                        ),
                      ),
                  ],
                ),
              ),
              const SizedBox(height: 10),
              // the title search row (Search Title)
              Row(
                children: [
                  Expanded(
                    child: TextField(
                      controller: searchController,
                      decoration: InputDecoration(
                        isDense: true,
                        hintText: 'Movie / series name (S1E2)',
                        border: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(12)),
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  FilledButton(
                    onPressed: () async {
                      await AnantPlay.instance.findSubtitles(
                          searchController.text.trim());
                      if (sheet.mounted) Navigator.pop(sheet);
                    },
                    child: const Text('Find SL subs'),
                  ),
                ],
              ),
              const SizedBox(height: 10),
              // a pasted subtitle URL
              Row(
                children: [
                  Expanded(
                    child: TextField(
                      controller: urlController,
                      decoration: InputDecoration(
                        isDense: true,
                        hintText: 'Subtitle URL (.srt / .zip)',
                        border: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(12)),
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  OutlinedButton(
                    onPressed: () async {
                      final ok = await AnantPlay.instance
                          .loadSubtitleUrl(urlController.text.trim());
                      if (sheet.mounted) Navigator.pop(sheet);
                      if (ok && mounted) {
                        ScaffoldMessenger.of(context).showSnackBar(
                            const SnackBar(
                                content: Text('Subtitles attached')));
                      }
                    },
                    child: const Text('Load'),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              // the sync stepper (live, the video keeps playing)
              Row(
                children: [
                  const Text('Sync',
                      style: TextStyle(fontSize: 13.5)),
                  const Spacer(),
                  _SmallGlassButton(
                    icon: mi.Icons.remove_rounded,
                    onTap: () async {
                      final step = s.i(SettingsMirror.kSyncStepMs, 250);
                      await AnantPlay.instance.shiftSubtitles(-step);
                      setSheet(() {});
                    },
                  ),
                  Padding(
                    padding: const EdgeInsets.symmetric(
                        horizontal: 14),
                    child: FutureBuilder<String>(
                      future: AnantPlay.instance.subtitleStatus(),
                      builder: (context, snap) => Text(
                        snap.data ?? '',
                        style: const TextStyle(
                            fontSize: 12.5,
                            color: Colors.white70),
                      ),
                    ),
                  ),
                  _SmallGlassButton(
                    icon: mi.Icons.add_rounded,
                    onTap: () async {
                      final step = s.i(SettingsMirror.kSyncStepMs, 250);
                      await AnantPlay.instance.shiftSubtitles(step);
                      setSheet(() {});
                    },
                  ),
                ],
              ),
              const SizedBox(height: 10),
              // the live style studio (the preview IS the player above)
              _SliderLine(
                title: 'Size',
                value: (_subFontSp(s) - 12) / 24,
                label: '${_subFontSp(s).round()}sp',
                onChanged: (v) => AppState.of(sheet)
                    .scope
                    .putInt(SettingsMirror.kFontSpKey,
                        (12 + v * 24).round().clamp(12, 36)),
              ),
              _SliderLine(
                title: 'Position',
                value: (s.i(SettingsMirror.kSubBottomPct, 8) - 2) / 33,
                label:
                    '${s.i(SettingsMirror.kSubBottomPct, 8)}%',
                onChanged: (v) => AppState.of(sheet)
                    .scope
                    .putInt(SettingsMirror.kSubBottomPct,
                        (2 + v * 33).round().clamp(2, 35)),
              ),
              const SizedBox(height: 6),
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton.icon(
                  icon: const Icon(mi.Icons.expand_more_rounded,
                      size: 18),
                  label: const Text('More tools'),
                  onPressed: () {
                    // Retry / Provider / Register / Free translate —
                    // each maps 1:1 onto the native pipeline.
                    showAppBottomSheet<void>(
                      context: sheet,
                      title: 'More subtitle tools',
                      builder: (more) => Padding(
                        padding: const EdgeInsets.fromLTRB(
                            8, 4, 8, 20),
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            for (final entry in const [
                              ('Retry search', 'retry'),
                              ('Free translate now', 'free'),
                              ('Spoken register', 'spoken'),
                              ('Formal register', 'formal'),
                            ])
                              ListTile(
                                dense: true,
                                title: Text(entry.$1),
                                trailing: const Icon(mi.Icons
                                    .chevron_right_rounded),
                                onTap: () {
                                  switch (entry.$2) {
                                    case 'retry':
                                      AnantPlay.instance.findSubtitles(
                                          widget.request.title ??
                                              widget.request.url);
                                    case 'free':
                                      AnantPlay.instance
                                          .loadSubtitleUrl('');
                                    case 'spoken':
                                      AppState.of(more).scope.putString(
                                          'registerOverride', 'spoken');
                                    case 'formal':
                                      AppState.of(more).scope.putString(
                                          'registerOverride', 'formal');
                                  }
                                  Navigator.pop(more);
                                },
                              ),
                          ],
                        ),
                      ),
                    );
                  },
                ),
              ),
            ],
          ),
        ),
      ),
    );
    _armHudTimer();
  }

  void _openTracksSheet() {
    showAppBottomSheet<void>(
      context: context,
      title: 'Audio & quality',
      subtitle: 'Real Media3 track selection',
      builder: (sheet) => FutureBuilder<List<PlayerTrackGroup>>(
        future: AnantPlay.instance.playerTracks(),
        builder: (context, snap) {
          final groups = snap.data ?? const [];
          if (groups.isEmpty) {
            return const Padding(
              padding: EdgeInsets.all(20),
              child: Text('No selectable tracks (single rendition).'),
            );
          }
          return Padding(
            padding: const EdgeInsets.fromLTRB(8, 4, 8, 20),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                for (final g in groups) ...[
                  Padding(
                    padding: const EdgeInsets.only(
                        left: 8, top: 8, bottom: 4),
                    child: Text(
                        g.type == 'video'
                            ? 'Video quality'
                            : 'Audio language',
                        style: const TextStyle(
                            fontSize: 12.5,
                            fontWeight: FontWeight.w600)),
                  ),
                  for (final t in g.tracks)
                    ListTile(
                      dense: true,
                      selected: t.index == g.selected,
                      leading: Icon(
                          t.index == g.selected
                              ? mi.Icons.check_circle_rounded
                              : mi.Icons
                                  .radio_button_unchecked_rounded,
                          size: 20),
                      title: Text(t.label),
                      onTap: () async {
                        await AnantPlay.instance
                            .playerSelectTrack(
                                g.type == 'video' ? 2 : 1, t.index);
                        if (sheet.mounted) Navigator.pop(sheet);
                      },
                    ),
                ],
              ],
            ),
          );
        },
      ),
    );
    _armHudTimer();
  }

  void _openSpeedSheet() {
    showAppBottomSheet<void>(
      context: context,
      title: 'Playback speed',
      builder: (sheet) => Padding(
        padding: const EdgeInsets.fromLTRB(8, 4, 8, 20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (final v in [0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 2.5])
              ListTile(
                dense: true,
                selected: v == _speed,
                leading: Icon(
                    v == _speed
                        ? mi.Icons.check_circle_rounded
                        : mi.Icons.radio_button_unchecked_rounded,
                    size: 20),
                title: Text('${v.toStringAsFixed(v == v.roundToDouble() ? 1 : 2)}×'),
                onTap: () async {
                  await AnantPlay.instance.playerSetSpeed(v);
                  setState(() => _speed = v);
                  if (sheet.mounted) Navigator.pop(sheet);
                },
              ),
          ],
        ),
      ),
    );
    _armHudTimer();
  }

  void _openSearchSheet() {
    final controller = TextEditingController(
        text: widget.request.title);
    showAppBottomSheet<void>(
      context: context,
      title: 'Search title',
      subtitle: 'Type a name → instant Sinhala subtitles',
      builder: (sheet) => Padding(
        padding: const EdgeInsets.fromLTRB(8, 4, 8, 20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            TextField(
              controller: controller,
              autofocus: true,
              decoration: InputDecoration(
                hintText: 'e.g. Interstellar, Breaking Bad S01E02',
                border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12)),
              ),
            ),
            const SizedBox(height: 12),
            FilledButton.icon(
              icon: const Icon(mi.Icons.search_rounded, size: 18),
              label: const Text('Find Sinhala subtitles'),
              onPressed: () async {
                await AnantPlay.instance
                    .findSubtitles(controller.text.trim());
                if (sheet.mounted) Navigator.pop(sheet);
              },
            ),
          ],
        ),
      ),
    );
    _armHudTimer();
  }
}

/// The Mux HLS test stream constant (kept in sync with Media3TextureBridge).
class Media3TestUrl {
  static const value = 'https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8';
  const Media3TestUrl();
}

// ── the glass HUD controls ───────────────────────────────────────────────────

class _GlassIconButton extends StatelessWidget {
  const _GlassIconButton({
    required this.icon,
    required this.tooltip,
    required this.onTap,
    this.size = 22,
  });

  final IconData icon;
  final String tooltip;
  final VoidCallback onTap;
  final double size;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Tooltip(
      message: tooltip,
      child: Material(
        color: Colors.white.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(999),
        child: InkWell(
          borderRadius: BorderRadius.circular(999),
          onTap: onTap,
          child: SizedBox(
            width: 40,
            height: 40,
            child: Icon(icon, size: size, color: scheme.onSurface),
          ),
        ),
      ),
    );
  }
}

class _SmallGlassButton extends StatelessWidget {
  const _SmallGlassButton({required this.icon, required this.onTap});

  final IconData icon;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.white.withValues(alpha: 0.1),
      borderRadius: BorderRadius.circular(999),
      child: InkWell(
        borderRadius: BorderRadius.circular(999),
        onTap: onTap,
        child: SizedBox(
          width: 34,
          height: 34,
          child: Icon(icon, size: 18, color: Colors.white),
        ),
      ),
    );
  }
}

class _DockButton extends StatelessWidget {
  const _DockButton({
    required this.icon,
    required this.label,
    required this.onTap,
    this.accent = false,
  });

  final IconData icon;
  final String label;
  final VoidCallback onTap;
  final bool accent;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return InkWell(
      borderRadius: BorderRadius.circular(14),
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Stack(
              clipBehavior: Clip.none,
              children: [
                Icon(icon,
                    size: 22,
                    color: accent ? scheme.primary : Colors.white),
                if (accent)
                  Positioned(
                    right: -3,
                    top: -1,
                    child: Container(
                      width: 7,
                      height: 7,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: scheme.primary,
                        border: Border.all(color: Colors.black87, width: 1),
                      ),
                    ),
                  ),
              ],
            ),
            const SizedBox(height: 3),
            Text(label,
                style: TextStyle(
                    color:
                        accent ? scheme.primary : Colors.white70,
                    fontSize: 10.5,
                    fontWeight: FontWeight.w600)),
          ],
        ),
      ),
    );
  }
}

class _SliderLine extends StatelessWidget {
  const _SliderLine({
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
    return Row(
      children: [
        SizedBox(
            width: 64,
            child: Text(title,
                style: const TextStyle(fontSize: 13))),
        Expanded(
          child: Slider(
            value: value.clamp(0.0, 1.0),
            onChanged: onChanged,
          ),
        ),
        SizedBox(
            width: 44,
            child: Text(label,
                textAlign: TextAlign.right,
                style: const TextStyle(
                    fontSize: 12, color: Colors.white70))),
      ],
    );
  }
}
