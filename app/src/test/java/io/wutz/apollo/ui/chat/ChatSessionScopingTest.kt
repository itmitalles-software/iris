package io.wutz.apollo.ui.chat

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.local.ChatMessageDao
import io.wutz.apollo.data.local.ChatMessageEntity
import io.wutz.apollo.data.local.DataScope
import io.wutz.apollo.data.local.HermesDatabase
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.HermesApiService
import io.wutz.apollo.data.session.ProfileSwitchCoordinator
import io.wutz.apollo.data.ws.ConnectionStatus
import io.wutz.apollo.data.ws.HermesWsClient
import io.wutz.apollo.data.ws.WsEvent
import io.wutz.apollo.ui.chat.fakes.FakeChatMessageDao
import io.wutz.apollo.ui.chat.fakes.FakeChatPersistenceRepository
import io.wutz.apollo.ui.chat.fakes.FakeSlashUsageStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Code review 2026-10-14 (chat area): background-session events must not leak into the open
 * session (S4, C2, C3), session reset clears every prompt (C9), and reducer persistence keeps
 * event order (C6). Kept out of ChatViewModelTest to avoid merge conflicts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionScopingTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockEventsFlow = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)
    private val mockConnectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    private lateinit var app: Application
    private lateinit var fakeRepo: FakeChatPersistenceRepository
    private lateinit var mockApi: HermesApiService
    private var reqCount = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        reqCount = 0

        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        mockkObject(AuthManager)
        // ChatModelSwitchDelegate.preloadModelOptions() reads pinned models
        // when the catalog load succeeds; without this stub the spy falls
        // through to the real AuthManager and throws "not initialized".
        every { AuthManager.getPinnedModels() } returns emptyList()
        // The delegate and the shared catalog store both collect this flow;
        // park them on a never-emitting state so a later real-AuthManager
        // emission cannot resume a stale Main-dispatched collector.
        every { AuthManager.dataScopeFlow } returns MutableStateFlow<DataScope?>(null)
        mockkObject(HermesWsClient)
        mockkObject(ApiClient)
        mockkObject(HermesDatabase)

        app = mockk(relaxed = true)
        EnglishTestResources.install(app)
        fakeRepo = FakeChatPersistenceRepository()
        mockApi = mockk(relaxed = true)

        mockConnectionStatus.value = ConnectionStatus.DISCONNECTED

        every { AuthManager.getToken() } returns "test-token"
        every { AuthManager.getPinnedModels() } returns emptyList()
        every { AuthManager.getBaseUrl() } returns "http://test.local/"
        every { AuthManager.getSelectedProfileId() } returns null
        every { AuthManager.getBusySendMode() } returns io.wutz.apollo.data.model.BusySendMode.CORRECT
        every { AuthManager.isTypingEffectEnabled() } returns true
        every { AuthManager.getTypingEffectDelayMs() } returns 30
        every { AuthManager.isMessageStatsEnabled() } returns false
        every { AuthManager.isUserMessageTokensEnabled() } returns true
        every { AuthManager.isAssistantMessageTokensEnabled() } returns true
        every { AuthManager.isTokensPerSecondEnabled() } returns true
        every { AuthManager.isModelProviderShown() } returns false
        every { AuthManager.isAutoReconnect() } returns false
        every { AuthManager.isRestoreLastSession() } returns false
        every { AuthManager.getLastOpenedSessionId() } returns null
        every { AuthManager.setLastOpenedSessionId(any()) } returns Unit
        every { AuthManager.clearLastOpenedSessionId() } returns Unit
        every { HermesWsClient.events } returns mockEventsFlow
        every { HermesWsClient.connectionStatus } returns mockConnectionStatus
        every { HermesWsClient.connect() } answers {
            mockConnectionStatus.value = ConnectionStatus.CONNECTING
        }
        every { HermesWsClient.disconnect() } returns Unit

        // ChatViewModel's init subscribes to ProfileSwitchCoordinator.switched
        // on viewModelScope. Without mocking the singleton, every VM created
        // here parks a collector on the REAL flow — when a later test class
        // (e.g. ProfileSwitchCoordinatorTest) emits on it, the stale-Main
        // resumption crashes with DispatchException (CI flakes). Mirror
        // ChatViewModelTest: stub the flow with a never-emitting mock so the
        // collectors park harmlessly.
        mockkObject(ProfileSwitchCoordinator)
        every { ProfileSwitchCoordinator.switched } returns MutableSharedFlow<String>()
        every { ProfileSwitchCoordinator.connectionSwitched } returns MutableSharedFlow<String>()

        every { HermesWsClient.send(any(), any(), any()) } answers {
            reqCount++
            val id = "req-id-$reqCount"
            arg<((String) -> Unit)?>(2)?.invoke(id)
            id
        }
        every { HermesWsClient.sendMessage(any(), any(), any(), any()) } answers {
            reqCount++
            val id = "req-msg-$reqCount"
            arg<((String) -> Unit)?>(2)?.invoke(id)
            id
        }
        // Catch-all: mirror send() but complete immediately (no 120s timer).
        every { HermesWsClient.request(any(), any(), any()) } answers {
            HermesWsClient.send(arg(0), arg(1)) {}
            CompletableDeferred<Any?>(Unit)
        }
        every { ApiClient.hermesApi } returns mockApi
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private suspend fun TestScope.createViewModelWithSession(
        repository: ChatPersistenceRepository = fakeRepo,
    ): Pair<ChatViewModel, String> {
        val vm =
            ChatViewModel(
                app,
                false,
                repository,
                FakeSlashUsageStore(),
                ioDispatcher = testDispatcher,
                sendStore = ChatSendStore(),
            )
        advanceUntilIdle()
        mockConnectionStatus.value = ConnectionStatus.CONNECTED
        mockEventsFlow.emit(WsEvent.GatewayReady(null))
        advanceUntilIdle()
        // req-id-3 = session.create (after loadSessions + fetchCommandCatalog)
        mockEventsFlow.emit(WsEvent.RpcResult("req-id-3", mapOf("session_id" to SESSION)))
        advanceUntilIdle()
        assertEquals(SESSION, vm.uiState.value.currentSessionId)
        return Pair(vm, SESSION)
    }

    private suspend fun TestScope.emit(event: WsEvent) {
        mockEventsFlow.emit(event)
        advanceUntilIdle()
    }

    @Test
    fun backgroundSessionPromptsDoNotAppearInTheOpenSession() =
        runTest {
            val (vm, _) = createViewModelWithSession()

            emit(WsEvent.ApprovalRequest("rm -rf /tmp/x", "delete", null, OTHER, requestId = "approval-b"))
            emit(WsEvent.SudoRequest("sudo-b", OTHER))
            emit(WsEvent.SecretRequest("secret-b", OTHER, envVar = "TOKEN", prompt = "token?"))
            emit(WsEvent.VaultUnlockRequest("vault-b", OTHER))
            emit(WsEvent.VaultSaveLoginRequest("save-b", OTHER))
            emit(WsEvent.VaultCodeRequest("code-b", OTHER))

            val state = vm.uiState.value
            assertTrue(state.messages.none { it.approvalInfo != null })
            assertNull(state.sudoPrompt)
            assertNull(state.secretPrompt)
            assertNull(state.vaultUnlockPrompt)
            assertNull(state.vaultSaveLoginPrompt)
            assertNull(state.vaultCodePrompt)
        }

    @Test
    fun currentAndUnscopedPromptsStillAppear() =
        runTest {
            val (vm, session) = createViewModelWithSession()

            emit(WsEvent.ApprovalRequest("ls", "list", null, session, requestId = "approval-a"))
            emit(WsEvent.ApprovalRequest("pwd", "legacy", null, null, requestId = "approval-legacy"))
            emit(WsEvent.SudoRequest("sudo-a", session))
            emit(WsEvent.VaultUnlockRequest("vault-a", session))

            val state = vm.uiState.value
            assertEquals(
                listOf("approval-a", "approval-legacy"),
                state.messages.mapNotNull { it.approvalInfo?.requestId },
            )
            assertEquals("sudo-a", state.sudoPrompt?.requestId)
            assertEquals("vault-a", state.vaultUnlockPrompt?.requestId)
        }

    @Test
    fun backgroundToolStartDoesNotTruncateTheStreamingReply() =
        runTest {
            val (vm, session) = createViewModelWithSession()

            emit(WsEvent.MessageStart(session))
            emit(WsEvent.MessageToken("Hello ", session))
            emit(WsEvent.MessageToken("world", session))
            emit(WsEvent.ToolStart("terminal", mapOf("tool_id" to "t-b"), OTHER))
            emit(WsEvent.MessageToken("!", session))

            assertEquals(
                "Hello world!",
                vm.streamingState.value.streamingMessage
                    ?.content,
            )
        }

    @Test
    fun clarifyKeepsTurnBookkeepingAndIgnoresOtherSessions() =
        runTest {
            val (vm, session) = createViewModelWithSession()

            emit(WsEvent.MessageStart(session))
            emit(WsEvent.MessageToken("Let me check first", session))
            // Seals the interim text as an orphan the final completion must de-duplicate.
            emit(WsEvent.ToolStart("clarify", mapOf("tool_id" to "t-1"), session))
            val sealed = vm.streamingState.value.sealedOrphanIds
            assertEquals(1, sealed.size)

            emit(WsEvent.ClarifyRequest("Which one?", listOf("a", "b"), clarifyId = "c-b", sessionId = OTHER))
            assertNull(vm.uiState.value.clarifyRequest)
            assertEquals(sealed, vm.streamingState.value.sealedOrphanIds)

            emit(WsEvent.ClarifyRequest("Which one?", listOf("a", "b"), clarifyId = "c-a", sessionId = session))
            assertEquals(
                "c-a",
                vm.uiState.value.clarifyRequest
                    ?.clarifyId,
            )
            assertEquals(sealed, vm.streamingState.value.sealedOrphanIds)
            assertEquals(false, vm.streamingState.value.isThinking)
        }

    @Test
    fun sessionResetClearsVaultPrompts() =
        runTest {
            val (vm, session) = createViewModelWithSession()
            emit(WsEvent.VaultUnlockRequest("vault-a", session))
            emit(WsEvent.VaultSaveLoginRequest("save-a", session))
            emit(WsEvent.VaultCodeRequest("code-a", session))
            assertNotNull(vm.uiState.value.vaultUnlockPrompt)

            vm.createNewSession()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertNull(state.vaultUnlockPrompt)
            assertNull(state.vaultSaveLoginPrompt)
            assertNull(state.vaultCodePrompt)
        }

    @Test
    fun reducerPersistenceKeepsEventOrderForTheSameRow() =
        runTest {
            val inner = FakeChatMessageDao()
            // A slow first write (RUNNING) used to finish after the fast second one (COMPLETED).
            val slowRunningDao =
                object : ChatMessageDao by inner {
                    override suspend fun upsert(message: ChatMessageEntity) {
                        if (message.toolStatus.equals("running", ignoreCase = true)) delay(1_000)
                        inner.upsert(message)
                    }
                }
            val (_, session) = createViewModelWithSession(ChatPersistenceRepository(slowRunningDao))

            // Both events are handled before the slow write finishes.
            mockEventsFlow.emit(WsEvent.ToolStart("terminal", mapOf("tool_id" to "tool-1"), session))
            mockEventsFlow.emit(
                WsEvent.ToolComplete("terminal", mapOf("tool_id" to "tool-1", "result" to "ok"), session),
            )
            advanceUntilIdle()

            val stored = inner.getMessagesForSession(session).single { it.role.equals("tool", ignoreCase = true) }
            assertTrue(stored.toolStatus.equals("completed", ignoreCase = true))
        }

    private companion object {
        const val SESSION = "session-a"
        const val OTHER = "session-b"
    }
}
