package io.wutz.apollo

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.ViewKanban
import androidx.compose.material.icons.filled.Webhook
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import io.wutz.apollo.ui.common.NeurologyIcon
import io.wutz.apollo.ui.settings.ConnectionsPage
import io.wutz.apollo.ui.achievements.AchievementsScreen as AchievementsScreenContent
import io.wutz.apollo.ui.analytics.AnalyticsScreen as AnalyticsScreenContent
import io.wutz.apollo.ui.billing.BillingScreen as BillingScreenContent
import io.wutz.apollo.ui.bots.BotsScreen as BotsScreenContent
import io.wutz.apollo.ui.channels.ChannelsScreen as ChannelsScreenContent
import io.wutz.apollo.ui.chat.ChatScreen as ChatScreenContent
import io.wutz.apollo.ui.config.ConfigScreen as ConfigScreenContent
import io.wutz.apollo.ui.cron.CronJobsScreen as CronJobsScreenContent
import io.wutz.apollo.ui.files.FilesScreen as FilesScreenContent
import io.wutz.apollo.ui.gateway.GatewayScreen as GatewayScreenContent
import io.wutz.apollo.ui.kanban.KanbanScreen as KanbanScreenContent
import io.wutz.apollo.ui.keys.KeysScreen as KeysScreenContent
import io.wutz.apollo.ui.logs.LogsScreen as LogsScreenContent
import io.wutz.apollo.ui.mcp.McpServersScreen as McpServersScreenContent
import io.wutz.apollo.ui.memory.MemoryScreen as MemoryScreenContent
import io.wutz.apollo.ui.model.ModelScreen as ModelScreenContent
import io.wutz.apollo.ui.pairing.PairingScreen as PairingScreenContent
import io.wutz.apollo.ui.plugins.PluginsScreen as PluginsScreenContent
import io.wutz.apollo.ui.profiles.ProfilesScreen as ProfilesScreenContent
import io.wutz.apollo.ui.projects.ProjectsScreen as ProjectsScreenContent
import io.wutz.apollo.ui.providers.ProvidersScreen as ProvidersScreenContent
import io.wutz.apollo.ui.sessions.SessionsScreen as HistoryScreenContent
import io.wutz.apollo.ui.settings.SettingsScreen as SettingsScreenContent
import io.wutz.apollo.ui.skills.SkillsScreen as SkillsScreenContent
import io.wutz.apollo.ui.system.SystemScreen as SystemScreenContent
import io.wutz.apollo.ui.toolsets.ToolsetsScreen as ToolsetsScreenContent
import io.wutz.apollo.ui.webhooks.WebhooksScreen as WebhooksScreenContent

enum class DrawerSection(
    @param:StringRes val titleRes: Int,
) {
    CONVERSE(R.string.nav_drawer_section_converse),
    AUTOMATE(R.string.nav_drawer_section_automate),
    CONFIGURE(R.string.nav_drawer_section_configure),
    INSPECT(R.string.nav_drawer_section_inspect),
}

data class ScreenDefinition(
    val key: NavKey,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
    val drawerSection: DrawerSection?,
    val content: @Composable (sessionId: String?, openDrawer: () -> Unit) -> Unit,
)

object ScreenRegistry {
    val ALL_SCREENS =
        listOf(
            ScreenDefinition(
                ChatScreen,
                R.string.screen_chat,
                Icons.AutoMirrored.Filled.Chat,
                DrawerSection.CONVERSE,
            ) { sessionId, openDrawer -> ChatScreenContent(onOpenDrawer = openDrawer, sessionId = sessionId) },
            ScreenDefinition(
                HistoryScreen,
                R.string.screen_history,
                Icons.Filled.History,
                DrawerSection.CONVERSE,
            ) { sessionId, openDrawer -> HistoryScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                ProjectsScreen,
                R.string.screen_projects,
                Icons.Filled.Workspaces,
                DrawerSection.CONVERSE,
            ) { sessionId, openDrawer -> ProjectsScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                ProfilesScreen,
                R.string.screen_profiles,
                Icons.Filled.AccountCircle,
                DrawerSection.CONVERSE,
            ) { sessionId, openDrawer -> ProfilesScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                BotsScreen,
                R.string.screen_bots,
                Icons.Filled.SmartToy,
                DrawerSection.CONVERSE,
            ) { sessionId, openDrawer -> BotsScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                CronJobsScreen,
                R.string.screen_cron,
                Icons.Filled.Schedule,
                DrawerSection.AUTOMATE,
            ) { sessionId, openDrawer -> CronJobsScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                WebhooksScreen,
                R.string.screen_webhooks,
                Icons.Filled.Webhook,
                DrawerSection.AUTOMATE,
            ) { sessionId, openDrawer -> WebhooksScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                KanbanScreen,
                R.string.screen_kanban,
                Icons.Filled.ViewKanban,
                DrawerSection.AUTOMATE,
            ) { sessionId, openDrawer -> KanbanScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                SkillsScreen,
                R.string.screen_skills,
                Icons.Filled.Inventory2,
                DrawerSection.CONFIGURE,
            ) { sessionId, openDrawer -> SkillsScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                ToolsetsScreen,
                R.string.screen_toolsets,
                Icons.Filled.Build,
                DrawerSection.CONFIGURE,
            ) { sessionId, openDrawer -> ToolsetsScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                PluginsScreen,
                R.string.screen_plugins,
                Icons.Filled.Extension,
                DrawerSection.CONFIGURE,
            ) { sessionId, openDrawer -> PluginsScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                ConfigScreen,
                R.string.screen_config,
                Icons.Filled.Code,
                DrawerSection.CONFIGURE,
            ) { sessionId, openDrawer -> ConfigScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                McpServersScreen,
                R.string.screen_mcp_servers,
                Icons.Filled.Power,
                DrawerSection.CONFIGURE,
            ) { sessionId, openDrawer -> McpServersScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                MemoryScreen,
                R.string.screen_memory,
                NeurologyIcon,
                DrawerSection.CONFIGURE,
            ) { sessionId, openDrawer -> MemoryScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                ModelScreen,
                R.string.screen_models,
                Icons.Filled.Psychology,
                DrawerSection.CONFIGURE,
            ) { sessionId, openDrawer -> ModelScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                PairingScreen,
                R.string.screen_pairing,
                Icons.Filled.Devices,
                DrawerSection.CONFIGURE,
            ) { sessionId, openDrawer -> PairingScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                KeysScreen,
                R.string.screen_keys,
                Icons.Filled.Key,
                DrawerSection.CONFIGURE,
            ) { sessionId, openDrawer -> KeysScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                ChannelsScreen,
                R.string.screen_channels,
                Icons.Filled.Sensors,
                DrawerSection.CONFIGURE,
            ) { sessionId, openDrawer -> ChannelsScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                ProvidersScreen,
                R.string.screen_providers,
                Icons.Filled.Cloud,
                DrawerSection.CONFIGURE,
            ) { sessionId, openDrawer -> ProvidersScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                GatewayScreen,
                R.string.screen_gateway,
                Icons.Filled.Bolt,
                DrawerSection.INSPECT,
            ) { sessionId, openDrawer -> GatewayScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                SystemScreen,
                R.string.screen_system,
                Icons.Filled.Dns,
                DrawerSection.INSPECT,
            ) { sessionId, openDrawer -> SystemScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                LogsScreen,
                R.string.screen_logs,
                Icons.Filled.HistoryEdu,
                DrawerSection.INSPECT,
            ) { sessionId, openDrawer -> LogsScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                AnalyticsScreen,
                R.string.screen_analytics,
                Icons.Filled.BarChart,
                DrawerSection.INSPECT,
            ) { sessionId, openDrawer -> AnalyticsScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                BillingScreen,
                R.string.screen_billing,
                Icons.Filled.AccountBalanceWallet,
                DrawerSection.INSPECT,
            ) { sessionId, openDrawer -> BillingScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                FilesScreen,
                R.string.screen_files,
                Icons.Filled.Folder,
                DrawerSection.INSPECT,
            ) { sessionId, openDrawer -> FilesScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                AchievementsScreen,
                R.string.screen_achievements,
                Icons.Filled.EmojiEvents,
                DrawerSection.INSPECT,
            ) { sessionId, openDrawer -> AchievementsScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                ConnectionsScreen,
                R.string.screen_connections,
                Icons.Filled.Dns,
                null,
            ) { _, _ -> ConnectionsPage() },
            ScreenDefinition(
                SettingsScreen,
                R.string.screen_settings,
                Icons.Filled.Settings,
                DrawerSection.INSPECT,
            ) { sessionId, openDrawer ->
                SettingsScreenContent(
                    onOpenDrawer = openDrawer,
                    onNavigateToLogin = {
                        NavigationController.navigateTo(AuthLoginScreen)
                    },
                )
            },
        )
}
