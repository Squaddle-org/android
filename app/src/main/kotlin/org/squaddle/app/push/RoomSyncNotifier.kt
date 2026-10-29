package org.squaddle.app.push

import kotlinx.coroutines.CancellationException
import org.squaddle.app.data.AuthoredOp
import org.squaddle.app.data.SyncGateway
import org.squaddle.app.store.AppLocalStore
import org.squaddle.app.store.Known_rooms
import org.squaddle.core.encoding.Base32
import org.squaddle.core.encoding.CborValue
import org.squaddle.core.encoding.Hex
import org.squaddle.core.membership.EntryType
import org.squaddle.core.membership.LogEntry
import org.squaddle.core.membership.Role
import org.squaddle.core.ops.OpBody
import org.squaddle.core.sync.SyncOutcome

/** What to show, for one item - [NotificationPoster] (the real `NotificationManagerCompat`/
 * `PendingIntent`/permission-check wiring, `SyncAllRoomsWorker.kt`) is the only consumer; kept
 * separate so [RoomSyncNotifier] itself needs no Android `Context`/permission classes and is
 * directly testable with [org.squaddle.app.data.FakeSyncGateway]/
 * [org.squaddle.app.store.FakeAppLocalStore]. [itemIdB32]/[deepLinkKind] together say what tapping
 * this notification should open - `null`/[DeepLinkKind.ROOM] for the two kinds ([Kind.KICKED],
 * [Kind.ROLE_CHANGE]) that don't name a specific idea/event. */
public data class RoomNotification(
    public val roomIdB32: String,
    public val roomName: String,
    public val channel: String,
    public val kind: Kind,
    public val itemIdB32: String? = null,
    public val deepLinkKind: DeepLinkKind = DeepLinkKind.ROOM,
) {
    public enum class Kind { IDEA, COMMENT, EVENT, RSVP, ROLE_CHANGE, KICKED, PENDING_APPROVAL }
    public enum class DeepLinkKind { ROOM, IDEA, EVENT, MEMBERS, ROOMS_LIST }
}

public fun interface NotificationPoster {
    /** Called once per room, only when [notifications] is non-empty - lets the real
     * implementation post every item notification plus one group-summary notification for the
     * room together, with the full batch in hand (`SyncAllRoomsWorker.kt`'s own doc comment on
     * why this is a batch callback and not one call per item). */
    public fun post(notifications: List<RoomNotification>)
}

/**
 * The actual sync-everything-and-notify-what's-new logic [SyncAllRoomsWorker] runs - pulled out
 * of the `CoroutineWorker` itself so it can be unit-tested against fakes without a real Android
 * `Context`/`WorkManager`/`NotificationManager` (see `RoomSyncNotifierTest`). Diffs each known
 * room's newly-stored ops AND membership-log entries against its own `notified_cursors` row -
 * never `read_cursors`, a different concept (what the *user* has looked at, `AppLocalDb.sq`'s own
 * doc comment) - so a notification is only posted once per piece of new content, never re-shown
 * on a later run. Never notifies for anything *I* did, on any of my devices (same `author` user
 * key either way) - only for ops/entries authored by someone else.
 */
public class RoomSyncNotifier(
    private val gateway: SyncGateway,
    private val appLocalStore: AppLocalStore,
    private val muteStore: RoomMuteStore,
    private val myUserKey: ByteArray,
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

        // Warms the in-memory epoch-key cache opsSince/materialize both rely on.
        val materialization = gateway.materialize(roomId)

        val notifiedCursor = appLocalStore.notifiedCursorFor(roomId)
        val sinceOpsCursor = notifiedCursor?.last_notified_ops_cursor ?: 0L
        val sinceLogIndex = notifiedCursor?.last_notified_log_index ?: -1L

        val newOps = gateway.opsSince(roomId, sinceOpsCursor).filterNot { it.author.contentEquals(myUserKey) }
        val newLogEntries = gateway.logEntriesSince(roomId, sinceLogIndex).filterNot { it.author.contentEquals(myUserKey) }
        val currentCursor = gateway.syncedCursor(roomId)

        if ((newOps.isNotEmpty() || newLogEntries.isNotEmpty()) && !muteStore.isMuted(roomId)) {
            val roomIdB32 = Base32.encode(roomId)
            val notifications = buildList {
                addAll(opNotifications(roomIdB32, room.display_name, newOps, materialization.state))
                addAll(logNotifications(roomId, roomIdB32, room.display_name, newLogEntries))
            }
            if (notifications.isNotEmpty()) poster.post(notifications)
        }

        if (currentCursor != null) {
            appLocalStore.upsertNotifiedCursor(roomId, currentCursor.first, currentCursor.second)
        }
    }

    private fun opNotifications(
        roomIdB32: String,
        roomName: String,
        newOps: List<AuthoredOp>,
        state: org.squaddle.core.ops.RoomState,
    ): List<RoomNotification> {
        val myIdeaIds = state.ideas.values.filter { it.createdBy.contentEquals(myUserKey) }.map { it.id.toKey() }.toSet()
        val myCommentedIdeaIds = state.comments.values.filter { it.author.contentEquals(myUserKey) }.map { it.ideaId.toKey() }.toSet()
        val myEventIds = state.events.values.filter { it.createdBy.contentEquals(myUserKey) }.map { it.id.toKey() }.toSet()

        return newOps.mapNotNull { (_, body) ->
            when (body) {
                is OpBody.IdeaCreate -> RoomNotification(
                    roomIdB32, roomName, NotificationChannels.IDEA, RoomNotification.Kind.IDEA,
                    Base32.encode(body.id), RoomNotification.DeepLinkKind.IDEA,
                )
                // "a new comment on an idea I created or commented on" - never for every comment
                // in the room (that would be most comments, for an active room).
                is OpBody.CommentAdd -> if (body.ideaId.toKey() in myIdeaIds || body.ideaId.toKey() in myCommentedIdeaIds) {
                    RoomNotification(
                        roomIdB32, roomName, NotificationChannels.COMMENT, RoomNotification.Kind.COMMENT,
                        Base32.encode(body.ideaId), RoomNotification.DeepLinkKind.IDEA,
                    )
                } else {
                    null
                }
                is OpBody.EventCreate -> RoomNotification(
                    roomIdB32, roomName, NotificationChannels.EVENT, RoomNotification.Kind.EVENT,
                    Base32.encode(body.id), RoomNotification.DeepLinkKind.EVENT,
                )
                // "RSVP changes on my events" - never for someone RSVPing to an event I didn't create.
                is OpBody.RsvpSet -> if (body.eventId.toKey() in myEventIds) {
                    RoomNotification(
                        roomIdB32, roomName, NotificationChannels.RSVP, RoomNotification.Kind.RSVP,
                        Base32.encode(body.eventId), RoomNotification.DeepLinkKind.EVENT,
                    )
                } else {
                    null
                }
                else -> null
            }
        }
    }

    /** Membership-log entries don't carry a target's role transition directly decodable without
     * re-running the whole fold (PROTOCOL.md §3.3) - this reads just the one field each relevant
     * [EntryType] actually needs (always body field 0, the target user, same position every
     * type uses it) rather than re-deriving [org.squaddle.core.membership.MembershipState]'s full
     * fold a second time here. */
    private suspend fun logNotifications(roomId: ByteArray, roomIdB32: String, roomName: String, newLogEntries: List<LogEntry>): List<RoomNotification> {
        val result = mutableListOf<RoomNotification>()
        var currentState: org.squaddle.core.membership.MembershipState? = null
        suspend fun stateNow() = currentState ?: gateway.membershipHistory(roomId).current.also { currentState = it }

        for (entry in newLogEntries) {
            when (entry.type) {
                EntryType.APPROVE, EntryType.REJECT, EntryType.SET_ROLE, EntryType.REMOVE, EntryType.TRANSFER_OWNER -> {
                    val target = (entry.body.entries[0L] as? CborValue.Bytes)?.value ?: continue
                    if (!target.contentEquals(myUserKey)) continue
                    val kind = if (entry.type == EntryType.REMOVE) RoomNotification.Kind.KICKED else RoomNotification.Kind.ROLE_CHANGE
                    val deepLink = if (kind == RoomNotification.Kind.KICKED) RoomNotification.DeepLinkKind.ROOMS_LIST else RoomNotification.DeepLinkKind.ROOM
                    result += RoomNotification(roomIdB32, roomName, NotificationChannels.MEMBERSHIP, kind, deepLinkKind = deepLink)
                }
                EntryType.JOIN -> {
                    // "pending approvals (admins only)" - only if I'm currently admin/owner, and
                    // only for a join that's still pending (an open/no-approval invite needs no
                    // one's action, so it isn't notification-worthy here).
                    val myRole = stateNow().roleOf(myUserKey)
                    if (myRole != Role.ADMIN && myRole != Role.OWNER) continue
                    if (stateNow().roleOf(entry.author) != Role.PENDING) continue
                    result += RoomNotification(roomIdB32, roomName, NotificationChannels.PENDING_APPROVAL, RoomNotification.Kind.PENDING_APPROVAL, deepLinkKind = RoomNotification.DeepLinkKind.MEMBERS)
                }
                else -> {}
            }
        }
        return result
    }
}

private fun ByteArray.toKey(): String = Hex.encode(this)
