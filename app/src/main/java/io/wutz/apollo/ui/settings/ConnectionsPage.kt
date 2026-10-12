package io.wutz.apollo.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wutz.apollo.NavigationController
import io.wutz.apollo.data.local.AuthManager

/** Reuse the connection settings flow, including before the first server login. */
@Composable
internal fun ConnectionsPage() {
    val token by AuthManager.tokenFlow.collectAsStateWithLifecycle()
    SettingsConnectionPage(
        onBack = { NavigationController.goBack() },
        onLogout = {},
        showLogout = !token.isNullOrBlank(),
    )
}
