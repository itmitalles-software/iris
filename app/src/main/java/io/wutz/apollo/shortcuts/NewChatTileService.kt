package io.wutz.apollo.shortcuts

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile that opens Apollo with a new chat. It is an action tile without on/off
 * state; on a locked device the system asks to unlock first.
 */
class NewChatTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun(::openNewChat) else openNewChat()
    }

    private fun openNewChat() {
        val intent = AppShortcuts.entryIntent(this, AppShortcuts.ACTION_NEW_CHAT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending =
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            startActivityAndCollapse(pending)
        } else {
            startActivityAndCollapseLegacy(intent)
        }
    }

    /** Below API 34 the PendingIntent overload does not exist; the Intent one is the only option. */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun startActivityAndCollapseLegacy(intent: Intent) {
        startActivityAndCollapse(intent)
    }
}
