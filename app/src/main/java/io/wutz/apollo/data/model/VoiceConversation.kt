package io.wutz.apollo.data.model

import kotlinx.serialization.Serializable

/**
 * Non-secret part of `GET /api/audio/voice-config` (hermes-agent
 * `tools/voice_client_config.py`).
 *
 * The server answers `{"ok": true, "stt": {...}, "tts": {...}}`. A direction is either
 * `{"mode": "relay", "reason": "..."}` or `{"mode": "direct", ..., "api_key": "..."}` for
 * desktop client-direct synthesis. Apollo always uses the relay endpoints, so this model
 * deliberately has no key fields: provider credentials are never deserialized, stored or logged.
 */
@Serializable
data class VoiceConfigResponse(
    val ok: Boolean = false,
    val stt: VoiceDirectionConfig? = null,
    val tts: VoiceDirectionConfig? = null,
)

@Serializable
data class VoiceDirectionConfig(
    val mode: String? = null,
    val reason: String? = null,
    /** STT only: true when `/api/audio/transcribe-stream` has a live provider. */
    val streaming: Boolean? = null,
)

/** What the voice conversation may use on the connected server. */
data class VoiceCapabilities(
    val sttAvailable: Boolean,
    val liveTranscription: Boolean,
) {
    companion object {
        /** Older servers without voice-config: try the relay and live STT, fall back on failure. */
        val UNKNOWN = VoiceCapabilities(sttAvailable = true, liveTranscription = true)

        /** The resolver's verdict when `stt.enabled` is false in the profile's config. */
        private const val STT_DISABLED_REASON = "stt disabled"

        fun from(config: VoiceConfigResponse?): VoiceCapabilities {
            val stt = config?.stt ?: return UNKNOWN
            val disabled = stt.mode == "relay" && stt.reason?.trim()?.lowercase() == STT_DISABLED_REASON
            return VoiceCapabilities(
                sttAvailable = !disabled,
                liveTranscription = !disabled && stt.streaming != false,
            )
        }
    }
}

/**
 * Body of `POST /api/audio/stt-lease` and `POST /api/audio/tts-lease`: warm-up / release
 * signals so a local STT or TTS model is loaded before the first utterance or reply.
 */
@Serializable
data class AudioLeaseRequest(
    val lease: String,
    val active: Boolean,
)
