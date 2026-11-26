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

  double? _dragPosition;
  bool _dragCancelled = false;

  /// v3.4.6.g — THE TACTILE LIQUID-GLASS PHYSICS (the real-phone complaint:
  /// the green switches felt static). The press controller drives the thumb
  /// STRETCH while held or dragged (MornyeMotion.snappy — the 350 ms /
  /// bounce-0.15 spring, the same one the travel rides), and while sliding
  /// the thumb wears a luminous translucent sheen + rim highlight.
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

  /// The hold in / release out — both on the snappy spring (bounce 0.15).
  void _hold(bool held) {
    if (!held && _press.value == 0 && !_press.isAnimating) return;
    if (MediaQuery.disableAnimationsOf(context)) {
      _press.value = held ? 1 : 0;
      return;
    }
    _press.animateWith(
      SpringSimulation(
        MornyeMotion.snappySpring,
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
        // while held/dragged it STRETCHES (scaleX up, scaleY in — the tactile
        // liquid-glass deformation on the snappy spring, bounce 0.15).
        builder: (context, child) => Align(
          alignment: AlignmentDirectional(
            -1 + _thumb.value.clamp(0.0, 1.0) * 2,
            0,
          ),
          child: Transform.scale(
            scaleX: 1 + 0.24 * _press.value.clamp(0.0, 1.0),
            scaleY: 1 - 0.06 * _press.value.clamp(0.0, 1.0),
            child: child,
          ),
        ),
        child: AnimatedBuilder(
          animation: _press,
          // v3.4.6.g — THE LUMINOUS GLASS THUMB: while pressed or mid-travel
          // the thumb wears a translucent sheen + a live rim highlight (and a
          // soft accent glow), then settles back to solid white at rest.
          builder: (context, _) {
            final sliding = _press.value > 0.02 ||
                (_thumb.value > 0.02 && _thumb.value < 0.98);
            final w = sliding ? 0.6 : 0.0;
            return Container(
              width: 37,
              height: 24,
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(12),
                gradient: sliding
                    ? const LinearGradient(
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                        colors: [Colors.white, Color(0xE6FFFFFF)],
                      )
                    : null,
                color: sliding ? null : Colors.white,
                border: sliding
                    ? Border.all(
                        color: Colors.white.withValues(alpha: w),
                        width: 1.2,
                      )
                    : null,
                boxShadow: sliding
                    ? [
                        BoxShadow(
                          color: trackOn.withValues(alpha: 0.35),
                          blurRadius: 10,
                          spreadRadius: 0.5,
                        ),
                      ]
                    : null,
              ),
            );
          },
        ),
      ),
    );

    return Listener(
      onPointerDown: (_) {
        _dragCancelled = false;
        _hold(true);
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
                          if (!_dragCancelled && next != value) onChanged(next);
                        }
                      : null,
                  onHorizontalDragCancel: enabled
                      ? () => setState(() => _dragPosition = null)
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
