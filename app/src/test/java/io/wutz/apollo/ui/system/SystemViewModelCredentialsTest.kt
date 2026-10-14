package io.wutz.apollo.ui.system

import android.app.Application
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.wutz.apollo.data.model.ActionResponse
import io.wutz.apollo.data.model.CredentialPoolEntry
import io.wutz.apollo.data.model.CredentialPoolProvider
import io.wutz.apollo.data.model.CredentialPoolResponse
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.util.concurrent.atomic.AtomicInteger

/**
 * Review U12: the server removes pool entries by their 1-based list position only
 * (`DELETE /api/credentials/pool/{provider}/{index}`, hermes_cli/web_routers/ops.py), so a removal
 * must never target a guessed or outdated position.
 *
 * The VM's IO dispatcher is injected, so the tests run on virtual time without sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SystemViewModelCredentialsTest {
    private val mockApp = mockk<Application>(relaxed = true)
    private val mockApi = mockk<HermesApiService>(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    private fun pool(vararg labels: String) =
        CredentialPoolResponse(
            providers =
                listOf(
                    CredentialPoolProvider(
                        provider = "openrouter",
                        entries = labels.mapIndexed { i, label -> CredentialPoolEntry(index = i + 1, label = label) },
                    ),
                ),
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(ApiClient)
        every { ApiClient.hermesApi } returns mockApi
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel() = SystemViewModel(mockApp, ioDispatcher = testDispatcher)

    private fun settle() {
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `removals are blocked until the list is reloaded after a removal`() {
        val removeGate = CompletableDeferred<Unit>()
        coEvery { mockApi.removeCredentialPoolEntry("openrouter", 1) } coAnswers {
            removeGate.await()
            Response.success(ActionResponse(ok = true))
        }
        coEvery { mockApi.getCredentialPool() } returns Response.success(pool("second"))
        val vm = createViewModel()

        vm.removeCredential("openrouter", 1)
        assertTrue(vm.uiState.value.credentialsPending)
        // The second row of the outdated list is position 1 on the server once the first removal lands.
        vm.removeCredential("openrouter", 2)

        removeGate.complete(Unit)
        settle()
        assertFalse(vm.uiState.value.credentialsPending)

        assertEquals(
            listOf("second"),
            vm.uiState.value.credentials
                .single()
                .entries
                ?.map { it.label },
        )
        coVerify(exactly = 1) { mockApi.removeCredentialPoolEntry(any(), any()) }
        coVerify(exactly = 0) { mockApi.removeCredentialPoolEntry("openrouter", 2) }
    }

    @Test
    fun `a removal is never sent for a missing or invalid position`() {
        val vm = createViewModel()

        vm.removeCredential("openrouter", 0)
        vm.removeCredential("", 1)

        assertFalse(vm.uiState.value.credentialsPending)
        coVerify(exactly = 0) { mockApi.removeCredentialPoolEntry(any(), any()) }
    }

    @Test
    fun `a pool listing requested before a removal does not unblock removals`() {
        val calls = AtomicInteger()
        val staleListStarted = CompletableDeferred<Unit>()
        val staleListGate = CompletableDeferred<Unit>()
        coEvery { mockApi.getCredentialPool() } coAnswers {
            if (calls.getAndIncrement() == 0) {
                staleListStarted.complete(Unit)
                staleListGate.await()
                Response.success(pool("first", "second"))
            } else {
                Response.success(pool("second"))
            }
        }
        coEvery { mockApi.removeCredentialPoolEntry("openrouter", 1) } returns
            Response.success(ActionResponse(ok = true))
        val vm = createViewModel()

        vm.loadCredentials()
        testDispatcher.scheduler.runCurrent()
        assertTrue(staleListStarted.isCompleted)
        vm.removeCredential("openrouter", 1)
        settle()
        assertFalse(vm.uiState.value.credentialsPending)
        staleListGate.complete(Unit)
        settle()
        assertEquals(2, calls.get())

        assertFalse(vm.uiState.value.credentialsPending)
        assertEquals(
            listOf("second"),
            vm.uiState.value.credentials
                .single()
                .entries
                ?.map { it.label },
        )
    }
}
