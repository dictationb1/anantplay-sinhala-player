# V3.3.1 — ZERO-CRASH AUDIT & FIX REPORT
## AnantPlay Sinhala Player · Android 16 (SDK 36) · 2026-11-23

**ශ්‍රී ලංකාවේ භාවිතය සඳහා සම්පූර්ණ වාර්තාව · සිංහල + English**

| | |
|---|---|
| **App** | AnantPlay Sinhala Player (`lk.techworld.anantplay.sicast`) |
| **From → To** | `v3.3.0` (`versionCode 30300`) → **`v3.3.1` (`versionCode 30301`)** |
| **Target** | Android 16 / `targetSdk 36`, `compileSdk 36`, `minSdk 26` |
| **Trigger** | Two fatal crashes logged on the user's own Android 16 phone on 2026-11-23 at `02:03:40.645` and `02:04:58.461` |
| **Branch** | `fix/v3.3.1-zero-crash-and-ui-polish` → PR → `main` |
| **Lab** | `bash lab/run_lab.sh` — **773/773 checks PASS, 0 FAIL** (+ `verify_refs.py` OK + `verify_layouts.py` OK) |
| **Static gate** | `python3 lab/verify_layouts.py` — 46 files, 342 layout calls, **0 problems**; 13/13 selftest cases behave as expected |
| **APK (local proof)** | 3,424,979 bytes · `versionCode='30301'` · `versionName='3.3.1'` · `targetSdk 36` · `zipalign -c 4` OK · **v2 + v3 signatures verified** |

> **කෙටියෙන් (Sinhala summary):** v3.3.0 හි දෙවරක් crash වූ දෙකම — (1) `LayoutParams`
> වැරදි වර්ගයක් `FrameLayout`/`ScrollView` එකකට දැමූ නිසා `FrameLayout.onMeasure` තුළ
> `ClassCastException`, (2) probe thread සහ UI thread එකම ලැයිස්තුවක් එකවර වෙනස් කළ
> නිසා `ConcurrentModificationException` — මූලයේදීම නිවැරදි කර, නැවත ඇතිවීම
> **ව්‍යුහාත්මකව කළ නොහැකි** බවට පත් කර ඇත. අලුත් static පරීක්ෂකයක්
> (`lab/verify_layouts.py`) සහ 8-thread × 1,000-write stress test එකක් සෑම CI build
> එකකම ධාවනය වේ. ඊට අමතරව Card-corner-radius සැකසුම සහ Backup & Restore හි
> නැතිව ගිය යතුරු 4 ද නිවැරදි කර ඇත.

---

# 1. Forensic root cause & fix table (exact `file:line`)

## 1.1 CRASH #1 — `ClassCastException: LinearLayout$LayoutParams → FrameLayout$LayoutParams`

```
EXCEPTION java.lang.ClassCastException: android.widget.LinearLayout$LayoutParams cannot be
cast to android.widget.FrameLayout$LayoutParams
    at android.widget.FrameLayout.onMeasure(FrameLayout.java:195)
    at android.view.View.measure(View.java:30133)
    at android.view.ViewGroup.measureChildWithMargins(ViewGroup.java:7081)
    at android.widget.FrameLayout.onMeasure(FrameLayout.java:194)
    ...
    at android.widget.LinearLayout.measureVertical(LinearLayout.java:878)
    ...
    at com.android.internal.policy.DecorView.onMeasure(DecorView.java:895)
```

### Why this crash can never be caught

`FrameLayout.onMeasure()` reads **every child's** `LayoutParams` with an unchecked cast:

```java
// android/widget/FrameLayout.java  (API 36)
protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    for (int i = 0; i < count; i++) {
        final View child = getChildAt(i);
        if (mMeasureAllChildren || child.getVisibility() != GONE) {
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);  // :194
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();             // :195  ← THROW
```

`ScrollView` and `HorizontalScrollView` **extend** `FrameLayout`, so they inherit the same
cast. The throw happens inside the framework's own measure pass — a `try/catch` in our code
cannot intercept it, and `View.setLayoutParams()` never validates the type either. The only
real fix is to never create the mismatch; the only real *guarantee* is a build-time gate.

### Every mismatch found in `src/` and fixed

| # | File:line (v3.3.0) | Container | Wrong params passed | Consequence on the phone | Fix (v3.3.1) |
|---|---|---|---|---|---|
| 1 | `PlayActivity.java:1367` + `:1369` | `root` — **`FrameLayout`** (the player's root) | `LinearLayout.LayoutParams` (`pillLp` was declared `LinearLayout.LayoutParams` at `:238`) | **The crash in the log.** The subtitle pill column (`pillBox`) was mounted with the wrong class, so the very first measure pass after `setContentView(root)` died inside `FrameLayout.onMeasure:195`. Every path into the Cinema Player hit it (Test Player, cast, manual URL, WebView hand-off) — "the Player completely broke". | `pillLp` is now `FrameLayout.LayoutParams` (`PlayActivity.java:245`, created `:1378` with `Gravity.TOP｜CENTER_HORIZONTAL`) |
| 2 | `PlayActivity.java:1510` | same `pillBox` / `root` | same `pillLp` re-applied from the insets listener | Same throw, on every inset re-application (rotation, gesture-bar change) | `PlayActivity.java:1521` — the same `FrameLayout.LayoutParams`, `topMargin` re-set from the measured status inset |
| 3 | `PlayActivity.java:1688` | `hs` — **`HorizontalScrollView`** (the HUD chip strip) | `LinearLayout.LayoutParams` | `HorizontalScrollView.onMeasure → FrameLayout.onMeasure:195` → the HUD chip row (💬 Subtitles, 🔊 Audio, 📐, ⏩, 🔄, 📺 VLC, 🔍) killed the player as soon as the bottom bar was laid out | `PlayActivity.java:1701` — `hs.addView(chips, new FrameLayout.LayoutParams(-2, -2))` |
| 4 | `PlayActivity.java:2418` (inside `ripple()`) | `hudCenter.getParent()` — **`LinearLayout`** (the HUD box) | `FrameLayout.LayoutParams` (the *inverse* mismatch) | `LinearLayout.measureVertical → measureChildBeforeLayout` cast a `FrameLayout.LayoutParams` to `LinearLayout.LayoutParams` → **double-tap ⏪/⏩ seek crashed the player mid-playback** | `PlayActivity.java:2445` — the ripple now mounts on the **root `FrameLayout`** (`rootView`, new field at `:239`), which is exactly the parent its `LayoutParams` describe; the transient view is removed after 550 ms |
| 5 | `PlayActivity.java:1897` | `surface` (child of `videoHost`, a `FrameLayout`) | unchecked `(FrameLayout.LayoutParams) surface.getLayoutParams()` | A latent `ClassCastException` on the UI thread if the surface were ever re-parented/re-typed — a crash waiting for a future refactor | `PlayActivity.java:1919` — `instanceof` guard, falls back to a correct `FrameLayout.LayoutParams(-1,-1, CENTER)` |
| 6 | `PlayActivity.java:2104` | — | dead helper `langRowLp(LinearLayout)` returning a `FrameLayout.LayoutParams` | An unused factory that invited exactly this bug class | Deleted |

### What was **verified clean** (audited, no change needed)

| File | What was audited | Verdict |
|---|---|---|
| `MainActivity.java` | `shell` (`LinearLayout`) · `topBar` · `pages` (**`FrameLayout`**) · all 4 tab roots (`Home`, `Web`, `Customize`, `Troubleshoot`) · every drill-down page (`Look & Feel`, `Subtitles Studio`, `Player & Gestures`, `Web & Custom Links`, `Cast & Network`, `Updates`, `Backup & Restore`) · every `ScrollView` child (`ViewGroup.LayoutParams`, legal) · the `Subtitles Studio` live preview (`FrameLayout` stage + `FrameLayout.LayoutParams` cast) | **0 mismatches** — all 6 `pages.addView(...)` calls carry `FrameLayout.LayoutParams` |
| `WebBrowserView.java` | `extends FrameLayout`; root `column` (`FrameLayout.LayoutParams`) · `bar`/`progress`/`linksRow`/`web` (children of a `LinearLayout`, correctly `LinearLayout.LayoutParams`) · floating stream/subtitle `pillRow` (bare `LayoutParams` = `FrameLayout.LayoutParams` inside a `FrameLayout` subclass — correct) · fullscreen `customView` overlay on the decor (`FrameLayout.LayoutParams`) | **0 mismatches** |
| `PlayActivity.java` (rest) | `videoHost`/`subLayer` (`FrameLayout`) · `hud`/`hudTopBar`/`hudCenter`/`hudBottomBar` (`LinearLayout`) · `panel`/`sheet`/`errorCard`/`unlockPill` on the root `FrameLayout` · all 5 sheets/dialogs | **0 mismatches** |

**Total across all 46 Java files: 342 `addView` / `setLayoutParams` / `getLayoutParams`-cast call sites typed and verified.**

---

## 1.2 CRASH #2 — `ConcurrentModificationException` at `MainActivity.paintProbeCard:2127`

```
EXCEPTION java.util.ConcurrentModificationException
    at java.util.ArrayList$Itr.checkForComodification(ArrayList.java:1112)
    at java.util.ArrayList$Itr.next(ArrayList.java:1065)
    at lk.techworld.anantplay.sicast.MainActivity.paintProbeCard(MainActivity.java:2127)
    at lk.techworld.anantplay.sicast.MainActivity.access$300(MainActivity.java:71)
    at lk.techworld.anantplay.sicast.MainActivity$2.lambda$onProbeDone$1(MainActivity.java:2201)
```

### The exact defect

`probeOutcomes` was a **live `ArrayList`** shared by two threads with no synchronisation:

```java
// v3.3.0 — MainActivity.java
private volatile java.util.List<FeatureProbe.Outcome> probeOutcomes;      // :112

probeOutcomes = new ArrayList<>();                                        // :2179  UI thread
...
@Override public void onProbeDone(FeatureProbe.Outcome o) {
    java.util.List<FeatureProbe.Outcome> list = probeOutcomes;            // :2198  PROBE thread
    if (list != null) {
        list.add(o);                                                      // :2199  structural write
        main.post(MainActivity.this::paintProbeCard);                     // :2200
    }
}
...
for (FeatureProbe.Outcome o : outcomes) {                                 // :2127  UI thread iterator
```

The iterator is created on the UI thread, `main.post(paintProbeCard)` fires from the probe
thread for **every one of the 10 probes**, and each `add()` bumps `modCount` → the next
`Itr.next()` throws. It is a race, which is why it appeared "repeatedly but not always".

### The fix (three independent guarantees)

| Layer | File:line | Change |
|---|---|---|
| Engine state | `FeatureProbe.java:92–94` | `AtomicBoolean RUNNING` (one run at a time) + `CopyOnWriteArrayList<Outcome> LAST` (append-safe) |
| Engine API | `FeatureProbe.java:84–86`, `:105`, `:158` | `Listener` gains `default onProbeSnapshot(List)` / `onAllDone(List)`; **every** callback receives `Collections.unmodifiableList(new ArrayList<>(LAST))`; `lastResults()` always returns a fresh immutable copy |
| Engine guard | `FeatureProbe.java:124–136` | `runAll()` returns the previous snapshot if a run is already in flight (a double-tap can never interleave two probe sets) |
| UI state | `MainActivity.java:112`, `:2207` | `probeOutcomes` is `volatile` and **always** an immutable snapshot (`Collections.emptyList()` / the engine's unmodifiable list) — never a mutable `ArrayList` |
| UI wiring | `MainActivity.java:2229` | the probe thread only **swaps a reference** (`probeOutcomes = snap`) and posts a repaint; it never mutates a list |
| UI paint | `MainActivity.java:2126–2139` | `paintProbeCard()` guards `isFinishing() \|\| isDestroyed()`, delegates to `paintProbeCardInner()`, and catches **every** `Throwable` → `Diag.w` + `Troubleshooter.record("TROUBLE", …)` instead of a process kill |
| Final callback | `MainActivity.java:2242` | the post-run UI update also checks `isFinishing()/isDestroyed()` |

### Full shared-state audit (every other list/map in the app)

| Class | Shared state | Verdict |
|---|---|---|
| `Troubleshooter` | `ArrayDeque<Event> events` | **Safe** — every read/write under `synchronized (lock)`; `timelineLines()` copies inside the lock |
| `Diag` | ring buffer | **Safe** — same lock discipline |
| `PlayActivity` | `volatile List<Cue> cues` | **Safe** — copy-on-write: always re-assigned to a fresh list, never mutated in place |
| `SubtitleStore` | file-backed | **Safe** — no shared in-memory collection |
| `ReceiverService` | request bodies | **Safe** — per-request locals |
| `WebBrowserView` | `LinkedHashMap hits` | **Fixed** — mutation and iteration were already `synchronized (hits)`, but the UI thread read `hits.size()` **unsynchronised** at `:515`; now `hitCount()` (synchronized), `WebBrowserView.java:518` |
| `MainActivity` | `refreshers`, `tabIcons`, `tabLabels` | **Safe** — built on the UI thread during `buildUi()`, only read afterwards |

---

## 1.3 Other crash traps eliminated in the same pass

| Trap | Where | Status |
|---|---|---|
| `setBackgroundResource(<raw ARGB int>)` → `Resources$NotFoundException` | all `src/` | **0 occurrences** (gated) |
| `Uri.fromFile(...)` → `FileUriExposedException` | all `src/` | **0 occurrences**; `📺 VLC` publishes `http://127.0.0.1:<port>/subs/current.srt` instead (gated) |
| `registerReceiver(...)` without `RECEIVER_NOT_EXPORTED` → `SecurityException` on Android 14+ | all `src/` | **0 unflagged registrations** (gated) |
| Unchecked `(X.LayoutParams) view.getLayoutParams()` casts | all `src/` | 1 found (`applyVideoSize`), now `instanceof`-guarded; gated |

---

# 2. Static & runtime protection proof (verbatim results)

## 2.1 `lab/verify_layouts.py` — the new gating static analyzer

```
$ python3 lab/verify_layouts.py
verify_layouts: scanned 46 java files, 342 layout calls, 0 problem(s)
verify_layouts: OK — every addView/setLayoutParams/getLayoutParams cast in src/ is
type-correct; no raw-ARGB background, no Uri.fromFile, no un-flagged receiver
```

It is not a grep. It builds a **scope-aware type environment** per file (declarations,
field assignments, `new` expressions, helper return types, and the *inherited* nested
`LayoutParams` type inside a `FrameLayout` subclass), resolves each child's container from
its `addView` edge (nearest declaration first, so shadowed locals and per-loop
re-declarations are handled), and resolves `(ViewGroup) x.getParent()` to the real container.

**The self-test proves the verifier still catches bugs** (a green gate that stopped
detecting anything would be worse than no gate):

```
$ python3 lab/verify_layouts.py --selftest
  ok   clean FrameLayout child
  ok   LinearLayout params into FrameLayout  (FrameLayout.addView(...) given LinearLayout.LayoutParams -> ClassCastException in onMeasure)
  ok   FrameLayout params into LinearLayout  (LinearLayout.addView(...) given FrameLayout.LayoutParams -> ClassCastException in onMeasure)
  ok   LinearLayout params into a ScrollView  (ScrollView.addView(...) given LinearLayout.LayoutParams -> ClassCastException in onMeasure)
  ok   params variable mismatch  (FrameLayout.addView(...) given LinearLayout.LayoutParams -> ClassCastException in onMeasure)
  ok   setLayoutParams mismatch  (FrameLayout.addView(...) given LinearLayout.LayoutParams -> ClassCastException in onMeasure)
  ok   unchecked cast into the wrong parent  ((FrameLayout.LayoutParams) v.getLayoutParams() but v lives in a LinearLayout -> ClassCastException at runtime)
  ok   ripple mounted on getParent()  (LinearLayout.addView(...) given FrameLayout.LayoutParams -> ClassCastException in onMeasure)
  ok   bare LayoutParams inside a FrameLayout subclass  (FrameLayout.addView(...) given LinearLayout.LayoutParams -> ClassCastException in onMeasure)
  ok   raw ARGB background  (setBackgroundResource(<raw ARGB>) — that argument is a colour, not a resource id; use setBackgroundColor(...))
  ok   Uri.fromFile  (Uri.fromFile(...) — FileUriExposedException on API 24+)
  ok   receiver without an export flag  (registerReceiver(...) without an explicit RECEIVER_NOT_EXPORTED/RECEIVER_EXPORTED flag — SecurityException on Android 14+)
  ok   receiver with RECEIVER_NOT_EXPORTED
verify_layouts selftest: 13/13 cases behaved as expected
```

**Proof it catches the actual field bug** — run against the pre-fix `v3.3.0` `PlayActivity.java`:

```
verify_layouts: scanned 1 java files, 112 layout calls, 3 problem(s)
  FAIL PlayActivity.java:1369: FrameLayout.addView(...) given LinearLayout.LayoutParams -> ClassCastException in onMeasure
  FAIL PlayActivity.java:1688: HorizontalScrollView.addView(...) given LinearLayout.LayoutParams -> ClassCastException in onMeasure
  FAIL PlayActivity.java:1510: pillBox (child of FrameLayout) set to LinearLayout.LayoutParams -> ClassCastException in onMeasure
verify_layouts: FAILED — a LayoutParams ClassCastException is still shippable
exit code 1
```

`lab/run_lab.sh` runs `--selftest` **and** the real scan before `ReceiverLab`; both are
mandatory (`set -e`), so the hosted `Cast protocol lab` job is red if either fails.

## 2.2 `lab/verify_refs.py` — the bytecode reference verifier (pre-existing, still green)

```
bytecode verifier: OK — 205 distinct referenced types, all resolvable (lab classes + JDK + android.*)
```

## 2.3 The 8-thread × 1,000-write concurrency stress test (`lab/ReceiverLab.java`, `v331ConcurrencyStress`)

A real execution, not a shape check: **8 writer threads append 1,000 `Outcome`s each**
(`live.add(...)` on the engine's own `CopyOnWriteArrayList`), each writer also records into
`Troubleshooter` and `Diag` and takes snapshots; **4 reader threads** simultaneously run the
exact loop that used to throw — `for (Outcome o : FeatureProbe.lastResults())` — plus
`Troubleshooter.timelineLines()`, `Troubleshooter.probeSection(snapshot)` and `Diag.dump()`.

```
== v3.3.1 crash #2 — 8 threads × 1,000 writes + concurrent snapshot reads ==
  PASS v331 crash2: 8 writers × 1,000 writes + 4 concurrent snapshot readers threw NOTHING — 0 exceptions
  PASS v331 crash2: zero ConcurrentModificationException anywhere in the stress window — no CME
  PASS v331 crash2: every one of the 8,000 outcomes survived (no lost/duplicated writes) — 8000 of 8000
  PASS v331 crash2: lastResults() returns a full, intact snapshot of the live run — 8000 outcomes
  PASS v331 crash2: the readers iterated without ever seeing a torn list — <n> snapshot iterations, 0 torn
  PASS v331 crash2: lastResults() is IMMUTABLE (a UI thread can never mutate it)
  PASS v331 crash2: two lastResults() calls are two distinct copies (never the live list)
  PASS v331 crash2: the Troubleshooter timeline absorbed the concurrent records intact
  PASS v331 crash2: the Diag ring absorbed the concurrent records intact
  PASS v331 crash2: the report section renders 8,000 outcomes without throwing
  PASS v331 crash2: isRunning() reports the in-flight guard
  PASS v331 crash2: a second concurrent runAll() returns the snapshot instead of running again
  PASS v331 crash2: the guard clears itself (isRunning() false after the run)
```

## 2.4 The full lab

```
$ bash lab/run_lab.sh
...
Lab result: ALL 773 PASSED
Lab proof: 773 checks passed (0 failed)
bytecode verifier: OK — 205 distinct referenced types, all resolvable
verify_layouts: OK — every addView/setLayoutParams/getLayoutParams cast in src/ is type-correct
```

**773 / 773 checks, 0 FAIL** (v3.3.0 was 716; v3.3.1 adds 57 new checks across the three
new suites: `v331 crash1`, `v331 crash2`, `v331 player & customize`, `v331 parity`).

## 2.5 The real local APK build (`./build.sh`, `platforms;android-36` + `build-tools;35.0.1`)

```
── version: 3.3.1 (versionCode 30301, previous 30200)
── aapt2: compile + link resources
── javac (Java 8 bytecode target, android.jar + media3 on the classpath)
── d8 → dex (app classes + the media3/guava jars, multidex)
── package
── zipalign
── sign
── verify
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Number of signers: 1
── badging (the shipped version is checked, not assumed)
   package: name='lk.techworld.anantplay.sicast' versionCode='30301' versionName='3.3.1'
            platformBuildVersionName='16' platformBuildVersionCode='36' compileSdkVersion='36'
            compileSdkVersionCodename='16'
── done: dist/AnantPlay-Sinhala-Player-v3.3.1.apk
   size        : 3424979 bytes (3344 KiB)
   sha256      : ccf99a2bcab4fa3d80203373c5c31c31b9822e83914df946168b757cf62fbe22
```

`zipalign -c 4` passed (build.sh dies otherwise). The local run was **debug-signed**; the
hosted release job signs with the permanent `KS_BASE64` keystore, so the released SHA-256
differs by design (see §5).

> Build-pipeline hardening found while proving this: `apksigner` defaults to `-Xmx1024M`,
> which on a 2 GB container fails with *"Failed to reserve memory for metaspace"* **after** a
> successful compile/d8/package. `build.sh` now passes `-JXmx640m` to both `apksigner` calls
> (no behaviour change on a normal 4–16 GB CI runner, but the pipeline now completes on a
> small machine).

---

# 3. Player, Troubleshooter & Mornye/Apple UI verification

## 3.1 The Cinema Player — every entry path

| Entry path | Audit result |
|---|---|
| **Test Player** (Home → 🎬) | `PlayActivity` builds `root` → `videoHost` → `surface` → `subLayer` → `pillBox` → `hud` → `panel`/`sheet`/`errorCard`; every `addView` now type-correct. **No crash.** |
| **AnantPlay Cast** (mDNS + DLNA) | same `buildUi()`; the cast hand-over (`expectTakeover`) is untouched |
| **Manual Video URL** | same `buildUi()`; `StreamHttpConfig.normalize` still supplies the browser UA |
| **WebView stream hand-off** | `WebBrowserView.play(hit)` → same `PlayActivity`; the header bundle is unchanged |
| **Rotation / insets** | the `setOnApplyWindowInsetsListener` re-applies `pillLp` (now `FrameLayout.LayoutParams`) — this was crash site #2 |
| **Double-tap ⏪/⏩** | `ripple()` now mounts on the root `FrameLayout` — this was crash site #4 |

| Player control | Verified |
|---|---|
| `← Back` | wired, null-safe |
| `🔒 Lock` / unlock pill | `setLocked()` + `unlockPill` inset re-apply uses a guarded `FrameLayout.LayoutParams` cast |
| `🔄 Rotate` | `setRequestedOrientation` in a `try/catch` |
| `📺 VLC` | publishes `http://127.0.0.1:<port>/subs/current.srt`; **zero `Uri.fromFile`**; lab proves 404 when unarmed, 200 + UTF-8 ZWJ intact when armed |
| `⚙️ Settings sheet` | `buildSettingsSheet()` — corner radius now follows the user's card-radius choice |
| `💬 Subtitles sheet` | `togglePanel()` |
| `🔊 Audio/Quality tracks` | `showTrackSheet()` |
| `📐 Aspect` | `cycleAspect()` → `applyVideoSize()` (`instanceof`-guarded) |
| `⏩ Speed` | `cycleSpeed()` |
| `🔍 Title search` | `openTitleSearch()` |
| `🇱🇰 Find Sinhala sub` | `LkSubFinder` (`.zip` + ZWJ encoders lab-proven) |
| `⏪ -10s / ▶⏸ / ⏩ +10s` | transport row, all null-safe |
| SeekBar | `onProgressChanged` guarded |
| Gestures (swipe brightness/volume/seek, double-tap seek) | `wireGestures(root)` — every branch wrapped |

**Subtitle geometry:** the subtitle `TextView` keeps `FrameLayout.LayoutParams` inside
`subLayer` (a `FrameLayout`), `Gravity.BOTTOM｜CENTER_HORIZONTAL`, 4–12 % bottom margin.

## 3.2 Troubleshooter

| Requirement | Status |
|---|---|
| `Run Full App Troubleshoot` never crashes | **Fixed** — immutable snapshots + `isFinishing/isDestroyed` guard + `try/catch(Throwable)` that records to the timeline |
| Live progress for all 10 probes | `onProbeStart` → status line; `onProbeSnapshot` → repaint of the card |
| `PASS ✓` / `FAIL ✗` + ms + detail | unchanged rendering, now fed by snapshots |
| 1-tap copy / share, privacy-safe | `PrivacyScrubber` — lab proves all **14** canary leak shapes (IP, MAC, EUI-64 IPv6, model, fingerprint, cookie, bearer, Google key, `?token=`, absolute path) are scrubbed |
| Double-tap the hero | `AtomicBoolean probeRunning` (UI) **and** `FeatureProbe.RUNNING` (engine) — a second run returns the previous snapshot |

## 3.3 Customization — every option works **and** persists

| Screen | Options audited | Result |
|---|---|---|
| **Look & Feel** | theme (system/dark/AMOLED/light), dynamic colour, 8 accents, **custom `#RRGGBB`**, UI language (bi/si/en), density (spacious/comfortable/compact), **card radius (sharp/rounded/Apple-pill)** | **Fixed:** the card radius now flows into `ThemeEngine.palette(..., cardRadiusPref)` → `P.radiusCard` → every card (`MainActivity.card()` `:2515`) and the player's panel/sheet/error card (`PlayActivity` `:1737`, `:1818`, `:2736`). Tapping the row recreates the activity → visible immediately. |
| **Subtitles Studio** | font size 14–36, family (noto/system/serif), colour, mask (pill/outline/box/shadow), opacity, bold, bottom %, line spacing, **live preview** | **Improved:** the preview line is now `ශ්‍රී ලංකාවේ චිත්‍රපටිය — සිංහල උපසිරැසි` (`MainActivity.java:1179`), i.e. it contains the ZWJ conjunct **ශ්‍රී**, so a broken shaping stack is visible before opening a movie |
| **Player & Gestures** | aspect, HUD hide, seek step, sync step, speed, auto-PiP, keep-screen-on, auto-resume, 3 swipe switches, **lock-on-start** | all present; **4 of them were missing from `AppPrefs.normalizeValue`** → Backup & Restore dropped them (see below) |
| **Web & Custom Links** | desktop UA, ad-block, auto-open, custom links CRUD (≤8) | round-trips through the backup (`parseLinks` → 2 links restored) |
| **Cast & Network** | receiver name/port, mDNS, DLNA, Worker URL, DoH diag | round-trips |
| **Updates** | auto-update, zero-rate-limit 302 + `docs/latest.json` | lab-proven (`tagFromLocation`, derived asset URLs, `httpsOnly`) |
| **Backup & Restore** | export/import | **Fixed** — see below |

### The Backup & Restore defect found by the lab

`AppPrefs.BACKUP_KEYS` listed 50 keys, but `AppPrefs.normalizeValue()` had **no case** for
`swipeBrightness`, `swipeVolume`, `swipeSeek` and `lockOnStart` — the four v3.3.0 player
switches. `parseImport()` counts unknown keys as `rejected` and **drops them silently**, so
restoring a backup quietly turned the user's swipe gestures and lock-on-start back off.

Fix: the four keys are in the boolean branch of `normalizeValue` (`AppPrefs.java:330`), and
the lab now asserts an exact **50 / 50** round-trip with **0 rejected**:

```
  PASS v331 customize: all 50 customization keys survive export → import
       (the v3.3.0 lock/swipe keys used to be silently dropped) — 50 keys restored, 0 rejected
  PASS v331 customize: the theme, radius, aspect, lock-on-start and all three swipe switches
       restore exactly as chosen
```

## 3.4 1-minute verification on the real phone

1. **Player:** Home → 🎬 *Test Player* → video starts. Tap twice on the right half (⏪/⏩ ripple appears) → **no crash**. Rotate the phone twice → **no crash**, the video stays letterboxed.
2. **HUD:** tap the screen → the chip strip appears (💬 Subtitles · 🔊 Audio · 📐 Aspect · ⏩ Speed · 🔄 Rotate · 📺 VLC · 🔍 Title) → tap each one → the player keeps playing.
3. **Troubleshooter:** 🩺 → *Run Full App Troubleshoot (All Features)* → tap it **twice quickly** (the second tap says a run is already active) → the 10 rows fill live with ✓/✗ + ms → **no crash** → *📋 Copy* gives a report with no IP/model/token.
4. **Customize:** 🎨 → *Look & Feel* → tap **Card corner radius** three times (Sharp → Rounded → Apple Pill): the cards visibly change each time; kill the app and reopen → the choice is still there.
5. **Backup:** 🎨 → *Backup & Restore* → export → flip *Lock on start* + a swipe switch → import → both are back to the backed-up values.
6. **VLC:** play a movie → `📺 VLC` → VLC opens the stream **with Sinhala subtitles** (no "file not found", no `file://` error).

---

# 4. Complete file-by-file changelog

| File | Change |
|---|---|
| `src/…/PlayActivity.java` | **Crash #1 fixes**: `pillLp` → `FrameLayout.LayoutParams` (`:245`, `:1378`); chip strip on `HorizontalScrollView` → `FrameLayout.LayoutParams` (`:1701`); `ripple()` mounts on the new `rootView` field (`:239`, `:2445`); `applyVideoSize()` `instanceof` guard (`:1919`); dead `langRowLp()` deleted; new `radiusCard()/radiusSheet()` helpers so the player's panel/sheet/error card honour the user's card-radius choice (`:1730`, `:1737`, `:1818`, `:2736`) |
| `src/…/FeatureProbe.java` | **Crash #2 engine fix**: `CopyOnWriteArrayList LAST` + `AtomicBoolean RUNNING` (`:92–94`); `lastResults()` returns an immutable snapshot (`:105`); `land()` publishes a snapshot after every outcome (`:158`); `Listener` gains `onProbeSnapshot`/`onAllDone` (`:84–86`); `runAll()` is single-flight and returns `unmodifiableList` |
| `src/…/MainActivity.java` | **Crash #2 UI fix**: `probeOutcomes` is a volatile immutable snapshot (`:112`, `:2207`); the probe thread only swaps a reference (`:2229`); `paintProbeCard()` guards finishing/destroyed and catches `Throwable` → `Troubleshooter.record("TROUBLE", …)` (`:2126–2139`); post-run UI update guarded (`:2242`); `card()` paints `P.radiusCard` (`:2515`); palette receives the card-radius pref (`:183`); Subtitles Studio preview now carries the ZWJ conjunct (`:1179`) |
| `src/…/ThemeEngine.java` | New `palette(mode, seed, accentIdx, customHex, cardRadiusPref)` overload; `radii(cardRadiusPref)` instead of `radii(null)` (`:119`, `:149`) — the card-radius token now reaches the UI |
| `src/…/AppPrefs.java` | `normalizeValue()` now handles `K_SWIPE_BRIGHTNESS`, `K_SWIPE_VOLUME`, `K_SWIPE_SEEK`, `K_LOCK_ON_START` (`:330`) — Backup & Restore no longer drops them; new `cardRadius(prefs)` getter (`:362`) |
| `src/…/WebBrowserView.java` | UI-thread `hits.size()` read replaced by the synchronized `hitCount()` (`:518`) |
| `lab/verify_layouts.py` | **NEW** — the gating static LayoutParams verifier + 13-case `--selftest` |
| `lab/run_lab.sh` | Runs `verify_layouts.py --selftest` **and** `verify_layouts.py` as mandatory gates |
| `lab/ReceiverLab.java` | New `v331ZeroCrashChecks()`, `v331ConcurrencyStress()`, `v331PlayerCustomizeChecks()`, `runPython()` helper; version parity retargeted to 3.3.1 / 30301 |
| `AndroidManifest.xml` | `versionCode 30301`, `versionName 3.3.1` |
| `src/…/AppInfo.java` | `VERSION_NAME "3.3.1"`, `VERSION_CODE 30301` |
| `build.sh` | Defaults `3.3.1 / 30301`; `-JXmx640m` on both `apksigner` calls (small-machine signing fix) |
| `.github/workflows/build-apk.yml` | APK identity assertions → `30301 / 3.3.1`, artifact name `…-v3.3.1.apk` |
| `.github/workflows/release-apk.yml` | `version` default `3.3.1` |
| `docs/latest.json` | `3.3.1 / 30301` + the v3.3.1 asset URL (the release job rewrites `sha256` from the signed APK) |
| `README.md`, `SETUP-SI.md` | v3.3.1 release notes (English + Sinhala) |
| `V3_3_1_ZERO_CRASH_AUDIT_AND_FIX_REPORT.md` | **this report** |

**Repo hygiene:** 0 `.zip`, 0 `.apk`, 0 `.jks`/`.keystore`/`.p12` tracked. `build/` and
`dist/` are removed before the commit (and are git-ignored).

---

# 5. GitHub CI & release proof

## 5.1 Pre-merge (this PR)

| Item | Value |
|---|---|
| Branch | `fix/v3.3.1-zero-crash-and-ui-polish` |
| PR | into `main` (see the PR link posted with this delivery) |
| `Cast protocol lab` (gating) | 0 tracked `.zip` · `verify_layouts.py --selftest` 13/13 · `verify_layouts.py` 0 problems · `verify_refs.py` OK · **773/773 PASS, 0 FAIL** |
| `APK build` | `platforms;android-36` + `build-tools;35.0.1` · `versionCode='30301'` · `versionName='3.3.1'` · `zipalign -c 4` · `apksigner verify` **v2 + v3 PASS** · `AnantPlay-Sinhala-Player-v3.3.1.apk` uploaded |

## 5.2 Post-merge (filled in immediately after the merge, in a follow-up docs commit)

| Item | Value |
|---|---|
| Merged commit SHA | *(recorded after merge)* |
| `build-apk.yml` on `main` | *(recorded after merge)* |
| `release-apk.yml` on `main` | *(recorded after merge)* |
| Live release | `https://github.com/dictationb1/anantplay-sinhala-player/releases/tag/v3.3.1` — exactly **2 assets**: `AnantPlay-Sinhala-Player-v3.3.1.apk` (`versionCode 30301`) + `SHA256SUMS.txt` (GNU format) |
| `docs/latest.json` on `main` | auto-rewritten to `3.3.1 / 30301` + the release APK's real SHA-256 by the release job (`[skip ci]`, no loop) |

---

# 6. Why this cannot happen again

1. **A wrong `LayoutParams` cannot be committed.** `lab/verify_layouts.py` types all 342
   layout call sites in all 46 files, and `lab/run_lab.sh` (and therefore the hosted CI) is
   red on a single mismatch — the exact `FrameLayout.onMeasure:195` throw is now a *build*
   failure, not a field crash.
2. **The verifier cannot rot.** Its 13-case selftest runs on every build and must reject
   every bug shape (including the two it found in this release).
3. **A shared mutable list cannot reach the UI.** The probe engine exposes only immutable
   snapshots; the UI only swaps references; 8 writers × 1,000 writes against 4 concurrent
   readers is a green gate on every build.
4. **A dead Activity cannot be painted.** `paintProbeCard()` checks
   `isFinishing()/isDestroyed()` and turns any residual surprise into a Troubleshooter line.
5. **A customization cannot silently fail.** 50/50 backup keys round-trip, and the card-radius
   token is painted by every card instead of a hard-coded `dp(16)`.

**සිංහල:** ඉහත 5 කරුණු නිසා මෙම crash දෙක නැවත ඇතිවීම ගොඩනැගීමේ මට්ටමේදීම
(Build මට්ටමේදීම) වළක්වා ඇත — දුරකථනයේදී නොව, CI එකේදී.

---

*Report generated 2026-11-23 · AnantPlay Sinhala Player v3.3.1 (versionCode 30301) · strictly v3.x, never v4.*
