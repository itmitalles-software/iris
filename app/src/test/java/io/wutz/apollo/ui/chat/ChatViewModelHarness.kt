package io.wutz.apollo.ui.chat

import android.app.Application
import android.util.Log
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.local.DataScope
import io.wutz.apollo.data.local.HermesDatabase
import io.wutz.apollo.data.model.BusySendMode
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.HermesApiService
import io.wutz.apollo.data.session.ActiveSessionHolder
import io.wutz.apollo.data.session.ProfileSwitchCoordinator
import io.wutz.apollo.data.ws.ConnectionStatus
import io.wutz.apollo.data.ws.HermesWsClient
import io.wutz.apollo.data.ws.WsEvent
import io.wutz.apollo.data.ws.WsMethods
import io.wutz.apollo.data.ws.contract.ProcessStopResult
import io.wutz.apollo.data.ws.contract.RpcMethods
import io.wutz.apollo.testutil.MainDispatcherRule
import io.wutz.apollo.ui.chat.fakes.FakeChatPersistenceRepository
import io.wutz.apollo.ui.chat.fakes.FakeSlashUsageStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File

/**
 * Shared fixture for [ChatViewModel] tests: installs the test Main dispatcher, stubs the singletons the VM reads
 * (AuthManager, HermesWsClient, ApiClient, HermesDatabase, ProfileSwitchCoordinator), routes every WebSocket send
 * through deterministic request ids (`req-id-N`, `req-msg-N`) and creates view models wired to the test scheduler
 * with [ChatTimingConfig.TEST]. Tests re-stub anything specific after the rule ran (in `@Before` or the test).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelHarness : TestRule {
    private val mainDispatcherRule = MainDispatcherRule()
    val testDispatcher: TestDispatcher get() = mainDispatcherRule.dispatcher
    val mockEventsFlow = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)
    val mockConnectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val mockSwitchFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    lateinit var app: Application
    lateinit var fakeRepo: FakeChatPersistenceRepository
    lateinit var fakeSlashUsageStore: FakeSlashUsageStore
    lateinit var attachmentCacheDir: File
    lateinit var attachmentFilesDir: File

    /** Relaxed `ApiClient.hermesApi` mock; model options are stubbed so the GatewayReady preload is safe. */
    lateinit var mockApi: HermesApiService

    /** Counter used to generate unique WS request IDs. */
    var reqCount = 0

    /** Method and id of every `send()`, in order. */
    val sentRequestMethods = mutableListOf<Pair<String, String>>()

    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        mainDispatcherRule.apply(
            object : Statement() {
                override fun evaluate() {
                    setUp()
                    try {
                        base.evaluate()
                    } finally {
                        tearDown()
                    }
                }
            },
            description,
        )

    private fun setUp() {
        reqCount = 0
        sentRequestMethods.clear()

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockkObject(AuthManager)
        every { AuthManager.getPinnedModels() } returns emptyList()
        mockkObject(HermesWsClient)
        // Catch-all for unstubbed request() calls: the real implementation
        // registers a pending call and launches a 120s timeout job on the
        // singleton's real-IO wsScope. The timer outlives the test class,
        // fires after unmockkAll and crashes an unrelated later test with
        // MockK's "can't find stub" (UncaughtExceptionsBeforeTest). Mirror
        // the real request()'s send() delegation (tests verify send calls)
        // but skip the timer. Must COMPLETE (Unit): a never-completing
        // deferred freezes the VM's event collector at the SESSION_CREATE
        // session.usage await and lets its late completion race per-test
        // capture stubs (slash-focus flake, CI 31137581335 on 4645a2b).
        // All four parameters: a three-matcher stub only covers suppressErrorEvent = false, so slash.exec
        // and process calls (suppressErrorEvent = true) reached the real transport, which queued the request,
        // opened a real socket on Dispatchers.IO and crashed a later test on the unstubbed AuthManager.
        every { HermesWsClient.request(any(), any(), any(), any()) } answers {
            HermesWsClient.send(arg(0), arg(1)) {}
            CompletableDeferred<Any?>(Unit)
        }
        // /stop also fires process.stop; default to "nothing to kill" so unrelated /stop tests never hit the socket.
        coEvery { HermesWsClient.call(RpcMethods.PROCESS_STOP, any(), any(), any()) } returns
            ProcessStopResult(killed = 0)
        mockkObject(ApiClient)
        mockkObject(HermesDatabase)

        app = mockk(relaxed = true)
        EnglishTestResources.install(app)
        attachmentCacheDir =
            java.nio.file.Files
                .createTempDirectory("chat-attachments-test")
                .toFile()
        attachmentFilesDir =
            java.nio.file.Files
                .createTempDirectory("chat-send-files-test")
                .toFile()
        every { app.cacheDir } returns attachmentCacheDir
        every { app.filesDir } returns attachmentFilesDir
        fakeRepo = FakeChatPersistenceRepository()
        fakeSlashUsageStore = FakeSlashUsageStore()
        ActiveSessionHolder.set(null)

        mockConnectionStatus.value = ConnectionStatus.DISCONNECTED

        every { AuthManager.getToken() } returns "test-token"
        every { AuthManager.getBaseUrl() } returns "http://test.local/"
        every { AuthManager.getSelectedProfileId() } returns null
        // sendVoiceNote snapshots the data scope for its ownership check
        // (review, PR #1250). Default it to null: an unstubbed mockk call
        // used to throw and be swallowed by the runCatching callers, which
        // kept SwrCache keys unscoped in this class; returning a concrete
        // scope here enables cross-test cache hits and skips sends other
        // tests count. Scope-specific tests re-stub this with real scopes.
        every { AuthManager.currentDataScope() } returns null
        // ChatViewModel's model-switch delegate and the shared catalog store
        // both collect this flow; park them on a never-emitting state so a
        // later real-AuthManager emission cannot resume a stale
        // Main-dispatched collector between test classes.
        every { AuthManager.dataScopeFlow } returns MutableStateFlow<DataScope?>(null)
        // sendScope() reads the active server profile for its send-owner check. Isolate it from the
        // real singleton flow: a late write by another test class in the same fork would make the
        // owner stale and silently drop a queued prompt. Profile tests re-stub this.
        every { AuthManager.activeProfileId } returns MutableStateFlow<String?>(null)
        mockkObject(ProfileSwitchCoordinator)
        every { ProfileSwitchCoordinator.switched } returns mockSwitchFlow
        every { ProfileSwitchCoordinator.connectionSwitched } returns MutableSharedFlow<String>()
        every { AuthManager.isTypingEffectEnabled() } returns true
        every { AuthManager.getBusySendMode() } returns BusySendMode.CORRECT
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

        // Default send stub: generates unique IDs and invokes onSent callback
        every { HermesWsClient.send(any(), any(), any()) } answers {
            reqCount++
            val id = "req-id-$reqCount"
            sentRequestMethods += arg<String>(0) to id
            arg<((String) -> Unit)?>(2)?.invoke(id)
            id
        }
        every { HermesWsClient.sendMessage(any(), any(), any(), any()) } answers {
            reqCount++
            val id = "req-msg-$reqCount"
            arg<((String) -> Unit)?>(2)?.invoke(id)
            id
        }
        // Typed redirects bypass send(); keep this fixture off the real singleton transport.
        every { HermesWsClient.sendRedirect(any(), any(), any()) } answers {
            reqCount++
            val id = "req-redirect-$reqCount"
            sentRequestMethods += WsMethods.SESSION_REDIRECT to id
            arg<((String) -> Unit)?>(2)?.invoke(id)
            id
        }
        every { HermesWsClient.respondToServerRequest(any(), any()) } returns true
        every { HermesWsClient.respondToServerRequestError(any(), any(), any()) } returns true

        // Stub model-options so preloadModelOptions() (fired at GatewayReady) is safe.
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi
        coEvery {
            mockApi.getModelOptions(any(), any())
        } returns
            retrofit2.Response.success(
                io.wutz.apollo.data.model.ModelOptionsResponse(
                    providers =
                        listOf(
                            io.wutz.apollo.data.model.ModelProvider(
                                slug = "openai",
                                name = "OpenAI",
                                models = listOf("gpt-4o", "gpt-4o-mini"),
                            ),
                            io.wutz.apollo.data.model.ModelProvider(
                                slug = "anthropic",
                                name = "Anthropic",
                                models = listOf("claude-3-5-sonnet"),
                            ),
                        ),
                ),
            )
    }

    private fun tearDown() {
        if (::attachmentCacheDir.isInitialized) attachmentCacheDir.deleteRecursively()
        if (::attachmentFilesDir.isInitialized) attachmentFilesDir.deleteRecursively()
        unmockkAll()
    }

    /** Create a ViewModel with the fake repo injected directly. */
    fun createViewModel(
        startCleanup: Boolean = false,
        historyDispatcher: CoroutineDispatcher = testDispatcher,
        sendStore: ChatSendStore = ChatSendStore(),
        repository: ChatPersistenceRepository = fakeRepo,
        timing: ChatTimingConfig = ChatTimingConfig.TEST,
        onCompressionHistoryReplaced: (() -> Unit)? = null,
    ): ChatViewModel =
        // All injected dispatchers share the test scheduler so RPC ordering is deterministic.
        ChatViewModel(
            app,
            startCleanup,
            repository,
            fakeSlashUsageStore,
            testDispatcher,
            testDispatcher,
            historyDispatcher,
            sendStore,
            onCompressionHistoryReplacedForTest = onCompressionHistoryReplaced,
            timing = timing,
        )

    /**
     * Create ViewModel, simulate GatewayReady, feed SESSION_CREATE result,
     * and return a Pair(viewModel, sessionId).
     *
     * Request ID sequence: GatewayReady triggers loadSessions (req-id-1),
     * fetchCommandCatalog (req-id-2), then createNewSession (req-id-3).
     */
    suspend fun createViewModelWithSession(
        scope: TestScope,
        startCleanup: Boolean = false,
        historyDispatcher: CoroutineDispatcher = testDispatcher,
        sendStore: ChatSendStore = ChatSendStore(),
        repository: ChatPersistenceRepository = fakeRepo,
        timing: ChatTimingConfig = ChatTimingConfig.TEST,
    ): Pair<ChatViewModel, String> {
        val viewModel = createViewModel(startCleanup, historyDispatcher, sendStore, repository, timing)
        scope.advanceUntilIdle()

        mockConnectionStatus.value = ConnectionStatus.CONNECTED
        mockEventsFlow.emit(WsEvent.GatewayReady(null))
        scope.advanceUntilIdle()

        // Emit SESSION_CREATE result (req-id-3 — after loadSessions and fetchCommandCatalog)
        mockEventsFlow.emit(WsEvent.RpcResult("req-id-3", mapOf("session_id" to "session-123")))
        scope.advanceUntilIdle()

        // Sanity check: confirm the session was actually set
        val session = viewModel.uiState.value.currentSessionId
        checkNotNull(session) {
            "createViewModelWithSession: session was not set — " +
                "req-id-3 did not match SESSION_CREATE. " +
                "If the req sequence changed, update the RpcResult id here."
        }

        return Pair(viewModel, "session-123")
    }
}
