package org.squaddle.app.ui.voucher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.squaddle.app.data.FakeSyncGateway
import org.squaddle.app.data.RelayErrorException
import org.squaddle.core.sync.RoomLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Against a [FakeSyncGateway] - no Android framework, no real crypto, no network. */
class VoucherRedeemViewModelTest {

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

    @Test
    fun redeemIgnoresABlankCode() = runTest(testDispatcher) {
        val gateway = FakeSyncGateway()
        val vm = VoucherRedeemViewModel(room, gateway)

        vm.setCode("   ")
        vm.redeem()
        advanceUntilIdle()

        assertNull(vm.uiState.value.resultTier)
        assertFalse(vm.uiState.value.isRedeeming)
    }

    @Test
    fun successfulRedeemShowsTheNewTier() = runTest(testDispatcher) {
        val gateway = FakeSyncGateway().apply {
            redeemVoucherResult = Result.success(RoomLimits("boosted", 100, 50, null, 1_073_741_824L, 25, 1_800_000_000L))
        }
        val vm = VoucherRedeemViewModel(room, gateway)

        vm.setCode("ABCD-1234")
        vm.redeem()
        advanceUntilIdle()

        assertEquals("boosted", vm.uiState.value.resultTier)
        assertNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.isRedeeming)
    }

    @Test
    fun alreadyRedeemedCodeShowsTheRelaysOwnMessage() = runTest(testDispatcher) {
        val gateway = FakeSyncGateway().apply {
            redeemVoucherResult = Result.failure(RelayErrorException("invalid_entitlement", "voucher not found or already redeemed"))
        }
        val vm = VoucherRedeemViewModel(room, gateway)

        vm.setCode("USED-CODE")
        vm.redeem()
        advanceUntilIdle()

        assertEquals("voucher not found or already redeemed", vm.uiState.value.error)
        assertNull(vm.uiState.value.resultTier)
    }

    @Test
    fun dismissErrorClearsIt() = runTest(testDispatcher) {
        val gateway = FakeSyncGateway().apply {
            redeemVoucherResult = Result.failure(RelayErrorException("invalid_entitlement", "bad code"))
        }
        val vm = VoucherRedeemViewModel(room, gateway)
        vm.setCode("X")
        vm.redeem()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.error != null)

        vm.dismissError()

        assertNull(vm.uiState.value.error)
    }
}
