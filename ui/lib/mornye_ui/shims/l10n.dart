// ── Authored shim: l10n for the vendored engine ────────────────────────────
// Replaces Mornye's l10n/l10n.dart for the vendored visual engine (upstream:
// mornyeapp/Mornye @ c4a1c7b8, LGPL-3.0-only). Used only for the dialogCancel
// string in app_alert_dialog.dart. AnantPlay original; the repository LICENSE
// applies.
import 'package:flutter/widgets.dart';

extension AppLocalizationsX on BuildContext {
  AppL10n get l10n => const AppL10n();
}

class AppL10n {
  const AppL10n();
  String get dialogCancel => 'Cancel';
}
