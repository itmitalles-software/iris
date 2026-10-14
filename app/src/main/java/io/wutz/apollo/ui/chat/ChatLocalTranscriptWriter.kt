package io.wutz.apollo.ui.chat

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Appends local transcript rows (slash feedback, local notices, optimistic prompts) and persists them in order,
 * serialized with the authoritative history replacement of a context compression. A row appended while a
 * replacement is committing is re-anchored behind the new canonical tail, so it never lands between rows the
 * replacement removed.
 *
 * Extracted behaviour-preservingly from [ChatViewModel].
 */
internal class ChatLocalTranscriptWriter(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val repo: ChatPersistenceRepository,
    private val isCurrent: (sessionId: String, generation: Long) -> Boolean,
    private val onHistoryReplacedForTest: (() -> Unit)? = null,
) {
    private val lock = Any()
    private var persistenceTail: Job? = null

    /** Guarded by [lock]; advanced only after the RPC replacement commits. */
    private val compressedHistoryEpochs = mutableMapOf<String, Pair<Long, Long>>()

    fun append(
        message: ChatMessage,
        persist: Boolean = true,
    ): ChatMessage {
        val (placed, persistenceJob) =
            synchronized(lock) {
                var placed = message
                var sessionId: String? = null
                uiState.update { state ->
                    placed = message.withLocalTranscriptAnchor(state.messages)
                    sessionId = state.currentSessionId
                    state.copy(messages = state.messages + placed)
                }
                val capturedMessage = placed
                val capturedSessionId = sessionId
                val previousWrite = persistenceTail
                val capturedEpoch = capturedSessionId?.let { compressedHistoryEpochs[it]?.first } ?: 0L
                val job =
                    if (persist && capturedSessionId != null) {
                        scope
                            .launch(ioDispatcher, start = CoroutineStart.LAZY) {
                                previousWrite?.join()
                                val reanchor =
                                    synchronized(lock) {
                                        compressedHistoryEpochs[capturedSessionId]
                                            ?.takeIf { it.first != capturedEpoch }
                                            ?.second
                                    }
                                val persisted =
                                    if (reanchor != null && capturedMessage.isPermanentlyLocal() &&
                                        !capturedMessage.isSessionStartMarker()
                                    ) {
                                        capturedMessage.copy(localAnchorOrder = reanchor, localPredecessorId = null)
                                    } else {
                                        capturedMessage
                                    }
                                repo.persistMessage(persisted, capturedSessionId)
                                if (reanchor != null && persisted !== capturedMessage) {
                                    uiState.update { state ->
                                        if (state.currentSessionId != capturedSessionId) {
                                            state
                                        } else {
                                            state.copy(
                                                messages =
                                                    state.messages.map { current ->
                                                        if (current.id == capturedMessage.id) {
                                                            current.copy(
                                                                localAnchorOrder = reanchor,
                                                                localPredecessorId = null,
                                                            )
                                                        } else {
                                                            current
                                                        }
                                                    },
                                            )
                                        }
                                    }
                                }
                            }.also { persistenceTail = it }
                    } else {
                        null
                    }
                placed to job
            }
        // No database access inside the StateFlow transform or append lock.
        persistenceJob?.start()
        return placed
    }

    /** Serialize authoritative RPC replacement with local append writes. */
    suspend fun replaceCanonicalHistory(
        sessionId: String,
        generation: Long,
        replacement: List<ChatMessage>,
    ): Boolean {
        if (!isCurrent(sessionId, generation)) return false
        val replacementTail =
            replacement
                .mapNotNull { it.canonicalRestId?.substringAfterLast('-')?.toLongOrNull() }
                .maxOrNull() ?: -1L
        val barrier =
            synchronized(lock) {
                val predecessor = persistenceTail
                scope
                    .async(ioDispatcher, start = CoroutineStart.LAZY) {
                        predecessor?.join()
                        if (!isCurrent(sessionId, generation)) return@async false
                        val retained = repo.replaceCanonicalHistory(sessionId, replacement)
                        onHistoryReplacedForTest?.invoke()
                        if (!isCurrent(sessionId, generation)) return@async false
                        val retainedById = retained.associateBy { it.id }
                        synchronized(lock) {
                            val nextEpoch = (compressedHistoryEpochs[sessionId]?.first ?: 0L) + 1L
                            compressedHistoryEpochs[sessionId] = nextEpoch to replacementTail
                            uiState.update { state ->
                                if (!isCurrent(sessionId, generation)) {
                                    state
                                } else {
                                    val visible = state.messages.filter { it.canonicalRestId == null }
                                    val visibleLocals =
                                        visible.map { message ->
                                            retainedById[message.id]?.let { row ->
                                                message.copy(
                                                    localAnchorOrder = row.localAnchorOrder,
                                                    localPredecessorId = row.localPredecessorId,
                                                )
                                            } ?: if (message.isPermanentlyLocal() && !message.isSessionStartMarker()) {
                                                message.copy(
                                                    localAnchorOrder = replacementTail,
                                                    localPredecessorId = null,
                                                )
                                            } else {
                                                message
                                            }
                                        }
                                    state.copy(messages = replacement + (visibleLocals + retained).distinctBy { it.id })
                                }
                            }
                        }
                        true
                    }.also { persistenceTail = it }
            }
        barrier.start()
        return barrier.await()
    }
}
