# v3.4.0 — UI OVERHAUL IMPLEMENTATION REPORT
### Vector icons · Adaptive app icon · Real Sinhala weights · Mornye shell · Cinema HUD · Safari-grade browser

| | |
|---|---|
| **Version** | `3.4.0` — `versionCode 30400` (previous `3.3.1` / `30301`) |
| **Branch** | `feat/v3.4.0-vector-ui-mornye-player-browser` |
| **Scope** | M1 → M4 of `DESIGN_AND_UI_OVERHAUL_DEEP_RESEARCH_BLUEPRINT.md`, implemented end to end |
| **Lab** | **922 / 922 checks PASS** (773 inherited from v3.3.1 + 149 new `v340` checks) |
| **Static gates** | `verify_layouts.py` **0 problems** (401 layout calls, 57 Java files) · `verify_refs.py` OK |
| **APK (local)** | **3,000,212 bytes (2,929 KiB)** — down from 3,424,979 B (3,344 KiB) at v3.3.1 |
| **APK identity** | `versionCode='30400'` · `versionName='3.4.0'` · `targetSdkVersion='36'` · `application-icon='res/mipmap-anydpi-v26/ic_launcher.xml'` |
| **Signing** | v2 + v3 schemes verified · `zipalign -c 4` OK |
| **Local SHA-256** | `11d7b5b1a8566a2e14e3c0edb47ffed1b2222fc5d755158a348710d96690e2f1` (debug-signed local build) |

---

## 1. What the four blueprints traps were, and how each was prevented

The design review caught four real defects in the draft snippets. All four are now
structurally impossible, not just avoided.

### Trap #1 — `pyftsubset` without ZWJ / ZWNJ
The draft's `--unicodes` list omitted `U+200C`/`U+200D`. Subsetting a Sinhala font without the
zero-width joiner breaks the rakaransaya conjunct (ශ්‍රී = ශ + ් + ZWJ + ර + ී) into two glyphs.

**Fix:** the pipeline now runs with
`--unicodes="U+0020-007E,U+00A0,U+0D80-0DFF,U+200B-200F,U+2010-2014,U+2018-201D,U+2026,U+25CC"`
(the range `U+200B-200F` contains both `U+200C` and `U+200D`), then instantiates the variable
axis at 400 and 600 and drops the variation tables.

**Proof (lab, `v340 fonts` + `v340 A9`):** both files contain `GSUB`, `GPOS`, `GDEF`; both are
static (no `gvar`); `Cue.stripMarkup()` preserves `ශ්‍රී` and `ක්‍ෂ` byte-for-byte while still
stripping `{\an8}` / `<i>`.

| | bytes |
|---|---|
| v3.3.1 variable original | 1,181,956 |
| **v3.4.0 Regular (wght 400)** | **211,264** |
| **v3.4.0 SemiBold (wght 600)** | **211,372** |
| **total (2 real weights)** | **422,636 — 742 KB smaller** |

> Honest note: the brief asked for "combined < 320 KB". **That is below the glyph-outline floor.**
> Measured: 90 % of each file is the `glyf` table (167 KB of Sinhala outlines); GSUB is 7 KB and
> GPOS 5 KB. Reaching 320 KB would require dropping characters or shaping features. The lab
> therefore asserts `< 430 KB` **and** `> 700 KB saved`, and says so in the check name.

### Trap #2 — `UiIcons.setCompound()` clearing the label
The draft called `tv.setText("")` inside `setCompound()`, which would have erased the text of every
labelled chip that gained an icon.

**Fix:** `setCompound()` attaches the tinted compound drawable and `dp(6)` padding and **never
touches the text**. Clearing is a separate, deliberately named `setIconOnly()`.

**Proof:** `v340 UiIcons: setCompound NEVER clears the text (trap #2)` — the lab slices the method
body out of the source and asserts it contains no `setText(`.

### Trap #3 — `UiIcons.view()` hard-coding `LinearLayout.LayoutParams`
Returning an `ImageView` pre-configured with `LinearLayout.LayoutParams` is exactly how the v3.3.0
`ClassCastException: LinearLayout$LayoutParams cannot be cast to FrameLayout$LayoutParams` happened.

**Fix:** `view()` sets **no** `LayoutParams`; the caller supplies the parent's own class at
`addView()`. `UiIcons.paramsFor(parent, …)` / `UiIcons.add(parent, …)` build the right class for a
`LinearLayout`, `FrameLayout`, `GridLayout` or bare `ViewGroup`.

**Proof:** `v340 UiIcons: view() sets NO container-specific LayoutParams (trap #3)` + the whole-file
`verify_layouts.py` gate (0 problems across 401 calls).

### Trap #4 — `styles.xml` `tools:` tricks and two-path "cutouts"
`tools:targetApi` without `xmlns:tools` can fail the aapt2 link, and an inner cutout authored as a
second `<path>` simply paints over the first instead of cutting a hole.

**Fix:**
- `res/values/styles.xml` is **untouched** and free of any `tools:` attribute. The window is themed
  in code by **`UiWindow.apply(Activity, Palette)`**: decor background, `statusBarColor`,
  `navigationBarColor`, `setNavigationBarContrastEnforced(false)` / `setStatusBarContrastEnforced(false)`
  (API 29+), and `WindowInsetsController.setSystemBarsAppearance(...)` (API 30+) with the legacy
  `SYSTEM_UI_FLAG_LIGHT_*` path below that. Switching Midnight → AMOLED → Light repaints the whole
  window with no cold-start mismatch.
- `UiWindow` lives in its own class **on purpose**: the lab compiles `ThemeEngine` as a *pure* Java
  class (no `android.jar`) to prove the palette maths, and a window call would break that contract.
- `ic_nav_home_filled`, `ic_nav_web_filled`, `ic_nav_troubleshoot_filled` and
  `ic_launcher_monochrome` are each **one** `<path android:fillType="evenOdd">` containing the body
  plus every cutout.

**Proof:** `v340 A2: ic_nav_home_filled is ONE evenOdd path` (counts `android:pathData=` = 1) and
`v340 A3: the monochrome layer is a single-colour silhouette (evenOdd play cutout)`.

---

## 2. Milestone M1 — vectors, icon, fonts, typography

### 2.1 Icons
- **63 `res/drawable/ic_*.xml`** installed from `design/vector/`: 53 outline icons, 4 filled nav
  twins, 3 launcher layers, plus the re-pathed `ic_stat_si.xml` and the new `ic_dot.xml`.
- Grammar: 24 × 24 viewport, 2 dp optical padding, **1.75 dp** stroke, round caps and joins,
  white-on-transparent, tinted at runtime through `UiIcons`.
- `ic_stat_si.xml` re-authored as a flat white silhouette (a notification icon must never carry a
  gradient) — asserted by the lab.
- `ic_dot.xml` replaces the 🟢 / 🟡 / ⚪ emoji trio as a tinted compound drawable.

### 2.2 Launcher icon
- `res/mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_round.xml` → `application-icon` in the APK
  badging is now **`res/mipmap-anydpi-v26/ic_launcher.xml`** (it was a double-masked 192 px PNG).
- Layers: `ic_launcher_background` (108 dp radial obsidian→indigo + violet aura),
  `ic_launcher_foreground` (the **real outline of the letter ස**, extracted from
  `NotoSansSinhala-Regular.ttf` with fontTools — 6.5 KB of path data — plus a gradient play badge),
  `ic_launcher_monochrome` (Material You silhouette with the play triangle knocked out).
- The monogram is the base letter `ස`, not `සි`: the vowel sign `ි` has zero advance and negative
  bearings (it is placed by GPOS mark attachment), so concatenating it without a shaping engine
  would misplace it. In the app's text the platform shaper renders `සි` correctly.

### 2.3 Fonts & typography
- `assets/fonts/NotoSansSinhala-Regular.ttf` (400) + `NotoSansSinhala-SemiBold.ttf` (600), both real
  static instances cut from the variable original, 422,636 B total.
- `UiTokens.si(ctx, weight)` loads them with a cached, crash-proof fallback; `UiTokens.si(ctx, 600)`
  is used for every bold Sinhala, so **faux bold is gone** (`applyLangStyle()` now selects
  `siFontSemi` instead of `Typeface.create(siFont, BOLD)`).
- `UiTokens` implements the full type scale (hero 28 → nav label 10 sp, never below 10), gaps
  4/8/12/16/24, radii 6/8/12/16/28, motion 150/250/400, `tnum` timestamps, and
  `setBilingual(primary, secondary, si, en)` which collapses a row to one line in `si`/`en` mode
  instead of the old `"සිංහල · English"` dot-wall.

### 2.4 Emoji elimination
| File | Emoji lines before | after |
|---|---|---|
| `MainActivity.java` | 154 | **0** (comments excluded) |
| `PlayActivity.java` | 131 | **0** |
| `WebBrowserView.java` | 30 | **0** |

Emoji survive **only** inside log/report text bodies (`Troubleshooter`, `FeatureProbe`, `BridgeApi`,
`Diag`) — exactly as the brief allows. The lab scans all three screens line by line and fails on a
single pictograph outside a comment (including trailing comments, which the first iteration of the
check caught: `// 🔒 swallow everything`).

---

## 3. Milestone M2 — the Mornye shell and 75 options

### 3.1 New primitives (all pure framework, zero dependencies)
| Class | What it does |
|---|---|
| `UiCard` | one card surface: radius / flat-vs-glass / hairline alpha + a press scale that honours the Motion toggle |
| `UiGroup` | grouped settings grammar: overline, 20 dp vector, two-line bilingual row, value pill, chevron, text-indented dividers, 48 dp rows, slider rows, designed empty state |
| `UiSegmented` | 2–4 segment pill with an animated thumb + the 10-accent dot strip |
| `UiChip` | icon + single-language label pill with a selected tint and a scrollable strip |
| `UiSlider` | themed slider with a live readout |
| `UiSheet` | modal sheet: scrim, 36×4 dp drag handle, drag-to-dismiss, `navigationBars()` inset, `elevation-3` |
| `UiWindow` | window + system-bar theming per palette (kept out of `ThemeEngine` so the lab stays pure) |

### 3.2 The Customize hub — five Mornye groups
1. **Appearance** — 4 real theme thumbnails (System / Midnight / AMOLED / Light), 10 accent dots +
   Custom hex, Card radius, Surface (Flat/Glass), Border alpha slider, Density, Bottom bar
   (Docked/Floating), UI language, and switches for labels, motion, accent glow, Browser tab,
   Troubleshoot tab.
2. **Subtitles & style** — a **74 dp live cinema frame** rendering `ශ්‍රී ලංකාවේ චිත්‍රපටිය — සිංහල
   උපසිරැසි` with the user's real font, colour, background, outline and line spacing, then the full
   studio.
3. **Browser** — omnibar Top/Bottom, speed-dial 2/3/4, search engine (DDG/Google/Startpage/Brave),
   ad shield, blocked count, auto-open, desktop UA, reader dark, favicons, custom links.
4. **Player** — aspect, default speed, volume boost, orientation, external player (VLC/MX/mpv/Ask),
   sleep timer, double-tap, auto-PiP, auto-resume, keep-screen-on, gestures.
5. **Cast, network & data** — receiver name/port, DLNA, mDNS, updates, backup, about.

### 3.3 Preferences
- **22 new `K_*` keys**, each with a normalizer, a typed reader and a `normalizeValue` case.
- `BACKUP_KEYS` now covers **73 / 73** declared keys — verified programmatically by the lab, which
  parses `AppPrefs.java`, collects every `public static final String K_`, and asserts each one is in
  the backup block. This is the permanent guard against the v3.3.1 bug where four player switches
  silently fell out of Backup & Restore.
- `AppPrefs.Link` gained a third field (`name|url|group`); a v3.3.1 two-field backup still parses and
  defaults to the streaming group.

### 3.4 Shell
- Unbreakable `LinearLayout(VERTICAL)` shell: `topBar` + `contentHost (0dp, weight 1f)` +
  `bottomBarHost`; `shellInvariantOk()` and `measureShell()` preserved.
- Bottom bar: docked (full-width + hairline) or floating pill (24 dp radius, side margins,
  elevation), Icon+Label or Icons-only, outline ⇄ filled icons, animated active state.
- Top bar keeps 1-tap vector Troubleshoot + Settings.

---

## 4. Milestone M3 — the cinema player

| Added | Detail |
|---|---|
| `ScrubBar` | custom `View`: 3 dp hairline growing to 6 dp while dragging, separate 30 % buffered layer, accent played track, 13 dp knob only while touching, 24 dp vertical touch slop, live drag-preview callback. Replaces the framework `SeekBar`. |
| `CapsuleBar` | 26 × 104 dp Apple-style vertical capsule (shell + fill + vector glyph), driven by the existing brightness/volume swipe gestures, flanking the transport. |
| Player settings sheet | 62 % `UiSheet`: speed slider, aspect segmenteds wired to `VideoViewport.modeFrom`, sleep timer, volume boost, double-tap, keep-on. |
| Subtitles sheet | 66 % `UiSheet`: 18 language chips, Spoken/Formal register, SL sub-site quick-launch, local `.srt`, free translate, title search, **live style studio** (font size / mask / line spacing re-render the subtitle behind the sheet), sync chips. |
| Audio & Quality sheet | track cards + a designed empty state (was an `AlertDialog` that covered the video). |
| Player prefs wired | volume boost via `player.setVolume` (no FFmpeg), sleep timer, default orientation, preferred external player, subtitle hex colour + outline. |
| Play/pause | the transport disc now actually swaps its icon on `onIsPlayingChanged` (it never did before). |

**Preserved untouched:** `StreamHttpConfig` (HTTP-403 header engine + `ResolvingDataSource` cookie
layer + one-shot recovery), `VideoViewport` aspect math, `LkSubFinder` (+ `.zip` capture),
`FreeSubTranslator`, `SubLangDetect`, `Cue.stripMarkup`, and the loopback `/subs/current.srt` VLC
hand-off.

---

## 5. Milestone M4 — the browser

| Added | Detail |
|---|---|
| Bottom omnibar (default) | one widget, placed top or bottom by `K_OMNIBAR_POS`; 52 dp pill; back/forward, lock, clear, reload, shield + blocked badge, overflow. |
| `SpeedDial` | two groups (Streaming / Sri Lankan subtitles), weighted rows at 2/3/4 columns, coloured monogram tiles from a stable host hash, a `+` tile, and a long-press sheet (open / move up / move down / delete). Replaces the clipped horizontal chip row. |
| Light tabs | `List<String>` + `activeTab` + a visible tab strip + `+`. One renderer: multiple live `WebView`s cost 60–120 MB **each** and would OOM a 3 GB phone — documented in the code and in the sheet. |
| Capture dock | `IDLE` / `STREAM` / `SUBTITLE` glass pill, a child of the `FrameLayout` web host (never of the column). |
| Overflow sheet | desktop mode, bookmark, bookmarks, paste, find on page, browser home, clear cache & cookies — never a `PopupMenu` over the address bar. |
| Shield | live blocked-count badge + a blocked-host sheet. |
| Search / UA / reader | `AppPrefs.searchUrl` (DDG/Google/Startpage/Brave), custom UA > desktop UA > default, `color-scheme:dark` injection. |

---

## 6. Verification — every number is from a real run

```
$ bash lab/run_lab.sh
Lab result: ALL 922 PASSED
Lab proof: 922 checks passed (0 failed)

$ python3 lab/verify_layouts.py
verify_layouts: scanned 57 java files, 401 layout calls, 0 problem(s)
verify_layouts: OK — every addView/setLayoutParams/getLayoutParams cast in src/ is type-correct

$ ./build.sh
── version: 3.4.0 (versionCode 30400, previous 30301)
   package: name='lk.techworld.anantplay.sicast' versionCode='30400' versionName='3.4.0'
   application: label='AnantPlay සිංහල Player' icon='res/mipmap-anydpi-v26/ic_launcher.xml'
── done: dist/AnantPlay-Sinhala-Player-v3.4.0.apk
   size        : 3000212 bytes (2929 KiB)
   sha256      : 11d7b5b1a8566a2e14e3c0edb47ffed1b2222fc5d755158a348710d96690e2f1
   Verified using v2 scheme: true / v3 scheme: true
```

| Acceptance criterion | Status | Evidence |
|---|---|---|
| **A1** zero UI-chrome emoji | ✅ | `v340 A1` × 3 files, 0 offending lines |
| **A2** every icon is a VectorDrawable | ✅ | 63 files, each asserted as a 24 dp vector with pathData (launcher layers 108 dp) |
| **A3** adaptive + monochrome icon | ✅ | badging `application-icon-anydpi-v26`; 3 layers asserted |
| **A4** APK ≤ 5.5 MB (target ≤ 5.0) | ✅ | 2,929 KiB |
| **A5** no LayoutParams mismatch | ✅ | `verify_layouts.py` 0 problems |
| **A6** no dangling resource refs | ✅ | `verify_refs.py` OK (inside `run_lab.sh`) |
| **A7** lab green | ✅ | 922 / 922 |
| **A8** every pref survives Backup & Restore | ✅ | 73/73 keys in `BACKUP_KEYS`, import map round-trips |
| **A9** ZWJ / ZWNJ preserved | ✅ | `stripMarkup` byte-identical on ශ්‍රී and ක්‍ෂ |
| **A10** edge-to-edge on a cutout device | ⏳ device | `UiWindow` + per-zone insets; needs the 1-minute device pass below |
| **A11** version stays v3.x | ✅ | 3.4.0 / 30400, `< 40000` |

---

## 7. One-minute real-phone verification checklist

1. **Install** the release APK over v3.3.1 (same key ⇒ in-place update, no uninstall).
2. **Launcher** — the icon is the new squircle with the `ස` monogram + play badge; on Android 13+
   long-press → *Themed icons* shows the monochrome version.
3. **Customize → Appearance** — tap each of the four theme thumbnails: the whole window (status bar,
   nav bar, background) changes with no flash. Tap AMOLED: the canvas is pure `#000000`.
4. **Accent** — tap a dot, then *Custom* and type `#22D3EE`: every chip, tab and icon follows.
5. **Bottom bar** — switch Docked ⇄ Floating and Icons-only: the pill floats with margins; icons stay
   centred.
6. **Subtitles preview** — the 74 dp frame shows `ශ්‍රී ලංකාවේ චිත්‍රපටිය` in the real SemiBold; the
   `ර` in `ශ්‍රී` is joined to the `ශ` (no split) — that is the ZWJ proving itself.
7. **Cast a film** — the player HUD shows the transport with the brightness/volume capsules; the
   scrub bar thickens under the finger; `● සිංහල · N` appears in the top bar.
8. **Subtitles sheet** — drag the font-size slider: the subtitle behind the sheet grows live.
9. **Browser** — the speed dial shows two groups in 4 columns; the omnibar is at the bottom; tap the
   shield to see the blocked hosts; open a `.m3u8` page and the capture dock slides up.
10. **Troubleshoot** — *Run all probes* → 10/10 with vector marks; *Copy report* → no phone model,
    no IP, no MAC.

---

## 8. File-by-file changelog

**New Java (12):** `UiIcons`, `UiTokens`, `UiWindow`, `UiCard`, `UiGroup`, `UiSegmented`, `UiChip`,
`UiSlider`, `UiSheet`, `ScrubBar`, `CapsuleBar`, `SpeedDial`.

**New resources:** 63 `res/drawable/ic_*.xml` (incl. 3 launcher layers), `ic_dot.xml`,
`res/mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml`, `assets/fonts/NotoSansSinhala-SemiBold.ttf`.

**Modified:** `MainActivity.java` (shell, tabs, Customize hub, emoji → vectors), `PlayActivity.java`
(HUD, sheets, scrub bar, capsules, fonts, prefs), `WebBrowserView.java` (omnibar position, speed dial,
tabs, shield, overflow sheet, UA, reader dark), `AppPrefs.java` (22 keys + normalizers + readers +
`Link.group` + `subColorArgb` + `searchUrl`), `ThemeEngine.java` (`hairline`, `cardFill`),
`AppInfo.java`, `AndroidManifest.xml`, `build.sh`, both workflows, `docs/latest.json`, `README.md`,
`SETUP-SI.md`, `lab/ReceiverLab.java` (+149 `v340` checks, version parity retargeted).

**Design deliverables committed:** `DESIGN_AND_UI_OVERHAUL_DEEP_RESEARCH_BLUEPRINT.md`,
`design/figma-tokens.json`, `design/preview.html`, `design/icon-manifest.json`, `design/svg/` (66),
`design/vector/` (62), `design/mipmap-anydpi-v26/` (2).

**Never committed:** no `.apk`, no `.zip`, no keystore (`dist/` and `build/` are gitignored).

---

## 9. Sinhala summary — v3.4.0 අලුත් දේ

1. **සියලුම emoji වෙනුවට සැබෑ vector අයිකන** — පහළ තීරුව, ඉහළ තීරුව, ධාවක HUD, chip, sheet සහ
   බ්‍රව්සර් තීරුව තුළ 63 `VectorDrawable` (24dp, 1.75dp stroke).
2. **නව යෙදුම් අයිකනය** — adaptive (ස්ක්වර්කල්), රවුම් සහ Material You monochrome; `ස` අකුරේ සැබෑ
   outline එකෙන් නිර්මාණය කළා.
3. **සැබෑ Sinhala බර 400 + 600** — faux bold ඉවරයි; ෆොන්ට් එක 1,154 KB සිට 422 KB දක්වා
   (742 KB ඉතිරි). ZWJ/ZWNJ සම්පූර්ණයෙන් ආරක්ෂිතයි.
4. **Mornye-මට්ටමේ Customize** — තේමා 4, වර්ණ 10 + custom hex, glass surface, bottom bar style,
   සජීවී උපසිරැසි preview සහ තවත් (මුළු විකල්ප 75; සියල්ලම Backup එකේ ඇතුළත්).
5. **නව ධාවක HUD** — 3-zone glass HUD, 3 bottom sheet (Subtitles / Audio & Quality / Player
   settings), නව scrub bar සහ brightness/volume capsule.
6. **නව බ්‍රව්සරය** — speed dial (2/3/4 තීරු, කිසිදා කැපෙන්නේ නැත), පහළ omnibar, tabs,
   ad-block shield ගණන සමඟ, capture dock.
7. **කිසිදු පැරණි එන්ජිමක් බිඳී නැත** — 403 fix, aspect math, SL subtitle finder, updater,
   privacy scrubbing — සියල්ල එලෙසම; lab 922/922 PASS.
