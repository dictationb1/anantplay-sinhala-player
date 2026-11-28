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

  /// v3.4.6.f — GAP #2: value equality. AppState.updateShouldNotify compares
  /// snapshots; without this every refresh forced a full-tree rebuild even
  /// when no setting had actually changed.
  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    if (other is! SettingsMirror) return false;
    if (_values.length != other._values.length) return false;
    for (final MapEntry<String, Object?> entry in _values.entries) {
      if (!other._values.containsKey(entry.key) ||
          other._values[entry.key] != entry.value) {
        return false;
      }
    }
    return true;
  }

  @override
  int get hashCode => Object.hashAllUnordered(
        _values.entries.map((e) => Object.hash(e.key, e.value)),
      );

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
  /// v3.4.6.j — THE DUAL APP STYLE ('mornye' default | 'material'): the
  /// whole shell's theme + widget language flips on this key (AppPrefs'
  /// Java twin + buildAppTheme both read it).
  static const kAppStyle = 'appStyle';
  /// v3.4.6.j — the Continue Watching presentation ('list' | 'grid').
  static const kHistoryView = 'historyView';
  /// v3.4.6.j — the per-category backup toggles (Backup & Restore page).
  static const kBackupAppSettings = 'backupAppSettings';
  static const kBackupHistory = 'backupHistory';
  static const kBackupIndexes = 'backupIndexes';
  static const kOnboardingDone = 'onboardingDone';
  static const kOnboardingStage = 'onboardingStage';
  static const kOnboardingVersion = 'onboardingVersion';
  static const kQuickstartDone = 'quickstartDone';
  /// v3.4.6.m — the Setup Coach completion stamp (AppPrefs.K_COACH_COMPLETED).
  static const kCoachCompleted = 'coachCompleted';
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
  /// v3.4.6.i — the DoH DNS bypass flag (AppPrefs.K_DOH_DIAG's mirror twin;
  /// read by the Sources Hub's status chip).
  static const kDohDiag = 'dohDiag';
  static const kAutoFetch = 'autoFetch';
  static const kAutoTranslateNonSi = 'autoTranslateNonSi';
  static const kAutoZip = 'autoZip';
  static const kFreeTranslator = 'freeTranslator';
  /// v3.4.6.m — Low Data Mode (AppPrefs.K_LOW_DATA_MODE): 480p / 1.2 Mbps cap.
  static const kLowDataMode = 'lowDataMode';
  static const kMidnightDimmer = 'midnightDimmer';
  static const kMidnightDimPct = 'midnightDimPct';
  static const kIndexUrl = 'indexUrl';

  /// v3.4.6.k — the Multi-Index URL Manager's storage ("name|url" lines).
  static const kIndexUrls = 'indexUrlsList';
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
  // ══ v3.4.6.l — THE NEXT-LEVEL CINEMA PLAYER KEYS (1:1 with AppPrefs) ═════
  /// Double-tap seek seconds (5 | 10 | 15 | 30; AppPrefs.K_DOUBLE_TAP_SEC).
  static const kDoubleTapSeekSec = 'doubleTapSeekSec';
  /// Long-press hold speed per-mille (150 | 200 | 250 -> 1.5x/2.0x/2.5x).
  static const kLongPressSpeedX = 'longPressSpeedX';
  /// Two-finger pinch Fit/Fill zoom toggle (AppPrefs.K_PINCH_ZOOM).
  static const kPinchZoom = 'pinchZoom';
  /// 24/30/60 Hz seamless frame-rate matching (AppPrefs.K_FRAME_RATE_MATCH).
  static const kFrameRateMatch = 'frameRateMatch';
  /// Controls auto-hide milliseconds (2000 | 3000 | 3500 | 5000).
  static const kHudHideMs = 'hudHideMs';
  /// The seek-step milliseconds (5000 | 10000 | 15000 | 30000).
  static const kSeekStepMs = 'seekStepMs';
  /// The double-tap-to-seek toggle (AppPrefs.K_DOUBLE_TAP_SEEK).
  static const kDoubleTapSeek = 'doubleTapSeek';
  /// The horizontal swipe-seek toggle (AppPrefs.K_SWIPE_SEEK).
  static const kSwipeSeek = 'swipeSeek';
  /// The swipe-brightness toggle (AppPrefs.K_SWIPE_BRIGHTNESS).
  static const kSwipeBrightness = 'swipeBrightness';
  /// The swipe-volume toggle (AppPrefs.K_SWIPE_VOLUME).
  static const kSwipeVolume = 'swipeVolume';
  static const kAspectMode = 'aspectMode';
  static const kReceiverName = 'receiverName';
  static const kReceiverPort = 'receiverPort';
  static const kDlna = 'dlnaEnabled';
  static const kMdns = 'mdnsEnabled';
  static const kWorkerUrl = 'workerUrl';
  /// v3.4.6.d — the subtitle pipeline's default language (AppPrefs.K_DEFAULT_LANG).
  static const kDefaultLang = 'defaultLang';
  /// v3.4.6.k — THE SIGNED OTA CONFIG CHANNEL (AppPrefs.K_OTA_* — written by
  /// OtaConfigSync only after both-CDN consensus + the payload SHA-256
  /// verify; read by Settings' About & Storage pages so the remote config
  /// state is always visible, never a silent hidden pref).
  static const kOtaBridgeMirrors = 'otaBridgeMirrors';
  static const kOtaIndexFeeds = 'otaIndexFeeds';
  static const kOtaAdRules = 'otaAdRules';
  static const kOtaSubCacheDays = 'otaSubCacheDays';
  static const kOtaSyncAt = 'otaSyncAt';

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

  /// v3.4.6.m — the Setup Coach completion stamp (AppPrefs.K_COACH_COMPLETED).
  bool get coachCompleted => b(kCoachCompleted, false);

  /// v3.4.6.m — Low Data Mode (AppPrefs.K_LOW_DATA_MODE): 480p / 1.2 Mbps cap.
  bool get lowDataMode => b(kLowDataMode, false);
  bool get showWebTab => b(kShowWebTab, true);
  bool get autoUpdate => b(kAutoUpdate, true);
  bool get motionEnabled => b(kMotion, true);

  /// v3.4.6.b — the Sources & Index Helper's saved URL (AppPrefs.DEFAULT_INDEX_URL).
  String get indexUrl => s(kIndexUrl, 'https://raw.githubusercontent.com/AnantPlay/index/main/index.json');

  // ── v3.4.6.k — the signed OTA config channel (typed readers) ──────────────

  /// The newline-joined VERIFIED bridge mirrors ('' = pinned defaults).
  String get otaBridgeMirrors => s(kOtaBridgeMirrors, '');

  /// The newline-joined VERIFIED index feeds ('' = pinned defaults).
  String get otaIndexFeeds => s(kOtaIndexFeeds, '');

  /// The newline-joined VERIFIED ad-block rules ('' = pinned defaults).
  String get otaAdRules => s(kOtaAdRules, '');

  /// The local subtitle SRT cache TTL in days (OTA-managed, 1..30, default 7).
  int get otaSubCacheDays => i(kOtaSubCacheDays, 7).clamp(1, 30);

  /// Wall-clock ms of the last VERIFIED manifest application (0 = never).
  int get otaSyncAtMs => i(kOtaSyncAt, 0);

  /// True once one signed manifest has been verified and applied.
  bool get otaSynced => otaSyncAtMs > 0;

  /// The number of non-blank lines in a newline-joined list (0 for '').
  static int lineCount(String joined) =>
      joined.split('\n').where((l) => l.trim().isNotEmpty).length;
}
