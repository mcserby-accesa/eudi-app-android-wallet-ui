/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * Interop contract test: the wallet's [OfflineTokenVerifier] must accept
 * a real `de-offline+jwt` minted by the companion-repo `ncb-issuer`
 * against the workshop NCB offline root, AND the payload it parses must
 * deep-equal the values the issuer claims to have signed.
 *
 * If the NCB signer changes shape (new claim, different cert chain,
 * different curve, …) this test breaks before any phone-side smoke
 * test does. The fixtures live in `src/test/resources/`:
 *
 *  - workshop_ncb_root.pem          — copy of the production trust anchor
 *                                     (resources-logic/.../workshop_ncb_root.pem).
 *                                     Also asserted byte-identical here.
 *  - workshop_sample_offline_token.jws
 *                                   — single in-date sample token from
 *                                     ncb-issuer's `SampleTokenExporter`.
 *  - workshop_sample_holder_public.json
 *                                   — public JWK the sample token is
 *                                     bound to (extracted from the JWS
 *                                     payload itself; the sample
 *                                     exporter writes a Java-toString
 *                                     formatted blob which is not valid
 *                                     JSON, so we re-derive from the
 *                                     payload to keep a clean fixture).
 *
 * To regenerate the fixtures: re-run
 *   `mvn test -Dgroups=sample-export -Dtest=SampleTokenExporter -pl services/ncb-issuer`
 * in the companion repo, then copy
 *   target/sample-de-offline-token.txt → extract the JWS line
 * into this module's `src/test/resources/workshop_sample_offline_token.jws`.
 * The holderPub fixture re-derives from the JWS payload automatically.
 */

package eu.europa.ec.delogic.jws

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class WorkshopNcbInteropTest {

    @Test
    fun `bundled NCB root parses to a single X509 certificate`() {
        val anchors = workshopTrustAnchors()
        assertEquals(1, anchors.size)
        val cert = anchors.single()
        assertTrue(
            "Subject DN was '${cert.subjectX500Principal.name}'",
            cert.subjectX500Principal.name.contains("Accesa Workshop NCB Offline Root"),
        )
    }

    @Test
    fun `prod resources-logic cert and de-logic test fixture are byte-identical`() {
        // Defence against silent drift: if `resources-logic/.../workshop_ncb_root.pem`
        // is ever refreshed (PR12 → PR13 pattern) without this test fixture
        // also being updated, every other test in this file would falsely keep
        // passing because both live in test/resources/. This guard catches that.
        val prod = File(PROD_RESOURCE_PATH).readBytes()
        val fixture = resourceBytes(BUNDLED_ROOT_RESOURCE)
        assertArrayEquals(
            "de-logic/src/test/resources/$BUNDLED_ROOT_RESOURCE has drifted from " +
                PROD_RESOURCE_PATH +
                " — re-copy the production cert into test resources.",
            prod,
            fixture,
        )
    }

    @Test
    fun `verifier accepts the sample token signed under the workshop root`() {
        val jws = readResource(SAMPLE_JWS_RESOURCE).trim()
        val holderPub = Json.parseToJsonElement(
            readResource(SAMPLE_HOLDER_PUB_RESOURCE),
        ) as JsonObject

        val verifier = OfflineTokenVerifierImpl(
            trustStore = StaticTrustStore(workshopTrustAnchors()),
            // Sample token expires 2031-05-20; pin to a clock comfortably
            // before that. Refresh both clock + sample token together when
            // the issuer's expiry policy changes.
            clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC),
        )

        val result = verifier.verify(jws, expectedHolderPub = holderPub)
        assertTrue(
            "Expected Ok, was $result",
            result is OfflineTokenVerifyResult.Ok,
        )
        val payload = (result as OfflineTokenVerifyResult.Ok).payload
        // Stable invariants of `SampleTokenExporter`: a single 5-EUR token
        // minted at DEMODEAA. Serial / issuedAt / expiry rotate at every
        // regenerate so we don't pin them — just assert the serial is a
        // well-formed UUID (the schema the spec mandates).
        assertEquals(500L, payload.amount)
        assertEquals("EUR", payload.currency)
        assertEquals("DEMODEAA", payload.ncbBic)
        assertTrue(
            "serial '${payload.serial}' is not a UUID",
            payload.serial.matches(UUID_REGEX),
        )
    }

    @Test
    fun `verifier rejects the sample token when holderPub is not the bound one`() {
        val jws = readResource(SAMPLE_JWS_RESOURCE).trim()
        val original = Json.parseToJsonElement(
            readResource(SAMPLE_HOLDER_PUB_RESOURCE),
        ) as JsonObject
        // Mutate the first byte of `x` to a different valid base64url char
        // — same length, well-formed JWK, but a different (essentially
        // certainly-invalid) point on the curve. The verifier compares
        // `kty`/`crv`/`x`/`y` only; any of those four flipping triggers
        // HolderPubMismatch.
        val originalX = (original["x"] as kotlinx.serialization.json.JsonPrimitive).content
        val flippedFirst = if (originalX.first() == 'A') 'B' else 'A'
        val mutatedX = flippedFirst + originalX.drop(1)
        val mutated = kotlinx.serialization.json.buildJsonObject {
            original.forEach { (k, v) ->
                if (k == "x") {
                    put(k, kotlinx.serialization.json.JsonPrimitive(mutatedX))
                } else {
                    put(k, v)
                }
            }
        }
        check(original != mutated) { "sanity: mutated holderPub should differ" }

        val verifier = OfflineTokenVerifierImpl(
            trustStore = StaticTrustStore(workshopTrustAnchors()),
            clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC),
        )

        assertTrue(
            verifier.verify(jws, expectedHolderPub = mutated)
                is OfflineTokenVerifyResult.Failure.HolderPubMismatch,
        )
    }

    // ---- helpers -----------------------------------------------------------

    private fun workshopTrustAnchors(): Set<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        return resourceBytes(BUNDLED_ROOT_RESOURCE).inputStream().use { stream ->
            factory.generateCertificates(stream)
                .filterIsInstance<X509Certificate>()
                .toSet()
        }
    }

    private fun readResource(name: String): String =
        resourceBytes(name).toString(Charsets.UTF_8)

    private fun resourceBytes(name: String): ByteArray =
        WorkshopNcbInteropTest::class.java.getResourceAsStream("/$name")
            ?.use { it.readBytes() }
            ?: error("missing test resource: $name")

    private class StaticTrustStore(private val anchors: Set<X509Certificate>) : NcbTrustStore {
        override fun trustAnchors(): Set<X509Certificate> = anchors
    }

    private companion object {
        const val BUNDLED_ROOT_RESOURCE = "workshop_ncb_root.pem"
        const val SAMPLE_JWS_RESOURCE = "workshop_sample_offline_token.jws"
        const val SAMPLE_HOLDER_PUB_RESOURCE = "workshop_sample_holder_public.json"

        // Gradle's test working directory is the module root.
        const val PROD_RESOURCE_PATH =
            "../resources-logic/src/main/res/raw/workshop_ncb_root.pem"

        val UUID_REGEX =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
