package io.wutz.apollo.data.voice

import io.wutz.apollo.data.remote.ServerEndpoint
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/*
 * Wire contract of the dashboard's two audio WebSockets
 * (hermes-agent `hermes_cli/web_routers/audio.py`).
 *
 * `/api/audio/speak-stream` (`speak_stream_ws`), one socket per spoken reply:
 *   client → {"text": "..."} (incremental deltas), {"done": true} when the reply is complete,
 *            {"stop": true} or a disconnect for barge-in
 *   server → {"type": "start", "sample_rate": N, "channels": 1} with the first PCM frame,
 *            binary s16le PCM frames, then {"type": "end"};
 *            {"type": "fallback"} instead when synthesis produced no audio at all
 *
 * `/api/audio/transcribe-stream` (`transcribe_stream_ws`), one socket per utterance:
 *   client → {"sample_rate": N} first (8000-48000), binary s16le mono PCM, {"eos": true}
 *   server → {"type": "partial", "text"}, {"type": "final", "transcript", "provider"},
 *            {"type": "error", "message"} (also right after accept when no live STT exists)
 *
 * Both sockets authenticate like `/api/ws` (`_ws_auth_ok`: `?ticket=` in gated mode,
 * `?token=` on loopback) and take the profile as a `?profile=` query parameter.
 */

/** Dashboard path of the streaming TTS socket. */
const val SPEAK_STREAM_PATH = "api/audio/speak-stream"

/** Dashboard path of the live STT socket. */
const val TRANSCRIBE_STREAM_PATH = "api/audio/transcribe-stream"

/** Server-accepted sample-rate range of `/api/audio/transcribe-stream`. */
val TRANSCRIBE_STREAM_SAMPLE_RATES = 8_000..48_000

private val protocolJson = Json { ignoreUnknownKeys = true }

/** A text frame sent by the speak-stream server. Binary frames are PCM and never parsed. */
sealed interface SpeakStreamServerFrame {
    /** Playback format; arrives once, together with the first PCM frame. */
    data class Start(
        val sampleRate: Int,
        val channels: Int,
    ) : SpeakStreamServerFrame

    /** All PCM was sent. */
    data object End : SpeakStreamServerFrame

    /** Synthesis produced no audio; the client speaks the whole reply via `POST /api/audio/speak`. */
    data object Fallback : SpeakStreamServerFrame

    /** Any other or malformed frame; ignored. */
    data object Unknown : SpeakStreamServerFrame
}

/** A text frame sent by the transcribe-stream server. */
sealed interface TranscribeStreamServerFrame {
    data class Partial(
        val text: String,
    ) : TranscribeStreamServerFrame

    data class Final(
        val transcript: String,
        val provider: String?,
    ) : TranscribeStreamServerFrame

    data class Error(
        val message: String,
    ) : TranscribeStreamServerFrame

    data object Unknown : TranscribeStreamServerFrame
}

/** Default PCM rate when a start frame omits it (server streamers default to 24 kHz). */
const val SPEAK_STREAM_DEFAULT_SAMPLE_RATE = 24_000

object SpeakStreamProtocol {
    fun textFrame(text: String): String = buildJsonObject { put("text", text) }.toString()

    fun doneFrame(): String = buildJsonObject { put("done", true) }.toString()

    fun stopFrame(): String = buildJsonObject { put("stop", true) }.toString()

    fun parse(frame: String): SpeakStreamServerFrame {
        val obj = parseObject(frame) ?: return SpeakStreamServerFrame.Unknown
        return when (obj.string("type")) {
            "start" -> {
                val rate = obj.int("sample_rate")?.takeIf { it > 0 } ?: SPEAK_STREAM_DEFAULT_SAMPLE_RATE
                val channels = obj.int("channels")?.takeIf { it in 1..2 } ?: 1
                SpeakStreamServerFrame.Start(rate, channels)
            }

            "end" -> {
                SpeakStreamServerFrame.End
            }

            "fallback" -> {
                SpeakStreamServerFrame.Fallback
            }

            else -> {
                SpeakStreamServerFrame.Unknown
            }
        }
    }
}

object TranscribeStreamProtocol {
    fun startFrame(sampleRate: Int): String {
        require(sampleRate in TRANSCRIBE_STREAM_SAMPLE_RATES) { "Unsupported sample rate $sampleRate" }
        return buildJsonObject { put("sample_rate", sampleRate) }.toString()
    }

    fun endOfSpeechFrame(): String = buildJsonObject { put("eos", true) }.toString()

    fun parse(frame: String): TranscribeStreamServerFrame {
        val obj = parseObject(frame) ?: return TranscribeStreamServerFrame.Unknown
        return when (obj.string("type")) {
            "partial" -> {
                obj.string("text")?.let { TranscribeStreamServerFrame.Partial(it) }
                    ?: TranscribeStreamServerFrame.Unknown
            }

            "final" -> {
                TranscribeStreamServerFrame.Final(
                    transcript = obj.string("transcript").orEmpty().trim(),
                    provider = obj.string("provider")?.takeIf { it.isNotBlank() },
                )
            }

            "error" -> {
                TranscribeStreamServerFrame.Error(obj.string("message").orEmpty())
            }

            else -> {
                TranscribeStreamServerFrame.Unknown
            }
        }
    }
}

/**
 * The URL of an audio WebSocket: the same origin, proxy prefix and credential parameter as
 * `/api/ws`, plus the active profile so the server resolves that profile's STT/TTS chain
 * (`speak_stream_ws` reads `ws.query_params["profile"]`; the OkHttp WebSocket client has no
 * ProfileScopeInterceptor, so the parameter is added here).
 */
fun voiceSocketUrl(
    endpoint: ServerEndpoint,
    path: String,
    authParameter: String,
    credential: String,
    profile: String?,
): String =
    endpoint.webSocketUrl(
        path = path,
        authParameter = authParameter.ifBlank { "token" },
        credential = credential,
        extraParams = profile?.trim()?.takeIf { it.isNotEmpty() }?.let { mapOf("profile" to it) } ?: emptyMap(),
    )

private fun parseObject(frame: String): JsonObject? =
    try {
        protocolJson.parseToJsonElement(frame).jsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)
        ?.takeIf {
            it.isString
        }?.contentOrNull

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
