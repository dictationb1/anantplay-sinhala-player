# AnantPlay සිංහල Player — Xiaomi HyperOS සැකසුම් මාර්ගෝපදේශය (v2.0.0)

> **අලුත්ම ක්‍රමය: Accessibility අවසරයක් අවශ්‍ය නැත.** AnantPlay හි **Cast** බොත්තමෙන් චිත්‍රපටිය
> **මෙම app එකටම** එවනවා — සිංහල උපසිරැසි සහිතව. AnantPlay නිල app එක තවමත් 100% නොවෙනස්ව තිබේ.

## 1 · App එක install කරන්න

`AnantPlay-Sinhala-Player.apk` install කරලා **විවෘත කරන්න** (එය "AnantPlay සිංහල Player" ලෙස පෙනේ).
"Start receiver" බොත්තම ඔබන්න — ඉහළම දැනුම්දීම් තීරුවේ **"අ — Sinhala Player cast ready"**
දැනුම්දීම පැමිණේ (එය receiver එක ක්‍රියාත්මක බවට සලකුණයි).

## 2 · එක් වරක් — Display over other apps (වැදගත්ම එක)

App එක ඇතුළලේ **"Display over other apps"** පේළියේ ❌ දුටුවහම **Open setting** ඔබලා
ඔබේ app එකට **Allow** කරන්න (Settings → Apps → Special access → Display over other apps).
මෙය cast එක ආදාම තිරය අල්ලාගැනීමට අවශ්‍ය එකම පද්ධති ටොගලයයි — **Accessibility නොවේ**.

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
   **"සිංහල Player - AnantPlay (Recommended)"** තෝරන්න.
3. චිත්‍රපටිය මෙතනදී පානවා — සිංහල උපසිරැසි සමඟ. අඩුපුරවා ගන්නා විට
   **"අ" pill** එකේ (වම් පස search icon) — font size, mask, ±0.25s/±1s කාල ගැලපුම, title
   override, local .srt/.zip load සියල්ල එහි තිබෙනවා.
4. ඉවර වුවහම: Back ඔබන්න — AnantPlay හි Remote screen එකට ආපසු යන්නේ ඔබ නතර වූ තැනින්ම;
   නැතහොත් Remote එකේ **Stop casting** → AnantPlay player එකම අඛණ්ඩව දිගටම පානවා.

## 6 · ප්‍රශ්නයක් තිබෙනවා නම්

- App එකේ **Diagnostics · Crash log** කොටස බලන්න — සෑම event එකක්ම වාර්තා වී තිබේ;
  **📋 Copy diagnostics log** බොත්තමෙන් copy කරගෙන bug වාර්තාවකට එකතු කරන්න.
- Cast list එකේ "සිංහල Player" නොපෙනේ නම්: Wi-Fi ONද? → app එක විවෘත කර Self-test ධාවනය
  කරන්න → තවමත් ❌ නම් AnantPlay හි cast sheet එකේ **Re-scan** ඔබන්න.
- **"CC English (stream)"** පේළියක් පෙනේ නම්: ඒ අර්ථය නම් සිංහල pipeline එක තාවකාලිකව
  අසමත් වී ඉංග්‍රීසි CC වෙත පසුබසින බවයි — "නැවත ලබාගන්න · Refetch subs" ඔබන්න, නැත්නම්
  local .srt එකක් load කරන්න.
- Gemini auto-translation 429/quota දෝෂයක් නම්: worker එකේ API key quota එක අවසාන වී ඇත
  (නොමිලේ tier එක daily reset වේ) — `wrangler secret put GEMINI_API_KEY` නැවත සකසන්න.

## 7 · පැරණි v1 Companion එක

පැරණි `AnantPlay-SI-Companion` (accessibility overlay) app එක **disable/uninstall** කරන්න —
නව ක්‍රමයට එය අවශ්‍ය නැත, එය HyperOS හි "Not working" තත්ත්වයටම වැටෙන බව දැනටමත්
ඔබ දුටුවා. AnantPlay නිල app එකට කිසිදු වෙනසක් සිදු නොවේ — එය තවමත් නිල update
ලබාගනී.

— සියලු විස්තර (English): `docs/CAST-VERIFICATION.md` · `README.md`
