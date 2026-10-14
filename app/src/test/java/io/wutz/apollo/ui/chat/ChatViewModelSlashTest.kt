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
import io.wutz.apollo.share.SharedContent
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

/** ChatViewModel behaviour: Slash commands typed into the composer, usage ranking and /resume. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelSlashTest : ChatViewModelTestBase() {
    // ── Slash command tests ──────────────────────────────────────────────────

    @Test
    fun blockedCommand_feedbackCannotPersistAheadOfVisibleCommand() =
        runTest(testDispatcher) {
            val commandEntered = CompletableDeferred<Unit>()
            val releaseCommand = CompletableDeferred<Unit>()
            var gateNextWrite = false
            val repository =
                ChatPersistenceRepository {
                    if (gateNextWrite) {
                        gateNextWrite = false
                        commandEntered.complete(Unit)
                        releaseCommand.await()
                    }
                    fakeRepo.dao
                }
            val (viewModel, sessionId) = createViewModelWithSession(repository = repository)
            val baselineCount = fakeRepo.dao.count()
            gateNextWrite = true

            viewModel.sendMessage("/clear")
            runCurrent()
            commandEntered.await()

            val visible =
                viewModel.uiState.value.messages
                    .takeLast(2)
            assertEquals(listOf("/clear", "/clear is not supported on mobile"), visible.map { it.content })
            // The first DAO access is deliberately suspended. A second local write must
            // not overtake it and acquire the earlier physical sort order.
            assertEquals(baselineCount, fakeRepo.dao.count())

            releaseCommand.complete(Unit)
            advanceUntilIdle()
            val rows = fakeRepo.dao.getMessagesForSession(sessionId)
            assertEquals(baselineCount + 2, rows.size)
            assertEquals(visible.map { it.id }, rows.takeLast(2).map { it.id })
            val restored = repository.loadMessages(sessionId)
            assertEquals(visible.map { it.id }, restored.takeLast(2).map { it.id })
            assertEquals(visible.map { it.content }, restored.takeLast(2).map { it.content })
        }

    @Test
    fun testSlashCommand_help_addsHelpMessage() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            every {
                HermesWsClient.request(WsMethods.SLASH_EXEC, any(), any(), true)
            } returns
                CompletableDeferred(
                    mapOf("type" to "exec", "output" to "**Available Commands:**\n\u2022 `/status`\n\u2022 `/new`"),
                )

            viewModel.sendMessage("/help")
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.content.contains("Available Commands") },
            )
            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.content.contains("/status") },
            )
        }

    @Test
    fun testSlashCommand_new_createsNewSession() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/new")
            advanceUntilIdle()

            verify(atLeast = 1) { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }
        }

    @Test
    fun testSlashCommand_new_inBotChat_compactsInsteadOfCreatingSession() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            // Set session title to "Bot Chat" via SESSION_LIST result
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    id = "req-id-1",
                    result =
                        mapOf(
                            "sessions" to
                                listOf(
                                    mapOf(
                                        "id" to sessionId,
                                        "title" to "Bot Chat",
                                        "message_count" to 5.0,
                                    ),
                                ),
                        ),
                ),
            )
            advanceUntilIdle()

            // Reset recording on HermesWsClient to only inspect calls after /new
            clearMocks(HermesWsClient, answers = false, recordedCalls = true)

            viewModel.sendMessage("/new")
            advanceUntilIdle()

            // In Bot Chat, /new should NOT create a fresh session, but instead dispatch /compact
            verify(exactly = 0) {
                HermesWsClient.send(
                    WsMethods.SESSION_CREATE,
                    any(),
                    any(),
                )
            }
            assertTrue(
                viewModel.uiState.value.messages.any {
                    it.content.contains("compacting instead")
                },
            )
        }

    @Test
    fun profileSwitch_wipesOpenSessionForFreshStart() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            assertEquals(sessionId, viewModel.uiState.value.currentSessionId)

            // Profile switch fires → chat wipes the stale conversation. The
            // re-dialed socket's gateway.ready then auto-creates a FRESH
            // session in the new profile (handleGatewayReady, desktop
            // requestFreshSession parity).
            mockSwitchFlow.emit("meow")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.currentSessionId)
            assertEquals("Hermes", viewModel.uiState.value.chatTitle)
        }

    @Test
    fun profileSwitch_endToEnd_recreatesFreshSessionInNewProfile() =
        runTest {
            val (viewModel, oldSessionId) = createViewModelWithSession()
            assertEquals(oldSessionId, viewModel.uiState.value.currentSessionId)

            // 1. Switch fires → stale conversation wiped.
            mockSwitchFlow.emit("meow")
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.currentSessionId)

            // 2. The coordinator re-dials the socket: disconnect first (status
            //    transition), then gateway.ready → handleGatewayReady:
            //    loadSessions (req-id-4), fetchCommandCatalog (req-id-5),
            //    then createNewSession (req-id-6) — desktop requestFreshSession.
            //    (The first ready cycle consumed req-ids 1-3.)
            mockConnectionStatus.value = ConnectionStatus.DISCONNECTED
            advanceUntilIdle()
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            // 3. Fresh session created → a NEW session id, not the old one.
            //    createNewSession is the LAST WS send of the ready cycle, so
            //    its id is the current counter value (robust against extra
            //    sends from the reconnect path).
            mockEventsFlow.emit(
                WsEvent.RpcResult("req-id-$reqCount", mapOf("session_id" to "session-meow")),
            )
            advanceUntilIdle()

            assertEquals("session-meow", viewModel.uiState.value.currentSessionId)
            assertTrue(viewModel.uiState.value.currentSessionId != oldSessionId)
            // The fresh session create goes through send() → WsProfileParams
            // injects the active profile (the WS profile-scoping seam).
            verify(atLeast = 1) { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }
        }

    @Test
    fun testSlashCommand_fork_sendsSessionBranch() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            val captured = mutableListOf<Pair<String, Map<String, Any>>>()
            every { HermesWsClient.send(any(), any(), any()) } answers {
                val id = "req-${captured.size + 1}"
                captured.add(arg<String>(0) to (arg<Map<String, Any>>(1)))
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            // /fork with an optional branch title sends session.branch_whole.
            viewModel.sendMessage("/fork my-fork")
            advanceUntilIdle()

            val branchSent = captured.firstOrNull { it.first == WsMethods.SESSION_BRANCH_WHOLE }
            assertNotNull("session.branch_whole should be dispatched for /fork", branchSent)
            assertEquals(JsonPrimitive(sessionId), branchSent!!.second["session_id"])
            assertEquals(JsonPrimitive("my-fork"), branchSent.second["name"])
        }

    @Test
    fun testSessionBranch_publishesRuntimeSessionId() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            every { HermesWsClient.send(any(), any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("branch-request")
                "branch-request"
            }

            viewModel.sendMessage("/fork")
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    "branch-request",
                    mapOf(
                        "session_id" to "runtime-branch",
                        "stored_session_id" to "stored-branch",
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals("runtime-branch", ActiveSessionHolder.activeSessionId.value)
        }

    @Test
    fun testSlashCommand_fork_withoutName_omitsNameParam() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            val captured = mutableListOf<Pair<String, Map<String, Any>>>()
            every { HermesWsClient.send(any(), any(), any()) } answers {
                val id = "req-${captured.size + 1}"
                captured.add(arg<String>(0) to (arg<Map<String, Any>>(1)))
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            viewModel.sendMessage("/fork")
            advanceUntilIdle()

            val branchSent = captured.firstOrNull { it.first == WsMethods.SESSION_BRANCH_WHOLE }
            assertNotNull("session.branch_whole should be dispatched for /fork", branchSent)
            assertEquals(JsonPrimitive(sessionId), branchSent!!.second["session_id"])
            assertFalse("name param should be omitted when no title given", branchSent.second.containsKey("name"))
        }

    @Test
    fun testSlashCommand_fork_branchWholeSuccess_handledLikeSessionBranch() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val captured = mutableListOf<Pair<String, Map<String, Any>>>()
            every { HermesWsClient.send(any(), any(), any()) } answers {
                val id = "req-${captured.size + 1}"
                captured.add(arg<String>(0) to (arg<Map<String, Any>>(1)))
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            viewModel.sendMessage("/fork")
            advanceUntilIdle()

            val branchWholeSent = captured.firstOrNull { it.first == WsMethods.SESSION_BRANCH_WHOLE }
            assertNotNull("session.branch_whole dispatched", branchWholeSent)

            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    "req-1",
                    mapOf(
                        "session_id" to "runtime-branch-whole",
                        "stored_session_id" to "stored-branch-whole",
                        "title" to "Whole Branch",
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals("runtime-branch-whole", ActiveSessionHolder.activeSessionId.value)
            assertEquals("stored-branch-whole", viewModel.uiState.value.currentSessionId)
            assertTrue(
                "System message 'Session branched' should be added",
                viewModel.uiState.value.messages
                    .any { it.content.contains("Session branched") },
            )
            // No fallback was sent
            assertEquals(0, captured.count { it.first == WsMethods.SESSION_BRANCH })
            assertEquals(1, captured.count { it.first == WsMethods.SESSION_BRANCH_WHOLE })
        }

    @Test
    fun testSlashCommand_fork_branchWholeError32601_retriesWithSessionBranch() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val captured = mutableListOf<Pair<String, Map<String, Any>>>()
            every { HermesWsClient.send(any(), any(), any()) } answers {
                val id = "req-${captured.size + 1}"
                captured.add(arg<String>(0) to (arg<Map<String, Any>>(1)))
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            viewModel.sendMessage("/fork my-retry-branch")
            advanceUntilIdle()

            val branchWholeSent = captured.firstOrNull { it.first == WsMethods.SESSION_BRANCH_WHOLE }
            assertNotNull("session.branch_whole should be sent", branchWholeSent)
            assertEquals(JsonPrimitive("my-retry-branch"), branchWholeSent!!.second["name"])
            assertEquals(JsonPrimitive(sessionId), branchWholeSent.second["session_id"])

            // Backend answers -32601 unknown method
            mockEventsFlow.emit(
                WsEvent.RpcError(
                    "req-1",
                    JsonRpcError(code = -32601, message = "unknown method: session.branch_whole"),
                ),
            )
            advanceUntilIdle()

            // Retries ONCE with session.branch using same params
            val branchSent = captured.firstOrNull { it.first == WsMethods.SESSION_BRANCH }
            assertNotNull("session.branch fallback should be sent", branchSent)
            assertEquals(JsonPrimitive("my-retry-branch"), branchSent!!.second["name"])
            assertEquals(JsonPrimitive(sessionId), branchSent.second["session_id"])
            assertEquals(1, captured.count { it.first == WsMethods.SESSION_BRANCH })

            // session.branch result is then handled
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    "req-2",
                    mapOf(
                        "session_id" to "runtime-branch-fallback",
                        "stored_session_id" to "stored-branch-fallback",
                        "title" to "my-retry-branch",
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals("runtime-branch-fallback", ActiveSessionHolder.activeSessionId.value)
            assertEquals("stored-branch-fallback", viewModel.uiState.value.currentSessionId)
            assertTrue(
                "System message 'Session branched' should be added",
                viewModel.uiState.value.messages
                    .any { it.content.contains("Session branched") },
            )
        }

    @Test
    fun testSlashCommand_fork_branchWholeOtherError_noFallback() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val captured = mutableListOf<Pair<String, Map<String, Any>>>()
            every { HermesWsClient.send(any(), any(), any()) } answers {
                val id = "req-${captured.size + 1}"
                captured.add(arg<String>(0) to (arg<Map<String, Any>>(1)))
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            viewModel.sendMessage("/fork")
            advanceUntilIdle()

            val branchWholeSent = captured.firstOrNull { it.first == WsMethods.SESSION_BRANCH_WHOLE }
            assertNotNull("session.branch_whole should be sent", branchWholeSent)

            // Backend answers with code other than -32601
            mockEventsFlow.emit(
                WsEvent.RpcError(
                    "req-1",
                    JsonRpcError(code = -32600, message = "invalid request"),
                ),
            )
            advanceUntilIdle()

            // No retry/fallback sent
            assertEquals(0, captured.count { it.first == WsMethods.SESSION_BRANCH })
            assertTrue(
                viewModel.uiState.value.errorMessage
                    ?.en()
                    ?.contains("session.branch_whole") == true,
            )
        }

    @Test
    fun testSlashCommand_stop_sendsInterrupt() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/stop")
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
        }

    @Test
    fun testSlashCommand_stop_alsoKillsBackgroundProcesses() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            coEvery { HermesWsClient.call(RpcMethods.PROCESS_STOP, any(), any(), any()) } returns
                ProcessStopResult(killed = 2)

            viewModel.sendMessage("/stop")
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
            coVerify(exactly = 1) { HermesWsClient.call(RpcMethods.PROCESS_STOP, ProcessStopParams, any(), any()) }
            val contents =
                viewModel.uiState.value.messages
                    .map { it.content }
            assertTrue(contents.contains("Stopped 2 background processes."))
        }

    @Test
    fun testSlashCommand_interrupt_doesNotKillBackgroundProcesses() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            viewModel.sendMessage("/interrupt")
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
            coVerify(exactly = 0) { HermesWsClient.call(RpcMethods.PROCESS_STOP, any(), any(), any()) }
        }

    @Test
    fun testSlashCommand_stop_reportsProcessStopFailure() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            coEvery { HermesWsClient.call(RpcMethods.PROCESS_STOP, any(), any(), any()) } throws
                IllegalStateException("boom")

            viewModel.sendMessage("/stop")
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.content == "Could not stop background processes: boom" },
            )
        }

    @Test
    fun testSlashCommand_stop_andInterruptedNoticeStayInPlaceAcrossSync() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            // 1. Initial messages in UI state
            val m0 = ChatMessage(id = "rest-$sessionId-0", role = MessageRole.USER, content = "start task")
            val m1 = ChatMessage(id = "rest-$sessionId-1", role = MessageRole.ASSISTANT, content = "running...")
            val stateField =
                ChatViewModel::class.java
                    .getDeclaredField("_uiState")
                    .apply { isAccessible = true }

            @Suppress("UNCHECKED_CAST")
            val state = stateField.get(viewModel) as MutableStateFlow<ChatUiState>
            state.value = viewModel.uiState.value.copy(messages = listOf(m0, m1))

            every {
                HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any())
            } answers {
                val onSent = thirdArg<((String) -> Unit)?>()
                onSent?.invoke("interrupt-req-1")
                "interrupt-req-1"
            }

            // 2. User sends /stop
            viewModel.sendMessage("/stop")
            advanceUntilIdle()

            // 3. WS returns interrupt success -> adds "Session interrupted"
            mockEventsFlow.emit(WsEvent.RpcResult("interrupt-req-1", mapOf("status" to "interrupted")))
            advanceUntilIdle()

            // 4. Server transcript sync arrives with newer turns
            val serverRows =
                listOf(
                    io.wutz.apollo.data.model.SessionMessage(
                        id = 0,
                        role = "user",
                        content = kotlinx.serialization.json.JsonPrimitive("start task"),
                        timestamp = kotlinx.serialization.json.JsonPrimitive(1),
                    ),
                    io.wutz.apollo.data.model.SessionMessage(
                        id = 1,
                        role = "assistant",
                        content = kotlinx.serialization.json.JsonPrimitive("running..."),
                        timestamp = kotlinx.serialization.json.JsonPrimitive(2),
                    ),
                    io.wutz.apollo.data.model.SessionMessage(
                        id = 2,
                        role = "user",
                        content = kotlinx.serialization.json.JsonPrimitive("next prompt"),
                        timestamp = kotlinx.serialization.json.JsonPrimitive(3),
                    ),
                    io.wutz.apollo.data.model.SessionMessage(
                        id = 3,
                        role = "assistant",
                        content = kotlinx.serialization.json.JsonPrimitive("next reply"),
                        timestamp = kotlinx.serialization.json.JsonPrimitive(4),
                    ),
                )

            // Simulate sync/merge with serverRows
            val current = viewModel.uiState.value.messages
            val merged =
                io.wutz.apollo.ui.chat.mergeTranscriptWithLive(
                    io.wutz.apollo.ui.chat
                        .mapServerMessages(sessionId, serverRows, 0, true, current),
                    current,
                    chronological = true,
                    preserveLiveIds = true,
                )

            val contents = merged.map { it.content }
            assertEquals(
                listOf(
                    "start task",
                    "running...",
                    "/stop",
                    "No background processes to stop.",
                    "Session interrupted",
                    "next prompt",
                    "next reply",
                ),
                contents,
            )
        }

    @Test
    fun testSlashCommand_interrupt_sendsInterrupt() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/interrupt")
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
        }

    @Test
    fun testSlashCommand_btw_dispatchesPromptBtw_andSetsBtwState() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            every {
                HermesWsClient.request(WsMethods.PROMPT_BTW, any(), any())
            } returns
                CompletableDeferred(
                    mapOf("task_id" to "btw_123456"),
                )

            viewModel.sendMessage("/btw which file was that in?")
            advanceUntilIdle()

            val btw = viewModel.uiState.value.btwState
            assertNotNull(btw)
            assertEquals("which file was that in?", btw?.question)
            assertEquals("btw_123456", btw?.taskId)
            assertTrue(btw?.isLoading == true)
            verify {
                HermesWsClient.request(
                    WsMethods.PROMPT_BTW,
                    JsonObject(
                        mapOf(
                            "session_id" to JsonPrimitive(sessionId),
                            "text" to JsonPrimitive("which file was that in?"),
                        ),
                    ),
                    any(),
                )
            }
            // Ensure transcript messages were NOT polluted with the side question
            assertTrue(
                viewModel.uiState.value.messages
                    .none { it.content.contains("which file was that in?") },
            )
        }

    // ── Slash usage ranking (issue #865) ────────────────────────────────────

    @Test
    fun slashUsage_dispatchIncrementsCountPerCommand() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            every {
                HermesWsClient.request(WsMethods.COMMAND_DISPATCH, any(), any())
            } returns
                CompletableDeferred(
                    mapOf("type" to "exec", "output" to "ok"),
                )

            viewModel.sendMessage("/help")
            advanceUntilIdle()
            viewModel.sendMessage("/help")
            advanceUntilIdle()
            viewModel.sendMessage("/new")
            advanceUntilIdle()

            assertEquals(2, viewModel.uiState.value.slashUsageCounts["/help"])
            assertEquals(1, viewModel.uiState.value.slashUsageCounts["/new"])
        }

    @Test
    fun slashUsage_blockedCommand_notCounted() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/clear")
            advanceUntilIdle()

            // A blocklisted command can never dispatch — it must not climb
            // the autocomplete ranking either.
            assertTrue(
                viewModel.uiState.value.slashUsageCounts
                    .isEmpty(),
            )
            verify(exactly = 0) {
                HermesWsClient.request(WsMethods.COMMAND_DISPATCH, any(), any())
            }
        }

    // ── /resume · /history open the history tab (issue #864) ────────────────

    @Test
    fun slashCommand_resume_requestsHistoryNavigationWithoutGateway() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/resume")
            advanceUntilIdle()

            assertTrue(
                "openHistoryRequested must be set for the screen to navigate",
                viewModel.uiState.value.openHistoryRequested,
            )
            // Client-side only: no gateway round-trip for /resume.
            verify(exactly = 0) {
                HermesWsClient.request(WsMethods.COMMAND_DISPATCH, any(), any())
            }
            verify(exactly = 0) {
                HermesWsClient.request(WsMethods.SLASH_EXEC, any(), any())
            }
        }

    @Test
    fun slashCommand_history_requestsHistoryNavigation() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/history")
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.openHistoryRequested)
            verify(exactly = 0) {
                HermesWsClient.request(WsMethods.COMMAND_DISPATCH, any(), any())
            }
        }

    @Test
    fun consumeOpenHistoryRequest_clearsFlag() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/resume")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.openHistoryRequested)

            viewModel.consumeOpenHistoryRequest()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.openHistoryRequested)
        }

    @Test
    fun testSlashCommand_unknown_showsErrorMessage() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            var dispatchReqId = "dispatch-unk"

            every { HermesWsClient.send(WsMethods.COMMAND_DISPATCH, any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke(dispatchReqId)
                dispatchReqId
            }

            viewModel.sendMessage("/nonexistent")
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.RpcError(
                    dispatchReqId,
                    JsonRpcError(code = -32601, message = "Unknown command: nonexistent"),
                ),
            )
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.errorMessage
                    ?.en()
                    ?.contains("Unknown command") == true,
            )
        }

    @Test
    fun testSlashCommandStatusRoutesToSlash() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/status")
            advanceUntilIdle()

            verify { HermesWsClient.request(WsMethods.SLASH_EXEC, any(), any(), true) }
        }

    @Test
    fun testSlashCommandSessionsRoutesToSlash() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/sessions")
            advanceUntilIdle()

            verify { HermesWsClient.request(WsMethods.SLASH_EXEC, any(), any(), true) }
        }

    @Test
    fun testSlashCommandStatsRoutesToSlash() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/stats")
            advanceUntilIdle()

            verify { HermesWsClient.request(WsMethods.SLASH_EXEC, any(), any(), true) }
        }

    @Test
    fun testBareModelCommand_opensPickerInsteadOfDispatch() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            // The shared catalog store may still hold another class's cached
            // (possibly empty) state — reset it so the picker's load below
            // fetches through this class's stubbed API.
            ModelCatalogStore.shared.onScopeChanged(
                DataScope("preload-reset", "http://preload-reset.test/", AuthManager.DEFAULT_PROFILE_ID),
            )

            // A bare "/model" must NOT dispatch a slash command; it opens the picker.
            viewModel.sendMessage("/model")
            advanceUntilIdle()

            assertTrue(
                "picker should be shown when bare /model is typed",
                viewModel.uiState.value.showModelPicker,
            )
            assertTrue(
                "picker should have preloaded providers (cached at GatewayReady)",
                viewModel.uiState.value.modelPickerProviders
                    .isNotEmpty(),
            )
            verify(exactly = 0) { HermesWsClient.send(WsMethods.COMMAND_DISPATCH, any(), any()) }
        }

    @Test
    fun testModelPickerSelection_hotSwapsCurrentSessionViaSlash() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/model")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.showModelPicker)

            // Selecting a model must send the bare spec "gpt-4o --provider openai
            // --session" via the `config.set` RPC with key="model" (the gateway
            // routes key=="model" to _apply_model_switch; the /model prefix is
            // stripped before send because config.set does not parse slash
            // commands). NOT command.dispatch (4018s on /model), NOT prompt.submit
            // (LLM would treat it as text). Capture the config.set params.
            val modelCalls = mutableListOf<Triple<String, String, String>>()
            every { HermesWsClient.send(RpcMethods.CONFIG_SET, any<ConfigSetParams>(), any()) } answers {
                val params = arg<ConfigSetParams>(1)
                modelCalls.add(Triple(params.key, params.value, params.sessionId.orEmpty()))
                "req-cfg-${modelCalls.size}"
            }

            viewModel.sendSlashModel("openai", "gpt-4o")
            advanceUntilIdle()

            assertFalse("picker closes after selection", viewModel.uiState.value.showModelPicker)
            assertEquals(
                "openai/gpt-4o",
                viewModel.uiState.value.currentSessionModel,
            )
            verify { HermesWsClient.send(RpcMethods.CONFIG_SET, any<ConfigSetParams>(), any()) }
            val call = modelCalls.firstOrNull { it.first == "model" }
            assertNotNull("selection must route through config.set key=model", call)
            assertEquals("gpt-4o --provider openai --session", call!!.second)
            assertEquals(sessionId, call.third)
        }

    @Test
    fun testTypedModelCommandWithArg_dispatchesDirectly() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            // A fully-typed "/model <model> --provider <slug> --session" bypasses the
            // picker and dispatches straight to the backend as a normal prompt.
            viewModel.sendMessage("/model gpt-4o --provider openai --session")
            advanceUntilIdle()

            assertFalse(
                "typed /model with arg should not open the picker",
                viewModel.uiState.value.showModelPicker,
            )
            // A fully-typed /model goes to the backend via the `config.set` RPC
            // (key="model"), which the gateway routes to _apply_model_switch. NOT
            // command.dispatch (4018s on /model) and NOT prompt.submit (LLM would
            // treat it as text).
            verify { HermesWsClient.send(RpcMethods.CONFIG_SET, any<ConfigSetParams>(), any()) }
        }

    @Test
    fun testTypedModelCommand_caseInsensitive_doesNotForwardSlashPrefix() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            // A fully-typed /MODEL (uppercase) must still route through
            // config.set key=model with the BARE spec — the leading "/MODEL"
            // slash prefix must be stripped, or parse_model_flags on the
            // backend won't recognize it and the hot-swap silently fails.
            val modelCalls = mutableListOf<Triple<String, String, String>>()
            every { HermesWsClient.send(RpcMethods.CONFIG_SET, any<ConfigSetParams>(), any()) } answers {
                val params = arg<ConfigSetParams>(1)
                modelCalls.add(Triple(params.key, params.value, params.sessionId.orEmpty()))
                "req-cfg-ci-${modelCalls.size}"
            }

            viewModel.sendMessage("/MODEL gpt-4o --provider openai --session")
            advanceUntilIdle()

            val call = modelCalls.firstOrNull { it.first == "model" }
            assertNotNull("uppercase /MODEL must route through config.set key=model", call)
            assertEquals(
                "slash prefix must be stripped before send",
                "gpt-4o --provider openai --session",
                call!!.second,
            )
            assertFalse(
                "value must not carry the literal /MODEL prefix",
                call.second.startsWith("/"),
            )
            assertEquals(sessionId, call.third)
        }

    @Test
    fun testModelSwitch_confirmRequired_triggersConfirmationDialogAndReSends() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            var lastReqId = ""
            val capturedParams = mutableListOf<ConfigSetParams>()
            every { HermesWsClient.send(RpcMethods.CONFIG_SET, any<ConfigSetParams>(), any()) } answers {
                val params = arg<ConfigSetParams>(1)
                capturedParams.add(params)
                val id = "req-confirm-${capturedParams.size}"
                lastReqId = id
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            // User sends model switch command that triggers privacy/expensive guard
            viewModel.sendMessage("/model muse-spark-1.3-contributor-free --provider opencode-free --session")
            advanceUntilIdle()

            assertEquals(1, capturedParams.size)
            assertNull(capturedParams[0].confirmExpensiveModel)

            // Backend returns confirm_required = true
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    id = lastReqId,
                    result =
                        mapOf(
                            "key" to "model",
                            "value" to "muse-spark-1.3-contributor-free",
                            "confirm_required" to true,
                            "confirm_message" to "Meta training warning",
                        ),
                ),
            )
            advanceUntilIdle()

            // UI state now shows confirmation dialog message
            assertEquals(UiText.raw("Meta training warning"), viewModel.uiState.value.modelSwitchConfirmMessage)

            // User confirms
            viewModel.confirmModelSwitchExpensive()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.modelSwitchConfirmMessage)
            assertEquals(2, capturedParams.size)
            assertEquals(true, capturedParams[1].confirmExpensiveModel)
            assertEquals(
                "muse-spark-1.3-contributor-free --provider opencode-free --session",
                capturedParams[1].value,
            )
            assertEquals(sessionId, capturedParams[1].sessionId)
        }

    @Test
    fun testModelSwitch_dismissConfirmation_revertsModel() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            var lastReqId = ""
            every { HermesWsClient.send(RpcMethods.CONFIG_SET, any<ConfigSetParams>(), any()) } answers {
                val id = "req-cfg-dismiss"
                lastReqId = id
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            // Set an initial model
            viewModel.sendSlashModel("openai", "gpt-4o")
            advanceUntilIdle()
            assertEquals("openai/gpt-4o", viewModel.uiState.value.currentSessionModel)

            // Switch to expensive model
            viewModel.sendSlashModel("opencode-free", "muse-spark-1.3-contributor-free")
            advanceUntilIdle()
            assertEquals("opencode-free/muse-spark-1.3-contributor-free", viewModel.uiState.value.currentSessionModel)

            // Backend requires confirmation
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    id = lastReqId,
                    result =
                        mapOf(
                            "key" to "model",
                            "value" to "muse-spark-1.3-contributor-free",
                            "confirm_required" to true,
                            "confirm_message" to "Meta training warning",
                        ),
                ),
            )
            advanceUntilIdle()
            assertEquals(UiText.raw("Meta training warning"), viewModel.uiState.value.modelSwitchConfirmMessage)

            // User dismisses/cancels
            viewModel.dismissModelSwitchConfirm()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.modelSwitchConfirmMessage)
            // Reverted back to previous model
            assertEquals("openai/gpt-4o", viewModel.uiState.value.currentSessionModel)
        }

    @Test
    fun sharedTextIsStagedToAppendToAnExistingDraft() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            viewModel.stageSharedContent(SharedContent(text = "shared note", attachments = emptyList()))
            advanceUntilIdle()

            assertEquals("shared note", viewModel.uiState.value.pendingPrefillText)
            assertTrue(viewModel.uiState.value.pendingPrefillAppends)
            viewModel.consumePendingPrefill()
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.pendingPrefillText)
            assertFalse(viewModel.uiState.value.pendingPrefillAppends)
        }

    @Test
    fun testUndoCommand_dispatchesAndPrefillsComposer() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            every {
                HermesWsClient.request(
                    WsMethods.COMMAND_DISPATCH,
                    buildJsonObject {
                        put("name", "undo")
                        put("arg", "2")
                        put("session_id", sessionId)
                    },
                    any(),
                )
            } returns
                CompletableDeferred(
                    mapOf(
                        "type" to "prefill",
                        "message" to "undone user message",
                        "notice" to "↶ Undid 2 turns",
                    ),
                )

            viewModel.sendMessage("/undo 2")
            advanceUntilIdle()

            // Prefill text is staged in UI state
            assertEquals("undone user message", viewModel.uiState.value.pendingPrefillText)
            // A command prefill replaces the composer; only shared content is appended.
            assertFalse(viewModel.uiState.value.pendingPrefillAppends)
            // Notice is displayed as system message with rewind target feedback
            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.content.contains("↶ Undid 2 turns") && it.content.contains("undone user message") },
            )
            // Verify slash usage was recorded
            assertEquals(1, viewModel.uiState.value.slashUsageCounts["/undo"])

            // Consume prefill text
            viewModel.consumePendingPrefill()
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.pendingPrefillText)
        }
}
