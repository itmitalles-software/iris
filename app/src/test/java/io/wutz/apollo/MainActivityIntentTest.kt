package io.wutz.apollo

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityIntentTest {
    @Test
    fun `open-chat intent through the non-exported alias is trusted`() {
        assertTrue(
            MainActivity.isTrustedNotificationIntent(
                MainActivity.ACTION_OPEN_CHAT_FROM_NOTIFICATION,
                MainActivity.NOTIFICATION_ENTRY_ALIAS,
            ),
        )
    }

    @Test
    fun `forged open-chat intent aimed at the exported activity is refused`() {
        // A foreign app can set the action, but can only name the exported MainActivity.
        assertFalse(
            MainActivity.isTrustedNotificationIntent(
                MainActivity.ACTION_OPEN_CHAT_FROM_NOTIFICATION,
                MainActivity::class.java.name,
            ),
        )
        assertFalse(MainActivity.isTrustedNotificationIntent(MainActivity.ACTION_OPEN_CHAT_FROM_NOTIFICATION, null))
    }

    @Test
    fun `alias without the open-chat action is refused`() {
        assertFalse(MainActivity.isTrustedNotificationIntent(Intent.ACTION_MAIN, MainActivity.NOTIFICATION_ENTRY_ALIAS))
        assertFalse(MainActivity.isTrustedNotificationIntent(null, MainActivity.NOTIFICATION_ENTRY_ALIAS))
    }

    @Test
    fun `recents relaunch is recognised as a replay`() {
        assertTrue(
            MainActivity.isReplayedFromHistory(
                Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY or Intent.FLAG_ACTIVITY_NEW_TASK,
            ),
        )
        assertFalse(MainActivity.isReplayedFromHistory(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        assertFalse(MainActivity.isReplayedFromHistory(0))
    }
}
