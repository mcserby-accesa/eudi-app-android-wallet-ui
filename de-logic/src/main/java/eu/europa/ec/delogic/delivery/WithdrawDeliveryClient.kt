/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * Direct-POST `/deliver` HTTP client per `de-wallet-app-api.md`
 * §withdrawToWallet and ADR 0010 §5. The wallet POSTs the signed
 * authorisation + holderPub to whatever `deliveryUrl` arrived on the
 * `eudi-de-authorize://` deep-link, with `deliveryToken` as a Bearer.
 *
 * The wallet stays bank-agnostic: it does not know any bank URLs at
 * compile time and forgets them after the operation completes. See
 * memory note `feedback-wallet-bank-agnostic` for the design rule.
 */

package eu.europa.ec.delogic.delivery

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

class WithdrawDeliveryClient(private val httpClient: HttpClient) {

    /**
     * M4c (sync + self-redeem). POST signed authorisation + the (token,
     * transferProof) pairs to the bank's `/sync/deliver` or
     * `/redeem-self/deliver` endpoint — same wire shape, the bank-app's
     * /initiate response chose the path by URL.
     *
     * No `holderPub` in the request body: the proofs already bind each
     * token to the right key per ADR 0011 §5 (sync uses recipient's
     * holderPub from the NFC tap; self-redeem uses token.holderPub as
     * both `from` and `to`).
     */
    suspend fun redeem(
        deliveryUrl: String,
        deliveryToken: String,
        walletAuthorisationJwt: String,
        tokens: List<RedeemTokenRow>,
        transferProofs: List<RedeemProofRow>,
    ): DeliveryResult = runCatching {
        val response: HttpResponse = httpClient.post(deliveryUrl) {
            bearerAuth(deliveryToken)
            contentType(ContentType.Application.Json)
            setBody(
                RedeemRequest(
                    walletAuthorisation = walletAuthorisationJwt,
                    tokens = tokens,
                    transferProofs = transferProofs,
                ),
            )
        }
        mapDeliveryResponse(response)
    }.getOrElse { DeliveryResult.Error("network_error") }

    /**
     * Sends the wallet's signed authorisation + freshly minted holderPub
     * to [deliveryUrl] and returns the bank's minted-token response.
     *
     * Error codes follow `mobile-wallet.md` §M4a Error mapping — they are
     * the strings the wallet eventually appends to the bank-app callback
     * `?error=…` query parameter.
     */
    suspend fun deliver(
        deliveryUrl: String,
        deliveryToken: String,
        walletAuthorisationJwt: String,
        holderPub: JsonObject,
    ): DeliveryResult = runCatching {
        val response: HttpResponse = httpClient.post(deliveryUrl) {
            bearerAuth(deliveryToken)
            contentType(ContentType.Application.Json)
            setBody(
                DeliverRequest(
                    walletAuthorisation = walletAuthorisationJwt,
                    holderPub = holderPub,
                ),
            )
        }
        when (response.status) {
            HttpStatusCode.OK -> {
                runCatching { response.body<DeliverResponse>() }
                    .map { DeliveryResult.Ok(it) }
                    .getOrElse { DeliveryResult.Error("delivery_invalid") }
            }

            // 401s come in two shapes from the bank backend
            // (DELIVERY_TOKEN_INVALID vs AUTHORISATION_INVALID); the
            // body's `code` distinguishes them. Default to the broader
            // `auth_rejected` if the body is unparseable.
            HttpStatusCode.Unauthorized -> {
                val errorCode = parseErrorCode(response) ?: "AUTHORISATION_INVALID"
                DeliveryResult.Error(
                    when (errorCode) {
                        "DELIVERY_TOKEN_INVALID" -> "delivery_token_invalid"
                        else -> "auth_rejected"
                    },
                )
            }

            HttpStatusCode.Conflict -> DeliveryResult.Error("cap_exceeded")

            // 410 GONE — pending expired before wallet authorised.
            HttpStatusCode.Gone -> DeliveryResult.Error("expired")

            // Anything else maps to a generic delivery_invalid — the
            // bank-app's mapWalletError will surface a generic failure copy.
            else -> DeliveryResult.Error("delivery_invalid")
        }
    }.getOrElse { thrown ->
        // Connection refused, DNS failure, TLS error, etc. — networking
        // failures map to `network_error`. Throwing through to the
        // ViewModel would lose the typed-result shape that the rest of
        // the post-confirm flow consumes.
        DeliveryResult.Error("network_error")
    }

    private suspend fun parseErrorCode(response: HttpResponse): String? = runCatching {
        val text = response.bodyAsText()
        Regex(""""code"\s*:\s*"([^"]+)"""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
    }.getOrNull()

    /**
     * Shared status-code → DeliveryResult mapping. Used by both `deliver`
     * (withdraw path; expects DeliverResponse) and `redeem` (sync + self-redeem
     * path; expects RedeemResponse, but only the 200 branch parses the body).
     */
    private suspend fun mapDeliveryResponse(response: HttpResponse): DeliveryResult =
        when (response.status) {
            HttpStatusCode.OK -> {
                runCatching { response.body<DeliverResponse>() }
                    .map { DeliveryResult.Ok(it) }
                    .getOrElse { DeliveryResult.Error("delivery_invalid") }
            }
            HttpStatusCode.Unauthorized -> {
                val errorCode = parseErrorCode(response) ?: "AUTHORISATION_INVALID"
                DeliveryResult.Error(
                    when (errorCode) {
                        "DELIVERY_TOKEN_INVALID" -> "delivery_token_invalid"
                        else -> "auth_rejected"
                    },
                )
            }
            HttpStatusCode.Conflict -> {
                val errorCode = parseErrorCode(response) ?: ""
                DeliveryResult.Error(
                    when (errorCode) {
                        "SERIAL_ALREADY_SPENT" -> "serial_already_spent"
                        "TRANSFER_PROOF_EXPIRED" -> "transfer_proof_expired"
                        "CAP_EXCEEDED" -> "cap_exceeded"
                        else -> "cap_exceeded"  // historical default for the withdraw path
                    },
                )
            }
            HttpStatusCode.BadRequest -> {
                val errorCode = parseErrorCode(response) ?: ""
                DeliveryResult.Error(
                    when (errorCode) {
                        "TRANSFER_PROOF_INVALID" -> "transfer_proof_invalid"
                        "TRANSFER_PROOF_TO_MISMATCH" -> "transfer_proof_invalid"
                        else -> "delivery_invalid"
                    },
                )
            }
            HttpStatusCode.Gone -> DeliveryResult.Error("expired")
            else -> DeliveryResult.Error("delivery_invalid")
        }
}

@Serializable
data class RedeemRequest(
    @SerialName("walletAuthorisation") val walletAuthorisation: String,
    val tokens: List<RedeemTokenRow>,
    val transferProofs: List<RedeemProofRow>,
)

@Serializable
data class RedeemTokenRow(
    val serial: String,
    val amount: Long,
    val currency: String,
    val jws: String,
)

@Serializable
data class RedeemProofRow(
    val serial: String,
    val jws: String,
)

sealed interface DeliveryResult {
    data class Ok(val response: DeliverResponse) : DeliveryResult

    /** Wallet-side spec error code. See `mobile-wallet.md` §M4a Error mapping. */
    data class Error(val code: String) : DeliveryResult
}

@Serializable
data class DeliverRequest(
    @SerialName("walletAuthorisation") val walletAuthorisation: String,
    @SerialName("holderPub") val holderPub: JsonObject,
)

@Serializable
data class DeliverResponse(
    val tokens: List<DeliveredToken>,
    /**
     * Bank's base URL where the wallet's reconcile job calls
     * `GET /bank/de/offline/serial-status/{serial}` (ADR 0011 §8).
     * Stored per token on the SE side. Nullable for backward-compat with
     * pre-M4b/c bank builds that don't yet serve the field — wallet's
     * reconcile path no-ops when missing.
     */
    val reconciliationUrl: String? = null,
    val newOnlineBalance: Long? = null,
    val newOfflineBalance: Long? = null,
    val eventId: String? = null,
)

/**
 * Raw token row as the bank's `/deliver` endpoint returns it. `jws` is
 * the compact-serialised `de-offline+jwt`; the wallet verifies it
 * separately via [eu.europa.ec.delogic.jws.OfflineTokenVerifier] before
 * persistence.
 */
@Serializable
data class DeliveredToken(
    val serial: String,
    val amount: Long,
    val currency: String,
    val jws: String,
)
