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

/** ChatViewModel behaviour: Session switch, resume and resume recovery. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelSessionTest : ChatViewModelTestBase() {
    // ── Session switch ───────────────────────────────────────────────────────

    @Test
    fun testSwitchSession_opensSelectedHistorySessionInsteadOfLatestDescendant() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            coEvery { ApiClient.hermesApi.getLatestDescendant("session-456") } returns
                retrofit2.Response.success(
                    io.wutz.apollo.data.model.LatestDescendantResponse(
                        requested_session_id = "session-456",
                        session_id = "unrelated-descendant",
                        changed = true,
                    ),
                )

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            assertEquals("session-456", viewModel.uiState.value.currentSessionId)
            assertTrue(
                viewModel.uiState.value.messages
                    .isEmpty(),
            )

            verify {
                HermesWsClient.send(
                    WsMethods.SESSION_RESUME,
                    JsonObject(
                        mapOf(
                            "session_id" to JsonPrimitive("session-456"),
                            "source" to JsonPrimitive("desktop"),
                            "omit_messages" to JsonPrimitive(true),
                        ),
                    ),
                    any(),
                )
            }
        }

    @Test
    fun testSwitchSession_ignoresLateResumeResultFromPreviousSelection() =
        runTest {
            stubEmptySessionRests("session-a", "session-b")
            val (viewModel, _) = createViewModelWithSession()
            val resumeRequests = mutableMapOf<String, String>()
            every { HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any()) } answers {
                val sessionId = (arg<Map<String, Any?>>(1).getValue("session_id") as JsonPrimitive).content
                val requestId = "resume-$sessionId"
                resumeRequests[sessionId] = requestId
                arg<((String) -> Unit)?>(2)?.invoke(requestId)
                requestId
            }

            viewModel.switchSession("session-a")
            runCurrent()
            viewModel.switchSession("session-b")
            runCurrent()
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    resumeRequests.getValue("session-b"),
                    mapOf("session_id" to "runtime-b", "resumed" to "session-b"),
                ),
            )
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    resumeRequests.getValue("session-a"),
                    mapOf("session_id" to "runtime-a", "resumed" to "session-a"),
                ),
            )
            advanceUntilIdle()

            assertEquals("session-b", viewModel.uiState.value.currentSessionId)
            assertEquals("runtime-b", ActiveSessionHolder.activeSessionId.value)
        }

    @Test
    fun testReconnect_ignoresSupersededResumeForSameSession() =
        runTest {
            stubEmptySessionRests("session-a")
            val (viewModel, _) = createViewModelWithSession()
            val resumeRequests = mutableListOf<String>()
            every { HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any()) } answers {
                val requestId = "resume-${resumeRequests.size + 1}"
                resumeRequests += requestId
                arg<((String) -> Unit)?>(2)?.invoke(requestId)
                requestId
            }

            viewModel.switchSession("session-a")
            runCurrent()
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            runCurrent()
            mockEventsFlow.emit(
                WsEvent.RpcResult(resumeRequests[1], mapOf("session_id" to "runtime-new")),
            )
            mockEventsFlow.emit(
                WsEvent.RpcResult(resumeRequests[0], mapOf("session_id" to "runtime-old")),
            )
            advanceUntilIdle()

            assertEquals("session-a", viewModel.uiState.value.currentSessionId)
            assertEquals("runtime-new", ActiveSessionHolder.activeSessionId.value)
        }

    @Test
    fun testSwitchSession_ignoresLateResumeErrorBeforeReducerMutation() =
        runTest {
            val mockApi = ApiClient.hermesApi
            val messagesA =
                CompletableDeferred<retrofit2.Response<io.wutz.apollo.data.model.SessionMessagesResponse>>()
            val messagesB =
                CompletableDeferred<retrofit2.Response<io.wutz.apollo.data.model.SessionMessagesResponse>>()
            every { AuthManager.getBaseUrl() } returns "http://test.local/"
            coEvery { mockApi.getSessions(any(), any(), any()) } returns
                retrofit2.Response.success(
                    io.wutz.apollo.data.model
                        .SessionListResponse(sessions = emptyList(), total = 0),
                )
            coEvery {
                mockApi.getSessionMessages(
                    "session-a",
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } coAnswers { messagesA.await() }
            coEvery {
                mockApi.getSessionMessages(
                    "session-b",
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } coAnswers { messagesB.await() }

            val (viewModel, _) = createViewModelWithSession()
            val resumeRequests = mutableMapOf<String, String>()
            every { HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any()) } answers {
                val sessionId = (arg<Map<String, Any?>>(1).getValue("session_id") as JsonPrimitive).content
                val requestId = "resume-$sessionId"
                resumeRequests[sessionId] = requestId
                arg<((String) -> Unit)?>(2)?.invoke(requestId)
                requestId
            }

            viewModel.switchSession("session-a")
            runCurrent()
            viewModel.switchSession("session-b")
            runCurrent()
            mockEventsFlow.emit(
                WsEvent.RpcError(
                    resumeRequests.getValue("session-a"),
                    JsonRpcError(code = -32000, message = "stale resume failure"),
                ),
            )
            runCurrent()

            assertEquals("session-b", viewModel.uiState.value.currentSessionId)
            assertTrue(viewModel.uiState.value.isLoading)
            assertNull(viewModel.uiState.value.errorMessage)
            assertNull(viewModel.uiState.value.resumeError)

            val empty =
                retrofit2.Response.success(
                    io.wutz.apollo.data.model
                        .SessionMessagesResponse(messages = emptyList()),
                )
            messagesA.complete(empty)
            messagesB.complete(empty)
            advanceUntilIdle()
        }

    @Test
    fun testSwitchSession_ignoresLateRestHydrationFromPreviousGeneration() =
        runTest {
            val mockApi = ApiClient.hermesApi
            val messagesA =
                CompletableDeferred<retrofit2.Response<io.wutz.apollo.data.model.SessionMessagesResponse>>()
            val messagesB =
                CompletableDeferred<retrofit2.Response<io.wutz.apollo.data.model.SessionMessagesResponse>>()
            every { AuthManager.getBaseUrl() } returns "http://test.local/"
            coEvery { mockApi.getSessions(any(), any(), any()) } returns
                retrofit2.Response.success(
                    io.wutz.apollo.data.model
                        .SessionListResponse(sessions = emptyList(), total = 0),
                )
            coEvery {
                mockApi.getSessionMessages(
                    "session-a",
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } coAnswers { messagesA.await() }
            coEvery {
                mockApi.getSessionMessages(
                    "session-b",
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } coAnswers { messagesB.await() }

            val (viewModel, _) = createViewModelWithSession()
            viewModel.switchSession("session-a")
            runCurrent()
            viewModel.switchSession("session-b")
            runCurrent()

            messagesB.complete(
                retrofit2.Response.success(
                    io.wutz.apollo.data.model.SessionMessagesResponse(
                        messages =
                            listOf(
                                io.wutz.apollo.data.model.SessionMessage(
                                    role = "assistant",
                                    content = JsonPrimitive("message-b"),
                                ),
                            ),
                    ),
                ),
            )
            runCurrent()
            messagesA.complete(
                retrofit2.Response.success(
                    io.wutz.apollo.data.model.SessionMessagesResponse(
                        messages =
                            listOf(
                                io.wutz.apollo.data.model.SessionMessage(
                                    role = "assistant",
                                    content = JsonPrimitive("stale-message-a"),
                                ),
                            ),
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals("session-b", viewModel.uiState.value.currentSessionId)
            assertEquals(
                listOf("message-b"),
                viewModel.uiState.value.messages
                    .map { it.content },
            )
        }

    @Test
    fun testSwitchSession_clearsSessionBoundUiState() =
        runTest {
            stubEmptySessionRests("session-b")
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(
                WsEvent.ClarifyRequest("Choose", listOf("A"), "clarify-1", sessionId),
            )
            mockEventsFlow.emit(WsEvent.SudoRequest("sudo-1", sessionId))
            mockEventsFlow.emit(WsEvent.SecretRequest("secret-1", sessionId))
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    mapOf(
                        "model" to "model-a",
                        "provider" to "provider-a",
                        "reasoning_effort" to "high",
                        "terminal_backend" to "docker",
                    ),
                ),
            )
            mockEventsFlow.emit(
                WsEvent.SubagentEvent(
                    type = "subagent.start",
                    payload = mapOf("subagent_id" to "sub-1", "goal" to "work"),
                    sessionId = sessionId,
                ),
            )
            mockEventsFlow.emit(
                WsEvent.ToolStart(
                    name = "todo",
                    data = mapOf("todos" to listOf(mapOf("id" to "todo-1", "content" to "work"))),
                    sessionId = sessionId,
                ),
            )
            mockEventsFlow.emit(WsEvent.GatewayError("old error"))
            viewModel.openModelPicker()
            runCurrent()

            val oldState = viewModel.uiState.value
            assertNotNull(oldState.clarifyRequest)
            assertNotNull(oldState.sudoPrompt)
            assertNotNull(oldState.secretPrompt)
            assertNotNull(oldState.currentSessionModel)
            assertEquals("docker", oldState.terminalBackend)
            assertTrue(oldState.subagentIndicators.isNotEmpty())
            assertTrue(oldState.todos.isNotEmpty())
            assertTrue(oldState.showModelPicker)
            assertNotNull(oldState.errorMessage)

            viewModel.switchSession("session-b")
            runCurrent()
            val state = viewModel.uiState.value

            assertEquals("session-b", state.currentSessionId)
            assertTrue(state.messages.isEmpty())
            assertNull(state.clarifyRequest)
            assertNull(state.sudoPrompt)
            assertNull(state.secretPrompt)
            assertNull(state.currentSessionModel)
            assertNull(state.reasoningLevel)
            assertNull(state.terminalBackend)
            assertTrue(state.subagentIndicators.isEmpty())
            assertTrue(state.todos.isEmpty())
            assertFalse(state.showModelPicker)
            assertNull(state.errorMessage)
            assertNull(state.resumeError)
        }

    @Test
    fun testCreateNewSession_invalidatesResumeAndClearsSessionStateBeforeSend() =
        runTest {
            stubEmptySessionRests("session-a")
            val (viewModel, _) = createViewModelWithSession()
            var resumeRequestId = ""
            every { HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any()) } answers {
                resumeRequestId = "resume-a"
                arg<((String) -> Unit)?>(2)?.invoke(resumeRequestId)
                resumeRequestId
            }
            viewModel.switchSession("session-a")
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.SudoRequest("sudo-1", "session-a"))
            mockEventsFlow.emit(WsEvent.GatewayError("old error"))
            runCurrent()

            viewModel.createNewSession()
            mockEventsFlow.emit(
                WsEvent.RpcError(
                    resumeRequestId,
                    JsonRpcError(code = -32000, message = "stale resume failure"),
                ),
            )
            runCurrent()

            val state = viewModel.uiState.value
            assertNull(state.currentSessionId)
            assertNull(state.sudoPrompt)
            assertNull(state.errorMessage)
            assertNull(state.resumeError)
            assertNull(state.pendingReasoningLevel)
            assertNull(state.reasoningWireLevel)
            assertTrue(state.isLoading)
        }

    @Test
    fun testSessionInfo_reconcilesRequestedAndWireReasoningEffort() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    data =
                        mapOf(
                            "model" to "gpt-5",
                            "provider" to "openai",
                            "reasoning_effort" to "ultra",
                            "reasoning_effort_wire" to "max",
                        ),
                    sessionId = sessionId,
                ),
            )
            runCurrent()

            val state = viewModel.uiState.value
            assertEquals("ultra", state.reasoningLevel)
            assertEquals("max", state.reasoningWireLevel)

            // Other session's event is ignored
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    data =
                        mapOf(
                            "model" to "gpt-5",
                            "provider" to "openai",
                            "reasoning_effort" to "low",
                        ),
                    sessionId = "other-session-id",
                ),
            )
            runCurrent()

            assertEquals("ultra", viewModel.uiState.value.reasoningLevel)
        }

    @Test
    fun testSessionBranchResult_clearsSessionBoundUiState() =
        runTest {
            stubEmptySessionRests("branch-1")
            val (viewModel, sessionId) = createViewModelWithSession()
            val captured = captureSends()
            mockEventsFlow.emit(WsEvent.SecretRequest("secret-1", sessionId))
            mockEventsFlow.emit(
                WsEvent.SubagentEvent(
                    type = "subagent.start",
                    payload = mapOf("subagent_id" to "sub-1", "goal" to "work"),
                    sessionId = sessionId,
                ),
            )
            mockEventsFlow.emit(
                WsEvent.ToolStart(
                    name = "todo",
                    data = mapOf("todos" to listOf(mapOf("id" to "todo-1", "content" to "work"))),
                    sessionId = sessionId,
                ),
            )
            mockEventsFlow.emit(WsEvent.SessionInfo(mapOf("model" to "model-a", "provider" to "provider-a")))
            runCurrent()

            viewModel.sendMessage("/fork")
            runCurrent()
            val branchRequest = captured.last { it.first == WsMethods.SESSION_BRANCH_WHOLE }.second
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    branchRequest,
                    mapOf("session_id" to "branch-1", "title" to "Branch"),
                ),
            )
            runCurrent()

            val state = viewModel.uiState.value
            assertEquals("branch-1", state.currentSessionId)
            assertNull(state.secretPrompt)
            assertNull(state.currentSessionModel)
            assertTrue(state.subagentIndicators.isEmpty())
            assertTrue(state.todos.isEmpty())
            assertNull(state.errorMessage)
        }

    // ── Session resume recovery (desktop parity: warm cache + bounded retry) ──

    /** Override the send stub to capture (method → id) pairs. */
    @Test
    fun testSendReadiness_restBeforeResume_preservesDraftUntilAck() =
        runTest {
            stubSession456Rests(success = true)
            val (viewModel, _) = createViewModelWithSession()
            assertTrue(viewModel.uiState.value.isSessionReady)
            val captured = captureSends()
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            val before = viewModel.uiState.value
            assertFalse(before.isSessionReady)
            assertFalse(viewModel.sendMessage("keep this draft"))
            assertEquals(before.messages, viewModel.uiState.value.messages)
            assertEquals(before.pendingAttachments, viewModel.uiState.value.pendingAttachments)
            assertFalse(viewModel.uiState.value.isAgentTyping)
            val id = captured.last { it.first == WsMethods.SESSION_RESUME }.second
            mockEventsFlow.emit(WsEvent.RpcResult(id, mapOf("session_id" to "runtime-456")))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isSessionReady)
            assertTrue(viewModel.sendMessage("keep this draft"))
            advanceUntilIdle()
            assertEquals(
                1,
                viewModel.uiState.value.messages
                    .count { it.content == "keep this draft" },
            )
        }

    @Test
    fun testSendReadiness_resumeBeforeRest_waitsForHydration() =
        runTest {
            stubSession456Rests(success = true)
            val response =
                CompletableDeferred<retrofit2.Response<io.wutz.apollo.data.model.SessionMessagesResponse>>()
            coEvery {
                ApiClient.hermesApi.getSessionMessages("session-456", any(), any(), any(), any())
            } coAnswers { response.await() }
            val (viewModel, _) = createViewModelWithSession()
            val captured = captureSends()
            viewModel.switchSession("session-456")
            runCurrent()
            val id = captured.last { it.first == WsMethods.SESSION_RESUME }.second
            mockEventsFlow.emit(WsEvent.RpcResult(id, mapOf("session_id" to "runtime-456")))
            runCurrent()
            assertFalse(viewModel.uiState.value.isSessionReady)
            assertFalse(viewModel.sendMessage("/queue draft"))
            response.complete(
                retrofit2.Response.success(
                    io.wutz.apollo.data.model
                        .SessionMessagesResponse(messages = emptyList()),
                ),
            )
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isSessionReady)
        }

    @Test
    fun testSendReadiness_reconnectRejectsOldAckAndRequiresFreshHydration() =
        runTest {
            stubSession456Rests(success = true)
            val (viewModel, _) = createViewModelWithSession()
            val captured = captureSends()
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            val oldId = captured.last { it.first == WsMethods.SESSION_RESUME }.second
            mockConnectionStatus.value = ConnectionStatus.RECONNECTING
            runCurrent()
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.RpcResult(oldId, mapOf("session_id" to "stale-runtime")))
            runCurrent()
            assertFalse(viewModel.uiState.value.isSessionReady)
            assertFalse(viewModel.sendMessage("draft"))
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isSessionReady)
            val freshId = captured.last { it.first == WsMethods.SESSION_RESUME }.second
            mockEventsFlow.emit(WsEvent.RpcResult(freshId, mapOf("session_id" to "fresh-runtime")))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isSessionReady)
            viewModel.retryResumeSession()
            runCurrent()
            assertFalse(viewModel.uiState.value.isSessionReady)
        }

    @Test
    fun testSendReadiness_malformedAckNeverEnablesSend() =
        runTest {
            stubSession456Rests(success = true)
            val (viewModel, _) = createViewModelWithSession()
            val captured = captureSends()
            viewModel.switchSession("session-456")
            runCurrent()
            val id = captured.last { it.first == WsMethods.SESSION_RESUME }.second
            mockEventsFlow.emit(WsEvent.RpcResult(id, mapOf("session_id" to "")))
            runCurrent()
            assertFalse(viewModel.uiState.value.isSessionReady)
            assertFalse(viewModel.sendMessage("draft"))
            assertTrue(viewModel.uiState.value.isResumeRetrying)
        }

    @Test
    fun testSendReadiness_abandonedCreateCannotAcceptDraft() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()
            mockConnectionStatus.value = ConnectionStatus.RECONNECTING
            runCurrent()
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            runCurrent()
            assertFalse(viewModel.sendMessage("retain me"))
            assertFalse(viewModel.uiState.value.isAgentTyping)
        }

    @Test
    fun testSendReadiness_pendingCreateAppliesBackpressure() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()
            assertTrue(viewModel.sendMessage("first"))
            assertFalse(viewModel.sendMessage("second"))
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.content == "second" },
            )
        }

    @Test
    fun testSwitchSession_paintsWarmCache_andKeepsItWhenResumeExhausted() =
        runTest {
            // Seed the Room cache for the target session.
            fakeRepo.dao.addMessageDirect(
                io.wutz.apollo.data.local.ChatMessageEntity(
                    id = "cached-1",
                    sessionId = "session-456",
                    role = "user",
                    content = "Cached hello",
                    timestamp = 1L,
                ),
            )
            // Server keeps failing → bounded retries exhaust → resumeError.
            stubSession456Rests(success = false)

            val (viewModel, _) = createViewModelWithSession()

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            // Warm cache painted and survived the exhausted retry cycle — the
            // screen never went blank, and the user gets history + an error.
            assertEquals(1, viewModel.uiState.value.messages.size)
            assertEquals(
                "Cached hello",
                viewModel.uiState.value.messages[0]
                    .content,
            )
            assertFalse(viewModel.uiState.value.isLoading)
            assertNotNull("resumeError must be set after retries exhaust", viewModel.uiState.value.resumeError)
            assertFalse(viewModel.uiState.value.isResumeRetrying)
        }

    @Test
    fun testSessionResume_boundedRetry_thenExplicitError() =
        runTest {
            stubSession456Rests(success = true)
            val (viewModel, _) = createViewModelWithSession()
            val captured = captureSends()

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            // Drive one RPC failure per cycle; each failure arms a backoff retry.
            // Exhaustion needs MAX+1 failures: attempts 0..MAX-1 arm retries,
            // the MAX-th failure hits the exhausted latch.
            for (cycle in 1..(ChatViewModel.MAX_RESUME_RETRIES + 1)) {
                val resumeId = captured.last { it.first == WsMethods.SESSION_RESUME }.second
                mockEventsFlow.emit(
                    WsEvent.RpcError(
                        resumeId,
                        JsonRpcError(code = -32000, message = "resume rejected"),
                    ),
                )
                advanceUntilIdle()
            }

            val resumeSends = captured.count { it.first == WsMethods.SESSION_RESUME }
            assertEquals(
                "initial + MAX_RESUME_RETRIES retries",
                ChatViewModel.MAX_RESUME_RETRIES + 1,
                resumeSends,
            )
            assertNotNull(viewModel.uiState.value.resumeError)
            assertFalse(viewModel.uiState.value.isResumeRetrying)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun testSessionResume_rpcAndRestFailuresCountAsOneCycle() =
        runTest {
            // REST fails + one RpcError lands while the retry is already armed —
            // they must count as ONE failure, so the retry budget is not
            // double-burned (5 sends total, not 4).
            stubSession456Rests(success = false)
            val (viewModel, _) = createViewModelWithSession()
            val captured = captureSends()

            viewModel.switchSession("session-456")
            runCurrent()
            // First cycle: REST already failed and armed the retry; now the WS
            // reject for the same resume arrives while that job is pending.
            val resumeId = captured.last { it.first == WsMethods.SESSION_RESUME }.second
            mockEventsFlow.emit(
                WsEvent.RpcError(
                    resumeId,
                    JsonRpcError(code = -32000, message = "resume rejected"),
                ),
            )
            runCurrent()
            advanceUntilIdle()

            val resumeSends = captured.count { it.first == WsMethods.SESSION_RESUME }
            assertEquals(
                "initial + MAX_RESUME_RETRIES retries — no double-burn",
                ChatViewModel.MAX_RESUME_RETRIES + 1,
                resumeSends,
            )
            assertNotNull(viewModel.uiState.value.resumeError)
        }

    @Test
    fun testReconnectOnUnconfirmedSession_skipsDoomedResume() =
        runTest {
            stubSession456Rests(success = true)
            val (viewModel, _) = createViewModelWithSession()
            val captured = captureSends()

            // Simulate a WS reconnect while sitting on the freshly created
            // session (never prompted — the gateway persists the DB row
            // lazily on the first prompt). The old code re-resumed the
            // storage key and the gateway 4007'd "session not found"
            // permanently; the retry could never fix it because the row only
            // appears once a prompt lands.
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            verify(exactly = 0) {
                HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any())
            }
            assertNull(
                "reconnect on an unconfirmed session must not error",
                viewModel.uiState.value.resumeError,
            )

            // Positive control: once the session HAS server presence (REST
            // 200 confirmed the row), a reconnect resumes it as before.
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()
            verify {
                HermesWsClient.send(
                    WsMethods.SESSION_RESUME,
                    JsonObject(
                        mapOf(
                            "session_id" to JsonPrimitive("session-456"),
                            "source" to JsonPrimitive("desktop"),
                            "omit_messages" to JsonPrimitive(true),
                        ),
                    ),
                    any(),
                )
            }
        }

    @Test
    fun testSessionInfo_hydratesFastModeState() =
        runTest {
            stubEmptySessionRests("session-a")
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.fastMode)

            // Fast = true in session.info
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    mapOf("fast" to true, "service_tier" to "priority"),
                ),
            )
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.fastMode)
            assertFalse(viewModel.uiState.value.isFastModeChanging)

            // Fast = false / normal in session.info
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    mapOf("fast" to false, "service_tier" to "normal"),
                ),
            )
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.fastMode)

            // Partial session.info without fast/service_tier does NOT clear state
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    mapOf("fast" to true),
                ),
            )
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.fastMode)

            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    mapOf("terminal_backend" to "tmux"),
                ),
            )
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.fastMode) // preserved!
        }

    @Test
    fun testSessionResume_hydratesFastModeState() =
        runTest {
            stubEmptySessionRests("session-b")
            val (viewModel, _) = createViewModelWithSession()
            var resumeReqId = ""
            every { HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any()) } answers {
                val reqId = "resume-req-1"
                resumeReqId = reqId
                arg<((String) -> Unit)?>(2)?.invoke(reqId)
                reqId
            }

            viewModel.switchSession("session-b")
            runCurrent()

            // Simulate SESSION_RESUME response with fast=true in info
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    id = resumeReqId,
                    result =
                        mapOf(
                            "session_id" to "sess-runtime-1",
                            "resumed" to "session-b",
                            "info" to
                                mapOf(
                                    "model" to "gpt-4o",
                                    "provider" to "openai",
                                    "fast" to true,
                                    "service_tier" to "priority",
                                ),
                        ),
                ),
            )
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.fastMode)
            assertFalse(viewModel.uiState.value.isFastModeChanging)
        }

    @Test
    fun testResumeNotFound_recoversWithNewSession() =
        runTest {
            stubSession456Rests(success = true)
            val (viewModel, _) = createViewModelWithSession()
            val captured = captureSends()

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            val resumeId = captured.last { it.first == WsMethods.SESSION_RESUME }.second
            val createsBefore = captured.count { it.first == WsMethods.SESSION_CREATE }

            // Gateway definitively has no row for this session (RPC 4007) —
            // e.g. the session was deleted or pruned server-side.
            mockEventsFlow.emit(
                WsEvent.RpcError(resumeId, JsonRpcError(code = 4007, message = "session not found")),
            )
            advanceUntilIdle()

            // No dead-end popup: the app recovered by creating a fresh session.
            assertNull("4007 must not dead-end on resumeError", viewModel.uiState.value.resumeError)
            assertEquals(createsBefore + 1, captured.count { it.first == WsMethods.SESSION_CREATE })

            // A second reject for the same resume (the paired REST 404 lands
            // just after the WS reject) must not double-create.
            mockEventsFlow.emit(
                WsEvent.RpcError(resumeId, JsonRpcError(code = 4007, message = "session not found")),
            )
            advanceUntilIdle()
            assertEquals(createsBefore + 1, captured.count { it.first == WsMethods.SESSION_CREATE })

            // Land the recovery create → the user lands on the new session
            // with an explanatory notice (queued until the create result so
            // the create's message wipe can't swallow it).
            val createId = captured.last { it.first == WsMethods.SESSION_CREATE }.second
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    createId,
                    mapOf(
                        "session_id" to "runtime-new",
                        "stored_session_id" to "session-new",
                    ),
                ),
            )
            advanceUntilIdle()
            assertEquals("session-new", viewModel.uiState.value.currentSessionId)
            assertTrue(
                "recovery notice must be visible",
                viewModel.uiState.value.messages
                    .any { it.content.contains("no longer available") },
            )
        }

    @Test
    fun testBranchResult_keepsStorageIdAsCurrentSession() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val captured = captureSends()

            // mapServerMessages reads AuthManager.getBaseUrl() unconditionally
            // on the REST-success path (mockkObject spy fall-through → real
            // uninitialized AuthManager throws). Stub it — same pattern as
            // stubSession456Rests / E2eIntegrationTest.

            // Stub the transcript fetch explicitly (relaxed mocks return null
            // and muddy the retry path) and capture which session id it is
            // requested with.
            val fetchedSessions = mutableListOf<String>()
            coEvery {
                ApiClient.hermesApi.getSessionMessages(capture(fetchedSessions), any(), any(), any(), any())
            } returns
                retrofit2.Response.success(
                    io.wutz.apollo.data.model
                        .SessionMessagesResponse(messages = emptyList()),
                )

            // /fork sends session.branch_whole keyed on the runtime id.
            viewModel.sendMessage("/fork")
            advanceUntilIdle()

            val branchId = captured.last { it.first == WsMethods.SESSION_BRANCH_WHOLE }.second
            val branchStorage = "branch-storage-1"
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    branchId,
                    mapOf(
                        "session_id" to "branch-runtime-1",
                        "stored_session_id" to branchStorage,
                        "title" to "Branch",
                        "message_count" to 0,
                        "messages" to emptyList<Any>(),
                    ),
                ),
            )
            advanceUntilIdle()

            // The DB key must stay in currentSessionId — storing the runtime
            // id here made every later resume 4007 and the transcript 404.
            assertEquals(branchStorage, viewModel.uiState.value.currentSessionId)
            // And the transcript must be fetched by the storage key, not the
            // runtime registry id (which the gateway 404s on).
            assertTrue(
                "transcript must be fetched by the storage key",
                fetchedSessions.contains(branchStorage),
            )
        }

    @Test
    fun testSessionResume_wsSuccessWaitsForRestRetry() =
        runTest {
            stubSession456Rests(success = true)
            val mockApi = ApiClient.hermesApi
            var restCalls = 0
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
            } coAnswers {
                val call = restCalls++
                if (call < 3) {
                    retrofit2.Response.error(
                        500,
                        okhttp3.ResponseBody.create(null, "{\"detail\":\"boom\"}"),
                    )
                } else {
                    retrofit2.Response.success(
                        io.wutz.apollo.data.model
                            .SessionMessagesResponse(messages = emptyList()),
                    )
                }
            }
            val (viewModel, _) = createViewModelWithSession()
            val captured = captureSends()

            viewModel.switchSession("session-456")
            withTimeout(2_000) { viewModel.uiState.first { it.isResumeRetrying } }

            val resumeId = captured.last { it.first == WsMethods.SESSION_RESUME }.second
            mockEventsFlow.emit(
                WsEvent.RpcResult(resumeId, mapOf("session_id" to "runtime-456")),
            )
            runCurrent()

            assertTrue("WS success must leave the REST retry armed", viewModel.uiState.value.isResumeRetrying)
            assertEquals(1, captured.count { it.first == WsMethods.SESSION_RESUME })

            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isResumeRetrying)
            assertNull(viewModel.uiState.value.resumeError)
            assertEquals(2, captured.count { it.first == WsMethods.SESSION_RESUME })
        }

    @Test
    fun testRetryResumeSession_clearsErrorAndResends() =
        runTest {
            stubSession456Rests(success = true)
            val (viewModel, _) = createViewModelWithSession()
            val captured = captureSends()

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            // Exhaust the retry budget (MAX+1 failures — see boundedRetry test).
            for (cycle in 1..(ChatViewModel.MAX_RESUME_RETRIES + 1)) {
                val resumeId = captured.last { it.first == WsMethods.SESSION_RESUME }.second
                mockEventsFlow.emit(
                    WsEvent.RpcError(
                        resumeId,
                        JsonRpcError(code = -32000, message = "resume rejected"),
                    ),
                )
                advanceUntilIdle()
            }
            assertNotNull(viewModel.uiState.value.resumeError)

            // Manual retry clears the exhausted latch and dispatches a fresh resume.
            viewModel.retryResumeSession()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.resumeError)
            assertFalse(viewModel.uiState.value.isResumeRetrying)
            val resumeSends = captured.count { it.first == WsMethods.SESSION_RESUME }
            assertEquals(ChatViewModel.MAX_RESUME_RETRIES + 2, resumeSends)
        }

    @Test
    fun testSessionReclaimed_forBoundRuntime_invalidatesRuntimeAndOffersRetry() =
        runTest {
            stubSession456Rests(success = true)
            val (viewModel, _) = createViewModelWithSession()
            val captured = captureSends()
            resumeSession456(viewModel, captured)
            assertTrue(viewModel.uiState.value.isSessionReady)
            val resumesBefore = captured.count { it.first == WsMethods.SESSION_RESUME }

            mockEventsFlow.emit(WsEvent.SessionReclaimed("runtime-456", "session-456", "idle_timeout"))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isSessionReady)
            assertNotNull("reclaim must surface the Retry affordance", state.resumeError)
            assertEquals("stored session and history must be kept", "session-456", state.currentSessionId)
            assertEquals(
                "no automatic resume after a reclaim",
                resumesBefore,
                captured.count {
                    it.first ==
                        WsMethods.SESSION_RESUME
                },
            )

            viewModel.retryResumeSession()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.resumeError)
            assertEquals(resumesBefore + 1, captured.count { it.first == WsMethods.SESSION_RESUME })
        }

    @Test
    fun testSessionReclaimed_forOtherOrAmbiguousRuntime_isIgnored() =
        runTest {
            stubSession456Rests(success = true)
            val (viewModel, _) = createViewModelWithSession()
            val captured = captureSends()
            resumeSession456(viewModel, captured)

            // Another runtime, a stored-id-only event, and a conflicting stored id must not touch this chat.
            mockEventsFlow.emit(WsEvent.SessionReclaimed("runtime-other", "session-456", "lru_evict"))
            mockEventsFlow.emit(WsEvent.SessionReclaimed(null, "session-456", "lru_evict"))
            mockEventsFlow.emit(WsEvent.SessionReclaimed("runtime-456", "session-other", "lru_evict"))
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isSessionReady)
            assertNull(viewModel.uiState.value.resumeError)
        }

    @Test
    fun testSessionReclaimed_duplicateIsIdempotent() =
        runTest {
            stubSession456Rests(success = true)
            val (viewModel, _) = createViewModelWithSession()
            val captured = captureSends()
            resumeSession456(viewModel, captured)

            val event = WsEvent.SessionReclaimed("runtime-456", "session-456", "ws_orphan_reap")
            mockEventsFlow.emit(event)
            advanceUntilIdle()
            val afterFirst = viewModel.uiState.value
            mockEventsFlow.emit(event)
            advanceUntilIdle()

            assertEquals(afterFirst.resumeError, viewModel.uiState.value.resumeError)
            assertFalse(viewModel.uiState.value.isSessionReady)
        }

    @Test
    fun testGatewayReconnect_clearsResumeErrorAndRestartsCycle() =
        runTest {
            stubSession456Rests(success = false)
            val (viewModel, _) = createViewModelWithSession()

            viewModel.switchSession("session-456")
            advanceUntilIdle()
            assertNotNull("resumeError must be set after retries exhaust", viewModel.uiState.value.resumeError)

            // Reconnect is a fresh start: the error latch is cleared and the
            // current session is re-resumed on the new socket.
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            runCurrent()

            assertNull(viewModel.uiState.value.resumeError)
            assertFalse(viewModel.uiState.value.isResumeRetrying)

            // The re-resume's own failures start a NEW bounded cycle.
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.resumeError)
        }

    @Test
    fun testInterruptSession_withSessionId_sendsRpc() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.interruptSession()
            advanceUntilIdle()

            verify {
                HermesWsClient.send(
                    WsMethods.SESSION_INTERRUPT,
                    match { it["session_id"] == JsonPrimitive(sessionId) && it.size == 1 },
                    any(),
                )
            }
        }

    @Test
    fun testInterruptSession_withoutSessionId_doesNotSendRpc() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.interruptSession()
            advanceUntilIdle()

            verify(exactly = 0) { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
        }

    private suspend fun TestScope.resumeSession456(
        viewModel: ChatViewModel,
        captured: MutableList<Pair<String, String>>,
    ) {
        viewModel.switchSession("session-456")
        advanceUntilIdle()
        val resumeId = captured.last { it.first == WsMethods.SESSION_RESUME }.second
        mockEventsFlow.emit(WsEvent.RpcResult(resumeId, mapOf("session_id" to "runtime-456")))
        advanceUntilIdle()
    }
}
