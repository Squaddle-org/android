package org.squaddle.app.ui.link

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.squaddle.app.backup.toBackupRoomRef
import org.squaddle.app.store.AppLocalStore
import org.squaddle.core.crypto.Crypto
import org.squaddle.core.identity.UserIdentity
import org.squaddle.core.link.DeviceLink
import org.squaddle.core.link.LinkQr

public sealed interface DeviceLinkUiState {
    public data object Scanning : DeviceLinkUiState
    public data object Sending : DeviceLinkUiState
    public data class Sent(public val confirmationCode: String) : DeviceLinkUiState
    public data class Failed(public val message: String) : DeviceLinkUiState
}

/**
 * Owns the entire master (E) role flow for `DeviceLinkScanScreen` (PROTOCOL.md §2.3) end to end -
 * scan result in, confirmation code or error out - rather than splitting state across this route
 * and `SettingsScreen`'s own `SettingsViewModel` (which Navigation Compose scopes to its own nav
 * entry anyway, so sharing one instance across routes isn't the natural option here).
 */
public class DeviceLinkViewModel(
    private val crypto: Crypto,
    private val userIdentity: UserIdentity,
    private val appLocalStore: AppLocalStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow<DeviceLinkUiState>(DeviceLinkUiState.Scanning)
    public val uiState: StateFlow<DeviceLinkUiState> = _uiState.asStateFlow()

    /** [qrUri] is whatever `DeviceLinkScanScreen`'s camera decoded. Seals this device's own
     * entropy + known rooms to the scanned QR's ephemeral key and posts it to the relay's
     * one-shot mailbox; on success, computes the 6-digit confirmation code for the user to
     * compare against the new device's own display - no automated remedy exists yet for a
     * mismatch (device revocation isn't enforced server-side today, intentionally out of scope
     * for this feature), so this is a user-driven check, not a gate. */
    public fun onQrDecoded(qrUri: String) {
        val qr = LinkQr.parse(qrUri)
        if (qr == null) {
            _uiState.value = DeviceLinkUiState.Failed("That QR code isn't a valid device-linking code.")
            return
        }
        _uiState.value = DeviceLinkUiState.Sending
        viewModelScope.launch {
            val rooms = appLocalStore.allKnownRooms().map { it.toBackupRoomRef() }
            val sealed = DeviceLink.sealForLink(crypto, qr, userIdentity.entropy, rooms)
            val sent = try {
                DeviceLink.postMailbox(qr.relayHost, qr.code, sealed)
            } catch (e: Exception) {
                false
            }
            _uiState.value = if (sent) {
                DeviceLinkUiState.Sent(DeviceLink.confirmationCode(crypto, qr.ephemeralPublicKey, userIdentity.userKey))
            } else {
                DeviceLinkUiState.Failed("Couldn't reach the relay - check your connection and try again.")
            }
        }
    }

    public fun retry() {
        _uiState.value = DeviceLinkUiState.Scanning
    }
}
