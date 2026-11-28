// AnantPlay Sinhala Player v3.4.6.m — THE APPLE iOS 26 "LIQUID GLASS" TOP
// NOTIFICATION BANNER (Law #2, Part_0.04 03:29).
//
// Every old bottom SnackBar (action_feedback's _say, the settings sheets,
// the onboarding installer, the help screen, the browser hub) rides this ONE
// top-anchored surface: a 24 dp squircle floating glass card that drops in
// below the status bar — real frosted blur (sigma 22), a luminous specular
// rim (light catching the top edge), a tinted frosted icon badge, Apple
// title/caption typography, a depth shadow, swipe-up-to-dismiss and the
// 2.5 s auto-dismiss.
//
// v3.4.6.m — THE STRICT SINGLE QUEUE: exactly ONE banner can ever be alive
// app-wide. The widget owns ONE root-overlay entry for the whole process;
// a new notification does not stack a second entry, it REPLACES the visible
// banner in place (the host swaps the AnimatedSwitcher child, so the old
// banner slides out while the new one slides in — the field video's two
// stacked toasts are structurally impossible now).
import 'package:material_ui/material_ui.dart';
import 'dart:async' show Timer;
import 'dart:ui' show ImageFilter;

import 'package:anantplay_ui/mornye_ui/theme/app_motion.dart';

/// The notification's tone (the icon badge tint + glyph).
enum MornyeGlassNotificationKind { info, success, warning, error }

/// The one live root-overlay entry (a second call replaces the banner
/// in place through the host — never two entries, never two banners).
OverlayEntry? _activeGlassNotification;

/// The live host state behind [_activeGlassNotification] (registers itself
/// on init; unregisters on dispose so a torn-down overlay is re-created).
_MornyeGlassNotificationHostState? _activeGlassNotificationHost;

/// Show the top floating Apple "Liquid Glass" notification banner.
///
/// [message] is the default single crisp line; [error] swaps the accent to
/// the destructive tone; [duration] defaults to 2.5 s (auto-dismiss); a
/// swipe UP (drag or fling) dismisses early. Light/dark adaptivity rides the
/// ambient [Theme] of the navigator context.
///
/// v3.4.6.m — optional [title]/[caption] upgrade the banner to the Apple
/// hierarchy (bold 1-line title + subtle 2-line caption); [kind] picks the
/// info/success/warning/error tint directly (otherwise `error` maps to the
/// error tone and the default reads as success). All additive — the legacy
/// call sites keep working untouched.
void showMornyeGlassNotification(
  BuildContext context, {
  required String message,
  bool error = false,
  Duration duration = const Duration(milliseconds: 2500),
  String? title,
  String? caption,
  MornyeGlassNotificationKind? kind,
}) {
  final data = _MornyeGlassNotificationData(
    message: message,
    error: error,
    duration: duration,
    title: title,
    caption: caption,
    kind: kind,
  );
  // STRICT SINGLE QUEUE (part 1): a live host just swaps its banner in place.
  final host = _activeGlassNotificationHost;
  if (host != null && host.mounted) {
    host.show(data);
    return;
  }
  final overlay = Overlay.maybeOf(context, rootOverlay: true);
  if (overlay == null) return;
  // STRICT SINGLE QUEUE (part 2): first show (or the host died with its
  // overlay). Two calls inside the same frame would race the entry's first
  // build, so a not-yet-built entry is dropped and re-created with the
  // newest payload — one entry ever exists, showing the LATEST message.
  _activeGlassNotification?.remove();
  final entry = OverlayEntry(
    builder: (_) => _MornyeGlassNotificationHost(initial: data),
  );
  _activeGlassNotification = entry;
  overlay.insert(entry);
}

/// The immutable one-shot payload the host swaps between.
class _MornyeGlassNotificationData {
  const _MornyeGlassNotificationData({
    required this.message,
    required this.error,
    required this.duration,
    this.title,
    this.caption,
    this.kind,
  });

  final String message;
  final bool error;
  final Duration duration;
  final String? title;
  final String? caption;
  final MornyeGlassNotificationKind? kind;

  MornyeGlassNotificationKind get effectiveKind =>
      kind ??
      (error
          ? MornyeGlassNotificationKind.error
          : MornyeGlassNotificationKind.success);
}

/// The app-wide singleton host behind the one overlay entry. It owns the
/// current payload, the single auto-dismiss timer and the AnimatedSwitcher
/// that swaps banners in place (a new payload mid-flight replaces the
/// visible banner — never stacks a second card).
class _MornyeGlassNotificationHost extends StatefulWidget {
  const _MornyeGlassNotificationHost({this.initial});

  final _MornyeGlassNotificationData? initial;

  @override
  State<_MornyeGlassNotificationHost> createState() =>
      _MornyeGlassNotificationHostState();
}

class _MornyeGlassNotificationHostState
    extends State<_MornyeGlassNotificationHost> {
  _MornyeGlassNotificationData? _current;
  int _seq = 0;

  /// The single auto-dismiss timer (re-armed per show; always cancelled on
  /// dispose — never a pending timer after the tree is gone).
  Timer? _autoDismiss;

  @override
  void initState() {
    super.initState();
    _activeGlassNotificationHost = this;
    final initial = widget.initial;
    if (initial != null) {
      // v3.4.6.m — the first payload is delivered one frame after the entry
      // builds, so the AnimatedSwitcher sees the banner ARRIVE (the slide +
      // fade drop-in plays; a first-build child would pop in static).
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) show(initial);
      });
    }
  }

  @override
  void dispose() {
    _autoDismiss?.cancel();
    _autoDismiss = null;
    if (identical(_activeGlassNotificationHost, this)) {
      _activeGlassNotificationHost = null;
      // The entry died with its overlay; a future show re-creates it.
      _activeGlassNotification = null;
    }
    super.dispose();
  }

  /// Replace the visible banner IN PLACE (the switcher animates old out /
  /// new in) and re-arm the auto-dismiss with the new duration.
  void show(_MornyeGlassNotificationData data) {
    if (!mounted) return;
    setState(() {
      _seq++;
      _current = data;
    });
    _armAutoDismiss(data.duration);
  }

  void _armAutoDismiss(Duration duration) {
    _autoDismiss?.cancel();
    _autoDismiss = Timer(duration, _dismiss);
  }

  void _dismiss() {
    _autoDismiss?.cancel();
    _autoDismiss = null;
    if (!mounted || _current == null) return;
    // The switcher animates the banner's slide-up + fade exit.
    setState(() => _current = null);
  }

  @override
  Widget build(BuildContext context) {
    final current = _current;
    return Positioned(
      // Top-anchored, below the status bar, never over the bottom tab bar.
      top: 0,
      left: 0,
      right: 0,
      child: SafeArea(
        bottom: false,
        child: Padding(
          // v3.4.6.m — the iOS 26 banner inset: 14 dp horizontal margins.
          padding: const EdgeInsets.fromLTRB(14, 8, 14, 0),
          child: AnimatedSwitcher(
            duration: const Duration(milliseconds: 360),
            // v3.4.6.m — the banner drops in on the app's own fast spring
            // (MornyeMotion.fast.curve, the v3.4.6.k top-drop law: never a
            // linear ease); the exit lane is the crisp ease-in.
            switchInCurve: MornyeMotion.fast.curve,
            switchOutCurve: Curves.easeInCubic,
            // Top-anchored stacking: the leaving banner keeps its place
            // while the replacement slides over it in the same lane.
            layoutBuilder: (currentChild, previousChildren) => Stack(
              alignment: Alignment.topCenter,
              // v3.4.6.m — the null-aware marker keeps the lane clean (the
              // analyzer's use_null_aware_elements rule).
              children: [...previousChildren, ?currentChild],
            ),
            // Entry: slide + fade DOWN from above the status bar; exit: the
            // same lane reversed (slides back up) — the smooth curve, never
            // a linear ease.
            transitionBuilder: (child, animation) => SlideTransition(
              position: Tween<Offset>(
                begin: const Offset(0, -1),
                end: Offset.zero,
              ).animate(animation),
              child: FadeTransition(opacity: animation, child: child),
            ),
            child: current == null
                ? const SizedBox.shrink(key: ValueKey<int>(-1))
                : _MornyeGlassBanner(
                    key: ValueKey<int>(_seq),
                    data: current,
                    onDismiss: _dismiss,
                  ),
          ),
        ),
      ),
    );
  }
}

/// The visible Liquid Glass banner card (drag state only — the entry/exit
/// motion and the auto-dismiss timer live in the host).
class _MornyeGlassBanner extends StatefulWidget {
  const _MornyeGlassBanner({
    super.key,
    required this.data,
    required this.onDismiss,
  });

  final _MornyeGlassNotificationData data;
  final VoidCallback onDismiss;

  @override
  State<_MornyeGlassBanner> createState() => _MornyeGlassBannerState();
}

class _MornyeGlassBannerState extends State<_MornyeGlassBanner> {
  /// The live swipe-up drag travel (negative = lifted off the anchor).
  double _dragDy = 0;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final bool dark = Theme.of(context).brightness == Brightness.dark;
    final kind = widget.data.effectiveKind;
    final tint = switch (kind) {
      MornyeGlassNotificationKind.info => scheme.primary,
      MornyeGlassNotificationKind.success => scheme.tertiary,
      MornyeGlassNotificationKind.warning => scheme.secondary,
      MornyeGlassNotificationKind.error => scheme.error,
    };
    final icon = switch (kind) {
      MornyeGlassNotificationKind.info => Icons.info_rounded,
      MornyeGlassNotificationKind.success => Icons.check_rounded,
      MornyeGlassNotificationKind.warning => Icons.warning_amber_rounded,
      MornyeGlassNotificationKind.error => Icons.error_outline_rounded,
    };
    // Apple typography: the bold title + the optional secondary caption.
    final title = widget.data.title ?? widget.data.message;
    final caption = widget.data.caption ?? '';
    final bool hasCaption = widget.data.caption != null;
    return GestureDetector(
      // swipe UP to dismiss (the banner also auto-dismisses); the card
      // tracks the finger while the drag is live.
      onVerticalDragUpdate: (d) {
        setState(() {
          _dragDy = (_dragDy + d.delta.dy).clamp(-96.0, 0.0);
        });
      },
      onVerticalDragEnd: (d) {
        if (_dragDy < -12 || d.velocity.pixelsPerSecond.dy < -320) {
          widget.onDismiss();
        } else {
          setState(() => _dragDy = 0);
        }
      },
      onVerticalDragCancel: () => setState(() => _dragDy = 0),
      child: Transform.translate(
        offset: Offset(0, _dragDy),
        child: Material(
          color: Colors.transparent,
          // v3.4.6.m — THE REAL FROSTED GLASS: one BackdropFilter blur at
          // sigma 22 clipped to the 24 dp squircle card, over a translucent
          // fill. Light mode rides the near-opaque iOS 26 milk glass
          // (#F8FFFFFF, #111118 text); dark/AMOLED keep the legible dimmer
          // tint (the blur + 0.60 fill over dark content stays crisp).
          child: ClipRRect(
            borderRadius: BorderRadius.circular(24),
            child: BackdropFilter(
              filter: ImageFilter.blur(sigmaX: 22, sigmaY: 22),
              child: Container(
                padding: const EdgeInsets.symmetric(
                    horizontal: 14, vertical: 12),
                decoration: BoxDecoration(
                  color: dark
                      ? scheme.surfaceContainerHigh.withValues(alpha: 0.60)
                      : const Color(0xF8FFFFFF),
                  borderRadius: BorderRadius.circular(24),
                  // the subtle depth shadow below the floating card
                  boxShadow: [
                    BoxShadow(
                      color: Colors.black
                          .withValues(alpha: dark ? 0.45 : 0.20),
                      blurRadius: 24,
                      offset: const Offset(0, 10),
                      spreadRadius: -6,
                    ),
                  ],
                ),
                child: Stack(
                  children: [
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.center,
                      children: [
                        // the circular frosted icon badge, tinted by kind
                        SizedBox(
                          width: 40,
                          height: 40,
                          child: ClipOval(
                            child: BackdropFilter(
                              filter: ImageFilter.blur(sigmaX: 14, sigmaY: 14),
                              child: Container(
                                decoration: BoxDecoration(
                                  shape: BoxShape.circle,
                                  color: tint.withValues(
                                      alpha: dark ? 0.30 : 0.22),
                                  border: Border.all(
                                    color: tint.withValues(alpha: 0.45),
                                    width: 0.8,
                                  ),
                                ),
                                child: Icon(icon, size: 21, color: tint),
                              ),
                            ),
                          ),
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Text(
                                title,
                                maxLines: hasCaption ? 1 : 2,
                                overflow: TextOverflow.ellipsis,
                                style: TextStyle(
                                  fontSize: 14.5,
                                  fontWeight: FontWeight.w700,
                                  height: 1.15,
                                  color: dark
                                      ? scheme.onSurface
                                      : const Color(0xFF111118),
                                ),
                              ),
                              if (hasCaption) ...[
                                const SizedBox(height: 2.5),
                                Text(
                                  caption,
                                  maxLines: 2,
                                  overflow: TextOverflow.ellipsis,
                                  style: TextStyle(
                                    fontSize: 12,
                                    fontWeight: FontWeight.w500,
                                    height: 1.25,
                                    color: dark
                                        ? scheme.onSurfaceVariant
                                        : const Color(0xFF6C6C78),
                                  ),
                                ),
                              ],
                            ],
                          ),
                        ),
                      ],
                    ),
                    // the luminous specular rim (light catching the glass)
                    Positioned.fill(
                      child: CustomPaint(
                        painter: _SpecularRimPainter(dark: dark),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// v3.4.6.m — THE LUMINOUS SPECULAR RIM (v3.4.6.k's _HairlineRimPainter
/// grown into the full iOS 26 rim — the same hairline stroke, now the
/// wider luminous light-catch sweep): a 1.1 px vertical-gradient stroke
/// around the squircle card — whites 0.65 at the top light catch (0.42 in
/// dark, where the rim must stay a whisper), 0.22 mid, 0.06 at the bottom —
/// the "light catching glass" edge of an iOS 26 Liquid Glass surface.
class _SpecularRimPainter extends CustomPainter {
  _SpecularRimPainter({required this.dark});

  final bool dark;

  @override
  void paint(Canvas canvas, Size size) {
    final rect = Offset.zero & size;
    final rrect = RRect.fromRectAndRadius(
      rect.deflate(0.6),
      const Radius.circular(23.4),
    );
    final shader = LinearGradient(
      begin: Alignment.topCenter,
      end: Alignment.bottomCenter,
      colors: [
        Colors.white.withValues(alpha: dark ? 0.42 : 0.65),
        Colors.white.withValues(alpha: dark ? 0.14 : 0.22),
        Colors.white.withValues(alpha: dark ? 0.04 : 0.06),
      ],
      stops: const [0.0, 0.5, 1.0],
    ).createShader(rect);
    final paint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.1
      ..shader = shader;
    canvas.drawRRect(rrect, paint);
  }

  @override
  bool shouldRepaint(_SpecularRimPainter old) => old.dark != dark;
}
