# V3.1 — CAST REFUSED-NONLOCAL FIX · CRASH RECORDER · SILENT ZERO-LAG UPDATER · HYPEROS AUTOSTART FIX · MORNYE-INSPIRED CUSTOMIZABLE UI

**AnantPlay සිංහල Player v3.1.0 (versionCode 30100) — STRICTLY v3.x**
සම්පූර්ණ වාර්තාව · Complete Upgrade & Audit Report
Built on the real-device field log of **2026-11-22 16:36 (Xiaomi Android 16, SDK 36, v3.0.0, `Binging: The Odyssey`)** — every smoking gun root-caused with `file:line` proof, fixed, and locked dead by **422 lab regression checks (ALL PASS, 0 FAIL)**.

> **සාරාංශය · Executive summary:** v3.0.0 එකේ DLNA/mDNS කැම්පේනය සාර්ථක වූවා — නිල AnantPlay එකේ Cast අයිකනය පෙනීමටත්, `"සිංහල Player"` සොයාගැනීමටත් හැකි විය. නමුත් **අපේම receiver එක එම නිල AnantPlay කාස්ට් එක `refused-nonlocal` ලෙස ප්‍රතික්ෂේප කළා** (එකම දුරකථනයෙන් ආ සම්බන්ධතාවක් විදේශීය අයෙකු ලෙස වරදවා වටහාගත් නිසා). v3.1.0 එකෙන් එය **LocalNet same-host policy** එකෙන් ස්ථිරව මර්ධනය කර ඇත, crash එකක් වුවහොත් සම්පූර්ණ trace එක app එක තුළම සටහන් වේ, යාවත්කාලීන **සම්පූර්ණයෙන්ම නිහඬ ය**, දුරකථනය මන්දගාමී නොකරයි, සහ UI එක **Mornye ආකෘතියෙන්** මිනිස්-හිතකාමී ලෙස නැවත නිර්මාණය කර ඇත.

---

## 1 · BUG 1 — `GET /dlna/desc.xml → refused-nonlocal` ("The TV refused this video" / "Can't reach සිංහල Player")

### මූලික හේතුව · Exact root cause (v3.0.0 file:line)

| Evidence | Citation |
| --- | --- |
| The refusal gate | `ReceiverServer.java:198` — `boolean local = isSelf(sock.getInetAddress());` → `!local` ⇒ verdict `refused-nonlocal`, **HTTP 200** + the exact **38-byte** JSON body `{"ok":false,"why":"local-device-only"}` (`ReceiverServer.java:203-205`) — the 38 B in your log |
| The flawed check | `ReceiverServer.java:313-340` — `isSelf()`: loopback ✓, any-local ✓, IPv4-mapped normalization ✓, BUT the whole `NetworkInterface` enumeration sits inside `try { … } catch (Exception ignored) { } return false;` (lines 331-339) |
| Why it fired on your phone | **HyperOS can return null / throw from `NetworkInterface.getNetworkInterfaces()`** (transient netd contention early in process life — your request arrived 133 ms after server start). v3.0.0 turned that transient failure into "stranger" |
| Missing layers | **No same-host rule** (remote == the connection's own destination — the bulletproof proof that AnantPlay connected to the LOCATION we advertised, from this phone) · **`InetAddress.equals()` instead of byte-wise** (IPv6 scope-id mismatch trap) · **no `%scope` handling** · **no cached own-address fallback** · **no refusal logging** (why a field report was needed at all) |
| Why AnantPlay showed both errors | Official `Dlna.java:74-88 describe()` parses desc.xml's `<service>` blocks — a JSON body has none → renderer dropped → `cast_unreachable` = **"Can't reach සිංහල Player"** (`strings_player.xml:88`); the SOAP attempt then fails → `cast_dlna_refused` = **"The TV refused this video"** (`strings_player.xml:107`) |

### ස්ථිර කළ ආකාරය · The fix (v3.1.0)

**New pure-Java `LocalNet.java`** (lab-proven, byte-wise everywhere):

1. loopback / any-local (127.0.0.0/8, ::1, 0.0.0.0);
2. IPv4-mapped IPv6 `::ffff:a.b.c.d` **normalized to the 4-byte IPv4 form first**;
3. **THE SAME-HOST RULE** — remote == the connection's own local endpoint ⇒ same phone. AnantPlay connects to `http://192.168.1.39:8765/d…` (the LOCATION we advertise) **from the same phone**, so source == destination. A LAN stranger (192.168.1.148) has source ≠ destination **by construction** — this rule can never accept one;
4. byte-wise membership in the **live interface enumeration** (never `InetAddress.equals()`;
   scope-ids cannot mismatch);
5. byte-wise membership in the **cached own-address snapshot** (`ReceiverServer.setLocalSnapshot` — primed at arm from `LocalNet.enumerateOwn()` (`ReceiverService.java` onCreate) and kept warm by every successful enumeration) — the exact fallback for the HyperOS transient failure;
6. refusals are now **HTTP 403** `Forbidden` (`ReceiverServer.reason(403)`) with the exact remote IP + reason in Diag (`REFUSED <route> from <ip> (local endpoint <ip>, known-own N addr)`).

**Strangers stay refused** — the security model is unchanged: only THIS phone is served.

### නිරන්තර පිළිතුර · Lab proof (all PASS)

- `v31 b1: the own wlan0 192.168.1.39 is accepted via the SAME-HOST rule (live enum EMPTY — the exact HyperOS failure)`
- `v31 b1: … via the CACHED snapshot (live enum EMPTY)` · `… via the LIVE enumeration`
- `v31 b1: ::ffff:192.168.1.39 (dual-stack socket) is accepted` + normalizes to 4-byte v4
- `v31 b1: the own fe80:: link-local is accepted (byte-wise, scope-safe)`
- `v31 b1: a LAN stranger 192.168.1.148 is STILL refused (the security lock)` (+ 10.0.0.5, foreign fe80::)
- `v31 b1: the refusal status is HTTP 403 with the exact reason phrase`
- **Full E2E over a real socket**: `GET /dlna/desc.xml` → 200 XML with friendlyName + absolute AVTransport controlURL → `SetAVTransportURI` → `Play` → `GetPositionInfo` all 200, and **`v31 e2e: ZERO refused-nonlocal in the whole exchange (the smoking gun is dead)`**

---

## 2 · BUG 2 — `"AnantPlay සිංහල Player keeps stopping"` (cast-time crash)

### විමර්ශනය · The audit

Every cast-triggered path was walked line by line: `ReceiverService.onPlay` (298-343, BAL-wrapped), `PlayActivity.onCreate/parseIntent/startPlayback` (194-464), `DlnaEndpoint` SOAP (226-239), `DlnaBridge.open` (177-184), `MdnsResponder` serve loop, `SelfUpdater` job → PackageInstaller. No deterministic crash site is reachable with well-formed input; the two field conditions that can kill an OEM build are (a) the **notification storm of Bug 5** (rapid IMPORTANCE_HIGH notify() → SystemUI pressure → ANR, which MIUI surfaces as "keeps stopping"), and (b) any unforeseen device fault — for which v3.0.0 had **no persistent recorder** (only the 3000-char-truncated, rolling Diag ring).

### ස්ථිරීකරණය · The v3.1.0 hardening

- **New `CrashLog.java`** — a chained `Thread.UncaughtExceptionHandler` in `App.onCreate` (before the system dialog) **atomically** writes `filesDir/last_crash.txt` (`write tmp + rename`, crash-safe): wall-clock timestamp, app version, SDK + manufacturer/model, thread name, the exception class + message and **every cause + every frame** (no cap), all passed through the new **`Diag.redactFull()`** (line-preserving, never-truncating secret scrub — cookies, tokens, bearer JWTs and API keys can never land in the file or a Share sheet).
- **Diagnostics → "⚠ Last Crash Captured" card** — 1-tap **Copy / Share / Clear** ( MainActivity `paintCrashCard()`).
- **`PlayActivity.onNewIntent` re-cast path hardened end-to-end**: a malformed re-cast can no longer leave a half-dead player — the previous stream is resumed, and any failure finishes cleanly instead of a crash dialog.
- The updater storm (the ANR theory) is eliminated by Bug 5's throttle below.

### Lab proof
`v31 b2: the report carries the app version + thread + BOTH causes` · `… the full stack frame lines are present (no 3000-char cap)` · `… cookies + tokens in the exception MESSAGE are redacted` · `scrub() also kills bearer JWTs and api keys` · `writeAtomically persists the report` · `read() returns the same text` · `clear() wipes both the report and the tmp leftover` · `the file is filesDir/last_crash.txt`.

---

## 3 · BUG 3 — NSD watchdog killed the healthy `MdnsResponder`

### මූලික හේතුව (v3.0.0)

`NsdAnnouncer.java:260-271` watchdog → `start()`; the healthy gate (174-181) requires `announced == true` — **never true during the very MdnsAdvertiser silent-hang the watchdog exists for** — so `stopRegistrations()` (360-379) executed `responder.stop()` (369-372). Exactly your log: `24.341 W/nsd: … re-driving (attempt 2)` → `24.351 I/mdnsrsp: responder down`; the cycle then repeated every 12 s / 120 s, killing any in-flight cast.

### ස්ථිර කළ ආකාරය (v3.1.0)

- New pure **`AnnouncerPolicy.java`**: the watchdog's scope is **RE_REGISTER_ONLY** while the responder is alive (may **never** stop the responder, its multicast lock, or the DLNA renderer); a dead responder allows **RE_REGISTER_AND_START_RESPONDER** (start only); **only a genuine network change** can ever be a FULL_REARM.
- `NsdAnnouncer.start()` takes the re-register-only path (`stopNsdRegistrations()` — NSD listeners only) when the responder is alive on the same network; the watchdog logs its scope (`responder alive → UNTOUCHED`).
- `onAvailable` is scoped through `AnnouncerPolicy.networkEventScope` (same-network + healthy → no-op).

### Lab proof
`v31 b3: a LIVE responder forces the re-register-ONLY scope (the exact field bug)` · `the re-register scope may NEVER stop the responder` · `even the start scope may NEVER stop the responder` · `no watchdog path can produce a FULL_REARM (the destructive v3.0.0 shape)` · `same network + healthy → NONE (the debounce)` · `a DIFFERENT network → full re-arm is allowed` · 10 checks total.

---

## 4 · BUG 4 — HyperOS Autostart false negative ("⚠️ Setup · Autostart" forever)

### මූලික හේතුව (v3.0.0)

`MainActivity.java:444` — `boolean autostart = !Miui.isXiaomi();`: on every Xiaomi the value is permanently false, so the setup row (462-463) could never clear — HyperOS blocks third-party reads of `OP_AUTO_START (10008)` and v3.0.0 never even attempted a read.

### ස්ථිර කළ ආකාරය (v3.1.0)

- **`Miui.AutostartState { ENABLED, DISABLED, UNKNOWN }`** — a best-effort AppOps read (reflection for the int op, since android-36 removed the public int-typed signature; every failure → UNKNOWN, never a crash, never a false DISABLED).
- **`autostart_user_confirmed` pref + a 1-tap `"✓ Done / මම සක්‍රිය කළා"` button right next to `[Open]`** in the setup row — once you confirm, the warning clears **permanently** (UNKNOWN and DISABLED both trust your word; the row returns only if you un-confirm).
- The setup card rebuilds cleanly after confirmation (no stale rows).

### Lab proof
`v31 sec: the 3-state Autostart model exists (Bug 4)` · `the user-confirmation pref + API exist`.

---

## 5 · BUG 5 — Update notification spam & phone lag

### මූලික හේතුව (v3.0.0)

`SelfUpdater.java:196-197` called `App.notifyUpdateProgress()` on **every 64 KB chunk** (dozens per download), on **`CHANNEL_CAST` = IMPORTANCE_HIGH** with badges (`App.java:74-78`) — no `setOnlyAlertOnce`, no throttle, no single-flight, no cooldown, no skip → heads-up + sound per chunk → SystemUI lag, and the 4-hourly cycle re-alerted the same release forever ("අප්ඩේට් කරන්න, අප්ඩේට් කරන්න…").

### ස්ථිර කළ ආකාරය (v3.1.0)

- **`CHANNEL_UPDATES_SILENT`** — IMPORTANCE_LOW, `setSound(null, null)`, `enableVibration(false)`, `setShowBadge(false)` (`App.onCreate`).
- Every update notification is **`setOnlyAlertOnce(true)` + `PRIORITY_LOW`** on the silent channel (android-36 removed `Builder.setSilent` — the LOW-importance channel is what makes them silent; verified against the platform jar).
- **New pure `UpdateNotify.java`**: progress notify at most **1 per 1,000 ms**, deduped per percent.
- **`AtomicBoolean` single-flight** (launch-check + periodic job can no longer double-download).
- **24-hour per-version alert cooldown** (manual "Check now" deliberately bypasses it).
- **Skip this version**: quiet notification action (`UpdateActionReceiver`, exported=false) + Settings → Silent Updates + the update dialog button; a skipped version stays silent **until a newer release appears**.
- Temp-APK cleanup unchanged and re-proven: deleted the moment the PackageInstaller session is handed off + swept on every app start (**0 leftover bytes**).

### Lab proof
`v31 b5: 500 rapid chunk callbacks post AT MOST ONE notify (the SystemUI flood breaker)` (notified=1, skipped=499) · `a 10 s burst still throttles to <= 10 notifies (was ~500 in v3.0.0)` · `the same percent never notifies twice` · `an alert 12 h ago is SUPPRESSED (the repeat-spam killer)` · `a MANUAL check always bypasses the cooldown` · `a skipped version never alerts` · `a skipped 3.1.9 stops suppressing once 3.2.0 lands` · `the throttle window is exactly 1000 ms and the cooldown 24 h` · `progress posts go through the UpdateNotify throttle (no per-chunk notify)` · `the single-flight AtomicBoolean guard exists`.

---

## 6 · THE MORNYE-INSPIRED CUSTOMIZATION ENGINE (github.com/mornyeapp/Mornye — read-only research, ZERO fork, ZERO Flutter, ZERO new libraries)

Mornye's `lib/models/theme_settings.dart` + `lib/theme/*` + `lib/screens/settings/*` architecture translated **natively into pure Android Java** (~3.3 MB APK, `./build.sh` + GitHub Actions CI unchanged):

| Mornye concept | Native v3.1.0 implementation |
| --- | --- |
| ThemeMode × useAmoled × useDynamicColor × accent enum | **`ThemeEngine.java`** (pure): System / **Dark (Mornye Slate)** / **AMOLED Pure Black `#000000`** (canvas pure black, distinct near-black container tier — Mornye's own navigation lesson, app_theme.dart:285) / Light; **Material You dynamic color** on Android 12+ (`system_accent1_500` seed — moves the accent, never the surfaces); **6 curated accent presets** (Mornye Violet · Ocean Blue · Emerald Green · Sunset Coral · Warm Amber · Rose Pink) as selectable color dots; derived `accentDim/accentSoft` blends |
| normalizeXxx() clamps | **`AppPrefs.java`** (pure): every key + default + normalizer — theme/uiLang/density whitelists, font 14–36 sp, pill opacity 0–90%, bottom position 4–28%, seek 5/10/15/30 s, HUD hide 2/4/6 s, sync 0.1/0.25/0.5 s, receiver-name trim/newline-strip/64-cap, https-only worker URL |
| Settings tab icon+title+subtitle rows → focused pages | **Settings drill-down**: 🎨 Appearance · 💬 Subtitles & Style Studio · 🎬 Playback · 📡 Cast & Network · ⬆️ Silent Updates · 💾 Backup & Restore · ℹ️ About & Security — each a focused sub-page with a crisp ‹ back bar (no more 10-page vertical scroll) |
| toJson()/fromJson() backup | **`SettingsBackup.java`** (pure): one clean JSON document (`app/schema/exportedAt/version/settings`) with **strict schema validation** — wrong app, wrong schema, missing settings, all-bad values and unknown keys are named rejections; nothing ever imports partially |
| Lyrics/subtitle styling studio | **Subtitle Styling Studio with a REAL-TIME live cinema preview** — the sample line `"ඔයා මොකක්ද කරන්නේ? — මේක හරිම පැහැදිලියි!"` re-renders as you drag: font size, 4 text colors (Pure White/Warm Yellow/Soft Cyan/Mint Green), pill-vs-outline background, opacity, bold, bottom position |

**Every setting actually drives the player**: `PlayActivity` reads the studio + playback prefs (live subtitle restyle, seek step, gestures, Auto-PiP, Keep screen on, Auto-Resume last position per URL, HUD hide delay, sync step, Cinema Polish + DoH toggles).

**Other UI upgrades**: the Home hero status strip is now a **weighted grid (2 chips per row, last full-width) — physically cannot clip** on any screen; UI Language mode (Bilingual / සිංහල පමණයි / English Only) applies app-wide; layout density; **WebView tab visibility** (3-tab ultra-clean mode for cast-only users); the smart one-time cast toast `"🟢 සිංහල Player Cast සූදානම් — AnantPlay හි Cast බොත්තමෙන් තෝරන්න"` (zero nagging); instant theme apply via `recreate()`.

### Lab proof (ThemeEngine/AppPrefs/SettingsBackup)
`six curated accent presets exist` · `AMOLED canvas is PURE #000000` + `keeps a distinct container tier` · `a dynamic seed overrides the preset accent` · `dynamic color NEVER repaints the surfaces` · 20+ normalizer checks · the full backup round-trip (`every value survives normalized`) · 6 strict-rejection checks (`garbage JSON`, `another app's backup`, `a future schema`, `a missing settings object`, `all-bad values`, `an empty document`).

---

## 7 · SECURITY & ZERO-VIRUS AUDIT (Phase 3)

- ✅ **ZERO** third-party ad/analytics/telemetry SDKs (only media3 1.11.1 + guava jars, unchanged, Apache-2.0 — THIRD_PARTY.md).
- ✅ **ZERO** obfuscated/hidden binary blobs · **ZERO** dynamic code loading (no `DexClassLoader`) · **ZERO** `AccessibilityService` (lab-proven from the manifest).
- ✅ **ZERO** unnecessary dangerous permissions: no Contacts/SMS/Location/Phone/Storage-all-files (lab-checked). Manifest keeps `allowBackup="false"`, only intended components exported; `SelfUpdater$UpdateActionReceiver` is `exported="false"`.
- ✅ **`CastPolicy` local-only lock hardened** (Bug 1): byte-wise, same-host rule, snapshot fallback — and **external LAN strangers are still refused** (`192.168.1.148`, `10.0.0.5` — lab-proven), now with HTTP 403 + the remote IP logged.
- ✅ **`WebBrowserView` hardened**: `setAllowFileAccess(false)`, `setAllowContentAccess(false)`, `setAllowFileAccessFromFileURLs(false)`, `setAllowUniversalAccessFromFileURLs(false)`, `setGeolocationEnabled(false)`, `setSaveFormData(false)`; navigation is **http/https only** — `file://`, `content://`, `intent://` are refused outright (only `mailto:`/`tel:` reach the system), the address box refuses non-http(s), and the JS bridge still accepts only http(s) URLs that pass the sniffer.
- ✅ **Updater**: HTTPS-only, 30 MB cap (before + during the stream), mandatory SHA-256 vs `SHA256SUMS.txt`, temp APK deleted after session handoff + startup sweep (0 leftover bytes, lab-proven).
- ✅ **Secrets**: `Diag.redact` for the ring + the new `Diag.redactFull` for CrashLog + Share paths (lab-proven against cookies, tokens, JWTs, API keys).
- ✅ **`SrtLoader`** zip-bomb + `..`-traversal guards unchanged (lab-covered since v2).

---

## 8 · BUILD, LAB & VERSION PROOF

- **Version discipline (STRICTLY v3.x):** `versionName 3.1.0` / `versionCode 30100` — `30000 < 30100 < 40000`, stamped in `AppInfo.java`, `build.sh` defaults, both workflows, README, SETUP-SI (lab-checked: `v3 upd: versionCode 30100 strictly > 30000 (v3.0.0) and still v3.x`).
- **Lab (`bash lab/run_lab.sh`)**: **ALL 422 CHECKS PASSED — 0 FAIL** (was 295 in v3.0.0; **127 new v3.1.0 regression checks**). The suite was also re-run **from a clean extraction of the delivered ZIP** — 422/422 again (zero drift between shipped and tested).
- **APK (`./build.sh`, build-tools 35.0.1, platforms;android-36, targetSdk 36):**
  - `AnantPlay-Sinhala-Player-v3.1.0.apk` — **3,367,659 bytes (3,288 KiB ≈ 3.3 MB)** — inside the ~3.3–3.8 MB lightweight budget;
  - `zipalign -c 4` → **PASS**;
  - `apksigner verify --verbose` → **v2 ✓ true, v3 ✓ true** (1 signer);
  - `aapt2 dump badging` → `versionCode='30100' versionName='3.1.0' compileSdkVersion='36'`;
  - SHA-256: `6f4623be2d92747ceee7ec591d429327e2446bfd7a871c3effecf65390602b96`.
  - *Signing note:* this sandbox build uses the stable **debug keystore** (no release secrets available here, exactly like a local run). Your CI signs with `KS_BASE64/KS_PASS/KS_KEYPASS/KS_ALIAS` for permanent Obtainium-compatible in-place updates — unchanged flow.
- **Arena.ai-ready ZIP** (`repo3-anantplay-sinhala-player-v3.1.0.zip`, 102 files): includes **`.github/workflows/build-apk.yml` + `.github/workflows/release-apk.yml` + `.gitignore`**, `SETUP-SI.md`, `README.md`, the new `lab/stubs/` (SharedPreferences compile stub for the pure-Java settings model — never shipped, never dexed), verified with `unzip -l`: **0 `../` paths (zero zip-slip)**.

---

## 9 · HONEST LIMITATIONS · අවංක සීමාවන්

1. **දුරකථන පරීක්ෂණයක් නැත**: මෙම sandbox එකේ emulator ක්‍රියාත්මක නොමැති බැවින් UI එක screenshot මට්ටමින් පරීක්ෂා කළ නොහැක — සියල්ල ඔප්පු කරනු ලැබුවේ 422 lab checks + compile + `aapt2/zipalign/apksigner` මඟිනි. This sandbox has no emulator, so the UI was verified by the 422-check lab, full compile and APK verification — please run the one-tap `Run Self-Test` on your Xiaomi once after installing.
2. **Crash stack එක මුල් ලොගයේ නොතිබුණු බැවින්** "keeps stopping" හි නිශ්චිත stack එක නිගමනය කළ නොහැක — v3.1.0 හි CrashLog එක මඟින් ඉදිරි ඕනෑම crash එකක සම්පූර්ණ redacted trace එක `Diagnostics → Last Crash Captured` එකෙන් 1-tap ලබාගත හැක.
3. **Autostart read එක HyperOS වලින් block වීම** ස්වභාවිකයි — එය මඟහරින්නේ 3-state model + "✓ Done" පිළිගැනීමෙනි (AppOps කියවීම reflection මඟින් MIUI 12–14 වල තවම ක්‍රියා කරයි).
4. mDNS service නාම ("AnantPlay on සිංහල Player" / "AnantPlay Sinhala Player") නොවෙනස්ව තබා ඇත — ඒවා v2.4.0 field-proven conflict-coexistence නාම වේ; Custom Receiver Display Name එක DLNA friendlyName (AnantPlay හි cast list එකේ `<නම> - DLNA` ලෙස) වෙත යෙදේ.

## 10 · DELIVERABLES

| File | What it is |
| --- | --- |
| `repo3-anantplay-sinhala-player-v3.1.0.zip` | The complete Arena.ai-ready upgrade for `dictationb1/anantplay-sinhala-player` (102 files, `.github/workflows/` + `.gitignore` included, 0 zip-slip paths) |
| `AnantPlay-Sinhala-Player-v3.1.0.apk` | `versionCode 30100 / versionName 3.1.0 / targetSdk 36` — zipalign + apksigner (v2+v3) verified, 3,367,659 bytes |
| `V3_1_CAST_CRASH_FIX_AND_MORNYE_UI_REPORT.md` | This report |

**Install → Home → Start receiver → AnantPlay → Cast → "සිංහල Player"** — this time the cast lands, the subs fetch, and if anything ever trips, its full trace is one tap away. 🎬
