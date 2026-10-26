package com.massivex.android.wallet

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import com.massivex.android.R
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Platform BiometricPrompt on API30+. No new support library and no unsupported legacy combinations. */
class AndroidAuthentication(private val activity: Activity, private val secrets: AndroidSecrets) : OwnerAuthentication {
    private var pending: CancellationSignal? = null
    private var epoch = 0L
    private val clock = Clock { SystemClock.elapsedRealtime() }
    override fun cancel() { epoch++; pending?.cancel(); pending = null }
    override suspend fun approve(action: Action): Approval {
        if (Build.VERSION.SDK_INT < 30) fail(WalletError.UNSUPPORTED)
        secrets.checkCapability()
        requireWallet(pending == null, WalletError.BUSY)
        val types = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        requireWallet(activity.getSystemService(BiometricManager::class.java).canAuthenticate(types) == BiometricManager.BIOMETRIC_SUCCESS, WalletError.AUTH)
        val generation = epoch
        val signal = CancellationSignal(); pending = signal
        try {
            return withTimeoutOrNull(60000) {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { signal.cancel() }
                    BiometricPrompt.Builder(activity)
                        .setTitle(activity.getString(R.string.authorize))
                        .setSubtitle(activity.getString(when (action.purpose) {
                            Purpose.ADD -> R.string.auth_add
                            Purpose.REMOVE -> R.string.auth_remove
                            Purpose.REVEAL -> R.string.auth_reveal
                            Purpose.COPY -> R.string.auth_copy
                            Purpose.SIGN -> R.string.auth_sign
                        }))
                        .setAllowedAuthenticators(types).build()
                        .authenticate(signal, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                if (!continuation.isActive) return
                                try {
                                    requireWallet(generation == epoch && !activity.isFinishing, WalletError.CHANGED)
                                    secrets.proveAuthorization()
                                    continuation.resume(Approval(action, clock, clock.now()) { generation == epoch && !activity.isFinishing })
                                } catch (e: Exception) { continuation.resumeWithException(e) }
                            }
                            override fun onAuthenticationError(code: Int, message: CharSequence) {
                                if (continuation.isActive) continuation.resumeWithException(WalletException(
                                    if (code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED || code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED) WalletError.CANCELLED else WalletError.AUTH))
                            }
                        })
                }
            } ?: fail(WalletError.EXPIRED)
        } finally { signal.cancel(); pending = null }
    }
}
