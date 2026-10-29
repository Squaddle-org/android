package org.squaddle.app.ui.voucher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.squaddle.app.data.RelayErrorException
import org.squaddle.app.data.SyncGateway
import org.squaddle.app.data.friendlyErrorMessage

public data class VoucherRedeemUiState(
    public val code: String = "",
    public val isRedeeming: Boolean = false,
    public val resultTier: String? = null,
    public val error: String? = null,
)

/**
 * PROTOCOL.md §8.3's voucher provider only - `google_play` stays unimplemented client-side too
 * (M5b, same scope cut the relay's own handler already makes). Available in both flavors: boost
 * *purchase* (Play Billing) is what's flavor-gated, not voucher *redemption*, which needs no
 * Google dependency at all.
 */
public class VoucherRedeemViewModel(private val room: ByteArray, private val gateway: SyncGateway) : ViewModel() {
    private val _uiState = MutableStateFlow(VoucherRedeemUiState())
    public val uiState: StateFlow<VoucherRedeemUiState> = _uiState.asStateFlow()

    public fun setCode(text: String) {
        _uiState.value = _uiState.value.copy(code = text, error = null)
    }

    public fun redeem() {
        val code = _uiState.value.code.trim()
        if (code.isBlank()) return
        _uiState.value = _uiState.value.copy(isRedeeming = true, error = null)
        viewModelScope.launch {
            val result = gateway.redeemVoucher(room, code)
            _uiState.value = result.fold(
                onSuccess = { limits -> _uiState.value.copy(isRedeeming = false, resultTier = limits.tier) },
                onFailure = { e ->
                    val message = (e as? RelayErrorException)?.let { friendlyErrorMessage(it.code, it.message ?: "") } ?: e.message
                    _uiState.value.copy(isRedeeming = false, error = message ?: "redeeming voucher failed")
                },
            )
        }
    }

    public fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
