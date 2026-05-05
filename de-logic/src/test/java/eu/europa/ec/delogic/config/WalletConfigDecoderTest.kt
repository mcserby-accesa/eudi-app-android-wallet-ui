/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 */

package eu.europa.ec.delogic.config

import org.junit.Assert.assertEquals
import org.junit.Test

class WalletConfigDecoderTest {

    private val decoder = WalletConfigDecoder()

    @Test
    fun `accepts a JSON payload from a workshop QR`() {
        val r = decoder.decode("""{"pidIssuerUrl":"https://pid.example/issuer"}""")
        assertEquals(WalletConfigDecoder.Result.Success("https://pid.example/issuer"), r)
    }

    @Test
    fun `accepts a bare URL when typed manually`() {
        val r = decoder.decode("https://pid.example/issuer")
        assertEquals(WalletConfigDecoder.Result.Success("https://pid.example/issuer"), r)
    }

    @Test
    fun `accepts http for local emulator routing`() {
        // Workshop / local-dev pid-issuer runs on plain HTTP at the host:
        //   http://10.0.2.2:8092 from an emulator. We must not reject this.
        val r = decoder.decode("http://10.0.2.2:8092")
        assertEquals(WalletConfigDecoder.Result.Success("http://10.0.2.2:8092"), r)
    }

    @Test
    fun `strips a single trailing slash`() {
        val r = decoder.decode("https://pid.example/issuer/")
        assertEquals(WalletConfigDecoder.Result.Success("https://pid.example/issuer"), r)
    }

    @Test
    fun `trims surrounding whitespace before parsing`() {
        val r = decoder.decode("   https://pid.example  ")
        assertEquals(WalletConfigDecoder.Result.Success("https://pid.example"), r)
    }

    @Test
    fun `tolerates unknown JSON fields`() {
        val r = decoder.decode(
            """{"pidIssuerUrl":"https://pid.example","alias":"workshop-1"}""",
        )
        assertEquals(WalletConfigDecoder.Result.Success("https://pid.example"), r)
    }

    @Test
    fun `rejects empty input`() {
        val r = decoder.decode("")
        assertEquals(WalletConfigDecoder.Result.Failure(WalletConfigDecoder.ErrorCode.EMPTY), r)
    }

    @Test
    fun `rejects whitespace-only input as empty`() {
        val r = decoder.decode("   \t  ")
        assertEquals(WalletConfigDecoder.Result.Failure(WalletConfigDecoder.ErrorCode.EMPTY), r)
    }

    @Test
    fun `rejects malformed JSON with the right error code`() {
        val r = decoder.decode("""{ "pidIssuerUrl": broken""")
        assertEquals(
            WalletConfigDecoder.Result.Failure(WalletConfigDecoder.ErrorCode.MALFORMED_JSON),
            r,
        )
    }

    @Test
    fun `rejects JSON that lacks pidIssuerUrl`() {
        val r = decoder.decode("""{"unrelated":"value"}""")
        assertEquals(
            WalletConfigDecoder.Result.Failure(WalletConfigDecoder.ErrorCode.MALFORMED_JSON),
            r,
        )
    }

    @Test
    fun `rejects URLs with non-http schemes`() {
        val r = decoder.decode("ftp://pid.example")
        assertEquals(
            WalletConfigDecoder.Result.Failure(WalletConfigDecoder.ErrorCode.INVALID_URL),
            r,
        )
    }

    @Test
    fun `rejects garbage text`() {
        val r = decoder.decode("definitely not a url")
        assertEquals(
            WalletConfigDecoder.Result.Failure(WalletConfigDecoder.ErrorCode.INVALID_URL),
            r,
        )
    }

    @Test
    fun `rejects URL missing host`() {
        val r = decoder.decode("https:///path-only")
        assertEquals(
            WalletConfigDecoder.Result.Failure(WalletConfigDecoder.ErrorCode.INVALID_URL),
            r,
        )
    }
}
