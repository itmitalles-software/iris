package io.wutz.apollo.ui.chat.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmAudioTest {
    @Test
    fun `rms of a square wave is its amplitude`() {
        val samples = ShortArray(320) { if (it % 2 == 0) 1_000 else -1_000 }
        assertEquals(1_000.0, PcmAudio.rms(samples), 0.001)
        assertEquals(0.0, PcmAudio.rms(ShortArray(0)), 0.0)
        assertEquals(1_000.0, PcmAudio.rms(samples, count = 2), 0.001)
    }

    @Test
    fun `samples are encoded as s16le`() {
        val bytes = PcmAudio.toLittleEndian(shortArrayOf(1, -2, 0x1234, Short.MIN_VALUE))
        assertArrayEquals(
            byteArrayOf(0x01, 0x00, 0xFE.toByte(), 0xFF.toByte(), 0x34, 0x12, 0x00, 0x80.toByte()),
            bytes,
        )
        assertEquals(2, PcmAudio.toLittleEndian(shortArrayOf(1, 2, 3), count = 1).size)
    }

    @Test
    fun `wav header describes mono 16-bit PCM`() {
        val pcm = ByteArray(640) { it.toByte() }
        val wav = PcmAudio.wav(pcm, sampleRate = 16_000)
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(PcmAudio.WAV_HEADER_BYTES + pcm.size, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + pcm.size, header.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals(1, header.getShort(20).toInt())
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(16_000, header.getInt(24))
        assertEquals(32_000, header.getInt(28))
        assertEquals(2, header.getShort(32).toInt())
        assertEquals(16, header.getShort(34).toInt())
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(pcm.size, header.getInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }

    @Test
    fun `meter level is clamped`() {
        assertEquals(0f, PcmAudio.meterLevel(0.0), 0f)
        assertEquals(1f, PcmAudio.meterLevel(100_000.0), 0f)
    }

    @Test
    fun `aligner carries odd bytes into the next chunk`() {
        val aligner = PcmFrameAligner(frameBytes = 2)
        assertArrayEquals(byteArrayOf(1, 2), aligner.align(byteArrayOf(1, 2, 3)))
        assertArrayEquals(byteArrayOf(3, 4, 5, 6), aligner.align(byteArrayOf(4, 5, 6)))
        assertArrayEquals(byteArrayOf(), aligner.align(byteArrayOf(7)))
        aligner.reset()
        assertArrayEquals(byteArrayOf(8, 9), aligner.align(byteArrayOf(8, 9)))
    }

    @Test
    fun `utterance keeps the pre-roll and caps its size`() {
        val buffer = UtteranceBuffer(preRollFrames = 2, maxBytes = 6)
        listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)).forEach { assertTrue(buffer.add(it)) }
        assertFalse(buffer.isCapturing)

        val preRoll = buffer.begin()
        assertEquals(listOf(2.toByte(), 3.toByte()), preRoll.map { it.single() })
        assertTrue(buffer.isCapturing)
        assertTrue(buffer.add(byteArrayOf(4, 5)))
        assertFalse(buffer.add(byteArrayOf(6, 7, 8)))
        assertArrayEquals(byteArrayOf(2, 3, 4, 5), buffer.finish())
        assertFalse(buffer.isCapturing)
        assertArrayEquals(byteArrayOf(), buffer.finish())
    }
}
