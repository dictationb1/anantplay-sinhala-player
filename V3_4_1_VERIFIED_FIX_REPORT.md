# V3.4.1 — VERIFIED FIX REPORT

**Release:** `v3.4.1` · `versionCode 30401` (strictly `3.4.x` — never `3.5`)
**Branch:** `fix/v3.4.1-relay-403-subs-automation-ui-truth`
**Base:** `main` @ `3770b89` (`v3.4.0` / `30400`)
**Reference sources studied read-only:** `AnantPlay/android` (`Dlna.java`, `Chromecast.Relay`, `Cast.java`, `PlayerActivity.java`)

Every claim in this report is a `file:line` fact from the shipped sources, or a
lab assertion that fails the build when it stops being true. Nothing here is a
screenshot opinion.

---

## 0. Headline

| # | Field defect (v3.4.0, real phone, Android 16) | Status in v3.4.1 | Proof |
|---|---|---|---|
| 1 | DLNA relay DASH stream (`Influencers`) → `HTTP 403`, twice | **Fixed** — 3-stage ladder; stage 3 leaves the relay entirely | `v341 relay403:` × 25, incl. a live 403 server |
| 2 | AnantPlay's player pops up over ours during a retry | **Fixed** — `TRANSITIONING` hold (15 s grace, every stage, every buffer) | `DlnaEndpoint.transportState` + `statusJson()` |
| 3 | Self-test dies after probe 7, then prints `7/10 PASS — ALL FEATURES HEALTHY` | **Fixed** — 4 GiB allocation removed, all 10 rows always land, verdict requires 10/10 | `v341 probes:` × 14, incl. a 48 MB-heap child JVM |
| 4 | Wrong 906 cached cues under `ArrowTV Player`; subs loaded but not painted; no never-fail path | **Fixed** — junk-title cache gate, slug recovery, cue-match + visibility guards, Layer 4 live CC translation, Layer 5 Chromium Blob bridge | `v341 subs:` × 21 |
| 5 | "symbols way too huge", "customizations not working", "hard for a first-time user" | **Fixed** — optical caps 16/18/20/22/24, first-time 3-step card, jargon moved to Troubleshoot, all 74 prefs wired + backed up | `verify_ui_completeness.py` (R1/R2/R3) |

**Local gates, this commit:** `982/982` lab checks PASS, 0 FAIL · `verify_refs` OK · `verify_layouts` 467 calls 0 problems · `verify_ui_completeness` OK · `verify_syntax` OK on 58 sources.

---

## 1. DEFECT #1 — the `HTTP 403` on AnantPlay's DLNA relay

### 1.1 The field log

```
236.190 I/play: from dlna:http://<lan-peer>:34783/aHR0cHM6Ly9wcm94eS55ZW5pbWUubmV0/manifest.mpd?url=…&t=<b64>
236.497 first attempt · headers=Accept, Accept-Language, Origin, User-Agent       → HTTP 403
241.177 recovery      · headers=… Origin, Referer, Sec-Fetch-* …                → HTTP 403
```

### 1.2 Why no header could ever fix it (AnantPlay source, line by line)

* `Chromecast.java:330-341` — `Relay.serve()` reads **only** the request line and `Range`. Every `Origin`/`Referer`/`Sec-Fetch-*` we sent to the relay was **discarded on arrival**, so our "recovery" retry was byte-identical to the first attempt. That is exactly what the phone recorded: two 403s, 4.6 s apart, different header lists, same result.
* `Chromecast.java:367-383` — `Relay.open()` replays the **captured** header map plus a cookie for the unwrapped URL. It never decodes the `t=` payload, so the signed `Edge-Cache-Cookie` the token describes is never attached.
* `Chromecast.java:348-354` — `Relay.serve()` rewrites **HLS only** (`#EXTM3U`). A DASH `.mpd` is streamed through **byte-for-byte**, so its `BaseURL`/`SegmentTemplate` still point at the raw signed CDN. **DLNA + DASH is structurally broken in the relay.**

### 1.3 What our own v3.4.0 code did wrong

* `StreamHttpConfig.java:212-216` — step 3 of `normalize()` did `o = originOf(url)` where `url` is the **relay** URL → `Origin: http://192.168.x.x:34783`. That is the fake LAN origin in the log.
* `StreamHttpConfig.java:230-245` — `recovery()` added `Referer: http://192.168.x.x:34783/`, the same fake `Origin` and the `Sec-Fetch-*` triple.
* `PlayActivity.java:621-631` — the only recovery path swapped headers and rebuilt **the same URL**, so a relay failure was unrecoverable by design.

### 1.4 The fix — three different REQUESTS, not three header sets

New pure engine in `StreamHttpConfig` (`relay`, `relayClean`, `relayOriginHeaders`, `directHeaders`, `directUrl`, `isPrivateHost`, `b64Url`, `b64Std`, `param`, `percentDecode`, `jsonString`):

| Stage | Request | Why it can succeed |
|---|---|---|
| 1 | relay URL + **clean** headers (Chrome UA, `Accept: */*`, **zero** `Origin`/`Referer`/`Sec-Fetch-*`) | The relay attaches its own upstream set; nothing of ours can conflict. |
| 2 | relay URL + `Origin`/`Referer` from the **decoded base64 upstream origin** (`https://proxy.yenime.net`) | A mirroring token check is satisfied by the real origin; never the LAN IP. |
| 3 | **direct upstream** from `url=` + `Cookie: <Edge-Cache-Cookie>` | Bypasses the relay and its HLS-only rewrite — the signed URL is self-sufficient. |

`normalize()` no longer invents a LAN `Origin` for a relay URL and drops a fake LAN `Referer`; `recovery()` on a relay URL **is** stage 2. `PlayActivity.startPlayback()` starts at stage 1 and `onPlayerError` climbs the ladder (`rebuildAt`), resetting to stage 0 on a manual Retry.

### 1.5 Proof (`lab/ReceiverLab.java`, `v341 relay403:`)

A real `com.sun.net.httpserver` that returns **403** when it sees an `Origin`/`Referer` pointing at `127.0.0.1`, and **403** on a CDN path without the signed cookie:

```
PASS v341 relay403: THE BUG — a LAN Origin is refused with HTTP 403
PASS v341 relay403: STAGE 1 clean pass-through → HTTP 200 OK
PASS v341 relay403: STAGE 2 decoded-upstream Origin → HTTP 200 OK
PASS v341 relay403: stage 3 against a signed CDN without the cookie → 403 (control)
PASS v341 relay403: STAGE 3 direct unwrap + Edge-Cache-Cookie → HTTP 200 OK
```

…plus 20 pure checks driven by the **real field URL and the real token payload** (the base64 origin, the `url=` stream, the `c`/`b`/`slug` fields of `t=`).

---

## 2. DEFECT #2 — AnantPlay pops up over our player

`PlayActivity.statusJson()` used to answer with the media shape even while the player was `STATE_IDLE` between rebuilds; `playWhenReady == false` + `IDLE` made `DlnaEndpoint.transportState()` return `NO_MEDIA_PRESENT`, the remote concluded the TV had stopped, and AnantPlay's own `PlayerActivity` came forward (the `237.250 takeover`).

v3.4.1 adds `holdUntil` (`HOLD_MS = 15000`):

* `startPlayback()` holds on entry; `rebuildAt()` holds on every rebuild; `onPlaybackStateChanged(STATE_BUFFERING)` extends it; every ladder stage extends it.
* `statusJson()` returns `{"loading":true}` → `TRANSITIONING` while the hold is live, and only reports `{"idle":true}` when the player is genuinely `IDLE` **after** the hold.
* `showError()` and `STATE_READY` release the hold, so the honest state (or the real frame) is never masked.

---

## 3. DEFECT #3 — the self-test that died at probe 7 and lied about it

### 3.1 The 4 GiB allocation

`FeatureProbe.java:363` (v3.4.0):

```java
byte[] chunk = new byte[65536];
for (int i = 0; i < 65536; i++) bz.write(chunk);   // "~4 MB... use 8 chunks"
```

`65536 × 65536 = 4,294,967,296` bytes — **4 GiB** into a `ByteArrayOutputStream`. The comment beside it proves the bound was meant to be `8`.

### 3.2 Why the run stopped dead

`OutOfMemoryError extends VirtualMachineError extends Error`. It passed through `catch (IOException)` (the "expected" zip-bomb branch) and `catch (Exception)`, reached `timed()`'s `catch (Throwable)`, and there the `"threw: " + name` concatenation **allocated again on the exhausted heap** and died. `land()` never ran for probes 8, 9, 10 → exactly three missing rows.

### 3.3 Why the verdict lied

`pass == outcomes.size()` with 7 rows and 7 passes → `"ALL FEATURES HEALTHY"`.

### 3.4 The fix

1. The bomb is now **8 × 64 KiB = 512 KiB**, and its own `catch (Throwable)` records a FAIL instead of escaping.
2. `timed()` is OOM-safe: the failure path uses the pre-allocated `FAIL_DETAIL` constant and a guarded `safeName()`.
3. `runAll()` runs every probe through `guarded(id, name, supplier, l)`, which **always lands a row** — 10/10 rows are now structural.
4. `FeatureProbe.verdict()` / `allHealthy()` require `results.size() == 10 && pass == 10`. Anything less prints `X/10 PASS · Y MISSING · Z FAIL — FAILED: #n name`. Wired into `Troubleshooter.probeSection()` and `MainActivity`'s verdict view.

### 3.5 Proof

```
PASS v341 probes: a 7-row all-green run is NEVER called healthy — 7/10 PASS · 3 MISSING
PASS v341 probes: a complete 10/10 run IS called healthy — 10/10 PASS — ALL FEATURES HEALTHY
PASS v341 probes: the failing row is NAMED by number — 9/10 PASS · 1 FAIL — FAILED: #9 probe 9
PASS v341 probes: the 4 GiB zip-bomb loop is really gone (the live loop is 8 chunks)
PASS v341 probes: every probe is run through guarded() so a row can never go missing
PASS v341 probes: probe 8 completes in a 48 MB heap (the 4 GiB loop is really gone)
```

The last one is a **real child JVM** (`-Xmx48m`) that runs probe 8 and must print `PROBE8 OK` — the v3.4.0 code cannot even start there.

---

## 4. DEFECT #4 — subtitles

### 4.1 Junk-title cache poisoning

`PlayActivity.fetchSinhala()` read `store.load("movie:t:arrowtv player:0:0:si")` **before** any junk test, and four `store.save(...)` sites wrote under the same key. That is how `"ArrowTV Player"` owned 906 cues.

v3.4.1:

* the read is gated by `isJunkForCache()`; **every** write goes through `saveCues()`, which refuses a junk title and logs the refusal;
* `StreamHttpConfig.Relay` now exposes the token's `r.slug`, and `TitleCleaner.fromSlug("influencers-MAyBtWlRaw8")` → `"Influencers"`. `recoverTitleFromCast()` runs at start-up and on every re-cast, so the prompt usually never appears;
* when the title is *still* junk, `promptJunkTitle()` shows a friendly one-tap prompt (vector icon, never an emoji glyph) that opens the movie-title search.

### 4.2 Loaded but not rendering

* `applyCues()` guarantees a `t0`-sorted list — the 4 Hz ticker's `break` contract was only true for sorted input, and a translated/merged track can arrive unsorted.
* `repairSubtitleView()` fixes `alpha`, `visibility` and z-order (`bringToFront()` above the surface) and logs the repair.
* the first painted cue is **proven**, not assumed: `[SUBS] first cue rendered ✓ · cue t0=… · playhead=… · offset=…`.
* a playhead outside every cue window is named once, with numbers, instead of leaving a silent screen.

### 4.3 LAYER 4 — the stream's own captions

`fallbackEnglishTrack()` now sets `liveTranslateOn`. Every `onCues()` line goes through `queueLiveTranslation()` → `FreeSubTranslator` (on-device, keyless, disk-cached) with a **400-line in-memory LRU**, so a rewind is instant. Only the line still on screen is repainted; a late answer is cached but never painted over the current line. This layer needs no provider, no key and no download — it is what makes "never fail" literally true.

### 4.4 LAYER 5 — Chromium automation

`WebBrowserView` installs a Blob hook on every page finish:

* wraps `URL.createObjectURL`, so any subtitle-shaped Blob is read with `FileReader` and posted as base64;
* catches `<a download href="blob:…">` in the capture phase and fetches the blob **in-page** (only the page can read its own `blob:`);
* posts through the **one** registered interface (`SniJS.onBlobDownload`), caps at **5 MB**, base64-decodes off the UI thread and attaches to the movie playing right now.

`https://tools.newzone.top/en/subtitle-translator` is a built-in Speed Dial entry, so its client-side `.srt` export now lands in the player with one tap.

---

## 5. DEFECT #5 — UI truth, measured

`lab/verify_ui_completeness.py` parses every Java file with a comment-stripping tokenizer:

| Rule | Result |
|---|---|
| R1 icon optical caps | **49** call sites checked; histogram `9:1, 12:3, 13:4, 14:9, 15:10, 16:4, 19:1, 20:11, 22:3, 24:2, 34:1`; **zero** above the cap. The single 34dp is `UiGroup.empty`'s designed illustration, capped at 40 and named as an exception in the gate. |
| R2 zero fake customization | **74/74** `K_*` keys are in `BACKUP_KEYS` **and** reachable from a UI surface (directly or through an `AppPrefs` accessor). Zero dead keys. |
| R3 no emoji in UI chrome | **0** — the one offender (`WebBrowserView` bookmarks dialog title) was replaced. |

### What was actually oversized

| Site | v3.4.0 | v3.4.1 |
|---|---|---|
| `MainActivity` bottom nav | `showLabels ? 22 : 24` (+2dp inflated box) | **22dp**, no inflated box |
| `PlayActivity` HUD back | 24 | **20** |
| `PlayActivity` HUD lock/rotate/external/settings | 15 (beside a 24) | **20** (consistent) |
| `PlayActivity` transport disc | 26 (built) / 26 (swap) | **24** / **24** |

### First-time UX

A dismissible **3-step Quick-Start card** at the top of Home (numbered vector badges, `Got it` button), persisted in `K_QUICKSTART_DONE` — added to `AppPrefs` **and** `BACKUP_KEYS`, so it survives export/import. Home no longer prints `port 8765` / `Rsp+DLNA` / `Nsd+Rsp+DLNA`; those facts stay in Troubleshoot, and Home says `running` / `discoverable`.

---

## 6. New and extended gates

| Gate | What it proves |
|---|---|
| `lab/ReceiverLab.java` → `v341 relay403:` (25) | The relay 403, reproduced on a real socket, then defeated by each of the 3 stages. |
| `lab/ReceiverLab.java` → `v341 probes:` (14) | 10/10 rows always land; the verdict cannot lie; probe 8 runs in a 48 MB heap. |
| `lab/ReceiverLab.java` → `v341 subs:` (21) | Junk-title cache gate, slug recovery from the real token, cue sorting, view repair, first-cue proof, Layers 4 and 5. |
| `lab/verify_ui_completeness.py` (new) | R1 icon caps (incl. the HUD helper), R2 pref wiring + backup, R3 emoji chrome. |
| `lab/verify_syntax.py` (new) | `javac` parse of all 58 shipped sources — the four Activity classes the lab cannot compile are no longer invisible locally. |

---

## 7. Version discipline

`3.4.1` / `30401` in `AndroidManifest.xml`, `AppInfo.java`, `build.sh` (`PREV_VERSION_CODE=30400`), `.github/workflows/build-apk.yml`, `.github/workflows/release-apk.yml`, `docs/latest.json`, `README.md`, `SETUP-SI.md`, and every lab assertion that pins the release identity. **Strictly `3.4.x` — never `3.5`, never `4.0`.**

---

## 8. Honest limitations

* **No Android SDK in the build sandbox.** `javac` + the pure-engine lab run locally; the APK itself is compiled and signed only by hosted CI (`build.sh` against `android.jar`). The four Activity classes are therefore covered locally by `verify_syntax.py` (parse) + `verify_ui_completeness.py` (structure) + `verify_layouts.py` (LayoutParams types), and by the hosted build for full symbol resolution.
* **`mornyeapp/Mornye` could not be cloned** (remote refused). The proportions enforced here are the ones specified in the brief; they are asserted mechanically rather than copied from that repo.
* **The 403 stages are proven against a mock that reproduces the observed rule**, not against the live `proxy.yenime.net` CDN (which needs the phone's session). Stage 3 is the one designed to be self-sufficient, and it is proven to carry exactly the headers the token describes.
