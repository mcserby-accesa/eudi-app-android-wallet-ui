/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * Verifier for `de-transferproof+jwt` JWSes per ADR 0011 §5. Checks:
 *   - JWS shape and header values (`alg=ES256`, `typ=de-transferproof+jwt`).
 *   - ES256 signature against the embedded `fromHolderPub` JWK.
 *   - `expiry` is in the future.
 *
 * The verifier does NOT check `tokenSerial == token.serial` or
 * `fromHolderPub == token.holderPub` — those are the caller's
 * responsibility because they require the corresponding token in hand
 * (matched by serial). See [TransferProofVerifyResult.Ok.payload] for
 * the fields the caller cross-checks.
 */

package eu.europa.ec.delogic.jws

import eu.europa.ec.delogic.jwt.JwkEcPublicKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.util.Base64

sealed interface TransferProofVerifyResult {
    data class Ok(val payload: TransferProofPayload) : TransferProofVerifyResult

    sealed interface Failure : TransferProofVerifyResult {
        val reason: String

        /** Spec callback code: `transfer_proof_invalid`. */
        data class SignatureInvalid(override val reason: String) : Failure

        /** Spec callback code: `transfer_proof_invalid`. */
        data class Malformed(override val reason: String) : Failure

        /** Spec callback code: `transfer_proof_expired`. */
        data object Expired : Failure {
            override val reason: String = "transfer_proof_expired"
        }
    }
}

data class TransferProofPayload(
    val tokenSerial: String,
    val fromHolderPub: JsonObject,
    val toHolderPub: JsonObject,
    val amount: Long,
    val currency: String,
    val ts: Instant,
    val expiry: Instant,
    val nonce: String,
    val senderTxCounter: Long,
)

interface TransferProofVerifier {
    fun verify(compactJws: String): TransferProofVerifyResult
}

class TransferProofVerifierImpl(
    private val clock: Clock = Clock.systemUTC(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = false },
) : TransferProofVerifier {

    override fun verify(compactJws: String): TransferProofVerifyResult {
        val segments = compactJws.split(".")
        if (segments.size != 3) return malformed("not_a_compact_jws")

        val headerRaw = decodeSegment(segments[0]) ?: return malformed("header_decode")
        val payloadRaw = decodeSegment(segments[1]) ?: return malformed("payload_decode")
        val signatureBytes = runCatching { Base64.getUrlDecoder().decode(segments[2]) }
            .getOrNull() ?: return malformed("signature_decode")

        val header = runCatching { json.parseToJsonElement(headerRaw).jsonObject }
            .getOrNull() ?: return malformed("header_json")
        val payloadJson = runCatching { json.parseToJsonElement(payloadRaw).jsonObject }
            .getOrNull() ?: return malformed("payload_json")

        if (header["alg"]?.jsonPrimitive?.content != "ES256") return malformed("alg")
        if (header["typ"]?.jsonPrimitive?.content != "de-transferproof+jwt") return malformed("typ")

        val payload = runCatching { decodePayload(payloadJson) }
            .getOrNull() ?: return malformed("payload_shape")

        val publicKey = runCatching { JwkEcPublicKey.parseP256(payload.fromHolderPub) }
            .getOrNull()
            ?: return TransferProofVerifyResult.Failure.SignatureInvalid("from_holder_pub_unparseable")

        val signingInput = "${segments[0]}.${segments[1]}".toByteArray(Charsets.US_ASCII)
        if (signatureBytes.size != 64) {
            return TransferProofVerifyResult.Failure.SignatureInvalid("signature_size")
        }
        val derSig = runCatching { DerSignatureEncoding.encodeP256(signatureBytes) }
            .getOrNull()
            ?: return TransferProofVerifyResult.Failure.SignatureInvalid("signature_encoding")

        val valid = runCatching {
            Signature.getInstance("SHA256withECDSA").apply {
                initVerify(publicKey)
                update(signingInput)
            }.verify(derSig)
        }.getOrDefault(false)
        if (!valid) return TransferProofVerifyResult.Failure.SignatureInvalid("signature")

        if (!payload.expiry.isAfter(clock.instant())) {
            return TransferProofVerifyResult.Failure.Expired
        }
        return TransferProofVerifyResult.Ok(payload)
    }

    private fun decodeSegment(segment: String): String? = runCatching {
        Base64.getUrlDecoder().decode(segment).toString(Charsets.UTF_8)
    }.getOrNull()

    private fun decodePayload(payload: JsonObject): TransferProofPayload {
        fun str(name: String): String = payload[name]!!.jsonPrimitive.content
        fun longOf(name: String): Long = payload[name]!!.jsonPrimitive.longOrNull
            ?: throw IllegalArgumentException(name)
        return TransferProofPayload(
            tokenSerial = str("tokenSerial"),
            fromHolderPub = payload["fromHolderPub"]!!.jsonObject,
            toHolderPub = payload["toHolderPub"]!!.jsonObject,
            amount = longOf("amount"),
            currency = str("currency"),
            ts = Instant.parse(str("ts")),
            expiry = Instant.parse(str("expiry")),
            nonce = str("nonce"),
            senderTxCounter = longOf("senderTxCounter"),
        )
    }

    private fun malformed(reason: String): TransferProofVerifyResult =
        TransferProofVerifyResult.Failure.Malformed("malformed:$reason")
}
