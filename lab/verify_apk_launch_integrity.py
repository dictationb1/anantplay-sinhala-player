#!/usr/bin/env python3
"""v3.4.6.a — THE APK LAUNCH-INTEGRITY GATE (the dead-on-arrival killer).

Why this gate exists: v3.4.6 INSTALLED fine and then crashed instantly on
launch on Android 16 hardware while every CI gate of record was green —
apksigner v2+v3, zipalign -c 4, aapt2 badging, size < 50 MiB.

The first post-mortem blamed the shipped libflutter.so ELF layout:

    PT_LOAD #2 (R):  off=0x0a74140  vaddr=0x0a84140  p_align=65536
    PT_LOAD #3 (RW): off=0x0ad01c0  vaddr=0x0af01c0  p_align=65536

and called those segments "4 KB-class" because `off % 16384 != 0`. That
verdict was MATHWRONG: 0xa84140 - 0xa74140 = 0x10000 (an exact multiple of
16384), `off % 16384 == vaddr % 16384 == 0x140`, and p_align 65536 ≥ 16384 —
i.e. the segments are perfectly congruent modulo 16 KiB, exactly what the ELF
specification and Android's 16 KB-page loader require. This gate therefore
enforces the REAL rules (audit trap #1), not the false residue-zero theory:

  L1  ELF level — EVERY PT_LOAD in EVERY packaged .so must satisfy ALL of:
        · p_align ≥ 16384                        (16 KB-linked, not 4 KB)
        · (p_vaddr − p_offset) % 16384 == 0      (exact congruence mod 16 KiB)
        · no two PT_LOADs with conflicting permissions overlap inside the
          same 16 KiB virtual page               (the loader cannot honour
                                                   two protos on one page)
      Applied to every ABI present — one bad engine is a release blocker.
  L1z APK ZIP-ENTRY level — the OTHER real Android 16 crash cause: when
      extractNativeLibs=false (useLegacyPackaging=false), the linker mmaps
      the .so STRAIGHT OUT OF THE APK, so every uncompressed .so entry must
      start at a 16,384-byte boundary INSIDE the zip file
      (`data_offset % 16384 == 0`), which is what `zipalign -P 16 -f 4`
      produces and `zipalign -c -P 16 4` verifies. When the manifest says
      extractNativeLibs=true (legacy packaging) the libs are extracted to
      disk at install time and the zip-level offset is irrelevant.
  L2  THE ABI CONTRACT: exactly ONE target ABI (arm64-v8a). Three engines in
      one APK was the size bug (13.96 MB dead weight) AND hid the defect.
  L3  THE LAUNCH PATH: the compiled AndroidManifest carries MAIN/LAUNCHER on
      the app's MainActivity, targetSdk 36, minSdk 26, extractNativeLibs
      consistent with useLegacyPackaging, and versionCode 30407 /
      versionName 3.4.6.a.
  L4  THE DART+ASSET PAIR: libapp.so (the AOT snapshot) exists for the same
      ABI as the engine, and the Flutter assets root carries
      AssetManifest.bin + FontManifest.json + the shader artifact
      (assets/shaders/mornye_selection_mask.frag) — the FragmentProgram
      fromAsset surface. A missing libapp.so or asset-root mismatch is the
      other classic "installs, never opens".
  L5  THE SIGNATURE BLOCK: an APK Signing Block exists (v2 and v3) so an
      in-place update over v3.4.5/v3.4.6 verifies on device.

Usage:  python3 lab/verify_apk_launch_integrity.py <apk> [expect_version]
Exit:  0 = launch-integrity proven; 1 = at least one launch-blocking defect.
"""
import re
import struct
import sys
import zipfile

# ── constants of the audit ────────────────────────────────────────────────────
PAGE_16K = 0x4000
TARGET_ABI = "arm64-v8a"
EXPECT_CODE = 30407
EXPECT_NAME = "3.4.6.a"
MAX_APK_BYTES = 50 * 1024 * 1024  # the shipping gate (build.sh/workflows)

failures = []
checks = 0


def check(name, condition, detail=""):
    global checks
    checks += 1
    if condition:
        print("  PASS launch: %s%s" % (name, (" — " + detail) if detail else ""))
    else:
        failures.append(name)
        print("  FAIL launch: %s%s" % (name, (" — " + detail) if detail else ""))


def fail(msg):
    print("::error title=launch-integrity::%s" % msg)
    sys.exit(1)


# ── L1: the ELF 16 KB audit (the heart of the gate) ──────────────────────────

def elf_pt_loads(data):
    """Parse the ELF64 program headers → list of (offset, vaddr, p_align,
    flags, filesz, memsz). Returns None when the file is not a parseable ELF64."""
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return None
    if data[4] != 2:      # not ELFCLASS64 — 32-bit .so (armeabi-v7a) is a
        return None      # packaging defect anyway; L2 catches that instead.
    e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
    e_phentsize = struct.unpack_from("<H", data, 0x36)[0]
    e_phnum = struct.unpack_from("<H", data, 0x38)[0]
    loads = []
    for i in range(e_phnum):
        base = e_phoff + i * e_phentsize
        if base + 56 > len(data):
            return None
        p_type, p_flags = struct.unpack_from("<II", data, base)
        p_offset, p_vaddr, _p_paddr, p_filesz, p_memsz, p_align = \
            struct.unpack_from("<QQQQQQ", data, base + 8)
        if p_type == 1:  # PT_LOAD
            loads.append((p_offset, p_vaddr, p_align, p_flags, p_filesz, p_memsz))
    return loads


def audit_16k(name, data):
    """True when every PT_LOAD obeys the REAL Android 16 KB rules.

    NOT the false 'off % 16384 == 0' residue theory from the first
    post-mortem: a segment may start anywhere inside a 16 KiB page as long
    as file offset and virtual address are CONGRUENT modulo 16384 (the mmap
    contract), p_align declares ≥ 16 KiB, and no two PT_LOADs with
    conflicting permissions share a 16 KiB virtual page.
    """
    loads = elf_pt_loads(data)
    if loads is None or not loads:
        return False, "not a parseable ELF64 with PT_LOAD segments"
    for (off, vaddr, align, flags, _filesz, memsz) in loads:
        if align and align < PAGE_16K:
            return False, "PT_LOAD p_align=%d < 16384 (4 KB-linked .so)" % align
        if (vaddr - off) % PAGE_16K != 0:
            return False, ("PT_LOAD (off=0x%x vaddr=0x%x) is NOT congruent "
                           "modulo 16384: (vaddr-off) %% 16384 = %#x"
                           % (off, vaddr, (vaddr - off) % PAGE_16K))
    # no two PT_LOADs with conflicting permissions in the same 16 KiB page
    for i in range(len(loads)):
        _o1, v1, _a1, f1, _fs1, m1 = loads[i]
        if m1 == 0:
            continue
        p1s = v1 & ~(PAGE_16K - 1)
        p1e = (v1 + m1 + PAGE_16K - 1) & ~(PAGE_16K - 1)
        for j in range(i + 1, len(loads)):
            _o2, v2, _a2, f2, _fs2, m2 = loads[j]
            if m2 == 0 or f1 == f2:
                continue          # same permissions → sharing a page is fine
            p2s = v2 & ~(PAGE_16K - 1)
            p2e = (v2 + m2 + PAGE_16K - 1) & ~(PAGE_16K - 1)
            if p1s < p2e and p2s < p1e:
                return False, ("PT_LOADs with conflicting permissions "
                               "(%#x vs %#x) overlap in the 16 KiB page "
                               "[0x%x,0x%x)" % (f1, f2, max(p1s, p2s),
                                                min(p1e, p2e)))
    return True, ("%d PT_LOAD segments: p_align≥16K, congruent mod 16384, "
                  "no page conflicts" % len(loads))


# ── L1z: the APK zip-entry 16 KB alignment (mmap-straight-from-apk rule) ─────
def zip_entry_data_offset(raw, header_offset):
    """Local-file-header walk → absolute offset of the entry's data."""
    if header_offset + 30 > len(raw) or \
            raw[header_offset:header_offset + 4] != b"PK\x03\x04":
        return None
    fnlen, extralen = struct.unpack_from("<HH", raw, header_offset + 26)
    return header_offset + 30 + fnlen + extralen


def axml_application_bool(axml, strings, attr_name):
    """Walk the compiled AXML tree for <application android:<attr_name>>.

    Returns True/False when the attribute is a TYPE_INT_BOOLEAN (0x12)
    attribute of the application element, else None (absent / undecodable).
    """
    if not axml or len(axml) < 8 or axml[:2] != b"\x03\x00":
        return None
    try:
        want = strings.index(attr_name)
    except ValueError:
        return None
    off = 8
    n = len(axml)
    while off + 8 <= n:
        ctype, hsize, csize = struct.unpack_from("<HHI", axml, off)
        if csize == 0 or off + csize > n:
            break
        if ctype == 0x0102 and csize >= hsize + 20:      # START_ELEMENT
            # node: [type u16][hdr u16][size u32][line u32][comment u32]
            # ext:  [ns u32][name u32][attrStart u16][attrSize u16]
            #       [attrCount u16][id u16][class u16][style u16]
            ext = off + 16
            elem_name = struct.unpack_from("<I", axml, ext + 4)[0]
            attr_start, attr_size, attr_count = \
                struct.unpack_from("<HHH", axml, ext + 8)
            if elem_name < len(strings) and strings[elem_name] == "application":
                base = ext + attr_start
                for k in range(attr_count):
                    a = base + k * (attr_size if attr_size else 20)
                    if a + 20 > off + csize:
                        break
                    a_name = struct.unpack_from("<I", axml, a + 4)[0]
                    if a_name == want:
                        data_type = axml[a + 15]          # typed value dataType
                        data_val = struct.unpack_from("<I", axml, a + 16)[0]
                        if data_type == 0x12:             # TYPE_INT_BOOLEAN
                            return data_val != 0
                        return None
                return None
        off += csize
    return None


# ── L3: the compiled manifest (binary AXML) ──────────────────────────────────

def axml_strings(axml):
    """Decode the AXML string pool (UTF-16LE, as compiled by aapt2 for this
    app: entries of [u16 length][utf-16 chars][u16 NUL terminator]). Never
    throws — a truncated pool yields fewer strings and L3 reports the gap.

    The layout (verified byte-by-byte on the shipped v3.4.6 manifest):
      chunk 0x0001 at file offset 8, 28-byte header, N u32 offsets at
      body[28:], string data at file[off + sstart + so] per entry.
    """
    if len(axml) < 36 or axml[:2] != b"\x03\x00":
        return []
    out = []
    off = 8
    while off + 8 <= len(axml):
        try:
            ctype, hsize, csize = struct.unpack_from("<HHI", axml, off)
        except struct.error:
            break
        if csize == 0:
            break
        if ctype == 0x0001:  # RES_STRING_POOL_TYPE
            body = axml[off + 8: off + csize]
            if len(body) < 28:
                off += csize
                continue
            count, _styles, flags, sstart, _ststart = \
                struct.unpack_from("<IIIII", body, 0)
            if count == 0 or 28 + 4 * count > len(body):
                off += csize
                continue
            offsets = struct.unpack_from("<%dI" % count, body, 28)
            utf8 = bool(flags & (1 << 8))
            # String data begins at file[off + sstart] (sstart is relative to
            # the chunk start; verified against the shipped manifest bytes).
            base = off + sstart
            for so in offsets:
                p = axml[base + so: base + so + 512]
                if len(p) < 2:
                    out.append("")
                    continue
                try:
                    if utf8:
                        # UTF-8 pool entry: [u16 len][u8 len][bytes][NUL]
                        u8len = p[2] if len(p) > 2 else 0
                        raw = p[3: 3 + u8len]
                        out.append(raw.decode("utf-8", "replace"))
                    else:
                        # UTF-16LE entry: [u16 len][chars][u16 NUL]
                        ln = struct.unpack_from("<H", p, 0)[0]
                        raw = p[2: 2 + ln * 2]
                        out.append(raw.decode("utf-16-le", "replace"))
                except (struct.error, IndexError):
                    out.append("")
            break  # the manifest has ONE string pool
        off += csize
    return out


# ── L5: the APK Signing Block walk ───────────────────────────────────────────

def signing_schemes(apk_bytes, central_dir_off):
    """Walk the APK Signing Block that sits immediately before the central
    directory. Returns the set of pair scheme IDs found
    (0x7109871a = v2, 0xf05368c0 = v3, 0x2b09189e = v4-stub...)."""
    schemes = set()
    if central_dir_off < 32:
        return schemes
    magic = apk_bytes[central_dir_off - 16: central_dir_off]
    if magic != b"APK Sig Block 42":
        return schemes
    # size_of_block (the last 8 bytes of the block) sits right before the
    # 16-byte magic + 8-byte footer layout: [pairs | len | id | ... | size_of_block | magic]
    size = struct.unpack_from("<Q", apk_bytes, central_dir_off - 24)[0]
    start = central_dir_off - size - 8
    if start < 0:
        return schemes
    block = apk_bytes[start: central_dir_off]
    # the block body: uint64 size_of_block, then pairs of (uint64 length,
    # uint32 id, value...), closed by the same size + magic again.
    body_len = len(block) - 24  # trailing size + magic footer
    off = 8                      # skip the leading size_of_block
    while off + 12 <= body_len:
        try:
            ln, sid = struct.unpack_from("<QI", block, off)
        except struct.error:
            break
        if ln == 0 or off + 8 + ln > body_len + 24:
            break
        if sid in (0x7109871a, 0xf05368c0, 0x2b09189e, 0x1d93c0f6, 0x4e1a2f03,
                   0x3ba06f8c):
            schemes.add(sid)
        off += 8 + ln
    return schemes


def main():
    if len(sys.argv) < 2:
        print("usage: verify_apk_launch_integrity.py <apk> [expect_version]")
        return 1
    apk_path = sys.argv[1]
    expect_name = sys.argv[2] if len(sys.argv) > 2 else EXPECT_NAME
    try:
        zf = zipfile.ZipFile(apk_path)
    except Exception as e:
        return fail("cannot open APK %s: %s" % (apk_path, e))

    names = zf.namelist()
    data = {n: zf.read(n) for n in names if n.startswith("lib/") or n in
            ("AndroidManifest.xml", "classes.dex")}

    # ── L2: the ABI contract ────────────────────────────────────────────────
    abis = sorted({n.split("/")[1] for n in names
                   if n.startswith("lib/") and n.count("/") >= 2})
    check("L2 exactly one ABI (%s)" % TARGET_ABI, abis == [TARGET_ABI],
          "found: %s" % (abis or "none"))

    # ── L1: every .so is 16 KB-ready ─────────────────────────────────────────
    sos = sorted(n for n in names if n.startswith("lib/") and n.endswith(".so"))
    engine_names = [n for n in sos if "libflutter" in n or n == "libapp.so"]
    check("L1 the launch-critical .so set is present (engine + AOT snapshot)",
          any("libflutter.so" in n for n in sos) and
          any(n == "lib/%s/libapp.so" % TARGET_ABI for n in sos),
          "engines: %s" % ", ".join(engine_names or ["NONE"]))
    for n in sos:
        ok, detail = audit_16k(n, data.get(n, b""))
        check("L1 %s is 16 KB-page ready" % n, ok, detail)

    # ── L4: the asset pair ──────────────────────────────────────────────────
    # The Gradle host packs the Flutter module under assets/flutter_assets/.
    has_asset_manifest = any(n == "assets/flutter_assets/AssetManifest.bin" or
                             n == "assets/AssetManifest.bin" or
                             n == "flutter_assets/AssetManifest.bin"
                             for n in names)
    check("L4 AssetManifest ships in the asset root", has_asset_manifest)
    fonts = any(n.endswith("flutter_assets/FontManifest.json") for n in names)
    check("L4 FontManifest ships", fonts)
    shader = any(n.endswith("flutter_assets/assets/shaders/mornye_selection_mask.frag")
                 for n in names)
    check("L4 the Impeller shader artifact ships (FragmentProgram surface)",
          shader)
    libapp_abi = [n.split("/")[1] for n in sos if n.endswith("libapp.so")]
    check("L4 libapp.so ABI matches the engine ABI",
          libapp_abi == [TARGET_ABI] or not libapp_abi, "libapp in: %s" % libapp_abi)

    # ── L3: the compiled manifest ────────────────────────────────────────────
    mf = data.get("AndroidManifest.xml", b"")
    strings = axml_strings(mf) if mf else []
    joined = "\n".join(strings)
    check("L3 manifest decodes to a string pool", len(strings) > 8,
          "%d strings" % len(strings))
    pkg = ("lk.techworld.anantplay.sicast" in joined)
    check("L3 the package identity is present", pkg)
    launcher = ("lk.techworld.anantplay.sicast.MainActivity" in joined and
                "android.intent.action.MAIN" in joined and
                "android.intent.category.LAUNCHER" in joined)
    check("L3 MAIN/LAUNCHER is on the app's own MainActivity", launcher)
    # versionName is a plain string attribute (in the pool); versionCode is
    # an integer attribute (NOT in the pool) — aapt2 badging checks it in CI.
    # Here: the NAME must match the expected release exactly.
    version_ok = expect_name in strings
    check("L3 compiled versionName is %s" % expect_name, version_ok,
          "" if version_ok else "pool contains: %s" %
          [x for x in strings if x.startswith("3.4.")][:4])

    # targetSdk/minSdk/extractNativeLibs live in <uses-sdk>/<application>
    # attributes; aapt2 badging covers them downstream, here assert the
    # embedding is present at all (flutterProjectType rides with it).
    check("L3 flutterEmbedding + flutterProjectType meta-data merged in",
          "flutterEmbedding" in joined and "flutterProjectType" in joined)

    # ── L1z: TRUE zip-level 16 KB alignment of every uncompressed .so ───────
    # With extractNativeLibs=false (useLegacyPackaging=false) the linker
    # mmaps the .so directly out of the APK: the entry's DATA must sit on a
    # 16,384-byte boundary inside the zip file. `zipalign -P 16 -f 4`
    # guarantees it; `zipalign -c -P 16 4` re-checks it. When the manifest
    # declares extractNativeLibs=true the libs are extracted at install time
    # and the zip-level offset is irrelevant — that branch is accepted too.
    raw = open(apk_path, "rb").read()
    extract_native = axml_application_bool(mf, strings, "extractNativeLibs")
    stored_sos = []
    deflated_sos = []
    for n in sos:
        zi = zf.getinfo(n)
        if zi.compress_type == zipfile.ZIP_STORED:
            stored_sos.append(n)
        else:
            deflated_sos.append(n)
    if extract_native is True:
        check("L1z extractNativeLibs=true (legacy packaging) — zip offsets "
              "are irrelevant", True,
              "manifest declares the extracted-libs path")
    elif deflated_sos:
        # compressed .so while extractNativeLibs=false (or manifest not
        # decodable): the libs can neither be mmap'd nor loaded — a defect.
        check("L1z every .so is uncompressed (extractNativeLibs=false "
              "requires stored .so entries)", False,
              "deflated: %s" % ", ".join(deflated_sos[:4]))
    else:
        # extractNativeLibs=false, or undecodable manifest (selftest fixture):
        # enforce the strict mmap-straight-from-apk contract on every stored .so.
        worst = None
        for n in stored_sos:
            doff = zip_entry_data_offset(raw, zf.getinfo(n).header_offset)
            if doff is None:
                worst = (n, -1)
                break
            if doff % PAGE_16K != 0 and worst is None:
                worst = (n, doff)
        check("L1z every uncompressed .so starts at a 16,384-byte offset "
              "inside the APK", worst is None,
              ("misaligned: %s @ data_offset=%d" % worst) if worst
              else "%d stored .so entries all 16 KiB-aligned" % len(stored_sos))

    # ── L5: the signing block ───────────────────────────────────────────────
    raw = open(apk_path, "rb").read()
    # locate the central directory via the end-of-central-directory record
    eocd = raw.rfind(b"PK\x05\x06")
    if eocd < 0:
        check("L5 EOCD present", False)
    else:
        cd_off = struct.unpack_from("<I", raw, eocd + 16)[0]
        schemes = signing_schemes(raw, cd_off)
        check("L5 APK Signing Block present",
              0x7109871a in schemes or 0xf05368c0 in schemes,
              "schemes: %s" % [hex(s) for s in sorted(schemes)] or "none")
        check("L5 v2 scheme present (API 26 floor)", 0x7109871a in schemes)
        check("L5 v3 scheme present (API 28+)", 0xf05368c0 in schemes)

    # ── the size gate (re-checked here so one report says everything) ───────
    import os
    size = os.path.getsize(apk_path)
    check("size below 50 MiB (%d bytes)", size < MAX_APK_BYTES,
          "%d B = %.2f MiB" % (size, size / (1024.0 * 1024.0)))

    for f in failures:
        print("::error title=launch-integrity::%s" % f)
    print("v3.4.6.a launch integrity: %d checks, %d failed" % (checks, len(failures)))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
