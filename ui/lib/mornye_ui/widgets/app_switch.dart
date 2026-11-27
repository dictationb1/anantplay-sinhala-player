// Vendored from Mornye v5.4.0 (mornyeapp/Mornye @ c4a1c7b812eb135d6e1a295b6b25e11abbd72a7e)
// upstream file: lib/widgets/app_switch.dart — Copyright 2026 zarzet, SPDX-License-Identifier: LGPL-3.0-only.
// Vendored for AnantPlay Sinhala Player v3.4.6 (lk.techworld.anantplay.sicast) under
// LGPL-3.0-only. See ui/lib/mornye_ui/LICENSE (+ COPYING, NOTICE) for the full terms.
// Modification: only the package import below was rewritten from package:mornye/
// to package:anantplay_ui/mornye_ui/; no other lines were changed in this file.
import 'package:material_ui/material_ui.dart';
import 'package:flutter/physics.dart';
import 'package:material_3_expressive/material_3_expressive.dart';
import 'package:anantplay_ui/mornye_ui/theme/app_motion.dart';
import 'package:anantplay_ui/mornye_ui/theme/material_expressive.dart';
import 'package:anantplay_ui/mornye_ui/theme/mornye_theme.dart';
import 'package:anantplay_ui/mornye_ui/widgets/app_icon.dart';

/// One switch style for settings, extension controls and editing sheets.
/// Mornye keeps its wide oval thumb even when glass or motion is disabled.
class AppSwitch extends StatelessWidget {
  const AppSwitch({
    super.key,
    required this.value,
    required this.onChanged,
    this.adaptive = false,
    this.semanticLabel,
  });

  final bool value;
  final ValueChanged<bool>? onChanged;
  final bool adaptive;
  final String? semanticLabel;

  @override
  Widget build(BuildContext context) {
    if (materialExpressiveEnabled(context)) {
      return MaterialExpressiveScope(
        child: M3ESwitch(
          value: value,
          onChanged: onChanged,
          selectedIcon: const AppIcon(Icons.check),
          semanticLabel: semanticLabel,
        ),
      );
    }
    if (!context.isMornye) {
      return adaptive
          ? Switch.adaptive(value: value, onChanged: onChanged)
          : Switch(value: value, onChanged: onChanged);
    }

    return _MornyeSwitch(
      value: value,
      onChanged: onChanged,
      semanticLabel: semanticLabel,
    );
  }
}

class _MornyeSwitch extends StatefulWidget {
  const _MornyeSwitch({
    required this.value,
    required this.onChanged,
    required this.semanticLabel,
  });

  final bool value;
  final ValueChanged<bool>? onChanged;
  final String? semanticLabel;

  @override
  State<_MornyeSwitch> createState() => _MornyeSwitchState();
}

class _MornyeSwitchState extends State<_MornyeSwitch>
    with TickerProviderStateMixin {
  /// How far the thumb travels, from off to on.
  static const double _travel = 22;

  /// v3.4.6.k — SYNCED WITH MORNYE v5.4.5 (/tmp/mornye-latest lib/widgets/
  /// app_switch.dart:76-81): the held lens at iOS 27's size — the thumb
  /// GROWS from 37×24 to 58×38.33 around its resting center — the authentic
  /// visible horizontal thumb STRETCH on press & toggle (an OverflowBox
  /// growth, never a bare scaleX squeeze).
  static const double _heldWidthGrowth = 21;
  static const double _heldHeightGrowth = 43 / 3;

  double? _dragPosition;
  bool _dragCancelled = false;

  late final AnimationController _press = AnimationController.unbounded(
    vsync: this,
  );
  late final Listenable _thumbChanges = Listenable.merge([_thumb, _press]);

  @override
  void dispose() {
    _press.dispose();
    _thumb.dispose();
    super.dispose();
  }

  /// v3.4.6.k — MORNYE v5.4.5'S EXACT HOLD/RELEASE SPRINGS: the lens lifts
  /// within ~120 ms (effectsFast, 150 ms / bounce 0) and settles more slowly
  /// (fast, 250 ms / bounce 0) — the bouncy spring settle the v5.4.0 build
  /// (snappy 350/0.15 on both) never had.
  void _hold(bool held) {
    if (!mounted) return;   // a released pointer after teardown is a no-op
    if (!held && _press.value == 0 && !_press.isAnimating) return;
    // A drag-cancel can fire during tree teardown (the gesture recognizer
    // flushes its cancel callback while this element is already inactive) —
    // the ancestor lookup is unsafe then, so the reduced-motion read is
    // best-effort and never throws out of the gesture handler.
    bool reduceMotion = false;
    try {
      reduceMotion = MediaQuery.disableAnimationsOf(context);
    } catch (_) {}
    if (reduceMotion) {
      _press.value = held ? 1 : 0;
      return;
    }
    _press.animateWith(
      SpringSimulation(
        held ? MornyeMotion.effectsFastSpring : MornyeMotion.fastSpring,
        _press.value,
        held ? 1 : 0,
        _press.velocity,
        snapToEnd: true,
      ),
    );
  }

  /// The thumb, from off (0) to on (1). A drag places it under the finger;
  /// otherwise the snappy spring carries it to rest, starting from the
  /// finger's velocity on release and from its own velocity when a new
  /// value interrupts it.
  late final AnimationController _thumb = AnimationController.unbounded(
    vsync: this,
    value: widget.value ? 1 : 0,
  );

  /// The side the thumb is moving to, or null after a drag placed it.
  late double? _thumbTarget = _thumb.value;

  /// The finger's velocity, in thumb travels a second, for the next move.
  double? _releaseVelocity;

  /// Sends the thumb to the current value's side unless a drag holds it.
  /// Runs during build, after the parent has applied a new value, so a
  /// released thumb never heads for the old side first.
  void _followValue() {
    if (_dragPosition != null) return;
    final target = widget.value ? 1.0 : 0.0;
    final velocity = _releaseVelocity;
    _releaseVelocity = null;
    if (MediaQuery.disableAnimationsOf(context)) {
      _thumbTarget = target;
      if (_thumb.value != target) _thumb.value = target;
      return;
    }
    if (target == _thumbTarget && velocity == null) return;
    _thumbTarget = target;
    _thumb.animateWith(
      SpringSimulation(
        MornyeMotion.snappySpring,
        _thumb.value,
        target,
        velocity ?? _thumb.velocity,
        snapToEnd: true,
      ),
    );
  }

  void _place(double position) {
    _dragPosition = position;
    _thumbTarget = null;
    _thumb.value = position;
  }

  /// v3.4.6.k — THE v5.4.5 HELD-LENS THUMB: grown to 58×38.33 via OverflowBox
  /// around the resting center, wearing the translucent glass highlight (the
  /// refractive lens degrades to this sheen outside the liquid_glass_widgets
  /// package — same geometry, same fade, same feel).
  Widget _heldThumb(double held, Color trackOn) {
    final height = 24 + _heldHeightGrowth * held;
    final radius = BorderRadius.circular(height / 2);
    return SizedBox(
      width: 37,
      height: 24,
      child: OverflowBox(
        minWidth: 37 + _heldWidthGrowth * held,
        maxWidth: 37 + _heldWidthGrowth * held,
        minHeight: height,
        maxHeight: height,
        alignment: Alignment.center,
        child: Container(
          decoration: BoxDecoration(
            borderRadius: radius,
            // the crisp translucent glass highlight: the white fill fades
            // as the lens lifts (v5.4.5's exact (1 − held×1.4) fade)
            gradient: LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: [
                Colors.white.withValues(
                    alpha: (1 - held * 1.4).clamp(0.0, 1.0)),
                Colors.white.withValues(
                    alpha: (0.9 - held * 1.1).clamp(0.0, 1.0)),
              ],
            ),
            border: Border.all(
              color: Colors.white.withValues(alpha: 0.55 * held),
              width: 1.2,
            ),
            boxShadow: [
              BoxShadow(
                color: trackOn.withValues(alpha: 0.35 * held),
                blurRadius: 12,
                spreadRadius: 0.5,
              ),
            ],
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final value = widget.value;
    final onChanged = widget.onChanged;
    final scheme = Theme.of(context).colorScheme;
    final enabled = onChanged != null;
    final fade = MediaQuery.disableAnimationsOf(context)
        ? Duration.zero
        : MornyeMotion.effectsFast.duration;
    final inactive = scheme.brightness == Brightness.dark
        ? const Color(0xff39393d)
        : const Color(0xff8e8e93);
    _followValue();

    final position = _dragPosition ?? (value ? 1.0 : 0.0);
    // v3.4.6.i — THE LIVE-ACCENT TRACK (video_4 03:03): the ON track IS the
    // user's chosen Theme.of(context).colorScheme.primary now — the preset /
    // custom # hex / Material You wallpaper accent, at full strength —
    // instead of the hardcoded bright-green-with-a-45%-accent-lerp that made
    // every accent look washed-out green. (The v3.4.6.g 0.45 lerp left the
    // switch clearly green for 6 of 7 presets + custom hex.) The liquid-glass
    // stretch spring is untouched.
    final trackOn = scheme.primary;
    final control = AnimatedContainer(
      duration: fade,
      curve: MornyeMotion.effectsFast.curve,
      width: 63,
      height: 28,
      padding: const EdgeInsets.all(2),
      decoration: BoxDecoration(
        color: position >= 0.5 ? trackOn : inactive,
        borderRadius: BorderRadius.circular(14),
      ),
      child: AnimatedBuilder(
        animation: _thumbChanges,
        // The thumb stays inside the track when the spring runs past a side;
        // while held/dragged it STRETCHES the authentic v5.4.5 way — the
        // OverflowBox lens GROWING to 58×38.33 around the 37×24 resting
        // thumb (never a bare scaleX squeeze), with the crisp translucent
        // glass highlight fading in as the white fill lifts.
        builder: (context, child) {
          final held = _press.value.clamp(0.0, 1.0);
          return Align(
            alignment: AlignmentDirectional(
              -1 + _thumb.value.clamp(0.0, 1.0) * 2,
              0,
            ),
            child: held > 0 ? _heldThumb(held, trackOn) : child,
          );
        },
        child: Container(
          width: 37,
          height: 24,
          decoration: BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.circular(12),
          ),
        ),
      ),
    );

    return Listener(
      onPointerDown: (_) {
        _dragCancelled = false;
        _hold(true);   // v3.4.6.k — the lens turns on the moment the finger lands
      },
      onPointerUp: (_) => _hold(false),
      onPointerCancel: (_) {
        _dragCancelled = true;
        _hold(false);
      },
      child: Semantics(
        label: widget.semanticLabel,
        toggled: value,
        enabled: enabled,
        child: Opacity(
          opacity: enabled ? 1 : 0.5,
          child: Material(
            color: Colors.transparent,
            child: InkWell(
              onTap: enabled ? () => onChanged(!value) : null,
              borderRadius: BorderRadius.circular(22),
              // InkWell supplies keyboard and accessible activation. The
              // thumb uses only paint/layout, even on a page of switches.
              child: ExcludeSemantics(
                child: GestureDetector(
                  // v3.4.6.k — v5.4.5's drag-lens contract: the thumb turns
                  // to glass as the finger lands, and back when a scroll or a
                  // tap takes the pointer.
                  onHorizontalDragDown:
                      enabled ? (_) => _hold(true) : null,
                  // A drag picks the thumb up where it is, also mid-move.
                  onHorizontalDragStart: enabled
                      ? (_) =>
                            setState(() => _place(_thumb.value.clamp(0.0, 1.0)))
                      : null,
                  onHorizontalDragUpdate: enabled
                      ? (event) {
                          final direction =
                              Directionality.of(context) == TextDirection.rtl
                              ? -1.0
                              : 1.0;
                          setState(
                            () => _place(
                              ((_dragPosition ?? (value ? 1 : 0)) +
                                      event.delta.dx * direction / _travel)
                                  .clamp(0.0, 1.0),
                            ),
                          );
                        }
                      : null,
                  onHorizontalDragEnd: enabled
                      ? (details) {
                          final next =
                              (_dragPosition ?? (value ? 1 : 0)) >= 0.5;
                          final direction =
                              Directionality.of(context) == TextDirection.rtl
                              ? -1.0
                              : 1.0;
                          setState(() {
                            _dragPosition = null;
                            // The thumb leaves the finger at its speed.
                            _releaseVelocity =
                                (details.primaryVelocity ?? 0) *
                                direction /
                                _travel;
                          });
                          _hold(false);
                          if (!_dragCancelled && next != value) onChanged(next);
                        }
                      : null,
                  onHorizontalDragCancel: enabled
                      ? () {
                          setState(() => _dragPosition = null);
                          _hold(false);
                        }
                      : null,
                  child: SizedBox(
                    width: 71,
                    height: 44,
                    child: Center(child: control),
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

class AppSwitchListTile extends StatelessWidget {
  const AppSwitchListTile({
    super.key,
    required this.value,
    required this.onChanged,
    required this.title,
    this.subtitle,
    this.contentPadding,
    this.adaptive = false,
  });

  final bool value;
  final ValueChanged<bool>? onChanged;
  final Widget title;
  final Widget? subtitle;
  final EdgeInsetsGeometry? contentPadding;
  final bool adaptive;

  @override
  Widget build(BuildContext context) {
    if (!context.isMornye && !materialExpressiveEnabled(context)) {
      return adaptive
          ? SwitchListTile.adaptive(
              value: value,
              onChanged: onChanged,
              title: title,
              subtitle: subtitle,
              contentPadding: contentPadding,
            )
          : SwitchListTile(
              value: value,
              onChanged: onChanged,
              title: title,
              subtitle: subtitle,
              contentPadding: contentPadding,
            );
    }
    return MergeSemantics(
      child: ListTile(
        title: title,
        subtitle: subtitle,
        contentPadding: contentPadding,
        enabled: onChanged != null,
        onTap: onChanged == null ? null : () => onChanged!(!value),
        trailing: AppSwitch(value: value, onChanged: onChanged),
      ),
    );
  }
}
