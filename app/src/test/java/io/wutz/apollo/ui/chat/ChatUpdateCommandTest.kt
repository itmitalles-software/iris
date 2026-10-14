package io.wutz.apollo.ui.chat

import android.app.Application
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.local.DataScope
import io.wutz.apollo.data.local.HermesDatabase
import io.wutz.apollo.data.model.ActionResponse
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.HermesApiService
import io.wutz.apollo.data.session.ProfileSwitchCoordinator
import io.wutz.apollo.data.ws.ConnectionStatus
import io.wutz.apollo.data.ws.HermesWsClient
import io.wutz.apollo.data.ws.WsEvent
import io.wutz.apollo.data.ws.WsMethods
import io.wutz.apollo.ui.chat.fakes.FakeChatPersistenceRepository
import io.wutz.apollo.ui.chat.fakes.FakeSlashUsageStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import retrofit2.Response

/**
 * Issue #862 — `/update` from chat: the backend handler is interactive +
 * session-exiting (confirmation modal + relaunch as `hermes update`), so it can
 * never produce the single response the slash worker waits for — sending it via
 * slash.exec/command.dispatch always dies with a 45s "slash worker timed out".
 * The command must be intercepted client-side: confirm dialog → REST
 * `POST /api/hermes/update` → the shared ActionProgressDialog tracks the log.
 *
 * NOTE: no mockkStatic(Dispatchers) — that bleed breaks later classes. The
 * update flow is REST + StateFlow only; setMain on the test dispatcher is safe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatUpdateCommandTest {
    @get:Rule
    val harness = ChatViewModelHarness()
    private val testDispatcher get() = harness.testDispatcher
    private val mockEventsFlow get() = harness.mockEventsFlow
    private val mockConnectionStatus get() = harness.mockConnectionStatus
    private val app get() = harness.app
    private val fakeRepo get() = harness.fakeRepo
    private val mockApi get() = harness.mockApi

    @Before
    fun setUp() {
        // The harness default stubs cover everything this class needs.
    }

    private suspend fun TestScope.createViewModelWithSession(): Pair<ChatViewModel, String> {
        // FakeSlashUsageStore: the real store inits a DataStore on real IO
        // threads — with a relaxed-mock app that NPEs on cacheDir and leaks
        // an uncaught exception into the NEXT runTest class (CI flakes:
        // UncaughtExceptionsBeforeTest).
        val vm =
            ChatViewModel(
                app,
                false,
                fakeRepo,
                FakeSlashUsageStore(),
                ioDispatcher = testDispatcher,
                sendStore = ChatSendStore(),
                timing = ChatTimingConfig.TEST,
            )
        advanceUntilIdle()
        mockConnectionStatus.value = ConnectionStatus.CONNECTED
        mockEventsFlow.emit(WsEvent.GatewayReady(null))
        advanceUntilIdle()
        // req-id-3 = session.create (after loadSessions + fetchCommandCatalog)
        mockEventsFlow.emit(WsEvent.RpcResult("req-id-3", mapOf("session_id" to "session-xyz")))
        advanceUntilIdle()
        return Pair(vm, "session-xyz")
    }

    @Test
    fun `update in chat opens the confirm dialog and fires no WS RPC`() =
        runTest {
            val (vm, _) = createViewModelWithSession()

            vm.sendMessage("/update")
            advanceUntilIdle()

            assertTrue("confirm dialog should open", vm.uiState.value.updateConfirmOpen)
            assertEquals(
                "/update",
                vm.uiState.value.messages
                    .last()
                    .content,
            )

            // The whole point of the fix: NOTHING goes over the wire.
            verify(exactly = 0) {
                HermesWsClient.request(WsMethods.COMMAND_DISPATCH, any(), any())
            }
            verify(exactly = 0) { HermesWsClient.request(WsMethods.SLASH_EXEC, any(), any()) }
        }

    @Test
    fun `applyUpdate triggers the REST action and starts the progress popup`() =
        runTest {
            val vm =
                ChatViewModel(
                    app,
                    false,
                    fakeRepo,
                    FakeSlashUsageStore(),
                    ioDispatcher = testDispatcher,
                    sendStore = ChatSendStore(),
                    timing = ChatTimingConfig.TEST,
                )
            coEvery { mockApi.updateHermes() } returns
                Response.success(ActionResponse(ok = true, name = "hermes-update"))
            // One running poll, then the action exits — runTest's teardown
            // advances virtual time, so the loop must terminate.
            coEvery { mockApi.getActionStatus("hermes-update") } returnsMany
                listOf(
                    Response.success(
                        io.wutz.apollo.data.model.ActionStatusResponse(
                            name = "hermes-update",
                            running = true,
                            lines = listOf("cloning repo…"),
                        ),
                    ),
                    Response.success(
                        io.wutz.apollo.data.model.ActionStatusResponse(
                            name = "hermes-update",
                            running = false,
                            exit_code = 0,
                            lines = listOf("cloning repo…", "done"),
                        ),
                    ),
                )

            vm.applyUpdate()
            runCurrent()

            val state = vm.actionProgress.state.value
            assertTrue("popup should be visible", state.visible)
            assertEquals(
                io.wutz.apollo.ui.common.ActionProgressPhase.RUNNING,
                state.phase,
            )
            assertEquals("hermes-update", state.actionName)
        }

    @Test
    fun `applyUpdate settles on success when the action exits cleanly`() =
        runTest {
            val vm =
                ChatViewModel(
                    app,
                    false,
                    fakeRepo,
                    FakeSlashUsageStore(),
                    ioDispatcher = testDispatcher,
                    sendStore = ChatSendStore(),
                    timing = ChatTimingConfig.TEST,
                )
            coEvery { mockApi.updateHermes() } returns
                Response.success(ActionResponse(ok = true, name = "hermes-update"))
            coEvery { mockApi.getActionStatus("hermes-update") } returns
                Response.success(
                    io.wutz.apollo.data.model.ActionStatusResponse(
                        name = "hermes-update",
                        running = false,
                        exit_code = 0,
                        lines = listOf("done"),
                    ),
                )

            vm.applyUpdate()
            advanceTimeBy(1_500)
            runCurrent()

            val state = vm.actionProgress.state.value
            assertEquals(
                io.wutz.apollo.ui.common.ActionProgressPhase.SUCCEEDED,
                state.phase,
            )
            assertEquals(0, state.exitCode)
        }

    @Test
    fun `applyUpdate surfaces a rejected trigger in the popup`() =
        runTest {
            val vm =
                ChatViewModel(
                    app,
                    false,
                    fakeRepo,
                    FakeSlashUsageStore(),
                    ioDispatcher = testDispatcher,
                    sendStore = ChatSendStore(),
                    timing = ChatTimingConfig.TEST,
                )
            coEvery { mockApi.updateHermes() } returns
                Response.error(404, "no update endpoint".toResponseBody())

            vm.applyUpdate()
            runCurrent()

            val state = vm.actionProgress.state.value
            assertEquals(
                io.wutz.apollo.ui.common.ActionProgressPhase.FAILED,
                state.phase,
            )
            assertNotNull(state.error)
            assertFalse(vm.uiState.value.updateConfirmOpen)
        }
}
