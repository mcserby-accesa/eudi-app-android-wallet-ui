/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * "Wallet holdings" drill-in. Reads the simulated SE's current state
 * once on enter — there is no live observation; if the user withdraws,
 * sends, receives, or reconciles, the screen reloads on next entry.
 *
 * Splits the persisted token set into three buckets so the drill-in
 * can render Live / Sending / Receiving sections per ADR 0011 §7
 * (TokenState).
 */

package eu.europa.ec.defeature.ui.holdings

import androidx.lifecycle.viewModelScope
import eu.europa.ec.destorage.HeldToken
import eu.europa.ec.destorage.SimulatedSecureElement
import eu.europa.ec.destorage.TokenState
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

sealed interface HoldingsState : ViewState {
    data object Loading : HoldingsState
    data class Loaded(
        /** Spendable balance — sum of LIVE tokens only. */
        val liveBalanceCents: Long,
        /** All non-CONSUMED tokens, in their original order. */
        val allTokens: List<HeldToken>,
        val liveTokens: List<HeldToken>,
        val outgoingPending: List<HeldToken>,
        val incomingPending: List<HeldToken>,
    ) : HoldingsState
}

sealed interface HoldingsEvent : ViewEvent {
    data object Refresh : HoldingsEvent
    data object Back : HoldingsEvent
}

sealed interface HoldingsEffect : ViewSideEffect {
    data object NavigateBack : HoldingsEffect
}

@KoinViewModel
class WalletHoldingsViewModel(
    private val simulatedSecureElement: SimulatedSecureElement,
) : MviViewModel<HoldingsEvent, HoldingsState, HoldingsEffect>() {

    override fun setInitialState(): HoldingsState = HoldingsState.Loading

    override fun handleEvents(event: HoldingsEvent) {
        when (event) {
            HoldingsEvent.Refresh -> refresh()
            HoldingsEvent.Back -> setEffect { HoldingsEffect.NavigateBack }
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            val balance = simulatedSecureElement.offlineBalance()
            val tokens = simulatedSecureElement.listHeldTokens()
            val live = tokens.filter { it.state == TokenState.LIVE }
            val outgoing = tokens.filter { it.state == TokenState.OUTGOING_PENDING }
            val incoming = tokens.filter { it.state == TokenState.INCOMING_PENDING }
            setState {
                HoldingsState.Loaded(
                    liveBalanceCents = balance,
                    allTokens = tokens,
                    liveTokens = live,
                    outgoingPending = outgoing,
                    incomingPending = incoming,
                )
            }
        }
    }
}
