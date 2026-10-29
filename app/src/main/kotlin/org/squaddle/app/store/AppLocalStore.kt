package org.squaddle.app.store

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/** Abstraction over [AndroidAppLocalStore] so ViewModels can depend on this instead of the
 * concrete SQLDelight-backed class - same "depend on an interface" shape
 * [org.squaddle.app.crypto.IdentityVault]/[org.squaddle.app.data.SyncGateway] already use. A
 * [org.squaddle.app.store.FakeAppLocalStore] test double backs ViewModel unit tests. */
public interface AppLocalStore {
    public fun insertKnownRoom(roomId: ByteArray, displayName: String, relayHost: String, joinedAt: Long, lastActivityAt: Long)
    public fun allKnownRooms(): List<Known_rooms>
    public fun knownRoom(roomId: ByteArray): Known_rooms?
    public fun touchRoomActivity(roomId: ByteArray, at: Long)
    public fun deleteKnownRoom(roomId: ByteArray)
    public fun upsertReadCursor(roomId: ByteArray, lastReadLogIndex: Long, lastReadOpsCursor: Long)
    public fun readCursorFor(roomId: ByteArray): Read_cursors?
    public fun upsertNotifiedCursor(roomId: ByteArray, lastNotifiedLogIndex: Long, lastNotifiedOpsCursor: Long)
    public fun notifiedCursorFor(roomId: ByteArray): Notified_cursors?
}

/** Thin wrapper over the generated [AppLocalDb] queries - mirrors core's own `LocalStore`
 * pattern (`org.squaddle.core.store.LocalStore`), just for this app's local-only bookkeeping
 * instead of protocol data. */
public class AndroidAppLocalStore(context: Context) : AppLocalStore {
    private val db: AppLocalDb = AppLocalDb(createDriver(context))
    private val q = db.appLocalDbQueries

    override fun insertKnownRoom(roomId: ByteArray, displayName: String, relayHost: String, joinedAt: Long, lastActivityAt: Long) {
        q.insertKnownRoom(roomId, displayName, relayHost, joinedAt, lastActivityAt)
    }

    override fun allKnownRooms(): List<Known_rooms> = q.allKnownRooms().executeAsList()

    override fun knownRoom(roomId: ByteArray): Known_rooms? = q.knownRoom(roomId).executeAsOneOrNull()

    override fun touchRoomActivity(roomId: ByteArray, at: Long) {
        q.touchRoomActivity(at, roomId)
    }

    override fun deleteKnownRoom(roomId: ByteArray) {
        q.deleteKnownRoom(roomId)
    }

    override fun upsertReadCursor(roomId: ByteArray, lastReadLogIndex: Long, lastReadOpsCursor: Long) {
        q.upsertReadCursor(roomId, lastReadLogIndex, lastReadOpsCursor)
    }

    override fun readCursorFor(roomId: ByteArray): Read_cursors? = q.readCursorFor(roomId).executeAsOneOrNull()

    override fun upsertNotifiedCursor(roomId: ByteArray, lastNotifiedLogIndex: Long, lastNotifiedOpsCursor: Long) {
        q.upsertNotifiedCursor(roomId, lastNotifiedLogIndex, lastNotifiedOpsCursor)
    }

    override fun notifiedCursorFor(roomId: ByteArray): Notified_cursors? = q.notifiedCursorFor(roomId).executeAsOneOrNull()

    private companion object {
        fun createDriver(context: Context): SqlDriver = AndroidSqliteDriver(AppLocalDb.Schema, context, "squaddle_app_local.db")
    }
}
