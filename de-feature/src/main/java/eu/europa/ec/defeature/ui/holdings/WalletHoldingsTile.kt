/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * "Wallet holdings" tile per `mobile-wallet.md` §M4a. Decoration on the
 * upstream home tab — the upstream surface owns no Accesa state.
 */

package eu.europa.ec.defeature.ui.holdings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import eu.europa.ec.destorage.SimulatedSecureElement
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.annotation.KoinViewModel

/**
 * Cross-feature entry point for the dashboard. The dashboard module
 * imports this composable directly (see `dashboard-feature → de-feature`
 * dep). The wrapping Koin ViewModel is resolved here so callers don't
 * need to know about [SimulatedSecureElement].
 */
@Composable
fun WalletHoldingsTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: WalletHoldingsTileViewModel = koinViewModel()
    val state: TileState by viewModel.viewState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.setEvent(TileEvent.Refresh)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(PaddingValues(horizontal = 16.dp, vertical = 16.dp)),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = formatAmount(state.balanceCents, "EUR"),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = "Offline Digital Euro on this device",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = "${state.tokenCount} tokens · simulated SE",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )
        }
    }
}

private fun formatAmount(cents: Long, currency: String): String {
    val whole = cents / 100
    val remainder = (cents % 100).toString().padStart(2, '0')
    val symbol = when (currency.uppercase()) { "EUR" -> "€"; else -> "$currency " }
    return "$symbol$whole.$remainder"
}

internal data class TileState(
    val balanceCents: Long = 0L,
    val tokenCount: Int = 0,
) : ViewState

internal sealed interface TileEvent : ViewEvent {
    data object Refresh : TileEvent
}

internal sealed interface TileEffect : ViewSideEffect

@KoinViewModel
internal class WalletHoldingsTileViewModel(
    private val simulatedSecureElement: SimulatedSecureElement,
) : MviViewModel<TileEvent, TileState, TileEffect>() {

    override fun setInitialState(): TileState = TileState()

    override fun handleEvents(event: TileEvent) {
        when (event) {
            TileEvent.Refresh -> refresh()
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            val balance = simulatedSecureElement.offlineBalance()
            val tokens = simulatedSecureElement.listHeldTokens()
            setState { copy(balanceCents = balance, tokenCount = tokens.size) }
        }
    }
}
