// AnantPlay Sinhala Player v3.4.6 — the typed mirror of the AppPrefs surface.
//
// The Java host owns the values (getSharedPreferences("sicast")); this class
// is the Dart-side read/typed layer for the Mornye Appearance Studio. Keys
// and defaults match AppPrefs.java exactly (verify against gatherSettings()).

/// The import preview (SettingsBackup.parseImport summary).
class ImportPreview {
  const ImportPreview({
    required this.ok,
    required this.error,
    required this.errorSi,
    required this.rejected,
    required this.count,
  });

  factory ImportPreview.fromMap(Map<dynamic, dynamic> map) => ImportPreview(
        ok: map['ok'] == true,
        error: (map['error'] as String?) ?? '',
        errorSi: (map['errorSi'] as String?) ?? '',
        rejected: (map['rejected'] as num?)?.toInt() ?? 0,
        count: (map['count'] as num?)?.toInt() ?? 0,
      );

  final bool ok;
  final String error;
  final String errorSi;
  final int rejected;
  final int count;
}

/// The whole AppPrefs map with typed readers (key names = AppPrefs constants).
class SettingsMirror {
  SettingsMirror(this._values);

  final Map<String, Object?> _values;

  static SettingsMirror fromJavaMap(Map<dynamic, dynamic> map) {
    final values = <String, Object?>{};
    map.forEach((k, v) => values[k as String] = v);
    return SettingsMirror(values);
  }

  /// v3.4.6.b — the raw map (completeOnboarding's synchronous overlay writes
  /// through this, so the local flip never loses any other live value).
  Map<String, Object?> get raw => _values;

  bool b(String key, bool def) {
    final v = _values[key];
    return v is bool ? v : def;
  }

  int i(String key, int def) {
    final v = _values[key];
    if (v is int) return v;
    if (v is num) return v.toInt();
    return def;
  }

  String s(String key, String def) {
    final v = _values[key];
    return v is String ? v : def;
  }

  // ── theme/appearance (the Mornye Studio surface) ──────────────────────────

  static const kThemeMode = 'themeMode';
  static const kDynamicColor = 'dynamicColor';
  static const kAccent = 'accent';
  static const kAccentHex = 'accentHex';
  static const kUiLang = 'uiLang';
  static const kGlassClarity = 'glassClarity';
  static const kGlassOutlines = 'glassOutlines';
  static const kIconGlass = 'iconGlass';
  static const kUiFont = 'uiFont';
  static const kMotion = 'motionEnabled';
  static const kAccentGlow = 'accentGlow';
  static const kSurfaceStyle = 'surfaceStyle';
  static const kBottomBarStyle = 'bottomBarStyle';
  static const kBottomBarLabels = 'bottomBarLabels';
  static const kOnboardingDone = 'onboardingDone';
  static const kOnboardingStage = 'onboardingStage';
  static const kOnboardingVersion = 'onboardingVersion';
  static const kQuickstartDone = 'quickstartDone';
  static const kShowWebTab = 'showWebTab';
  static const kShowQuickActions = 'showQuickActions';
  static const kAutoUpdate = 'autoUpdate';
  static const kAutoOpenSniffed = 'autoOpenSniffed';
  static const kAdBlock = 'adBlock';
  static const kWebDesktopUa = 'webDesktopUa';
  static const kReaderDark = 'readerDark';
  static const kFavicons = 'favicons';
  static const kShowBlockCount = 'showBlockCount';
  static const kSearchEngine = 'searchEngine';
  static const kSpeedDialCols = 'speedDialCols';
  static const kOmnibarPos = 'omnibarPos';
  static const kAutoFetch = 'autoFetch';
  static const kAutoTranslateNonSi = 'autoTranslateNonSi';
  static const kAutoZip = 'autoZip';
  static const kFreeTranslator = 'freeTranslator';
  static const kMidnightDimmer = 'midnightDimmer';
  static const kMidnightDimPct = 'midnightDimPct';
  static const kIndexUrl = 'indexUrl';
  static const kContinueWatching = 'continueWatching';

  // ── v3.4.6.c — the advanced customization keys (AppPrefs 1:1) ───────────

  static const kDensity = 'layoutDensity';
  static const kFontSpKey = 'fontSp';
  static const kFontFamily = 'fontFamily';
  static const kSubColor = 'subColor';
  static const kSubBg = 'subBgStyle';
  static const kSubBgOpacity = 'subBgOpacity';
  static const kSubBold = 'subBold';
  static const kSubBottomPct = 'subBottomPct';
  static const kSubLineSpacing = 'subLineSpacing';
  static const kSyncStepMs = 'subSyncStepMs';
  static const kAspectMode = 'aspectMode';
  static const kReceiverName = 'receiverName';
  static const kReceiverPort = 'receiverPort';
  static const kDlna = 'dlnaEnabled';
  static const kMdns = 'mdnsEnabled';
  static const kWorkerUrl = 'workerUrl';

  String get themeMode => s(kThemeMode, 'dark');
  bool get dynamicColor => b(kDynamicColor, false);
  int get accent => i(kAccent, 0);
  String get accentHex => s(kAccentHex, '');
  int get glassClarity => i(kGlassClarity, 75);
  bool get glassOutlines => b(kGlassOutlines, true);
  bool get iconGlass => b(kIconGlass, true);
  bool get onboardingDone => b(kOnboardingDone, false);
  int get onboardingStage => i(kOnboardingStage, 0);

  /// v3.4.6.c — the onboarding VERSION int (the upgrader's gate):
  /// 0 = fresh install, 1 = a legacy v3.4.6.a install that already finished
  /// the OLD wizard (onboardingDone=true, no stamp), 2 = the current 6-slide
  /// interactive wizard has been seen. `onboardingCurrent` = version >= 2.
  int get onboardingVersion => i(kOnboardingVersion, onboardingDone ? 1 : 0);
  bool get onboardingCurrent => onboardingVersion >= 2;
  bool get quickstartDone => b(kQuickstartDone, false);
  bool get showWebTab => b(kShowWebTab, true);
  bool get autoUpdate => b(kAutoUpdate, true);
  bool get motionEnabled => b(kMotion, true);

  /// v3.4.6.b — the Sources & Index Helper's saved URL (AppPrefs.DEFAULT_INDEX_URL).
  String get indexUrl => s(kIndexUrl, 'https://raw.githubusercontent.com/AnantPlay/index/main/index.json');
}
