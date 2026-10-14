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

/** ChatViewModel behaviour: Approval, sudo and secret prompts. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelPromptsTest : ChatViewModelTestBase() {
    // ── Approval flow ────────────────────────────────────────────────────────

    @Test
    fun testApprovalRequest_addsSystemMessage() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.ApprovalRequest(
                    command = "rm -rf /data",
                    description = "The agent wants to execute: rm -rf /data",
                    patternKeys = listOf("shell:rm"),
                    sessionId = null,
                ),
            )
            advanceUntilIdle()

            val msg =
                viewModel.uiState.value.messages
                    .first { it.content.contains("Approval Required") }
            assertNotNull(msg.approvalInfo)
            assertEquals("rm -rf /data", msg.approvalInfo?.command)
        }

    @Test
    fun testRespondToApproval_sendsRpc() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(
                WsEvent.ApprovalRequest(
                    command = "rm",
                    description = "Dangerous command",
                    patternKeys = null,
                    sessionId = null,
                ),
            )
            advanceUntilIdle()

            viewModel.respondToApproval("approve")
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.APPROVAL_RESPOND, any(), any()) }
        }

    @Test
    fun testRespondToApproval_clearsButtons() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(
                WsEvent.ApprovalRequest(
                    command = "rm",
                    description = "Dangerous",
                    patternKeys = null,
                    sessionId = null,
                ),
            )
            advanceUntilIdle()

            val approvalMsg =
                viewModel.uiState.value.messages
                    .firstOrNull { it.approvalInfo != null }
            assertNotNull(approvalMsg)

            viewModel.respondToApproval("approve")
            advanceUntilIdle()

            val msgAfter =
                viewModel.uiState.value.messages
                    .firstOrNull { it.id == approvalMsg!!.id }
            assertNotNull(msgAfter)
            assertNull(msgAfter!!.approvalInfo)
        }

    @Test
    fun testApprovalRequest_storesRequestIdAndChoices() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.ApprovalRequest(
                    command = "rm -rf /tmp/x",
                    description = "dangerous command",
                    patternKeys = listOf("shell:rm"),
                    sessionId = null,
                    requestId = "req-1",
                    choices = listOf("once", "session", "always", "deny"),
                    allowPermanent = true,
                    smartDenied = false,
                ),
            )
            advanceUntilIdle()

            val msg =
                viewModel.uiState.value.messages
                    .first { it.content.contains("Approval Required") }
            assertEquals("req-1", msg.approvalInfo?.requestId)
            assertEquals(
                listOf("once", "session", "always", "deny"),
                msg.approvalInfo?.choices,
            )
            assertEquals(true, msg.approvalInfo?.allowPermanent)
        }

    @Test
    fun testRespondToApproval_mapsApproveToOnceAndSendsRequestId() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.ApprovalRequest(
                    command = "rm",
                    description = "Dangerous",
                    patternKeys = null,
                    sessionId = null,
                    requestId = "req-42",
                    choices = listOf("once", "deny"),
                ),
            )
            advanceUntilIdle()

            viewModel.respondToApproval("approve")
            advanceUntilIdle()

            verify {
                HermesWsClient.send(
                    WsMethods.APPROVAL_RESPOND,
                    withArg { params ->
                        assertEquals(JsonPrimitive(sessionId), params["session_id"])
                        assertEquals(JsonPrimitive("once"), params["choice"])
                        assertEquals(JsonPrimitive("req-42"), params["request_id"])
                    },
                    any(),
                )
            }
        }

    @Test
    fun testRespondToApproval_sessionChoice() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.ApprovalRequest(
                    command = "rm",
                    description = "Dangerous",
                    patternKeys = null,
                    sessionId = null,
                    requestId = "req-7",
                    choices = listOf("once", "session", "always", "deny"),
                ),
            )
            advanceUntilIdle()

            viewModel.respondToApproval("session")
            advanceUntilIdle()

            verify {
                HermesWsClient.send(
                    WsMethods.APPROVAL_RESPOND,
                    withArg { params ->
                        assertEquals(JsonPrimitive("session"), params["choice"])
                        assertEquals(JsonPrimitive("req-7"), params["request_id"])
                    },
                    any(),
                )
            }
        }

    @Test
    fun testRespondToApproval_omitsRequestIdWhenAbsent() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.ApprovalRequest(
                    command = "ls",
                    description = "Safe",
                    patternKeys = null,
                    sessionId = null,
                ),
            )
            advanceUntilIdle()

            viewModel.respondToApproval("deny")
            advanceUntilIdle()

            verify {
                HermesWsClient.send(
                    WsMethods.APPROVAL_RESPOND,
                    withArg { params ->
                        assertEquals(JsonPrimitive("deny"), params["choice"])
                        assertFalse(params.containsKey("request_id"))
                    },
                    any(),
                )
            }
        }

    @Test
    fun testRespondToApproval_triggersPendingReplay() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.ApprovalRequest(
                    command = "rm",
                    description = "Dangerous",
                    patternKeys = null,
                    sessionId = null,
                    requestId = "req-9",
                    choices = listOf("once", "deny"),
                ),
            )
            advanceUntilIdle()

            viewModel.respondToApproval("once")
            advanceUntilIdle()

            verify {
                HermesWsClient.send(
                    WsMethods.APPROVAL_PENDING,
                    withArg { params ->
                        assertEquals(JsonPrimitive(sessionId), params["session_id"])
                    },
                    any(),
                )
            }
        }

    @Test
    fun testApprovalFlow_prefersRuntimeSessionIdOverStorageId() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    "req-id-3",
                    mapOf(
                        "session_id" to "runtime-sid-999",
                        "stored_session_id" to "storage-uuid-111",
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals("storage-uuid-111", viewModel.uiState.value.currentSessionId)

            mockEventsFlow.emit(
                WsEvent.ApprovalRequest(
                    command = "rm -rf /data",
                    description = "Dangerous operation",
                    patternKeys = null,
                    sessionId = null,
                    requestId = "req-runtime-test",
                    choices = listOf("once", "deny"),
                ),
            )
            advanceUntilIdle()

            verify {
                HermesWsClient.send(
                    WsMethods.APPROVAL_RECEIVED,
                    withArg { params ->
                        assertEquals(JsonPrimitive("runtime-sid-999"), params["session_id"])
                        assertEquals(JsonPrimitive("req-runtime-test"), params["request_id"])
                    },
                    any(),
                )
            }

            viewModel.respondToApproval("once")
            advanceUntilIdle()

            verify {
                HermesWsClient.send(
                    WsMethods.APPROVAL_RESPOND,
                    withArg { params ->
                        assertEquals(JsonPrimitive("runtime-sid-999"), params["session_id"])
                        assertEquals(JsonPrimitive("once"), params["choice"])
                        assertEquals(JsonPrimitive("req-runtime-test"), params["request_id"])
                    },
                    any(),
                )
                HermesWsClient.send(
                    WsMethods.APPROVAL_PENDING,
                    withArg { params ->
                        assertEquals(JsonPrimitive("runtime-sid-999"), params["session_id"])
                    },
                    any(),
                )
            }
        }

    // ── Sudo / secret prompt flow (issue #524) ───────────────────────────

    @Test
    fun testSudoRequest_setsPromptState() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.SudoRequest(requestId = "sudo-1", sessionId = null),
            )
            advanceUntilIdle()

            val prompt = viewModel.uiState.value.sudoPrompt
            assertNotNull(prompt)
            assertEquals("sudo-1", prompt?.requestId)
        }

    @Test
    fun testRespondToSudo_sendsRpc() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.SudoRequest(requestId = "sudo-1", sessionId = null),
            )
            advanceUntilIdle()

            viewModel.respondToSudo("hunter2")
            advanceUntilIdle()

            verify {
                HermesWsClient.send(
                    WsMethods.SUDO_RESPOND,
                    withArg { params ->
                        assertEquals(sessionId, params["session_id"])
                        assertEquals("hunter2", params["password"])
                        assertEquals("sudo-1", params["request_id"])
                    },
                    any(),
                )
            }
        }

    @Test
    fun testRespondToSudo_clearsPrompt() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.SudoRequest(requestId = "sudo-1", sessionId = null),
            )
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.sudoPrompt)

            viewModel.respondToSudo("hunter2")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.sudoPrompt)
        }

    @Test
    fun testSecretRequest_setsPromptState() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.SecretRequest(requestId = "secret-1", sessionId = null),
            )
            advanceUntilIdle()

            val prompt = viewModel.uiState.value.secretPrompt
            assertNotNull(prompt)
            assertEquals("secret-1", prompt?.requestId)
        }

    @Test
    fun testRespondToSecret_sendsRpc() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.SecretRequest(requestId = "secret-1", sessionId = null),
            )
            advanceUntilIdle()

            viewModel.respondToSecret("super-secret-token")
            advanceUntilIdle()

            verify {
                HermesWsClient.send(
                    WsMethods.SECRET_RESPOND,
                    withArg { params ->
                        assertEquals(sessionId, params["session_id"])
                        assertEquals("super-secret-token", params["value"])
                        assertEquals("secret-1", params["request_id"])
                    },
                    any(),
                )
            }
        }

    @Test
    fun testRespondToSecret_clearsPrompt() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.SecretRequest(requestId = "secret-1", sessionId = null),
            )
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.secretPrompt)

            viewModel.respondToSecret("super-secret-token")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.secretPrompt)
        }

    @Test
    fun testSudoExpire_clearsMatchingPrompt() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.SudoRequest(requestId = "sudo-1", sessionId = null))
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.sudoPrompt)

            mockEventsFlow.emit(WsEvent.SudoExpire(requestId = "sudo-1", sessionId = null))
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.sudoPrompt)
        }

    @Test
    fun testSudoExpire_ignoresNonMatchingRequestId() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.SudoRequest(requestId = "sudo-2", sessionId = null))
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.sudoPrompt)

            mockEventsFlow.emit(WsEvent.SudoExpire(requestId = "sudo-stale", sessionId = null))
            advanceUntilIdle()

            assertNotNull(viewModel.uiState.value.sudoPrompt)
        }

    @Test
    fun testSecretExpire_clearsMatchingPrompt() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.SecretRequest(requestId = "secret-1", sessionId = null))
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.secretPrompt)

            mockEventsFlow.emit(WsEvent.SecretExpire(requestId = "secret-1", sessionId = null))
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.secretPrompt)
        }

    @Test
    fun testSecretRequest_setsEnvVarAndPrompt() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.SecretRequest(
                    requestId = "secret-1",
                    sessionId = null,
                    envVar = "GITHUB_TOKEN",
                    prompt = "Enter your GitHub token",
                ),
            )
            advanceUntilIdle()

            val prompt = viewModel.uiState.value.secretPrompt
            assertNotNull(prompt)
            assertEquals("GITHUB_TOKEN", prompt?.envVar)
            assertEquals("Enter your GitHub token", prompt?.prompt)
        }

    @Test
    fun testDismissSudo_sendsEmptyPassword() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.SudoRequest(requestId = "sudo-1", sessionId = null))
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.sudoPrompt)

            viewModel.dismissSudo()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.sudoPrompt)
            verify {
                HermesWsClient.send(
                    WsMethods.SUDO_RESPOND,
                    withArg { params ->
                        assertEquals(sessionId, params["session_id"])
                        assertEquals("", params["password"])
                        assertEquals("sudo-1", params["request_id"])
                    },
                    any(),
                )
            }
        }

    @Test
    fun testDismissSecret_sendsEmptyValue() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.SecretRequest(requestId = "secret-1", sessionId = null))
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.secretPrompt)

            viewModel.dismissSecret()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.secretPrompt)
            verify {
                HermesWsClient.send(
                    WsMethods.SECRET_RESPOND,
                    withArg { params ->
                        assertEquals(sessionId, params["session_id"])
                        assertEquals("", params["value"])
                        assertEquals("secret-1", params["request_id"])
                    },
                    any(),
                )
            }
        }
}
