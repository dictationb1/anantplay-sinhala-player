// Vendored from Mornye v5.4.0 (mornyeapp/Mornye @ c4a1c7b812eb135d6e1a295b6b25e11abbd72a7e)
// upstream file: lib/widgets/animation_utils.dart — Copyright 2026 zarzet, SPDX-License-Identifier: LGPL-3.0-only.
// Vendored for AnantPlay Sinhala Player v3.4.6.a (lk.techworld.anantplay.sicast) under
// LGPL-3.0-only. See ui/lib/mornye_ui/LICENSE (+ COPYING, NOTICE) for the full terms.
// Modification: only the package import below was rewritten from package:mornye/
// to package:anantplay_ui/mornye_ui/; no other lines were changed in this file.
// Modification: extracted AnimatedBadge (+ its State) and StaggeredListItem
// from the upstream 1690-line skeleton library (v3.4.6.a full-parity pull);
// the remaining classes (ShimmerLoading, the desktop TrackTable skeletons, …)
// depend on Mornye's music library UI and are not part of the vendored engine.
// StaggeredListItem needs the upstream loadedFadeFirstFrame constant
// (lib/widgets/loaded_sliver_fade_in.dart) — re-declared below verbatim.
// Upstream imports for both widgets are re-declared below.

import 'dart:math' as math;
import 'package:flutter/physics.dart';
import 'package:flutter/widgets.dart';
import 'package:material_3_expressive/material_3_expressive.dart';
import 'package:material_ui/material_ui.dart';
import 'package:anantplay_ui/mornye_ui/theme/app_motion.dart';
import 'package:anantplay_ui/mornye_ui/theme/material_expressive.dart';
import 'package:anantplay_ui/mornye_ui/theme/mornye_theme.dart';

/// How far into its fade loaded content starts, so the frame that drops the
/// skeleton already shows the content: one frame at 60 Hz.
/// (upstream: lib/widgets/loaded_sliver_fade_in.dart — verbatim)
const loadedFadeFirstFrame = Duration(milliseconds: 16);

/// Reveals list rows one after another on entry: each item springs up after
/// its own delay and may overshoot slightly, while its opacity follows the
/// effects spring. Skipped (child returned as-is) when animate is false, the
/// page already scrolled, the index is past the animated cap, or the user
/// asked the OS for reduced motion.
/// (upstream: lib/widgets/animation_utils.dart lines 24-112 — verbatim)
class StaggeredListItem extends StatelessWidget {
  static const int _maxAnimatedItems = 10;

  final int index;
  final Widget child;
  final Duration duration;
  final Duration staggerDelay;
  final bool animate;

  const StaggeredListItem({
    super.key,
    required this.index,
    required this.child,
    this.duration = const Duration(milliseconds: 250),
    this.staggerDelay = const Duration(milliseconds: 40),
    this.animate = true,
  });

  static bool _hasScrolled(ScrollableState? scrollable) {
    final position = scrollable?.position;
    return position != null &&
        position.hasContentDimensions &&
        position.pixels > position.minScrollExtent;
  }

  @override
  Widget build(BuildContext context) {
    // A shelf scrolls sideways inside a page that builds its shelves lazily
    // while it scrolls down, so a moved page skips the reveal as well.
    final scrolled =
        _hasScrolled(Scrollable.maybeOf(context)) ||
        _hasScrolled(Scrollable.maybeOf(context, axis: Axis.vertical));
    if (!animate ||
        scrolled ||
        index >= _maxAnimatedItems ||
        MediaQuery.disableAnimationsOf(context)) {
      return child;
    }
    final cappedIndex = index.clamp(0, _maxAnimatedItems - 1);
    final delay = staggerDelay * cappedIndex;
    // Expressive: each item springs up after its own delay and may
    // overshoot slightly, while its opacity follows the effects spring.
    final rise = AppMotion.spatial(
      context,
      AppMotionSpec(duration, Curves.easeOutCubic),
    );
    final fade = AppMotion.effectsFast(
      context,
      AppMotionSpec(duration, Curves.easeOutCubic),
    );
    final distance = materialExpressiveEnabled(context) ? 16.0 : 12.0;
    final span = rise.duration > fade.duration ? rise.duration : fade.duration;
    final total = (span + delay).inMicroseconds.toDouble();
    // The skeleton leaves in the frame these rows arrive, so the reveal
    // starts one frame in and the first row already shows.
    final start = math.min(
      loadedFadeFirstFrame.inMicroseconds.toDouble(),
      total,
    );
    double progress(double elapsed, Duration length) =>
        (elapsed / length.inMicroseconds).clamp(0.0, 1.0);

    return TweenAnimationBuilder<double>(
      key: ValueKey('stagger_$index'),
      tween: Tween(begin: start, end: total),
      duration: Duration(microseconds: (total - start).round()),
      builder: (context, time, child) {
        final elapsed = time - delay.inMicroseconds;
        final moved = rise.curve.transform(progress(elapsed, rise.duration));
        final opacity = fade.curve.transform(progress(elapsed, fade.duration));
        return Opacity(
          opacity: opacity.clamp(0.0, 1.0),
          child: Transform.translate(
            offset: Offset(0, distance * (1 - moved)),
            child: child,
          ),
        );
      },
      child: child,
    );
  }
}

class AnimatedBadge extends StatefulWidget {
  final int count;
  final Widget child;

  const AnimatedBadge({super.key, required this.count, required this.child});

  /// How far above 1 a bump from rest reaches.
  static const bumpHeight = 0.15;

  @override
  State<AnimatedBadge> createState() => _AnimatedBadgeState();
}

class _AnimatedBadgeState extends State<AnimatedBadge>
    with SingleTickerProviderStateMixin {
  static final _materialSpring = M3EMotion.expressiveSpatialFast
      .toDescription();

  /// The push of each style's spring, by Animation speed: a slower spring
  /// reaches the same height on a gentler push.
  static final _pushes = <(bool mornye, double timeScale), double>{};

  /// The speed, in scale units a second, at which a push from rest on
  /// [spring] peaks at [AnimatedBadge.bumpHeight].
  static double _pushFor(SpringDescription spring) {
    final probe = SpringSimulation(spring, 0, 0, 1);
    var peak = 0.0;
    for (var t = 0.0; t < 1; t += 1 / 1000) {
      peak = math.max(peak, probe.x(t));
    }
    return AnimatedBadge.bumpHeight / peak;
  }

  // The controller holds the scale itself; at rest it is 1.
  late final AnimationController _controller = AnimationController.unbounded(
    vsync: this,
    value: 1,
  );
  int _previousCount = 0;

  @override
  void initState() {
    super.initState();
    _previousCount = widget.count;
  }

  @override
  void didUpdateWidget(AnimatedBadge oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.count > _previousCount &&
        !MediaQuery.disableAnimationsOf(context)) {
      final mornye = context.isMornye;
      final spring = mornye
          ? MornyeMotion.fastSpring
          : MotionPace.spring(_materialSpring);
      final push = _pushes[(mornye, MotionPace.timeScale)] ??= _pushFor(spring);
      // A badge already rising faster than a push keeps its speed.
      _controller.animateWith(
        SpringSimulation(
          spring,
          _controller.value,
          1,
          math.max(_controller.velocity, push),
          // Rests at exactly 1, so the badge is not left a hair off scale.
          snapToEnd: true,
        ),
      );
    }
    _previousCount = widget.count;
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ScaleTransition(scale: _controller, child: widget.child);
  }
}
