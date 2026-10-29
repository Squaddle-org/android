package org.squaddle.app.ui.members

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.squaddle.app.data.SyncGateway
import org.squaddle.app.data.friendlyMessage
import org.squaddle.core.membership.Role
import org.squaddle.core.sync.PushOutcome

public data class MemberSummary(
    public val user: ByteArray,
    public val role: Role,
    public val canApprove: Boolean,
    public val canReject: Boolean,
    public val canKick: Boolean,
    public val canPromote: Boolean,
    public val canDemote: Boolean,
    public val canTransferOwnership: Boolean,
)

public data class MembersUiState(
    public val isLoading: Boolean = true,
    public val members: List<MemberSummary> = emptyList(),
    public val actionError: String? = null,
)

/**
 * Roles + pending approvals via [org.squaddle.core.membership.MembershipState.members] (incl.
 * pending). Every action gate mirrors §3.3's literal authorization column, client-side (the
 * relay/`MembershipStateImpl` still enforce for real - this only decides which buttons to show):
 * `remove` - owner removes anyone but self, admin removes members only; `setRole`/`transferOwner`
 * - owner only (transfer's target must already be admin); `approve`/`reject` - owner/admin,
 * target pending. [kick] additionally calls `autoRotate` right after a successful remove, same as
 * [org.squaddle.app.data.SyncGateway.kick]'s own doc comment explains.
 */
public class MembersViewModel(
    private val room: ByteArray,
    private val myUserKey: ByteArray,
    private val gateway: SyncGateway,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MembersUiState())
    public val uiState: StateFlow<MembersUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    public fun refresh() {
        viewModelScope.launch {
            gateway.pull(room)
            val history = gateway.membershipHistory(room)
            val current = history.current
            val myRole = current.roleOf(myUserKey)
            val isOwner = myRole == Role.OWNER
            val isAdminOrOwner = myRole == Role.OWNER || myRole == Role.ADMIN

            val members = current.members().map { member ->
                val isSelf = member.user.contentEquals(myUserKey)
                MemberSummary(
                    user = member.user,
                    role = member.role,
                    canApprove = isAdminOrOwner && member.role == Role.PENDING,
                    canReject = isAdminOrOwner && member.role == Role.PENDING,
                    // Pending users get dedicated approve/reject actions - offering a redundant
                    // "remove" for them would also needlessly flip rotationPending (any remove
                    // does, per §3.4) for someone who never held an epoch key to begin with.
                    canKick = !isSelf && member.role != Role.PENDING && (
                        (isOwner && member.role != Role.OWNER) || (myRole == Role.ADMIN && member.role == Role.MEMBER)
                    ),
                    canPromote = isOwner && !isSelf && member.role == Role.MEMBER,
                    canDemote = isOwner && !isSelf && member.role == Role.ADMIN,
                    canTransferOwnership = isOwner && !isSelf && member.role == Role.ADMIN,
                )
            }.sortedBy { roleSortOrder(it.role) }

            _uiState.value = MembersUiState(isLoading = false, members = members)
        }
    }

    private fun roleSortOrder(role: Role): Int = when (role) {
        Role.OWNER -> 0
        Role.ADMIN -> 1
        Role.MEMBER -> 2
        Role.PENDING -> 3
    }

    private fun runAction(action: suspend () -> PushOutcome) {
        viewModelScope.launch {
            val outcome = action()
            if (outcome is PushOutcome.Error) {
                _uiState.value = _uiState.value.copy(actionError = outcome.friendlyMessage())
            } else {
                refresh()
            }
        }
    }

    public fun approve(target: ByteArray): Unit = runAction { gateway.approve(room, target) }
    public fun reject(target: ByteArray): Unit = runAction { gateway.reject(room, target) }
    public fun kick(target: ByteArray): Unit = runAction { gateway.kick(room, target) }
    public fun promote(target: ByteArray): Unit = runAction { gateway.setRole(room, target, "admin") }
    public fun demote(target: ByteArray): Unit = runAction { gateway.setRole(room, target, "member") }
    public fun transferOwnership(target: ByteArray): Unit = runAction { gateway.transferOwner(room, target) }

    public fun dismissActionError() {
        _uiState.value = _uiState.value.copy(actionError = null)
    }
}
