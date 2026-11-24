#!/usr/bin/env python3
"""v3.4.4 UI/artifact gate: audit every vector, path, text floor, and manifest row."""
import json
import math
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DRAWABLES = ROOT / "res" / "drawable"
MANIFEST = ROOT / "design" / "icon-manifest.json"
SRC = ROOT / "src"
ANDROID = "{http://schemas.android.com/apk/res/android}"
AAPT = "{http://schemas.android.com/aapt}"
PATH_COMMANDS = re.compile(r"^[MmZzLlHhVvCcSsQqTtAaEe0-9\s.,+\-]*$")
NUMBER = re.compile(r"[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?")

passed = 0
failed = 0


def check(name, ok, detail=""):
    global passed, failed
    if ok:
        passed += 1
        print("  PASS v344 ui: " + name)
    else:
        failed += 1
        print("  FAIL v344 ui: " + name + ((" — " + detail) if detail else ""))


def has_paint(path):
    if path.get(ANDROID + "fillColor") or path.get(ANDROID + "strokeColor"):
        return True
    for node in path:
        if node.tag == AAPT + "attr" and node.get("name") == "android:fillColor":
            return True
    return False


def main():
    print("== v3.4.4 icon and typography audit ==")
    try:
        catalog = json.loads(MANIFEST.read_text(encoding="utf-8"))
    except Exception as exc:
        check("icon catalog parses as JSON", False, str(exc))
        return 1
    check("icon catalog parses as JSON", isinstance(catalog, list))
    rows = {row.get("name"): row for row in catalog if isinstance(row, dict)}
    files = {path.stem: path for path in DRAWABLES.glob("ic_*.xml")}
    check("catalog and drawable resource sets contain the same names", set(rows) == set(files),
          "missing=" + str(sorted(set(files) - set(rows))) + " extra=" + str(sorted(set(rows) - set(files))))

    for name in sorted(files):
        path = files[name]
        row = rows.get(name, {})
        try:
            root = ET.parse(path).getroot()
            parsed = root.tag == "vector"
        except Exception:
            root = None
            parsed = False
        check(name + ": XML parses as a vector drawable", parsed)
        if root is None:
            continue

        launcher = name in {"ic_launcher_background", "ic_launcher_foreground", "ic_launcher_monochrome"}
        expected_dp = "108dp" if launcher else "24dp"
        check(name + ": width and height match the " + expected_dp + " icon family",
              root.get(ANDROID + "width") == expected_dp and root.get(ANDROID + "height") == expected_dp)
        expected_viewport = 108.0 if launcher else 24.0
        try:
            vw = float(root.get(ANDROID + "viewportWidth", "nan"))
            vh = float(root.get(ANDROID + "viewportHeight", "nan"))
        except ValueError:
            vw = vh = float("nan")
        check(name + ": viewport matches its physical size family",
              math.isfinite(vw) and math.isfinite(vh) and vw == expected_viewport and vh == expected_viewport)

        paths = root.findall(".//path")
        check(name + ": has at least one drawable path", bool(paths))
        check(name + ": has a complete catalog row", row.get("name") == name
              and isinstance(row.get("group"), str) and bool(row.get("metaphor"))
              and isinstance(row.get("filled"), bool) and isinstance(row.get("stroke"), (int, float)))
        stroked = []
        for index, node in enumerate(paths, 1):
            tag = name + " path " + str(index)
            data = node.get(ANDROID + "pathData", "")
            check(tag + ": has non-empty path data", bool(data.strip()))
            check(tag + ": path data uses supported vector commands",
                  bool(data) and bool(PATH_COMMANDS.fullmatch(data)))
            nums = [float(value) for value in NUMBER.findall(data)]
            check(tag + ": all path coordinates are finite",
                  bool(nums) and all(math.isfinite(value) for value in nums))
            check(tag + ": declares a fill, stroke, or gradient paint", has_paint(node))
            if node.get(ANDROID + "strokeWidth") is not None:
                stroked.append(node)
                try:
                    width = float(node.get(ANDROID + "strokeWidth"))
                except ValueError:
                    width = float("nan")
                launcher_rim = name == "ic_launcher_foreground"
                check(tag + ": outlined width follows its icon family",
                      math.isfinite(width) and (
                          (launcher_rim and 1.19 <= width <= 1.41)
                          or (not launcher_rim and 1.75 <= width <= 1.85)))
                check(tag + ": outline uses a round cap",
                      node.get(ANDROID + "strokeLineCap") == "round")
                check(tag + ": outline uses a round join",
                      node.get(ANDROID + "strokeLineJoin") == "round"
                      or (launcher_rim and node.get(ANDROID + "strokeLineJoin") is None))
                color = node.get(ANDROID + "strokeColor", "").upper()
                check(tag + ": stroke is tint-ready white or the launcher badge rim",
                      color == "#FFFFFFFF" or (
                          launcher_rim and color in {"#CCFFFFFF", "#BFFFFFFF"}))

        actual_stroke = max((float(p.get(ANDROID + "strokeWidth")) for p in stroked), default=0.0)
        try:
            catalog_stroke = float(row.get("stroke", -1))
        except (TypeError, ValueError):
            catalog_stroke = -1
        check(name + ": catalog stroke matches the vector paths",
              abs(catalog_stroke - actual_stroke) < 0.001)

    # Text below 10sp is not allowed in app chrome. Dynamic subtitle sizes are
    # governed separately by AppPrefs.normalizeFontSp (minimum 12sp).
    size_calls = 0
    for path in sorted(SRC.rglob("*.java")):
        source = path.read_text(encoding="utf-8")
        for match in re.finditer(r"\.setTextSize\s*\(([^()]*)\)", source):
            args = [part.strip() for part in match.group(1).split(",")]
            literal = args[-1] if args else ""
            if re.fullmatch(r"\d+(?:\.\d+)?f?", literal):
                size = float(literal.rstrip("f"))
                size_calls += 1
                check(path.relative_to(ROOT).as_posix() + ": setTextSize call at line "
                      + str(source.count("\n", 0, match.start()) + 1) + " is at least 10sp",
                      size >= 10, str(size) + "sp")
    for path in sorted(SRC.rglob("*.java")):
        source = path.read_text(encoding="utf-8")
        for match in re.finditer(r"UiTokens\.text\s*\(([^()]*)\)", source):
            args = [part.strip() for part in match.group(1).split(",")]
            if len(args) > 1 and re.fullmatch(r"\d+(?:\.\d+)?f?", args[1]):
                size = float(args[1].rstrip("f"))
                size_calls += 1
                check(path.relative_to(ROOT).as_posix() + ": UiTokens.text call at line "
                      + str(source.count("\n", 0, match.start()) + 1) + " is at least 10sp",
                      size >= 10, str(size) + "sp")
    check("all literal text-size call sites were inspected", size_calls > 0, str(size_calls) + " call sites")

    print("UI audit result: %d PASS, %d FAIL" % (passed, failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
