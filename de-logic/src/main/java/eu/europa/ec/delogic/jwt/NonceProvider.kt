/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 */

package eu.europa.ec.delogic.jwt

import java.security.SecureRandom
import java.util.Base64

fun interface NonceProvider {
    /** Returns `byteLength` cryptographically random bytes encoded as unpadded base64url. */
    fun nextBase64Url(byteLength: Int): String
}

class SecureRandomNonceProvider(
    private val random: SecureRandom = SecureRandom(),
) : NonceProvider {
    override fun nextBase64Url(byteLength: Int): String {
        require(byteLength > 0)
        val bytes = ByteArray(byteLength)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
