# v3.4.4 Mornye UI Audit

## English

### Delivered

- Updated the visible app name to **AnantPlay Player** and the app version to **3.4.4** / code **30404**. The existing Cast receiver name remains unchanged.
- Standardized outlined drawable strokes to **1.75–1.85dp**, with round caps and joins. The icon catalog now covers all **66** `ic_*.xml` resources; the launcher badge keeps its intentional dark outline.
- Added named tonal surface tiers and two new accent presets, bringing the palette to **10** choices. Quick Settings now has visual Flat/Glass and Docked/Floating previews that save the existing preferences.
- Reworked the player controls into five fixed, accessible bottom actions: subtitles, tracks/quality, aspect, speed, and title search. Lock, rotate, PiP, external-player, and settings actions remain in the top bar.
- Kept subtitle sync and live style controls in the primary sheet, with provider and AI actions—including free translation—inside a collapsed, accessible “More tools” section.
- Removed duplicate Home guidance and placed the overlay-permission action inside the receiver card. The setup checklist stays hidden while repair guidance is active.
- Added bilingual English/Sinhala audit documentation and version checks. `docs/latest.json` remains the previous signed release until the release workflow can generate the v3.4.4 APK and its real SHA-256.

### Verification

- **1,221/1,221** executable Java regression checks passed.
- **1,915/1,915** static icon, vector-path, manifest, and text-size checks passed.
- **3,136 total checks passed; 0 failed.**
- The existing UI-completeness, layout-parameter, reference, and Java-syntax gates passed. The syntax gate parsed **63** shipped Java sources.
- The text-size audit found no literal UI text below **10sp**.
- The Android SDK and `aapt2` are not available locally, so a packaged APK and device-rendered screenshots were not verified here. The approved hosted run found two missing player-palette symbols; both are now corrected locally, but a follow-up hosted run is still pending approval. No merge or release was performed.

## සිංහල

### කළ වැඩ

- යෙදුමේ පෙනෙන නම **AnantPlay Player** ලෙසත් අනුවාදය **3.4.4** / code **30404** ලෙසත් සකස් කළා. Cast receiver එකේ පවතින නම වෙනස් කළේ නැහැ.
- Outlined අයිකනවල stroke පළල **1.75–1.85dp** අතරට සකස් කර, round cap සහ join තහවුරු කළා. `ic_*.xml` සම්පත් **66ම** අයිකන නාමාවලියට ඇතුළත්. Launcher badge එකේ අඳුරු outline එක අරමුණින් තබා ඇත.
- Tonal surface මට්ටම් නම් කර accent වර්ණ **10ක්** කළා. Quick Settings තුළ Flat/Glass සහ Docked/Floating දෘශ්‍ය පෙරදසුන් ඇති අතර, ඒවා පවතින සැකසුම් සුරකියි.
- Player එකේ පහළ පාලක **5ක්** ස්ථිර හා ප්‍රවේශ විස්තර සහිත කළා: subtitles, tracks/quality, aspect, speed, සහ title search. Lock, rotate, PiP, external player, සහ settings ඉහළ තීරුවේ ඇත.
- Subtitle sync සහ live style controls ප්‍රධාන sheet එකේ තබා, provider සහ AI මෙවලම්—free translation ඇතුළුව—ප්‍රවේශ විස්තර සහිතව වසා ඇති **“More tools”** කොටසට ගෙන ගියා.
- Home තිරයේ නැවත නැවත පෙන්වන උපදෙස් ඉවත් කර overlay permission ක්‍රියාව receiver card එක තුළ තැබුවා. Repair උපදෙස් පෙන්වන විට setup checklist එක සඟවා තබයි.
- ඉංග්‍රීසි/සිංහල විගණන වාර්තාව සහ අනුවාද පරීක්ෂණ එක් කළා. v3.4.4 APK එක සහ සැබෑ SHA-256 අගය release workflow එකෙන් සාදන තුරු `docs/latest.json` පෙර අත්සන් කළ release එකේම තබා ඇත.

### පරීක්ෂණ

- ක්‍රියාත්මක Java regression පරීක්ෂණ **1,221/1,221** සමත්.
- අයිකන, vector path, නාමාවලිය, සහ අකුරු ප්‍රමාණ පරීක්ෂණ **1,915/1,915** සමත්.
- **මුළු පරීක්ෂණ 3,136ක් සමත්; අසමත් 0යි.**
- UI-completeness, layout-parameter, reference, සහ Java syntax පරීක්ෂණ සමත්. Syntax පරීක්ෂණය යෙදුමේ Java source **63ක්** පරීක්ෂා කළා.
- UI අකුරු සඳහා **10spට අඩු** literal ප්‍රමාණයක් හමු නොවීය.
- මෙහි Android SDK සහ `aapt2` නොමැති බැවින් APK එකක් package කිරීම හෝ දුරකථනයක render කළ screenshots පරීක්ෂා කිරීම සිදු කළ නොහැකි විය. අනුමත hosted run එකේ player palette සම්බන්ධ missing symbols දෙකක් හමු විය; ඒවා දැන් local code එකේ නිවැරදි කර ඇති නමුත් නැවත hosted run කිරීම සඳහා අනුමැතිය අවශ්‍යයි. Merge හෝ release සිදු කළේ නැත.
