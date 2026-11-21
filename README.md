# AnantPlay Sinhala Player (SI Cast Receiver) v2.0.0

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
./lab/run_lab.sh     # the cast-protocol lab: 23 checks, byte-compatible with Cast.java
```

The lab drives `ReceiverServer` with the exact requests official AnantPlay's
`Cast.call()`/`describe()` produce, plus the SRT round-trip through the very code the APK ships.

## The user-facing flow

1. Install the APK, open it once → **Start receiver** (a persistent "අ" notification appears).
2. Grant **Display over other apps** (one system toggle — for the one-tap screen takeover) and,
   on Xiaomi, the MIUI permissions the screen walks you through.
3. In official AnantPlay, play any movie on any site → tap the **cast** chip →
   **සිංහල Player - AnantPlay (Recommended)**.
4. The movie opens here with Sinhala subs. Stop casting / close → AnantPlay's own player resumes
   exactly where you were.

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
  BridgeApi/Cue/MiniJson/SrtLoader/SubtitleStore   the proven v1 engines (pure Java)
lab/ReceiverLab.java         the 23-check cast-protocol lab
lab/BridgeLab.java           live worker E2E (best effort)
docs/CAST-VERIFICATION.md    every AnantPlay file:line this is built on
SETUP-SI.md                  the Sinhala setup checklist
```

## License

MIT (this app's code). `libs/` are the unmodified Apache-2.0 jars AnantPlay itself ships;
`assets/fonts/NotoSansSinhala-Regular.ttf` is the SIL OFL font.
