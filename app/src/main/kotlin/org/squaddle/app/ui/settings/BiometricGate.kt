package org.squaddle.app.ui.settings

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val ALLOWED_AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

/** `true` once a device credential or biometric check passes - no "remember for N minutes"
 * window: every seed reveal re-authenticates, since this is the one screen in the app that
 * shows the user's complete account recovery secret. [androidx.biometric.BiometricPrompt]
 * requires a [FragmentActivity] (not a plain `ComponentActivity`), which is why
 * `MainActivity.kt` is one. */
public suspend fun authenticateForSeedReveal(activity: FragmentActivity): Boolean {
    if (BiometricManager.from(activity).canAuthenticate(ALLOWED_AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS) {
        // Neither biometrics nor a device credential (PIN/pattern/password) is set up at all -
        // there is nothing to gate behind, so this device simply can't protect the reveal.
        return false
    }
    return suspendCancellableCoroutine { continuation: CancellableContinuation<Boolean> ->
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) continuation.resume(true)
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (continuation.isActive) continuation.resume(false)
                }
                override fun onAuthenticationFailed() {
                    // A single failed attempt (e.g. wrong fingerprint) - the prompt itself stays
                    // open for a retry, so this is not terminal; only onAuthenticationError (which
                    // includes user cancellation) or onAuthenticationSucceeded resolve this call.
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock to view your recovery seed")
            // setNegativeButtonText is invalid together with DEVICE_CREDENTIAL in the allowed
            // authenticators (the system itself provides the cancel affordance in that case).
            .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
            .build()
        prompt.authenticate(info)
        continuation.invokeOnCancellation { prompt.cancelAuthentication() }
    }
}
