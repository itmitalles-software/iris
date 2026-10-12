package io.wutz.apollo.theme.presets

import androidx.compose.ui.graphics.Color
import io.wutz.apollo.theme.HermesStatusColors
import io.wutz.apollo.theme.PaletteColors
import io.wutz.apollo.theme.buildTheme

// ---------------------------------------------------------------------
// Apollo — the app's default identity. Dark mode is true black (#000000)
// so OLED panels switch those pixels off; a warm sun-gold accent carries
// the brand. Light mode is a clean warm-white counterpart with a deeper
// ochre accent for contrast on light surfaces.
// ---------------------------------------------------------------------

// Fixed roles are identical in both modes (Material 3 contract)
private val FixedGold = Color(0xFFFFDF9E)
private val FixedGoldDim = Color(0xFFF5C451)
private val OnFixedGold = Color(0xFF261A00)
private val OnFixedGoldVariant = Color(0xFF5C4300)
private val FixedSky = Color(0xFFD4E3FF)
private val FixedSkyDim = Color(0xFFA4C8FF)
private val OnFixedSky = Color(0xFF001C38)
private val OnFixedSkyVariant = Color(0xFF1A4A7A)
private val FixedLaurel = Color(0xFFB7F1C2)
private val FixedLaurelDim = Color(0xFF9BD5A7)
private val OnFixedLaurel = Color(0xFF00210B)
private val OnFixedLaurelVariant = Color(0xFF175230)

// Dark swatches
private val DarkFloor = Color(0xFF000000)
private val DarkContainer = Color(0xFF0E0E0F)
private val DarkContainerHigh = Color(0xFF19191B)
private val DarkContainerHighest = Color(0xFF242427)
private val DarkOutline = Color(0xFF8E8E93)
private val DarkOutlineVariant = Color(0xFF3A3A3D)
private val DarkText = Color(0xFFF2F2F2)
private val DarkTextDim = Color(0xFFB8B8BD)
private val DarkGold = Color(0xFFFFC857) // primary accent
private val DarkGoldContainer = Color(0xFF3D2E0A)
private val DarkOnGoldContainer = Color(0xFFFFE2A3)
private val DarkSky = Color(0xFF9CC7FF) // secondary accent
private val DarkSkyContainer = Color(0xFF16273D)
private val DarkLaurel = Color(0xFF8FD6A0) // tertiary accent + success
private val DarkLaurelContainer = Color(0xFF12301C)
private val DarkAmber = Color(0xFFFFB86B)
private val DarkAmberContainer = Color(0xFF3B2410)
private val DarkRed = Color(0xFFFF8A80)
private val DarkRedContainer = Color(0xFF3F1412)
private val DarkInfo = Color(0xFF8ECEFF)
private val DarkInfoContainer = Color(0xFF102A3D)

// Light swatches
private val LightFloor = Color(0xFFFFFDF8)
private val LightContainerLow = Color(0xFFFAF6EE)
private val LightContainer = Color(0xFFF4EFE5)
private val LightContainerHigh = Color(0xFFEEE8DC)
private val LightContainerHighest = Color(0xFFE7E0D2)
private val LightOutline = Color(0xFF7A746A)
private val LightOutlineVariant = Color(0xFFD0C8B9)
private val LightText = Color(0xFF1C1B18)
private val LightTextDim = Color(0xFF4D483F)
private val LightGold = Color(0xFF7A5900) // primary accent (AA on white)
private val LightGoldContainer = Color(0xFFFFDF9E)
private val LightOnGoldContainer = Color(0xFF261A00)
private val LightSky = Color(0xFF2D5F94)
private val LightSkyContainer = Color(0xFFD4E3FF)
private val LightLaurel = Color(0xFF2B6B3C)
private val LightLaurelContainer = Color(0xFFB7F1C2)
private val LightAmber = Color(0xFF8A5100)
private val LightAmberContainer = Color(0xFFFFDCBC)
private val LightRed = Color(0xFFBA1A1A)
private val LightRedContainer = Color(0xFFFFDAD6)
private val LightInfo = Color(0xFF00629D)
private val LightInfoContainer = Color(0xFFCFE5FF)

val ApolloTheme =
    buildTheme(
        dark =
            PaletteColors(
                primary = DarkGold,
                onPrimary = DarkFloor,
                primaryContainer = DarkGoldContainer,
                onPrimaryContainer = DarkOnGoldContainer,
                secondary = DarkSky,
                onSecondary = DarkFloor,
                secondaryContainer = DarkSkyContainer,
                onSecondaryContainer = DarkSky,
                tertiary = DarkLaurel,
                onTertiary = DarkFloor,
                tertiaryContainer = DarkLaurelContainer,
                onTertiaryContainer = DarkLaurel,
                background = DarkFloor,
                onBackground = DarkText,
                surface = DarkFloor,
                onSurface = DarkText,
                surfaceVariant = DarkContainerHigh,
                onSurfaceVariant = DarkTextDim,
                surfaceDim = DarkFloor,
                surfaceBright = DarkContainerHighest,
                primaryFixed = FixedGold,
                primaryFixedDim = FixedGoldDim,
                onPrimaryFixed = OnFixedGold,
                onPrimaryFixedVariant = OnFixedGoldVariant,
                secondaryFixed = FixedSky,
                secondaryFixedDim = FixedSkyDim,
                onSecondaryFixed = OnFixedSky,
                onSecondaryFixedVariant = OnFixedSkyVariant,
                tertiaryFixed = FixedLaurel,
                tertiaryFixedDim = FixedLaurelDim,
                onTertiaryFixed = OnFixedLaurel,
                onTertiaryFixedVariant = OnFixedLaurelVariant,
                surfaceContainerLowest = DarkFloor,
                surfaceContainerLow = DarkFloor,
                surfaceContainer = DarkContainer,
                surfaceContainerHigh = DarkContainerHigh,
                surfaceContainerHighest = DarkContainerHighest,
                inverseSurface = DarkText,
                inverseOnSurface = DarkFloor,
                inversePrimary = LightGold,
                outline = DarkOutline,
                outlineVariant = DarkOutlineVariant,
                scrim = Color.Black,
                status =
                    HermesStatusColors(
                        success = DarkLaurel,
                        successContainer = DarkLaurelContainer,
                        onSuccess = DarkFloor,
                        warning = DarkAmber,
                        warningContainer = DarkAmberContainer,
                        onWarning = DarkFloor,
                        error = DarkRed,
                        errorContainer = DarkRedContainer,
                        onError = DarkFloor,
                        onErrorContainer = DarkRed,
                        info = DarkInfo,
                        infoContainer = DarkInfoContainer,
                        onInfo = DarkFloor,
                    ),
            ),
        light =
            PaletteColors(
                primary = LightGold,
                onPrimary = Color.White,
                primaryContainer = LightGoldContainer,
                onPrimaryContainer = LightOnGoldContainer,
                secondary = LightSky,
                onSecondary = Color.White,
                secondaryContainer = LightSkyContainer,
                onSecondaryContainer = LightText,
                tertiary = LightLaurel,
                onTertiary = Color.White,
                tertiaryContainer = LightLaurelContainer,
                onTertiaryContainer = LightText,
                background = LightFloor,
                onBackground = LightText,
                surface = LightFloor,
                onSurface = LightText,
                surfaceVariant = LightContainerHigh,
                onSurfaceVariant = LightTextDim,
                surfaceDim = LightContainerHighest,
                surfaceBright = LightFloor,
                primaryFixed = FixedGold,
                primaryFixedDim = FixedGoldDim,
                onPrimaryFixed = OnFixedGold,
                onPrimaryFixedVariant = OnFixedGoldVariant,
                secondaryFixed = FixedSky,
                secondaryFixedDim = FixedSkyDim,
                onSecondaryFixed = OnFixedSky,
                onSecondaryFixedVariant = OnFixedSkyVariant,
                tertiaryFixed = FixedLaurel,
                tertiaryFixedDim = FixedLaurelDim,
                onTertiaryFixed = OnFixedLaurel,
                onTertiaryFixedVariant = OnFixedLaurelVariant,
                surfaceContainerLowest = Color.White,
                surfaceContainerLow = LightContainerLow,
                surfaceContainer = LightContainer,
                surfaceContainerHigh = LightContainerHigh,
                surfaceContainerHighest = LightContainerHighest,
                inverseSurface = LightText,
                inverseOnSurface = LightFloor,
                inversePrimary = DarkGold,
                outline = LightOutline,
                outlineVariant = LightOutlineVariant,
                scrim = Color.Black,
                status =
                    HermesStatusColors(
                        success = LightLaurel,
                        successContainer = LightLaurelContainer,
                        onSuccess = Color.White,
                        warning = LightAmber,
                        warningContainer = LightAmberContainer,
                        onWarning = Color.White,
                        error = LightRed,
                        errorContainer = LightRedContainer,
                        onError = Color.White,
                        onErrorContainer = LightText,
                        info = LightInfo,
                        infoContainer = LightInfoContainer,
                        onInfo = Color.White,
                    ),
            ),
    )
