package org.squaddle.app.push

import kotlinx.coroutines.CancellationException
import org.squaddle.app.data.SyncGateway
import org.squaddle.app.store.AppLocalStore
import org.squaddle.app.store.Known_rooms
import org.squaddle.core.encoding.Hex
import org.squaddle.core.ops.OpBody
import org.squaddle.core.sync.SyncOutcome

/** What to show, per room, for one notification channel - [NotificationPoster] (the real
 * `NotificationManagerCompat`/permission-check wiring) is the only consumer; kept separate so
 * [RoomSyncNotifier] itself needs no Android `Context`/permission classes and is directly
 * testable with [org.squaddle.app.data.FakeSyncGateway]/[org.squaddle.app.store.FakeAppLocalStore]. */
public data class RoomNotification(public val roomKey: String, public val channel: String, public val roomName: String, public val kind: Kind) {
    public enum class Kind { IDEA, COMMENT, EVENT, RSVP }
}

public fun interface NotificationPoster {
    public fun post(notification: RoomNotification)
}

/**
 * The actual sync-everything-and-notify-what's-new logic [SyncAllRoomsWorker] runs - pulled out
 * of the `CoroutineWorker` itself so it can be unit-tested against fakes without a real Android
 * `Context`/`WorkManager`/`NotificationManager` (see `RoomSyncNotifierTest`). Diffs each known
 * room's newly-stored ops against its own `notified_cursors` row - never `read_cursors`, a
 * different concept (what the *user* has looked at, `AppLocalDb.sq`'s own doc comment) - so a
 * notification is only posted once per piece of new content, never re-shown on a later run.
 */
public class RoomSyncNotifier(
    private val gateway: SyncGateway,
    private val appLocalStore: AppLocalStore,
    private val muteStore: RoomMuteStore,
    private val poster: NotificationPoster,
) {
    public suspend fun syncAllAndNotify() {
        for (room in appLocalStore.allKnownRooms()) {
            try {
                syncOneRoom(room)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best-effort per room (same posture as every other network call in this app) -
                // one unreachable/misbehaving room must not stop the rest from syncing.
            }
        }
    }

    private suspend fun syncOneRoom(room: Known_rooms) {
        val roomId = room.room_id
        if (gateway.pull(roomId) !is SyncOutcome.Ok) return

        // Warms the in-memory epoch-key cache opsSince relies on - see RealSyncGateway.opsSince's
        // own doc comment on why materialize() must run first in the same pass.
        gateway.materialize(roomId)

        val sinceCursor = appLocalStore.notifiedCursorFor(roomId)?.last_notified_ops_cursor ?: 0L
        val newOps = gateway.opsSince(roomId, sinceCursor)
        val currentCursor = gateway.syncedCursor(roomId)

        if (newOps.isNotEmpty() && !muteStore.isMuted(roomId)) {
            postNotifications(roomId, room.display_name, newOps)
        }

        if (currentCursor != null) {
            appLocalStore.upsertNotifiedCursor(roomId, currentCursor.first, currentCursor.second)
        }
    }

    private fun postNotifications(roomId: ByteArray, roomName: String, newOps: List<OpBody>) {
        val roomKey = Hex.encode(roomId)
        if (newOps.any { it is OpBody.IdeaCreate }) {
            poster.post(RoomNotification(roomKey, NotificationChannels.IDEA, roomName, RoomNotification.Kind.IDEA))
        }
        if (newOps.any { it is OpBody.CommentAdd }) {
            poster.post(RoomNotification(roomKey, NotificationChannels.COMMENT, roomName, RoomNotification.Kind.COMMENT))
        }
        if (newOps.any { it is OpBody.EventCreate }) {
            poster.post(RoomNotification(roomKey, NotificationChannels.EVENT, roomName, RoomNotification.Kind.EVENT))
        }
        if (newOps.any { it is OpBody.RsvpSet }) {
            poster.post(RoomNotification(roomKey, NotificationChannels.RSVP, roomName, RoomNotification.Kind.RSVP))
        }
    }
}
