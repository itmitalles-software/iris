package io.wutz.apollo.ui.chat

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import io.wutz.apollo.R
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.remote.CleartextPolicy
import io.wutz.apollo.data.remote.PasswordLoginClient
import io.wutz.apollo.data.remote.PasswordLoginResult
import io.wutz.apollo.data.remote.ServerEndpoint
import io.wutz.apollo.data.remote.WsTicketResult
import io.wutz.apollo.ui.authlogin.failureText
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

class ChatReloginAuthenticatorTest {
    private lateinit var server: MockWebServer
    private lateinit var authenticator: ChatReloginAuthenticator

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        mockkObject(AuthManager)
        every { AuthManager.setWsAuthParam(any()) } returns Unit
        every { AuthManager.setToken(any()) } returns Unit
        val endpoint = ServerEndpoint.parse(server.url("/").toString(), CleartextPolicy.ALLOW_WITH_WARNING)
        authenticator =
            ChatReloginAuthenticator(
                ioDispatcher = Dispatchers.IO,
                client = PasswordLoginClient(clientProvider = { OkHttpClient() }, ioDispatcher = Dispatchers.IO),
                endpoint = { endpoint },
            )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        unmockkObject(AuthManager)
    }

    @Test
    fun `successful relogin installs the fresh ticket`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ticket":"fresh"}"""))

            assertNull(authenticator.relogin("alice", "s3cret"))

            verify { AuthManager.setWsAuthParam("ticket") }
            verify { AuthManager.setToken("fresh") }
        }

    @Test
    fun `wrong password shows the localized credentials error and keeps the old token`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(401))

            assertEquals(UiText.res(R.string.auth_error_invalid_credentials), authenticator.relogin("alice", "x"))

            verify(exactly = 0) { AuthManager.setToken(any()) }
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `ticket failure after a good login is reported`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(MockResponse().setResponseCode(500))

            assertEquals(UiText.res(R.string.auth_error_ws_ticket_http, 500), authenticator.relogin("alice", "s3cret"))

            verify(exactly = 0) { AuthManager.setToken(any()) }
        }

    @Test
    fun `invalid server address is a connection error, not a crash`() =
        runTest {
            val broken =
                ChatReloginAuthenticator(
                    ioDispatcher = Dispatchers.IO,
                    endpoint = { throw IllegalArgumentException("Malformed base URL") },
                )

            assertEquals(
                UiText.res(R.string.connect_error_connection_failed, "Malformed base URL"),
                broken.relogin("alice", "s3cret"),
            )
        }

    @Test
    fun `failure texts map every result to a localized message`() {
        assertNull(PasswordLoginResult.Success.failureText())
        assertEquals(
            UiText.res(R.string.auth_error_invalid_credentials),
            PasswordLoginResult.HttpError(401).failureText(),
        )
        assertEquals(UiText.res(R.string.connect_error_403), PasswordLoginResult.HttpError(403).failureText())
        assertEquals(
            UiText.res(R.string.connect_error_http_code, 502),
            PasswordLoginResult.HttpError(502).failureText(),
        )
        assertEquals(
            UiText.res(R.string.connect_error_connection_failed, "refused"),
            PasswordLoginResult.ConnectionFailed(IOException("refused")).failureText(),
        )
        assertNull(WsTicketResult.Success("t").failureText())
        assertEquals(UiText.res(R.string.auth_error_ws_ticket_http, 401), WsTicketResult.HttpError(401).failureText())
        assertEquals(UiText.res(R.string.auth_login_msg_ws_ticket_failed), WsTicketResult.InvalidTicket.failureText())
        assertEquals(
            UiText.res(R.string.connect_error_connection_failed, ""),
            WsTicketResult.ConnectionFailed(IOException()).failureText(),
        )
    }
}
