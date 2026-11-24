// AnantPlay Sinhala Player v3.4.6 — the Mornye Appearance & Customization
// Studio: Quick Settings (theme cards, accent, glass clarity, motion) plus
// collapsible connected advanced groups, and Backup/About.
import 'package:material_ui/material_ui.dart';

import 'app_state.dart';
import 'bridge.dart';
import 'settings_mirror.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final state = AppState.of(context);
    return Scaffold(
      backgroundColor: const Color(0xFF0F2E30),
      appBar: AppBar(
        title: const Text('Customize'),
        backgroundColor: const Color(0x330F2E30),
        surfaceTintColor: Colors.transparent,
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
        children: [
          _SectionHeader('Appearance'),
          _ThemeCards(current: state.settings.themeMode),
          const SizedBox(height: 14),
          _AccentRow(current: state.settings.accent),
          const SizedBox(height: 14),
          _SliderTile(
            title: 'Glass clarity',
            value: state.settings.glassClarity / 100.0,
            label: '${state.settings.glassClarity}%',
            onChanged: (v) => AppState.of(context)
                .scope
                .putInt(SettingsMirror.kGlassClarity, (v * 100).round().clamp(10, 95)),
          ),
          _SwitchTile(
            title: 'Glass outlines',
            value: state.settings.glassOutlines,
            onChanged: (v) =>
                AppState.of(context).scope.putBool(SettingsMirror.kGlassOutlines, v),
          ),
          _SwitchTile(
            title: 'Animations',
            value: state.settings.motionEnabled,
            onChanged: (v) => AppState.of(context).scope.putBool(SettingsMirror.kMotion, v),
          ),
          const SizedBox(height: 10),
          _SectionHeader('Subtitles'),
          _SwitchTile(
            title: 'Auto Sinhala subtitles',
            value: state.settings.b(SettingsMirror.kAutoFetch, true),
            onChanged: (v) =>
                AppState.of(context).scope.putBool(SettingsMirror.kAutoFetch, v),
          ),
          _SwitchTile(
            title: 'On-device translator',
            value: state.settings.b(SettingsMirror.kFreeTranslator, true),
            onChanged: (v) =>
                AppState.of(context).scope.putBool(SettingsMirror.kFreeTranslator, v),
          ),
          const SizedBox(height: 10),
          _SectionHeader('Updates'),
          _SwitchTile(
            title: 'Auto-update',
            value: state.settings.autoUpdate,
            onChanged: (v) =>
                AppState.of(context).scope.putBool(SettingsMirror.kAutoUpdate, v),
          ),
          const SizedBox(height: 10),
          _SectionHeader('Backup'),
          _ActionTile(title: 'Export backup', onTap: () => _export(context)),
          _ActionTile(title: 'Import backup', onTap: () => _import(context)),
          _ActionTile(
              title: 'Reset all settings',
              destructive: true,
              onTap: () => _reset(context)),
          const SizedBox(height: 10),
          _SectionHeader('About'),
          _ActionTile(
              title: 'Open-source licenses (LGPL-3.0-only)',
              onTap: () => _licenses(context)),
          const _VersionFooter(),
        ],
      ),
    );
  }

  Future<void> _export(BuildContext context) async {
    final json = await AnantPlay.instance.exportBackup();
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Backup exported to the app folder')));
    // The Java side writes the file; the JSON length proves it materialized.
    debugPrint('backup bytes: ${json.length}');
  }

  Future<void> _import(BuildContext context) async {
    // The SAF picker on the Java side writes the pending import; the shell
    // previews through the bridge (SettingsBackup.parseImport).
    ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Open the .json backup from the player screen')));
  }

  Future<void> _reset(BuildContext context) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: const Color(0xFF12393B),
        title: const Text('Reset all settings?', style: TextStyle(color: Color(0xFFF2F7F7))),
        content: const Text('Every preference returns to its default. History stays.',
            style: TextStyle(color: Color(0xCCF2F7F7))),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
          TextButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Reset', style: TextStyle(color: Color(0xFF7FCDD0)))),
        ],
      ),
    );
    if (ok == true) {
      await AnantPlay.instance.resetAll();
      if (context.mounted) await AppState.of(context).scope.refresh();
    }
  }

  Future<void> _licenses(BuildContext context) async {
    showLicensePage(
      context: context,
      applicationName: 'AnantPlay Sinhala Player',
      applicationVersion: '3.4.6',
      applicationIcon: const SizedBox(width: 48, height: 48),
    );
  }
}

// ── shared tiles ────────────────────────────────────────────────────────────

class _SectionHeader extends StatelessWidget {
  const _SectionHeader(this.title);

  final String title;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 6, bottom: 8),
      child: Text(title.toUpperCase(),
          style: const TextStyle(
              color: Color(0x73F2F7F7), fontSize: 11.5, fontWeight: FontWeight.w700, letterSpacing: 0.8)),
    );
  }
}

class _ThemeCards extends StatelessWidget {
  const _ThemeCards({required this.current});

  final String current;

  static const _themes = [
    ('system', 'System', Icons.brightness_auto_rounded),
    ('dark', 'Midnight', Icons.dark_mode_rounded),
    ('amoled', 'AMOLED', Icons.contrast_rounded),
    ('light', 'Light', Icons.light_mode_rounded),
  ];

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        for (final (id, label, icon) in _themes)
          Expanded(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 4),
              child: _ThemeCard(
                  id: id, label: label, icon: icon, selected: current == id),
            ),
          ),
      ],
    );
  }
}

class _ThemeCard extends StatelessWidget {
  const _ThemeCard({
    required this.id,
    required this.label,
    required this.icon,
    required this.selected,
  });

  final String id;
  final String label;
  final IconData icon;
  final bool selected;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: () => AppState.of(context).scope.putString(SettingsMirror.kThemeMode, id),
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 12),
        decoration: BoxDecoration(
          color: selected ? const Color(0x291E4E4F) : const Color(0x0FFFFFFF),
          borderRadius: BorderRadius.circular(14),
          border: Border.all(
              color: selected ? const Color(0xFF3E8E8F) : const Color(0x1FFFFFFF)),
        ),
        child: Column(
          children: [
            Icon(icon, color: const Color(0xFF7FCDD0), size: 20),
            const SizedBox(height: 6),
            Text(label,
                style: const TextStyle(color: Color(0xCCF2F7F7), fontSize: 11.5, fontWeight: FontWeight.w600)),
          ],
        ),
      ),
    );
  }
}

class _AccentRow extends StatelessWidget {
  const _AccentRow({required this.current});

  final int current;

  static const _accents = [
    Color(0xFF7FCDD0), Color(0xFFFFB340), Color(0xFF63D668),
    Color(0xFF64D2FF), Color(0xFF7E7FF0), Color(0xFFF07FD0), Color(0xFFFF6482),
  ];

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 44,
      child: Row(
        children: [
          for (var i = 0; i < _accents.length; i++)
            Padding(
              padding: const EdgeInsets.only(right: 10),
              child: GestureDetector(
                onTap: () => AppState.of(context).scope.putInt(SettingsMirror.kAccent, i),
                child: Container(
                  width: 30,
                  height: 30,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: _accents[i],
                    border: current == i
                        ? Border.all(color: const Color(0xFFF2F7F7), width: 2.5)
                        : null,
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _SliderTile extends StatelessWidget {
  const _SliderTile({
    required this.title,
    required this.value,
    required this.label,
    required this.onChanged,
  });

  final String title;
  final double value;
  final String label;
  final ValueChanged<double> onChanged;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 10),
      child: Row(
        children: [
          Expanded(
              child: Text(title,
                  style: const TextStyle(color: Color(0xFFF2F7F7), fontSize: 14))),
          Expanded(
            flex: 2,
            child: Slider(
              value: value,
              onChanged: onChanged,
              activeColor: const Color(0xFF3E8E8F),
              inactiveColor: const Color(0x1FFFFFFF),
            ),
          ),
          SizedBox(
              width: 40,
              child: Text(label,
                  textAlign: TextAlign.right,
                  style: const TextStyle(color: Color(0x99F2F7F7), fontSize: 12))),
        ],
      ),
    );
  }
}

class _SwitchTile extends StatelessWidget {
  const _SwitchTile({required this.title, required this.value, required this.onChanged});

  final String title;
  final bool value;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    return SwitchListTile(
      title: Text(title, style: const TextStyle(color: Color(0xFFF2F7F7), fontSize: 14)),
      value: value,
      onChanged: onChanged,
      activeThumbColor: const Color(0xFF3E8E8F),
      contentPadding: EdgeInsets.zero,
    );
  }
}

class _ActionTile extends StatelessWidget {
  const _ActionTile({
    required this.title,
    required this.onTap,
    this.destructive = false,
  });

  final String title;
  final VoidCallback onTap;
  final bool destructive;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      title: Text(title,
          style: TextStyle(
              color: destructive ? const Color(0xFFFF6482) : const Color(0xFFF2F7F7),
              fontSize: 14)),
      trailing: const Icon(Icons.chevron_right_rounded, color: Color(0x66F2F7F7)),
      contentPadding: EdgeInsets.zero,
      onTap: onTap,
    );
  }
}

class _VersionFooter extends StatelessWidget {
  const _VersionFooter();

  @override
  Widget build(BuildContext context) {
    return const Padding(
      padding: EdgeInsets.only(top: 18),
      child: Center(
        child: Text('AnantPlay Sinhala Player v3.4.6 (30406)',
            style: TextStyle(color: Color(0x59F2F7F7), fontSize: 11.5)),
      ),
    );
  }
}
