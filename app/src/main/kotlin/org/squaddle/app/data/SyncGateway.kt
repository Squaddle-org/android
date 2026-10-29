package org.squaddle.app.data

import org.squaddle.core.membership.Role
import org.squaddle.core.ops.MembershipHistory
import org.squaddle.core.ops.OpBody
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

    /** Registers [endpoint] (a UnifiedPush distributor's delivery URL) with the default relay
     * for the current device - called from [org.squaddle.app.push.MessagingReceiver] whenever
     * the distributor hands out a new endpoint (first run, or after it rotates one). */
    public suspend fun registerPushEndpoint(endpoint: String): PushOutcome

    /** Removes this device's push registration - e.g. the user disabling push, or the
     * distributor reporting it was unregistered. */
    public suspend fun unregisterPush(): PushOutcome

    /** The default relay's VAPID public key (ADR-0013), or `null` if push isn't configured
     * there / the relay is unreachable - passed straight into UnifiedPush's own
     * `register(vapid = ...)` parameter. */
    public suspend fun vapidPublicKey(): String?

    /** Mints a room id+nonce, calls core's `createRoom`, and records it in the local
     * known-rooms registry (room names/registry aren't a protocol concept - see `AppLocalDb.sq`).
     * Always uses the gateway's own configured relay host (see [RealSyncGateway]'s constructor) -
     * every call site has only ever passed that one value anyway. */
    public suspend fun createRoom(roomName: String): Result<ByteArray>

    /** Seed-only restore (PROTOCOL.md §5, spec v1.0.0-draft.3): asks the gateway's configured
     * relay for every room the current user holds an active role in, inserts each into the local
     * known-rooms registry, and pulls it. Returns the count recovered. Scoped to that single
     * relay - rooms on a different relay need a fresh invite link instead. */
    public suspend fun restoreKnownRooms(): Result<Int>

    public suspend fun pull(room: ByteArray): SyncOutcome

    /** `(lastLogIndex, lastOpsCursor)` as core's own `LocalStore` currently has them synced to,
     * or `null` if nothing has ever been pulled for [room] yet - what the Rooms list compares
     * against `AppLocalDb`'s `read_cursors` to compute "unread" (a purely local UI concept, not
     * a protocol one; see `AppLocalDb.sq`'s own doc comment). */
    public suspend fun syncedCursor(room: ByteArray): Pair<Long, Long>?

    /** Launches core's `connectLiveUpdates` (a real WebSocket subscription) and invokes
     * [onChanged] whenever a short local-store-only poll (no network) observes new data - see the
     * plan's own note on why this is polled rather than pushed directly from core. Suspends for
     * as long as the caller's coroutine keeps it running; cancel the job to disconnect. */
    public suspend fun connectLiveUpdates(room: ByteArray, onChanged: () -> Unit)

    /** Replays the local log+ops into a [RoomMaterialization] - no network. Mirrors
     * `core/cli`'s own `materializeRoomState`/`certResolverFromLog` (`cli/src/main/kotlin/org/squaddle/cli/Main.kt:243-275`),
     * a third legitimate call site of core's already-public replay/decrypt/fold functions. */
    public suspend fun materialize(room: ByteArray): RoomMaterialization

    /** Decrypted op bodies stored with a local ops-cursor strictly after [sinceOpsCursor] (core's
     * own `LocalStore.opsAfter` ordering) - no network, no full-state fold. Lets a caller (the
     * push/periodic sync worker) see *what* is new since it last acted, which [materialize]'s
     * full accumulated [RoomState] can't answer on its own. */
    public suspend fun opsSince(room: ByteArray, sinceOpsCursor: Long): List<OpBody>

    public suspend fun membershipHistory(room: ByteArray): MembershipHistory

    public suspend fun createIdea(room: ByteArray, title: String, desc: String?): Boolean
    public suspend fun editIdea(room: ByteArray, id: ByteArray, field: String, value: Any?): Boolean
    public suspend fun deleteIdea(room: ByteArray, id: ByteArray): Boolean
    public suspend fun vote(room: ByteArray, ideaId: ByteArray, value: Int): Boolean
    public suspend fun addComment(room: ByteArray, ideaId: ByteArray, text: String): Boolean
    public suspend fun deleteComment(room: ByteArray, id: ByteArray): Boolean

    /** [ideaId] is `null` for a standalone event, non-null for "convert this idea into an event"
     * (both are the same op per PROTOCOL.md §4.3 - `ideaId` is just an optional reference field
     * on `EventCreate`). Authorization (admin/owner, or member iff `membersCanCreateEvents`) is
     * enforced server-side the same way idea-create authorization is - the UI only decides
     * whether to *offer* the action via [org.squaddle.app.ui.room.RoomUiState.canCreateEvent]. */
    public suspend fun createEvent(room: ByteArray, ideaId: ByteArray?, title: String, start: Long, end: Long?, tz: String, location: String?, allDay: Boolean): Boolean
    public suspend fun cancelEvent(room: ByteArray, id: ByteArray): Boolean

    /** [value] is `"yes"`, `"no"`, or `"maybe"` (PROTOCOL.md §4.3) - always the caller's own RSVP,
     * same "author's own vote only" shape as [vote]. */
    public suspend fun rsvp(room: ByteArray, eventId: ByteArray, value: String): Boolean

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
