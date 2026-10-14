package io.wutz.apollo.ui.providers

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.model.OAuthProvider
import io.wutz.apollo.data.model.OAuthStartResponse
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.HermesApiService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/** Review U13: the `/oauth/{provider}/start` request belongs to the flow that issued it. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProvidersViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockApi = mockk<HermesApiService>(relaxed = true)
    private val provider = OAuthProvider(id = "openai-codex", name = "OpenAI Codex", flow = "device_code")
    private val deviceStart =
        OAuthStartResponse(sessionId = "s1", flow = "device_code", userCode = "ABCD", pollInterval = 1)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(ApiClient)
        every { ApiClient.hermesApi } returns mockApi
        mockkObject(AuthManager)
        every { AuthManager.currentDataScope() } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun gatedStart(): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        coEvery { mockApi.startOAuthLogin(provider.id) } coAnswers {
            gate.await()
            Response.success(deviceStart)
        }
        return gate
    }

    @Test
    fun `cancelling during STARTING ignores the late start response and never polls`() {
        val gate = gatedStart()
        val vm = ProvidersViewModel()

        vm.startOAuthFlow(provider)
        testDispatcher.scheduler.runCurrent()
        assertEquals(OAuthFlowPhase.STARTING, vm.uiState.value.flowPhase)

        vm.cancelOAuthFlow()
        gate.complete(Unit)
        testDispatcher.scheduler.runCurrent()
        testDispatcher.scheduler.advanceTimeBy(5_000)
        testDispatcher.scheduler.runCurrent()

        assertEquals(OAuthFlowPhase.IDLE, vm.uiState.value.flowPhase)
        assertNull(vm.uiState.value.flowStart)
        coVerify(exactly = 0) { mockApi.pollOAuthSession(any(), any()) }
    }

    @Test
    fun `dismissing during STARTING ignores the late start response`() {
        val gate = gatedStart()
        val vm = ProvidersViewModel()

        vm.startOAuthFlow(provider)
        testDispatcher.scheduler.runCurrent()
        vm.dismissFlow()
        gate.complete(Unit)
        testDispatcher.scheduler.runCurrent()

        assertEquals(OAuthFlowPhase.IDLE, vm.uiState.value.flowPhase)
        assertEquals("", vm.uiState.value.flowSessionId)
    }

    @Test
    fun `start response of the current flow begins polling`() {
        val gate = gatedStart()
        val vm = ProvidersViewModel()

        vm.startOAuthFlow(provider)
        testDispatcher.scheduler.runCurrent()
        gate.complete(Unit)
        testDispatcher.scheduler.runCurrent()

        assertEquals(OAuthFlowPhase.POLLING, vm.uiState.value.flowPhase)
        assertEquals("s1", vm.uiState.value.flowSessionId)
        vm.cancelOAuthFlow()
        testDispatcher.scheduler.runCurrent()
    }
}
