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
            child: SlideTransition(
              position: Tween<Offset>(
                      begin: const Offset(0, -1.2), end: Offset.zero)
                  .animate(CurvedAnimation(
                      parent: _slide, curve: Curves.easeOutCubic)),
              child: ScaleTransition(
                scale: Tween<double>(begin: 0.96, end: 1).animate(
                    CurvedAnimation(parent: _slide, curve: Curves.easeOutBack)),
                child: Material(
                  color: Colors.transparent,
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                        horizontal: 14, vertical: 11),
                    decoration: BoxDecoration(
                      // the translucent liquid-glass tint (light/dark
                      // adaptive) + the accent hairline.
                      color: scheme.surfaceContainerHigh.withValues(alpha: 0.9),
                      borderRadius: BorderRadius.circular(999),
                      border: Border.all(color: accent.withValues(alpha: 0.55)),
                      boxShadow: [
                        BoxShadow(
                          color: Colors.black.withValues(
                              alpha: Theme.of(context).brightness ==
                                      Brightness.dark
                                  ? 0.35
                                  : 0.18),
                          blurRadius: 18,
                          offset: const Offset(0, 6),
                        ),
                      ],
                    ),
                    child: Row(
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
