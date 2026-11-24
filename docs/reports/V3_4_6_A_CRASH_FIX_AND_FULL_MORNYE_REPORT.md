# V3_4_6_A — THE CRASH FIX + FULL MORNYE PARITY REPORT

**AnantPlay Sinhala Player v3.4.6.a (versionCode 30407)** — **SHIPPED.**
Tag `v3.4.6.a` · PR **#28** merged to `main` as `22a237e` (2026-11-24T14:02:31Z) · release **live** with exactly 2 assets.

---

## 1. THE INCIDENT

**v3.4.6** (versionCode 30406) installed successfully on Android 16 and then **crashed instantly on every launch — the app would not open at all.** All static CI gates of that release were green (signature, zipalign, badging, size), because none of them ever *started* the app.

**v3.4.6.a** fixes the true root cause — found by actually launching the build on a live KVM Android emulator in CI — plus the three audit traps that let v3.4.6 through.

> **සිංහල:** v3.4.6 (30406) ස්ථාපනය වූ නමුත් විවෘත කළ විගසම ක්‍රෑෂ් විය. v3.4.6.a (30407) මූලික හේතුව සජීවී emulator පරීක්ෂණයෙන් සොයා නිවැරදි කර ඇත: **`MainActivity.onCreate` එකේ `prefs` යොදන අනුක්‍රමය වැරදි නිසා `configureFlutterEngine()` කියවන විට `SharedPreferences` null වී NPE දී, ක්‍රියාමාර්ගය මරා දැමීය.** (ELF 16 KB මතභූමි මතභූත කතාව වැරදිය — පහත අංක 2.)

---

## 2. THE TRUE ROOT CAUSE — PROVEN BY LIVE LOGCAT

The CI launch-smoke test (x86_64 KVM emulator, Android 16 / API 36 `google_apis`, `pixel_6`) reproduced the crash **on our own rebuilt APK** before the fix — run **45406738360**, job "Android Emulator Launch Smoke Test":

```
11-24 13:50:31.785  2447  2447 E AndroidRuntime: FATAL EXCEPTION: main
11-24 13:50:31.785  2447  2447 E AndroidRuntime: Process: lk.techworld.anantplay.sicast, PID: 2447
11-24 13:50:31.785  2447  2447 E AndroidRuntime: java.lang.RuntimeException: Unable to start activity
    ComponentInfo{lk.techworld.anantplay.sicast/lk.techworld.anantplay.sicast.MainActivity}:
    java.lang.NullPointerException: Attempt to invoke interface method
    'boolean android.content.SharedPreferences.getBoolean(java.lang.String, boolean)'
    on a null object reference
        at android.app.ActivityThread.performLaunchActivity(ActivityThread.java:4280)
        at android.app.ActivityThread.handleLaunchActivity(ActivityThread.java:4467)
```

(The system relaunched the activity once — PID 2356 at 13:51:05 — and it crashed identically. `am start` never produced a focused window.)

### The mechanism

```java
// BEFORE (v3.4.6, identical on origin/main @ 30406) — DEAD ON ARRIVAL
@Override
protected void onCreate(Bundle b) {
    super.onCreate(b);                                   // ← FlutterActivity.onCreate runs
    prefs = getSharedPreferences("sicast", MODE_PRIVATE);//    configureFlutterEngine() INSIDE
}                                                       //    → prefs still null → NPE
```

`FlutterActivity.onCreate(...)` invokes `configureFlutterEngine(...)` **during `super.onCreate(...)`**, and that method synchronously reads:

```java
} else if (prefs.getBoolean(AppPrefs.K_AUTO_UPDATE, true)) {   // MainActivity.java:152
    SelfUpdater.checkOnLaunch(this);
}
```

With the old order, `prefs` was still `null` at that moment → the exact `NullPointerException` above → `Unable to start activity` → the process dies before the first frame. **The shipped v3.4.6 carries this identical ordering** — this is the real v3.4.6 crash, independent of page size.

### The fix

```java
// AFTER (v3.4.6.a)
@Override
protected void onCreate(Bundle b) {
    prefs = getSharedPreferences("sicast", MODE_PRIVATE); // ContextWrapper is live
    super.onCreate(b);                                    // configureFlutterEngine now sees prefs
}
```

`getSharedPreferences` is legal before `super.onCreate` (`attachBaseContext` has already run). Live logcat after the fix: **zero FATAL/SIGSEGV/SIGABRT, `am start -W` → `Status: ok`, process alive 12 s, MainActivity focused** — run **45408511950**, smoke job green (5 m 55 s).

### Why Z.ai's "4 KB-page-class libflutter.so" diagnosis was FALSE

The autopsy table itself disproves the theory (`p_vaddr ≡ p_offset (mod p_align)` is the ELF rule):

| Segment | off | vaddr | vaddr−off | congruence |
|---|---|---|---|---|
| PT_LOAD #2 (R) | `0xa74140` | `0xa84140` | `0x10000` = 65536 | **65536 mod 65536 = 0 → VALID** (even for 64 KiB pages) |
| PT_LOAD #3 (RW) | `0xad01c0` | `0xaf01c0` | `0x20000` = 131072 | **VALID** |

The claimed rule "`off % 16384 != 0` ⇒ 4 KB-class" is not a rule at all — `p_offset` is free to be any file offset; what must hold (and does) is the **congruence** `(p_vaddr − p_offset) % p_align == 0`. A universal-engine Maven artifact also cannot be "swapped" to hyphenated per-ABI coordinates (they don't exist — see trap 2). The 16 KB machinery that *is* real — `useLegacyPackaging=false`, `zipalign -P 16`, the ELF congruence gate — remains **in** this release as hardening for actual 16 KB-page Android 16 devices; it is simply not the DOA cause, and the launch smoke is what found that.

---

## 3. THE THREE AUDIT TRAPS — FIXED

| # | Trap | Fix |
|---|------|-----|
| 1 | `lab/verify_apk_launch_integrity.py` implemented fake ELF math (`off % 16384`) and both workflows ran `zipalign -c 4` only | Real rules: `p_align ≥ 16384`, `(p_vaddr−p_offset) % 16384 == 0`, no conflicting-permission PT_LOAD overlap in one 16 KiB virtual page; **new L1z zip gate**: every stored `.so` `data_offset % 16384 == 0` (or `extractNativeLibs=true`, decoded from compiled AXML); both workflows now run `zipalign -P 16 -f 4` **before** `apksigner sign` and verify with `zipalign -c -P 16 4` + the gate script. Z.ai's own example segments pass the corrected gate. |
| 2 | Bogus hyphenated `io.flutter:flutter-arm64-v8a-*` Maven substitutions; `useLegacyPackaging=true` hid zip offsets; no way to build an emulator twin | Substitution **removed** with an audit-truth comment (real coords are `io.flutter:arm64_v8a_release` / `flutter_embedding_release`); `useLegacyPackaging = false` (extractNativeLibs=false — 16 KB zip contract real); PR#27 excludes verified against shipped jars (guava carries ListenableFuture — they are safe and intentional); optional `-PsmokeAbi` (ndk abiFilters + jniLibs excludes) builds the identical release for the emulator's ABI. `lab/verify_gradle_host.py` 64/64. |
| 3 | No launch test existed — v3.4.6 shipped "installs fine, never opens" with everything green | Full rewrite: ubuntu-latest + **x86_64 KVM** (`reactivecircus/android-emulator-runner@v2`, API **36**, `google_apis`, `pixel_6`, 4 cores/4 GB, boot timeout 900 s, udev KVM rule so the emulator can never silently fall back to software); builds its own x86_64 release twin (`-PsmokeAbi=x86_64 -Ptarget-platform=android-x64`); ABI lock (root `lib/` = `lib/x86_64` only, every `.so` under it, `libflutter.so`+`libapp.so` present) + v2/v3 signature; then `adb logcat -c` → `install -r` → `am start -W` (`Status: ok`) → **12 s** watch: non-empty `pidof`, `dumpsys window` focus on `lk.techworld.anantplay.sicast/.MainActivity`, **zero** `FATAL EXCEPTION|Fatal signal|SIGSEGV|SIGABRT` in `logcat -b crash` and none outside it; every failure dumps the FULL crash buffer + `AndroidRuntime|flutter` `*:E` lines into `$GITHUB_STEP_SUMMARY` and `::error::` annotations. The action executes `script:` **line by line as separate `sh -c` runs** (fresh shell per line), so every probe is a self-contained single line persisting state in `/tmp` files. |

### Parity & other defects fixed en route (found by the gates)

- **`ui/pubspec.yaml` `version: 3.4.6.a+30407` is invalid pub_semver** — 4-part core; every `flutter pub get` died writing `.dart_tool/package_graph.json` (`'version': p.version.toString()`). Now `3.4.6+30407.a` (valid build metadata), description keeps `v3.4.6.a`.
- 7 stale identity checks in `lab/ReceiverLab.java`, `AndroidManifest.xml`/`ui/pubspec.yaml` identity comments, `README.md`/`SETUP-SI.md` headers → all on **3.4.6.a / 30407**.
- `settings_screen.dart`: missing `app_tokens` import (`context.tokens` undefined) and never-passed `isLast` parameter (`unused_element_parameter` — fatal-warnings) — both fixed.
- Zero tracked `.zip`/`.apk` (Gate 1), machine-local paths untracked.

---

## 4. PROOFS

| Gate | Proof |
|---|---|
| Local pre-checks | `lab/run_lab.sh` **3174 checks, 0 failed** (1256 cast-protocol + 1861 UI + gradle-host 64/64 + launch-integrity selftest), both workflows parse as YAML, every smoke-script line passes `dash -n` exactly as the action runs it |
| PR CI | PR **#28** — 4/4 green on `arena/b7777ea5-anantplay-sinhala-player`: Cast lab ✓ · Flutter analyze & test ✓ · APK build ✓ · **Android Emulator Launch Smoke Test ✓** (run 45408511950) |
| Emulator red→green | RED run 45406738360: NPE FATAL EXCEPTION (above) → fix → GREEN run 45408511950: install ✓, `am start -W` `Status: ok` ✓, live pid after 12 s ✓, focused ✓, zero crash ✓ |
| Merge | PR #28 **MERGED** → `22a237e` on `main`, 2026-11-24T14:02:31Z; post-merge `build-apk.yml` run 45409708089 **green 4/4** |
| Release | `release-apk.yml` run 45409707820 **success** → release **v3.4.6.a** LIVE (not draft), tag `v3.4.6.a` = `ee6fc1e`, **exactly 2 assets**: `AnantPlay-Sinhala-Player-v3.4.6.a.apk` (41,811,330 B ≈ 39.9 MiB < 50 MiB, v2+v3, `zipalign -c -P 16 4` + launch-integrity green) and `SHA256SUMS.txt` (104 B) |
| Updater tier-2 | `docs/latest.json` on `main`: `{"version":"3.4.6.a","code":30407,"sha256":"0713415892db62a0df8ad67b639b6117c631d6377e3ba92a98e9f011c281b095",…}` committed with `[skip ci]` (`ee6fc1e`) |

---

## 5. THE FIX SET

| # | Fix | Where |
|---|-----|-------|
| 1 | **Prefs assigned before `super.onCreate`** — the true DOA root cause (§2) | `src/lk/techworld/anantplay/sicast/MainActivity.java` |
| 2 | **Real Android 16 gates** — ELF congruence + L1z stored-zip alignment, `zipalign -P 16 -f 4` → `apksigner` → `zipalign -c -P 16 4` + gate script in both workflows; `useLegacyPackaging=false` | `lab/verify_apk_launch_integrity.py`, both workflows, `android/app/build.gradle` |
| 3 | **Live launch smoke** — x86_64 KVM API-36 emulator, 12 s liveness + focus + zero-crash with full-logcat failure reporting (§3 trap 3) | `.github/workflows/build-apk.yml` |
| 4 | **Audit-truth Gradle** — bogus substitutions removed, `-PsmokeAbi` twin, one-ABI contract enforced by packaging excludes | `android/app/build.gradle` |
| 5 | **Dart-side crash-proofing** — `FlutterError.onError` + `PlatformDispatcher.onError` → new `anantplay/crashlog` channel (persistent CrashLog stamped with this build's versionCode) | `ui/lib/main.dart`, `MainActivity.java` |
| 6 | **Alphabetical version ladder** `3.4.6 < .a < .b < .c < .d < 3.4.7` — native 4th letter dimension, never `NumberFormatException`; CI codes .a=30407…​.d=30410, 3.4.7=30411 | `UpdateChecker.java`, `SelfUpdateCore.java`, `release-apk.yml` |
| 7 | **Version identity sweep** — build.sh, AppInfo, pubspec (semver-valid), both workflows, README, SETUP-SI, manifest comments, ReceiverLab gates: all **3.4.6.a / 30407** | repo-wide |

---

## 6. FULL MORNYE PARITY (the Phase-2 work in this release)

The Mornye v5.4.0 visual effects that v3.4.6 shipped without, now present:

- **`StaggeredListItem`** (upstream `animation_utils.dart` lines 24-112, vendored verbatim with the `loadedFadeFirstFrame` constant): the list-entry reveal — each item springs up after its own delay (spatial spring, may overshoot), opacity on the effects spring, reduced-motion + scrolled-page aware. Wired into **Home** (every section), **Onboarding** (first-stage art/title/body ladder; stage changes keep the AnimatedSwitcher), and **Help** (the 4 action cards).
- **`MornyeGlassPreparation`** (upstream `mornye_glass_preparation.dart`, 30 lines, vendored verbatim): the one-time GPU warm-up when liquid glass is on — placed exactly as upstream `app.dart` places it: `ProviderScope > AppStateScope > MornyeGlassPreparation > MaterialApp`. The first glass frame never janks.
- **The connected settings list** (upstream appearance-page contract): `SettingsGroup` cards — `radiusCard = 24`, one surface per section, dividers between rows, `SettingsItem` row chrome with token paddings, the Mornye `AppSwitch` as the trailing control — replacing the floating per-tile cards. Every real preference (theme, accent, glass clarity 10–95%, outlines, motion, auto-subs, translator, auto-update, backup/reset, licenses) keeps its exact key and write path.

The music-player-specific `MornyeCoverGlow`/`MornyeMotionGlow` remain upstream by design: they need `cover_store` + `video_player`, which in this app are owned natively by the Media3 `PlayActivity` pipeline — the shared entry/glass/list contracts above are the parity surface. The Cinema Player (Media3 1.11.1 hardware pipeline through `Media3TextureBridge`) and the Browser (`WebBrowserView`) are untouched and remain native.

---

## 7. INCIDENT / ROOT-CAUSE SUMMARY (සිංහල)

- **v3.4.6 ක්‍රෑෂ් හේතුව (සජීවී logcat සාක්ෂි):** `MainActivity.onCreate` හි `super.onCreate()` පළමුව කැඳවීම නිසා `configureFlutterEngine()` තුළ `prefs.getBoolean(...)` null `SharedPreferences` එකකින් කියවී → `NullPointerException` → `Unable to start activity` → යෙදුම **විවෘත නොවේ**. නිවැරදිය: `prefs` යෙදීම **පෙර** `super.onCreate(b)`.
- **ELF 16 KB මතභූතය අසත්‍යය:** `0xa74140 → 0xa84140` හි වෙනස `0x10000` — `p_vaddr ≡ p_offset (mod p_align)` නීතිය **සපුරාලයි**; `off % 16384` නීතියක් නොවේ. නමුත් 16 KB තදබද ගේට්ටු (zipalign -P 16, useLegacyPackaging=false, L1z) තවමත් ක්‍රියාත්මකයි — ඒවා දුරස්ථ 16 KB උපාංග සඳහා අවශ්‍ය ශක්තිමත් කිරීමකි.
- **දියත් කිරීම:** PR #28 (checks 4/4 කොළ) → merge `22a237e` → release **v3.4.6.a** සජීවී (APK + SHA256SUMS පමණයි) → `docs/latest.json` = 3.4.6.a / 30407 (`[skip ci]`).
