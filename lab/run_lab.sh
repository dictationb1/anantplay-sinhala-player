#!/bin/sh
# The verification lab: compiles the pure-Java engines (the exact classes the APK
# ships) and drives them like AnantPlay's own DlnaTest — no emulator needed.
#
# GATING vs BEST EFFORT (audit Gate 5):
#   · ReceiverLab  — ALWAYS mandatory: protocol + security + parser regression checks.
#     Any FAIL line, or a non-zero exit, fails this script (and therefore CI).
#   · BridgeLab    — live internet E2E against the deployed worker. Recorded, never
#     gating: offline CI or a Gemini HTTP 429 must not turn the build red.
set -e
cd "$(dirname "$0")/.."
rm -rf lab/out
mkdir -p lab/out/classes

echo "── compile the lab-only android stub (SharedPreferences — never shipped, never dexed)"
javac -encoding UTF-8 -nowarn -d lab/out/classes lab/stubs/android/content/SharedPreferences.java

echo "── compile the pure engines + the lab"
javac -encoding UTF-8 -nowarn -cp lab/out/classes -d lab/out/classes \
  src/lk/techworld/anantplay/sicast/LocalNet.java \
  src/lk/techworld/anantplay/sicast/AnnouncerPolicy.java \
  src/lk/techworld/anantplay/sicast/UpdateNotify.java \
  src/lk/techworld/anantplay/sicast/CrashLog.java \
  src/lk/techworld/anantplay/sicast/AppPrefs.java \
  src/lk/techworld/anantplay/sicast/ThemeEngine.java \
  src/lk/techworld/anantplay/sicast/SettingsBackup.java \
  src/lk/techworld/anantplay/sicast/Langs.java \
  src/lk/techworld/anantplay/sicast/ReceiverServer.java \
  src/lk/techworld/anantplay/sicast/BridgeApi.java \
  src/lk/techworld/anantplay/sicast/Cue.java \
  src/lk/techworld/anantplay/sicast/CastPolicy.java \
  src/lk/techworld/anantplay/sicast/StreamHint.java \
  src/lk/techworld/anantplay/sicast/MiniJson.java \
  src/lk/techworld/anantplay/sicast/SrtLoader.java \
  src/lk/techworld/anantplay/sicast/SubtitleStore.java \
  src/lk/techworld/anantplay/sicast/Diag.java \
  src/lk/techworld/anantplay/sicast/PrivacyScrubber.java \
  src/lk/techworld/anantplay/sicast/Troubleshooter.java \
  src/lk/techworld/anantplay/sicast/MdnsWire.java \
  src/lk/techworld/anantplay/sicast/MdnsResponder.java \
  src/lk/techworld/anantplay/sicast/DlnaEndpoint.java \
  src/lk/techworld/anantplay/sicast/DlnaRenderer.java \
  src/lk/techworld/anantplay/sicast/StreamSniffer.java \
  src/lk/techworld/anantplay/sicast/NetPolicy.java \
  src/lk/techworld/anantplay/sicast/TitleCleaner.java \
  src/lk/techworld/anantplay/sicast/DohResolver.java \
  src/lk/techworld/anantplay/sicast/UpdateChecker.java \
  src/lk/techworld/anantplay/sicast/SelfUpdateCore.java \
  src/lk/techworld/anantplay/sicast/SubLangDetect.java \
  src/lk/techworld/anantplay/sicast/FreeSubTranslator.java \
  src/lk/techworld/anantplay/sicast/FeatureProbe.java \
  src/lk/techworld/anantplay/sicast/AppInfo.java \
  lab/ReceiverLab.java

echo "── run the cast-protocol lab (GATING)"
set +e
java -cp lab/out/classes lk.techworld.anantplay.sicast.ReceiverLab > lab/out/lab.txt 2>&1
LAB_EXIT=$?
set -e
cat lab/out/lab.txt
if [ "$LAB_EXIT" -ne 0 ]; then
  echo "LAB FAILED: ReceiverLab exited $LAB_EXIT" >&2
  exit 1
fi
if grep -q "  FAIL " lab/out/lab.txt; then
  echo "LAB FAILED: at least one FAIL line in lab/out/lab.txt" >&2
  exit 1
fi
PASSED=$(grep -c "  PASS " lab/out/lab.txt || true)
echo "Lab proof: $PASSED checks passed (0 failed)"

# ─────────────────────────────────────────────────────────────────────────────
# v3.1.2 — THE BYTECODE REFERENCE VERIFIER (structure-level crash prevention)
#
# Parses every compiled .class constant pool and proves that EVERY class the
# pure engines reference resolves to (a) another lab class, (b) the JDK, or
# (c) android.* (checked against android.jar at APK build time — the same
# contract build.sh enforces by compiling against android.jar + libs/*.jar).
# A missing Media3 class or a deleted R.string.* is structurally IMPOSSIBLE to
# ship once this gate passes at build time, which is exactly the class of
# NoClassDefFoundError / Resources$NotFoundException the v3.1.1 field crash
# taught us to prevent.
# ─────────────────────────────────────────────────────────────────────────────
python3 lab/verify_refs.py

echo "── live bridge E2E (best effort — never gating)"
if javac -encoding UTF-8 -nowarn -cp lab/out/classes -d lab/out/classes lab/BridgeLab.java; then
  java -cp lab/out/classes lk.techworld.anantplay.sicast.BridgeLab lab/out/bridge.txt \
    > lab/out/bridge-run.txt 2>&1 || echo "(bridge unreachable or quota-limited — recorded as SKIP)"
else
  echo "(BridgeLab compile skipped — recorded as SKIP)"
fi
echo "done: lab/out/"
