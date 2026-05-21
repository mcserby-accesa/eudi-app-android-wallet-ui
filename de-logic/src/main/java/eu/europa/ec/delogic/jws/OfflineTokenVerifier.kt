/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * Verifier for `de-offline+jwt` tokens (ADR 0010 §1). Verifies:
 *  - JWS shape and header values (`alg`, `typ`).
 *  - `x5c` cert chain validates to a [NcbTrustStore] anchor under PKIX.
 *  - ES256 signature against the leaf cert's public key.
 *  - `holderPub` in the payload matches the wallet-generated keypair.
 *  - `expiry` is in the future.
 *
 * Defense-in-depth: even though the bank backend already verified the
 * wallet's authorisation against `Customer.deviceKey`, and the NCB issued
 * the token, the wallet refuses to persist a bearer claim it has not
 * itself verified — exactly as the spec mandates at withdraw §step 5.
 */

package eu.europa.ec.delogic.jws

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.Signature
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Instant
import java.util.Base64

/**
 * Outcome of an [OfflineTokenVerifier.verify] call. Failure reasons map
 * onto the bank-app callback `error` vocabulary in
 * `mobile-wallet.md` §M4a Error mapping.
 */
sealed interface OfflineTokenVerifyResult {
    data class Ok(val payload: OfflineTokenPayload) : OfflineTokenVerifyResult

    sealed interface Failure : OfflineTokenVerifyResult {
        val reason: String

        /** Spec callback code: `token_signature_invalid`. */
        data class SignatureInvalid(override val reason: String) : Failure

        /** Spec callback code: `holder_pub_mismatch`. */
        data object HolderPubMismatch : Failure {
            override val reason: String = "holder_pub_mismatch"
        }

        /** Internal — bubbles up to `token_signature_invalid`. */
        data class Malformed(override val reason: String) : Failure

        /** Spec callback code: `delivery_invalid` (per StoreResult.Rejected mapping). */
        data class Expired(override val reason: String = "expired") : Failure
    }
}

/**
 * Parsed `de-offline+jwt` payload. The verifier never returns this
 * without having first validated the JWS signature + chain.
 */
data class OfflineTokenPayload(
    val serial: String,
    val amount: Long,
    val currency: String,
    val ncbBic: String,
    val holderPub: JsonObject,
    val issuedAt: Instant,
    val expiry: Instant,
)

interface OfflineTokenVerifier {
    /**
     * Verifies [compactJws]'s signature + chain + holderPub + expiry.
     * Returns the parsed payload on success; otherwise a typed failure.
     *
     * [expectedHolderPub] is the JWK whose private half lives in the
     * simulated SE for this withdraw operation — every minted token must
     * be bound to that key per ADR 0010 §5.
     */
    fun verify(
        compactJws: String,
        expectedHolderPub: JsonObject,
    ): OfflineTokenVerifyResult
}

class OfflineTokenVerifierImpl(
    private val trustStore: NcbTrustStore,
    private val clock: Clock = Clock.systemUTC(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = false },
) : OfflineTokenVerifier {

    override fun verify(
        compactJws: String,
        expectedHolderPub: JsonObject,
    ): OfflineTokenVerifyResult {
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
        if (header["typ"]?.jsonPrimitive?.content != "de-offline+jwt") return malformed("typ")

        val x5c = header["x5c"]?.let { if (it is JsonArray) it.jsonArray else null }
            ?: return malformed("x5c_missing")
        val chain = x5c.mapNotNull { element ->
            runCatching { decodeCertificate(element.jsonPrimitive.content) }.getOrNull()
        }
        if (chain.size != x5c.size || chain.isEmpty()) {
            return malformed("x5c_decode")
        }

        val anchors = trustStore.trustAnchors()
        if (anchors.isEmpty()) {
            // Workshop placeholder cert hasn't been populated yet (per the
            // README in resources-logic/.../workshop_ncb_root.pem). Fail
            // closed; production must never reach this branch.
            return OfflineTokenVerifyResult.Failure.SignatureInvalid("trust_anchor_unconfigured")
        }
        if (!validatesAgainstAnchors(chain, anchors)) {
            return OfflineTokenVerifyResult.Failure.SignatureInvalid("chain")
        }

        val signingInput = "${segments[0]}.${segments[1]}".toByteArray(Charsets.US_ASCII)
        if (!verifyEs256(chain.first(), signingInput, signatureBytes)) {
            return OfflineTokenVerifyResult.Failure.SignatureInvalid("signature")
        }

        val payload = runCatching { decodePayload(payloadJson) }
            .getOrNull() ?: return malformed("payload_shape")

        if (!holderPubsEqual(payload.holderPub, expectedHolderPub)) {
            return OfflineTokenVerifyResult.Failure.HolderPubMismatch
        }
        if (!payload.expiry.isAfter(clock.instant())) {
            return OfflineTokenVerifyResult.Failure.Expired()
        }

        return OfflineTokenVerifyResult.Ok(payload)
    }

    private fun decodeSegment(segment: String): String? = runCatching {
        Base64.getUrlDecoder().decode(segment).toString(Charsets.UTF_8)
    }.getOrNull()

    private fun decodeCertificate(base64: String): X509Certificate {
        // x5c values are standard base64 (RFC 7515 §4.1.6) — not base64url.
        val der = Base64.getDecoder().decode(base64)
        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(der.inputStream()) as X509Certificate
    }

    /**
     * Validates [chain] against any anchor in [anchors] via PKIX. The
     * cert path is the chain MINUS the root (the validator finds the
     * matching anchor itself). If the chain ends at a self-signed cert
     * that exactly matches an anchor, drop it from the path; if it ends
     * at an intermediate that chains to an anchor, keep the full chain.
     */
    private fun validatesAgainstAnchors(
        chain: List<X509Certificate>,
        anchors: Set<X509Certificate>,
    ): Boolean {
        val pathCerts = if (anchors.any { it == chain.last() }) {
            chain.dropLast(1).ifEmpty { return chain.single() in anchors }
        } else {
            chain
        }
        val factory = CertificateFactory.getInstance("X.509")
        val certPath = factory.generateCertPath(pathCerts)
        val params = PKIXParameters(anchors.map { TrustAnchor(it, null) }.toSet()).apply {
            isRevocationEnabled = false
        }
        return runCatching {
            CertPathValidator.getInstance("PKIX").validate(certPath, params)
            true
        }.getOrDefault(false)
    }

    private fun verifyEs256(
        leaf: X509Certificate,
        signingInput: ByteArray,
        jwsSignature: ByteArray,
    ): Boolean = runCatching {
        if (jwsSignature.size != 64) return@runCatching false
        val der = DerSignatureEncoding.encodeP256(jwsSignature)
        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(leaf.publicKey)
            update(signingInput)
        }
        verifier.verify(der)
    }.getOrDefault(false)

    private fun decodePayload(payload: JsonObject): OfflineTokenPayload {
        fun str(name: String): String = payload[name]!!.jsonPrimitive.content
        val issuedAtRaw = payload["issuedAt"]!!.jsonPrimitive
        val expiryRaw = payload["expiry"]!!.jsonPrimitive
        return OfflineTokenPayload(
            serial = str("serial"),
            amount = payload["amount"]!!.jsonPrimitive.longOrNull
                ?: throw IllegalArgumentException("amount"),
            currency = str("currency"),
            ncbBic = str("ncbBic"),
            holderPub = payload["holderPub"]!!.jsonObject,
            issuedAt = Instant.parse(issuedAtRaw.content),
            expiry = Instant.parse(expiryRaw.content),
        )
    }

    /**
     * Compare two EC JWKs by their RFC 7517 §3 required members
     * (`kty`, `crv`, `x`, `y`) only. Optional members like `use` or
     * `kid` don't affect identity and must not.
     */
    private fun holderPubsEqual(a: JsonObject, b: JsonObject): Boolean {
        val members = setOf("kty", "crv", "x", "y")
        return members.all { name -> a[name] == b[name] }
    }

    private fun malformed(reason: String): OfflineTokenVerifyResult =
        OfflineTokenVerifyResult.Failure.Malformed("malformed:$reason")
}
