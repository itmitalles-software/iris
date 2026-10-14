package io.wutz.apollo.data.repository

import io.wutz.apollo.data.model.ActiveWorkersResponse
import io.wutz.apollo.data.model.AttachmentListResponse
import io.wutz.apollo.data.model.AttachmentUploadResponse
import io.wutz.apollo.data.model.AutoDescribeResponse
import io.wutz.apollo.data.model.BoardExportResult
import io.wutz.apollo.data.model.BoardImportResult
import io.wutz.apollo.data.model.BulkTasksBody
import io.wutz.apollo.data.model.BulkTasksResponse
import io.wutz.apollo.data.model.CreateBoardBody
import io.wutz.apollo.data.model.CreateBoardResponse
import io.wutz.apollo.data.model.CreateTaskBody
import io.wutz.apollo.data.model.CreateTaskResponse
import io.wutz.apollo.data.model.DeleteAttachmentResponse
import io.wutz.apollo.data.model.DeleteBoardResponse
import io.wutz.apollo.data.model.DispatchResult
import io.wutz.apollo.data.model.ExportBoardBody
import io.wutz.apollo.data.model.ImportBoardBody
import io.wutz.apollo.data.model.KanbanBoardResponse
import io.wutz.apollo.data.model.KanbanBoardsResponse
import io.wutz.apollo.data.model.KanbanProfilesResponse
import io.wutz.apollo.data.model.KanbanProjectsResponse
import io.wutz.apollo.data.model.KanbanRunInspection
import io.wutz.apollo.data.model.KanbanRunResponse
import io.wutz.apollo.data.model.KanbanTaskDetailResponse
import io.wutz.apollo.data.model.ManagedFileActionResponse
import io.wutz.apollo.data.model.ManagedFileDelete
import io.wutz.apollo.data.model.OrchestrationSettings
import io.wutz.apollo.data.model.OrchestrationSettingsUpdate
import io.wutz.apollo.data.model.ReassignTaskBody
import io.wutz.apollo.data.model.ReassignTaskResponse
import io.wutz.apollo.data.model.ReclaimTaskBody
import io.wutz.apollo.data.model.ReclaimTaskResponse
import io.wutz.apollo.data.model.RenameBoardBody
import io.wutz.apollo.data.model.RenameBoardResponse
import io.wutz.apollo.data.model.SpecifyTaskBody
import io.wutz.apollo.data.model.SpecifyTaskResponse
import io.wutz.apollo.data.model.TaskEstimate
import io.wutz.apollo.data.model.TaskLinkBody
import io.wutz.apollo.data.model.TaskLinkResponse
import io.wutz.apollo.data.model.TerminateRunBody
import io.wutz.apollo.data.model.TerminateRunResponse
import io.wutz.apollo.data.model.UpdateTaskBody
import io.wutz.apollo.data.model.UpdateTaskResponse
import io.wutz.apollo.data.model.WorkerLog
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.HermesApiService
import io.wutz.apollo.data.remote.KanbanApiService
import io.wutz.apollo.data.remote.NetworkResult
import io.wutz.apollo.data.remote.safeApiCall
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody

interface KanbanRepository {
    suspend fun getBoards(includeArchived: Boolean = false): NetworkResult<KanbanBoardsResponse>

    suspend fun getBoard(
        board: String,
        includeArchived: Boolean = false,
        tenant: String? = null,
        workflowTemplateId: String? = null,
        currentStepKey: String? = null,
    ): NetworkResult<KanbanBoardResponse>

    suspend fun createBoard(body: CreateBoardBody): NetworkResult<CreateBoardResponse>

    suspend fun updateBoard(
        slug: String,
        body: RenameBoardBody,
    ): NetworkResult<RenameBoardResponse>

    suspend fun deleteBoard(
        slug: String,
        delete: Boolean = false,
    ): NetworkResult<DeleteBoardResponse>

    suspend fun exportBoard(
        slug: String,
        body: ExportBoardBody = ExportBoardBody(),
    ): NetworkResult<BoardExportResult>

    suspend fun importBoard(body: ImportBoardBody): NetworkResult<BoardImportResult>

    /**
     * Uploads a board archive into the managed files area so [importBoard] can read it. Returns the
     * server path of the stored archive ([targetPath] when the server does not echo one).
     */
    suspend fun uploadImportArchive(
        targetPath: String,
        fileName: String,
        archive: RequestBody,
    ): NetworkResult<String>

    /** Removes an archive stored by [uploadImportArchive]. */
    suspend fun deleteImportArchive(serverPath: String): NetworkResult<ManagedFileActionResponse>

    suspend fun getTask(
        taskId: String,
        board: String?,
        runStateType: String? = null,
        runStateName: String? = null,
    ): NetworkResult<KanbanTaskDetailResponse>

    suspend fun createTask(
        board: String?,
        body: CreateTaskBody,
    ): NetworkResult<CreateTaskResponse>

    suspend fun updateTask(
        taskId: String,
        board: String?,
        body: UpdateTaskBody,
    ): NetworkResult<UpdateTaskResponse>

    suspend fun deleteTask(
        taskId: String,
        board: String?,
    ): NetworkResult<Unit>

    suspend fun bulkTasks(
        board: String?,
        body: BulkTasksBody,
    ): NetworkResult<BulkTasksResponse>

    suspend fun addComment(
        taskId: String,
        board: String?,
        body: String,
    ): NetworkResult<Unit>

    suspend fun reassignTask(
        taskId: String,
        board: String?,
        profile: String?,
        reclaimFirst: Boolean = true,
    ): NetworkResult<ReassignTaskResponse>

    suspend fun reclaimTask(
        taskId: String,
        board: String?,
        reason: String? = null,
    ): NetworkResult<ReclaimTaskResponse>

    suspend fun getTaskLog(
        taskId: String,
        board: String?,
        tail: Int = 16384,
    ): NetworkResult<WorkerLog>

    suspend fun estimateTask(
        taskId: String,
        board: String?,
    ): NetworkResult<TaskEstimate>

    suspend fun estimateNew(
        title: String,
        body: String?,
    ): NetworkResult<TaskEstimate>

    suspend fun uploadAttachment(
        taskId: String,
        board: String?,
        file: MultipartBody.Part,
    ): NetworkResult<AttachmentUploadResponse>

    suspend fun listAttachments(
        taskId: String,
        board: String,
    ): NetworkResult<AttachmentListResponse>

    suspend fun deleteAttachment(
        attachmentId: Long,
        board: String,
    ): NetworkResult<DeleteAttachmentResponse>

    suspend fun downloadAttachment(
        attachmentId: Long,
        board: String?,
    ): NetworkResult<ResponseBody>

    suspend fun getActiveWorkers(board: String): NetworkResult<ActiveWorkersResponse>

    suspend fun getRun(
        runId: Long,
        board: String,
    ): NetworkResult<KanbanRunResponse>

    suspend fun inspectRun(
        runId: Long,
        board: String,
    ): NetworkResult<KanbanRunInspection>

    suspend fun terminateRun(
        runId: Long,
        board: String,
    ): NetworkResult<TerminateRunResponse>

    suspend fun specifyTask(
        taskId: String,
        board: String,
    ): NetworkResult<SpecifyTaskResponse>

    suspend fun createTaskLink(
        board: String,
        parentId: String,
        childId: String,
    ): NetworkResult<TaskLinkResponse>

    suspend fun getProfiles(): NetworkResult<KanbanProfilesResponse>

    suspend fun updateProfileDescription(
        name: String,
        description: String,
    ): NetworkResult<Unit>

    suspend fun autoDescribeProfile(name: String): NetworkResult<AutoDescribeResponse>

    suspend fun getOrchestration(): NetworkResult<OrchestrationSettings>

    suspend fun updateOrchestration(body: OrchestrationSettingsUpdate): NetworkResult<OrchestrationSettings>

    suspend fun getProjects(): NetworkResult<KanbanProjectsResponse>

    suspend fun nudgeDispatcher(board: String?): NetworkResult<DispatchResult>
}

class KanbanRepositoryImpl(
    private val apiProvider: () -> KanbanApiService = { ApiClient.kanbanApi },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nudger: KanbanDispatcherNudger = DefaultKanbanDispatcherNudger(apiProvider),
    private val filesApiProvider: () -> HermesApiService = { ApiClient.hermesApi },
) : KanbanRepository {
    override suspend fun getBoards(includeArchived: Boolean): NetworkResult<KanbanBoardsResponse> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().getBoards(includeArchived) }
        }

    override suspend fun getBoard(
        board: String,
        includeArchived: Boolean,
        tenant: String?,
        workflowTemplateId: String?,
        currentStepKey: String?,
    ): NetworkResult<KanbanBoardResponse> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().getBoard(board, includeArchived, tenant, workflowTemplateId, currentStepKey) }
        }

    override suspend fun createBoard(body: CreateBoardBody): NetworkResult<CreateBoardResponse> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().createBoard(body) }
        }

    override suspend fun updateBoard(
        slug: String,
        body: RenameBoardBody,
    ): NetworkResult<RenameBoardResponse> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().updateBoard(slug, body) }
        }

    override suspend fun deleteBoard(
        slug: String,
        delete: Boolean,
    ): NetworkResult<DeleteBoardResponse> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().deleteBoard(slug, delete) }
        }

    override suspend fun exportBoard(
        slug: String,
        body: ExportBoardBody,
    ): NetworkResult<BoardExportResult> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().exportBoard(slug, body) }
        }

    override suspend fun importBoard(body: ImportBoardBody): NetworkResult<BoardImportResult> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().importBoard(body) }
        }

    // Retrofit streams the body on OkHttp's worker; no withContext, so a confirmed upload response is not
    // lost to its prompt cancellation on the return hop (#1137).
    override suspend fun uploadImportArchive(
        targetPath: String,
        fileName: String,
        archive: RequestBody,
    ): NetworkResult<String> {
        val pathBody = targetPath.toRequestBody("text/plain".toMediaTypeOrNull())
        val overwriteBody = "true".toRequestBody("text/plain".toMediaTypeOrNull())
        val filePart = MultipartBody.Part.createFormData("file", fileName, archive)
        return when (
            val result =
                safeApiCall { filesApiProvider().uploadManagedFileStream(pathBody, overwriteBody, filePart) }
        ) {
            is NetworkResult.Success -> NetworkResult.Success(result.data.entry?.path ?: result.data.path ?: targetPath)
            is NetworkResult.Failure -> result
        }
    }

    override suspend fun deleteImportArchive(serverPath: String): NetworkResult<ManagedFileActionResponse> =
        withContext(ioDispatcher) {
            safeApiCall { filesApiProvider().deleteManagedFile(ManagedFileDelete(serverPath)) }
        }

    override suspend fun getTask(
        taskId: String,
        board: String?,
        runStateType: String?,
        runStateName: String?,
    ): NetworkResult<KanbanTaskDetailResponse> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().getTask(taskId, board, runStateType, runStateName) }
        }

    override suspend fun createTask(
        board: String?,
        body: CreateTaskBody,
    ): NetworkResult<CreateTaskResponse> =
        withContext(ioDispatcher) {
            val result = safeApiCall { apiProvider().createTask(board = board, body = body) }
            if (result is NetworkResult.Success) {
                nudger.scheduleNudge(board)
            }
            result
        }

    override suspend fun updateTask(
        taskId: String,
        board: String?,
        body: UpdateTaskBody,
    ): NetworkResult<UpdateTaskResponse> =
        withContext(ioDispatcher) {
            val result = safeApiCall { apiProvider().updateTask(taskId = taskId, board = board, body = body) }
            if (result is NetworkResult.Success) {
                nudger.scheduleNudge(board)
            }
            result
        }

    override suspend fun deleteTask(
        taskId: String,
        board: String?,
    ): NetworkResult<Unit> =
        withContext(ioDispatcher) {
            val result = safeApiCall { apiProvider().deleteTask(taskId = taskId, board = board) }
            if (result is NetworkResult.Success) {
                nudger.scheduleNudge(board)
            }
            result
        }

    override suspend fun bulkTasks(
        board: String?,
        body: BulkTasksBody,
    ): NetworkResult<BulkTasksResponse> =
        withContext(ioDispatcher) {
            val result = safeApiCall { apiProvider().bulkTasks(board = board, body = body) }
            if (result is NetworkResult.Success) {
                nudger.scheduleNudge(board)
            }
            result
        }

    override suspend fun addComment(
        taskId: String,
        board: String?,
        body: String,
    ): NetworkResult<Unit> =
        withContext(ioDispatcher) {
            safeApiCall {
                apiProvider().addComment(
                    taskId = taskId,
                    board = board,
                    body = mapOf("body" to body, "author" to "mobile"),
                )
            }
        }

    override suspend fun reassignTask(
        taskId: String,
        board: String?,
        profile: String?,
        reclaimFirst: Boolean,
    ): NetworkResult<ReassignTaskResponse> =
        withContext(ioDispatcher) {
            val result =
                safeApiCall {
                    apiProvider().reassignTask(
                        taskId = taskId,
                        board = board,
                        body = ReassignTaskBody(profile = profile, reclaimFirst = reclaimFirst),
                    )
                }
            if (result is NetworkResult.Success) {
                nudger.scheduleNudge(board)
            }
            result
        }

    override suspend fun reclaimTask(
        taskId: String,
        board: String?,
        reason: String?,
    ): NetworkResult<ReclaimTaskResponse> =
        withContext(ioDispatcher) {
            val result =
                safeApiCall {
                    apiProvider().reclaimTask(
                        taskId = taskId,
                        board = board,
                        body = ReclaimTaskBody(reason = reason),
                    )
                }
            if (result is NetworkResult.Success) {
                nudger.scheduleNudge(board)
            }
            result
        }

    override suspend fun getTaskLog(
        taskId: String,
        board: String?,
        tail: Int,
    ): NetworkResult<WorkerLog> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().getTaskLog(taskId = taskId, tail = tail, board = board) }
        }

    override suspend fun estimateTask(
        taskId: String,
        board: String?,
    ): NetworkResult<TaskEstimate> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().estimateTask(taskId = taskId, board = board) }
        }

    override suspend fun estimateNew(
        title: String,
        body: String?,
    ): NetworkResult<TaskEstimate> =
        withContext(ioDispatcher) {
            val payload =
                buildMap {
                    put("title", title)
                    if (!body.isNullOrBlank()) put("body", body)
                }
            safeApiCall { apiProvider().estimateNew(payload) }
        }

    override suspend fun uploadAttachment(
        taskId: String,
        board: String?,
        file: MultipartBody.Part,
    ): NetworkResult<AttachmentUploadResponse> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().uploadAttachment(taskId = taskId, board = board, file = file) }
        }

    override suspend fun listAttachments(
        taskId: String,
        board: String,
    ): NetworkResult<AttachmentListResponse> =
        withContext(ioDispatcher) { safeApiCall { apiProvider().listAttachments(taskId, board) } }

    override suspend fun deleteAttachment(
        attachmentId: Long,
        board: String,
    ): NetworkResult<DeleteAttachmentResponse> =
        withContext(ioDispatcher) { safeApiCall { apiProvider().deleteAttachment(attachmentId, board) } }

    override suspend fun downloadAttachment(
        attachmentId: Long,
        board: String?,
    ): NetworkResult<ResponseBody> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().downloadAttachment(attachmentId = attachmentId, board = board) }
        }

    override suspend fun getActiveWorkers(board: String): NetworkResult<ActiveWorkersResponse> =
        withContext(ioDispatcher) { safeApiCall { apiProvider().getActiveWorkers(board) } }

    override suspend fun getRun(
        runId: Long,
        board: String,
    ): NetworkResult<KanbanRunResponse> =
        withContext(ioDispatcher) { safeApiCall { apiProvider().getRun(runId, board) } }

    override suspend fun inspectRun(
        runId: Long,
        board: String,
    ): NetworkResult<KanbanRunInspection> =
        withContext(ioDispatcher) { safeApiCall { apiProvider().inspectRun(runId, board) } }

    override suspend fun terminateRun(
        runId: Long,
        board: String,
    ): NetworkResult<TerminateRunResponse> =
        withContext(ioDispatcher) {
            val result = safeApiCall { apiProvider().terminateRun(runId, board, TerminateRunBody(reason = "mobile")) }
            if (result is NetworkResult.Success && result.data.ok) nudger.scheduleNudge(board)
            result
        }

    override suspend fun specifyTask(
        taskId: String,
        board: String,
    ): NetworkResult<SpecifyTaskResponse> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().specifyTask(taskId, board, SpecifyTaskBody(author = "mobile")) }
        }

    override suspend fun createTaskLink(
        board: String,
        parentId: String,
        childId: String,
    ): NetworkResult<TaskLinkResponse> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().createTaskLink(board, TaskLinkBody(parentId, childId)) }
        }

    override suspend fun getProfiles(): NetworkResult<KanbanProfilesResponse> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().getKanbanProfiles() }
        }

    override suspend fun updateProfileDescription(
        name: String,
        description: String,
    ): NetworkResult<Unit> =
        withContext(ioDispatcher) {
            safeApiCall {
                apiProvider().updateProfileDescription(
                    name = name,
                    body = mapOf("description" to description),
                )
            }
        }

    override suspend fun autoDescribeProfile(name: String): NetworkResult<AutoDescribeResponse> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().autoDescribeProfile(name) }
        }

    override suspend fun getOrchestration(): NetworkResult<OrchestrationSettings> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().getOrchestration() }
        }

    override suspend fun updateOrchestration(body: OrchestrationSettingsUpdate): NetworkResult<OrchestrationSettings> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().updateOrchestration(body) }
        }

    override suspend fun getProjects(): NetworkResult<KanbanProjectsResponse> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().getProjects() }
        }

    override suspend fun nudgeDispatcher(board: String?): NetworkResult<DispatchResult> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().nudgeDispatcher(board) }
        }
}
