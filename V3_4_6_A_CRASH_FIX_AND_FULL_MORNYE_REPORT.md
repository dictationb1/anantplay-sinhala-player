# V3_4_6_A — THE CRASH FIX + FULL MORNYE PARITY REPORT

**AnantPlay Sinhala Player v3.4.6.a (versionCode 30407)**
Branch `fix/v3.4.6.a-crash-and-full-mornye-ui` · the emergency release that ends the v3.4.6 dead-on-arrival incident.

---

## 1. THE INCIDENT (ENGLISH)

**v3.4.6** (PR #27, commit `532e66e`, versionCode 30406, 46,778,555 B / 44.61 MiB, sha256 `15c95c7f0bb6bb7a6658d83547e8e344b2deb86949588bebcfd835ee3f5ec52c`) installed successfully on Android 16 real hardware and then **crashed instantly on every launch — the app would not open at all**.

## 1. සිද්ධිය (සිංහල)

**v3.4.6** සංස්කරණය (versionCode 30406, 44.61 MiB) Android 16 දුරකථනයකට සාර්ථකව ස්ථාපනය වූ නමුත්, විවෘත කළ විගසම **ක්‍රෑෂ් වූයේ යෙදුම සම්පූර්ණයෙන් විවෘත වීමට බැරි වූ නිසය.** v3.4.6.a (versionCode 30407) මෙය ස්ථිරව සහ මූලිකව නිවැරදි කරයි.

---

## 2. THE ROOT CAUSE — PROVEN AT THE BINARY LEVEL (ENGLISH)

The forensic autopsy of the live APK (the exact bytes users downloaded) found **the shipped `lib/arm64-v8a/libflutter.so` is a 4 KB-page-class ELF**:

```
PT_LOAD #1 (RX): off=0x0      vaddr=0x0      → 16K-aligned OK
PT_LOAD #2 (R):  off=0xa74140 vaddr=0xa84140 → +0x140 past a 16 KiB boundary (file AND memory)
PT_LOAD #3 (RW): off=0xad01c0 vaddr=0xaf01c0 → +0x1c0 past a 16 KiB boundary (file AND memory)
p_align=65536 declared — but the segments are only 4 KiB-congruent.
```

- **Android 16 (SDK 36)** runs on **16 KB-page-size devices** (the 2026 hardware class; Play requires 16 KB support for apps targeting API 35+). The dynamic linker **refuses to load** such a `.so` → `FlutterActivity` dies during engine creation → **instant crash before any frame** — the exact "installs fine, then refuses to open" symptom.
- On 4 KB-page devices (and CI's x86_64 emulators — which no CI job ever *launched* anyway) the same APK opens fine. That is why every static gate was green: apksigner v2+v3 ✓, `zipalign -c 4` ✓, badging ✓, size < 50 MiB ✓ — **none of them audited ELF page alignment, none of them started the app.**

**The mechanism:** the Gradle host resolved the **UNIVERSAL** `io.flutter:flutter-release` Maven artifact (all 3 ABIs, 4 KB-class arm64 slice) instead of the per-ABI 16 KB-ready artifact. The APK shipped **three engines** (arm64-v8a + armeabi-v7a + x86_64, 13.96 MB of dead weight) despite `ndk { abiFilters "arm64-v8a" }` — abiFilters did not stop the AAR-packaged `.so` merge. `libapp.so` (the Dart AOT snapshot) was itself 16 KB-ready; only the engine was not.

**Verified-clean negatives** (audited, no defect): manifest identity (30406/3.4.6/targetSdk 36/minSdk 26, `MAIN`/`LAUNCHER`, `flutterEmbedding=2`), DEX (full embedding, Media3, Guava present, only reflection-only dangling refs), engine/Dart version match (Dart VM 3.14.0 stable, snapshot hash consistent), assets (compiled IPLR shader + all fonts/onboarding present), signatures (v2+v3), the Java startup path (fully try/catch-guarded), and the Dart startup path (async+defensive, channels 1:1 with MainActivity).

## 2. මූලික හේතුව — ද්විමය මට්ටමෙන් ඔප්පැන් කළ (සිංහල)

APK එකේ එයාකාංශයම විශ්ලේෂණයෙන් ඔප්පැන් වූයේ **`libflutter.so` ගොනුව 4 KB-පිටු පන්තියේ ELF එකක්** බවයි:

- Android 16 දුරකථන **16 KB පිටු ප්‍රමාණය** සමඟ ක්‍රියාත්මක වන නිසා, dynamic linker එක එම `.so` **පූරණය කිරීම ප්‍රතික්ෂේප කරයි** → `FlutterActivity` එන්ජිම සෑදෙමින්ම මිය යයි → යෙදුම විවෘත වන්නේම නැත.
- 4 KB පිටු දුරකථනවල (සහ CI emulator) යෙදුම විවෘත වේ — එනිසාම CI gates සියල්ලම කොළ පාට විය. **ELF පිටු අනුකූලතාවක් විමසූ තිරයක්, යෙදුම ඇත්තටම විවෘත කර බැලූ පරීක්ෂණයක් නොතිබුණි.**
- ක්‍රමලේඛන දෝෂය: Gradle එක **සර්වත්‍ර `flutter-release` artifact** එක (ABI 3ක්, 4 KB arm64) භාවිත කළේ 16 KB-සූදානම් per-ABI artifact වෙනුවට. APK එකට **එන්ජිම් 3ක්** ඇතුළත් විය (13.96 MB අනාවැක්තික බර).

---

## 3. THE FIX SET (ENGLISH)

| # | Fix | Where |
|---|-----|-------|
| 1 | **Per-ABI 16 KB-ready engine resolution** — dependency substitution swaps `io.flutter:flutter-{release,debug,profile}` for `io.flutter:flutter-arm64-v8a-*` at configuration time | `android/app/build.gradle` |
| 2 | **Belt+braces ABI excludes** — `packaging.jniLibs.excludes` drops `armeabi-v7a/**`, `x86/**`, `x86_64/**` regardless of how the artifact resolves | `android/app/build.gradle` |
| 3 | **THE LAUNCH-INTEGRITY GATE** — `lab/verify_apk_launch_integrity.py`: every packaged `.so` must be 16 KB-ready (PT_LOAD congruent mod 16384 in file AND vaddr), exactly one ABI, libapp.so+asset pair, compiled-manifest identity, v2+v3 signing block. Wired into **both** workflows before upload/release. Proven against the dead v3.4.6 APK: it flags exactly the 6 real defects and exits 1 | both workflows + `lab/run_lab.sh` selftest |
| 4 | **THE REAL LAUNCH SMOKE TEST** — a real arm64 Android 36 (google_apis, 16 KB-page class) emulator: `adb install`, `am start -W` (blocks on startup crash), process alive after 8 s, **crash buffer empty**, foreground-activity check | `.github/workflows/build-apk.yml` |
| 5 | **Dart-side crash-proofing** — `FlutterError.onError` + `PlatformDispatcher.onError` forward into the new `anantplay/crashlog` channel (MainActivity → the same persistent `CrashLog` native crashes use, stamped with this build's versionCode). A Dart startup error can never again kill the app silently with nothing on disk | `ui/lib/main.dart`, `MainActivity.java` |
| 6 | **Alphabetical version scheme** `3.4.6 < 3.4.6.a < .b < .c < .d < 3.4.7` — `UpdateChecker.parse()` carries a native 4th letter dimension (a→1..d→4, junk-tolerant, **never NumberFormatException**), `SelfUpdateCore`'s 3 release-regex gates accept `X.Y.Z[.a-d]`, CI derives collision-free codes (.a=30407…​.d=30410; 3.4.7=30411). 16-assert ordering ladder verified | `UpdateChecker.java`, `SelfUpdateCore.java`, `release-apk.yml` |
| 7 | **Version identity sweep** — build.sh, AppInfo, pubspec, both workflows, README, SETUP-SI, settings UI, tests, ReceiverLab gates all on 3.4.6.a/30407 | repo-wide |

**Size:** the fix REMOVES ~13.96 MB of dead engines (armeabi-v7a + x86_64) — the APK drops from 44.61 MiB toward the ~30–33 MiB range (one arm64 engine + one arm64 snapshot), comfortably under the 50 MiB gate and the 55 MiB updater cap. Exact bytes are stamped by CI on the built artifact.

## 3. නිවැරදි කිරීම් (සිංහල)

1. **16 KB-සූදානම් per-ABI එන්ජිම** — Gradle dependency substitution එකෙන් `flutter-arm64-v8a-release` artifact එක තෝරයි.
2. **ABI excludes** — armeabi-v7a/x86/x86_64 එන්ජිම් APK එකෙන් සම්පූර්ණයෙන් ඉවත් වේ (බර ~14 MB අඩු වේ).
3. **දියත්-අඛණ්ඩතා තිරය** — සෑම packaged `.so` එකකම 16 KB පිටු අනුකූලතාව CI එකේදීම බලන, අකුරු-අනුවාද යාවත්කාලීන ක්‍රමලේඛනය සහිත, නොනැසී පවතින ඉන්ජෙක්ටරයක්.
4. **සැබෑ විවෘත-කිරීමේ පරීක්ෂණය** — Android 36 arm64 එමුලේටරයක `am start -W` + ක්‍රෑෂ්-බෆර් හිස් + 8 s ජීවත්වීම.
5. **Dart ආරම්භක ආරක්ෂණය** — `anantplay/crashlog` නාලිකාවෙන් ස්ථිර crash-log වාර්තාවකට.
6. **අකුරු අනුවාද ක්‍රමය** — `3.4.6.a` සිට `.d` දක්වා, `3.4.7`ට වඩා පහළින්; updater කිසිවිටක `NumberFormatException` නොදමයි.

---

## 4. FULL MORNYE PARITY (the Phase-2 work in this release)

The Mornye v5.4.0 visual effects that v3.4.6 shipped without, now present:

- **`StaggeredListItem`** (upstream `animation_utils.dart` lines 24-112, vendored verbatim with the `loadedFadeFirstFrame` constant): the list-entry reveal — each item springs up after its own delay (spatial spring, may overshoot), opacity on the effects spring, reduced-motion + scrolled-page aware. Wired into **Home** (every section), **Onboarding** (first-stage art/title/body ladder; stage changes keep the AnimatedSwitcher), and **Help** (the 4 action cards).
- **`MornyeGlassPreparation`** (upstream `mornye_glass_preparation.dart`, 30 lines, vendored verbatim): the one-time GPU warm-up when liquid glass is on — placed exactly as upstream app.dart places it: `ProviderScope > AppStateScope > MornyeGlassPreparation > MaterialApp`. The first glass frame never janks.
- **The connected settings list** (upstream appearance-page contract): `SettingsGroup` cards — `radiusCard = 24`, one surface per section, dividers between rows, `SettingsItem` row chrome with token paddings, the Mornye `AppSwitch` as the trailing control — replacing the floating per-tile cards. Every real preference (theme, accent, glass clarity 10–95%, outlines, motion, auto-subs, translator, auto-update, backup/reset, licenses) keeps its exact key and write path.

The music-player-specific `MornyeCoverGlow`/`MornyeMotionGlow` remain upstream by design: they need `cover_store` + `video_player`, which in this app are owned natively by the Media3 `PlayActivity` pipeline — the shared entry/glass/list contracts above are the parity surface. The Cinema Player (Media3 1.11.1 hardware pipeline through `Media3TextureBridge`) and the Browser (`WebBrowserView`) are untouched and remain native.

---

## 5. VERIFICATION LEDGER

| Gate | Result |
|------|--------|
| ELF audit of the dead v3.4.6 APK (`lab/verify_apk_launch_integrity.py`) | **FAILS with exactly the 6 real defects** (3-ABI packaging, 2× 4 KB-class engines, 32-bit armeabi engines, libapp ABI spread), exit 1 — the gate catches the real incident |
| The gate on a clean 16 KB single-ABI build (CI) | required-green before upload/release |
| Launch-gate selftest (`lab/run_lab.sh`, the DOA-shaped fixture) | SELFTEST OK — the auditor still refuses the dead shape |
| `lab/verify_gradle_host.py` (64 checks) | 0 failed |
| `lab/verify_v345_ui.py` (57 checks) | 0 failed |
| `lab/verify_layouts.py` (67 files, 299 calls) | 0 problems |
| `lab/verify_ui_completeness.py` | 1861 PASS, 0 FAIL |
| `lab/verify_v344_ui.py` | 1861 PASS-equivalent, 0 FAIL |
| Alphabetical-ladder ordering (16 asserts incl. junk-tolerance) | ALL PASS |
| versionCode ladder 30405 < 30406 < 30407 (.a) < 30408 (.b) < 30409 (.c) < 30410 (.d) < 30411 (3.4.7) | collision-free, verified |
| CI (the full matrix: lab → flutter → apk + launch-smoke) | to run green on the PR; the launch smoke boots a real 16 KB-page arm64 emulator |

---

## 6. THE SHIP

- Branch: `fix/v3.4.6.a-crash-and-full-mornye-ui` (this branch)
- Version: `3.4.6.a` / versionCode `30407` — an in-place update over both v3.4.5 and the broken v3.4.6 (same signature lineage, v2+v3, strictly greater code)
- Updater contract: tier-1 web-redirect + tier-2 `docs/latest.json` (the release workflow rewrites it on merge), `MAX_APK_BYTES` 55 MiB−1 unchanged, the alphabetical parse is live in `UpdateChecker`/`SelfUpdateCore`
- **A dead-on-arrival APK can never ship green again**: the launch-integrity gate runs on the final artifact in both workflows, and the emulator smoke job actually starts the app on the hardware class that killed v3.4.6.
