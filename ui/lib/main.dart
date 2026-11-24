// AnantPlay Sinhala Player v3.4.6.a — the Mornye Liquid-Glass shell entry.
//
// Hosts the vendored Mornye v5.4.0 engine (ui/lib/mornye_ui, LGPL-3.0-only)
// and routes between the screens: onboarding (first launch), home, settings,
// help — with the player + browser living in the native layer (PlayActivity's
// Media3 engine + WebBrowserView), launched through the bridge.
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

import 'package:anantplay_ui/mornye_ui/theme/mornye_theme.dart';
import 'package:anantplay_ui/mornye_ui/models/theme_settings.dart';
import 'package:anantplay_ui/mornye_ui/widgets/mornye_glass_preparation.dart';

import 'anantplay/app_state.dart';
import 'anantplay/home_screen.dart';
import 'anantplay/onboarding_screen.dart';

// ── v3.4.6.a — THE DART-SIDE CRASH REPORTER ─────────────────────────────────
// The native layer exposes `anantplay/crashlog` (MainActivity.onCrashlogCall),
// which persists the report into the SAME CrashLog file native crashes use.
// Both hooks below are registered BEFORE runApp() and guard themselves: a
// reporter that throws inside the error hook would loop, so every send is
// try/caught and async-fire-and-forget — the framework still gets to render
// the error screen / keep running (PlatformDispatcher.onError returning true
// means "handled": a startup error must never take the whole app down with it,
// which is the exact "won't open" failure class this build exists to kill).
const MethodChannel _crashlog = MethodChannel('anantplay/crashlog');

void _forwardToNativeCrashLog(String kind, Object error, StackTrace? stack,
    {String zone = 'main'}) {
  try {
    _crashlog.invokeMethod('report', <String, Object>{
      'kind': kind,
      'zone': zone,
      'error': error.toString(),
      'stack': stack?.toString() ?? '',
    }).catchError((Object e) {/* the native side already logged; drop */});
  } catch (_) {
    // The channel itself is down (engine-level failure) — nothing more to do.
  }
}

void _installCrashHooks() {
  FlutterError.onError = (FlutterErrorDetails details) {
    _forwardToNativeCrashLog('FlutterError', details.exception, details.stack,
        zone: details.library ?? 'flutter');
    FlutterError.presentError(details); // keep the default red/error surface too
  };
  PlatformDispatcher.instance.onError = (Object error, StackTrace stack) {
    _forwardToNativeCrashLog('PlatformError', error, stack);
    return true; // handled — the app stays alive
  };
}

void main() {
  _installCrashHooks();
  runApp(const AnantPlayApp());
}

class AnantPlayApp extends StatelessWidget {
  const AnantPlayApp({super.key});

  @override
  Widget build(BuildContext context) {
    // v3.4.6.a — FULL MORNYE PARITY: ProviderScope (the riverpod root the
    // vendored Mornye providers — glass level, runtime profile — resolve
    // against) + MornyeGlassPreparation (upstream app.dart's exact placement:
    // inside the app widget tree, wrapping the shell; it fires the one-time
    // GPU warm-up when liquid glass is on so the FIRST glass frame never
    // janks — the same contract upstream keeps).
    return ProviderScope(
      child: AppStateScope(
        child: MornyeGlassPreparation(
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
        ),
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
