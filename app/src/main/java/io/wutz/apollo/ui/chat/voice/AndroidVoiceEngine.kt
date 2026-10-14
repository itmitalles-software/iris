package io.wutz.apollo.ui.chat.voice

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.model.AudioLeaseRequest
import io.wutz.apollo.data.model.VoiceCapabilities
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.NetworkResult
import io.wutz.apollo.data.repository.VoiceNoteRepository
import io.wutz.apollo.data.voice.SpeakStreamSession
import io.wutz.apollo.data.voice.TranscribeStreamSession
import io.wutz.apollo.ui.chat.components.AndroidSpeechPlayer
import io.wutz.apollo.ui.chat.components.AndroidSpeechSynthesizer
import io.wutz.apollo.ui.chat.components.SpeechAudio
import io.wutz.apollo.ui.chat.components.SpeechRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.resume

private const val TAG = "VoiceEngine"

/**
 * Android [VoiceEngine]: microphone + [VoiceActivityDetector] on the capture thread, live STT over
 * `/api/audio/transcribe-stream` with the `POST /api/audio/transcribe` upload as fallback, and
 * [ReplySpeech] for the streamed answer. Only official Hermes dashboard endpoints are used.
 */
class AndroidVoiceEngine(
    context: Context,
    private val listener: VoiceEngineListener,
    private val voiceNotes: VoiceNoteRepository = VoiceNoteRepository(),
) : VoiceEngine {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val leaseId = "apollo:voice-conversation:${UUID.randomUUID()}"
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    // Capture state, guarded by [lock]: touched by the mic thread and the main thread.
    private val lock = Any()
    private val vad = VoiceActivityDetector()
    private var utterance: UtteranceBuffer? = null
    private var liveStream: TranscribeStreamSession? = null
    private var liveTranscription = false
    private var released = false
    private var frameCount = 0

    private var mic: MicCapture? = null
    private var focusRequest: AudioFocusRequest? = null
    private var leasesHeld = false

    @Volatile
    private var speech: ReplySpeech? = null

    override suspend fun resolveCapabilities(): VoiceCapabilities =
        withContext(Dispatchers.IO) {
            try {
                val response = ApiClient.hermesApi.getVoiceConfig()
                if (response.isSuccessful) VoiceCapabilities.from(response.body()) else VoiceCapabilities.UNKNOWN
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "voice-config unavailable: ${e.javaClass.simpleName}")
                VoiceCapabilities.UNKNOWN
            }
        }

    override fun acquire(liveTranscription: Boolean): Boolean {
        val capture =
            MicCapture(
                context = appContext,
                onFrame = ::onFrame,
                onError = listener::onMicrophoneFailed,
            )
        synchronized(lock) {
            if (released) return false
            this.liveTranscription = liveTranscription
        }
        if (!capture.start()) return false
        synchronized(lock) {
            utterance =
                UtteranceBuffer(preRollFrames = PRE_ROLL_FRAMES, maxBytes = capture.sampleRate * 2 * MAX_SECONDS)
        }
        mic = capture
        requestAudioFocus()
        setLeases(active = true)
        return true
    }

    override fun listen(alreadySpeaking: Boolean) {
        synchronized(lock) {
            if (released) return
            val buffer = utterance ?: return
            if (alreadySpeaking && buffer.isCapturing) {
                vad.arm(VoiceActivityDetector.Mode.LISTEN, alreadySpeaking = true)
                return
            }
            buffer.finish()
            liveStream?.cancel()
            liveStream = null
            vad.arm(VoiceActivityDetector.Mode.LISTEN)
        }
    }

    override fun watchForBargeIn() {
        synchronized(lock) {
            if (released || utterance?.isCapturing == true) return
            vad.arm(VoiceActivityDetector.Mode.BARGE)
        }
    }

    override fun endUtterance() {
        val rate = mic?.sampleRate ?: 0
        val (pcm, stream) =
            synchronized(lock) {
                vad.arm(VoiceActivityDetector.Mode.IDLE)
                val bytes = utterance?.finish() ?: ByteArray(0)
                val live = liveStream
                liveStream = null
                bytes to live
            }
        scope.launch {
            if (pcm.isEmpty() || rate <= 0) {
                stream?.cancel()
                listener.onTranscribed("")
                return@launch
            }
            val live = stream?.takeUnless { it.hasFailed }?.finish(LIVE_FINAL_TIMEOUT_MS)
            stream?.cancel()
            if (live != null) {
                listener.onTranscribed(live)
                return@launch
            }
            val result = withContext(Dispatchers.IO) { voiceNotes.transcribe(PcmAudio.wav(pcm, rate), WAV_MIME_TYPE) }
            when (result) {
                is NetworkResult.Success -> listener.onTranscribed(result.data)
                is NetworkResult.Failure -> listener.onTranscriptionFailed(result.error.message)
            }
        }
    }

    override fun openSpeech() {
        speech?.cancel()
        speech =
            ReplySpeech(
                scope = scope,
                listener =
                    object : ReplySpeech.Listener {
                        override fun onAudioStarted() = listener.onAudioStarted()

                        override fun onFinished() = listener.onSpeechFinished()

                        override fun onFailed() = listener.onSpeechFailed()
                    },
                streamFactory = { callbacks -> SpeakStreamSession(scope, callbacks) },
                sinkFactory = ::createSink,
                relaySpeaker = ::speakViaRelay,
            ).also { it.open() }
    }

    override fun appendSpeech(text: String) {
        speech?.append(text)
    }

    override fun finishSpeech() {
        speech?.finish()
    }

    override fun cancelSpeech() {
        speech?.cancel()
        speech = null
    }

    override fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            vad.arm(VoiceActivityDetector.Mode.IDLE)
            utterance?.clear()
            utterance = null
            liveStream?.cancel()
            liveStream = null
        }
        mic?.stop()
        mic = null
        speech?.cancel()
        speech = null
        scope.cancel()
        abandonAudioFocus()
        if (leasesHeld) setLeases(active = false)
    }

    /** Capture thread: level meter, voice activity detection and utterance capture. */
    private fun onFrame(
        samples: ShortArray,
        count: Int,
    ) {
        val rms = PcmAudio.rms(samples, count)
        val bytes = PcmAudio.toLittleEndian(samples, count)
        val playing = speech?.isAudible() == true
        var signal: VadSignal? = null
        var meter = false
        synchronized(lock) {
            if (released) return
            val buffer = utterance ?: return
            signal = vad.onFrame(rms, playing)
            if (signal == VadSignal.SPEECH_START || signal == VadSignal.BARGE_IN) beginCapture(buffer)
            if (buffer.isCapturing) {
                if (buffer.add(bytes)) liveStream?.push(bytes)
            } else {
                buffer.add(bytes)
            }
            frameCount++
            meter = frameCount % LEVEL_EVERY_FRAMES == 0
        }
        when (signal) {
            VadSignal.SPEECH_START -> {
                listener.onSpeechStarted()
            }

            VadSignal.SPEECH_END -> {
                listener.onSpeechEnded()
            }

            VadSignal.NO_SPEECH_TIMEOUT -> {
                listener.onNoSpeechTimeout()
            }

            VadSignal.BARGE_IN -> {
                listener.onBargeIn()
            }

            null -> {}
        }
        if (meter) listener.onLevel(PcmAudio.meterLevel(rms))
    }

    /** Called under [lock] when speech starts: keep the pre-roll and stream it live if possible. */
    private fun beginCapture(buffer: UtteranceBuffer) {
        val preRoll = buffer.begin()
        liveStream?.cancel()
        liveStream = null
        val rate = mic?.sampleRate ?: return
        if (!liveTranscription) return
        liveStream =
            TranscribeStreamSession(
                scope = scope,
                sampleRate = rate,
                onPartial = listener::onPartialTranscript,
            ).also { stream ->
                stream.open()
                preRoll.forEach(stream::push)
            }
    }

    private fun createSink(
        sampleRate: Int,
        channels: Int,
        onDrained: () -> Unit,
    ): PcmSink =
        try {
            AudioTrackPcmSink(sampleRate, channels, onDrained)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unsupported reply audio format ($sampleRate Hz, $channels ch)", e)
            FailedPcmSink(onDrained)
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "Reply audio track unavailable", e)
            FailedPcmSink(onDrained)
        }

    /** Last resort: synthesize the whole reply via `POST /api/audio/speak` and play it with ExoPlayer. */
    private suspend fun speakViaRelay(
        text: String,
        onPlaying: () -> Unit,
    ): Boolean {
        val synthesizer =
            AndroidSpeechSynthesizer(appContext, ApiClient.hermesApi, AuthManager.activeProfileId.value)
        val audio: SpeechAudio =
            try {
                synthesizer.synthesize(SpeechRequest(scopeKey = leaseId, messageId = leaseId, text = text))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Relay speech failed: ${e.javaClass.simpleName}")
                return false
            }
        return withContext(Dispatchers.Main) {
            val player = AndroidSpeechPlayer(appContext)
            try {
                onPlaying()
                suspendCancellableCoroutine { continuation ->
                    player.play(
                        audio = audio,
                        onEnded = { if (continuation.isActive) continuation.resume(true) },
                        onError = { if (continuation.isActive) continuation.resume(false) },
                    )
                }
            } finally {
                player.stop()
                audio.dispose()
            }
        }
    }

    private fun requestAudioFocus() {
        val manager = audioManager ?: return
        val request =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(voiceReplyAudioAttributes())
                .build()
        if (manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focusRequest = request
    }

    private fun abandonAudioFocus() {
        val request = focusRequest ?: return
        audioManager?.abandonAudioFocusRequest(request)
        focusRequest = null
    }

    /**
     * Warm-up / release of the server's STT and TTS (`/api/audio/stt-lease`, `/api/audio/tts-lease`),
     * so a local model is loaded before the first utterance. Failures only cost the warm-up.
     */
    private fun setLeases(active: Boolean) {
        leasesHeld = active
        LEASE_SCOPE.launch {
            val body = AudioLeaseRequest(lease = leaseId, active = active)
            runCatching { ApiClient.hermesApi.setSttLease(body) }
            runCatching { ApiClient.hermesApi.setTtsLease(body) }
        }
    }

    private class FailedPcmSink(
        private val onDrained: () -> Unit,
    ) : PcmSink {
        override fun write(bytes: ByteArray) = Unit

        override fun finish() = onDrained()

        override fun stop() = Unit

        override fun isAudible(): Boolean = false
    }

    private companion object {
        /** 500 ms of audio before the detector triggered. */
        const val PRE_ROLL_FRAMES = 25

        /** Cap of one utterance buffer (the detector ends utterances at 60 s). */
        const val MAX_SECONDS = 65

        const val LEVEL_EVERY_FRAMES = 4
        const val LIVE_FINAL_TIMEOUT_MS = 15_000L
        const val WAV_MIME_TYPE = "audio/wav"

        /** Lease calls outlive the conversation's scope so the release still reaches the server. */
        val LEASE_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
