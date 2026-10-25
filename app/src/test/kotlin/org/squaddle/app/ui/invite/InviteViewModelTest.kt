package org.squaddle.app.ui.invite

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Against a [FakeSyncGateway] - no Android framework, no real network. */
class InviteViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val room = ByteArray(32) { it.toByte() }

    @Test
    fun defaultsToOpenInviteNeverExpiresUnlimitedUses() {
        val vm = InviteViewModel(room, FakeSyncGateway())
        val state = vm.uiState.value
        assertEquals(false, state.requiresApproval)
        assertNull(state.expiresInDays)
        assertNull(state.maxUses)
    }

    @Test
    fun createInviteSucceedsAndExposesTheLink() = runTest(testDispatcher) {
        val link = InviteLink("relay.squaddle.org", room, ByteArray(16), ByteArray(32))
        val gateway = FakeSyncGateway().apply { createInviteResult = Result.success(link) }
        val vm = InviteViewModel(room, gateway)

        vm.setRequiresApproval(true)
        vm.setMaxUses(5)
        vm.createInvite()
        advanceUntilIdle()

        assertEquals(link, vm.uiState.value.createdLink)
        assertEquals(false, vm.uiState.value.isCreating)
    }

    @Test
    fun createInviteFailureSurfacesError() = runTest(testDispatcher) {
        val gateway = FakeSyncGateway().apply { createInviteResult = Result.failure(IllegalStateException("relay unreachable")) }
        val vm = InviteViewModel(room, gateway)

        vm.createInvite()
        advanceUntilIdle()

        assertEquals("relay unreachable", vm.uiState.value.error)
        assertNull(vm.uiState.value.createdLink)
    }

    @Test
    fun makeAnotherInviteClearsTheCreatedLink() = runTest(testDispatcher) {
        val link = InviteLink("relay.squaddle.org", room, ByteArray(16), ByteArray(32))
        val gateway = FakeSyncGateway().apply { createInviteResult = Result.success(link) }
        val vm = InviteViewModel(room, gateway)

        vm.createInvite()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.createdLink)

        vm.makeAnotherInvite()
        assertNull(vm.uiState.value.createdLink)
    }

    @Test
    fun dismissErrorClearsIt() = runTest(testDispatcher) {
        val gateway = FakeSyncGateway().apply { createInviteResult = Result.failure(IllegalStateException("boom")) }
        val vm = InviteViewModel(room, gateway)

        vm.createInvite()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.error)

        vm.dismissError()
        assertNull(vm.uiState.value.error)
    }
}
