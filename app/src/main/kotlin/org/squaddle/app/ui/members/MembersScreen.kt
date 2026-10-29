@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.squaddle.app.ui.members

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.squaddle.app.R
import org.squaddle.core.encoding.Base32
import org.squaddle.core.membership.Role

@Composable
public fun MembersScreen(viewModel: MembersViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.members_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        if (state.isLoading) {
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val (pending, active) = state.members.partition { it.role == Role.PENDING }
            LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                if (pending.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.members_pending_approval)) }
                    items(pending, key = { Base32.encode(it.user) }) { member ->
                        PendingRow(member, onApprove = { viewModel.approve(member.user) }, onReject = { viewModel.reject(member.user) })
                    }
                }
                item { SectionHeader(stringResource(R.string.members_title)) }
                items(active, key = { Base32.encode(it.user) }) { member ->
                    MemberRow(
                        member,
                        onKick = { viewModel.kick(member.user) },
                        onPromote = { viewModel.promote(member.user) },
                        onDemote = { viewModel.demote(member.user) },
                        onTransferOwnership = { viewModel.transferOwnership(member.user) },
                    )
                }
            }
        }
    }

    state.actionError?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissActionError,
            confirmButton = { TextButton(onClick = viewModel::dismissActionError) { Text(stringResource(R.string.action_ok)) } },
            title = { Text(stringResource(R.string.members_action_failed_title)) },
            text = { Text(error) },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun PendingRow(member: MemberSummary, onApprove: () -> Unit, onReject: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("member_${Base32.encode(member.user)}")) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(displayLabel(member), modifier = Modifier.weight(1f))
            if (member.canReject) TextButton(onClick = onReject) { Text(stringResource(R.string.members_reject)) }
            if (member.canApprove) TextButton(onClick = onApprove) { Text(stringResource(R.string.members_approve)) }
        }
    }
}

@Composable
private fun MemberRow(
    member: MemberSummary,
    onKick: () -> Unit,
    onPromote: () -> Unit,
    onDemote: () -> Unit,
    onTransferOwnership: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val hasAnyAction = member.canKick || member.canPromote || member.canDemote || member.canTransferOwnership

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("member_${Base32.encode(member.user)}")) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(displayLabel(member))
                Text(roleLabel(member.role), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (hasAnyAction) {
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.testTag("memberMenu_${Base32.encode(member.user)}")) {
                        Text("⋮") // vertical ellipsis, no extra icon dependency needed
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        if (member.canPromote) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.members_promote)) }, onClick = { showMenu = false; onPromote() })
                        }
                        if (member.canDemote) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.members_demote)) }, onClick = { showMenu = false; onDemote() })
                        }
                        if (member.canTransferOwnership) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.members_transfer_ownership)) },
                                onClick = { showMenu = false; onTransferOwnership() },
                            )
                        }
                        if (member.canKick) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.members_remove)) }, onClick = { showMenu = false; onKick() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun roleLabel(role: Role): String = when (role) {
    Role.OWNER -> stringResource(R.string.role_owner)
    Role.ADMIN -> stringResource(R.string.role_admin)
    Role.MEMBER -> stringResource(R.string.role_member)
    Role.PENDING -> stringResource(R.string.role_pending)
}

private fun shortKey(user: ByteArray): String = Base32.encode(user).take(12)

/** The name this member chose via [org.squaddle.app.data.SyncGateway.setProfile], or a shortened
 * key for a member who never has (an older client's own join, say) - never the raw/truncated key
 * when a real name is available, which is what every member row showed before this fix. */
private fun displayLabel(member: MemberSummary): String = member.displayName?.takeIf { it.isNotBlank() } ?: shortKey(member.user)
