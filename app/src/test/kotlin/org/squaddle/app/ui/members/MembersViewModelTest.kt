package org.squaddle.app.ui.members

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.squaddle.app.data.FakeSyncGateway
import org.squaddle.core.crypto.Crypto
import org.squaddle.core.crypto.KeyPair
import org.squaddle.core.encoding.CborValue
import org.squaddle.core.identity.DeviceCert
import org.squaddle.core.identity.UserIdentity
import org.squaddle.core.membership.EntryType
import org.squaddle.core.membership.LogEntry
import org.squaddle.core.membership.MembershipStateImpl
import org.squaddle.core.membership.StoredEntry
import org.squaddle.core.ops.MembershipHistory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Against a real [MembershipHistory] built from a real (tiny) membership log chain - unlike
 * [org.squaddle.core.ops.RoomState]/`Idea`/`Vote`, [org.squaddle.core.membership.MembershipState]
 * has no public constructor to fake a roster directly with (`MembershipStateImpl`'s constructor
 * is private; `MembershipHistory`'s too, only `build()` is public), so this drives a few real
 * log entries through the chain-building/authorization logic instead - the same pattern core's
 * own `MembershipStateTest`/`RoomStateTest` already use, just with [ChainCrypto] standing in for
 * real libsodium: `app` depends on core's *Android* AAR variant (ARM native libs only), which
 * can't load inside this plain JVM test process on an x86_64 host, and the existing
 * [org.squaddle.app.data.FakeCrypto] is too constant to distinguish actors (every "random" byte
 * array and derived key would collide to the same bytes). [ChainCrypto] keeps the same
 * "signatures always verify" leniency but makes hashes/keys actual functions of their input, so
 * distinct actors/entries get distinct identities - everything this test actually needs.
 * [org.squaddle.app.data.FakeSyncGateway] still stands in for
 * [org.squaddle.app.data.SyncGateway] itself (no network).
 */
class MembersViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private object ChainCrypto : Crypto {
        private var counter = 0

        override fun randomBytes(n: Int): ByteArray {
            counter++
            return ByteArray(n) { (it + counter).toByte() }
        }

        override fun blake2b256(vararg parts: ByteArray): ByteArray {
            val combined = parts.fold(ByteArray(0)) { acc, part -> acc + part }
            return ByteArray(32) { i -> if (i < combined.size) combined[i] else 0 }
        }

        override fun sha256(data: ByteArray): ByteArray = blake2b256(data)
        override fun signSeedKeypair(seed32: ByteArray): KeyPair = KeyPair(publicKey = seed32.copyOf(32), secretKey = seed32.copyOf(32) + seed32.copyOf(32))
        override fun sign(secretKey: ByteArray, message: ByteArray): ByteArray = ByteArray(64)
        override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = true
        override fun boxSeedKeypair(seed32: ByteArray): KeyPair = KeyPair(publicKey = seed32.copyOf(32), secretKey = seed32.copyOf(32))
        override fun sealBox(recipientPk: ByteArray, plaintext: ByteArray): ByteArray = plaintext
        override fun openSealedBox(recipient: KeyPair, ciphertext: ByteArray): ByteArray? = ciphertext
        override fun aeadEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, ad: ByteArray): ByteArray = plaintext
        override fun aeadDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, ad: ByteArray): ByteArray? = ciphertext
        override fun kdf(master32: ByteArray, id: Long, context: String): ByteArray = blake2b256(master32, byteArrayOf(id.toByte()), context.encodeToByteArray())
        override fun argon2id(password: ByteArray, salt16: ByteArray, opsLimit: Long, memLimit: Int): ByteArray = blake2b256(password, salt16)
    }

    private fun crypto(): Crypto = ChainCrypto

    private class Actor(val identity: UserIdentity, val deviceKey: KeyPair, val cert: DeviceCert)

    private fun actor(crypto: Crypto, label: String): Actor {
        val identity = UserIdentity.create(crypto)
        val deviceKey = crypto.signSeedKeypair(crypto.randomBytes(32))
        val cert = DeviceCert.sign(crypto, identity.signing.secretKey, identity.userKey, identity.userBoxKey, deviceKey.publicKey, label, 1L)
        return Actor(identity, deviceKey, cert)
    }

    private class Chain(val crypto: Crypto, val roomId: ByteArray) {
        var index = 0L
        var head: ByteArray? = null
        val entries = mutableListOf<StoredEntry>()

        fun append(actor: Actor, type: EntryType, body: CborValue.Map) {
            val entry = LogEntry.sign(crypto, actor.deviceKey.secretKey, roomId, index, head, actor.identity.userKey, actor.cert, type, body, 1_700_000_000L + index)
            entries += StoredEntry(entry, 1_700_000_500L)
            index = entry.index + 1
            head = entry.entryHash(crypto)
        }
    }

    private fun genesisBody(nonce: ByteArray) = CborValue.Map(linkedMapOf(0L to CborValue.Bytes(nonce), 1L to CborValue.Text("relay.squaddle.org"), 2L to CborValue.Bytes(ByteArray(48))))
    private fun inviteCreateBody(inviteId: ByteArray, requiresApproval: Boolean) = CborValue.Map(linkedMapOf(0L to CborValue.Bytes(inviteId), 3L to CborValue.Bool(requiresApproval), 4L to CborValue.Text("member")))
    private fun joinBody(inviteId: ByteArray) = CborValue.Map(mapOf(0L to CborValue.Bytes(inviteId)))
    private fun setRoleBody(user: ByteArray, role: String) = CborValue.Map(mapOf(0L to CborValue.Bytes(user), 1L to CborValue.Text(role)))

    /** Owner + an admin (promoted after joining) + a plain member + a pending (approval-required) joiner. */
    private fun buildFourRoleRoom(): Quad {
        val crypto = crypto()
        val owner = actor(crypto, "Owner")
        val adminCandidate = actor(crypto, "AdminCandidate")
        val plainMember = actor(crypto, "Member")
        val pendingUser = actor(crypto, "Pending")

        val nonce = crypto.randomBytes(16)
        val rid = crypto.blake2b256("SQDL1room".encodeToByteArray(), owner.identity.userKey, nonce)
        val chain = Chain(crypto, rid)
        chain.append(owner, EntryType.GENESIS, genesisBody(nonce))

        val invite1 = crypto.randomBytes(16)
        chain.append(owner, EntryType.INVITE_CREATE, inviteCreateBody(invite1, requiresApproval = false))
        chain.append(adminCandidate, EntryType.JOIN, joinBody(invite1))
        chain.append(owner, EntryType.SET_ROLE, setRoleBody(adminCandidate.identity.userKey, "admin"))

        val invite2 = crypto.randomBytes(16)
        chain.append(owner, EntryType.INVITE_CREATE, inviteCreateBody(invite2, requiresApproval = false))
        chain.append(plainMember, EntryType.JOIN, joinBody(invite2))

        val invite3 = crypto.randomBytes(16)
        chain.append(owner, EntryType.INVITE_CREATE, inviteCreateBody(invite3, requiresApproval = true))
        chain.append(pendingUser, EntryType.JOIN, joinBody(invite3))

        val history = MembershipHistory.build(chain.entries, MembershipStateImpl.empty(crypto))
        return Quad(rid, history, listOf(owner, adminCandidate, plainMember, pendingUser))
    }

    private data class Quad(val room: ByteArray, val history: MembershipHistory, val actors: List<Actor>)

    private fun gatewayWith(history: MembershipHistory) = FakeSyncGateway().apply { this.history = history }

    @Test
    fun ownerSeesPromoteKickAndTransferForAdminButNotForSelf() = runTest(testDispatcher) {
        val (room, history, actors) = buildFourRoleRoom()
        val (owner, admin, _, _) = actors
        val vm = MembersViewModel(room, owner.identity.userKey, gatewayWith(history))
        advanceUntilIdle()

        val ownerRow = vm.uiState.value.members.first { it.user.contentEquals(owner.identity.userKey) }
        assertFalse(ownerRow.canKick)
        assertFalse(ownerRow.canDemote)

        val adminRow = vm.uiState.value.members.first { it.user.contentEquals(admin.identity.userKey) }
        assertTrue(adminRow.canKick)
        assertTrue(adminRow.canDemote)
        assertTrue(adminRow.canTransferOwnership)
        assertFalse(adminRow.canPromote)
    }

    @Test
    fun ownerCanPromotePlainMember() = runTest(testDispatcher) {
        val (room, history, actors) = buildFourRoleRoom()
        val (owner, _, member, _) = actors
        val vm = MembersViewModel(room, owner.identity.userKey, gatewayWith(history))
        advanceUntilIdle()

        val memberRow = vm.uiState.value.members.first { it.user.contentEquals(member.identity.userKey) }
        assertTrue(memberRow.canPromote)
        assertTrue(memberRow.canKick)
        assertFalse(memberRow.canDemote)
        assertFalse(memberRow.canTransferOwnership)
    }

    @Test
    fun pendingUserOnlyGetsApproveAndRejectNeverKick() = runTest(testDispatcher) {
        val (room, history, actors) = buildFourRoleRoom()
        val (owner, _, _, pending) = actors
        val vm = MembersViewModel(room, owner.identity.userKey, gatewayWith(history))
        advanceUntilIdle()

        val pendingRow = vm.uiState.value.members.first { it.user.contentEquals(pending.identity.userKey) }
        assertTrue(pendingRow.canApprove)
        assertTrue(pendingRow.canReject)
        assertFalse(pendingRow.canKick)
        assertFalse(pendingRow.canPromote)
    }

    @Test
    fun plainMemberHasNoActionsAtAll() = runTest(testDispatcher) {
        val (room, history, actors) = buildFourRoleRoom()
        val (_, _, member, _) = actors
        val vm = MembersViewModel(room, member.identity.userKey, gatewayWith(history))
        advanceUntilIdle()

        for (row in vm.uiState.value.members) {
            assertFalse(row.canKick, "member should never see kick")
            assertFalse(row.canPromote)
            assertFalse(row.canDemote)
            assertFalse(row.canTransferOwnership)
            assertFalse(row.canApprove)
            assertFalse(row.canReject)
        }
    }

    @Test
    fun adminCanKickPlainMemberButNotAnotherAdminOrOwner() = runTest(testDispatcher) {
        val (room, history, actors) = buildFourRoleRoom()
        val (owner, admin, member, _) = actors
        val vm = MembersViewModel(room, admin.identity.userKey, gatewayWith(history))
        advanceUntilIdle()

        val memberRow = vm.uiState.value.members.first { it.user.contentEquals(member.identity.userKey) }
        assertTrue(memberRow.canKick)
        val ownerRow = vm.uiState.value.members.first { it.user.contentEquals(owner.identity.userKey) }
        assertFalse(ownerRow.canKick)
    }

    @Test
    fun approveCallsGatewayAndRefreshes() = runTest(testDispatcher) {
        val (room, history, actors) = buildFourRoleRoom()
        val (owner, _, _, pending) = actors
        val gateway = gatewayWith(history)
        val vm = MembersViewModel(room, owner.identity.userKey, gateway)
        advanceUntilIdle()

        vm.approve(pending.identity.userKey)
        advanceUntilIdle()

        assertTrue(gateway.approvals.isNotEmpty())
    }
}
