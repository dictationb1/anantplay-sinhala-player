#!/bin/sh
# Builds AnantPlay-Sinhala-Player.apk with plain SDK tools — NO Gradle, the same recipe
# official AnantPlay itself uses (AnantPlay/android build.sh).
#
# Needs: an Android SDK (platform 34 + build-tools 34.x/35.x) and a JDK 17+.
#   ANDROID_HOME=/path/to/sdk ./build.sh
#
# Output: dist/AnantPlay-Sinhala-Player.apk (signed + apksigner-verified, installable).
#
# Signing, in priority order (the build NEVER fails for a missing keystore):
#   1. release keystore from env (CI GitHub Secrets):
#        KS_BASE64 (base64 .keystore/.p12), KS_PASS, KS_KEYPASS (default: KS_PASS), KS_ALIAS
#   2. a stable debug keystore reused across local builds (~/.android/sicast-debug.keystore)
#   3. an ephemeral debug key generated for this run — clearly marked as such.
set -e
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
cd "$(dirname "$0")"

BT=""
for d in "$SDK"/build-tools/*; do
  [ -x "$d/aapt2" ] || continue
  if [ -z "$BT" ] || [ "$(printf '%s\n%s\n' "$BT" "$d" | sort -V | tail -1)" = "$d" ]; then BT=$d; fi
done
JAR=$SDK/platforms/android-34/android.jar
LIBS=$(ls libs/*.jar | tr '\n' ':')
VERSION_NAME=2.1.0
VERSION_CODE=20100
VERSION="--version-code $VERSION_CODE --version-name $VERSION_NAME"

die() { echo "ERROR: $*" >&2; exit 1; }

[ -n "$BT" ] || die "no build-tools with aapt2 under $SDK/build-tools — install build-tools;35.0.1"
[ -f "$JAR" ] || die "$JAR missing. Install with: sdkmanager \"platforms;android-34\" \"build-tools;35.0.1\""
ls libs/media3-exoplayer-*.jar >/dev/null 2>&1 || die "libs/ missing — media3 jars ship with this repo (Apache-2.0)"
[ -d src/lk/techworld/anantplay/sicast ] || die "src/ missing"
command -v javac >/dev/null 2>&1 || die "javac not on PATH (need JDK 17+)"
command -v keytool >/dev/null 2>&1 || die "keytool not on PATH (need JDK 17+)"
command -v zip >/dev/null 2>&1 || die "zip not on PATH (apt-get install zip)"

rm -rf build dist
mkdir -p build/classes dist

echo "── aapt2: compile + link resources"
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/app.apk -I "$JAR" --manifest AndroidManifest.xml build/res.zip \
  -A assets --java build --min-sdk-version 26 --target-sdk-version 34 $VERSION

echo "── javac (Java 8 bytecode target, android.jar + media3 on the classpath)"
LAMBDA=$BT/core-lambda-stubs.jar
[ -f "$LAMBDA" ] || LAMBDA=tools/core-lambda-stubs.jar
find src build/lk -name '*.java' > build/sources.txt
javac -encoding UTF-8 -nowarn -Xlint:-options -source 8 -target 8 \
  -bootclasspath "$JAR:$LAMBDA" -classpath "$LIBS" \
  -d build/classes @build/sources.txt

echo "── d8 → dex (app classes + the media3/guava jars, multidex)"
"$BT/d8" --lib "$JAR" --min-api 26 --output build $(find build/classes -name '*.class') $(ls libs/*.jar)
ls build/classes*.dex >/dev/null 2>&1 || die "d8 produced no dex"

echo "── package"
( cd build && zip -q app.apk classes*.dex )

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

"$BT/apksigner" sign --ks "$KS_FILE" --ks-pass "pass:$KS_STOREPASS" --key-pass "pass:$KS_KEYPASS" \
  --ks-key-alias "$KS_ALIAS" --min-sdk-version 26 \
  --out "dist/AnantPlay-Sinhala-Player.apk" build/aligned.apk

echo "── verify"
"$BT/apksigner" verify --verbose --print-certs "dist/AnantPlay-Sinhala-Player.apk" | head -12
"$BT/apksigner" verify "dist/AnantPlay-Sinhala-Player.apk" || die "apksigner verify FAILED"

echo "── badging"
"$BT/aapt2" dump badging "dist/AnantPlay-Sinhala-Player.apk" | head -6

SIZE=$(wc -c < "dist/AnantPlay-Sinhala-Player.apk")
if command -v sha256sum >/dev/null 2>&1; then
  SHA=$(sha256sum "dist/AnantPlay-Sinhala-Player.apk" | cut -d' ' -f1)
else
  SHA=$(shasum -a 256 "dist/AnantPlay-Sinhala-Player.apk" | cut -d' ' -f1)
fi
echo "── done: dist/AnantPlay-Sinhala-Player.apk"
echo "   signed with : $SIGN_MODE"
echo "   size        : $SIZE bytes ($((SIZE / 1024)) KiB)"
echo "   sha256      : $SHA"
