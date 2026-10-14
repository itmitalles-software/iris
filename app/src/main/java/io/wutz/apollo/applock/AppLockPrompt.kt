package io.wutz.apollo.applock

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.wutz.apollo.R
import kotlinx.coroutines.launch

/**
 * Shows the system authentication for [AppLock] on behalf of [activity], without extra libraries:
 * - Android 11+: framework [BiometricPrompt] that also accepts the PIN, pattern or password.
 * - Android 10: the same, through the older device-credential switch.
 * - Android 9: [BiometricPrompt] with a "Use screen lock" button; devices without usable
 *   biometrics go straight to the screen-lock confirmation.
 * - Android 8: the screen-lock confirmation of [KeyguardManager].
 *
 * Create it while the activity is being constructed: the screen-lock confirmation needs an
 * activity-result launcher, which can only be registered before the activity starts.
 */
class AppLockPrompt(
    private val activity: ComponentActivity,
) {
    private var inFlight = false

    private val confirmCredential =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            finish(success = result.resultCode == Activity.RESULT_OK)
        }

    /** Shows the prompt by itself once per lock while the activity is in front. */
    fun observe() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                AppLock.locked.collect { locked ->
                    if (locked && AppLock.consumeAutoPrompt()) authenticate()
                }
            }
        }
    }

    /** Asks for the fingerprint, face or screen lock; a success unlocks [AppLock]. */
    fun authenticate() {
        if (inFlight || !AppLock.locked.value) return
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isDeviceSecure) {
            // Without a screen lock there is nothing to check against; the setting cannot be turned
            // on in that state, and a lock that can never open would only shut the user out.
            AppLock.onUnlocked()
            return
        }
        inFlight = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            showBiometricPrompt(keyguard)
        } else {
            confirmScreenLock(keyguard)
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun showBiometricPrompt(keyguard: KeyguardManager) {
        val builder =
            BiometricPrompt
                .Builder(activity)
                .setTitle(activity.getString(R.string.app_lock_prompt_title))
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                builder.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
            }

            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> {
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)
            }

            else -> {
                builder.setNegativeButton(
                    activity.getString(R.string.app_lock_use_screen_lock),
                    activity.mainExecutor,
                ) { _, _ -> confirmScreenLock(keyguard) }
            }
        }
        val noBiometricsErrors =
            setOf(
                BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT,
                BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS,
            )
        val callback =
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    finish(success = true)
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && errorCode in noBiometricsErrors) {
                        confirmScreenLock(keyguard)
                    } else {
                        finish(success = false)
                    }
                }
            }
        try {
            builder.build().authenticate(CancellationSignal(), activity.mainExecutor, callback)
        } catch (_: RuntimeException) {
            // Some Android 9 builds reject the prompt outright when no biometric hardware exists.
            confirmScreenLock(keyguard)
        }
    }

    private fun confirmScreenLock(keyguard: KeyguardManager) {
        val title = activity.getString(R.string.app_lock_prompt_title)

        @Suppress("DEPRECATION")
        val intent = keyguard.createConfirmDeviceCredentialIntent(title, null)
        if (intent == null) {
            // No secure lock screen (any more): nothing to confirm against.
            finish(success = true)
            return
        }
        inFlight = true
        confirmCredential.launch(intent)
    }

    private fun finish(success: Boolean) {
        inFlight = false
        if (success) AppLock.onUnlocked()
    }
}
