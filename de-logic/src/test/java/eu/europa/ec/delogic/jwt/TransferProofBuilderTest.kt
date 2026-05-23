/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 */

package eu.europa.ec.delogic.jwt

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

class TransferProofBuilderTest {

    private val fromHolderPub: JsonObject = buildJsonObject {
        put("kty", "EC")
        put("crv", "P-256")
        put("x", "f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU")
        put("y", "x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0")
    }

    private val toHolderPub: JsonObject = buildJsonObject {
        put("kty", "EC")
        put("crv", "P-256")
        put("x", "VlBcrYJCpwSEjlrAGT6JJzkn-yT7xZlBJgyP7lThM3M")
        put("y", "PJSF99v0DKKlNxe2yPwbE2WzfwoR-q5MoY4o7tQO7w8")
    }

    private val ts = Instant.parse("2026-05-23T12:00:00Z")
    private val clock = Clock.fixed(ts, ZoneOffset.UTC)

    @Test
    fun `header carries alg=ES256 and typ=de-transferproof+jwt`() {
        val builder = TransferProofBuilder(clock = clock, nonceProvider = { _ -> "AAAA" })

        val built = builder.build(
            tokenSerial = "uuid-1",
            fromHolderPub = fromHolderPub,
            toHolderPub = toHolderPub,
            amount = 5000,
            currency = "EUR",
            senderTxCounter = 7,
        )
        val header = decodeJsonSegment(built.headerB64)
        assertEquals("ES256", header.field("alg"))
        assertEquals("de-transferproof+jwt", header.field("typ"))
    }

    @Test
    fun `payload carries the ADR 0011 fields verbatim`() {
        val builder = TransferProofBuilder(clock = clock, nonceProvider = { _ -> "deadbeef" })

        val built = builder.build(
            tokenSerial = "uuid-serial-42",
            fromHolderPub = fromHolderPub,
            toHolderPub = toHolderPub,
            amount = 5000,
            currency = "EUR",
            senderTxCounter = 13,
        )
        val payload = decodeJsonSegment(built.payloadB64)

        assertEquals(1L, payload["version"]?.jsonPrimitive?.longOrNull)
        assertEquals("uuid-serial-42", payload.field("tokenSerial"))
        assertEquals(5000L, payload["amount"]?.jsonPrimitive?.longOrNull)
        assertEquals("EUR", payload.field("currency"))
        assertEquals(13L, payload["senderTxCounter"]?.jsonPrimitive?.longOrNull)
        assertEquals("deadbeef", payload.field("nonce"))

        assertEquals(fromHolderPub["x"], (payload["fromHolderPub"] as JsonObject)["x"])
        assertEquals(toHolderPub["x"], (payload["toHolderPub"] as JsonObject)["x"])
    }

    @Test
    fun `ts and expiry are now and now + 5 minutes`() {
        val builder = TransferProofBuilder(clock = clock, nonceProvider = { _ -> "AAAA" })

        val built = builder.build(
            tokenSerial = "uuid-1",
            fromHolderPub = fromHolderPub,
            toHolderPub = toHolderPub,
            amount = 1000,
            currency = "EUR",
            senderTxCounter = 0,
        )
        val payload = decodeJsonSegment(built.payloadB64)

        assertEquals("2026-05-23T12:00:00Z", payload.field("ts"))
        assertEquals("2026-05-23T12:05:00Z", payload.field("expiry"))
        assertEquals(Instant.parse("2026-05-23T12:00:00Z"), built.ts)
        assertEquals(Instant.parse("2026-05-23T12:05:00Z"), built.expiry)
    }

    @Test
    fun `signing input is ASCII header dot payload`() {
        val builder = TransferProofBuilder(clock = clock, nonceProvider = { _ -> "AAAA" })

        val built = builder.build(
            tokenSerial = "uuid-1",
            fromHolderPub = fromHolderPub,
            toHolderPub = toHolderPub,
            amount = 1000,
            currency = "EUR",
            senderTxCounter = 0,
        )

        val expected = "${built.headerB64}.${built.payloadB64}".toByteArray(Charsets.US_ASCII)
        assertArrayEquals(expected, built.signingInput)
    }

    @Test
    fun `nonce length is 16 bytes`() {
        var captured: Int? = null
        val provider = NonceProvider { byteLength ->
            captured = byteLength
            "x".repeat(22)
        }
        TransferProofBuilder(clock = clock, nonceProvider = provider).build(
            tokenSerial = "uuid-1",
            fromHolderPub = fromHolderPub,
            toHolderPub = toHolderPub,
            amount = 1000,
            currency = "EUR",
            senderTxCounter = 0,
        )
        assertEquals(16, captured)
    }

    @Test
    fun `assemble appends base64url signature as the third segment`() = runTest {
        val builder = TransferProofBuilder(clock = clock, nonceProvider = { _ -> "AAAA" })
        val signer = Signer { _ -> ByteArray(64) { 0x42 } }

        val jws = builder.signAndAssemble(
            tokenSerial = "uuid-1",
            fromHolderPub = fromHolderPub,
            toHolderPub = toHolderPub,
            amount = 1000,
            currency = "EUR",
            senderTxCounter = 0,
            signer = signer,
        )
        val segments = jws.split(".")
        assertEquals(3, segments.size)
        assertNotNull(Base64.getUrlDecoder().decode(segments[0]))
        assertNotNull(Base64.getUrlDecoder().decode(segments[1]))
        assertEquals(86, segments[2].length)
    }

    private fun JsonObject.field(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    private fun decodeJsonSegment(b64u: String): JsonObject {
        val raw = Base64.getUrlDecoder().decode(b64u).toString(Charsets.UTF_8)
        return Json.parseToJsonElement(raw).jsonObject
    }
}
