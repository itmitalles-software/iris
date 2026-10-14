package io.wutz.apollo.ui.chat.voice

import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/** Pure 16-bit PCM helpers for the voice conversation (no Android dependencies). */
object PcmAudio {
    /** Root mean square of the first [count] samples, in int16 units (0..32767). */
    fun rms(
        samples: ShortArray,
        count: Int = samples.size,
    ): Double {
        val n = count.coerceIn(0, samples.size)
        if (n == 0) return 0.0
        var sum = 0.0
        for (i in 0 until n) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / n)
    }

    /** Little-endian (s16le) bytes of the first [count] samples, the wire format of both audio sockets. */
    fun toLittleEndian(
        samples: ShortArray,
        count: Int = samples.size,
    ): ByteArray {
        val n = count.coerceIn(0, samples.size)
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = samples[i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** A complete mono 16-bit PCM WAV file, accepted by `POST /api/audio/transcribe` as `audio/wav`. */
    fun wav(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int = 1,
    ): ByteArray {
        val byteRate = sampleRate * channels * BYTES_PER_SAMPLE
        val out = ByteArrayOutputStream(WAV_HEADER_BYTES + pcm.size)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.writeIntLe(36 + pcm.size)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.writeIntLe(16)
        out.writeShortLe(1) // PCM
        out.writeShortLe(channels)
        out.writeIntLe(sampleRate)
        out.writeIntLe(byteRate)
        out.writeShortLe(channels * BYTES_PER_SAMPLE)
        out.writeShortLe(16)
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.writeIntLe(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    /** UI meter level 0..1 for an int16 RMS value. */
    fun meterLevel(rms: Double): Float = (rms / METER_FULL_SCALE_RMS).toFloat().coerceIn(0f, 1f)

    const val BYTES_PER_SAMPLE = 2
    const val WAV_HEADER_BYTES = 44
    private const val METER_FULL_SCALE_RMS = 6_000.0

    private fun ByteArrayOutputStream.writeIntLe(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeShortLe(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }
}

/**
 * Re-aligns PCM byte chunks to whole frames. Provider chunks on the speak-stream socket are not
 * sample-aligned (the desktop client carries the odd byte over the same way), and AudioTrack
 * needs whole frames.
 */
class PcmFrameAligner(
    private val frameBytes: Int,
) {
    init {
        require(frameBytes > 0) { "frameBytes must be positive" }
    }

    private var carry = ByteArray(0)

    /** Returns the aligned part of [carry] + [chunk]; the remainder waits for the next chunk. */
    fun align(chunk: ByteArray): ByteArray {
        val joined = if (carry.isEmpty()) chunk else carry + chunk
        val usable = joined.size - joined.size % frameBytes
        carry = joined.copyOfRange(usable, joined.size)
        return if (usable == joined.size) joined else joined.copyOfRange(0, usable)
    }

    fun reset() {
        carry = ByteArray(0)
    }
}

/**
 * Collects the PCM of one utterance. A ring of recent frames (the pre-roll) is kept while
 * nobody is speaking, so the first syllables before the detector triggered are not lost.
 */
class UtteranceBuffer(
    private val preRollFrames: Int,
    private val maxBytes: Int,
) {
    private val preRoll = ArrayDeque<ByteArray>()
    private var utterance: ByteArrayOutputStream? = null

    val isCapturing: Boolean get() = utterance != null

    /** Adds a frame to the pre-roll or the running utterance. Returns false once [maxBytes] is reached. */
    fun add(frame: ByteArray): Boolean {
        val current = utterance
        if (current == null) {
            preRoll.addLast(frame)
            while (preRoll.size > preRollFrames) preRoll.removeFirst()
            return true
        }
        if (current.size() + frame.size > maxBytes) return false
        current.write(frame)
        return true
    }

    /** Starts the utterance with the pre-roll; returns the pre-roll frames so a live stream can catch up. */
    fun begin(): List<ByteArray> {
        val frames = preRoll.toList()
        preRoll.clear()
        utterance = ByteArrayOutputStream().also { out -> frames.forEach { out.write(it) } }
        return frames
    }

    /** Ends the utterance and returns its PCM (empty when nothing was captured). */
    fun finish(): ByteArray {
        val bytes = utterance?.toByteArray() ?: ByteArray(0)
        utterance = null
        return bytes
    }

    fun clear() {
        preRoll.clear()
        utterance = null
    }
}
