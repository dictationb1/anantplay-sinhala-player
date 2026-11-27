// AnantPlay Sinhala Player v3.4.6.j — THE TOP FLOATING MORNYE LIQUID-GLASS
// NOTIFICATION PILL (video_1 05:10–05:45, Law #2).
//
// Every old bottom SnackBar (action_feedback's _say, the settings sheets,
// the onboarding installer, the help screen, the browser hub) is replaced by
// this: a TOP-anchored floating glass pill that slides down below the status
// bar with spring physics, shows a crisp Mornye vector icon + one line of
// text, adapts to light/dark, auto-dismisses in 2.5 s, and swipes UP to
// dismiss — NEVER covering the bottom tab bar (the exact iOS/Mornye pattern
// the reference app uses for its transient toasts).
import 'package:material_ui/material_ui.dart';
import 'dart:async' show Timer;
import 'dart:ui' show ImageFilter;

import 'package:anantplay_ui/mornye_ui/theme/app_motion.dart';

/// The one live overlay entry (a second call replaces the first — never two
/// pills stacked).
OverlayEntry? _activeGlassNotification;

/// Show the top floating Mornye liquid-glass notification.
///
/// [message] is the single crisp line; [error] swaps the accent to the
/// destructive tone; [duration] defaults to 2.5 s (auto-dismiss); a swipe
/// UP (drag or fling) dismisses early. Light/dark adaptivity rides the
/// ambient [Theme] of the navigator context.
void showMornyeGlassNotification(
  BuildContext context, {
  required String message,
  bool error = false,
  Duration duration = const Duration(milliseconds: 2500),
}) {
  final overlay = Overlay.maybeOf(context, rootOverlay: true);
  if (overlay == null) return;
  _activeGlassNotification?.remove();
  final entry = OverlayEntry(
    builder: (context) => _MornyeGlassNotification(
      message: message,
      error: error,
      duration: duration,
    ),
  );
  _activeGlassNotification = entry;
  overlay.insert(entry);
}

class _MornyeGlassNotification extends StatefulWidget {
  const _MornyeGlassNotification({
    required this.message,
    required this.error,
    required this.duration,
  });

  final String message;
  final bool error;
  final Duration duration;

  @override
  State<_MornyeGlassNotification> createState() =>
      _MornyeGlassNotificationState();
}

class _MornyeGlassNotificationState extends State<_MornyeGlassNotification>
    with SingleTickerProviderStateMixin {
  late final AnimationController _slide =
      AnimationController(vsync: this, duration: const Duration(milliseconds: 320));
  double _dragDy = 0;

  /// The auto-dismiss timer (always cancelled on dispose — never a pending
  /// timer after the tree is gone).
  Timer? _autoDismiss;

  @override
  void initState() {
    super.initState();
    _slide.forward();
    _autoDismiss = Timer(widget.duration, _dismiss);
  }

  void _dismiss() {
    if (!mounted) return;
    _slide.reverse().whenComplete(() {
      if (_activeGlassNotification != null) {
        _activeGlassNotification?.remove();
        _activeGlassNotification = null;
      }
    });
  }

  @override
  void dispose() {
    _autoDismiss?.cancel();
    if (_activeGlassNotification != null) {
      _activeGlassNotification?.remove();
      _activeGlassNotification = null;
    }
    _slide.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final accent = widget.error ? scheme.error : scheme.primary;
    final bool dark = Theme.of(context).brightness == Brightness.dark;
    return Positioned(
      // Top-anchored, below the status bar, never over the bottom tab bar.
      top: 0,
      left: 0,
      right: 0,
      child: SafeArea(
        bottom: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
          child: GestureDetector(
            // swipe UP to dismiss (the pill also auto-dismisses)
            onVerticalDragUpdate: (d) {
              if (d.delta.dy < 0) {
                _dragDy += d.delta.dy;
              }
              if (_dragDy < -24) {
                _dismiss();
              }
            },
            onVerticalDragEnd: (_) {
              if (_dragDy < -12) {
                _dismiss();
              } else {
                _dragDy = 0;
              }
            },
            // v3.4.6.k — THE SMOOTH TOP SPRING DROP (Mornye v5.4.5's snackbar
            // pattern, app_snack_bar.dart:283-317): the pill drops in on the
            // 250 ms fast spring (a 14 px travel, never a linear ease) — the
            // real glass slides over the page instead of fading.
            child: AnimatedBuilder(
              animation: _slide,
              builder: (context, child) {
                final t =
                    CurvedAnimation(parent: _slide, curve: MornyeMotion.fast.curve)
                        .value;
                return Opacity(
                  opacity: t.clamp(0.0, 1.0),
                  child: Transform.translate(
                    offset: Offset(0, 14 * (1 - t)),
                    child: child,
                  ),
                );
              },
              child: Material(
                color: Colors.transparent,
                // v3.4.6.k — THE REAL FROSTED GLASS: one BackdropFilter blur at
                // sigma 14 clipped to the pill (the top notification pill is
                // one of Mornye's three legitimate live-blur surfaces — the
                // floating tab bar, the popovers and this pill), with the
                // luminous hairline rim painted around the same rounded rect.
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(999),
                  child: BackdropFilter(
                    filter: ImageFilter.blur(sigmaX: 14, sigmaY: 14),
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 14, vertical: 11),
                      decoration: BoxDecoration(
                        // the translucent liquid-glass tint (light/dark
                        // adaptive) — the frosted read comes from the blur above.
                        color: scheme.surfaceContainerHigh.withValues(
                            alpha: dark ? 0.62 : 0.55),
                        borderRadius: BorderRadius.circular(999),
                        border: Border.all(
                            color: accent.withValues(alpha: 0.55)),
                        boxShadow: [
                          BoxShadow(
                            color: Colors.black.withValues(
                                alpha: dark ? 0.35 : 0.18),
                            blurRadius: 18,
                            offset: const Offset(0, 6),
                          ),
                        ],
                      ),
                      foregroundDecoration: const BoxDecoration(
                        borderRadius: BorderRadius.all(Radius.circular(999)),
                      ),
                      child: Stack(
                        children: [
                          Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Icon(
                                widget.error
                                    ? Icons.error_outline_rounded
                                    : Icons.check_circle_rounded,
                                size: 18,
                                color: accent,
                              ),
                              const SizedBox(width: 10),
                              Flexible(
                                child: Text(
                                  widget.message,
                                  maxLines: 2,
                                  overflow: TextOverflow.ellipsis,
                                  style: TextStyle(
                                    fontSize: 13,
                                    fontWeight: FontWeight.w600,
                                    color: scheme.onSurface,
                                  ),
                                ),
                              ),
                            ],
                          ),
                          // the luminous Mornye hairline rim (0.6 px vertical
                          // gradient stroke, brighter at the top light catch)
                          Positioned.fill(
                            child: CustomPaint(
                              painter: _HairlineRimPainter(
                                accent: accent,
                                dark: dark,
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// v3.4.6.k — THE LUMINOUS MORNYE HAIRLINE RIM (Mornye's _MornyeGlassRim
/// pattern, mornye_chrome.dart:430-529): a 0.6 px vertical-gradient stroke
/// around the pill — whites 0x88 at the top light catch, 0x26 mid, 0x0d at the
/// bottom, with the accent tint riding the same sweep.
class _HairlineRimPainter extends CustomPainter {
  _HairlineRimPainter({required this.accent, required this.dark});

  final Color accent;
  final bool dark;

  @override
  void paint(Canvas canvas, Size size) {
    final rect = Offset.zero & size;
    final rrect = RRect.fromRectAndRadius(
        rect.deflate(0.3), Radius.circular(rect.height / 2));
    final shader = LinearGradient(
      begin: Alignment.topCenter,
      end: Alignment.bottomCenter,
      colors: [
        Colors.white.withValues(alpha: dark ? 0.55 : 0.34),
        accent.withValues(alpha: 0.28),
        Colors.white.withValues(alpha: dark ? 0.10 : 0.06),
      ],
      stops: const [0.0, 0.5, 1.0],
    ).createShader(rect);
    final paint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 0.6
      ..shader = shader;
    canvas.drawRRect(rrect, paint);
  }

  @override
  bool shouldRepaint(_HairlineRimPainter old) =>
      old.accent != accent || old.dark != dark;
}
