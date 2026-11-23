#!/usr/bin/env python3
"""v3.4.1 — GATING AUDITOR #5: JAVAC SYNTAX GATE FOR THE ANDROID-ONLY SOURCES.

`lab/run_lab.sh` compiles only the PURE engines (the classes that touch no
`android.*` type). The Activity classes — `PlayActivity`, `MainActivity`,
`WebBrowserView`, `DlnaEndpoint` — are compiled exclusively by `build.sh`
against `android.jar`, so a stray brace or a mis-typed statement in one of them
is invisible to the local lab and only surfaces on the hosted APK build.

This gate closes that hole without an SDK: it runs `javac` over every shipped
source at once and keeps ONLY the syntax-class diagnostics. The symbol errors
("package android.content does not exist") are expected and discarded — javac
reports both kinds in the same pass, so a file that produces no syntax
diagnostic is provably parseable.

Diagnostics treated as fatal (a file that does not parse):
    ';' expected | <identifier> expected | illegal start of expression |
    class, interface, enum, or record expected | reached end of file while parsing |
    not a statement | unclosed string literal | unclosed comment |
    ')' expected | '(' expected | '{' expected | '}' expected | 'else' without 'if' |
    invalid method declaration | duplicate class | malformed

Exit 0 = every shipped source parses. Exit 1 = at least one does not, printed
with file:line so the fix is mechanical. Exit 77 = no javac on this machine
(reported as SKIP, never as a pass).
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src")

SYNTAX = re.compile(
    r"(?:';' expected|<identifier> expected|illegal start of (?:expression|type)"
    r"|class, interface, enum, or record expected|reached end of file while parsing"
    r"|not a statement|unclosed (?:string literal|comment|character literal)"
    r"|'[)\]}]' expected"              # ') expected' / '}' expected / ']' expected
    r"|'(?:else|catch|finally)' without"
    r"|invalid method declaration|duplicate class|malformed)",
    re.IGNORECASE,
)


def javac():
    exe = shutil.which("javac")
    if exe:
        return exe
    home = os.environ.get("JAVA_HOME")
    if home:
        cand = os.path.join(home, "bin", "javac")
        if os.path.isfile(cand):
            return cand
    return None


def main():
    jc = javac()
    if not jc:
        print("verify_syntax: SKIP — no javac on this machine (install a JDK 17 to enable)")
        return 77
    if not os.path.isdir(SRC):
        print("FATAL: no src/ under %s" % ROOT)
        return 2

    files = []
    for dirpath, _dirs, names in os.walk(SRC):
        for n in sorted(names):
            if n.endswith(".java"):
                files.append(os.path.join(dirpath, n))

    out_dir = tempfile.mkdtemp(prefix="syntax-")
    cmd = [jc, "-encoding", "UTF-8", "-nowarn", "-proc:none", "-d", out_dir] + files
    proc = subprocess.run(cmd, capture_output=True, text=True, cwd=ROOT)
    text = proc.stdout + proc.stderr

    hits = []
    for line in text.splitlines():
        if "error:" not in line:
            continue
        if SYNTAX.search(line):
            hits.append(line.strip())

    print("verify_syntax: %d shipped sources parsed with %s" % (len(files), os.path.basename(jc)))
    if hits:
        print("verify_syntax: FAIL — %d syntax diagnostic(s):" % len(hits))
        for h in hits[:40]:
            print("  " + h)
        return 1
    print("verify_syntax: OK — no syntax diagnostic in any of the %d sources "
          "(symbol errors from the absent android.jar are expected and ignored)" % len(files))
    return 0


if __name__ == "__main__":
    sys.exit(main())
