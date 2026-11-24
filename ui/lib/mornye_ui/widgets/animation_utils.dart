// Vendored from Mornye v5.4.0 (mornyeapp/Mornye @ c4a1c7b812eb135d6e1a295b6b25e11abbd72a7e)
// upstream file: lib/widgets/animation_utils.dart — Copyright 2026 zarzet, SPDX-License-Identifier: LGPL-3.0-only.
// Vendored for AnantPlay Sinhala Player v3.4.6 (lk.techworld.anantplay.sicast) under
// LGPL-3.0-only. See ui/lib/mornye_ui/LICENSE (+ COPYING, NOTICE) for the full terms.
// Modification: only the package import below was rewritten from package:mornye/
// to package:anantplay_ui/mornye_ui/; no other lines were changed in this file.
// Modification: extracted ONLY AnimatedBadge (+ its State) from the upstream
// 1690-line skeleton library; the remaining classes (ShimmerLoading, the
// desktop TrackTable skeletons, …) depend on Mornye's music library UI and
// are not part of the vendored engine. Upstream imports for the badge are
// re-declared below.

import 'dart:math' as math;
import 'package:flutter/physics.dart';
import 'package:flutter/widgets.dart';
import 'package:material_3_expressive/material_3_expressive.dart';
import 'package:material_ui/material_ui.dart';
import 'package:anantplay_ui/mornye_ui/theme/app_motion.dart';
import 'package:anantplay_ui/mornye_ui/theme/mornye_theme.dart';

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
