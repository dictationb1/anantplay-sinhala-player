# AnantPlay Sinhala Player — v3.4.6.d Mornye Overhaul
## Audit & Progress Report (versionCode 30410)

**Baseline:** `dictationb1/anantplay-sinhala-player` `main` @ `v3.4.6.c` (30409, commit `1d738f1`)
**Deliverable:** `v3.4.6.d` / `30410` — every one of the 7 real-device critical defects fixed, mechanically proven, all verification gates green.
**Android target:** Android 16 (SDK 36) · minSdk 26 · arm64-v8a · APK < 50 MiB · LGPL-3.0-only.

---

## 0. Verification Scorecard (all gates re-run on the final tree)

| Gate | Result |
|---|---|
| `python3 lab/verify_flutter_bridge_wiring.py` | **66 PASS / 0 FAIL** (0 dead channels, 0 fake buttons) |
| `cd ui && flutter analyze` (Flutter 3.50.1) | **No issues found** |
| `cd ui && flutter test` | **51/51 PASS** (10 new v3.4.6.d tests) |
| `scripts/compile_check.sh` (android-36 + flutter.jar + media3 1.11.1) | **COMPILE OK: 160 classes** (68 sources) |
| `bash lab/run_lab.sh` (the full gated lab) | **EXIT 0 — 3,257 checks PASS / 0 FAIL**, incl. the new `v346dUpdaterE2eChecks` live mock-server E2E (12/12), `verify_gradle_host.py` 64/64, `verify_apk_launch_integrity.py` selftest, `verify_layouts.py`, `verify_refs.py`, `verify_ui_completeness.py`, `verify_v345_ui.py` |
| Repo hygiene | Root = **exactly 7 files** · `docs/latest.json` **untouched at v3.4.6.c** (release-apk.yml publishes the real signed 3.4.6.d SHA-256) · zero `__pycache__/`, `.pyc`, `local.properties`, `.zip`, `.apk` tracked |
| Version parity | `versionName "3.4.6.d"` + `versionCode 30410` in `android/app/build.gradle`, `AppInfo.java`, `ui/pubspec.yaml` (`3.4.6+30410`, pure-integer build), `build.sh` defaults (PREV 30409), both CI workflows |

---

## 1. Defect #1 — In-app auto-updater broken → MANDATORY full-screen blocking gate

### 1a. Root cause of "had to update via Obtainium" (traced, fixed, proven)

`SelfUpdater.java` gated all pending-APK metadata on the strict 3-segment regex
`"[0-9]+\.[0-9]+\.[0-9]+"`. A downloaded-and-SHA-256-verified `v3.4.6.d` release made
`savePendingUpdate()` throw `IllegalArgumentException`, `hasPendingUpdate()` stayed
`false`, and the install never ran — so the user fell back to Obtainium.

**Fix — `src/.../SelfUpdater.java:239`**
`VERSION_PATTERN = "[0-9]+\.[0-9]+\.[0-9]+(\.[a-z])?"` + `validVersion()`; both
`pendingFile()` and `savePendingUpdate()` accept the whole alphabetical emergency lane.

### 1a-bis. THE PERMANENT LAW — the `[a-e]` ceiling is REMOVED FOREVER (`[a-z]`)

The first pass of this release widened the gate from 3 segments to `(\.[a-e])?`. That was
still a **ceiling**: `3.4.6.f` would have fallen through as "no letter", compared equal to
`3.4.6`, and the updater would have silently gone dead again — a second Obtainium outage
scheduled in advance. Every gate now uses the **whole alphabet**, `a`→1 … `z`→26:

| File | Gate |
|---|---|
| `SelfUpdater.java` | `VERSION_PATTERN = "[0-9]+\.[0-9]+\.[0-9]+(\.[a-z])?"` (pending metadata + `validVersion`) |
| `SelfUpdateCore.java` | `(\.[a-z])?` on all three resolver gates (`usableRelease`, tier-1 `fetchViaWebRedirect`, tier-2 `fetchViaRawJsonBody`) |
| `UpdateChecker.java` | `parse()` maps `'a'…'z'` → 1…26 (and `'A'…'Z'`), so `3.4.6.z` = 26 and ordering is `3.4.6 < 3.4.6.a < … < 3.4.6.z < 3.4.7` |
| `.github/workflows/release-apk.yml` | `X.Y.Z[.a-z]`, letter derived **arithmetically** (`LETTER=$((LETTER - 96))`, never a stale `case` list) |
| `.github/workflows/build-apk.yml` | an `X.Y.Z[.a-z]` shape gate on all three version-resolution steps |
| `lab/ReceiverLab.java` | `v346dUpdaterE2eChecks` proves `.f/.g/.z` + an exhaustive `a→z` monotonic sweep; the source-level proof asserts `[a-z]` **and** the absence of `[a-e]`/`[a-d]` |

**versionCode space is monotonic forever** (the `+1` letter lane and a `+1` patch step used to
collide — the `30407 = 3.4.6.a` trap that originally forced `+9` hacks and a `.d` ceiling):

- **Legacy lane** (base ≤ 30406, already shipped, must never move): `CODE = BASE + LETTER`,
  so `3.4.6.c = 30409`, `3.4.6.d = 30410`, `3.4.6.e = 30411` … `3.4.6.z = 30432`.
- **Wide lane** (every base > 30406): `CODE = MAJOR*1000000 + MINOR*10000 + PATCH*100 + LETTER`,
  a 100-wide slot per numeric version — `3.4.7 = 3040700` (…`3.4.7.z = 3040726`),
  `3.4.8 = 3040800`, `3.5.0 = 3050000`, `4.0.0 = 4000000`. The next numeric is always above
  the whole previous letter lane, and a `.z` emergency release is possible on *every* version.
- A hard `::error` guard refuses any derived code ≤ 30410 (except `3.4.6.d` itself, exactly
  30410) and refuses a patch/minor ≥ 100 that would spill into the next lane.

### 1b. Cold-start suppression removed

`SelfUpdater.checkOnLaunch()` now checks on **every cold start** (a new process). The
5-minute success cooldown only guards warm re-entries inside one process; the 15-minute
failure backoff still protects an offline phone. In `MainActivity.configureFlutterEngine`,
the check now runs for every launch (the `K_AUTO_UPDATE` pref governs only the
auto-download leg, never the check/gate).

### 1b-bis. ZERO-RACE DELIVERY — the gate can no longer be dropped

The gate used to be fire-and-forget. The cold-start check runs on a daemon worker right after
`configureFlutterEngine`, so a fast Tier-1 302 could deliver `gate:available` **before**
Flutter's `ForceUpdateScreen` subscribed — the event hit a null sink and the user was dropped
into the app with a mandatory update parked in cache. That is exactly the class of silent
stall this release exists to kill, so it is now structurally impossible:

- `MainActivity` keeps **`lastGatePayload`** (a `volatile` map) and every gate blob —
  `available`, `progress`, `verify`, `installer`, `failed`, `uptodate` — is delivered through
  **`pushGate(m)`**, which **caches first, then pushes**.
- **`EventChannel.StreamHandler.onListen(...)` calls `replayGate(s)`** the instant Flutter
  subscribes, so a check that finished before the subscription is replayed, never lost.
  `replayGate` also re-arms a verified pending APK when nothing is cached.
- **Warm resume**: `MainActivity.onResume()` calls the new
  `SelfUpdater.checkOnResume(this)`, which re-checks when **> 5 minutes** have passed since the
  last attempt (`RESUME_RECHECK_MS`), while the **15-minute failure backoff** still short-circuits
  an offline phone (no loop, no battery drain). A verified pending APK is re-armed, never
  re-downloaded. Auto-update OFF never disables the *check* — only the auto-download leg.

All of the above is now a **permanent, CI-killing lab gate**
(`v346dPermanentAutoUpdateLawChecks`, wired into `lab/run_lab.sh`), asserting the whole law:
no letter ceiling, cache-then-push, the `onListen` replay ordering, the cold-start branch
ignoring a persisted `lastCheck`, skipVersion/cooldown never suppressing the gate, the 5-min
resume cadence, the 15-min offline backoff, the 55 MiB − 1 cap, `PackageInstaller.Session`,
zero `Uri.fromFile`, Android 16 `PendingIntent` flags, the Unknown-Sources auto-resume, the
2-tier/zero-REST resolver, and the non-dismissible `ForceUpdateScreen` (`PopScope(canPop:false)`,
no Skip/Dismiss/Later, live progress + Retry).

### 1c. The blocking gate itself

- **New `ui/lib/anantplay/force_update_screen.dart`** — `PopScope(canPop: false)`,
  **no Skip / Dismiss / Later button anywhere**, the version badge
  (`v3.4.6.d → v3.4.6.e`), the 1-line notice, the single **Update Now** glass button
  streaming live `0% → 100%` + MB downloaded + `Verifying SHA-256…` + `Opening Installer…`,
  a failure reason + **Retry Update** (gate stays locked), and the unknown-sources
  1-tap guide with automatic install resume on `onResume`.
- **`main.dart` ShellRoot** — the moment a `gate:available` frame arrives with a strictly
  newer version, the entire viewport (tabs, onboarding, player) is replaced by
  `ForceUpdateScreen`; only `gate:uptodate` (offline / no update) unlocks it, so
  offline playback keeps working. An `initialGate` handoff arms the screen even when
  its own subscription attaches after the event (cold-boot pending-APK case).
- **`SelfUpdater` GateListener** — `available / progress(bytes,total,percent) / verify /
  installer / failed(reason) / uptodate` events pumped onto the `anantplay/updater`
  EventChannel. The skip-version choice and 24 h alert cooldown can no longer suppress
  the gate (they only govern notifications).
- **`MainActivity` `updateNow` handler** — pending verified APK → resume its install;
  otherwise re-run the full Tier-1/Tier-2 resolve → download → verify pipeline (the
  Retry lane).

### 1d. Live E2E proof — `lab/ReceiverLab.java` `v346dUpdaterE2eChecks` (12/12 PASS)

A real local HTTP mock serves the exact tier-1 `302 → /releases/tag/v3.4.6.e` +
`SHA256SUMS.txt` + the APK stream. Proven by execution: the alphabetical tag parses,
**0 `api.github.com` calls**, the exact
`.../releases/download/v3.4.6.e/AnantPlay-Sinhala-Player-v3.4.6.e.apk` URL derives,
SHA-256 verifies, a **tampered APK is refused**, `isNewer` orders
`3.4.6.d < 3.4.6.e < 3.4.7`, and the v3.4.6.c strict 3-segment gate is gone from the
shipped source. Flutter-side: `shell_test.dart` proves the gate blocks `HomeScreen`
entirely, has zero dismiss affordances, renders live 47% progress, and unlocks on
`uptodate`.

---

## 2. Defect #2 — fake buttons (mechanically killed, Phase 1)

**New gating auditor `lab/verify_flutter_bridge_wiring.py`** (wired into `lab/run_lab.sh`,
CI-killing):
- **R1** — every `invokeMethod('…')` in `ui/lib/anantplay/*.dart` must have a matching
  Java handler in `MainActivity.java` / `Media3TextureBridge.java` (`case "…":` or
  `"…".equals(call.method)`). The v3.4.6.c run exposed exactly the 2 dead channels:
  `enterPip`, `openExternal`.
- **R2** — zero empty `onPressed: () {}` / `onTap: () {}` stubs (comments stripped
  first). The v3.4.6.c run exposed the `MornyeFilterChip` stub.

**Fixes:**
- `MainActivity.java` — real `enterPip` (host `enterPictureInPictureMode`), real
  `openExternal` (VLC-first, chooser fallback), plus the Phase-3 handlers below.
- `player_screen.dart` — the Subtitles sheet's language chips now persist
  `K_DEFAULT_LANG` through the real settings bridge; "Free translate now" re-runs the
  real subtitle search instead of a dead `loadSubtitleUrl('')`.
- `Start/Stop Receiver` was audited live: the buttons invoke
  `ReceiverService.arm/stop`, the `anantplay/cast` EventChannel pushes the fresh
  status snapshot AND the MethodChannel result returns synchronously — the Flutter test
  suite asserts the toggle + label flip (`Active · Ready for Cast` ↔ `Stopped`).

---

## 3. Defect #3 — broken permission detection & wrong intents (Phase 3)

- **The wrong-intent bug** — onboarding slide 3 (Notifications) called
  `openOverlaySettings()`. Now it calls the new **`requestNotifications`** handler:
  API 33+ fires the native `POST_NOTIFICATIONS` runtime dialog **in-app**
  (`requestPermissions` + `onRequestPermissionsResult` → immediate status push);
  already-granted → instant green `Notifications Enabled ✓`; permanently denied →
  `ACTION_APP_NOTIFICATION_SETTINGS`.
- **Notification detection** — `Miui.notifGranted` = BOTH
  `NotificationManager.areNotificationsEnabled()` AND (API 33+)
  `checkSelfPermission(POST_NOTIFICATIONS)`.
- **Display over other apps** — `Settings.canDrawOverlays` live query + the new
  **`watchOverlayGrant`** bounded watcher: 500 ms polls while paused, 30 s lifetime,
  auto-brings `MainActivity` back to the front (`REORDER_TO_FRONT | SINGLE_TOP`) the
  instant the user flips the toggle, cleanly cancelled on destroy/timeout.
- **Background stability** — `isIgnoringBatteryOptimizations` live query; the in-app
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog on Android 16.
- **Official AnantPlay live status** — fresh `getPackageInfo("me.champdev.weblock", 0)`
  on **every `onResume`** (never cached): `pushOfficialVersion()` streams the exact
  installed version to the wizard (`AnantPlay v3.9.2 Installed ✓` — covered by test).
- **Interactive demos** — every hero takes a `replay` counter: tap = restart the demo
  AND trigger the action pill. Notifications = the glowing Cast pill
  (`Casting: The Odyssey → Tap to play`) sliding into a phone mockup with the 5-word
  why ("See casts when app is closed"); Overlay = the 2-frame story
  ("Tap Cast" → "Player opens by itself — no extra tap"); Background = the cast signal
  wave staying green; Sources = the 3-step flow (`1. Install AnantPlay` →
  `2. Add Index URL in Sources` → `3. Tap Cast`).

---

## 4. Defect #4 — ONE Mornye Cinema Player + headless Chromium browser, Google default (Phase 4)

- **One player:** `PlayActivity.onCreate` is now a **pure zero-UI router** — every
  external `ACTION_VIEW` / `ACTION_SEND` / cast `/play` / DLNA `SetAVTransportURI`
  payload forwards through `trampolineToShell` into `MainActivity` → the Flutter
  **`PlayerScreen`** (Media3TextureBridge over Media3 1.11.1 ExoPlayer +
  StreamHttpConfig 403-relay + per-host cookies + SubtitleStore + BridgeApi +
  FreeSubTranslator + LkSubFinder + WatchHistory), then `finish()`. `ReceiverService`
  routes `/play`, the takeover, and the tap-to-open notification to the shell — the old
  raw Java player UI can never appear again.
- **Headless browser:** `WebBrowserView(Activity, boolean headlessMode)` — the Flutter
  PlatformView factory passes `true`: the Java omnibar, nav buttons, tab strip, speed
  dial, and snackbar pills are all omitted (only the pure Chromium viewport renders),
  while 100% of the hardened settings survive (MIXED_CONTENT_NEVER_ALLOW, BridgeGuard
  Blob capture, StreamSniffer, ad/tracker blocker, reader dark, site JS automation).
  Sniffed streams push to the Flutter capture dock via the shell route pump.
- **Google default:** `AppPrefs.SEARCH_DEFAULT = SEARCH_GOOGLE`, `normalizeSearchEngine`
  round-trips all four engines (ddg was missing from the old accepted list — fixed),
  `WebBrowserView.HOME_URL = https://www.google.com/`, the Settings segmented row lists
  Google first and defaults to it. DuckDuckGo/Startpage/Brave remain one-tap options.

---

## 5. Defect #5 — UI lag & battery drain (Phase 5)

- **`RepaintBoundary`** isolates the native video `Texture` — HUD rebuilds and subtitle
  re-layers never repaint the movie layer.
- **Zero-lag position pump** — the 4 Hz frame ticks feed a `ValueNotifier<PlayerFrame>`;
  only the seek bar + time text rebuild (`ValueListenableBuilder` island). The whole
  `PlayerScreen` rebuilds solely on structural flips (play/buffer state, geometry, cue
  state) — v3.4.6.c called `setState` on the entire screen every 250 ms.
- **Zero blur while watching** — when `hudVisible == false`, the top glass bar, bottom
  glass bar, and all `MornyeGlassPanel`/`BackdropFilter` widgets are unmounted
  (`maintainState: false` semantics via conditional rendering), so no backdrop-blur
  shader executes over the video.
- **`TickerMode(enabled: _tab == N)`** wraps all four tabs — offstage ambient
  animation controllers stop ticking (proven by the new performance test: exactly one
  enabled gate, following the active tab).
- Player teardown releases ExoPlayer, the SurfaceProducer, and timers on
  `dispose()`/pause.

---

## 6. Defect #6 — 1-tap auto-copy troubleshoot (Phase 6)

`HelpScreen`: tapping **Run full app troubleshoot** runs all 10 real `FeatureProbe`
probes; the moment probe 10 lands on the stream, the complete privacy-safe report
(already passed through `PrivacyScrubber` — zero hardware model / IP / MAC / EUI-64 /
cookie / token / PII, IMDb IDs + ms timings preserved) is written to the Android
clipboard **automatically**, with the green confirmation pill
`"10/10 Troubleshoot complete — Privacy-safe report auto-copied to clipboard ✓"` and
the quick **Copy Again** + **Share** buttons below. (The 20 s watchdog timer is now
owned and cancelled in `dispose()`.) Proven by the auto-copy widget test.

---

## 7. What a next session can resume from

**Nothing is pending.** All 7 phases are complete; every gate is green on the final
tree. The only remaining step in the release pipeline is CI's own
`release-apk.yml`, which publishes the real signed `v3.4.6.d` APK + updates
`docs/latest.json` (deliberately left at `v3.4.6.c` on this branch, and
`ReceiverLab` accepts both `3.4.6.c` and `3.4.6.d` in `latest.json`).

---

## සාරාංශය (සිංහල)

**v3.4.6.d (30410)** — දුරකථනයේ හඳුනාගත් දෝෂ 7 ම නිවැරදි කර අවසානයි:

1. **යාවත්කාලීන කර්මාන්තශාලාව සුව කරන ලදී** — අකුරු අනුවාද පිළිගන්නා regex එක හේතුවෙන් `3.4.6.d` ස්ථාපනය අසාර්ථක වී Obtainium අවශ්‍ය විය; දැන් `a–e` රේඛාව සම්පූර්ණයෙන් පිළිගනී. අලුත් නිකුතුවක් ඇති මොහොතේම **සම්පූර්ණ තිරය අගුළුනා ForceUpdateScreen** (ආපසු බොත්තම අවහිර, Skip නැත, `Update Now` මගින් 0→100% බාගැනීම + SHA-256 තහවුරු කිරීම + ස්ථාපනය) — අන්තර්ජාලය නැති විට / යාවත්කාලීන නැති විට පමණක් අගුළු නොවේ.
2. **ව්‍යාජ බොත්තම් ඉවත්** — අලුත් `verify_flutter_bridge_wiring.py` දොරටුව හරහා මිය ගිය නාලිකා 2ක් (`enterPip`, `openExternal`) සහ හිස් `onTap` 1ක් හමුවී එම සියල්ල සැබෑ ක්‍රියාත්මක කිරීම් බවට පත් කළා (66/66 PASS).
3. **අවසර හඳුනාගැනීම සුව කරන ලදී** — දැනුම්දීම් පිටුව වැරදියට Overlay සැකසුම් විවෘත කළා; දැන් යෙදුම ඇතුළතම අවසර චීනය එලියයි, සැකසුම් වෙතින් ආපසු පැමිණි විගස ස්වයංක්‍රීයව හඳුනාගනී, සහ එක් එක් පියවර ම තේරුම්ගත හැකි ඇතුළත් වීඩියෝ පැහැදිලි කිරීම් ලැබේ.
4. **එකම වීඩියෝ වාදකයා + Google පෙරනම් සෙවුම** — පැරණි Java වාදකයා හා බ්‍රව්සර් තීරු තවදුරටත් නොපෙනේ; සියලු මාර්ග Mornye සිනමා වාදකයා වෙත යයි, සෙවුම් පෙරනම Google වේ.
5. **වේගය හා බැටරි** — වීඩියෝව `RepaintBoundary` එකක, 250ms සෑම ටික් එකකදීම සම්පූර්ණ තිර නැවත ගොඩනැගීම නැවතී, HUD සැඟවුණු විට blur ෂේඩර් නොක්‍රියාත්මක වේ, ක්‍රියාත්මක නොවන ටැබ් වල සජීවීකරණ නැවතේ.
6. **එක් තට්ටුවකින් උපකරණ වාර්තාව** — "Run full app troubleshoot" ඔබන මොහොතේම පෞද්ගලිකත්ව-ආරක්ෂිත වාර්තාව ස්වයංක්‍රීයව පසුරු පුවරුවට පිටපත් වේ ("10/10 … auto-copied ✓").
7. **සියලු දොරටු කොළයි** — run_lab.sh 3,257 PASS / 0 FAIL · flutter test 51/51 · analyze පිරිසිදුයි · compile_check 160 classes · මූලයේ ගොනු 7යි · `docs/latest.json` තවම `v3.4.6.c` (CI විසින් අත්සන් කළ `v3.4.6.d` ප්‍රකාශනයේදී යාවත්කාලීන වේ).
