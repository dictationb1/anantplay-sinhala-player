// Vendored from Mornye v5.4.0 (mornyeapp/Mornye @ c4a1c7b812eb135d6e1a295b6b25e11abbd72a7e)
// upstream file: lib/widgets/legacy_theme_brightness.dart — Copyright 2026 zarzet, SPDX-License-Identifier: LGPL-3.0-only.
// Vendored for AnantPlay Sinhala Player v3.4.6 (lk.techworld.anantplay.sicast) under
// LGPL-3.0-only. See ui/lib/mornye_ui/LICENSE (+ COPYING, NOTICE) for the full terms.
// Modification: only the package import below was rewritten from package:mornye/
// to package:anantplay_ui/mornye_ui/; no other lines were changed in this file.
// The glass package still reads the brightness from Flutter's built-in
// Material library, which the app itself no longer uses.
// ignore: deprecated_member_use
import 'package:flutter/material.dart' as legacy show Theme, ThemeData;
import 'package:material_ui/material_ui.dart';

/// Gives [child] the app theme's brightness through Flutter's built-in
/// Material theme, for package widgets that still read it there. Without it
/// they see the built-in fallback theme, which is always light.
///
/// The built-in theme also sets an icon theme; the app's own is put back so
/// icons under [child] keep their colors.
class LegacyThemeBrightness extends StatelessWidget {
  const LegacyThemeBrightness({super.key, required this.child});

  final Widget child;

  static final _light = legacy.ThemeData(brightness: Brightness.light);
  static final _dark = legacy.ThemeData(brightness: Brightness.dark);

  @override
  Widget build(BuildContext context) {
    final dark = Theme.of(context).brightness == Brightness.dark;
    return legacy.Theme(
      data: dark ? _dark : _light,
      child: IconTheme(data: IconTheme.of(context), child: child),
    );
  }
}
