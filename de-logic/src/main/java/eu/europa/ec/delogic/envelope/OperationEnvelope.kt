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

@Serializable
enum class OperationType {
    @SerialName("topUp")
    TOP_UP,

    @SerialName("redeem")
    REDEEM,

    @SerialName("payment")
    PAYMENT,
}

@Serializable
data class Payer(
    val iban: String,
    val holderName: String,
)

@Serializable
data class Payee(
    val iban: String? = null,
    val holderName: String? = null,
    val alias: String? = null,
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
)
