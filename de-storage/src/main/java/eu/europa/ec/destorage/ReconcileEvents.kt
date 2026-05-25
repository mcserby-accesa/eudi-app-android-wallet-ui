/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * Process-wide publisher for reconcile-pass events that UI surfaces
 * need to react to in real time. Today there's only one: a list of
 * just-finalised OUTGOING_PENDING tokens, so the holdings drill-in
 * can render a "Delivered" confirmation instead of letting the row
 * silently disappear when the bank confirms SPENT.
 *
 * Wired in DI as a singleton; written to by [ReconcileScheduler],
 * read by [WalletHoldingsViewModel].
 */

package eu.europa.ec.destorage

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class ReconcileEvents {

    // replay = 1 so a UI screen opened just after the reconcile pass
    // still picks up the most recent batch. extraBufferCapacity gives
    // us slack so emit() never suspends.
    private val _delivered = MutableSharedFlow<List<FinalisedOutgoing>>(
        replay = 1,
        extraBufferCapacity = 4,
    )

    /** Batches of OUTGOING_PENDING tokens that just transitioned to CONSUMED. */
    val delivered: SharedFlow<List<FinalisedOutgoing>> = _delivered.asSharedFlow()

    fun publishDelivered(batch: List<FinalisedOutgoing>) {
        if (batch.isEmpty()) return
        _delivered.tryEmit(batch)
    }
}
