package org.squaddle.app.ui.join

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.squaddle.app.data.InviteLink
import org.squaddle.app.data.SyncGateway
import org.squaddle.core.sync.PushOutcome

public sealed interface JoinUiState {
    public data object Idle : JoinUiState
    public data object Parsing : JoinUiState
    public data class Confirm(public val link: InviteLink, public val roomName: String?) : JoinUiState
    public data object Joining : JoinUiState
    public data object Joined : JoinUiState
    public data class Error(public val message: String) : JoinUiState
}

/**
 * Parses a `https://squaddle.org/j/...` link (App Link intent data, or a clipboard-fallback
 * guess), fetches+decrypts the invite envelope read-only to show the room name before the user
 * commits ([SyncGateway.peekInviteRoomName] - no join yet), then [confirm] actually joins via
 * [SyncGateway.joinViaLink]. Never logs the link text or anything derived from it (CLAUDE.md -
 * the fragment is the invite secret).
 */
public class JoinViewModel(private val gateway: SyncGateway) : ViewModel() {

    private val _uiState = MutableStateFlow<JoinUiState>(JoinUiState.Idle)
    public val uiState: StateFlow<JoinUiState> = _uiState.asStateFlow()

    public fun handleLinkText(text: String) {
        val link = gateway.parseInviteLink(text)
        if (link == null) {
            _uiState.value = JoinUiState.Error("That doesn't look like a Squaddle invite link.")
            return
        }
        openWithLink(link)
    }

    public fun openWithLink(link: InviteLink) {
        _uiState.value = JoinUiState.Parsing
        viewModelScope.launch {
            val roomName = gateway.peekInviteRoomName(link)
            _uiState.value = JoinUiState.Confirm(link, roomName)
        }
    }

    public fun confirm() {
        val state = _uiState.value as? JoinUiState.Confirm ?: return
        _uiState.value = JoinUiState.Joining
        viewModelScope.launch {
            val outcome = gateway.joinViaLink(state.link)
            _uiState.value = if (outcome == PushOutcome.Ok) {
                JoinUiState.Joined
            } else {
                JoinUiState.Error((outcome as? PushOutcome.Error)?.message ?: "couldn't join this room")
            }
        }
    }

    public fun cancel() {
        _uiState.value = JoinUiState.Idle
    }
}
