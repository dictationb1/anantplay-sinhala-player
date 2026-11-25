#!/usr/bin/env python3
"""v3.4.6.d — THE FLUTTER-BRIDGE WIRING AUDITOR (Phase 1's mechanical gate).

Two proofs, both mechanical, both CI-killing:

  R1  EVERY `invokeMethod('<name>' ...)` in ui/lib/anantplay/*.dart has a
      matching Java handler (`case "<name>":` or `"<name>".equals(call.method)`
      / `"<name>".equals(m)`) in src/lk/techworld/anantplay/sicast/MainActivity.java
      or Media3TextureBridge.java. A missing handler is a DEAD CHANNEL — the
      v3.4.6.c real-phone defects (enterPip, openExternal) shipped exactly this
      way and this gate exists so it can never happen again.

  R2  ZERO empty `onPressed: () {}` / `onTap: () {}` stub callbacks anywhere in
      ui/lib/anantplay/ — a button that does nothing is a fake button (the
      MornyeFilterChip defect class).

Exit 0 = wired; exit 1 = at least one dead channel or fake button (CI fails).
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DART_DIR = ROOT / "ui" / "lib" / "anantplay"
JAVA_FILES = [
    ROOT / "src" / "lk" / "techworld" / "anantplay" / "sicast" / "MainActivity.java",
    ROOT / "src" / "lk" / "techworld" / "anantplay" / "sicast" / "Media3TextureBridge.java",
]

PASS = 0
FAIL = 0


def check(name: str, ok: bool, detail: str = "") -> None:
    global PASS, FAIL
    tag = "PASS" if ok else "FAIL"
    if ok:
        PASS += 1
    else:
        FAIL += 1
    print(f"  {tag} {name}" + (f" — {detail}" if detail else ""))


# ── R1: every Dart invokeMethod has a Java handler ────────────────────────────

# invokeMethod( 'name' | "name" — first string literal after the paren, which is
# the method name in both the MethodChannel.invokeMethod and channel-level forms.
INVOKE_RE = re.compile(
    r"""invokeMethod\s*\(\s*(['"])((?:\\.|(?!\1).)+?)\1""")

# The Java-side dispatch surfaces: switch cases + .equals(...) string tests.
CASE_RE = re.compile(r'case\s+"([^"]+)"\s*:')
EQUALS_RE = re.compile(r'"([^"]+)"\s*\.equals\s*\(\s*(?:call\.method|m|call\.action)\s*\)')


def collect_dart_invocations() -> dict[str, list[str]]:
    """method name → [(file, line)] across the anantplay Dart surface."""
    found: dict[str, list[str]] = {}
    for dart in sorted(DART_DIR.glob("*.dart")):
        text = dart.read_text(encoding="utf-8")
        for m in INVOKE_RE.finditer(text):
            name = m.group(2)
            line = text.count("\n", 0, m.start()) + 1
            found.setdefault(name, []).append(f"{dart.name}:{line}")
    return found


def collect_java_handlers() -> set[str]:
    handled: set[str] = set()
    for java in JAVA_FILES:
        if not java.is_file():
            continue
        text = java.read_text(encoding="utf-8")
        handled.update(CASE_RE.findall(text))
        handled.update(EQUALS_RE.findall(text))
    return handled


def rule_r1() -> None:
    print("R1 — every Dart invokeMethod has a Java handler (zero dead channels)")
    invocations = collect_dart_invocations()
    handlers = collect_java_handlers()
    if not invocations:
        check("dart surface discovered", False, "no invokeMethod calls found at all")
        return
    dead = 0
    for name in sorted(invocations):
        sites = invocations[name]
        ok = name in handlers
        if not ok:
            dead += 1
        check(
            f"channel '{name}'",
            ok,
            "" if ok else "invoked at " + ", ".join(sites) + " but NO Java handler",
        )
    check("R1 total: 0 dead channels", dead == 0, f"{dead} dead" if dead else "")


# ── R2: zero empty button callbacks ───────────────────────────────────────────

# onPressed/onTap/onClick with an IMMEDIATELY empty lambda body. Whitespace and
# comments inside the braces count as empty (a comment is not work).
EMPTY_CB_RE = re.compile(
    r"""(?:onPressed|onTap|onClick|onLongPress)\s*:\s*\(\s*\)\s*\{\s*(?://[^\n]*)?\s*\}""",
)

# Dart line + block comments are stripped BEFORE matching so a comment that
# QUOTES the defect pattern ("was a dead `onTap: () {}`") is not itself a
# finding. Strings are preserved (a code string is still code).
LINE_COMMENT_RE = re.compile(r"//[^\n]*")
BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)


def strip_dart_comments(text: str) -> str:
    # Replace comments with same-length whitespace so line numbers survive.
    def blank(m: re.Match) -> str:
        return re.sub(r"[^\n]", " ", m.group(0))
    text = BLOCK_COMMENT_RE.sub(blank, text)
    text = LINE_COMMENT_RE.sub(blank, text)
    return text


def rule_r2() -> None:
    print("R2 — zero empty onPressed/onTap stub callbacks (zero fake buttons)")
    stubs = 0
    for dart in sorted(DART_DIR.glob("*.dart")):
        text = strip_dart_comments(dart.read_text(encoding="utf-8"))
        for m in EMPTY_CB_RE.finditer(text):
            stubs += 1
            line = text.count("\n", 0, m.start()) + 1
            check(f"{dart.name}:{line} empty callback", False, m.group(0))
    check("R2 total: 0 fake buttons", stubs == 0, f"{stubs} empty stubs" if stubs else "")


def main() -> int:
    print("── lab/verify_flutter_bridge_wiring.py (v3.4.6.d)")
    rule_r1()
    rule_r2()
    print(f"Bridge wiring: {PASS} passed, {FAIL} failed")
    if FAIL:
        print("BRIDGE WIRING FAILED: dead channels or fake buttons exist", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
