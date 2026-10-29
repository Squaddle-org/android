package org.squaddle.app.ui.join

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.squaddle.app.R

/** [onJoined] fires once [JoinUiState.Joined] is reached; [onCancelled] on [JoinViewModel.cancel]
 * or an explicit "Cancel" tap - both navigate away from this screen, the caller decides where to. */
@Composable
public fun JoinScreen(viewModel: JoinViewModel, onJoined: () -> Unit, onCancelled: () -> Unit) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state) {
        if (state is JoinUiState.Joined) onJoined()
    }

    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        when (val s = state) {
            is JoinUiState.Idle -> Unit
            is JoinUiState.Parsing, is JoinUiState.Joining -> CircularProgressIndicator()
            is JoinUiState.Confirm -> ConfirmContent(
                roomName = s.roomName,
                onConfirm = viewModel::confirm,
                onCancel = onCancelled,
            )
            is JoinUiState.Error -> ErrorContent(message = s.message, onDismiss = onCancelled)
            is JoinUiState.Joined -> Unit // onJoined fires via LaunchedEffect above
        }
    }
}

@Composable
private fun ConfirmContent(roomName: String?, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.join_confirm_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            roomName ?: stringResource(R.string.default_room_name),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth().testTag("confirmJoinButton")) {
            Text(stringResource(R.string.action_join))
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

@Composable
private fun ErrorContent(message: String, onDismiss: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.join_error_title), style = MaterialTheme.typography.headlineSmall)
        Text(message, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_ok)) }
    }
}

/** The deferred-deep-link fallback (ARCHITECTURE.md): offered once per cold start when the
 * clipboard holds something that looks like a Squaddle invite link - never polled continuously,
 * only checked on resume (android/CLAUDE.md: never log the link text itself, so this banner shows
 * no part of it). */
@Composable
public fun ClipboardInviteBanner(onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth().padding(16.dp).testTag("clipboardInviteBanner")) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.join_clipboard_banner), modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
                TextButton(onClick = onOpen) { Text(stringResource(R.string.action_open)) }
            }
        }
    }
}
