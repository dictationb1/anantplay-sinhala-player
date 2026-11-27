#!/usr/bin/env python3
"""v3.4.6.d — THE GRADLE-HOST STRUCTURAL GATE (gating auditor #6).

The Option-A host (android/ + the Flutter module ui/) is the ONLY packaging path
now, so a structural break there is a release blocker that no runtime test can
see. This gate reads the host the way Gradle will, and fails on the exact defects
the shipped delta had (all four were hard build failures, not warnings):

  G1  NO MACHINE-LOCAL FILES.  android/local.properties must not exist and must
      never be tracked (it carries a sandbox path such as /tmp/flutter).
  G2  THE BUILD SCRIPT IS GROOVY, NOT A SHELL SCRIPT.  A leading "#" comment in a
      .gradle file is a syntax error; a "#" anywhere outside a string fails.
  G3  THE FLUTTER PLUGIN IS APPLIED.  Without dev.flutter.flutter-gradle-plugin
      the engine AAR is never resolved and :flutter is never packaged.
  G4  THE VERSION TRIAD AGREES.  Gradle host / AppInfo / build.sh / workflows all
      say 3.4.6.h + 30414, and the wrapper + AGP pins match Flutter 3.50.1
      (Gradle 9.5.0, AGP 9.3.1) — a mismatch here is an instant CI failure.
  G5  THE MODULE CONTRACT.  ui/.android/include_flutter.groovy is what
      settings.gradle evaluates; the pubspec must declare a module + the real
      asset paths (a missing asset directory aborts `flutter pub get`).
  G6  THE GPG-FREE HYGIENE.  Build output (.gradle/, build/, .dart_tool/) is
      git-ignored; the fonts and pubspec.lock are NOT.
"""
import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

failures = []
checks = 0


def check(name, condition):
    global checks
    checks += 1
    if condition:
        print("  PASS gradle host: %s" % name)
    else:
        failures.append(name)
        print("  FAIL gradle host: %s" % name)


def path(rel):
    return os.path.join(ROOT, rel)


def read(rel):
    with open(path(rel), encoding="utf-8") as fh:
        return fh.read()


def strip_groovy(text):
    """Remove // and /* */ comments plus string literals (offset-stable)."""
    out = list(text)
    i, n = 0, len(text)
    while i < n:
        if text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                out[k] = " "
            i = j
        elif text.startswith("/*", i):
            j = text.find("*/", i)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                if out[k] != "\n":
                    out[k] = " "
            i = j
        elif text[i] in "\"'":
            q = text[i]
            j = i + 1
            while j < n and text[j] != q:
                j += 2 if text[j] == "\\" else 1
            for k in range(i, min(j + 1, n)):
                if out[k] != "\n":
                    out[k] = " "
            i = j + 1
        else:
            i += 1
    return "".join(out)


# ── G1: no machine-local files ───────────────────────────────────────────────
check("android/local.properties does not exist on disk",
      not os.path.exists(path("android/local.properties")))
gitignore = read(".gitignore")
check(".gitignore ignores android/local.properties", "android/local.properties" in gitignore)
# tracked-file check without invoking git (the file may legitimately not exist)
if os.path.isdir(path(".git")):
    import subprocess
    try:
        tracked = subprocess.run(["git", "ls-files", "--", "android/local.properties"],
                                 cwd=ROOT, capture_output=True, text=True).stdout.strip()
    except OSError:
        tracked = ""
    check("android/local.properties is not tracked by git", tracked == "")

# ── G2: the Gradle files are valid Groovy (no shell comments) ────────────────
for rel in ("android/settings.gradle", "android/build.gradle", "android/app/build.gradle"):
    src = read(rel)
    bare = strip_groovy(src)
    bad = [ln for ln in bare.split("\n") if ln.lstrip().startswith("#")]
    check("%s has no shell '#' comment" % rel, not bad)
    check("%s parses as balanced Groovy blocks" % rel,
          bare.count("{") == bare.count("}"))

# ── G3: the Flutter plugin is applied ────────────────────────────────────────
host = read("android/app/build.gradle")
check("the app module applies dev.flutter.flutter-gradle-plugin",
      'id "dev.flutter.flutter-gradle-plugin"' in host)
check("the app module depends on the :flutter project", 'project(":flutter")' in host)
check("the app module references the Media3/Guava jars in place",
      'include: ["*.jar"]' in host
      and 'project.rootProject.projectDir.parentFile' in host)
# v3.4.6 — one policy instead of whack-a-mole: every library libs/ already ships
# is excluded from the MAVEN graph, so AGP can never see two copies of a class.
check("the Maven copies of the libraries libs/ provides are excluded globally",
      all(('exclude group: "%s", module: "%s"' % gm) in host for gm in
          (("com.google.guava", "guava"), ("com.google.guava", "failureaccess"),
           ("com.google.guava", "listenablefuture"),
           ("androidx.annotation", "annotation-experimental"),
           ("androidx.concurrent", "concurrent-futures"))))
check("every shipped jar is still referenced in place (nothing deleted or moved)",
      sorted(os.path.basename(j) for j in glob.glob(path("libs/*.jar"))) == [
          "annotation-experimental-1.3.1.jar", "concurrent-futures-1.2.0.jar",
          "failureaccess-1.0.2.jar", "guava-33.3.1-android.jar",
          "media3-common-1.11.1.jar", "media3-container-1.11.1.jar",
          "media3-database-1.11.1.jar", "media3-datasource-1.11.1.jar",
          "media3-decoder-1.11.1.jar", "media3-exoplayer-1.11.1.jar",
          "media3-exoplayer-dash-1.11.1.jar", "media3-exoplayer-hls-1.11.1.jar",
          "media3-extractor-1.11.1.jar"])
check("the host compiles at Java 17 (Flutter 3.50.1's floor)",
      "VERSION_17" in host and "VERSION_1_8" not in host)
check("minSdk 26 / targetSdk 36 / compileSdk 36 are pinned",
      "minSdk = 26" in host and "targetSdk = 36" in host and "compileSdk = 36" in host)
check("the CI contract is a single arm64 artifact", 'abiFilters "arm64-v8a"' in host)
check("signing is wired from -P properties (KS_FILE/KS_PASS/KS_KEYPASS/KS_ALIAS)",
      all(k in host for k in ("KS_FILE", "KS_PASS", "KS_KEYPASS", "KS_ALIAS")))
settings = read("android/settings.gradle")
check("settings.gradle evaluates ui/.android/include_flutter.groovy",
      'ui/.android/include_flutter.groovy' in settings)
check("settings.gradle resolves the Flutter SDK from FLUTTER_ROOT (local.properties is a fallback)",
      'System.getenv("FLUTTER_ROOT")' in settings)
check("the Gradle host declares AndroidX on (the Flutter engine needs it)",
      "android.useAndroidX=true" in read("android/gradle.properties"))

# ── G3b: the manifest must PARSE (an XML comment may not contain "--", and a
#         single stray double hyphen turns :processReleaseMainManifest red)
import xml.etree.ElementTree as _ET
try:
    _root = _ET.parse(path("AndroidManifest.xml")).getroot()
    _manifest_ok = _root.tag.endswith("manifest")
except Exception as _e:                                   # noqa: BLE001
    _manifest_ok = False
    print("  manifest parse error: %s" % _e)
check("AndroidManifest.xml is well-formed XML", _manifest_ok)
check("no XML comment in the manifest contains a double hyphen",
      all("--" not in c for c in __import__("re").findall(r"<!--(.*?)-->", read("AndroidManifest.xml"), __import__("re").S)))

# ── G4: the version triad + the toolchain pins ───────────────────────────────
appinfo = read("src/lk/techworld/anantplay/sicast/AppInfo.java")
buildsh = read("build.sh")
build_wf = read(".github/workflows/build-apk.yml")
rel_wf = read(".github/workflows/release-apk.yml")
manifest = read("AndroidManifest.xml")
version_pins = {
    "android/app/build.gradle": host,
    "AppInfo.java": appinfo,
    "build.sh": buildsh,
}
for name, text in version_pins.items():
    # v3.4.6.k — the pinned release identity.
    check("%s carries 3.4.6.k + 30417" % name, "3.4.6.k" in text and "30417" in text)
check("the manifest no longer carries the AGP-9-obsolete package attribute",
      'package="lk.techworld' not in manifest)
# v3.4.6 — AGP 9 rejects <uses-sdk>; the SDK levels are owned by the Gradle host
# (minSdk 26 / targetSdk 36) and mirrored by build.sh's aapt2 link flags.
# (the parsed TREE, not the text: the explanatory comment names the tag on purpose)
check("the manifest carries NO <uses-sdk> (AGP 9 forbids it)",
      not any(_c.tag.endswith("uses-sdk") for _c in list(_root)))
check("the SDK levels are pinned where both packaging paths read them",
      "minSdk = 26" in host and "targetSdk = 36" in host and "compileSdk = 36" in host
      and "TARGET_SDK=" in buildsh and "36" in buildsh)
check("Gradle 9.5.0 is the pinned wrapper distribution (AGP 9.3.1's floor)",
      "gradle-9.5.0-bin.zip" in read("android/gradle/wrapper/gradle-wrapper.properties"))
check("the gradle-wrapper.jar ships (the wrapper must not download it)",
      os.path.getsize(path("android/gradle/wrapper/gradle-wrapper.jar")) > 40_000)
check("AGP 9.3.1 is the pinned plugin version", 'version "9.3.1"' in settings)
check("both workflows pin Flutter 3.50.1", build_wf.count("flutter-version: 3.50.1") >= 2
      and rel_wf.count("flutter-version: 3.50.1") >= 1)
check("both workflows pin Gradle 9.5.0", "gradle-version: '9.5.0'" in build_wf
      and "gradle-version: '9.5.0'" in rel_wf)
check("both workflows run through the committed wrapper (./gradlew)",
      "./gradlew assembleRelease" in build_wf and "./gradlew assembleRelease" in rel_wf)
check("the workflows never trust a committed local.properties (they write it from FLUTTER_ROOT)",
      'flutter.sdk=%s' in build_wf and 'flutter.sdk=%s' in rel_wf)
check("the release workflow publishes the versioned APK path it later verifies",
      'dist/AnantPlay-Sinhala-Player-v${{ steps.ver.outputs.version }}.apk' in rel_wf)
check("SHA256SUMS.txt is written in dist/ (the directory the upload step reads)",
      "cd dist" in rel_wf and "sha256sum \"$APK\" > SHA256SUMS.txt" in rel_wf)

# ── G4b2: the signature scheme contract ──────────────────────────────────────
# AGP derives v3 from minSdk/maxSdk and, with minSdk 26, left it OFF — a v2-only
# APK shipped. Both schemes must be requested AND both must be asserted in the
# workflows that publish the artifact.
check("the release signing config requests v1 + v2 + v3 explicitly",
      "v1SigningEnabled true" in host and "v2SigningEnabled true" in host
      and "enableV3Signing true" in host)
check("both workflows FAIL the build unless apksigner reports v2 AND v3 true",
      build_wf.count("Verified using v3 scheme (APK Signature Scheme v3): true") >= 1
      and rel_wf.count("Verified using v3 scheme (APK Signature Scheme v3): true") >= 1
      and build_wf.count("Verified using v2 scheme (APK Signature Scheme v2): true") >= 1
      and rel_wf.count("Verified using v2 scheme (APK Signature Scheme v2): true") >= 1)

# ── G4c: the flutter extension must point the plugin at ui/ ─────────────────
# The plugin's default source is "../.." (a standalone Flutter app's shape), which
# resolves to the REPO ROOT here and makes `flutter assemble` exit 1 with no
# message. It must be spelled out.
check("the flutter extension points at the module (ui/, lib/main.dart)",
      re.search(r"^flutter \{", host, re.M) is not None
      and 'new File(project.rootProject.projectDir.parentFile, "ui").absolutePath' in host
      and 'target = "lib/main.dart"' in host)

# ── G5: the Flutter module contract ──────────────────────────────────────────
pubspec = read("ui/pubspec.yaml")
check("pubspec carries the 3.4.6.k / 30417 identity in a pub-semver-VALID, Gradle-integer build number",
      re.search(r"^version: 3\.4\.6\+30417$", pubspec, re.M) is not None
      and "3.4.6.k" in pubspec
      and "3.4.6.j+30417" not in pubspec
      and re.search(r"^version: .*\.\w+$", pubspec, re.M) is None)
check("pubspec declares the add-to-app module block", re.search(r"^  module:", pubspec, re.M) is not None)
_asset_dirs = re.findall(r"^\s+- (mornye/[A-Za-z0-9_.-]+|assets/[A-Za-z0-9_/.-]+/)$", pubspec, re.M)
check("pubspec only lists asset entries that exist (resolved against ui/ or ui/lib/mornye_ui/)",
      all(os.path.isdir(path("ui/" + p)) or os.path.isdir(path("ui/lib/mornye_ui/" + p))
          or os.path.isfile(path("ui/" + p)) or os.path.isfile(path("ui/lib/mornye_ui/" + p))
          or os.path.isfile(path("ui/assets/" + p))
          for p in _asset_dirs))
check("the pinned font stack ships (Inter + Google Sans Flex + Sinhala)",
      all(os.path.getsize(path("ui/assets/fonts/" + f)) > 1000 for f in
          ("InterVariable.ttf", "GoogleSansFlex.ttf",
           "NotoSansSinhala-Regular.ttf", "NotoSansSinhala-SemiBold.ttf")))
check("the vendored engine keeps LICENSE + COPYING + NOTICE",
      all(os.path.isfile(path("ui/lib/mornye_ui/" + f)) for f in ("LICENSE", "COPYING", "NOTICE")))
# ui/.dart_tool/package_config.json is generated by `flutter pub get`; the repo
# ships the pins in pubspec.yaml and resolves in CI, so a MISSING pubspec.lock is
# NOT a defect (Flutter module locks are regenerated per SDK). Assert the pins.
check("ui/pubspec.yaml pins every direct dependency to an exact version",
      "liquid_glass_easy: 5.2.0" in pubspec and "material_3_expressive: 1.2.1" in pubspec
      and "flutter_riverpod: 3.5.0" in pubspec and "material_ui: 1.11.0" in pubspec)
for dart in ("ui/lib/main.dart", "ui/lib/anantplay/bridge.dart",
             "ui/lib/anantplay/home_screen.dart", "ui/lib/anantplay/settings_screen.dart",
             "ui/lib/anantplay/help_screen.dart", "ui/lib/anantplay/onboarding_screen.dart",
             "ui/lib/anantplay/app_state.dart", "ui/lib/anantplay/settings_mirror.dart"):
    check("%s ships" % dart, os.path.isfile(path(dart)))

# ── G6: hygiene ──────────────────────────────────────────────────────────────
for ignored in ("ui/.dart_tool/", "ui/.android/", "ui/build/", "android/.gradle/",
                "android/build/", "android/app/build/", "**/ephemeral/"):
    check(".gitignore ignores %s" % ignored.strip(), ignored in gitignore)
check(".gitignore keeps the fonts + pubspec.lock tracked",
      ".*\\.ttf" not in gitignore and "pubspec.lock" not in
      "\n".join(l for l in gitignore.splitlines() if not l.strip().startswith("#")))
if os.path.isdir(path(".git")):
    import subprocess
    zips = subprocess.run(["git", "ls-files", "*.zip"], cwd=ROOT,
                          capture_output=True, text=True).stdout.strip()
    check("no .zip is tracked right now", zips == "")

for f in failures:
    print("::error title=gradle-host::%s" % f)
print("v3.4.6.d gradle host: %d checks, %d failed" % (checks, len(failures)))
sys.exit(1 if failures else 0)
