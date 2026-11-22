#!/usr/bin/env python3
# v3.3.1 — THE STATIC LAYOUT-PARAMS VERIFIER (gating: lab/run_lab.sh + hosted CI).
#
# THE FIELD CRASH THIS PREVENTS (2026-11-23, Android 16 / SDK 36, v3.3.0):
#
#   java.lang.ClassCastException: android.widget.LinearLayout$LayoutParams
#       cannot be cast to android.widget.FrameLayout$LayoutParams
#       at android.widget.FrameLayout.onMeasure(FrameLayout.java:195)
#
# FrameLayout.onMeasure() reads every child's LayoutParams through an unchecked
# cast — ((FrameLayout.LayoutParams) child.getLayoutParams()) — and ScrollView /
# HorizontalScrollView inherit that exact code. Give such a container a
# LinearLayout.LayoutParams child (or give a LinearLayout a FrameLayout
# .LayoutParams child) and the process dies inside the framework's own measure
# pass, where no try/catch of ours can ever run. The only real fix is to make the
# mismatch impossible to write — which is what this gate does.
#
# WHAT IT PROVES, per .java file under src/:
#   1. it types every container variable, field and helper-return (FrameLayout,
#      ScrollView, HorizontalScrollView, LinearLayout, ViewGroup) plus every
#      class that EXTENDS one of them (WebBrowserView extends FrameLayout, so a
#      bare `LayoutParams` inside it means FrameLayout.LayoutParams),
#   2. it resolves every addView(child[, index], params) and every
#      setLayoutParams(params) LayoutParams argument — a literal, a local, a
#      field, a helper return or an inherited bare LayoutParams,
#   3. it resolves the CONTAINER of every child (the addView edge, nearest
#      declaration first, so shadowed locals and per-loop re-declarations are
#      handled),
#   4. and it fails the build on a mismatch, on an unchecked
#      (X.LayoutParams) view.getLayoutParams() cast whose container is not an X,
#      and on any call whose types it cannot prove (an unprovable layout call is
#      a latent crash, not a pass).
#
# It also gates the three other framework-level traps v3.3.1 audited:
#   · setBackgroundResource(<raw ARGB int>) — Resources$NotFoundException,
#   · Uri.fromFile(...)                     — FileUriExposedException (API 24+),
#   · registerReceiver(...) without RECEIVER_NOT_EXPORTED/RECEIVER_EXPORTED
#     — SecurityException on Android 14+ (targetSdk 34+).
#
# Usage:  python3 lab/verify_layouts.py [src-root]        (default: src)
# Exit 0 = a LayoutParams ClassCastException is structurally impossible to ship.

import pathlib
import re
import sys

SRC_DEFAULT = 'src'

FRAME_LIKE = {'FrameLayout', 'ScrollView', 'HorizontalScrollView'}
LINEAR_LIKE = {'LinearLayout'}
ANY_LIKE = {'ViewGroup', 'View', 'Object'}

LP_FRAME = 'FrameLayout.LayoutParams'
LP_LINEAR = 'LinearLayout.LayoutParams'
LP_VG = 'ViewGroup.LayoutParams'

# ── comment / literal stripping (line numbers must survive) ─────────────────
_BLOCK = re.compile(r'/\*.*?\*/', re.S)
_LINE_C = re.compile(r'//[^\n]*')
_STR = re.compile(r'"(?:\\.|[^"\\\n])*"')      # never crosses a line
_CHR = re.compile(r"'\\.'|'[^'\\\n]'")

CONTAINERS = r'(?:android\.widget\.)?(?:FrameLayout|LinearLayout|ScrollView|HorizontalScrollView|ViewGroup)'

DECL = re.compile(r'\b(' + CONTAINERS + r')\s+([A-Za-z_]\w*)\s*(?==|;|,|\))')
NEW = re.compile(r'\b([A-Za-z_]\w*)\s*=\s*new\s+' + CONTAINERS + r'\s*\(')
NEWT = re.compile(r'\b([A-Za-z_]\w*)\s*=\s*new\s+(' + CONTAINERS + r')\s*\(')
ASSIGN_CALL = re.compile(r'\b([A-Za-z_]\w*)\s*=\s*([A-Za-z_]\w*)\s*\(')
GETPARENT_ALIAS = re.compile(r'\b([A-Za-z_]\w*)\s*=\s*\(\s*ViewGroup\s*\)\s*'
                             r'([A-Za-z_]\w*)\s*\.\s*getParent\s*\(\s*\)')

LP_DECL = re.compile(r'\b((?:FrameLayout|LinearLayout|ViewGroup)\.LayoutParams)\s+([A-Za-z_]\w*)\s*(?==|;|,|\))')
LP_BARE_DECL = re.compile(r'\bLayoutParams\s+([A-Za-z_]\w*)\s*(?==|;|,|\))')
LP_NEW = re.compile(r'\b([A-Za-z_]\w*)\s*=\s*new\s+((?:FrameLayout|LinearLayout|ViewGroup)\.LayoutParams)\s*\(')
LP_BARE_NEW = re.compile(r'\b([A-Za-z_]\w*)\s*=\s*new\s+LayoutParams\s*\(')

METHOD = re.compile(r'\b(FrameLayout|LinearLayout|ScrollView|HorizontalScrollView|ViewGroup|View|'
                    r'(?:FrameLayout|LinearLayout|ViewGroup)\.LayoutParams)\s+([A-Za-z_]\w*)\s*\(')
CLASS_EXT = re.compile(r'\b(?:class|interface)\s+([A-Za-z_]\w*)\s+extends\s+'
                       r'(?:android\.widget\.)?(FrameLayout|LinearLayout|ScrollView|HorizontalScrollView|ViewGroup)\b')
ADDVIEW = re.compile(r'\b([A-Za-z_]\w*|this)\s*\.\s*addView\s*\(')
# a bare addView(...) inside a class that EXTENDS a container (WebBrowserView
# extends FrameLayout) — the receiver is `this`, so the container is the superclass.
ADDVIEW_BARE = re.compile(r'(?<![.\w])addView\s*\(')
SETLP = re.compile(r'\b([A-Za-z_]\w*)\s*\.\s*setLayoutParams\s*\(')
CAST_GETLP = re.compile(r'\(\s*((?:FrameLayout|LinearLayout|ViewGroup)\.LayoutParams)\s*\)\s*'
                        r'([A-Za-z_]\w*)\s*\.\s*getLayoutParams\s*\(\s*\)')

BAD_BG = re.compile(r'setBackgroundResource\s*\(\s*0[xX][0-9a-fA-F]+\s*\)')
URI_FILE = re.compile(r'Uri\s*\.\s*fromFile\s*\(')
REG_RX = re.compile(r'\bregisterReceiver\s*\(')


def strip(src):
    out = _BLOCK.sub(lambda m: '\n' * m.group(0).count('\n'), src)
    out = _LINE_C.sub('', out)
    out = _STR.sub('""', out)
    out = _CHR.sub("'x'", out)
    return out


def call_args(text, open_idx):
    depth, i, n = 0, open_idx, len(text)
    while i < n:
        c = text[i]
        if c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                return text[open_idx + 1:i]
        i += 1
    return ''


def split_top(args):
    out, depth, cur = [], 0, ''
    for c in args:
        if c in '([{':
            depth += 1
        elif c in ')]}':
            depth -= 1
        if c == ',' and depth == 0:
            out.append(cur.strip())
            cur = ''
        else:
            cur += c
    if cur.strip():
        out.append(cur.strip())
    return out


def norm(t):
    return t.replace('android.widget.', '') if t else t


class Scope:
    """Nearest-preceding-declaration type environment for one source file."""

    def __init__(self):
        self.events = []          # (pos, name, type)   container vars
        self.lp = []              # (pos, name, type)   LayoutParams vars
        self.edges = []           # (pos, child, container_type)  addView parents
        self.aliases = []         # (pos, name, src_var)  name = (ViewGroup) src.getParent()

    def add(self, bucket, pos, name, typ):
        if typ:
            bucket.append((pos, name, typ))

    @staticmethod
    def _lookup(bucket, name, pos, fallback=True):
        best = None
        for p, n, t in bucket:
            if n != name:
                continue
            if p < pos:
                if best is None or p > best[0]:
                    best = (p, t)
        if best:
            return best[1]
        if fallback:                     # a field declared further down the file
            for p, n, t in bucket:
                if n == name:
                    return t
        return None

    def var(self, name, pos):
        alias = self._lookup(self.aliases, name, pos, fallback=False)
        if alias:
            # `ViewGroup p = (ViewGroup) x.getParent();` — p IS x's container.
            return self.parent(alias, pos)
        return self._lookup(self.events, name, pos)

    def lpvar(self, name, pos):
        return self._lookup(self.lp, name, pos)

    def parent(self, name, pos):
        # NO fallback: a parent edge must precede the use. A view added in a
        # different method is a different view (locals shadow), and guessing it
        # is exactly how a false negative — or a false positive — creeps in.
        return self._lookup(self.edges, name, pos, fallback=False)


def analyse(path, super_map, fails, stats):
    raw = path.read_text(encoding='utf-8')
    text = strip(raw)
    rel = str(path).replace('\\', '/')

    own_super = None
    for cls, sup in super_map.items():
        if re.search(r'\bclass\s+' + re.escape(cls) + r'\b', text):
            own_super = sup

    methods = {}
    for m in METHOD.finditer(text):
        methods[m.group(2)] = norm(m.group(1))

    sc = Scope()

    # ── container declarations / assignments ────────────────────────────────
    for m in DECL.finditer(text):
        sc.add(sc.events, m.start(2), m.group(2), norm(m.group(1)))
    for m in NEWT.finditer(text):
        sc.add(sc.events, m.start(1), m.group(1), norm(m.group(2)))
    for m in ASSIGN_CALL.finditer(text):
        r = methods.get(m.group(2))
        if r and not r.endswith('LayoutParams'):
            sc.add(sc.events, m.start(1), m.group(1), r)
    for m in GETPARENT_ALIAS.finditer(text):
        sc.add(sc.aliases, m.start(1), m.group(1), m.group(2))

    # ── LayoutParams declarations / assignments ─────────────────────────────
    for m in LP_DECL.finditer(text):
        sc.add(sc.lp, m.start(2), m.group(2), m.group(1))
    for m in LP_NEW.finditer(text):
        sc.add(sc.lp, m.start(1), m.group(1), m.group(2))
    inherited = LP_FRAME if own_super in FRAME_LIKE else (LP_LINEAR if own_super in LINEAR_LIKE else None)
    if inherited:
        for m in LP_BARE_DECL.finditer(text):
            sc.add(sc.lp, m.start(1), m.group(1), inherited)
        for m in LP_BARE_NEW.finditer(text):
            sc.add(sc.lp, m.start(1), m.group(1), inherited)

    def lp_of(expr, pos):
        e = expr.strip()
        m = re.search(r'new\s+((?:FrameLayout|LinearLayout|ViewGroup)\.LayoutParams)\s*\(', e)
        if m:
            return m.group(1)
        if re.search(r'new\s+LayoutParams\s*\(', e):
            return inherited
        m = re.match(r'^\(?\s*([A-Za-z_]\w*)\s*\)?$', e)
        if m:
            got = sc.lpvar(m.group(1), pos)
            if got:
                return got
        m = re.match(r'^([A-Za-z_]\w*)\s*\(', e)
        if m:
            r = methods.get(m.group(1))
            return r if r and r.endswith('LayoutParams') else None
        return None

    def container_of(expr, pos):
        e = expr.strip()
        # `(ViewGroup) someView.getParent()` — the runtime parent is the container
        # someView was added to, so resolve THAT, not the cast's own type. This is
        # exactly the v3.3.0 ripple bug (hudCenter's parent is a LinearLayout).
        m = re.match(r'^\(\s*[A-Za-z_.]+\s*\)\s*([A-Za-z_]\w*)\s*\.\s*getParent\s*\(\s*\)$', e)
        if m:
            return sc.parent(m.group(1), pos)
        e = re.sub(r'^\(\s*[A-Za-z_.]+\s*\)\s*', '', e)          # leading cast
        m = re.match(r'^([A-Za-z_]\w*)$', e)
        if m:
            v = m.group(1)
            if v == 'this':
                return own_super
            return sc.var(v, pos) or (v if v in FRAME_LIKE | LINEAR_LIKE | ANY_LIKE else None)
        m = re.match(r'^new\s+' + CONTAINERS + r'\s*\(', e)
        if m:
            return norm(re.match(r'^new\s+(' + CONTAINERS + r')', e).group(1))
        m = re.match(r'^([A-Za-z_]\w*)\s*\(', e)
        if m:
            return methods.get(m.group(1))
        m = re.search(r'\(\s*([A-Za-z_]\w*)\s*\)\s*$', e)         # (FrameLayout) view
        if m:
            return m.group(1)
        return None

    def compatible(container, lp):
        if container in ANY_LIKE:
            return True
        if lp == LP_VG:
            return True
        if container in FRAME_LIKE:
            return lp == LP_FRAME
        if container in LINEAR_LIKE:
            return lp == LP_LINEAR
        return True            # a custom container: not gated by this rule

    # ── addView edges first (the setLayoutParams pass needs them) ───────────
    calls = []
    for m in ADDVIEW.finditer(text):
        line = text.count('\n', 0, m.start()) + 1
        args = split_top(call_args(text, m.end() - 1))
        if not args:
            continue
        container = container_of(m.group(1), m.start())
        child = args[0]
        cm = re.match(r'^([A-Za-z_]\w*)$', child.strip())
        if cm and container:
            sc.add(sc.edges, m.start(), cm.group(1), container)
        calls.append((line, m.group(1), container, args))

    for m in ADDVIEW_BARE.finditer(text):
        line = text.count('\n', 0, m.start()) + 1
        args = split_top(call_args(text, m.end() - 1))
        if not args:
            continue
        container = own_super
        child = args[0]
        cm = re.match(r'^([A-Za-z_]\w*)$', child.strip())
        if cm and container:
            sc.add(sc.edges, m.start(), cm.group(1), container)
        calls.append((line, 'this', container, args))

    for line, container_name, container, args in calls:
        stats['calls'] += 1
        if len(args) < 2:
            continue                                    # addView(child): framework types it
        params = args[-1]
        if len(args) == 2 and re.match(r'^\d+$', params):
            continue                                    # addView(child, index)
        pos = text.count('\n', 0, text.find(container_name))   # only for messages
        lp = lp_of(params, _pos_of(text, container_name, line))
        if container is None:
            fails.append(f'{rel}:{line}: addView on an unresolvable container '
                         f'({container_name}) — the LayoutParams match cannot be proven')
            continue
        if lp is None:
            fails.append(f'{rel}:{line}: addView(..., {params}) — LayoutParams type '
                         f'unresolvable, cannot prove it matches a {container}')
            continue
        if not compatible(container, lp):
            fails.append(f'{rel}:{line}: {container}.addView(...) given {lp} '
                         f'-> ClassCastException in onMeasure')

    # ── setLayoutParams ────────────────────────────────────────────────────
    for m in SETLP.finditer(text):
        line = text.count('\n', 0, m.start()) + 1
        var = m.group(1)
        args = split_top(call_args(text, m.end() - 1))
        if not args:
            continue
        stats['calls'] += 1
        parent = sc.parent(var, m.start())
        lp = lp_of(args[0], m.start())
        if parent is None and sc.var(var, m.start()) is not None:
            # a container var with no addView edge: its own parent is unknown
            parent = None
        if parent is None:
            if sc.var(var, m.start()) in (FRAME_LIKE | LINEAR_LIKE):
                fails.append(f'{rel}:{line}: {var}.setLayoutParams({args[0]}) — {var} is a '
                             f'container whose parent is unknown, the match cannot be proven')
            continue
        if lp is None:
            fails.append(f'{rel}:{line}: {var}.setLayoutParams({args[0]}) — LayoutParams type '
                         f'unresolvable, cannot prove it matches a {parent}')
            continue
        if not compatible(parent, lp):
            fails.append(f'{rel}:{line}: {var} (child of {parent}) set to {lp} '
                         f'-> ClassCastException in onMeasure')

    # ── unchecked (X.LayoutParams) view.getLayoutParams() casts ────────────
    for m in CAST_GETLP.finditer(text):
        line = text.count('\n', 0, m.start()) + 1
        want, var = m.group(1), m.group(2)
        stats['calls'] += 1
        parent = sc.parent(var, m.start())
        if parent is None:
            fails.append(f'{rel}:{line}: ({want}) {var}.getLayoutParams() — {var}\'s '
                         f'container is unknown, the cast cannot be proven safe')
        elif not compatible(parent, want):
            fails.append(f'{rel}:{line}: ({want}) {var}.getLayoutParams() but {var} lives in a '
                         f'{parent} -> ClassCastException at runtime')

    # ── the three framework traps ──────────────────────────────────────────
    for m in BAD_BG.finditer(text):
        line = text.count('\n', 0, m.start()) + 1
        fails.append(f'{rel}:{line}: setBackgroundResource(<raw ARGB>) — that argument is a '
                     f'colour, not a resource id; use setBackgroundColor(...)')
    for m in URI_FILE.finditer(text):
        line = text.count('\n', 0, m.start()) + 1
        fails.append(f'{rel}:{line}: Uri.fromFile(...) — FileUriExposedException on API 24+')
    for m in REG_RX.finditer(text):
        line = text.count('\n', 0, m.start()) + 1
        tail = text[m.end():m.end() + 800]
        if not re.search(r'RECEIVER_NOT_EXPORTED|RECEIVER_EXPORTED|ContextCompat', tail):
            fails.append(f'{rel}:{line}: registerReceiver(...) without an explicit '
                         f'RECEIVER_NOT_EXPORTED/RECEIVER_EXPORTED flag — SecurityException '
                         f'on Android 14+')


def _pos_of(text, name, line):
    """Character offset of a given source line (1-based)."""
    idx = 0
    for _ in range(line - 1):
        nxt = text.find('\n', idx)
        if nxt < 0:
            return len(text)
        idx = nxt + 1
    return idx


SELFTEST_CASES = [
    # (label, source, must_fail)
    ("clean FrameLayout child", """
class A { void f(FrameLayout root) {
  LinearLayout box = new LinearLayout(c);
  root.addView(box, new FrameLayout.LayoutParams(-1, -1));
} }""", False),
    ("LinearLayout params into FrameLayout", """
class A { void f(FrameLayout root) {
  LinearLayout box = new LinearLayout(c);
  root.addView(box, new LinearLayout.LayoutParams(-1, -1));
} }""", True),
    ("FrameLayout params into LinearLayout", """
class A { void f(LinearLayout root) {
  TextView box = new TextView(c);
  root.addView(box, new FrameLayout.LayoutParams(-1, -1));
} }""", True),
    ("LinearLayout params into a ScrollView", """
class A { void f(ScrollView sc) {
  LinearLayout box = new LinearLayout(c);
  sc.addView(box, new LinearLayout.LayoutParams(-1, -2));
} }""", True),
    ("params variable mismatch", """
class A { void f(FrameLayout root) {
  LinearLayout box = new LinearLayout(c);
  LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -1);
  root.addView(box, lp);
} }""", True),
    ("setLayoutParams mismatch", """
class A { void f(FrameLayout root) {
  LinearLayout box = new LinearLayout(c);
  LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -1);
  root.addView(box, lp);
  box.setLayoutParams(lp);
} }""", True),
    ("unchecked cast into the wrong parent", """
class A { void f(LinearLayout root) {
  TextView v = new TextView(c);
  root.addView(v, new LinearLayout.LayoutParams(-1, -2));
  FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
} }""", True),
    ("ripple mounted on getParent()", """
class A { void f(LinearLayout root, LinearLayout center) {
  root.addView(center, new LinearLayout.LayoutParams(-1, 0, 1f));
  ViewGroup parent = (ViewGroup) center.getParent();
  parent.addView(new TextView(c), new FrameLayout.LayoutParams(-1, -1));
} }""", True),
    ("bare LayoutParams inside a FrameLayout subclass", """
class W extends FrameLayout { void f(LinearLayout column) {
  addView(column, new LayoutParams(-1, -1));
  addView(new TextView(c), new LinearLayout.LayoutParams(-2, -2));
} }""", True),
    ("raw ARGB background", """
class A { void f(TextView v) { v.setBackgroundResource(0xFF112233); } }""", True),
    ("Uri.fromFile", """
class A { void f(File f) { Uri u = Uri.fromFile(f); } }""", True),
    ("receiver without an export flag", """
class A { void f(Context c) { c.registerReceiver(rx, filter); } }""", True),
    ("receiver with RECEIVER_NOT_EXPORTED", """
class A { void f(Context c) {
  c.registerReceiver(rx, filter, Context.RECEIVER_NOT_EXPORTED);
} }""", False),
]


def selftest():
    """Prove the analyzer both ACCEPTS clean code and REJECTS every bug shape."""
    import tempfile
    bad = 0
    for label, src, must_fail in SELFTEST_CASES:
        with tempfile.TemporaryDirectory() as td:
            p = pathlib.Path(td) / 'A.java'
            p.write_text(src, encoding='utf-8')
            fails, stats = [], {'calls': 0}
            supers = {}
            for m in CLASS_EXT.finditer(strip(src)):
                supers[m.group(1)] = norm(m.group(2))
            analyse(p, supers, fails, stats)
            got = bool(fails)
            ok = got == must_fail
            if not ok:
                bad += 1
            print(('  ok   ' if ok else '  BAD  ') + label
                  + (f'  ({fails[0].split(": ", 1)[-1]})' if fails else ''))
    print(f'verify_layouts selftest: {len(SELFTEST_CASES) - bad}/{len(SELFTEST_CASES)} cases behaved '
          f'as expected')
    return 0 if bad == 0 else 1


def main():
    if '--selftest' in sys.argv:
        return selftest()
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else SRC_DEFAULT)
    if not root.exists():
        raise SystemExit(f'verify_layouts: source root not found: {root}')
    files = sorted(root.rglob('*.java'))
    if not files:
        raise SystemExit(f'verify_layouts: no .java files under {root}')

    super_map = {}
    for p in files:
        for m in CLASS_EXT.finditer(strip(p.read_text(encoding='utf-8'))):
            super_map[m.group(1)] = norm(m.group(2))

    fails, stats = [], {'calls': 0}
    for p in files:
        analyse(p, super_map, fails, stats)

    print(f'verify_layouts: scanned {len(files)} java files, {stats["calls"]} layout calls, '
          f'{len(fails)} problem(s)')
    for f in fails[:60]:
        print('  FAIL ' + f)
    if len(fails) > 60:
        print(f'  ... and {len(fails) - 60} more')
    if fails:
        print('verify_layouts: FAILED — a LayoutParams ClassCastException is still shippable')
        return 1
    print('verify_layouts: OK — every addView/setLayoutParams/getLayoutParams cast in src/ '
          'is type-correct; no raw-ARGB background, no Uri.fromFile, no un-flagged receiver')
    return 0


if __name__ == '__main__':
    sys.exit(main())
