@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.squaddle.app.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.squaddle.app.push.SyncAllRoomsWorker

/** Debug-build-only (gated at the call site, [org.squaddle.app.ui.rooms.RoomsListScreen]'s gear
 * icon) override for the relay host every client-initiated action talks to. */
@Composable
public fun DeveloperSettingsScreen(viewModel: DeveloperSettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Developer settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Text("Relay host", style = MaterialTheme.typography.titleMedium)
            Text(
                "Overrides the default (${state.defaultRelayHost}). HTTPS only - the app never connects over plain HTTP.",
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            OutlinedTextField(
                value = state.relayHostInput,
                onValueChange = viewModel::updateInput,
                label = { Text("Relay host") },
                modifier = Modifier.fillMaxWidth().testTag("relayHostOverrideField"),
            )
            state.savedMessage?.let { message ->
                Text(message, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary)
            }
            Button(
                onClick = viewModel::save,
                enabled = state.relayHostInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag("saveRelayHostButton"),
            ) { Text("Save") }
            TextButton(onClick = viewModel::resetToDefault, modifier = Modifier.fillMaxWidth()) {
                Text("Reset to default")
            }

            Text("Push", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
            Text(
                "Runs SyncAllRoomsWorker immediately - the exact same job a real push message or " +
                    "the 6h periodic fallback enqueues (SquaddlePushService.onMessage), without " +
                    "needing a real UnifiedPush distributor or waiting for the real schedule. " +
                    "Manual-testing only (MANUAL_TESTING.md).",
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            Button(
                onClick = {
                    val request = OneTimeWorkRequestBuilder<SyncAllRoomsWorker>()
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .build()
                    WorkManager.getInstance(context).enqueue(request)
                },
                modifier = Modifier.fillMaxWidth().testTag("triggerSyncNowButton"),
            ) { Text("Trigger sync now") }
        }
    }
}
