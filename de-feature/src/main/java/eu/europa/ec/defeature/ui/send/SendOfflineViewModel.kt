/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * Sender side of the M4b NFC tap (ADR 0011 §6). Owns:
 *  - amount entry + LIVE-balance validation
 *  - LIFO token selection from the simulated SE
 *  - the reader-mode handshake (SELECT → OFFER → COMMIT)
 *  - state-machine transitions on the SE (LIVE → OUTGOING_PENDING) via
 *    SimulatedSecureElement.signTransferProofs
 *
 * The Compose layer hands its host Activity in on `StartTap` because
 * NfcAdapter.enableReaderMode requires an Activity context.
 */

package eu.europa.ec.defeature.ui.send

import android.app.Activity
import androidx.lifecycle.viewModelScope
import eu.europa.ec.delogic.jws.TransferProofVerifier
import eu.europa.ec.delogic.jws.TransferProofVerifyResult
import eu.europa.ec.denfc.JsonOverApdu
import eu.europa.ec.denfc.NfcReaderController
import eu.europa.ec.denfc.OfflineTransferReader
import eu.europa.ec.denfc.ReaderOutcome
import eu.europa.ec.denfc.TokenCommitRow
import eu.europa.ec.denfc.TokenOfferRow
import eu.europa.ec.denfc.TransferCommit
import eu.europa.ec.denfc.TransferOffer
import eu.europa.ec.denfc.TransferReject
import eu.europa.ec.denfc.TransferProofCommitRow
import eu.europa.ec.destorage.HeldToken
import eu.europa.ec.destorage.SimulatedSecureElement
import eu.europa.ec.destorage.TokenState
import eu.europa.ec.destorage.TransferProofJws
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

sealed interface SendState : ViewState {
    /** Loading the SE balance + LIVE token list. */
    data object Loading : SendState

    /** Showing the amount input. */
    data class Idle(
        val liveBalanceCents: Long,
        val amountInput: String = "",
        val errorMessage: String? = null,
    ) : SendState

    /** NFC reader mode active, waiting for a tap. */
    data class Tapping(val amountCents: Long, val selectedSerials: List<String>) : SendState

    /** Tap finished successfully — tokens moved to OUTGOING_PENDING. */
    data class Sent(val amountCents: Long, val serials: List<String>) : SendState

    /** Tap failed — reason maps to a user-visible message. */
    data class Failed(val message: String) : SendState
}

sealed interface SendEvent : ViewEvent {
    data class AmountChanged(val raw: String) : SendEvent
    data class StartTap(val activity: Activity) : SendEvent
    data object Cancel : SendEvent
    data object DismissError : SendEvent
}

sealed interface SendEffect : ViewSideEffect {
    data object NavigateBack : SendEffect
}

@KoinViewModel
class SendOfflineViewModel(
    private val simulatedSecureElement: SimulatedSecureElement,
    private val nfcReader: NfcReaderController,
    private val transferReader: OfflineTransferReader,
    private val transferProofVerifier: TransferProofVerifier,
) : MviViewModel<SendEvent, SendState, SendEffect>() {

    init {
        // Initial state is Loading; bootstrap fires the SE read once on
        // construction so the screen lands on Idle quickly.
        viewModelScope.launch { loadIdle() }
    }

    override fun setInitialState(): SendState = SendState.Loading

    override fun handleEvents(event: SendEvent) {
        when (event) {
            is SendEvent.AmountChanged -> onAmountChanged(event.raw)
            is SendEvent.StartTap -> startTap(event.activity)
            SendEvent.Cancel -> setEffect { SendEffect.NavigateBack }
            SendEvent.DismissError -> viewModelScope.launch { loadIdle() }
        }
    }

    private suspend fun loadIdle() {
        val balance = simulatedSecureElement.offlineBalance()
        setState { SendState.Idle(liveBalanceCents = balance) }
    }

    private fun onAmountChanged(raw: String) {
        val current = viewState.value
        if (current !is SendState.Idle) return
        // Allow only digits; max 6 chars (€9999.99 cap).
        val sanitised = raw.filter { it.isDigit() }.take(6)
        setState { current.copy(amountInput = sanitised, errorMessage = null) }
    }

    private fun startTap(activity: Activity) {
        val current = viewState.value
        if (current !is SendState.Idle) return

        val amountCents = parseAmountCents(current.amountInput)
        val validationError = validateAmount(amountCents, current.liveBalanceCents)
        if (validationError != null) {
            setState { current.copy(errorMessage = validationError) }
            return
        }
        amountCents!! // non-null after validation

        viewModelScope.launch {
            val live = simulatedSecureElement.listHeldTokens()
                .filter { it.state == TokenState.LIVE && it.currency == "EUR" }
            val selected = pickLifo(live, amountCents)
            if (selected == null) {
                setState { SendState.Failed("No combination of LIVE tokens sums to exactly the amount entered.") }
                return@launch
            }
            setState { SendState.Tapping(amountCents = amountCents, selectedSerials = selected.map { it.serial }) }

            runTap(activity = activity, amountCents = amountCents, selected = selected)
        }
    }

    private suspend fun runTap(
        activity: Activity,
        amountCents: Long,
        selected: List<HeldToken>,
    ) {
        // SE rows carry per-token reconciliationUrl; the offer message
        // forwards it so the recipient can reconcile against the sender's
        // bank. We assume every selected token shares a non-null URL
        // (M4a deliver always set it post-PR23). Tokens predating that
        // would carry null — guard with an empty string fallback for the
        // workshop, even though the recipient's reconcile would no-op.
        val offerRows = selected.map { token ->
            TokenOfferRow(
                serial = token.serial,
                amount = token.amount,
                reconciliationUrl = token.reconciliationUrl.orEmpty(),
            )
        }
        val offer = TransferOffer(
            amount = amountCents,
            currency = "EUR",
            tokens = offerRows,
            description = "Receive €${formatEuros(amountCents)} via NFC",
        )

        val outcome = nfcReader.run(activity = activity) { pipe ->
            transferReader.execute(
                pipe = pipe,
                offer = offer,
                buildCommit = { ack ->
                    val proofs = simulatedSecureElement.signTransferProofs(
                        serials = selected.map { it.serial },
                        recipientHolderPub = ack.recipientHolderPub,
                        currency = "EUR",
                    )
                    TransferCommit(
                        tokens = selected.map { tok ->
                            TokenCommitRow(
                                serial = tok.serial,
                                amount = tok.amount,
                                currency = tok.currency,
                                jws = tok.jws,
                            )
                        },
                        transferProofs = proofs.map { proof: TransferProofJws ->
                            TransferProofCommitRow(serial = proof.tokenSerial, jws = proof.jws)
                        },
                    )
                },
            )
        }

        when (outcome) {
            is ReaderOutcome.Ok ->
                setState { SendState.Sent(amountCents = amountCents, serials = outcome.acceptedSerials) }

            is ReaderOutcome.SelectFailed ->
                setState { SendState.Failed("The other phone wasn't ready to receive (status 0x${outcome.statusWord.toString(16)}).") }

            is ReaderOutcome.OfferRejected ->
                setState { SendState.Failed(decodeReject(outcome.body) ?: "Receiver rejected the offer.") }

            ReaderOutcome.OfferAckMalformed ->
                setState { SendState.Failed("Receiver returned a malformed acknowledgement.") }

            is ReaderOutcome.CommitRejected ->
                setState { SendState.Failed(decodeReject(outcome.body) ?: "Receiver rejected the commit.") }

            ReaderOutcome.CommitAckMalformed ->
                setState { SendState.Failed("Receiver returned a malformed commit acknowledgement.") }

            null ->
                setState { SendState.Failed("NFC unavailable or tap timed out. Move the phones closer and try again.") }
        }
    }

    private fun pickLifo(live: List<HeldToken>, amountCents: Long): List<HeldToken>? {
        val sorted = live.sortedByDescending { it.issuedAt }
        val picked = mutableListOf<HeldToken>()
        var remaining = amountCents
        for (tok in sorted) {
            if (remaining == 0L) break
            if (tok.amount <= remaining) {
                picked += tok
                remaining -= tok.amount
            }
        }
        return if (remaining == 0L) picked else null
    }

    private fun parseAmountCents(raw: String): Long? = raw.toLongOrNull()

    private fun validateAmount(amountCents: Long?, liveBalanceCents: Long): String? = when {
        amountCents == null || amountCents <= 0L -> "Enter an amount in cents (e.g. 500 for €5)."
        amountCents % 500L != 0L -> "Amount must be a multiple of €5 (workshop denominations)."
        amountCents > liveBalanceCents -> "Amount exceeds your spendable offline balance."
        else -> null
    }

    private fun decodeReject(body: ByteArray): String? = runCatching {
        JsonOverApdu.decodeMessage<TransferReject>(body).reason
    }.getOrNull()

    private fun formatEuros(cents: Long): String {
        val whole = cents / 100
        val rem = (cents % 100).toString().padStart(2, '0')
        return "$whole.$rem"
    }
}
