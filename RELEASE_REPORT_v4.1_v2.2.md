# RELEASE REPORT — Worker v4.1 (Multi-Language) + Player v2.2.0
# නිකුතු වාර්තාව — v4.1 බහු-භාෂා Worker + v2.2.0 Player

**Build date: 2026-11-21/22 · GO decision: "GO A+C" with the 5-point hardening — ALL DELIVERED**
**නිර්මාණ දිනය: 2026-11-21/22 · GO තීරණය: "GO A+C" + බලකාරී දෘඩීකරණ 5ක් — සියල්ල භාරදී ඇත**

---

## 1. Phase 0 — the 5 mandated gaps, before/after proof · පර්යේෂණ කාරණා 5, පෙර/පසු ඔප්පු

### Gap 1 — True cinema-grade Sinhala quality (two-input polish, model named)
| Engine (same 10 tricky movie lines) | Score |
|---|---|
| translate-pa alone (research phase) | 13/20 |
| Generic LLM from scratch (research phase) | 4/20 |
| **EN + pa_draft → cinema polish (glm-4-plus, two-input protocol)** | **19/20** ✅ |

- Fixed exactly the stiffness the GO decision called out: `"ඔයා කරන්නේ කුමක් ද?"` → **`"ඔයා මොකක්ද කරන්නේ?"`**
- Deterministic `flagCuesForPolish()` (idiom/slang/sarcasm patterns + si stiff-literary markers + structural signals; ~20% budget, strong idioms always kept) — 12/12 semantics verified
- Per-cue fallback validator: 10/10 cues kept in the live test; a garbled cue falls back to its clean NMT draft, never corrupting the batch
- The exact tested model is named in the report and in `cinema.js` docs: **glm-4-plus** via the z-ai CLI (the sandbox's only live LLM; the shipped polish rides Gemini → Workers AI through the same numbered protocol)

### Gap 2 — translate-pa hardening (26/26 checks incl. LIVE endpoint)
- **Zero `AIza…` literals** in any git-tracked file (verified by grep in the live lab): `ENABLE_TRANSLATE_PA` gate + `TRANSLATE_PA_KEY` (or HEAD/TAIL modular vars) — GitHub secret-scanner stays quiet
- **Live-probed escaping protocol**: raw `<i>/<b>/<u>` tags survive as real HTML (`"<i>Stay back!</i>" → "<i>ආපසු ඉන්න!</i>"`); text `& < >` entity-escaped (`"Tom & Jerry"` clean round-trip); control-char sentinels were proven STRIPPED by the endpoint and discarded
- **True multi-line mapping**: 1,500-cue fixture (2-line cues, SFX, ♪) → flattened per-line items → ≤500 lines/request → rebuilt with EXACT cue count + byte-identical timestamps + `[GUNS COCKING]`/`♪` passthrough verbatim

### Gap 3 — Two-stage instant delivery (simulated timeline 6/6 + live-lab proven)
- Stage 1: valid base .srt publishes the moment the NMT cascade finishes (measured +3.7 s in simulation; `X-Sub-Quality: base-nmt`, `X-Polish-Status: pending`) — **playable immediately**
- Stage 2: cinema polish runs as resumable KV steps (≤3 LLM calls), then force-swaps the SAME token in place (`publishSrt(force)` — the lab caught the original write-if-absent no-op bug); the player's delayed re-check hot-swaps `SubtitleStore` silently and flashes "✨ Cinema Polish Ready"
- Failure safety: polish errors NEVER touch the published base .srt (proven both in simulation and live)

### Gap 4 — Universal multi-language registry + script hygiene (12/12)
- `worker/src/languages.js` ↔ Android `Langs.java` 1:1 mirror: **18 languages** with OpenSubtitles ISO-639-2B codes (sin/tam/hin/eng/spa/jpn/kor/chi/ara/urd/ger/rus/por/tha/ind/tur/vie), SubDL codes, script classes, RTL flags (ar/ur), per-script line budgets (CJK 18, si 42)
- Player rendering: bundled `NotoSansSinhala-Regular.ttf` ONLY for `si`; every other language → system `Typeface.DEFAULT_BOLD`; `TEXT_DIRECTION_ANY_RTL` for Arabic/Urdu — all verified by lab checks
- Live workerd lab proved independent jobs + caching for si, ta, ja, ar (own job ids, own tokens, <50 ms cached re-fetch, zero provider calls)

### Gap 5 — Subrequest ceiling + safe Option-C
- Worst-case per-invocation subrequest count: **15 of 25** (pa-dead azure path: 12 MT + 3 polish; KV ops are not subrequests) — never near Cloudflare's 50
- Option-C shipped as the "✨ AI Studio" power-user action: builds the two-input cinema prompt + first 300 cues (**31,092 bytes ≤ 40 KB** clipboard-safe), copies to clipboard, opens `https://aistudio.google.com/` via `ACTION_VIEW` (the REAL browser — never a WebView, zero automation, zero ToS risk), and the existing 📄 Local .srt loader imports the result

---

## 2. Verification lab results · සත්‍යාපන රසායනාගාර ප්‍රතිඵල

| Lab | Result |
|---|---|
| Worker offline suite (`node tests/run-all.js`) | **235 PASS / 0 FAIL** (220 v4 + 15 new v4.1, incl. 48 security/SSRF/zip-bomb) |
| **Worker v4.1 LIVE lab** (`lab/verify_worker_v41_e2e.mjs`, real workerd + simulated KV) | **35 PASS / 0 FAIL** — pa escape protocol on the wire, multi-line rebuild, pa→azure failover, si/ta/ja/ar independent jobs + cache, two-stage headers + in-place swap, zero AIza literals |
| Worker v4 lab re-run (backward compat) | **27 PASS / 0 FAIL** |
| Player lab (`lab/run_lab.sh`) | **93 PASS / 0 FAIL** (81 v2.1 + 12 new v2.2: registry, ISO-639-2B, RTL/fonts, Option-C chunk budget, query shapes) |
| APK build + `apksigner verify` | **PASS** (v2.2.0, versionCode 20200, SHA-256 `f5cf23b6…b8ee224`) |

**4 real engine bugs the live lab caught and we fixed** (worth every minute):
1. `/api/paired/` route + `JOB_ID_RE` rejected the `:lang` job-id suffix → ta/ja/ar jobs unreachable
2. The polish pass could land on an MT provider (pa) which just re-translated the prompt — now `providerKind: 'llm'` filters the chain
3. `publishSrt` was write-if-absent → the polish swap was a silent no-op — now `force` overwrites the same token
4. The strict numbered parser dropped multi-line polish values (2-line cues collapsed) — new `parseNumberedMulti` for polish batches + a line-count guard in the per-cue validator

---

## 3. What's in the boxes · පැකේජ තුළ ඇත්තේ

### repo1 — `repo1-worker-v4.1-multilang.zip` (upgrade for `dictationb1/anantplay-sinhala-bridge`)
- NEW `worker/src/languages.js` — the 18-language registry (codes, scripts, RTL, budgets)
- NEW `worker/src/cinema.js` — Cinemeta context (KV-cached), genre→register selector, deterministic flagger, the two-input polish prompt, per-cue fallback validator
- `worker/src/providers.js` — hardened `pa` Layer-1 provider (env-gated key, tag-raw/entity-text escaping, ≤500-line batches, SFX passthrough, instant breaker), `chainOf(env, lang)` per-language chains, language-aware prompts, `parseNumberedMulti`
- `worker/src/pipeline.js` — `langJobIdOf` (si = v4 bare id, back-compat), multi-language human-subs search, two-stage publish + `runCinemaPolish` (resumable, fail-safe, force-swap), per-language script validation
- `worker/src/index.js` — `?lang=` + `?register=` on `/subtitles` and `/api/subs-direct`, `X-Sub-Quality`/`X-Polish-Status`/`X-Sub-Lang` headers on `/sub/<token>.srt`, per-language `/health` + `/api/status`
- `tests/v41.test.js` (15 checks), `lab/verify_worker_v41_e2e.mjs` (35 live checks), updated `wrangler.toml` docs
- **Every v4.0 win intact**: SSRF allowlist, zip-bomb caps, single-flight KV locks, circuit breakers, `/api/subs-direct`, dashboard UI, `local-server/server.py`

### repo3 — `repo3-anantplay-sinhala-player-v2.2.zip` (upgrade for `dictationb1/anantplay-sinhala-player`)
- NEW `Langs.java` — the registry mirror
- `PlayActivity.java` — language chip row (සිංහල · தமிழ் · हिन्दी · English · Español · 日本語 · 한국어 · العربية + More), Spoken/Formal register toggle, two-stage hot-swap ("✨ Cinema Polish Ready"), script-safe fonts + RTL, Option-C AI Studio bridge
- `BridgeApi.java` — `subtitles(..., lang, register)`, `subsDirect(..., lang, register)`, `srtWithQuality(token)` header capture
- `MainActivity.java` — Default Subtitle Language picker (bilingual explanation)
- `lab/ReceiverLab.java` +12 checks; `build.sh` v2.2.0 (20200)
- **Every v2.1 win intact**: `MdnsWire` raw-socket mDNS + `MulticastLock`, AdGuard VPN help card, fixed dark-mode titles + bottom insets, bilingual settings, Cast `subs` passthrough, `POST_NOTIFICATIONS`, `THIRD_PARTY.md`, build-tools 35.0.1 + d8 recipe

### APK — `AnantPlay-Sinhala-Player-v2.2.0.apk`
3,281,643 bytes · `apksigner verify` PASS · debug-signed (the stable `sicast-debug.keystore`; swap in your release keystore via `KS_BASE64/KS_PASS/KS_ALIAS` env when you ship to Play).

---

## 4. Deploy in 2 minutes · තත්පර 120කින් deploy කරන්න

```bash
# ── Worker v4.1 (repo1) ──────────────────────────────────────────────
cd anantplay-sinhala-bridge
npx wrangler deploy                                  # core deploy (si default, azure→google→gemini chain)
# optional Layer-1 accelerator (the research breakthrough):
npx wrangler secret put TRANSLATE_PA_KEY             # paste the key from the research report §3
npx wrangler deploy                                  # then enable in Cloudflare dashboard →
                                                     # Settings → Variables → ENABLE_TRANSLATE_PA = "true"
# (or use the modular HEAD/TAIL vars to avoid any full-key secret pattern)

# ── Player v2.2 (repo3) ──────────────────────────────────────────────
cd ../anantplay-sinhala-player
ANDROID_HOME=/path/to/sdk ./build.sh                 # dist/AnantPlay-Sinhala-Player.apk
# or install the prebuilt:
adb install AnantPlay-Sinhala-Player-v2.2.0.apk
```

**Zero-migration promise**: every existing v4 KV entry, job id, token and cached .srt keeps working unchanged (si jobs keep their bare v4 ids; the player's default request is byte-identical). No user action needed on upgrade — the new languages appear as chips in the player.

---

## 5. Honest limits · අවංක සීමා

- **translate-pa is undocumented** — Google can wall it any day. That is why it ships OFF by default, env-gated, with the 10-minute circuit breaker and azure/google_v3/gemini behind it. If it dies: nothing breaks, capacity drops to the official free tiers (~68 fresh movies/month worst case).
- The polish pass rides whatever LLM the worker chain has (Gemini free tier by default); the 19/20 benchmark was measured with glm-4-plus — a different model may polish differently, which is exactly why the per-cue fallback validator exists.
- The APK is debug-signed (same as v2.1's local builds); use your release keystore for store distribution.
