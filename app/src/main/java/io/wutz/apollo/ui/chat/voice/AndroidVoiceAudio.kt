package io.wutz.apollo.ui.chat.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import java.util.concurrent.LinkedBlockingQueue

private const val TAG = "VoiceAudio"

/** Audio attributes of spoken replies: assistant speech on the media stream. */
internal fun voiceReplyAudioAttributes(): AudioAttributes =
    AudioAttributes
        .Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

/**
 * Streams s16le PCM to an [AudioTrack] from a dedicated writer thread, so a blocking write never
 * stalls the WebSocket reader (OkHttp would miss its pong deadline on a long reply).
 */
class AudioTrackPcmSink(
    private val sampleRate: Int,
    channels: Int,
    private val onDrained: () -> Unit,
) : PcmSink {
    private val frameBytes = PcmAudio.BYTES_PER_SAMPLE * channels
    private val aligner = PcmFrameAligner(frameBytes)
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val track: AudioTrack
    private val bufferBytes: Int

    @Volatile
    private var stopped = false

    @Volatile
    private var released = false

    @Volatile
    private var framesWritten = 0L

    @Volatile
    private var playing = false

    init {
        val mask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        bufferBytes = maxOf(minBuffer, sampleRate * frameBytes / 4)
        track =
            AudioTrack
                .Builder()
                .setAudioAttributes(voiceReplyAudioAttributes())
                .setAudioFormat(
                    AudioFormat
                        .Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(mask)
                        .build(),
                ).setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        Thread(::writeLoop, "apollo-voice-playback").apply { isDaemon = true }.start()
    }

    override fun write(bytes: ByteArray) {
        if (!stopped && bytes.isNotEmpty()) queue.put(bytes)
    }

    override fun finish() {
        queue.put(END)
    }

    override fun stop() {
        if (stopped) return
        stopped = true
        queue.clear()
        queue.put(END)
        runCatching {
            track.pause()
            track.flush()
        }
    }

    override fun isAudible(): Boolean {
        if (stopped || released || !playing) return false
        return try {
            framesWritten > track.playbackHeadPosition.toLong()
        } catch (_: IllegalStateException) {
            false
        }
    }

    private fun writeLoop() {
        var drainedNormally = false
        try {
            while (!stopped) {
                val chunk = queue.take()
                if (chunk === END) break
                val aligned = aligner.align(chunk)
                if (aligned.isEmpty()) continue
                if (!playing) {
                    track.play()
                    playing = true
                }
                if (!writeFully(aligned)) break
                framesWritten += aligned.size / frameBytes
            }
            if (!stopped) {
                drain()
                drainedNormally = !stopped
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Reply playback failed", e)
            drainedNormally = !stopped
        } finally {
            released = true
            runCatching {
                track.pause()
                track.flush()
            }
            track.release()
        }
        if (drainedNormally) onDrained()
    }

    private fun writeFully(bytes: ByteArray): Boolean {
        var offset = 0
        while (offset < bytes.size && !stopped) {
            val written = track.write(bytes, offset, bytes.size - offset)
            if (written <= 0) return false
            offset += written
        }
        return !stopped
    }

    /** Plays out the buffered tail: pad with silence so short audio passes the start threshold. */
    private fun drain() {
        if (!playing || framesWritten == 0L) return
        writeFully(ByteArray(bufferBytes - bufferBytes % frameBytes))
        val target = framesWritten
        val remainingMs = (target - track.playbackHeadPosition.toLong()).coerceAtLeast(0) * 1_000 / sampleRate
        val deadline = SystemClock.elapsedRealtime() + remainingMs + DRAIN_SLACK_MS
        while (!stopped && track.playbackHeadPosition.toLong() < target && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(DRAIN_POLL_MS)
        }
    }

    private companion object {
        val END = ByteArray(0)
        const val DRAIN_SLACK_MS = 2_000L
        const val DRAIN_POLL_MS = 20L
    }
}

/**
 * Reads 20 ms mono PCM frames from the microphone on a dedicated thread. Uses the
 * voice-communication source with the platform echo canceler when available, so the spoken
 * reply is less likely to trigger barge-in on its own.
 */
class MicCapture(
    private val context: Context,
    private val onFrame: (samples: ShortArray, count: Int) -> Unit,
    private val onError: () -> Unit,
) {
    var sampleRate: Int = 0
        private set

    @Volatile
    private var running = false
    private var record: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var thread: Thread? = null

    /** Starts capturing; false when the permission is missing or no configuration works. */
    fun start(): Boolean {
        if (running) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        var created: Pair<AudioRecord, Int>? = null
        for (candidate in SAMPLE_RATES) {
            created = createRecord(candidate)
            if (created != null) break
        }
        val (audioRecord, rate) = created ?: return false
        try {
            audioRecord.startRecording()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Microphone start failed", e)
            audioRecord.release()
            return false
        } catch (e: SecurityException) {
            Log.w(TAG, "Microphone permission revoked", e)
            audioRecord.release()
            return false
        }
        record = audioRecord
        sampleRate = rate
        attachEffects(audioRecord.audioSessionId)
        running = true
        thread =
            Thread({ readLoop(audioRecord, rate / FRAMES_PER_SECOND) }, "apollo-voice-mic")
                .apply { isDaemon = true }
                .also { it.start() }
        return true
    }

    fun stop() {
        running = false
        record?.let { audioRecord -> runCatching { audioRecord.stop() } }
        // Release only after the reader left AudioRecord.read (at most one 20 ms frame).
        thread?.let { reader ->
            runCatching { reader.join(READER_JOIN_MS) }
            reader.interrupt()
        }
        thread = null
        record?.release()
        record = null
        echoCanceler?.release()
        echoCanceler = null
        noiseSuppressor?.release()
        noiseSuppressor = null
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createRecord(rate: Int): Pair<AudioRecord, Int>? {
        val minBuffer =
            AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return null
        return try {
            val audioRecord =
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, rate * PcmAudio.BYTES_PER_SAMPLE / 5),
                )
            if (audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord to rate
            } else {
                audioRecord.release()
                null
            }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Microphone rejected $rate Hz", e)
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "Microphone permission missing", e)
            null
        }
    }

    private fun attachEffects(sessionId: Int) {
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = runCatching { AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } }.getOrNull()
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = runCatching { NoiseSuppressor.create(sessionId)?.apply { enabled = true } }.getOrNull()
        }
    }

    private fun readLoop(
        audioRecord: AudioRecord,
        frameSamples: Int,
    ) {
        val buffer = ShortArray(frameSamples)
        while (running) {
            var filled = 0
            while (filled < frameSamples && running) {
                val read = audioRecord.read(buffer, filled, frameSamples - filled)
                if (read < 0) {
                    if (running) {
                        Log.w(TAG, "Microphone read failed: $read")
                        running = false
                        onError()
                    }
                    return
                }
                filled += read
            }
            if (running && filled == frameSamples) onFrame(buffer, filled)
        }
    }

    companion object {
        /** 20 ms frames. */
        const val FRAMES_PER_SECOND = 50

        private const val READER_JOIN_MS = 250L

        /** 16 kHz suits STT best; the others are fallbacks for unusual microphones. */
        private val SAMPLE_RATES = listOf(16_000, 48_000, 44_100)
    }
}
