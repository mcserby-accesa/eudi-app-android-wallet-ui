/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * Sender-side helper. The send-offline screen owns this controller
 * for the duration of one tap: it puts the device into NFC reader
 * mode, drives the SELECT-AID → TRANSFER_OFFER → TRANSFER_COMMIT
 * sequence over [IsoDep], and surfaces a typed outcome.
 *
 * Lifecycle: a single [run] call covers one tap. The Activity-scoped
 * caller passes its own [android.app.Activity] and listens for the
 * suspending result. PR25 wires this into the SendOfflineViewModel.
 */

package eu.europa.ec.denfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class NfcReaderController {

    /**
     * Put [activity] into reader mode, wait for the first IsoDep tag,
     * run the handshake described by [exchange], then leave reader mode.
     * Returns the result of [exchange] or null on timeout / NFC unavailable.
     *
     * [timeoutMs] guards against a sender staying in reader mode
     * indefinitely — workshop default 30 seconds.
     */
    suspend fun <T> run(
        activity: Activity,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        exchange: suspend (ApduPipe) -> T,
    ): T? {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return null
        val tagDeferred = CompletableDeferred<Tag>()

        val callback = NfcAdapter.ReaderCallback { tag -> tagDeferred.complete(tag) }
        adapter.enableReaderMode(
            activity,
            callback,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            /* extras = */ null,
        )

        return try {
            val tag = withTimeoutOrNull(timeoutMs) { tagDeferred.await() } ?: return null
            val isoDep = IsoDep.get(tag) ?: return null
            withContext(Dispatchers.IO) {
                isoDep.connect()
                try {
                    exchange(IsoDepPipe(isoDep))
                } finally {
                    runCatching { isoDep.close() }
                }
            }
        } finally {
            runCatching { adapter.disableReaderMode(activity) }
        }
    }

    private class IsoDepPipe(private val isoDep: IsoDep) : ApduPipe {
        override suspend fun transceive(apdu: ByteArray): ByteArray =
            withContext(Dispatchers.IO) { isoDep.transceive(apdu) }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 30_000L
    }
}

/**
 * Thin transceive surface so the handshake choreography can be unit-
 * tested with a fake pipe without standing up a real IsoDep tag.
 */
fun interface ApduPipe {
    suspend fun transceive(apdu: ByteArray): ByteArray
}
