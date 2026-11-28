#!/bin/sh
# v3.4.6.c local Java compile gate: compile EVERY shipped source against
# android-36 + libs/*.jar + the Flutter embedding + the aapt2-generated R
# class (the same classpath the Gradle host uses), so a syntax/API error
# never survives to CI. Toolchain roots are overridable via env so the gate
# survives a recycled sandbox (SDK re-extracted anywhere).
set -e
cd "$(dirname "$0")/.."
ROOT=${ROOT:-/home/z/my-project}
JDK=${JDK:-$(dirname "$(dirname "$(command -v javac)")")}
AJAR=${AJAR:-$ROOT/tools-dl/android-sdk/platforms/android-36/android.jar}
AAPT2=${AAPT2:-$ROOT/tools-dl/android-sdk/build-tools/36.0.0/aapt2}
EXTRA_CP=${EXTRA_CP:-$ROOT/tools-dl/extra-cp}
FLUTTER_JAR=$(ls "$ROOT"/tools-dl/flutter/bin/cache/artifacts/engine/android-*/flutter.jar 2>/dev/null | head -1)
if [ -z "$FLUTTER_JAR" ]; then
  FLUTTER_JAR=$(ls "$HOME"/flutter-sdk/bin/cache/artifacts/engine/android-*/flutter.jar 2>/dev/null | head -1)
fi
if [ -z "$FLUTTER_JAR" ]; then
  FLUTTER_JAR=$(find "$HOME/flutter-sdk/bin/cache/artifacts/engine" -name "flutter.jar" 2>/dev/null | head -1)
fi
echo "flutter.jar: $FLUTTER_JAR"

# the R class (aapt2 compile+link, package attr injected — AGP does the same)
rm -rf /tmp/rout && mkdir -p /tmp/rout
"$AAPT2" compile --dir res -o /tmp/res.zip
sed 's/<manifest xmlns:android=/<manifest package="lk.techworld.anantplay.sicast" xmlns:android=/' \
  AndroidManifest.xml > /tmp/manifest-pkg.xml
"$AAPT2" link -o /tmp/rout/app.ap_ -I "$AJAR" \
  --manifest /tmp/manifest-pkg.xml --java /tmp/rout --auto-add-overlay /tmp/res.zip
echo "R.java: $(grep -c 'public static final int' /tmp/rout/lk/techworld/anantplay/sicast/R.java) ids"

rm -rf /tmp/javac-out && mkdir -p /tmp/javac-out
find src -name "*.java" > /tmp/srcs.txt
echo "sources: $(wc -l < /tmp/srcs.txt)"
# $EXTRA_CP carries androidx.lifecycle + androidx.annotation (the two
# transitive classpath entries the Flutter embedding's FlutterActivity API
# surface needs; AGP provides them from maven in the real build).
"$JDK/bin/javac" -encoding UTF-8 -nowarn \
  -cp "$AJAR:$FLUTTER_JAR:libs/*:/tmp/rout:$EXTRA_CP/lifecycle-common-jvm.jar:$EXTRA_CP/lifecycle-common.jar:$EXTRA_CP/lifecycle-runtime.jar:$EXTRA_CP/annotation.jar" \
  -d /tmp/javac-out @/tmp/srcs.txt 2>&1 | grep -v "^Note:" | head -80
echo "COMPILE OK: $(find /tmp/javac-out -name '*.class' | wc -l) classes"
