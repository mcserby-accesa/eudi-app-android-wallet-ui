/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 */

package eu.europa.ec.delogic.jwt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JoseSignatureEncodingTest {

    @Test
    fun `pads short components with leading zeros`() {
        val r = byteArrayOf(0x01, 0x02)               // 2 bytes
        val s = byteArrayOf(0x0A, 0x0B, 0x0C)         // 3 bytes
        val out = JoseSignatureEncoding.encodeP256(r, s)
        assertEquals(64, out.size)
        // r lands in bytes 30..31, s in bytes 61..63 (left-padded).
        assertEquals(0x01.toByte(), out[30])
        assertEquals(0x02.toByte(), out[31])
        assertEquals(0x0A.toByte(), out[61])
        assertEquals(0x0B.toByte(), out[62])
        assertEquals(0x0C.toByte(), out[63])
        // Everything else is zero.
        for (i in 0 until 30) assertEquals(0.toByte(), out[i])
        for (i in 32 until 61) assertEquals(0.toByte(), out[i])
    }

    @Test
    fun `passes through 32-byte components unchanged`() {
        val r = ByteArray(32) { (it + 1).toByte() }
        val s = ByteArray(32) { (it + 100).toByte() }
        val out = JoseSignatureEncoding.encodeP256(r, s)
        assertArrayEquals(r, out.copyOfRange(0, 32))
        assertArrayEquals(s, out.copyOfRange(32, 64))
    }

    @Test
    fun `trims a single leading-zero sign byte`() {
        // Simulates a BigInteger.toByteArray() output where the high bit of the
        // 32-byte value is set, so a 0x00 sign byte is prepended → 33 bytes.
        val r33 = byteArrayOf(0x00) + ByteArray(32) { 0xFF.toByte() }
        val s = ByteArray(32) { 0x01.toByte() }
        val out = JoseSignatureEncoding.encodeP256(r33, s)
        assertEquals(64, out.size)
        for (i in 0 until 32) assertEquals(0xFF.toByte(), out[i])
        for (i in 32 until 64) assertEquals(0x01.toByte(), out[i])
    }

    @Test
    fun `rejects components longer than 32 bytes after trimming`() {
        // A 33-byte value that does not start with 0 cannot be a P-256 component.
        val tooLong = ByteArray(33) { 0xFF.toByte() }
        val s = ByteArray(32)
        assertThrows(IllegalArgumentException::class.java) {
            JoseSignatureEncoding.encodeP256(tooLong, s)
        }
    }

    @Test
    fun `accepts empty component as zero`() {
        val empty = ByteArray(0)
        val out = JoseSignatureEncoding.encodeP256(empty, empty)
        assertEquals(64, out.size)
        for (b in out) assertEquals(0.toByte(), b)
    }
}
