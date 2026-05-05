/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 */

package eu.europa.ec.delogic.jwt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JwkThumbprintTest {

    /**
     * Pinned EC thumbprint — independently verified against
     * `sha256('{"crv":"P-256","kty":"EC","x":"f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU","y":"x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0"}')` then base64url-no-pad.
     *
     * Verifies in one shot:
     *  (a) the four required EC members are included in lex order {crv,kty,x,y},
     *  (b) extra members (`use`, `kid`) are dropped before hashing,
     *  (c) the canonical JSON has no whitespace,
     *  (d) base64url-without-padding encoding.
     */
    @Test
    fun `pinned EC thumbprint matches independent SHA-256 computation`() {
        val jwk = parse(
            """
            {
              "kty": "EC",
              "crv": "P-256",
              "x": "f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU",
              "y": "x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0",
              "use": "enc",
              "kid": "1"
            }
            """.trimIndent(),
        )

        val expected = "oKIywvGUpTVTyxMQ3bwIIeQUudfr_CkLMjCE19ECD-U"
        assertEquals(expected, JwkThumbprint.compute(jwk))
    }

    @Test
    fun `extra non-required members do not affect the thumbprint`() {
        val a = parse("""{"kty":"EC","crv":"P-256","x":"X","y":"Y"}""")
        val b = parse(
            """
            {"kty":"EC","crv":"P-256","x":"X","y":"Y","use":"sig","kid":"abc","alg":"ES256"}
            """.trimIndent(),
        )
        assertEquals(JwkThumbprint.compute(a), JwkThumbprint.compute(b))
    }

    @Test
    fun `member order in input does not affect the thumbprint`() {
        val a = parse("""{"kty":"EC","crv":"P-256","x":"X","y":"Y"}""")
        val b = parse("""{"y":"Y","x":"X","crv":"P-256","kty":"EC"}""")
        assertEquals(JwkThumbprint.compute(a), JwkThumbprint.compute(b))
    }

    @Test
    fun `OKP keys use the three required members`() {
        val a = parse("""{"kty":"OKP","crv":"Ed25519","x":"X"}""")
        val b = parse("""{"kty":"OKP","crv":"Ed25519","x":"X","alg":"EdDSA"}""")
        assertEquals(JwkThumbprint.compute(a), JwkThumbprint.compute(b))
    }

    @Test
    fun `missing required EC member is rejected`() {
        val incomplete = parse("""{"kty":"EC","crv":"P-256","x":"X"}""")
        assertThrows(IllegalArgumentException::class.java) {
            JwkThumbprint.compute(incomplete)
        }
    }

    @Test
    fun `unknown kty is rejected`() {
        val rsa = parse("""{"kty":"RSA","n":"…","e":"AQAB"}""")
        assertThrows(IllegalArgumentException::class.java) {
            JwkThumbprint.compute(rsa)
        }
    }

    private fun parse(text: String): JsonObject =
        Json.parseToJsonElement(text) as JsonObject
}
