/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * Sender-side handshake choreography. Drives one [ApduPipe] through:
 *   1. SELECT-AID                            → expects 9000
 *   2. TRANSFER_OFFER (encoded body)         → expects TransferAck body
 *   3. TRANSFER_COMMIT (tokens + proofs)     → expects TransferOk body
 *
 * Decoupled from [NfcReaderController] so it can be exercised against
 * an in-memory ApduPipe in tests (the HCE service is hard to invoke
 * without a real two-device setup).
 */

package eu.europa.ec.denfc

class OfflineTransferReader {

    suspend fun execute(
        pipe: ApduPipe,
        offer: TransferOffer,
        buildCommit: suspend (TransferAck) -> TransferCommit,
    ): ReaderOutcome {
        // 1. SELECT
        val selectResp = pipe.transceive(buildSelectAidApdu())
        if (!endsWith(selectResp, SW_OK)) {
            return ReaderOutcome.SelectFailed(statusWord(selectResp))
        }

        // 2. OFFER
        val offerApdu = JsonOverApdu.buildCommandApdu(
            ins = INS_TRANSFER_OFFER,
            data = JsonOverApdu.encodeMessage(offer),
        )
        val offerResp = pipe.transceive(offerApdu)
        if (!endsWith(offerResp, SW_OK)) {
            return ReaderOutcome.OfferRejected(statusWord(offerResp), bodyOf(offerResp))
        }
        val ack = runCatching {
            JsonOverApdu.decodeMessage<TransferAck>(bodyOf(offerResp))
        }.getOrElse {
            return ReaderOutcome.OfferAckMalformed
        }
        if (ack.type != TransferAck.TYPE) return ReaderOutcome.OfferAckMalformed

        // 3. COMMIT
        val commit = buildCommit(ack)
        val commitApdu = JsonOverApdu.buildCommandApdu(
            ins = INS_TRANSFER_COMMIT,
            data = JsonOverApdu.encodeMessage(commit),
        )
        val commitResp = pipe.transceive(commitApdu)
        if (!endsWith(commitResp, SW_OK)) {
            return ReaderOutcome.CommitRejected(statusWord(commitResp), bodyOf(commitResp))
        }
        val ok = runCatching {
            JsonOverApdu.decodeMessage<TransferOk>(bodyOf(commitResp))
        }.getOrElse {
            return ReaderOutcome.CommitAckMalformed
        }
        return ReaderOutcome.Ok(acceptedSerials = ok.acceptedSerials)
    }

    private fun endsWith(apdu: ByteArray, sw: ByteArray): Boolean =
        apdu.size >= sw.size && apdu.copyOfRange(apdu.size - sw.size, apdu.size).contentEquals(sw)

    private fun statusWord(apdu: ByteArray): Int =
        if (apdu.size < 2) 0
        else ((apdu[apdu.size - 2].toInt() and 0xff) shl 8) or (apdu[apdu.size - 1].toInt() and 0xff)

    private fun bodyOf(apdu: ByteArray): ByteArray =
        if (apdu.size < 2) ByteArray(0) else apdu.copyOfRange(0, apdu.size - 2)
}

sealed interface ReaderOutcome {
    data class Ok(val acceptedSerials: List<String>) : ReaderOutcome

    data class SelectFailed(val statusWord: Int) : ReaderOutcome
    data class OfferRejected(val statusWord: Int, val body: ByteArray) : ReaderOutcome {
        override fun equals(other: Any?): Boolean = other is OfferRejected &&
            statusWord == other.statusWord && body.contentEquals(other.body)
        override fun hashCode(): Int = 31 * statusWord + body.contentHashCode()
    }
    data object OfferAckMalformed : ReaderOutcome
    data class CommitRejected(val statusWord: Int, val body: ByteArray) : ReaderOutcome {
        override fun equals(other: Any?): Boolean = other is CommitRejected &&
            statusWord == other.statusWord && body.contentEquals(other.body)
        override fun hashCode(): Int = 31 * statusWord + body.contentHashCode()
    }
    data object CommitAckMalformed : ReaderOutcome
}
