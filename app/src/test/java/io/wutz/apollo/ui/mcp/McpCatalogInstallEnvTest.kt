package io.wutz.apollo.ui.mcp

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.wutz.apollo.data.model.ActionResponse
import io.wutz.apollo.data.model.McpCatalogEntry
import io.wutz.apollo.data.model.McpCatalogEnvVar
import io.wutz.apollo.data.model.McpCatalogInstallRequest
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.HermesApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/** Env values typed for one catalog entry must never be sent with another entry. */
@OptIn(ExperimentalCoroutinesApi::class)
class McpCatalogInstallEnvTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApi: HermesApiService
    private val sent = slot<McpCatalogInstallRequest>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockApi = mockk()
        mockkObject(ApiClient)
        every { ApiClient.hermesApi } returns mockApi
        // A 4xx keeps the test on the failure path (no retries, no follow-up reloads).
        coEvery { mockApi.installMcpCatalogEntry(capture(sent)) } returns
            Response.error<ActionResponse>(400, "{}".toResponseBody())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun installSendsOnlyTheEntrysDeclaredVariables() =
        TestScope(testDispatcher).runTest {
            val vm = McpServersViewModel(ioDispatcher = testDispatcher)
            vm.updateCatalogEnvVar("GITHUB_PERSONAL_ACCESS_TOKEN", "ghp_secret")
            vm.updateCatalogEnvVar("BRAVE_API_KEY", "brave-key")

            vm.installCatalogEntry(
                McpCatalogEntry(name = "brave", env = listOf(McpCatalogEnvVar(key = "BRAVE_API_KEY"))),
            )
            advanceUntilIdle()
            assertEquals(mapOf("BRAVE_API_KEY" to "brave-key"), sent.captured.env)

            vm.installCatalogEntry(McpCatalogEntry(name = "time"))
            advanceUntilIdle()
            assertNull(sent.captured.env)
        }

    @Test
    fun scopeChangeDropsTypedValues() {
        val vm = McpServersViewModel(ioDispatcher = testDispatcher)
        vm.updateCatalogEnvVar("GITHUB_PERSONAL_ACCESS_TOKEN", "ghp_secret")

        vm.clearScopeOwnedState()

        assertEquals(emptyMap<String, String>(), vm.uiState.value.catalogInstallEnv)
    }
}
