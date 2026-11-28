// AnantPlay Sinhala Player v3.4.6.d — THE MANDATORY FULL-SCREEN BLOCKING
// FORCE-UPDATE GATE.
//
// The instant the host pushes a `gate:available` frame with a strictly newer
// release (Tier-1 releases/latest 302 → Tier-2 docs/latest.json, zero
// api.github.com calls, alphabetical 3.4.6.d < 3.4.6.e), the shell replaces
// the ENTIRE app viewport with this screen:
//
//   · PopScope(canPop: false) — the hardware Back button is BLOCKED;
//   · NO Skip / Dismiss / Later button exists anywhere on the surface;
//   · the ONLY way forward is `Update Now`: live 0% → 100% in-app download
//     progress (percent + MB downloaded), SHA-256 verification, and the
//     Android PackageInstaller handoff (auto-resumed on resume when the
//     user grants unknown-sources permission);
//   · a failed download (network drop) shows the clear reason + a Retry
//     Update button — the gate stays LOCKED until the update is installed;
//   · the gate NEVER appears when there is no update or the device is
//     offline (offline playback keeps working exactly as before).
import 'dart:async';

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart' hide Icons;
import 'package:material_ui/material_ui.dart' as mi show Icons;

import 'bridge.dart';

/// The one mandatory update surface. Renders nothing until an `available`
/// frame arrives; then it owns the whole screen and cannot be dismissed.
class ForceUpdateScreen extends StatefulWidget {
  const ForceUpdateScreen({super.key, this.currentVersion = '', this.initialGate});

  /// The installed version (AppInfo.VERSION_NAME, e.g. '3.4.6.d').
  final String currentVersion;

  /// v3.4.6.d — the shell's last gate:available frame, so the screen renders
  /// ARMED even when its own subscription attaches after that event landed
  /// (the cold-boot pending-APK handoff).
  final UpdateGateEvent? initialGate;

  @override
  State<ForceUpdateScreen> createState() => _ForceUpdateScreenState();
}

enum _GatePhase { idle, downloading, verifying, installer, failed }

class _ForceUpdateScreenState extends State<ForceUpdateScreen>
    with WidgetsBindingObserver {
  _GatePhase _phase = _GatePhase.idle;
  int _percent = 0;
  int _bytes = 0;
  int _total = 0;
  String _reason = '';
  String _next = '';
  String _current = '';
  bool _updateKnown = false;
  bool _resumedInstall = false;
  /// v3.4.6.f — LAW #3 Step 1: true between the user's Update Now tap and
  /// the moment the install-permission grant lands (the bounded native
  /// 30 s watcher auto-resumes the download the instant it does).
  bool _awaitingPermission = false;
  /// v3.4.6.i — ZERO-DOUBLE-DOWNLOAD: a verified APK for the target version
  /// is already cached on disk (its SHA-256 matched) — the button becomes
  /// 'Install Downloaded Update' and NEVER re-downloads.
  bool _cachedApkReady = false;
  /// v3.4.6.i — while a download / verify / installer handoff is in flight
  /// the button is disabled (a double-tap can never queue a second resolve
  /// → download cycle).
  bool _tapBusy = false;
  /// v3.4.6.j — the PackageInstaller handoff started at this time; 8 s
  /// later the button re-enables as `Install Downloaded Update` (the cached
  /// APK is verified — a tap re-opens the installer in < 50 ms; the red
  /// `Update Now` flash from video_6 03:20 is dead). v3.4.6.m — the window
  /// grew 3 s → 8 s (Part_0.01 04:46–05:15): the Android confirm sheet can
  /// legitimately sit on top for several seconds, and an accidental
  /// double-tap during that window must be structurally impossible.
  DateTime? _installerHandoffAt;
  Timer? _reenableTimer;
  StreamSubscription<UpdateGateEvent>? _gateSub;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    // The shell's handed-off frame arms the gate IMMEDIATELY (a late
    // subscriber would otherwise miss the event that created it).
    final g = widget.initialGate;
    if (g != null && g.gate == 'available' && g.next.isNotEmpty) {
      _updateKnown = true;
      _current = g.current;
      _next = g.next;
    }
    _gateSub = AnantPlay.instance.updateGateEvents().listen(_onGate);
    // A pending verified APK from a previous session re-arms instantly —
    // but ONLY when it is STRICTLY newer than the installed build (LAW #1:
    // the v3.4.6.e bootstrap armed the gate with a SAME-version pending and
    // locked the user behind v3.4.6.e → v3.4.6.e).
    _bootstrapPending();
  }

  Future<void> _bootstrapPending() async {
    final pending = await AnantPlay.instance.pendingUpdate();
    if (!mounted) return;
    if (!pending.pending || pending.version.isEmpty) return;
    final info = await AnantPlay.instance.appInfo();
    if (!mounted) return;
    final installed = info.versionName;
    // v3.4.6.f — LAW #1: the pending APK must be STRICTLY newer than the
    // installed build, else this screen renders NOTHING (self-lock dead).
    if (!isNewerVersion(pending.version, installed)) return;
    setState(() {
      _updateKnown = true;
      _next = pending.version;
      _current = installed.isEmpty ? widget.currentVersion : installed;
      // v3.4.6.i — a strictly-newer pending APK means the verified cache is
      // on disk RIGHT NOW: the button starts as 'Install Downloaded Update'.
      _cachedApkReady = true;
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _gateSub?.cancel();
    _reenableTimer?.cancel();
    super.dispose();
  }

  void _onGate(UpdateGateEvent e) {
    if (!mounted) return;
    setState(() {
      switch (e.gate) {
        case 'available':
          // v3.4.6.f — LAW #1 IN DART: an available frame whose `next` is NOT
          // strictly newer than the installed build never arms this screen
          // (v3.4.6.f → v3.4.6.f renders NOTHING — the self-lock is dead).
          final installed =
              e.current.isNotEmpty ? e.current : widget.currentVersion;
          if (!isNewerVersion(e.next, installed)) {
            _updateKnown = false;
            _phase = _GatePhase.idle;
            break;
          }
          _updateKnown = true;
          _current = e.current.isEmpty ? widget.currentVersion : e.current;
          _next = e.next;
          _phase = _GatePhase.idle;
        case 'progress':
          _phase = _GatePhase.downloading;
          // v3.4.6.i — live download bytes: the cached-APK fast path is NOT
          // in play while a real download is streaming.
          _cachedApkReady = false;
          _percent = e.percent.clamp(0, 100);
          _bytes = e.bytes;
          _total = e.total;
        case 'verify':
          _phase = _GatePhase.verifying;
        case 'installer':
          _phase = _GatePhase.installer;
          _awaitingPermission = false;
          // v3.4.6.i — the verified APK is now cached (the download just
          // completed, or the cache-reuse lane fired): any further tap is
          // an INSTALLER RE-OPEN, never a re-download.
          _cachedApkReady = true;
          _installerHandoffAt = DateTime.now();
        case 'failed':
          _phase = _GatePhase.failed;
          _reason = e.reason;
        case 'uptodate':
          // The release vanished (yanked / race): unlock the gate.
          _updateKnown = false;
          _phase = _GatePhase.idle;
      }
    });
    // v3.4.6.j — re-enable the button 8 s (v3.4.6.m, was 3 s) after the
    // installer handoff as 'Install Downloaded Update' (the verified cached
    // APK re-opens the installer in < 50 ms — never a red 'Update Now').
    if (e.gate == 'installer') {
      _reenableTimer?.cancel();
      _reenableTimer = Timer(const Duration(seconds: 8), () {
        if (mounted && _phase == _GatePhase.installer) {
          setState(() {});
        }
      });
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // The user just granted unknown-sources install permission in system
    // settings and came back: resume the pending install automatically.
    if (state == AppLifecycleState.resumed && _updateKnown) {
      _resumeInstall();
    }
  }

  Future<void> _resumeInstall() async {
    final pending = await AnantPlay.instance.pendingUpdate();
    if (!mounted) return;
    if (pending.pending) {
      setState(() => _resumedInstall = true);
      await AnantPlay.instance.installPendingUpdate();
    }
  }

  /// v3.4.6.f — LAW #3, THE USER'S UPDATE NOW TAP: permission FIRST, then
  /// the single-flight download, then verify + installer.
  /// v3.4.6.i — ZERO-DOUBLE-DOWNLOAD: (a) a verified cached APK is used
  /// INSTANTLY (the Java lane re-opens PackageInstaller in < 50 ms, never a
  /// re-download); (b) the tap is single-flight while a download / verify /
  /// handoff is running; (c) the v3.4.6.h follow-up checkForUpdate() network
  /// leg (fired because pendingUpdate() reports false while metadata is only
  /// written AFTER the download completes) is GONE.
  Future<void> _updateNow() async {
    if (_tapBusy) return;                    // one tap in flight, forever
    setState(() {
      _reason = '';
      _awaitingPermission = false;
      _tapBusy = true;
    });
    try {
      // ── Step 1 of 2 — the install-permission guarantee comes FIRST ──
      bool installsGranted = false;
      try {
        installsGranted = await AnantPlay.instance.canRequestPackageInstalls();
      } on PlatformException {
        installsGranted = false;
      }
      if (!installsGranted) {
        setState(() {
          _awaitingPermission = true;
          _reason = 'Step 1 of 2: allow AnantPlay Player to install updates.\n'
              'The install-updates page just opened — flip the switch, and the '
              'download starts automatically the moment you return.';
        });
        await AnantPlay.instance.installPendingUpdate();
        return;
      }
      // ── Step 2 — the native 'updateNow' lane: a verified APK already
      // cached → it re-opens the installer from cache (< 50 ms, zero
      // network); no cache → the ONE single-flight download → verify →
      // installer handoff. Progress frames stream through the gate events.
      await AnantPlay.instance.installPendingUpdate();
    } finally {
      if (mounted) setState(() => _tapBusy = false);
    }
  }

  String get _mb => _bytes <= 0
      ? ''
      : ' · ${(_bytes / 1048576).toStringAsFixed(1)} MB'
          '${_total > 0 ? ' / ${(_total / 1048576).toStringAsFixed(1)} MB' : ''}';

  /// v3.4.6.j — the button's honest label (video_6 03:20 — the red
  /// `Update Now` flash is DEAD):
  /// · failed → `Retry Update`;
  /// · a verified cached APK (idle) → `Install Downloaded Update` (never a
  ///   re-download);
  /// · the first 8 s of the PackageInstaller handoff → disabled
  ///   `Installer Active…` (the Android confirm sheet is on top; a double
  ///   tap can never queue a second handoff);
  /// · ≥ 8 s into the handoff → `Install Downloaded Update` — the cached
  ///   APK is verified on disk, so a tap re-opens the installer in < 50 ms
  ///   (ZERO re-download, and the button NEVER flashes red `Update Now`);
  /// · otherwise → `Update Now`.
  String get _buttonLabel {
    if (_phase == _GatePhase.failed) return 'Retry Update';
    if (_phase == _GatePhase.installer) {
      final heldFor = _installerHandoffAt == null
          ? const Duration()
          : DateTime.now().difference(_installerHandoffAt!);
      return heldFor >= const Duration(seconds: 8)
          ? 'Install Downloaded Update'
          : 'Installer Active…';
    }
    if (_cachedApkReady) return 'Install Downloaded Update';
    return 'Update Now';
  }

  /// v3.4.6.j — the button is disabled while a tap, download, verification or
  /// the first 8 s (v3.4.6.m) of the installer handoff is in flight (a
  /// double-tap can never queue a second resolve → handoff cycle).
  bool get _buttonBusy =>
      _tapBusy ||
      _phase == _GatePhase.downloading ||
      _phase == _GatePhase.verifying ||
      (_phase == _GatePhase.installer &&
          (_installerHandoffAt == null ||
              DateTime.now().difference(_installerHandoffAt!) <
                  const Duration(seconds: 8)));

  @override
  Widget build(BuildContext context) {
    // No update known: render nothing (the shell shows the normal app).
    if (!_updateKnown) return const SizedBox.shrink();
    final scheme = Theme.of(context).colorScheme;
    return PopScope(
      // THE LOCK: the hardware Back button can never leave this screen.
      canPop: false,
      child: AnnotatedRegion<SystemUiOverlayStyle>(
        value: SystemUiOverlayStyle.light,
        child: Scaffold(
          backgroundColor: const Color(0xFF0B1E1F),
          body: SafeArea(
            child: Center(
              child: SingleChildScrollView(
                padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 24),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const SizedBox(height: 8),
                    // ── the emblem ─────────────────────────────────────────
                    Container(
                      width: 96,
                      height: 96,
                      alignment: Alignment.center,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        gradient: RadialGradient(colors: [
                          scheme.primary.withValues(alpha: 0.35),
                          const Color(0x000B1E1F),
                        ]),
                        border: Border.all(
                            color: scheme.primary.withValues(alpha: 0.5)),
                      ),
                      child: Icon(mi.Icons.system_update_rounded,
                          size: 44, color: scheme.primary),
                    ),
                    const SizedBox(height: 22),
                    Text(
                      'Update required',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                          color: scheme.onSurface,
                          fontSize: 24,
                          fontWeight: FontWeight.w800),
                    ),
                    const SizedBox(height: 10),
                    // ── the version badge: v3.4.6.d → vX.Y.Z ────────────────
                    Center(
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                            horizontal: 14, vertical: 7),
                        decoration: BoxDecoration(
                          color: scheme.primaryContainer.withValues(alpha: 0.4),
                          borderRadius: BorderRadius.circular(999),
                          border: Border.all(
                              color: scheme.primary.withValues(alpha: 0.6)),
                        ),
                        child: Text(
                          'v${_current.isEmpty ? widget.currentVersion : _current}  →  v$_next',
                          style: TextStyle(
                              color: scheme.onSurface,
                              fontSize: 14.5,
                              fontWeight: FontWeight.w700,
                              fontFeatures: const [FontFeature.tabularFigures()]),
                        ),
                      ),
                    ),
                    const SizedBox(height: 14),
                    Text(
                      'An update is required to continue using AnantPlay Player.',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                          color: scheme.onSurfaceVariant,
                          fontSize: 13.5,
                          height: 1.5),
                    ),
                    const SizedBox(height: 26),
                    // ── the live progress / status surface ──────────────────
                    if (_phase == _GatePhase.downloading ||
                        _phase == _GatePhase.verifying ||
                        _phase == _GatePhase.installer) ...[
                      ClipRRect(
                        borderRadius: BorderRadius.circular(6),
                        child: LinearProgressIndicator(
                          minHeight: 7,
                          value: _phase == _GatePhase.installer
                              ? null
                              : (_phase == _GatePhase.verifying
                                  ? 1.0
                                  : _percent / 100.0),
                          backgroundColor:
                              scheme.onSurface.withValues(alpha: 0.12),
                          color: scheme.primary,
                        ),
                      ),
                      const SizedBox(height: 10),
                      Text(
                        switch (_phase) {
                          _GatePhase.downloading =>
                            'Downloading update — $_percent%$_mb',
                          _GatePhase.verifying => 'Verifying SHA-256…',
                          _GatePhase.installer =>
                            'Installer Active… (tap Update on the system sheet)',
                          _ => '',
                        },
                        textAlign: TextAlign.center,
                        style: TextStyle(
                            color: scheme.onSurface,
                            fontSize: 13,
                            fontWeight: FontWeight.w600),
                      ),
                      const SizedBox(height: 18),
                    ],
                    // ── the failure surface (gate stays LOCKED) ─────────────
                    if (_phase == _GatePhase.failed || _awaitingPermission) ...[
                      Container(
                        padding: const EdgeInsets.all(14),
                        decoration: BoxDecoration(
                          color: _awaitingPermission
                              ? scheme.primaryContainer.withValues(alpha: 0.35)
                              : scheme.errorContainer.withValues(alpha: 0.3),
                          borderRadius: BorderRadius.circular(14),
                          border: Border.all(
                              color: _awaitingPermission
                                  ? scheme.primary.withValues(alpha: 0.5)
                                  : scheme.error.withValues(alpha: 0.5)),
                        ),
                        child: Text(
                          _reason.isEmpty
                              ? 'The download failed. Check your connection and try again.'
                              : _reason,
                          textAlign: TextAlign.center,
                          style: TextStyle(
                              color: scheme.onSurface,
                              fontSize: 12.5,
                              height: 1.45),
                        ),
                      ),
                      const SizedBox(height: 14),
                    ],
                    // ── THE ONLY BUTTON: Update Now / Install Downloaded
                    // Update / Installer Active… / Retry Update ─────────
                    MornyeUpdateButton(
                      label: _buttonLabel,
                      busy: _buttonBusy,
                      onTap: _updateNow,
                    ),
                    const SizedBox(height: 10),
                    Text(
                      'The app stays locked until the update is installed.',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                          color: scheme.onSurfaceVariant.withValues(
                              alpha: 0.7),
                          fontSize: 11.5),
                    ),
                    // A resume-driven install may have already handed off.
                    if (_resumedInstall) ...[
                      const SizedBox(height: 8),
                      Text(
                        'Install permission granted — resuming installation…',
                        textAlign: TextAlign.center,
                        style: TextStyle(
                            color: scheme.primary,
                            fontSize: 11.5,
                            fontWeight: FontWeight.w600),
                      ),
                    ],
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// The one prominent Mornye glass action button of the gate.
class MornyeUpdateButton extends StatelessWidget {
  const MornyeUpdateButton({
    super.key,
    required this.label,
    required this.onTap,
    this.busy = false,
  });

  final String label;
  final VoidCallback onTap;
  final bool busy;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return FilledButton.icon(
      style: FilledButton.styleFrom(
        backgroundColor: scheme.primary,
        foregroundColor: scheme.onPrimary,
        disabledForegroundColor: scheme.onPrimary.withValues(alpha: 0.6),
        padding: const EdgeInsets.symmetric(vertical: 15, horizontal: 22),
        shape:
            RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      ),
      onPressed: busy ? null : onTap,
      icon: busy
          ? const SizedBox(
              width: 18,
              height: 18,
              child: CircularProgressIndicator(strokeWidth: 2.4))
          : Icon(mi.Icons.download_rounded, size: 20),
      label: Text(label,
          style:
              const TextStyle(fontSize: 15.5, fontWeight: FontWeight.w700)),
    );
  }
}
