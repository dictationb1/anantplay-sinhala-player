# AnantPlay සිංහල Player — Xiaomi HyperOS සැකසුම් මාර්ගෝපදේශය (v2.3.1)

> **අලුත්ම ක්‍රමය: Accessibility අවසරයක් අවශ්‍ය නැත.** AnantPlay හි **Cast** බොත්තමෙන් චිත්‍රපටිය
> **මෙම app එකටම** එවනවා — සිංහල උපසිරැසි සහිතව. AnantPlay නිල app එක තවමත් 100% නොවෙනස්ව තිබේ.
>
> **v2.2 අලුත් දේ:** භාෂා **18ක්**, **Spoken/Formal** රටා මාරුව, **දෙවන අදියර Cinema Polish**
> (ස්වයංක්‍රීයව උසස් වේ), සහ අවශ්‍ය නම් පමණක් **✨ AI Studio** බොත්තම.

## 1 · App එක install කරන්න

`AnantPlay-Sinhala-Player-v2.3.1.apk` install කරලා **විවෘත කරන්න** (එය "AnantPlay සිංහල Player" ලෙස පෙනේ).
"Start receiver" බොත්තම ඔබන්න — ඉහළම දැනුම්දීම් තීරුවේ **"අ — Sinhala Player cast ready"**
දැනුම්දීම පැමිණේ (එය receiver එක ක්‍රියාත්මක බවට සලකුණයි).

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
සම්පූර්ණ දාමයම ඔබේ දුරකථනයේ පරීක්ෂා කරනවා. ප්‍රතිඵලය ස්ථර අනුව පෙන්වයි —
උදා: `mDNS ok [L1 daemon ✓ · L2 responder ✓ · L3 NSD ✗]` — එහි L1 = mdnsd daemon (loopback + Wi-Fi),
L2 = අපේම 5353 responder (kernel loopback), L3 = NsdManager discovery (bonus). සියල්ල ✅ නම්
**සියල්ල සූදානම්**; L1 හෝ L2 ✅ නම් cast එක ක්‍රියා කරන බව ඔප්පු වේ (L3 ❌ වුවත්).
(Wi-Fi ON වී තිබිය යුතුය — same-device cast සඳහා අවශ්‍යයි; internet අවශ්‍ය නැත, router එකක්
සම්බන්ධ වී සිටීම ප්‍රමාණවත්.)

## 5 · චිත්‍රපටිය නැරඹීම

1. සාමාන්‍ය පරිදිම **AnantPlay** විවෘත කරන්න (Settings → Apps → Open AnantPlay බොත්තමෙන් හෝ
   launcher එකෙන්) — ඕනෑම site එකක ඕනෑම චිත්‍රපටියක්/කථාංශයක් විවෘත කරන්න.
2. Player එකේ **cast icon** එක ඔබන්න → list එකේ පළමුණුවෙන්ම තිබෙන
   **"සිංහල Player - AnantPlay (Recommended)"** තෝරන්න.
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

## 9 · Obtainium + GitHub Releases — සදාකාලික in-place යාවත්කාලීන (v2.3.1)

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

### පියවර 3 — Release එකක් publish කරන්න

- **ස්වයංක්‍රීය:** `git tag v2.3.1 && git push origin v2.3.1` — `release-apk.yml`
  එක Lab 100% PASS බව පරීක්ෂා කර, APK එක sign කර `apksigner` එකෙන් verify කර,
  `SHA256SUMS.txt` සමඟ Release එකක් සාදයි.
- **අතින් 1-click:** GitHub → Actions → **Release APK** → Run workflow → version එක
  ලියන්න (උදා: `2.3.1`).

### පියවර 4 — Obtainium එකට දමන්න (දුරකථනයේ, 3 tap!)

1. Obtainium install කරන්න (F-Droid/KVTB).
2. **+ Add App** → `https://github.com/dictationb1/anantplay-sinhala-player` paste කරන්න
   → Add (default settings එක ම ඇති — Release එකේ `.apk` file එකක් පමණක් ඇති නිසා
   තේරීමක් අවශ්‍ය නැත). හෝ `docs/obtainium-config.json` import කරන්න.
3. Install → ඉන්පසු සෑම release එකක්ම Obtainium එකම දර්ශනය කරයි → **Update** ඔබන්න.

**ඇයි uninstall කරන්න අවශ්‍ය නැත්තේ?** සෑම release එකක්ම එකම `KS_BASE64` key එකෙන්
sign වන නිසා Android එක එය "same app" ලෙස හඳුනාගෙන data/data එක තබාගෙන update කරයි —
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` කිසිදා නැත. Key එක වෙනස් වුණොත් පමණක් uninstall අවශ්‍ය වේ.

### දුරකථනයේදීම update check

ඇප් එක තුළ **"⬆ Check for updates (GitHub)"** බොත්තම — අලුත් release එකක් ඇත්නම්
Release පිටුව විවෘත වේ (Obtainium එකේදී එය ස්වයංක්‍රීයවම සිදු වේ).
