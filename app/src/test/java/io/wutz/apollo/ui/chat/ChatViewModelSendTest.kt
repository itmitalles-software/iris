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

/** ChatViewModel behaviour: Send pipeline: receipts, queueing, voice notes and turn correlation. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelSendTest : ChatViewModelTestBase() {
    @Test
    fun sendMessage_mixedAttachmentsWithOversizedFileAreRejectedBeforeReading() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val contentResolver = mockk<ContentResolver>()
            every { app.contentResolver } returns contentResolver

            viewModel.addAttachment(
                uri = "content://image/photo",
                name = "photo.jpg",
                mimeType = "image/jpeg",
                size = 1024,
            )
            viewModel.addAttachment(
                uri = "content://oversized/archive",
                name = "archive.zip",
                mimeType = "application/zip",
                size = MAX_CHAT_ATTACHMENT_BYTES + 1,
            )

            assertFalse(viewModel.sendMessage("Inspect this archive"))
            advanceUntilIdle()

            assertEquals(2, viewModel.uiState.value.pendingAttachments.size)
            assertNull(viewModel.uiState.value.composerTextToRestore)
            assertTrue(
                viewModel.uiState.value.errorMessage
                    ?.en()
                    ?.contains("too large") == true,
            )
            verify(exactly = 0) { contentResolver.openInputStream(any()) }
        }

    @Test
    fun sendMessage_unknownSizeOversizedAttachmentRestoresComposerWithoutGhostMessage() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val uriString = "content://unknown/large-file"
            val mockUri = mockk<Uri>()
            val contentResolver = mockk<ContentResolver>()
            val oversizedStream =
                object : java.io.InputStream() {
                    private var remaining = MAX_CHAT_ATTACHMENT_BYTES + 1

                    override fun read(): Int = if (remaining-- > 0) 0 else -1

                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        if (remaining <= 0) return -1
                        val count = minOf(length.toLong(), remaining).toInt()
                        remaining -= count
                        return count
                    }
                }
            mockkStatic(Uri::class)
            every { Uri.parse(uriString) } returns mockUri
            every { app.contentResolver } returns contentResolver
            every { contentResolver.openInputStream(mockUri) } returns oversizedStream

            viewModel.addAttachment(
                uri = uriString,
                name = "unknown-size.bin",
                mimeType = "application/octet-stream",
                size = 0,
            )

            viewModel.sendMessage("Inspect this file")
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.pendingAttachments.size)
            assertEquals("Inspect this file", viewModel.uiState.value.composerTextToRestore)
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.content == "Inspect this file" },
            )
            assertTrue(
                viewModel.uiState.value.errorMessage
                    ?.en()
                    ?.contains("too large") == true,
            )
        }

    @Test
    fun sendMessage_oversizedDuringSnapshotLeavesNoPersistedGhostMessage() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val persistedBeforeSend = fakeRepo.dao.count()
            val uriString = "content://unknown/growing-file"
            val mockUri = mockk<Uri>()
            val contentResolver = mockk<ContentResolver>()
            val oversizedStream =
                object : java.io.InputStream() {
                    private var remaining = MAX_CHAT_ATTACHMENT_BYTES + 1

                    override fun read(): Int = if (remaining-- > 0) 0 else -1

                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        if (remaining <= 0) return -1
                        val count = minOf(length.toLong(), remaining).toInt()
                        remaining -= count
                        return count
                    }
                }
            mockkStatic(Uri::class)
            every { Uri.parse(uriString) } returns mockUri
            every { app.contentResolver } returns contentResolver
            every { contentResolver.openInputStream(mockUri) } returns oversizedStream

            viewModel.addAttachment(
                uri = uriString,
                name = "growing-file.bin",
                mimeType = "application/octet-stream",
                size = 0,
            )

            viewModel.sendMessage("Inspect changing file")
            advanceUntilIdle()

            assertFalse(viewModel.streamingState.value.turnUsageBaselineCaptured)
            assertEquals(persistedBeforeSend, fakeRepo.dao.count())
            assertFalse(
                fakeRepo.dao
                    .getMessagesForSession("session-123")
                    .any { it.content == "Inspect changing file" },
            )
            assertEquals(1, viewModel.uiState.value.pendingAttachments.size)
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.content == "Inspect changing file" },
            )

            mockEventsFlow.emit(
                WsEvent.SessionUsage(
                    data = mapOf("usage" to mapOf("output" to 1300L)),
                    sessionId = sessionId,
                ),
            )
            advanceUntilIdle()
            viewModel.removeAttachment(0)
            viewModel.sendMessage("Retry without attachment")
            advanceUntilIdle()

            assertEquals(
                1300L,
                viewModel.streamingState.value.turnUsageBaseline
                    ?.outputTokens,
            )
            assertTrue(viewModel.streamingState.value.turnUsageBaselineCaptured)
        }

    @Test
    fun sendMessage_persistenceFailureRestoresComposerAndDoesNotDispatch() =
        runTest {
            fakeRepo = spyk(fakeRepo)
            coEvery {
                fakeRepo.persistMessage(match { it.content == "keep this prompt" }, any())
            } throws IllegalStateException("disk full")
            val (viewModel, _) = createViewModelWithSession()

            viewModel.sendMessage("keep this prompt")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.messages.any { it.content == "keep this prompt" })
            assertEquals("keep this prompt", state.composerTextToRestore)
            assertTrue(state.errorMessage?.en()?.contains("save") == true)
            assertFalse(state.isAgentTyping)
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun sendMessage_rawJsonFileAttachmentRetainsReference() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val uriString = "content://test/note"
            val uri = mockk<Uri>()
            val snapshotUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            lateinit var snapshotFile: java.io.File
            mockkStatic(Uri::class)
            every { Uri.parse(uriString) } returns uri
            every { Uri.fromFile(any()) } answers {
                snapshotFile = firstArg()
                snapshotUri
            }
            every { snapshotUri.toString() } returns "file://private-note-copy"
            every { Uri.parse("file://private-note-copy") } returns snapshotUri
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(uri) } answers { "hello".byteInputStream() }
            every { resolver.openInputStream(snapshotUri) } answers { snapshotFile.inputStream() }
            mockkConstructor(android.util.Base64OutputStream::class)
            every { anyConstructed<android.util.Base64OutputStream>().write(any<ByteArray>(), any(), any()) } returns
                Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit
            every { HermesWsClient.request(WsMethods.FILE_ATTACH, any(), any()) } returns
                CompletableDeferred<Any?>(
                    buildJsonObject {
                        put("attached", true)
                        put("ref_text", "@file:attachments/note.txt")
                    },
                )
            viewModel.addAttachment(uriString, "note.txt", "text/plain", 5)

            viewModel.sendMessage("Inspect file")
            advanceUntilIdle()

            verify {
                HermesWsClient.sendMessage(any(), "@file:attachments/note.txt\n\nInspect file", any(), any())
            }
        }

    @Test
    fun sendMessage_laterGrowingAttachmentDoesNotPartiallyUploadEarlierAttachments() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val imageUriString = "content://image/valid"
            val growingUriString = "content://unknown/growing-file"
            val imageUri = mockk<Uri>()
            val growingUri = mockk<Uri>()
            val contentResolver = mockk<ContentResolver>()
            val oversizedStream =
                object : java.io.InputStream() {
                    private var remaining = MAX_CHAT_ATTACHMENT_BYTES + 1

                    override fun read(): Int = if (remaining-- > 0) 0 else -1

                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        if (remaining <= 0) return -1
                        val count = minOf(length.toLong(), remaining).toInt()
                        remaining -= count
                        return count
                    }
                }
            mockkStatic(Uri::class)
            every { Uri.parse(imageUriString) } returns imageUri
            every { Uri.parse(growingUriString) } returns growingUri
            every { app.contentResolver } returns contentResolver
            every { contentResolver.openInputStream(imageUri) } returns
                java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
            every { contentResolver.openInputStream(growingUri) } returns oversizedStream

            viewModel.addAttachments(
                listOf(
                    Attachment(imageUriString, "photo.jpg", "image/jpeg", 4),
                    Attachment(growingUriString, "growing.bin", "application/octet-stream", 0),
                ),
            )

            viewModel.sendMessage("Inspect both")
            advanceUntilIdle()

            verify(exactly = 0) {
                HermesWsClient.request(
                    match { it == WsMethods.IMAGE_ATTACH_BYTES || it == WsMethods.FILE_ATTACH },
                    any(),
                    any(),
                )
            }
            assertEquals(2, viewModel.uiState.value.pendingAttachments.size)
        }

    @Test
    fun sendMessage_oversizeResultAfterSessionSwitchDoesNotMutateNewSession() =
        runTest {
            stubSession456Rests(success = true)
            val (viewModel, _) = createViewModelWithSession()
            val uriString = "content://unknown/session-switch-large-file"
            val mockUri = mockk<Uri>()
            val contentResolver = mockk<ContentResolver>()
            val oversizedStream =
                object : java.io.InputStream() {
                    private var remaining = MAX_CHAT_ATTACHMENT_BYTES + 1

                    override fun read(): Int = if (remaining-- > 0) 0 else -1

                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        if (remaining <= 0) return -1
                        val count = minOf(length.toLong(), remaining).toInt()
                        java.util.Arrays.fill(buffer, offset, offset + count, 0.toByte())
                        remaining -= count
                        return count
                    }
                }

            mockkStatic(Uri::class)
            every { Uri.parse(uriString) } returns mockUri
            every { app.contentResolver } returns contentResolver
            every { contentResolver.openInputStream(mockUri) } answers {
                viewModel.switchSession("session-456")
                oversizedStream
            }

            viewModel.addAttachment(
                uri = uriString,
                name = "large.bin",
                mimeType = "application/octet-stream",
                size = 0,
            )

            viewModel.sendMessage("Inspect in the old session")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("session-456", state.currentSessionId)
            assertTrue(state.pendingAttachments.isEmpty())
            assertNull(state.composerTextToRestore)
            assertNull(state.errorMessage)
        }

    @Test
    fun sendMessage_promptErrorAfterSessionSwitchDoesNotMutateNewSession() =
        runTest {
            stubSession456Rests(success = true)
            val (viewModel, oldSessionId) = createViewModelWithSession()
            val uriString = "content://file/session-switch-valid-file"
            val mockUri = mockk<Uri>()
            val contentResolver = mockk<ContentResolver>()
            val promptRequestId = "old-session-prompt"

            mockkStatic(Uri::class)
            every { Uri.parse(uriString) } returns mockUri
            every { app.contentResolver } returns contentResolver
            every { contentResolver.openInputStream(mockUri) } answers {
                viewModel.switchSession("session-456")
                java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
            }
            every { HermesWsClient.sendMessage(any(), any(), any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke(promptRequestId)
                promptRequestId
            }

            viewModel.addAttachment(
                uri = uriString,
                name = "valid.bin",
                mimeType = "application/octet-stream",
                size = 4,
            )

            viewModel.sendMessage("Send in the old session")
            advanceUntilIdle()

            verify(exactly = 0) {
                HermesWsClient.sendMessage(
                    oldSessionId,
                    match { it.contains("Send in the old session") },
                    any(),
                    any(),
                )
            }
            assertFalse(viewModel.streamingState.value.turnUsageBaselineCaptured)

            val state = viewModel.uiState.value
            assertEquals("session-456", state.currentSessionId)
            assertNull(state.errorMessage)

            val resumeRequestId = sentRequestMethods.last { it.first == WsMethods.SESSION_RESUME }.second
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    resumeRequestId,
                    mapOf("session_id" to "session-456"),
                ),
            )
            advanceUntilIdle()
            mockEventsFlow.emit(
                WsEvent.SessionUsage(
                    data = mapOf("usage" to mapOf("output" to 9000L)),
                    sessionId = "session-456",
                ),
            )
            advanceUntilIdle()
            viewModel.sendMessage("Send in the new session")
            advanceUntilIdle()

            assertEquals(
                9000L,
                viewModel.streamingState.value.turnUsageBaseline
                    ?.outputTokens,
            )
            assertTrue(viewModel.streamingState.value.turnUsageBaselineCaptured)
            verify {
                HermesWsClient.sendMessage(
                    "session-456",
                    match { it.contains("Send in the new session") },
                    any(),
                    any(),
                )
            }
        }

    // ── Send message ─────────────────────────────────────────────────────────

    @Test
    fun testSendMessage() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            ActiveSessionHolder.set(sessionId, "stale-session")

            viewModel.sendMessage("Hello Hermes")
            advanceUntilIdle()

            assertEquals(2, viewModel.uiState.value.messages.size)
            assertEquals(
                "Hello Hermes",
                viewModel.uiState.value.messages[1]
                    .content,
            )
            assertEquals(
                MessageRole.USER,
                viewModel.uiState.value.messages[1]
                    .role,
            )
            assertTrue(viewModel.uiState.value.isSending)
            assertEquals(sessionId, ActiveSessionHolder.resolveStoredSessionId(sessionId))

            verify { HermesWsClient.sendMessage(sessionId, "Hello Hermes", any()) }
        }

    @Test
    fun testSendMessageRedirectWhenStreaming() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            // First send starts streaming
            viewModel.sendMessage("Hello Hermes")
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isAgentTyping)

            // Second send while streaming triggers redirect
            viewModel.sendMessage("Wait, correction")
            advanceUntilIdle()

            verify { HermesWsClient.sendRedirect(sessionId, "Wait, correction", any()) }
        }

    @Test
    fun rejectedRedirectRemovesGhostAndQueuesRecoverableText() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            var redirectId = ""
            every { HermesWsClient.sendRedirect(sessionId, any(), any()) } answers {
                redirectId = "rejected-redirect"
                arg<((String) -> Unit)?>(2)?.invoke(redirectId)
                redirectId
            }

            viewModel.sendMessage("First turn")
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.sendMessage("Correction")
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult(redirectId, mapOf("status" to "rejected")))
            advanceUntilIdle()

            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.content == "Correction" },
            )
            assertFalse(fakeRepo.loadMessages(sessionId).any { it.content == "Correction" })
            assertTrue(
                viewModel.uiState.value.pendingSends
                    .any { it.text == "Correction" },
            )
            assertTrue(viewModel.uiState.value.isAgentTyping)
        }

    @Test
    fun rejectedRedirectFallsBackToQueueAndRestHistoryRecoversOneUserRow() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            var redirectId = ""
            var queuedId = ""
            var historyHasCorrection = false
            every { HermesWsClient.sendRedirect(sessionId, any(), any()) } answers {
                redirectId = "redirect-for-history"
                arg<((String) -> Unit)?>(2)?.invoke(redirectId)
                redirectId
            }
            every { HermesWsClient.sendMessage(sessionId, "Correction", any(), true) } answers {
                queuedId = "queued-for-history"
                arg<((String) -> Unit)?>(2)?.invoke(queuedId)
                queuedId
            }
            coEvery {
                ApiClient.hermesApi.getSessionMessages(sessionId, any(), any(), any(), any())
            } answers {
                retrofit2.Response.success(
                    SessionMessagesResponse(
                        messages =
                            if (historyHasCorrection) {
                                listOf(
                                    SessionMessage(
                                        id = 12,
                                        role = "user",
                                        content = JsonPrimitive("Correction"),
                                        timestamp = JsonPrimitive(System.currentTimeMillis()),
                                    ),
                                )
                            } else {
                                emptyList()
                            },
                        pagination = PaginationInfo(order = "latest"),
                    ),
                )
            }

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.sendMessage("Correction")
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult(redirectId, mapOf("status" to "rejected")))
            advanceUntilIdle()
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.content == "Correction" },
            )

            mockEventsFlow.emit(WsEvent.MessageComplete("Done", sessionId))
            advanceUntilIdle()
            assertEquals("queued-for-history", queuedId)
            mockEventsFlow.emit(WsEvent.RpcResult(queuedId, mapOf("status" to "queued")))
            advanceUntilIdle()
            assertEquals(
                PendingSendState.ACCEPTED,
                viewModel.uiState.value.pendingSends
                    .single()
                    .state,
            )

            historyHasCorrection = true
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(WsEvent.MessageComplete("Answered", sessionId))
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.pendingSends
                    .isEmpty(),
            )
            assertEquals(
                1,
                viewModel.uiState.value.messages
                    .count { it.content == "Correction" },
            )
            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.content == "Correction" && it.canonicalRestId != null },
            )
        }

    @Test
    fun busyQueueWaitsForCompletionAndUsesQueuedSubmit() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()

            assertTrue(viewModel.sendMessage("Next turn", BusySendMode.QUEUE))
            advanceUntilIdle()
            assertEquals(
                PendingSendState.QUEUED,
                viewModel.uiState.value.pendingSends
                    .single()
                    .state,
            )
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Next turn", any(), any()) }

            mockEventsFlow.emit(WsEvent.MessageComplete("Done", sessionId))
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Next turn", any(), true) }
        }

    @Test
    fun queueTapAfterTurnCompletionSubmitsNormally() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.MessageComplete("Done", sessionId))
            advanceUntilIdle()

            assertTrue(viewModel.sendMessage("Late queue tap", BusySendMode.QUEUE))
            advanceUntilIdle()

            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Late queue tap", any(), false) }
            assertFalse(
                viewModel.uiState.value.pendingSends
                    .any { it.text == "Late queue tap" && it.state == PendingSendState.QUEUED },
            )
        }

    @Test
    fun queueDrainKeepsOwnershipWhileFirstReceiptPersistenceIsSuspended() =
        runTest {
            fakeRepo = spyk(fakeRepo)
            val releasePersistence = CompletableDeferred<Unit>()
            var holdPersistence = false
            var preparationCount = 0
            coEvery { fakeRepo.persistMessage(match { it.content == "FIFO first" }, any()) } coAnswers {
                if (holdPersistence) {
                    preparationCount++
                    releasePersistence.await()
                }
                Unit
            }
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.sendMessage("FIFO first", BusySendMode.QUEUE)
            viewModel.sendMessage("FIFO second", BusySendMode.QUEUE)
            advanceUntilIdle()

            holdPersistence = true
            mockEventsFlow.emit(WsEvent.MessageComplete("Done", sessionId))
            advanceUntilIdle()
            assertEquals(1, preparationCount)
            // A duplicate completion must not start another drain during pre-enqueue IO.
            mockEventsFlow.emit(WsEvent.MessageComplete("Done again", sessionId))
            advanceUntilIdle()
            val preparationsBeforeRelease = preparationCount
            releasePersistence.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, preparationsBeforeRelease)
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "FIFO first", any(), true) }
            verify(exactly = 0) { HermesWsClient.sendMessage(sessionId, "FIFO second", any(), any()) }
        }

    @Test
    fun slashQueueSharesDurableLocalQueueWhileBusy() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.sendMessage("/queue after tools")
            advanceUntilIdle()
            assertEquals(
                PendingSendState.QUEUED,
                viewModel.uiState.value.pendingSends
                    .single()
                    .state,
            )
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "after tools", any(), any()) }

            mockEventsFlow.emit(WsEvent.MessageComplete("Done", sessionId))
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "after tools", any(), true) }
        }

    @Test
    fun busyAttachmentIsSnapshottedAndQueuedWithoutRedirect() =
        runTest {
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val sourceUri = mockk<Uri>()
            val snapshotUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            lateinit var snapshotFile: java.io.File
            mockkStatic(Uri::class)
            every { Uri.parse("content://queued/photo") } returns sourceUri
            every { Uri.parse("file://queued-copy") } returns snapshotUri
            every { Uri.fromFile(any()) } answers {
                snapshotFile = firstArg()
                snapshotUri
            }
            every { snapshotUri.toString() } returns "file://queued-copy"
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(sourceUri) } answers { byteArrayOf(1, 2, 3).inputStream() }
            every { resolver.openInputStream(snapshotUri) } answers { snapshotFile.inputStream() }
            io.mockk.mockkConstructor(android.util.Base64OutputStream::class)
            every {
                anyConstructed<android.util.Base64OutputStream>().write(any<ByteArray>(), any(), any())
            } returns Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit
            every { HermesWsClient.request(WsMethods.IMAGE_ATTACH_BYTES, any(), any()) } returns
                CompletableDeferred<Any?>(mapOf("attached" to true))

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.addAttachment("content://queued/photo", "photo.png", "image/png", 3)
            viewModel.sendMessage("See photo")
            assertTrue("The receipt must not exist before the private snapshot completes", store.all().isEmpty())
            advanceUntilIdle()
            assertEquals(
                PendingSendState.QUEUED,
                viewModel.uiState.value.pendingSends
                    .single()
                    .state,
            )
            assertTrue(snapshotFile.exists())
            assertEquals(
                "file://queued-copy",
                store
                    .all()
                    .single()
                    .attachments
                    .single()
                    .uri,
            )
            verify(exactly = 0) { HermesWsClient.sendRedirect(any(), any(), any()) }

            mockEventsFlow.emit(WsEvent.MessageComplete("Done", sessionId))
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "See photo", any(), true) }
        }

    @Test
    fun manualAttachmentRetryCannotOverlapActiveQueuePreparation() =
        runTest {
            fakeRepo = spyk(fakeRepo)
            val releasePersistence = CompletableDeferred<Unit>()
            var holdPersistence = false
            var preparations = 0
            coEvery { fakeRepo.persistMessage(match { it.content == "Queued attachment" }, any()) } coAnswers {
                if (holdPersistence) {
                    preparations++
                    releasePersistence.await()
                }
                Unit
            }
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val sourceUri = mockk<Uri>()
            val snapshotUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            lateinit var snapshot: java.io.File
            mockkStatic(Uri::class)
            every { Uri.parse("content://queue/overlap") } returns sourceUri
            every { Uri.fromFile(any()) } answers {
                snapshot = firstArg()
                snapshotUri
            }
            every { snapshotUri.toString() } returns "file://queue-overlap"
            every { Uri.parse("file://queue-overlap") } returns snapshotUri
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(sourceUri) } answers { byteArrayOf(1, 2, 3).inputStream() }
            every { resolver.openInputStream(snapshotUri) } answers { snapshot.inputStream() }
            mockkConstructor(android.util.Base64OutputStream::class)
            every { anyConstructed<android.util.Base64OutputStream>().write(any<ByteArray>(), any(), any()) } returns
                Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit
            every { HermesWsClient.request(WsMethods.FILE_ATTACH, any(), any()) } returns
                CompletableDeferred<Any?>(mapOf("ref_text" to "@file:overlap.txt"))
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.addAttachment("content://queue/overlap", "overlap.txt", "text/plain", 3)
            viewModel.sendMessage("Queued attachment", BusySendMode.QUEUE)
            advanceUntilIdle()
            val receipt = store.all().single()
            holdPersistence = true
            mockEventsFlow.emit(WsEvent.MessageComplete("Done", sessionId))
            advanceUntilIdle()
            assertEquals(1, preparations)

            viewModel.sendQueuedNow(receipt.id)
            viewModel.sendQueuedNow(receipt.id)
            advanceUntilIdle()
            val preparationsBeforeRelease = preparations
            releasePersistence.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, preparationsBeforeRelease)
            assertEquals(1, store.all().size)
            assertEquals(PendingSendState.SENDING, store.all().single().state)
            verify(exactly = 1) { HermesWsClient.request(WsMethods.FILE_ATTACH, any(), any()) }
            verify(exactly = 1) {
                HermesWsClient.sendMessage(sessionId, "@file:overlap.txt\n\nQueued attachment", any(), true)
            }
            verify(exactly = 0) {
                HermesWsClient.sendMessage(sessionId, "@file:overlap.txt\n\nQueued attachment", any(), false)
            }
        }

    @Test
    fun idleAttachmentReceiptIsCommittedOnlyWithPrivateSnapshotUri() =
        runTest {
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val sourceUri = mockk<Uri>()
            val snapshotUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            lateinit var snapshotFile: java.io.File
            mockkStatic(Uri::class)
            every { Uri.parse("content://idle/photo") } returns sourceUri
            every { Uri.fromFile(any()) } answers {
                snapshotFile = firstArg()
                snapshotUri
            }
            every { snapshotUri.toString() } returns "file://private-idle-copy"
            every { Uri.parse("file://private-idle-copy") } returns snapshotUri
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(sourceUri) } answers { byteArrayOf(1, 2, 3).inputStream() }
            every { resolver.openInputStream(snapshotUri) } answers { snapshotFile.inputStream() }
            mockkConstructor(android.util.Base64OutputStream::class)
            every { anyConstructed<android.util.Base64OutputStream>().write(any<ByteArray>(), any(), any()) } returns
                Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit
            every { HermesWsClient.request(WsMethods.IMAGE_ATTACH_BYTES, any(), any()) } returns
                CompletableDeferred<Any?>(mapOf("attached" to true))

            viewModel.addAttachment("content://idle/photo", "photo.png", "image/png", 3)
            assertTrue(viewModel.sendMessage("Idle attachment"))
            assertTrue(store.all().isEmpty())
            advanceUntilIdle()

            assertTrue(snapshotFile.exists())
            assertEquals(
                "file://private-idle-copy",
                store
                    .all()
                    .single()
                    .attachments
                    .single()
                    .uri,
            )
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Idle attachment", any(), false) }
        }

    @Test
    fun retiredReceiptKeepsStagedImageReadableWhileBubbleStillReferencesIt() =
        runTest {
            // #1459: retiring the delivery receipt deleted the only file the visible bubble could load.
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val sourceUri = mockk<Uri>()
            val snapshotUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            lateinit var snapshotFile: java.io.File
            mockkStatic(Uri::class)
            every { Uri.parse("content://retire/photo") } returns sourceUri
            every { Uri.fromFile(any()) } answers {
                snapshotFile = firstArg()
                snapshotUri
            }
            every { snapshotUri.toString() } returns "file://private-retire-copy"
            every { Uri.parse("file://private-retire-copy") } returns snapshotUri
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(sourceUri) } answers { byteArrayOf(1, 2, 3).inputStream() }
            every { resolver.openInputStream(snapshotUri) } answers { snapshotFile.inputStream() }
            mockkConstructor(android.util.Base64OutputStream::class)
            every { anyConstructed<android.util.Base64OutputStream>().write(any<ByteArray>(), any(), any()) } returns
                Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit
            every { HermesWsClient.request(WsMethods.IMAGE_ATTACH_BYTES, any(), any()) } returns
                CompletableDeferred<Any?>(mapOf("attached" to true))
            every { HermesWsClient.sendMessage(sessionId, "Retire me", any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("retire-submit")
                "retire-submit"
            }

            viewModel.addAttachment("content://retire/photo", "photo.png", "image/png", 3)
            assertTrue(viewModel.sendMessage("Retire me"))
            advanceUntilIdle()
            mockEventsFlow.emit(
                WsEvent.RpcResult("retire-submit", mapOf("status" to "streaming", "user_row_id" to 91)),
            )
            advanceUntilIdle()
            assertTrue(snapshotFile.exists())

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(
                WsEvent.MessageComplete(
                    "Reply",
                    sessionId,
                    rawPayload =
                        mapOf(
                            "persisted_turn" to
                                mapOf("row_ids" to listOf(91, 92), "complete" to true, "user_row_id" to 91),
                        ),
                ),
            )
            advanceUntilIdle()

            assertTrue("The delivery receipt must retire", store.all().isEmpty())
            val bubble =
                viewModel.uiState.value.messages
                    .single { it.role == MessageRole.USER }
            assertEquals("file://private-retire-copy", bubble.attachments?.single()?.uri)
            assertTrue("The bubble's only image source was deleted at receipt retirement", snapshotFile.exists())
        }

    @Test
    fun interruptAttachmentReceiptIsPrivateBeforeInterruptRequest() =
        runTest {
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val sourceUri = mockk<Uri>()
            val snapshotUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            mockkStatic(Uri::class)
            every { Uri.parse("content://interrupt/note") } returns sourceUri
            every { Uri.fromFile(any()) } returns snapshotUri
            every { snapshotUri.toString() } returns "file://private-interrupt-copy"
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(sourceUri) } answers { byteArrayOf(4, 5, 6).inputStream() }

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.addAttachment("content://interrupt/note", "note.txt", "text/plain", 3)
            assertTrue(viewModel.sendMessage("Replace with file", BusySendMode.INTERRUPT))
            assertTrue(store.all().isEmpty())
            advanceUntilIdle()

            assertEquals(
                "file://private-interrupt-copy",
                store
                    .all()
                    .single()
                    .attachments
                    .single()
                    .uri,
            )
            verify(exactly = 1) { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Replace with file", any(), any()) }
        }

    @Test
    fun slowAttachmentPrecommitKeepsLaterTextBehindItsReceipt() =
        runTest {
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val sourceUri = mockk<Uri>()
            val snapshotUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            lateinit var snapshotFile: java.io.File
            mockkStatic(Uri::class)
            every { Uri.parse("content://slow/first") } returns sourceUri
            every { Uri.fromFile(any()) } answers {
                snapshotFile = firstArg()
                snapshotUri
            }
            every { snapshotUri.toString() } returns "file://private-first-copy"
            every { Uri.parse("file://private-first-copy") } returns snapshotUri
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(sourceUri) } answers {
                assertTrue(viewModel.sendMessage("Later text"))
                byteArrayOf(1, 2, 3).inputStream()
            }
            every { resolver.openInputStream(snapshotUri) } answers { snapshotFile.inputStream() }
            mockkConstructor(android.util.Base64OutputStream::class)
            every { anyConstructed<android.util.Base64OutputStream>().write(any<ByteArray>(), any(), any()) } returns
                Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit
            every { HermesWsClient.request(WsMethods.IMAGE_ATTACH_BYTES, any(), any()) } returns
                CompletableDeferred<Any?>(mapOf("attached" to true))

            viewModel.addAttachment("content://slow/first", "first.png", "image/png", 3)
            assertTrue(viewModel.sendMessage("First attachment"))
            advanceUntilIdle()

            assertEquals(listOf("First attachment", "Later text"), store.all().map { it.text })
            assertTrue(store.all().zipWithNext().all { (a, b) -> a.createdAt < b.createdAt })
            assertEquals(PendingSendState.SENDING, store.all()[0].state)
            assertEquals(PendingSendState.QUEUED, store.all()[1].state)
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "First attachment", any(), false) }
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Later text", any(), any()) }
        }

    @Test
    fun busyAttachmentSnapshotFailureRestoresDraftWithoutReceiptOrGhost() =
        runTest {
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val sourceUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            mockkStatic(Uri::class)
            every { Uri.parse("content://queued/missing") } returns sourceUri
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(sourceUri) } returns null

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.addAttachment("content://queued/missing", "missing.txt", "text/plain", 4)

            assertTrue(viewModel.sendMessage("Keep my draft", BusySendMode.QUEUE))
            advanceUntilIdle()

            assertTrue(store.all().isEmpty())
            assertEquals("Keep my draft", viewModel.uiState.value.composerTextToRestore)
            assertEquals(
                "content://queued/missing",
                viewModel.uiState.value.pendingAttachments
                    .single()
                    .uri,
            )
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.content == "Keep my draft" },
            )
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Keep my draft", any(), any()) }
        }

    @Test
    fun busyAttachmentSnapshotFinishingAfterSessionSwitchDoesNotCommitOrSubmit() =
        runTest {
            stubSession456Rests(success = true)
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val sourceUri = mockk<Uri>()
            val snapshotUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            lateinit var stagedFile: java.io.File
            mockkStatic(Uri::class)
            every { Uri.parse("content://queued/session-switch") } returns sourceUri
            every { Uri.fromFile(any()) } answers {
                stagedFile = firstArg()
                snapshotUri
            }
            every { snapshotUri.toString() } returns "file://stale-copy"
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(sourceUri) } answers {
                viewModel.switchSession("session-456")
                byteArrayOf(1, 2, 3).inputStream()
            }

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.addAttachment(
                "content://queued/session-switch",
                "switch.txt",
                "text/plain",
                3,
            )
            assertTrue(viewModel.sendMessage("Old session queued", BusySendMode.QUEUE))
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.content == "Old session queued" },
            )

            advanceUntilIdle()

            assertEquals("session-456", viewModel.uiState.value.currentSessionId)
            assertTrue(store.all().isEmpty())
            assertFalse(stagedFile.exists())
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Old session queued", any(), any()) }
        }

    @Test
    fun idleIgnoresBusyModeAndSubmitsNormally() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            assertTrue(viewModel.sendMessage("Idle", BusySendMode.GUIDE))
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Idle", any(), false) }
            verify(exactly = 0) { HermesWsClient.send(WsMethods.SESSION_STEER, any(), any()) }
        }

    @Test
    fun subagentActivityAloneDoesNotTurnAnIdleMainSessionBusy() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(
                WsEvent.SubagentEvent(
                    type = "subagent.progress",
                    payload = mapOf("subagent_id" to "child-1", "status" to "running"),
                    sessionId = sessionId,
                ),
            )
            advanceUntilIdle()
            viewModel.sendMessage("Main prompt", BusySendMode.GUIDE)
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Main prompt", any(), false) }
            verify(exactly = 0) { HermesWsClient.send(WsMethods.SESSION_STEER, any(), any()) }
        }

    @Test
    fun toolStartMarksMainTurnBusyEvenWithoutMessageStart() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(WsEvent.ToolStart("shell", emptyMap(), sessionId))
            advanceUntilIdle()
            viewModel.sendMessage("Guide", BusySendMode.GUIDE)
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.send(WsMethods.SESSION_STEER, any(), any()) }
            verify(exactly = 0) { HermesWsClient.sendMessage(sessionId, "Guide", any(), any()) }
        }

    @Test
    fun refreshSettingsPicksUpChangedSavedBusyMode() =
        runTest {
            var savedMode = BusySendMode.CORRECT
            every { AuthManager.getBusySendMode() } answers { savedMode }
            val viewModel = createViewModel()
            advanceUntilIdle()
            assertEquals(BusySendMode.CORRECT, viewModel.uiState.value.busySendMode)

            savedMode = BusySendMode.GUIDE
            viewModel.refreshSettings()
            advanceUntilIdle()
            assertEquals(BusySendMode.GUIDE, viewModel.uiState.value.busySendMode)
        }

    @Test
    fun rapidDuplicateTapCreatesOneReceiptAndOneRpc() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            assertTrue(viewModel.sendMessage("One tap"))
            assertFalse(viewModel.sendMessage("One tap"))
            advanceUntilIdle()
            assertEquals(1, viewModel.uiState.value.pendingSends.size)
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "One tap", any(), any()) }
        }

    @Test
    fun stalePromptAckAfterSessionSwitchKeepsOldReceiptAndCurrentSessionClear() =
        runTest {
            stubSession456Rests(success = true)
            val sendStore = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = sendStore)
            val requestId = "old-prompt"
            every { HermesWsClient.sendMessage(sessionId, "Old session", any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke(requestId)
                requestId
            }
            viewModel.sendMessage("Old session")
            advanceUntilIdle()
            viewModel.switchSession("session-456")
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.RpcResult(requestId, mapOf("status" to "streaming")))
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.pendingSends
                    .isEmpty(),
            )
            // The exact old RPC ACK belongs to its original receipt, not the new chat.
            assertEquals(PendingSendState.ACCEPTED, sendStore.all().single().state)
            assertTrue(sendStore.all().single().requiresExactReconciliation)
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Old session", any(), any()) }
        }

    @Test
    fun lateRpcErrorCannotOverwriteNewerReceiptAttempt() =
        runTest {
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            every { HermesWsClient.sendMessage(sessionId, "Retry me", any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("attempt-one")
                "attempt-one"
            }
            assertTrue(viewModel.sendMessage("Retry me"))
            advanceUntilIdle()
            val receipt = store.all().single()
            store.update(receipt.id) { it.copy(attempts = it.attempts + 1, state = PendingSendState.ACCEPTED) }

            mockEventsFlow.emit(WsEvent.RpcError("attempt-one", JsonRpcError(4010, "old failure")))
            advanceUntilIdle()
            assertEquals(PendingSendState.ACCEPTED, store.all().single().state)
            assertEquals(receipt.attempts + 1, store.all().single().attempts)
        }

    @Test
    fun lateAckForReplacedSameSessionReceiptCannotConfirmNewAttemptOrMessage() =
        runTest {
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            every { HermesWsClient.sendMessage(sessionId, "Replaced ACK", any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("old-ack")
                "old-ack"
            }
            assertTrue(viewModel.sendMessage("Replaced ACK"))
            advanceUntilIdle()
            val original = store.all().single()
            val replacement = original.copy(attempts = original.attempts + 1, state = PendingSendState.SENDING)
            store.put(replacement)
            val messages = viewModel.uiState.value.messages

            mockEventsFlow.emit(WsEvent.RpcResult("old-ack", mapOf("status" to "streaming", "user_row_id" to 991)))
            advanceUntilIdle()

            assertEquals(replacement, store.all().single())
            assertEquals(messages, viewModel.uiState.value.messages)
            assertNull(
                viewModel.uiState.value.messages
                    .single { it.id == original.id }
                    .serverRowId,
            )
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Replaced ACK", any(), any()) }
        }

    @Test
    fun lateErrorForReplacedSameSessionReceiptCannotRejectNewAttemptOrClearMessages() =
        runTest {
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            every { HermesWsClient.sendMessage(sessionId, "Replaced error", any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("old-error")
                "old-error"
            }
            assertTrue(viewModel.sendMessage("Replaced error"))
            advanceUntilIdle()
            val original = store.all().single()
            val replacement = original.copy(attempts = original.attempts + 1, state = PendingSendState.SENDING)
            store.put(replacement)
            val messages = viewModel.uiState.value.messages
            val errorMessage = viewModel.uiState.value.errorMessage

            mockEventsFlow.emit(WsEvent.RpcError("old-error", JsonRpcError(4001, "stale rejection")))
            advanceUntilIdle()

            assertEquals(replacement, store.all().single())
            assertEquals(messages, viewModel.uiState.value.messages)
            assertEquals(errorMessage, viewModel.uiState.value.errorMessage)
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Replaced error", any(), any()) }
        }

    @Test
    fun expiredRequestForReplacedSameSessionReceiptCannotQuarantineNewAttempt() =
        runTest {
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            every { HermesWsClient.sendMessage(sessionId, "Replaced timeout", any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("old-timeout")
                "old-timeout"
            }
            assertTrue(viewModel.sendMessage("Replaced timeout"))
            advanceUntilIdle()
            val original = store.all().single()
            val replacement = original.copy(attempts = original.attempts + 1, state = PendingSendState.SENDING)
            store.put(replacement)
            val messages = viewModel.uiState.value.messages

            viewModel.expireOutgoingRequest("old-timeout")
            advanceUntilIdle()

            assertEquals(replacement, store.all().single())
            assertEquals(messages, viewModel.uiState.value.messages)
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Replaced timeout", any(), any()) }
        }

    @Test
    fun expiredHardInterruptForReplacedSameSessionReceiptCannotQuarantineNewAttempt() =
        runTest {
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            assertTrue(viewModel.sendMessage("Replaced interrupt", BusySendMode.INTERRUPT))
            advanceUntilIdle()
            val interruptId = sentRequestMethods.last { it.first == WsMethods.SESSION_INTERRUPT }.second
            val original = store.all().single()
            val replacement = original.copy(attempts = original.attempts + 1, state = PendingSendState.SENDING)
            store.put(replacement)
            val messages = viewModel.uiState.value.messages

            viewModel.expireOutgoingRequest(interruptId)
            advanceUntilIdle()

            assertEquals(replacement, store.all().single())
            assertEquals(messages, viewModel.uiState.value.messages)
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Replaced interrupt", any(), any()) }
        }

    @Test
    fun lateHardInterruptAckCannotDispatchReplacedSameSessionAttempt() =
        runTest {
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            assertTrue(viewModel.sendMessage("Interrupt replacement", BusySendMode.INTERRUPT))
            advanceUntilIdle()
            val id = sentRequestMethods.last { it.first == WsMethods.SESSION_INTERRUPT }.second
            val receipt = store.all().single()
            val replacement = receipt.copy(attempts = receipt.attempts + 1, state = PendingSendState.SENDING)
            store.put(replacement)
            val before = viewModel.uiState.value
            val streamBefore = viewModel.streamingState.value

            mockEventsFlow.emit(WsEvent.RpcResult(id, mapOf("status" to "interrupted")))
            advanceUntilIdle()

            assertEquals(replacement, store.all().single())
            assertEquals(before.messages, viewModel.uiState.value.messages)
            assertEquals(before, viewModel.uiState.value)
            assertEquals(streamBefore, viewModel.streamingState.value)
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Interrupt replacement", any(), any()) }
        }

    @Test
    fun lateHardInterruptErrorCannotRejectReplacedSameSessionAttempt() =
        runTest {
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            assertTrue(viewModel.sendMessage("Interrupt replacement", BusySendMode.INTERRUPT))
            advanceUntilIdle()
            val id = sentRequestMethods.last { it.first == WsMethods.SESSION_INTERRUPT }.second
            val receipt = store.all().single()
            val replacement = receipt.copy(attempts = receipt.attempts + 1, state = PendingSendState.SENDING)
            store.put(replacement)
            val before = viewModel.uiState.value
            val streamBefore = viewModel.streamingState.value

            mockEventsFlow.emit(WsEvent.RpcError(id, JsonRpcError(4001, "old interrupt")))
            advanceUntilIdle()

            assertEquals(replacement, store.all().single())
            assertEquals(before.messages, viewModel.uiState.value.messages)
            assertEquals(before, viewModel.uiState.value)
            assertEquals(streamBefore, viewModel.streamingState.value)
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Interrupt replacement", any(), any()) }
        }

    @Test
    fun lateHardInterruptAckCannotQuarantineReplacedStaleSessionAttempt() =
        runTest {
            stubSession456Rests(success = true)
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            assertTrue(viewModel.sendMessage("Interrupt replacement", BusySendMode.INTERRUPT))
            advanceUntilIdle()
            val id = sentRequestMethods.last { it.first == WsMethods.SESSION_INTERRUPT }.second
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            val receipt = store.all().single()
            val replacement = receipt.copy(attempts = receipt.attempts + 1, state = PendingSendState.SENDING)
            store.put(replacement)
            val before = viewModel.uiState.value
            val streamBefore = viewModel.streamingState.value

            mockEventsFlow.emit(WsEvent.RpcResult(id, mapOf("status" to "interrupted")))
            advanceUntilIdle()

            assertEquals(replacement, store.all().single())
            assertEquals(before.messages, viewModel.uiState.value.messages)
            assertEquals(before, viewModel.uiState.value)
            assertEquals(streamBefore, viewModel.streamingState.value)
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Interrupt replacement", any(), any()) }
        }

    @Test
    fun lateHardInterruptErrorCannotQuarantineReplacedStaleSessionAttempt() =
        runTest {
            stubSession456Rests(success = true)
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            assertTrue(viewModel.sendMessage("Interrupt replacement", BusySendMode.INTERRUPT))
            advanceUntilIdle()
            val id = sentRequestMethods.last { it.first == WsMethods.SESSION_INTERRUPT }.second
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            val receipt = store.all().single()
            val replacement = receipt.copy(attempts = receipt.attempts + 1, state = PendingSendState.SENDING)
            store.put(replacement)
            val before = viewModel.uiState.value
            val streamBefore = viewModel.streamingState.value

            mockEventsFlow.emit(WsEvent.RpcError(id, JsonRpcError(4001, "old interrupt")))
            advanceUntilIdle()

            assertEquals(replacement, store.all().single())
            assertEquals(before.messages, viewModel.uiState.value.messages)
            assertEquals(before, viewModel.uiState.value)
            assertEquals(streamBefore, viewModel.streamingState.value)
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Interrupt replacement", any(), any()) }
        }

    @Test
    fun staleSessionRpcErrorCannotOverwriteReceiptFromOldSession() =
        runTest {
            stubSession456Rests(success = true)
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            every { HermesWsClient.sendMessage(sessionId, "Old session error", any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("old-error")
                "old-error"
            }
            assertTrue(viewModel.sendMessage("Old session error"))
            advanceUntilIdle()
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            val receipt = store.all().single()
            store.update(receipt.id) { it.copy(state = PendingSendState.ACCEPTED) }

            mockEventsFlow.emit(WsEvent.RpcError("old-error", JsonRpcError(4001, "old failure")))
            advanceUntilIdle()
            assertEquals(PendingSendState.ACCEPTED, store.all().single().state)
        }

    @Test
    fun completePersistedTurnRetiresOnlyTheReceiptBoundToItsUserRow() =
        runTest {
            val sendStore = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = sendStore)
            every { HermesWsClient.sendMessage(sessionId, "Durable", any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("durable-submit")
                "durable-submit"
            }
            viewModel.sendMessage("Durable")
            advanceUntilIdle()
            mockEventsFlow.emit(
                WsEvent.RpcResult("durable-submit", mapOf("status" to "streaming", "user_row_id" to 77)),
            )
            advanceUntilIdle()

            val receipt = sendStore.all().single()
            assertEquals(77L, receipt.userRowId)
            assertEquals(
                77L,
                viewModel.uiState.value.messages
                    .single { it.id == receipt.id }
                    .serverRowId,
            )

            // Partial and foreign receipts never retire the local turn.
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(
                WsEvent.MessageComplete(
                    "Reply",
                    sessionId,
                    rawPayload =
                        mapOf(
                            "persisted_turn" to
                                mapOf(
                                    "row_ids" to listOf(77),
                                    "complete" to false,
                                    "user_row_id" to 77,
                                ),
                        ),
                ),
            )
            advanceUntilIdle()
            assertEquals(listOf(receipt.id), sendStore.all().map { it.id })
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(
                WsEvent.MessageComplete(
                    "Reply",
                    sessionId,
                    rawPayload =
                        mapOf(
                            "persisted_turn" to mapOf("row_ids" to listOf(78), "complete" to true, "user_row_id" to 78),
                        ),
                ),
            )
            advanceUntilIdle()
            assertEquals(listOf(receipt.id), sendStore.all().map { it.id })

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(
                WsEvent.MessageComplete(
                    "Reply",
                    sessionId,
                    rawPayload =
                        mapOf(
                            "persisted_turn" to
                                mapOf(
                                    "row_ids" to listOf(77, 79),
                                    "complete" to true,
                                    "user_row_id" to 77,
                                    "final_assistant_row_id" to 79,
                                ),
                        ),
                ),
            )
            advanceUntilIdle()
            assertTrue(sendStore.all().isEmpty())
            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.id == receipt.id },
            )
        }

    @Test
    fun submitAckWithoutUserRowIdLeavesReceiptUnproven() =
        runTest {
            val sendStore = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = sendStore)
            every { HermesWsClient.sendMessage(sessionId, "Legacy", any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("legacy-submit")
                "legacy-submit"
            }
            viewModel.sendMessage("Legacy")
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult("legacy-submit", mapOf("status" to "streaming")))
            advanceUntilIdle()

            val receipt = sendStore.all().single()
            assertNull(receipt.userRowId)
            assertEquals(PendingSendState.ACCEPTED, receipt.state)
            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.id == receipt.id && it.serverRowId == null },
            )
        }

    @Test
    fun busyGuideUsesSteerAndUnsupportedFallsBackToQueueWithoutInterrupt() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()

            viewModel.sendMessage("Keep the tests", BusySendMode.GUIDE)
            advanceUntilIdle()
            val id = sentRequestMethods.last { it.first == WsMethods.SESSION_STEER }.second
            mockEventsFlow.emit(WsEvent.RpcError(id, JsonRpcError(4010, "steer unsupported")))
            advanceUntilIdle()

            assertEquals(
                PendingSendState.QUEUED,
                viewModel.uiState.value.pendingSends
                    .single()
                    .state,
            )
            verify(exactly = 0) { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
        }

    @Test
    fun interruptWithUnknownDeliverySubmitsWithoutReplayingEarlierPrompt() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            every { HermesWsClient.sendMessage(sessionId, "Uncertain", any(), false) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("uncertain-submit")
                "uncertain-submit"
            }

            viewModel.sendMessage("Uncertain")
            advanceUntilIdle()
            viewModel.expireOutgoingRequest("uncertain-submit")
            advanceUntilIdle()

            viewModel.sendMessage("Replacement", BusySendMode.INTERRUPT)
            advanceUntilIdle()

            val replacement =
                viewModel.uiState.value.pendingSends
                    .single { it.text == "Replacement" }
            assertEquals(BusySendMode.CORRECT, replacement.mode)
            assertEquals(PendingSendState.SENDING, replacement.state)
            assertEquals(
                PendingSendState.UNKNOWN,
                viewModel.uiState.value.pendingSends
                    .single { it.text == "Uncertain" }
                    .state,
            )
            verify(exactly = 0) { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
            verify(exactly = 1) { HermesWsClient.sendMessage(any(), "Replacement", any(), any()) }
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Uncertain", any(), any()) }
        }

    @Test
    fun interruptWithAcceptedDeliverySubmitsWithoutReplayingEarlierPrompt() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            every { HermesWsClient.sendMessage(sessionId, "Accepted", any(), false) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("accepted-submit")
                "accepted-submit"
            }

            viewModel.sendMessage("Accepted")
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult("accepted-submit", mapOf("status" to "streaming")))
            advanceUntilIdle()

            viewModel.sendMessage("Replacement", BusySendMode.INTERRUPT)
            advanceUntilIdle()

            val replacement =
                viewModel.uiState.value.pendingSends
                    .single { it.text == "Replacement" }
            assertEquals(BusySendMode.CORRECT, replacement.mode)
            assertEquals(PendingSendState.SENDING, replacement.state)
            assertEquals(
                PendingSendState.ACCEPTED,
                viewModel.uiState.value.pendingSends
                    .single { it.text == "Accepted" }
                    .state,
            )
            verify(exactly = 0) { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
            verify(exactly = 1) { HermesWsClient.sendMessage(any(), "Replacement", any(), any()) }
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Accepted", any(), any()) }
        }

    @Test
    fun interruptAfterCompletedTurnSubmitsNormally() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(WsEvent.MessageComplete("Done", sessionId))
            advanceUntilIdle()

            viewModel.sendMessage("Fresh turn", BusySendMode.INTERRUPT)
            advanceUntilIdle()

            verify(exactly = 0) { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Fresh turn", any(), false) }
        }

    @Test
    fun lateCompletionAfterInterruptUpdatesSealedReplyInPlace() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(WsEvent.MessageToken("partial reply", sessionId))
            advanceUntilIdle()
            val originalId =
                viewModel.streamingState.value.streamingMessage!!
                    .id

            viewModel.interruptSession()
            advanceUntilIdle()
            val id = sentRequestMethods.last { it.first == WsMethods.SESSION_INTERRUPT }.second
            mockEventsFlow.emit(WsEvent.RpcResult(id, mapOf("status" to "interrupted")))
            advanceUntilIdle()
            assertNull(viewModel.streamingState.value.streamingMessage)

            mockEventsFlow.emit(WsEvent.MessageToken("late tail", sessionId))
            advanceUntilIdle()
            assertNull(viewModel.streamingState.value.streamingMessage)
            mockEventsFlow.emit(WsEvent.MessageComplete("partial reply with final tail", sessionId))
            advanceUntilIdle()

            val messages = viewModel.uiState.value.messages
            val replies = messages.filter { it.role == MessageRole.ASSISTANT }
            assertEquals(1, replies.size)
            assertEquals(originalId, replies.single().id)
            assertEquals("partial reply with final tail", replies.single().content)
            assertTrue(
                messages.indexOf(replies.single()) < messages.indexOfFirst { it.content == "Session interrupted" },
            )
            assertNull(viewModel.streamingState.value.interruptedMessage)
        }

    @Test
    fun hardInterruptWaitsForAckBeforeSubmitting() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()

            viewModel.sendMessage("Urgent", BusySendMode.INTERRUPT)
            advanceUntilIdle()
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Urgent", any(), any()) }
            val id = sentRequestMethods.last { it.first == WsMethods.SESSION_INTERRUPT }.second
            mockEventsFlow.emit(WsEvent.RpcResult(id, mapOf("status" to "interrupted")))
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Urgent", any(), true) }
        }

    @Test
    fun notInterruptedQueuesKnownUnsentReplacementAndSubmitsExactlyOnce() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()

            viewModel.sendMessage("Replacement", BusySendMode.INTERRUPT)
            advanceUntilIdle()
            val interruptId = sentRequestMethods.last { it.first == WsMethods.SESSION_INTERRUPT }.second

            mockEventsFlow.emit(WsEvent.RpcResult(interruptId, mapOf("status" to "not_interrupted")))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult(interruptId, mapOf("status" to "not_interrupted")))
            advanceUntilIdle()

            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Replacement", any(), true) }
            assertEquals(
                PendingSendState.SENDING,
                viewModel.uiState.value.pendingSends
                    .single { it.text == "Replacement" }
                    .state,
            )
        }

    @Test
    fun lateNotInterruptedDoesNotClearOrRaceANewerSameSessionTurn() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.sendMessage("Replacement", BusySendMode.INTERRUPT)
            advanceUntilIdle()
            val interruptId = sentRequestMethods.last { it.first == WsMethods.SESSION_INTERRUPT }.second

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(WsEvent.MessageToken("new turn", sessionId))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult(interruptId, mapOf("status" to "not_interrupted")))
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isMainTurnBusy)
            assertEquals(
                "new turn",
                viewModel.streamingState.value.streamingMessage
                    ?.content,
            )
            assertEquals(
                PendingSendState.QUEUED,
                viewModel.uiState.value.pendingSends
                    .single { it.text == "Replacement" }
                    .state,
            )
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Replacement", any(), any()) }

            mockEventsFlow.emit(WsEvent.MessageComplete("Done", sessionId))
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Replacement", any(), true) }
        }

    @Test
    fun lateInterruptedDoesNotClearOrDispatchAcrossANewerSameSessionTurn() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.sendMessage("Replacement", BusySendMode.INTERRUPT)
            advanceUntilIdle()
            val interruptId = sentRequestMethods.last { it.first == WsMethods.SESSION_INTERRUPT }.second

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(WsEvent.MessageToken("new turn", sessionId))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult(interruptId, mapOf("status" to "interrupted")))
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isMainTurnBusy)
            assertEquals(
                "new turn",
                viewModel.streamingState.value.streamingMessage
                    ?.content,
            )
            assertEquals(
                PendingSendState.QUEUED,
                viewModel.uiState.value.pendingSends
                    .single { it.text == "Replacement" }
                    .state,
            )
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Replacement", any(), any()) }

            mockEventsFlow.emit(WsEvent.MessageComplete("Done", sessionId))
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Replacement", any(), true) }
        }

    @Test
    fun attachmentInterruptUsesTurnCapturedAtClickAndQueuesIfCopyOutlivesIt() =
        runTest {
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val sourceUri = mockk<Uri>()
            val snapshotUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            lateinit var snapshotFile: java.io.File
            mockkStatic(Uri::class)
            every { Uri.parse("content://interrupt/slow") } returns sourceUri
            every { Uri.fromFile(any()) } answers {
                snapshotFile = firstArg()
                snapshotUri
            }
            every { snapshotUri.toString() } returns "file://private-slow-copy"
            every { Uri.parse("file://private-slow-copy") } returns snapshotUri
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(sourceUri) } answers {
                mockEventsFlow.tryEmit(WsEvent.MessageStart(sessionId))
                byteArrayOf(1, 2, 3).inputStream()
            }
            every { resolver.openInputStream(snapshotUri) } answers { snapshotFile.inputStream() }
            mockkConstructor(android.util.Base64OutputStream::class)
            every { anyConstructed<android.util.Base64OutputStream>().write(any<ByteArray>(), any(), any()) } returns
                Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit
            every { HermesWsClient.request(WsMethods.FILE_ATTACH, any(), any()) } returns
                CompletableDeferred<Any?>(mapOf("ref_text" to "@file:slow.txt"))

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.addAttachment("content://interrupt/slow", "slow.txt", "text/plain", 3)
            assertTrue(viewModel.sendMessage("Captured turn", BusySendMode.INTERRUPT))
            advanceUntilIdle()

            val receipt = store.all().single()
            assertEquals(PendingSendState.QUEUED, receipt.state)
            assertEquals(BusySendMode.QUEUE, receipt.mode)
            verify(exactly = 0) { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }

            mockEventsFlow.emit(WsEvent.MessageComplete("Done", sessionId))
            advanceUntilIdle()
            verify(
                exactly = 1,
            ) { HermesWsClient.sendMessage(sessionId, "@file:slow.txt\n\nCaptured turn", any(), true) }
        }

    @Test
    fun stopParksLocalQueueAndSendNowUnparksIt() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.sendMessage("Later", BusySendMode.QUEUE)
            advanceUntilIdle()
            val queuedId =
                viewModel.uiState.value.pendingSends
                    .single()
                    .id

            viewModel.interruptSession()
            advanceUntilIdle()
            assertEquals(
                PendingSendState.PARKED,
                viewModel.uiState.value.pendingSends
                    .single()
                    .state,
            )
            val stopId = sentRequestMethods.last { it.first == WsMethods.SESSION_INTERRUPT }.second
            mockEventsFlow.emit(WsEvent.RpcResult(stopId, mapOf("status" to "interrupted")))
            advanceUntilIdle()
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Later", any(), any()) }

            viewModel.sendQueuedNow(queuedId)
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Later", any(), true) }
        }

    @Test
    fun acceptedReceiptReleasesNextQueuedSubmissionWithoutWaitingForHistory() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            every { HermesWsClient.sendMessage(sessionId, "First", any(), true) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("first-queued-submit")
                "first-queued-submit"
            }
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.sendMessage("First", BusySendMode.QUEUE)
            viewModel.sendMessage("Second", BusySendMode.QUEUE)
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.MessageComplete("Done", sessionId))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult("first-queued-submit", mapOf("status" to "queued")))
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            val second =
                viewModel.uiState.value.pendingSends
                    .single { it.text == "Second" }
            viewModel.sendQueuedNow(second.id)
            advanceUntilIdle()

            assertEquals(
                PendingSendState.SENDING,
                viewModel.uiState.value.pendingSends
                    .single { it.id == second.id }
                    .state,
            )
            verify(exactly = 0) { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Second", any(), true) }
        }

    @Test
    fun manualRetryRecommitsAttachmentOnlyAfterPrivateStaging() =
        runTest {
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val scope =
                listOf(
                    AuthManager.getBaseUrl(),
                    AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                    AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                ).joinToString("\u001f")
            val sourceUri = mockk<Uri>()
            val snapshotUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            lateinit var snapshotFile: java.io.File
            mockkStatic(Uri::class)
            every { Uri.parse("content://retry/note") } returns sourceUri
            every { Uri.fromFile(any()) } answers {
                snapshotFile = firstArg()
                snapshotUri
            }
            every { snapshotUri.toString() } returns "file://private-retry-copy"
            every { Uri.parse("file://private-retry-copy") } returns snapshotUri
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(sourceUri) } answers { byteArrayOf(7, 8, 9).inputStream() }
            every { resolver.openInputStream(snapshotUri) } answers { snapshotFile.inputStream() }
            mockkConstructor(android.util.Base64OutputStream::class)
            every { anyConstructed<android.util.Base64OutputStream>().write(any<ByteArray>(), any(), any()) } returns
                Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit
            every { HermesWsClient.request(WsMethods.FILE_ATTACH, any(), any()) } returns
                CompletableDeferred<Any?>(mapOf("ref_text" to "@file:note.txt"))
            store.put(
                PendingSend(
                    id = "legacy-retry",
                    scope = scope,
                    sessionId = sessionId,
                    text = "Retry attachment",
                    attachments = listOf(Attachment("content://retry/note", "note.txt", "text/plain", 3)),
                    mode = BusySendMode.QUEUE,
                    state = PendingSendState.REJECTED,
                ),
            )
            viewModel.refreshSettings()
            advanceUntilIdle()

            viewModel.sendQueuedNow("legacy-retry")
            viewModel.sendQueuedNow("legacy-retry")
            assertEquals(
                "content://retry/note",
                store
                    .all()
                    .single()
                    .attachments
                    .single()
                    .uri,
            )
            assertTrue(store.all().single().requiresAttachmentRecovery)
            advanceUntilIdle()

            assertEquals(
                "file://private-retry-copy",
                store
                    .all()
                    .single()
                    .attachments
                    .single()
                    .uri,
            )
            assertFalse(store.all().single().requiresAttachmentRecovery)
            verify(exactly = 1) {
                HermesWsClient.sendMessage(sessionId, "@file:note.txt\n\nRetry attachment", any(), false)
            }
        }

    @Test
    fun acknowledgedAttachmentRetryTimeoutIsUnacknowledged() =
        runTest {
            val store = ChatSendStore()
            val (vm, session) = createViewModelWithSession(sendStore = store)
            val scope =
                listOf(
                    AuthManager.getBaseUrl(),
                    AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                    AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                ).joinToString("\u001f")
            val uri = mockk<Uri>()
            val snapshotUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            lateinit var snapshotFile: java.io.File
            mockkStatic(Uri::class)
            every { Uri.parse("content://retry/ack") } returns uri
            every { Uri.fromFile(any()) } answers {
                snapshotFile = firstArg()
                snapshotUri
            }
            every { snapshotUri.toString() } returns "file://private-ack-retry-copy"
            every { Uri.parse("file://private-ack-retry-copy") } returns snapshotUri
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(uri) } answers { "hello".byteInputStream() }
            every { resolver.openInputStream(snapshotUri) } answers { snapshotFile.inputStream() }
            mockkConstructor(android.util.Base64OutputStream::class)
            every { anyConstructed<android.util.Base64OutputStream>().write(any<ByteArray>(), any(), any()) } returns
                Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit
            val attachmentRequest = slot<Map<String, Any>>()
            every { HermesWsClient.request(WsMethods.FILE_ATTACH, capture(attachmentRequest), any()) } returns
                CompletableDeferred<Any?>(mapOf("ref_text" to "@file:retry-ack.txt"))
            var submittedRequestId: String? = null
            every { HermesWsClient.sendMessage(session, any(), any(), any()) } answers {
                "attachment-retry-submit".also { id ->
                    submittedRequestId = id
                    arg<((String) -> Unit)?>(2)?.invoke(id)
                }
            }
            store.put(
                PendingSend(
                    id = "attachment-ack",
                    scope = scope,
                    sessionId = session,
                    text = "again",
                    attachments = listOf(Attachment("content://retry/ack", "retry-ack.txt", "text/plain", 5)),
                    mode = BusySendMode.QUEUE,
                    state = PendingSendState.UNKNOWN,
                    userOrderingReleased = true,
                ),
            )
            vm.sendQueuedNow("attachment-ack")
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.request(WsMethods.FILE_ATTACH, any(), any()) }
            assertEquals(session, (attachmentRequest.captured["session_id"] as JsonPrimitive).content)
            verify(exactly = 1) {
                HermesWsClient.sendMessage(session, "@file:retry-ack.txt\n\nagain", any(), false)
            }
            assertEquals("attachment-retry-submit", submittedRequestId)
            assertEquals(1, store.all().single().attempts)
            assertEquals(PendingSendState.SENDING, store.all().single().state)
            assertEquals(false, store.all().single().userOrderingReleased)
            vm.expireOutgoingRequest(requireNotNull(submittedRequestId))
            assertEquals(PendingSendState.UNKNOWN, store.all().single().state)
            assertEquals(false, store.all().single().userOrderingReleased)
        }

    @Test
    fun acknowledgedBusyTextRetryTimeoutIsUnacknowledged() =
        runTest {
            val store = ChatSendStore()
            val (vm, session) = createViewModelWithSession(sendStore = store)
            val scope =
                listOf(
                    AuthManager.getBaseUrl(),
                    AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                    AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                ).joinToString("\u001f")
            store.put(
                PendingSend(
                    id = "busy-ack",
                    scope = scope,
                    sessionId = session,
                    text = "busy retry",
                    mode = BusySendMode.QUEUE,
                    state = PendingSendState.UNKNOWN,
                    userOrderingReleased = true,
                ),
            )
            mockEventsFlow.emit(WsEvent.MessageStart(session))
            advanceUntilIdle()
            vm.sendQueuedNow("busy-ack")
            advanceUntilIdle()
            assertEquals(false, store.all().single().userOrderingReleased)
            val interruptId = sentRequestMethods.last { it.first == WsMethods.SESSION_INTERRUPT }.second
            vm.expireOutgoingRequest(interruptId)
            assertEquals(PendingSendState.UNKNOWN, store.all().single().state)
            assertEquals(false, store.all().single().userOrderingReleased)

            // The interrupt timeout above is not a prompt timeout. Retry the
            // uncertain receipt and complete the interrupt to reach prompt.submit.
            val attemptsAfterInterruptTimeout = store.all().single().attempts
            var submittedRequestId: String? = null
            every { HermesWsClient.sendMessage(session, "busy retry", any(), true) } answers {
                "busy-retry-submit".also { id ->
                    submittedRequestId = id
                    arg<((String) -> Unit)?>(2)?.invoke(id)
                }
            }
            vm.sendQueuedNow("busy-ack")
            advanceUntilIdle()
            val retryInterruptId = sentRequestMethods.last { it.first == WsMethods.SESSION_INTERRUPT }.second
            assertTrue(retryInterruptId != interruptId)
            assertEquals(attemptsAfterInterruptTimeout + 1, store.all().single().attempts)
            assertEquals(false, store.all().single().userOrderingReleased)
            verify(exactly = 0) { HermesWsClient.sendMessage(session, "busy retry", any(), any()) }

            mockEventsFlow.emit(WsEvent.RpcResult(retryInterruptId, mapOf("status" to "interrupted")))
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(session, "busy retry", any(), true) }
            assertEquals("busy-retry-submit", submittedRequestId)
            assertEquals(PendingSendState.SENDING, store.all().single().state)
            vm.expireOutgoingRequest(requireNotNull(submittedRequestId))
            assertEquals(PendingSendState.UNKNOWN, store.all().single().state)
            assertEquals(false, store.all().single().userOrderingReleased)
        }

    @Test
    fun manualRetryPreparationOversizePreservesOriginalReceipt() =
        runTest {
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val scope =
                listOf(
                    AuthManager.getBaseUrl(),
                    AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                    AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                ).joinToString("\u001f")
            val sourceUri = mockk<Uri>()
            val snapshotUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            mockkStatic(Uri::class)
            every { Uri.parse("content://retry/growing") } returns sourceUri
            every { Uri.fromFile(any()) } returns snapshotUri
            every { snapshotUri.toString() } returns "file://retry-growing-copy"
            every { Uri.parse("file://retry-growing-copy") } returns snapshotUri
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(sourceUri) } answers { byteArrayOf(1).inputStream() }
            every { resolver.openInputStream(snapshotUri) } answers {
                object : java.io.InputStream() {
                    private var remaining = MAX_CHAT_ATTACHMENT_BYTES + 1

                    override fun read(): Int = if (remaining-- > 0) 0 else -1

                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        if (remaining <= 0) return -1
                        val count = minOf(length.toLong(), remaining).toInt()
                        remaining -= count
                        return count
                    }
                }
            }
            mockkConstructor(android.util.Base64OutputStream::class)
            every { anyConstructed<android.util.Base64OutputStream>().write(any<ByteArray>(), any(), any()) } returns
                Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit
            store.put(
                PendingSend(
                    id = "retry-growing",
                    scope = scope,
                    sessionId = sessionId,
                    text = "Keep recovery receipt",
                    attachments =
                        listOf(
                            Attachment("content://retry/growing", "growing.bin", "application/octet-stream", 1),
                        ),
                    mode = BusySendMode.QUEUE,
                    state = PendingSendState.REJECTED,
                ),
            )
            val original = store.all().single()
            viewModel.refreshSettings()
            advanceUntilIdle()
            viewModel.sendQueuedNow(original.id)
            advanceUntilIdle()

            assertEquals(listOf(original), store.all())
            assertTrue(
                viewModel.uiState.value.errorMessage
                    ?.en()
                    ?.contains("too large") == true,
            )
            verify(exactly = 0) { HermesWsClient.request(WsMethods.FILE_ATTACH, any(), any()) }
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Keep recovery receipt", any(), any()) }
        }

    @Test
    fun manualRetryOwnerChangePreservesExistingPrivateSnapshot() =
        runTest {
            var baseUrl = "http://test.local/"
            every { AuthManager.getBaseUrl() } answers { baseUrl }
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val scope =
                listOf(
                    baseUrl,
                    AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                    AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                ).joinToString("\u001f")
            val snapshot = java.io.File(attachmentFilesDir, "chat-send/retry-existing/0")
            snapshot.parentFile.mkdirs()
            snapshot.writeBytes(byteArrayOf(1, 2, 3))
            val snapshotUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            mockkStatic(Uri::class)
            every { Uri.parse("file://retry-existing") } returns snapshotUri
            every { Uri.fromFile(any()) } returns snapshotUri
            every { snapshotUri.toString() } returns "file://retry-existing"
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(snapshotUri) } answers {
                baseUrl = "http://other-profile.local/"
                snapshot.inputStream()
            }
            mockkConstructor(android.util.Base64OutputStream::class)
            every { anyConstructed<android.util.Base64OutputStream>().write(any<ByteArray>(), any(), any()) } returns
                Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit
            val original =
                PendingSend(
                    id = "retry-existing",
                    scope = scope,
                    sessionId = sessionId,
                    text = "Keep existing snapshot",
                    attachments = listOf(Attachment("file://retry-existing", "note.txt", "text/plain", 3)),
                    mode = BusySendMode.QUEUE,
                    state = PendingSendState.UNKNOWN,
                )
            store.put(original)
            viewModel.sendQueuedNow(original.id)
            advanceUntilIdle()

            assertEquals(listOf(original), store.all())
            assertTrue("Rollback must retain the original receipt's private bytes", snapshot.exists())
            verify(exactly = 0) { HermesWsClient.request(WsMethods.FILE_ATTACH, any(), any()) }
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun manualRetryUnreadablePreparationPreservesUnknownReceipt() =
        runTest {
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val scope =
                listOf(
                    AuthManager.getBaseUrl(),
                    AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                    AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                ).joinToString("\u001f")
            val snapshot = java.io.File(attachmentFilesDir, "chat-send/retry-unreadable/0")
            snapshot.parentFile.mkdirs()
            snapshot.writeBytes(byteArrayOf(1))
            val snapshotUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            mockkStatic(Uri::class)
            every { Uri.parse("file://retry-unreadable") } returns snapshotUri
            every { Uri.fromFile(any()) } returns snapshotUri
            every { snapshotUri.toString() } returns "file://retry-unreadable"
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(snapshotUri) } returns null
            val original =
                PendingSend(
                    id = "retry-unreadable",
                    scope = scope,
                    sessionId = sessionId,
                    text = "Keep unknown receipt",
                    attachments = listOf(Attachment("file://retry-unreadable", "note.txt", "text/plain", 1)),
                    mode = BusySendMode.QUEUE,
                    state = PendingSendState.UNKNOWN,
                )
            store.put(original)
            viewModel.sendQueuedNow(original.id)
            advanceUntilIdle()

            assertEquals(listOf(original), store.all())
            verify(exactly = 0) { HermesWsClient.request(WsMethods.FILE_ATTACH, any(), any()) }
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun legacyContentUriReceiptIsQuarantinedUntilExplicitRecovery() =
        runTest {
            val store = ChatSendStore()
            val scope =
                listOf(
                    AuthManager.getBaseUrl(),
                    AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                    AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                ).joinToString("\u001f")
            store.put(
                PendingSend(
                    id = "legacy-auto-drain",
                    scope = scope,
                    sessionId = "session-123",
                    text = "Needs attachment recovery",
                    attachments = listOf(Attachment("content://legacy/lost", "lost.txt", "text/plain", 4)),
                    mode = BusySendMode.QUEUE,
                    state = PendingSendState.QUEUED,
                ),
            )
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)

            val receipt =
                viewModel.uiState.value.pendingSends
                    .single()
            assertEquals(PendingSendState.REJECTED, receipt.state)
            assertTrue(receipt.requiresAttachmentRecovery)
            assertEquals("Needs attachment recovery", receipt.text)
            assertEquals("lost.txt", receipt.attachments.single().name)

            mockEventsFlow.emit(WsEvent.MessageComplete("Done", sessionId))
            advanceUntilIdle()
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Needs attachment recovery", any(), any()) }
        }

    @Test
    fun manualAttachmentRetryKeepsOriginalReceiptAcrossSessionChange() =
        runTest {
            stubSession456Rests(success = true)
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val scope =
                listOf(
                    AuthManager.getBaseUrl(),
                    AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                    AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                ).joinToString("\u001f")
            val sourceUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            mockkStatic(Uri::class)
            every { Uri.parse("content://retry/session") } returns sourceUri
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(sourceUri) } answers {
                viewModel.switchSession("session-456")
                byteArrayOf(1, 2, 3).inputStream()
            }
            store.put(
                PendingSend(
                    id = "retry-session",
                    scope = scope,
                    sessionId = sessionId,
                    text = "Keep old session receipt",
                    attachments = listOf(Attachment("content://retry/session", "session.txt", "text/plain", 3)),
                    mode = BusySendMode.QUEUE,
                    state = PendingSendState.UNKNOWN,
                ),
            )
            viewModel.refreshSettings()

            viewModel.sendQueuedNow("retry-session")
            advanceUntilIdle()

            val preserved = store.all().single { it.id == "retry-session" }
            assertEquals("content://retry/session", preserved.attachments.single().uri)
            assertEquals(PendingSendState.REJECTED, preserved.state)
            assertTrue(preserved.requiresAttachmentRecovery)
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Keep old session receipt", any(), any()) }
        }

    @Test
    fun manualAttachmentRetryKeepsOriginalReceiptAcrossProfileScopeChange() =
        runTest {
            var baseUrl = "http://test.local/"
            every { AuthManager.getBaseUrl() } answers { baseUrl }
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val scope =
                listOf(
                    baseUrl,
                    AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                    AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                ).joinToString("\u001f")
            val sourceUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            mockkStatic(Uri::class)
            every { Uri.parse("content://retry/profile") } returns sourceUri
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(sourceUri) } answers {
                baseUrl = "http://other-profile.local/"
                byteArrayOf(1, 2, 3).inputStream()
            }
            store.put(
                PendingSend(
                    id = "retry-profile",
                    scope = scope,
                    sessionId = sessionId,
                    text = "Keep old profile receipt",
                    attachments = listOf(Attachment("content://retry/profile", "profile.txt", "text/plain", 3)),
                    mode = BusySendMode.QUEUE,
                    state = PendingSendState.UNKNOWN,
                ),
            )
            viewModel.refreshSettings()

            viewModel.sendQueuedNow("retry-profile")
            advanceUntilIdle()

            val preserved = store.all().single { it.id == "retry-profile" }
            assertEquals(scope, preserved.scope)
            assertEquals("content://retry/profile", preserved.attachments.single().uri)
            assertEquals(PendingSendState.REJECTED, preserved.state)
            assertTrue(preserved.requiresAttachmentRecovery)
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Keep old profile receipt", any(), any()) }
        }

    @Test
    fun `sendMessage while session create is in flight queues prompt and dispatches on SESSION_CREATE`() =
        runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            val sentPrompts = mutableListOf<String>()
            every { HermesWsClient.sendMessage(any(), capture(sentPrompts), any(), any()) } returns "req-send-1"

            vm.sendMessage("My super important long prompt")
            advanceUntilIdle()

            // Optimistic UI state has the user message immediately
            assertTrue(
                vm.uiState.value.messages
                    .any { it.content == "My super important long prompt" },
            )
            assertTrue(vm.uiState.value.isSending)
            // But wsClient.sendMessage not dispatched yet because session_id was null
            assertTrue(sentPrompts.isEmpty())

            // Now emit SESSION_CREATE result for the active session request
            val createReqId = sentRequestMethods.last { it.first == WsMethods.SESSION_CREATE }.second
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    createReqId,
                    mapOf("session_id" to "session-969", "stored_session_id" to "session-storage-969"),
                ),
            )
            advanceUntilIdle()

            // Prompt should be dispatched automatically!
            assertEquals(listOf("My super important long prompt"), sentPrompts)
            assertEquals("session-storage-969", vm.uiState.value.currentSessionId)
            assertTrue(
                vm.uiState.value.messages
                    .any { it.content == "My super important long prompt" },
            )
        }

    /**
     * The REST high-watermark must be read BEFORE prompt.submit leaves the
     * device. Capturing it afterwards lets a fast turn persist its assistant row
     * first, and a lower bound that already contains the reply can never exclude
     * a historical duplicate.
     */
    @Test
    fun sendMessage_readsTurnBoundaryBeforeSubmittingPrompt() =
        runTest {
            TurnCorrelationTracker.resetForTest()
            stubActiveProfile()
            val (viewModel, sessionId) = createViewModelWithSession()
            val events = mutableListOf<String>()
            val api = mockk<io.wutz.apollo.data.remote.HermesApiService>(relaxed = true)
            every { ApiClient.hermesApi } returns api
            coEvery { api.getSessionMessages(any(), any(), any(), any(), any(), any()) } answers {
                // Only the boundary probe asks for a single newest row.
                if (arg<Int?>(1) == 1 && arg<String?>(3) == "latest") events += "boundary"
                retrofit2.Response.success(
                    SessionMessagesResponse(
                        messages = listOf(SessionMessage(id = 41, role = "user", content = JsonPrimitive("hi"))),
                        pagination = PaginationInfo(limit = 1, offset = 0, order = "latest", returned = 1),
                    ),
                )
            }
            every { HermesWsClient.sendMessage(any(), any(), any(), any()) } answers {
                events += "submit"
                reqCount++
                val id = "req-msg-$reqCount"
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            viewModel.sendMessage("hello there")
            advanceUntilIdle()

            assertEquals(listOf("boundary", "submit"), events)
            assertEquals(41, TurnCorrelationTracker.boundaryFor("default", sessionId)?.beforeMessageId)
        }

    @Test
    fun sendMessage_stillSubmitsWhenBoundaryReadFails() =
        runTest {
            TurnCorrelationTracker.resetForTest()
            stubActiveProfile()
            val (viewModel, sessionId) = createViewModelWithSession()
            val api = mockk<io.wutz.apollo.data.remote.HermesApiService>(relaxed = true)
            every { ApiClient.hermesApi } returns api
            coEvery { api.getSessionMessages(any(), any(), any(), any(), any(), any()) } returns
                retrofit2.Response.error(500, "boom".toResponseBody())
            var submits = 0
            every { HermesWsClient.sendMessage(any(), any(), any(), any()) } answers {
                submits++
                reqCount++
                val id = "req-msg-$reqCount"
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            viewModel.sendMessage("hello there")
            advanceUntilIdle()

            assertEquals("REST health must never block sending chat", 1, submits)
            assertNull(TurnCorrelationTracker.boundaryFor("default", sessionId))
        }

    /**
     * A gateway that ignores `order=latest` answers a `limit=1` probe with the
     * OLDEST row; trusting that number would arm a boundary that is far too low
     * and let historical duplicates win. Without the pagination proof the turn is
     * left uncorrelatable, and the prompt still goes out.
     */
    @Test
    fun sendMessage_leavesTurnUncorrelatableWhenGatewayCannotConfirmOrder() =
        runTest {
            TurnCorrelationTracker.resetForTest()
            stubActiveProfile()
            val (viewModel, sessionId) = createViewModelWithSession()
            val api = mockk<io.wutz.apollo.data.remote.HermesApiService>(relaxed = true)
            every { ApiClient.hermesApi } returns api
            coEvery { api.getSessionMessages(any(), any(), any(), any(), any(), any()) } answers {
                retrofit2.Response.success(
                    SessionMessagesResponse(
                        messages = listOf(SessionMessage(id = 1, role = "user", content = JsonPrimitive("oldest"))),
                    ),
                )
            }
            var submits = 0
            every { HermesWsClient.sendMessage(any(), any(), any(), any()) } answers {
                submits++
                reqCount++
                val id = "req-msg-$reqCount"
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            viewModel.sendMessage("hello there")
            advanceUntilIdle()

            assertEquals(1, submits)
            assertNull(
                "Unconfirmed row order must not produce a boundary",
                TurnCorrelationTracker.boundaryFor("default", sessionId),
            )
        }

    /**
     * `AuthManager.activeProfileId` is null until a server profile is explicitly
     * selected, and the rest of AuthManager treats that as
     * [AuthManager.DEFAULT_PROFILE_ID]. A raw `.orEmpty()` made the whole feature
     * a no-op on a normal install: no boundary armed, so no reply notification
     * could ever be REST-auto-dismissed.
     */
    @Test
    fun sendMessage_nullActiveProfileArmsBoundaryUnderTheDefaultScope() =
        runTest {
            TurnCorrelationTracker.resetForTest()
            every { AuthManager.activeProfileId } returns MutableStateFlow<String?>(null)
            val (viewModel, sessionId) = createViewModelWithSession()
            val api = mockk<io.wutz.apollo.data.remote.HermesApiService>(relaxed = true)
            every { ApiClient.hermesApi } returns api
            coEvery { api.getSessionMessages(any(), any(), any(), any(), any(), any()) } answers {
                retrofit2.Response.success(
                    SessionMessagesResponse(
                        messages = listOf(SessionMessage(id = 41, role = "user", content = JsonPrimitive("hi"))),
                        pagination = PaginationInfo(limit = 1, offset = 0, order = "latest", returned = 1),
                    ),
                )
            }
            var submits = 0
            every { HermesWsClient.sendMessage(any(), any(), any(), any()) } answers {
                submits++
                reqCount++
                val id = "req-msg-$reqCount"
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            viewModel.sendMessage("hello there")
            advanceUntilIdle()

            assertEquals(1, submits)
            assertEquals(
                "A default-profile install must still be correlatable",
                41,
                TurnCorrelationTracker.boundaryFor(AuthManager.DEFAULT_PROFILE_ID, sessionId)?.beforeMessageId,
            )
        }

    /**
     * `pagination.order` is the only proof the gateway honoured `order=latest`.
     * A gateway that reports `oldest` answered the `limit=1` probe with the
     * OLDEST row, and that as a lower bound would sit below the whole transcript
     * — exactly what lets a historical duplicate win.
     */
    @Test
    fun sendMessage_leavesTurnUncorrelatableWhenGatewayReportsOldestOrder() =
        runTest {
            TurnCorrelationTracker.resetForTest()
            stubActiveProfile()
            val (viewModel, sessionId) = createViewModelWithSession()
            val api = mockk<io.wutz.apollo.data.remote.HermesApiService>(relaxed = true)
            every { ApiClient.hermesApi } returns api
            coEvery { api.getSessionMessages(any(), any(), any(), any(), any(), any()) } answers {
                retrofit2.Response.success(
                    SessionMessagesResponse(
                        messages = listOf(SessionMessage(id = 1, role = "user", content = JsonPrimitive("oldest"))),
                        pagination = PaginationInfo(limit = 1, offset = 0, order = "oldest", returned = 1),
                    ),
                )
            }
            var submits = 0
            every { HermesWsClient.sendMessage(any(), any(), any(), any()) } answers {
                submits++
                reqCount++
                val id = "req-msg-$reqCount"
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            viewModel.sendMessage("hello there")
            advanceUntilIdle()

            assertEquals(1, submits)
            assertNull(
                "An unproven row order must not produce a boundary",
                TurnCorrelationTracker.boundaryFor("default", sessionId),
            )
        }

    /**
     * A queued prompt runs behind a turn that is already in flight, so no clean
     * lower bound exists for it — it must not borrow the running turn's boundary.
     */
    @Test
    fun queuedPromptNeverArmsATurnBoundary() =
        runTest {
            TurnCorrelationTracker.resetForTest()
            stubActiveProfile()
            val (viewModel, sessionId) = createViewModelWithSession()
            val api = mockk<io.wutz.apollo.data.remote.HermesApiService>(relaxed = true)
            every { ApiClient.hermesApi } returns api
            every { HermesWsClient.sendMessage(any(), any(), any(), any()) } answers {
                reqCount++
                val id = "req-msg-$reqCount"
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            viewModel.sendMessage("/queue do the thing")
            advanceUntilIdle()

            io.mockk.coVerify(exactly = 0) {
                api.getSessionMessages(any(), any(), any(), any(), any(), any())
            }
            assertNull(TurnCorrelationTracker.boundaryFor("default", sessionId))
        }

    @Test
    fun sendVoiceNote_transcribesViaServerAndSubmitsTranscript() =
        runTest {
            TurnCorrelationTracker.resetForTest()
            stubActiveProfile()
            val api = mockk<io.wutz.apollo.data.remote.HermesApiService>(relaxed = true)
            every { ApiClient.hermesApi } returns api
            every { ApiClient.transcriptionService(any()) } returns api
            coEvery { api.transcribeAudio(any()) } returns
                Response.success(
                    AudioTranscriptionResponse(ok = true, transcript = "hello from voice"),
                )
            val (viewModel, _) = createViewModelWithSession()

            val submittedTexts = mutableListOf<String>()
            every { HermesWsClient.sendMessage(any(), any(), any(), any()) } answers {
                reqCount++
                val id = "req-msg-$reqCount"
                submittedTexts += arg<String>(1)
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            val voiceFile =
                java.io.File(attachmentCacheDir, "voice_note.m4a").apply {
                    writeBytes(byteArrayOf(1, 2, 3))
                }
            viewModel.sendVoiceNote(voiceFile)
            advanceUntilIdle()

            assertEquals("hello from voice", submittedTexts.lastOrNull())
            assertFalse(voiceFile.exists())
            assertNull(viewModel.uiState.value.errorMessage)
            assertFalse(viewModel.uiState.value.isTranscribingVoiceNote)
        }

    @Test
    fun sendVoiceNote_reportsTranscriptionFailureAndDeletesClip() =
        runTest {
            val api = mockk<io.wutz.apollo.data.remote.HermesApiService>(relaxed = true)
            every { ApiClient.hermesApi } returns api
            every { ApiClient.transcriptionService(any()) } returns api
            coEvery { api.transcribeAudio(any()) } returns
                Response.error(500, "transcribe failed".toResponseBody("text/plain".toMediaType()))
            val (viewModel, _) = createViewModelWithSession()

            val voiceFile =
                java.io.File(attachmentCacheDir, "voice_fail.m4a").apply {
                    writeBytes(byteArrayOf(9))
                }
            viewModel.sendVoiceNote(voiceFile)
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.errorMessage
                    ?.en()
                    ?.contains("Voice note transcription failed") == true,
            )
            assertFalse(voiceFile.exists())
            assertFalse(viewModel.uiState.value.isTranscribingVoiceNote)
        }

    @Test
    fun sendVoiceNote_emptyTranscriptShowsNoSpeechAndDoesNotSubmit() =
        runTest {
            val api = mockk<io.wutz.apollo.data.remote.HermesApiService>(relaxed = true)
            every { ApiClient.hermesApi } returns api
            every { ApiClient.transcriptionService(any()) } returns api
            coEvery { api.transcribeAudio(any()) } returns
                Response.success(AudioTranscriptionResponse(ok = true, transcript = "   "))
            val (viewModel, _) = createViewModelWithSession()

            val voiceFile =
                java.io.File(attachmentCacheDir, "voice_silent.m4a").apply {
                    writeBytes(byteArrayOf(0))
                }
            viewModel.sendVoiceNote(voiceFile)
            advanceUntilIdle()

            assertEquals(
                UiText.res(io.wutz.apollo.R.string.chat_msg_voice_note_empty),
                viewModel.uiState.value.errorMessage,
            )
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun sendVoiceNote_whileDisconnectedDropsClipLocallyWithoutUploading() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            val voiceFile =
                java.io.File(attachmentCacheDir, "voice_offline.m4a").apply {
                    writeBytes(byteArrayOf(1))
                }
            viewModel.sendVoiceNote(voiceFile)
            advanceUntilIdle()

            assertFalse(voiceFile.exists())
            assertEquals(
                UiText.res(io.wutz.apollo.R.string.chat_msg_voice_note_offline),
                viewModel.uiState.value.errorMessage,
            )
        }

    @Test
    fun sendVoiceNote_whenSessionChangesMidTranscription_keepsTheTranscriptInTheComposer() =
        runTest {
            stubActiveProfile()
            val api = mockk<io.wutz.apollo.data.remote.HermesApiService>(relaxed = true)
            every { ApiClient.hermesApi } returns api
            every { ApiClient.transcriptionService(any()) } returns api
            val transcription = CompletableDeferred<Response<AudioTranscriptionResponse>>()
            coEvery { api.transcribeAudio(any()) } coAnswers { transcription.await() }
            val (viewModel, _) = createViewModelWithSession()

            val voiceFile =
                java.io.File(attachmentCacheDir, "voice_session_race.m4a").apply {
                    writeBytes(byteArrayOf(7))
                }
            viewModel.sendVoiceNote(voiceFile)
            advanceUntilIdle()

            // The user moves to a new session while STT is still running.
            viewModel.createNewSession()
            advanceUntilIdle()

            transcription.complete(
                Response.success(AudioTranscriptionResponse(ok = true, transcript = "late transcript")),
            )
            advanceUntilIdle()

            // The recording belonged to the old session: the transcript must
            // survive in the composer instead of landing in a session it was
            // never recorded for, or vanishing (review, PR #1250).
            assertEquals("late transcript", viewModel.uiState.value.composerTextToRestore)
            assertEquals(
                UiText.res(io.wutz.apollo.R.string.chat_msg_voice_note_unsent),
                viewModel.uiState.value.errorMessage,
            )
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
            assertFalse(viewModel.uiState.value.isTranscribingVoiceNote)
            assertFalse(voiceFile.exists())
        }

    @Test
    fun sendVoiceNote_whenTheSendIsRejected_keepsTheTranscriptInTheComposer() =
        runTest {
            stubActiveProfile()
            val api = mockk<io.wutz.apollo.data.remote.HermesApiService>(relaxed = true)
            every { ApiClient.hermesApi } returns api
            every { ApiClient.transcriptionService(any()) } returns api
            coEvery { api.transcribeAudio(any()) } returns
                Response.success(AudioTranscriptionResponse(ok = true, transcript = "keep me"))
            val (viewModel, _) = createViewModelWithSession()

            val voiceFile =
                java.io.File(attachmentCacheDir, "voice_send_race.m4a").apply {
                    writeBytes(byteArrayOf(8))
                }
            viewModel.sendVoiceNote(voiceFile)
            // The connection drops while the transcript is being produced.
            mockConnectionStatus.value = ConnectionStatus.DISCONNECTED
            advanceUntilIdle()

            assertEquals("keep me", viewModel.uiState.value.composerTextToRestore)
            assertEquals(
                UiText.res(io.wutz.apollo.R.string.chat_msg_voice_note_unsent),
                viewModel.uiState.value.errorMessage,
            )
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun sendVoiceNote_whileATranscriptionIsInFlight_dropsTheSecondClip() =
        runTest {
            stubActiveProfile()
            val api = mockk<io.wutz.apollo.data.remote.HermesApiService>(relaxed = true)
            every { ApiClient.hermesApi } returns api
            every { ApiClient.transcriptionService(any()) } returns api
            val transcription = CompletableDeferred<Response<AudioTranscriptionResponse>>()
            coEvery { api.transcribeAudio(any()) } coAnswers { transcription.await() }
            val (viewModel, _) = createViewModelWithSession()

            val submittedTexts = mutableListOf<String>()
            every { HermesWsClient.sendMessage(any(), any(), any(), any()) } answers {
                reqCount++
                val id = "req-msg-$reqCount"
                submittedTexts += arg<String>(1)
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            val firstFile =
                java.io.File(attachmentCacheDir, "voice_first.m4a").apply { writeBytes(byteArrayOf(1)) }
            val secondFile =
                java.io.File(attachmentCacheDir, "voice_second.m4a").apply { writeBytes(byteArrayOf(2)) }
            viewModel.sendVoiceNote(firstFile)
            advanceUntilIdle()

            viewModel.sendVoiceNote(secondFile)
            advanceUntilIdle()

            // Single-flight: the second clip is dropped without a second upload.
            assertFalse(secondFile.exists())
            coVerify(exactly = 1) { api.transcribeAudio(any()) }

            transcription.complete(
                Response.success(AudioTranscriptionResponse(ok = true, transcript = "only once")),
            )
            advanceUntilIdle()

            assertEquals(listOf("only once"), submittedTexts)
            assertFalse(viewModel.uiState.value.isTranscribingVoiceNote)
        }

    @Test
    fun sendVoiceNote_whenDataScopeChangesDuringTranscription_doesNotSendIntoNewScope() =
        runTest {
            stubActiveProfile()
            val api = mockk<io.wutz.apollo.data.remote.HermesApiService>(relaxed = true)
            every { ApiClient.hermesApi } returns api
            every { ApiClient.transcriptionService(any()) } returns api
            val transcription = CompletableDeferred<Response<AudioTranscriptionResponse>>()
            coEvery { api.transcribeAudio(any()) } coAnswers { transcription.await() }

            every { AuthManager.currentDataScope() } returns
                DataScope(
                    connectionProfileId = "alpha",
                    baseUrl = "http://alpha.test/",
                    activeProfileId = AuthManager.DEFAULT_PROFILE_ID,
                )

            val viewModel = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            // A pending SESSION_CREATE is all this test needs; skipping the
            // gateway preload keeps the shared ModelCatalogStore out of it.
            viewModel.createNewSession()
            advanceUntilIdle()

            // SESSION_CREATE is still pending, so the storage session ID is
            // null — exactly the window where the session-ID snapshot alone
            // cannot detect a scope move.
            assertNull(viewModel.uiState.value.currentSessionId)

            val voiceFile =
                java.io.File(attachmentCacheDir, "voice_scope_switch.m4a").apply {
                    writeBytes(byteArrayOf(1, 2, 3))
                }
            viewModel.sendVoiceNote(voiceFile)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isTranscribingVoiceNote)

            // The user switches to another server/profile while STT runs; the
            // replacement session create is pending as well, so both session
            // IDs can stay null/null and only the data scope moves.
            every { AuthManager.currentDataScope() } returns
                DataScope(
                    connectionProfileId = "beta",
                    baseUrl = "http://beta.test/",
                    activeProfileId = AuthManager.DEFAULT_PROFILE_ID,
                )
            advanceUntilIdle()

            transcription.complete(
                Response.success(
                    AudioTranscriptionResponse(ok = true, transcript = "do not send this to the new server"),
                ),
            )
            advanceUntilIdle()

            // The transcript must not become the initial prompt of the new
            // scope; it stays in the composer instead (review, PR #1250).
            verify(exactly = 0) {
                HermesWsClient.sendMessage(any(), "do not send this to the new server", any(), any())
            }
            assertEquals(
                "do not send this to the new server",
                viewModel.uiState.value.composerTextToRestore,
            )
            assertEquals(
                UiText.res(io.wutz.apollo.R.string.chat_msg_voice_note_unsent),
                viewModel.uiState.value.errorMessage,
            )
            assertFalse(viewModel.uiState.value.isTranscribingVoiceNote)
            assertFalse(voiceFile.exists())
        }

    @Test
    fun sendVoiceNote_whenServerProfileChangesDuringTranscription_doesNotSendIntoNewScope() =
        runTest {
            stubActiveProfile()
            val api = mockk<io.wutz.apollo.data.remote.HermesApiService>(relaxed = true)
            every { ApiClient.hermesApi } returns api
            every { ApiClient.transcriptionService(any()) } returns api
            val transcription = CompletableDeferred<Response<AudioTranscriptionResponse>>()
            coEvery { api.transcribeAudio(any()) } coAnswers { transcription.await() }

            every { AuthManager.currentDataScope() } returns
                DataScope(
                    connectionProfileId = "alpha",
                    baseUrl = "http://alpha.test/",
                    activeProfileId = "default",
                )

            val viewModel = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            // A pending SESSION_CREATE is all this test needs; skipping the
            // gateway preload keeps the shared ModelCatalogStore out of it.
            viewModel.createNewSession()
            advanceUntilIdle()

            val voiceFile =
                java.io.File(attachmentCacheDir, "voice_profile_switch.m4a").apply {
                    writeBytes(byteArrayOf(4, 5, 6))
                }
            viewModel.sendVoiceNote(voiceFile)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isTranscribingVoiceNote)

            // Same server, different server-side Hermes profile: the session
            // IDs cannot see this change either, so the scope must.
            every { AuthManager.currentDataScope() } returns
                DataScope(
                    connectionProfileId = "alpha",
                    baseUrl = "http://alpha.test/",
                    activeProfileId = "work",
                )
            advanceUntilIdle()

            transcription.complete(
                Response.success(
                    AudioTranscriptionResponse(ok = true, transcript = "profile-scoped transcript"),
                ),
            )
            advanceUntilIdle()

            verify(exactly = 0) {
                HermesWsClient.sendMessage(any(), "profile-scoped transcript", any(), any())
            }
            assertEquals(
                "profile-scoped transcript",
                viewModel.uiState.value.composerTextToRestore,
            )
            assertEquals(
                UiText.res(io.wutz.apollo.R.string.chat_msg_voice_note_unsent),
                viewModel.uiState.value.errorMessage,
            )
            assertFalse(viewModel.uiState.value.isTranscribingVoiceNote)
            assertFalse(voiceFile.exists())
        }

    @Test
    fun normalAcceptanceKeepsPromptVisibleBeforeAndDuringItsTurn() =
        runTest {
            val store = ChatSendStore()
            val (vm, session) = createViewModelWithSession(sendStore = store)
            every { HermesWsClient.sendMessage(session, "live-prompt", any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("live-submit")
                "live-submit"
            }
            assertTrue(vm.sendMessage("live-prompt"))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult("live-submit", mapOf("status" to "streaming", "user_row_id" to 10)))
            advanceUntilIdle()
            vm.syncCurrentSession()
            advanceUntilIdle()
            assertEquals(PendingSendState.ACCEPTED, store.all().single().state)
            assertEquals(
                "live-prompt",
                vm.transcriptState.value.messages
                    .single { it.role == MessageRole.USER }
                    .content,
            )
            mockEventsFlow.emit(WsEvent.MessageStart(session))
            mockEventsFlow.emit(WsEvent.MessageToken("Working", session))
            advanceUntilIdle()
            assertEquals(PendingSendState.ACCEPTED, store.all().single().state)
            assertTrue(
                vm.transcriptState.value.messages
                    .any { it.content == "live-prompt" },
            )
        }

    @Test
    fun gatewayQueuedAcceptanceStaysVisibleThroughPreviousTurnCompletion() =
        runTest {
            val store = ChatSendStore()
            val (vm, session) = createViewModelWithSession(sendStore = store)
            mockEventsFlow.emit(WsEvent.MessageStart(session))
            advanceUntilIdle()
            assertTrue(vm.sendMessage("guide-next", BusySendMode.GUIDE))
            advanceUntilIdle()
            val requestId = sentRequestMethods.last { it.first == WsMethods.SESSION_STEER }.second
            mockEventsFlow.emit(WsEvent.RpcResult(requestId, mapOf("status" to "queued", "user_row_id" to 10)))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.MessageComplete("Previous reply", session))
            advanceUntilIdle()
            vm.syncCurrentSession()
            advanceUntilIdle()

            assertEquals(PendingSendState.ACCEPTED, store.all().single().state)
            assertTrue(
                vm.transcriptState.value.messages
                    .any { it.content == "guide-next" },
            )
        }

    @Test
    fun changedReceiptIdentityDoesNotBlockNewPromptsOrFalselyConfirmDelivery() =
        runTest {
            val store = ChatSendStore()
            val (vm, session) = createViewModelWithSession(sendStore = store)
            every { HermesWsClient.sendMessage(session, "probe-first", any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("probe-submit")
                "probe-submit"
            }
            assertTrue(vm.sendMessage("probe-first"))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult("probe-submit", mapOf("status" to "streaming", "user_row_id" to 10)))
            advanceUntilIdle()
            coEvery { ApiClient.hermesApi.getSessionMessages(session, any(), any(), any(), any()) } returns
                Response.success(
                    SessionMessagesResponse(
                        messages =
                            listOf(
                                SessionMessage(id = 20, role = "user", content = JsonPrimitive("probe-first")),
                                SessionMessage(id = 21, role = "assistant", content = JsonPrimitive("probe-reply")),
                            ),
                        // Absolute-position backend: this case pins the legacy identity rules. With
                        // order=latest the complete newest page proves row 10 re-issued (#1520) and folds it.
                        pagination = PaginationInfo(),
                    ),
                )
            mockEventsFlow.emit(WsEvent.MessageStart(session))
            mockEventsFlow.emit(WsEvent.MessageComplete("probe-reply", session))
            advanceUntilIdle()
            vm.syncCurrentSession()
            advanceUntilIdle()
            assertEquals(
                2,
                vm.uiState.value.messages
                    .count { it.role == MessageRole.USER && it.content == "probe-first" },
            )
            assertEquals(
                "probe-first",
                vm.uiState.value.messages
                    .last()
                    .content,
            )
            // #1427: a mismatched history row cannot retire the gateway-confirmed receipt.
            assertEquals(PendingSendState.ACCEPTED, store.all().single().state)
            assertTrue(vm.uiState.value.isSessionReady)
            assertTrue(vm.sendMessage("probe-second"))
            advanceUntilIdle()
            assertTrue(
                vm.uiState.value.messages
                    .any { it.content == "probe-second" },
            )
            assertEquals(PendingSendState.SENDING, store.all().single { it.text == "probe-second" }.state)
            assertNull(vm.uiState.value.errorMessage)
            verify(exactly = 1) { HermesWsClient.sendMessage(session, "probe-second", any(), any()) }
            verify(exactly = 1) { HermesWsClient.sendMessage(session, "probe-first", any(), any()) }
            assertEquals(PendingSendState.ACCEPTED, store.all().single { it.text == "probe-first" }.state)
            // The accepted row 10 remains visible separately from the unrelated history row 20.
            assertEquals(
                2,
                vm.transcriptState.value.messages
                    .count { it.content == "probe-first" },
            )
        }

    @Test
    fun deliveredSendFoldsIntoCompactionReissuedRowOnSync() =
        runTest {
            val store = ChatSendStore()
            val (vm, _) = createViewModelWithSession(sendStore = store)
            val session = "session-456"

            fun page(vararg rows: SessionMessage) =
                Response.success(
                    SessionMessagesResponse(
                        rows.toList(),
                        pagination = PaginationInfo(limit = 150, offset = 0, order = "latest", returned = rows.size),
                    ),
                )
            val ts0 = JsonPrimitive(1_700_000_000.0)
            val older = SessionMessage(id = 5, role = "user", content = JsonPrimitive("first"), timestamp = ts0)
            val olderReply = SessionMessage(id = 6, role = "assistant", content = JsonPrimitive("ok"), timestamp = ts0)
            coEvery { ApiClient.hermesApi.getSessionMessages(session, any(), any(), any(), any()) } returns
                page(older, olderReply)
            vm.switchSession(session)
            advanceUntilIdle()
            val resumeId = sentRequestMethods.last { it.first == WsMethods.SESSION_RESUME }.second
            mockEventsFlow.emit(WsEvent.RpcResult(resumeId, mapOf("session_id" to "runtime-456", "resumed" to session)))
            advanceUntilIdle()
            every { HermesWsClient.sendMessage(any(), "hello", any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("hello-submit")
                "hello-submit"
            }
            assertTrue(vm.sendMessage("hello"))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult("hello-submit", mapOf("status" to "streaming", "user_row_id" to 10)))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.MessageStart(session))
            mockEventsFlow.emit(WsEvent.MessageComplete("reply", session))
            advanceUntilIdle()
            val sentAt =
                vm.uiState.value.messages
                    .single { it.content == "hello" }
                    .timestamp
            val ts = JsonPrimitive(sentAt / 1000.0)
            coEvery { ApiClient.hermesApi.getSessionMessages(session, any(), any(), any(), any()) } returns
                page(
                    older,
                    olderReply,
                    SessionMessage(id = 10, role = "user", content = JsonPrimitive("hello"), timestamp = ts),
                    SessionMessage(id = 11, role = "assistant", content = JsonPrimitive("reply"), timestamp = ts),
                )
            vm.syncCurrentSession()
            advanceUntilIdle()
            assertEquals(
                1,
                vm.uiState.value.messages
                    .count { it.content == "hello" },
            )
            coEvery { ApiClient.hermesApi.getSessionMessages(session, any(), any(), any(), any()) } returns
                page(
                    older,
                    olderReply,
                    SessionMessage(id = 20, role = "user", content = JsonPrimitive("hello"), timestamp = ts),
                    SessionMessage(id = 21, role = "assistant", content = JsonPrimitive("reply"), timestamp = ts),
                )
            vm.syncCurrentSession()
            advanceUntilIdle()
            // #1520: compaction re-issued the delivered send's row id (10 -> 20) without notice.
            val hello =
                vm.uiState.value.messages
                    .filter { it.content == "hello" }
            assertEquals(1, hello.size)
            assertEquals(20L, hello.single().serverRowId)
        }

    @Test
    fun timeoutReleasesQueuedPromptWithoutReplayingUncertainSend() =
        runTest {
            val store = ChatSendStore()
            val (vm, session) = createViewModelWithSession(sendStore = store)
            every { HermesWsClient.sendMessage(session, "uncertain-first", any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("uncertain-submit")
                "uncertain-submit"
            }
            assertTrue(vm.sendMessage("uncertain-first"))
            advanceUntilIdle()
            assertTrue(vm.sendMessage("queued-second"))
            advanceUntilIdle()
            assertEquals(PendingSendState.QUEUED, store.all().single { it.text == "queued-second" }.state)
            vm.expireOutgoingRequest("uncertain-submit")
            advanceUntilIdle()

            assertEquals(PendingSendState.UNKNOWN, store.all().single { it.text == "uncertain-first" }.state)
            verify(exactly = 1) { HermesWsClient.sendMessage(session, "queued-second", any(), true) }
            verify(exactly = 1) { HermesWsClient.sendMessage(session, "uncertain-first", any(), any()) }
        }

    @Test
    fun discardUnknownPreservesConversationAndReceiptIncludingAcknowledgedState() =
        runTest {
            val store = ChatSendStore()
            val (vm, session) = createViewModelWithSession(sendStore = store)
            assertTrue(vm.sendMessage("dismiss-first"))
            advanceUntilIdle()
            val receipt = store.all().single()
            vm.discardPendingSend(receipt.id)
            assertEquals(PendingSendState.SENDING, store.all().single().state)
            store.update(receipt.id) { it.copy(state = PendingSendState.UNKNOWN) }
            for (acknowledged in listOf(false, true)) {
                store.update(receipt.id) { it.copy(userOrderingReleased = acknowledged) }
                val before = vm.uiState.value.messages
                vm.discardPendingSend(receipt.id)
                advanceUntilIdle()
                assertEquals(PendingSendState.UNKNOWN, store.all().single().state)
                assertEquals(acknowledged, store.all().single().userOrderingReleased)
                assertEquals(before, vm.uiState.value.messages)
                assertTrue(fakeRepo.dao.getMessagesForSession(session).any { it.id == receipt.id })
            }
            verify(exactly = 1) { HermesWsClient.sendMessage(session, "dismiss-first", any(), any()) }
        }

    @Test
    fun acknowledgeUnknownPersistsOnlyMetadataWithoutSendingOrDrainingQueuedRows() =
        runTest {
            val store = ChatSendStore()
            val (vm, session) = createViewModelWithSession(sendStore = store)
            assertTrue(vm.sendMessage("ack-first"))
            advanceUntilIdle()
            val receipt = store.all().single().copy(state = PendingSendState.UNKNOWN)
            store.put(receipt)
            val queued =
                receipt.copy(
                    id = "queued-after-ack",
                    text = "queued-after-ack",
                    state = PendingSendState.QUEUED,
                )
            store.put(queued)
            val before = vm.uiState.value.messages

            vm.acknowledgePendingSend(receipt.id)
            advanceUntilIdle()

            assertEquals(receipt.copy(userOrderingReleased = true), store.all().first { it.id == receipt.id })
            assertEquals(queued, store.all().first { it.id == queued.id })
            assertEquals(before, vm.uiState.value.messages)
            assertNull(vm.uiState.value.errorMessage)
            verify(exactly = 1) { HermesWsClient.sendMessage(session, "ack-first", any(), any()) }
            verify(exactly = 0) { HermesWsClient.sendMessage(session, "queued-after-ack", any(), any()) }
        }

    @Test
    fun acknowledgeUnknownCommitFailureRetainsReceiptAndShowsErrorWithoutSending() =
        runTest {
            var saved: String? = null
            var staged: String? = null
            var writable = true
            val prefs = mockk<SharedPreferences>()
            val editor = mockk<SharedPreferences.Editor>()
            every { prefs.getString("rows", null) } answers { saved }
            every { prefs.edit() } returns editor
            every { editor.putString("rows", any()) } answers {
                staged = secondArg()
                editor
            }
            every { editor.commit() } answers {
                if (writable) saved = staged
                writable
            }
            val store = ChatSendStore(prefs)
            val (vm, session) = createViewModelWithSession(sendStore = store)
            assertTrue(vm.sendMessage("ack-failure"))
            advanceUntilIdle()
            val receipt = store.all().single().copy(state = PendingSendState.UNKNOWN)
            store.put(receipt)
            val persistedBefore = saved
            writable = false

            assertNull(runCatching { vm.acknowledgePendingSend(receipt.id) }.exceptionOrNull())
            advanceUntilIdle()
            assertEquals(listOf(receipt), store.all())
            assertEquals(persistedBefore, saved)
            assertEquals(
                UiText.res(io.wutz.apollo.R.string.chat_pending_acknowledge_failed),
                vm.uiState.value.errorMessage,
            )
            verify(exactly = 1) { HermesWsClient.sendMessage(session, "ack-failure", any(), any()) }
        }

    @Test
    fun removeAcknowledgedPendingSendPreservesConversationAndAttachmentSnapshots() =
        runTest {
            val store = ChatSendStore()
            val (vm, session) = createViewModelWithSession(sendStore = store)
            assertTrue(vm.sendMessage("preserved"))
            advanceUntilIdle()
            val original = store.all().single()
            val file = java.io.File(attachmentFilesDir, "preserved.txt").apply { writeText("keep") }
            val attachment = Attachment(file.toURI().toString(), "preserved.txt", "text/plain", 4)
            val receipt =
                original.copy(
                    state = PendingSendState.UNKNOWN,
                    userOrderingReleased = true,
                    attachments = listOf(attachment),
                )
            store.put(receipt)
            val history = ChatMessage(receipt.id, MessageRole.USER, receipt.text, attachments = listOf(attachment))
            fakeRepo.persistMessage(history, session)
            val stateField = ChatViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }

            @Suppress("UNCHECKED_CAST")
            val state = stateField.get(vm) as MutableStateFlow<ChatUiState>
            state.value =
                state.value.copy(
                    messages = state.value.messages.map { if (it.id == history.id) history else it },
                    pendingAttachments = listOf(attachment),
                )
            advanceUntilIdle()
            val messagesBefore = vm.uiState.value.messages
            assertEquals(1, messagesBefore.count { it.id == history.id })
            assertEquals(listOf(attachment), messagesBefore.single { it.id == history.id }.attachments)

            vm.removeAcknowledgedPendingSend(receipt)
            advanceUntilIdle()

            assertTrue(store.all().isEmpty())
            assertEquals(messagesBefore, vm.uiState.value.messages)
            assertTrue(fakeRepo.dao.getMessagesForSession(session).any { it.id == history.id })
            assertEquals(listOf(attachment), vm.uiState.value.pendingAttachments)
            assertTrue(file.exists())
            verify(exactly = 1) { HermesWsClient.sendMessage(session, "preserved", any(), any()) }
        }

    @Test
    fun removeAcknowledgedPendingSendFailureKeepsReceiptAndReportsError() =
        runTest {
            var saved: String? = null
            var writable = true
            var staged: String? = null
            val prefs = mockk<SharedPreferences>()
            val editor = mockk<SharedPreferences.Editor>()
            every { prefs.getString("rows", null) } answers { saved }
            every { prefs.edit() } returns editor
            every { editor.putString("rows", any()) } answers {
                staged = secondArg()
                editor
            }
            every { editor.commit() } answers {
                if (writable) saved = staged
                writable
            }
            val store = ChatSendStore(prefs)
            val (vm, session) = createViewModelWithSession(sendStore = store)
            assertTrue(vm.sendMessage("disk-failure"))
            advanceUntilIdle()
            val receipt = store.all().single().copy(state = PendingSendState.UNKNOWN, userOrderingReleased = true)
            store.put(receipt)
            val persistedBefore = saved
            writable = false

            assertNull(runCatching { vm.removeAcknowledgedPendingSend(receipt) }.exceptionOrNull())
            advanceUntilIdle()
            assertEquals(listOf(receipt), store.all())
            assertEquals(persistedBefore, saved)
            assertEquals(
                UiText.res(io.wutz.apollo.R.string.chat_pending_remove_failed),
                vm.uiState.value.errorMessage,
            )
            verify(exactly = 1) { HermesWsClient.sendMessage(session, "disk-failure", any(), any()) }
        }

    @Test
    fun removeAcknowledgedPendingSendRejectsOtherScopeSessionAndStates() =
        runTest {
            val store = ChatSendStore()
            val (vm, _) = createViewModelWithSession(sendStore = store)
            assertTrue(vm.sendMessage("scope-check"))
            advanceUntilIdle()
            val released = store.all().single().copy(state = PendingSendState.UNKNOWN, userOrderingReleased = true)
            store.put(released)
            every { AuthManager.getBaseUrl() } returns "http://other-account.test/"
            vm.removeAcknowledgedPendingSend(released)
            assertEquals(listOf(released), store.all())
            every { AuthManager.getBaseUrl() } returns "http://test.local/"
            for (row in listOf(
                released.copy(sessionId = "other-session"),
                released.copy(state = PendingSendState.ACCEPTED),
                released.copy(userOrderingReleased = false),
            )) {
                store.put(row)
                vm.removeAcknowledgedPendingSend(row)
                assertEquals(listOf(row), store.all())
            }
            store.put(released.copy(attempts = released.attempts + 1))
            vm.removeAcknowledgedPendingSend(released)
            assertEquals(released.attempts + 1, store.all().single().attempts)
        }
}
