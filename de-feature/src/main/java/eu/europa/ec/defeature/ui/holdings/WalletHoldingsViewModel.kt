/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * "Wallet holdings" drill-in. Reads the simulated SE's current state
 * on enter, refreshes itself whenever the periodic reconcile pass
 * publishes a finalised-outgoing batch, and surfaces a short-lived
 * "Delivered EUR X.XX" confirmation per finalised token so the demo
 * presenter sees positive closure instead of the row silently
 * vanishing.
 *
 * Splits the persisted token set into three buckets so the drill-in
 * can render Live / Sending / Receiving sections per ADR 0011 §7
 * (TokenState).
 */

package eu.europa.ec.defeature.ui.holdings

import androidx.lifecycle.viewModelScope
import eu.europa.ec.destorage.FinalisedOutgoing
import eu.europa.ec.destorage.HeldToken
import eu.europa.ec.destorage.ReconcileEvents
import eu.europa.ec.destorage.SimulatedSecureElement
import eu.europa.ec.destorage.TokenState
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** UI projection of a recently-delivered outgoing token. */
data class DeliveredEntry(
    val serial: String,
    val amountCents: Long,
    val currency: String,
    val deliveredAt: Instant,
)

sealed interface HoldingsState : ViewState {
    data object Loading : HoldingsState
    data class Loaded(
        /** Spendable balance — sum of LIVE tokens only. */
        val liveBalanceCents: Long,
        /** All non-CONSUMED tokens, in their original order. */
        val allTokens: List<HeldToken>,
        val liveTokens: List<HeldToken>,
        val outgoingPending: List<HeldToken>,
        val incomingPending: List<HeldToken>,
        /**
         * Tokens that just transitioned from OUTGOING_PENDING -> CONSUMED
         * via the reconcile pass. Auto-dismissed after [DELIVERED_TTL].
         */
        val recentlyDelivered: List<DeliveredEntry> = emptyList(),
    ) : HoldingsState
}

sealed interface HoldingsEvent : ViewEvent {
    data object Refresh : HoldingsEvent
    data object Back : HoldingsEvent
}

sealed interface HoldingsEffect : ViewSideEffect {
    data object NavigateBack : HoldingsEffect
}

/** Visibility window for the per-row "Delivered" confirmation. */
val DELIVERED_TTL: Duration = Duration.ofMinutes(5)

@KoinViewModel
class WalletHoldingsViewModel(
    private val simulatedSecureElement: SimulatedSecureElement,
    private val reconcileEvents: ReconcileEvents,
) : MviViewModel<HoldingsEvent, HoldingsState, HoldingsEffect>() {

    private val clock: Clock = Clock.systemUTC()

    init {
        viewModelScope.launch {
            reconcileEvents.delivered.collect { batch ->
                onDeliveredBatch(batch)
            }
        }
    }

    override fun setInitialState(): HoldingsState = HoldingsState.Loading

    override fun handleEvents(event: HoldingsEvent) {
        when (event) {
            HoldingsEvent.Refresh -> refresh()
            HoldingsEvent.Back -> setEffect { HoldingsEffect.NavigateBack }
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            val balance = simulatedSecureElement.offlineBalance()
            val tokens = simulatedSecureElement.listHeldTokens()
            val live = tokens.filter { it.state == TokenState.LIVE }
            val outgoing = tokens.filter { it.state == TokenState.OUTGOING_PENDING }
            val incoming = tokens.filter { it.state == TokenState.INCOMING_PENDING }
            val now = clock.instant()
            val carried = (viewState.value as? HoldingsState.Loaded)?.recentlyDelivered.orEmpty()
                .filter { Duration.between(it.deliveredAt, now) <= DELIVERED_TTL }
            setState {
                HoldingsState.Loaded(
                    liveBalanceCents = balance,
                    allTokens = tokens,
                    liveTokens = live,
                    outgoingPending = outgoing,
                    incomingPending = incoming,
                    recentlyDelivered = carried,
                )
            }
        }
    }

    private fun onDeliveredBatch(batch: List<FinalisedOutgoing>) {
        val now = clock.instant()
        val newEntries = batch.map {
            DeliveredEntry(
                serial = it.serial,
                amountCents = it.amount,
                currency = it.currency,
                deliveredAt = now,
            )
        }
        // Merge into existing state (or stash for next refresh) and re-read SE.
        val current = viewState.value
        if (current is HoldingsState.Loaded) {
            val merged = (current.recentlyDelivered + newEntries)
                .distinctBy { it.serial }
                .filter { Duration.between(it.deliveredAt, now) <= DELIVERED_TTL }
            setState { current.copy(recentlyDelivered = merged) }
        }
        // Refresh balances + token lists; reconcile just removed CONSUMED rows.
        refresh()
        scheduleTtlSweep()
    }

    private fun scheduleTtlSweep() {
        viewModelScope.launch {
            delay(DELIVERED_TTL.toMillis())
            val current = viewState.value as? HoldingsState.Loaded ?: return@launch
            val now = clock.instant()
            val pruned = current.recentlyDelivered
                .filter { Duration.between(it.deliveredAt, now) <= DELIVERED_TTL }
            if (pruned.size != current.recentlyDelivered.size) {
                setState { current.copy(recentlyDelivered = pruned) }
            }
        }
    }
}
