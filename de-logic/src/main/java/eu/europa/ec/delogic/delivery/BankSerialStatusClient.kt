/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * Wallet-direct call to the bank's anonymous serial-status proxy per
 * ADR 0011 §8 (services-side commit 5202942). The bank forwards to NCB
 * and returns the same `{status, spentAt?, eventId?}` shape. Anonymous
 * — the path's UUID-v4 serial is the only identifier; an attacker
 * learns one bit per query and can't enumerate.
 *
 * The wallet stays bank-agnostic: the base URL arrives at runtime, per
 * token, on the M4a deliver response (`reconciliationUrl`) or in the
 * NFC `transferOffer` (per-token reconciliationUrl). The wallet never
 * caches a bank URL outside of per-token rows in the simulated SE.
 */

package eu.europa.ec.delogic.delivery

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Three-state spent-set lookup result. */
enum class SerialStatus {
    /** Token has been redeemed at NCB. */
    SPENT,

    /** Token has not been redeemed (yet). */
    UNSPENT,

    /**
     * Network error or unexpected response shape; caller treats as a
     * temporary failure and retries on next reconcile pass. Never
     * triggers a state transition.
     */
    UNKNOWN,
}

/**
 * Pluggable lookup so callers (and tests) can substitute the HTTP
 * client. Wired in DI via [BankSerialStatusClient].
 */
fun interface SerialStatusLookup {
    suspend fun status(reconciliationUrl: String, serial: String): SerialStatus
}

class BankSerialStatusClient(private val httpClient: HttpClient) : SerialStatusLookup {

    /**
     * `GET <reconciliationUrl>/bank/de/offline/serial-status/{serial}`.
     * No auth. Returns [SerialStatus.UNKNOWN] for any 4xx / 5xx / network
     * failure — the reconcile job will retry next time the device comes
     * online, so a transient bank outage never spuriously rolls back a
     * pending token.
     */
    override suspend fun status(reconciliationUrl: String, serial: String): SerialStatus =
        runCatching {
            val response: HttpResponse = httpClient.get(reconciliationUrl) {
                url {
                    appendPathSegments("bank", "de", "offline", "serial-status", serial)
                }
            }
            when (response.status) {
                HttpStatusCode.OK -> {
                    val body = runCatching { response.body<SerialStatusResponse>() }
                        .getOrElse { return@runCatching SerialStatus.UNKNOWN }
                    when (body.status.uppercase()) {
                        "SPENT" -> SerialStatus.SPENT
                        "UNSPENT" -> SerialStatus.UNSPENT
                        else -> SerialStatus.UNKNOWN
                    }
                }
                else -> SerialStatus.UNKNOWN
            }
        }.getOrDefault(SerialStatus.UNKNOWN)
}

@Serializable
private data class SerialStatusResponse(
    val status: String,
    @SerialName("spentAt") val spentAt: String? = null,
    @SerialName("eventId") val eventId: String? = null,
)
