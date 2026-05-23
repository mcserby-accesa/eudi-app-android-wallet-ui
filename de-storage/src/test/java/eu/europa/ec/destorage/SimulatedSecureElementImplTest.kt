/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 */

package eu.europa.ec.destorage

import eu.europa.ec.businesslogic.controller.storage.PrefsController
import eu.europa.ec.delogic.delivery.SerialStatus
import eu.europa.ec.delogic.delivery.SerialStatusLookup
import eu.europa.ec.delogic.jws.OfflineTokenPayload
import eu.europa.ec.delogic.jws.OfflineTokenVerifier
import eu.europa.ec.delogic.jws.OfflineTokenVerifyResult
import eu.europa.ec.delogic.jws.TransferProofVerifier
import eu.europa.ec.delogic.jws.TransferProofVerifierImpl
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        assertEquals(43, x.length)
        assertEquals(43, y.length)
        assertEquals(32, Base64.getUrlDecoder().decode(x).size)
        assertEquals(32, Base64.getUrlDecoder().decode(y).size)
    }

    @Test
    fun `each generateHolderKey returns a distinct alias and a different key`() = runTest {
        val se = newSe()
        val a = se.generateHolderKey()
        val b = se.generateHolderKey()

        assertNotEquals(a.alias, b.alias)
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
    fun `storeTokens persists reconciliationUrl on each new row`() = runTest {
        val prefs = InMemoryPrefs()
        val se = SimulatedSecureElementImpl(
            prefs = prefs,
            verifier = AcceptingVerifier(),
            transferProofVerifier = TransferProofVerifierImpl(),
            serialStatusClient = SerialStatusLookup { _, _ -> SerialStatus.UNKNOWN },
            clock = fixedClock(),
            aliasFactory = aliasFactory(),
        )
        val handle = se.generateHolderKey()
        se.storeTokens(
            handle = handle,
            tokens = listOf(token("S1", 500)),
            reconciliationUrl = "https://bank-a.example",
        )
        // The persisted prefs row carries the URL; the listHeldTokens contract
        // doesn't expose it (workshop drill-in screen has no use for it), but
        // reconcilePending depends on it being present.
        val raw = prefs.getString("de.se.tokens", "")
        assertTrue(
            "expected reconciliationUrl in persisted row, was: $raw",
            raw.contains("\"reconciliationUrl\":\"https://bank-a.example\""),
        )
    }

    @Test
    fun `storeTokens with an unknown holder alias is rejected`() = runTest {
        val se = newSe()
        val fakeHandle = HolderKeyHandle(
            alias = "never-generated",
            publicJwk = buildJsonObject { put("kty", JsonPrimitive("EC")) },
        )
        val result = se.storeTokens(fakeHandle, listOf(token("S1", 100)))
        assertTrue(result is StoreResult.Rejected)
        assertEquals("unknown_holder_alias", (result as StoreResult.Rejected).reason)
        assertEquals(0L, se.txCounter())
        assertEquals(0L, se.offlineBalance())
    }

    @Test
    fun `state persists across instances backed by the same PrefsController`() = runTest {
        val prefs = InMemoryPrefs()
        val first = newSeWith(prefs)
        val handle = first.generateHolderKey()
        first.storeTokens(handle, listOf(token("S1", 750)))

        val second = newSeWith(prefs)
        assertEquals(2L, second.txCounter())
        assertEquals(750L, second.offlineBalance())
        assertEquals(1, second.listHeldTokens().size)
    }

    @Test
    fun `reset wipes counter tokens and aliases`() = runTest {
        val prefs = InMemoryPrefs()
        val se = newSeWith(prefs)
        val handle = se.generateHolderKey()
        se.storeTokens(handle, listOf(token("S1", 100)))
        assertTrue(prefs.contains("de.se.tx_counter"))

        se.reset()

        assertEquals(0L, se.txCounter())
        assertEquals(0L, se.offlineBalance())
        assertEquals(0, se.listHeldTokens().size)
        assertTrue(se.storeTokens(handle, listOf(token("S2", 100))) is StoreResult.Rejected)
    }

    @Test
    fun `holderPub mismatch from verifier triggers Rejected at SE boundary`() = runTest {
        val se = SimulatedSecureElementImpl(
            prefs = InMemoryPrefs(),
            verifier = object : OfflineTokenVerifier {
                override fun verify(compactJws: String, expectedHolderPub: JsonObject) =
                    OfflineTokenVerifyResult.Failure.HolderPubMismatch
            },
            transferProofVerifier = TransferProofVerifierImpl(),
            serialStatusClient = SerialStatusLookup { _, _ -> SerialStatus.UNKNOWN },
            clock = fixedClock(),
            aliasFactory = aliasFactory(),
        )
        val handle = se.generateHolderKey()

        val result = se.storeTokens(handle, listOf(token("S1", 500)))
        assertTrue(result is StoreResult.Rejected)
        assertEquals("holder_pub_mismatch", (result as StoreResult.Rejected).reason)
        assertEquals(1L, se.txCounter())
        assertEquals(0L, se.offlineBalance())
    }

    @Test
    fun `signature-invalid from verifier triggers Rejected token_signature_invalid`() = runTest {
        val se = SimulatedSecureElementImpl(
            prefs = InMemoryPrefs(),
            verifier = object : OfflineTokenVerifier {
                override fun verify(compactJws: String, expectedHolderPub: JsonObject) =
                    OfflineTokenVerifyResult.Failure.SignatureInvalid("chain")
            },
            transferProofVerifier = TransferProofVerifierImpl(),
            serialStatusClient = SerialStatusLookup { _, _ -> SerialStatus.UNKNOWN },
            clock = fixedClock(),
            aliasFactory = aliasFactory(),
        )
        val handle = se.generateHolderKey()
        val result = se.storeTokens(handle, listOf(token("S1", 500)))
        assertEquals("token_signature_invalid", (result as StoreResult.Rejected).reason)
    }

    // ─── M4b/c — signTransferProofs ──────────────────────────────────────────

    @Test
    fun `signTransferProofs marks tokens OUTGOING_PENDING and produces verifiable proofs`() = runTest {
        val se = newSe()
        val handle = se.generateHolderKey()
        se.storeTokens(handle, listOf(token("S1", 500), token("S2", 1000)))
        assertEquals(1500L, se.offlineBalance())

        val recipientJwk = freshEcJwk()
        val proofs = se.signTransferProofs(
            serials = listOf("S1"),
            recipientHolderPub = recipientJwk,
            currency = "EUR",
        )

        assertEquals(1, proofs.size)
        assertEquals("S1", proofs[0].tokenSerial)
        // OUTGOING token drops out of spendable balance.
        assertEquals(1000L, se.offlineBalance())
        val held = se.listHeldTokens().associateBy { it.serial }
        assertEquals(TokenState.OUTGOING_PENDING, held.getValue("S1").state)
        assertEquals(TokenState.LIVE, held.getValue("S2").state)
        // Proof is a real ES256 JWS verifiable against the token's holderPub.
        val verifyResult = TransferProofVerifierImpl(
            clock = Clock.fixed(Instant.parse("2026-06-01T00:01:00Z"), ZoneOffset.UTC),
        ).verify(proofs[0].jws)
        assertTrue(
            "expected Ok but was $verifyResult",
            verifyResult is eu.europa.ec.delogic.jws.TransferProofVerifyResult.Ok,
        )
    }

    @Test
    fun `signTransferProofs refuses to re-sign a token that is OUTGOING_PENDING`() = runTest {
        val se = newSe()
        val handle = se.generateHolderKey()
        se.storeTokens(handle, listOf(token("S1", 500)))
        val recipientJwk = freshEcJwk()
        se.signTransferProofs(listOf("S1"), recipientJwk, "EUR")

        try {
            se.signTransferProofs(listOf("S1"), recipientJwk, "EUR")
            error("expected ISE for double-sign of the same serial")
        } catch (e: IllegalStateException) {
            assertTrue(
                "msg=${e.message}",
                e.message?.startsWith("token_not_live:S1") == true,
            )
        }
    }

    // ─── M4b/c — buildSelfRedeem + commitRedeemed ─────────────────────────────

    @Test
    fun `buildSelfRedeem selects LIFO LIVE tokens summing to amount`() = runTest {
        val se = newSe()
        val handle = se.generateHolderKey()
        se.storeTokens(
            handle,
            listOf(token("A", 500), token("B", 1000), token("C", 500)),
        )
        val bundle = se.buildSelfRedeem(amount = 1000, currency = "EUR")
        assertTrue("bundle was null", bundle != null)
        assertEquals(1000L, bundle!!.totalAmount)
        // LIFO: last-stored fits first; storage order is A, B, C → "C" (500) +
        // one of A (500) covers 1000. The greedy LIFO walk picks C (most recent
        // storedAt) then A.
        val serials = bundle.tokens.map { it.serial }
        assertTrue("serials=$serials", serials.contains("C"))
    }

    @Test
    fun `buildSelfRedeem returns null when no exact-sum subset exists`() = runTest {
        val se = newSe()
        val handle = se.generateHolderKey()
        se.storeTokens(handle, listOf(token("S1", 500), token("S2", 1000)))
        // Asking for 700 cents: no combination of {500, 1000} sums to 700.
        assertNull(se.buildSelfRedeem(amount = 700, currency = "EUR"))
    }

    @Test
    fun `commitRedeemed removes the listed serials and bumps txCounter`() = runTest {
        val se = newSe()
        val handle = se.generateHolderKey()
        se.storeTokens(handle, listOf(token("S1", 500), token("S2", 1000)))
        val countBefore = se.txCounter()

        se.commitRedeemed(listOf("S1"))

        assertEquals(countBefore + 1, se.txCounter())
        assertEquals(1, se.listHeldTokens().size)
        assertEquals("S2", se.listHeldTokens().first().serial)
    }

    // ─── M4b/c — reconcilePending ─────────────────────────────────────────────

    @Test
    fun `reconcilePending restores OUTGOING_PENDING tokens when bank says UNSPENT`() = runTest {
        val lookupCalls = mutableListOf<Pair<String, String>>()
        val lookup = SerialStatusLookup { url, serial ->
            lookupCalls += url to serial
            SerialStatus.UNSPENT
        }
        // Build SE with a clock starting BEFORE the 5-min expiry so signing succeeds,
        // then later we advance time past expiry for the reconcile pass.
        val initialClock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC)
        val advancedClock = Clock.fixed(Instant.parse("2026-06-01T00:06:00Z"), ZoneOffset.UTC)
        val prefs = InMemoryPrefs()
        val signer = newSeWith(
            prefs = prefs,
            clock = initialClock,
            lookup = lookup,
        )
        val handle = signer.generateHolderKey()
        signer.storeTokens(
            handle = handle,
            tokens = listOf(token("S1", 500)),
            reconciliationUrl = "https://bank-a.example",
        )
        signer.signTransferProofs(
            serials = listOf("S1"),
            recipientHolderPub = freshEcJwk(),
            currency = "EUR",
        )
        // Re-open with advanced clock to simulate "next reconnect, 6 min later".
        val reconciler = newSeWith(prefs = prefs, clock = advancedClock, lookup = lookup)
        val result = reconciler.reconcilePending()

        assertEquals(listOf("S1"), result.restoredOutgoing)
        assertEquals(listOf("https://bank-a.example" to "S1"), lookupCalls)
        // Restored token is LIVE again, spendable balance back up.
        assertEquals(500L, reconciler.offlineBalance())
        val held = reconciler.listHeldTokens().first()
        assertEquals(TokenState.LIVE, held.state)
    }

    @Test
    fun `reconcilePending finalises OUTGOING_PENDING when bank says SPENT`() = runTest {
        val lookup = SerialStatusLookup { _, _ -> SerialStatus.SPENT }
        val initialClock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC)
        val advancedClock = Clock.fixed(Instant.parse("2026-06-01T00:06:00Z"), ZoneOffset.UTC)
        val prefs = InMemoryPrefs()
        val signer = newSeWith(prefs = prefs, clock = initialClock, lookup = lookup)
        val handle = signer.generateHolderKey()
        signer.storeTokens(
            handle = handle,
            tokens = listOf(token("S1", 500)),
            reconciliationUrl = "https://bank-a.example",
        )
        signer.signTransferProofs(listOf("S1"), freshEcJwk(), "EUR")

        val reconciler = newSeWith(prefs = prefs, clock = advancedClock, lookup = lookup)
        val result = reconciler.reconcilePending()

        assertEquals(listOf("S1"), result.finalisedOutgoing)
        // Recipient kept the token; we drop it from our spendable balance.
        assertEquals(0L, reconciler.offlineBalance())
    }

    @Test
    fun `reconcilePending leaves tokens alone on UNKNOWN`() = runTest {
        val lookup = SerialStatusLookup { _, _ -> SerialStatus.UNKNOWN }
        val initialClock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC)
        val advancedClock = Clock.fixed(Instant.parse("2026-06-01T00:06:00Z"), ZoneOffset.UTC)
        val prefs = InMemoryPrefs()
        val signer = newSeWith(prefs = prefs, clock = initialClock, lookup = lookup)
        val handle = signer.generateHolderKey()
        signer.storeTokens(
            handle = handle,
            tokens = listOf(token("S1", 500)),
            reconciliationUrl = "https://bank-a.example",
        )
        signer.signTransferProofs(listOf("S1"), freshEcJwk(), "EUR")

        val reconciler = newSeWith(prefs = prefs, clock = advancedClock, lookup = lookup)
        val result = reconciler.reconcilePending()

        assertTrue(result.restoredOutgoing.isEmpty())
        assertTrue(result.finalisedOutgoing.isEmpty())
        // Token stays OUTGOING_PENDING so the next reconnect retries.
        val held = reconciler.listHeldTokens().first()
        assertEquals(TokenState.OUTGOING_PENDING, held.state)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun newSe(): SimulatedSecureElement = newSeWith(InMemoryPrefs())

    private fun newSeWith(
        prefs: PrefsController = InMemoryPrefs(),
        clock: Clock = fixedClock(),
        verifier: OfflineTokenVerifier = AcceptingVerifier(),
        transferProofVerifier: TransferProofVerifier = TransferProofVerifierImpl(clock = clock),
        lookup: SerialStatusLookup = SerialStatusLookup { _, _ -> SerialStatus.UNKNOWN },
    ): SimulatedSecureElementImpl = SimulatedSecureElementImpl(
        prefs = prefs,
        verifier = verifier,
        transferProofVerifier = transferProofVerifier,
        serialStatusClient = lookup,
        clock = clock,
        aliasFactory = aliasFactory(),
    )

    private class AcceptingVerifier : OfflineTokenVerifier {
        override fun verify(
            compactJws: String,
            expectedHolderPub: JsonObject,
        ): OfflineTokenVerifyResult {
            val parts = compactJws.split(".")
            return OfflineTokenVerifyResult.Ok(
                OfflineTokenPayload(
                    serial = parts[1],
                    amount = parts[2].toLong(),
                    currency = parts[3],
                    ncbBic = "NCBEUDE",
                    holderPub = expectedHolderPub,
                    issuedAt = Instant.parse("2026-06-01T00:00:00Z"),
                    expiry = Instant.parse("2031-06-01T00:00:00Z"),
                ),
            )
        }
    }

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
            jws = "stub.$serial.$amount.EUR.signature",
        )

    private fun freshEcJwk(): JsonObject {
        val kp = java.security.KeyPairGenerator.getInstance("EC")
            .apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val pub = kp.public as java.security.interfaces.ECPublicKey
        val x = leftPad(pub.w.affineX, 32)
        val y = leftPad(pub.w.affineY, 32)
        return buildJsonObject {
            put("kty", "EC")
            put("crv", "P-256")
            put("x", Base64.getUrlEncoder().withoutPadding().encodeToString(x))
            put("y", Base64.getUrlEncoder().withoutPadding().encodeToString(y))
        }
    }

    private fun leftPad(value: java.math.BigInteger, width: Int): ByteArray {
        val raw = value.toByteArray()
        val trimmed = if (raw.size == width + 1 && raw[0] == 0.toByte()) {
            raw.copyOfRange(1, raw.size)
        } else raw
        val out = ByteArray(width)
        trimmed.copyInto(out, destinationOffset = width - trimmed.size)
        return out
    }

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
