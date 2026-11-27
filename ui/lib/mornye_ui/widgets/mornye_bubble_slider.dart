// AnantPlay Sinhala Player v3.4.6.k — MORNYE BUBBLE SLIDER (Law #4).
//
// The reusable slider with the FLOATING VALUE-CALLOUT BUBBLE, matching
// Mornye v5.4.5's in-app slider feel (mornyeapp/Mornye: the library
// fast-scroller bubble + the settings slider's raised value indicator):
//   · dragging the thumb smoothly pops up a crisp accent-tinted floating
//     callout pill (`14sp`, `55%`, `65%`, `100%` …) directly ABOVE the
//     thumb, scale-popping in from 0.6 → 1.0 on the fast spring curve;
//   · the bubble disappears the moment the drag ends — while dragging, the
//     ONLY work per frame is the local draft state (zero MethodChannel
//     round-trips: callers write to the platform channel strictly in
//     onChangeEnd);
//   · the thumb travels the real Material slider lane (its gestures,
//     semantics and keyboard control stay intact), with the bubble pinned
//     at the same fraction.
import 'package:material_ui/material_ui.dart' hide Icons;

/// A slider whose value bubble pops up above the thumb while dragging.
class MornyeBubbleSlider extends StatefulWidget {
  const MornyeBubbleSlider({
    super.key,
    required this.value,
    required this.onChanged,
    this.onChangeEnd,
    this.enabled = true,
    this.activeColor,
    this.inactiveColor,
    this.calloutFor,
    this.semanticLabel,
  });

  /// The current value, 0.0 … 1.0 (the caller maps its own range).
  final double value;

  /// Fires on every drag frame — LOCAL DRAFT ONLY (never a channel write).
  final ValueChanged<double> onChanged;

  /// Fires ONCE when the drag ends — the single commit point.
  final ValueChanged<double>? onChangeEnd;

  /// False renders the inert-but-visible control.
  final bool enabled;

  /// The bubble's accent (defaults to the theme primary).
  final Color? activeColor;

  /// The track's inactive color.
  final Color? inactiveColor;

  /// The bubble text for a drag position (`55%`, `14sp`, `100%` …).
  final String Function(double v)? calloutFor;

  /// The slider's semantics label.
  final String? semanticLabel;

  @override
  State<MornyeBubbleSlider> createState() => _MornyeBubbleSliderState();
}

class _MornyeBubbleSliderState extends State<MornyeBubbleSlider> {
  bool _dragging = false;
  double _fraction = 0;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final accent = widget.activeColor ?? scheme.primary;
    return LayoutBuilder(
      builder: (context, constraints) {
        final double w = constraints.maxWidth;
        return Stack(
          clipBehavior: Clip.none,
          alignment: Alignment.center,
          children: [
            SizedBox(
              width: double.infinity,
              child: Slider(
                value: widget.value.clamp(0.0, 1.0),
                semanticFormatterCallback:
                    widget.semanticLabel == null ? null : (v) => widget.semanticLabel!,
                onChanged: widget.enabled
                    ? (v) {
                        setState(() {
                          _dragging = true;
                          _fraction = v;
                        });
                        widget.onChanged(v);
                      }
                    : null,
                onChangeEnd: widget.enabled
                    ? (v) {
                        setState(() => _dragging = false);
                        widget.onChangeEnd?.call(v);
                      }
                    : null,
                activeColor: accent,
                inactiveColor: widget.inactiveColor ??
                    scheme.onSurface.withValues(alpha: 0.12),
              ),
            ),
            // ── the floating callout bubble, pinned above the thumb ─────────
            if (_dragging)
              Positioned(
                left:
                    (w * (0.10 + 0.80 * _fraction) - 32).clamp(0.0, w - 64.0),
                bottom: 44,
                child: IgnorePointer(
                  child: TweenAnimationBuilder<double>(
                    tween: Tween<double>(begin: 0.6, end: 1.0),
                    duration: const Duration(milliseconds: 250),
                    curve: Curves.easeOutCubic,
                    builder: (context, t, child) => Transform.scale(
                      scale: t,
                      alignment: Alignment.bottomCenter,
                      child: child,
                    ),
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 13, vertical: 6),
                      decoration: BoxDecoration(
                        color: accent.withValues(alpha: 0.94),
                        borderRadius: BorderRadius.circular(14),
                        border: Border.all(
                            color: const Color(0x33FFFFFF), width: 0.5),
                        boxShadow: [
                          BoxShadow(
                            color: accent.withValues(alpha: 0.45),
                            blurRadius: 18,
                            spreadRadius: 1,
                          ),
                        ],
                      ),
                      child: Text(
                        widget.calloutFor?.call(_fraction) ??
                            '${(_fraction * 100).round()}%',
                        style: const TextStyle(
                          color: Color(0xFFFFFFFF),
                          fontSize: 12.5,
                          fontWeight: FontWeight.w700,
                          height: 1.0,
                        ),
                      ),
                    ),
                  ),
                ),
              ),
          ],
        );
      },
    );
  }
}
