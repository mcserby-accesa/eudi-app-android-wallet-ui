/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 */

package eu.europa.ec.destorage

import eu.europa.ec.businesslogic.controller.storage.PrefsController
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class SimulatedSecureElementImplTest {

    @Test
    fun `generateHolderKey returns an EC P-256 JWK with 32-byte x and y`() = runTest {
        val se = newSe()
        val handle = se.generateHolderKey()

        val jwk = handle.publicJwk
        assertEquals("EC", jwk["kty"]?.jsonPrimitive?.content)
        assertEquals("P-256", jwk["crv"]?.jsonPrimitive?.content)
        val x = jwk["x"]?.jsonPrimitive?.content!!
        val y = jwk["y"]?.jsonPrimitive?.content!!
        // base64url unpadded of 32 bytes => 43 chars (ceil(32 * 4 / 3) trimmed of '=').
        assertEquals(43, x.length)
        assertEquals(43, y.length)
        // The decoded coordinates must be exactly 32 bytes per RFC 7518 §6.2.
        assertEquals(32, Base64.getUrlDecoder().decode(x).size)
        assertEquals(32, Base64.getUrlDecoder().decode(y).size)
    }

    @Test
    fun `each generateHolderKey returns a distinct alias and a different key`() = runTest {
        val se = newSe()
        val a = se.generateHolderKey()
        val b = se.generateHolderKey()

        assertNotEquals(a.alias, b.alias)
        // EC public keys are essentially impossible to collide; if both `x`
        // values match we have either an RNG fault or a persistence bug.
        assertNotEquals(a.publicJwk["x"], b.publicJwk["x"])
    }

    @Test
    fun `txCounter increments monotonically across keygen and storeTokens`() = runTest {
        val se = newSe()
        assertEquals(0L, se.txCounter())

        val handle = se.generateHolderKey()
        assertEquals(1L, se.txCounter())

        val r1 = se.storeTokens(handle, listOf(token("S1", 500)))
        assertTrue(r1 is StoreResult.Ok)
        assertEquals(2L, se.txCounter())

        val r2 = se.storeTokens(handle, listOf(token("S2", 1000)))
        assertTrue(r2 is StoreResult.Ok)
        assertEquals(3L, se.txCounter())
    }

    @Test
    fun `storeTokens accumulates balance and dedups by serial`() = runTest {
        val se = newSe()
        val handle = se.generateHolderKey()

        val first = se.storeTokens(
            handle,
            tokens = listOf(token("S1", 500), token("S2", 1000)),
        )
        assertEquals(StoreResult.Ok(storedCount = 2, newBalance = 1500, newTxCounter = 2L), first)
        assertEquals(1500L, se.offlineBalance())

        // Re-store the same S1 + a new S3 — dedup keeps S1's persisted row and
        // only S3 counts as newly stored.
        val second = se.storeTokens(
            handle,
            tokens = listOf(token("S1", 500), token("S3", 2000)),
        )
        assertEquals(
            StoreResult.Ok(storedCount = 1, newBalance = 3500, newTxCounter = 3L),
            second,
        )
        assertEquals(3500L, se.offlineBalance())
        assertEquals(3, se.listHeldTokens().size)
    }

    @Test
    fun `storeTokens with an unknown holder alias is rejected`() = runTest {
        val se = newSe()
        val fakeHandle = HolderKeyHandle(
            alias = "never-generated",
            publicJwk = kotlinx.serialization.json.buildJsonObject {
                put("kty", kotlinx.serialization.json.JsonPrimitive("EC"))
            },
        )
        val result = se.storeTokens(fakeHandle, listOf(token("S1", 100)))
        assertTrue(result is StoreResult.Rejected)
        assertEquals("unknown_holder_alias", (result as StoreResult.Rejected).reason)
        // Failed call must not perturb counter or balance.
        assertEquals(0L, se.txCounter())
        assertEquals(0L, se.offlineBalance())
    }

    @Test
    fun `state persists across instances backed by the same PrefsController`() = runTest {
        val prefs = InMemoryPrefs()
        val first = SimulatedSecureElementImpl(prefs, clock = fixedClock(), aliasFactory = aliasFactory())
        val handle = first.generateHolderKey()
        first.storeTokens(handle, listOf(token("S1", 750)))

        val second = SimulatedSecureElementImpl(prefs, clock = fixedClock(), aliasFactory = aliasFactory())
        assertEquals(2L, second.txCounter())
        assertEquals(750L, second.offlineBalance())
        assertEquals(1, second.listHeldTokens().size)
    }

    @Test
    fun `reset wipes counter tokens and aliases`() = runTest {
        val prefs = InMemoryPrefs()
        val se = SimulatedSecureElementImpl(prefs, clock = fixedClock(), aliasFactory = aliasFactory())
        val handle = se.generateHolderKey()
        se.storeTokens(handle, listOf(token("S1", 100)))
        assertTrue(prefs.contains("de.se.tx_counter"))

        se.reset()

        assertEquals(0L, se.txCounter())
        assertEquals(0L, se.offlineBalance())
        assertEquals(0, se.listHeldTokens().size)
        // After reset the handle is unknown again — proves the alias map was cleared too.
        assertTrue(se.storeTokens(handle, listOf(token("S2", 100))) is StoreResult.Rejected)
    }

    // ---- Helpers ---------------------------------------------------------------

    private fun newSe(): SimulatedSecureElement =
        SimulatedSecureElementImpl(InMemoryPrefs(), clock = fixedClock(), aliasFactory = aliasFactory())

    private fun fixedClock(): Clock =
        Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC)

    private fun aliasFactory(): () -> String {
        val counter = AtomicInteger(0)
        return { "alias-${counter.incrementAndGet()}" }
    }

    private fun token(serial: String, amount: Long): OfflineTokenJws =
        OfflineTokenJws(
            serial = serial,
            amount = amount,
            currency = "EUR",
            jws = "stub.$serial.signature",
        )

    private class InMemoryPrefs : PrefsController {
        private val strings = mutableMapOf<String, String>()
        private val longs = mutableMapOf<String, Long>()

        override suspend fun contains(key: String): Boolean =
            key in strings || key in longs

        override suspend fun clear(key: String) {
            strings.remove(key)
            longs.remove(key)
        }

        override suspend fun clear() {
            strings.clear()
            longs.clear()
        }

        override suspend fun setString(key: String, value: String) { strings[key] = value }
        override suspend fun setLong(key: String, value: Long) { longs[key] = value }
        override suspend fun getString(key: String, defaultValue: String): String =
            strings[key] ?: defaultValue

        override suspend fun getLong(key: String, defaultValue: Long): Long =
            longs[key] ?: defaultValue

        override suspend fun setBool(key: String, value: Boolean) = error("not used")
        override suspend fun setInt(key: String, value: Int) = error("not used")
        override suspend fun getBool(key: String, defaultValue: Boolean): Boolean = error("not used")
        override suspend fun getInt(key: String, defaultValue: Int): Int = error("not used")
    }
}
