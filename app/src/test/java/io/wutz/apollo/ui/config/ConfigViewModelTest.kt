package io.wutz.apollo.ui.config

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.model.ConfigUpdateRequest
import io.wutz.apollo.data.model.RawConfigResponse
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.HermesApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/** Review U10: a reload must never hide unsaved edits that the next Save still sends. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockApi = mockk<HermesApiService>(relaxed = true)

    private fun config(vararg sections: Pair<String, Map<String, String>>): Map<String, JsonElement> =
        sections.associate { (section, values) ->
            section to JsonObject(values.mapValues { JsonPrimitive(it.value) })
        }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(ApiClient)
        every { ApiClient.hermesApi } returns mockApi
        mockkObject(AuthManager)
        every { AuthManager.currentDataScope() } returns null
        coEvery { mockApi.getConfigSchema() } returns Response.error(404, "{}".toResponseBody(null))
        coEvery { mockApi.getConfigDefaults() } returns Response.error(404, "{}".toResponseBody(null))
        coEvery { mockApi.getRawConfig() } returns Response.success(RawConfigResponse(path = "/home/h/config.yaml"))
        coEvery { mockApi.updateConfig(any()) } returns Response.success(Unit)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun settle() {
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `reload keeps pending edits visible on top of fresh server values`() {
        coEvery { mockApi.getConfig() } returnsMany
            listOf(
                Response.success(config("agent" to mapOf("model" to "a"))),
                Response.success(config("agent" to mapOf("model" to "b"), "display" to mapOf("theme" to "dark"))),
            )
        val vm = ConfigViewModel(ioDispatcher = testDispatcher)
        settle()

        vm.updateField("agent.model", JsonPrimitive("edited"))
        vm.loadAll(forceRefresh = true)
        settle()

        val state = vm.uiState.value
        assertEquals(JsonPrimitive("edited"), state.values?.get("agent.model"))
        assertEquals(JsonPrimitive("dark"), state.values?.get("display.theme"))
        assertEquals(setOf("agent.model"), state.modifiedKeys)

        vm.saveConfig()
        settle()
        coVerify(exactly = 1) {
            mockApi.updateConfig(
                ConfigUpdateRequest(config = mapOf("agent" to JsonObject(mapOf("model" to JsonPrimitive("edited"))))),
            )
        }
    }

    @Test
    fun `reload without pending edits shows the server values`() {
        coEvery { mockApi.getConfig() } returnsMany
            listOf(
                Response.success(config("agent" to mapOf("model" to "a"))),
                Response.success(config("agent" to mapOf("model" to "b"))),
            )
        val vm = ConfigViewModel(ioDispatcher = testDispatcher)
        settle()

        vm.loadAll(forceRefresh = true)
        settle()

        assertEquals(
            JsonPrimitive("b"),
            vm.uiState.value.values
                ?.get("agent.model"),
        )
        assertEquals(emptySet<String>(), vm.uiState.value.modifiedKeys)
    }
}
