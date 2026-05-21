/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * Wire format for AUTHORIZE_OPERATION envelopes.
 * Authoritative spec: companion repo `specs/protocols/de-wallet-app-api.md`.
 */

package eu.europa.ec.delogic.envelope

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class OperationType {
    @SerialName("topUp")
    TOP_UP,

    @SerialName("redeem")
    REDEEM,

    @SerialName("payment")
    PAYMENT,

    @SerialName("withdrawToWallet")
    WITHDRAW_TO_WALLET,
}

@Serializable
data class Payer(
    val iban: String,
    val holderName: String,
)

/**
 * Merchant block carried in a `type: "payment"` envelope. `merchantId` is the
 * merchant's NCB userId — opaque to the wallet UI; signed only so the bank
 * backend can cross-check it at confirm time. `merchantName` is rendered
 * verbatim on the confirm screen. `description` is the optional human-readable
 * line of the payment-request — the merchant's typed-in description.
 *
 * For `type: "topUp"` and `type: "redeem"` the envelope's `payee` is `null`
 * and the wallet does not render any payee block.
 */
@Serializable
data class Payee(
    val merchantId: String,
    val merchantName: String,
    val description: String? = null,
)

/**
 * Operation envelope as sent by a bank app via `eudi-de-authorize://?envelope=…`.
 *
 * The wallet's confirmation screen displays only `description`, formatted
 * `amount`, `bankDisplayName ?? bic`, and `type` — never `payer.iban` or
 * `payer.holderName`. The full envelope is still signed verbatim into the
 * authorisation JWT, where the bank's backend cross-checks the bank-side
 * fields at verification time.
 */
@Serializable
data class OperationEnvelope(
    val type: OperationType,
    val amount: Long,
    val currency: String,
    val paymentRef: String,
    val expiry: String,
    val payer: Payer,
    val payee: Payee? = null,
    val bic: String,
    val bankDisplayName: String? = null,
    val description: String,
    /**
     * `true` on the inbound envelope when `type == WITHDRAW_TO_WALLET` — the bank
     * app is telling the wallet to mint a fresh `holderPub` and embed it before
     * signing (`specs/protocols/de-wallet-app-api.md` §withdrawToWallet). Absent
     * for every other operation type.
     */
    val holderPubRequest: Boolean? = null,
    /**
     * Always `null` on the inbound envelope — the wallet sets this to the freshly
     * generated EC P-256 JWK at sign time, before passing the envelope to the
     * authorisation-JWT builder. The bank backend cross-checks `envelope.holderPub`
     * against the request body's `holderPub` field at `/deliver` verification time.
     */
    val holderPub: JsonObject? = null,
)
