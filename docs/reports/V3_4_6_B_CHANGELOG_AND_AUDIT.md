# v3.4.6.b — CHANGELOG & AUDIT (the Finish-button fix, the six-slide wizard, the repo cleanup)

**Release identity:** `versionName 3.4.6.b` · `versionCode 30408` · tag `v3.4.6.b` ·
APK `AnantPlay-Sinhala-Player-v3.4.6.b.apk` · `targetSdk 36` · `arm64-v8a` only · < 50 MiB.

**Ladder (never skipped, never jumped to 3.5/4.0):**
`3.4.6.a (30407) → 3.4.6.b (30408) → 3.4.6.c (30409) → 3.4.6.d (30410) → 3.4.7 (30411)`

---

## 1. The Finish contract (onboarding can no longer lock the user out)

v3.4.6.b makes the wizard's **Finish** step write its completion flag through a dedicated native
path instead of the generic settings writer:

1. **A dedicated native writer.** `MainActivity` handles a `finishOnboarding` method-channel call
   and persists `AppPrefs.setOnboardingDone(prefs, true)` and stage `5`
   (`MainActivity.java`, `finishOnboarding` branch).
2. **The keys are part of the live settings blob.** `gatherSettings()` includes
   `K_ONBOARDING_DONE` and `K_ONBOARDING_STAGE`; the Flutter shell reads them back.
3. **A regression test.** `ui/test/shell_test.dart` → `THE FINISH CONTRACT: Finish flips the shell
   into HomeScreen` walks the real onboarding to the last slide, taps Finish and asserts that the
   shell shows `HomeScreen`.

**Startup NPE guard (unchanged):** `prefs = getSharedPreferences("sicast", MODE_PRIVATE)` is still
assigned **before** `super.onCreate(b)` — the v3.4.6 startup-NPE fix. Reordering it re-opens that
incident.

---

## 2. The six-slide swipeable onboarding

The wizard has **six** slides (`_pageCount = 6` in `ui/lib/anantplay/onboarding_screen.dart`) and
uses a `PageView.builder` (free swipe). Titles, in order (`_titles`):

| # | Slide title | Purpose |
| --- | --- | --- |
| 1 | Welcome | What the app is. |
| 2 | Install AnantPlay | Installs the official AnantPlay app through `AnantPlayInstaller`. |
| 3 | Notifications | Notification permission; the pill flips to *Enabled* and auto-advances after 600 ms. |
| 4 | Auto-open on cast | Display-over-other-apps permission; state is read live from native (`canDrawOverlays`). |
| 5 | Keep Cast ready | Battery-optimisation settings deep link. |
| 6 | Sources & Index | Index URL helper (copy / save) and the Finish step. |

`AppPrefs.onboardingStage()` is clamped to **0..5** (it was 0..4 before).

---

## 3. `AnantPlayInstaller` — the in-app official-app installer

New file `src/lk/techworld/anantplay/sicast/AnantPlayInstaller.java`. As written:

- **HTTPS-only source.** It resolves `https://github.com/AnantPlay/android/releases/latest` through
  `SelfUpdateCore.resolveLatestTagByRedirect` (the redirect path, not the REST API) and checks
  asset URLs with `SelfUpdateCore.httpsOnly`.
- **Own size cap:** `MAX_APK_BYTES = 200 MiB − 1` for the official app. The self-updater's
  **55 MiB** cap in `SelfUpdateCore` is unchanged.
- **Download to cache, streamed with progress.** The APK is written under `cacheDir/weblock/`.
  `sweepCache()` deletes it on resume, and the download path deletes it on failure.
- **`ACTION_MANAGE_UNKNOWN_APP_SOURCES`** is scoped to this app's own package (`openUnknownSources`
  in `MainActivity`).
- **Handoff:** a `PackageInstaller` session whose result goes to the existing
  `SelfUpdater.InstallResultReceiver` (PendingIntent request code `32046`), so the user confirms
  through Android's own system sheet.

---

## 4. Sources & Index helper

`AppPrefs.java` documents that the official app's `SourcesActivity` is `exported="false"`, so there
is no deep link for adding an index. The helper therefore does the simple, honest thing:
`K_INDEX_URL` (a **backed-up** preference, default
`https://raw.githubusercontent.com/AnantPlay/index/main/index.json`), a `copyToClipboard` channel,
a `saveIndexUrl` writer, and an "Open AnantPlay" action.

---

## 5. Repository root cleanup — the 24-file report dump is gone

The root had accumulated **24 historical reports**. They are all archived under
**`docs/reports/`** now (history preserved byte-for-byte, nothing deleted):

| Moved from root | Now |
| --- | --- |
| `V3_*.md` (17 files, `V3_1_*` … `V3_4_6_*` + `V3_MASTER_*`) | `docs/reports/` |
| `ANDROID16_CAST_AND_AUTOUPDATE_REPORT_v2.4.md` | `docs/reports/` |
| `FORENSIC_FIX_REPORT_v2.3.1.md` | `docs/reports/` |
| `ROUTER_FREE_CAST_AND_OBTAINIUM_REPORT.md` | `docs/reports/` |
| `DESIGN_AND_UI_OVERHAUL_DEEP_RESEARCH_BLUEPRINT.md` | `docs/reports/` |
| `REPLIT_UI_ONLY_AUDIT_LOG.txt` | `docs/reports/` |
| `REPLIT_UI_REDESIGN_AND_CANVA_BLUEPRINT.md` / `.pdf` | `docs/reports/` |
| `replit.md` | `docs/reports/` |
| `docs/RELEASE_REPORT_v4.1_v2.2.md` (already off-root) | `docs/reports/` |

**The root is now exactly (verified against the git index):** `README.md` · `SETUP-SI.md` · `LICENSE` · `THIRD_PARTY.md` ·
`AndroidManifest.xml` · `build.sh` · `.gitignore`, plus the project directories
(`.github/ android/ assets/ design/ docs/ lab/ libs/ res/ scripts/ src/ tools/ ui/`).

### The lab-path fix (no assertion weakened)

`lab/ReceiverLab.java` proved the reports existed with `new java.io.File("V3_3_1_….md").isFile()`.
Those three checks now point at `docs/reports/…` — **same assertion, new path** — plus three new
`v346b hygiene` checks: the reports are archived, the root carries only the essentials, and the
upload junk is absent. `README.md`, `SETUP-SI.md` and `release-apk.yml` cross-references were
re-pointed too.

### CI guard so the clutter cannot come back

`build-apk.yml` → `Cast protocol lab` job gained a gating step **"Clean repo root +
docs/reports archive (v3.4.6.b)"**: the tracked root-file list must equal the allow-list exactly,
`docs/reports/` must contain the archive, and `git ls-files` must return **0** for
`ui/.ios/**`, `**/__pycache__/**`, `*.pyc`, `ui/.flutter-plugins-dependencies`,
`*.zip`, `*.apk`, `*.aab`.

---

## 6. Accidental-upload junk purged from the archive

The `v3.4.6.b-mornye-upgrade.zip` source drop carried **559 entries, every one nested under a
leading `anantplay-sinhala-player/` prefix**. It was audited for `..` traversal (none), extracted
to a temp dir, and the **contents** were synced onto the repo root — there is no nested
`anantplay-sinhala-player/` directory in the repo. Purged before commit:

- `ui/.ios/` — **52** Xcode / Storyboard / Podfile / `Assets.xcassets` files for an iOS target
  this project does not ship;
- `ui/.flutter-plugins-dependencies` — machine-local Flutter state;
- `lab/__pycache__/verify_apk_launch_integrity.cpython-312.pyc` — bytecode;
- `v3.4.6.b-mornye-upgrade.zip` itself (it was tracked!) — `git rm -f`'d, so
  `git ls-files '*.zip' '*.apk'` returns **0**.

`.gitignore` now also ignores `ui/.ios/`, `**/.ios/`, `**/__pycache__/`, `**/*.pyc`,
`**/.flutter-plugins-dependencies`, `*.7z`, `*.tar.gz`.

---

## 7. Build identity audit

| Surface | Value |
| --- | --- |
| `android/app/build.gradle` | `versionCode = 30408`, `versionName = "3.4.6.b"`, `targetSdk = 36`, `minSdk = 26`, `abiFilters "arm64-v8a"` |
| `AppInfo.java` | `VERSION_NAME = "3.4.6.b"`, `VERSION_CODE = 30408` |
| `build.sh` | `VERSION_NAME:-3.4.6.b`, `VERSION_CODE:-30408`, `PREV_VERSION_CODE:-30407` |
| `ui/pubspec.yaml` | `version: 3.4.6+30408` — **pure integer build number** (see below) |
| `.github/workflows/build-apk.yml` | asserts `3.4.6.b` / `30408` in all three jobs |
| `.github/workflows/release-apk.yml` | `default: '3.4.6.b'`, letter lane `b → +2 = 30408` |
| `lab/ReceiverLab.java` | `latest.json` accepts `3.4.6.b` / `30408` as the current signed release |

### `pubspec.yaml` — why `+30408` and not `+30408.b`

`pub_semver` accepts a multi-identifier build (`+30408.b` parses), so `flutter pub get` survives
either form. But the Flutter Gradle plugin types `flutter.versionCode` as an **Integer** and
forwards `BuildNumber` verbatim into `flutter assemble`; a trailing `.b` is a
`NumberFormatException` waiting for the first tool that reads it. The release identity lives in
`android/app/build.gradle` and `AppInfo.java`, so the pubspec build number is now the pure
integer `30408` — guaranteed valid for **both** `pub_semver` **and** Gradle. The `.b` stays in
the pubspec `description` (`v3.4.6.b`) and in the shipped `versionName`.

### Release-ladder bug fixed in `release-apk.yml`

The blind `30407 → 30408` sweep had changed the "first numeric after the letter lane" branch to
`[ "$BASE" -eq 30408 ]`. `BASE` for `3.4.7` is `3*10000 + 4*100 + 7 = 30407`; `30408` is
`3.4.6.b`'s *code*, not a base. Left as-is, a future `3.4.7` would have fallen into the
`+9` bucket and published `30416` instead of the documented `30411`. Restored to `-eq 30407`
with a comment explaining why. (`3.4.6.b` itself is unaffected — it takes the letter lane.)

---

## 8. Security & integrity contract (unchanged, re-verified)

- **Zero `AccessibilityService`** declarations or API references in `AndroidManifest.xml`,
  `src/` and `lab/`.
- `android:allowBackup="false"`.
- `zipalign -P 16 -f 4` → `apksigner sign` → `zipalign -c -P 16 4` → `apksigner verify`
  (`v2 true` + `v3 true`), plus `verify_apk_launch_integrity.py` (16 KB-page ELF audit of every
  packaged `.so`, one-ABI contract, `libapp.so` + asset pair).
- Release APK: **`arm64-v8a` only, < 50 MiB**, `targetSdk 36`.
- `SelfUpdateCore`: **55 MiB** cap + alphabetical parser
  `3.4.6 < 3.4.6.a < 3.4.6.b < 3.4.6.c < 3.4.6.d < 3.4.7`.

---

## 9. Live verification

**Status: PENDING** — this section is filled in from the hosted run after the PR's four jobs
finish (see the final report for the run URLs, merged SHA and release proof).

Local pre-checks run in the authoring sandbox (no JDK / Flutter available there):

| Check | Result |
| --- | --- |
| `lab/verify_gradle_host.py` | 64 checks, 0 failed |
| `lab/verify_layouts.py` | 68 files, 299 layout calls, 0 problems |
| `lab/verify_ui_completeness.py` | OK (icons ≤ 28 dp, 0 unbacked prefs, 0 emoji chrome) |
| `lab/verify_v344_ui.py` | 1861 PASS, 0 FAIL |
| `lab/verify_v345_ui.py` | 57 passed, 0 failed |
| `sh -n build.sh`, `sh -n lab/run_lab.sh`, emulator `script:` block | OK |
| Both workflow YAML files | parse (PyYAML) |
| New `Clean repo root` CI step (run locally) | root = 7 essentials, reports archived, junk = 0 |
| `git ls-files '*.zip' '*.apk'` | 0 |

`lab/verify_syntax.py` and the full `lab/run_lab.sh` need a JDK; they are exercised by the hosted
`Cast protocol lab` job, not locally.

---

## 10. සිංහල සාරාංශය (Sinhala summary)

**v3.4.6.b — Finish බොත්තම නිවැරදි කිරීම · පිටු හයේ සජීවී ඔන්බෝඩිං විසඳුම · ගබඩාව පිරිසිදු කිරීම**

- **Finish බොත්තම.** `finishOnboarding` කියන කැපවූ native මාර්ගය `K_ONBOARDING_DONE = true`
  සහ stage 5 ලියයි; `THE FINISH CONTRACT` ටෙස්ට් එකෙන් එම ගමන `HomeScreen` දක්වා පරීක්ෂා කරයි.
  `prefs` ආරම්භය `super.onCreate` ට පෙර පවතී (v3.4.6 NPE නිවැරදි කිරීම).
- **පිටු හයක සජීවී ඔන්බෝඩිං** — ස්වායු කළ හැකි PageView: Welcome → AnantPlay ස්ථාපනය →
  දැනුම්දීම් → Overlay → පසුබිම ස්ථාවරත්වය → Sources/Index උදව්කාරකය. සියලු අවසර
  පෙට්ටි සජීවී native තත්ත්වයෙන් යාවත්කාලීන වේ.
- **`AnantPlayInstaller`** — HTTPS redirect මගින් නවතම release සොයයි, 200 MiB සීමාව, cache
  පිරිසිදු කිරීම, `ACTION_MANAGE_UNKNOWN_APP_SOURCES` (තමන්ගේ package පමණි), සහ Android ගේම
  තහවුරු කිරීමේ තිරය (PackageInstaller).
- **Sources & Index උදව්කාරකය** — index URL එක තැන්පත් කර පිටපත් කිරීම, AnantPlay විවෘත කිරීම.
- **ගබඩා මූලය පිරිසිදුයි** — ඓතිහාසික වාර්තා 24ක් `docs/reports/` වෙත ගෙන ගියේය; iOS/Xcode
  අනවශ්‍ය ගොනු, `.pyc`, `.zip`, `.apk` ගබඩාවෙන් සම්පූර්ණයෙන් ඉවත් කළේය (0 tracked).
  CI එක දැන් මූලය පිරිසිදුව සහ `docs/reports/` පැවතීම දැඩියේ පරීක්ෂා කරයි.
- **අනන්‍යතාවය:** `3.4.6.b` / `30408` — `arm64-v8a`, `targetSdk 36`, < 50 MiB, 16 KB-page
  aligned, v2+v3 අත්සන් සහිත. `3.4.6.a → .b → .c → .d → 3.4.7` පෙළගෙඩිය කිසිදා නොකැඩේ.
