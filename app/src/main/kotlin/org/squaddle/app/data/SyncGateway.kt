package org.squaddle.app.data

import org.squaddle.core.membership.Role
import org.squaddle.core.ops.MembershipHistory
import org.squaddle.core.ops.RoomState
import org.squaddle.core.sync.PushOutcome
import org.squaddle.core.sync.SyncOutcome

/** [state] is core's own materialized content-plane view; [ideaCreatedAtLamport] (keyed the same
 * hex-string way [RoomState]'s own maps are) lets the Room screen sort by recency - [RoomState.Idea]
 * carries no creation timestamp, so this is captured while decrypting ops to fold, not reread
 * from core afterwards (see [SyncGateway.materialize]'s doc comment for why). */
public data class RoomMaterialization(
    public val state: RoomState,
    public val ideaCreatedAtLamport: Map<String, Long>,
)

/**
 * The single app<->core integration seam - every ViewModel in this app depends on this interface,
 * never on [org.squaddle.core.sync.SyncClient] directly, which is what makes "ViewModel unit tests
 * against a fake sync client" possible at all ([SyncClient] is a concrete class, not an interface).
 * Scoped exactly to what M4a's screens need - no event/rsvp/profile methods yet (M4b).
 */
public interface SyncGateway {
    public suspend fun registerDevice(): PushOutcome

    /** Mints a room id+nonce, calls core's `createRoom`, and records it in the local
     * known-rooms registry (room names/registry aren't a protocol concept - see `AppLocalDb.sq`). */
    public suspend fun createRoom(roomName: String, relayHost: String): Result<ByteArray>

    public suspend fun pull(room: ByteArray): SyncOutcome

    /** Launches core's `connectLiveUpdates` (a real WebSocket subscription) and invokes
     * [onChanged] whenever a short local-store-only poll (no network) observes new data - see the
     * plan's own note on why this is polled rather than pushed directly from core. Suspends for
     * as long as the caller's coroutine keeps it running; cancel the job to disconnect. */
    public suspend fun connectLiveUpdates(room: ByteArray, onChanged: () -> Unit)

    /** Replays the local log+ops into a [RoomMaterialization] - no network. Mirrors
     * `core/cli`'s own `materializeRoomState`/`certResolverFromLog` (`cli/src/main/kotlin/org/squaddle/cli/Main.kt:243-275`),
     * a third legitimate call site of core's already-public replay/decrypt/fold functions. */
    public suspend fun materialize(room: ByteArray): RoomMaterialization

    public suspend fun membershipHistory(room: ByteArray): MembershipHistory

    public suspend fun createIdea(room: ByteArray, title: String, desc: String?): Boolean
    public suspend fun editIdea(room: ByteArray, id: ByteArray, field: String, value: Any?): Boolean
    public suspend fun deleteIdea(room: ByteArray, id: ByteArray): Boolean
    public suspend fun vote(room: ByteArray, ideaId: ByteArray, value: Int): Boolean
    public suspend fun addComment(room: ByteArray, ideaId: ByteArray, text: String): Boolean
    public suspend fun deleteComment(room: ByteArray, id: ByteArray): Boolean

    /** REMOVE + an immediate `autoRotate` right after - not left for a lazy `rotation_required`
     * bounce, mirroring `core`'s own `EndToEndIntegrationTest` kick sequence exactly. */
    public suspend fun kick(room: ByteArray, target: ByteArray): PushOutcome

    /** [role] is `"admin"` or `"member"` (§3.3 row 8) - owner only. */
    public suspend fun setRole(room: ByteArray, target: ByteArray, role: String): PushOutcome
    public suspend fun transferOwner(room: ByteArray, target: ByteArray): PushOutcome
    public suspend fun approve(room: ByteArray, target: ByteArray): PushOutcome
    public suspend fun reject(room: ByteArray, target: ByteArray): PushOutcome

    public suspend fun createInvite(room: ByteArray, requiresApproval: Boolean, expiresAt: Long?, maxUses: Int?): Result<InviteLink>

    /** Parsing only (Base32/Base64 decode) - no network, safe to call before confirming anything. */
    public fun parseInviteLink(text: String): InviteLink?

    /** Fetches+decrypts the envelope read-only, for the Join screen's confirm step - does not
     * join. `null` if the envelope can't be fetched/decrypted, or carries no room name. */
    public suspend fun peekInviteRoomName(link: InviteLink): String?

    public suspend fun joinViaLink(link: InviteLink): PushOutcome

    /** Current device's role in [room], or `null` if not (yet) a member - convenience the UI
     * needs constantly to decide what actions to show (promote/kick/transfer-owner gating etc.). */
    public suspend fun myRole(room: ByteArray): Role?
}
