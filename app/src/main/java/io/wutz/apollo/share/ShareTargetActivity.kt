package io.wutz.apollo.share

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.wutz.apollo.MainActivity

/**
 * Share-sheet entry point. The system launches share targets inside the
 * sender's task, which would create a second [MainActivity] next to the one
 * already running. This invisible trampoline forwards the share into Iris's
 * own task instead and re-grants read access to the shared URIs, which
 * otherwise end with this activity.
 */
class ShareTargetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = intent
        if (source.action == Intent.ACTION_SEND || source.action == Intent.ACTION_SEND_MULTIPLE) {
            val forward =
                Intent(this, MainActivity::class.java).apply {
                    action = source.action
                    type = source.type
                    source.extras?.let(::putExtras)
                    clipData = source.clipData
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                }
            try {
                startActivity(forward)
            } catch (_: SecurityException) {
                // The sender shared a URI without granting it to us; we cannot re-grant it.
                // Forward the text alone: MainActivity drops the unreadable stream and tells the user.
                startActivity(
                    forward.apply {
                        clipData = null
                        removeExtra(Intent.EXTRA_STREAM)
                        flags = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION.inv()
                    },
                )
            }
        }
        finish()
    }
}
