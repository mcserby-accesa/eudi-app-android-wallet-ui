/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * "Wallet holdings" drill-in. Reads the simulated SE's current state
 * once on enter — there is no live observation; if the user withdraws
 * again the user returns through the AUTHORIZE_OPERATION callback and
 * the screen re-loads naturally on next entry.
 */

package eu.europa.ec.defeature.ui.holdings

import androidx.lifecycle.viewModelScope
import eu.europa.ec.destorage.HeldToken
import eu.europa.ec.destorage.SimulatedSecureElement
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

sealed interface HoldingsState : ViewState {
    data object Loading : HoldingsState
    data class Loaded(
        val balanceCents: Long,
        val tokens: List<HeldToken>,
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
            setState { HoldingsState.Loaded(balanceCents = balance, tokens = tokens) }
        }
    }
}
