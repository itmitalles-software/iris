package io.wutz.apollo.applock

/**
 * When the optional app lock is engaged. Pure state machine; [AppLock] feeds it the activity
 * lifecycle and the stored settings.
 *
 * - A process start with the lock enabled starts locked.
 * - Leaving the app records the time; coming back after [timeoutMs] or longer locks again.
 * - While locked, leaving and returning changes nothing: the lock stays until an unlock. That
 *   also covers the screen-lock confirmation activity, which itself sends Apollo to the background.
 */
internal class AppLockPolicy(
    private val clock: () -> Long,
) {
    var enabled: Boolean = false
        private set
    var timeoutMs: Long = DEFAULT_TIMEOUT_MS
        private set
    var locked: Boolean = false
        private set
    var initialized: Boolean = false
        private set

    private var backgroundSince: Long? = null
    private var autoPromptPending = false

    /** Applies the stored settings once per process. An enabled lock starts locked. */
    fun initialize(
        enabled: Boolean,
        timeoutMs: Long,
    ) {
        if (initialized) return
        initialized = true
        this.enabled = enabled
        this.timeoutMs = timeoutMs
        if (enabled) lock()
    }

    /** Turning the lock on does not lock the running session; turning it off unlocks. */
    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) unlock()
    }

    fun setTimeout(value: Long) {
        timeoutMs = value
    }

    fun onBackground() {
        if (enabled && !locked) backgroundSince = clock()
    }

    fun onForeground() {
        val since = backgroundSince ?: return
        backgroundSince = null
        if (enabled && clock() - since >= timeoutMs) lock()
    }

    fun unlock() {
        locked = false
        autoPromptPending = false
        backgroundSince = null
    }

    /** True once per lock: the activity shows the system prompt by itself only the first time. */
    fun consumeAutoPrompt(): Boolean {
        if (!locked || !autoPromptPending) return false
        autoPromptPending = false
        return true
    }

    private fun lock() {
        locked = true
        autoPromptPending = true
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L
    }
}
