package io.wutz.apollo.ui.kanban

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.wutz.apollo.R
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.model.KanbanProfile
import io.wutz.apollo.data.model.KanbanRun
import io.wutz.apollo.data.model.KanbanRunInspection
import io.wutz.apollo.data.model.KanbanTaskDetailResponse
import io.wutz.apollo.data.model.ModelProvider
import io.wutz.apollo.data.model.PinnedModel
import io.wutz.apollo.data.model.TaskEstimate
import io.wutz.apollo.data.model.UpdateTaskBody
import io.wutz.apollo.data.model.WorkerLog
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.NetworkResult
import io.wutz.apollo.data.remote.safeApiCall
import io.wutz.apollo.data.repository.KanbanRepository
import io.wutz.apollo.data.repository.KanbanRepositoryImpl
import io.wutz.apollo.data.ws.KanbanEventsClient
import io.wutz.apollo.ui.common.ToastHost
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MultipartBody
import okhttp3.ResponseBody

data class KanbanTaskUiState(
    val isLoading: Boolean = false,
    val detail: KanbanTaskDetailResponse? = null,
    val workerLog: WorkerLog? = null,
    val profiles: List<KanbanProfile> = emptyList(),
    val modelProviders: List<ModelProvider> = emptyList(),
    val pinnedModels: List<PinnedModel> = emptyList(),
    val estimate: TaskEstimate? = null,
    val isEstimating: Boolean = false,
    val isDeleting: Boolean = false,
    val errorMessage: UiText? = null,
    val toastMessage: UiText? = null,
    val isSubmittingComment: Boolean = false,
    val isSavingDescription: Boolean = false,
    val isReclaiming: Boolean = false,
    val isReassigning: Boolean = false,
    val isUpdatingModel: Boolean = false,
    val isUploadingAttachment: Boolean = false,
    val isDeletingAttachment: Boolean = false,
    val isSpecifying: Boolean = false,
    val isLinking: Boolean = false,
    val isInspectingRun: Boolean = false,
    val isTerminatingRun: Boolean = false,
    val selectedRun: KanbanRun? = null,
    val runInspection: KanbanRunInspection? = null,
    val runStateType: String? = null,
    val runStateName: String? = null,
)

class KanbanTaskViewModel(
    private val repository: KanbanRepository = KanbanRepositoryImpl(),
    private val eventsClientProvider: () -> KanbanEventsClient = { KanbanEventsClient() },
    private val enableFallbackPolling: Boolean = true,
) : ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(KanbanTaskUiState())
    val uiState: StateFlow<KanbanTaskUiState> = _uiState.asStateFlow()

    private var eventsClient: KanbanEventsClient? = null
    private var eventsBoard: String? = null
    private var activeTaskId: String? = null
    private var activeBoard: String? = null
    private var currentLoadGen: Int = 0
    private var reloadJob: Job? = null
    private var logPollJob: Job? = null
    private var detailPollJob: Job? = null

    fun loadTask(
        board: String,
        taskId: String,
    ) {
        val gen = ++currentLoadGen
        reloadJob?.cancel()
        stopLogPolling()
        detailPollJob?.cancel()
        if (activeBoard != board || activeTaskId != taskId) {
            _uiState.update {
                it.copy(
                    runStateType = null,
                    runStateName = null,
                    selectedRun = null,
                    runInspection = null,
                )
            }
        }
        activeTaskId = taskId
        activeBoard = board
        _uiState.update {
            it.copy(
                isLoading = true,
                detail = null,
                workerLog = null,
                estimate = null,
                errorMessage = null,
            )
        }
        loadProfiles()
        loadModelOptions()
        connectEvents(board)
        startDetailPolling(board, taskId)

        viewModelScope.launch {
            when (
                val result =
                    repository.getTask(
                        taskId,
                        board,
                        _uiState.value.runStateType,
                        _uiState.value.runStateName,
                    )
            ) {
                is NetworkResult.Success -> {
                    if (gen != currentLoadGen) return@launch
                    val taskData = result.data
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            detail = taskData,
                        )
                    }
                    if (taskData.task.status.equals("running", ignoreCase = true)) {
                        startLogPolling(board, taskId)
                    } else {
                        stopLogPolling()
                        loadLog(board, taskId)
                    }
                }

                is NetworkResult.Failure -> {
                    if (gen != currentLoadGen) return@launch
                    stopLogPolling()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = UiText.res(R.string.kanban_msg_load_task_failed, result.error.message),
                        )
                    }
                }
            }
        }
    }

    fun loadProfiles() {
        viewModelScope.launch {
            when (val result = repository.getProfiles()) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(profiles = result.data.profiles) }
                }

                is NetworkResult.Failure -> {
                    // Failures to read profiles don't block detail
                }
            }
        }
    }

    fun loadModelOptions() {
        viewModelScope.launch {
            when (val res = safeApiCall { ApiClient.hermesApi.getModelOptions() }) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            modelProviders = res.data.providers,
                            pinnedModels = AuthManager.getPinnedModels(),
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    // Failures to read models don't block detail
                }
            }
        }
    }

    fun loadLog(
        board: String,
        taskId: String,
    ) {
        viewModelScope.launch {
            fetchLogInternal(board, taskId)
        }
    }

    private suspend fun fetchLogInternal(
        board: String,
        taskId: String,
    ) {
        when (val result = repository.getTaskLog(taskId = taskId, board = board)) {
            is NetworkResult.Success -> {
                _uiState.update { it.copy(workerLog = result.data) }
            }

            is NetworkResult.Failure -> {
                // Failures to read log don't blank the task screen
            }
        }
    }

    private fun startLogPolling(
        board: String,
        taskId: String,
    ) {
        logPollJob?.cancel()
        logPollJob =
            viewModelScope.launch {
                fetchLogInternal(board, taskId)
                val isRunning = {
                    _uiState.value.detail
                        ?.task
                        ?.status
                        ?.equals("running", ignoreCase = true) == true
                }
                while (isActive && isRunning()) {
                    delay(POLL_INTERVAL_MS)
                    fetchLogInternal(board, taskId)
                }
            }
    }

    private fun stopLogPolling() {
        logPollJob?.cancel()
        logPollJob = null
    }

    private fun startDetailPolling(
        board: String,
        taskId: String,
    ) {
        if (!enableFallbackPolling) return
        detailPollJob =
            viewModelScope.launch {
                while (isActive) {
                    delay(DETAIL_POLL_INTERVAL_MS)
                    loadTaskSilently(board, taskId)
                }
            }
    }

    private fun connectEvents(board: String) {
        if (eventsBoard == board) return
        eventsClient?.disconnect()
        eventsBoard = board
        val client = eventsClient ?: eventsClientProvider().also { eventsClient = it }
        client.connect(
            scope = viewModelScope,
            board = board,
            onEvents = { envelope ->
                val current = activeTaskId
                if (current != null && envelope.events.any { it.taskId == current }) {
                    scheduleTaskReload(board, current)
                }
            },
            onStatus = {},
        )
    }

    private fun scheduleTaskReload(
        board: String,
        taskId: String,
    ) {
        reloadJob?.cancel()
        reloadJob =
            viewModelScope.launch {
                delay(RELOAD_DEBOUNCE_MS)
                loadTaskSilently(board, taskId)
            }
    }

    private suspend fun loadTaskSilently(
        board: String,
        taskId: String,
    ) {
        val gen = currentLoadGen
        val result = repository.getTask(taskId, board, _uiState.value.runStateType, _uiState.value.runStateName)
        if (gen != currentLoadGen || taskId != activeTaskId || board != activeBoard) return
        if (result is NetworkResult.Success) {
            val taskData = result.data
            _uiState.update { state ->
                state.copy(detail = taskData.copy(attachments = taskData.attachments ?: state.detail?.attachments))
            }
            if (taskData.task.status.equals("running", ignoreCase = true)) {
                if (logPollJob == null) startLogPolling(board, taskId)
            } else {
                stopLogPolling()
            }
        }
    }

    fun updateDescription(
        board: String,
        taskId: String,
        newBody: String,
    ) {
        _uiState.update { it.copy(isSavingDescription = true) }
        viewModelScope.launch {
            val result =
                repository.updateTask(
                    taskId = taskId,
                    board = board,
                    body = UpdateTaskBody(body = newBody),
                )
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { state ->
                        state.copy(
                            isSavingDescription = false,
                            detail =
                                state.detail?.let { d ->
                                    d.copy(task = d.task.copy(body = newBody))
                                },
                            toastMessage = UiText.res(R.string.kanban_msg_description_saved),
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isSavingDescription = false,
                            toastMessage = UiText.res(R.string.kanban_msg_save_failed, result.error.message),
                        )
                    }
                }
            }
        }
    }

    fun reassign(
        board: String,
        taskId: String,
        profile: String?,
    ) {
        _uiState.update { it.copy(isReassigning = true) }
        viewModelScope.launch {
            when (
                val result =
                    repository.reassignTask(
                        taskId = taskId,
                        board = board,
                        profile = profile,
                        reclaimFirst = true,
                    )
            ) {
                is NetworkResult.Success -> {
                    val msg =
                        if (profile != null) {
                            UiText.res(R.string.kanban_msg_task_reassigned, profile)
                        } else {
                            UiText.res(R.string.kanban_msg_task_unassigned)
                        }
                    _uiState.update { it.copy(isReassigning = false, toastMessage = msg) }
                    loadTask(board, taskId)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isReassigning = false,
                            toastMessage = UiText.res(R.string.kanban_msg_reassign_failed, result.error.message),
                        )
                    }
                }
            }
        }
    }

    fun updateModelOverride(
        board: String,
        taskId: String,
        override: KanbanModelOverride,
    ) {
        _uiState.update { it.copy(isUpdatingModel = true) }
        viewModelScope.launch {
            when (val res = repository.updateTask(taskId = taskId, board = board, body = override.toUpdateTaskBody())) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isUpdatingModel = false,
                            toastMessage = UiText.res(R.string.kanban_msg_model_override_updated),
                        )
                    }
                    loadTask(board, taskId)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isUpdatingModel = false,
                            toastMessage = UiText.res(R.string.kanban_msg_model_update_failed, res.error.message),
                        )
                    }
                }
            }
        }
    }

    fun estimateTask(
        board: String,
        taskId: String,
    ) {
        if (_uiState.value.isEstimating) return
        _uiState.update { it.copy(isEstimating = true) }
        viewModelScope.launch {
            when (val res = repository.estimateTask(taskId = taskId, board = board)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isEstimating = false,
                            estimate = res.data,
                            toastMessage = UiText.res(R.string.kanban_msg_estimate_completed),
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isEstimating = false,
                            toastMessage = UiText.res(R.string.kanban_msg_estimate_failed, res.error.message),
                        )
                    }
                }
            }
        }
    }

    fun deleteTask(
        board: String,
        taskId: String,
        onSuccess: () -> Unit,
    ) {
        if (_uiState.value.isDeleting) return
        _uiState.update { it.copy(isDeleting = true) }
        viewModelScope.launch {
            when (val res = repository.deleteTask(taskId = taskId, board = board)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isDeleting = false,
                            toastMessage = UiText.res(R.string.kanban_msg_task_deleted),
                        )
                    }
                    onSuccess()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isDeleting = false,
                            toastMessage = UiText.res(R.string.kanban_msg_task_delete_failed, res.error.message),
                        )
                    }
                }
            }
        }
    }

    fun addComment(
        board: String,
        taskId: String,
        body: String,
    ) {
        if (body.isBlank()) return
        _uiState.update { it.copy(isSubmittingComment = true) }
        viewModelScope.launch {
            when (val result = repository.addComment(taskId = taskId, board = board, body = body)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSubmittingComment = false,
                            toastMessage = UiText.res(R.string.kanban_msg_comment_added),
                        )
                    }
                    loadTask(board, taskId)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isSubmittingComment = false,
                            toastMessage = UiText.res(R.string.kanban_msg_comment_add_failed, result.error.message),
                        )
                    }
                }
            }
        }
    }

    fun postNoteAndRequeue(
        board: String,
        taskId: String,
        body: String,
    ) {
        if (body.isBlank()) return
        _uiState.update { it.copy(isSubmittingComment = true) }
        viewModelScope.launch {
            val commentRes = repository.addComment(taskId = taskId, board = board, body = body)
            if (commentRes is NetworkResult.Failure) {
                _uiState.update {
                    it.copy(
                        isSubmittingComment = false,
                        toastMessage = UiText.res(R.string.kanban_msg_note_post_failed, commentRes.error.message),
                    )
                }
                return@launch
            }

            val reclaimRes = repository.reclaimTask(taskId = taskId, board = board, reason = "Requeued with note")
            when (reclaimRes) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSubmittingComment = false,
                            toastMessage = UiText.res(R.string.kanban_msg_note_posted_requeued),
                        )
                    }
                    loadTask(board, taskId)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isSubmittingComment = false,
                            toastMessage =
                                UiText.res(
                                    R.string.kanban_msg_note_posted_reclaim_failed,
                                    reclaimRes.error.message,
                                ),
                        )
                    }
                }
            }
        }
    }

    fun reclaim(
        board: String,
        taskId: String,
        reason: String? = null,
    ) {
        _uiState.update { it.copy(isReclaiming = true) }
        viewModelScope.launch {
            when (val result = repository.reclaimTask(taskId = taskId, board = board, reason = reason)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isReclaiming = false,
                            toastMessage = UiText.res(R.string.kanban_msg_task_reclaimed),
                        )
                    }
                    loadTask(board, taskId)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isReclaiming = false,
                            toastMessage = UiText.res(R.string.kanban_msg_reclaim_failed, result.error.message),
                        )
                    }
                }
            }
        }
    }

    fun uploadAttachment(
        board: String,
        taskId: String,
        part: MultipartBody.Part,
    ) {
        _uiState.update { it.copy(isUploadingAttachment = true) }
        viewModelScope.launch {
            when (val result = repository.uploadAttachment(taskId = taskId, board = board, file = part)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isUploadingAttachment = false,
                            toastMessage = UiText.res(R.string.kanban_msg_attachment_uploaded),
                        )
                    }
                    if (activeTaskId == taskId && activeBoard == board) {
                        loadTaskSilently(board, taskId)
                        refreshAttachments(board, taskId)
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isUploadingAttachment = false,
                            toastMessage = UiText.res(R.string.kanban_msg_upload_failed, result.error.message),
                        )
                    }
                }
            }
        }
    }

    fun refreshAttachments(
        board: String,
        taskId: String,
    ) {
        viewModelScope.launch {
            when (val result = repository.listAttachments(taskId, board)) {
                is NetworkResult.Success -> {
                    _uiState.update { state ->
                        if (activeTaskId != taskId || activeBoard != board) {
                            state
                        } else {
                            state.copy(detail = state.detail?.copy(attachments = result.data.attachments))
                        }
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = UiText.raw(result.error.message)) }
                }
            }
        }
    }

    fun deleteAttachment(
        board: String,
        taskId: String,
        attachmentId: Long,
    ) {
        if (_uiState.value.isDeletingAttachment) return
        _uiState.update { it.copy(isDeletingAttachment = true) }
        viewModelScope.launch {
            when (val result = repository.deleteAttachment(attachmentId, board)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isDeletingAttachment = false,
                            toastMessage =
                                if (result.data.ok) {
                                    UiText.res(R.string.kanban_msg_attachment_deleted)
                                } else {
                                    UiText.res(R.string.kanban_msg_attachment_not_deleted)
                                },
                        )
                    }
                    if (result.data.ok) refreshAttachments(board, taskId)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(isDeletingAttachment = false, toastMessage = UiText.raw(result.error.message))
                    }
                }
            }
        }
    }

    suspend fun downloadAttachment(
        board: String,
        attachmentId: Long,
    ): NetworkResult<ResponseBody> = repository.downloadAttachment(attachmentId, board)

    fun showToast(message: UiText) {
        _uiState.update { it.copy(toastMessage = message) }
    }

    fun inspectRun(
        board: String,
        taskId: String,
        runId: Long,
    ) {
        if (_uiState.value.isInspectingRun) return
        _uiState.update { it.copy(isInspectingRun = true, selectedRun = null, runInspection = null) }
        viewModelScope.launch {
            val run = repository.getRun(runId, board)
            val inspection = if (run is NetworkResult.Success) repository.inspectRun(runId, board) else null
            if (activeTaskId != null && (activeTaskId != taskId || activeBoard != board)) return@launch
            _uiState.update {
                it.copy(
                    isInspectingRun = false,
                    selectedRun = (run as? NetworkResult.Success)?.data?.run,
                    runInspection = (inspection as? NetworkResult.Success)?.data,
                    toastMessage =
                        (
                            (run as? NetworkResult.Failure)?.error?.message
                                ?: (inspection as? NetworkResult.Failure)?.error?.message
                        )?.let { UiText.raw(it) },
                )
            }
        }
    }

    fun clearRunInspection() {
        _uiState.update { it.copy(selectedRun = null, runInspection = null) }
    }

    fun terminateRun(
        board: String,
        taskId: String,
        runId: Long,
    ) {
        if (_uiState.value.isTerminatingRun) return
        _uiState.update { it.copy(isTerminatingRun = true) }
        viewModelScope.launch {
            when (val result = repository.terminateRun(runId, board)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isTerminatingRun = false,
                            toastMessage =
                                if (result.data.ok) {
                                    UiText.res(R.string.kanban_msg_run_terminated)
                                } else {
                                    UiText.res(R.string.kanban_msg_run_not_terminated)
                                },
                        )
                    }
                    if (result.data.ok && result.data.taskId == taskId &&
                        activeTaskId == taskId && activeBoard == board
                    ) {
                        val latestRun = repository.getRun(runId, board)
                        _uiState.update { it.copy(selectedRun = (latestRun as? NetworkResult.Success)?.data?.run) }
                        loadTaskSilently(board, taskId)
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(isTerminatingRun = false, toastMessage = UiText.raw(result.error.message))
                    }
                }
            }
        }
    }

    fun specifyTask(
        board: String,
        taskId: String,
    ) {
        if (_uiState.value.isSpecifying) return
        _uiState.update { it.copy(isSpecifying = true) }
        viewModelScope.launch {
            when (val result = repository.specifyTask(taskId, board)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSpecifying = false,
                            toastMessage =
                                if (result.data.ok) {
                                    UiText.res(R.string.kanban_msg_task_specified)
                                } else {
                                    result.data.reason?.let { UiText.raw(it) }
                                        ?: UiText.res(R.string.kanban_msg_specification_failed)
                                },
                        )
                    }
                    if (result.data.ok && activeTaskId == taskId &&
                        activeBoard == board
                    ) {
                        loadTaskSilently(board, taskId)
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(isSpecifying = false, toastMessage = UiText.raw(result.error.message))
                    }
                }
            }
        }
    }

    fun createParentLink(
        board: String,
        childId: String,
        parentId: String,
    ) {
        if (parentId.isBlank() || _uiState.value.isLinking) return
        _uiState.update { it.copy(isLinking = true) }
        viewModelScope.launch {
            when (val result = repository.createTaskLink(board, parentId.trim(), childId)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLinking = false,
                            toastMessage =
                                when {
                                    !result.data.ok -> UiText.res(R.string.kanban_msg_parent_link_not_created)
                                    result.data.gated -> UiText.res(R.string.kanban_msg_parent_linked_gated)
                                    else -> UiText.res(R.string.kanban_msg_parent_linked)
                                },
                        )
                    }
                    if (result.data.ok) {
                        if (activeTaskId == childId && activeBoard == board) {
                            loadTaskSilently(board, childId)
                        } else if (activeTaskId == null) {
                            loadTask(board, childId)
                        }
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(isLinking = false, toastMessage = UiText.raw(result.error.message))
                    }
                }
            }
        }
    }

    fun setRunFilter(
        board: String,
        taskId: String,
        type: String?,
        name: String?,
    ) {
        if ((type == null) != (name == null) || type !in setOf(null, "status", "outcome")) return
        _uiState.update { it.copy(runStateType = type, runStateName = name) }
        if (activeBoard == board && activeTaskId == taskId) {
            viewModelScope.launch { loadTaskSilently(board, taskId) }
        }
    }

    public override fun onCleared() {
        stopLogPolling()
        detailPollJob?.cancel()
        reloadJob?.cancel()
        eventsClient?.disconnect()
        super.onCleared()
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private companion object {
        const val RELOAD_DEBOUNCE_MS = 250L
        const val POLL_INTERVAL_MS = 3000L
        const val DETAIL_POLL_INTERVAL_MS = 30_000L
    }
}
