package org.squaddle.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.squaddle.app.crypto.IdentityRecord
import org.squaddle.app.crypto.IdentityVault
import org.squaddle.app.data.SyncGateway
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
    public data class NameEntry(val displayName: String, val isRestore: Boolean) : OnboardingUiState
    public data object Saving : OnboardingUiState
    public data object Done : OnboardingUiState
    public data class Failed(val message: String) : OnboardingUiState
}

/**
 * Create: generate identity+device, show the 24-word seed with a required "I wrote it down"
 * confirm, ask a display name, persist, register the device.
 * Restore: enter 24 words (validated via [UserIdentity.fromMnemonic]), same device-registration
 * flow, then [SyncGateway.restoreKnownRooms] (PROTOCOL.md §5, spec v1.0.0-draft.3) recovers every
 * room the user holds an active role in on the gateway's configured relay - rooms on a different
 * relay still need a fresh invite link.
 * Never logs the mnemonic, entropy, or any key material (CLAUDE.md).
 */
public class OnboardingViewModel(
    private val crypto: Crypto,
    private val identityVault: IdentityVault,
    private val openSession: (UserIdentity, KeyPair, DeviceCert) -> SyncGateway,
) : ViewModel() {

    private val _uiState = MutableStateFlow<OnboardingUiState>(OnboardingUiState.ModeSelect)
    public val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private var pendingIdentity: UserIdentity? = null

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

    public fun backToModeSelect() {
        pendingIdentity = null
        _uiState.value = OnboardingUiState.ModeSelect
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
            if (state.isRestore) {
                // Best-effort, same posture as registerDevice() above - the rooms list screen's
                // own pull-to-refresh covers a failed/partial recovery.
                gateway.restoreKnownRooms()
            }
            _uiState.value = OnboardingUiState.Done
        }
    }
}
