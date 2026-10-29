package org.squaddle.app.ui.room

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.squaddle.app.data.FakeCrypto
import org.squaddle.app.data.FakeSyncGateway
import org.squaddle.app.data.RoomMaterialization
import org.squaddle.app.push.FakeRoomMuteStore
import org.squaddle.core.membership.MembershipStateImpl
import org.squaddle.core.membership.Role
import org.squaddle.core.ops.Comment
import org.squaddle.core.ops.Event
import org.squaddle.core.ops.Idea
import org.squaddle.core.ops.LwwStamp
import org.squaddle.core.ops.MembershipHistory
import org.squaddle.core.ops.RoomState
import org.squaddle.core.ops.Rsvp
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
    private val muteStore = FakeRoomMuteStore()

    private fun key(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun stamp(lamport: Long, device: ByteArray = myKey) = LwwStamp(lamport, device)

    private fun idea(id: ByteArray, createdBy: ByteArray, title: String, desc: String? = null): Idea = Idea(
        id = id, createdBy = createdBy, title = title, titleStamp = stamp(1),
        desc = desc, descStamp = stamp(1), tags = emptyList(), tagsStamp = stamp(1),
        status = null, statusStamp = stamp(0), deleted = false, deletedStamp = stamp(0),
    )

    private fun event(id: ByteArray, createdBy: ByteArray, title: String, start: Long, cancelled: Boolean = false): Event = Event(
        id = id, createdBy = createdBy, ideaId = null, title = title, titleStamp = stamp(1),
        start = start, startStamp = stamp(1), end = null, endStamp = stamp(0),
        tz = "UTC", tzStamp = stamp(1), location = null, locationStamp = stamp(0),
        allDay = false, allDayStamp = stamp(1), cancelled = cancelled, cancelledStamp = stamp(0),
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
        val vm = RoomViewModel(room, myKey, gateway, muteStore)
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
        val vm = RoomViewModel(room, myKey, gateway, muteStore)
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
        val vm = RoomViewModel(room, myKey, gateway, muteStore)
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
        val vm = RoomViewModel(room, myKey, gateway, muteStore)
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
        val vm = RoomViewModel(room, myKey, gateway, muteStore)
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
        val vm = RoomViewModel(room, myKey, gateway, muteStore)
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
        val vm = RoomViewModel(room, myKey, gateway, muteStore)
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
        val vm = RoomViewModel(room, myKey, gateway, muteStore)
        advanceUntilIdle()

        vm.createIdea("   ", null)
        advanceUntilIdle()

        assertTrue(gateway.createdIdeas.isEmpty())
    }

    @Test
    fun toggleMutePersistsToTheStoreAndFlipsUiState() = runTest(testDispatcher) {
        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(RoomState(), emptyMap())
            myRoleResult = Role.MEMBER
        }
        val localMuteStore = FakeRoomMuteStore()
        val vm = RoomViewModel(room, myKey, gateway, localMuteStore)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isMuted)

        vm.toggleMute()
        assertTrue(vm.uiState.value.isMuted)
        assertTrue(localMuteStore.isMuted(room))

        vm.toggleMute()
        assertFalse(vm.uiState.value.isMuted)
        assertFalse(localMuteStore.isMuted(room))
    }

    @Test
    fun eventsSortedByStartWithRsvpCountsAndCancelledOnesExcluded() = runTest(testDispatcher) {
        val eventA = event(ByteArray(16) { 1 }, myKey, "Later", start = 200L)
        val eventB = event(ByteArray(16) { 2 }, myKey, "Earlier", start = 100L)
        val cancelledEvent = event(ByteArray(16) { 3 }, myKey, "Cancelled", start = 50L, cancelled = true)
        val rsvps = mapOf(
            key(eventA.id) + key(myKey) to Rsvp("yes", stamp(1)),
            key(eventA.id) + key(otherKey) to Rsvp("no", stamp(1)),
            key(eventB.id) + key(otherKey) to Rsvp("maybe", stamp(1)),
        )
        val state = RoomState(events = mapOf(key(eventA.id) to eventA, key(eventB.id) to eventB, key(cancelledEvent.id) to cancelledEvent), rsvps = rsvps)
        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(state, emptyMap())
            myRoleResult = Role.MEMBER
        }
        val vm = RoomViewModel(room, myKey, gateway, muteStore)
        advanceUntilIdle()

        val events = vm.uiState.value.events
        assertEquals(listOf("Earlier", "Later"), events.map { it.title }, "sorted by start ascending, cancelled excluded")
        val later = events.first { it.title == "Later" }
        assertEquals("yes", later.myRsvp)
        assertEquals(1, later.yesCount)
        assertEquals(1, later.noCount)
        val earlier = events.first { it.title == "Earlier" }
        assertEquals(1, earlier.maybeCount)
        assertEquals(null, earlier.myRsvp)
    }

    @Test
    fun canCreateEventIsTrueForAdminRegardlessOfRoomSetting() = runTest(testDispatcher) {
        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(RoomState(), emptyMap())
            myRoleResult = Role.ADMIN
        }
        val vm = RoomViewModel(room, myKey, gateway, muteStore)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.canCreateEvent)
    }

    @Test
    fun canCreateEventDefaultsTrueForMemberWithNoRoomSettingsSnapshotYet() = runTest(testDispatcher) {
        // membersCanCreateEvents defaults to true with no snapshot at all
        // (MembershipHistory.membersCanCreateEventsAt's own doc comment) - exercising an actual
        // "false" needs a real applied ROOM_SETTINGS log entry, which belongs to core's own
        // MembershipStateImpl/RoomState tests, not this ViewModel (it only reads the result,
        // never re-implements the authorization rule).
        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(RoomState(), emptyMap())
            myRoleResult = Role.MEMBER
            history = MembershipHistory.build(emptyList(), MembershipStateImpl.empty(FakeCrypto))
        }
        val vm = RoomViewModel(room, myKey, gateway, muteStore)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.canCreateEvent)
    }

    @Test
    fun createEventIgnoresBlankTitle() = runTest(testDispatcher) {
        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(RoomState(), emptyMap())
            myRoleResult = Role.MEMBER
        }
        val vm = RoomViewModel(room, myKey, gateway, muteStore)
        advanceUntilIdle()

        vm.createEvent(ideaId = null, title = "   ", start = 1L, end = null, tz = "UTC", location = null, allDay = false)
        advanceUntilIdle()

        assertTrue(gateway.createdEvents.isEmpty())
    }

    @Test
    fun createEventAndRsvpCallTheGateway() = runTest(testDispatcher) {
        val eventId = ByteArray(16) { 9 }
        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(RoomState(), emptyMap())
            myRoleResult = Role.MEMBER
        }
        val vm = RoomViewModel(room, myKey, gateway, muteStore)
        advanceUntilIdle()

        vm.createEvent(ideaId = null, title = "Camping weekend", start = 1_700_000_000L, end = null, tz = "UTC", location = "Lake", allDay = true)
        vm.rsvp(eventId, "yes")
        advanceUntilIdle()

        assertEquals(listOf("Camping weekend"), gateway.createdEvents)
        assertEquals(listOf(key(eventId) to "yes"), gateway.rsvps)
    }

    @Test
    fun cancelEventCallsTheGateway() = runTest(testDispatcher) {
        val eventId = ByteArray(16) { 7 }
        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(RoomState(), emptyMap())
            myRoleResult = Role.OWNER
        }
        val vm = RoomViewModel(room, myKey, gateway, muteStore)
        advanceUntilIdle()

        vm.cancelEvent(eventId)
        advanceUntilIdle()

        assertEquals(listOf(key(eventId)), gateway.cancelledEvents)
    }

    @Test
    fun selectTabSwitchesTheUiState() = runTest(testDispatcher) {
        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(RoomState(), emptyMap())
            myRoleResult = Role.MEMBER
        }
        val vm = RoomViewModel(room, myKey, gateway, muteStore)
        advanceUntilIdle()
        assertEquals(RoomTab.IDEAS, vm.uiState.value.selectedTab)

        vm.selectTab(RoomTab.EVENTS)

        assertEquals(RoomTab.EVENTS, vm.uiState.value.selectedTab)
    }

    @Test
    fun startsAlreadyMutedWhenTheStoreSaysSo() = runTest(testDispatcher) {
        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(RoomState(), emptyMap())
            myRoleResult = Role.MEMBER
        }
        val localMuteStore = FakeRoomMuteStore().apply { setMuted(room, true) }
        val vm = RoomViewModel(room, myKey, gateway, localMuteStore)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isMuted)
    }

    @Test
    fun loadsTheRoomTierFromGetLimits() = runTest(testDispatcher) {
        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(RoomState(), emptyMap())
            myRoleResult = Role.MEMBER
            getLimitsResult = Result.success(org.squaddle.core.sync.RoomLimits("boosted", 100, 50, null, 1_073_741_824L, 25, null))
        }
        val vm = RoomViewModel(room, myKey, gateway, muteStore)
        advanceUntilIdle()

        assertEquals("boosted", vm.uiState.value.tier)
    }

    @Test
    fun aFailedLimitsFetchLeavesTierNullWithoutBlockingTheRestOfTheScreen() = runTest(testDispatcher) {
        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(RoomState(), emptyMap())
            myRoleResult = Role.MEMBER
            getLimitsResult = Result.failure(RuntimeException("relay unreachable"))
        }
        val vm = RoomViewModel(room, myKey, gateway, muteStore)
        advanceUntilIdle()

        assertEquals(null, vm.uiState.value.tier)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun pullToRefreshSetsIsRefreshingWhileRunningThenClearsIt() = runTest(testDispatcher) {
        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(RoomState(), emptyMap())
            myRoleResult = Role.MEMBER
        }
        val vm = RoomViewModel(room, myKey, gateway, muteStore)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isRefreshing)

        vm.pullToRefresh()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isRefreshing, "must clear isRefreshing once the refresh actually finishes")
    }

    @Test
    fun plainRefreshNeverSetsIsRefreshing() = runTest(testDispatcher) {
        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(RoomState(), emptyMap())
            myRoleResult = Role.MEMBER
        }
        val vm = RoomViewModel(room, myKey, gateway, muteStore)
        advanceUntilIdle()

        vm.refresh()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isRefreshing, "button-triggered refresh() must not flash the pull-to-refresh indicator")
    }

    @Test
    fun toggleVoteAppliesOptimisticUpdateBeforeTheNetworkCallResolves() = runTest(testDispatcher) {
        val ideaA = idea(ByteArray(16) { 1 }, myKey, "Idea A")
        val state = RoomState(ideas = mapOf(key(ideaA.id) to ideaA))
        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(state, emptyMap())
            myRoleResult = Role.MEMBER
            opOutcome = true
        }
        val vm = RoomViewModel(room, myKey, gateway, muteStore)
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.ideas.single().myVote)

        vm.toggleVote(ideaA.id, 1)

        // No advanceUntilIdle yet - this is the synchronous optimistic update, before the
        // launched coroutine (and its network call) has run at all.
        val optimistic = vm.uiState.value.ideas.single()
        assertEquals(1, optimistic.myVote)
        assertEquals(1, optimistic.score)
    }

    @Test
    fun toggleVoteRollsBackOnFailureAndSetsActionError() = runTest(testDispatcher) {
        val ideaA = idea(ByteArray(16) { 1 }, myKey, "Idea A")
        val state = RoomState(ideas = mapOf(key(ideaA.id) to ideaA))
        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(state, emptyMap())
            myRoleResult = Role.MEMBER
            opOutcome = false
        }
        val vm = RoomViewModel(room, myKey, gateway, muteStore)
        advanceUntilIdle()

        vm.toggleVote(ideaA.id, 1)
        advanceUntilIdle()

        val rolledBack = vm.uiState.value.ideas.single()
        assertEquals(0, rolledBack.myVote)
        assertEquals(0, rolledBack.score)
        assertTrue(vm.uiState.value.actionError != null)
    }

    @Test
    fun addCommentAppendsOptimisticCommentBeforeTheNetworkCallResolves() = runTest(testDispatcher) {
        val ideaA = idea(ByteArray(16) { 1 }, myKey, "Idea A")
        val state = RoomState(ideas = mapOf(key(ideaA.id) to ideaA))
        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(state, emptyMap())
            myRoleResult = Role.MEMBER
            opOutcome = true
        }
        val vm = RoomViewModel(room, myKey, gateway, muteStore)
        advanceUntilIdle()
        vm.selectIdea(ideaA.id)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.comments.isEmpty())

        vm.addComment(ideaA.id, "nice idea!")

        val optimistic = vm.uiState.value.comments
        assertEquals(1, optimistic.size)
        assertEquals("nice idea!", optimistic.single().text)
        assertFalse(optimistic.single().canDelete, "an optimistic comment's own canDelete is unknown until the real one replaces it")
    }

    @Test
    fun addCommentRollsBackOnFailureAndSetsActionError() = runTest(testDispatcher) {
        val ideaA = idea(ByteArray(16) { 1 }, myKey, "Idea A")
        val state = RoomState(ideas = mapOf(key(ideaA.id) to ideaA))
        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(state, emptyMap())
            myRoleResult = Role.MEMBER
            opOutcome = false
        }
        val vm = RoomViewModel(room, myKey, gateway, muteStore)
        advanceUntilIdle()
        vm.selectIdea(ideaA.id)
        advanceUntilIdle()

        vm.addComment(ideaA.id, "nice idea!")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.comments.isEmpty(), "a failed post must roll back to the pre-optimistic snapshot")
        assertTrue(vm.uiState.value.actionError != null)
    }

    @Test
    fun dismissActionErrorClearsIt() = runTest(testDispatcher) {
        val ideaA = idea(ByteArray(16) { 1 }, myKey, "Idea A")
        val state = RoomState(ideas = mapOf(key(ideaA.id) to ideaA))
        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(state, emptyMap())
            myRoleResult = Role.MEMBER
            opOutcome = false
        }
        val vm = RoomViewModel(room, myKey, gateway, muteStore)
        advanceUntilIdle()
        vm.toggleVote(ideaA.id, 1)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.actionError != null)

        vm.dismissActionError()

        assertEquals(null, vm.uiState.value.actionError)
    }
}
