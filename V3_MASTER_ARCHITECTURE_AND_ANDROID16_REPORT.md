# AnantPlay සිංහල Player v3.0.0 — Master Architecture & Android 16 Report
### V3_MASTER_ARCHITECTURE_AND_ANDROID16_REPORT.md
**Date: 2026-11-22 · Repo: `dictationb1/anantplay-sinhala-player` · versionCode 30000 · targetSdk 36 (Android 16) · Lab: 288/288 PASS**

---

## 0. සාරාංශය · Executive Summary

**English.** v3.0.0 is a complete overhaul built on the forensic evidence of the
2026-11-22 15:15 real-device log (Xiaomi, Android 16 / SDK 36, v2.4.0 running
alongside 100% official unmodified AnantPlay). That log **disproved** two earlier
theories and proved three new facts, all now fixed at the root:

| The v2.4.0 mystery | The proven root cause (with AOSP citations) | The v3.0.0 fix |
| --- | --- | --- |
| `NsdManager.registerService()` never called back (0 → 340 s, `announced: false`) | Android 14+ routes every registration through `MdnsAdvertiser` → `mPendingRegistrations`, which has **no timeout**: when `MdnsSocketProvider` never produces a socket for wlan0, **neither success nor failure is ever delivered** | A silent-hang **watchdog** (12 s fast / 2 min slow re-drive with fresh transaction ids) + two independent fallback announcers that never depend on the daemon |
| AnantPlay's 40+ browse queries got answers on the wire — the Cast icon still never appeared | (a) every browse query carries the **QU bit**, and v2.4.0 replied **unicast-only** to the phone's own `:5353` — a unicast packet to a local address travels the kernel **`local` route (interface `lo`)**, while the platform's `MdnsInterfaceSocket` is `SO_BINDTODEVICE(wlan0)` and can never receive it; (b) the rare multicast replies egressed the **default multicast interface** (never pinned), so kernel loopback never reached the system socket; (c) TTL was Java's default `1`, not RFC 6762 §11's 255; (d) no AAAA record for the `fe80::` queriers | Replies are **always multicast to `224.0.0.251:5353` AND `ff02::fb:5353`** (kernel fan-out to every joined socket), egress **pinned** via `setNetworkInterface`, **TTL 255**, **AAAA** added, QU unicast kept only as a courtesy |
| Only one cast path existed (mDNS) | Official AnantPlay **also** discovers DLNA renderers via SSDP (`Cast.discover → Dlna.search`), and `Cast.any()` counts them exactly like TVs — a path that shares **zero** code with NsdManager | A pure-Java **UPnP/DLNA MediaRenderer** (SSDP responder on UDP 1900 + `/dlna/desc.xml` + AVTransport/RenderingControl SOAP on the existing port 8765) — cast works even when the platform mDNS stack is wedged |

On top of the cast fix, v3.0.0 adds the two other pillars the user asked for:
a **built-in WebView Cinema Browser with automatic stream sniffing and instant
Sinhala subtitles** (works on Wi-Fi *and* mobile data, on any router, with zero
cast dependency) and a **fully automatic merge-to-release CI + app-open
self-updater** with visible progress and guaranteed storage cleanup.

**සිංහල.** v3.0.0 යනු ඔබේ සැබෑ Xiaomi Android 16 දුරකථනයෙන් ලැබුණු 2026-11-22 15:15
ලොගයේ තහවුරු කළ සාක්ෂි මත ගොඩනැගුණු සම්පූර්ණ ප්‍රතිසංස්කරණයකි. පැරණි උපකල්පන දෙකක්
බිඳ වැටුණි: `MdnsResponder` එක NsdManager එකට බාධා කළේ නැත (එය ක්‍රියාත්මක වීමට
පෙරත් ලොගයේ `announced: false` දක්වා තිබුණි), රවුටරය multicast බ්ලොක් කළේත් නැත
(`192.168.1.148` / `192.168.1.70` යන අනෙක් උපාංගවල ප්‍රශ්න LAN අතරේ මගින්
මෙම දුරකථනයට ලැබුණි). ඇත්ත හේතුව: Android 14+ හි NsdService එකේ
`MdnsAdvertiser` එකට socket එකක් නොලැබුණහොත් කිසිදු callback එකක් කිසිදා නොපැමිණේ
(වෙන් කිරීමක් නැත), එසේම අපේ පිළිතුරු unicast-only ලෙස `lo` route එකේ ගිය
නිසා system socket එකට (wlan0-bound) කිසිදා නොලැබුණි. v3.0.0 එකේදී: පිළිතුරු සැමවිටම
multicast (v4+v6), TTL 255, pinned egress, AAAA record — ඒ දෙකම එකට **DLNA MediaRenderer
එකක්** (SSDP) එකතු කර ඇත: AnantPlay එකේ Cast බොත්තම NsdManager එක නැතුවම
පවා පාලනය වේ. අමතරව: built-in WebView browser එකක් (stream sniff කර සිංහල
උපසිරැසි සමඟ ක්ෂණිකව විවෘත වේ — Wi-Fi නැතිවත්, ඕනෑම ජාලයක), නව 4-tab UI එකක්,
සහ app එක ඇරෙන විගසම ස්වයංක්‍රීය update පද්ධතියක්.

---

## 1. Mystery 1 — `announced: false` for 340 s: the AOSP silent-hang, proven

**English.** The log: `0.117` armed the registration, `2.621` was the v2.4.0
settle-timeout, and `announced` stayed false for the entire 340 s run with **zero**
`onServiceRegistered`/`onRegistrationFailed` callbacks. Research into the real
AOSP sources (`platform/packages/modules/Connectivity`, `android16-release`
@ `627eb3c6`) proves the exact silent paths:

* On Android 14+, **every** registration goes through the Java backend
  (`NsdService.java:976–978`: `clientInfo.mUseJavaBackend ||
  mDeps.isMdnsAdvertiserEnabled(mContext) || useAdvertiserForType(...)`).
* `NsdService.registerService` validates, calls
  `maybeStartMonitoringSockets()` (line 1049) and hands the service to
  `MdnsAdvertiser.addOrUpdateService` (line 1058–1059) — which stores it in
  `InterfaceAdvertiserRequest.mPendingRegistrations` (MdnsAdvertiser.java:520–531)
  **until `MdnsSocketProvider` produces a socket**.
* `MdnsSocketProvider` can silently never produce one: `createSocket` bails
  without any log when `isMdnsCapableInterface` returns false
  (MdnsSocketProvider.java:529–531); when the Wi-Fi transports are missing it
  falls back to `iface.supportsMulticast()` — and the code's own comment admits
  *"some Wifi interfaces may not have the MULTICAST flag even though they can do
  mDNS"* (lines 561–588); a null `LinkProperties` is only a DBG log with no retry
  (644–650). There is no `onRegisterServiceFailed` for any of these —
  `MdnsAdvertiser` has **no timeout**.
* A second silent path: a socket destroyed mid-probe orphans the pending
  registration (`onInterfaceDestroyed` → `destroyAdvertiser`,
  MdnsAdvertiser.java:370–377); AOSP itself `wtf`-logs
  *"unexpected onAdvertiserDestroyed() when there are pending registrations"*
  (lines 401–408).
* The same-name-different-type double registration is **not** the cause:
  `getConflictingRegistrationDueToService` requires both name **and** type to
  match (MdnsAdvertiser.java:462–480) — `_anantplay._tcp` + `_fmhyplay._tcp`
  with the same instance name is legal and conflict-free (verified in the lab).

**The v3.0.0 fix** (`NsdAnnouncer.java`):
1. **Registration watchdog** — if no real callback arrives within 12 s, the
   registrations are torn down and re-driven with fresh transaction ids (the
   app-side workaround for a stuck `requestSocket`); after 5 unanswered cycles
   it slows to a 2-minute cadence while the fallback announcers keep cast alive.
2. The NsdManager registration itself stays (both types, same names as v2.4.0 —
   TV-compatible), with the v2.4.0 anti-sabotage rules intact (distinct
   responder instance name; ANY/§8.1 probes never answered).
3. **Two announcers that never touch NsdManager** (below) — so the Cast chip no
   longer depends on the daemon at all.

**සිංහල.** ලොගයේ `announced: false` එක පැයකට ආසන්න කලක් එලෙසම පැවතුණේ Android 16
හි platform mDNS backend එකේ (MdnsAdvertiser) **timeout එකක් නොමැති නිසා** — Wi-Fi
interface එකට socket එකක් නොලැබුණහොත් callback කිසිවක් කිසිදා නොපැමිණේ. v3.0.0
එකේ watchdog එක 12 තත්පරයක් ඇතුළත callback එකක් නොමැති නම් registration එක
නැවත නැවත අලුත් transaction id සමඟ යවයි; එසේම cast එක දැන් NsdManager එක මත
රඳා නොපවතී (පහත බලන්න).

---

## 2. Mystery 2 — the 40+ ignored replies: the kernel delivery rules, proven

**English.** The log shows our responder answered every AnantPlay query
(`4.242`–`339.469`) yet AnantPlay's own discovery — and our self-test's
`L3 NSD: false` — saw none of them. Four delivery facts from the AOSP
`service-t/…/mdns/` sources:

1. **The QU-unicast black hole (FACT A).** Android's browse queries carry the
   QU bit; v2.4.0 replied unicast-only to `192.168.1.39:5353`. A unicast packet
   to the phone's **own** address takes the kernel `local` route whose egress
   device is `lo` — while the platform's discovery socket is created with
   `SO_BINDTODEVICE(wlan0)` (`MdnsInterfaceSocket.java:74–80`: *"Bind socket to
   the interface for receiving from that interface only"*). A packet that
   arrives via `lo` can never be received by the wlan0-bound socket (and
   `SO_REUSEADDR` unicast delivery is one-winner anyway). All 40+ unicast
   replies were invisible to the exact client that asked.
2. **The unpinned multicast egress (FACT B).** The two multicast replies
   (`5.245/5.246`) were lost because v2.4.0 never called `setNetworkInterface`
   on the responder: the kernel loops multicast back **on the egress
   interface**, which on that Xiaomi was not the joined wlan0 — so the system
   socket (joined on wlan0) never saw a copy. Proof by contrast inside the same
   log: the self-test's `probeLocal` — which *does* pin its interface — worked
   (`answered=true sent=1 recv=1`).
3. **TTL 1 → 255 (FACT C).** Java's default multicast TTL is 1; RFC 6762 §11
   mandates 255 and AOSP itself sends 255 (`MdnsInterfaceSocket.java:70–71`).
   AOSP does not check the TTL on receive, but mDNS-snooping switches may.
4. **No AAAA (FACT D).** The `fe80::f84a:81ff:feac:e2f8%wlan0` queries wanted a
   v6 answer; `MdnsResponse.isComplete()` (MdnsResponse.java:336–340) accepts
   SRV + TXT + (A **or** AAAA), so an AAAA completes the v6 leg too.

Also fixed here: v2.4.0's PTR rdata carried only the instance label instead of
the RFC 6763 full service name (`<instance>.<type>.local`) — which AOSP's
decoder Loop-2 (`findResponseWithPointer`) needs for the SRV/TXT name match.
The lab now proves the full AOSP-decoder compatibility end-to-end (the
`AospDecoder` mirror + the new v3 wire checks).

**The v3.0.0 MdnsResponder** (`MdnsResponder.java`):
* replies are **always multicast to BOTH `224.0.0.251:5353` and `ff02::fb:5353`**
  — multicast fan-out delivers a copy to *every* socket joined on the interface
  (ours, the platform's, any other app's) with no one-winner selection — and
  the QU unicast reply is additionally sent as a courtesy for real legacy
  queriers on other devices;
* `setTimeToLive(255)` + `setNetworkInterface(joinedIface)` — the exact two
  settings AOSP's own `MdnsInterfaceSocket` applies;
* the AAAA record for the interface's IPv6 (link-local first), same
  `<hostName>.local` owner as the SRV target;
* the PTR answer now carries the RFC-6763 full service name; dual-type browses
  get both PTRs in one packet; A/AAAA follow-ups are answered with host records
  only;
* all v2.4.0 safety rules kept: never answer §8.1 ANY probes, instance name
  distinct from the daemon's, cache-flush bits per RFC 6762 §10.2.

**සිංහල.** ප්‍රධාන හේතුව: AnantPlay එකේ ප්‍රශ්නවලට QU bit එකක් තිබූ නිසා v2.4.0
unicast-only පිළිතුරු යැව්වේය — නමුත් දුරකථනයේම ලිපිනයට යන unicast පැකටය `lo` (loopback)
route එකෙන් යන අතර, Android එකේ mDNS socket එක wlan0 මතම bind වී ඇත
(`SO_BINDTODEVICE`) — එම නිසා එය කිසිදා නොලැබේ. v3.0.0: පිළිතුරු සැමවිටම multicast
(v4+v6 දෙකටම — සියලුම joined socket වලට පිටපතක් ලැබේ), TTL 255, interface එක pin
කර ඇත, සහ IPv6 AAAA record එකක් එකතු කර ඇත.

---

## 3. Mystery 3 — the second, NsdManager-independent cast target: DLNA

**English.** Field research into official AnantPlay's own source (read-only;
never a fork) proved the decisive fact: `Cast.discover()` also fires
`Dlna.search()` (`Cast.java:293` — *"DLNA renderers answer an SSDP search, not
mDNS"*), and `Cast.any()` (`Cast.java:290`) counts the `renderers` map exactly
like `tvs`/`chromecasts` — so **a DLNA renderer alone lights the Cast chip**,
with zero involvement of NsdManager/MdnsAdvertiser. The protocol it speaks
(`Dlna.java`), mirrored line-for-line in v3.0.0's `DlnaEndpoint.java` +
`DlnaRenderer.java`:

* **SSDP**: `M-SEARCH * HTTP/1.1` multicast to `239.255.255.250:1900` with
  `ST: urn:schemas-upnp-org:device:MediaRenderer:1`, `MX: 2`, sent 3× (0/1/2 s)
  from a plain unicast socket; **only the `LOCATION` header** of each 200-OK
  reply is read, deduped by LOCATION. Our SSDP responder binds UDP 1900
  (`SO_REUSEADDR`, group joined on the Wi-Fi iface, TTL 4 per UPnP 1.0, egress
  pinned), answers every matching ST within 0–1200 ms **unicast to the
  searcher's ephemeral port** (no 5353-style port stealing is even possible),
  plus periodic `ssdp:alive` bursts and `ssdp:byebye` on stop.
* **Device description**: `GET LOCATION` → `friendlyName` + `<service>` blocks
  (`serviceType` + `controlURL`); **AVTransport required, RenderingControl
  optional**. Our `/dlna/desc.xml` advertises both, with absolute control URLs.
* **SOAP playback** (`POST /dlna/avt`, `SOAPAction: "<urn>#<Action>"`):
  `SetAVTransportURI` (CurrentURI = AnantPlay's own phone-side relay URL +
  DIDL-Lite with `dc:title`) → the SAME `/play` pipeline the mDNS cast uses →
  `Play`, `Pause`, `Stop`, `Seek` (`REL_TIME` `H:MM:SS` — the exact shape
  AnantPlay's `ms()` regex parses), `GetTransportInfo`
  (`CurrentTransportState`), `GetPositionInfo` (`TrackDuration`/`RelTime`),
  `GetMediaInfo`; RenderingControl `GetVolume`/`SetVolume` (Master channel,
  0–100, bridged to the real media volume). HTTP ≥ 400 = a SOAP fault with
  `errorDescription` — AnantPlay shows "The TV refused this video".
* **Delivery model — why this cannot fail like mDNS did**: the M-SEARCH arrives
  as *multicast* (our group membership receives it), and the reply goes
  *unicast to the searcher's own ephemeral port* — a unique port, so no other
  socket can steal it and no interface binding can hide it.
* **Security**: the `/dlna/*` HTTP surface follows the same
  **local-device-only** policy as `/play`/`/do` (AnantPlay's DLNA control
  always originates from the same phone via its relay); a stranger on the Wi-Fi
  is refused.
* The renderer's UDN is stable per install (persisted), and the cast list shows
  **"සිංහල Player - DLNA"** alongside the mDNS entry — two independent paths
  to the same Sinhala player.

**සිංහල.** නිල AnantPlay එක DLNA renderers ද SSDP search එකකින් සොයන අතර
`Cast.any()` එකට ඒවාද TV ලෙසම ගණන් වේ — එනම් NsdManager එක hang වුණත් Cast බොත්තම
පාලනය වේ. අපගේ pure-Java MediaRenderer එක AnantPlay එකේම protocol එක (SSDP 1900 +
  desc.xml + AVTransport/RenderingControl SOAP) පේළියෙන් පේළිය implement කර ඇති
අතර lab එක AnantPlay එකේම `describe()` regex code එකෙන් අපේ desc.xml parse වන
බව ඔප්පු කරයි.

---

## 4. Mystery 4 — the HyperOS side-check (honest answer)

**English.** `NsdService` itself enforces only `INTERNET` (NsdService.java:
2308–2310, `android16-release`); `NEARBY_WIFI_DEVICES` is never referenced for
discovery or registration, and a randomized (Private) Wi-Fi MAC only changes
the address — it cannot block multicast. So **no Android setting can be proven
to have caused the v2.4.0 hang** — the AOSP no-socket paths fully explain it.
For completeness, the v3.0.0 Diagnostics tab surfaces a 1-tap check whenever
mDNS stays down while the wire probe works, and the setup checklist covers the
four permissions that DO matter on HyperOS (Notifications, Battery
"No restrictions", MIUI Autostart, Install-unknown-apps). If a future HyperOS
build adds a "Local network"-style gate, the DLNA + WebView pillars keep the
app fully usable regardless.

**සිංහල.** NsdService එකට අවශ්‍ය එකම permission එක `INTERNET` — Android 16 හි
HyperOS setting එකකින් NsdManager hang වූ බව ඔප්පු කළ නොහැක (AOSP හේතුවම සම්පූර්ණ
පැහැදිලියි). වුවත් Diagnostics tab එකේ 1-tap පරීක්ෂා සහ setup checklist එකෙන්
HyperOS හි වැදගත්ම අවසර 4 නිවැරදි කරගත හැක.

---

## 5. The Built-in WebView Cinema Browser + automatic Sinhala subtitles

**English.** Tab 2 (`WebBrowserView.java` + the pure-Java `StreamSniffer.java`)
is the zero-cast path: browse a streaming site inside the app and the moment a
real video stream is detected, the floating pill **"🎬 සිංහල උපසිරැසි සමඟ බලන්න
(HLS)"** appears over the video — one tap opens our own Media3 player with the
SAME url + `Referer`/`Origin`/`Cookie`/`User-Agent` the site served, and the
Worker v4.1 two-stage pipeline fetches Sinhala subtitles automatically
("✨ සිංහල උපසිරැසි ලබාගනිමින්…" → "✓ සිංහල උපසිරැසි සූදානම්", with the ✨ Cinema
Polish hot-swap). This works **on Wi-Fi AND mobile data, on any router** —
no mDNS, no SSDP, no AnantPlay discovery.

* **Sniffing**: `shouldInterceptRequest` classifies every request with the
  pure-Java core — HLS (`.m3u8/.m3u`), DASH (`.mpd`), progressive
  MP4/MKV/WEBM/FLV — with only the PATH deciding (a query token never
  classifies), a Content-Type assist for extensionless URLs, and aggressive
  noise filtering: 40+ ad/tracker domains (doubleclick, exoclick, popads,
  taboola… — streaming CDNs are never blocked), HLS/DASH media segments
  (`.ts`, `.m4s`, `init.mp4`, `/seg*`), tracking pixels and small pre-rolls
  (< 3 MB when the length is known), and `blob:`/`data:` sources Media3 cannot
  replay. A `play`/`loadedmetadata` JS hook catches `<video>/<source>` picks
  the network layer cannot see; page titles run through `TitleCleaner` for the
  subtitle lookup. One report per URL; the pill aggregates the count.
* **The browser itself**: compact top bar (URL/search — non-URL input becomes a
  DuckDuckGo search — back/forward/reload, a ⋮ menu with Desktop-mode toggle,
  bookmarks, and "Paste link from clipboard"), a thin progress strip,
  popup/redirect blocking (`window.open` targets are never created; ad-host
  navigations refused), clean dark-mode styling (`color-scheme: dark`
  injection), and the **⚡Auto-Open** chip (default OFF, user-controlled) that
  opens the Cinema Player by itself the moment a new stream is sniffed.
* **Share/Open targets**: `ACTION_SEND` (text/plain) and `ACTION_VIEW`
  (http/https) intent filters now let AnantPlay's own Share ⋮ → "Copy link" —
  or any browser's share sheet — open the video **directly in the Sinhala
  player** with automatic subtitles.
* Zero new dependencies: `android.webkit` only — the APK stays 3.3 MB.

**සිංහල.** Tab 2 එක තුළ ඕනෑම streaming site එකක් බ්‍රව්ස් කරන්න — සැබෑ වීඩියෝ stream
එකක් හමු වූ විගස "🎬 සිංහල උපසිරැසි සමඟ බලන්න" pill එක video එක මතින් පෙනේ; එක
tap එකකින් අපේ Media3 player එක site එකේම URL + headers සමඟ විවෘත වී සිංහල
උපසිරැසි ස්වයංක්‍රීයව ලැබේ. මෙය **mobile data වලත්, ඕනෑම router එකකත්** ක්‍රියා
කරයි — Cast/mDNS කිසිම දෙයක් අවශ්‍ය නැත. AnantPlay එකෙන් හෝ browser එකකින් link
එකක් Share කළොත් කෙලින්ම සිංහල player එකේ විවෘත වේ.

---

## 6. The 4-tab modern UI + 1-tap diagnostics

**English.** `MainActivity` is rebuilt as a Material-Dark bottom-navigation app
(edge-to-edge safe on Android 16 via real window insets — no deprecated
system-ui flags, nothing clips under HyperOS status/gesture bars):

1. **🏠 Home** — the hero receiver card (big green/amber status pill
   "🟢 සිංහල Player සූදානම්", Start/Stop, a live 3-chip strip
   Receiver / Cast-DLNA / Subs-Engine), a clean 2×2 quick-action grid
   (▶ Open AnantPlay · 🌐 Watch in WebView · 🔗 Play Video URL with 1-tap
   Paste & Play · 🎬 Test Player), a collapsible 3-step visual cast guide, a
   setup-checklist card that stays **completely hidden** while all four
   required permissions are green, and the live update banner.
2. **🌐 WebView** — the cinema browser above.
3. **⚙️ Settings** — grouped cards: Subtitles (18 languages, Spoken/Formal
   register, auto-fetch), Player & WebView (⚡Auto-Open, ad-block always-on),
   Updates (⚡auto-update toggle, version badge, the Install-Unknown-Apps state
   with a 1-tap jump, manual check, the Obtainium note), and Advanced
   (Worker URL + reset) collapsed by default.
4. **🩺 Diagnostics** — five live health cards (Loopback · NSD daemon · mDNS
   responder · **DLNA renderer** · Worker bridge), the self-test, the
   monospace log viewer, and the buttons the field debugging was crying for:
   **📋 Copy Full Log** (instant clipboard + toast), **📤 Share Log**
   (`ACTION_SEND`), and **🗑 Clear Log**.

`PlayActivity` keeps **every** v2.2–v2.4.0 capability (18 languages,
Spoken/Formal, two-stage Cinema Polish with the "✨ Cinema Polish Ready"
hot-swap, Option-C AI Studio bridge, local .srt/.zip, sync nudges, DoH
ISP-block diagnostics + tap-to-retry, PiP, audio focus, the AnantPlay Remote
contract) and adds the immediate launch status pill so the subtitle pipeline is
never silent.

**සිංහල.** අලුත් 4-tab UI එක: Home (status + quick actions + guide), WebView
(cinema browser), Settings (subtitle/update සිටුවම්), Diagnostics (health cards +
**1-tap Copy Log**). පැරණි දිග scroll එක හා වචන පවුරු ඉවත් — PlayActivity එකේ
සියලුම පැරණි හැකියාවන් (භාෂා 18, Cinema Polish, local .srt, PiP ආදිය) සම්පූර්ණයි.

---

## 7. Merge-to-release CI + the app-open auto-updater + storage cleanup

**English.**

* **`.github/workflows/release-apk.yml` (v3)**: triggers on **every push to
  `main`** (i.e. every merged PR) plus tags and manual dispatch. It extracts
  `VERSION_NAME`/`VERSION_CODE` from `build.sh` itself, exits cleanly when the
  tag `v<version>` already exists (idempotent merges — no duplicate-release
  errors), runs the **full gating lab** (100% PASS required), builds and signs
  with the permanent keystore (`KS_BASE64`/`KS_PASS`/`KS_KEYPASS`/`KS_ALIAS` —
  byte-identical names across build.sh, both workflows and both keystore
  scripts, verified by the lab), verifies `aapt2 dump badging` +
  `zipalign -c 4` + `apksigner verify --verbose` (v2+v3), writes
  `SHA256SUMS.txt` in the exact GNU format `SelfUpdateCore.pickShaLine`
  parses, then creates+pushes the tag and publishes the Release with **exactly
  one .apk asset + SHA256SUMS.txt**. A push to main *without* `KS_BASE64`
  logs a friendly summary notice and exits green (tags/dispatch still refuse —
  an explicit release intent must be release-signed).
* **The self-updater (`SelfUpdater.java` + `SelfUpdateCore.java`)**: checks
  **the moment the app opens** (60-second debounce so quick re-opens never
  spam) and **every 4 hours** via a persisted `JobScheduler` job
  (`BIND_JOB_SERVICE` + `RECEIVE_BOOT_COMPLETED`). When a newer release
  exists and Auto-Update is ON (the default), the download starts by itself
  with a **live notification** ("අලුත් සංස්කරණය vX.Y.Z ඩවුන්ලෝඩ් වෙමින්… 64%") and
  the in-app Home banner progress bar. Verification is strict: **https-only**
  URL, a hard **30 MB cap** (checked against the declared size before the
  first byte and again during the stream), and an exact **SHA-256** match
  against `SHA256SUMS.txt` — a tampered download is refused and deleted.
  Install: `canRequestPackageInstalls()` is checked first (missing → a 1-tap
  notification straight to `ACTION_MANAGE_UNKNOWN_APP_SOURCES`), then the file
  is streamed into a `PackageInstaller.Session` committed with an **explicit
  mutable PendingIntent** (Android 14–16 compliant), with a **BAL-safe
  high-priority notification fallback** when the background confirm-launch is
  refused. **Storage cleanup is guaranteed**: the temporary `.apk` is deleted
  the moment it is written into the session, and every app start sweeps
  `cacheDir/updates/` — the lab proves 0 leftover bytes.
* Obtainium compatibility is unchanged: same permanent signing lineage, so
  Obtainium, the in-app updater and manual sideloads all update in place with
  zero uninstalls, forever.

**සිංහල.** දැන් main branch එකට merge වන සෑම PR එකක්ම ස්වයංක්‍රීයව GitHub Release
එකක් බවට පත් වේ (Lab 100% PASS gate එකෙන් පසු). App එක ඇරෙන විගසම (තත්පර 60ක
debounce) + පැය 4කට සැරයක් update check වේ; අලුත් version එකක් ඇති විගස
percentage සහිත notification එකකින් download වී, SHA-256 verify වී, Android එකේම
1-tap "Update" dialog එක පැමිණේ. Temporary file එක session එකට ලියූ විගසම මකා
දමන අතර සෑම app start එකකදීම `cacheDir/updates/` sweep වේ — storage එක සැමවිටම
හිස් රැඳේ.

---

## 8. The Android-16 honesty note (the one tap that stays)

**English.** Non-root Android can never install an APK silently:
`PackageInstaller` always shows the OS's own 1-tap "Update" confirmation. That
tap is the user's security boundary and v3.0.0 respects it — everything before
it (check, download, progress, verification, session handoff, cleanup) is fully
automatic. This is stated in-app (Settings → Updates) and in the release notes.

**සිංහල.** Android එකේ install එක සදාකාලිකවම 1-tap confirmation එකක් පෙන්වයි (ආරක්ෂණ
හේතුවෙන්) — එය මඟහැරිය නොහැක, නමුත් ඉන් පෙර සියල්ල (check → download → verify →
handoff → cleanup) 100% ස්වයංක්‍රීයයි.

---

## 9. Proof — the expanded verification lab

`bash lab/run_lab.sh` — **288 checks, ALL PASS (0 FAIL)**, deterministic across
three consecutive runs. New v3 sections (98 checks over the v2.4.0 baseline of
190):

* **Android 16 mDNS v3 wire**: TTL-255 policy, the AAAA record (owner ==
  SRV target, fe80:: rdata), A-only follow-ups, dual-type browses, the LIVE
  reply policy (a QU question provably receives its unicast reply AND the
  multicast v4-group copy — the kernel fan-out), plus source-level wiring
  parity for the Android-only classes (watchdog, pinned egress, v6 plumbing).
* **DLNA MediaRenderer E2E** over the real receiver HTTP server: the
  AnantPlay-shaped M-SEARCH → 200-OK (LOCATION, ST, USN, CACHE-CONTROL), the
  desc.xml parsed **with AnantPlay's own `describe()` regexes** (ported
  verbatim into the lab), the full SOAP command order (Stop → SetAVTransportURI
  → Play → GetTransportInfo → Pause → Seek → GetPositionInfo → GetMediaInfo →
  Stop) with the exact state transitions, the clock contract vs AnantPlay's
  `ms()` regex, RenderingControl volumes with clamping, fault wording, and the
  Cast-chip contract (a renderer entry lights `Cast.any()`).
* **WebView stream sniffer**: classification (path-only rule, Content-Type
  assist), the ad/tracker blocklist (streaming CDNs never blocked), segment /
  pixel / pre-roll filtering, blob:/data: refusal, chunklist-manifests-still-
  report, TitleCleaner integration, the bilingual pill label.
* **Self-updater v3 + storage cleanup**: release parsing with the declared
  size, live monotonic progress (0→100), streaming SHA-256 == in-memory SHA-256,
  https-only refusal, the over-cap pre-read gate, and the cleanup proof
  (freed-bytes counted; 0 leftover files after the sweep) + the version
  contract (30000 > 20400).
* **Pipeline parity**: the merge-to-release triggers (push main + tags +
  dispatch), tag-exists idempotence, the friendly no-keystore notice, the lab
  gate, the one-apk + SHA256SUMS release assets, GNU `sha256sum` format ==
  the parser, badging/zipalign/apksigner verification, `contents: write`, the
  build-tools;35.0.1 guard, `.gitignore`, build.sh defaults == the shipped
  version, PowerShell 5.1 compatibility of the keystore script, and the
  manifest's SEND/VIEW intent filters + pinned back-callback.

## 10. Proof — the real APK build

`./build.sh` with build-tools 35.0.1 + platform android-36 produces
`AnantPlay-Sinhala-Player-v3.0.0.apk`:

```
package: name='lk.techworld.anantplay.sicast' versionCode='30000' versionName='3.0.0'
         platformBuildVersionName='16' platformBuildVersionCode='36'
         compileSdkVersion='36' compileSdkVersionCodename='16'
targetSdkVersion:'36'
zipalign -c 4: OK
apksigner verify: Verifies — v2 scheme: true, v3 scheme: true, 1 signer
size: 3,334,891 bytes (3.3 MB)  — zero new dependencies
```

The APK delivered with this report is signed with the local build key (the
permanent `KS_BASE64` release keystore lives only in the repo's GitHub Secrets,
as designed); the CI builds from this exact tree are release-signed with it.

---

## 11. File map (what changed / what was added)

| File | Status | Purpose |
| --- | --- | --- |
| `src/…/MdnsResponder.java` | **rewritten (v3)** | TTL 255, pinned egress, always-multicast v4+v6, AAAA, RFC-6763 PTR rdata, dual-type answers, QU courtesy unicast |
| `src/…/NsdAnnouncer.java` | updated | the silent-hang watchdog (12 s / 2 min re-drive), `wifiLanIp6()`, v6 plumbing to the responder |
| `src/…/DlnaEndpoint.java` | **new** | pure-Java UPnP MediaRenderer core (SSDP strings, desc.xml/SCPD, SOAP state machine, clock contract) |
| `src/…/DlnaRenderer.java` | **new** | the SSDP UDP responder lifecycle (1900, alive/byebye, 0–1200 ms answer delays) |
| `src/…/ReceiverServer.java` | updated | the `/dlna/*` routes (local-device-only), SOAPAction capture, status/content-type responses |
| `src/…/ReceiverService.java` | updated | DLNA wiring (shared `/play` engine), `/info` fields, heal includes the DLNA pillar |
| `src/…/StreamSniffer.java` | **new** | pure-Java stream classification + ad/segment filters + title cleaning |
| `src/…/WebBrowserView.java` | **new** | the built-in cinema browser (sniffer pill, ⚡Auto-Open, ad/popup block, bookmarks, UA toggle) |
| `src/…/MainActivity.java` | **rewritten** | the 4-tab Material-Dark UI (Home / WebView / Settings / Diagnostics) |
| `src/…/PlayActivity.java` | updated | ACTION_SEND/ACTION_VIEW targets, sniffer headers, the immediate launch status pill |
| `src/…/SelfUpdater.java` + `SelfUpdateCore.java` | **rewritten (v3)** | app-open 60 s debounce + 4 h job, live progress, 30 MB cap, streaming SHA-256, BAL-safe install, guaranteed cleanup |
| `src/…/App.java` | updated | the percentage notification, the startup sweep |
| `AndroidManifest.xml` | updated | SEND/VIEW intent filters, `enableOnBackInvokedCallback="false"` pin |
| `build.sh` | updated | 3.0.0 / 30000, platform android-36, `--target-sdk-version 36` |
| `.github/workflows/release-apk.yml` | **rewritten** | the merge-to-release pipeline (push main + tags + dispatch, idempotent, gated) |
| `.github/workflows/build-apk.yml` | updated | platform 36, v3.0.0 identity |
| `lab/ReceiverLab.java` + `lab/run_lab.sh` | expanded | 190 → **288 checks** |
| `res/values/strings.xml` | cleaned | the v3 string set |

---

*Zero fork · zero accessibility · official AnantPlay (`me.champdev.weblock`) stays untouched. All AnantPlay source references are read-only research citations with file:line accuracy as of 2026-11-22.*
