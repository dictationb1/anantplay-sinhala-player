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
  src/lk/techworld/anantplay/sicast/VideoViewport.java \
  src/lk/techworld/anantplay/sicast/StreamHttpConfig.java \
  src/lk/techworld/anantplay/sicast/LkSubFinder.java \
  src/lk/techworld/anantplay/sicast/WatchHistory.java \
  src/lk/techworld/anantplay/sicast/MiniJson.java \
  src/lk/techworld/anantplay/sicast/SrtLoader.java \
  src/lk/techworld/anantplay/sicast/SubtitleStore.java \
  src/lk/techworld/anantplay/sicast/Diag.java \
  src/lk/techworld/anantplay/sicast/PrivacyScrubber.java \
  src/lk/techworld/anantplay/sicast/Chrome.java \
  src/lk/techworld/anantplay/sicast/BridgeGuard.java \
  src/lk/techworld/anantplay/sicast/OnboardingSpec.java \
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
# v3.3.0 — -Dsun.net.http.allowRestrictedHeaders=true makes the JDK's HttpURLConnection
# send "Origin" like Android's OkHttp-backed one does. Without it the JDK SILENTLY DROPS
# the header, and the 403 lab would be testing a client that is not the one we ship.
java -Dsun.net.http.allowRestrictedHeaders=true -cp lab/out/classes \
  lk.techworld.anantplay.sicast.ReceiverLab > lab/out/lab.txt 2>&1
LAB_EXIT=$?
set -e
if [ "$LAB_EXIT" -ne 0 ]; then
  cat lab/out/lab.txt
  echo "LAB FAILED: ReceiverLab exited $LAB_EXIT" >&2
  exit 1
fi

# v3.4.4 — Audit each drawable/path and each literal app text size. Individual
# assertions are appended to the same lab report so the published count reflects
# both the executable Java regressions and the source/resource checks.
if ! python3 lab/verify_v344_ui.py >> lab/out/lab.txt 2>&1; then
  cat lab/out/lab.txt
  echo "LAB FAILED: v3.4.4 vector/typography audit" >&2
  exit 1
fi

cat lab/out/lab.txt
if grep -q "  FAIL " lab/out/lab.txt; then
  echo "LAB FAILED: at least one FAIL line in lab/out/lab.txt" >&2
  exit 1
fi
PASSED=$(grep -c "  PASS " lab/out/lab.txt || true)
echo "Lab proof: $PASSED executable and static checks passed (0 failed)"

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

# ─────────────────────────────────────────────────────────────────────────────
# v3.3.1 — THE STATIC LAYOUT-PARAMS VERIFIER (structure-level crash prevention)
#
# The 2026-11-23 field crash was a ClassCastException INSIDE the framework's own
# measure pass (FrameLayout.onMeasure:195) — no try/catch of ours can ever catch
# it, so the only real fix is to make the mismatch unwritable. This gate types
# every container/child pair across all 46 Java files in src/ and fails the build
# on any mismatch, on any unprovable call, on a raw-ARGB setBackgroundResource,
# on Uri.fromFile, and on a receiver registered without an export flag.
# Its own 13-case selftest runs first: a verifier that stopped catching bugs
# would be worse than none.
# ─────────────────────────────────────────────────────────────────────────────
python3 lab/verify_layouts.py --selftest
python3 lab/verify_layouts.py

# ─────────────────────────────────────────────────────────────────────────────
# v3.4.1 — THE UI-COMPLETENESS TRUTH GATE (third gating auditor)
#
# The v3.4.0 field report said "symbols are way too huge" and "customizations
# aren't actually working". This gate turns both sentences into arithmetic:
#   R1  every UiIcons.* call with a literal size must be <= 28dp (hero disc
#       32dp, designed empty-state illustration 40dp — both named exceptions);
#   R2  every AppPrefs K_* must be listed in BACKUP_KEYS *and* reachable from a
#       UI surface, directly or through an AppPrefs accessor (zero dead keys);
#   R3  zero emoji inside a control string — the chrome is drawn with
#       res/drawable/ic_*.xml through UiIcons, never with a font glyph.
# ─────────────────────────────────────────────────────────────────────────────
python3 lab/verify_ui_completeness.py

# ─────────────────────────────────────────────────────────────────────────────
# v3.4.1 — THE JAVAC SYNTAX GATE (fourth gating auditor)
#
# run_lab.sh compiles only the PURE engines; the four Activity classes are compiled
# exclusively by build.sh against android.jar, so a stray brace there used to be
# invisible locally and only turned the hosted APK build red. This gate parses all
# 58 shipped sources with javac and keeps only the syntax-class diagnostics (the
# "package android.content does not exist" noise is expected and discarded).
# Exit 77 = no JDK on this machine → SKIP, never a silent pass.
# ─────────────────────────────────────────────────────────────────────────────
set +e
python3 lab/verify_syntax.py
SYNTAX_EXIT=$?
set -e
if [ "$SYNTAX_EXIT" -ne 0 ] && [ "$SYNTAX_EXIT" -ne 77 ]; then
  echo "LAB FAILED: verify_syntax.py exited $SYNTAX_EXIT" >&2
  exit 1
fi

echo "── live bridge E2E (best effort — never gating)"
# v3.3.0 — LAB_SKIP_LIVE=1 skips the network leg entirely (air-gapped runners and
# local verification runs must finish in seconds, not wait on a worker timeout).
if [ -n "${LAB_SKIP_LIVE:-}" ]; then
  echo "(LAB_SKIP_LIVE set — bridge E2E skipped)"
elif javac -encoding UTF-8 -nowarn -cp lab/out/classes -d lab/out/classes lab/BridgeLab.java; then
  java -cp lab/out/classes lk.techworld.anantplay.sicast.BridgeLab lab/out/bridge.txt \
    > lab/out/bridge-run.txt 2>&1 || echo "(bridge unreachable or quota-limited — recorded as SKIP)"
else
  echo "(BridgeLab compile skipped — recorded as SKIP)"
fi
echo "done: lab/out/"
