package io.wutz.apollo.ui.chat

import android.app.Application
import io.mockk.coEvery
import io.mockk.every
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.ws.ConnectionStatus
import io.wutz.apollo.data.ws.HermesWsClient
import io.wutz.apollo.data.ws.WsEvent
import io.wutz.apollo.ui.chat.fakes.FakeChatPersistenceRepository
import io.wutz.apollo.ui.chat.fakes.FakeSlashUsageStore
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Rule
import java.io.File

/**
 * Base of the per-feature ChatViewModel test classes: holds the [ChatViewModelHarness] rule and exposes its state
 * under the names the tests use, plus helpers shared by several feature classes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class ChatViewModelTestBase {
    @get:Rule
    val harness = ChatViewModelHarness()

    protected val testDispatcher: TestDispatcher get() = harness.testDispatcher
    protected val mockEventsFlow: MutableSharedFlow<WsEvent> get() = harness.mockEventsFlow
    protected val mockConnectionStatus: MutableStateFlow<ConnectionStatus> get() = harness.mockConnectionStatus
    protected val mockSwitchFlow: MutableSharedFlow<String> get() = harness.mockSwitchFlow
    protected val app: Application get() = harness.app
    protected var fakeRepo: FakeChatPersistenceRepository
        get() = harness.fakeRepo
        set(value) {
            harness.fakeRepo = value
        }
    protected val fakeSlashUsageStore: FakeSlashUsageStore get() = harness.fakeSlashUsageStore
    protected val attachmentCacheDir: File get() = harness.attachmentCacheDir
    protected val attachmentFilesDir: File get() = harness.attachmentFilesDir
    protected val sentRequestMethods: MutableList<Pair<String, String>> get() = harness.sentRequestMethods
    protected var reqCount: Int
        get() = harness.reqCount
        set(value) {
            harness.reqCount = value
        }

    protected fun createViewModel(
        startCleanup: Boolean = false,
        historyDispatcher: CoroutineDispatcher = testDispatcher,
        sendStore: ChatSendStore = ChatSendStore(),
        repository: ChatPersistenceRepository = fakeRepo,
    ): ChatViewModel = harness.createViewModel(startCleanup, historyDispatcher, sendStore, repository)

    protected suspend fun TestScope.createViewModelWithSession(
        startCleanup: Boolean = false,
        historyDispatcher: CoroutineDispatcher = testDispatcher,
        sendStore: ChatSendStore = ChatSendStore(),
        repository: ChatPersistenceRepository = fakeRepo,
    ): Pair<ChatViewModel, String> =
        harness.createViewModelWithSession(this, startCleanup, historyDispatcher, sendStore, repository)

    /** Resolves a UI message in English so content assertions keep checking the shown text (issue #10). */
    protected fun UiText.en(): String = EnglishTestResources.resolve(this)

    protected fun captureSends(): MutableList<Pair<String, String>> {
        val captured = mutableListOf<Pair<String, String>>()
        every { HermesWsClient.send(any(), any(), any()) } answers {
            reqCount++
            val id = "req-id-$reqCount"
            captured.add(arg<String>(0) to id)
            arg<((String) -> Unit)?>(2)?.invoke(id)
            id
        }
        return captured
    }

    protected fun stubSession456Rests(success: Boolean) {
        val mockApi = ApiClient.hermesApi
        // mapServerMessages reads AuthManager.getBaseUrl() on the SUCCESS path
        // before mapping any messages. mockkObject is spy-semantics: unstubbed
        // calls fall through to the REAL AuthManager, whose serverStore is null
        // unless another test class happened to init it earlier in the JVM
        // (the order-dependent flakiness). Stub it for determinism — same
        // pattern as E2eIntegrationTest / ApiClientTest.
        if (success) {
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
                                    message_count = 0,
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
        } else {
            // Relaxed mock returns a non-success response → NetworkResult.Failure.
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
            } returns
                retrofit2.Response.error(
                    500,
                    okhttp3.ResponseBody.create(null, "{\"detail\":\"boom\"}"),
                )
        }
    }

    protected fun stubEmptySessionRests(vararg sessionIds: String) {
        val mockApi = ApiClient.hermesApi
        every { AuthManager.getBaseUrl() } returns "http://test.local/"
        coEvery { mockApi.getSessions(any(), any(), any()) } returns
            retrofit2.Response.success(
                io.wutz.apollo.data.model.SessionListResponse(
                    sessions =
                        sessionIds.map {
                            io.wutz.apollo.data.model.SessionInfo(
                                id = it,
                                title = it,
                                message_count = 0,
                            )
                        },
                    total = sessionIds.size,
                ),
            )
        sessionIds.forEach { sessionId ->
            coEvery { mockApi.getSessionMessages(sessionId, any(), any(), any(), any()) } returns
                retrofit2.Response.success(
                    io.wutz.apollo.data.model
                        .SessionMessagesResponse(messages = emptyList()),
                )
        }
    }

    /**
     * Turn boundaries are keyed by the active profile, so these tests need one.
     * Scoped per test rather than added to setUp: a non-blank profile is also
     * appended to `session.resume` params, which the older resume/switch tests
     * assert exactly.
     */
    protected fun stubActiveProfile() {
        every { AuthManager.activeProfileId } returns MutableStateFlow<String?>("default")
    }
}
