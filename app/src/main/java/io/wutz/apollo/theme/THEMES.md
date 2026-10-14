# Themes

How theming works in Apollo, and how to add or edit a theme.

This is the implementation guide. [DESIGN.md](../../../../../../../../DESIGN.md)
defines visual and interaction requirements; the Kotlin sources define the actual
tokens. When changing tokens or behavior, update the relevant documentation too.

## Where themes live

All theme code is under:

```
app/src/main/java/io/wutz/apollo/theme/
├── Theme.kt                       # dispatcher — mode fallback + dynamic colors
├── ThemeRegistry.kt               # single preset/palette registry
├── PaletteTemplate.kt             # the template — the ONE shape every theme follows
├── Color.kt                       # shared tokens for non-theme code (status fallbacks, code blocks, …)
├── HermesStatusColors.kt          # semantic status color model (success/warning/error/info + on*)
├── Type.kt / Shapes.kt            # typography + shape tokens
├── ApolloType.kt                  # Cinzel display styles + wordmark (Apollo preset only)
├── Spacing.kt / Motion.kt         # spacing + motion tokens
├── SystemBars.kt                  # system bar icons/scrims follow the in-app light/dark choice
└── presets/                       # one file per ThemePreset — same skeleton everywhere
    ├── ApolloScheme.kt            # the default preset (docs/BRANDING.md)
    ├── DefaultScheme.kt
    ├── MonochromeScheme.kt
    ├── GruvboxScheme.kt
    ├── CatppuccinScheme.kt
    ├── AmoledScheme.kt            # dark-only (ThemeMode.DARK_ONLY)
    ├── NordScheme.kt
    └── GarnetScheme.kt
```

## The template — one shape for every theme

Every preset file is a **single `ThemePalette`** built from raw color specs via
the template in `PaletteTemplate.kt`. A theme is a pure color spec: fill in
`PaletteColors` (the Material slots) + the semantic status set
(`HermesStatusColors`) and the app does the rest. No per-preset behavior, no
aliases, no shared-token imports.

```kotlin
val MyTheme =
    buildTheme(
        dark =
            PaletteColors(
                primary = Color(0xFF…),
                // …every slot…
                status =
                    HermesStatusColors(
                        success = Color(0xFF…),
                        // …
                    ),
            ),
        light = PaletteColors(/* … */),
    )
```

The template maps `PaletteColors` into the Material 3 `ColorScheme` slots. The
Material `error` slots (`error`, `onError`, `errorContainer`, `onErrorContainer`)
are **derived** from the theme's status set — the theme author defines semantic
colors once. `surfaceTint` comes from `primary`. Every other Material color role
is required explicitly, including bright/dim surfaces and all fixed accent roles.
No role may inherit Material baseline colors. Fixed accent and foreground roles
stay identical across a preset’s shipped modes; Fixed/Dim may share an official
swatch when the source palette has no accessible tonal ladder.

Prefer upstream swatches. Document every derived color beside its declaration
with the official pairing’s measured contrast and why a derived role is needed.

## Preset conventions

- **Named swatches** for any hex reused across slots (Gruvbox bg ladder,
  Catppuccin's official names, Nord's nord0–nord15, Monochrome's mono ramp).
  A theme with repeated hexes should define them once at the top of the file.
- **Bright/faded accent split** (Gruvbox, Slate): bright variants against dark
  surfaces, muted "faded" variants against light surfaces so contrast holds
  without neon saturation. Single-accent-set themes (Nord) instead keep dark
  "on" text in both modes — pastel accents stay light, so Snow-Storm-style
  light text would fail contrast.
- **Grayscale status colors** (Monochrome, AMOLED) separate success/warning/
  error/info by lightness only — consumers must pair with icons or labels.
- Normal-text foreground/background pairs, including accent containers, fixed
  accents, semantic status fills, errors, and the surface ladder, are enforced at **>= 4.5:1 contrast**
  by `ThemePaletteTest`. The separate full-bleed header guard remains >= 3:1.

Reusable `StatusBadge` and `SessionLiveStatusIndicator` use a shared
`statusBadgeColors` mapping: status fills (`success`, `warning`, `error`, `info`)
with their matching `on*` text/icons. `onSuccess` is not a foreground token for
`successContainer`. Palette tests exercise this exact renderer mapping in every
resolved mode, including fallback modes; neutral badges use Material tokens.

Enabled Material components also have a component-state contract:
- Graphics/boundaries target >= 3:1: switch thumbs/tracks and borders, radio and
  checkbox states, slider active/inactive tracks, text-field borders, and segmented borders.
- Primary/error text and field labels on surface/background target >= 4.5:1.
- Segmented content uses the corresponding container foreground at >= 4.5:1.

`ThemeComponentContrastTest` checks the pinned Material role combinations in every
resolved preset/mode. `ThemeComponentGalleryTest` reads the installed Compose
public color defaults and verifies rendered pixels for all sixteen preset/mode
combinations, including focused/error fields. Disabled controls are deliberately
excluded from the enabled contrast contract; Material uses opacity to mute them.
Settings switches use Material defaults (`primary` track / `onPrimary` thumb),
not the reversed `primaryContainer` track / `primary` thumb combination.

Outlines must contrast against both surface and the unchecked switch track
(`surfaceContainerHighest`). Named palettes may need documented derived primary
or error tones for direct text; fixed roles still preserve their upstream accents.

## Apollo

Apollo is the default preset (`ThemePreset.APOLLO`) and the app's brand
identity: true-black and anthracite surfaces with antique gold in dark mode,
warm marble with a deepened gold in light mode. Only while it is active, the
app also uses the Cinzel display styles (`ApolloTypography`) and the Apollo
component accents (bronze user bubbles with a gold hairline, gold tool edge).
The palette, typography and accents are documented in
[docs/BRANDING.md](../../../../../../../../docs/BRANDING.md).

## Garnet

Garnet preserves Default's slate surface ladder, typography, and semantic status
colors. Its seed is `#990000`, used as the light primary and dark primary
container. Dark primary/text/icons use a lighter `#FFB4AB` for contrast; rose
secondary and copper tertiary roles, their containers, fixed accents, inverse
primary, and surface tint follow the new accent family. Both modes are shipped.

## Theme modes

A theme declares which modes it ships via the factory you build it with:

| Factory | Mode | Meaning |
|---------|------|---------|
| `buildTheme(...)` | `FULL` | bespoke dark + light palettes |
| `buildThemeDarkOnly(...)` | `DARK_ONLY` | dark only; light mode falls back to the `Default` palette (AMOLED) |
| `buildThemeLightOnly(...)` | `LIGHT_ONLY` | light only; dark mode falls back to the `Default` palette |

The dispatcher falls back to the `Default` (Slate) palette for any mode a preset
doesn't ship — never to a sibling preset.

## The dispatcher

`ThemeRegistry.kt` maps every `ThemePreset` to one `ThemePalette`. Resolution,
UI selection, and palette tests use this registry. An exact enum/registry coverage
test rejects missing, duplicate, or reordered entries. Android string resources
stay in the UI’s exhaustive label mapping, outside the palette model.

`Theme.kt` reads `preset.palette()` and applies the mode fallback above.

Dynamic (Material You) color on API 31+ can optionally override the preset scheme when
`useDynamicColors = true` (defaults to `false`). Semantic status colors are always resolved from the
active preset via `LocalHermesStatusColors`. The preset selector stays enabled
and explains that it controls status colors while Material You supplies app colors.

## Adding a new theme

1. **Create** `presets/<Name>Scheme.kt` as a template fill (see above) —
   `buildTheme` for a full theme, `buildThemeDarkOnly` / `buildThemeLightOnly`
   for single-mode themes. Every status color must be bespoke — no aliasing.
2. **Register** the new palette in `ThemeRegistry.kt`.
3. **Add the enum entry** `MY_THEME` to `ThemePreset` in `Theme.kt`.
4. **Wire the UI**: add the exhaustive label mapping in
   `ui/settings/components/AppearanceSection.kt` (and any string resource).
5. **Verify**:
   ```bash
   ./gradlew ktlintCheck testDebugUnitTest
   ```
   `ThemePaletteTest` checks text contrast, complete Material slot mapping,
   mode-independent fixed roles, registry coverage, and mode/fallback invariants.

## Conventions

- ktlint 1.8.0 is enforced in CI. `./gradlew ktlintCheck` runs the `ktlint`
  binary, so it must be on `PATH`; `./gradlew gate` runs it together with the
  rest of the local gate (see [BUILD.md](../../../../../../../../BUILD.md)).
- Import order is ASCII-lexicographic (uppercase before lowercase).
- Every change goes through a PR targeting `main`.
