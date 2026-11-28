# Third-party licenses — AnantPlay Sinhala Player (SI Cast Receiver)

This repository ships **no code from AnantPlay** (`me.champdev.weblock`). It is a
standalone, zero-fork cast receiver: the protocol it speaks (`_anantplay._tcp`, port
`8765`, `/play`, `/status`, `/do`, `/info`) is re-implemented from observed wire
behaviour, and every bundled binary below is an unmodified upstream artifact.

All bundled jars live in `libs/` and are compiled into the APK by `build.sh`
(aapt2 → javac → d8). Versions are pinned to the same set official AnantPlay 2.23.1
ships, so the Media3 playback stack is identical byte-for-byte at the API level.

## Bundled binaries

| Artifact | Version | License | Upstream | Notes |
| --- | --- | --- | --- | --- |
| `libs/media3-*.jar` (common, container, database, datasource, decoder, exoplayer, exoplayer-dash, exoplayer-hls, extractor) | 1.11.1 | **Apache License 2.0** | https://github.com/androidx/media | The ExoPlayer/Media3 playback engine. Unmodified release jars. `NOTICE`: Copyright The Android Open Source Project. |
| `libs/guava-33.3.1-android.jar` | 33.3.1 | **Apache License 2.0** | https://github.com/google/guava | Media3's runtime dependency (flavor `-android`). |
| `libs/failureaccess-1.0.2.jar` | 1.0.2 | **Apache License 2.0** | https://github.com/google/guava | Guava's `ListenableFuture` support. |
| `libs/concurrent-futures-1.2.0.jar` | 1.2.0 | **Apache License 2.0** | https://developer.android.com/jetpack/androidx/releases/concurrent | AndroidX `ListenableFuture`/`CallbackToFutureAdapter`. |
| `libs/annotation-experimental-1.3.1.jar` | 1.3.1 | **Apache License 2.0** | https://developer.android.com/jetpack/androidx/releases/annotation | `@RequiresOptIn` support for Media3's `@UnstableApi`. |
| `assets/fonts/NotoSansSinhala-Regular.ttf` | Google Noto (Sinhala, Regular) | **SIL Open Font License 1.1** | https://github.com/notofonts/sinhala | Bundled so Sinhala cues render with correct conjuncts on every OEM build. OFL allows embedding in an application; the font itself is not sold separately and is redistributed unmodified. |
| `tools/core-lambda-stubs.jar` | OpenJDK `core-lambda-stubs` | **GPL-2.0 with the Classpath Exception** | https://openjdk.org | Build-time only (`javac -bootclasspath`) to compile Java 8 lambdas against `android.jar`. **Not** packaged into the APK. |

In v2.2 the bundled Sinhala font is applied **only** when the active subtitle language is
Sinhala (`si`) — see `Langs.useBundledSinhalaFont()`; Tamil, Devanagari, CJK, Arabic and the
Latin-script languages render with the platform font stack, so the OFL font is never used as a
general-purpose bundled typeface.

`libs/*.jar` are the only binary inputs to the APK; `tools/core-lambda-stubs.jar` is used
only by the compiler and never reaches `dist/AnantPlay-Sinhala-Player.apk` (verify with
`unzip -l dist/AnantPlay-Sinhala-Player.apk`).

## v3.4.6.b — the Mornye v5.4.0 Liquid-Glass Flutter UI (LGPL-3.0-only)

The `ui/` Flutter module vendors the visual engine of **Mornye**
(`mornyeapp/Mornye`, commit `c4a1c7b812eb135d6e1a295b6b25e11abbd72a7e`,
version **5.4.0**, **Copyright 2026 zarzet**) under the
**GNU Lesser General Public License, version 3 only (LGPL-3.0-only)**.

| Item | Detail |
| --- | --- |
| Upstream | https://github.com/mornyeapp/Mornye (read-only reference; **not** a fork of this app) |
| Pinned commit | `c4a1c7b812eb135d6e1a295b6b25e11abbd72a7e` (v5.4.0) |
| Vendored path | `ui/lib/mornye_ui/` |
| Licence texts shipped | `ui/lib/mornye_ui/LICENSE` (LGPL-3.0), `ui/lib/mornye_ui/COPYING` (GPL-3.0 base), `ui/lib/mornye_ui/NOTICE` (SPDX `LGPL-3.0-only`, Copyright 2026 zarzet) — also packaged into the APK under `assets/mornye/` (listed in `ui/pubspec.yaml`) next to Flutter's own licence registry, which the in-app **Settings → Open-source licenses** page (`showLicensePage`) renders |
| What was vendored | `theme/`, `widgets/`, `utils/`, `controllers/`, `providers/`, `models/`, `shims/` (the Liquid-Glass visual engine + its theme tokens and motion curves). v3.4.6.b adds `widgets/mornye_glass_preparation.dart` (verbatim) and the `StaggeredListItem` extract inside `widgets/animation_utils.dart` (verbatim upstream lines 24-112 + the `loadedFadeFirstFrame` constant from `widgets/loaded_sliver_fade_in.dart`), each carrying its own provenance header |
| What was **not** vendored | Mornye's application shell, its music/metadata features, its Rust/FFI backend, its networking and storage layers |

**LGPL-3.0-only compliance.** This application does not modify the vendored engine
files. They are redistributed verbatim, with their `LICENSE`, `COPYING` and `NOTICE`
alongside them, inside the same repository that builds the binary. Because the
engine is vendored **as source** and compiled together with the rest of the
application, the LGPL's source-availability requirement is satisfied by simply
publishing this repository: every line that goes into the APK is here, under
`ui/lib/mornye_ui/`. There is no separate `.so`/`.jar` of Mornye to relink, and no
technique (static linking of a proprietary derivative, obfuscation of the engine,
or a licence ban on reverse engineering for debugging) restricts the user's LGPL
rights in the vendored portion. If you modify `ui/lib/mornye_ui/`, that modified
portion stays LGPL-3.0-only and must remain published.

### Flutter/Dart package pins (all permissive)

| Package | Pinned version | Licence | Upstream |
| --- | --- | --- | --- |
| `flutter` / `flutter_test` (SDK) | Flutter 3.50.1 | **BSD-3-Clause** | https://github.com/flutter/flutter |
| `liquid_glass_easy` | 5.2.0 | **MIT** | https://github.com/AhmeedGamil/liquid_glass_easy |
| `material_3_expressive` | 1.2.1 | **BSD-3-Clause** | https://github.com/paadevelopments/material_3_expressive |
| `material_ui` | 1.11.0 | **BSD-3-Clause** | https://github.com/flutter/packages |
| `cupertino_ui` | 1.1.6 | **BSD-3-Clause** | https://github.com/flutter/packages |
| `material_color_utilities` | 0.13.0 | **Apache-2.0** | https://github.com/material-foundation/material-color-utilities |
| `material_symbols_icons` | 4.2960.0 | **Apache-2.0** (icons: Apache-2.0) | https://github.com/ScerIO/material-symbols-icons |
| `dynamic_color` | 2.1.0 | **BSD-3-Clause** | https://github.com/material-foundation/flutter-packages |
| `flutter_riverpod` | 3.5.0 | **MIT** | https://github.com/rrousselGit/riverpod |
| `flutter_svg` | 2.2.1 | **MIT** | https://github.com/dnfield/flutter_svg |
| `cupertino_icons` | 2.0.0 | **MIT** | https://github.com/flutter/packages |
| `flutter_lints` (dev) | 6.0.0 | **BSD-3-Clause** | https://github.com/flutter/packages |

The exact resolved graph (including every transitive package and its content hash)
is committed in `ui/pubspec.lock`; their licences are auto-collected by Flutter's
`LicenseRegistry` and are browsable in-app through **Settings → Open-source licenses**
(`showLicensePage`).

### Fonts (SIL OFL 1.1)

| Font | File | Licence | Upstream |
| --- | --- | --- | --- |
| **Inter** (variable) | `ui/assets/fonts/InterVariable.ttf` | **SIL Open Font License 1.1** | https://github.com/rsms/inter (`Inter-LICENSE.txt`) |
| **Google Sans Flex** | `ui/assets/fonts/GoogleSansFlex.ttf` | **SIL Open Font License 1.1** | shipped by Mornye `assets/fonts/` (`GoogleSansFlex-LICENSE.txt`) |
| **Noto Sans Sinhala** 400/600 | `ui/assets/fonts/NotoSansSinhala-{Regular,SemiBold}.ttf` | **SIL Open Font License 1.1** | https://github.com/notofonts/sinhala |

All three font families are redistributed unmodified; the OFL permits embedding in
an application, and none is sold separately. `Inter-LICENSE.txt` and
`GoogleSansFlex-LICENSE.txt` ship next to the `.ttf` files.

### Android/Flutter engine runtime

The Flutter engine (`libflutter.so` for `arm64-v8a`, resolved from the pinned
Flutter 3.50.1 SDK at build time) carries the **Flutter engine licence
(BSD-3-Clause + the engine's bundled third-party notices, including Skia/BSD and
Dart/BSD)**; its `NOTICE`/`LICENSE` set is emitted by the Flutter Gradle plugin
into the APK's `META-INF/` and is enumerated by the in-app licence page.

## Apache-2.0 compliance

The Apache 2.0 licenses are satisfied by this notice plus the unmodified binaries:
attribution is given here, no upstream file was modified, and no `NOTICE` file content
was removed from the jars. The full license text is available at
https://www.apache.org/licenses/LICENSE-2.0 and inside each jar's `META-INF/`.

## SIL OFL 1.1 compliance (Noto Sans Sinhala)

The font is redistributed unmodified under the SIL Open Font License 1.1
(https://openfontlicense.org). The reserved font name "Noto" is not used by this
application for any modified version, because the file is not modified. The font is
embedded, not sold on its own.

## This project

`AnantPlay-Sinhala-Player` source is licensed under the **GNU General Public License v3.0 only**
(**`GPL-3.0-only`**) from **v3.4.6.m** (versionCode `30419`) onward — see `LICENSE`, which carries
the verbatim GNU GPL v3.0 text. Releases up to and including **v3.4.6.l** were published under the
**MIT** License and keep that original release history. The GPL covers only this repository's own
code, not the third-party artifacts listed above.

The vendored **Mornye UI** stays **`LGPL-3.0-only`**: `ui/lib/mornye_ui/LICENSE|COPYING|NOTICE`
and `ui/assets/mornye/LICENSE|COPYING|NOTICE` are redistributed **unmodified**, so its weaker
copyleft terms are never narrowed by the repository's GPL-3.0-only upgrade.

| Scope | License |
| --- | --- |
| This project's own code (`v3.4.6.m` onward) | **GPL-3.0-only** |
| Historical releases `<= v3.4.6.l` | MIT (unchanged release history) |
| Vendored Mornye UI | **LGPL-3.0-only** |
| Media3 / Guava (`libs/`) | Apache-2.0 |
| `liquid_glass_easy` | MIT |
| `flutter_riverpod`, `flutter_svg`, `cupertino_icons` | MIT |
| Noto Sans Sinhala / Inter / Google Sans Flex | SIL OFL 1.1 |
