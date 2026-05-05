/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * PR2 placeholder. PR6 replaces the body to:
 *  - Decode the envelope via `EnvelopeDecoder`.
 *  - Render the confirmation UI (description / amount / bankDisplayName ?? bic / type).
 *  - Drive biometric → SecureArea.sign() → AuthorizationJwtBuilder.assemble.
 *  - Fire `Intent.ACTION_VIEW` on `callback` with `?state=…&authorization=…|error=…`.
 */

package eu.europa.ec.defeature.ui.authorize

import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

data class State(
    val envelope: String,
    val state: String,
    val callback: String,
) : ViewState

sealed interface Event : ViewEvent {
    data object Init : Event
}

sealed interface Effect : ViewSideEffect

@KoinViewModel
class AuthorizeOperationViewModel(
    @InjectedParam private val envelope: String,
    @InjectedParam private val state: String,
    @InjectedParam private val callback: String,
) : MviViewModel<Event, State, Effect>() {

    override fun setInitialState(): State = State(
        envelope = envelope,
        state = state,
        callback = callback,
    )

    override fun handleEvents(event: Event) {
        // PR6 fills this in.
    }
}
