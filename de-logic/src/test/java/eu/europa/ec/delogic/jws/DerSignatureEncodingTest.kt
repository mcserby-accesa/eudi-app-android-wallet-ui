/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 */

package eu.europa.ec.delogic.jws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class DerSignatureEncodingTest {

    @Test
    fun `encodes a typical 64-byte JWS signature into well-formed DER`() {
        val r = BigInteger("01" + "00".repeat(31), 16)
        val s = BigInteger("02" + "00".repeat(31), 16)
        val raw = leftPad(r, 32) + leftPad(s, 32)
        val der = DerSignatureEncoding.encodeP256(raw)

        // Outer SEQUENCE
        assertEquals(0x30.toByte(), der[0])
        // 2-byte INTEGER (no sign-byte padding because MSB is 0x01 / 0x02).
        assertEquals(0x02.toByte(), der[2])
        assertEquals(32, der[3].toInt())
    }

    @Test
    fun `prefixes a 0x00 sign byte when r or s has the high bit set`() {
        // r with MSB set — DER must prepend 0x00 to avoid being interpreted
        // as a negative two's-complement integer.
        val r = BigInteger("ff" + "00".repeat(31), 16)
        val s = BigInteger("01" + "00".repeat(31), 16)
        val raw = leftPad(r, 32) + leftPad(s, 32)
        val der = DerSignatureEncoding.encodeP256(raw)

        // After the outer SEQUENCE header (0x30, len), r INTEGER tag + len
        // + 33 payload bytes (1 sign byte + 32 magnitude bytes).
        assertEquals(0x02.toByte(), der[2])
        assertEquals(33, der[3].toInt())
        assertEquals(0x00.toByte(), der[4])
        // r magnitude follows at offset 5..36
        assertEquals(0xff.toByte(), der[5])
    }

    @Test
    fun `rejects signatures that are not exactly 64 bytes`() {
        runCatching { DerSignatureEncoding.encodeP256(ByteArray(63)) }
            .onFailure { assertTrue(it is IllegalArgumentException) }
            .onSuccess { error("Expected IllegalArgumentException") }
    }

    private fun leftPad(value: BigInteger, width: Int): ByteArray {
        val raw = value.toByteArray()
        val trimmed = if (raw.size == width + 1 && raw[0] == 0.toByte()) {
            raw.copyOfRange(1, raw.size)
        } else {
            raw
        }
        val out = ByteArray(width)
        trimmed.copyInto(out, destinationOffset = width - trimmed.size)
        return out
    }
}
