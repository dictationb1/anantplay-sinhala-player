// AnantPlay Sinhala Player v3.4.6.h — THE ONE SHARED SUBTITLE TEXT-STYLE
// RESOLVER (the "fake Subtitle Font" fix).
//
// THE v3.4.6.g DEFECT: the Settings Live Preview hardcoded
// fontFamily: 'Noto Sans Sinhala' and the PlayerScreen overlay did the same —
// the "Subtitle font" control (System / Serif / Noto / Cinema Bold) wrote its
// preference but NOTHING on screen ever changed.
//
// THE LAW: BOTH the Settings Live Preview Card AND PlayerScreen's live
// subtitle overlay resolve their TextStyle HERE, so every font choice
// produces an immediate, unmistakable visual change in both places — while
// the Sinhala ZWJ conjuncts (ශ්‍රී, U+200D) pass through the text verbatim
// (a style never re-encodes text).
import 'package:flutter/painting.dart';

import 'settings_mirror.dart';

/// The four shipped subtitle font choices (labels in Settings order).
const List<String> kSubtitleFontChoices = <String>[
  'noto',    // Noto Sans Sinhala (bundled)
  'system',  // the platform default
  'serif',   // Noto Serif Sinhala via the Android system serif stack
  'cinema',  // Cinema Bold (Inter display + Noto Sinhala fallback)
];

/// The human label for a subtitle font id.
String subtitleFontLabel(String id) => switch (id) {
      'system' => 'System',
      'serif' => 'Serif',
      'cinema' => 'Cinema Bold',
      _ => 'Noto Sinhala',
    };

/// One font choice → its font family (null = the platform default).
String? subtitleFontFamily(String id) => switch (id) {
      'system' => null,
      'serif' => 'serif',
      'cinema' => 'Inter',
      _ => 'Noto Sans Sinhala',
    };

/// The fallback chain for a font choice (Cinema Bold's Latin display face
/// falls back to the bundled Sinhala face for Sinhala glyphs).
List<String>? subtitleFontFallback(String id) =>
    id == 'cinema' ? const <String>['Noto Sans Sinhala'] : null;

/// v3.4.6.h — THE ONE RESOLVER both the Settings Live Preview and the
/// PlayerScreen overlay paint with. `base` carries the caller-owned geometry
/// (fontSize, height, weight, color); the resolver owns ONLY the family +
/// fallback + the Cinema Bold weight bump, so the two surfaces can never
/// drift apart again.
TextStyle resolveSubtitleTextStyle(
  SettingsMirror s, {
  TextStyle? base,
}) {
  final String id = s.s(SettingsMirror.kFontFamily, 'noto');
  final bool cinema = id == 'cinema';
  // Cinema Bold: the display lane is a HEAVIER cut (+2 weight steps) with a
  // hair of letter spacing — an unmistakable change even before the eye
  // catches the Latin face swap (the native UiFonts.cinemaTypeface pairs
  // Inter w700 with the Sinhala Noto fallback the same way).
  final FontWeight weight = cinema
      ? FontWeight.w800
      : (base?.fontWeight ?? FontWeight.w500);
  final double? letterSpacing = cinema ? 0.2 : base?.letterSpacing;
  return (base ?? const TextStyle()).copyWith(
    fontFamily: subtitleFontFamily(id),
    fontFamilyFallback: subtitleFontFallback(id),
    fontWeight: weight,
    letterSpacing: letterSpacing,
  );
}
