package io.wutz.apollo.ui.plugins

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import io.wutz.apollo.data.model.PluginInfo
import io.wutz.apollo.theme.HermesControlTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class PluginCardTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun inactiveUserPluginOffersEnableAndUninstallInsteadOfInstall() {
        val viewModel = PluginsViewModel()
        composeRule.setContent {
            HermesControlTheme {
                PluginCard(
                    plugin = PluginInfo(name = "demo", source = "user", runtimeStatus = "inactive", canRemove = true),
                    state = PluginsUiState(),
                    viewModel = viewModel,
                    onClick = {},
                )
            }
        }

        composeRule.onNodeWithText("Install", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("Enable", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Uninstall", useUnmergedTree = true).assertExists()
    }

    @Test
    fun bundledPluginNeverShowsUninstall() {
        val viewModel = PluginsViewModel()
        composeRule.setContent {
            HermesControlTheme {
                PluginCard(
                    plugin =
                        PluginInfo(
                            name = "builtin",
                            source = "bundled",
                            runtimeStatus = "inactive",
                            canRemove = true,
                        ),
                    state = PluginsUiState(),
                    viewModel = viewModel,
                    onClick = {},
                )
            }
        }

        composeRule.onNodeWithText("Uninstall", useUnmergedTree = true).assertDoesNotExist()
    }
}
