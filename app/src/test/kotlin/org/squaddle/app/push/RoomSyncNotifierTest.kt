package org.squaddle.app.push

import kotlinx.coroutines.test.runTest
import org.squaddle.app.data.AuthoredOp
import org.squaddle.app.data.FakeSyncGateway
import org.squaddle.app.data.RoomMaterialization
import org.squaddle.app.store.FakeAppLocalStore
import org.squaddle.core.crypto.Crypto
import org.squaddle.core.crypto.KeyPair
import org.squaddle.core.encoding.CborValue
import org.squaddle.core.identity.DeviceCert
import org.squaddle.core.membership.EntryType
import org.squaddle.core.membership.LogEntry
import org.squaddle.core.membership.MembershipStateImpl
import org.squaddle.core.membership.Role
import org.squaddle.core.membership.StoredEntry
import org.squaddle.core.ops.Comment
import org.squaddle.core.ops.Event
import org.squaddle.core.ops.Idea
import org.squaddle.core.ops.LwwStamp
import org.squaddle.core.ops.MembershipHistory
import org.squaddle.core.ops.OpBody
import org.squaddle.core.ops.RoomState
import org.squaddle.core.sync.SyncOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomSyncNotifierTest {

    private val room = ByteArray(32) { it.toByte() }
    private val myUserKey = ByteArray(32) { 0x11 }
    private val otherUser = ByteArray(32) { 0x22 }
    private val gateway = FakeSyncGateway()
    private val store = FakeAppLocalStore()
    private val muteStore = FakeRoomMuteStore()
    private val posted = mutableListOf<List<RoomNotification>>()
    private val notifier = RoomSyncNotifier(gateway, store, muteStore, myUserKey, poster = { posted += it })

    private fun seedRoom() {
        store.insertKnownRoom(room, "Camping Trip", "relay.squaddle.org", 1L, 1L)
        gateway.syncedCursorResult = 5L to 10L
    }

    private fun stamp() = LwwStamp(1, otherUser)

    private fun idea(id: ByteArray, createdBy: ByteArray) = Idea(
        id = id, createdBy = createdBy, title = "t", titleStamp = stamp(), desc = null, descStamp = stamp(),
        tags = emptyList(), tagsStamp = stamp(), status = null, statusStamp = stamp(), deleted = false, deletedStamp = stamp(),
    )

    private fun comment(ideaId: ByteArray, author: ByteArray) = Comment(
        id = ByteArray(16) { 0x33 }, ideaId = ideaId, author = author, text = "nice", deleted = false, deletedStamp = stamp(),
    )

    private fun event(id: ByteArray, createdBy: ByteArray) = Event(
        id = id, createdBy = createdBy, ideaId = null, title = "t", titleStamp = stamp(), start = 1L, startStamp = stamp(),
        end = null, endStamp = stamp(), tz = "UTC", tzStamp = stamp(), location = null, locationStamp = stamp(),
        allDay = true, allDayStamp = stamp(), cancelled = false, cancelledStamp = stamp(),
    )

    private val dummyCert = DeviceCert(user = otherUser, userBox = ByteArray(32), device = ByteArray(32), label = "test", issuedAt = 0L)

    /** Unsigned - [RoomSyncNotifier] never calls [LogEntry.verify]/applies these through
     * [org.squaddle.core.membership.MembershipState], it only reads [LogEntry.type]/
     * [LogEntry.author]/[LogEntry.body] directly, so a real chain/signature isn't needed here
     * (unlike [buildAdminHistoryWithOnePending] below, which exercises the real fold). */
    private fun logEntry(type: EntryType, author: ByteArray, targetUser: ByteArray? = null, role: String? = null) = LogEntry(
        v = 1, room = room, index = 0, prev = null, author = author, cert = dummyCert, type = type,
        body = CborValue.Map(
            buildMap {
                targetUser?.let { put(0L, CborValue.Bytes(it)) }
                role?.let { put(1L, CborValue.Text(it)) }
            },
        ),
        ts = 0L, sig = null,
    )

    @Test
    fun noNewOpsPostsNothingButStillAdvancesCursor() = runTest {
        seedRoom()

        notifier.syncAllAndNotify()

        assertTrue(posted.isEmpty())
        assertEquals(10L, store.notifiedCursorFor(room)?.last_notified_ops_cursor)
    }

    @Test
    fun newIdeaFromSomeoneElsePostsOneIdeaNotification() = runTest {
        seedRoom()
        gateway.opsSinceResult = listOf(AuthoredOp(otherUser, OpBody.IdeaCreate(ByteArray(16), "New idea", null, emptyList())))

        notifier.syncAllAndNotify()

        val batch = posted.single()
        assertEquals(listOf(RoomNotification.Kind.IDEA), batch.map { it.kind })
        assertEquals(NotificationChannels.IDEA, batch.single().channel)
        assertEquals("Camping Trip", batch.single().roomName)
        assertEquals(10L, store.notifiedCursorFor(room)?.last_notified_ops_cursor)
    }

    @Test
    fun myOwnIdeaFromAnyOfMyDevicesNeverNotifies() = runTest {
        seedRoom()
        gateway.opsSinceResult = listOf(AuthoredOp(myUserKey, OpBody.IdeaCreate(ByteArray(16), "My idea", null, emptyList())))

        notifier.syncAllAndNotify()

        assertTrue(posted.isEmpty(), "must never notify for my own action, regardless of which of my devices did it")
    }

    @Test
    fun commentOnAnIdeaICreatedNotifies() = runTest {
        seedRoom()
        val ideaId = ByteArray(16) { 1 }
        gateway.materialization = RoomMaterialization(RoomState(ideas = mapOf("ideaA" to idea(ideaId, createdBy = myUserKey))), emptyMap())
        gateway.opsSinceResult = listOf(AuthoredOp(otherUser, OpBody.CommentAdd(ByteArray(16), ideaId, "nice!")))

        notifier.syncAllAndNotify()

        assertEquals(listOf(RoomNotification.Kind.COMMENT), posted.single().map { it.kind })
    }

    @Test
    fun commentOnAnIdeaIPreviouslyCommentedOnNotifies() = runTest {
        seedRoom()
        val ideaId = ByteArray(16) { 1 }
        gateway.materialization = RoomMaterialization(
            RoomState(
                ideas = mapOf("ideaA" to idea(ideaId, createdBy = otherUser)),
                comments = mapOf("c1" to comment(ideaId, author = myUserKey)),
            ),
            emptyMap(),
        )
        gateway.opsSinceResult = listOf(AuthoredOp(otherUser, OpBody.CommentAdd(ByteArray(16), ideaId, "nice!")))

        notifier.syncAllAndNotify()

        assertEquals(listOf(RoomNotification.Kind.COMMENT), posted.single().map { it.kind })
    }

    @Test
    fun commentOnAnUnrelatedIdeaDoesNotNotify() = runTest {
        seedRoom()
        val ideaId = ByteArray(16) { 1 }
        gateway.materialization = RoomMaterialization(RoomState(ideas = mapOf("ideaA" to idea(ideaId, createdBy = otherUser))), emptyMap())
        gateway.opsSinceResult = listOf(AuthoredOp(otherUser, OpBody.CommentAdd(ByteArray(16), ideaId, "nice!")))

        notifier.syncAllAndNotify()

        assertTrue(posted.isEmpty(), "a comment on an idea I neither created nor ever commented on must not notify")
    }

    @Test
    fun rsvpOnMyEventNotifies() = runTest {
        seedRoom()
        val eventId = ByteArray(16) { 2 }
        gateway.materialization = RoomMaterialization(RoomState(events = mapOf("eventA" to event(eventId, createdBy = myUserKey))), emptyMap())
        gateway.opsSinceResult = listOf(AuthoredOp(otherUser, OpBody.RsvpSet(eventId, "yes")))

        notifier.syncAllAndNotify()

        assertEquals(listOf(RoomNotification.Kind.RSVP), posted.single().map { it.kind })
    }

    @Test
    fun rsvpOnSomeoneElsesEventDoesNotNotify() = runTest {
        seedRoom()
        val eventId = ByteArray(16) { 2 }
        gateway.materialization = RoomMaterialization(RoomState(events = mapOf("eventA" to event(eventId, createdBy = otherUser))), emptyMap())
        gateway.opsSinceResult = listOf(AuthoredOp(otherUser, OpBody.RsvpSet(eventId, "yes")))

        notifier.syncAllAndNotify()

        assertTrue(posted.isEmpty())
    }

    @Test
    fun newEventAlwaysNotifies() = runTest {
        seedRoom()
        gateway.opsSinceResult = listOf(AuthoredOp(otherUser, OpBody.EventCreate(ByteArray(16), null, "Weekend", 1L, null, "UTC", null, true)))

        notifier.syncAllAndNotify()

        assertEquals(listOf(RoomNotification.Kind.EVENT), posted.single().map { it.kind })
    }

    @Test
    fun roleChangeAffectingMeNotifiesWithRoomDeepLink() = runTest {
        seedRoom()
        gateway.logEntriesSinceResult = listOf(logEntry(EntryType.SET_ROLE, author = otherUser, targetUser = myUserKey, role = "admin"))

        notifier.syncAllAndNotify()

        val notification = posted.single().single()
        assertEquals(RoomNotification.Kind.ROLE_CHANGE, notification.kind)
        assertEquals(RoomNotification.DeepLinkKind.ROOM, notification.deepLinkKind)
        assertEquals(NotificationChannels.MEMBERSHIP, notification.channel)
    }

    @Test
    fun kickAffectingMeNotifiesWithRoomsListDeepLink() = runTest {
        seedRoom()
        gateway.logEntriesSinceResult = listOf(logEntry(EntryType.REMOVE, author = otherUser, targetUser = myUserKey))

        notifier.syncAllAndNotify()

        val notification = posted.single().single()
        assertEquals(RoomNotification.Kind.KICKED, notification.kind)
        assertEquals(RoomNotification.DeepLinkKind.ROOMS_LIST, notification.deepLinkKind)
    }

    @Test
    fun roleChangeTargetingSomeoneElseDoesNotNotify() = runTest {
        seedRoom()
        val thirdUser = ByteArray(32) { 0x33 }
        gateway.logEntriesSinceResult = listOf(logEntry(EntryType.SET_ROLE, author = otherUser, targetUser = thirdUser, role = "admin"))

        notifier.syncAllAndNotify()

        assertTrue(posted.isEmpty())
    }

    @Test
    fun myOwnTransferOfOwnershipDoesNotNotify() = runTest {
        seedRoom()
        val thirdUser = ByteArray(32) { 0x33 }
        // I'm the author (I transferred ownership away); not notification-worthy even though my
        // OWN role also changes as a side effect (OWNER -> ADMIN) - I already know, I just did it.
        gateway.logEntriesSinceResult = listOf(logEntry(EntryType.TRANSFER_OWNER, author = myUserKey, targetUser = thirdUser))

        notifier.syncAllAndNotify()

        assertTrue(posted.isEmpty())
    }

    @Test
    fun mutedRoomPostsNothingButStillAdvancesCursor() = runTest {
        seedRoom()
        muteStore.setMuted(room, true)
        gateway.opsSinceResult = listOf(AuthoredOp(otherUser, OpBody.IdeaCreate(ByteArray(16), "New idea", null, emptyList())))

        notifier.syncAllAndNotify()

        assertTrue(posted.isEmpty())
        assertEquals(10L, store.notifiedCursorFor(room)?.last_notified_ops_cursor)
    }

    @Test
    fun failedPullSkipsTheRoomEntirely() = runTest {
        seedRoom()
        gateway.pullOutcome = SyncOutcome.Error(0, "network_error", "unreachable")
        gateway.opsSinceResult = listOf(AuthoredOp(otherUser, OpBody.IdeaCreate(ByteArray(16), "New idea", null, emptyList())))

        notifier.syncAllAndNotify()

        assertTrue(posted.isEmpty())
        assertEquals(null, store.notifiedCursorFor(room))
    }

    @Test
    fun repeatedRunDoesNotReNotifyAlreadySeenOps() = runTest {
        seedRoom()
        gateway.opsSinceResult = listOf(AuthoredOp(otherUser, OpBody.IdeaCreate(ByteArray(16), "New idea", null, emptyList())))
        notifier.syncAllAndNotify()
        assertEquals(1, posted.size)

        // Second run: a real SyncGateway would return an empty opsSince() once the cursor caught
        // up - this fake can't express "cursor-dependent" results, so it's simulated directly by
        // clearing opsSinceResult, same effect from RoomSyncNotifier's point of view.
        gateway.opsSinceResult = emptyList()
        notifier.syncAllAndNotify()

        assertEquals(1, posted.size, "must not re-notify for ops already seen on a previous run")
    }

    // ---- pending-approval: needs a real MembershipState fold (roleOf(joiner) == PENDING is
    // derived from the invite's own requiresApproval flag, not decodable from the JOIN entry
    // alone - RoomSyncNotifier.logNotifications' own doc comment) ----

    private object ChainCrypto : Crypto {
        private var counter = 0
        override fun randomBytes(n: Int): ByteArray { counter++; return ByteArray(n) { (it + counter).toByte() } }
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

    /** [myRole] in the resulting room: OWNER (me as genesis author) if `amOwner`, else MEMBER (a
     * second, already-approved joiner). Either way a THIRD actor's join is left pending. */
    private fun buildHistoryWithOnePending(amOwner: Boolean): Pair<MembershipHistory, ByteArray> {
        val crypto = ChainCrypto
        // amOwner=false: someone ELSE (genesisAuthor) owns the room and I join as a plain member
        // first - the pending joiner is a third, separate actor either way.
        val genesisAuthor = if (amOwner) myUserKey else ByteArray(32) { 0x55 }
        val nonce = crypto.randomBytes(16)
        val rid = crypto.blake2b256("SQDL1room".encodeToByteArray(), genesisAuthor, nonce)
        var index = 0L
        var head: ByteArray? = null
        val entries = mutableListOf<StoredEntry>()
        fun append(author: ByteArray, type: EntryType, body: CborValue.Map) {
            val cert = DeviceCert(user = author, userBox = ByteArray(32), device = crypto.randomBytes(32), label = "d", issuedAt = 0L, sig = ByteArray(64))
            val entry = LogEntry.sign(crypto, crypto.randomBytes(64), rid, index, head, author, cert, type, body, 1_700_000_000L + index)
            entries += StoredEntry(entry, 1_700_000_500L)
            index = entry.index + 1
            head = entry.entryHash(crypto)
        }

        append(genesisAuthor, EntryType.GENESIS, CborValue.Map(linkedMapOf(0L to CborValue.Bytes(nonce))))
        if (!amOwner) {
            val openInvite = crypto.randomBytes(16)
            append(genesisAuthor, EntryType.INVITE_CREATE, CborValue.Map(linkedMapOf(0L to CborValue.Bytes(openInvite), 3L to CborValue.Bool(false), 4L to CborValue.Text("member"))))
            append(myUserKey, EntryType.JOIN, CborValue.Map(mapOf(0L to CborValue.Bytes(openInvite))))
        }
        val approvalInvite = crypto.randomBytes(16)
        append(genesisAuthor, EntryType.INVITE_CREATE, CborValue.Map(linkedMapOf(0L to CborValue.Bytes(approvalInvite), 3L to CborValue.Bool(true), 4L to CborValue.Text("member"))))
        val joiner = otherUser
        append(joiner, EntryType.JOIN, CborValue.Map(mapOf(0L to CborValue.Bytes(approvalInvite))))

        return MembershipHistory.build(entries, MembershipStateImpl.empty(crypto)) to joiner
    }

    @Test
    fun pendingJoinNotifiesWhenIAmOwner() = runTest {
        seedRoom()
        val (history, joiner) = buildHistoryWithOnePending(amOwner = true)
        gateway.history = history
        gateway.logEntriesSinceResult = listOf(logEntry(EntryType.JOIN, author = joiner))

        notifier.syncAllAndNotify()

        val notification = posted.single().single()
        assertEquals(RoomNotification.Kind.PENDING_APPROVAL, notification.kind)
        assertEquals(RoomNotification.DeepLinkKind.MEMBERS, notification.deepLinkKind)
        assertEquals(NotificationChannels.PENDING_APPROVAL, notification.channel)
    }

    @Test
    fun pendingJoinDoesNotNotifyPlainMembers() = runTest {
        seedRoom()
        val (history, joiner) = buildHistoryWithOnePending(amOwner = false)
        assertEquals(Role.MEMBER, history.current.roleOf(myUserKey), "test setup sanity check")
        gateway.history = history
        gateway.logEntriesSinceResult = listOf(logEntry(EntryType.JOIN, author = joiner))

        notifier.syncAllAndNotify()

        assertTrue(posted.isEmpty(), "a plain member must never get an admin-only pending-approval notification")
    }
}
