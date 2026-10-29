package org.squaddle.app.push

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.squaddle.app.data.SyncGateway
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.ResolvedDistributor

/**
 * Resolves and registers a UnifiedPush distributor on every cold start - same idempotent,
 * best-effort, no-persisted-pending-flag posture as [org.squaddle.app.MainActivity]'s own
 * `registerDevice()` retry (`SignedInApp`'s doc comment): cheap to repeat, safe to repeat.
 *
 * [org.unifiedpush.android.connector.UnifiedPush.register] itself is fire-and-forget (it just
 * talks to the distributor; the real outcome arrives later via [SquaddlePushService]'s
 * callbacks), so there's nothing to await here beyond fetching the VAPID key first.
 */
public object PushRegistrar {
    public fun ensureRegistered(context: Context, gateway: SyncGateway) {
        when (val resolved = UnifiedPush.resolveDefaultDistributor(context)) {
            is ResolvedDistributor.Found -> {
                UnifiedPush.saveDistributor(context, resolved.packageName)
                PeriodicSyncScheduler.disable(context)
                CoroutineScope(Dispatchers.IO).launch { registerWithRelay(context, gateway) }
            }
            ResolvedDistributor.ToSelect -> {
                val activity = context as? Activity
                if (activity != null) {
                    UnifiedPush.tryUseDefaultDistributor(activity) { success ->
                        if (success) {
                            PeriodicSyncScheduler.disable(context)
                            CoroutineScope(Dispatchers.IO).launch { registerWithRelay(context, gateway) }
                        } else {
                            PeriodicSyncScheduler.enable(context)
                        }
                    }
                } else {
                    // Can't launch the picker UI without an Activity - fall back to polling
                    // rather than silently doing nothing; the next cold start retries resolution.
                    PeriodicSyncScheduler.enable(context)
                }
            }
            ResolvedDistributor.NoneAvailable -> PeriodicSyncScheduler.enable(context)
        }
    }

    /** `true` if there is genuinely no UnifiedPush distributor installed at all - the one case
     * the in-app "install ntfy" hint (`SignedInApp`) should show, as opposed to `ToSelect`
     * (something IS installed, just not chosen yet). */
    public fun noDistributorInstalled(context: Context): Boolean = UnifiedPush.getDistributors(context).isEmpty()

    private suspend fun registerWithRelay(context: Context, gateway: SyncGateway) {
        val vapid = gateway.vapidPublicKey()
        UnifiedPush.register(context, vapid = vapid)
    }
}
