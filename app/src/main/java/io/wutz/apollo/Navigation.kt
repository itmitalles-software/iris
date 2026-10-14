package io.wutz.apollo

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.ws.ConnectionStatus
import io.wutz.apollo.data.ws.HermesWsClient
import io.wutz.apollo.theme.ApolloWordmarkStyle
import io.wutz.apollo.theme.LocalHermesStatusColors
import io.wutz.apollo.ui.common.DisableDrawerGestures
import io.wutz.apollo.ui.common.DrawerGestureController
import io.wutz.apollo.ui.common.LocalDrawerGestureController
import io.wutz.apollo.ui.common.LocalPersistentSidebarVisible
import io.wutz.apollo.ui.plugins.MemoryProviderDetailScreen
import io.wutz.apollo.ui.settings.ClientCertificatesPage
import io.wutz.apollo.ui.settings.SettingsAboutPage
import io.wutz.apollo.ui.settings.SettingsAppearancePage
import io.wutz.apollo.ui.settings.SettingsBehaviorPage
import io.wutz.apollo.ui.settings.SettingsChatPage
import io.wutz.apollo.ui.settings.SettingsConnectionPage
import io.wutz.apollo.ui.settings.SettingsLanguagePage
import io.wutz.apollo.ui.settings.SettingsVaultPage
import io.wutz.apollo.ui.settings.SettingsViewModel
import io.wutz.apollo.ui.toolsets.ToolsetDetailScreen
import kotlinx.coroutines.launch
import io.wutz.apollo.ui.authlogin.AuthLoginScreen as AuthLoginScreenContent
import io.wutz.apollo.ui.connectors.AccountConnectorsScreen as AccountConnectorsScreenContent
import io.wutz.apollo.ui.landing.LandingScreen as LandingScreenContent

private fun appEntryProvider(
    sessionId: String?,
    openDrawer: () -> Unit,
) = entryProvider {
    entry<LandingScreen> {
        // B7 (Jun 30 2026, kanban t_424): route landing screen buttons through navigateTo to prevent duplicate screens
        LandingScreenContent(
            onConnections = { NavigationController.navigateTo(ConnectionsScreen) },
            onAuthLogin = {
                NavigationController.navigateTo(AuthLoginScreen)
            },
        )
        // Landing doesn't use HermesScaffold — opt out of drawer gestures explicitly (issue #619).
        DisableDrawerGestures()
    }

    entry<AuthLoginScreen> {
        AuthLoginScreenContent(
            onConnected = {
                NavigationController.resetTo(ChatScreen)
            },
            onBack = {
                NavigationController.goBack()
            },
        )
        DisableDrawerGestures()
    }

    ScreenRegistry.ALL_SCREENS.forEach { screen ->
        addEntryProvider(clazz = screen.key::class) {
            screen.content(sessionId, openDrawer)
        }
    }

    // ── Settings drill-down sub-pages ───────────────────────────────────
    // Each passes drawerGesturesEnabled = false to HermesScaffold — this is the
    // single source of truth that prevents the drawer-scrim stuck-open bug
    // (issue #619). No global gesture set, no closeDrawer callback, no
    // LaunchedEffect(snapTo(Closed)) — the scaffold reconciles automatically.
    entry<ClientCertificatesScreen> {
        ClientCertificatesPage(onBack = { NavigationController.goBack() })
    }
    entry<SettingsConnection> {
        SettingsConnectionPage(
            onBack = { NavigationController.goBack() },
            onLogout = { /* handled by caller via goBack fallback */ },
            viewModel = viewModel { SettingsViewModel() },
        )
    }
    entry<AccountConnectorsScreen> {
        AccountConnectorsScreenContent(onBack = { NavigationController.goBack() })
    }
    entry<SettingsAppearance> {
        SettingsAppearancePage(
            onBack = { NavigationController.goBack() },
            viewModel = viewModel { SettingsViewModel() },
        )
    }
    entry<SettingsLanguage> {
        SettingsLanguagePage(
            onBack = { NavigationController.goBack() },
            viewModel = viewModel { SettingsViewModel() },
        )
    }
    entry<SettingsChat> {
        SettingsChatPage(
            onBack = { NavigationController.goBack() },
            viewModel = viewModel { SettingsViewModel() },
        )
    }
    entry<SettingsBehavior> {
        SettingsBehaviorPage(
            onBack = { NavigationController.goBack() },
            viewModel = viewModel { SettingsViewModel() },
        )
    }
    entry<SettingsVault> {
        SettingsVaultPage(
            onBack = { NavigationController.goBack() },
        )
    }
    entry<SettingsAbout> {
        SettingsAboutPage(
            onBack = { NavigationController.goBack() },
        )
    }

    // ── Toolset detail drill-down (issue #782) ────────────────────────
    entry<ToolsetDetailKey> { key ->
        ToolsetDetailScreen(
            name = key.name,
            label = key.label,
            onBack = { NavigationController.goBack() },
        )
    }

    // ── Memory provider detail drill-down (issue #783) ─────────────────
    entry<MemoryProviderDetailKey> { key ->
        MemoryProviderDetailScreen(
            name = key.name,
            label = key.label,
            onBack = { NavigationController.goBack() },
        )
    }

    // ── Multi-Agent Group Chat Room ─────────────────────────────────────
    entry<GroupChatKey> { key ->
        io.wutz.apollo.ui.bots.group.GroupChatScreen(
            groupName = key.groupName,
            onBack = { NavigationController.goBack() },
        )
    }

    // ── Kanban Task Detail ─────────────────────────────────────────────
    entry<KanbanTaskDetailKey> { key ->
        io.wutz.apollo.ui.kanban.KanbanTaskScreen(
            boardSlug = key.boardSlug,
            taskId = key.taskId,
            onBack = { NavigationController.goBack() },
        )
    }
}

private val EXPANDED_WIDTH = 840.dp
private val SIDEBAR_WIDTH = 300.dp

@Composable
fun MainNavigation(sessionId: String? = null) {
    val token by AuthManager.tokenFlow.collectAsState()
    val hasToken = !token.isNullOrBlank()
    val startScreen: NavKey = if (hasToken) ChatScreen else LandingScreen

    // Saveable so rotation, dark-mode or locale changes (no configChanges in the manifest) keep the
    // current screen; key() still starts a fresh stack when login or logout changes the start screen.
    val backStack = key(startScreen) { rememberNavBackStack(startScreen) }
    NavigationController.backStack = backStack

    val currentScreen = backStack.lastOrNull() ?: startScreen
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Single source of truth for drawer gestures (issue #619).
    // HermesScaffold / DisableDrawerGestures reconcile each screen's preference
    // into this controller via SideEffect; ModalNavigationDrawer reads .enabled
    // below. No DRAWER_GESTURE_SCREENS set, no LaunchedEffect(snapTo(Closed)),
    // no closeDrawer callback — the controller closes the drawer itself when a
    // screen opts out.
    val gestureController =
        remember(drawerState, scope) {
            DrawerGestureController(drawerState, scope)
        }

    // Apollo: tablets, unfolded foldables and large landscape windows get a
    // persistent sidebar instead of the modal drawer; the menu button then
    // shows or hides that sidebar.
    val windowWidthDp =
        with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.width
                .toDp()
        }
    val isExpandedWidth = hasToken && windowWidthDp >= EXPANDED_WIDTH
    var sidebarVisible by rememberSaveable { mutableStateOf(true) }

    val openDrawer: () -> Unit =
        if (isExpandedWidth) {
            { sidebarVisible = !sidebarVisible }
        } else {
            { scope.launch { drawerState.open() } }
        }

    val context = LocalContext.current
    var lastBackPressTime by remember { mutableLongStateOf(0L) }
    val backToastText = stringResource(R.string.press_back_again_to_exit)

    // Double-back to exit on root ChatScreen — unless the chat was opened from
    // a list (History, Bots): then the first back returns to that list (Telegram
    // chat-list parity) and only a further double-back exits.
    BackHandler(enabled = currentScreen == ChatScreen && backStack.size == 1) {
        val pendingReturn = NavigationController.chatReturnScreen
        if (pendingReturn != null) {
            NavigationController.consumeChatReturnScreen()
            NavigationController.navigateTo(pendingReturn)
        } else {
            val now = System.currentTimeMillis()
            if (now - lastBackPressTime < 2000L) {
                (context as? Activity)?.finish()
            } else {
                lastBackPressTime = now
                Toast.makeText(context, backToastText, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Safety fallback: if a non-chat screen somehow sits alone on stack, swipe back returns to ChatScreen
    BackHandler(
        enabled = currentScreen != ChatScreen && currentScreen != LandingScreen && backStack.size == 1,
    ) {
        NavigationController.goBack()
    }

    val drawerItems: @Composable ColumnScope.() -> Unit = {
        val connectionStatus by HermesWsClient.connectionStatus.collectAsState()
        val statusColor =
            when (connectionStatus) {
                ConnectionStatus.CONNECTED -> LocalHermesStatusColors.current.success

                ConnectionStatus.CONNECTING,
                ConnectionStatus.RECONNECTING,
                -> LocalHermesStatusColors.current.warning

                ConnectionStatus.DISCONNECTED,
                ConnectionStatus.NO_NETWORK,
                ConnectionStatus.AUTH_EXPIRED,
                -> LocalHermesStatusColors.current.error
            }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 2.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_apollo_mark),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.brand_wordmark),
                style = ApolloWordmarkStyle.copy(fontSize = 22.sp),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            val statusDescription =
                stringResource(
                    R.string.a11y_connection_status,
                    stringResource(
                        when (connectionStatus) {
                            ConnectionStatus.CONNECTED -> R.string.a11y_connection_connected
                            ConnectionStatus.CONNECTING -> R.string.chat_status_connecting
                            ConnectionStatus.RECONNECTING -> R.string.chat_status_reconnecting
                            ConnectionStatus.DISCONNECTED -> R.string.chat_status_disconnected
                            ConnectionStatus.NO_NETWORK -> R.string.chat_status_no_network
                            ConnectionStatus.AUTH_EXPIRED -> R.string.chat_status_auth_expired
                        },
                    ),
                )
            Box(
                modifier =
                    Modifier
                        .size(10.dp)
                        .background(color = statusColor, shape = CircleShape)
                        .semantics { contentDescription = statusDescription },
            )
        }
        Text(
            text = stringResource(R.string.nav_drawer_subtitle),
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp, end = 16.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        for (section in DrawerSection.entries) {
            Text(
                text = stringResource(section.titleRes).uppercase(),
                modifier =
                    Modifier
                        .padding(
                            start = 16.dp,
                            top = 8.dp,
                            bottom = 4.dp,
                            end = 16.dp,
                        ).semantics { heading() },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            ScreenRegistry.ALL_SCREENS
                .filter { it.drawerSection == section && hasToken }
                .forEach { entry ->
                    NavigationDrawerItem(
                        icon = { Icon(entry.icon, contentDescription = null) },
                        label = { Text(stringResource(entry.labelRes)) },
                        selected = currentScreen == entry.key,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(entry.key)
                        },
                        colors =
                            NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                    )
                }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(12.dp))
    }
    val screenContent: @Composable () -> Unit = {
        Scaffold(
            contentWindowInsets = WindowInsets.navigationBars,
        ) { paddingValues ->
            NavDisplay(
                backStack = backStack,
                onBack = { NavigationController.goBack() },
                entryProvider =
                    appEntryProvider(sessionId, openDrawer),
                modifier =
                    Modifier
                        .padding(paddingValues)
                        .consumeWindowInsets(paddingValues)
                        .fillMaxSize(),
            )
        }
    }

    // Leaving the modal layout must not leave a stale scrim behind.
    LaunchedEffect(isExpandedWidth) {
        if (isExpandedWidth) drawerState.snapTo(DrawerValue.Closed)
    }

    CompositionLocalProvider(
        LocalDrawerGestureController provides gestureController,
        LocalPersistentSidebarVisible provides if (isExpandedWidth) sidebarVisible else null,
    ) {
        // One layout for every width: NavDisplay keeps the same composition position, so
        // crossing the threshold (rotation, unfolding) keeps drafts and scroll state.
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = gestureController.enabled && !isExpandedWidth,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    content = drawerItems,
                )
            },
        ) {
            Row {
                if (isExpandedWidth) {
                    AnimatedVisibility(visible = sidebarVisible) {
                        Row {
                            PermanentDrawerSheet(
                                modifier =
                                    Modifier
                                        .width(SIDEBAR_WIDTH)
                                        .verticalScroll(rememberScrollState()),
                                content = drawerItems,
                            )
                            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    screenContent()
                }
            }
        }
    }
}
