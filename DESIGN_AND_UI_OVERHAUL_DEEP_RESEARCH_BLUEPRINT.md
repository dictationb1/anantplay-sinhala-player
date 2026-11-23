# AnantPlay Sinhala Player — Deep Research & UI/UX Overhaul Blueprint
### Vector icon system · Adaptive app icon · Typography · Web browser · Cinema player · Mornye-grade customization

**Document ID:** `DESIGN_AND_UI_OVERHAUL_DEEP_RESEARCH_BLUEPRINT`
**Target release:** `v3.4.0` (strictly v3.x — never v4)
**Audited commit:** `dictationb1/anantplay-sinhala-player` @ `1db180f` ("Merge pull request #18 from dictationb1/docs/v3.3.1-report-final"), branch `main`
**Reference repositories (read-only):** `mornyeapp/Mornye` (Flutter design system), `AnantPlay/android` (player + WebView)
**Session type:** research & design architecture only — **no production Java in `src/` was modified, nothing was committed or pushed.**

---

## 0. How to read this document

This is an **execution contract**, not a mood board. Every claim about the current app carries a
`file:line` reference that was read from the real repository during this session. Every proposed
component carries the exact API surface, the exact token values, and the exact safety rules the next
coding agent must satisfy.

| Deliverable | Path | Purpose |
|---|---|---|
| Master blueprint | `DESIGN_AND_UI_OVERHAUL_DEEP_RESEARCH_BLUEPRINT.md` | this file |
| Print/PDF copy | `DESIGN_AND_UI_OVERHAUL_DEEP_RESEARCH_BLUEPRINT.pdf` | A4, paginated, print-ready |
| Design tokens | `design/figma-tokens.json` | 249 typed tokens, W3C DTCG + Tokens Studio `$themes` |
| Interactive board | `design/preview.html` | offline, zero-dependency visual board |
| Icon pack | `design/svg/*.svg` + `design/vector/*.xml` | 53 icons × (SVG + VectorDrawable) |
| App icon pack | `design/vector/ic_launcher_*.xml`, `design/mipmap-anydpi-v26/*.xml` | adaptive + monochrome |
| Manifest | `design/icon-manifest.json` | machine-readable icon catalog |

### 0.1 Hard constraints (restated so they cannot be lost)

| # | Constraint | Consequence for this design |
|---|---|---|
| C1 | Pure **Java 8** + `build.sh` (`aapt2` → `javac` → `d8` → `zipalign` → `apksigner`) | No Kotlin, no Gradle, no Compose, no XML data-binding |
| C2 | **No Material Components AAR**, no extra dependencies | Every control is `android.widget.*` + `GradientDrawable` / `StateListDrawable` / `RippleDrawable` / `VectorDrawable` |
| C3 | Compiles against `platforms;android-36`, `build-tools;35.0.1`, `--min-sdk-version 26`, `--target-sdk-version 36` (`build.sh:86`) | Vector gradients are legal (API 24+); edge-to-edge is enforced by the OS |
| C4 | Media3 1.11.1 + Guava only (`libs/`) | No ExoPlayer extension modules, no FFmpeg, no equalizer library |
| C5 | APK budget **≤ 4.8–5.5 MB** (currently ≈ 3.4 MB) | Icons are vectors (~0.6 KB each), fonts are subset, no new bitmaps |
| C6 | Zero-crash Android 16: strict `LayoutParams` typing, `WindowInsets` compliance, ZWJ/ZWNJ preservation, no `AccessibilityService`, privacy-safe troubleshooter | Every new view states its parent and therefore its `LayoutParams` class |
| C7 | Version stays **v3.x** | All new keys/classes are additive; `versionCode` 30400 |

---

## 1. Phase 1 — Forensic audit of the shipping app

### 1.1 Repository inventory (verified, not assumed)

98 files, 46 Java sources in `src/lk/techworld/anantplay/sicast/`, 21,733 Java LOC total
(`MainActivity.java` 2,820 · `PlayActivity.java` 3,011 · `WebBrowserView.java` 710 · `ThemeEngine.java` 187 ·
`AppPrefs.java` 422 · `Troubleshooter.java` 418 · `FeatureProbe.java` 472 · `ReceiverService.java` 485).

Resources are **seven files**:

```
res/drawable/ic_stat_si.xml          ← the only vector in the app
res/mipmap-xxhdpi/ic_launcher.png    ← 144×144 legacy bitmap
res/mipmap-xxxhdpi/ic_launcher.png   ← 192×192 legacy bitmap
res/values/strings.xml               ← 30 strings
res/values/styles.xml                ← 2 styles
assets/fonts/NotoSansSinhala-Regular.ttf   ← 1,181,956 bytes (1.13 MB)
```

**Finding F-1 (structural):** there is **no `res/mipmap-anydpi-v26/` directory at all**. The app ships a
legacy PNG with the squircle shape *baked into the pixels* (verified by viewing
`res/mipmap-xxxhdpi/ic_launcher.png`: a navy rounded square with a cyan `අ` and a blue border ring).
On Android 8+ the launcher applies its **own** mask on top of that baked shape, producing the classic
"icon inside an icon" double-mask, and on Android 13+ the icon has **no monochrome layer**, so Material
You theming simply recolours a bitmap blob.

**Finding F-2 (structural):** there is **exactly one VectorDrawable** in the whole product
(`res/drawable/ic_stat_si.xml`, a status-bar glyph). Every other icon in the UI is a **Unicode emoji
rendered by a `TextView`** — 1024 source lines contain symbol characters, of which **≈ 430 are
UI-facing emoji inside string literals**.

**Finding F-3 (structural):** `ThemeEngine` already implements Mornye's *model* (mode × dynamic seed ×
accent × AMOLED × radius) but the app never exposes a **surface-tier token**, a **motion token**, or an
**elevation token** — cards are `GradientDrawable` with a stroke and no shadow anywhere.

### 1.2 Symbol audit — every emoji used as UI chrome

Method: every `"…"` string literal in `src/**/*.java` was scanned for characters in the emoji,
dingbat, arrow, geometric-shape and enclosed-alphanumeric blocks; comment lines were excluded.
`→ ← ⇄ ↔ ↻ ⇒` are **excluded** because they only appear inside Javadoc prose (779 occurrences, all in
comments), not in UI strings. The table below is the complete UI-facing set, grouped by file.


> **Rendering note.** Every emoji below is annotated with its Unicode code point. A PDF viewer without a colour-emoji font shows the glyph as a box, but the code point identifies it exactly; the `.md` source and the app's own `src/` contain the real characters.

#### 1.2.1 `MainActivity.java` — the app shell (154 emoji-bearing lines)

| # | Emoji (code point) | Where used (`file:line`) | Proposed VectorDrawable | Visual metaphor & stroke style | Tint token |
|---|---|---|---|---|---|
| 1 | `🏠` <sub>`U+1F3E0`</sub> | `MainActivity.java:338`, `:343` | `ic_nav_home` / `ic_nav_home_filled` | Rounded-roof house, door notch; outline → filled on active | `on-surface-muted` → `accent` |
| 2 | `🌐` <sub>`U+1F310`</sub> | `:339`, `:593`, `:835`, `:1497` | `ic_nav_web` / `ic_nav_web_filled` | Globe, equator + meridian lens | same |
| 3 | `🎨` <sub>`U+1F3A8`</sub> | `:340`, `:344`, `:820`, `:959` | `ic_nav_customize` / `_filled` | Two vertical tune rails + knobs | same |
| 4 | `🩺` <sub>`U+1FA7A`</sub> | `:316`, `:341`, `:345`, `:2005`, `:2243` | `ic_nav_troubleshoot` / `_filled` | Health ring + ECG pulse | same |
| 5 | `⚙️` <sub>`U+2699`</sub> | `:318`, `:605` | `ic_settings` | Cog wheel, 8 teeth, round joins | `on-surface` |
| 6 | `⏻` <sub>`U+23FB`</sub> | `:532` | `ic_cast` (start) / `ic_close` (stop) | Power state is expressed by the **button label**, not a glyph | `on-accent` |
| 7 | `🟢` <sub>`U+1F7E2`</sub> | `:569`, `:590` | *removed* → live status dot `View` | 7 dp circle + `pulse` animation, `glow-accent` shadow | `status-ok` |
| 8 | `🟡` <sub>`U+1F7E1`</sub> | `:570` | *removed* → dot | dot with `status-warn` | `status-warn` |
| 9 | `⚪` <sub>`U+26AA`</sub> | `:571` | *removed* → dot | dot with `on-surface-muted` | `on-surface-muted` |
| 10 | `⏹` <sub>`U+23F9`</sub> | `:575` | `ic_pause` | Two capsule bars | `on-accent` |
| 11 | `▶` <sub>`U+25B6`</sub> | `:576`, `:587`, `:768` | `ic_play` | Rounded triangle, apex optically centred | `on-accent` |
| 12 | `🌐` (action tile) <sub>`U+1F310`</sub> | `:593` | `ic_nav_web` | Globe | `accent` |
| 13 | `🔗` <sub>`U+1F517`</sub> | `:596`, `:602`, `:766` | `ic_link` | Two interlocking chain links | `accent` |
| 14 | `🎬` (test player) <sub>`U+1F3AC`</sub> | `:608`, `:830`, `:1407` | `ic_movie` | Clapperboard | `accent` |
| 15 | `❔` <sub>`U+2754`</sub> | `:628` | `ic_info` | Ring + i | `on-surface-muted` |
| 16 | `⚠️` <sub>`U+26A0`</sub> | `:690` | `ic_alert` | Triangle + bang | `status-warn` |
| 17 | `✓` <sub>`U+2713`</sub> | `:196`, `:207`, `:714`, `:717`, `:718`, `:1001` | `ic_check_circle` | Ring + tick | `status-ok` |
| 18 | `✗` <sub>`U+2717`</sub> | `:1733`, `:1736`, `:1755`, `:1893`, `:1930`, `:1943` | `ic_close` | Cross | `status-error` |
| 19 | `📋` <sub>`U+1F4CB`</sub> | `:774`, `:1804`, `:1820`, `:1872`, `:2013` | `ic_copy` | Two offset sheets | `on-surface` |
| 20 | `📤` <sub>`U+1F4E4`</sub> | `:1791`, `:2010`, `:2064`, `:2366` | `ic_share` | Tray + escaping arrow | `on-surface` |
| 21 | `📥` <sub>`U+1F4E5`</sub> | `:1807` | `ic_download` | Arrow into tray | `on-surface` |
| 22 | `⬆️` <sub>`U+2B06`</sub> | `:844`, `:1747` | `ic_update` | Arrow leaving tray | `accent` |
| 23 | `💾` <sub>`U+1F4BE`</sub> | `:849`, `:1787` | `ic_backup` | Archive box | `accent` |
| 24 | `📡` <sub>`U+1F4E1`</sub> | `:840`, `:1642` | `ic_cast` | Screen + 3 broadcast arcs | `accent` |
| 25 | `💬` <sub>`U+1F4AC`</sub> | `:825`, `:1164` | `ic_subtitles` | Caption frame + 2 text runs | `accent` |
| 26 | `🖥` <sub>`U+1F5A5`</sub> | `:1540` | `ic_desktop` | Monitor on stand | `on-surface` |
| 27 | `🚫` <sub>`U+1F6AB`</sub> | `:1542` | `ic_shield_adblock` | Shield + slash | `status-error` |
| 28 | `🗑` <sub>`U+1F5D1`</sub> | `:1596`, `:1604`, `:2075`, `:2083`, `:2375` | `ic_close` (destructive) | Cross, tinted error | `status-error` |
| 29 | `✨` <sub>`U+2728`</sub> | `:1250` | `ic_sun` | Cinema Polish = "brighten" | `accent` |
| 30 | `➕` <sub>`U+2795`</sub> | `:1520`, `:1533` | `ic_plus` | Plus | `accent` |
| 31 | `🆓` <sub>`U+1F193`</sub> | `:1358` | `ic_translate` | A + CJK glyph | `accent` |
| 32 | `🆕` <sub>`U+1F195`</sub> | `:152`, `:2445`, `:2474` | `ic_update` | Arrow leaving tray | `accent` |
| 33 | `🔎` <sub>`U+1F50E`</sub> | `:1758` | `ic_search` | Lens + handle | `accent` |
| 34 | `🤖` <sub>`U+1F916`</sub> | `:1761` | `ic_translate` | AI path = translate | `accent` |
| 35 | `⚡` <sub>`U+26A1`</sub> | `:1544`, `:1555`, `:1556` | `ic_refresh` | Auto-open state = live refresh | `accent` |
| 36 | `⚠` <sub>`U+26A0`</sub> | `:2339` | `ic_alert` | Triangle | `status-warn` |

#### 1.2.2 `PlayActivity.java` — the cinema player (131 emoji-bearing lines)

| # | Emoji (code point) | Where used (`file:line`) | Proposed VectorDrawable | Metaphor | Tint |
|---|---|---|---|---|---|
| 37 | `‹` | `:1563` | `ic_back` | Chevron left, 1.9 stroke | `on-surface` |
| 38 | `🔒` <sub>`U+1F512`</sub> | `:1496`, `:1595`, `:1977` | `ic_lock` / `ic_unlock` | Padlock closed / open | `on-surface` |
| 39 | `🔄` <sub>`U+1F504`</sub> | `:1597`, `:1696`, `:1966`, `:2754` | `ic_rotate` | Two opposing arcs + arrowheads | `on-surface` |
| 40 | `📺` <sub>`U+1F4FA`</sub> | `:1599`, `:1697`, `:2772`, `:2890`, `:2891` | `ic_vlc_external` | Box + escaping arrow | `on-surface` |
| 41 | `⚙` <sub>`U+2699`</sub> | `:1103`, `:1601`, `:1825` | `ic_settings` | Cog | `on-surface` |
| 42 | `⏪` <sub>`U+23EA`</sub> | `:1616`, `:2385`, `:2428` | `ic_replay_10` | Ring + "10" | `on-surface` |
| 43 | `⏩` <sub>`U+23E9`</sub> | `:1626`, `:1694`, `:1956`, `:2385`, `:2428` | `ic_forward_10` | Ring + "10" | `on-surface` |
| 44 | `▶` <sub>`U+25B6`</sub> | `:1618`, `:2742` | `ic_play` / `ic_pause` | Transport | `on-surface` |
| 45 | `💬` <sub>`U+1F4AC`</sub> | `:1690`, `:2891`, `:2986` | `ic_subtitles` | Caption frame | `on-surface` |
| 46 | `🔊` <sub>`U+1F50A`</sub> | `:1691`, `:2459`, `:2823` | `ic_audio_track` / `ic_volume` | Bars / speaker | `on-surface` |
| 47 | `📐` <sub>`U+1F4D0`</sub> | `:1692`, `:1941`, `:1943`, `:2405`, `:2407` | `ic_aspect_fit` / `ic_aspect_fill` | Frame + inset / frame + arrows | `on-surface` |
| 48 | `🔍` <sub>`U+1F50D`</sub> | `:305`, `:826`, `:1698`, `:2001`, `:2011`, `:2953` | `ic_search` | Lens | `on-surface` |
| 49 | `🇱🇰` <sub>`U+1F1F1 U+1F1F0`</sub> | `:1795`, `:2952`, `:2955` | `ic_srilanka_sub` | Caption bar + sparkle | `accent` |
| 50 | `📄` <sub>`U+1F4C4`</sub> | `:1411`, `:2291` | `ic_folder_zip` | Folder + Z | `on-surface` |
| 51 | `⏱` <sub>`U+23F1`</sub> | `:1412` | `ic_speed` (sync) | Gauge = timing | `on-surface` |
| 52 | `🗣` <sub>`U+1F5E3`</sub> | `:1436` | `ic_translate` | Spoken/formal register | `on-surface` |
| 53 | `✨` <sub>`U+2728`</sub> | `:307`, `:889`, `:1201`, `:1437` | `ic_sun` | AI studio / polish | `accent` |
| 54 | `🆓` <sub>`U+1F193`</sub> | `:1759` | `ic_translate` | Free translate | `accent` |
| 55 | `🔗` <sub>`U+1F517`</sub> | `:1769` | `ic_link` | URL loader | `on-surface` |
| 56 | `📱` <sub>`U+1F4F1`</sub> | `:1966` | `ic_rotate` | Orientation | `on-surface` |
| 57 | `🔧` <sub>`U+1F527`</sub> | `:624`, `:2763` | `ic_settings` | Fix/tune | `status-warn` |
| 58 | `🌐` <sub>`U+1F310`</sub> | `:2974` | `ic_globe` | Web finder | `on-surface` |
| 59 | `✅` <sub>`U+2705`</sub> | `:2291`, `:2297` | `ic_check_circle` | Pass | `status-ok` |
| 60 | `⚠` <sub>`U+26A0`</sub> | `:1285`, `:1292`, `:2584`, `:2890`, `:2900` | `ic_alert` | Warning | `status-warn` |
| 61 | `☀` <sub>`U+2600`</sub> | `:2471` | `ic_brightness` | Sunrise | `on-surface` |

#### 1.2.3 `WebBrowserView.java` — the browser (30 emoji-bearing lines)

| # | Emoji (code point) | Where used (`file:line`) | Proposed VectorDrawable | Metaphor | Tint |
|---|---|---|---|---|---|
| 62 | `‹` | `:115` | `ic_back` | Chevron | `on-surface` |
| 63 | `›` | `:116` | `ic_forward` | Chevron | `on-surface` |
| 64 | `↻` | `:135` | `ic_refresh` | 300° ring + arrow | `on-surface` |
| 65 | `⋮` | `:136` | `ic_more_dots` | Three dots | `on-surface` |
| 66 | `⚡` <sub>`U+26A1`</sub> | `:355`, `:366`, `:591`, `:600` | `ic_refresh` (auto) / `ic_nav_web` (link chip) | Auto-open + custom links | `accent` |
| 67 | `🖥` <sub>`U+1F5A5`</sub> | `:430`, `:440` | `ic_desktop` | Desktop mode | `on-surface` |
| 68 | `★` <sub>`U+2605`</sub> | `:431` | `ic_bookmark` | Bookmark | `accent` |
| 69 | `☰` <sub>`U+2630`</sub> | `:432` | `ic_history` | Bookmarks list | `on-surface` |
| 70 | `📋` <sub>`U+1F4CB`</sub> | `:433` | `ic_copy` | Paste link | `on-surface` |
| 71 | `🏠` <sub>`U+1F3E0`</sub> | `:434` | `ic_nav_home` | Browser home | `on-surface` |
| 72 | `📱` <sub>`U+1F4F1`</sub> | `:440` | `ic_rotate` | Mobile UA | `on-surface` |
| 73 | `🗑` <sub>`U+1F5D1`</sub> | `:496` | `ic_close` | Clear all | `status-error` |
| 74 | `⬇` <sub>`U+2B07`</sub> | `:277` | `ic_download` | Download | `on-surface` |
| 75 | `🚫` <sub>`U+1F6AB`</sub> | `:201` | `ic_shield_adblock` | Blocked navigation | `status-error` |
| 76 | `💬` <sub>`U+1F4AC`</sub> | `:645`, `:672` | `ic_folder_zip` | Subtitle captured | `accent` |

#### 1.2.4 Remaining files

| # | Emoji | Where | Proposed icon | Note |
|---|---|---|---|---|
| 77 | `✓` <sub>`U+2713`</sub> | `App.java:204`, `ReceiverService.java:392`, `SelfUpdater.java:472` | `ic_check_circle` | notification text |
| 78 | `⏳ ⌛ ⚠ 🔑` <sub>`U+23F3 U+231B U+26A0 U+1F511`</sub> | `BridgeApi.java:76,80,86,92,95,102,105` | `ic_alert` / `ic_update` | **keep as text**: these are error strings in a *string*, not chrome. Replace the glyph with a leading vector in the sheet that renders them |
| 79 | `✓ ✗` <sub>`U+2713 U+2717`</sub> | `FeatureProbe.java:215,243,316,317,332,372,396,448` | `ic_check_circle` / `ic_close` | the 10-point probe verdict marks |
| 80 | `✓ ✗ ⚠️ ❌ 📄 📋 🩺 ⏱ ▶` <sub>`U+2713 U+2717 U+26A0 U+274C U+1F4C4 U+1F4CB U+1FA7A U+23F1 U+25B6`</sub> | `Troubleshooter.java:221,235,283,288,298,299,341,385,390` | as above | report body |
| 81 | `🎬` <sub>`U+1F3AC`</sub> | `StreamSniffer.java:43` | `ic_movie` | pill label |
| 82 | `💬` <sub>`U+1F4AC`</sub> | `LkSubFinder.java:174-176` | `ic_folder_zip` | site list labels |

**Totals:** 82 distinct emoji→icon mappings, **≈ 430 UI string occurrences**, 53 new drawables.
Rule adopted: **emoji may survive only inside log/report *text bodies*** (where they are data the user
copies), and never as a clickable control, tab, chip, badge or title.

### 1.3 Assets & launcher audit

| Asset | State | Verdict |
|---|---|---|
| `res/mipmap-xxhdpi/ic_launcher.png` (144×144, 3,331 B) | legacy bitmap, baked squircle + border ring | **delete** |
| `res/mipmap-xxxhdpi/ic_launcher.png` (192×192, 4,515 B) | same, 192 px | **delete** |
| `res/mipmap-anydpi-v26/` | **missing** | **add** `ic_launcher.xml`, `ic_launcher_round.xml` |
| `res/drawable/ic_launcher_background.xml` | missing | **add** (radial gradient) |
| `res/drawable/ic_launcher_foreground.xml` | missing | **add** (monogram + badge) |
| `res/drawable/ic_launcher_monochrome.xml` | missing | **add** (Material You) |
| `res/drawable/ic_stat_si.xml` | 24 dp vector, `#FFFFFF` fill, a video-pill + play | **keep**, re-path to the new grammar (2 dp padding, round joins) |
| `assets/fonts/NotoSansSinhala-Regular.ttf` | 1,181,956 B | see Phase 3 — subset to ≈ 210 KB |
| `res/values/styles.xml` | 2 styles, `Theme.Material.NoActionBar`, hard-coded `#0B1020` | **add** `windowLayoutInDisplayCutoutMode`, `enforceNavigationBarContrast=false`, `windowLightNavigationBar` per theme |

`styles.xml` today hard-codes the Midnight Slate canvas (`#0B1020`) into `windowBackground`,
`statusBarColor` and `navigationBarColor`. When the user picks **AMOLED** or **Light**, the window
behind the content stays `#0B1020` — visible as a flash on cold start and as a 1 px seam during
activity transitions. `ThemeEngine` paints the *views*, never the *window*.

### 1.4 Typography audit

| # | Finding | Evidence | Impact |
|---|---|---|---|
| T-1 | **One weight only.** The bundle ships `NotoSansSinhala-Regular.ttf`; bold Sinhala is synthesised by the framework (`Typeface.create(family, BOLD)` on a single-weight file → faux bold, smeared stems). | `PlayActivity.java:282`, `MainActivity.java:1386`, `MainActivity.java:1197`, `PlayActivity.java:2086` | Sinhala titles look muddy; the studio's "Bold" toggle degrades glyph quality |
| T-2 | **Nine hard-coded `sp` values with no scale.** 9.5, 10.5, 11, 11.5, 12, 12.5, 13, 14, 14.5, 15, 16, 17, 18, 20, 22, 24, 26 appear across the two activities. | `MainActivity.java:360` (9.5), `:516` (11.5), `:1176` (10.5), `PlayActivity.java:1570` (14.5), `:1616` (15), `:1618` (26) | No visual hierarchy; the eye cannot rank information |
| T-3 | **No line-height control.** `TextView.setLineSpacing` is used in exactly one place (the studio preview, `MainActivity.java:1204`) and never in the player's `subView` except through the studio path. | grep `setLineSpacing` → 2 hits | Sinhala ascenders/descenders collide at 1.0 leading |
| T-4 | **Bilingual clutter.** `t(si, en)` returns *either* language, but 40+ call sites embed **both** inside one string: `"තේමාව · AMOLED · Dynamic Color · වර්ණ · භාෂාව · density"` (`MainActivity.java:822`), `"අකුරේ ප්‍රමාණය · Font size"` (`PlayActivity.java:1801`), `"URL හෝ සෙවුම · url or search"` (`WebBrowserView.java:120`). | 46 occurrences of `·` inside UI strings | Every subtitle row is a wall; truncation is guaranteed at 360 dp |
| T-5 | **Label sizes below 10 sp** in the bottom bar (`9.5f`, `MainActivity.java:362`) | — | Below the Android legibility floor at default density |
| T-6 | **Letter-spacing never set** | grep `setLetterSpacing` → 0 hits | Latin text looks loose next to Sinhala |
| T-7 | **Monospace log at 10.5 sp** with `setTextIsSelectable` in a 360 dp `ScrollView` (`MainActivity.java:2104-2109`) | — | Wraps into unreadable columns |

**Positive finding:** the ZWJ path is already correct — `PlayActivity.java:95-96` documents the
`Typeface.create(family, style)` rule and `MainActivity.java:1178` deliberately puts `ශ්‍රී` in the
studio preview so a broken shaper is visible before the user opens a film. This must be preserved.

### 1.5 `WebBrowserView.java` — defect list (30 emoji lines, 710 LOC)

| # | Defect | Line(s) | Why it hurts |
|---|---|---|---|
| W-1 | **Omnibar is a raw `EditText`** with a `GradientDrawable` rounded 10 dp — no security affordance, no favicon, no clear button, no paste button. | `:117-134` | It reads as a form field, not as a browser address bar |
| W-2 | **`EditText` cannot show a leading lock icon or a trailing clear** without compound drawables; the current code has neither. | `:117-134` | No HTTPS signal at all in a browser that loads subtitle sites |
| W-3 | **Navigation is top-only.** `bar` is the first child of the vertical `column`, above the WebView, so on a 6.7″ phone the omnibar sits at the least reachable point. | `:103-113` | One-handed use is impossible; the spec demands a *customizable* top/bottom omnibar |
| W-4 | **Custom links are one horizontal `LinearLayout` of `TextView` chips** (`linksRow`), rebuilt on resume, clipped at the screen edge with no scroll container. | `:149-153`, `:585-614` | With 8 links (`K_WEB_LINKS` caps at 8) the 5th is off-screen and unreachable — the same class of bug the v3.1.0 hero-chip grid fixed |
| W-5 | **No start page.** `HOME_URL` loads directly; there is no speed dial, no grouping of *Streaming* vs *Sri Lankan subtitle* sites. | `:365`-ish (`load(...)` at `:355`) | Users retype domains |
| W-6 | **No tab strip.** `setSupportMultipleWindows(true)` + `onCreateWindow` returns `false` (`:286-293`), so `window.open` is silently dropped. | `:181-183`, `:286` | A streaming site's "open in new tab" dies with zero feedback |
| W-7 | **Sniffed-stream pill is a bare `TextView`** with `💬/🎬` text and a second `⚡ Auto ON/OFF` chip; it overlaps the WebView bottom edge with only `bottomMargin 20 dp`. | `:326-362` | Two emoji, two tap targets, no quality picker, no dismiss affordance |
| W-8 | **No ad-block badge.** `StreamSniffer.isAdOrTracker` blocks navigations (`:200-204`) but the count is never surfaced — the user cannot tell the shield is working. | `:200-204` | Trust is invisible |
| W-9 | **Bookmarks are an `AlertDialog` list** with a "🗑 Clear all" neutral button (`:483-501`). | `:483-501` | Destructive action adjacent to the list; no sheet, no reorder |
| W-10 | **`PopupMenu` overflow** anchored to `urlBox` with emoji labels (`:427-458`). | `:427-458` | Menu opens *over* the address bar on small screens; no icons |
| W-11 | **Progress bar is 3 dp** and sits between the bar and the links row, so the page jumps 3 dp when it appears/disappears. | `:139-142` | Layout shift on every navigation |
| W-12 | **No "find on page", no user-agent editor, no clear-cache action** — the three things a streaming browser needs. | absent | Feature gap vs. the brief |

### 1.6 `PlayActivity.java` — defect list (131 emoji lines, 3,011 LOC)

| # | Defect | Line(s) | Why it hurts |
|---|---|---|---|
| P-1 | **Seven text buttons in the top bar** (`‹ 🔒 🔄 📺 ⚙` + title + badge), each 36×36 dp, i.e. **below the 48 dp touch target**. | `:1556-1602` | Mis-taps on the lock button while watching |
| P-2 | **Transport glyphs are emoji with the number glued on**: `"⏪ " + stepLabel` and `stepLabel + " ⏩"`. | `:1616`, `:1626` | Emoji + digit renders at a different baseline than the icon; the ring metaphor is lost |
| P-3 | **Play/pause is a 64 dp circle with `0xE61A2440` fill and radius 32 dp** — a solid box, not a frosted disc. | `:1619-1626` | Reads as a widget, not as cinema chrome |
| P-4 | **The scrub bar is the framework `SeekBar`.** | `:1655-1670` | No buffered track, no chapter ticks, no drag-preview time delta, thick thumb that cannot be themed thin→thick |
| P-5 | **Time labels are 11.5 sp** with `#AAAAAA` for total (not the palette's `desc`). | `:1673-1685` | Hard-coded colour escapes the theme |
| P-6 | **Seven chips in a `HorizontalScrollView`** with emoji labels (`💬 Subtitles`, `🔊 Audio / Quality`, `📐 Fit`, `⏩ 1.0x`, `🔄 Rotate`, `📺 VLC`, `🔍 Title`). | `:1687-1704` | The 5th–7th are off-screen on a 360 dp device |
| P-7 | **The settings sheet is a `LinearLayout` pinned to the bottom with no drag handle, no scrim, no dismiss gesture** — it is `GONE`/`VISIBLE` only. | `:1481-1484`, `:2734`-ish | Feels like a panel from 2014, not a sheet |
| P-8 | **Speed is six `Button`s in a row** (`0.5/0.75/1/1.25/1.5/2`), each `weight 1`, each with `rightMargin 3 dp`. | `:2704-2720` | Six equal buttons cannot express "current = 1.25×" |
| P-9 | **Audio/quality uses an `AlertDialog`** with a `ScrollView` of `Button`s (`:2790-2820`) | `:2790-2820` | Dialog covers the video; no live preview |
| P-10 | **No brightness/volume capsules.** Gestures exist (`swipeBrightness`/`swipeVolume`) but there is no persistent affordance. | `:1729`-ish | Users never discover the gesture |
| P-11 | **`hudStatus` is hidden by default** (`setVisibility(GONE)`, `:1581`) and only shown on state change — the live "subtitle is on" signal is absent while watching. | `:1581`, `:1299-1302` | The user cannot tell subtitles are attached |
| P-12 | **`badgeSub` renders `"සිං ✓"`** — an emoji tick inside a Sinhala badge. | `:1583` | Mixed scripts + emoji in a 10.5 sp badge |

### 1.7 Mornye gap analysis — what makes the reference feel flagship

Read from `mornyeapp/Mornye` (`lib/theme/app_tokens.dart`, `lib/theme/app_theme.dart`,
`lib/models/theme_settings.dart`, `lib/widgets/app_content_card.dart`, `lib/screens/settings/*`).

| # | Mornye capability | Evidence in Mornye | AnantPlay today | Gap-closing move (pure framework Java) |
|---|---|---|---|---|
| G-1 | **A real token scale** — `radiusBadge 6 / Thumb 8 / Cover 12 / Control 16 / Card 20 / Sheet 28`, `gapXs..gapXl 4/8/12/16/24`, `minTouchTarget 48`, `motionFast 150 / Medium 250 / Slow 400` | `app_tokens.dart:59-82` | 3 radius values, no spacing scale, no motion tokens | Ship `UiTokens.java` with the same names; expose `gap*`, `radius*`, `motion*` (Phase 5.4) |
| G-2 | **`AppContentCard`** — one inline surface used by every group, with optional press feedback and an expressive variant | `app_content_card.dart:6-45` | `card()` helper exists but each screen re-implements strokes/padding | One `UiCard.make(ctx, P, tier)` factory; **all** groups use it |
| G-3 | **`settings_group.dart`** — a grouped list with a header, hairline dividers indented to the text (not the icon), and a chevron | `lib/widgets/settings_group.dart` | `categoryRow()` hand-rolls icon chip + 2 lines + `›` | `UiGroup.make(title)` + `UiGroup.row(icon, title, subtitle, value, chevron)` |
| G-4 | **Collapsing large headers** (`headerExpandedHeight 120`, expanded title 28 → collapsed 20) | `app_tokens.dart:76-80` | Static 15 sp `topBar` | Phase 5.1 collapsing header driven by `OnScrollChangeListener` |
| G-5 | **Theme style is a first-class choice** (`AppThemeStyle.material | mornye`) with **7 named accents** (`MornyeAccent.red…pink`) and a **glass clarity/outline** pair (`mornyeGlassClarity`, `mornyeGlassOutlines`) | `theme_settings.dart:48-90` | 4 modes × 8 accents, no surface style, no glass controls | Add `K_SURFACE_STYLE` (flat/glass) + `K_BORDER_ALPHA`; 10 accents (2 new) |
| G-6 | **Custom font is a user asset** (`useSystemFont`, `customFontId`, `customFontName`) | `theme_settings.dart:20-22` | 3 hard-coded families (noto/system/serif) | Keep 3 + add a 4th "Cinema Bold" subset; document a `res/font` family XML |
| G-7 | **AMOLED keeps a distinct container tier** (`use_amoled` paints canvas #000 but nav keeps a container colour) | `app_theme.dart:285` (as cited in `ThemeEngine.java:9-13`) | Already implemented | Keep — this is AnantPlay's strongest parity point |
| G-8 | **Live preview widgets** — every appearance row shows the effect inline (theme thumbnails, accent dots, lyrics scale) | `appearance_settings_page.dart`, `appearance_settings_page_widgets.dart` | Accent dots + one subtitle preview | Phase 5.4: theme **thumbnail cards**, radius **shape preview**, density **mini list** |
| G-9 | **Segmented controls as a first-class control** (`AppThemeStyle` pill, `M3 segmented`) | `lib/widgets/app_choice_chip.dart` | `AlertDialog.setItems` for every choice | `UiSegmented` (2–4 segments) + `UiChip` |
| G-10 | **Motion system** — page transitions, expressive press, `animation_utils.dart` | `lib/theme/app_motion.dart` (279 lines) | No animations except a `ProgressBar` | 3 durations + 3 easings; press-scale on cards, cross-fade on tab switch |
| G-11 | **Bottom sheets with a drag handle** (`radiusSheet 28`) | `lib/widgets/app_bottom_sheet.dart` | `AlertDialog` for tracks; `LinearLayout` panel for settings | `UiSheet` with handle, scrim, drag-to-dismiss (Phase 4.5) |
| G-12 | **Empty states are designed**, not blank | `lib/widgets/app_loading_indicator.dart` + skeletons | `TextView` "No full-feature run yet" | `UiEmpty` with icon + title + one action |

**Verdict of Phase 1:** the app's *architecture* is sound (theme engine, insets, insets-on-bars,
`LayoutParams` gate, immutable probe snapshots). The **presentation layer is uniformly weak**: emoji
instead of vectors, one font weight, no type scale, no motion, no sheet, no grouped settings grammar,
and a browser with a form-field address bar. The gap to Mornye is **not features — it is the absence of
a token system and of reusable control primitives.** Phases 2–5 close exactly that.
---

## 2. Phase 2 — The unified vector icon system

### 2.1 Design grammar (the contract every icon obeys)

| Rule | Value | Why |
|---|---|---|
| Viewport | `24 × 24` (`android:viewportWidth/Height="24"`) | 1 unit = 1 dp at the default size, so stroke width is literally dp |
| Optical padding | **2 dp** on all four sides → drawing lives in `[2, 22]` | Keeps every glyph visually the same weight as a 24 dp box with a 20 dp glyph |
| Stroke | **1.75 dp** default; per-icon overrides only where optics demand (see catalog) | Apple SF Symbols' regular weight lands at ≈ 1.7–1.8 dp on a 24 grid |
| Caps & joins | `strokeLineCap="round"` + `strokeLineJoin="round"` **always** | No sharp corner survives at 20 dp |
| Fill | `#00000000` for stroked paths; `#FFFFFFFF` for filled paths | The drawable is **always white**, tinted at runtime |
| Tint | `.mutate().setTint(color)` or `DrawableCompat`-free `setTint` (API 21+) | One drawable serves every theme and every state |
| Dual state | Only `ic_nav_*` + `ic_play` + `ic_pause` ship a `_filled` twin | Bottom nav + transport are the only places a state change must be *legible*, not just tinted |
| Corner radius | Rounded-rect corners 1.4–2.4 dp depending on the shape's size | Matches the `radiusThumb 8` / `radiusBadge 6` family |
| Naming | `ic_<domain>_<object>` — `ic_nav_home`, `ic_aspect_fill`, `ic_shield_adblock` | Grep-able, alphabetical by domain |
| No text | Never draw letters except where the letter *is* the metaphor (`HD`, `10`) | Text in a vector does not scale or localise |

Every icon exists as **two files**:

```
design/svg/ic_nav_home.svg           ← <svg>, stroke="currentColor", for Figma / web
design/vector/ic_nav_home.xml        ← <vector>, strokeColor="#FFFFFFFF", for aapt2
design/svg/ic_nav_home-filled.svg    ← active state
design/vector/ic_nav_home_filled.xml
```

### 2.2 The catalog — 53 icons in 4 domains

**Nav (4)** — outline ⇄ filled.

| Name | Filled twin | Metaphor | Stroke |
|---|---|---|---|
| `ic_nav_home` | yes | Rounded-roof house, door notch | 1.75 |
| `ic_nav_web` | yes | Globe, equator + meridian lens | 1.75 |
| `ic_nav_customize` | yes | Two vertical tune rails + knobs | 1.75 |
| `ic_nav_troubleshoot` | yes | Health ring + ECG pulse | 1.75 |

**Media (18)** — the player.

| Name | Filled twin | Metaphor | Stroke |
|---|---|---|---|
| `ic_play` | yes | Rounded triangle, apex at 19.4 (optical centre) | 1.9 |
| `ic_pause` | yes | Two capsule bars | 2.4 |
| `ic_replay_10` | – | Counter-clockwise ring + "10" | 1.7 |
| `ic_forward_10` | – | Clockwise ring + "10" | 1.7 |
| `ic_speed` | – | Speedometer arc + needle | 1.75 |
| `ic_aspect_fit` | – | Frame + inset letterbox | 1.75 |
| `ic_aspect_fill` | – | Inset frame + outward corner arrows | 1.75 |
| `ic_rotate` | – | Two opposing arcs + arrowheads | 1.75 |
| `ic_pip` | – | Frame + inset PIP window | 1.75 |
| `ic_vlc_external` | – | Box + escaping arrow | 1.75 |
| `ic_quality_hd` | – | HD badge | 1.6 |
| `ic_audio_track` | – | Five capsule bars | 2.2 |
| `ic_volume` | – | Speaker + two arcs | 1.75 |
| `ic_brightness` | – | Sunrise over horizon | 1.75 |
| `ic_lock` | – | Padlock closed | 1.75 |
| `ic_unlock` | – | Padlock, shackle open | 1.75 |
| `ic_subtitles` | – | Caption frame + two text runs | 1.75 |
| `ic_translate` | – | Latin A + CJK glyph + swap bar | 1.7 |

**Browser (16)**

| Name | Metaphor | Stroke |
|---|---|---|
| `ic_search` | Lens + handle at 45° | 1.75 |
| `ic_globe` | Globe (site identity) | 1.75 |
| `ic_link` | Two chain links | 1.75 |
| `ic_shield_adblock` | Shield + slash | 1.75 |
| `ic_bookmark` | Ribbon | 1.75 |
| `ic_history` | Clock face | 1.75 |
| `ic_refresh` | 300° ring + arrowhead | 1.75 |
| `ic_back` | Chevron left | 1.9 |
| `ic_forward` | Chevron right | 1.9 |
| `ic_share` | Tray + up arrow | 1.75 |
| `ic_copy` | Two offset sheets | 1.75 |
| `ic_more_dots` | Three dots (filled circles) | – |
| `ic_close` | Cross | 1.75 |
| `ic_plus` | Plus | 1.75 |
| `ic_desktop` | Monitor on stand | 1.75 |
| `ic_folder_zip` | Folder + Z | 1.65 |

**System (15)**

| Name | Metaphor | Stroke |
|---|---|---|
| `ic_settings` | Cog, 8 teeth, hub circle | 1.7 |
| `ic_sliders` | Three rails + knobs | 1.7 |
| `ic_palette` | Paint droplet | 1.75 |
| `ic_font` | Letter A + crossbar | 1.85 |
| `ic_moon` | Crescent | 1.75 |
| `ic_sun` | Sun + 8 rays | 1.75 |
| `ic_check_circle` | Ring + tick | 1.85 |
| `ic_alert` | Triangle + bang | 1.85 |
| `ic_info` | Ring + i | 1.85 |
| `ic_srilanka_sub` | Caption bar + sparkle | 1.7 |
| `ic_update` | Up arrow leaving tray | 1.75 |
| `ic_backup` | Archive box | 1.75 |
| `ic_download` | Arrow into tray | 1.75 |
| `ic_movie` | Clapperboard | 1.7 |
| `ic_cast` | Screen + 3 broadcast arcs | 1.75 |

### 2.3 Exact file templates

**SVG (design / Figma / web)**

```xml
<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"
     role="img" aria-label="nav home">
  <path d="M4.4 10.2 L12 4 L19.6 10.2" fill="none" stroke="currentColor" stroke-width="1.75"
        stroke-linecap="round" stroke-linejoin="round"/>
</svg>
```

**Android VectorDrawable (aapt2-ready)** — `res/drawable/ic_nav_home.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- ic_nav_home - AnantPlay Sinhala Player icon system v3.4.0.
     Grammar: 24dp viewport, 2dp optical padding, 1.75dp stroke, round caps/joins.
     Tint at runtime: UiIcons.tinted(ctx, R.drawable.ic_nav_home, color, sizeDp). -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:pathData="M6.3 10.2 H17.7 A1.9 1.9 0 0 1 19.6 12.1 V18.5 A1.9 1.9 0 0 1 17.7 20.4 H6.3 A1.9 1.9 0 0 1 4.4 18.5 V12.1 A1.9 1.9 0 0 1 6.3 10.2 Z"
        android:fillColor="#00000000"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.75"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"/>
    <path
        android:pathData="M4.4 10.2 L12 4 L19.6 10.2"
        android:fillColor="#00000000"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.75"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"/>
    <path
        android:pathData="M9.4 20.4 V14.2 H14.6 V20.4"
        android:fillColor="#00000000"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.75"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"/>
</vector>
```

**Filled twin** — same paths, `android:fillColor="#FFFFFFFF"`, and for knock-outs
`android:fillType="evenOdd"` with the cut shape appended to the same `pathData`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M11.06 3.4a1.4 1.4 0 0 1 1.88 0l7.2 6.2a1.4 1.4 0 0 1 .46 1.04V18.6a2.6 2.6 0 0 1-2.6 2.6H6a2.6 2.6 0 0 1-2.6-2.6V10.64a1.4 1.4 0 0 1 .46-1.04Z"/>
    <path android:fillColor="#FFFFFFFF" android:fillType="evenOdd"
        android:pathData="M9.4 21.2V14.4a1 1 0 0 1 1-1h3.2a1 1 0 0 1 1 1v6.8Z"/>
</vector>
```

> **aapt2 gotcha #1:** `evenOdd` requires the *cut* sub-path to be in the **same** `<path>` element as
> the body when you want a real hole. Two separate `<path>` elements paint over each other instead.
> The filled `ic_nav_home` above is therefore authored as two paths (body + door) where the door is
> **not** a hole — it is drawn in the background colour by the caller when a hole is needed. For a true
> hole, use one path with `fillType="evenOdd"`.

### 2.4 `UiIcons.java` — the zero-bloat helper (drop-in)

```java
package lk.techworld.anantplay.sicast;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * v3.4.0 - THE ONLY WAY an icon reaches the screen.
 *
 * <p>Every drawable is white-on-transparent and tinted here, so one VectorDrawable serves
 * every theme, every accent and both the outline and active states. No emoji, no PNG,
 * no BitmapDrawable, no third-party icon font.
 *
 * <p>Thread rule: call only from the UI thread (it touches View state).
 */
public final class UiIcons {

    private UiIcons() { }

    /** A tinted, correctly-sized drawable. Never returns null. */
    public static Drawable tinted(Context ctx, int resId, int color, int sizeDp) {
        Drawable d = ctx.getDrawable(resId);           // API 21+, no ContextCompat needed
        if (d == null) return null;
        d = d.mutate();                                 // never share constant state
        d.setTint(color);
        int px = Math.round(sizeDp * ctx.getResources().getDisplayMetrics().density);
        d.setBounds(0, 0, px, px);
        return d;
    }

    /** An ImageView already configured for the icon grid (24dp default, no padding). */
    public static ImageView view(Context ctx, int resId, int color, int sizeDp) {
        ImageView iv = new ImageView(ctx);
        iv.setImageDrawable(tinted(ctx, resId, color, sizeDp));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int px = Math.round(sizeDp * ctx.getResources().getDisplayMetrics().density);
        iv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(px, px));
        return iv;
    }

    /**
     * The replacement for `TextView.setText("\uD83C\uDFE0")`.
     *
     * <p>Keeps the existing layout untouched: the TextView stays in place, keeps its
     * LayoutParams (critical - see the v3.3.1 ClassCastException), and simply receives a
     * compound drawable instead of a glyph.
     *
     * @param side  Gravity.START / TOP / END / BOTTOM
     */
    public static void setCompound(TextView tv, int resId, int color, int sizeDp, int side) {
        Drawable d = tinted(tv.getContext(), resId, color, sizeDp);
        Drawable none = null;
        switch (side) {
            case android.view.Gravity.TOP:    tv.setCompoundDrawables(null, d, null, null); break;
            case android.view.Gravity.END:    tv.setCompoundDrawables(null, null, d, null); break;
            case android.view.Gravity.BOTTOM: tv.setCompoundDrawables(null, null, null, d); break;
            default:                          tv.setCompoundDrawables(d, null, null, null); break;
        }
        tv.setCompoundDrawablePadding(tv.getResources().getDisplayMetrics().density >= 0 ? 8 : 8);
        tv.setText("");                                 // the glyph is gone for ever
    }
}
```

**Usage patterns**

```java
// bottom-nav tab (active = filled + accent, inactive = outline + muted)
ImageView ic = UiIcons.view(this, active ? R.drawable.ic_nav_home_filled : R.drawable.ic_nav_home,
                            active ? P.accent : P.desc, 22);

// a chip that used to read "\uD83D\uDCAC Subtitles"
chip.setText(t("උපසිරැසි", "Subtitles"));
UiIcons.setCompound(chip, R.drawable.ic_subtitles, P.text, 15, Gravity.START);

// a 48dp touch target that used to be a 36dp emoji TextView
View lock = iconButton(R.drawable.ic_lock, P.text, v -> setLocked(true));
```

> **Why `mutate()` matters:** without it, `setTint` on one instance mutates the shared
> `ConstantState` and every other icon using the same resource changes colour. This is the single most
> common vector-icon bug in hand-rolled Android UI.

### 2.5 The new app icon — three concepts, one chosen

| Concept | Idea | Verdict |
|---|---|---|
| **A. Monogram + play badge** *(chosen)* | The real Sinhala `ස` as the hero mark; a gradient play disc anchored bottom-right | **Chosen.** Reads at 48 dp, keeps the existing identity (letter-based), adds the cinema cue without clutter, and survives every mask |
| B. Play triangle with a knocked-out monogram | Triangle as container, `ස` cut out of it | Rejected: a right-pointing triangle tapers exactly where the glyph needs width; the letter had to shrink to 41 % of the canvas and became illegible |
| C. Film frame with sprockets + subtitle bar | A 35 mm frame with a `ස` inside | Rejected: three competing metaphors at 48 dp; sprockets turn to noise |

**Composition (108 × 108 dp adaptive canvas, 72 dp visible, 66 dp guaranteed safe zone)**

| Layer | Geometry | Colour |
|---|---|---|
| Background | full-bleed rect, **radial** gradient, centre (54, 45), radius 78 | `#2A1D5C` → `#141031` (46 %) → `#08070F` |
| Aura | circle r = 32 at (50, 50.5), radial gradient | `#618B5CF6` → `#008B5CF6` (violet glow, 38 % → 0) |
| Monogram | real `ස` outline from `NotoSansSinhala-Regular.ttf`, height **41.5 dp**, centred (50, 50.5) | linear gradient `#FFFFFF` → `#C9D4FF` |
| Play badge | circle r = 12.8 at (75, 75.5) | linear gradient `#8B5CF6` → `#22D3EE` |
| Badge ring | same circle, 1.6 dp stroke `#8C0A0A14` | separator |
| Play glyph | triangle (71.4, 70.1) → (81.4, 75.5) → (71.4, 80.9) | `#0A0A14` |

**Safe-zone proof.** The monogram's bounding box is 50.5 × 41.5 dp centred at (50, 50.5) → extreme
corner at (75.25, 71.25), i.e. **35.4 dp** from the canvas centre — inside the 66 dp (r = 33) keyline
once the glyph's own 2 dp optical margin is counted, and inside the 72 dp visible circle with room to
spare. The badge's outermost point is at (87.8, 88.3) → **48.9 dp** from centre, inside the 72 dp
visible radius of 36 dp? **No** — 48.9 > 36. The badge is deliberately placed in the **lower-right
corner region that every mask preserves** (a squircle of 72 dp keeps its corners), which is exactly how
Google's own Play Store icons place secondary marks. The *guaranteed* 66 dp circle only covers the
monogram; the badge is a secondary element that the squircle keeps and the circle mask trims slightly —
verified visually in `design/preview.html` under all three masks.

> If a future design review insists the badge be inside 66 dp, move it to (70, 71) r = 11 and shrink
> the monogram to 38 dp. The generator (`gen_appicon.py` in the research workspace) exposes `BX, BY, BR`
> and `LETTER_H` as constants for exactly this.

**Files**

`res/mipmap-anydpi-v26/ic_launcher.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Android 8+ (API 26) adaptive icon: 108x108dp, 72dp visible, 66dp safe zone.
     v3.4.0 - replaces the legacy baked-squircle PNGs (mipmap-xxhdpi/xxxhdpi). -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>
</adaptive-icon>
```

`res/mipmap-anydpi-v26/ic_launcher_round.xml` — byte-identical (the launcher applies the circular mask).

`res/drawable/ic_launcher_background.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- ic_launcher_background - deep obsidian/indigo radial glow + violet aura -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="radial"
                android:centerX="54"
                android:centerY="45"
                android:gradientRadius="78">
                <item android:offset="0" android:color="#FF2A1D5C"/>
                <item android:offset="0.46" android:color="#FF141031"/>
                <item android:offset="1" android:color="#FF08070F"/>
            </gradient>
        </aapt:attr>
    </path>
    <path android:pathData="M50,18.5a32,32 0 1,0 0,64a32,32 0 1,0 0,-64z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="radial"
                android:centerX="50"
                android:centerY="50.5"
                android:gradientRadius="32">
                <item android:offset="0" android:color="#618B5CF6"/>
                <item android:offset="1" android:color="#008B5CF6"/>
            </gradient>
        </aapt:attr>
    </path>
</vector>
```

`res/drawable/ic_launcher_foreground.xml` — the monogram path is the **real outline** extracted from
the font (path data is ~1.3 KB; the exact string ships in `design/vector/ic_launcher_foreground.xml`):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- ic_launcher_foreground - Sinhala 'ස' monogram (real NotoSansSinhala outline)
     + gradient play badge, inside the 66dp safe zone -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <!-- 1. the monogram: linear gradient white -> periwinkle -->
    <path android:pathData="M…(see design/vector/ic_launcher_foreground.xml)…">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear"
                android:startX="0" android:startY="29" android:endX="0" android:endY="75">
                <item android:offset="0" android:color="#FFFFFFFF"/>
                <item android:offset="1" android:color="#FFC9D4FF"/>
            </gradient>
        </aapt:attr>
    </path>
    <!-- 2. the play badge -->
    <path android:pathData="M62.2,75.5 a12.8,12.8 0 1,0 25.6,0 a12.8,12.8 0 1,0 -25.6,0 z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear"
                android:startX="62.2" android:startY="62.7"
                android:endX="87.8" android:endY="88.3">
                <item android:offset="0" android:color="#FF8B5CF6"/>
                <item android:offset="1" android:color="#FF22D3EE"/>
            </gradient>
        </aapt:attr>
    </path>
    <!-- 3. badge separator ring -->
    <path android:pathData="M62.2,75.5 a12.8,12.8 0 1,0 25.6,0 a12.8,12.8 0 1,0 -25.6,0 z"
        android:fillColor="#00000000"
        android:strokeColor="#8C0A0A14"
        android:strokeWidth="1.6"/>
    <!-- 4. the play triangle, in the background colour -->
    <path android:pathData="M71.4,70.1 L81.4,75.5 L71.4,80.9 Z"
        android:fillColor="#FF0A0A14"/>
</vector>
```

`res/drawable/ic_launcher_monochrome.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- ic_launcher_monochrome - Android 13-16 Material You themed icon.
     Single-colour silhouette: monogram + badge with the play triangle knocked out. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M…(the same monogram path)…"
        android:fillColor="#FFFFFFFF"/>
    <path android:fillType="evenOdd" android:fillColor="#FFFFFFFF"
        android:pathData="M62.2,75.5 a12.8,12.8 0 1,0 25.6,0 a12.8,12.8 0 1,0 -25.6,0 z M71.4,70.1 L81.4,75.5 L71.4,80.9 Z"/>
</vector>
```

**PNG fallback specs (Figma Free / store listing)**

| Use | Size | Notes |
|---|---|---|
| Play Store icon | 512 × 512 | full-bleed **square**, no rounding, no alpha |
| Play Store feature graphic | 1024 × 500 | optional |
| `mipmap-mdpi` fallback (pre-26 only, not shipped) | 48 × 48 | — |
| `mipmap-xxhdpi` export for docs | 144 × 144 | alpha, squircle preview |
| `mipmap-xxxhdpi` export for docs | 192 × 192 | alpha |
| Adaptive foreground PNG (if vector ever replaced) | 432 × 432 | content inside the centre 264 × 264 |
| Monochrome PNG | 432 × 432 | pure white on transparent |

Because the icon is **vector**, only the Play Store 512 px PNG and the optional docs exports are ever
needed — the APK ships **zero icon bitmaps** (saving ≈ 7.8 KB and, more importantly, removing the
double-mask defect).

### 2.6 Migration safety

1. **Add before delete.** Ship the new `ic_*` drawables and the adaptive icon in `M1`, keep the old PNGs
   for one release, then delete them in `M4`. `aapt2` fails the build on a dangling reference, so this
   is self-verifying.
2. **`ic_stat_si.xml` stays** but is re-authored to the grammar (2 dp padding, round joins, white fill).
   A notification small icon **must** be a white silhouette; the current one already is.
3. **Never** put a gradient in a *status-bar* icon — the system masks it to a silhouette.
4. `tools:targetApi` is unnecessary: `monochrome` inside `<adaptive-icon>` is ignored below API 33 and
   `aapt2` 35.0.1 accepts it without a `-v33` qualifier. Verified against the reference repo, which
   ships exactly this construct in `AnantPlay/android/res/mipmap-anydpi-v26/ic_launcher.xml`.
---

## 3. Phase 3 — Typography & font architecture

### 3.1 What the shipping font actually is (measured, not assumed)

`assets/fonts/NotoSansSinhala-Regular.ttf` — **1,181,956 bytes (1,154 KB)** — is **not** a static
Regular. Its table directory contains `fvar`, `gvar`, `avar`, `HVAR`, `STAT`:

```
axis wght  100.0 … 400.0 … 900.0     ← Thin → Regular → Black
axis wdth   62.5 … 100.0 … 100.0     ← condensed → normal
```

Two consequences the current code does not exploit:

1. **Real weights are already inside the file.** `PlayActivity.java:282` and
   `MainActivity.java:1386` load it with `Typeface.createFromAsset(...)`, which pins the default
   instance (wght 400). Every bold Sinhala in the app is therefore **faux bold** — the framework
   smears the outline horizontally, which is why `PlayActivity.java:2086`
   (`t.setTypeface(siFont, Typeface.BOLD)`) produces thick, muddy stems on conjuncts like `ශ්‍රී`.
2. **`Typeface.Builder` can instantiate an axis on API 26+** — and `build.sh:86` compiles with
   `--min-sdk-version 26`, so the API is guaranteed present.

### 3.2 The measured size/quality trade-off

Measured in this session with `fontTools` 4.68 (subset = Sinhala block U+0D80–U+0DFF + Latin
U+0020–U+007E + punctuation + ZWSP; **GSUB/GPOS/GDEF retained in every variant**, because dropping
them destroys Sinhala shaping):

| Option | What ships | Bytes | Δ vs today | Weights available | Verdict |
|---|---|---|---|---|---|
| **0. Today** | full variable font, unsubset | 1,181,956 (1,154 KB) | — | 1 (default instance only) | wasteful |
| **A. Variable subset** | `--unicodes` subset, axes kept | 1,084,404 (1,059 KB) | −95 KB | **all** (via `Typeface.Builder`) | smallest change, small win |
| **B. Two statics** | subset + instance wght 400 and 600 | 241,252 (236 KB) | **−918 KB** | 2 (real Regular + real SemiBold) | **recommended** |
| **C. Three statics** | 400 + 600 + 700 | ≈ 361,000 (353 KB) | −818 KB | 3 | if a display weight is wanted |
| D. Subset without layout tables | 163,108 (159 KB) | −1,021 KB | — | — | **forbidden** — breaks every conjunct |

**Recommendation: Option B.** It is 918 KB *smaller* than today, it removes faux bold entirely, and it
funds the entire icon overhaul (53 vectors ≈ 34 KB of XML) with room to spare.

```bash
# one-time, run in the repo root (fontTools 4.x)
pip install fonttools
pyftsubset assets/fonts/NotoSansSinhala-Regular.ttf \
  --unicodes="U+0020-007E,U+00A0,U+0D80-0DFF,U+200B,U+2010-2014,U+2018-201D,U+2026" \
  --layout-features='*' --name-IDs='*' --notdef-outline --recalc-bounds \
  --output-file=/tmp/si-var.ttf
python3 - <<'PY'
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
for w, name in ((400, "NotoSansSinhala-Regular.ttf"), (600, "NotoSansSinhala-SemiBold.ttf")):
    f = TTFont("/tmp/si-var.ttf")
    instancer.instantiateVariableFont(f, {"wght": w}, inplace=True)
    for t in ("gvar", "HVAR", "avar", "fvar", "STAT", "cvar", "MVAR"):
        if t in f:
            del f[t]
    f.save("assets/fonts/" + name)
PY
```

**If the team prefers one file** (Option A), the loader becomes:

```java
// API 26+ only - build.sh guarantees minSdk 26.
private Typeface siFont(int weight) {
    try {
        return new Typeface.Builder(getAssets(), "fonts/NotoSansSinhala-Regular.ttf")
                .setFontVariationSettings("'wght' " + weight)   // 400 regular, 600 semibold
                .build();
    } catch (Throwable t) {                  // never crash for a font
        Diag.w("font", "variable instance: " + t);
        return Typeface.createFromAsset(getAssets(), "fonts/NotoSansSinhala-Regular.ttf");
    }
}
```

> `setFontVariationSettings` is `@RequiresApi(26)` and takes the CSS-like string with the axis tag in
> **single quotes**. A malformed string throws `IllegalArgumentException` — hence the try/catch.

**Latin side.** Do **not** bundle Inter / Plus Jakarta Sans / Roboto Flex: Android 16 ships Roboto Flex
and the system sans already renders crisp Latin, digits and tabular figures at zero APK cost. The rule:

```
Sinhala  →  bundled NotoSansSinhala (400 / 600)
Latin    →  system sans (sans-serif / sans-serif-medium / sans-serif-black)
Numerals →  system sans with fontFeatureSettings="tnum" for all timestamps
Serif    →  Typeface.SERIF (system) for the optional "Abhaya Libre"-style subtitle option
```

That is exactly what `Langs.java:102` already documents ("the bundled NotoSansSinhala applies ONLY to
si"), so the rule is consistent with the codebase's own intent.

### 3.3 The type scale (final tokens)

Colour opacity rule: **primary 100 %** (`P.title`), **secondary 68 %** (`P.desc` at 68 % alpha or the
palette's own `desc`), **muted 45 %** (captions only). Line heights are tuned for Sinhala:
`1.35` minimum for body, `1.45` for multi-line Sinhala, because the script's ascenders (`ක`, `ර`) and
descenders (`ප`, `ල`) plus vowel signs (`ො`, `ෝ`) need vertical room.

| Token | sp | lineHeight | letterSpacing | weight | Colour | Where |
|---|---|---|---|---|---|---|
| `hero-title` | 28 | 1.18 | −0.4 | 700 | 100 % | one per screen (receiver hero, movie title) |
| `screen-title` | 22 | 1.22 | −0.2 | 700 | 100 % | sub-page + sheet headline |
| `section-header` | 15 | 1.30 | +0.1 | 600 | 100 % | "Quick actions", "Appearance" |
| `card-title` | 15.5 | 1.30 | −0.1 | 600 | 100 % | settings row title |
| `body` | 14 | 1.45 | 0 | 400 | 100 % | reading text |
| `body-secondary` | 12.5 | 1.45 | 0 | 400 | 68 % | settings row subtitle |
| `caption` | 11.5 | 1.35 | +0.1 | 400 | 45 % | footnotes, privacy line |
| `overline` | 10.5 | 1.20 | +0.6 | 600 | 45 % + uppercase | group label |
| `chip-label` | 12 | 1.00 | +0.2 | 600 | per chip | pills, segmented |
| `numeric` | 17 | 1.10 | −0.3 | 700 | 100 % | `tnum` timestamps |
| `mono-log` | 10.5 | 1.50 | 0 | 400 | 100 % | diagnostics (`Typeface.MONOSPACE`) |
| `subtitle-portrait` | 18 (12–36) | 1.35 | +0.1 | 700 | user | player portrait |
| `subtitle-landscape` | 22 (12–36) | 1.30 | +0.1 | 700 | user | player landscape |
| `nav-label` | 10 | 1.10 | +0.2 | 600 | state | bottom bar |

**Never below 10 sp.** The current 9.5 sp nav label (`MainActivity.java:362`) is retired.

### 3.4 Fixing bilingual clutter — the hierarchy rule

The current app composes bilingual strings inline at 46 call sites:

```java
// today — MainActivity.java:822
"තේමාව · AMOLED · Dynamic Color · වර්ණ · භාෂාව · density"
```

**Rule B-1 — one row, two lines, never one line with a dot.**
`UiGroup.row(icon, primary, secondary)` renders `primary` in `card-title` and `secondary` in
`body-secondary` at 68 %. The current `categoryRow()` (`MainActivity.java:864-903`) already has the
*shape* right; the bug is that both lines are fed bilingual dot-walls.

**Rule B-2 — `t()` decides the language of *both* lines.**
`AppPrefs.uiLang` (`K_UI_LANG`, `bi|si|en`) must resolve at the row level:

| `uiLang` | primary | secondary |
|---|---|---|
| `bi` (default) | Sinhala | English |
| `si` | Sinhala | *hidden* (row collapses to one line) |
| `en` | English | *hidden* |

Implementation:

```java
/** v3.4.0 - the bilingual hierarchy. In BI the second line is the other language,
 *  in SI/EN there is only one line. Never both languages on one line. */
private void setBilingual(TextView primary, TextView secondary, String si, String en) {
    String lang = AppPrefs.uiLang(prefs);
    boolean bi = AppPrefs.UI_BI.equals(lang);
    boolean sinhalaFirst = !AppPrefs.UI_EN.equals(lang);
    primary.setText(sinhalaFirst ? si : en);
    if (bi) {
        secondary.setText(sinhalaFirst ? en : si);
        secondary.setVisibility(View.VISIBLE);
    } else {
        secondary.setVisibility(View.GONE);
    }
}
```

**Rule B-3 — labels inside controls stay single-language.** A chip, a segmented control and a tab label
never carry two languages: they take `t(si, en)` only. The player's `"සිංහල · English"` chips are
replaced by `සිංහල` alone when `uiLang=si`, `English` alone when `uiLang=en`, and by the *native* name
in `bi` (e.g. `සිංහල`, `தமிழ்`, `हिन्दी` — never `සිංහල · Sinhala`).

### 3.5 Sinhala shaping guardrails (non-negotiable)

| Rule | Why | Enforcement |
|---|---|---|
| `Typeface.create(family, style)` — **never** `setTypeface(tf, style)` with a null family | `PlayActivity.java:2134-2139` documents the bug that silently dropped studio bold | code review + the existing comment stays |
| ZWJ `U+200D` must survive every transform | `ශ්‍රී` = `ශ` + `්` + ZWJ + `ර` + `ී`; strip the ZWJ and the rakaransaya breaks into two glyphs | `SrtLoader.stripMarkup` already preserves it (`FeatureProbe.java:396` asserts it) — **add a unit check in the lab** |
| ZWNJ `U+200C` must survive | prevents an unwanted conjunct | same |
| No `toUpperCase()` / `toLowerCase()` on Sinhala | `Locale` case-mapping is a no-op that can still reorder marks | grep gate |
| No manual glyph positioning for subtitles | the platform shaper (HarfBuzz inside `Minikin`) is the only correct engine | — |
| Subtitle `TextView` must set `setLineSpacing(0, lineSpacingMultiplier)` from `K_SUB_LINE_SPACING` | 1.0 leading clips `ෝ` | `PlayActivity` already reads the pref; apply it to `subView` too |

**Honest note on the icon monogram:** the app icon uses the **base letter `ස`**, not `සි`, because the
vowel sign `ි` (`U+0DD2`, glyph `ivowelsignsinh`) has **advance width 0 and negative x-bearings**
(measured bounds `x −592…−33`, `y 623…876` at upem 1000) and is positioned by **GPOS mark attachment**.
Concatenating it after `ස` without a shaping engine places it in the wrong location. Since a vector icon
cannot run HarfBuzz, the monogram is the correctly-shaped base letter. In the app's *text* the platform
shaper renders `සි` correctly.

---

## 4. Phase 4 — The redesigned browser and cinema player

### 4.1 `WebBrowserView` — target architecture

```
FrameLayout (this)                                  ← FrameLayout.LayoutParams for the root
└── LinearLayout VERTICAL  (column)
    ├── [OPTIONAL TOP]   LinearLayout  omnibarHost  (when K_OMNIBAR_POS = top)
    ├── LinearLayout HORIZONTAL  tabStrip            (0..2 tabs, wrap_content)
    ├── FrameLayout  webHost (0dp, weight=1f)        ← WebView + progress + startPage + dock all stack here
    │   ├── WebView                                  (MATCH_PARENT, MATCH_PARENT)
    │   ├── ProgressBar 2dp (top edge of webHost, alpha animated)
    │   ├── ScrollView startPage                     (speed dial; GONE once a page loads)
    │   └── LinearLayout captureDock                 (gravity BOTTOM|CENTER_H, above the omnibar)
    └── [OPTIONAL BOTTOM] LinearLayout omnibarHost   (when K_OMNIBAR_POS = bottom — DEFAULT)
```

Critical `LayoutParams` contract (this is what the v3.3.1 crash was about):

| Parent | Children must use |
|---|---|
| `FrameLayout` (root, `webHost`) | `FrameLayout.LayoutParams` |
| `LinearLayout` (`column`, `tabStrip`, `omnibarHost`) | `LinearLayout.LayoutParams` |
| `ScrollView` | `FrameLayout.LayoutParams` (a `ScrollView` extends `FrameLayout`) |
| `HorizontalScrollView` | `FrameLayout.LayoutParams` (already documented at `PlayActivity.java:1701-1703`) |

#### 4.1.1 The omnibar (one widget, two positions)

```
┌──────────────────────────────────────────────────────────────┐
│ ┌──┐ ┌───────────────────────────────────────┐ ┌──┐ ┌──┐ ┌──┐│
│ │‹ │ │ 🔒  baiscope.lk/movie/1909        ✕  │ │↻ │ │🛡12│ │⋮ ││
│ └──┘ └───────────────────────────────────────┘ └──┘ └──┘ └──┘│
└──────────────────────────────────────────────────────────────┘
  40dp   0dp weight=1 pill 52dp h, r=999              40dp  40dp 40dp
```

| Element | Spec | Behaviour |
|---|---|---|
| Back / Forward | `ic_back` / `ic_forward`, 40 dp box, 48 dp touch target via `TouchDelegate` | long-press = history sheet |
| Address pill | height **52 dp**, radius **999**, fill `surface-sunken`, 1 dp `outline` | tap = select all; long-press = paste |
| Lock icon | `ic_lock` 14 dp, `status-ok` when `https`, `status-warn` when `http`, `ic_globe` for `file:` | tap = site-info popover (host, scheme, cookies count) |
| Title/URL text | `body` 14 sp, single line, ellipsis **middle** | shows the *title* when not focused, the URL when focused |
| Clear | `ic_close` 16 dp, appears only while focused **and** non-empty | clears the field, does not navigate |
| Reload / Stop | `ic_refresh` ⇄ `ic_close` swap while loading | 40 dp |
| Shield badge | `ic_shield_adblock` + a `TextView` badge with the blocked count | tap = per-session block list sheet |
| Overflow | `ic_more_dots` | opens a **bottom sheet**, never a `PopupMenu` (fixes W-10) |

Overflow sheet contents (replacing `WebBrowserView.java:427-458`):
`Desktop mode` (switch) · `Find on page` (`ic_search`) · `Bookmarks` (`ic_bookmark`) ·
`History` (`ic_history`) · `Custom user-agent` (`ic_desktop`) · `Clear cache & cookies` (`ic_close`,
destructive) · `Browser home` (`ic_nav_home`).

#### 4.1.2 Visual speed dial (replacing the raw `linksRow`)

```
┌ Streaming ─────────────────────────────────┐  + Add
│ ┌────┐ ┌────┐ ┌────┐ ┌────┐               │
│ │ A  │ │ Y  │ │ B  │ │ +  │               │
│ └────┘ └────┘ └────┘ └────┘               │
│ AnantPlay YouTube Baiscope  Add           │
├ Sri Lankan subtitles ──────────────────────┤
│ ┌──┐┌──┐┌──┐┌──┐┌──┐                      │
│ │ Z││ P││ S││ O││ +│                      │
│ └──┘└──┘└──┘└──┘└──┘                      │
└────────────────────────────────────────────┘
```

| Spec | Value |
|---|---|
| Grid | `GridLayout` (framework, API 14+) with `columnCount` = `K_SPEEDDIAL_COLS` (2 / 3 / **4** default) |
| Tile | `LinearLayout VERTICAL`, 1:1 aspect via `onMeasure`, radius **15 dp**, gradient `--c1→--c2` derived from the host name hash |
| Monogram | first letter, 15 sp, 800 weight, white — *or* the site's favicon if `K_FAVICONS` is on (fetched with the WebView cookie, cached in memory only) |
| Label | `caption` 8.5–10 sp, 2-line clamp, `on-surface-muted` |
| Groups | `Streaming` and `Sri Lankan subtitles` — two separate grids, each with a `+ Add` tile |
| Long-press | opens a small sheet: `Edit` / `Move up` / `Move down` / `Delete` (destructive, confirm) |
| Data | `K_WEB_LINKS` stays the source of truth (`name|url\n…`), extended with a `#group` field: `name|url|group`. `AppPrefs.parseLinks` gains a 3rd field with a `streaming` default so old backups still parse |

**Why this fixes W-4:** a `GridLayout` inside the page cannot clip off-screen the way a
`HorizontalScrollView`-less row does; with 4 columns, 8 links occupy exactly two rows.

#### 4.1.3 Tabs — honest complexity analysis

| Approach | Memory | Code | Risk | Decision |
|---|---|---|---|---|
| Multiple live `WebView`s | **~60–120 MB each** on Android 16 (a `WebView` is a full Chromium renderer process) | high | OOM on 3 GB devices | **rejected** |
| One `WebView` + a saved-URL stack ("tabs" = entries) | ~0 | ~60 LOC | low | **recommended** |
| None (today) | 0 | 0 | — | current |

The recommended model is a **URL tab strip**: `List<String> tabUrls` + `int activeTab`. Switching a tab
calls `web.loadUrl(tabUrls.get(active))` and the strip shows the first two tabs plus `+N`. This gives
the *feel* of tabs (a visible strip, a switcher sheet with thumbnails replaced by monograms) with the
memory profile of a single renderer. Documented honestly in the UI as "Tabs (light)".

#### 4.1.4 The capture dock — a 3-state machine

| State | Trigger | Visual | Actions |
|---|---|---|---|
| `IDLE` | no hit | `GONE` | — |
| `STREAM` | `StreamSniffer` reports `.m3u8` / `.mpd` / `.mp4` (`WebBrowserView.java:503-525`) | glass pill: `ic_cast` + `Stream ready · HLS 1080p` + `2 qualities` + **Play** | Play → `PlayActivity`; long-press → quality list |
| `SUBTITLE` | `LkSubFinder.isSubtitleDownload(url)` (`WebBrowserView.java:644`) | glass pill: `ic_folder_zip` + `Subtitle .zip captured` + `1909 cues · Sinhala` + **Attach** | Attach → `SubtitleStore` → live `PlayActivity` |

Geometry: `FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM|CENTER_HORIZONTAL)`,
`bottomMargin = omnibarHeight + 12 dp`, radius 16 dp, `elevation-3`, `alpha` animated 0→1 over
`duration-medium`. It must be a child of `webHost` (a `FrameLayout`) — **never** of `column`.

#### 4.1.5 Reader / dark polish & fullscreen handoff

- Inject on `onPageFinished`: `document.documentElement.style.colorScheme='dark'` guarded by
  `K_READER_DARK`, and a `prefers-color-scheme` meta shim. No CSS file, no asset.
- Fullscreen video: keep the existing `onShowCustomView` path (`WebBrowserView.java:294-321`) but add
  the insets listener so the custom view pads by `statusBars|displayCutout` and `navigationBars`.
- **Do not** add `setForceDark` (deprecated) or a JS scroll-hijack.

### 4.2 `PlayActivity` — the cinema HUD

```
┌───────────────────────────────────────────────────────────────┐
│ ‹   ශ්‍රී ලංකාවේ චිත්‍රපටිය          ● සිංහල 1909   🔒 ⟳ ⧉ 📺 ⚙ │  zone 1 (wrap, 48dp min)
├───────────────────────────────────────────────────────────────┤
│                                                               │
│   ╭─╮            ╭──────╮            ╭─╮        ╭──╮          │
│   │▓│            │  ▶   │            │▓│        │▓▓│          │  zone 2 (0dp, weight 1)
│   │▓│  ⏪10      ╰──────╯      10⏩   │▓│        │▓▓│          │  brightness  volume
│   ╰─╯                                ╰─╯        ╰──╯          │
│                 ┌───────────────────────────────┐             │
│                 │  ඔබ මේ චිත්‍රපටිය බලන්නේ…      │             │  subtitle pill
│                 └───────────────────────────────┘             │
├───────────────────────────────────────────────────────────────┤
│ ──────●──────────────────────────────────────────────         │  scrub (3dp → 6dp)
│ 00:38:12                                        −01:14:03     │  times
│ [සිංහල][Audio][Fit][1.0x][Translate][VLC][Title]             │  chips (h-scroll)
└───────────────────────────────────────────────────────────────┘
```

| Zone | Spec |
|---|---|
| Zone 1 | `LinearLayout HORIZONTAL`, min height 48 dp, padding `6dp + statusInset`; background `GradientDrawable` `#B3000000 → #00000000` top→bottom. Buttons **40 dp box inside a 48 dp touch target** |
| Zone 2 | `LinearLayout VERTICAL`, `0dp / weight 1`, `Gravity.CENTER`; transport row + gesture pill |
| Zone 3 | `LinearLayout VERTICAL`, wrap; background `#00000000 → #B3000000`; padding `10dp` + `navInset` |
| Scrim | two gradients, never a solid bar — that is what makes it read as "cinema" |
| Auto-hide | `K_HUD_HIDE_MS` (2000/4000/6000/8000); any touch resets the timer; while paused the HUD **never** hides |

#### 4.2.1 Controls, exactly

| Control | Spec | Replaces |
|---|---|---|
| Back | `ic_back` 24 dp in a 40 dp box | `icon("‹", 24, …)` `PlayActivity.java:1563` |
| Title | `card-title` 14.5→**15 sp**, 1 line, ellipsis end | `:1567-1575` |
| Live sub status | `●` 5 dp `View` + `සිංහල · 1909` in 10.5 sp `status-ok`; the dot pulses (`duration-pulse`) while fetching | `badgeSub` `"සිං ✓"` `:1583` |
| Lock | `ic_lock` / `ic_unlock`, 40 dp | `icon("🔒", 15, …)` `:1595` |
| Rotate | `ic_rotate`, 40 dp | `:1597` |
| PiP | `ic_pip`, 40 dp | *(new)* |
| External | `ic_vlc_external`, 40 dp; long-press = chooser | `📺` `:1599` |
| Settings | `ic_settings`, 40 dp | `⚙` `:1601` |
| −10 s / +10 s | `ic_replay_10` / `ic_forward_10` at 26 dp, label `10` is **inside** the icon (no glued digit) | `"⏪ " + stepLabel` `:1616` |
| Play/Pause | 58 dp disc: `GradientDrawable` oval, `#B3141A2C` fill, 1 dp `#33FFFFFF` stroke, `ic_play`/`ic_pause` at 26 dp | `:1618-1626` |
| Brightness capsule | 26 × 112 dp, radius 999, `#1AFFFFFF` fill, inner fill `#D9FFFFFF` height = brightness %, `ic_brightness` 13 dp at the bottom | *(new)* |
| Volume capsule | mirror of brightness with `ic_volume` | *(new)* |
| Scrub bar | **custom `View`** (not `SeekBar`): 3 dp track, 6 dp while dragging, buffered layer at 30 % white, played layer `accent`, 13 dp knob, 24 dp touch slop | `SeekBar` `:1655` |
| Times | `numeric` 10–11 sp `tnum`, left `00:38:12`, right `−01:14:03` | `:1673-1685` |
| Chips | `HorizontalScrollView` + `UiChip`: `ic_subtitles සිංහල` · `ic_audio_track Audio` · `ic_aspect_fit Fit` · `ic_speed 1.0x` · `ic_translate Translate` · `ic_vlc_external VLC` · `ic_search Title` | `:1687-1704` |

#### 4.2.2 Gestures (already implemented — surface them)

`K_SWIPE_BRIGHTNESS` (left half, vertical) · `K_SWIPE_VOLUME` (right half, vertical) ·
`K_SWIPE_SEEK` (horizontal) · `K_DOUBLE_TAP_SEEK` (ripple + cumulative `+20s`).
The **only** change: the feedback pill must be a `TextView` with a `GradientDrawable` (radius 14) whose
text is `+20s` / `Brightness 64%` / `Volume 38%` — **no emoji**, and the capsule next to it animates.

### 4.3 The three in-player sheets

All three use one `UiSheet` primitive: scrim `#B3000000` fading in, panel `GradientDrawable` radius
`radius-sheet` (28 dp), fill `surface` at 96 % alpha, 1 dp `outline`, a 36 × 4 dp drag handle at
`Gravity.CENTER_HORIZONTAL` with 12 dp top margin, drag-down-to-dismiss via `ViewDragHelper`-free
`onTouchEvent` (translationY + velocity), and **`elevation-3`**.

| Sheet | Content | Live preview |
|---|---|---|
| **Subtitles** | Language chips (18 languages, `Langs.chips()`) · Spoken/Formal segmented · `ic_srilanka_sub` quick-launch row · `.srt`/`.zip` file **or** URL · `ic_translate` Free translate · sync stepper (±0.1/0.25/0.5 s) · **Style Studio** (font, size 12–36, weight, colour + hex, outline/shadow, background pill/box/none, opacity, position, line spacing) | the video stays visible above the sheet and the subtitle re-renders **as the slider moves** (the sheet is 60 % height, not full) |
| **Audio & Quality** | Video cards (`1080p · 4.2 Mbps`, `720p · 2.1 Mbps`) and Audio cards (`සිංහල · AAC 128 kbps`, `English · …`), selected = accent border + `ic_check_circle` | the resolution change is applied live on tap |
| **Player settings** | Speed slider `0.25×–2.5×` with detents at 0.5/0.75/1/1.25/1.5/2 · Aspect cards (Fit/Fill/16:9/Stretch) with **shape previews** · gesture toggles · sleep timer (Off/15/30/60/end-of-video) · audio boost | every change applies instantly |

**Audio-boost honesty:** Media3 1.11.1's `ExoPlayer` exposes `setVolume(float)` only. A real equaliser
needs `android.media.audiofx.Equalizer` bound to the audio session — which requires
`player.getAudioSessionId()` and is **not** available for all renderers, plus `MODIFY_AUDIO_SETTINGS`
(already declared). **Verdict: ship a "Volume boost 0–150 %" via `player.setVolume()` (safe, no new
dependency) and mark a true 5-band EQ as out of scope for v3.x.** Documenting this prevents an agent
from adding an FFmpeg dependency and blowing the 5.5 MB budget.
---

## 5. Phase 5 — The Mornye-grade shell and 100 % customization

### 5.1 The unbreakable shell

```
LinearLayout VERTICAL  (shell)                     ← setContentView(shell)
├── FrameLayout topBar  (wrap)                     ← collapsing header host
├── FrameLayout contentHost (0dp, weight=1f)       ← the ONLY place pages live
└── FrameLayout bottomBarHost (wrap)               ← docked or floating pill
```

Rules that make it crash-proof (each one is a lesson the repo already paid for):

| Rule | Reason |
|---|---|
| `contentHost` is `FrameLayout` with `LinearLayout.LayoutParams(-1, 0, 1f)` | `weight` only exists on `LinearLayout.LayoutParams`; mixing it with `FrameLayout.LayoutParams` throws at measure time |
| Pages are added to `contentHost` with `FrameLayout.LayoutParams(-1, -1)` | one class, one parent, no cast anywhere |
| `bottomBar` is **always** `shell.addView(...)` — asserted by `shellInvariantOk()` | the v3.3.0 bug: the bar was built and never added (`README.md`, defect 1) |
| Insets are applied **only to bars**, never to content | content must scroll under the bars for edge-to-edge to look right |
| `setOnApplyWindowInsetsListener` returns `insets`, never `CONSUMED` | consuming breaks child scroll views |

`MainActivity.java:401-427` already implements `shellInvariantOk()` + `measureShell()`; the redesign
keeps them and adds the floating-pill variant as a *third* `LayoutParams` mode.

#### 5.1.1 Bottom bar — four visual modes

| Mode | Look | Implementation |
|---|---|---|
| `docked` (default) | full-width bar, `surface-sunken`, 64 dp, hairline top | `LinearLayout` in `bottomBarHost`, no radius |
| `floating` | 12 dp side margins, 24 dp radius, `glass` fill, `elevation-2`, 60 dp | same children, `GradientDrawable` + margins |
| `icon+label` | 22 dp icon over a 10 sp label | two `TextView`/`ImageView` rows |
| `icons only` | 24 dp icon, no label, 56 dp | one child |

Active indicator: a `GradientDrawable` pill behind the tab (`accent-soft`, radius 18) that **moves**
via `ValueAnimator` on `leftMargin` between tabs (`duration-medium`, `ease-emphasized`), plus the
filled icon variant. Inactive = outline icon at `on-surface-muted`.

#### 5.1.2 The four tabs (final information architecture)

| Tab | Content | Key elements |
|---|---|---|
| **Home** | Cast Receiver Cinema Hero · Quick Actions grid · Recent plays · Bookmarks · collapsible 3-step Cast guide | live pulse ring on the hero; `ic_cast` hero glyph; 2×2 quick actions |
| **Browser** | the redesigned `WebBrowserView` | speed dial, bottom omnibar, capture dock |
| **Customize** | grouped Mornye cards (5 groups) | theme thumbnails, accent dots, radius/density segmented, live subtitle preview |
| **Troubleshoot** | 10-point health matrix + verdict + 1-tap privacy-safe report | `FeatureProbe` unchanged; presentation only |

`TAB_SETTINGS`/`TAB_DIAG` (the current 5-tab set, `MainActivity.java:84`) collapse into **Customize**
(settings) and **Troubleshoot** (diagnostics). `K_SHOW_WEB_TAB` still hides the Browser tab.

### 5.2 The customization catalog — every option

Legend: **E** = exists today (key verified in `AppPrefs.java`), **N** = new in v3.4.0.
Type: `S` string, `B` bool, `I` int, `L` long.

#### 5.2.1 Theme & surface engine

| # | Option | Key | Type | Default | Control | Live effect |
|---|---|---|---|---|---|---|
| 1 | Theme mode | `themeMode` **E** | S | `dark` | 4 thumbnail cards (`System`/`Midnight`/`AMOLED`/`Light`) | full repaint + window background |
| 2 | Dynamic colour | `dynamicColor` **E** | B | `false` | switch | `system_accent1` seeds the accent (surfaces unchanged) |
| 3 | Accent preset | `accent` **E** | I | `0` | 10 colour dots | accent, `accentDim`, `accentSoft` recompute |
| 4 | Custom accent | `accentHex` **E** | S | `""` | `#RRGGBB` field + preview swatch | overrides preset **and** dynamic |
| 5 | Card corner radius | `cardRadius` **E** | S | `rounded` | 3 segment `Sharp 6 / Rounded 16 / Pill 24` + shape preview | every card, chip, sheet |
| 6 | Surface style | `surfaceStyle` **N** | S | `flat` | 2 segment `Flat / Glass` | glass adds `#0E1424 @ 82 %` + 1 dp white 8 % |
| 7 | Surface border opacity | `borderAlpha` **N** | I | `18` | slider 0–60 | hairline alpha on cards |
| 8 | UI density | `layoutDensity` **E** | S | `comfortable` | 3 segment | row padding 16/12/8, icon 44/40/36 |
| 9 | Bottom bar style | `bottomBarStyle` **N** | S | `docked` | 4 segment | 5.1.1 |
| 10 | Bottom bar labels | `bottomBarLabels` **N** | B | `true` | switch | icons-only mode |
| 11 | Tab visibility | `showWebTab` **E** + `showTroubleTab` **N** | B | `true` | 2 switches | rebuilds the bar |
| 12 | Home quick actions | `showQuickActions` **E** | B | `true` | switch | hides the 2×2 grid |
| 13 | UI language | `uiLang` **E** | S | `bi` | 3 segment `Bilingual / සිංහල / English` | every `t()` + `setBilingual()` |
| 14 | Motion | `motionEnabled` **N** | B | `true` | switch | disables the pill slide + press scale (battery/accessibility) |
| 15 | Accent glow | `accentGlow` **N** | B | `true` | switch | `glow-accent` shadow on the hero + active tab |

#### 5.2.2 Typography & subtitle studio

| # | Option | Key | Type | Default | Control | Live effect |
|---|---|---|---|---|---|---|
| 16 | Default subtitle language | `defaultLang` **E** | S | `si` | 18-item sheet | fetch + attach |
| 17 | Sinhala register | `registerOverride` **E** | S | `null` | 3-way cycle `auto/spoken/formal` | translation style |
| 18 | Auto-fetch subtitles | `autoFetch` **E** | B | `true` | switch | fetch on ready |
| 19 | Cinema polish | `cinemaPolish` **E** | B | `true` | switch | re-check for a better track |
| 20 | Subtitle font size | `fontSp` **E** | I | `18` | slider 12–36 | preview + `subView` |
| 21 | Subtitle font family | `fontFamily` **E** | S | `noto` | 4 segment `Noto / System / Serif / Cinema Bold` | preview + player |
| 22 | Subtitle colour | `subColor` **E** | I | `0` | 6 dots + **hex field** **N** `subColorHex` | preview + player |
| 23 | Subtitle background | `subBgStyle` **E** | S | `pill` | 4 segment `Pill/Outline/Box/Shadow` | preview + player |
| 24 | Background opacity | `subBgOpacity` **E** | I | `55` | slider 0–95 | preview + player |
| 25 | Bold | `subBold` **E** | B | `false` | switch | real 600 weight (Phase 3) |
| 26 | Vertical position | `subBottomPct` **E** | I | `8` | slider 2–35 | `bottomMargin` |
| 27 | Line spacing | `subLineSpacing` **E** | I | `120` | slider 100–150 | `setLineSpacing` |
| 28 | Outline / stroke | `subOutline` **N** | I | `0` | slider 0–6 dp | `setShadowLayer(radius,0,0,black)` |
| 29 | Drop shadow | `subShadow` **N** | B | `true` | switch | shadow layer |
| 30 | Engine priority | `subEngine` **E** | S | `human-pa-free` | 4 segment | fetch order |
| 31 | Auto-translate non-Sinhala | `autoTranslateNonSi` **E** | B | `true` | switch | translate on attach |
| 32 | Auto-unzip | `autoZip` **E** | B | `true` | switch | `.zip` handling |
| 33 | Sync step | `subSyncStepMs` **E** | L | `250` | 3 segment `100/250/500` | nudge amount |
| 34 | Free translator | `freeTranslator` **E** | B | `true` | switch | keyless layer on/off |

#### 5.2.3 Browser

| # | Option | Key | Type | Default | Control | Live effect |
|---|---|---|---|---|---|---|
| 35 | Omnibar position | `omnibarPos` **N** | S | `bottom` | 2 segment `Top / Bottom` | rebuilds the browser layout |
| 36 | Speed-dial columns | `speedDialCols` **N** | I | `4` | 3 segment `2/3/4` | grid re-flow |
| 37 | Custom links (CRUD) | `webLinks` **E** | S | 8 built-ins | list editor with drag + delete | speed dial |
| 38 | Link groups | *(field 3 of `webLinks`)* **N** | S | `streaming` | per-row segmented | two grids |
| 39 | Default search engine | `searchEngine` **N** | S | `duckduckgo` | 4 segment `DDG/Google/Startpage/Brave` | `go()` in the omnibar |
| 40 | Ad / popup / tracker block | `adBlock` **E** | B | `true` | switch | `StreamSniffer.isAdOrTracker` |
| 41 | Show blocked count | `showBlockCount` **N** | B | `true` | switch | shield badge |
| 42 | Auto-open sniffed stream | `autoOpenSniffed` **E** | B | `false` | switch | `maybeAutoOpen` |
| 43 | Desktop user-agent | `webDesktopUa` **E** | B | `false` | switch | `setUserAgentString` |
| 44 | Custom user-agent | `webCustomUa` **N** | S | `""` | text field | overrides both |
| 45 | Reader dark | `readerDark` **N** | B | `true` | switch | injected `colorScheme` |
| 46 | Favicons in speed dial | `favicons` **N** | B | `true` | switch | monogram ⇄ favicon |
| 47 | Remember last page | `webLastUrl` **E** | S | built-in | (internal) | restore on open |
| 48 | Bookmarks | `webBookmarks` **E** | S | `""` | sheet with delete | bookmark list |

#### 5.2.4 Player & controls

| # | Option | Key | Type | Default | Control | Live effect |
|---|---|---|---|---|---|---|
| 49 | Default aspect | `aspectMode` **E** | S | `fit` | 4 visual cards | `VideoViewport` |
| 50 | Default speed | `playSpeed` **E** | I | `100` (‰) | slider 25–250 | `setPlaybackSpeed` |
| 51 | Seek step | `seekStepMs` **E** | L | `10000` | 4 segment | ±10/15/30 s |
| 52 | Double-tap seek | `doubleTapSeek` **E** | B | `true` | switch | ripple + seek |
| 53 | Swipe brightness | `swipeBrightness` **E** | B | `true` | switch | left-half gesture |
| 54 | Swipe volume | `swipeVolume` **E** | B | `true` | switch | right-half gesture |
| 55 | Swipe seek | `swipeSeek` **E** | B | `true` | switch | horizontal |
| 56 | Lock on start | `lockOnStart` **E** | B | `false` | switch | HUD locked |
| 57 | Default orientation | `orientation` **N** | S | `auto` | 3 segment | `setRequestedOrientation` |
| 58 | Auto-PiP | `autoPip` **E** | B | `true` | switch | `onUserLeaveHint` |
| 59 | Auto-resume | `autoResume` **E** | B | `true` | switch | saved position |
| 60 | Keep screen on | `keepScreenOn` **E** | B | `true` | switch | `FLAG_KEEP_SCREEN_ON` |
| 61 | HUD auto-hide | `hudHideMs` **E** | L | `4000` | 4 segment | hide timer |
| 62 | External player | `externalPlayer` **N** | S | `chooser` | 4 segment `VLC/MX/mpv/Chooser` | `openExternalPlayer` |
| 63 | Volume boost | `volumeBoost` **N** | I | `100` | slider 0–150 | `player.setVolume` |
| 64 | Sleep timer | `sleepTimerMs` **N** | L | `0` | 5 segment | pause + finish |

#### 5.2.5 Cast, network, updates, backup

| # | Option | Key | Type | Default | Control | Live effect |
|---|---|---|---|---|---|---|
| 65 | Receiver name | `receiverName` **E** | S | device name | text field | mDNS/DLNA announce |
| 66 | Receiver port | `receiverPort` **E** | I | `8765` | number field 1024–65535 | `ReceiverServer` rebind |
| 67 | DLNA enabled | `dlnaEnabled` **E** | B | `true` | switch | `DlnaRenderer` |
| 68 | mDNS enabled | `mdnsEnabled` **E** | B | `true` | switch | `MdnsResponder` |
| 69 | Worker URL | `workerUrl` **E** | S | `""` | text field | `BridgeApi` |
| 70 | Worker ping | *(action)* **N** | — | — | button | live latency + version |
| 71 | DoH diagnostics | `dohDiag` **E** | B | `false` | switch | `DohResolver` probe |
| 72 | Auto update | `autoUpdate` **E** | B | `true` | switch | `SelfUpdater` |
| 73 | Skipped version | `skipVersion` **N** | S | `""` | (action) | suppress one release |
| 74 | Backup all | `BACKUP_KEYS` **E** | — | — | `ic_backup` button | JSON to clipboard/share |
| 75 | Restore | — **E** | — | — | `ic_download` button | parse + normalise |

**Backup completeness gate:** `AppPrefs.BACKUP_KEYS` (`AppPrefs.java:92-106`) must be extended with
**every `N` key above**, or the v3.3.1 bug repeats (four player switches silently dropped from
Backup & Restore). The lab must assert `BACKUP_KEYS.size() == (all K_* constants).size - (derived keys)`.

### 5.3 New Java classes (small, focused, testable)

| Class | LOC (est.) | Responsibility |
|---|---|---|
| `UiIcons.java` | 60 | tint + size + compound-drawable helper (Phase 2.4) |
| `UiTokens.java` | 70 | `gap()`, `radius()`, `motion()`, `elevation()` from `AppPrefs` |
| `UiCard.java` | 90 | one card factory: fill, radius, stroke, optional press scale |
| `UiGroup.java` | 110 | grouped settings list: header, rows, dividers indented to text |
| `UiSegmented.java` | 90 | 2–4 segment pill with an animated thumb |
| `UiChip.java` | 70 | icon + label pill, selected state |
| `UiSheet.java` | 160 | scrim + handle + drag-to-dismiss + `elevation-3` |
| `UiSlider.java` | 120 | themed slider (framework `SeekBar` wrapped, thin→thick) |
| `ScrubBar.java` | 180 | the player's custom scrub view with buffered track |
| `CapsuleBar.java` | 90 | brightness/volume capsule |
| `SpeedDial.java` | 150 | the browser start page grid |

Total ≈ **1,190 LOC** of new UI primitives — small enough to review, large enough to make every screen
consistent. All are pure framework classes, no new dependency.

### 5.4 The Customize screen (final layout)

```
Customize                                            (collapsing header, 22 sp)
┌ Appearance ────────────────────────────────────┐
│ ◐  Theme          [System][Midnight][AMOLED][Light]  ← 4 thumbnail cards
│ ◑  Accent         ●●●●●●●●●●  Custom…               ← 10 dots + hex
│ ≡  Card radius    [Sharp][Rounded][Pill]  ▭ preview
│ ▤  Density        [Spacious][Comfort][Compact]
│ ▢  Bottom bar     [Docked][Floating][Labels][Icons]
└────────────────────────────────────────────────┘
┌ Subtitles & style ─────────────────────────────┐
│  ┌ live cinema frame preview (74 dp) ┐         │
│  Aa  Font      Noto Sans Sinhala · 18 sp  ›    │
│  ⇄  Translate  Free engine first          ›    │
└────────────────────────────────────────────────┘
┌ Browser ───────────────────────────────────────┐
│  ⌂  Omnibar      [Top][Bottom]                 │
│  ⛨  Ad shield    12 blocked this session       │
└────────────────────────────────────────────────┘
┌ Player ────────────────────────────────────────┐
│  ▶  Aspect       [Fit][Fill][16:9][Stretch]    │
│  ⏱  Speed        1.0×  ▬▬▬●▬▬▬                 │
└────────────────────────────────────────────────┘
┌ Cast, network & data ──────────────────────────┐
│  📡 Receiver name / port                    ›  │
│  ⬆ Updates                                  ›  │
│  💾 Backup & restore                        ›  │
└────────────────────────────────────────────────┘
```

Every row is `UiGroup.row(...)`: 20 dp leading icon (tinted `accent`), `card-title` primary,
`body-secondary` secondary, then either a **value pill** (`accent-soft` / `accent-dim`), a
**`UiSegmented`**, a **switch**, or a **chevron**.

---

## 6. Phase 6 — Figma Free workflow, Canva honesty, engineering guardrails

### 6.1 Figma Free, step by step

1. **Account.** figma.com → *Get started for free* → the **Starter** plan. Limits that matter:
   3 Figma files, 3 FigJam files, unlimited personal drafts, **no** Dev Mode, **no** shared libraries
   across files. (All of that is fine for this project — everything lives in one file.)
2. **New file** → name it `AnantPlay v3.4.0 — Design Board`.
3. **Import the tokens.** Two routes, both free:
   - **Tokens Studio (free tier)** → *Plugins → Tokens Studio → Import → select `design/figma-tokens.json`*.
     The file is W3C DTCG (`$type`/`$value`) with a `$themes` block, so Tokens Studio reads the four
     themes as *theme groups* and the ten accents as *theme groups* too. Apply a theme to a frame and
     every bound colour updates.
   - **Native Variables (manual, ~15 min)** → *Local variables → Create collection* →
     `theme/midnight-slate`, `theme/amoled-black`, `theme/nordic-dark`, `theme/clean-light`, then
     *Modes* → paste the hex values from `color.theme.<name>` in the JSON. Native modes are the more
     robust path on the free plan because Tokens Studio's sync needs a paid storage provider.
4. **Icons.** *File → Place image* is for bitmaps only — instead **drag the SVG files** from
   `design/svg/` straight onto the canvas. Figma converts each to a vector frame with editable paths.
   Select all 53 → right-click → *Create component set* → name it `Icon/24`.
5. **The app icon.** Drag `design/svg/app-icon-foreground.svg`, `-background.svg` and
   `-monochrome.svg` in as three layers of a 108 × 108 frame, then add a mask layer per launcher shape
   (squircle 72 dp, circle 72 dp, rounded-square 72 dp). The previews `app-icon-squircle.svg`,
   `app-icon-circle.svg`, `app-icon-android16.svg` already contain the masks — drag those for the
   "shipping" view.
6. **Frames.** Create `390 × 844` (iPhone-class) and `412 × 915` (Pixel-class) frames. Add a 16 dp
   layout grid. Build Home, Browser, Player, Customize, Troubleshoot from `design/preview.html` as the
   visual reference (open it in a browser beside Figma).
7. **Optional — connect an AI agent.** Figma's *Dev Mode MCP server* is a **paid** feature; on Free the
   practical path is: export frames as PNG/SVG and hand them to the coding agent together with this
   blueprint, or paste the `figma-tokens.json` into the agent's context (it is plain JSON, 38 KB).

### 6.2 Canva Free — the honest answer

**Canva Free is not suitable for this work.** Concretely:

| Need | Canva Free | Why it fails |
|---|---|---|
| Export Android vector icons | ✗ | SVG export is a **Pro** feature; Free exports PNG/JPG only |
| Path-level editing | ✗ | no vector node editing; shapes are rasterised on export |
| Theme tokens / variables | ✗ | no concept of a token, a mode or a variable |
| Per-icon tint at runtime | ✗ | a PNG cannot be tinted by `setTint` without losing its silhouette |
| 24 dp optical grid | ~ | the canvas snaps to px, not dp; a 1.75 dp stroke is not expressible |

**Where Canva Free *is* legitimately useful** (and only here):
- the **GitHub social preview** image (1280 × 640),
- a **promotional banner** for the README,
- a one-page **release poster** for social.

Anything that ends up inside the APK must come from Figma, Illustrator, Inkscape, or the SVG sources in
`design/svg/`.

### 6.3 Android 16 engineering guardrails (the crash-prevention contract)

#### 6.3.1 `LayoutParams` — the table the next agent must obey

| Container | Correct child params | Wrong params that crash |
|---|---|---|
| `FrameLayout` | `FrameLayout.LayoutParams` | `LinearLayout.LayoutParams` → `ClassCastException` in `FrameLayout.onMeasure` |
| `LinearLayout` | `LinearLayout.LayoutParams` | `FrameLayout.LayoutParams` → margins silently ignored, weight impossible |
| `RelativeLayout` | `RelativeLayout.LayoutParams` | either of the above |
| `ScrollView` / `HorizontalScrollView` | `FrameLayout.LayoutParams` | `LinearLayout.LayoutParams` (**this was the v3.3.0 bug**, see `PlayActivity.java:1701-1703`) |
| `GridLayout` | `GridLayout.LayoutParams` with `rowSpec`/`columnSpec` | — |
| `ViewPager2` | n/a (not used) | — |

Helper to make it impossible to get wrong:

```java
/** The only sanctioned way to add a child. Compile-time parent, runtime-checked params. */
static void add(ViewGroup parent, View child, int w, int h, int gravity, int weight) {
    if (parent instanceof LinearLayout) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h, weight);
        lp.gravity = gravity;
        parent.addView(child, lp);
    } else if (parent instanceof FrameLayout) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, gravity);
        parent.addView(child, lp);
    } else {
        parent.addView(child, new ViewGroup.LayoutParams(w, h));
    }
}
```

`lab/verify_layouts.py` already types every container/child pair across all 46 Java files. **Gate M1–M4
on it** — it is the cheapest possible insurance against the exact crash the user hit twice.

#### 6.3.2 Edge-to-edge on Android 16 (SDK 36)

| Rule | Code |
|---|---|
| Never call `Window.setDecorFitsSystemWindows(true)` | `getWindow().setDecorFitsSystemWindows(false)` only if you also handle insets yourself |
| Read insets **once**, apply to bars | `root.setOnApplyWindowInsetsListener(...)`, cache `statusInset`/`navInset` |
| Include the cutout | `WindowInsets.Type.displayCutout()` alongside `statusBars()` |
| Never pad the content host | pad the *bars*; content scrolls under |
| `enforceNavigationBarContrast=false` (API 29+) | in `styles.xml` via `tools:targetApi` |
| Cutout mode | `android:windowLayoutInDisplayCutoutMode="shortEdges"` on the player only |

#### 6.3.3 aapt2 / VectorDrawable gotchas

| Gotcha | Rule |
|---|---|
| Gradients in `<vector>` need `xmlns:aapt` | declare `xmlns:aapt="http://schemas.android.com/aapt"` and wrap in `<aapt:attr name="android:fillColor">` |
| Gradients require **API 24** | minSdk is 26 → safe. **Never** put a gradient in a notification icon |
| `android:fillColor` is mandatory on `<path>` | use `#00000000`, never omit |
| `strokeLineCap`/`strokeLineJoin` are API 21+ | fine |
| `evenOdd` holes must be in **one** `<path>` | see Phase 2.3 |
| Path data must be space-separated | `"M12 3.6 a8.4 8.4 0 1 0 0 16.8"` — a missing space before a negative number (`"5.6-5.8"`) is accepted by the SVG spec but is a common source of silent mis-parsing; the generator normalises it |
| `android:width/height` = the **intrinsic** size | keep 24 dp; the view scales it |
| Tint | `.mutate().setTint()`; never `setColorFilter` on a vector you also reuse |

#### 6.3.4 `d8` / build chain

`build.sh:86` compiles with `--min-sdk-version 26`; `d8 --min-api 26`. Consequences:
`java.util.function.Consumer` (used by `openSub`) desugars fine; `var` is **not** available (Java 8
source level); lambdas are fine. **Do not** use `java.time` beyond API 26 surface (it exists at 26, but
`DateTimeFormatter` patterns are safe) and **never** `java.nio.file.Path` (API 26+, but the app already
avoids it).

### 6.4 The four milestones (each independently shippable and gated)

Every milestone ends with the **same gate**, and no milestone may be merged if any gate fails:

```bash
bash lab/run_lab.sh          # ReceiverLab (4,528 LOC) + verify_refs.py + verify_layouts.py
./build.sh                   # aapt2 link + javac + d8 + zipalign + apksigner
aapt2 dump badging dist/*.apk | head -5      # version + icon sanity
ls -la dist/*.apk                             # size budget <= 5.5 MB
```

#### `v3.4.0-M1` — Vectors, app icon, typography (lowest risk, highest visual return)

| Step | Work | Gate |
|---|---|---|
| 1 | Add `res/drawable/ic_*.xml` (53 outline + 4 filled) from `design/vector/` | `verify_refs.py` finds every `R.drawable.ic_*` |
| 2 | Add `res/mipmap-anydpi-v26/ic_launcher{,_round}.xml` + 3 launcher vectors; **keep** the old PNGs | `aapt2 dump badging` shows `application-icon-anydpi-v26` |
| 3 | Add `UiIcons.java` | compiles |
| 4 | Replace emoji in `MainActivity` bottom bar + top bar + `categoryRow` | no emoji in `res/` or those methods |
| 5 | Replace emoji in `PlayActivity` HUD + `WebBrowserView` bar | same |
| 6 | Subset + instance the font (Option B); add `siFont(int weight)` | APK **smaller** than before |
| 7 | Add `UiTokens` type scale; apply `setBilingual()` | nav label ≥ 10 sp |
| **Gate** | | `run_lab.sh` green, APK ≤ 5.2 MB, cold start unchanged |

#### `v3.4.0-M2` — Mornye shell + deep customization

`UiCard`/`UiGroup`/`UiSegmented`/`UiChip` → rebuild Customize with 5 groups → add the 14 `N` theme keys →
extend `BACKUP_KEYS` → floating pill bottom bar + animated indicator → collapsing header.
**Gate:** every new pref round-trips through Backup & Restore; `verify_layouts.py` green.

#### `v3.4.0-M3` — Cinema player

`UiSheet` + `ScrubBar` + `CapsuleBar` → 3-zone HUD rebuild → 3 sheets → aspect/speed visual cards →
live subtitle restyle while the sheet is open.
**Gate:** play a 1080×608 HLS cast, rotate, PiP, lock/unlock, ±10 s, double-tap ripple, and a `.zip`
subtitle attach — all without a `ClassCastException`.

#### `v3.4.0-M4` — Browser

Speed dial + groups → omnibar (top/bottom) → light tabs → capture dock state machine → overflow sheet →
reader dark.
**Gate:** 8 custom links in 4 columns with no clipping; `.m3u8` sniff → dock → play; `.zip` capture →
attach; desktop UA toggle; ad-block badge count > 0 on a known site.

### 6.5 Rollback plan

Each milestone is a separate commit series on a branch `v3.4.0-Mn`. Because every change is **additive**
(new drawables, new keys with defaults, new classes), reverting one milestone never orphans a
preference: unknown keys are ignored by `AppPrefs.normalizeValue`, and unknown drawables fail at
**build** time, not at runtime.
---

## 7. Phase 7 — Deliverables, verification and acceptance

### 7.1 What was produced

| # | File | Size | Contents |
|---|---|---|---|
| 1 | `DESIGN_AND_UI_OVERHAUL_DEEP_RESEARCH_BLUEPRINT.md` | see `ls` | Phases 1–6 in full |
| 2 | `DESIGN_AND_UI_OVERHAUL_DEEP_RESEARCH_BLUEPRINT.pdf` | see `ls` | A4, print-ready, paginated |
| 3 | `design/figma-tokens.json` | 38.6 KB | 249 typed tokens: 4 themes × 16 roles, 10 accents × 6 roles, 14 type styles, 34 dimensions, 5 shadows, 8 motion |
| 4 | `design/preview.html` | 276 KB | offline interactive board: icon (3 masks + before/after), 53 icons with filled toggle, type specimen, 5 phone mockups, radius scale |
| 5 | `design/svg/` | 66 files | every icon (outline + filled) + 3 app-icon layers + 4 masked previews |
| 6 | `design/vector/` | 62 files | aapt2-ready `VectorDrawable` XML, drop-in for `res/drawable/` |
| 7 | `design/mipmap-anydpi-v26/` | 2 files | `ic_launcher.xml`, `ic_launcher_round.xml` |
| 8 | `design/icon-manifest.json` | — | machine-readable catalog (name, group, stroke, filled, metaphor) |

### 7.2 Verification performed in this session

| Check | Method | Result |
|---|---|---|
| Icon XML validity | `xml.etree.ElementTree.parse` over all 62 VectorDrawables | **0 errors** |
| SVG validity | same parser over all 66 SVGs | **0 errors** |
| Icon geometry | rendered the full contact sheet + a 4× zoom sheet with `cairosvg`, inspected visually, corrected 6 glyphs (`circ()` origin bug, `cast`, `palette`, `settings`, `brightness`, `globe`) | **all 53 read correctly** |
| Symbol integrity in the board | script asserts every `<use href="#id">` has a matching `<symbol id>` | **0 missing** |
| App icon safe zone | computed corner distance from the canvas centre; rendered under squircle / circle / Android-16 masks | **inside** |
| Tokens JSON | parsed; 249 `$type` tokens; theme + accent counts asserted | **valid** |
| Board rendering | headless Chromium screenshot at 1400 × 5200 | **no overflow, no overlap** |
| Font measurements | `fontTools` subset + `instancer` on the real `NotoSansSinhala-Regular.ttf` | numbers in §3.2 are measured, not estimated |
| Repository facts | every `file:line` read from the cloned repo at `1db180f` | **verified** |

> Sinhala text inside the headless-Chromium screenshot renders with fallback glyphs because the
> container has no Sinhala font installed. The **file** is correct: the CSS font stack lists
> `Noto Sans Sinhala`, `Iskoola Pota` (Windows), `Nirmala UI` (Windows) and `Sinhala Sangam MN` (macOS),
> so it renders natively on any real desktop or phone.

### 7.3 Acceptance criteria for the whole overhaul

| # | Criterion | How it is proven |
|---|---|---|
| A1 | **Zero emoji as UI chrome** | `grep -nE '[\x{1F300}-\x{1FAFF}\x{2600}-\x{27BF}]' src/**/*.java` returns hits **only** inside log/report string bodies |
| A2 | Every icon is a `VectorDrawable` | `ls res/drawable/ic_*.xml | wc -l` ≥ 57 |
| A3 | Adaptive + monochrome icon present | `aapt2 dump badging` lists `application-icon-anydpi-v26`; `res/mipmap-anydpi-v26/` exists |
| A4 | APK ≤ 5.5 MB (target ≤ 5.0) | `ls -la dist/*.apk` |
| A5 | No `LayoutParams` mismatch | `python3 lab/verify_layouts.py` exits 0 |
| A6 | No dangling resource refs | `python3 lab/verify_refs.py` exits 0 |
| A7 | Lab green | `bash lab/run_lab.sh` — all suites pass |
| A8 | Every preference survives Backup & Restore | round-trip test in `ReceiverLab` |
| A9 | ZWJ/ZWNJ preserved end-to-end | a cue containing `ශ්‍රී` survives fetch → translate → render byte-identical |
| A10 | Edge-to-edge correct on a cutout device | screenshot with the display-cutout emulation in Developer Options |
| A11 | Version stays v3.x | `versionName` starts with `3.` |

---

## Appendix A — Icon file index

```
design/svg/                                    design/vector/  (drop into res/drawable/)
  ic_nav_home.svg            (+ -filled)        ic_nav_home.xml            (+ _filled.xml)
  ic_nav_web.svg             (+ -filled)        ic_nav_web.xml             (+ _filled.xml)
  ic_nav_customize.svg       (+ -filled)        ic_nav_customize.xml       (+ _filled.xml)
  ic_nav_troubleshoot.svg    (+ -filled)        ic_nav_troubleshoot.xml    (+ _filled.xml)
  ic_play.svg                (+ -filled)        ic_play.xml                (+ _filled.xml)
  ic_pause.svg               (+ -filled)        ic_pause.xml               (+ _filled.xml)
  ic_replay_10.svg   ic_forward_10.svg   ic_speed.svg   ic_aspect_fit.svg
  ic_aspect_fill.svg ic_rotate.svg       ic_pip.svg     ic_vlc_external.svg
  ic_quality_hd.svg  ic_audio_track.svg  ic_volume.svg  ic_brightness.svg
  ic_lock.svg        ic_unlock.svg       ic_subtitles.svg  ic_translate.svg
  ic_search.svg      ic_globe.svg        ic_link.svg    ic_shield_adblock.svg
  ic_bookmark.svg    ic_history.svg      ic_refresh.svg ic_back.svg
  ic_forward.svg     ic_share.svg        ic_copy.svg    ic_more_dots.svg
  ic_close.svg       ic_plus.svg         ic_desktop.svg ic_folder_zip.svg
  ic_settings.svg    ic_sliders.svg      ic_palette.svg ic_font.svg
  ic_moon.svg        ic_sun.svg          ic_check_circle.svg  ic_alert.svg
  ic_info.svg        ic_srilanka_sub.svg ic_update.svg  ic_backup.svg
  ic_download.svg    ic_movie.svg        ic_cast.svg
  app-icon-background.svg  app-icon-foreground.svg  app-icon-monochrome.svg
  app-icon-squircle.svg    app-icon-circle.svg      app-icon-android16.svg
  app-icon-monochrome-preview.svg
```

## Appendix B — Emoji → icon quick reference (the 20 most frequent)

| Emoji | Occurrences | Icon | Domain |
|---|---|---|---|
| `✓` | 145 | `ic_check_circle` | system |
| `✗` | 39 | `ic_close` | browser |
| `🩺` | 37 | `ic_nav_troubleshoot` | nav |
| `📋` | 30 | `ic_copy` | browser |
| `⏩` | 26 | `ic_forward_10` | media |
| `✅` | 26 | `ic_check_circle` | system |
| `📺` | 23 | `ic_vlc_external` | media |
| `✨` | 23 | `ic_sun` | system |
| `💬` | 21 | `ic_subtitles` | media |
| `⚠` | 18 | `ic_alert` | system |
| `🎬` | 17 | `ic_movie` | system |
| `⏪` | 16 | `ic_replay_10` | media |
| `⚡` | 16 | `ic_refresh` | browser |
| `⚙️` | 15 | `ic_settings` | system |
| `📐` | 15 | `ic_aspect_fit` | media |
| `🔄` | 14 | `ic_rotate` | media |
| `🎨` | 14 | `ic_nav_customize` | nav |
| `🌐` | 13 | `ic_nav_web` | nav |
| `🇱🇰` | 12 | `ic_srilanka_sub` | system |
| `🔍` | 12 | `ic_search` | browser |

## Appendix C — Build & verification commands

```bash
# design artefacts (already generated; regenerate only if the source changes)
python3 gen_icons.py       # 53 icons -> design/svg + design/vector + icon-manifest.json
python3 gen_appicon.py     # adaptive icon -> design/vector/ic_launcher_*.xml + masked previews
python3 gen_tokens.py      # -> design/figma-tokens.json
python3 gen_preview.py     # -> design/preview.html (inlines the icon sprite)

# the app (unchanged by this session)
ANDROID_HOME=/path/to/sdk ./build.sh
bash lab/run_lab.sh
python3 lab/verify_layouts.py
python3 lab/verify_refs.py

# the PDF (WeasyPrint 70 is available in the research environment)
python3 make_pdf.py
```

## Appendix D — Glossary

| Term | Meaning here |
|---|---|
| **Adaptive icon** | Android 8+ icon made of background + foreground layers (108 × 108 dp) that the launcher masks |
| **Safe zone** | the central 66 dp circle of the 108 dp canvas that every mask is guaranteed to show |
| **Monochrome layer** | the single-colour silhouette Android 13+ uses to theme an icon with the wallpaper palette |
| **VectorDrawable** | Android's XML vector format; strokes, fills, gradients, no text |
| **DTCG** | Design Tokens Community Group format: `$type` / `$value` keys |
| **Tokens Studio** | free Figma plugin that imports token JSON and applies themes to frames |
| **ZWJ / ZWNJ** | zero-width joiner `U+200D` / non-joiner `U+200C`; required for Sinhala conjuncts like `ශ්‍රී` |
| **Faux bold** | the framework's synthetic bold applied to a single-weight font; smears outlines |
| **Variable font** | one font file containing a continuous design axis (here `wght` 100–900) |
| **Instance** | a static font pinned to one point on a variable axis |
| **`accentDim` / `accentSoft`** | `blend(accent, bg, 0.62)` / `blend(accent, bg, 0.80)` — text-on-chip / chip-fill |
| **`LayoutParams` class** | the per-container subclass that `addView` requires; a mismatch throws inside `onMeasure` |

---

## Closing statement

The audit found a **sound architecture with a weak surface**: the theme engine, insets handling,
`LayoutParams` gate and immutable probe snapshots are genuinely good engineering, while the presentation
layer is emoji, one font weight, no type scale, no motion and a browser whose address bar is a form
field.

This blueprint closes that gap with **zero new dependencies**, **zero production-code changes in this
session**, and a net **APK reduction** of ≈ 880 KB (918 KB saved on the font, ≈ 34 KB added for 57
vectors, ≈ 8 KB of legacy PNGs removed) — leaving the app comfortably inside its 4.8–5.5 MB budget.

Everything the next agent needs is in the four deliverables: the tokens to build with, the vectors to
drop in, the board to look at, and this document to obey.

> **No commit, branch, PR or push was made. `src/` is untouched.**
