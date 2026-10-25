@file:OptIn(ExperimentalEncodingApi::class)

package org.squaddle.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.squaddle.app.di.AppContainer
import org.squaddle.app.di.AppSession
import org.squaddle.app.store.AppLocalStore
import org.squaddle.core.encoding.CborValue
import org.squaddle.core.identity.DeviceCert
import org.squaddle.core.membership.EntryType
import org.squaddle.core.membership.EpochKeys
import org.squaddle.core.membership.InviteEnvelope
import org.squaddle.core.membership.LogEntry
import org.squaddle.core.membership.MembershipState
import org.squaddle.core.membership.MembershipStateImpl
import org.squaddle.core.membership.Role
import org.squaddle.core.membership.StoredEntry
import org.squaddle.core.membership.roomId
import org.squaddle.core.ops.MembershipHistory
import org.squaddle.core.ops.OpBody
import org.squaddle.core.ops.OpEnvelope
import org.squaddle.core.ops.OpOpenResult
import org.squaddle.core.ops.RoomState
import org.squaddle.core.store.LocalStore
import org.squaddle.core.sync.PushOutcome
import org.squaddle.core.sync.SyncClient
import org.squaddle.core.sync.SyncOutcome
import org.squaddle.core.sync.nowUnixSeconds
import java.net.HttpURLConnection
import java.net.URL
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private fun ByteArray.toKey(): String = joinToString("") { "%02x".format(it) }
private fun b64Url(bytes: ByteArray): String = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)

/**
 * Wraps [SyncClient]/[LocalStore] - the real implementation of the single app<->core integration
 * seam. Every "glue" sequence here (materialize, kick+autoRotate, approve's userBoxKey lookup) is
 * built from core's already-public functions, mirroring an existing, established call site
 * (`core/cli`'s `Main.kt` or `core`'s own `EndToEndIntegrationTest`) rather than inventing new
 * protocol behavior - cited inline at each one.
 */
public class RealSyncGateway(
    private val session: AppSession,
    private val appLocalStore: AppLocalStore,
) : SyncGateway {

    private fun syncClientForRoom(room: ByteArray): SyncClient {
        val relayHost = appLocalStore.knownRoom(room)?.relay_host
            ?: error("room not known locally: ${room.toKey()}")
        return session.syncClientFor(relayHost)
    }

    private fun buildHistory(room: ByteArray): MembershipHistory {
        var state: MembershipState = MembershipStateImpl.empty(session.crypto)
        val storedEntries = mutableListOf<StoredEntry>()
        for (row in session.store.logEntriesFrom(room, 0L)) {
            val entry = LogEntry.decode(row.entry_cbor) ?: continue
            val stored = StoredEntry(entry, row.rts)
            state = state.apply(stored).getOrElse { state }
            storedEntries += stored
        }
        return MembershipHistory.build(storedEntries, MembershipStateImpl.empty(session.crypto))
    }

    /** Mirrors `core/cli`'s own `certResolverFromLog` (`cli/src/main/kotlin/org/squaddle/cli/Main.kt:243-250`). */
    private fun certResolverFromLog(room: ByteArray): (ByteArray) -> DeviceCert? {
        val byDevice = mutableMapOf<String, DeviceCert>()
        for (row in session.store.logEntriesFrom(room, 0L)) {
            val entry = LogEntry.decode(row.entry_cbor) ?: continue
            byDevice[entry.cert.device.toKey()] = entry.cert
        }
        return { device -> byDevice[device.toKey()] }
    }

    /** Mirrors `SyncClient`'s own private `collectUserBoxKeys` (not public) - needed by [approve]
     * to seal the current epoch key to the target's actual per-user box key. */
    private fun collectUserBoxKeys(room: ByteArray): Map<String, ByteArray> {
        val map = mutableMapOf<String, ByteArray>()
        for (row in session.store.logEntriesFrom(room, 0L)) {
            val entry = LogEntry.decode(row.entry_cbor) ?: continue
            map[entry.author.toKey()] = entry.cert.userBox
        }
        return map
    }

    override suspend fun registerDevice(): PushOutcome {
        // Registering needs *a* relay to talk to even before any room exists - the app's default.
        return session.syncClientFor(AppContainer.DEFAULT_RELAY_HOST).publishDeviceCert()
    }

    override suspend fun createRoom(roomName: String, relayHost: String): Result<ByteArray> {
        val sync = session.syncClientFor(relayHost)
        val nonce = session.crypto.randomBytes(16)
        val room = roomId(session.crypto, session.userIdentity.userKey, nonce)
        val outcome = sync.createRoom(room, nonce)
        if (outcome != PushOutcome.Ok) return Result.failure(IllegalStateException("createRoom failed: $outcome"))
        val now = System.currentTimeMillis()
        appLocalStore.insertKnownRoom(room, roomName, relayHost, now, now)
        return Result.success(room)
    }

    override suspend fun pull(room: ByteArray): SyncOutcome {
        val outcome = syncClientForRoom(room).pull(room)
        if (outcome == SyncOutcome.Ok) appLocalStore.touchRoomActivity(room, System.currentTimeMillis())
        return outcome
    }

    override suspend fun connectLiveUpdates(room: ByteArray, onChanged: () -> Unit) {
        val sync = syncClientForRoom(room)
        // core's own connectLiveUpdates suspends for the socket's lifetime and already calls
        // pull() internally on every server push, but exposes no "something changed" signal back
        // out - adding one would be a core change, out of this round's android-repo-only scope
        // (see the plan). This poll is local-SQLite-only (no network) and just exists to notice
        // when that internal pull landed new data.
        coroutineScope {
            launch { sync.connectLiveUpdates(listOf(room)) }
            launch {
                var lastLogIndex = session.store.maxLogIndex(room)
                var lastOpsCursor = session.store.maxOpsCursor(room)
                while (true) {
                    delay(1500)
                    val logIndex = session.store.maxLogIndex(room)
                    val opsCursor = session.store.maxOpsCursor(room)
                    if (logIndex != lastLogIndex || opsCursor != lastOpsCursor) {
                        lastLogIndex = logIndex
                        lastOpsCursor = opsCursor
                        appLocalStore.touchRoomActivity(room, System.currentTimeMillis())
                        onChanged()
                    }
                }
            }
        }
    }

    /** Mirrors `core/cli`'s own `materializeRoomState` (`cli/src/main/kotlin/org/squaddle/cli/Main.kt:252-275`),
     * plus tracking each idea.create op's lamport for the Room screen's recency sort - RoomState's
     * own Idea struct carries no creation timestamp, so this is captured in the same pass rather
     * than needing a second one. */
    override suspend fun materialize(room: ByteArray): RoomMaterialization {
        val sync = syncClientForRoom(room)
        sync.rehydrateEpochKeys(room)

        val history = buildHistory(room)
        val certOf = certResolverFromLog(room)
        val ideaCreatedAtLamport = mutableMapOf<String, Long>()
        val ops = session.store.opsAfter(room, 0L).mapNotNull { row ->
            val env = OpEnvelope.decode(row.envelope_cbor) ?: return@mapNotNull null
            val key = sync.epochKeyFor(room, env.epoch) ?: return@mapNotNull null
            val opened = env.open(session.crypto, key, certOf)
            val body = (opened as? OpOpenResult.Ok)?.body ?: return@mapNotNull null
            if (body is OpBody.IdeaCreate) ideaCreatedAtLamport[body.id.toKey()] = env.lamport
            env to body
        }
        return RoomMaterialization(RoomState.fold(ops, history), ideaCreatedAtLamport)
    }

    override suspend fun membershipHistory(room: ByteArray): MembershipHistory = buildHistory(room)

    override suspend fun myRole(room: ByteArray): Role? = buildHistory(room).current.roleOf(session.userIdentity.userKey)

    private suspend fun enqueueAndPush(room: ByteArray, body: OpBody): Boolean {
        val sync = syncClientForRoom(room)
        if (!sync.enqueueOp(room, body)) return false
        sync.push(room) // best-effort; a failed push just leaves the op queued for the next one
        return true
    }

    override suspend fun createIdea(room: ByteArray, title: String, desc: String?): Boolean =
        enqueueAndPush(room, OpBody.IdeaCreate(session.crypto.randomBytes(16), title, desc, emptyList()))

    override suspend fun editIdea(room: ByteArray, id: ByteArray, field: String, value: Any?): Boolean =
        enqueueAndPush(room, OpBody.IdeaSet(id, field, value))

    override suspend fun deleteIdea(room: ByteArray, id: ByteArray): Boolean =
        enqueueAndPush(room, OpBody.IdeaDelete(id))

    override suspend fun vote(room: ByteArray, ideaId: ByteArray, value: Int): Boolean =
        enqueueAndPush(room, OpBody.VoteSet(ideaId, value))

    override suspend fun addComment(room: ByteArray, ideaId: ByteArray, text: String): Boolean =
        enqueueAndPush(room, OpBody.CommentAdd(session.crypto.randomBytes(16), ideaId, text))

    override suspend fun deleteComment(room: ByteArray, id: ByteArray): Boolean =
        enqueueAndPush(room, OpBody.CommentDelete(id))

    /** REMOVE + an immediate `autoRotate` right after - mirrors `core`'s own
     * `EndToEndIntegrationTest` kick sequence exactly, rather than waiting for a lazy
     * `rotation_required` bounce on some future content push. */
    override suspend fun kick(room: ByteArray, target: ByteArray): PushOutcome {
        val sync = syncClientForRoom(room)
        val removeOutcome = sync.appendLogEntry(room) { index, prev ->
            LogEntry.sign(
                session.crypto, session.device.secretKey, room, index, prev, session.userIdentity.userKey, session.deviceCert,
                EntryType.REMOVE, CborValue.Map(mapOf(0L to CborValue.Bytes(target))), nowUnixSeconds(),
            )
        }
        if (removeOutcome != PushOutcome.Ok) return removeOutcome
        return sync.autoRotate(room)
    }

    override suspend fun setRole(room: ByteArray, target: ByteArray, role: String): PushOutcome {
        val sync = syncClientForRoom(room)
        return sync.appendLogEntry(room) { index, prev ->
            LogEntry.sign(
                session.crypto, session.device.secretKey, room, index, prev, session.userIdentity.userKey, session.deviceCert,
                EntryType.SET_ROLE, CborValue.Map(mapOf(0L to CborValue.Bytes(target), 1L to CborValue.Text(role))), nowUnixSeconds(),
            )
        }
    }

    override suspend fun transferOwner(room: ByteArray, target: ByteArray): PushOutcome {
        val sync = syncClientForRoom(room)
        return sync.appendLogEntry(room) { index, prev ->
            LogEntry.sign(
                session.crypto, session.device.secretKey, room, index, prev, session.userIdentity.userKey, session.deviceCert,
                EntryType.TRANSFER_OWNER, CborValue.Map(mapOf(0L to CborValue.Bytes(target))), nowUnixSeconds(),
            )
        }
    }

    override suspend fun reject(room: ByteArray, target: ByteArray): PushOutcome {
        val sync = syncClientForRoom(room)
        return sync.appendLogEntry(room) { index, prev ->
            LogEntry.sign(
                session.crypto, session.device.secretKey, room, index, prev, session.userIdentity.userKey, session.deviceCert,
                EntryType.REJECT, CborValue.Map(mapOf(0L to CborValue.Bytes(target))), nowUnixSeconds(),
            )
        }
    }

    /** Needs the target's own `userBoxKey` to seal the current epoch key for them - read off
     * their own `join` entry's embedded cert (same log scan `SyncClient`'s own private
     * `collectUserBoxKeys` already does internally for `autoRotate`, duplicated here since that
     * helper isn't public: a public-API-only loop, not new protocol logic). */
    override suspend fun approve(room: ByteArray, target: ByteArray): PushOutcome {
        val sync = syncClientForRoom(room)
        val epoch = buildHistory(room).current.epoch
        val epochKey = sync.epochKeyFor(room, epoch)
            ?: return PushOutcome.Error(500, "invalid", "epoch key not available locally")
        val targetUserBox = collectUserBoxKeys(room)[target.toKey()]
            ?: return PushOutcome.Error(500, "invalid", "target's userBoxKey not found - have they sent a join entry yet?")
        val sealed = EpochKeys.seal(session.crypto, targetUserBox, room, epoch, epochKey)
        return sync.appendLogEntry(room) { index, prev ->
            LogEntry.sign(
                session.crypto, session.device.secretKey, room, index, prev, session.userIdentity.userKey, session.deviceCert,
                EntryType.APPROVE, CborValue.Map(mapOf(0L to CborValue.Bytes(target), 1L to CborValue.Bytes(sealed))), nowUnixSeconds(),
            )
        }
    }

    override suspend fun createInvite(room: ByteArray, requiresApproval: Boolean, expiresAt: Long?, maxUses: Int?): Result<InviteLink> {
        val relayHost = appLocalStore.knownRoom(room)?.relay_host
            ?: return Result.failure(IllegalStateException("room not known locally"))
        val sync = session.syncClientFor(relayHost)
        val roomName = appLocalStore.knownRoom(room)?.display_name
        val inviteId = session.crypto.randomBytes(16)
        val outcome = sync.createInvite(room, inviteId, requiresApproval, expiresAt, maxUses, roomName)
        if (outcome != PushOutcome.Ok) return Result.failure(IllegalStateException("createInvite failed: $outcome"))
        if (requiresApproval) {
            // §3.5: "For approval invites, no envelope exists" - no secret to carry in the link.
            return Result.success(InviteLink(relayHost, room, inviteId, inviteSecret = null))
        }
        val secret = session.store.inviteSecretsFor(room).firstOrNull { it.invite_id.contentEquals(inviteId) }?.secret
            ?: return Result.failure(IllegalStateException("invite secret missing right after createInvite"))
        return Result.success(InviteLink(relayHost, room, inviteId, secret))
    }

    override fun parseInviteLink(text: String): InviteLink? = InviteLink.parse(text)

    /** `GET /v1/invites/{room}/{inviteId}/envelope` is unauthenticated by design (PROTOCOL.md
     * §5/§3.5 - readable without auth, rate-limited per IP) - no signing/protocol logic is
     * involved in fetching it, so a plain [HttpURLConnection] GET against the documented path is
     * not re-implementing anything `core` owns; [InviteEnvelope.open] (core's own public function)
     * still does all the actual crypto. No convenience method for "fetch without joining" exists
     * on [SyncClient] today - this exists purely so the Join screen can preview the room name
     * before the user commits to actually joining. */
    override suspend fun peekInviteRoomName(link: InviteLink): String? {
        val secret = link.inviteSecret ?: return null // approval invite: no envelope exists to peek
        val envelopeBytes = fetchEnvelopeBytes(link.relayHost, link.roomId, link.inviteId) ?: return null
        return InviteEnvelope.open(session.crypto, secret, link.roomId, link.inviteId, envelopeBytes)?.roomName
    }

    private suspend fun fetchEnvelopeBytes(relayHost: String, room: ByteArray, inviteId: ByteArray): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("https://$relayHost/v1/invites/${b64Url(room)}/${b64Url(inviteId)}/envelope")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                if (connection.responseCode != 200) {
                    connection.disconnect()
                    return@withContext null
                }
                val bytes = connection.inputStream.use { it.readBytes() }
                connection.disconnect()
                bytes
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Joining an **open** (no-approval) invite goes through core's own `joinViaLink` unchanged.
     * Joining an **approval-required** one has no envelope to fetch/decrypt at all (§3.5) - the
     * epoch key arrives later via the owner/admin's `approve` entry instead - so this only needs
     * the same two already-public calls `joinViaLink`'s own open-invite path makes *after* its
     * envelope step (`pullLog` then `appendLogEntry`); no `core` change is needed for this path.
     */
    override suspend fun joinViaLink(link: InviteLink): PushOutcome {
        val sync = session.syncClientFor(link.relayHost)
        val outcome = if (link.inviteSecret != null) {
            sync.joinViaLink(link.roomId, link.inviteId, link.inviteSecret)
        } else {
            val pullOutcome = sync.pullLog(link.roomId)
            if (pullOutcome is SyncOutcome.Error) {
                return PushOutcome.Error(pullOutcome.status, pullOutcome.code, pullOutcome.message)
            }
            sync.appendLogEntry(link.roomId) { index, prev ->
                LogEntry.sign(
                    session.crypto, session.device.secretKey, link.roomId, index, prev, session.userIdentity.userKey, session.deviceCert,
                    EntryType.JOIN, CborValue.Map(mapOf(0L to CborValue.Bytes(link.inviteId))), nowUnixSeconds(),
                )
            }
        }
        if (outcome == PushOutcome.Ok) {
            val roomName = peekInviteRoomName(link) ?: "Squaddle room"
            val now = System.currentTimeMillis()
            appLocalStore.insertKnownRoom(link.roomId, roomName, link.relayHost, now, now)
        }
        return outcome
    }
}
