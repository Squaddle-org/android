package org.squaddle.app.store

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/** Thin wrapper over the generated [AppLocalDb] queries - mirrors core's own `LocalStore`
 * pattern (`org.squaddle.core.store.LocalStore`), just for this app's local-only bookkeeping
 * instead of protocol data. */
public class AppLocalStore(context: Context) {
    private val db: AppLocalDb = AppLocalDb(createDriver(context))
    private val q = db.appLocalDbQueries

    public fun insertKnownRoom(roomId: ByteArray, displayName: String, relayHost: String, joinedAt: Long, lastActivityAt: Long) {
        q.insertKnownRoom(roomId, displayName, relayHost, joinedAt, lastActivityAt)
    }

    public fun allKnownRooms(): List<Known_rooms> = q.allKnownRooms().executeAsList()

    public fun knownRoom(roomId: ByteArray): Known_rooms? = q.knownRoom(roomId).executeAsOneOrNull()

    public fun touchRoomActivity(roomId: ByteArray, at: Long) {
        q.touchRoomActivity(at, roomId)
    }

    public fun deleteKnownRoom(roomId: ByteArray) {
        q.deleteKnownRoom(roomId)
    }

    public fun upsertReadCursor(roomId: ByteArray, lastReadLogIndex: Long, lastReadOpsCursor: Long) {
        q.upsertReadCursor(roomId, lastReadLogIndex, lastReadOpsCursor)
    }

    public fun readCursorFor(roomId: ByteArray): Read_cursors? = q.readCursorFor(roomId).executeAsOneOrNull()

    private companion object {
        fun createDriver(context: Context): SqlDriver = AndroidSqliteDriver(AppLocalDb.Schema, context, "squaddle_app_local.db")
    }
}
