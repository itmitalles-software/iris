package io.wutz.apollo.ui.chat.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceActivityDetectorTest {
    private val config = VadConfig()
    private val frameMs = config.frameMs

    private fun VoiceActivityDetector.feed(
        rms: Double,
        ms: Int,
        playing: Boolean = false,
    ): List<VadSignal> = (0 until ms / frameMs).mapNotNull { onFrame(rms, playing) }

    private fun calibrated(): VoiceActivityDetector =
        VoiceActivityDetector(config).apply {
            feed(QUIET, 400)
            arm(VoiceActivityDetector.Mode.LISTEN)
        }

    @Test
    fun `sustained speech starts an utterance and silence ends it`() {
        val vad = calibrated()
        assertTrue(vad.feed(SPEECH, 200).isEmpty())
        assertEquals(listOf(VadSignal.SPEECH_START), vad.feed(SPEECH, 200))
        assertTrue(vad.inSpeech)
        assertTrue(vad.feed(SPEECH, 2_000).isEmpty())
        assertTrue(vad.feed(QUIET, config.endSilenceMs - frameMs).isEmpty())
        assertEquals(listOf(VadSignal.SPEECH_END), vad.feed(QUIET, frameMs))
        assertFalse(vad.inSpeech)
        assertEquals(VoiceActivityDetector.Mode.IDLE, vad.mode)
    }

    @Test
    fun `short clicks and dips inside words do not start or end an utterance`() {
        val vad = calibrated()
        assertTrue(vad.feed(SPEECH, 100).isEmpty())
        assertTrue(vad.feed(QUIET, 400).isEmpty())
        assertFalse(vad.inSpeech)

        assertEquals(listOf(VadSignal.SPEECH_START), vad.feed(SPEECH, 400))
        // A 400 ms pause between words is shorter than the end-of-utterance silence.
        assertTrue(vad.feed(QUIET, 400).isEmpty())
        assertTrue(vad.feed(SPEECH, 400).isEmpty())
        assertTrue(vad.inSpeech)
    }

    @Test
    fun `talking during calibration is learned as noise until the room is quiet again`() {
        val vad = VoiceActivityDetector(config)
        vad.arm(VoiceActivityDetector.Mode.LISTEN)
        assertTrue(vad.feed(SPEECH, 1_000).isEmpty())
        assertTrue(vad.feed(QUIET, 2_000).isEmpty())
        assertEquals(listOf(VadSignal.SPEECH_START), vad.feed(SPEECH, 400))
    }

    @Test
    fun `the trigger follows a noisy room`() {
        val vad = VoiceActivityDetector(config)
        vad.feed(600.0, 1_000)
        assertEquals(600.0 * config.floorMultiplier, vad.trigger(playing = false), 0.001)
        vad.arm(VoiceActivityDetector.Mode.LISTEN)
        assertTrue(vad.feed(1_500.0, 1_000).isEmpty())
    }

    @Test
    fun `no speech for the timeout reports it once`() {
        val vad = calibrated()
        val signals = vad.feed(QUIET, config.noSpeechTimeoutMs + 1_000)
        assertEquals(listOf(VadSignal.NO_SPEECH_TIMEOUT), signals)
    }

    @Test
    fun `utterances are capped`() {
        val vad = calibrated()
        val signals = vad.feed(SPEECH, config.maxUtteranceMs + 1_000)
        assertEquals(listOf(VadSignal.SPEECH_START, VadSignal.SPEECH_END), signals)
    }

    @Test
    fun `speaker bleed during playback cannot trip barge-in, real speech can`() {
        val vad = calibrated()
        vad.arm(VoiceActivityDetector.Mode.BARGE)
        assertTrue(vad.feed(BLEED, 3_000, playing = true).isEmpty())
        assertEquals(config.playbackMinTriggerRms, vad.trigger(playing = true), 0.001)

        assertEquals(listOf(VadSignal.BARGE_IN), vad.feed(SPEECH, 400, playing = true))
        assertTrue(vad.inSpeech)
        assertEquals(VoiceActivityDetector.Mode.LISTEN, vad.mode)
    }

    @Test
    fun `barge-in is suppressed right after playback starts`() {
        val vad = calibrated()
        vad.arm(VoiceActivityDetector.Mode.BARGE)
        assertTrue(vad.feed(SPEECH, config.playbackGraceMs - frameMs, playing = true).isEmpty())
        assertEquals(listOf(VadSignal.BARGE_IN), vad.feed(SPEECH, 400, playing = true))
    }

    @Test
    fun `the playback trigger is capped so loud rooms can still interrupt`() {
        val vad = VoiceActivityDetector(config)
        vad.feed(2_000.0, 1_000)
        assertEquals(config.triggerCeilingRms, vad.trigger(playing = true), 0.001)
    }

    @Test
    fun `listen continues a barge-in utterance when asked to`() {
        val vad = calibrated()
        vad.arm(VoiceActivityDetector.Mode.BARGE)
        vad.feed(QUIET, 600, playing = true)
        assertEquals(listOf(VadSignal.BARGE_IN), vad.feed(SPEECH, 400, playing = true))
        vad.arm(VoiceActivityDetector.Mode.LISTEN, alreadySpeaking = true)
        assertTrue(vad.inSpeech)
        assertEquals(listOf(VadSignal.SPEECH_END), vad.feed(QUIET, config.endSilenceMs))

        vad.arm(VoiceActivityDetector.Mode.LISTEN, alreadySpeaking = true)
        assertFalse(vad.inSpeech)
    }

    @Test
    fun `idle mode reports nothing`() {
        val vad = calibrated()
        vad.arm(VoiceActivityDetector.Mode.IDLE)
        assertNull(vad.onFrame(SPEECH, playing = false))
        assertTrue(vad.feed(SPEECH, 2_000).isEmpty())
    }

    private companion object {
        const val QUIET = 120.0
        const val BLEED = 1_200.0
        const val SPEECH = 3_000.0
    }
}
