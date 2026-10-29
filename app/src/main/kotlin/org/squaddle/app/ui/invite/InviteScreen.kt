@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.squaddle.app.ui.invite

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.squaddle.app.R
import org.squaddle.app.data.InviteLink

/** String resource id (not the resolved text - that needs a `@Composable` context, see
 * [ExpiryPicker]) paired with the day count that option sets; `null` = never expires. */
private val EXPIRY_OPTIONS: List<Pair<Int, Int?>> = listOf(
    R.string.invite_expiry_never to null,
    R.string.invite_expiry_1_day to 1,
    R.string.invite_expiry_7_days to 7,
    R.string.invite_expiry_30_days to 30,
)

@Composable
public fun InviteScreen(viewModel: InviteViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showDiscardConfirm by remember { mutableStateOf(false) }

    // "invite options" form asks "discard changes?" - dirty means any option moved off its
    // default, and only before an invite's actually been created (CreatedInviteContent below is
    // a result view, not a form - there's nothing left to discard once link != null). Covers
    // both the TopAppBar's own back icon and system back (requestBack is what both call).
    val isDirty = state.createdLink == null &&
        (state.requiresApproval || state.expiresInDays != null || state.maxUses != null)
    val requestBack = { if (isDirty) showDiscardConfirm = true else onBack() }
    androidx.activity.compose.BackHandler(enabled = isDirty, onBack = { showDiscardConfirm = true })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.invite_title)) },
                navigationIcon = {
                    IconButton(onClick = requestBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        val link = state.createdLink
        if (link != null) {
            CreatedInviteContent(
                link = link,
                modifier = Modifier.padding(padding),
                onShare = { shareInviteLink(context, link) },
                onMakeAnother = viewModel::makeAnotherInvite,
            )
        } else {
            InviteFormContent(
                state = state,
                modifier = Modifier.padding(padding),
                onRequiresApprovalChange = viewModel::setRequiresApproval,
                onExpiresInDaysChange = viewModel::setExpiresInDays,
                onMaxUsesChange = viewModel::setMaxUses,
                onCreate = viewModel::createInvite,
            )
        }
    }

    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            confirmButton = { TextButton(onClick = viewModel::dismissError) { Text(stringResource(R.string.action_ok)) } },
            title = { Text(stringResource(R.string.invite_error_title)) },
            text = { Text(error) },
        )
    }

    if (showDiscardConfirm) {
        org.squaddle.app.ui.room.DiscardChangesDialog(onKeepEditing = { showDiscardConfirm = false }, onDiscard = onBack)
    }
}

@Composable
private fun InviteFormContent(
    state: InviteUiState,
    modifier: Modifier = Modifier,
    onRequiresApprovalChange: (Boolean) -> Unit,
    onExpiresInDaysChange: (Int?) -> Unit,
    onMaxUsesChange: (Int?) -> Unit,
    onCreate: () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.invite_require_approval), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.invite_require_approval_explainer), style = MaterialTheme.typography.bodyMedium)
            }
            Switch(
                checked = state.requiresApproval,
                onCheckedChange = onRequiresApprovalChange,
                modifier = Modifier.testTag("requiresApprovalSwitch"),
            )
        }

        ExpiryPicker(selectedDays = state.expiresInDays, onSelect = onExpiresInDaysChange)

        var maxUsesText by remember(state.maxUses) { mutableStateOf(state.maxUses?.toString() ?: "") }
        OutlinedTextField(
            value = maxUsesText,
            onValueChange = { text ->
                maxUsesText = text
                onMaxUsesChange(text.toIntOrNull())
            },
            label = { Text(stringResource(R.string.invite_max_uses_label)) },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag("maxUsesField"),
        )

        if (state.isCreating) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
        } else {
            Button(onClick = onCreate, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                Text(stringResource(R.string.invite_create))
            }
        }
    }
}

@Composable
private fun ExpiryPicker(selectedDays: Int?, onSelect: (Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val labelResId = EXPIRY_OPTIONS.firstOrNull { it.second == selectedDays }?.first ?: R.string.invite_expiry_never

    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text(stringResource(R.string.invite_expires), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.Box {
            TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().testTag("expiryField")) {
                Text(stringResource(labelResId), modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
            }
            androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for ((optionLabelResId, days) in EXPIRY_OPTIONS) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(optionLabelResId)) },
                        onClick = { onSelect(days); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun CreatedInviteContent(link: InviteLink, modifier: Modifier = Modifier, onShare: () -> Unit, onMakeAnother: () -> Unit) {
    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Text(stringResource(R.string.invite_ready_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.invite_ready_explainer),
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        Button(onClick = onShare, modifier = Modifier.fillMaxWidth().testTag("shareInviteButton")) {
            Text(stringResource(R.string.invite_share))
        }
        TextButton(onClick = onMakeAnother, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(stringResource(R.string.invite_create_another))
        }
    }
}

private fun shareInviteLink(context: android.content.Context, link: InviteLink) {
    val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, link.toUriString())
    }
    context.startActivity(android.content.Intent.createChooser(sendIntent, context.getString(R.string.invite_share)))
}
