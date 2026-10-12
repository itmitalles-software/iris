# Apollo branding and design system

**APOLLO** — For Hermes Agent — by wutz.io

Apollo is an independent open-source Android client for Hermes Agent, developed by wutz.io. Not affiliated with or endorsed by Nous Research.

## Name usage

| Where | What is shown |
| --- | --- |
| Launcher label | `Apollo` only (`app_name`) |
| Splash (Android 12+) | Emblem plus "APOLLO" (white) and "for hermes agent" (`splash_branding`) |
| Landing, About | Lockup `ApolloBrandLockup`: emblem, APOLLO wordmark in white (`onSurface`), "for hermes agent" |
| About details | Developer wutz.io (https://wutz.io) and the independence notice |
| Navigation header | Mark + APOLLO wordmark, subtitle "For Hermes Agent" |
| Store / releases | "Apollo", developer "wutz.io", releases named "Apollo vX.Y.Z — by wutz.io" |

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
typographic layouts (nousresearch.com). Apollo's own motif is the god of light:
a radiate sun crown above a classical capital A, which also reads as a temple
pediment or bust.

## Emblem and icon

Source: `scripts/brand/generate_brand_assets.py` (single geometric source for
all variants). Do not edit the generated files by hand.

| Asset | File |
| --- | --- |
| Adaptive icon background / foreground | `res/drawable/ic_launcher_background.xml`, `ic_launcher_foreground.xml` |
| Themed (monochrome) icon | `res/drawable/ic_launcher_monochrome.xml` |
| Notification icon | `res/drawable/ic_notification.xml` |
| Tintable mark (in-app) | `res/drawable/ic_apollo_mark.xml` |
| Full-colour emblem (in-app) | `res/drawable/ic_apollo_emblem.xml` (dark surfaces), `ic_apollo_emblem_light.xml` (light) |
| Wordmark (in app) | Cinzel text via `ApolloWordmarkStyle` (`theme/ApolloType.kt`) |
| Splash brand line | `res/drawable/splash_branding.xml` |
| SVG masters | `docs/brand/apollo-icon.svg`, `apollo-mark.svg`, `apollo-wordmark.svg` |
| README banner | `docs/brand/apollo-banner.svg` |
| Store assets | `fastlane/metadata/android/*/images/icon.png` (512²), `featureGraphic.png` (1024×500) |

Construction (108 dp canvas, safe zone r = 33): nine tapered rays on an arc
around (54, 63), a thin diadem arc, a Roman A with a thin left stem, heavy
right stem and slab serifs, and a faint dial ring with 15° ticks. The
monochrome variant keeps only rays and A so it stays legible at 24 dp.

Regenerate after a geometry change:

```bash
python3 scripts/brand/generate_brand_assets.py   # needs fontTools
```

PNG store assets are rendered from the SVGs (any SVG renderer; we use
headless Chrome at the exact target size).

## Colour

Defined once in `theme/presets/ApolloScheme.kt` (the default `ThemePreset.APOLLO`).
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
  (`theme/ApolloType.kt`, `res/font/cinzel.ttf`, SIL OFL 1.1 —
  `docs/licenses/Cinzel-OFL.txt`). Used only for display styles and brand
  surfaces when the Apollo theme is active.
- Everything else: system sans (Roboto) and system monospace for code, as in
  upstream, for readability and performance.

## Shape, spacing, motion

Unchanged upstream tokens (`theme/Shapes.kt` 4/8/12/16/28 dp, `Spacing.kt`
8 dp rhythm, `Motion.kt`). Apollo adds no animation.

## Apollo-specific component accents

Only when `ThemePreset.APOLLO` is active, so upstream presets look as before:

- User chat bubble: bronze `primaryContainer` with a 1 dp gold hairline
  instead of a solid gold fill (`ui/chat/UserBubble.kt`).
- Tool calls: 2 dp gold edge, brighter while the tool runs
  (`ui/chat/ToolBubble.kt`).

## Later improvements

Vector artwork is deliberately geometric. Richer illustration (for example a
rendered marble Apollo bust for store art or an onboarding header) needs an
image-generation or 3D pipeline and a licence review; it is not part of the
app today.
