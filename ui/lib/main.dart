// AnantPlay Sinhala Player v3.4.6 — the Mornye Liquid-Glass shell entry.
//
// Hosts the vendored Mornye v5.4.0 engine (ui/lib/mornye_ui, LGPL-3.0-only)
// and routes between the screens: onboarding (first launch), home, settings,
// help — with the player + browser living in the native layer (PlayActivity's
// Media3 engine + WebBrowserView), launched through the bridge.
import 'package:material_ui/material_ui.dart';

import 'package:anantplay_ui/mornye_ui/theme/mornye_theme.dart';
import 'package:anantplay_ui/mornye_ui/models/theme_settings.dart';

import 'anantplay/app_state.dart';
import 'anantplay/home_screen.dart';
import 'anantplay/onboarding_screen.dart';

void main() {
  runApp(const AnantPlayApp());
}

class AnantPlayApp extends StatelessWidget {
  const AnantPlayApp({super.key});

  @override
  Widget build(BuildContext context) {
    return AppStateScope(
      child: MaterialApp(
        title: 'AnantPlay Player',
        debugShowCheckedModeBanner: false,
        theme: MornyeTheme.build(
          Brightness.dark,
          accent: MornyeAccent.teal,
          glassClarity: 0.75,
        ),
        home: const ShellRoot(),
      ),
    );
  }
}

/// The shell root: first-launch onboarding vs. the home shell.
class ShellRoot extends StatelessWidget {
  const ShellRoot({super.key});

  @override
  Widget build(BuildContext context) {
    final state = AppState.of(context);
    if (!state.settings.onboardingDone) {
      return const OnboardingScreen();
    }
    return const HomeScreen();
  }
}
