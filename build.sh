#!/bin/sh
# Builds AnantPlay-Sinhala-Player.apk with plain SDK tools — NO Gradle, the same recipe
# official AnantPlay itself uses (AnantPlay/android build.sh).
#
# Needs: an Android SDK (platform 36 + build-tools 35.0.1) and a JDK 17+.
#   ANDROID_HOME=/path/to/sdk ./build.sh
#
# Output: dist/AnantPlay-Sinhala-Player-v$VERSION_NAME.apk (signed + apksigner-verified,
#         installable) and dist/AnantPlay-Sinhala-Player.apk (same bytes, stable name).
#
# Signing, in priority order (the build NEVER fails for a missing keystore):
#   1. release keystore from env (CI GitHub Secrets):
#        KS_BASE64 (base64 .keystore/.p12), KS_PASS, KS_KEYPASS (default: KS_PASS), KS_ALIAS
#      → the PERMANENT key: every release signed identically = Obtainium in-place
#        updates work forever (INSTALL_FAILED_UPDATE_INCOMPATIBLE can never happen).
#   2. a stable debug keystore reused across local builds (~/.android/sicast-debug.keystore)
#   3. an ephemeral debug key generated for this run — clearly marked as such.
#
# v2.3.0 hardening (the release pipeline):
#   · VERSION_NAME/VERSION_CODE may be overridden by env for release runs, but the
#     aapt2 badging of the OUTPUT is ALWAYS verified to carry exactly these values
#     (an accidental stale version can never ship).
#   · A strictly-increasing versionCode is enforced against PREV_VERSION_CODE
#     (default 20200 = v2.2.0) so an app-store/Obtainium downgrade is impossible.
#   · When no release keystore is configured, the CI summary gets a loud
#     "DEBUG-SIGNED" warning instead of a silently broken update path.
set -e
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
cd "$(dirname "$0")"

BT=""
# Deterministic first choice: the exact toolchain this release is audited against.
# (Keeps CI reproducible even when the runner image also exposes a newer or an
# older build-tools directory.)
[ -x "$SDK/build-tools/35.0.1/aapt2" ] && BT="$SDK/build-tools/35.0.1"
#…else fall back to the highest build-tools that is new enough for d8 8.6.
[ -n "$BT" ] || for d in "$SDK"/build-tools/*; do
  [ -x "$d/aapt2" ] || continue
  # d8 8.2 (build-tools 34) crashes on jars carrying unnamed MethodParameters
  # ("Cannot invoke String.length() because <parameter1> is null") — 35.0.1+ (D8 8.6) is fine.
  case "$d" in
    */34.0.*|*/3[0-3].*) continue ;;   # too old — d8 8.2 bug (see comment above)
  esac
  if [ -z "$BT" ] || [ "$(printf '%s\n%s\n' "$BT" "$d" | sort -V | tail -1)" = "$d" ]; then BT=$d; fi
done
# v3.4.6 — a TRUE Android 16 (SDK 36) build: compiles + links against
# platforms/android-36 ONLY (no silent fallback: the release always ships
# targetSdkVersion 36, and edge-to-edge inset handling depends on it).
JAR=""
for p in android-36; do
  [ -f "$SDK/platforms/$p/android.jar" ] && { JAR="$SDK/platforms/$p/android.jar"; break; }
done
# (v3.4.6: no silent platform fallback — the release always compiles against android-36)
TARGET_SDK=36
LIBS=$(ls libs/*.jar | tr '\n' ':')
VERSION_NAME=${VERSION_NAME:-3.4.6.i}
VERSION_CODE=${VERSION_CODE:-30415}
PREV_VERSION_CODE=${PREV_VERSION_CODE:-30413}   # v3.4.6.g — the last released build
VERSION="--version-code $VERSION_CODE --version-name $VERSION_NAME"

die() { echo "ERROR: $*" >&2; exit 1; }

[ -n "$BT" ] || die "no build-tools >= 35.0.1 under $SDK/build-tools — d8 8.2 (34.x) crashes on the shipped jars; install build-tools;35.0.1"
[ -f "$JAR" ] || die "$JAR missing. Install with: sdkmanager \"platforms;android-36\" \"build-tools;35.0.1\""
ls libs/media3-exoplayer-*.jar >/dev/null 2>&1 || die "libs/ missing — media3 jars ship with this repo (Apache-2.0)"
[ -d src/lk/techworld/anantplay/sicast ] || die "src/ missing"
command -v javac >/dev/null 2>&1 || die "javac not on PATH (need JDK 17+)"
command -v keytool >/dev/null 2>&1 || die "keytool not on PATH (need JDK 17+)"
command -v zip >/dev/null 2>&1 || die "zip not on PATH (apt-get install zip)"

# the version guard: strictly increasing versionCode (Obtainium/the OS refuse downgrades)
case "$VERSION_CODE" in
  ''|*[!0-9]*) die "VERSION_CODE must be a number (got '$VERSION_CODE')" ;;
esac
[ "$VERSION_CODE" -gt "$PREV_VERSION_CODE" ] \
  || die "versionCode $VERSION_CODE is NOT greater than the previous release ($PREV_VERSION_CODE) — bump it (e.g. v2.4.0 → 20400)"
echo "── version: $VERSION_NAME (versionCode $VERSION_CODE, previous $PREV_VERSION_CODE)"

rm -rf build dist
mkdir -p build/classes dist

echo "── aapt2: compile + link resources"
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/app.apk -I "$JAR" --manifest AndroidManifest.xml build/res.zip \
  -A assets --java build --min-sdk-version 26 --target-sdk-version $TARGET_SDK $VERSION

echo "── javac (Java 8 bytecode target, android.jar + media3 on the classpath)"
LAMBDA=$BT/core-lambda-stubs.jar
[ -f "$LAMBDA" ] || LAMBDA=tools/core-lambda-stubs.jar
find src build/lk -name '*.java' > build/sources.txt
javac -encoding UTF-8 -nowarn -Xlint:-options -source 8 -target 8 \
  -bootclasspath "$JAR:$LAMBDA" -classpath "$LIBS" \
  -d build/classes @build/sources.txt

echo "── d8 → dex (app classes + the media3/guava jars, multidex)"
# v3.3.0 — a bounded JVM heap for d8: on a 2 GB container (and every 4 GB CI runner)
# d8's default -Xmx2g fails with "Could not reserve enough space for 2097152KB object
# heap" BEFORE it reads a single class. -J-Xmx is the only way to pass a heap to the
# d8 launcher (it ignores JAVA_OPTS); D8_XMX overrides the default.
"$BT/d8" -JXmx"${D8_XMX:-896m}" --lib "$JAR" --min-api 26 --output build $(find build/classes -name '*.class') $(ls libs/*.jar)
ls build/classes*.dex >/dev/null 2>&1 || die "d8 produced no dex"

echo "── package"
# v3.1.1 — REPRODUCIBLE ZIP ENTRY TIMESTAMPS. `zip` stores each entry's mtime
# (build wall-clock), so two runs of the SAME commit produced two different
# APK SHA-256s. Every entry is pinned to a fixed epoch and TZ=UTC, so the same
# signed bytes always hash the same — a release can be re-verified forever.
TZ=UTC; export TZ
touch -t 198001010000 build/*.dex 2>/dev/null || true
( cd build && TZ=UTC zip -q -X app.apk classes*.dex )

echo "── zipalign"
"$BT/zipalign" -f 4 build/app.apk build/aligned.apk
"$BT/zipalign" -c 4 build/aligned.apk >/dev/null || die "zipalign check failed"

echo "── sign"
SIGN_MODE=""
if [ -n "${KS_BASE64:-}" ]; then
  echo "   signing with the RELEASE keystore from CI secrets (KS_ALIAS=${KS_ALIAS:-sicast})"
  printf '%s' "$KS_BASE64" | base64 -d > build/release.keystore 2>/dev/null \
    || printf '%s' "$KS_BASE64" | base64 -D > build/release.keystore
  [ -s build/release.keystore ] || die "KS_BASE64 did not decode to a keystore"
  KS_FILE=build/release.keystore
  KS_STOREPASS=${KS_PASS:?KS_PASS must be set with KS_BASE64}
  KS_KEYPASS=${KS_KEYPASS:-$KS_PASS}
  KS_ALIAS=${KS_ALIAS:-sicast}
  SIGN_MODE="release keystore (${KS_ALIAS})"
else
  DBG=${DEBUG_KS:-$HOME/.android/sicast-debug.keystore}
  mkdir -p "$(dirname "$DBG")" 2>/dev/null || true
  if [ ! -f "$DBG" ]; then
    echo "   no release keystore configured — generating a DEBUG key at $DBG"
    echo "   NOTE: debug-signed builds are for testing only and cannot be updated in place"
    echo "         across CI runs (each runner starts empty). Add KS_BASE64/KS_PASS secrets"
    echo "         to sign releases reproducibly."
    keytool -genkeypair -keystore "$DBG" -storetype PKCS12 -alias sicast \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=AnantPlay Sinhala Player (debug)" -storepass android -keypass android >/dev/null 2>&1 \
      || die "keytool could not create the debug keystore"
  else
    echo "   no release keystore configured — reusing the DEBUG key at $DBG"
  fi
  KS_FILE=$DBG
  KS_STOREPASS=android
  KS_KEYPASS=android
  KS_ALIAS=sicast
  SIGN_MODE="DEBUG keystore (not for release) — $DBG"
fi

# v3.3.1 — a bounded apksigner heap. apksigner defaults to -Xmx1024M; on a 2 GB
# container (and any small runner) that reservation collides with the metaspace
# reservation and dies with "Failed to reserve memory for metaspace" AFTER a
# successful compile/d8/package — a confusing late failure for a 3 MB APK.
# -JXmx640m is plenty for signing/verifying and changes nothing on big runners.
"$BT/apksigner" -JXmx640m sign --ks "$KS_FILE" --ks-pass "pass:$KS_STOREPASS" --key-pass "pass:$KS_KEYPASS" \
  --ks-key-alias "$KS_ALIAS" --min-sdk-version 26 \
  --out "dist/AnantPlay-Sinhala-Player.apk" build/aligned.apk

echo "── verify"
"$BT/apksigner" -JXmx640m verify --verbose --print-certs "dist/AnantPlay-Sinhala-Player.apk" | head -12
"$BT/apksigner" -JXmx640m verify "dist/AnantPlay-Sinhala-Player.apk" || die "apksigner verify FAILED"

echo "── badging (the shipped version is checked, not assumed)"
BADGE=$("$BT/aapt2" dump badging "dist/AnantPlay-Sinhala-Player.apk" | head -1)
echo "   $BADGE"
echo "$BADGE" | grep -q "versionCode='$VERSION_CODE'" || die "badging versionCode mismatch — expected $VERSION_CODE"
echo "$BADGE" | grep -q "versionName='$VERSION_NAME'" || die "badging versionName mismatch — expected $VERSION_NAME"

# the versioned artifact name (Obtainium wants exactly ONE unambiguous .apk per release)
cp "dist/AnantPlay-Sinhala-Player.apk" "dist/AnantPlay-Sinhala-Player-v$VERSION_NAME.apk"

SIZE=$(wc -c < "dist/AnantPlay-Sinhala-Player-v$VERSION_NAME.apk")
[ "$SIZE" -lt 52428800 ] || die "APK size must be strictly below 50 MiB (got $SIZE bytes)"
if command -v sha256sum >/dev/null 2>&1; then
  SHA=$(sha256sum "dist/AnantPlay-Sinhala-Player-v$VERSION_NAME.apk" | cut -d' ' -f1)
else
  SHA=$(shasum -a 256 "dist/AnantPlay-Sinhala-Player-v$VERSION_NAME.apk" | cut -d' ' -f1)
fi
echo "── done: dist/AnantPlay-Sinhala-Player-v$VERSION_NAME.apk"
echo "   signed with : $SIGN_MODE"
echo "   size        : $SIZE bytes ($((SIZE / 1024)) KiB)"
echo "   sha256      : $SHA"
# the CI-summary warning the audit demanded: never let a debug-signed build pass silently
if [ -z "${KS_BASE64:-}" ] && [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "### ⚠️ DEBUG-SIGNED — set KS_BASE64 for permanent Obtainium in-place updates"
    echo ""
    echo "This build was signed with an ephemeral/local DEBUG key. Installing it over a"
    echo "release-signed build will fail with INSTALL_FAILED_UPDATE_INCOMPATIBLE."
    echo "Run \`scripts/create-release-keystore.sh\` (or .ps1) once, set the 4 secrets"
    echo "(KS_BASE64, KS_PASS, KS_KEYPASS, KS_ALIAS), then re-run — every future release"
    echo "then updates in place forever."
  } >> "$GITHUB_STEP_SUMMARY"
fi
