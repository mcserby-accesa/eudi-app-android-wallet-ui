/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 */

package eu.europa.ec.delogic.state

import eu.europa.ec.businesslogic.controller.storage.PrefsController
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletStateRepositoryTest {

    @Test
    fun `current returns null when no state has been persisted`() = runTest {
        val repo = WalletStateRepositoryImpl(InMemoryPrefs())
        assertNull(repo.current())
    }

    @Test
    fun `save round-trips a WalletState with all fields`() = runTest {
        val prefs = InMemoryPrefs()
        val repo = WalletStateRepositoryImpl(prefs)

        val state = WalletState(
            pidIssuerUrl = "https://pid.example",
            deviceKeyAlias = "alias-1",
            deviceKeyPublicJwk = """{"kty":"EC","crv":"P-256","x":"X","y":"Y"}""",
        )
        repo.save(state)

        assertEquals(state, repo.current())
    }

    @Test
    fun `save round-trips with optional fields absent — first-launch shape`() = runTest {
        val repo = WalletStateRepositoryImpl(InMemoryPrefs())
        val state = WalletState(pidIssuerUrl = "https://pid.example")
        repo.save(state)

        val read = repo.current()!!
        assertEquals("https://pid.example", read.pidIssuerUrl)
        assertNull(read.deviceKeyAlias)
        assertNull(read.deviceKeyPublicJwk)
    }

    @Test
    fun `clear removes a previously saved state`() = runTest {
        val prefs = InMemoryPrefs()
        val repo = WalletStateRepositoryImpl(prefs)
        repo.save(WalletState(pidIssuerUrl = "https://pid.example"))
        assertTrue(prefs.contains("de.walletState"))

        repo.clear()

        assertFalse(prefs.contains("de.walletState"))
        assertNull(repo.current())
    }

    @Test
    fun `current returns null when persisted payload is malformed`() = runTest {
        val prefs = InMemoryPrefs()
        prefs.setString("de.walletState", "{ this is not valid JSON")
        val repo = WalletStateRepositoryImpl(prefs)

        // Better to surface "no state" than crash on a corrupted prefs file —
        // the QR-config screen will simply re-run.
        assertNull(repo.current())
    }

    /**
     * Minimal in-memory [PrefsController] for unit tests. Only the methods this
     * repository uses are implemented; the rest throw to flag accidental use.
     */
    private class InMemoryPrefs : PrefsController {
        private val store = mutableMapOf<String, String>()

        override suspend fun contains(key: String): Boolean = store.containsKey(key)
        override suspend fun clear(key: String) { store.remove(key) }
        override suspend fun clear() { store.clear() }
        override suspend fun setString(key: String, value: String) { store[key] = value }
        override suspend fun getString(key: String, defaultValue: String): String =
            store[key] ?: defaultValue

        override suspend fun setLong(key: String, value: Long) = error("not used")
        override suspend fun setBool(key: String, value: Boolean) = error("not used")
        override suspend fun setInt(key: String, value: Int) = error("not used")
        override suspend fun getLong(key: String, defaultValue: Long): Long = error("not used")
        override suspend fun getBool(key: String, defaultValue: Boolean): Boolean = error("not used")
        override suspend fun getInt(key: String, defaultValue: Int): Int = error("not used")
    }
}
