"""Tiny A4 text-PDF writer for the generated blueprint; no third-party package."""

from __future__ import annotations

import re
import textwrap
import unicodedata
from pathlib import Path

PAGE_W, PAGE_H = 595, 842


def _ascii(text: str) -> str:
    text = (text.replace("→", "to").replace("×", "x").replace("–", "-")
            .replace("—", "-").replace("•", "*").replace("≤", "<="))
    return unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode("ascii")


def _pdf_escape(text: str) -> str:
    return text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")


def _lines(markdown: str) -> list[tuple[str, bool]]:
    result: list[tuple[str, bool]] = []
    for raw in markdown.splitlines():
        line = raw.strip()
        if not line:
            result.append(("", False))
            continue
        is_heading = line.startswith("#")
        line = re.sub(r"^#{1,6}\s*", "", line)
        line = re.sub(r"\*\*(.*?)\*\*", r"\1", line)
        line = re.sub(r"`([^`]*)`", r"\1", line)
        if line.startswith("|") and line.endswith("|"):
            line = "  |  ".join(bit.strip() for bit in line.strip("|").split("|"))
        line = _ascii(line)
        width = 86 if not is_heading else 80
        wraps = textwrap.wrap(line, width=width, break_long_words=True) or [""]
        result.extend((part, is_heading) for part in wraps)
    return result


def write_pdf(source: Path, output: Path) -> None:
    source_text = source.read_text("utf-8")
    all_lines = _lines(source_text)
    rows_per_page = 50
    pages = [all_lines[i:i + rows_per_page] for i in range(0, len(all_lines), rows_per_page)] or [[]]
    objects: list[bytes] = []
    page_ids = [5 + index * 2 for index in range(len(pages))]
    content_ids = [page_id + 1 for page_id in page_ids]

    objects.append(b"<< /Type /Catalog /Pages 2 0 R >>")
    kids = " ".join(f"{page_id} 0 R" for page_id in page_ids)
    objects.append(f"<< /Type /Pages /Kids [{kids}] /Count {len(pages)} >>".encode())
    objects.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    objects.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>")

    for number, (page, content_id) in enumerate(zip(pages, content_ids), 1):
        page_id = page_ids[number - 1]
        objects.append(
            f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {PAGE_W} {PAGE_H}] "
            f"/Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> "
            f"/Contents {content_id} 0 R >>".encode()
        )
        operations = [
            "BT /F2 9 Tf 42 808 Td (ANANTPLAY UI STUDIO - V3.4.4 BLUEPRINT) Tj ET",
            "0.75 w 42 798 m 553 798 l S",
        ]
        y = 780
        for text, heading in page:
            if not text:
                y -= 8
                continue
            font = "F2" if heading else "F1"
            size = 12 if heading else 9
            operations.append(
                f"BT /{font} {size} Tf 42 {y} Td ({_pdf_escape(text)}) Tj ET"
            )
            y -= 17 if heading else 13
        operations.extend([
            "0.75 w 42 34 m 553 34 l S",
            f"BT /F1 8 Tf 42 19 Td (UI-only design handoff - page {number} of {len(pages)}) Tj ET",
        ])
        stream = "\n".join(operations).encode("latin-1", "replace")
        objects.append(
            f"<< /Length {len(stream)} >>\nstream\n".encode()
            + stream
            + b"\nendstream"
        )

    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as file:
        file.write(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
        offsets = [0]
        for index, obj in enumerate(objects, 1):
            offsets.append(file.tell())
            file.write(f"{index} 0 obj\n".encode())
            file.write(obj)
            file.write(b"\nendobj\n")
        xref = file.tell()
        file.write(f"xref\n0 {len(objects) + 1}\n".encode())
        file.write(b"0000000000 65535 f \n")
        for offset in offsets[1:]:
            file.write(f"{offset:010d} 00000 n \n".encode())
        file.write(
            f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\n"
            f"startxref\n{xref}\n%%EOF\n".encode()
        )
