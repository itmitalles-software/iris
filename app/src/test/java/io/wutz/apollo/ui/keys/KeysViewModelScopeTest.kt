package io.wutz.apollo.ui.keys

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.model.EnvVarRevealResponse
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.HermesApiService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/** Review U28: key state of one server/profile must not survive a switch to another. */
@OptIn(ExperimentalCoroutinesApi::class)
class KeysViewModelScopeTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockApi = mockk<HermesApiService>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(ApiClient)
        every { ApiClient.hermesApi } returns mockApi
        mockkObject(AuthManager)
        every { AuthManager.currentDataScope() } returns null
        coEvery { mockApi.getEnvVars() } returns Response.success(emptyMap())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(): KeysViewModel = KeysViewModel().also { it.ioDispatcher = testDispatcher }

    private fun settle() {
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private fun deleteKey(vm: KeysViewModel) {
        vm.requestDeleteKey("OPENAI_API_KEY")
        vm.confirmDeleteKey()
        settle()
    }

    @Test
    fun `server switch clears the restart gateway banner`() {
        coEvery { mockApi.deleteEnvVar(any()) } returns Response.success(Unit)
        val vm = createViewModel()
        deleteKey(vm)
        assertTrue(vm.uiState.value.keysChanged)

        vm.clearScopeOwnedState()

        assertFalse(vm.uiState.value.keysChanged)
    }

    @Test
    fun `key change finishing after a server switch does not mark the new server as changed`() {
        val gate = CompletableDeferred<Unit>()
        coEvery { mockApi.deleteEnvVar(any()) } coAnswers {
            gate.await()
            Response.success(Unit)
        }
        val vm = createViewModel()
        deleteKey(vm)

        vm.clearScopeOwnedState()
        gate.complete(Unit)
        settle()

        assertFalse(vm.uiState.value.keysChanged)
        assertTrue(
            vm.uiState.value.deletingKeys
                .isEmpty(),
        )
    }

    @Test
    fun `value revealed by the previous server is not shown after a switch`() {
        val gate = CompletableDeferred<Unit>()
        coEvery { mockApi.revealEnvVar(any()) } coAnswers {
            gate.await()
            Response.success(EnvVarRevealResponse(key = "OPENAI_API_KEY", value = "old-server-secret"))
        }
        val vm = createViewModel()
        vm.revealKey("OPENAI_API_KEY")
        settle()

        vm.clearScopeOwnedState()
        gate.complete(Unit)
        settle()

        assertTrue(
            vm.uiState.value.revealedValues
                .isEmpty(),
        )
    }
}
