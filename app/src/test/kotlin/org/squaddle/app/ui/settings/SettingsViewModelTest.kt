package org.squaddle.app.ui.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.squaddle.app.backup.BackupReadOutcome
import org.squaddle.app.backup.BackupWriteOutcome
import org.squaddle.app.backup.FakeAutoBackupSettingsStore
import org.squaddle.app.backup.FakeBackupManager
import org.squaddle.app.backup.FakeBackupPassphraseVault
import org.squaddle.app.data.FakeCrypto
import org.squaddle.app.store.FakeAppLocalStore
import org.squaddle.app.update.SelfUpdateChecker
import org.squaddle.core.backup.BackupContent
import org.squaddle.core.backup.BackupRoomRef
import org.squaddle.core.identity.UserIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Against fakes - no Android framework, no real crypto, no network (same shape as
 * [org.squaddle.app.ui.room.RoomViewModelTest]). */
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val identity = UserIdentity.fromEntropy(FakeCrypto, ByteArray(32) { 0x42 })
    private val room = ByteArray(32) { 0x01 }

    private fun newViewModel(
        appLocalStore: FakeAppLocalStore = FakeAppLocalStore(),
        backupManager: FakeBackupManager = FakeBackupManager(),
        passphraseVault: FakeBackupPassphraseVault = FakeBackupPassphraseVault(),
        autoBackupSettings: FakeAutoBackupSettingsStore = FakeAutoBackupSettingsStore(),
    ): SettingsViewModel = SettingsViewModel(FakeCrypto, identity, appLocalStore, backupManager, passphraseVault, autoBackupSettings, SelfUpdateChecker())

    @Test
    fun mnemonicIsPopulatedFromTheIdentityAtConstruction() {
        val vm = newViewModel()
        assertEquals(identity.toMnemonic(FakeCrypto), vm.uiState.value.mnemonic)
        assertFalse(vm.uiState.value.seedRevealed)
    }

    @Test
    fun onSeedAuthenticatedRevealsAndHideSeedHidesItAgain() {
        val vm = newViewModel()
        vm.onSeedAuthenticated()
        assertTrue(vm.uiState.value.seedRevealed)
        vm.hideSeed()
        assertFalse(vm.uiState.value.seedRevealed)
    }

    @Test
    fun createBackupSendsEveryKnownRoomAndReportsSuccess() = runTest(testDispatcher) {
        val store = FakeAppLocalStore().apply {
            insertKnownRoom(room, "Camping Trip", "relay.squaddle.org", 1L, 1L)
        }
        val manager = FakeBackupManager()
        val vm = newViewModel(appLocalStore = store, backupManager = manager)

        vm.createBackup("content://backup", "correct horse battery staple")
        advanceUntilIdle()

        assertEquals(listOf("content://backup" to "correct horse battery staple"), manager.writeCalls)
        assertEquals(1, manager.writtenRooms.single().size)
        assertEquals("Backup saved.", vm.uiState.value.backupResultMessage)
        assertFalse(vm.uiState.value.backupInProgress)
    }

    @Test
    fun createBackupReportsFailureMessage() = runTest(testDispatcher) {
        val manager = FakeBackupManager().apply { writeOutcome = BackupWriteOutcome.Error("disk full") }
        val vm = newViewModel(backupManager = manager)

        vm.createBackup("content://backup", "passphrase")
        advanceUntilIdle()

        assertEquals("Backup failed: disk full", vm.uiState.value.backupResultMessage)
    }

    @Test
    fun restoreBackupImportsRoomsWhenEntropyMatches() = runTest(testDispatcher) {
        val store = FakeAppLocalStore()
        val manager = FakeBackupManager().apply {
            readOutcome = BackupReadOutcome.Ok(
                BackupContent(
                    entropy = identity.entropy,
                    rooms = listOf(BackupRoomRef(org.squaddle.core.encoding.Base32.encode(room) + "@relay.squaddle.org", "Camping Trip")),
                    createdAt = 1_700_000_000L,
                ),
            )
        }
        val vm = newViewModel(appLocalStore = store, backupManager = manager)

        vm.restoreBackup("content://backup", "correct horse battery staple")
        advanceUntilIdle()

        assertEquals("Restored 1 room(s).", vm.uiState.value.restoreResultMessage)
        assertTrue(store.allKnownRooms().any { it.room_id.contentEquals(room) && it.display_name == "Camping Trip" })
    }

    @Test
    fun restoreBackupRefusesAMismatchedAccountWithoutImportingAnything() = runTest(testDispatcher) {
        val store = FakeAppLocalStore()
        val manager = FakeBackupManager().apply {
            readOutcome = BackupReadOutcome.Ok(
                BackupContent(entropy = ByteArray(32) { 0x99.toByte() }, rooms = emptyList(), createdAt = 1L),
            )
        }
        val vm = newViewModel(appLocalStore = store, backupManager = manager)

        vm.restoreBackup("content://backup", "whatever")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.restoreResultMessage.orEmpty().contains("different account"))
        assertTrue(store.allKnownRooms().isEmpty())
    }

    @Test
    fun restoreBackupReportsACleanErrorForAWrongPassphrase() = runTest(testDispatcher) {
        val manager = FakeBackupManager().apply { readOutcome = BackupReadOutcome.Error("wrong passphrase, or not a Squaddle backup file") }
        val vm = newViewModel(backupManager = manager)

        vm.restoreBackup("content://backup", "wrong")
        advanceUntilIdle()

        assertEquals("wrong passphrase, or not a Squaddle backup file", vm.uiState.value.restoreResultMessage)
    }

    @Test
    fun setAutoBackupEnablePersistsSettingsAndPassphrase() {
        val settings = FakeAutoBackupSettingsStore()
        val vault = FakeBackupPassphraseVault()
        val vm = newViewModel(autoBackupSettings = settings, passphraseVault = vault)

        vm.setAutoBackup(enabled = true, uriString = "content://auto", passphrase = "secret", intervalHours = 12)

        assertTrue(vm.uiState.value.autoBackupEnabled)
        assertEquals(12, vm.uiState.value.autoBackupIntervalHours)
        assertTrue(settings.isEnabled())
        assertEquals("content://auto", settings.uriString())
        assertEquals("secret", vault.load())
    }

    @Test
    fun setAutoBackupDisableClearsThePassphrase() {
        val settings = FakeAutoBackupSettingsStore()
        val vault = FakeBackupPassphraseVault().apply { save("secret") }
        val vm = newViewModel(autoBackupSettings = settings, passphraseVault = vault)

        vm.setAutoBackup(enabled = false, uriString = null, passphrase = null, intervalHours = 12)

        assertFalse(vm.uiState.value.autoBackupEnabled)
        assertFalse(settings.isEnabled())
        assertEquals(null, vault.load())
    }

    @Test
    fun updateAutoBackupIntervalKeepsTheExistingUriAndPassphrase() {
        val settings = FakeAutoBackupSettingsStore()
        val vault = FakeBackupPassphraseVault()
        val vm = newViewModel(autoBackupSettings = settings, passphraseVault = vault)
        vm.setAutoBackup(enabled = true, uriString = "content://auto", passphrase = "secret", intervalHours = 24)

        vm.updateAutoBackupInterval(6)

        assertEquals(6, vm.uiState.value.autoBackupIntervalHours)
        assertEquals("content://auto", settings.uriString())
        assertEquals("secret", vault.load())
    }

    @Test
    fun updateAutoBackupIntervalIsANoOpWhenNotEnabled() {
        val settings = FakeAutoBackupSettingsStore()
        val vm = newViewModel(autoBackupSettings = settings)

        vm.updateAutoBackupInterval(6)

        assertFalse(vm.uiState.value.autoBackupEnabled)
        assertFalse(settings.isEnabled())
    }
}
