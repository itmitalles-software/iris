package io.wutz.apollo.ui.kanban

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import io.wutz.apollo.R
import io.wutz.apollo.data.model.AttachmentListResponse
import io.wutz.apollo.data.model.DeleteAttachmentResponse
import io.wutz.apollo.data.model.KanbanAttachment
import io.wutz.apollo.data.model.KanbanRun
import io.wutz.apollo.data.model.KanbanRunInspection
import io.wutz.apollo.data.model.KanbanRunResponse
import io.wutz.apollo.data.model.KanbanTaskDetailResponse
import io.wutz.apollo.data.model.KanbanTaskFull
import io.wutz.apollo.data.model.ReassignTaskResponse
import io.wutz.apollo.data.model.ReclaimTaskResponse
import io.wutz.apollo.data.model.SpecifyTaskResponse
import io.wutz.apollo.data.model.TaskLinkResponse
import io.wutz.apollo.data.model.TerminateRunResponse
import io.wutz.apollo.data.model.UpdateTaskResponse
import io.wutz.apollo.data.model.WorkerLog
import io.wutz.apollo.data.remote.NetworkError
import io.wutz.apollo.data.remote.NetworkResult
import io.wutz.apollo.data.repository.KanbanRepository
import io.wutz.apollo.data.ws.KanbanEventsClient
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KanbanTaskViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockRepository = mockk<KanbanRepository>(relaxed = true)
    private val mockEventsClient = mockk<KanbanEventsClient>(relaxed = true)

    private fun createViewModel(enableFallbackPolling: Boolean = false): KanbanTaskViewModel =
        KanbanTaskViewModel(
            repository = mockRepository,
            eventsClientProvider = { mockEventsClient },
            enableFallbackPolling = enableFallbackPolling,
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testLoadTaskSuccess() =
        runTest(testDispatcher) {
            val task = KanbanTaskFull(id = "t_1", title = "Task 1", status = "todo")
            coEvery { mockRepository.getTask("t_1", "dev") } returns
                NetworkResult.Success(KanbanTaskDetailResponse(task = task))

            val vm = createViewModel()
            vm.loadTask("dev", "t_1")
            testDispatcher.scheduler.advanceUntilIdle()

            assertNotNull(vm.uiState.value.detail)
            assertEquals(
                "Task 1",
                vm.uiState.value.detail
                    ?.task
                    ?.title,
            )
        }

    @Test
    fun testLoadRunningTaskLoadsLog() =
        runTest(testDispatcher) {
            val task = KanbanTaskFull(id = "t_1", title = "Running task", status = "running")
            coEvery { mockRepository.getTask("t_1", "dev") } returns
                NetworkResult.Success(KanbanTaskDetailResponse(task = task))
            coEvery { mockRepository.getTaskLog("t_1", "dev", any()) } returns
                NetworkResult.Success(WorkerLog(taskId = "t_1", content = "Log line 1", exists = true))

            val vm = createViewModel()
            vm.loadTask("dev", "t_1")
            testDispatcher.scheduler.runCurrent()

            assertEquals(
                "Log line 1",
                vm.uiState.value.workerLog
                    ?.content,
            )
            coVerify { mockRepository.getTaskLog("t_1", "dev", any()) }
            vm.onCleared()
        }

    @Test
    fun testUpdateDescription() =
        runTest(testDispatcher) {
            val task = KanbanTaskFull(id = "t_1", title = "Task 1", body = "Old body", status = "todo")
            coEvery { mockRepository.getTask("t_1", "dev") } returns
                NetworkResult.Success(KanbanTaskDetailResponse(task = task))
            coEvery { mockRepository.updateTask("t_1", "dev", any()) } returns
                NetworkResult.Success(UpdateTaskResponse())

            val vm = createViewModel()
            vm.loadTask("dev", "t_1")
            testDispatcher.scheduler.advanceUntilIdle()

            vm.updateDescription("dev", "t_1", "New description")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                "New description",
                vm.uiState.value.detail
                    ?.task
                    ?.body,
            )
            assertEquals(UiText.res(R.string.kanban_msg_description_saved), vm.uiState.value.toastMessage)
        }

    @Test
    fun testAddComment() =
        runTest(testDispatcher) {
            val task = KanbanTaskFull(id = "t_1", title = "Task 1", status = "todo")
            coEvery { mockRepository.getTask("t_1", "dev") } returns
                NetworkResult.Success(KanbanTaskDetailResponse(task = task))
            coEvery { mockRepository.addComment("t_1", "dev", "Steering note") } returns
                NetworkResult.Success(Unit)

            val vm = createViewModel()
            vm.loadTask("dev", "t_1")
            testDispatcher.scheduler.advanceUntilIdle()

            vm.addComment("dev", "t_1", "Steering note")
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { mockRepository.addComment("t_1", "dev", "Steering note") }
            assertEquals(UiText.res(R.string.kanban_msg_comment_added), vm.uiState.value.toastMessage)
        }

    @Test
    fun testPostNoteAndRequeue() =
        runTest(testDispatcher) {
            val task = KanbanTaskFull(id = "t_1", title = "Task 1", status = "todo")
            coEvery { mockRepository.getTask("t_1", "dev") } returns
                NetworkResult.Success(KanbanTaskDetailResponse(task = task))
            coEvery { mockRepository.addComment("t_1", "dev", "Stop now") } returns
                NetworkResult.Success(Unit)
            coEvery { mockRepository.reclaimTask("t_1", "dev", any()) } returns
                NetworkResult.Success(ReclaimTaskResponse(taskId = "t_1"))

            val vm = createViewModel()
            vm.loadTask("dev", "t_1")
            testDispatcher.scheduler.advanceUntilIdle()

            vm.postNoteAndRequeue("dev", "t_1", "Stop now")
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { mockRepository.addComment("t_1", "dev", "Stop now") }
            coVerify { mockRepository.reclaimTask("t_1", "dev", match { it.contains("Requeued") }) }
            assertEquals(
                UiText.res(R.string.kanban_msg_note_posted_requeued),
                vm.uiState.value.toastMessage,
            )
        }

    @Test
    fun testReassign() =
        runTest(testDispatcher) {
            coEvery { mockRepository.reassignTask("t_1", "dev", "coder", true) } returns
                NetworkResult.Success(ReassignTaskResponse(taskId = "t_1", assignee = "coder"))

            val vm = createViewModel()
            vm.reassign("dev", "t_1", "coder")
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { mockRepository.reassignTask("t_1", "dev", "coder", true) }
            assertEquals(
                UiText.res(R.string.kanban_msg_task_reassigned, "coder"),
                vm.uiState.value.toastMessage,
            )
        }

    @Test
    fun testUploadAttachmentSuccess() =
        runTest(testDispatcher) {
            val task = KanbanTaskFull(id = "t_1", title = "Task with file", status = "todo")
            coEvery { mockRepository.getTask("t_1", "dev") } returns
                NetworkResult.Success(KanbanTaskDetailResponse(task = task))
            coEvery { mockRepository.uploadAttachment("t_1", "dev", any()) } returns
                NetworkResult.Success(
                    io.wutz.apollo.data.model.AttachmentUploadResponse(
                        attachment =
                            io.wutz.apollo.data.model.KanbanAttachment(
                                id = 101L,
                                filename = "test.png",
                            ),
                    ),
                )

            val vm = createViewModel()
            vm.uploadAttachment(
                "dev",
                "t_1",
                MultipartBody.Part.createFormData("file", "test.png", byteArrayOf(1, 2, 3).toRequestBody()),
            )
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { mockRepository.uploadAttachment("t_1", "dev", any()) }
            assertEquals(UiText.res(R.string.kanban_msg_attachment_uploaded), vm.uiState.value.toastMessage)
        }

    @Test
    fun testUpdateModelOverride() =
        runTest(testDispatcher) {
            val task = KanbanTaskFull(id = "t_1", title = "Task 1", status = "todo")
            coEvery { mockRepository.getTask("t_1", "dev") } returns
                NetworkResult.Success(KanbanTaskDetailResponse(task = task))
            coEvery { mockRepository.updateTask("t_1", "dev", any()) } returns
                NetworkResult.Success(UpdateTaskResponse())

            val vm = createViewModel()
            vm.loadTask("dev", "t_1")
            testDispatcher.scheduler.advanceUntilIdle()

            val override = KanbanModelOverride(model = "claude-3-5-sonnet", provider = "anthropic", effort = "high")
            vm.updateModelOverride("dev", "t_1", override)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify {
                mockRepository.updateTask(
                    "t_1",
                    "dev",
                    match {
                        it.modelOverride == "claude-3-5-sonnet" &&
                            it.providerOverride == "anthropic" &&
                            it.reasoningEffort == "high" &&
                            !it.clearModelOverride
                    },
                )
            }
            assertEquals(UiText.res(R.string.kanban_msg_model_override_updated), vm.uiState.value.toastMessage)
        }

    @Test
    fun testEstimateTask() =
        runTest(testDispatcher) {
            val task = KanbanTaskFull(id = "t_1", title = "Task 1", status = "todo")
            coEvery { mockRepository.getTask("t_1", "dev") } returns
                NetworkResult.Success(KanbanTaskDetailResponse(task = task))
            coEvery { mockRepository.estimateTask("t_1", "dev") } returns
                NetworkResult.Success(
                    io.wutz.apollo.data.model.TaskEstimate(
                        ok = true,
                        estTokens = 1500,
                        complexity = "medium",
                    ),
                )

            val vm = createViewModel()
            vm.loadTask("dev", "t_1")
            testDispatcher.scheduler.advanceUntilIdle()

            vm.estimateTask("dev", "t_1")
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { mockRepository.estimateTask("t_1", "dev") }
            assertEquals(
                1500,
                vm.uiState.value.estimate
                    ?.tokens,
            )
            assertEquals(
                "medium",
                vm.uiState.value.estimate
                    ?.complexity,
            )
        }

    @Test
    fun testDeleteTask() =
        runTest(testDispatcher) {
            coEvery { mockRepository.deleteTask("t_1", "dev") } returns NetworkResult.Success(Unit)

            val vm = createViewModel()
            var deleted = false
            vm.deleteTask("dev", "t_1") {
                deleted = true
            }
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { mockRepository.deleteTask("t_1", "dev") }
            assertTrue(deleted)
            assertEquals(UiText.res(R.string.kanban_msg_task_deleted), vm.uiState.value.toastMessage)
        }

    @Test
    fun testFallbackPollingReloadsTaskWithoutEvents() =
        runTest(testDispatcher) {
            val first = KanbanTaskFull(id = "t_1", title = "Before", status = "todo")
            val second = KanbanTaskFull(id = "t_1", title = "After", status = "todo")
            coEvery { mockRepository.getTask("t_1", "dev") } returnsMany
                listOf(
                    NetworkResult.Success(KanbanTaskDetailResponse(task = first)),
                    NetworkResult.Success(KanbanTaskDetailResponse(task = second)),
                )

            val vm = createViewModel(enableFallbackPolling = true)
            vm.loadTask("dev", "t_1")
            testDispatcher.scheduler.runCurrent()
            assertEquals(
                "Before",
                vm.uiState.value.detail
                    ?.task
                    ?.title,
            )

            testDispatcher.scheduler.advanceTimeBy(30_000L)
            testDispatcher.scheduler.runCurrent()

            assertEquals(
                "After",
                vm.uiState.value.detail
                    ?.task
                    ?.title,
            )
            coVerify(exactly = 2) { mockRepository.getTask("t_1", "dev") }
            vm.onCleared()
        }

    @Test
    fun testTerminateRunRefreshesRunAndExactTask() =
        runTest(testDispatcher) {
            val running = KanbanTaskFull(id = "t_1", title = "Task", status = "running")
            val ready = running.copy(status = "ready")
            coEvery { mockRepository.getTask("t_1", "dev", any(), any()) } returnsMany
                listOf(
                    NetworkResult.Success(KanbanTaskDetailResponse(task = running)),
                    NetworkResult.Success(KanbanTaskDetailResponse(task = ready)),
                )
            coEvery { mockRepository.terminateRun(7L, "dev") } returns
                NetworkResult.Success(TerminateRunResponse(ok = true, runId = 7L, taskId = "t_1"))
            coEvery { mockRepository.getRun(7L, "dev") } returns
                NetworkResult.Success(KanbanRunResponse(KanbanRun(id = 7L, status = "reclaimed")))

            val vm = createViewModel()
            vm.loadTask("dev", "t_1")
            testDispatcher.scheduler.runCurrent()
            vm.terminateRun("dev", "t_1", 7L)
            testDispatcher.scheduler.runCurrent()

            assertEquals(
                "ready",
                vm.uiState.value.detail
                    ?.task
                    ?.status,
            )
            assertEquals(
                "reclaimed",
                vm.uiState.value.selectedRun
                    ?.status,
            )
            coVerify(exactly = 2) { mockRepository.getTask("t_1", "dev", any(), any()) }
            coVerify { mockRepository.getRun(7L, "dev") }
            vm.onCleared()
        }

    @Test
    fun testRunInspectionUnavailableIsRepresentedHonestly() =
        runTest(testDispatcher) {
            coEvery { mockRepository.getRun(7L, "dev") } returns
                NetworkResult.Success(KanbanRunResponse(KanbanRun(id = 7L, status = "running")))
            coEvery { mockRepository.inspectRun(7L, "dev") } returns
                NetworkResult.Success(KanbanRunInspection(runId = 7L, alive = false, reason = "psutil not available"))

            val vm = createViewModel()
            vm.inspectRun("dev", "t_1", 7L)
            testDispatcher.scheduler.runCurrent()

            assertEquals(
                "psutil not available",
                vm.uiState.value.runInspection
                    ?.reason,
            )
            assertEquals(
                7L,
                vm.uiState.value.selectedRun
                    ?.id,
            )
        }

    @Test
    fun testSpecifyFailureUsesTypedReasonWithoutRefreshingTask() =
        runTest(testDispatcher) {
            coEvery { mockRepository.specifyTask("t_1", "dev") } returns
                NetworkResult.Success(SpecifyTaskResponse(ok = false, taskId = "t_1", reason = "Not configured"))

            val vm = createViewModel()
            vm.specifyTask("dev", "t_1")
            testDispatcher.scheduler.runCurrent()

            assertEquals(
                UiText.raw("Not configured"),
                vm.uiState.value.toastMessage,
            )
            coVerify(exactly = 0) { mockRepository.getTask("t_1", "dev", any(), any()) }
        }

    @Test
    fun testLinkGatingOutcomeIsVisibleAndReloadsTask() =
        runTest(testDispatcher) {
            coEvery { mockRepository.createTaskLink("dev", "parent", "t_1") } returns
                NetworkResult.Success(TaskLinkResponse(ok = true, gated = true))
            coEvery { mockRepository.getTask("t_1", "dev", any(), any()) } returns
                NetworkResult.Success(
                    KanbanTaskDetailResponse(task = KanbanTaskFull(id = "t_1", title = "Child", status = "todo")),
                )

            val vm = createViewModel()
            vm.createParentLink("dev", "t_1", "parent")
            testDispatcher.scheduler.runCurrent()

            assertEquals(
                UiText.res(R.string.kanban_msg_parent_linked_gated),
                vm.uiState.value.toastMessage,
            )
            coVerify { mockRepository.getTask("t_1", "dev", any(), any()) }
        }

    @Test
    fun testDeleteAttachmentReloadsExactTaskAttachmentList() =
        runTest(testDispatcher) {
            val task = KanbanTaskFull(id = "t_1", title = "Task", status = "todo")
            coEvery { mockRepository.getTask("t_1", "dev", any(), any()) } returns
                NetworkResult.Success(
                    KanbanTaskDetailResponse(task = task, attachments = listOf(KanbanAttachment(9L, filename = "old"))),
                )
            coEvery { mockRepository.deleteAttachment(9L, "dev") } returns
                NetworkResult.Success(DeleteAttachmentResponse(ok = true, id = 9L))
            coEvery { mockRepository.listAttachments("t_1", "dev") } returns
                NetworkResult.Success(AttachmentListResponse(emptyList()))

            val vm = createViewModel()
            vm.loadTask("dev", "t_1")
            testDispatcher.scheduler.runCurrent()
            vm.deleteAttachment("dev", "t_1", 9L)
            testDispatcher.scheduler.runCurrent()

            assertTrue(
                vm.uiState.value.detail
                    ?.attachments
                    ?.isEmpty() == true,
            )
            coVerify { mockRepository.listAttachments("t_1", "dev") }
        }

    @Test
    fun testRunFilterPassesPairedBackendParameters() =
        runTest(testDispatcher) {
            val detail = KanbanTaskDetailResponse(task = KanbanTaskFull("t_1", "Task", status = "done"))
            coEvery { mockRepository.getTask("t_1", "dev", any(), any()) } returns NetworkResult.Success(detail)

            val vm = createViewModel()
            vm.loadTask("dev", "t_1")
            testDispatcher.scheduler.runCurrent()
            vm.setRunFilter("dev", "t_1", "status", "reclaimed")
            testDispatcher.scheduler.runCurrent()

            coVerify { mockRepository.getTask("t_1", "dev", "status", "reclaimed") }
            assertEquals("reclaimed", vm.uiState.value.runStateName)
        }

    @Test
    fun testTerminateRefusalPreservesTaskAndReportsReason() =
        runTest(testDispatcher) {
            coEvery { mockRepository.terminateRun(7L, "dev") } returns
                NetworkResult.Failure(NetworkError.Http(409, "run 7 already ended"))
            val vm = createViewModel()
            vm.terminateRun("dev", "t_1", 7L)
            testDispatcher.scheduler.runCurrent()

            assertEquals(UiText.Failure(UiText.raw("run 7 already ended")), vm.uiState.value.toastMessage)
            assertTrue(!vm.uiState.value.isTerminatingRun)
            coVerify(exactly = 0) { mockRepository.getRun(7L, "dev") }
        }

    private fun detailOf(taskId: String): NetworkResult<KanbanTaskDetailResponse> =
        NetworkResult.Success(
            KanbanTaskDetailResponse(task = KanbanTaskFull(id = taskId, title = "T", status = "todo")),
        )

    @Test
    fun testInspectRunForLeftTaskResetsInspectingFlag() =
        runTest(testDispatcher) {
            val runResult = CompletableDeferred<NetworkResult<KanbanRunResponse>>()
            coEvery { mockRepository.getTask(any(), "dev") } answers { detailOf(firstArg()) }
            coEvery { mockRepository.getRun(7L, "dev") } coAnswers { runResult.await() }

            val vm = createViewModel()
            vm.loadTask("dev", "t_1")
            testDispatcher.scheduler.runCurrent()
            vm.inspectRun("dev", "t_1", 7L)
            testDispatcher.scheduler.runCurrent()
            assertTrue(vm.uiState.value.isInspectingRun)

            // The user opens another task while the inspection is in flight.
            vm.loadTask("dev", "t_2")
            testDispatcher.scheduler.runCurrent()
            runResult.complete(NetworkResult.Success(KanbanRunResponse(KanbanRun(id = 7L, status = "done"))))
            testDispatcher.scheduler.runCurrent()

            assertFalse(vm.uiState.value.isInspectingRun)
            assertNull(vm.uiState.value.selectedRun)
            vm.onCleared()
        }

    @Test
    fun testLateLogOfPreviousTaskIsDropped() =
        runTest(testDispatcher) {
            val staleLog = CompletableDeferred<NetworkResult<WorkerLog>>()
            coEvery { mockRepository.getTask(any(), "dev") } answers { detailOf(firstArg()) }
            coEvery { mockRepository.getTaskLog("t_1", "dev", any()) } coAnswers { staleLog.await() }
            coEvery { mockRepository.getTaskLog("t_2", "dev", any()) } returns
                NetworkResult.Success(WorkerLog(taskId = "t_2", content = "log of t_2", exists = true))

            val vm = createViewModel()
            vm.loadTask("dev", "t_1")
            testDispatcher.scheduler.runCurrent()
            vm.loadTask("dev", "t_2")
            testDispatcher.scheduler.runCurrent()
            staleLog.complete(NetworkResult.Success(WorkerLog(taskId = "t_1", content = "log of t_1", exists = true)))
            testDispatcher.scheduler.runCurrent()

            assertEquals(
                "log of t_2",
                vm.uiState.value.workerLog
                    ?.content,
            )
            vm.onCleared()
        }

    @Test
    fun testDeleteTaskDoesNotNavigateBackAfterUserLeft() =
        runTest(testDispatcher) {
            val deleteResult = CompletableDeferred<NetworkResult<Unit>>()
            coEvery { mockRepository.getTask(any(), "dev") } answers { detailOf(firstArg()) }
            coEvery { mockRepository.deleteTask("t_1", "dev") } coAnswers { deleteResult.await() }

            val vm = createViewModel()
            vm.attachScreen()
            vm.loadTask("dev", "t_1")
            testDispatcher.scheduler.runCurrent()
            var backCalls = 0
            vm.deleteTask("dev", "t_1") { backCalls++ }
            testDispatcher.scheduler.runCurrent()

            // The user already went back: the screen left composition before the delete answered.
            vm.detachScreen()
            deleteResult.complete(NetworkResult.Success(Unit))
            testDispatcher.scheduler.runCurrent()

            assertEquals(0, backCalls)
            assertEquals(UiText.res(R.string.kanban_msg_task_deleted), vm.uiState.value.toastMessage)
            vm.onCleared()
        }

    @Test
    fun testDeleteTaskDoesNotNavigateBackFromAnotherTask() =
        runTest(testDispatcher) {
            val deleteResult = CompletableDeferred<NetworkResult<Unit>>()
            coEvery { mockRepository.getTask(any(), "dev") } answers { detailOf(firstArg()) }
            coEvery { mockRepository.deleteTask("t_1", "dev") } coAnswers { deleteResult.await() }

            val vm = createViewModel()
            vm.attachScreen()
            vm.loadTask("dev", "t_1")
            testDispatcher.scheduler.runCurrent()
            var backCalls = 0
            vm.deleteTask("dev", "t_1") { backCalls++ }
            testDispatcher.scheduler.runCurrent()
            vm.loadTask("dev", "t_2")
            testDispatcher.scheduler.runCurrent()
            deleteResult.complete(NetworkResult.Success(Unit))
            testDispatcher.scheduler.runCurrent()

            assertEquals(0, backCalls)
            vm.onCleared()
        }

    @Test
    fun testDetachScreenStopsPollingAndEventsUntilReattached() =
        runTest(testDispatcher) {
            val task = KanbanTaskFull(id = "t_1", title = "Running", status = "running")
            coEvery { mockRepository.getTask("t_1", "dev", any(), any()) } returns
                NetworkResult.Success(KanbanTaskDetailResponse(task = task))
            coEvery { mockRepository.getTaskLog("t_1", "dev", any()) } returns
                NetworkResult.Success(WorkerLog(taskId = "t_1", content = "log", exists = true))

            val vm = createViewModel(enableFallbackPolling = true)
            vm.attachScreen()
            vm.loadTask("dev", "t_1")
            testDispatcher.scheduler.runCurrent()
            verify(exactly = 1) { mockEventsClient.connect(any(), "dev", any(), any(), any()) }

            vm.detachScreen()
            verify(exactly = 1) { mockEventsClient.disconnect() }
            testDispatcher.scheduler.advanceTimeBy(120_000L)
            testDispatcher.scheduler.runCurrent()
            // Only the initial detail fetch and the initial log fetch ran; no poll after leaving the screen.
            coVerify(exactly = 1) { mockRepository.getTask("t_1", "dev", any(), any()) }
            coVerify(exactly = 1) { mockRepository.getTaskLog("t_1", "dev", any()) }

            // A mutation-triggered reload while detached must not restart the socket.
            vm.loadTask("dev", "t_1")
            testDispatcher.scheduler.runCurrent()
            verify(exactly = 1) { mockEventsClient.connect(any(), "dev", any(), any(), any()) }

            // Re-entering the screen restarts live updates.
            vm.attachScreen()
            vm.loadTask("dev", "t_1")
            testDispatcher.scheduler.runCurrent()
            verify(exactly = 2) { mockEventsClient.connect(any(), "dev", any(), any(), any()) }
            vm.onCleared()
        }

    @Test
    fun testOverlappingScreensKeepLiveUpdatesUntilTheLastOneLeaves() =
        runTest(testDispatcher) {
            coEvery { mockRepository.getTask(any(), "dev") } answers { detailOf(firstArg()) }
            val vm = createViewModel()
            // Task A is open, task B is pushed on top; A's entry leaves composition after B entered.
            vm.attachScreen()
            vm.loadTask("dev", "t_a")
            vm.attachScreen()
            vm.loadTask("dev", "t_b")
            testDispatcher.scheduler.runCurrent()
            vm.detachScreen()

            verify(exactly = 0) { mockEventsClient.disconnect() }
            vm.detachScreen()
            verify(exactly = 1) { mockEventsClient.disconnect() }
            vm.onCleared()
        }
}
