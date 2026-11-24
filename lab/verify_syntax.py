#!/usr/bin/env python3
"""v3.4.1 — GATING AUDITOR #5: THE JAVAC GATE FOR THE ANDROID-ONLY SOURCES.

`lab/run_lab.sh` compiles only the PURE engines (the classes that touch no
`android.*` type). The Activity classes — `PlayActivity`, `MainActivity`,
`WebBrowserView`, `DlnaEndpoint` — are otherwise compiled exclusively by
`build.sh` against `android.jar`, so a bad cast or a method reference captured
by the wrong `this` was invisible locally and only turned the hosted APK build
red. (That is exactly how v3.4.1's first PR run failed twice.)

The gate has two modes:

  FULL    an `android.jar` is available (ANDROID_JAR env, or
          $ANDROID_HOME/platforms/android-*/android.jar). The gate generates a
          stub `R` from every `R.<type>.<name>` reference in src/, compiles ALL
          shipped sources against android.jar + libs/*.jar, and fails on ANY
          diagnostic — syntax, missing symbol, bad cast, invalid method
          reference. This is the same compile the APK build performs.

  SYNTAX  no android.jar. The gate still runs javac over all sources and keeps
          only the syntax-class diagnostics (the "package android.content does
          not exist" noise is expected and discarded).

Exit 0 = clean. Exit 1 = at least one diagnostic, printed with file:line.
Exit 77 = no javac at all (reported as SKIP, never as a silent pass).
"""
import glob
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src")
PKG_PATH = os.path.join("lk", "techworld", "anantplay", "sicast")

SYNTAX = re.compile(
    r"(?:';' expected|<identifier> expected|illegal start of (?:expression|type)"
    r"|class, interface, enum, or record expected|reached end of file while parsing"
    r"|not a statement|unclosed (?:string literal|comment|character literal)"
    r"|'[)\]}]' expected"
    r"|'(?:else|catch|finally)' without"
    r"|invalid method declaration|duplicate class|malformed)",
    re.IGNORECASE,
)


_probed_roots = []


def flutter_embedding_jars():
    """v3.4.6 — the Flutter embedding classes the host activity imports.

    MainActivity/Media3TextureBridge extend FlutterActivity and use MethodChannel,
    so a FULL compile against android.jar alone reports dozens of false
    "package io.flutter... does not exist" diagnostics the real Gradle build
    never has (the Gradle host resolves the embedding from the engine's maven
    repo).

    The embedding AAR is unpacked into a pub cache by `flutter pub get`:
        <cache>/hosted/pub.dev/flutter_embedding_*/jars/*.jar
    subosito/flutter-action keeps that cache under RUNNER_TOOL_CACHE, and Flutter
    honours PUB_CACHE / FLUTTER_ROOT, so every plausible root is probed. A job
    that never resolved simply finds nothing; io.flutter diagnostics are then
    downgraded to a note (see main()).
    """
    global _probed_roots
    found = []
    roots = []
    for env in ("PUB_CACHE", "FLUTTER_ROOT", "RUNNER_TOOL_CACHE", "HOME"):
        v = os.environ.get(env)
        if v:
            roots.append(v if env != "FLUTTER_ROOT" else os.path.join(v, ".pub-cache"))
            if env == "FLUTTER_ROOT":
                # the engine artifacts (incl. the embedding jars) live in bin/cache
                roots.append(os.path.join(v, "bin", "cache"))
    if os.environ.get("HOME"):
        roots.append(os.path.join(os.environ["HOME"], ".pub-cache"))
        roots.append(os.path.join(os.environ["HOME"], ".pub-cache", "hosted"))
    seen = set()
    _probed_roots = roots
    for root in roots:
        if not root or root in seen or not os.path.isdir(root):
            continue
        seen.add(root)
        for dirpath, dirs, files in os.walk(root):
            base = os.path.basename(dirpath)
            if base.startswith("flutter_embedding_") or base == "maven":
                for f in files:
                    if f.endswith(".jar") and "flutter" in f.lower():
                        found.append(os.path.join(dirpath, f))
                jd = os.path.join(dirpath, "jars")
                if os.path.isdir(jd):
                    found.extend(os.path.join(jd, f) for f in os.listdir(jd) if f.endswith(".jar"))
                dirs[:] = []
            if dirpath.count(os.sep) - root.count(os.sep) >= 6:
                dirs[:] = []
    return sorted(set(found))


def find_javac():
    exe = shutil.which("javac")
    if exe:
        return exe
    home = os.environ.get("JAVA_HOME")
    if home:
        cand = os.path.join(home, "bin", "javac")
        if os.path.isfile(cand):
            return cand
    return None


def find_android_jar():
    """ANDROID_JAR, then $ANDROID_HOME / $ANDROID_SDK_ROOT platforms, newest first."""
    explicit = os.environ.get("ANDROID_JAR")
    if explicit and os.path.isfile(explicit):
        return explicit
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(var)
        if not root:
            continue
        hits = sorted(glob.glob(os.path.join(root, "platforms", "android-*", "android.jar")),
                      key=lambda p: int(re.sub(r"\D", "", os.path.basename(os.path.dirname(p))) or 0))
        if hits:
            return hits[-1]
    # a jar dropped next to the lab is honoured too
    local = os.path.join(ROOT, "lab", "android.jar")
    return local if os.path.isfile(local) else None


def sources():
    out = []
    for dirpath, _dirs, names in os.walk(SRC):
        for n in sorted(names):
            if n.endswith(".java"):
                out.append(os.path.join(dirpath, n))
    return out


def gen_stub_r(files):
    """A stub R holding exactly the R.<type>.<name> ids the sources reference.

    The real R is generated by aapt2 at APK time; for a COMPILE gate we only need
    the names to resolve, so every field is a distinct dummy int.
    """
    refs = {}
    for f in files:
        s = open(f, encoding="utf-8").read()
        for t, n in re.findall(r"\bR\.(\w+)\.(\w+)", s):
            refs.setdefault(t, set()).add(n)
    out_dir = tempfile.mkdtemp(prefix="rgen-")
    pkg_dir = os.path.join(out_dir, PKG_PATH)
    os.makedirs(pkg_dir, exist_ok=True)
    i = 0x7F010000
    lines = ["package lk.techworld.anantplay.sicast;", "", "public final class R {"]
    for t in sorted(refs):
        lines.append("    public static final class %s {" % t)
        for n in sorted(refs[t]):
            i += 1
            lines.append("        public static final int %s = 0x%08X;" % (n, i))
        lines.append("    }")
    lines.append("}")
    path = os.path.join(pkg_dir, "R.java")
    open(path, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    return path, sum(len(v) for v in refs.values())


def main():
    jc = find_javac()
    if not jc:
        print("verify_syntax: SKIP — no javac on this machine (install a JDK 17 to enable)")
        return 77
    if not os.path.isdir(SRC):
        print("FATAL: no src/ under %s" % ROOT)
        return 2

    files = sources()
    android = find_android_jar()
    out_dir = tempfile.mkdtemp(prefix="javac-")
    cmd = [jc, "-encoding", "UTF-8", "-nowarn", "-proc:none", "-d", out_dir]

    mode = "SYNTAX"
    if android:
        # v3.4.6 — the APK is packaged by the Gradle host, whose app module
        # embeds the Flutter engine. MainActivity/Media3TextureBridge import
        # io.flutter.*, so the FULL compile must see the embedding jars or it
        # reports false "cannot find symbol" failures the real build never has.
        embed = flutter_embedding_jars()
        cp = [android] + embed + sorted(glob.glob(os.path.join(ROOT, "libs", "*.jar")))
        rjava, fields = gen_stub_r(files)
        cmd += ["-cp", os.pathsep.join(cp)] + files + [rjava]
        mode = "FULL"
        print("verify_syntax: FULL mode — android.jar %s + %d libs + %d flutter-embedding jars, stub R with %d ids"
              % (os.path.basename(os.path.dirname(android)), len(cp) - len(embed) - 1,
                 len(embed), fields))
        if not embed:
            # No pub cache: the embedding imports cannot resolve, so failures
            # mentioning io.flutter are EXPECTED. Downgrade those specific
            # diagnostics to a note instead of a false red gate.
            print("verify_syntax: NOTE — no flutter_embedding jars found (run `flutter pub get` "
                  "in ui/ first); io.flutter.* symbols are not checked in this run")
            print("verify_syntax: NOTE — searched %s" % ", ".join(p for p in _probed_roots if p))
    else:
        cmd += files
        print("verify_syntax: SYNTAX mode — no android.jar found "
              "(set ANDROID_JAR or ANDROID_HOME for a full compile)")

    proc = subprocess.run(cmd, capture_output=True, text=True, cwd=ROOT)
    text = proc.stdout + proc.stderr

    def _blocks(t):
        """Group javac output into complete diagnostics.

        javac prints the reason on the lines AFTER the error, under a ^ caret:

            MainActivity.java:49: error: cannot find symbol
            public class MainActivity extends FlutterActivity {
                                               ^
              symbol:   class FlutterActivity
              location: class MainActivity

        A bare "cannot find symbol" in a check-run annotation tells nobody
        anything, so the source line, the caret and every reason keyword are
        folded into one string. (v3.4.6: the first version dropped the reason
        lines entirely, which also defeated the embedding filter below.)
        """
        lines = t.splitlines()
        out, i = [], 0
        while i < len(lines):
            if "error:" in lines[i]:
                parts = [lines[i].strip()]
                j = i + 1
                while j < len(lines) and j <= i + 8:
                    raw, st = lines[j], lines[j].strip()
                    if not st or "error:" in raw:
                        break
                    if st.startswith(("symbol:", "location:", "required:", "found:", "reason:")):
                        parts.append(st)
                    elif st.startswith("^"):
                        pass          # the caret: no information of its own
                    elif not parts[-1].startswith("<src>"):
                        parts.append(st)   # the offending source line
                    j += 1
                out.append(" | ".join(parts))
                i = j
                continue
            i += 1
        return out

    if mode == "FULL":
        hits = _blocks(text)
        if android and not embed:
            # A missing embedding jar is not a syntax defect in our sources. javac
            # reports the unresolved EMBEDDING SYMBOLS in several shapes
            # ("package io.flutter.x does not exist", "package MethodChannel does
            # not exist", "cannot find symbol ... FlutterActivity"), so drop only
            # the diagnostics that name one of them — everything else still fails.
            _EMBED = re.compile(r"io\.flutter|package io\.|MethodChannel|EventChannel|"
                                r"MethodCall|BinaryMessenger|FlutterActivity|FlutterEngine|"
                                r"FlutterView|FlutterPlugin|PluginRegistry|StandardMethodCodec|"
                                r"PlatformView|FlutterFragment")
            _dropped = [h for h in hits if _EMBED.search(h)]
            hits = [h for h in hits if not _EMBED.search(h)]
            if _dropped:
                print("verify_syntax: NOTE — %d embedding-only diagnostic(s) suppressed "
                      "(no flutter_embedding jar on this runner)" % len(_dropped))
    else:
        hits = [ln.strip() for ln in text.splitlines()
                if "error:" in ln and SYNTAX.search(ln)]

    print("verify_syntax: %d shipped sources parsed with %s" % (len(files), os.path.basename(jc)))
    if hits:
        print("verify_syntax: FAIL — %d diagnostic(s) in %s mode:" % (len(hits), mode))
        for h in hits[:40]:
            print("  " + h)
            print("::error title=javac::" + h[:900].replace("\n", " "))
        return 1
    print("verify_syntax: OK — 0 diagnostics in %s mode across %d sources" % (mode, len(files)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
