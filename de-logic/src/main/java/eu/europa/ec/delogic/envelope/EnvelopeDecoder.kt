/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 */

package eu.europa.ec.delogic.envelope

import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Instant
import java.util.Base64

sealed interface EnvelopeDecodeResult {
    data class Success(val envelope: OperationEnvelope) : EnvelopeDecodeResult

    sealed interface Failure : EnvelopeDecodeResult {
        val errorCode: String

        data object Malformed : Failure {
            override val errorCode: String = "invalid_envelope"
        }

        data object Expired : Failure {
            override val errorCode: String = "expired"
        }
    }
}

/**
 * Decodes the base64url payload from an `eudi-de-authorize://?envelope=…` deep
 * link into a typed [OperationEnvelope] and validates the structural / temporal
 * preconditions the protocol requires before the wallet shows the user a
 * confirmation screen.
 */
class EnvelopeDecoder(
    private val clock: Clock = Clock.systemUTC(),
    private val json: Json = StrictJson,
) {
    fun decode(base64UrlPayload: String): EnvelopeDecodeResult {
        val raw = decodeBase64Url(base64UrlPayload)
            ?: return EnvelopeDecodeResult.Failure.Malformed

        val envelope = runCatching {
            json.decodeFromString(OperationEnvelope.serializer(), raw)
        }.getOrNull() ?: return EnvelopeDecodeResult.Failure.Malformed

        if (envelope.amount <= 0) return EnvelopeDecodeResult.Failure.Malformed
        if (envelope.currency.isBlank()) return EnvelopeDecodeResult.Failure.Malformed
        if (envelope.paymentRef.isBlank()) return EnvelopeDecodeResult.Failure.Malformed
        if (envelope.bic.isBlank()) return EnvelopeDecodeResult.Failure.Malformed
        if (envelope.description.isBlank()) return EnvelopeDecodeResult.Failure.Malformed
        if (envelope.type == OperationType.PAYMENT && envelope.payee == null) {
            return EnvelopeDecodeResult.Failure.Malformed
        }

        val expiry = runCatching { Instant.parse(envelope.expiry) }.getOrNull()
            ?: return EnvelopeDecodeResult.Failure.Malformed
        if (!expiry.isAfter(clock.instant())) return EnvelopeDecodeResult.Failure.Expired

        return EnvelopeDecodeResult.Success(envelope)
    }

    private fun decodeBase64Url(payload: String): String? = runCatching {
        Base64.getUrlDecoder().decode(payload).toString(Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        // Strict — fail closed on unknown fields and unexpected `type` values.
        val StrictJson = Json {
            ignoreUnknownKeys = false
            isLenient = false
        }
    }
}
