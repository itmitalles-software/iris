package io.wutz.apollo.ui.chat.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceConversationMachineTest {
    private var state = VoiceMachineState()
    private val effects = mutableListOf<VoiceEffect>()

    private fun send(event: VoiceEvent): List<VoiceEffect> {
        val transition = VoiceConversationMachine.reduce(state, event)
        state = transition.state
        effects += transition.effects
        return transition.effects
    }

    private fun startListening(liveTranscription: Boolean = true) {
        assertEquals(listOf(VoiceEffect.ResolveCapabilities), send(VoiceEvent.Start))
        assertEquals(VoicePhase.STARTING, state.phase)
        assertEquals(
            listOf(VoiceEffect.Acquire, VoiceEffect.Listen(alreadySpeaking = false)),
            send(VoiceEvent.CapabilitiesResolved(sttAvailable = true, liveTranscription = liveTranscription)),
        )
        assertEquals(VoicePhase.LISTENING, state.phase)
        assertEquals(liveTranscription, state.liveTranscription)
    }

    private fun sayAndSend(text: String) {
        send(VoiceEvent.SpeechStarted)
        assertTrue(state.heardSpeech)
        assertEquals(listOf(VoiceEffect.EndUtterance), send(VoiceEvent.SpeechEnded))
        assertEquals(VoicePhase.TRANSCRIBING, state.phase)
        assertEquals(
            listOf(VoiceEffect.Submit(text, interruptRunningTurn = false)),
            send(VoiceEvent.Transcribed(" $text ")),
        )
        assertEquals(VoicePhase.THINKING, state.phase)
        assertEquals(ReplyTurn.AWAITING_START, state.turn)
    }

    @Test
    fun `full loop listens, sends, speaks the streamed reply and listens again`() {
        startListening()
        sayAndSend("What time is it?")

        assertEquals(listOf(VoiceEffect.OpenSpeech), send(VoiceEvent.ReplyStarted))
        assertEquals(listOf(VoiceEffect.AppendSpeech("It is ")), send(VoiceEvent.ReplyDelta("It is ")))
        assertEquals(listOf(VoiceEffect.AppendSpeech("noon.")), send(VoiceEvent.ReplyDelta("noon.")))
        assertEquals(listOf(VoiceEffect.WatchForBargeIn), send(VoiceEvent.AudioStarted))
        assertEquals(VoicePhase.SPEAKING, state.phase)
        assertEquals(listOf(VoiceEffect.FinishSpeech), send(VoiceEvent.ReplyCompleted("It is noon.", failed = false)))
        assertEquals(ReplyTurn.COMPLETE, state.turn)
        assertEquals(VoicePhase.SPEAKING, state.phase)

        assertEquals(listOf(VoiceEffect.Listen(alreadySpeaking = false)), send(VoiceEvent.SpeechFinished))
        assertEquals(VoicePhase.LISTENING, state.phase)
        assertEquals(ReplyTurn.NONE, state.turn)
        assertFalse(state.heardSpeech)
    }

    @Test
    fun `server without STT ends before the microphone opens`() {
        send(VoiceEvent.Start)
        val result = send(VoiceEvent.CapabilitiesResolved(sttAvailable = false, liveTranscription = false))
        assertEquals(listOf(VoiceEffect.Release, VoiceEffect.Notify(VoiceNotice.SttDisabled)), result)
        assertEquals(VoicePhase.OFF, state.phase)
        assertFalse(effects.contains(VoiceEffect.Acquire))
    }

    @Test
    fun `tap sends the utterance early, but only after speech was heard`() {
        startListening()
        assertTrue(send(VoiceEvent.Tap).isEmpty())
        send(VoiceEvent.SpeechStarted)
        assertEquals(listOf(VoiceEffect.EndUtterance), send(VoiceEvent.Tap))
        assertEquals(VoicePhase.TRANSCRIBING, state.phase)
    }

    @Test
    fun `barge-in by voice cuts the reply and keeps the running utterance`() {
        startListening()
        sayAndSend("Tell me a story")
        send(VoiceEvent.ReplyStarted)
        send(VoiceEvent.ReplyDelta("Once upon a time"))
        send(VoiceEvent.AudioStarted)

        val result = send(VoiceEvent.BargeIn)
        assertEquals(listOf(VoiceEffect.CancelSpeech, VoiceEffect.Listen(alreadySpeaking = true)), result)
        assertEquals(VoicePhase.LISTENING, state.phase)
        assertTrue(state.heardSpeech)
        assertTrue(state.bargedIntoRunningTurn)

        // Late events of the interrupted turn are not spoken.
        assertTrue(send(VoiceEvent.ReplyDelta(" there was")).isEmpty())
        assertTrue(send(VoiceEvent.ReplyCompleted("Once upon a time there was", failed = false)).isEmpty())

        send(VoiceEvent.SpeechEnded)
        assertEquals(
            listOf(VoiceEffect.Submit("Stop, shorter please", interruptRunningTurn = true)),
            send(VoiceEvent.Transcribed("Stop, shorter please")),
        )
        assertFalse(state.bargedIntoRunningTurn)
    }

    private fun bargeInAndResend(text: String) {
        startListening()
        sayAndSend("Tell me a story")
        send(VoiceEvent.ReplyStarted)
        send(VoiceEvent.ReplyDelta("Once upon a time"))
        send(VoiceEvent.AudioStarted)
        send(VoiceEvent.BargeIn)
        send(VoiceEvent.SpeechEnded)
        assertEquals(
            listOf(VoiceEffect.Submit(text, interruptRunningTurn = true)),
            send(VoiceEvent.Transcribed(text)),
        )
        // The interrupted turn was still running when the transcript went out.
        assertTrue(send(VoiceEvent.SubmittedBehindRunningTurn).isEmpty())
        assertEquals(ReplyTurn.BEHIND_RUNNING_TURN, state.turn)
    }

    @Test
    fun `the interrupted turn's failed completion does not end the wait for the new reply`() {
        bargeInAndResend("Stop, shorter please")

        // message.complete of the interrupted turn (interrupt sentinel → failed).
        assertTrue(send(VoiceEvent.ReplyCompleted("Once upon a time", failed = true)).isEmpty())
        assertEquals(VoicePhase.THINKING, state.phase)
        assertEquals(ReplyTurn.BEHIND_RUNNING_TURN, state.turn)

        // The new turn's reply is spoken.
        assertEquals(listOf(VoiceEffect.OpenSpeech), send(VoiceEvent.ReplyStarted))
        assertEquals(listOf(VoiceEffect.AppendSpeech("Short one.")), send(VoiceEvent.ReplyDelta("Short one.")))
        assertEquals(listOf(VoiceEffect.FinishSpeech), send(VoiceEvent.ReplyCompleted("Short one.", failed = false)))
        assertEquals(ReplyTurn.COMPLETE, state.turn)
    }

    @Test
    fun `the interrupted turn's partial completion is not spoken as the new reply`() {
        bargeInAndResend("Stop, shorter please")

        assertTrue(send(VoiceEvent.ReplyCompleted("Once upon a time there was", failed = false)).isEmpty())
        assertFalse(effects.contains(VoiceEffect.AppendSpeech("Once upon a time there was")))
        assertEquals(VoicePhase.THINKING, state.phase)

        send(VoiceEvent.ReplyStarted)
        assertEquals(
            listOf(VoiceEffect.AppendSpeech("Short one."), VoiceEffect.FinishSpeech),
            send(VoiceEvent.ReplyCompleted("Short one.", failed = false)),
        )
    }

    @Test
    fun `a transcript queued behind a running turn waits for its own reply`() {
        startListening()
        sayAndSend("And the weather?")
        send(VoiceEvent.SubmittedBehindRunningTurn)

        // Tokens and completion of the turn that was already running when voice mode started.
        assertTrue(send(VoiceEvent.ReplyDelta("Earlier answer")).isEmpty())
        assertTrue(send(VoiceEvent.ReplyCompleted("Earlier answer", failed = false)).isEmpty())
        assertEquals(VoicePhase.THINKING, state.phase)

        assertEquals(listOf(VoiceEffect.OpenSpeech), send(VoiceEvent.ReplyStarted))
        assertEquals(listOf(VoiceEffect.AppendSpeech("Sunny.")), send(VoiceEvent.ReplyDelta("Sunny.")))
    }

    @Test
    fun `tap while waiting behind a running turn interrupts it with the next transcript`() {
        startListening()
        sayAndSend("Hi")
        send(VoiceEvent.SubmittedBehindRunningTurn)
        assertEquals(listOf(VoiceEffect.Listen(alreadySpeaking = false)), send(VoiceEvent.Tap))
        assertTrue(state.bargedIntoRunningTurn)
    }

    @Test
    fun `behind-running-turn is only noted while our reply has not started`() {
        startListening()
        sayAndSend("Hi")
        send(VoiceEvent.ReplyStarted)
        assertTrue(send(VoiceEvent.SubmittedBehindRunningTurn).isEmpty())
        assertEquals(ReplyTurn.STREAMING, state.turn)
    }

    @Test
    fun `barge-in after the reply completed does not interrupt the next turn`() {
        startListening()
        sayAndSend("Hi")
        send(VoiceEvent.ReplyStarted)
        send(VoiceEvent.ReplyDelta("Hello!"))
        send(VoiceEvent.AudioStarted)
        send(VoiceEvent.ReplyCompleted("Hello!", failed = false))
        send(VoiceEvent.BargeIn)
        assertFalse(state.bargedIntoRunningTurn)
    }

    @Test
    fun `voice barge-in is ignored before audio plays, tap still interrupts`() {
        startListening()
        sayAndSend("Hi")
        send(VoiceEvent.ReplyStarted)
        assertTrue(send(VoiceEvent.BargeIn).isEmpty())
        assertEquals(VoicePhase.THINKING, state.phase)

        assertEquals(
            listOf(VoiceEffect.CancelSpeech, VoiceEffect.Listen(alreadySpeaking = false)),
            send(VoiceEvent.Tap),
        )
        assertEquals(VoicePhase.LISTENING, state.phase)
        assertFalse(state.heardSpeech)
    }

    @Test
    fun `a reply without deltas is spoken from the complete text`() {
        startListening()
        sayAndSend("Hi")
        send(VoiceEvent.ReplyStarted)
        assertEquals(
            listOf(VoiceEffect.AppendSpeech("Hello there."), VoiceEffect.FinishSpeech),
            send(VoiceEvent.ReplyCompleted("Hello there.", failed = false)),
        )
    }

    @Test
    fun `a completed reply without message start still speaks`() {
        startListening()
        sayAndSend("Hi")
        assertEquals(
            listOf(VoiceEffect.OpenSpeech, VoiceEffect.AppendSpeech("Done."), VoiceEffect.FinishSpeech),
            send(VoiceEvent.ReplyCompleted("Done.", failed = false)),
        )
    }

    @Test
    fun `an empty reply goes straight back to listening`() {
        startListening()
        sayAndSend("Run the backup")
        send(VoiceEvent.ReplyStarted)
        assertEquals(
            listOf(VoiceEffect.CancelSpeech, VoiceEffect.Listen(alreadySpeaking = false)),
            send(VoiceEvent.ReplyCompleted("", failed = false)),
        )
        assertEquals(VoicePhase.LISTENING, state.phase)
    }

    @Test
    fun `a failed reply cancels speech and listens again`() {
        startListening()
        sayAndSend("Hi")
        send(VoiceEvent.ReplyStarted)
        send(VoiceEvent.ReplyDelta("Partial"))
        assertEquals(
            listOf(VoiceEffect.CancelSpeech, VoiceEffect.Listen(alreadySpeaking = false)),
            send(VoiceEvent.ReplyCompleted("Error", failed = true)),
        )
        assertEquals(VoicePhase.LISTENING, state.phase)
    }

    @Test
    fun `speech output failure is reported once per conversation`() {
        startListening()
        sayAndSend("One")
        send(VoiceEvent.ReplyStarted)
        send(VoiceEvent.ReplyDelta("A"))
        send(VoiceEvent.ReplyCompleted("A", failed = false))
        assertEquals(
            listOf(VoiceEffect.Notify(VoiceNotice.SpeechOutputFailed), VoiceEffect.Listen(alreadySpeaking = false)),
            send(VoiceEvent.SpeechFailed),
        )
        sayAndSend("Two")
        send(VoiceEvent.ReplyStarted)
        send(VoiceEvent.ReplyDelta("B"))
        send(VoiceEvent.ReplyCompleted("B", failed = false))
        assertEquals(listOf(VoiceEffect.Listen(alreadySpeaking = false)), send(VoiceEvent.SpeechFailed))
    }

    @Test
    fun `empty transcripts re-listen and end the conversation after the limit`() {
        startListening()
        repeat(VoiceConversationMachine.MAX_EMPTY_TRANSCRIPTS - 1) {
            send(VoiceEvent.SpeechStarted)
            send(VoiceEvent.SpeechEnded)
            assertEquals(listOf(VoiceEffect.Listen(alreadySpeaking = false)), send(VoiceEvent.Transcribed("  ")))
        }
        send(VoiceEvent.SpeechStarted)
        send(VoiceEvent.SpeechEnded)
        assertEquals(
            listOf(VoiceEffect.Release, VoiceEffect.Notify(VoiceNotice.NothingHeard)),
            send(VoiceEvent.Transcribed("")),
        )
        assertEquals(VoicePhase.OFF, state.phase)
    }

    @Test
    fun `a failed transcription is reported and retried once`() {
        startListening()
        send(VoiceEvent.SpeechStarted)
        send(VoiceEvent.SpeechEnded)
        assertEquals(
            listOf(
                VoiceEffect.Notify(VoiceNotice.TranscriptionFailed("HTTP 500")),
                VoiceEffect.Listen(alreadySpeaking = false),
            ),
            send(VoiceEvent.TranscriptionFailed("HTTP 500")),
        )
        send(VoiceEvent.SpeechStarted)
        send(VoiceEvent.SpeechEnded)
        assertEquals(
            listOf(VoiceEffect.Release, VoiceEffect.Notify(VoiceNotice.TranscriptionFailed("HTTP 500"))),
            send(VoiceEvent.TranscriptionFailed("HTTP 500")),
        )
    }

    @Test
    fun `a rejected send restores the transcript and ends`() {
        startListening()
        sayAndSend("Remember this")
        assertEquals(
            listOf(
                VoiceEffect.RestoreTranscript("Remember this"),
                VoiceEffect.Release,
                VoiceEffect.Notify(VoiceNotice.TranscriptUnsent),
            ),
            send(VoiceEvent.SubmitRejected("Remember this")),
        )
        assertEquals(VoicePhase.OFF, state.phase)
    }

    @Test
    fun `silence ends the conversation`() {
        startListening()
        assertEquals(
            listOf(VoiceEffect.Release, VoiceEffect.Notify(VoiceNotice.NothingHeard)),
            send(VoiceEvent.NoSpeechTimeout),
        )
    }

    @Test
    fun `stop and fatal errors release from every phase`() {
        startListening()
        sayAndSend("Hi")
        send(VoiceEvent.ReplyStarted)
        assertEquals(listOf(VoiceEffect.Release), send(VoiceEvent.Stop))
        assertEquals(VoiceMachineState(), state)

        startListening()
        assertEquals(
            listOf(VoiceEffect.Release, VoiceEffect.Notify(VoiceNotice.ConnectionLost)),
            send(VoiceEvent.Fatal(VoiceNotice.ConnectionLost)),
        )
        assertTrue(send(VoiceEvent.Fatal(VoiceNotice.ConnectionLost)).isEmpty())
    }

    @Test
    fun `events outside a conversation are ignored`() {
        listOf(
            VoiceEvent.ReplyStarted,
            VoiceEvent.ReplyDelta("x"),
            VoiceEvent.SpeechStarted,
            VoiceEvent.Tap,
            VoiceEvent.Transcribed("x"),
        ).forEach { assertTrue(send(it).isEmpty()) }
        assertEquals(VoicePhase.OFF, state.phase)
    }

    @Test
    fun `reply start is only adopted after our transcript was sent`() {
        startListening()
        assertTrue(send(VoiceEvent.ReplyStarted).isEmpty())
        sayAndSend("Hi")
        send(VoiceEvent.ReplyStarted)
        // A second message.start of the same turn keeps the open stream.
        assertTrue(send(VoiceEvent.ReplyStarted).isEmpty())
    }
}
