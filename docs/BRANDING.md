# Iris branding and design system

**IRIS** — For Hermes Agent — by wutz.io

Iris (named Apollo until 2026-10-17; the application ID `io.wutz.apollo` stays so
installed apps keep updating) is an independent open-source Android client for Hermes Agent, developed by wutz.io. Not affiliated with or endorsed by Nous Research.

## Name usage

| Where | What is shown |
| --- | --- |
| Launcher label | `Iris` only (`app_name`) |
| Splash (Android 12+) | Emblem plus "IRIS" (white) and "for hermes agent" (`splash_branding`) |
| Landing, About | Lockup `IrisBrandLockup`: emblem, IRIS wordmark in white (`onSurface`), "for hermes agent" |
| About details | Developer wutz.io (https://wutz.io) and the independence notice |
| Navigation header | Mark + IRIS wordmark, subtitle "For Hermes Agent" |
| Store / releases | "Iris", developer "wutz.io", releases named "Iris vX.Y.Z — by wutz.io" |

"Hermes Agent" is used only as a factual compatibility reference. Do not use
Nous Research or Hermes Agent logos, the "Nous girl" illustrations, their
proprietary typefaces (e.g. the Rules/Collapse families from the Hermes web
UI) or any wording that suggests an official product. The Apache-2.0 and MIT
licenses of Hermes Mobile and Hermes Agent cover code, not trademarks.

## Visual direction

Classical meets technical: dark monumental surfaces, restrained antique gold
and bronze, Roman capitals, and fine technical linework (dial ticks, grid,
Greek-key band) used sparingly. No fantasy chrome, no heavy textures, no
decorative animation; readability and speed come first.

The Hermes Agent / Nous Research references informed the mood only: dark
near-black canvases and gold/amber accents (Hermes banner), stark monochrome
typographic layouts (nousresearch.com). Iris's own motif is the goddess of the
rainbow and messenger of the gods: a tall classical capital I rising above a gold
rainbow of three concentric bands, which also reads as a column under an arch.

## Emblem and icon

Source: `scripts/brand/generate_brand_assets.py` (single geometric source for
all variants). Do not edit the generated files by hand.

| Asset | File |
| --- | --- |
| Adaptive icon background / foreground | `res/drawable/ic_launcher_background.xml`, `ic_launcher_foreground.xml` |
| Themed (monochrome) icon | `res/drawable/ic_launcher_monochrome.xml` |
| Notification icon | `res/drawable/ic_notification.xml` |
| Tintable mark (in-app) | `res/drawable/ic_iris_mark.xml` |
| Full-colour emblem (in-app) | `res/drawable/ic_iris_emblem.xml` (dark surfaces), `ic_iris_emblem_light.xml` (light) |
| Wordmark (in app) | Cinzel text via `IrisWordmarkStyle` (`theme/IrisType.kt`) |
| Splash brand line | `res/drawable/splash_branding.xml` |
| SVG masters | `docs/brand/iris-icon.svg`, `iris-mark.svg`, `iris-wordmark.svg` |
| README banner | `docs/brand/iris-banner.svg` |
| Store assets | `fastlane/metadata/android/*/images/icon.png` (512²), `featureGraphic.png` (1024×500) |

Construction (108 dp canvas, safe zone r = 33): three half-ring rainbow bands
around (54, 67) (radii 27.6/23.0/18.4, 2.6 wide; outer band brightest, inner
band deepest gold), a Roman I from y 31.5 to 80 with bracketed slab serifs that
rises above the bands, and a faint dial ring with 15° ticks. The "trippy" layers:
about 60 fine rays of light behind the I inside the inner band and two
undulating halo rings (18 and 24 waves). The monochrome variant keeps only the
bands and the I so it stays legible at 24 dp.

Regenerate after a geometry change:

```bash
python3 scripts/brand/generate_brand_assets.py   # needs fontTools
```

PNG store assets are rendered from the SVGs (any SVG renderer; we use
headless Chrome at the exact target size).

## Colour

Defined once in `theme/presets/IrisScheme.kt` (the default `ThemePreset.APOLLO`).
All text/surface pairs are ≥ 4.5:1 and fixed roles are mode-independent
(`ThemePaletteTest`).

| Role | Dark (default, AMOLED) | Light |
| --- | --- | --- |
| Background / surface | `#000000` (true black) | `#F7F4EE` marble |
| Surface container low / default / highest | `#0B0B0D` / `#17171B` / `#25252B` | `#F2EEE6` / `#ECE7DD` / `#DFD8CA` |
| Text / secondary text | `#ECE8E1` marble / `#A8A49C` | `#1B1A17` / `#4F4B44` |
| Primary (antique gold) | `#D4AF72` | `#765719` |
| Primary container (bronze) | `#2E2618` | `#F1E2C2` |
| Secondary (bronze) | `#C79A6E` | `#83552B` |
| Tertiary (technical teal) | `#8EC3BA` | `#2B6660` |
| Success / warning / error / info | `#93C493` / `#DDAA5E` / `#E5857A` / `#94B9DE` | `#2D6A39` / `#805300` / `#B3261E` / `#2B5C8C` |
| Outline / outline variant | `#7D7972` / `#34343B` | `#766F64` / `#CFC7B8` |

The brief's near-black `#0B0B0D` is the first container step; the floor is
true black because AMOLED dark is the default. Material You (dynamic colour)
and the upstream presets remain selectable in Settings → Appearance.

## Typography

- Display / wordmark: **Cinzel** SemiBold, tracked 0.16 em
  (`theme/IrisType.kt`, `res/font/cinzel.ttf`, SIL OFL 1.1 —
  `docs/licenses/Cinzel-OFL.txt`). Used only for display styles and brand
  surfaces when the Iris theme is active.
- Everything else: system sans (Roboto) and system monospace for code, as in
  upstream, for readability and performance.

## Shape, spacing, motion

Unchanged upstream tokens (`theme/Shapes.kt` 4/8/12/16/28 dp, `Spacing.kt`
8 dp rhythm, `Motion.kt`). Iris adds no animation.

## Iris-specific component accents

Only when `ThemePreset.APOLLO` is active, so upstream presets look as before:

- User chat bubble: bronze `primaryContainer` with a 1 dp gold hairline
  instead of a solid gold fill (`ui/chat/UserBubble.kt`).
- Tool calls: 2 dp gold edge, brighter while the tool runs
  (`ui/chat/ToolBubble.kt`).

## Illustrations

Engraving-style illustrations in one colour, purple `#8D4BA3`, complement the
gold emblem: classical motifs (Iris with feathered wings and rainbow halo, her
pitcher, the rainbow bridge, laurel, temple, columns) drawn with fine op-art
linework. The Iris motifs replace the Apollo ones (bust, lyre, sun chariot) from
the 2026-11-17 Codex batch (`docs/brand/art/iris-art-spec.md`, provenance in
`docs/brand/art/iris-art-provenance.md`). They are original artwork from an
owner-run image batch, used only as decoration.

- Pipeline: `scripts/brand/process_art.py <batch folder>` re-inks every
  native output to exactly `#8D4BA3` with the paper turned into transparency
  (needs Pillow). One file therefore works on both themes: a purple engraving
  on marble in light mode, glowing purple lines on black in dark mode.
- App (`res/drawable-nodpi/art_*.webp`, `IrisArt` in
  `ui/common/IrisBrand.kt`): Landing, About banner, and the empty, empty-chat
  and offline states (`ui/common/StateViews.kt`). Only with the Iris theme
  and when the state has at least 360 dp of height; other presets and short
  layouts keep the icon.
- Website: `website/assets/img/*.webp`.

Native outputs are never edited; regenerate the derived files from them.

## Website

`website/` is the static site for iris.wutz.io (English, German via a
persisted toggle, system-aware light/dark with a persisted toggle). Its CSS
tokens mirror the colour table above; the header uses the vector emblem, the
footer the painted gold emblem. The `Website` workflow publishes it to GitHub
Pages and runs only on manual dispatch until the site goes live. The footer
links the imprint and privacy policy of itmitalles.de; the page's own privacy
section covers the app.
