package org.squaddle.app.ui.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.squaddle.app.data.SyncGateway
import org.squaddle.app.store.AppLocalStore
import org.squaddle.app.store.Known_rooms

public data class RoomSummary(
    public val roomId: ByteArray,
    public val displayName: String,
    public val relayHost: String,
    public val lastActivityAt: Long,
    public val hasUnread: Boolean,
)

public data class RoomsListUiState(
    public val rooms: List<RoomSummary> = emptyList(),
    public val isRefreshing: Boolean = false,
    public val createRoomError: String? = null,
)

/**
 * Reads the local known-rooms registry (room names/registry aren't a protocol concept - see
 * `AppLocalDb.sq`'s own doc comment), re-pulling every known room on screen entry and
 * pull-to-refresh. "Unread" compares each room's locally-synced cursor
 * ([SyncGateway.syncedCursor]) against what the user has last actually opened that room to look
 * at ([AppLocalStore.readCursorFor]) - both purely local concepts, no server involvement.
 */
public class RoomsListViewModel(
    private val gateway: SyncGateway,
    private val appLocalStore: AppLocalStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RoomsListUiState())
    public val uiState: StateFlow<RoomsListUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    public fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
            val known = appLocalStore.allKnownRooms()
            val summaries = known.map { room ->
                gateway.pull(room.room_id)
                room.toSummary()
            }
            _uiState.value = RoomsListUiState(rooms = summaries, isRefreshing = false)
        }
    }

    private suspend fun Known_rooms.toSummary(): RoomSummary {
        val synced = gateway.syncedCursor(room_id)
        val read = appLocalStore.readCursorFor(room_id)
        val hasUnread = synced != null && (
            read == null || synced.first > read.last_read_log_index || synced.second > read.last_read_ops_cursor
        )
        return RoomSummary(room_id, display_name, relay_host, last_activity_at, hasUnread)
    }

    public fun createRoom(roomName: String) {
        if (roomName.isBlank()) return
        viewModelScope.launch {
            val outcome = gateway.createRoom(roomName)
            if (outcome.isFailure) {
                _uiState.value = _uiState.value.copy(createRoomError = outcome.exceptionOrNull()?.message ?: "failed to create room")
            } else {
                refresh()
            }
        }
    }

    public fun dismissCreateRoomError() {
        _uiState.value = _uiState.value.copy(createRoomError = null)
    }
}
