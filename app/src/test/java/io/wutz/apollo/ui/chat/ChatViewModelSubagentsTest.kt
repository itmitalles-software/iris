package io.wutz.apollo.ui.chat

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import io.mockk.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.local.DataScope
import io.wutz.apollo.data.local.HermesDatabase
import io.wutz.apollo.data.model.Attachment
import io.wutz.apollo.data.model.AttachmentSource
import io.wutz.apollo.data.model.AudioTranscriptionResponse
import io.wutz.apollo.data.model.BusySendMode
import io.wutz.apollo.data.model.PaginationInfo
import io.wutz.apollo.data.model.SessionMessage
import io.wutz.apollo.data.model.SessionMessagesAroundPagination
import io.wutz.apollo.data.model.SessionMessagesAroundResponse
import io.wutz.apollo.data.model.SessionMessagesResponse
import io.wutz.apollo.data.model.SessionTimelineEntry
import io.wutz.apollo.data.model.SessionTimelinePagination
import io.wutz.apollo.data.model.SessionTimelineResponse
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.GatewayFile
import io.wutz.apollo.data.remote.GatewayFileClient
import io.wutz.apollo.data.remote.GatewayFileResult
import io.wutz.apollo.data.session.ActiveSessionHolder
import io.wutz.apollo.data.session.ProfileSwitchCoordinator
import io.wutz.apollo.data.ws.ConnectionOperationParser
import io.wutz.apollo.data.ws.ConnectionStatus
import io.wutz.apollo.data.ws.HermesWsClient
import io.wutz.apollo.data.ws.JsonRpcError
import io.wutz.apollo.data.ws.ModelCatalogStore
import io.wutz.apollo.data.ws.WsEvent
import io.wutz.apollo.data.ws.WsMethods
import io.wutz.apollo.data.ws.contract.ConfigSetParams
import io.wutz.apollo.data.ws.contract.ProcessStopParams
import io.wutz.apollo.data.ws.contract.ProcessStopResult
import io.wutz.apollo.data.ws.contract.RpcMethods
import io.wutz.apollo.data.ws.contract.SubagentInterruptResult
import io.wutz.apollo.data.ws.contract.SubagentSteerParams
import io.wutz.apollo.data.ws.contract.SubagentSteerResult
import io.wutz.apollo.data.ws.contract.SubagentTailParams
import io.wutz.apollo.notification.TurnCorrelationTracker
import io.wutz.apollo.ui.chat.fakes.FakeChatPersistenceRepository
import io.wutz.apollo.ui.chat.fakes.FakeSlashUsageStore
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/** ChatViewModel behaviour: Subagent steering and cancellation. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelSubagentsTest : ChatViewModelTestBase() {
    @Test
    fun testSteerSubagent_callsSubagentSteerAndMarksSteeredWhenQueued() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            coEvery { HermesWsClient.call(RpcMethods.SUBAGENT_STEER, any(), any(), any()) } returns
                SubagentSteerResult(status = "queued", subagentId = "sub-123")
            val indicator = startSubagent(sessionId, "Search database")

            viewModel.steerSubagent(indicator, "  Use index scan instead ")
            advanceUntilIdle()

            val updated =
                viewModel.uiState.value.subagentIndicators
                    .first { it.subagentId == "sub-123" }
            assertEquals("steered", updated.status)
            assertTrue(updated.logs.any { it.text.contains("Use index scan instead") })
            coVerify(exactly = 1) {
                HermesWsClient.call(
                    RpcMethods.SUBAGENT_STEER,
                    SubagentSteerParams(sessionId, "sub-123", "Use index scan instead"),
                    any(),
                    any(),
                )
            }
            // The parent turn is never redirected with slash text.
            io.mockk.verify(exactly = 0) { HermesWsClient.sendRedirect(any(), any(), any()) }
        }

    @Test
    fun testSteerSubagent_rejectedKeepsStatusAndLogsNotDelivered() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            coEvery { HermesWsClient.call(RpcMethods.SUBAGENT_STEER, any(), any(), any()) } returns
                SubagentSteerResult(status = "rejected", subagentId = "sub-123")
            val indicator = startSubagent(sessionId, "Search database")

            viewModel.steerSubagent(indicator, "Use index scan instead")
            advanceUntilIdle()

            val updated =
                viewModel.uiState.value.subagentIndicators
                    .first { it.subagentId == "sub-123" }
            assertTrue(updated.isRunning)
            assertTrue(updated.logs.any { it.isError && it.text.contains("not delivered") })
        }

    @Test
    fun testStopSubagent_callsSubagentInterruptAndMarksCancelled() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            coEvery { HermesWsClient.call(RpcMethods.SUBAGENT_INTERRUPT, any(), any(), any()) } returns
                SubagentInterruptResult(found = true, subagentId = "sub-123")
            val indicator = startSubagent(sessionId, "Run deep scan")

            viewModel.stopSubagent(indicator)
            advanceUntilIdle()

            val updated =
                viewModel.uiState.value.subagentIndicators
                    .first { it.subagentId == "sub-123" }
            assertEquals("cancelled", updated.status)
            assertTrue(updated.logs.any { it.text.contains("Stopped subagent") })
            coVerify(exactly = 1) {
                HermesWsClient.call(
                    RpcMethods.SUBAGENT_INTERRUPT,
                    SubagentTailParams(sessionId, "sub-123"),
                    any(),
                    any(),
                )
            }
            io.mockk.verify(exactly = 0) { HermesWsClient.sendRedirect(any(), any(), any()) }
        }

    @Test
    fun testStopSubagent_notFoundReportsAlreadyFinished() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            coEvery { HermesWsClient.call(RpcMethods.SUBAGENT_INTERRUPT, any(), any(), any()) } returns
                SubagentInterruptResult(found = false, subagentId = "sub-123")
            val indicator = startSubagent(sessionId, "Run deep scan")

            viewModel.stopSubagent(indicator)
            advanceUntilIdle()

            val updated =
                viewModel.uiState.value.subagentIndicators
                    .first { it.subagentId == "sub-123" }
            assertFalse(updated.isCancelled)
            assertTrue(updated.logs.any { it.text.contains("already finished") })
        }

    @Test
    fun testStopSubagent_rpcFailureIsLogged() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            coEvery { HermesWsClient.call(RpcMethods.SUBAGENT_INTERRUPT, any(), any(), any()) } throws
                HermesWsClient.HermesRpcException("session not found or not owned by this transport", 4001)
            val indicator = startSubagent(sessionId, "Run deep scan")

            viewModel.stopSubagent(indicator)
            advanceUntilIdle()

            val updated =
                viewModel.uiState.value.subagentIndicators
                    .first { it.subagentId == "sub-123" }
            assertTrue(updated.isRunning)
            assertTrue(updated.logs.any { it.isError && it.text.contains("Could not reach the subagent") })
        }

    @Test
    fun testSubagentControls_withoutSubagentIdSendNothing() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val indicator =
                SubagentIndicator(type = "subagent.start", subagentId = null, goal = "Legacy", status = "running")

            viewModel.steerSubagent(indicator, "Change course")
            viewModel.stopSubagent(indicator)
            advanceUntilIdle()

            coVerify(exactly = 0) { HermesWsClient.call(RpcMethods.SUBAGENT_STEER, any(), any(), any()) }
            coVerify(exactly = 0) { HermesWsClient.call(RpcMethods.SUBAGENT_INTERRUPT, any(), any(), any()) }
            io.mockk.verify(exactly = 0) { HermesWsClient.sendRedirect(any(), any(), any()) }
        }

    @Test
    fun testHandleGatewayReady_withRestoreLastSessionEnabled_resumesStoredSession() =
        runTest {
            every { AuthManager.isRestoreLastSession() } returns true
            every { AuthManager.getLastOpenedSessionId() } returns "session-stored-999"

            val resumeParamsSlot = slot<Map<String, Any>>()
            every {
                HermesWsClient.send(WsMethods.SESSION_RESUME, capture(resumeParamsSlot), any())
            } answers {
                "req-resume-test"
            }

            val viewModel = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            io.mockk.verify {
                HermesWsClient.send(
                    WsMethods.SESSION_RESUME,
                    any(),
                    any(),
                )
            }
            assertEquals(JsonPrimitive("session-stored-999"), resumeParamsSlot.captured["session_id"])
        }

    @Test
    fun testHandleGatewayReady_withRestoreLastSessionDisabled_createsNewSession() =
        runTest {
            every { AuthManager.isRestoreLastSession() } returns false
            every { AuthManager.getLastOpenedSessionId() } returns "session-stored-999"

            val viewModel = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            io.mockk.verify {
                HermesWsClient.send(
                    WsMethods.SESSION_CREATE,
                    any(),
                    any(),
                )
            }
            io.mockk.verify(exactly = 0) {
                HermesWsClient.send(
                    WsMethods.SESSION_RESUME,
                    any(),
                    any(),
                )
            }
        }

    @Test
    fun testSwitchSession_updatesLastOpenedSessionId() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            viewModel.switchSession("session-other-456")
            advanceUntilIdle()

            io.mockk.verify { AuthManager.setLastOpenedSessionId("session-other-456") }
        }

    // ── Subagent steering & cancellation (issue #1030) ───────────────────

    private suspend fun TestScope.startSubagent(
        sessionId: String,
        goal: String,
    ): SubagentIndicator {
        mockEventsFlow.emit(
            WsEvent.SubagentEvent(
                type = "subagent.start",
                payload = mapOf("subagent_id" to "sub-123", "goal" to goal),
                sessionId = sessionId,
            ),
        )
        advanceUntilIdle()
        return SubagentIndicator(type = "subagent.start", subagentId = "sub-123", goal = goal, status = "running")
    }
}
