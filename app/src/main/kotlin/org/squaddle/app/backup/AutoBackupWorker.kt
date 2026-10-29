package org.squaddle.app.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.squaddle.app.appContainer
import java.util.concurrent.TimeUnit

/**
 * Writes to the SAF URI the user picked when enabling auto-backup (android/CLAUDE.md M4b item
 * 7) - needs no prompt per run, so the passphrase has to be readable unattended; see
 * [BackupPassphraseVault]'s own doc comment for why that's still Keystore-at-rest, not a plain
 * setting. A missing passphrase/URI/session (auto-backup turned off between schedule and run,
 * or signed out) is simply a no-op, not a retryable failure - [AutoBackupScheduler.disable]
 * already cancels the periodic request itself in the normal "user turned it off" case; this is
 * only a defensive fallback for the gap between that and a run already in flight.
 */
public class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer()
        val session = container.session ?: return Result.success()
        val settings = AndroidAutoBackupSettingsStore(applicationContext)
        if (!settings.isEnabled()) return Result.success()
        val uriString = settings.uriString() ?: return Result.success()
        val passphrase = AndroidBackupPassphraseVault(applicationContext).load() ?: return Result.success()

        val rooms = container.appLocalStore.allKnownRooms().map { it.toBackupRoomRef() }
        val manager = AndroidBackupManager(applicationContext, session.crypto)
        return when (manager.writeBackup(uriString, passphrase, session.userIdentity.entropy, rooms)) {
            BackupWriteOutcome.Ok -> Result.success()
            is BackupWriteOutcome.Error -> Result.retry()
        }
    }
}

public object AutoBackupScheduler {
    private const val WORK_NAME = "auto-backup"

    public fun enable(context: Context, intervalHours: Int) {
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(intervalHours.toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
            .build()
        // REPLACE, not KEEP: changing the interval (the one setting this screen exposes) must
        // actually take effect on the next schedule, unlike PeriodicSyncScheduler's own interval
        // which is fixed and never user-configurable.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.REPLACE, request)
    }

    public fun disable(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
