package io.wutz.apollo.theme

import io.wutz.apollo.theme.presets.AmoledTheme
import io.wutz.apollo.theme.presets.CatppuccinTheme
import io.wutz.apollo.theme.presets.DefaultTheme
import io.wutz.apollo.theme.presets.GarnetTheme
import io.wutz.apollo.theme.presets.GruvboxTheme
import io.wutz.apollo.theme.presets.IrisTheme
import io.wutz.apollo.theme.presets.MonochromeTheme
import io.wutz.apollo.theme.presets.NordTheme

internal data class ThemeDefinition(
    val preset: ThemePreset,
    val palette: ThemePalette,
)

// Regression theme-contract: selection, resolution, and tests share this registry.
internal val ThemeRegistry =
    listOf(
        ThemeDefinition(ThemePreset.APOLLO, IrisTheme),
        ThemeDefinition(ThemePreset.DEFAULT, DefaultTheme),
        ThemeDefinition(ThemePreset.MONOCHROME, MonochromeTheme),
        ThemeDefinition(ThemePreset.GRUVBOX, GruvboxTheme),
        ThemeDefinition(ThemePreset.CATPPUCCIN, CatppuccinTheme),
        ThemeDefinition(ThemePreset.AMOLED, AmoledTheme),
        ThemeDefinition(ThemePreset.NORD, NordTheme),
        ThemeDefinition(ThemePreset.GARNET, GarnetTheme),
    )

internal fun ThemePreset.palette(): ThemePalette = ThemeRegistry.single { it.preset == this }.palette
