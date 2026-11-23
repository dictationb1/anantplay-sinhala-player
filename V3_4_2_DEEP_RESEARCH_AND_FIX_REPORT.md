# AnantPlay Sinhala Player — v3.4.2 Deep Research & Fix Report

**Version:** `3.4.2` · **versionCode** `30402` · **targetSdk** 36 (Android 16) · **minSdk** 26
**Branch:** `feat/v3.4.2-clean-ui-history-subs-research` → `main`
**Base:** `v3.4.1` (`30401`)
**CI lab:** `bash lab/run_lab.sh` → **1,101 / 1,101 PASS, 0 FAIL**
**APK:** `AnantPlay-Sinhala-Player-v3.4.2.apk` — **3,024,788 bytes (2,953 KiB ≈ 2.89 MB)**, `zipalign -c 4` OK, `apksigner verify` v2 + v3 PASS

Every statement below is either (a) a line of code in this repo, or (b) a check that runs in
`lab/ReceiverLab.java` and passes. Nothing here is a promise about a future release.

---

## 1. Phase 1 — Deep research findings

### 1.1 The Sri Lankan + global subtitle source catalogue

Researched and catalogued, then wired into `LkSubFinder.sites()` in priority order
(Sri Lankan human-verified Sinhala first, global catalogues second):

| # | Source | Home | Search template | Type |
|---|--------|------|-----------------|------|
| 1 | Baiscope | `https://www.baiscope.lk/` | `?s={q}` | SL, human Sinhala |
| 2 | Zoom.lk | `https://zoom.lk/` | `?s={q}` | SL, human Sinhala |
| 3 | PirateLK | `https://piratelk.com/` | `?s={q}` | SL, human Sinhala |
| 4 | Cineru.lk | `https://cineru.lk/` | `?s={q}` | SL, human Sinhala |
| 5 | Subz.lk | `https://subzlk.com/` | `?s={q}` | SL, human Sinhala |
| 6 | SubDL | `https://subdl.com/` | `/search/{q}` | global |
| 7 | OpenSubtitles | `https://www.opensubtitles.org/` | `search2/sublanguageid-sin/moviename-{q}` | global |
| 8 | Subsource | `https://subsource.net/` | `/search/{q}` | global |
| 9 | YIFY subtitles | `https://yifysubtitles.ch/` | `search?q={q}` | global |
| 10 | Podnapisi | `https://www.podnapisi.net/` | `subtitles/search/?keywords={q}` | global |

Notes that shaped the implementation:

* **Baiscope moved to `www.`** — the bare `baiscope.lk` search form 301s to
  `www.baiscope.lk`, so the deep link now points straight at the canonical host
  (the v3.3.0 assertion was updated with it).
* **Subz.lk resolves as `subzlk.com`** — the site publishes under that domain.
* The Sri Lankan sites are all WordPress search endpoints (`?s=`), which is why the
  generic `{q}` template works for all five and why the capture path
  (`.srt/.zip/.vtt/.ass/.ssa/.sub`, including `?file=x.zip`) already covered them.
* There is a community meta-API (`bettercopelk`) that fronts Baiscope / Cineru /
  PirateLK / Zoom.lk, but it is a third-party volunteer deployment: the app does not
  depend on it for correctness, only on the sites' own search pages, so a dead
  deployment can never break subtitle discovery.

### 1.2 The metadata-ranked priority engine (`LkSubFinder.score/rank/best`)

"Which subtitle is *the* one?" is arithmetic, not a guess. `Meta` carries what the
player actually knows (IMDb id, cleaned title, year, season/episode); each
`Candidate` is scored:

| Signal | Weight |
|---|---|
| exact IMDb id match | **+1000** |
| exact cleaned title | +400 |
| partial title containment | +180 |
| year match | +120 |
| season **and** episode match | +250 |
| a *wrong* season/episode | −150 |
| Sinhala, human-verified | +300 |
| Sinhala, machine | +60 |
| English exact (auto-translate path) | +120 |

`rank()` sorts descending and `best()` returns the winner; the lab pins that an
IMDb-exact match always beats a fuzzy title hit, that the ordering is
score-descending, that a wrong episode is penalised, and that an empty list returns
`null` instead of throwing.

### 1.3 Smart title discovery (no "tap to search" nagging)

Four sources are consulted in order, all pure string work so the lab can prove them:

1. the Cast JSON `title` (already parsed in `PlayActivity.parseIntent`),
2. the URL slug — `LkSubFinder.titleFromUrl("…/influencers-2026-sinhala-subtitles/")`
   → `influencers 2026`, with the streaming noise (`with`, `full`, `watch`, `online`,
   `free`, `download`, `sinhala`, `subtitles`) stripped,
3. the page HTML — `LkSubFinder.titleFromHtml()` prefers `og:title`, falls back to
   `<title>`, and trims `| Watch …`-style suffixes,
4. Media3 `MediaMetadata` / the HLS `#EXT-X-TITLE` (already handled by the player).

Only when *all four* fail is a small search affordance shown.

### 1.4 The false "playhead is OUTSIDE cue range" alarm

During the opening credits the playhead is legitimately before the first cue.
`LkSubFinder.isNormalPreRollGap(playhead, firstCueStart)` returns `true` for any
playhead under 3 minutes, or before the first cue — so the player no longer shouts
at the user during a title sequence. The lab pins both the suppressed case and the
genuine 40-minute-gap case.

### 1.5 `tools.newzone.top` subtitle translator

Researched: it is a **free, open-source (MIT), fully client-side** batch translator
(`rockbenben/subtitle-translator`) for `.srt/.ass/.ssa/.vtt/.lrc/.sbv`. It strips
timecodes locally and sends only dialogue to the engine, so the timeline physically
cannot be corrupted; it defaults to the free GTX engine (no API key) and can use
DeepL / Gemini / DeepSeek keys the user already owns; exports are ordinary browser
downloads.

Implementation: the Subtitles sheet carries a **NewZone translator** chip that opens
`https://tools.newzone.top/en/subtitle-translator` inside the app's own Chromium tab
(`openNewZoneTranslator()`). Because the export is a normal download, the existing
`DownloadListener` + `LkSubFinder.isSubtitleDownload()` capture path already attaches
the resulting `.srt` live to the running player — no new plumbing was needed, and no
Blob-only path is relied upon (the tool's downloads are real files).

### 1.6 Voice / audio-to-subtitle AI extraction — honest feasibility

When no subtitle exists anywhere on the internet, there are exactly three realistic
routes on Android 16 with a ≤5 MB APK. All three were analysed; the two that fit the
APK budget were implemented, the third was rejected on measured cost.

| Option | How it would work | Latency | APK impact | Verdict |
|---|---|---|---|---|
| **A1 — Cloudflare Workers AI `@cf/openai/whisper`** via the user's own Worker | Capture a PCM/WAV slice with a Media3 `TeeAudioProcessor`, POST it to the Worker, receive text + segments | ~1–3 s per 30 s slice | **0 bytes** (already the app's Worker path) | **Implemented** — chip 1 of the Audio→SRT helper re-runs the Worker/Whisper path |
| **A2 — AI Studio / Gemini with a prepared prompt** | Copy a ready-made "transcribe to timestamped SRT + translate to Sinhala" prompt, open the user's AI Studio, paste the exported clip | manual, ~1 min | 0 bytes | **Implemented** — chip 2 copies the exact prompt |
| **B — on-device `SpeechRecognizer` / Live Caption bridge** | Feed the audio session to the platform recogniser | real-time | 0 bytes | **Rejected**: `SpeechRecognizer` cannot consume an arbitrary Media3 audio session on Android 16 without an `AccessibilityService` (a hard project rule is *zero* AccessibilityService), and it returns no reliable cue timings |
| **C — bundled on-device Whisper (whisper.cpp / TFLite)** | Ship the model in the APK | fast after load | **+40–150 MB** model | **Rejected**: violates the ≤5.0 MB APK budget outright |

Measured cost of A1, for the record: Workers AI bills **41.14 neurons per audio
minute** against a free allocation of **10,000 neurons/day**, i.e. roughly **4 hours
of audio per day free**, at a rate limit of **720 ASR requests/minute**. That is
ample for one movie a night, and it is the user's own Worker/credentials — the app
ships no key.

The helper is `PlayActivity.showAudioToSrtHelper()`: three explicit chips
(`Whisper via my Worker (auto)` · `Copy the AI Studio transcript prompt` ·
`Pick a finished .srt file`). All three end in the same place — a timestamped `.srt`
attached live to the player — which is the only contract that matters.

---

## 2. The three field screenshots — exact `file:line` causes and fixes

### 2.1 Screenshot 2 — subtitle across the actors' faces, stuck pill, fake fullscreen

| # | Defect | Root cause (v3.4.1) | Fix (v3.4.2) |
|---|---|---|---|
| 1 | Sinhala line rendered **dead-centre**, across faces | `PlayActivity.java:1768` `subView.setGravity(Gravity.CENTER)` combined with a layer whose only vertical anchor came from padding — the view could be laid out anywhere in the padded box | `PlayActivity.java` `subView.setGravity(Gravity.CENTER_HORIZONTAL)`; `subLayoutParams()` anchors **`Gravity.BOTTOM \| Gravity.CENTER_HORIZONTAL`** with `bottomMargin` = **5% landscape / 8% portrait**, capped at 20% |
| 2 | `"සිංහල උපසිරැසි සූදානම් · 1909 cues · ai"` pill **stuck over a forehead** | `showStatus()` scheduled `pillHide` only for the "ready" state and `pillHide` set `GONE` with no animation; any later repaint re-showed it and nothing re-armed the timer | `showStatus()` now `removeCallbacks(pillHide)` then `postDelayed(pillHide, 2500)` and resets `alpha=1f`; `pillHide` fades `alpha(0f).setDuration(220)` → `GONE` |
| 3 | Landscape **not immersive** (status bar `26`, Wi-Fi, gesture bar visible) | Nothing ever called `WindowInsetsController.hide(...)`; the HUD hide path only did `hud.setVisibility(GONE)` | new `applyImmersive(boolean)` hides `statusBars() \| navigationBars()` with `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`; called `true` from `hudHide` and `toggleControls`, `false` from `showControls` and at `buildUi` end |
| 4 | Duplicate "Searching human Sinhala subs…" (under title **and** in a pill) | both `hudStatus` and `statusPill` printed `stateDetail` | one indicator only: `hudStatus` stays the compact top-bar line, the pill is the transient toast — and it now fades |

### 2.2 Screenshots 1 & 3 — giant icons and colliding floating rows

| # | Defect | Root cause (v3.4.1) | Fix (v3.4.2) |
|---|---|---|---|
| 5 | Top-right `Lock`/`Rotate`/`External`/`Settings` icons **burst out of their circles** | `icon()` drew a 24 dp-intrinsic vector into a 36 dp `ImageView` with **no `setPadding`**, corner radius 12 dp → the glyph filled the plate | `icon()` now applies `pad = (40 − size)/2` (**10 dp** around a **20 dp** glyph), plate is **40 dp**, corner radius 20 dp (a true circle); `topIconParams()` gives every plate 3 dp gutters |
| 6 | `Retry / Provider / Local .srt / ±0.25s`, language chips (one **squished into a vertical box**), `Spoken/Formal` + `AI Studio` **collided with `−10 / Pause / +10`** | `buildUi()` added three absolutely-positioned rows (`subActions`, `langChips`, `extraActions`) into `pillBox`, which shares the overlay band with the centre transport zone | all three rows **deleted** (fields removed, `showSubActions()` reduced to "show the pill"); every control moved into the Subtitles `UiSheet` as `WRAP_CONTENT` chips in horizontally-scrolling strips (`toolsRow`, `aiRow`, `speedRow`) — a chip can no longer be squashed |

### 2.3 Settings jumped back to Home on every change

* Cause: `MainActivity.themeChanged()` did `closeSubPage(); … recreate();` — `recreate()`
  rebuilt the shell on `TAB_HOME` and discarded the open sub-page.
* Fix: `rememberPlace()` captures `currentTab`, the live `ScrollView` offset and a
  re-runnable `lastSubOpener` (set by `openSub`) into three `static volatile` slots;
  `restorePlace()` (called from `onCreate` after `setContentView`) restores tab →
  re-opens the same sub-page → scrolls back, then **clears the slots** so a cold start
  still lands on Home. `closeSubPage()` clears `lastSubOpener` so a closed page is
  never resurrected.

### 2.4 Web browser could not be typed into

* Causes: `MainActivity` had **no `windowSoftInputMode`**, and nothing in
  `WebBrowserView` ever read `WindowInsets.Type.ime()`; the omnibar stayed under the
  keyboard and the bottom nav bar floated on top of it.
* Fix: `android:windowSoftInputMode="adjustResize"` on `.MainActivity`; the shell's
  inset listener reads `ime()` and (a) pads `pages` by `ime − navBars`, (b) hides
  `bottomBar` while the keyboard is up; `WebBrowserView.applyImeInsets()` applies
  `ime − navBars` to the **omnibar host** and calls `requestRectangleOnScreen()` on
  the field. Live suggestions: `scheduleSuggestions()` debounces **250 ms**, fetches
  HTTPS DuckDuckGo (`duckduckgo.com/ac/?type=list&q=`) or Google
  (`suggestqueries.google.com/complete/search?client=firefox`) **off the UI thread**
  with a 4 s timeout, drops stale responses (`if (!query.equals(now)) return;`), and
  renders a 1-tap dropdown inside the same linear column as the omnibar.

### 2.5 `PrivacyScrubber` over-scrubbing

* Cause: the v3.4.1 hardware-token regex was a blind digit-run matcher
  (`[a-z0-9]{3}\d[a-z]?[0-9]{3,4}…`), so it rewrote IMDb ids and millisecond values:
  `imdb=tt33764258` → `imdb=<phone-model>`, `cues 90956-6842000` →
  `cues 90956-<phone-model>`.
* Fix: the regex now only **proposes** a token; `isHwModel()` decides. A real model
  carries ≥2 upper-case letters **and** ≥1 digit and is not an IMDb id, not a pure
  digit run, not a version, and not a known technology token (`SDK`, `HTTP`, `H264`,
  `EXO`, …). `VENDOR_MODEL` also learned `pixel`/`galaxy` and accepts one-character
  model tails, closing the `Pixel 9` / `Galaxy A52` leak the old pattern had.
  Result: `tt33764258`, `t0=141776`, `playhead=832031`, `90956-6842000`, `h264`,
  `1080p`, `SDK 36`, `8765`, `30402`, `http 403`, `EXO-1` all survive byte-identical,
  while `Xiaomi 24117RN76G`, `24117RN76G`, `M2101K6G`, `SM-S928B`, `CPH2451`,
  `Pixel 9`, EUI-64 IPv6, LAN IPs, cookies and `Build.MODEL=` are still scrubbed.

### 2.6 `(not run yet)` after a passing 10/10 run

* Cause: `Troubleshooter.probeSection(probeOutcomes)` received `MainActivity`'s
  **instance** field, which is empty after any tab switch or `recreate()`.
* Fix: `FeatureProbe.remember()` publishes a completed run into the existing static
  snapshot; `probeSection(null|empty)` falls back to `FeatureProbe.lastResults()`;
  `MainActivity.seedProbeOutcomes()` re-reads it in `onCreate`; `troubleshootReport()`
  passes the snapshot explicitly. The lab proves the report prints all ten rows and
  `10/10 PASS` from a null/empty argument.

---

## 3. New features

1. **Clean English default UI** — `AppPrefs.uiLang()` defaults to `UI_EN`;
   `normalizeUiLang(null|unknown)` returns `en`; `si` and `bi` remain first-class
   options in Settings. App label is `AnantPlay Player`. **Movie subtitles stay
   Sinhala** (`K_DEFAULT_LANG` untouched).
2. **Continue Watching** (`WatchHistory.java`, pure Java + JSON) — records on pause,
   destroy and seek; deduped by URL (else title); newest-first; capped at **20**;
   `resumable()` excludes finished titles; `progressLabel()` prints
   **`0:42:15 / 1:58:00`**; Home renders a card with a real progress bar and 1-tap
   resume that re-opens `PlayActivity` at the saved millisecond (`startMs`).
3. **Settings stay in place** — tab + sub-page + scroll survive every `recreate()`.
4. **1-tap "Auto-Open Player on Cast"** banner on Home whenever
   `!Settings.canDrawOverlays(this)`, opening `ACTION_MANAGE_OVERLAY_PERMISSION`
   with the package URI; it disappears the moment the grant exists.
5. **Midnight Night Dimmer** — `K_MIDNIGHT_DIMMER` + `K_MIDNIGHT_DIM_PCT` (10–70%),
   applied as `screenBrightness` **and** a non-touch-blocking veil in both the shell
   and the player; both keys ride Backup & Restore.
6. **Sensor auto-rotate + orientation lock** — `FULL_SENSOR` for Auto, with a 3-way
   sheet (Auto / Lock landscape / Lock portrait).
7. **Hold-for-2.0× speed boost** — 550 ms hold → `2.0×` with a "2.0× Speed" pill;
   release restores the exact previous speed; plus a 0.5×–2.5× picker.
8. **Subtitle tools relocated into the Subtitles sheet** — Retry, Provider, ±0.25 s,
   Spoken/Formal, AI Studio, NewZone translator, Audio→SRT (AI), speed chips.

---

## 4. CI lab — 1,101 checks, 0 failures

`bash lab/run_lab.sh` (all gating):

```
Lab proof: 1101 checks passed (0 failed)
verify_refs:        OK
verify_layouts:     OK (13/13 selftest cases still rejected)
verify_ui_completeness: OK — 76 AppPrefs keys, 0 missing from BACKUP_KEYS,
                        0 unreachable, 0 emoji chrome, every icon ≤ 28 dp
verify_syntax:      OK — 0 diagnostics across 59 shipped sources (android-36 + 13 jars)
```

New v3.4.2 suites (all inside `ReceiverLab`):

| Suite | What it proves |
|---|---|
| `v342 privacy` | IMDb ids, `t0=141776`, `playhead=832031`, cue ranges, codecs, ports and versions survive; every real hardware identifier is still scrubbed; `scrub()` is idempotent; 0 canary leaks |
| `v342 history` | clock/percent/label formatting, add, dedupe-update, newest-first, cap-20, JSON round-trip (incl. quoted titles), corrupt-blob safety, resume-ms, clear-one/clear-all, real `SharedPreferences` persistence; English default, dimmer prefs + `BACKUP_KEYS` coverage, 16 sp default |
| `v342 player` | 40 dp plate + real padding + `topIconParams()`; `Gravity.BOTTOM` subtitle anchor; 5%/8% margins; 2.5 s fade with alpha animation; the three legacy rows are deleted; immersive hides both bar types; `FULL_SENSOR`; hold-2× restores speed; 2.5× picker; history wired; `startMs` resume; `themeChanged()` no longer closes the sub-page; Continue watching + overlay banner + dimmer + probe re-seed; `adjustResize`; `ime()` insets; nav bar hides; 250 ms debounce; HTTPS endpoints; stale-response drop |
| `v342 subs` | all 10 sources wired, 5 Sri Lankan first, `{q}` templates, IMDb-exact wins the ranking, score ordering, human-Sinhala > English, wrong-episode penalty, `best()` null-safety, slug/og:title/`<title>` title discovery, pre-roll gap suppression, NewZone + Audio→SRT wiring, and the 10/10 report printing from the static snapshot |

---

## 5. Release

* **Local verified build:** `dist/AnantPlay-Sinhala-Player-v3.4.2.apk`
  — `versionCode='30402'`, `versionName='3.4.2'`, `compileSdkVersion='36'`,
  **3,024,788 bytes (2,953 KiB)**, `zipalign -c 4` OK, `apksigner verify` v2+v3 PASS.
  SHA-256 (this debug-signed local artifact):
  `f07096d5831dfa9ceeed14a6b7ea0e7a32dd930d3079bf0db6b4b1aceeb2879a`
* **Release artifact:** the hosted `release-apk.yml` run re-signs the same commit with
  the permanent release keystore and publishes
  `https://github.com/dictationb1/anantplay-sinhala-player/releases/tag/v3.4.2`
  with `AnantPlay-Sinhala-Player-v3.4.2.apk` + `SHA256SUMS.txt`; the authoritative
  release hash is the one in `SHA256SUMS.txt` and in `docs/latest.json`.
* `docs/latest.json` now announces `3.4.2 / 30402` pointing at the `v3.4.2` asset.
* `dist/` and `build/` are git-ignored: **0 `.apk` and 0 `.zip` are tracked.**

---

## 6. සිංහල සාරාංශය (Sinhala summary)

**v3.4.2 — පිරිසිදු ඉංග්‍රීසි UI, Continue Watching, ධාවක නිවැරදි කිරීම්, උපසිරැසි මූලාශ්‍ර 10ක්, CI 1,101/1,101.**

* ඔබේ තිර රුව තුනේ පෙනුණු සියල්ල මූලයේදීම නිවැරදි කළා: උපසිරැසි දැන් **පහළින්**
  (landscape 5%, portrait 8%), "උපසිරැසි සූදානම්" pill **තත්පර 2.5කින්** මැකෙනවා,
  ඉහළ-දකුණු අයිකන **40dp තැටිය තුළ 20dp** ලෙස පිරිසිදුයි, පැටලෙන
  Retry/Provider/Language/Spoken/AI Studio පේළි වීඩියෝව මතින් **ඉවත් කළා**
  (දැන් Subtitles sheet එකේ), landscape **සැබෑ fullscreen**.
* **Continue Watching** — මුල් තිරයේ progress bar, `0:42:15 / 1:58:00`, 1-tap resume.
* **Midnight Night Dimmer** (10–70%), **Auto-Open on Cast** එක-වරක් සැකසුම,
  **sensor auto-rotate + orientation lock**, **Hold-2×** සහ **0.5×–2.5×** වේගය.
* **Settings තැනේම රැඳේ**; **බ්‍රව්සරයේ** omnibar keyboard එකට ඉහළින් + live suggestions.
* **PrivacyScrubber** දැන් IMDb id සහ මිලි-තත්පර අංක **විනාශ නොකරයි**, නමුත් දුරකථන
  මාදිලි/IP/MAC/cookie **සම්පූර්ණයෙන්** ඉවත් කරයි. **10/10 probe වාර්තාව** දැන්
  `(not run yet)` නොකියයි.
* UI **default ඉංග්‍රීසි**; සිංහල UI Settings එකෙන් තෝරාගත හැක. **චිත්‍රපට උපසිරැසි තවමත් සිංහල.**
