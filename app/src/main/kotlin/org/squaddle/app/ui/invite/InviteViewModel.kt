package org.squaddle.app.ui.invite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.squaddle.app.data.InviteLink
import org.squaddle.app.data.RelayErrorException
import org.squaddle.app.data.SyncGateway
import org.squaddle.app.data.friendlyErrorMessage
import org.squaddle.core.sync.nowUnixSeconds

public data class InviteUiState(
    public val requiresApproval: Boolean = false,
    public val expiresInDays: Int? = null, // null = never expires
    public val maxUses: Int? = null, // null = unlimited
    public val isCreating: Boolean = false,
    public val createdLink: InviteLink? = null,
    public val error: String? = null,
)

/** Expiry/max-uses/approval-toggle form state -> [SyncGateway.createInvite] (already does the
 * right thing per §3.5, incl. minting+uploading the envelope for open invites) -> the resulting
 * [InviteLink] is handed to the screen for the Android share sheet (a platform action, not
 * something a ViewModel should hold a `Context`/`Intent` to perform itself). */
public class InviteViewModel(
    private val room: ByteArray,
    private val gateway: SyncGateway,
) : ViewModel() {

    private val _uiState = MutableStateFlow(InviteUiState())
    public val uiState: StateFlow<InviteUiState> = _uiState.asStateFlow()

    public fun setRequiresApproval(value: Boolean) {
        _uiState.value = _uiState.value.copy(requiresApproval = value)
    }

    public fun setExpiresInDays(days: Int?) {
        _uiState.value = _uiState.value.copy(expiresInDays = days)
    }

    public fun setMaxUses(uses: Int?) {
        _uiState.value = _uiState.value.copy(maxUses = uses)
    }

    public fun createInvite() {
        val state = _uiState.value
        _uiState.value = state.copy(isCreating = true, error = null)
        viewModelScope.launch {
            val expiresAt = state.expiresInDays?.let { days -> nowUnixSeconds() + days * SECONDS_PER_DAY }
            val result = gateway.createInvite(room, state.requiresApproval, expiresAt, state.maxUses)
            _uiState.value = result.fold(
                onSuccess = { link -> _uiState.value.copy(isCreating = false, createdLink = link) },
                onFailure = { e ->
                    val message = (e as? RelayErrorException)?.let { friendlyErrorMessage(it.code, it.message ?: "") } ?: e.message
                    _uiState.value.copy(isCreating = false, error = message ?: "failed to create invite")
                },
            )
        }
    }

    public fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    public fun makeAnotherInvite() {
        _uiState.value = _uiState.value.copy(createdLink = null)
    }

    private companion object {
        const val SECONDS_PER_DAY = 86_400L
    }
}
