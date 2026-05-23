/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * "Wallet holdings" tile per `mobile-wallet.md` §M4a + §M4b/c. Decoration
 * on the upstream home tab — the upstream surface owns no Accesa state.
 *
 * Tile shows the spendable (LIVE) balance plus small sub-counts when
 * tokens are pending. Tapping drills into the holdings screen for
 * detail + Send/Receive CTAs.
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
import eu.europa.ec.destorage.TokenState
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.annotation.KoinViewModel

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
                text = formatAmount(state.liveBalanceCents, "EUR"),
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
                text = subCaption(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )
        }
    }
}

private fun subCaption(state: TileState): String {
    val parts = mutableListOf("${state.liveCount} live")
    if (state.outgoingCount > 0) parts += "${state.outgoingCount} sending"
    if (state.incomingCount > 0) parts += "${state.incomingCount} receiving"
    return parts.joinToString(" · ") + " · simulated SE"
}

private fun formatAmount(cents: Long, currency: String): String {
    val whole = cents / 100
    val remainder = (cents % 100).toString().padStart(2, '0')
    val symbol = when (currency.uppercase()) { "EUR" -> "€"; else -> "$currency " }
    return "$symbol$whole.$remainder"
}

internal data class TileState(
    val liveBalanceCents: Long = 0L,
    val liveCount: Int = 0,
    val outgoingCount: Int = 0,
    val incomingCount: Int = 0,
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
            val live = tokens.count { it.state == TokenState.LIVE }
            val out = tokens.count { it.state == TokenState.OUTGOING_PENDING }
            val incoming = tokens.count { it.state == TokenState.INCOMING_PENDING }
            setState {
                copy(
                    liveBalanceCents = balance,
                    liveCount = live,
                    outgoingCount = out,
                    incomingCount = incoming,
                )
            }
        }
    }
}
