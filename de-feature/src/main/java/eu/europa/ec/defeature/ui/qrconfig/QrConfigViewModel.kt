/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * PR2 placeholder. The PR3 implementation will:
 *  - Embed the upstream `QrScanScreen` from `:common-feature`.
 *  - Parse the scanned JSON `{ pidIssuerUrl }`.
 *  - Persist `WalletState` via `WalletStateRepository`.
 *  - Navigate into the upstream OID4VCI issuance flow.
 */

package eu.europa.ec.defeature.ui.qrconfig

import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import org.koin.core.annotation.KoinViewModel

data object State : ViewState

sealed interface Event : ViewEvent {
    data object Init : Event
}

sealed interface Effect : ViewSideEffect

@KoinViewModel
class QrConfigViewModel : MviViewModel<Event, State, Effect>() {
    override fun setInitialState(): State = State
    override fun handleEvents(event: Event) {
        // PR3 fills this in.
    }
}
