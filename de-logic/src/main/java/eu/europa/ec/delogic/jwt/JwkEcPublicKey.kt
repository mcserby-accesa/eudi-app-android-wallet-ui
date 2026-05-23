/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * Convert an RFC 7517 EC JWK (`{kty:"EC", crv:"P-256", x, y}`) into a
 * java.security.interfaces.ECPublicKey ready for ES256 signature
 * verification. P-256 only — the only curve the wallet uses.
 */

package eu.europa.ec.delogic.jwt

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64

object JwkEcPublicKey {

    private val P256_PARAMS: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC")
            .apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    }

    /**
     * Parse [jwk] as an EC P-256 public key. Throws
     * IllegalArgumentException if any required member is missing or if
     * the key type / curve is not the one the wallet expects.
     */
    fun parseP256(jwk: JsonObject): ECPublicKey {
        val kty = jwk["kty"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("JWK missing required member: kty")
        require(kty == "EC") { "Unsupported JWK kty: $kty (expected EC)" }
        val crv = jwk["crv"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("JWK missing required member: crv")
        require(crv == "P-256") { "Unsupported JWK crv: $crv (expected P-256)" }

        val x = jwk["x"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("JWK missing required member: x")
        val y = jwk["y"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("JWK missing required member: y")

        val decoder = Base64.getUrlDecoder()
        val xBytes = runCatching { decoder.decode(x) }
            .getOrElse { throw IllegalArgumentException("JWK x: not base64url") }
        val yBytes = runCatching { decoder.decode(y) }
            .getOrElse { throw IllegalArgumentException("JWK y: not base64url") }

        val point = ECPoint(BigInteger(1, xBytes), BigInteger(1, yBytes))
        val spec = ECPublicKeySpec(point, P256_PARAMS)
        return KeyFactory.getInstance("EC").generatePublic(spec) as ECPublicKey
    }
}
