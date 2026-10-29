package org.squaddle.app.push

import android.content.Context
import org.squaddle.core.encoding.Hex

/** Per-room notification mute, checked by [SyncAllRoomsWorker] before posting any notification
 * for a room. Not secret, so a plain SharedPreferences store with no encryption at rest - same
 * shape as [org.squaddle.app.settings.DeveloperSettingsStore]. */
public interface RoomMuteStore {
    public fun isMuted(roomId: ByteArray): Boolean
    public fun setMuted(roomId: ByteArray, muted: Boolean)
}

public class AndroidRoomMuteStore(context: Context) : RoomMuteStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun isMuted(roomId: ByteArray): Boolean = prefs.getBoolean(keyFor(roomId), false)

    override fun setMuted(roomId: ByteArray, muted: Boolean) {
        prefs.edit().apply {
            if (muted) putBoolean(keyFor(roomId), true) else remove(keyFor(roomId))
        }.apply()
    }

    private fun keyFor(roomId: ByteArray): String = Hex.encode(roomId)

    private companion object {
        const val PREFS_NAME = "room_mute"
    }
}
