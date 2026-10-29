@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.squaddle.app.ui.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import org.squaddle.app.BuildConfig
import org.squaddle.core.encoding.Base32

@Composable
public fun RoomsListScreen(
    viewModel: RoomsListViewModel,
    onOpenRoom: (ByteArray) -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenDeveloperSettings: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rooms") },
                actions = {
                    IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("settingsButton")) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    if (BuildConfig.DEBUG) {
                        IconButton(onClick = onOpenDeveloperSettings, modifier = Modifier.testTag("developerSettingsButton")) {
                            Icon(Icons.Filled.BugReport, contentDescription = "Developer settings")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Create room")
            }
        },
    ) { padding ->
        if (state.rooms.isEmpty() && !state.isRefreshing) {
            EmptyRoomsState(modifier = Modifier.padding(padding))
        } else {
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                if (state.isRefreshing) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                    }
                }
                LazyColumn {
                    items(state.rooms, key = { Base32.encode(it.roomId) }) { room ->
                        RoomRow(room, onClick = { onOpenRoom(room.roomId) })
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateRoomDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                viewModel.createRoom(name)
                showCreateDialog = false
            },
        )
    }

    state.createRoomError?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissCreateRoomError,
            confirmButton = { TextButton(onClick = viewModel::dismissCreateRoomError) { Text("OK") } },
            title = { Text("Couldn't create room") },
            text = { Text(error) },
        )
    }
}

@Composable
private fun EmptyRoomsState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No rooms yet", style = MaterialTheme.typography.titleLarge)
            Text(
                "Create a room, or open an invite link someone shared with you.",
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun RoomRow(room: RoomSummary, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("room_${Base32.encode(room.roomId)}"),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(room.displayName, style = MaterialTheme.typography.titleMedium)
                Text(room.relayHost, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (room.hasUnread) {
                Spacer(modifier = Modifier.size(8.dp))
                Box(
                    modifier = Modifier.size(10.dp)
                        .background(color = MaterialTheme.colorScheme.primary, shape = CircleShape)
                        .testTag("unread_${Base32.encode(room.roomId)}"),
                )
            }
        }
    }
}

@Composable
private fun CreateRoomDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create a room") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Room name") },
                modifier = Modifier.fillMaxWidth().testTag("createRoomNameField"),
            )
        },
        confirmButton = {
            Button(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
