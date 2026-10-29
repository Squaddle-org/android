package org.squaddle.app.ui.join

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.squaddle.app.data.FakeSyncGateway
import org.squaddle.app.data.InviteLink
import org.squaddle.core.sync.PushOutcome
import kotlin.test.Test
import kotlin.test.assertIs

/** Against a [FakeSyncGateway] - no Android framework, no real network. */
class JoinViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val link = InviteLink("relay.squaddle.org", ByteArray(32), ByteArray(16), ByteArray(32))

    @Test
    fun startsIdle() {
        val vm = JoinViewModel(FakeSyncGateway())
        assertIs<JoinUiState.Idle>(vm.uiState.value)
    }

    @Test
    fun garbageTextProducesAnError() {
        val vm = JoinViewModel(FakeSyncGateway())
        vm.handleLinkText("not a link")
        assertIs<JoinUiState.Error>(vm.uiState.value)
    }

    @Test
    fun validLinkPeeksRoomNameThenReachesConfirm() = runTest(testDispatcher) {
        val gateway = FakeSyncGateway().apply { peekRoomName = "Camping Trip" }
        val vm = JoinViewModel(gateway)

        vm.handleLinkText(link.toUriString())
        assertIs<JoinUiState.Parsing>(vm.uiState.value)
        advanceUntilIdle()

        val confirm = assertIs<JoinUiState.Confirm>(vm.uiState.value)
        kotlin.test.assertEquals("Camping Trip", confirm.roomName)
    }

    @Test
    fun confirmSuccessReachesJoined() = runTest(testDispatcher) {
        val gateway = FakeSyncGateway().apply { joinOutcome = PushOutcome.Ok }
        val vm = JoinViewModel(gateway)

        vm.openWithLink(link)
        advanceUntilIdle()
        vm.confirm()
        assertIs<JoinUiState.Joining>(vm.uiState.value)
        advanceUntilIdle()

        assertIs<JoinUiState.Joined>(vm.uiState.value)
    }

    @Test
    fun confirmFailureReachesErrorWithMessage() = runTest(testDispatcher) {
        val gateway = FakeSyncGateway().apply { joinOutcome = PushOutcome.Error(409, "room_full", "this room is full") }
        val vm = JoinViewModel(gateway)

        vm.openWithLink(link)
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()

        val error = assertIs<JoinUiState.Error>(vm.uiState.value)
        // M4b item 8: room_full gets friendlier copy than the relay's own raw message.
        kotlin.test.assertEquals("This room is full. Ask an admin to boost it for more space.", error.message)
    }

    @Test
    fun cancelReturnsToIdle() = runTest(testDispatcher) {
        val gateway = FakeSyncGateway().apply { peekRoomName = "Book Club" }
        val vm = JoinViewModel(gateway)

        vm.openWithLink(link)
        advanceUntilIdle()
        assertIs<JoinUiState.Confirm>(vm.uiState.value)

        vm.cancel()
        assertIs<JoinUiState.Idle>(vm.uiState.value)
    }
}
