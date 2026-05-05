/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 */

package eu.europa.ec.delogic.jwt

/**
 * Signs the JWS signing input (`base64url(header) || "." || base64url(payload)`,
 * ASCII bytes) with the wallet's device key and returns the raw `r || s`
 * concatenated ECDSA signature bytes (the JWS-encoded form, not DER).
 *
 * PR1 ships a stub implementation for tests. The production implementation in
 * PR6 wires this to `org.multipaz.securearea.SecureArea.sign(...)` against the
 * PID document's StrongBox-bound key, gated by a biometric prompt.
 */
fun interface Signer {
    suspend fun sign(signingInput: ByteArray): ByteArray
}
