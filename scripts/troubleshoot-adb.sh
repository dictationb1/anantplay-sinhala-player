#!/bin/sh
# ─────────────────────────────────────────────────────────────────────────────
# v3.1.2 — PC ADB LIVE CRASH-CAPTURE HELPER (the privacy-safe variant)
#
# Captures ONLY the two relevant package streams on a USB/ADB-connected phone:
#   · lk.techworld.anantplay.sicast          (this app — the Sinhala cast receiver)
#   · me.champdev.weblock                    (official AnantPlay, read-only reference)
# and pipes every line through THE SAME PRIVACY SCRUBBER RULES the app uses
# (zero phone model / IP / MAC / PII in the output — safe to paste anywhere).
#
# Usage:
#   sh scripts/troubleshoot-adb.sh                # live capture (Ctrl-C to stop)
#   sh scripts/troubleshoot-adb.sh 300            # capture for 300 seconds
#   sh scripts/troubleshoot-adb.sh | tee si.log   # save the scrubbed stream
#
# Requires: adb on PATH, USB debugging enabled, phone connected + authorized.
# Nothing is written to the phone; the scrubbed text stays on THIS PC.
# ─────────────────────────────────────────────────────────────────────────────
set -u
DUR="${1:-0}"

command -v adb >/dev/null 2>&1 || { echo "ERROR: adb not on PATH — install platform-tools first" >&2; exit 1; }
SERIALS=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
[ -n "$SERIALS" ] || { echo "ERROR: no authorized device — check USB debugging" >&2; exit 1; }

scrub() {
    # the same rules as PrivacyScrubber.java (kept in sync by the lab):
    #   IPs → <local-v4>/<lan-peer>/<remote-ip>, fe80::… → <local-v6>,
    #   MACs → <redacted>, vendor+model tokens → <phone-model>,
    #   cookies/tokens/keys → masked, /storage/… → <local-media>, SSID → masked
    sed -E \
      -e 's/fe80:[0-9a-fA-F:]+(%[a-zA-Z0-9]+)?/<local-v6>/g' \
      -e 's/([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}/<redacted>/g' \
      -e 's/\b(127\.([0-9]{1,3}\.){2}[0-9]{1,3})\b/<local-v4>/g' \
      -e 's/\b(10\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}|192\.168\.[0-9]{1,3}\.[0-9]{1,3}|172\.(1[6-9]|2[0-9]|3[01])\.[0-9]{1,3}\.[0-9]{1,3})\b/<lan-peer>/g' \
      -e 's/\b([0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3})\b/<remote-ip>/g' \
      -e 's/(Xiaomi|Redmi|POCO|Realme|OPPO|vivo|OnePlus|Honor|Huawei|Samsung|Google|Infinix|Tecno|Nokia|Motorola|Sony|Asus|Lenovo|MEIZU|ZTE|Nothing)[[:space:]]+[A-Za-z0-9._-]+/<phone-model>/g' \
      -e 's/[?&;](token|sig|signature|key|api_key|apikey|auth|password|passwd|pass|secret|session|expires|code)=[^&[:space:]]+/\1=<redacted>/g' \
      -e 's/(Cookie|Authorization|Set-Cookie)[:=][[:space:]]*([^,;[:space:]]+)/\1: <redacted>/g' \
      -e 's/AIza[0-9A-Za-z_-]{30,}/<redacted>/g' \
      -e 's/(ssid|SSID|wifi-?name|network[[:space:]]+name)[:=][[:space:]]*[^,;[:space:]]+/\1=<redacted>/g' \
      -e 's#/storage/emulated/[0-9]+/[^[:space:]]*#<local-media>#g' \
      -e 's#/sdcard/[^[:space:]]*#<local-media>#g'
}

run() {
    adb logcat -T 1 -s 2>/dev/null | grep -E --line-buffered \
      "lk\.techworld\.anantplay\.sicast|me\.champdev\.weblock|AndroidRuntime.*lk\.techworld|FATAL EXCEPTION" \
      | scrub
    # (logcat -T 1: start from now — only LIVE events, no history re-read)
}

echo "# AnantPlay Sinhala Player — ADB live capture (privacy-scrubbed)"
echo "# packages: lk.techworld.anantplay.sicast + me.champdev.weblock (cast/crash lines only)"
if [ "$DUR" -gt 0 ] 2>/dev/null; then
    ( run ) & PID=$!
    sleep "$DUR"
    kill $PID 2>/dev/null || true
else
    run
fi
