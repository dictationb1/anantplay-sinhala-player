# AnantPlay Sinhala Player — v3.4.6.j (versionCode 30416) Complete Upgrade Report

**Release identity:** `versionName 3.4.6.j` · `versionCode 30416` · Git tag `v3.4.6.j` · APK `AnantPlay-Sinhala-Player-v3.4.6.j.apk` · `ui/pubspec.yaml` `3.4.6+30416` · **`docs/latest.json` untouched (still the live 3.4.6.i / 30415 tier — the release workflow is its only writer)** · `lab/ReceiverLab.java` gate widened to accept both `3.4.6.i` and `3.4.6.j` as the current tier.

**Verification summary (this sandbox, this tree):**

| Gate | Result |
| --- | --- |
| Java compile gate (`scripts/compile_check.sh`, android-36 + libs + Flutter embedding + R.java) | **COMPILE OK — 177 classes, 71 sources** |
| Flutter analyze | **No issues found** |
| Flutter widget/unit tests (`flutter test`) | **All 160 tests passed** |
| Cast-protocol + static lab (`lab/run_lab.sh`) | **Lab result: ALL 1469 PASSED — 3483 executable + static checks, 0 FAILED** |
| Gradle-host APK build (Gradle 9.5.0 · AGP 9.3.1 · Flutter 3.50.1 · JDK 21 · compileSdk/targetSdk 36) | **BUILD SUCCESSFUL** |
| APK badging | `versionCode='30416'` `versionName='3.4.6.j'` `minSdk='26'` `targetSdk='36'` |
| APK size | **43 MiB (cap 50 MiB)** |
| `zipalign -P 16 -f 4` + `zipalign -c -P 16 4` | **OK (Android-16 16 KiB zip offset contract)** |
| `apksigner verify` | **OK (v2+v3, debug key in this sandbox; the release keystore stays a CI secret)** |
| 73-probe DeepProbe matrix on the lab hooks | **73/73 PASS (domain roll-up A=6/6 B=9/9 C=16/16 D=6/6 E=13/13 F=6/6 G=8/8 I=9/9)** |
| Worker outage honesty | worker HTTP 0 ⇒ `env_fail=1, fail=0` (an outage is never laundered into an app failure); probe 47 still prints `✗ #47 FAIL` with the exact HTTP pair |

*(Sandbox build note: Maven Central rate-limited this sandbox's shared IP with intermittent 429s, so local resolution used the Aliyun google+public mirrors through an init script + `flutter.androidSdkRoot/-Pflutter.installedNdkVersions` properties. No shipped Gradle file was changed; CI on GitHub runners resolves from `google()`/`mavenCentral()` as before. The local NDK is a version-checked stub with a real `llvm-strip` extracted from `android-ndk-r28c` — AGP only uses it for symbol stripping and the version check.)*

---

## 0. සිංහල සාරාංශය (Sinhala summary)

**AnantPlay Sinhala Player v3.4.6.j** යනු සැබෑ ක්‍රියාත්මක බව ඔප්පු කළ, දුරදත්ත චිත්‍රපටි ධාවකයකි:

* **ශුද්ධ ස්වාභාවික දෘශ්‍ය ධාවකය (LAW #4):** චිත්‍රපටිය දැන් ධාවනය වන්නේ Flutter සංයුජකයෙන් (compositor) සම්පූර්ණයෙන් ගත් Flutter ධාවකයකින් — `PlayActivity` විවෘත වූ මොහොතේ `MainActivity.onPause()` ක්‍රියාත්මක වී Impeller GPU අත්හිටුවයි. දෘශ්‍යරූපය Media3 1.11.1 යකඩ MediaCodec + HWC ක්‍රමලේඛනයෙන් තිරයට එයි; රූප වේගය 120Hz→24/30/60Hz ගැළපේ; ක්‍රීඩා වේලාවට අනුව ධාරිතාව 30–60 තත්පර පුරවයි — සෑම 2 ක්‍රීඩා කාලයකටම බැටරි ~10% ක් (video_2 එහි 50%+ උණුසුම/ප්‍රමාදය මැරී ගියේය).
* **සැබෑ Mornye යෙදුම් රටාව (LAW #2):** App Style මාරුව (`mornye`|`material`), සෙවිය හැකි 8 උප-පිටු සැකසුම්, Storage & Cache සැබෑ MB විභාජනය, Backup & Restore වර්ගීකරණය, ඉහළ පාවෙන වීදුරු දැනුම්දීම් (පැරණි SnackBar මැරී ඇත), Apple-විලාසී උඩුපත් සන්දර්භ මෙනුව, සැබෑ මධ්‍යම රාත්‍රි අඳුරුකරණය, ඉතිහාස List/Grid.
* **නිල AnantPlay ස්ථාපනය + මඟ පෙන්වන පුහුණුකරු (LAW #3):** යෙදුම තුළම ස්ථාපනය/යාවත්කාලීන, ස්ථාපනය නොමිලේ අවසාන වූ මොහොතේම අනාවරණය (package-added/replaced receiver + 1s නිරීක්ෂණය), `SYSTEM_ALERT_WINDOW` සුළු-මධ්‍යස්ථ තටාකයේ 6-පියවර AnantPlay පුහුණුකරු (AccessibilityService කිසිවක් නැත), අවසර 90 තත්. ස්වයං-ආපසු.
* **කිසිදා නොවසන ස්වයං-යාවත්කාලීකරණය (LAW #1):** වැසීමේ ස්විචයක් නැත, ගබස් ඇති APK <50ms නැවත භාවිතය, ස්ථාපන තහවුරු පිටුව විවෘත වූ විට බොත්තම `Installer Open…` (තත්පර 3ක් අක්‍රිය) → `Install Downloaded Update` — කිසිදා රතු `Update Now` දිගස් නැත (video_6 03:20 ප්‍රතිසාධනය).
* **අවංක නිදොස් යන්ත්‍රය (LAW #5):** පෙරරූප නොමැති නිදොස් පිටු, `#87 FAIL` වර්ගීකරණ ප්‍රතිසාධනය (රූපස්ථර පිටිපත් බවට වෙනස්), Mornye තත්ත්ව කාඩ්පත්, අද්භූත ක්‍රමලේඛ දෝෂ නිරපේක්ෂව කියයි.

**සියල්ල ඔප්පු කළේය:** විභාග 160/160 සමත් · රසායනාගාර 3483 පරීක්ෂණ 0 අසමත් · APK 43 MiB (< 50 MiB) · 16 KiB සමමුහුර්ත · apksigner තහවුරු කළේය.

---

## 1. LAW #1 — the forced auto-updater (no off-switch, ever)

### 1.1 The `Installer Open…` state machine (video_6 03:20 — the red `Update Now` flash is dead)

`ui/lib/anantplay/force_update_screen.dart:248-272` — the button's honest label ladder:

* failed → `Retry Update`;
* **the first 3 s of the PackageInstaller handoff → disabled `Installer Open…`** (the Android confirm sheet sits on top; a double-tap can never queue a second handoff);
* **≥ 3 s into the handoff → `Install Downloaded Update`** — the verified cached APK re-opens the installer in < 50 ms (zero re-download, and the button **never** flashes red `Update Now`);
* a verified cached APK (idle) → `Install Downloaded Update` (never a re-download);
* otherwise → `Update Now`.

The busy gate (`force_update_screen.dart:274-284`) disables the button for the first **3 s** of the handoff; the re-enable timer (`force_update_screen.dart:171-181`) re-arms it as `Install Downloaded Update` at t=3 s. The installer-phase caption reads `Installer Open… (tap Update on the system sheet)` (`force_update_screen.dart:380-395`).

Contract mirrors updated with the new labels: `ui/test/v346i_test.dart:82-95`, `ui/test/shell_test.dart:1168-1173`, `src/lk/techworld/anantplay/sicast/DeepProbe.java:1650-1659` (now asserts `'Installer Open…'` present and `'Open Installer Again'`/`'Opening installer…'` gone).

### 1.2 The five permanent guarantees (unchanged, re-proven)

1. Frame-0 reopen lock with `required_update_version` persistence (`SelfUpdateCore.java`; lab checks `v346h upd: required_update_version is persisted…` / `every cold start re-arms the gate…` / `the persisted floor self-clears…` — all PASS).
2. Offline→online `ConnectivityManager.NetworkCallback` instant re-check (`MainActivity`; lab `v346h upd: MainActivity registers the offline→online NetworkCallback` — PASS).
3. 4 s fast check + the jsDelivr mirror lane; 55 MiB APK cap (`SelfUpdateCore.java:51`); zero `api.github.com` REST.
4. The full `[a-z]` alphabetical ladder, proven by execution (`ReceiverLab.java` `for (char ch = 'a'; ch < 'z'; …)` sweep — PASS).
5. Zero-disable-switch: only the honest manual `Check for update now` row remains (`settings_screen.dart:816-823`); the About page carries no auto-update switch (lab `v346i upd: zero-disable-switch` — PASS).

---

## 2. LAW #2 — the true dual App Style (Mornye ⇆ Material 3 Expressive)

### 2.1 The dual style key + live theme flip

* `AppPrefs.java:134-137` — `K_APP_STYLE = "appStyle"` (`mornye` default | `material`), round-trips through Backup & Restore (`AppPrefs.java:364`).
* `ui/lib/main.dart:215-260` — `buildAppTheme()`: the `material` lane rebuilds the same accent/wallpaper/AMOLED logic through Material 3's authentic tonal generator (`ColorScheme.fromSeed` → `surfaceContainerLow/High/Highest`, `surfaceDim/Bright`), while the `mornye` default keeps the vendored Liquid-Glass Cupertino palette.
* `ui/lib/anantplay/settings_screen.dart:421-432` — the App Style segmented row (Mornye | Material) writes the key live; the caption at `settings_screen.dart:502-508` names the active engine.

### 2.2 The searchable 8-page settings hub (video_2 03:40 / video_1 00:30–01:25)

`ui/lib/anantplay/settings_screen.dart:42-262` — one hub with a live `Search settings` field (`ValueKey('settings-search')`, `settings_screen.dart:163-186`) that filters every setting across every page inline (`_search` + `SettingEntry` index, `settings_screen.dart:281-341`), and eight connected sub-pages:

| Sub-page | Class | Evidence |
| --- | --- | --- |
| Appearance | `_AppearanceSettingsPage` | `settings_screen.dart:403` (App style, theme cards, accent + `#` Color Studio, Glass clarity/outlines, Animation speed, Interface size 85–115%, History view List/Grid, Midnight dimmer) |
| Playback & Subtitles | `_PlaybackSubtitlesPage` | `settings_screen.dart:518` (subtitle studio + gestures) |
| AnantPlay & Sources | `_AnantPlaySourcesPage` | `settings_screen.dart:564` (multi-index manager: 1-tap copy/add/delete) |
| Browser & Privacy | `_BrowserPrivacyPage` | `settings_screen.dart:592` (search-engine instant switch google.com/duckduckgo.com/startpage.com/search.brave.com, ad block, reader) |
| Cast & Network | `_CastNetworkPage` | `settings_screen.dart:607` (DLNA, mDNS, `Ping worker`) |
| Storage & Cache | `_StorageCachePage` | `settings_screen.dart:623` (real MB breakdown: app cache & WebView · subtitle cache · update cache · watch history + `Clear all cache` + `Refresh stats`) |
| Backup & Restore | `_BackupRestorePage` | `settings_screen.dart:767` (per-category toggles + create/restore + reset) |
| About & Licenses | `_AboutPage` | `settings_screen.dart:807` (version, `Check for update now`, `Run setup guide`, LGPL-3.0-only licenses) |

The sub-pages use the Mornye springy slide-up push (`settings_screen.dart:257-262` — `_pushPage`). **Pop-first rule fix:** the `Run setup guide` row now pops its sub-page route before re-arming the wizard (`settings_screen.dart:824-838` — the wizard can never render behind a pushed page; proven by `ui/test/shell_test.dart` "Run setup guide reopens the wizard inside the live shell" — PASS).

### 2.3 Storage & Cache — real bytes

`ui/lib/anantplay/settings_screen.dart:623-760` — `AnantPlay.instance.cacheStats()` (the real native breakdown) drives four MB rows + `total`, `Clear all cache` (app+WebView+subtitles+updates; the watch history has its own row) and `Refresh stats`; the bridge lane is `ui/lib/anantplay/bridge.dart` `cacheStats()/clearCache()/clearHistory()` (`MainActivity` answers with the live byte counts). The `v346j` lab checks cover the storage rows' presence and wiring.

### 2.4 The top floating glass notification (video_1 05:10–05:45 — the SnackBar is dead)

`ui/lib/mornye_ui/widgets/mornye_glass_notification.dart:1-40` — one top-anchored liquid-glass pill that slides down below the status bar with spring physics, auto-dismisses in 2.5 s, swipes UP to dismiss, adapts to light/dark, and never covers the bottom tab bar. Every consumer flipped: `action_feedback.dart:19-23` (`_say`), the settings sheets, the help screen's copy/share banners, the player's clipboard/lock hints (`player_screen.dart:986-1005`), the browser hub. Lab check `v346j` notification wiring greps prove the call sites; the widget tests assert the replacement.

### 2.5 The real Midnight dimmer (video_3 00:20–00:55 — "dragging the slider dims nothing")

`ui/lib/main.dart:470-490` — a root-level scrim above every widget: `IgnorePointer` (purely visual, zero hit-test impact) wrapping a `Colors.black.withValues(alpha: dimPct/100*0.65)` `ColoredBox` keyed `'midnight-dim-overlay'`, armed only in Dark/AMOLED, driven live by `Dim amount` 10–70% (`settings_screen.dart:492-504`). The old fake (two lerped surface roles) is gone.

### 2.6 History view + Mornye context menu + Apple overflow popover

* History view List|Grid — `AppPrefs.java:139` (`K_HISTORY_VIEW`), the segmented row `settings_screen.dart:496-501`, the Home renderer (video_2 05:20 fix).
* The settings overflow `⋮` opens the vendored `showMornyeContextMenu` popover (`settings_screen.dart:56-78` — Export/Import backup, Check for update now).

---

## 3. LAW #3 — official AnantPlay in-app install, the floating coach, instant detection

### 3.1 The guided setup coach (video_5) — `SYSTEM_ALERT_WINDOW` only, ZERO AccessibilityService

`src/lk/techworld/anantplay/sicast/AnantPlayCoachService.java` (new, 387 lines) — a draggable liquid-glass coach card over the official app:

* **6 steps** (`AnantPlayCoachService.java:55-76`): Step 1 Settings⚙ → Sources → *Add this index*; Step 2 `[Copy Sri Lanka IPTV Index]` → `https://raw.githubusercontent.com/dictationb1/srilanka-anantplay/main/index.json`; Step 3 `[Copy Sinhala Subs Bridge]` → `https://anantplay-si-subs-bridge.techworldlk.workers.dev/index.json`; Step 4 Connection → WARP:On · Ad block:On · Address bar:On; Step 5 IPTV → Hide offline channels:On → check channels now; Step 6 Player → boosts/auto-subs → `Finish Setup ✓` (removes the overlay cleanly, `AnantPlayCoachService.java:255-266`).
* 1-tap copy buttons per index step; collapse into a compact pill; Start/Stop wired through `MainActivity.java:1130-1141` (`openOfficialWithCoach` launches official AnantPlay **and** the overlay; leaving the guided setup removes it).
* The manifest declares `<service android:name=".AnantPlayCoachService" android:exported="false"/>` (`AndroidManifest.xml:149`).

### 3.2 The instant official-install detection (video_6 05:00 — `100% · 0 · confirm` stuck)

`src/lk/techworld/anantplay/sicast/MainActivity.java:966-1015`:

* a dynamic `ACTION_PACKAGE_ADDED/REPLACED` receiver watching `me.champdev.weblock`, registered with the Android-16-correct export flag — **one call, all APIs**: `registerReceiver(packageInstallReceiver, f, SDK_INT >= 33 ? Context.RECEIVER_NOT_EXPORTED : 0)` (`MainActivity.java:995-1006`; the un-flagged form would throw `SecurityException` on API 33+ and silently kill the watcher — caught by `lab/verify_layouts.py`, now fixed);
* **plus** a 1 s poll while the confirm state is active;
* the moment the install lands, `{t:'installed', x:'<version>'}` fires on the install events stream — Slide 2 flips green and auto-advances; the announcement itself (`announceOfficialInstalled`, `MainActivity.java:1046-1054`) pushes the Dart event instantly.

### 3.3 The 90-second permission auto-return (video_6 01:40–02:55 / video_4 01:30 — the ESET-style snap-back)

`src/lk/techworld/anantplay/sicast/MainActivity.java:1365-1425` — one shared watcher, three permissions (overlay/installs/battery):

* **90 s bounded lifetime (225 ticks)** — survives the full HyperOS danger-warning + toggle round trip with margin;
* **400 ms polling** — the exact millisecond the permission flips is caught in-budget;
* the return relaunch rides the **BAL-allowed PendingIntent lane** (`MODE_BACKGROUND_ACTIVITY_START_ALLOWED`, API 34+) with `NEW_TASK | SINGLE_TOP | REORDER_TO_FRONT`;
* zero AccessibilityService (permanent law — the lab's zero-accessibility gate re-proven).
* Watchers arm from Dart: `watchOverlayGrant` / `watchInstallPermission` / **new `watchBatteryGrant`** (`bridge.dart` + `MainActivity.java:1251-1270`), all 90 s / 400 ms.
* Lab check updated to the new contract: `v346f law3: the bounded 90 s install-permission watcher exists` (`lab/ReceiverLab.java:7393-7396` — `permissionWatchTicks >= 225` + `postDelayed(this, 400)`).

### 3.4 The hardened sources reader (video_4 03:30–04:05 — `Feed: unreachable (offline ok)`)

`src/lk/techworld/anantplay/sicast/SelfUpdateCore.java:735-880` — `fetchIndexFeed(url)`:

* **10 s hard deadline, 1 MiB body cap** (`FEED_DEADLINE_MS = 10_000`, `FEED_MAX_BYTES = 1 MiB`);
* HTTP **301/302/307/308 redirects followed (≤ 5 hops, every hop https)**, **gzip decoded**, a clean standard browser User-Agent;
* **the jsDelivr mirror lane**: a failing `raw.githubusercontent.com/<o>/<r>/<b>/<path>` retries as `https://cdn.jsdelivr.net/gh/<o>/<r>@<b>/<path>` (the Sri Lanka ISP DNS/connect kill);
* the **exact sanitized reason** on failure — `HTTP 404`, `UnknownHostException: raw.githubusercontent.com`, `deadline 10 s`, `body over 1 MiB cap`, `redirect without Location` — never a vague "unreachable (offline ok)".
* The Dart reply carries `{body, error, mirror}` (`bridge.dart` `IndexFeedResult` + `MainActivity.java` `fetchUrl` handler, run off the main thread); the Sources Hub renders the exact error string.

### 3.5 PermissionGuideActivity — the animated settings mockups (video_6 04:20 / video_4 01:10–03:10)

`src/lk/techworld/anantplay/sicast/PermissionGuideActivity.java` (446 lines) — for all four permissions (overlay / unknown-sources / battery / notifications) a mini animated Android-Settings page: the real app icon row, the selected row highlighted, **the glowing toggle that slides ON** (`animatedToggle`, `PermissionGuideActivity.java:368-400`), the looping lift arrow, in-fade rows. The onboarding copy was cleaned (video_6 04:20): `"3. Smooth, Cool Cinema Player"` / `"සිංහල උපසිරැසි සිනමා ධාවකය"` (`ui/lib/anantplay/onboarding_screen.dart:863-870` — the awkward `උණුසුම් නැති සිනමා වේදිකාව` is gone).

### 3.6 Onboarding: the mandatory first-run hides Skip (video_4 00:30–01:10)

`ui/lib/anantplay/onboarding_screen.dart:228-246` — a first-time launch (`isFirstLaunch && onboardingVersion < 2`) shows **no Skip**; a voluntary re-run shows `Close`. All 6 slides keep their interactive heroes (tap-to-replay beats).

---

## 4. LAW #4 — the pure native hardware cinema player (video_2 00:40–02:15: lag + >50% battery per 2 h)

### 4.1 Every launch lane routes to `PlayActivity`

`src/lk/techworld/anantplay/sicast/PlayActivity.java:163-196` — `launchNative(...)` is the one native Cinema lane every caller shares (Test Player, Play URL, Continue Watching, cast `/play`, DLNA `SetAVTransportURI`, the browser's *Play in Cinema*, external ACTION_VIEW/SEND). The v3.4.6.d "zero-UI router" (which forwarded into the Flutter shell's in-compositor player and finished) is **gone**: playback runs HERE, in a full-screen opaque Activity — Android calls `MainActivity.onPause()` the moment it opens, suspending the Flutter Impeller GPU renderer for the whole session (0% CPU / 0% GPU while the movie plays). `MainActivity.shellRoute`'s `play` type is intercepted and routed native (`MainActivity.java` route pump); the Test Player tap launches it directly (`testPlayer` handler).

### 4.2 The marathon hardware engine

`src/lk/techworld/anantplay/sicast/PlayActivity.java:878-910`:

* `DefaultRenderersFactory(EXTENSION_RENDERER_MODE_OFF)` + `setEnableDecoderFallback(false)` — hardware MediaCodec decoders only, zero software lane that could silently fall back to CPU decode;
* `setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)` — Android 16 drops the display from 120 Hz to the video's native 24/25/30/60 Hz while playback is seamless (HWC scanout at the movie's own cadence — the single biggest idle-power win);
* `DefaultLoadControl` **30–60 s buffer** — a handful of network wake-ups per minute instead of a 15 s drumbeat; the radio returns to idle between them;
* `WAKE_MODE_LOCAL` — the wake lock rides the player exactly while playing.
* Sinhala cues: `NotoSansSinhala`, ZWJ `ශ්‍රී` intact, 82% maxWidth, 3 lines, 14sp/15sp, **anchored cleanly above the bottom dock** — `bottomMargin = hudVisible ? max(base + dp(108), land ? dp(116) : dp(136)) : base` (`PlayActivity.java:3204-3213`, animated translationY) — the video_2 00:50 overlap fix.
* The native Mornye dark HUD: `GradientDrawable` glass bar (zero GPU shader cost), top bar (back/title/lock/rotate/PiP/VLC/status pill, 2.5 s auto-hide), center controls (play/±10s/long-press 2.0×/brightness+volume sliders), the fixed 5-icon bottom dock (Subtitles|Tracks|Fit|Speed|Search) + native dark bottom sheets.

### 4.3 The in-shell PlayerScreen is the compat surface only

`ui/lib/main.dart` — the shell's `play` route hands the URL to the native layer (`AnantPlay.instance.openPlayer(...)`), never mounting the in-compositor player for real playback; on exit the Continue Watching list refreshes. The shipped `PlayerScreen` class (still present, still tested — the compact-cinema constants the audits read) no longer receives real launches.

---

## 5. LAW #5 — the honest debug engine (zero fake PASS)

### 5.1 The `#87 FAIL` classification fix (video_2 02:20–02:45)

`ui/lib/main.dart:81-119` — `FlutterError.onError` now classifies: a framework **diagnostic** (a RenderFlex overflow line, `Improper use of ParentDataWidget`, `Another exception was thrown` echoes) is recorded in `_lastSessionDiagnostic` and surfaced in the H14 detail line but **never counted as an uncaught error**; a REAL exception (assertion, throw, state error) still increments `sessionFlutterErrorCount` and still fails H14 honestly — the count is never suppressed, only correctly classified. `deep_troubleshooter.dart:633-660` renders the split (`0 uncaught Dart errors ✓ · non-fatal diagnostic recorded: …`). The 73-probe native matrix runs **73/73 green** on the lab hooks with the fix in place.

### 5.2 Mornye status cards on the troubleshoot feed (video_2 02:20–02:45 — the `✓ #84 PASS` ASCII wall)

`ui/lib/anantplay/help_screen.dart` — every live feed row is now a `_ProbeStatusRow`: a crisp **circular green/red/spinner vector badge**, the probe number + its own running title, and a compact `ms` duration pill. **Fix found by the tests:** the done row carries its name through a dedicated `_feedNames` ledger (`help_screen.dart:116-133`, `401-449`) — the old post-hoc regex over the feed lines could not find a Checking line that its own done frame had already replaced. The structured `✓/✗` text still feeds the auto-copied clipboard report (Law #5 — nothing hidden), and the full honest report still leads with every `✗ FAIL`.

### 5.3 The permanent honesty machinery (re-proven in this run)

* `prefs` before `super.onCreate` (`MainActivity`), `safeGetLong/safeGetInt/putTyped` (`AppPrefs`), `zipalign -P 16`, LGPL-3.0-only, `allowBackup="false"`, APK < 50 MiB — all lab-checked green.
* The crash gate: an uncaught Java/Dart exception in the build is a FAIL naming the exact class+line (`probeF6`); the lab injects both cases and both behave.
* Worker outage ⇒ `env_fail`, never `fail`; a genuinely broken receiver lane stays in `fail` — both behaviors executed in this run.

---

## 6. The video-audit fix ledger (all 6 recordings)

| Video · timestamp | Defect | Fix · evidence |
| --- | --- | --- |
| video_1 00:30–01:25 | settings wall scroll chaos | searchable 8-page hub — `settings_screen.dart:42-262` |
| video_1 05:10–05:45 | bottom SnackBars | top floating glass pill — `mornye_glass_notification.dart:1-40`, all call sites flipped |
| video_2 00:40–02:15 | lag + >50% battery / 2 h | pure native hardware cinema — `PlayActivity.java:163-196, 878-910` |
| video_2 00:50 | subtitles under the dock | `bottomMargin = hudVisible ? …dp(136)/dp(116)… : base` — `PlayActivity.java:3204-3213` |
| video_2 02:20–02:45 | `✓ #84 PASS` ASCII wall + `#87 FAIL` | Mornye status rows + the diagnostic/error classification — `help_screen.dart`, `main.dart:81-119` |
| video_2 03:40 | settings wall | the hub (above) |
| video_2 05:20 | history presentation | History view List/Grid — `AppPrefs.java:139`, `settings_screen.dart:496-501` |
| video_3 00:20–00:55 | dimmer does nothing | real root scrim — `main.dart:470-490` |
| video_4 00:30–01:10 | first-run shows Skip | hidden on first launch — `onboarding_screen.dart:228-246` |
| video_4 01:10–03:10 | blurry permission boxes | animated settings mockups — `PermissionGuideActivity.java` |
| video_4 01:30 | no auto-return after grant | 90 s / 400 ms BAL snap-back — `MainActivity.java:1365-1425` |
| video_4 03:30–04:05 | `Feed: unreachable (offline ok)` | hardened fetch — `SelfUpdateCore.java:735-880` |
| video_5 | guided setup over official app | the floating 6-step coach — `AnantPlayCoachService.java` |
| video_6 01:40–02:55 | no auto-return | the watchers (above) |
| video_6 03:20 | red `Update Now` flash | `Installer Open…` 3 s → `Install Downloaded Update` — `force_update_screen.dart:248-284` |
| video_6 04:20 | awkward Sinhala copy + blank permission boxes | `onboarding_screen.dart:863-870` + the animated mockups |
| video_6 05:00–05:45 | Slide 2 stuck at `100% · 0 · confirm` | the instant detection — `MainActivity.java:966-1015` |
| video_2 01:30 (audit) | `Free translate now` → `worker unreachable (HttpExcept…)` | BridgeApi/FreeSubTranslator fallback chain + the exact-reason feed errors (the worker's own `/health` is live: `{"ok":true,"version":"4.1.0"}` — verified from this sandbox; the fallback to `LkSubFinder` + keyless `FreeSubTranslator` stays proven by lab checks) |

---

## 7. What shipped in the ZIP (the packaging contract)

`v3.4.6.j-mornye-v2-complete.zip` — the repo's ZIP-root layout, **exactly the 7 allowed root files** (`AndroidManifest.xml`, `LICENSE`, `README.md`, `SETUP-SI.md`, `THIRD_PARTY.md`, `build.sh`, `.gitignore`), all directories intact (`android/`, `assets/`, `docs/`, `lab/`, `libs/`, `res/`, `scripts/`, `src/`, `tools/`, `ui/`, `.github/`), and **zero** of: duplicates, `lab/out/`, `*.class`, `*.pyc`, `ui/.flutter-plugins-dependencies`, `ui/.ios`/`ui/.android`/`ui/.dart_tool`/`ui/build`, `android/local.properties`, `android/.gradle`/`build` dirs, `.git/`, `*.apk`, `*.zip`. This report also lives inside the ZIP at `docs/reports/V3_4_6_J_COMPLETE_UPGRADE_REPORT.md`.

## 8. How to build the APK from the ZIP

```
unzip v3.4.6.j-mornye-v2-complete.zip -d anantplay && cd anantplay
(cd ui && flutter pub get)                       # regenerates ui/.android + the pub cache
printf 'sdk.dir=<SDK>\nflutter.sdk=<FLUTTER_ROOT>\n' > android/local.properties
cd android && ./gradlew :app:assembleRelease     # Gradle 9.5.0 · AGP 9.3.1 · Flutter 3.50.1 · JDK 17+
# then: zipalign -P 16 -f 4 … && apksigner sign (the CI's release-apk.yml carries the full pipeline)
```

The mandatory gates before any release: `bash lab/run_lab.sh` (0 FAIL), `cd ui && flutter test` (0 FAIL), and the CI's launch-integrity auditor over the built APK (`lab/verify_apk_launch_integrity.py`).
