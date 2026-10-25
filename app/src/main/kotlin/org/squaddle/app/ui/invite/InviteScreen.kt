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
import androidx.compose.ui.unit.dp
import org.squaddle.app.data.InviteLink

private val EXPIRY_OPTIONS: List<Pair<String, Int?>> = listOf(
    "Never" to null,
    "1 day" to 1,
    "7 days" to 7,
    "30 days" to 30,
)

@Composable
public fun InviteScreen(viewModel: InviteViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Invite") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
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
            confirmButton = { TextButton(onClick = viewModel::dismissError) { Text("OK") } },
            title = { Text("Couldn't create invite") },
            text = { Text(error) },
        )
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
                Text("Require approval", style = MaterialTheme.typography.titleMedium)
                Text("New joiners wait for an admin to approve them.", style = MaterialTheme.typography.bodyMedium)
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
            label = { Text("Max uses (optional)") },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag("maxUsesField"),
        )

        if (state.isCreating) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
        } else {
            Button(onClick = onCreate, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                Text("Create invite")
            }
        }
    }
}

@Composable
private fun ExpiryPicker(selectedDays: Int?, onSelect: (Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = EXPIRY_OPTIONS.firstOrNull { it.second == selectedDays }?.first ?: "Never"

    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text("Expires", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.Box {
            TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().testTag("expiryField")) {
                Text(label, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
            }
            androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for ((optionLabel, days) in EXPIRY_OPTIONS) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(optionLabel) },
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
        Text("Invite ready", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Share this link - whoever opens it can join the room.",
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        Button(onClick = onShare, modifier = Modifier.fillMaxWidth().testTag("shareInviteButton")) {
            Text("Share invite link")
        }
        TextButton(onClick = onMakeAnother, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("Create another invite")
        }
    }
}

private fun shareInviteLink(context: android.content.Context, link: InviteLink) {
    val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, link.toUriString())
    }
    context.startActivity(android.content.Intent.createChooser(sendIntent, "Share invite link"))
}
