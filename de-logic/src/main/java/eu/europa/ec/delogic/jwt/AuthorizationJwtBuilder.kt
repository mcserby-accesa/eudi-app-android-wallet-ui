/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * Builds the AUTHORIZE_OPERATION authorisation JWT defined by
 * `specs/protocols/de-wallet-app-api.md` in the companion repo.
 */

package eu.europa.ec.delogic.jwt

import eu.europa.ec.delogic.envelope.OperationEnvelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.util.Base64

/**
 * JWS Compact-serialised authorisation JWT with `typ = de-authz+jwt`, `alg =
 * ES256`, and a payload that embeds the operation envelope verbatim.
 *
 * Bank-side verification (per the protocol spec):
 *   1. Resolve customer → `Customer.deviceKey`.
 *   2. Verify the signature against that key; match `kid` to the pinned thumbprint.
 *   3. Cross-check `payload.envelope.payer.iban`, `bic`, `amount`, `paymentRef`
 *      against the pending operation it created.
 *   4. Verify `payload.envelope.expiry` and `payload.iat` (±5min default window).
 */
class AuthorizationJwtBuilder(
    private val clock: Clock = Clock.systemUTC(),
    private val nonceProvider: NonceProvider = SecureRandomNonceProvider(),
    private val json: Json = DefaultJson,
) {
    /**
     * Build the signing input bytes (`base64url(header) || '.' || base64url(payload)`,
     * ASCII) and the per-call inputs the signer needs. The caller passes the
     * resulting [BuildResult.signingInput] to a [Signer] and reassembles the
     * compact JWS via [assemble].
     *
     * Split into "build" + "assemble" so that callers can biometric-gate the
     * signing step between them without holding a long-running coroutine.
     */
    fun build(
        envelope: OperationEnvelope,
        deviceKeyJwk: JsonObject,
    ): BuildResult {
        val thumbprint = JwkThumbprint.compute(deviceKeyJwk)

        val header = buildJsonObject {
            put("alg", "ES256")
            put("typ", "de-authz+jwt")
            put("kid", thumbprint)
        }

        val payload = buildJsonObject {
            put("iat", clock.instant().epochSecond)
            put("envelope", json.encodeToJsonElement(OperationEnvelope.serializer(), envelope))
            put("aud", envelope.bic)
            put("sub_jwk", deviceKeyJwk)
            put("nonce", nonceProvider.nextBase64Url(NONCE_BYTES))
        }

        val headerB64 = encodeBase64Url(json.encodeToString(JsonObject.serializer(), header))
        val payloadB64 = encodeBase64Url(json.encodeToString(JsonObject.serializer(), payload))
        val signingInput = "$headerB64.$payloadB64".toByteArray(Charsets.US_ASCII)

        return BuildResult(
            headerB64 = headerB64,
            payloadB64 = payloadB64,
            signingInput = signingInput,
        )
    }

    suspend fun signAndAssemble(
        envelope: OperationEnvelope,
        deviceKeyJwk: JsonObject,
        signer: Signer,
    ): String {
        val built = build(envelope, deviceKeyJwk)
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
    ) {
        override fun equals(other: Any?): Boolean =
            other is BuildResult &&
                headerB64 == other.headerB64 &&
                payloadB64 == other.payloadB64 &&
                signingInput.contentEquals(other.signingInput)

        override fun hashCode(): Int {
            var result = headerB64.hashCode()
            result = 31 * result + payloadB64.hashCode()
            result = 31 * result + signingInput.contentHashCode()
            return result
        }
    }

    private fun encodeBase64Url(s: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))

    private companion object {
        const val NONCE_BYTES = 16
        val DefaultJson = Json { encodeDefaults = false; prettyPrint = false }
    }
}
