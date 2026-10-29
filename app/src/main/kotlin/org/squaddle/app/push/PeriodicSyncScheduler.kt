package org.squaddle.app.push

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** M4b item 5's "periodic fallback (e.g. 6h) when no distributor is installed" - the only way a
 * device with no UnifiedPush distributor (ntfy or otherwise) ever learns about new content
 * short of opening the app. [PushRegistrar] is the only caller: it enables this whenever
 * [org.unifiedpush.android.connector.UnifiedPush.resolveDefaultDistributor] comes back
 * `NoneAvailable`/`ToSelect`-without-an-activity, and disables it the moment a real registration
 * succeeds (push delivery makes the 6h poll redundant, not just wasteful). */
public object PeriodicSyncScheduler {
    private const val WORK_NAME = "periodic-sync-fallback"
    private const val INTERVAL_HOURS = 6L

    public fun enable(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncAllRoomsWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        // KEEP, not REPLACE: re-resolving "no distributor" on every cold start (PushRegistrar
        // runs there) must not reset this request's own retry backoff/next-run time each time.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    public fun disable(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
