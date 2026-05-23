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
     * Inverse of [encodeP256]: parse an ASN.1 DER ECDSA signature into a
     * 64-byte raw `r || s` array per RFC 7518 §3.4. Used when the wallet
     * signs locally with `java.security.Signature("SHA256withECDSA")`
     * (returns DER) and needs the JWS form for the third compact-JWS
     * segment. P-256 only; the SEQUENCE body fits in single-byte DER
     * length form.
     */
    fun decodeP256(der: ByteArray): ByteArray {
        require(der.isNotEmpty() && der[0] == 0x30.toByte()) {
            "Expected ASN.1 SEQUENCE tag (0x30) at offset 0"
        }
        var i = 2 // skip SEQUENCE tag + single-byte length
        require(i < der.size && der[i] == 0x02.toByte()) { "Expected INTEGER tag for r" }
        val rLen = der[i + 1].toInt() and 0xff
        val rBytes = der.copyOfRange(i + 2, i + 2 + rLen)
        i += 2 + rLen
        require(i < der.size && der[i] == 0x02.toByte()) { "Expected INTEGER tag for s" }
        val sLen = der[i + 1].toInt() and 0xff
        val sBytes = der.copyOfRange(i + 2, i + 2 + sLen)
        return eu.europa.ec.delogic.jwt.JoseSignatureEncoding.encodeP256(rBytes, sBytes)
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
