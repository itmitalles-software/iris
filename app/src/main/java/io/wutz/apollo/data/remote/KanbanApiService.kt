package io.wutz.apollo.data.remote

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
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface KanbanApiService {
    @GET("api/plugins/kanban/boards")
    suspend fun getBoards(
        @Query("include_archived") includeArchived: Boolean = false,
    ): Response<KanbanBoardsResponse>

    @GET("api/plugins/kanban/board")
    suspend fun getBoard(
        @Query("board") board: String? = null,
        @Query("include_archived") includeArchived: Boolean = false,
        @Query("tenant") tenant: String? = null,
        @Query("workflow_template_id") workflowTemplateId: String? = null,
        @Query("current_step_key") currentStepKey: String? = null,
    ): Response<KanbanBoardResponse>

    @POST("api/plugins/kanban/boards")
    suspend fun createBoard(
        @Body body: CreateBoardBody,
    ): Response<CreateBoardResponse>

    @PATCH("api/plugins/kanban/boards/{slug}")
    suspend fun updateBoard(
        @Path("slug") slug: String,
        @Body body: RenameBoardBody,
    ): Response<RenameBoardResponse>

    @DELETE("api/plugins/kanban/boards/{slug}")
    suspend fun deleteBoard(
        @Path("slug") slug: String,
        @Query("delete") delete: Boolean = false,
    ): Response<DeleteBoardResponse>

    @POST("api/plugins/kanban/boards/{slug}/switch")
    suspend fun switchBoard(
        @Path("slug") slug: String,
    ): Response<Unit>

    @POST("api/plugins/kanban/boards/{slug}/export")
    suspend fun exportBoard(
        @Path("slug") slug: String,
        @Body body: ExportBoardBody = ExportBoardBody(),
    ): Response<BoardExportResult>

    @POST("api/plugins/kanban/boards/import")
    suspend fun importBoard(
        @Body body: ImportBoardBody,
    ): Response<BoardImportResult>

    @GET("api/plugins/kanban/tasks/{id}")
    suspend fun getTask(
        @Path("id") taskId: String,
        @Query("board") board: String? = null,
        @Query("run_state_type") runStateType: String? = null,
        @Query("run_state_name") runStateName: String? = null,
    ): Response<KanbanTaskDetailResponse>

    @POST("api/plugins/kanban/tasks")
    suspend fun createTask(
        @Query("board") board: String? = null,
        @Body body: CreateTaskBody,
    ): Response<CreateTaskResponse>

    @PATCH("api/plugins/kanban/tasks/{id}")
    suspend fun updateTask(
        @Path("id") taskId: String,
        @Query("board") board: String? = null,
        @Body body: UpdateTaskBody,
    ): Response<UpdateTaskResponse>

    @DELETE("api/plugins/kanban/tasks/{id}")
    suspend fun deleteTask(
        @Path("id") taskId: String,
        @Query("board") board: String? = null,
    ): Response<Unit>

    @POST("api/plugins/kanban/tasks/bulk")
    suspend fun bulkTasks(
        @Query("board") board: String? = null,
        @Body body: BulkTasksBody,
    ): Response<BulkTasksResponse>

    @POST("api/plugins/kanban/tasks/{id}/comments")
    suspend fun addComment(
        @Path("id") taskId: String,
        @Query("board") board: String? = null,
        @Body body: Map<String, String>,
    ): Response<Unit>

    @POST("api/plugins/kanban/tasks/{id}/reassign")
    suspend fun reassignTask(
        @Path("id") taskId: String,
        @Query("board") board: String? = null,
        @Body body: ReassignTaskBody,
    ): Response<ReassignTaskResponse>

    @POST("api/plugins/kanban/tasks/{id}/reclaim")
    suspend fun reclaimTask(
        @Path("id") taskId: String,
        @Query("board") board: String? = null,
        @Body body: ReclaimTaskBody = ReclaimTaskBody(),
    ): Response<ReclaimTaskResponse>

    @GET("api/plugins/kanban/tasks/{id}/log")
    suspend fun getTaskLog(
        @Path("id") taskId: String,
        @Query("tail") tail: Int = 16384,
        @Query("board") board: String? = null,
    ): Response<WorkerLog>

    @POST("api/plugins/kanban/tasks/{id}/estimate")
    suspend fun estimateTask(
        @Path("id") taskId: String,
        @Query("board") board: String? = null,
    ): Response<TaskEstimate>

    @POST("api/plugins/kanban/estimate")
    suspend fun estimateNew(
        @Body body: Map<String, String>,
    ): Response<TaskEstimate>

    @Multipart
    @POST("api/plugins/kanban/tasks/{id}/attachments")
    suspend fun uploadAttachment(
        @Path("id") taskId: String,
        @Query("board") board: String? = null,
        @Part file: MultipartBody.Part,
    ): Response<AttachmentUploadResponse>

    @GET("api/plugins/kanban/tasks/{id}/attachments")
    suspend fun listAttachments(
        @Path("id") taskId: String,
        @Query("board") board: String? = null,
    ): Response<AttachmentListResponse>

    @Streaming
    @GET("api/plugins/kanban/attachments/{id}")
    suspend fun downloadAttachment(
        @Path("id") attachmentId: Long,
        @Query("board") board: String? = null,
    ): Response<ResponseBody>

    @DELETE("api/plugins/kanban/attachments/{id}")
    suspend fun deleteAttachment(
        @Path("id") attachmentId: Long,
        @Query("board") board: String? = null,
    ): Response<DeleteAttachmentResponse>

    @GET("api/plugins/kanban/workers/active")
    suspend fun getActiveWorkers(
        @Query("board") board: String? = null,
    ): Response<ActiveWorkersResponse>

    @GET("api/plugins/kanban/runs/{id}")
    suspend fun getRun(
        @Path("id") runId: Long,
        @Query("board") board: String? = null,
    ): Response<KanbanRunResponse>

    @GET("api/plugins/kanban/runs/{id}/inspect")
    suspend fun inspectRun(
        @Path("id") runId: Long,
        @Query("board") board: String? = null,
    ): Response<KanbanRunInspection>

    @POST("api/plugins/kanban/runs/{id}/terminate")
    suspend fun terminateRun(
        @Path("id") runId: Long,
        @Query("board") board: String? = null,
        @Body body: TerminateRunBody = TerminateRunBody(),
    ): Response<TerminateRunResponse>

    @POST("api/plugins/kanban/tasks/{id}/specify")
    suspend fun specifyTask(
        @Path("id") taskId: String,
        @Query("board") board: String? = null,
        @Body body: SpecifyTaskBody = SpecifyTaskBody(),
    ): Response<SpecifyTaskResponse>

    @POST("api/plugins/kanban/links")
    suspend fun createTaskLink(
        @Query("board") board: String? = null,
        @Body body: TaskLinkBody,
    ): Response<TaskLinkResponse>

    @GET("api/plugins/kanban/profiles")
    suspend fun getKanbanProfiles(): Response<KanbanProfilesResponse>

    @PATCH("api/plugins/kanban/profiles/{name}")
    suspend fun updateProfileDescription(
        @Path("name") name: String,
        @Body body: Map<String, String>,
    ): Response<Unit>

    @POST("api/plugins/kanban/profiles/{name}/describe-auto")
    suspend fun autoDescribeProfile(
        @Path("name") name: String,
        @Body body: Map<String, Boolean> = mapOf("overwrite" to true),
    ): Response<AutoDescribeResponse>

    @GET("api/plugins/kanban/orchestration")
    suspend fun getOrchestration(): Response<OrchestrationSettings>

    @PUT("api/plugins/kanban/orchestration")
    suspend fun updateOrchestration(
        @Body body: OrchestrationSettingsUpdate,
    ): Response<OrchestrationSettings>

    @GET("api/plugins/kanban/projects")
    suspend fun getProjects(): Response<KanbanProjectsResponse>

    @POST("api/plugins/kanban/dispatch")
    suspend fun nudgeDispatcher(
        @Query("board") board: String? = null,
    ): Response<DispatchResult>
}
