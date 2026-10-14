package io.wutz.apollo.ui.system

import android.app.Application
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.wutz.apollo.R
import io.wutz.apollo.data.model.ActionResponse
import io.wutz.apollo.data.model.ActionStatusResponse
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.HermesApiService
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.util.concurrent.atomic.AtomicInteger

/**
 * The System screen's action log polling must not run forever when the
 * status endpoint keeps failing (the activity-scoped ViewModel outlives the screen).
 *
 * The poll delay runs on the virtual main dispatcher; the API call hops to the real
 * Dispatchers.IO, so the test drives the scheduler until a bounded deadline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SystemViewModelActionPollingTest {
    private val scheduler = TestCoroutineScheduler()
    private val mockApp = mockk<Application>(relaxed = true)
    private val mockApi = mockk<HermesApiService>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        mockkObject(ApiClient)
        every { ApiClient.hermesApi } returns mockApi
        coEvery { mockApi.runSecurityAudit() } returns Response.success(ActionResponse(name = "security-audit"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun driveUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not reached" }
            scheduler.advanceUntilIdle()
            Thread.sleep(5)
        }
    }

    /** Lets in-flight IO results land and advances well past several poll intervals. */
    private fun settle() {
        repeat(20) {
            scheduler.advanceUntilIdle()
            Thread.sleep(5)
        }
    }

    @Test
    fun `polling stops after repeated status failures and reports it`() {
        val calls = AtomicInteger()
        coEvery { mockApi.getActionStatus("security-audit", any()) } answers {
            calls.incrementAndGet()
            Response.error(404, "not found".toResponseBody())
        }
        val vm = SystemViewModel(mockApp)

        vm.runSecurityAudit()
        driveUntil {
            (vm.uiState.value.toastMessage as? UiText.Resource)?.id == R.string.system_msg_action_status_lost
        }
        settle()

        assertEquals(SystemViewModel.MAX_ACTION_POLL_FAILURES, calls.get())
    }

    @Test
    fun `a successful poll resets the failure count`() {
        val calls = AtomicInteger()
        coEvery { mockApi.getActionStatus("security-audit", any()) } answers {
            val n = calls.incrementAndGet()
            when {
                n == 4 -> Response.success(ActionStatusResponse(name = "security-audit", running = true))
                n < 9 -> Response.error(404, "not found".toResponseBody())
                else -> Response.success(ActionStatusResponse(name = "security-audit", running = false))
            }
        }
        val vm = SystemViewModel(mockApp)

        vm.runSecurityAudit()
        driveUntil { isFinished(vm) }
        settle()

        // Three failures, one success, then four failures stay below the cap until the action ends.
        assertEquals(9, calls.get())
        assertTrue(isFinished(vm))
    }

    private fun isFinished(vm: SystemViewModel): Boolean {
        val log = vm.uiState.value.actionLog ?: return false
        return log.running == false
    }
}
