"""Convert safe, path-based SVGs into Android VectorDrawable XML."""

from __future__ import annotations

import io
import math
import re
import xml.etree.ElementTree as ET
import zipfile
from dataclasses import dataclass

ANDROID_NS = "http://schemas.android.com/apk/res/android"
ANDROID_ATTR = "{" + ANDROID_NS + "}"
ET.register_namespace("android", ANDROID_NS)
MAX_SVG_BYTES = 1_000_000
PATH_CHARS = re.compile(r"^[MmZzLlHhVvCcSsQqTtAa0-9eE+.,\-\s]+$")
NUMBER = re.compile(r"[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?")
NAME_RE = re.compile(r"[^A-Za-z0-9_]+")


def _tag(element: ET.Element) -> str:
    return element.tag.rsplit("}", 1)[-1]


def _attr(element: ET.Element, key: str, default: str | None = None) -> str | None:
    return element.attrib.get(key, default)


def _number(value: str | None, label: str) -> float:
    if value is None:
        raise ValueError(f"SVG is missing {label}.")
    match = re.fullmatch(r"\s*([-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?)\s*(?:px)?\s*", value)
    if not match:
        raise ValueError(f"Unsupported SVG {label}.")
    result = float(match.group(1))
    if not math.isfinite(result) or abs(result) > 100_000:
        raise ValueError(f"Invalid SVG {label}.")
    return result


def _fmt(value: float) -> str:
    if abs(value) < 0.00001:
        value = 0
    return f"{value:.4f}".rstrip("0").rstrip(".")


def _path(value: str) -> str:
    value = value.strip()
    if not value or len(value) > 100_000 or not PATH_CHARS.fullmatch(value):
        raise ValueError("The SVG contains unsupported or invalid path data.")
    if not re.search(r"[Mm]", value):
        raise ValueError("Each SVG path must begin with a move command.")
    return value


def _paint(value: str | None) -> str | None:
    if value is None:
        return None
    val = value.strip().lower()
    if val == "none":
        return None
    if val in ("currentcolor", "inherit") or "url(" in val:
        raise ValueError("SVG gradients and CSS paint are not supported; use flat fills and strokes.")
    if val == "transparent":
        return None
    if val.startswith("#"):
        if not re.fullmatch(r"#(?:[0-9a-f]{3}|[0-9a-f]{6})", val):
            raise ValueError("SVG colors must use a valid hex color or a flat named color.")
    elif val not in {
        "black", "white", "red", "green", "blue", "yellow", "orange",
        "purple", "gray", "grey", "navy", "teal", "silver", "maroon",
        "lime", "aqua", "fuchsia", "olive", "cyan", "magenta",
    } and not re.fullmatch(
        r"rgb\(\s*(?:\d{1,3}%?\s*,\s*){2}\d{1,3}%?\s*\)", val,
    ):
        raise ValueError("Unsupported SVG paint value; use a flat color.")
    return "#FFFFFFFF"


def _primitive_path(element: ET.Element) -> str | None:
    kind = _tag(element)
    if kind == "path":
        d = _attr(element, "d")
        return _path(d or "")
    if kind == "rect":
        x = _number(_attr(element, "x", "0"), "rectangle x")
        y = _number(_attr(element, "y", "0"), "rectangle y")
        w = _number(_attr(element, "width"), "rectangle width")
        h = _number(_attr(element, "height"), "rectangle height")
        if w <= 0 or h <= 0 or _number(_attr(element, "rx", "0"), "rectangle radius") != 0:
            raise ValueError("Only positive, square-corner SVG rectangles are supported.")
        return f"M{_fmt(x)},{_fmt(y)}h{_fmt(w)}v{_fmt(h)}h-{_fmt(w)}z"
    if kind in ("circle", "ellipse"):
        cx = _number(_attr(element, "cx", "0"), "center x")
        cy = _number(_attr(element, "cy", "0"), "center y")
        rx = _number(_attr(element, "r" if kind == "circle" else "rx"), "radius x")
        ry = rx if kind == "circle" else _number(_attr(element, "ry"), "radius y")
        if rx <= 0 or ry <= 0:
            raise ValueError("SVG circles and ellipses need positive radii.")
        return (
            f"M{_fmt(cx-rx)},{_fmt(cy)}"
            f"a{_fmt(rx)},{_fmt(ry)} 0 1,0 {_fmt(2*rx)},0"
            f"a{_fmt(rx)},{_fmt(ry)} 0 1,0 -{_fmt(2*rx)},0z"
        )
    if kind == "line":
        x1 = _number(_attr(element, "x1", "0"), "line x1")
        y1 = _number(_attr(element, "y1", "0"), "line y1")
        x2 = _number(_attr(element, "x2", "0"), "line x2")
        y2 = _number(_attr(element, "y2", "0"), "line y2")
        return f"M{_fmt(x1)},{_fmt(y1)}L{_fmt(x2)},{_fmt(y2)}"
    if kind in ("polyline", "polygon"):
        raw = _attr(element, "points", "") or ""
        nums = NUMBER.findall(raw)
        residue = NUMBER.sub("", raw)
        if (not re.fullmatch(r"[\s,]+", residue or " ") or len(nums) < 4
                or len(nums) % 2 or len(nums) > 10_000):
            raise ValueError("SVG polygon points are invalid.")
        points = list(zip(nums[::2], nums[1::2]))
        d = "M" + " ".join(f"{x},{y}" for x, y in points)
        return d + ("z" if kind == "polygon" else "")
    return None


def _group_attributes(element: ET.Element) -> dict[str, str]:
    transform = (_attr(element, "transform", "") or "").strip()
    if not transform:
        return {}
    # VectorDrawable supports these transform components. Limit each group to one
    # transform so SVG's ordered transform composition is never silently changed.
    match = re.fullmatch(r"(translate|scale|rotate)\s*\(([^()]*)\)", transform)
    if not match:
        raise ValueError("Use one simple translate, scale, or rotate per SVG group.")
    operation = match.group(1)
    raw_values = match.group(2)
    residue = NUMBER.sub("", raw_values)
    if not re.fullmatch(r"[\s,]+", residue or " "):
        raise ValueError("SVG group transform values are invalid.")
    values = [float(v) for v in NUMBER.findall(raw_values)]
    if any(not math.isfinite(v) or abs(v) > 100_000 for v in values):
        raise ValueError("SVG group transform is out of range.")
    if operation == "translate" and len(values) in (1, 2):
        return {
            ANDROID_ATTR + "translateX": _fmt(values[0]),
            ANDROID_ATTR + "translateY": _fmt(values[1] if len(values) > 1 else 0),
        }
    if operation == "scale" and len(values) in (1, 2):
        return {
            ANDROID_ATTR + "scaleX": _fmt(values[0]),
            ANDROID_ATTR + "scaleY": _fmt(values[1] if len(values) > 1 else values[0]),
        }
    if operation == "rotate" and len(values) in (1, 3):
        attrs = {ANDROID_ATTR + "rotation": _fmt(values[0])}
        if len(values) == 3:
            attrs[ANDROID_ATTR + "pivotX"] = _fmt(values[1])
            attrs[ANDROID_ATTR + "pivotY"] = _fmt(values[2])
        return attrs
    raise ValueError("SVG group transform values are invalid.")


def _validate_attrs(element: ET.Element, allowed: set[str]) -> None:
    for key in element.attrib:
        name = key.rsplit("}", 1)[-1]
        if name not in allowed or name.lower().startswith("on"):
            raise ValueError(f"Unsupported SVG attribute: {name}.")


def _safe_nodes(source: ET.Element, target: ET.Element,
                inherited_fill: str | None = "#000000",
                inherited_stroke: str | None = None,
                inherited_width: str = "1",
                inherited_rule: str = "nonzero",
                inherited_cap: str | None = None,
                inherited_join: str | None = None) -> None:
    for child in list(source):
        kind = _tag(child)
        if kind in ("title", "desc", "metadata"):
            continue
        if kind == "g":
            _validate_attrs(child, {
                "fill", "stroke", "stroke-width", "fill-rule", "stroke-linecap",
                "stroke-linejoin", "transform", "opacity", "id",
            })
            if _attr(child, "opacity") not in (None, "1", "1.0"):
                raise ValueError("SVG group opacity is not supported.")
            group = ET.SubElement(target, "group", _group_attributes(child))
            fill = _paint(_attr(child, "fill")) if "fill" in child.attrib else inherited_fill
            stroke = _paint(_attr(child, "stroke")) if "stroke" in child.attrib else inherited_stroke
            cap = _attr(child, "stroke-linecap", inherited_cap)
            join = _attr(child, "stroke-linejoin", inherited_join)
            if cap is not None and cap not in ("butt", "round", "square"):
                raise ValueError("SVG stroke-linecap must be butt, round, or square.")
            if join is not None and join not in ("miter", "round", "bevel"):
                raise ValueError("SVG stroke-linejoin must be miter, round, or bevel.")
            _safe_nodes(
                child, group, fill, stroke,
                _attr(child, "stroke-width", inherited_width) or inherited_width,
                _attr(child, "fill-rule", inherited_rule) or inherited_rule,
                cap, join,
            )
            continue
        if kind in ("path", "rect", "circle", "ellipse", "line", "polyline", "polygon"):
            allowed = {
                "path": {"d", "fill", "stroke", "stroke-width", "fill-rule", "stroke-linecap", "stroke-linejoin", "opacity", "id"},
                "rect": {"x", "y", "width", "height", "rx", "fill", "stroke", "stroke-width", "fill-rule", "stroke-linecap", "stroke-linejoin", "opacity", "id"},
                "circle": {"cx", "cy", "r", "fill", "stroke", "stroke-width", "fill-rule", "stroke-linecap", "stroke-linejoin", "opacity", "id"},
                "ellipse": {"cx", "cy", "rx", "ry", "fill", "stroke", "stroke-width", "fill-rule", "stroke-linecap", "stroke-linejoin", "opacity", "id"},
                "line": {"x1", "y1", "x2", "y2", "fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin", "opacity", "id"},
                "polyline": {"points", "fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin", "opacity", "id"},
                "polygon": {"points", "fill", "stroke", "stroke-width", "fill-rule", "stroke-linecap", "stroke-linejoin", "opacity", "id"},
            }[kind]
            _validate_attrs(child, allowed)
            d = _primitive_path(child)
            assert d is not None
            fill = _paint(_attr(child, "fill")) if "fill" in child.attrib else inherited_fill
            stroke = _paint(_attr(child, "stroke")) if "stroke" in child.attrib else inherited_stroke
            if fill is None and stroke is None:
                continue
            path_attrs = {ANDROID_ATTR + "pathData": d}
            if fill:
                path_attrs[ANDROID_ATTR + "fillColor"] = fill
                rule = _attr(child, "fill-rule", inherited_rule)
                if rule not in ("evenodd", "nonzero"):
                    raise ValueError("SVG fill-rule must be evenodd or nonzero.")
                path_attrs[ANDROID_ATTR + "fillType"] = "evenOdd" if rule == "evenodd" else "nonZero"
            if stroke:
                width = _number(_attr(child, "stroke-width", inherited_width), "stroke width")
                if width <= 0:
                    raise ValueError("SVG stroke widths must be positive.")
                path_attrs[ANDROID_ATTR + "strokeColor"] = stroke
                path_attrs[ANDROID_ATTR + "strokeWidth"] = _fmt(width)
                cap = _attr(child, "stroke-linecap", inherited_cap)
                join = _attr(child, "stroke-linejoin", inherited_join)
                if cap is not None and cap not in ("butt", "round", "square"):
                    raise ValueError("SVG stroke-linecap must be butt, round, or square.")
                if join is not None and join not in ("miter", "round", "bevel"):
                    raise ValueError("SVG stroke-linejoin must be miter, round, or bevel.")
                if cap in ("butt", "round", "square"):
                    path_attrs[ANDROID_ATTR + "strokeLineCap"] = cap
                if join in ("miter", "round", "bevel"):
                    path_attrs[ANDROID_ATTR + "strokeLineJoin"] = join
            if _attr(child, "opacity") not in (None, "1", "1.0"):
                raise ValueError("SVG opacity is not supported; convert the artwork to opaque paths.")
            ET.SubElement(target, "path", path_attrs)
            continue
        raise ValueError(f"Unsupported SVG element: {kind}. Convert text, filters, and CSS to paths first.")


def _normalize_name(name: str) -> str:
    name = NAME_RE.sub("_", name.strip()).strip("_")
    name = re.sub(r"_+", "_", name)
    if not name:
        name = "ic_custom"
    if not name.startswith("ic_"):
        name = "ic_" + name
    return name[:64]


def _preview_tree(vector: ET.Element, min_x: float, min_y: float,
                  width: float, height: float) -> str:
    preview = ET.Element("svg", {
        "xmlns": "http://www.w3.org/2000/svg",
        "viewBox": f"{_fmt(min_x)} {_fmt(min_y)} {_fmt(width)} {_fmt(height)}",
    })

    def visit(source: ET.Element, target: ET.Element) -> None:
        for child in list(source):
            if child.tag == "group":
                attrs = {}
                mapping = {
                    "translateX": "translateX", "translateY": "translateY",
                    "scaleX": "scaleX", "scaleY": "scaleY",
                    "rotation": "rotation", "pivotX": "pivotX", "pivotY": "pivotY",
                }
                values = {
                    key: child.attrib.get(ANDROID_ATTR + key)
                    for key in mapping if child.attrib.get(ANDROID_ATTR + key) is not None
                }
                tx = values.get("translateX", "0")
                ty = values.get("translateY", "0")
                sx = values.get("scaleX", "1")
                sy = values.get("scaleY", "1")
                rotate = values.get("rotation")
                transforms = []
                if tx != "0" or ty != "0":
                    transforms.append(f"translate({tx} {ty})")
                if sx != "1" or sy != "1":
                    transforms.append(f"scale({sx} {sy})")
                if rotate is not None:
                    px = values.get("pivotX", "0")
                    py = values.get("pivotY", "0")
                    transforms.append(f"rotate({rotate} {px} {py})")
                if transforms:
                    attrs["transform"] = " ".join(transforms)
                nested = ET.SubElement(target, "g", attrs)
                visit(child, nested)
                continue
            if child.tag != "path":
                continue
            attrs = {
                "d": child.attrib.get(ANDROID_ATTR + "pathData", ""),
                "fill": "#ffffff" if child.attrib.get(ANDROID_ATTR + "fillColor") else "none",
                "stroke": "#ffffff" if child.attrib.get(ANDROID_ATTR + "strokeColor") else "none",
            }
            stroke_width = child.attrib.get(ANDROID_ATTR + "strokeWidth")
            if stroke_width:
                attrs["stroke-width"] = stroke_width
            cap = child.attrib.get(ANDROID_ATTR + "strokeLineCap")
            join = child.attrib.get(ANDROID_ATTR + "strokeLineJoin")
            if cap:
                attrs["stroke-linecap"] = cap
            if join:
                attrs["stroke-linejoin"] = join
            ET.SubElement(target, "path", attrs)

    visit(vector, preview)
    return ET.tostring(preview, encoding="unicode")


def _parse(svg: str) -> tuple[ET.Element, float, float, float, float]:
    if len(svg.encode("utf-8")) > MAX_SVG_BYTES:
        raise ValueError("SVG files must be smaller than 1 MB.")
    if re.search(r"<!DOCTYPE|<!ENTITY", svg, re.IGNORECASE):
        raise ValueError("SVG document types and entities are not allowed.")
    try:
        root = ET.fromstring(svg)
    except ET.ParseError:
        raise ValueError("This is not valid SVG XML.") from None
    if _tag(root) != "svg":
        raise ValueError("The document root must be an SVG element.")
    _validate_attrs(root, {
        "viewBox", "width", "height", "fill", "stroke", "stroke-width",
        "fill-rule", "stroke-linecap", "stroke-linejoin", "version", "id",
        "preserveAspectRatio",
    })
    vb = _attr(root, "viewBox")
    if vb:
        values = NUMBER.findall(vb)
        if len(values) != 4 or len(NUMBER.sub("", vb).replace(",", "").split()):
            # The second test rejects stray text while allowing whitespace and commas.
            cleaned = re.sub(r"[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?", "", vb)
            if len(values) != 4 or not re.fullmatch(r"[\s,]+", cleaned):
                raise ValueError("SVG viewBox must contain exactly four numbers.")
        min_x, min_y, width, height = map(float, values)
    else:
        min_x = min_y = 0
        width = _number(_attr(root, "width"), "width")
        height = _number(_attr(root, "height"), "height")
    if not all(math.isfinite(v) and abs(v) <= 100_000 for v in (min_x, min_y, width, height)):
        raise ValueError("SVG viewBox values are out of range.")
    if width <= 0 or height <= 0:
        raise ValueError("SVG viewBox width and height must be positive.")
    return root, min_x, min_y, width, height


@dataclass
class Conversion:
    xml: str
    preview_svg: str
    file_stem: str
    kind: str
    width: float
    height: float

    def public_result(self) -> dict[str, str | float]:
        return {
            "xml": self.xml,
            "previewSvg": self.preview_svg,
            "fileStem": self.file_stem,
            "kind": self.kind,
            "viewportWidth": self.width,
            "viewportHeight": self.height,
        }


def convert_svg(svg: str, icon_name: str = "ic_custom", kind: str = "icon") -> Conversion:
    if kind not in ("icon", "adaptive"):
        raise ValueError("Choose a 24 dp icon or a 108 dp adaptive icon.")
    source, min_x, min_y, width, height = _parse(svg)
    vector = ET.Element("vector", {
        f"{{{ANDROID_NS}}}width": "24dp" if kind == "icon" else "108dp",
        f"{{{ANDROID_NS}}}height": "24dp" if kind == "icon" else "108dp",
        f"{{{ANDROID_NS}}}viewportWidth": _fmt(width),
        f"{{{ANDROID_NS}}}viewportHeight": _fmt(height),
    })
    # Translate a non-zero viewBox origin into a VectorDrawable group.
    root_group = vector
    if min_x or min_y:
        root_group = ET.SubElement(vector, "group", {
            f"{{{ANDROID_NS}}}translateX": _fmt(-min_x),
            f"{{{ANDROID_NS}}}translateY": _fmt(-min_y),
        })
    root_fill = _paint(_attr(source, "fill")) if "fill" in source.attrib else "#000000"
    root_stroke = _paint(_attr(source, "stroke")) if "stroke" in source.attrib else None
    _safe_nodes(
        source, root_group, root_fill, root_stroke,
        _attr(source, "stroke-width", "1") or "1",
        _attr(source, "fill-rule", "nonzero") or "nonzero",
        _attr(source, "stroke-linecap"),
        _attr(source, "stroke-linejoin"),
    )
    if not list(vector):
        raise ValueError("SVG has no supported visible paths.")
    xml = ET.tostring(vector, encoding="unicode", xml_declaration=False)
    # Build a browser-safe preview from normalized paths, not from the upload.
    safe_preview = _preview_tree(vector, min_x, min_y, width, height)
    return Conversion(xml, safe_preview, _normalize_name(icon_name), kind, width, height)


def _adaptive_xml(stem: str, kind: str, converted_xml: str) -> dict[str, str]:
    if kind == "icon":
        return {f"res/drawable/{stem}.xml": converted_xml}
    parsed = ET.fromstring(converted_xml)
    parsed.set(f"{{{ANDROID_NS}}}width", "108dp")
    parsed.set(f"{{{ANDROID_NS}}}height", "108dp")
    foreground = ET.tostring(parsed, encoding="unicode")
    monochrome = foreground
    background_stem = stem + "_background"
    background = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" '
        'android:width="108dp" android:height="108dp" '
        'android:viewportWidth="108" android:viewportHeight="108">'
        '<path android:pathData="M0,0h108v108h-108z" android:fillColor="#FF101522"/>'
        '</vector>'
    )
    adaptive = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">'
        f'<background android:drawable="@drawable/{background_stem}"/>'
        f'<foreground android:drawable="@drawable/{stem}_foreground"/>'
        f'<monochrome android:drawable="@drawable/{stem}_monochrome"/>'
        '</adaptive-icon>'
    )
    return {
        f"res/drawable/{stem}_foreground.xml": foreground,
        f"res/drawable/{stem}_monochrome.xml": monochrome,
        f"res/drawable/{background_stem}.xml": background,
        "res/mipmap-anydpi-v26/ic_launcher.xml": adaptive,
        "res/mipmap-anydpi-v26/ic_launcher_round.xml": adaptive,
    }


def make_bundle(result: Conversion, icon_name: str, kind: str) -> bytes:
    entries = _adaptive_xml(result.file_stem, kind, result.xml)
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path, content in entries.items():
            info = zipfile.ZipInfo(path, date_time=(2020, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, content)
    return out.getvalue()
