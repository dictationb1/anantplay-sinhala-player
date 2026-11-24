# AnantPlay Sinhala Player v3.4.6 — Mornye Liquid-Glass Flutter UI release report

**Release line:** `v3.4.6` / `versionCode 30406` (version locked — no jump to 3.5 or 4.0)
**Delta integrated:** `v3.4.6-option-a-delta.zip` (Option A: Mornye v5.4.0 Liquid-Glass
Flutter add-to-app UI over the untouched Java/Media3 backend)
**Branch:** `arena/bdd02ad3-anantplay-sinhala-player` → `main`
**PR:** https://github.com/dictationb1/anantplay-sinhala-player/pull/27
**Merged:** **`532e66e7a6a5185faa5be0a3ddc6ba1b1bbbb6f1`** (2026-11-24T09:15:31Z)

---

## 1. What shipped

| Layer | Content |
|---|---|
| Backend (untouched) | 68 `src/lk/techworld/anantplay/sicast/*.java` pure-Java engines, `res/`, `assets/`, `libs/*.jar` (Media3 1.11.1 + Guava 33.3.1, 13 jars) |
| Bridge (new) | `MainActivity` (FlutterActivity) + `Media3TextureBridge.java` — MethodChannel/EventChannel contract for cast, player, subtitles, settings, history, troubleshoot, updater |
| UI (new) | `ui/lib/mornye_ui/` — Mornye v5.4.0 vendored LGPL-3.0-only, plus `ui/lib/anantplay/` shell: onboarding (5 stages), home, settings (9 sections), help, troubleshoot |
| Gradle host | `android/` — AGP 9.3.1, Gradle 9.5.0 wrapper, Flutter 3.50.1 add-to-app include, arm64-v8a only |

## 2. Pre-merge traps — fixed

1. **`android/local.properties` deleted**, `.gitignore` extended (`android/local.properties`,
   `ui/.dart_tool/`, `ui/.android/`, `ui/build/`, `android/.gradle/`, `android/build/`,
   `android/app/build/`, `**/ephemeral/`) while `ui/pubspec.lock` + the four `*.ttf` fonts and `*.jar` stay on the release side of the line.
2. **Workflows audited and repaired:** versioned artifact
   `dist/AnantPlay-Sinhala-Player-v3.4.6.apk`, `SHA256SUMS.txt` written in `dist/`, Gradle 9.5.0 ↔ AGP 9.3.1 ↔
   Flutter 3.50.1 pins, badging assertions (`lk.techworld.anantplay.sicast` / 30406 / 3.4.6 / with targetSdk 36),
   `zipalign -c 4`, `apksigner` v2+v3, < 50 MiB, exactly 2 release assets, `docs/latest.json` committed with `[skip ci]`.
3. **`THIRD_PARTY.md`** — LGPL-3.0-only block for `mornyeapp/Mornye @ c4a1c7b8` v5.4.0 © 2026 zarzet
   (`ui/lib/mornye_ui/` with LICENSE/COPYING/NOTICE intact), `liquid_glass_easy 5.2.0`,
   `material_3_expressive 1.2.1`, `flutter_riverpod`, `flutter_svg`, InterVariable + GoogleSansFlex fonts.
4. **Zero-regression hygiene** — no `AccessibilityService` references, `allowBackup="false"`,
   `SelfUpdateCore.MAX_APK_BYTES` = 55 MiB − 1, applicationId/minSdk 26/targetSdk 36/30406/3.4.6,
   no `.zip` and no `.apk` tracked (`git ls-files '*.zip'` = 0, `'*.apk'` = 0).

## 3. Root causes found and fixed while driving hosted CI green

Each of these was a **hard build failure** that no local check could see (no JDK/Flutter in the
authoring sandbox), and each was diagnosed from check-run annotations after adding
annotation-based failure reporting to every gate.

| # | Failure (exact hosted message) | Root cause | Fix |
|---|---|---|---|
| 1 | `processReleaseMainManifest`: `mainManifest specifies file '.../android/AndroidManifest.xml' which doesn't exist` | AGP 9 resolves `sourceSets.manifest.srcFile` **relative to the module** at task time | `sourceSets` now use absolute `File`s computed from the root project |
| 2 | `ManifestMerger2$MergeFailureException: Error parsing AndroidManifest.xml` | an XML comment contained `--` (illegal in XML comments) made a `<!-- ... -->` comment unparseable | comment reworded; the lab now asserts the manifest **parses** and that no comment contains `--` |
| 3 | `Manifest merger failed : The <uses-sdk> tag was detected ... Starting with AGP 9.0.0 these attributes have been deprecated` | AGP 9 forbids `<uses-sdk>` in the manifest | removed; minSdk 26 / targetSdk 36 / compileSdk 36 live in `android/app/build.gradle` and `build.sh`'s aapt2 flags (both stamp targetSdk 36) |
| 4 | `cannot find symbol : class ExoPlayer` in `PlayActivity` | `fileTree(dir: "../libs")` resolves against `android/app` → `android/libs` (absent), so the Media3 jars were **off the compile classpath** | absolute libs dir |
| 5 | `checkReleaseDuplicateClasses`: `annotation-experimental 1.3.1` vs `1.4.0` | the shipped jar and Media3's transitive Maven artifact both dexed | see below |
| 6 | `checkReleaseDuplicateClasses`: `concurrent-futures 1.2.0` vs `1.1.0` | same | see below |
| 7 | `checkReleaseDuplicateClasses`: `guava` vs `com.google.guava:listenablefuture:1.0` | same | **one policy** (below) |
| 8 | `:app:compileFlutterBuildRelease` → `Process 'flutter' finished with non-zero exit value 1` (no message) | `FlutterExtension.source` defaults to `../..`; for a standalone Flutter app that is the project root, but here (`android/app`) it resolved to the **repo root**, which has no `pubspec.yaml` | `flutter { source = <repo>/ui; target = "lib/main.dart" }` (absolute — `source` goes through `project.file()`, so `../ui` would be `android/ui`) |
| 9 | `apksigner`: **`Verified using v3 scheme: false`** on an otherwise green build | AGP derives the v3 scheme from min/maxSdk and, with minSdk 26, left it OFF — the release was v2-only | `enableV3Signing true` (v1/v2 retained) **and** both workflows now grep for `...v2...: true` *and* `...v3...: true` and fail otherwise |

**Duplicate-class policy (5–7).** After three separate pairs surfaced one per CI run, the rule
was made explicit instead of chasing versions: *`libs/` is the provider of record.* The Maven
copies of every library the shipped jars already contain are excluded globally
(`com.google.guava:{guava, failureaccess, listenablefuture}`,
`androidx.annotation:annotation-experimental`, `androidx.concurrent:concurrent-futures`).
No jar was deleted, moved or renamed — `build.sh` still compiles against exactly the same
13 files, and every jar is dexed exactly as shipped.

## 4. Verification

### 4.1 Hosted CI (GitHub Actions), run **45357434132** @ `0095ef0`

| Job | Result |
|---|---|
| Cast protocol lab (gating) | ✅ **3170/3170 PASS, 0 FAIL — Lab result: ALL 1252 PASSED** |
| Flutter analyze & test (Mornye Liquid-Glass module) | ✅ **No issues found**; **All tests passed** (14 tests: 10 bridge + 4 shell) |
| APK build (Gradle 9.5.0 + AGP 9.3.1 + SDK 36, signed + verified) | ✅ APK bytes **46,778,555** (< 50 MiB), version **3.4.6 (versionCode 30406)**, **apksigner v2 true + v3 true**, `zipalign -c 4` OK |

Every assertion in the APK job publishes a check-run annotation, so the artifact's identity is
provable from the API: `zipalign -c 4` exit 0, `apksigner verify` **v2 true + v3 true**, badging
`lk.techworld.anantplay.sicast` / `targetSdkVersion 36`, `lib/arm64-v8a/libflutter.so` present,
`assets/fonts/NotoSansSinhala-Regular.ttf` present.

### 4.1b Post-merge release pipeline (run **45363481682** @ merge commit `532e66e`)

| Job | Result |
|---|---|
| Lab gate → signed APK → GitHub Release | ✅ `Lab: 3170/3170 PASS — releasing v3.4.6` |
| Build Sinhala Player APK (4563851894 → 45363481994) | ✅ |
| Release **v3.4.6** | ✅ live, **exactly 2 assets** |

**Release:** https://github.com/dictationb1/anantplay-sinhala-player/releases/tag/v3.4.6

| Asset | Size |
|---|---|
| `AnantPlay-Sinhala-Player-v3.4.6.apk` | **46,778,555 B (44.6 MiB) — < 50 MiB** |
| `SHA256SUMS.txt` | 102 B |

**Digest chain (independently re-fetched over HTTPS, not from the build log):**

| Source | Value |
|---|---|
| `SHA256SUMS.txt` on the release | `15c95c7f0bb6bb7a6658d83547e8e344b2deb86949588bebcfd835ee3f5ec52c` |
| `docs/latest.json` on `main` (`raw.githubusercontent.com`) | `"version":"3.4.6","code":30406,"sha256":"15c95c7f…"` |
| Match | ✅ **identical**, and `apkUrl` points at `releases/download/v3.4.6/…` |

`docs/latest.json` landed as commit `00ca5b4 chore(release): docs/latest.json v3.4.6 (updater tier-2 endpoint) [skip ci]`
— **no CI loop** (a `GITHUB_TOKEN` push cannot start a run, and the `[skip ci]` marker is belt-and-braces).

### 4.2 Local lab

| Gate | Result |
|---|---|
| `lab/run_lab.sh` (Java) | the hosted run above is the authoritative one (this sandbox has no JDK) |
| `lab/verify_v344_ui.py` | 1861 PASS, 0 FAIL |
| `lab/verify_v345_ui.py` | 57 PASS, 0 FAIL |
| `lab/verify_ui_completeness.py` | OK — icons ≤ 28dp, every pref wired + backed up, zero emoji chrome |
| `lab/verify_layouts.py` | OK — every addView/setLayoutParams cast type-correct |
| `lab/verify_gradle_host.py` | **62 checks, 0 failed** |
| `bash -n build.sh lab/run_lab.sh` | OK |

### 4.3 Delta extraction

- zero `..` path traversal entries; nothing outside the overlay set was overwritten;
- all 13 `libs/*.jar` preserved byte-identically; `res/`, `assets/` untouched;
- 4 fonts landed with exact non-zero sizes: `InterVariable.ttf` 879,708 B,
  `GoogleSansFlex.ttf` 571,544 B, `NotoSansSinhala-Regular.ttf` 211,264 B,
  `NotoSansSinhala-SemiBold.ttf` 211,372 B;
- `git rm -f *.zip` → `git ls-files '*.zip'` = **0**, `'*.apk'` = **0**.

## 5. Sinhala summary — සිංහල සාරාංශය

**v3.4.6** නිකුතුවේ ප්‍රධාන කරුණු:

- නව **Mornye v5.4.0 Liquid-Glass** Flutter UI එක (LGPL-3.0-only, `ui/lib/mornye_ui/`) පැරණි
  Java/Media3 backend එකට හානියක් නොකර එකතු කර ඇත — `libs/*.jar` 13 ම, `res/`, `assets/` සහ
  වෙනස් නොකළ `.java` ගොනු 68 එලෙසම පවතී.
- අනුවාදය **3.4.6 (versionCode 30406)** ලෙසම රඳවා ඇත; APK එක **46.7 MB** (< 50 MiB).
- APK එක `lk.techworld.anantplay.sicast` ලෙස, **targetSdk 36**, arm64-v8a, **v2 + v3** අත්සනින්
  (දෙකම `apksigner` මගින් තහවුරු කර ඇත, zipalign 4-byte) — පෙර v3.4.5 උඩින් ස්ථාපනය කළ හැක
  (Obtainium update contract).
- CI සියලු job **3/3 සම්පූර්ණයෙන් සාර්ථක**: lab **3170/3170 PASS**, Flutter test **14/14**,
  APK build signed + verified (v2 + v3).
- **v3.4.6 නිකුතුව සජීවීය**: ගොනු **2ක් පමණි** — APK (46,778,555 bytes) සහ `SHA256SUMS.txt`;
  `docs/latest.json` හි sha256 (`15c95c7f…`) නිකුතුවේ sha256 සමඟ **සම්පූර්ණයෙන් සමාන** ය.
- නිදහස් මෘදුකාංග බලපත්‍ර (`THIRD_PARTY.md`) යාවත්කාලීන කර ඇත; `.zip`/`.apk` ගොනු Git
  එකේ **නොමැත**.

---

*Report generated for the v3.4.6 release; all facts above are reproducible from the workflow run
annotations and the repository state at the merge commit.*
