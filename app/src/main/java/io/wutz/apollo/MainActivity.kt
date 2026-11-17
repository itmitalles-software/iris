package io.wutz.apollo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.wutz.apollo.applock.AppLock
import io.wutz.apollo.applock.AppLockGate
import io.wutz.apollo.applock.AppLockPrompt
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.update.UpdateNoticeManager
import io.wutz.apollo.data.ws.HermesWsClient
import io.wutz.apollo.notification.NotificationHelper
import io.wutz.apollo.notification.NotificationReplyReceiver
import io.wutz.apollo.share.ShareInbox
import io.wutz.apollo.share.ShareIntentParser
import io.wutz.apollo.shortcuts.AppShortcuts
import io.wutz.apollo.shortcuts.SessionShortcuts
import io.wutz.apollo.shortcuts.ShortcutRequest
import io.wutz.apollo.theme.HermesControlTheme
import io.wutz.apollo.theme.SystemBarsForTheme
import io.wutz.apollo.theme.resolvesToDark
import io.wutz.apollo.ui.common.ErrorState
import io.wutz.apollo.ui.common.LoadingState
import io.wutz.apollo.util.LocaleContextWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    companion object {
        /**
         * Action that identifies notification-driven "open this chat" intents.
         * [ChatNotificationService] stamps it on the content intent; the
         * consumer refuses anything else (issue #832 — MainActivity is
         * exported as the launcher, so a foreign app could otherwise inject
         * a session id via an explicit intent).
         */
        const val ACTION_OPEN_CHAT_FROM_NOTIFICATION =
            "io.wutz.apollo.ACTION_OPEN_CHAT_FROM_NOTIFICATION"

        /**
         * Non-exported `<activity-alias>` of this activity. Only Iris's own
         * notification PendingIntents can target it: a foreign app that names
         * it gets a SecurityException, and one that names MainActivity directly
         * keeps MainActivity as the component. The action alone is forgeable.
         */
        const val NOTIFICATION_ENTRY_ALIAS = "io.wutz.apollo.NotificationEntryAlias"

        /** True only for an open-chat intent that came through [NOTIFICATION_ENTRY_ALIAS]. */
        internal fun isTrustedNotificationIntent(
            action: String?,
            componentClassName: String?,
        ): Boolean = action == ACTION_OPEN_CHAT_FROM_NOTIFICATION && componentClassName == NOTIFICATION_ENTRY_ALIAS

        /**
         * A relaunch from Recents (after the activity finished, or after a reboot)
         * replays the task's base intent with this flag. Its share or notification
         * payload was already handled when it first arrived.
         */
        internal fun isReplayedFromHistory(flags: Int): Boolean =
            flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
    }

    private var baseContextLanguageCode: String = LocaleContextWrapper.SYSTEM_LANGUAGE

    // Registers its activity-result launcher, so it must exist before the activity starts.
    private val appLockPrompt = AppLockPrompt(this)

    /**
     * Apply the user-selected display language before any view is inflated.
     * Reads the persisted code from [AuthManager]; an uninitialized store
     * (shouldn't happen here, but guarded) falls back to the device locale.
     */
    override fun attachBaseContext(base: Context) {
        val code =
            runCatching { AuthManager.getAppLanguage() }
                .getOrDefault(LocaleContextWrapper.SYSTEM_LANGUAGE)
        baseContextLanguageCode = code
        super.attachBaseContext(LocaleContextWrapper.wrapWithCode(base, code))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A recreated activity (rotation, language switch, process restore) still
        // holds the original launch intent; only the first creation consumes it.
        // A Recents relaunch replays the task's base intent: never consume it again.
        if (savedInstanceState == null && !isReplayedFromHistory(intent.flags)) {
            consumeNotificationIntent(intent)
            consumeShareIntent(intent)
            consumeShortcutIntent(intent)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                AuthManager.initializationState.collect { state ->
                    if (state == AuthManager.InitializationState.Ready) {
                        if (AuthManager.isGatedMode() || !AuthManager.getToken().isNullOrBlank()) {
                            HermesWsClient.connect()
                        }
                    }
                }
            }
        }

        appLockPrompt.observe()
        enableEdgeToEdge()
        setContent {
            val themePreference by AuthManager.themePreferenceFlow.collectAsState()
            val useDynamicColors by AuthManager.useDynamicColorsFlow.collectAsState()
            val themePreset by AuthManager.themePresetFlow.collectAsState()
            val chatFontScale by AuthManager.chatFontScaleFlow.collectAsState()
            SystemBarsForTheme(darkTheme = themePreference.resolvesToDark())
            HermesControlTheme(
                themePreference = themePreference,
                useDynamicColors = useDynamicColors,
                themePreset = themePreset,
                chatFontScale = chatFontScale,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val initState by AuthManager.initializationState.collectAsStateWithLifecycle()
                    when (initState) {
                        AuthManager.InitializationState.Loading -> {
                            LoadingState()
                        }

                        AuthManager.InitializationState.Ready -> {
                            val savedLanguage = AuthManager.getAppLanguage()
                            if (savedLanguage != baseContextLanguageCode) {
                                // The next activity attaches with loaded preferences. Keep navigation
                                // gated across recompositions until that recreation completes (#1171).
                                LaunchedEffect(savedLanguage) {
                                    recreate()
                                }
                                LoadingState()
                            } else {
                                AppLockGate(
                                    onUnlock = appLockPrompt::authenticate,
                                    onBackWhileLocked = { moveTaskToBack(true) },
                                ) {
                                    MainNavigation()
                                }
                            }
                        }

                        AuthManager.InitializationState.Failed -> {
                            ErrorState(
                                message = stringResource(R.string.auth_init_failed),
                                onRetry = {
                                    AuthManager.init(applicationContext)
                                },
                            )
                        }
                    }
                }
            }
        }
        StartupDrawGate(isReady = ::isReadyForFirstFrame).install(findViewById(android.R.id.content))
    }

    /**
     * The first frame waits for the stored settings, and for the recreation that applies a saved
     * display language, so a cold start shows no loading spinner between the splash and the content.
     */
    private fun isReadyForFirstFrame(): Boolean =
        when (AuthManager.initializationState.value) {
            AuthManager.InitializationState.Loading -> {
                false
            }

            AuthManager.InitializationState.Failed -> {
                true
            }

            AuthManager.InitializationState.Ready -> {
                runCatching { AuthManager.getAppLanguage() == baseContextLanguageCode }.getOrDefault(true)
            }
        }

    override fun onStart() {
        super.onStart()
        AppLock.onForeground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeNotificationIntent(intent)
        consumeShareIntent(intent)
        consumeShortcutIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            AuthManager.initializationState.first { it == AuthManager.InitializationState.Ready }
            UpdateNoticeManager.checkOnLaunch()
        }
        NotificationHelper.setAppForeground(this, true)
        ExternalActivityLifecycleGuard.onHostResumed()
        NotificationHelper.stop(this)
    }

    override fun onPause() {
        ExternalActivityLifecycleGuard.onHostPaused()
        // Prepare the FGS while still eligible to start it. A pause alone does
        // not mean background: rotation also pauses and recreates this activity.
        NotificationHelper.start(this)
        super.onPause()
    }

    override fun onStop() {
        // isChangingConfigurations is reliable here, not during onPause.
        // Keep the shared socket alive across recreation, but still release an
        // idle connection when the user really leaves the app or locks the phone.
        if (!isChangingConfigurations) {
            NotificationHelper.setAppForeground(this, false)
            AppLock.onBackground()
        }
        super.onStop()
    }

    /**
     * Stage share-sheet content in a new chat (never sent automatically).
     * Signed-out users get a hint instead, so content cannot linger and
     * surface in an unrelated chat later.
     */
    private fun consumeShareIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return
        val shared = Intent(intent)
        intent.action = null
        lifecycleScope.launch {
            // Provider queries can be slow (cloud documents, many items): keep them off the main thread.
            val content =
                withContext(Dispatchers.IO) {
                    ShareIntentParser.parse(
                        intent = shared,
                        ownAuthority = "$packageName.fileprovider",
                        resolver = ShareIntentParser.contentResolverDescriber(contentResolver),
                    )
                }
            if (content == null) {
                Toast.makeText(this@MainActivity, R.string.share_nothing_usable, Toast.LENGTH_LONG).show()
                return@launch
            }
            AuthManager.initializationState.first { it == AuthManager.InitializationState.Ready }
            // A direct share to a conversation shortcut goes into that chat, if it belongs to the
            // current connection and profile; otherwise it falls back to a new chat.
            val targetSession =
                AppShortcuts
                    .sessionIdFromShortcutId(shared.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID))
                    ?.takeIf { SessionShortcuts.isCurrent(this@MainActivity, it, AuthManager.currentDataScope()) }
            ShareInbox.offer(content, targetSession)
            if (AuthManager.isGatedMode() || !AuthManager.getToken().isNullOrBlank()) {
                if (targetSession != null) {
                    NavigationController.openChatSession(targetSession)
                } else {
                    NavigationController.openNewChat()
                }
            } else {
                ShareInbox.clear()
                Toast.makeText(this@MainActivity, R.string.share_sign_in_first, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Launcher shortcuts and the Quick Settings tile arrive through the non-exported
     * [AppShortcuts.SHORTCUT_ENTRY_ALIAS]; anything else is ignored. Signed-out users land on
     * the start screen as usual.
     */
    private fun consumeShortcutIntent(intent: Intent?) {
        val request = intent?.let(AppShortcuts::parse) ?: return
        intent.action = null
        lifecycleScope.launch {
            AuthManager.initializationState.first { it == AuthManager.InitializationState.Ready }
            if (!AuthManager.isGatedMode() && AuthManager.getToken().isNullOrBlank()) return@launch
            when (request) {
                ShortcutRequest.NewChat -> {
                    NavigationController.openNewChat()
                }

                ShortcutRequest.VoiceChat -> {
                    NavigationController.openNewVoiceChat()
                }

                ShortcutRequest.History -> {
                    NavigationController.requestScreen(HistoryScreen)
                }

                is ShortcutRequest.Session -> {
                    if (SessionShortcuts.matchesScope(request.scopeKey, AuthManager.currentDataScope())) {
                        ShortcutManagerCompat.reportShortcutUsed(
                            this@MainActivity,
                            AppShortcuts.SESSION_SHORTCUT_PREFIX + request.sessionId,
                        )
                        NavigationController.openChatSession(request.sessionId)
                    } else {
                        // Another connection or profile: its session id means nothing here.
                        Toast
                            .makeText(this@MainActivity, R.string.shortcut_session_other_scope, Toast.LENGTH_LONG)
                            .show()
                        NavigationController.requestScreen(HistoryScreen)
                    }
                }
            }
        }
    }

    private fun consumeNotificationIntent(intent: Intent?) {
        // Issue #832: MainActivity is exported (launcher requirement) — only
        // honor open-chat intents delivered through the non-exported alias.
        if (intent == null || !isTrustedNotificationIntent(intent.action, intent.component?.className)) return
        val sessionId = intent.getStringExtra(NotificationReplyReceiver.EXTRA_SESSION_ID)
        intent.removeExtra(NotificationReplyReceiver.EXTRA_SESSION_ID)
        sessionId?.takeIf { it.isNotBlank() }?.let(NavigationController::openChatSessionFromNotification)
    }
}
