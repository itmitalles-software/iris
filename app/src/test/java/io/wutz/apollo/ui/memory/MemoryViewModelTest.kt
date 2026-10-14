package io.wutz.apollo.ui.memory

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.wutz.apollo.R
import io.wutz.apollo.data.model.LearningGraphNode
import io.wutz.apollo.data.model.LearningNodeDetail
import io.wutz.apollo.data.model.LearningNodeEdit
import io.wutz.apollo.data.model.LearningNodeMutationResponse
import io.wutz.apollo.data.model.LearningNodeRef
import io.wutz.apollo.data.model.MemoryProviderStatusRow
import io.wutz.apollo.data.model.MemoryResetRequest
import io.wutz.apollo.data.model.MemoryResetResponse
import io.wutz.apollo.data.model.MemoryResponse
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.HermesApiService
import io.wutz.apollo.data.session.ProfileSwitchCoordinator
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

/**
 * Memory management home (moved out of the System tab). No
 * mockkStatic(Dispatchers) — the ViewModel does no explicit IO hop, so
 * setMain(StandardTestDispatcher) alone is deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoryViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApi: HermesApiService
    private val mockSwitchFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val mockConnSwitchFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)

    private val memoryResponse =
        MemoryResponse(
            active = "web",
            builtin_files =
                io.wutz.apollo.data.model.BuiltinFileSizes(
                    memory = 1024,
                    user = 2048,
                ),
            providers =
                listOf(
                    MemoryProviderStatusRow(name = "web", status = "ready"),
                    MemoryProviderStatusRow(name = "tts", status = "needs_config"),
                ),
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(ApiClient)
        mockkObject(ProfileSwitchCoordinator)
        every { ProfileSwitchCoordinator.switched } returns mockSwitchFlow
        every { ProfileSwitchCoordinator.connectionSwitched } returns mockConnSwitchFlow
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi
        coEvery { mockApi.getMemory() } returns Response.success(memoryResponse)
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun `load fetches memory status with providers`() {
        val vm = MemoryViewModel()
        vm.load()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("web", state.memory?.active)
        assertEquals(2, state.memory?.providers?.size)
        assertEquals(1024L, state.memory?.builtin_files?.memory)
    }

    @Test
    fun `profile switch triggers silent reload`() {
        val vm = MemoryViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 0) { mockApi.getMemory() }

        mockSwitchFlow.tryEmit("yasmin")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { mockApi.getMemory() }
    }

    @Test
    fun `connection profile switch triggers silent reload`() {
        val vm = MemoryViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 0) { mockApi.getMemory() }

        mockConnSwitchFlow.tryEmit("server-remote")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { mockApi.getMemory() }
    }

    @Test
    fun `load also fetches learning graph for self-improvement section`() {
        val graph =
            io.wutz.apollo.data.model
                .LearningGraphResponse(nodes = emptyList())
        coEvery { mockApi.getLearningGraph() } returns Response.success(graph)
        val vm = MemoryViewModel()
        vm.load()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { mockApi.getLearningGraph() }
        assertEquals(graph, vm.uiState.value.learningGraph)
    }

    @Test
    fun `load failure surfaces error message`() {
        coEvery { mockApi.getMemory() } returns
            Response.error(500, "boom".toResponseBody())
        val vm = MemoryViewModel()
        vm.load()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.memory)
        val error = vm.uiState.value.errorMessage as UiText.Raw
        assertTrue(error.text.isNotBlank())
    }

    @Test
    fun `resetMemory posts target and reloads`() {
        coEvery { mockApi.resetMemory(MemoryResetRequest(target = "all")) } returns
            Response.success(MemoryResetResponse(ok = true))
        val vm = MemoryViewModel()
        vm.load()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.resetMemory("all")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { mockApi.resetMemory(MemoryResetRequest(target = "all")) }
        assertEquals(UiText.res(R.string.memory_msg_reset_done, "all"), vm.uiState.value.toastMessage)
        // One load + one reload after reset.
        coVerify(exactly = 2) { mockApi.getMemory() }
    }

    @Test
    fun `resetMemory failure shows error toast`() {
        coEvery { mockApi.resetMemory(MemoryResetRequest(target = "memory")) } returns
            Response.error(500, "boom".toResponseBody())
        val vm = MemoryViewModel()
        vm.resetMemory("memory")
        testDispatcher.scheduler.advanceUntilIdle()

        val toast = vm.uiState.value.toastMessage as UiText.Resource
        assertEquals(R.string.memory_msg_reset_failed, toast.id)
        assertTrue((toast.args.single() as String).isNotBlank())
        assertNull(vm.uiState.value.resetting)
    }

    private val memoryNode =
        LearningGraphNode(id = "memory:memory:0:abc", label = "Prefers dark mode", kind = "memory")
    private val skillNode = LearningGraphNode(id = "deploy-notes", label = "deploy-notes", kind = "skill")

    private fun openedVm(node: LearningGraphNode): MemoryViewModel {
        coEvery { mockApi.getLearningNode(node.id) } returns
            Response.success(LearningNodeDetail(ok = true, kind = node.kind, id = node.id, content = "old text"))
        val vm = MemoryViewModel()
        vm.openNode(node)
        testDispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    @Test
    fun `openNode loads the node content for editing`() {
        val vm = openedVm(memoryNode)

        val node = vm.uiState.value.node!!
        assertEquals("memory:memory:0:abc", node.id)
        assertEquals("old text", node.detail?.content)
        assertFalse(node.isLoading)
        vm.closeNode()
        assertNull(vm.uiState.value.node)
    }

    @Test
    fun `openNode failure keeps the sheet open with an error`() {
        coEvery { mockApi.getLearningNode(any()) } returns Response.error(404, "{}".toResponseBody())
        val vm = MemoryViewModel()
        vm.openNode(memoryNode)
        testDispatcher.scheduler.advanceUntilIdle()

        val node = vm.uiState.value.node!!
        assertNull(node.detail)
        assertEquals(R.string.memory_node_load_failed, (node.error as UiText.Resource).id)
    }

    @Test
    fun `saveNode rewrites the node and reloads the graph`() {
        coEvery { mockApi.updateLearningNode(any()) } returns
            Response.success(LearningNodeMutationResponse(ok = true, message = "updated"))
        val vm = openedVm(memoryNode)

        vm.saveNode("new text")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { mockApi.updateLearningNode(LearningNodeEdit(id = memoryNode.id, content = "new text")) }
        assertNull(vm.uiState.value.node)
        assertEquals(UiText.res(R.string.memory_node_saved, "Prefers dark mode"), vm.uiState.value.toastMessage)
        // Memory ids carry a fingerprint of the old text: the graph must be refetched.
        coVerify(atLeast = 1) { mockApi.getLearningGraph() }
    }

    @Test
    fun `saveNode refuses blank content without calling the server`() {
        val vm = openedVm(memoryNode)

        vm.saveNode("   ")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { mockApi.updateLearningNode(any()) }
        assertEquals(
            UiText.res(R.string.memory_node_empty_content),
            vm.uiState.value.node
                ?.error,
        )
    }

    @Test
    fun `saveNode failure keeps the draft sheet open with the server message`() {
        coEvery { mockApi.updateLearningNode(any()) } returns
            Response.error(400, """{"detail":"Replacement would put memory over the limit"}""".toResponseBody())
        val vm = openedVm(memoryNode)

        vm.saveNode("new text")
        testDispatcher.scheduler.advanceUntilIdle()

        val node = vm.uiState.value.node!!
        assertFalse(node.isSaving)
        assertEquals(R.string.memory_node_save_failed, (node.error as UiText.Resource).id)
    }

    @Test
    fun `forgetNode archives a skill and says so`() {
        coEvery { mockApi.deleteLearningNode(any()) } returns
            Response.success(LearningNodeMutationResponse(ok = true))
        val vm = openedVm(skillNode)

        vm.forgetNode()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { mockApi.deleteLearningNode(LearningNodeRef(id = "deploy-notes")) }
        assertNull(vm.uiState.value.node)
        assertEquals(UiText.res(R.string.memory_node_forgot_skill, "deploy-notes"), vm.uiState.value.toastMessage)
    }

    @Test
    fun `forgetNode removes a memory entry`() {
        coEvery { mockApi.deleteLearningNode(any()) } returns
            Response.success(LearningNodeMutationResponse(ok = true))
        val vm = openedVm(memoryNode)

        vm.forgetNode()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { mockApi.deleteLearningNode(LearningNodeRef(id = memoryNode.id)) }
        assertEquals(UiText.res(R.string.memory_node_forgot_memory), vm.uiState.value.toastMessage)
    }

    @Test
    fun `a scope switch closes an open node`() {
        val vm = openedVm(memoryNode)

        mockSwitchFlow.tryEmit("other")
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.node)
    }
}
