package io.wutz.apollo.data.voice

import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.remote.OkHttpProvider
import io.wutz.apollo.data.ws.HermesWsClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** Opens an authenticated audio WebSocket; null when no credential could be minted. */
typealias VoiceSocketOpener = suspend (path: String, listener: WebSocketListener) -> WebSocket?

/** Normal closure code (RFC 6455). */
private const val CLOSE_NORMAL = 1000

/**
 * Opens a dashboard audio socket the way the chat socket authenticates: a fresh credential per
 * socket from [HermesWsClient.mintWsTicket] (single-use ticket in gated mode, the refreshed
 * session token on loopback), the stored auth parameter name, the active profile. The URL is
 * never logged because it carries the credential.
 */
object VoiceSockets {
    suspend fun open(
        path: String,
        listener: WebSocketListener,
    ): WebSocket? =
        withContext(Dispatchers.IO) {
            val credential = HermesWsClient.mintWsTicket()?.takeIf { it.isNotBlank() } ?: return@withContext null
            val authParameter =
                runCatching { AuthManager.serverStore.getLatestState().wsAuthParam }.getOrDefault("token")
            val url =
                voiceSocketUrl(
                    endpoint = AuthManager.endpointForBuild(),
                    path = path,
                    authParameter = authParameter,
                    credential = credential,
                    profile = AuthManager.activeProfileId.value,
                )
            OkHttpProvider.websocket.newWebSocket(Request.Builder().url(url).build(), listener)
        }
}

/** Events of one speak-stream socket. Invoked on OkHttp's socket thread. */
interface SpeakStreamCallbacks {
    fun onFormat(
        sampleRate: Int,
        channels: Int,
    )

    fun onPcm(bytes: ByteArray)

    fun onEnd()

    fun onFallback()

    /** The socket could not open or closed before `end`/`fallback`. */
    fun onFailure()
}

/** One spoken reply on the speak-stream socket (an interface so the playback logic is testable). */
interface SpeakStream {
    fun open()

    fun appendText(text: String)

    fun finish()

    fun stop()
}

/**
 * Client of `/api/audio/speak-stream` for one reply: text deltas go up as they stream in,
 * PCM comes back as soon as the server finished a sentence. Text sent before the socket is
 * open is buffered and flushed in order.
 */
class SpeakStreamSession(
    private val scope: CoroutineScope,
    private val callbacks: SpeakStreamCallbacks,
    private val opener: VoiceSocketOpener = VoiceSockets::open,
) : SpeakStream {
    private val lock = Any()
    private var socket: WebSocket? = null
    private val pending = mutableListOf<String>()
    private var closed = false
    private var terminal = false

    override fun open() {
        scope.launch {
            val ws = runCatching { opener(SPEAK_STREAM_PATH, listener) }.getOrNull()
            val failed =
                synchronized(lock) {
                    when {
                        closed -> {
                            ws?.cancel()
                            false
                        }

                        ws == null -> {
                            markTerminal()
                        }

                        else -> {
                            socket = ws
                            pending.forEach(ws::send)
                            pending.clear()
                            false
                        }
                    }
                }
            if (failed) callbacks.onFailure()
        }
    }

    override fun appendText(text: String) {
        if (text.isNotEmpty()) send(SpeakStreamProtocol.textFrame(text))
    }

    override fun finish() = send(SpeakStreamProtocol.doneFrame())

    /** Barge-in or teardown: tell the server to stop synthesis and close the socket. */
    override fun stop() {
        synchronized(lock) {
            if (closed) return
            closed = true
            terminal = true
            pending.clear()
            socket?.let {
                it.send(SpeakStreamProtocol.stopFrame())
                it.close(CLOSE_NORMAL, null)
            }
            socket = null
        }
    }

    private fun send(frame: String) {
        synchronized(lock) {
            if (closed) return
            val ws = socket
            if (ws == null) pending += frame else ws.send(frame)
        }
    }

    /** True when this call made the session terminal (so callbacks fire once). */
    private fun markTerminal(): Boolean {
        if (terminal || closed) return false
        terminal = true
        return true
    }

    private val listener =
        object : WebSocketListener() {
            override fun onMessage(
                webSocket: WebSocket,
                text: String,
            ) {
                if (synchronized(lock) { closed }) return
                when (val frame = SpeakStreamProtocol.parse(text)) {
                    is SpeakStreamServerFrame.Start -> {
                        callbacks.onFormat(frame.sampleRate, frame.channels)
                    }

                    SpeakStreamServerFrame.End -> {
                        if (synchronized(lock) { markTerminal() }) callbacks.onEnd()
                    }

                    SpeakStreamServerFrame.Fallback -> {
                        if (synchronized(lock) { markTerminal() }) callbacks.onFallback()
                    }

                    SpeakStreamServerFrame.Unknown -> {}
                }
            }

            override fun onMessage(
                webSocket: WebSocket,
                bytes: ByteString,
            ) {
                if (synchronized(lock) { closed }) return
                callbacks.onPcm(bytes.toByteArray())
            }

            override fun onClosing(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) {
                webSocket.close(CLOSE_NORMAL, null)
                if (synchronized(lock) { markTerminal() }) callbacks.onFailure()
            }

            override fun onFailure(
                webSocket: WebSocket,
                t: Throwable,
                response: Response?,
            ) {
                if (synchronized(lock) { markTerminal() }) callbacks.onFailure()
            }
        }
}

/**
 * Client of `/api/audio/transcribe-stream` for one utterance. [finish] returns the final
 * transcript ("" means nothing was heard) or null when live STT failed; the caller then uploads
 * the recorded utterance to `POST /api/audio/transcribe` like the desktop client does.
 */
class TranscribeStreamSession(
    private val scope: CoroutineScope,
    private val sampleRate: Int,
    private val onPartial: (String) -> Unit,
    private val opener: VoiceSocketOpener = VoiceSockets::open,
    private val maxPendingBytes: Int = DEFAULT_MAX_PENDING_BYTES,
) {
    private val lock = Any()
    private var socket: WebSocket? = null
    private val pending = mutableListOf<ByteArray>()
    private var pendingBytes = 0
    private var closed = false
    private var endRequested = false
    private val result = CompletableDeferred<String?>()

    @Volatile
    private var failed = false

    /** True once live STT can no longer deliver a transcript. */
    val hasFailed: Boolean get() = failed

    @Volatile
    private var rejected = false

    /**
     * True when the server refused live STT (an `error` frame) or the rate is unsupported: retrying
     * with the next utterance would fail the same way, so the caller should go straight to upload.
     */
    val wasRejected: Boolean get() = rejected

    fun open() {
        if (sampleRate !in TRANSCRIBE_STREAM_SAMPLE_RATES) {
            rejected = true
            fail()
            return
        }
        scope.launch {
            val ws = runCatching { opener(TRANSCRIBE_STREAM_PATH, listener) }.getOrNull()
            synchronized(lock) {
                if (closed || ws == null) {
                    ws?.cancel()
                    fail()
                    return@launch
                }
                socket = ws
                ws.send(TranscribeStreamProtocol.startFrame(sampleRate))
                pending.forEach { ws.send(it.toByteString()) }
                pending.clear()
                pendingBytes = 0
                if (endRequested) ws.send(TranscribeStreamProtocol.endOfSpeechFrame())
            }
        }
    }

    fun push(pcm: ByteArray) {
        synchronized(lock) {
            if (closed || endRequested || result.isCompleted) return
            val ws = socket
            if (ws != null) {
                ws.send(pcm.toByteString())
            } else if (pendingBytes + pcm.size <= maxPendingBytes) {
                pending += pcm
                pendingBytes += pcm.size
            } else {
                // The socket never opened in time; the upload fallback has the full recording.
                pending.clear()
                fail()
            }
        }
    }

    /** Ends the utterance and waits up to [timeoutMs] for the final transcript. */
    suspend fun finish(timeoutMs: Long): String? {
        val deadlineResult =
            withTimeoutOrNull(timeoutMs) {
                synchronized(lock) {
                    if (!closed && !endRequested) {
                        endRequested = true
                        // Still connecting: open() sends the end marker after the buffered audio.
                        socket?.send(TranscribeStreamProtocol.endOfSpeechFrame())
                    }
                }
                result.await()
            }
        cancel()
        return deadlineResult
    }

    fun cancel() {
        synchronized(lock) {
            if (closed) return
            closed = true
            pending.clear()
            socket?.close(CLOSE_NORMAL, null)
            socket = null
        }
        fail()
    }

    private val listener =
        object : WebSocketListener() {
            override fun onMessage(
                webSocket: WebSocket,
                text: String,
            ) {
                when (val frame = TranscribeStreamProtocol.parse(text)) {
                    is TranscribeStreamServerFrame.Partial -> {
                        if (!result.isCompleted) onPartial(frame.text)
                    }

                    is TranscribeStreamServerFrame.Final -> {
                        result.complete(frame.transcript)
                    }

                    is TranscribeStreamServerFrame.Error -> {
                        if (!result.isCompleted) rejected = true
                        fail()
                    }

                    TranscribeStreamServerFrame.Unknown -> {}
                }
            }

            override fun onClosing(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) {
                webSocket.close(CLOSE_NORMAL, null)
                fail()
            }

            override fun onFailure(
                webSocket: WebSocket,
                t: Throwable,
                response: Response?,
            ) {
                fail()
            }
        }

    private fun fail() {
        if (result.complete(null)) failed = true
    }

    companion object {
        /** Ten seconds of 16 kHz mono PCM while the socket opens. */
        const val DEFAULT_MAX_PENDING_BYTES = 16_000 * 2 * 10
    }
}
