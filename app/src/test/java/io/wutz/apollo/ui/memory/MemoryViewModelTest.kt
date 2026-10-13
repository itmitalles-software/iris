package io.wutz.apollo.ui.memory

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.wutz.apollo.R
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
}
