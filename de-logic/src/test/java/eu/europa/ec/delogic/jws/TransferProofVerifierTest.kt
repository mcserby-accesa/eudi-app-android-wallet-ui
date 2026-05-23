/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * End-to-end signature-verify tests for `de-transferproof+jwt`. A fresh
 * EC P-256 keypair is generated per test; we sign a TransferProof
 * payload with the private key, embed the matching JWK as
 * `fromHolderPub`, and check the verifier accepts it (and rejects
 * various tampered cases).
 */

package eu.europa.ec.delogic.jws

import eu.europa.ec.delogic.jwt.JoseSignatureEncoding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

class TransferProofVerifierTest {

    @Test
    fun `accepts a valid TransferProof signed by the embedded fromHolderPub`() {
        val sender = newKeyPair()
        val recipientJwk = newKeyPair().publicJwk()
        val jws = signTransferProof(sender, recipientJwk = recipientJwk)

        val verifier = TransferProofVerifierImpl(clock = fixedClock())
        val result = verifier.verify(jws)
        assertTrue("expected Ok but was $result", result is TransferProofVerifyResult.Ok)
        val payload = (result as TransferProofVerifyResult.Ok).payload
        assertEquals("uuid-1", payload.tokenSerial)
        assertEquals(5000L, payload.amount)
        assertEquals("EUR", payload.currency)
        assertEquals(7L, payload.senderTxCounter)
    }

    @Test
    fun `rejects when the signature was made by a different key`() {
        val realSender = newKeyPair()
        val attacker = newKeyPair()
        // Embed real sender's JWK as fromHolderPub but sign with attacker's key.
        val jws = signTransferProof(
            signingKey = attacker,
            embeddedFromJwk = realSender.publicJwk(),
            recipientJwk = newKeyPair().publicJwk(),
        )

        val verifier = TransferProofVerifierImpl(clock = fixedClock())
        val result = verifier.verify(jws)
        assertTrue(
            "expected SignatureInvalid but was $result",
            result is TransferProofVerifyResult.Failure.SignatureInvalid,
        )
    }

    @Test
    fun `rejects when the TransferProof has expired`() {
        val sender = newKeyPair()
        val jws = signTransferProof(
            signingKey = sender,
            embeddedFromJwk = sender.publicJwk(),
            recipientJwk = newKeyPair().publicJwk(),
            ts = "2026-05-23T12:00:00Z",
            expiry = "2026-05-23T12:05:00Z",
        )
        val verifier = TransferProofVerifierImpl(
            // 1 minute past expiry.
            clock = Clock.fixed(Instant.parse("2026-05-23T12:06:00Z"), ZoneOffset.UTC),
        )
        assertTrue(verifier.verify(jws) is TransferProofVerifyResult.Failure.Expired)
    }

    @Test
    fun `rejects unsupported alg in header`() {
        val sender = newKeyPair()
        val jws = signTransferProof(sender, headerAlg = "RS256")
        val verifier = TransferProofVerifierImpl(clock = fixedClock())
        assertTrue(verifier.verify(jws) is TransferProofVerifyResult.Failure.Malformed)
    }

    @Test
    fun `rejects unsupported typ in header`() {
        val sender = newKeyPair()
        val jws = signTransferProof(sender, headerTyp = "de-authz+jwt")
        val verifier = TransferProofVerifierImpl(clock = fixedClock())
        assertTrue(verifier.verify(jws) is TransferProofVerifyResult.Failure.Malformed)
    }

    @Test
    fun `rejects malformed compact JWS (segment count)`() {
        val verifier = TransferProofVerifierImpl(clock = fixedClock())
        assertTrue(
            verifier.verify("just.two-segments") is TransferProofVerifyResult.Failure.Malformed,
        )
    }

    @Test
    fun `payload retains both holderPub JWKs verbatim`() {
        val sender = newKeyPair()
        val recipientJwk = newKeyPair().publicJwk()
        val jws = signTransferProof(sender, recipientJwk = recipientJwk)

        val ok = TransferProofVerifierImpl(clock = fixedClock()).verify(jws)
                as TransferProofVerifyResult.Ok
        assertEquals(sender.publicJwk()["x"], ok.payload.fromHolderPub["x"])
        assertEquals(recipientJwk["x"], ok.payload.toHolderPub["x"])
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private fun fixedClock(): Clock =
        Clock.fixed(Instant.parse("2026-05-23T12:01:00Z"), ZoneOffset.UTC)

    private fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()

    private fun KeyPair.publicJwk(): JsonObject {
        val pub = public as ECPublicKey
        val x = leftPad(pub.w.affineX, 32)
        val y = leftPad(pub.w.affineY, 32)
        return buildJsonObject {
            put("kty", "EC")
            put("crv", "P-256")
            put("x", Base64.getUrlEncoder().withoutPadding().encodeToString(x))
            put("y", Base64.getUrlEncoder().withoutPadding().encodeToString(y))
        }
    }

    /**
     * Sign a TransferProof JWS. Defaults sign with [signingKey] and
     * embed [signingKey]'s own public JWK as fromHolderPub — the happy
     * path. Override [embeddedFromJwk] to test signature-vs-embedded-key
     * mismatches.
     */
    private fun signTransferProof(
        signingKey: KeyPair,
        embeddedFromJwk: JsonObject = signingKey.publicJwk(),
        recipientJwk: JsonObject = newKeyPair().publicJwk(),
        headerAlg: String = "ES256",
        headerTyp: String = "de-transferproof+jwt",
        ts: String = "2026-05-23T12:00:00Z",
        expiry: String = "2026-05-23T12:05:00Z",
    ): String {
        val header = buildJsonObject {
            put("alg", headerAlg)
            put("typ", headerTyp)
        }
        val payload = buildJsonObject {
            put("version", 1)
            put("tokenSerial", "uuid-1")
            put("fromHolderPub", embeddedFromJwk)
            put("toHolderPub", recipientJwk)
            put("amount", 5000L)
            put("currency", "EUR")
            put("ts", ts)
            put("expiry", expiry)
            put("nonce", "AAAA")
            put("senderTxCounter", 7L)
        }
        val headerB64 = encodeB64Url(header.toString())
        val payloadB64 = encodeB64Url(payload.toString())
        val signingInput = "$headerB64.$payloadB64".toByteArray(Charsets.US_ASCII)
        val der = Signature.getInstance("SHA256withECDSA").apply {
            initSign(signingKey.private)
            update(signingInput)
        }.sign()
        val (r, s) = derToRs(der)
        val rawSig = JoseSignatureEncoding.encodeP256(r = r, s = s)
        return "$headerB64.$payloadB64.${Base64.getUrlEncoder().withoutPadding().encodeToString(rawSig)}"
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

    private fun derToRs(der: ByteArray): Pair<ByteArray, ByteArray> {
        require(der[0] == 0x30.toByte()) { "Expected DER SEQUENCE" }
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
}
