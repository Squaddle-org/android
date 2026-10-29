package org.squaddle.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.squaddle.app.backup.AutoBackupSettingsStore
import org.squaddle.app.backup.BackupManager
import org.squaddle.app.backup.BackupPassphraseVault
import org.squaddle.app.backup.BackupReadOutcome
import org.squaddle.app.backup.BackupWriteOutcome
import org.squaddle.app.backup.toBackupRoomRef
import org.squaddle.app.backup.toRoomIdAndRelayHost
import org.squaddle.app.store.AppLocalStore
import org.squaddle.core.crypto.Crypto
import org.squaddle.core.identity.UserIdentity

public data class SettingsUiState(
    public val seedRevealed: Boolean = false,
    public val mnemonic: List<String> = emptyList(),
    public val backupInProgress: Boolean = false,
    public val backupResultMessage: String? = null,
    public val restoreInProgress: Boolean = false,
    public val restoreResultMessage: String? = null,
    public val autoBackupEnabled: Boolean = false,
    public val autoBackupIntervalHours: Int = 24,
)

/**
 * Seed display (gated behind device credential/biometric - [org.squaddle.app.ui.settings.SettingsScreen]
 * calls [onSeedAuthenticated] only after a real [androidx.biometric.BiometricPrompt] success,
 * never on its own) plus manual and periodic encrypted backup (android/CLAUDE.md M4b item 7).
 *
 * Restoring a backup while already signed in (the only way this screen can be reached - there is
 * no onboarding-time equivalent, see the plan's own scope note) deliberately does NOT re-derive
 * or swap this device's identity: [restoreBackup] checks the decoded entropy against
 * [userIdentity]'s own and refuses (a clear error, not a silent no-op) if they differ, then only
 * imports `rooms[]` into [appLocalStore] - this device's signing/box keys and device cert stay
 * exactly as they are, which is the only coherent behavior for a session that's already live.
 */
public class SettingsViewModel(
    private val crypto: Crypto,
    private val userIdentity: UserIdentity,
    private val appLocalStore: AppLocalStore,
    private val backupManager: BackupManager,
    private val passphraseVault: BackupPassphraseVault,
    private val autoBackupSettings: AutoBackupSettingsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            mnemonic = userIdentity.toMnemonic(crypto),
            autoBackupEnabled = autoBackupSettings.isEnabled(),
            autoBackupIntervalHours = autoBackupSettings.intervalHours(),
        ),
    )
    public val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    public fun onSeedAuthenticated() {
        _uiState.value = _uiState.value.copy(seedRevealed = true)
    }

    public fun hideSeed() {
        _uiState.value = _uiState.value.copy(seedRevealed = false)
    }

    public fun clearBackupResult() {
        _uiState.value = _uiState.value.copy(backupResultMessage = null)
    }

    public fun clearRestoreResult() {
        _uiState.value = _uiState.value.copy(restoreResultMessage = null)
    }

    public fun createBackup(uriString: String, passphrase: String) {
        _uiState.value = _uiState.value.copy(backupInProgress = true, backupResultMessage = null)
        viewModelScope.launch {
            val rooms = appLocalStore.allKnownRooms().map { it.toBackupRoomRef() }
            val outcome = backupManager.writeBackup(uriString, passphrase, userIdentity.entropy, rooms)
            val message = when (outcome) {
                BackupWriteOutcome.Ok -> "Backup saved."
                is BackupWriteOutcome.Error -> "Backup failed: ${outcome.message}"
            }
            _uiState.value = _uiState.value.copy(backupInProgress = false, backupResultMessage = message)
        }
    }

    public fun restoreBackup(uriString: String, passphrase: String) {
        _uiState.value = _uiState.value.copy(restoreInProgress = true, restoreResultMessage = null)
        viewModelScope.launch {
            when (val outcome = backupManager.readBackup(uriString, passphrase)) {
                is BackupReadOutcome.Error -> {
                    _uiState.value = _uiState.value.copy(restoreInProgress = false, restoreResultMessage = outcome.message)
                }
                is BackupReadOutcome.Ok -> {
                    if (!outcome.content.entropy.contentEquals(userIdentity.entropy)) {
                        _uiState.value = _uiState.value.copy(
                            restoreInProgress = false,
                            restoreResultMessage = "This backup belongs to a different account - sign in with its seed to restore it.",
                        )
                        return@launch
                    }
                    val now = System.currentTimeMillis()
                    var imported = 0
                    for (room in outcome.content.rooms) {
                        val (roomId, relayHost) = room.toRoomIdAndRelayHost() ?: continue
                        appLocalStore.insertKnownRoom(roomId, room.name ?: "Squaddle room", relayHost, now, now)
                        imported++
                    }
                    _uiState.value = _uiState.value.copy(restoreInProgress = false, restoreResultMessage = "Restored $imported room(s).")
                }
            }
        }
    }

    /** Scheduling/cancelling the actual [androidx.work.WorkManager] periodic request is the
     * Composable's job ([SettingsScreen] reacts to [SettingsUiState.autoBackupEnabled]/
     * [SettingsUiState.autoBackupIntervalHours] changing) - same split this app already uses for
     * [org.squaddle.app.push.PushRegistrar]/[org.squaddle.app.push.PeriodicSyncScheduler], kept
     * out of ViewModels so they stay plain-JUnit-testable with no Android `Context`. */
    public fun setAutoBackup(enabled: Boolean, uriString: String?, passphrase: String?, intervalHours: Int) {
        if (enabled) {
            requireNotNull(uriString) { "enabling auto-backup needs a chosen file" }
            requireNotNull(passphrase) { "enabling auto-backup needs a passphrase" }
            autoBackupSettings.set(enabled = true, uriString = uriString, intervalHours = intervalHours)
            passphraseVault.save(passphrase)
        } else {
            autoBackupSettings.set(enabled = false, uriString = null, intervalHours = intervalHours)
            passphraseVault.clear()
        }
        _uiState.value = _uiState.value.copy(autoBackupEnabled = enabled, autoBackupIntervalHours = intervalHours)
    }

    /** Changing just the interval while already enabled - deliberately separate from
     * [setAutoBackup] so it never re-validates/overwrites the already-chosen URI or passphrase
     * (there is nothing new to pick or type here). */
    public fun updateAutoBackupInterval(hours: Int) {
        if (!_uiState.value.autoBackupEnabled) return
        autoBackupSettings.set(enabled = true, uriString = autoBackupSettings.uriString(), intervalHours = hours)
        _uiState.value = _uiState.value.copy(autoBackupIntervalHours = hours)
    }
}
