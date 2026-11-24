// Vendored from Mornye v5.4.0 (mornyeapp/Mornye @ c4a1c7b812eb135d6e1a295b6b25e11abbd72a7e)
// upstream file: lib/utils/desktop_platform.dart — Copyright 2026 zarzet, SPDX-License-Identifier: LGPL-3.0-only.
// Vendored for AnantPlay Sinhala Player v3.4.6 (lk.techworld.anantplay.sicast) under
// LGPL-3.0-only. See ui/lib/mornye_ui/LICENSE (+ COPYING, NOTICE) for the full terms.
// Modification: only the package import below was rewritten from package:mornye/
// to package:anantplay_ui/mornye_ui/; no other lines were changed in this file.
import 'dart:io' show Platform;

/// Overrides [isDesktopPlatform] in tests.
bool? debugDesktopPlatformOverride;

// `flutter test` runs on a desktop host; tests see a mobile app unless they
// opt in through [debugDesktopPlatformOverride].
final bool _hostIsDesktop =
    !Platform.environment.containsKey('FLUTTER_TEST') &&
    (Platform.isMacOS || Platform.isWindows || Platform.isLinux);

/// True in Windows, Linux and macOS builds of the app.
bool get isDesktopPlatform => debugDesktopPlatformOverride ?? _hostIsDesktop;
