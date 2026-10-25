package org.squaddle.app.ui.rooms

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.squaddle.app.data.FakeSyncGateway
import org.squaddle.app.store.FakeAppLocalStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Against a [FakeSyncGateway] / [FakeAppLocalStore] - no Android framework, no real network. */
class RoomsListViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun roomId(byte: Byte): ByteArray = ByteArray(32) { byte }

    @Test
    fun emptyStoreStartsWithNoRooms() = runTest(testDispatcher) {
        val vm = RoomsListViewModel(FakeSyncGateway(), FakeAppLocalStore())
        advanceUntilIdle()
        assertTrue(vm.uiState.value.rooms.isEmpty())
        assertFalse(vm.uiState.value.isRefreshing)
    }

    @Test
    fun refreshListsKnownRoomsAndPullsEachOne() = runTest(testDispatcher) {
        val store = FakeAppLocalStore()
        store.insertKnownRoom(roomId(1), "Camping Trip", "relay.squaddle.org", joinedAt = 100L, lastActivityAt = 200L)
        store.insertKnownRoom(roomId(2), "Book Club", "relay.squaddle.org", joinedAt = 50L, lastActivityAt = 300L)
        val gateway = FakeSyncGateway()

        val vm = RoomsListViewModel(gateway, store)
        advanceUntilIdle()

        val rooms = vm.uiState.value.rooms
        assertEquals(2, rooms.size)
        // sorted by last activity desc, per FakeAppLocalStore.allKnownRooms()
        assertEquals("Book Club", rooms[0].displayName)
        assertEquals("Camping Trip", rooms[1].displayName)
    }

    @Test
    fun roomWithNewerSyncedCursorThanReadCursorIsUnread() = runTest(testDispatcher) {
        val store = FakeAppLocalStore()
        val room = roomId(3)
        store.insertKnownRoom(room, "Unread Room", "relay.squaddle.org", joinedAt = 0L, lastActivityAt = 0L)
        store.upsertReadCursor(room, lastReadLogIndex = 0L, lastReadOpsCursor = 0L)
        val gateway = FakeSyncGateway().apply { syncedCursorResult = 5L to 10L }

        val vm = RoomsListViewModel(gateway, store)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.rooms.single().hasUnread)
    }

    @Test
    fun roomWithMatchingCursorsIsNotUnread() = runTest(testDispatcher) {
        val store = FakeAppLocalStore()
        val room = roomId(4)
        store.insertKnownRoom(room, "Caught Up Room", "relay.squaddle.org", joinedAt = 0L, lastActivityAt = 0L)
        store.upsertReadCursor(room, lastReadLogIndex = 5L, lastReadOpsCursor = 10L)
        val gateway = FakeSyncGateway().apply { syncedCursorResult = 5L to 10L }

        val vm = RoomsListViewModel(gateway, store)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.rooms.single().hasUnread)
    }

    @Test
    fun createRoomSuccessTriggersRefresh() = runTest(testDispatcher) {
        val store = FakeAppLocalStore()
        val newRoomId = roomId(9)
        val gateway = FakeSyncGateway().apply {
            createRoomResult = Result.success(newRoomId)
        }
        val vm = RoomsListViewModel(gateway, store)
        advanceUntilIdle()

        // simulate what RealSyncGateway.createRoom would have recorded locally
        store.insertKnownRoom(newRoomId, "New Room", "relay.squaddle.org", joinedAt = 1L, lastActivityAt = 1L)
        vm.createRoom("New Room")
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.rooms.size)
        assertEquals(null, vm.uiState.value.createRoomError)
    }

    @Test
    fun createRoomFailureSurfacesError() = runTest(testDispatcher) {
        val gateway = FakeSyncGateway().apply {
            createRoomResult = Result.failure(IllegalStateException("relay unreachable"))
        }
        val vm = RoomsListViewModel(gateway, FakeAppLocalStore())
        advanceUntilIdle()

        vm.createRoom("Doomed Room")
        advanceUntilIdle()

        assertEquals("relay unreachable", vm.uiState.value.createRoomError)
    }

    @Test
    fun blankRoomNameIsIgnored() = runTest(testDispatcher) {
        val gateway = FakeSyncGateway()
        val vm = RoomsListViewModel(gateway, FakeAppLocalStore())
        advanceUntilIdle()

        vm.createRoom("   ")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.rooms.isEmpty())
        assertEquals(null, vm.uiState.value.createRoomError)
    }
}
