/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * Coordination point between the Compose "Receive offline" screen and
 * the platform [OfflineTransferHostApduService]. The HCE service runs
 * in the same process as the rest of the wallet but is invoked by
 * Android's NFC dispatcher — it doesn't share lifecycle with the
 * Activity, so we use a process-wide singleton registry.
 *
 * Activation gate: the HCE service rejects every APDU unless the
 * receive-offline ViewModel has installed an active [NfcTransferSession]
 * via [NfcTransferSessionRegistry.set]. This is what makes the wallet
 * not accept random NFC taps while the receive screen is closed.
 */

package eu.europa.ec.denfc

/**
 * Per-tap callback wallet-side. The HCE service hands the parsed
 * [TransferOffer] off via [onOfferReceived], gets back a [TransferAck]
 * with the recipient's holderPub, then forwards [TransferCommit]'s
 * tokens + proofs via [onCommitReceived] and returns the result.
 *
 * All methods are suspend so the implementer can integrate with the
 * ViewModel coroutine scope (biometric prompt, SE writes, UI updates).
 */
interface NfcTransferSession {
    suspend fun onOfferReceived(offer: TransferOffer): TransferAck
    suspend fun onCommitReceived(commit: TransferCommit): CommitOutcome
    /** Called when the NFC link drops mid-handshake or after a final response. */
    fun onSessionEnded()
}

/** Outcome of a [TransferCommit] applied to the SE. */
sealed interface CommitOutcome {
    data class Ok(val acceptedSerials: List<String>) : CommitOutcome
    data class Rejected(val reason: String, val rejectedSerials: List<String> = emptyList()) :
        CommitOutcome
}

/**
 * Process-wide singleton. The receive-offline ViewModel calls
 * [set] in `onStart` (or when the user opens the screen) and [clear]
 * in `onStop`. The HCE service reads via [current] on every APDU.
 *
 * Synchronisation: writes from the UI thread, reads from Android's NFC
 * binder thread. A simple volatile reference is enough — the session
 * object itself owns its coroutine context.
 */
object NfcTransferSessionRegistry {

    @Volatile
    private var active: NfcTransferSession? = null

    fun set(session: NfcTransferSession) {
        active = session
    }

    fun clear(session: NfcTransferSession? = null) {
        // If a specific session is named, only clear if it's still the
        // active one — guards against a stale clear() from an old
        // ViewModel racing against a fresh set() from a new one.
        if (session == null || active === session) {
            active = null
        }
    }

    fun current(): NfcTransferSession? = active
}
