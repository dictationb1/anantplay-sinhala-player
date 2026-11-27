# AnantPlay Sinhala Player — v3.4.6.k (versionCode 30417) Silent-Update + Full Video-Audit Fix Report

**Release identity:** `versionName 3.4.6.k` · `versionCode 30417` · APK `AnantPlay-Sinhala-Player-v3.4.6.k.apk` · `ui/pubspec.yaml` `3.4.6+30417` · `docs/latest.json` untouched at the live `3.4.6.j / 30416` tier (the release workflow is its only writer) · APK size ceiling **raised 50/55 MiB → 100 MiB (104,857,600 bytes)** across `SelfUpdateCore.MAX_APK_BYTES`, `build.sh`, both GitHub workflows and the lab's acceptance checks.

**Verification summary (this sandbox, this tree — every gate re-run fresh in this delivery round):**

| Gate | Result |
| --- | --- |
| Java compile gate (`scripts/compile_check.sh`, android-36 + Flutter 3.50.1 embedding + R.java, 94 ids) | **COMPILE OK — 189 classes, 73 sources, 0 errors** |
| Flutter analyze | **No issues found** |
| Flutter widget/unit tests (`flutter test`) | **All 196 tests passed** (194 prior + **2 new v3.4.6.k OTA-visibility tests**) |
| Cast-protocol + static lab (`bash lab/run_lab.sh`) | **exit 0 — 3484 executable + static checks, 0 FAIL** (ReceiverLab 1471 · v3.4.4 vector/typography audit 1866 · v3.4.5/6 Mornye UI/updater audit 57 · bridge-wiring audit 90) |
| Bytecode reference verifier | **OK — 260 distinct referenced types, all resolvable** |
| Layout-params verifier (+ its own 13-case selftest) | **13/13 selftest · 0 mismatches across all shipped sources** |
| UI-completeness truth gate (R1 icons / R2 zero fake customization / R3 zero emoji controls) | **93 AppPrefs keys, all in BACKUP_KEYS, all wired to a real UI surface — this round it caught 5 unwired `K_OTA_*` keys, fixed before delivery (§1.6)** |
| Gradle-host structural gate | **64 checks, 0 failed (re-pinned to the 3.4.6.k / 30417 identity this round — §1.6)** |
| APK launch-integrity selftest | **SELFTEST OK — the gate refuses the dead-on-arrival 4 KB-page / multi-ABI shape** |
| javac syntax gate | **73 shipped sources, 0 diagnostics** |
| 78-probe DeepProbe matrix on the lab hooks | **78/78 PASS (domains A=6/9/16/6/13/6/8/9/5)** |
| Live bridge E2E (best effort, not gating) | **Worker UP — v4.1.0, health ok, resolve "The Odyssey" → `tt33764258`, a 1909-cue Sinhala `.srt` round-trips playable — 3/3 PASS** |

*(Sandbox note: the Gradle-host APK build itself runs on GitHub CI — `build-apk.yml` resolves Gradle 9.5.0 / AGP 9.3.1 / Flutter 3.50.1 / JDK 21, builds, `zipalign -P 16`, signs and runs `lab/verify_apk_launch_integrity.py` over the real APK; this local round re-proved every structural gate the CI build feeds on, plus the full executable lab. `docs/latest.json` stays untouched so the updater's tier-2 endpoint only moves when CI publishes the release.)*

---

## 0. සිංහල සාරාංශය (Sinhala summary)

**AnantPlay Sinhala Player v3.4.6.k** — නිශ්ශබ්ද, කවුළු-රහිත, කිසිදා නොවැසෙන ස්වයං-යාවත්කාලීනය සහ වීඩියෝ වාර්තාවේ සෑම දෝෂයක්ම මෙම නිකුතුයෙන් අවසන් වේ:

* **නිශ්ශබ්ධ ශුන්‍ය-තැපෑල යාවත්කාලීන (Android 12–16):** `UPDATE_PACKAGES_WITHOUT_USER_ACTION` අවසරය + `SessionParams.setRequireUserAction(USER_ACTION_NOT_REQUIRED)` — අකුරු-පැච් යාවත්කාලීන (3.4.6.k → .l → .m) ස්ථාපන කවුළුවක් **තැපෑලක්වත් නොමැතිව** සිදු වේ; ඉහළට පාවෙන වීදුරු පෙතියක් පමණක් පෙනේ ("Updating to vX…"). ස්ථාපන චක්‍රය (2–3× නැවත නැවත පෙනීම) තනි-පියවර අගුලකින් + 15 තත්පර සිසිලනයකින් මැරී ඇත. විශාල යාවත්කාලීන (3.5.0+) සම්පූර්ණ-තිරය වසා-නොහැකි ForceUpdateScreen එක පමණි. APK සීමාව 100 MiB දක්වා ඉහළ ගොස් ඇත.
* **100% කළු-තිර සහ DLNA සැඟවුණු දර්ශනය මැරුණි:** `PlayActivity` නැවත `buildUi()` + `startPlayback()` අනුපිළිවෙළින් විවෘත වේ (Test Player සහ ▶ Play in Cinema දෙකම). DLNA SetAVTransportURI දැන් BAL-අවසරයෙන් `PlayActivity` එකටම යයි (HomeScreen කිසිවිටෙක නොවේ).
* **ශුන්‍ය-වෙළඳ දැන්වීම් YouTube:** youtube.com/watch, youtu.be, shorts සියල්ල InnerTube (ANDROID_VR→IOS→WEB) මඟින් කෙළින්ම ධාවනය වේ; SponsorBlock කොටස් ස්වයංක්‍රීයව මඟහරින අතර උපසිරැසි timedtext ධාවාවෙන්ම සිංහලට පරිවර්තනය වේ — `▶ Play Ad-Free in Cinema`.
* **අමු #EXTM3U පෙළ මැරුණි:** .m3u/.m3u8 සම්බන්ධතා යෙදුම තුළම සොයිය-හැකි නාලිකා-පත්‍රයක් බවට පරිවර්තනය වේ; තෝරාගත් නාලිකාව කෙළින්ම සිනමා-ධාවකයේ විවෘත වේ.
* **ස්ථාපිත මාර්ගෝපදේශකය (Setup Wizard):** ඉදිරිපසට අනවසර-ලිස්සුම අගුල, පිටස්තරයෙන් ආපසු ගිය විට එමම පිටුවේ නැවත ආරම්භය, සිසිල්-ආරම්භ ක්ෂණික-දර්ශන මැරුණි, ⋮ ලේඛන-නැංගුරුව හරියටම බොත්තමේ කොනේ පිපෙයි.
* **පාවෙන-ඊතව මඟ පෙන්වන AnantPlay පුහුණුකරු:** පතුලේ-නැංගුරු බබළන ඊතා සමඟ පියවර 10 (✕ / — බොත්තම් නැත), දෙපැත්තම එක්-තැපෑලක් Copy.
* **අවංක දුරස්ථ-ක්‍රමලේඛන සමමුහුර්තය:** `ota/v1/manifest.json` — CDN දෙකක බයිට්-සමානතාව + SHA-256 සත්‍යාපනය (fail-closed); පාලම්-දර්පණ 2, දර්ශක 2, දැන්වීම්-නීති 7, දින-7 උපසිරැසි කෑෂ් TTL — සියල්ල About → "Remote config (signed)" පේළියෙන් ප්‍රත්‍යක්ෂව පෙනේ.

**සියල්ල ඔප්පු කළේය:** විභාග 196/196 · රසායනාගාරය 3484 පරීක්ෂණ 0 අසමත් · සජීවී පාලම් E2E 3/3 · 78/78 ප්‍රෝබ්.

---

## 1. LAW #1 — the silent zero-prompt forced auto-updater (Android 12–16)

### 1.1 The two OS grants that arm the silent lane

`AndroidManifest.xml:51`:

```xml
<uses-permission android:name="android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION"/>
```

`SelfUpdater.java:1283-1295` — every session is created with the AOSP silent-update contract (API 31+, targetSdk 36 ≥ 34):

```java
if (android.os.Build.VERSION.SDK_INT >= 31) {
    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
}
```

`AnantPlayInstaller.java:435-438` — the installer-of-record for the companion `me.champdev.weblock` update carries the exact same `USER_ACTION_NOT_REQUIRED` commit. Flutter test proof: `v3.4.6.k 6 — USER_ACTION_NOT_REQUIRED + UPDATE_PACKAGES_WITHOUT_USER_ACTION` (asserts both files).

### 1.2 The 2–3× installer-sheet loop is dead (single-flight + 15 s cooldown)

The audit's repeated-installer-popup timeline was three commits for one APK: `onResume()` re-fired `resumePendingInstall()` against a session already committed, and each commit opened another system sheet. The kill (`SelfUpdater.java:1240-1310`):

* `AtomicBoolean installCommitInFlight` — a second commit while one is in flight is dropped (`install: another session is already committed — dropping this duplicate commit`);
* `activeInstallSessionId` + `INSTALL_COMMIT_COOLDOWN_MS = 15_000` — a re-commit for the same session inside 15 s is dropped with a reason line;
* the pending-resume flags clear **the instant the commit fires** — neither `onResume()` nor the 90 s watcher can re-commit this session (the loop's second and third legs);
* `InstallResultReceiver` treats `STATUS_PENDING_USER_ACTION` as the FALLBACK lane: exactly one confirm sheet may be forwarded, the lock stays held until a terminal status, so two PENDING broadcasts can never stack two sheets.

### 1.3 The two-lane version policy (letter patches go silent, majors gate)

`SelfUpdater.java:1050-1080`: when a strictly newer release lands —

* **letter patch** (same major.minor.patch base, e.g. 3.4.6.k → .l): the SILENT lane — download ONCE + SHA-256 verify + `USER_ACTION_NOT_REQUIRED` commit, with only the top Mornye glass pill "Updating to vX…". `ForceUpdateScreen` appears only if the OS returns `STATUS_PENDING_USER_ACTION` or the install permission is missing;
* **major/minor** (3.5.0, 3.6.0…): the full-screen non-dismissible `ForceUpdateScreen` (`PopScope(canPop: false)`), which itself commits with `USER_ACTION_NOT_REQUIRED` on the tap.

There is still **no off-switch anywhere**: the v3.4.6.e `K_AUTO_UPDATE` mute is deleted, the check always runs, the gate is only armed by `UpdateChecker.isNewer() == true`, `sweepStalePending()` deletes non-strictly-newer pending metadata on every entry (the same-version lock is mathematically impossible), and Obtainium compatibility is unchanged.

### 1.4 The 100 MiB APK ceiling (the law tier)

`SelfUpdateCore.java:51` — `static final long MAX_APK_BYTES = 100L * 1024 * 1024;` (104,857,600 bytes), enforced on the download pump, the release-acceptance check and mirrored in `build.sh:173` (`APK size must be at most 100 MiB`), both GitHub workflows, `ReceiverLab` (`v346k LAW: the download cap is 100 MiB` / `the acceptance cap is exactly 100 MiB`) and `verify_v345_ui.py` (`updater cap is exactly 100 MiB (the v3.4.6.k law tier)`).

### 1.5 The signed JSON OTA config sync (`docs/ota/manifest.json` + `OtaConfigSync.java`)

A self-hosted, cryptographically verified config channel that keeps the app's remote endpoints fresh without shipping an APK:

* **both-CDN consensus** — the manifest is fetched from `raw.githubusercontent.com` AND `cdn.jsdelivr.net`; bodies must be byte-identical or the update is refused (fail-closed to the pinned defaults);
* **payload SHA-256** — `sha256` must equal the digest of the payload's canonical JSON (sorted keys, no whitespace). The shipped manifest self-verifies: `7073e793…c09c91a` (re-hashed and matched in this delivery round);
* **SSRF guard** — every URL must pass `SelfUpdateCore.safeRemoteUrl` (https-only, no loopback/private/metadata hosts);
* one 5-second wall-clock budget, single-flight, background thread, never blocking the UI or the receiver;
* the payload pins: 2 Subtitle Bridge mirrors, 2 index feeds, 7 ad-block rules, and the **7-day local SRT cache TTL** — `SubtitleStore.sweepExpired(ttlMs)` deletes stale `.srt` files while a reused subtitle refreshes its stamp.

### 1.6 This round's fresh catches — found by the gates, fixed before delivery

This delivery round re-ran the full lab from a cold sandbox (the previous round's run was interrupted mid-gate). Two real defects surfaced and were fixed:

1. **`verify_ui_completeness.py` R2 — "an unwired pref is a fake pref"**: all five new `K_OTA_*` keys (`otaBridgeMirrors`, `otaIndexFeeds`, `otaAdRules`, `otaSubCacheDays`, `otaSyncAt`) were engine-only. Fix: they now ride the full house wiring — typed `SettingsMirror` constants + getters, a live **About → "Remote config (signed)"** card (verified date+clock, mirror/feed/ad-rule counts, or the honest "Pending — pinned defaults in force"), and the **Storage & Cache → Subtitle cache** row states the live TTL ("Auto-expires after 7 days (signed config)"). Two new tests pin the contract (`v3.4.6.k 7 — the signed OTA config channel is USER-VISIBLE`).
2. **`verify_gradle_host.py` was still pinned to the 3.4.6.j / 30416 identity** — the v3.4.6.k tree would have failed the Gradle-host gate in CI. Re-pinned to 3.4.6.k / 30417 (build.gradle · AppInfo · build.sh · pubspec).

Both are now green in the final lab run, and the ZIP carries the fixes.

---

## 2. The 7-part video-audit fixes (Part_01–Part_06 + XRecorder_20261127_01)

### 2.1 Part_04 04:50 / Part_05 03:29 — the 100% blank dark PlayActivity

v3.4.6.j removed the zero-UI router trampoline but never restored the launch sequence the native engine needs — `PlayActivity` opened with zero content view on **both** "Test Player" and "Play in Cinema". `PlayActivity.java:570-600` restores the field-proven v3.4.6.c sequence verbatim: `buildUi()` FIRST (the `SurfaceView` + Mornye HUD + spinner + error card — `setContentView` is back), then `startPlayback()` (Media3 1.11.1, `EXTENSION_RENDERER_MODE_OFF`, `setVideoChangeFrameRateStrategy(ONLY_IF_SEAMLESS)`, `setVideoSurfaceView(surface)`, 30–60 s buffer, `WAKE_MODE_LOCAL`), then the ticker, volume boost, sleep timer and the never-silent subtitle pill ("… ලබාගනිමින්…" → "✓ සූදානම්"). An unplayable URL leaves cleanly with a logged reason — no half UI. Lab proof: `v346k host: the Test Player action opens the NATIVE hardware cinema player`; Flutter test `the blank PlayActivity screen is dead (buildUi + startPlayback)`.

### 2.2 Part_05 02:06–02:12 — DLNA cast opened HomeScreen instead of the movie

The `/play` (and DLNA `SetAVTransportURI`) path called `PlayActivity.launchNative()`'s plain `startActivity()` from the HTTP worker thread — backgrounded, Android 14–16 silently drops it, and the 800 ms takeover then brought the Flutter shell (Home) to the front on top of the blank player. `ReceiverService.java:617-633` now rides the BAL-ALLOWED PendingIntent lane targeting **PlayActivity itself**: `PlayActivity.launchBal()` (`PlayActivity.java:214`) carries `NEW_TASK | SINGLE_TOP | CLEAR_TOP | REORDER_TO_FRONT` + `MODE_BACKGROUND_ACTIVITY_START_ALLOWED` (API 34+ via `ActivityOptions.makeBasic()`), so a live player gets `onNewIntent`, a cold process gets `onCreate`, and Home never covers the movie. The fallback ladder (direct start → trampoline) still exists and is honest about failures. Lab proof: `v346k host: the Test Player action opens the NATIVE hardware cinema player (PlayActivity, zero Impeller)`; Flutter test `the DLNA cast lands on PlayActivity (never MainActivity)`.

### 2.3 Part_05 00:20 / 00:17 / 01:32 — YouTube hard crash + in-WebView ads + the stale "Play in Cinema" banner

The new pure-Java `YouTubeExtractor.java` (zero Python, zero `api.github.com`): any `youtube.com/watch?v=…`, `m.youtube.com`, `shorts/…` or `youtu.be/…` URL opened in the Browser tab resolves the **ad-free direct stream** via the InnerTube `POST /youtubei/v1/player` client ladder (ANDROID_VR — no signature deciphering — then IOS, then WEB), picks the muxed progressive itags (18 = 360p, 22 = 720p when served), and pulls the caption track (`captions.playerCaptionsTracklistRenderer`) for the Sinhala auto-translation. `sponsor.ajay.app/api/skipSegments` segments auto-skip during playback. Every request runs under a 6 s budget; the renderer-crash shield guards the parse; the dock lights up **`▶ Play Ad-Free in Cinema`** which opens the native hardware player. Flutter tests: `the YouTube renderer-crash shield + the ad-free lane`.

### 2.4 Part_04 04:45 — raw `#EXTM3U` text dumped into the WebView

`browser_screen.dart` now detects playlist links (`.m3u` / `.m3u8`) before the WebView renders, parses them (`parseM3u` — `#EXTINF` name + the next non-comment line, `tvg-status="off"` marks a channel offline), and opens the in-app IPTV channel sheet: searchable, offline-filterable, one-tap → the native cinema player. A raw playlist body is never dumped as text again. The Ad-Block rules that ride the OTA manifest cover the ad hosts the WebView itself would load.

### 2.5 Part_01 / Part_06 — global Settings/UI jank

The scrolling Settings tiles carry **no live backdrop blur** (no `BackdropFilter` in scrollable tile lists — verified by test); the new `MornyeBubbleSlider` keeps a **local draft `ValueNotifier`** and commits to the MethodChannel **only on `onChangeEnd`** (the browser-repo slider storm is dead); the Sticky Live Sinhala Subtitle Preview Card sits pinned at the top of Playback & Subtitles so a live preview never jumps under your thumb; `RepaintBoundary`/`AutomaticKeepAliveClientMixin` discipline on the heavy lists.

### 2.6 Mornye v5.4.5 UI alignment

`AppSwitch` — the held-lens thumb stretch (37→58 wide, 21/3 growth), `OverflowBox` lens, the 150 ms press-in / 250 ms release springs (`MornyeMotion.effectsFastSpring` / `fastSpring`); `MornyeGlassNotification` — the real frosted `BackdropFilter` at sigma 14, the `_HairlineRimPainter` luminous rim, the smooth top spring drop (the old SnackBar is dead everywhere); the ⋮ popover anchors at `Alignment.topRight` from the **button's own Builder rect** (never screen-center); the vendored menu pours out of the anchor. Flutter tests: `the button's OWN Builder context supplies the anchor rect`, `the AppSwitch stretchy lens + the Mornye notification glass`.

### 2.7 Setup Wizard + the guided coach + the honest troubleshooter

* **forward-swipe lock** — `_ForwardBlockedScrollPhysics` (onboarding_screen.dart:1990) blocks forward drags until the slide's permission is granted; back is always free;
* **stage persistence** — `onboardingStage(0..5)` persists on every page change, `PageController(initialPage:)` resumes the exact slide on relaunch (never a reset to page 1);
* **cold-launch flash kill** — `main.dart:739` holds a clean `_HydrationBackdrop` until the first real `getAll()` snapshot lands (`!state.hydrated`), so the defaults can never flash OnboardingScreen for one frame on a phone that finished setup;
* **the arrow-guided coach** — `AnantPlayCoachService` renders bottom-anchored, with a glowing animated directional arrow per step (`↗ Top-right ⚙`, `↓ Tap Sources`, …, 10 steps verbatim from the audit), NO ✕/— buttons (a step completes only by doing it), one-tap Copy pills for both index JSONs plus every user-added URL, ONE view tree at ~0 CPU (a 4 Hz alpha pulse — zero blur shaders, zero AccessibilityService);
* **the honest troubleshooter** — the deep audit is now a **non-disruptive round trip** (a captured `PlatformException` FAILS honestly, the #87 false-PASS is dead: `the honest crash gate`), and worker outages stay `env_fail`, never a fake PASS.

---

## 3. The permanent red lines (re-proven, unchanged)

`prefs` assigned **before** `super.onCreate` (`MainActivity.java:337-339`, the v3.1.1 NPE kill) · the 8-channel Method/EventChannel hub · `safeGetLong/safeGetInt/putTyped` · LGPL-3.0-only · `allowBackup="false"` · zero AccessibilityService · `zipalign -P 16` (the Android-16 16 KiB zip contract) · `docs/latest.json` writer = the release workflow only · Obtainium compatibility unchanged.

## 4. What shipped in the ZIP (the packaging contract)

`v3.4.6.k-silent-update-video-fix.zip` — the repo's ZIP-root layout, exactly the 7 allowed root files (`AndroidManifest.xml`, `LICENSE`, `README.md`, `SETUP-SI.md`, `THIRD_PARTY.md`, `build.sh`, `.gitignore`), all directories intact (`android/`, `assets/`, `docs/`, `lab/`, `libs/`, `res/`, `scripts/`, `src/`, `tools/`, `ui/`, `.github/`), and **zero** of: duplicates, `lab/out/`, `*.class`, `*.pyc`, `ui/.flutter-plugins-dependencies`, `ui/.ios`/`ui/.android`/`ui/.dart_tool`/`ui/build`, `android/local.properties`, `android/.gradle`/`build` dirs, `.git/`, `*.apk`, `*.zip`. This report also lives inside the ZIP at `docs/reports/V3_4_6_K_SILENT_UPDATE_AND_VIDEO_AUDIT_FIX_REPORT.md`.

## 5. How to build the APK from the ZIP

```
unzip v3.4.6.k-silent-update-video-fix.zip -d anantplay && cd anantplay
(cd ui && flutter pub get)                       # regenerates ui/.android + the pub cache
printf 'sdk.dir=<SDK>\nflutter.sdk=<FLUTTER_ROOT>\n' > android/local.properties
cd android && ./gradlew :app:assembleRelease     # Gradle 9.5.0 · AGP 9.3.1 · Flutter 3.50.1 · JDK 17+
# then: zipalign -P 16 -f 4 … && apksigner sign (CI's release-apk.yml carries the full pipeline)
```

The mandatory gates before any release: `bash lab/run_lab.sh` (exit 0, 0 FAIL), `cd ui && flutter test` (0 FAIL), and the CI launch-integrity auditor over the built APK (`lab/verify_apk_launch_integrity.py` — its selftest re-proved this round it still refuses the dead-on-arrival shape).
