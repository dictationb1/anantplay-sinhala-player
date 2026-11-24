# Liquid-Glass Cinema brand assets — pure-SVG suite

This directory holds the **1:1 Canva Pro drop-in slot** for brand artwork.
Everything shipped in v3.4.6 is authored as pure UTF-8 SVG (`flutter_svg`
renders it razor-sharp at every DPI; VectorDrawable XML covers the launcher):

| Slot | Shipped (v3.4.6) | Canva Pro replacement |
| --- | --- | --- |
| App logo / lockup | `onboarding/welcome.svg` | export → `brand/logo.svg` |
| Onboarding heroes (5) | `onboarding/*.svg` | export → same file names |
| Empty states (2) | `empty/*.svg` | export → same file names |
| Chrome icons (9) | `icons/ic_*.svg` | export → same file names |
| Adaptive launcher | `android/.../ic_launcher_*.xml` | export → `brand/` + re-run `scripts/sync-vector.sh` |

**To replace with a Canva Pro export (1:1):** open the design in Canva,
Share → Download → **SVG** (Pro), name the file exactly as in the table
above, and drop it into `ui/assets/` here — the UI picks it up on the next
build with zero code changes. Keep the deep-teal `#1E4E4F` lineage for a
seamless fit with the Mornye glass chrome.

License note: these SVGs are AnantPlay originals (repo LICENSE applies);
they carry no Canva-embedded fonts or raster images, so no binary blob ever
crosses the MCP transport.
