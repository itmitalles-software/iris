package io.wutz.apollo.assistant

import android.content.Intent
import android.os.RemoteException
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Stub recognition service. The framework requires a recognition service to
 * be declared in voice_interaction_service.xml for the assistant component to
 * be valid (AOSP VoiceInteractionServiceInfo parse error otherwise), but v1
 * has no speech input — long-press Home just opens the app.
 *
 * While Iris is the assistant app the system also uses this service as the
 * default speech recognizer, so other apps call it. Every request is refused
 * at once with an error; a silent stub would leave those apps waiting forever.
 */
class AssistantRecognitionService : RecognitionService() {
    override fun onStartListening(
        intent: Intent,
        callback: Callback,
    ) {
        try {
            callback.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (e: RemoteException) {
            // The calling app is gone; nothing left to tell it.
            Log.w("AssistantRecognition", "Recognition client died before the refusal", e)
        }
    }

    // Nothing is ever listening, so stop and cancel have nothing to end.
    override fun onStopListening(callback: Callback) = Unit

    override fun onCancel(callback: Callback) = Unit
}
