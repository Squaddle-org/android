@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.squaddle.app.ui.voucher

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.squaddle.app.R

/** Same PROTOCOL.md §8.1 tier names as `RoomScreen.kt`'s own `tierLabel` - kept as its own small
 * copy here rather than a shared cross-package function, not worth the coupling for four lines. */
@Composable
private fun voucherTierLabel(tier: String): String = when (tier) {
    "free" -> stringResource(R.string.tier_free)
    "boosted" -> stringResource(R.string.tier_boosted)
    "unlimited" -> stringResource(R.string.tier_unlimited)
    else -> tier.replaceFirstChar { it.uppercase() }
}

@Composable
public fun VoucherRedeemScreen(viewModel: VoucherRedeemViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.voucher_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            val resultTier = state.resultTier
            if (resultTier != null) {
                Text(stringResource(R.string.voucher_new_tier, voucherTierLabel(resultTier)), style = MaterialTheme.typography.titleMedium)
                Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.action_done)) }
            } else {
                Text(stringResource(R.string.voucher_explainer))
                OutlinedTextField(
                    value = state.code,
                    onValueChange = viewModel::setCode,
                    label = { Text(stringResource(R.string.voucher_code_label)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("voucherCodeField"),
                )
                Button(
                    onClick = viewModel::redeem,
                    enabled = !state.isRedeeming && state.code.isNotBlank(),
                    modifier = Modifier.padding(top = 16.dp).testTag("redeemVoucherButton"),
                ) { Text(stringResource(R.string.voucher_redeem)) }
            }
        }
    }

    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            title = { Text(stringResource(R.string.voucher_error_title)) },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = viewModel::dismissError) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}
