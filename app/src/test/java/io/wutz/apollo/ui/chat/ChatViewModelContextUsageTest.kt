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

/** ChatViewModel behaviour: Context meter: model swaps, RPC model matching and refetches. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelContextUsageTest : ChatViewModelTestBase() {
    /**
     * Regression for the spurious "Error: JsonRpcError(code=4001, message=session
     * not found, data=null)" snackbar when opening a session from history: the
     * chat sync effect fires fetchContextUsage() immediately on session switch,
     * BEFORE session.resume has registered the session on the gateway. The
     * context/usage RPCs resolve against the gateway's LIVE runtime registry
     * (_sess_nowait) and would 4001 on the storage id — so they must be skipped
     * until the resume result confirms the runtime id (which is then used).
     */
    @Test
    fun testSwitchSession_contextRpcSkippedUntilResumeConfirmsRuntimeId() =
        runTest {
            stubSession456Rests(success = true)

            // Awaited live-registry RPCs (sendRpcAndAwait → HermesWsClient.request).
            // Completed deferred so the awaits resolve; capture the params. Installed
            // BEFORE createViewModelWithSession so the SESSION_CREATE-era
            // fetchContextUsage() call is stubbed too (otherwise its unstubbed
            // invocation is still recorded and pollutes the verify(exactly = 0)).
            val paramsSlot = slot<Map<String, Any>>()
            every {
                HermesWsClient.request(any(), capture(paramsSlot), any())
            } returns CompletableDeferred<Any?>(emptyMap<String, Any?>())

            val (viewModel, _) = createViewModelWithSession()

            // Capture the session.resume request id so its result can be delivered.
            var resumeRequestId: String? = null
            every { HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any()) } answers {
                reqCount++
                val id = "resume-$reqCount"
                arg<((String) -> Unit)?>(2)?.invoke(id)
                resumeRequestId = id
                id
            }

            // Switch to session-456: runtimeSessionId resets to null until the
            // resume result lands — the storage id is not (yet) live on the
            // gateway. ChatScreen's sync effect fires fetchContextUsage()
            // immediately on session switch — simulate it while the resume
            // result is still in flight. The live-registry RPCs must NOT fire
            // with the stale storage id (the gateway would 4001).
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            viewModel.fetchContextUsage()
            advanceUntilIdle()

            verify(exactly = 0) {
                HermesWsClient.request(
                    WsMethods.SESSION_CONTEXT_BREAKDOWN,
                    match { it["session_id"] == JsonPrimitive("session-456") },
                    any(),
                )
            }
            verify(exactly = 0) {
                HermesWsClient.request(
                    WsMethods.SESSION_USAGE,
                    match { it["session_id"] == JsonPrimitive("session-456") },
                    any(),
                )
            }

            // Resume result lands → runtime id confirmed → the RPCs fire with it.
            val resumeId = checkNotNull(resumeRequestId)
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    resumeId,
                    mapOf(
                        "session_id" to "runtime-456",
                        "resumed" to "session-456",
                        "info" to emptyMap<String, Any?>(),
                    ),
                ),
            )
            advanceUntilIdle()

            verify {
                HermesWsClient.request(
                    WsMethods.SESSION_CONTEXT_BREAKDOWN,
                    match { it["session_id"] == JsonPrimitive("runtime-456") },
                    any(),
                )
            }
            verify {
                HermesWsClient.request(
                    WsMethods.SESSION_USAGE,
                    match { it["session_id"] == JsonPrimitive("runtime-456") },
                    any(),
                )
            }
            assertEquals(JsonPrimitive("runtime-456"), paramsSlot.captured["session_id"])
        }

    @Test
    fun testSessionInfoModelSwap_blanksStaleMeterAndRefetches() =
        runTest {
            stubEmptySessionRests("session-a")
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            // Establish the OLD model's label through the real event path.
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    mapOf("model" to "deepseek-v4-flash", "provider" to "opencode-go"),
                ),
            )
            advanceUntilIdle()
            assertEquals("opencode-go/deepseek-v4-flash", viewModel.uiState.value.currentSessionModel)

            // Fetch the meter so it carries the OLD model's window (1M).
            var contextMax = 1_000_000L
            var breakdownCalls = 0
            every { HermesWsClient.request(WsMethods.SESSION_CONTEXT_BREAKDOWN, any(), any()) } answers {
                breakdownCalls++
                CompletableDeferred<Any?>(
                    mapOf("context_max" to contextMax, "context_used" to 42025L),
                )
            }
            viewModel.fetchContextUsage()
            advanceUntilIdle()
            assertEquals(1_000_000L, viewModel.uiState.value.fullContextTokens)

            // The swap: the live agent hasn't warmed up yet, so the RPC comes
            // back empty — the meter must NOT fall back to the profile-scoped
            // REST window (which describes the OLD model). It stays hidden.
            contextMax = 0L
            val callsBeforeSwap = breakdownCalls
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    mapOf("model" to "tencent/hy3:free", "provider" to "nous"),
                ),
            )
            advanceUntilIdle()

            assertEquals("nous/tencent/hy3:free", viewModel.uiState.value.currentSessionModel)
            assertTrue("model swap must re-fire the meter fetch", breakdownCalls > callsBeforeSwap)
            assertNull(
                "cold RPC after a swap must keep the meter hidden, not show the old window",
                viewModel.uiState.value.fullContextTokens,
            )

            // The runtime warms up — the next fetch lands the NEW model's window.
            contextMax = 262_144L
            viewModel.fetchContextUsage()
            advanceUntilIdle()
            assertEquals(262_144L, viewModel.uiState.value.fullContextTokens)
        }

    @Test
    fun testPickerModelSwitch_blanksMeterAndResolvesNewContextWindow_issue1103() =
        runTest {
            stubEmptySessionRests("session-a")
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            // Profile default model is 900k
            coEvery { ApiClient.hermesApi.getModelInfo() } returns
                retrofit2.Response.success(
                    io.wutz.apollo.data.model.ModelInfoResponse(
                        model = "gpt-5.6-luna-900k",
                        provider = "openai-codex",
                        effective_context_length = 900_000L,
                    ),
                )

            // Initial session.info for default model
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    mapOf("model" to "gpt-5.6-luna-900k", "provider" to "openai-codex"),
                ),
            )
            advanceUntilIdle()
            assertEquals("openai-codex/gpt-5.6-luna-900k", viewModel.uiState.value.currentSessionModel)

            var breakdownResult: Any =
                mapOf("context_max" to 900_000L, "context_used" to 10_000L)
            every { HermesWsClient.request(WsMethods.SESSION_CONTEXT_BREAKDOWN, any(), any()) } answers {
                CompletableDeferred<Any?>(breakdownResult)
            }
            viewModel.fetchContextUsage()
            advanceUntilIdle()
            assertEquals(900_000L, viewModel.uiState.value.fullContextTokens)

            // User picks Solar 200k via model picker (sendSlashModel)
            viewModel.sendSlashModel("nous", "upstage/solar-pro4:free")
            advanceUntilIdle()
            assertEquals("nous/upstage/solar-pro4:free", viewModel.uiState.value.currentSessionModel)
            assertNull("Picker model switch must immediately blank meter", viewModel.uiState.value.fullContextTokens)

            // Backend replies with session.info confirming Solar
            // Breakdown RPC returns JsonObject (from wire) with 524288 context_max
            breakdownResult =
                kotlinx.serialization.json.buildJsonObject {
                    put("context_max", kotlinx.serialization.json.JsonPrimitive(524288))
                    put("context_used", kotlinx.serialization.json.JsonPrimitive(20000))
                }
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    mapOf("model" to "upstage/solar-pro4:free", "provider" to "nous"),
                ),
            )
            advanceUntilIdle()

            // Verified: meter resolves to Solar's 524288 window, NOT profile's 900k
            assertEquals(524288L, viewModel.uiState.value.fullContextTokens)

            // Periodic background sync with skipRestFallback=false must NOT overwrite with profile's 900k
            viewModel.fetchContextUsage(skipRestFallback = false)
            advanceUntilIdle()
            assertEquals(524288L, viewModel.uiState.value.fullContextTokens)
        }

    @Test
    fun testIsMatchingRpcModel_namespacedAndProviderMatching() =
        runTest {
            stubEmptySessionRests("session-a")
            val (viewModel, _) = createViewModelWithSession()

            // Current session model has provider prefix, rpc model retains internal namespace
            assertTrue(
                viewModel.isMatchingRpcModel(
                    "nous/upstage/solar-pro4:free",
                    "upstage/solar-pro4:free",
                ),
            )
            // Exact match
            assertTrue(
                viewModel.isMatchingRpcModel(
                    "upstage/solar-pro4:free",
                    "upstage/solar-pro4:free",
                ),
            )
            // Provider prefix with non-namespaced model
            assertTrue(
                viewModel.isMatchingRpcModel(
                    "openai/gpt-4o",
                    "gpt-4o",
                ),
            )
            // Never blindly strip model's own namespace: rpcModel missing internal namespace must not match
            assertFalse(
                viewModel.isMatchingRpcModel(
                    "nous/upstage/solar-pro4:free",
                    "solar-pro4:free",
                ),
            )
            // Mismatch
            assertFalse(
                viewModel.isMatchingRpcModel(
                    "nous/upstage/solar-pro4:free",
                    "claude-3",
                ),
            )
        }

    @Test
    fun testSessionInfoSameModel_keepsMeterUntouched() =
        runTest {
            stubEmptySessionRests("session-a")
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            // Establish label + meter through the real paths.
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    mapOf("model" to "tencent/hy3:free", "provider" to "nous"),
                ),
            )
            advanceUntilIdle()
            var breakdownCalls = 0
            every { HermesWsClient.request(WsMethods.SESSION_CONTEXT_BREAKDOWN, any(), any()) } answers {
                breakdownCalls++
                CompletableDeferred<Any?>(
                    mapOf("context_max" to 262_144L),
                )
            }
            viewModel.fetchContextUsage()
            advanceUntilIdle()
            assertEquals(262_144L, viewModel.uiState.value.fullContextTokens)
            val callsBefore = breakdownCalls

            // Identical model again — no swap, no blank, no refetch.
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    mapOf("model" to "tencent/hy3:free", "provider" to "nous"),
                ),
            )
            advanceUntilIdle()

            assertEquals(callsBefore, breakdownCalls)
            assertEquals(262_144L, viewModel.uiState.value.fullContextTokens)
            assertEquals("nous/tencent/hy3:free", viewModel.uiState.value.currentSessionModel)
        }
}
