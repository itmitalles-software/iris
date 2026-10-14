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

/** ChatViewModel behaviour: Connection setup, RPC results, streaming, clarify, errors and reconnects. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelEventsTest : ChatViewModelTestBase() {
    // ── Connection / init tests ──────────────────────────────────────────────

    @Test
    fun testInitialStateAndConnection() =
        runTest {
            mockConnectionStatus.value = ConnectionStatus.DISCONNECTED

            createViewModel()
            advanceUntilIdle()

            verify { HermesWsClient.connect() }
        }

    @Test
    fun testAlreadyConnectedOnLaunch_createsSession() =
        runTest {
            mockConnectionStatus.value = ConnectionStatus.CONNECTED

            createViewModel()
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.SESSION_LIST, any(), any()) }
            verify { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }
        }

    @Test
    fun testGatewayReady_createsSessionIfNoneExists() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.SESSION_LIST, any(), any()) }
            verify { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }
            assertTrue(viewModel.uiState.value.isConnected)
        }

    @Test
    fun testGatewayReady_doesNotAddConnectionStatusRowsToTranscript() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            // A reconnect re-fires gateway.ready; connection state belongs to the banner, not the transcript.
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            val systemContents =
                viewModel.uiState.value.messages
                    .filter { it.role == MessageRole.SYSTEM }
                    .map { it.content }
            assertFalse(systemContents.toString(), systemContents.any { it == "Connected to Hermes" })
            assertFalse(systemContents.toString(), systemContents.any { it == "Session resumed" })
        }

    @Test
    fun testGatewayReady_withInitialSessionId_switchesToIt() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.initialSessionId = "session-from-notification"

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            assertEquals("session-from-notification", viewModel.uiState.value.currentSessionId)
            verify {
                HermesWsClient.send(
                    WsMethods.SESSION_RESUME,
                    JsonObject(
                        mapOf(
                            "session_id" to JsonPrimitive("session-from-notification"),
                            "source" to JsonPrimitive("desktop"),
                            "omit_messages" to JsonPrimitive(true),
                        ),
                    ),
                    any(),
                )
            }
            // Should NOT create a new session
            verify(inverse = true) { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }
        }

    // ── RPC result tests ─────────────────────────────────────────────────────

    @Test
    fun testSessionCreateRpcResult() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            // GatewayReady sends SESSION_LIST (req-id-1), COMMANDS_CATALOG (req-id-2),
            // then SESSION_CREATE (req-id-3)
            mockEventsFlow.emit(WsEvent.RpcResult("req-id-3", mapOf("session_id" to "session-123")))
            advanceUntilIdle()

            assertEquals("session-123", viewModel.uiState.value.currentSessionId)
            assertFalse(viewModel.uiState.value.isLoading)
            assertEquals(1, viewModel.uiState.value.messages.size)
            assertEquals(
                "Session created",
                viewModel.uiState.value.messages[0]
                    .content,
            )
        }

    @Test
    fun testSessionListRpcResult() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            // GatewayReady sends SESSION_LIST (req-id-1), COMMANDS_CATALOG (req-id-2),
            // then SESSION_CREATE (req-id-3). Emit the SESSION_LIST result.
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    "req-id-1",
                    mapOf(
                        "sessions" to
                            listOf(
                                mapOf(
                                    "id" to "session-123",
                                    "title" to "My Session Title",
                                    "message_count" to 12.0,
                                ),
                            ),
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.sessions.size)
            assertEquals(
                "session-123",
                viewModel.uiState.value.sessions[0]
                    .id,
            )
            assertEquals(
                "My Session Title",
                viewModel.uiState.value.sessions[0]
                    .title,
            )
            assertEquals(
                12,
                viewModel.uiState.value.sessions[0]
                    .messageCount,
            )
        }

    // ── Streaming tests ──────────────────────────────────────────────────────

    @Test
    fun terminalReplyFailureIsScopedDismissibleAndNeverResends() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(WsEvent.MessageToken("Partial", sessionId))
            advanceUntilIdle()
            mockEventsFlow.emit(
                WsEvent.MessageComplete("Other error", "other-session", rawPayload = mapOf("status" to "error")),
            )
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isAgentTyping)
            assertNull(viewModel.uiState.value.replyFailure)
            assertEquals(
                "Partial",
                viewModel.streamingState.value.streamingMessage
                    ?.content,
            )
            mockEventsFlow.emit(
                WsEvent.MessageComplete("Provider error", sessionId, rawPayload = mapOf("status" to "error")),
            )
            advanceUntilIdle()
            val failure = viewModel.uiState.value.replyFailure!!
            assertFalse(viewModel.uiState.value.isAgentTyping)
            assertFalse(viewModel.uiState.value.isThinking)
            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.content == "Partial" },
            )
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.content == "Provider error" },
            )
            viewModel.dismissReplyFailure("stale-card")
            advanceUntilIdle()
            assertEquals(failure, viewModel.uiState.value.replyFailure)
            viewModel.dismissReplyFailure(failure.id)
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.replyFailure)
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun unscopedFailureCannotCrossSessionSwitch() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val failure = WsEvent.MessageComplete("Error", null, rawPayload = mapOf("status" to "error"))
            mockEventsFlow.emit(failure)
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.replyFailure)
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.switchSession("another-session")
            advanceUntilIdle()
            // A delayed start without identity cannot authorize a failed turn on this chat.
            mockEventsFlow.emit(WsEvent.MessageStart(null))
            advanceUntilIdle()
            mockEventsFlow.emit(failure)
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.replyFailure)
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.content == "Error" },
            )
        }

    @Test
    fun unscopedFailureAfterForeignStartCannotUsePreviousSessionPin() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(WsEvent.MessageToken("Current partial", sessionId))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.MessageStart("foreign-session"))
            mockEventsFlow.emit(
                WsEvent.MessageComplete("Foreign diagnostic", null, rawPayload = mapOf("status" to "error")),
            )
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.replyFailure)
            assertEquals(
                "Current partial",
                viewModel.streamingState.value.streamingMessage
                    ?.content,
            )
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.content == "Foreign diagnostic" },
            )
        }

    @Test
    fun testMessageStreamingFlow() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            // 1 — Start: reducer creates streamingMessage and sets isAgentTyping on uiState
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isAgentTyping)
            assertNotNull(viewModel.streamingState.value.streamingMessage)

            // 2 — Thinking
            mockEventsFlow.emit(WsEvent.ThinkingDelta("Thinking...", sessionId))
            advanceUntilIdle()
            assertTrue(viewModel.streamingState.value.isThinking)
            assertEquals("Thinking...", viewModel.streamingState.value.thinkingText)

            // 3 — Deeper thinking
            mockEventsFlow.emit(WsEvent.ThinkingDelta(" deeper", sessionId))
            advanceUntilIdle()
            assertTrue(viewModel.streamingState.value.isThinking)
            assertEquals("Thinking... deeper", viewModel.streamingState.value.thinkingText)

            // 4 — First token (flushed by isTestEnvironment)
            mockEventsFlow.emit(WsEvent.MessageToken("Hello", sessionId))
            advanceUntilIdle()
            assertFalse(viewModel.streamingState.value.isThinking)
            assertEquals(
                "Hello",
                viewModel.streamingState.value.streamingMessage
                    ?.content,
            )

            // 5 — Second token
            mockEventsFlow.emit(WsEvent.MessageToken(" world", sessionId))
            advanceUntilIdle()
            assertEquals(
                "Hello world",
                viewModel.streamingState.value.streamingMessage
                    ?.content,
            )

            // 6 — Complete: reducer finalizes message + resets streamingState
            mockEventsFlow.emit(WsEvent.MessageComplete("Hello world!", sessionId))
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isAgentTyping)
            assertNull(viewModel.streamingState.value.streamingMessage)
            assertEquals(2, viewModel.uiState.value.messages.size)
            assertEquals(
                "Hello world!",
                viewModel.uiState.value.messages[1]
                    .content,
            )
            assertFalse(
                viewModel.uiState.value.messages[1]
                    .isStreaming,
            )
        }

    @Test
    fun testReasoningStreamingFlow() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            // 1 — Reasoning available: block becomes visible (no token yet)
            mockEventsFlow.emit(WsEvent.ReasoningAvailable(sessionId))
            advanceUntilIdle()
            assertTrue(viewModel.streamingState.value.isReasoning)

            // 2 — Reasoning delta
            mockEventsFlow.emit(WsEvent.ReasoningDelta("Let me think", sessionId))
            advanceUntilIdle()
            assertEquals("Let me think", viewModel.streamingState.value.reasoningText)

            // 3 — Deeper reasoning
            mockEventsFlow.emit(WsEvent.ReasoningDelta(" step by step", sessionId))
            advanceUntilIdle()
            assertEquals("Let me think step by step", viewModel.streamingState.value.reasoningText)

            // 4 — Thinking still independent
            mockEventsFlow.emit(WsEvent.ThinkingDelta("thinking", sessionId))
            advanceUntilIdle()
            assertTrue(viewModel.streamingState.value.isThinking)
            assertEquals("thinking", viewModel.streamingState.value.thinkingText)
            // reasoning untouched
            assertEquals("Let me think step by step", viewModel.streamingState.value.reasoningText)

            // 5 — Complete: reducer finalizes message, attaching reasoning
            mockEventsFlow.emit(WsEvent.MessageComplete("The answer is 42", sessionId))
            advanceUntilIdle()

            assertNull(viewModel.streamingState.value.streamingMessage)
            assertEquals(2, viewModel.uiState.value.messages.size)
            assertEquals(
                "The answer is 42",
                viewModel.uiState.value.messages[1]
                    .content,
            )
            // reasoning carried onto the finalized UI message
            assertEquals(
                "Let me think step by step",
                viewModel.uiState.value.messages[1]
                    .reasoningText,
            )
            // reasoning persisted to the entity (survives reload)
            val persisted =
                fakeRepo.dao.getMessagesForSession(sessionId).first { it.role == "ASSISTANT" }
            assertEquals("Let me think step by step", persisted.reasoningText)
        }

    @Test
    fun testToolExecution_sealsInterimTextAndStripsCompletePrefix() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(WsEvent.MessageToken("Calculating sum", sessionId))
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.ToolStart("calculator", mapOf("input" to "2+2")))
            advanceUntilIdle()

            // Issue #842: the interim text is sealed as its own bubble at
            // tool.start (desktop parity), the stream tail clears, and the
            // sealed id is tracked for the complete-prefix strip.
            // messages[0] = "Session created" system message
            assertEquals(
                "Calculating sum",
                viewModel.uiState.value.messages[1]
                    .content,
            )
            assertEquals(
                MessageRole.ASSISTANT,
                viewModel.uiState.value.messages[1]
                    .role,
            )
            assertFalse(
                viewModel.uiState.value.messages[1]
                    .isStreaming,
            )
            assertEquals(
                MessageRole.TOOL,
                viewModel.uiState.value.messages[2]
                    .role,
            )
            assertNull(viewModel.streamingState.value.streamingMessage)
            assertEquals(
                listOf(
                    viewModel.uiState.value.messages[1]
                        .id,
                ),
                viewModel.streamingState.value.sealedOrphanIds,
            )

            // Finalize: the complete payload repeats the sealed commentary as
            // a prefix — the final bubble strips it, so each line appears once.
            mockEventsFlow.emit(WsEvent.MessageComplete("Calculating sum = 4", sessionId))
            advanceUntilIdle()
            val assistant =
                viewModel.uiState.value.messages
                    .filter { it.role == MessageRole.ASSISTANT }
            assertEquals(2, assistant.size)
            assertEquals("Calculating sum", assistant[0].content)
            assertEquals(" = 4", assistant[1].content)
            assertFalse(assistant[1].isStreaming)
            assertNull(viewModel.streamingState.value.streamingMessage)
        }

    @Test
    fun testMessageStart_finalizesPreviousStreamingMessage() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(WsEvent.MessageToken("First part", sessionId))
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(WsEvent.MessageToken("Second part", sessionId))
            advanceUntilIdle()

            // messages[0] = "Session created" system message
            assertEquals(
                "First part",
                viewModel.uiState.value.messages[1]
                    .content,
            )
            assertFalse(
                viewModel.uiState.value.messages[1]
                    .isStreaming,
            )
            assertNotNull(viewModel.streamingState.value.streamingMessage)
            assertEquals(
                "Second part",
                viewModel.streamingState.value.streamingMessage
                    ?.content,
            )
        }

    @Test
    fun testToolExecution_serializesDataAsJson() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(
                WsEvent.ToolStart(
                    name = "calculator",
                    data = mapOf("input" to "2+2", "nested" to mapOf("key" to "value")),
                ),
            )
            advanceUntilIdle()

            assertEquals(
                MessageRole.TOOL,
                viewModel.uiState.value.messages[1]
                    .role,
            )
            assertEquals(
                ToolStatus.RUNNING,
                viewModel.uiState.value.messages[1]
                    .toolStatus,
            )

            mockEventsFlow.emit(
                WsEvent.ToolComplete("calculator", mapOf("result" to "4", "exit_code" to 0)),
            )
            advanceUntilIdle()

            assertEquals(
                ToolStatus.COMPLETED,
                viewModel.uiState.value.messages[1]
                    .toolStatus,
            )
        }

    // ── Clarify tests ────────────────────────────────────────────────────────

    @Test
    fun mainTurnBusyTracksRuntimeEventsNotClarificationTyping() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            assertFalse(viewModel.uiState.value.isMainTurnBusy)

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isMainTurnBusy)

            mockEventsFlow.emit(WsEvent.MessageComplete("Done", sessionId))
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isMainTurnBusy)

            mockEventsFlow.emit(
                WsEvent.ClarifyRequest(
                    text = "Continue?",
                    options = listOf("Yes"),
                    clarifyId = "clarify-busy-state",
                    sessionId = sessionId,
                ),
            )
            advanceUntilIdle()
            viewModel.respondToClarify("Yes")
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isAgentTyping)
            assertFalse(viewModel.uiState.value.isMainTurnBusy)
        }

    @Test
    fun testClarifyRequestAndRespond() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(WsEvent.ClarifyRequest("Please choose:", listOf("Yes", "No"), "clarify-123"))
            advanceUntilIdle()

            assertEquals(
                "Please choose:",
                viewModel.uiState.value.clarifyRequest
                    ?.text,
            )
            assertEquals(
                listOf("Yes", "No"),
                viewModel.uiState.value.clarifyRequest
                    ?.options,
            )
            assertEquals(
                "clarify-123",
                viewModel.uiState.value.clarifyRequest
                    ?.clarifyId,
            )

            viewModel.respondToClarify("Yes")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.clarifyRequest)
            assertEquals(2, viewModel.uiState.value.messages.size)

            verify {
                HermesWsClient.send(
                    method = WsMethods.CLARIFY_RESPOND,
                    params =
                        mapOf(
                            "session_id" to sessionId,
                            "response" to "Yes",
                            "answer" to "Yes",
                            "clarify_id" to "clarify-123",
                            "request_id" to "clarify-123",
                        ),
                    onSent = any(),
                )
            }
        }

    @Test
    fun testClarifyRequestWithQuestionIdAndRespond() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(
                WsEvent.ClarifyRequest(
                    text = "Pick one:",
                    options = listOf("Option 1"),
                    clarifyId = "clarify-batch-1",
                    sessionId = sessionId,
                    questionId = "q0",
                ),
            )
            advanceUntilIdle()

            assertEquals(
                "Pick one:",
                viewModel.uiState.value.clarifyRequest
                    ?.text,
            )
            assertEquals(
                "q0",
                viewModel.uiState.value.clarifyRequest
                    ?.questionId,
            )

            viewModel.respondToClarify("Option 1")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.clarifyRequest)
            verify {
                HermesWsClient.send(
                    method = WsMethods.CLARIFY_RESPOND,
                    params =
                        mapOf(
                            "session_id" to sessionId,
                            "response" to "Option 1",
                            "answer" to "Option 1",
                            "clarify_id" to "clarify-batch-1",
                            "request_id" to "clarify-batch-1",
                            "question_id" to "q0",
                        ),
                    onSent = any(),
                )
            }
        }

    @Test
    fun testClarifyBatch_respondsToAllQuestions() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(
                WsEvent.ClarifyRequest(
                    text = "1. Pick A\n\n2. Pick B",
                    options = listOf("A1", "A2"),
                    clarifyId = "clarify-batch-multi",
                    sessionId = sessionId,
                    questionId = "q0",
                    questions =
                        listOf(
                            WsEvent.ClarifyQuestion(
                                qid = "q0",
                                question = "Pick A",
                                choices = listOf("A1", "A2"),
                                multiSelect = false,
                            ),
                            WsEvent.ClarifyQuestion(
                                qid = "q1",
                                question = "Pick B",
                                choices = listOf("B1", "B2"),
                                multiSelect = true,
                            ),
                        ),
                ),
            )
            advanceUntilIdle()

            val clarify = viewModel.uiState.value.clarifyRequest
            assertNotNull(clarify)
            assertEquals(2, clarify?.resolvedQuestions?.size)

            viewModel.respondToClarifyBatch(
                mapOf(
                    "q0" to "A1",
                    "q1" to "B1, B2",
                ),
            )
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.clarifyRequest)
            verify {
                HermesWsClient.send(
                    method = WsMethods.CLARIFY_RESPOND,
                    params =
                        mapOf(
                            "session_id" to sessionId,
                            "response" to "A1",
                            "answer" to "A1",
                            "question_id" to "q0",
                            "clarify_id" to "clarify-batch-multi",
                            "request_id" to "clarify-batch-multi",
                        ),
                    onSent = any(),
                )
            }
            verify {
                HermesWsClient.send(
                    method = WsMethods.CLARIFY_RESPOND,
                    params =
                        mapOf(
                            "session_id" to sessionId,
                            "response" to "B1, B2",
                            "answer" to "B1, B2",
                            "question_id" to "q1",
                            "clarify_id" to "clarify-batch-multi",
                            "request_id" to "clarify-batch-multi",
                        ),
                    onSent = any(),
                )
            }
        }

    @Test
    fun testServerRequestClarifyReplay_restoresLockedAnswersAndMergesRemainingAnswer() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(
                WsEvent.ServerRequest(
                    id = "srq-clarify-replay",
                    method = "clarify",
                    params =
                        mapOf(
                            "session_id" to sessionId,
                            "questions" to
                                listOf(
                                    mapOf("qid" to "q0", "question" to "First?", "choices" to listOf("yes")),
                                    mapOf("qid" to "q1", "question" to "Second?", "choices" to emptyList<String>()),
                                ),
                            "answers" to mapOf("q0" to "yes", "invalid" to 42),
                        ),
                    replayed = true,
                ),
            )
            advanceUntilIdle()

            val clarify = viewModel.uiState.value.clarifyRequest
            assertEquals("srq-clarify-replay", clarify?.serverRequestId)
            assertEquals(mapOf("q0" to "yes"), clarify?.lockedAnswers)
            assertEquals(2, clarify?.resolvedQuestions?.size)

            viewModel.respondToClarifyBatch(mapOf("q0" to "", "q1" to "new answer"))
            advanceUntilIdle()

            verify {
                HermesWsClient.respondToServerRequest(
                    "srq-clarify-replay",
                    withArg { result ->
                        val answers = result.jsonObject["answers"]?.jsonObject
                        assertEquals("yes", answers?.get("q0")?.jsonPrimitive?.content)
                        assertEquals("new answer", answers?.get("q1")?.jsonPrimitive?.content)
                        assertFalse(answers?.containsKey("invalid") == true)
                    },
                )
            }
        }

    @Test
    fun serverRequestCancel_clearsOnlyMatchingClarifyPrompt() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(
                WsEvent.ServerRequest(
                    id = "srq-clarify-cancel",
                    method = "clarify",
                    params =
                        mapOf(
                            "session_id" to sessionId,
                            "question" to "Continue?",
                        ),
                ),
            )
            advanceUntilIdle()
            assertEquals(
                "srq-clarify-cancel",
                viewModel.uiState.value.clarifyRequest
                    ?.serverRequestId,
            )

            mockEventsFlow.emit(
                WsEvent.ServerRequestCancelled(
                    id = "srq-other",
                    method = "clarify",
                    reason = "timeout",
                    sessionId = sessionId,
                ),
            )
            advanceUntilIdle()
            assertEquals(
                "srq-clarify-cancel",
                viewModel.uiState.value.clarifyRequest
                    ?.serverRequestId,
            )

            mockEventsFlow.emit(
                WsEvent.ServerRequestCancelled(
                    id = "srq-clarify-cancel",
                    method = "clarify",
                    reason = "timeout",
                    sessionId = sessionId,
                ),
            )
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.clarifyRequest)
        }

    @Test
    fun testClarifyRequestCustomResponse() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(WsEvent.ClarifyRequest("Please explain:", emptyList(), "clarify-456"))
            advanceUntilIdle()

            assertEquals(
                "Please explain:",
                viewModel.uiState.value.clarifyRequest
                    ?.text,
            )
            assertTrue(
                viewModel.uiState.value.clarifyRequest
                    ?.options
                    ?.isEmpty() == true,
            )

            viewModel.respondToClarify("This is my custom response text")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.clarifyRequest)
            assertEquals(2, viewModel.uiState.value.messages.size)
            assertEquals(
                "This is my custom response text",
                viewModel.uiState.value.messages[1]
                    .content,
            )
            assertEquals(
                MessageRole.USER,
                viewModel.uiState.value.messages[1]
                    .role,
            )

            verify {
                HermesWsClient.send(
                    WsMethods.CLARIFY_RESPOND,
                    params =
                        mapOf(
                            "session_id" to sessionId,
                            "response" to "This is my custom response text",
                            "answer" to "This is my custom response text",
                            "clarify_id" to "clarify-456",
                            "request_id" to "clarify-456",
                        ),
                    onSent = any(),
                )
            }
        }

    @Test
    fun testClarifyDismissInformsAgent() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(
                WsEvent.ClarifyRequest(
                    "Please choose:",
                    listOf("Yes", "No"),
                    "clarify-789",
                ),
            )
            advanceUntilIdle()
            assertEquals(
                "clarify-789",
                viewModel.uiState.value.clarifyRequest
                    ?.clarifyId,
            )

            viewModel.dismissClarify()
            advanceUntilIdle()

            // Dialog dismissed locally
            assertNull(viewModel.uiState.value.clarifyRequest)
            // Baseline has 1 "Session created" marker; dismiss adds exactly
            // ONE system note and must NOT fake a user bubble.
            val messages = viewModel.uiState.value.messages
            assertEquals(2, messages.size)
            assertEquals(MessageRole.SYSTEM, messages[0].role) // pre-existing "Session created"
            assertEquals(MessageRole.SYSTEM, messages[1].role) // dismiss trace
            assertTrue(messages[1].content.contains("dismissed", ignoreCase = true))

            verify {
                HermesWsClient.send(
                    WsMethods.CLARIFY_RESPOND,
                    params =
                        mapOf(
                            "session_id" to sessionId,
                            "response" to "The user cancelled — no answer provided.",
                            "answer" to "The user cancelled — no answer provided.",
                            "clarify_id" to "clarify-789",
                            "request_id" to "clarify-789",
                        ),
                    onSent = any(),
                )
            }
        }

    @Test
    fun testClarifyDismissWithQuestionIdInformsAgent() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(
                WsEvent.ClarifyRequest(
                    "Please choose:",
                    listOf("Yes", "No"),
                    "clarify-789",
                    sessionId,
                    questionId = "q0",
                ),
            )
            advanceUntilIdle()
            assertEquals(
                "clarify-789",
                viewModel.uiState.value.clarifyRequest
                    ?.clarifyId,
            )
            assertEquals(
                "q0",
                viewModel.uiState.value.clarifyRequest
                    ?.questionId,
            )

            viewModel.dismissClarify()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.clarifyRequest)
            verify {
                HermesWsClient.send(
                    method = WsMethods.CLARIFY_RESPOND,
                    params =
                        mapOf(
                            "session_id" to sessionId,
                            "response" to "The user cancelled — no answer provided.",
                            "answer" to "The user cancelled — no answer provided.",
                            "clarify_id" to "clarify-789",
                            "request_id" to "clarify-789",
                            "question_id" to "q0",
                        ),
                    onSent = any(),
                )
            }
        }

    // ── Error handling ───────────────────────────────────────────────────────

    @Test
    fun testRpcErrorHandling() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.RpcError(
                    "req-id-1",
                    JsonRpcError(code = -32603, message = "Internal error during creation"),
                ),
            )
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.errorMessage!!
                    .en()
                    .contains("Internal error during creation"),
            )
        }

    // ── Session mismatch ─────────────────────────────────────────────────────

    @Test
    fun testSessionMismatchEventsAreIgnored() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(
                WsEvent.ToolStart(name = "calculator", data = mapOf("input" to "2+2"), sessionId = "session-other"),
            )
            mockEventsFlow.emit(
                WsEvent.ClarifyRequest(
                    text = "Choose:",
                    options = listOf("Yes"),
                    clarifyId = "clarify-1",
                    sessionId = "session-other",
                ),
            )
            mockEventsFlow.emit(WsEvent.MessageStart("session-other"))
            mockEventsFlow.emit(WsEvent.MessageToken("Hello", "session-other"))
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.messages.size)
            assertEquals(
                "Session created",
                viewModel.uiState.value.messages[0]
                    .content,
            )
            assertNull(viewModel.streamingState.value.streamingMessage)
            assertNull(viewModel.uiState.value.clarifyRequest)
        }

    // ── Reconnect ────────────────────────────────────────────────────────────

    @Test
    fun testReconnectDoesNotDuplicateEventCollection() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.reconnect()
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(WsEvent.MessageToken("Hello", sessionId))
            advanceUntilIdle()

            assertEquals(
                "Hello",
                viewModel.streamingState.value.streamingMessage
                    ?.content,
            )
        }

    // ── MessageComplete without streaming ────────────────────────────────────

    @Test
    fun testMessageCompleteWithoutStreaming_upsertsAssistantMessage() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(WsEvent.MessageComplete("Fully complete message", sessionId))
            advanceUntilIdle()

            assertEquals(2, viewModel.uiState.value.messages.size)
            assertEquals(
                "Fully complete message",
                viewModel.uiState.value.messages[1]
                    .content,
            )
            assertEquals(
                MessageRole.ASSISTANT,
                viewModel.uiState.value.messages[1]
                    .role,
            )
        }

    @Test
    fun `reconnect with unpersisted session re-creates session on new socket and dispatches queued prompt`() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            advanceUntilIdle()

            // Session has no server presence yet (fresh session, no prompt sent).
            // Simulate reconnect:
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            // On reconnect, an unpersisted session triggers createNewSession to mint a valid session on the new socket
            verify(atLeast = 2) { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }

            val sentPrompts = mutableListOf<String>()
            every { HermesWsClient.sendMessage(any(), capture(sentPrompts), any(), any()) } returns "req-send-2"

            val createReqId = sentRequestMethods.last { it.first == WsMethods.SESSION_CREATE }.second
            vm.sendMessage("Prompt typed during reconnect")
            advanceUntilIdle()

            // Optimistic message in UI
            assertTrue(
                vm.uiState.value.messages
                    .any { it.content == "Prompt typed during reconnect" },
            )

            // Complete the new SESSION_CREATE RPC
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    createReqId,
                    mapOf("session_id" to "session-reconnected", "stored_session_id" to "storage-reconnected"),
                ),
            )
            advanceUntilIdle()

            assertTrue(sentPrompts.contains("Prompt typed during reconnect"))
        }
}
