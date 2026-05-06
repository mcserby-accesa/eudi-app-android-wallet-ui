/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 */

package eu.europa.ec.delogic.jwt

/**
 * JWS ES256 signatures are 64 bytes — the `r` and `s` components of an ECDSA
 * P-256 signature, each fixed-width 32 bytes, big-endian, concatenated.
 *
 * The Multipaz `EcSignature` exposes raw `r` and `s` bytes that may be
 * shorter or longer than 32 bytes (e.g. shorter when leading zeros are
 * trimmed; one byte longer when a positive value's MSB would otherwise be
 * interpreted as a sign bit). This helper normalises to the JWS form.
 *
 * RFC 7515 §A.3.1 specifies the encoding; RFC 7518 §3.4 mandates the
 * fixed-length-per-component format for ECDSA.
 */
object JoseSignatureEncoding {

    private const val P256_COMPONENT_LENGTH = 32

    fun encodeP256(r: ByteArray, s: ByteArray): ByteArray {
        val out = ByteArray(P256_COMPONENT_LENGTH * 2)
        copyFixedWidth(r, out, 0)
        copyFixedWidth(s, out, P256_COMPONENT_LENGTH)
        return out
    }

    /**
     * Copies [source] into [dest] starting at [destOffset], aligning to
     * [P256_COMPONENT_LENGTH] bytes big-endian. Trims any leading zero byte
     * and rejects values that exceed the component length.
     */
    private fun copyFixedWidth(source: ByteArray, dest: ByteArray, destOffset: Int) {
        var input = source
        // Trim a leading zero byte (BigInteger sign-byte padding).
        if (input.size == P256_COMPONENT_LENGTH + 1 && input[0] == 0.toByte()) {
            input = input.copyOfRange(1, input.size)
        }
        require(input.size <= P256_COMPONENT_LENGTH) {
            "Signature component is ${input.size} bytes; max ${P256_COMPONENT_LENGTH}"
        }
        // Left-pad with zeros so MSB-aligned values land at the end of the slot.
        val pad = P256_COMPONENT_LENGTH - input.size
        input.copyInto(dest, destinationOffset = destOffset + pad)
    }
}
