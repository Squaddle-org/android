package org.squaddle.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.squaddle.app.backup.BackupManager
import org.squaddle.app.backup.BackupReadOutcome
import org.squaddle.app.backup.toRoomIdAndRelayHost
import org.squaddle.app.crypto.IdentityRecord
import org.squaddle.app.crypto.IdentityVault
import org.squaddle.app.data.SyncGateway
import org.squaddle.app.settings.DisplayNameStore
import org.squaddle.app.store.AppLocalStore
import org.squaddle.core.backup.BackupRoomRef
import org.squaddle.core.crypto.Crypto
import org.squaddle.core.crypto.KeyPair
import org.squaddle.core.identity.Bip39
import org.squaddle.core.identity.DeviceCert
import org.squaddle.core.identity.UserIdentity
import org.squaddle.core.sync.nowUnixSeconds

public sealed interface OnboardingUiState {
    public data object ModeSelect : OnboardingUiState
    public data class ShowSeed(val words: List<String>, val wroteItDown: Boolean) : OnboardingUiState
    public data class EnterSeed(val input: String, val error: String?) : OnboardingUiState
    /** [uriString] is the backup file the user just picked via SAF (`OnboardingScreen`'s own
     * `ActivityResultContracts.OpenDocument` launcher - picking the file itself needs no
     * ViewModel involvement, same "Composable owns the launcher" split `SettingsScreen` already
     * uses). */
    public data class EnterBackupPassphrase(val uriString: String, val error: String?) : OnboardingUiState
    public data class NameEntry(val displayName: String, val isRestore: Boolean) : OnboardingUiState
    public data object Saving : OnboardingUiState
    public data object Done : OnboardingUiState
    public data class Failed(val message: String) : OnboardingUiState
}

/**
 * Create: generate identity+device, show the 24-word seed with a required "I wrote it down"
 * confirm, ask a display name, persist, register the device.
 * Restore (two ways to recover the same identity): enter 24 words (validated via
 * [UserIdentity.fromMnemonic]), or pick a `.squaddle-backup` file + its passphrase
 * ([BackupManager.readBackup] decodes it into the same entropy, [UserIdentity.fromEntropy]
 * derives the identity exactly as [org.squaddle.app.di.AppContainer.restoreSessionIfPossible]
 * already does for a normal cold start). Either way, same device-registration flow follows; room
 * recovery then differs by *how* the rooms list is known - a typed seed phrase has nothing but
 * [SyncGateway.restoreKnownRooms] (PROTOCOL.md §5, spec v1.0.0-draft.3: every room the user holds
 * an active role in on the gateway's single configured relay), while a backup file already
 * carries its own `rooms[]` (each with its own relay host - can span multiple relays, which pure
 * seed-restore can't) - [finish] imports that list directly instead, the same way
 * [org.squaddle.app.ui.settings.SettingsViewModel.restoreBackup] already does for an
 * already-signed-in device restoring a second backup.
 * Never logs the mnemonic, entropy, passphrase, or any key material (CLAUDE.md).
 */
public class OnboardingViewModel(
    private val crypto: Crypto,
    private val identityVault: IdentityVault,
    private val displayNameStore: DisplayNameStore,
    private val backupManager: BackupManager,
    private val appLocalStore: AppLocalStore,
    private val openSession: (UserIdentity, KeyPair, DeviceCert) -> SyncGateway,
) : ViewModel() {

    private val _uiState = MutableStateFlow<OnboardingUiState>(OnboardingUiState.ModeSelect)
    public val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private var pendingIdentity: UserIdentity? = null

    /** Set only by [submitBackupPassphrase] on success - `null` means [finish] is on the plain
     * seed-restore (or create) path and should call [SyncGateway.restoreKnownRooms] as before. */
    private var pendingBackupRooms: List<BackupRoomRef>? = null

    /** The file [pendingBackupRooms] was decoded from - kept only so [stepBack] can rebuild
     * [OnboardingUiState.EnterBackupPassphrase] (re-prompting for the passphrase, not re-picking
     * the file) when backing up out of [OnboardingUiState.NameEntry]. */
    private var pendingBackupUriString: String? = null

    public fun startCreate() {
        val identity = UserIdentity.create(crypto)
        pendingIdentity = identity
        _uiState.value = OnboardingUiState.ShowSeed(identity.toMnemonic(crypto), wroteItDown = false)
    }

    public fun toggleWroteItDown() {
        val state = _uiState.value as? OnboardingUiState.ShowSeed ?: return
        _uiState.value = state.copy(wroteItDown = !state.wroteItDown)
    }

    public fun confirmSeedWritten() {
        val state = _uiState.value as? OnboardingUiState.ShowSeed ?: return
        if (!state.wroteItDown) return
        _uiState.value = OnboardingUiState.NameEntry(displayName = "", isRestore = false)
    }

    public fun startRestore() {
        _uiState.value = OnboardingUiState.EnterSeed(input = "", error = null)
    }

    public fun updateSeedInput(text: String) {
        val state = _uiState.value as? OnboardingUiState.EnterSeed ?: return
        _uiState.value = state.copy(input = text, error = null)
    }

    public fun submitSeed() {
        val state = _uiState.value as? OnboardingUiState.EnterSeed ?: return
        val words = state.input.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        try {
            pendingIdentity = UserIdentity.fromMnemonic(crypto, words)
            _uiState.value = OnboardingUiState.NameEntry(displayName = "", isRestore = true)
        } catch (e: Bip39.InvalidMnemonicException) {
            _uiState.value = state.copy(error = e.message ?: "invalid recovery phrase")
        }
    }

    /** Called once a SAF file picker (`OnboardingScreen`'s own launcher) returns a URI - just
     * moves to the passphrase step, nothing decoded yet. */
    public fun backupFilePicked(uriString: String) {
        _uiState.value = OnboardingUiState.EnterBackupPassphrase(uriString, error = null)
    }

    public fun submitBackupPassphrase(passphrase: String) {
        val state = _uiState.value as? OnboardingUiState.EnterBackupPassphrase ?: return
        viewModelScope.launch {
            when (val outcome = backupManager.readBackup(state.uriString, passphrase)) {
                is BackupReadOutcome.Error -> _uiState.value = state.copy(error = outcome.message)
                is BackupReadOutcome.Ok -> {
                    pendingIdentity = UserIdentity.fromEntropy(crypto, outcome.content.entropy)
                    pendingBackupRooms = outcome.content.rooms
                    pendingBackupUriString = state.uriString
                    _uiState.value = OnboardingUiState.NameEntry(displayName = "", isRestore = true)
                }
            }
        }
    }

    public fun backToModeSelect() {
        pendingIdentity = null
        pendingBackupRooms = null
        pendingBackupUriString = null
        _uiState.value = OnboardingUiState.ModeSelect
    }

    /** System back's entry point for onboarding's wizard (`OnboardingScreen`'s own
     * `BackHandler`) - returns `true` if it consumed the back press by moving to a previous
     * step, `false` if there's nowhere to go back to within onboarding (`ModeSelect`, or a
     * terminal/in-flight state), in which case the caller lets the press propagate (exits the
     * app - the only sane behavior this early, before there's even a rooms list to land on). */
    public fun stepBack(): Boolean {
        val state = _uiState.value
        return when (state) {
            is OnboardingUiState.ShowSeed, is OnboardingUiState.EnterSeed,
            is OnboardingUiState.EnterBackupPassphrase, is OnboardingUiState.Failed,
            -> {
                backToModeSelect()
                true
            }
            is OnboardingUiState.NameEntry -> {
                val backupUri = pendingBackupUriString
                val identity = pendingIdentity
                _uiState.value = when {
                    backupUri != null -> OnboardingUiState.EnterBackupPassphrase(backupUri, error = null)
                    state.isRestore -> OnboardingUiState.EnterSeed(input = identity?.toMnemonic(crypto)?.joinToString(" ").orEmpty(), error = null)
                    else -> OnboardingUiState.ShowSeed(words = identity?.toMnemonic(crypto).orEmpty(), wroteItDown = true)
                }
                true
            }
            is OnboardingUiState.ModeSelect, is OnboardingUiState.Saving, is OnboardingUiState.Done -> false
        }
    }

    public fun updateDisplayName(text: String) {
        val state = _uiState.value as? OnboardingUiState.NameEntry ?: return
        _uiState.value = state.copy(displayName = text)
    }

    public fun finish() {
        val state = _uiState.value as? OnboardingUiState.NameEntry ?: return
        val identity = pendingIdentity ?: run {
            _uiState.value = OnboardingUiState.Failed("no identity pending - go back and try again")
            return
        }
        _uiState.value = OnboardingUiState.Saving
        viewModelScope.launch {
            // Saved before the session even opens: RealSyncGateway.createRoom/joinViaLink/
            // restoreKnownRooms all read this back to set each room's own profile automatically
            // right after success (ProfileSet is per-room, there's no single global profile).
            if (state.displayName.isNotBlank()) displayNameStore.setDisplayName(state.displayName)
            val device = crypto.signSeedKeypair(crypto.randomBytes(32))
            val label = android.os.Build.MODEL ?: "Device"
            val issuedAt = nowUnixSeconds()
            val cert = DeviceCert.sign(crypto, identity.signing.secretKey, identity.userKey, identity.userBoxKey, device.publicKey, label, issuedAt)
            identityVault.save(
                IdentityRecord(
                    entropy = identity.entropy,
                    deviceSecretKey = device.secretKey,
                    devicePublicKey = device.publicKey,
                    deviceCertUser = cert.user,
                    deviceCertUserBox = cert.userBox,
                    deviceCertLabel = cert.label,
                    deviceCertIssuedAt = cert.issuedAt,
                    deviceCertSig = requireNotNull(cert.sig) { "DeviceCert.sign always sets sig" },
                ),
            )
            val gateway = openSession(identity, device, cert)
            // Best-effort: a failed device registration (e.g. no network right now) doesn't block
            // onboarding - it's a one-shot publish that can be retried by any later sync.
            gateway.registerDevice()
            val backupRooms = pendingBackupRooms
            if (backupRooms != null) {
                // A backup file's own rooms[] (each with its own relay host) rather than
                // restoreKnownRooms()'s single-relay relay-query - see this class's own doc
                // comment on why. Best-effort per room, same posture as registerDevice() above.
                //
                // KNOWN GAP (found via on-device testing, not yet fixed - pre-existing, not
                // introduced by this restore path): setProfile here reliably no-ops for any room
                // this device didn't itself create/join. Verified directly: the ProfileSet op
                // DOES get signed, enqueued, pushed, and pulled back (present in the local `ops`
                // table at a real cursor) - but every client's own op-open step rejects it,
                // because RealSyncGateway.certResolverFromLog (same gap in RealSyncGateway.pull's
                // own op materialization) only ever resolves a device's cert from an entry that
                // device itself authored in THIS room's own membership log. A freshly-restored
                // device never has one (it didn't create or join this room - the original device
                // did), so certOf(thisDevice) is always null here, and the op is silently dropped
                // by every mapNotNull in RealSyncGateway.materialize/opsSince, everywhere,
                // including on this very device. The relay's own GET /users/{user}/devices (which
                // would resolve this correctly) has no SyncClient wrapper in core at all yet -
                // fixing this for real means adding one plus changing certResolverFromLog to use
                // it, a protocol-adjacent change bigger than this restore feature; left as a
                // known, reported limitation rather than guessed at here. Affects the existing
                // seed-phrase restoreKnownRooms() path identically, not just this one.
                val now = System.currentTimeMillis()
                for (room in backupRooms) {
                    val (roomId, relayHost) = room.toRoomIdAndRelayHost() ?: continue
                    appLocalStore.insertKnownRoom(roomId, room.name ?: "Squaddle room", relayHost, now, now)
                    try {
                        gateway.pull(roomId)
                        if (state.displayName.isNotBlank()) gateway.setProfile(roomId, state.displayName, null)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Best-effort, see doc comment above - a failed pull/profile-set for one
                        // imported room must not abort importing the rest.
                    }
                }
            } else if (state.isRestore) {
                // Best-effort, same posture as registerDevice() above - the rooms list screen's
                // own pull-to-refresh covers a failed/partial recovery.
                gateway.restoreKnownRooms()
            }
            _uiState.value = OnboardingUiState.Done
        }
    }
}
