package org.squaddle.app.store

private fun ByteArray.toKey(): String = joinToString("") { "%02x".format(it) }

/** In-memory [AppLocalStore] test double - no SQLDelight driver, no Android Context. */
public class FakeAppLocalStore : AppLocalStore {
    private val rooms = mutableMapOf<String, Known_rooms>()
    private val cursors = mutableMapOf<String, Read_cursors>()
    private val notifiedCursors = mutableMapOf<String, Notified_cursors>()

    override fun insertKnownRoom(roomId: ByteArray, displayName: String, relayHost: String, joinedAt: Long, lastActivityAt: Long) {
        rooms[roomId.toKey()] = Known_rooms(roomId, displayName, relayHost, joinedAt, lastActivityAt)
    }

    override fun allKnownRooms(): List<Known_rooms> = rooms.values.sortedByDescending { it.last_activity_at }

    override fun knownRoom(roomId: ByteArray): Known_rooms? = rooms[roomId.toKey()]

    override fun touchRoomActivity(roomId: ByteArray, at: Long) {
        rooms[roomId.toKey()]?.let { rooms[roomId.toKey()] = it.copy(last_activity_at = at) }
    }

    override fun deleteKnownRoom(roomId: ByteArray) {
        rooms.remove(roomId.toKey())
    }

    override fun upsertReadCursor(roomId: ByteArray, lastReadLogIndex: Long, lastReadOpsCursor: Long) {
        cursors[roomId.toKey()] = Read_cursors(roomId, lastReadLogIndex, lastReadOpsCursor)
    }

    override fun readCursorFor(roomId: ByteArray): Read_cursors? = cursors[roomId.toKey()]

    override fun upsertNotifiedCursor(roomId: ByteArray, lastNotifiedLogIndex: Long, lastNotifiedOpsCursor: Long) {
        notifiedCursors[roomId.toKey()] = Notified_cursors(roomId, lastNotifiedLogIndex, lastNotifiedOpsCursor)
    }

    override fun notifiedCursorFor(roomId: ByteArray): Notified_cursors? = notifiedCursors[roomId.toKey()]
}
