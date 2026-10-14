package io.wutz.apollo.ui.chat.voice

/** Visible phase of the hands-free voice conversation. */
enum class VoicePhase {
    OFF,

    /** Checking the server's voice configuration and opening the microphone. */
    STARTING,

    /** Microphone open, waiting for (or recording) the user's utterance. */
    LISTENING,

    /** The utterance ended; waiting for the transcript. */
    TRANSCRIBING,

    /** The transcript was sent; waiting for the reply's first audio. */
    THINKING,

    /** Reply audio is playing; speaking or tapping interrupts it. */
    SPEAKING,
}

/** Which agent turn the conversation is speaking. */
enum class ReplyTurn {
    /** Not tracking a turn: reply events are ignored (also after a barge-in). */
    NONE,

    /** A transcript was sent; the next `message.start` is our reply. */
    AWAITING_START,

    /** The reply streams; deltas go to the speech stream. */
    STREAMING,

    /** The reply text is complete; the speech stream drains. */
    COMPLETE,
}

/** User-facing outcomes the conversation reports. Mapped to localized text by the UI layer. */
sealed interface VoiceNotice {
    data object SttDisabled : VoiceNotice

    data object MicrophoneUnavailable : VoiceNotice

    data object NothingHeard : VoiceNotice

    data object NotConnected : VoiceNotice

    data object ConnectionLost : VoiceNotice

    data object SpeechOutputFailed : VoiceNotice

    data object TranscriptUnsent : VoiceNotice

    data class TranscriptionFailed(
        val detail: String?,
    ) : VoiceNotice
}

/** Inputs of [VoiceConversationMachine]. */
sealed interface VoiceEvent {
    data object Start : VoiceEvent

    data object Stop : VoiceEvent

    data class CapabilitiesResolved(
        val sttAvailable: Boolean,
        val liveTranscription: Boolean,
    ) : VoiceEvent

    data object SpeechStarted : VoiceEvent

    data object SpeechEnded : VoiceEvent

    data object NoSpeechTimeout : VoiceEvent

    /** Sustained speech while reply audio plays; the engine already captures the utterance. */
    data object BargeIn : VoiceEvent

    /** Tap on the conversation panel: send now while listening, interrupt while thinking/speaking. */
    data object Tap : VoiceEvent

    data class Transcribed(
        val text: String,
    ) : VoiceEvent

    data class TranscriptionFailed(
        val detail: String?,
    ) : VoiceEvent

    data class SubmitRejected(
        val text: String,
    ) : VoiceEvent

    data object ReplyStarted : VoiceEvent

    data class ReplyDelta(
        val text: String,
    ) : VoiceEvent

    data class ReplyCompleted(
        val text: String,
        val failed: Boolean,
    ) : VoiceEvent

    /** First reply audio is audible. */
    data object AudioStarted : VoiceEvent

    /** The speech stream finished playing (only after [VoiceEffect.FinishSpeech]). */
    data object SpeechFinished : VoiceEvent

    /** Speech output failed (only after [VoiceEffect.FinishSpeech]); the reply stays as text. */
    data object SpeechFailed : VoiceEvent

    data class Fatal(
        val notice: VoiceNotice,
    ) : VoiceEvent
}

/** Side effects the controller runs for the machine. */
sealed interface VoiceEffect {
    data object ResolveCapabilities : VoiceEffect

    /** Open the microphone (and warm up the server's STT/TTS). */
    data object Acquire : VoiceEffect

    /** Listen for the next utterance; [alreadySpeaking] keeps a barge-in utterance running. */
    data class Listen(
        val alreadySpeaking: Boolean,
    ) : VoiceEffect

    /** Watch the microphone for the user talking over the reply. */
    data object WatchForBargeIn : VoiceEffect

    /** Stop capturing and transcribe; the engine answers Transcribed or TranscriptionFailed. */
    data object EndUtterance : VoiceEffect

    data class Submit(
        val text: String,
        val interruptRunningTurn: Boolean,
    ) : VoiceEffect

    data object OpenSpeech : VoiceEffect

    data class AppendSpeech(
        val text: String,
    ) : VoiceEffect

    /** No more reply text; the engine answers SpeechFinished or SpeechFailed once audio drained. */
    data object FinishSpeech : VoiceEffect

    /** Barge-in: cut playback now and close the speech stream. */
    data object CancelSpeech : VoiceEffect

    data class Notify(
        val notice: VoiceNotice,
    ) : VoiceEffect

    data class RestoreTranscript(
        val text: String,
    ) : VoiceEffect

    /** Close microphone, sockets and players. */
    data object Release : VoiceEffect
}

data class VoiceMachineState(
    val phase: VoicePhase = VoicePhase.OFF,
    val heardSpeech: Boolean = false,
    val turn: ReplyTurn = ReplyTurn.NONE,
    val speechOpen: Boolean = false,
    val replyHadText: Boolean = false,
    /** The last barge-in cut into a reply that was still generating. */
    val bargedIntoRunningTurn: Boolean = false,
    val liveTranscription: Boolean = false,
    val emptyTranscripts: Int = 0,
    val failedTranscriptions: Int = 0,
    val speechFailureReported: Boolean = false,
)

data class VoiceTransition(
    val state: VoiceMachineState,
    val effects: List<VoiceEffect> = emptyList(),
)

/**
 * Pure state machine of the hands-free loop: listen → transcribe → send → speak the streamed
 * reply → listen again, with barge-in and clean shutdown. No Android or coroutine dependencies;
 * [VoiceConversationController] feeds it events and runs the returned effects.
 */
object VoiceConversationMachine {
    /** Consecutive empty transcripts (noise, no words) before the conversation ends. */
    const val MAX_EMPTY_TRANSCRIPTS = 3

    /** Consecutive failed transcriptions before the conversation ends. */
    const val MAX_FAILED_TRANSCRIPTIONS = 2

    fun reduce(
        state: VoiceMachineState,
        event: VoiceEvent,
    ): VoiceTransition {
        if (event is VoiceEvent.Stop) return shutdown(notice = null)
        if (event is VoiceEvent.Fatal) return if (state.phase == VoicePhase.OFF) same(state) else shutdown(event.notice)
        return when (state.phase) {
            VoicePhase.OFF -> off(state, event)
            VoicePhase.STARTING -> starting(state, event)
            VoicePhase.LISTENING -> listening(state, event)
            VoicePhase.TRANSCRIBING -> transcribing(state, event)
            VoicePhase.THINKING, VoicePhase.SPEAKING -> replying(state, event)
        }
    }

    private fun off(
        state: VoiceMachineState,
        event: VoiceEvent,
    ): VoiceTransition =
        if (event is VoiceEvent.Start) {
            VoiceTransition(VoiceMachineState(phase = VoicePhase.STARTING), listOf(VoiceEffect.ResolveCapabilities))
        } else {
            same(state)
        }

    private fun starting(
        state: VoiceMachineState,
        event: VoiceEvent,
    ): VoiceTransition =
        when (event) {
            is VoiceEvent.CapabilitiesResolved -> {
                if (!event.sttAvailable) {
                    shutdown(VoiceNotice.SttDisabled)
                } else {
                    VoiceTransition(
                        state.copy(phase = VoicePhase.LISTENING, liveTranscription = event.liveTranscription),
                        listOf(VoiceEffect.Acquire, VoiceEffect.Listen(alreadySpeaking = false)),
                    )
                }
            }

            else -> {
                same(state)
            }
        }

    private fun listening(
        state: VoiceMachineState,
        event: VoiceEvent,
    ): VoiceTransition =
        when (event) {
            VoiceEvent.SpeechStarted -> {
                same(state.copy(heardSpeech = true))
            }

            VoiceEvent.SpeechEnded -> {
                endUtterance(state)
            }

            VoiceEvent.Tap -> {
                if (state.heardSpeech) endUtterance(state) else same(state)
            }

            VoiceEvent.NoSpeechTimeout -> {
                shutdown(VoiceNotice.NothingHeard)
            }

            else -> {
                same(state)
            }
        }

    private fun endUtterance(state: VoiceMachineState): VoiceTransition =
        VoiceTransition(
            state.copy(phase = VoicePhase.TRANSCRIBING, heardSpeech = false),
            listOf(VoiceEffect.EndUtterance),
        )

    private fun transcribing(
        state: VoiceMachineState,
        event: VoiceEvent,
    ): VoiceTransition =
        when (event) {
            is VoiceEvent.Transcribed -> {
                val text = event.text.trim()
                if (text.isEmpty()) {
                    val empties = state.emptyTranscripts + 1
                    if (empties >= MAX_EMPTY_TRANSCRIPTS) {
                        shutdown(VoiceNotice.NothingHeard)
                    } else {
                        listenAgain(state.copy(emptyTranscripts = empties, failedTranscriptions = 0))
                    }
                } else {
                    VoiceTransition(
                        state.copy(
                            phase = VoicePhase.THINKING,
                            turn = ReplyTurn.AWAITING_START,
                            speechOpen = false,
                            replyHadText = false,
                            bargedIntoRunningTurn = false,
                            emptyTranscripts = 0,
                            failedTranscriptions = 0,
                        ),
                        listOf(VoiceEffect.Submit(text, interruptRunningTurn = state.bargedIntoRunningTurn)),
                    )
                }
            }

            is VoiceEvent.TranscriptionFailed -> {
                val failures = state.failedTranscriptions + 1
                if (failures >= MAX_FAILED_TRANSCRIPTIONS) {
                    shutdown(VoiceNotice.TranscriptionFailed(event.detail))
                } else {
                    val next = listenAgain(state.copy(failedTranscriptions = failures))
                    next.copy(
                        effects =
                            listOf(VoiceEffect.Notify(VoiceNotice.TranscriptionFailed(event.detail))) + next.effects,
                    )
                }
            }

            else -> {
                same(state)
            }
        }

    private fun replying(
        state: VoiceMachineState,
        event: VoiceEvent,
    ): VoiceTransition =
        when (event) {
            is VoiceEvent.SubmitRejected -> {
                val result = shutdown(VoiceNotice.TranscriptUnsent)
                result.copy(effects = listOf(VoiceEffect.RestoreTranscript(event.text)) + result.effects)
            }

            VoiceEvent.ReplyStarted -> {
                if (state.turn == ReplyTurn.AWAITING_START) {
                    VoiceTransition(
                        state.copy(turn = ReplyTurn.STREAMING, speechOpen = true),
                        listOf(VoiceEffect.OpenSpeech),
                    )
                } else {
                    same(state)
                }
            }

            is VoiceEvent.ReplyDelta -> {
                if (state.turn == ReplyTurn.STREAMING && event.text.isNotEmpty()) {
                    VoiceTransition(state.copy(replyHadText = true), listOf(VoiceEffect.AppendSpeech(event.text)))
                } else {
                    same(state)
                }
            }

            is VoiceEvent.ReplyCompleted -> {
                replyCompleted(state, event)
            }

            VoiceEvent.AudioStarted -> {
                if (state.speechOpen && state.phase == VoicePhase.THINKING) {
                    VoiceTransition(state.copy(phase = VoicePhase.SPEAKING), listOf(VoiceEffect.WatchForBargeIn))
                } else {
                    same(state)
                }
            }

            VoiceEvent.SpeechFinished -> {
                if (state.speechOpen) listenAgain(state) else same(state)
            }

            VoiceEvent.SpeechFailed -> {
                if (!state.speechOpen) {
                    same(state)
                } else {
                    val next = listenAgain(state.copy(speechFailureReported = true))
                    if (state.speechFailureReported) {
                        next
                    } else {
                        next.copy(effects = listOf(VoiceEffect.Notify(VoiceNotice.SpeechOutputFailed)) + next.effects)
                    }
                }
            }

            VoiceEvent.BargeIn -> {
                if (state.phase == VoicePhase.SPEAKING) bargeIn(state, alreadySpeaking = true) else same(state)
            }

            VoiceEvent.Tap -> {
                bargeIn(state, alreadySpeaking = false)
            }

            else -> {
                same(state)
            }
        }

    private fun replyCompleted(
        state: VoiceMachineState,
        event: VoiceEvent.ReplyCompleted,
    ): VoiceTransition {
        if (state.turn != ReplyTurn.STREAMING && state.turn != ReplyTurn.AWAITING_START) return same(state)
        if (event.failed) {
            val cancel = if (state.speechOpen) listOf(VoiceEffect.CancelSpeech) else emptyList()
            val next = listenAgain(state)
            return next.copy(effects = cancel + next.effects)
        }
        val effects = mutableListOf<VoiceEffect>()
        if (!state.speechOpen) effects += VoiceEffect.OpenSpeech
        // A reply without deltas (non-streaming provider) is spoken from the complete text.
        val completeText = event.text.takeIf { !state.replyHadText && it.isNotBlank() }
        if (completeText != null) effects += VoiceEffect.AppendSpeech(completeText)
        if (!state.replyHadText && completeText == null) {
            // Nothing to say (tool-only or empty reply): close the stream and listen again.
            val next = listenAgain(state)
            val cancel = if (state.speechOpen) listOf(VoiceEffect.CancelSpeech) else emptyList()
            return next.copy(effects = cancel + next.effects)
        }
        effects += VoiceEffect.FinishSpeech
        return VoiceTransition(
            state.copy(turn = ReplyTurn.COMPLETE, speechOpen = true, replyHadText = true),
            effects,
        )
    }

    private fun bargeIn(
        state: VoiceMachineState,
        alreadySpeaking: Boolean,
    ): VoiceTransition {
        val effects = mutableListOf<VoiceEffect>()
        if (state.speechOpen) effects += VoiceEffect.CancelSpeech
        effects += VoiceEffect.Listen(alreadySpeaking)
        val running = state.turn == ReplyTurn.STREAMING || state.turn == ReplyTurn.AWAITING_START
        return VoiceTransition(
            state.copy(
                phase = VoicePhase.LISTENING,
                heardSpeech = alreadySpeaking,
                turn = ReplyTurn.NONE,
                speechOpen = false,
                replyHadText = false,
                bargedIntoRunningTurn = running,
            ),
            effects,
        )
    }

    private fun listenAgain(state: VoiceMachineState): VoiceTransition =
        VoiceTransition(
            state.copy(
                phase = VoicePhase.LISTENING,
                heardSpeech = false,
                turn = ReplyTurn.NONE,
                speechOpen = false,
                replyHadText = false,
            ),
            listOf(VoiceEffect.Listen(alreadySpeaking = false)),
        )

    private fun shutdown(notice: VoiceNotice?): VoiceTransition =
        VoiceTransition(
            VoiceMachineState(),
            listOfNotNull(VoiceEffect.Release, notice?.let { VoiceEffect.Notify(it) }),
        )

    private fun same(state: VoiceMachineState) = VoiceTransition(state)
}
