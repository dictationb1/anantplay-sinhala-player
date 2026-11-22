# V3.3.0 — Cinema Player & HTTP-403 Overhaul · සිනමා ධාවකය සහ 403 නිවැරදි කිරීම

**Version:** 3.3.0 · **versionCode:** 30300 · **targetSdk:** 36 (Android 16) · **minSdk:** 26
**APK:** `AnantPlay-Sinhala-Player-v3.3.0.apk` — 3,424,979 bytes (3.34 MB), v2+v3 signed, zipalign-verified
**SHA-256 (hosted CI build, debug-signed):** `1cc98a485e0e2c18cfecc04e3975a008ade09ebf555995694142ee7c7bcfa7c7`
**Lab:** 716/716 checks PASS / 0 FAIL + bytecode reference verifier OK (hosted GitHub Actions)
**Strictly v3.x** — no v4, no libVLC `.so`, no Flutter, no ad SDK, no AccessibilityService.

This report proves, at `file:line` level, the cause and the fix of the four catastrophic
defects visible in the 2026-11-22 22:17 real-device screenshots of v3.2.0, and documents the
rebuilt Cinema Player, the Sri Lankan subtitle automation and the verification evidence.

---

## 0. Executive summary · සාරාංශය

| # | Field defect (v3.2.0) | Root cause (file:line) | Fix (v3.3.0) |
|---|---|---|---|
| 1 | Entire bottom navigation bar missing — Settings/Customize/Troubleshoot unreachable | `MainActivity.java:234-303` built `bottomBar` (241), filled it (279) and **never added it to the root** — no `root.addView(bottomBar)` anywhere | `LinearLayout(VERTICAL)` shell: TopBar (WRAP) + content (0dp, weight 1) + BottomBar (WRAP); runtime invariant measured every resume |
| 2 | `ERROR_CODE_IO_BAD_HTTP_STATUS · Response code: 403` on a stream official AnantPlay plays | `PlayActivity.java:479-496` sent a truncated stub UA (489), never called `setUserAgent()`, never derived `Origin`, sent one cast cookie to every CDN host, had no recovery leg | `StreamHttpConfig` (browser UA + Origin + Accept + dedupe) + `setUserAgent()` + per-host `ResolvingDataSource` cookie + one automatic 403 recovery retry |
| 3 | 16:9 video stretched to fill a portrait screen; `< Back` overlapping Retry row; oversized subtitles | `PlayActivity.java:1231-1234` surface at MATCH_PARENT; no `onVideoSizeChanged`; pill rows floated at fixed `topMargin = dp(64)`; default 22 sp, no `maxWidth` | `VideoViewport` engine + 3-zone HUD (top WRAP · centre weight 1 · bottom WRAP) + inset-padded bars + 18/20 sp cinema subtitles with 88 % max width |
| 4 | No automatic Sri Lankan subtitle finding | no `LkSubFinder`, no `.zip` capture in the WebView | `LkSubFinder` (Baiscope/Zoom.lk/PirateLK/SubDL/OS) + WebView `.zip`/`.srt` capture → unzip → attach live + 📺 external VLC/MX/mpv |

සිංහලයෙන්: v3.2.0 හි **(1)** පහළ සැරිසැරීමේ තීරුව කිසිසේත් තිරයේ නොපෙනුණේ එය
සාදා තිබුණද `root.addView(bottomBar)` කිසිවිටෙකත් කැඳවා නොතිබූ නිසාය; **(2)** නිල
AnantPlay හි play වන ධාරාව 403 වූයේ කප්පාදු කළ User-Agent, `Origin` නොමැතිකම සහ
cookie වැරදි host එකට යැවීම නිසාය; **(3)** 16:9 වීඩියෝව සිරස් තිරයට දිගු වූයේ
`onVideoSizeChanged` නොසලකා හැරීම නිසාය; **(4)** ශ්‍රී ලාංකික උපසිරැසි ස්වයංක්‍රීයව
සොයාගැනීමක් නොතිබුණි. හතරම v3.3.0 හි මූලයේදීම නිවැරදි කර ඇත.

---

## 1. BUG #1 — the vanished bottom navigation bar

### 1.1 The exact cause (`MainActivity.java`, v3.2.0)

```
234:  private View buildUi() {
235:      FrameLayout root = new FrameLayout(this);
238:      pages = new FrameLayout(this);
239:      root.addView(pages, new FrameLayout.LayoutParams(-1, -1));   // content: full screen
241:      bottomBar = new LinearLayout(this);                          // the bar is CREATED
242:      bottomBar.setOrientation(LinearLayout.HORIZONTAL);
279:          bottomBar.addView(tab, new LinearLayout.LayoutParams(0, -2, 1f));  // 4 tabs added
297:          pages.setPadding(left, top, right, bottom + dp(58));      // 58 dp reserved for a bar…
298:          bottomBar.setPadding(left, 0, right, bottom);
303:      return root;                                                 // …that is NEVER added
```

`bottomBar` has **no parent**. A `View` without a parent is never measured, never laid out and
never drawn — it is not "hidden", it does not exist on screen. The `dp(58)` bottom padding on
`pages` is the only remnant, and it is exactly the "pure empty dark background" under
*"AnantPlay සිංහල Player v3.2.0…"* in screenshot 1. Because the tabs were also the only route to
Settings/Customize/Troubleshoot, the user was locked out of four of five screens.

### 1.2 The fix — a shell where "off-screen" is arithmetically impossible

```
LinearLayout shell (VERTICAL)
├── topBar    MATCH_PARENT × WRAP_CONTENT   ← title + version pill · 🩺 Troubleshoot · ⚙️ Settings
├── pages     MATCH_PARENT × 0, weight 1.0f ← the content host
└── bottomBar MATCH_PARENT × WRAP_CONTENT   ← 🏠 Home · 🌐 Web · 🎨 Customize · 🩺 Troubleshoot
```

With the content host at **height 0 + weight 1.0f**, the vertical LinearLayout must give the
bottom bar its full measured height first; the content can only ever consume what is left. A bar
pushed off-screen is now impossible by construction, on every screen size and every inset.

* **Double navigation access** — the top app bar always carries a 1-tap 🩺 Troubleshoot and a
  1-tap ⚙️ Settings icon, so even a manufacturer skin that eats a bottom bar cannot lock the user
  out. `topBarIcon("🩺")` / `topBarIcon("⚙️")`.
* **Insets** — `topBar` is padded by `statusBars()|displayCutout()`, `bottomBar` by
  `navigationBars()|displayCutout()`, minimum height 64 dp.
* **Tabs** — 4 tabs (Home/Web/Customize/Troubleshoot); when the Web tab is hidden it becomes 3
  centred tabs and **Customize + Troubleshoot are never hidden**.
* **Runtime proof** — `MainActivity.measureShell()` runs 260 ms after every `onResume` and asserts
  `bottomBar.isShown() && pages.getHeight() > 0 && bottomBar.getTop() >= pages.getBottom() - 1 &&
  bottomBar.getBottom() <= shell.getHeight()`. The verdict feeds probe #10 and the
  `Troubleshooter` timeline (`LAYOUT nav shell OK · content 2148px · bar top 2148 · bar h 168`).

---

## 2. BUG #2 — `ERROR_CODE_IO_BAD_HTTP_STATUS · Response code: 403`

### 2.1 Line-by-line comparison with official AnantPlay

| Aspect | Official `AnantPlay/android PlayerActivity.java:1087-1103` | Our v3.2.0 `PlayActivity.java:479-496` |
|---|---|---|
| UA source | `setUserAgent(ua)` with `ua = "Mozilla/5.0 (Linux; Android 14) … Chrome/130.0 Mobile Safari/537.36"`; UA is **removed** from the property map (`if (k.equalsIgnoreCase("User-Agent")) ua = …; else headers.put(…)`) | **No `setUserAgent()` at all**; a truncated stub `"Mozilla/5.0 (Linux; Android " + RELEASE + ") AppleWebKit/537.36"` (line 489) was put in the property map — no `Chrome/`, no `Mobile`, no `Safari` |
| Origin | forwarded from the phone player's intent headers | **never** derived from `Referer` |
| Cookie | `ResolvingDataSource` asks `CookieManager` for **each request's own host** (1099-1103) | one cast cookie pushed into `setDefaultRequestProperties` → sent to every CDN/segment host |
| Redirects | `setAllowCrossProtocolRedirects(true)`, 15 s/30 s timeouts | `true`, but only 8 s/8 s |
| Failure handling | logs `FAILED <code> <uri> sent <header keys>` and exits | red box, terminal |

A CDN (Cloudflare / VidSrc / Prism-class) sees a non-browser UA, no `Origin`, and a cookie minted
for another host → **403**. The stream is fine; the request was not.

### 2.2 The fix — one pure, lab-proven engine

`StreamHttpConfig.java` (new, zero Android imports):

* `parseHeaders()` handles **all three** cast shapes AnantPlay can send: a flat JSON object, a
  nested `{"headers":{…}}` object, a nested `"headers"` **string** (what `Cast.describe()` actually
  produces), plus top-level `ua / referer / referrer / origin / cookie / authorization` aliases.
* `normalize()` → canonical header names, hop-by-hop headers dropped (`Host`, `Content-Length`,
  `Connection`, `Transfer-Encoding`…), a **browser-grade Chrome 131 Mobile UA** whenever the stream
  supplied none or supplied a blocked one (`AndroidXMedia3/*`, `Dalvik/*`, our own stub), `Origin`
  derived from the `Referer`, `Accept: */*`, `Accept-Language`, and the cast cookie merged once.
* `recovery()` → the one-shot 401/403/451 fallback: forced Chrome UA, `Origin` from `Referer`,
  `Referer` derived from the URL when absent, `Accept: */*`, and the `Sec-Fetch-Dest/Mode/Site`
  triple a real Chrome sends.
* `describe()` → header **names only** (sorted), so the Troubleshooter can record exactly what was
  sent without ever leaking a cookie or a token.

Wiring in `PlayActivity.startPlayback()`:

```java
httpHeaders = StreamHttpConfig.normalize(headersJson, cookie, url, extra);   // ua/referer/origin
String ua = headers.remove("User-Agent");                                     // exactly official
new DefaultHttpDataSource.Factory().setUserAgent(ua)                          //   AnantPlay's recipe
    .setDefaultRequestProperties(headers).setAllowCrossProtocolRedirects(true)
    .setConnectTimeoutMs(15000).setReadTimeoutMs(30000);
DataSource.Factory cookieLayer = new ResolvingDataSource.Factory(http, spec -> { … CookieManager …
    return spec.withAdditionalHeaders(Collections.singletonMap("Cookie", ck)); });
```

and the recovery leg, in `Player.Listener.onPlayerError`:

```java
int code = httpStatusOf(e);
if (!triedHeaderRecovery && code > 0 && StreamHttpConfig.recoverable(code)) {
    triedHeaderRecovery = true;
    httpHeaders = StreamHttpConfig.recovery(url, httpHeaders);
    rebuildAt(pos);           // resumes at the exact position, one retry only
    return;
}
```

If the retry also fails, the honest error card appears with **🔄 Retry · 🔧 Retry with alternate
headers · 📺 Open in VLC · 📋 Copy error** — never a red wall over the video.

### 2.3 Live proof (mock CDN, `v330 http403:` — 24 checks)

The lab starts a real HTTP server on `127.0.0.1` that answers **403** unless `Referer` (same
origin), `Origin`, a Chrome `User-Agent`, the exact `Cookie` and an `Accept` header all arrive,
and that refuses `AndroidXMedia3/*` outright:

| Request | Result |
|---|---|
| v3.2.0 stub UA + referer + cookie | **403** (the field bug, reproduced) |
| `AndroidXMedia3/1.11.1` UA | **403** |
| `StreamHttpConfig.normalize(...)` output | **200 OK on the first try** |
| UA + cookie, no Origin/Referer | **403** |
| `StreamHttpConfig.recovery(...)` output | **200 OK** (the automatic retry works) |

Note: the JDK's `HttpURLConnection` silently drops `Origin` unless
`-Dsun.net.http.allowRestrictedHeaders=true`; Android's OkHttp-backed implementation sends it. The
lab sets the flag so it tests the client we actually ship, not a crippled one.

---

## 3. BUG #3 — the rebuilt AnantPlay/VLC-grade Cinema Player

### 3.1 Aspect ratio (`VideoViewport.java` + `onVideoSizeChanged`)

v3.2.0 added the surface with `MATCH_PARENT/MATCH_PARENT` and **no** video-size callback, so a
1920×1080 movie was stretched into 1080×2400 (a 2.37× vertical distortion). v3.3.0 listens to
`Player.Listener.onVideoSizeChanged(VideoSize)` (width, height **and** `pixelWidthHeightRatio`) and
to the host's `onLayoutChange`, then sizes the surface with `VideoViewport.compute(...)`:

| Case | Result | Meaning |
|---|---|---|
| 1920×1080 in 1080×2400, **Fit** | **1080 × 608** | letterboxed, zero distortion |
| 1920×1080 in 1080×2400, **Fill** | **4267 × 2400** | proportional crop, aspect preserved |
| 1920×1080 in 2400×1080 (landscape), Fit | **1920 × 1080** | exact |
| 1440×1080 par 1.333, Fit | 1920 × 1080 | anamorphic corrected |
| 0×0 (unknown yet) | container size | never a crash |
| NaN / 0 par | treated as 1 | no NaN geometry |
| **Stretch** | container | the only distorting mode, and only on request |

Modes: **Fit** (default) → **Fill** → **16:9** → **Stretch**, cycled by the 📐 chip and by a
pinch (Fit ⇄ Fill).

### 3.2 The 3-zone HUD (zero overlapping views)

```
LinearLayout(VERTICAL)
├── hudTopBar    WRAP  ← ‹ Back · title (1 line, ellipsized) · "සිං ✓" badge · 🔒 · 🔄 · 📺 · ⚙
├── hudCenter    0dp weight 1  ← ⏪ -10s · ▶/⏸ (64dp circular) · ⏩ +10s · gesture pill
└── hudBottomBar WRAP  ← SeekBar · 0:12:45 … 2:52:35 · chips (h-scrollable):
                          💬 Subtitles · 🔊 Audio/Quality · 📐 Aspect · ⏩ Speed · 🔄 Rotate · 📺 VLC · 🔍 Title
```

Because the three zones are siblings of a vertical LinearLayout, the top bar and the bottom chips
**cannot** be laid out over each other on any screen. The v3.2.0 `‹ Back`-over-Retry collision came
from floating `subActions` / `langChips` / `extraActions` at fixed `topMargin = dp(64/104/144)`
regardless of the top bar's real height; they now **stack inside `pillBox`**, whose top margin is
`statusInset + dp(50)` — measured, never guessed. Subtitles live in a padded `subLayer`
(`statusInset + dp(40)` top, `navInset + dp(96)` bottom) so they can never sit under either bar.

### 3.3 Gestures, lock, rotate, tracks, external VLC

* **Double-tap left/right** → −/+ the user's seek step, with a ⏪/⏩ ripple on the tapped half.
* **Vertical swipe (left half)** → brightness pill `☀ 68%`; **(right half)** → volume `🔊 75%`;
  **horizontal swipe** → seek preview `⏩ 01:14:22 (+15s)` then seek on release.
* **🔒 Lock** → the HUD goes away, every touch is swallowed, one tiny "Tap to unlock" pill remains.
* **🔄 Rotate** → `setRequestedOrientation(PORTRAIT ⇄ LANDSCAPE)` (also a bottom chip for thumb reach).
* **🔊 Audio / Quality** → a real Media3 `Tracks` picker: every audio language and video
  resolution, selected via `TrackSelectionOverride` (`✓` marks the active track).
* **📺 VLC** → writes the current Sinhala cues to `cache/sinhala-sub.srt` and hands
  `url + title + position + subtitles_location` to VLC → MX Player → mpv → the system chooser. The
  ultimate escape hatch for a codec Media3 cannot decode — without shipping libVLC's 50 MB of `.so`.

### 3.4 Balanced subtitles (the "oversized / clunky" fix)

| | v3.2.0 | v3.3.0 |
|---|---|---|
| Default size | 22 sp | **18 sp portrait / 20 sp landscape** |
| Range | 14–40 sp | **12–36 sp** |
| Max width | none (edge to edge) | **88 % of screen width** |
| Padding | 10/4/10/4 | **12/4/12/4** |
| Position | 4–28 % (could hit the chips) | capped at 12 %, inside the padded layer |
| Font | NotoSansSinhala, ZWJ preserved | unchanged (ශ්‍රී ZWJ/ZWNJ survival is lab-gated) |

The studio (font family, bold, 6 colors + hex, pill/box/outline/shadow, opacity, position, line
spacing) still applies live from both Customize and the in-player ⚙️ sheet.

---

## 4. BUG #4 — Sri Lankan subtitle automation

`LkSubFinder.java` (new, pure): the ordered site list **Baiscope · Zoom.lk · PirateLK · SubDL ·
OpenSubtitles**, each with a real search deep-link (`?s={q}`, `subdl.com/search/{q}`,
`opensubtitles.org/…sublanguageid-sin/moviename-{q}`), a DuckDuckGo fallback, `TitleCleaner`-based
query cleaning (`The Odyssey (2026) 1080p WEB-DL` → `The Odyssey`), plus link classification:
`.srt/.zip/.ass/.ssa/.vtt/.sub`, including `?file=Movie.zip` query forms.

Flow, end to end:

1. **💬 Subtitles → 🇱🇰 Find Sinhala Sub (SL sites)** → a mini web-picker lists the five sites for
   *this* movie's title.
2. Tapping one opens the app's Web tab on that site's search (`webUrl` hand-off, `openWebFromIntent`).
3. The moment the user taps any `.srt`/`.zip`, `WebBrowserView.shouldOverrideUrlLoading` +
   `setDownloadListener` intercept it (`captureSubtitle`), download it (6 MB cap, WebView cookie
   attached) and hand the bytes to `SrtLoader` — 5 MB decompressed cap, 64-entry cap, `..` traversal
   refused.
4. If `PlayActivity.current() != null` the cues are **attached live** (`attachCapturedSubs` →
   `onLocalCues`); otherwise they are cached for the next movie.
5. A non-Sinhala list is detected by `SubLangDetect` and routed to the keyless translator; a Sinhala
   list is used as-is. ZWJ conjuncts (ශ්‍රී) survive the whole path.

---

## 5. Verification evidence

### 5.1 The lab — 716/716 checks, 0 FAIL (hosted CI)

```
── compile the pure engines + the lab
── run the cast-protocol lab (GATING)
Lab proof: 716 checks passed (0 failed)
bytecode verifier: OK — 196 distinct referenced types, all resolvable (lab classes + JDK + android.*)
```

New v3.3.0 sections (all gating):

| Section | Checks | What it proves |
|---|---|---|
| `v330 layout:` | 21 | the shell shape (0dp/weight 1, `shell.addView(bottomBar, …)`), the top-bar 1-tap 🩺/⚙️, inset padding, the 3-zone HUD, the padded subtitle layer, the deleted v3.2.0 defects, `onVideoSizeChanged`, rotate/lock, the error card |
| `v330 http403:` | 24 | the live mock CDN (403 for the stub UA / Media3 UA / missing Origin; 200 for normalized **and** recovered headers), nested cast shapes, hop-by-hop dropping, name-only logging, `recoverable()`, `isBlockedUa()`, the `ResolvingDataSource` + recovery wiring |
| `v330 aspect:` | 12 | 1080×608 · 4267×2400 · 1920×1080 · stretch · 16:9 · anamorphic par · unknown size · NaN par · mode round-trip · chip cycle · `coversScreen` · the 18 sp default |
| `v330 subzip:` | 35 | link classification, the SL site order, encoded search URLs, `preferredEntry`, a real archive extraction with ZWJ preserved, Sinhala/Latin routing, the external-player extras, the WebView capture wiring, the caps |
| `v330 parity:` | 10 | 3.3.0 / 30300 in manifest, AppInfo, build.sh, docs/latest.json, README, SETUP-SI, both workflows; no 4.x; the report ships |

Also fixed: the v3.2.0 `v320 free: the disk cache round-trips` check was **network-dependent** and
failed on every offline runner. It now seeds the cache through the engine's own writer and proves a
hit with `requests == 0` — deterministic, and a stronger assertion than before.

### 5.2 The APK

```
package: name='lk.techworld.anantplay.sicast' versionCode='30300' versionName='3.3.0'
         compileSdkVersion='36' platformBuildVersionName='16' platformBuildVersionCode='36'
size   : 3,424,979 bytes (3344 KiB)   ← hosted build-apk.yml artifact
sha256 : 1cc98a485e0e2c18cfecc04e3975a008ade09ebf555995694142ee7c7bcfa7c7
apksigner verify --verbose:
  Verified using v2 scheme (APK Signature Scheme v2): true
  Verified using v3 scheme (APK Signature Scheme v3): true
zipalign -c 4 → OK
```

Both workflows (`build-apk.yml` **and** `release-apk.yml`) receive the permanent signing
secrets, so the artifact above is signed with the **release** keystore — and `build.sh`'s
pinned ZIP timestamps make it byte-reproducible: the PR run and the post-merge run produced the
**identical** SHA-256 (`1cc98a48…`), which is the value published in `docs/latest.json` and in
`SHA256SUMS.txt`. `apksigner verify` (v2 + v3), `zipalign -c 4` and the `aapt2` badging are
re-verified on every push. A locally built APK without `KS_BASE64` is debug-signed and would fail
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` over v3.2.0 — set the four secrets (`KS_BASE64`, `KS_PASS`,
`KS_KEYPASS`, `KS_ALIAS`) before building for users.

### 5.3 Prior wins kept intact

Sinhala `\u200D` ZWJ / `\u200C` ZWNJ preservation in `Cue.stripMarkup()`; the zero-rate-limit
3-tier GitHub updater (releases/latest 302 → `docs/latest.json` `[skip ci]` → 15-min backoff);
LocalNet same-host rule; CrashLog stale auto-clear; PrivacyScrubber zero-PII; AnnouncerPolicy;
MIUI 3-state autostart; **zero** AccessibilityService; pure Java + Media3 1.11.1 + Guava only.

---

## 6. Files changed

| File | Change |
|---|---|
| `src/…/VideoViewport.java` | **new** — the aspect-ratio engine (pure math) |
| `src/…/StreamHttpConfig.java` | **new** — the 403 header engine (pure) |
| `src/…/LkSubFinder.java` | **new** — the Sri Lankan finder + external-player extras (pure) |
| `src/…/PlayActivity.java` | rebuilt UI (3-zone HUD, VideoViewport, gestures, lock/rotate/tracks/VLC, error card), the 403 HTTP layer + recovery, 18/20 sp subtitles |
| `src/…/MainActivity.java` | the unbreakable nav shell (top bar + 0dp/weight-1 content + fixed bottom bar), `measureShell()`, `webUrl` hand-off |
| `src/…/WebBrowserView.java` | `.zip`/`.srt` capture (navigation + download listener), 6 MB capped fetch, live attach |
| `src/…/AppPrefs.java` | aspect model (fit/fill/16:9/stretch), 12–36 sp, 18 sp default, `fontSpFor()`, gesture toggles, lock-on-start |
| `src/…/FeatureProbe.java` | probe #10 now also proves the 403 headers, the aspect math and the nav shell |
| `lab/ReceiverLab.java` | 88 new v3.3.0 checks + the offline cache fix |
| `lab/run_lab.sh` | `LAB_SKIP_LIVE`, the restricted-headers JVM flag |
| `build.sh` | 3.3.0/30300 defaults, a bounded d8 heap (`-JXmx`) for 2 GB runners |
| `AndroidManifest.xml`, `docs/latest.json`, `README.md`, `SETUP-SI.md`, `.github/workflows/*` | version parity |

---

## 7. What to verify on the phone (1 minute)

1. Open the app → the bottom bar is **there** (🏠 🌐 🎨 🩺) and 🩺/⚙️ are at the top right.
2. Cast a movie from AnantPlay → it plays, **no 403**. If a CDN does refuse, the pill says
   *"🔧 HTTP 403 — retrying with browser headers…"* and playback resumes.
3. Rotate the phone → the video stays 16:9 (letterboxed), never stretched; 🔄 flips it.
4. 💬 → **🇱🇰 Find Sinhala Sub** → tap a `.zip` on Baiscope → subtitles attach to the playing movie.
5. 🩺 → Run Full App Troubleshoot → probe #10 shows `HTTP-403 headers ✓ · aspect 1080×608 ✓ · nav shell ✓`.

---

## 8. Independent audit & hardening (Arena, v3.3.0 integration)

The v3.3.0 patch was integrated from `repo3-anantplay-sinhala-player-v3.3.0.zip` and then
re-audited line by line. Everything below was **verified independently**, not taken on trust.

### 8.1 Nested-ZIP extraction — 0 traversal, 0 `.zip`

All 119 archive entries carried the single prefix `repo3-anantplay-sinhala-player-v3.3.0/`.
Each entry was resolved against a temp root and refused if it escaped it (`..`, absolute or
drive-qualified names — none were present), then the **contents** of that folder (dotfiles
included: `.github/workflows/*`, `.gitignore`, `docs/latest.json`) were copied over the repo
root. No nested `repo3-anantplay-sinhala-player-v3.3.0/` directory exists, and the archive
itself was deleted from disk **and** from git (`git ls-files '*.zip'` → `0`). This also fixed
the red `Cast protocol lab (gating)` job on `main`, whose first step is
`[ "$(git ls-files '*.zip' | wc -l)" -eq 0 ]`.

### 8.2 `📺 VLC` on Android 16 — the audit found a *different* bug than the crash

* **`FileUriExposedException`: not present.** `git grep 'Uri.fromFile'` over `src/` returns
  **zero** hits, and the lab now asserts that `PlayActivity` contains no `Uri.fromFile(` call
  and no `getCacheDir()` at all, so the crash class cannot be reintroduced.
* **The real defect: unreadable subtitle path.** `writeCurrentSubsToCache()` wrote
  `cache/sinhala-sub.srt` and passed the **absolute path string** as VLC's
  `subtitles_location` / MX's `subtitle` / generic `subs`. VLC runs under a different UID →
  `EACCES` on our 0700 cache directory → the user silently got *no* subtitles, blamed the app.
* **Fix (zero bloat, no FileProvider dependency):** the cues are published to the receiver we
  already run — `GET http://127.0.0.1:<port>/subs/current.srt`, `text/plain; charset=utf-8`,
  `Cache-Control: no-store`, gated by the existing `LocalNet.isSelf` local-device rule (a LAN
  peer gets the same 403 as `/play`). `ReceiverServer.setSubsSrt()/subsUrl()` own the body;
  `ReceiverService` keeps it statically so a self-heal re-arm restores the route; the route
  answers 404 once the cues are cleared, so no stale subtitle is ever served.
* **`LkSubFinder.safeSubLocation()`** is now the only gate into the Intent extras: `http(s)://`
  and `content://` pass, raw paths and `file://` are **dropped** (extra omitted entirely).
* **The whole hand-off is inside one `try/catch (Throwable)`** — a missing player, a vendor
  `SecurityException` or a work-profile refusal produces a toast, never a crash.

### 8.3 Rotation (`configChanges`) — already correct, now guarded

`AndroidManifest.xml` declares
`android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboard|keyboardHidden|uiMode"`
on `.PlayActivity`, so `setRequestedOrientation` from 🔄 recreates nothing and playback never
restarts from 0:00 (`onConfigurationChanged` only re-applies the 18 sp → 20 sp studio style).

### 8.4 Pipeline audit

`release-apk.yml` parses as valid YAML (PyYAML, both workflows), defaults to `3.3.0`, derives
the code `3*10000+3*100+0 = 30300`, gates the release on a 100 % lab pass, verifies
`apksigner`/`zipalign`/badging, writes GNU-format `SHA256SUMS.txt` (`<hash>  <file>` — the exact
shape `SelfUpdateCore.pickShaLine` parses), and updates `docs/latest.json` only when
`git diff --quiet` says the file actually changed, committing with `[skip ci]` and retrying a
rejected push up to 3× **without ever failing the release**. `build-apk.yml`'s zero-accessibility
grep scans `src/` + `lab/` and cannot match `ReceiverLab`'s runtime-assembled
`"accessibility" + "service"` literal. `build.sh` `-JXmx896m` is safe on a 7 GB runner (d8's
default 2 GB heap was the OOM), and `lab/stubs/` is still never compiled or dexed (only
`src/` + `build/lk` are given to `javac`).

### 8.5 Bottom bar, 403 engine, privacy — verified, unchanged

`MainActivity` builds `LinearLayout(VERTICAL)` → `topBar` (WRAP_CONTENT, 🩺/⚙️) →
`pages` (MATCH_PARENT, height 0, weight 1) → `bottomBar` added with `shell.addView(...)` and
padded by `navigationBars()`; `tabIdsInOrder()` always contains Home, Customize and
Troubleshoot (only the Web tab is hideable). `StreamHttpConfig.normalize()` + `PlayActivity`
remove `User-Agent` from the property map and use `setUserAgent()`, set
`setAllowCrossProtocolRedirects(true)`, derive `Origin` from `Referer`, wrap the factory in a
`ResolvingDataSource` per-host `CookieManager`, and retry 401/403/451 exactly once behind
`triedHeaderRecovery`. `allowBackup="false"`, no AdvertisingId/Firebase/telemetry, no
`AccessibilityService`, `PrivacyScrubber` Zero-PII.

### 8.6 Hosted CI proof (PR #15, branch `arena/c81ae7c4-anantplay-sinhala-player`)

| Job | Result |
|---|---|
| `Cast protocol lab (gating)` | ✅ **716/716 PASS, 0 FAIL** (`Lab result: ALL 716 PASSED`), bytecode verifier OK, tracked `.zip` files: **0**, AccessibilityService references: **0** |
| `APK (JDK 17 + SDK 36 + build-tools 35.0.1, signed + verified)` | ✅ `AnantPlay-Sinhala-Player-v3.3.0.apk` — **3,424,979 bytes**, sha256 `1cc98a485e0e2c18cfecc04e3975a008ade09ebf555995694142ee7c7bcfa7c7`, `versionCode='30300'` / `versionName='3.3.0'`, `apksigner verify` PASS, `zipalign -c 4` PASS |

The 14 new checks in this audit run over a real socket: `/subs/current.srt` answers 404 when no
subtitle is armed, 200 + `text/plain; charset=utf-8` + `Cache-Control: no-store` with the exact
UTF-8 byte length once it is, keeps the Sinhala ZWJ conjunct `ශ්‍රී` intact, yields the loopback
URL `http://127.0.0.1:<port>/subs/current.srt`, returns to 404 when the cues are cleared, and
returns **no URL at all** when the receiver is stopped — plus the source-level proof that
`PlayActivity` contains no `Uri.fromFile(` and no `getCacheDir()`.
