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

`AnantPlay-Sinhala-Player` source is licensed under the MIT License — see `LICENSE`.
That license covers only this repository's own code, not the third-party artifacts
listed above.
