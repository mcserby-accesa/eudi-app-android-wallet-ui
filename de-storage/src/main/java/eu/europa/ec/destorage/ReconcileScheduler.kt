/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * Drives SimulatedSecureElement.reconcilePending() so OUTGOING_PENDING
 * and INCOMING_PENDING tokens stay in sync with the bank's spent-set
 * without the user having to do anything. Two triggers:
 *
 *  - Network reconnect (ConnectivityManager.onAvailable). Covers the
 *    "user reopens wallet after being offline" case.
 *  - Periodic 20-second poll while at least one pending row exists.
 *    Covers the workshop demo where the phone stays online the whole
 *    time but the recipient's sync hasn't propagated to the payer's
 *    UI yet. Per-pass cost is one HTTP GET per pending serial; the
 *    poll self-stops as soon as storage drains.
 *
 * On each pass, finalised-outgoing details are pushed through
 * [ReconcileEvents.publishDelivered] so the holdings drill-in can
 * render a brief "Delivered EUR X.XX" confirmation before the row
 * vanishes.
 *
 * Wired in Application.onCreate (assembly-logic). Single instance per
 * process.
 */

package eu.europa.ec.destorage

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ReconcileScheduler(
    private val context: Context,
    private val simulatedSecureElement: SimulatedSecureElement,
    private val reconcileEvents: ReconcileEvents,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private var registered = false
    private var pollerJob: Job? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch { runReconcile() }
        }
    }

    /**
     * Idempotent — calling [start] more than once is a no-op so
     * Application.onCreate can register without worrying about
     * configuration-change re-runs.
     */
    fun start() {
        if (registered) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
        registered = true
        startPollerLoop()
    }

    private fun startPollerLoop() {
        if (pollerJob?.isActive == true) return
        pollerJob = scope.launch {
            while (isActive) {
                runCatching {
                    // Only pay the HTTP cost when there's something to reconcile.
                    val held = simulatedSecureElement.listHeldTokens()
                    val hasPending = held.any {
                        it.state == TokenState.OUTGOING_PENDING || it.state == TokenState.INCOMING_PENDING
                    }
                    if (hasPending) runReconcile()
                }
                delay(pollIntervalMs)
            }
        }
    }

    private suspend fun runReconcile() {
        runCatching {
            val result = simulatedSecureElement.reconcilePending()
            if (result.finalisedOutgoingDetails.isNotEmpty()) {
                reconcileEvents.publishDelivered(result.finalisedOutgoingDetails)
            }
        }
    }

    private companion object {
        // 20s — fast enough that a demo presenter sees the row clear within a
        // couple of seconds of recipient sync, slow enough that idle polling
        // (no pending rows) costs essentially zero.
        const val DEFAULT_POLL_INTERVAL_MS: Long = 20_000L
    }
}
