package io.wutz.apollo.ui.chat.voice

/**
 * Tuning of [VoiceActivityDetector]. Levels are int16 RMS values and mirror the Hermes voice
 * loop (hermes-agent `tools/voice_mode.py` full-duplex constants and the desktop's
 * `lib/voice-barge-in.ts`): the trigger is the quiet noise floor times a multiplier, clamped
 * up while the reply plays so speaker bleed alone cannot trip it, and capped so speech always
 * stays reachable.
 */
data class VadConfig(
    val frameMs: Int = 20,
    /** Lowest speech trigger (desktop `MIN_TRIGGER_LEVEL` 0.075 ≈ int16 RMS 800). */
    val minTriggerRms: Double = 800.0,
    val floorMultiplier: Double = 3.5,
    /** While the reply plays (`PLAYBACK_MIN_TRIGGER`). */
    val playbackMinTriggerRms: Double = 1_500.0,
    /** Upper bound of the trigger while the reply plays (`TRIGGER_CEILING`). */
    val triggerCeilingRms: Double = 4_000.0,
    /** Sustained-speech window and the share of its frames that must be above the trigger. */
    val sustainedMs: Int = 300,
    val sustainedMajority: Double = 0.8,
    /** Quiet frames needed before the floor is trusted. */
    val calibrationMs: Int = 200,
    val floorSampleCap: Int = 150,
    /** Silence after speech that ends an utterance. */
    val endSilenceMs: Int = 1_600,
    /** Speech level relative to the trigger that still counts as speaking (hysteresis). */
    val endLevelFactor: Double = 0.7,
    val maxUtteranceMs: Int = 60_000,
    /** Listening without any speech for this long ends the conversation. */
    val noSpeechTimeoutMs: Int = 60_000,
    /** Barge-in is suppressed right after playback starts (start transient). */
    val playbackGraceMs: Int = 500,
    val playbackGapForGraceMs: Int = 1_000,
)

/** What the detector reports for a frame. */
enum class VadSignal {
    /** Sustained speech started while listening. */
    SPEECH_START,

    /** The utterance ended (silence after speech, or the length cap). */
    SPEECH_END,

    /** Nobody spoke for [VadConfig.noSpeechTimeoutMs]. */
    NO_SPEECH_TIMEOUT,

    /** Sustained speech while the reply plays. The detector is now in a running utterance. */
    BARGE_IN,
}

/**
 * Energy-based voice activity detection for the hands-free loop, one call per fixed-size frame.
 * Time is counted in frames, so the detector is deterministic and testable without a clock.
 *
 * Modes: [Mode.IDLE] only tracks the noise floor, [Mode.LISTEN] finds the start and end of an
 * utterance, [Mode.BARGE] watches for the user talking over the reply. The noise floor is learned
 * from quiet frames only and held while audio plays (calibrating on speaker bleed would make the
 * trigger unreachable).
 */
class VoiceActivityDetector(
    private val config: VadConfig = VadConfig(),
) {
    enum class Mode { IDLE, LISTEN, BARGE }

    var mode: Mode = Mode.IDLE
        private set

    /** True while an utterance is running (after SPEECH_START or BARGE_IN). */
    var inSpeech: Boolean = false
        private set

    private val sustainedFrames = (config.sustainedMs / config.frameMs).coerceAtLeast(1)
    private val window = ArrayDeque<Boolean>()
    private val floorSamples = ArrayDeque<Double>()
    private var quietFloor = 0.0
    private var calibratedFrames = 0
    private var framesInMode = 0
    private var speechFrames = 0
    private var quietFrames = 0
    private var graceFrames = 0
    private var wasPlaying = false
    private var framesSincePlaying = Int.MAX_VALUE
    private var playbackSeen = false

    /** Switches mode. [alreadySpeaking] continues a running utterance (after a barge-in). */
    fun arm(
        mode: Mode,
        alreadySpeaking: Boolean = false,
    ) {
        val keepSpeech = mode == Mode.LISTEN && alreadySpeaking && inSpeech
        this.mode = mode
        window.clear()
        framesInMode = 0
        if (!keepSpeech) {
            inSpeech = false
            speechFrames = 0
            quietFrames = 0
        }
    }

    /** Current trigger level for the given playback state. */
    fun trigger(playing: Boolean): Double {
        val base = maxOf(config.minTriggerRms, quietFloor * config.floorMultiplier)
        return if (playing) {
            base.coerceAtLeast(config.playbackMinTriggerRms).coerceAtMost(config.triggerCeilingRms)
        } else {
            base
        }
    }

    /** Processes one frame with level [rms]; [playing] is true while reply audio is audible. */
    fun onFrame(
        rms: Double,
        playing: Boolean,
    ): VadSignal? {
        trackPlayback(playing)
        val trigger = trigger(playing)
        // Calibrate on every quiet-phase frame at first, then only on frames below the trigger.
        if (!inSpeech && !playing && (rms < trigger || !calibrated())) addFloorSample(rms)
        framesInMode++
        return when (mode) {
            Mode.IDLE -> null
            Mode.LISTEN -> if (inSpeech) continueUtterance(rms, playing) else awaitSpeech(rms, trigger)
            Mode.BARGE -> awaitBargeIn(rms, trigger)
        }
    }

    private fun awaitSpeech(
        rms: Double,
        trigger: Double,
    ): VadSignal? {
        if (sustained(rms >= trigger && calibrated())) {
            startUtterance()
            return VadSignal.SPEECH_START
        }
        if (framesInMode * config.frameMs >= config.noSpeechTimeoutMs) {
            mode = Mode.IDLE
            return VadSignal.NO_SPEECH_TIMEOUT
        }
        return null
    }

    private fun awaitBargeIn(
        rms: Double,
        trigger: Double,
    ): VadSignal? {
        val above = rms >= trigger && calibrated() && graceFrames == 0
        if (sustained(above)) {
            mode = Mode.LISTEN
            framesInMode = 0
            startUtterance()
            return VadSignal.BARGE_IN
        }
        return null
    }

    private fun continueUtterance(
        rms: Double,
        playing: Boolean,
    ): VadSignal? {
        speechFrames++
        if (rms >= trigger(playing) * config.endLevelFactor) quietFrames = 0 else quietFrames++
        val ended =
            quietFrames * config.frameMs >= config.endSilenceMs ||
                speechFrames * config.frameMs >= config.maxUtteranceMs
        if (!ended) return null
        inSpeech = false
        mode = Mode.IDLE
        return VadSignal.SPEECH_END
    }

    private fun startUtterance() {
        inSpeech = true
        speechFrames = 0
        quietFrames = 0
        window.clear()
    }

    /** Windowed majority: the last [VadConfig.sustainedMs] must be mostly above the trigger. */
    private fun sustained(above: Boolean): Boolean {
        window.addLast(above)
        while (window.size > sustainedFrames) window.removeFirst()
        if (!above || window.size < sustainedFrames) return false
        return window.count { it } >= sustainedFrames * config.sustainedMajority
    }

    private fun calibrated(): Boolean = calibratedFrames * config.frameMs >= config.calibrationMs

    private fun addFloorSample(rms: Double) {
        floorSamples.addLast(rms)
        while (floorSamples.size > config.floorSampleCap) floorSamples.removeFirst()
        quietFloor = floorSamples.sorted()[floorSamples.size / 2]
        calibratedFrames++
    }

    private fun trackPlayback(playing: Boolean) {
        if (playing && !wasPlaying) {
            // Grace only after a real gap, so sentence gaps cannot chain grace windows.
            if (!playbackSeen || framesSincePlaying * config.frameMs >= config.playbackGapForGraceMs) {
                graceFrames = config.playbackGraceMs / config.frameMs
            }
            playbackSeen = true
        }
        if (graceFrames > 0) graceFrames--
        wasPlaying = playing
        framesSincePlaying = if (playing) 0 else framesSincePlaying.saturatingIncrement()
    }

    private fun Int.saturatingIncrement(): Int = if (this == Int.MAX_VALUE) this else this + 1
}
