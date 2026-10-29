package org.squaddle.app.referrer

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse
import com.android.installreferrer.api.InstallReferrerStateListener
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * `play` flavor's version of this class (android/CLAUDE.md M4b item 9 - the `foss` flavor's
 * own copy of the same class, under its own source set, always returns `null`). Deferred deep
 * links: a user who tapped a Squaddle invite link on the Play Store listing (before the app was
 * even installed) has that link preserved by Play and readable here, once, right after first
 * launch - Google's own docs: "the install referrer is available for 90 days and won't change
 * unless the app is reinstalled... call the API only once, during the first execution." This app
 * calls it once per cold start (`MainActivity.kt`'s `SignedInApp`, same `LaunchedEffect(Unit)`
 * cadence as `registerDevice()`) rather than literally once-ever - cheap and idempotent to repeat
 * since the value itself never changes, and simpler than a persisted "already consumed" flag.
 */
public class InstallReferrerChecker {
    /** `null` on any failure (API unsupported, connection failed, no referrer set) - never
     * throws. The caller treats this exactly like a pasted clipboard link: parse it as an invite
     * link, and do nothing if it isn't one (`MainActivity.kt`'s `SignedInApp`). */
    public suspend fun referrerUrl(context: Context): String? = suspendCancellableCoroutine { continuation: CancellableContinuation<String?> ->
        val client = InstallReferrerClient.newBuilder(context).build()
        client.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                val referrer = if (responseCode == InstallReferrerResponse.OK) {
                    try {
                        client.installReferrer.installReferrer
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
                client.endConnection()
                if (continuation.isActive) continuation.resume(referrer)
            }

            override fun onInstallReferrerServiceDisconnected() {
                // No retry: a disconnect before setup finished just means this one check comes
                // back null, same as any other failure - the next cold start tries again anyway.
            }
        })
        continuation.invokeOnCancellation { client.endConnection() }
    }
}
