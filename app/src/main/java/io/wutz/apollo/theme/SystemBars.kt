package io.wutz.apollo.theme

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

// Scrims androidx's enableEdgeToEdge() applies by default to three-button navigation on API 26-28.
private val LightNavigationScrim = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DarkNavigationScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b)

/**
 * Apollo: style the system bars from the in-app light/dark choice. enableEdgeToEdge() alone
 * follows the device night mode, which inverts icons (and, on API 26-28, the navigation scrim)
 * when the app theme differs, e.g. Apollo's default dark theme on a light-mode device.
 */
@Composable
fun ComponentActivity.SystemBarsForTheme(darkTheme: Boolean) {
    DisposableEffect(darkTheme) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
            navigationBarStyle = SystemBarStyle.auto(LightNavigationScrim, DarkNavigationScrim) { darkTheme },
        )
        onDispose {}
    }
}
