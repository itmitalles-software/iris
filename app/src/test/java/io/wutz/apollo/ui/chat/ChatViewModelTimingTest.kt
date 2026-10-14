package io.wutz.apollo.ui.chat

import io.mockk.every
import io.mockk.justRun
import io.wutz.apollo.data.ws.ConnectionStatus
import io.wutz.apollo.data.ws.HermesWsClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production timers of [ChatViewModel] (connect spinner, session-create loading, outgoing-request expiry),
 * driven with virtual time through an injected [ChatTimingConfig].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTimingTest : ChatViewModelTestBase() {
    private val noTimers = ChatTimingConfig.TEST

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun stubReconnectableSocket() {
        justRun { HermesWsClient.rejectAllPending(any()) }
        every { HermesWsClient.disconnect() } answers {
            mockConnectionStatus.value = ConnectionStatus.DISCONNECTED
        }
    }

    @Test
    fun connectSpinnerClearsAfterTheConfiguredTimeoutWhenTheSocketHangs() =
        runTest(testDispatcher) {
            stubReconnectableSocket()
            val viewModel =
                harness.createViewModel(timing = noTimers.copy(connectSpinnerTimeoutMs = 10_000L))
            runCurrent()

            viewModel.reconnect()
            advance(600L)
            assertEquals(ConnectionStatus.CONNECTING, mockConnectionStatus.value)
            assertTrue(viewModel.uiState.value.isLoading)

            advance(9_000L)
            assertTrue(viewModel.uiState.value.isLoading)

            advance(1_000L)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun connectSpinnerStaysWithoutTheTimer() =
        runTest(testDispatcher) {
            stubReconnectableSocket()
            val viewModel = harness.createViewModel(timing = noTimers)
            runCurrent()

            viewModel.reconnect()
            advance(60_000L)

            assertTrue(viewModel.uiState.value.isLoading)
        }

    @Test
    fun sessionCreateLoadingClearsAfterTheConfiguredTimeoutWithoutAResponse() =
        runTest(testDispatcher) {
            val (viewModel, _) =
                harness.createViewModelWithSession(
                    this,
                    timing = noTimers.copy(sessionCreateLoadingTimeoutMs = 10_000L),
                )

            viewModel.createNewSession()
            advance(9_999L)
            assertTrue(viewModel.uiState.value.isLoading)

            advance(2L)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun unansweredPromptBecomesUnknownAfterTheExpiry() =
        runTest(testDispatcher) {
            val (viewModel, sessionId) =
                harness.createViewModelWithSession(
                    this,
                    timing = noTimers.copy(outgoingRequestExpiryMs = 30_000L),
                )
            val requestId = "never-answered"
            every { HermesWsClient.sendMessage(sessionId, "Hello?", any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke(requestId)
                requestId
            }

            viewModel.sendMessage("Hello?")
            advance(1L)
            assertEquals(
                PendingSendState.SENDING,
                viewModel.uiState.value.pendingSends
                    .single()
                    .state,
            )

            advance(29_000L)
            assertEquals(
                PendingSendState.SENDING,
                viewModel.uiState.value.pendingSends
                    .single()
                    .state,
            )

            advance(1_000L)
            assertEquals(
                PendingSendState.UNKNOWN,
                viewModel.uiState.value.pendingSends
                    .single()
                    .state,
            )
        }

    @Test
    fun unansweredPromptStaysSendingWithoutTheExpiry() =
        runTest(testDispatcher) {
            val (viewModel, sessionId) = harness.createViewModelWithSession(this, timing = noTimers)
            every { HermesWsClient.sendMessage(sessionId, "Hello?", any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("never-answered")
                "never-answered"
            }

            viewModel.sendMessage("Hello?")
            advance(120_000L)

            assertEquals(
                PendingSendState.SENDING,
                viewModel.uiState.value.pendingSends
                    .single()
                    .state,
            )
        }
}
