# AnantPlay Sinhala Player (SI Cast Receiver) v2.2.0

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
./lab/run_lab.sh     # the gating lab — 95 checks, byte-compatible with Cast.java
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
the APK on JDK 17 + **Android SDK 34 + build-tools 35.0.1** with **zero Gradle**, and uploads
`AnantPlay-Sinhala-Player-v2.2.0.apk` as a downloadable artifact.

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
lab/ReceiverLab.java         the 95-check gating lab (protocol + security + i18n regressions)
lab/BridgeLab.java           live worker E2E (best effort)
docs/CAST-VERIFICATION.md    every AnantPlay file:line this is built on
THIRD_PARTY.md               licenses of every bundled jar + the Sinhala font
SETUP-SI.md                  the Sinhala setup checklist
```

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
