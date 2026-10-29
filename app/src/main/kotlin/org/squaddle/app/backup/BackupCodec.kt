package org.squaddle.app.backup

import org.squaddle.app.store.Known_rooms
import org.squaddle.core.backup.Argon2Params
import org.squaddle.core.backup.Backup
import org.squaddle.core.backup.BackupContent
import org.squaddle.core.backup.BackupRoomRef
import org.squaddle.core.crypto.Crypto
import org.squaddle.core.encoding.Base32

/**
 * Thin wrapper over `core`'s existing [Backup] object (PROTOCOL.md §7, already fully implemented
 * with test vectors - this file adds nothing cryptographic, only android-side plumbing: a
 * production [Argon2Params] choice and `RoomRef` string parsing [AppLocalStore]'s
 * [org.squaddle.app.store.AppLocalStore] needs that aren't [Backup]'s own concern (it only ever
 * sees the opaque `roomRef` string, same as the wire format's own `0:roomRef` field).
 */
public object BackupCodec {
    /** PROTOCOL.md §1: "Argon2id (`crypto_pwhash`, `OPSLIMIT_MODERATE`, `MEMLIMIT_MODERATE`
     * minimum)" - this app uses exactly the spec's floor, not higher: `MEMLIMIT_SENSITIVE` (1GiB)
     * risks failing outright on a low-RAM device, and `MODERATE` is already the mandated minimum,
     * not a weak default chosen for speed. */
    public val PRODUCTION_PARAMS: Argon2Params = Argon2Params(opsLimit = 3L, memLimit = 268_435_456)

    public fun encode(crypto: Crypto, passphrase: String, entropy: ByteArray, rooms: List<BackupRoomRef>, createdAt: Long): ByteArray =
        Backup.create(crypto, passphrase.encodeToByteArray(), BackupContent(entropy, rooms, createdAt), PRODUCTION_PARAMS)

    public fun decode(crypto: Crypto, passphrase: String, fileBytes: ByteArray): BackupContent? =
        Backup.restore(crypto, passphrase.encodeToByteArray(), fileBytes)
}

/** PROTOCOL.md §3.1: `RoomRef = base32(roomId) "@" relayHost` - [Known_rooms] is this app's own
 * local bookkeeping row (`AppLocalDb.sq`), never the protocol structure itself. */
public fun Known_rooms.toBackupRoomRef(): BackupRoomRef = BackupRoomRef(Base32.encode(room_id) + "@" + relay_host, display_name)

/** `null` if [BackupRoomRef.roomRef] doesn't parse as `base32(roomId)@relayHost` - never throws;
 * a backup file from a future/foreign client that wrote a differently-shaped ref is simply
 * skipped during restore rather than aborting the whole import. */
public fun BackupRoomRef.toRoomIdAndRelayHost(): Pair<ByteArray, String>? {
    val atIndex = roomRef.lastIndexOf('@')
    if (atIndex <= 0 || atIndex == roomRef.length - 1) return null
    val roomId = Base32.decode(roomRef.substring(0, atIndex)) ?: return null
    return roomId to roomRef.substring(atIndex + 1)
}
