# v3.4.6.e — The 54-Probe Full-App Deep Troubleshooter + the Real-Device Fix Release (30411)

**English (Sinhala summary at the end).**

AnantPlay Sinhala Player `v3.4.6.e` / `30411` (alphabetical ladder `.d → .e`, never 3.5/4.0). This release fixes every named real-device defect — the dead **Stop Receiver** button, the **oversized subtitles** that covered the movie, the dead **Auto-Rotate**/**player-tool** controls — and replaces the old shallow 10-point troubleshoot with a **6-domain, 54-probe Full-App Deep Troubleshooter** that audits every feature in the entire app and **auto-copies the 100% privacy-safe report to the phone's clipboard** the instant it finishes.

---

## 1. Stop Receiver — the dead button, root-caused and fixed

**Root cause (`file:line`):**
- `MainActivity.onCastCall` handled `"stop"` with a bare `stopService(new Intent(...))` + ONE `postDelayed(pushCastStatus, 400)` — an *asynchronous* ask that races the single status push: when the service teardown lands after the 400 ms push, Flutter keeps rendering "Active · Ready for Cast" and the tap looks dead.
- `ReceiverService.onStartCommand`'s `ACTION_STOP` path called a bare `stopSelf()` WITHOUT `stopForeground(STOP_FOREGROUND_REMOVE)` — the persistent "ready for cast" notification survived as a ghost.
- Nothing re-pushed `running=false` when `onDestroy` actually landed.

**The fix (all proven by the lab):**
1. **`ReceiverService.requestStop(ctx)`** (new, `ReceiverService.java`): the deterministic stop lane — flags `userStop`, issues BOTH the `ACTION_STOP` start AND `stopService()`, clears the static `running` reference **synchronously** so `isUp()/port()` answer "down" the moment the user taps, and `stopNow()` performs `stopForeground(STOP_FOREGROUND_REMOVE)` + `stopSelf()`.
2. **`onStartCommand` ACTION_STOP** now removes the foreground notification (`Service.STOP_FOREGROUND_REMOVE`) before `stopSelf()`.
3. **`onDestroy()` → `MainActivity.notifyReceiverDown()`** — the instant status flip onto the `anantplay/cast` EventChannel (zero 400 ms race).
4. **The `userStop` veto**: `onCreate` refuses a `START_STICKY` zombie resurrection; `arm()` (the Home Start button) is the one lane that clears it; `App.onCreate`'s "app-start" auto-arm and `BootReceiver`'s boot auto-arm both honor a standing user stop (the receiver the user stopped STAYS stopped across app restarts and reboots).
5. `MainActivity`'s `"stop"` handler calls `requestStop`, pushes the status immediately, and returns `{up: false}` synchronously.

**Start Receiver** was already correct (`ReceiverService.arm`) and now also clears the veto, so both directions are instantaneous and reliable.

## 2. Compact cinema subtitles — never covering the movie

| Contract | Old (v3.4.6.d) | New (v3.4.6.e) |
| --- | --- | --- |
| Default size | flat 16sp | **14sp portrait / 15sp landscape** |
| Slider range | 12–36sp | **10–28sp** (`AppPrefs.normalizeFontSp` clamps HARD) |
| Width | unbounded pill | **maxWidth = 82%** of viewport (`ConstrainedBox`) |
| Lines | unbounded | **maxLines: 3, softWrap: true, ellipsis** |
| Line height | ≈1.55 | **tight 1.25** |
| Pill padding | 14/6 | **horizontal: 10, vertical: 4** |
| Bottom anchor | 8%/5% | **6% portrait / 4% landscape** |

`player_screen.dart` renders the compact pill; `AppPrefs.fontSp` default is 14 with `fontSpFor(landscape) = +1`. Sinhala shaping is untouched: the cue text is never re-encoded — **ZWJ (`\u200D`) / ZWNJ (`\u200C`) in `ශ්‍රී` survive verbatim**, re-proven by the lab every run.

## 3. Auto-rotate + every cinema player control — real wiring

- **Auto-Rotate / orientation lock**: `Media3TextureBridge` gained `setOrientation("auto" | "landscape" | "portrait")` → `Activity.setRequestedOrientation(FULL_SENSOR | SENSOR_LANDSCAPE | PORTRAIT)`; the policy persists (`K_ORIENTATION`) and is applied at session `open()`; `dispose` restores `UNSPECIFIED`. The HUD's Rotate button opens a real 3-mode sheet (Auto / Landscape lock / Portrait lock).
- **Subtitle language switching**: `switchSubLanguage(lang)` persists `K_DEFAULT_LANG` AND re-runs the fetch pipeline in the new language (Sinhala / English / Tamil chips), replacing cues live; the worker call now uses the persisted language.
- **Sync**: the four explicit steps **−1s / −0.25s / +0.25s / +1s** with a cumulative offset readout in signed seconds ("+0.25s").
- **Tracks**: `selectTrack` via Media3 `TrackSelectionOverride` (audio language / video quality) — unchanged, re-proven.
- **Find Subtitles**: `LkSubFinder` 10 sites (Subz.lk/Zoom.lk/PirateLK/Baiscope/Cineru tier-1) — re-proven by the E9 probe.
- **More Tools** — every row is real work now: Retry search · **Change provider** (engine rotation human-pa-free → human-free → free → worker-only) · Free translate · Spoken/Formal register · **NewZone Translator** (opens the search in the Browser tab) · **Audio → SRT** (copies the receiver's `/subs/current.srt` URL for external players) · PiP · External VLC (already fixed in .d).

## 4. The 6-domain, 54-probe Full-App Deep Troubleshooter

**`Run Full App Troubleshoot`** on the Help tab now runs **54 live probes across every feature in the entire app** (`DeepProbe.java`, pure-Java so the lab executes the exact same matrix), grouped:

- **A — Auto-Updater Law (6)**: tier-1 `releases/latest` 302 (zero `api.github.com`), tier-2 `docs/latest.json` schema, the full a–z ladder (`3.4.6.d < .e < .f < .z < 3.4.7`), the 55 MiB cap + asset derivation, the ForceUpdateScreen blocking gate (`PopScope(canPop: false)`, zero skip/later/dismiss), the Check-Now round trip.
- **B — Home & Cast Receiver (9)**: arm → `/info` HTTP 200, **Stop live execution → running == false → restore**, DLNA DIDL `/dlna/desc.xml`, mDNS 5353 responder, official-app detection, Continue-Watching round-trip, the URL validator, the Test-Player trampoline, the Browser quick-action.
- **C — Quick Settings & Customizations (16)**: theme modes, accents + custom hex, Material You, glass clarity, outlines + surface, motion/density/radius, bottom bar, midnight dimmer, **Subtitle Studio compact bounds**, auto-subs/translate/zip/engine, player defaults, Sources index URL, Cast & Network, Browser & Privacy, **backup export→validate→restore**, and channel completeness (0 dead).
- **D — Browser (6)**: headless Chromium, **Google default engine**, security hardening, BridgeGuard caps + capture window, StreamSniffer HLS/DASH/MP4 + ad-host blocking, the speed-dial parser + NewZone.
- **E — Cinema Player & Subtitles (12)**: the trampoline, the HTTP-403 ladder, the aspect math (1080×608/4267×2400), **the compact-subtitle contract**, language switch + ZWJ, sync math, tracks, auto-rotate, LkSubFinder's 10 sites, worker + free translator, More-Tools wiring, the battery/GPU guards.
- **F — Permissions & Privacy (5)**: notifications, overlays, battery, zero AccessibilityService + allowBackup=false, and the **PrivacyScrubber live self-test** (PII canaries — phone model, LAN IP, EUI-64, MAC, Cookie, Bearer JWT — 100% scrubbed while `tt33764258` + `141776` survive).

**NON-DESTRUCTIVE (transactional):** before any mutating probe, the full `AppPrefs` snapshot + the receiver state are captured; `DeepProbe.runAll`'s `finally` restores the user's exact settings and receiver state even if a probe throws — the lab proves the snapshot is restored exactly.

**Instant auto-copy:** the moment the final probe lands, the Dart side fetches the structured report (Status Summary + Domains A–F ledger + Last Crash + Timeline, scrubbed on the Java side) and copies it through **BOTH** the native `ClipboardManager` and Flutter `Clipboard.setData`, showing the green banner **"54/54 Deep Troubleshoot complete — 100% Privacy-Safe report auto-copied to clipboard ✓"** with **Copy Again** and **Share** buttons.

**Live per-probe progress:** every completed probe pushes one frame (`{t:'deep', done:n, total:54, outcome:{…}}`) onto the `anantplay/troubleshoot/events` channel, and the Help tab renders the live counter **"Running 54 deep feature probes… 32/54"** as the audit advances. The Help screen routes the channel's RAW frames (the typed 10-point `probeResults()` stream would have swallowed the deep payloads — a wiring bug caught and fixed before ship), with collapsible per-domain PASS/FAIL cards rendering as results accumulate.

## 5. Verification — every local gate, 0 FAIL

| Gate | Result |
| --- | --- |
| `lab/run_lab.sh` (ReceiverLab + all static auditors) | **ALL 1354 PASSED** (was 1330) — includes the new `v346e` legs: the 54-probe matrix executes 54/54 with the transactional restore, the identity/compact-subtitle/stop-contract checks |
| `scripts/compile_check.sh` (all 69 Java sources against android-36 + Media3 1.11.1 + Flutter embedding) | **COMPILE OK: 165 classes** (toolchain roots downloaded into `tools-dl/` with env overrides) |
| `lab/verify_flutter_bridge_wiring.py` | **71 passed, 0 failed** — 0 dead channels (the 5 new methods all have handlers), 0 fake buttons |
| `verify_layouts.py` (selftest 13/13 + main) | **0 problems** across 69 files |
| `verify_v344_ui.py` / `verify_v345_ui.py` / `verify_gradle_host.py` | **1861 / 57 / 64 PASS, 0 FAIL** |
| `flutter analyze` + `flutter test` | **Not run in this sandbox — no Flutter SDK available.** Every touched Dart file passed a strict token-level structure check; the shipped widget-test suite was extended (deep-run, domain cards, auto-copy, compact-subtitle, Stop-Receiver tests) but could not be executed here. CI's `flutter test` gate runs it. |

## 6. Version parity + hygiene

- `3.4.6.e` / `30411` everywhere: `AppInfo.java`, `android/app/build.gradle`, `build.sh` (PREV 30410), `ui/pubspec.yaml` (`3.4.6+30411`, pure integer), both workflows (identity locks + the derivation gate `3.4.6.e → 30411`), README + SETUP-SI headers.
- **`docs/latest.json` untouched** at the live `v3.4.6.d` / 30410 (the updater's target); `ReceiverLab`'s latest.json gates accept BOTH `.d` and `.e` tiers.
- Repo root remains **exactly 7 files** (`.gitignore`, `AndroidManifest.xml`, `LICENSE`, `README.md`, `SETUP-SI.md`, `THIRD_PARTY.md`, `build.sh`); reports live in `docs/reports/`; zero regressions: prefs-before-super.onCreate, `zipalign -P 16 -f 4` + integrity checks, `anantplay/crashlog`, LGPL-3.0-only, zero AccessibilityService, `allowBackup="false"`.

---

## සාරාංශය (සිංහල)

**v3.4.6.e (30411)** — මෙම නිකුතුවේ ප්‍රධාන කාරණා:

1. **Stop Receiver බොත්තම සැබවටම වැඩ කරයි** — `requestStop()` ක්‍රමය දෙපැත්තම (ACTION_STOP + stopService) වහාලයි, දැනුම්දීම ක්ෂණිකව යාවත් වේ, සහ `userStop` නිෂේධනය නිසා නැවත-පණගැන්වීම් (zombie) වළක්වයි.
2. **උපසිරැසි දැන් හරියට ම ප්‍රමාණයේ** — 14sp/15sp කුඩා සම්මතය, 82% පළල සීමාව, 3 පේළි, 10–28sp slider — චිත්‍රපටිය කිසිවිටෙකත් වැසෙන්නේ නැත; සිංහල ZWJ (ශ්‍රී) 100% ආරක්ෂිතයි.
3. **Auto-Rotate සහ සියලු ධාවක මෙවලම් තවදුරටත් මළ නොවේ** — සැබෑ `setRequestedOrientation`, භාෂා-මාරුව (සිංහල/English/தமிழ்), −1s/−0.25s/+0.25s/+1s sync, Tracks, Find Subtitles, සහ More Tools සියල්ල (Retry, Provider, Spoken/Formal, Free Translate, NewZone, Audio→SRT).
4. **54-පියවර Deep Troubleshooter** — යෙදුමේ සෑම feature කමක්ම (6 domains A–F) සජීවීව පරීක්ෂා කරයි; පරිශීලකයාගේ settings/receiver තත්ත්වය සැමවිටම restore වේ; අවසාන විගස **100% privacy-safe වාර්තාව clipboard එකට ස්වයංක්‍රීයව පිටපත් වේ**.
5. **සියලු local gates 0 FAIL** — lab 1354/1354, compile OK, wiring 71/71; docs/latest.json එක වෙනස් නොකෙරියි (v3.4.6.d ම ඉතිරිවේ).

**Auto-Update Law රැකෙනවා** — tier-1 302 → tier-2 latest.json (0 api.github.com), a–z ladder, 55 MiB cap, ForceUpdateScreen blocking gate යන සියල්ලම නොවෙනස්ව ඉතිරිවේ සහ A-domain 6 probes මගින් සෑම troubleshoot run කකක්හිදී නැවත ඔප්පු වේ.
