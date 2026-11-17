package io.wutz.apollo.assistant

import android.service.voice.VoiceInteractionService

/**
 * Top-level digital-assistant service. The system keeps this running while
 * Iris is the default assistant; the actual trigger handling lives
 * in [AssistantSessionService] / [AssistantSession].
 */
class AssistantService : VoiceInteractionService()
