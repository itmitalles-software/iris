package io.wutz.apollo.ui.chat

import io.wutz.apollo.R
import io.wutz.apollo.data.model.MessageReaction
import io.wutz.apollo.data.ws.contract.MessageReactParams
import io.wutz.apollo.data.ws.contract.RpcMethods
import io.wutz.apollo.data.ws.contract.TypedRpcCaller
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The six iOS Tapback defaults in Apple's order, the same quick set the Hermes desktop offers. */
internal val QUICK_REACTIONS: List<String> = listOf("❤️", "👍", "👎", "😂", "‼️", "❓")

/** Author value the gateway stores for reactions made by the person using the app. */
internal const val USER_REACTION_AUTHOR = "user"

/**
 * Tapback semantics, applied locally before the gateway answers: one reaction per author, the
 * same emoji again (or null) retracts the author's reaction.
 */
internal fun applyReaction(
    reactions: List<MessageReaction>,
    emoji: String?,
    author: String = USER_REACTION_AUTHOR,
): List<MessageReaction> {
    val previous = reactions.firstOrNull { it.author == author }
    val without = reactions.filterNot { it.author == author }
    return if (emoji == null || previous?.emoji == emoji) without else without + MessageReaction(emoji, author)
}

/**
 * Lets the user react to stored agent messages through the gateway's `message.react`.
 *
 * Optimistic: the chip row changes at once and the gateway's returned list wins afterwards. A
 * failed write rolls the row back and says so, instead of the reaction silently vanishing.
 */
class ChatReactionsDelegate(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val runtimeSessionId: () -> String?,
    private val rpc: TypedRpcCaller,
) {
    /** True when [message] can carry a user reaction: a finished agent message with a stored row id. */
    fun canReact(message: ChatMessage): Boolean =
        message.role == MessageRole.ASSISTANT && !message.isStreaming && message.serverRowId != null

    /** Toggle [emoji] (null clears) as the user's reaction on the message [messageId]. */
    fun react(
        messageId: String,
        emoji: String?,
    ) {
        val message = uiState.value.messages.firstOrNull { it.id == messageId } ?: return
        if (!canReact(message)) return
        val rowId = message.serverRowId ?: return
        val sessionId = runtimeSessionId()
        if (sessionId == null) {
            uiState.update { it.copy(errorMessage = UiText.res(R.string.chat_reaction_failed)) }
            return
        }
        val snapshot = message.reactions
        writeReactions(rowId, applyReaction(snapshot, emoji))
        scope.launch(ioDispatcher) {
            try {
                val result =
                    rpc.call(
                        RpcMethods.MESSAGE_REACT,
                        MessageReactParams(
                            sessionId = sessionId,
                            rowId = rowId,
                            emoji = emoji,
                            author = USER_REACTION_AUTHOR,
                        ),
                    )
                writeReactions(
                    result.rowId,
                    result.reactions
                        .filter { it.emoji.isNotBlank() }
                        .map { MessageReaction(emoji = it.emoji, author = it.author) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                writeReactions(rowId, snapshot)
                uiState.update { it.copy(errorMessage = UiText.res(R.string.chat_reaction_failed)) }
            }
        }
    }

    private fun writeReactions(
        rowId: Long,
        reactions: List<MessageReaction>,
    ) {
        uiState.update { state ->
            if (state.messages.none { it.serverRowId == rowId }) {
                state
            } else {
                state.copy(
                    messages =
                        state.messages.map {
                            if (it.serverRowId == rowId && it.role == MessageRole.ASSISTANT) {
                                it.copy(reactions = reactions)
                            } else {
                                it
                            }
                        },
                )
            }
        }
    }
}
