# v3.1.0 — Cast Refusal Fix · Crash Recorder · Non-Destructive Watchdog · HyperOS Autostart · Silent Zero-Lag Updater · Mornye-Inspired Customizable UI

**Version:** `3.1.0` (`versionCode 30100`) · **Target:** Android 16 / SDK 36 · **Platform:** `platforms;android-36` + `build-tools;35.0.1`

**Field trigger:** the 2026-11-22 16:36 Xiaomi (HyperOS, Android 16) log where every cast from
official AnantPlay on the *same phone* ended in **“The TV refused this video” / “Can’t reach සිංහල Player”**,
the app then showed **“AnantPlay සිංහල Player keeps stopping”**, and update notifications
**spammed repeatedly and visibly lagged the device**.

This document is the engineering record for the six workstreams that make up v3.1.0. Every
claim below is asserted by a check in `lab/ReceiverLab.java`, which runs on a bare JVM (no
emulator, no Android SDK) and gates both CI workflows.

---

## 1. Bug 1 — `GET /dlna/desc.xml → refused-nonlocal` (same-device cast refused)

**Root cause.** v3.0.0’s `ReceiverServer.isSelf()` decided “is this my own address?” *only* by
enumerating `NetworkInterface.getNetworkInterfaces()`, inside a swallowed `try/catch`, and
compared with `InetAddress.equals()`. On HyperOS the stack can return `null`/throw under
transient netd contention early in process life, and `InetAddress.equals()` mismatches
IPv6 scope-ids — so a connection that provably originated on this phone was classified as a
stranger and refused.

**Fix — new `LocalNet.java` (pure `java.net`, lab-proven).** Each rule is independently
sufficient, and every comparison is **byte-wise**:

| # | Rule | Why it is safe |
|---|------|----------------|
| 1 | null remote → refuse | defensive only (`accept()` never yields null) |
| 2 | loopback (`127.0.0.0/8`, `::1`) → self | cannot be a LAN stranger |
| 3 | any-local (`0.0.0.0`, `::`) → self | not a routable peer |
| 4 | IPv4-mapped IPv6 `::ffff:a.b.c.d` → normalised to 4-byte IPv4 **first** | every later rule sees one shape (the dual-stack case v3.0.0 half-handled) |
| 5 | **same-host TCP rule**: `remote == the connection’s own local endpoint` | AnantPlay connects to the exact LOCATION we advertised (`http://<our-ip>:8765/…`), so source == destination. A stranger’s source can never equal our destination. |
| 6 | byte-wise membership in the live interface enumeration | `getAddress()` bytes only — never `InetAddress.equals()` (the scope-id trap) |
| 7 | byte-wise membership in the **cached own-address snapshot** (`setLocalSnapshot`) | the fallback for the HyperOS case where a fresh enumeration returns nothing |

**Server side (`ReceiverServer`).** `isSelf(Socket)` warms the snapshot on every successful
enumeration and delegates the decision to `LocalNet`. Refusals are now **HTTP 403 Forbidden**
(was `200` + JSON, which `soap()`/`describe()` treated as success), and the exact remote IP plus
the connection’s local endpoint are logged:

```
REFUSED GET /dlna/desc.xml from 192.168.1.148 (local endpoint 192.168.1.39:8765, known-own 6 addr) — not an address of this phone
```

`ReceiverService` seeds the snapshot at arm time (`LocalNet.enumerateOwn()`) and refreshes it from
the network callback. External LAN strangers (a different phone’s IP) still match no rule and are
refused — the lock is **not** weakened.

---

## 2. Bug 2 — “keeps stopping”, with no trace on disk

**Root cause.** The only record of a crash was the `Diag` ring entry, truncated at 3,000 chars and
rolled over after 1,200 lines — useless after a restart and invisible to the user.

**Fix — new `CrashLog.java`.** `App.onCreate()` installs a **chained**
`Thread.UncaughtExceptionHandler`: ours runs first, then the previously-installed default, so the
system crash dialog still appears. The report is written **atomically** (`last_crash.txt.tmp` +
rename, with a copy fallback if the rename crosses a filesystem boundary) to
`filesDir/last_crash.txt`, and contains:

* wall-clock timestamp, app version, `versionCode`;
* SDK level, manufacturer/model, Android release;
* thread name, exception class + message, **every cause and every frame** (no 3,000-char cap,
  12-deep cause chain);
* every line passed through **`Diag.redactFull()`** — newline-preserving, never truncating, so
  cookies, `Authorization` headers, API keys, tokens and JWT-shaped blobs are scrubbed but the
  stack trace survives intact.

`Diag.redactFull()` is the multi-line sibling of `Diag.redact()` (which still caps at
`MAX_LINE` for the ring). Diagnostics gains a **Last Crash Captured** card with 1-tap
Copy / Share / Clear (`MainActivity.paintCrashCard` → `CrashLog.read`).

**Re-cast hardening.** `PlayActivity.onNewIntent` now parses defensively: a malformed re-cast
never kills a running player — if the new payload yields no playable URL the previous stream is
resumed, and only a genuinely unrecoverable failure finishes the activity cleanly.

---

## 3. Bug 3 — the NSD watchdog tore down a *healthy* mDNS responder

**Root cause (from the field log):**

```
24.341 W/nsd: registration silent … re-driving (attempt 2)
24.351 I/mdnsrsp: responder down
```

The watchdog called `start()`; `start()`’s healthy gate required `announced == true`, which is
**never** true during the very `MdnsAdvertiser` silent-hang the watchdog exists for. The gate fell
through to `stopRegistrations()`, which executed `responder.stop()` on a perfectly healthy
`MdnsResponder` — the pillar AnantPlay’s casts were actively using — then re-created it 2.5 s
later. Every 12 s, then every 120 s, forever.

**Fix — new `AnnouncerPolicy.java` (pure decision logic).**

```java
enum Scope { NONE, RE_REGISTER_ONLY, RE_REGISTER_AND_START_RESPONDER, FULL_REARM }
static Scope watchdogScope(boolean responderRunning) {
    return responderRunning ? Scope.RE_REGISTER_ONLY : Scope.RE_REGISTER_AND_START_RESPONDER;
}
static boolean mayStopResponder(Scope s) { return s == Scope.FULL_REARM; }
```

* A watchdog re-drive takes `RE_REGISTER_ONLY` whenever `MdnsResponder` is alive and re-registers
  **only** the `NsdManager` records (`stopNsdRegistrations()`, which never touches the responder,
  the multicast lock or the `DlnaRenderer`).
* A watchdog may **start** a stopped responder (announcer of last resort) but never stop one.
* `FULL_REARM` — the only scope allowed to stop a responder — is reachable **only** from a genuine
  network change (`networkEventScope(sameNetwork=false)`), never from any watchdog path.

---

## 4. Bug 4 — HyperOS autostart warning that never cleared

**Root cause.** The checklist treated every Xiaomi as “not granted”
(`autostart = !isXiaomi()`), and HyperOS 15/16 additionally blocks third-party reads of
`OP_AUTO_START` (hidden AppOps op `10008`), so even an attempted read reported nothing — the Home
card showed `⚠️ Setup · Autostart [Open]` permanently, even after the user had enabled it.

**Fix — `Miui.java` 3-state model.**

```java
enum AutostartState { ENABLED, DISABLED, UNKNOWN }
```

* Best-effort **reflection** AppOps read (`checkOpNoThrow(int,int,String)`): MIUI 12–14 answer;
  HyperOS returns `UNKNOWN`. Every failure path returns `UNKNOWN` — the read can never throw or
  crash on SDK 36, and never reports a false `DISABLED`.
* `autostart_user_confirmed` (in the `sicast` prefs) plus a 1-tap **“✓ Done / මම සක්‍රිය කළා”**
  button beside `[Open]`; confirming clears the setup warning permanently.
* `autostartOk()` = `ENABLED` **or** user-confirmed — so an honest `DISABLED` re-shows the row
  (after a HyperOS reset) without nagging a user who already enabled it.

---

## 5. Bug 5 — update notifications spammed and lagged the phone

**Root causes.** `SelfUpdater` called `notify()` on **every 64 KB download chunk** (dozens of
notifications per ~3.5 MB APK), on `CHANNEL_CAST` = `IMPORTANCE_HIGH` with no `setSilent()` /
`setOnlyAlertOnce()`, so every chunk re-alerted SystemUI.

**Fix.**

| Rule | Implementation |
|------|----------------|
| Silent channel | `CHANNEL_UPDATES_SILENT` with `IMPORTANCE_LOW`, `setSound(null,null)`, `enableVibration(false)`, `setShowBadge(false)` |
| No re-alert | every update builder: `setOnlyAlertOnce(true)` + `PRIORITY_LOW` |
| No flood | `UpdateNotify` throttle: **at most one `notify()` per 1,000 ms**, plus same-percent dedupe |
| No overlap | `AtomicBoolean inFlight` single-flight guard |
| No repeat nag | 24 h per-version alert cooldown; manual “Check now” deliberately bypasses it |
| User control | **Skip this version** via `SelfUpdater$UpdateActionReceiver` (`android:exported="false"`), cleared automatically when a newer release appears |
| SDK 36 correctness | explicit `FLAG_IMMUTABLE` pending intents for our own actions; explicit component + `FLAG_MUTABLE` for the `PackageInstaller` result (the system must fill in the status extras) |
| Integrity | HTTPS-only URL, hard **30 MB** cap checked before *and* during the streamed download, strict SHA-256 against `SHA256SUMS.txt`, refusal when no hash line exists |
| Zero residue | 0-byte / leftover temp APKs swept at app start and after every hand-off |

---

## 6. Mornye-inspired UI & customization engine

* **`ThemeEngine`** — `SYSTEM · DARK (Mornye Slate) · AMOLED · LIGHT` palettes; **AMOLED** paints a
  pure `#000000` canvas while keeping a **distinct** near-black container tier (so cards stay
  visible); **6 curated accent presets**; safe Material-You seeding on **API 31+ only**, guarded by
  `Build.VERSION.SDK_INT >= 31` **and** a `try/catch` — zero crash below API 31; subtitle colour +
  pill palette helpers.
* **`AppPrefs`** — a normalizer on **every** read (`normalizeAccent`, `normalizeFontSp`,
  `normalizeSeekStep`, `normalizeSubBgOpacity`, …), so a corrupt value or a restored backup can
  never put the UI in an impossible state; UI language (`bi|si|en`), density, WebView-tab
  visibility, register override.
* **`SettingsBackup`** — export/import with **strict schema validation** (`schema: 1`, app name
  match, per-key whitelist + clamp, `Import` result object with rejected counts). Never throws;
  names the exact problem on rejection.
* **`MainActivity`** — Settings **drill-down sub-pages** (one sub-page at a time: Appearance,
  Playback, Subtitles, Cast & Network, Backup), a **non-clipping weighted hero status grid**
  (`LinearLayout.LayoutParams(0, -2, 1f)` chips, so nothing is truncated at any density), theme
  mode picker, dynamic-colour row shown as “Android 12+ only” below API 31, accent dots, and the
  one smart non-intrusive cast confirmation
  (`🟢 සිංහල Player Cast සූදානම් — AnantPlay හි Cast බොත්තමෙන් තෝරන්න`).
* **`PlayActivity`** — the **live Subtitle Styling Studio** is wired straight into the player:
  `fontSp`, `subColor`, `subBgStyle` (pill/outline), `subBgOpacity`, `subBold`, `subBottomPct`
  (4–28 %), sync step, seek step, HUD hide delay, gestures, auto-PiP, auto-resume and keep-screen-on
  all read through `AppPrefs` and repaint immediately.

---

## 7. Repository & pipeline hygiene (this release)

* **`lab/stubs/` isolation** — `build.sh` compiles **only** `src/` (plus aapt2-generated R) against
  the real `platforms/android-36/android.jar` and dexes only `build/classes`; it never sees
  `lab/stubs/`. `lab/run_lab.sh` compiles `lab/stubs/android/content/SharedPreferences.java` for the
  host JVM only, so `ReceiverLab` can drive the pure engines without an emulator.
* **Version parity** — `3.1.0` / `30100` in `AndroidManifest.xml` (via aapt2 `--version-name` /
  `--version-code`), `AppInfo`, `build.sh` defaults, both workflows, `README.md`, `SETUP-SI.md`.
  The `build-apk.yml` artifact was still named `…-v3.0.0.apk` while the manifest shipped 3.1.0; it
  is now `AnantPlay-Sinhala-Player-v3.1.0.apk`, matching `build.sh` and `release-apk.yml`, and two
  new lab checks assert the parity.
* **Zero-`.zip` hygiene** — the uploaded archive is not tracked; `git ls-files '*.zip'` returns `0`,
  and `.gitignore` blocks `*.zip`, `*.apk`, `*.jks`, `*.keystore` and friends.
* **`release-apk.yml`** — `permissions: contents: write`; triggers on `push: branches: [main]`,
  `tags: ['v*']` and `workflow_dispatch`; idempotent tag check; **exits green with a friendly notice
  when `KS_BASE64` is absent on a `push: main`** (strict failure only for an explicit tag/dispatch
  release); publishes exactly one APK + `SHA256SUMS.txt` in GNU `sha256sum` format, which
  `SelfUpdateCore.pickShaLine` parses 1:1.

---

## 8. Security posture (unchanged, re-verified)

* **Zero** `AccessibilityService` declarations and zero accessibility API references.
* **Zero** third-party ad/telemetry SDKs; **zero** `DexClassLoader` / dynamic code loading.
* **No** Contacts / SMS / Location / Phone / All-Files permissions. `READ_MEDIA_VIDEO` and
  `READ_EXTERNAL_STORAGE` (`maxSdkVersion="32"`) are requested at runtime **only** when a
  downloaded-video cast actually arrives.
* `android:allowBackup="false"`. Only the four launcher/tile/boot surfaces are exported;
  `ReceiverService`, `SelfUpdater$CheckJob`, `SelfUpdater$InstallResultReceiver` and
  `SelfUpdater$UpdateActionReceiver` are all `exported="false"`.
* `WebBrowserView` disables file access, content access, file-URL cross-origin access and
  geolocation, and refuses every non-`http(s)` navigation (`file://`, `content://`, `intent://`).

---

## 9. Verification

`bash lab/run_lab.sh` compiles the pure engines and the lab on a bare JVM and asserts **429
gating checks** (protocol, DLNA E2E, mDNS wire, stream sniffer, updater, security greps, the five
field-bug regressions of v3.1.0, the Mornye engine, and pipeline parity). Both CI workflows run it
as a hard gate; a single `FAIL` line turns the run red.

```sh
bash lab/run_lab.sh          # 429 checks, 0 failed
```

The Android build then proves the artifact: `aapt2 dump badging` must report
`versionCode='30100'` / `versionName='3.1.0'`, `apksigner verify` must pass, `zipalign -c 4` must
pass, and the shipped APK carries `assets/fonts/NotoSansSinhala-Regular.ttf` plus no
`lab/stubs` classes.
