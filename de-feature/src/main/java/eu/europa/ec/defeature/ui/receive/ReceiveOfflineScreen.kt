/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 */

package eu.europa.ec.defeature.ui.receive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach

@Composable
fun ReceiveOfflineScreen(
    navController: NavController,
    viewModel: ReceiveOfflineViewModel,
) {
    val state: ReceiveState by viewModel.viewState.collectAsStateWithLifecycle()
    Content(
        state = state,
        effectFlow = viewModel.effect,
        onBack = { viewModel.setEvent(ReceiveEvent.Back) },
        onEffect = { effect ->
            when (effect) {
                ReceiveEffect.NavigateBack -> navController.popBackStack()
            }
        },
    )
}

@Composable
private fun Content(
    state: ReceiveState,
    effectFlow: Flow<ReceiveEffect>,
    onBack: () -> Unit,
    onEffect: (ReceiveEffect) -> Unit,
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
                text = "Receive offline (NFC)",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            when (state) {
                ReceiveState.Preparing -> Centered { CircularProgressIndicator() }
                ReceiveState.Listening -> {
                    Text(
                        text = "Hold the sender's phone against this one.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Centered { CircularProgressIndicator() }
                }
                is ReceiveState.OfferReceived -> {
                    Text(
                        text = "Receiving ${formatEuros(state.amountCents)}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    state.description?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    Centered { CircularProgressIndicator() }
                }
                is ReceiveState.Received -> {
                    Text(
                        text = "Received ${state.acceptedSerials.size} token(s) ✅",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = "They're pending sync. Open your bank app's \"Sync received tokens\" within 5 minutes to claim them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 12.dp),
                    ) { Text("Done") }
                }
                is ReceiveState.Failed -> {
                    Text(
                        text = "Receive failed",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 12.dp),
                    ) { Text("Back") }
                }
            }
        }
    }
    LaunchedEffect(Unit) { effectFlow.onEach(onEffect).collect() }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        content()
    }
}

private fun formatEuros(cents: Long): String {
    val whole = cents / 100
    val rem = (cents % 100).toString().padStart(2, '0')
    return "€$whole.$rem"
}
