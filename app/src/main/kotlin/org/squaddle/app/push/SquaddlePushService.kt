package org.squaddle.app.push

import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.squaddle.app.appContainer
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * UnifiedPush's own callback service (android/CLAUDE.md: "Push via UnifiedPush ... No FCM").
 * [onMessage]'s [PushMessage.content] is never read - PROTOCOL.md §6's push body is genuinely
 * empty by design (ADR-0013's correction), so there is nothing in it to read; every notification
 * this app ever shows comes from [SyncAllRoomsWorker] decrypting real content after a real pull.
 */
public class SquaddlePushService : PushService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        scope.launch {
            try {
                val container = applicationContext.appContainer()
                if (container.session != null) container.syncGateway().registerPushEndpoint(endpoint.url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best-effort: MainActivity's own cold-start retry loop has no equivalent for
                // push endpoints today, but a later onNewEndpoint call (distributors resend
                // periodically) or the user reopening the app covers recovery in practice.
            }
        }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        // NETWORK/INTERNAL_ERROR: PushRegistrar re-resolves and retries on every cold start
        // (MainActivity's SignedInApp), so no explicit retry is needed here. ACTION_REQUIRED/
        // VAPID_REQUIRED need the user to act in the distributor app itself - nothing this
        // service can do about either from the background.
    }

    override fun onUnregistered(instance: String) {
        scope.launch {
            try {
                val container = applicationContext.appContainer()
                if (container.session != null) container.syncGateway().unregisterPush()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best-effort - nothing to clean up locally if this fails; the relay's own
                // stale registration just stops being used (relay never decrypts/reads it either way).
            }
        }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val request = OneTimeWorkRequestBuilder<SyncAllRoomsWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(applicationContext).enqueue(request)
    }
}
