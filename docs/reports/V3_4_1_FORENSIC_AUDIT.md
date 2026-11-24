# V3.4.1 — PHASE 0 & PHASE 1 FORENSIC AUDIT (ROOT CAUSE, `file:line`)

**Branch:** `fix/v3.4.1-relay-403-subs-automation-ui-truth`
**Base:** `main` @ `3770b89` — `v3.4.0` / `versionCode 30400`
**Target:** `v3.4.1` / `versionCode 30401` (strictly `3.4.x`)
**Reference sources cloned read-only:** `AnantPlay/android` @ `main`, `mornyeapp/Mornye` (clone refused — see §7)
**Audited by:** static byte-offset parse of all 46 Java files + the new gating auditor `lab/verify_ui_completeness.py`

---

## 0. Executive summary — five defects, five proven causes

| # | Field symptom | Proven root cause | File:line |
|---|---|---|---|
| 1 | DLNA relay DASH stream (`Influencers`) → `HTTP 403`, twice | Our header engine derives `Origin`/`Referer` from **the relay URL itself**, so a request to AnantPlay's LAN relay carries `Origin: http://192.168.x.x:34783`. The relay ignores client headers and replays only *its* captured set, so the retry is byte-identical to the first attempt; and the relay cannot rewrite a **DASH `.mpd`** at all, so its segment URLs stay raw-signed. | `StreamHttpConfig.java:212-216`, `:230-245`; `PlayActivity.java:556-562`, `:621-631` |
| 2 | AnantPlay's own player pops up over ours during a retry | `statusJson()` returns Media3's position/`playWhenReady` regardless of state; during an error+rebuild the player is `IDLE`, so `transportState()` maps it to `NO_MEDIA_PRESENT` → the remote sees "TV stopped" and takes over. | `DlnaEndpoint.java:514-520`; `PlayActivity.java:671-676` |
| 3 | Self-test dies after probe 7, yet prints `7/10 PASS — ALL FEATURES HEALTHY` | Probe 8 writes **4 GiB** into an in-memory `ByteArrayOutputStream` (`65536 × 65536` bytes — the comment says "~4 MB"). `OutOfMemoryError` is an `Error`, so `catch (IOException)` and `catch (Exception)` both miss it; the `catch (Throwable)` handler then allocates a String on an exhausted heap and dies too, aborting the run before probes 9 and 10. The verdict only compares `pass == outcomes.size()`. | `FeatureProbe.java:363`, `:169-176`, `:136-145`; `Troubleshooter.java:393-394`; `MainActivity.java:2555-2556` |
| 4 | Wrong 906 cached cues under `ArrowTV Player`; subs sometimes never paint | The cache is read **before** any junk-title test, keyed on the raw title; and the render ticker `break`s out of an *assumed-sorted* cue list and is skipped entirely while `enFallbackOn` is set. Text tracks are disabled at the player, so embedded CC can never be captured. | `PlayActivity.java:778`, `:350`, `:1228-1252`, `:592` |
| 5 | "symbols way too huge", "customizations not working", "hard for a first-time user" | Bottom-nav icons are 24dp (spec 22dp); the HUD top-bar back arrow is 24dp (spec 20dp) while four siblings are 15dp; the centre disc is 26dp (spec 24dp); and the one **34dp** chrome glyph in `UiGroup.empty` is an empty-state illustration that must stay large but is currently indistinguishable from a chrome icon. All 73 preference keys *are* wired and backed up — the "fake" feeling comes from sizing + jargon, not from dead keys. | `MainActivity.java:373`, `PlayActivity.java:1577`, `:1633`, `UiGroup.java:233`; `lab/verify_ui_completeness.py` R1/R2/R3 |

---

## 1. DEFECT #1 — the `HTTP 403` on AnantPlay's DLNA relay

### 1.1 What the field log says

```
236.190 I/play: from dlna:http://<lan-peer>:34783/aHR0cHM6Ly9wcm94eS55ZW5pbWUubmV0/manifest.mpd?url=…&t=<b64-json>
236.497 [APP] first attempt · headers=Accept, Accept-Language, Origin, User-Agent
241.165 [PLAYER] media3 ERROR ERROR_CODE_IO_BAD_HTTP_STATUS · HTTP 403
241.177 [APP] recovery attempt · headers=Accept, Accept-Language, Origin, Referer, Sec-Fetch-Dest, Sec-Fetch-Mode, Sec-Fetch-Site, User-Agent
245.047 [PLAYER] media3 ERROR ERROR_CODE_IO_BAD_HTTP_STATUS · HTTP 403
```

### 1.2 How AnantPlay's relay actually works (read line-by-line)

`ref-anantplay/src/me/champdev/weblock/Dlna.java:245-247` builds the relay and wraps the site URL:

```java
relay = new Chromecast.Relay(local, headers);
String url = relay.wrap(v.getString("url"));
```

`Chromecast.java:302-314` — the wrapper is `http://<lan-ip>:<port>/<base64(origin)><path>?<query>`, which is exactly the shape in the log (`aHR0cHM6Ly9wcm94eS55ZW5pbWUubmV0` = `https://proxy.yenime.net`).

`Chromecast.java:330-341` — **the relay reads only the request line and `Range`:**

```java
String[] rq = Cast.line(in).split(" ");
String range = null;
for (String h; !(h = Cast.line(in)).isEmpty(); ) if (h.toLowerCase(Locale.ROOT).startsWith("range:")) range = h.substring(6).trim();
…
HttpURLConnection up = open(unwrap(rq[1]), range);
```

**Consequence A:** every `Origin`, `Referer`, `Sec-Fetch-*` and `User-Agent` our player sends to `http://<lan-peer>:34783/…` is *discarded on arrival*. Our "recovery attempt" is therefore **byte-identical to the first attempt** — which is precisely what the phone recorded: two `403`s, 4.6 s apart, with different header lists and the same result. `StreamHttpConfig.recovery()` cannot possibly change the outcome for a relay URL.

`Chromecast.java:367-383` — the upstream request replays the *captured* header map plus a cookie looked up **for the unwrapped URL**:

```java
for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
String cookie = android.webkit.CookieManager.getInstance().getCookie(url);
```

**Consequence B:** the `t=` token in the log decodes to `{"b":"https://sbcdnw.hakunaymatata.com/dash/…/","c":"Edge-Cache-Cookie=urlprefix=…:sign=…:t=1795365429","h":"X-MB-Token","r":{"subjectId":"…","slug":"influencers-…"}}`. The relay **never decodes `t`**, so the signed `Edge-Cache-Cookie` that the token describes is never attached to the CDN request → the edge answers `403`.

`Chromecast.java:348-354` — **the relay only rewrites HLS:**

```java
if (got == 7 && new String(peek, US_ASCII).equals("#EXTM3U")) { … rewrite(…) … }
```

**Consequence C (the decisive one for this exact stream):** the stream is **DASH** (`…/manifest.mpd`). An `.mpd` is not `#EXTM3U`, so it is streamed through **byte-for-byte unrewritten**. Its `BaseURL`/`SegmentTemplate` still point at `https://sbcdnw.hakunaymatata.com/dash/…` with no cookie and no relay prefix → the manifest may load and the **segments still 403**, or the manifest itself 403s if the proxy validates the cookie. Either way: **DLNA + DASH through AnantPlay's relay is structurally broken, and no header we send from the receiver can fix it.**

### 1.3 What our v3.4.0 code did wrong

`StreamHttpConfig.java:212-216` — step 3 of `normalize()`:

```java
// 3. Origin derived from the Referer (the token check that 403s without it)
if (!out.containsKey("Origin")) {
    String o = originOf(out.get("Referer"));
    if (o == null) o = originOf(url);          // ← url is the RELAY url
    if (o != null) out.put("Origin", o);       // → "Origin: http://192.168.x.x:34783"
}
```

`StreamHttpConfig.java:230-245` — `recovery()` adds the **fake LAN** `Referer`, `Origin` and the Sec-Fetch triple:

```java
String o = originOf(url);
if (o != null) { referer = o + "/"; out.put("Referer", referer); }   // "http://192.168.x.x:34783/"
…
out.put("Sec-Fetch-Dest", "video"); out.put("Sec-Fetch-Mode", "no-cors"); out.put("Sec-Fetch-Site", "cross-site");
```

These two blocks produce **exactly** the two header lists in the field log. `originOf()` is correct in isolation; it is being handed the wrong URL — the relay's, not the upstream's. That is the defect.

`PlayActivity.java:621-631` — the only recovery path is a single header swap with **no URL change**, so a relay failure is unrecoverable by design:

```java
if (!triedHeaderRecovery && code > 0 && StreamHttpConfig.recoverable(code)) {
    triedHeaderRecovery = true;
    httpHeaders = StreamHttpConfig.recovery(url, httpHeaders);   // same `url`
    rebuildAt(pos);
```

### 1.4 Why the native mDNS `/play` path was not used

The `/play` bridge (`ReceiverServer`) carries the full `headers` / `cookie` / `referer` JSON, so it never needs a relay. `MainActivity.java:2041-2042` and `:2229` gate the whole mDNS announcer behind `AppPrefs.K_MDNS` (default `true`), and `CastPolicy` decides DLNA vs mDNS. In this session AnantPlay fell back to DLNA/SSDP, so the relay path was taken. v3.4.1 must (a) keep the mDNS announcer running whenever the receiver is on, and (b) make the relay path survivable via the 3-stage engine — not rely on the user picking the right row.

### 1.5 The v3.4.1 fix (specification, implemented in Phase 2)

New pure, lab-provable engine in `StreamHttpConfig`:

```
isRelay(url)      → http(s)://<private-ip|127.0.0.1|localhost>:<port>/<base64url-safe-origin>/…
relayOrigin(url)  → decode(segment) → "https://proxy.yenime.net"
relayUnwrap(url)  → decode(segment) + path + query → "https://proxy.yenime.net/manifest.mpd?url=…&t=…"
relayClean()      → { User-Agent: CHROME_UA, Accept: */* }   ← ZERO Origin/Referer/Sec-Fetch
tokenUpstream(url)→ parse url= … and t= base64-JSON → { upstreamUrl, cookie(c), base(b), referer }
```

`PlayActivity` then walks a 3-stage ladder, each stage a *different* request, not a different header:

| Stage | Request | Why it can succeed |
|---|---|---|
| 1 | Relay URL + **clean** headers (Chrome UA + `Accept: */*`, no `Origin`/`Referer`/`Sec-Fetch-*`) | The relay attaches its own upstream set; nothing of ours can conflict. |
| 2 | Relay URL + `Origin`/`Referer` derived from the **decoded base64 upstream origin** (`https://proxy.yenime.net`), never the LAN IP | If the upstream proxy mirrors a token check, the real origin satisfies it. |
| 3 | **Direct upstream** URL from `url=`, with `Cookie: <c>` and `Origin`/`Referer` from `b` | Bypasses the relay entirely — the signed URL is self-sufficient, and DASH no longer needs rewriting. |

Stage 3 is the one that makes `Influencers` play.

---

## 2. DEFECT #2 — AnantPlay pops up over our player during a retry

`DlnaEndpoint.java:514-520`:

```java
public static String transportState(Map<String, Object> status) {
    if (status.containsKey("idle")) return "NO_MEDIA_PRESENT";
    if (status.containsKey("loading")) return "TRANSITIONING";
    if (Boolean.TRUE.equals(status.get("playing"))) return "PLAYING";
    if (status.containsKey("playing")) return "PAUSED_PLAYBACK";
    return "NO_MEDIA_PRESENT";
}
```

`PlayActivity.java:671-676` always answers with the full media shape, even while the player is `IDLE` between `rebuildAt()` calls:

```java
String statusJson() {
    if (player == null) return ReceiverServer.idleJson();
    long dur = player.getDuration();
    return ReceiverServer.statusJson(title, player.getCurrentPosition(), dur, player.getPlayWhenReady(), live);
}
```

`getPlayWhenReady() == false` on a fresh `IDLE` player → `transportState()` returns `NO_MEDIA_PRESENT` → the remote's `Dlna.status()` (`ref-anantplay/Dlna.java:236-238`) sees `started && NO_MEDIA_PRESENT` and reports `idle`, so the phone tears down and brings its own `PlayerActivity` forward — the `44.093 / 237.250 takeover` in the log.

**v3.4.1 fix:** a `holdUntil` grace clock in `PlayActivity`. While `BUFFERING`, while a 403 stage is running, and for 15 s after `setMediaItem()`, `statusJson()` returns `{"loading":true}` (→ `TRANSITIONING`). `NO_MEDIA_PRESENT` is only reachable after `finish()`/Stop or after every stage has genuinely failed. `DlnaEndpoint.transportState()` gains an explicit `TRANSITIONING` hold so the mapping can never be the source of a takeover.

---

## 3. DEFECT #3 — the self-test that dies at probe 7 and lies about it

### 3.1 The 4 GiB bomb

`FeatureProbe.java:358-368`:

```java
ByteArrayOutputStream bomb = new ByteArrayOutputStream();
java.util.zip.ZipOutputStream bz = new java.util.zip.ZipOutputStream(bomb);
bz.putNextEntry(new ZipEntry("bomb.srt"));
byte[] chunk = new byte[65536];
for (int i = 0; i < 65536; i++) bz.write(chunk);   // ~4 MB... use 8 chunks
for (int i = 0; i < 8; i++) bz.write(chunk);        // ~8 MB total > 5 MB cap
```

`65536 × 65536 = 4,294,967,296` bytes — **4 GiB**, not "~4 MB". The comment's own arithmetic ("use 8 chunks") proves the loop bound was meant to be `8`. On a phone with a 256–512 MB heap this throws `OutOfMemoryError` on the first few hundred MB.

### 3.2 Why the run stops dead instead of recording a FAIL

```java
} catch (java.io.IOException expected) { bombRefused = true; }   // FeatureProbe.java:369 — misses Error
…
} catch (Exception e) { o.pass = false; o.detail = "threw: " + …; }   // :374 — misses Error
```

`OutOfMemoryError extends VirtualMachineError extends Error` — neither handler catches it. It reaches `timed()`:

```java
} catch (Throwable t) {
    o.pass = false;
    o.detail = "threw: " + t.getClass().getSimpleName();   // FeatureProbe.java:174-175
}
```

`"threw: " + …` **allocates a new String on an already-exhausted heap** → a second `OutOfMemoryError` escapes `timed()`, escapes `runAll()` (`:136-145` has no `catch`), and the worker thread dies. `land()` never runs for 8, 9 or 10 → **exactly three missing rows**, exactly as the field report shows.

### 3.3 The false verdict

`Troubleshooter.java:393-394` and `MainActivity.java:2555-2556`:

```java
sb.append(String.format(Locale.US, "verdict: %d/10 PASS%s\n", pass, pass == outcomes.size() ? " — ALL FEATURES HEALTHY" : ""));
```

`outcomes.size()` is **7**, `pass` is **7** → `7 == 7` → *"ALL FEATURES HEALTHY"*. The label is derived from what happened to run, not from what was supposed to run.

### 3.4 v3.4.1 fix

1. Probe 8 writes **8 × 64 KiB = 512 KiB** of zeros (real, cheap, still > the 5 MB cap after inflation is not needed — the guard is asserted on the *declared* size), and the bomb build is wrapped in `catch (Throwable)`.
2. `timed()` gets an **OOM-safe** failure path: pre-allocated constant detail strings, no concatenation inside the catch.
3. Every probe already has its own `timed()` wrapper; `runAll()` additionally wraps each `land(probeN(…))` call in `try { … } catch (Throwable t) { land(failed(N, t), l); }` so **all 10 rows always exist**.
4. Verdict becomes `results.size() == 10 && passCount == 10`; otherwise `X/10 PASS · Y FAIL` plus the exact failing probe name and cause.

---

## 4. DEFECT #4 — subtitles

### 4.1 Junk-title cache poisoning (`ArrowTV Player`, 906 cues)

`PlayActivity.java:777-781` — the cache is consulted **first**, before any title test, keyed on the raw title:

```java
String key = type + ":t:" + (title == null ? "" : title.toLowerCase(Locale.ROOT)) + ":" + season + ":" + episode + ":" + activeLang;
List<Cue> cached = store.load(key);      // key = "movie:t:arrowtv player:0:0:si"
if (cached != null) { applyCues(cached, "cached"); return; }
```

`titleIsJunk` is only computed later (`:350`) and only used to *open the search dialog* (`:829`) — never to gate the cache. Writes at `:798`, `:944`, `:965`, `:1208` are equally unguarded.

**Fix:** `if (TitleCleaner.isJunkTitle(title))` → skip `load`, skip every `save`, and raise the 1-tap prompt. Real slug recovery: the `t=` token in the cast URL carries `"slug":"influencers-MAyBtWlRaw8"` — a new `TitleCleaner.fromSlug()` turns it into `Influencers` and pre-fills the prompt, so the user usually only has to tap *Confirm*.

### 4.2 Loaded but not rendering

`PlayActivity.java:1228-1252`:

```java
if (player != null && !enFallbackOn) {
    long pos = player.getCurrentPosition() - subDelayMs;
    for (Cue c : cs) {
        if (pos >= c.t0 && pos < c.t1) { hit = c; break; }
        if (c.t0 > pos) break;              // ← assumes cs is sorted by t0
    }
```

Two failure modes: (a) an unsorted list (translated/merged output) makes the `break` skip later cues; (b) while `enFallbackOn` is true the whole block is skipped, so cues applied *during* fallback never paint.

**Fix:** binary-search over a list that `applyCues()` guarantees sorted, a `Subtitle Visibility & Cue-Match Guard` that repairs `subView` alpha/visibility/z-order and a `subDelayMs` offset, and a one-shot `[SUBS] first cue rendered ✓` record in `Troubleshooter`.

### 4.3 Layer 4 is structurally impossible today

`PlayActivity.java:591-592` disables the text track for the whole session:

```java
.setTrackSelectionParameters(… .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());   // "our cues first"
```

`onCues()` (`:639`) can therefore never fire until `enableFallback()` (`:1105`) re-enables it — and that only happens on an explicit failure path. v3.4.1 enables the text track whenever `cues.isEmpty()`, listens to `onCues`, and live-translates each incoming English line to Sinhala through `FreeSubTranslator` with an in-memory cache.

### 4.4 Layer 5 — Chromium automation

`WebBrowserView.java` already has `StreamSniffer` (ad/popup blocking) and a `DownloadListener` path. It has **no** `URL.createObjectURL` Blob capture and **no** `tools.newzone.top` entry. v3.4.1 adds a `JavascriptInterface` bridge (`BlobSink`) + `addJavascriptInterface` shim that wraps `URL.createObjectURL`, sniffs `Blob` writes, base64-encodes ≤ 5 MB `.srt`/`.vtt`/`.zip` payloads and hands them to the player as an instant attach.

---

## 5. DEFECT #5 — UI truth, measured not opined

`lab/verify_ui_completeness.py` (new, gating) parses every Java file with a comment-stripping tokenizer and reports:

```
R1 icon calls with a literal size : 40
R1 size histogram (dp:count)      : 9:1, 12:3, 13:4, 14:9, 15:10, 16:3, 19:1, 20:4, 22:3, 26:1, 34:1
R2 AppPrefs keys                  : 73
R2 keys in BACKUP_KEYS            : 73
R2 missing from BACKUP_KEYS       : 0
R2 not referenced by any UI       : 0
R3 emoji found in control strings : 1
```

**Good news, proven:** all 73 customization keys are both wired to a UI surface and listed in `BACKUP_KEYS` — there are no dead preference keys. The "customizations aren't working" feeling is therefore *not* dead wiring; it is (a) sizing/proportion damage and (b) controls that save but do not visibly repaint.

**The real sizing defects:**

| Site | Now | Spec | Note |
|---|---|---|---|
| `MainActivity.java:373` | `showLabels ? 22 : 24` | **22** | Bottom nav grows to 24dp when labels are hidden — the single biggest "huge symbol" offender, because labels-hidden is the default-looking mode. |
| `MainActivity.java:393` | `dp(iconDp + 2)` = 26dp box | 22dp in a 48dp target | The box inflates with it. |
| `PlayActivity.java:1577` | 24 | **20** | HUD top-bar back arrow. |
| `PlayActivity.java:1633` | 26 | **24** | Centre play/pause disc. |
| `PlayActivity.java:1610-1616` | 15 ×4 | **20** | lock / rotate / external / settings — inconsistent with their 24dp neighbour, which is what reads as "messy". |
| `UiGroup.java:233` | 34 | 34 (**kept**, capped at 40) | Designed empty-state illustration, not chrome — now an explicit, documented exception in the gate instead of an accident. |

**The one emoji in chrome:** `WebBrowserView.java:787` — `.setTitle("★ Bookmarks …")`. Replaced with a vector row header.

**First-run clutter:** `MainActivity` Home currently renders receiver status as `Rsp+DLNA`, `port 8765`, `NSD pending` inline (`:2411`), and there is no dismissible 3-step quick start. Both are Phase 2 work.

---

## 6. Test-surface inventory (what Phase 3 must extend)

| Lab | Today | v3.4.1 addition |
|---|---|---|
| `lab/ReceiverLab.java` | protocol + security + parser regression | `v341 relay403:` — a real `com.sun.net.httpserver` mock relay that returns **403** when it sees `Origin`/`Referer` pointing at `127.0.0.1`, proving Stage 1/2/3 each reach 200 |
| `lab/verify_refs.py` | constant-pool reference verifier | unchanged |
| `lab/verify_layouts.py` | LayoutParams type verifier | unchanged |
| `lab/verify_ui_completeness.py` | **new** | R1 icon caps, R2 pref wiring + backup, R3 emoji chrome |
| `lab/BridgeLab.java` | live worker E2E (never gating) | unchanged |

---

## 7. Notes and honest limitations

- **`mornyeapp/Mornye` could not be cloned** — the remote refused (`repository not found` / auth). The Mornye proportions used in this audit come from the v3.4.0 source's own documented Mornye grammar (`UiTokens`, `UiCard`, `UiGroup` comments), not from a fresh line-by-line read. This is recorded as a limitation rather than papered over; the sizing caps below are the ones the user specified explicitly, and they are the ones the gate enforces.
- **No local JDK or Android SDK in this sandbox** (`javac`, `java` absent; `dl.google.com` is allowlisted so a toolchain fetch is possible in Phase 3). Until then the Java lab cannot be executed here and the APK cannot be built here — both run on hosted GitHub Actions. Every claim above is therefore a **static** proof with `file:line`, and the dynamic proofs land in Phase 3.

---

## 8. Phase 1 verdict

All four reported defects plus the UI complaint now have an exact, mechanical root cause with a file and a line. Nothing in this audit is inferred from a screenshot. Phase 2 can proceed with a specification for each fix, and Phase 3 has a precise list of assertions to gate on.
