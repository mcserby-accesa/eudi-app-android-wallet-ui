/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * Implements the AUTHORIZE_OPERATION receiver per
 * `specs/protocols/de-wallet-app-api.md` in the companion repo:
 *  1. Decode the base64url envelope from the deep-link query.
 *  2. Render only the user-visible fields (description / amount / bank / type).
 *  3. On Confirm: biometric → SecureArea.sign() with the PID's single key →
 *     assemble the de-authz+jwt JWS → fire the callback URI on the bank app.
 *  4. On Cancel or any error: fire the callback URI with the spec's error code.
 */

package eu.europa.ec.defeature.ui.authorize

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.commonfeature.interactor.DeviceAuthenticationInteractor
import eu.europa.ec.delogic.envelope.EnvelopeDecodeResult
import eu.europa.ec.delogic.envelope.EnvelopeDecoder
import eu.europa.ec.delogic.envelope.OperationEnvelope
import eu.europa.ec.delogic.jwt.AuthorizationJwtBuilder
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

/** Spec error codes returned to the bank app via the callback URI. */
internal object AuthorizeErrorCode {
    const val INVALID_ENVELOPE = "invalid_envelope"
    const val EXPIRED = "expired"
    const val USER_CANCELLED = "user_cancelled"
    const val WALLET_NOT_PROVISIONED = "wallet_not_provisioned"
}

sealed interface State : ViewState {
    /** Envelope rejected at decode time; auto-fire the callback and finish. */
    data class FailedEarly(
        val errorCode: String,
        val state: String,
        val callback: String,
    ) : State

    /** Envelope decoded; render the confirmation screen. */
    data class ReadyToConfirm(
        val envelope: OperationEnvelope,
        val state: String,
        val callback: String,
    ) : State

    /** Confirmed; biometric prompt + signing in flight. */
    data class Signing(
        val envelope: OperationEnvelope,
        val state: String,
        val callback: String,
    ) : State
}

sealed interface Event : ViewEvent {
    data object OnEnter : Event
    data object Cancel : Event
    data class Confirm(val context: Context) : Event
}

sealed interface Effect : ViewSideEffect {
    /** Fire `Intent.ACTION_VIEW` on the callback URI and close the wallet. */
    data class FireCallbackAndFinish(val uri: Uri) : Effect
}

@KoinViewModel
class AuthorizeOperationViewModel(
    @InjectedParam private val envelopeArg: String,
    @InjectedParam private val stateArg: String,
    @InjectedParam private val callbackArg: String,
    // `withdrawToWallet`-only: target URL the wallet POSTs the signed
    // authorisation + holderPub to after biometric confirm, and the single-use
    // bearer it sets on that POST. Empty strings for every other operation type.
    // Slice 1 just plumbs them through; consumed in slice 4.
    @InjectedParam private val deliveryUrlArg: String,
    @InjectedParam private val deliveryTokenArg: String,
    private val envelopeDecoder: EnvelopeDecoder,
    private val authorizationJwtBuilder: AuthorizationJwtBuilder,
    private val pidCredentialSigner: PidCredentialSigner,
    private val deviceAuthenticationInteractor: DeviceAuthenticationInteractor,
) : MviViewModel<Event, State, Effect>() {

    override fun setInitialState(): State {
        if (callbackArg.isBlank()) {
            // No callback URI means we have nowhere to return the result. Fail
            // closed without firing anything; the user sees the auto-finish.
            return State.FailedEarly(
                errorCode = AuthorizeErrorCode.INVALID_ENVELOPE,
                state = stateArg,
                callback = "",
            )
        }
        return when (val decoded = envelopeDecoder.decode(envelopeArg)) {
            is EnvelopeDecodeResult.Success -> State.ReadyToConfirm(
                envelope = decoded.envelope,
                state = stateArg,
                callback = callbackArg,
            )

            is EnvelopeDecodeResult.Failure -> State.FailedEarly(
                errorCode = decoded.errorCode,
                state = stateArg,
                callback = callbackArg,
            )
        }
    }

    override fun handleEvents(event: Event) {
        when (event) {
            Event.OnEnter -> {
                val s = viewState.value
                if (s is State.FailedEarly) fireFailureAndFinish(s.callback, s.state, s.errorCode)
            }

            Event.Cancel -> {
                val s = viewState.value
                fireFailureAndFinish(s.callback(), s.stateArg(), AuthorizeErrorCode.USER_CANCELLED)
            }

            is Event.Confirm -> {
                val s = viewState.value as? State.ReadyToConfirm ?: return
                setState {
                    State.Signing(envelope = s.envelope, state = s.state, callback = s.callback)
                }
                signAndFireCallback(event.context, s.envelope, s.state, s.callback)
            }
        }
    }

    private fun signAndFireCallback(
        context: Context,
        envelope: OperationEnvelope,
        callbackState: String,
        callback: String,
    ) {
        // Run a biometric prompt as the inherence factor (per de-wallet-app-api.md
        // §Security properties). The PID document's keys are configured with
        // `userAuthenticationRequired = false` upstream, so the prompt runs as a
        // UI gate rather than as a key-unlocking ceremony — equivalent to the
        // upstream OID4VP KB JWT pattern.
        deviceAuthenticationInteractor.authenticateWithBiometrics(
            context = context,
            crypto = BiometricCrypto(cryptoObject = null),
            notifyOnAuthenticationFailure = true,
            resultHandler = DeviceAuthenticationResult(
                onAuthenticationSuccess = { signAndComplete(envelope, callbackState, callback) },
                onAuthenticationError = {
                    fireFailureAndFinish(callback, callbackState, AuthorizeErrorCode.USER_CANCELLED)
                },
                onAuthenticationFailure = { /* prompt re-enters; nothing to do */ },
            ),
        )
    }

    private fun signAndComplete(
        envelope: OperationEnvelope,
        callbackState: String,
        callback: String,
    ) {
        viewModelScope.launch {
            val resolved = try {
                pidCredentialSigner.resolveOrThrow()
            } catch (_: PidCredentialSigner.WalletNotProvisionedException) {
                fireFailureAndFinish(callback, callbackState, AuthorizeErrorCode.WALLET_NOT_PROVISIONED)
                return@launch
            }

            val jws = try {
                authorizationJwtBuilder.signAndAssemble(
                    envelope = envelope,
                    deviceKeyJwk = resolved.publicJwk,
                    signer = resolved.signer,
                )
            } catch (_: Throwable) {
                fireFailureAndFinish(callback, callbackState, AuthorizeErrorCode.WALLET_NOT_PROVISIONED)
                return@launch
            }

            setEffect {
                Effect.FireCallbackAndFinish(buildSuccessUri(callback, callbackState, jws))
            }
        }
    }

    private fun fireFailureAndFinish(callback: String, callbackState: String, errorCode: String) {
        // No callback to fire → just finish silently. The wallet activity will
        // close and the bank app will time out its own polling.
        if (callback.isBlank()) {
            setEffect { Effect.FireCallbackAndFinish(Uri.EMPTY) }
            return
        }
        setEffect {
            Effect.FireCallbackAndFinish(buildFailureUri(callback, callbackState, errorCode))
        }
    }

    private fun buildSuccessUri(callback: String, state: String, jws: String): Uri =
        callback.toUri().buildUpon()
            .appendQueryParameter("state", state)
            .appendQueryParameter("authorization", jws)
            .build()

    private fun buildFailureUri(callback: String, state: String, errorCode: String): Uri =
        callback.toUri().buildUpon()
            .appendQueryParameter("state", state)
            .appendQueryParameter("error", errorCode)
            .build()

    private fun State.callback(): String = when (this) {
        is State.FailedEarly -> callback
        is State.ReadyToConfirm -> callback
        is State.Signing -> callback
    }

    private fun State.stateArg(): String = when (this) {
        is State.FailedEarly -> state
        is State.ReadyToConfirm -> state
        is State.Signing -> state
    }
}
