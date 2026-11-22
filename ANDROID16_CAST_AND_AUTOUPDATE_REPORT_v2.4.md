# ANDROID 16 CAST & AUTO-UPDATE REPORT — v2.4.0
## Why `"announced": false` persisted, why official AnantPlay showed no Cast icon, and what v2.4.0 changes — plus the built-in GitHub self-updater

**Date / දිනය:** 2026-11-22 · **Version:** 2.4.0 (versionCode 20400)
**Field evidence:** Xiaomi Android 16 (sdk 36) diagnostics log + AnantPlay player screenshot, 2026-11-22 13:16, AnantPlay-Sinhala-Player v2.3.1
**Proof:** lab gating green (the exact N/N count is published by CI as a run annotation; the pre-v2.4.0 baseline was 152/152) · `apksigner verify` v2+v3 ✓ · `zipalign -c` ✓ · badging `20400 / 2.4.0` ✓

---

## 1 · QUESTION 1 — WHY `"announced": false` FOR 35 SECONDS ON ANDROID 16 (SDK 36)?

### 1.1 The answer in one paragraph

**YES — our own `MdnsResponder` sabotaged `NsdManager.registerService()`, and the log proves it.** On Android 14+ the registration path runs through the Java `MdnsAdvertiser`, which must complete RFC 6762 §8.1 conflict probing (3 probes, 250 ms apart, QTYPE=ANY on `<instance>.<type>.local` — `MdnsProber.java:78-95`) before `onServiceRegistered` can fire. Our v2.3.x responder answered EXACTLY those probe questions (`MdnsResponder.java:268-271` matched `qtype == 255` on the instance name), replying with an SRV record for the same name. Android compares every received answer record against the pending registration (`MdnsInterfaceAdvertiser.java:429-436` → `MdnsRecordRepository.getConflictingServices`, `MdnsRecordRepository.java:1196`): a same-named record with different data (`conflictForService`, `MdnsRecordRepository.java:1263-1290` — IDENTICAL requires equal rdata; ours differs) is a **CONFLICT** → the advertiser renames (`makeNewServiceInfoForServiceConflict`, `MdnsAdvertiser.java:250-266`) and probes the new name → for names our responder still matches, the cycle repeats. A network conflict NEVER produces `onRegistrationFailed` — the daemon silently renames forever. Result: `"announced": false` at `35.386` with ZERO registration callbacks, exactly as logged.

### 1.2 The timeline, mapped

| Log | What happened (with proof) |
|---|---|
| `0.003 arm` | `start()` #1: `nsd.registerService` ×2 listeners pending (probe phase begins inside the daemon) |
| `0.048 responder up` | v2.3.1 started the responder IMMEDIATELY — while the registration was still probing. **This is the coexistence bug.** |
| `0.050 network up — re-announcing` | `registerDefaultNetworkCallback`'s IMMEDIATE initial `onAvailable` (Android contract); v2.3.1's `NetPolicy` saw `announced=false` (callbacks hadn't arrived) → treated as unhealthy → re-arm posted |
| `0.559 responder down / 0.571 up` | the 500 ms re-arm ran `stopRegistrations()` (unregistering the pending listeners mid-probe) + re-registered — the startup grace-window hole |
| `6.435 / 26.436 answered multicast ×4` | **20-second cadence = `MdnsConfigs.timeBetweenBurstsMs() = 20_000`** (`MdnsQueryScheduler` bursts): a LIVE discovery session's browse queries — 2 types (`_anantplay._tcp`, `_fmhyplay._tcp`) × 2 sockets (IPv4 + IPv6 link-local) = 4 answers per burst. These are AnantPlay's `Cast.discover` (`App.java:88`) — proof that AnantPlay WAS actively looking for us. (They are NOT conflict probes: probes are QTYPE=ANY on the instance name, sent only during registration.) |
| `35.386 announced:false` | The registration never settled: our responder's answers kept re-conflicting the (re-armed) registration's probes for the original name. |

### 1.3 The v2.4.0 fix (three layers, all lab-proven)

1. **The responder NEVER answers QTYPE=ANY** (`MdnsResponder.answerIfForUs`, `if (qtype == 255) continue;`) — a §8.1 probe is not a query; correct RFC behavior AND it lets the daemon own its name unchallenged. Lab: `an RFC-6762 §8.1 ANY conflict probe is NEVER answered`.
2. **Distinct instance names**: the responder advertises `"AnantPlay Sinhala Player"` (`NsdAnnouncer.RESPONDER_NAME`) while NsdManager registers `"AnantPlay on සිංහල Player"`. Even a future matcher bug cannot collide with the daemon's probes. Both entries appear in AnantPlay's cast list (its `tvs` map is keyed by shown name — `Cast.java:270`).
3. **Registration-first coexistence** (`NsdAnnouncer.start()`): the responder starts only after `onServiceRegistered`/`onRegistrationFailed` (or a 2.5 s settle timeout when registration is impossible), so it can never answer probes for a PENDING registration. Registration failures now log the exact error code (`failureText`: FAILURE_INTERNAL_ERROR / MAX_LIMIT …).

---

## 2 · QUESTION 2 — WHY DID ANDROID 16's DISCOVERY IGNORE OUR 8 ANSWERS?

Even with the registration dead, our responder answered AnantPlay's browse queries — and every answer was ignored. Three wire-level defects, all fixed:

| # | Defect (v2.3.x wire) | AOSP rule violated | v2.4.0 wire |
|---|---|---|---|
| 1 | **The A record never attached** — SRV target = the bare instance name (`srvRdata` wrote instance labels + root, NO `.local`, NO type labels); A owner = `<instance>.<type>.local`. The two names never match. | `MdnsResponseDecoder` Loop 3 (`MdnsResponseDecoder.java:248-255`): an A/AAAA record attaches to a service only when `inetRecord.getName()` equals `serviceRecord.getServiceHost()` (the SRV target). Without an attached address, `isComplete()` (`MdnsResponse.java:336-341`: SRV ∧ TXT ∧ ≥1 inet) is **false**, and `onServiceFound` fires only `if (response.isComplete())` (`MdnsServiceTypeClient.java:646-651`). | SRV target = **`Sinhala-Player-<hex4>.local`** (a proper RFC 6763 §4.3 host, deterministic from the IP); A owner = **the same name** → the address attaches → `isComplete()` = true → `onServiceFound` → `resolve` gets a host → the chip appears. |
| 2 | No cache-flush bit anywhere (all classes `0x0001`) | RFC 6762 §10.2: SRV/TXT/A are unique records and MUST be `0x8001`; the decoder uses the bit to clear stale addresses (`MdnsResponseDecoder.java:236-243`) | PTR stays `0x0001` (shared), SRV/TXT/A are `0x8001` |
| 3 | No A-question answering; TXT had no friendly name | RFC 6763 §6/§12.2: resolvers send SRV then A for the target; `fn` is the Chromecast-style display name | A/AAAA questions on `<host>.local` are answered; TXT carries `app=sicast` + `fn=<instance>`; PTR TTL 4500 (standard browse TTL), unique TTLs 120 |

The lab now contains **`AospDecoder`** — a strict mirror of Android 14–16's parsing path (QR flag, ≥1 answer, label/compression rules with hop caps, UTF-8 labels ≤ 63 bytes, Loop-1/2/3 attach semantics, `isComplete`). It proves: our v2.4.0 packet is complete; a hand-built v2.3.x-shaped packet is **NEVER complete** under the same decoder (the exact field failure, pinned forever as a regression test).

---

## 3 · QUESTION 3 — OFFICIAL ANANTPLAY'S CAST ICON & FILTERING (exact lines)

1. **Where the icon is:** `PlayerActivity.java:560` — `chipCast = chip(R.drawable.ic_p_cast, …)` in the player's **chip row** (the bottom toolbar's chip strip, next to the quality/audio/subtitle chips). **Visibility:** `PlayerActivity.java:999` — `show(chipCast, !App.tv && (casting != null || Cast.any()), …)`. **The chip is completely hidden while `Cast.any()` is false** — that is why your `Binging: The Odyssey` screenshot shows only 8 icons with no Cast anywhere: AnantPlay was discovering (the 20 s bursts), but our records never became complete (§2), so `tvs` stayed empty. Discovery starts at app start (`App.java:88` `Cast.discover`) and refreshes the chips via the `changed` callback.
2. **No same-device filter:** `Cast.resolve` (`Cast.java:262-271`) only guards `r.getHost() == null` (`Cast.java:265`); `Cast.targets()` (`Cast.java:292-301`) lists everything with `host:port`. A same-phone receiver works — the video is sent to `127.0.0.1`/the Wi-Fi IP `:8765`, where our `ReceiverServer` listens (`0.0.0.0:8765`) exactly like a TV's.
3. **resolveService:** official AnantPlay uses the deprecated `nsd.resolveService` (`Cast.java:263`) — on Android 14+ this maps to the Java backend (`NsdService.java:1653-1683`): RESOLVE succeeded only with non-empty `getInetAddresses` — i.e. §2's attach fix is also what makes THEIR resolve succeed.
4. **L1 false positive — CONFIRMED AND FIXED:** at `35.388` our OWN responder (sharing 5353) answered the "daemon" probe. v2.4.0's `probeDaemon` additionally requires the DAEMON's name (`"AnantPlay on සිංහල Player"`) in the answer via `MdnsWire.mentionsUtf8` — our responder's distinct name can never satisfy it (lab-proven both ways). The Self-test also shows `⚠ daemon-announce pending` when the real registration hasn't settled.

---

## 4 · QUESTION 4 — THE UX PATH (WHY NOTHING APPEARED, WHAT APPEARS NOW)

Nothing appeared because the Cast chip requires a discovered target (`Cast.any()`), and discovery could never complete our records. **With v2.4.0's wire fix, the native Cast chip appears inside official AnantPlay's player** — bottom chip row — listing `AnantPlay Sinhala Player - AnantPlay (Recommended)` (and the daemon's `සිංහල Player - AnantPlay (Recommended)` entry once its registration settles). One tap → the video opens here with Sinhala subtitles.

For faster triggering, the app keeps (all zero-Accessibility, Xiaomi-safe): the **Quick Settings tile** (`CastTileService`, manifest:83), the **receiver notification**, and NEW in v2.4.0 a bilingual **"Where is the Cast button?"** card in MainActivity describing the exact chip and what to tap. **Honest limits:** detecting AnantPlay's playback from another app without Accessibility would need a NotificationListener (fragile on HyperOS — Xiaomi battery-killer territory) or their MediaSession (not exported to other apps); we deliberately do NOT ship either. The cast chip IS the designed path.

---

## 5 · QUESTION 5 — THE BUILT-IN GITHUB SELF-UPDATER (`SelfUpdater.java` + `SelfUpdateCore.java`)

**The honest Android 16 security model:** on non-root devices, silent zero-tap APK installation is blocked by the OS — `PackageInstaller` always shows the 1-tap system "Update" confirmation. No app can (or should) bypass that. What we CAN do — and now do, mirroring official AnantPlay's own updater:

1. **CHECK** `https://api.github.com/repos/dictationb1/anantplay-sinhala-player/releases/latest` — automatically on every app launch (rate-limited to 15 min) AND every 6 h via a persisted `JobScheduler` job (survives reboot; `SelfUpdater.CheckJob`).
2. **COMPARE** the release version against `AppInfo.VERSION_NAME` (`UpdateChecker.isNewer` — the same semver logic Obtainium relies on).
3. **DOWNLOAD** the single `.apk` asset from that release.
4. **VERIFY** its SHA-256 against the same release's `SHA256SUMS.txt` (GNU sums format; a missing line, a non-hex hash, or any mismatch → **REFUSED** and the failure banner shown). This is the security hardening: a corrupted or tampered APK can never reach the installer.
5. **INSTALL** via `PackageInstaller.Session` → the OS's own 1-tap "Update" prompt → **in-place update, same permanent keystore as the GitHub Release build, zero uninstall, all data preserved** (subs cache, prefs, the works).

Obtainium users lose nothing: Obtainium reads the same Releases with the same signature; the two paths install over each other freely. `REQUEST_INSTALL_PACKAGES` is declared in the manifest (a standard install-source permission; Android shows the source as "Sinhala Player" self-update).

### 5.1 · The Android 14–16 (targetSdk 34) traps — each one closed and lab-tested

Every PackageInstaller trap is a *decision*, so each one lives in the pure-Java
`SelfUpdateInstall` and is asserted by the gating lab (a regression in the Android
path turns the lab red):

| # | Trap (Android 14–16) | What the app now does | Lab proof |
|---|---|---|---|
| T1 | **Implicit `PendingIntent` with `FLAG_MUTABLE`** → `IllegalArgumentException` on targetSdk 34 | `commit()` receives `new Intent(c, InstallResultReceiver.class).setPackage(getPackageName())` — explicit by **component AND package**, mutable by design (the system writes the status extras) | `isCommitPendingIntentSafe(true,true)` ✓ and the two negative cases ✓ |
| T2 | **Background Activity Launch (BAL)** on `STATUS_PENDING_USER_ACTION` from the 6 h `CheckJob` | Foreground → dialog launched directly. Background (or a `SecurityException`) → a **HIGH-priority tap-to-install notification** carrying the *same* confirm intent (`App.notifyTapToInstall`); a notification tap is a user gesture, so BAL can never lose the update | `shouldPostNotification()` truth table ×3 ✓ |
| T3 | **`canRequestPackageInstalls() == false`** → session commit dies silently | Checked **before** `createSession`; when false it opens `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` for `package:lk.techworld.anantplay.sicast` plus a bilingual high-priority notice | `needsUnknownSources()` ×2 + the exact `package:` URI ✓ and an ordering assertion in the source ✓ |
| T4 | **Manifest / JobService contract** | `REQUEST_INSTALL_PACKAGES` + `RECEIVE_BOOT_COMPLETED` (required by `setPersisted(true)`); `SelfUpdater$CheckJob` with `android:permission="android.permission.BIND_JOB_SERVICE"` + `exported="false"`; the install receiver `exported="false"` | asserted against the real `AndroidManifest.xml` text ✓ ×4 |
| T5 | **Download safety** | **HTTPS-only** (the downloader itself refuses `http://` and any redirect that lands off HTTPS; only loopback is exempt so the lab can run), **30 MB hard cap** (Content-Length *and* per-chunk), **mandatory SHA-256** from `SHA256SUMS.txt` (missing/short/non-hex hash → refuse, nothing is installed), 100 % off the main thread | transport/cap/hash predicates ✓ ×5 + `SelfUpdateCore.download("http://…")` refused ✓ |

### 5.2 · `SHA256SUMS.txt` producer ⇄ consumer parity (the format cross-check)

`release-apk.yml` runs `sha256sum "AnantPlay-Sinhala-Player-v<v>.apk" > SHA256SUMS.txt`,
i.e. exactly:

```
<64 lowercase hex><SPACE><SPACE>AnantPlay-Sinhala-Player-v2.4.0.apk
```

`SelfUpdateCore.pickShaLine()` splits on `\s+` into 2 parts, lowercases the hash,
strips a leading `*` (binary-mode output), validates `[0-9a-f]{64}` and compares the
file name to `apkFileName(apkUrl)` of the asset it downloaded. `SelfUpdateInstall.sumsLine()`
+ `sumsLineParsesBack()` encode that contract as an executable assertion, so the CI
producer and the app-side parser can never drift apart (four dedicated lab checks,
including the `*` binary-mode variant).

The pure logic (`SelfUpdateCore`: release parsing via MiniJson, sums parsing, SHA-256) runs in the lab against a **local mock GitHub Releases server**: version comparison, hash acceptance, tamper rejection, missing-line refusal, offline graceful failure — all green.

---

## 6 · LAB + BUILD PROOF

- **`bash lab/run_lab.sh` → `Lab result: ALL <N> PASSED`** (the previous green run was 152/152; v2.4.0 adds the AOSP-mirror mDNS decoder, the §8.1/coexistence/NetPolicy-grace checks, the `SelfUpdater` mock-GitHub suite **and** the new Android 14–16 PackageInstaller-hardening suite: T1 PendingIntent ×4, T2 BAL ×3, T3 unknown-sources ×3, T4 manifest/JobService/receiver ×4, T5 download safety ×5, SHA256SUMS parity ×3, plus the in-source ordering/threading assertions). The exact count is published by CI as a run annotation on every push.
- **Build:** `./build.sh` → `dist/AnantPlay-Sinhala-Player-v2.4.0.apk`, `versionCode 20400 > 20301` guard ✓, `aapt2 dump badging` → `versionCode='20400' versionName='2.4.0'` ✓, `zipalign -c 4` ✓, `apksigner verify --verbose` → **v2 ✓ v3 ✓**, SHA-256 `531b2750…6073ad`.
- All v2.2–v2.3.1 wins preserved: 18 languages, Spoken/Formal, Two-Stage Cinema Polish, Option-C cap, `DohResolver`, `TitleCleaner`, `NetPolicy`, HyperOS inset scrollbars, `UpdateChecker`, keystore scripts + secret parity, `release-apk.yml` (first-release safe), `obtainium-config.json`, build-tools 35.0.1, zero Accessibility.

---

## 7 · සිංහල සාරාංශය (Sinhala summary)

ඔබගේ Android 16 දුරකථනයේ `"announced": false` වීමට හේතු දෙකක් ඔප්පු කළා: (1) අපගේම `MdnsResponder` එක NsdManager එකේ නම-ගැටුම් පරීක්ෂණ (RFC 6762 §8.1 ANY probes) ට පිළිතුරු දී ලියාපදිංචියම නැවත නැවත අසාර්ථක කළා — දැන් එය කිසිවිටෙක ANY ප්‍රශ්නවලට පිළිතුරු නොදෙන අතර වෙනස් නමක් (`AnantPlay Sinhala Player`) භාවිත කරයි; (2) අපේ පිළිතුරු පැකටයේ A වාර්තාව SRV target නමට ගැලපුණේ නැති නිසා Android එකේ decoder එක ලිපිය සම්පූර්ණ ලෙස නොපිළිගත්තා — දැන් `<host>.local` නම් දෙකම ගැලපේ, cache-flush bits නියමිත පරිදි යෙදේ. ඒ නිසාම AnantPlay player එකේ Cast chip එක (පහළ chip පේළියේ) දැන් පෙනේ — "AnantPlay Sinhala Player - AnantPlay (Recommended)" තෝරන්න. Self-test එක දැන් අසත්‍ය `L1 ✓` එකක් පෙන්වන්නේ නැත. අලුත්ම: ඇප් එකම GitHub එකෙන් ස්වයංක්‍රීයව යාවත්කාලීන පරීක්ෂා කර, APK බාගෙන, SHA-256 එක තහවුරු කර, Android එකේම 1-tap "Update" ප්‍රොම්ප්ට් එක පෙන්වයි (uninstall අවශ්‍ය නැත; Obtainium එකද එලෙසම වැඩ කරයි). Lab සම්පූර්ණයෙන් PASS (ගණන CI annotation එකේ); APK v2.4.0 (20400) apksigner-verified.

---

## 8 · Files delivered

| File | What it is |
|---|---|
| the repository itself (`main`) | The complete v2.4.0 tree — all sources, `.github/workflows/` (build + release), `.gitignore`, keystore scripts, obtainium config, updated README/SETUP-SI, and this report. **No ZIP is tracked or shipped any more** (`git ls-files '*.zip'` → 0) |
| `AnantPlay-Sinhala-Player-v2.4.0.apk` | Built by CI (20400/2.4.0), zipaligned, `apksigner`-verified (v2+v3), uploaded as the `AnantPlay-Sinhala-Player-v2.4.0-apk` artifact / the single GitHub Release asset. Your permanent keystore signs every release, so the self-updater's installs chain onto that lineage |
| `ANDROID16_CAST_AND_AUTOUPDATE_REPORT_v2.4.md` | This report |

**Install:** v2.4.0 (20400) installs directly over v2.3.1 (20301) — same package, higher versionCode, same debug-key lineage for this ZIP build. After installing: open the app once (arms the receiver + starts the 6 h update schedule), then open AnantPlay, play a video, and the Cast chip will be in the bottom chip row.
