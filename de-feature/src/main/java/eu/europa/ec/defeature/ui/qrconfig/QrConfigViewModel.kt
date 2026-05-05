/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * First-launch QR-config screen. PR3 ships paste-or-type only; the camera-based
 * QR scanner is folded in once we know which upstream code path to plug into.
 */

package eu.europa.ec.defeature.ui.qrconfig

import androidx.lifecycle.viewModelScope
import eu.europa.ec.delogic.config.WalletConfigDecoder
import eu.europa.ec.delogic.state.WalletState
import eu.europa.ec.delogic.state.WalletStateRepository
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

data class State(
    val input: String = "",
    val error: ConfigError? = null,
    val isSubmitting: Boolean = false,
) : ViewState

enum class ConfigError {
    EMPTY,
    INVALID_URL,
    MALFORMED_JSON,
}

sealed interface Event : ViewEvent {
    data class InputChanged(val value: String) : Event
    data object Submit : Event
}

sealed interface Effect : ViewSideEffect {
    sealed interface Navigation : Effect {
        data object ToSplash : Navigation
    }
}

@KoinViewModel
class QrConfigViewModel(
    private val walletConfigDecoder: WalletConfigDecoder,
    private val walletStateRepository: WalletStateRepository,
) : MviViewModel<Event, State, Effect>() {

    override fun setInitialState(): State = State()

    override fun handleEvents(event: Event) {
        when (event) {
            is Event.InputChanged -> setState {
                copy(input = event.value, error = null)
            }

            is Event.Submit -> submit()
        }
    }

    private fun submit() {
        if (viewState.value.isSubmitting) return
        val current = viewState.value.input
        when (val decoded = walletConfigDecoder.decode(current)) {
            is WalletConfigDecoder.Result.Failure -> setState {
                copy(error = decoded.errorCode.toUi())
            }

            is WalletConfigDecoder.Result.Success -> persist(decoded.pidIssuerUrl)
        }
    }

    private fun persist(pidIssuerUrl: String) {
        setState { copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            walletStateRepository.save(WalletState(pidIssuerUrl = pidIssuerUrl))
            setEffect { Effect.Navigation.ToSplash }
        }
    }

    private fun WalletConfigDecoder.ErrorCode.toUi(): ConfigError = when (this) {
        WalletConfigDecoder.ErrorCode.EMPTY -> ConfigError.EMPTY
        WalletConfigDecoder.ErrorCode.MALFORMED_JSON -> ConfigError.MALFORMED_JSON
        WalletConfigDecoder.ErrorCode.INVALID_URL -> ConfigError.INVALID_URL
    }
}
