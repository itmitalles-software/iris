package io.wutz.apollo.data.ws

import io.wutz.apollo.data.remote.mockSystemServerTrust
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/*
 * Isolation helpers for tests that drive the real singleton [HermesWsClient].
 * Without them a class only passes when an earlier test happened to build
 * OkHttpProvider's clients, and a handshake still running after tearDown can
 * hit the next test's mocks (UncaughtExceptionsBeforeTest).
 */

/** First call in `@Before`: the socket client needs a trust manager, which the app sets up in `Application.onCreate`. */
internal fun prepareWsClientForTest() = mockSystemServerTrust()

/** Call in `@After` right after `disconnect()` and before `unmockkAll()`. */
internal fun awaitWsHandshakeForTest() {
    runBlocking { withTimeout(5_000) { HermesWsClient.awaitOpenSocketForTest() } }
}
