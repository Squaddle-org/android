package org.squaddle.app.ui.room

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.squaddle.app.data.SyncGateway
import org.squaddle.app.push.RoomMuteStore
import org.squaddle.core.membership.Role

private fun ByteArray.toKey(): String = joinToString("") { "%02x".format(it) }
private const val IDEA_ID_HEX_LENGTH = 32 // 16 bytes

public data class IdeaSummary(
    public val id: ByteArray,
    public val title: String,
    public val desc: String?,
    public val score: Int,
    public val myVote: Int,
    public val canEditOrDelete: Boolean,
    public val createdAtLamport: Long,
)

public data class CommentSummary(
    public val id: ByteArray,
    public val text: String,
    public val canDelete: Boolean,
)

public data class RoomUiState(
    public val isLoading: Boolean = true,
    public val ideas: List<IdeaSummary> = emptyList(),
    public val selectedIdeaId: ByteArray? = null,
    public val comments: List<CommentSummary> = emptyList(),
    public val isMuted: Boolean = false,
)

/**
 * Ideas sorted by score (sum of [org.squaddle.core.ops.Vote] values for that idea) then recency
 * ([org.squaddle.app.data.RoomMaterialization.ideaCreatedAtLamport] - [org.squaddle.core.ops.Idea]
 * itself carries no creation timestamp). Vote toggles via re-sending the existing value as `0` to
 * clear ("author's own vote only" per §4.2 - there's no separate "unvote" op type). Create/edit/
 * delete are client-side gated the same way [org.squaddle.core.ops.RoomState] gates them
 * server-side (author-or-admin) - the UI simply doesn't offer actions it knows will be rejected.
 */
public class RoomViewModel(
    private val room: ByteArray,
    private val myUserKey: ByteArray,
    private val gateway: SyncGateway,
    private val muteStore: RoomMuteStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RoomUiState(isMuted = muteStore.isMuted(room)))
    public val uiState: StateFlow<RoomUiState> = _uiState.asStateFlow()

    private var liveUpdatesJob: Job? = null

    init {
        refresh()
        liveUpdatesJob = viewModelScope.launch {
            gateway.connectLiveUpdates(room) { refresh() }
        }
    }

    override fun onCleared() {
        liveUpdatesJob?.cancel()
    }

    public fun refresh() {
        viewModelScope.launch {
            gateway.pull(room)
            val materialization = gateway.materialize(room)
            val myRole = gateway.myRole(room)
            val isAdminOrOwner = myRole == Role.ADMIN || myRole == Role.OWNER
            val myKeyHex = myUserKey.toKey()

            val ideas = materialization.state.ideas.values
                .filterNot { it.deleted }
                .map { idea ->
                    val ideaIdHex = idea.id.toKey()
                    val score = materialization.state.votes
                        .filterKeys { it.startsWith(ideaIdHex) }
                        .values.sumOf { it.value }
                    val myVote = materialization.state.votes[ideaIdHex + myKeyHex]?.value ?: 0
                    IdeaSummary(
                        id = idea.id,
                        title = idea.title,
                        desc = idea.desc,
                        score = score,
                        myVote = myVote,
                        canEditOrDelete = isAdminOrOwner || idea.createdBy.contentEquals(myUserKey),
                        createdAtLamport = materialization.ideaCreatedAtLamport[ideaIdHex] ?: 0L,
                    )
                }
                .sortedWith(compareByDescending<IdeaSummary> { it.score }.thenByDescending { it.createdAtLamport })

            val selected = _uiState.value.selectedIdeaId
            val comments = if (selected != null) commentsFor(selected, materialization, isAdminOrOwner) else emptyList()
            _uiState.value = _uiState.value.copy(isLoading = false, ideas = ideas, comments = comments)
        }
    }

    private fun commentsFor(
        ideaId: ByteArray,
        materialization: org.squaddle.app.data.RoomMaterialization,
        isAdminOrOwner: Boolean,
    ): List<CommentSummary> =
        materialization.state.comments.values
            .filter { it.ideaId.contentEquals(ideaId) && !it.deleted }
            .map { CommentSummary(it.id, it.text, canDelete = isAdminOrOwner || it.author.contentEquals(myUserKey)) }

    public fun selectIdea(ideaId: ByteArray?) {
        _uiState.value = _uiState.value.copy(selectedIdeaId = ideaId)
        refresh()
    }

    public fun toggleVote(ideaId: ByteArray, direction: Int) {
        val current = _uiState.value.ideas.firstOrNull { it.id.contentEquals(ideaId) }?.myVote ?: 0
        val newValue = if (current == direction) 0 else direction
        viewModelScope.launch {
            if (gateway.vote(room, ideaId, newValue)) refresh()
        }
    }

    public fun createIdea(title: String, desc: String?) {
        if (title.isBlank()) return
        viewModelScope.launch {
            if (gateway.createIdea(room, title, desc?.ifBlank { null })) refresh()
        }
    }

    public fun editIdea(ideaId: ByteArray, title: String, desc: String?) {
        viewModelScope.launch {
            gateway.editIdea(room, ideaId, "title", title)
            gateway.editIdea(room, ideaId, "desc", desc)
            refresh()
        }
    }

    public fun deleteIdea(ideaId: ByteArray) {
        viewModelScope.launch {
            if (gateway.deleteIdea(room, ideaId)) {
                if (_uiState.value.selectedIdeaId?.contentEquals(ideaId) == true) {
                    _uiState.value = _uiState.value.copy(selectedIdeaId = null)
                }
                refresh()
            }
        }
    }

    public fun addComment(ideaId: ByteArray, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            if (gateway.addComment(room, ideaId, text)) refresh()
        }
    }

    public fun deleteComment(commentId: ByteArray) {
        viewModelScope.launch {
            if (gateway.deleteComment(room, commentId)) refresh()
        }
    }

    /** Local-only preference (android/CLAUDE.md M4b item 5) - checked by
     * [org.squaddle.app.push.RoomSyncNotifier] before posting any notification for this room.
     * No network call, so no [viewModelScope] launch is needed. */
    public fun toggleMute() {
        val newValue = !_uiState.value.isMuted
        muteStore.setMuted(room, newValue)
        _uiState.value = _uiState.value.copy(isMuted = newValue)
    }
}
