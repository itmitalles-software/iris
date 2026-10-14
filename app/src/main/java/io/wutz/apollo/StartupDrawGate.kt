package io.wutz.apollo

import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver

/**
 * Holds back the activity's first frame until [isReady] or until [timeoutMs] has passed.
 *
 * The Android 12+ system splash (and the splash window background before Android 12) stays on
 * screen until the app draws its first frame. Holding that frame while the encrypted settings
 * load lets a cold start go straight from the splash to the content instead of flashing a
 * loading spinner in between. The timeout keeps a slow keystore from hiding the app: after it,
 * the in-app loading state shows as before. Skipped frames do not block the main thread.
 */
internal class StartupDrawGate(
    private val isReady: () -> Boolean,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val clock: () -> Long = SystemClock::uptimeMillis,
) {
    private val startedAt = clock()

    /** True when the first frame may draw. */
    fun canDraw(): Boolean = isReady() || clock() - startedAt >= timeoutMs

    /** Withholds [content]'s frames until [canDraw], then removes itself. */
    fun install(content: View) {
        content.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (!canDraw()) return false
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
            },
        )
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 2_000L
    }
}
