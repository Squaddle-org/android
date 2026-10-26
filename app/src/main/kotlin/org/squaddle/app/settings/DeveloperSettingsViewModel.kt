package org.squaddle.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

public data class DeveloperSettingsUiState(
    public val relayHostInput: String = "",
    public val defaultRelayHost: String = "",
    public val savedMessage: String? = null,
)

/** Debug-build-only relay host override (android/CLAUDE.md: "https only" is already structurally
 * guaranteed - [org.squaddle.core.sync.SyncClient] always talks TLS - so this needs no extra
 * validation beyond "non-blank"). */
public class DeveloperSettingsViewModel(
    private val store: DeveloperSettingsStore,
    defaultRelayHost: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        DeveloperSettingsUiState(relayHostInput = store.relayHostOverride() ?: "", defaultRelayHost = defaultRelayHost),
    )
    public val uiState: StateFlow<DeveloperSettingsUiState> = _uiState.asStateFlow()

    public fun updateInput(text: String) {
        _uiState.value = _uiState.value.copy(relayHostInput = text, savedMessage = null)
    }

    public fun save() {
        viewModelScope.launch {
            val host = _uiState.value.relayHostInput.trim()
            store.setRelayHostOverride(host.ifBlank { null })
            _uiState.value = _uiState.value.copy(relayHostInput = host, savedMessage = "Saved")
        }
    }

    public fun resetToDefault() {
        viewModelScope.launch {
            store.setRelayHostOverride(null)
            _uiState.value = _uiState.value.copy(relayHostInput = "", savedMessage = "Using build default")
        }
    }
}
