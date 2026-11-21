# The code-verified audit trail — AnantPlay Cast Receiver v2.0.0

Everything below is verified against **`AnantPlay/android` @ `be74df2` (tag v2.23.1)** — the exact
commit the user's phone runs — and against AOSP sources. Read-only; not one byte of upstream code
is included in this repo.

---

## 1. Root cause of the 18:12 failure ("Not working. Tap for info.")

| Step | Evidence |
|---|---|
| The string is `accessibility_summary_state_stopped` | AOSP `packages/apps/Settings/.../AccessibilitySettings.java:268-272` — `if (serviceEnabled && info.crashed) return context.getText(R.string.accessibility_summary_state_stopped);` |
| `info.crashed` means the bound service **died and is awaiting restart** | AOSP `frameworks/base/core/java/android/accessibilityservice/AccessibilityServiceInfo.java:528-534` ("Whether or not the service has crashed and is awaiting restart"), set from `userState.mCrashedServices` — `AccessibilityManagerService.java:2596-2601` |
| The process death | HyperOS's powerkeeper / Security (cleaner, "Clear all", battery manager) **force-stops** background apps; a force-stop sets the package stopped-state, so the enabled accessibility service cannot rebind → enabled-but-crashed stays. Xiaomi-specific mechanism reported by llcloud (Oct 2026): "A force-stop switches off the app's accessibility service, and the app cannot switch it back on itself." |
| Why v1 died within a minute | v1 companion had **no foreground service** (its own audit §4.5 gambled on "the system keeps alive while enabled"), **no MIUI Autostart** (fresh sideload = off by default), **battery saver default-restricted**, **not locked in recents** — a textbook powerkeeper victim. |
| Why KDE Connect showed the same, ESET didn't | Same environmental killer: KDE Connect's accessibility process gets no MIUI exemptions either; ESET keeps a **permanent foreground service** + its setup walks users through the exact MIUI permissions. |
| Not a v1 code crash | `SinhalaOverlayService.onServiceConnected()` is defensive (`Typeface.createFromAsset` and every `WindowManager.addView` are wrapped; `addOverlays()` catches `Throwable`). Nothing in the enable-time path crashes on Android 14–16 semantics. **The service was killed, not crashed** — and "no overlay during The Odyssey" is fully explained by the process being dead. |

**Conclusion:** an AccessibilityService architecture is *hostage* to vendor survival rules on
HyperOS. The cast receiver architecture removes the hostage entirely.

## 2. The Cast protocol this app speaks (all `Cast.java` unless noted)

- Receiver = HTTP server + mDNS: `Cast.java:65-84` (`start()`, port 8765, announces
  `_anantplay._tcp` + old `_fmhyplay._tcp` as `"AnantPlay on <name>"` via `NsdManager`).
- Hand-rolled HTTP: `Cast.answer()` `:103-126` (request line, `content-length`, ≤256 KB body,
  `HTTP/1.0 200` + `Content-Type: application/json` + `Content-Length`); `Cast.line()` `:128-132`.
- `/play` payload: `Cast.describe()` `:342-354` serializes **PlayerActivity's whole intent** —
  `url`, `headers` (JSON string, parsed at `PlayerActivity.java:1087-1091`), `title`, `pageTitle`,
  `site`, `pageUrl`, `imdb`, `series`, `season`, `episode`, `dash`, `mime`, `subs[]` (side-loaded
  subtitle URLs, consumed at `PlayerActivity.java:1112-1117`) — plus `at` (position, honored at
  `PlayerActivity.java:1234`), `cookie` (`:351-353`), and `castTo()` adds `live` + `title`
  (`PlayerActivity.java:2518`).
- `/play` semantics: `Cast.play()` `:143-177` — http/https link check (`:144-145`), opens the
  player, waits ≤5 s (`:171-176`), answers `{ok:true}` or `{ok:false, why:"blocked"|"did not open"}`;
  `blocked` maps to AnantPlay's "blocked" message (`:331`), and `canOpen()` = `canDrawOverlays`
  (`:91-92`) is the background-activity-start exemption the receiver needs for the one-tap takeover.
- `/status` shape: `Cast.status()` `:185-193` — `{title,pos,dur,playing,live}` (live: `dur==TIME_UNSET
  || isCurrentMediaItemLive()`), `{loading:true}`, `{idle:true}`.
- `/do` actions: `Cast.act()` `:195-206` — `toggle|seek|stop|vol+|vol-` (`vol±` adjusts the
  RECEIVER's `STREAM_MUSIC`).
- The phone side discovers from app start: `App.java:84-88` (`tv ? Cast.start : Cast.discover`), so
  the cast chip is ready (`PlayerActivity.java:999` gates it on `Cast.any()`); the cast list puts
  AnantPlay-receivers FIRST, labelled `"%1$s - AnantPlay (Recommended)"` (`strings_player.xml:94`,
  `Cast.targets()` `:299-307`).
- After a successful cast the phone PAUSES its own player (`PlayerActivity.java:2531`), shows
  `Cast.Remote` (polls `/status` 1 Hz, `Cast.java:383-389`), and **Stop casting resumes AnantPlay's
  own player at the receiver's last position** (`PlayerActivity.java:2546-2555`) — the loop this
  receiver closes deliberately: our player finishing → `/status` = `{idle:true}` → the Remote exits
  → AnantPlay resumes. Same-device UX: the receiver takes the screen back ~0.8 s after `/play`
  (AnantPlay's Remote keeps working underneath).
- **Phones never run the receiver in official AnantPlay**: `App.java:71` — `tv = !flavor.equals
  ("android") && looksLikeTv`, and the "android" flavor is what the phone APK builds
  (`build.sh` pack step). Port 8765 is free on the user's phone.

## 3. Player parity (why anything AnantPlay plays, we play)

- Same engine jars: AnantPlay's `libs/` = media3 1.11.1 (+guava 33.3.1, concurrent-futures,
  failureaccess, annotation-experimental, androidx-webkit) — this repo ships the same subset.
- Same DataSource recipe: `DefaultHttpDataSource` with the handed-over headers + `Cookie`,
  `setAllowCrossProtocolRedirects(true)`, `DefaultDataSource.Factory` wrapping it
  (`PlayerActivity.java:1100-1140` mirrors).
- Same mime defaulting: `mime` extra → `dash` → `.flv` → M3U8 (`PlayerActivity.java:1107-1110`).
- Same orientation/PiP shape as `PlayerActivity` (`AndroidManifest.xml:49-51`).
- No DRM anywhere in AnantPlay (repo-wide grep — nothing to replicate).

## 4. Why the other candidates lost (each verified, not guessed)

- **Sources (`index.json`)**: `Sources.java:22,28-32` — a source is one of exactly `sites` /
  `playlist` / `deeplinks`: **downloaded data lists** (JSON/M3U, `parseIndex` `:55-86`,
  `fetch` `:199-210`). No code runs, no plugins, no subtitle providers; a source cannot touch
  another site's `PlayerActivity`. Impossible by design.
- **Subtitles inside AnantPlay**: its picker searches OpenSubtitles/SubDL by language
  (`Subs.search` `:59-79`) with the Stremio addon **hardcoded** (`Subs.java:14`) — a user cannot
  add their own subtitle source. Sinhala *is* in the language list (`:25-26`) but legacy
  OpenSubtitles has almost no Sinhala — the exact gap the worker fills.
- **Downloads + sibling `.srt`**: `DownloadsActivity.play()` `:208-212` opens `PlayerActivity`
  with an **empty** `subs` list — no sibling-`.srt` autoload exists anywhere in the player.
- **Quick Settings tile / SYSTEM_ALERT_WINDOW without accessibility** (`Candidate 4`): cannot read
  the title or the stream URL of another app's player — the whole premise dies; kept only as the
  receiver's arming shortcut (`CastTileService`).
- **Shizuku / wireless ADB** (`Candidate 5`): cannot prevent a vendor force-stop, needs re-pairing
  after every reboot, and still fights powerkeeper forever. A power-user toy, not an architecture.
- **Hardened accessibility companion** (`Candidate 1`): buildable (ESET proves it can survive with
  FGS + MIUI permissions) but remains the most invasive permission in Android, hostage to every
  MIUI toggle, battery-heavier, and subtitle timing stays heuristic (cue-matching/clock-lock).
  Its hardening patterns — foreground service, guided MIUI checks, diagnostics log, test-now
  button — were **merged into the winning app** instead.

## 5. Same-device mDNS — the make-or-break, honestly

`NsdManager` registration and discovery both run in the system's mDNS stack on the same host;
cross-app same-device discovery is the standard NSD workflow. Constraints handled openly in-app:

- Wi-Fi must be **connected** (records resolve to the interface address — `Cast.resolve()` drops
  null hosts, `Cast.java:266`). Hotspot-on counts. **The app's Self-test proves it per-device**
  (`MainActivity.NsdAnnouncerTest.discoverSelf`) and shows ✅/❌ with guidance, so the user is
  never left guessing.
- Announcements are re-registered on network changes + retries (`NsdAnnouncer`), and the receiver
  also registers the legacy `_fmhyplay._tcp` type for compatibility.

## 6. Live-bridge findings from this build (2026-11-21)

- **v1 latent bug fixed**: `BridgeApi.paired()` percent-encoded the job id's colons (`%3A`); the
  worker's route matches **literal** colons (its own bodies and tests use
  `/api/paired/movie:tt…:0:0.json` — `worker/src/index.js:244`, `tests/pipeline.test.js:221`), so
  v1's live polling fell through to 404. Fixed; and the receiver now primarily polls the
  **deterministic** `/sub/<token>.srt` (200 .srt | 202 progress | 404 → re-ensure) instead of the
  isolate-sensitive `/api/paired` — the deployed worker runs in **memory mode** (`pipeline.js:57-68`,
  per-isolate Map), which the lab caught live.
- The deployed worker's **Gemini key is currently quota-exhausted** (`429 quota exceeded` on
  The Odyssey tt33764258): the human-Sinhala and local-.srt paths are unaffected; auto-translation
  returns when the quota resets or a new `GEMINI_API_KEY` is set.
- `The Odyssey` resolves correctly (Cinemeta → **tt33764258, 2026**) through the deployed worker.

## 7. Verification summary

- **Lab**: 23/23 checks pass (`lab/run_lab.sh`) — protocol byte-compatibility, guard rails
  (256 KB cap, bad-link rule, LAN-source lock, unknown paths), /status·/do state machine, SRT
  round-trip, JSON round-trip.
- **APK**: builds with plain SDK tools (aapt2/javac/d8/zipalign/apksigner), **apksigner verify:
  v2+v3 pass**, manifest dump confirms package/version/minSdk 26/targetSdk 34, `connectedDevice`
  FGS type, PiP player, tile service, boot receiver, **zero accessibility declarations**.
- CI (`.github/workflows/build-apk.yml`) reproduces lab + APK on every push.
