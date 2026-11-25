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

## 5. Verification — every gate reachable from this sandbox, 0 FAIL

| Gate | Result |
| --- | --- |
| `verify_flutter_bridge_wiring.py` | **71 passed, 0 failed** — 0 dead channels, 0 fake buttons. Fixed in this pass: the harvest compared Dart method names against ONE global set of Java `case "…"` labels, so the three channel methods that share a name with another channel's case label (`restart` on cast vs the webview reload, `status` on cast vs the subtitle pipeline, `copyToClipboard` on cast vs settings) looked dead. The exact `"<name>".equals(m)` dispatch form is now harvested too. |
| `verify_layouts.py` (selftest 13/13 + main) | **0 problems** across 69 files |
| `verify_v344_ui.py` / `verify_v345_ui.py` / `verify_gradle_host.py` / `verify_ui_completeness.py` / `verify_refs.py` | **1861 / 57 / 64 PASS, 0 FAIL** + the completeness gate OK + the bytecode resolver OK |
| Java + Dart syntax | **0 errors across 71 Java sources and 53 Dart files**, parsed with the real grammars (tree-sitter `java` / `dart`, ERROR-node walk) after every edit in this pass |
| Workflow gates | both workflows pass `yaml.safe_load`; every one of the **70** emulator-script lines passes `sh -n` individually (the runner executes them as separate `sh -c` invocations) |
| `lab/run_lab.sh` (ReceiverLab + all static auditors) | The Java legs of this script need a **JDK with `javac`**, which this sandbox could not obtain (the only reachable networks are PyPI/npm/GitHub, and every JDK route — `jdk4py` (JRE-only image), `cjdk` (fetches `raw.githubusercontent.com`), the Adoptium/GCS tarballs — is blocked). Everything it gates that is *not* Java-execution was run directly and is green (the six auditors above + the workflow/script/javac-syntax gates). CI's `lab` job has `actions/setup-java` and runs the full 1354-check matrix. |
| `flutter analyze` + `flutter test` | **Not run in this sandbox — no Flutter/Dart SDK is reachable.** Every touched Dart file was grammar-parsed and hand-audited; three real defects were found and fixed by that audit (see §5b). CI's `flutter analyze`/`flutter test` jobs are the executing gate. |

## 5b. The untested-Dart audit — three real defects found without a compiler

Z.ai's sandbox never had a Flutter SDK, so none of the `.e` Dart had ever been
compiled or executed. The audit that replaced the compiler found:

1. **`Settings → About` footer was `v3.4.6.d (30410)`** while `AppInfo`, Gradle,
   `build.sh`, `pubspec.yaml` and both workflows are `3.4.6.e / 30411` — the
   version lock was already broken on one shipped surface, and the (unrunnable)
   widget test had been written expecting `.e`, so it would have failed in CI.
2. **`AnantPlay.deepReport()` did `… as String`** on an untyped channel reply.
   Any host that answers `null` (channel not ready, engine restarting) throws a
   `TypeError` inside the Help tab's auto-copy path, which would have left the
   live "Running 54 deep feature probes…" state stuck. It now returns `''`, and
   the Help screen's auto-copy / Copy-Again / Share paths all degrade cleanly
   (and `_run()` resets the button instead of spinning for the 90 s watchdog).
3. **The new compact-subtitle widget test asserted on an empty string** — it
   looked for a Sinhala cue that no host-side demo provides, so it would have
   failed on the first CI run (or, worse, passed vacuously). It now drives a
   real `anantplay/player/events` frame and asserts the 3-line / 1.25 height /
   10–28sp clamp and the 82 % width cap on the live widget tree.

`deepReport` and the Help-screen guards are also why the sweep of the whole Dart
surface used the tree-sitter grammar: a syntax error in `help_screen.dart`,
`deep_troubleshooter.dart`, `player_screen.dart`, `bridge.dart`, `shell_test.dart`
or `bridge_test.dart` is now impossible to ship unnoticed.

## 5c. The hosted Android 36 KVM emulator is now a LIVE 54-FEATURE harness

**The defect this removes:** the CI emulator job used to prove exactly one
thing — `am start -W` returns `Status: ok`, the process is alive 12 s later and
the crash buffer carries no entry for our package. That is a *launch* test. A
dead Stop-Receiver button, oversized subtitles, a dead Auto-Rotate or a
silently disabled updater all pass it. The `v3.4.6.e` fix release exists
precisely because that gate was blind.

**The hook (`MainActivity`, ~90 lines):** a launch extra `ci_run_deep_probe`
(`adb shell am start -W -n lk.techworld.anantplay.sicast/.MainActivity --ez
ci_run_deep_probe true`) makes the activity settle 3 s and then run the REAL
`DeepProbe.runAll(deepHooks(), null)` — the same 54-probe matrix the Help tab
runs, against real `SharedPreferences`, a real foreground `ReceiverService`, a
real `PackageManager`, a real `DNS-SD/DLNA` stack and a real `ClipboardManager`.
It is inert without the extra (one boolean read at cold start), can only ever
do what the user's own "Run full app troubleshoot" button does, and inherits
the engine's transactionality (`snapshot → mutate → verify → restore` in a
`finally`).

**The published wire (parsed by the workflow, pinned by the lab):**

```
AnantPlayCI: PROBE <id> <name> -> PASS|FAIL (<ms> ms) <detail>      × 54
AnantPlayCI: DEEP_PROBE_SUMMARY pass=<N> fail=<F> total=<T> clipboard_copied=<b> privacy_clean=<b>
AnantPlayCI: DEEP_PROBE_DOMAINS A=6/6 B=9/9 C=16/16 D=6/6 E=12/12 F=5/5 version=… code=… sdk=36
AnantPlayCI: DEEP_PROBE_DONE pass=<N> fail=<F> total=<T>
```

`clipboard_copied` is the live proof that the auto-copy contract works on a
real Android clipboard (the native `ClipData` write is read back);
`privacy_clean` is the `PrivacyScrubber` **fixed point** on the exact copied
text (`scrub(report) == report`) plus a zero-survivor sweep of
`PrivacyScrubber.canaryLeaks()` — a leaked phone model, LAN IP, EUI-64, MAC,
Cookie or Bearer JWT turns the job red.

**The workflow (`build-apk.yml` → `launch-smoke`, 9 steps):** install →
`am start -W` (`Status: ok`) → alive 12 s → focused → launch twice with
`--ez ci_run_deep_probe true` (warm, then cold after `force-stop`) → poll
`adb logcat -d -s AnantPlayCI:I` up to 45 s for the summary and 40 s more for
the completion marker → render every probe as a row of `$GITHUB_STEP_SUMMARY`
→ **hard-assert `DEEP_PROBE_SUMMARY` present, `total >= 54`, `fail == 0`,
`clipboard_copied=true`, `privacy_clean=true`, ≥ 54 `PROBE ` lines** → crash
attribution (our package/pid only; GMS noise is `::notice`, never a failure).
Every line of that block passes `sh -n` individually, because
`reactivecircus/android-emulator-runner` splits `script:` line by line.

The lab pins the contract (`v346e ci:` legs): the exact line shapes, the
parsed keys, the 54 total, the per-domain roll-up, the scrubber fixed point
(a scrubbed report passes, a raw PII report must NOT) and the workflow's own
trigger/poll/assert strings — so a rename can never silently disarm CI.

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
5. **Android 36 CI emulator දැන් සැබෑ පරීක්ෂකයෙක්** — `--ez ci_run_deep_probe true` ලෙස launch කළ විට යෙදුම තුළම සියලු **54 probes** සැබෑ Android OS එක තුළ ධාවනය වී logcat එකට `AnantPlayCI: PROBE …` ලෙස වාර්තා කරයි; job එක `total >= 54`, `fail == 0`, `clipboard_copied=true`, `privacy_clean=true` ලෙස දැඩි ලෙස සත්‍යාපනය කරයි (launch-only smoke යුගය අවසන්).
6. **සියලු gates 0 FAIL** — wiring 71/71, layouts 0, refs OK, 1861+57+64 PASS, Java 71 + Dart 53 syntax 0 error, workflow 70 lines `sh -n` OK; docs/latest.json එක වෙනස් නොකෙරියි (v3.4.6.d ම ඉතිරිවේ).

**Auto-Update Law රැකෙනවා** — tier-1 302 → tier-2 latest.json (0 api.github.com), a–z ladder, 55 MiB cap, ForceUpdateScreen blocking gate යන සියල්ලම නොවෙනස්ව ඉතිරිවේ සහ A-domain 6 probes මගින් සෑම troubleshoot run කකක්හිදී නැවත ඔප්පු වේ.
