package org.squaddle.app.ui.onboarding

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.squaddle.app.crypto.FakeIdentityVault
import org.squaddle.app.data.FakeCrypto
import org.squaddle.app.data.FakeSyncGateway
import org.squaddle.app.settings.FakeDisplayNameStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Against a [FakeSyncGateway] / [FakeIdentityVault] / [FakeCrypto] - no Android framework, no
 * real crypto, no network (the task's own "ViewModel unit tests against a fake sync client"). */
class OnboardingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        vault: FakeIdentityVault = FakeIdentityVault(),
        gateway: FakeSyncGateway = FakeSyncGateway(),
        displayNameStore: FakeDisplayNameStore = FakeDisplayNameStore(),
    ): OnboardingViewModel =
        OnboardingViewModel(
            crypto = FakeCrypto,
            identityVault = vault,
            displayNameStore = displayNameStore,
            openSession = { _, _, _ -> gateway },
        )

    @Test
    fun startsAtModeSelect() {
        assertIs<OnboardingUiState.ModeSelect>(viewModel().uiState.value)
    }

    @Test
    fun createShowsTwentyFourWordsAndGatesContinueOnWroteItDown() {
        val vm = viewModel()
        vm.startCreate()
        val shown = assertIs<OnboardingUiState.ShowSeed>(vm.uiState.value)
        assertEquals(24, shown.words.size)
        assertTrue(!shown.wroteItDown)

        vm.confirmSeedWritten() // not allowed yet
        assertIs<OnboardingUiState.ShowSeed>(vm.uiState.value)

        vm.toggleWroteItDown()
        vm.confirmSeedWritten()
        val nameEntry = assertIs<OnboardingUiState.NameEntry>(vm.uiState.value)
        assertTrue(!nameEntry.isRestore)
    }

    @Test
    fun finishSavesIdentityAndRegistersDevice() = runTest(testDispatcher) {
        val vault = FakeIdentityVault()
        val gateway = FakeSyncGateway()
        val vm = viewModel(vault, gateway)
        vm.startCreate()
        vm.toggleWroteItDown()
        vm.confirmSeedWritten()
        vm.updateDisplayName("Alex")
        vm.finish()
        advanceUntilIdle()

        assertTrue(vault.hasIdentity())
        assertEquals(OnboardingUiState.Done, vm.uiState.value)
    }

    @Test
    fun finishPersistsTheChosenDisplayNameForLaterRoomsToUse() = runTest(testDispatcher) {
        val displayNameStore = FakeDisplayNameStore()
        val vm = viewModel(displayNameStore = displayNameStore)
        vm.startCreate()
        vm.toggleWroteItDown()
        vm.confirmSeedWritten()
        vm.updateDisplayName("Alex")
        vm.finish()
        advanceUntilIdle()

        // RealSyncGateway.createRoom/joinViaLink/restoreKnownRooms all read this back afterward
        // (DisplayNameStore's own doc comment) - persisting it here is what makes MembersScreen
        // ever show a real name instead of falling back to a member's raw key.
        assertEquals("Alex", displayNameStore.displayName())
    }

    @Test
    fun finishOnTheRestorePathCallsRestoreKnownRoomsButCreatePathDoesNot() = runTest(testDispatcher) {
        val createGateway = FakeSyncGateway()
        val create = viewModel(gateway = createGateway)
        create.startCreate()
        create.toggleWroteItDown()
        create.confirmSeedWritten()
        create.updateDisplayName("Alex")
        create.finish()
        advanceUntilIdle()
        assertEquals(0, createGateway.restoreKnownRoomsCalls)

        val restoreVault = FakeIdentityVault()
        val create2 = viewModel()
        create2.startCreate()
        val words = (create2.uiState.value as OnboardingUiState.ShowSeed).words

        val restoreGateway = FakeSyncGateway()
        val restore = viewModel(vault = restoreVault, gateway = restoreGateway)
        restore.startRestore()
        restore.updateSeedInput(words.joinToString(" "))
        restore.submitSeed()
        restore.updateDisplayName("Alex")
        restore.finish()
        advanceUntilIdle()

        assertEquals(1, restoreGateway.restoreKnownRoomsCalls)
        assertEquals(OnboardingUiState.Done, restore.uiState.value)
    }

    @Test
    fun restoreWithInvalidPhraseShowsErrorAndStaysOnEnterSeed() {
        val vm = viewModel()
        vm.startRestore()
        vm.updateSeedInput("not a valid seed phrase at all")
        vm.submitSeed()
        val state = assertIs<OnboardingUiState.EnterSeed>(vm.uiState.value)
        assertTrue(state.error != null)
    }

    @Test
    fun restoreWithValidPhraseReachesNameEntryMarkedAsRestore() {
        val create = viewModel()
        create.startCreate()
        val words = (create.uiState.value as OnboardingUiState.ShowSeed).words

        val restore = viewModel()
        restore.startRestore()
        restore.updateSeedInput(words.joinToString(" "))
        restore.submitSeed()
        val nameEntry = assertIs<OnboardingUiState.NameEntry>(restore.uiState.value)
        assertTrue(nameEntry.isRestore)
    }

    @Test
    fun backToModeSelectClearsPendingState() {
        val vm = viewModel()
        vm.startRestore()
        vm.updateSeedInput("something")
        vm.backToModeSelect()
        assertIs<OnboardingUiState.ModeSelect>(vm.uiState.value)
    }
}
