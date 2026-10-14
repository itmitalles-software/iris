package io.wutz.apollo.data.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Drives both audio socket clients against a fake dashboard speaking the audio.py protocol. */
class VoiceSocketSessionTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
        client.dispatcher.executorService.shutdown()
    }

    /** Server side that answers the client's close handshake. */
    private open class ClosingListener : WebSocketListener() {
        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            webSocket.close(1000, null)
        }
    }

    private val opener: VoiceSocketOpener = { path, listener ->
        client.newWebSocket(Request.Builder().url(server.url("/$path")).build(), listener)
    }

    private class RecordingSpeakCallbacks : SpeakStreamCallbacks {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val pcm = ByteArrayOutputStream()
        val terminal = CountDownLatch(1)

        override fun onFormat(
            sampleRate: Int,
            channels: Int,
        ) {
            events += "format:$sampleRate:$channels"
        }

        override fun onPcm(bytes: ByteArray) {
            synchronized(pcm) { pcm.write(bytes) }
            events += "pcm"
        }

        override fun onEnd() {
            events += "end"
            terminal.countDown()
        }

        override fun onFallback() {
            events += "fallback"
            terminal.countDown()
        }

        override fun onFailure() {
            events += "failure"
            terminal.countDown()
        }
    }

    @Test
    fun `speak stream sends buffered deltas in order and plays PCM until end`() {
        val received = Collections.synchronizedList(mutableListOf<String>())
        val audio = byteArrayOf(1, 2, 3, 4, 5, 6)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        received += text
                        if (text == SpeakStreamProtocol.doneFrame()) {
                            webSocket.send("""{"type":"start","sample_rate":16000,"channels":1}""")
                            webSocket.send(audio.toByteString())
                            webSocket.send("""{"type":"end"}""")
                            webSocket.close(1000, null)
                        }
                    }
                },
            ),
        )
        val callbacks = RecordingSpeakCallbacks()
        val session = SpeakStreamSession(scope, callbacks, opener)
        session.appendText("Hello. ")
        session.open()
        session.appendText("World.")
        session.finish()

        assertTrue(callbacks.terminal.await(5, TimeUnit.SECONDS))
        assertEquals(
            listOf(
                SpeakStreamProtocol.textFrame("Hello. "),
                SpeakStreamProtocol.textFrame("World."),
                SpeakStreamProtocol.doneFrame(),
            ),
            received.toList(),
        )
        assertEquals(listOf("format:16000:1", "pcm", "end"), callbacks.events.toList())
        assertArrayEquals(audio, synchronized(callbacks.pcm) { callbacks.pcm.toByteArray() })
    }

    @Test
    fun `speak stream reports the server's fallback verdict`() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        if (text == SpeakStreamProtocol.doneFrame()) {
                            webSocket.send("""{"type":"fallback"}""")
                            webSocket.close(1000, null)
                        }
                    }
                },
            ),
        )
        val callbacks = RecordingSpeakCallbacks()
        val session = SpeakStreamSession(scope, callbacks, opener)
        session.open()
        session.appendText("Hi.")
        session.finish()

        assertTrue(callbacks.terminal.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)
        assertEquals(listOf("fallback"), callbacks.events.toList())
    }

    @Test
    fun `barge-in sends stop and suppresses later callbacks`() {
        val received = Collections.synchronizedList(mutableListOf<String>())
        val closed = CountDownLatch(1)
        val gotText = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        received += text
                        gotText.countDown()
                    }

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        webSocket.close(1000, null)
                        closed.countDown()
                    }
                },
            ),
        )
        val callbacks = RecordingSpeakCallbacks()
        val session = SpeakStreamSession(scope, callbacks, opener)
        session.open()
        session.appendText("A long reply.")
        assertTrue(gotText.await(5, TimeUnit.SECONDS))
        session.stop()

        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertEquals(SpeakStreamProtocol.stopFrame(), received.last())
        Thread.sleep(200)
        assertTrue(callbacks.events.isEmpty())
    }

    @Test
    fun `no credential is a failure`() {
        val callbacks = RecordingSpeakCallbacks()
        SpeakStreamSession(scope, callbacks) { _, _ -> null }.open()
        assertTrue(callbacks.terminal.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("failure"), callbacks.events.toList())
    }

    @Test
    fun `server closing before end is a failure`() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        webSocket.close(1011, "boom")
                    }
                },
            ),
        )
        val callbacks = RecordingSpeakCallbacks()
        SpeakStreamSession(scope, callbacks, opener).open()
        assertTrue(callbacks.terminal.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("failure"), callbacks.events.toList())
    }

    @Test
    fun `transcribe stream announces the rate first, then audio, then eos`() =
        runBlocking {
            val texts = Collections.synchronizedList(mutableListOf<String>())
            val audio = ByteArrayOutputStream()
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            texts += text
                            if (text == TranscribeStreamProtocol.endOfSpeechFrame()) {
                                webSocket.send("""{"type":"partial","text":"hello"}""")
                                webSocket.send("""{"type":"final","transcript":"hello world","provider":"x"}""")
                                webSocket.close(1000, null)
                            }
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            bytes: ByteString,
                        ) {
                            synchronized(audio) { audio.write(bytes.toByteArray()) }
                        }
                    },
                ),
            )
            val partials = Collections.synchronizedList(mutableListOf<String>())
            val session = TranscribeStreamSession(scope, 16_000, { partials += it }, opener)
            session.push(byteArrayOf(1, 2))
            session.open()
            session.push(byteArrayOf(3, 4))

            assertEquals("hello world", session.finish(5_000))
            assertEquals(TranscribeStreamProtocol.startFrame(16_000), texts.first())
            assertEquals(TranscribeStreamProtocol.endOfSpeechFrame(), texts.last())
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), synchronized(audio) { audio.toByteArray() })
            assertEquals(listOf("hello"), partials.toList())
            assertFalse(session.hasFailed)
        }

    @Test
    fun `transcribe stream without live STT fails so the caller uploads`() =
        runBlocking {
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: okhttp3.Response,
                        ) {
                            webSocket.send("""{"type":"error","message":"no live STT for the configured provider"}""")
                            webSocket.close(1000, null)
                        }
                    },
                ),
            )
            val session = TranscribeStreamSession(scope, 16_000, {}, opener)
            session.open()
            session.push(byteArrayOf(1, 2))
            Thread.sleep(500)
            assertTrue(session.hasFailed)
            assertNull(session.finish(1_000))
        }

    @Test
    fun `transcribe stream times out to null`() =
        runBlocking {
            server.enqueue(MockResponse().withWebSocketUpgrade(ClosingListener()))
            val session = TranscribeStreamSession(scope, 16_000, {}, opener)
            session.open()
            Thread.sleep(300)
            assertNull(session.finish(300))
        }

    @Test
    fun `unsupported rates never open a socket`() =
        runBlocking {
            val session = TranscribeStreamSession(scope, 96_000, {}, { _, _ -> error("must not open") })
            session.open()
            assertTrue(session.hasFailed)
            assertNull(session.finish(100))
        }
}
