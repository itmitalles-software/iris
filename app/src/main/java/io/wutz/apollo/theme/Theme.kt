package io.wutz.apollo.theme

import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import io.wutz.apollo.theme.presets.DefaultTheme
import kotlinx.serialization.Serializable

@Serializable
enum class ThemePreference { SYSTEM, LIGHT, DARK }

@Serializable
enum class ThemePreset { APOLLO, DEFAULT, MONOCHROME, GRUVBOX, CATPPUCCIN, AMOLED, NORD, GARNET }

val LocalThemePreference = compositionLocalOf { ThemePreference.DARK }
val LocalThemePreset = compositionLocalOf { ThemePreset.APOLLO }
val LocalChatFontScale = compositionLocalOf { 1.0f }

/**
 * Resolve the Material 3 [ColorScheme] for a preset + dark flag.
 *
 * A preset that doesn't ship the requested mode (DARK_ONLY / LIGHT_ONLY —
 * e.g. AMOLED has no light palette) falls back to the default theme's
 * palette for that mode.
 */
internal fun resolveColorScheme(
    preset: ThemePreset,
    darkTheme: Boolean,
): ColorScheme {
    val theme = preset.palette()
    // DefaultTheme is FULL — its scheme is never null for either mode.
    return theme.schemeFor(darkTheme) ?: requireNotNull(DefaultTheme.schemeFor(darkTheme))
}

/**
 * Resolve the semantic status colors for a preset + dark flag.
 * Same fallback rule as the scheme.
 */
internal fun resolveStatusColors(
    preset: ThemePreset,
    darkTheme: Boolean,
): HermesStatusColors {
    val theme = preset.palette()
    // DefaultTheme is FULL — its status colors are never null for either mode.
    return theme.statusFor(darkTheme) ?: requireNotNull(DefaultTheme.statusFor(darkTheme))
}

@Composable
fun HermesControlTheme(
    themePreference: ThemePreference = LocalThemePreference.current,
    useDynamicColors: Boolean = false,
    themePreset: ThemePreset = ThemePreset.APOLLO,
    chatFontScale: Float = LocalChatFontScale.current,
    content: @Composable () -> Unit,
) {
    val darkTheme =
        when (themePreference) {
            ThemePreference.SYSTEM -> isSystemInDarkTheme()
            ThemePreference.LIGHT -> false
            ThemePreference.DARK -> true
        }

    val context = LocalContext.current
    val dynamicAvailable =
        useDynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme =
        if (dynamicAvailable) {
            if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        } else {
            resolveColorScheme(themePreset, darkTheme)
        }

    val statusColors = resolveStatusColors(themePreset, darkTheme)

    // enableEdgeToEdge() picks system-bar icon colors from the *system* night
    // mode. The in-app theme can differ (Apollo defaults to dark), so keep the
    // bar icons readable against the app's own background.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalThemePreference provides themePreference,
        LocalThemePreset provides themePreset,
        LocalChatFontScale provides chatFontScale,
        LocalHermesStatusColors provides statusColors,
        LocalSpacing provides SpacingDefaults,
        LocalMotion provides MotionDefaults,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = HermesShapes,
            content = content,
        )
    }
}

private tailrec fun android.content.Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
