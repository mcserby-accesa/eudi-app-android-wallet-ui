/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * Drives the sender-side handshake through an in-memory [ApduPipe]
 * stand-in for IsoDep, paired with a fake recipient that mimics the
 * HCE service's behaviour. Lets us exercise the choreography (and
 * the error branches) without standing up two real devices.
 */

package eu.europa.ec.denfc

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineTransferReaderTest {

    private val recipientJwk: JsonObject = buildJsonObject {
        put("kty", "EC")
        put("crv", "P-256")
        put("x", "abc")
        put("y", "def")
    }

    private val offer = TransferOffer(
        amount = 5000,
        currency = "EUR",
        tokens = listOf(TokenOfferRow("uuid-1", 5000, "https://bank-a.example")),
    )

    @Test
    fun `happy-path SELECT - OFFER - COMMIT returns Ok with accepted serials`() = runTest {
        val recipient = FakeRecipient(
            ackOnOffer = TransferAck(recipientHolderPub = recipientJwk),
            okOnCommit = TransferOk(acceptedSerials = listOf("uuid-1")),
        )

        val result = OfflineTransferReader().execute(
            pipe = recipient,
            offer = offer,
            buildCommit = { ack ->
                assertEquals(recipientJwk, ack.recipientHolderPub)
                TransferCommit(
                    tokens = listOf(TokenCommitRow("uuid-1", 5000, "EUR", "tok.jws")),
                    transferProofs = listOf(TransferProofCommitRow("uuid-1", "proof.jws")),
                )
            },
        )

        assertEquals(ReaderOutcome.Ok(acceptedSerials = listOf("uuid-1")), result)
        // Choreography order: SELECT, OFFER, COMMIT.
        assertEquals(listOf(0xA4, 0x01, 0x02), recipient.seenInstructions.map { it.toInt() and 0xff })
    }

    @Test
    fun `recipient SELECT failure surfaces SelectFailed`() = runTest {
        val pipe = FakeRecipient(
            ackOnOffer = TransferAck(recipientHolderPub = recipientJwk),
            okOnCommit = TransferOk(acceptedSerials = emptyList()),
            selectResponse = SW_CONDITIONS_NOT_SATISFIED,
        )
        val result = OfflineTransferReader().execute(
            pipe = pipe,
            offer = offer,
            buildCommit = { TransferCommit(tokens = emptyList(), transferProofs = emptyList()) },
        )
        assertTrue(result is ReaderOutcome.SelectFailed)
        assertEquals(0x6985, (result as ReaderOutcome.SelectFailed).statusWord)
    }

    @Test
    fun `offer rejected surfaces OfferRejected with body`() = runTest {
        val rejectBody = JsonOverApdu.encodeMessage(TransferReject(reason = "no_active_session"))
        val pipe = FakeRecipient(
            ackOnOffer = TransferAck(recipientHolderPub = recipientJwk),
            okOnCommit = TransferOk(acceptedSerials = emptyList()),
            offerResponseOverride = rejectBody + SW_CONDITIONS_NOT_SATISFIED,
        )
        val result = OfflineTransferReader().execute(
            pipe = pipe,
            offer = offer,
            buildCommit = { TransferCommit(tokens = emptyList(), transferProofs = emptyList()) },
        )
        assertTrue(result is ReaderOutcome.OfferRejected)
    }

    @Test
    fun `malformed ack body returns OfferAckMalformed`() = runTest {
        val pipe = FakeRecipient(
            ackOnOffer = TransferAck(recipientHolderPub = recipientJwk),
            okOnCommit = TransferOk(acceptedSerials = emptyList()),
            offerResponseOverride = "not-json".toByteArray() + SW_OK,
        )
        val result = OfflineTransferReader().execute(
            pipe = pipe,
            offer = offer,
            buildCommit = { TransferCommit(tokens = emptyList(), transferProofs = emptyList()) },
        )
        assertEquals(ReaderOutcome.OfferAckMalformed, result)
    }

    /**
     * In-memory ApduPipe that plays the role of the HCE service for
     * testing the sender-side choreography. Records every INS byte
     * for ordering assertions.
     */
    private class FakeRecipient(
        private val ackOnOffer: TransferAck,
        private val okOnCommit: TransferOk,
        private val selectResponse: ByteArray = SW_OK,
        private val offerResponseOverride: ByteArray? = null,
        private val commitResponseOverride: ByteArray? = null,
    ) : ApduPipe {
        val seenInstructions = mutableListOf<Byte>()

        override suspend fun transceive(apdu: ByteArray): ByteArray {
            seenInstructions += apdu[1]
            return when {
                isSelectAid(apdu) -> selectResponse
                apdu[1] == INS_TRANSFER_OFFER ->
                    offerResponseOverride ?: (JsonOverApdu.encodeMessage(ackOnOffer) + SW_OK)
                apdu[1] == INS_TRANSFER_COMMIT ->
                    commitResponseOverride ?: (JsonOverApdu.encodeMessage(okOnCommit) + SW_OK)
                else -> SW_FILE_NOT_FOUND
            }
        }
    }
}
