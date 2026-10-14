package io.wutz.apollo.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class PasswordLoginClientTest {
    private lateinit var server: MockWebServer
    private lateinit var endpoint: ServerEndpoint
    private val jar = InMemoryCookieJar()
    private val http =
        OkHttpClient
            .Builder()
            .cookieJar(jar)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    private val client = PasswordLoginClient(clientProvider = { http }, ioDispatcher = Dispatchers.IO)

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        endpoint = ServerEndpoint.parse(server.url("/").toString(), CleartextPolicy.ALLOW_WITH_WARNING)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `login 200 succeeds and posts the password payload`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

            val result = client.login(endpoint, "alice", "s3cret", "basic")

            assertEquals(PasswordLoginResult.Success, result)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/auth/password-login", request.path)
            assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"username\":\"alice\""), body)
            assertTrue(body.contains("\"password\":\"s3cret\""), body)
            assertTrue(body.contains("\"provider\":\"basic\""), body)
        }

    @Test
    fun `login 401 and 403 report the status code`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(403))

            assertEquals(PasswordLoginResult.HttpError(401), client.login(endpoint, "alice", "wrong"))
            assertEquals(PasswordLoginResult.HttpError(403), client.login(endpoint, "alice", "wrong"))
        }

    @Test
    fun `login without an HTTP answer is a connection failure`() =
        runTest {
            server.shutdown()

            val result = client.login(endpoint, "alice", "s3cret")

            assertTrue(result is PasswordLoginResult.ConnectionFailed, "$result")
        }

    @Test
    fun `ticket request carries the session cookie set by the login`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Set-Cookie", "hermes_session=abc; Path=/; HttpOnly"),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ticket":"t-1","ttl_seconds":30}"""))

            assertEquals(PasswordLoginResult.Success, client.login(endpoint, "alice", "s3cret"))
            assertEquals(WsTicketResult.Success("t-1"), client.mintTicket(endpoint))

            server.takeRequest()
            val ticketRequest = server.takeRequest()
            assertEquals("/api/auth/ws-ticket", ticketRequest.path)
            assertEquals("POST", ticketRequest.method)
            assertEquals("hermes_session=abc", ticketRequest.getHeader("Cookie"))
        }

    @Test
    fun `ticket with escaped characters is decoded, not cut at the quote`() =
        runTest {
            // Wire body: {"ticket":"a\"b\\c"} decodes to a"b\c.
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ticket":"a\"b\\c","ttl_seconds":30}"""))

            assertEquals(WsTicketResult.Success("a\"b\\c"), client.mintTicket(endpoint))
        }

    @Test
    fun `empty, blank or missing ticket is invalid`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ticket":""}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ticket":"   "}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ttl_seconds":30}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))

            repeat(4) {
                assertEquals(WsTicketResult.InvalidTicket, client.mintTicket(endpoint))
            }
        }

    @Test
    fun `ticket HTTP error reports the status code`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(401))

            assertEquals(WsTicketResult.HttpError(401), client.mintTicket(endpoint))
        }

    @Test
    fun `ticket request without an HTTP answer is a connection failure`() =
        runTest {
            server.shutdown()

            assertTrue(client.mintTicket(endpoint) is WsTicketResult.ConnectionFailed)
        }

    @Test
    fun `parseWsTicket returns the decoded ticket or null`() {
        assertEquals("abc", PasswordLoginClient.parseWsTicket("""{"ticket":"abc"}"""))
        assertEquals("abc", PasswordLoginClient.parseWsTicket("""{"ticket":"abc","extra":1}"""))
        assertNull(PasswordLoginClient.parseWsTicket("""{"ticket":null}"""))
        assertNull(PasswordLoginClient.parseWsTicket(""))
    }

    private class InMemoryCookieJar : CookieJar {
        private val cookies = mutableListOf<Cookie>()

        override fun saveFromResponse(
            url: HttpUrl,
            cookies: List<Cookie>,
        ) {
            this.cookies += cookies
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) }
    }
}
