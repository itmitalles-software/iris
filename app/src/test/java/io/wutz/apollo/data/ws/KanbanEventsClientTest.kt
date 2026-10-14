package io.wutz.apollo.data.ws

import android.util.Log
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import io.wutz.apollo.data.config.ServerStore
import io.wutz.apollo.data.config.ServerStoreState
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.remote.CleartextPolicy
import io.wutz.apollo.data.remote.OkHttpProvider
import io.wutz.apollo.data.remote.ServerEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class KanbanEventsClientTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val statuses = CopyOnWriteArrayList<KanbanLiveStatus>()
    private val statusChannel = Channel<KanbanLiveStatus>(Channel.UNLIMITED)
    private val mints = AtomicInteger(0)
    private val mintChannel = Channel<Int>(Channel.UNLIMITED)

    @Before
    fun setUp() {
        prepareWsClientForTest()
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        mockkObject(HermesWsClient)
        mockkObject(AuthManager)
        val store = mockk<ServerStore>()
        every { store.getLatestState() } returns ServerStoreState(wsAuthParam = "ticket")
        every { AuthManager.serverStore } returns store
        every { AuthManager.endpointForBuild() } returns
            ServerEndpoint.parse("http://127.0.0.1:9119/", CleartextPolicy.ALLOW_WITH_WARNING)
    }

    @After
    fun tearDown() {
        scope.cancel()
        unmockkAll()
    }

    private fun mintReturns(result: WsCredentialResult) {
        coEvery { HermesWsClient.mintWsCredential() } answers {
            mintChannel.trySend(mints.incrementAndGet())
            result
        }
    }

    private fun KanbanEventsClient.open() =
        connect(scope, board = "default", onEvents = {}) { status ->
            statuses.add(status)
            statusChannel.trySend(status)
        }

    @Test
    fun `transient ticket failure keeps reconnecting instead of failing auth`() =
        runBlocking {
            mintReturns(WsCredentialResult(credential = null, retryable = true))
            val client = KanbanEventsClient()

            client.open()

            withTimeout(5_000) {
                assertEquals(KanbanLiveStatus.DISCONNECTED, statusChannel.receive())
                // The backoff (1 s) elapses and the stream mints a fresh ticket again.
                mintChannel.receive()
                assertEquals(2, mintChannel.receive())
            }
            client.disconnect()
            assertFalse(KanbanLiveStatus.AUTH_FAILED in statuses)
        }

    @Test
    fun `rejected session is terminal auth failure`() =
        runBlocking {
            mintReturns(WsCredentialResult(credential = null, retryable = false))
            val client = KanbanEventsClient()

            client.open()

            withTimeout(5_000) {
                assertEquals(KanbanLiveStatus.AUTH_FAILED, statusChannel.receive())
            }
            client.disconnect()
            assertEquals(1, mints.get())
        }

    @Test
    fun `socket created after disconnect is cancelled instead of orphaned`() =
        runBlocking {
            mintReturns(WsCredentialResult(credential = "ticket-1", retryable = false))
            val client = KanbanEventsClient()
            val socket = mockk<WebSocket>(relaxed = true)
            val created = Channel<Unit>(Channel.UNLIMITED)
            val http = mockk<OkHttpClient>()
            every { http.newWebSocket(any(), any()) } answers {
                // The owner leaves the board while the handshake is being set up.
                client.disconnect()
                created.trySend(Unit)
                socket
            }
            mockkObject(OkHttpProvider)
            every { OkHttpProvider.websocket } returns http

            client.open()

            withTimeout(5_000) { created.receive() }
            // The stale socket is cancelled right after newWebSocket returns.
            verify(timeout = 5_000) { socket.cancel() }
        }

    @Test
    fun `parses envelope with snake_case fields and cursor`() {
        val frame =
            """{"events":[{"id":7,"task_id":"t-1","run_id":"r-9","kind":"status","payload":{"status":"done"},"created_at":1710000000}],"cursor":7}"""
        val envelope = parseKanbanEventsFrame(frame)
        assertNotNull(envelope)
        assertEquals(7L, envelope!!.cursor)
        assertEquals(1, envelope.events.size)
        val event = envelope.events.first()
        assertEquals(7L, event.id)
        assertEquals("t-1", event.taskId)
        assertEquals("r-9", event.runId)
        assertEquals("status", event.kind)
        assertEquals(
            "done",
            event.payload
                ?.get("status")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(1710000000L, event.createdAt)
    }

    @Test
    fun `parses event with numeric run_id from sqlite`() {
        val frame =
            """{"events":[{"id":8,"task_id":"t-2","run_id":42,"kind":"status","payload":null,"created_at":1710000001}],"cursor":8}"""
        val envelope = parseKanbanEventsFrame(frame)
        assertNotNull(envelope)
        assertEquals(1, envelope!!.events.size)
        assertEquals("42", envelope.events.first().runId)
    }

    @Test
    fun `parses empty events batch`() {
        val envelope = parseKanbanEventsFrame("""{"events":[],"cursor":3}""")
        assertNotNull(envelope)
        assertEquals(3L, envelope!!.cursor)
        assertEquals(0, envelope.events.size)
    }

    @Test
    fun `null payload tolerated`() {
        val envelope = parseKanbanEventsFrame("""{"events":[{"id":1,"kind":"edited","payload":null}],"cursor":1}""")
        assertNotNull(envelope)
        assertNull(envelope!!.events.first().payload)
    }

    @Test
    fun `malformed frames return null`() {
        assertNull(parseKanbanEventsFrame("not json"))
        assertNull(parseKanbanEventsFrame("""{"events": 42}"""))
        assertNull(parseKanbanEventsFrame(""))
    }
}
