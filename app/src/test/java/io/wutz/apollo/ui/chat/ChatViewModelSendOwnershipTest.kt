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

/** ChatViewModel behaviour: Send ownership: a send never lands in another session, profile or generation. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelSendOwnershipTest : ChatViewModelTestBase() {
    // ── Send ownership linearization regressions ────────────────────────────

    @Test
    fun manualAttachmentRetryContextChangeAfterValidationPreservesOriginalReceiptAndDoesNotDispatch() =
        runTest {
            var baseUrl = "http://test.local/"
            var changeContextOnNextStoreEdit = false
            every { AuthManager.getBaseUrl() } answers { baseUrl }
            val prefs = mockk<SharedPreferences>()
            val editor = mockk<SharedPreferences.Editor>()
            every { prefs.getString("rows", null) } returns null
            every { prefs.edit() } answers {
                if (changeContextOnNextStoreEdit) {
                    changeContextOnNextStoreEdit = false
                    baseUrl = "http://other-profile.local/"
                }
                editor
            }
            every { editor.putString("rows", any()) } returns editor
            every { editor.commit() } returns true
            val store = ChatSendStore(prefs)
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val scope =
                listOf(
                    baseUrl,
                    AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                    AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                ).joinToString("\u001f")
            val sourceUri = mockk<Uri>()
            val snapshotUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            lateinit var snapshotFile: java.io.File
            mockkStatic(Uri::class)
            every { Uri.parse("content://retry/post-validation") } returns sourceUri
            every { Uri.fromFile(any()) } answers {
                snapshotFile = firstArg()
                snapshotUri
            }
            every { snapshotUri.toString() } returns "file://private-post-validation-copy"
            every { Uri.parse("file://private-post-validation-copy") } returns snapshotUri
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(sourceUri) } answers { byteArrayOf(1, 2, 3).inputStream() }
            every { resolver.openInputStream(snapshotUri) } answers { snapshotFile.inputStream() }
            mockkConstructor(android.util.Base64OutputStream::class)
            every { anyConstructed<android.util.Base64OutputStream>().write(any<ByteArray>(), any(), any()) } returns
                Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit
            every { HermesWsClient.request(WsMethods.FILE_ATTACH, any(), any()) } returns
                CompletableDeferred<Any?>(mapOf("ref_text" to "@file:post-validation.txt"))
            store.put(
                PendingSend(
                    id = "retry-post-validation",
                    scope = scope,
                    sessionId = sessionId,
                    text = "Keep post-validation receipt",
                    attachments =
                        listOf(
                            Attachment(
                                "content://retry/post-validation",
                                "post-validation.txt",
                                "text/plain",
                                3,
                            ),
                        ),
                    mode = BusySendMode.QUEUE,
                    state = PendingSendState.REJECTED,
                ),
            )
            viewModel.refreshSettings()
            advanceUntilIdle()

            // processPendingSendReservation has already validated the old owner when it
            // opens the store transaction that would replace the recovery receipt.
            changeContextOnNextStoreEdit = true
            viewModel.sendQueuedNow("retry-post-validation")
            advanceUntilIdle()

            val preserved = store.all().single { it.id == "retry-post-validation" }
            assertEquals("http://other-profile.local/", baseUrl)
            assertEquals(scope, preserved.scope)
            assertEquals("content://retry/post-validation", preserved.attachments.single().uri)
            assertEquals(PendingSendState.REJECTED, preserved.state)
            assertTrue(preserved.requiresAttachmentRecovery)
            verify(exactly = 0) { HermesWsClient.request(WsMethods.FILE_ATTACH, any(), any()) }
            verify(exactly = 0) {
                HermesWsClient.sendMessage(
                    any(),
                    "@file:post-validation.txt\n\nKeep post-validation receipt",
                    any(),
                    any(),
                )
            }
        }

    @Test
    fun queuedClaimContextChangeBeforeFinalValidationRollsBackToQueuedWithoutDispatch() =
        runTest {
            var baseUrl = "http://test.local/"
            var armClaimCommit = false
            var changeContextOnNextScopeRead = false
            every { AuthManager.getBaseUrl() } answers {
                if (changeContextOnNextScopeRead) {
                    changeContextOnNextScopeRead = false
                    baseUrl = "http://other-profile.local/"
                }
                baseUrl
            }
            val prefs = mockk<SharedPreferences>()
            val editor = mockk<SharedPreferences.Editor>()
            every { prefs.getString("rows", null) } returns null
            every { prefs.edit() } returns editor
            every { editor.putString("rows", any()) } returns editor
            every { editor.commit() } answers {
                if (armClaimCommit) {
                    armClaimCommit = false
                    changeContextOnNextScopeRead = true
                }
                true
            }
            val store = ChatSendStore(prefs)
            val scope =
                listOf(
                    baseUrl,
                    AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                    AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                ).joinToString("\u001f")
            store.put(
                PendingSend(
                    id = "queue-post-claim-context-change",
                    scope = scope,
                    sessionId = "session-123",
                    text = "Claimed then stale",
                    mode = BusySendMode.QUEUE,
                    state = PendingSendState.QUEUED,
                ),
            )

            // The claim's durable commit arms the first scope read in publishPendingSends,
            // after update() has installed SENDING but before drainPendingQueue's final check.
            armClaimCommit = true
            val (_, sessionId) = createViewModelWithSession(sendStore = store)
            mockEventsFlow.emit(WsEvent.MessageComplete("Done", sessionId))
            advanceUntilIdle()

            val recoverable = store.all().single { it.id == "queue-post-claim-context-change" }
            assertEquals("http://other-profile.local/", baseUrl)
            assertEquals(PendingSendState.QUEUED, recoverable.state)
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Claimed then stale", any(), any()) }
        }

    @Test
    fun manualAttachmentRetryContextChangeBeforeAttachmentRpcPreservesOriginalReceipt() =
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
            val snapshotUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            lateinit var snapshotFile: java.io.File
            mockkStatic(Uri::class)
            every { Uri.parse("content://retry/pre-attachment-rpc") } returns sourceUri
            every { Uri.fromFile(any()) } answers {
                snapshotFile = firstArg()
                snapshotUri
            }
            every { snapshotUri.toString() } returns "file://private-pre-attachment-rpc"
            every { Uri.parse("file://private-pre-attachment-rpc") } returns snapshotUri
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(sourceUri) } answers { byteArrayOf(1, 2, 3).inputStream() }
            every { resolver.openInputStream(snapshotUri) } answers {
                baseUrl = "http://other-profile.local/"
                snapshotFile.inputStream()
            }
            mockkConstructor(android.util.Base64OutputStream::class)
            every { anyConstructed<android.util.Base64OutputStream>().write(any<ByteArray>(), any(), any()) } returns
                Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit
            every { HermesWsClient.request(WsMethods.FILE_ATTACH, any(), any()) } returns
                CompletableDeferred<Any?>(mapOf("ref_text" to "@file:pre-attachment-rpc.txt"))
            store.put(
                PendingSend(
                    id = "retry-pre-attachment-rpc",
                    scope = scope,
                    sessionId = sessionId,
                    text = "Keep pre-attachment receipt",
                    attachments =
                        listOf(
                            Attachment(
                                "content://retry/pre-attachment-rpc",
                                "pre-attachment-rpc.txt",
                                "text/plain",
                                3,
                            ),
                        ),
                    mode = BusySendMode.QUEUE,
                    state = PendingSendState.REJECTED,
                ),
            )
            viewModel.refreshSettings()
            advanceUntilIdle()

            viewModel.sendQueuedNow("retry-pre-attachment-rpc")
            advanceUntilIdle()

            val preserved = store.all().single { it.id == "retry-pre-attachment-rpc" }
            assertEquals(scope, preserved.scope)
            assertEquals("content://retry/pre-attachment-rpc", preserved.attachments.single().uri)
            assertEquals(PendingSendState.REJECTED, preserved.state)
            assertTrue(preserved.requiresAttachmentRecovery)
            verify(exactly = 0) { HermesWsClient.request(WsMethods.FILE_ATTACH, any(), any()) }
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun acceptedReceiptCanBeRetriedOnlyByExplicitSendAgain() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            every { HermesWsClient.sendMessage(sessionId, "Possibly buffered", any(), false) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("buffered-submit")
                "buffered-submit"
            }
            viewModel.sendMessage("Possibly buffered")
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult("buffered-submit", mapOf("status" to "streaming")))
            advanceUntilIdle()
            val receipt =
                viewModel.uiState.value.pendingSends
                    .single()
            assertEquals(PendingSendState.ACCEPTED, receipt.state)
            verify(exactly = 0) { HermesWsClient.sendMessage(sessionId, "Possibly buffered", any(), true) }

            viewModel.sendQueuedNow(receipt.id)
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Possibly buffered", any(), true) }
        }

    @Test
    fun disconnectQuarantinesAcceptedReceiptWithoutAutomaticResend() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            every { HermesWsClient.sendMessage(sessionId, "Accepted before disconnect", any(), false) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("accepted-before-disconnect")
                "accepted-before-disconnect"
            }
            viewModel.sendMessage("Accepted before disconnect")
            advanceUntilIdle()
            mockEventsFlow.emit(
                WsEvent.RpcResult("accepted-before-disconnect", mapOf("status" to "streaming")),
            )
            advanceUntilIdle()
            assertEquals(
                PendingSendState.ACCEPTED,
                viewModel.uiState.value.pendingSends
                    .single()
                    .state,
            )

            mockConnectionStatus.value = ConnectionStatus.DISCONNECTED
            advanceUntilIdle()

            assertEquals(
                PendingSendState.ACCEPTED,
                viewModel.uiState.value.pendingSends
                    .single()
                    .state,
            )
            assertTrue(
                viewModel.uiState.value.pendingSends
                    .single()
                    .requiresExactReconciliation,
            )
            verify(exactly = 1) {
                HermesWsClient.sendMessage(sessionId, "Accepted before disconnect", any(), false)
            }
            verify(exactly = 0) {
                HermesWsClient.sendMessage(sessionId, "Accepted before disconnect", any(), true)
            }
        }

    @Test
    fun sendNowPromotesTheSelectedQueuedMessageWhileIdle() =
        runTest {
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val scope =
                listOf(
                    AuthManager.getBaseUrl(),
                    AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                    AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                ).joinToString("\u001f")
            store.put(PendingSend("first", scope, sessionId, "First queued", mode = BusySendMode.QUEUE))
            store.put(PendingSend("second", scope, sessionId, "Second queued", mode = BusySendMode.QUEUE))
            viewModel.refreshSettings()
            advanceUntilIdle()

            viewModel.sendQueuedNow("second")
            advanceUntilIdle()

            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Second queued", any(), true) }
            verify(exactly = 0) { HermesWsClient.sendMessage(sessionId, "First queued", any(), any()) }
            assertEquals(PendingSendState.QUEUED, store.all().single { it.id == "first" }.state)
        }

    @Test
    fun pendingQueueIsScopedByConnectionProfileAndStoredSession() =
        runTest {
            var connectionProfile = "first"
            every { AuthManager.getSelectedProfileId() } answers { connectionProfile }
            stubSession456Rests(success = true)
            val sendStore = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = sendStore)
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.sendMessage("Only here", BusySendMode.QUEUE)
            advanceUntilIdle()
            assertEquals(1, viewModel.uiState.value.pendingSends.size)

            connectionProfile = "second"
            viewModel.refreshSettings()
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.pendingSends
                    .isEmpty(),
            )
            connectionProfile = "first"
            viewModel.refreshSettings()
            advanceUntilIdle()
            assertEquals(1, viewModel.uiState.value.pendingSends.size)

            viewModel.switchSession("session-456")
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.pendingSends
                    .isEmpty(),
            )
        }

    @Test
    fun restoredQueueDrainsAfterResumeHydrationWhenAckArrivesFirst() =
        runTest {
            stubSession456Rests(success = true)
            val response =
                CompletableDeferred<retrofit2.Response<SessionMessagesResponse>>()
            coEvery {
                ApiClient.hermesApi.getSessionMessages("session-456", any(), any(), any(), any())
            } coAnswers { response.await() }
            val store = ChatSendStore()
            val (viewModel, _) = createViewModelWithSession(sendStore = store)
            val captured = captureSends()
            val scope =
                listOf(
                    AuthManager.getBaseUrl(),
                    AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                    AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                ).joinToString("\u001f")
            store.put(PendingSend("restored", scope, "session-456", "After relaunch", mode = BusySendMode.QUEUE))

            viewModel.switchSession("session-456")
            runCurrent()
            val resumeId = captured.last { it.first == WsMethods.SESSION_RESUME }.second
            mockEventsFlow.emit(WsEvent.RpcResult(resumeId, mapOf("session_id" to "runtime-456", "running" to false)))
            runCurrent()
            assertFalse(viewModel.uiState.value.isSessionReady)
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "After relaunch", any(), any()) }

            response.complete(retrofit2.Response.success(SessionMessagesResponse(messages = emptyList())))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isSessionReady)
            verify(exactly = 1) { HermesWsClient.sendMessage("runtime-456", "After relaunch", any(), true) }
        }

    @Test
    fun sessionSwitchCancelsObsoleteDrainAndDispatchesRestoredQueue() =
        runTest {
            stubSession456Rests(success = true)
            val store = ChatSendStore()
            val (viewModel, _) = createViewModelWithSession(sendStore = store)
            val scope =
                listOf(
                    AuthManager.getBaseUrl(),
                    AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                    AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                ).joinToString("\u001f")
            // Model an active session-A staging job without real threads or blocking I/O.
            // Reset must release ownership before session B becomes ready to drain.
            val obsoleteDrain = Job()
            ChatViewModel::class.java
                .getDeclaredField("queueDrainJob")
                .apply { isAccessible = true }
                .set(viewModel, obsoleteDrain)
            every { HermesWsClient.sendMessage("runtime-456", "Session B queued", any(), true) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("session-b-submit")
                "session-b-submit"
            }
            store.put(
                PendingSend(
                    "session-b-restored",
                    scope,
                    "session-456",
                    "Session B queued",
                    mode = BusySendMode.QUEUE,
                ),
            )
            viewModel.switchSession("session-456")
            runCurrent()
            val resumeId = sentRequestMethods.last { it.first == WsMethods.SESSION_RESUME }.second
            mockEventsFlow.emit(
                WsEvent.RpcResult(resumeId, mapOf("session_id" to "runtime-456", "running" to false)),
            )
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isSessionReady)
            assertTrue("Session switch must cancel the obsolete drain", obsoleteDrain.isCancelled)
            verify(exactly = 1) {
                HermesWsClient.sendMessage("runtime-456", "Session B queued", any(), true)
            }
            verify(exactly = 0) {
                HermesWsClient.sendMessage(any(), "Session A queued", any(), any())
            }
        }

    @Test
    fun timeoutKeepsRecoverableReceiptAndLateAckDoesNotResend() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val requestId = "timeout-request"
            every { HermesWsClient.sendMessage(sessionId, "Maybe", any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke(requestId)
                requestId
            }
            viewModel.sendMessage("Maybe")
            advanceUntilIdle()
            viewModel.expireOutgoingRequest(requestId)
            advanceUntilIdle()
            assertEquals(
                PendingSendState.UNKNOWN,
                viewModel.uiState.value.pendingSends
                    .single()
                    .state,
            )
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.content == "Maybe" },
            )

            mockEventsFlow.emit(WsEvent.RpcResult(requestId, mapOf("status" to "streaming")))
            advanceUntilIdle()
            assertEquals(
                PendingSendState.ACCEPTED,
                viewModel.uiState.value.pendingSends
                    .single()
                    .state,
            )
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Maybe", any(), any()) }
        }

    @Test
    fun restCompletionBeforePromptAckSettlesReceiptAndKeepsCompletionIdentity() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val promptId = "late-prompt-ack"
            every { HermesWsClient.sendMessage(sessionId, "Fast", any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke(promptId)
                promptId
            }
            coEvery {
                ApiClient.hermesApi.getSessionMessages(sessionId, any(), any(), any(), any())
            } returns
                retrofit2.Response.success(
                    SessionMessagesResponse(
                        messages =
                            listOf(
                                SessionMessage(
                                    id = 4,
                                    role = "user",
                                    content = JsonPrimitive("Fast"),
                                    timestamp = JsonPrimitive(System.currentTimeMillis()),
                                ),
                            ),
                        pagination = PaginationInfo(order = "latest"),
                    ),
                )

            viewModel.sendMessage("Fast")
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(WsEvent.MessageComplete("Answer", sessionId, completionId = "completion-fast"))
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.pendingSends
                    .isEmpty(),
            )
            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.completionId == "completion-fast" },
            )

            mockEventsFlow.emit(WsEvent.RpcResult(promptId, mapOf("status" to "streaming")))
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.pendingSends
                    .isEmpty(),
            )
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Fast", any(), any()) }
        }
}
