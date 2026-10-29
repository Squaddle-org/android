package org.squaddle.app.push

private fun ByteArray.toKey(): String = joinToString("") { "%02x".format(it) }

/** In-memory [RoomMuteStore] test double - no Android `Context`/`SharedPreferences`. */
public class FakeRoomMuteStore : RoomMuteStore {
    private val muted = mutableSetOf<String>()

    override fun isMuted(roomId: ByteArray): Boolean = roomId.toKey() in muted

    override fun setMuted(roomId: ByteArray, muted: Boolean) {
        if (muted) this.muted += roomId.toKey() else this.muted -= roomId.toKey()
    }
}
