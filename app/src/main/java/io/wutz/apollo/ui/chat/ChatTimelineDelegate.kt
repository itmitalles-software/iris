package io.wutz.apollo.ui.chat

import io.wutz.apollo.R
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.NetworkResult
import io.wutz.apollo.data.remote.safeApiCall
import io.wutz.apollo.notification.ReplyNotificationTarget
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TIMELINE_PAGE_SIZE = 500
private const val HISTORY_WINDOW_SIZE = 120

/**
 * Owns the session timeline sheet (`/api/sessions/{id}/timeline`) and the read-only history window opened from
 * one of its entries (`messages/around`). Every request is bound to the session, generation, profile and its own
 * request sequence, so a late response of a closed sheet or a previous session never lands.
 *
 * Extracted behaviour-preservingly from [ChatViewModel].
 */
internal class ChatTimelineDelegate(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val historyDispatcher: CoroutineDispatcher,
    private val currentSessionId: () -> String?,
    private val sessionGeneration: () -> Long,
    private val liveMessages: () -> List<ChatMessage>,
    private val activeReplyTarget: () -> ReplyNotificationTarget?,
    private val mediaUrl: (String) -> String?,
    private val onOpen: () -> Unit,
) {
    private var timelineJob: Job? = null
    private var historyWindowJob: Job? = null
    private var timelineRequestSequence = 0L
    private var activeTimelineRequestSequence = 0L
    private var historyWindowRequestSequence = 0L
    private var activeHistoryWindowRequestSequence = 0L
    private val _state = MutableStateFlow(ChatTimelineState())
    val state: StateFlow<ChatTimelineState> = _state.asStateFlow()

    fun openTimeline() {
        if (currentSessionId() == null || _state.value.isOpen) return
        onOpen()
        _state.update {
            it.copy(
                isOpen = true,
                entries = emptyList(),
                isLoading = false,
                hasMore = false,
                nextCursor = null,
                errorMessage = null,
                windowErrorMessage = null,
            )
        }
        loadTimelinePage(reset = true)
    }

    fun closeTimeline() {
        timelineJob?.cancel()
        historyWindowJob?.cancel()
        activeTimelineRequestSequence = ++timelineRequestSequence
        activeHistoryWindowRequestSequence = ++historyWindowRequestSequence
        _state.update {
            it.copy(
                isOpen = false,
                isLoading = false,
                jumpingRowId = null,
            )
        }
    }

    fun retryTimeline() {
        if (!_state.value.isOpen) return
        _state.update { it.copy(windowErrorMessage = null) }
        loadTimelinePage(reset = true)
    }

    fun loadMoreTimeline() {
        val state = _state.value
        if (!state.isOpen || state.isLoading || !state.hasMore || state.nextCursor == null) return
        loadTimelinePage(reset = false)
    }

    private fun loadTimelinePage(reset: Boolean) {
        val sessionId = currentSessionId() ?: return
        val generation = sessionGeneration()
        val profile = AuthManager.activeProfileId.value
        val cursor = if (reset) 0 else _state.value.nextCursor ?: return
        val requestSequence = ++timelineRequestSequence
        activeTimelineRequestSequence = requestSequence
        timelineJob?.cancel()
        _state.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                entries = if (reset) emptyList() else it.entries,
                hasMore = if (reset) false else it.hasMore,
                nextCursor = if (reset) null else it.nextCursor,
            )
        }
        val valid = {
            generation == sessionGeneration() &&
                sessionId == currentSessionId() &&
                profile == AuthManager.activeProfileId.value &&
                requestSequence == activeTimelineRequestSequence
        }
        timelineJob =
            scope.launch {
                try {
                    val result =
                        withContext(ioDispatcher) {
                            safeApiCall {
                                ApiClient.hermesApi.getSessionTimeline(
                                    sessionId = sessionId,
                                    profile = profile,
                                    limit = TIMELINE_PAGE_SIZE,
                                    afterRowId = cursor,
                                )
                            }
                        }
                    if (!valid()) return@launch
                    when (result) {
                        is NetworkResult.Success -> {
                            val page = result.data
                            val nextCursor = page.pagination.next_cursor
                            _state.update { current ->
                                if (!valid()) {
                                    current
                                } else {
                                    val entries =
                                        (if (reset) page.entries else current.entries + page.entries)
                                            .distinctBy { it.row_id }
                                    current.copy(
                                        entries = entries,
                                        hasMore = page.pagination.has_more && nextCursor != null,
                                        nextCursor = nextCursor,
                                        errorMessage = null,
                                    )
                                }
                            }
                        }

                        is NetworkResult.Failure -> {
                            _state.update {
                                if (valid()) {
                                    it.copy(
                                        errorMessage =
                                            UiText.res(
                                                R.string.chat_msg_load_timeline_failed,
                                                result.error.message,
                                            ),
                                    )
                                } else {
                                    it
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (valid()) {
                        _state.update {
                            it.copy(
                                errorMessage =
                                    UiText.res(R.string.chat_msg_load_timeline_failed, e.message ?: e.toString()),
                            )
                        }
                    }
                } finally {
                    if (valid()) _state.update { it.copy(isLoading = false) }
                }
            }
    }

    fun jumpToTimelineEntry(rowId: Int) {
        if (rowId <= 0) return
        val sessionId = currentSessionId() ?: return
        val generation = sessionGeneration()
        val profile = AuthManager.activeProfileId.value
        val requestSequence = ++historyWindowRequestSequence
        activeHistoryWindowRequestSequence = requestSequence
        historyWindowJob?.cancel()
        _state.update {
            it.copy(
                jumpingRowId = rowId,
                windowErrorMessage = null,
            )
        }
        val valid = {
            generation == sessionGeneration() &&
                sessionId == currentSessionId() &&
                profile == AuthManager.activeProfileId.value &&
                requestSequence == activeHistoryWindowRequestSequence
        }
        historyWindowJob =
            scope.launch {
                try {
                    val result =
                        withContext(ioDispatcher) {
                            safeApiCall {
                                ApiClient.hermesApi.getSessionMessagesAround(
                                    sessionId = sessionId,
                                    rowId = rowId,
                                    profile = profile,
                                    limit = HISTORY_WINDOW_SIZE,
                                )
                            }
                        }
                    if (!valid()) return@launch
                    when (result) {
                        is NetworkResult.Success -> {
                            val response = result.data
                            if (response.messages.size > HISTORY_WINDOW_SIZE) {
                                _state.update {
                                    it.copy(windowErrorMessage = UiText.res(R.string.chat_msg_history_page_limit))
                                }
                                return@launch
                            }
                            val page =
                                withContext(historyDispatcher) {
                                    mapServerMessages(
                                        sessionId = sessionId,
                                        messages = response.messages,
                                        offset = response.pagination.offset,
                                        latestPaging = false,
                                        liveMessages = liveMessages(),
                                        isPagingOlder = true,
                                        stableRowIds = true,
                                        activeReplyTarget = activeReplyTarget(),
                                        mediaUrl = mediaUrl,
                                    )
                                }
                            if (!valid()) return@launch
                            val targetId = RestMessageId.of(sessionId, rowId)
                            if (page.none { it.id == targetId || it.canonicalRestId == targetId }) {
                                _state.update {
                                    it.copy(windowErrorMessage = UiText.res(R.string.chat_msg_prompt_unavailable))
                                }
                                return@launch
                            }
                            if (!valid()) return@launch
                            _state.update {
                                it.copy(
                                    isOpen = false,
                                    historyMessages = page,
                                    historyAnchorRowId = rowId,
                                    historyHasOlder = response.pagination.has_older,
                                    historyHasNewer = response.pagination.has_newer,
                                    windowErrorMessage = null,
                                )
                            }
                        }

                        is NetworkResult.Failure -> {
                            _state.update {
                                if (valid()) {
                                    it.copy(
                                        windowErrorMessage =
                                            UiText.res(R.string.chat_msg_load_message_failed, result.error.message),
                                    )
                                } else {
                                    it
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (valid()) {
                        _state.update {
                            it.copy(
                                windowErrorMessage =
                                    UiText.res(R.string.chat_msg_load_message_failed, e.message ?: e.toString()),
                            )
                        }
                    }
                } finally {
                    if (valid()) _state.update { it.copy(jumpingRowId = null) }
                }
            }
    }

    fun returnToLatestMessages() {
        historyWindowJob?.cancel()
        activeHistoryWindowRequestSequence = ++historyWindowRequestSequence
        _state.update {
            it.copy(
                jumpingRowId = null,
                windowErrorMessage = null,
                historyMessages = null,
                historyAnchorRowId = null,
                historyHasOlder = false,
                historyHasNewer = false,
            )
        }
    }

    /** Cancels both requests and closes the sheet and any history window (session switch or reset). */
    fun reset() {
        timelineJob?.cancel()
        historyWindowJob?.cancel()
        activeTimelineRequestSequence = ++timelineRequestSequence
        activeHistoryWindowRequestSequence = ++historyWindowRequestSequence
        _state.value = ChatTimelineState()
    }
}
