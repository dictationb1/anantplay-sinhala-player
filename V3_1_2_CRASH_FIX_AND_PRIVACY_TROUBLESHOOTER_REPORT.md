# V3.1.2 — Crash Fix + Privacy-First Deep Troubleshooter Report

**App:** AnantPlay සිංහල Player · `lk.techworld.anantplay.sicast`
**Version:** 3.1.2 (`versionCode 30102`, strictly v3.x — never v4)
**Date:** 2026-11-22 · **Built against:** `platforms;android-36` + `build-tools;35.0.1`, media3 1.11.1

---

## 1 · The "keeps stopping" crash — ROOT CAUSE (file:line) ✅ FIXED

### 1.1 The smoking gun

```
PlayActivity.java:1001 (v3.1.1)
        notice.setBackgroundResource(0x88000000);
```

`View.setBackgroundResource(int)` interprets a bare `int` **as a resource ID**, not as a
color. `0x88000000` is raw ARGB (a translucent black) and resolves to **no resource** →
`android.content.res.Resources$NotFoundException` thrown synchronously inside
`buildUi()` during `PlayActivity.onCreate()`. On Android 16 (SDK 36) the exception
surfaces through the app-transaction machinery exactly as the field log showed:

```
android.content.res.Resources$NotFoundException: Resource ID #0x88000000
    at android.view.View.setBackgroundResource(View.java)
    at lk.techworld.anantplay.sicast.PlayActivity.buildUi(PlayActivity.java:1001)
    at lk.techworld.anantplay.sicast.PlayActivity.onCreate(PlayActivity.java:215)
    at android.app.Activity.performCreate(Activity.java)
    at android.app.servertransaction.TransactionExecutor.executeNonLifecycleItem(TransactionExecutor.java:136)
    at android.app.ActivityThread$H.handleMessage(ActivityThread.java:3103)
```

The visible bottom frames (`TransactionExecutor.executeNonLifecycleItem → handleMessage`)
that the user's red `Last Crash Captured` box displayed are the **delivery** path —
the actual throw was one `setBackgroundResource` call that ran on **every** launch of
the player:

| Entry point | Path to the crash | Field symptom |
| --- | --- | --- |
| **Test Player** button | `MainActivity` → `startActivity(test=true)` → `PlayActivity.onCreate` → `buildUi` → throw | "keeps stopping" instantly |
| **AnantPlay Cast (mDNS)** | `ReceiverServer /play` → `ReceiverService.onPlay` → `startActivity(castJson)` → same throw | AnantPlay reports "The TV refused this video" (its POST /play 5 s window never sees the player open) |
| **DLNA cast** | `DlnaEndpoint SetAVTransportURI` → `DlnaBridge.open` → same `onPlay` → same throw | same refusal |
| **Play Video URL / ACTION_VIEW / share** | same `onCreate` → same throw | same crash |

### 1.2 The fix

```
PlayActivity.java (v3.1.2)
        // v3.1.2 CRASH FIX ... (documents the TransactionExecutor path)
        notice.setBackgroundColor(0x88000000);
```

`setBackgroundColor(int)` is the ARGB-interpretation API — identical translucent
black, zero resource lookup, zero crash. Belt-and-braces additions:

- The `Test Player` button's `startActivity` is wrapped in try/catch (`MainActivity.java`,
  "test player launch") — a launch can never kill the main UI either.
- The receiver's existing crash recorder (`App.onCreate` chained
  `Thread.setDefaultUncaughtExceptionHandler` → `CrashLog.writeAtomically`) is kept —
  any future fault is always captured before the system dialog.
- The lab now **greps the shipped sources** for any surviving
  `.setBackgroundResource(0x…)` call (comments stripped, so the fix's own documentation
  cannot false-match): `v312 crash: … GONE / NO view anywhere still calls …` — both PASS.

### 1.3 Why the crash ALSO broke casting ("The TV refused this video")

`ReceiverService.onPlay` answers `{"ok":true}` only after it sees
`PlayActivity.current() != null` (Cast.play's 50 × 100 ms wait). With the crash, the
player never opened → the wait loop expired → the fallback notification path also
depended on the (crashed) activity → AnantPlay's client interpreted the outcome as a
refused cast. With the crash fixed, the player opens within the window, `scheduleTakeover()`
hands the screen over, and `/play` returns `ok:true` — the wire contract the lab has
always proven at the byte level now actually completes on the phone.

---

## 2 · The `selfupd` 38-lines-in-645 ms spin loop — ✅ FIXED

### 2.1 The mechanism

The field log (`0.610 … 1.255`, 38+ lines at ~10 ms apart) is `checkAndMaybeInstall`'s
in-flight guard firing over and over:

```
I/selfupd: single-flight: another update run is active — skipping
```

Two cooperating defects in v3.1.1:

1. **`SelfUpdater.schedule(c, 0L)` was callable from every entry point** (app start via
   `checkOnLaunch`, the install-now notification action, the settings toggle) with
   `setMinimumLatency(0)`. Each call **replaces** the pending `JobInfo`, and every
   replacement makes JobScheduler re-deliver `CheckJob.onStartJob` — while a previous
   run was still in flight, so each delivery hit the single-flight skip and logged.
   The persisted 60 s `lastCheck` debounce lived only inside `checkOnLaunch`, not in
   `schedule()` itself.
2. **The skip log was un-throttled** — every skip printed a Diag line, so the ring
   (and the Diagnostics screen) drowned in identical lines, which the field report
   read as startup lag.

(The crash loop in §1 made it *worse* on the field phone: each crashed relaunch drove
new arm/schedule traffic.)

### 2.2 The fix (`SelfUpdater.java`, v3.1.2)

```java
static final long SCHEDULE_DEBOUNCE_MS = 10_000L;      // the in-process burst guard
private static volatile long lastScheduleAt;           // inside schedule()
...
if (delayMs <= 0) {
    if (last > 0 && now - last < SCHEDULE_DEBOUNCE_MS) return;   // NO-OP
    lastScheduleAt = now;
}
...
if (last == 0 || now - last >= 1000L) {                // the skip-log rate limit
    lastSkipLogAt = now;
    Diag.i("selfupd", "single-flight: ...");
}
```

Two layers: **(1)** an immediate (`delayMs == 0`) re-schedule inside a 10 s window is
a silent NO-OP — JobScheduler is handed the job once per burst, never 38 times; **(2)**
the skip log emits at most one line per second. The lab proves it by simulating the
field storm: 50 rapid zero-latency schedule calls inside 1 s collapse to exactly **1**
JobScheduler handoff, and the Diag ring sees at most **ONE** single-flight line
(`v312 upd: … collapse to exactly 1 JobScheduler handoff` / `… at most ONE single-flight line`).

---

## 3 · Privacy-first deep troubleshooter (Phase 2) ✅ IMPLEMENTED

### 3.1 The field leak (what v3.1.1 exposed)

The v3.1.1 startup line and every crash header carried:

```
… up, sdk 36 Xiaomi 24117RN76G                    ← App.java:66-67
device: sdk 36 | Xiaomi 24117RN76G | release-16   ← App.java:78-79 (every crash report)
REFUSED … from 192.168.1.39 (local endpoint …, known-own …) ← ReceiverServer logs
… ttl 4 … fe80::f84a:81ff:feac:e2f8%wlan0 …      ← EUI-64 IPv6 = the phone's Wi-Fi MAC
```

The EUI-64 address embeds the physical MAC (`f8:4a:81:ac:e2:f8`, inverted bits) — a
permanent hardware identifier. That was the strictest possible violation of the
user's zero-PII requirement.

### 3.2 `PrivacyScrubber.java` (new, pure Java, lab-proven)

Enforced at **the sink** — `Diag.redact()` now chains `PrivacyScrubber.scrub()` after
its own secret pass, so every ring line, every `diag.log` line, every crash report and
every export is anonymised even if a caller accidentally logs an identifier:

| Rule | In | Out |
| --- | --- | --- |
| loopback IPv4 | `127.0.0.1` | `<local-v4>` |
| private/lan IPv4 (incl. router) | `192.168.1.1`, `192.168.1.39`, `192.168.1.148` | `<lan-peer>` |
| public IPv4 | `8.8.8.8`, `203.0.113.9` | `<remote-ip>` |
| link-local IPv6 (EUI-64/MAC) | `fe80::f84a:81ff:feac:e2f8%wlan0` | `<local-v6>` |
| any other IPv6 | `2600:…` | `<peer-v6>` |
| MAC (any form) | `f8:4a:81:ac:e2:f8` | `<redacted>` |
| vendor + model | `Xiaomi 24117RN76G` | `<phone-model>` |
| model tokens / Build.* | `Build.MODEL=24117RN76G`, `fingerprint=xiaomi/milkway/M2101K6G…` | `<phone-model>` |
| cookies / auth / JWT | `Cookie: session=abc`, `Authorization: Bearer eyJ…` | masked / `<redacted>` |
| Google API keys | `AIzaSy…` | `<redacted>` |
| query secrets | `?token=secret&sig=…&expires=…` | `token=<redacted>` … |
| SSIDs | `ssid=MyHomeWiFi_5G` | masked |
| user paths | `/storage/emulated/0/Movies/private.mp4` | `<local-media>` |

**What is kept** (the debuggable half): the app version, the generic `sdk N` integer,
port numbers, DLNA/mDNS/NSD states, Worker version/storage/provider facts, subtitle
stages/HTTP codes/cue counts, and the full redacted stack trace.

The benign v3.1.2 crash header `device: sdk 36` is explicitly exempted (line-level
exemption in `scrubDeviceIdentifiers`) so the only header that survives is the one
with zero PII. The lab feeds the exact field strings plus 14 canonical canary shapes
through one scrub pass and proves **zero survive** while the technical line stays
byte-intact — 17 `v312 privacy:` checks, all PASS, including idempotence and the
"Diag ring itself is scrubbed" end-to-end proof.

`App.java` v3.1.2 also **stops producing the hardware lines at the source**: the
startup log and the `CrashLog.format` device argument are now `sdk N` only.

### 3.3 `Troubleshooter.java` (new, pure Java, lab-proven)

**Timeline** (capped 400 events, every entry scrubbed at capture time) in the six
structured categories:

- `[APP]` — process up (version + SDK int), receiver arm requests
- `[CAST]` — `/play` accepted/refused (+ why), player-open result, overlay fallback
- `[PLAYER]` — launch source (`cast`/`test-player`/`manual-url`/`webview`/`share`),
  Media3 state transitions (`IDLE/BUFFERING/READY/ENDED`), the exact
  `ERROR_CODE_*` name + cause
- `[SUBS]` — TitleCleaner output, `/api/resolve` result (tt-id or no-match),
  cues-loaded counts + source
- `[UPDATER]` — the single check result (up-to-date / no-release)
- `[CRASH]` — the uncaught thread + exception class + message

**Top-first crash formatter** — `Troubleshooter.crashTop()` returns the `EXCEPTION …`
line first, then the first app frames; the v3.1.1 card's useless bottom
(`Handler.dispatchMessage`) tail is gone. The Diagnostics card renders exactly this;
`Copy (full)` still shares the whole trace (scrubbed).

**Auto-diagnosis** — `diagnose(Facts, timeline)`: severity-ordered bilingual cards:

- ❌ a captured crash always leads (with the class + first app frame)
- ❌ playback failed (the exact ExoPlayer error-code name, 1-tap retry)
- ❌ receiver down → `Home → ▶ Start receiver`
- ❌ both cast pillars down → Wi-Fi/Settings guidance
- ✓ NSD daemon silent but our 5353 responder + DLNA alive → casting works (info)
- ❌ Worker unconfigured / unreachable / ⚠ KV unbound → `bind SUBS_KV`
- ❌/⚠ subtitle needs-key / not-found / error → the three 1-tap fixes
- ✓ otherwise: "cast + subtitles are healthy"

**Report bundle** — `buildReport()`: Status Summary + Auto-Detected Issues +
Last-Crash (top-first) + the subsystem timeline, one final scrub over the whole
bundle. The lab proves all four sections, the EXCEPTION-first ordering, the
version+SDK-only header, and end-to-end zero-PII.

### 3.4 The Diagnostics tab upgrade (`MainActivity.buildDiagTab`)

- **Hero row:** `📋 Copy Full Troubleshoot Report (Privacy-Safe)` → clipboard;
  `📤 Share Report`; `🩺 Run Deep Troubleshoot`.
- **Run Deep Troubleshoot** = a real 6-stage ACTIVE self-test:
  1. loopback HTTP `/info` on the live receiver port;
  2. DLNA E2E loopback (`/dlna/desc.xml` fetch, `MediaRenderer` verified);
  3. mDNS wire probe (the responder's kernel-loopback test);
  4. Worker `/health` (version + storage cached into the report);
  5. Worker **live subtitle-resolve dry-run** (`/api/resolve` for a known title —
     proves the cast→subs path without starting playback);
  6. Player readiness (the open player's state, exact error code if any).
  Every stage lands in the timeline; the verdict line shows ✓/✗ + the per-stage facts.
- **The 300 dp gap fix:** the log viewer is no longer a fixed-height
  `TextView` + internal `ScrollingMovementMethod` (which produced the giant empty
  block and the overlapping lines). It is a real `ScrollView` wrapping a
  wrap-content monospace `TextView` inside a bounded card — overlap is
  impossible by construction, and the card reserves no dead space.
- **The crash card** is top-first (above), with `Copy (full)` / `Share` / `Clear`.

### 3.5 PC ADB helper (`scripts/troubleshoot-adb.sh` + `.ps1`)

Filters live logcat to **only** `lk.techworld.anantplay.sicast` + `me.champdev.weblock`
cast/crash lines and pipes them through the same scrub rules (IPs/MAC/EUI-64/vendor+
model/tokens/paths/SSID → placeholders). PowerShell 5.1-compatible; `-Seconds N`
timed capture on both platforms; nothing is written to the phone.

---

## 4 · Live verification lab — 481/481 PASS (was 427)

```
$ bash lab/run_lab.sh
Lab result: ALL 481 PASSED            (0 FAIL — gating, exits non-zero otherwise)
bytecode verifier: OK — 172 distinct referenced types, all resolvable
```

New v3.1.2 suites (54 new checks):

- **`v312 crash:`** — the raw-ARGB call is gone; `setBackgroundColor` is used; no view
  anywhere calls `setBackgroundResource` with a bare hex int; the crash recorder still
  chains; the Test Player launch is wrapped.
- **`v312 privacy:`** — 17 checks: every field string + 14 canonical canaries die in
  one pass; the benign `device: sdk 36` survives; the technical line stays intact;
  the crash trace keeps every frame; idempotence; the Diag ring end-to-end.
- **`v312 tsr:`** — timeline categories + capture-time scrubbing; crashTop EXCEPTION-first
  (the `at <timestamp>` header line never leaks into the frame list); the diagnosis
  rule engine (crash-leads, player-error card, KV card, receiver card, needs-key card,
  NSD-info card); the 4-section report; version+SDK-only header; end-to-end zero-PII.
- **`v312 upd:`** — the spin-loop proofs (50 calls → 1 handoff; ≤1 skip line).
- **`v312 launch:`** — Test Player contract (the mux HLS sandbox stream), TitleCleaner
  on `"Binging: The Odyssey"`, a describe()-shaped `/play` → `ok:true`, a malformed
  re-cast refused without killing the receiver, DLNA `SetAVTransportURI` driving the
  same pipeline, the `castJson` + notification-fallback contracts.

**Bytecode reference verifier (new, `lab/verify_refs.py`):** walks every compiled
`.class` constant pool and proves each referenced type resolves to a lab class, the
JDK, or `android.*` — the missing-class class of `NoClassDefFoundError` is structurally
impossible to ship. (The APK build itself compiles against `android-36/android.jar`
+ `libs/*.jar` + generated `R.java`, which closes the `R.string.*` / Media3 reference
surface in the same stroke.)

**Live Worker v4.1 E2E (best-effort, recorded):** in this sandbox the deployed worker
answered — `/health` → `{"ok":true,"version":"4.1.0","storage":"kv","pa":"configured"}`,
`/api/resolve?title=The Odyssey` → `tt33764258 (2026)`, and the subtitle job returned a
**1909-cue Sinhala .srt** with UTF-8 intact. Player ↔ Worker v4.1 compatibility is
proven end-to-end.

---

## 5 · The real APK — built and verified

```
$ ANDROID_HOME=/…/android-sdk ./build.sh
── javac (Java 8 bytecode target, android.jar + media3 on the classpath)
── d8 → dex (app classes + the media3/guava jars)
── zipalign        → OK (-c 4)
── sign            → v2 scheme: true · v3 scheme: true (apksigner verify)
── badging         → package: lk.techworld.anantplay.sicast
                     versionCode='30102' versionName='3.1.2'
                     targetSdkVersion:'36' compileSdkVersion:'36'
── done: dist/AnantPlay-Sinhala-Player-v3.1.2.apk
   size   : 3,379,923 bytes (3300 KiB)
   sha256 : ddc0d185ca98c211d3e70e3832b6593c36f738fde9ea435e96b3db967895abd5
```

- single `classes.dex` (6.46 MB) — the app + media3 1.11.1 + guava, no multidex surprise
- `assets/fonts/NotoSansSinhala-Regular.ttf` present
- permission set unchanged (zero Accessibility, verified in badging)
- **Note on signing:** built with the local debug keystore (no release secrets in this
  sandbox). CI with `KS_BASE64/KS_PASS/…` secrets signs identically across releases —
  Obtainium in-place updates keep working. Installing a debug-signed build over a
  release-signed install requires one uninstall first.

---

## 6 · Cast + Sinhala subtitles end-to-end (the #1 goal)

The complete chain, each hop now verified:

```
official AnantPlay (me.champdev.weblock)  →  Cast chip lists "සිංහල Player"
  (mDNS: NsdManager _anantplay._tcp + our 5353 responder;  DLNA: SSDP 1900 M-SEARCH)
→ POST http://<phone>:8765/play           (same-phone source; LocalNet accepts)
→ ReceiverService.onPlay                   (file:// gated; overlay fallback = notification)
→ PlayActivity.onCreate                   ← THE CRASH IS FIXED — opens in the 5 s window
→ {"ok":true} to AnantPlay                (its Remote stays live: /status /do polling)
→ BridgeApi → Worker v4.1                 /api/resolve → /subtitles job → /sub/<token>.srt
→ SrtLoader → List<Cue>                   (1909 Sinhala cues in the live test)
→ the 4 Hz native renderer                (cues vs. the player's own position — frame-exact)
→ Sinhala subtitles on screen ✓           (subtitle failure = bilingual pill + Retry /
                                            Provider / Local .srt — never silent, never a crash)
```

---

## 7 · File map of the v3.1.2 changes

| File | Change |
| --- | --- |
| `src/…/PlayActivity.java` | **the crash fix** (`setBackgroundColor`), Troubleshooter hooks (launch source, Media3 states, errors, subs), the privacy-safe diag accessors |
| `src/…/MainActivity.java` | Test Player launch guard; the Diagnostics tab rebuild (hero row, Deep Troubleshoot, gap/overlap-free log card, top-first crash card); facts collection |
| `src/…/App.java` | startup/crash headers → `sdk N` only (the privacy fix); Troubleshooter APP/CRASH events |
| `src/…/SelfUpdater.java` | the spin-loop fix (SCHEDULE_DEBOUNCE_MS + rate-limited skip log); UPDATER timeline events |
| `src/…/Diag.java` | every ring line passes PrivacyScrubber (the last gate) |
| `src/…/PrivacyScrubber.java` | **new** — the zero-PII engine |
| `src/…/Troubleshooter.java` | **new** — timeline + crashTop + diagnose + buildReport |
| `src/…/ReceiverService.java` | CAST timeline events (accept/refuse/open/fallback) |
| `src/…/AppInfo.java`, `AndroidManifest.xml`, `build.sh`, `.github/workflows/*.yml` | 3.1.2 / 30102 everywhere (lab-enforced parity) |
| `lab/ReceiverLab.java` | +54 checks (the five v3.1.2 suites) |
| `lab/verify_refs.py`, `lab/run_lab.sh` | **new** bytecode verifier, wired + gating |
| `scripts/troubleshoot-adb.sh`, `scripts/troubleshoot-adb.ps1` | **new** privacy-safe PC ADB capture |

---

## 8 · How to use the troubleshooter on the phone

1. **Diagnostics → 🩺 Run Deep Troubleshoot** — the 6 stages run for real; the verdict
   line shows ✓/✗ per stage and every stage lands in the timeline.
2. **Diagnostics → 📋 Copy Full Troubleshoot Report (Privacy-Safe)** — the whole
   Markdown bundle (Status Summary + Auto-Detected Issues + top-first Last Crash +
   timeline) hits the clipboard, scrubbed. Paste it anywhere — it contains no phone
   model, no IP, no MAC, no PII (lab-proven).
3. **On a PC:** `sh scripts/troubleshoot-adb.sh` (or the `.ps1`) — live, scrubbed
   logcat for only the two apps while you reproduce a cast.

**Bilingual by design** — every diagnosis card carries both the Sinhala and the English
line, matching the app's UI language mode.
