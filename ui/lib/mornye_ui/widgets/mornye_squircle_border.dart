// Vendored from Mornye v5.4.0 (mornyeapp/Mornye @ c4a1c7b812eb135d6e1a295b6b25e11abbd72a7e)
// upstream file: lib/widgets/mornye_squircle_border.dart — Copyright 2026 zarzet, SPDX-License-Identifier: LGPL-3.0-only.
// Vendored for AnantPlay Sinhala Player v3.4.6 (lk.techworld.anantplay.sicast) under
// LGPL-3.0-only. See ui/lib/mornye_ui/LICENSE (+ COPYING, NOTICE) for the full terms.
// Modification: only the package import below was rewritten from package:mornye/
// to package:anantplay_ui/mornye_ui/; no other lines were changed in this file.
import 'package:material_ui/material_ui.dart';
import 'package:liquid_glass_easy/liquid_glass_easy.dart';

/// Uses the same outline as the optical lens, including its continuous corners.
/// The fill, shadow, clip and rim must agree to avoid cutting off the glass edge.
class MornyeSquircleBorder extends ShapeBorder {
  const MornyeSquircleBorder(this.radius);

  final double radius;

  @override
  EdgeInsetsGeometry get dimensions => EdgeInsets.zero;

  @override
  ShapeBorder scale(double t) => MornyeSquircleBorder(radius * t);

  @override
  Path getOuterPath(Rect rect, {TextDirection? textDirection}) =>
      liquidGlassSquirclePath(rect.size, radius, 1).shift(rect.topLeft);

  @override
  Path getInnerPath(Rect rect, {TextDirection? textDirection}) =>
      getOuterPath(rect, textDirection: textDirection);

  @override
  void paint(Canvas canvas, Rect rect, {TextDirection? textDirection}) {}

  @override
  bool operator ==(Object other) =>
      other is MornyeSquircleBorder && other.radius == radius;

  @override
  int get hashCode => radius.hashCode;
}
