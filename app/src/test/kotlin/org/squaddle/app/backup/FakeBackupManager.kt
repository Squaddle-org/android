package org.squaddle.app.backup

import org.squaddle.core.backup.BackupRoomRef

/** In-memory [BackupManager] test double - no Android `Context`/`ContentResolver`. */
public class FakeBackupManager : BackupManager {
    public val writeCalls: MutableList<Pair<String, String>> = mutableListOf() // uriString to passphrase
    public val writtenRooms: MutableList<List<BackupRoomRef>> = mutableListOf()
    public var writeOutcome: BackupWriteOutcome = BackupWriteOutcome.Ok
    public var readOutcome: BackupReadOutcome = BackupReadOutcome.Error("no fake outcome configured for readBackup")

    override suspend fun writeBackup(uriString: String, passphrase: String, entropy: ByteArray, rooms: List<BackupRoomRef>): BackupWriteOutcome {
        writeCalls += uriString to passphrase
        writtenRooms += rooms
        return writeOutcome
    }

    override suspend fun readBackup(uriString: String, passphrase: String): BackupReadOutcome = readOutcome
}
