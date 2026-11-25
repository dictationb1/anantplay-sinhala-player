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
    // A pending verified APK from a previous session re-arms instantly.
    _bootstrapPending();
  }

  Future<void> _bootstrapPending() async {
    final pending = await AnantPlay.instance.pendingUpdate();
    if (!mounted) return;
    if (pending.pending && pending.version.isNotEmpty) {
      setState(() {
        _updateKnown = true;
        _next = pending.version;
        _current = widget.currentVersion;
      });
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _gateSub?.cancel();
    super.dispose();
  }

  void _onGate(UpdateGateEvent e) {
    if (!mounted) return;
    setState(() {
      switch (e.gate) {
        case 'available':
          _updateKnown = true;
          _current = e.current.isEmpty ? widget.currentVersion : e.current;
          _next = e.next;
          _phase = _GatePhase.idle;
        case 'progress':
          _phase = _GatePhase.downloading;
          _percent = e.percent.clamp(0, 100);
          _bytes = e.bytes;
          _total = e.total;
        case 'verify':
          _phase = _GatePhase.verifying;
        case 'installer':
          _phase = _GatePhase.installer;
        case 'failed':
          _phase = _GatePhase.failed;
          _reason = e.reason;
        case 'uptodate':
          // The release vanished (yanked / race): unlock the gate.
          _updateKnown = false;
          _phase = _GatePhase.idle;
      }
    });
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

  Future<void> _updateNow() async {
    setState(() => _reason = '');
    await AnantPlay.instance.installPendingUpdate();
    // If nothing was pending (or the pending file was swept), the updateNow
    // handler re-runs the full resolve → download → verify pipeline; the
    // progress frames stream in through the gate events.
    final pending = await AnantPlay.instance.pendingUpdate();
    if (!mounted) return;
    if (!pending.pending) {
      await AnantPlay.instance.checkForUpdate();
    }
  }

  String get _mb => _bytes <= 0
      ? ''
      : ' · ${(_bytes / 1048576).toStringAsFixed(1)} MB'
          '${_total > 0 ? ' / ${(_total / 1048576).toStringAsFixed(1)} MB' : ''}';

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
                            'Opening installer… (tap Update on the system sheet)',
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
                    if (_phase == _GatePhase.failed) ...[
                      Container(
                        padding: const EdgeInsets.all(14),
                        decoration: BoxDecoration(
                          color: scheme.errorContainer.withValues(alpha: 0.3),
                          borderRadius: BorderRadius.circular(14),
                          border: Border.all(
                              color: scheme.error.withValues(alpha: 0.5)),
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
                    // ── THE ONLY BUTTON: Update Now / Retry Update ─────────
                    MornyeUpdateButton(
                      label: _phase == _GatePhase.failed
                          ? 'Retry Update'
                          : 'Update Now',
                      busy: _phase == _GatePhase.downloading ||
                          _phase == _GatePhase.verifying,
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
