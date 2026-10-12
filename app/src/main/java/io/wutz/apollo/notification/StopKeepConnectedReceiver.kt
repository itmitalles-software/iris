package io.wutz.apollo.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.wutz.apollo.data.local.AuthManager

/**
 * Handles the "Turn off" action on the ongoing background notification:
 * disables "Stay connected in background" and lets the controller retire the
 * lease/service.
 */
class StopKeepConnectedReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_STOP_KEEP_CONNECTED = "io.wutz.apollo.ACTION_STOP_KEEP_CONNECTED"
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_STOP_KEEP_CONNECTED) return
        AuthManager.setKeepConnectedInBackground(false)
        BackgroundConnectionController.default.onKeepConnectedDisabled()
    }
}
