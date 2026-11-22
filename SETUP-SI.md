# AnantPlay සිංහල Player — Xiaomi HyperOS සැකසුම් මාර්ගෝපදේශය (v3.0.0)

> **අලුත්ම ක්‍රමය: Accessibility අවසරයක් අවශ්‍ය නැත.** AnantPlay හි **Cast** බොත්තමෙන් චිත්‍රපටිය
> **මෙම app එකටම** එවනවා — සිංහල උපසිරැසි සහිතව. AnantPlay නිල app එක තවමත් 100% නොවෙනස්ව තිබේ.
>
> **v3.0.0 අලුත් දේ:** **ටැබ් 4ක UI** (Home · WebView · Settings · Diagnostics + 1-tap Copy Log),
> **ආරක්ෂිත Cast මාර්ග දෙකක්** (mDNS + **`සිංහල Player - DLNA`** MediaRenderer),
> **ඇතුළතම ඇති Cinema Browser** (cast නැතුව, Wi-Fi හෝ Mobile Data — ස්වයංක්‍රීය සිංහල උපසිරැසි),
> සහ **App-open Auto-Updater** (ඇප් එක විවෘත කරන විටම update පරීක්ෂා කරයි) + **Merge-to-Release CI**.
>
> **v2.2 සිට තිබෙන දේ:** භාෂා **18ක්**, **Spoken/Formal** රටා මාරුව, **දෙවන අදියර Cinema Polish**
> (ස්වයංක්‍රීයව උසස් වේ), සහ අවශ්‍ය නම් පමණක් **✨ AI Studio** බොත්තම.

## 1 · App එක install කරන්න

`AnantPlay-Sinhala-Player-v3.0.0.apk` install කරලා **විවෘත කරන්න** (එය "AnantPlay සිංහල Player" ලෙස පෙනේ).
"Start receiver" බොත්තම ඔබන්න — ඉහළම දැනුම්දීම් තීරුවේ **"අ — Sinhala Player cast ready"**
දැනුම්දීම පැමිණේ (එය receiver එක ක්‍රියාත්මක බවට සලකුණයි).

## 1.5 · v3.0.0 — ටැබ් 4ක UI · Cast මාර්ග දෙකක් · Cinema Browser · Auto-Updater

### 1.5.1 · ටැබ් 4ක UI (Home · WebView · Settings · Diagnostics)

පහළ තීරුවේ ටැබ් **4ක්** ඇත — Android 16 (targetSdk 36) edge-to-edge insets වලට ගැලපෙන ලෙස
status bar / gesture bar යටින් කිසිවක් කැපී නොයයි:

| Tab | භාවිතය |
|---|---|
| 🏠 **Home · මුල** | receiver pill (Start/Stop), Receiver · Cast/DLNA · Subs chips, quick actions, cast guide, update banner |
| 🌐 **WebView · බ්‍රව්සර්** | ඇතුළතම ඇති Cinema Browser (§1.5.3) |
| ⚙️ **Settings · සැකසුම්** | Auto-Update toggle, updates check, cast/subtitle සැකසුම් |
| 🩺 **Diagnostics · දෝෂ නිර්ණය** | health rows (loopback · NsdManager · mDNS responder · DLNA · bridge), Self-test, log |

**1-tap Copy Log (වඩාත්ම වැදගත්):** Diagnostics ටැබ් එකේ **📋 Copy Log** ඔබන විට මුළු log එකම
clipboard එකට copy වේ (cookie/token/Authorization ස්වයංක්‍රීයව redact වේ). **📤 Share Log** එකෙන්
එය වෙනත් app එකකට යැවිය හැක. Bug එකක් වාර්තා කරන විට මෙය එක තට්ටුවකින් සම්පූර්ණයි.

**EN:** The bottom bar carries 4 tabs. Home (receiver status + quick actions), WebView (the
built-in cinema browser), Settings (auto-update, updates, subtitle options) and Diagnostics
(health rows, self-test, log). **📋 Copy Log** copies the entire log in one tap with
cookies/tokens redacted; **📤 Share Log** hands it to any app.

### 1.5.2 · Cast මාර්ග දෙකක් — mDNS **සහ** `සිංහල Player - DLNA`

AnantPlay හි cast list එක දැන් **එකම වේලාවේ මාර්ග දෙකකින්** පිරේ — එකක් අසාර්ථක වුවත්
Cast බොත්තම දැල්වේ:

1. **mDNS (NsdManager + අපේම RFC-6762 responder):** TTL 255, egress interface pin කර ඇත,
   පිළිතුර සැමවිටම multicast (224.0.0.251 + ff02::fb) — Android 16 හි NsdManager ප්‍රමාද වුවත්
   අපේ responder එක ක්ෂණිකව පිළිතුරු දේ.
2. **DLNA MediaRenderer (SSDP, UDP 1900):** AnantPlay හි DLNA list එකේ
   **`සිංහල Player - DLNA`** ලෙස පෙනේ (`deviceType: MediaRenderer:1`, AVTransport +
   RenderingControl සේවා). මෙය NsdManager/MdnsAdvertiser සම්පූර්ණයෙන් වෙන් වූ මාර්ගයකි —
   ප්ලැට්ෆෝම් mDNS stack එක සම්පූර්ණයෙන් hang වුවත් DLNA මාර්ගය වැඩ කරයි.

Diagnostics ටැබ් එකේ `mDNS responder` සහ `DLNA` පේළි දෙකෙන් කුමන මාර්ගය සක්‍රීයද පෙනේ.
DLNA අංක 1900 bind කළ නොහැකි නම් එය **degraded** ලෙස පෙන්වයි — crash නොවේ, mDNS දිගටම වැඩ කරයි.

**EN:** Cast discovery now fills from **two independent paths**: our own mDNS responder
(TTL 255, pinned egress, always-multicast replies) **and** a UPnP/DLNA MediaRenderer that
appears in AnantPlay's DLNA list as **`සිංහල Player - DLNA`**. SSDP shares no code with
NsdManager, so the Cast chip lights up even when the platform mDNS stack hangs.

### 1.5.3 · ඇතුළතම ඇති Cinema Browser + ස්වයංක්‍රීය සිංහල උපසිරැසි (Cast අවශ්‍ය නැත)

🌐 **WebView** ටැබය = සම්පූර්ණ browser එකක් (URL box, back/forward/reload, ⋮ menu: desktop mode,
bookmarks, paste link). වීඩියෝවක් play වන විට stream sniffer එක HLS/DASH/MP4/MKV/WEBM
හඳුනාගෙන **“🎬 සිංහල උපසිරැසි සමඟ බලන්න”** pill එක පෙන්වයි — එය ඔබන විට **අපේම player** එක
(එම URL + එම Referer/Cookie headers සමඟ) විවෘත වී සිංහල උපසිරැසි ස්වයංක්‍රීයව ලබාගනී.
**Wi-Fi සහ Mobile Data දෙකෙහිම** වැඩ කරයි; router/mDNS වලට කිසිදු සම්බන්ධයක් නැත.

- **ජාවාරම්/ads:** දන්නා ad/tracker domains block වේ, popup windows සම්පූර්ණයෙන් නවතී.
- **⚡Auto chip:** ස්වයංක්‍රීය විවෘත කිරීම (default **OFF** — ඔබම තීරණය කරන්න).
- App එකෙන් එලියට ගිය විට WebView එක pause වී ශබ්දය නවතී; app එක වැසුවහම WebView එක
  සම්පූර්ණයෙන් destroy වේ (පසුබිම් ශබ්දයක්/leak එකක් නැත).
- **AnantPlay එකෙන් link එකක් යවන්නත් පුළුවන්:** ඕනෑම app එකක Share → **“සිංහල Player”**
  (ACTION_SEND) හෝ browser එකේ “Open with” → stream link එක කෙලින්ම අපේ player එකේ විවෘත වේ.

**EN:** The WebView tab is a real browser with a stream sniffer. When a video plays, the
**“🎬 Play with Sinhala subs”** pill appears; one tap opens our own Media3 player with the exact
URL + Referer/Cookie/UA and auto-fetches Sinhala subtitles — on Wi-Fi **and** mobile data,
with no Cast, mDNS or router involvement. Ads/popups are blocked, and the WebView is paused
on background and destroyed with the app.

### 1.5.4 · App-Open Auto-Updater (සහ Obtainium)

- ඇප් එක **විවෘත කරන විටම** (තත්පර 60ක debounce එකක් සමඟ) සහ **සෑම පැය 4කට** වරක් GitHub
  releases පරීක්ෂා වේ — රහසිගත බොත්තමක් ඔබන්නට අවශ්‍ය නැත.
- අලුත් release එකක් නම්: **https-only** + **30 MB සීමාව** + **SHA-256 අනිවාර්ය සත්‍යාපනය**
  (`SHA256SUMS.txt`) — පසුව OS එකේ **1-tap “Update”** තහඩුව පෙන්වයි (non-root Android හි
  නිශ්ශබ්ද ස්ථාපනය OS මගින්ම අවහිර කර ඇත — එය නිර්මාණයෙන්ම එසේය).
- “Unknown sources” අවසරය නැති නම්, කෙලින්ම Settings → **Install unknown apps** වෙත යන
  1-tap දැනුම්දීමක් පැමිණේ.
- බාගත කළ තාවකාලික `.apk` session එකට ලිවූ **වහාම** මකා දැමේ; සෑම ඇප් ආරම්භයකදීම
  `cacheDir/updates/` පිරිසිදු වේ.

**EN:** The updater checks the moment the app opens (60 s debounce) and every 4 h. A newer
release is downloaded automatically (https-only, 30 MB cap, mandatory SHA-256 match) and the
OS shows its 1-tap **Update** prompt. Temp APKs are deleted immediately after the installer
handoff and swept at every startup.

## 2 · එක් වරක් — Display over other apps (වැදගත්ම එක)

App එක ඇතුළලේ **"Display over other apps"** පේළියේ ❌ දුටුවහම **Open setting** ඔබලා
ඔබේ app එකට **Allow** කරන්න (Settings → Apps → Special access → Display over other apps).
මෙය cast එක ආදාම තිරය අල්ලාගැනීමට අවශ්‍ය එකම පද්ධති ටොගලයයි — **Accessibility නොවේ**.

> **මෙය අනිවාර්ය නොවේ.** අවසරය නොදුන්නත් cast එක **සාර්ථක වේ** — එවිට ඉහළ ප්‍රමුඛතා
> "Cast received" දැනුම්දීමක් පැමිණේ; එය tap කළ විට සිංහල player එක විවෘත වේ
> (crash එකක් හෝ නිශ්ශබ්ද අසාර්ථක වීමක් නැත).

## 3 · Xiaomi HyperOS / MIUI අවසර (අපේ app එක ඇතුළත "Open setting" බොත්තමෙන්)

1. **Autostart** — Autostart පේළියේ "Open setting" → AnantPlay සිංහල Player → **Autostart සකසන්න**
   (දුරකථනය restart වුනාම receiver එක ස්වයංක්‍රීයව අවදි වීමට).
2. **Battery saver → No restrictions** — "Open setting" → **No restrictions** තෝරන්න
   (idle receiver එක powerkeeper අතින් බේරාගැනීමට; චිත්‍රපටිය දාම අපිම app එකම foreground — එවිට
   කිසිම vendor එකකට එය මරන්න බෑ).
3. **Other permissions (MIUI)** — "Other permissions" බොත්තම → **"Display pop-up windows while
   running in the background"** සහ **"Display pop-up window"** දෙකම Allow කරන්න.
4. **Notifications** — Android 13+ දී ඉල්ලුවහම Allow කරන්න; ඉල්ලීම block වුනොත්:
   Settings → Apps → අපේ app → **⋮ → Allow restricted settings** (install කරන්නා APK file එකෙන්
   නිසා Android එක මෙය එක් වරක් ඉල්ලයි).
5. (භාරකරුවන්ට) Recents හිදී app එකේ card එක **ලොක් කරන්න** — "Clear all" වලින් receiver එක බේරේ.

## 4 · ස්වයං පරීක්ෂණය (දුරකථනයම මතදී)

**🧪 Self-test** බොත්තම ඔබන්න — එය loopback HTTP → same-device mDNS discovery → bridge යන
සම්පූර්ණ දාමයම ඔබේ දුරකථනයේ පරීක්ෂා කරනවා. තුනම ✅ නම් **සියල්ල සූදානම්**.
(Wi-Fi ON වී තිබිය යුතුය — same-device cast සඳහා අවශ්‍යයි; internet අවශ්‍ය නැත, router එකක්
සම්බන්ධ වී සිටීම ප්‍රමාණවත්.)

## 5 · චිත්‍රපටිය නැරඹීම

1. සාමාන්‍ය පරිදිම **AnantPlay** විවෘත කරන්න (Settings → Apps → Open AnantPlay බොත්තමෙන් හෝ
   launcher එකෙන්) — ඕනෑම site එකක ඕනෑම චිත්‍රපටියක්/කථාංශයක් විවෘත කරන්න.
2. Player එකේ **cast icon** එක ඔබන්න → list එකේ පළමුණුවෙන්ම තිබෙන
   **"සිංහල Player - AnantPlay (Recommended)"** තෝරන්න. එය නොපෙනේ නම් එම list එකේම
   පහළින් ඇති **`සිංහල Player - DLNA`** එකද තෝරන්න පුළුවන් — එය සම්පූර්ණයෙන් වෙනස්
   මාර්ගයක් (SSDP) නිසා එකක් අසාර්ථක වුවත් අනෙක වැඩ කරයි (§1.5.2).
   *(EN: if the mDNS entry is missing, pick the **`සිංහල Player - DLNA`** entry — an entirely
   independent SSDP path.)*
3. චිත්‍රපටිය මෙතනදී පානවා — සිංහල උපසිරැසි සමඟ. අඩුපුරවා ගන්නා විට
   **"අ" pill** එකේ (වම් පස search icon) — font size, mask, ±0.25s/±1s කාල ගැලපුම, title
   override, local .srt/.zip load සියල්ල එහි තිබෙනවා.
4. ඉවර වුවහම: Back ඔබන්න — AnantPlay හි Remote screen එකට ආපසු යන්නේ ඔබ නතර වූ තැනින්ම;
   නැතහොත් Remote එකේ **Stop casting** → AnantPlay player එකම අඛණ්ඩව දිගටම පානවා.

## 6 · භාෂා 18ක් · Spoken/Formal · Cinema Polish · AI Studio (v2.2)

**භාෂාව මාරු කිරීම.** Player එකේ පහළින් ඇති chips වලින් **සිංහල · தமிழ் · हिन्दी · English ·
Español · 日本語 · 한국어 · العربية** තෝරන්න; **“+ More”** ඔබලා සියලුම භාෂා **18** බලන්න
(ප්‍රංශ, ජර්මන්, රුසියන්, පෘතුගීසි, තායි, ඉන්දුනීසියානු, තුර්කි, වියට්නාම්, උර්දු, චීන).
සිංහල අකුරු සඳහා app එකේම ඇති NotoSansSinhala font එක යොදනු ලැබේ; අනෙක් භාෂා දුරකථනයේ
ම system fonts වලින් පෙන්වයි (අතිරේක download එකක් නැත). **العربية / اردو** දකුණේ සිට වමට
නිවැරදිව පෙන්වයි.

**Spoken ⇄ Formal.** උපසිරැසි පැනලයේ register pill එකෙන් `auto (genre) → spoken → formal`
මාරු වේ. “spoken” = කථා කරන භාෂාව (සිනමා සංවාදය), “formal” = ලිඛිත/විධිමත් සිංහල
(ලේඛන, ප්‍රවෘත්ති). මාරු කළ විට එම රටාවෙන් නැවත පරිවර්තනය වේ.

**දෙවන අදියර · Cinema Polish.** පළමු පරිවර්තනය (base NMT) **වහාම** පෙනේ — චිත්‍රපටිය නතර
නොවේ. තත්පර 20–40ක් ඇතුළත worker එකේ සිනමා සංස්කරණය සූදානම් වූ විට, උපසිරැසි **නිශ්ශබ්දව**
(playback නතර නොකර) උසස් වේ — තිරයේ **“✨ Cinema Polish Ready”** කෙටියෙන් පෙනේ.
එය නොපෙනුනත් base උපසිරැසි දිගටම ක්‍රියාත්මකයි; අතින් **↻ Retry** ඔබන්න.

**✨ AI Studio (අවශ්‍ය නම් පමණක්).** `අ` පැනලයේ **✨ AI Studio** ඔබන විට, ඉංග්‍රීසි දෙබස්
සහ පවතින පරිවර්තනය 40 KBකට නොවැඩි කොටසක් clipboard එකට copy වී, ඔබේ **සැබෑ browser** එකේ
`aistudio.google.com` විවෘත වේ (WebView එකක් නොවේ — ස්වයංක්‍රීයකරණයක් නොවේ). එහි paste කර
හොඳ සිනමා සිංහල පිටපතක් ලබාගෙන, **📄 Local .srt** මගින් නැවත import කරන්න. Browser එකක්
නැති දුරකථනයක එය crash නොවේ — “පේළි පිටපත් විය, නමුත් browser එකක් නැත” ලෙස පවසයි.

**AdGuard: Private DNS ⚠️ නොවේ, VPN ⚠️ ඔව්.** AdGuard **Private DNS**
(`dns.adguard-dns.com`, DoT 853) casting එකට කිසිදු බලපෑමක් නොකරයි — එය encrypt කරන්නේ
unicast DNS පමණි; mDNS භාවිතා කරන UDP 5353 multicast ට එයින් බලපෑමක් නැත. නමුත්
AdGuard **Android app** එක (local VPN) multicast අවහිර කළ හැක. විසඳුම: AdGuard →
Settings → Network → **DNS filtering → “Filter DNS only”** හෝ app එකේ **“Exclude AnantPlay”**
එකතු කරන්න; නැතහොත් casting අතරතුර AdGuard app එක off කරන්න. In-app **AdGuard help**
කාඩ්පතේ මෙම පියවර දෙකම ඇත.

## 7 · ප්‍රශ්නයක් තිබෙනවා නම්

- App එකේ **Diagnostics · Crash log** කොටස බලන්න — සෑම event එකක්ම වාර්තා වී තිබේ;
  **📋 Copy diagnostics log** බොත්තමෙන් copy කරගෙන bug වාර්තාවකට එකතු කරන්න.
- Cast list එකේ "සිංහල Player" නොපෙනේ නම්: Wi-Fi ONද? → app එක විවෘත කර Self-test ධාවනය
  කරන්න → තවමත් ❌ නම් AnantPlay හි cast sheet එකේ **Re-scan** ඔබන්න.
- **"CC English (stream)"** පේළියක් පෙනේ නම්: ඒ අර්ථය නම් සිංහල pipeline එක තාවකාලිකව
  අසමත් වී ඉංග්‍රීසි CC වෙත පසුබසින බවයි — "නැවත ලබාගන්න · Refetch subs" ඔබන්න, නැත්නම්
  local .srt එකක් load කරන්න.
- Gemini auto-translation 429/quota දෝෂයක් නම්: worker එකේ API key quota එක අවසාන වී ඇත
  (නොමිලේ tier එක daily reset වේ) — `wrangler secret put GEMINI_API_KEY` නැවත සකසන්න.

## 8 · පැරණි v1 Companion එක

පැරණි `AnantPlay-SI-Companion` (accessibility overlay) app එක **disable/uninstall** කරන්න —
නව ක්‍රමයට එය අවශ්‍ය නැත, එය HyperOS හි "Not working" තත්ත්වයටම වැටෙන බව දැනටමත්
ඔබ දුටුවා. AnantPlay නිල app එකට කිසිදු වෙනසක් සිදු නොවේ — එය තවමත් නිල update
ලබාගනී.

— සියලු විස්තර (English): `docs/CAST-VERIFICATION.md` · `README.md`


---

## 9 · 1-වරක් ස්ථිර signing key · Merge-to-Release CI · Obtainium (v3.0.0)

### පියවර 1 — ස්ථිර signing key එක හදන්න (එක් වරක් පමණි!)

ඔබේ PC එකේ මෙය ධාවනය කරන්න (Linux/macOS: `sh scripts/create-release-keystore.sh` ·
Windows: `pwsh -File scripts\create-release-keystore.ps1`). එය `release-keystore.jks` +
`github-secrets.txt` සාදයි. **දෙකම ආරක්ෂිතව backup කරගන්න** (password manager + තවත් disk එකක්).

### පියවර 2 — GitHub Secrets 4ක් set කරන්න (එක් වරක් පමණි!)

GitHub → dictationb1/anantplay-sinhala-player → **Settings → Secrets and variables →
Actions → New repository secret** — `github-secrets.txt` එකේ අගයන් 4:

| Secret | අගය |
|---|---|
| `KS_BASE64` | secrets ගොනුවේ දිග base64 පේළිය (සම්පූර්ණයෙන්) |
| `KS_PASS` | ජනනය වූ මුරපදය |
| `KS_KEYPASS` | එම මුරපදයම |
| `KS_ALIAS` | `sicast` |

### පියවර 3 — Release එකක් publish කිරීම — **දැන් ස්වයංක්‍රීයයි (Merge-to-Release)**

Secrets 4 set කළ පසු, **`main` වෙත merge වන සෑම PR එකක්ම** ස්වයංක්‍රීයව release එකක් බවට පත් වේ:

1. `release-apk.yml` `build.sh` එකෙන් version එක කියවයි (`3.0.0` → `versionCode 30000`).
2. එම tag එක (`v3.0.0`) දැනටමත් තිබේ නම් → **නිශ්ශබ්දව skip** (duplicate release error නැත).
3. නැත්නම්: Lab 100% PASS → APK sign → `apksigner verify` → `SHA256SUMS.txt` → tag → Release.
4. Release එකේ **එකම `.apk` එකක් පමණක්** ඇත (`AnantPlay-Sinhala-Player-v3.0.0.apk`) +
   `SHA256SUMS.txt` — Obtainium ට වඩාත් පැහැදිලිය.

- **අතින් (අවශ්‍ය නම් පමණක්):** GitHub → Actions → **Release APK** → Run workflow → version
  එක ලියන්න (උදා: `3.0.1`). හෝ `git tag v3.0.1 && git push origin v3.0.1`.
- `KS_BASE64` තවම set නොකර ඇත්නම් `main` push එක **රතු නොවේ** — එය මිත්‍රශීලී දැනුම්දීමක්
  පමණක් පෙන්වා සාමාන්‍යයෙන් (green) අවසන් වේ; APK එක `build-apk.yml` artifact එකෙන් ලැබේ.

**EN:** This is the whole one-time setup: run the keystore script, add
`KS_BASE64` / `KS_PASS` / `KS_KEYPASS` / `KS_ALIAS` once. After that **every PR merged into
`main`** is gated by the lab, built, signed with that permanent key and published as a GitHub
Release (one `.apk` + `SHA256SUMS.txt`). Existing tags are skipped idempotently, and a
push to `main` without the secrets exits green with a friendly notice.

### පියවර 4 — Obtainium එකට දමන්න (දුරකථනයේ, 3 tap!)

1. Obtainium install කරන්න (F-Droid/KVTB).
2. **+ Add App** → `https://github.com/dictationb1/anantplay-sinhala-player` paste කරන්න
   → Add (default settings එක ම ඇති — Release එකේ `.apk` file එකක් පමණක් ඇති නිසා
   තේරීමක් අවශ්‍ය නැත). හෝ `docs/obtainium-config.json` import කරන්න.
3. Install → ඉන්පසු සෑම release එකක්ම Obtainium එකම දර්ශනය කරයි → **Update** ඔබන්න.

**ඇයි uninstall කරන්න අවශ්‍ය නැත්තේ?** සෑම release එකක්ම එකම `KS_BASE64` key එකෙන්
sign වන නිසා Android එක එය "same app" ලෙස හඳුනාගෙන data/data එක තබාගෙන update කරයි —
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` කිසිදා නැත. Key එක වෙනස් වුණොත් පමණක් uninstall අවශ්‍ය වේ.

### පියවර 5 — දුරකථනයේදීම update (App-Open Auto-Updater)

**ඔබ කිසිවක් ඔබන්නේ නැතුව** ඇප් එක update වේ:

1. ඇප් එක විවෘත කරන විටම (තත්පර 60ක debounce) + සෑම පැය 4කට වරක් GitHub releases පරීක්ෂා වේ.
2. අලුත් version එකක් ඇත්නම් Home tab එකේ **live progress bar** එකක් + දැනුම්දීමක් පෙන්වමින්
   බාගත වේ (https-only · 30 MB සීමාව · **SHA-256 අනිවාර්ය**).
3. OS එකේ **1-tap “Update”** තහඩුව පැමිණේ → එක තට්ටුවකින් install (data එකම තිබේ).
4. “Unknown sources” අවසරය නැති නම් Settings වෙත යන 1-tap දැනුම්දීමක් පැමිණේ.

Settings ටැබයේ **Auto-Update** toggle එකෙන් මෙය off කළ හැක (එවිට දැනුම්දීමක් පමණක් පැමිණේ).

**EN:** The app updates itself the moment it opens — no button, no Obtainium required.
A newer release is downloaded with live progress, verified by SHA-256, and the OS shows its
1-tap Update prompt (the only step Android allows a non-root app to leave to the user).

### දුරකථනයේදීම අතින් check (Settings · Obtainium)

- **Settings → Updates → Check now** — වහාම පරීක්ෂා කරයි (Auto-Update off කළත්).
- **Obtainium** — දිගටම වැඩ කරයි; එය සහ ඇප් එකේ updater එක **එකම permanent key** එකෙන්
  sign වන නිසා දෙකම in-place update වේ (uninstall කිසිදා නැත).
- Release එකේ `SHA256SUMS.txt` එකෙන් APK එකේ අඛණ්ඩතාව අතින්ම තහවුරු කරගත හැක:
  `sha256sum AnantPlay-Sinhala-Player-v3.0.0.apk`

**EN:** Manual check: Settings → Updates → Check now. Obtainium keeps working, and both paths
share the same permanent signing key, so updates always install in place. Every release
carries `SHA256SUMS.txt` for independent verification.

---

## 10 · v3.0.0 — Cast chip එක කොහෙද? · ගැටලු විසඳීම (සිංහල + English)

### 10.1 · AnantPlay එකේ Cast chip එක — හරියටම කොහෙද

1. AnantPlay (නිල app එක — `me.champdev.weblock`) විවෘත කරන්න.
2. යම් වීඩියෝවක් play වෙන තිරයේ **පහළ toolbar** එක දෙස බලන්න.
3. **Cast icon** එක එහි ඇත. එය ඔබන්න.
4. ලැයිස්තුවේ **`සිංහල Player - AnantPlay (Recommended)`** හෝ **`සිංහල Player - DLNA`** තෝරන්න.
5. එය සාර්ථක නම් Sinhala Player app එකේ player එක විවෘත වේ (Sinhala subtitles සමඟ).

> මෙය නොපෙනේ නම්: app එකේ **🩺 Diagnostics → 🧪 Self-test** ඔබන්න. එහි
> `L1 daemon ✓ · L2 responder ✓ · DLNA ✓` පෙනේ නම් discovery හරි. තවමත් ❌ නම් AnantPlay
> cast sheet එකේ **Re-scan** ඔබන්න, නැත්නම් **🌐 WebView** ටැබයෙන් cast නැතුවම බලන්න (§1.5.3).

**English:** In AnantPlay, open any video → the **bottom toolbar** carries the **Cast icon** →
tap it → choose **`සිංහල Player - AnantPlay (Recommended)`** or **`සිංහල Player - DLNA`**.
The Sinhala Player opens with Sinhala subtitles. If neither appears, run the in-app
**Diagnostics → Self-test** (`L1 daemon ✓ · L2 responder ✓ · DLNA ✓` proves discovery), then
**Re-scan** in AnantPlay's cast sheet — or skip casting entirely with the **WebView** tab.

### 10.2 · ගැටලු විසඳීම (v3.0.0 විශේෂ)

| ලක්ෂණය | අර්ථය | විසඳුම |
|---|---|---|
| `mDNS responder: degraded(mdnsd-only)` | UDP 5353 share කළ නොහැක (vendor block) | NsdManager මාර්ගය + DLNA දිගටම වැඩ කරයි; VPN/AdGuard off කරන්න |
| `DLNA: bind-failed` | UDP 1900 වෙනත් app එකක (උදා: media server) අල්ලාගෙන | එම app එක නවත්වන්න හෝ mDNS මාර්ගය භාවිතා කරන්න |
| WebView ටැබය “update කරන්න” කියයි | Android System WebView update වෙමින්/නැත | Play Store → **Android System WebView** update කර නැවත විවෘත කරන්න |
| Update බාගත වුනත් install නොවේ | “Install unknown apps” අවසරය නැත | දැනුම්දීමේ 1-tap → අපේ app එකට Allow |

---

— සියලු ගැඹුරු විස්තර (English): `V3_MASTER_ARCHITECTURE_AND_ANDROID16_REPORT.md` ·
`docs/CAST-VERIFICATION.md` · `README.md`
