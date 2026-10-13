package io.wutz.apollo.ui.chat.components

import android.Manifest
import android.content.ClipData
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.wutz.apollo.NavigationController
import io.wutz.apollo.R
import io.wutz.apollo.data.ws.ConnectionStatus
import io.wutz.apollo.notification.ReplyNotificationTracker
import io.wutz.apollo.notification.correlationScopeId
import io.wutz.apollo.share.ShareInbox
import io.wutz.apollo.ui.chat.ChatMessage
import io.wutz.apollo.ui.chat.ChatViewModel
import io.wutz.apollo.ui.chat.ClarifyUi
import io.wutz.apollo.ui.chat.SecretPromptUi
import io.wutz.apollo.ui.chat.SudoPromptUi
import io.wutz.apollo.ui.common.UiText
import io.wutz.apollo.ui.common.resolve
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun ChatLifecycleEffects(
    sessionId: String?,
    connectionStatus: ConnectionStatus,
    currentSessionId: String?,
    messages: List<ChatMessage>,
    errorMessage: UiText?,
    backgroundCompleteMessage: UiText?,
    openError: UiText?,
    clarifyRequest: ClarifyUi?,
    sudoPrompt: SudoPromptUi?,
    secretPrompt: SecretPromptUi?,
    listState: LazyListState,
    scrollController: ChatScrollController,
    snackbarHostState: SnackbarHostState,
    viewModel: ChatViewModel,
    isOverlayActive: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Switch to session from notification/history
    val pendingNavigation = NavigationController.pendingChatNavigation
    val pendingNewChatNavigation = NavigationController.pendingNewChatNavigation
    LaunchedEffect(sessionId, pendingNavigation, pendingNewChatNavigation, connectionStatus) {
        if (connectionStatus != ConnectionStatus.CONNECTED) return@LaunchedEffect
        val newChatRequest = NavigationController.consumePendingNewChatNavigation()
        val request = NavigationController.consumePendingChatNavigation()
        if (newChatRequest != null) {
            viewModel.startNewChat(newChatRequest.project)
            // Share-sheet content lands in the fresh chat after its reset.
            ShareInbox.consume()?.let(viewModel::stageSharedContent)
            return@LaunchedEffect
        }
        val target = request?.sessionId ?: sessionId
        if (!target.isNullOrBlank()) {
            viewModel.switchSession(target)
        }
        if (request?.scrollToBottom == true) {
            scrollController.jumpToBottom(animated = false)
        }
    }

    // Land instantly at the bottom on a session switch (issue #682).
    LaunchedEffect(currentSessionId) {
        if (currentSessionId != null) {
            scrollController.jumpToBottom(animated = false)
        }
    }

    // Refresh chat state when the app returns to the foreground.
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        viewModel.refreshSettings()
                        viewModel.refreshCurrentSession()
                    }

                    else -> {}
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val messageMap = remember(messages) { messages.associateBy { it.id } }

    // Auto-dismiss reply notifications when their message is displayed in the viewport
    LaunchedEffect(lifecycleOwner, currentSessionId, messageMap, listState, isOverlayActive) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow<List<ChatMessage>> {
                if (currentSessionId.isNullOrBlank() || isOverlayActive) {
                    emptyList()
                } else {
                    ChatReadObserver.findVisibleAssistantMessages(listState.layoutInfo, messageMap)
                }
            }.distinctUntilChanged()
                .collect { visibleAssistantMsgs ->
                    val scopeId = correlationScopeId()
                    for (msg in visibleAssistantMsgs) {
                        ReplyNotificationTracker.onMessageVisible(
                            context = context,
                            scopeId = scopeId,
                            sessionId = currentSessionId,
                            completionId = msg.completionId,
                        )
                    }
                }
        }
    }

    // Request POST_NOTIFICATIONS permission on Android 13+
    val requestNotificationPermission =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { /* granted */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(context, permission) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(permission)
            }
        }
    }

    // Show error as snackbar
    val clipboard = LocalClipboard.current
    val copyLabel = stringResource(R.string.action_copy_error)
    // Own scope + immediate clear: a later `errorMessage = null` elsewhere must not cancel a visible popup.
    val snackbarScope = rememberCoroutineScope()
    val resources = LocalResources.current
    LaunchedEffect(errorMessage) {
        errorMessage?.let { errorText ->
            val error = errorText.resolve(resources)
            viewModel.clearError()
            snackbarScope.launch {
                val result =
                    snackbarHostState.showSnackbar(
                        error,
                        actionLabel = copyLabel,
                        duration = SnackbarDuration.Long,
                        withDismissAction = true,
                    )
                if (result == SnackbarResult.ActionPerformed) {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, error)))
                }
            }
        }
    }

    // Show background-complete as a non-blocking snackbar (issue #527)
    LaunchedEffect(backgroundCompleteMessage) {
        backgroundCompleteMessage?.let { message ->
            snackbarHostState.showSnackbar(message.resolve(resources))
            viewModel.clearBackgroundComplete()
        }
    }

    // Show attachment-open failures as a non-blocking snackbar (issue #724)
    LaunchedEffect(openError) {
        openError?.let { message ->
            val text = message.resolve(resources)
            if (message is UiText.Resource && message.id == R.string.chat_msg_file_saved) {
                snackbarHostState.showSnackbar(ChatSuccessSnackbarVisuals(text))
            } else {
                snackbarHostState.showSnackbar(text)
            }
            viewModel.clearOpenError()
        }
    }

    // Sudo / secret prompt dialogs (issue #524)
    sudoPrompt?.let { prompt ->
        SudoPromptDialog(
            onConfirm = viewModel::respondToSudo,
            onDismiss = viewModel::dismissSudo,
        )
    }

    secretPrompt?.let { prompt ->
        SecretPromptDialog(
            onConfirm = viewModel::respondToSecret,
            onDismiss = viewModel::dismissSecret,
            envVar = prompt.envVar,
            prompt = prompt.prompt,
        )
    }
}

/**
 * Snackbar visuals for a success notice (a saved attachment); the chat snackbar host shows these in the
 * success colors. Replaces matching the English "Saved " prefix, which broke once the text was localized.
 */
internal class ChatSuccessSnackbarVisuals(
    override val message: String,
) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction: Boolean = false
    override val duration: SnackbarDuration = SnackbarDuration.Short
}
