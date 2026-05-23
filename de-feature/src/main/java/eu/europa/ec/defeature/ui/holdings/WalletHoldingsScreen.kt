/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * Drill-in screen for the "Wallet holdings" dashboard tile per
 * `specs/components/mobile-wallet.md` §M4a + §M4b/c "Wallet holdings"
 * tile. Lists held offline DE tokens grouped by state — LIVE,
 * Sending (OUTGOING_PENDING), Receiving (INCOMING_PENDING) — and
 * surfaces Send / Receive CTAs to enter the M4b NFC flows.
 *
 * Carries the non-negotiable "Simulated SE — workshop demo. Not a
 * real Secure Element." disclaimer (every simulated-SE surface must).
 */

package eu.europa.ec.defeature.ui.holdings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import eu.europa.ec.destorage.HeldToken
import eu.europa.ec.destorage.TokenState
import eu.europa.ec.uilogic.navigation.DeScreens
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Composable
fun WalletHoldingsScreen(
    navController: NavController,
    viewModel: WalletHoldingsViewModel,
) {
    val state: HoldingsState by viewModel.viewState.collectAsStateWithLifecycle()
    Content(
        state = state,
        effectFlow = viewModel.effect,
        onBack = { viewModel.setEvent(HoldingsEvent.Back) },
        onSend = { navController.navigate(DeScreens.SendOffline.screenRoute) },
        onReceive = { navController.navigate(DeScreens.ReceiveOffline.screenRoute) },
        onEffect = { effect ->
            when (effect) {
                HoldingsEffect.NavigateBack -> navController.popBackStack()
            }
        },
    )

    LaunchedEffect(Unit) {
        viewModel.setEvent(HoldingsEvent.Refresh)
    }
}

@Composable
private fun Content(
    state: HoldingsState,
    effectFlow: Flow<HoldingsEffect>,
    onBack: () -> Unit,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onEffect: (HoldingsEffect) -> Unit,
) {
    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Wallet holdings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )

            when (state) {
                HoldingsState.Loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                is HoldingsState.Loaded -> LoadedBody(state, onSend, onReceive)
            }

            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) { Text("Back") }
        }
    }

    LaunchedEffect(Unit) {
        effectFlow.onEach(onEffect).collect()
    }
}

@Composable
private fun LoadedBody(
    state: HoldingsState.Loaded,
    onSend: () -> Unit,
    onReceive: () -> Unit,
) {
    Text(
        text = formatAmount(state.liveBalanceCents, currency = "EUR"),
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = "${state.liveTokens.size} live tokens · simulated SE — demo only",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onSend,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 12.dp),
            enabled = state.liveBalanceCents > 0,
        ) { Text("Send via NFC") }
        OutlinedButton(
            onClick = onReceive,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) { Text("Receive via NFC") }
    }

    HorizontalDivider()

    if (state.allTokens.isEmpty()) {
        Text(
            text = "No offline DE tokens yet. Use \"Withdraw to wallet\" from your bank app to mint some.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.liveTokens.isNotEmpty()) {
                item("hdr-live") { SectionHeader("Live (spendable)") }
                items(state.liveTokens, key = { "live-${it.serial}" }) { token ->
                    TokenRow(token, badge = null)
                }
            }
            if (state.outgoingPending.isNotEmpty()) {
                item("hdr-out") {
                    SectionHeader("Sending — awaiting recipient sync")
                }
                items(state.outgoingPending, key = { "out-${it.serial}" }) { token ->
                    TokenRow(token, badge = countdownLabel(token.transferExpiry))
                }
            }
            if (state.incomingPending.isNotEmpty()) {
                item("hdr-in") {
                    SectionHeader("Receiving — open your bank app to claim")
                }
                items(state.incomingPending, key = { "in-${it.serial}" }) { token ->
                    TokenRow(token, badge = countdownLabel(token.transferExpiry))
                }
            }
        }
    }

    Text(
        text = "Simulated SE — workshop demo. Not a real Secure Element.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun TokenRow(token: HeldToken, badge: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = formatAmount(token.amount, token.currency),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "serial ${truncateSerial(token.serial)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (badge != null) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

private fun countdownLabel(expiry: Instant?): String {
    if (expiry == null) return "expired"
    val remaining = Duration.between(Clock.systemUTC().instant(), expiry)
    if (remaining.isNegative || remaining.isZero) return "expired"
    val minutes = remaining.toMinutes()
    val seconds = remaining.minusMinutes(minutes).seconds
    return "%02d:%02d left".format(minutes, seconds)
}

private fun truncateSerial(serial: String): String =
    if (serial.length <= 12) serial else "${serial.take(6)}…${serial.takeLast(6)}"

private fun formatAmount(cents: Long, currency: String): String {
    val whole = cents / 100
    val remainder = (cents % 100).toString().padStart(2, '0')
    val symbol = when (currency.uppercase()) { "EUR" -> "€"; else -> "$currency " }
    return "$symbol$whole.$remainder"
}
