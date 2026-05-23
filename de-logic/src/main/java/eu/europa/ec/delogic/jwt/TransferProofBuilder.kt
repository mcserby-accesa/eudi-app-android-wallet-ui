/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * Builds the `de-transferproof+jwt` per ADR 0011 §5 — the JWS that a
 * holder signs to address an offline token to a recipient (M4b NFC tap)
 * or to themselves (M4c self-redeem). The signing key is the token's
 * `holderPub` private key, held in the simulated SE.
 *
 * Verification (recipient or NCB) uses the embedded `fromHolderPub`; the
 * caller cross-checks `token.holderPub == fromHolderPub` separately.
 */

package eu.europa.ec.delogic.jwt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

class TransferProofBuilder(
    private val clock: Clock = Clock.systemUTC(),
    private val nonceProvider: NonceProvider = SecureRandomNonceProvider(),
    private val json: Json = DefaultJson,
    private val expiryDuration: Duration = TRANSFER_PROOF_EXPIRY,
) {

    /**
     * Build the signing input bytes (`base64url(header) || '.' ||
     * base64url(payload)`, ASCII) for one TransferProof. The caller
     * passes [BuildResult.signingInput] to a [Signer] (the SE-held
     * holderPub private key) and reassembles the compact JWS via
     * [assemble].
     *
     * Splitting build/assemble lets a caller biometric-gate the signing
     * step without holding a long-running coroutine.
     */
    fun build(
        tokenSerial: String,
        fromHolderPub: JsonObject,
        toHolderPub: JsonObject,
        amount: Long,
        currency: String,
        senderTxCounter: Long,
        now: Instant = clock.instant(),
    ): BuildResult {
        val header = buildJsonObject {
            put("alg", "ES256")
            put("typ", "de-transferproof+jwt")
        }

        val expiry = now.plus(expiryDuration)
        val payload = buildJsonObject {
            put("version", 1)
            put("tokenSerial", tokenSerial)
            put("fromHolderPub", fromHolderPub)
            put("toHolderPub", toHolderPub)
            put("amount", amount)
            put("currency", currency)
            put("ts", now.toString())
            put("expiry", expiry.toString())
            put("nonce", nonceProvider.nextBase64Url(NONCE_BYTES))
            put("senderTxCounter", senderTxCounter)
        }

        val headerB64 = encodeB64Url(json.encodeToString(JsonObject.serializer(), header))
        val payloadB64 = encodeB64Url(json.encodeToString(JsonObject.serializer(), payload))
        val signingInput = "$headerB64.$payloadB64".toByteArray(Charsets.US_ASCII)

        return BuildResult(
            headerB64 = headerB64,
            payloadB64 = payloadB64,
            signingInput = signingInput,
            ts = now,
            expiry = expiry,
        )
    }

    suspend fun signAndAssemble(
        tokenSerial: String,
        fromHolderPub: JsonObject,
        toHolderPub: JsonObject,
        amount: Long,
        currency: String,
        senderTxCounter: Long,
        signer: Signer,
    ): String {
        val built = build(
            tokenSerial = tokenSerial,
            fromHolderPub = fromHolderPub,
            toHolderPub = toHolderPub,
            amount = amount,
            currency = currency,
            senderTxCounter = senderTxCounter,
        )
        val signature = signer.sign(built.signingInput)
        return assemble(built, signature)
    }

    fun assemble(built: BuildResult, signature: ByteArray): String {
        val sigB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
        return "${built.headerB64}.${built.payloadB64}.$sigB64"
    }

    data class BuildResult(
        val headerB64: String,
        val payloadB64: String,
        val signingInput: ByteArray,
        val ts: Instant,
        val expiry: Instant,
    ) {
        override fun equals(other: Any?): Boolean =
            other is BuildResult &&
                headerB64 == other.headerB64 &&
                payloadB64 == other.payloadB64 &&
                signingInput.contentEquals(other.signingInput) &&
                ts == other.ts &&
                expiry == other.expiry

        override fun hashCode(): Int {
            var result = headerB64.hashCode()
            result = 31 * result + payloadB64.hashCode()
            result = 31 * result + signingInput.contentHashCode()
            result = 31 * result + ts.hashCode()
            result = 31 * result + expiry.hashCode()
            return result
        }
    }

    private fun encodeB64Url(s: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))

    companion object {
        // ADR 0011 §2 — 5-minute expiry. Workshop simplification of the
        // 24–48h production analogue; protocol identical at either value.
        val TRANSFER_PROOF_EXPIRY: Duration = Duration.ofMinutes(5)
        private const val NONCE_BYTES = 16
        private val DefaultJson = Json { encodeDefaults = false; prettyPrint = false }
    }
}
