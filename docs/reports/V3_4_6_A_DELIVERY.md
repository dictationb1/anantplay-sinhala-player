# v3.4.6.a — Arena.ai delivery package

This ZIP is the complete `fix/v3.4.6.a-crash-and-full-mornye-ui` branch of
AnantPlay Sinhala Player, exportable as-is into Arena.ai (or any runner with
a JDK 17, Flutter 3.50.1, and the Android SDK 36).

## What's inside

The full repository tree at commit 781c135 (5 commits over main), including:

- `V3_4_6_A_CRASH_FIX_AND_FULL_MORNYE_REPORT.md` — the bilingual incident report
  (root cause, fix set, verification ledger)
- `android/`, `ui/`, `src/`, `res/`, `assets/`, `libs/`, `lab/`, `.github/workflows/`
- Both workflows carry the new launch-integrity gate + the real emulator
  launch-smoke job

## What's intentionally absent

`android/local.properties` (machine-local), `ui/.dart_tool/` and `ui/.android/`
(ephemeral Flutter outputs), any `build/`/`.gradle/` directory, any `.apk`
(the workflow builds and signs it), no keystore material (CI secrets
`KS_BASE64`/`KS_PASS`/`KS_KEYPASS`/`KS_ALIAS` drive signing).

## To run CI exactly as this branch defines it

1. Create the branch `fix/v3.4.6.a-crash-and-full-mornye-ui` on the repo and
   push this tree; open the PR against `main`.
2. CI runs: lab (incl. the launch-gate selftest) → flutter analyze/test →
   Gradle APK build (with the per-ABI 16 KB engine substitution) → the
   launch-integrity gate → the real arm64 Android 36 emulator launch smoke.
3. Merge, then `git tag v3.4.6.a && git push origin v3.4.6.a` triggers the
   release workflow: it builds, gates, signs v2+v3, publishes the release
   assets and rewrites `docs/latest.json` (the updater's tier-2 endpoint).

## The one-line summary

v3.4.6 installed fine and crashed on every launch on Android 16 because the
universal Flutter-engine artifact ships a 4 KB-page-class arm64 `libflutter.so`
(the dynamic linker refuses it on 16 KB-page devices) — and no CI gate ever
audited ELF page alignment or actually launched the app. v3.4.6.a pins the
16 KB-ready per-ABI engine, removes 13.96 MB of dead engines, adds the
launch-integrity gate + a real emulator launch smoke test, completes the
Mornye v5.4.0 visual parity, and installs the alphabetical 3.4.6.a version
scheme (30407) across the app and the self-updater.
