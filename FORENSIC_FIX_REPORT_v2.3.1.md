# FORENSIC FIX REPORT — v2.3.1
## The `mdnsrsp` answered-but-no-answer paradox, the 4-packet loopback storm, the UI clipping — exact causes, exact fixes

**Date / දිනය:** 2026-11-22 · **Version:** 2.3.1 (versionCode 20301)
**Field evidence:** Xiaomi HyperOS diagnostics log + Self-test screenshot, 2026-11-22 11:15, AnantPlay-Sinhala-Player v2.3.0
**Verification:** lab **152/152 PASS** (20 new field-scenario regression checks) · `apksigner verify` v2+v3 ✓ · `zipalign -c` ✓ · badging `20301 / 2.3.1` ✓

---

## 1 · THE PARADOX: 16+ "answered a unicast(QU) query" lines, yet "mDNS FAILED (no-answer)" — fully explained

### 1.1 The one-sentence answer / එක් වාක්‍ය පිළිතුර

The replies ARRIVED and were ANSWERED correctly by `MdnsResponder` — but the two probes **decoded the reply with the wrong character set and searched for the Sinhala instance name**, which can never match: `"සිංහල"` is 15 **UTF-8** bytes, and decoding those bytes as **ISO-8859-1** yields mojibake (`à·ƒ…`) that `.contains("සිංහල Player")` can never find. Every genuine reply was counted as silence.

### 1.2 The exact broken code (v2.3.0)

| Site | Code | Why it can never match |
|---|---|---|
| L1 daemon probe | `NsdAnnouncer.java:272-273` — `new String(buf, 0, len, ISO_8859_1)` then `text.contains(NAME)` where `NAME = "සිංහල Player"` (`NsdAnnouncer.java:72`) | The responder (and `mdnsd`) write the instance name as **UTF-8** (`MdnsResponder.java:368-372`, `writeLabels → getBytes(UTF_8)`); a Latin-1 decode of those bytes is not the Sinhala string |
| L2 wire probe | `MdnsResponder.java:433-434` — same ISO-8859-1 decode + `text.contains(responder.instanceName)` | Same mojibake math |
| L3 legacy NSD | HyperOS `mdnsd` filters a uid's own registration out of same-uid discovery → `onServiceFound` never fires | OEM behaviour (the reason L1/L2 exist at all) |

### 1.3 Who owned the two ports in your log

- **`127.0.0.1:41138`** = `probeDaemon`'s socket (`NsdAnnouncer.java:255`, `new DatagramSocket()`). Its unicast questions to `127.0.0.1:5353` are sourced from this ephemeral port. When `wifiLanIp()` returned null on your build, BOTH loop targets were `127.0.0.1` (v2.3.0 line 262-263) → duplicate sends, both answered, both logged.
- **`127.0.0.1:43882`** = `probeLocal`'s `MulticastSocket` (`MdnsResponder.java:410-412`, bound wildcard). Its multicast send egressed the kernel's default multicast route — **loopback on your build** — so the source was `127.0.0.1:43882`. The responder (bound `0.0.0.0:5353`, joined `wlan0`) still received the locally delivered copy — your log proves it — and replied unicast to `127.0.0.1:43882`, which the wildcard-bound probe received… and then the mojibake match failed.

### 1.4 The 4-packet / ~207 ms cadence — a spin loop, not an echo loop

- `probeDaemon` (v2.3.0 `NsdAnnouncer.java:260-279`): the unicast reply returns in under 1 ms, so `receive()` (timeout 500 ms) never blocks; the failed match means no early return; the loop re-sends immediately. Every 4th round sleeps 200 ms (line 278) — exactly your ~207 ms cadence. Two sends per round × fast rounds = the ×4 bursts, repeating until the 2500 ms deadline.
- `probeLocal` (v2.3.0 `MdnsResponder.java:425-441`): sends on **every** iteration (line 426 — the `round % 5` guard existed only in the dead `probeSelf`, not here). With kernel loopback enabled, its own question AND the responder's reply both arrive instantly → a full-speed send spin for the whole window.
- Both sockets interleaving = the 16-line flood you captured. **There was no self-reply/echo loop**: `answerIfForUs` rejects responses (`MdnsResponder.java:216`, QR bit check), so the responder never answers its own answers.

### 1.5 The v2.3.1 fix

**`MdnsWire.isOurAnswer(pkt, len, type, legacyType)`** (new, `MdnsWire.java`): validates **structurally and only against ASCII** —
1. QR bit set (a response — our own looped-back question can never false-positive),
2. AN+NS+AR record count > 0,
3. the pure-ASCII service type `_anantplay._tcp` present in the packet (ASCII is invariant in ISO-8859-1, so the decode used for that check can never mojibake).

`mentionsUtf8()` (new) does the optional instance-name detail check with a correct UTF-8 decode. Both probes now validate with `isOurAnswer`. The lab **reproduces the exact field scenario**: the old ISO-8859-1 check is proven to fail on the real Sinhala reply bytes while the new validator accepts the very same packet (checks `v2.3.1 field: …`).

### 1.6 Is the Sinhala instance name itself unsafe? — NO (audited against official AnantPlay)

- RFC 6763 §4.1.1: instance names are **opaque UTF-8**, ≤ 63 bytes per label. `"AnantPlay on සිංහල Player"` = 35 bytes ≤ 63. `MdnsResponder` writes the correct label length (verified by the lab's byte-round-trip check).
- Official AnantPlay handles it correctly end-to-end: `Cast.resolve` takes `r.getServiceName()` (a proper Unicode String, `Cast.java:262-264`), `Cast.shown()` only strips the `"AnantPlay on "` prefix (`Cast.java:61`), and the cast list renders `"සිංහල Player - AnantPlay (Recommended)"` (`strings_player.xml:94`). Your own v2.2/v2.3 logs show `announced … as "AnantPlay on සිංහල Player"` — NsdManager accepted it.
- **Decision: the Sinhala name stays.** An ASCII name would merely have masked the real bug (the match would coincidentally have succeeded).

---

## 2 · THE `network up — re-announcing` CHURN RACE — fully explained and fixed

### 2.1 The loop, from your log (25.304 → 25.321, repeating)

```
start() → stop()                      (NsdAnnouncer.java:109-111 — stop FIRST)
stop() unregisters netWatch, nulls it  (NsdAnnouncer.java:224-232, v2.3.0)
start() → watchNetwork() re-registers  (line 145)
registerDefaultNetworkCallback fires onAvailable IMMEDIATELY (Android contract)
onAvailable → reArm posted 500 ms      (lines 176-179)
reArm → start() → stop() → …           — an endless down/up cycle
```

Your log lines are one cycle: `25.304 responder down (answered 12 queries)` — **the cycle murdered a responder that had just answered 12 queries** — then `25.313 lock re-acquired`, `25.316 responder up`, `25.321 network up — re-announcing` (the next iteration's callback). Cost: NsdManager register/unregister churn (OEM stacks rate-limit → `announce failed` risk), the multicast lock bouncing, battery drain, and the self-test observing a responder restart mid-probe.

### 2.2 The v2.3.1 fix (three layers)

1. **`start()` is idempotent** — it computes `netKey(ctx)` (transport + current Wi-Fi IP) and no-ops when everything is healthy on the same network (`announced && responder running && same IP && same key`).
2. **The callback is registered once and only released by `destroy()`** — restarts call the new `stopRegistrations()` (registrations + responder + lock only), never unregistering `netWatch`. `ReceiverService.onDestroy` still calls the full `stop()`.
3. **`NetPolicy.shouldReArm`** (new, pure Java, lab-tested truth table): re-arm only when the network genuinely changed, something is unhealthy, or we never armed. A repeat `onAvailable` on the same healthy network is ignored. An unhealthy stack re-arms immediately even if we armed moments ago (a dead responder must be rebuilt — the lab pins this ordering).

---

## 3 · THE RIGHT-EDGE UI CLIPPING — fully explained and fixed

All three cut fragments (`GitHub Relea`, `…Release whe`, `…automatical`) are consecutive lines of the update card's `cap()` TextView (`MainActivity.java:260-262` in v2.3.0), sliced at the **same x** — a vertical band over the right edge.

- **Root cause:** the ScrollView used the default `SCROLLBARS_INSIDE_OVERLAY`; on HyperOS/MIUI the scrollbar is a fat translucent band drawn **over** content while scrolling — exactly the observed band.
- **Fix:** `SCROLLBARS_INSIDE_INSET` on the ScrollView **and** the diagnostics `logView` (inset reserves layout space instead of covering text).
- **Belt-and-braces:** every full-width child (`cap`, `head`, check rows, permission cards, buttons, EditText, `selfTestLine`) now carries explicit `MATCH_PARENT` layout params instead of generated defaults; the player's bottom time labels now share the free space with weights (fixed icons take 228 dp on a 360 dp screen — the old non-weighted labels could overflow).

---

## 4 · THE HOLISTIC AUDIT — everything else verified

| Area | Finding |
|---|---|
| **Self-test re-entry** | v2.3.0 let every tap spawn another probe thread (your log's two simultaneous sockets). v2.3.1: an `AtomicBoolean` guard; the result line now shows **`[L1 daemon ✓ · L2 responder ✓ · L3 NSD ✗]`** per layer — a red verdict can never hide an answering responder again. |
| **Log/CPU spam** | The responder aggregates: one line per source per second (`…(×N in 1 s)`); probes are bounded (≤ 4 rounds / ≤ 6 sends, stop at first validated answer); idle cost is zero. |
| **Real cast path** | Unchanged and unaffected: official AnantPlay discovers via NsdManager → `mdnsd` daemon cache (same-phone binder IPC). The paradox was purely a self-test defect; casting worked all along. |
| **Live Worker (probed 2026-11-22)** | `{"ok":true,"version":"4.1.0","storage":"kv",providers:{pa:off,azure:off,google_v3:off,**gemini:configured**,workers_ai:off},languages:{count:18,default:si,chips:[si,ta,hi,en,es,ja,ko,ar]}}` — `Langs.java` (18 languages, 8 chips) matches exactly; `storage=kv` means SUBS_KV is bound (no needs-key risk); the Gemini chain is live; `BridgeApi` endpoints unchanged. **Zero protocol mismatches.** |
| **Release pipeline** | All 4 secret names (`KS_BASE64`, `KS_PASS`, `KS_KEYPASS`, `KS_ALIAS`) byte-identical across `create-release-keystore.sh`, `.ps1`, `build.sh`, both workflows, and SETUP-SI (lab-checked). The `.ps1` is PowerShell 5.1-safe (`Get-Command -SilentlyContinue`, `Set-Clipboard` in try/catch, expandable here-string, `Out-File -Encoding utf8`). `release-apk.yml` handles a first-ever release: the tag-push trigger checks out the tag itself; the tag-create step runs only on manual dispatch and guards "already exists". |
| **v2.2/v2.3.0 wins preserved** | 18 languages, Spoken/Formal, Two-Stage Cinema Polish, Option-C 40 KB cap, `DohResolver`, `TitleCleaner`, `UpdateChecker`, `release-apk.yml`, `obtainium-config.json`, build-tools 35.0.1 guard, zero Accessibility — all intact. |

---

## 5 · Lab proof (the exact bug can never return)

New `v2.3.1 field:` regression block in `lab/ReceiverLab.java` (20 checks):

1. The Sinhala instance name is answered on the wire; the ISO-8859-1 decode is **proven unable** to contain it (the old bug, pinned forever); `isOurAnswer` accepts the **same bytes**; a looped-back QUESTION never validates; a foreign type never validates; the 15-byte UTF-8 label appears verbatim with the correct length (≤ 63).
2. The live router-free probe answers with the Sinhala name; sends are **bounded ≤ 6**; a healthy probe stops at the **first** answer (≤ 2 sends) — the storm is structurally impossible.
3. A **unicast** question to the responder (the L1 path) is answered AND validated.
4. The `NetPolicy` truth table: first arm ✓, different network ✓, unhealthy ✓, same-healthy-network ✗ (the loop fix), immediate re-register replay ✗, `sameKey` edge cases.
5. Secret-name parity across all 5 pipeline files + `versionCode 20301 > 20300`.

**Result: 152/152 PASS (0 FAIL).**

Build proof: `aapt2 dump badging` → `versionCode='20301' versionName='2.3.1'` · `zipalign -c 4` OK · `apksigner verify --verbose` → **v2 ✓ v3 ✓**.

---

## 6 · සිංහල සාරාංශය (Sinhala summary)

ඔබගේ log එකේ `answered a unicast(QU) query` 16+ වතාවක් පෙනුණත් Self-test එක "no-answer" කීවේ **පිළිතුරු ලැබුණේ නැති නිසා නොවේ** — ඒවා `ISO-8859-1` ලෙස decode කර `"සිංහල"` (UTF-8 bytes 15ක්) සොයන නිසා කිසිදා ගැලපෙන්නේ නැති නිසාය. v2.3.1 එකේ පිළිතුරු සංයුතියෙන්ම (QR bit + records + ASCII service type) සත්‍යාපනය කරයි. 4-packet storm එක එන්නේ ඒ අසාර්ථක match එකෙන් full-speed re-send වන නිසා ය — probes දැන් සීමා සහිතය. `network up — re-announcing` loop එක start() එක තුළ stop() කලින් run වී callback නැවත register වීමෙන් — දැන් idempotent කර, `NetPolicy` එකෙන් repeat events නොසලකයි. දකුණු පැත්තෙන් අකුරු කැපීම HyperOS scroll bar overlay එක නිසා ය — inset scrollbars එක්ක සහ MATCH_PARENT params එක්ක නිවැරදි කළා. Self-test ප්‍රතිඵලය දැන් ස්ථර අනුව පෙන්වයි: `[L1 daemon ✓ · L2 responder ✓ · L3 NSD ✗]`. සියල්ල lab 152/152 PASS එකෙන් ඔප්පු කර ඇත; cast මාර්ගය අඛණ්ඩව ක්‍රියා කළේය (AnantPlay → mdnsd daemon).

---

## 7 · Files delivered

| File | What it is |
|---|---|
| (zip) | The complete v2.3.1 upgrade — all sources, `.github/workflows/` (build + release), `.gitignore`, keystore scripts, obtainium config, updated README/SETUP-SI, and this report — extracted over the repo and **deleted from git + disk per the zero-zip mandate (0 `.zip` files remain tracked)** |
| `AnantPlay-Sinhala-Player-v2.3.1.apk` | Freshly compiled, zipaligned, apksigner-verified (v2+v3). Debug-signed locally — your permanent keystore (via `scripts/create-release-keystore.*` + the 4 GitHub Secrets) signs all GitHub Releases, keeping Obtainium updates in place forever |
| `FORENSIC_FIX_REPORT_v2.3.1.md` | This report |

**Install note:** v2.3.1 (20301) installs directly over v2.3.0 (20300) — same package, higher versionCode. If your v2.3.0 was the debug-signed ZIP build, this build shares that debug key; a future GitHub-Release build (your permanent key) will need one final uninstall-reinstall before the permanent lineage takes over.
