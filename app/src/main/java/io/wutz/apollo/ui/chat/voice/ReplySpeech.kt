package io.wutz.apollo.ui.chat.voice

import io.wutz.apollo.data.voice.SPEAK_STREAM_DEFAULT_SAMPLE_RATE
import io.wutz.apollo.data.voice.SpeakStream
import io.wutz.apollo.data.voice.SpeakStreamCallbacks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Playback target for streamed PCM. Implementations are thread-safe. */
interface PcmSink {
    fun write(bytes: ByteArray)

    /** No more PCM: play out what is buffered, then call the drained callback. */
    fun finish()

    /** Cut playback immediately; the drained callback is not called. */
    fun stop()

    /** True while written audio has not been played yet. */
    fun isAudible(): Boolean
}

/**
 * Speaks one agent reply: deltas go to the speak-stream socket while the reply generates, PCM
 * plays as it arrives. When the socket yields no audio (`fallback` frame, or it failed before the
 * first PCM) the whole reply is spoken through [relaySpeaker] (`POST /api/audio/speak`) instead,
 * the same last resort the desktop client uses. Exactly one of [Listener.onFinished] or
 * [Listener.onFailed] is reported, and only after [finish]; [cancel] (barge-in) reports nothing.
 */
class ReplySpeech(
    private val scope: CoroutineScope,
    private val listener: Listener,
    streamFactory: (SpeakStreamCallbacks) -> SpeakStream,
    private val sinkFactory: (sampleRate: Int, channels: Int, onDrained: () -> Unit) -> PcmSink,
    private val relaySpeaker: suspend (text: String, onPlaying: () -> Unit) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val stallTimeoutMs: Long = DEFAULT_STALL_TIMEOUT_MS,
) {
    interface Listener {
        fun onAudioStarted()

        fun onFinished()

        fun onFailed()
    }

    private enum class Next { NOTHING, RELAY, FINISHED }

    private val lock = Any()
    private val text = StringBuilder()
    private var sink: PcmSink? = null
    private var finishRequested = false
    private var audioStarted = false
    private var drained = false
    private var streamEnded = false
    private var streamFailed = false
    private var fallbackRequested = false
    private var relayStarted = false
    private var relayJob: Job? = null
    private var watchdog: Job? = null
    private var settled = false
    private var cancelled = false
    private var lastProgressAt = 0L

    @Volatile
    private var relayPlaying = false

    private val stream = streamFactory(StreamCallbacks())

    fun open() = stream.open()

    fun append(delta: String) {
        if (delta.isEmpty()) return
        synchronized(lock) {
            if (cancelled || settled || finishRequested) return
            text.append(delta)
        }
        stream.appendText(delta)
    }

    fun finish() {
        synchronized(lock) {
            if (cancelled || settled || finishRequested) return
            finishRequested = true
            lastProgressAt = clock()
        }
        stream.finish()
        watchdog = scope.launch { watchForStall() }
        evaluate()
    }

    fun cancel() {
        val (currentSink, job) =
            synchronized(lock) {
                if (cancelled) return
                cancelled = true
                sink to relayJob
            }
        stream.stop()
        currentSink?.stop()
        job?.cancel()
        watchdog?.cancel()
        relayPlaying = false
    }

    fun isAudible(): Boolean = relayPlaying || synchronized(lock) { sink }?.isAudible() == true

    private suspend fun watchForStall() {
        while (true) {
            delay(STALL_POLL_MS)
            val stalled =
                synchronized(lock) {
                    if (cancelled || settled || relayStarted) return
                    !streamEnded && !streamFailed && clock() - lastProgressAt >= stallTimeoutMs
                }
            if (stalled) {
                // The server stopped answering: cut to what played, or speak the text via the relay.
                val s =
                    synchronized(lock) {
                        streamFailed = true
                        sink.takeIf { audioStarted }
                    }
                stream.stop()
                s?.finish()
                evaluate()
                return
            }
        }
    }

    private fun onDrained() {
        synchronized(lock) { drained = true }
        evaluate()
    }

    private fun evaluate() {
        val next =
            synchronized(lock) {
                when {
                    cancelled || settled || !finishRequested || relayStarted -> {
                        Next.NOTHING
                    }

                    fallbackRequested || (streamFailed && !audioStarted) -> {
                        relayStarted = true
                        Next.RELAY
                    }

                    audioStarted && drained -> {
                        Next.FINISHED
                    }

                    !audioStarted && streamEnded -> {
                        Next.FINISHED
                    }

                    else -> {
                        Next.NOTHING
                    }
                }
            }
        when (next) {
            Next.NOTHING -> {}

            Next.FINISHED -> {
                settle(ok = true)
            }

            Next.RELAY -> {
                startRelay()
            }
        }
    }

    private fun startRelay() {
        val replyText = synchronized(lock) { text.toString() }
        stream.stop()
        synchronized(lock) { sink }?.stop()
        val job =
            scope.launch {
                val ok =
                    replyText.isBlank() ||
                        try {
                            relaySpeaker(replyText) {
                                relayPlaying = true
                                scope.launch { listener.onAudioStarted() }
                            }
                        } finally {
                            relayPlaying = false
                        }
                settle(ok)
            }
        synchronized(lock) { relayJob = job }
    }

    private fun settle(ok: Boolean) {
        synchronized(lock) {
            if (settled || cancelled) return
            settled = true
        }
        watchdog?.cancel()
        stream.stop()
        scope.launch { if (ok) listener.onFinished() else listener.onFailed() }
    }

    private inner class StreamCallbacks : SpeakStreamCallbacks {
        override fun onFormat(
            sampleRate: Int,
            channels: Int,
        ) {
            synchronized(lock) {
                if (cancelled || settled || sink != null) return
                lastProgressAt = clock()
                sink = sinkFactory(sampleRate, channels, ::onDrained)
            }
        }

        override fun onPcm(bytes: ByteArray) {
            var first = false
            val target =
                synchronized(lock) {
                    if (cancelled || settled || relayStarted) return
                    lastProgressAt = clock()
                    first = !audioStarted
                    audioStarted = true
                    sink ?: sinkFactory(SPEAK_STREAM_DEFAULT_SAMPLE_RATE, 1, ::onDrained).also { sink = it }
                }
            target.write(bytes)
            if (first) scope.launch { listener.onAudioStarted() }
        }

        override fun onEnd() {
            val target =
                synchronized(lock) {
                    streamEnded = true
                    lastProgressAt = clock()
                    sink.takeIf { audioStarted }
                }
            if (target != null) target.finish() else synchronized(lock) { drained = true }
            evaluate()
        }

        override fun onFallback() {
            synchronized(lock) { fallbackRequested = true }
            evaluate()
        }

        override fun onFailure() {
            val target =
                synchronized(lock) {
                    streamFailed = true
                    sink.takeIf { audioStarted && !streamEnded }
                }
            target?.finish()
            evaluate()
        }
    }

    companion object {
        /** No answer from the server for this long after the reply ended counts as a stall. */
        const val DEFAULT_STALL_TIMEOUT_MS = 20_000L
        private const val STALL_POLL_MS = 1_000L
    }
}
