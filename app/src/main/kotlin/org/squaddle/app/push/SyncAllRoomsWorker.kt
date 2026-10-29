package org.squaddle.app.push

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.squaddle.app.R
import org.squaddle.app.appContainer

/**
 * The single sync-everything job both triggers run (M4b item 5): a UnifiedPush message
 * ([SquaddlePushService.onMessage]) and the periodic no-distributor fallback
 * ([PeriodicSyncScheduler]) both just enqueue this with no room-specific input data. That's not
 * a simplification of scope - it's required by PROTOCOL.md §6's push body being genuinely empty
 * (ADR-0013 correction): a push names no room, so "sync everything, see what's new" is the only
 * correct response to one arriving.
 *
 * Thin Android shell over [RoomSyncNotifier] - the actual diff/decide-what's-new logic is there
 * (and unit-tested there, against fakes) so this class only has to wire real
 * [androidx.core.app.NotificationManagerCompat]/permission-check plumbing in.
 */
public class SyncAllRoomsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer()
        if (container.session == null) return Result.success() // signed out / onboarding not finished yet

        val notifier = RoomSyncNotifier(
            gateway = container.syncGateway(),
            appLocalStore = container.appLocalStore,
            muteStore = AndroidRoomMuteStore(applicationContext),
            poster = { notification -> post(notification) },
        )
        notifier.syncAllAndNotify()
        return Result.success()
    }

    private fun post(notification: RoomNotification) {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return // API 33+ only - below that, POST_NOTIFICATIONS doesn't exist and is implicitly granted.
        }
        val text = when (notification.kind) {
            RoomNotification.Kind.IDEA -> applicationContext.getString(R.string.notification_new_idea, notification.roomName)
            RoomNotification.Kind.COMMENT -> applicationContext.getString(R.string.notification_new_comment, notification.roomName)
            RoomNotification.Kind.EVENT -> applicationContext.getString(R.string.notification_new_event, notification.roomName)
            RoomNotification.Kind.RSVP -> applicationContext.getString(R.string.notification_new_rsvp, notification.roomName)
        }
        val built = NotificationCompat.Builder(applicationContext, notification.channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify((notification.roomKey + notification.channel).hashCode(), built)
    }
}
