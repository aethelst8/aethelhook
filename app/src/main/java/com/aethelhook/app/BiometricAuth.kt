package com.aethelhook.app

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

// Fingerprint (or face, whatever the device offers) with automatic fallback to the
// phone's own screen-lock PIN/pattern/password - not a separate secret AethelHook has
// to manage or store. DEVICE_CREDENTIAL alone can't be combined with a custom negative
// button, so PromptInfo deliberately doesn't set one; the system supplies its own
// "use PIN" / cancel affordance for this authenticator combination.
private const val REVEAL_AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

// Gates revealing a masked sensitive value (LAN/Tailscale IP, API token) behind
// device auth. Every call re-prompts - there's no "unlocked for N minutes" state -
// so hiding a field again always requires a fresh prompt to re-reveal it. If the
// device has no secure lock screen configured at all (canAuthenticate fails), there's
// nothing to gate against, so onSuccess runs directly rather than blocking the user
// with a security requirement the OS itself doesn't offer.
fun requestRevealAuth(ctx: Context, label: String, onSuccess: () -> Unit) {
    val activity = ctx.findFragmentActivity() ?: return onSuccess()
    val manager = BiometricManager.from(activity)
    if (manager.canAuthenticate(REVEAL_AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS) {
        onSuccess()
        return
    }
    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Reveal $label")
        .setSubtitle("Confirm it's you with your fingerprint or device PIN")
        .setAllowedAuthenticators(REVEAL_AUTHENTICATORS)
        .build()
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }
        }
    )
    prompt.authenticate(promptInfo)
}
