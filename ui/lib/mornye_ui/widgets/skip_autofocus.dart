// Vendored from Mornye v5.4.0 (mornyeapp/Mornye @ c4a1c7b812eb135d6e1a295b6b25e11abbd72a7e)
// upstream file: lib/widgets/skip_autofocus.dart — Copyright 2026 zarzet, SPDX-License-Identifier: LGPL-3.0-only.
// Vendored for AnantPlay Sinhala Player v3.4.6 (lk.techworld.anantplay.sicast) under
// LGPL-3.0-only. See ui/lib/mornye_ui/LICENSE (+ COPYING, NOTICE) for the full terms.
// Modification: only the package import below was rewritten from package:mornye/
// to package:anantplay_ui/mornye_ui/; no other lines were changed in this file.
import 'dart:async';

import 'package:flutter/widgets.dart';

/// Mounts [child] without letting it take keyboard focus.
///
/// `M3EButtonGroup` autofocuses its first enabled segment. On desktop that
/// moves focus off the app's idle node, so Space presses the segment instead
/// of toggling playback. The descendants stay unfocusable until the
/// autofocus pass of the mounting frame has run; after that, Tab and
/// clicks reach them as usual.
class SkipAutofocus extends StatefulWidget {
  const SkipAutofocus({super.key, required this.child});

  final Widget child;

  @override
  State<SkipAutofocus> createState() => _SkipAutofocusState();
}

class _SkipAutofocusState extends State<SkipAutofocus> {
  var _focusable = false;

  @override
  void initState() {
    super.initState();
    // Autofocus applies in a microtask the mounting frame's build queues. A
    // microtask queued after that frame runs after it.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      scheduleMicrotask(() {
        if (mounted) setState(() => _focusable = true);
      });
    });
  }

  @override
  Widget build(BuildContext context) => Focus(
    canRequestFocus: false,
    skipTraversal: true,
    descendantsAreFocusable: _focusable,
    child: widget.child,
  );
}
