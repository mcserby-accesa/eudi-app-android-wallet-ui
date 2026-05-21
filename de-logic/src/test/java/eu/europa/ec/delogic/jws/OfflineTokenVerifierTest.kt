/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * End-to-end signature-verify tests. A pre-generated PKCS12 keystore
 * (`src/test/resources/workshop_test_issuer.p12`, generated once via
 * `keytool` — see this file's git history) provides the test issuer's
 * EC P-256 keypair. We sign a `de-offline+jwt` with that key and check
 * that the verifier accepts it when the trust anchor is the same cert,
 * and rejects it under various failure conditions.
 */

package eu.europa.ec.delogic.jws

import eu.europa.ec.delogic.jwt.JoseSignatureEncoding
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

class OfflineTokenVerifierTest {

    @Test
    fun `accepts a valid token signed by the trust anchor`() {
        val holderPub = newP256Jwk()
        val jws = signOfflineToken(payload = stockPayload(holderPub))

        val verifier = OfflineTokenVerifierImpl(
            trustStore = TrustStore(setOf(issuerCert)),
            clock = fixedClock(),
        )
        val result = verifier.verify(jws, expectedHolderPub = holderPub)
        assertTrue("expected Ok but was $result", result is OfflineTokenVerifyResult.Ok)
        val payload = (result as OfflineTokenVerifyResult.Ok).payload
        assertEquals("uuid-serial-1", payload.serial)
        assertEquals(5000L, payload.amount)
        assertEquals("EUR", payload.currency)
    }

    @Test
    fun `rejects when the trust anchor set is empty`() {
        val holderPub = newP256Jwk()
        val jws = signOfflineToken(payload = stockPayload(holderPub))

        val verifier = OfflineTokenVerifierImpl(
            trustStore = TrustStore(emptySet()),
            clock = fixedClock(),
        )
        val result = verifier.verify(jws, holderPub)
        assertEquals(
            "trust_anchor_unconfigured",
            (result as OfflineTokenVerifyResult.Failure.SignatureInvalid).reason,
        )
    }

    @Test
    fun `rejects when the embedded holderPub does not match expected`() {
        val mintedFor = newP256Jwk()
        val somebodyElsesHolderPub = newP256Jwk()
        val jws = signOfflineToken(payload = stockPayload(mintedFor))

        val verifier = OfflineTokenVerifierImpl(
            trustStore = TrustStore(setOf(issuerCert)),
            clock = fixedClock(),
        )
        assertTrue(
            verifier.verify(jws, somebodyElsesHolderPub)
                is OfflineTokenVerifyResult.Failure.HolderPubMismatch,
        )
    }

    @Test
    fun `rejects when the token has expired`() {
        val holderPub = newP256Jwk()
        val payload = buildJsonObject {
            put("serial", "uuid-expired")
            put("amount", 1000L)
            put("currency", "EUR")
            put("ncbBic", "NCBEUDE")
            put("holderPub", holderPub)
            put("issuedAt", "2026-01-01T00:00:00Z")
            put("expiry", "2026-02-01T00:00:00Z")
        }
        val jws = signOfflineToken(payload = payload)

        val verifier = OfflineTokenVerifierImpl(
            trustStore = TrustStore(setOf(issuerCert)),
            clock = Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"), ZoneOffset.UTC),
        )
        assertTrue(verifier.verify(jws, holderPub) is OfflineTokenVerifyResult.Failure.Expired)
    }

    @Test
    fun `rejects malformed JWS shape (segment count)`() {
        val verifier = OfflineTokenVerifierImpl(
            trustStore = TrustStore(setOf(issuerCert)),
            clock = fixedClock(),
        )
        val result = verifier.verify("not.a.real.jws.with.too.many.segments", newP256Jwk())
        assertTrue(result is OfflineTokenVerifyResult.Failure.Malformed)
    }

    @Test
    fun `rejects unsupported alg in header`() {
        val jws = signOfflineToken(
            payload = stockPayload(newP256Jwk()),
            headerAlg = "RS256",
        )
        val verifier = OfflineTokenVerifierImpl(
            trustStore = TrustStore(setOf(issuerCert)),
            clock = fixedClock(),
        )
        assertTrue(
            verifier.verify(jws, newP256Jwk()) is OfflineTokenVerifyResult.Failure.Malformed,
        )
    }

    @Test
    fun `rejects unsupported typ in header`() {
        val jws = signOfflineToken(
            payload = stockPayload(newP256Jwk()),
            headerTyp = "openid4vci-credential",
        )
        val verifier = OfflineTokenVerifierImpl(
            trustStore = TrustStore(setOf(issuerCert)),
            clock = fixedClock(),
        )
        assertTrue(
            verifier.verify(jws, newP256Jwk()) is OfflineTokenVerifyResult.Failure.Malformed,
        )
    }

    // ---- shared issuer keypair (loaded once) ------------------------------------

    companion object {
        private const val KEYSTORE_RESOURCE = "/workshop_test_issuer.p12"
        private const val KEYSTORE_PASS = "changeit"
        private const val KEY_ALIAS = "t"

        lateinit var issuerCert: X509Certificate
            private set
        lateinit var issuerPrivate: PrivateKey
            private set

        @BeforeClass
        @JvmStatic
        fun loadIssuer() {
            val stream = OfflineTokenVerifierTest::class.java.getResourceAsStream(KEYSTORE_RESOURCE)
                ?: error("test resource $KEYSTORE_RESOURCE missing")
            val ks = KeyStore.getInstance("PKCS12")
            stream.use { ks.load(it, KEYSTORE_PASS.toCharArray()) }
            issuerCert = ks.getCertificate(KEY_ALIAS) as X509Certificate
            issuerPrivate = ks.getKey(KEY_ALIAS, KEYSTORE_PASS.toCharArray()) as PrivateKey
        }
    }

    private fun fixedClock(): Clock =
        Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC)

    private fun newP256Jwk(): JsonObject {
        val kp = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val pub = kp.public as ECPublicKey
        val x = leftPad(pub.w.affineX, 32)
        val y = leftPad(pub.w.affineY, 32)
        return buildJsonObject {
            put("kty", "EC")
            put("crv", "P-256")
            put("x", Base64.getUrlEncoder().withoutPadding().encodeToString(x))
            put("y", Base64.getUrlEncoder().withoutPadding().encodeToString(y))
        }
    }

    private fun stockPayload(holderPub: JsonObject): JsonObject = buildJsonObject {
        put("serial", "uuid-serial-1")
        put("amount", 5000L)
        put("currency", "EUR")
        put("ncbBic", "NCBEUDE")
        put("holderPub", holderPub)
        put("issuedAt", "2026-05-01T00:00:00Z")
        put("expiry", "2031-05-01T00:00:00Z")
    }

    /**
     * Build a compact-serialised `de-offline+jwt` JWS using the bundled
     * test issuer's EC key (loaded by [loadIssuer]).
     */
    private fun signOfflineToken(
        payload: JsonObject,
        headerAlg: String = "ES256",
        headerTyp: String = "de-offline+jwt",
    ): String {
        val header = buildJsonObject {
            put("alg", headerAlg)
            put("typ", headerTyp)
            put("kid", "test-kid")
            put(
                "x5c",
                buildJsonArray {
                    add(Base64.getEncoder().encodeToString(issuerCert.encoded))
                },
            )
        }
        val headerB64 = encodeB64Url(header.toString())
        val payloadB64 = encodeB64Url(payload.toString())
        val signingInput = "$headerB64.$payloadB64".toByteArray(Charsets.US_ASCII)

        val der = Signature.getInstance("SHA256withECDSA").apply {
            initSign(issuerPrivate)
            update(signingInput)
        }.sign()
        val (r, s) = derToRs(der)
        val rawSig = JoseSignatureEncoding.encodeP256(r = r, s = s)
        val sigB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(rawSig)
        return "$headerB64.$payloadB64.$sigB64"
    }

    private fun encodeB64Url(s: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))

    private fun leftPad(value: BigInteger, width: Int): ByteArray {
        val raw = value.toByteArray()
        val trimmed = if (raw.size == width + 1 && raw[0] == 0.toByte()) {
            raw.copyOfRange(1, raw.size)
        } else raw
        val out = ByteArray(width)
        trimmed.copyInto(out, destinationOffset = width - trimmed.size)
        return out
    }

    /** Parse DER-encoded ECDSA signature into raw r and s components. */
    private fun derToRs(der: ByteArray): Pair<ByteArray, ByteArray> {
        require(der[0] == 0x30.toByte()) { "Expected DER SEQUENCE" }
        // The outer length may be one byte (<128) or two bytes; for ECDSA
        // P-256 signatures the SEQUENCE body is always < 128 bytes, so
        // single-byte length is safe.
        var i = 2
        require(der[i] == 0x02.toByte()) { "Expected INTEGER for r" }
        val rLen = der[i + 1].toInt() and 0xff
        val rBytes = der.copyOfRange(i + 2, i + 2 + rLen)
        i += 2 + rLen
        require(der[i] == 0x02.toByte()) { "Expected INTEGER for s" }
        val sLen = der[i + 1].toInt() and 0xff
        val sBytes = der.copyOfRange(i + 2, i + 2 + sLen)
        return rBytes to sBytes
    }

    private class TrustStore(private val anchors: Set<X509Certificate>) : NcbTrustStore {
        override fun trustAnchors(): Set<X509Certificate> = anchors
    }
}
