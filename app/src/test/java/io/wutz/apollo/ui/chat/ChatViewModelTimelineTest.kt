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

/** ChatViewModel behaviour: Session timeline sheet and history windows. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTimelineTest : ChatViewModelTestBase() {
    @Test
    fun timelinePagesUseBackendCursorAndStopOnFinalPage() =
        runTest {
            stubActiveProfile()
            val (viewModel, sessionId) = createViewModelWithSession()
            val api = ApiClient.hermesApi
            coEvery {
                api.getSessionTimeline(sessionId, "default", 500, 0)
            } returns
                retrofit2.Response.success(
                    SessionTimelineResponse(
                        entries = listOf(SessionTimelineEntry(row_id = 10, preview = "first")),
                        pagination =
                            SessionTimelinePagination(
                                limit = 500,
                                after_row_id = 0,
                                returned = 1,
                                total = 2,
                                has_more = true,
                                next_cursor = 123,
                            ),
                    ),
                )
            coEvery {
                api.getSessionTimeline(sessionId, "default", 500, 123)
            } returns
                retrofit2.Response.success(
                    SessionTimelineResponse(
                        entries = listOf(SessionTimelineEntry(row_id = 200, preview = "second")),
                        pagination =
                            SessionTimelinePagination(
                                limit = 500,
                                after_row_id = 123,
                                returned = 1,
                                total = 2,
                                has_more = false,
                                next_cursor = null,
                            ),
                    ),
                )

            viewModel.openTimeline()
            advanceUntilIdle()

            assertEquals(
                listOf(10),
                viewModel.timelineState.value.entries
                    .map { it.row_id },
            )
            assertEquals(123, viewModel.timelineState.value.nextCursor)
            assertTrue(viewModel.timelineState.value.hasMore)

            viewModel.loadMoreTimeline()
            advanceUntilIdle()

            assertEquals(
                listOf(10, 200),
                viewModel.timelineState.value.entries
                    .map { it.row_id },
            )
            assertFalse(viewModel.timelineState.value.hasMore)
            assertNull(viewModel.timelineState.value.nextCursor)
            coVerify(exactly = 1) { api.getSessionTimeline(sessionId, "default", 500, 123) }
        }

    @Test
    fun timelineJumpUsesBoundedHistoryWindowWithoutReplacingLiveTail() =
        runTest {
            stubActiveProfile()
            val (viewModel, sessionId) = createViewModelWithSession()
            val api = ApiClient.hermesApi
            val persistedBeforeJump = fakeRepo.dao.count()
            val liveMessages = viewModel.uiState.value.messages
            coEvery {
                api.getSessionMessagesAround(sessionId, 42, "default", 120)
            } returns
                retrofit2.Response.success(
                    SessionMessagesAroundResponse(
                        messages =
                            listOf(
                                SessionMessage(id = 42, role = "user", content = JsonPrimitive("target")),
                                SessionMessage(id = 43, role = "assistant", content = JsonPrimitive("answer")),
                            ),
                        pagination =
                            SessionMessagesAroundPagination(
                                row_id = 42,
                                limit = 120,
                                returned = 2,
                                order = "oldest",
                                offset = 7,
                                total = 100,
                                has_older = true,
                                has_newer = true,
                            ),
                    ),
                )

            viewModel.jumpToTimelineEntry(42)
            advanceUntilIdle()

            val timeline = viewModel.timelineState.value
            assertTrue(timeline.isHistorical)
            assertEquals(42, timeline.historyAnchorRowId)
            assertTrue(timeline.historyHasOlder)
            assertTrue(timeline.historyHasNewer)
            assertEquals(
                listOf("rest-$sessionId-42", "rest-$sessionId-43"),
                timeline.historyMessages!!.map { it.id },
            )
            assertEquals(listOf("target", "answer"), timeline.historyMessages.map { it.content })
            assertEquals(liveMessages, viewModel.uiState.value.messages)
            // Direct-address history is a replaceable display window, not a
            // contiguous live-cache page. Persisting it would create fake gaps.
            assertEquals(persistedBeforeJump, fakeRepo.dao.count())

            viewModel.returnToLatestMessages()
            assertFalse(viewModel.timelineState.value.isHistorical)
            assertNull(viewModel.timelineState.value.historyMessages)
            assertFalse(viewModel.timelineState.value.historyHasOlder)
            assertFalse(viewModel.timelineState.value.historyHasNewer)
        }

    @Test
    fun missingTimelineRowKeepsCurrentHistoryWindow() =
        runTest {
            stubActiveProfile()
            val (viewModel, sessionId) = createViewModelWithSession()
            val api = ApiClient.hermesApi
            coEvery {
                api.getSessionMessagesAround(sessionId, 42, "default", 120)
            } returns
                retrofit2.Response.success(
                    SessionMessagesAroundResponse(
                        messages = listOf(SessionMessage(id = 42, role = "user", content = JsonPrimitive("target"))),
                        pagination = SessionMessagesAroundPagination(row_id = 42),
                    ),
                )
            coEvery {
                api.getSessionMessagesAround(sessionId, 999, "default", 120)
            } returns
                retrofit2.Response.error(
                    404,
                    """{"detail":"Prompt not found"}""".toResponseBody(),
                )

            viewModel.jumpToTimelineEntry(42)
            advanceUntilIdle()
            val originalWindow = viewModel.timelineState.value.historyMessages

            viewModel.jumpToTimelineEntry(999)
            advanceUntilIdle()

            assertEquals(42, viewModel.timelineState.value.historyAnchorRowId)
            assertEquals(originalWindow, viewModel.timelineState.value.historyMessages)
            assertNotNull(viewModel.timelineState.value.windowErrorMessage)
        }

    @Test
    fun retryTimelineClearsWindowErrorBeforeRefreshing() =
        runTest {
            stubActiveProfile()
            val (viewModel, sessionId) = createViewModelWithSession()
            val api = ApiClient.hermesApi
            coEvery {
                api.getSessionTimeline(sessionId, "default", 500, 0)
            } returns
                retrofit2.Response.success(
                    SessionTimelineResponse(
                        entries = listOf(SessionTimelineEntry(row_id = 42, preview = "target")),
                        pagination = SessionTimelinePagination(has_more = false),
                    ),
                )
            coEvery {
                api.getSessionMessagesAround(sessionId, 42, "default", 120)
            } returns
                retrofit2.Response.error(
                    404,
                    """{"detail":"Prompt not found"}""".toResponseBody(),
                )

            viewModel.openTimeline()
            advanceUntilIdle()
            viewModel.jumpToTimelineEntry(42)
            advanceUntilIdle()

            assertNotNull(viewModel.timelineState.value.windowErrorMessage)

            viewModel.retryTimeline()

            assertNull(viewModel.timelineState.value.windowErrorMessage)
            advanceUntilIdle()
            assertNull(viewModel.timelineState.value.windowErrorMessage)
            assertEquals(
                listOf(42),
                viewModel.timelineState.value.entries
                    .map { it.row_id },
            )
            coVerify(exactly = 2) { api.getSessionTimeline(sessionId, "default", 500, 0) }
        }

    @Test
    fun profileSwitchRejectsLateTimelineJumpResponse() =
        runTest {
            val profileFlow = MutableStateFlow<String?>("default")
            every { AuthManager.activeProfileId } returns profileFlow
            val (viewModel, sessionId) = createViewModelWithSession()
            val api = ApiClient.hermesApi
            val response = CompletableDeferred<retrofit2.Response<SessionMessagesAroundResponse>>()
            coEvery {
                api.getSessionMessagesAround(sessionId, 42, "default", 120)
            } coAnswers {
                response.await()
            }

            viewModel.jumpToTimelineEntry(42)
            runCurrent()
            profileFlow.value = "other"
            mockSwitchFlow.emit("other")
            runCurrent()
            response.complete(
                retrofit2.Response.success(
                    SessionMessagesAroundResponse(
                        messages = listOf(SessionMessage(id = 42, role = "user", content = JsonPrimitive("stale"))),
                        pagination = SessionMessagesAroundPagination(row_id = 42),
                    ),
                ),
            )
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.currentSessionId)
            assertFalse(viewModel.timelineState.value.isOpen)
            assertNull(viewModel.timelineState.value.historyMessages)
            assertNull(viewModel.timelineState.value.historyAnchorRowId)
        }

    @Test
    fun sessionSwitchRejectsLateTimelineJumpResponse() =
        runTest {
            stubActiveProfile()
            val (viewModel, sessionId) = createViewModelWithSession()
            val api = ApiClient.hermesApi
            val response = CompletableDeferred<retrofit2.Response<SessionMessagesAroundResponse>>()
            coEvery {
                api.getSessionMessagesAround(sessionId, 42, "default", 120)
            } coAnswers {
                response.await()
            }

            viewModel.jumpToTimelineEntry(42)
            runCurrent()
            viewModel.switchSession("session-other")
            runCurrent()
            response.complete(
                retrofit2.Response.success(
                    SessionMessagesAroundResponse(
                        messages = listOf(SessionMessage(id = 42, role = "user", content = JsonPrimitive("stale"))),
                        pagination = SessionMessagesAroundPagination(row_id = 42),
                    ),
                ),
            )
            runCurrent()

            assertEquals("session-other", viewModel.uiState.value.currentSessionId)
            assertFalse(viewModel.timelineState.value.isOpen)
            assertNull(viewModel.timelineState.value.historyMessages)
            assertNull(viewModel.timelineState.value.historyAnchorRowId)
        }
}
