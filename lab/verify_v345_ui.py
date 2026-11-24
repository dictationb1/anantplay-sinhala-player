#!/usr/bin/env python3
"""Focused source/resource gates for the v3.4.5 glass UI and updater release."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src/lk/techworld/anantplay/sicast"
RES = ROOT / "res"
checks = 0
failures = 0


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def check(name, condition):
    global checks, failures
    checks += 1
    if condition:
        print(f"  PASS v345 UI: {name}")
    else:
        failures += 1
        print(f"  FAIL v345 UI: {name}")


prefs = read("src/lk/techworld/anantplay/sicast/AppPrefs.java")
main = read("src/lk/techworld/anantplay/sicast/MainActivity.java")
glass = read("src/lk/techworld/anantplay/sicast/GlassSurface.java")
icon = read("src/lk/techworld/anantplay/sicast/UiGlassIcon.java")
fonts = read("src/lk/techworld/anantplay/sicast/UiFonts.java")
tokens = read("src/lk/techworld/anantplay/sicast/UiTokens.java")
card = read("src/lk/techworld/anantplay/sicast/UiCard.java")
player = read("src/lk/techworld/anantplay/sicast/PlayActivity.java")
browser = read("src/lk/techworld/anantplay/sicast/WebBrowserView.java")
onboarding = read("src/lk/techworld/anantplay/sicast/OnboardingWizard.java")
update_core = read("src/lk/techworld/anantplay/sicast/SelfUpdateCore.java")
updater = read("src/lk/techworld/anantplay/sicast/SelfUpdater.java")
update_lab = read("lab/ReceiverLab.java")
build = read("build.sh")
build_workflow = read(".github/workflows/build-apk.yml")
release_workflow = read(".github/workflows/release-apk.yml")

# Backed-up appearance preferences and bounded values.
appearance_keys = [
    "K_GLASS_CLARITY", "K_GLASS_OUTLINES", "K_ICON_GLASS", "K_UI_FONT",
]
for key in appearance_keys:
    check(f"{key} is declared and included in settings backup",
          f"String {key}" in prefs and key in prefs[prefs.index("BACKUP_KEYS"):])
check("glass clarity is bounded to 10–95 percent",
      "normalizeGlassClarity(int value) { return clampTo(value, 10, 95); }" in prefs)
check("glass clarity defaults to 75 percent",
      "normalizeGlassClarity(p.getInt(K_GLASS_CLARITY, 75))" in prefs)
check("glass outline and icon-glass controls persist",
      "glassOutlines(SharedPreferences p)" in prefs and "iconGlass(SharedPreferences p)" in prefs)
check("UI font preference normalizes unknown values",
      "normalizeUiFont" in prefs and "K_UI_FONT" in prefs)
check("Mornye Glass is the default surface style",
      "SURFACE_GLASS" in prefs and "SURFACE_GLASS" in prefs[prefs.index("surfaceStyle("):])
check("floating glass is the default bottom-bar style",
      "BAR_FLOATING" in prefs
      and "normalizeBottomBarStyle(p.getString(K_BOTTOM_BAR_STYLE, BAR_FLOATING))" in prefs)

# Themes, glass controls, and saved quick settings.
for label in ("System", "Midnight", "AMOLED", "Clean Light"):
    check(f"theme chooser includes {label}", label in main)
check("quick settings expose the glass clarity slider",
      '"Glass clarity", 10, 95' in main and "K_GLASS_CLARITY" in main)
check("clarity changes refresh glass surfaces immediately",
      "GlassSurface.refreshAll" in main and "glassClarity(prefs)" in main)
check("glass outline toggle is present in settings",
      "Glass outlines" in main and "K_GLASS_OUTLINES" in main)
check("icon medallion toggle is present in settings",
      "Glass icon medallions" in main and "K_ICON_GLASS" in main)
check("surface style chooser names Mornye Glass",
      'previewChoiceRow("UI style", new String[]{"Flat Material", "Mornye Glass"}' in main)
check("bottom navigation chooser includes Floating Glass",
      'previewChoiceRow("Bottom bar", new String[]{"Docked", "Floating Glass"}' in main)

# App-wide typography, including Latin and Sinhala fallback.
check("Inter variable font is bundled",
      (ROOT / "assets/fonts/Inter[opsz,wght].ttf").is_file())
check("Inter license is shipped with its font asset",
      (ROOT / "assets/fonts/Inter-OFL.txt").is_file())
check("modern UI font uses the bundled Inter face",
      "fonts/Inter[opsz,wght].ttf" in fonts)
check("Sinhala uses a Noto fallback with the modern Latin font",
      "UiTokens.si(text.getContext()" in fonts and "NotoSansSinhala-Regular.ttf" in tokens)
check("UI font choices include System, Rounded and Monospace",
      all(v in main for v in ("System", "Rounded", "Monospace")))
check("subtitle preview is excluded from app-font replacement",
      "markSubtitle(TextView view)" in fonts and "UiFonts.markSubtitle(subView)" in player)
check("Cinema Bold remains an available subtitle style",
      "FONT_CINEMA" in prefs and "Cinema Bold" in main and "FONT_CINEMA" in player)
check("main screen applies the selected UI font",
      "UiFonts.applyTree" in main)
check("player applies the selected UI font",
      "UiFonts.applyTree" in player)
check("browser applies the selected UI font",
      "UiFonts.applyTree" in browser)
check("onboarding applies the selected UI font",
      "UiFonts.applyTree" in onboarding)

# Glass surfaces and accessible icon controls.
check("glass cards use shared surface rendering",
      "GlassSurface.apply(box, prefs" in card)
check("flat cards have no outline",
      "else g.setStroke(0, 0)" in card)
check("glass cards use a clarity-aware hairline",
      "ThemeEngine.hairline(p, AppPrefs.borderAlpha(prefs))" in card)
check("glass surfaces provide separate solid and glass modes",
      "AppPrefs.glass(prefs)" in glass and "setStroke" in glass)
check("glass surfaces refresh live when clarity changes",
      "refreshAll" in glass and "WeakHashMap" in glass)
check("decorative blur is limited to supported Android versions",
      "Build.VERSION.SDK_INT < 31" in glass and "RenderEffect.createBlurEffect" in glass)
check("glass effect documents that Android blur is not backdrop sampling",
      "not a backdrop" in glass)
check("icon controls use accessible descriptions",
      "setContentDescription(description)" in icon)
check("interactive icon targets are at least 44dp",
      "listener == null ? 32 : 44" in icon)
check("icon glyphs are centered at their requested size",
      "Gravity.CENTER" in icon and "glyphDp" in icon)
check("icon medallions honor the user's motion setting",
      "AppPrefs.motionEnabled(prefs)" in icon)
check("main navigation uses glass medallions",
      "UiGlassIcon.create" in main)
check("player controls use glass medallions",
      "UiGlassIcon.create" in player)
check("browser controls use glass medallions",
      "UiGlassIcon.create" in browser)

# Versioned updater protections, local E2E, and release size gates.
check("updater cap is strictly below 50 MiB",
      "50L * 1024 * 1024 - 1" in update_core)
check("updater rechecks SHA-256 before installation",
      "matchesSha256(apk, expected)" in updater and "install(c, apk)" in updater)
check("loopback updater E2E exercises the redirected release flow",
      "v345UpdaterE2eChecks" in update_lab and "HEAD /releases/latest" in update_lab)
check("loopback E2E verifies tampered APK rejection",
      "tampered APK is rejected" in update_lab)
check("loopback E2E proves no GitHub REST calls are made",
      "zero GitHub REST calls" in update_lab)
check("build script rejects an APK at or above 50 MiB",
      'SIZE" -lt 52428800' in build)
check("build workflow rejects an APK at or above 50 MiB",
      'SIZE" -lt 52428800' in build_workflow)
check("release workflow rejects an APK at or above 50 MiB",
      'SIZE" -lt 52428800' in release_workflow)
check("build workflow validates version 3.4.5 / 30405",
      'test "$VERSION" = "3.4.5"' in build_workflow
      and 'test "$CODE" = "30405"' in build_workflow)
check("release workflow defaults to version 3.4.5",
      "default: '3.4.5'" in release_workflow)

# Adaptive launcher icon remains a valid layered vector resource.
try:
    foreground = ET.parse(RES / "drawable/ic_launcher_foreground.xml").getroot()
    adaptive = ET.parse(RES / "mipmap-anydpi-v26/ic_launcher.xml").getroot()
    check("launcher foreground remains a parseable vector", foreground.tag.endswith("vector"))
    check("adaptive launcher keeps a foreground and background layer",
          adaptive.tag.endswith("adaptive-icon")
          and any(child.tag.endswith("background") for child in adaptive)
          and any(child.tag.endswith("foreground") for child in adaptive))
except (OSError, ET.ParseError):
    check("launcher adaptive resources parse", False)

print(f"v3.4.5 UI audit: {checks - failures} passed, {failures} failed")
raise SystemExit(1 if failures else 0)
