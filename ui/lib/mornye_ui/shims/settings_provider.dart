// ── Authored shim: settings for the vendored engine ─────────────────────────
// Replaces Mornye's providers/settings_provider.dart for the vendored visual
// engine (upstream: mornyeapp/Mornye @ c4a1c7b8, LGPL-3.0-only). The engine
// reads exactly two fields — motionArtworkEnabled and forceBackdropBlur —
// which AnantPlay mirrors from AppPrefs over the platform bridge. This file
// is an AnantPlay original; the repository LICENSE applies.
import 'package:flutter_riverpod/flutter_riverpod.dart';

class AppSettings {
  const AppSettings({
    this.motionArtworkEnabled = true,
    this.forceBackdropBlur = false,
  });

  final bool motionArtworkEnabled;
  final bool forceBackdropBlur;
}

/// Overridden in main.dart with the live AppPrefs mirror (Riverpod 3 keeps
/// Provider.select, so the engine's `settingsProvider.select(...)` reads
/// work unchanged).
final settingsProvider = Provider<AppSettings>((ref) => const AppSettings());
