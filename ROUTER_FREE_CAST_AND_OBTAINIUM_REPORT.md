# ROUTER-FREE CAST & OBTAINIUM REPORT
## AnantPlay සිංහල Player v2.3.0 — රවුටරය නොවෙනස් කර Cast + සදාකාලික යාවත්කාලීන

**Date / දිනය:** 2026-11-21 · **Version:** 2.3.0 (versionCode 20300)
**Audit bases:** official AnantPlay/android `me.champdev.weblock` v2.23.1 @ `be74df2` (read-only, zero fork) · dictationb1/anantplay-sinhala-player v2.2.0 @ `38a2539`
**Verification:** lab 132/132 PASS · apksigner v2+v3 verified · zipalign OK · aapt2 badging 20300/2.3.0

---

## 1 · The exact field failure and its TRUE root cause / ඇත්ත මූලික හේතුව

### 1.1 What you saw (2026-11-22, Xiaomi HyperOS + old router)

```
✗ loopback ok, mDNS FAILED (no-answer),
[the router filters multicast (AP isolation?) – check the router's AP-isolation/multicast settings]
bridge ok.
```

### 1.2 The proven chain (every claim cited to code you can open)

| # | Fact | Proof |
|---|------|-------|
| 1 | The v2.2.0 Self-test sent its mDNS question from a **plain ephemeral unicast socket** — it never joined the multicast group, so it could only ever receive unicast replies. | `NsdAnnouncer.java:257` `new DatagramSocket(new InetSocketAddress(wifi, 0))` |
| 2 | The question packet asked for a unicast reply (the QU bit) — correctly intended. | `MdnsWire.java:40` (QCLASS with QU) |
| 3 | **BUT the QU bit was written into the WRONG byte**: `0x01|0x80 = 0x81` went into the **low** class byte, producing "class 129" — a meaningless value. The real unicast-reply request is the **top bit of the 16-bit class field**: bytes `0x80,0x01`. v2.2.0's wire bytes never actually carried the QU flag. | v2.2.0 `MdnsWire.java:40` `b.write(0); b.write(0x01|0x80);` → fixed in v2.3.0 to `b.write(0x80); b.write(0x01);` |
| 4 | So the phone's own `mdnsd` daemon (which **does hold** our NsdManager registration, `NsdAnnouncer.java:133`) answered with a **multicast** reply to 224.0.0.251:5353 — which the ephemeral-port probe socket cannot receive (multicast is delivered by destination port). | Kernel UDP semantics; responder reply path |
| 5 | Even if the QU bit had been correct, on an old router with multicast snooping and no IGMP querier the multicast question may never loop back to the sender's own interface — no responder ever sees it. | Old-router multicast behaviour |
| 6 | **The classic `setLoopbackMode` bug is NOT present**: neither repo ever calls `setLoopbackMode` (verified by grep over both codebases). v2.3.0 calls it explicitly and correctly — `setLoopbackMode(false)` = loopback ENABLED (Java's inverted API). | `MdnsResponder.java` probe + `NsdAnnouncer.probeSelf` |
| 7 | **Casting itself was never broken on this phone**: official AnantPlay's discovery (`Cast.discover`, `Cast.java:239-259`) goes through **NsdManager binder IPC to the same system `mdnsd`** our registration lives in — daemon-to-daemon, entirely inside the phone, **no router multicast required**. The v2.2.0 test probed the wrong path and produced a false negative. | `Cast.java:243-257` (phone side) vs `NsdAnnouncer.java:133` (our side) |

**Bottom line / සාරාංශය:** v2.2.0 එකේ Self-test එක **වැරදි මාර්ගයක්** පරීක්ෂා කළේය — රවුටරය මඟින් multicast ආපසු පරාවර්තනය වන බවට සිතා (packet එකක QU bit එකක් ද වැරදියට ලියා) ඇති. AnantPlay එක ඇත්ත වශයෙන්ම අපව සොයන්නේ **phone එක තුළම ඇති mdnsd daemon එකෙන්** — රවුටරයේ multicast අවශ්‍ය නැත. v2.3.0 එක මෙය නිවැරදි කරයි: wire bug එක නිවැරදි, අපේම mDNS responder එකක්, සහ අවංක 3-තට්‌ටු self-test එකක්.

---

## 2 · The v2.3.0 zero-router architecture / රවුටරය නොවෙනස් කරන නිර්මාණය

### 2.1 The three self-test layers (PASS = any one)

```
L1 DAEMON PROBE   unicast QU question → 127.0.0.1:5353 + <wifi-ip>:5353
                  (mdnsd answers from its cache — the exact record set
                   AnantPlay's NsdManager discovery reads; 100% in-phone)

L2 WIRE PROBE     our own MdnsResponder on 5353 (SO_REUSEADDR beside mdnsd):
                  probe socket AND responder both joined to 224.0.0.251 on the
                  Wi-Fi interface → question + unicast answer travel through the
                  phone's KERNEL MULTICAST LOOPBACK — router reflection irrelevant

L3 LEGACY         NsdManager.discoverServices (bonus signal; some OEM stacks
                  filter same-uid discovery — HyperOS sometimes does)
```

### 2.2 What each layer means for casting

| Layer | What it proves | Router needed? |
|-------|----------------|----------------|
| L1 daemon answer | The record is in the daemon AnantPlay reads → **the cast list will show us** | No |
| L2 wire answer | Our responder + kernel loopback work → same-phone + cross-app discovery robust even with broken multicast | No |
| L3 legacy | OEM same-uid discovery allowed | No |

**Why casting already worked:** AnantPlay's `Cast.discover()` asks NsdManager; NsdManager asks the phone's `mdnsd`; `mdnsd` holds our registration. All three live inside the phone. Your old router's multicast filtering cannot touch this path — which is exactly why the v2.2.0 "FAILED" verdict was a false alarm for real casting.

### 2.3 The mDNS responder (MdnsResponder.java)

- `MulticastSocket(5353)` + `setReuseAddress(true)` — coexists with `mdnsd` (proven in the lab: both bind 5353 simultaneously; EADDRINUSE degrades gracefully to NsdManager-only announcing, logged, never a crash).
- Answers `PTR? _anantplay._tcp.local` **and** legacy `_fmhyplay._tcp` with a full authoritative response: PTR + SRV (port 8765) + TXT (`app=sicast`) + A (the phone's Wi-Fi IP) — with name compression and a proper `0x8400` AA header.
- Replies **unicast to the querier's address:port when the QU bit is set** (RFC 6762 §5.4), multicast otherwise.
- Interface-explicit `joinGroup` — only joins interfaces that are **up, non-loopback and multicast-capable** (a kernel that accepts a loopback join then blackholes packets was exactly the silent failure class this project refuses to ship).
- `setLoopbackMode(false)` — explicit and correct (Java's inverted semantics: `false` = loopback ENABLED).

### 2.4 What the old router CAN still break (the honest list)

| Scenario | Impact | v2.3.0 answer |
|----------|--------|---------------|
| Router blocks client-to-client multicast | **Another phone** on the same Wi-Fi cannot discover us via mDNS | The official AnantPlay cast is same-phone (daemon path) — unaffected. Cross-phone needs the router to pass multicast or the hotspot trick (§4.4). |
| AP isolation | Same as above for multicast AND unicast between clients | Same — same-phone casting unaffected. |
| Mobile Data (no Wi-Fi) | No mDNS at all | The No-Wi-Fi fallback card: copy the stream link in AnantPlay (Share ⋮ → Copy link) → paste into the "Play URL" box — subtitles + playback work on any network (the bridge and the stream are internet-side). |

---

## 3 · Predictive audit — what would have broken next / ඊළඟට කැඩෙන්නේ කුමක්ද

### 3.1 Hidden Killer #1 — WARP-proxied streams (ISP-blocked sites)

**The finding (from official code):** the official player sends its video through `Warp.proxyFor(host)` — a **phone-side** HTTP proxy to the bundled Cloudflare WARP helper (`PlayerActivity.java:1079`, `Warp.java:202-207`). `Cast.describe` (`Cast.java:342-354`) forwards the **raw URL + headers + cookie** — never a WARP-proxied URL. So a stream whose host your ISP blocks (the reason WARP was on) arrives at our ExoPlayer **directly blocked**: DNS hijack answers a wrong IP or the connection times out.

**The honest fix (v2.3.0):**
- We cannot ship Cloudflare WARP — `libwarp.so` is AnantPlay's binary. No fork allowed anyway.
- **DoH diagnostics (`DohResolver.java`)**: on a network-ish playback error we resolve the stream host via **Cloudflare `1.1.1.1` DoH** (IP-pinned, no bootstrap DNS needed) with **AdGuard DoH fallback**, and compare against the system resolver. A mismatch (or a system failure where DoH answers) is announced bilingually as *"ISP DNS බාධාවක් සැක සහිතයි — system DNS සහ DoH එකට වඩා වෙනස් පිළිතුරු දෙයි"* with both IP sets shown, plus the practical advice (play the same video in AnantPlay with WARP on, or use the direct-URL handoff).
- **One-tap retry**: the error box itself is now tappable — retry rebuilds the player **at the last known position** (fresh DataSource; expired HLS tokens get re-fetched).
- Headers/cookies are NOT the issue: `Cast.describe` forwards them and we already send them (`PlayActivity` `setDefaultRequestProperties`).

### 3.2 Hidden Killer #2 — noisy titles / missing IMDb

**The finding:** for arbitrary streaming sites `imdb` arrives empty and `title` is the site's page title — *"Watch The Odyssey 2026 Full Movie Free HD - OnlyFlix"*, *"Episode 3 - 1080p - somesite.to"*. Fed raw to the worker's `/api/resolve`, Cinemeta's fuzzy match fails or mismatches → subtitles die at step one.

**The fix (`TitleCleaner.java`, pure Java, 12 lab checks):**
- Strips site suffixes (both ` - OnlyFlix` and ` | somesite.to` shapes), noise tokens (watch/full movie/free/HD/1080p/WEBRip/x264/HEVC/…), leading verbs, trailing generic words, separator crumbs and brackets.
- Extracts **year** (1930–2049), **SxxExx**, **Season N Episode M**, bare **Episode N** — season/episode found here repair series lookups where the cast payload had none.
- ALL-CAPS titles become Title Case; **Sinhala titles pass through untouched** (proven: "ආයුබෝවන් සිංහල චිත්‍රපටිය 2026 HD" → year extracted, Sinhala words intact).
- **Idempotent** (stable cache key), never throws, falls back to the raw title when cleaning fails — and the resolve path retries once with the raw title if the cleaned form doesn't match. The HUD keeps showing the original title; only the lookup uses the cleaned form.

### 3.3 Hidden Killer #3 — worker v4.1 edge cases

| Case | Audit result | v2.3.0 change |
|------|--------------|---------------|
| 2-hour movie, 25–40 s translate | `pollToDone` polls 240 × 4 s ≈ 16 min with 429 back-off — adequate | none needed (verified) |
| **Language switch mid-movie** | `fetchGen` guard already stopped stale `applyCues`, **but** a stale thread could still flip the status pill to "translating 3/12" *after* the switch — confusing, occasionally looking like a wrong-language fetch | **`setState` is now generation-guarded** — a stale thread cannot touch the UI at all |
| SUBS_KV missing | Self-test already warns (storage=cache+memory) | kept; `/info` surfaces more state |
| Two-stage swap | `cues = got` inside `main.post` — thread-safe (verified) | none needed |
| Expired HLS token / stream error | Generic error box, no recovery path | **tap-to-retry at last position** (§3.1) |
| AudioFocus / screen sleep / rotation / PiP | `setAudioAttributes(…, true)`, `FLAG_KEEP_SCREEN_ON`, `configChanges`, auto-PiP after hand-over — all verified present | none needed |

### 3.4 Hidden Killer #4 — HyperOS long-term survival

- The `connectedDevice` FGS + `START_STICKY` + `BootReceiver` (BOOT_COMPLETED / MY_PACKAGE_REPLACED / QUICKBOOT) + Quick Settings tile + notification actions: all verified Android 14–16 hardened (startForeground-first inside the 5 s window, `ForegroundServiceStartNotAllowedException` caught by name).
- Overlay fallback: without the grant the cast still lands as a high-priority tap-to-open notification — verified.
- **New in v2.3.0:** a 15-minute **self-heal** timer re-checks `server.isRunning()` + announcement state and re-arms if a powerkeeper pass half-killed the receiver — defensive, cheap, fully wrapped.

---

## 4 · Zero-router fallback paths audited / විකල්ප මාර්ග විමර්ශනය

### 4.1 Manual cast IP / subnet scan in official AnantPlay?
**Not supported.** `Cast.targets()` (`Cast.java:299-307`) lists only what NsdManager/SSDP filled — no manual entry, no 8765-sweep. (Zero-fork means we cannot add one.)

### 4.2 DLNA/UPnP (SSDP) as a bypass?
**No.** `Dlna.search()` (`Dlna.java:39-63`) sends `M-SEARCH` to `239.255.255.250:1900` from an ephemeral socket — the **same multicast dependency**; a router that filters 224.0.0.251 almost always filters 239.255.255.250. Not a router-free path.

### 4.3 Chromecast?
Honest answer: `_googlecast._tcp` discovery rides the same NsdManager daemon (works same-phone), but **becoming** a Cast device means TLS + device auth we cannot and should not fake. Out of scope — the AnantPlay-to-AnantPlay protocol is strictly better for this use case.

### 4.4 Same-phone hotspot trick
When the phone hosts a hotspot it IS the AP — client↔host multicast doesn't traverse AP isolation. Works, but you leave your home Wi-Fi. Documented as a fallback for cross-phone casting only; **same-phone casting (the normal case) needs nothing at all**.

### 4.5 Mobile Data / hostile router (the guaranteed fallback)
AnantPlay → Share ⋮ → Copy link → paste into the app's **Play URL** box. Playback + Sinhala subtitles work on any network (worker + stream are internet-side). Surfaced as the "No-Wi-Fi fallback" card in v2.3.0.

---

## 5 · The release pipeline / නිකුතු පයිප්ලයිනය — zero-uninstall forever

### 5.1 WHY Android blocks updates / Android යාවත්කාලීන අවහිර කරන්නේ ඇයිද

Android එක install කරන app එකක signing certificate එක මතින් හඳුනාගනී. Update එකක certificate එක වෙනස් නම් එය "වෙනම app එකක්" ලෙස සලකා **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`** එකෙන් ප්‍රතික්ෂේප කරයි — uninstall කර අලුතෙන් දැමිය විනා වෙන මඟක් නැත. GitHub CI runner එකක් හැම run එකකම හිස් නිසා "CI debug key" හැම එකක්ම වෙනස් ය — එනිසාම මෙතෙක් release APK එකක් අත්සන් කිරීමට **ස්ථිර keystore එකක්** අවශ්‍ය විය. (English: Android identifies an app by its signing certificate; a different certificate means a different app and the in-place update is refused. Ephemeral CI keys change every run — a permanent local keystore is the only way.)

### 5.2 The one-time setup (15 minutes, then forever)

1. **Create the permanent keystore on YOUR PC** (never inside GitHub):
   - Windows: `pwsh -File scripts\create-release-keystore.ps1`
   - Linux/macOS: `sh scripts/create-release-keystore.sh`
   - Makes `release-keystore.jks` (RSA-2048, 10,000-day validity) + `github-secrets.txt` (the 4 secrets; KS_BASE64 also lands on your clipboard). **Back both up** (password manager + second disk) — lose the key and users uninstall once.
2. **Set the 4 GitHub Secrets** (Settings → Secrets and variables → Actions): `KS_BASE64`, `KS_PASS`, `KS_KEYPASS`, `KS_ALIAS`.
3. **Publish**: `git tag v2.3.0 && git push origin v2.3.0` — or Actions → *Release APK* → Run workflow → enter `2.3.0`.

### 5.3 What release-apk.yml guarantees

1. The **Lab must pass 100%** before anything is released (132 checks gate the run).
2. Builds with `build.sh` (build-tools 35.0.1, platform 34 — the d8 8.2 crash guard kept).
3. **Refuses to publish without `KS_BASE64`** — a debug-signed release would silently break everyone's updates. With it: permanent-keystore signing, `apksigner verify` (v2+v3), zipalign check, and the **badging assertion** (versionCode 20300 / versionName 2.3.0 — verified, not assumed).
4. `versionCode` is computed from the tag (v2.3.0 → 20300) and `build.sh` **enforces strictly increasing** code (`PREV_VERSION_CODE` guard) — an accidental downgrade cannot ship.
5. Writes `SHA256SUMS.txt`, creates the tag (manual dispatch), and publishes a Release with **exactly ONE `.apk` asset** — `AnantPlay-Sinhala-Player-v2.3.0.apk` — so Obtainium needs zero regex guessing.

### 5.4 Obtainium — the 3-tap setup (phone)

1. Install Obtainium → **+ Add App**
2. Paste `https://github.com/dictationb1/anantplay-sinhala-player` → Add (defaults work: one APK asset per release). Or import `docs/obtainium-config.json`.
3. Install → every future release shows up → **Update** — in place, no uninstall, ever.

### 5.5 In-app update checker

The app's **"⬆ Check for updates (GitHub)"** button calls `releases/latest`, compares cleanly (numeric semver — `2.10.0 > 2.9.0`, missing parts as zero) and opens the release page when newer. Rate-limit (403) and no-release (404) are handled with clear text, never a crash.

### 5.6 The proven test results

| Gate | Result |
|------|--------|
| Lab (gating) | **132/132 PASS** — including the live **router-free kernel-loopback probe** (sent=1, recv=1: our responder answered through kernel multicast loopback with NO router involvement), the responder's byte-level response contract (AA header, PTR+SRV+TXT+A, port 8765 in SRV, QU-bit detection), the title cleaner (12 cases), DoH parsing, and semver logic |
| Build | aapt2 + javac (JDK 17) + d8 8.6.2 clean |
| Signature | apksigner: **v2 ✓ v3 ✓**, `Verifies` — both the debug key and the full release-keystore path (test keystore, `CN=AnantPlay Sinhala Player Release`) were exercised |
| Version | versionCode **20300** > 20200 ✓, versionName **2.3.0** ✓ |
| ZIP | 80 files, **`.github/workflows/build-apk.yml` + `.github/workflows/release-apk.yml` + `.gitignore` all present** (unzip -l verified), **0 `../` zip-slip paths** |

---

## 6 · Files delivered / ලබාදෙන ගොනු

| File | What it is |
|------|------------|
| `repo3-anantplay-sinhala-player-v2.3.zip` | The complete v2.3.0 upgrade — all sources, `.github/workflows/` (build + release), `.gitignore`, `scripts/create-release-keystore.{sh,ps1}`, `docs/obtainium-config.json`, README/SETUP-SI updated |
| `AnantPlay-Sinhala-Player-v2.3.0.apk` | The freshly compiled, zipaligned, **apksigner-verified** APK (versionCode 20300). Signed with a local debug key — install it freely; when your permanent keystore exists, GitHub releases replace it with the release-signed lineage |
| `ROUTER_FREE_CAST_AND_OBTAINIUM_REPORT.md` / `.pdf` | This report |

**Upgrade note:** replace the repo contents with the ZIP's contents (or merge the branch); the two workflows, the scripts/ directory, the four new engines (`MdnsResponder`, `TitleCleaner`, `DohResolver`, `UpdateChecker`) and the seven modified files are all inside.

---

## 7 · Honest limitations / අවංක සීමා

1. **The delivered APK is debug-signed.** Your permanent keystore must be created **by you** (`scripts/create-release-keystore.*`) — that is the entire point of zero-uninstall: the key never leaves your machine. Until the first release-signed build, update over it once (or uninstall the debug build first — one time only).
2. **Cross-phone mDNS on a multicast-hostile router**: same-phone casting (the normal AnantPlay→Sinhala-Player flow) is router-independent, but discovering our receiver **from another phone** still needs the router to pass multicast (or the hotspot trick). No app can fix a router that drops client-to-client multicast without touching it.
3. **DoH diagnoses and explains ISP DNS blocks; it does not bypass IP-level blocks.** For those, the practical route remains: play the video in AnantPlay with WARP on, or use the direct-URL handoff.
4. The in-app update checker needs GitHub to be reachable; Obtainium is the primary update path.
