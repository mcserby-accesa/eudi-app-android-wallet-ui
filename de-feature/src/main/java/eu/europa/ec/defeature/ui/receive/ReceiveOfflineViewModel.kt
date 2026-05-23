/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * Receiver side of the M4b NFC tap. Generates a fresh holderPub on
 * entry, registers itself as the active NfcTransferSession so the
 * HostApduService starts accepting taps, persists incoming items as
 * INCOMING_PENDING via SimulatedSecureElement.acceptIncoming, and
 * tears down on Cleared.
 */

package eu.europa.ec.defeature.ui.receive

import androidx.lifecycle.viewModelScope
import eu.europa.ec.denfc.CommitOutcome
import eu.europa.ec.denfc.NfcTransferSession
import eu.europa.ec.denfc.NfcTransferSessionRegistry
import eu.europa.ec.denfc.TransferAck
import eu.europa.ec.denfc.TransferCommit
import eu.europa.ec.denfc.TransferOffer
import eu.europa.ec.destorage.AcceptResult
import eu.europa.ec.destorage.HolderKeyHandle
import eu.europa.ec.destorage.IncomingItem
import eu.europa.ec.destorage.OfflineTokenJws
import eu.europa.ec.destorage.SimulatedSecureElement
import eu.europa.ec.destorage.TransferProofJws
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.annotation.KoinViewModel

sealed interface ReceiveState : ViewState {
    /** Generating a holderPub and registering the NFC session. */
    data object Preparing : ReceiveState

    /** Ready and listening — HCE is armed; user is told to tap. */
    data object Listening : ReceiveState

    /** Received an offer (informational; the SE accepts the commit next). */
    data class OfferReceived(val amountCents: Long, val description: String?) : ReceiveState

    /** Final happy state — committed tokens persisted as INCOMING_PENDING. */
    data class Received(val acceptedSerials: List<String>) : ReceiveState

    data class Failed(val message: String) : ReceiveState
}

sealed interface ReceiveEvent : ViewEvent {
    data object Back : ReceiveEvent
}

sealed interface ReceiveEffect : ViewSideEffect {
    data object NavigateBack : ReceiveEffect
}

@KoinViewModel
class ReceiveOfflineViewModel(
    private val simulatedSecureElement: SimulatedSecureElement,
) : MviViewModel<ReceiveEvent, ReceiveState, ReceiveEffect>() {

    private var holderHandle: HolderKeyHandle? = null
    private val session = object : NfcTransferSession {

        override suspend fun onOfferReceived(offer: TransferOffer): TransferAck {
            val handle = ensureHandle()
            setState {
                ReceiveState.OfferReceived(
                    amountCents = offer.amount,
                    description = offer.description,
                )
            }
            return TransferAck(recipientHolderPub = handle.publicJwk)
        }

        override suspend fun onCommitReceived(commit: TransferCommit): CommitOutcome {
            val handle = ensureHandle()

            // Re-pair tokens and proofs by serial for the SE call.
            val proofBySerial = commit.transferProofs.associateBy { it.serial }
            val reconciliationByOffer = offerReconciliationUrls
            val items = commit.tokens.mapNotNull { tok ->
                val proof = proofBySerial[tok.serial] ?: return@mapNotNull null
                val url = reconciliationByOffer[tok.serial] ?: ""
                IncomingItem(
                    token = OfflineTokenJws(
                        serial = tok.serial,
                        amount = tok.amount,
                        currency = tok.currency,
                        jws = tok.jws,
                    ),
                    transferProof = TransferProofJws(
                        tokenSerial = proof.serial,
                        jws = proof.jws,
                    ),
                    reconciliationUrl = url,
                )
            }

            val result = simulatedSecureElement.acceptIncoming(
                ourHolderPub = handle,
                items = items,
            )
            return when (result) {
                is AcceptResult.Ok -> {
                    setState { ReceiveState.Received(acceptedSerials = items.map { it.token.serial }) }
                    CommitOutcome.Ok(acceptedSerials = items.map { it.token.serial })
                }
                is AcceptResult.PartialOk -> {
                    setState { ReceiveState.Received(acceptedSerials = items.map { it.token.serial }.minus(result.rejected.map { it.serial }.toSet())) }
                    CommitOutcome.Ok(acceptedSerials = items.map { it.token.serial }.minus(result.rejected.map { it.serial }.toSet()))
                }
                is AcceptResult.AllRejected -> {
                    val reasons = result.rejections.joinToString { "${it.serial}=${it.reason}" }
                    setState { ReceiveState.Failed("All tokens rejected: $reasons") }
                    CommitOutcome.Rejected(reason = "all_rejected", rejectedSerials = result.rejections.map { it.serial })
                }
            }
        }

        override fun onSessionEnded() { /* No-op: state remains as-is so the user sees the final outcome. */ }
    }

    /**
     * Per-serial reconciliationUrl is shipped in the transferOffer
     * (per ADR 0011 §6 amendment), but onCommitReceived processes the
     * commit message. We stash the URL map between the two callbacks
     * here, captured at offer time.
     */
    private var offerReconciliationUrls: Map<String, String> = emptyMap()

    init {
        viewModelScope.launch {
            holderHandle = simulatedSecureElement.generateHolderKey()
            NfcTransferSessionRegistry.set(wrappedSession())
            setState { ReceiveState.Listening }
        }
    }

    /** Wraps [session] so it captures offer.tokens.reconciliationUrl into the per-serial stash. */
    private fun wrappedSession(): NfcTransferSession = object : NfcTransferSession {
        override suspend fun onOfferReceived(offer: TransferOffer): TransferAck {
            offerReconciliationUrls = offer.tokens.associate { it.serial to it.reconciliationUrl }
            return session.onOfferReceived(offer)
        }
        override suspend fun onCommitReceived(commit: TransferCommit): CommitOutcome =
            session.onCommitReceived(commit)
        override fun onSessionEnded() = session.onSessionEnded()
    }

    private fun ensureHandle(): HolderKeyHandle = holderHandle
        ?: runBlocking { simulatedSecureElement.generateHolderKey() }.also { holderHandle = it }

    override fun setInitialState(): ReceiveState = ReceiveState.Preparing

    override fun handleEvents(event: ReceiveEvent) {
        when (event) {
            ReceiveEvent.Back -> setEffect { ReceiveEffect.NavigateBack }
        }
    }

    override fun onCleared() {
        // Stop listening as soon as the ViewModel is disposed — anyone
        // else's tap should not land here.
        NfcTransferSessionRegistry.clear()
        super.onCleared()
    }
}
