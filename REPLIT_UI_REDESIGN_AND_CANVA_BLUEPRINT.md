# AnantPlay UI Studio — v3.4.4 Design Handoff

## Scope and baseline

This is a design-only handoff for the next `v3.4.x` Android release. The audit
source is the imported v3.4.3 app (`versionCode 30403`). Android production
sources, manifests, icons, and resources were inspected read-only and are not
changed by the studio. Keep the eventual Android work in pure Java 8, minSdk 26,
targetSdk 36, under 5 MB, with no Compose/Flutter/AAR expansion, no
`AccessibilityService`, and the existing safe `LayoutParams` discipline.

This audit is a source review plus a static inventory, not a device screenshot
audit. No Android emulator is installed. The simulator in the web studio is a
design schematic, not a rendering of the running Android app.

## Audit findings and evidence

The accompanying `REPLIT_UI_ONLY_AUDIT_LOG.txt` has the line-numbered evidence,
all 64 `ic_*.xml` drawable summaries, every `UiIcons` call site in the Java
package, and a literal-text candidate inventory for `MainActivity`,
`PlayActivity`, `WebBrowserView`, and `OnboardingWizard`. The audit's text-size
locator is a static nearest-call heuristic; dynamic strings, inherited styles,
helper defaults, and Sinhala/English alternatives are identified as such
rather than represented as proven measurements.

### Player label-to-symbol plan

| Current control | Current source | Proposed dock | Label/value retained |
| --- | --- | --- | --- |
| `Subtitles` | `PlayActivity.java:2269` | Closed-caption glyph | `CC` accessibility name |
| `Audio / Quality` | `PlayActivity.java:2270` | Audio-track glyph | Spoken accessibility name |
| Aspect mode label | `PlayActivity.java:2271` | Crop/aspect glyph | Compact value such as `Fit` only when changed |
| `1.0x` | `PlayActivity.java:2273` | Speed glyph | Current multiplier, formatted `1.0×` |
| `Rotate` | `PlayActivity.java:2275` | Rotate glyph | Spoken accessibility name |
| `VLC` | `PlayActivity.java:2276` | External-player glyph | No brand text in the dock |
| `Title` | `PlayActivity.java:2277` | Search glyph | Spoken accessibility name |
| `Fit ⇄ Fill ⇄ Zoom` | `PlayActivity.java:2441` | Aspect selector | Short selected mode, not the full cycle description |
| `Playback speed` | `PlayActivity.java:2493` | Speed section | Current value adjacent to a segmented picker |
| `SL Sub Sites` / `Local .srt` | `PlayActivity.java:3553–3555` | Source / file icons | Short labels in the sheet only |
| `Free translate`, `Title search` | `PlayActivity.java:3557–3559` | Translate / search icons | Move to the secondary “More” section |
| `Spoken/Formal`, `AI Studio` | `PlayActivity.java:3582–3584` | Language / tune icons | Keep language choice visible; move AI tools to More |
| `NewZone translator`, `Audio → SRT (AI)` | `PlayActivity.java:3593–3596` | Translate / audio icons | Secondary actions, not primary dock items |
| `Retry`, `Provider` | `PlayActivity.java:3571–3574` | Refresh / source icons | State-dependent actions in the sheet |

Icons must remain real accessible controls: give each a meaningful content
description, a minimum 44dp touch target, visible pressed/selected state, and a
subtle active dot or selected tint. Do not remove text that is needed for
discoverability without providing a screen-reader label or a first-use hint.

### Player architecture

Retain the existing three-zone HUD and its safe-area inset behavior. Redesign
the zones rather than replacing the player:

1. **Top:** Back, title, and a compact overflow group for Lock, Rotate, PiP,
   External, and Settings. Keep the title to one ellipsized line. Show status
   only when useful.
2. **Center:** Rewind, Play/Pause, and Forward; preserve the 64dp control
   targets and the 2× hold behavior. Keep temporary gesture feedback transient.
3. **Bottom:** Scrub bar, tabular timestamps, then a fixed-width icon dock:
   Subtitles, Tracks, Aspect, Speed, Search. Do not put seven wide chips in a
   horizontal scroller. Portrait and landscape use the same control order;
   landscape may reduce spacing, not remove accessibility.
4. **Sheets:** Subtitle sheet is a short task list: language/source/sync/style,
   then a collapsed More group for provider retry, site search, translation, and
   AI actions. Tracks sheet separates audio and video quality and marks the
   selected item. Settings sheet uses segmented controls and inline values.

### Home, browser, onboarding, and settings

- **Home:** Keep one primary receiver-state card and one action. Put the
  auto-open permission explanation behind a short, dismissible disclosure.
  Move the setup checklist to an optional compact checklist, not a second
  paragraph card competing with the receiver action.
- **Browser:** Keep the omnibar and suggestion hierarchy. Raise the 8sp shield
  count to at least 10sp and use a shield icon plus count. Replace the `Auto ON`
  sentence with an icon/state switch whose accessible name says “Automatic
  refresh, on/off.” Keep URL suggestions readable at the user's system font
  scale.
- **Onboarding:** Preserve its permission and safety explanations. Remove
  duplicated instructions only after the related step is complete. Validate
  bilingual copy at narrow widths; 20sp and 17sp titles are hierarchy roles,
  not automatic font-reduction targets.
- **Settings:** Retain Simple and Advanced modes. In Simple mode, make theme,
  accent, surface, bottom-bar style, and icon style visual preview tiles.
  Advanced remains an expandable list of all current controls; do not hide or
  discard preferences. Put hex editing, fine sliders, and rare integrations in
  Advanced.

### Typography and layout system

Keep the existing `UiTokens` scale as the starting point. It already has a
4dp spacing grid, named roles, and Sinhala font weights. The problem is local
copy density and repeated ad-hoc rows, not a globally oversized type scale.
For v3.4.4:

- Use the shared roles for headings/body/captions; no new 8sp labels.
- Keep utility metadata at 10–11sp and interactive labels at 12–14sp.
- Prefer one short line plus a disclosure to multi-sentence card descriptions.
- Keep bilingual mode, but controls show one language at a time as the existing
  preference contract intends.
- Use 4/8/12/16/24dp spacing and the current palette tiers; reduce stacked
  strokes and borders. Reserve borders for focus/selection, not every card.
- Maintain 48dp recommended minimum control height, safe-area insets, text
  scaling, and contrast in Dark, AMOLED, Light, and Dynamic Color.

### Vector icon and app-mark direction

All 64 drawable XMLs are listed in the audit log with viewport, path count, and
observed stroke widths. The set is mostly 24×24, but outlined symbols span
approximately 1.6–2.4 viewport units and mixed filled/outlined metaphors remain.
Unify the outline family around a consistent apparent 1.75–1.85 stroke at
24dp, use a shared optical safe area, and reserve filled forms for selected
navigation/playback states. Inspect optical balance at 20dp and 24dp; equal XML
viewports do not guarantee equal visual weight. Replace remaining text
chevrons/arrows used as icons with existing vectors.

The launcher already has Android adaptive foreground/background and monochrome
resources. For a new editable Canva source, create one simple cinema-frame or
play-window mark with a distinct center cutout, centered inside the adaptive
safe area. Avoid wordmarks, hairlines, gradients, tiny perforations, and a
second outer rounded-square (Android applies the mask). The monochrome variant
must preserve the same silhouette and negative space; use a single white
foreground path so Android 13+ themed icons can tint it.

## Mornye comparison

The read-only reference checkout is `/tmp/mornye-ref`. The strongest
transferable patterns are connected settings surfaces and measured player
groups, not copied colors or Flutter code:

- `lib/widgets/settings_group.dart` separates connected rows from section
  containers and uses surface-container tones for hierarchy.
- `lib/widgets/player_playback_controls.dart` measures a compact transport
  group and centers the play action rather than making every action a separate
  word pill.
- `lib/widgets/mornye_player_layout.dart` composes the player around explicit
  surfaces and control groups.
- `lib/theme/app_tokens.dart` centralizes spacing/radius roles.

AnantPlay already has theme modes, accent presets, an 8/12/16/24 radius
language, and a shared text scale. The remaining gap is how screens compose
those primitives: local labels, stacked chip rows, and explanation-heavy cards
still dominate some surfaces. Transfer the hierarchy and grouping, not the
framework.

## Canva OAuth and API behavior

The studio uses a server-side Authorization Code + PKCE flow. It reads
`CANVA_CLIENT_ID` and `CANVA_CLIENT_SECRET` at runtime only; neither is embedded
in the page, returned by status endpoints, or written into reports. OAuth state
and PKCE verifiers are one-time server memory values. The session cookie is
HttpOnly, SameSite=Lax, and Secure when the HTTPS proxy is detected. Access and
refresh tokens are saved per session under `ui_studio/.canva_store/`, outside
the served directory, with a private directory and 0600 files; this path is
ignored by Git.

The default minimum scopes are `design:meta:read design:content:read`. Override
with `CANVA_SCOPES` only when the Canva Developer app has those scopes enabled.
Register the exact callback shown by the studio in Canva's Developer Portal.
The callback setting can be provided explicitly as `CANVA_REDIRECT_URI`; after
publishing, set it to the production URL. This imported project has no
production deployment yet, so its current development callback is not a
production callback and may be inaccessible to Canva while the Replit preview
is private.

Canva's API exposes per-design export formats and export jobs. SVG may be
available for some designs, but Canva can reject an SVG job as unavailable.
The studio therefore checks each design's formats, attempts SVG only when
listed, and keeps manual SVG upload as the reliable path. It does not claim that
every Canva Pro design can be exported to SVG through the API.

## SVG-to-VectorDrawable limits and bundle

The converter accepts SVG path data and common flat primitives (rectangles,
circles, ellipses, lines, polylines, polygons), simple groups, and one
translate/scale/rotate transform per group. It normalizes opaque fill/stroke
paint to white, retains supported stroke styling, checks XML, and rejects
scripts, entities, CSS, text, gradients, opacity, external content, unsupported
elements, and unsupported transform composition explicitly. Convert text to
paths and flatten complex effects in Canva before upload.

“XML-valid” means well-formed XML and supported VectorDrawable constructs; it
does not mean this workspace ran `aapt2`. No Android SDK build tools or emulator
were installed as part of this read-only Android audit. Validate downloaded XML
with the Android build before merging it into `res/`.

The 24dp icon ZIP contains `res/drawable/ic_*.xml`. The 108dp adaptive bundle
contains foreground and monochrome vector drawables, a background drawable,
and adaptive icon resources under `res/mipmap-anydpi-v26/`. Rename the generated
resources if the app uses a different launcher resource name.

## Canva callback

The production callback format is:

`https://<published-production-host>/auth/canva/callback`

No production host is available until this Repl is published. The studio
currently shows its exact development callback separately. Do not register that
development URL as the production callback for an app that needs a stable
public endpoint.
