/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * Wire format for the three messages exchanged over NFC during the
 * offline-token transfer (ADR 0011 §6):
 *
 *   sender → recipient   transferOffer    INS 0x01
 *   recipient → sender   transferAck      R-APDU body
 *   sender → recipient   transferCommit   INS 0x02
 *
 * Each message is a UTF-8 JSON document placed in the APDU data field.
 * Extended-length APDUs cover the ~800-byte single-token transferCommit
 * case comfortably. Multi-token batches > ~50 tokens would need a
 * CONTINUE INS — out of scope for the workshop demo.
 */

package eu.europa.ec.denfc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * First message of the handshake. Carries the tokens the sender is
 * about to give up — per-token reconciliationUrl is the sender's bank's
 * serial-status base URL (so the recipient knows where to reconcile if
 * the sender's bank later marks the token spent).
 */
@Serializable
data class TransferOffer(
    val type: String = TYPE,
    val amount: Long,
    val currency: String,
    val tokens: List<TokenOfferRow>,
    val description: String? = null,
) {
    companion object {
        const val TYPE: String = "transferOffer"
    }
}

@Serializable
data class TokenOfferRow(
    val serial: String,
    val amount: Long,
    val reconciliationUrl: String,
)

/**
 * Response to a [TransferOffer]. Recipient announces the holderPub JWK
 * the sender must bind each TransferProof to. Generated fresh per
 * receive (mirrors per-withdraw pattern).
 */
@Serializable
data class TransferAck(
    val type: String = TYPE,
    val recipientHolderPub: JsonObject,
) {
    companion object {
        const val TYPE: String = "transferAck"
    }
}

/**
 * Final message — sender hands over the token JWSes + matching
 * TransferProofs. Same serial set as [TransferOffer.tokens]; the
 * recipient cross-checks before signalling commit-ack via [TransferOk].
 */
@Serializable
data class TransferCommit(
    val type: String = TYPE,
    val tokens: List<TokenCommitRow>,
    val transferProofs: List<TransferProofCommitRow>,
) {
    companion object {
        const val TYPE: String = "transferCommit"
    }
}

@Serializable
data class TokenCommitRow(
    val serial: String,
    val amount: Long,
    val currency: String,
    val jws: String,
)

@Serializable
data class TransferProofCommitRow(
    val serial: String,
    val jws: String,
)

/**
 * Final R-APDU body sent by the recipient after persisting all items as
 * INCOMING_PENDING (or [TransferReject] on failure). Always paired with
 * status word 9000 / 6Fxx — the body distinguishes 1-of-N failures.
 */
@Serializable
data class TransferOk(
    val type: String = TYPE,
    val acceptedSerials: List<String>,
) {
    companion object {
        const val TYPE: String = "transferOk"
    }
}

@Serializable
data class TransferReject(
    val type: String = TYPE,
    val reason: String,
    val rejectedSerials: List<String> = emptyList(),
) {
    companion object {
        const val TYPE: String = "transferReject"
    }
}
