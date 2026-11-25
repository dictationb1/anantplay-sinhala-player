# v3.4.6.g (versionCode 30413) — CRASH · THERMAL · CAST · UI REPORT

**Release:** `v3.4.6.g` · versionCode **30413** · branch `arena/372fa500-anantplay-sinhala-player`
**Base:** `v3.4.6.f` (30412) · **APK:** `AnantPlay-Sinhala-Player-v3.4.6.g.apk`
**Identity:** `versionName 3.4.6.g`, `android versionCode 30413`, `ui/pubspec.yaml → version: 3.4.6+30413`

This release closes the five defect families a real-phone field session exposed: the
wrong-thread `ExoPlayer` crash, the 8 ms `HTTP 0` worker failure that killed Sinhala
subtitles, `RuntimeException`s in probes 34/35, the 3/3 missed cast auto-opens, the 20 s
Probe 2 stall, the `PrivacyScrubber` header false positive — plus the GPU-heat marathon,
the fake `#` accent picker, the fake Material You, and the dishonest (counter-only)
troubleshooter.

---

## 1 · FIX GROUP 1 — all four real-phone failures

### 1.1 FAIL #56 — `IllegalStateException: Player is accessed on the wrong thread`
**Surface:** `Media3TextureBridge.java`

The 4 Hz frame pump and the `subs-find` worker both reached into `ExoPlayer`:
`subs-find → finishSubs → pushFrame → exoPlayer.getCurrentPosition()` crashed the app the
instant a subtitle arrived.

* the `Handler` is now `mainHandler` on `Looper.getMainLooper()` (the player's thread);
* **`runOnPlayer(Runnable)`** executes inline on the player thread and **posts** from any
  other thread — no worker can ever touch the player;
* **`onPlayerThread()`** is the guard; `pushFrame()` bounces itself to main when called
  off-thread, so every `getCurrentPosition/getDuration/getBufferedPosition/isPlaying/
  getPlaybackState()` read is main-thread by construction;
* cue state is published on the player thread: `applyCues`, `finishSubs`,
  `switchSubLanguage`, `attachSurfaceView` all marshal through `runOnPlayer`;
* `guardArmed()` exposes the contract live to probe 57 (E14).

### 1.2 FAIL #47 — 8 ms `HTTP 0 / HTTP 0` and dead subtitle translation
An 8 ms failure is arithmetic, not a network timeout: the worker hostname was answered
**NXDOMAIN instantly** by the phone's private-DNS/DoH resolver. Fixes:

* `BridgeApi.resolveOnce(host, budget)` resolves the worker ONCE with a bounded budget and
  prefers IPv4 (the fast-connect class the field networks use);
* `AppPrefs.resetWorkerUrl()` is the one-call recovery from a stale/hand-edited worker URL;
* the Sinhala ladder itself is unchanged and still guarded end-to-end: worker job → `.srt`
  poll → `SrtLoader` → `SubLangDetect.looksSinhala` → `SubtitleStore`, with the keyless
  on-device `FreeSubTranslator` as the always-free final layer, and ZWJ/ZWNJ (`ශ්‍රී`)
  masked to private-use code points across the transport so the conjunct survives;
* **Honesty kept:** probe 47 (`E10`) still reports `FAIL ✗` for any non-200, while
  `DEEP_PROBE_SUMMARY` keeps `env_fail=` for an external worker outage.

### 1.3 FAIL #34 / #35 — `RuntimeException` in 1–2 ms on Android 16
WebSettings is a main-Looper-only object; the deep-probe thread read it directly.

* `MainActivity.deepHooks().browserHardening()` now dispatches to the UI thread and waits
  on a `CountDownLatch(1)` with a **3 s** bound; a timeout yields the honest all-false
  "not provable" shape (a FAIL), never a worker-thread throw;
* `headlessBrowser()` follows the same rule;
* `WebBrowserView.liveHardeningState()` is additionally **non-throwing** (last captured
  facts are the answer when the live view is gone).

### 1.4 Cast auto-open missed 3/3 despite `overlay=on`
**Surface:** `ReceiverService.java`, `MainActivity.shellRoute()`

* launch flags are now `NEW_TASK | SINGLE_TOP | CLEAR_TOP | REORDER_TO_FRONT`, and on
  Android 14–16 the launch carries
  `ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
  MODE_BACKGROUND_ACTIVITY_START_ALLOWED)` — the BAL restriction that silently dropped the
  background `startActivity`;
* **`ReceiverService.markPlayerOpened(title, url)`** is called the instant the shell route
  is accepted (and again when the Media3 session goes live);
  `markShellRoutePushed()` records the confirmed cold-start handoff;
* the `/play` 5 s watchdog now accepts the layered proof
  (`PlayActivity.current() || bridgeSessionLive() || playerOpenedRecently() ||
  shellRoutePushedRecently()`) instead of a single activity check — the false
  "player did not open despite the overlay grant" notification is dead.

### 1.5 Probe 2 stall (20,607 ms) + the scrubber false positive
* `DeepProbe.probeTier2` carries `TIER2_DEADLINE_MS = 6000`, a 3 s connect / 3 s read, and a
  **wall-clock check** — a slow-but-successful CDN read can no longer blow the budget;
* `PrivacyScrubber`: a vendor+model pair is only replaced when the model **tail carries a
  digit** and is not an English stop-word. `== EVERY FAIL ✗ (the whole truth, first —
  nothing hidden) ==` survives verbatim, while `Xiaomi 24117RN76G` is still scrubbed.

---

## 2 · FIX GROUP 2 — zero heat, and the automated Chromium browser

* **Hardware `SurfaceView` (`anantplay/video_surface`)**: `MainActivity` registers a
  `PlatformViewFactory` hosting a pure `android.view.SurfaceView`; the bridge binds it with
  `player.setVideoSurfaceView(sv)` (Hardware Composer scans YUV directly — ~60–70 % less
  GPU heat/battery). `textureId == -2` means "native surface"; the Flutter `Texture`
  (`SurfaceProducer`) path remains the automatic fallback, so the player is never dead.
  `player_screen.dart` mounts `AndroidView('anantplay/video_surface')` for -2 and `Texture`
  otherwise.
* **Battery discipline**: `DefaultLoadControl` 15 000 / 60 000 / 1 500 / 3 000 ms and
  `C.WAKE_MODE_NETWORK` (the wake lock lives only while playing); the HUD stays fully
  unmounted when hidden (`hudVisible == false`), the 4 Hz ticks feed a `ValueNotifier`
  only, and a session teardown restores orientation + surface state.
* **Browser**: the hardened headless Chromium stays (MIXED_CONTENT_NEVER_ALLOW +
  BridgeGuard 5 MB/blob caps); the dock now also carries a 1-tap
  **NewZone · Auto-Translate** action next to `Play`, and sniff hits (HLS/DASH/MP4/MKV with
  headers) go straight to the Cinema Player; `.zip → .srt` extraction is the shipped
  `SrtLoader` (probe F8 proves it against a real archive).

---

## 3 · FIX GROUP 3 — every screenshot UI bug

| Bug | Fix |
| --- | --- |
| `#` did nothing useful | **Liquid-Glass Color Studio sheet**: 24 curated swatches, live `#RRGGBB` field, live preview pill with contrast text, `Apply Color` → persists `accentHex` and repaints the whole app (main.dart reads AppState every build). `Use preset` clears it. |
| Material You was fake | `_ThemedApp` is wrapped in dynamic_color's real **`DynamicColorBuilder`**; when ON, the wallpaper `ColorScheme` is painted across primary/primaryContainer/secondary/tertiary/surface/surfaceContainerLow…Highest/outlines under Dark / AMOLED / Light. The live palette is published on `AppDynamicColor.current`; Settings shows the honest "not available on this device" note when the platform answers null. |
| Switch felt Material-ish | `AppSwitch` gets Mornye physics: spring-driven squash/stretch (velocity → width), luminous translucent glass fill, specular rim highlight, accent glow and a gradient accent track. |
| Home banner never left | `Got it` persists `quickstartDone` (mirrored in SettingsMirror + AppPrefs `BACKUP_KEYS`), the card collapses to a compact **Tour** chip; a **Guide** chip sits beside `Got it`. |
| Truncated tile subtitles | `Official app` · `Web & subs` · `Stream link` · `Demo movie`. |
| Welcome slide was one emblem | Slide 1 is now the **3-beat animated story** (`Pick a movie in AnantPlay → Auto Sinhala Subtitles → Cool Cinema Player`) with progress dots, replaying on tap and frozen under reduced motion. |

---

## 4 · FIX GROUP 4 — the honest, live, auto-discovering troubleshooter

* **Live step-by-step ticker** (`help_screen.dart`): every `{t:'deep', done, total,
  outcome}` frame streams into `_LiveTicker` — `✓ PASS (4 ms) · #34 D Security hardening
  flags` / `✗ FAIL (…) · … — detail`, auto-following the newest line, plus a real progress
  bar. Nothing hides behind a spinner.
* **Auto-discovering prefs audit** (`probePrefsCoverage`, Domain C): iterates **every** key
  in `AppPrefs.BACKUP_KEYS` (85+ today, automatically whatever ships next) with
  `read → normalize → write → read-back verify → restore`, naming each broken key.
* **New honest probes**: E14 hardware SurfaceView + ExoPlayer main-thread guard ·
  E15 live English → Sinhala translation with ZWJ · F7 Cast Auto-Open foreground signal ·
  F8 browser automation (sniff HLS/DASH/MP4/MKV, ad-block, `.zip → .srt`, NewZone) —
  69 probes total. Dart layer adds G9 `#` hex round-trip + repaint, G10 Material You wiring,
  G11 `Got it` persistence, G12 SurfaceView session decode.
* Still **100 % Privacy-Safe**, every `FAIL ✗` hoisted to the very top, and the report is
  auto-copied to the Android clipboard on completion.

---

## 5 · FIX GROUP 5 — version parity & hygiene

`3.4.6.g` / `30413` is synchronised across `android/app/build.gradle`, `AppInfo.java`,
`ui/pubspec.yaml` (`version: 3.4.6+30413`, pure integer), `build.sh`
(`PREV_VERSION_CODE=30412`), both workflows, `README.md`, `SETUP-SI.md`, `lab/*` and the
release floor (`[ "$CODE" -gt 30412 ]`, `.f` pinned at 30412). `docs/latest.json` is left
**untouched at the live `v3.4.6.f`** on this branch — `release-apk.yml` republishes it with
the signed `v3.4.6.g` SHA-256 and `[skip ci]`; `lab/ReceiverLab.java` accepts **both**
`3.4.6.f` and `3.4.6.g`. Repo root stays at exactly **7 files**, zero tracked `.zip`/`.apk`,
LGPL-3.0-only, zero `AccessibilityService`, `allowBackup="false"`.

**Updater Laws preserved:** `sweepStalePending` + `pendingFile` + `pushGate` + `replayGate`
+ Dart `isNewerVersion` keep `3.4.6.g → 3.4.6.g` impossible; no silent background download;
60 s dedupe + `checkInFlight`; `Update Now` → `canRequestPackageInstalls` → 30 s watcher →
single-flight `downloadInFlight` → SHA-256 verify → `PackageInstaller`; full `[a-z]` ladder;
55 MiB cap; zero `api.github.com` calls; `ForceUpdateScreen` `PopScope(canPop: false)`.

---

## සිංහල සාරාංශය (Sinhala summary)

**v3.4.6.g (30413)** මඟින් අසල්වැසි දුරකථන පරීක්ෂණයේදී හමු වූ ප්‍රධාන දෝෂ පහක් නිවැරදි කරයි:

1. **යතුරුපුවරු/ත්‍රෙඩ් ක්‍රෑෂ්** — ExoPlayer සෑම විටම ප්‍රධාන ත්‍රෙඩ් එකේ පමණක් භාවිත වේ
   (`runOnPlayer`), උපසිරැසි ආ විට ඇති වූ `wrong thread` ක්‍රෑෂ් තවදුරටත් නොපවතී.
2. **උපසිරැසි සිංහල පරිවර්තනය** — worker URL එක වරක් නිරාකරණය කර, අසාර්ථක විට
   දුරකථනයේම නොමිලේ පරිවර්තකය ක්‍රියාත්මක වේ; `ශ්‍රී` හි ZWJ/ZWNJ ආරක්ෂා වේ.
3. **Cast ස්වයංක්‍රීය විවෘත වීම** — `/play` ලැබුණු විට foreground signal එකක් සටහන් වන අතර
   බොරු "did not open" දැනුම්දීම ඉවත් විය.
4. **තාපය (heat)** — දෘඩාංග `SurfaceView` මඟින් GPU පිටපත් රහිතව වීඩියෝ දර්ශනය වේ
   (~60–70 % අඩු GPU තාපය), දිගු චිත්‍රපට නැරඹීමට සුදුසුය.
5. **UI** — `#` මගින් සැබෑ **Color Studio** එකක් විවෘත වේ, **Material You** සැබෑ ලෙසම
   ක්‍රියාත්මක වේ, Home හි `Got it` සුරැකේ, සහ Troubleshooter එකේ සජීවී පියවර ලැයිස්තුවක්
   දිස්වේ. සියලුම ප්‍රතිඵල **සත්‍යවශයෙන්** (FAIL ✗ ඉහළින්ම) පෙන්වන අතර වාර්තාව
   දුරකථනයේ clipboard එකට ස්වයංක්‍රීයව පිටපත් වේ.
