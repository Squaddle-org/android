package org.squaddle.app.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.squaddle.app.R

/** `onFinished` fires once [OnboardingUiState.Done] is reached - the caller swaps to the rest of
 * the app (rooms list), not this screen. */
@Composable
public fun OnboardingScreen(viewModel: OnboardingViewModel, onFinished: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    if (state is OnboardingUiState.Done) {
        onFinished()
        return
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        when (val s = state) {
            is OnboardingUiState.ModeSelect -> ModeSelectStep(onCreate = viewModel::startCreate, onRestore = viewModel::startRestore)
            is OnboardingUiState.ShowSeed -> ShowSeedStep(
                words = s.words,
                wroteItDown = s.wroteItDown,
                onToggle = viewModel::toggleWroteItDown,
                onContinue = viewModel::confirmSeedWritten,
            )
            is OnboardingUiState.EnterSeed -> EnterSeedStep(
                input = s.input,
                error = s.error,
                onInputChange = viewModel::updateSeedInput,
                onSubmit = viewModel::submitSeed,
                onBack = viewModel::backToModeSelect,
            )
            is OnboardingUiState.NameEntry -> NameEntryStep(
                displayName = s.displayName,
                isRestore = s.isRestore,
                onNameChange = viewModel::updateDisplayName,
                onFinish = viewModel::finish,
            )
            is OnboardingUiState.Saving -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                CircularProgressIndicator()
            }
            is OnboardingUiState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
            is OnboardingUiState.Done -> Unit // handled above
        }
    }
}

@Composable
private fun ModeSelectStep(onCreate: () -> Unit, onRestore: () -> Unit) {
    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
    Text(
        stringResource(R.string.onboarding_tagline),
        modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
    )
    Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onboarding_create_identity)) }
    TextButton(onClick = onRestore, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(stringResource(R.string.onboarding_have_seed))
    }
}

@Composable
private fun ShowSeedStep(words: List<String>, wroteItDown: Boolean, onToggle: () -> Unit, onContinue: () -> Unit) {
    Text(stringResource(R.string.onboarding_seed_title), style = MaterialTheme.typography.headlineSmall)
    Text(
        stringResource(R.string.onboarding_seed_warning),
        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            for (rowStart in words.indices step 2) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("${rowStart + 1}. ${words[rowStart]}", modifier = Modifier.weight(1f).padding(4.dp))
                    if (rowStart + 1 < words.size) {
                        Text("${rowStart + 2}. ${words[rowStart + 1]}", modifier = Modifier.weight(1f).padding(4.dp))
                    }
                }
            }
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = 16.dp)
            .clickable(onClick = onToggle)
            .testTag("wroteItDownToggle"),
    ) {
        Checkbox(checked = wroteItDown, onCheckedChange = null)
        Text(stringResource(R.string.onboarding_wrote_it_down))
    }
    Button(onClick = onContinue, enabled = wroteItDown, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(stringResource(R.string.action_continue))
    }
}

@Composable
private fun EnterSeedStep(input: String, error: String?, onInputChange: (String) -> Unit, onSubmit: () -> Unit, onBack: () -> Unit) {
    Text(stringResource(R.string.onboarding_restore_title), style = MaterialTheme.typography.headlineSmall)
    Text(
        stringResource(R.string.onboarding_restore_explainer),
        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
    )
    OutlinedTextField(
        value = input,
        onValueChange = onInputChange,
        label = { Text(stringResource(R.string.onboarding_seed_input_label)) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = onSubmit, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text(stringResource(R.string.action_continue)) }
    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_back)) }
}

@Composable
private fun NameEntryStep(displayName: String, isRestore: Boolean, onNameChange: (String) -> Unit, onFinish: () -> Unit) {
    Text(stringResource(R.string.onboarding_name_title), style = MaterialTheme.typography.headlineSmall)
    Text(
        stringResource(R.string.onboarding_name_explainer),
        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
    )
    OutlinedTextField(
        value = displayName,
        onValueChange = onNameChange,
        label = { Text(stringResource(R.string.onboarding_display_name_label)) },
        modifier = Modifier.fillMaxWidth().testTag("displayNameField"),
    )
    Button(
        onClick = onFinish,
        enabled = displayName.isNotBlank(),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) {
        Text(stringResource(if (isRestore) R.string.onboarding_finish_restoring else R.string.onboarding_finish))
    }
}
