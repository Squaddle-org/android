@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.squaddle.app.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch
import org.squaddle.app.R
import org.squaddle.app.backup.AutoBackupScheduler

private const val BACKUP_MIME_TYPE = "application/octet-stream"

@Composable
public fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pendingCreateBackupUri by remember { mutableStateOf<String?>(null) }
    var pendingRestoreUri by remember { mutableStateOf<String?>(null) }
    var pendingAutoBackupUri by remember { mutableStateOf<String?>(null) }

    val createDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE)) { uri ->
        if (uri != null) pendingCreateBackupUri = uri.toString()
    }
    val openDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingRestoreUri = uri.toString()
    }
    val autoBackupDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE)) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            pendingAutoBackupUri = uri.toString()
        }
    }

    // Reacts to the ViewModel's own state rather than being called directly from the toggle
    // handler - same split RoomSyncNotifier/PeriodicSyncScheduler use, keeps SettingsViewModel
    // itself free of any WorkManager/Context dependency.
    LaunchedEffect(state.autoBackupEnabled, state.autoBackupIntervalHours) {
        if (state.autoBackupEnabled) {
            AutoBackupScheduler.enable(context, state.autoBackupIntervalHours)
        } else {
            AutoBackupScheduler.disable(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            item {
                SeedSection(
                    revealed = state.seedRevealed,
                    mnemonic = state.mnemonic,
                    onRevealRequested = {
                        val activity = context as? FragmentActivity
                        if (activity != null) {
                            scope.launch { if (authenticateForSeedReveal(activity)) viewModel.onSeedAuthenticated() }
                        }
                    },
                    onHide = viewModel::hideSeed,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp)) }
            item {
                BackupSection(
                    inProgress = state.backupInProgress,
                    resultMessage = state.backupResultMessage,
                    onCreateBackup = { createDocumentLauncher.launch("squaddle-backup.squaddle-backup") },
                    onDismissResult = viewModel::clearBackupResult,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp)) }
            item {
                RestoreSection(
                    inProgress = state.restoreInProgress,
                    resultMessage = state.restoreResultMessage,
                    onRestoreFromFile = { openDocumentLauncher.launch(arrayOf(BACKUP_MIME_TYPE)) },
                    onDismissResult = viewModel::clearRestoreResult,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp)) }
            item {
                AutoBackupSection(
                    enabled = state.autoBackupEnabled,
                    intervalHours = state.autoBackupIntervalHours,
                    onEnable = { autoBackupDocumentLauncher.launch("squaddle-auto-backup.squaddle-backup") },
                    onDisable = { viewModel.setAutoBackup(enabled = false, uriString = null, passphrase = null, intervalHours = state.autoBackupIntervalHours) },
                    onIntervalChanged = viewModel::updateAutoBackupInterval,
                )
            }
        }
    }

    pendingCreateBackupUri?.let { uriString ->
        PassphraseDialog(
            title = stringResource(R.string.settings_protect_backup_title),
            confirmLabel = stringResource(R.string.action_save),
            onDismiss = { pendingCreateBackupUri = null },
            onConfirm = { passphrase ->
                viewModel.createBackup(uriString, passphrase)
                pendingCreateBackupUri = null
            },
        )
    }

    pendingRestoreUri?.let { uriString ->
        PassphraseDialog(
            title = stringResource(R.string.settings_enter_backup_passphrase_title),
            confirmLabel = stringResource(R.string.settings_restore),
            onDismiss = { pendingRestoreUri = null },
            onConfirm = { passphrase ->
                viewModel.restoreBackup(uriString, passphrase)
                pendingRestoreUri = null
            },
        )
    }

    pendingAutoBackupUri?.let { uriString ->
        PassphraseDialog(
            title = stringResource(R.string.settings_choose_auto_backup_passphrase_title),
            confirmLabel = stringResource(R.string.settings_enable),
            onDismiss = { pendingAutoBackupUri = null },
            onConfirm = { passphrase ->
                viewModel.setAutoBackup(enabled = true, uriString = uriString, passphrase = passphrase, intervalHours = state.autoBackupIntervalHours)
                pendingAutoBackupUri = null
            },
        )
    }
}

@Composable
private fun SeedSection(revealed: Boolean, mnemonic: List<String>, onRevealRequested: () -> Unit, onHide: () -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(stringResource(R.string.settings_recovery_seed), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.settings_seed_warning),
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        if (revealed) {
            LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.testTag("seedWordsGrid")) {
                items(mnemonic) { word -> Text(word, modifier = Modifier.padding(4.dp)) }
            }
            TextButton(onClick = onHide, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.settings_hide)) }
        } else {
            Button(onClick = onRevealRequested, modifier = Modifier.testTag("revealSeedButton")) { Text(stringResource(R.string.settings_reveal_seed)) }
        }
    }
}

@Composable
private fun BackupSection(inProgress: Boolean, resultMessage: String?, onCreateBackup: () -> Unit, onDismissResult: () -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(stringResource(R.string.settings_backup), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_backup_explainer), modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        Button(onClick = onCreateBackup, enabled = !inProgress, modifier = Modifier.testTag("createBackupButton")) { Text(stringResource(R.string.settings_create_backup)) }
    }
    if (resultMessage != null) {
        AlertDialog(
            onDismissRequest = onDismissResult,
            title = { Text(stringResource(R.string.settings_backup)) },
            text = { Text(resultMessage) },
            confirmButton = { TextButton(onClick = onDismissResult) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}

@Composable
private fun RestoreSection(inProgress: Boolean, resultMessage: String?, onRestoreFromFile: () -> Unit, onDismissResult: () -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(stringResource(R.string.settings_restore), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_restore_explainer), modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        Button(onClick = onRestoreFromFile, enabled = !inProgress, modifier = Modifier.testTag("restoreBackupButton")) {
            Text(stringResource(R.string.settings_restore_from_backup))
        }
    }
    if (resultMessage != null) {
        AlertDialog(
            onDismissRequest = onDismissResult,
            title = { Text(stringResource(R.string.settings_restore)) },
            text = { Text(resultMessage) },
            confirmButton = { TextButton(onClick = onDismissResult) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}

@Composable
private fun AutoBackupSection(enabled: Boolean, intervalHours: Int, onEnable: () -> Unit, onDisable: () -> Unit, onIntervalChanged: (Int) -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_automatic_backups), style = MaterialTheme.typography.titleMedium, modifier = Modifier.Companion.weight(1f))
            Switch(checked = enabled, onCheckedChange = { if (it) onEnable() else onDisable() }, modifier = Modifier.testTag("autoBackupSwitch"))
        }
        if (enabled) {
            var intervalText by remember(intervalHours) { mutableStateOf(intervalHours.toString()) }
            OutlinedTextField(
                value = intervalText,
                onValueChange = { text ->
                    intervalText = text
                    text.toIntOrNull()?.takeIf { it > 0 }?.let(onIntervalChanged)
                },
                label = { Text(stringResource(R.string.settings_interval_hours_label)) },
                modifier = Modifier.padding(top = 8.dp).testTag("autoBackupIntervalField"),
            )
        }
    }
}

@Composable
private fun PassphraseDialog(title: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    var passphraseVisible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text(stringResource(R.string.settings_passphrase_label)) },
                visualTransformation = if (passphraseVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { passphraseVisible = !passphraseVisible }, modifier = Modifier.testTag("togglePassphraseVisibility")) {
                        Icon(
                            if (passphraseVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = stringResource(if (passphraseVisible) R.string.settings_hide_passphrase else R.string.settings_show_passphrase),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("passphraseField"),
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(passphrase) }, enabled = passphrase.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
