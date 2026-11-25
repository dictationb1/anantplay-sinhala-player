# AnantPlay Sinhala Player v3.4.6.f — The Honest Updater & Dead-Button Fix Report

**versionName `3.4.6.f` · versionCode `30412` · tag `v3.4.6.f` · APK `AnantPlay-Sinhala-Player-v3.4.6.f.apk` · Android 16 (SDK 36) · Flutter 3.50.1 · Gradle 9.5.0 / AGP 9.3.1**

This release exists to kill four defects the v3.4.6.e real-phone field report proved with screenshots and a captured crash, and to rebuild the deep troubleshooter so it can never lie again:

1. **The `v3.4.6.e → v3.4.6.e` self-lock** — a user ALREADY on v3.4.6.e was locked out of the app by a "Force Update" gate pointing at the version they were already running, while the app silently downloaded the APK 2–3 times in the background and the timeline recorded **7 updater checks in 12 seconds**.
2. **The `PlatformException(error, No active stream to cancel, null, null)` crash** — captured by `Last Crash`, forwarded by our own Dart crash reporter at `MainActivity.onCrashlogCall` (the v3.4.6.e CrashLog line 1456 shape).
3. **Real dead buttons** — Home/Settings taps fired but nothing visibly changed ("everything feels like fake buttons"); `Stop Receiver` logged `84.835 receiver stop requested (user)` yet the Status Summary still said `receiver: up (port 8765)`.
4. **The lying troubleshooter** — 54/54 PASS while the Worker was `HTTP 0` (broken subtitles) and a real crash sat in `Last Crash`; Probe 2 stalled **32,891 ms**.

---

## 1. Root cause & fix — the `v3.4.6.e → v3.4.6.e` self-lock + multi-download (LAW #1 + LAW #2)

### 1.1 The self-lock: stale pending-APK metadata re-armed the gate with NO `isNewer()` check

**Root cause chain (v3.4.6.e):**

| # | Location (v3.4.6.e) | Defect |
|---|---|---|
| 1 | `MainActivity.configureFlutterEngine` (pending re-arm block) | `hasPendingUpdate()` true → pushed `gate:available` with `current = AppInfo.VERSION_NAME` and `next = pendingVersion` — **without one `UpdateChecker.isNewer()` test**. A completed/abandoned install left `pendingInstallVersion = 3.4.6.e` in the `selfupdate` prefs; the user upgraded to 3.4.6.e; the gate re-armed `3.4.6.e → 3.4.6.e`. This is the exact screenshot. |
| 2 | `MainActivity.replayGate()` | The zero-race replay re-armed from the same stale cache, same missing guard. |
| 3 | `SelfUpdater.pendingFile()` | Metadata validation checked the version *shape*, the hash *shape*, and the file *name* — **never that the pending version is strictly newer than the installed one**. Every consumer of `pendingFile()` inherited the hole. |
| 4 | `SelfUpdater.checkOnLaunch/checkOnResume/checkAndMaybeInstall` | All three treated `pendingFile() != null` as "Update ready" and re-pushed the gate. |
| 5 | Dart `ShellRootState` (`ui/lib/main.dart`) + `ForceUpdateScreen` (`ui/lib/anantplay/force_update_screen.dart`) | `_gateSub` flipped `_forceUpdateLock = true` on ANY `gate:available` with `e.next.isNotEmpty` — no version comparison in Dart either. `_bootstrapPending()` armed the screen from `pendingUpdate()` alone. |
| 6 | Dart `main.dart` | The badge fallback was a **hardcoded literal `'3.4.6.e'`** (itself a leftover from the `.d`→`.e` cycle), so the gate could even show a version the package never ran. |

**The v3.4.6.f fix — the same-version lock is now impossible at every layer:**

- `SelfUpdater.sweepStalePending(Context)` (**`SelfUpdater.java:347`**) — called from `App.onCreate()` (**`App.java:166`**, before `schedulePeriodic`) and at the top of every check entry (`:435`, `:494`, `:756`). A pending version that is not STRICTLY newer than the installed build is deleted from prefs **and** disk.
- `SelfUpdater.pendingFile()` (**`SelfUpdater.java:295`**) — the gate itself: `if (!UpdateChecker.isNewer(version, AppInfo.VERSION_NAME)) { clearPendingMetadata(c); sweepUpdates(c); return null; }`. Every consumer (`hasPendingUpdate`, `pendingVersion`, `installPendingUpdate`, every re-arm) is structurally healed.
- `SelfUpdater.gateAvailable(current, next)` (**`SelfUpdater.java:150`**) — the only availability emitter; a non-newer pair is suppressed and pushes `uptodate` instead.
- `MainActivity` pending re-arm (**`MainActivity.java:635`**) — guarded by `UpdateChecker.isNewer(pendingVer, AppInfo.VERSION_NAME)`.
- `MainActivity.pushGate()` (**`MainActivity.java:1124`**) — defense in depth: an `available` blob whose `next` is not strictly newer than `current` is **rewritten to `uptodate`** inside the pump, whatever produced it.
- `MainActivity.replayGate()` (**`MainActivity.java:1139`**) — the replay path (the exact cold-boot handoff that produced the screenshot) re-arms only a strictly-newer cached blob.
- Dart LAW #1 mirror — `isNewerVersion()` (**`ui/lib/anantplay/bridge.dart:160`**, the Dart twin of Java's 4-segment a–z comparator) gates the lock in `ShellRootState` (**`ui/lib/main.dart:220`**: `if (!isNewerVersion(e.next, installed))` → lock cannot flip) and the screen render in `ForceUpdateScreen` (**`ui/lib/anantplay/force_update_screen.dart:117`** same guard) and `_bootstrapPending()` (same-version pending renders nothing).
- The installed identity now arrives from the host: the `appInfo` method (**`MainActivity.java:1479`** / `bridge.dart:606`) — the hardcoded `'3.4.6.e'` literal in `main.dart` is gone (replaced with the gate frame or the native reply, `main.dart:181`).

### 1.2 The 7-checks-in-12-seconds storm (LAW #2)

**Root cause (v3.4.6.e):** the launch/resume/periodic/`checkNow` legs each re-resolved independently; `checkNow` (manual) never wrote `lastCheck`, and the Dart-side "Check for update" buttons (Home gear, Settings, Help) each fired a full network resolve — 7 resolves landed in the timeline within 12 s.

**Fix:** `SelfUpdater.PROCESS_DEDUPE_MS = 60_000` (**`SelfUpdater.java:85`**) + `processDedupeAllows()` — a 60-second **in-process dedupe** on every entry point (launch `:438`, resume `:509`, `checkNow` `:543`) layered on the persisted cooldowns; a strict single-flight `AtomicBoolean checkInFlight` (**`SelfUpdater.java:93`**) wraps the whole check; the strict download-only leg is the only bypass.

### 1.3 The background multi-download (LAW #2, continued)

**Root cause (v3.4.6.e):** `checkAndMaybeInstall` read `K_AUTO_UPDATE` (default **true**) and, when a newer release existed and the APK's first download failed verification (or install permission was missing), every subsequent check re-downloaded — the 2–3 silent background downloads the timeline showed, ending in the failed `Install unknown apps` hand-off with the permission never requested first.

**Fix:** the automatic legs **download nothing, ever**. `checkAndMaybeInstall` arms the gate (`gateAvailable`) and returns `"available <version>"` with the log line *"gate armed, WAITING for the user's Update Now tap (zero background download)"* (**`SelfUpdater.java:809`**). The `if (!autoUpdate && !forceDownload)` block is deleted — `K_AUTO_UPDATE` now only mutes the 4-hour periodic job (`schedulePeriodic`, `SelfUpdater.java:709`), never the check and never the gate. The download lane exists solely behind the user's `Update Now` tap.

### 1.4 LAW #3 — permission first, download exactly once, verify, install

- **Step 1 (permission guarantee):** `ForceUpdateScreen._updateNow` (**`force_update_screen.dart:172–195`**) checks `canRequestPackageInstalls()` FIRST; when ungranted it shows the inline *"Step 1 of 2: allow AnantPlay Player to install updates"* card and calls the native lane. `SelfUpdater.openPermissionAndAwait()` (**`SelfUpdater.java:847`**) opens `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` and starts `watchInstallPermission()` (**`SelfUpdater.java:605`**) — a bounded 30-second, 500 ms-poll watcher that auto-resumes the install the instant the grant lands; `onResume → resumePendingInstall` remains the second lane.
- **Step 2 (single-flight download):** `downloadVerifyInstall()` (**`SelfUpdater.java:870`**) — strict `AtomicBoolean downloadInFlight` (`:872`); a cached APK whose SHA-256 already matches is reused without re-downloading (`:895`); otherwise ONE streamed download with live 0→100% + MB progress into the gate.
- **Step 3 (verify + install):** strict SHA-256 against `SHA256SUMS.txt` (tampered APK deleted + Retry), then the PackageInstaller 1-tap sheet.
- **Onboarding half (LAW #3):** slide 5 "Keep Cast ready" now carries the **Allow App Updates** card (`onboarding_screen.dart:354–377`) — `openUnknownSources()` + the auto-advance spring on the installs grant — so brand-new and upgrading users guarantee update-install permission on day one.

### 1.5 The 32-second hang (probe 2)

`SelfUpdateCore` check timeouts are now **5 s connect / 8 s read** (`CHECK_CONNECT_MS/CHECK_READ_MS`, **`SelfUpdateCore.java:111–112`**) in `resolveLatestTagByRedirect` and `httpGet`; the troubleshooter's probes use the 5 s/6 s overloads (`resolveLatestTagByRedirect(url, 5000, 6000)`, `httpGet(url, 5000, 6000)`).

---

## 2. Root cause & fix — the `No active stream to cancel` EventChannel collision & the dead buttons

### 2.1 The collision

**Root cause (v3.4.6.e):** `bridge.dart` created a **separate `receiveBroadcastStream()` product per feature view on the SAME channel name** — four on `anantplay/cast/events` (`castStatus()` line 398, `officialVersionEvents()` 503, `onboardingStatusEvents()` 532, `installEvents()` 540) and two on `anantplay/updater/events` (`updaterEvents()` 406, `updateGateEvents()` 416). Each product installs its own message handler on the channel name when its first listener attaches, so whichever view listened LAST silently stole every event — the Home receiver card stopped updating (taps "worked", UI never changed), and a stale product's cancel arrived at a native channel with no active stream → `EventChannel`'s Java side replies `error("No active stream to cancel")` → the unhandled PlatformException the Dart crash reporter forwarded to CrashLog (the captured `MainActivity.onCrashlogCall` stack).

**The fix — one channel name = ONE hub = ONE native listen for the app lifetime:**

- `_ChannelHub` (**`ui/lib/anantplay/bridge.dart:51`**): one lazy `StreamController.broadcast()` per channel name, fed by at most ONE `receiveBroadcastStream().listen(...)` started by the first subscriber and **never canceled** (`cancelOnError: false`, errors counted, never surfaced as uncaught zone errors). All feature views (`castStatus`, `updaterEvents`, `updateGateEvents`, `probeResults`, `rawTroubleEvents`, `onboardingStatusEvents`, `officialVersionEvents`, `installEvents`, `shellRoutes`, `playerFrames`) map over the same hub stream (`bridge.dart:610–748`).
- `AnantPlay.hubStats()` (**`bridge.dart:589`**) exposes per-channel listen/cancel/error counters — the honest Layer-1 bridge audit reads them (a healthy bridge answers `listens ≤ 1, errors == 0`).
- Java side (`MainActivity.java:484–545`): every `StreamHandler.onCancel` is idempotent (`if (sink != null) sink = null` — nulling an already-null sink is a no-op, never a throw) and `onListen` replays the last snapshot (`replayGate` for the updater, `pushCastStatus` for cast).
- Test-proven: `bridge_test.dart` — *"MANY listeners on ONE channel: exactly one native listen, zero errors"* and *"updateGateEvents and updaterEvents share ONE native listen (both views fed)"* — the exact v3.4.6.e topology now produces zero collisions.

### 2.2 The dead buttons

The buttons themselves were never unwired — **the feedback loop was severed**: taps fired the method call, the native side ran and pushed the status event, and the clobbered handler dropped it, so `AppState` never republished and the widget never rebuilt. With the hub restoring delivery end-to-end, the existing audit chain (`AppStateScope` → `InheritedNotifier` → `_republish`) is sound. Verified live: `ShellRootState`'s gate subscription, Home's `_ReceiverHero` (its `Stop Receiver` tap → `stopReceiver()` → native `requestStop` + `pushCastStatus` twice → hub → `AppState._onCast` → `publish` → Home re-renders **Stopped** with the button flipping to **Start Receiver**) — asserted in the new shell test *"Stop Receiver visibly flips the hero card to Stopped + Start Receiver (the hub delivers)"*. Also fixed while auditing: `_InstallerSheet`'s leaked, never-canceled `installEvents()` subscription (**`home_screen.dart:514–538`** — every opened sheet stacked one more listener forever).

### 2.3 The `receiver stop requested (user)` vs `receiver: up` contradiction

v3.4.6.e already had the honest stop (`ReceiverService.requestStop` clears `running` synchronously, both teardown lanes, the START_STICKY veto, `notifyReceiverDown` on `onDestroy`). The **Status Summary** lie was a symptom of §2.1: the `up=false` frames never reached the Dart state that feeds the report's receiver line. With the hub, `pushCastStatus()` frames land and `AppState.castStatus` holds the truth; `Status Summary` in the rebuilt `buildDeepReport` reads `ReceiverService.isUp()` **directly** (never the stale Dart cache), so the two can no longer disagree. The user's stop stays stopped: the standing-stop veto is untouched (`App.java:146–152`).

---

## 3. Root cause & fix — the troubleshooter that lied (54/54 PASS on real failures)

### 3.1 Probe 47's fake PASS (the `HTTP 0 → offline retention path ✓` lie)

**Root cause (v3.4.6.e):** `DeepProbe.probeE10` computed `o.pass = freeOk && health >= 0` — `workerHealthHttp()` can only return `≥ 0`, so a dead worker (HTTP 0, subtitles broken) still passed, printing *"worker /health HTTP 0 → offline retention path ✓"*.

**The v3.4.6.f honesty law:** `probeE10` (**`DeepProbe.java:955`**) now calls BOTH `/health` **and** `/api/resolve?title=The+Odyssey` (`Hooks.workerResolveHttp()`, `MainActivity.java:1860`, same 5 s/6 s caps) and `o.pass = (health == 200) && (resolve == 200) && freeOk` (**`DeepProbe.java:969`**). Any non-200 on either leg is a FAIL with the exact code: *"FAIL — Worker unreachable (HTTP 0 / HTTP 0): subtitles fall back to the local translator"*. The lab inverts the old assertion on purpose (`ReceiverLab` v346f env checks: dead worker → FAIL; resolve-only dead → FAIL; 503 → FAIL with the code).

### 3.2 The crash gate (the captured `Last Crash` that 54/54 PASS ignored)

New probe F6 (**`DeepProbe.java:1077`**, `Hooks.crashTopLines()` → `MainActivity.java:1875` → `CrashLog.read`): an uncaught Java/Dart exception captured for THIS build is a FAIL with the exact class + line — *"FAIL — uncaught crash captured: IllegalStateException: PlatformException(error, No active stream to cancel, null, null) | at MainActivity.onCrashlogCall"*. CrashLog's per-version stamping filters stale reports on upgrade; zero captured lines is the only honest PASS.

### 3.3 The real subtitle E2E probe

New probe E13 (**`DeepProbe.java:1028`**): a genuine SRT body carrying the Sinhala ZWJ conjunct ශ්‍රී through the exact shipped pipeline — `SrtLoader.load` (byte-level parse) → `Cue.stripMarkup` → `SubtitleStore.save/load` → the cue read back with timestamps and ZWJ intact. Every leg is named in the detail; any leg failing is a FAIL.

### 3.4 The 32.8 s stall and the 0 ms fake checks

- Every network probe is capped 5 s connect / 6 s read (probeTier1/probeTier2 via the new overloads, `DeepProbe.java:336/353`; worker legs in `MainActivity.deepHooks`, `:1833/:1850`) — the whole run finishes in seconds.
- The "0 ms strictly inside Java SharedPreferences" complaint is answered structurally: the run is now **two-layer** (below) — the Flutter UI layer executes in Dart against the live `AppState` and the live bridge, and the merged verdict counts both. (Native probes that audit shipped-source contracts still use the source atlas — by design, since an installed APK cannot execute its own source tree.)

### 3.5 The two-layer honest audit (`ui/lib/anantplay/deep_troubleshooter.dart`)

When the user taps **Run Full App Troubleshoot**, the native 56-probe run (Domains A–F) executes; the moment its final payload lands, the Dart layer runs (`help_screen.dart:60–98` → `DartLayerAuditor.run`, **`deep_troubleshooter.dart:108`**, ids 57–64, Domain **G**, each with a 3 s timeout so a hung channel can never strand the verdict):

| id | probe | live proof |
|---|---|---|
| 57 | AppStateScope live rebuild trigger | toggles the theme through the real persistence path, counts `notifyListeners` firings, restores the original |
| 58 | Bridge singleton hubs | `hubStats()`: every channel ≤ 1 native listen, 0 errors (the collision killer, measured live) |
| 59 | Stop/Start receiver round-trip | `stop → up==false → start → up==true` through Dart `currentCastStatus`, ending in the original state |
| 60 | Settings persistence round-trip | real `putBool`/`getBool` through the live channel |
| 61 | ForceUpdateScreen isNewer guard | the app's OWN version: `isNewerVersion(installed, installed) == false`, newer/lane cases true |
| 62 | Google search-engine contract | the engine preference round-trips through the live mirror (native probe D2 owns the URL builder) |
| 63 | PlayerScreen compact subtitle bounds | reads the SHIPPED constants: 82 % width cap, 14/15 sp base, 10–28 sp clamp, 1.25 line height |
| 64 | History read-back | the live `WatchHistory` surface answers |

**The truthful verdict:** `composeHonestReport()` (**`deep_troubleshooter.dart:355`**) merges both layers, prints `== MERGED VERDICT: X/N PASS · Y FAIL ==` and **every FAIL ✗ in its own block at the very top** — before the native report body and the Domain G ledger — so nothing is ever hidden. The native `buildDeepReport` got the same law (`MainActivity.java:1652–1673`). The complete report is **auto-copied to the clipboard** the instant the run ends (Flutter `Clipboard.setData` + the native `ClipboardManager` lane), with the banner showing the real pass/fail counts and the Copy Again / Share rows unchanged. PII scrubbing stays 100 % (`PrivacyScrubber` on the native body; the Dart layer carries no PII by construction).

---

## 4. What was NOT touched (zero-regression contract)

`prefs` still initialized **before** `super.onCreate(b)` (`MainActivity.java:235`); `zipalign -P 16 -f 4` + `verify_apk_launch_integrity.py` + `verify_flutter_bridge_wiring.py` + the whole `lab/run_lab.sh` gate unchanged in contract; `anantplay/crashlog` untouched; LGPL-3.0-only set intact (`LICENSE`, `ui/lib/mornye_ui` COPYING/NOTICE, `THIRD_PARTY.md`); zero `AccessibilityService`; `allowBackup="false"`; the APK < 50 MiB gate unchanged. `docs/latest.json` stays at the live `v3.4.6.e / 30411` on this branch; the release workflow rewrites it when `v3.4.6.f` ships, and `lab/ReceiverLab.java` now accepts BOTH tiers in its latest.json gates.

---

## 5. Verification (0 FAIL required — all green)

| Gate | Result |
|---|---|
| `python3 lab/verify_flutter_bridge_wiring.py` | **72 passed, 0 failed** (0 dead channels, 0 fake buttons) |
| `scripts/compile_check.sh` (android-36 + flutter.jar + media3 + R) | **COMPILE OK: 166 classes** from 69 sources |
| `lab/verify_syntax.py` | **0 diagnostics across 69 sources** |
| `bash lab/run_lab.sh` (JDK 21) | **3397 executable + static checks passed, 0 failed** — including the new honesty inversions (worker HTTP 0 → FAIL, crash-gate FAIL, sweep/single-flight/dedupe source contracts, both-tier latest.json acceptance) |
| `flutter pub get && flutter analyze` | **No issues found** |
| `flutter test` | **73 tests — all passed**, including the new suite: the self-lock kill E2E (`v3.4.6.f → v3.4.6.f` never locks; a strictly-newer release still does), the same-version pending bootstrap render-nothing proof, the two hub-collision kill tests, the visible `Stop Receiver → Stopped + Start Receiver` flip, the honest `composeHonestReport` FAIL-first proof, and the onboarding install-updates permission cards |
| Version parity | `AppInfo 3.4.6.f/30412`, `build.gradle 30412/"3.4.6.f"`, `pubspec 3.4.6+30412` (pure integer), `build.sh 3.4.6.f/30412 (prev 30411)`, workflows + README/SETUP-SI headers bumped, tag `v3.4.6.f` |

---

## 6. සාරාංශය (සිංහල)

**v3.4.6.f (30412) — අවංක යාවත්කාලීනකරු සහ නොක්‍රියාකාරී බොත්තම් අලුත්වැඩියාව**

- **ස්වයං-ලොක් ක්‍රියා රහිත කිරීම:** ඔබ දැනටමත් `3.4.6.e` හි සිටියද `3.4.6.e → 3.4.6.e` ගේට් එක මුළු යෙදුමම අගුලු දැමුවා. දැන් සෑම මට්ටමකදීම (Java `pendingFile` + `sweepStalePending` සහ Dart `isNewerVersion`) අලුත් සංස්කරණයක් පමණක් ගේට් එක විවෘත වේ — එකම විශාලත්වයේ ගේට් එක ගණිතමය වශයෙන් කිසිදා අවහිර විය නොහැක.
- **කිසිදු නොකැඳවූ බාගැනීමක් නැත:** යෙදුම විවෘත වූ විගස පසුබිමේ APK බාගැනීම (12 තත්පරයකින් 7 පරීක්ෂා + 2–3 වාර නොකැඳවූ බාගැනීම්) සම්පූර්ණයෙන් ඉවත් කර ඇත. යාවත්කාලීනයක් ඇති විට `Update Now` ඔබන තුරු ගේට් එක රැඳී සිටිනවා පමණි; ඔබ ඔබන විට පළමුව **"ස්ථාපන අවසරය"** (Step 1 of 2), ඉන්පසු එක් වරක් පමණක් බාගැනීම (0% → 100% සජීවී ප්‍රගතිය), SHA-256 තහවුරු කිරීම, සහ Android එක්-තට්ටු ස්ථාපන පත්‍රය.
- **`No active stream to cancel` ගැටලුව මරා දමා ඇත:** එක් EventChannel නාමයකට එක් සබ්ස්ක්‍රිප්ෂනයක් පමණක් — Home/Settings බොත්තම් දැන් සැබව ක්‍රියාත්මක වේ; **Stop Receiver** ඔබූ විට කාඩ්පත ක්ෂණිකව **විරාම කර ඇත** බවට හා **Start Receiver** ලෙස බොත්තම මාරු වේ.
- **අවංක දෝෂ මුද්‍රණය:** Worker `HTTP 0` වුවහොත් හෝ `Last Crash` තුළ සැබෑ දෝෂයක් තිබේ නම් පරීක්ෂකයා දැන් `FAIL ✗` ලෙසම වාර්තා කරයි — සියලු FAIL පළමුව ඉහළින්ම; වාර්තාව සම්පූර්ණයින්ම ක්ලිප්බෝඩ් එකට ස්වයංක්‍රීයව පිටපත් වේ; ජාල පරීක්ෂණ 5–6 තත්පර පමණක් (මිනිත්තුවක් හරියට නොවේ). දැන් 56 ස්වදේශීය + 8 Dart-ස්ථර පරීක්ෂණ — සජීවී Flutter UI පරීක්ෂණ ඇතුළුව.
- **සියලු තහවුරු කිරීම් හරියට පැහැදිලියි:** පරීක්ෂකයාගේ lab 3397/3397, Java සම්පාදන 166/166, Flutter 73/73 — දෝෂ අංශකය බිංදුවක් වුවත් අපගේ අලුත් අවංකබව පරීක්ෂණවලින් පෙන්නුම් කරන්නේ බොරු `PASS` කිසිදා නොවන බවයි.

---

*Report generated for the v3.4.6.f release branch. All file:line references are against the v3.4.6.f tree.*

---

## 7. Addendum — the five CROSS-AUDIT surgical patches (final integration)

The ZIP landed Updater Laws #1–#3, `_ChannelHub` and the honest 64-probe two-layer
troubleshooter intact. A second cross-audit found five untouched files; all five are
now patched in this release branch with no change to those laws:

| # | Patch | Verifiable contract |
|---|---|---|
| 1 | **Dynamic Mornye repaint** — `ui/lib/main.dart` | `AnantPlayApp` now hosts `_ThemedApp` below `AppStateScope`. It rebuilds `ThemeData` from `themeMode` (system/dark/AMOLED `#000000`/light), presets 0–6 + custom `#RRGGBB` + Material You seed, glass clarity 0.10–0.95, outlines, force-liquid-blur, motion, density, card radius, bottom-bar style and midnight dimmer. The very next frame after a tap repaints `MaterialApp` / `Theme.of(context)`; widget tests tap Light / AMOLED and the clarity slider and assert the repaint without a cold restart. |
| 2 | **Optimistic settings + real import** — `app_state.dart`, `settings_mirror.dart`, `settings_screen.dart`, `browser_screen.dart`, `MainActivity.java` | `putBool`/`putInt`/`putString` publish into `SettingsMirror` and `notifyListeners()` BEFORE the MethodChannel write (with rollback on failure). `SettingsMirror` has value equality + hash. Native `importBackup` validates with `SettingsBackup`, excludes `onboardingDone`/`onboardingStage`/`onboardingVersion`, commits, returns `applied` + a fresh `gatherSettings()` snapshot. Check for update (Home/Settings/Help), Ping worker and Import/Export now show crisp feedback (`Checking…` → `Up to date (v3.4.6.f)`, `Worker OK · HTTP …`, `Worker unreachable · HTTP 0`, `Imported N settings ✓`). `_CaptureDock.onToggle` was audited and is already wired to the live dock state. |
| 3 | **Persistent user stop** — `ReceiverService.java`, `App.java`, `BootReceiver.java`, `MainActivity.java`, `DeepProbe.java` | A dedicated `receiver_state` SharedPreferences file holds `userStop` with a synchronous `commit()`. It is hydrated at process start/boot, honored by `ACTION_APPLY_PREFS`, the 15-minute healer and every auto-arm, and cleared ONLY by the explicit Home **Start Receiver** tap. DeepProbe snapshots the exact veto, runs in a transient unblocked lane and restores it in `finally`, so Status Summary can never claim `receiver: up` after a user stop. The file is outside `SettingsBackup` / `sicast`. |
| 4 | **Frame discriminator + honest hooks** — `bridge.dart`, `MainActivity.java`, `WebBrowserView.java`, `BridgeGuard.java` | `castStatus()` now filters its shared event hub to frames tagged `t:'castStatus'` (legacy `up`-bearing frames still accepted), so onboarding/officialVersion/install frames can never decode as `up:false`. `deepHooks()` replaces `return 0`/`return true` stubs with the live `notImplemented` registry, real `WebSettings`/BridgeGuard state and the actual PlatformView headless policy. |
| 5 | **CI `fail` vs `env_fail`** — `DeepProbe.java`, `MainActivity.java`, `build-apk.yml` | Probe 47 remains brutally honest: HTTP 0 / non-200 is `FAIL ✗` at the top of the user report and in every per-probe CI line. The machine summary adds `env_fail=<N>` for an external Worker outage only; `fail` counts REAL app/code failures (including a broken local translator) and must be `0`. The workflow parses both at a token boundary, emits `::notice` for `env_fail>0`, and hard-fails on `fail>0` plus any `No active stream to cancel`. |

`docs/latest.json` stays at the live `v3.4.6.e / 30411` on the PR branch; the
release workflow is the only writer of the final `3.4.6.f / 30412` SHA-256.

---

## 8. Addendum — hosted-CI hardening (run 45654794027)

The first hosted run exposed four non-release defects; all are fixed without
changing any of the five patch contracts above:

1. **`guarded()` dropped the `envFail` marker** when it stamped a missing probe
   identity. It now stamps `id`/`domain`/`name` in place, preserving the honest
   `pass=55 fail=0 env_fail=1` split while Probe 47 still prints `FAIL ✗`.
2. **Dart analyzer** flagged a missing `SettingsMirror` import, an unused
   `_ThemedApp` `key`, an unused local, an unsupported `tap(offset:)` and six
   `use_build_context_synchronously` infos. All are fixed with explicit
   `context.mounted` guards and a direct `Slider.onChanged` repaint test.
3. **The lab's "real failure stays in `fail=`" check was vacuous** — it compared
   counts instead of injecting a defect. It now breaks the receiver's own
   `/info` lane and asserts `fail>0` with the worker outage still in `env_fail`.
4. **The persisted user-stop veto stranded the deep-probe re-arm** after the
   live Stop-Receiver probe: `probeB3` now clears the veto transiently *before*
   re-arming, with the existing `finally` restore protecting the user's state.
   This removes the false emulator failures on probes 9 and 10 without ever
   mislabelling a genuine app failure as environmental.

The APK build job is already green on the rerun with `30412 / 3.4.6.f`;
the remaining three jobs are re-running against these fixes.
