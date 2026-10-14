package io.wutz.apollo.ui.chat.voice

import io.wutz.apollo.data.voice.SpeakStream
import io.wutz.apollo.data.voice.SpeakStreamCallbacks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReplySpeechTest {
    private class FakeStream : SpeakStream {
        val calls = mutableListOf<String>()
        lateinit var callbacks: SpeakStreamCallbacks

        override fun open() {
            calls += "open"
        }

        override fun appendText(text: String) {
            calls += "text:$text"
        }

        override fun finish() {
            calls += "done"
        }

        override fun stop() {
            calls += "stop"
        }
    }

    private class FakeSink(
        val rate: Int,
        val channels: Int,
        private val onDrained: (Boolean) -> Unit,
    ) : PcmSink {
        val written = mutableListOf<ByteArray>()
        var finished = false
        var stopped = false

        override fun write(bytes: ByteArray) {
            written += bytes
        }

        override fun finish() {
            finished = true
        }

        fun drain(played: Boolean = true) = onDrained(played)

        override fun stop() {
            stopped = true
        }

        override fun isAudible(): Boolean = !finished && !stopped && written.isNotEmpty()
    }

    private class Recorder : ReplySpeech.Listener {
        val events = mutableListOf<String>()

        override fun onAudioStarted() {
            events += "audio"
        }

        override fun onFinished() {
            events += "finished"
        }

        override fun onFailed() {
            events += "failed"
        }
    }

    private val stream = FakeStream()
    private val sinks = mutableListOf<FakeSink>()
    private val listener = Recorder()
    private val relayed = mutableListOf<String>()
    private var relayResult = CompletableDeferred<Boolean>()

    private fun TestScope.speech(stallMs: Long = 20_000L) =
        ReplySpeech(
            scope = this,
            listener = listener,
            streamFactory = { callbacks ->
                stream.callbacks = callbacks
                stream
            },
            sinkFactory = { rate, channels, onDrained -> FakeSink(rate, channels, onDrained).also { sinks += it } },
            relaySpeaker = { text, onPlaying ->
                relayed += text
                onPlaying()
                relayResult.await()
            },
            clock = { testScheduler.currentTime },
            stallTimeoutMs = stallMs,
        ).also { it.open() }

    @Test
    fun `streamed reply plays PCM and finishes after the audio drained`() =
        runTest {
            val speech = speech()
            speech.append("Hello. ")
            speech.append("Bye.")
            stream.callbacks.onFormat(22_050, 1)
            stream.callbacks.onPcm(byteArrayOf(1, 2))
            runCurrent()
            assertEquals(listOf("audio"), listener.events)
            assertTrue(speech.isAudible())

            speech.finish()
            stream.callbacks.onPcm(byteArrayOf(3, 4))
            stream.callbacks.onEnd()
            val sink = sinks.single()
            assertEquals(22_050, sink.rate)
            assertTrue(sink.finished)
            runCurrent()
            assertEquals(listOf("audio"), listener.events)

            sink.drain()
            advanceUntilIdle()
            assertEquals(listOf("audio", "finished"), listener.events)
            assertEquals(listOf("open", "text:Hello. ", "text:Bye.", "done", "stop"), stream.calls)
            assertEquals(2, sink.written.size)
        }

    @Test
    fun `fallback frame speaks the whole reply via the relay`() =
        runTest {
            val speech = speech()
            speech.append("Hello ")
            speech.append("world.")
            speech.finish()
            stream.callbacks.onFallback()
            runCurrent()
            assertEquals(listOf("Hello world."), relayed)
            assertEquals(listOf("audio"), listener.events)
            assertTrue(speech.isAudible())

            relayResult.complete(true)
            advanceUntilIdle()
            assertEquals(listOf("audio", "finished"), listener.events)
            assertFalse(speech.isAudible())
        }

    @Test
    fun `socket failure before audio waits for the full text, then relays`() =
        runTest {
            val speech = speech()
            speech.append("Part one. ")
            stream.callbacks.onFailure()
            runCurrent()
            assertTrue(relayed.isEmpty())

            speech.append("Part two.")
            speech.finish()
            runCurrent()
            assertEquals(listOf("Part one. Part two."), relayed)
            relayResult.complete(false)
            advanceUntilIdle()
            assertEquals(listOf("audio", "failed"), listener.events)
        }

    @Test
    fun `socket failure after audio plays out what arrived, then reports the reply unspoken`() =
        runTest {
            val speech = speech()
            speech.append("Long reply.")
            stream.callbacks.onFormat(24_000, 1)
            stream.callbacks.onPcm(byteArrayOf(1, 2))
            stream.callbacks.onFailure()
            speech.finish()
            val sink = sinks.single()
            assertTrue(sink.finished)
            runCurrent()
            assertEquals(listOf("audio"), listener.events)

            sink.drain()
            advanceUntilIdle()
            assertTrue(relayed.isEmpty())
            assertEquals(listOf("audio", "failed"), listener.events)
        }

    @Test
    fun `a stall after audio started reports the reply unspoken`() =
        runTest {
            val speech = speech(stallMs = 5_000L)
            speech.append("Long reply.")
            stream.callbacks.onPcm(byteArrayOf(1, 2))
            speech.finish()
            advanceTimeBy(6_000L)
            runCurrent()
            val sink = sinks.single()
            assertTrue(sink.finished)
            sink.drain()
            advanceUntilIdle()
            assertTrue(relayed.isEmpty())
            assertEquals(listOf("audio", "failed"), listener.events)
        }

    @Test
    fun `a playback failure is reported, not taken for the end of the audio`() =
        runTest {
            val speech = speech()
            speech.append("Hello.")
            stream.callbacks.onPcm(byteArrayOf(1, 2))
            speech.finish()
            stream.callbacks.onEnd()
            sinks.single().drain(played = false)
            advanceUntilIdle()
            assertEquals(listOf("audio", "failed"), listener.events)
        }

    @Test
    fun `server with nothing to say finishes without audio`() =
        runTest {
            val speech = speech()
            speech.append("```code only```")
            speech.finish()
            stream.callbacks.onEnd()
            advanceUntilIdle()
            assertEquals(listOf("finished"), listener.events)
        }

    @Test
    fun `an announced format without audio never opens a player`() =
        runTest {
            val speech = speech()
            speech.append("```code only```")
            stream.callbacks.onFormat(24_000, 1)
            speech.finish()
            stream.callbacks.onEnd()
            advanceUntilIdle()
            assertTrue(sinks.isEmpty())
            assertEquals(listOf("finished"), listener.events)
        }

    @Test
    fun `the player uses the announced format once audio arrives`() =
        runTest {
            val speech = speech()
            speech.append("Hi.")
            stream.callbacks.onFormat(48_000, 2)
            assertTrue(sinks.isEmpty())
            stream.callbacks.onPcm(byteArrayOf(1, 2, 3, 4))
            val sink = sinks.single()
            assertEquals(48_000, sink.rate)
            assertEquals(2, sink.channels)
            speech.cancel()
            advanceUntilIdle()
        }

    @Test
    fun `cancel cuts playback and reports nothing`() =
        runTest {
            val speech = speech()
            speech.append("Story.")
            stream.callbacks.onFormat(24_000, 1)
            stream.callbacks.onPcm(byteArrayOf(1, 2))
            speech.cancel()
            assertTrue(sinks.single().stopped)
            assertTrue(stream.calls.contains("stop"))

            speech.finish()
            stream.callbacks.onEnd()
            advanceUntilIdle()
            assertEquals(listOf("audio"), listener.events)
        }

    @Test
    fun `cancel during the relay stops it`() =
        runTest {
            val speech = speech()
            speech.append("Text.")
            speech.finish()
            stream.callbacks.onFallback()
            runCurrent()
            speech.cancel()
            relayResult.complete(true)
            advanceUntilIdle()
            assertEquals(listOf("audio"), listener.events)
            assertFalse(speech.isAudible())
        }

    @Test
    fun `a silent server after done is a stall that falls back to the relay`() =
        runTest {
            val speech = speech(stallMs = 5_000L)
            speech.append("Are you there?")
            speech.finish()
            advanceTimeBy(6_000L)
            runCurrent()
            assertEquals(listOf("Are you there?"), relayed)
            relayResult.complete(true)
            advanceUntilIdle()
            assertEquals(listOf("audio", "finished"), listener.events)
        }

    @Test
    fun `text after finish is ignored`() =
        runTest {
            val speech = speech()
            speech.append("A.")
            speech.finish()
            speech.append("B.")
            assertEquals(listOf("open", "text:A.", "done"), stream.calls)
            speech.cancel()
            advanceUntilIdle()
        }
}
