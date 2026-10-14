package io.wutz.apollo.ui.chat.components

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.wutz.apollo.data.local.ComposerDraftStorage
import io.wutz.apollo.data.local.ComposerDraftStore
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/** Draft key of a chat that has no conversation yet (the desktop's "new chat" draft). */
internal const val NEW_CHAT_DRAFT_KEY = "new-chat"

/**
 * Which draft the composer belongs to. A change of [ChatUiState.chatGeneration] means the user
 * opened another conversation (switch). Inside one generation, a new chat keeps the "new chat"
 * key until its first message is accepted, then moves to its session id (rekey, text stays).
 */
internal class ComposerDraftKeyTracker {
    private var generation: Long? = null
    private var startedAsNewChat = false
    private var sent = false

    companion object {
        private const val NO_GENERATION = Long.MIN_VALUE

        /** Survives leaving the chat screen, so a new chat keeps its key when the user comes back. */
        val Saver: Saver<ComposerDraftKeyTracker, List<Any>> =
            Saver(
                save = { listOf(it.generation ?: NO_GENERATION, it.startedAsNewChat, it.sent) },
                restore = { saved ->
                    ComposerDraftKeyTracker().apply {
                        generation = (saved[0] as Long).takeIf { it != NO_GENERATION }
                        startedAsNewChat = saved[1] as Boolean
                        sent = saved[2] as Boolean
                    }
                },
            )
    }

    data class Key(
        val key: String,
        val switched: Boolean,
    )

    fun update(
        chatGeneration: Long,
        sessionId: String?,
    ): Key {
        val switched = generation != null && generation != chatGeneration
        if (generation != chatGeneration) {
            generation = chatGeneration
            startedAsNewChat = sessionId == null
            sent = false
        }
        val key = if (sessionId == null || (startedAsNewChat && !sent)) NEW_CHAT_DRAFT_KEY else sessionId
        return Key(key, switched)
    }

    fun onSendAccepted() {
        sent = true
    }
}

/**
 * Keeps one unsent draft per conversation, like the Hermes desktop's per-session drafts: switching chats saves the text under the chat that is left and restores the draft of
 * the chat that opens, and drafts survive the app being closed. A restored draft never drops text
 * that is already in the composer (a share-sheet prefill, or typing that raced the load); that text
 * follows the draft.
 */
internal class ComposerDraftSession(
    private val storage: ComposerDraftStorage,
    private val readText: () -> String,
    private val replaceText: (String) -> Unit,
    private val keys: ComposerDraftKeyTracker = ComposerDraftKeyTracker(),
) {
    private var activeKey: String? = null
    private var activeScopeKey: String? = null

    /** False while the active chat's draft is still loading: an empty composer must not erase it. */
    private var loaded = false
    private var lastGeneration = 0L
    private var lastSessionId: String? = null
    private var lastScopeKey: String? = null

    suspend fun onChat(
        chatGeneration: Long,
        sessionId: String?,
        scopeKey: String?,
    ) {
        lastGeneration = chatGeneration
        lastSessionId = sessionId
        lastScopeKey = scopeKey
        val key = keys.update(chatGeneration, sessionId)
        val fullKey = scopeKey?.let { "$it|${key.key}" } ?: return
        val previous = activeKey
        if (fullKey == previous) return
        val text = readText()
        when {
            // Another conversation, or another server/profile: park the text and load the new draft.
            previous == null || key.switched || scopeKey != activeScopeKey -> {
                if (previous != null && loaded) storage.save(previous, text)
                activeKey = fullKey
                activeScopeKey = scopeKey
                loaded = false
                if (previous != null && readText() == text) replaceText("")
                val draft = storage.load(fullKey)
                // The chat may have changed again while the draft loaded.
                if (activeKey != fullKey) return
                val current = readText()
                if (!draft.isNullOrBlank() && current != draft) {
                    // Text that is already there (a share into this chat, or typing that raced the load) is
                    // kept after the draft, so neither is lost.
                    replaceText(if (current.isBlank()) draft else "$draft\n\n$current")
                }
                loaded = true
            }

            else -> {
                // Same conversation under a new key (a new chat got its session id): move the text.
                if (loaded) storage.remove(previous)
                activeKey = fullKey
                if (loaded) storage.save(fullKey, text)
            }
        }
    }

    /** Debounced composer text: store it for the active chat. */
    fun persist(text: String) {
        val key = activeKey ?: return
        if (loaded) storage.save(key, text)
    }

    /** The composer is going away or the app is stopping: store the current text now. */
    fun flush() = persist(readText())

    /** A send was accepted: its text is gone from the composer and the draft is done. */
    suspend fun onSendAccepted() {
        activeKey?.let(storage::remove)
        keys.onSendAccepted()
        onChat(lastGeneration, lastSessionId, lastScopeKey)
    }
}

/**
 * Binds [inputState] to the per-conversation draft store. Call [ComposerDraftSession.onSendAccepted]
 * after a send was accepted and the composer cleared.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun rememberComposerDrafts(
    inputState: TextFieldState,
    chatGeneration: Long,
    sessionId: String?,
    scopeKey: String?,
    storage: ComposerDraftStorage = ComposerDraftStore,
): ComposerDraftSession {
    val context = LocalContext.current
    val keys = rememberSaveable(saver = ComposerDraftKeyTracker.Saver) { ComposerDraftKeyTracker() }
    val session =
        remember(inputState, storage, keys) {
            ComposerDraftStore.init(context)
            ComposerDraftSession(
                storage = storage,
                readText = { inputState.text.toString() },
                replaceText = { text ->
                    if (text.isEmpty()) inputState.clearText() else inputState.replaceComposerDraft(text)
                },
                keys = keys,
            )
        }
    LaunchedEffect(session, chatGeneration, sessionId, scopeKey) {
        session.onChat(chatGeneration, sessionId, scopeKey)
    }
    LaunchedEffect(session) {
        snapshotFlow { inputState.text.toString() }
            .distinctUntilChanged()
            .debounce(DRAFT_SAVE_DEBOUNCE_MS)
            .collect(session::persist)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(session, lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) session.flush()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            session.flush()
        }
    }
    return session
}

private const val DRAFT_SAVE_DEBOUNCE_MS = 400L
