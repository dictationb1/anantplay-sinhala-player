# AnantPlay Sinhala Player — v3.4.6.m (versionCode 30419)
# THE SILENT-GLASS-PLAYER FIX REPORT

**Release:** `v3.4.6.m` · `versionCode 30419` · Git tag `v3.4.6.m`
**Baseline:** `v3.4.6.l` (`30418`) @ `main`
**Scope:** the 7-video audit + the real-phone `ForegroundServiceDidNotStartInTimeException` crash — every single item fixed, honestly probed, and verified with 0 FAIL across every local gate.

---

## සාරාංශය (සිංහල)

**v3.4.6.m** යවන්නේ **නිශ්ශබ්ද-වීදුරු-වීඩියෝ සිළි පිරිසිදු කිරීමේ** නිකුතුවයි:

* **බලාපොරොත්තු නොමැති බැසීම මරා දමන ලදී** — `ForegroundServiceDidNotStartInTimeException` හතර මාර්ගයම (veto / ACTION_STOP / අනවසර පිටපත් / නතර වීම) දැන් `startForeground()` **පළමුවෙන්ම** ක්‍රියාත්මක කරයි (`ReceiverService.java:304,488`); `connectedDevice → mediaPlayback → dataSync` දම්බු දාමය සමඟ.
* **3.4.6.x නිහළ යාවත්කාලීන** — අකුරු-පැච් එකක් සඳහා වාරික පිටුව **කිසිවිටෙක නොපෙනේ**; Apple වීදුරු දැනුම්දීම පමණක්: `Updating to vX… → Installing update…`. `Installer Active…` බොත්තම 8 තත්පරයක් අගුලු ලා ඇත; අවසර නැවත ඒම 200 ms ක්ෂණිකයි.
* **සුබ පැතුම් පින්තූර කැඩීම මැරුණි** — `getInitialRoute()` රාමුව 1 ට පෙර `"/home"` / `"/onboarding?stage=N"` පිළිතුර දෙයි; Slide 1 නැවත පැමිණීම අභව්‍යයි.
* **සම්පූර්ණ ඔන්බෝඩින් කුලුම්බන** — සියලු අවශ්‍ය ස්ලයිඩ 5 (අටුවාපත් ස්ථාපනය, දැනුම්දීම්, ඔවර්ලේ, බැටරි + යාවත්කාලීන); `✓ Finish` ක්‍රීඩා පුහුණු අවසානය දක්වා අගුලු ලා ඇත.
* **තාපය / HW-කොඩෙක් සිනමා එන්ජිම** — `CodecPolicy` දෘඪාංග ප්‍රවර්ණක තෝරයි; Low Data Mode 480p/1.2 Mbps; 0/1/4 Hz ටික් නියමය.
* **සැබෑ අගුලු ප්‍රකාරය** — අගුලු ලා ඇති විට එකම unlock කුඩිකය හැර සෑම ස්පර්ශයක්ම ගිලී යයි.
* **නොබැඳුණු `AlertDialog` නොමැත** — සිනමා වීඩියෝ තුනම අඳුරු-වීදුරු sheets වේ; උස්සන-දඟයක් සජීවී වටිනාකම් bubbles; කේන්ද්‍ර බෆර් වළලුව.
* **Apple iOS 26 Liquid Glass** — තනි-පෝලිම් සැරසිලි බැනරය (sigma 22 + specular rim); OverlayPortal කැඩෙන්නේ-නැති slider bubbles; බ්‍රවුසරයේ **පාවෙන `▶` ධාවන බොත්තම**.
* **සෑම දේම අවංකව ඔප්පු කරන ලදී** — පරීක්ෂණ 84 native + 14 Dart = **98**; lab 3487/3487 ✓ · flutter analyze 0 · flutter test 268/268 ✓ · 0 FAIL.

---

## 1 — THE CRASH: `ForegroundServiceDidNotStartInTimeException` (FAIL 56 / FAIL 92)

**Root causes found (four distinct unsatisfied-FGS lanes in `ReceiverService.java`):**

| # | Lane | The defect in v3.4.6.l | The v3.4.6.m fix |
|---|------|------------------------|-------------------|
| 1 | `arm()` → `startForegroundService()` → `onCreate()` → persisted `userStop` restored **inside the service** → `armCore()` veto returned | `stopSelf()` **without ever calling `startForeground()`** → Android 12+ killed the process ~5 s later | `ensureForegroundSatisfied()` is the **FIRST statement of `onCreate()`** (`ReceiverService.java:291-304`), before the veto can return |
| 2 | A **duplicate** `startForegroundService()` delivery landing on a fresh instance while the original still serves | `armCore()` "another live instance already serving — no-op" returned; the duplicate never called `startForeground()` → the whole process died | The duplicate now tears down cleanly **after** its onCreate satisfied the contract (`ReceiverService.java:328-339`) |
| 3 | `ACTION_STOP` delivered to a half-torn-down instance whose `onCreate` will not re-run | teardown before any re-satisfy | `ensureForegroundSatisfied()` at the **top of `onStartCommand()`** (`ReceiverService.java:481-488`), before the ACTION_STOP lane |
| 4 | `requestStop()` used `startService()` unconditionally | could materialise a fresh instance just to stop it | only a **live** instance gets the in-place `startService()` (`ReceiverService.java:233-246`) — NEVER `startForegroundService()` |

**The typed chain** (`ReceiverService.java:586-610`): `startForeground(1, n, type)` is attempted as `connectedDevice → FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK → FOREGROUND_SERVICE_TYPE_DATA_SYNC → untyped`; the manifest declares `connectedDevice|mediaPlayback|dataSync` plus both `FOREGROUND_SERVICE_MEDIA_PLAYBACK` / `FOREGROUND_SERVICE_DATA_SYNC` permissions (`AndroidManifest.xml:152-160`, `:43-45`). The idempotence guard is `fgsStarted` (`:540`), reset by `stopNow()` (`:263`) so a re-delivery re-satisfies it.

**Honest probes:** `DeepProbe.probeM1` (id 79) asserts every element of this contract; `ui/test/v346m_test.dart` group 1 asserts the onCreate/onStartCommand ordering, the requestStop call-form, and the manifest chain.

## 2 — THE PERFECT TWO-LANE SILENT AUTO-PUSHER (Rule #1)

* **The gate-ordering kill** — v3.4.6.l armed `persistRequiredVer` + `gateAvailable` **before** checking the letter-patch lane, so a 3.4.6.x patch pushed the blocking "Update required" screen on (`SelfUpdater.java:1060-1076`): the `silentPatchLane` guard now arms the gate ONLY for a major/minor release (≥ 3.5.0) or when the patch lane cannot run (no install permission).
* **The glass pill's second act** — `fire("Installing update…")` fires the exact moment the `PackageInstaller` session commits (`SelfUpdater.java:1325-1329`), after `"Updating to v" + rel.version + "…"` (`:1080`).
* **The re-clickable kill** — the button is strictly disabled 8 s as **`Installer Active…`** with the spinner (`force_update_screen.dart:69-76, 174-184, 253-289`); `installCommitInFlight` + the 15 s cooldown remain the native belt-and-braces.
* **Instant auto-return** — the permission watcher poll tightened **400 ms → 200 ms** (450 ticks = 90 s) (`MainActivity.java:1463-1476`); snap-back < 300 ms.
* **PENDING_USER_ACTION** stays the honest fallback: exactly ONE confirm sheet, single-flight held (`SelfUpdater InstallResultReceiver`).
* **OtaConfigSync "Pending" kill** — each CDN fetch now gets its **own 4 s budget** (`FETCH_BUDGET_MS`, `OtaConfigSync.java:61-65`): raw first; if raw fails/times out on ISP DNS, jsDelivr's answer alone can carry the sync (strict canonical SHA-256 + SSRF guard still enforced, `:110-160`); the Verified stamp + live counts commit synchronously (`:232-237`) so `About & Licenses → Remote config (signed)` reads **`Verified ✓ · 2 mirrors · 2 feeds · 7 ad rules`**.

## 3 — ZERO WELCOME FLASH / ZERO SLIDE-1 RESET (Phase 2)

* **Synchronous `getInitialRoute()`** (`MainActivity.java:381-415`): `"/home"` when `onboardingVersion ≥ 2 && onboardingDone`; otherwise `"/onboarding?stage=N"` with the EXACT persisted stage — answered **before frame 1** (a synchronous first SharedPreferences read, never the async getAll hydration).
* **The Dart twin** (`main.dart:595-621`): `_ShellRoot` reads `platformDispatcher.defaultRouteName` once, parses `_initialOnboardingStage`, and the postFrame stage-reset now runs ONLY **after hydration** and ONLY for the legacy v3.4.6.a finisher — the pre-hydration stage-0 write that reset every mid-wizard user to Slide 1 is dead (`main.dart:698-716`).
* **The wizard-stage writer is synchronous** — `putInt('onboardingStage')` rides a `commit()` lane in the Java bridge (`MainActivity.java:1955-1981`), matching the Dart `_persistStageBeforeLeaving()` contract.
* **Complete gating** (`onboarding_screen.dart:52-63`): `1 => !s.official` (Install AnantPlay — the `Continue without installing` escape is DELETED, `:585-588`), `2 => !s.notif`, `3 => !s.overlay`, `4 => !s.battery || !s.installs` (BOTH Background Activity AND Allow App Updates). Backward swipe / Back stays free.
* **The coach-gated `✓ Finish`** (`onboarding_screen.dart:670-725`): disabled with the caption `"Complete 'Open AnantPlay with User Guide' first"` until `K_COACH_COMPLETED` is true (live in `_status.coach`, pushed by `onboardingStatusMap` `MainActivity.java:1027`); unlocked it GLOWS; `_finish()` carries the belt-and-braces hard gate (`:252-263`).
* **The coach upgrade** (`AnantPlayCoachService.java`): a second, strictly non-blocking **Target Pointer Arrow overlay** (`FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE`, `:407-420`) animates a glowing right-pointing arrow at the exact target zone per step (`targetPointerYFrac`, `:392-397`); 1-tap-copy auto-advance on the URL steps (`:462-473`); Step 10 `Finish Setup ✓` synchronously `commit()`s `K_COACH_COMPLETED = true` + `K_ONBOARDING_STAGE = 5` **before** either overlay leaves the screen (`:395-408`) and returns to Slide 6 through the BAL lane. ZERO AccessibilityService — permanently.

## 4 — THE DEEP CINEMA PLAYER OVERHAUL (Phase 3)

* **`CodecPolicy.java` (new, 15 KB)** — scans `MediaCodecList(REGULAR_CODECS)` for strictly `isHardwareAccelerated()` decoders (AVC/HEVC/VP9/AV1, `:167-185`), wraps them in a hardware-first `MediaCodecSelector` that **never drops a decoder and never downgrades resolution** (`:187-235`); `applyHardwareFirst` is wired into BOTH the renderers factory and the player builder (`PlayActivity.java:1075,1104`); one engine identity line logs at playback start (`PlayActivity.java:1119`).
* **Low Data Mode (`K_LOW_DATA_MODE`)** — ON caps ABR at `1_200_000` bps / 854×480 (`CodecPolicy.java:279-287`), read live (`AppPrefs.lowDataMode`, `AppPrefs.java:979-982`), re-applied through a prefs listener (`PlayActivity.java:1287-1310`), toggleable in Settings → Playback & Subtitles (`settings_screen.dart` `_SwitchRow`) and backed up (`BACKUP_KEYS`, `AppPrefs.java:431-432`).
* **The UI tick law** — the ticker now computes its cadence live: **0 Hz** when HUD hidden + no cues, **1 Hz** when HUD hidden + subtitles active, **4 Hz** only while the HUD is visible (`PlayActivity.java:2429-2487`, re-armed from every state change `:879,1134,1248,1930`).
* **The duplicate-ExoPlayer kill** — the lingering bridge engine is released before native playback starts (`PlayActivity.java:930`).
* **REAL lock mode** — while locked, `dispatchTouchEvent` **swallows every event** (Play/Pause, double-tap, long-press, scrub, pinch, brightness/volume swipes, sheet opens) except the unlock pill's own hit-box (`PlayActivity.java:3304-3330`); every swallowed touch re-offers the `🔒 Tap to unlock` pill for 2.5 s (`:3287-3293`); CinemaGestures early-returns as belt-and-braces (`CinemaGestures.java:66,134`).
* **Subtitle pills** — a tap flips the ACTIVE accent highlight on the same tap through the `selectLangChip` painter (`CinemaSheets.java:480-491`), persists `K_DEFAULT_LANG`, re-fetches through `switchLang` (Test Player demo cues included, `PlayActivity.java:2189+`), and the toast reads **`"Fetching " + name + " subtitles…"`** — the missing space is dead (`PlayActivity.java:3393`).
* **Zero legacy `AlertDialog`s** — all five builder sites in PlayActivity are replaced by rounded dark-glass `UiSheet` bottom sheets: the **Orientation sheet** (first-tap apply + highlight, `CinemaSheets.java:338-368`), the **searchable All-Languages sheet** (`:397-443`), and the **Title-search input sheet** (IME-safe, `PlayActivity.java:3330+`). `grep AlertDialog PlayActivity/CinemaSheets/CinemaHud` → **0 matches**.
* **Quality-switch viewport guard** — transient `w/h ≤ 0` refused and a changed aspect is HELD until `onRenderedFirstFrame` of the new track (`PlayActivity.java:399,1152-1168`); `AppPrefs.ASPECT_FIT` stays the strict default.
* **Landscape-safe sheets** — `insetSafe()` applies `statusBars | displayCutout | navigationBars` padding to every sheet ScrollView (`CinemaSheets.java:197,239,266,335,622+`); orientation pills apply on the 1st tap.
* **Live thumb bubbles** — `showThumbBubble` floats the accent value pill above the dragging thumb on Volume boost / Subtitle size / Background opacity / Vertical position (`CinemaSheets.java:489-600`), on the sheet's overlay FrameLayout (code-only, verify_layouts-proven).
* **The center buffering ring** — `STATE_BUFFERING` renders the spinning ring centered on the hero Play/Pause disc with the glyph standing down (`CinemaHud.java:235,354-381`; `PlayActivity.java:1127-1134`).
* **Sleep timer / double-tap / long-press** — all execute real live actions: the sleep countdown pill rides the ticker and pauses the player on expiry (`PlayActivity.java:434-437,2412`); double-tap seeks exactly `K_DOUBLE_TAP_SEC`; long-press holds exactly `K_LONG_PRESS_SPEED` (CinemaGestures hold-speed lane).

## 5 — APPLE iOS 26 LIQUID GLASS + ZERO-CLIP BUBBLES + ZERO GAPS + THE FLOATING ▶ (Phase 4)

* **The banner** (`mornye_glass_notification.dart`): 24dp squircle floating below the status bar, real `BackdropFilter(sigmaX: 22, sigmaY: 22)` (`:304`), the luminous `_SpecularRimPainter` rim (`:394,412`), frosted 40dp tinted icon badge, bold title + 2-line caption, depth shadow, swipe-up dismiss — and a **strict single queue**: exactly ONE `OverlayEntry` app-wide (`:29`) whose `AnimatedSwitcher` child is replaced in-place (`:196-210`), so duplicate/overlapping toasts are structurally impossible. Light legibility `#F8FFFFFF` / `#111118`; Dark + AMOLED keep ≥ 4.5:1.
* **HelpScreen fires exactly ONE banner** on troubleshoot completion (the per-run `_finishRun` ledger dedupes the double delivery, `help_screen.dart`).
* **The zero-clip slider bubble** (`mornye_bubble_slider.dart`): `OverlayPortalController` (`:76`) + `CompositedTransformTarget`/`CompositedTransformFollower` with `showWhenUnlinked: false` render the `47% / 61% / 14sp` pill in the app Overlay — it can never clip against a `SettingsGroup` card border again.
* **Zero bottom black gaps** — Settings' hub and sub-pages and the Browser page pad to the tight `nav + 12` inset; content scrolls behind the floating bar (Settings/Browser scroll paddings tightened 78/32 → 12 on top of the shell's reserve).
* **The browser floating `▶`** — `WebBrowserView` injects `VIDEO_RECT_HOOK_JS` (`:292-310`), a lightweight observer reporting every visible `<video>`'s CSS rect through `SniJS.onVideoRect`; the headless rail converts to dp and pushes `t='video-detected'` (kind/url/title/left/top/width/height/adFree; 300 ms rail limit, `shellVisible()` guard so a backgrounded observer NEVER relaunches the app, `:1465-1543`); `t='video-clear'` leaves with the page. `BrowserScreen` renders the **52dp pulsing frosted circle** with the glowing accent Play triangle anchored at the video rect's top-right corner (`browser_screen.dart:126-144,215-231+`) — one tap into the Cinema Player (the ad-free YouTube lane when the payload is live).

## 6 — HONEST TROUBLESHOOT + PRIVACY (Phase 5)

* **PrivacyScrubber technical-term whitelist** (`PrivacyScrubber.java:114-137`): `SHA-256`, `SHA-1`, `3-URL`, `16-KiB`, `H.264`, `H.265`, `AV1`, `VP9`, `HLS`, `DASH`, `M3U8`, `v3.4.6(.k/.l/.m)` are full-token, case-insensitive members of `TECHNICAL_TERMS`; dotted version/codec strings are never hardware models (`:152-156`). Probes 73/76 can no longer be corrupted into `<phone-model>` while real models/PII stay 100% scrubbed.
* **Tightened honest probes** — `#42` (E5): the real chip-selection + the `"Fetching "` space; `#45` (E8): first-tap orientation + **zero AlertDialog**; `#74` (K1): the silent letter-patch lane vs the major gate (`silentPatchLane` + `Installing update…` + 8 s lock); `#78` (K5): slides 1–4 gating + the coach gate + the synchronous `getInitialRoute()`.
* **Six new Domain M probes (ids 79–84)** — FGS kill, lock swallow, CodecPolicy + Low Data, OverlayPortal bubble, Liquid Glass single-queue, browser ▶ (`DeepProbe.java:354-360,2512-2685`); the Dart layer renumbered to ids 85–98 with the `M` domain registered (`deep_troubleshooter.dart:114-121,165`); the Help counter reads **98** (`help_screen.dart:414-418,444`). Sequential unique ids 1..98 — **0 collisions**.
* **`ciDomainLine`** carries the M roll-up (`DeepProbe.java:2095-2101`); the CI emulator job hard-asserts `fail=0` and ≥ 84 probes (`build-apk.yml:1054-1055`).

## 7 — ZERO-REGRESSION LAWS (all preserved)

`prefs` assigned before `super.onCreate(b)` (`MainActivity.java:353`) · `_ChannelHub` single-subscription EventChannels (0 `No active stream to cancel`) · `safeGetLong/safeGetInt/putTyped` type-heal laws intact · **100 MiB APK cap** (`SelfUpdateCore.MAX_APK_BYTES` + `104857600` in both workflows) · `zipalign -P 16 -f 4` unchanged · `verify_apk_launch_integrity.py` / `verify_flutter_bridge_wiring.py` green · `anantplay/crashlog` reporter unchanged · **LGPL-3.0-only** notices intact · ZERO `AccessibilityService` · `allowBackup="false"` · zero sandbox Gradle mirrors / NDK stubs.

## 8 — VERSION PARITY (3.4.6.m / 30419)

| File | Where |
|------|-------|
| `android/app/build.gradle` | `versionCode = 30419`, `versionName = "3.4.6.m"` (`:106-107`), previous-tier comment `.l/30418` |
| `ui/pubspec.yaml` | `version: 3.4.6+30419` (pure integer) |
| `.github/workflows/build-apk.yml` | `test "$VERSION" = "3.4.6.m"` / `"30419"` ×3 · 84-Probe labels · 100 MiB cap |
| `.github/workflows/release-apk.yml` | `default: "3.4.6.m"` (derives 30406+13 = 30419) · 100 MiB cap |
| `README.md` / `SETUP-SI.md` | v3.4.6.m (30419) headers |
| `build.sh` | `VERSION_NAME:-3.4.6.m` · `VERSION_CODE:-30419` · `PREV_VERSION_CODE:-30418` |
| `AppInfo.java` | `VERSION_NAME = "3.4.6.m"`, `VERSION_CODE = 30419` (+ the .m changelog block) |
| `lab/verify_gradle_host.py` | pins .m/30419, latest.json stays LIVE at `.l/30418` |
| `lab/ReceiverLab.java` | every identity check now `v346m` 30419; the widened latest.json gate accepts BOTH 3.4.6.l and 3.4.6.m |
| `docs/latest.json` | **UNTOUCHED** — `{"version":"3.4.6.l","code":30418}` (the release workflow rewrites it on ship) |
| Git | tag **`v3.4.6.m`**; APK artifact name `AnantPlay-Sinhala-Player-v3.4.6.m.apk` |

## 9 — THE FULL VERIFICATION LEDGER (0 FAIL)

| Gate | Result |
|------|--------|
| `scripts/compile_check.sh` (all 78 Java sources vs android-36 + media3 + Flutter jars) | **COMPILE OK: 207 classes** |
| `python3 lab/verify_flutter_bridge_wiring.py` | **90 passed, 0 failed** (0 dead channels, 0 fake buttons) |
| `python3 lab/verify_ui_completeness.py` | **99 keys wired + backed up, 0 violations** (incl. `K_LOW_DATA_MODE`, `K_COACH_COMPLETED`) |
| `python3 lab/verify_gradle_host.py` | **68 checks, 0 failed** |
| `python3 lab/verify_layouts.py` | **0 problems** (328 layout calls proven type-correct) |
| `bash lab/run_lab.sh` (ReceiverLab + every static auditor) | **EXIT 0 — 3,487 checks passed, 0 failed** |
| `cd ui && flutter pub get && flutter analyze` | **No issues found** |
| `cd ui && flutter test` (incl. the new `ui/test/v346m_test.dart`, 37 tests) | **268/268 passed** |
| Workflow validation (`yaml.safe_load` + `bash -n`/`dash -n` per script block) | **40 blocks clean, 0 failures**, both pinned .m/30419 + 100 MiB |

**JDK/Flutter toolchain used for verification:** JDK 17.0.21 (compile + lab) · Flutter 3.50.1 stable (the exact version both workflows pin) on Linux x64.

## 10 — PACKAGING CONTRACT

The deliverable ZIP `v3.4.6.m-silent-glass-player-fix.zip` is built from **`git ls-files`** (every tracked file — 475 sources + this report = 476) in **ZIP-root layout** (no wrapping directory), with:
* **EXACTLY 7 root files**: `.gitignore`, `AndroidManifest.xml`, `LICENSE`, `README.md`, `SETUP-SI.md`, `THIRD_PARTY.md`, `build.sh`;
* **0 duplicates**; **0** `lab/out/`, `android/.kotlin/`, `*.class`, `*.pyc`, `.flutter-plugins-dependencies`, `.ios/`, `.android/`, `.dart_tool/`, `build/`, `android/local.properties`, `.git/`, `*.apk`, `*.zip`;
* `docs/latest.json` byte-identical to the LIVE `3.4.6.l / 30418` tier; `ui/pubspec.yaml` = `version: 3.4.6+30419`;
* every specifically required asset asserted present by the automated packaging gate (fonts, launcher XML, `ic_lock.xml`, the mornye LICENSE/COPYING/NOTICE, both workflows pinned to 3.4.6.m — see the assertion printout in the delivery log).

*This report is also inside the ZIP at `docs/reports/V3_4_6_M_SILENT_GLASS_PLAYER_REPORT.md`.*

---

## 11 — ADDENDUM: THE `MIT → GPL-3.0-only` REPOSITORY LICENSE UPGRADE

Sole copyright owner **@dictationb1** upgraded the *repository* license from MIT to
**GNU General Public License v3.0 only (`GPL-3.0-only`)** effective **v3.4.6.m** (`30419`).
This is a repository-license switch, not a rewrite of history: every release up to and
including **v3.4.6.l** (`30418`) keeps its original **MIT** release history.

* **Root `LICENSE`** is now the **verbatim GNU GPL v3.0 text** (34,674 bytes), copied
  byte-for-byte from the in-tree, already-verified copy at `ui/lib/mornye_ui/COPYING`
  (`md5 75d892af193fd5a298f724c4377d8f62`) — zero network fetch.
* **Upstream Mornye `LGPL-3.0-only` notices are 100% untouched**:
  `ui/lib/mornye_ui/LICENSE`, `ui/lib/mornye_ui/COPYING`, `ui/lib/mornye_ui/NOTICE` and
  `ui/assets/mornye/LICENSE|COPYING|NOTICE` are byte-identical to their pre-upgrade state.
  A project-wide GPL upgrade never narrows a dependency's weaker copyleft.
* **Full parity across docs, app UI and tests:**
  `THIRD_PARTY.md`, `README.md`, `SETUP-SI.md` (§10) and `Settings → About & Licenses`
  (`ui/lib/anantplay/settings_screen.dart`) now state Project License **GPL-3.0-only**,
  Mornye UI **LGPL-3.0-only**, Media3/Guava **Apache-2.0**, `liquid_glass_easy` **MIT**,
  Fonts **OFL-1.1**.
* **Gates now assert it**: `lab/ReceiverLab.java` (root `LICENSE` = verbatim GNU GPL v3.0 +
  Mornye `LGPL-3.0-only` intact + docs parity), `lab/verify_gradle_host.py` (same, plus the
  About-page strings) and `ui/test/v346m_test.dart` group 12 (three Dart license contracts).
* The Lab therefore grows by **3 ReceiverLab checks + 6 `verify_gradle_host` checks + 3 Dart
  tests**; the GitHub Actions job output remains the single authority for the exact totals.

Nothing else in this release changed: the 98-probe identity (84 native + 14 Dart), the
`3.4.6.m / 30419` version lock, the 100 MiB cap and `docs/latest.json` (still the live
`3.4.6.l / 30418` tier at branch time) are exactly as described in §8–§10 above.
