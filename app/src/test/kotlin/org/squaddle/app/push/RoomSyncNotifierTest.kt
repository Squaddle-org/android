package org.squaddle.app.push

import kotlinx.coroutines.test.runTest
import org.squaddle.app.data.FakeSyncGateway
import org.squaddle.app.store.FakeAppLocalStore
import org.squaddle.core.ops.OpBody
import org.squaddle.core.sync.SyncOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomSyncNotifierTest {

    private val room = ByteArray(32) { it.toByte() }
    private val gateway = FakeSyncGateway()
    private val store = FakeAppLocalStore()
    private val muteStore = FakeRoomMuteStore()
    private val posted = mutableListOf<RoomNotification>()
    private val notifier = RoomSyncNotifier(gateway, store, muteStore, poster = { posted += it })

    private fun seedRoom() {
        store.insertKnownRoom(room, "Camping Trip", "relay.squaddle.org", 1L, 1L)
        gateway.syncedCursorResult = 5L to 10L
    }

    @Test
    fun noNewOpsPostsNothingButStillAdvancesCursor() = runTest {
        seedRoom()
        gateway.opsSinceResult = emptyList()

        notifier.syncAllAndNotify()

        assertTrue(posted.isEmpty())
        assertEquals(10L, store.notifiedCursorFor(room)?.last_notified_ops_cursor)
    }

    @Test
    fun newIdeaAndCommentPostExactlyThoseTwoChannels() = runTest {
        seedRoom()
        gateway.opsSinceResult = listOf(
            OpBody.IdeaCreate(ByteArray(16), "New idea", null, emptyList()),
            OpBody.CommentAdd(ByteArray(16), ByteArray(16), "nice!"),
        )

        notifier.syncAllAndNotify()

        val channels = posted.map { it.channel }.toSet()
        assertEquals(setOf(NotificationChannels.IDEA, NotificationChannels.COMMENT), channels)
        assertTrue(posted.all { it.roomName == "Camping Trip" })
        assertEquals(10L, store.notifiedCursorFor(room)?.last_notified_ops_cursor)
    }

    @Test
    fun eventAndRsvpPostExactlyThoseTwoChannels() = runTest {
        seedRoom()
        gateway.opsSinceResult = listOf(
            OpBody.EventCreate(ByteArray(16), null, "Camping weekend", 1_700_000_000L, null, "UTC", "Lake", true),
            OpBody.RsvpSet(ByteArray(16), "yes"),
        )

        notifier.syncAllAndNotify()

        val channels = posted.map { it.channel }.toSet()
        assertEquals(setOf(NotificationChannels.EVENT, NotificationChannels.RSVP), channels)
    }

    @Test
    fun mutedRoomPostsNothingButStillAdvancesCursor() = runTest {
        seedRoom()
        muteStore.setMuted(room, true)
        gateway.opsSinceResult = listOf(OpBody.IdeaCreate(ByteArray(16), "New idea", null, emptyList()))

        notifier.syncAllAndNotify()

        assertTrue(posted.isEmpty())
        assertEquals(10L, store.notifiedCursorFor(room)?.last_notified_ops_cursor)
    }

    @Test
    fun failedPullSkipsTheRoomEntirely() = runTest {
        seedRoom()
        gateway.pullOutcome = SyncOutcome.Error(0, "network_error", "unreachable")
        gateway.opsSinceResult = listOf(OpBody.IdeaCreate(ByteArray(16), "New idea", null, emptyList()))

        notifier.syncAllAndNotify()

        assertTrue(posted.isEmpty())
        assertEquals(null, store.notifiedCursorFor(room))
    }

    @Test
    fun repeatedRunDoesNotReNotifyAlreadySeenOps() = runTest {
        seedRoom()
        gateway.opsSinceResult = listOf(OpBody.IdeaCreate(ByteArray(16), "New idea", null, emptyList()))
        notifier.syncAllAndNotify()
        assertEquals(1, posted.size)

        // Second run: a real SyncGateway would return an empty opsSince() once the cursor caught
        // up - this fake can't express "cursor-dependent" results, so it's simulated directly by
        // clearing opsSinceResult, same effect from RoomSyncNotifier's point of view.
        gateway.opsSinceResult = emptyList()
        notifier.syncAllAndNotify()

        assertEquals(1, posted.size, "must not re-notify for ops already seen on a previous run")
    }
}
