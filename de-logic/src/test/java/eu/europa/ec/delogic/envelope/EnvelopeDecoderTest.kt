/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 */

package eu.europa.ec.delogic.envelope

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

class EnvelopeDecoderTest {

    @Test
    fun `decodes a well-formed top-up envelope`() {
        val raw = """
            {
              "type": "topUp",
              "amount": 5000,
              "currency": "EUR",
              "paymentRef": "11111111-2222-3333-4444-555555555555",
              "expiry": "2030-01-01T00:00:00Z",
              "payer": { "iban": "DE89370400440532013000", "holderName": "Mihai Test" },
              "payee": null,
              "bic": "DEMODEAA",
              "bankDisplayName": "Bank A",
              "description": "Top up €50.00 from your account at Bank A"
            }
        """.trimIndent()

        val result = decoderAt("2026-01-01T00:00:00Z").decode(asBase64Url(raw))

        assertTrue(result is EnvelopeDecodeResult.Success)
        val envelope = (result as EnvelopeDecodeResult.Success).envelope
        assertEquals(OperationType.TOP_UP, envelope.type)
        assertEquals(5000L, envelope.amount)
        assertEquals("DEMODEAA", envelope.bic)
        assertEquals("Bank A", envelope.bankDisplayName)
    }

    @Test
    fun `bankDisplayName is optional`() {
        val raw = topUpJson(extra = ""","description":"Top up €50"""", omitDisplayName = true)
        val result = decoderAt("2026-01-01T00:00:00Z").decode(asBase64Url(raw))
        assertTrue(result is EnvelopeDecodeResult.Success)
        assertEquals(null, (result as EnvelopeDecodeResult.Success).envelope.bankDisplayName)
    }

    @Test
    fun `payee is required for payment-type operations`() {
        val raw = """
            {
              "type": "payment",
              "amount": 1000,
              "currency": "EUR",
              "paymentRef": "11111111-2222-3333-4444-555555555555",
              "expiry": "2030-01-01T00:00:00Z",
              "payer": { "iban": "DE89", "holderName": "X" },
              "bic": "DEMODEAA",
              "description": "Pay something"
            }
        """.trimIndent()

        val result = decoderAt("2026-01-01T00:00:00Z").decode(asBase64Url(raw))
        assertEquals(EnvelopeDecodeResult.Failure.Malformed, result)
    }

    @Test
    fun `decodes a well-formed payment envelope with full payee block`() {
        val raw = paymentJson()
        val result = decoderAt("2026-01-01T00:00:00Z").decode(asBase64Url(raw))
        assertTrue(result is EnvelopeDecodeResult.Success)
        val envelope = (result as EnvelopeDecodeResult.Success).envelope
        assertEquals(OperationType.PAYMENT, envelope.type)
        assertEquals("merchant-uuid-1234", envelope.payee?.merchantId)
        assertEquals("MediaMarkt Saturn", envelope.payee?.merchantName)
        assertEquals("Bluetooth headphones", envelope.payee?.description)
    }

    @Test
    fun `payment envelope accepts a payee without optional description`() {
        val raw = paymentJson(payeeDescription = null)
        val result = decoderAt("2026-01-01T00:00:00Z").decode(asBase64Url(raw))
        assertTrue(result is EnvelopeDecodeResult.Success)
        assertEquals(null, (result as EnvelopeDecodeResult.Success).envelope.payee?.description)
    }

    @Test
    fun `payment envelope rejects payee with blank merchantId`() {
        val raw = paymentJson(merchantId = "")
        val result = decoderAt("2026-01-01T00:00:00Z").decode(asBase64Url(raw))
        assertEquals(EnvelopeDecodeResult.Failure.Malformed, result)
    }

    @Test
    fun `payment envelope rejects payee with blank merchantName`() {
        val raw = paymentJson(merchantName = "")
        val result = decoderAt("2026-01-01T00:00:00Z").decode(asBase64Url(raw))
        assertEquals(EnvelopeDecodeResult.Failure.Malformed, result)
    }

    @Test
    fun `payment envelope rejects payee missing merchantId field entirely`() {
        // The Payee data class requires merchantId; serialization fails closed
        // when the field is absent.
        val raw = """
            {
              "type": "payment",
              "amount": 1000,
              "currency": "EUR",
              "paymentRef": "11111111-2222-3333-4444-555555555555",
              "expiry": "2030-01-01T00:00:00Z",
              "payer": { "iban": "DE89", "holderName": "X" },
              "payee": { "merchantName": "M", "description": "d" },
              "bic": "DEMODEAA",
              "description": "Pay something"
            }
        """.trimIndent()
        val result = decoderAt("2026-01-01T00:00:00Z").decode(asBase64Url(raw))
        assertEquals(EnvelopeDecodeResult.Failure.Malformed, result)
    }

    @Test
    fun `decodes a well-formed withdrawToWallet envelope`() {
        val raw = withdrawJson()
        val result = decoderAt("2026-01-01T00:00:00Z").decode(asBase64Url(raw))
        assertTrue(result is EnvelopeDecodeResult.Success)
        val envelope = (result as EnvelopeDecodeResult.Success).envelope
        assertEquals(OperationType.WITHDRAW_TO_WALLET, envelope.type)
        assertEquals(true, envelope.holderPubRequest)
        // The wallet — not the bank-app — is the source of truth for holderPub.
        // On the inbound envelope the field MUST be null; the wallet fills it
        // in before signing.
        assertEquals(null, envelope.holderPub)
    }

    @Test
    fun `withdrawToWallet envelope requires holderPubRequest = true`() {
        val raw = withdrawJson(holderPubRequest = false)
        val result = decoderAt("2026-01-01T00:00:00Z").decode(asBase64Url(raw))
        assertEquals(EnvelopeDecodeResult.Failure.Malformed, result)
    }

    @Test
    fun `withdrawToWallet envelope rejects bank-app supplied holderPub`() {
        // The wallet is the only legitimate source of `holderPub`. A bank-app
        // sending one in the inbound envelope is either confused or hostile —
        // either way fail closed so the user is not asked to authorise a key
        // they did not generate.
        val raw = withdrawJson(extraField = ""","holderPub": { "kty":"EC","crv":"P-256","x":"a","y":"b" }""")
        val result = decoderAt("2026-01-01T00:00:00Z").decode(asBase64Url(raw))
        assertEquals(EnvelopeDecodeResult.Failure.Malformed, result)
    }

    @Test
    fun `rejects malformed base64url`() {
        val result = decoderAt("2026-01-01T00:00:00Z").decode("!!!not base64url!!!")
        assertEquals(EnvelopeDecodeResult.Failure.Malformed, result)
    }

    @Test
    fun `rejects unknown fields strictly`() {
        // The protocol spec is a closed schema for v1 — unknown fields suggest a bank
        // app on a newer protocol. Fail closed so the user is not asked to authorise
        // something the wallet might be misrendering.
        val raw = """
            {
              "type": "topUp",
              "amount": 100,
              "currency": "EUR",
              "paymentRef": "x",
              "expiry": "2030-01-01T00:00:00Z",
              "payer": { "iban": "DE", "holderName": "X" },
              "bic": "B",
              "description": "d",
              "unexpectedField": "value"
            }
        """.trimIndent()

        val result = decoderAt("2026-01-01T00:00:00Z").decode(asBase64Url(raw))
        assertEquals(EnvelopeDecodeResult.Failure.Malformed, result)
    }

    @Test
    fun `rejects unknown operation type`() {
        val raw = topUpJson(typeOverride = "transfer")
        val result = decoderAt("2026-01-01T00:00:00Z").decode(asBase64Url(raw))
        assertEquals(EnvelopeDecodeResult.Failure.Malformed, result)
    }

    @Test
    fun `rejects non-positive amount`() {
        val zero = topUpJson(amountOverride = 0)
        assertEquals(
            EnvelopeDecodeResult.Failure.Malformed,
            decoderAt("2026-01-01T00:00:00Z").decode(asBase64Url(zero)),
        )
        val negative = topUpJson(amountOverride = -50)
        assertEquals(
            EnvelopeDecodeResult.Failure.Malformed,
            decoderAt("2026-01-01T00:00:00Z").decode(asBase64Url(negative)),
        )
    }

    @Test
    fun `rejects expired envelope at or before now`() {
        val raw = topUpJson(expiryOverride = "2025-12-31T23:59:59Z")
        val result = decoderAt("2026-01-01T00:00:00Z").decode(asBase64Url(raw))
        assertEquals(EnvelopeDecodeResult.Failure.Expired, result)
    }

    @Test
    fun `rejects unparseable expiry`() {
        val raw = topUpJson(expiryOverride = "not-a-timestamp")
        val result = decoderAt("2026-01-01T00:00:00Z").decode(asBase64Url(raw))
        assertEquals(EnvelopeDecodeResult.Failure.Malformed, result)
    }

    private fun decoderAt(now: String) = EnvelopeDecoder(
        clock = Clock.fixed(Instant.parse(now), ZoneOffset.UTC),
        json = Json { ignoreUnknownKeys = false; isLenient = false },
    )

    private fun asBase64Url(raw: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))

    @Suppress("LongParameterList")
    private fun topUpJson(
        typeOverride: String = "topUp",
        amountOverride: Long = 5000L,
        expiryOverride: String = "2030-01-01T00:00:00Z",
        omitDisplayName: Boolean = false,
        extra: String = "",
    ): String {
        val displayName = if (omitDisplayName) "" else ""","bankDisplayName":"Bank A""""
        return """
            {
              "type": "$typeOverride",
              "amount": $amountOverride,
              "currency": "EUR",
              "paymentRef": "11111111-2222-3333-4444-555555555555",
              "expiry": "$expiryOverride",
              "payer": { "iban": "DE89370400440532013000", "holderName": "Mihai Test" },
              "bic": "DEMODEAA"$displayName,
              "description": "Top up €50.00"$extra
            }
        """.trimIndent()
    }

    private fun withdrawJson(
        holderPubRequest: Boolean = true,
        extraField: String = "",
    ): String = """
        {
          "type": "withdrawToWallet",
          "amount": 2500,
          "currency": "EUR",
          "paymentRef": "11111111-2222-3333-4444-555555555555",
          "expiry": "2030-01-01T00:00:00Z",
          "payer": { "iban": "DE89370400440532013000", "holderName": "Mihai Test" },
          "bic": "DEMODEAA",
          "bankDisplayName": "Bank A",
          "description": "Withdraw €25.00 from your account onto this device",
          "holderPubRequest": $holderPubRequest$extraField
        }
    """.trimIndent()

    private fun paymentJson(
        merchantId: String = "merchant-uuid-1234",
        merchantName: String = "MediaMarkt Saturn",
        payeeDescription: String? = "Bluetooth headphones",
    ): String {
        val description = payeeDescription?.let { ""","description":"$it"""" } ?: ""
        return """
            {
              "type": "payment",
              "amount": 2345,
              "currency": "EUR",
              "paymentRef": "11111111-2222-3333-4444-555555555555",
              "expiry": "2030-01-01T00:00:00Z",
              "payer": { "iban": "DE89370400440532013000", "holderName": "Mihai Test" },
              "payee": {
                "merchantId": "$merchantId",
                "merchantName": "$merchantName"$description
              },
              "bic": "DEMODEAA",
              "bankDisplayName": "Bank A",
              "description": "Pay MediaMarkt Saturn €23.45"
            }
        """.trimIndent()
    }
}
