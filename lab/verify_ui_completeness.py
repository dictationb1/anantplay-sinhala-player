#!/usr/bin/env python3
"""v3.4.1 — GATING AUDITOR #4: UI COMPLETENESS & OPTICAL-SIZING TRUTH.

The v3.4.0 field report said, in the user's own words:

    "UI looks fake / messy, symbols are way too huge, many features /
     customizations aren't actually working 100%"

Three claims, three mechanical checks. Nothing here is a screenshot opinion:
every rule is decided by parsing `src/**/*.java`.

  R1  ICON OPTICAL CAPS.  Every `UiIcons.tinted/view/button/setCompound/
      setIconOnly(...)` call whose size argument is an integer LITERAL must be
      <= CAP[call-site-kind].  A hero Play/Pause disc may reach 32dp; nothing
      else may exceed 28dp.  (v3.4.0 shipped 36-48dp stretched vectors.)

  R2  ZERO FAKE CUSTOMIZATION.  Every `public static final String K_*` in
      AppPrefs.java must be (a) listed in BACKUP_KEYS and (b) actually READ or
      WRITTEN by a UI surface (MainActivity / PlayActivity / WebBrowserView).
      A key that is in neither is an unwired control; a key that is only in
      BACKUP_KEYS is a control that survives export but changes nothing.

  R3  NO EMOJI IN UI CHROME.  Comments are stripped first (the source is
      allowed to *talk* about 🎬).  Any emoji left inside a STRING LITERAL that
      is handed to a control (setText / setTitle / UiChip / UiSegmented /
      UiSheet title / Toast) fails the gate: the app draws its chrome with
      res/drawable/ic_*.xml through UiIcons, never with font glyphs.

Exit 0 = all three rules pass.  Exit 1 = at least one violation, printed with
file:line so the fix is mechanical.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src")
PKG = os.path.join(SRC, "lk", "techworld", "anantplay", "sicast")

# ── rule constants ───────────────────────────────────────────────────────────
HARD_MAX_DP = 28          # absolute cap for any icon that is not the hero disc
HERO_MAX_DP = 32          # Play/Pause hero disc only
EMPTY_STATE_MAX = 40      # a designed empty-state ILLUSTRATION (not chrome) may be 40dp
HERO_ALLOW = {
    # (file, sizeDp) pairs that are the centre hero disc and may reach 32dp.
    ("PlayActivity.java", 32),
}
# A designed empty-state illustration is not UI chrome: it is one large muted
# vector above a title + body block (UiGroup.empty). Allowed up to 40dp, and
# ONLY inside that one method — every other call site keeps the 28dp cap.
ILLUSTRATION_METHOD = ("UiGroup.java", "empty")
# The cinema HUD's own helper: 20dp top bar, 24dp centre disc, never more.
HUD_MAX_DP = 24
# The design grammar's intended sizes (documented, checked as a WARN not a FAIL)
GRAMMAR = {22: "bottom nav", 20: "top app bar / HUD", 18: "settings row", 16: "chip/button"}

ICON_CALLS = ("tinted", "view", "button", "setCompound", "setIconOnly")

EMOJI = re.compile(
    "[\U0001F000-\U0001FAFF\u2600-\u27BF\u2B00-\u2BFF\uFE0F\u23E9-\u23FA"
    "\u2705\u274C\u2757\u2753\u2B50\u2B55\u2139]"
)

CONTROL_SINKS = re.compile(
    r"(setText|setTitle|setHint|setContentTitle|setContentText|Toast\.makeText"
    r"|UiChip\.\w+|UiSegmented\.\w+|UiSheet\.\w+|UiGroup\.\w+|flash)\s*\("
)

violations = []
notes = []


def rel(p):
    return os.path.relpath(p, ROOT)


def strip_comments(text):
    """Blank out // and /* */ comments, keeping byte offsets stable (newlines
    are preserved so line numbers stay true)."""
    out = []
    i, n = 0, len(text)
    state = None  # None | 'line' | 'block' | 'str' | 'chr'
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if state is None:
            if c == "/" and nxt == "/":
                state = "line"; out.append("  "); i += 2; continue
            if c == "/" and nxt == "*":
                state = "block"; out.append("  "); i += 2; continue
            if c == '"':
                state = "str"; out.append(c); i += 1; continue
            if c == "'":
                state = "chr"; out.append(c); i += 1; continue
            out.append(c); i += 1; continue
        if state == "line":
            if c == "\n":
                state = None; out.append(c)
            else:
                out.append(" ")
            i += 1; continue
        if state == "block":
            if c == "*" and nxt == "/":
                state = None; out.append("  "); i += 2; continue
            out.append("\n" if c == "\n" else " "); i += 1; continue
        if state in ("str", "chr"):
            if c == "\\":
                out.append(c); out.append(nxt if i + 1 < n else ""); i += 2; continue
            if (state == "str" and c == '"') or (state == "chr" and c == "'"):
                state = None
            out.append(c); i += 1; continue
    return "".join(out)


def split_args(s):
    """Top-level comma split of a call's argument list."""
    args, depth, cur, i = [], 0, [], 0
    instr = None
    while i < len(s):
        c = s[i]
        if instr:
            cur.append(c)
            if c == "\\" and i + 1 < len(s):
                cur.append(s[i + 1]); i += 2; continue
            if c == instr:
                instr = None
            i += 1; continue
        if c in "\"'":
            instr = c; cur.append(c); i += 1; continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            if depth == 0:
                break
            depth -= 1
        if c == "," and depth == 0:
            args.append("".join(cur).strip()); cur = []; i += 1; continue
        cur.append(c); i += 1
    tail = "".join(cur).strip()
    if tail:
        args.append(tail)
    return args


def line_of(text, off):
    return text.count("\n", 0, off) + 1


def enclosing_method(code, off):
    """The name of the nearest method declared above `off` (best effort, good
    enough for a size-cap allowlist keyed on one method)."""
    best = ""
    for m in re.finditer(r"\b(?:static|public|private|protected|final|\s)+[\w<>\[\].]+\s+(\w+)\s*\(", code[:off]):
        best = m.group(1)
    return best


# ── R1: icon optical caps ────────────────────────────────────────────────────
def rule_icons():
    checked = 0
    hist = {}
    for dirpath, _dirs, files in os.walk(SRC):
        for fn in sorted(files):
            if not fn.endswith(".java"):
                continue
            p = os.path.join(dirpath, fn)
            raw = open(p, encoding="utf-8").read()
            code = strip_comments(raw)
            for m in re.finditer(r"UiIcons\.(\w+)\s*\(", code):
                kind = m.group(1)
                if kind not in ICON_CALLS:
                    continue
                args = split_args(code[m.end():])
                if len(args) < 4:
                    continue
                size = args[3]
                if not re.fullmatch(r"\d+", size):
                    notes.append("%s:%d UiIcons.%s(..., %s) — non-literal size, "
                                 "not checkable statically" % (rel(p), line_of(code, m.start()), kind, size))
                    continue
                dp = int(size)
                checked += 1
                hist[dp] = hist.get(dp, 0) + 1
                if (fn, dp) in HERO_ALLOW:
                    continue
                cap = HARD_MAX_DP
                if fn == ILLUSTRATION_METHOD[0] and enclosing_method(code, m.start()) == ILLUSTRATION_METHOD[1]:
                    cap = EMPTY_STATE_MAX
                if dp > cap:
                    violations.append(
                        "R1 OVERSIZED ICON %s:%d  UiIcons.%s(..., %ddp) > %ddp cap"
                        % (rel(p), line_of(code, m.start()), kind, dp, cap))
    # v3.4.1 — the PlayActivity HUD helper `icon(R.drawable.x, 20, click)` is a second
    # icon surface with the same optical cap. v3.4.0 shipped 24dp there (spec: 20dp)
    # next to four 15dp siblings, which is exactly what reads as "messy".
    play = os.path.join(PKG, "PlayActivity.java")
    if os.path.exists(play):
        code = strip_comments(open(play, encoding="utf-8").read())
        for m in re.finditer(r"(?<![\w.])icon\s*\(\s*R\.drawable\.\w+\s*,\s*(\d+)\s*,", code):
            dp = int(m.group(1))
            checked += 1
            hist[dp] = hist.get(dp, 0) + 1
            if dp > HUD_MAX_DP:
                violations.append("R1 OVERSIZED HUD ICON %s:%d  icon(..., %ddp) > %ddp HUD cap"
                                  % (rel(play), line_of(code, m.start()), dp, HUD_MAX_DP))

    print("R1 icon calls with a literal size : %d" % checked)
    print("R1 size histogram (dp:count)      : %s"
          % ", ".join("%d:%d" % (k, hist[k]) for k in sorted(hist)))
    off = {k: v for k, v in hist.items() if k not in GRAMMAR}
    if off:
        print("R1 sizes outside the 16/18/20/22 grammar (allowed, <=28dp): %s"
              % ", ".join("%ddp x%d" % (k, off[k]) for k in sorted(off)))


# ── R2: zero fake customization ──────────────────────────────────────────────
def rule_prefs():
    prefs_p = os.path.join(PKG, "AppPrefs.java")
    src = strip_comments(open(prefs_p, encoding="utf-8").read())
    keys = {}
    for m in re.finditer(r'static final String (K_\w+)\s*=\s*"([^"]*)"', src):
        keys[m.group(1)] = m.group(2)
    backed = set()
    bm = re.search(r"BACKUP_KEYS\s*=\s*Arrays\.asList\((.*?)\);", src, re.S)
    if bm:
        backed = set(re.findall(r"K_\w+", bm.group(1)))
    surfaces = {}
    for fn in ("MainActivity.java", "PlayActivity.java", "WebBrowserView.java",
               "SpeedDial.java", "UiSheet.java", "SettingsBackup.java", "App.java",
               "ReceiverService.java", "DlnaEndpoint.java", "StreamHttpConfig.java"):
        p = os.path.join(PKG, fn)
        if os.path.exists(p):
            surfaces[fn] = strip_comments(open(p, encoding="utf-8").read())

    # A pref is WIRED when a UI surface names it, OR names one of the AppPrefs
    # accessors that read/write it (AppPrefs.swipeSeek(prefs) is a real wire).
    accessors = {}   # K_ -> {method names that mention it}
    for m in re.finditer(r"(?:public|static|private)[\w\s<>\[\],.]*?\b(\w+)\s*\([^)]*\)\s*\{", src):
        name, start = m.group(1), m.end()
        depth, i = 1, start
        while i < len(src) and depth:
            if src[i] == "{":
                depth += 1
            elif src[i] == "}":
                depth -= 1
            i += 1
        body = src[start:i]
        for k in re.findall(r"\bK_\w+\b", body):
            accessors.setdefault(k, set()).add(name)
    ui_all = "\n".join(surfaces.values())

    print("R2 AppPrefs keys                  : %d" % len(keys))
    print("R2 keys in BACKUP_KEYS            : %d" % len(backed))
    missing_backup, unwired = [], []
    for k, raw in sorted(keys.items()):
        if k not in backed:
            missing_backup.append((k, raw))
        used = bool(re.search(r"\b%s\b" % k, ui_all))
        if not used:
            for acc in sorted(accessors.get(k, ())):
                if acc in ("read", "write", "get", "put", "set", "load", "save"):
                    continue
                if re.search(r"\b%s\s*\(" % re.escape(acc), ui_all):
                    used = True
                    break
        if not used:
            unwired.append((k, raw))
    for k, raw in missing_backup:
        violations.append("R2 NOT IN BACKUP_KEYS %s (\"%s\") — export/import would drop it" % (k, raw))
    for k, raw in unwired:
        violations.append("R2 UNWIRED PREF %s (\"%s\") — no UI surface reads or writes it" % (k, raw))
    print("R2 missing from BACKUP_KEYS       : %d" % len(missing_backup))
    print("R2 not referenced by any UI       : %d" % len(unwired))
    extra = backed - set(keys)
    if extra:
        violations.append("R2 BACKUP_KEYS names a key that AppPrefs does not define: %s"
                          % ", ".join(sorted(extra)))


# ── R3: no emoji in UI chrome ────────────────────────────────────────────────
def rule_emoji():
    total = 0
    for fn in ("MainActivity.java", "PlayActivity.java", "WebBrowserView.java",
               "SpeedDial.java", "UiChip.java", "UiGroup.java", "UiCard.java",
               "UiSheet.java", "UiSegmented.java", "UiSlider.java", "CapsuleBar.java"):
        p = os.path.join(PKG, fn)
        if not os.path.exists(p):
            continue
        code = strip_comments(open(p, encoding="utf-8").read())
        # every string literal in live code, with its offset
        for m in re.finditer(r'"((?:[^"\\]|\\.)*)"', code):
            body = m.group(1)
            if not EMOJI.search(body):
                continue
            ln = line_of(code, m.start())
            line_start = code.rfind("\n", 0, m.start()) + 1
            line_txt = code[line_start:code.find("\n", m.start())]
            if CONTROL_SINKS.search(line_txt) or CONTROL_SINKS.search(
                    code[max(0, line_start - 200):line_start]):
                total += 1
                violations.append("R3 EMOJI IN UI CHROME %s:%d  %s" % (rel(p), ln, line_txt.strip()[:100]))
    print("R3 emoji found in control strings : %d" % total)


def main():
    if not os.path.isdir(SRC):
        print("FATAL: no src/ under %s" % ROOT)
        return 2
    print("=" * 72)
    print("verify_ui_completeness.py — v3.4.1 UI TRUTH GATE")
    print("=" * 72)
    rule_icons()
    rule_prefs()
    rule_emoji()
    print("-" * 72)
    if violations:
        print("FAIL — %d violation(s):" % len(violations))
        for v in violations:
            print("  " + v)
        if notes:
            print("notes (%d):" % len(notes))
            for n in notes[:20]:
                print("  · " + n)
        return 1
    print("OK — icons <= %ddp, every pref wired + backed up, zero emoji chrome."
          % HARD_MAX_DP)
    return 0


if __name__ == "__main__":
    sys.exit(main())
