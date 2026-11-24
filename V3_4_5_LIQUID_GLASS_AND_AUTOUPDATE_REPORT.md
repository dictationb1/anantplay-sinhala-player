# v3.4.5 Mornye Glass and Auto-Update Report

## English

### Research reference

- Reviewed the public Mornye Android project, its `v5.4.0` release (17 November 2026), and recent UI changes in `v5.3.x`.
- Reference: [Mornye source](https://github.com/mornyeapp/Mornye) · [v5.4.0 release](https://github.com/mornyeapp/Mornye/releases/tag/v5.4.0).
- Applied the relevant patterns to this native Java app: restrained glass surfaces, clarity and outline controls, focused settings, and accessible icon medallions. No Mornye code was copied into the project.

### Delivered in the release branch

- Bumped the app to **3.4.5 / versionCode 30405** and updated English/Sinhala release documentation and build/release workflows.
- Made **Mornye Glass** the default card style and the **Floating Glass** bottom navigation default. Added System, Midnight, AMOLED, and Clean Light themes; separate rim and icon-glass switches; and a persisted, live **10–95%** glass-clarity control (75% default).
- Added a bundled Inter variable font with its OFL license, System/Rounded/Monospace choices, Noto Sinhala fallback, and a Cinema Bold subtitle option. App-wide font application leaves subtitle previews intact.
- Reused shared glass treatments across Home, Settings, player controls, browser controls, onboarding, and icon medallions. Interactive icon targets remain at least 44dp and keep accessible descriptions.
- Kept the updater on its existing redirect-and-checksum path. The accepted APK size is strictly below **50 MiB**, downloaded and pending APKs are SHA-256 checked, and build/release workflows enforce the same limit.
- Added a local loopback updater E2E covering `HEAD` redirect → checksum file → APK, progress, valid/tampered hashes, absence of GitHub REST calls, cooldown/backoff, and manual bypass.
- The launcher foreground now has a translucent, rim-lit play badge; the adaptive icon background and monochrome layer remain unchanged.

### Canva asset status

Canva was not connected in this workspace, so no Canva designs were queried or exported. As the approved local fallback, **66 editable SVG icon masters**, a linked SVG contact sheet, and a README were created in `ui_studio/canva_masters/` in the main workspace. These files are deliberately outside the release worktree and will not be included in the app PR.

### Local verification

- **1,251 / 1,251** executable Java regression and updater checks passed.
- **1,930 / 1,930** icon, vector-path, manifest, and typography checks passed.
- **57 / 57** v3.4.5 glass UI, font, launcher, updater, and release-limit checks passed.
- **3,238 total checks passed; 0 failed.**
- UI completeness, layout-parameter, reference, and Java syntax gates passed. The syntax gate parsed **66 Java sources**; the preference audit found **80 / 80** backup keys present and wired to UI.
- No literal app-chrome text size below **10sp** was found.
- Hosted PR run [45301457397](https://github.com/dictationb1/anantplay-sinhala-player/actions/runs/45301457397) passed the Android SDK 36 build, signing, `apksigner`, `zipalign`, and manifest checks. The CI APK is **3,508,510 bytes**, version **3.4.5 / 30405**, SHA-256 `0bf145fc95d732af39689ad04a4a06169cab2fad44b2c169356cee1cda3325ad` (strictly below 50 MiB).
- The local container has no Android SDK, so device-rendered verification was not available. The permanent-key GitHub release and `docs/latest.json` update will be verified after the authorized PR merge.

## සිංහල

### පර්යේෂණ මූලාශ්‍රය

- Mornye Android ව්‍යාපෘතිය, 2026 නොවැම්බර් 17 නිකුත් කළ `v5.4.0` අනුවාදය සහ `v5.3.x` UI වෙනස්කම් සමාලෝචනය කළා.
- මූලාශ්‍ර: [Mornye ව්‍යාපෘතිය](https://github.com/mornyeapp/Mornye) · [v5.4.0 නිකුතුව](https://github.com/mornyeapp/Mornye/releases/tag/v5.4.0).
- Glass මතුපිට, clarity/outline පාලක, සරල සැකසුම් සහ ප්‍රවේශ විස්තර සහිත icon medallions වැනි අදහස් native Java යෙදුමට ගැළපෙන ලෙස භාවිත කළා. Mornye කේතය පිටපත් කළේ නැහැ.

### නිකුතු ශාඛාවේ කළ දේ

- යෙදුම **3.4.5 / versionCode 30405** ලෙස යාවත්කාලීන කර, ඉංග්‍රීසි/සිංහල ලේඛන සහ build/release workflows සකස් කළා.
- **Mornye Glass** card මතුපිටත් **Floating Glass** පහළ navigation එකත් පෙරනිමිය කළා. System, Midnight, AMOLED, Clean Light themes; rim outline සහ icon-glass පාලක; සහ සජීවීව වෙනස් කළ හැකි **10–95%** clarity slider එක (පෙරනිමිය 75%) එක් කළා.
- Inter variable font සහ එහි OFL license එක එක් කර, System/Rounded/Monospace විකල්ප, Noto Sinhala fallback, සහ Cinema Bold subtitle style එකතු කළා. Subtitle preview-වල තෝරාගත් UI font එක අනිසි ලෙස යොදන්නේ නැහැ.
- Home, Settings, player, browser, onboarding සහ icon medallions තුළ එකම glass rendering භාවිත කළා. ක්‍රියාකාරී icon targets අවම වශයෙන් 44dp වන අතර accessibility descriptions තබා ඇත.
- Updater එකේ පවතින redirect සහ checksum ක්‍රමය තබාගෙන, APK සීමාව **50 MiB ට අඩු** කළා. Download කළ සහ install කිරීමට බලා සිටින APK දෙකම SHA-256 මඟින් නැවත පරීක්ෂා කරයි; build/release workflows ද එම සීමාව තහවුරු කරයි.
- `HEAD` redirect → checksum → APK, progress, හරි/විකෘති hash, GitHub REST calls නොමැති බව, cooldown/backoff සහ manual bypass ඇතුළත් local loopback updater E2E පරීක්ෂණ එක් කළා.
- Launcher foreground එකට translucent, rim-lit play badge එකක් එක් කළා. Adaptive icon background සහ monochrome layer වෙනස් කළේ නැහැ.

### Canva ගොනු

මෙම workspace එකේ Canva සම්බන්ධතාවය නොතිබූ නිසා Canva designs විමසීමක් හෝ export කිරීමක් සිදු කළේ නැහැ. අනුමත local fallback එක ලෙස **සංස්කරණය කළ හැකි SVG icon masters 66ක්**, SVG contact sheet එකක් සහ README එකක් main workspace හි `ui_studio/canva_masters/` තුළ සකස් කළා. ඒවා release worktree එකෙන් පිටත තබා ඇති අතර app PR එකට ඇතුළත් නොවේ.

### දේශීය පරීක්ෂණ

- ක්‍රියාත්මක Java regression සහ updater පරීක්ෂණ **1,251 / 1,251** සමත්.
- Icon, vector path, manifest සහ typography පරීක්ෂණ **1,930 / 1,930** සමත්.
- v3.4.5 glass UI, fonts, launcher, updater සහ release සීමා පරීක්ෂණ **57 / 57** සමත්.
- **මුළු පරීක්ෂණ 3,238ක් සමත්; අසමත් 0යි.**
- UI completeness, layout-parameter, reference සහ Java syntax gates සමත්. Syntax gate එක Java source **66ක්** පරීක්ෂා කළා; backup keys **80 / 80ක්** UI වෙත සම්බන්ධව backup ලැයිස්තුවේ ඇත.
- App chrome තුළ literal text size **10spට අඩු** එකක් හමු නොවීය.
- [45301457397 hosted PR run](https://github.com/dictationb1/anantplay-sinhala-player/actions/runs/45301457397) Android SDK 36 build, signing, `apksigner`, `zipalign`, සහ manifest පරීක්ෂණ සමත්. CI APK එක **3,508,510 bytes**, version **3.4.5 / 30405**, SHA-256 `0bf145fc95d732af39689ad04a4a06169cab2fad44b2c169356cee1cda3325ad` — 50 MiB ට අඩුයි.
- Local container එකේ Android SDK නොමැති නිසා device-rendered UI පරීක්ෂා කළේ නැහැ. අනුමත PR merge එකෙන් පසු permanent-key GitHub release එක සහ `docs/latest.json` යාවත්කාලීනය තහවුරු කරමි.
