/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 */

package eu.europa.ec.defeature.ui.qrconfig

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import eu.europa.ec.uilogic.navigation.StartupScreens
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach

@Composable
fun QrConfigScreen(
    navController: NavController,
    viewModel: QrConfigViewModel,
) {
    val state: State by viewModel.viewState.collectAsStateWithLifecycle()
    Content(
        state = state,
        effectFlow = viewModel.effect,
        onInputChange = { viewModel.setEvent(Event.InputChanged(it)) },
        onSubmit = { viewModel.setEvent(Event.Submit) },
        onNavigationRequested = { effect ->
            when (effect) {
                Effect.Navigation.ToSplash -> {
                    navController.navigate(StartupScreens.Splash.screenRoute) {
                        popUpTo(navController.graph.startDestinationId) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            }
        },
    )
}

@Composable
private fun Content(
    state: State,
    effectFlow: Flow<Effect>,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onNavigationRequested: (Effect.Navigation) -> Unit,
) {
    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Set up Digital Euro wallet",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "Paste or type the URL of the workshop's PID issuer below. " +
                    "On a workshop emulator this is typically http://10.0.2.2:8092.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = state.input,
                onValueChange = onInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("PID issuer URL") },
                placeholder = { Text("https://… or { \"pidIssuerUrl\": \"…\" }") },
                singleLine = true,
                isError = state.error != null,
                enabled = !state.isSubmitting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = state.error?.let {
                    { Text(it.userMessage(), color = MaterialTheme.colorScheme.error) }
                },
            )

            Button(
                onClick = onSubmit,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isSubmitting && state.input.isNotBlank(),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                Text(if (state.isSubmitting) "Saving…" else "Continue")
            }
        }
    }

    LaunchedEffect(Unit) {
        effectFlow.onEach { effect ->
            if (effect is Effect.Navigation) onNavigationRequested(effect)
        }.collect()
    }
}

private fun ConfigError.userMessage(): String = when (this) {
    ConfigError.EMPTY -> "Please enter the PID issuer URL."
    ConfigError.INVALID_URL ->
        "That doesn't look like a valid URL. It should start with http:// or https://."
    ConfigError.MALFORMED_JSON ->
        "The JSON payload is malformed or missing the pidIssuerUrl field."
}
