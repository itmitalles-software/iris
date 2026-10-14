package io.wutz.apollo.applock

import android.os.SystemClock
import io.wutz.apollo.data.local.AuthManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The app lock settings as the Settings screen shows them. */
data class AppLockSettings(
    val enabled: Boolean = false,
    val timeoutSeconds: Int = AppLock.DEFAULT_TIMEOUT_SECONDS,
)

/**
 * Optional app lock (off by default): asks for the fingerprint, face or screen lock when Apollo
 * opens and when it comes back after [AppLockSettings.timeoutSeconds] in the background.
 * The settings live in the server store next to the other app preferences.
 */
object AppLock {
    const val DEFAULT_TIMEOUT_SECONDS = 60

    /** Choices offered in Settings, in seconds. */
    val TIMEOUT_CHOICES_SECONDS = listOf(30, 60, 300, 900)

    private val policy = AppLockPolicy(SystemClock::elapsedRealtime)

    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val _settings = MutableStateFlow(AppLockSettings())
    val settings: StateFlow<AppLockSettings> = _settings.asStateFlow()

    /** Loads the stored settings once the encrypted store is ready; later calls do nothing. */
    @Synchronized
    fun initializeFromSettings() {
        if (policy.initialized) return
        val state = runCatching { AuthManager.serverStore.getLatestState() }.getOrNull() ?: return
        policy.initialize(state.appLockEnabled, state.appLockTimeoutSeconds * 1_000L)
        _settings.value = AppLockSettings(state.appLockEnabled, state.appLockTimeoutSeconds)
        publish()
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        policy.setEnabled(enabled)
        AuthManager.serverStore.update { it.copy(appLockEnabled = enabled) }
        _settings.value = _settings.value.copy(enabled = enabled)
        publish()
    }

    @Synchronized
    fun setTimeoutSeconds(seconds: Int) {
        policy.setTimeout(seconds * 1_000L)
        AuthManager.serverStore.update { it.copy(appLockTimeoutSeconds = seconds) }
        _settings.value = _settings.value.copy(timeoutSeconds = seconds)
    }

    /** The user left the app (not a configuration change). */
    @Synchronized
    fun onBackground() {
        policy.onBackground()
    }

    /** The app is visible again; locks when it stayed away for the timeout or longer. */
    @Synchronized
    fun onForeground() {
        policy.onForeground()
        publish()
    }

    @Synchronized
    fun onUnlocked() {
        policy.unlock()
        publish()
    }

    /** True once per lock, so a cancelled prompt does not reappear on every resume. */
    @Synchronized
    fun consumeAutoPrompt(): Boolean = policy.consumeAutoPrompt()

    private fun publish() {
        _locked.value = policy.locked
    }
}
