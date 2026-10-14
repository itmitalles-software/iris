package io.wutz.apollo.ui.chat.voice

import io.wutz.apollo.data.model.VoiceCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Callbacks of a [VoiceEngine]; may be invoked from any thread. */
interface VoiceEngineListener {
    fun onSpeechStarted()

    fun onSpeechEnded()

    fun onNoSpeechTimeout()

    fun onBargeIn()

    fun onLevel(level: Float)

    fun onPartialTranscript(text: String)

    fun onTranscribed(text: String)

    fun onTranscriptionFailed(detail: String?)

    fun onAudioStarted()

    fun onSpeechFinished()

    fun onSpeechFailed()

    fun onMicrophoneFailed()
}

/**
 * Audio side of one voice conversation: microphone + voice activity detection, transcription
 * and the streamed reply speech. One instance per conversation; [release] ends it for good.
 */
interface VoiceEngine {
    suspend fun resolveCapabilities(): VoiceCapabilities

    /** Opens the microphone; false when it cannot be used. */
    fun acquire(liveTranscription: Boolean): Boolean

    fun listen(alreadySpeaking: Boolean)

    fun watchForBargeIn()

    fun endUtterance()

    fun openSpeech()

    fun appendSpeech(text: String)

    fun finishSpeech()

    fun cancelSpeech()

    fun release()
}

/** What the conversation needs from the chat. */
interface VoiceConversationHost {
    /** Sends [text] as the next user message; false when the chat cannot accept it right now. */
    fun submitTranscript(
        text: String,
        interruptRunningTurn: Boolean,
    ): Boolean

    fun restoreTranscript(text: String)

    fun showNotice(notice: VoiceNotice)
}

data class VoiceConversationUiState(
    val phase: VoicePhase = VoicePhase.OFF,
    val heardSpeech: Boolean = false,
    val level: Float = 0f,
    val partialTranscript: String = "",
) {
    val isActive: Boolean get() = phase != VoicePhase.OFF
}

/**
 * Runs [VoiceConversationMachine] against a [VoiceEngine] created per conversation. All state
 * changes happen on [scope] (the ViewModel's main scope); engine callbacks from audio and socket
 * threads are posted there and dropped when they belong to an earlier conversation.
 */
class VoiceConversationController(
    private val scope: CoroutineScope,
    private val engineFactory: (VoiceEngineListener) -> VoiceEngine,
    private val host: VoiceConversationHost,
) {
    private val _state = MutableStateFlow(VoiceConversationUiState())
    val state: StateFlow<VoiceConversationUiState> = _state.asStateFlow()

    val isActive: Boolean get() = machine.phase != VoicePhase.OFF

    private var machine = VoiceMachineState()
    private var engine: VoiceEngine? = null
    private var conversation = 0L

    fun start() {
        if (isActive) return
        conversation++
        val id = conversation
        engine = engineFactory(ConversationListener(id))
        dispatch(VoiceEvent.Start)
    }

    fun stop() {
        if (isActive || engine != null) dispatch(VoiceEvent.Stop)
    }

    /** Ends the conversation with [notice], e.g. when the chat connection dropped. */
    fun abort(notice: VoiceNotice) {
        if (isActive) dispatch(VoiceEvent.Fatal(notice))
    }

    fun tap() = dispatch(VoiceEvent.Tap)

    fun onReplyStarted() = dispatchIfActive(VoiceEvent.ReplyStarted)

    fun onReplyDelta(text: String) = dispatchIfActive(VoiceEvent.ReplyDelta(text))

    fun onReplyCompleted(
        text: String,
        failed: Boolean,
    ) = dispatchIfActive(VoiceEvent.ReplyCompleted(text, failed))

    private fun dispatchIfActive(event: VoiceEvent) {
        if (isActive) dispatch(event)
    }

    private fun dispatch(event: VoiceEvent) {
        val transition = VoiceConversationMachine.reduce(machine, event)
        machine = transition.state
        _state.update {
            val phase = transition.state.phase
            val keepPartial = phase == VoicePhase.LISTENING || phase == VoicePhase.TRANSCRIBING
            it.copy(
                phase = phase,
                heardSpeech = transition.state.heardSpeech,
                level = if (phase == VoicePhase.OFF) 0f else it.level,
                partialTranscript = if (keepPartial) it.partialTranscript else "",
            )
        }
        transition.effects.forEach(::run)
    }

    private fun run(effect: VoiceEffect) {
        val engine = engine
        when (effect) {
            VoiceEffect.ResolveCapabilities -> {
                val id = conversation
                scope.launch {
                    val capabilities = engine?.resolveCapabilities() ?: VoiceCapabilities.UNKNOWN
                    if (id == conversation) {
                        dispatch(
                            VoiceEvent.CapabilitiesResolved(capabilities.sttAvailable, capabilities.liveTranscription),
                        )
                    }
                }
            }

            VoiceEffect.Acquire -> {
                if (engine?.acquire(machine.liveTranscription) != true) {
                    dispatch(VoiceEvent.Fatal(VoiceNotice.MicrophoneUnavailable))
                }
            }

            is VoiceEffect.Listen -> {
                if (!effect.alreadySpeaking) _state.update { it.copy(partialTranscript = "") }
                engine?.listen(effect.alreadySpeaking)
            }

            VoiceEffect.WatchForBargeIn -> {
                engine?.watchForBargeIn()
            }

            VoiceEffect.EndUtterance -> {
                engine?.endUtterance()
            }

            is VoiceEffect.Submit -> {
                if (!host.submitTranscript(effect.text, effect.interruptRunningTurn)) {
                    dispatch(VoiceEvent.SubmitRejected(effect.text))
                }
            }

            VoiceEffect.OpenSpeech -> {
                engine?.openSpeech()
            }

            is VoiceEffect.AppendSpeech -> {
                engine?.appendSpeech(effect.text)
            }

            VoiceEffect.FinishSpeech -> {
                engine?.finishSpeech()
            }

            VoiceEffect.CancelSpeech -> {
                engine?.cancelSpeech()
            }

            is VoiceEffect.Notify -> {
                host.showNotice(effect.notice)
            }

            is VoiceEffect.RestoreTranscript -> {
                host.restoreTranscript(effect.text)
            }

            VoiceEffect.Release -> {
                this.engine = null
                conversation++
                engine?.release()
            }
        }
    }

    private inner class ConversationListener(
        private val id: Long,
    ) : VoiceEngineListener {
        private fun post(block: () -> Unit) {
            scope.launch { if (id == conversation) block() }
        }

        override fun onSpeechStarted() = post { dispatch(VoiceEvent.SpeechStarted) }

        override fun onSpeechEnded() = post { dispatch(VoiceEvent.SpeechEnded) }

        override fun onNoSpeechTimeout() = post { dispatch(VoiceEvent.NoSpeechTimeout) }

        override fun onBargeIn() = post { dispatch(VoiceEvent.BargeIn) }

        override fun onLevel(level: Float) = post { _state.update { it.copy(level = level) } }

        override fun onPartialTranscript(text: String) =
            post {
                if (machine.phase == VoicePhase.LISTENING || machine.phase == VoicePhase.TRANSCRIBING) {
                    _state.update { it.copy(partialTranscript = text) }
                }
            }

        override fun onTranscribed(text: String) = post { dispatch(VoiceEvent.Transcribed(text)) }

        override fun onTranscriptionFailed(detail: String?) = post { dispatch(VoiceEvent.TranscriptionFailed(detail)) }

        override fun onAudioStarted() = post { dispatch(VoiceEvent.AudioStarted) }

        override fun onSpeechFinished() = post { dispatch(VoiceEvent.SpeechFinished) }

        override fun onSpeechFailed() = post { dispatch(VoiceEvent.SpeechFailed) }

        override fun onMicrophoneFailed() = post { dispatch(VoiceEvent.Fatal(VoiceNotice.MicrophoneUnavailable)) }
    }
}
