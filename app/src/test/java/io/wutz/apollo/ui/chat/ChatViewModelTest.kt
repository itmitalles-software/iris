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

/** ChatViewModel behaviour: Pure transcript helpers, connection operations, settings, request timeouts and turn state. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest : ChatViewModelTestBase() {
    @Test
    fun mergeTranscriptWithLive_collapsesDuplicateIdsKeepingLatestMessage() {
        val stale = ChatMessage(id = "duplicate-id", role = MessageRole.ASSISTANT, content = "stale")
        val latest = stale.copy(content = "latest")

        val merged = mergeTranscriptWithLive(emptyList(), listOf(stale, latest))

        assertEquals(listOf(latest), merged)
    }

    @Test
    fun mergeTranscriptWithLive_matchingUserContentCollapsesLocalAndRestCopies() {
        // /queue bubbles show the stripped queued text, so the optimistic
        // local copy and the later REST echo share content — the sync merge
        // must collapse them into one row instead of rendering a duplicate
        // below its answer (PR #892 follow-up).
        val local =
            ChatMessage(
                id = "ws-local-1",
                role = MessageRole.USER,
                content = "do the thing",
                timestamp = 100L,
            )
        val rest =
            ChatMessage(
                id = "rest-sess-5",
                role = MessageRole.USER,
                content = "do the thing",
                timestamp = 100L,
            )

        val merged = mergeTranscriptWithLive(listOf(rest), listOf(local))

        assertEquals(1, merged.size)
        // The richer local copy wins when both sides carry the same content.
        assertEquals("ws-local-1", merged.single().id)
    }

    @Test
    fun mergeTranscriptWithLive_collapsesGatewayAttachmentContextCopy() {
        val local =
            ChatMessage(
                id = "ws-local-attachment",
                role = MessageRole.USER,
                content = "What is the secret word in the attached file?",
                timestamp = 100L,
            )
        val rest =
            ChatMessage(
                id = "rest-sess-attachment",
                role = MessageRole.USER,
                content =
                    """
                    @file:files/agent-vault/hermes/attachments/note.txt

                    What is the secret word in the attached file?

                    --- Attached Context ---

                    📄 @file:files/agent-vault/hermes/attachments/note.txt (8 tokens)
                    ```
                    THE_SECRET_WORD_IS_MANGO_8421
                    ```
                    """.trimIndent(),
                timestamp = 100L,
            )

        val merged = mergeTranscriptWithLive(listOf(rest), listOf(local))

        assertEquals(1, merged.size)
        assertEquals("ws-local-attachment", merged.single().id)
    }

    @Test
    fun mapServerMessages_restOnlyAttachmentHidesGatewayContext() {
        val ref = "@file:files/agent-vault/hermes/attachments/note.txt"
        val persisted =
            """
            $ref

            What is the secret word in the attached file?

            --- Attached Context ---

            📄 $ref (8 tokens)
            ```
            THE_SECRET_WORD_IS_MANGO_8421
            ```
            """.trimIndent()

        val mapped =
            mapServerMessages(
                sessionId = "session-1",
                messages =
                    listOf(
                        SessionMessage(
                            id = 42,
                            role = "user",
                            content = JsonPrimitive(persisted),
                            timestamp = JsonPrimitive("100"),
                        ),
                    ),
                offset = 0,
                latestPaging = true,
                liveMessages = emptyList(),
            )

        assertEquals(1, mapped.size)
        assertEquals(
            """
            $ref

            What is the secret word in the attached file?
            """.trimIndent(),
            mapped.single().content,
        )
        assertFalse(mapped.single().content.contains("--- Attached Context ---"))
        assertFalse(mapped.single().content.contains("THE_SECRET_WORD_IS_MANGO_8421"))
    }

    @Test
    fun mapServerMessages_prefersCompactionDisplayContent() {
        val mapped =
            mapServerMessages(
                sessionId = "session-1",
                messages =
                    listOf(
                        SessionMessage(
                            id = 42,
                            role = "user",
                            content = JsonPrimitive("[model-facing compaction summary]"),
                            display_content = JsonPrimitive("The original user prompt"),
                        ),
                    ),
                offset = 0,
                latestPaging = true,
                liveMessages = emptyList(),
            )

        assertEquals("The original user prompt", mapped.single().content)
    }

    @Test
    fun stripAttachmentRefLines_preservesUserAuthoredAttachedContextHeading() {
        val authored =
            """
            Explain this heading:
            --- Attached Context ---
            this is ordinary user-authored text
            """.trimIndent()

        assertEquals(authored, stripAttachmentRefLines(authored))
    }

    @Test
    fun pastedImages_areAddedOnlyToCapturedReadySessionWithoutSubmitting() =
        runTest(testDispatcher) {
            val (viewModel, _) = createViewModelWithSession()
            val target = checkNotNull(viewModel.captureAttachmentTarget())
            val image = Attachment("content://test/pasted.png", "pasted.png", "image/png", 4)
            val messagesBefore = viewModel.uiState.value.messages
            assertTrue(viewModel.addPastedAttachments(target, listOf(image)))
            runCurrent()
            assertEquals(listOf(image), viewModel.uiState.value.pendingAttachments)
            assertEquals(messagesBefore, viewModel.uiState.value.messages)
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun pastedImages_rejectLateCompletionAfterSwitchAwayAndBack() =
        runTest(testDispatcher) {
            val (viewModel, sessionId) = createViewModelWithSession()
            val target = checkNotNull(viewModel.captureAttachmentTarget())
            viewModel.switchSession("other-paste-test-session")
            viewModel.switchSession(sessionId)
            val image = Attachment("content://test/pasted.png", "pasted.png", "image/png", 4)
            assertFalse(viewModel.addPastedAttachments(target, listOf(image)))
            assertTrue(
                viewModel.uiState.value.pendingAttachments
                    .isEmpty(),
            )
        }

    @Test
    fun pastedImages_rejectChangedServerAndProfilesAndGeneration() =
        runTest(testDispatcher) {
            val (viewModel, _) = createViewModelWithSession()
            val target = checkNotNull(viewModel.captureAttachmentTarget())
            val image = Attachment("content://test/pasted.png", "pasted.png", "image/png", 4)
            for (stale in listOf(
                target.copy(baseUrl = "http://other-paste-test.local/"),
                target.copy(connectionProfileId = "other-connection"),
                target.copy(agentProfileId = "other-agent"),
                target.copy(generation = target.generation + 1),
            )) {
                assertFalse(viewModel.addPastedAttachments(stale, listOf(image)))
            }
            assertTrue(
                viewModel.uiState.value.pendingAttachments
                    .isEmpty(),
            )
        }

    @Test
    fun pastedImages_cannotCaptureTargetBeforeReady() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            advanceUntilIdle()
            assertNull(viewModel.captureAttachmentTarget())
        }

    @Test
    fun pastedImages_rejectDisconnectedSessionBeforeCombinedUiStateCatchesUp() =
        runTest(testDispatcher) {
            val (viewModel, _) = createViewModelWithSession()
            val target = checkNotNull(viewModel.captureAttachmentTarget())
            mockConnectionStatus.value = ConnectionStatus.DISCONNECTED
            assertNull(viewModel.captureAttachmentTarget())
            assertFalse(viewModel.addPastedAttachments(target, emptyList()))
        }

    @Test
    fun plainTextSend_leavesStagedAttachmentsInTheComposer() =
        runTest(testDispatcher) {
            val (viewModel, _) = createViewModelWithSession()
            val target = checkNotNull(viewModel.captureAttachmentTarget())
            val image = Attachment("content://test/staged.png", "staged.png", "image/png", 4)
            assertTrue(viewModel.addPastedAttachments(target, listOf(image)))
            runCurrent()

            assertTrue(viewModel.sendMessage("Turn on the lights", BusySendMode.QUEUE, plainText = true))
            advanceUntilIdle()

            verify { HermesWsClient.sendMessage(any(), "Turn on the lights", any(), any()) }
            assertEquals(listOf(image), viewModel.uiState.value.pendingAttachments)
            val sent =
                viewModel.uiState.value.messages
                    .last { it.role == MessageRole.USER }
            assertEquals("Turn on the lights", sent.content)
            assertNull(sent.attachments)
        }

    @Test
    fun plainTextSend_doesNotRunSlashCommands() =
        runTest(testDispatcher) {
            val (viewModel, _) = createViewModelWithSession()

            assertTrue(viewModel.sendMessage("/stop the music", BusySendMode.QUEUE, plainText = true))
            advanceUntilIdle()

            verify { HermesWsClient.sendMessage(any(), "/stop the music", any(), any()) }
            verify(exactly = 0) { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
        }

    @Test
    fun connectionOperation_liveEventRoutesAndSessionResetClearsIt() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val snapshot = connectionOperationSnapshot(sessionId = sessionId)

            mockEventsFlow.emit(WsEvent.ConnectionRequest(snapshot))
            advanceUntilIdle()
            assertEquals(
                "op-1218",
                viewModel.connectionOperationState.value.operation
                    ?.opId,
            )

            viewModel.createNewSession()
            advanceUntilIdle()
            assertNull(viewModel.connectionOperationState.value.operation)

            mockEventsFlow.emit(WsEvent.ConnectionUpdate(snapshot.copy(seq = 2L)))
            advanceUntilIdle()
            assertNull(viewModel.connectionOperationState.value.operation)
        }

    @Test
    fun connectionOperation_sessionResumeRestoresPendingSnapshot() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.switchSession("stored-session")
            advanceUntilIdle()
            val resumeRequestId = sentRequestMethods.last { it.first == WsMethods.SESSION_RESUME }.second

            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    resumeRequestId,
                    mapOf(
                        "session_id" to "runtime-session",
                        "pending_connection" to connectionOperationPayload(),
                    ),
                ),
            )
            advanceUntilIdle()

            val operation = viewModel.connectionOperationState.value.operation
            assertEquals("runtime-session", operation?.sessionId)
            assertEquals("op-1218", operation?.opId)
        }

    @Test
    fun sessionResume_clarifyReplayBeforeRuntimeBindingRestoresOptions() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.switchSession("stored-clarify")
            advanceUntilIdle()
            val resumeId = sentRequestMethods.last { it.first == WsMethods.SESSION_RESUME }.second
            val params =
                mapOf(
                    "session_id" to "runtime-clarify",
                    "question" to "QA color?",
                    "choices" to listOf("QA BLUE", "QA GREEN"),
                )
            // RpcChannel emits open_requests before its enclosing resume result.
            mockEventsFlow.emit(WsEvent.ServerRequest("srq-resume", "clarify", params, replayed = true))
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.clarifyRequest)
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    resumeId,
                    mapOf(
                        "session_id" to "runtime-clarify",
                        "resumed" to "stored-clarify",
                        "open_requests" to
                            listOf(mapOf("id" to "srq-resume", "method" to "clarify", "params" to params)),
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals("stored-clarify", viewModel.uiState.value.currentSessionId)
            val clarify = viewModel.uiState.value.clarifyRequest
            assertNotNull("Pending clarify must survive runtime binding on resume", clarify)
            assertEquals("srq-resume", clarify?.serverRequestId)
            assertEquals(listOf("QA BLUE", "QA GREEN"), clarify?.options)
            verify(exactly = 0) { HermesWsClient.respondToServerRequest(any(), any()) }
        }

    @Test
    fun sessionResume_clarifyReplayFromStaleResumeCannotSurface() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.switchSession("stored-old-clarify")
            advanceUntilIdle()
            val staleId = sentRequestMethods.last { it.first == WsMethods.SESSION_RESUME }.second
            viewModel.switchSession("stored-current-clarify")
            advanceUntilIdle()
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    staleId,
                    mapOf(
                        "session_id" to "runtime-old-clarify",
                        "resumed" to "stored-old-clarify",
                        "open_requests" to
                            listOf(
                                mapOf(
                                    "id" to "srq-old",
                                    "method" to "clarify",
                                    "params" to mapOf("session_id" to "runtime-old-clarify", "question" to "Old?"),
                                ),
                            ),
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals("stored-current-clarify", viewModel.uiState.value.currentSessionId)
            assertNull(viewModel.uiState.value.clarifyRequest)
            verify(exactly = 0) { HermesWsClient.respondToServerRequest(any(), any()) }
        }

    @Test
    fun sessionResume_clarifyReplayRejectsUnrelatedRequestSession() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.switchSession("stored-clarify")
            advanceUntilIdle()
            val resumeId = sentRequestMethods.last { it.first == WsMethods.SESSION_RESUME }.second
            val params = mapOf("session_id" to "runtime-unrelated", "question" to "Unrelated?")
            mockEventsFlow.emit(WsEvent.ServerRequest("srq-unrelated", "clarify", params, replayed = true))
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    resumeId,
                    mapOf(
                        "session_id" to "runtime-clarify",
                        "resumed" to "stored-clarify",
                        "open_requests" to
                            listOf(mapOf("id" to "srq-unrelated", "method" to "clarify", "params" to params)),
                    ),
                ),
            )
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.clarifyRequest)
            verify(exactly = 0) { HermesWsClient.respondToServerRequest(any(), any()) }
        }

    @Test
    fun sessionResume_sendsDesktopSourceLikeSessionCreate() =
        runTest {
            // #1450: session.create declared source="desktop" but session.resume omitted it, so
            // the gateway resolved the resumed runtime from its host env ("tui") and staged a
            // bogus surface switch that also unloaded the desktop_ui toolset.
            val viewModel = createViewModel()
            advanceUntilIdle()

            val captured = mutableListOf<Pair<String, Map<String, Any>>>()
            every { HermesWsClient.send(any(), any(), any()) } answers {
                val id = "req-${captured.size + 1}"
                captured.add(arg<String>(0) to (arg<Map<String, Any>>(1)))
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            viewModel.switchSession("stored-session")
            advanceUntilIdle()

            val resumeSent = captured.firstOrNull { it.first == WsMethods.SESSION_RESUME }
            assertNotNull("session.resume should be dispatched", resumeSent)
            assertEquals(JsonPrimitive("stored-session"), resumeSent!!.second["session_id"])
            assertEquals(JsonPrimitive("desktop"), resumeSent.second["source"])
        }

    @Test
    fun sessionResume_restoresRetainedFailureAsNonDurablePartial() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.switchSession("stored-session")
            advanceUntilIdle()
            val resumeRequestId = sentRequestMethods.last { it.first == WsMethods.SESSION_RESUME }.second

            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    resumeRequestId,
                    mapOf(
                        "session_id" to "runtime-session",
                        "resumed" to "stored-session",
                        "inflight" to
                            mapOf(
                                "assistant" to "Retained partial answer",
                                "user" to "Private user prompt",
                                "streaming" to false,
                                "status" to "error",
                                "error" to "Provider failed",
                                "recoverable" to true,
                                "error_surface" to mapOf("provider" to "example", "code" to "rate_limit"),
                            ),
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals("stored-session", viewModel.uiState.value.currentSessionId)
            assertEquals(
                "Retained partial answer",
                viewModel.uiState.value.messages
                    .last()
                    .content,
            )
            assertEquals(
                "code: rate_limit\nprovider: example\nProvider failed",
                viewModel.uiState.value.replyFailure
                    ?.details,
            )
            assertFalse(
                viewModel.uiState.value.replyFailure!!
                    .details
                    .contains("Private user prompt"),
            )
            assertTrue(
                fakeRepo.dao
                    .getMessagesForSession("stored-session")
                    .none { it.content == "Retained partial answer" },
            )
        }

    @Test
    fun staleSessionResumeFailureCannotClearHealthyCurrentStream() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.switchSession("stored-old")
            advanceUntilIdle()
            val staleResumeId = sentRequestMethods.last { it.first == WsMethods.SESSION_RESUME }.second

            viewModel.switchSession("stored-current")
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.MessageStart("stored-current"))
            mockEventsFlow.emit(WsEvent.MessageToken("Healthy current stream", "stored-current"))
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    staleResumeId,
                    mapOf(
                        "session_id" to "runtime-old",
                        "inflight" to
                            mapOf(
                                "assistant" to "Old failed partial",
                                "status" to "error",
                                "error" to "Old provider failure",
                            ),
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals("stored-current", viewModel.uiState.value.currentSessionId)
            assertNull(viewModel.uiState.value.replyFailure)
            assertEquals(
                "Healthy current stream",
                viewModel.streamingState.value.streamingMessage
                    ?.content,
            )
            assertTrue(
                viewModel.uiState.value.messages
                    .none { it.content == "Old failed partial" },
            )
        }

    // ── Settings ─────────────────────────────────────────────────────────────

    @Test
    fun testRefreshSettings_updatesUiState() =
        runTest {
            // Given the default setup, init{} already calls refreshSettings() once,
            // so the initial state reflects the setUp defaults.
            val viewModel = createViewModel()
            advanceUntilIdle()
            with(viewModel.uiState.value) {
                assertTrue(typingEffectEnabled)
                assertEquals(30, typingEffectDelayMs)
                assertFalse(messageStatsEnabled)
                assertTrue(showUserMessageTokens)
                assertTrue(showAssistantMessageTokens)
                assertTrue(showTokensPerSecond)
                assertFalse(showModelProvider)
            }

            // When settings change after construction and refreshSettings() is re-invoked,
            // the UI state must reflect the NEW values — this proves refreshSettings()
            // re-reads AuthManager live (the real regression scenario).
            every { AuthManager.isTypingEffectEnabled() } returns false
            every { AuthManager.getTypingEffectDelayMs() } returns 50
            every { AuthManager.isMessageStatsEnabled() } returns true
            every { AuthManager.isUserMessageTokensEnabled() } returns false
            every { AuthManager.isAssistantMessageTokensEnabled() } returns false
            every { AuthManager.isTokensPerSecondEnabled() } returns false
            every { AuthManager.isModelProviderShown() } returns true
            viewModel.refreshSettings()
            advanceUntilIdle()

            // Then
            val state = viewModel.uiState.value
            assertFalse(state.typingEffectEnabled)
            assertEquals(50, state.typingEffectDelayMs)
            assertTrue(state.messageStatsEnabled)
            assertFalse(state.showUserMessageTokens)
            assertFalse(state.showAssistantMessageTokens)
            assertFalse(state.showTokensPerSecond)
            assertTrue(state.showModelProvider)
        }

    @Test
    fun testSendMessage_capturesCurrentUsageBeforeTurnAndSessionSwitchClearsIt() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(
                WsEvent.SessionUsage(
                    data = mapOf("usage" to mapOf("output" to 1000L)),
                    sessionId = sessionId,
                ),
            )
            advanceUntilIdle()

            viewModel.sendMessage("prompt")
            advanceUntilIdle()

            assertEquals(
                1000L,
                viewModel.streamingState.value.turnUsageBaseline
                    ?.outputTokens,
            )
            assertTrue(viewModel.streamingState.value.turnUsageBaselineCaptured)
            viewModel.switchSession("session-other")

            assertNull(viewModel.streamingState.value.turnUsageBaseline)
            assertFalse(viewModel.streamingState.value.turnUsageBaselineCaptured)
        }

    @Test
    fun testToggleSearch() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            assertFalse(viewModel.searchState.isActive)

            viewModel.toggleSearch()
            advanceUntilIdle()

            assertTrue(viewModel.searchState.isActive)

            viewModel.setSearchQuery("test")
            advanceUntilIdle()

            viewModel.toggleSearch()
            advanceUntilIdle()

            assertFalse(viewModel.searchState.isActive)
            assertEquals("", viewModel.searchState.query)
        }

    @Test
    fun testSendMessage_readContentUriThrowsException_handlesGracefully() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            // Android framework Uri.parse throws "not mocked" in plain unit tests,
            // so stub it (no Robolectric here). Then make the resolver throw on read.
            mockkStatic(Uri::class)
            val mockUri = mockk<Uri>()
            every { Uri.parse("content://dummy") } returns mockUri

            val contentResolver = mockk<ContentResolver>()
            every { app.contentResolver } returns contentResolver
            every { contentResolver.openInputStream(any()) } throws
                SecurityException("Permission denied")

            viewModel.addAttachment("content://dummy", "test.png", "image/png", 1000)
            advanceUntilIdle()

            viewModel.sendMessage("Here is an image")
            advanceUntilIdle()

            // Private staging failed before a durable receipt or outbound RPC existed.
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.content == "Here is an image" },
            )
            assertTrue(
                viewModel.uiState.value.pendingSends
                    .isEmpty(),
            )
            assertEquals("Here is an image", viewModel.uiState.value.composerTextToRestore)
            assertEquals(
                "content://dummy",
                viewModel.uiState.value.pendingAttachments
                    .single()
                    .uri,
            )
            verify(exactly = 0) { Log.e(any(), match { it.contains("Permission denied") }, any()) }
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    /**
     * Regression for the "session not found" (code 4001) error when sending an
     * image: the mobile image-attach path must pass `session_id` to
     * `image.attach_bytes` (the gateway resolves the session from it; desktop
     * does the same). Without it the backend 4001s and the image is dropped.
     * Also asserts the image attach is AWAITED (staged before prompt.submit),
     * not fire-and-forget.
     */
    @Test
    fun testSendMessage_imageAttachment_sendsSessionId() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            // sendRpcAndAwait calls HermesWsClient.request(method, params) — the
            // 2-arg form (Kotlin synthesizes request(String, Map)). Stub that
            // exact signature, capture params, and return a completed deferred
            // so the await resolves without hanging.
            val paramsSlot = slot<Map<String, Any>>()
            every {
                HermesWsClient.request(any(), capture(paramsSlot), any())
            } returns CompletableDeferred<Any?>(mapOf("attached" to true))

            // Mock Android's Base64OutputStream constructor to avoid "Stub!" exception in JVM tests
            io.mockk.mockkConstructor(android.util.Base64OutputStream::class)
            every {
                anyConstructed<android.util.Base64OutputStream>().write(
                    any<ByteArray>(),
                    any(),
                    any(),
                )
            } returns Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit

            // The image bytes read via ContentResolver must succeed.
            mockkStatic(Uri::class)
            val mockUri = mockk<Uri>()
            val snapshotUri = mockk<Uri>()
            lateinit var snapshotFile: java.io.File
            every { Uri.parse("content://dummy") } returns mockUri
            every { Uri.fromFile(any()) } answers {
                snapshotFile = firstArg()
                snapshotUri
            }
            every { snapshotUri.toString() } returns "file://private-image-copy"
            every { Uri.parse("file://private-image-copy") } returns snapshotUri
            val contentResolver = mockk<ContentResolver>()
            every { app.contentResolver } returns contentResolver
            every { contentResolver.openInputStream(mockUri) } returns
                java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
            every { contentResolver.openInputStream(snapshotUri) } answers { snapshotFile.inputStream() }

            viewModel.addAttachment("content://dummy", "test.png", "image/png", 1000)
            advanceUntilIdle()
            assertTrue(
                "pendingAttachments must contain the image before send",
                viewModel.uiState.value.pendingAttachments
                    .isNotEmpty(),
            )
            assertTrue(
                "attached png must have isImage=true",
                viewModel.uiState.value.pendingAttachments
                    .first()
                    .isImage,
            )

            viewModel.sendMessage("Here is an image")
            advanceUntilIdle()

            // Verify the image attach RPC was issued with session_id.
            verify { HermesWsClient.request(WsMethods.IMAGE_ATTACH_BYTES, any()) }
            assertEquals(
                "session_id must be forwarded to image.attach_bytes",
                JsonPrimitive(sessionId),
                paramsSlot.captured["session_id"],
            )
            assertTrue("encoded attachment cache must be cleaned", attachmentCacheDir.listFiles().isNullOrEmpty())
        }

    // ── Pending request timeout + rejectAllPending (issue #526) ───────────

    /**
     * On disconnect (RECONNECTING) the ViewModel must run rejectAllPending
     * without throwing and stay usable — mirroring desktop
     * JsonRpcGatewayClient.rejectAllPending invoked on socket close. This is
     * what prevents callers awaiting a CompletableDeferred from hanging
     * across a socket drop.
     */
    @Test
    fun testDisconnect_rejectsPendingWithoutError() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            mockEventsFlow.emit(
                WsEvent.ApprovalRequest(
                    command = "rm",
                    description = "Dangerous",
                    patternKeys = null,
                    sessionId = null,
                ),
            )
            advanceUntilIdle()
            viewModel.respondToApproval("approve")
            advanceUntilIdle()

            // Simulate socket drop → reconnecting (triggers rejectAllPending).
            mockConnectionStatus.value = ConnectionStatus.RECONNECTING
            advanceUntilIdle()

            // No exception propagated; VM remains usable.
            assertNull(viewModel.uiState.value.errorMessage)
        }

    /**
     * viewModel.reconnect() calls rejectAllPending() before wsClient.disconnect(),
     * so any in-flight awaited RPC is failed fast instead of hanging until its
     * own timeout.
     */
    @Test
    fun testReconnect_rejectsPendingWithoutError() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            mockEventsFlow.emit(
                WsEvent.ApprovalRequest(
                    command = "rm",
                    description = "Dangerous",
                    patternKeys = null,
                    sessionId = null,
                ),
            )
            advanceUntilIdle()
            viewModel.respondToApproval("approve")
            advanceUntilIdle()

            // User-initiated reconnect must not throw / hang.
            viewModel.reconnect()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.errorMessage)
            verify { HermesWsClient.disconnect() }
        }

    @Test
    fun canInterrupt_staysFalseWhileTypingWithNoRuntimeSession_thenTracksTheGeneration() =
        runTest {
            stubActiveProfile()
            val vm = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            val sentPrompts = mutableListOf<String>()
            every { HermesWsClient.sendMessage(any(), any(), any(), any()) } answers {
                reqCount++
                val id = "req-msg-$reqCount"
                sentPrompts += arg<String>(1)
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            // Queued while session create is in flight: sending is pending,
            // but the session has no runtime to interrupt yet, so Stop must stay
            // hidden and interruptSession() must not be reachable from it
            // (review, PR #1250).
            vm.sendMessage("held under session preparation")
            advanceUntilIdle()
            assertTrue(vm.uiState.value.isSending)
            assertFalse(vm.uiState.value.canInterrupt)

            // Once the runtime session exists and the prompt is dispatched,
            // the running generation is interruptible. Read the captured
            // session.create id instead of counting sends: a singleton store
            // warmed by an earlier test can fire an extra model.options send
            // in this gateway flow and shift the counter (see the resume
            // tests, same pattern).
            val createReqId =
                sentRequestMethods.last { it.first == WsMethods.SESSION_CREATE }.second
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    createReqId,
                    mapOf("session_id" to "session-969", "stored_session_id" to "session-storage-969"),
                ),
            )
            advanceUntilIdle()

            assertEquals(listOf("held under session preparation"), sentPrompts)
            // A successful prompt.submit acknowledgement only means the
            // gateway accepted it; the generation is interruptible only once
            // the runtime reports message.start for its session.
            mockEventsFlow.emit(WsEvent.MessageStart("session-969"))
            advanceUntilIdle()
            assertTrue(vm.uiState.value.canInterrupt)
        }

    private fun connectionOperationSnapshot(
        sessionId: String,
        seq: Long = 1L,
    ) = checkNotNull(ConnectionOperationParser.parse(connectionOperationPayload(seq), sessionId))

    private fun connectionOperationPayload(seq: Long = 1L): Map<String, Any?> =
        mapOf(
            "op_id" to "op-1218",
            "seq" to seq,
            "deadline_at" to 2_000_000_000.0,
            "timeout_seconds" to 300.0,
            "tool_call_id" to "tool-1218",
            "targets" to
                listOf(
                    mapOf(
                        "name" to "github",
                        "kind" to "connector",
                        "action" to "authorize",
                        "state" to "pending",
                    ),
                ),
        )
}
