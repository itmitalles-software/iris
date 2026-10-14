package io.wutz.apollo.ui.mcp

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.wutz.apollo.R
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.model.McpServer
import io.wutz.apollo.data.model.McpServersResponse
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.HermesApiService
import io.wutz.apollo.data.remote.mapHttpError
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/** Loading, validation and delete paths; ownership, 409 and edit-context races are in [McpOwnershipTest]. */
@OptIn(ExperimentalCoroutinesApi::class)
class McpServersViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = mockk<HermesApiService>()
    private val server = McpServer(name = "files", enabled = true, source = "config")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(ApiClient, AuthManager)
        every { ApiClient.hermesApi } returns api
        every { AuthManager.activeProfileId } returns MutableStateFlow("work")
        every { AuthManager.currentDataScope() } returns null
        coEvery { api.getMcpServers() } returns Response.success(McpServersResponse(listOf(server)))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun loaded(): McpServersViewModel =
        McpServersViewModel(ioDispatcher = dispatcher).also {
            it.loadServers()
            dispatcher.scheduler.advanceUntilIdle()
        }

    @Test
    fun `load lists the servers`() {
        val state = loaded().uiState.value

        assertFalse(state.isLoading)
        assertEquals(listOf(server), state.servers)
        assertNull(state.errorMessage)
    }

    @Test
    fun `load failure shows a localized error`() {
        coEvery { api.getMcpServers() } returns Response.error(403, "".toResponseBody())

        val state = loaded().uiState.value

        assertFalse(state.isLoading)
        assertEquals(UiText.res(R.string.mcp_msg_load_failed, mapHttpError(403).message), state.errorMessage)
    }

    @Test
    fun `an environment variable needs a key before anything is sent`() {
        val vm = loaded()
        vm.startEditingEnv(server)
        vm.updateEnvKey("  ")
        vm.updateEnvValue("secret")
        vm.addEnvVar(server.name)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(UiText.res(R.string.mcp_msg_env_key_required), vm.uiState.value.toastMessage)
        coVerify(exactly = 0) { api.getSavedConfig(any(), any()) }
        coVerify(exactly = 0) { api.replaceMcpServers(any()) }
    }

    @Test
    fun `deleting a server confirms and reloads the list`() {
        coEvery { api.deleteMcpServer(server.name) } returns Response.success(Unit)
        val vm = loaded()
        coEvery { api.getMcpServers() } returns Response.success(McpServersResponse(emptyList()))

        vm.deleteServer(server.name)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(UiText.res(R.string.mcp_msg_server_deleted, server.name), vm.uiState.value.toastMessage)
        assertEquals(emptyList<McpServer>(), vm.uiState.value.servers)
    }

    @Test
    fun `a failed delete keeps the server and reports the error`() {
        coEvery { api.deleteMcpServer(server.name) } returns Response.error(500, "".toResponseBody())
        val vm = loaded()

        vm.deleteServer(server.name)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            UiText.res(R.string.mcp_msg_delete_failed, mapHttpError(500).message),
            vm.uiState.value.toastMessage,
        )
        assertEquals(listOf(server), vm.uiState.value.servers)
    }
}
