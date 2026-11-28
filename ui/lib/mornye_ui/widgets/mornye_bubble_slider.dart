// AnantPlay Sinhala Player v3.4.6.m — MORNYE BUBBLE SLIDER (Law #4).
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
//
// v3.4.6.m — THE ZERO-CLIP BUBBLE (field video: the red pill's top half was
// sliced off by the SettingsGroup card's Clip.antiAlias whenever the slider
// row sat near a card edge). The bubble no longer renders inside the row's
// Stack — it renders through an OverlayPortal + CompositedTransformTarget /
// CompositedTransformFollower pair in the app Overlay, so it floats above
// EVERY ancestor clip while staying pinned to the thumb: the row hosts an
// invisible 64-wide THUMB ANCHOR (the same 10–90 % lane fraction the old
// bubble used), the follower hangs its bottom-center off that anchor's
// top-center, and the bubble only exists while the drag is live.
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
  /// v3.4.6.m — THE ZERO-CLIP PAIR: the layer link pins the overlay
  /// follower to the invisible thumb anchor inside the row; the controller
  /// lifts the bubble into the app Overlay while (and only while) the drag
  /// is live — the exact visibility contract the row Stack had.
  final LayerLink _thumbLink = LayerLink();
  final OverlayPortalController _bubbleController = OverlayPortalController();

  /// The live fraction feeding the overlay bubble's text (updated from the
  /// gesture handlers — never during build, so the overlay-side listener is
  /// always notified at a safe point in the frame).
  final ValueNotifier<double> _liveFraction = ValueNotifier<double>(0);

  bool _dragging = false;
  double _fraction = 0;

  void _handleChanged(double v) {
    final bool dragStarted = !_dragging;
    setState(() {
      _dragging = true;
      _fraction = v;
    });
    _liveFraction.value = v;
    if (dragStarted && !_bubbleController.isShowing) {
      _bubbleController.show();
    }
    widget.onChanged(v);
  }

  void _handleChangeEnd(double v) {
    setState(() => _dragging = false);
    if (_bubbleController.isShowing) {
      _bubbleController.hide();
    }
    widget.onChangeEnd?.call(v);
  }

  @override
  void dispose() {
    // v3.4.6.m — THE TEARDOWN: the OverlayPortal removes its own overlay
    // entry with the row, so a drag that is still live at unmount leaves
    // nothing in the app overlay (no controller hide needed — the portal
    // owns the entry). The notifier dies with the state; its overlay-side
    // listener was already torn down with the portal subtree.
    _liveFraction.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final accent = widget.activeColor ?? scheme.primary;
    return LayoutBuilder(
      builder: (context, constraints) {
        final double w = constraints.maxWidth;
        return OverlayPortal(
          controller: _bubbleController,
          // v3.4.6.m — THE OVERLAY BUBBLE: a CompositedTransformFollower
          // hung off the thumb anchor — targetAnchor topCenter on the
          // anchor, followerAnchor bottomCenter on the pill, an 8 dp lift,
          // and showWhenUnlinked: false (a dead anchor never strands the
          // bubble in the overlay).
          overlayChildBuilder: (context) => CompositedTransformFollower(
            link: _thumbLink,
            targetAnchor: Alignment.topCenter,
            followerAnchor: Alignment.bottomCenter,
            showWhenUnlinked: false,
            offset: const Offset(0, -8),
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
                child: ValueListenableBuilder<double>(
                  valueListenable: _liveFraction,
                  builder: (context, f, _) => Container(
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
                      widget.calloutFor?.call(f) ??
                          '${(f * 100).round()}%',
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
          ),
          child: Stack(
            clipBehavior: Clip.none,
            alignment: Alignment.center,
            children: [
              SizedBox(
                width: double.infinity,
                child: Slider(
                  value: widget.value.clamp(0.0, 1.0),
                  semanticFormatterCallback:
                      widget.semanticLabel == null ? null : (v) => widget.semanticLabel!,
                  onChanged: widget.enabled ? _handleChanged : null,
                  onChangeEnd: widget.enabled ? _handleChangeEnd : null,
                  activeColor: accent,
                  inactiveColor: widget.inactiveColor ??
                      scheme.onSurface.withValues(alpha: 0.12),
                ),
              ),
              // ── the invisible THUMB ANCHOR (the follower's leader) ──────
              // The same lane math the old in-row bubble used — the thumb
              // travels 10–90 % of the track — clamped to the row so the
              // bubble's center can never leave the slider's own box.
              Positioned(
                left: (w * (0.10 + 0.80 * _fraction) - 32)
                    .clamp(0.0, w - 64.0),
                top: 0,
                child: CompositedTransformTarget(
                  link: _thumbLink,
                  child: const SizedBox(width: 64),
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}
