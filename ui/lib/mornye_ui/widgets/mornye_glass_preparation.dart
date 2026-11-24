// Vendored from Mornye v5.4.0 (mornyeapp/Mornye @ c4a1c7b812eb135d6e1a295b6b25e11abbd72a7e)
// upstream file: lib/widgets/mornye_glass_preparation.dart — Copyright 2026 zarzet, SPDX-License-Identifier: LGPL-3.0-only.
// Vendored for AnantPlay Sinhala Player v3.4.6.a (lk.techworld.anantplay.sicast) under
// LGPL-3.0-only. See ui/lib/mornye_ui/LICENSE (+ COPYING, NOTICE) for the full terms.
// Modification: only the package imports were rewritten from package:mornye/
// to package:anantplay_ui/mornye_ui/; no other lines were changed in this file.

import 'dart:async';

import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:anantplay_ui/mornye_ui/controllers/mornye_glass_warmup.dart';
import 'package:anantplay_ui/mornye_ui/providers/runtime_profile_provider.dart';
import 'package:anantplay_ui/mornye_ui/theme/mornye_theme.dart';

/// Starts the one-time preparation when the full glass theme is enabled,
/// including switching to Mornye or enabling blur after the app has started.
class MornyeGlassPreparation extends ConsumerWidget {
  const MornyeGlassPreparation({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (context.isMornye &&
        ref.watch(mornyeGlassLevelProvider) == MornyeGlassLevel.liquid &&
        MornyeTheme.glassClarityOf(context) > 0.2 &&
        !ref.watch(
          mornyeGlassContrastBlockedProvider(
            MediaQuery.highContrastOf(context),
          ),
        )) {
      unawaited(MornyeGlassWarmup.prepare(View.of(context)));
    }
    return child;
  }
}
