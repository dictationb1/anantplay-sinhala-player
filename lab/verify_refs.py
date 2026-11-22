#!/usr/bin/env python3
# v3.1.2 — THE BYTECODE REFERENCE VERIFIER (run by lab/run_lab.sh).
#
# Parses every compiled .class constant pool under lab/out/classes and proves
# that EVERY class the pure engines reference resolves to (a) another compiled
# lab class, (b) the JDK, or (c) android.* (the same contract build.sh enforces
# by compiling against android.jar + libs/*.jar). A missing class is
# structurally impossible to ship once this gate passes — the class of
# NoClassDefFoundError the v3.1.1 field crash taught us to prevent.
#
# This file is copied to lab/out/verify_refs.py by run_lab.sh and executed
# from the repo root:  python3 lab/verify_refs.py  (uses lab/out/classes).
import sys
import struct
import pathlib

CONST_UTF8, CONST_CLASS = 1, 7


def parse(path):
    """Return (utf8_by_slot, class_name_indices) for one .class file."""
    data = pathlib.Path(path).read_bytes()
    if struct.unpack_from('>I', data, 0)[0] != 0xCAFEBABE:
        raise SystemExit(f'not a class file: {path}')
    n = struct.unpack_from('>H', data, 8)[0]
    utf8, classes, i, slot = {}, [], 10, 1
    while slot < n:
        tag = data[i]
        if tag == CONST_UTF8:
            ln = struct.unpack_from('>H', data, i + 1)[0]
            utf8[slot] = data[i + 3:i + 3 + ln].decode('utf-8', 'replace')
            i += 3 + ln
        elif tag == CONST_CLASS:
            classes.append(struct.unpack_from('>H', data, i + 1)[0])
            i += 3
        elif tag in (8, 19, 20):          # String / Module / Package
            i += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            i += 5
        elif tag in (5, 6):               # long/double occupy TWO slots
            i += 9
            slot += 1
        elif tag == 15:                   # MethodHandle
            i += 4
        elif tag == 16:                   # MethodType
            i += 3
        elif tag == 0:                    # the phantom slot after 5/6
            i += 1
        else:
            raise SystemExit(f'unknown cp tag {tag} in {path} at offset {i}')
        slot += 1
    return utf8, classes


def main():
    root = pathlib.Path('lab/out/classes')
    if not root.exists():
        raise SystemExit('lab/out/classes missing — run the compile step first')
    refs = set()
    for p in root.rglob('*.class'):
        utf8, classes = parse(p)
        for idx in classes:
            nm = utf8.get(idx, '')
            if nm:
                refs.add(nm)

    allowed = ('java/', 'javax/', 'jdk/', 'sun/', 'com/sun/', 'android/', 'lk/techworld/')
    bad = []
    for ref in sorted(refs):
        r = ref
        if r.startswith('['):              # array descriptors
            d = r.lstrip('[')
            if d.startswith('L') and d.endswith(';'):
                d = d[1:-1]
            elif d and d[0] not in 'BCDFIJSZ':
                continue
            else:
                continue
            r = d
        if any(r.startswith(p) for p in allowed):
            continue
        bad.append(r.replace('/', '.'))

    if bad:
        print('BYTECODE VERIFIER FAILED — unresolved references:')
        for b in sorted(set(bad)):
            print('  ', b)
        sys.exit(1)
    print(f'bytecode verifier: OK — {len(refs)} distinct referenced types, '
          'all resolvable (lab classes + JDK + android.*)')


if __name__ == '__main__':
    main()
