/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 */

package eu.europa.ec.defeature.ui.send

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach

@Composable
fun SendOfflineScreen(
    navController: NavController,
    viewModel: SendOfflineViewModel,
) {
    val state: SendState by viewModel.viewState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Content(
        state = state,
        effectFlow = viewModel.effect,
        onAmountChanged = { viewModel.setEvent(SendEvent.AmountChanged(it)) },
        onStartTap = {
            (context as? Activity)?.let { viewModel.setEvent(SendEvent.StartTap(it)) }
        },
        onCancel = { viewModel.setEvent(SendEvent.Cancel) },
        onDismissError = { viewModel.setEvent(SendEvent.DismissError) },
        onEffect = { effect ->
            when (effect) {
                SendEffect.NavigateBack -> navController.popBackStack()
            }
        },
    )
}

@Composable
private fun Content(
    state: SendState,
    effectFlow: Flow<SendEffect>,
    onAmountChanged: (String) -> Unit,
    onStartTap: () -> Unit,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
    onEffect: (SendEffect) -> Unit,
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
                text = "Send offline (NFC)",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Hold this phone close to the recipient's phone after you tap Send.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (state) {
                SendState.Loading -> Loading()
                is SendState.Idle -> Idle(state, onAmountChanged, onStartTap)
                is SendState.Tapping -> Tapping(state)
                is SendState.Sent -> Sent(state, onCancel)
                is SendState.Failed -> Failed(state, onDismissError, onCancel)
            }
        }
    }

    LaunchedEffect(Unit) { effectFlow.onEach(onEffect).collect() }
}

@Composable private fun Loading() {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable private fun Idle(
    state: SendState.Idle,
    onAmountChanged: (String) -> Unit,
    onStartTap: () -> Unit,
) {
    Text(
        text = "Spendable balance: ${formatEuros(state.liveBalanceCents)}",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = state.amountInput,
        onValueChange = onAmountChanged,
        label = { Text("Amount in cents (e.g. 500 = €5)") },
        singleLine = true,
        isError = state.errorMessage != null,
        supportingText = state.errorMessage?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = onStartTap,
        modifier = Modifier.fillMaxWidth(),
        enabled = state.amountInput.isNotBlank(),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) { Text("Tap to send") }
}

@Composable private fun Tapping(state: SendState.Tapping) {
    Text(
        text = "Hold the phones together…",
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        text = "Sending ${formatEuros(state.amountCents)} via NFC.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable private fun Sent(state: SendState.Sent, onDone: () -> Unit) {
    Text(
        text = "Sent ${formatEuros(state.amountCents)} 🎉",
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(
        text = "${state.serials.size} token(s) marked as Sending. They'll finalise after the recipient syncs (within 5 minutes), or roll back to your spendable balance.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = onDone,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) { Text("Done") }
}

@Composable private fun Failed(
    state: SendState.Failed,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    Text(
        text = "Send failed",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.error,
    )
    Text(
        text = state.message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(
        onClick = onRetry,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) { Text("Try again") }
    OutlinedButton(
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) { Text("Cancel") }
}

private fun formatEuros(cents: Long): String {
    val whole = cents / 100
    val rem = (cents % 100).toString().padStart(2, '0')
    return "€$whole.$rem"
}
