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

/** ChatViewModel behaviour: History hydration, paging and sync. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelHistoryTest : ChatViewModelTestBase() {
    // ── History pagination guard (issue #674 & #686) ───────────────────────────────

    /**
     * When the initial REST page returns empty messages, hasOlderMessages
     * must be false — otherwise the UI shows a load-more button that fetches
     * the same empty page again, creating an infinite loop.
     */
    @Test
    fun testEmptyInitialRestPage_disablesOlderPagination() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            val mockApi = ApiClient.hermesApi
            coEvery {
                mockApi.getSessions(any(), any(), any())
            } returns
                retrofit2.Response.success(
                    io.wutz.apollo.data.model.SessionListResponse(
                        sessions =
                            listOf(
                                io.wutz.apollo.data.model.SessionInfo(
                                    id = "session-456",
                                    title = "Test",
                                    message_count = 200,
                                ),
                            ),
                        total = 1,
                    ),
                )
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
            } returns
                retrofit2.Response.success(
                    io.wutz.apollo.data.model.SessionMessagesResponse(
                        messages = emptyList(),
                    ),
                )

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            assertFalse(
                "hasOlderMessages must be false when initial page is empty",
                viewModel.uiState.value.hasOlderMessages,
            )
            assertTrue(
                viewModel.uiState.value.messages
                    .isEmpty(),
            )
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun testLoadOlderMessages_honorsServerReturnedOffset() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            val mockApi = ApiClient.hermesApi
            coEvery {
                mockApi.getSessions(any(), any(), any())
            } returns
                retrofit2.Response.success(
                    io.wutz.apollo.data.model.SessionListResponse(
                        sessions =
                            listOf(
                                io.wutz.apollo.data.model.SessionInfo(
                                    id = "session-456",
                                    title = "Test",
                                    message_count = 100,
                                ),
                            ),
                        total = 1,
                    ),
                )
            var messagesCallCount = 0
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
            } coAnswers {
                messagesCallCount += 1
                // Call 1 = the order=latest probe (discarded when the legacy
                // backend echoes no pagination — issue #859).
                if (messagesCallCount == 2) {
                    retrofit2.Response.success(
                        io.wutz.apollo.data.model.SessionMessagesResponse(
                            messages =
                                listOf(
                                    io.wutz.apollo.data.model.SessionMessage(
                                        role = "assistant",
                                        content = JsonPrimitive("Msg 50"),
                                    ),
                                ),
                            offset = 50,
                            total = 100,
                        ),
                    )
                } else {
                    retrofit2.Response.success(
                        io.wutz.apollo.data.model.SessionMessagesResponse(
                            messages =
                                listOf(
                                    io.wutz.apollo.data.model.SessionMessage(
                                        role = "user",
                                        content = JsonPrimitive("Older Msg 20"),
                                    ),
                                ),
                            offset = 20,
                            total = 100,
                        ),
                    )
                }
            }

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.hasOlderMessages)

            // Server returns effective offset 20 (different from requested offset 0)
            viewModel.loadOlderMessages()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.hasOlderMessages)
            val messages = viewModel.uiState.value.messages
            assertEquals(2, messages.size)
            assertEquals("rest-session-456-20", messages[0].id)
            assertEquals("Older Msg 20", messages[0].content)
        }

    @Test
    fun testLoadOlderMessages_oldServerFallback_stopsPaginationWhenOffsetDoesNotDecrease() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            val mockApi = ApiClient.hermesApi
            coEvery {
                mockApi.getSessions(any(), any(), any())
            } returns
                retrofit2.Response.success(
                    io.wutz.apollo.data.model.SessionListResponse(
                        sessions =
                            listOf(
                                io.wutz.apollo.data.model.SessionInfo(
                                    id = "session-456",
                                    title = "Test",
                                    message_count = 100,
                                ),
                            ),
                        total = 1,
                    ),
                )
            var messagesCallCount = 0
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
            } coAnswers {
                messagesCallCount += 1
                if (messagesCallCount == 1) {
                    retrofit2.Response.success(
                        io.wutz.apollo.data.model.SessionMessagesResponse(
                            messages =
                                listOf(
                                    io.wutz.apollo.data.model.SessionMessage(
                                        role = "assistant",
                                        content = JsonPrimitive("Msg 50"),
                                    ),
                                ),
                            offset = 50,
                            total = 100,
                        ),
                    )
                } else {
                    // Older server ignores query params and returns offset = 50
                    // (same as oldOffset, offset did not decrease)
                    retrofit2.Response.success(
                        io.wutz.apollo.data.model.SessionMessagesResponse(
                            messages =
                                listOf(
                                    io.wutz.apollo.data.model.SessionMessage(
                                        role = "assistant",
                                        content = JsonPrimitive("Msg 50"),
                                    ),
                                ),
                            offset = 50,
                            total = 100,
                        ),
                    )
                }
            }

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            viewModel.loadOlderMessages()
            advanceUntilIdle()

            assertFalse(
                "hasOlderMessages must be false when returned offset does not decrease",
                viewModel.uiState.value.hasOlderMessages,
            )
        }

    @Test
    fun testLoadMessages_handlesJsonObjectToolResult() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            val mockApi = ApiClient.hermesApi
            coEvery {
                mockApi.getSessions(any(), any(), any())
            } returns
                retrofit2.Response.success(
                    io.wutz.apollo.data.model.SessionListResponse(
                        sessions =
                            listOf(
                                io.wutz.apollo.data.model.SessionInfo(
                                    id = "session-456",
                                    title = "Test",
                                    message_count = 1,
                                ),
                            ),
                        total = 1,
                    ),
                )
            val jsonObjectContent =
                buildJsonObject {
                    put("status", JsonPrimitive("ok"))
                }
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
            } returns
                retrofit2.Response.success(
                    io.wutz.apollo.data.model.SessionMessagesResponse(
                        messages =
                            listOf(
                                io.wutz.apollo.data.model.SessionMessage(
                                    role = "tool",
                                    content = jsonObjectContent,
                                ),
                            ),
                        offset = 0,
                        total = 1,
                    ),
                )

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            val messages = viewModel.uiState.value.messages
            assertEquals(1, messages.size)
            assertEquals("{\"status\":\"ok\"}", messages[0].content)
        }

    // ── Newest-anchored paging (issue #859) ───────────────────────────────

    @Test
    fun testInitialLoad_latestOrder_pagesFromNewest() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            val mockApi = ApiClient.hermesApi
            val captured = mutableListOf<Triple<Int, Int, String?>>()
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
            } coAnswers {
                captured.add(Triple(arg<Int>(2), arg<Int>(1), arg<String?>(3)))
                retrofit2.Response.success(
                    io.wutz.apollo.data.model.SessionMessagesResponse(
                        messages =
                            (1..150).map { i ->
                                io.wutz.apollo.data.model.SessionMessage(
                                    id = i,
                                    role = "assistant",
                                    content = JsonPrimitive("Msg $i"),
                                )
                            },
                        pagination =
                            io.wutz.apollo.data.model.PaginationInfo(
                                limit = 150,
                                offset = 0,
                                order = "latest",
                                returned = 150,
                            ),
                    ),
                )
            }

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            // One request: the newest page at offset 0 with order=latest —
            // no count-based anchor, no sessions-list fetch.
            assertEquals(1, captured.size)
            assertEquals(Triple(0, 150, "latest"), captured[0])
            assertTrue(viewModel.uiState.value.hasOlderMessages)
            assertEquals(150, viewModel.uiState.value.messages.size)
            // Stable keys come from the server row id, not the from-end position.
            assertEquals(
                "rest-session-456-150",
                viewModel.uiState.value.messages
                    .last()
                    .id,
            )
        }

    @Test
    fun testInitialLoad_latestOrder_shortPage_hasNoOlder() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            val mockApi = ApiClient.hermesApi
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
            } returns
                retrofit2.Response.success(
                    io.wutz.apollo.data.model.SessionMessagesResponse(
                        messages =
                            listOf(
                                io.wutz.apollo.data.model.SessionMessage(
                                    id = 1,
                                    role = "assistant",
                                    content = JsonPrimitive("Only Msg"),
                                ),
                            ),
                        pagination =
                            io.wutz.apollo.data.model.PaginationInfo(
                                limit = 150,
                                offset = 0,
                                order = "latest",
                                returned = 1,
                            ),
                    ),
                )

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.hasOlderMessages)
            assertEquals(1, viewModel.uiState.value.messages.size)
        }

    @Test
    fun testLoadOlderMessages_latestOrder_increasesOffset() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            val mockApi = ApiClient.hermesApi
            val captured = mutableListOf<Triple<Int, Int, String?>>()
            var page = 0
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
            } coAnswers {
                captured.add(Triple(arg<Int>(2), arg<Int>(1), arg<String?>(3)))
                page += 1
                val (offset, returned) =
                    when (page) {
                        1 -> 0 to 150
                        2 -> 150 to 150
                        else -> 300 to 50
                    }
                retrofit2.Response.success(
                    io.wutz.apollo.data.model.SessionMessagesResponse(
                        messages =
                            (1..returned).map { i ->
                                io.wutz.apollo.data.model.SessionMessage(
                                    id = offset + i,
                                    role = "assistant",
                                    content = JsonPrimitive("Page $page Msg $i"),
                                )
                            },
                        pagination =
                            io.wutz.apollo.data.model.PaginationInfo(
                                limit = 150,
                                offset = offset,
                                order = "latest",
                                returned = returned,
                            ),
                    ),
                )
            }

            viewModel.switchSession("session-456")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.hasOlderMessages)

            // Older pages INCREASE the from-end offset, always full-size.
            viewModel.loadOlderMessages()
            advanceUntilIdle()
            assertEquals(Triple(150, 150, "latest"), captured[1])
            assertTrue(viewModel.uiState.value.hasOlderMessages)
            assertEquals(300, viewModel.uiState.value.messages.size)

            // Short final page: the oldest boundary — pagination stops.
            viewModel.loadOlderMessages()
            advanceUntilIdle()
            assertEquals(Triple(300, 150, "latest"), captured[2])
            assertFalse(viewModel.uiState.value.hasOlderMessages)
            assertEquals(350, viewModel.uiState.value.messages.size)
        }

    @Test
    fun sync_newSessionProbesLatestPagingSoColdStartIdsMatch() =
        runTest {
            // Apollo #5: a chat created in the app was never hydrated. Its first sync used the
            // absolute-position protocol and persisted rest-<sid>-0/1, which never matched the
            // stable rest-<sid>-<row id> a cold start loads, so every turn rendered twice.
            val (vm, session) = createViewModelWithSession()
            every { HermesWsClient.sendMessage(session, "hello", any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("hello-submit")
                "hello-submit"
            }
            assertTrue(vm.sendMessage("hello"))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult("hello-submit", mapOf("status" to "ok", "user_row_id" to 7)))
            mockEventsFlow.emit(WsEvent.MessageStart(session))
            mockEventsFlow.emit(WsEvent.MessageComplete("hi", session))
            advanceUntilIdle()

            val orders = mutableListOf<String?>()
            coEvery {
                ApiClient.hermesApi.getSessionMessages(session, any(), any(), any(), any())
            } coAnswers {
                orders += arg<String?>(3)
                retrofit2.Response.success(
                    SessionMessagesResponse(
                        messages =
                            listOf(
                                io.wutz.apollo.data.model.SessionMessage(
                                    id = 7,
                                    role = "user",
                                    content = JsonPrimitive("hello"),
                                ),
                                io.wutz.apollo.data.model.SessionMessage(
                                    id = 8,
                                    role = "assistant",
                                    content = JsonPrimitive("hi"),
                                ),
                            ),
                        pagination =
                            io.wutz.apollo.data.model.PaginationInfo(
                                limit = 150,
                                offset = 0,
                                order = "latest",
                                returned = 2,
                            ),
                    ),
                )
            }

            vm.syncCurrentSession()
            advanceUntilIdle()

            assertEquals("first transcript request $orders", "latest", orders.firstOrNull())
            val restIds =
                vm.uiState.value.messages
                    .mapNotNull { it.canonicalRestId }
                    .toSet()
            assertEquals(setOf("rest-$session-7", "rest-$session-8"), restIds)
            assertEquals(
                1,
                vm.uiState.value.messages
                    .count { it.role == MessageRole.USER && it.content == "hello" },
            )
        }

    @Test
    fun sync_failedLatestProbeFallsBackToAbsolutePositions() =
        runTest {
            // Apollo #5: a failing order=latest probe must not break the sync of a new session.
            val (vm, session) = createViewModelWithSession()
            every { HermesWsClient.sendMessage(session, "hello", any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("hello-submit")
                "hello-submit"
            }
            assertTrue(vm.sendMessage("hello"))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult("hello-submit", mapOf("status" to "ok", "user_row_id" to 7)))
            mockEventsFlow.emit(WsEvent.MessageStart(session))
            mockEventsFlow.emit(WsEvent.MessageComplete("hi", session))
            advanceUntilIdle()

            val orders = mutableListOf<String?>()
            coEvery {
                ApiClient.hermesApi.getSessionMessages(session, any(), any(), any(), any())
            } coAnswers {
                val order = arg<String?>(3)
                orders += order
                if (order == "latest") {
                    retrofit2.Response.error(500, "boom".toResponseBody())
                } else {
                    retrofit2.Response.success(
                        SessionMessagesResponse(
                            messages =
                                listOf(
                                    io.wutz.apollo.data.model.SessionMessage(
                                        id = 7,
                                        role = "user",
                                        content = JsonPrimitive("hello"),
                                    ),
                                    io.wutz.apollo.data.model.SessionMessage(
                                        id = 8,
                                        role = "assistant",
                                        content = JsonPrimitive("hi"),
                                    ),
                                ),
                            pagination =
                                io.wutz.apollo.data.model
                                    .PaginationInfo(),
                        ),
                    )
                }
            }

            vm.syncCurrentSession()
            advanceUntilIdle()

            // The probe (with safeApiCall's 5xx retries) comes first, then the legacy request.
            assertEquals("latest", orders.first())
            assertNull(orders.last())
            assertNull(vm.uiState.value.errorMessage)
            assertTrue(
                vm.uiState.value.messages
                    .any { it.role == MessageRole.ASSISTANT && it.content == "hi" },
            )
        }

    @Test
    fun testSync_latestOrder_grownTranscript_keepsNewestMessage() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            val mockApi = ApiClient.hermesApi
            var page = 0
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
            } coAnswers {
                page += 1
                // Hydration serves rows 1..150; the transcript then grows by 10
                // and the sync refetches the newest page (rows 11..160).
                val (from, to) = if (page == 1) 1 to 150 else 11 to 160
                retrofit2.Response.success(
                    io.wutz.apollo.data.model.SessionMessagesResponse(
                        messages =
                            (from..to).map { i ->
                                io.wutz.apollo.data.model.SessionMessage(
                                    id = i,
                                    role = "assistant",
                                    content = JsonPrimitive("Msg $i"),
                                )
                            },
                        pagination =
                            io.wutz.apollo.data.model.PaginationInfo(
                                limit = 150,
                                offset = 0,
                                order = "latest",
                                returned = 150,
                            ),
                    ),
                )
            }

            viewModel.switchSession("session-456")
            advanceUntilIdle()
            assertEquals(150, viewModel.uiState.value.messages.size)

            viewModel.syncCurrentSession()
            advanceUntilIdle()

            // 160 rows: the 10 new ones appended, the existing 150 kept —
            // no duplicates, no dropped newest copy (stable row-id keys).
            assertEquals(160, viewModel.uiState.value.messages.size)
            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.id == "rest-session-456-160" },
            )
        }

    @Test
    fun paging_staleCacheBeforeRestDoesNotBecomeTheLatestTail() =
        runTest {
            val cachedIds = seedPagingCache(3)
            val response = CompletableDeferred<retrofit2.Response<SessionMessagesResponse>>()
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                ApiClient.hermesApi.getSessionMessages("session-456", any(), any(), any(), any())
            } coAnswers { response.await() }

            viewModel.switchSession("session-456")
            runCurrent()
            assertEquals(
                cachedIds,
                viewModel.uiState.value.messages
                    .map { it.id },
            )
            response.complete(pagingResponse(100..102))
            advanceUntilIdle()

            val expected = cachedIds + (100..102).map { "rest-session-456-$it" }
            assertEquals(
                expected,
                viewModel.uiState.value.messages
                    .map { it.id },
            )
            viewModel.syncCurrentSession()
            advanceUntilIdle()
            assertEquals(
                expected,
                viewModel.uiState.value.messages
                    .map { it.id },
            )
            assertTrue(fakeRepo.dao.idsForSession("session-456").containsAll(cachedIds))
        }

    @Test
    fun paging_staleCacheAfterRestDoesNotBecomeTheLatestTail() =
        runTest {
            val cachedIds = seedPagingCache(3)
            val cacheRead = CompletableDeferred<Unit>()
            fakeRepo.dao.beforeRead = { cacheRead.await() }
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                ApiClient.hermesApi.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(100..102)

            viewModel.switchSession("session-456")
            runCurrent()
            cacheRead.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                cachedIds + (100..102).map { "rest-session-456-$it" },
                viewModel.uiState.value.messages
                    .map { it.id },
            )
        }

    @Test
    fun paging_coldRestartUnanchoredPendingLocalPrecedesCanonicalWindowWithoutConfirmation() =
        runTest {
            val pending =
                ChatMessage(
                    id = "pending-local",
                    role = MessageRole.USER,
                    content = "not delivered yet",
                    messageProvenance = MessageProvenance.LOCAL_PENDING,
                )
            fakeRepo.persistMessage(pending, "session-456")
            val cacheRead = CompletableDeferred<Unit>()
            fakeRepo.dao.beforeRead = { cacheRead.await() }
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                ApiClient.hermesApi.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(100..102)

            viewModel.switchSession("session-456")
            runCurrent()
            cacheRead.complete(Unit)
            advanceUntilIdle()

            // No receipt or durable anchor: this is only a conservative placement,
            // not a claim that the prompt was submitted or delivered.
            assertEquals(
                listOf(pending.id) + (100..102).map { "rest-session-456-$it" },
                viewModel.uiState.value.messages
                    .map { it.id },
            )
            val retained =
                viewModel.uiState.value.messages
                    .single { it.id == pending.id }
            assertEquals(MessageProvenance.LOCAL_PENDING, retained.messageProvenance)
            assertNull(retained.canonicalRestId)
            assertTrue(retained.isRestoredUnconfirmed)
            assertTrue(fakeRepo.dao.idsForSession("session-456").contains(pending.id))
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun paging_coldLocalPendingOrphansBeforeRestKeepIdentityWithoutTailPlacement() =
        runTest { verifyColdLocalPendingOrphans(cacheFirst = true) }

    @Test
    fun paging_coldLocalPendingOrphansAfterRestKeepIdentityWithoutTailPlacement() =
        runTest { verifyColdLocalPendingOrphans(cacheFirst = false) }

    @Test
    fun paging_coldPendingReceiptStatesAndDurableAnchorDoNotUseOrphanPlacement() =
        runTest {
            val session = "session-456"
            val states =
                listOf(
                    PendingSendState.QUEUED,
                    PendingSendState.PARKED,
                    PendingSendState.SENDING,
                    PendingSendState.UNKNOWN,
                    PendingSendState.REJECTED,
                )
            val scope =
                listOf(
                    AuthManager.getBaseUrl(),
                    AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                    AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                ).joinToString("\u001f")
            val store = ChatSendStore()
            states.forEach { state ->
                val id = "protected-${state.name}"
                fakeRepo.persistMessage(
                    ChatMessage(
                        id,
                        MessageRole.USER,
                        "protected ${state.name}",
                        messageProvenance = MessageProvenance.LOCAL_PENDING,
                    ),
                    session,
                )
                store.put(
                    PendingSend(
                        id = id,
                        scope = scope,
                        sessionId = session,
                        text = "protected ${state.name}",
                        mode = BusySendMode.QUEUE,
                        state = state,
                    ),
                )
            }
            val anchored =
                ChatMessage(
                    id = "anchored-pending",
                    role = MessageRole.USER,
                    content = "anchored",
                    messageProvenance = MessageProvenance.LOCAL_PENDING,
                    localAnchorOrder = 99L,
                )
            fakeRepo.persistMessage(anchored, session)
            val (vm, _) = createViewModelWithSession(sendStore = store)
            val api = ApiClient.hermesApi
            coEvery { api.getSessionMessages(session, any(), any(), any(), any()) } returns pagingResponse(100..102)
            vm.switchSession(session)
            advanceUntilIdle()

            (states.map { "protected-${it.name}" } + anchored.id).forEach { id ->
                val retained =
                    vm.uiState.value.messages
                        .single { it.id == id }
                assertFalse("$id is not an unanchored cold orphan", retained.isRestoredUnconfirmed)
                assertEquals(MessageProvenance.LOCAL_PENDING, retained.messageProvenance)
                assertNull(retained.canonicalRestId)
            }
            assertEquals(
                99L,
                vm.uiState.value.messages
                    .single { it.id == anchored.id }
                    .localAnchorOrder,
            )
            states.forEach { state ->
                assertEquals(state, store.all().single { it.id == "protected-${state.name}" }.state)
            }
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun paging_lateCacheCannotReclassifyObservedLivePendingRowAsColdOrphan() =
        runTest {
            val session = "session-456"
            val observed =
                ChatMessage(
                    id = "observed-live-pending",
                    role = MessageRole.USER,
                    content = "observed prompt",
                    messageProvenance = MessageProvenance.LOCAL_PENDING,
                )
            fakeRepo.persistMessage(observed, session)
            val cacheRead = CompletableDeferred<Unit>()
            fakeRepo.dao.beforeRead = { cacheRead.await() }
            val api = ApiClient.hermesApi
            coEvery { api.getSessionMessages(session, any(), any(), any(), any()) } returns pagingResponse(100..102)
            val (vm, _) = createViewModelWithSession()
            vm.switchSession(session)
            runCurrent()
            @Suppress("UNCHECKED_CAST")
            val state =
                ChatViewModel::class.java
                    .getDeclaredField("_uiState")
                    .apply { isAccessible = true }
                    .get(vm) as MutableStateFlow<ChatUiState>
            state.value = state.value.copy(messages = state.value.messages + observed)
            cacheRead.complete(Unit)
            advanceUntilIdle()

            val retained =
                vm.uiState.value.messages
                    .single { it.id == observed.id }
            assertFalse(retained.isRestoredUnconfirmed)
            assertEquals(MessageProvenance.LOCAL_PENDING, retained.messageProvenance)
            assertNull(retained.canonicalRestId)
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun paging_restoredLegacyUserBlockBeforeRestDoesNotFollowNewestReply() =
        runTest { verifyRestoredLegacyUserPlacement(cacheFirst = true) }

    @Test
    fun paging_restoredLegacyUserBlockAfterRestDoesNotFollowNewestReply() =
        runTest { verifyRestoredLegacyUserPlacement(cacheFirst = false) }

    @Test
    fun paging_queuePromptsPersistPendingProvenanceAfterRestart() = runTest { verifyQueuedPromptRestoration("/queue") }

    @Test
    fun paging_queueAliasPromptsPersistPendingProvenanceAfterRestart() = runTest { verifyQueuedPromptRestoration("/q") }

    @Test
    fun paging_coldRestoredAcceptedLegacyUuidBeforeRestWindow() =
        runTest { verifyColdRestoredAcceptedLegacyPlacement(cacheFirst = true) }

    @Test
    fun paging_coldRestoredAcceptedLegacyUuidAfterRestWindow() =
        runTest { verifyColdRestoredAcceptedLegacyPlacement(cacheFirst = false) }

    @Test
    fun paging_coldRestartReceiptBackedUnknownUserRemainsLiveTail() =
        runTest {
            val receiptBacked =
                ChatMessage(
                    id = "receipt-backed-unknown-user",
                    role = MessageRole.USER,
                    content = "possibly accepted before restart",
                    messageProvenance = MessageProvenance.UNKNOWN,
                )
            val rejected =
                ChatMessage(
                    id = "receipt-backed-rejected-user",
                    role = MessageRole.USER,
                    content = "rejected before restart",
                    messageProvenance = MessageProvenance.UNKNOWN,
                )
            fakeRepo.persistMessage(receiptBacked, "session-456")
            fakeRepo.persistMessage(rejected, "session-456")
            val store = ChatSendStore()
            val scope =
                listOf(
                    AuthManager.getBaseUrl(),
                    AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                    AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                ).joinToString("\u001f")
            store.put(
                PendingSend(
                    id = receiptBacked.id,
                    scope = scope,
                    sessionId = "session-456",
                    text = receiptBacked.content,
                    mode = BusySendMode.QUEUE,
                    state = PendingSendState.UNKNOWN,
                ),
            )
            store.put(
                PendingSend(
                    id = rejected.id,
                    scope = scope,
                    sessionId = "session-456",
                    text = rejected.content,
                    mode = BusySendMode.QUEUE,
                    state = PendingSendState.REJECTED,
                ),
            )
            val cacheRead = CompletableDeferred<Unit>()
            fakeRepo.dao.beforeRead = { cacheRead.await() }
            val (viewModel, _) = createViewModelWithSession(sendStore = store)
            coEvery {
                ApiClient.hermesApi.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(100..102)

            viewModel.switchSession("session-456")
            runCurrent()
            cacheRead.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                listOf(rejected.id) + (100..102).map { "rest-session-456-$it" } + receiptBacked.id,
                viewModel.uiState.value.messages
                    .map { it.id },
            )
            val rejectedRetained =
                viewModel.uiState.value.messages
                    .single { it.id == rejected.id }
            assertTrue(rejectedRetained.isRestoredUnconfirmed)
            assertEquals(MessageProvenance.UNKNOWN, rejectedRetained.messageProvenance)
            val retained =
                viewModel.uiState.value.messages
                    .single { it.id == receiptBacked.id }
            assertFalse(retained.isHistoricalCache)
            assertFalse(retained.isRestoredUnconfirmed)
            assertEquals(MessageProvenance.UNKNOWN, retained.messageProvenance)
            assertNull(retained.canonicalRestId)
            assertEquals(PendingSendState.UNKNOWN, store.all().single { it.id == receiptBacked.id }.state)
            assertEquals(PendingSendState.REJECTED, store.all().single { it.id == rejected.id }.state)
            verify(exactly = 0) {
                HermesWsClient.sendMessage(any(), receiptBacked.content, any(), any())
            }
        }

    @Test
    fun paging_coldRestartLegacyUnknownUserRemainsConservativelyUnconfirmed() =
        runTest {
            val ambiguous =
                ChatMessage(
                    id = "legacy-unknown-user",
                    role = MessageRole.USER,
                    content = "possibly unsent before migration",
                )
            fakeRepo.persistMessage(ambiguous, "session-456")
            val cacheRead = CompletableDeferred<Unit>()
            fakeRepo.dao.beforeRead = { cacheRead.await() }
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                ApiClient.hermesApi.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(100..102)
            viewModel.switchSession("session-456")
            runCurrent()
            cacheRead.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                listOf(ambiguous.id) + (100..102).map { "rest-session-456-$it" },
                viewModel.uiState.value.messages
                    .map { it.id },
            )
            val retained =
                viewModel.uiState.value.messages
                    .single { it.id == ambiguous.id }
            assertFalse(retained.isHistoricalCache)
            assertTrue(retained.isRestoredUnconfirmed)
            assertEquals(MessageProvenance.UNKNOWN, retained.messageProvenance)
            assertNull(retained.canonicalRestId)
            assertTrue(fakeRepo.dao.idsForSession("session-456").contains(ambiguous.id))
        }

    @Test
    fun paging_coldRestartPermanentLocalKeepsLocalOrdering() =
        runTest {
            val localCommand =
                ChatMessage(
                    id = "local-command",
                    role = MessageRole.USER,
                    content = "/help",
                )
            fakeRepo.persistMessage(localCommand, "session-456")
            val cacheRead = CompletableDeferred<Unit>()
            fakeRepo.dao.beforeRead = { cacheRead.await() }
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                ApiClient.hermesApi.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(100..102)

            viewModel.switchSession("session-456")
            runCurrent()
            cacheRead.complete(Unit)
            advanceUntilIdle()

            // Persistence records a session-start anchor when no canonical
            // predecessor exists. Later REST hydration must not move it to the tail.
            repeat(3) {
                assertEquals(
                    listOf(localCommand.id) + (100..102).map { "rest-session-456-$it" },
                    viewModel.uiState.value.messages
                        .map { it.id },
                )
                val retained =
                    viewModel.uiState.value.messages
                        .single { it.id == localCommand.id }
                assertEquals(localCommand.content, retained.content)
                assertEquals(MessageRole.USER, retained.role)
                assertEquals(-1L, retained.localAnchorOrder)
                assertNull(retained.localPredecessorId)
                assertNull(retained.canonicalRestId)
                assertEquals(MessageProvenance.UNKNOWN, retained.messageProvenance)
                assertTrue(fakeRepo.dao.idsForSession("session-456").contains(localCommand.id))
                viewModel.refreshCurrentSession()
                advanceUntilIdle()
            }
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun paging_coldRestartLegacyCommandStaysOutsideHydratedTailWithoutInventedPlacement() =
        runTest {
            val commandId = "legacy-model-command"
            // Simulate a migrated row: bypass today's anchor allocation in upsert().
            fakeRepo.dao.addMessageDirect(
                io.wutz.apollo.data.local.ChatMessageEntity(
                    id = commandId,
                    sessionId = "session-456",
                    role = "USER",
                    content = "/model old",
                    timestamp = 0L,
                ),
            )
            val cacheRead = CompletableDeferred<Unit>()
            fakeRepo.dao.beforeRead = { cacheRead.await() }
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                ApiClient.hermesApi.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(100..102)

            viewModel.switchSession("session-456")
            runCurrent()
            cacheRead.complete(Unit)
            advanceUntilIdle()

            repeat(3) {
                assertEquals(
                    listOf(commandId) + (100..102).map { "rest-session-456-$it" },
                    viewModel.uiState.value.messages
                        .map { it.id },
                )
                val retained =
                    viewModel.uiState.value.messages
                        .single { it.id == commandId }
                assertEquals("/model old", retained.content)
                assertEquals(MessageRole.USER, retained.role)
                assertNull(retained.localAnchorOrder)
                assertNull(retained.localPredecessorId)
                assertNull(retained.canonicalRestId)
                assertEquals(MessageProvenance.UNKNOWN, retained.messageProvenance)
                assertTrue(fakeRepo.dao.idsForSession("session-456").contains(commandId))
                viewModel.refreshCurrentSession()
                advanceUntilIdle()
            }
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun paging_coldRestartAnchoredLocalCommandRetainsRecordedPositionAcrossRefresh() =
        runTest {
            val predecessor =
                ChatMessage(
                    id = "rest-session-456-101",
                    role = MessageRole.ASSISTANT,
                    content = "Preceding reply",
                    timestamp = 1L,
                )
            val localCommand =
                ChatMessage(
                    id = "anchored-model-command",
                    role = MessageRole.USER,
                    content = "/model test",
                    timestamp = Long.MAX_VALUE,
                ).withLocalTranscriptAnchor(listOf(predecessor))
            fakeRepo.persistMessage(localCommand, "session-456")
            val cacheRead = CompletableDeferred<Unit>()
            fakeRepo.dao.beforeRead = { cacheRead.await() }
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                ApiClient.hermesApi.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(100..102)

            viewModel.switchSession("session-456")
            runCurrent()
            cacheRead.complete(Unit)
            advanceUntilIdle()

            repeat(3) {
                assertEquals(
                    listOf("rest-session-456-100", predecessor.id, localCommand.id, "rest-session-456-102"),
                    viewModel.uiState.value.messages
                        .map { it.id },
                )
                val retained =
                    viewModel.uiState.value.messages
                        .single { it.id == localCommand.id }
                assertEquals(localCommand.content, retained.content)
                assertEquals(101L, retained.localAnchorOrder)
                assertNull(retained.canonicalRestId)
                assertEquals(MessageProvenance.UNKNOWN, retained.messageProvenance)
                viewModel.refreshCurrentSession()
                advanceUntilIdle()
            }
            coEvery {
                ApiClient.hermesApi.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(100..104)
            viewModel.refreshCurrentSession()
            advanceUntilIdle()
            assertEquals(
                listOf("rest-session-456-100", predecessor.id, localCommand.id) +
                    (102..104).map { "rest-session-456-$it" },
                viewModel.uiState.value.messages
                    .map { it.id },
            )
            assertNull(
                viewModel.uiState.value.messages
                    .single { it.id == localCommand.id }
                    .canonicalRestId,
            )
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun paging_staleCacheDoesNotDemoteOptimisticIdentity() =
        runTest {
            val cachedIds = seedPagingCache(3)
            val cacheRead = CompletableDeferred<Unit>()
            fakeRepo.dao.beforeRead = { cacheRead.await() }
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                ApiClient.hermesApi.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(100..102)

            viewModel.switchSession("session-456")
            runCurrent()
            val attachment = Attachment("content://test/file", "file.txt", "text/plain")
            val optimistic =
                ChatMessage(
                    id = cachedIds[1],
                    role = MessageRole.USER,
                    content = "Cached message 2",
                    attachments = listOf(attachment),
                )

            @Suppress("UNCHECKED_CAST")
            val state =
                ChatViewModel::class.java
                    .getDeclaredField("_uiState")
                    .apply { isAccessible = true }
                    .get(viewModel) as MutableStateFlow<Any>
            state.value = viewModel.uiState.value.copy(messages = viewModel.uiState.value.messages + optimistic)
            cacheRead.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                listOf(cachedIds[0], cachedIds[2]) + (100..102).map { "rest-session-456-$it" } + cachedIds[1],
                viewModel.uiState.value.messages
                    .map { it.id },
            )
            val retained =
                viewModel.uiState.value.messages
                    .last()
            assertFalse(retained.isHistoricalCache)
            assertEquals(listOf(attachment), retained.attachments)
        }

    @Test
    fun paging_syncNewestRestEcho_preservesLiveIdAndCompletionInOneBubble() =
        runTest {
            val api = ApiClient.hermesApi
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(IntRange.EMPTY)
            viewModel.switchSession("session-456")
            advanceUntilIdle()

            // A completed live reply has no confirmed REST identity until its echo arrives.
            val live =
                ChatMessage(
                    id = "live-reply",
                    role = MessageRole.ASSISTANT,
                    content = "Server message 42",
                    timestamp = 42_000L,
                    completionId = "comp",
                    restId = null,
                )

            @Suppress("UNCHECKED_CAST")
            val state =
                ChatViewModel::class.java
                    .getDeclaredField("_uiState")
                    .apply { isAccessible = true }
                    .get(viewModel) as MutableStateFlow<Any>
            state.value = viewModel.uiState.value.copy(messages = listOf(live))
            advanceUntilIdle()
            assertNull(
                viewModel.uiState.value.messages
                    .single()
                    .restId,
            )
            assertEquals(
                "comp",
                viewModel.uiState.value.messages
                    .single()
                    .completionId,
            )

            coEvery {
                api.getSessionMessages("session-456", 150, 0, "latest", any())
            } returns pagingResponse(42..42)

            viewModel.syncCurrentSession()
            advanceUntilIdle()

            coVerify(exactly = 2) {
                api.getSessionMessages("session-456", 150, 0, "latest", any())
            }
            val messages = viewModel.uiState.value.messages
            assertEquals("The newest REST echo must confirm the live reply without adding a bubble", 1, messages.size)
            val message = messages.single()
            assertEquals("Server message 42", message.content)
            assertEquals("live-reply", message.id)
            assertEquals("rest-session-456-42", message.restId)
            assertEquals("comp", message.completionId)
        }

    @Test
    fun paging_largeCache_initialReadIsBoundedAtDao() =
        runTest {
            val expectedIds = seedPagingCache(10_000)
            stubSession456Rests(success = false)
            val (viewModel, _) = createViewModelWithSession()

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            assertEquals(
                expectedIds.takeLast(150),
                viewModel.uiState.value.messages
                    .map { it.id },
            )
            assertEquals("Loading all rows then taking a suffix is not paging", 0, fakeRepo.dao.fullSessionReads)
            assertEquals(expectedIds.toSet(), fakeRepo.dao.idsForSession("session-456"))
            assertTrue(viewModel.uiState.value.hasOlderMessages)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun paging_offlinePagesRetainEveryRowWithEqualTimestamps() =
        runTest {
            val expectedIds = seedPagingCache(350, sameTimestamp = true)
            stubSession456Rests(success = false)
            val (viewModel, _) = createViewModelWithSession()
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            assertEquals(
                expectedIds.takeLast(150),
                viewModel.uiState.value.messages
                    .map { it.id },
            )

            viewModel.loadOlderMessages()
            advanceUntilIdle()
            assertEquals(
                expectedIds.takeLast(300),
                viewModel.uiState.value.messages
                    .map { it.id },
            )
            assertTrue(viewModel.uiState.value.hasOlderMessages)
            assertFalse(viewModel.uiState.value.isLoadingOlder)

            viewModel.loadOlderMessages()
            advanceUntilIdle()
            assertEquals(
                expectedIds,
                viewModel.uiState.value.messages
                    .map { it.id },
            )
            assertFalse(viewModel.uiState.value.hasOlderMessages)
            assertFalse(viewModel.uiState.value.isLoadingOlder)
            assertEquals(expectedIds.toSet(), fakeRepo.dao.idsForSession("session-456"))
            assertEquals(0, fakeRepo.dao.fullSessionReads)
        }

    @Test
    fun paging_cacheEchoEnrichesExistingUuidBeforeEarlierRepeatedOccurrencesArrive() =
        runTest {
            val roles = listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL)
            for (index in 1..303) {
                val occurrence =
                    when (index) {
                        in 1..3 -> index - 1
                        in 151..153 -> index - 151
                        in 301..303 -> index - 301
                        else -> null
                    }
                val role = occurrence?.let { roles[it] } ?: MessageRole.SYSTEM
                val id =
                    when (index) {
                        in 1..3 -> "rest-session-456-${10 + requireNotNull(occurrence)}"
                        in 151..153 -> "rest-session-456-${20 + requireNotNull(occurrence)}"
                        in 301..303 -> "uuid-${requireNotNull(occurrence)}"
                        else -> "filler-$index"
                    }
                fakeRepo.dao.addMessageDirect(
                    io.wutz.apollo.data.local.ChatMessageEntity(
                        id = id,
                        sessionId = "session-456",
                        role = role.name,
                        content =
                            when (role) {
                                MessageRole.TOOL -> """{"output":"ok"}"""
                                MessageRole.SYSTEM -> "filler $index"
                                else -> "continue"
                            },
                        timestamp = index.toLong(),
                        toolName = if (role == MessageRole.TOOL && index > 300) "terminal" else null,
                    ),
                )
            }
            val storedIds = fakeRepo.dao.idsForSession("session-456")
            stubSession456Rests(success = false)
            val (viewModel, _) = createViewModelWithSession()
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            assertEquals(150, viewModel.uiState.value.messages.size)

            viewModel.loadOlderMessages()
            advanceUntilIdle()
            for (index in roles.indices) {
                val retained =
                    viewModel.uiState.value.messages
                        .single { it.id == "uuid-$index" }
                assertEquals("rest-session-456-${20 + index}", retained.restId)
            }
            assertEquals(297, viewModel.uiState.value.messages.size)

            viewModel.loadOlderMessages()
            advanceUntilIdle()
            val messages = viewModel.uiState.value.messages
            assertEquals(300, messages.size)
            for (index in roles.indices) {
                assertEquals(2, messages.count { it.role == roles[index] })
                assertTrue(messages.any { it.id == "rest-session-456-${10 + index}" })
                assertTrue(messages.any { it.id == "uuid-$index" })
            }
            assertEquals("terminal", messages.single { it.id == "uuid-2" }.toolName)
            assertEquals(storedIds, fakeRepo.dao.idsForSession("session-456"))
            assertFalse(viewModel.uiState.value.hasOlderMessages)
        }

    @Test
    fun paging_reusedToolPersistsCanonicalKeyWithoutReplacingLiveKey() =
        runTest {
            fakeRepo.dao.addMessageDirect(
                io.wutz.apollo.data.local.ChatMessageEntity(
                    id = "uuid-tool",
                    sessionId = "session-456",
                    role = "TOOL",
                    content = """{"output":"ok"}""",
                    timestamp = 1L,
                    toolName = "terminal",
                ),
            )
            val api = ApiClient.hermesApi
            val response = pagingResponse(42..42).body()!!
            coEvery {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            } returns
                retrofit2.Response.success(
                    response.copy(
                        messages =
                            response.messages.map {
                                it.copy(
                                    role = "tool",
                                    content = JsonPrimitive("""{"output":"ok"}"""),
                                )
                            },
                    ),
                )
            val (viewModel, _) = createViewModelWithSession()
            viewModel.switchSession("session-456")
            advanceUntilIdle()

            val message =
                viewModel.uiState.value.messages
                    .single()
            assertEquals("uuid-tool", message.id)
            assertEquals("rest-session-456-42", message.restId)
            assertEquals("terminal", message.toolName)
            assertEquals(setOf("uuid-tool", "rest-session-456-42"), fakeRepo.dao.idsForSession("session-456"))
        }

    @Test
    fun paging_emptyServerPageDoesNotHideOlderOfflineHistory() =
        runTest {
            val api = ApiClient.hermesApi
            val expectedIds = seedPagingCache(350)
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(IntRange.EMPTY)

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            assertEquals(150, viewModel.uiState.value.messages.size)
            assertTrue(viewModel.uiState.value.hasOlderMessages)
            viewModel.loadOlderMessages()
            advanceUntilIdle()
            assertEquals(300, viewModel.uiState.value.messages.size)
            assertEquals(expectedIds.toSet(), fakeRepo.dao.idsForSession("session-456"))
        }

    @Test
    fun paging_cacheFirst_serverHydrationDoesNotInflateInitialWindow() =
        runTest {
            val api = ApiClient.hermesApi
            seedPagingCache(10_000)
            val response =
                CompletableDeferred<retrofit2.Response<io.wutz.apollo.data.model.SessionMessagesResponse>>()
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            } coAnswers { response.await() }

            viewModel.switchSession("session-456")
            runCurrent()
            val cacheSize = viewModel.uiState.value.messages.size
            response.complete(pagingResponse(1..150))
            advanceUntilIdle()

            assertEquals(150, cacheSize)
            assertEquals(300, viewModel.uiState.value.messages.size)
            assertFalse(viewModel.uiState.value.isLoading)
            assertTrue(viewModel.uiState.value.hasOlderMessages)
            assertEquals(0, fakeRepo.dao.fullSessionReads)
        }

    @Test
    fun paging_serverFirst_cacheCursorStillAllowsOfflineHistory() =
        runTest {
            val api = ApiClient.hermesApi
            seedPagingCache(350)
            val cacheRead = CompletableDeferred<Unit>()
            fakeRepo.dao.beforeRead = { cacheRead.await() }
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(IntRange.EMPTY)

            viewModel.switchSession("session-456")
            runCurrent()
            cacheRead.complete(Unit)
            advanceUntilIdle()

            assertEquals(150, viewModel.uiState.value.messages.size)
            assertTrue(viewModel.uiState.value.hasOlderMessages)
            viewModel.loadOlderMessages()
            advanceUntilIdle()
            assertEquals(300, viewModel.uiState.value.messages.size)
            assertFalse(viewModel.uiState.value.isLoadingOlder)
        }

    @Test
    fun paging_pendingOlderPageIsSuppressedAndFailureAllowsRetry() =
        runTest {
            val api = ApiClient.hermesApi
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(151..300)
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            val pending =
                CompletableDeferred<retrofit2.Response<io.wutz.apollo.data.model.SessionMessagesResponse>>()
            coEvery {
                api.getSessionMessages("session-456", 150, 150, "latest", any())
            } coAnswers { pending.await() }

            viewModel.loadOlderMessages()
            runCurrent()
            repeat(5) { viewModel.loadOlderMessages() }
            runCurrent()
            coVerify(exactly = 1) {
                api.getSessionMessages("session-456", 150, 150, "latest", any())
            }
            assertTrue(viewModel.uiState.value.isLoadingOlder)
            pending.complete(retrofit2.Response.error(500, okhttp3.ResponseBody.create(null, "test failure")))
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isLoadingOlder)
            assertTrue(viewModel.uiState.value.hasOlderMessages)

            coEvery {
                api.getSessionMessages("session-456", 150, 150, "latest", any())
            } returns pagingResponse(1..150, offset = 150)
            viewModel.loadOlderMessages()
            advanceUntilIdle()
            assertEquals(300, viewModel.uiState.value.messages.size)
            assertFalse(viewModel.uiState.value.isLoadingOlder)
        }

    @Test
    fun paging_oldSessionResponseCannotReplaceNewSession() =
        runTest {
            val api = ApiClient.hermesApi
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(151..300)
            coEvery {
                api.getSessionMessages("session-other", any(), any(), any(), any())
            } returns pagingResponse(900..900)
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            val pending =
                CompletableDeferred<retrofit2.Response<io.wutz.apollo.data.model.SessionMessagesResponse>>()
            coEvery {
                api.getSessionMessages("session-456", 150, 150, "latest", any())
            } coAnswers { pending.await() }

            viewModel.loadOlderMessages()
            runCurrent()
            viewModel.switchSession("session-other")
            runCurrent()
            pending.complete(pagingResponse(1..150, offset = 150))
            advanceUntilIdle()

            assertEquals("session-other", viewModel.uiState.value.currentSessionId)
            assertEquals(
                listOf("rest-session-other-900"),
                viewModel.uiState.value.messages
                    .map { it.id },
            )
            assertFalse(viewModel.uiState.value.isLoadingOlder)
        }

    @Test
    fun paging_liveMessageArrivingBeforeComputedSnapshotCommitSurvives() =
        runTest {
            val api = ApiClient.hermesApi
            val cpu = QueuedHistoryDispatcher()
            val (viewModel, _) = createViewModelWithSession(historyDispatcher = cpu)
            coEvery {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(1..150)
            viewModel.switchSession("session-456")
            runCurrent()
            // Cache query runs first; hydration has already captured an empty snapshot.
            cpu.runNext()
            runCurrent()
            mockEventsFlow.emit(WsEvent.MessageComplete("Live during merge", "session-456"))
            cpu.runNext()
            // The queued WS event runs before the computed result can commit on Main.
            runCurrent()
            drainHistory(cpu)

            val messages = viewModel.uiState.value.messages
            assertEquals(151, messages.size)
            assertEquals(1, messages.count { it.content == "Live during merge" })
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun paging_sessionSwitchDiscardsQueuedCpuResult() =
        runTest {
            val api = ApiClient.hermesApi
            val cpu = QueuedHistoryDispatcher()
            val (viewModel, _) = createViewModelWithSession(historyDispatcher = cpu)
            coEvery {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(1..150)
            coEvery {
                api.getSessionMessages("session-other", any(), any(), any(), any())
            } returns pagingResponse(900..900)
            viewModel.switchSession("session-456")
            runCurrent()
            cpu.runNext()
            runCurrent()
            cpu.runNext()
            viewModel.switchSession("session-other")
            drainHistory(cpu)

            assertEquals("session-other", viewModel.uiState.value.currentSessionId)
            assertEquals(
                listOf("rest-session-other-900"),
                viewModel.uiState.value.messages
                    .map { it.id },
            )
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun paging_reconnectInvalidatesPendingOlderRequestAndItsCursor() =
        runTest {
            val api = ApiClient.hermesApi
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(301..450)
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            val oldResumeId = sentRequestMethods.last { it.first == WsMethods.SESSION_RESUME }.second
            mockEventsFlow.emit(WsEvent.RpcResult(oldResumeId, mapOf("session_id" to "runtime-456")))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isSessionReady)
            val pending =
                CompletableDeferred<retrofit2.Response<io.wutz.apollo.data.model.SessionMessagesResponse>>()
            coEvery {
                api.getSessionMessages("session-456", 150, 150, "latest", any())
            } coAnswers { pending.await() }
            viewModel.loadOlderMessages()
            runCurrent()

            coEvery {
                api.getSessionMessages("session-456", 150, 0, "latest", any())
            } returns pagingResponse(501..650)
            mockConnectionStatus.value = ConnectionStatus.RECONNECTING
            runCurrent()
            assertFalse(viewModel.uiState.value.isSessionReady)
            assertFalse(viewModel.uiState.value.isLoadingOlder)
            assertFalse(viewModel.sendMessage("preserved draft"))
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            runCurrent()
            pending.complete(pagingResponse(1..150, offset = 150))
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isLoadingOlder)
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.id == "rest-session-456-1" },
            )

            mockEventsFlow.emit(WsEvent.RpcResult(oldResumeId, mapOf("session_id" to "stale-runtime")))
            runCurrent()
            assertFalse(viewModel.uiState.value.isSessionReady)
            val freshResumeId = sentRequestMethods.last { it.first == WsMethods.SESSION_RESUME }.second
            mockEventsFlow.emit(WsEvent.RpcResult(freshResumeId, mapOf("session_id" to "fresh-runtime")))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isSessionReady)
            coEvery {
                api.getSessionMessages("session-456", 150, 150, "latest", any())
            } returns pagingResponse(351..500, offset = 150)
            viewModel.loadOlderMessages()
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.id == "rest-session-456-500" },
            )
            coVerify(exactly = 0) {
                api.getSessionMessages("session-456", 150, 300, "latest", any())
            }
        }

    @Test
    fun paging_offlineCacheRemainsReadableAndFreshResumeRestoresSendReadiness() =
        runTest {
            val ids = seedPagingCache(350)
            stubSession456Rests(success = false)
            val (viewModel, _) = createViewModelWithSession()
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isSessionReady)
            assertFalse(viewModel.sendMessage("draft"))
            mockConnectionStatus.value = ConnectionStatus.NO_NETWORK
            runCurrent()
            viewModel.loadOlderMessages()
            advanceUntilIdle()
            assertEquals(
                ids.takeLast(300),
                viewModel.uiState.value.messages
                    .map { it.id },
            )
            assertFalse(viewModel.uiState.value.isLoadingOlder)
            val api = ApiClient.hermesApi
            coEvery {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(IntRange.EMPTY)
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isSessionReady)
            val resumeId = sentRequestMethods.last { it.first == WsMethods.SESSION_RESUME }.second
            mockEventsFlow.emit(WsEvent.RpcResult(resumeId, mapOf("session_id" to "runtime-456")))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isSessionReady)
            assertTrue(viewModel.sendMessage("draft"))
            advanceUntilIdle()
            assertEquals(
                1,
                viewModel.uiState.value.messages
                    .count { it.content == "draft" },
            )
        }

    @Test
    fun paging_cacheAndServerCursorsAdvanceIndependently() =
        runTest {
            val api = ApiClient.hermesApi
            val cachedIds = seedPagingCache(350)
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(151..300)
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            repeat(2) {
                viewModel.loadOlderMessages()
                advanceUntilIdle()
            }
            coVerify(exactly = 1) {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            }
            assertEquals(
                cachedIds.toSet() + (151..300).map { "rest-session-456-$it" },
                viewModel.uiState.value.messages
                    .map { it.id }
                    .toSet(),
            )
            coEvery {
                api.getSessionMessages("session-456", 150, 150, "latest", any())
            } returns pagingResponse(1..150, offset = 150)
            viewModel.loadOlderMessages()
            advanceUntilIdle()

            // The remaining cache page contains only the latest REST rows already on screen.
            // This same action must reach the independent server cursor, not stop on those echoes.
            coVerify(exactly = 1) {
                api.getSessionMessages("session-456", 150, 150, "latest", any())
            }
            val expectedIds = cachedIds.toSet() + (1..300).map { "rest-session-456-$it" }
            assertEquals(650, viewModel.uiState.value.messages.size)
            assertEquals(
                expectedIds,
                viewModel.uiState.value.messages
                    .map { it.id }
                    .toSet(),
            )
            assertEquals(expectedIds, fakeRepo.dao.idsForSession("session-456"))
            assertFalse(viewModel.uiState.value.isLoadingOlder)
            assertEquals(0, fakeRepo.dao.fullSessionReads)
            assertTrue(fakeRepo.dao.pageLimits.all { it == 151 })
        }

    @Test
    fun paging_latestGrowthBetweenPagesAndSyncNeverSkipsOlderRows() =
        runTest {
            val api = ApiClient.hermesApi
            val (viewModel, _) = createViewModelWithSession()
            var total = 450
            coEvery {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            } coAnswers {
                val offset = arg<Int>(2)
                val end = (total - offset).coerceAtLeast(0)
                val start = (end - 150).coerceAtLeast(0) + 1
                pagingResponse(start..end, offset)
            }
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            viewModel.loadOlderMessages()
            advanceUntilIdle()
            total = 460
            viewModel.syncCurrentSession()
            advanceUntilIdle()
            repeat(2) {
                viewModel.loadOlderMessages()
                advanceUntilIdle()
            }

            assertEquals(
                (1..460).map { "rest-session-456-$it" }.toSet(),
                viewModel.uiState.value.messages
                    .map { it.id }
                    .toSet(),
            )
            assertFalse(viewModel.uiState.value.hasOlderMessages)
        }

    @Test
    fun paging_rawHiddenRowsStillAdvanceServerCursor() =
        runTest {
            val api = ApiClient.hermesApi
            val (viewModel, _) = createViewModelWithSession()
            val response = pagingResponse(1..150).body()!!
            coEvery {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            } returns
                retrofit2.Response.success(
                    response.copy(messages = response.messages.map { it.copy(content = JsonPrimitive("")) }),
                )
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.hasOlderMessages)
            coEvery {
                api.getSessionMessages("session-456", 150, 150, "latest", any())
            } returns pagingResponse(IntRange.EMPTY, offset = 150)
            viewModel.loadOlderMessages()
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.hasOlderMessages)
            coVerify(exactly = 1) {
                api.getSessionMessages("session-456", 150, 150, "latest", any())
            }
        }

    @Test
    fun paging_cacheReadExceptionSettlesAndAllowsRetry() =
        runTest {
            val ids = seedPagingCache(350)
            stubSession456Rests(success = false)
            fakeRepo.dao.beforeRead = { error("Synthetic Room read failure") }
            val (viewModel, _) = createViewModelWithSession()
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isLoading)
            assertTrue(viewModel.uiState.value.hasOlderMessages)
            fakeRepo.dao.beforeRead = {}
            viewModel.loadOlderMessages()
            advanceUntilIdle()

            assertEquals(
                ids.takeLast(150),
                viewModel.uiState.value.messages
                    .map { it.id },
            )
            assertFalse(viewModel.uiState.value.isLoadingOlder)
        }

    @Test
    fun paging_mapperExceptionSettlesAndRetriesSameServerOffset() =
        runTest {
            val api = ApiClient.hermesApi
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(151..300)
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            val invalid = pagingResponse(1..150, 150).body()!!
            coEvery {
                api.getSessionMessages("session-456", 150, 150, "latest", any())
            } returns retrofit2.Response.success(invalid.copy(messages = invalid.messages.map { it.copy(id = null) }))
            viewModel.loadOlderMessages()
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isLoadingOlder)
            assertTrue(viewModel.uiState.value.hasOlderMessages)
            coEvery {
                api.getSessionMessages("session-456", 150, 150, "latest", any())
            } returns pagingResponse(1..150, 150)
            viewModel.loadOlderMessages()
            advanceUntilIdle()
            assertEquals(300, viewModel.uiState.value.messages.size)
        }

    @Test
    fun paging_cacheExhaustedOverlappingServerPageKeepsExistingOrder() =
        runTest { assertOverlappingHistoryPageKeepsOrder(equalTimestamps = false) }

    @Test
    fun paging_cacheExhaustedOverlapWithEqualTimestampsKeepsExistingOrder() =
        runTest { assertOverlappingHistoryPageKeepsOrder(equalTimestamps = true) }

    /**
     * RED reproducer for the diagnostic v4 shape: two cold UUID-only USER rows,
     * LOCAL_PENDING and unanchored; one has no receipt, the other an exact-only
     * ACCEPTED receipt whose server row is outside this bounded REST window.
     * The before-window assertion is a placement target, NOT delivery proof:
     * the receiptless row could equally be a crash-before-submit prompt.
     */
    private suspend fun TestScope.verifyColdLocalPendingOrphans(cacheFirst: Boolean) {
        val session = "session-456"
        val noReceipt =
            ChatMessage(
                id = "cold-local-no-receipt",
                role = MessageRole.USER,
                content = "newer prompt",
                timestamp = Long.MAX_VALUE,
                messageProvenance = MessageProvenance.LOCAL_PENDING,
            )
        val accepted =
            ChatMessage(
                id = "cold-local-accepted",
                role = MessageRole.USER,
                content = "repeatable accepted prompt",
                timestamp = Long.MAX_VALUE - 1,
                messageProvenance = MessageProvenance.LOCAL_PENDING,
            )
        listOf(noReceipt, accepted).forEach { fakeRepo.persistMessage(it, session) }
        val persisted = listOf(noReceipt, accepted).map { checkNotNull(fakeRepo.dao.getMessage(it.id)) }
        assertTrue(persisted.all { it.restId == null && it.localAnchorOrder == null && it.localPredecessorId == null })
        assertTrue(persisted.all { it.messageProvenance == MessageProvenance.LOCAL_PENDING.name })
        val scope =
            listOf(
                AuthManager.getBaseUrl(),
                AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
            ).joinToString("\u001f")
        val store = ChatSendStore()
        store.put(
            PendingSend(
                id = accepted.id,
                scope = scope,
                sessionId = session,
                text = accepted.content,
                mode = BusySendMode.QUEUE,
                state = PendingSendState.ACCEPTED,
                userRowId = 77L,
                requiresExactReconciliation = true,
            ),
        )
        val cacheRead = CompletableDeferred<Unit>()
        if (!cacheFirst) fakeRepo.dao.beforeRead = { cacheRead.await() }
        val response = CompletableDeferred<Response<SessionMessagesResponse>>()
        val api = ApiClient.hermesApi
        coEvery { api.getSessionMessages(session, any(), any(), any(), any()) } coAnswers { response.await() }
        val (viewModel, _) = createViewModelWithSession(sendStore = store)
        viewModel.switchSession(session)
        runCurrent()
        response.complete(
            Response.success(
                SessionMessagesResponse(
                    messages =
                        listOf(
                            // Equal text is an unrelated occurrence (row 100 != receipt row 77).
                            SessionMessage(id = 100, role = "user", content = JsonPrimitive(accepted.content)),
                            SessionMessage(id = 101, role = "user", content = JsonPrimitive("newer prompt")),
                            SessionMessage(id = 102, role = "assistant", content = JsonPrimitive("newest reply")),
                        ),
                    pagination = PaginationInfo(limit = 150, offset = 0, order = "latest", returned = 3),
                ),
            ),
        )
        runCurrent()
        cacheRead.complete(Unit)
        advanceUntilIdle()
        val canonicalIds = (100..102).map { "rest-$session-$it" }
        val expected = listOf(noReceipt.id, accepted.id) + canonicalIds
        repeat(2) { pass ->
            val messages = viewModel.uiState.value.messages
            assertNull("REST hydration must complete", viewModel.uiState.value.errorMessage)
            assertEquals(1, messages.count { it.id == noReceipt.id })
            assertEquals(1, messages.count { it.id == accepted.id })
            assertTrue(messages.map { it.id }.containsAll(canonicalIds))
            listOf(noReceipt, accepted).forEach { source ->
                val retained = messages.single { it.id == source.id }
                assertEquals(source.content, retained.content)
                assertEquals(source.timestamp, retained.timestamp)
                assertEquals(MessageProvenance.LOCAL_PENDING, retained.messageProvenance)
                assertNull("No text-only REST identity", retained.canonicalRestId)
                assertNull(retained.localAnchorOrder)
                assertNull(retained.localPredecessorId)
                assertFalse(retained.isHistoricalCache)
                assertTrue("Placement only; never delivery proof", retained.isRestoredUnconfirmed)
            }
            assertEquals(2, messages.count { it.role == MessageRole.USER && it.content == accepted.content })
            assertEquals(2, messages.count { it.role == MessageRole.USER && it.content == noReceipt.content })
            assertEquals(listOf(accepted.id), store.all().map { it.id })
            assertEquals(PendingSendState.ACCEPTED, store.all().single().state)
            assertEquals(77L, store.all().single().userRowId)
            assertTrue(store.all().single().requiresExactReconciliation)
            assertTrue(fakeRepo.dao.idsForSession(session).containsAll(listOf(noReceipt.id, accepted.id)))
            assertEquals(0, fakeRepo.dao.fullSessionReads)
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
            // Expected RED on v3: both cold LOCAL_PENDING rows instead follow the latest reply.
            assertEquals(
                "pass=$pass cold placement must be independent of delivery state",
                expected,
                messages.map { it.id },
            )
            viewModel.syncCurrentSession()
            advanceUntilIdle()
        }
    }

    private suspend fun TestScope.verifyRestoredLegacyUserPlacement(cacheFirst: Boolean) {
        val restored =
            (1..3).map { index ->
                ChatMessage(
                    id = "restored-legacy-$index",
                    role = MessageRole.USER,
                    content = "Earlier prompt $index",
                    // Placement must not infer delivery or chronology from wall-clock age.
                    timestamp = Long.MAX_VALUE - index,
                )
            }
        val pending =
            ChatMessage(
                id = "real-pending",
                role = MessageRole.USER,
                content = "Unsent current prompt",
                timestamp = 1L,
                messageProvenance = MessageProvenance.LOCAL_PENDING,
            )
        (restored + pending).forEach { fakeRepo.persistMessage(it, "session-456") }
        val cacheRead = CompletableDeferred<Unit>()
        if (!cacheFirst) fakeRepo.dao.beforeRead = { cacheRead.await() }
        val response = CompletableDeferred<retrofit2.Response<SessionMessagesResponse>>()
        val (viewModel, _) = createViewModelWithSession()
        val api = ApiClient.hermesApi
        coEvery {
            api.getSessionMessages("session-456", any(), any(), any(), any())
        } coAnswers { response.await() }

        viewModel.switchSession("session-456")
        runCurrent()
        response.complete(pagingResponse(100..102))
        runCurrent()
        cacheRead.complete(Unit)
        advanceUntilIdle()

        // The receiptless, unanchored cold pending row has no proven current-turn placement.
        // Seat it outside the live tail without confirming or discarding its unsent content.
        val expected = restored.map { it.id } + pending.id + (100..102).map { "rest-session-456-$it" }
        assertEquals(
            "Restored legacy prompts must not form a stale block after the newest reply",
            expected,
            viewModel.uiState.value.messages
                .map { it.id },
        )
        restored.forEach { original ->
            val retained =
                viewModel.uiState.value.messages
                    .single { it.id == original.id }
            assertEquals(original.content, retained.content)
            assertEquals(original.timestamp, retained.timestamp)
            assertEquals(MessageProvenance.UNKNOWN, retained.messageProvenance)
            assertNull("Moving a row must not invent delivery confirmation", retained.canonicalRestId)
            assertFalse("Restored placement is not proven historical delivery", retained.isHistoricalCache)
        }
        val retainedPending =
            viewModel.uiState.value.messages
                .single { it.id == pending.id }
        assertEquals(MessageProvenance.LOCAL_PENDING, retainedPending.messageProvenance)
        assertNull(retainedPending.canonicalRestId)
        assertFalse(retainedPending.isHistoricalCache)
        assertTrue(retainedPending.isRestoredUnconfirmed)
        assertTrue(fakeRepo.dao.idsForSession("session-456").containsAll(restored.map { it.id } + pending.id))
        assertEquals(0, fakeRepo.dao.fullSessionReads)
        coVerify(exactly = 1) {
            api.getSessionMessages("session-456", any(), any(), any(), any())
        }
        verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }

        viewModel.syncCurrentSession()
        advanceUntilIdle()
        assertEquals(
            expected,
            viewModel.uiState.value.messages
                .map { it.id },
        )
        verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
    }

    private suspend fun TestScope.verifyQueuedPromptRestoration(command: String) {
        // Restore the durable outbox too: a fresh empty store misrepresents a queued
        // prompt as a receiptless orphan and cannot certify pending-queue behavior.
        val store = ChatSendStore()
        val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
        assertTrue(viewModel.sendMessage("$command pending queued prompt"))
        advanceUntilIdle()
        val queued =
            viewModel.uiState.value.messages
                .single { it.role == MessageRole.USER }
        assertEquals("pending queued prompt", queued.content)
        assertEquals(
            "Queued prompts must retain durable pending provenance",
            MessageProvenance.LOCAL_PENDING.name,
            fakeRepo.dao.getMessage(queued.id)?.messageProvenance,
        )

        val api = ApiClient.hermesApi
        coEvery {
            api.getSessionMessages(sessionId, any(), any(), any(), any())
        } returns pagingResponse(100..102)
        val recreated = createViewModel(sendStore = store)
        advanceUntilIdle()
        recreated.switchSession(sessionId)
        advanceUntilIdle()

        val restored = recreated.uiState.value.messages
        assertEquals(queued.id, restored.last().id)
        assertTrue(restored.indexOfFirst { it.id == "rest-$sessionId-102" } in 0 until restored.lastIndex)
        assertEquals(MessageProvenance.LOCAL_PENDING, restored.last().messageProvenance)
        assertNull(restored.last().canonicalRestId)
        assertFalse(restored.last().isHistoricalCache)
        verify(exactly = 1) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
    }

    private suspend fun TestScope.verifyColdRestoredAcceptedLegacyPlacement(cacheFirst: Boolean) {
        val session = "session-456"
        val olderText = "repeatable older prompt"
        val cached =
            ChatMessage(
                id = "legacy-accepted-uuid",
                role = MessageRole.USER,
                content = olderText,
                timestamp = Long.MAX_VALUE,
                messageProvenance = MessageProvenance.UNKNOWN,
            )
        fakeRepo.persistMessage(cached, session)
        val scope =
            listOf(
                AuthManager.getBaseUrl(),
                AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
            ).joinToString("\u001f")
        val store = ChatSendStore()
        store.put(
            PendingSend(
                id = cached.id,
                scope = scope,
                sessionId = session,
                text = olderText,
                mode = BusySendMode.QUEUE,
                state = PendingSendState.ACCEPTED,
                requiresExactReconciliation = true,
            ),
        )
        val cacheRead = CompletableDeferred<Unit>()
        if (!cacheFirst) fakeRepo.dao.beforeRead = { cacheRead.await() }
        val response = CompletableDeferred<Response<SessionMessagesResponse>>()
        val api = ApiClient.hermesApi
        coEvery { api.getSessionMessages(session, any(), any(), any(), any()) } coAnswers { response.await() }
        val (viewModel, _) = createViewModelWithSession(sendStore = store)
        viewModel.switchSession(session)
        runCurrent()
        response.complete(
            Response.success(
                SessionMessagesResponse(
                    messages =
                        listOf(
                            SessionMessage(id = 100, role = "user", content = JsonPrimitive(olderText)),
                            SessionMessage(id = 101, role = "user", content = JsonPrimitive("newer prompt")),
                            SessionMessage(id = 102, role = "assistant", content = JsonPrimitive("newest reply")),
                        ),
                    pagination = PaginationInfo(limit = 150, offset = 0, order = "latest", returned = 3),
                ),
            ),
        )
        runCurrent()
        cacheRead.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { api.getSessionMessages(session, any(), any(), any(), any()) }
        assertNull("REST fixture must complete without an error", viewModel.uiState.value.errorMessage)
        assertEquals(listOf(cached.id), store.all().map { it.id })
        assertEquals(PendingSendState.ACCEPTED, store.all().single().state)
        assertTrue(store.all().single().requiresExactReconciliation)
        assertNull(store.all().single().userRowId)
        val retained =
            viewModel.uiState.value.messages
                .single { it.id == cached.id }
        assertEquals(olderText, retained.content)
        assertEquals(MessageProvenance.UNKNOWN, retained.messageProvenance)
        assertNull("Equal text is not a durable row identity", retained.canonicalRestId)
        assertFalse(retained.isHistoricalCache)
        assertEquals(
            2,
            viewModel.uiState.value.messages
                .count {
                    it.role == MessageRole.USER && it.content == olderText
                },
        )
        verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        assertEquals(
            "Cold accepted UUID must precede the bounded canonical window, not follow its newest reply",
            listOf(cached.id, "rest-$session-100", "rest-$session-101", "rest-$session-102"),
            viewModel.uiState.value.messages
                .map { it.id },
        )
    }

    // Chat paging: deterministic regressions, with no real network or wall-clock timing.
    private fun seedPagingCache(
        count: Int,
        sameTimestamp: Boolean = false,
    ): List<String> =
        (1..count).map { index ->
            val id = "cached-${index.toString().padStart(5, '0')}"
            fakeRepo.dao.addMessageDirect(
                io.wutz.apollo.data.local.ChatMessageEntity(
                    id = id,
                    sessionId = "session-456",
                    role = "user",
                    content = "Cached message $index",
                    timestamp = if (sameTimestamp) 1L else index.toLong(),
                ),
            )
            id
        }

    private fun pagingResponse(
        rows: IntRange,
        offset: Int = 0,
    ): retrofit2.Response<io.wutz.apollo.data.model.SessionMessagesResponse> =
        retrofit2.Response.success(
            io.wutz.apollo.data.model.SessionMessagesResponse(
                messages =
                    rows.map { index ->
                        io.wutz.apollo.data.model.SessionMessage(
                            id = index,
                            role = "assistant",
                            content = JsonPrimitive("Server message $index"),
                            timestamp = JsonPrimitive(index),
                        )
                    },
                pagination =
                    io.wutz.apollo.data.model.PaginationInfo(
                        limit = 150,
                        offset = offset,
                        order = "latest",
                        returned = rows.count(),
                    ),
            ),
        )

    /** Holds CPU continuations without sleeping or starting a real thread. */
    private class QueuedHistoryDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        val pending: Boolean get() = queue.isNotEmpty()

        override fun dispatch(
            context: kotlin.coroutines.CoroutineContext,
            block: Runnable,
        ) {
            queue.addLast(block)
        }

        fun runNext() = queue.removeFirst().run()
    }

    private fun TestScope.drainHistory(dispatcher: QueuedHistoryDispatcher) {
        repeat(100) {
            runCurrent()
            if (!dispatcher.pending) return
            dispatcher.runNext()
        }
        error("History did not settle after 100 CPU continuations")
    }

    private suspend fun TestScope.assertOverlappingHistoryPageKeepsOrder(equalTimestamps: Boolean) {
        val api = ApiClient.hermesApi
        val (viewModel, _) = createViewModelWithSession()
        (1..350).forEach { index ->
            fakeRepo.dao.addMessageDirect(
                io.wutz.apollo.data.local.ChatMessageEntity(
                    id = "rest-session-456-$index",
                    sessionId = "session-456",
                    role = "ASSISTANT",
                    content = "Server message $index",
                    timestamp = if (equalTimestamps) 1_000L else index * 1_000L,
                ),
            )
        }
        coEvery { api.getSessionMessages("session-456", any(), any(), any(), any()) } coAnswers {
            val offset = arg<Int>(2)
            val end = 350 - offset
            val response = pagingResponse((end - 149).coerceAtLeast(1)..end, offset).body()!!
            retrofit2.Response.success(
                if (equalTimestamps) {
                    response.copy(messages = response.messages.map { it.copy(timestamp = JsonPrimitive(1)) })
                } else {
                    response
                },
            )
        }
        viewModel.switchSession("session-456")
        advanceUntilIdle()
        repeat(2) {
            viewModel.loadOlderMessages()
            advanceUntilIdle()
        }
        val before =
            viewModel.uiState.value.messages
                .map { it.id }
        assertEquals((1..350).map { "rest-session-456-$it" }.toSet(), before.toSet())
        assertEquals(350, before.size)
        coVerify(exactly = 1) { api.getSessionMessages("session-456", any(), any(), any(), any()) }
        // Local history is exhausted. Both subsequent server pages overlap it completely.
        repeat(2) {
            viewModel.loadOlderMessages()
            advanceUntilIdle()
            assertEquals(
                before,
                viewModel.uiState.value.messages
                    .map { it.id },
            )
        }
        assertFalse(viewModel.uiState.value.hasOlderMessages)
        coVerify(exactly = 1) { api.getSessionMessages("session-456", 150, 150, "latest", any()) }
        coVerify(exactly = 1) { api.getSessionMessages("session-456", 150, 300, "latest", any()) }
    }
}
