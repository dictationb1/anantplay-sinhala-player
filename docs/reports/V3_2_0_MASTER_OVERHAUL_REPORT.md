# V3.2.0 MASTER OVERHAUL REPORT — AnantPlay සිංහල Player
## (සිංහල + English · 2026-11-22 · versionCode 30200 · strictly v3.x)

---

## 1. මුල් පිටපත | Executive Summary

**v3.1.2 (30102) → v3.2.0 (30200)** — 5 field-proven defects fixed at the source, zero new dependencies, APK stays **3.3 MB** (3,408,595 bytes), pure Java + media3 1.11.1 + Guava jars only.

| Phase | Deliverable | Status |
|---|---|---|
| 1 | Zero-Rate-Limit GitHub updater (250 ms loop killed) | ✅ Lab-proven |
| 1 | Stale-crash auto-clear (versionCode stamped) | ✅ Lab-proven |
| 2 | Edge-to-edge insets, Sinhala bold-Typeface fix, markup stripper, script-ratio validation | ✅ Lab-proven |
| 2 | FreeSubTranslator (zero-quota keyless) + URL .srt/.zip loader + junk-title search | ✅ Lab-proven |
| 3 | Mornye/Apple 5-tab shell, deep customization hub, custom-links manager, in-player settings sheet, AnantPlay-style Web player | ✅ Compiles vs android-36 |
| 4 | 🩺 10-point full-feature Troubleshoot hub (100% privacy-safe) | ✅ Lab-proven |
| 5–6 | Lab **599/599 PASS** + bytecode verifier OK + signed APK (v2+v3 signatures, zipalign OK, badging 30200/3.2.0) | ✅ Built & verified |

---

## 2. Smoking Gun #1 — 250 ms updater spin-loop → Zero-Rate-Limit updater

### Root cause (file:line)
- `SelfUpdater.java:281` (v3.1.2): `SelfUpdateCore.fetchRelease(API_LATEST)` returned null on GitHub's **60/hr api.github.com 403** → `Troubleshooter.record("UPDATER", "check: no release info (offline?)")`.
- The re-fire mechanism: `CheckJob.onStartJob` called **`schedulePeriodic()` from inside itself** — rescheduling a past-due persisted periodic job makes JobScheduler re-deliver it IMMEDIATELY → fail (~250 ms round trip: DNS+TLS+403+jobFinished+reschedule) → re-fire. 200+ calls / 148 s on the field phone.

### The fix (4 layers, all lab-proven)
1. **Tier-1 resolver — ZERO api.github.com on the happy path**: `SelfUpdateCore.resolveLatestTagByRedirect()` sends HEAD to `https://github.com/<repo>/releases/latest` with `setInstanceFollowRedirects(false)`, reads the **302 Location** header → tag `v3.2.1` → derives deterministic asset URLs:
   - `…/releases/download/v<ver>/AnantPlay-Sinhala-Player-v<ver>.apk`
   - `…/releases/download/v<ver>/SHA256SUMS.txt`
2. **Tier-2**: `docs/latest.json` on `raw.githubusercontent.com` (auto-published by the release workflow — the new "Publish docs/latest.json" step writes version+code+sha256 back to main after every release).
3. **Tier-3 (LAST resort)**: legacy api.github.com REST, only when both tiers fail.
4. **15-minute failure backoff** (`SelfUpdateCore.checkAllowed`, pure): a failed check persists `lastFailedCheck`; every automatic re-check inside 15 min is a hard no-op (manual bypasses). `lastCheck` is persisted **BEFORE** the network call. `CheckJob` NEVER calls `schedulePeriodic` — periodic re-arm lives in App.onCreate only.

**Lab proof**: a mock github.com serves the exact 302 → tier-1 resolves v3.2.1, the mock asserts **api.github.com was never touched**; the 200-calls-in-148-s field storm simulation collapses to **exactly 1 network attempt**.

## 3. Smoking Gun #3 — stale crash from v3.1.1 shown in v3.1.2

`CrashLog.format` now stamps **`versionCode:`** in every report header; `CrashLog.staleFor(report, runningCode, now)` (pure) → a report stamped with an older code is ALWAYS stale; `App.onCreate` calls `CrashLog.clearStaleFor(filesDir, AppInfo.VERSION_CODE, now)` — the v3.1.1 `#0x88000000` crash deleted BEFORE the Troubleshooter ever reads it. Legacy unstamped reports age out after 48 h.

## 4. Smoking Gun #2 — flawless Sinhala subtitles

1. **Status-bar overlap** (`"ArrowTV Player"` under `20:52`): the root's `OnApplyWindowInsetsListener` pads the HUD top bar by `statusBars()|displayCutout()` and the bottom control bar by `navigationBars()` — measured insets, never a collision.
2. **Garbled bold Sinhala (the Typeface bug)**: `applyLangStyle()` is now the ONE typeface setter, applying bold the Android-correct way — `Typeface.create(sinhalaFamily, bold ? BOLD : NORMAL)` — never the `setTypeface(tf, style)` second-arg form that silently dropped the family on language switches. Complex-script wrapping: `BREAK_STRATEGY_HIGH_QUALITY` + `HYPHENATION_FREQUENCY_NONE` + line spacing 1.2.
3. **Markup stripper** (`Cue.stripMarkup`): `{\an8}`/`{\pos(…)}` ASS blocks, `<i>`/`<font>` tags, `&amp;`/`&quot;`/`&#39;` entities and numeric entities are stripped at parse time for worker JSON, local .srt AND .zip files — together with **genuine** invisible garbage only: BOM (U+FEFF), ZWSP (U+200B), soft-hyphen (U+00AD) and word-joiner (U+2060). **Sinhala ZWJ (U+200D) and ZWNJ (U+200C) are PRESERVED**: they are orthographically mandatory for the rakaransaya (‍ර), yansaya (‍ය) and conjuncts — `ශ්‍රී`, `චිත්‍රපටිය`, `ක්‍රියා`, `ප්‍රශ්නය`, `ව්‍යාපෘතිය` — and stripping them would break every conjunct into hal-kirima glyphs. Locked by explicit regression checks in `lab/ReceiverLab.java` (all three ZWJ in the `ශ්‍රී ලංකාවේ චිත්‍රපටිය & ව්‍යාපෘතිය` fixture must survive, as must ZWNJ and the same invariants through `fromSrt()`/`fromPairedJson()`).
4. **Wrong-language fix**: `SubLangDetect` (pure) computes the Sinhala script ratio (U+0D80..U+0DFF letters / all letters). A cue list fetched for si that is NOT ≥30% Sinhala is NEVER labeled "සිංහල උපසිරැසි සූදානම්" — it routes to the free translator (`validateOrTranslate`).
5. **Zero-quota translation (Layer 3)** — `FreeSubTranslator`: keyless public endpoints (`translate.googleapis.com/translate_a/single?client=gtx` + `clients5.google.com/translate_a/t?client=dict-chrome-ex`) with ~40-cue/1900-char batching (timestamps + indices preserved), 600 ms pacing, 3 retries with failover, disk cache per source hash (a re-watched movie NEVER re-translates), and a never-throws contract (English shows instead of an error). Worker 404 "job not on this isolate" retries silently — never a raw JSON banner.
6. **Auto .zip/.gz/.srt**: paste a subtitle URL in the subs panel → download (HTTPS, ≤5 MB bomb-safe) → SrtLoader extract → script check → auto-translate non-Sinhala → attach.
7. **Junk titles**: `TitleCleaner.isJunkTitle` ("ArrowTV Player", "VidSrc", "Embed Player", "Watch Online", "Index of /", "Loading…") NEVER reach Cinemeta — the pill becomes **"🔍 චිත්‍රපටයේ නම හඳුනාගත නොහැක — තට්ටු කර සොයන්න"**, tapping it opens the 1-tap movie-title search (name + optional S/E → /api/resolve → instant subs).

## 5. Phase 3 — Mornye/Apple-grade UX

- **5 bottom tabs**: 🏠 Home · 🌐 Web (hideable) · 🎨 Customize · 🩺 Troubleshoot.
- **Look & Feel**: 4 themes (System/Dark Slate/AMOLED #000000/Light), Material You dynamic color, **8 curated accents + custom #RRGGBB hex** (explicit hex outranks wallpaper), card radius (Sharp 6dp/Rounded 16dp/Apple Pill 24dp — Mornye app_tokens), density (Spacious/Comfortable/Compact), UI language (සිංහල+EN/සිංහල/EN), quick-action visibility.
- **Subtitle Studio** (live preview): font family (Noto/System/Serif), size 14–40 sp, 6 colors, bg (pill/outline/box/shadow), opacity 0–95%, bottom 2–35%, line spacing 1.0–1.5, bold (family-safe), **engine priority (human→PA→free / human→free / free / worker)**, auto-translate toggle, auto-zip toggle.
- **Playback & Gestures**: default speed 0.5–2.0x, aspect (fit/fill/zoom), seek 5/10/15/30 s, double-tap seek, swipes, Auto-PiP, keep-screen-on, auto-resume, HUD 2/4/6/8 s, sync step 0.1/0.25/0.5 s.
- **Web Player & Custom Links**: full CRUD (add/rename/reorder/delete, ≤8, deduped) — the Web tab's ⚡ launcher renders the live list, so a link edit applies with zero app update. Desktop UA, Ad/Popup blocker toggle (request-level 0-byte refuse + navigation refuse), auto-sniff auto-open.
- **Cast & Network**: receiver name, port override (8765), DLNA/mDNS toggles, Worker URL + ping, DoH diag.
- **Backup & Restore**: strict-schema JSON export/import — every v3.2.0 key round-trips (lab-proven, hostile values normalized).
- **In-Player settings sheet** (the ⚙ button): speed chips, aspect cycle, LIVE subtitle style steppers, sync, language quick-switch — over the paused/playing movie, zero leaving.
- **AnantPlay-style Web player**: inline fullscreen (`onShowCustomView` on the decor with immersive-sticky flags — WebActivity.java:276's exact pattern), stream sniffer with clean titles (junk → search pill), custom links launcher.

## 6. Phase 4 — 🩺 the dedicated 10-point Troubleshoot hub

One hero button (`🩺 Run Full App Troubleshoot (All Features)`) runs `FeatureProbe.runAll`:
1. Loopback Receiver (port 8765) — GET /info
2. DLNA MediaRenderer E2E — /dlna/desc.xml DIDL check
3. mDNS Wire Responder — real UDP 5353 probe to 224.0.0.251
4. Zero-Rate-Limit Updater — the tier-1 redirect check (0 api.github.com)
5. Cloudflare Worker Bridge — /health + version + storage + KV verdict
6. Subtitle Resolve + Script Check — live /api/resolve + the Unicode validator self-test
7. Free Keyless Translator — translates one real phrase to Sinhala
8. Auto .zip extractor + zip-bomb guard — synthetic zip through the real SrtLoader
9. Sinhala font asset + shaping — stripMarkup on hostile markup
10. Customization + Backup round-trip + CrashLog status

Every outcome (PASS ✓ **and** FAIL ✗) lands live in the probe card, in the Troubleshooter timeline, and in the 1-tap Copy/Share report — **zero PII** (booleans, latencies, HTTP codes only; PrivacyScrubber at capture AND at report build; lab-proven with canaries).

## 7. Lab + APK proof

- **`bash lab/run_lab.sh` → `Lab result: ALL 599 PASSED` (0 FAIL)** + `verify_refs.py: OK — 187 distinct referenced types, all resolvable` + zero accessibility declarations.
- **APK**: `dist/AnantPlay-Sinhala-Player-v3.2.0.apk` — `aapt2 dump badging`: `versionCode='30200' versionName='3.2.0' targetSdk 36`; `zipalign -c 4` OK; `apksigner verify --verbose`: **v2 ✓ v3 ✓**; size **3,408,595 bytes**; SHA-256 `f26888d396204f854031a44eb77a83a0aca4d0867195533aaaca902c95951882`. Signed with the local debug keystore in this sandbox (the repo's CI signs with the permanent KS_BASE64 secrets for release).
- Pipeline: both workflows bumped to 3.2.0/30200; release-apk.yml gained the **docs/latest.json tier-2 publish step**; build.sh defaults 3.2.0/30200/prev 30102.

## 8. Deliverables

1. `repo3-anantplay-sinhala-player-v3.2.0.zip` — the complete Arena.ai-ready repo (includes `.github/workflows/`, `.gitignore`, this report, updated `README.md`/`SETUP-SI.md`).
2. `AnantPlay-Sinhala-Player-v3.2.0.apk` — the verified APK.
3. `V3_2_0_MASTER_OVERHAUL_REPORT.md` — this report (also inside the ZIP).

---

*Strictly v3.x — versionCode 30200. Zero fork of AnantPlay. Zero accessibility services. Zero new dependencies.*
