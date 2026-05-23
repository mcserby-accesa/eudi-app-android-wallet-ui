/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * HCE responder for the offline-token transfer (ADR 0011 §6). Receives
 * SELECT-AID → TRANSFER_OFFER → TRANSFER_COMMIT from the sender's
 * reader-mode wallet, forwards the parsed messages to the active
 * [NfcTransferSession] (installed by the receive-offline ViewModel),
 * and returns the R-APDU each step.
 *
 * Activation gate: every command APDU is rejected with
 * SW_CONDITIONS_NOT_SATISFIED if no session is registered. The receive
 * screen registers via [NfcTransferSessionRegistry.set] when shown and
 * clears on dispose.
 */

package eu.europa.ec.denfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import kotlinx.coroutines.runBlocking

class OfflineTransferHostApduService : HostApduService() {

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null) return SW_INCORRECT_DATA

        if (isSelectAid(commandApdu)) {
            // Even on SELECT-AID we want to refuse if no session is
            // listening — otherwise the sender's app could think the
            // recipient is ready when the user hasn't opened "Receive
            // offline" yet.
            val session = NfcTransferSessionRegistry.current()
                ?: return SW_CONDITIONS_NOT_SATISFIED
            return JsonOverApdu.successResponse(emptyCapabilitiesBody())
        }

        val command = JsonOverApdu.parseCommandApdu(commandApdu)
            ?: return SW_INCORRECT_DATA

        val session = NfcTransferSessionRegistry.current()
            ?: return SW_CONDITIONS_NOT_SATISFIED

        return when (command.ins) {
            INS_TRANSFER_OFFER -> handleOffer(session, command.data)
            INS_TRANSFER_COMMIT -> handleCommit(session, command.data)
            else -> SW_FILE_NOT_FOUND
        }
    }

    override fun onDeactivated(reason: Int) {
        // Reasons: DEACTIVATION_LINK_LOSS (NFC field withdrew) or
        // DEACTIVATION_DESELECTED (sender SELECT'd a different AID).
        // Either way the handshake is over.
        NfcTransferSessionRegistry.current()?.onSessionEnded()
    }

    private fun handleOffer(session: NfcTransferSession, body: ByteArray): ByteArray {
        val offer = runCatching {
            JsonOverApdu.decodeMessage<TransferOffer>(body)
        }.getOrElse {
            return JsonOverApdu.errorResponse(
                rejectBody(reason = "offer_malformed"),
                SW_INCORRECT_DATA,
            )
        }
        if (offer.type != TransferOffer.TYPE) {
            return JsonOverApdu.errorResponse(
                rejectBody(reason = "offer_wrong_type"),
                SW_INCORRECT_DATA,
            )
        }

        val ack = runCatching {
            runBlocking { session.onOfferReceived(offer) }
        }.getOrElse {
            return JsonOverApdu.errorResponse(
                rejectBody(reason = "recipient_failed"),
                SW_INTERNAL_ERROR,
            )
        }
        return JsonOverApdu.successResponse(JsonOverApdu.encodeMessage(ack))
    }

    private fun handleCommit(session: NfcTransferSession, body: ByteArray): ByteArray {
        val commit = runCatching {
            JsonOverApdu.decodeMessage<TransferCommit>(body)
        }.getOrElse {
            return JsonOverApdu.errorResponse(
                rejectBody(reason = "commit_malformed"),
                SW_INCORRECT_DATA,
            )
        }
        if (commit.type != TransferCommit.TYPE) {
            return JsonOverApdu.errorResponse(
                rejectBody(reason = "commit_wrong_type"),
                SW_INCORRECT_DATA,
            )
        }

        val outcome = runCatching {
            runBlocking { session.onCommitReceived(commit) }
        }.getOrElse {
            return JsonOverApdu.errorResponse(
                rejectBody(reason = "recipient_failed"),
                SW_INTERNAL_ERROR,
            )
        }
        return when (outcome) {
            is CommitOutcome.Ok -> JsonOverApdu.successResponse(
                JsonOverApdu.encodeMessage(TransferOk(acceptedSerials = outcome.acceptedSerials)),
            )
            is CommitOutcome.Rejected -> JsonOverApdu.errorResponse(
                JsonOverApdu.encodeMessage(
                    TransferReject(
                        reason = outcome.reason,
                        rejectedSerials = outcome.rejectedSerials,
                    ),
                ),
                SW_INCORRECT_DATA,
            )
        }
    }

    private fun emptyCapabilitiesBody(): ByteArray =
        """{"v":1}""".toByteArray(Charsets.UTF_8)

    private fun rejectBody(reason: String): ByteArray =
        JsonOverApdu.encodeMessage(TransferReject(reason = reason))
}
