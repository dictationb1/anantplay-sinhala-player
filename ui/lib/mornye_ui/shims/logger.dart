// ── Authored shim: logger for the vendored engine ───────────────────────────
// Replaces Mornye's utils/logger.dart (AppLogger) for the vendored visual
// engine (upstream: mornyeapp/Mornye @ c4a1c7b8, LGPL-3.0-only). Only the
// warning channel is exercised by the engine (glass warmup). AnantPlay
// original; the repository LICENSE applies.
import 'package:flutter/foundation.dart';

class AppLogger {
  AppLogger(this._name);
  final String _name;

  void w(String message) {
    debugPrint('[$_name] $message');
  }

  void i(String message) {
    debugPrint('[$_name] $message');
  }

  void e(String message, [Object? error]) {
    debugPrint('[$_name] $message $error');
  }
}
