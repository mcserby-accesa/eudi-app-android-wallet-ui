/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * Inverse of [eu.europa.ec.delogic.jwt.JoseSignatureEncoding]: convert a
 * raw r||s JWS ES256 signature back into the ASN.1 DER form that
 * `java.security.Signature("SHA256withECDSA")` expects.
 */

package eu.europa.ec.delogic.jws

import java.math.BigInteger

object DerSignatureEncoding {

    private const val P256_COMPONENT_LENGTH = 32

    /**
     * Convert a 64-byte JWS ES256 signature (RFC 7518 §3.4) into ASN.1
     * DER: `SEQUENCE { INTEGER r, INTEGER s }`. P-256 only; the body of
     * the SEQUENCE is always under 128 bytes, so we can use the
     * single-byte DER length form throughout.
     */
    fun encodeP256(jwsSignature: ByteArray): ByteArray {
        require(jwsSignature.size == P256_COMPONENT_LENGTH * 2) {
            "ES256 signature must be 64 bytes, got ${jwsSignature.size}"
        }
        val rBytes = unsignedIntegerBytes(
            BigInteger(1, jwsSignature.copyOfRange(0, P256_COMPONENT_LENGTH)),
        )
        val sBytes = unsignedIntegerBytes(
            BigInteger(1, jwsSignature.copyOfRange(P256_COMPONENT_LENGTH, P256_COMPONENT_LENGTH * 2)),
        )

        val rEncoded = byteArrayOf(0x02, rBytes.size.toByte()) + rBytes
        val sEncoded = byteArrayOf(0x02, sBytes.size.toByte()) + sBytes
        val body = rEncoded + sEncoded
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    /**
     * `BigInteger.toByteArray()` returns the minimal two's-complement
     * representation — exactly what ASN.1 DER's `INTEGER` form needs.
     * Specifically it prepends 0x00 whenever the MSB of the unsigned
     * value would otherwise look like a sign bit, which is the DER
     * requirement.
     */
    private fun unsignedIntegerBytes(value: BigInteger): ByteArray = value.toByteArray()
}
