package org.squaddle.app.data

import org.squaddle.core.membership.Role
import org.squaddle.core.ops.MembershipHistory
import org.squaddle.core.ops.RoomState
import org.squaddle.core.sync.PushOutcome
import org.squaddle.core.sync.SyncOutcome

/**
 * In-memory [SyncGateway] test double - no real crypto, no network. Every ViewModel test in this
 * app injects this instead of [RealSyncGateway], which is exactly what makes "ViewModel unit
 * tests against a fake sync client" possible (the task's own wording) despite
 * [org.squaddle.core.sync.SyncClient] being a concrete class, not an interface.
 */
public class FakeSyncGateway : SyncGateway {
    public var registerDeviceOutcome: PushOutcome = PushOutcome.Ok
    public var pullOutcome: SyncOutcome = SyncOutcome.Ok
    public var materialization: RoomMaterialization = RoomMaterialization(RoomState(), emptyMap())
    public var history: MembershipHistory = MembershipHistory.build(emptyList(), org.squaddle.core.membership.MembershipStateImpl.empty(FakeCrypto))
    public var myRoleResult: Role? = Role.OWNER
    public var createRoomResult: Result<ByteArray> = Result.success(ByteArray(32))
    public var createInviteResult: Result<InviteLink> = Result.success(InviteLink("relay.squaddle.org", ByteArray(32), ByteArray(16), ByteArray(32)))
    public var joinOutcome: PushOutcome = PushOutcome.Ok
    public var peekRoomName: String? = "Test Room"
    public var opOutcome: Boolean = true
    public var logEntryOutcome: PushOutcome = PushOutcome.Ok

    public val createdIdeas: MutableList<Pair<String, String?>> = mutableListOf()
    public val votes: MutableList<Pair<String, Int>> = mutableListOf()
    public val comments: MutableList<Pair<String, String>> = mutableListOf()
    public val kicked: MutableList<String> = mutableListOf()
    public val roleChanges: MutableList<Pair<String, String>> = mutableListOf()
    public val transfers: MutableList<String> = mutableListOf()
    public val approvals: MutableList<String> = mutableListOf()
    public val rejections: MutableList<String> = mutableListOf()

    override suspend fun registerDevice(): PushOutcome = registerDeviceOutcome

    override suspend fun createRoom(roomName: String, relayHost: String): Result<ByteArray> = createRoomResult

    override suspend fun pull(room: ByteArray): SyncOutcome = pullOutcome

    override suspend fun connectLiveUpdates(room: ByteArray, onChanged: () -> Unit) {
        // No-op in tests - nothing to subscribe to.
    }

    override suspend fun materialize(room: ByteArray): RoomMaterialization = materialization

    override suspend fun membershipHistory(room: ByteArray): MembershipHistory = history

    override suspend fun myRole(room: ByteArray): Role? = myRoleResult

    override suspend fun createIdea(room: ByteArray, title: String, desc: String?): Boolean {
        createdIdeas += title to desc
        return opOutcome
    }

    override suspend fun editIdea(room: ByteArray, id: ByteArray, field: String, value: Any?): Boolean = opOutcome

    override suspend fun deleteIdea(room: ByteArray, id: ByteArray): Boolean = opOutcome

    override suspend fun vote(room: ByteArray, ideaId: ByteArray, value: Int): Boolean {
        votes += ideaId.joinToString("") { "%02x".format(it) } to value
        return opOutcome
    }

    override suspend fun addComment(room: ByteArray, ideaId: ByteArray, text: String): Boolean {
        comments += ideaId.joinToString("") { "%02x".format(it) } to text
        return opOutcome
    }

    override suspend fun deleteComment(room: ByteArray, id: ByteArray): Boolean = opOutcome

    override suspend fun kick(room: ByteArray, target: ByteArray): PushOutcome {
        kicked += target.joinToString("") { "%02x".format(it) }
        return logEntryOutcome
    }

    override suspend fun setRole(room: ByteArray, target: ByteArray, role: String): PushOutcome {
        roleChanges += target.joinToString("") { "%02x".format(it) } to role
        return logEntryOutcome
    }

    override suspend fun transferOwner(room: ByteArray, target: ByteArray): PushOutcome {
        transfers += target.joinToString("") { "%02x".format(it) }
        return logEntryOutcome
    }

    override suspend fun approve(room: ByteArray, target: ByteArray): PushOutcome {
        approvals += target.joinToString("") { "%02x".format(it) }
        return logEntryOutcome
    }

    override suspend fun reject(room: ByteArray, target: ByteArray): PushOutcome {
        rejections += target.joinToString("") { "%02x".format(it) }
        return logEntryOutcome
    }

    override suspend fun createInvite(room: ByteArray, requiresApproval: Boolean, expiresAt: Long?, maxUses: Int?): Result<InviteLink> =
        createInviteResult

    override fun parseInviteLink(text: String): InviteLink? = InviteLink.parse(text)

    override suspend fun peekInviteRoomName(link: InviteLink): String? = peekRoomName

    override suspend fun joinViaLink(link: InviteLink): PushOutcome = joinOutcome
}
