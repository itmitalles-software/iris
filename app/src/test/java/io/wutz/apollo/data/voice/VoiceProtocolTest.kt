package io.wutz.apollo.data.voice

import io.wutz.apollo.data.model.VoiceCapabilities
import io.wutz.apollo.data.model.VoiceConfigResponse
import io.wutz.apollo.data.model.VoiceDirectionConfig
import io.wutz.apollo.data.remote.OkHttpProvider
import io.wutz.apollo.data.remote.ServerEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wire contract of hermes-agent `hermes_cli/web_routers/audio.py` (speak_stream_ws, transcribe_stream_ws). */
class VoiceProtocolTest {
    @Test
    fun `speak-stream client frames match the server contract`() {
        assertEquals("""{"text":"Hello there."}""", SpeakStreamProtocol.textFrame("Hello there."))
        assertEquals("""{"done":true}""", SpeakStreamProtocol.doneFrame())
        assertEquals("""{"stop":true}""", SpeakStreamProtocol.stopFrame())
    }

    @Test
    fun `speak-stream text frames escape JSON`() {
        assertEquals("""{"text":"say \"hi\"\n"}""", SpeakStreamProtocol.textFrame("say \"hi\"\n"))
    }

    @Test
    fun `speak-stream start frame carries the playback format`() {
        assertEquals(
            SpeakStreamServerFrame.Start(sampleRate = 22_050, channels = 1),
            SpeakStreamProtocol.parse("""{"type":"start","sample_rate":22050,"channels":1}"""),
        )
    }

    @Test
    fun `speak-stream start frame falls back to 24 kHz mono`() {
        assertEquals(
            SpeakStreamServerFrame.Start(sampleRate = SPEAK_STREAM_DEFAULT_SAMPLE_RATE, channels = 1),
            SpeakStreamProtocol.parse("""{"type":"start","sample_rate":0,"channels":7}"""),
        )
    }

    @Test
    fun `speak-stream end, fallback and unknown frames`() {
        assertEquals(SpeakStreamServerFrame.End, SpeakStreamProtocol.parse("""{"type":"end"}"""))
        assertEquals(SpeakStreamServerFrame.Fallback, SpeakStreamProtocol.parse("""{"type":"fallback"}"""))
        assertEquals(SpeakStreamServerFrame.Unknown, SpeakStreamProtocol.parse("""{"type":"later"}"""))
        assertEquals(SpeakStreamServerFrame.Unknown, SpeakStreamProtocol.parse("not json"))
        assertEquals(SpeakStreamServerFrame.Unknown, SpeakStreamProtocol.parse("[1,2]"))
        assertEquals(SpeakStreamServerFrame.Unknown, SpeakStreamProtocol.parse("""{"type":5}"""))
    }

    @Test
    fun `transcribe-stream client frames match the server contract`() {
        assertEquals("""{"sample_rate":16000}""", TranscribeStreamProtocol.startFrame(16_000))
        assertEquals("""{"eos":true}""", TranscribeStreamProtocol.endOfSpeechFrame())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `transcribe-stream rejects rates the server refuses`() {
        TranscribeStreamProtocol.startFrame(96_000)
    }

    @Test
    fun `transcribe-stream server frames`() {
        assertEquals(
            TranscribeStreamServerFrame.Partial("hello wor"),
            TranscribeStreamProtocol.parse("""{"type":"partial","text":"hello wor"}"""),
        )
        assertEquals(
            TranscribeStreamServerFrame.Final("hello world", "openai"),
            TranscribeStreamProtocol.parse("""{"type":"final","transcript":" hello world ","provider":"openai"}"""),
        )
        assertEquals(
            TranscribeStreamServerFrame.Final("", null),
            TranscribeStreamProtocol.parse("""{"type":"final","transcript":"","provider":""}"""),
        )
        assertEquals(
            TranscribeStreamServerFrame.Error("no live STT for the configured provider"),
            TranscribeStreamProtocol.parse("""{"type":"error","message":"no live STT for the configured provider"}"""),
        )
        assertEquals(TranscribeStreamServerFrame.Unknown, TranscribeStreamProtocol.parse("""{"type":"partial"}"""))
        assertEquals(TranscribeStreamServerFrame.Unknown, TranscribeStreamProtocol.parse("{"))
    }

    @Test
    fun `socket url keeps the proxy prefix, credential and profile`() {
        val endpoint = ServerEndpoint.parse("https://hermes.example.test/hermes")
        val url = voiceSocketUrl(endpoint, SPEAK_STREAM_PATH, "ticket", "t-1", "work")
        assertEquals("wss://hermes.example.test/hermes/api/audio/speak-stream?ticket=t-1&profile=work", url)
    }

    @Test
    fun `socket url without a profile and with the default auth parameter`() {
        val endpoint = ServerEndpoint.parse("https://127.0.0.1:9119/")
        val url = voiceSocketUrl(endpoint, TRANSCRIBE_STREAM_PATH, "", "tok", " ")
        assertEquals("wss://127.0.0.1:9119/api/audio/transcribe-stream?token=tok", url)
    }

    @Test
    fun `voice config never keeps provider keys`() {
        val body =
            """
            {"ok":true,
             "stt":{"mode":"direct","wire":"openai-multipart","api_key":"sk-secret","streaming":true},
             "tts":{"mode":"relay","reason":"provider 'edge' has no client wire"}}
            """.trimIndent()
        val parsed = OkHttpProvider.json.decodeFromString(VoiceConfigResponse.serializer(), body)
        assertEquals(VoiceDirectionConfig(mode = "direct", streaming = true), parsed.stt)
        assertFalse(parsed.toString().contains("sk-secret"))
        assertEquals(VoiceCapabilities(sttAvailable = true, liveTranscription = true), VoiceCapabilities.from(parsed))
    }

    @Test
    fun `voice config detects disabled STT and missing live STT`() {
        val disabled =
            VoiceConfigResponse(ok = true, stt = VoiceDirectionConfig(mode = "relay", reason = "stt disabled"))
        assertFalse(VoiceCapabilities.from(disabled).sttAvailable)

        val relayOnly =
            VoiceConfigResponse(
                ok = true,
                stt = VoiceDirectionConfig(mode = "relay", reason = "local provider", streaming = false),
            )
        assertEquals(
            VoiceCapabilities(sttAvailable = true, liveTranscription = false),
            VoiceCapabilities.from(relayOnly),
        )
    }

    @Test
    fun `older servers without voice config try everything`() {
        assertEquals(VoiceCapabilities.UNKNOWN, VoiceCapabilities.from(null))
        assertEquals(VoiceCapabilities.UNKNOWN, VoiceCapabilities.from(VoiceConfigResponse(ok = true)))
        assertTrue(VoiceCapabilities.UNKNOWN.sttAvailable)
    }
}
