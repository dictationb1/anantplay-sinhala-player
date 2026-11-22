#!/usr/bin/env pwsh
# ============================================================================
# create-release-keystore.ps1 — the ONE-TIME permanent signing keystore maker
# (Windows PowerShell edition)
#
# WHY THIS EXISTS (Sinhala + English):
#   Android යාවත්කාලීන කිරීමේදී අත්සන් කරන key එක වෙනස් නම්, පරණ ඇප් එක අයින් කර
#   අලුතෙන් install කරන්නට සිදු වේ (INSTALL_FAILED_UPDATE_INCOMPATIBLE). GitHub CI
#   එකේ එක එක run වලට අලුත් debug key එකක් හැදෙන නිසා එය කිසිසේත් වැඩ කරන්නේ නැත.
#   මේ ස්ක්‍රිප්ට් එකෙන් ඔබේ PC එකේම (GitHub එකට නොදමා) 10,000-දින RSA-2048 keystore
#   එකක් හැදී, එය GitHub Secrets 4කට පැහැදිලිව පරිවර්තනය වේ. එක වරක් එය set කළ
#   පසු සෑම release එකක්ම සදාකාලිකව එම අත්සනෙන්ම වේ — Obtainium එකෙන් අයින් කරන්නේ
#   නැතුව in-place යාවත්කාලීන වේ.
#   EN: Android refuses in-place updates when the signing key changes
#   (INSTALL_FAILED_UPDATE_INCOMPATIBLE). Ephemeral CI debug keys guarantee that
#   failure. This script creates ONE 10,000-day RSA-2048 keystore ON YOUR PC,
#   exports the 4 GitHub Secrets, and after you set them once, every release is
#   signed with the same permanent key — Obtainium updates in place, forever.
#
# Usage:  pwsh -File scripts\create-release-keystore.ps1
# Output: .\release-keystore.jks  +  .\github-secrets.txt  (both git-ignored;
#         keep a backup of the .jks + passwords somewhere safe!)
# ============================================================================

$ErrorActionPreference = "Stop"

$Keystore = "release-keystore.jks"
$StorePass = "anantplay-si-" + ([System.Guid]::NewGuid().ToString("N").Substring(0, 12))
$KeyPass   = $StorePass          # one password keeps it simple; split if you prefer
$Alias     = "sicast"
$Validity  = 10000               # days (~27 years) — outlives the app
$Dname     = "CN=AnantPlay Sinhala Player Release, OU=TechWorld, O=TechWorldLK, L=Colombo, C=LK"

Write-Host "== AnantPlay Sinhala Player — permanent release keystore ==" -ForegroundColor Cyan

if (Test-Path $Keystore) {
    Write-Host "!! $Keystore already exists — NOT overwriting it." -ForegroundColor Yellow
    Write-Host "   (delete it yourself first if you really want a new key; the old one is what your users update from)"
    exit 1
}

# keytool ships with any JDK; try keytool on PATH, then common Java locations
$Keytool = Get-Command keytool -ErrorAction SilentlyContinue
if (-not $Keytool) {
    foreach ($p in @("$env:JAVA_HOME\bin\keytool.exe",
                     "C:\Program Files\Eclipse Adoptium\jdk-17*\bin\keytool.exe",
                     "C:\Program Files\Java\jdk-17*\bin\keytool.exe",
                     "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe")) {
        $found = Get-Item $p -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) {
            # PS 5.1 + 7 safe: normalize to the plain full path string
            $Keytool = $found.FullName
            break
        }
    }
}
if (-not $Keytool) {
    Write-Host "!! keytool not found — install any JDK 17 (e.g. Temurin) or Android Studio first." -ForegroundColor Red
    exit 1
}

Write-Host "-- generating $Validity-day RSA-2048 keystore (this is your PERMANENT release key)"
& $Keytool -genkeypair -keystore $Keystore -storetype PKCS12 -alias $Alias `
    -keyalg RSA -keysize 2048 -validity $Validity `
    -dname $Dname -storepass $StorePass -keypass $KeyPass
if ($LASTEXITCODE -ne 0) { Write-Host "!! keytool failed" -ForegroundColor Red; exit 1 }

Write-Host "-- reading the keystore back as base64 (KS_BASE64)"
$Bytes  = [System.IO.File]::ReadAllBytes((Resolve-Path $Keystore).ProviderPath)
$Base64 = [System.Convert]::ToBase64String($Bytes)

# export KS_BASE64 to the clipboard when possible (nice-to-have, not required)
try {
    Set-Clipboard -Value $Base64
    Write-Host "   (KS_BASE64 copied to your clipboard — paste it into GitHub Secrets now)" -ForegroundColor Green
} catch {
    Write-Host "   (clipboard unavailable — copy it from github-secrets.txt instead)"
}

$Secrets = @"
================= GitHub Secrets — paste these 4 ONCE =================
Repo: https://github.com/dictationb1/anantplay-sinhala-player
Settings -> Secrets and variables -> Actions -> New repository secret

  Name         Value
  ----         -----
  KS_BASE64    (the long base64 blob at the bottom of this file)
  KS_PASS      $StorePass
  KS_KEYPASS   $KeyPass
  KS_ALIAS     $Alias

KS_BASE64 (one line, the WHOLE thing):
$Base64

========================================================================
 keystore file : $Keystore  (NEVER commit it; .gitignore already blocks *.jks)
 validity      : $Validity days (~27 years)
 what next     : set the 4 secrets above, then publish a release with
                 .github/workflows/release-apk.yml (push a vX.Y.Z tag or run
                 the workflow manually). Every APK is then signed by THIS key,
                 so Obtainium updates in place — no uninstall, ever.
 backup advice: copy $Keystore + these passwords to a safe place (a password
                 manager + a second disk). Lose the key and users must
                 uninstall/reinstall once.
========================================================================
"@
[System.IO.File]::WriteAllText("github-secrets.txt", $Secrets,
    (New-Object System.Text.UTF8Encoding $false))

Write-Host ""
Write-Host "== DONE ==" -ForegroundColor Green
Write-Host "Created: $Keystore"
Write-Host "Created: github-secrets.txt (the 4 GitHub Secrets + the base64 blob)"
Write-Host ""
Write-Host " 1. Open github-secrets.txt and set the 4 secrets in the repo (ONE time)." -ForegroundColor White
Write-Host " 2. BACK UP the .jks + passwords safely (password manager + another disk)." -ForegroundColor White
Write-Host " 3. Publish releases via release-apk.yml — same key every time = zero-uninstall updates." -ForegroundColor White
