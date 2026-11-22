#!/bin/sh
# ============================================================================
# create-release-keystore.sh — the ONE-TIME permanent signing keystore maker
# (Linux / macOS edition — the PowerShell twin does exactly the same)
#
# WHY (Sinhala + English):
#   Android යාවත්කාලීන කිරීමේදී අත්සන් කරන key එක වෙනස් නම් install එක පරණ app එක
#   මත fail වේ (INSTALL_FAILED_UPDATE_INCOMPATIBLE) — uninstall කර අලුතෙන්
#   දැමිය යුතුය. CI debug keys සෑම run එකකම වෙනස් නිසා කිසිවිටෙක භාවිතා නොකරන්න.
#   EN: Android refuses in-place updates when the signing key changes. This
#   script makes ONE 10,000-day RSA-2048 keystore LOCALLY, exports the 4 GitHub
#   Secrets; set them once and every release is signed identically forever —
#   Obtainium updates in place, zero uninstalls.
#
# Usage:  sh scripts/create-release-keystore.sh
# Needs:  keytool (any JDK 17+)
# Output: ./release-keystore.jks + ./github-secrets.txt (both git-ignored;
#         BACK THEM UP — lose the key and users must uninstall once)
# ============================================================================
set -e

KEYSTORE="release-keystore.jks"
STOREPASS="anantplay-si-$(LC_ALL=C tr -dc a-z0-9 < /dev/urandom | head -c 12)"
KEYPASS="$STOREPASS"
ALIAS="sicast"
VALIDITY=10000
DNAME="CN=AnantPlay Sinhala Player Release, OU=TechWorld, O=TechWorldLK, L=Colombo, C=LK"

echo "== AnantPlay Sinhala Player — permanent release keystore =="

if [ -f "$KEYSTORE" ]; then
  echo "!! $KEYSTORE already exists — NOT overwriting it."
  echo "   (delete it yourself first if you really want a new key; the old one is"
  echo "    what your users update from)"
  exit 1
fi

KEYTOOL="$(command -v keytool || true)"
if [ -z "$KEYTOOL" ]; then
  for p in "$JAVA_HOME/bin/keytool" /usr/lib/jvm/*/bin/keytool /opt/java/*/bin/keytool; do
    [ -x "$p" ] && KEYTOOL="$p" && break
  done
fi
[ -n "$KEYTOOL" ] || { echo "!! keytool not found — install a JDK 17+ (e.g. Temurin) first"; exit 1; }

echo "-- generating a $VALIDITY-day RSA-2048 keystore (your PERMANENT release key)"
"$KEYTOOL" -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -alias "$ALIAS" \
  -keyalg RSA -keysize 2048 -validity "$VALIDITY" \
  -dname "$DNAME" -storepass "$STOREPASS" -keypass "$KEYPASS"

echo "-- exporting the keystore as base64 (KS_BASE64)"
BASE64="$(base64 -w0 "$KEYSTORE" 2>/dev/null || base64 "$KEYSTORE" | tr -d '\n')"

# copy to the clipboard when possible (best-effort; the file is the source of truth)
if command -v xclip >/dev/null 2>&1; then
  printf '%s' "$BASE64" | xclip -selection clipboard 2>/dev/null \
    && echo "   (KS_BASE64 copied to your clipboard — paste it into GitHub Secrets now)" \
    || echo "   (clipboard unavailable — copy it from github-secrets.txt)"
elif command -v pbcopy >/dev/null 2>&1; then
  printf '%s' "$BASE64" | pbcopy 2>/dev/null \
    && echo "   (KS_BASE64 copied to your clipboard — paste it into GitHub Secrets now)" \
    || echo "   (clipboard unavailable — copy it from github-secrets.txt)"
else
  echo "   (no clipboard tool — copy KS_BASE64 from github-secrets.txt)"
fi

cat > github-secrets.txt <<EOF
================= GitHub Secrets — paste these 4 ONCE =================
Repo: https://github.com/dictationb1/anantplay-sinhala-player
Settings -> Secrets and variables -> Actions -> New repository secret

  Name         Value
  ----         -----
  KS_BASE64    (the long base64 blob at the bottom of this file)
  KS_PASS      $STOREPASS
  KS_KEYPASS   $KEYPASS
  KS_ALIAS     $ALIAS

KS_BASE64 (one line, the WHOLE thing):
$BASE64

========================================================================
 keystore file : $KEYSTORE  (NEVER commit it; .gitignore already blocks *.jks)
 validity      : $VALIDITY days (~27 years)
 what next     : set the 4 secrets above, then publish a release with
                 .github/workflows/release-apk.yml (push a vX.Y.Z tag or run
                 the workflow manually). Every APK is then signed by THIS key,
                 so Obtainium updates in place — no uninstall, ever.
 backup advice: copy $KEYSTORE + these passwords somewhere safe (a password
                 manager + a second disk). Lose the key and users must
                 uninstall/reinstall once.
========================================================================
EOF

echo ""
echo "== DONE =="
echo "Created: $KEYSTORE"
echo "Created: github-secrets.txt (the 4 GitHub Secrets + the base64 blob)"
echo ""
echo " 1. Open github-secrets.txt and set the 4 secrets in the repo (ONE time)."
echo " 2. BACK UP the keystore + passwords safely (password manager + another disk)."
echo " 3. Publish releases via release-apk.yml — same key every time = zero-uninstall updates."
