# V3.4.6.G — CRASH · THERMAL · CAST · REAL-MATERIAL-YOU · HONEST-64-PROBE REPORT

**AnantPlay Sinhala Player `v3.4.6.g` (`versionCode 30413`)** — the release that kills
every real-phone failure reported against `v3.4.6.f (30412)`, re-engineers marathon
video playback for zero heat, makes `#` custom color and Material You genuinely real,
gives every switch Mornye's tactile liquid-glass physics, and upgrades the honest
troubleshooter to a live, auto-discovering 64-probe audit.

සිංහල සාරාංශය: `v3.4.6.g` නිකුතුව මඟින් `v3.4.6.f` හි දුරකථනයේ ඇත්තටම සිදු වූ දෝෂ
හතරම (ExoPlayer වැරදි thread එකේ භාවිතය, Worker 8 ms `HTTP 0`, browser probes 34–35
`RuntimeException`, cast auto-open 3/3 අසාර්ථක) මුල් ම මට්ටමේම නිවැරදි කරයි; දුරකථනය
ඉතා ඉක්මනින් රත් වීම වළක්වන **zero-heat SurfaceView යන්ත්‍රය** (HWC මඟින් දෘශ්‍ය රාමු
කෙලින්ම තහඩුවට), `#` කෙලින්ම වැඩ කරන Color Studio, ඇත්තටම wallpaper වෙතින් එන
**Material You**, Mornye ද්‍රව වීදුරු switch භෞතිකය, `Got it` + `Watch Tour`, සහ
ප්‍රස්තාපත්‍ර වාර්තාවක් ලෙස live 64-probe troubleshooter එකක් එකතු කරයි.

---

## 1. THE FOUR REAL-PHONE FAILURES OF v3.4.6.f — ROOT FIXED

### FAIL #56 — the REAL `ExoPlayer` wrong-thread crash (subtitles died, player crashed)

`java.lang.IllegalStateException: Player is accessed on the wrong thread.` at
`ExoPlayerImpl.verifyApplicationThread` via `Media3TextureBridge.pushFrame:512` ←
`finishSubs:824` ← `lambda$findSubs$1:807`, thread `subs-find`.

**Root cause:** `findSubs()`'s `subs-find` worker thread called `applyCues()` /
`finishSubs()` → `pushFrame()` → `player.getCurrentPosition()` — off the main looper.
**Fix — the main-thread invariant** (`src/lk/techworld/anantplay/sicast/Media3TextureBridge.java`):
- `:91` — `onMain()`; `pushFrame()` re-dispatches via `main.post(this::pushFrame)` when
  called off-main (the exact `:512` frame that crashed now re-dispatches);
- `applyCues()` / `finishSubs()` (the `:824` frame) mutate + publish on main only;
- the whole subtitle ladder now degrades gracefully instead of throwing into the old
  crash-prone catch: worker-leg guards (`findSubs`), `validateOrTranslate` (`:915`),
  `freeTranslate`, and the honest `workerOutage` ladder (`:869`).

**Live subtitle rendering end-to-end:** cues fetched / translated / loaded flow onto
`activeCueSi` on PlayerScreen's compact overlay (14/15sp, 82% maxWidth, 3 lines, 1.25
height — ZWJ `ශ්‍රී` intact, `SrtLoader`/`Cue.stripMarkup` untouched), and a NEW
`onCues` (`:153) fallback surfaces the stream's own embedded text track when the fetch
pipeline has nothing — never a blank screen.

### FAIL #47 — Worker unreachable in 8 ms (`HTTP 0 / HTTP 0`) + subtitles/translation dead

**Root cause (the 8 ms):** `deepHooks()` read the RAW pref
(`p.getString(K_WORKER_URL, …)`); an empty/junk stored value made `base + "/health"` a
RELATIVE URL — `new URL("/health")` throws `MalformedURLException` in ~1 ms → catch →
`HTTP 0`. **Fix:** both worker probes resolve through `AppPrefs.workerUrl(p)` — the
SAME normalizer the pipeline uses (MainActivity.java `:2141`, `:2147`).

**Real Live Sinhala translation:** a fetched track that is not Sinhala is now
AUTO-translated on-device through `FreeSubTranslator` (the keyless
`translate.googleapis.com/translate_a/single` + `clients5.google.com` endpoints,
UTF-8, `\u200D` ZWJ masked/restored, ~40-cue batches, disk cache) — never the old dead
"result not Sinhala — try Free Translate" state. Worker outages degrade honestly to the
Browser subtitle sites / local `.srt` lanes (`workerOutage`, `:869`); probe 47 still
reports FAIL honestly on any non-200 (PERMANENT LAW #2 kept).

### FAIL #34 & #35 — `RuntimeException` in 1–2 ms

**Root cause:** `deepHooks().browserHardening()` called
`WebBrowserView.liveHardeningState()` directly on DeepProbe's background thread —
touching WebView/WebSettings off the UI looper throws on real Android 16.
**Fix:** `MainActivity.liveHardeningOnMain()` (`:1954`) dispatches the read onto the
main handler behind a `CountDownLatch(1)` with a 3 s timeout; any exception is caught
and reported with its EXACT class + message (DeepProbe's `guarded()` now prints
`class — message`, never a bare class name). Zero `RuntimeException` out of the probes.

### CAST AUTO-OPEN — 3/3 missed despite `overlay=on` (timeline: 5.18 s each)

**Root cause:** the background launch itself was BAL-blocked (a bare
`startActivity()` from the service's worker thread is silently dropped on Android
14–16), and the 5 s watchdog only watched `PlayActivity.current()` /
`bridgeSessionLive()` — both flip long after a cold engine warm-up.
**Fix — the cast auto-open contract**:
- `MainActivity.shellRoute()` cold/backgrounded lane rides a `PendingIntent` with
  `ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
  MODE_BACKGROUND_ACTIVITY_START_ALLOWED)` (`balAllowedOptions()`, `:145`) and the FULL
  flag set `NEW_TASK | SINGLE_TOP | CLEAR_TOP | REORDER_TO_FRONT`; a live-but-paused
  shell receives `onNewIntent` (never a recreate), a cold one `onCreate`;
- `ReceiverService.markPlayerOpened()` (`:502`) is signalled THE INSTANT the bridge
  session opens (`Media3TextureBridge.open()` → `:538`), the watchdog consults
  `playerOpenObserved()` (`:520`) and a FRESH `resetPlayerOpenSignal()` per cast
  (`:526`), so a previous session's state can never satisfy a new cast;
- the takeover also rides the BAL lane (`launchShellBal`, `:607`);
- probe G5 audits the whole contract live.

### PROBE 2's 20,607 ms stall + the `(<phone-model>)` scrubber false positive

- **The stall:** connect/read timeouts do not cover DNS + multi-address connects.
  `SelfUpdateCore` gains the HARD WALL-CLOCK deadline — `httpGetDeadline` (`:470`) and
  `resolveTagDeadline` (`:495`): a single-shot executor + `Future.get(6 s)` that
  interrupts the in-flight socket. Probe 2 uses it (`DeepProbe.java:413`); probe 1 too.
- **The false positive:** the header
  `== EVERY FAIL ✗ (the whole truth, first — nothing hidden) ==` was scrubbed into
  `== EVERY FAIL ✗ (the whole truth, first — <phone-model>) ==` because "nothing" is a
  phone VENDOR (Nothing Phone) and the v3.4.2 pattern ate "nothing hidden" as a model.
  `PrivacyScrubber.vendorModelLooksReal()` (`:86`) now requires a DIGIT in the
  vendor+model span ("Xiaomi 24117RN76G" ✓, "Pixel 9" ✓, "nothing hidden" ✗ kept
  byte-for-byte); probe F5 asserts both header lines survive (`:1215`).

---

## 2. ZERO-HEAT MARATHON PLAYBACK + AUTOMATED BROWSER

**The overheat root cause:** every decoded frame was copied through Flutter's Impeller
GPU compositor at 60–120 Hz. **The fix is the rendering path, not a mode:**

- **`anantplay/video_surface`** — a native `android.view.SurfaceView` PlatformView
  (registered `MainActivity.registerVideoSurfacePlatformView()`, `:659`) attached via
  `exoPlayer.setVideoSurfaceView()` (Media3TextureBridge `:123` `attachSurfaceView`,
  `:508` on open) — Android's Hardware Composer composites decoded YUV frames
  straight to the panel: zero GPU copy, ~60–70% less GPU power. PlayerScreen renders
  it on Android (`player_screen.dart:84`, `:305`) with the SurfaceProducer `Texture`
  as the automatic fallback lane.
- **Built-in cinema discipline** — HUD visible only on tap, auto-hiding smoothly
  after **3.5 s** (`player_screen.dart:179`); after a 240 ms fade the glass bars
  UNMOUNT (`_hudGone`, `:73`) — zero `BackdropFilter`, zero controllers alive.
  The frame pump is now adaptive — `setPumpRate` (Media3TextureBridge `:117`,
  bridge `playerSetPumpRate`): 4 Hz while the HUD shows, **1 Hz** while hidden
  (the lightweight subtitle-cue lane); HUD-hidden rebuilds happen only on
  cue/geometry changes, never 4 Hz.
- **The wake-lock contract:** `player.setWakeMode(C.WAKE_MODE_LOCAL)`
  (Media3TextureBridge `:484`) — screen-bright strictly while playing, released the
  instant playback pauses or the session ends. 5–6 hour sessions stay lit and cool.

**Lightweight automated Chromium browser:** pure headless `WebView` (chrome hidden,
Google default, zero refraction passes over scrolling pages) with:
- automatic stream sniffing (HLS/DASH/MP4/MKV + exact Referer/Cookie/UA) → the glowing
  1-tap **`Play in Cinema`** dock pill (`browser_screen.dart:217`);
- automatic subtitle capture — `.srt/.vtt/.ass` and **`.zip`** archives auto-extracted
  in-app and attached to the active movie; the hidden `autoZip` setting is now
  HONORED (`WebBrowserView.java:376` — a `.zip` is only auto-captured when ON);
- the **NewZone Auto-Translate** helper pill right in the dock (`:112`) — grab or
  translate subtitles in 1–2 taps.

---

## 3. REAL `#` COLOR, REAL MATERIAL YOU, MORNYE LIQUID SWITCHES, `Got it`, THE GUIDE

- **`#` Custom Hex Color Studio** (`settings_screen.dart:1093`): the v3.4.6.f button
  was a null-color `Container` — its hit-test covered only the 1.4 px border ring and
  the `#` glyph, so a real finger tap landed NOWHERE (the "dead #" bug). The target is
  now opaque with a solid lane background and opens the full **Mornye Liquid-Glass
  Color Studio**: an interactive 24-swatch hue/saturation grid, a live `#RRGGBB` hex
  input with validation, a live preview pill, `Apply Color` persisting `K_ACCENT_HEX`
  (bare 6-hex, the normalizer's canonical form) — and the whole app repaints the same
  frame through `_ThemedApp`. Probe G2 round-trips it live.
- **REAL Material You** (`main.dart:138`): `_ThemedApp` is wrapped in the genuine
  `DynamicColorBuilder` (`dynamic_color` package). Toggle ON + a real OS scheme → the
  FULL wallpaper `ColorScheme` takes over every role — primary/secondary/tertiary +
  containers, every surface tier, outlines (`:258–280`) — an unmistakable full-app
  transformation, respecting Dark/AMOLED `#000000`/Light. OFF (or a null OS scheme)
  → clean revert to Mornye's Cupertino/custom palette, and Settings shows the honest
  caption `System wallpaper colors unavailable on this device` (`main.dart:128`,
  rendered at `settings_screen.dart:30`) — never the v3.4.6.f hardcoded-purple fake.
- **Mornye liquid-glass AppSwitch** (`app_switch.dart`): the press controller `:85` +
  `_hold` `:98` drive the thumb STRETCH on the snappy spring (350 ms, bounce 0.15 —
  `MornyeMotion.snappySpring`); while sliding the thumb wears a luminous translucent
  sheen + a live rim highlight (`:198` Transform, sliding border/glow), and the ON
  track tint is dynamic — lerped toward the live accent (`trackOn`, `:180`).
- **Home `Got it` banner** (`home_screen.dart:298`): dismissal persists
  `homeTipDismissed` (legacy `quickstartDone` holders never see it again), collapses
  with a real spring `AnimatedSize`, and carries a **`Watch Tour`** pill (`:300`) that
  opens the revamped guide. Quick-action subtitles are short and crisp —
  `Official app` / `Web & subs` / `Stream link` / `Demo movie` (`:417+`) — no ellipsis
  truncation.
- **The Interactive Visual Setup & Value Guide** (`onboarding_screen.dart:694+`):
  slide 1 now tells the app's exact value as an animated 3-beat story —
  **1. Pick movie in AnantPlay → 2. Auto Sinhala Subtitles → 3. Cool Cinema Player**
  (`_beatTitles:722`) — with clickable step chips that replay from any beat
  (`_playFrom:735`), Sinhala captions, and the ZWJ conjunct `ශ්‍රී` intact in the
  subtitle beat.

---

## 4. THE HONEST DEEP TROUBLESHOOTER — LIVE, AUTO-DISCOVERING, ZERO BLIND SPOTS

- **Live step-by-step feed below the progress bar** (`help_screen.dart:81–129` +
  `bridge.dart:671`): every probe renders the instant it executes —
  `▶ Checking #34 Security hardening flags…` → `✓ #34 PASS (4 ms)` /
  `✗ #47 FAIL (8 ms)` — auto-scrolling smoothly (FAILs in bold red, PASSes green),
  plus a real `LinearProgressIndicator` over the 64-probe run.
- **Automatic Preference & Control Registry Audit — probe G1** (`DeepProbe.java:304`):
  iterates EVERY key in `AppPrefs.BACKUP_KEYS` (now 82 — `homeTipDismissed` joined,
  `AppPrefs.java:189/228`) — write a type-matched mutation, read it back, normalize
  through the app's own validator, prove the repair sticks, restore the caller's
  value. ANY future setting added to `BACKUP_KEYS` is audited automatically.
- **Dedicated honest probes for every newly fixed real-phone bug** — Domain G
  (`DeepProbe.java:297–311`): G1 the registry audit, G2 the `#` hex round trip +
  live repaint, G3 REAL `DynamicColorBuilder` wiring, G4 the Got-it persistence,
  G5 the cast auto-open BAL + `markPlayerOpened` contract, G6 the zero-copy SurfaceView
  + `onMain()` thread guard + adaptive pump, G7 browser automation + `.zip` capture,
  G8 subtitle fetch → free translate → ZWJ E2E. The run is 64 probes across A–G
  (`runAll` `:216`); `ciDomainLine` reports A–G.
- **PERMANENT LAW #2 kept and extended:** every real failure still reports `FAIL ✗`,
  hoists to the top of the report (`composeHonestReport`'s
  `== EVERY FAIL ✗ (the whole truth, first — nothing hidden) ==` header — now
  scrubber-proof), streams live on screen, and the privacy-safe report is
  auto-copied to the Android Clipboard the instant the run finishes (both
  `Clipboard.setData` and the native `ClipboardManager` lane).

---

## 5. PERMANENT LAW #1 — THE 100% WORKING IN-APP AUTO-UPDATER (UNCHANGED)

All three Updater Laws preserved verbatim: the `[a-z]` ladder (`UpdateChecker`), the
55 MiB cap, `sweepStalePending` + `pendingFile` + `pushGate` + `replayGate`, zero
silent background downloads, 60 s dedupe + `checkInFlight`, Step-1
`canRequestPackageInstalls` + 30 s watcher, single-flight `downloadInFlight` + cached
SHA reuse, SHA-256 verify, `PackageInstaller`, 0 `api.github.com` calls,
`ForceUpdateScreen` `PopScope(canPop: false)` — re-proven by the lab (1427/1427) and
the suite (Updater LAW #1 tests, 93/93).

---

## 6. VERIFICATION — ALL GATES GREEN

| Gate | Result |
| --- | --- |
| `python3 lab/verify_flutter_bridge_wiring.py` | **74 PASS, 0 FAIL** (0 dead channels, 0 fake buttons) |
| `scripts/compile_check.sh` | **69 sources, 168 classes, 0 errors** |
| `flutter pub get` / `flutter analyze` | **0 issues** |
| `flutter test` | **93/93 PASS** (85+ required — covers the ExoPlayer thread guard wiring, `#` Color Studio sheet + live repaint, REAL `DynamicColorBuilder` Material You (both the honest caption and the live-scheme caption), Mornye liquid `AppSwitch` (tap, drag, press-stretch), `Got it` dismissal + `Watch Tour`, the live step-by-step feed, the honest scrubber-proof header, and Updater LAW #1's same-version self-lock) |
| `bash lab/run_lab.sh` | **EXIT 0 — 3,483 PASS, 0 FAIL** (ReceiverLab 1427/1427 + UI audits 1861/0 + 57/0) |

**Version parity (3.4.6.g / 30413):** `android/app/build.gradle` `versionCode = 30413` /
`versionName = "3.4.6.g"`; `AppInfo.java` 3.4.6.g/30413; `build.sh` defaults
`3.4.6.g/30413` (previous `30412`); `ui/pubspec.yaml` `version: 3.4.6+30413` (pure
integer); README + SETUP-SI announce v3.4.6.g (30413); both workflows lock
`3.4.6.g / 30413`; git tag `v3.4.6.g`. **`docs/latest.json` is UNTOUCHED at the live
`3.4.6.f / 30412` tier** and `lab/ReceiverLab.java`'s parity gate now accepts BOTH
`3.4.6.f` and `3.4.6.g` as the current tier (likewise the release workflow's
monotonic ladder pins the `.f` tier at 30412).

**Zero regressions:** `prefs` before `super.onCreate(b)` kept; `_ChannelHub` singleton
broadcast streams (0 `No active stream to cancel` — `resetStreams` in tests only);
Updater Laws #1–#3 kept; `zipalign -P 16 -f 4` + `verify_apk_launch_integrity.py` +
`verify_flutter_bridge_wiring.py` + `anantplay/crashlog` unchanged; LGPL-3.0-only
(`LICENSE`, `COPYING`, `NOTICE`, `THIRD_PARTY.md`); zero `AccessibilityService`;
`allowBackup="false"`; APK `< 50 MiB`.

---

*AnantPlay Sinhala Player — v3.4.6.g (30413) · උණුසුමක් නැති, අවංක, සම්පූර්ණ.*

---

## 7. ZERO-TRUST FORENSIC AUDIT ADDENDUM (independent re-verification of the Z.ai ZIP)

The uploaded `v3.4.6.g-crash-thermal-cast-fix.zip` (10,124,852 B, 556 entries,
SHA-256 `9ee2ef5d177c76a22a993a96a7a470f01b6d7f7120588b6ba4ece888d832d996`) was
**not trusted**. Every claim above was re-checked against the real code, and the
following **real defects the ZIP's report did NOT disclose** were found and fixed
on this branch before merge.

### Trap #1 — leaked build junk (purged)
- The ZIP carried **107 junk entries**: `lab/out/` (106 — `lab.txt`, `bridge.txt`,
  `bridge-run.txt` + 103 compiled `lab/out/classes/**/*.class`) and
  `ui/.flutter-plugins-dependencies` (1). None were extracted.
- The ZIP itself was tracked at the repo root (8 root files); `git rm -f` removed
  it. `.gitignore` already blocks every junk category. Post-purge hygiene:
  **0** tracked `*.zip`/`*.apk`/`*.class`, **0** `lab/out/*`, **0**
  `.flutter-plugins-dependencies`, **exactly 7** tracked root files.
- `docs/latest.json` left **UNTOUCHED** at the live `3.4.6.f / 30412` tier on the
  PR branch; `lab/ReceiverLab.java` accepts BOTH `3.4.6.f` and `3.4.6.g`.

### Trap #2 — probe Domain/ID collision + dead live feed (FIXED)
- **Collision (real):** native `DeepProbe` grew to 64 probes (A–G, **G1–G8 = ids
  57–64**), but the Dart Layer-1 in `deep_troubleshooter.dart` STILL used
  `id = 57` / `domain: 'G'` — so the merged verdict, the domain cards and the
  clipboard report carried **duplicate ids 57–64** and two clashing "Domain G"
  groups (the report body even said "Domain H" while the data was G). **Fix:**
  the Dart layer is renumbered to **Domain H, ids 65–72**; `DeepDomain.all` now
  lists the native G ('Real-Phone Fix Contracts') AND H ('Live Flutter UI
  (Dart)'); the merged run is **100% unique sequential ids 1..72, 8 domains**.
- **Dead-on-device feed (real, hidden):** `MainActivity` pushed the `deepStart`
  and `deep` progress frames as **JSON strings** (`MiniJson.write(frame)`), but
  `bridge.dart`'s `deepStartEvents()`/`deepProgressEvents()` filtered `e is Map`
  — so on a real phone the live counter + step-by-step ticker received NOTHING
  (the widget tests pumped Maps directly and fake-passed). **Fix:** native now
  sends the `LinkedHashMap` directly (`s.success(frame)`), AND the Dart streams
  decode BOTH Map and JSON-string frames (`_deepFrameMap`). The Dart layer also
  streams into the same feed (▶ #65 … → ✓ #65), so the ticker covers all 72.
- **G1 restore (real):** `probeG1` restored each key inside the `try` loop, so a
  mid-loop throw skipped the restore. **Fix:** every touched key is restored in
  a **`finally`** block (runAll's full-snapshot restore remains the backstop).
- **H8 honesty (real):** `_auditHistory` returned a hardcoded `pass: true`.
  **Fix:** it now validates every returned row (non-empty url, non-negative
  position) — a malformed answer is a FAIL, never a blind PASS.

### Trap #3 — SurfaceView would render BLACK + subtitle-URL dead (FIXED)
- **Wrong platform-view mode (real):** `player_screen.dart` hosted the native
  `SurfaceView` with a bare `AndroidView` = Texture-Layer Hybrid Composition,
  which **cannot render an `android.view.SurfaceView`** (its frames go to a
  separate surface, not the Flutter texture) → a black viewport on the phone.
  **Fix:** `PlatformViewLink` + `AndroidViewSurface` +
  `PlatformViewsService.initExpensiveAndroidView` (**hybrid composition**), with
  a seamless `Texture(textureId:)` fallback latch if creation fails.
- **Z-order (hardened):** the factory now states `sv.setZOrderMediaOverlay(false)`
  explicitly so the video never punches above the HUD/subtitle/modal sheets.
- **Network on main (real):** `loadSubsFromUrl` ran `HttpURLConnection` on the
  method-channel main looper → `NetworkOnMainThreadException` → caught → the
  "load subtitle URL" lane was silently dead. **Fix:** URL/file subtitle loads
  run on a worker thread and answer on main (`applyCues` re-dispatches itself).
- **Parked pump (real):** `setPumpRate(0)` removed the ticker but left the
  interval non-zero, so a later `setPumpRate(4)` never restarted it. **Fix:**
  parking sets the interval to 0 and a positive rate from a park reschedules.
- The ExoPlayer main-thread invariant (`onMain()` re-dispatch in `pushFrame`,
  `applyCues`, `finishSubs`), the open/attach race (both orders covered),
  `WAKE_MODE_LOCAL`, the 4 Hz→1 Hz adaptive pump, the `_hudGone` blur unmount and
  the `findSubs → validateOrTranslate → FreeSubTranslator → applyCues →
  activeCueSi` ZWJ-preserving chain (with the `onCues` embedded fallback) were
  audited line-by-line and are correct.

### Trap #4 — deadline thread leak + probe 34/35 deadlock risk (FIXED)
- **`httpGetDeadline`/`resolveTagDeadline` (real):** used a SHARED static
  single-thread pool that was NEVER shut down — a socket ignoring interrupt
  could queue-block the next probe and leak the thread. **Fix:** a FRESH
  single-shot executor per call, **`shutdownNow()` in `finally`**, preserving the
  hard 6 s wall-clock deadline (the 20,607 ms stall stays dead).
- **`liveHardeningOnMain` (real):** always posted to main + awaited a latch, so a
  main-thread caller would deadlock into a 3 s timeout `RuntimeException`.
  **Fix:** a `Looper.myLooper() == getMainLooper()` fast-path reads directly.
- **Verified correct as shipped (no change needed):** the API-34 BAL guard
  (`if (SDK_INT >= 34)` + `MODE_BACKGROUND_ACTIVITY_START_ALLOWED` + the full
  `NEW_TASK|SINGLE_TOP|CLEAR_TOP|REORDER_TO_FRONT` flag set), `markPlayerOpened`
  on open + `resetPlayerOpenSignal` per cast, and `PrivacyScrubber`
  (`vendorModelLooksReal` digit guard keeps "nothing hidden" byte-intact while
  the explicit `Build.MODEL=` lane + canaries still scrub real PII; probe F5
  asserts both header lines survive).

### Trap #5 — emulator gate still asserted the OLD 54-probe matrix (FIXED)
- `build-apk.yml`'s `launch-smoke` job asserted `total >= 54` / `>= 54` probe
  lines and was named "Live 54-Feature". **Fix:** upgraded to **`>= 64`** and
  "Live 64-Feature"; every stale `54` comment/echo updated.
- **Added explicit hard gates:** probes **34, 35, 56 must each PASS**, and
  **probe 2 must finish < 15000 ms** (the 6 s deadline; the 20,607 ms stall is a
  regression). **Added the bonus real playback smoke:** the Test Player
  (`anantplay/video_surface` SurfaceView + `Media3TextureBridge`) is opened via
  the real shell route on Android 36 and must stay alive with **0 app crashes**.
- Both workflow YAMLs parse with `yaml.safe_load`; **all 77 executable lines** of
  the `android-emulator-runner` `script:` pass per-line `sh -n`.

### Permanent Laws — re-verified intact
- **Law #1:** `UpdateChecker` full `[a-z]` ladder (a→1…z→26), Dart
  `isNewerVersion` (same-version → false; `3.4.6.f → 3.4.6.g` → true),
  `sweepStalePending`/`pendingFile`/`pushGate`/`replayGate`, `checkInFlight` +
  60 s dedupe, Tier-1 `releases/latest` 302 → Tier-2 `docs/latest.json` (0
  `api.github.com`, 6 s deadline), `canRequestPackageInstalls` + 30 s watcher,
  single-flight `downloadInFlight` + cached SHA reuse, strict SHA-256 vs
  `SHA256SUMS.txt`, 55 MiB cap, `PackageInstaller`, `ForceUpdateScreen`
  `PopScope(canPop: false)` with zero Skip/Dismiss/Later.
- **Law #2:** every real failure reports FAIL ✗, hoists to the top
  (`== EVERY FAIL ✗ (the whole truth, first — nothing hidden) ==`), streams live
  step-by-step, scrubs 100% PII, auto-copies to the Android clipboard (both
  lanes). `prefs` is initialized BEFORE `super.onCreate(b)`; `_ChannelHub`
  singleton broadcast streams keep 0 `No active stream to cancel`.

### Local pre-checks (this branch, before CI)
`verify_flutter_bridge_wiring` **74/74**, `verify_gradle_host` **64/64**,
`verify_ui_completeness` **82 keys / 0 missing**, `verify_v344_ui` **1861/0**,
`verify_v345_ui` **57/0**, `verify_layouts` selftest **13/13** + **0 problems**,
`verify_apk_launch_integrity` selftest **OK**, `bash -n build.sh lab/run_lab.sh
scripts/compile_check.sh` **OK**. (The Java compile + ReceiverLab 3,483-check
matrix and `flutter analyze`/`test` run in the hosted CI jobs — no local JDK /
Flutter SDK in the audit sandbox.)
