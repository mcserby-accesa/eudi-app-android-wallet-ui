/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * Forward + backward compatibility tests for the M4a /deliver response
 * shape. Pre-M4b/c banks return no reconciliationUrl; post-M4b/c banks
 * return it as a top-level string. Wallet's parser must tolerate both
 * so the wallet can ship before the services-side cluster update lands.
 */

package eu.europa.ec.delogic.delivery

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WithdrawDeliveryClientResponseTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    @Test
    fun `parses M4a-shape response (no reconciliationUrl) — backward compat`() {
        val raw = """
            {
              "tokens": [
                { "serial": "uuid-1", "amount": 5000, "currency": "EUR", "jws": "h.p.s" }
              ],
              "newOnlineBalance": 100000,
              "newOfflineBalance": 50000,
              "eventId": "evt-1"
            }
        """.trimIndent()
        val parsed = json.decodeFromString(DeliverResponse.serializer(), raw)

        assertEquals(1, parsed.tokens.size)
        assertEquals("uuid-1", parsed.tokens[0].serial)
        assertNull(parsed.reconciliationUrl)
        assertEquals(100000L, parsed.newOnlineBalance)
        assertEquals(50000L, parsed.newOfflineBalance)
    }

    @Test
    fun `parses M4b-shape response (with reconciliationUrl) — forward compat`() {
        val raw = """
            {
              "tokens": [
                { "serial": "uuid-1", "amount": 5000, "currency": "EUR", "jws": "h.p.s" }
              ],
              "reconciliationUrl": "https://bank-a.fips.accesa.tech",
              "newOnlineBalance": 100000,
              "newOfflineBalance": 50000,
              "eventId": "evt-1"
            }
        """.trimIndent()
        val parsed = json.decodeFromString(DeliverResponse.serializer(), raw)

        assertEquals("https://bank-a.fips.accesa.tech", parsed.reconciliationUrl)
    }
}
