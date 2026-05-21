/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 */

package eu.europa.ec.delogic.jwt

import eu.europa.ec.delogic.envelope.OperationEnvelope
import eu.europa.ec.delogic.envelope.OperationType
import eu.europa.ec.delogic.envelope.Payer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

class AuthorizationJwtBuilderTest {

    private val deviceJwk: JsonObject = Json.parseToJsonElement(
        """
        {
          "kty": "EC",
          "crv": "P-256",
          "x": "f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU",
          "y": "x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0"
        }
        """.trimIndent(),
    ).jsonObject

    private val envelope = OperationEnvelope(
        type = OperationType.TOP_UP,
        amount = 5000,
        currency = "EUR",
        paymentRef = "11111111-2222-3333-4444-555555555555",
        expiry = "2030-01-01T00:00:00Z",
        payer = Payer(iban = "DE89370400440532013000", holderName = "Mihai Test"),
        payee = null,
        bic = "DEMODEAA",
        bankDisplayName = "Bank A",
        description = "Top up €50.00 from your account at Bank A",
    )

    @Test
    fun `header carries alg, typ, and the JWK thumbprint as kid`() {
        val builder = AuthorizationJwtBuilder(
            clock = Clock.fixed(Instant.parse("2026-05-05T12:00:00Z"), ZoneOffset.UTC),
            nonceProvider = { _ -> "AAAA" },
        )

        val built = builder.build(envelope, deviceJwk)
        val header = decodeJsonSegment(built.headerB64)

        assertEquals("ES256", header.field("alg"))
        assertEquals("de-authz+jwt", header.field("typ"))
        assertEquals(JwkThumbprint.compute(deviceJwk), header.field("kid"))
    }

    @Test
    fun `payload embeds the envelope verbatim and binds aud to bic`() {
        val builder = AuthorizationJwtBuilder(
            clock = Clock.fixed(Instant.parse("2026-05-05T12:00:00Z"), ZoneOffset.UTC),
            nonceProvider = { _ -> "AAAA" },
        )

        val built = builder.build(envelope, deviceJwk)
        val payload = decodeJsonSegment(built.payloadB64)

        assertEquals(envelope.bic, payload.field("aud"))
        // 2026-05-05T12:00:00Z = 1777982400 epoch seconds.
        assertEquals(1777982400L, payload["iat"]?.jsonPrimitive?.longOrNull)
        assertEquals("AAAA", payload.field("nonce"))

        val embedded = payload["envelope"] as JsonObject
        // Spot-check the embedded envelope: full bank-side identity is preserved
        // even though the wallet UI never displays it.
        assertEquals("DEMODEAA", embedded.field("bic"))
        assertEquals("DE89370400440532013000", (embedded["payer"] as JsonObject).field("iban"))
        assertEquals("Mihai Test", (embedded["payer"] as JsonObject).field("holderName"))

        // `sub_jwk` is the public device key.
        val subJwk = payload["sub_jwk"] as JsonObject
        assertEquals("EC", subJwk.field("kty"))
    }

    @Test
    fun `signing input is the ASCII concatenation header then dot then payload`() {
        val builder = AuthorizationJwtBuilder(
            clock = Clock.fixed(Instant.parse("2026-05-05T12:00:00Z"), ZoneOffset.UTC),
            nonceProvider = { _ -> "AAAA" },
        )

        val built = builder.build(envelope, deviceJwk)

        val expected = "${built.headerB64}.${built.payloadB64}".toByteArray(Charsets.US_ASCII)
        assertArrayEquals(expected, built.signingInput)
    }

    @Test
    fun `nonce length is 16 bytes encoded base64url unpadded`() {
        var captured: Int? = null
        val provider = NonceProvider { byteLength ->
            captured = byteLength
            "x".repeat(22) // arbitrary; we only assert the byteLength asked for
        }

        AuthorizationJwtBuilder(nonceProvider = provider).build(envelope, deviceJwk)

        assertEquals(16, captured)
    }

    @Test
    fun `embeds the withdrawToWallet holderPub JWK verbatim in the envelope claim`() {
        // When the wallet signs a withdrawToWallet envelope it must include the
        // freshly-generated holderPub JWK so the bank backend can deep-equal-check
        // it against the /deliver request body. See specs/protocols/de-wallet-app-api.md
        // §withdrawToWallet step 8.
        val holderPub = Json.parseToJsonElement(
            """
            { "kty": "EC", "crv": "P-256",
              "x": "VlBcrYJCpwSEjlrAGT6JJzkn-yT7xZlBJgyP7lThM3M",
              "y": "PJSF99v0DKKlNxe2yPwbE2WzfwoR-q5MoY4o7tQO7w8" }
            """.trimIndent(),
        ).jsonObject

        val withdrawEnvelope = envelope.copy(
            type = OperationType.WITHDRAW_TO_WALLET,
            holderPubRequest = true,
            holderPub = holderPub,
        )

        val built = AuthorizationJwtBuilder(
            clock = Clock.fixed(Instant.parse("2026-05-05T12:00:00Z"), ZoneOffset.UTC),
            nonceProvider = { _ -> "AAAA" },
        ).build(withdrawEnvelope, deviceJwk)

        val payload = decodeJsonSegment(built.payloadB64)
        val embedded = payload["envelope"] as JsonObject
        val embeddedHolderPub = embedded["holderPub"] as JsonObject
        assertEquals(holderPub["x"], embeddedHolderPub["x"])
        assertEquals(holderPub["y"], embeddedHolderPub["y"])
        assertEquals("P-256", embeddedHolderPub.field("crv"))
    }

    @Test
    fun `omits holderPubRequest and holderPub from the envelope claim when null`() {
        // encodeDefaults=false in the builder's Json means null optional fields
        // do not pollute the signed payload. Bank backends pinned on payment /
        // top-up envelope shape would otherwise reject the JWT outright.
        val built = AuthorizationJwtBuilder(
            clock = Clock.fixed(Instant.parse("2026-05-05T12:00:00Z"), ZoneOffset.UTC),
            nonceProvider = { _ -> "AAAA" },
        ).build(envelope, deviceJwk)

        val payload = decodeJsonSegment(built.payloadB64)
        val embedded = payload["envelope"] as JsonObject
        assertEquals(null, embedded["holderPub"])
        assertEquals(null, embedded["holderPubRequest"])
    }

    @Test
    fun `assemble appends base64url signature as the third JWS segment`() = runTest {
        val builder = AuthorizationJwtBuilder(
            clock = Clock.fixed(Instant.parse("2026-05-05T12:00:00Z"), ZoneOffset.UTC),
            nonceProvider = { _ -> "AAAA" },
        )
        val signer = Signer { input ->
            // Stub r||s — content does not matter, only the encoding round-trip.
            ByteArray(64) { (it + input.size).toByte() }
        }

        val jws = builder.signAndAssemble(envelope, deviceJwk, signer)
        val segments = jws.split(".")
        assertEquals(3, segments.size)
        assertNotNull(Base64.getUrlDecoder().decode(segments[0]))
        assertNotNull(Base64.getUrlDecoder().decode(segments[1]))
        // 64-byte signature → 86 base64url chars without padding.
        assertEquals(86, segments[2].length)
    }

    private fun JsonObject.field(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    private fun decodeJsonSegment(base64Url: String): JsonObject {
        val raw = Base64.getUrlDecoder().decode(base64Url).toString(Charsets.UTF_8)
        return Json.parseToJsonElement(raw).jsonObject
    }
}
