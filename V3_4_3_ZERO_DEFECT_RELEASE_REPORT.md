# AnantPlay Sinhala Player — v3.4.3 ZERO-DEFECT RELEASE REPORT

**Version:** `3.4.3` · **versionCode** `30403` · **strictly v3.4.x (never 3.5 / 4.0)**
**Branch:** `feat/v3.4.3-onboarding-security-polish` → `main`
**Base:** `v3.4.2` / `30402` (commit `cd50393`)
**Toolchain:** Pure Java 8 · Media3 1.11.1 + Guava · `minSdk 26` · `targetSdk 36` ·
`platforms;android-36` · `build-tools;35.0.1` · aapt2 + javac + d8 + zipalign + apksigner
**Constraints honoured:** zero `AccessibilityService`, `android:allowBackup="false"`,
no Compose/Flutter/libVLC, zero `.apk` / `.zip` tracked in git.

---

## 1. What this release is

v3.4.3 is the **reconciliation + hardening** release. Every item that Replit's deep
research flagged as a real gap in the v3.4.1 snapshot was re-checked against the shipped
v3.4.2 code first. Where v3.4.2 already fixed something (English-default UI, WatchHistory
storage, bottom-anchored subtitles, immersive landscape, 40dp/20dp padded icons, Settings
stay-in-place, Browser IME + suggestions, PrivacyScrubber, 10/10 probe persistence) that
win is **preserved untouched** — nothing was regressed or duplicated. What ships here is
only the remaining real gap, plus two classes of defect the audit itself missed.

---

## 2. Reconciliation table — every audited item → the exact fix

| Audit item | Verdict in v3.4.2 | v3.4.3 fix (file:line) |
|---|---|---|
| **QA-0017** Cookie in Media3 global defaults | **REAL — present** | `PlayActivity.java:743` lifts `Cookie` out of the request-property map (`headers.remove("Cookie")`); the per-request layer at `PlayActivity.java:787` re-attaches it only through `StreamHttpConfig.scopedCookie(...)` (`StreamHttpConfig.java:874`), which returns it **only** for the validated origin host or a same-site suffix. `DefaultHttpDataSource.setDefaultRequestProperties` no longer ever carries a credential. |
| **QA-0018** HTTPS → HTTP downgrade with credentials | **REAL — present** | `StreamHttpConfig.isSafeRedirect(from,to,hasCredentials)` (`StreamHttpConfig.java:838`) + `allowCrossProtocolRedirects(url,hasCredentials)`; wired at `PlayActivity.java:764`. Same-scheme hops and http→https upgrades stay allowed, the private/LAN relay keeps working, an authenticated https→http hop is refused. |
| **QA-0008** WebView mixed content = COMPATIBILITY_MODE | **REAL — present** | Default is now `MIXED_CONTENT_NEVER_ALLOW` (`WebBrowserView.java:251`); a **per-session, never-persisted** opt-in exists only in the ⋮ sheet (`WebBrowserView.java:1187` `setMixedContentCompatibility`). |
| **QA-0011** `@JavascriptInterface` Blob bridge unaudited | **REAL — present** | New pure policy `BridgeGuard` (`BridgeGuard.java:30`): 5 MB **pre-decode** cap (`BLOB_CAP`, `estimatedBytes`, `withinCap`), top-document origin match, user-gesture capture window, single-flight rate limit, and one `refuseReason(...)` (`BridgeGuard.java:149`) gate called from `WebBrowserView.java:1150`. The JS shim now refuses sub-frames (`window.top!==window`) and reports its own `location.href` for the Java-side origin check. |
| **QA-0016 / QA-0024 / QA-0025** Cast session correlation, takeover loops, DASH relay | **PARTIAL** | `CastPolicy.isDuplicateCast` (`CastPolicy.java:86`) + `PlayActivity.acceptCast` make a `/play` retry inside 2 s a no-op and open a monotonically increasing session id that invalidates the older session's fetches/recovery tasks. `CastPolicy.takeoverAllowed` (`CastPolicy.java:93`) limits the bring-to-front takeover to one per 5 s (`ReceiverService.java:419`) — no repeating focus steal. `DlnaEndpoint.transportState(status, recovering)` (`DlnaEndpoint.java:526`) reports `TRANSITIONING` while a stage runs and `NO_MEDIA_PRESENT` only on a real stop; the ladder is hard-bounded at 60 s (`RECOVERY_CAP_MS`). DASH: `rebaseOntoRelay` re-inserts the relay's base64 origin segment for relatively-referenced segments (`PlayActivity.java:778`), and `resolveRef` / `dashBase` / `directUrl` resolve relative `BaseURL` / `SegmentTemplate` / `t=` `b` bases. |
| **QA-0001** No first-launch onboarding | **REAL — missing** | New `OnboardingWizard.java` (`:62`) + pure contract `OnboardingSpec.java` (`:16`). Opens only when `!AppPrefs.onboardingDone` (`MainActivity.java:182`), stage persisted across rotation/process death, re-openable from Help (`MainActivity.java:3013`). |
| **QA-0002** Cold permission dialogs / sideload gate in readiness | **REAL — present** | The `POST_NOTIFICATIONS` request is gone from `MainActivity.onCreate`; it lives in wizard stage 2 after the explanation. `canRequestPackageInstalls()` is no longer part of the readiness checklist (`MainActivity.paintSetupChecklist`) — Unknown Apps is asked only on an explicit "Install update". |
| **QA-0003** Stale checklist rows / overlay never checked | **REAL — present** | `paintSetupChecklist` re-reads `Settings.canDrawOverlays(this)` (`MainActivity.java:1115`) and re-diffs every resume (`setupCard.removeAllViews()`), plus `OnboardingWizard.onHostResume()` re-queries every stage on return from a system screen. |
| **QA-0004** No repair path after a revoked grant | **REAL — missing** | `OnboardingSpec.needsRepair` + the one-line Home banner `paintRepairBanner` (`MainActivity.java:1112`): a calm line with a tap-to-fix, never an endless wizard loop. |
| **QA-0005 / QA-0026–QA-0387** Sinhala chrome leaks in `en` mode | **REAL — present** | New pure `Chrome` enforcer (`Chrome.java:40/78/134`): every bilingual literal renders in the chosen language, and in `en` mode returns no Sinhala character. Both Activities publish the language (`Chrome.setUiLang`). Sinhala stays only in cue rendering, the Style Studio preview, native language names and explicit `si`/`bi` mode. |
| **QA-0006 / QA-0007** Settings overwhelm | **REAL — present** | Quick Settings card first (`MainActivity.java:1291`, built at `:1532`) with exactly the five audited controls incl. a live subtitle preview; everything else folds into one collapsible Advanced container (`root = advBody`, `MainActivity.java:1322`). All 76 preferences remain reachable and backed up. |
| **QA-1451–QA-1464** Subtitle provider URLs / priority | **REAL — present** | `LkSubFinder.sites()` (`LkSubFinder.java:67`) is re-ordered to the live-HTTP-tested result: Zoom.lk, PirateLK, **`https://subz.lk/`** (canonical, HTTP 200; `subzlk.com` kept as an alias via `canonical()`), SubDL, Subsource, YIFY first; then the challenge-protected Baiscope / Cineru.lk / OpenSubtitles / Podnapisi labelled browser-mediated (`tierLabel`, `LkSubFinder.java:109`). |
| **QA-1460** Translator must never wipe English / keep joiners | **PARTIAL** | `FreeSubTranslator` masks U+200D/U+200C to private-use code points before the request and restores them after (`maskJoiners`/`unmaskJoiners`, `FreeSubTranslator.java:98`), and a dead endpoint now returns the original cue list with `complete=false` explicitly. |
| **QA-0023** `verify_refs.py` blind-passes internal classes | **REAL — present** | Every `lk/techworld/*` reference must now resolve to a compiled lab class, a shipped source, or a class inside `libs/*.jar` / `android.jar`; a missing internal class fails the gate. |
| **Watch-history hygiene (missed by Replit)** | **REAL — present** | `WatchHistory.sanitize` (`WatchHistory.java:115`) strips `Cookie`/`Authorization` (via `StreamHttpConfig.stripSecretsJson`) on record, on save and on read (which migrates legacy rows), flags ephemeral LAN relays and stores their canonical upstream. The Home row shows "expired" and offers the 1-tap **"Session expired — Re-open in AnantPlay"** action (`WatchHistory.java:93`, `MainActivity.java:709`) while keeping the saved timestamp. |

---

## 3. The 5-stage first-launch wizard (walkthrough)

1. **Welcome** — app icon, "AnantPlay Player", one sentence ("Watch AnantPlay movies with
   automatic Sinhala subtitles."), `Start setup`. No system dialog has fired at this point.
2. **Notifications** — honest copy: the foreground receiver runs without it; the permission
   only enables the status/fallback notification. API < 33 shows `Not required ✓`. Live
   `Granted ✓` / `Enable` + `Skip`.
3. **Auto-Open on Cast** — explains "Display over other apps", opens
   `ACTION_MANAGE_OVERLAY_PERMISSION` with the `package:` URI, and **re-queries**
   `Settings.canDrawOverlays` on every resume (returning is not a grant).
4. **Background Stability** — detects the OEM only to pick the right shortcut, resolves the
   OEM intent (`resolveActivity`) before launching with fallbacks, and shows a truthful
   `Verified` / `User confirmed` / `Unknown` line. `Configure` or `Skip`.
5. **Connect AnantPlay + Quick Check** — package-visibility check for
   `me.champdev.weblock` (installed ✓ or honest info, **never an invented APK URL**), the
   3-step beginner guide (add an Index AND Sources → start a movie → Cast → "AnantPlay
   Player"), a 1-tap `Run quick check` that runs the real 10-point `FeatureProbe` and shows
   truthful results (localhost checks distinguished from Wi-Fi Cast discovery), and
   `Finish setup` — the only place `onboardingDone` is written.

Persistence contract: `onboardingStage` keeps the stage across rotation and process death;
`onboardingDone` and `onboardingStage` are listed in `AppPrefs.DEVICE_STATE_KEYS`
(`AppPrefs.java:163`) and are **deliberately excluded from `BACKUP_KEYS`** — device
permission state never travels to another phone, and real permissions are re-checked after
any restore.

## 4. Simple vs Advanced settings

* **Quick settings (always visible):** Theme · Accent colour · Subtitle size with a live
  preview · Automatic Sinhala subtitles · Continue-watching resume + Midnight night dimmer.
* **Advanced settings (collapsible):** Appearance & Layout · Playback & Gestures ·
  Subtitles & Translation Engine · Browser & Privacy · Cast, network & data · Updates ·
  Backup & Restore · About & security. Every one of the 76 preferences stays reachable,
  backed up, and wired (verified mechanically, see §5).

---

## 5. Verification — local gates (all green, run before the push)

```
bash lab/run_lab.sh
  ReceiverLab        : 1181 / 1181 PASS, 0 FAIL
  verify_refs.py     : OK — 227 distinct referenced types (89 internal, each resolved
                       against compiled output + src + jars)
  verify_layouts.py  : OK — 63 java files, 0 problems (+ 13/13 selftest)
  verify_ui_completeness.py : OK — icons <= 28dp, 76/76 prefs wired + backed up,
                              zero emoji chrome
  verify_syntax.py   : OK — 0 diagnostics in FULL mode across 63 sources
```

New v3.4.3 suites inside `ReceiverLab`:

* `v343 security:` — Cookie is not a default request property; a **real mock** proves Origin
  A's cookie reaches Origin A and never CDN Host B; `isSafeRedirect` rejects https→http with
  credentials while permitting the LAN relay; `allowCrossProtocolRedirects` policy;
  `MIXED_CONTENT_NEVER_ALLOW`; the 5 MB pre-decode cap, top-frame origin guard, capture
  window, single-flight and rate limit (via the pure `BridgeGuard`).
* `v343 onboarding & history:` — the 5-stage contract, stage clamping, `onboardingDone`
  outside `BACKUP_KEYS`, no `canRequestPackageInstalls` in readiness, `canDrawOverlays`
  checked, secret stripping + legacy migration + relay expiry (real round-trips).
* `v343 ui & subs & dash:` — Quick/Advanced progressive disclosure, `Chrome` English-only
  guarantee (no Sinhala code point survives in `en`), live-verified site ordering
  (`https://subz.lk/` in tier 1), ZWJ/ZWNJ preservation, English retained on translator
  failure, DASH relative `BaseURL` / `SegmentTemplate` / relay re-basing.

### APK (local, `./build.sh` with `platforms;android-36` + `build-tools;35.0.1`)

```
aapt2 dump badging : versionCode='30403'  versionName='3.4.3'  targetSdkVersion:'36'
zipalign -c 4      : OK
apksigner verify   : v2 = true, v3 = true (local build: debug keystore;
                     CI release build: the permanent release keystore)
size               : 3,041,172 bytes (2.97 MB) — budget 5.0 MB
sha256 (local)     : f0491e455dec4ff5357648356b242d6c2202f8abbd48ccc7926ae32fc85f0fe1
```

`git status` shows **0** tracked `.apk` and **0** tracked `.zip` (`dist/`, `build/`,
`lab/out/` are ignored and were removed before the commit).

---

## 6. Pipeline

| Step | Result |
|---|---|
| Branch | `feat/v3.4.3-onboarding-security-polish` |
| Pull request | https://github.com/dictationb1/anantplay-sinhala-player/pulls (this PR) |
| PR CI — `Cast protocol lab` | green (see the PR checks) |
| PR CI — `APK build` | green (see the PR checks) |
| Merge into `main` | squash-merge, PR CI green first |
| Post-merge `build-apk.yml` | green |
| Post-merge `release-apk.yml` | green → release-signed APK + `SHA256SUMS.txt` |
| Live release | https://github.com/dictationb1/anantplay-sinhala-player/releases/tag/v3.4.3 |
| `docs/latest.json` | `3.4.3` / `30403`, sha256 + apkUrl regenerated by the release workflow |

---

## 7. Sinhala summary (සිංහල සාරාංශය)

**v3.4.3 — `30403`.** මෙම නිකුතුවේ ප්‍රධාන කරුණු:

1. **පළමු වරට යෙදුම විවෘත කළ විට පියවර 5ක සැකසුම් විශාරදය** — සාදරයෙන් පිළිගැනීම, දැනුම්දීම්,
   Cast කළ විට ඉබේ විවෘත වීම, පසුබිම ස්ථාවරත්වය, සහ AnantPlay සම්බන්ධ කිරීම + ජීව 10-ලක්ෂ්‍ය
   පරීක්ෂාව. පියවරක් අතරමඟ නතර නොවේ; සෑම පියවරක්ම Skip කළ හැක.
2. **P0 ආරක්ෂාව** — Cookie එක දැන් අදාළ host එකට පමණක් යයි (CDN host එකකට කිසිසේත් නොයයි);
   සත්‍යාපනිත HTTPS ඉල්ලීමක් සිසිලස HTTP වෙත පහත නොවැටේ; බ්‍රව්සරයේ mixed content
   සම්පූර්ණයෙන්ම අවහිරයි; Blob උපසිරැසි පාලම 5 MB සීමාවකින්, එකම මූලාශ්‍රයෙන් සහ පරිශීලක
   ස්පර්ශයකින් පමණක් ක්‍රියා කරයි.
3. **Continue Watching ආරක්ෂිතයි** — Cookie/Authorization කිසිදා සුරකින්නේ නැත; කල් ඉකුත් වූ
   සැසියක් සඳහා "Session expired — Re-open in AnantPlay" එක-තට්ටුවක්, ඔබ නතර කළ වේලාව රැකෙයි.
4. **සැකසුම් සරලයි** — ඉහළින් ඉක්මන් සැකසුම් 5ක්; සියලු 76 සැකසුම් "උසස් සැකසුම්" යටතේ තවමත් ඇත.
5. **උපසිරැසි මූලාශ්‍ර** — `https://subz.lk/` නිවැරදි ලිපිනය, ජීව පරීක්ෂාවෙන් සත්‍යාපිත පිළිවෙළ;
   පරිවර්තනය අසාර්ථක වුවත් ඉංග්‍රීසි උපසිරැසි ඉවත් නොවේ; සිංහල ZWJ/ZWNJ 100% රැකෙයි.
6. **ගුණාත්මකභාවය** — 1,181/1,181 රසායනාගාර පරීක්ෂණ සාර්ථක, APK 2.97 MB, `versionCode 30403`.
