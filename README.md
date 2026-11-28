# AnantPlay Player (SI Cast Receiver) v3.4.6.l (versionCode 30418)

> **v3.4.6.c — THE ZERO-DEAD-BUTTON MORNYE SHELL · REAL CINEMA PLAYER + BROWSER · 16 KB-PAGE ENGINE · 55 MB UPDATER TIER.**
> Mornye Glass is now the default surface, with live 10–95% clarity, optional rim
> outlines, glass icon medallions, and System/Midnight/AMOLED/Clean Light themes.
> Inter is bundled for modern Latin UI text with Noto Sinhala fallback; System,
> Rounded, and Monospace remain selectable. The existing five-action player dock,
> subtitle tools, Home guidance, backup, privacy, and permissions are retained.
>
> Local verification: 1,251 executable Java regressions + 1,930 icon/typography checks
> + 57 v3.4.5 appearance/release gates = **3,238 checks passed**.

> The signed release metadata (`docs/latest.json`) is written by the release workflow
> after it builds the permanent-key APK, so its checksum always matches the published file.

> **v3.4.3 — ZERO-DEFECT HARDENING: 5-STAGE FIRST-LAUNCH ONBOARDING · P0 MEDIA3/WEBVIEW
> SECURITY · WATCH-HISTORY CREDENTIAL HYGIENE · SIMPLE/ADVANCED SETTINGS ·
> LIVE-TESTED SUBTITLE ORDER · 1,181/1,181 CI LAB.**
> A brand-new user is set up in 30 seconds by a five-stage wizard (Welcome →
> Notifications → Auto-open on Cast → Background stability → Connect AnantPlay +
> a real 10-point check) that explains before it asks and never traps anyone in a
> loop. The cast cookie is now origin-scoped instead of a Media3 default request
> property, an authenticated HTTPS request can no longer be silently downgraded to
> plaintext, the WebView blocks mixed content by default, and its Blob subtitle
> bridge is capped, same-origin and gesture-gated. Watch history no longer stores a
> Cookie or Authorization and knows when an AnantPlay LAN relay has expired. Settings
> opens with five Quick controls and folds the rest into Advanced; the subtitle
> provider order is now the live-HTTP-tested one (`https://subz.lk/` first tier).

> **v3.4.2 — CLEAN-ENGLISH FLAGSHIP UI · CONTINUE WATCHING · CINEMA PLAYER FIXES ·
> 10 SOURCES FOR SINHALA SUBTITLES · 1,101/1,101 CI LAB.**
> Everything the three real-device screenshots showed is fixed at the source: the
> subtitle line is anchored at the bottom (5% landscape / 8% portrait) instead of
> across the actors' faces, the "subtitles ready" pill fades in 2.5 s instead of
> sitting on a forehead forever, the top-bar icons are 20 dp glyphs inside 40 dp
> plates with real padding, the overlapping Retry/Provider/Language/Spoken/AI-Studio
> rows are gone from the video surface (they live in the Subtitles sheet), and
> landscape is TRUE immersive fullscreen. New: a Watch-History "Continue watching"
> row with a progress bar, the exact `0:42:15 / 1:58:00` timestamp and 1-tap resume;
> a Midnight Night Dimmer (10–70%); a one-tap "Auto-Open Player on Cast" overlay
> banner; sensor auto-rotate with an orientation lock; hold-for-2.0× speed boost and
> a 0.5×–2.5× picker. Settings no longer jump back to Home on every change, the
> browser omnibar rides above the keyboard with live search suggestions, and the
> PrivacyScrubber no longer mangles IMDb ids or millisecond numbers. UI is clean
> English by default (Sinhala remains an option); movie subtitles stay Sinhala.

> **v3.4.0 — THE VECTOR UI OVERHAUL · 63 VectorDrawables · NEW ADAPTIVE + MONOCHROME
> ICON · REAL 400/600 SINHALA WEIGHTS (−742 KB) · MORNYE-GRADE SHELL WITH 75
> CUSTOMIZATION OPTIONS · REDESIGNED CINEMA HUD + 3 BOTTOM SHEETS · SAFARI-GRADE
> BROWSER (SPEED DIAL · BOTTOM OMNIBAR · CAPTURE DOCK).**
> Every emoji that was doing an icon's job is gone: the bottom bar, the top bar, the
> player HUD, the chips, the sheets and the browser bar all paint tinted vectors from
> `res/drawable/ic_*.xml` through `UiIcons`. The launcher icon is a real Android 8+
> adaptive icon (background + foreground + Material-You monochrome) drawn from the
> **outline of the letter ස** extracted from the bundled font — no more baked-squircle
> PNG that a launcher masks twice. The bundled Sinhala font was a variable font pinned
> to its 400 default, so every bold was synthetic; it is now two REAL static instances
> (400 + 600) with full GSUB/GPOS/GDEF shaping and ZWJ/ZWNJ preserved, at **422 KB
> instead of 1,154 KB**. All 773 v3.3.1 lab checks still pass, plus the new v3.4.0
> suites (icons, fonts, prefs, browser, layout).

> **v3.3.0 — CINEMA PLAYER & NAVIGATION OVERHAUL · HTTP-403 KILLED ·
> ASPECT-RATIO ENGINE · SRI LANKAN SUBTITLE CAPTURE · 660/660 CI LAB.**
> Built on the 2026-11-22 22:17 real-device screenshots of v3.2.0, which showed
> four catastrophic defects: (1) the **entire bottom navigation bar had
> disappeared** — `buildUi()` constructed `bottomBar`, filled it with four tabs
> and then **never called `root.addView(bottomBar)`**, leaving it parentless
> (the only trace was 58 dp of reserved empty space under the Home page);
> (2) a cast that plays perfectly inside official AnantPlay died with
> **`ERROR_CODE_IO_BAD_HTTP_STATUS · Response code: 403`** because the player
> sent a truncated stub User-Agent, never derived `Origin` from the `Referer`,
> and forwarded one cast cookie to every CDN host; (3) the hand-rolled
> `PlayActivity` **stretched a 16:9 movie to fill a 20:9 portrait screen** (no
> `onVideoSizeChanged`, no aspect math) with a `‹ Back` button sitting on top of
> the Retry row and 22 sp subtitles; (4) no automatic Sri Lankan subtitle
> finding. v3.3.0 fixes all four at the source and proves it: **716/716 lab
> checks PASS** (hosted CI, now 773 with the v3.3.1 suites), a live mock-CDN 403 lab, the aspect math (1080×608),
> the nav-shell invariant, the loopback `/subs/current.srt` route that makes
> `📺 VLC` work on Android 16 without a `file://` Uri (and without handing a
> private cache path to another app), and a signed APK whose badging says
> 3.3.0 / 30300.

> **v3.3.1 — ZERO-CRASH HARDENING · LAYOUTPARAMS STATIC GATE · THREAD-SAFE
> TROUBLESHOOTER · CARD-RADIUS FIX · 773/773 CI LAB.**
> Built on two real Android 16 (SDK 36) crashes logged on the user's own phone on
> 2026-11-23 at 02:03:40 and 02:04:58: (1) **`ClassCastException:
> LinearLayout$LayoutParams cannot be cast to FrameLayout$LayoutParams` at
> `FrameLayout.onMeasure(FrameLayout.java:195)`** — the player's subtitle pill
> column, the HUD chip strip and the ⏪/⏩ double-tap ripple were mounted with the
> wrong `LayoutParams` class, so the framework's own measure pass killed the
> process (no `try/catch` of ours can catch a throw inside `View.measure`);
> (2) **`ConcurrentModificationException` at `MainActivity.paintProbeCard:2127`**
> — the 10-point troubleshoot shared one live `ArrayList` between the probe
> thread (appending) and the UI thread (iterating). v3.3.1 fixes both at the
> source and then makes them **structurally impossible**: a new gating static
> analyzer (`lab/verify_layouts.py`) types every container/child pair across all
> 46 Java files and fails the build on any mismatch, the probe engine hands out
> only immutable snapshots (`CopyOnWriteArrayList` + `unmodifiableList`), and an
> 8-thread × 1,000-write stress test with four concurrent readers runs on every
> CI build. Also fixed: the **Card corner radius** setting that changed nothing
> on screen (the palette always used the default token) and the four v3.3.0
> player switches (lock-on-start, the three swipe gestures) that
> **Backup & Restore silently dropped**.

> **v3.2.0 — PLAYACTIVITY CRASH FIX · SELF-UPDATER SPIN-LOOP FIX ·
> PRIVACY-FIRST DEEP TROUBLESHOOTER · SINHALA ZWJ CONJUNCT FIX ·
> 606/606 CI LAB + BYTECODE VERIFIER.**
> Built on the 2026-11-22 real-device log: the player died on every launch with
> `Resources$NotFoundException: Resource ID #0x88000000` —
> `View.setBackgroundResource(int)` treats a bare int as a **resource id**, so
> the translucent-black `notice` background threw from `buildUi()` inside
> `PlayActivity.onCreate` (the `TransactionExecutor.executeNonLifecycleItem`
> "keeps stopping" crash on Test Player, Play Video URL and every cast
> arrival, which AnantPlay reported as "The TV refused this video"). Replaced
> with `setBackgroundColor(0x88000000)` — the ARGB API, zero resource lookup.
> The updater's 38-lines-in-645 ms `single-flight` storm is killed by
> `SCHEDULE_DEBOUNCE_MS = 10_000L` plus a ≤1-line/sec skip log. Diagnostics
> gains a **privacy-first built-in Deep Troubleshooter** (6 active stages +
> 1-tap `📋 Copy Full Troubleshoot Report (Privacy-Safe)` + `📤 Share Report`),
> with `PrivacyScrubber` enforcing **zero phone model / IP / MAC / EUI-64 /
> SSID / token / path** leaks on every log line, crash trace and report.
> The lab is now **606/606 PASS** and a new bytecode reference verifier
> (`lab/verify_refs.py`) makes `NoClassDefFoundError` structurally impossible.
> Still 100% pure Java / Android SDK — **strictly v3.x, never v4.**

> **v3.1.1 — CAST FIX · CRASH RECORDER · SILENT ZERO-LAG UPDATER · HYPEROS
> AUTOSTART FIX · MORNYE-INSPIRED CUSTOMIZABLE UI.** Built on the 2026-11-22
> 16:36 real-device log: our own receiver refused official AnantPlay's
> `GET /dlna/desc.xml` with `refused-nonlocal` (the same-phone cast was
> misclassified as a stranger — fixed by the LocalNet same-host policy), the
> NSD watchdog killed a healthy mDNS responder (now re-registers ONLY, the
> responder is untouchable), update notifications spammed and lagged the phone
> (now a silent IMPORTANCE_LOW channel, ≤ 1 notify/second, 24 h per-version
> cooldown, Skip-this-version), and every uncaught crash now lands in
> `filesDir/last_crash.txt` with a Diagnostics "Last Crash Captured" card.
> The UI is redesigned the Mornye way (github.com/mornyeapp/Mornye — read-only
> research reference, zero fork, zero Flutter): categorized settings
> drill-down, theme modes + AMOLED + dynamic color + 6 accent presets, a
> Subtitle Styling Studio with a REAL-TIME live preview, playback
> customization, Cast & Network controls, and strict-schema Backup & Restore.
> Still 100% pure Java / Android SDK (~3.3–3.8 MB APK, zero new libraries).

**The zero-accessibility, zero-fork Sinhala subtitle solution for the 100% official, unmodified
AnantPlay app** — built for the hardest target: a **Xiaomi HyperOS (Android 14–16)** phone.

This app is an **AnantPlay Cast Receiver**. Official AnantPlay (`me.champdev.weblock`, v2.23.1)
discovers it over the same Wi-Fi exactly like an AnantPlay TV, hands it the stream (URL + headers +
cookie + title + position), and the movie then plays **here** — with native, frame-exact Sinhala
subtitles from the Sinhala bridge worker.

```
official AnantPlay (untouched)                 this app (Sinhala Player)
┌────────────────────────────┐   mDNS   ┌──────────────────────────────────┐
│ player → Cast chip →       ─────────► │ _anantplay._tcp + HTTP :8765     │
│ "සිංහල Player - AnantPlay │  /play   │ POST /play {url, headers, title} │
│  (Recommended)"            │ ◄─────── │ → opens its own ExoPlayer here   │
│ (its Remote keeps working) │ /status  │ GET /status {title,pos,dur,…}    │
│                            │   /do    │ POST /do a=toggle|seek|stop|vol± │
└────────────────────────────┘          │ + Sinhala subs from the Worker   │
                                        └──────────────────────────────────┘
```

## Why this exists (the 18:12 failure)

The previous approach — `AnantPlay-SI-Companion` v1, an `AccessibilityService` overlay over the
official player — flipped to **"Not working. Tap for info."** on a Xiaomi HyperOS phone within the
same minute it was enabled. Verified from AOSP source: that string is
`accessibility_summary_state_stopped`, shown when `serviceEnabled && info.crashed`
(`packages/apps/Settings .../AccessibilitySettings.java:268-272`), i.e. **the enabled service's
process died and was never rebound**. HyperOS's powerkeeper/Security force-stops background apps
without MIUI's Autostart + battery exceptions — a force-stop sets the package stopped-state, so the
accessibility service cannot restart (the same thing happened to KDE Connect on the same phone;
ESET stays `On` because it runs a permanent foreground service with the vendor permissions granted).

**The cast receiver removes the fragile part entirely: there is no AccessibilityService in this app
at all.** While a movie plays, the app IS the foreground app — the one state no vendor killer
touches. While idle, it runs a tiny `connectedDevice` foreground service (AnantPlay's own
`Cast$Keep` pattern) — and even if HyperOS kills *that*, nothing shows "Not working": one tap
re-arms it. See `docs/CAST-VERIFICATION.md` for every `file:line` citation.

## What you get

- **Cast hand-over**: tap AnantPlay's own cast button → `සිංහල Player - AnantPlay (Recommended)` →
  the movie plays here (same URL/headers/cookie; same media3 1.11.1 engine AnantPlay uses).
- **Native Sinhala subtitles**: human subs (OpenSubtitles sin / SubDL / piratelk) or Gemini EN→SI
  auto-translation via the deployed worker
  `https://anantplay-si-subs-bridge.techworldlk.workers.dev`, cached locally forever; rendered with
  NotoSansSinhala against **the player's own clock — frame-exact by construction**, ±0.25 s / ±1 s
  nudges, font-size and mask controls, and a local `.srt`/`.zip` loader (Baiscope/Zoom/PirateLK).
- **18 subtitle languages** (v2.2): සිංහල plus தமிழ் · हिन्दी · English · Español · Français · 日本語 ·
  한국어 · 中文 · العربية · اردو · Deutsch · Русский · Português · ไทย · Bahasa Indonesia · Türkçe ·
  Tiếng Việt — the 8 first-class chips (`si ta hi en es ja ko ar`) with **"+ More"** for the full
  registry. RTL is handled properly for `ar`/`ur`, CJK gets an 18-char line budget, and the bundled
  NotoSansSinhala font is used **only** for Sinhala (every other script uses the OS font stack).
- **Spoken ⇄ Formal register toggle** (v2.2): one tap cycles `auto (genre) → spoken → formal`,
  which re-runs the translation with the matching Sinhala register (`?register=` on the worker).
- **Two-stage Cinema Polish** (v2.2): the base NMT subtitle appears **immediately**; the player then
  does one background re-check and, when the worker flips the job to `cinema-polished`, silently
  hot-swaps the cue list mid-playback (no pause, no restart) and flashes **"✨ Cinema Polish Ready"**.
- **Optional "✨ AI Studio" power path** (v2.2, Option-C): copies a ≤ 40 KB UTF-8 prompt of the
  English/ draft cue pairs to the clipboard and opens `https://aistudio.google.com/` in your **real
  browser** (an `ACTION_VIEW` intent — never a WebView, zero automation, zero ToS risk). Paste,
  polish, then re-import with **📄 Local .srt**. No browser installed? It says so and keeps the
  clipboard.
- **AnantPlay stays the remote**: its own Remote screen (and the resume-on-stop loop) keep working
  — `/status`, `/do`; or control everything right here (gestures, seek bar, double-tap ±10 s,
  brightness/volume swipes, PiP).
- **Xiaomi HyperOS guided setup**: live ✅/❌ checks + one-tap jumps to the exact MIUI screens
  (Autostart, Battery No-restrictions, Other permissions, overlay grant), a **Self-test** that
  proves the whole chain on your phone (loopback HTTP → same-device mDNS discovery → the bridge),
  a **Test player right now** button, and a **Diagnostics log** (every event, every crash, Copy
  button) — no more silent failures. Quick Settings tile: `අ සිංහල Cast`.

## Build

Plain SDK tools — no Gradle (AnantPlay's own recipe):

```sh
# needs: JDK 17+, Android SDK: platforms;android-34 + build-tools;35.0.1
ANDROID_HOME=/path/to/sdk ./build.sh
# → dist/AnantPlay-Sinhala-Player.apk (signed; apksigner-verified)
```

The media3 1.11.1 + guava jars (the exact set AnantPlay ships in its `libs/`, Apache-2.0) are in
`libs/`; CI (`.github/workflows/build-apk.yml`) runs the lab + builds the APK on every push.

## Verify

```sh
./lab/run_lab.sh     # the gating lab — 606 checks + the bytecode verifier, byte-compatible with Cast.java
```

The lab drives `ReceiverServer` with the exact requests official AnantPlay's
`Cast.call()`/`describe()` produce, plus the SRT round-trip through the very code the APK ships —
and it gates every hardening claim below: the cast policy (scheme/action whitelists, seek
clamping), defensive HTTP parsing (mixed-case headers, negative/huge/garbage `Content-Length`,
header-injection, malformed JSON), `SrtLoader` (Zip-Slip, zip bomb, BOM/CRLF, UTF-16, nested
folders, `__MACOSX`), `MiniJson` nesting, `Diag` secret redaction, `BridgeApi` (literal job-id
colons, HTTP 429/5xx/202 UX) and `StreamHint` mime defaulting.

The **v2.2 gate** adds the multi-language contract: the 18-entry `Langs` registry (codes, native
labels, ISO-639-2B/SubDL codes, RTL flags `ar`/`ur`, CJK budgets) is asserted entry-by-entry against
`worker/src/languages.js` v4.1, the 8-chip row, the bundled-font rule (`si` only), the safe
unknown-code fallback, the `lang=`/`register=` query shapes and the ≤ 40 KB Option-C chunk. The raw
mDNS bytes come from `MdnsWire`, so the PTR question with the QU bit is proven on a bare JVM. The live worker E2E
(`lab/BridgeLab.java`) is recorded but never gating — an offline runner or a Gemini 429 must not
turn the build red.

CI (`.github/workflows/build-apk.yml`) runs that lab, then builds, signs and `apksigner verify`s
the APK on JDK 17 + **Android SDK 36 (`platforms;android-36`) + build-tools 35.0.1** with **zero Gradle**, and uploads
`AnantPlay-Sinhala-Player-v3.2.0.apk` as a downloadable artifact.

> **build-tools 35.0.1 is mandatory, not cosmetic.** `d8` 8.2 (build-tools 34.0.0) crashes on the
> media3 1.11.1 / guava 33.3.1 jars — *"Cannot invoke String.length() because `<parameter1>` is
> null"*, i.e. unnamed `MethodParameters`. `build.sh` therefore refuses every `34.x` toolchain and
> CI installs `build-tools;35.0.1` explicitly.

## The user-facing flow

1. Install the APK, open it once → **Start receiver** (a persistent "අ" notification appears).
2. Grant **Display over other apps** (one system toggle — for the one-tap screen takeover) and,
   on Xiaomi, the MIUI permissions the screen walks you through.
3. In official AnantPlay, play any movie on any site → tap the **cast** chip →
   **සිංහල Player - AnantPlay (Recommended)**.
4. The movie opens here with Sinhala subs — tap the **si · ta · hi · en · es · ja · ko · ar** chips
   (or **+ More** for all 18 languages) to change language, and the **Spoken / Formal** pill to change
   register. The base subtitle appears immediately and upgrades itself to **✨ Cinema Polish Ready**
   when the worker finishes the second pass.
5. *Optional:* in the `අ` panel, **✨ AI Studio** copies a ≤ 40 KB polishing prompt and opens your
   browser — paste into AI Studio, then re-import with **📄 Local .srt**.
6. Stop casting / close → AnantPlay's own player resumes exactly where you were.
7. No overlay permission? The cast still succeeds: a high-priority "Cast received" notification
   opens the player on one tap (nothing crashes, nothing is silently dropped).

Sinhala setup guide: **SETUP-SI.md** · Full architecture + audit trail: **docs/CAST-VERIFICATION.md**

## Repo layout

```
AndroidManifest.xml          no accessibility anywhere; connectedDevice FGS; PiP player
build.sh                     aapt2/javac/d8/zipalign/apksigner — AnantPlay's recipe
src/.../sicast/
  ReceiverServer.java        the Cast protocol (pure java.net; byte-compatible; LAN-source-locked)
  ReceiverService.java       the foreground receiver + /play dispatch + takeover
  NsdAnnouncer.java          _anantplay._tcp + _fmhyplay._tcp registration (like a TV)
  PlayActivity.java          media3 player + native Sinhala renderer + worker pipeline
  MainActivity.java          Xiaomi HyperOS guided setup + Self-test + diagnostics
  CastTileService.java       Quick Settings tile
  Langs.java                 the 18-language registry (1:1 with worker/src/languages.js) — pure Java
  MdnsWire.java              RFC 6762 PTR question bytes (QU bit) — pure Java, lab-proven
  CastPolicy.java            cast-arrival policy (schemes, /do whitelist, seek clamp) — pure Java
  StreamHint.java            Media3 mime defaulting for extensionless HLS/DASH — pure Java
  BridgeApi/Cue/MiniJson/SrtLoader/SubtitleStore   the proven v1 engines (pure Java)
lab/ReceiverLab.java         the 606-check gating lab (protocol + security + i18n + v3.2.0 field-bug regressions)
lab/verify_refs.py           the bytecode reference verifier (every .class ref resolves)
lab/BridgeLab.java           live worker E2E (best effort)
PrivacyScrubber.java         the zero-PII scrubber (IP / MAC / EUI-64 / model / token / path)
docs/CAST-VERIFICATION.md    every AnantPlay file:line this is built on
THIRD_PARTY.md               licenses of every bundled jar + the Sinhala font
SETUP-SI.md                  the Sinhala + English setup checklist (v3.2.0)
scripts/troubleshoot-adb.*   PC ADB capture helpers (sh + PowerShell 5.1), privacy-safe
```

## What's new in v3.2.0 (PlayActivity crash fix · SelfUpdater spin-loop fix · privacy-first Deep Troubleshooter)

> **v3.2.0 is a crash + reliability + privacy release.** Download:
> `AnantPlay-Sinhala-Player-v3.2.0.apk` — `versionCode 30200`,
> `versionName 3.2.0`, `targetSdk 36`, release-signed with the same permanent
> keystore as v3.1.0/v3.1.1 → your phone updates **in place**, no uninstall.

Built on the 2026-11-22 Xiaomi Android 16 field log, every defect root-caused
with file:line proof and covered by new regression checks in
`lab/ReceiverLab.java` (see `docs/reports/V3_1_2_CRASH_FIX_AND_PRIVACY_TROUBLESHOOTER_REPORT.md`):

- **Bug 1 — the `PlayActivity` "keeps stopping" crash FIXED.**
  `PlayActivity.buildUi()` called `notice.setBackgroundResource(0x88000000)`;
  `View.setBackgroundResource(int)` interprets a bare int as a **resource id**
  and `0x88000000` resolves to none, so `Resources$NotFoundException` was thrown
  synchronously from `PlayActivity.onCreate` on **every** player launch (Test
  Player, Play Video URL, ACTION_VIEW/Share, and every mDNS/DLNA cast). The fix
  is `notice.setBackgroundColor(0x88000000)` — identical translucent black, the
  ARGB-interpretation API. The `Test Player` launch is additionally wrapped in
  `try/catch`, and no view anywhere in `src/` calls
  `.setBackgroundResource(0x…)` (lab-verified with comments stripped).
- **Bug 2 — the `selfupd` 38-lines-in-645 ms spin loop FIXED.**
  `SelfUpdater.schedule(c, 0L)` was callable from every entry point and each
  call **replaced** the pending `JobInfo`, making JobScheduler re-deliver
  `CheckJob.onStartJob` while a run was still in flight → a `single-flight`
  Diag storm. `schedule()` now debounces zero-delay re-schedules with
  `SCHEDULE_DEBOUNCE_MS = 10_000L`, and the skip log is rate-limited to
  **≤ 1 line/second** (lab-proven: a 50-call storm → 1 job handoff, 1 skip line).
- **Bug 3 — the v3.1.1 diagnostics log carried real PII.** The field log exposed
  the phone's hardware model, the LAN IP, and an EUI-64 IPv6 link-local address
  that **embeds the Wi-Fi MAC**. New `PrivacyScrubber` (pure Java + regex)
  removes vendor+model pairs, hardware-model tokens, `Build.*` fields, MACs,
  EUI-64/any IPv6, every IPv4 (private/loopback → `<local-v4>`/`<lan-peer>`,
  public → `<remote-ip>`), Bearer/JWT/API keys/cookie/query secrets, SSIDs and
  `/storage/…` paths — while keeping the technical lines byte-intact.
  `App.java` now logs only `sdk N`; `Diag.redact()` chains
  `PrivacyScrubber.scrub()`; `Troubleshooter.crashTop()` puts the
  `EXCEPTION`/`CAUSED BY` line and the **first app frames first**.
- **The privacy-first built-in Deep Troubleshooter.** The Diagnostics tab is a
  real `ScrollView` (no fixed-height gap, no overlapping lines) with
  `📋 Copy Full Troubleshoot Report (Privacy-Safe)`, `📤 Share Report` and
  `🩺 Run Deep Troubleshoot` — a 6-stage active self-test (receiver loopback,
  mDNS, DLNA, worker bridge, subtitle pipeline, player readiness) whose verdict
  is readable on the phone and safe to paste into a bug report.
- **PC ADB capture helpers.** `scripts/troubleshoot-adb.sh` and
  `scripts/troubleshoot-adb.ps1` (PowerShell 5.1-compatible) filter live logcat
  to this app's cast/crash lines and pipe them through the same scrub rules —
  nothing is written to the phone.
- **Sinhala conjuncts are never mangled (ZWJ/ZWNJ preserved).** `Cue.stripMarkup()`
  cleans `{\an8}` ASS blocks, `<i>`/`<font>` tags and HTML entities, but it
  now strips only **genuine** invisible garbage — BOM (U+FEFF), ZWSP (U+200B),
  soft-hyphen (U+00AD) and word-joiner (U+2060). **U+200D (ZWJ) and U+200C
  (ZWNJ) survive**, because Sinhala requires ZWJ for the rakaransaya and
  yansaya: `ශ්‍රී`, `චිත්‍රපටිය`, `ක්‍රියා`, `ප්‍රශ්නය`, `ව්‍යාපෘතිය` keep their
  joiners through worker JSON, `.srt`, `.zip` and the free translator —
  no more broken hal-kirima glyphs.
- **The 606-check CI lab + bytecode verifier.** `lab/ReceiverLab.java` grows to
  **606 checks** (125 new v3.2.0 suites: crash, privacy, troubleshooter,
  updater spin-loop, launch contracts). `lab/verify_refs.py` parses every
  compiled `.class` constant pool and proves every referenced type resolves to
  a lab class, the JDK or `android.*` — a missing Media3/R class cannot ship.
- **Build isolation kept strict:** `build.sh` compiles only `src/` (never
  `lab/stubs/`) against `platforms;android-36` + `build-tools;35.0.1`, and the
  CI zero-accessibility grep can never false-match `ReceiverLab`'s own
  string literals (the forbidden tokens are assembled at runtime).

## What's new in v3.1.1 (v3.x — Cast refused-nonlocal fix · CrashLog · silent updater · HyperOS autostart · Mornye-style UI)

> **v3.1.1 is a packaging + release-integrity patch (no behaviour change).**
> The APK identity is now declared **explicitly** on `<manifest>`
> (`android:versionCode="30101" android:versionName="3.1.1"`) instead of being
> injected only by `aapt2 --version-*`, so the source tree, `AppInfo.java`,
> `build.sh`, both workflows, the lab assertions and this README all agree on
> one version — byte for byte. Download:
> `AnantPlay-Sinhala-Player-v3.1.1.apk` (release-signed with the same permanent
> keystore as v3.1.0 → your phone updates **in place**, no uninstall).
>
> **Reproducible APKs:** `build.sh` pins every ZIP entry to a fixed epoch
> (`touch -t 198001010000` + `zip -X`) with `TZ=UTC` in both workflows, so the
> same commit always produces the same SHA-256 — verified: two independent CI
> runs of the same sources both hash
> `4fb0b800cd52881bb3b3b2eeeb789830adf12bc52607b101547418308d55c089`
> (3,367,635 B). Before this, the pre-merge build and the post-merge release
> build of one commit differed only by ZIP entry mtimes — identical size,
> different hash. `SHA256SUMS.txt` is the permanent, re-verifiable record of
> exactly the bytes you install (its 102 bytes are precisely
> `"<64 hex>␠␠AnantPlay-Sinhala-Player-v3.1.1.apk\n"`, the GNU format
> `SelfUpdateCore.pickShaLine` parses).

Built on the 2026-11-22 16:36 Xiaomi Android 16 field log, every defect
root-caused with file:line proof and covered by new regression checks in
`lab/ReceiverLab.java` (see `docs/reports/V3_1_CAST_CRASH_FIX_AND_MORNYE_UI_REPORT.md`):

- **Bug 1 — `GET /dlna/desc.xml -> refused-nonlocal` FIXED.** v3.0.0's
  `ReceiverServer.isSelf()` relied solely on a `NetworkInterface` enumeration
  inside a swallowed try/catch; when the HyperOS stack returns nothing, a
  same-device cast is classified as a stranger. v3.1 adds `LocalNet`
  (pure Java): IPv4-mapped IPv6 normalization, byte-wise comparison, IPv6
  scope-safe matching, a **same-host rule** (remote == the connection's own
  destination means same phone — the LOCATION AnantPlay connects to is ours),
  and a cached own-address snapshot fallback. Refusals are now HTTP 403 with
  the exact remote IP logged. External LAN strangers (other phones) are still
  refused.
- **Bug 2 — "keeps stopping" crash hardened.** New `CrashLog`: a chained
  UncaughtExceptionHandler atomically writes the FULL redacted crash report
  (timestamp, version, device, thread, every cause + frame) to
  `filesDir/last_crash.txt`; Diagnostics gains a "Last Crash Captured" card
  with 1-tap Copy / Share / Clear. The re-cast path (`PlayActivity.onNewIntent`)
  is now defensive end-to-end.
- **Bug 3 — the NSD watchdog never kills a healthy responder again.** The
  watchdog's scope comes from the pure `AnnouncerPolicy`: a re-drive
  re-registers `NsdManager` records ONLY (fresh transaction ids); a running
  `MdnsResponder`, its multicast lock and the DLNA renderer are untouchable.
- **Bug 4 — HyperOS autostart false negative cleared.** `Miui.AutostartState
  {ENABLED, DISABLED, UNKNOWN}` with a best-effort AppOps read; on HyperOS 15/16
  (read blocked, UNKNOWN) the setup row carries a 1-tap "Done / the user
  confirms" button that clears the warning permanently.
- **Bug 5 — updates are 100% silent, zero-lag, zero-spam.** A dedicated
  IMPORTANCE_LOW channel (no sound/vibration/badge), silent + only-alert-once
  everywhere, a pure `UpdateNotify` throttle (at most 1 notify/second, deduped
  per percent), an AtomicBoolean single-flight, a 24 h per-version alert
  cooldown, and Skip-this-version (notification action + Settings + dialog).
- **Mornye-inspired customization engine** (pure Java): `ThemeEngine`
  (System / Dark Mornye Slate / AMOLED pure black / Light + Material You
  dynamic color on Android 12+ + six accent presets), `AppPrefs` (every
  setting typed + clamped, Mornye-style normalizers) and `SettingsBackup`
  (strict-schema JSON Backup & Restore). The Settings tab is a categorized
  drill-down; the Home hero status strip is a weighted grid that can never
  clip; the Subtitle Styling Studio re-renders a live Sinhala preview as you
  drag its sliders.

## What's new in v3.0.0 (TRUE Android 16 cast fix · WebView cinema browser · 4-tab UI · merge-to-release CI)

The complete overhaul, built on the 2026-11-22 15:15 real-device log (Xiaomi
Android 16, sdk 36). Full story: `docs/reports/V3_MASTER_ARCHITECTURE_AND_ANDROID16_REPORT.md`.

- **Cast fixed at the root** — the log DISPROVED the sabotage theory (the
  responder was off while `announced` was already false) and the router theory
  (other LAN devices' mDNS reached the phone). The real causes, proven against
  the AOSP android16-release sources: (1) Android 14+ `MdnsAdvertiser` delivers
  NO callback ever when `MdnsSocketProvider` never creates a Wi-Fi socket
  (no timeout — `NsdService.java:1049 → mPendingRegistrations`); (2) v2.4.0's
  QU replies were unicast-only to the phone's own :5353 — a packet that travels
  the kernel `local` route (`lo`) which the `SO_BINDTODEVICE(wlan0)` system
  socket can never receive; (3) the multicast egress was never pinned, so the
  kernel loopback never reached the system socket; (4) TTL stayed at Java's
  default 1 and there was no AAAA for the `fe80::` queriers.
- **v3 mDNS responder**: replies ALWAYS multicast to `224.0.0.251` + `ff02::fb`
  (kernel fan-out to every joined socket), `setTimeToLive(255)`,
  `setNetworkInterface` pinned, AAAA added, RFC-6763 full-name PTR rdata, plus
  a 12 s/2 min registration watchdog that re-drives a hung NsdManager
  registration with fresh transaction ids.
- **DLNA MediaRenderer (new)** — a pure-Java UPnP renderer (SSDP responder on
  UDP 1900 + `/dlna/desc.xml` + AVTransport/RenderingControl SOAP on port
  8765): official AnantPlay's `Dlna.search()` finds it and `Cast.any()` counts
  it exactly like a TV — the Cast chip works with ZERO NsdManager involvement.
- **Built-in WebView cinema browser (new)** — Tab 2 sniffs the streams of any
  site you browse (HLS/DASH/MP4/MKV/WEBM, ad/segment/pixel filtered) and the
  "🎬 සිංහල උපසිරැසි සමඟ බලන්න" pill opens our player with the site's own
  headers + automatic Sinhala subtitles — on Wi-Fi OR mobile data, any router.
  `ACTION_SEND`/`ACTION_VIEW` targets let AnantPlay's Share ⋮ → Copy link (or
  any browser) open a video straight in the Sinhala player.
- **4-tab modern UI (rebuilt)** — Home (hero status + quick actions +
  collapsible guide + hidden-when-green checklist), WebView, Settings,
  Diagnostics with **1-tap 📋 Copy Full Log**. Edge-to-edge safe on targetSdk 36.
- **App-open auto-updater** — checks the moment the app opens (60 s debounce) +
  every 4 h; downloads itself with a live percentage, enforces https-only, a
  30 MB cap and strict SHA-256 against SHA256SUMS.txt, hands off to
  PackageInstaller (explicit mutable PendingIntent, BAL-safe fallback) and
  deletes the temp apk the moment the session owns it + sweeps on every start.
- **Merge-to-release CI** — every PR merged to `main` now publishes a GitHub
  Release automatically (Lab-gated, idempotent, one apk + SHA256SUMS.txt;
  a friendly green notice while the signing secrets are not yet configured).
- **Lab: 288/288 PASS** (from 190) — DLNA SSDP/SOAP E2E, the sniffer, the
  updater, storage-cleanup and pipeline-parity checks included.

## What's new in v2.4.0 (the Android 16 cast-discovery fix + self-updater)

- **THE `"announced": false` / NO-CAST-ICON ANDROID 16 BUG — ROOT-CAUSED AND FIXED.**
  Two independent defects, both proven from AOSP `MdnsAdvertiser`/`MdnsResponseDecoder`:
  1. **Our own responder sabotaged NsdManager's registration.** It answered RFC 6762 §8.1
     conflict probes (QTYPE=ANY on the instance name) for the SAME name the system
     advertiser was probing → every answer counted as a name CONFLICT → the advertiser
     renamed and re-probed forever → `onServiceRegistered` never fired (`"announced":
     false` for 35 s, and the Cast chip in official AnantPlay — hidden whenever
     `Cast.any()` is false — never appeared). v2.4.0: the responder NEVER answers ANY
     questions (probes are not queries), uses a DISTINCT instance name
     ("AnantPlay Sinhala Player" vs the daemon's "AnantPlay on සිංහල Player"), and
     starts only AFTER the registration settles (registration-first coexistence).
  2. **The A record never attached.** The SRV target was the bare instance name while
     the A owner was the full service name — Android's decoder attaches an address only
     when `A.owner == SRV.target`, so `isComplete()` stayed false and every answer was
     ignored. v2.4.0: SRV target = `<host>.local` (RFC 6763 §4.3), A owner = the SAME
     name, cache-flush 0x8001 on SRV/TXT/A (RFC 6762 §10.2), PTR TTL 4500, TXT carries
     the `fn` friendly name. The lab's AOSP-mirror decoder proves `isComplete() == true`.
  3. **The startup teardown race** (the 0.559 responder restart): `registerDefaultNetwork
     Callback`'s IMMEDIATE `onAvailable` fired while the registration was still pending
     and `healthy=false` triggered a re-arm that killed it. `NetPolicy` now has a 5 s
     ARM_GRACE window while a registration is pending.
- **Self-test L1 truth** — the daemon probe now requires the DAEMON's name in the answer
  (our responder's distinct name can no longer produce a false `L1 ✓`), and the result
  line shows `⚠ daemon-announce pending` when the real NsdManager registration has not
  settled.
- **BUILT-IN GITHUB SELF-UPDATER (`SelfUpdater.java`)** — checks
  `api.github.com/…/releases/latest` on every app launch (15 min rate limit) and every
  6 h via JobScheduler; downloads the APK asset, VERIFIES ITS SHA-256 against the same
  release's `SHA256SUMS.txt` (tampered/corrupted downloads are refused), and hands it
  to `PackageInstaller` → Android's own 1-tap "Update" prompt → in-place update with
  the same permanent keystore (zero uninstall, all data preserved). Works alongside
  Obtainium (same GitHub Releases, same signature). Honest limit: non-root Android 16
  never allows silent installs — the single OS confirmation tap stays.
- **Cast guide card** in the app (bilingual) explaining EXACTLY where the Cast chip
  appears inside AnantPlay and what to tap.

## What's new in v2.3.1 (the HyperOS field-log fixes)

- **THE "answered-but-no-answer" PARADOX FIXED** — the v2.3.0 probes decoded mDNS
  replies as ISO-8859-1 and searched for the Sinhala instance name ("සිංහල" = 15
  UTF-8 bytes → Latin-1 mojibake → never matches), so every reply that DID arrive was
  counted as silence while `MdnsResponder` logged "answered a unicast(QU) query" 16+
  times. Replies are now validated structurally (`MdnsWire.isOurAnswer`: QR bit +
  record counts + the ASCII service type — charset-independent by construction).
- **THE 4-PACKET / ~207 ms PROBE STORM FIXED** — the failed match made both probes
  spin at full speed re-sending. Probes are now bounded (daemon probe: ≤ 4 rounds
  with a quiet 600 ms receive window and de-duplicated targets; wire probe: ≤ 6
  sends, stops at the first validated answer) and the responder aggregates its log
  lines (one per source per second, "…(×N in 1 s)") — no more Diag floods, no idle
  battery drain.
- **THE `network up — re-announcing` CHURN RACE FIXED** — `start()` used to run
  `stop()` (unregistering the network callback) then re-register it, and the
  immediate `onAvailable` re-armed 500 ms later: an endless down/up cycle that
  killed a HEALTHY responder ("responder down (answered 12 queries)" in the field
  log). `start()` is now idempotent (no-op when healthy on the same network), the
  callback is registered once and released only on service destroy, and
  `NetPolicy.shouldReArm` ignores repeat events on the same healthy network.
- **Self-test hardening** — a re-entry guard (one tap = one run; the field log's two
  simultaneous probe sockets were two taps) and per-layer reporting: the result line
  now shows `mDNS ok [L1 daemon ✓ · L2 responder ✓ · L3 NSD ✗]` instead of one
  aggregate, so a red verdict can never hide an answering responder again.
- **RIGHT-EDGE TEXT CLIPPING FIXED** — the ScrollView and the diagnostics log used
  overlay scrollbars; HyperOS's fat translucent bar sliced every long line at the
  same x ("GitHub Relea…"). Both now use inset scrollbars, every full-width card
  carries explicit MATCH_PARENT layout params, and the player's bottom time labels
  share the free space (weight) so a 360dp screen can no longer push them off-edge.
- **Sinhala instance name kept (proven safe)** — RFC 6763 §4.1.1 allows opaque UTF-8
  instance names (ours is 35 bytes ≤ 63); official AnantPlay resolves and displays
  it correctly (Cast.java:262-264, strings_player.xml:94). The bug was never the
  name — it was the Latin-1 decode.

## What's new in v2.3.0 (router-free cast · noisy-title fix · DoH diagnostics · Obtainium)

- **Router-free mDNS (`MdnsResponder.java`)** — our own pure-Java RFC-6762 responder on
  UDP 5353 (`SO_REUSEADDR` alongside `mdnsd`, graceful `EADDRINUSE` degradation): answers
  PTR/SRV/TXT/A for `_anantplay._tcp` + legacy `_fmhyplay._tcp` with **unicast replies**
  (the QU bit), and gives the Self-test a **kernel-loopback proof path** that needs ZERO
  router reflection (the fix for the old-router "mDNS FAILED (no-answer)" false negative).
- **A real wire bug fixed** — v2.2.0's query packet wrote the QU bit into the LOW class
  byte (`0x00,0x81` = class 129, meaningless); the correct form is `0x80,0x01`. Proven
  byte-for-byte by the lab.
- **The honest 3-layer Self-test** — (1) the *daemon probe*: a unicast QU question to the
  phone's own `mdnsd` (the exact service AnantPlay's `NsdManager` discovery reads —
  `Cast.java:239-259`), (2) the *wire probe* against our own responder via kernel
  multicast loopback, (3) legacy `discoverServices` as a bonus. PASS = any layer.
- **Noisy-title cleaner (`TitleCleaner.java`)** — arbitrary streaming sites send page
  titles like "Watch The Odyssey 2026 Full Movie Free HD - OnlyFlix"; the cleaner strips
  site suffixes/noise, extracts year + SxxExx, and feeds Cinemeta the right lookup (the
  display title stays as-is; a failed clean lookup retries with the raw title).
- **DoH ISP-block diagnostics (`DohResolver.java`)** — AnantPlay forwards the RAW url
  (WARP is phone-side only, `Warp.java:202-207`), so an ISP-blocked stream fails here.
  On a network-ish playback error we compare system DNS vs Cloudflare/AdGuard DoH and
  say exactly why, bilingually; the error box becomes a one-tap **retry** that resumes
  at the last position.
- **Language-switch race guard** — stale fetch threads can no longer flip the subtitle
  status pill after you switch language mid-movie (`setState` is generation-guarded).
- **HyperOS self-heal** — the receiver re-checks itself every 15 min and re-arms if a
  powerkeeper pass half-killed it.
- **In-app update checker (`UpdateChecker.java`)** — "⬆ Check for updates" queries
  GitHub's latest release with a clean semver compare.
- **The Obtainium pipeline** — permanent-keystore scripts, `release-apk.yml` (tag or
  1-click dispatch → lab gate → signed APK → `SHA256SUMS.txt` → GitHub Release with
  exactly ONE `.apk` asset), and `docs/obtainium-config.json`. See
  [SETUP-SI.md](SETUP-SI.md) §9 for the 3-tap setup.

## What's new in v2.2.0 (18 languages · two-stage cinema polish · Option-C)

| Area | What changed |
| --- | --- |
| **18-language registry** | `Langs.java` mirrors `worker/src/languages.js` v4.1 **1:1** — same 18 codes, same OpenSubtitles ISO-639-2B + SubDL codes, same native labels, same RTL flags (`ar`, `ur`), same per-line budgets (42 Latin/Sinhala, 38 Tamil/Hindi/Arabic/Urdu, 24 Thai, 18 CJK, 40 Cyrillic). The lab asserts the mirror so the two can never drift apart silently. |
| **Script-safe rendering** | The bundled `NotoSansSinhala-Regular.ttf` is applied **only when `lang == "si"`**; every other language uses the system font stack (`Typeface.DEFAULT_BOLD`, resolved once — never re-loaded per cue). `View.TEXT_DIRECTION_ANY_RTL` is applied for `ar`/`ur`, LTR everywhere else. |
| **Two-stage hot-swap** | `BridgeApi` captures the worker's `X-Sub-Quality` / `X-Polish-Status` headers. The base NMT subtitle is displayed immediately; when the headers say `base-nmt` + `pending`, the player schedules **one** non-blocking background re-check and, on `cinema-polished`, hot-swaps the cue list through `SubtitleStore` without touching playback — then flashes `✨ Cinema Polish Ready · සිනමා ප්‍රඥප්තිය සූදානම්`. |
| **Lifecycle (leak fix)** | That re-check lives on the Activity's `Handler` and is now **explicitly cancelled in `onDestroy()`** (`removeCallbacksAndMessages(null)`), together with the 4 Hz ticker, the HUD/pill hide timers and the delayed PiP arming — so no pending task can hold a destroyed Activity, released ExoPlayer or dead view alive. |
| **Option-C "✨ AI Studio"** | A power-user escape hatch that stays inside the rules: the prompt is capped at **40 KB of UTF-8** (measured as it is built, Binder-safe), copied to the clipboard, and AI Studio is opened through a real browser `ACTION_VIEW` — **never** a WebView, **zero** automation. `ActivityNotFoundException` is caught by name ("prompt copied, but this phone has no browser") so a browser-less device cannot crash the player. |
| **Spoken ⇄ Formal** | `Spoken / Formal / auto (genre)` cycles through the worker's `register` parameter, re-running the job with the matching Sinhala register. |
| **Language sheet** | 8 first-class chips + "+ More" opens the full 18-language sheet; switching a language re-fetches with `?lang=`, clears the stale polish watch and reuses the per-language subtitle store key. |

## What's new in v2.1 (the 2026-11-21 field-test fixes)

| Area | What changed |
| --- | --- |
| **mDNS self-test** | The v1 test failed on HyperOS ("mDNS self-discovery FAILED") because the OEM mDNS daemon filters a service out of its own app's discovery results — while casts kept working. v2.1 proves the announcement with a **raw-socket mDNS probe** (`MdnsWire` PTR question with the QU unicast bit, sent on the Wi-Fi interface while holding a `MulticastLock`), keeps legacy discovery as a bonus, and on failure says WHY: Wi-Fi off vs router blocking multicast (AP isolation) vs an active VPN — in Sinhala + English with the exact fix. |
| **AdGuard guidance** | New in-app help card: Private DNS (`dns.adguard-dns.com`, DoT 853) never affects casting (it only encrypts unicast DNS — not the UDP 5353 multicast mDNS uses); the AdGuard ANDROID APP (local VPN) can, with the 2-step exclusion fix inline. |
| **Invisible titles** | The permission cards' titles were never set (`permRow()` built the TextView but skipped `setText`) — the cards showed only "✓ granted". Fixed + every text now uses explicit high-contrast colors (`forceDarkAllowed=false` too). |
| **Gesture-bar clipping** | The setup screen pads by the real navigation-bar insets, so the bottom button is never cut off. |
| **Bilingual everything** | Every permission card, setting and action has a Sinhala + English title and a one-line WHY (`Worker URL`, `Auto-fetch subs`, `AdGuard help`, `Self-test`, `Test player`). |
| **In-player subtitle HUD** | A status pill shows the live state (searching human subs → translating batch 2/4 via Azure → ✓ ready, auto-hides 3 s) and on failure shows the exact reason plus 1-tap **Retry / Switch Provider / Local .srt / ±0.25s** controls. |
| **Cast `subs` passthrough** | AnantPlay's cast payload `subs` URLs (the English .srt the phone player already had) are fetched and translated first via the worker's new `POST /api/subs-direct` — instant, no IMDb needed, cached forever. |
| **Zero-429 worker v4** | The worker translates through azure → google_v3 → gemini → workers_ai with automatic 429 failover, per-provider circuit breakers, single-flight job locks and a permanent KV cache (see the bridge repo). |

## Hardening summary (v2.1.0 → v2.2.0 audit)

| Area | What changed |
| --- | --- |
| Zero accessibility | Manifest has no `AccessibilityService`/`BIND_ACCESSIBILITY_SERVICE`; CI greps the sources and fails the build if one ever appears. |
| Android 14–16 FGS | `startForeground()` runs first in `onCreate` (5 s window); `connectedDevice` type now backed by `CHANGE_WIFI_STATE`/`CHANGE_NETWORK_STATE` (Android 14 requires a device-connection permission for that type); every background start path catches `ForegroundServiceStartNotAllowedException` and logs why; the notification is stopped in `onDestroy`. |
| Notifications | `POST_NOTIFICATIONS` requested at runtime with a result callback; receiver channel raised to `IMPORTANCE_LOW` (an `IMPORTANCE_MIN` FGS notification can be invisible on Android 13+) plus a HIGH-priority cast channel. |
| Overlay | Optional. Without it a cast returns `ok:true` and lands as a tap-to-open high-priority notification — no crash, no dead cast. |
| LAN isolation | `isSelf` (loopback, own interfaces, v4-mapped form); foreign peers get a content-free refusal. |
| HTTP | Locale-safe headers, defensive `Content-Length`, 12-connection cap, whitelisted `/do`, clamped seeks, fixed response headers (no injection), explicit flush. |
| Secrets | `Diag` redacts cookies, tokens, `Authorization`/`Bearer`, JWTs and API keys, and caps each line, so *Copy log* can never leak a stream credential. |
| Subtitles | `SrtLoader` rejects Zip-Slip names (`..`, absolute, `C:\`, backslashes), enforces a shared 5 MB decompression budget, ignores `__MACOSX`, honours UTF-16 BOMs; `MiniJson` caps nesting. |
| Worker UX | HTTP **429 (Gemini quota)**, 5xx, 401/403, 410, 202 progress and 404 are each said out loud in Sinhala + English in the player status bar and Diag. |
| Media3 | Extensionless HLS/DASH defaulting, progressive mp4/mkv/webm/flv/ts never fed to the HLS parser, cached `Typeface`, PiP auto-enter, `player.release()` on destroy. |
| Threading (v2.2) | Every worker/Cinemeta/CDN call runs on its own `Thread` (`si-subs`); the main looper only ever paints. No network, no disk, no `SrtLoader` parse on the UI thread. |
| Lifecycle (v2.2) | `onDestroy()` clears the Activity `Handler` queue **before** releasing ExoPlayer, so a late polish re-check can neither leak the Activity nor touch a released player. |
| Clipboard (v2.2) | The Option-C payload is budgeted in **UTF-8 bytes** (≤ 40 KB), not characters, so non-Latin drafts cannot overflow a Binder transaction. |

## License

MIT (this app's code). `libs/` are the unmodified Apache-2.0 jars AnantPlay itself ships;
`assets/fonts/NotoSansSinhala-Regular.ttf` is the SIL OFL font; `tools/core-lambda-stubs.jar`
is GPL-2.0 + Classpath Exception and is used at build time only. Every bundled artifact and its
license is listed in **THIRD_PARTY.md**.
