/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 */

package eu.europa.ec.delogic.jwt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.security.MessageDigest
import java.util.Base64

/**
 * RFC 7638 JWK thumbprint. Returns `base64url(SHA-256(canonical JSON))` over
 * the required members of a JWK in lexicographic order, with no whitespace.
 *
 * Supported key types: `EC` (`{crv, kty, x, y}`), `OKP` (`{crv, kty, x}`).
 */
object JwkThumbprint {

    private val CompactJson = Json { encodeDefaults = true; prettyPrint = false }

    fun compute(jwk: JsonObject): String {
        val kty = (jwk["kty"] as? JsonPrimitive)?.content
            ?: throw IllegalArgumentException("JWK missing required member: kty")

        val canonical = when (kty) {
            "EC" -> buildJsonObject {
                put("crv", member(jwk, "crv"))
                put("kty", JsonPrimitive(kty))
                put("x", member(jwk, "x"))
                put("y", member(jwk, "y"))
            }

            "OKP" -> buildJsonObject {
                put("crv", member(jwk, "crv"))
                put("kty", JsonPrimitive(kty))
                put("x", member(jwk, "x"))
            }

            else -> throw IllegalArgumentException("Unsupported JWK kty: $kty")
        }

        val canonicalJson = CompactJson.encodeToString(JsonObject.serializer(), canonical)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalJson.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun member(jwk: JsonObject, name: String): JsonElement =
        jwk[name] ?: throw IllegalArgumentException("JWK missing required member: $name")
}
