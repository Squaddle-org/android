package org.squaddle.app.ui.room

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.squaddle.app.data.FakeSyncGateway
import org.squaddle.app.data.RoomMaterialization
import org.squaddle.core.membership.Role
import org.squaddle.core.ops.Comment
import org.squaddle.core.ops.Idea
import org.squaddle.core.ops.LwwStamp
import org.squaddle.core.ops.RoomState
import org.squaddle.core.ops.Vote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Against a [FakeSyncGateway] - no Android framework, no real crypto, no network. */
class RoomViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val room = ByteArray(32) { 0x01 }
    private val myKey = ByteArray(32) { 0xAA.toByte() }
    private val otherKey = ByteArray(32) { 0xBB.toByte() }

    private fun key(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun stamp(lamport: Long, device: ByteArray = myKey) = LwwStamp(lamport, device)

    private fun idea(id: ByteArray, createdBy: ByteArray, title: String, desc: String? = null): Idea = Idea(
        id = id, createdBy = createdBy, title = title, titleStamp = stamp(1),
        desc = desc, descStamp = stamp(1), tags = emptyList(), tagsStamp = stamp(1),
        status = null, statusStamp = stamp(0), deleted = false, deletedStamp = stamp(0),
    )

    @Test
    fun ideasSortedByScoreThenRecency() = runTest(testDispatcher) {
        val ideaA = idea(ByteArray(16) { 1 }, myKey, "Idea A") // score 1
        val ideaB = idea(ByteArray(16) { 2 }, myKey, "Idea B") // score 2, newer
        val ideaC = idea(ByteArray(16) { 3 }, myKey, "Idea C") // score 2, older

        val votes = mapOf(
            key(ideaA.id) + key(otherKey) to Vote(1, stamp(1)),
            key(ideaB.id) + key(otherKey) to Vote(1, stamp(1)),
            key(ideaB.id) + key(myKey) to Vote(1, stamp(1)),
            key(ideaC.id) + key(otherKey) to Vote(2, stamp(1)),
        )
        val state = RoomState(ideas = mapOf(key(ideaA.id) to ideaA, key(ideaB.id) to ideaB, key(ideaC.id) to ideaC), votes = votes)
        val lamports = mapOf(key(ideaA.id) to 1L, key(ideaB.id) to 3L, key(ideaC.id) to 2L)

        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(state, lamports)
            myRoleResult = Role.MEMBER
        }
        val vm = RoomViewModel(room, myKey, gateway)
        advanceUntilIdle()

        val titles = vm.uiState.value.ideas.map { it.title }
        assertEquals(listOf("Idea B", "Idea C", "Idea A"), titles) // B(2,lamport3) > C(2,lamport2) > A(1)
    }

    @Test
    fun deletedIdeasAreExcluded() = runTest(testDispatcher) {
        val live = idea(ByteArray(16) { 1 }, myKey, "Live")
        val gone = idea(ByteArray(16) { 2 }, myKey, "Gone").copy(deleted = true)
        val state = RoomState(ideas = mapOf(key(live.id) to live, key(gone.id) to gone))
        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(state, emptyMap())
            myRoleResult = Role.MEMBER
        }
        val vm = RoomViewModel(room, myKey, gateway)
        advanceUntilIdle()

        assertEquals(listOf("Live"), vm.uiState.value.ideas.map { it.title })
    }

    @Test
    fun ownIdeaIsEditableEvenAsPlainMember() = runTest(testDispatcher) {
        val mine = idea(ByteArray(16) { 1 }, myKey, "Mine")
        val theirs = idea(ByteArray(16) { 2 }, otherKey, "Theirs")
        val state = RoomState(ideas = mapOf(key(mine.id) to mine, key(theirs.id) to theirs))
        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(state, emptyMap())
            myRoleResult = Role.MEMBER
        }
        val vm = RoomViewModel(room, myKey, gateway)
        advanceUntilIdle()

        val ideas = vm.uiState.value.ideas.associateBy { it.title }
        assertTrue(ideas.getValue("Mine").canEditOrDelete)
        assertFalse(ideas.getValue("Theirs").canEditOrDelete)
    }

    @Test
    fun adminCanEditAnyIdea() = runTest(testDispatcher) {
        val theirs = idea(ByteArray(16) { 1 }, otherKey, "Theirs")
        val state = RoomState(ideas = mapOf(key(theirs.id) to theirs))
        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(state, emptyMap())
            myRoleResult = Role.ADMIN
        }
        val vm = RoomViewModel(room, myKey, gateway)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.ideas.single().canEditOrDelete)
    }

    @Test
    fun toggleVoteTogglesOffWhenSameDirectionClickedAgain() = runTest(testDispatcher) {
        val target = idea(ByteArray(16) { 1 }, otherKey, "Target")
        val state = RoomState(
            ideas = mapOf(key(target.id) to target),
            votes = mapOf(key(target.id) + key(myKey) to Vote(1, stamp(1))),
        )
        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(state, emptyMap())
            myRoleResult = Role.MEMBER
        }
        val vm = RoomViewModel(room, myKey, gateway)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.ideas.single().myVote)

        vm.toggleVote(target.id, 1) // clicking "up" again should clear it
        advanceUntilIdle()
        assertEquals(listOf(key(target.id) to 0), gateway.votes)
    }

    @Test
    fun selectingIdeaLoadsItsNonDeletedComments() = runTest(testDispatcher) {
        val targetIdea = idea(ByteArray(16) { 1 }, myKey, "Target")
        val keptComment = Comment(ByteArray(16) { 10 }, targetIdea.id, otherKey, "nice idea", deleted = false, deletedStamp = stamp(0))
        val deletedComment = Comment(ByteArray(16) { 11 }, targetIdea.id, otherKey, "retracted", deleted = true, deletedStamp = stamp(5))
        val otherIdeaComment = Comment(ByteArray(16) { 12 }, ByteArray(16) { 99 }, otherKey, "wrong idea", deleted = false, deletedStamp = stamp(0))
        val state = RoomState(
            ideas = mapOf(key(targetIdea.id) to targetIdea),
            comments = mapOf(
                key(keptComment.id) to keptComment,
                key(deletedComment.id) to deletedComment,
                key(otherIdeaComment.id) to otherIdeaComment,
            ),
        )
        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(state, emptyMap())
            myRoleResult = Role.MEMBER
        }
        val vm = RoomViewModel(room, myKey, gateway)
        advanceUntilIdle()

        vm.selectIdea(targetIdea.id)
        advanceUntilIdle()

        assertEquals(listOf("nice idea"), vm.uiState.value.comments.map { it.text })
    }

    @Test
    fun createIdeaCallsGatewayAndRefreshes() = runTest(testDispatcher) {
        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(RoomState(), emptyMap())
            myRoleResult = Role.MEMBER
        }
        val vm = RoomViewModel(room, myKey, gateway)
        advanceUntilIdle()

        vm.createIdea("New idea", "a description")
        advanceUntilIdle()

        assertEquals(listOf<Pair<String, String?>>("New idea" to "a description"), gateway.createdIdeas)
    }

    @Test
    fun blankIdeaTitleIsIgnored() = runTest(testDispatcher) {
        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(RoomState(), emptyMap())
            myRoleResult = Role.MEMBER
        }
        val vm = RoomViewModel(room, myKey, gateway)
        advanceUntilIdle()

        vm.createIdea("   ", null)
        advanceUntilIdle()

        assertTrue(gateway.createdIdeas.isEmpty())
    }
}
