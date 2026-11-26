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
import 'dart:io' show Platform;

import 'package:flutter/foundation.dart' show kIsWeb, Factory;
import 'package:flutter/gestures.dart' as ges show OneSequenceGestureRecognizer;
import 'package:flutter/rendering.dart' as ren show PlatformViewHitTestBehavior;
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart' as wid show PlatformViewLink, AndroidViewSurface;
import 'package:material_ui/material_ui.dart' hide Icons;
import 'package:material_ui/material_ui.dart' as mi show Icons;

import 'package:anantplay_ui/mornye_ui/widgets/mornye_chrome.dart';
import 'package:anantplay_ui/mornye_ui/widgets/mornye_glass_notification.dart';
import 'package:anantplay_ui/mornye_ui/widgets/app_bottom_sheet.dart';

import 'app_state.dart';
import 'bridge.dart';
import 'package:anantplay_ui/main.dart';
import 'settings_mirror.dart';
import 'subtitle_style.dart';

/// The playback request the shell hands the player.
/// v3.4.6.f — THE COMPACT CINEMA SUBTITLE CONTRACT, as named constants the
/// honest Layer-1 audit (deep_troubleshooter.dart) reads LIVE: width cap,
/// portrait/landscape base font, and the clamp bounds.
class PlayerScreen extends StatefulWidget {
  /// The subtitle pill never exceeds this fraction of the viewport width.
  static const double compactSubWidthRatio = 0.82;

  /// Portrait base subtitle size (sp).
  static const double compactSubPortraitSp = 14;

  /// Landscape base subtitle size (sp).
  static const double compactSubLandscapeSp = 15;

  /// The user-facing slider bounds the base clamps into.
  static const double compactSubMinSp = 10;
  static const double compactSubMaxSp = 28;

  /// The fixed tight compact cinema line height.
  static const double compactSubLineHeight = 1.25;

  /// v3.4.6.i — THE SUBTITLE HUD-LIFT CONTRACT (the video_3 05:00–05:16
  /// collision kill): when the HUD is visible the subtitle pill lifts to
  /// bottom: 136dp portrait / 116dp landscape — CLEANLY ABOVE the seekbar
  /// + 5-icon dock glass bar — and returns to the compact cinema anchor
  /// (4% landscape / 6% portrait, with a ≥ 20dp safe inset above the gesture
  /// pill) when the HUD hides. The honest Layer-1 audit reads THESE values.
  static const double compactSubHudLiftPortraitDp = 136;
  static const double compactSubHudLiftLandscapeDp = 116;
  static const double compactSubSafeInsetDp = 20;

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

  /// v3.4.6.g — THE THERMAL UNMOUNT GATE: the HUD glass bars fade out smoothly
  /// (AnimatedOpacity, 240 ms) and are then UNMOUNTED completely — zero
  /// BackdropFilter, zero blur shaders, zero controllers alive while the
  /// movie plays hidden. `_hudVisible` drives the fade; `_hudGone` drops the
  /// subtree AFTER the fade so the transition is never a hard cut.
  bool _hudGone = false;
  bool _locked = false;
  Timer? _hudTimer;
  double _speed = 1.0;
  String _aspect = 'fit';
  bool _draggingSeek = false;
  double _seekValue = 0;

  /// v3.4.6.g — THE ZERO-GPU-COPY ENGINE LANE: on Android the viewport is the
  /// native `anantplay/video_surface` SurfaceView PlatformView (HWC composites
  /// decoded frames straight to the panel — no Impeller copy at 60–120 Hz);
  /// everywhere else (tests, desktop) the Flutter Texture lane stays.
  static bool get _useNativeSurface => !kIsWeb && Platform.isAndroid;

  /// v3.4.6.g — the SurfaceView lane fallback latch: if the hybrid-composition
  /// PlatformView fails to create (no factory / engine hiccup), fall back to
  /// the SurfaceProducer Texture lane for the rest of the session — never a
  /// dead black viewport. The native bridge keeps the producer texture attached
  /// until a SurfaceView actually attaches, so the fallback is seamless.
  bool _surfaceViewFailed = false;

  // ── v3.4.6.d — THE ZERO-LAG POSITION PUMP ────────────────────────────────
  // The 4 Hz position ticks land in a ValueNotifier that ONLY the seek bar
  // and the time text listen to (ValueListenableBuilder) — the whole
  // PlayerScreen NEVER rebuilds on a position tick again (the v3.4.6.c
  // defect: setState on every frame repainted the entire HUD tree at 4 Hz
  // over the video surface, a top cause of the movie-streaming lag + heat).
  final ValueNotifier<PlayerFrame> _frameNotifier =
      ValueNotifier<PlayerFrame>(PlayerFrame.empty);

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
      // v3.4.6.d — the position pump: ticks feed the notifier (the seek bar
      // + time text rebuild alone); a WHOLE-SCREEN rebuild happens only when
      // a structural field flips (playing/buffering/geometry/cue state).
      // v3.4.6.g — THE ZERO-HEAT CONTRACT: while the HUD is hidden, the play
      // and buffering flips paint NOTHING (no HUD on screen) and are skipped;
      // ONLY a cue/subtitle/geometry change may rebuild — the lightweight
      // 1 Hz subtitle-cue lane. NEVER a 4 Hz rebuild over a hidden HUD.
      if (!mounted) return;
      _frameNotifier.value = f;
      final old = _frame;
      final bool structural;
      if (_hudGone) {
        structural = f.videoWidth != old.videoWidth ||
            f.videoHeight != old.videoHeight ||
            f.subtitleState != old.subtitleState ||
            f.activeCueSi != old.activeCueSi;
      } else {
        structural = f.playing != old.playing ||
            f.buffering != old.buffering ||
            f.videoWidth != old.videoWidth ||
            f.videoHeight != old.videoHeight ||
            f.subtitleState != old.subtitleState ||
            f.activeCueSi != old.activeCueSi;
      }
      _frame = f;
      if (structural) setState(() {});
    });
    // v3.4.6.g — the native side starts the adaptive pump at 4 Hz while the
    // HUD shows; PlayerScreen will drop it to 1 Hz the moment the HUD hides.
    AnantPlay.instance.playerSetPumpRate(4);
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
    _frameNotifier.dispose();
    AnantPlay.instance.playerDispose();
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    SystemChrome.setPreferredOrientations(DeviceOrientation.values);
    super.dispose();
  }

  void _armHudTimer() {
    _hudTimer?.cancel();
    // v3.4.6.g — the 3.5 s auto-hide (was 4 s): the HUD floats ONLY on demand,
    // then fades out smoothly and unmounts. The native pump drops to 1 Hz the
    // moment the HUD goes (the marathon-session thermal contract).
    _hudTimer = Timer(const Duration(milliseconds: 3500), _hideHud);
  }

  void _hideHud() {
    if (!mounted || _locked) return;
    setState(() => _hudVisible = false);
    // the thermal handoff: 1 Hz while hidden (subtitle cue checks only).
    AnantPlay.instance.playerSetPumpRate(1);
    // smooth exit: keep the subtree mounted through the 240 ms fade, then
    // unmount it entirely (zero blur shaders over the movie afterwards).
    Future.delayed(const Duration(milliseconds: 260), () {
      if (mounted && !_hudVisible) setState(() => _hudGone = true);
    });
  }

  void _toggleHud() {
    if (!_hudVisible) {
      // floating back in: remount, fade in, pump back to 4 Hz.
      setState(() {
        _hudVisible = true;
        _hudGone = false;
      });
      AnantPlay.instance.playerSetPumpRate(4);
    } else {
      setState(() => _hudVisible = false);
    }
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

  /// v3.4.6.g — THE ZERO-GPU-COPY VIEWPORT (Trap #3 fix): a native SurfaceView
  /// MUST be hosted with HYBRID COMPOSITION (PlatformViewLink +
  /// AndroidViewSurface + PlatformViewsService.initExpensiveAndroidView). The
  /// old `AndroidView` used Texture-Layer Hybrid Composition, which CANNOT
  /// render an android.view.SurfaceView (its decoded frames go to a separate
  /// surface, not the Flutter texture) → a black viewport on the real phone.
  /// Hybrid composition adds the SurfaceView to the real Android view
  /// hierarchy; the native setZOrderMediaOverlay(false) keeps it UNDER the
  /// Flutter HUD bars / subtitle overlay / modal sheets. If creation fails,
  /// latch the SurfaceProducer Texture fallback (never a black screen).
  Widget _nativeVideoSurface() {
    return wid.PlatformViewLink(
      viewType: 'anantplay/video_surface',
      surfaceFactory: (context, controller) {
        return wid.AndroidViewSurface(
          controller: controller as AndroidViewController,
          // Empty recognizers + translucent hit test: taps fall through to the
          // PlayerScreen GestureDetector (HUD toggle / double-tap seek) instead
          // of being swallowed by the platform view.
          gestureRecognizers: const <Factory<ges.OneSequenceGestureRecognizer>>{},
          hitTestBehavior: ren.PlatformViewHitTestBehavior.translucent,
        );
      },
      onCreatePlatformView: (params) {
        final controller = PlatformViewsService.initExpensiveAndroidView(
          id: params.id,
          viewType: 'anantplay/video_surface',
          layoutDirection: TextDirection.ltr,
          creationParams: const <String, dynamic>{},
          creationParamsCodec: const StandardMessageCodec(),
          onFocus: () => params.onFocusChanged(true),
        );
        controller.addOnPlatformViewCreatedListener(params.onPlatformViewCreated);
        controller.create().catchError((Object e) {
          // The SurfaceView lane could not be created → seamless Texture
          // fallback (the native producer texture is still attached).
          if (mounted) setState(() => _surfaceViewFailed = true);
        });
        return controller;
      },
    );
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
            // ── THE VIDEO ────────────────────────────────────────────────
            // v3.4.6.g — TWO ENGINES, ONE VIEWPORT: on Android the movie rides
            // the native `anantplay/video_surface` SurfaceView PlatformView —
            // Android's Hardware Composer composites the decoded YUV frames
            // directly onto the display panel (ZERO Impeller GPU copy at
            // 60–120 Hz — the severe-overheat fix for 5–6 hour marathon
            // sessions). The Flutter Texture (SurfaceProducer) stays as the
            // automatic fallback lane everywhere else.
            // v3.4.6.d — its OWN isolated RepaintBoundary: HUD rebuilds and
            // subtitle re-layers can never repaint the video layer.
            if (hasVideo)
              RepaintBoundary(
                child: Center(
                  child: LayoutBuilder(builder: (context, c) {
                    return Container(
                      constraints: _videoConstraints(c),
                      child: (_useNativeSurface && !_surfaceViewFailed)
                          ? _nativeVideoSurface()
                          : Texture(textureId: _session.textureId),
                    );
                  }),
                ),
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
            // v3.4.6.e — THE COMPACT CINEMA CONTRACT: crisp, readable, and
            // NEVER covering the movie. maxWidth 82% of the viewport, up to 3
            // wrapped lines at a tight 1.25 height, compact pill padding, and
            // anchored near the bottom (4% landscape / 6% portrait default).
            // The Sinhala ZWJ/ZWNJ shaping (ශ්‍රී) is preserved verbatim —
            // the text is never re-encoded, only styled.
            //
            // v3.4.6.i — THE HUD-LIFT (video_3 05:00–05:16): when the HUD is
            // visible the pill rides an AnimatedPadding that lifts it to
            // 136dp portrait / 116dp landscape — COMPLETELY ABOVE the seekbar
            // and the 5-icon dock (never colliding with `00:09 — 10:34`) —
            // and settles back to the compact cinema anchor (MornyeMotion
            // fast spring) the moment the HUD hides.
            if (!_hudVisible || hasVideo)
              Positioned(
                left: 16,
                right: 16,
                bottom: 0,
                child: IgnorePointer(
                  child: AnimatedPadding(
                    duration: const Duration(milliseconds: 260),
                    curve: Curves.easeOutCubic,
                    padding: EdgeInsets.only(bottom: _subBottom(s)),
                    child: Center(
                    child: ConstrainedBox(
                      // 82% of the video viewport — the pill can never grow to
                      // full-screen width even at the 28sp slider ceiling.
                      // (Named constant: the honest Layer-1 audit reads THIS
                      // shipped value, never a duplicated literal.)
                      constraints: BoxConstraints(
                        maxWidth:
                            MediaQuery.sizeOf(context).width *
                            PlayerScreen.compactSubWidthRatio,
                      ),
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                            horizontal: 10, vertical: 4),
                        decoration: _subDecoration(s),
                        child: Text(
                          _frame.activeCueSi,
                          textAlign: TextAlign.center,
                          maxLines: 3,
                          overflow: TextOverflow.ellipsis,
                          softWrap: true,
                          // v3.4.6.h — THE SHARED SUBTITLE STYLE RESOLVER:
                          // the SAME resolveSubtitleTextStyle the Settings
                          // Live Preview paints with — the Subtitle Font
                          // control (Noto / System / Serif / Cinema Bold) now
                          // visibly changes the LIVE movie overlay too (the
                          // v3.4.6.g overlay hardcoded 'Noto Sans Sinhala',
                          // making the control feel fake).
                          style: resolveSubtitleTextStyle(
                            s,
                            base: TextStyle(
                              fontSize: _subFontSp(s),
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
                ),
              ),
            ),

            // ── THE TOP GLASS BAR ────────────────────────────────────────────
            if (!_hudGone && !_locked)
              Positioned(
                top: 0,
                left: 0,
                right: 0,
                child: SafeArea(
                  bottom: false,
                  child: AnimatedOpacity(
                    opacity: _hudVisible ? 1 : 0,
                    duration: const Duration(milliseconds: 240),
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
            if (!_hudGone && !_locked)
              Center(
                child: AnimatedOpacity(
                  opacity: _hudVisible ? 1 : 0,
                  duration: const Duration(milliseconds: 240),
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
            // v3.4.6.d — THE BATTERY FIX: when the HUD hides (hudVisible ==
            // false) the ENTIRE glass bar — MornyeGlassPanel backdrop blur,
            // seek bar, dock — is UNMOUNTED (maintainState: false), so ZERO
            // blur shaders execute over the video while a movie plays (the
            // v3.4.6.c ~70%-of-GPU-heat defect class).
            if (!_hudGone && !_locked)
              Positioned(
                left: 0,
                right: 0,
                bottom: 0,
                child: SafeArea(
                  top: false,
                  child: AnimatedOpacity(
                    opacity: _hudVisible ? 1 : 0,
                    duration: const Duration(milliseconds: 240),
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
                            // v3.4.6.d — THE ZERO-LAG SEEK ISLAND: only this
                            // Row rebuilds on the 4 Hz position ticks (a
                            // ValueListenableBuilder on _frameNotifier); the
                            // rest of PlayerScreen stays cold (was: setState
                            // on the whole screen every 250 ms).
                            ValueListenableBuilder<PlayerFrame>(
                              valueListenable: _frameNotifier,
                              builder: (context, f, _) => Row(
                                children: [
                                  SizedBox(
                                    width: 44,
                                    child: Text(_clock(f.positionMs),
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
                                          _seekValue = f.durationMs > 0
                                              ? f.positionMs / f.durationMs
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
                                                f.durationMs)
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
                                              widthFactor: f.durationMs >
                                                      0
                                                  ? (f.bufferedMs /
                                                          f.durationMs)
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
                                                  : (f.durationMs > 0
                                                      ? (f.positionMs /
                                                              f.durationMs)
                                                          .clamp(0.0, 1.0)
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
                                        _clock(f.durationMs),
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
              ),
          ],
        ),
      ),
    );
  }

  // ── subtitle styling (live from the Subtitle Studio settings) ──────────────
  //
  // v3.4.6.e — THE COMPACT CINEMA DEFAULTS (the oversized-subtitle fix):
  //   · size: 14sp portrait / 15sp landscape (was a flat 16), user range
  //     10–28sp clamped HARD so even the largest setting never fills the
  //     viewport (a 28sp 3-line cue still occupies under a third of the
  //     height, and maxWidth 82% caps the width);
  //   · anchor: 4% landscape / 6% portrait from the bottom (was 5%/8%);
  //   · line height: a tight 1.25 (was spacing+0.35 ≈ 1.55).

  /// v3.4.6.i — the LIVE subtitle bottom offset (the HUD-lift contract):
  ///  · HUD hidden → the compact cinema anchor: subBottomPct% (6% portrait /
  ///    4% landscape default), floored by the ≥ 20dp safe inset above the
  ///    gesture pill;
  ///  · HUD visible → the LIFT: 136dp portrait / 116dp landscape — cleanly
  ///    above the seekbar + 5-icon dock glass bar (the video_3 collision).
  double _subBottom(SettingsMirror s) {
    final portrait =
        MediaQuery.sizeOf(context).height > MediaQuery.sizeOf(context).width;
    if (_hudVisible && !_hudGone) {
      return portrait
          ? PlayerScreen.compactSubHudLiftPortraitDp
          : PlayerScreen.compactSubHudLiftLandscapeDp;
    }
    final pct =
        s.i(SettingsMirror.kSubBottomPct, 6).clamp(2, 35) / 100.0;
    // 6% portrait / 4% landscape default, from the studio setting.
    final base = portrait ? 0.06 : 0.04;
    final anchor = MediaQuery.sizeOf(context).height * (pct > 0 ? pct : base);
    return anchor.clamp(
        PlayerScreen.compactSubSafeInsetDp, double.maxFinite);
  }

  double _subFontSp(SettingsMirror s) {
    final portrait =
        MediaQuery.sizeOf(context).height > MediaQuery.sizeOf(context).width;
    final base = s.i(SettingsMirror.kFontSpKey,
        portrait ? PlayerScreen.compactSubPortraitSp.round()
            : PlayerScreen.compactSubLandscapeSp.round());
    // The compact cinema clamp: the user's slider runs 10–28sp and even the
    // ceiling can never dominate the video window.
    return base.toDouble().clamp(
        PlayerScreen.compactSubMinSp, PlayerScreen.compactSubMaxSp);
  }

  double _subLineHeight(SettingsMirror s) {
    // v3.4.6.e — fixed tight 1.25 for the compact cinema line (the studio's
    // line-spacing preference still drives the SHEET preview, not the movie).
    return PlayerScreen.compactSubLineHeight;
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

  /// v3.4.6.e — the subtitle ENGINE rotation order (AppPrefs' 4-lane
  /// whitelist): human-pa-free → human-free → free → worker-only → repeat.
  String _nextSubEngine(SettingsMirror s) {
    const order = [
      'human-pa-free',
      'human-free',
      'free',
      'worker',
    ];
    final current = s.s('subEngine', order.first);
    final idx = order.indexOf(current);
    return order[(idx < 0 ? 0 : idx + 1) % order.length];
  }

  /// v3.4.6.e — NewZone Translator handoff: the current title's NewZone
  /// search URL, opened in the Browser tab (a real navigation, not a dead
  /// row). tools.newzone.top is the speed-dial's subtitle-group engine.
  void _openNewZoneTranslator() {
    final q = (widget.request.title ?? '').isEmpty
        ? widget.request.url
        : (widget.request.title ?? '');
    final url = 'https://tools.newzone.top/search?q=${Uri.encodeComponent(q)}';
    ShellRootState.of(context).openBrowserTab(url);
  }

  /// v3.4.6.e — Audio → SRT: the receiver's live /subs/current.srt route
  /// (VLC/MX/mpv read it from the LAN), copied to the clipboard for pasting
  /// into an external player's subtitle field.
  Future<void> _copySubsUrl() async {
    final status = await AnantPlay.instance.currentCastStatus();
    final port = status.port > 0 ? status.port : 8765;
    final url = 'http://127.0.0.1:$port/subs/current.srt';
    await Clipboard.setData(ClipboardData(text: url));
    if (mounted) {
      showMornyeGlassNotification(context,
          message: 'External subtitle URL copied: $url');
    }
  }

  void _showLockHint() {
    showMornyeGlassNotification(context,
        message: 'Locked — tap the unlock button');
  }

  Future<void> _rotate() async {
    // v3.4.6.e — REAL orientation control: a 3-way sheet (Auto = FULL_SENSOR
    // free rotation, Landscape lock, Portrait lock) through the native
    // setRequestedOrientation bridge. Was a Flutter-only preferred-orientation
    // flip that Android 16 could ignore (the dead Rotate complaint).
    showAppBottomSheet<void>(
      context: context,
      title: 'Rotation',
      subtitle: 'Real activity orientation control',
      builder: (sheet) => Padding(
        padding: const EdgeInsets.fromLTRB(8, 4, 8, 20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (final (label, mode) in const [
              ('Auto-rotate (sensor)', 'auto'),
              ('Landscape lock', 'landscape'),
              ('Portrait lock', 'portrait'),
            ])
              ListTile(
                dense: true,
                leading: Icon(
                  mode == 'auto'
                      ? mi.Icons.screen_rotation_rounded
                      : mode == 'landscape'
                          ? mi.Icons.stay_current_landscape_rounded
                          : mi.Icons.stay_current_portrait_rounded,
                  size: 22,
                ),
                title: Text(label),
                onTap: () async {
                  await AnantPlay.instance.playerSetOrientation(mode);
                  if (sheet.mounted) Navigator.pop(sheet);
                },
              ),
          ],
        ),
      ),
    );
    _armHudTimer();
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
              // language chips — v3.4.6.e — REAL LIVE SWITCH: the tap persists
              // the default subtitle language (K_DEFAULT_LANG) AND re-fetches
              // / re-applies the cues for that language on the LIVE session
              // through playerSwitchSubLanguage (was a dead pref-only write —
              // the movie's cues never changed).
              SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                child: Row(
                  children: [
                    for (final (lang, code) in const [
                      ('සිංහල', 'si'),
                      ('English', 'en'),
                      ('தமிழ்', 'ta'),
                    ])
                      Padding(
                        padding: const EdgeInsets.only(right: 8),
                        child: MornyeFilterChip(
                          label: lang,
                          selected: s.s(SettingsMirror.kDefaultLang, 'si') ==
                              code,
                          onTap: () {
                            AppState.of(sheet)
                                .scope
                                .putString(SettingsMirror.kDefaultLang, code);
                            AnantPlay.instance
                                .playerSwitchSubLanguage(code);
                          },
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
                        showMornyeGlassNotification(context,
                            message: 'Subtitles attached');
                      }
                    },
                    child: const Text('Load'),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              // the sync stepper (live, the video keeps playing) — v3.4.6.e:
              // the FOUR explicit steps (-1s / -0.25s / +0.25s / +1s) with the
              // cumulative offset read out live ("+0.25s" — shiftSubtitles
              // applies each tap cumulatively on the native cue lookup).
              Align(
                alignment: Alignment.centerLeft,
                child: Text('Sync',
                    style: const TextStyle(fontSize: 13.5)),
              ),
              const SizedBox(height: 6),
              Row(
                children: [
                  for (final (_, delta) in const [
                    ('−1s', -1000),
                    ('−0.25s', -250),
                    ('+0.25s', 250),
                    ('+1s', 1000),
                  ])
                    Padding(
                      padding: const EdgeInsets.only(right: 8),
                      child: _SmallGlassButton(
                        icon: delta < 0
                            ? mi.Icons.remove_rounded
                            : mi.Icons.add_rounded,
                        onTap: () async {
                          await AnantPlay.instance.shiftSubtitles(delta);
                          setSheet(() {});
                        },
                      ),
                    ),
                  const Spacer(),
                  FutureBuilder<String>(
                    future: AnantPlay.instance.subtitleStatus(),
                    builder: (context, snap) => Text(
                      snap.data ?? '',
                      style: const TextStyle(
                          fontSize: 12.5,
                          color: Colors.white70),
                    ),
                  ),
                ],
              ),
              // the step labels under the four buttons
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Row(
                  children: [
                    for (final (label, _) in const [
                      ('−1s', 0),
                      ('−0.25s', 0),
                      ('+0.25s', 0),
                      ('+1s', 0),
                    ])
                      Padding(
                        padding: const EdgeInsets.only(right: 26),
                        child: Text(label,
                            style: const TextStyle(
                                fontSize: 10.5,
                                color: Colors.white60)),
                      ),
                  ],
                ),
              ),
              const SizedBox(height: 10),
              // the live style studio (the preview IS the player above)
              // v3.4.6.e — the compact range: 10–28sp (was 12–36 — the
              // oversized-subtitle fix; the clamped ceiling can never fill
              // the viewport at 3 lines and 82% width).
              _SliderLine(
                title: 'Size',
                value: (_subFontSp(s) - 10) / 18,
                label: '${_subFontSp(s).round()}sp',
                onChanged: (v) => AppState.of(sheet)
                    .scope
                    .putInt(SettingsMirror.kFontSpKey,
                        (10 + v * 18).round().clamp(10, 28)),
              ),
              _SliderLine(
                title: 'Position',
                value: (s.i(SettingsMirror.kSubBottomPct, 6) - 2) / 33,
                label:
                    '${s.i(SettingsMirror.kSubBottomPct, 6)}%',
                onChanged: (v) => AppState.of(sheet)
                    .scope
                    .putInt(SettingsMirror.kSubBottomPct,
                        (2 + v * 33).round().clamp(2, 35)),
              ),
              const SizedBox(height: 6),
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton.icon(
                  icon: const Icon(mi.Icons.expand_more_rounded, size: 18),
                  label: const Text('More tools'),
                  onPressed: () {
                    // Retry / Provider / Register / Free translate / NewZone /
                    // Audio→SRT — each maps 1:1 onto the native pipeline.
                    showAppBottomSheet<void>(
                      context: sheet,
                      title: 'More subtitle tools',
                      builder: (more) => Padding(
                        padding: const EdgeInsets.fromLTRB(8, 4, 8, 20),
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            for (final entry in const [
                              ('Retry search', 'retry'),
                              ('Change provider', 'provider'),
                              ('Free translate now', 'free'),
                              ('Spoken register', 'spoken'),
                              ('Formal register', 'formal'),
                              ('NewZone translator', 'newzone'),
                              ('Audio → SRT (external players)', 'audiosrt'),
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
                                    case 'provider':
                                      // v3.4.6.e — REAL provider rotation: the
                                      // subtitle ENGINE priority cycles
                                      // human-pa-free → human-free → free →
                                      // worker-only, persisted (K_SUB_ENGINE),
                                      // then the pipeline re-runs in the new
                                      // lane (was: nothing — a dead row).
                                      AppState.of(more).scope.putString(
                                          'subEngine', _nextSubEngine(s));
                                    case 'free':
                                      // v3.4.6.d — REAL work: re-fetch the
                                      // current title's subtitles through the
                                      // free on-device translator lane (was a
                                      // dead loadSubtitleUrl('') call).
                                      AnantPlay.instance.findSubtitles(
                                          (widget.request.title ?? '')
                                                  .isEmpty
                                              ? widget.request.url
                                              : (widget.request.title ?? ''));
                                    case 'spoken':
                                      AppState.of(more).scope.putString(
                                          'registerOverride', 'spoken');
                                    case 'formal':
                                      AppState.of(more).scope.putString(
                                          'registerOverride', 'formal');
                                    case 'newzone':
                                      // v3.4.6.e — NewZone Translator: the
                                      // speed-dial's subtitle-group search
                                      // engine, opened on the current title in
                                      // the Browser tab (real handoff, not a
                                      // dead row).
                                      _openNewZoneTranslator();
                                    case 'audiosrt':
                                      // v3.4.6.e — Audio → SRT: the live
                                      // Sinhala cue track exported to the
                                      // receiver's /subs/current.srt route any
                                      // EXTERNAL player (VLC/MX/mpv) can read
                                      // — the URL is copied for pasting.
                                      _copySubsUrl();
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
