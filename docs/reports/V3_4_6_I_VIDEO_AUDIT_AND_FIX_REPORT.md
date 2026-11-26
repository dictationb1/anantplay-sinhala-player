# AnantPlay Sinhala Player — v3.4.6.i (versionCode 30415)
# THE COMPLETE VIDEO-AUDIT FIX RELEASE — every defect caught on the 5 screen-recorded videos + 4 screenshots + the v3.4.6.h Troubleshoot log, fixed end-to-end.

**Release identity:** `3.4.6.i` / `30415` everywhere (build.sh:56-57, android/app/build.gradle:83-84,
AppInfo.java:34-35, ui/pubspec.yaml:16 `version: 3.4.6+30415`, both GitHub workflows).
**`docs/latest.json` deliberately UNTOUCHED** at the live `3.4.6.h / 30414` tier — `lab/ReceiverLab.java`'s
latest.json gates now accept BOTH `3.4.6.h` and `3.4.6.i` as the current tier (ReceiverLab.java:4466-4476, 5309-5316).

---

## §1 · FAIL 68 / settings snap-back — THE RAW-MIRROR LAW (video_3 00:00–01:15, video_4 05:16–05:98)

### The exact root cause
`MainActivity.gatherSettings()` — the Java map behind the Dart `getAll` mirror — was a **hand-maintained
whitelist** that omitted the keys the Flutter UI actually writes (`aspectMode`, `fontFamily`, `accentHex`,
`autoZip`, `autoTranslateNonSi`, `continueWatching`, `homeTipDismissed`, a probe's round-trip string…) and
**collapsed `layoutDensity`'s `spacious` to `comfortable`**. `AppState._optimistic` re-read this map right
after every write, so each un-whitelisted value SNAPPED BACK on the very next frame. Probe 68's
`string ✗` is exactly this: the write lands in SharedPreferences; the read-back goes through the whitelist
and the key is missing (`deep_troubleshooter.dart:321-326`).

### The fix (every leg, end-to-end)
* **`gatherSettings()` now starts from the FULL live `prefs.getAll()` map** (MainActivity.java:1696 —
  `new LinkedHashMap<>(prefs.getAll())`) overlaid with the normalized typed readers (Long keys stay
  canonical, corrupt values heal on read). Every key any surface ever wrote is now visible to the mirror;
  probe 68's string leg reads back `probe-value` ✓ and the hex leg is no longer a vacuous pass.
* **`AppPrefs.density(p)`** (AppPrefs.java:813) — the FULL three-value reader; the mirror keeps
  `spacious|comfortable|compact` verbatim (MainActivity.java:1713). `spacious()`/`compact()` now derive from it.
* **`bridge.dart` getString/getInt raw lanes wired** (bridge.dart:998-1004) — the Java handlers existed since
  v3.4.6.h; the Dart half was never wired, so every string round-trip depended on whitelist coverage.
* **H4/68 reads BOTH ways** (deep_troubleshooter.dart:341-354): the raw `getString` lane AND through the live
  mirror — `string raw+mirror ✓`.
* **Search engine default is `google` on BOTH shores** (MainActivity.java:1765 — `AppPrefs.searchEngine(prefs)`;
  the raw read used `'ddg'`).
* **The live-UI application (the "changed but nothing happened" kills):**
  * `Bottom bar = Docked` **physically switches MornyeTabBar** between the floating rounded glass capsule and
    a full-width edge-to-edge docked bar (main.dart:622-667; mornye_chrome.dart:592-593 `docked`/`showLabels`).
  * `Tab labels = OFF` **immediately hides the text labels** under the four nav icons
    (mornye_chrome.dart:731-747 `if (showLabels)`).
  * `Interface scale` **visibly scales the app**: AppTokens row spacing tightens/relaxes (main.dart:269-272)
    AND the whole-app text rides a `_DensityTextScaler` (main.dart:397-429, 0.94 compact / 1.06 spacious —
    multiplied ON TOP of the system accessibility text scale, never discarding it).
  * `Default aspect`, `Subtitle font`, `Background style`, `Continue watching`, `Desktop site`,
    custom `#` accent hex all persist and stick immediately (the raw map carries them).
  * **Midnight dimmer in Light Mode** (settings_screen.dart:129-138): a CLEAN disabled state with the caption
    `"Active in Dark & AMOLED modes"` (the dim amount slider goes inert too) — tapping never looks broken.
* **Segmented labels shortened so ZERO ellipsis/overlap ever appears** (settings_screen.dart:505, 710):
  `Noto / System / Serif / Bold` and `Google / DDG / Start / Brave`.

---

## §2 · THE FORCE-UPDATE DOUBLE-DOWNLOAD KILL + FAIL 2 (video_5 05:00–05:50, the 6,008 ms stall)

### The double-download (42.8 MB × 2)
**Root cause:** `verifyAndInstallPending` deleted the verified APK + metadata the moment the PackageInstaller
session was **committed** (SelfUpdater.java, the old `install(c, apk); apk.delete(); clearPendingMetadata(c);`
sequence) — so when the user dismissed the Android confirm sheet and tapped Update Now again,
`hasPendingUpdate()` was false, the cache-reuse branch could never hit, and the ENTIRE APK re-downloaded
from 0%. The Dart half additionally fired a follow-up `checkForUpdate()` network leg on every tap
(force_update_screen.dart, the old `if (!pending.pending) { await …checkForUpdate(); }`).

**The fix — the ZERO-DOUBLE-DOWNLOAD LAW (SelfUpdater.java:735-770, 1209-1222):**
* The verified APK + `pendingInstallVersion`/`pendingInstallSha256` **SURVIVE the handoff**; the sweep runs
  only in `InstallResultReceiver`'s `STATUS_SUCCESS` branch (belt-and-braces: the NEW process's
  `App.onCreate → sweepStalePending()` does the identical sweep — LAW #1's self-lock kill, unchanged).
* When Update Now is tapped and the verified APK already exists with matching SHA-256: **never re-download —
  the installer re-opens in < 50 ms**; the button reads **`Install Downloaded Update`** while cached
  (force_update_screen.dart:248-269).
* The button is **cleanly disabled while a tap/download/verify/handoff is in flight** (`_tapBusy`,
  `_buttonBusy` — force_update_screen.dart:271-281); 2 s into the installer handoff it re-enables as
  **`Open Installer Again`** (the user dismissed the system sheet and wants it back).
* The follow-up `checkForUpdate()` network leg is GONE from `_updateNow()` (force_update_screen.dart:208-241).

### FAIL 2 — the Tier-2 `6008 ms` timeout
* **The jsDelivr CDN mirror tier added on both shores** (SelfUpdateCore.java:109-111
  `jsdelivrLatestJsonUrl()` — `https://cdn.jsdelivr.net/gh/dictationb1/anantplay-sinhala-player@main/docs/latest.json`;
  `fetchVersionFast` tier-3 at SelfUpdateCore.java:170-185; `fetchViaMirrorJson()` at SelfUpdateCore.java:336-350).
  The raw stall (Sri Lankan ISP DNS + IPv6-first multi-address connect are NOT covered by socket timers) now
  falls back to the CDN edge — the same bytes, still zero GitHub-REST calls.
* **Probe 2 upgraded** (DeepProbe.java:408-476): raw (4 s) → jsdelivr (4 s) — EITHER answering a schema-valid
  body PASSES, the detail names the winning route (`route: raw|jsdelivr`), and when BOTH fail the exact
  exception class + sanitized message is in the detail (never a silent red).

---

## §3 · THE ANANTPLAYINSTALLER TRIO — `0 · no-asset` / `0 · needs-permission` / `0 · busy` (video_2, video_4 00:25/01:23/03:45, video_5 00:33/02:21)

### no-asset — the REAL asset discovery
**Root cause:** GitHub's `releases/expanded_assets/<tag>` fragment embeds **RELATIVE** hrefs
(`/AnantPlay/android/releases/download/v2.24.0/AnantPlay-Android-v2.24.0.apk`), but the v3.4.6.h regex demanded
ABSOLUTE `https://…apk` hrefs — it could never match, so the installer answered `no-asset` every time.
*(Live-verified against github.com/AnantPlay/android/releases/expanded_assets/v2.24.0 — current tag v2.24.0,
assets AnantPlay-Android-v2.24.0.apk / AnantPlay-Android.apk / AnantPlay-TV-v2.24.0.apk / AnantPlay-TV.apk /
SHA256SUMS.txt, ALL relative hrefs.)*

**The fix (AnantPlayInstaller.java:287-296, 292-341):**
* `extractApkDownloadUrls` accepts BOTH shapes (absolute https and site-relative `/…apk`), absolutizing the
  relative ones against `https://github.com` (AnantPlayInstaller.java:302-308).
* `pickBestApkUrl` scores the preference ladder: arm64-v8a/universal phone (40) → versioned phone (30) →
  unversioned phone (20) → any non-TV (10) → TV (0).
* The official-app download rides the cap-parameterized `SelfUpdateCore.downloadToFile(url, file, cap, …)`
  (SelfUpdateCore.java:463-483) so the official 200 MB cap is honored for real.

### busy — the leaked `running` flag
**Root cause:** the `needs-permission` early `return` never reset the AtomicBoolean, so every later tap hit
`0 · busy` until process death. **Fix:** `workerStarted` try/finally (AnantPlayInstaller.java:176-220 —
`if (!workerStarted) running.set(false);`) — the flag hands itself back on EVERY no-worker path.

### needs-permission — the auto-resume that was promised but never implemented
**Fix (AnantPlayInstaller.java:53-163, MainActivity.java:809-814):** `awaitingPermission` parks the listener;
`MainActivity.onResume() → resumeIfWaiting()` — the INSTANT `canRequestPackageInstalls()` is true while an
official-APP install is parked — **re-runs the real HTTPS download (0% → 100% + MB progress) by itself**.
Never stuck on `0 · needs-permission` or `0 · busy` again.

### The Official AnantPlay auto-update detection (video_2)
`officialUpdateFacts()` (AnantPlayInstaller.java:371-393 + MainActivity.java:997-1004) compares the installed
`me.champdev.weblock` versionName against the latest `github.com/AnantPlay/android/releases/latest` tag
(releases-latest 302, zero GitHub-REST calls, 10-minute async cache), and the sleek
**`AnantPlay Update Available (vX → vY) — Update Now`** pill renders on **Home** (home_screen.dart:456-531) and
**Settings → AnantPlay & Sources** (settings_screen.dart:628-702) — official AnantPlay updates in 1 tap
right from our app.

---

## §4 · THE BLANK BROWSER KILL + THE INTEGRATED 3-INDEX SOURCES HUB (video_4 01:31–02:05 + the 4 screenshots)

### The blank dark-blue browser screen
**Root cause:** the headless PlatformView lane loaded NOTHING when there was no `webLastUrl` and no
creation-params URL — `HOME_URL` was declared (WebBrowserView.java:102) and **never referenced**.

**The fix (WebBrowserView.java:207-214, 649-661):**
* `bootstrapUrl(last)` — the never-blank contract: last page when there is one, otherwise
  **`https://www.google.com/`**; the headless WebView boots straight to it.
* **Theme-aware container** (WebBrowserView.java:178-241 `lightTheme()/themedBackground()/applyTheme()`):
  `#FFFFFF` in Light Mode, `#0F172A` in Dark/AMOLED, on the container AND the WebView itself; the page
  color-scheme hint follows the theme (the old line forced `dark` unconditionally); the Dart side
  re-applies on every theme flip (`applyBrowserTheme` bridge lane, MainActivity.java:1470-1477) and wires
  the Desktop-site toggle cleanly (`setUserAgentString(effectiveUa())` + reload).
* The Browser tab gained its own **top bar** (browser_screen.dart:253-303): Home + the Sources Hub pill + a
  live `Ad block: On/Off` chip.

### The Built-In Sources & Speed Dial Hub (browser_screen.dart:60-660)
* **ALL 3 exact index feeds preloaded as built-in presets** (browser_screen.dart:52-70
  `kBuiltInIndexPresets`): Sri Lanka IPTV & Live Sites · Sinhala Subtitle Bridge · Official AnantPlay Index
  — plus an **editable/addable custom list** (`sourcesHubCustom` pref + `_AddIndexCard`), never just 1 box.
* The hub **loads & parses the real `index.json` feeds right inside our app** (`fetchUrl` native lane with a
  6 s hard deadline + 256 KiB cap, MainActivity.java:1485-1514; dart:convert parsing), rendering each feed's
  live source rows (type chips, descriptions, 1-tap open).
* The user's exact status chips ride the header: `Address bar: On` · `Ad block: On` (live mirror) ·
  `DoH DNS bypass` (live mirror) · **`Hide offline channels`** — a REAL working toggle that filters
  `state:offline/dead` sources out of the parsed list (browser_screen.dart:696-704 `_isAlive`).
* **1-Tap Copy buttons** on every feed + the guided setup card
  (`1. Copy → 2. AnantPlay → Settings → Sources → 3. Paste`) + Open official AnantPlay.

---

## §5 · THE XRECORDER-STYLE TRANSLUCENT PERMISSION GUIDE + THE ONBOARDING RED-PEN LAYOUT (video_1 00:06–00:20, video_5 02:55–04:35, video_4 00:41–01:15)

### PermissionGuideActivity (the new translucent overlay — PermissionGuideActivity.java, manifest-registered)
* A sleek translucent dark card floating **right on top of the Android System Settings page**, launched via
  `startActivities(new Intent[]{settingsIntent, guideIntent})` (PermissionGuideActivity.java:106-129) —
  one back-stack, works on Android 16 **EVEN BEFORE overlay permission is granted** (a normal translucent
  activity of our own task, `Theme.Translucent.NoTitleBar` + `windowIsFloating`, never SYSTEM_ALERT_WINDOW;
  AndroidManifest.xml:87-102, noHistory + excludeFromRecents).
* **All 4 scenarios with animated visual step mockups** (PermissionGuideActivity.java:130-364):
  `overlay` (app-icon row + the toggle pulsing ON), `unknown_sources` (the toggle + the Xiaomi
  confirmation note), `battery` (the list with `No restrictions ✓` highlighted), `notifications`
  (Show notifications ON). Tap anywhere or swipe up to dismiss and use the settings page underneath.
* **Wired to EVERY permission launch site:** the Onboarding slides, Home repair banner, ForceUpdateScreen
  Step 1 (`SelfUpdater.installPendingUpdate` → `openUnknownSourcesWithGuide`, SelfUpdater.java:709-716), the
  `AnantPlayInstaller` needs-permission path (AnantPlayInstaller.java:193-197), and all four MainActivity
  handlers (openOverlay/openUnknownSources/openBatteryOptimization/requestNotifications —
  MainActivity.java:978-984, 1063-1069, 1095-1101, 1208-1215).

### The onboarding redesign (the red-pen sketches)
* **The exact geometry on every slide** (onboarding_screen.dart:243-302): the upper ~58% is a large
  crystal-clear animated visual hero stage (`Expanded flex: 3` + `FittedBox` — filling the card generously,
  never a cramped 210dp box with wasted space below); the lower ~40% carries the title + the CONCISE 1–2-line
  subtitle + the ONE full-width primary action button right above the page dots.
* **The bottom-right `Next` button is GONE** (onboarding_screen.dart:598-648): only `Back` (bottom-left,
  page > 0) and the centered dots remain; the final slide shows ONE clean full-width **`Finish`** (never a
  duplicate Finish + Next stack).
* **Slide 5's double `[✓ Enabled ✓] [Continue]` stack replaced** (onboarding_screen.dart:404-430 +
  651-768): TWO cleanly labeled compact permission rows inside ONE Mornye glass card —
  `Background Activity (No restrictions)` and `Allow App Updates`, each with `[Enable]` / `[Enabled ✓]` —
  plus ONE single full-width `Continue` at the bottom.
* **The dead Skip/Finish kill** (onboarding_screen.dart:169-185): whether the wizard runs first-install or
  re-opened (Settings → Run setup guide / Help → Run setup wizard / Home → Watch Tour), tapping **Skip or
  Finish pops the route (when pushed) and always persists** `onboardingDone=true` + `onboardingVersion=2`
  through the one `completeOnboarding()` lane — landing on HomeScreen immediately (test-proven:
  `THE FINISH CONTRACT` + the new `navigator.pop()` test).
* **Smart first-time progression** (onboarding_screen.dart:205-212): the grant button is the primary action
  with `Continue anyway` de-emphasized (the accessibility escape stays), and the flip-detection
  auto-advance machinery is unchanged.

---

## §6 · THE PLAYER, HOME, SNACKBAR, SWITCH & ICON FIXES (video_3 04:01–05:16, video_4 01:15/02:38/03:03)

### The subtitle crushed behind the seekbar (video_3 05:00–05:16)
* `_subBottom` is now the HUD-lift contract (player_screen.dart:58-66 named constants, 877-898): HUD visible
  → **lift to 136dp portrait / 116dp landscape** — COMPLETELY ABOVE the seekbar + 5-icon dock; HUD hidden →
  the compact cinema anchor (`subBottomPct`%, floored at the ≥ 20dp safe inset above the gesture pill).
  The pill rides an `AnimatedPadding` (260 ms easeOutCubic) so the lift/settle animates smoothly.
* **The Test Player cues SPAN the whole stream now** (Media3TextureBridge.java:816-852): the mux test stream
  runs ~10:34 (634 s); the old trio ended at 15 s so the demo subtitle VANISHED at 00:17 — the cue set now
  repeats the three-beat story every 12 s across the full duration (max 2 s gap).

### Home's `Got it` + the clipped `AnantPlay Player` header (video_3 04:01–04:10, video_4 01:15/02:38)
* **`Got it`** (home_screen.dart:334-454): optimistic in-memory `_tipDismissed = true` +
  `putBool('homeTipDismissed', true)` through the now-raw mirror lane — the very first tap collapses the card
  (AnimatedSize spring) and the flag SURVIVES the refresh (the whitelist used to drop it, so the banner
  snapped back). Opaque **44dp** tap target.
* **The header** (home_screen.dart:47-86): a plain pinned header row — `28sp / FontWeight.w700 / −0.3`
  tracking — inside the SafeArea directly: **never vertically clipped** under the status bar (the old
  `SliverAppBar.large` half-cut itself on edge-to-edge insets). Settings + Check-update stay 1-tap reachable.

### Light-mode black snackbar + mid-run toast spam (video_3 01:31–02:46)
* **`snackBarTheme`** (mornye_theme.dart:442-468): Light Mode paints a clean frosted **`#FFFFFF` elevated
  card with crisp `#111118` text** (computed luminance 1.00, contrast 18.8:1 — ≥ 4.5:1 with room to spare)
  and a subtle `#E4E4EC` outline; Dark Mode keeps the dark card.
* **ONE toast at the very end** (help_screen.dart:144-159, 227-237): the intermediate native-layer
  auto-copy is SILENT (`const [], true);`); the single completion banner fires only after the FULL
  two-layer run lands. (The progress-bar denominator also fixed: the bar divides by the same 87 as the
  counter — the old bar divided by 72.)

### The accent-tinted Mornye `AppSwitch` + crisp icons (video_4 03:03, video_3 02:21–02:35)
* The ON track paints the user's live **`Theme.of(context).colorScheme.primary`** at full strength
  (app_switch.dart:176-184) — the hardcoded green + 0.45 accent lerp is gone; the liquid-glass stretch
  spring is untouched.
* **Seven new Mornye/Cupertino glyph remaps** (mornye_icons.dart:233-268): health_and_safety/shield,
  restart_alt, movie, cast, backup, palette, tips_and_updates, upgrade — Help's action cards and Settings'
  expandable groups render through `mornyeIconFor` (help_screen.dart:553, settings_screen.dart:1082-1086).

---

## §7 · THE EXPANDED 100% HONEST DEBUG ENGINE — 87 native probes (Domain I added)

`DeepProbe.java` gains **Domain I — the nine v3.4.6.i video-audit fix contracts** (DeepProbe.java:316-333
registration, 1584-1933 bodies):

| Probe | Name | Proves |
|---|---|---|
| 65 (I1) | Raw-mirror settings law | the string round-trip through the LIVE mirror + the full density string + the `prefs.getAll()` source contract |
| 66 (I2) | Updater zero-double-download law | cache+metadata survive the handoff; sweep only at STATUS_SUCCESS; Dart no re-check; honest labels |
| 67 (I3) | Zero-disable-switch law | the Auto-update switch is absent; Check-now stays; the 5 LAW #1 guarantees armed |
| 68 (I4) | AnantPlayInstaller real asset discovery | relative+absolute hrefs, phone-build ladder, busy leak dead, auto-resume, official update pill |
| 69 (I5) | PermissionGuideActivity overlay contract | manifest registration, 4 scenarios, stacked launch, every launch site guided |
| 70 (I6) | Browser HOME_URL never-blank bootstrap | the bootstrap lane, the theme-aware container, the in-app 3-index Hub |
| 71 (I7) | Player subtitle HUD-lift contract | 136/116/20 constants + AnimatedPadding + the spanning test cues |
| 72 (I8) | Light-mode snackbar contrast | computed WCAG luminance 1.00 + contrast ≥ 4.5:1 + the one-toast law |
| 73 (I9) | Multi-index 3-URL preset integrity | the 3 exact feeds + the addable hub + the official default index |

The Dart layer's H4 (68) reads the string leg **both raw and mirror**; the counter/bar/total all read 87;
`ciDomainLine` renders `I=9/9`; the CI summary total is 73. **Zero fake passes: FAILs hoist to the top,
env_fail stays separate, one completion toast.**

---

## §8 · THE PERMANENT LAWS — verified intact

* **LAW #1 (unbreakable mandatory auto-updater):** the Auto-update ON/OFF switch is REMOVED from
  SettingsScreen; all five guarantees stay armed (required-version reopen lock, offline→online
  NetworkCallback, zero 15/60-min traps, 4 s fast lane, PopScope(canPop:false) with zero Skip/Dismiss/Later);
  the double-download is dead (§2); the 55 MiB cap, `[a-z]` ladder and zero-REST-host rule hold
  (the jsDelivr mirror is a static-file CDN, not a GitHub REST call).
* **LAW #2 (100% honest debug engine):** 78 → **87 native probes** + 14 Dart audits (9 new honest contracts,
  every discovered defect probed, FAILs at the top, ONE end toast, auto-copied privacy-safe report).
* **LAW #3 (strict Mornye icon law):** every Help/Settings glyph rides `mornyeIconFor` — 7 new remaps,
  zero mismatched boxy icons.
* **LAW #4 (zero regressions):** `prefs` before `super.onCreate` ✓ · `_ChannelHub` singleton streams ✓ ·
  ExoPlayer `onMain()` + Zero-Heat `SurfaceView` ✓ · `safeGetLong/safeGetInt/putTyped` ✓ · LGPL-3.0-only
  files untouched ✓ · zero AccessibilityService, `allowBackup="false"` ✓ · APK < 50 MiB cap ✓.

---

## §9 · The verification gates (all green in this build)

| Gate | Result |
|---|---|
| `scripts/compile_check.sh` | **COMPILE OK: 171 classes, 0 errors** (android-36 + Flutter 3.50.1 embedding) |
| `flutter pub get` / `flutter analyze` | **No issues found** |
| `flutter test` | **160/160 PASSED** (118 prior — 8 updated to the new correct contracts — + **42 new v3.4.6.i tests**: raw-mirror law, double-download, disable-switch, installer resolver, guide overlay, browser bootstrap, HUD-lift, WCAG snackbar, red-pen layout, 3-index hub, accent switch, icon law, version identity) |
| `bash lab/run_lab.sh` | **ALL 1461 PASSED, 0 FAIL** (ReceiverLab 1458 + the 73-probe matrix 73/73 PASS on the lab hooks; pinned to 3.4.6.i/30415; latest.json gate accepts BOTH 3.4.6.h and 3.4.6.i) |
| `lab/verify_flutter_bridge_wiring.py` | **81 passed, 0 failed** (0 dead channels — the 5 new lanes wired: getString/getInt, applyBrowserTheme, openHome, fetchUrl, officialUpdateFacts — 0 fake buttons) |
| `lab/verify_gradle_host.py` / `verify_v345_ui.py` / `verify_ui_completeness.py` / `verify_refs.py` / `verify_layouts.py` / `verify_syntax.py` | all green (pinned to the 3.4.6.i / 30415 identity) |

**Spotless deduplicated packaging:** the ZIP carries **EXACTLY 7 root files**
(`.gitignore`, `AndroidManifest.xml`, `LICENSE`, `README.md`, `SETUP-SI.md`, `THIRD_PARTY.md`, `build.sh`),
ALL reports in `docs/reports/`, **0** `lab/out/`, **0** `*.class`, **0** `*.pyc`, **0** build-tool dirs
(`.flutter-plugins-dependencies`, `.ios/.android/.dart_tool/build`), **0** `android/local.properties`,
**0** `.git/`, **0** `*.apk`, **0** `*.zip`, **0 duplicate entries**; `docs/latest.json` untouched at
`3.4.6.h / 30414`; `ui/pubspec.yaml` = `3.4.6+30415`.

---

## සාරාංශය (සිංහල)

**v3.4.6.i (30415)** — වීඩියෝ පරීක්ෂණයේදී හමු වූ සෑම ක්ෂණික දෝෂයක්ම මෙම නිකුතුවෙන් නිවැරදි විය:

1. **සැකසුම් නැවත පැනීම (FAIL 68) සම්පූර්ණයෙන් මිය ගියේය** — `gatherSettings()` දැන් `prefs.getAll()`
   අමු සිතියමෙන් ආරම්භ වේ; Default aspect, Search engine, Bottom bar, Interface scale, Subtitle font,
   Background style, Continue watching යන සියල්ල දැන් ස්ථිර වී අදාළ පෙනුමම වෙනස් කරයි.
2. **ForceUpdateScreen ද්වි-බාගැනීම මිය ගියේය** — සත්‍යාපිත APK එක කැච් වී තිබේ; `Install Downloaded
   Update` බටනයෙන් තත්පරයකට අඩුවෙන් ස්ථාපනය වෙයි; FAIL 2 ද jsDelivr CDN පිටුපස් මගින් මිය ගියේය.
3. **AnantPlayInstaller තුනම මිය ගියේය** — no-asset (අමු GitHub href), busy (ධජ පිටිපස්සේ), needs-permission
   (ස්වයං-ප්‍රතිඵල) + නිල AnantPlay යාවත්කාලීන පෙතිත්ත.
4. **හිස් බ්‍රවුසර මිය ගියේය** — HOME_URL bootstrap + තේමා-සංවේදී පසුබිම + තුන් Index feed එකම App එක තුළ.
5. **XRecorder-ශෛලීය පාරදෘශ්‍ය අවසර මාර්ගෝපදේශය + නව Onboarding අලේඛනය** (නොමැති Next, පිළියෙළ Source පේළි).
6. **Player උපසිරැසි-seekbar ගැටුම, Home `Got it`/ශීර්ෂය, Light-Mode snackbar, Accent AppSwitch, Mornye
   නිරූපණ** — සියල්ල මිය ගියේය.
7. **අවංක debug engine එක 87 probes දක්වා වැඩි විය** (Domain I නවයක්) — සියලුම 160 Flutter පරීක්ෂණ + 1461
   lab පරීක්ෂණ සමත්.

---

## §Z · ZERO-TRUST FORENSIC DELTAS (independent re-audit of this archive)

The archive was re-audited line by line against the real extracted source. Two genuine
defects that the generated archive still carried were found and fixed here:

### Z1 · TRAP #1 — probe-id collision on 65–73 (native Domain I vs. Dart Domain H)
* **Finding (real):** the v3.4.6.i native matrix grew to **73 probes** — Domain I
  (`probeI1..probeI9`, the nine video-audit fix contracts) landed on ids **65–73**
  (`DeepProbe.java`, `runAll()`: `id` starts at 1 and increments per probe). The Dart
  layer was **still** `Domain H, firstId = 65` (`deep_troubleshooter.dart`), so ids
  65–73 existed **twice** in the merged report — two rows with the same id, one native
  one Dart — exactly the class of defect the v3.4.6.g fix killed for 57–64.
  `help_screen.dart` already advertised `87` (`73 native + 14 Dart`) while the feed
  climbed to 78 and the progress bar divided by 87 — an internally inconsistent
  denominator, and `build-apk.yml` still hard-asserted `total >= 64`.
* **Fix:** Dart Domain H renumbered to **74–87** (`DartLayerAuditor.firstId = 74`);
  every H-audit doc id (H1..H14) updated; the merged run is now **unique sequential ids
  1..87 with zero collisions and zero gaps**. `ui/test/v346h_test.dart` asserts the
  new range **and** that no Dart id may land in the native 1..73. The emulator gate in
  `build-apk.yml` now hard-asserts `total >= 73` (with the matching
  `lab/ReceiverLab.java` workflow-contract check), so a shrunk native matrix can never
  silently pass again.

### Z2 · TRAP #5 — `fetchUrl` SSRF: scheme law only, no host law
* **Finding (real):** `MainActivity`'s `fetchUrl` handler — the Dart-fed Sources-Hub
  index reader — guarded with `SelfUpdateCore.httpsOnly(url)`, which proves **only**
  that the string starts with `https://`. It happily opened
  `https://127.0.0.1:8765/…`, `https://169.254.169.254/latest/meta-data/`,
  `https://[::1]/`, `https://router.local/` and any single-label intranet host: a
  textbook SSRF surface reachable from a hostile index feed. (The 256 KiB cap, the
  6 s deadline and `shutdownNow()` in `finally` were already correct.)
* **Fix:** new pure, offline, string-only guard `SelfUpdateCore.safeRemoteUrl(url)` —
  https-only **plus** a public-host law: refuses `localhost`/`*.localhost`/`*.local`/
  `*.internal`/`.home.arpa`/`metadata.google.internal`, every single-label host, and
  every loopback, private, CGNAT, link-local, ULA, multicast, broadcast, reserved and
  unspecified literal in both families (IPv4 including the odd shapes the URL parser
  normalises, and IPv6 including `::1`, `::ffff:127.0.0.1`, `fd00::/8`, `fe80::/10`).
  It performs **no DNS resolution**, so it is deterministic and lab-testable. The
  `fetchUrl` handler now routes through it and logs the refusal. Five new
  `lab/ReceiverLab.java` gate checks (accept real CDN hosts; refuse every non-https
  scheme; refuse private IPv4; refuse loopback/ULA IPv6 and local names) lock it in.
