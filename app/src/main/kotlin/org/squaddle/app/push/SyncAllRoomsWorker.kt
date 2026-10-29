package org.squaddle.app.push

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.squaddle.app.MainActivity
import org.squaddle.app.R
import org.squaddle.app.appContainer
import org.squaddle.app.ui.nav.Route
import org.squaddle.app.ui.nav.toDeepLinkUri

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
 * [androidx.core.app.NotificationManagerCompat]/`PendingIntent`/permission-check plumbing in.
 */
public class SyncAllRoomsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer()
        val session = container.session ?: return Result.success() // signed out / onboarding not finished yet

        val notifier = RoomSyncNotifier(
            gateway = container.syncGateway(),
            appLocalStore = container.appLocalStore,
            muteStore = AndroidRoomMuteStore(applicationContext),
            myUserKey = session.userIdentity.userKey,
            poster = { notifications -> postRoomBatch(notifications) },
        )
        notifier.syncAllAndNotify()
        return Result.success()
    }

    /** One real [android.app.Notification] per item in [notifications], all sharing one
     * `setGroup` key, plus a group-summary notification - without a summary, several individual
     * notifications under the same group can render as separate un-bundled alerts on some OEM
     * skins/older Android versions rather than visually collapsing under the room. */
    private fun postRoomBatch(notifications: List<RoomNotification>) {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return // API 33+ only - below that, POST_NOTIFICATIONS doesn't exist and is implicitly granted.
        }
        val roomIdB32 = notifications.first().roomIdB32
        val roomName = notifications.first().roomName
        val groupKey = "room_$roomIdB32"
        val manager = NotificationManagerCompat.from(applicationContext)

        for (notification in notifications) {
            val text = textFor(notification)
            val built = NotificationCompat.Builder(applicationContext, notification.channel)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentText(text)
                .setAutoCancel(true)
                .setGroup(groupKey)
                // Explicit, not just relying on the platform default (which happens to already be
                // PRIVATE) - this is a privacy-sensitive setting, worth being unambiguous about:
                // no publicVersion is ever set, so a locked device with "hide sensitive content"
                // selected shows nothing but the app's own name/icon for every notification kind.
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentIntent(pendingIntentFor(notification))
                .build()
            manager.notify(notificationId(notification), built)
        }

        val summaryText = applicationContext.getString(R.string.notification_group_summary, notifications.size, roomName)
        val summary = NotificationCompat.Builder(applicationContext, notifications.first().channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentText(summaryText)
            .setGroup(groupKey)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pendingIntentFor(notifications.first()))
            .build()
        manager.notify(summaryId(roomIdB32), summary)
    }

    private fun textFor(notification: RoomNotification): String = when (notification.kind) {
        RoomNotification.Kind.IDEA -> applicationContext.getString(R.string.notification_new_idea, notification.roomName)
        RoomNotification.Kind.COMMENT -> applicationContext.getString(R.string.notification_new_comment, notification.roomName)
        RoomNotification.Kind.EVENT -> applicationContext.getString(R.string.notification_new_event, notification.roomName)
        RoomNotification.Kind.RSVP -> applicationContext.getString(R.string.notification_new_rsvp, notification.roomName)
        RoomNotification.Kind.ROLE_CHANGE -> applicationContext.getString(R.string.notification_role_changed, notification.roomName)
        RoomNotification.Kind.KICKED -> applicationContext.getString(R.string.notification_kicked, notification.roomName)
        RoomNotification.Kind.PENDING_APPROVAL -> applicationContext.getString(R.string.notification_pending_approval, notification.roomName)
    }

    /** `squaddle://room/...` (`Route.kt`) for every kind with somewhere specific to point at;
     * [RoomNotification.Kind.KICKED] opens the app plainly instead (no `.setData(...)` at all) -
     * deep-linking into a room this device is no longer a member of would just show a broken/
     * empty screen, and [Route.RoomsList] is already `MainActivity`'s own default landing spot
     * with zero extra wiring needed. */
    private fun pendingIntentFor(notification: RoomNotification): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java)
        val uri = when (notification.deepLinkKind) {
            RoomNotification.DeepLinkKind.ROOM -> Route.Room(notification.roomIdB32).toDeepLinkUri()
            RoomNotification.DeepLinkKind.IDEA -> notification.itemIdB32?.let { Route.Idea(notification.roomIdB32, it).toDeepLinkUri() }
            RoomNotification.DeepLinkKind.EVENT -> notification.itemIdB32?.let { Route.Event(notification.roomIdB32, it).toDeepLinkUri() }
            RoomNotification.DeepLinkKind.MEMBERS -> Route.Members(notification.roomIdB32).toDeepLinkUri()
            RoomNotification.DeepLinkKind.ROOMS_LIST -> null
        }
        if (uri != null) intent.data = Uri.parse(uri)
        // requestCode 0 for every one of them is fine: PendingIntents with different Intent data
        // are already distinct (FLAG_IMMUTABLE never collapses them into one), and ROOMS_LIST's
        // data-less intents all intentionally collapse into the same "just open the app" PendingIntent.
        return PendingIntent.getActivity(applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun notificationId(notification: RoomNotification): Int =
        (notification.roomIdB32 + notification.channel + notification.itemIdB32.orEmpty()).hashCode()

    private fun summaryId(roomIdB32: String): Int = (roomIdB32 + "_summary").hashCode()
}
