package org.squaddle.app.backup

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.squaddle.app.store.AppLocalStore
import org.squaddle.core.backup.BackupContent
import org.squaddle.core.backup.BackupRoomRef
import org.squaddle.core.crypto.Crypto

public sealed interface BackupWriteOutcome {
    public data object Ok : BackupWriteOutcome
    public data class Error(public val message: String) : BackupWriteOutcome
}

public sealed interface BackupReadOutcome {
    public data class Ok(public val content: BackupContent) : BackupReadOutcome
    public data class Error(public val message: String) : BackupReadOutcome
}

/**
 * SAF file I/O around [BackupCodec] - deliberately takes/returns plain `String` URIs (a `uri`'s
 * `toString()`), not `android.net.Uri`, at this interface boundary: same "stay testable in a
 * plain JUnit test with no Android framework/Robolectric needed" reasoning
 * [org.squaddle.app.data.InviteLink] already documents for itself. [AndroidBackupManager] is the
 * only place that ever calls [Uri.parse].
 */
public interface BackupManager {
    public suspend fun writeBackup(uriString: String, passphrase: String, entropy: ByteArray, rooms: List<BackupRoomRef>): BackupWriteOutcome
    public suspend fun readBackup(uriString: String, passphrase: String): BackupReadOutcome
}

public class AndroidBackupManager(private val context: Context, private val crypto: Crypto) : BackupManager {
    override suspend fun writeBackup(uriString: String, passphrase: String, entropy: ByteArray, rooms: List<BackupRoomRef>): BackupWriteOutcome =
        withContext(Dispatchers.IO) {
            try {
                val bytes = BackupCodec.encode(crypto, passphrase, entropy, rooms, System.currentTimeMillis() / 1000)
                val output = context.contentResolver.openOutputStream(Uri.parse(uriString), "w")
                    ?: return@withContext BackupWriteOutcome.Error("could not open the chosen file for writing")
                output.use { it.write(bytes) }
                BackupWriteOutcome.Ok
            } catch (e: Exception) {
                BackupWriteOutcome.Error(e.message ?: "backup failed")
            }
        }

    override suspend fun readBackup(uriString: String, passphrase: String): BackupReadOutcome = withContext(Dispatchers.IO) {
        try {
            val input = context.contentResolver.openInputStream(Uri.parse(uriString))
                ?: return@withContext BackupReadOutcome.Error("could not open the chosen file for reading")
            val bytes = input.use { it.readBytes() }
            val content = BackupCodec.decode(crypto, passphrase, bytes)
                ?: return@withContext BackupReadOutcome.Error("wrong passphrase, or not a Squaddle backup file")
            BackupReadOutcome.Ok(content)
        } catch (e: Exception) {
            BackupReadOutcome.Error(e.message ?: "restore failed")
        }
    }
}
