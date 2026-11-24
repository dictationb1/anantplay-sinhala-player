// Vendored from Mornye v5.4.0 (mornyeapp/Mornye @ c4a1c7b812eb135d6e1a295b6b25e11abbd72a7e)
// upstream file: lib/utils/android_tv.dart — Copyright 2026 zarzet, SPDX-License-Identifier: LGPL-3.0-only.
// Vendored for AnantPlay Sinhala Player v3.4.6 (lk.techworld.anantplay.sicast) under
// LGPL-3.0-only. See ui/lib/mornye_ui/LICENSE (+ COPYING, NOTICE) for the full terms.
// Modification: only the package import below was rewritten from package:mornye/
// to package:anantplay_ui/mornye_ui/; no other lines were changed in this file.
import 'dart:io' show Platform;

import 'package:flutter/services.dart';

bool? debugAndroidTvOverride;
bool _isAndroidTv = false;

bool get isAndroidTv => debugAndroidTvOverride ?? _isAndroidTv;

/// Resolve before the first frame so TV never mounts the portrait phone UI.
Future<void> initializeAndroidTv() async {
  if (!Platform.isAndroid) return;
  _isAndroidTv =
      await const MethodChannel(
        'com.zarz.mornye/backend',
      ).invokeMethod<bool>('isAndroidTv') ??
      false;
}
