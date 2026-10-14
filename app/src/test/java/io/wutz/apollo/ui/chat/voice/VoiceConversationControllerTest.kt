package io.wutz.apollo.ui.chat.voice

import io.wutz.apollo.R
import io.wutz.apollo.data.model.VoiceCapabilities
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceConversationControllerTest {
    private class FakeEngine(
        val listener: VoiceEngineListener,
        private val capabilities: VoiceCapabilities,
        private val micWorks: Boolean,
    ) : VoiceEngine {
        val calls = mutableListOf<String>()

        override suspend fun resolveCapabilities(): VoiceCapabilities = capabilities

        override fun acquire(liveTranscription: Boolean): Boolean {
            calls += "acquire:$liveTranscription"
            return micWorks
        }

        override fun listen(alreadySpeaking: Boolean) {
            calls += "listen:$alreadySpeaking"
        }

        override fun watchForBargeIn() {
            calls += "watch"
        }

        override fun endUtterance() {
            calls += "end"
        }

        override fun openSpeech() {
            calls += "open"
        }

        override fun appendSpeech(text: String) {
            calls += "append:$text"
        }

        override fun finishSpeech() {
            calls += "finish"
        }

        override fun cancelSpeech() {
            calls += "cancel"
        }

        override fun release() {
            calls += "release"
        }
    }

    private class FakeHost(
        var accept: Boolean = true,
        var turnRunning: Boolean = false,
    ) : VoiceConversationHost {
        val submitted = mutableListOf<Pair<String, Boolean>>()
        val restored = mutableListOf<String>()
        val notices = mutableListOf<VoiceNotice>()

        override fun isTurnRunning(): Boolean = turnRunning

        override fun submitTranscript(
            text: String,
            interruptRunningTurn: Boolean,
        ): Boolean {
            submitted += text to interruptRunningTurn
            return accept
        }

        override fun restoreTranscript(text: String) {
            restored += text
        }

        override fun showNotice(notice: VoiceNotice) {
            notices += notice
        }
    }

    private val engines = mutableListOf<FakeEngine>()
    private val host = FakeHost()

    private fun TestScope.controller(
        capabilities: VoiceCapabilities = VoiceCapabilities(sttAvailable = true, liveTranscription = true),
        micWorks: Boolean = true,
    ) = VoiceConversationController(
        scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
        engineFactory = { listener -> FakeEngine(listener, capabilities, micWorks).also { engines += it } },
        host = host,
    )

    @Test
    fun `start opens the microphone and listens`() =
        runTest {
            val controller = controller()
            controller.start()
            val engine = engines.single()
            assertEquals(listOf("acquire:true", "listen:false"), engine.calls)
            assertEquals(VoicePhase.LISTENING, controller.state.value.phase)
            assertTrue(controller.isActive)
        }

    @Test
    fun `one turn goes from utterance to spoken reply and back to listening`() =
        runTest {
            val controller = controller()
            controller.start()
            val engine = engines.single()

            engine.listener.onSpeechStarted()
            engine.listener.onPartialTranscript("turn on")
            assertTrue(controller.state.value.heardSpeech)
            assertEquals("turn on", controller.state.value.partialTranscript)
            engine.listener.onSpeechEnded()
            assertEquals(VoicePhase.TRANSCRIBING, controller.state.value.phase)
            engine.listener.onTranscribed("turn on the lights")
            assertEquals(listOf("turn on the lights" to false), host.submitted)
            assertEquals(VoicePhase.THINKING, controller.state.value.phase)
            assertEquals("", controller.state.value.partialTranscript)

            controller.onReplyStarted()
            controller.onReplyDelta("Done.")
            engine.listener.onAudioStarted()
            assertEquals(VoicePhase.SPEAKING, controller.state.value.phase)
            controller.onReplyCompleted("Done.", failed = false)
            engine.listener.onSpeechFinished()

            assertEquals(VoicePhase.LISTENING, controller.state.value.phase)
            assertEquals(
                listOf(
                    "acquire:true",
                    "listen:false",
                    "end",
                    "open",
                    "append:Done.",
                    "watch",
                    "finish",
                    "listen:false",
                ),
                engine.calls,
            )
        }

    @Test
    fun `stop releases the engine and ignores its late callbacks`() =
        runTest {
            val controller = controller()
            controller.start()
            val engine = engines.single()
            controller.stop()
            assertEquals("release", engine.calls.last())
            assertEquals(VoicePhase.OFF, controller.state.value.phase)
            assertFalse(controller.isActive)

            engine.listener.onSpeechStarted()
            engine.listener.onTranscribed("late")
            controller.onReplyStarted()
            assertTrue(host.submitted.isEmpty())
            assertEquals(VoicePhase.OFF, controller.state.value.phase)
        }

    @Test
    fun `a restart gets a fresh engine and drops the old one's events`() =
        runTest {
            val controller = controller()
            controller.start()
            controller.stop()
            controller.start()
            assertEquals(2, engines.size)
            engines[0].listener.onSpeechStarted()
            assertFalse(controller.state.value.heardSpeech)
            engines[1].listener.onSpeechStarted()
            assertTrue(controller.state.value.heardSpeech)
        }

    @Test
    fun `missing STT is reported without opening the microphone`() =
        runTest {
            val controller =
                controller(capabilities = VoiceCapabilities(sttAvailable = false, liveTranscription = false))
            controller.start()
            assertEquals(listOf("release"), engines.single().calls)
            assertEquals(listOf<VoiceNotice>(VoiceNotice.SttDisabled), host.notices)
            assertEquals(VoicePhase.OFF, controller.state.value.phase)
        }

    @Test
    fun `a broken microphone ends the conversation`() =
        runTest {
            val controller = controller(micWorks = false)
            controller.start()
            assertEquals(listOf<VoiceNotice>(VoiceNotice.MicrophoneUnavailable), host.notices)
            assertEquals(VoicePhase.OFF, controller.state.value.phase)
            assertEquals(listOf("acquire:true", "release"), engines.single().calls)
        }

    @Test
    fun `a rejected transcript is restored to the composer`() =
        runTest {
            host.accept = false
            val controller = controller()
            controller.start()
            val engine = engines.single()
            engine.listener.onSpeechStarted()
            engine.listener.onSpeechEnded()
            engine.listener.onTranscribed("keep me")
            assertEquals(listOf("keep me"), host.restored)
            assertEquals(listOf<VoiceNotice>(VoiceNotice.TranscriptUnsent), host.notices)
            assertEquals(VoicePhase.OFF, controller.state.value.phase)
        }

    @Test
    fun `abort reports the reason only while active`() =
        runTest {
            val controller = controller()
            controller.abort(VoiceNotice.ConnectionLost)
            assertTrue(host.notices.isEmpty())
            controller.start()
            controller.abort(VoiceNotice.ConnectionLost)
            assertEquals(listOf<VoiceNotice>(VoiceNotice.ConnectionLost), host.notices)
        }

    @Test
    fun `a transcript sent behind a running turn ignores that turn's completion`() =
        runTest {
            host.turnRunning = true
            val controller = controller()
            controller.start()
            val engine = engines.single()
            engine.listener.onSpeechStarted()
            engine.listener.onSpeechEnded()
            engine.listener.onTranscribed("what about tomorrow")
            assertEquals(VoicePhase.THINKING, controller.state.value.phase)

            // The running turn ends (interrupted) after our transcript went out.
            controller.onReplyCompleted("Today it is", failed = true)
            assertEquals(VoicePhase.THINKING, controller.state.value.phase)
            assertEquals(listOf("acquire:true", "listen:false", "end"), engine.calls)

            controller.onReplyStarted()
            controller.onReplyDelta("Rain.")
            controller.onReplyCompleted("Rain.", failed = false)
            assertEquals(
                listOf("acquire:true", "listen:false", "end", "open", "append:Rain.", "finish"),
                engine.calls,
            )
        }

    @Test
    fun `losing the audio focus ends the conversation`() =
        runTest {
            val controller = controller()
            controller.start()
            val engine = engines.single()
            engine.listener.onAudioFocusLost()
            assertEquals("release", engine.calls.last())
            assertEquals(VoicePhase.OFF, controller.state.value.phase)
            assertEquals(listOf<VoiceNotice>(VoiceNotice.AudioFocusLost), host.notices)
        }

    @Test
    fun `level updates reach the UI state`() =
        runTest {
            val controller = controller()
            controller.start()
            engines.single().listener.onLevel(0.5f)
            assertEquals(0.5f, controller.state.value.level, 0f)
            controller.stop()
            assertEquals(0f, controller.state.value.level, 0f)
        }

    @Test
    fun `notices map to localized text`() {
        assertEquals(UiText.res(R.string.voice_conversation_err_stt_disabled), VoiceNotice.SttDisabled.toUiText())
        assertEquals(UiText.res(R.string.voice_conversation_err_audio_focus), VoiceNotice.AudioFocusLost.toUiText())
        assertEquals(
            UiText.res(R.string.voice_conversation_err_transcription, "HTTP 500"),
            VoiceNotice.TranscriptionFailed("HTTP 500").toUiText(),
        )
        assertEquals(
            UiText.res(R.string.voice_conversation_err_transcription_unknown),
            VoiceNotice.TranscriptionFailed(" ").toUiText(),
        )
    }

    @Test
    fun `panel offers send while hearing and interrupt while replying`() {
        assertEquals(null, primaryAction(VoiceConversationUiState(phase = VoicePhase.LISTENING)))
        assertEquals(
            R.string.voice_conversation_action_send,
            primaryAction(VoiceConversationUiState(phase = VoicePhase.LISTENING, heardSpeech = true)),
        )
        assertEquals(
            R.string.voice_conversation_action_interrupt,
            primaryAction(VoiceConversationUiState(phase = VoicePhase.SPEAKING)),
        )
        assertEquals(null, primaryAction(VoiceConversationUiState(phase = VoicePhase.TRANSCRIBING)))
    }
}
