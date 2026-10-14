package io.wutz.apollo

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.navigation3.runtime.serialization.NavKeySerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MainNavigation keeps its back stack in `rememberNavBackStack`, which saves it with
 * [NavBackStackSerializer] + [NavKeySerializer] across configuration changes (rotation, dark mode,
 * locale). Every key must survive that round trip, or the restore would crash or drop the user back to Chat.
 */
class NavigationBackStackSaveTest {
    private val serializer = NavBackStackSerializer(elementSerializer = NavKeySerializer<NavKey>())

    @Test
    fun everyNavigationKeySurvivesTheSavedStateRoundTrip() {
        val keys =
            listOf(ChatScreen, LandingScreen, AuthLoginScreen) +
                ScreenRegistry.ALL_SCREENS.map { it.key } +
                listOf(
                    SettingsConnection,
                    SettingsAbout,
                    OpenSourceLicensesScreen,
                    GroupChatKey(groupName = "Dream Team"),
                    ToolsetDetailKey(name = "web", label = "Web"),
                    MemoryProviderDetailKey(name = "honcho"),
                    KanbanTaskDetailKey(boardSlug = "default", taskId = "t_1"),
                )
        val backStack = NavBackStack(*keys.toTypedArray())

        val encoded = Json.encodeToString(serializer, backStack)
        val restored = Json.decodeFromString(serializer, encoded)

        assertEquals(keys, restored.toList())
    }
}
