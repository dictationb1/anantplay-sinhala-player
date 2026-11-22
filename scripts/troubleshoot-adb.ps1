# ─────────────────────────────────────────────────────────────────────────────
# v3.1.2 — PC ADB LIVE CRASH-CAPTURE HELPER (PowerShell, privacy-safe)
#
# Captures ONLY the two relevant package streams on a USB/ADB-connected phone:
#   - lk.techworld.anantplay.sicast          (this app - the Sinhala cast receiver)
#   - me.champdev.weblock                    (official AnantPlay, read-only reference)
# and pipes every line through THE SAME PRIVACY SCRUBBER RULES the app uses
# (zero phone model / IP / MAC / PII in the output - safe to paste anywhere).
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\troubleshoot-adb.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\troubleshoot-adb.ps1 -Seconds 300
#   ... | Out-File si-scrubbed.log      (save the scrubbed stream)
#
# Requires: adb on PATH, USB debugging enabled, phone connected + authorized.
# Nothing is written to the phone; the scrubbed text stays on THIS PC.
# ─────────────────────────────────────────────────────────────────────────────
param(
    [int]$Seconds = 0    # 0 = run until Ctrl+C
)

$ErrorActionPreference = 'Stop'

function Test-Adb {
    try { Get-Command adb -ErrorAction Stop | Out-Null; return $true }
    catch { Write-Error "adb not on PATH - install platform-tools first"; exit 1 }
}

# The same rules as PrivacyScrubber.java (kept in sync by the lab):
function Invoke-PrivacyScrub {
    param([string]$Line)
    $out = $Line
    $out = $out -replace 'fe80:[0-9a-fA-F:]+(%[a-zA-Z0-9]+)?', '<local-v6>'
    $out = $out -replace '([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}', '<redacted>'
    $out = $out -replace '\b(127\.(\d{1,3}\.){2}\d{1,3})\b', '<local-v4>'
    $out = $out -replace '\b(10\.\d{1,3}\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|172\.(1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})\b', '<lan-peer>'
    $out = $out -replace '\b(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})\b', '<remote-ip>'
    $out = $out -replace '(?i)(Xiaomi|Redmi|POCO|Realme|OPPO|vivo|OnePlus|Honor|Huawei|Samsung|Google|Infinix|Tecno|Nokia|Motorola|Sony|Asus|Lenovo|MEIZU|ZTE|Nothing)\s+[A-Za-z0-9._-]+', '<phone-model>'
    $out = $out -replace '(?i)[?&;](token|sig|signature|key|api_key|apikey|auth|password|passwd|pass|secret|session|expires|code)=[^&\s]+', '$1=<redacted>'
    $out = $out -replace '(?i)(Cookie|Authorization|Set-Cookie)[:=]\s*[^,;\s]+', '$1: <redacted>'
    $out = $out -replace 'AIza[0-9A-Za-z_-]{30,}', '<redacted>'
    $out = $out -replace '/storage/emulated/\d+/[^\s]*', '<local-media>'
    $out = $out -replace '/sdcard/[^\s]*', '<local-media>'
    return $out
}

Test-Adb

Write-Output "# AnantPlay Sinhala Player - ADB live capture (privacy-scrubbed)"
Write-Output "# packages: lk.techworld.anantplay.sicast + me.champdev.weblock (cast/crash lines only)"

$pattern = 'lk\.techworld\.anantplay\.sicast|me\.champdev\.weblock|AndroidRuntime.*lk\.techworld|FATAL EXCEPTION'

# stream from NOW (-T 1): only LIVE events, no history re-read
$adbArgs = @('logcat', '-T', '1')
if ($Seconds -gt 0) { $adbArgs += @('-d') }

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = 'adb'
$psi.Arguments = ($adbArgs -join ' ')
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.UseShellExecute = $false

$p = [System.Diagnostics.Process]::Start($psi)
$sw = [System.Diagnostics.Stopwatch]::StartNew()
try {
    while (-not $p.StandardOutput.EndOfStream) {
        $line = $p.StandardOutput.ReadLine()
        if ($null -eq $line) { break }
        if ($line -match $pattern) {
            Write-Output (Invoke-PrivacyScrub -Line $line)
        }
        if ($Seconds -gt 0 -and $sw.Elapsed.TotalSeconds -ge $Seconds) { break }
    }
} finally {
    try { if (-not $p.HasExited) { $p.Kill() } } catch { }
    $p.Dispose()
}
