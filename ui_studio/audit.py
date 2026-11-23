#!/usr/bin/env python3
"""Generate a read-only, reproducible UI-focused inventory and audit log."""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from pathlib import Path

from pdf_export import write_pdf

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = ROOT / "src/lk/techworld/anantplay/sicast"
DRAWABLE = ROOT / "res/drawable"
TARGETS = (
    "MainActivity.java", "PlayActivity.java", "WebBrowserView.java",
    "OnboardingWizard.java",
)
JAVA_STRING = re.compile(r'"((?:\\.|[^"\\])*)"')
TEXT_CALL = re.compile(
    r"\.setText\(|\.setTextSize\(|\b(?:chip|pillBtn|btn|label|paragraph|note|statusLine)\s*\("
    r"|UiChip\.make\(|UiGroup\.(?:make|row|switchRow)\(|new String\[\]\s*\{"
)
ANDROID = "{http://schemas.android.com/apk/res/android}"


def java_text_inventory() -> list[str]:
    output: list[str] = []
    total = 0
    for filename in TARGETS:
        path = PACKAGE / filename
        lines = path.read_text("utf-8").splitlines()
        records: list[tuple[int, str, str]] = []
        sizes: list[tuple[int, str]] = []
        for i, line in enumerate(lines, 1):
            size = re.search(r"\.setTextSize\(\s*([^)]+)", line)
            if size:
                sizes.append((i, size.group(1).strip()))
            if not TEXT_CALL.search(line):
                continue
            strings = JAVA_STRING.findall(line)
            if strings:
                records.append((i, line.strip(), " · ".join(strings)))
        output.append(f"\n[{filename}] {len(records)} literal text call lines")
        for line_no, source, text in records:
            nearest = min(sizes, key=lambda pair: abs(pair[0] - line_no)) if sizes else None
            size = f"{nearest[1]} sp (near line {nearest[0]})" if nearest and abs(nearest[0] - line_no) <= 8 else "not statically resolved"
            readable = text.replace("\\n", " ").replace("\\\"", '"')
            output.append(f"{filename}:{line_no} | {size} | {readable[:300]}")
            total += 1
    output.insert(0, f"Literal UI text candidate lines inventoried: {total}")
    output.append(
        "\nSizing note: the nearest setTextSize within eight source lines is an automated "
        "locator, not a Java data-flow proof. Dynamic strings, helper defaults, inherited "
        "styles, and bilingual alternatives are labelled conservatively."
    )
    return output


def icon_inventory() -> list[str]:
    records: list[str] = []
    files = sorted(DRAWABLE.glob("ic_*.xml"))
    widths: set[str] = set()
    for path in files:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            records.append(f"{path.relative_to(ROOT)} | XML parse failed")
            continue
        path_nodes = root.findall(".//path")
        stroke_values = sorted({
            item.attrib.get(ANDROID + "strokeWidth", "unset")
            for item in path_nodes
        })
        widths.update(value for value in stroke_values if value != "unset")
        view_w = root.attrib.get(ANDROID + "viewportWidth", "?")
        view_h = root.attrib.get(ANDROID + "viewportHeight", "?")
        records.append(
            f"{path.relative_to(ROOT)} | {view_w}×{view_h} viewport | "
            f"{len(path_nodes)} paths | stroke width(s): {', '.join(stroke_values) or 'none'}"
        )
    return [
        f"Vector resources audited: {len(files)}",
        f"Observed outline stroke widths: {', '.join(sorted(widths, key=float)) or 'none'}",
        "Every drawable resource is listed below; consistent 24×24 viewports are not a "
        "guarantee of consistent optical weight or padding.",
        *records,
    ]


def ui_icons_calls() -> list[str]:
    records: list[str] = []
    for path in sorted(PACKAGE.glob("*.java")):
        for line_no, line in enumerate(path.read_text("utf-8").splitlines(), 1):
            if "UiIcons." in line:
                records.append(f"{path.relative_to(ROOT)}:{line_no} | {line.strip()[:240]}")
    return [f"UiIcons call sites: {len(records)}", *records]


def find_line(filename: str, token: str) -> str:
    path = PACKAGE / filename
    for index, line in enumerate(path.read_text("utf-8").splitlines(), 1):
        if token in line:
            return f"{filename}:{index} | {line.strip()}"
    return f"{filename}:? | {token} (not found in current source)"


def mornye_refs() -> list[str]:
    checkout = Path("/tmp/mornye-ref")
    refs = [
        ("lib/widgets/settings_group.dart", "Expressive connected list"),
        ("lib/widgets/settings_group.dart", "surfaceContainerHigh"),
        ("lib/widgets/player_playback_controls.dart", "playPauseSize"),
        ("lib/widgets/mornye_player_layout.dart", "PlayerPlaybackControls"),
        ("lib/theme/app_tokens.dart", "gapLg"),
        ("lib/theme/app_theme.dart", "surfaceContainer"),
    ]
    output = ["Read-only Mornye comparison checkout: /tmp/mornye-ref"]
    for relative, term in refs:
        path = checkout / relative
        if not path.exists():
            output.append(f"{relative} | unavailable")
            continue
        found = []
        for line_no, line in enumerate(path.read_text("utf-8", errors="replace").splitlines(), 1):
            if term.lower() in line.lower():
                found.append(f"{line_no}: {line.strip()[:180]}")
                if len(found) == 2:
                    break
        output.append(f"{relative} :: {term} | " + ("; ".join(found) or "no exact match"))
    return output


def build_log() -> str:
    out = [
        "ANANTPLAY v3.4.3 — UI-ONLY FORENSIC AUDIT",
        "Read-only audit scope. No production Android Java or drawable was modified.",
        "Baseline: imported HEAD f118ea7 is a Replit config commit; its parent origin/main "
        "5b367912 records the v3.4.3 release, and AndroidManifest.xml says versionCode 30403.",
        "The request's example SHA was not the imported HEAD. The checked source itself is "
        "the v3.4.3 baseline; no version claim is made about v3.4.4.",
        "",
        "SCOPE & LIMITS",
        "This report combines source-anchored findings with a reproducible inventory of UI text "
        "call sites, vector resources, and UiIcons call sites. It is a static source audit, not "
        "a screenshot/device audit. There is no Android emulator installed, so visual states "
        "are represented in the studio simulator rather than claimed as device-verified.",
        "The project has no Browser UI screen; WebBrowserView is the in-app browser view. "
        "The subtitle, audio/quality, and settings overlays are sheets; code exposes more than "
        "three sheet/dialog states, so they are listed by action rather than miscounted.",
        "",
        "PRIORITIZED, EXACT SOURCE FINDINGS",
        "P1 — The cinema HUD's bottom dock is a seven-item horizontally scrolling row. "
        "Five items use word labels and two are live values. On narrow landscape screens, "
        "important controls can be off-screen and the dock reads as a pill wall.",
        find_line("PlayActivity.java", 'pillBtn(R.drawable.ic_subtitles, "Subtitles"'),
        find_line("PlayActivity.java", 'pillBtn(R.drawable.ic_audio_track, "Audio / Quality"'),
        find_line("PlayActivity.java", 'chip(R.drawable.ic_aspect_fit, VideoViewport.modeLabel'),
        find_line("PlayActivity.java", 'chip(R.drawable.ic_speed, "1.0x"'),
        find_line("PlayActivity.java", 'pillBtn(R.drawable.ic_rotate, "Rotate"'),
        find_line("PlayActivity.java", 'pillBtn(R.drawable.ic_vlc_external, "VLC"'),
        find_line("PlayActivity.java", 'pillBtn(R.drawable.ic_search, "Title"'),
        "These use pillBtn() at 12sp (helper) or chip() at 11.5sp; reducing point size "
        "will not fix the count/scroll problem. Use icon-only controls with content descriptions, "
        "active dots, and a small value only for aspect/speed.",
        "",
        "P1 — The subtitle sheet has multiple horizontal chip rows, including the long "
        "AI/action labels; the grouped actions compete for attention and create long scroll.",
        find_line("PlayActivity.java", 't2("SL Sub Sites")'),
        find_line("PlayActivity.java", '"Local .srt"'),
        find_line("PlayActivity.java", 't2("Spoken/Formal")'),
        find_line("PlayActivity.java", '"AI Studio"'),
        find_line("PlayActivity.java", 't2("NewZone translator")'),
        find_line("PlayActivity.java", 't2("Audio → SRT (AI)")'),
        "Combine rarely used translation tools into one secondary action; keep language, "
        "subtitle selection, and sync controls as the primary tasks.",
        "",
        "P2 — Player settings repeat descriptive labels and word-heavy buttons where compact "
        "values or segmented controls fit better.",
        find_line("PlayActivity.java", '"Fit ⇄ Fill ⇄ Zoom"'),
        find_line("PlayActivity.java", 'label(Chrome.pick("ධාවන වේගය · Playback speed"))'),
        find_line("PlayActivity.java", 'label(Chrome.pick("කාල ගැලපුම · Sync nudge"))'),
        find_line("PlayActivity.java", 'label(Chrome.pick("භාෂාව · Language"))'),
        find_line("PlayActivity.java", 'Button close = btn(Chrome.pick("වසන්න · Done"));'),
        "Recommended: display 0.5×–2.5× as one segmented row; use the active ratio in the "
        "dock; move slider units into a small inline value.",
        "",
        "P2 — Home combines a 14sp auto-open heading with an explanatory 11.5sp paragraph, "
        "plus a separate 15sp getting-started block. This creates competing onboarding "
        "explanations above the primary receiver action.",
        find_line("MainActivity.java", 'head.setText(t("Cast කළ විට ඉබේ විවෘත කරන්න"'),
        find_line("MainActivity.java", 'body.setText(t("\\"Display over other apps\\"'),
        find_line("MainActivity.java", 'qsTitle.setText(t("මිනිත්තු 10න් පටන් ගන්න"'),
        "Keep one primary action and a one-line state hint; place permission detail behind "
        "a compact disclosure instead of deleting the explanation.",
        "",
        "P2 — Onboarding and home use 20sp / 17sp / 15sp headings across the setup flow; "
        "those sizes can be correct as hierarchy, but the screen should be checked for "
        "translation wrapping and redundant instructions. The static audit flags them for "
        "layout review, not automatic reduction.",
        find_line("OnboardingWizard.java", "title.setTextSize(20f)"),
        find_line("OnboardingWizard.java", 'head.setText("3 steps to watch")'),
        find_line("MainActivity.java", "appTitle.setTextSize(16f)"),
        "",
        "P3 — Browser's omnibar and suggestions are 13–14sp; shield status is 8sp. The "
        "tiny badge is a legibility risk; keep status secondary but raise its floor and use "
        "a concise icon + count. MainActivity uses text glyphs for expand/back affordances "
        "(▾, ‹) in the customization/browser surfaces; replace with existing vectors.",
        find_line("WebBrowserView.java", "shieldBadge.setTextSize(8f)"),
        find_line("WebBrowserView.java", 'autoChip.setText("Auto " + (on ? "ON" : "OFF"));'),
        find_line("MainActivity.java", 'guideToggle.setText("▾")'),
        find_line("MainActivity.java", 'more.setText("›")'),
        find_line("MainActivity.java", 'back.setText("‹")'),
        "",
        "ICON AND LAYOUT INVENTORY",
        *icon_inventory(),
        "",
        "ALL UiIcons CALL SITES",
        *ui_icons_calls(),
        "",
        "MORNYE REFERENCE COMPARISON (read-only)",
        *mornye_refs(),
        "",
        "MORNYE-GRADE GAP SUMMARY",
        "• Surface hierarchy: app has a dark/AMOLED/light palette, accent presets, radius "
        "tokens and grouped settings, but several screens still depend on repeated stroked "
        "cards/chips rather than one clear canvas → surface → control hierarchy.",
        "• Density: player actions are split between a 7-control dock and several subtitle "
        "rows; Mornye's player layout organizes controls into measured, aligned groups and "
        "uses spacious surface-container contrast.",
        "• Typography: the shared scale is strong, but screen code frequently chooses local "
        "sizes and bilingual labels; simplify copy and centralize text roles instead of "
        "globally shrinking the font.",
        "• Customization: settings expose many raw rows and sliders. Build preview-first "
        "tiles for theme/accent/radius/navigation, retaining a collapsible advanced list.",
        "• App icon: current adaptive and monochrome XML assets exist; focus redesign on a "
        "single bold, centered cinema-frame/letterform silhouette with safe-zone margins, "
        "no fine linework, no text, and identical monochrome geometry.",
        "",
        "Canva API note: current export documentation has a design-export-formats endpoint "
        "that can report SVG availability and an SVG export job type, but exports can return "
        "feature_not_available. The studio offers per-design format checks and manual SVG "
        "upload; Canva SVG export is best-effort and cannot be promised for every design.",
        "Production callback note: this repl has no active production deployment. A production "
        "URL cannot be supplied until publishing. The studio labels the Replit development "
        "callback as development and prefers CANVA_REDIRECT_URI / REPLIT_APP_URL once set.",
        "",
        "RECOMMENDED v3.4.4 HANDOFF",
        "1. Replace the scrolling player action pills with an accessible icon dock: CC, audio "
        "tracks, aspect, speed, and title search; use a small active indicator and retain "
        "spoken labels as accessibility descriptions.",
        "2. Keep the upper HUD title and 3-zone layout; make only the low-frequency options "
        "(rotate, lock, PiP, external player, settings) icon controls. Use 44dp touch targets.",
        "3. Collapse subtitle tools into: language / source / sync / style / more. Put AI "
        "translation and site search under More; no more than one horizontal chip strip.",
        "4. Home: one receiver state card, one action, one short supporting sentence. Move "
        "onboarding steps into a dismissible guided checklist.",
        "5. Settings: preview-first theme, accent, and bar-style tiles; keep the current "
        "Simple/Advanced distinction and all existing preferences.",
        "6. Keep changes inside v3.4.x, Java 8, minSdk 26, targetSdk 36, under 5 MB, with "
        "the existing layout-parameter and no-AccessibilityService constraints.",
        "",
        "No Android production source or resource was modified by this work.",
        "",
        "TEXT-LITERAL SOURCE INVENTORY",
        *java_text_inventory(),
    ]
    return "\n".join(out) + "\n"


def main() -> int:
    result = build_log()
    (ROOT / "REPLIT_UI_ONLY_AUDIT_LOG.txt").write_text(result, encoding="utf-8")
    write_pdf(
        ROOT / "REPLIT_UI_REDESIGN_AND_CANVA_BLUEPRINT.md",
        ROOT / "REPLIT_UI_REDESIGN_AND_CANVA_BLUEPRINT.pdf",
    )
    print(f"Generated UI audit log ({len(result.splitlines())} lines) and blueprint PDF.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
