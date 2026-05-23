/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * Drives SimulatedSecureElement.reconcilePending() on network reconnect
 * so OUTGOING_PENDING and INCOMING_PENDING tokens past expiry get
 * restored / dropped automatically per ADR 0011 §2.
 *
 * Wired in Application.onCreate (assembly-logic). Single instance per
 * process; no scheduled WorkManager job — the on-reconnect signal is
 * sufficient for the workshop demo (sender / recipient open the wallet
 * on the same device they'll later see the 5-min countdown resolve).
 */

package eu.europa.ec.destorage

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReconcileScheduler(
    private val context: Context,
    private val simulatedSecureElement: SimulatedSecureElement,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch {
                runCatching { simulatedSecureElement.reconcilePending() }
            }
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
    }
}
