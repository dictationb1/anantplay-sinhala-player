# AnantPlay Player — Xiaomi HyperOS සැකසුම් මාර්ගෝපදේශය (v3.4.6.i) — versionCode 30415

> **v3.4.5 අලුත් දේ (Sinhala):**
>
> 1. **Mornye Glass** දැන් පෙරනිමි මතුපිටයි. Glass clarity 10–95% අතර සජීවීව සකස් කළ හැකි අතර
>    rim outline සහ glass icon medallions වෙන වෙනම සක්‍රිය/අක්‍රිය කළ හැක.
> 2. **Theme සහ අකුරු** — System, Midnight, AMOLED සහ Clean Light themes; Modern Inter,
>    System, Rounded සහ Monospace UI fonts. Sinhala අකුරු සඳහා Noto fallback එක පවතී.
> 3. **Auto-update ආරක්ෂාව** — 50 MiB ට අඩු APK සීමාව, download SHA-256 නැවත පරීක්ෂා කිරීම,
>    සහ redirect → sums → APK local E2E පරීක්ෂණ එක් කළා.
>
> **v3.4.4 අලුත් දේ (Sinhala):**
>
> 1. **Player එකේ පහසු පාලක තීරුව** — උපසිරැසි, tracks, aspect, වේගය සහ title search සඳහා
>    සමාන ඉඩ සහිත අයිකන 5ක්; සියලු බොත්තම්වල ප්‍රවේශ විස්තර ඇත.
> 2. **සැකසුම් පිරිසිදුයි** — ප්‍රධාන උපසිරැසි පාලක ඉදිරියෙන්; අමතර provider සහ AI මෙවලම්
>    “More tools” තුළ වසා තබයි. Aspect සහ sleep timer සඳහා segmented controls ඇත.
> 3. **Quick Settings පෙරදසුන්** — Flat/Glass මතුපිට සහ Docked/Floating පහළ තීරුව සෘජුව
>    තෝරාගත හැක; accent වර්ණ 10ක් ඇත.
> 4. **අයිකන හා වර්ණ** — outlined vector icons 1.75–1.85dp stroke පරාසයක සකස් කර ඇත; Flat cards වල
>    අනවශ්‍ය මායිම් ඉවත් කර Glass mode එකේ hairline සුරකියි; stroke පළල 1.75–1.85dp අතරය.
> 5. **English UI default** — Sinhala subtitle preview සහ subtitle language names එලෙසම පවතී.
>
> **v3.4.3 අලුත් දේ (Sinhala):**
>
> 1. **පළමු වරට යෙදුම විවෘත කළ විට පියවර 5ක සැකසුම් විශාරදය** — සාදරයෙන් පිළිගැනීම → දැනුම්දීම් → Cast කළ විට ඉබේ විවෘත වීම → පසුබිම ස්ථාවරත්වය → AnantPlay සම්බන්ධ කිරීම සහ ජීව ස්වයං-පරීක්ෂාව. සෑම පියවරක්ම Skip කළ හැක.
> 2. **ආරක්ෂාව (P0)** — Cookie එක අදාළ host එකට පමණක් යවයි, HTTPS → HTTP පහත වැටීමක් සිදු නොවේ, බ්‍රව්සරයේ mixed content සම්පූර්ණයෙන්ම අවහිරයි.
> 3. **Continue Watching ආරක්ෂිතයි** — Cookie/Authorization සුරකින්නේ නැත; කල් ඉකුත් වූ සැසියක් සඳහා "Session expired — Re-open in AnantPlay" එක-තට්ටුවක්.
> 4. **සැකසුම් සරලයි** — ඉහළින් ඉක්මන් සැකසුම් 5ක්; ඉතිරි සියල්ල "උසස් සැකසුම්" යටතේ.
>
> **v3.4.2 අලුත් දේ (Sinhala):**
>
> 1. **පිරිසිදු ඉංග්‍රීසි UI (default)** — සිංහල UI Settings එකෙන් තෝරාගත හැක. චිත්‍රපට උපසිරැසි තවමත් සිංහල.
> 2. **Continue Watching** — මුල් තිරයේම නැරඹූ චිත්‍රපට, progress bar, `0:42:15 / 1:58:00` වේලාව සහ 1-tap resume.
> 3. **ධාවක (player) නිවැරදි කිරීම්** — උපසිරැසි පහළින්, pill තත්පර 2.5න් මැකෙයි, අයිකන 40dp තුළ 20dp, පැටලෙන floating rows ඉවරයි, landscape සැබෑ fullscreen.
> 4. **Midnight Night Dimmer** — රාත්‍රී නැරඹීමට තිරය අඳුරු කරන පෙරහන (10–70%).
> 5. **Auto-Open on Cast** — එක වරක් "Display over other apps" දුන්නම සෑම Cast එකක්ම ඉබේ විවෘත වේ.
> 6. **Orientation lock + Hold-2× speed** සහ **0.5×–2.5×** වේග පිකර්.
> 7. **Settings තැනේම රැඳේ** — ඕනෑම සැකසුමක් වෙනස් කළත් Home එකට යන්නේ නැත.
> 8. **බ්‍රව්සරය** — keyboard එකට ඉහළින් omnibar, live search suggestions.

> **v3.4.0 අලුත් දේ (Sinhala):**
>
> 1. **සියලුම emoji වෙනුවට සැබෑ vector අයිකන.** පහළ තීරුව, ඉහළ තීරුව, ධාවක HUD,
>    chip, sheet සහ බ්‍රව්සර් තීරුව — 63 `VectorDrawable` (24dp, 1.75dp stroke).
> 2. **නව යෙදුම් අයිකනය** — adaptive (ස්ක්වර්කල්), රවුම් සහ Material You monochrome;
>    `ස` අකුරේ සැබෑ outline එකෙන් නිර්මාණය කළා.
> 3. **සැබෑ Sinhala බර 400 + 600** — faux bold ඉවරයි; ෆොන්ට් එක 1,154 KB සිට 422 KB දක්වා.
> 4. **Mornye-මට්ටමේ Customize** — තේමා 4, වර්ණ 10 + custom hex, glass surface,
>    bottom bar style, subtitle studio සහ තවත් (මුළු විකල්ප 75).
> 5. **නව ධාවක HUD** — 3-zone glass HUD, 3 bottom sheet (Subtitles / Audio & Quality /
>    Player settings), scrub bar සහ brightness/volume capsule.
> 6. **නව බ්‍රව්සරය** — speed dial (2/3/4 තීරු), පහළ omnibar, capture dock, tabs.

> **v3.3.1 අලුත් දේ (Sinhala):**
>
> 1. **දෙවරක් crash වූ දෙකම සම්පූර්ණයෙන් නැවැත්තුවා.** (අ) ධාවකයේ
>    `ClassCastException: LinearLayout$LayoutParams → FrameLayout$LayoutParams`
>    (`FrameLayout.onMeasure` තුළ) — උපසිරැසි pill තීරුව, chip පේළිය සහ
>    ⏪/⏩ ripple වැරදි `LayoutParams` වර්ගයෙන් එකතු කළ නිසා. (ආ) 🩺
>    Troubleshoot එකේ `ConcurrentModificationException` — probe thread එක සහ
>    UI thread එක එකම ලැයිස්තුවක් එකවර වෙනස් කළ නිසා. දැන් probe එංජිම
>    වෙනස් කළ නොහැකි snapshot පමණක් ලබා දෙයි.
> 2. **නව ස්ථිතික පරීක්ෂකය (`lab/verify_layouts.py`).** සියලුම Java ගොනු 46
>    පරීක්ෂා කර, වැරදි `LayoutParams` එකක් තිබුණොත් build එකම FAIL වේ —
>    මෙම crash නැවත ඇතිවීම ව්‍යුහාත්මකව කළ නොහැකි ය.
> 3. **Card corner radius දැන් සැබවින්ම වැඩ කරයි.** Sharp 6dp / Rounded 16dp /
>    Apple Pill 28dp — තට්ටු කළ විගස UI එක වෙනස් වේ (කලින් සැමවිටම 16dp).
> 4. **Backup & Restore සම්පූර්ණයි.** v3.3.0 හි lock-on-start සහ swipe
>    සැකසුම් 3 backup එකෙන් ඉවත් වී ගියා; දැන් සැකසුම් යතුරු 50ම
>    export → import වලින් නැවත ලැබේ.
>
> **v3.3.0 අලුත් දේ (Sinhala):**
>
> 1. **පහළ සැරිසැරීමේ තීරුව (Bottom Navigation Bar) නැවත ලැබුණා.** v3.2.0 හි
>    `MainActivity.buildUi()` එකේ `bottomBar` එක සාදා, ටැබ 4ක් ද එකතු කර,
>    **`root.addView(bottomBar)` කිසිවිටෙකත් කැඳවා නැති** නිසා එය තිරයේ
>    කිසිසේත් නොපෙනුණි (ඉතිරි වූයේ පහළින් හිස් අඳුරු ප්‍රදේශයක් පමණි).
>    දැන් `LinearLayout(VERTICAL)` — Top Bar (WRAP) + Content (0dp, weight 1) +
>    Bottom Bar (WRAP). තීරුව තිරයෙන් එළියට තල්ලු කිරීම ගණිතමය වශයෙන්
>    කළ නොහැක. ඉහළ තීරුවේ 🩺 සහ ⚙️ නිතරම 1-තට්ටුවකින් ලබා ගත හැක.
> 2. **HTTP 403 නිවැරදියි.** නිල AnantPlay හි play වන ධාරාවක් අපගේ ධාවකයේ
>    `ERROR_CODE_IO_BAD_HTTP_STATUS · 403` ලෙස අසාර්ථක වූයේ කප්පාදු කළ
>    User-Agent, `Origin` නොගැලපීම සහ cookie වැරදි host එකට යැවීම නිසාය.
>    දැන් සැබෑ Chrome UA + Referer වෙතින් ලබාගත් Origin + host-අනුව cookie +
>    401/403/451 සඳහා **ස්වයංක්‍රීය එක් නැවත උත්සාහයක්** ක්‍රියාත්මක වේ.
> 3. **රූප පරිමාණය (Aspect Ratio) නිවැරදියි.** 16:9 චිත්‍රපටයක් සිරස් තිරයේ
>    දැන් 1080×608 ලෙස (කිසිදු විකෘතියක් නැතිව) පෙන්වයි. Fit / Fill / 16:9 /
>    Stretch — 4 මාදිලි, 🔄 Rotate, 🔒 Lock, දෙවරක් තට්ටු කිරීමෙන් ±10s,
>    දකුණු/වම් ස්වයිප් එකෙන් හඬ/දීප්තිය, 📺 VLC බාහිර විවෘත කිරීම.
> 4. **🇱🇰 සිංහල උපසිරැසි ස්වයංක්‍රීය සොයාගැනීම.** Baiscope / Zoom.lk / PirateLK /
>    SubDL සොයා ගැනීම + බාගත කරන `.zip`/`.srt` ස්වයංක්‍රීයව විවෘත කර
>    (5 MB zip-bomb සීමාව, `..` තහනම) දැනට ධාවනය වන චිත්‍රපටයට සම්බන්ධ වේ.

> **v3.2.0 අලුත් දේ (Sinhala):**
>
> 1. **Player crash එක සම්පූර්ණයෙන් නිවැරදියි.** v3.1.1 දී **Test Player**, **Play Video URL**
>    සහ **Cast එකෙන් එන සෑම චිත්‍රපටියක්ම** විවෘත වන මොහොතේ **"keeps stopping"**
>    පණිවිඩය පැමිණියේ `PlayActivity` එක ඇතුළත `setBackgroundResource(0x88000000)`
>    ක්‍රමය එම අගය **resource id** එකක් ලෙස සලකා `Resources$NotFoundException`
>    එකක් විසි කළ නිසාය. දැන් `setBackgroundColor(0x88000000)` භාවිතා වේ —
>    crash එකක් නැත, සම්පූර්ණයෙන්ම නිහඬ ලෙස වැඩ කරයි.
> 2. **Updater spin-loop එක නැවතුණා.** තත්පර 645ක් ඇතුළත `selfupd` පේළි 38ක්
>    ලියැවුණු බව දුරකථන ලොගයේ පෙනුණා. `SCHEDULE_DEBOUNCE_MS = 10_000L` එකෙන්
>    zero-delay නැවත schedule කිරීම් නවතා, skip log එක තත්පරයකට පේළි 1කට සීමා කර ඇත.
> 3. **Privacy-First Deep Troubleshooter (app එක තුළම).** Diagnostics tab එකේ
>    **🩺 Run Deep Troubleshoot** බොත්තමෙන් **පියවර 6ක** active පරීක්ෂණයක් ධාවනය වේ
>    (receiver loopback → mDNS → DLNA → worker bridge → උපසිරැසි pipeline → player readiness),
>    සහ **📋 Copy Full Troubleshoot Report (Privacy-Safe)** බොත්තමෙන් සම්පූර්ණ වාර්තාව
>    1-tap එකකින් copy කරයි — **දුරකථන මාදිලිය / IP / MAC / EUI-64 / SSID / token /
>    storage path — කිසිවක් එහි නොලියේ** (`PrivacyScrubber` සෑම පේළියකම යොදයි).
> 4. **PC ADB capture scripts.** `scripts/troubleshoot-adb.sh` (Linux/macOS) සහ
>    `scripts/troubleshoot-adb.ps1` (Windows PowerShell 5.1) — logcat එකෙන් මේ app එකේ
>    cast/crash පේළි පමණක් ගෙන, එම privacy rules අනුව filter කර පෙන්වයි.
>    දුරකථනයට කිසිවක් ලියන්නේ නැත.
>
> **English (v3.2.0):** the `PlayActivity` crash is fixed at the source
> (`setBackgroundColor` instead of `setBackgroundResource(0x…)`), the updater's
> 38-lines-in-645 ms spin loop is gone (`SCHEDULE_DEBOUNCE_MS = 10_000L` +
> ≤1 skip line/sec), and Diagnostics now has a **privacy-first built-in Deep
> Troubleshooter** — a 6-stage active test plus 1-tap
> `📋 Copy Full Troubleshoot Report (Privacy-Safe)` and `📤 Share Report` with
> **zero phone-model / IP / MAC / EUI-64 / SSID / token / path leaks**.
> PC helpers: `scripts/troubleshoot-adb.sh` and `scripts/troubleshoot-adb.ps1`.
>
> **v3.1.1 අලුත් දේ (ඉතිරි වේ):** AnantPlay එකෙන් එන cast එක **"The TV refused this video" නැතිව**
> ක්‍රියා කරයි (එකම දුරකථනයෙන් එන සම්බන්ධතා දැන් 100% පිළිගනී); app එකක් crash වුවහොත්
> සම්පූර්ණ **crash trace එක app එක තුළම** තැන්පත් වේ (Diagnostics → Last Crash Captured);
> යාවත්කාලීන **සම්පූර්ණයෙන්ම නිහඬයි** (ශබ්දයක් නැත · spam නැත · දුරකථනය මන්දගාමී නොවේ);
> HyperOS **Autostart** අනතුරු ඇඟවීම දැන් **"✓ Done / මම සක්‍රිය කළා"** බොත්තමෙන් ස්ථිරව
> ඉවත් කළ හැක; සහ **Mornye-ආකෘතියේ අලුත් පෙනුම** — තේමා (AMOLED ඇතුළු), වර්ණ 6ක්,
> උපසිරැසි Style Studio (සජීවී preview), ධාවන සැකසුම් සහ Backup & Restore.

> **අලුත්ම ක්‍රමය: Accessibility අවසරයක් අවශ්‍ය නැත.** AnantPlay හි **Cast** බොත්තමෙන් චිත්‍රපටිය
> **මෙම app එකටම** එවනවා — සිංහල උපසිරැසි සහිතව. AnantPlay නිල app එක තවමත් 100% නොවෙනස්ව තිබේ.
>
> **v2.2 අලුත් දේ:** භාෂා **18ක්**, **Spoken/Formal** රටා මාරුව, **දෙවන අදියර Cinema Polish**
> (ස්වයංක්‍රීයව උසස් වේ), සහ අවශ්‍ය නම් පමණක් **✨ AI Studio** බොත්තම.

## 1 · App එක install කරන්න

`AnantPlay-Sinhala-Player-v3.2.0.apk` install කරලා **විවෘත කරන්න** (එය "AnantPlay සිංහල Player" ලෙස පෙනේ;
`versionCode 30200`). v3.1.0 / v3.1.1 එකෙන් **in-place update** වේ — uninstall අවශ්‍ය නැත
(එකම permanent signing key).
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

## 7 · ප්‍රශ්නයක් තිබෙනවා නම් — Privacy-First Deep Troubleshooter (v3.2.0)

### 7.1 · දුරකථනයේදීම — පියවර 6ක Deep Troubleshoot (v3.2.0)

App එකේ **Diagnostics** tab එකට ගිහින් **🩺 Run Deep Troubleshoot** ඔබන්න. එය
**සත්‍ය active පරීක්ෂණ** පියවර 6ක් ධාවනය කරයි (log කියවීමක් පමණක් නොවේ):

| # | පියවර (English) | පරීක්ෂණය |
|---|---|---|
| 1 | Receiver loopback | HTTP `:8765` receiver එක ඇත්තටම ඇහෙනවද? |
| 2 | mDNS (UDP 5353) | `NSD` + අපේම RFC-6762 responder එක announce වී ඇත්ද? |
| 3 | DLNA (UDP 1900) | UPnP `MediaRenderer` එක SSDM search වලට පිළිතුරු දෙනවද? |
| 4 | Worker bridge | KV + PA endpoints සජීවීව පිළිතුරු දෙනවද? |
| 5 | Subtitle pipeline | cast→subs දාමය (provider + HTTP code + cue count) |
| 6 | Player readiness | විවෘත player එකේ state එක + නිශ්චිත error code එක |

සෑම පියවරක්ම timeline එකට එකතු වේ; අවසාන verdict පේළියේ ✓/✗ + ඒ පියවරේ
තත්ත්වය පෙනේ. පසුව **📋 Copy Full Troubleshoot Report (Privacy-Safe)** ඔබන්න —
පූර්ණ වාර්තාව clipboard එකට ලැබේ (`📤 Share Report` එකෙන් කෙළින්ම share කළ හැක).
**Privacy guarantee:** එම වාර්තාවේ **දුරකථන මාදිලියක්, IP එකක්, MAC එකක්, EUI-64
IPv6 එකක්, Wi-Fi SSID එකක්, token එකක් හෝ storage path එකක් නොලියේ** — ඒ සියල්ල
`PrivacyScrubber` එකෙන් `<redacted>` / `<local-v4>` / `<lan-peer>` / `<remote-ip>` /
`<local-v6>` / `<peer-v6>` / `<phone-model>` / `<local-media>` ලෙස මාරු වේ. Crash trace
එකක් තිබේ නම් එහි **EXCEPTION line එක + මුල් app frames** ඉහළින්ම පෙනේ
(පැරණි `Handler.dispatchMessage` අන්තිම පේළිය නොවේ).

### 7.2 · PC එකෙන් — ADB capture scripts (v3.2.0)

දුරකථනය USB debug එකෙන් සම්බන්ධ කර, repo එකේ `scripts/` ඇතුළින්:

```sh
# Linux / macOS — තත්පර 120ක් capture (argument එක නොදුන්නොත් Ctrl-C දක්වා ධාවනය වේ)
sh scripts/troubleshoot-adb.sh 120
# file එකකට දැමීමට:  sh scripts/troubleshoot-adb.sh 120 | tee si-scrubbed.log
```

```powershell
# Windows — PowerShell 5.1 compatible
powershell -ExecutionPolicy Bypass -File scripts\troubleshoot-adb.ps1 -Seconds 120
# file එකකට දැමීමට:  ... | Out-File -Encoding utf8 si-scrubbed.log
```

Script එක logcat එකෙන් **`lk.techworld.anantplay.sicast`** (අපේ app එක) සහ
**`me.champdev.weblock`** (AnantPlay) cast/crash පේළි **පමණක්** ගෙන, app එක තුළ භාවිතා වන
එම privacy rules අනුව filter කර console එකට පෙන්වයි. **දුරකථනයට කිසිම දෙයක් ලියන්නේ
නැත** — read-only capture එකක් පමණි (`adb logcat -T 1` = දැන් සිට පමණක්, පැරණි history නොවේ).

### 7.3 · අනෙකුත් පිළිතුරු

- App එකේ **Diagnostics · Crash log** කොටස බලන්න — සෑම event එකක්ම වාර්තා වී තිබේ;
  **📋 Copy diagnostics log** බොත්තමෙන් copy කරගෙන bug වාර්තාවකට එකතු කරන්න.
- **Test Player / Play Video URL** ඔබද්දී crash වුවහොත්: v3.2.0 එකට update කරන්න
  (මෙය v3.2.0 දී නිවැරදි කළ ප්‍රධානම දෝෂයයි). තවමත් වැටේ නම්
  **🩺 Run Deep Troubleshoot** → **📋 Copy Full Troubleshoot Report** → එය bug වාර්තාවට එකතු කරන්න.
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

## 9 · Obtainium + GitHub Releases — සදාකාලික in-place යාවත්කාලීන (v3.2.0 / v3.x)

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

- **ස්වයංක්‍රීය (merge-to-release, v3.x):** `main` එකට merge වන සෑම Pull Request එකක්ම
  `release-apk.yml` එකෙන් release එකක් බවට පත් වේ — `build.sh` එකෙන් version එක කියවා
  (උදා: `3.2.0`), එම `v3.2.0` tag එක තිබේ නම් skip කරයි (duplicate release නැත),
  නැත්නම් Lab 100% PASS → sign → `apksigner` verify → `SHA256SUMS.txt` සමඟ Release
  එකක් සාදයි.
- **Tag එකකින්:** `git tag v3.2.0 && git push origin v3.2.0` (එම pipeline එකමයි).
- **අතින් 1-click:** GitHub → Actions → **Release APK** → Run workflow → version එක
  ලියන්න (උදා: `3.2.0`).

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
