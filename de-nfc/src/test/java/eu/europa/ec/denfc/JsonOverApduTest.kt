/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 */

package eu.europa.ec.denfc

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonOverApduTest {

    @Test
    fun `buildCommandApdu emits CLA INS 00 00 00 LcHi LcLo header`() {
        val payload = "hello".toByteArray()
        val apdu = JsonOverApdu.buildCommandApdu(INS_TRANSFER_OFFER, payload)
        assertEquals(CLA_DE_OFFLINE, apdu[0])
        assertEquals(INS_TRANSFER_OFFER, apdu[1])
        assertEquals(0x00.toByte(), apdu[2])
        assertEquals(0x00.toByte(), apdu[3])
        assertEquals(0x00.toByte(), apdu[4])  // extended-length marker
        assertEquals(0x00.toByte(), apdu[5])
        assertEquals(0x05.toByte(), apdu[6])  // LcLo
        assertArrayEquals(payload, apdu.copyOfRange(7, apdu.size))
    }

    @Test
    fun `parseCommandApdu round-trips the body and INS`() {
        val body = "{\"x\":1}".toByteArray()
        val apdu = JsonOverApdu.buildCommandApdu(INS_TRANSFER_COMMIT, body)
        val parsed = JsonOverApdu.parseCommandApdu(apdu)
        assertTrue(parsed != null)
        assertEquals(INS_TRANSFER_COMMIT, parsed!!.ins)
        assertArrayEquals(body, parsed.data)
    }

    @Test
    fun `parseCommandApdu rejects non-extended-length`() {
        // CLA INS P1 P2 Lc <data> — standard form, not what our codec produces
        val apdu = byteArrayOf(CLA_DE_OFFLINE, INS_TRANSFER_OFFER, 0x00, 0x00, 0x05) + "hello".toByteArray()
        assertNull(JsonOverApdu.parseCommandApdu(apdu))
    }

    @Test
    fun `parseCommandApdu rejects wrong CLA`() {
        val body = "x".toByteArray()
        val good = JsonOverApdu.buildCommandApdu(INS_TRANSFER_OFFER, body)
        val tampered = good.copyOf().apply { this[0] = 0x00 }
        assertNull(JsonOverApdu.parseCommandApdu(tampered))
    }

    @Test
    fun `parseCommandApdu rejects truncated body`() {
        val good = JsonOverApdu.buildCommandApdu(INS_TRANSFER_OFFER, "hello".toByteArray())
        val truncated = good.copyOfRange(0, good.size - 2)
        assertNull(JsonOverApdu.parseCommandApdu(truncated))
    }

    @Test
    fun `encodeMessage + decodeMessage round-trip a TransferOffer`() {
        val original = TransferOffer(
            amount = 5000,
            currency = "EUR",
            tokens = listOf(
                TokenOfferRow("uuid-1", 5000, "https://bank-a.example"),
            ),
            description = "Receive €50 via NFC",
        )
        val bytes = JsonOverApdu.encodeMessage(original)
        val decoded: TransferOffer = JsonOverApdu.decodeMessage(bytes)
        assertEquals(original, decoded)
    }

    @Test
    fun `encodeMessage + decodeMessage round-trip a TransferAck`() {
        val jwk: JsonObject = buildJsonObject {
            put("kty", "EC")
            put("crv", "P-256")
            put("x", "abc")
            put("y", "def")
        }
        val original = TransferAck(recipientHolderPub = jwk)
        val bytes = JsonOverApdu.encodeMessage(original)
        val decoded: TransferAck = JsonOverApdu.decodeMessage(bytes)
        assertEquals(original, decoded)
        assertEquals("EC", decoded.recipientHolderPub["kty"]?.toString()?.trim('"'))
    }

    @Test
    fun `encodeMessage + decodeMessage round-trip a TransferCommit`() {
        val original = TransferCommit(
            tokens = listOf(
                TokenCommitRow("uuid-1", 5000, "EUR", "header.payload.sig"),
            ),
            transferProofs = listOf(
                TransferProofCommitRow("uuid-1", "proof.header.proof.payload.proof.sig"),
            ),
        )
        val bytes = JsonOverApdu.encodeMessage(original)
        val decoded: TransferCommit = JsonOverApdu.decodeMessage(bytes)
        assertEquals(original, decoded)
    }

    @Test
    fun `isSelectAid recognises the workshop AID`() {
        assertTrue(isSelectAid(buildSelectAidApdu()))
    }

    @Test
    fun `isSelectAid rejects a different AID`() {
        val other = byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x00, 0x03)
        val apdu = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, other.size.toByte()) + other
        assertTrue(!isSelectAid(apdu))
    }
}
