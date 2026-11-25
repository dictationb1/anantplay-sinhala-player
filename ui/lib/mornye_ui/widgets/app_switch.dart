// Vendored from Mornye v5.4.0 (mornyeapp/Mornye @ c4a1c7b812eb135d6e1a295b6b25e11abbd72a7e)
// upstream file: lib/widgets/app_switch.dart — Copyright 2026 zarzet, SPDX-License-Identifier: LGPL-3.0-only.
// Vendored for AnantPlay Sinhala Player v3.4.6 (lk.techworld.anantplay.sicast) under
// LGPL-3.0-only. See ui/lib/mornye_ui/LICENSE (+ COPYING, NOTICE) for the full terms.
// Modification: only the package import below was rewritten from package:mornye/
// to package:anantplay_ui/mornye_ui/; no other lines were changed in this file.
import 'package:material_ui/material_ui.dart';
import 'package:cupertino_ui/cupertino_ui.dart' show CupertinoColors;
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
    with SingleTickerProviderStateMixin {
  /// How far the thumb travels, from off to on.
  static const double _travel = 22;

  double? _dragPosition;
  bool _dragCancelled = false;

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

  @override
  void dispose() {
    _thumb.dispose();
    super.dispose();
  }

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
    final active = CupertinoColors.systemGreen.resolveFrom(context);
    final enabled = onChanged != null;
    final fade = MediaQuery.disableAnimationsOf(context)
        ? Duration.zero
        : MornyeMotion.effectsFast.duration;
    final inactive = scheme.brightness == Brightness.dark
        ? const Color(0xff39393d)
        : const Color(0xff8e8e93);
    _followValue();

    final position = _dragPosition ?? (value ? 1.0 : 0.0);
    final control = AnimatedContainer(
      duration: fade,
      curve: MornyeMotion.effectsFast.curve,
      width: 63,
      height: 28,
      padding: const EdgeInsets.all(2),
      decoration: BoxDecoration(
        // v3.4.6.g — the glass track takes the live accent tint while ON, with
        // a soft inner rim so the glass reads even on a flat surface.
        gradient: position >= 0.5
            ? LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [active, active.withValues(alpha: 0.82)],
              )
            : null,
        color: position >= 0.5 ? null : inactive,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(
            color: position >= 0.5
                ? active.withValues(alpha: 0.55)
                : Colors.transparent,
            width: 0.6),
      ),
      child: AnimatedBuilder(
        animation: _thumb,
        // The thumb stays inside the track when the spring runs past a side.
        //
        // v3.4.6.g — THE AUTHENTIC MORNYE LIQUID-GLASS THUMB: the same snappy
        // spring now drives a tactile squash-and-stretch (the thumb widens
        // while it travels and settles back to 37 dp), a luminous translucent
        // glass fill with a specular top rim, and a soft accent glow, so the
        // control feels like sliding glass under the finger — Mornye's own
        // switch physics, not a reskinned Material pill.
        builder: (context, child) {
          final double v = _thumb.value.clamp(0.0, 1.0);
          // velocity-driven stretch: fast travel = wider thumb, at rest = 1.0
          final double speed = (_thumb.velocity.abs() / 6.0).clamp(0.0, 1.0);
          final double stretch = 1.0 + 0.14 * speed;
          final double w = 37.0 * stretch;
          return Align(
            alignment: AlignmentDirectional(-1 + v * 2, 0),
            child: Container(
              width: w,
              height: 24,
              decoration: BoxDecoration(
                // luminous glass: near-white core, translucent body
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [
                    Colors.white,
                    Colors.white.withValues(alpha: 0.86),
                    Colors.white.withValues(alpha: 0.94),
                  ],
                ),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(
                    color: Colors.white.withValues(alpha: 0.55), width: 0.6),
                boxShadow: [
                  // the specular rim + the accent-tinted glow
                  BoxShadow(
                    color: Colors.black.withValues(alpha: 0.22),
                    blurRadius: 4,
                    offset: const Offset(0, 1.5),
                  ),
                  BoxShadow(
                    color: active.withValues(alpha: 0.30 * v),
                    blurRadius: 10,
                    spreadRadius: 0.5,
                  ),
                ],
              ),
              // the specular highlight arc that reads as glass
              child: Align(
                alignment: const Alignment(0, -0.55),
                child: Container(
                  height: 6,
                  margin: const EdgeInsets.symmetric(horizontal: 7),
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.75),
                    borderRadius: BorderRadius.circular(3),
                  ),
                ),
              ),
            ),
          );
        },
      ),
    );

    return Listener(
      onPointerDown: (_) => _dragCancelled = false,
      onPointerCancel: (_) => _dragCancelled = true,
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
