package io.wutz.apollo.ui.kanban

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import io.wutz.apollo.R
import io.wutz.apollo.data.model.KanbanBoard
import io.wutz.apollo.data.model.KanbanBoardResponse
import io.wutz.apollo.data.model.KanbanBoardsResponse
import io.wutz.apollo.data.model.KanbanColumn
import io.wutz.apollo.data.model.KanbanTask
import io.wutz.apollo.data.model.UpdateTaskResponse
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.KanbanApiService
import io.wutz.apollo.data.repository.KanbanDispatcherNudger
import io.wutz.apollo.data.repository.KanbanRepositoryImpl
import io.wutz.apollo.data.ws.KanbanEvent
import io.wutz.apollo.data.ws.KanbanEventsClient
import io.wutz.apollo.data.ws.KanbanEventsEnvelope
import io.wutz.apollo.data.ws.KanbanLiveStatus
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class KanbanViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockApi = mockk<KanbanApiService>(relaxed = true)
    private val mockEventsClient = mockk<KanbanEventsClient>(relaxed = true)

    private fun createViewModel(): KanbanViewModel {
        val mockNudger = mockk<KanbanDispatcherNudger>(relaxed = true)
        val repo =
            KanbanRepositoryImpl(
                apiProvider = { mockApi },
                ioDispatcher = testDispatcher,
                nudger = mockNudger,
            )
        val vm =
            KanbanViewModel(
                repository = repo,
                eventsClientProvider = { mockEventsClient },
            )
        vm.ioDispatcher = testDispatcher
        testDispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    private fun settle() {
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private fun stubBoard(tasks: List<KanbanTask> = listOf(KanbanTask(id = "t1", title = "Task 1", status = "todo"))) {
        coEvery { mockApi.getBoards(any()) } returns
            Response.success(
                KanbanBoardsResponse(
                    boards = listOf(KanbanBoard(id = "work", name = "Work")),
                    current = "work",
                ),
            )
        coEvery { mockApi.getBoard(board = "work", any(), any()) } returns
            Response.success(KanbanBoardResponse(columns = listOf(KanbanColumn(name = "todo", tasks = tasks))))
        coEvery { mockApi.getBoard(board = "ops", any(), any()) } returns
            Response.success(KanbanBoardResponse(columns = listOf(KanbanColumn(name = "todo", tasks = emptyList()))))
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(ApiClient)
        every { ApiClient.kanbanApi } returns mockApi
        stubBoard()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `loadBoards connects events stream for current board`() {
        val vm = createViewModel()
        vm.loadBoards()
        settle()
        verify { mockEventsClient.connect(any(), eq("work"), any(), any(), any()) }
    }

    @Test
    fun `connected status flips isLive`() {
        val vm = createViewModel()
        vm.loadBoards()
        settle()
        val statusSlot = slot<(KanbanLiveStatus) -> Unit>()
        verify { mockEventsClient.connect(any(), any(), any(), any(), capture(statusSlot)) }
        statusSlot.captured(KanbanLiveStatus.CONNECTED)
        assertTrue(vm.uiState.value.isLive)
        statusSlot.captured(KanbanLiveStatus.DISCONNECTED)
        assertFalse(vm.uiState.value.isLive)
    }

    @Test
    fun `board unavailable close moves selection to an available board`() {
        val vm = createViewModel()
        vm.loadBoards()
        settle()
        val statusSlot = slot<(KanbanLiveStatus) -> Unit>()
        verify { mockEventsClient.connect(any(), eq("work"), any(), any(), capture(statusSlot)) }
        assertEquals(
            "work",
            vm.uiState.value.selectedBoard
                ?.id,
        )

        coEvery { mockApi.getBoards(any()) } returns
            Response.success(
                KanbanBoardsResponse(boards = listOf(KanbanBoard(id = "ops", name = "Ops")), current = "ops"),
            )
        statusSlot.captured(KanbanLiveStatus.BOARD_UNAVAILABLE)
        settle()

        assertEquals(
            "ops",
            vm.uiState.value.selectedBoard
                ?.id,
        )
        assertEquals(
            listOf("ops"),
            vm.uiState.value.boards
                .map { it.id },
        )
        assertEquals(UiText.res(R.string.kanban_msg_board_unavailable, "Work"), vm.uiState.value.toastMessage)
        verify { mockEventsClient.connect(any(), eq("ops"), any(), any(), any()) }
    }

    @Test
    fun `board unavailable close keeps a board that is still listed`() {
        val vm = createViewModel()
        vm.loadBoards()
        settle()
        val statusSlot = slot<(KanbanLiveStatus) -> Unit>()
        verify { mockEventsClient.connect(any(), eq("work"), any(), any(), capture(statusSlot)) }

        statusSlot.captured(KanbanLiveStatus.BOARD_UNAVAILABLE)
        settle()

        assertEquals(
            "work",
            vm.uiState.value.selectedBoard
                ?.id,
        )
        assertNull(vm.uiState.value.toastMessage)
        assertFalse(vm.uiState.value.isLive)
    }

    @Test
    fun `board unavailable close with no boards left clears the selection`() {
        val vm = createViewModel()
        vm.loadBoards()
        settle()
        val statusSlot = slot<(KanbanLiveStatus) -> Unit>()
        verify { mockEventsClient.connect(any(), eq("work"), any(), any(), capture(statusSlot)) }

        coEvery { mockApi.getBoards(any()) } returns
            Response.success(KanbanBoardsResponse(boards = emptyList(), current = null))
        statusSlot.captured(KanbanLiveStatus.BOARD_UNAVAILABLE)
        settle()

        assertNull(vm.uiState.value.selectedBoard)
        assertTrue(
            vm.uiState.value.boards
                .isEmpty(),
        )
    }

    @Test
    fun `events batch triggers debounced board reload`() {
        val vm = createViewModel()
        vm.loadBoards()
        settle()
        val eventsSlot = slot<(KanbanEventsEnvelope) -> Unit>()
        verify { mockEventsClient.connect(any(), any(), any(), capture(eventsSlot), any()) }

        coEvery { mockApi.getBoard(board = "work", any(), any()) } returns
            Response.success(
                KanbanBoardResponse(
                    columns =
                        listOf(
                            KanbanColumn(
                                name = "todo",
                                tasks =
                                    listOf(
                                        KanbanTask(id = "t1", title = "Task 1", status = "todo"),
                                        KanbanTask(id = "t2", title = "Task 2", status = "todo"),
                                    ),
                            ),
                        ),
                ),
            )

        eventsSlot.captured(KanbanEventsEnvelope(events = listOf(KanbanEvent(id = 1, kind = "created")), cursor = 1))
        testDispatcher.scheduler.advanceTimeBy(249)
        assertEquals(1, vm.uiState.value.tasks.size)
        testDispatcher.scheduler.advanceTimeBy(1)
        settle()
        assertEquals(2, vm.uiState.value.tasks.size)
        coVerify(atLeast = 2) { mockApi.getBoard(board = "work", any(), any()) }
    }

    @Test
    fun `board switch reconnects events stream with new board`() {
        val vm = createViewModel()
        vm.loadBoards()
        settle()
        vm.selectBoard(KanbanBoard(id = "ops", name = "Ops"))
        settle()
        verify { mockEventsClient.connect(any(), eq("ops"), any(), any(), any()) }
    }

    @Test
    fun `re-selecting already-live board does not reconnect`() {
        val vm = createViewModel()
        vm.loadBoards()
        settle()
        val statusSlot = slot<(KanbanLiveStatus) -> Unit>()
        verify { mockEventsClient.connect(any(), any(), any(), any(), capture(statusSlot)) }
        statusSlot.captured(KanbanLiveStatus.CONNECTED)

        vm.selectBoard(KanbanBoard(id = "work", name = "Work"))
        settle()
        verify(exactly = 1) { mockEventsClient.connect(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `onCleared disconnects events stream`() {
        val vm = createViewModel()
        vm.loadBoards()
        settle()
        val method = KanbanViewModel::class.java.getDeclaredMethod("onCleared")
        method.isAccessible = true
        method.invoke(vm)
        verify { mockEventsClient.disconnect() }
    }

    @Test
    fun `kanbanActionsForStatus gates transitions like the desktop`() {
        assertEquals(
            listOf(KanbanTaskAction.TRIAGE, KanbanTaskAction.READY, KanbanTaskAction.ARCHIVE),
            kanbanActionsForStatus("todo"),
        )
        assertEquals(
            listOf(
                KanbanTaskAction.TRIAGE,
                KanbanTaskAction.UNBLOCK,
                KanbanTaskAction.COMPLETE,
                KanbanTaskAction.ARCHIVE,
            ),
            kanbanActionsForStatus("blocked"),
        )
        assertEquals(
            listOf(
                KanbanTaskAction.TRIAGE,
                KanbanTaskAction.READY,
                KanbanTaskAction.BLOCK,
                KanbanTaskAction.COMPLETE,
                KanbanTaskAction.ARCHIVE,
            ),
            kanbanActionsForStatus("running"),
        )
        assertEquals(
            listOf(KanbanTaskAction.TRIAGE, KanbanTaskAction.READY, KanbanTaskAction.ARCHIVE),
            kanbanActionsForStatus("done"),
        )
        // Backend-rejected targets are never offered.
        assertFalse(kanbanActionsForStatus("todo").any { it.targetStatus == "review" })
        assertFalse(kanbanActionsForStatus("todo").any { it.targetStatus == "running" })
        assertFalse(kanbanActionsForStatus("ready").any { it.targetStatus == "running" })
    }

    @Test
    fun `moveTask updates status and PATCHes the target`() {
        val vm = createViewModel()
        vm.loadBoards()
        settle()
        coEvery { mockApi.getBoard(board = "work", any(), any()) } returns
            Response.success(
                KanbanBoardResponse(
                    columns =
                        listOf(
                            KanbanColumn(name = "todo", tasks = emptyList()),
                            KanbanColumn(
                                name = "ready",
                                tasks = listOf(KanbanTask(id = "t1", title = "Task 1", status = "ready")),
                            ),
                        ),
                ),
            )
        coEvery { mockApi.updateTask(any(), any(), any()) } returns Response.success(UpdateTaskResponse())
        vm.moveTask(KanbanTask(id = "t1", title = "Task 1", status = "todo"), KanbanTaskAction.READY)
        settle()
        assertEquals(
            "ready",
            vm.uiState.value.tasks
                .first { it.id == "t1" }
                .status,
        )
        coVerify { mockApi.updateTask("t1", "work", match { it.status == "ready" }) }
    }

    @Test
    fun `moveTask failure reverts status and shows toast`() {
        val vm = createViewModel()
        vm.loadBoards()
        settle()
        coEvery { mockApi.updateTask(any(), any(), any()) } returns Response.error(409, "".toResponseBody())
        vm.moveTask(KanbanTask(id = "t1", title = "Task 1", status = "todo"), KanbanTaskAction.READY)
        settle()
        assertEquals(
            "todo",
            vm.uiState.value.tasks
                .first { it.id == "t1" }
                .status,
        )
        assertEquals(
            UiText.res(
                R.string.kanban_msg_move_failed,
                "Conflict (HTTP 409): The request conflicts with current server state.",
            ),
            vm.uiState.value.toastMessage,
        )
    }

    @Test
    fun `moveTask dependency conflict surfaces blocking parent ids`() {
        val vm = createViewModel()
        vm.loadBoards()
        settle()
        coEvery { mockApi.updateTask(any(), any(), any()) } returns
            Response.error(
                409,
                """{"detail":"Cannot move to 'ready': blocked by parent(s) not done — 'Build' (t_parent, status=running)"}"""
                    .toResponseBody(),
            )
        vm.moveTask(KanbanTask(id = "t1", title = "Task 1", status = "todo"), KanbanTaskAction.READY)
        settle()
        assertEquals(
            "todo",
            vm.uiState.value.tasks
                .single()
                .status,
        )
        assertEquals(
            UiText.res(
                R.string.kanban_msg_move_blocked_by_parents,
                "t_parent",
                "running",
                "Cannot move to 'ready': blocked by parent(s) not done — 'Build' (t_parent, status=running)",
            ),
            vm.uiState.value.toastMessage,
        )
    }

    @Test
    fun `workflow filters retain only matching tasks`() {
        val tasks =
            listOf(
                KanbanTask("a", "A", status = "todo", workflowTemplateId = "wf", currentStepKey = "build"),
                KanbanTask("b", "B", status = "todo", workflowTemplateId = "other", currentStepKey = "test"),
            )
        assertEquals(listOf("a"), filterKanbanTasks(tasks, "wf", "build").map { it.id })
        assertEquals(listOf("a"), filterKanbanTasks(tasks, "wf", null).map { it.id })
    }

    @Test
    fun `active workers are loaded for selected board`() {
        val vm = createViewModel()
        vm.loadBoards()
        settle()
        coEvery { mockApi.getActiveWorkers("work") } returns
            Response.success(
                io.wutz.apollo.data.model.ActiveWorkersResponse(
                    workers =
                        listOf(
                            io.wutz.apollo.data.model.KanbanActiveWorker(
                                runId = 1,
                                taskId = "t1",
                                taskTitle = "Task 1",
                                taskStatus = "running",
                                workerPid = 42,
                            ),
                        ),
                    checkedAt = 1,
                ),
            )
        vm.loadActiveWorkers()
        settle()
        assertEquals(1, vm.uiState.value.activeWorkers.size)
        assertEquals(
            "t1",
            vm.uiState.value.activeWorkers
                .single()
                .taskId,
        )
        vm.selectBoard(KanbanBoard(id = "ops", name = "Ops"))
        settle()
        assertTrue(
            vm.uiState.value.activeWorkers
                .isEmpty(),
        )
    }

    @Test
    fun `successful move refreshes board to reconcile done ordering`() {
        coEvery { mockApi.getBoard(board = "work", any(), any()) } returns
            Response.success(
                KanbanBoardResponse(
                    columns =
                        listOf(
                            KanbanColumn(
                                name = "todo",
                                tasks = listOf(KanbanTask(id = "t1", title = "Task 1", status = "todo")),
                            ),
                            KanbanColumn(
                                name = "done",
                                tasks = listOf(KanbanTask(id = "older", title = "Older", status = "done")),
                            ),
                        ),
                ),
            )
        val vm = createViewModel()
        vm.loadBoards()
        settle()
        coEvery { mockApi.getBoard(board = "work", any(), any()) } returns
            Response.success(
                KanbanBoardResponse(
                    columns =
                        listOf(
                            KanbanColumn(name = "todo", tasks = emptyList()),
                            KanbanColumn(
                                name = "done",
                                tasks =
                                    listOf(
                                        KanbanTask(id = "t1", title = "Task 1", status = "done"),
                                        KanbanTask(id = "older", title = "Older", status = "done"),
                                    ),
                            ),
                        ),
                ),
            )
        coEvery { mockApi.updateTask(any(), any(), any()) } returns Response.success(UpdateTaskResponse())
        vm.moveTask(KanbanTask("t1", "Task 1", status = "todo"), KanbanTaskAction.COMPLETE, "done")
        settle()
        coVerify(atLeast = 2) { mockApi.getBoard(board = "work", any(), any()) }
        assertEquals(
            listOf("t1", "older"),
            vm.uiState.value.columns
                .single { it.name == "done" }
                .tasks
                .map { it.id },
        )
    }

    @Test
    fun `moveTask complete sends the completion summary`() {
        val vm = createViewModel()
        vm.loadBoards()
        settle()
        coEvery { mockApi.updateTask(any(), any(), any()) } returns Response.success(UpdateTaskResponse())
        vm.moveTask(
            KanbanTask(id = "t1", title = "Task 1", status = "ready"),
            KanbanTaskAction.COMPLETE,
            summary = "Shipped it",
        )
        settle()
        coVerify {
            mockApi.updateTask(
                "t1",
                "work",
                match { it.status == "done" && it.summary == "Shipped it" && it.result == "Shipped it" },
            )
        }
    }
}
