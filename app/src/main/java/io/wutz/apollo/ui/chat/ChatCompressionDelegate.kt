package io.wutz.apollo.ui.chat

import android.util.Log
import io.wutz.apollo.R
import io.wutz.apollo.data.model.SessionCompressResponse
import io.wutz.apollo.data.remote.OkHttpProvider
import io.wutz.apollo.data.ws.HermesWsClient
import io.wutz.apollo.data.ws.contract.RpcMethods
import io.wutz.apollo.data.ws.contract.SessionCompressParams
import io.wutz.apollo.data.ws.toAny
import io.wutz.apollo.data.ws.toJsonElement
import io.wutz.apollo.notification.ReplyNotificationTarget
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

private const val TAG = "ChatViewModel"
private const val COMPRESS_TIMEOUT_MS = 300_000L

/**
 * Runs `/compress` (`session.compress`) and reconciles the transcript with the compacted history the gateway
 * returns: an authoritative replacement through [transcriptWriter], or a REST reload when the response cannot be
 * mapped to stable rows. Every step is bound to the session and generation that started it.
 *
 * Extracted behaviour-preservingly from [ChatViewModel].
 */
internal class ChatCompressionDelegate(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val historyDispatcher: CoroutineDispatcher,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val transcriptWriter: ChatLocalTranscriptWriter,
    private val runtimeSessionId: () -> String?,
    private val sessionGeneration: () -> Long,
    private val latestPaging: () -> Boolean,
    private val isCurrent: (sessionId: String, generation: Long) -> Boolean,
    private val activeReplyTarget: () -> ReplyNotificationTarget?,
    private val mediaUrl: (String) -> String?,
    private val localized: (UiText) -> String,
    private val addAssistantMessage: (String) -> Unit,
    private val reloadHistory: suspend (sessionId: String, generation: Long) -> Unit,
    private val fetchContextUsage: () -> Unit,
    private val loadSessions: () -> Unit,
    private val handleSessionInfo: (Map<String, Any?>?) -> Unit,
    private val onServerPresenceConfirmed: () -> Unit,
    private val compressRpc: suspend (SessionCompressParams) -> JsonElement = { params ->
        HermesWsClient.call(RpcMethods.SESSION_COMPRESS, params, timeoutMs = COMPRESS_TIMEOUT_MS)
    },
) {
    private fun addAssistantMessage(text: UiText) = addAssistantMessage(localized(text))

    fun compressSession(focusTopic: String) {
        val sessionId = uiState.value.currentSessionId
        // Fix #1437: session.compress resolves a runtime ID, not the persisted history key.
        // Keep the stored ID below for generation guards and transcript persistence.
        val rpcSessionId = runtimeSessionId()
        if (sessionId == null || rpcSessionId == null) {
            addAssistantMessage(UiText.res(R.string.chat_msg_compress_no_session))
            return
        }
        if (uiState.value.isCompressing) return
        val generation = sessionGeneration()
        val current = { isCurrent(sessionId, generation) }
        uiState.update { it.copy(isCompressing = true, compressionStatus = SystemNotice.COMPRESSING_CONTEXT) }
        scope.launch(ioDispatcher) {
            try {
                val result =
                    compressRpc(
                        SessionCompressParams(
                            sessionId = rpcSessionId,
                            focusTopic = focusTopic.takeIf { it.isNotBlank() },
                        ),
                    )
                if (current()) handleCompressionResult(result, sessionId, generation)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (current()) addAssistantMessage("/compress: ${e.message ?: "compression failed"}")
            } finally {
                if (current()) uiState.update { it.copy(isCompressing = false, compressionStatus = null) }
            }
        }
    }

    private suspend fun handleCompressionResult(
        result: Any?,
        sessionId: String,
        generation: Long,
    ) {
        val response =
            try {
                OkHttpProvider.json.decodeFromJsonElement<SessionCompressResponse>(result.toJsonElement())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode session.compress response", e)
                null
            }
        if (!isCurrent(sessionId, generation)) return
        if (response == null ||
            (response.status != null && response.status !in setOf("ok", "compressed")) ||
            (
                response.status == null && response.messages == null && response.summary == null &&
                    response.message == null && response.compressed == null
            )
        ) {
            throw IllegalStateException("Unrecognized session.compress response; history was not reconciled")
        }
        val incompleteFallback = response.messages != null && latestPaging() && response.messages.any { it.id == null }
        if (response.messages != null && !incompleteFallback) {
            val replacement =
                withContext(historyDispatcher) {
                    mapServerMessages(
                        sessionId = sessionId,
                        messages = response.messages,
                        offset = 0,
                        latestPaging = latestPaging(),
                        liveMessages = emptyList(),
                        activeReplyTarget = activeReplyTarget(),
                        mediaUrl = mediaUrl,
                    )
                }
            if (!transcriptWriter.replaceCanonicalHistory(sessionId, generation, replacement)) return
        } else if (incompleteFallback) {
            // An id-less latest-page response needs the REST protocol's paging negotiation.
            // loadSessionMessages starts a child job; joining it is essential before feedback.
            reloadHistory(sessionId, generation)
            if (!isCurrent(sessionId, generation)) return
        }
        if (!isCurrent(sessionId, generation)) return
        val summary = response?.summary
        val feedback =
            listOfNotNull(
                summary?.headline?.takeIf { it.isNotBlank() },
                summary?.token_line?.takeIf { it.isNotBlank() },
                summary?.note?.takeIf { it.isNotBlank() },
            ).takeIf { it.isNotEmpty() }?.joinToString("\n") ?: response?.message
                ?: localized(UiText.res(R.string.chat_msg_context_compressed))
        addAssistantMessage(
            if (incompleteFallback) {
                localized(UiText.res(R.string.chat_msg_compressed_history_incomplete, feedback))
            } else {
                feedback
            },
        )
        if (!isCurrent(sessionId, generation)) return
        fetchContextUsage()
        loadSessions()
        if (!isCurrent(sessionId, generation)) return
        response?.info?.let { infoElement ->
            val infoMap = (infoElement.toAny() as? Map<*, *>)?.filterKeys { it is String } as? Map<String, Any?>
            handleSessionInfo(infoMap)
        }
        if (isCurrent(sessionId, generation)) onServerPresenceConfirmed()
    }
}
